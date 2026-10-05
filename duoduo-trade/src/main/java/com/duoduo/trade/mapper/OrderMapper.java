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
}
