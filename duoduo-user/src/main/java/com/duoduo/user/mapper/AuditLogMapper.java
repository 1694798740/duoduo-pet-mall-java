package com.duoduo.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.user.entity.AuditLogEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLogEntity> {
}
