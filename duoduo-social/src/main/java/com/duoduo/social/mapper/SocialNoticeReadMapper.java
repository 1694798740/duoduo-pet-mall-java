package com.duoduo.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.social.entity.SocialNoticeReadEntity;

/**
 * 公告已读集合 Mapper（第五十轮启用）。
 *
 * <p>只有主键级 CRUD，条件构造器即可覆盖，无需注解 SQL；
 * 重复插入撞复合主键按「已读」幂等。
 */
public interface SocialNoticeReadMapper extends BaseMapper<SocialNoticeReadEntity> {
}
