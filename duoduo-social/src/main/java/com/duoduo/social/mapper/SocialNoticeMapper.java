package com.duoduo.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.social.entity.SocialNoticeEntity;

/**
 * 通知 Mapper（第五十轮启用）。
 *
 * <p>全部走 MyBatis-Plus 条件构造器（按 (user_id, category, created_at) 索引）；
 * 已读不在本表记录（表上没有已读列），统一在 t_social_notice_read 集合表。
 */
public interface SocialNoticeMapper extends BaseMapper<SocialNoticeEntity> {
}
