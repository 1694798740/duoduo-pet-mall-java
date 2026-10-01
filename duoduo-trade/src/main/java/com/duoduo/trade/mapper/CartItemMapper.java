package com.duoduo.trade.mapper;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.trade.entity.CartItemEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CartItemMapper extends BaseMapper<CartItemEntity> {

    /**
     * 数量原子自增（并发加购时「行已存在」的兜底路径）。
     *
     * <p>走**当前读**（读最新已提交版本并加行锁），不受事务快照限制，也不会互相覆盖。
     *
     * @return 影响行数；0 = 行不存在（被并发删除）。调用方必须据此决定怎么办，不能当成成功
     */
    @Update("""
            UPDATE t_cart_item
               SET quantity = quantity + #{delta},
                   selected = 1,
                   updated_at = #{now}
             WHERE user_id = #{userId}
               AND sku_id = #{skuId}
            """)
    int addQuantity(@Param("userId") Long userId,
                    @Param("skuId") Long skuId,
                    @Param("delta") int delta,
                    @Param("now") LocalDateTime now);

    /**
     * 加购主路径：**一条语句完成「不在就插、在就累加」**。
     *
     * <p>为什么不让服务层「先 SELECT → 不在就 INSERT → 撞唯一键再 UPDATE」：
     * 那个两段式在并发下会**死锁** —— 失败的 INSERT 会在唯一索引上留下锁（S 锁 / 间隙锁），
     * 十来个事务互相持有对方要的锁，MySQL 直接判死锁回滚
     * （实测并发 10 加购 200 次，8 次 500，日志全是 Deadlock found）。
     * {@code ON DUPLICATE KEY UPDATE} 由数据库在**同一条语句**里判定，不存在这个中间态。
     *
     * @return 1 = 新插入，2 = 走累加分支
     */
    @Insert("""
            INSERT INTO t_cart_item
                (id, user_id, spu_id, sku_id, quantity, selected, created_at, updated_at)
            VALUES
                (#{id}, #{userId}, #{spuId}, #{skuId}, #{quantity}, 1, #{now}, #{now})
            ON DUPLICATE KEY UPDATE
                quantity = quantity + VALUES(quantity),
                selected = 1,
                updated_at = VALUES(updated_at)
            """)
    int upsert(@Param("id") Long id,
               @Param("userId") Long userId,
               @Param("spuId") Long spuId,
               @Param("skuId") Long skuId,
               @Param("quantity") int quantity,
               @Param("now") LocalDateTime now);
}
