package com.duoduo.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.user.entity.CoinCampaignUserEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 多多币活动每人累计数据访问。
 *
 * <p>每人上限的判定与累加落在同一条条件 UPDATE 上：
 * {@code per_user_cap = 0} 视为不限；否则 {@code awarded + extra <= per_user_cap} 才成功。
 * 并发加成只可能有一个线程把额度吃掉，不会出现「两人同时判定都还有余量」的超发。
 */
@Mapper
public interface CoinCampaignUserMapper extends BaseMapper<CoinCampaignUserEntity> {

    /** 创建活动每人累计行（幂等，撞 uk_coin_campaign_user 静默跳过） */
    @Insert("INSERT IGNORE INTO t_coin_campaign_user "
            + "(id, campaign_id, user_id, awarded, created_at, updated_at) "
            + "VALUES (#{id}, #{campaignId}, #{userId}, #{awarded}, #{createdAt}, #{updatedAt})")
    int insertIgnore(CoinCampaignUserEntity row);

    /** 占用每人额度：返回 0 = 已达上限 */
    @Update("UPDATE t_coin_campaign_user SET awarded = awarded + #{extra}, updated_at = #{at} "
            + "WHERE campaign_id = #{campaignId} AND user_id = #{userId} "
            + "AND (#{cap} = 0 OR awarded + #{extra} <= #{cap})")
    int addAwarded(@Param("campaignId") Long campaignId,
                   @Param("userId") Long userId,
                   @Param("extra") long extra,
                   @Param("cap") long cap,
                   @Param("at") LocalDateTime at);

    /** 预算占用失败时回退每人额度（与本方法调用方同事务） */
    @Update("UPDATE t_coin_campaign_user SET awarded = awarded - #{extra}, updated_at = #{at} "
            + "WHERE campaign_id = #{campaignId} AND user_id = #{userId} AND awarded >= #{extra}")
    int subtractAwarded(@Param("campaignId") Long campaignId,
                        @Param("userId") Long userId,
                        @Param("extra") long extra,
                        @Param("at") LocalDateTime at);
}
