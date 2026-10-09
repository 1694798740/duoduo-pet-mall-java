package com.duoduo.user.support;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 资质有效期判定（E15）。
 *
 * <p>这是 E15 唯一的「到期语义」定义处：提醒任务、上架门禁、商家端横幅全部调这里，
 * 免得三处各写一遍 {@code expireAt.isBefore(now)} 然后在边界上互相打架。
 *
 * <h2>档位</h2>
 * <pre>
 *   NONE  ── 无到期概念（expire_at 为 NULL）或还很远（&gt; 30 天）
 *   T30   ── 剩余 ≤ 30 天（第一次提醒）
 *   T7    ── 剩余 ≤ 7 天（第二次提醒）
 *   EXPIRED ── 已到期（自动停业）
 * </pre>
 * 档位是<b>有序</b>的（{@link Stage#rank()} 越大越紧急），提醒去重靠这个顺序：
 * 「新档位比已记档位更深才发一条」—— 于是同一批临期店铺每天被扫到也只发两次
 * （进 30 天档一次、进 7 天档一次），不会变成每日骚扰。
 *
 * <h2>两条容易写错的边界</h2>
 * <ol>
 *   <li><b>NULL 不是过期，是「无到期概念」</b>。存量材料、经营承诺书这类没有到期日的
 *       材料都是 NULL。把它当过期的后果是所有老商家在迁移瞬间集体停业。</li>
 *   <li><b>到期日当天仍算有效</b>。{@code expire_at} 由服务端按「当日 23:59:59」落库
 *       （见 {@code MerchantOnboardingService#parseExpireDate}），所以「有效期至 12-31」
 *       的执照在 12-31 白天依然可用，12-31 结束才转 EXPIRED。</li>
 * </ol>
 *
 * <p>剩余天数按<b>日历日</b>算（不是 24 小时块）：商家说「还有 7 天到期」指的是
 * 日历上还有 7 个格，而不是 168 个小时。用 {@link ChronoUnit#DAYS} 的整数差会让
 * 「12-31 到期、今天 12-24」算成 6 天（因为 12-24 10:00 到 12-31 23:59:59 不足 7 个整天），
 * 与人的直觉差一天 —— 客诉就出在这种地方。
 */
public final class LicenseExpiry {

    /** 第一次提醒的提前量（天） */
    public static final int REMIND_DAYS_FIRST = 30;
    /** 第二次提醒的提前量（天） */
    public static final int REMIND_DAYS_SECOND = 7;

    /**
     * 到期档位。**枚举顺序即深浅顺序**，但代码不要依赖 ordinal ——
     * 用 {@link #rank()}，这样将来有人在中间插一个新档位也不会静默出错。
     */
    public enum Stage {

        NONE(0, "未进入提醒区间"),
        T30(1, "即将到期"),
        T7(2, "临近到期"),
        EXPIRED(3, "已到期");

        private final int rank;
        private final String label;

        Stage(int rank, String label) {
            this.rank = rank;
            this.label = label;
        }

        public int rank() {
            return rank;
        }

        public String label() {
            return label;
        }

        /** 是否比另一档更深（更紧急）。提醒去重的唯一判据 */
        public boolean deeperThan(Stage other) {
            return other != null && this.rank > other.rank;
        }

        /** 是否需要商家去办续期（T7 之后就该催了） */
        public boolean needsRenewal() {
            return this == T7 || this == EXPIRED;
        }

        /**
         * 从库里存的字符串还原档位。
         *
         * <p>宽容处理：空值或无法识别一律按 {@link #NONE}。理由是这一列只用于
         * 「提醒过没有」的去重，读不懂时当作「没提醒过」最多多发一条通知；
         * 而如果抛异常，一条脏数据会让整个到期扫描任务停摆 —— 代价不对称。
         */
        public static Stage parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return NONE;
            }
            for (Stage s : values()) {
                if (s.name().equalsIgnoreCase(raw.trim())) {
                    return s;
                }
            }
            return NONE;
        }
    }

    private LicenseExpiry() {
    }

    /**
     * 判定某份证照在 {@code now} 时刻处于哪一档。
     *
     * @param expireAt 到期时刻（含当日有效，即当日 23:59:59）；{@code null} = 长期有效
     * @param now      判定基准时刻（UTC）
     */
    public static Stage stageOf(LocalDateTime expireAt, LocalDateTime now) {
        if (expireAt == null) {
            return Stage.NONE;          // 长期有效：既不提醒，也永不过期
        }
        if (!now.isBefore(expireAt)) {
            return Stage.EXPIRED;       // 到期时刻已到（含恰好相等）
        }
        long days = remainingDays(expireAt, now);
        if (days <= REMIND_DAYS_SECOND) {
            return Stage.T7;
        }
        if (days <= REMIND_DAYS_FIRST) {
            return Stage.T30;
        }
        return Stage.NONE;
    }

    /**
     * 剩余日历天数（已过期为负数）。
     *
     * <p>{@code expireAt} 为 {@code null} 时返回 {@link Long#MAX_VALUE}：
     * 「长期有效」的剩余天数在数学上就是无穷，用一个哨兵值比返回 0（会被读成「今天到期」）
     * 安全得多。调用方展示前应先判 {@link #hasExpiry}。
     */
    public static long remainingDays(LocalDateTime expireAt, LocalDateTime now) {
        if (expireAt == null) {
            return Long.MAX_VALUE;
        }
        return ChronoUnit.DAYS.between(now.toLocalDate(), expireAt.toLocalDate());
    }

    /** 是否具有到期概念（用于「长期有效」文案与提醒跳过判断） */
    public static boolean hasExpiry(LocalDateTime expireAt) {
        return expireAt != null;
    }

    /**
     * 从多份证照的到期时刻里取最早的那个 —— 店铺级到期日的定义。
     *
     * <p>语义是「本店所有证照里最先失效的那一天」：只要有一张证过期，
     * 这家店就处于违规经营状态，所以取 min 而不是「随便一张」或「平均值」。
     * 全为 {@code null}（都不带有效期）→ 返回 {@code null}（无到期概念）。
     */
    public static LocalDateTime earliest(LocalDateTime... expireAts) {
        LocalDateTime min = null;
        if (expireAts == null) {
            return null;
        }
        for (LocalDateTime at : expireAts) {
            if (at == null) {
                continue;               // 长期有效的证不参与取最小
            }
            if (min == null || at.isBefore(min)) {
                min = at;
            }
        }
        return min;
    }
}
