package com.duoduo.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.trade.entity.OrderPlaceTaskEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 下单任务台账 Mapper（A11）。
 *
 * <p>状态迁移用「按业务键 UPDATE」而不是「查出来改完再存」：
 * 消费者与入队端点并发更新同一条任务时，CAS 语义让后到者覆盖前到者也无妨 ——
 * 最终状态只可能是 PLACED / FAILED 之一，而「重复重放」由订单侧唯一索引兜底。
 */
public interface OrderPlaceTaskMapper extends BaseMapper<OrderPlaceTaskEntity> {

    /** 消费成功：PENDING → PLACED（幂等键定位，不动其它字段） */
    @Update("UPDATE t_order_place_task SET status = 'PLACED', fail_reason = NULL, updated_at = UTC_TIMESTAMP() "
            + "WHERE user_id = #{userId} AND idempotent_key = #{idempotentKey}")
    int markPlaced(@Param("userId") Long userId, @Param("idempotentKey") String idempotentKey);

    /** 业务性失败：PENDING → FAILED（带原因；调用方负责把原因截断到列宽内） */
    @Update("UPDATE t_order_place_task SET status = 'FAILED', fail_reason = #{failReason}, updated_at = UTC_TIMESTAMP() "
            + "WHERE user_id = #{userId} AND idempotent_key = #{idempotentKey} AND status = 'PENDING'")
    int markFailed(@Param("userId") Long userId, @Param("idempotentKey") String idempotentKey,
                   @Param("failReason") String failReason);
}
