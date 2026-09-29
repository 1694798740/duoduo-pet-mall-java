package com.duoduo.fulfilment.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 商品分享（分享圈，第四十三轮；第四十六轮加转发层）。
 *
 * <p>一条记录 = 「某用户带着自己写的评价分享了一个商品」。商品字段是
 * 发布时点的**快照**（与评价的 {@code productTitle/productImage} 同一不变性原理）：
 * 商品后续改名换图，历史分享仍按分享那一刻的样子展示。
 *
 * <p><b>转发语义</b>（第四十六轮）：转发不是「另一种展示」，而是**又一次分享** ——
 * 转发行是新记录，{@code userId} 是转发人，{@code parentShareId} 指向被转的原帖，
 * {@code content} 是转发人自己的附言（可为空串）。因此：
 * <ul>
 *   <li>信息流里转发行天然出现在最前（发布时间最新），无需额外聚合；</li>
 *   <li>详情页按 {@code userId} 渲染，转发人自己的转发行自然显示「以我为作者」；</li>
 *   <li>商品快照<b>沿用原帖</b>（转发引用的是原帖内容，重新拉商品会让同一内容前后不一致）。</li>
 * </ul>
 *
 * <p>时间语义（全库统一 UTC）：{@code createdAt} —— 发布时间；{@code updatedAt} ——
 * 行更新时刻；{@code editedAt} —— **内容被编辑过**的时刻，NULL 表示从未编辑
 * （第五十六轮起提供编辑，前端据此显示「已编辑」；两个字段刻意分开 ——
 * updatedAt 会被点赞计数等非内容变更刷新，拿它当「已编辑」判据会误报）。
 */
@TableName("t_product_share")
public class ShareEntity {

    @TableId
    private Long id;

    private Long userId;

    /** 被转发的原分享单 ID；NULL = 原创（唯一键 (user_id, parent_share_id) 靠此识别原创） */
    private Long parentShareId;

    /** 分享对象：SPU 维度（分享是口碑行为，不细分到规格） */
    private Long spuId;
    /** 商品快照：发布时点的标题 / 主图 / 最低价 */
    private String productTitle;
    private String productImage;
    private Long minPrice;
    /** 用户自撰评价内容（原创发布入口强制非空，服务层兜底校验；转发时是附言，可为空串） */
    private String content;
    /** 互动计数（冗余列，事实源是 t_share_like / t_share_favorite；第四十四轮） */
    private Integer likeCount;
    private Integer favoriteCount;
    /** 评论数（冗余列，事实源 t_share_comment；第五十六轮） */
    private Integer commentCount;
    /**
     * 软删时间；非 NULL = 已删除（第五十六轮）。
     *
     * <p><b>为什么必须软删</b>：转发单的「原帖引用」是查询时按 {@code parentShareId}
     * 回查原帖行得到的 —— 物理删掉原帖，转发单的引用块会变成空内容（用户视角像页面坏了）。
     * 软删同时保住「这条内容曾经存在」这个事实（举报留痕与计数需要它）。
     */
    private LocalDateTime deletedAt;
    /** 内容编辑时间；NULL = 从未编辑（前端据此显示「已编辑」；第五十六轮） */
    private LocalDateTime editedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getParentShareId() {
        return parentShareId;
    }

    public void setParentShareId(Long parentShareId) {
        this.parentShareId = parentShareId;
    }

    public Long getSpuId() {
        return spuId;
    }

    public void setSpuId(Long spuId) {
        this.spuId = spuId;
    }

    public String getProductTitle() {
        return productTitle;
    }

    public void setProductTitle(String productTitle) {
        this.productTitle = productTitle;
    }

    public String getProductImage() {
        return productImage;
    }

    public void setProductImage(String productImage) {
        this.productImage = productImage;
    }

    public Long getMinPrice() {
        return minPrice;
    }

    public void setMinPrice(Long minPrice) {
        this.minPrice = minPrice;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getLikeCount() {
        return likeCount;
    }

    public void setLikeCount(Integer likeCount) {
        this.likeCount = likeCount;
    }

    public Integer getFavoriteCount() {
        return favoriteCount;
    }

    public void setFavoriteCount(Integer favoriteCount) {
        this.favoriteCount = favoriteCount;
    }

    public Integer getCommentCount() {
        return commentCount;
    }

    public void setCommentCount(Integer commentCount) {
        this.commentCount = commentCount;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public LocalDateTime getEditedAt() {
        return editedAt;
    }

    public void setEditedAt(LocalDateTime editedAt) {
        this.editedAt = editedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
