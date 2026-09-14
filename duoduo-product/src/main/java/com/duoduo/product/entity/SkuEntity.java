package com.duoduo.product.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * SKU（Stock Keeping Unit）—— 可售最小单元，价格与库存都落在这一层。
 *
 * <p>宠物商品特有字段：{@code isFood} 标记食品（食品才需要展示喂食建议与过敏原警示）、
 * {@code targetSpecies} / {@code targetLifeStage} 用于「按宠物档案筛选适配商品」、
 * {@code allergens} 用于与宠物档案的过敏原做命中高亮。
 *
 * <p>库存字段说明：{@code stock} 是可售库存，{@code frozenStock} 是下单未支付的冻结量。
 * 两者都由交易服务在扣减时维护，商品服务只读。
 */
@TableName("t_sku")
public class SkuEntity {

    @TableId
    private Long id;
    private Long spuId;
    private String skuCode;
    /** 规格 JSON，例如 {"规格":"2kg","口味":"鸡肉"} */
    private String specJson;
    private String image;
    private Long price;
    private Long marketPrice;
    private Integer stock;
    private Integer frozenStock;
    private Integer isFood;
    /** 逗号分隔：cat / dog / other */
    private String targetSpecies;
    /** 逗号分隔：BABY / ADULT / SENIOR */
    private String targetLifeStage;
    /** 逗号分隔：谷物 / 牛肉 / 鸡肉 ... */
    private String allergens;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSpuId() {
        return spuId;
    }

    public void setSpuId(Long spuId) {
        this.spuId = spuId;
    }

    public String getSkuCode() {
        return skuCode;
    }

    public void setSkuCode(String skuCode) {
        this.skuCode = skuCode;
    }

    public String getSpecJson() {
        return specJson;
    }

    public void setSpecJson(String specJson) {
        this.specJson = specJson;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public Long getPrice() {
        return price;
    }

    public void setPrice(Long price) {
        this.price = price;
    }

    public Long getMarketPrice() {
        return marketPrice;
    }

    public void setMarketPrice(Long marketPrice) {
        this.marketPrice = marketPrice;
    }

    public Integer getStock() {
        return stock;
    }

    public void setStock(Integer stock) {
        this.stock = stock;
    }

    public Integer getFrozenStock() {
        return frozenStock;
    }

    public void setFrozenStock(Integer frozenStock) {
        this.frozenStock = frozenStock;
    }

    public Integer getIsFood() {
        return isFood;
    }

    public void setIsFood(Integer isFood) {
        this.isFood = isFood;
    }

    public String getTargetSpecies() {
        return targetSpecies;
    }

    public void setTargetSpecies(String targetSpecies) {
        this.targetSpecies = targetSpecies;
    }

    public String getTargetLifeStage() {
        return targetLifeStage;
    }

    public void setTargetLifeStage(String targetLifeStage) {
        this.targetLifeStage = targetLifeStage;
    }

    public String getAllergens() {
        return allergens;
    }

    public void setAllergens(String allergens) {
        this.allergens = allergens;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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
