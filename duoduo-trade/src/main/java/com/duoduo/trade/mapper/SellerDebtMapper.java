package com.duoduo.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.trade.entity.SellerDebtEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 卖家欠款数据访问（第五十八轮 · B-01）。
 *
 * <p><b>幂等落在数据库</b>：{@link #insertIgnore} 撞 {@code uk_seller_debt_order(order_id)}，
 * 重复垫付返回 0 行且不报错。应用层绝不写「先 SELECT 再 INSERT」——
 * 并发下两个请求都会查到「没有」，于是插出两条、形成两笔债。
 */
@Mapper
public interface SellerDebtMapper extends BaseMapper<SellerDebtEntity> {

    /**
     * 登记一笔欠款（幂等）。
     *
     * @return 1 = 首次登记（本次垫付成立）；0 = 该订单已有欠款记录（垫付被去重）
     */
    @Insert("INSERT IGNORE INTO t_seller_debt "
            + "(id, seller_id, order_id, order_no, amount, status, reason, created_by, created_at, updated_at) "
            + "VALUES (#{e.id}, #{e.sellerId}, #{e.orderId}, #{e.orderNo}, #{e.amount}, #{e.status}, "
            + "#{e.reason}, #{e.createdBy}, #{e.createdAt}, #{e.updatedAt})")
    int insertIgnore(@Param("e") SellerDebtEntity e);

    @Select("SELECT * FROM t_seller_debt WHERE order_id = #{orderId}")
    SellerDebtEntity selectByOrderId(@Param("orderId") Long orderId);

    /**
     * 收回欠款：仅「未收回」可改（CAS）。
     *
     * @return 1 = 本次收回成功，0 = 已收回 / 已核销（重复操作无副作用）
     */
    @Update("UPDATE t_seller_debt SET status = 'SETTLED', updated_at = #{at} "
            + "WHERE id = #{id} AND status = 'OUTSTANDING'")
    int markSettled(@Param("id") Long id, @Param("at") java.time.LocalDateTime at);

    /** 核销（确认收不回，走坏账）：同样只对「未收回」生效 */
    @Update("UPDATE t_seller_debt SET status = 'WRITTEN_OFF', updated_at = #{at} "
            + "WHERE id = #{id} AND status = 'OUTSTANDING'")
    int markWrittenOff(@Param("id") Long id, @Param("at") java.time.LocalDateTime at);

    /** 某卖家的未收回欠款（结算抵扣与风控看这个口径） */
    @Select("SELECT * FROM t_seller_debt WHERE seller_id = #{sellerId} AND status = 'OUTSTANDING' "
            + "ORDER BY created_at DESC")
    List<SellerDebtEntity> outstandingOf(@Param("sellerId") Long sellerId);

    /** 未收回欠款合计（分）；无记录时为 NULL，调用方需兜底为 0 */
    @Select("SELECT COALESCE(SUM(amount), 0) FROM t_seller_debt "
            + "WHERE seller_id = #{sellerId} AND status = 'OUTSTANDING'")
    Long sumOutstanding(@Param("sellerId") Long sellerId);

    /**
     * E24 · 全部租户的未收回欠款（按卖家分组），供租户健康度面板一次取回。
     *
     * <p><b>不带时间窗</b>，这是刻意的：欠款是**存量**而不是发生量 ——
     * 三个月前垫付、至今没收回的那笔债今天仍然挂在平台账上，
     * 用窗口把它滤掉会让面板显示"这家店很干净"，而平台实际还垫着钱。
     * （"这笔债是什么时候形成的"由 {@code t_seller_debt.created_at} 回答，
     * 需要时去看欠款列表，不需要塞进健康度的四个维度里。）
     *
     * <p>同理，<b>只看 {@code OUTSTANDING}</b>：{@code SETTLED}（已收回）与
     * {@code WRITTEN_OFF}（已核销走坏账）都是已经处置完的历史，不再构成当前风险。
     */
    @Select("SELECT seller_id AS sellerId, COUNT(*) AS cnt, "
            + "COALESCE(SUM(amount), 0) AS amount "
            + "FROM t_seller_debt WHERE status = 'OUTSTANDING' GROUP BY seller_id")
    List<Map<String, Object>> outstandingBySeller();
}
