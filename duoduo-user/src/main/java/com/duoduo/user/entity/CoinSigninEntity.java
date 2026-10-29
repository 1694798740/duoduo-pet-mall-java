package com.duoduo.user.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 多多币签到行（R-P2）：用户一天最多一行（{@code uk_coin_signin}）。
 *
 * <p>唯一索引是防刷的最终闸门：服务层先查重只是友好提示，
 * 并发双击 / 脚本重放由 INSERT IGNORE 撞键兜底，奖励绝不可能一天发两次。
 * {@code continuousDays} 是当日快照：断签（昨天没签）重置为 1，
 * 连签加发（达 3/7 天）只读昨天一行即可判定，不回扫历史。
 */
@TableName("t_coin_signin")
public class CoinSigninEntity {

    @TableId
    private Long id;
    private Long userId;
    /** 签到日：Asia/Shanghai 本地日（用户日历直觉），DATE 类型 */
    private LocalDate signDate;
    /** 当日实际发放币数（含连签加发） */
    private Integer coins;
    /** 当日连签天数快照 */
    private Integer continuousDays;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public LocalDate getSignDate() { return signDate; }
    public void setSignDate(LocalDate signDate) { this.signDate = signDate; }
    public Integer getCoins() { return coins; }
    public void setCoins(Integer coins) { this.coins = coins; }
    public Integer getContinuousDays() { return continuousDays; }
    public void setContinuousDays(Integer continuousDays) { this.continuousDays = continuousDays; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
