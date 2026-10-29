package com.duoduo.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.user.entity.CoinTaskEventEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * 多多币任务事件数据访问。写入只有 INSERT IGNORE 一条口子 ——
 * 幂等完全靠 {@code uk_coin_task_event (user_id, task_code, biz_date, biz_id)} 兜底。
 */
@Mapper
public interface CoinTaskEventMapper extends BaseMapper<CoinTaskEventEntity> {

    /** 记录任务事件（幂等，撞唯一键静默跳过）。返回 1 = 本次首次计数 */
    @Insert("INSERT IGNORE INTO t_coin_task_event (id, user_id, task_code, biz_date, biz_id, created_at) "
            + "VALUES (#{id}, #{userId}, #{taskCode}, #{bizDate}, #{bizId}, #{createdAt})")
    int insertIgnore(CoinTaskEventEntity row);
}
