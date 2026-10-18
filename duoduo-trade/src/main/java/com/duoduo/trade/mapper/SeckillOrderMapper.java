package com.duoduo.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.trade.entity.SeckillOrderEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SeckillOrderMapper extends BaseMapper<SeckillOrderEntity> {

    /**
     * 用户在某个秒杀 SKU 上的**有效**订单数（未关闭）。
     *
     * <p>限购口径（2026-10-18 修复）：已关闭的订单（用户取消 / 超时关单 / 运营强关）
     * 不计入限购名额 —— 关单时活动配额已回补，限购计数若仍把这些订单算进去，
     * 用户会被已关闭的订单占死名额。退款（REFUNDED）**仍计入**：货款已退但确曾成交，
     * 是否释放退款订单的名额是独立的业务口径，不在本次修复范围内。
     *
     * <p>用途：Redis「超限」拒绝时的 DB 仲裁（见 SeckillService.preDeductQuietly）。
     */
    @Select("""
            SELECT COUNT(*) FROM t_seckill_order s
            JOIN t_order o ON o.order_no = s.order_no
            WHERE s.user_id = #{userId}
              AND s.seckill_sku_id = #{seckillSkuId}
              AND o.status <> 'CLOSED'
            """)
    long countActiveByUserAndSku(@Param("userId") Long userId, @Param("seckillSkuId") Long seckillSkuId);
}
