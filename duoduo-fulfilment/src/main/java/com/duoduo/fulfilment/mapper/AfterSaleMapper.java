package com.duoduo.fulfilment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.fulfilment.entity.AfterSaleEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 售后单数据访问。
 *
 * <p>所有状态迁移都写成 <b>CAS（compare-and-set）条件更新</b>：把「当前必须处于哪些状态」
 * 写进 WHERE，用影响行数判断本次是否真的生效。
 *
 * <p>为什么不用「先查状态再更新」：查与改之间有窗口。运营在后台点「裁定退款」的同时，
 * 卖家也可能点了「同意」——两边都查到 PENDING，就会各自发起一次退款。
 * 交易域的托管 CAS 会挡住第二次扣钱，但本域若不也用 CAS，
 * 就会留下两条互相矛盾的售后结论（一条 REFUNDED、一条 CLOSED）。
 * 数据库行级原子性是这里唯一可靠的仲裁者，比加锁简单且没有锁失效的风险。
 *
 * <p>返回 0 行不是「服务坏了」，而是「已经有人处理过了」——
 * 调用方据此转 409（状态冲突），而不是 500。
 */
@Mapper
public interface AfterSaleMapper extends BaseMapper<AfterSaleEntity> {

    /**
     * 卖家同意 → 已退款（钱由交易域执行，本方法只记结论）。
     *
     * @return 1 = 本次流转成功；0 = 已经不是待卖家处理（并发下被别人处理了）
     */
    @Update("UPDATE t_after_sale SET status = 'REFUNDED', seller_reply = #{reply}, "
            + "refund_amount = #{refundAmount}, updated_at = #{at} "
            + "WHERE id = #{id} AND status = 'PENDING'")
    int markRefundedBySeller(@Param("id") Long id,
                             @Param("reply") String reply,
                             @Param("refundAmount") Long refundAmount,
                             @Param("at") LocalDateTime at);

    /**
     * 运营裁定支持退款 → 已退款（同样只记结论，钱在交易域）。
     *
     * <p>允许从「待卖家处理 / 卖家已拒绝 / 仲裁中」三态进入：
     * 卖家一直不处理时，运营不该被「必须先让卖家表态」卡住 —— 那不叫流程，那叫推诿。
     *
     * @return 1 = 本次流转成功；0 = 已被处理过（终态）
     */
    @Update("UPDATE t_after_sale SET status = 'REFUNDED', arbitrate_result = #{result}, "
            + "arbitrate_note = #{note}, arbitrated_by = #{operatorId}, "
            + "refund_amount = #{refundAmount}, updated_at = #{at} "
            + "WHERE id = #{id} AND status IN ('PENDING','SELLER_REJECTED','ARBITRATING','WAITING_RETURN','RETURNING')")
    int markRefundedByArbitration(@Param("id") Long id,
                                  @Param("result") String result,
                                  @Param("note") String note,
                                  @Param("operatorId") Long operatorId,
                                  @Param("refundAmount") Long refundAmount,
                                  @Param("at") LocalDateTime at);

    /**
     * 卖家拒绝（必填说明）→ 卖家已拒绝。
     *
     * @return 1 = 成功；0 = 已经不是待卖家处理
     */
    @Update("UPDATE t_after_sale SET status = 'SELLER_REJECTED', seller_reply = #{reply}, "
            + "updated_at = #{at} WHERE id = #{id} AND status = 'PENDING'")
    int markSellerRejected(@Param("id") Long id,
                           @Param("reply") String reply,
                           @Param("at") LocalDateTime at);

    /**
     * 买家申请平台仲裁 → 仲裁中。
     *
     * <p>允许从 PENDING 直接进入：卖家拖着不处理时买家需要一条求助平台的路。
     *
     * @return 1 = 成功；0 = 已是终态或已在仲裁中
     */
    @Update("UPDATE t_after_sale SET status = 'ARBITRATING', updated_at = #{at} "
            + "WHERE id = #{id} AND status IN ('PENDING','SELLER_REJECTED','WAITING_RETURN','RETURNING')")
    int markArbitrating(@Param("id") Long id, @Param("at") LocalDateTime at);

    /**
     * 运营裁定驳回 → 已关闭（终态）。
     *
     * @return 1 = 成功；0 = 已被处理过
     */
    @Update("UPDATE t_after_sale SET status = 'CLOSED', arbitrate_result = #{result}, "
            + "arbitrate_note = #{note}, arbitrated_by = #{operatorId}, updated_at = #{at} "
            + "WHERE id = #{id} AND status IN ('PENDING','SELLER_REJECTED','ARBITRATING','WAITING_RETURN','RETURNING')")
    int markClosedByArbitration(@Param("id") Long id,
                                @Param("result") String result,
                                @Param("note") String note,
                                @Param("operatorId") Long operatorId,
                                @Param("at") LocalDateTime at);

    // ---------------- 退货物流（第五十八轮 P1-5） ----------------

    /**
     * 卖家同意退货（仅退货退款）→ 等待买家寄回。
     *
     * <p>刻意与「同意即退款」分开成两条 SQL：同意退款与同意退货是两个不同的结论，
     * 混用同一条 UPDATE 就等于把「钱先退了货还没回」这种事故写进代码里。
     *
     * @return 1 = 成功；0 = 已经不是待卖家处理
     */
    @Update("UPDATE t_after_sale SET status = 'WAITING_RETURN', seller_reply = #{reply}, "
            + "updated_at = #{at} WHERE id = #{id} AND status = 'PENDING'")
    int markWaitingReturn(@Param("id") Long id,
                          @Param("reply") String reply,
                          @Param("at") LocalDateTime at);

    /**
     * 买家填写寄回运单 → 已寄回（待卖家签收）。
     *
     * <p>WHERE 里带 {@code status = 'WAITING_RETURN'} 是必要的：重复提交（手抖点两次、
     * 页面重试）只会返回 0 行，由服务层转成「已寄回，无需重复提交」，而不是把时间刷成第二次。
     */
    @Update("UPDATE t_after_sale SET status = 'RETURNING', return_courier = #{courier}, "
            + "return_tracking_no = #{trackingNo}, return_shipped_at = #{at}, updated_at = #{at} "
            + "WHERE id = #{id} AND status = 'WAITING_RETURN'")
    int markReturning(@Param("id") Long id,
                      @Param("courier") String courier,
                      @Param("trackingNo") String trackingNo,
                      @Param("at") LocalDateTime at);

    /**
     * 卖家确认收货 → 已签收并退款（钱由交易域执行，本方法只记结论）。
     *
     * @return 1 = 成功；0 = 已经不是「待收货」（并发下被别人签收 / 已走仲裁）
     */
    @Update("UPDATE t_after_sale SET status = 'REFUNDED', return_received_at = #{at}, "
            + "refund_amount = #{refundAmount}, updated_at = #{at} "
            + "WHERE id = #{id} AND status = 'RETURNING'")
    int markReceivedRefunded(@Param("id") Long id,
                             @Param("refundAmount") Long refundAmount,
                             @Param("at") LocalDateTime at);

    // ---------------- 信用分（A4） ----------------

    /**
     * 某卖家「被平台裁定支持买家」的售后单数（信用分的「售后诚信」因子）。
     *
     * <p>口径只说一件事：<b>运营裁定支持退款</b>（{@code arbitrate_result = 'APPROVE_REFUND'}）才算败诉。
     * 刻意<b>不</b>把「卖家主动同意退款」算进来 —— 主动同意是配合处理，
     * 把它当污点会反过来教卖家「拖着别同意」，与产品目标相反。
     *
     * <p>卖家拒绝后被运营裁定驳回（{@code CLOSED}）同样不计：那是卖家守住了规矩。
     */
    @Select("SELECT COUNT(*) FROM t_after_sale "
            + "WHERE seller_id = #{sellerId} AND arbitrate_result = 'APPROVE_REFUND'")
    int countAdverseBySeller(@Param("sellerId") Long sellerId);

    /** 本域已知的卖家标识（售后表里出现过的 seller_id，去重）—— 与评价表那份互补 */
    @Select("SELECT DISTINCT seller_id FROM t_after_sale WHERE seller_id IS NOT NULL")
    List<Long> distinctSellerIds();
}
