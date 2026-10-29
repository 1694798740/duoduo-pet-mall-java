package com.duoduo.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.trade.client.OrderNotifier;
import com.duoduo.trade.dto.OrderDtos.PaymentRefundView;
import com.duoduo.trade.entity.OrderEntity;
import com.duoduo.trade.entity.PaymentEntity;
import com.duoduo.trade.entity.PaymentRefundEntity;
import com.duoduo.trade.mapper.OrderMapper;
import com.duoduo.trade.mapper.PaymentMapper;
import com.duoduo.trade.mapper.PaymentRefundMapper;
import com.duoduo.trade.payment.PaymentChannel;
import com.duoduo.trade.payment.PaymentChannelRegistry;
import com.duoduo.trade.support.OrderNoGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 重复收款自动退款（B-04）。
 *
 * <p><b>职责边界</b>：只处理「同一订单被多收了一笔钱」的**渠道侧退款** ——
 * 多收的那笔从未进过托管（重复支付单被检出为 CLOSED，钱还停在渠道侧），
 * 所以这里的退款是「把钱从渠道原路退回买家」，与订单退款
 * （{@code doRefund}，动托管 CAS）是两条完全不同的资金路径，绝不能混用。
 *
 * <p><b>两段式设计</b>（检出与执行分离）：
 * <ol>
 *   <li><b>检出落库</b>：{@link #openAutoRefund} 由 {@code markDuplicateCharge}
 *       在**同一事务**里调用 —— 检出即落台账（INIT），撞 {@code uk_refund_pay_no}
 *       幂等跳过。这一步保证「检出了就一定会退」：哪怕进程下一秒崩溃，
 *       台账行已随检出事务提交，补偿调度总会把它捡起来；</li>
 *   <li><b>异步执行</b>：{@link #executePending} 由补偿调度（或手动触发）扫描 INIT，
 *       调渠道退款接口，CAS 置 SUCCESS。渠道调用不放在检出事务里 ——
 *       外部系统调用绝不该持有数据库事务与行锁。</li>
 * </ol>
 *
 * <p><b>不会退两次的三层保证</b>：
 * <ul>
 *   <li>台账唯一索引 {@code uk_refund_pay_no}：一张支付单最多一条退款；</li>
 *   <li>状态 CAS：只有 INIT 能被置为 SUCCESS，并发执行只有一方落账；</li>
 *   <li>渠道幂等号：{@code refund_no} 作为微信 {@code out_refund_no} /
 *       支付宝 {@code out_request_no} 提交，同号重提渠道返回同一笔退款。</li>
 * </ul>
 *
 * <p><b>失败口径</b>：渠道不可用 / 返回处理中 / 暂不支持退款 —— 台账**保持 INIT**，
 * {@code fail_reason} 记最近一次原因，下轮重试。与 {@code RESTOCK_PENDING} 同一条
 * 补偿哲学：把「暂时失败」固化成终态，等于宣布这笔多收的钱永远退不回去。
 */
@Service
public class PaymentRefundService {

    private static final Logger log = LoggerFactory.getLogger(PaymentRefundService.class);

    private final PaymentRefundMapper refundMapper;
    private final PaymentMapper paymentMapper;
    private final OrderMapper orderMapper;
    private final PaymentChannelRegistry channelRegistry;
    private final SnowflakeIdGenerator idGenerator;
    private final OrderNoGenerator orderNoGenerator;
    private final OrderNotifier orderNotifier;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final int sweepBatchSize;

    public PaymentRefundService(PaymentRefundMapper refundMapper,
                                PaymentMapper paymentMapper,
                                OrderMapper orderMapper,
                                PaymentChannelRegistry channelRegistry,
                                SnowflakeIdGenerator idGenerator,
                                OrderNoGenerator orderNoGenerator,
                                OrderNotifier orderNotifier,
                                org.springframework.context.ApplicationEventPublisher eventPublisher,
                                @Value("${duoduo.trade.sweep-batch-size:200}") int sweepBatchSize) {
        this.refundMapper = refundMapper;
        this.paymentMapper = paymentMapper;
        this.orderMapper = orderMapper;
        this.channelRegistry = channelRegistry;
        this.idGenerator = idGenerator;
        this.orderNoGenerator = orderNoGenerator;
        this.orderNotifier = orderNotifier;
        this.eventPublisher = eventPublisher;
        this.sweepBatchSize = sweepBatchSize;
    }

    // ---------------- 检出落库 ----------------

    /**
     * 检出重复收款时落一条退款台账（幂等，撞唯一索引静默跳过）。
     *
     * <p>必须在检出事务内调用：台账行与「支付单置 CLOSED + 留痕」要么同时成立，
     * 要么同时不存在 —— 不允许出现「支付单写着需退款、台账却没落」的断链。
     *
     * @param duplicate 被检出的重复支付单（钱多收了的那张）
     * @param winning   同订单已成功入账的支付单（用于日志定位）
     * @return true = 本次真的落了一条；false = 已存在（并发检出 / 回调重放），跳过
     */
    public boolean openAutoRefund(PaymentEntity duplicate, PaymentEntity winning) {
        PaymentRefundEntity refund = new PaymentRefundEntity();
        refund.setId(idGenerator.nextId());
        refund.setRefundNo(orderNoGenerator.next());
        refund.setPayNo(duplicate.getPayNo());
        refund.setOrderId(duplicate.getOrderId());
        refund.setUserId(duplicate.getUserId());
        refund.setChannel(duplicate.getChannel());
        refund.setChannelTradeNo(duplicate.getChannelTradeNo());
        refund.setAmount(duplicate.getAmount() == null ? 0L : duplicate.getAmount());
        refund.setStatus(PaymentRefundEntity.STATUS_INIT);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        refund.setCreatedAt(now);
        refund.setUpdatedAt(now);
        int inserted = refundMapper.insertIgnore(refund);
        if (inserted > 0) {
            log.error("重复收款自动退款已登记：refundNo={} payNo={} amount={}分 channel={} orderNo={}（原入账支付单 {}），"
                            + "待补偿调度执行", refund.getRefundNo(), refund.getPayNo(), refund.getAmount(),
                    refund.getChannel(), orderNoOf(duplicate.getOrderId()), winning == null ? "-" : winning.getPayNo());
            return true;
        }
        log.info("重复收款退款台账已存在，跳过：payNo={}", duplicate.getPayNo());
        return false;
    }

    // ---------------- 异步执行 ----------------

    /**
     * 执行一轮待处理退款：扫描 INIT，逐条调渠道退款接口。
     *
     * <p>幂等可重入：CAS 只让第一次把状态置为 SUCCESS；渠道调用以 {@code refund_no}
     * 为幂等号，即使补偿调度与手动触发并发跑，渠道侧也不会退两笔。
     * 单条失败只记原因不中断整轮（与渠道查询兜底同口径）。
     *
     * @return {@code [本轮成功笔数, 仍待处理笔数]}
     */
    public int[] executePending(LocalDateTime now) {
        List<PaymentRefundEntity> pendings = refundMapper.selectList(
                new LambdaQueryWrapper<PaymentRefundEntity>()
                        .eq(PaymentRefundEntity::getStatus, PaymentRefundEntity.STATUS_INIT)
                        .orderByAsc(PaymentRefundEntity::getCreatedAt)
                        .last("LIMIT " + Math.max(1, sweepBatchSize)));
        int executed = 0;
        int stillPending = 0;
        for (PaymentRefundEntity refund : pendings) {
            if (executeOne(refund, now)) {
                executed++;
            } else {
                stillPending++;
            }
        }
        return new int[]{executed, stillPending};
    }

    /**
     * 执行单笔退款。返回 true = 已到成功终态；false = 仍留在 INIT 待重试。
     */
    private boolean executeOne(PaymentRefundEntity refund, LocalDateTime now) {
        PaymentEntity payment = paymentMapper.selectOne(new LambdaQueryWrapper<PaymentEntity>()
                .eq(PaymentEntity::getPayNo, refund.getPayNo()));
        if (payment == null) {
            // 支付单都没了属于数据异常，不擅自把钱退给一个来历不明的支付单
            refundMapper.markFailReason(refund.getRefundNo(), "支付单不存在，需人工核对", now);
            log.error("退款台账指向的支付单不存在，保持 INIT 待人工核对：refundNo={} payNo={}",
                    refund.getRefundNo(), refund.getPayNo());
            return false;
        }
        try {
            PaymentChannel channel = channelRegistry.require(refund.getChannel());
            PaymentChannel.RefundResult result = channel.refund(new PaymentChannel.RefundCommand(
                    refund.getPayNo(), refund.getRefundNo(), nz(refund.getAmount()),
                    nz(payment.getAmount()), "重复支付原路退回"));
            if (result == null || !result.done()) {
                // 渠道受理但还在处理中：保持 INIT，下一轮补偿调度再确认
                refundMapper.markFailReason(refund.getRefundNo(),
                        "渠道处理中（受理未到终态），等待下轮确认", now);
                log.info("退款渠道受理未到终态，保持 INIT：refundNo={} channel={}",
                        refund.getRefundNo(), refund.getChannel());
                return false;
            }
            int marked = refundMapper.markSuccess(refund.getRefundNo(),
                    result.channelRefundNo(), now);
            if (marked > 0) {
                log.error("重复收款已自动退款：refundNo={} payNo={} amount={}分 channelRefundNo={}（CAS={})",
                        refund.getRefundNo(), refund.getPayNo(), refund.getAmount(),
                        result.channelRefundNo(), marked);
                eventPublisher.publishEvent(new PaymentRefundNoticeEvent(
                        refund.getUserId(), orderNoOf(refund.getOrderId()), nz(refund.getAmount())));
                return true;
            }
            // CAS 落败 = 并发执行已把它置为 SUCCESS，本次的渠道调用是同号幂等重提，无害
            log.info("退款已被并发执行置为 SUCCESS，跳过落账：refundNo={}", refund.getRefundNo());
            return true;
        } catch (RuntimeException e) {
            // 渠道不可用 / 不支持 / 报错：保持 INIT，记原因，下轮重试，绝不把台账打死
            String reason = trimReason("渠道退款失败：" + e.getMessage());
            refundMapper.markFailReason(refund.getRefundNo(), reason, now);
            log.error("重复收款自动退款执行失败，保持 INIT 待重试：refundNo={} payNo={} channel={} err={}",
                    refund.getRefundNo(), refund.getPayNo(), refund.getChannel(), e.getMessage());
            return false;
        }
    }

    // ---------------- 视图查询 ----------------

    /** 买家查自己的退款台账（支付单维度过滤可选） */
    public List<PaymentRefundView> listMine(Long userId, String payNo, int pageNo, int size) {
        LambdaQueryWrapper<PaymentRefundEntity> query = new LambdaQueryWrapper<PaymentRefundEntity>()
                .eq(PaymentRefundEntity::getUserId, userId)
                .orderByDesc(PaymentRefundEntity::getCreatedAt);
        if (payNo != null && !payNo.isBlank()) {
            query.eq(PaymentRefundEntity::getPayNo, payNo.trim());
        }
        int safePage = Math.max(1, pageNo);
        int safeSize = Math.min(Math.max(1, size), 50);
        query.last("LIMIT " + safeSize + " OFFSET " + (long) (safePage - 1) * safeSize);
        return toViews(refundMapper.selectList(query));
    }

    /** 运营侧台账（状态 / 支付单号 / 订单号筛选，分页） */
    public PageResult<PaymentRefundView> listForAdmin(String status, String payNo, String orderNo,
                                                      int pageNo, int size) {
        LambdaQueryWrapper<PaymentRefundEntity> query = new LambdaQueryWrapper<PaymentRefundEntity>()
                .orderByDesc(PaymentRefundEntity::getCreatedAt);
        if (status != null && !status.isBlank()) {
            query.eq(PaymentRefundEntity::getStatus, status.trim().toUpperCase());
        }
        if (payNo != null && !payNo.isBlank()) {
            query.eq(PaymentRefundEntity::getPayNo, payNo.trim());
        }
        Long orderId = null;
        if (orderNo != null && !orderNo.isBlank()) {
            OrderEntity order = orderMapper.selectOne(new LambdaQueryWrapper<OrderEntity>()
                    .eq(OrderEntity::getOrderNo, orderNo.trim()));
            if (order == null) {
                return PageResult.of(List.of(), 0, pageNo, size);
            }
            orderId = order.getId();
            query.eq(PaymentRefundEntity::getOrderId, orderId);
        }
        int safePage = Math.max(1, pageNo);
        int safeSize = Math.min(Math.max(1, size), 100);
        query.last("LIMIT " + safeSize + " OFFSET " + (long) (safePage - 1) * safeSize);
        List<PaymentRefundEntity> rows = refundMapper.selectList(query);
        long total = refundMapper.selectCount(cloneWithoutPaging(status, payNo, orderId));
        return PageResult.of(toViews(rows), total, safePage, safeSize);
    }

    /** 重建不带分页的同条件查询（COUNT 用） */
    private LambdaQueryWrapper<PaymentRefundEntity> cloneWithoutPaging(
            String status, String payNo, Long orderId) {
        LambdaQueryWrapper<PaymentRefundEntity> query = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            query.eq(PaymentRefundEntity::getStatus, status.trim().toUpperCase());
        }
        if (payNo != null && !payNo.isBlank()) {
            query.eq(PaymentRefundEntity::getPayNo, payNo.trim());
        }
        if (orderId != null) {
            query.eq(PaymentRefundEntity::getOrderId, orderId);
        }
        return query;
    }

    private List<PaymentRefundView> toViews(List<PaymentRefundEntity> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, String> orderNos = orderMapper.selectBatchIds(
                        rows.stream().map(PaymentRefundEntity::getOrderId).filter(Objects::nonNull).toList())
                .stream()
                .collect(Collectors.toMap(OrderEntity::getId, OrderEntity::getOrderNo, (a, b) -> a));
        return rows.stream()
                .map((Function<PaymentRefundEntity, PaymentRefundView>) r -> new PaymentRefundView(
                        r.getRefundNo(),
                        r.getPayNo(),
                        orderNos.get(r.getOrderId()),
                        nz(r.getAmount()),
                        r.getStatus(),
                        r.getChannel(),
                        r.getChannelRefundNo(),
                        r.getFailReason(),
                        isoTs(r.getRefundedAt()),
                        isoTs(r.getCreatedAt())))
                .toList();
    }

    // ---------------- 通知 ----------------

    /** 重复收款已自动退款（对买家） */
    public record PaymentRefundNoticeEvent(Long userId, String orderNo, long amount) {
    }

    /**
     * 事务提交后才推通知：台账行没提交完就推，用户会看到一条查无此单的退款。
     * 推送失败由 {@link OrderNotifier} 内部吞掉（通知不影响资金正确性）。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRefundNotice(PaymentRefundNoticeEvent event) {
        if (event.userId() == null) {
            return;
        }
        orderNotifier.notifyAutoRefund(event.userId(), event.orderNo(), event.amount());
    }

    // ---------------- 内部 ----------------

    private String orderNoOf(Long orderId) {
        if (orderId == null) {
            return "-";
        }
        OrderEntity order = orderMapper.selectById(orderId);
        return order == null ? "-" : order.getOrderNo();
    }

    private static String trimReason(String reason) {
        if (reason == null) {
            return "渠道退款失败";
        }
        return reason.length() > 250 ? reason.substring(0, 250) : reason;
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    private static String isoTs(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC).toString();
    }
}
