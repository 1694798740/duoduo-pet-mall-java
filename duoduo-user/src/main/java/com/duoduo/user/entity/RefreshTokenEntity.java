package com.duoduo.user.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 刷新令牌记录。
 *
 * <p>安全设计：只保存令牌的 SHA-256 哈希，令牌本身不落库 —— 即使数据库泄露，
 * 攻击者也无法直接冒用。轮转策略：每次刷新都吊销旧记录并签发新记录，
 * 旧令牌不可复用，有效缓解刷新令牌被窃取后的重放。
 */
@TableName("t_refresh_token")
public class RefreshTokenEntity {

    @TableId
    private String id;
    private Long userId;
    private String tokenHash;
    private LocalDateTime expiresAt;
    private Integer revoked;
    private String userAgent;
    private LocalDateTime createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Integer getRevoked() {
        return revoked;
    }

    public void setRevoked(Integer revoked) {
        this.revoked = revoked;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
