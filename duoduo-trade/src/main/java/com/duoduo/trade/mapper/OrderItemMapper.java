package com.duoduo.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.trade.entity.OrderItemEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItemEntity> {

    /**
     * 我已购买的商品（按 spu 去重，最近一次下单在前）。
     *
     * <p>只算真正付过钱的单：PAID / SHIPPED / COMPLETED。
     * 未支付、已关闭、已退款不算「已购买」—— 这是用户直觉，也是客服场景里
     * 「我买的这个」能否成立的前提。
     */
    @Select("""
            SELECT i.spu_id AS spuId,
                   MAX(i.title) AS title,
                   MAX(i.main_image) AS mainImage,
                   MAX(i.price) AS price,
                   MAX(o.created_at) AS lastAt
            FROM t_order_item i
                JOIN t_order o ON o.id = i.order_id
            WHERE o.user_id = #{userId}
              AND o.status IN ('PAID', 'SHIPPED', 'COMPLETED')
            GROUP BY i.spu_id
            ORDER BY lastAt DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<PurchasedProductRow> selectPurchased(@Param("userId") Long userId,
                                              @Param("limit") int limit,
                                              @Param("offset") int offset);

    /** 去重后的商品总数（分页 total） */
    @Select("""
            SELECT COUNT(DISTINCT i.spu_id)
            FROM t_order_item i JOIN t_order o ON o.id = i.order_id
            WHERE o.user_id = #{userId} AND o.status IN ('PAID', 'SHIPPED', 'COMPLETED')
            """)
    long countPurchased(@Param("userId") Long userId);

    /**
     * 批量统计 SKU 被订单明细引用的次数（A-14）。
     *
     * <p>不过滤订单状态：取消、退款、已完成的订单都算「这个规格已经卖过」。
     * 商品域用它在删除规格前做硬门禁 —— 规格一旦进入过订单，物理删除会让
     * 售后与对账失去可追溯的锚点，宁可让卖家改名复用，也不能删掉。
     */
    @Select("""
            <script>
            SELECT sku_id AS skuId, COUNT(*) AS orderCount
            FROM t_order_item
            WHERE sku_id IN
            <foreach collection="skuIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY sku_id
            </script>
            """)
    List<SkuUsageRow> countBySkuIds(@Param("skuIds") Collection<Long> skuIds);
}
