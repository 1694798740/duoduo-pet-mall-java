package com.duoduo.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.user.entity.CoinFreezeEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 多多币冻结单数据访问。状态迁移一律 CAS 条件更新。
 */
@Mapper
public interface CoinFreezeMapper extends BaseMapper<CoinFreezeEntity> {

    /** 创建冻结单（幂等，撞 uk_coin_freeze_order 静默跳过）。返回 1 = 本次创建。单实体入参，占位符直接绑实体属性 */
    @Insert("INSERT IGNORE INTO t_coin_freeze (id, user_id, order_no, coins, status, created_at, updated_at) "
            + "VALUES (#{id}, #{userId}, #{orderNo}, #{coins}, 'FROZEN', #{createdAt}, #{updatedAt})")
    int insertIgnore(CoinFreezeEntity freeze);

    /** 冻结 → 已扣减（支付成功）：只有 FROZEN 能推进 */
    @Update("UPDATE t_coin_freeze SET status = 'DEDUCTED', updated_at = #{at} "
            + "WHERE order_no = #{orderNo} AND status = 'FROZEN'")
    int markDeducted(@Param("orderNo") String orderNo, @Param("at") LocalDateTime at);

    /** 冻结 → 已释放（取消 / 超时关单）：只有 FROZEN 能推进 */
    @Update("UPDATE t_coin_freeze SET status = 'RELEASED', updated_at = #{at} "
            + "WHERE order_no = #{orderNo} AND status = 'FROZEN'")
    int markReleased(@Param("orderNo") String orderNo, @Param("at") LocalDateTime at);
}
