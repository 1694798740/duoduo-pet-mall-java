package com.duoduo.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.user.entity.CoinTaskProgressEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 多多币任务进度数据访问。
 *
 * <p>进度的三条写入口全部是**条件 UPDATE / INSERT IGNORE**，不做「先查再算再写」：
 * <ul>
 *   <li>{@link #insertIgnore} —— 撞 {@code uk_coin_task_progress} 即跳过（并发首次上报）；</li>
 *   <li>{@link #incrementProgress} —— 带 {@code progress < cap}，进度绝不超过每日上限；</li>
 *   <li>{@link #markRewarded} —— 带 {@code rewarded = 0}，同一进度行只可能发一次奖。</li>
 * </ul>
 */
@Mapper
public interface CoinTaskProgressMapper extends BaseMapper<CoinTaskProgressEntity> {

    /** 创建进度行（幂等，撞唯一键静默跳过）。单实体入参，占位符直接绑实体属性 */
    @Insert("INSERT IGNORE INTO t_coin_task_progress "
            + "(id, user_id, task_code, biz_date, progress, rewarded, reward_coins, created_at, updated_at) "
            + "VALUES (#{id}, #{userId}, #{taskCode}, #{bizDate}, #{progress}, #{rewarded}, "
            + "#{rewardCoins}, #{createdAt}, #{updatedAt})")
    int insertIgnore(CoinTaskProgressEntity row);

    /** 进度 +1（上限保护）：返回 0 = 已封顶或行不存在 */
    @Update("UPDATE t_coin_task_progress SET progress = progress + 1, updated_at = #{at} "
            + "WHERE user_id = #{userId} AND task_code = #{taskCode} AND biz_date = #{bizDate} "
            + "AND progress < #{cap}")
    int incrementProgress(@Param("userId") Long userId,
                          @Param("taskCode") String taskCode,
                          @Param("bizDate") LocalDate bizDate,
                          @Param("cap") int cap,
                          @Param("at") LocalDateTime at);

    /** 发奖闸门：rewarded 0 → 1。返回 1 = 本次抢到发放权，0 = 已发过（并发只成功一次） */
    @Update("UPDATE t_coin_task_progress SET rewarded = 1, reward_coins = #{coins}, updated_at = #{at} "
            + "WHERE user_id = #{userId} AND task_code = #{taskCode} AND biz_date = #{bizDate} "
            + "AND rewarded = 0")
    int markRewarded(@Param("userId") Long userId,
                     @Param("taskCode") String taskCode,
                     @Param("bizDate") LocalDate bizDate,
                     @Param("coins") int coins,
                     @Param("at") LocalDateTime at);
}
