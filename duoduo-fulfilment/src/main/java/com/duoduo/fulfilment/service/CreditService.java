package com.duoduo.fulfilment.service;

import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.fulfilment.client.OrderClient;
import com.duoduo.fulfilment.client.UserClient;
import com.duoduo.fulfilment.client.dto.CreditWriteRequest;
import com.duoduo.fulfilment.client.dto.CreditWriteResult;
import com.duoduo.fulfilment.client.dto.SellerOrderStats;
import com.duoduo.fulfilment.dto.CreditDtos.CreditFactor;
import com.duoduo.fulfilment.dto.CreditDtos.CreditInputs;
import com.duoduo.fulfilment.dto.CreditDtos.CreditRefreshResult;
import com.duoduo.fulfilment.dto.CreditDtos.CreditView;
import com.duoduo.fulfilment.mapper.AfterSaleMapper;
import com.duoduo.fulfilment.mapper.ReviewMapper;
import com.duoduo.fulfilment.support.CreditModel;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 信用分服务（A4）。
 *
 * <p><b>职责边界</b>：本类只做「取事实 → 交给 {@link CreditModel} 算 → 组装视图 / 回写」。
 * 算法在 {@code CreditModel}（纯函数、可单测、改动面唯一），
 * 权重来自配置（启动即校验），事实来自本域两张表 + 交易域一个内部计数接口。
 *
 * <h2>三个刻意的取舍</h2>
 * <ol>
 *   <li><b>读接口实时计算，不读 t_user 的缓存值</b>。{@code t_user.credit_score} 是给
 *       「拿不到履约域的场景」用的副本；信用分页面本身走实时计算，
 *       避免出现「库里还是 100、页面已经 82」的疑问。回写是为了让副本跟上，不是为了展示。</li>
 *   <li><b>交易域不可用 → 503，不当成「0 单」</b>。把依赖故障算成「这个卖家没有完成过订单」，
 *       会凭空压低别人的信用分，且用户完全无法察觉（分数本来就是个数字）。
 *       宁可显示「暂不可用」，也不给一个错的数（与 {@code OrderFacts} 同一原则）。</li>
 *   <li><b>评价 / 售后取本域数据，绝不跨库</b>。数据在哪就落哪（INV-3 数据边界）。</li>
 * </ol>
 */
@Service
public class CreditService {

    private static final Logger log = LoggerFactory.getLogger(CreditService.class);

    private final OrderClient orderClient;
    private final UserClient userClient;
    private final ReviewMapper reviewMapper;
    private final AfterSaleMapper afterSaleMapper;
    private final CreditModel.Weights weights;
    private final String note;

    public CreditService(OrderClient orderClient,
                         UserClient userClient,
                         ReviewMapper reviewMapper,
                         AfterSaleMapper afterSaleMapper,
                         @Value("${duoduo.credit.base:60}") int base,
                         @Value("${duoduo.credit.weight-completion:18}") int weightCompletion,
                         @Value("${duoduo.credit.weight-review:16}") int weightReview,
                         @Value("${duoduo.credit.weight-aftersale:6}") int weightAftersale) {
        this.orderClient = orderClient;
        this.userClient = userClient;
        this.reviewMapper = reviewMapper;
        this.afterSaleMapper = afterSaleMapper;
        this.weights = new CreditModel.Weights(base, weightCompletion, weightReview, weightAftersale);
        // 启动即校验：权重不成表（和为 100、非负）直接不让服务起来，
        // 免得对外供出一个「明细加起来不等于总分」的分数（INV-20）。
        CreditModel.validate(this.weights);
        this.note = String.format(Locale.ROOT,
                "基础分 %d + 履约 %d + 评价 %d + 售后 %d = 100",
                base, weightCompletion, weightReview, weightAftersale);
    }

    /** 当前生效的权重口径（供刷新结果回显） */
    public String weightNote() {
        return note;
    }

    /**
     * 计算并返回某用户的信用分视图（公开只读）。
     *
     * <p>口径：分数体现该用户**作为卖方**的可信度（商品 / 闲置页展示的就是卖家信用），
     * 由「履约完成率」「评价质量」「售后诚信」三项加上基础分构成。
     */
    public CreditView creditOf(Long userId) {
        if (userId == null) {
            throw BizException.validation("用户标识不能为空");
        }
        CreditModel.Inputs inputs = inputsOf(userId);
        CreditModel.Breakdown breakdown = CreditModel.compute(inputs, weights);
        List<CreditFactor> factors = breakdown.factors().stream()
                .map(f -> new CreditFactor(f.code(), f.label(), f.weight(), f.rawValue(), f.points()))
                .toList();
        CreditInputs viewInputs = new CreditInputs(
                inputs.completedOrders(), inputs.closedOrders(), inputs.refundedOrders(),
                inputs.totalOrders(), inputs.reviewCount(),
                String.format(Locale.ROOT, "%.2f", inputs.avgRating()),
                inputs.adverseAfterSales());
        return new CreditView(userId, breakdown.score(), breakdown.level(), breakdown.levelLabel(),
                factors, viewInputs, nowIso());
    }

    /**
     * 重算并回写一批用户的信用分（幂等）。
     *
     * <p>三种调用姿势都走这里：
     * <ul>
     *   <li>定时任务（{@code source=SCHEDULED_REFRESH}）—— 默认关闭，见 {@code CreditRefreshJob}；</li>
     *   <li>运维手动触发（{@code source=MANUAL_REFRESH}）—— 传 {@code userIds} 指定范围；</li>
     *   <li>冒烟 / 集成测试 —— 同样传 {@code userIds}，可精确断言。</li>
     * </ul>
     *
     * <p>不传 {@code userIds} 时只枚举**本域已知的卖家**（有评价或售后记录者）。
     * 这是刻意的保守范围：没有交易痕迹的用户分数恒为基础分，重算一万次也是同一个数；
     * 而「全站用户」这个集合本域根本拿不到，也不该绕道去用户域捞。
     *
     * <p>单个用户失败（对端不可用等）**跳过并继续**，不让一个坏数据卡住整批 ——
     * 但会被计进 {@code skipped}，不装作成功。
     */
    public CreditRefreshResult refresh(Collection<Long> userIds, int limit, String source) {
        List<Long> targets = resolveTargets(userIds, limit);
        int updated = 0;
        int unchanged = 0;
        int skipped = 0;
        for (Long userId : targets) {
            try {
                CreditModel.Breakdown breakdown = CreditModel.compute(inputsOf(userId), weights);
                CreditWriteResult result = writeBack(userId, breakdown.score(), source);
                if (result != null && result.changed()) {
                    updated++;
                } else {
                    unchanged++;
                }
            } catch (Exception e) {
                skipped++;
                log.warn("信用分刷新跳过 userId={} 原因={}", userId, e.getMessage());
            }
        }
        log.info("信用分刷新完成 scanned={} updated={} unchanged={} skipped={}",
                targets.size(), updated, unchanged, skipped);
        return new CreditRefreshResult(targets.size(), updated, unchanged, skipped, note);
    }

    // ---------------- 内部方法 ----------------

    private List<Long> resolveTargets(Collection<Long> userIds, int limit) {
        List<Long> targets;
        if (userIds == null || userIds.isEmpty()) {
            LinkedHashSet<Long> known = new LinkedHashSet<>();
            known.addAll(nullSafe(reviewMapper.distinctSellerIds()));
            known.addAll(nullSafe(afterSaleMapper.distinctSellerIds()));
            targets = new ArrayList<>(known);
        } else {
            targets = new ArrayList<>(new LinkedHashSet<>(userIds));
        }
        if (limit > 0 && targets.size() > limit) {
            targets = targets.subList(0, limit);
        }
        return targets;
    }

    private static List<Long> nullSafe(List<Long> ids) {
        return ids == null ? List.of() : ids;
    }

    /** 取三项因子的原始事实（交易域计数 + 本域评价 / 售后聚合）。 */
    private CreditModel.Inputs inputsOf(Long userId) {
        SellerOrderStats stats = fetchSellerStats(userId);
        Map<String, Object> agg = reviewMapper.aggregateBySeller(userId);
        int reviewCount = intOf(agg == null ? null : agg.get("total"));
        double avgRating = doubleOf(agg == null ? null : agg.get("avgRating"));
        int adverse = afterSaleMapper.countAdverseBySeller(userId);
        return new CreditModel.Inputs(
                stats.completedOrders(), stats.closedOrders(), stats.refundedOrders(),
                stats.totalOrders(), reviewCount, avgRating, adverse);
    }

    /**
     * 调交易域取卖家订单计数；对端不可用一律映射为 503，绝不降级成「0 单」。
     */
    private SellerOrderStats fetchSellerStats(Long userId) {
        try {
            var response = orderClient.sellerStats(userId);
            SellerOrderStats stats = response == null ? null : response.data();
            if (stats == null) {
                throw new BizException(ErrorCode.DEGRADED, "订单服务暂时不可用，请稍后再试");
            }
            return stats;
        } catch (FeignException e) {
            log.error("调用交易域取卖家订单统计失败 sellerId={} status={}", userId, e.status(), e);
            throw new BizException(ErrorCode.DEGRADED, "订单服务暂时不可用，请稍后再试");
        }
    }

    /** 回写：委托用户域设置分数；对端异常上抛，由调用方按批处理策略决定跳过还是终止。 */
    private CreditWriteResult writeBack(Long userId, int score, String source) {
        var response = userClient.writeCredit(userId,
                new CreditWriteRequest(score, source == null ? "UNKNOWN" : source));
        return response == null ? null : response.data();
    }

    private static int intOf(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private static double doubleOf(Object value) {
        return value == null ? 0.0 : ((Number) value).doubleValue();
    }

    private static String nowIso() {
        return LocalDateTime.now(ZoneOffset.UTC).toString();
    }
}
