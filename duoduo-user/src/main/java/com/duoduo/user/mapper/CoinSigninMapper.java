package com.duoduo.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.user.entity.CoinSigninEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * 多多币签到数据访问。写入只有 INSERT IGNORE 一条口子 ——
 * 幂等完全靠 {@code uk_coin_signin (user_id, sign_date)} 唯一索引兜底。
 */
@Mapper
public interface CoinSigninMapper extends BaseMapper<CoinSigninEntity> {

    /**
     * 创建签到行（幂等，撞 uk_coin_signin 静默跳过）。返回 1 = 本次创建。
     * 单实体入参，占位符直接绑实体属性（T-68：不能用 #{at} 这类自选名）。
     */
    @Insert("INSERT IGNORE INTO t_coin_signin (id, user_id, sign_date, coins, continuous_days, created_at) "
            + "VALUES (#{id}, #{userId}, #{signDate}, #{coins}, #{continuousDays}, #{createdAt})")
    int insertIgnore(CoinSigninEntity row);
}
