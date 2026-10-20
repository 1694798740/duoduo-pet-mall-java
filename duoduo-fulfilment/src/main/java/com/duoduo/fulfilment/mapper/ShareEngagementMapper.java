package com.duoduo.fulfilment.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 分享圈互动（点赞 / 收藏，第四十四轮）。
 *
 * <p>两张表同构（share_id, user_id 复合主键），操作只有「在不在」和「加 / 去」，
 * 不值得各建 Entity + BaseMapper —— 直接 SQL 写在这，事实一目了然。
 * 计数冗余在 t_product_share（信息流高频读不逐行 COUNT），回退扣减用
 * {@code GREATEST(0, n-1)} 防负漂移。
 */
@Mapper
public interface ShareEngagementMapper {

    String TABLES = "t_share_like";

    // ---------------- 点赞 ----------------

    @Select("SELECT COUNT(*) FROM t_share_like WHERE share_id = #{shareId} AND user_id = #{userId}")
    long likeExists(@Param("shareId") Long shareId, @Param("userId") Long userId);

    @Insert("INSERT IGNORE INTO t_share_like (share_id, user_id, created_at) "
            + "VALUES (#{shareId}, #{userId}, UTC_TIMESTAMP())")
    int insertLike(@Param("shareId") Long shareId, @Param("userId") Long userId);

    @Delete("DELETE FROM t_share_like WHERE share_id = #{shareId} AND user_id = #{userId}")
    int deleteLike(@Param("shareId") Long shareId, @Param("userId") Long userId);

    @Select("SELECT share_id FROM t_share_like WHERE user_id = #{userId} "
            + "AND share_id IN (${shareIds})")
    List<Long> likedShareIds(@Param("userId") Long userId, @Param("shareIds") String shareIds);

    // ---------------- 收藏 ----------------

    @Select("SELECT COUNT(*) FROM t_share_favorite WHERE share_id = #{shareId} AND user_id = #{userId}")
    long favoriteExists(@Param("shareId") Long shareId, @Param("userId") Long userId);

    @Insert("INSERT IGNORE INTO t_share_favorite (share_id, user_id, created_at) "
            + "VALUES (#{shareId}, #{userId}, UTC_TIMESTAMP())")
    int insertFavorite(@Param("shareId") Long shareId, @Param("userId") Long userId);

    @Delete("DELETE FROM t_share_favorite WHERE share_id = #{shareId} AND user_id = #{userId}")
    int deleteFavorite(@Param("shareId") Long shareId, @Param("userId") Long userId);

    @Select("SELECT share_id FROM t_share_favorite WHERE user_id = #{userId} "
            + "AND share_id IN (${shareIds})")
    List<Long> favoritedShareIds(@Param("userId") Long userId, @Param("shareIds") String shareIds);

    // ---------------- C7 · 账号注销：按用户整体清理（删/查，见 PersonalDataCleanupService） ----------------
    //
    // 为什么点赞/收藏也按 user_id 整表删：注销清的是「这个人留下的一切互动痕迹」，
    // 逐条 (share_id, user_id) 删要先查再删，纯属多绕一圈 —— 这四张表
    // （含评论点赞）在账号注销场景下没有「只删一部分」的语义。

    /** 该用户点过的赞总数（注销预检 / 执行回报） */
    @Select("SELECT COUNT(*) FROM t_share_like WHERE user_id = #{userId}")
    long countLikesByUser(@Param("userId") Long userId);

    /** 删该用户的全部点赞；幂等，重复执行命中 0 行 */
    @Delete("DELETE FROM t_share_like WHERE user_id = #{userId}")
    int deleteLikesByUser(@Param("userId") Long userId);

    /** 该用户的收藏总数（注销预检 / 执行回报） */
    @Select("SELECT COUNT(*) FROM t_share_favorite WHERE user_id = #{userId}")
    long countFavoritesByUser(@Param("userId") Long userId);

    /** 删该用户的全部收藏；幂等，重复执行命中 0 行 */
    @Delete("DELETE FROM t_share_favorite WHERE user_id = #{userId}")
    int deleteFavoritesByUser(@Param("userId") Long userId);

    // ---------------- 计数回写（GREATEST 防负漂移：事实行已删但计数列万一没对上） ----------------

    @Update("UPDATE t_product_share SET like_count = GREATEST(0, like_count + #{delta}) WHERE id = #{shareId}")
    int bumpLikeCount(@Param("shareId") Long shareId, @Param("delta") int delta);

    @Update("UPDATE t_product_share SET favorite_count = GREATEST(0, favorite_count + #{delta}) WHERE id = #{shareId}")
    int bumpFavoriteCount(@Param("shareId") Long shareId, @Param("delta") int delta);

    /**
     * 评论数回写（第五十六轮）。
     *
     * <p>口径：**一级评论与回复都算**（与主流 App 卡片上那个数字一致 ——
     * 用户看到「12 条评论」时想的是评论区里有多少条，而不是有多少个话题串）。
     * 因此发表评论 +1、运营下架评论时 -(1 + 其下未删回复数)。
     */
    @Update("UPDATE t_product_share SET comment_count = GREATEST(0, comment_count + #{delta}) WHERE id = #{shareId}")
    int bumpCommentCount(@Param("shareId") Long shareId, @Param("delta") int delta);
}
