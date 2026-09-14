package com.duoduo.user.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 宠物档案 —— 推荐与 AI 导购的核心上下文（PET-01 ~ PET-06）。
 *
 * <p>设计说明：一个账号可有多只宠物，其中一只为「当前宠物」（{@code isDefault}）。
 * 过敏原以逗号分隔字符串存储，避免为这个低频字段单独建表；
 * 检索与过滤在服务层完成（档案总量小，且只按 user_id 维度读取）。
 */
@TableName("t_pet_profile")
public class PetProfileEntity {

    @TableId
    private Long id;
    private Long userId;
    private String name;
    /** cat / dog / other */
    private String species;
    private String breed;
    private LocalDate birthday;
    /** BABY / ADULT / SENIOR */
    private String lifeStage;
    private BigDecimal weightKg;
    /** MALE / FEMALE / UNKNOWN */
    private String gender;
    private Integer neutered;
    /** 逗号分隔，例如：谷物,牛肉 */
    private String allergens;
    private String medicalHistory;
    private Integer isDefault;
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSpecies() {
        return species;
    }

    public void setSpecies(String species) {
        this.species = species;
    }

    public String getBreed() {
        return breed;
    }

    public void setBreed(String breed) {
        this.breed = breed;
    }

    public LocalDate getBirthday() {
        return birthday;
    }

    public void setBirthday(LocalDate birthday) {
        this.birthday = birthday;
    }

    public String getLifeStage() {
        return lifeStage;
    }

    public void setLifeStage(String lifeStage) {
        this.lifeStage = lifeStage;
    }

    public BigDecimal getWeightKg() {
        return weightKg;
    }

    public void setWeightKg(BigDecimal weightKg) {
        this.weightKg = weightKg;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public Integer getNeutered() {
        return neutered;
    }

    public void setNeutered(Integer neutered) {
        this.neutered = neutered;
    }

    public String getAllergens() {
        return allergens;
    }

    public void setAllergens(String allergens) {
        this.allergens = allergens;
    }

    public String getMedicalHistory() {
        return medicalHistory;
    }

    public void setMedicalHistory(String medicalHistory) {
        this.medicalHistory = medicalHistory;
    }

    public Integer getIsDefault() {
        return isDefault;
    }

    public void setIsDefault(Integer isDefault) {
        this.isDefault = isDefault;
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
