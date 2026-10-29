package com.duoduo.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.user.entity.CoinTaskDefEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 多多币任务定义数据访问。任务定义只由运营后台修改（走审计），
 * 服务层按 code / enabled / sort_no 查询，无自定义 SQL。
 */
@Mapper
public interface CoinTaskDefMapper extends BaseMapper<CoinTaskDefEntity> {
}
