package com.duoduo.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.user.entity.CoinLotEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 多多币批次数据访问。扣减按「先到期先扣」，锁定与扣减在同一事务里完成。
 */
@Mapper
public interface CoinLotMapper extends BaseMapper<CoinLotEntity> {

    /** 批次剩余扣减：remaining >= coins 才成功（同一批次被并发扣时保护） */
    @Update("UPDATE t_coin_lot SET remaining = remaining - #{coins}, updated_at = #{at} "
            + "WHERE id = #{lotId} AND remaining >= #{coins}")
    int deductRemaining(@Param("lotId") Long lotId, @Param("coins") Long coins, @Param("at") LocalDateTime at);

    /** 退款退回原批次：仅当批次未过期时生效 */
    @Update("UPDATE t_coin_lot SET remaining = remaining + #{coins}, updated_at = #{now} "
            + "WHERE id = #{lotId} AND expire_at > #{now}")
    int addRemainingIfValid(@Param("lotId") Long lotId, @Param("coins") Long coins, @Param("now") LocalDateTime now);

    /** 过期批次剩余清零，返回 1 表示确实有过剩余（调用方凭此记 EXPIRE 流水并扣余额） */
    @Update("UPDATE t_coin_lot SET remaining = 0, updated_at = #{at} "
            + "WHERE id = #{lotId} AND remaining > 0")
    int clearRemainingIfAny(@Param("lotId") Long lotId, @Param("at") LocalDateTime at);
}
