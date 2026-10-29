package com.duoduo.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.social.entity.SocialNoticeDeletedEntity;

/**
 * 通知删除集合 Mapper（2026-10-27）。
 *
 * <p>与 {@link SocialNoticeReadMapper} 同一形态：条件构造器足够，无需注解 SQL；
 * 重复插入撞复合主键按「已删除」幂等。
 */
public interface SocialNoticeDeletedMapper extends BaseMapper<SocialNoticeDeletedEntity> {
}
