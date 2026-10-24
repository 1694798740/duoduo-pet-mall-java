package com.duoduo.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.product.entity.SpuAuditLogEntity;
import org.apache.ibatis.annotations.Mapper;

/** 商品审核历史（A-08）：append-only，只有 insert 与按商品倒序读 */
@Mapper
public interface SpuAuditLogMapper extends BaseMapper<SpuAuditLogEntity> {
}
