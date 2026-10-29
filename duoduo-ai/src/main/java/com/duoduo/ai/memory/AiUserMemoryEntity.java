package com.duoduo.ai.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("t_ai_user_memory")
public class AiUserMemoryEntity {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long userId;
    private String preferredSpecies;
    private String preferredCategories;
    private String preferredBrands;
    private Long budgetMinCents;
    private Long budgetMaxCents;
    private Integer purchaseCycleDays;
    private LocalDateTime lastPurchaseAt;
    private String consentVersion;

    /**
     * 结构化记忆快照（JSON）：高风险记忆（过敏原、健康备注）等不适合拆成列的内容。
     *
     * <p>为什么高风险项单独放 JSON 而不加列：过敏原/健康备注是**用户可撤销**的派生数据，
     * 形状还会随运营调整；每加一种就改一次表，迁移成本高于收益。
     * 查询侧只需要「整块读出来」，不需要按字段检索。
     */
    private String memoryJson;

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

    public String getPreferredSpecies() {
        return preferredSpecies;
    }

    public void setPreferredSpecies(String preferredSpecies) {
        this.preferredSpecies = preferredSpecies;
    }

    public String getPreferredCategories() {
        return preferredCategories;
    }

    public void setPreferredCategories(String preferredCategories) {
        this.preferredCategories = preferredCategories;
    }

    public String getPreferredBrands() {
        return preferredBrands;
    }

    public void setPreferredBrands(String preferredBrands) {
        this.preferredBrands = preferredBrands;
    }

    public Long getBudgetMinCents() {
        return budgetMinCents;
    }

    public void setBudgetMinCents(Long budgetMinCents) {
        this.budgetMinCents = budgetMinCents;
    }

    public Long getBudgetMaxCents() {
        return budgetMaxCents;
    }

    public void setBudgetMaxCents(Long budgetMaxCents) {
        this.budgetMaxCents = budgetMaxCents;
    }

    public Integer getPurchaseCycleDays() {
        return purchaseCycleDays;
    }

    public void setPurchaseCycleDays(Integer purchaseCycleDays) {
        this.purchaseCycleDays = purchaseCycleDays;
    }

    public LocalDateTime getLastPurchaseAt() {
        return lastPurchaseAt;
    }

    public void setLastPurchaseAt(LocalDateTime lastPurchaseAt) {
        this.lastPurchaseAt = lastPurchaseAt;
    }

    public String getConsentVersion() {
        return consentVersion;
    }

    public void setConsentVersion(String consentVersion) {
        this.consentVersion = consentVersion;
    }

    public String getMemoryJson() {
        return memoryJson;
    }

    public void setMemoryJson(String memoryJson) {
        this.memoryJson = memoryJson;
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
