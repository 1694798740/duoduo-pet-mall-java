package com.duoduo.fulfilment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.fulfilment.entity.ShareCommentEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 分享评论 Mapper（第五十六轮）。
 *
 * <p>分两部分：
 * <ul>
 *   <li><b>评论本体</b>（列表 / 回复数 / 下架）—— 列表走条件构造器，聚合与批量走手写 SQL；</li>
 *   <li><b>评论点赞</b>（{@code t_share_comment_like}）—— 「在不在」「加 / 去」两类操作，
 *       与分享点赞同构（复合主键即唯一约束，{@code INSERT IGNORE} 撞键幂等）。</li>
 * </ul>
 *
 * <p>聚合结果用 {@code Map} 接（与 {@code ReviewMapper.aggregateBySpu} 同一写法）：
 * 一行两列的小结果不值得为它造一个只在此处出现的类型。
 */
@Mapper
public interface ShareCommentMapper extends BaseMapper<ShareCommentEntity> {

    /**
     * 批量取「每个一级评论下的回复条数」，返回形如 {@code [{parent_comment_id, cnt}, ...]}。
     *
     * <p>批量而非逐条：一页 10 条一级评论逐条 COUNT 就是 10 次往返，
     * 评论区滚动加载时这个差距会被放大成可见的卡顿。
     */
    @Select("SELECT parent_comment_id, COUNT(*) AS cnt FROM t_share_comment "
            + "WHERE parent_comment_id IS NOT NULL AND deleted_at IS NULL "
            + "AND parent_comment_id IN (${commentIds}) GROUP BY parent_comment_id")
    List<Map<String, Object>> countReplies(@Param("commentIds") String commentIds);

    // ---------------- 评论点赞 ----------------

    @Select("SELECT COUNT(*) FROM t_share_comment_like WHERE comment_id = #{commentId} AND user_id = #{userId}")
    long likeExists(@Param("commentId") Long commentId, @Param("userId") Long userId);

    @Insert("INSERT IGNORE INTO t_share_comment_like (comment_id, user_id, created_at) "
            + "VALUES (#{commentId}, #{userId}, UTC_TIMESTAMP())")
    int insertLike(@Param("commentId") Long commentId, @Param("userId") Long userId);

    @Delete("DELETE FROM t_share_comment_like WHERE comment_id = #{commentId} AND user_id = #{userId}")
    int deleteLike(@Param("commentId") Long commentId, @Param("userId") Long userId);

    /** 当前浏览者赞过哪些评论（一页一次 IN 查询，避免逐条问） */
    @Select("SELECT comment_id FROM t_share_comment_like WHERE user_id = #{userId} "
            + "AND comment_id IN (${commentIds})")
    List<Long> likedCommentIds(@Param("userId") Long userId, @Param("commentIds") String commentIds);

    /** 点赞计数回写；GREATEST 防负漂移（事实行已删但计数列万一没对上） */
    @Update("UPDATE t_share_comment SET like_count = GREATEST(0, like_count + #{delta}) WHERE id = #{commentId}")
    int bumpLikeCount(@Param("commentId") Long commentId, @Param("delta") int delta);

    // ---------------- 运营：下架评论（软删，不物理删，留痕且保住回复链） ----------------

    @Update("UPDATE t_share_comment SET deleted_at = UTC_TIMESTAMP() WHERE id = #{commentId} AND deleted_at IS NULL")
    int softDelete(@Param("commentId") Long commentId);

    /** 统计某条一级评论下仍在的回复数（下架评论时同步回退分享的评论计数用） */
    @Select("SELECT COUNT(*) FROM t_share_comment WHERE parent_comment_id = #{parentCommentId} AND deleted_at IS NULL")
    long countAliveReplies(@Param("parentCommentId") Long parentCommentId);
}
