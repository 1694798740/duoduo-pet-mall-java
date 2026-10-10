package com.duoduo.user.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * E21 · TOTP 恢复码（一次性）。
 *
 * <p>绑定两步验证后一次性下发若干枚，用于"手机丢了 / 换机没导出认证器"的场景。
 * 三条纪律写在这里，改动时不要"简化"：
 * <ul>
 *   <li><b>只存哈希</b>（与口令同一纪律）：库被读走时明文码不能跟着一起泄。
 *       明文只在生成那一刻返回给用户一次，之后服务端自己也不认；</li>
 *   <li><b>用一次作废一枚</b>：{@link #usedAt} 非空即不可再次用于登录。
 *       这一条同时给了"用了哪一枚"的审计痕迹；</li>
 *   <li><b>失败尝试不消耗恢复码</b>：只有校验通过才写 usedAt ——
 *       否则攻击者可以用错误输入把用户的恢复码一枚枚耗光。</li>
 * </ul>
 */
@TableName("t_totp_recovery_code")
public class TotpRecoveryCodeEntity {

    @TableId
    private Long id;
    private Long userId;
    /** 恢复码明文的 SHA-256 十六进制；禁止存明文 */
    private String codeHash;
    /** 使用时间（UTC）；非 null = 已用，不可再次用于登录 */
    private LocalDateTime usedAt;
    private LocalDateTime createdAt;

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

    public String getCodeHash() {
        return codeHash;
    }

    public void setCodeHash(String codeHash) {
        this.codeHash = codeHash;
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
}
