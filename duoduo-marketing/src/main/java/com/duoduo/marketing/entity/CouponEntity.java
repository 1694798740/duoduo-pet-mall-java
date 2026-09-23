package com.duoduo.marketing.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 券实例（用户手上那一张券）。
 *
 * <p>状态机见 {@link com.duoduo.marketing.support.CouponStatus}。
 *
 * <p><b>为什么用随机券码 {@code code} 而不是主键出到前端</b>：主键是雪花 BIGINT，
 * 18 位超过 JS 的 {@code Number.MAX_SAFE_INTEGER}，{@code JSON.parse} 会静默改写成另一个数，
 * 前端拿它拼 URL 必然打到不存在的券（INV-16 / 售后单踩过同一个坑）。券码天然规避。
 *
 * <p>{@code expireAt} 是领券时从模板 {@code endAt} 快照下来的：
 * 运营之后把模板窗口改短，不该让已经躺在用户卡包里的券凭空失效。
 */
@TableName("t_coupon")
public class CouponEntity {

    @TableId
    private Long id;

    /** 券码（对外唯一标识，随机串） */
    private String code;

    private Long templateId;

    private Long userId;

    /** 状态，取值见 {@link com.duoduo.marketing.support.CouponStatus} */
    private String status;

    private LocalDateTime claimedAt;

    private LocalDateTime expireAt;

    /** 被哪个订单锁定（未支付）；解锁后清空 */
    private String lockedOrderNo;

    private LocalDateTime lockedAt;

    /** 被哪个订单用掉；核销后不再变 */
    private String usedOrderNo;

    private LocalDateTime usedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public Long getTemplateId() {
        return templateId;
    }

    public void setTemplateId(Long templateId) {
        this.templateId = templateId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(LocalDateTime claimedAt) {
        this.claimedAt = claimedAt;
    }

    public LocalDateTime getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(LocalDateTime expireAt) {
        this.expireAt = expireAt;
    }

    public String getLockedOrderNo() {
        return lockedOrderNo;
    }

    public void setLockedOrderNo(String lockedOrderNo) {
        this.lockedOrderNo = lockedOrderNo;
    }

    public LocalDateTime getLockedAt() {
        return lockedAt;
    }

    public void setLockedAt(LocalDateTime lockedAt) {
        this.lockedAt = lockedAt;
    }

    public String getUsedOrderNo() {
        return usedOrderNo;
    }

    public void setUsedOrderNo(String usedOrderNo) {
        this.usedOrderNo = usedOrderNo;
    }

    public LocalDateTime getUsedAt() {
        return usedAt;
    }

    public void setUsedAt(LocalDateTime usedAt) {
        this.usedAt = usedAt;
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
