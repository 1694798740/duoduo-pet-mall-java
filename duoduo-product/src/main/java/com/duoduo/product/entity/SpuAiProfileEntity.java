package com.duoduo.product.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 商品 AI 属性（2026-10-28 · RAG 基础设施）。
 *
 * <p>这张表只服务检索与推荐，不是商品事实源：价格 / 库存 / 上下架状态仍以
 * {@code t_spu} / {@code t_sku} 为准，AI 生成前必须回源。
 */
@TableName("t_spu_ai_profile")
public class SpuAiProfileEntity {

    @TableId
    private Long spuId;
    /** cat/dog/small_pet/bird/fish/reptile/other */
    private String species;
    /** BABY,ADULT,SENIOR 逗号分隔 */
    private String lifeStages;
    /** SMALL,MEDIUM,LARGE 逗号分隔 */
    private String breedSizes;
    /** JSON 数组字符串 */
    private String allergens;
    /** JSON 数组字符串 */
    private String functionTags;
    private String packageSize;
    private Integer packageWeightG;
    private String contentSummary;
    private String searchText;
    private Long sourceVersion;
    private LocalDateTime updatedAt;

    public Long getSpuId() {
        return spuId;
    }

    public void setSpuId(Long spuId) {
        this.spuId = spuId;
    }

    public String getSpecies() {
        return species;
    }

    public void setSpecies(String species) {
        this.species = species;
    }

    public String getLifeStages() {
        return lifeStages;
    }

    public void setLifeStages(String lifeStages) {
        this.lifeStages = lifeStages;
    }

    public String getBreedSizes() {
        return breedSizes;
    }

    public void setBreedSizes(String breedSizes) {
        this.breedSizes = breedSizes;
    }

    public String getAllergens() {
        return allergens;
    }

    public void setAllergens(String allergens) {
        this.allergens = allergens;
    }

    public String getFunctionTags() {
        return functionTags;
    }

    public void setFunctionTags(String functionTags) {
        this.functionTags = functionTags;
    }

    public String getPackageSize() {
        return packageSize;
    }

    public void setPackageSize(String packageSize) {
        this.packageSize = packageSize;
    }

    public Integer getPackageWeightG() {
        return packageWeightG;
    }

    public void setPackageWeightG(Integer packageWeightG) {
        this.packageWeightG = packageWeightG;
    }

    public String getContentSummary() {
        return contentSummary;
    }

    public void setContentSummary(String contentSummary) {
        this.contentSummary = contentSummary;
    }

    public String getSearchText() {
        return searchText;
    }

    public void setSearchText(String searchText) {
        this.searchText = searchText;
    }

    public Long getSourceVersion() {
        return sourceVersion;
    }

    public void setSourceVersion(Long sourceVersion) {
        this.sourceVersion = sourceVersion;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
