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
}
