package com.duoduo.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.trade.entity.OrderEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Mapper
public interface OrderMapper extends BaseMapper<OrderEntity> {

    /**
     * 按状态统计某（几）个卖家的订单数，供商家端概览用。
     *
     * <p>写成 SQL 聚合而不是把订单查出来在内存里数：
     * 概览是每次进商家端都要走的路径，卖家的订单量会随时间单调增长，
     * 「全量捞回来再数」在今天跑得动，不代表一年后还跑得动 ——
     * 而这类慢查询一旦上线，往往要等到某天数据库 CPU 打满才被发现。
     *
     * @return 形如 {@code [{status: "PAID", cnt: 7}, ...]}；无数据时返回空列表
     */
    @Select("<script>"
            + "SELECT status, COUNT(*) AS cnt FROM t_order "
            + "WHERE seller_id IN "
            + "<foreach collection='sellerIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "GROUP BY status"
            + "</script>")
    List<Map<String, Object>> countBySellerAndStatus(@Param("sellerIds") Collection<Long> sellerIds);

    /** 全平台订单按状态统计（运营报表），返回形如 {@code [{status:"PAID", cnt:9}, ...]} */
    @Select("SELECT status, COUNT(*) AS cnt FROM t_order GROUP BY status")
    List<Map<String, Object>> countAllByStatus();

    /**
     * 某买家自己的订单按状态统计 —— 买家端订单页二级 Tab 角标的数据源。
     *
     * <p>必须是**服务端全量分桶**：角标要覆盖「我的全部订单」而不是「当前页里那几条」，
     * 前端拿 {@code items.filter(status)} 当角标会把第 2 页的待支付整个漏掉，
     * 表现为「Tab 上没数字、点进去却有待支付」。
     *
     * <p><b>2026-10-27 · 阅后即消</b>：{@code CLOSED / REFUNDED} 是终态，用户点进详情看过之后
     * 不该再占着角标 —— 用 {@code t_order_read} 把这部分减掉。持续提醒类
     * （{@code PENDING_PAYMENT / PAID / SHIPPED}）**不受已读影响**：它们是「还没办完的事」，
     * 读过也必须留着，否则用户会漏付、漏收货。
     *
     * <p>可消状态的名单与 {@code shared/lib/badge-rules.ts} 一一对应，改一处必须同时改另一处。
     *
     * @return 形如 {@code [{status: "PAID", cnt: 3}, ...]}；无订单时返回空列表
     */
    @Select("SELECT o.status AS status, COUNT(*) AS cnt FROM t_order o "
            + "WHERE o.user_id = #{userId} "
            + "AND NOT (o.status IN ('CLOSED','REFUNDED') "
            + "         AND EXISTS (SELECT 1 FROM t_order_read r "
            + "                     WHERE r.user_id = #{userId} AND r.order_id = o.id)) "
            + "GROUP BY o.status")
    List<Map<String, Object>> countByBuyerAndStatus(@Param("userId") Long userId);

    /**
     * 卖家端订单页签角标（2026-10-27）：与 {@link #countBySellerAndStatus} 的区别是
     * 「已发货 / 已完成 / 已关闭 / 已退款」读过的要从计数里减掉。
     *
     * <p><b>为什么「待发货」不减</b>：那是卖家还没办的事，点进去看过也得继续提醒 ——
     * 这与买家侧「待支付/待收货不减」是同一条规则。
     *
     * <p><b>为什么发货后「已发货」不会 +1</b>：卖家点「发货」时服务端顺手写了一条
     * 卖家已读（{@code SellerService#ship}），于是这条订单在「已发货」分桶里
     * 从诞生那一刻就是「已读过」—— 自己操作出来的状态不该算成新未读。
     */
    @Select("<script>"
            + "SELECT o.status AS status, COUNT(*) AS cnt FROM t_order o "
            + "WHERE o.seller_id IN "
            + "<foreach collection='sellerIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "AND NOT (o.status IN ('SHIPPED','COMPLETED','CLOSED','REFUNDED') "
            + "         AND EXISTS (SELECT 1 FROM t_order_read r "
            + "                     WHERE r.user_id = #{userId} AND r.order_id = o.id)) "
            + "GROUP BY o.status"
            + "</script>")
    List<Map<String, Object>> countBySellerAndStatusForBadge(@Param("sellerIds") Collection<Long> sellerIds,
                                                            @Param("userId") Long userId);

    /**
     * 记录「某人看过某订单」：复合主键 + {@code INSERT IGNORE}，重复上报天然幂等。
     *
     * <p>只影响**阅后即消**那几类状态的角标，不会改变订单本身的任何业务状态。
     */
    @org.apache.ibatis.annotations.Insert(
            "INSERT IGNORE INTO t_order_read (user_id, order_id, read_at) VALUES (#{userId}, #{orderId}, #{at})")
    int markOrderRead(@Param("userId") Long userId,
                      @Param("orderId") Long orderId,
                      @Param("at") java.time.LocalDateTime at);

    /**
     * 当前页哪些订单已被本人看过（2026-10-27 · 列表「橙色未读印记」的数据源）。
     *
     * <p>只查当前页的 id 集合（不整表扫）：列表接口本来就要取这一页，
     * 再来一条 {@code IN (...)} 的索引查询是常量级开销；
     * 反过来「把已读集合全捞回来再和页面做交集」会随用户历史线性增长。
     */
    @Select("<script>"
            + "SELECT order_id FROM t_order_read WHERE user_id = #{userId} AND order_id IN "
            + "<foreach collection='orderIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<Long> listReadOrderIds(@Param("userId") Long userId,
                                @Param("orderIds") Collection<Long> orderIds);

    // ---------------- 图表聚合（第六十六轮） ----------------
    //
    // 四条查询的公共点：都**只返回聚合结果**，绝不把明细捞回来在内存里算。
    // 图表数据比统计卡更容易失控 —— 一次 14 天 × 全平台的明细在演示库就有几千行，
    // 上线后随时间单调增长；而这类慢查询往往要等到数据库 CPU 打满才被发现。
    //
    // sellerIds 传 null 表示「全平台」（运营后台用），传集合表示「这几个卖家」（商家端用）。
    // 刻意用同一个方法而不是写两份 SQL：两份 SQL 迟早在某次口径调整后只改一处。

    /**
     * 按天聚合：当日下单数 + 当日成交额（分）。
     *
     * <p>口径说明（写清楚是为了避免后来者按直觉改）：
     * <ul>
     *   <li>日期取 {@code DATE(created_at)} —— 容器时区全为 UTC，所以这里是 **UTC 自然日**；
     *       北京时间 00:00–08:00 的订单会被算进前一天。这是刻意与存储口径保持一致，
     *       而不是在 SQL 里偷偷 +8 小时（那种「藏在查询里的时区假设」最难排查）。</li>
     *   <li>成交额只累计「已付款及之后」的订单；待付款与已关闭的钱从未收过，计 0。</li>
     * </ul>
     */
    @Select("<script>"
            + "SELECT DATE(created_at) AS d, COUNT(*) AS cnt, "
            + "COALESCE(SUM(CASE WHEN status IN ('PAID','SHIPPED','COMPLETED','REFUNDED') "
            + "THEN pay_amount ELSE 0 END), 0) AS gmv "
            + "FROM t_order WHERE created_at &gt;= #{from} "
            + "<if test='sellerIds != null'>AND seller_id IN "
            + "<foreach collection='sellerIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "</if>"
            + "GROUP BY DATE(created_at) ORDER BY d"
            + "</script>")
    List<Map<String, Object>> dailyTrend(@Param("sellerIds") Collection<Long> sellerIds,
                                         @Param("from") java.time.LocalDateTime from);

    /**
     * 商品销量 Top N（按件数）。
     *
     * <p>只统计「已付款及之后」的订单 —— 待付款/已关闭的商品不该出现在销量榜上，
     * 否则卖家会照着排行榜去补一个根本没人买过的货。
     *
     * <p>{@code GROUP BY i.spu_id} + {@code MAX(i.title)}：同一个 SPU 的历史订单标题
     * 可能因为改过名而不一致，按 spu_id 归一才是一条商品；取 MAX 是为了在
     * {@code ONLY_FULL_GROUP_BY} 下也能合法地挑出一个展示名。
     */
    @Select("<script>"
            + "SELECT MAX(i.title) AS name, COALESCE(SUM(i.quantity), 0) AS qty "
            + "FROM t_order_item i JOIN t_order o ON o.id = i.order_id "
            + "WHERE o.status IN ('PAID','SHIPPED','COMPLETED','REFUNDED') "
            + "<if test='sellerIds != null'>AND o.seller_id IN "
            + "<foreach collection='sellerIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "</if>"
            + "GROUP BY i.spu_id ORDER BY qty DESC, i.spu_id ASC LIMIT #{limit}"
            + "</script>")
    List<Map<String, Object>> topProductsBySales(@Param("sellerIds") Collection<Long> sellerIds,
                                                 @Param("limit") int limit);

    /**
     * 按商品的经营统计（2026-10-24，商家端商品分析）。
     *
     * <p>为什么不能读 {@code t_spu.sales_count}：那一列全仓只有建商品时写 0，
     * 下单/支付都不回写 —— 它是个"看起来像销量"的常量。真实销量只能从订单明细聚合。
     *
     * <p>口径：成交状态含退款（这些单确实成交过），退款件数单独出一列，
     * 让商家看到"卖出去多少 / 退回来多少"，而不是把退款单从分母里悄悄扣掉。
     */
    @Select("<script>"
            + "SELECT i.spu_id AS spuId, MAX(i.title) AS name, "
            + "COALESCE(SUM(i.quantity), 0) AS qty, "
            + "COALESCE(SUM(i.amount), 0) AS amount, "
            + "COALESCE(SUM(CASE WHEN i.refund_status IN ('REFUNDING','REFUNDED') THEN i.quantity ELSE 0 END), 0) AS refundQty, "
            + "COUNT(DISTINCT o.id) AS orderCnt "
            + "FROM t_order_item i JOIN t_order o ON o.id = i.order_id "
            + "WHERE o.status IN ('PAID','SHIPPED','COMPLETED','REFUNDED') "
            + "<if test='sellerIds != null'>AND o.seller_id IN "
            + "<foreach collection='sellerIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "</if>"
            + "GROUP BY i.spu_id ORDER BY qty DESC, i.spu_id ASC LIMIT #{limit}"
            + "</script>")
    List<Map<String, Object>> productStatsBySeller(@Param("sellerIds") Collection<Long> sellerIds,
                                                   @Param("limit") int limit);

    /**
     * 按商品 / SKU 的退款统计（D-05）。
     *
     * <p>只统计有退款明细的 SKU；金额取明细行金额（下单时快照），
     * 订单数按 {@code o.id} 去重 —— 同一订单退两个 SKU 不会把订单数算两次。
     */
    @Select("<script>"
            + "SELECT i.spu_id AS spuId, MAX(i.title) AS name, i.sku_id AS skuId, "
            + "MAX(i.spec_text) AS specText, "
            + "COALESCE(SUM(CASE WHEN i.refund_status IN ('REFUNDING','REFUNDED') "
            + "THEN i.quantity ELSE 0 END), 0) AS refundQty, "
            + "COALESCE(SUM(CASE WHEN i.refund_status IN ('REFUNDING','REFUNDED') "
            + "THEN i.amount ELSE 0 END), 0) AS refundAmount, "
            + "COUNT(DISTINCT CASE WHEN i.refund_status IN ('REFUNDING','REFUNDED') "
            + "THEN o.id END) AS refundOrderCount "
            + "FROM t_order_item i JOIN t_order o ON o.id = i.order_id "
            + "WHERE o.status IN ('PAID','SHIPPED','COMPLETED','REFUNDED') "
            + "<if test='sellerIds != null'>AND o.seller_id IN "
            + "<foreach collection='sellerIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "</if>"
            + "GROUP BY i.spu_id, i.sku_id "
            + "HAVING refundQty > 0 "
            + "ORDER BY refundAmount DESC, i.spu_id ASC, i.sku_id ASC LIMIT #{limit}"
            + "</script>")
    List<Map<String, Object>> productRefundStatsBySeller(@Param("sellerIds") Collection<Long> sellerIds,
                                                         @Param("limit") int limit);

    /** 订单类型分布（NORMAL / SECKILL），返回形如 {@code [{t:"SECKILL", cnt:28}, ...]} */
    @Select("<script>"
            + "SELECT order_type AS t, COUNT(*) AS cnt FROM t_order "
            + "<if test='sellerIds != null'>WHERE seller_id IN "
            + "<foreach collection='sellerIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "</if>"
            + "GROUP BY order_type"
            + "</script>")
    List<Map<String, Object>> countByOrderType(@Param("sellerIds") Collection<Long> sellerIds);

    /** 已付款订单的实付总额（GMV）：PAID / SHIPPED / COMPLETED 都算「钱已进平台托管」 */
    @Select("SELECT COALESCE(SUM(pay_amount), 0) FROM t_order "
            + "WHERE status IN ('PAID','SHIPPED','COMPLETED')")
    long sumPaidAmount();

    /**
     * 异常挂账：已付款却缺托管单的订单。
     *
     * <p>资金进入平台托管会同步写 t_settlement；这里用 LEFT JOIN 找出「有订单、没账本」的
     * 漏网之鱼。这种订单在确认收货放款时会因为找不到托管单而失败，必须在后台暴露出来。
     */
    @Select("SELECT o.order_no AS orderNo, o.status AS orderStatus, o.pay_amount AS payAmount "
            + "FROM t_order o LEFT JOIN t_settlement s ON s.order_id = o.id "
            + "WHERE o.status IN ('PAID','SHIPPED','COMPLETED') AND s.id IS NULL "
            + "ORDER BY o.id DESC LIMIT 100")
    List<Map<String, Object>> findOrdersWithoutSettlement();

    /**
     * 托管单回补扫描（A1 / I-14）：找出「已有资金事实、却没有账本」的**全部**订单。
     *
     * <p>与 {@link #findOrdersWithoutSettlement()} 的三点刻意差异，都是踩过才知道的：
     * <ol>
     *   <li><b>范围多一类</b>：额外覆盖 {@code REFUNDED}。退款终态的订单同样需要回答「这笔钱现在在谁手里」，
     *       只是它不出现在异常列表里（{@code abnormal} 的定义是「已付款却缺托管单」，
     *       退款的单已经被处理过了，不算挂账）；</li>
     *   <li><b>不 LIMIT</b>：异常列表给人看，限 100 条足够；回补要一次补齐，只取 100 条会导致
     *       「跑完一遍 abnormal 还不为空」这种看起来像没修好的现象；</li>
     *   <li><b>带出时间戳</b>：补 {@code COMPLETED} 时要用该订单真实的 {@code finished_at} 当放款时间 ——
     *       回补的意义是与历史对账对得上，用「脚本执行那一刻」会让对账差异无法解释。</li>
     * </ol>
     *
     * <p>返回 {@code Map} 而非实体：这里只需要少数字段，走实体会把收货人信息一起捞进内存，
     * 而回补是运营侧批量操作，没有理由让这些隐私字段参与其中。
     */
    @Select("SELECT o.id AS id, o.order_no AS orderNo, o.seller_id AS sellerId, o.status AS orderStatus, "
            + "o.pay_amount AS payAmount, o.paid_at AS paidAt, o.finished_at AS finishedAt "
            + "FROM t_order o LEFT JOIN t_settlement s ON s.order_id = o.id "
            + "WHERE o.status IN ('PAID','SHIPPED','COMPLETED','REFUNDED') AND s.id IS NULL "
            + "ORDER BY o.id ASC")
    List<Map<String, Object>> findOrdersNeedingSettlementBackfill();

    // ---------------- E24 · 租户健康度（按卖家 × 时间窗聚合） ----------------
    //
    // 与上面那批图表聚合同一姿势：只回聚合结果，绝不把明细捞回内存再数。
    // 区别是分组键从"全平台 / 指定卖家集合"换成"seller_id 自身" ——
    // 面板要的就是一行一个租户。

    /**
     * 窗口内每个卖家的订单量 / 已付款单数 / 成交额（分）。
     *
     * <h2>窗口取 {@code created_at}（下单时刻），不是 {@code paid_at}</h2>
     * 与 S3 对账、E22 账单、{@code dailyTrend} 一样，全站"某一笔业务算在哪一天"
     * 一律按**业务发生（创建）时刻**的 UTC 自然日。用 {@code paid_at} 会让
     * 「未付款的单」既不在任何窗口里、又真实存在 —— 于是订单量永远对不上订单列表。
     *
     * <h2>★ 「已付款」的口径含 {@code REFUNDED}，这是刻意与 {@code dailyTrend} 对齐</h2>
     * 售后率的分母是「本店这段时间成交了多少单」。退款单**当初确实付过款**
     * （钱进过托管、也进过当天的成交额），把它从分母里剔除会让一次退款
     * 同时抬高分子、压低分母，售后率被双重放大。
     * 这与 {@code dailyTrend} 里 gmv 包含 {@code REFUNDED} 是同一个口径，
     * 两个页面看到的分母因此不会互相打架。
     * （注意 {@code sumPaidAmount()} 用的是 PAID/SHIPPED/COMPLETED —— 那是"钱仍在平台托管"
     * 的另一个语义，服务于资金汇总，不是这里的"成交单数"。）
     *
     * <p>{@code seller_id} 为 0 的行是**平台自营**，不是"没有归属" ——
     * 服务层刻意保留它（自营也是一个租户，面板上要能看到它的接口错误率与售后率）。
     */
    @Select("SELECT seller_id AS sellerId, COUNT(*) AS cnt, "
            + "COALESCE(SUM(CASE WHEN status IN ('PAID','SHIPPED','COMPLETED','REFUNDED') "
            + "THEN 1 ELSE 0 END), 0) AS paidCnt, "
            + "COALESCE(SUM(CASE WHEN status IN ('PAID','SHIPPED','COMPLETED','REFUNDED') "
            + "THEN pay_amount ELSE 0 END), 0) AS gmv "
            // ★ 这里必须写**字面量** >= / <，不能写 XML 实体 &gt;= / &lt;：
            //   实体只在字符串以 <script> 开头时才会被 MyBatis 的 XML 解析器解码；
            //   本 SQL 没有 <script>，实体原样进库 ⇒ MP 的 JSqlParser 解析时
            //   把 &gt 当标识符、第一个 = 当运算符，报 "unexpected token: =" 并以 500 收场。
            //   反面参照：同文件的 dailyTrend 在 <script> 内，那里写实体才是对的。
            + "FROM t_order WHERE created_at >= #{from} AND created_at < #{to} "
            + "GROUP BY seller_id")
    List<Map<String, Object>> tenantHealthOrders(@Param("from") java.time.LocalDateTime from,
                                                 @Param("to") java.time.LocalDateTime to);

    /**
     * E24 · 每个租户「已付款却缺托管单」的订单数与金额（**不带时间窗**）。
     *
     * <p>判定条件与 {@link #findOrdersWithoutSettlement()} 逐字一致
     * （{@code PAID/SHIPPED/COMPLETED} + {@code LEFT JOIN … IS NULL}），
     * 只是把 LIMIT 100 的列表换成按卖家分组 —— 面板要的是"这家店有几笔挂账"，
     * 而不是"全平台前 100 笔里有没有它"。列表仍然由 {@code /api/admin/settlements/abnormal} 提供。
     *
     * <p>不带时间窗：挂账是**存量**，一笔三个月前的挂账今天仍然会让这单放不了款。
     */
    @Select("SELECT o.seller_id AS sellerId, COUNT(*) AS cnt, "
            + "COALESCE(SUM(o.pay_amount), 0) AS amount "
            + "FROM t_order o LEFT JOIN t_settlement s ON s.order_id = o.id "
            + "WHERE o.status IN ('PAID','SHIPPED','COMPLETED') AND s.id IS NULL "
            + "GROUP BY o.seller_id")
    List<Map<String, Object>> missingSettlementBySeller();
}
