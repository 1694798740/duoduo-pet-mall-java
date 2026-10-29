package com.duoduo.user.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 多多币视图与命令（R-P1）。
 *
 * <p>所有金额口径：<b>币数</b>与<b>抵扣金额（分）</b>数值相等（1 币 = 1 分），
 * 字段名区分 {@code coins}（数量）与 {@code amount}（金额），避免混用导致换算错位。
 */
public final class CoinDtos {

    private CoinDtos() {
    }

    /** 买家账户概览（「我的多多币」页首屏） */
    public record CoinBalanceView(
            long balance,
            long totalEarned,
            long totalSpent,
            long expiringSoonCoins,
            List<ExpiringLotView> expiringLots
    ) {
    }

    /** 30 天内到期批次（提示用户「即将过期」） */
    public record ExpiringLotView(
            String lotId,
            long remaining,
            String expireAt
    ) {
    }

    /** 流水行（买家与后台共用一份视图，后台多看 userId） */
    public record CoinLedgerView(
            String id,
            Long userId,
            String direction,
            long amount,
            long balanceAfter,
            String bizType,
            String bizNo,
            String remark,
            String createdAt
    ) {
    }

    /** 运营侧账户汇总（负债 = 全站未过期余额合计） */
    public record CoinAdminSummary(
            long totalBalance,
            long totalEarned,
            long totalSpent,
            long totalExpired,
            long accounts,
            long expiringIn30dCoins
    ) {
    }

    /** 人工调整命令（带审计留痕） */
    public record CoinAdjustRequest(
            Long userId,
            Long amount,
            String reason
    ) {
    }

    /** 对账结果（零条目 = 账实一致） */
    public record CoinReconResult(
            int accountsChecked,
            List<String> issues
    ) {
    }

    /** 内部接口 · 返币 / 调整结果 */
    public record CoinMutationResult(
            boolean applied,
            long balance
    ) {
    }

    /** 签到结果（R-P2）：当日发放数 / 连签天数 / 签后余额 */
    public record CoinSigninResult(
            boolean applied,
            int coins,
            int continuousDays,
            long balance
    ) {
    }

    /** 签到月历（R-P2）：当月已签日期（ISO yyyy-MM-dd）+ 连签快照 + 今日是否已签 */
    public record CoinSigninMonthView(
            String month,
            List<String> signedDates,
            int continuousDays,
            boolean signedToday
    ) {
    }

    /** 任务中心单条（R-P1 第三轮）：进度 / 是否已领 / 是否达成 */
    public record CoinTaskView(
            String code,
            String name,
            String taskType,
            String actionType,
            int targetCount,
            int progress,
            int rewardCoins,
            boolean rewarded,
            boolean completed,
            String description
    ) {
    }

    /** 任务上报命令（浏览类）：停留时长由服务端校验，不足阈值不计进度 */
    public record CoinTaskReportRequest(
            String taskCode,
            String bizId,
            Integer dwellSeconds
    ) {
    }

    /** 活动视图（买家端「进行中的活动」 + 运营端列表共用） */
    public record CoinCampaignView(
            String id,
            String code,
            String name,
            String startAt,
            String endAt,
            BigDecimal signinMultiplier,
            BigDecimal earnMultiplier,
            long budgetTotal,
            long budgetUsed,
            long perUserCap,
            String status
    ) {
    }

    /** 活动保存命令（运营端）：时间支持 ISO（不带时区按北京时间）；倍率 1.00 = 不加成 */
    public record CoinCampaignSaveRequest(
            String code,
            String name,
            String startAt,
            String endAt,
            BigDecimal signinMultiplier,
            BigDecimal earnMultiplier,
            Long budgetTotal,
            Long perUserCap
    ) {
    }

    /** 活动启停命令：enabled=true → ENABLED，false → DISABLED */
    public record CoinCampaignStatusRequest(
            Boolean enabled
    ) {
    }

    /** 运营端任务定义视图（含停用任务，便于运营看到全量） */
    public record CoinTaskDefAdminView(
            String id,
            String code,
            String name,
            String taskType,
            String actionType,
            int targetCount,
            int rewardCoins,
            int dailyCap,
            boolean enabled,
            int sortNo,
            String description
    ) {
    }

    /** 任务定义更新命令：null 字段表示「不改」 */
    public record CoinTaskDefUpdateRequest(
            Boolean enabled,
            Integer rewardCoins,
            Integer dailyCap
    ) {
    }
}
