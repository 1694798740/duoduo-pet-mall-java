package com.duoduo.user.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 资质到期判定单测（E15）。
 *
 * <h2>这组测试守的是什么</h2>
 * 到期判定本身只有三行代码，但它是<b>会自己动手停业</b>的那类逻辑：
 * 边界写偏一天，商家就会在执照还有效时被下架，或者过期一个月还挂在架上卖。
 * 而且错的两种方向都不会有人报障 —— 前者商家暴怒但找不到原因，后者平台担责却无人察觉。
 * 因此这里把每个档位的<b>边界值本身</b>（30 / 7 / 0 / 负一天）逐一钉死，
 * 而不是抽样几个「差不多」的日期。
 *
 * <p>另外两条容易被「顺手优化」掉的语义也在这里加锁：
 * {@code null} 不等于过期（否则存量商家集体停业）、到期日当天仍有效。
 */
class LicenseExpiryTest {

    /** 判定基准：2026-06-15 10:00 UTC */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 15, 10, 0);

    /** 服务端落库口径：把「有效期至某日」转成当日 23:59:59 */
    private static LocalDateTime endOfDay(int year, int month, int day) {
        return LocalDateTime.of(year, month, day, 23, 59, 59);
    }

    // ---------------- NULL 语义 ----------------

    @Test
    @DisplayName("NULL 到期日 = 长期有效，不是过期（否则存量商家会被集体停业）")
    void nullIsNotExpired() {
        assertThat(LicenseExpiry.stageOf(null, NOW)).isEqualTo(LicenseExpiry.Stage.NONE);
        assertThat(LicenseExpiry.hasExpiry(null)).isFalse();
    }

    @Test
    @DisplayName("NULL 的剩余天数是哨兵值而不是 0（0 会被读成「今天到期」）")
    void nullRemainingDaysIsSentinel() {
        assertThat(LicenseExpiry.remainingDays(null, NOW)).isEqualTo(Long.MAX_VALUE);
    }

    // ---------------- EXPIRED 边界 ----------------

    @Test
    @DisplayName("恰好到到期时刻 = 过期（边界含等号）")
    void exactlyAtExpiryIsExpired() {
        assertThat(LicenseExpiry.stageOf(NOW, NOW)).isEqualTo(LicenseExpiry.Stage.EXPIRED);
    }

    @Test
    @DisplayName("到期前一秒仍未过期（不是过期）")
    void oneSecondBeforeExpiryIsNotExpired() {
        assertThat(LicenseExpiry.stageOf(NOW.plusSeconds(1), NOW))
                .isEqualTo(LicenseExpiry.Stage.T7);
    }

    @Test
    @DisplayName("★ 到期日当天白天仍算有效：有效期至 6-15 的执照，6-15 10:00 还能营业")
    void expiryDayItselfIsStillValid() {
        // 这正是「落库取当日 23:59:59」的意义所在：若存成当日 00:00:00，
        // 商家在到期日当天 0 点就被停业，等于少用一整天。
        assertThat(LicenseExpiry.stageOf(endOfDay(2026, 6, 15), NOW))
                .isEqualTo(LicenseExpiry.Stage.T7);
        assertThat(LicenseExpiry.remainingDays(endOfDay(2026, 6, 15), NOW)).isZero();
    }

    @Test
    @DisplayName("过期一天 = EXPIRED")
    void oneDayPastIsExpired() {
        assertThat(LicenseExpiry.stageOf(endOfDay(2026, 6, 14), NOW))
                .isEqualTo(LicenseExpiry.Stage.EXPIRED);
        assertThat(LicenseExpiry.remainingDays(endOfDay(2026, 6, 14), NOW)).isEqualTo(-1);
    }

    // ---------------- T7 / T30 / NONE 边界 ----------------

    @Test
    @DisplayName("剩余 7 天 = T7（下边界含）")
    void sevenDaysIsT7() {
        assertThat(LicenseExpiry.stageOf(endOfDay(2026, 6, 22), NOW))
                .isEqualTo(LicenseExpiry.Stage.T7);
    }

    @Test
    @DisplayName("剩余 8 天 = T30（刚出 T7）")
    void eightDaysIsT30() {
        assertThat(LicenseExpiry.stageOf(endOfDay(2026, 6, 23), NOW))
                .isEqualTo(LicenseExpiry.Stage.T30);
    }

    @Test
    @DisplayName("剩余 30 天 = T30（下边界含）")
    void thirtyDaysIsT30() {
        assertThat(LicenseExpiry.stageOf(endOfDay(2026, 7, 15), NOW))
                .isEqualTo(LicenseExpiry.Stage.T30);
        assertThat(LicenseExpiry.remainingDays(endOfDay(2026, 7, 15), NOW)).isEqualTo(30);
    }

    @Test
    @DisplayName("剩余 31 天 = NONE（不进提醒区间，免得天天提醒）")
    void thirtyOneDaysIsNone() {
        assertThat(LicenseExpiry.stageOf(endOfDay(2026, 7, 16), NOW))
                .isEqualTo(LicenseExpiry.Stage.NONE);
    }

    @Test
    @DisplayName("★ 剩余天数按日历日算：6-15 10:00 看 6-22 到期是 7 天，不是 6 天")
    void remainingDaysCountsCalendarDays() {
        // 若按 24 小时块（ChronoUnit.DAYS 对时刻求差）会得 6，比人的直觉少一天。
        // 商家看到「还有 6 天」去办事，第七天回来店已被停 —— 客诉就出在这里。
        assertThat(LicenseExpiry.remainingDays(endOfDay(2026, 6, 22), NOW)).isEqualTo(7);
    }

    // ---------------- 档位深浅（提醒去重的唯一依据） ----------------

    @Test
    @DisplayName("档位严格有序：NONE < T30 < T7 < EXPIRED")
    void stageOrderIsStrict() {
        assertThat(LicenseExpiry.Stage.T30.deeperThan(LicenseExpiry.Stage.NONE)).isTrue();
        assertThat(LicenseExpiry.Stage.T7.deeperThan(LicenseExpiry.Stage.T30)).isTrue();
        assertThat(LicenseExpiry.Stage.EXPIRED.deeperThan(LicenseExpiry.Stage.T7)).isTrue();
        // 反向与同级都必须为 false，否则同一档位会被反复提醒
        assertThat(LicenseExpiry.Stage.NONE.deeperThan(LicenseExpiry.Stage.T30)).isFalse();
        assertThat(LicenseExpiry.Stage.T30.deeperThan(LicenseExpiry.Stage.T30)).isFalse();
        assertThat(LicenseExpiry.Stage.T7.deeperThan(null)).isFalse();
    }

    @Test
    @DisplayName("需要商家去续期的档位是 T7 与 EXPIRED（T30 只提示）")
    void needsRenewalOnlyWhenUrgent() {
        assertThat(LicenseExpiry.Stage.NONE.needsRenewal()).isFalse();
        assertThat(LicenseExpiry.Stage.T30.needsRenewal()).isFalse();
        assertThat(LicenseExpiry.Stage.T7.needsRenewal()).isTrue();
        assertThat(LicenseExpiry.Stage.EXPIRED.needsRenewal()).isTrue();
    }

    @Test
    @DisplayName("档位字符串还原：未知值与空值一律按 NONE（一条脏数据不该让扫描任务停摆）")
    void parseIsLenient() {
        assertThat(LicenseExpiry.Stage.parse("T30")).isEqualTo(LicenseExpiry.Stage.T30);
        assertThat(LicenseExpiry.Stage.parse(" t7 ")).isEqualTo(LicenseExpiry.Stage.T7);
        assertThat(LicenseExpiry.Stage.parse(null)).isEqualTo(LicenseExpiry.Stage.NONE);
        assertThat(LicenseExpiry.Stage.parse("")).isEqualTo(LicenseExpiry.Stage.NONE);
        assertThat(LicenseExpiry.Stage.parse("WHATEVER")).isEqualTo(LicenseExpiry.Stage.NONE);
    }

    // ---------------- 店铺级最早到期日 ----------------

    @Test
    @DisplayName("取最早到期日：只要有一张先过期，全店就算到期（取 min 不是取平均）")
    void earliestPicksMinimum() {
        LocalDateTime a = endOfDay(2027, 1, 1);
        LocalDateTime b = endOfDay(2026, 8, 1);
        LocalDateTime c = endOfDay(2026, 12, 1);
        assertThat(LicenseExpiry.earliest(a, b, c)).isEqualTo(b);
    }

    @Test
    @DisplayName("长期有效的证不参与取最小；全为 NULL 时结果仍是 NULL（无到期概念）")
    void earliestSkipsNulls() {
        assertThat(LicenseExpiry.earliest(null, endOfDay(2026, 8, 1), null))
                .isEqualTo(endOfDay(2026, 8, 1));
        assertThat(LicenseExpiry.earliest((LocalDateTime[]) null)).isNull();
        assertThat(LicenseExpiry.earliest()).isNull();
        assertThat(LicenseExpiry.earliest(null, null)).isNull();
    }
}
