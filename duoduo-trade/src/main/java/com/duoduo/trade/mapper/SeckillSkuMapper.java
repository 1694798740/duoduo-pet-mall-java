package com.duoduo.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.trade.entity.SeckillSkuEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SeckillSkuMapper extends BaseMapper<SeckillSkuEntity> {

    /**
     * 扣减一个秒杀配额（防超卖的正确性兜底）。
     *
     * <p>单条 UPDATE 的行锁天然原子：并发扣减在同一条行上串行执行，
     * {@code available_stock > 0} 条件保证扣不到负数 —— 影响行数为 0 即「已抢完」。
     * Redis 预扣只是挡在它前面的限流闸，这里才是最后的事实。
     */
    @Update("UPDATE t_seckill_sku SET available_stock = available_stock - 1, updated_at = UTC_TIMESTAMP() "
            + "WHERE id = #{id} AND available_stock > 0")
    int deductStock(@Param("id") Long id);

    /** 回补一个秒杀配额（订单关闭 / 取消时）。 */
    @Update("UPDATE t_seckill_sku SET available_stock = available_stock + 1, updated_at = UTC_TIMESTAMP() "
            + "WHERE id = #{id}")
    int restock(@Param("id") Long id);
}
