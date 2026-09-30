package com.duoduo.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.trade.entity.SellerDebtEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

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
}
