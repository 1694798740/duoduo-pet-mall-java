package com.duoduo.trade.mapper;

import com.duoduo.trade.mapper.ReconRows.DebtRow;
import com.duoduo.trade.mapper.ReconRows.FreezeRow;
import com.duoduo.trade.mapper.ReconRows.ItemQtyRow;
import com.duoduo.trade.mapper.ReconRows.OrderRow;
import com.duoduo.trade.mapper.ReconRows.SettlementRow;
import com.duoduo.trade.mapper.ReconRows.SkuFrozenRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 对账取数（S3）：只读，全部落在交易域自己的库上。
 *
 * <h2>为什么要有一个独立的取数 Mapper，而不是复用各业务的 Mapper</h2>
 * 对账的查询形态与业务查询不一样：
 * <ul>
 *   <li><b>按窗口取，而不是按主键取</b>：业务查询是"给我这一单"，对账是"给我这几天的全部"；</li>
 *   <li><b>业务 SQL 会被优化、会被加条件</b>（比如列表页后来加了"只看有效数据"），
 *       而对账必须看到**全部** —— 一个被业务查询过滤掉的异常，正需要对账把它捞出来。
 *       共用一条 SQL 时，业务侧顺手加的一个过滤条件会**静默**缩小对账覆盖面；</li>
 *   <li>对账不参与任何事务，也不写业务表。</li>
 * </ul>
 *
 * <h2>窗口（{@code from}）的取舍</h2>
 * 全表扫描逐年变慢，且对账要能长期每日跑。因此钱侧按"订单/托管单创建时间"取一个回看窗口
 * （默认 90 天，可配），<b>代价是窗口之外的历史问题不会被发现</b> ——
 * 这是明写的取舍，而不是"忘了看历史"。
 * 冻结侧例外：{@code FROZEN} 状态<b>不论多老都取</b>（它是唯一会持续占住库存的状态，
 * 而且正常情况下数量应该很少），其余状态才按窗口。
 *
 * <h2>聚合列必须显式 CAST</h2>
 * MySQL 的 {@code SUM(INT)} 返回 {@code DECIMAL}，JDBC 侧拿到的是 {@code BigDecimal}，
 * 映射到 {@code Long} 属性时会失败或按字符串转 —— 两种都是运行时才现形。
 * 因此所有 {@code SUM} 都写成 {@code CAST(... AS SIGNED)}，让返回值确定是 BIGINT。
 */
@Mapper
public interface ReconSourceMapper {

    /** 窗口内的订单 */
    @Select("SELECT id AS orderId, status, seller_id AS sellerId, pay_amount AS payAmount, "
            + "finished_at AS completedAt FROM t_order WHERE created_at >= #{from}")
    List<OrderRow> selectOrders(@Param("from") LocalDateTime from);

    /** 按 id 补取订单（用于判定"托管单是否孤儿"） */
    @Select("<script>SELECT id AS orderId, status, seller_id AS sellerId, pay_amount AS payAmount, "
            + "finished_at AS completedAt FROM t_order WHERE id IN "
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>"
            + "</script>")
    List<OrderRow> selectOrdersByIds(@Param("ids") Collection<Long> ids);

    /** 窗口内的托管单 */
    @Select("SELECT order_id AS orderId, seller_id AS sellerId, status, amount, "
            + "platform_fee AS platformFee, released_at AS releasedAt "
            + "FROM t_settlement WHERE created_at >= #{from}")
    List<SettlementRow> selectSettlements(@Param("from") LocalDateTime from);

    /** 窗口内的卖家欠款 */
    @Select("SELECT order_id AS orderId, amount, status FROM t_seller_debt WHERE created_at >= #{from}")
    List<DebtRow> selectDebts(@Param("from") LocalDateTime from);

    /**
     * 库存冻结台账 + 关联订单状态。
     *
     * <p>{@code FROZEN} 不限窗口，其余状态按窗口 —— 理由见类注释。
     */
    @Select("SELECT f.sku_id AS skuId, f.order_id AS orderId, f.quantity, f.status, "
            + "f.expire_at AS expireAt, o.status AS orderStatus "
            + "FROM t_stock_freeze f LEFT JOIN t_order o ON o.id = f.order_id "
            + "WHERE f.status = 'FROZEN' OR (f.status <> 'RESTOCKED' AND f.created_at >= #{from})")
    List<FreezeRow> selectFreezes(@Param("from") LocalDateTime from);

    /** 订单明细按 (订单 × SKU) 聚合的数量，用于与冻结数量对照 */
    @Select("<script>SELECT order_id AS orderId, sku_id AS skuId, "
            + "CAST(SUM(quantity) AS SIGNED) AS quantity FROM t_order_item WHERE order_id IN "
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach> "
            + "GROUP BY order_id, sku_id</script>")
    List<ItemQtyRow> selectItemQty(@Param("ids") Collection<Long> orderIds);

    /** 交易域按 SKU 汇总的「冻结中」数量（全部历史 —— 它要与商品域的累计值比） */
    @Select("SELECT sku_id AS skuId, CAST(SUM(quantity) AS SIGNED) AS quantity "
            + "FROM t_stock_freeze WHERE status = 'FROZEN' GROUP BY sku_id")
    List<SkuFrozenRow> selectTradeFrozenBySku();
}
