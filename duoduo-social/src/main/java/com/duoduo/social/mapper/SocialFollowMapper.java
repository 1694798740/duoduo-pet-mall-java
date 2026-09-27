package com.duoduo.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.social.entity.SocialFollowEntity;

/**
 * 关注关系 Mapper（第四十七轮）。
 *
 * <p>全部操作走条件构造器（复合主键没有单键语义）：
 * 计数用 {@code selectCount}，列表用 {@code selectList/selectPage}，取关用 {@code delete}。
 */
public interface SocialFollowMapper extends BaseMapper<SocialFollowEntity> {
}
