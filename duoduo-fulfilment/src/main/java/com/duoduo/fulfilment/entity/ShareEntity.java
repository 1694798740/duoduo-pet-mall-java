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
 * 预留（分享圈暂不提供编辑，字段保留与其它表一致，避免日后加列迁移）。
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
