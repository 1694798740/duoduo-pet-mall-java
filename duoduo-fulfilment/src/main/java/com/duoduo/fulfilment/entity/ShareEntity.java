package com.duoduo.fulfilment.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 商品分享（分享圈，第四十三轮）。
 *
 * <p>一条记录 = 「某用户带着自己写的评价分享了一个商品」。商品字段是
 * 发布时点的**快照**（与评价的 {@code productTitle/productImage} 同一不变性原理）：
 * 商品后续改名换图，历史分享仍按分享那一刻的样子展示。
 *
 * <p>时间语义（全库统一 UTC）：{@code createdAt} —— 发布时间；{@code updatedAt} ——
 * 预留（分享圈暂不提供编辑，字段保留与其它表一致，避免日后加列迁移）。
 */
@TableName("t_product_share")
public class ShareEntity {

    @TableId
    private Long id;

    private Long userId;
    /** 分享对象：SPU 维度（分享是口碑行为，不细分到规格） */
    private Long spuId;
    /** 商品快照：发布时点的标题 / 主图 / 最低价 */
    private String productTitle;
    private String productImage;
    private Long minPrice;
    /** 用户自撰评价内容（发布入口强制非空，服务层兜底校验） */
    private String content;
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
