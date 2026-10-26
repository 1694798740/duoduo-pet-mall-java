package com.duoduo.user.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 商家成员（E9 建表；E13 子账号体系在此之上做邀请 / 停用）。
 *
 * <p>一个用户至多属于一个商家（{@code uk_user} 唯一键先把口子收住，
 * 跨店兼职是将来有真实需求再放宽的事）。店主在入驻通过时写入（role=OWNER）。
 */
@TableName("t_merchant_member")
public class MerchantMemberEntity {

    @TableId
    private Long id;
    private Long merchantId;
    private Long userId;
    /** OWNER（店主）/ STAFF（店员，E13） */
    private String role;
    /** ACTIVE / DISABLED（E13 停用） */
    private String status;
    /**
     * F-04 · 操作权限清单（逗号分隔，见 {@code StaffPermissions}）。
     *
     * <p>{@code NULL} = F-04 之前的历史行，按全量解释；空串 = 显式仅查看。
     * 店主恒为全量，不读本列。
     */
    private String permissions;
    /** 邀请人（店主 user id；店主自建为 NULL） */
    private Long invitedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(Long merchantId) {
        this.merchantId = merchantId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPermissions() {
        return permissions;
    }

    public void setPermissions(String permissions) {
        this.permissions = permissions;
    }

    public Long getInvitedBy() {
        return invitedBy;
    }

    public void setInvitedBy(Long invitedBy) {
        this.invitedBy = invitedBy;
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
