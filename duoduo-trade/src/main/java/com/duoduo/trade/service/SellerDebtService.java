package com.duoduo.trade.service;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.trade.client.OrderNotifier;
import com.duoduo.trade.dto.AdminDtos.SellerDebtView;
import com.duoduo.trade.entity.OrderEntity;
import com.duoduo.trade.entity.SellerDebtEntity;
import com.duoduo.trade.entity.SettlementEntity;
import com.duoduo.trade.mapper.OrderMapper;
import com.duoduo.trade.mapper.SellerDebtMapper;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.OperatorGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 平台垫付退款与卖家追偿（第五十八轮 · B-01）。
 *
 * <h2>解决的是什么
 * 订单一旦「已完成」，钱就已经放款给卖家 —— 托管单是 {@code RELEASED} 终态，
 * 账上「买家的钱」不存在了。此前系统只能回一句「需走追责或平台垫付（尚未实现）」：
 * 买家拿不到钱，运营也没有任何可执行的动作，这类售后只能靠线下处理，
 * 且**在系统里不留任何痕迹** —— 对账时既看不到退款，也看不到谁欠谁。
 *
 * <h2>现在的口径（三条事实各归其位）
 * <ol>
 *   <li><b>买家的钱</b>：由平台垫付，托管单 {@code RELEASED → ADVANCED}（不是撤回放款，
 *       那条边不可逆），订单状态进 {@code REFUNDED} 终态；</li>
 *   <li><b>卖家的债</b>：在 {@code t_seller_debt} 记一笔欠款，金额为买家实付；</li>
 *   <li><b>货</b>：<b>不回补库存</b>。垫付是**资金动作**，不是退货 ——
 *       货在买家手上且订单已完成，是否退货属另一条售后流程，不能在这里顺手把库存加回去，
 *       否则会出现「钱退了、货没退、库存还多了一份」的三重错账。</li>
 * </ol>
 *
 * <h2>幂等两道闸
 * ① 欠款表 {@code uk_seller_debt_order(order_id)} 唯一键（{@code INSERT IGNORE}）；
 * ② 托管单 CAS（只有 {@code RELEASED} 才能变 {@code ADVANCED}）。
 * 任一闸挡住都返回明确的 409，绝不静默当成成功。
 */
@Service
public class SellerDebtService {

    private static final Logger log = LoggerFactory.getLogger(SellerDebtService.class);

    private final SellerDebtMapper debtMapper;
    private final OrderMapper orderMapper;
    private final SettlementService settlementService;
    private final OrderNotifier orderNotifier;
    private final SnowflakeIdGenerator idGenerator;

    public SellerDebtService(SellerDebtMapper debtMapper,
                             OrderMapper orderMapper,
                             SettlementService settlementService,
                             OrderNotifier orderNotifier,
                             SnowflakeIdGenerator idGenerator) {
        this.debtMapper = debtMapper;
        this.orderMapper = orderMapper;
        this.settlementService = settlementService;
        this.orderNotifier = orderNotifier;
        this.idGenerator = idGenerator;
    }

    /**
     * 平台垫付退款（运营操作）。
     *
     * @param orderNo 已完成（已放款）的订单号
     * @param reason  垫付原因（进对账口径，必填 —— 这类钱的去向必须可解释）
     */
    @Transactional
    public SellerDebtView compensate(String orderNo, String reason) {
        LoginUser operator = OperatorGuard.requireOperator();
        Long operatorId = operator == null ? null : operator.userId();
        if (orderNo == null || orderNo.isBlank()) {
            throw BizException.validation("订单号不能为空");
        }
        String why = (reason == null || reason.isBlank()) ? null : reason.trim();
        if (why == null) {
            throw BizException.validation("垫付原因必填：这笔钱为什么由平台出，必须留在账上可查");
        }

        OrderEntity order = orderMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OrderEntity>()
                .eq(OrderEntity::getOrderNo, orderNo.trim()));
        if (order == null) {
            throw BizException.notFound("订单");
        }
        // ① 幂等判定**必须放在状态校验之前**（探针 B1 抓到的顺序缺陷）：
        //    垫付成功后订单会变成 REFUNDED，若先校验状态，重复垫付会报
        //    「当前状态 REFUNDED，只有已完成订单才需要垫付」—— 运营看到的是一句**误导**，
        //    真实原因是「这笔已经垫付过了」。错误提示必须指向真实原因，否则会诱使人去找别的路子重试。
        SellerDebtEntity existing = debtMapper.selectByOrderId(order.getId());
        if (existing != null) {
            throw new BizException(ErrorCode.CONFLICT,
                    "该订单已垫付过（欠款状态：" + SellerDebtEntity.labelOf(existing.getStatus())
                            + "），不可重复垫付");
        }
        if (!"COMPLETED".equals(order.getStatus())) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL,
                    "只有「已完成」的订单才需要平台垫付；当前状态：" + order.getStatus()
                            + "。未放款订单请走正常退款通道");
        }
        long amount = order.getPayAmount() == null ? 0L : order.getPayAmount();
        if (amount <= 0) {
            throw new BizException(ErrorCode.BUSINESS_RULE, "订单实付金额为 0，无需垫付");
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        // ① 幂等闸一：欠款唯一键。撞键说明该订单已经垫付过 —— 明确报冲突，
        //    不能当成成功返回（否则运营以为又垫了一次，实际没有，买家也没再收到钱）。
        SellerDebtEntity debt = new SellerDebtEntity();
        debt.setId(idGenerator.nextId());
        debt.setSellerId(order.getSellerId());
        debt.setOrderId(order.getId());
        debt.setOrderNo(order.getOrderNo());
        debt.setAmount(amount);
        debt.setStatus(SellerDebtEntity.STATUS_OUTSTANDING);
        debt.setReason(why);
        debt.setCreatedBy(operatorId);
        debt.setCreatedAt(now);
        debt.setUpdatedAt(now);
        if (debtMapper.insertIgnore(debt) != 1) {
            // 并发下撞唯一键：SELECT 判重与 INSERT 之间仍有窗口，这里的兜底是**必需的**第二道闸
            throw new BizException(ErrorCode.CONFLICT, "该订单已垫付过，不可重复垫付");
        }

        // ② 幂等闸二：资金 CAS。不是 RELEASED 就说明判断与实际不一致 —— 事务回滚，欠款行也一并撤销。
        if (!settlementService.advance(order.getId(), now)) {
            SettlementEntity settlement = settlementService.findByOrderId(order.getId());
            throw new BizException(ErrorCode.CONFLICT,
                    "该订单资金不是「已放款」状态（当前："
                            + (settlement == null ? "无托管记录" : settlement.getStatus())
                            + "），无法垫付");
        }

        // ③ 订单进 REFUNDED 终态：对买家而言这笔钱已经拿到了
        order.setStatus("REFUNDED");
        order.setRefundedAt(now);
        order.setCloseReason("平台垫付退款：" + why);
        order.setUpdatedAt(now);
        orderMapper.updateById(order);

        log.warn("平台垫付：订单 {} 金额 {} 分，卖家 {} 欠款已登记（操作人 {}）",
                order.getOrderNo(), amount, order.getSellerId(), operatorId);

        // 通知买家：钱已退回（best-effort，失败不影响垫付结果 —— 钱已经出去了）
        try {
            orderNotifier.notifyRefunded(order.getUserId(), order.getOrderNo(), amount);
        } catch (RuntimeException e) {
            log.warn("垫付退款通知失败（不影响垫付结果）: {}", e.getMessage());
        }

        return toView(debtMapper.selectByOrderId(order.getId()));
    }

    /** 欠款列表（可按卖家过滤） */
    public List<SellerDebtView> list(Long sellerId, String status) {
        OperatorGuard.requireOperator();
        var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SellerDebtEntity>();
        if (sellerId != null) {
            wrapper.eq(SellerDebtEntity::getSellerId, sellerId);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(SellerDebtEntity::getStatus, status.trim());
        }
        wrapper.orderByDesc(SellerDebtEntity::getCreatedAt);
        return debtMapper.selectList(wrapper).stream().map(SellerDebtService::toView).toList();
    }

    /** 收回欠款（CAS：仅「未收回」可改） */
    @Transactional
    public SellerDebtView settle(Long debtId) {
        OperatorGuard.requireOperator();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (debtMapper.markSettled(debtId, now) != 1) {
            throw new BizException(ErrorCode.CONFLICT, "欠款不存在或已结清");
        }
        return toView(debtMapper.selectById(debtId));
    }

    /** 核销（确认收不回，走坏账） */
    @Transactional
    public SellerDebtView writeOff(Long debtId) {
        OperatorGuard.requireOperator();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (debtMapper.markWrittenOff(debtId, now) != 1) {
            throw new BizException(ErrorCode.CONFLICT, "欠款不存在或已结清");
        }
        return toView(debtMapper.selectById(debtId));
    }

    /** 某卖家的未收回欠款合计（分），供结算抵扣与风控读取 */
    public long outstandingTotal(Long sellerId) {
        Long total = debtMapper.sumOutstanding(sellerId);
        return total == null ? 0L : total;
    }

    private static SellerDebtView toView(SellerDebtEntity e) {
        if (e == null) {
            return null;
        }
        return new SellerDebtView(
                String.valueOf(e.getId()),
                String.valueOf(e.getSellerId()),
                String.valueOf(e.getOrderId()),
                e.getOrderNo(),
                e.getAmount(),
                e.getStatus(),
                SellerDebtEntity.labelOf(e.getStatus()),
                e.getReason(),
                e.getCreatedAt());
    }
}
