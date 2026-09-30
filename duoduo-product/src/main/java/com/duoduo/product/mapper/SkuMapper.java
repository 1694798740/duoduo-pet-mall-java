package com.duoduo.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.product.entity.SkuEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * SKU 数据访问。
 *
 * <p>库存三操作（冻结 / 释放 / 扣减）刻意写成**原子条件更新**，而不是
 * 「先 SELECT 判断、再 UPDATE」：
 * <ul>
 *   <li>「查完再改」在两个并发请求之间天然存在窗口，是超卖的经典来源；</li>
 *   <li>把判断条件写进 WHERE，由数据库的行锁与原子性保证「要么改成功、要么影响 0 行」，
 *       调用方按受影响行数判断成败即可，**不需要分布式锁**；</li>
 *   <li>用 {@code COALESCE} 兼容历史数据里可能为 NULL 的冻结量 ——
 *       否则 NULL 参与运算会让比较结果为 NULL，更新**静默不生效**（最难排查的一类问题）。</li>
 * </ul>
 */
@Mapper
public interface SkuMapper extends BaseMapper<SkuEntity> {

    /**
     * 冻结库存（下单时调用）：可售余量足够才成功。
     *
     * @return 受影响行数；1 = 冻结成功，0 = 余量不足或 SKU 已停用
     */
    @Update("UPDATE t_sku SET frozen_stock = COALESCE(frozen_stock, 0) + #{quantity}, "
            + "updated_at = #{updatedAt} "
            + "WHERE id = #{skuId} AND status = 'ACTIVE' "
            + "AND stock - COALESCE(frozen_stock, 0) >= #{quantity}")
    int freezeStock(@Param("skuId") Long skuId,
                    @Param("quantity") int quantity,
                    @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 释放冻结（取消订单 / 超时关单时调用）。
     *
     * @return 1 = 释放成功，0 = 冻结量不足（重复释放或数据异常）
     */
    @Update("UPDATE t_sku SET frozen_stock = COALESCE(frozen_stock, 0) - #{quantity}, "
            + "updated_at = #{updatedAt} "
            + "WHERE id = #{skuId} AND COALESCE(frozen_stock, 0) >= #{quantity}")
    int releaseStock(@Param("skuId") Long skuId,
                     @Param("quantity") int quantity,
                     @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 扣减库存（支付成功时调用）：真实库存与冻结量同时减少。
     *
     * <p>两个条件都必须在 WHERE 里，否则可能出现「库存扣了但冻结没减」这种对不上账的状态。
     *
     * @return 1 = 扣减成功，0 = 冻结量或库存不足
     */
    @Update("UPDATE t_sku SET stock = stock - #{quantity}, "
            + "frozen_stock = COALESCE(frozen_stock, 0) - #{quantity}, "
            + "updated_at = #{updatedAt} "
            + "WHERE id = #{skuId} AND stock >= #{quantity} "
            + "AND COALESCE(frozen_stock, 0) >= #{quantity}")
    int deductStock(@Param("skuId") Long skuId,
                    @Param("quantity") int quantity,
                    @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 登记一条入库幂等记录（第五十八轮 · B-02）。
     *
     * <p>用 {@code INSERT IGNORE} 撞主键：主键即去重键 {@code (ref_no, sku_id)}，
     * 首次调用插入成功（返回 1），**重复调用返回 0 且不报错**。
     * 幂等性完全交给数据库唯一键，而不是「先 SELECT 有没有、没有再 INSERT」——
     * 后者在并发重试下两个请求都会查到「没有」，于是都插入、都加库存（双写）。
     *
     * @return 1 = 本次是首次入库（可以加库存）；0 = 该 (ref_no, sku_id) 已入过库（必须跳过加库存）
     */
    @Insert("INSERT IGNORE INTO t_sku_restock_log (ref_no, sku_id, quantity) "
            + "VALUES (#{refNo}, #{skuId}, #{quantity})")
    int insertRestockLogIgnore(@Param("refNo") String refNo,
                               @Param("skuId") Long skuId,
                               @Param("quantity") int quantity);

    /**
     * 退货入库（退款成功后调用）：真实库存回补。
     *
     * <p><b>调用方必须先过 {@link #insertRestockLogIgnore} 这道闸</b>（见 {@code StockService}）：
     * 只有「首次插入」才允许执行本更新。本更新本身没有幂等护栏 ——
     * 重复执行会真的多加库存且不报任何错。
     *
     * <p>与前三个操作不同，这里<b>刻意不加 {@code status = 'ACTIVE'} 条件</b>：
     * 库存是**物理事实**。商品在下架期间发生退货，货照样要回到仓库，
     * 如果因为「已下架」就拒绝入库，系统里就看不见这批货，对账时凭空多出一笔差额，
     * 而且没人知道差在哪。下架只应影响「能不能卖」，不该影响「能不能入库」。
     *
     * <p>唯一的失败原因是 SKU 不存在 —— 那属于数据异常，需要人工核对。
     *
     * @return 受影响行数；1 = 入库成功，0 = SKU 不存在
     */
    @Update("UPDATE t_sku SET stock = stock + #{quantity}, "
            + "updated_at = #{updatedAt} "
            + "WHERE id = #{skuId}")
    int restockStock(@Param("skuId") Long skuId,
                     @Param("quantity") int quantity,
                     @Param("updatedAt") LocalDateTime updatedAt);
}
