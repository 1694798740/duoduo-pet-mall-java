package com.duoduo.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.trade.dto.SettlementDtos.SettlementView;
import com.duoduo.trade.entity.SettlementEntity;
import com.duoduo.trade.mapper.SettlementMapper;
import com.duoduo.trade.support.SettlementStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 资金托管服务：C2C 担保交易的钱路径。
 *
 * <p>三个动作，各自的幂等依据都不同，必须说清楚：
 * <ol>
 *   <li><b>创建托管</b>：靠唯一索引 {@code uk_settle_order}。
 *       支付回调会重复投递，重复创建只可能有一条落库，撞车方读取已存在那条即可；</li>
 *   <li><b>放款</b>：靠 CAS（{@code status = 'CUSTODY'} 写进 WHERE）。
 *       「确认收货」与「自动确认收货」可能同时发生，只有一个能改成功；</li>
 *   <li><b>退款</b>：同样靠 CAS。已放款的钱不在这里退 ——
 *       钱已经到卖家手里，须走追偿/平台垫付，那是另一条流程，这里刻意让它改不动。</li>
 * </ol>
 *
 * <p><b>刻意不做的事</b>：本服务不修改任何余额、不调用渠道退款接口。
 * 它只维护「这笔钱现在归谁」的账本状态。真正的资金出账
 * （微信/支付宝退款、卖家提现打款）属于渠道对接层，见计划中的支付渠道接入。
 * 这样拆分的好处是：账本逻辑可以脱离渠道独立测试与对账，
 * 而渠道替换（沙箱 → 生产、加一个新渠道）不会碰到账本代码。
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final SettlementMapper settlementMapper;
    private final SnowflakeIdGenerator idGenerator;

    /**
     * 平台佣金费率，单位「基点」（1 bp = 万分之一）。
     * 默认 100 bp = 1%，可通过 {@code PLATFORM_FEE_BPS} 覆盖，配 0 即免佣。
     */
    @Value("${duoduo.trade.platform-fee-bps:100}")
    private int platformFeeBps;

    public SettlementService(SettlementMapper settlementMapper, SnowflakeIdGenerator idGenerator) {
        this.settlementMapper = settlementMapper;
        this.idGenerator = idGenerator;
    }

    /**
     * 创建托管单（支付成功时调用）。
     *
     * <p>幂等：先查一次走快路径；并发下由 {@code uk_settle_order} 兜底，
     * 撞车方读取已存在的那条返回 —— 不抛异常，因为「已经建过了」对调用方而言就是成功。
     */
    @Transactional
    public SettlementEntity openCustody(Long orderId, Long sellerId, long amount, LocalDateTime now) {
        SettlementEntity existing = findByOrderId(orderId);
        if (existing != null) {
            return existing;
        }
        SettlementEntity entity = new SettlementEntity();
        entity.setId(idGenerator.nextId());
        entity.setOrderId(orderId);
        entity.setSellerId(sellerId);
        entity.setAmount(amount);
        entity.setPlatformFee(platformFeeOf(amount));
        entity.setStatus(SettlementEntity.STATUS_CUSTODY);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            settlementMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            SettlementEntity winner = findByOrderId(orderId);
            if (winner != null) {
                return winner;
            }
            throw e;
        }
        log.info("订单 {} 资金已进入平台托管：金额 {} 分，平台佣金 {} 分，卖家可得 {} 分",
                orderId, amount, entity.getPlatformFee(), amount - entity.getPlatformFee());
        return entity;
    }

    /**
     * 计算平台佣金。
     *
     * <p>用「加半个单位再整除」实现四舍五入到「分」：
     * 直接整除会把每笔都少收一点点，单笔看不出来，放大到百万笔就是可观的差额，
     * 而且对账时差异会均匀散布在所有订单上，几乎不可能定位。
     *
     * <p>金额用 {@code long} 参与乘法，避免 {@code int} 溢出：
     * 一笔 2 万元（2_000_000 分）的订单乘以 100 就已经接近 int 上限的量级，
     * 电商大额订单（家电、整箱宠粮）完全可能触发。
     */
    public long platformFeeOf(long amount) {
        if (amount <= 0 || platformFeeBps <= 0) {
            return 0L;
        }
        return (amount * platformFeeBps + 5000L) / 10000L;
    }

    /**
     * 放款给卖家（确认收货 / 自动确认收货时调用）。
     *
     * @return true = 本次真的放款了；false = 已经不是托管中（已放款或已退款）
     */
    public boolean release(Long orderId, LocalDateTime now) {
        return settlementMapper.markReleased(orderId, now) == 1;
    }

    /**
     * 退回买家（退款时调用）。
     *
     * @return true = 本次真的退款了；false = 不是托管中（已放款或已退款）
     */
    public boolean refund(Long orderId, LocalDateTime now) {
        return settlementMapper.markRefunded(orderId, now) == 1;
    }

    public SettlementEntity findByOrderId(Long orderId) {
        return settlementMapper.selectOne(new LambdaQueryWrapper<SettlementEntity>()
                .eq(SettlementEntity::getOrderId, orderId));
    }

    /** 批量取托管单，供订单列表装配视图（避免逐单查询造成 N+1） */
    public Map<Long, SettlementEntity> mapByOrderIds(Collection<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Map.of();
        }
        List<SettlementEntity> list = settlementMapper.selectList(new LambdaQueryWrapper<SettlementEntity>()
                .in(SettlementEntity::getOrderId, orderIds));
        Map<Long, SettlementEntity> map = new HashMap<>();
        for (SettlementEntity entity : list) {
            map.put(entity.getOrderId(), entity);
        }
        return map;
    }

    /** 装配视图；{@code orderNo} 由调用方传入（托管表只存 order_id，不冗余订单号） */
    public SettlementView toView(SettlementEntity entity, String orderNo) {
        if (entity == null) {
            return null;
        }
        long amount = nz(entity.getAmount());
        long fee = nz(entity.getPlatformFee());
        return new SettlementView(
                orderNo,
                entity.getSellerId(),
                amount,
                fee,
                amount - fee,
                entity.getStatus(),
                SettlementStatus.labelOf(entity.getStatus()),
                isoTs(entity.getReleaseAt()),
                isoTs(entity.getReleasedAt()),
                isoTs(entity.getCreatedAt()));
    }

    private static String isoTs(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC).toString();
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }
}
