package com.duoduo.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.trade.entity.PaymentEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 支付单数据访问。
 *
 * <p>状态迁移一律写成 <b>CAS 条件更新</b>，把「当前必须处于某状态」写进 WHERE：
 * <ul>
 *   <li><b>确认收款</b>：只有 {@code INIT / PENDING} 能被置为 {@code SUCCESS}。
 *       渠道回调会重复投递（渠道重试、网络抖动、超时重发），CAS 之后
 *       第二次调用影响 0 行，调用方据此判定「已经入过账」，不需要分布式锁；</li>
 *   <li><b>超时作废</b>：只有 {@code PENDING} 能被置为 {@code EXPIRED}；
 *       只有非终态能被置为 {@code CLOSED}。</li>
 * </ul>
 *
 * <p>返回 0 行只说明「状态不匹配」，具体是「已成功」还是「已作废」，
 * 调用方需要再查一次才能区分 —— 但那一次查询只用于**生成提示文案**，不参与判断。
 */
@Mapper
public interface PaymentMapper extends BaseMapper<PaymentEntity> {

    /**
     * 确认收款（渠道回调 / 沙箱收银台调用）。
     *
     * @return 1 = 本次真的入账了；0 = 已不是待支付状态（重复回调或已作废）
     */
    @Update("UPDATE t_payment SET status = 'SUCCESS', channel_trade_no = #{tradeNo}, "
            + "fail_reason = NULL, paid_at = #{at}, updated_at = #{at} "
            + "WHERE pay_no = #{payNo} AND status IN ('INIT', 'PENDING')")
    int markSuccess(@Param("payNo") String payNo,
                    @Param("tradeNo") String tradeNo,
                    @Param("at") LocalDateTime at);

    /**
     * 超时作废（补偿调度调用）。
     *
     * @return 1 = 本次置为过期；0 = 已不是待支付状态
     */
    @Update("UPDATE t_payment SET status = 'EXPIRED', fail_reason = '支付超时未完成', updated_at = #{at} "
            + "WHERE pay_no = #{payNo} AND status = 'PENDING'")
    int markExpired(@Param("payNo") String payNo, @Param("at") LocalDateTime at);

    /**
     * 订单被取消 / 关闭时，把该订单下所有**未支付完成**的支付单一起作废。
     *
     * <p>为什么必须做：否则会出现「订单已关闭、支付单还挂着能付」——
     * 用户扫了那张二维码还能把钱付进来，而订单已经不要了。
     * 收款入口虽然也会拒绝（校验订单状态），但把矛盾状态直接消除更省事、也更好排查。
     *
     * @return 受影响行数（作废了几个支付单）
     */
    @Update("UPDATE t_payment SET status = 'CLOSED', fail_reason = #{reason}, updated_at = #{at} "
            + "WHERE order_id = #{orderId} AND status IN ('INIT', 'PENDING')")
    int markClosedByOrder(@Param("orderId") Long orderId,
                          @Param("reason") String reason,
                          @Param("at") LocalDateTime at);
}
