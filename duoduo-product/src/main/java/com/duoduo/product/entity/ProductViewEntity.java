package com.duoduo.product.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 商品浏览足迹（第七十九轮 C 批）。
 *
 * <p>(user_id, spu_id) 唯一：同一商品只保留**最近一次**浏览，否则足迹列表
 * 会被同一个商品刷屏（用户点开三次就有三条），且分页失去意义。
 */
@TableName("t_product_view")
public class ProductViewEntity {

    @TableId
    private Long id;
    private Long userId;
    private Long spuId;
    private LocalDateTime viewedAt;

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

    public LocalDateTime getViewedAt() {
        return viewedAt;
    }

    public void setViewedAt(LocalDateTime viewedAt) {
        this.viewedAt = viewedAt;
    }
}
