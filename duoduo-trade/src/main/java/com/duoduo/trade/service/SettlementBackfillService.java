package com.duoduo.trade.service;

import com.duoduo.common.context.OperatorGuard;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.trade.dto.AdminDtos.BackfillItem;
import com.duoduo.trade.dto.AdminDtos.BackfillResult;
import com.duoduo.trade.entity.SettlementEntity;
import com.duoduo.trade.mapper.OrderMapper;
import com.duoduo.trade.mapper.SettlementMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 历史订单托管单回补（A1 / I-14）。
 *
 * <p><b>要解决的问题</b>：M5-8（C2C 担保交易）上线之前产生的订单没有 {@code t_settlement} 记录。
 * 这些单在「确认收货」时放款会找不到托管单，只能记一行 ERROR —— 订单照常完成，
 * 但**钱去了哪里从此没有答案**，对账时表现为「已付款却缺账本」的挂账。
 * 开发库里当前就有这类订单（{@code GET /api/admin/settlements/abnormal} 能看到它们）。
 *
 * <p><b>为什么是「按订单状态推断」而不是「不管」</b>：这些订单的资金事实其实是被完整记录的 ——
 * 付过款、发过货、收过货、退过款，每一步都有时间戳。缺的只是「把这两条时间线对齐成一行账本」这一步。
 * 推断依据因此不是猜的，而是从订单自己的状态与时间戳推出来的：
 *
 * <table border="1">
 *   <caption>状态推断口径</caption>
 *   <tr><th>订单状态</th><th>推断的资金状态</th><th>理由</th></tr>
 *   <tr><td>{@code PAID} / {@code SHIPPED}</td><td>{@code CUSTODY}</td><td>买家已付、卖家未交付完成 → 钱本应留在平台托管</td></tr>
 *   <tr><td>{@code COMPLETED}</td><td>{@code RELEASED}</td><td>已确认收货 → 钱本应已放款给卖家（金额与佣金按当刻费率补记）</td></tr>
 *   <tr><td>{@code REFUNDED}</td><td>{@code REFUNDED}</td><td>退款终态 → 钱本应已退回买家</td></tr>
 * </table>
 *
 * <p><b>刻意不做的事</b>：
 * <ul>
 *   <li><b>不碰已有托管单的订单</b>。扫描条件本身就带 {@code s.id IS NULL}，
 *       已入账的订单连读都不读进来 —— 回补脚本最怕的就是「顺手把对的数据也改了」；</li>
 *   <li><b>不给 {@code PENDING_PAYMENT} / {@code CLOSED} 补账</b>。这两类的钱**从未收过**，
 *       给它们建托管单等于凭空造出一笔钱，比不补更危险；</li>
 *   <li><b>不重算佣金费率的历史值</b>。历史单据没有留档结算当刻的费率，无法还原；
 *       本次统一用当前配置的 {@code PLATFORM_FEE_BPS} 重算，并把口径写进返回结果与日志留痕
 *       （这是本项目对「不可考」的一贯处理：宁可显式标注假设，也不假装精确）。</li>
 * </ul>
 *
 * <p><b>幂等</b>：靠 {@code uk_settle_order}（一单一条）。重复执行的扫描结果集为空，
 * 即使并发两次也只会有一个插入成功，撞车方按 {@code DuplicateKeyException} 记入 {@code skipped} 而不是抛错 ——
 * 对调用方而言「已经补过了」就是成功。这与全项目的幂等口径一致（INV-1：靠唯一索引，不靠先查再插）。
 *
 * <p><b>事务边界</b>：<b>刻意不给整个回补加一个大事务</b>。
 * 逐条独立提交，好处是「补了 80 条之后第 81 条出错」时前 80 条不会一起回滚 ——
 * 回补是运维操作，正确的失败语义是「尽力补齐 + 如实报告补了多少」，
 * 而不是「要么全成要么全不成」；后者在这种数据修复场景里只会让人反复重跑。
 * 单条插入本身仍是原子的。
 */
@Service
public class SettlementBackfillService {

    private static final Logger log = LoggerFactory.getLogger(SettlementBackfillService.class);

    private final OrderMapper orderMapper;
    private final SettlementMapper settlementMapper;
    private final SettlementService settlementService;
    private final SnowflakeIdGenerator idGenerator;

    public SettlementBackfillService(OrderMapper orderMapper,
                                     SettlementMapper settlementMapper,
                                     SettlementService settlementService,
                                     SnowflakeIdGenerator idGenerator) {
        this.orderMapper = orderMapper;
        this.settlementMapper = settlementMapper;
        this.settlementService = settlementService;
        this.idGenerator = idGenerator;
    }

    /**
     * 执行（或预演）回补。
     *
     * @param dryRun true = 只返回「将要创建什么」的清单，一行都不写库。
     *               回补是写账操作且**金额一旦落库就无法回滚到无记录状态**，
     *               所以运营必须先看计划再执行（本方法把「看计划」做成接口的一部分，
     *               而不是让运营靠 SQL 自己查一遍 —— 那样查出来的口径和实际执行的口径迟早会不一致）。
     */
    public BackfillResult backfill(boolean dryRun) {
        OperatorGuard.requireOperator();

        List<Map<String, Object>> rows = orderMapper.findOrdersNeedingSettlementBackfill();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        List<BackfillItem> items = new ArrayList<>();
        int custodyCount = 0, releasedCount = 0, refundedCount = 0;
        long custodyAmount = 0, releasedAmount = 0, refundedAmount = 0;
        int created = 0, skipped = 0;

        for (Map<String, Object> row : rows) {
            long orderId = lng(row, "id");
            String orderNo = str(row, "orderNo");
            String orderStatus = str(row, "orderStatus");
            long sellerId = lng(row, "sellerId");
            long amount = lng(row, "payAmount");
            String targetStatus = inferSettlementStatus(orderStatus);
            if (targetStatus == null) {
                continue;
            }
            long fee = settlementService.platformFeeOf(amount);

            items.add(new BackfillItem(orderNo, orderStatus, targetStatus, amount, fee));
            switch (targetStatus) {
                case SettlementEntity.STATUS_CUSTODY -> {
                    custodyCount++;
                    custodyAmount += amount;
                }
                case SettlementEntity.STATUS_RELEASED -> {
                    releasedCount++;
                    releasedAmount += amount;
                }
                case SettlementEntity.STATUS_REFUNDED -> {
                    refundedCount++;
                    refundedAmount += amount;
                }
                default -> { /* inferSettlementStatus 只会返回上面三种，这里防御性留空 */ }
            }

            if (dryRun) {
                continue;
            }
            if (insert(orderId, sellerId, amount, fee, targetStatus, orderStatus, row, now)) {
                created++;
            } else {
                skipped++;
            }
        }

        int bps = settlementService.platformFeeBps();
        String note = "平台佣金按当前配置 PLATFORM_FEE_BPS=" + bps
                + " 重算；历史单据未留档结算当刻费率，口径不可还原";

        if (dryRun) {
            log.info("托管单回补预演（dryRun）：扫描 {} 单，计划补建 托管中 {} / 已放款 {} / 已退回 {}",
                    items.size(), custodyCount, releasedCount, refundedCount);
        } else {
            log.info("托管单回补完成：扫描 {} 单，新建 {} 条、跳过 {} 条；托管中 {} / 已放款 {} / 已退回 {}",
                    items.size(), created, skipped, custodyCount, releasedCount, refundedCount);
        }

        return new BackfillResult(dryRun, items.size(), created, skipped,
                custodyCount, releasedCount, refundedCount,
                custodyAmount, releasedAmount, refundedAmount, bps, note, items);
    }

    /**
     * 按订单状态推断该订单应处的资金状态。
     *
     * @return 目标状态；{@code null} 表示这类订单不该有托管单（钱从未收过）
     */
    private static String inferSettlementStatus(String orderStatus) {
        if (orderStatus == null) {
            return null;
        }
        return switch (orderStatus) {
            case "PAID", "SHIPPED" -> SettlementEntity.STATUS_CUSTODY;
            case "COMPLETED" -> SettlementEntity.STATUS_RELEASED;
            case "REFUNDED" -> SettlementEntity.STATUS_REFUNDED;
            default -> null;
        };
    }

    /**
     * 插入一条回补出来的托管单。
     *
     * @return true = 本次真的建了；false = 并发下已被别人建过（撞唯一索引），按「已经补过」处理
     */
    private boolean insert(long orderId, long sellerId, long amount, long fee,
                           String status, String orderStatus, Map<String, Object> row, LocalDateTime now) {
        SettlementEntity entity = new SettlementEntity();
        // id-type: input，主键必须显式赋雪花值，否则 Column 'id' cannot be null（INV-13）
        entity.setId(idGenerator.nextId());
        entity.setOrderId(orderId);
        entity.setSellerId(sellerId);
        entity.setAmount(amount);
        entity.setPlatformFee(fee);
        entity.setStatus(status);
        if (SettlementEntity.STATUS_RELEASED.equals(status)) {
            // 用订单真实的完成时间当放款时间：回补的目的是让账目与历史对得上，
            // 填「脚本执行那一刻」会让对账差异凭空多出一段时间无法解释的错位。
            LocalDateTime finishedAt = time(row, "finishedAt");
            LocalDateTime at = finishedAt != null ? finishedAt : now;
            entity.setReleaseAt(at);
            entity.setReleasedAt(at);
        }
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            settlementMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            log.warn("托管单回补撞唯一索引，判为已补过：订单 {}（原状态 {}）", orderId, orderStatus);
            return false;
        }
        return true;
    }

    /** 列名大小写容错：不同 JDBC 驱动可能回传 {@code orderNo} / {@code ORDERNO}，写死会全取到 null */
    private static String str(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (value == null) {
            value = row.get(column.toUpperCase());
        }
        return value == null ? null : String.valueOf(value);
    }

    private static long lng(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (value == null) {
            value = row.get(column.toUpperCase());
        }
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /** 时间列容错：MySQL 驱动给 {@code Timestamp}，H2 给 {@code LocalDateTime}，两种都要认 */
    private static LocalDateTime time(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (value == null) {
            value = row.get(column.toUpperCase());
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt;
        }
        return null;
    }
}
