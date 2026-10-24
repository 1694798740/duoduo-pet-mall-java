package com.duoduo.product.mapper;

import com.duoduo.product.entity.SkuStockReservationEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 订单级库存预占台账访问。
 *
 * <p>不继承 {@code BaseMapper}：主键是 {@code (order_id, sku_id)} 复合键，
 * MyBatis-Plus 的单主键方法在这里没有正确语义，写出来反而会被误用。
 *
 * <p>并发口径：
 * <ul>
 *   <li>登记用 {@code INSERT IGNORE} 撞主键，幂等交给数据库唯一键；</li>
 *   <li>状态迁移前先 {@code SELECT ... FOR UPDATE}，把同一订单同一 SKU 的操作串行化，
 *       避免「释放」和「扣减」同时读到 FROZEN 后各自继续；</li>
 *   <li>状态迁移的 UPDATE 仍带 {@code status = 'FROZEN'} 条件，作为第二道防线。</li>
 * </ul>
 */
@Mapper
public interface SkuStockReservationMapper {

    @Insert("INSERT IGNORE INTO t_sku_stock_reservation "
            + "(order_id, sku_id, quantity, status, created_at, updated_at) "
            + "VALUES (#{orderId}, #{skuId}, #{quantity}, '"
            + SkuStockReservationEntity.STATUS_FROZEN + "', #{now}, #{now})")
    int insertIgnore(@Param("orderId") Long orderId,
                     @Param("skuId") Long skuId,
                     @Param("quantity") int quantity,
                     @Param("now") LocalDateTime now);

    @Select("SELECT order_id AS orderId, sku_id AS skuId, quantity, status, "
            + "created_at AS createdAt, updated_at AS updatedAt "
            + "FROM t_sku_stock_reservation "
            + "WHERE order_id = #{orderId} AND sku_id = #{skuId} FOR UPDATE")
    SkuStockReservationEntity selectForUpdate(@Param("orderId") Long orderId,
                                              @Param("skuId") Long skuId);

    @Update("UPDATE t_sku_stock_reservation SET status = '"
            + SkuStockReservationEntity.STATUS_RELEASED + "', updated_at = #{now} "
            + "WHERE order_id = #{orderId} AND sku_id = #{skuId} AND status = '"
            + SkuStockReservationEntity.STATUS_FROZEN + "'")
    int markReleased(@Param("orderId") Long orderId,
                     @Param("skuId") Long skuId,
                     @Param("now") LocalDateTime now);

    @Update("UPDATE t_sku_stock_reservation SET status = '"
            + SkuStockReservationEntity.STATUS_DEDUCTED + "', updated_at = #{now} "
            + "WHERE order_id = #{orderId} AND sku_id = #{skuId} AND status = '"
            + SkuStockReservationEntity.STATUS_FROZEN + "'")
    int markDeducted(@Param("orderId") Long orderId,
                     @Param("skuId") Long skuId,
                     @Param("now") LocalDateTime now);
}
