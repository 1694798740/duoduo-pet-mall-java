package com.duoduo.marketing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.marketing.entity.CouponEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 券实例 Mapper。
 *
 * <p><b>所有状态迁移都是 CAS 条件更新</b>（把「当前状态必须是 X」写进 WHERE），
 * 而不是「先查状态、再 update」。并发下先查再写必然漏：两个订单同时锁同一张券，
 * 两次查询都会读到 UNUSED，于是两单都认为自己锁成功了。
 *
 * <p>返回值是<b>受影响行数</b>，服务层据此判断成功与否（0 = 没抢到，转 409）。
 */
public interface CouponMapper extends BaseMapper<CouponEntity> {

    @Select("SELECT * FROM t_coupon WHERE code = #{code}")
    CouponEntity findByCode(@Param("code") String code);

    /** 领券幂等快路径：该用户是否已领过这个模板 */
    @Select("SELECT * FROM t_coupon WHERE user_id = #{userId} AND template_id = #{templateId}")
    CouponEntity findByUserAndTemplate(@Param("userId") Long userId,
                                       @Param("templateId") Long templateId);

    /**
     * 某用户「未使用且未过期」的券（结算页候选），按到期时间升序 —— 快过期的排在前面。
     *
     * <p><b>{@code now} 传的是「当天零点」而不是当前时刻</b>（R60 放宽）：券的有效期按
     * <b>自然日</b>判定 —— 到期日当天 23:59:59 之前都还能用。若这里传当前时刻，
     * 一张 {@code expire_at = 今天 08:00} 的券在早上 9 点就查不出来了，
     * 而用户看到的到期日写的是「今天」—— 页面上写着今天到期、实际早上就不能用，
     * 是最招投诉的一类不一致。参数名保持 {@code now} 以免动 SQL，语义见调用处注释。
     */
    @Select("SELECT * FROM t_coupon WHERE user_id = #{userId} AND status = 'UNUSED' "
            + "AND expire_at >= #{now} ORDER BY expire_at ASC")
    List<CouponEntity> selectUsable(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /** 锁定在某个订单上的券（解锁 / 核销都按订单号定位，交易域只知道订单号） */
    @Select("SELECT * FROM t_coupon WHERE status = 'LOCKED' AND locked_order_no = #{orderNo}")
    List<CouponEntity> findLockedByOrder(@Param("orderNo") String orderNo);

    /**
     * 锁定：仅当券处于 UNUSED、归属该用户、且未过期时才生效。
     *
     * <p>{@code expire_at >= #{validFrom}} 也写进 WHERE：过期券不该被锁，
     * 否则「券已过期但仍能抵扣」会变成一条真实的资金缺口。
     *
     * <p><b>{@code now} 与 {@code validFrom} 必须是两个参数</b>（R60 放宽）：
     * {@code now} 写进 {@code locked_at}（要精确时刻），{@code validFrom} 只用于有效期判定
     * （传当天零点，实现「到期日当天仍可用」）。共用一个参数就得二选一 ——
     * 要么时间戳记成零点，要么有效期退回秒级判定。
     */
    @Update("UPDATE t_coupon SET status = 'LOCKED', locked_order_no = #{orderNo}, "
            + "locked_at = #{now}, updated_at = #{now} "
            + "WHERE code = #{code} AND user_id = #{userId} AND status = 'UNUSED' "
            + "AND expire_at >= #{validFrom}")
    int lock(@Param("code") String code,
             @Param("userId") Long userId,
             @Param("orderNo") String orderNo,
             @Param("now") LocalDateTime now,
             @Param("validFrom") LocalDateTime validFrom);

    /**
     * 解锁：仅当券处于 LOCKED <b>且确实是被这个订单锁的</b>才生效。
     *
     * <p>带上 orderNo 是刻意的：A 单超时想解锁，不该把已经被 B 单锁住的同一张券解开。
     */
    @Update("UPDATE t_coupon SET status = 'UNUSED', locked_order_no = NULL, "
            + "locked_at = NULL, updated_at = #{now} "
            + "WHERE code = #{code} AND status = 'LOCKED' AND locked_order_no = #{orderNo}")
    int release(@Param("code") String code,
                @Param("orderNo") String orderNo,
                @Param("now") LocalDateTime now);

    /**
     * 核销：仅当券处于 LOCKED 且属于该订单。USED 是终态，重复核销返回 0 行 → 409。
     */
    @Update("UPDATE t_coupon SET status = 'USED', used_order_no = #{orderNo}, "
            + "used_at = #{now}, updated_at = #{now} "
            + "WHERE code = #{code} AND status = 'LOCKED' AND locked_order_no = #{orderNo}")
    int consume(@Param("code") String code,
                @Param("orderNo") String orderNo,
                @Param("now") LocalDateTime now);

    /**
     * 扫描「锁定超过 TTL 仍未支付」的券（解锁兜底）。
     *
     * <p>为什么需要它而不只靠交易域关单时主动调 release：下单成功但用户直接关掉浏览器、
     * 进程重启、对端调用失败……这些情况下主动调用根本不会发生。
     * 没有兜底，券会被永久锁死，用户看着卡包里一张「已锁定」的券用不了，
     * 而运营在后台也查不出任何异常。
     */
    @Select("SELECT * FROM t_coupon WHERE status = 'LOCKED' AND locked_at <= #{deadline} "
            + "ORDER BY locked_at ASC LIMIT #{limit}")
    List<CouponEntity> findLockedBefore(@Param("deadline") LocalDateTime deadline,
                                        @Param("limit") int limit);

    /** 把已过期但仍未使用的券标记为 EXPIRED（终态） */
    @Update("UPDATE t_coupon SET status = 'EXPIRED', updated_at = #{now} "
            + "WHERE status = 'UNUSED' AND expire_at < #{now}")
    int markExpired(@Param("now") LocalDateTime now);
}
