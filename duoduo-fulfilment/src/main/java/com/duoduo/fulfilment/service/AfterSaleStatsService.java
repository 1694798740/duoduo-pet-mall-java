package com.duoduo.fulfilment.service;

import com.duoduo.common.exception.BizException;
import com.duoduo.fulfilment.dto.AfterSaleStatsDtos.TenantAfterSaleStat;
import com.duoduo.fulfilment.dto.AfterSaleStatsDtos.TenantAfterSaleStats;
import com.duoduo.fulfilment.mapper.AfterSaleMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * E24 · 售后聚合（供交易域的租户健康度面板跨域读取）。
 *
 * <p>本服务只有一件职责：把「某个时间窗内每个卖家出了多少售后」变成一次 GROUP BY。
 * 不做比例、不做判级、不认识"健康度" —— 那些都在交易域（它才同时看得到订单量与接口指标）。
 *
 * <p>与 {@code AfterSaleService} 分开成两个类，是因为这一份**不参与任何业务状态机**：
 * 它是纯只读的巡检视图。混在一起会让人误以为它受售后权限体系约束，
 * 而它的真实约束是「不经网关 + 服务间签名」（见 {@code InternalTokenFilter}）。
 */
@Service
public class AfterSaleStatsService {

    private final AfterSaleMapper afterSaleMapper;

    public AfterSaleStatsService(AfterSaleMapper afterSaleMapper) {
        this.afterSaleMapper = afterSaleMapper;
    }

    /**
     * 按卖家聚合窗口内的售后。
     *
     * @param fromIso 窗口起点（UTC，{@code yyyy-MM-ddTHH:mm:ss}）
     * @param toIso   窗口终点（UTC，**开区间**）
     * @throws BizException 400 —— 时间格式非法或 {@code to <= from}。
     *                      刻意把非法窗口判成 400 而不是"自行纠正"：
     *                      静默纠正一个写错的窗口，会让调用方拿着一个不是自己要求的数字去做判断，
     *                      而这类错误在面板上表现为"这几天售后率怎么这么低"，没有任何报错。
     */
    public TenantAfterSaleStats tenantStats(String fromIso, String toIso) {
        LocalDateTime from = parse(fromIso, "from");
        LocalDateTime to = parse(toIso, "to");
        if (!to.isAfter(from)) {
            throw BizException.validation("窗口非法：to 必须晚于 from（收到 " + fromIso + " → " + toIso + "）");
        }

        List<Map<String, Object>> raw = afterSaleMapper.tenantStats(from, to);
        List<TenantAfterSaleStat> rows = new ArrayList<>();
        for (Map<String, Object> row : raw) {
            Long sellerId = lng(row, "sellerId");
            if (sellerId == null) {
                // seller_id 是 NOT NULL 列，真出现 null 说明有人手工改过库；
                // 跳过而不是算到 0 号租户（自营）账上 —— 自营是一个真实租户，
                // 把脏数据混进去会让自营的健康度看起来变差。
                continue;
            }
            rows.add(new TenantAfterSaleStat(
                    sellerId,
                    orZero(lng(row, "total")),
                    orZero(lng(row, "refunded")),
                    orZero(lng(row, "openCnt")),
                    orZero(lng(row, "adverse")),
                    orZero(lng(row, "refundAmount"))));
        }
        return new TenantAfterSaleStats(from.toString(), to.toString(), rows);
    }

    private static LocalDateTime parse(String raw, String name) {
        if (raw == null || raw.isBlank()) {
            throw BizException.validation("缺少窗口参数 " + name + "（UTC，形如 2026-09-13T00:00:00）");
        }
        try {
            return LocalDateTime.parse(raw.trim());
        } catch (java.time.format.DateTimeParseException ex) {
            throw BizException.validation(name + " 时间格式应为 yyyy-MM-ddTHH:mm:ss（UTC），收到：" + raw);
        }
    }

    /**
     * 列名大小写容错取数。
     *
     * <p>{@code @Select} 里写了驼峰别名（{@code AS sellerId}），但不同 JDBC 驱动 / 数据库
     * 可能回传 {@code SELLERID} 或按原样大小写。本项目已经因为这一点栽过一次
     * （见 {@code AdminOpsService#str}：「列名大小写容错……写死会全零」）。
     * 这里统一容错，而不是在每个调用点各写一遍。
     */
    private static Long lng(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (value == null) {
            value = row.get(column.toUpperCase(java.util.Locale.ROOT));
        }
        if (value == null) {
            return null;
        }
        return value instanceof Number number ? number.longValue() : null;
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }
}
