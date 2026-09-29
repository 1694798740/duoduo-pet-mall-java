package com.duoduo.fulfilment.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 分享 ↔ 话题关系 Mapper（第五十六轮）。
 *
 * <p>复合主键 {@code (share_id, topic_id)} 即唯一约束，没有单键语义 ——
 * 与 {@code ShareEngagementMapper} 同样直接写 SQL，不为它造 Entity + BaseMapper。
 */
@Mapper
public interface ShareTopicRelMapper {

    /** 幂等挂话题：重复挂同一（分享, 话题）返回 0，由调用方据此决定是否给计数 +1 */
    @Insert("INSERT IGNORE INTO t_share_topic_rel (share_id, topic_id) VALUES (#{shareId}, #{topicId})")
    int insertIgnore(@Param("shareId") Long shareId, @Param("topicId") Long topicId);

    /** 编辑话题时先清空再挂（话题只有 2 个，差值计算不如整体替换直白） */
    @Delete("DELETE FROM t_share_topic_rel WHERE share_id = #{shareId}")
    int deleteByShare(@Param("shareId") Long shareId);

    @Select("SELECT topic_id FROM t_share_topic_rel WHERE share_id = #{shareId}")
    List<Long> topicIdsOf(@Param("shareId") Long shareId);

    /** 信息流批量装配：一页一次 IN 查询，避免逐条问（N+1） */
    @Select("SELECT share_id, topic_id FROM t_share_topic_rel WHERE share_id IN (${shareIds})")
    List<java.util.Map<String, Object>> relsOf(@Param("shareIds") String shareIds);
}
