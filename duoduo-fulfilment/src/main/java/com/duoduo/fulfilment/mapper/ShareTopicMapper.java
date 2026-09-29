package com.duoduo.fulfilment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.fulfilment.entity.ShareTopicEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 话题标签 Mapper（第五十六轮）。
 *
 * <p>「查不到就建」走「直接 INSERT，撞唯一键再回查」——不要先 SELECT 再 INSERT：
 * 两个用户同时首发同一个话题时，先查后插会让其中一个拿到 500（撞键异常）。
 */
@Mapper
public interface ShareTopicMapper extends BaseMapper<ShareTopicEntity> {

    /** 话题使用次数回写；GREATEST 防负漂移（分享被删时回退，事实行已删但计数没对上也不会变负） */
    @Update("UPDATE t_share_topic SET share_count = GREATEST(0, share_count + #{delta}) WHERE id = #{topicId}")
    int bumpShareCount(@Param("topicId") Long topicId, @Param("delta") int delta);
}
