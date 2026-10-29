package com.duoduo.marketing.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 券模板（运营创建，买家领取的依据）。
 *
 * <p>与「用户手上那张券」（{@link CouponEntity}）分开的两点理由：
 * <ul>
 *   <li><b>生命周期不同</b>：模板是运营长期维护的主数据，改文案、调库存是日常操作；
 *       券实例是用户资产，一旦发出就不该被运营的编辑动作影响。</li>
 *   <li><b>唯一索引无从定义</b>：合表之后「同一用户同一模板只能领一张」这条幂等约束
 *       只能靠应用层「先查再插」，并发下必然漏（INV-1）。</li>
 * </ul>
 *
 * <p>时间语义（全库统一 UTC）：{@code startAt} / {@code endAt} 是<b>领取窗口</b>，
 * 不是券的使用有效期 —— 使用有效期在领券时快照进 {@link CouponEntity#expireAt}，
 * 这样运营之后改模板窗口也不会动到用户已经领到手的券。
 */
@TableName("t_coupon_template")
public class CouponTemplateEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    /** 券类型：满减（固定抵扣额） */
    public static final String TYPE_FULL_REDUCE = "FULL_REDUCE";

    /** 券类型：折扣（按折后比例减，可封顶） */
    public static final String TYPE_DISCOUNT = "DISCOUNT";

    /** 折扣券允许的折后比例区间（百分比）：1 ~ 99，即 0.1 折 ~ 9.9 折 */
    public static final int MIN_DISCOUNT_PERCENT = 1;
    public static final int MAX_DISCOUNT_PERCENT = 99;

    @TableId
    private Long id;

    private String name;

    private String description;

    private String type;

    /** 满减门槛（分）：订单商品总额 &gt;= 门槛才能用 */
    private Long thresholdAmount;

    /** 抵扣金额（分）：满减券的固定抵扣额；折扣券恒为 0 */
    private Long discountAmount;

    /**
     * 折扣券「折后应付比例」（百分比）：85 表示 8.5 折（付 85%、减 15%）。
     *
     * <p>刻意用整数百分比而不是浮点折率：全库金额口径是整数分，浮点会引入
     * 「0.85 × 10000 = 8499.999…」这类误差，最终表现为少减或多减 1 分。
     * 满减券此字段恒为 100（不参与计算）。
     */
    private Integer discountPercent;

    /**
     * 折扣券封顶抵扣（分）：0 = 不封顶。
     *
     * <p>折扣券必须有封顶能力 —— 一张 8 折券落在上万元订单上会一次减掉几千元，
     * 运营在没有任何上限参数时根本不敢发折扣券。
     */
    private Long maxDiscountAmount;

    /** 发放总量 */
    private Integer totalCount;

    /** 已领数量（靠 CAS 自增，不靠查后写） */
    private Integer claimedCount;

    private LocalDateTime startAt;

    private LocalDateTime endAt;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Long getThresholdAmount() {
        return thresholdAmount;
    }

    public void setThresholdAmount(Long thresholdAmount) {
        this.thresholdAmount = thresholdAmount;
    }

    public Long getDiscountAmount() {
        return discountAmount;
    }

    public void setDiscountAmount(Long discountAmount) {
        this.discountAmount = discountAmount;
    }

    public Integer getDiscountPercent() {
        return discountPercent;
    }

    public void setDiscountPercent(Integer discountPercent) {
        this.discountPercent = discountPercent;
    }

    public Long getMaxDiscountAmount() {
        return maxDiscountAmount;
    }

    public void setMaxDiscountAmount(Long maxDiscountAmount) {
        this.maxDiscountAmount = maxDiscountAmount;
    }

    public Integer getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(Integer totalCount) {
        this.totalCount = totalCount;
    }

    public Integer getClaimedCount() {
        return claimedCount;
    }

    public void setClaimedCount(Integer claimedCount) {
        this.claimedCount = claimedCount;
    }

    public LocalDateTime getStartAt() {
        return startAt;
    }

    public void setStartAt(LocalDateTime startAt) {
        this.startAt = startAt;
    }

    public LocalDateTime getEndAt() {
        return endAt;
    }

    public void setEndAt(LocalDateTime endAt) {
        this.endAt = endAt;
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
