package com.duoduo.user.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 用户主体。手机号全平台唯一（ACC-01）。
 *
 * <p>安全：口令只存 BCrypt 哈希；身份证号只保留掩码（前 4 + 后 4），
 * 完整号码不落库、不写日志。
 */
@TableName("t_user")
public class UserEntity {

    @TableId
    private Long id;
    private String phone;
    private String passwordHash;
    private String nickname;
    private String avatar;
    /** ACTIVE / BANNED（ACC-05：封禁账号仅保留浏览与问答能力） */
    private String status;
    /**
     * 系统角色。取值 {@code USER / SELLER / OPERATOR / ADMIN} ——
     * 唯一口径见 {@code RolePermissionMatrix.KNOWN_ROLES}（E16 起不再各处自列一份；
     * 本注释此前只写 {@code USER/OPERATOR/ADMIN}，漏了实际在用的 SELLER）。
     */
    private String role;
    private String realName;
    private String idCardMasked;
    /** 微信 openid（A13）：绑定后写入；唯一索引兜底「同一微信并发绑到两个账号」。未绑定为 null */
    private String wxOpenid;
    private Integer creditScore;
    /**
     * E17 · 权限版本（权威值）。
     *
     * <p>任何权限相关变更（角色调整）都让它 +1；访问令牌里带着签发时的值，
     * 网关比对不一致即拒绝 —— 于是「刚被撤销的权限」下一个请求就失效，
     * 不必等令牌自然过期，也不必强制对方重新登录。
     *
     * <p>DDL 上是 {@code NOT NULL DEFAULT 0}，但老库可能为 NULL，读取一律走
     * {@code PermVersion.orInitial(...)}，不在这里做"看起来非空"的假设。
     */
    private Long permVersion;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getRealName() {
        return realName;
    }

    public void setRealName(String realName) {
        this.realName = realName;
    }

    public String getIdCardMasked() {
        return idCardMasked;
    }

    public void setIdCardMasked(String idCardMasked) {
        this.idCardMasked = idCardMasked;
    }

    public String getWxOpenid() {
        return wxOpenid;
    }

    public void setWxOpenid(String wxOpenid) {
        this.wxOpenid = wxOpenid;
    }

    public Integer getCreditScore() {
        return creditScore;
    }

    public void setCreditScore(Integer creditScore) {
        this.creditScore = creditScore;
    }

    public Long getPermVersion() {
        return permVersion;
    }

    public void setPermVersion(Long permVersion) {
        this.permVersion = permVersion;
    }

    public LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(LocalDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
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

    public boolean isRealNameVerified() {
        return realName != null && !realName.isBlank();
    }
}
