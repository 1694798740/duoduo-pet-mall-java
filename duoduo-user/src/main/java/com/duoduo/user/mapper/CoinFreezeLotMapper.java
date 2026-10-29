package com.duoduo.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.user.entity.CoinFreezeLotEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 冻结批次明细数据访问。
 */
@Mapper
public interface CoinFreezeLotMapper extends BaseMapper<CoinFreezeLotEntity> {
}
