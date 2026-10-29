package com.duoduo.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.user.entity.CoinCampaignEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 多多币活动数据访问。预算扣减必须原子：条件写进 WHERE，不依赖应用层先查后算。
 */
@Mapper
public interface CoinCampaignMapper extends BaseMapper<CoinCampaignEntity> {

    /**
     * 原子占用活动预算：{@code budget_total = 0} 视为不限预算；
     * 否则 {@code budget_used + extra <= budget_total} 才成功。返回 0 = 预算不足。
     */
    @Update("UPDATE t_coin_campaign SET budget_used = budget_used + #{extra}, updated_at = #{at} "
            + "WHERE id = #{id} AND status = 'ENABLED' "
            + "AND (budget_total = 0 OR budget_used + #{extra} <= budget_total)")
    int consumeBudget(@Param("id") Long id, @Param("extra") long extra, @Param("at") LocalDateTime at);
}
