package com.duoduo.fulfilment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.fulfilment.entity.AfterSaleEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

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

    /**
     * 买家自己提起的售后，按状态分桶 —— 买家端售后页二级 Tab 角标的数据源。
     *
     * <p>口径与 {@code listMine} 完全一致（{@code buyer_id = 我}），只是把分页换成聚合：
     * 角标必须覆盖全量，不能拿当前页的条数充数（分页外的未结案会被漏掉）。
     *
     * <p><b>2026-10-27 · 阅后即消</b>：终态 {@code REFUNDED / CLOSED} 看过的要从计数里减掉
     * （用户原话：看完角标常驻）。其余状态都是「还没走完的事」，读过也必须继续提醒 ——
     * 与订单侧「待支付/待发货/待收货不减」是同一条规则。
     *
     * <p>可消状态名单与 {@code shared/lib/badge-rules.ts} 一一对应，改一处必须同时改另一处。
     */
    @Select("SELECT a.status AS status, COUNT(*) AS cnt FROM t_after_sale a "
            + "WHERE a.buyer_id = #{buyerId} "
            + "AND NOT (a.status IN ('REFUNDED','CLOSED') "
            + "         AND EXISTS (SELECT 1 FROM t_after_sale_read r "
            + "                     WHERE r.user_id = #{viewerId} AND r.after_sale_id = a.id)) "
            + "GROUP BY a.status")
    List<Map<String, Object>> countByBuyerAndStatus(@Param("buyerId") Long buyerId,
                                                    @Param("viewerId") Long viewerId);

    /**
     * 我店铺收到的售后，按状态分桶 —— 卖家端售后页二级 Tab 角标的数据源。
     *
     * <p>{@code sellerIds} 与 {@code listForSeller} 的可见范围同源（卖家本人；
     * 运营额外含平台自营 0），由服务层算好后传入，SQL 这边不自己推导身份。
     *
     * <p>可消范围比买家侧多一类：卖家主动拒绝（{@code SELLER_REJECTED}）是「我已经处理完、
     * 等买家决定下一招」，对卖家而言是已办事项，看过就消。但 {@code PENDING}（待处理）与
     * {@code RETURNING}（待我签收）必须留着 —— 那是还没办的事。
     */
    @Select("<script>"
            + "SELECT a.status AS status, COUNT(*) AS cnt FROM t_after_sale a "
            + "WHERE a.seller_id IN "
            + "<foreach collection='sellerIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "AND NOT (a.status IN ('REFUNDED','CLOSED','SELLER_REJECTED') "
            + "         AND EXISTS (SELECT 1 FROM t_after_sale_read r "
            + "                     WHERE r.user_id = #{viewerId} AND r.after_sale_id = a.id)) "
            + "GROUP BY a.status"
            + "</script>")
    List<Map<String, Object>> countBySellerAndStatus(@Param("sellerIds") Collection<Long> sellerIds,
                                                    @Param("viewerId") Long viewerId);

    /**
     * 记录「某人看过某售后单」：复合主键 + {@code INSERT IGNORE}，重复上报幂等。
     *
     * <p>只影响终态角标，不改变售后单任何业务状态。
     */
    @org.apache.ibatis.annotations.Insert(
            "INSERT IGNORE INTO t_after_sale_read (user_id, after_sale_id, read_at) "
            + "VALUES (#{userId}, #{afterSaleId}, #{at})")
    int markAfterSaleRead(@Param("userId") Long userId,
                          @Param("afterSaleId") Long afterSaleId,
                          @Param("at") LocalDateTime at);

    /**
     * 当前页已读集合（2026-10-27 · 逐条橙色未读印记）。
     *
     * <p>一次 IN 查询取回整页，避免每行一次「看过没有」；
     * 只影响展示，不参与状态机。
     */
    @Select("<script>SELECT after_sale_id FROM t_after_sale_read "
            + "WHERE user_id = #{userId} AND after_sale_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<Long> listReadIds(@Param("userId") Long userId, @Param("ids") Collection<Long> ids);

    /** 运营后台售后队列的当前页已读集合（source=AFTER_SALE） */
    @Select("<script>SELECT biz_id FROM t_admin_queue_read "
            + "WHERE user_id = #{userId} AND source = 'AFTER_SALE' AND biz_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<Long> listAdminReadIds(@Param("userId") Long userId, @Param("ids") Collection<Long> ids);

    /**
     * 运营后台「售后仲裁」的未读待处理数（2026-10-27 · 点击详情即消除）。
     *
     * <p>与卖家侧分开算：运营看的是全平台 {@code PENDING}，且只排除**本运营**
     * 点开看过的单（{@code t_admin_queue_read}）。卖家侧那份已读集合
     * （{@code t_after_sale_read}）不影响这里 —— 卖家看过不等于运营看过。
     */
    @Select("SELECT COUNT(*) FROM t_after_sale a "
            + "WHERE a.status = 'PENDING' "
            + "AND NOT EXISTS (SELECT 1 FROM t_admin_queue_read q "
            + "                WHERE q.user_id = #{operatorId} AND q.source = 'AFTER_SALE' AND q.biz_id = a.id)")
    long countUnreadPendingForAdmin(@Param("operatorId") Long operatorId);

    // ---------------- E24 · 租户健康度（按卖家 × 时间窗聚合） ----------------

    /**
     * 窗口内每个卖家的售后立案数 / 已退款数 / 未结案数 / 败诉数 / 已退金额。
     *
     * <h2>为什么用 {@code created_at} 做窗口，而不是"当前状态"</h2>
     * 健康度面板回答的是「这段时间里这家店出了多少售后」，是**发生量**而非**存量**。
     * 按当前状态过滤会把去年立案、今天才结案的售后算进今天，
     * 于是任何一天的售后率都随结案速度漂移 —— 而面板要的恰是"这段时间发生了什么"。
     * 与 E22 账单「本期发生额按事件时间过滤」是同一条口径，服务层注释里有完整理由。
     *
     * <h2>为什么把五个量一次查回</h2>
     * 全平台租户数不多，但"每个租户发一条 SQL"是 N+1；而分成五条按维度聚合的查询
     * 又会各自扫一遍表、还可能落在不同的时间点（用户正在立案）。一次 GROUP BY 回来，
     * 五列来自**同一个快照**。
     *
     * <h2>口径逐条（改口径时对照）</h2>
     * <ul>
     *   <li>{@code total} —— 窗口内立案总数（分母之一，见面板的售后率定义）；</li>
     *   <li>{@code refunded} —— 终态为已退款；</li>
     *   <li>{@code openCnt} —— <b>非终态</b>（待卖家处理 / 已拒绝未上诉 / 仲裁中 / 待寄回 / 已寄回）。
     *       {@code CLOSED}（运营裁定驳回）也是终态，不算未结案 ——
     *       把它算进去会让"处理得干净"的店看起来有积压；</li>
     *   <li>{@code adverse} —— 运营裁定<b>支持退款</b>（{@code arbitrate_result='APPROVE_REFUND'}）。
     *       与信用分那条口径一致：卖家主动同意退款不算污点（否则等于教卖家拖着别同意）；</li>
     *   <li>{@code refundAmount} —— 已退金额合计（分）。未结案的行 {@code refund_amount} 为 null，用 COALESCE 兜住。</li>
     * </ul>
     *
     * <p>别名用驼峰：返回的是 {@code Map<String,Object>}，列名即 key；
     * 本项目已经因为"列名大小写"栽过一次（见 {@code AdminOpsService#str}），
     * 这里用显式别名 + 读取侧统一容错（{@code TenantHealthService}）。
     */
    @Select("SELECT seller_id AS sellerId, "
            + "COUNT(*) AS total, "
            + "SUM(CASE WHEN status = 'REFUNDED' THEN 1 ELSE 0 END) AS refunded, "
            + "SUM(CASE WHEN status NOT IN ('REFUNDED','CLOSED') THEN 1 ELSE 0 END) AS openCnt, "
            + "SUM(CASE WHEN arbitrate_result = 'APPROVE_REFUND' THEN 1 ELSE 0 END) AS adverse, "
            + "COALESCE(SUM(COALESCE(refund_amount, 0)), 0) AS refundAmount "
            + "FROM t_after_sale "
            // ★ 同上：本 SQL 无 <script>，必须写字面量 >= / <；
            //   写成 XML 实体会原样进库，MP 的 JSqlParser 直接解析失败（编译期毫无征兆）。
            + "WHERE created_at >= #{from} AND created_at < #{to} "
            + "GROUP BY seller_id")
    List<Map<String, Object>> tenantStats(@Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to);
}
