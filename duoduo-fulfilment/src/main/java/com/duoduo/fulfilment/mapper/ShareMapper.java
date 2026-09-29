package com.duoduo.fulfilment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.fulfilment.entity.ShareEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ShareMapper extends BaseMapper<ShareEntity> {

    /**
     * 话题聚合页：按话题查分享（第五十六轮）。
     *
     * <p>为什么用 JOIN 而不是「先查关系表拿 id 再 {@code selectBatchIds}」：
     * 后者在「关系表命中 N 条、其中一部分分享已被删除」时，过滤后这一页会**少几条**，
     * 分页页数越翻越诡异（最后一页特别短甚至为空）。JOIN 把删除过滤放进同一条 SQL，
     * 分页与总数才自洽。
     *
     * <p>排序用 {@code s.id DESC}（雪花 id 随时间单调递增）而不是 {@code created_at}：
     * 同一毫秒内发布的分享靠 id 仍能稳定排序，created_at 相等时顺序会抖。
     */
    @Select("SELECT s.* FROM t_product_share s "
            + "JOIN t_share_topic_rel r ON r.share_id = s.id "
            + "WHERE r.topic_id = #{topicId} AND s.deleted_at IS NULL "
            + "ORDER BY s.id DESC LIMIT #{offset}, #{size}")
    List<ShareEntity> selectPageByTopic(@Param("topicId") Long topicId,
                                        @Param("offset") int offset,
                                        @Param("size") int size);

    /** 话题聚合页总数（与上面的 JOIN 条件必须逐字一致，否则分页会多出空页） */
    @Select("SELECT COUNT(*) FROM t_product_share s "
            + "JOIN t_share_topic_rel r ON r.share_id = s.id "
            + "WHERE r.topic_id = #{topicId} AND s.deleted_at IS NULL")
    long countByTopic(@Param("topicId") Long topicId);
}
