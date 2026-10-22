package com.duoduo.trade.mapper;

/**
 * 「已购买商品」查询行（第七十九轮 C 批）。
 *
 * <p>按 spu 去重：同一商品买过多次只出现一条，取最后一次下单时间排序。
 */
public class PurchasedProductRow {

    private Long spuId;
    private String title;
    private String mainImage;
    private Long price;
    private java.time.LocalDateTime lastAt;

    public Long getSpuId() {
        return spuId;
    }

    public void setSpuId(Long spuId) {
        this.spuId = spuId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getMainImage() {
        return mainImage;
    }

    public void setMainImage(String mainImage) {
        this.mainImage = mainImage;
    }

    public Long getPrice() {
        return price;
    }

    public void setPrice(Long price) {
        this.price = price;
    }

    public java.time.LocalDateTime getLastAt() {
        return lastAt;
    }

    public void setLastAt(java.time.LocalDateTime lastAt) {
        this.lastAt = lastAt;
    }
}
