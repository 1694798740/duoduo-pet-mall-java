package com.duoduo.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.trade.entity.PaymentRefundEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 重复收款自动退款台账数据访问。
 *
 * <p>状态迁移一律 <b>CAS 条件更新</b>：置 SUCCESS 只认 INIT，
 * 并发执行（补偿调度与手动触发撞车）只可能有一方把状态写成功。
 * 渠道侧的幂等由 {@code refund_no} 保证（微信 {@code out_refund_no} /
 * 支付宝 {@code out_request_no} 同号重提返回同一笔），两层合起来才敢说「不会退两次」。
 */
@Mapper
public interface PaymentRefundMapper extends BaseMapper<PaymentRefundEntity> {

    /**
     * 检出落库（幂等）：撞 {@code uk_refund_pay_no} 静默跳过。
     *
     * <p>不用「先查再插」：检出路径可能被并发回调、渠道查询兜底同时触发，
     * 唯一索引是唯一可靠的闸门。返回 0 只说明「已经有人落过」，不是错误。
     */
    @Insert("INSERT IGNORE INTO t_payment_refund "
            + "(id, refund_no, pay_no, order_id, user_id, channel, channel_trade_no, "
            + " amount, status, created_at, updated_at) "
            + "VALUES (#{id}, #{refundNo}, #{payNo}, #{orderId}, #{userId}, #{channel}, "
            + " #{channelTradeNo}, #{amount}, 'INIT', #{createdAt}, #{createdAt})")
    int insertIgnore(PaymentRefundEntity refund);

    /**
     * 执行成功：INIT → SUCCESS（CAS）。
     *
     * @return 1 = 本次真的置为成功；0 = 已被并发执行处理过，本次不重复落账
     */
    @Update("UPDATE t_payment_refund SET status = 'SUCCESS', channel_refund_no = #{channelRefundNo}, "
            + "fail_reason = NULL, refunded_at = #{at}, updated_at = #{at} "
            + "WHERE refund_no = #{refundNo} AND status = 'INIT'")
    int markSuccess(@Param("refundNo") String refundNo,
                    @Param("channelRefundNo") String channelRefundNo,
                    @Param("at") LocalDateTime at);

    /**
     * 记录最近一次执行失败的原因（状态保持 INIT，下一轮补偿调度重试）。
     *
     * <p>只允许对 INIT 行写：SUCCESS 是终态，不能被一次迟到的失败改写。
     */
    @Update("UPDATE t_payment_refund SET fail_reason = #{reason}, updated_at = #{at} "
            + "WHERE refund_no = #{refundNo} AND status = 'INIT'")
    int markFailReason(@Param("refundNo") String refundNo,
                       @Param("reason") String reason,
                       @Param("at") LocalDateTime at);
}
