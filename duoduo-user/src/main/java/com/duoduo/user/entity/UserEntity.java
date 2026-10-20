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
    /**
     * E21 · TOTP 共享密钥（Base32 无填充）。null = 未绑定。
     *
     * <p>它与 {@link #totpEnabled} 的**组合**才有意义：secret 非空但 enabled 为 false，
     * 表示"已生成密钥、用户还没完成验证"的过渡态 —— 这种状态下登录不要求动态码，
     * 但再次调用 setup 会覆盖密钥（等于作废上一个二维码）。
     */
    private String totpSecret;
    /** E21 · 是否已完成绑定验证；true 时登录必须携带动态码或恢复码 */
    private Boolean totpEnabled;
    /** E21 · 绑定完成时间（UTC） */
    private LocalDateTime totpBoundAt;
    /**
     * E21 · 最近一次**成功校验**的时间步。
     *
     * <p>用于防重放：同一枚动态码在 ±30 秒窗口内可被重复提交，只有记录"已经用掉的时间步"
     * 才能拒绝"截获后原样重放"。允许 null（从未成功校验过）。
     */
    private Long totpLastStep;
    /**
     * C7 · 账号注销状态：{@code ACTIVE} / {@code CANCELLING}（冷静期，登录停用、可撤销）
     * / {@code DELETED}（已注销，PII 已清空、行保留）。
     *
     * <p>与 {@link #status} 正交：status 管封禁，本列管注销。刻意不复用 status，
     * 是因为「已注销」必须能被登录/刷新/调度三处稳定识别，而封禁态另有一套
     * 「仅可浏览与申诉」的语义，混在一列里迟早会写出 {@code status==DELETED && 允许浏览}
     * 这类自相矛盾的判断。
     */
    private String accountStatus;
    /** C7 · 用户提交注销申请的 UTC 时间 */
    private LocalDateTime deleteRequestedAt;
    /** C7 · 冷静期截止 UTC 时间；调度到点执行注销（{@code AccountDeletionJob}） */
    private LocalDateTime deleteScheduledAt;
    /** C7 · 用户填写的注销原因（可选） */
    private String deleteReason;
    /** C7 · 实际执行注销的 UTC 时间（五域个人数据清理全部成功后写入） */
    private LocalDateTime deleteExecutedAt;
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

    public String getTotpSecret() {
        return totpSecret;
    }

    public void setTotpSecret(String totpSecret) {
        this.totpSecret = totpSecret;
    }

    public Boolean getTotpEnabled() {
        return totpEnabled;
    }

    public void setTotpEnabled(Boolean totpEnabled) {
        this.totpEnabled = totpEnabled;
    }

    public LocalDateTime getTotpBoundAt() {
        return totpBoundAt;
    }

    public void setTotpBoundAt(LocalDateTime totpBoundAt) {
        this.totpBoundAt = totpBoundAt;
    }

    public Long getTotpLastStep() {
        return totpLastStep;
    }

    public void setTotpLastStep(Long totpLastStep) {
        this.totpLastStep = totpLastStep;
    }

    public String getAccountStatus() {
        return accountStatus;
    }

    public void setAccountStatus(String accountStatus) {
        this.accountStatus = accountStatus;
    }

    public LocalDateTime getDeleteRequestedAt() {
        return deleteRequestedAt;
    }

    public void setDeleteRequestedAt(LocalDateTime deleteRequestedAt) {
        this.deleteRequestedAt = deleteRequestedAt;
    }

    public LocalDateTime getDeleteScheduledAt() {
        return deleteScheduledAt;
    }

    public void setDeleteScheduledAt(LocalDateTime deleteScheduledAt) {
        this.deleteScheduledAt = deleteScheduledAt;
    }

    public String getDeleteReason() {
        return deleteReason;
    }

    public void setDeleteReason(String deleteReason) {
        this.deleteReason = deleteReason;
    }

    public LocalDateTime getDeleteExecutedAt() {
        return deleteExecutedAt;
    }

    public void setDeleteExecutedAt(LocalDateTime deleteExecutedAt) {
        this.deleteExecutedAt = deleteExecutedAt;
    }

    /**
     * E21 · 是否已启用两步验证。
     *
     * <p>统一走这个判断，而不是各处写 {@code Boolean.TRUE.equals(getTotpEnabled())}：
     * 老库的该列可能为 NULL（虽然 DDL 上是 NOT NULL DEFAULT 0，但读旧数据时不能假设），
     * 拼错一次就是"2FA 看起来绑了、实际没拦"，这类错误在测试里只表现为"少了一次校验请求"。
     */
    public boolean isTwoFactorEnabled() {
        return Boolean.TRUE.equals(totpEnabled);
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
