package com.duoduo.common.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 审核 SLA 规则单测（E28）。
 *
 * <h2>这组测试守的是什么</h2>
 * 抽查制度是「免除事前人工审核」的对价。它一旦悄悄失效，平台不会报错、页面不会变红 ——
 * 只会出现「所有东西都自动放行了，而且谁都没回头看」。这类失效没有现象，
 * 因此规则本身必须被钉死：
 *
 * <ol>
 *   <li><b>可复现</b>：同一天同一批数据，谁算、什么时候算、算几遍，结果必须是同一批。
 *       若用了随机数，运营看到队列里突然多出一批重复项就再也不敢信这套制度；</li>
 *   <li><b>包含关系</b>（本类里最重要的一条）：比例从 5% 抬到 10% 时，
 *       原来被抽中的那批必须<b>仍然</b>在集合里。否则「收紧」会把上次的复核结论作废
 *       （同一件商品被重复建单、或已判合规的又被拉出来重判）；</li>
 *   <li><b>边界方向</b>：恰好 10% 的违规率<b>不</b>算超标、放行 2 件才全查、放行 3 件就不再全查 ——
 *       这些边界写偏一个方向，要么永远不收紧（制度烂掉），要么天天收紧（运营被淹没）。</li>
 * </ol>
 *
 * <p>本类不碰数据库、不启动 Spring：规则是纯函数，测试就不该需要一整套运行环境。
 */
class AuditSlaPolicyTest {

    /** 造一批「像雪花 id」的主键：高位相同、低位递增 —— 正是会暴露「取模露出规律」的那种数据 */
    private static List<Long> snowflakeLikeIds(int count) {
        return LongStream.range(0, count)
                .map(i -> 1000000000000000000L + i * 4096L)
                .boxed()
                .toList();
    }

    // ---------------- 哈希取模：可复现 ----------------

    @Test
    @DisplayName("★ 同一主键 + 同一天 → 桶号恒定（重跑任务不会换人）")
    void bucketIsDeterministic() {
        LocalDate day = LocalDate.of(2026, 10, 9);
        int first = AuditSlaPolicy.hashBucket(123456789L, day);
        for (int i = 0; i < 200; i++) {
            assertThat(AuditSlaPolicy.hashBucket(123456789L, day)).isEqualTo(first);
        }
    }

    @Test
    @DisplayName("桶号落在 [0,100)：直接决定「桶号 < 比例」这条判据能不能用")
    void bucketIsWithinHundred() {
        LocalDate day = LocalDate.of(2026, 10, 9);
        for (long id : snowflakeLikeIds(5000)) {
            assertThat(AuditSlaPolicy.hashBucket(id, day)).isBetween(0, 99);
        }
    }

    @Test
    @DisplayName("日期参与哈希：同一条商品换一天重算，桶号会变（但不是所有天都相同）")
    void bucketDependsOnDate() {
        long id = 1000000000000004096L;
        List<Integer> buckets = new ArrayList<>();
        for (int d = 1; d <= 30; d++) {
            buckets.add(AuditSlaPolicy.hashBucket(id, LocalDate.of(2026, 10, d)));
        }
        assertThat(buckets.stream().distinct().count())
                .as("30 天里桶号应当明显分散，否则「换一天换一批」不成立")
                .isGreaterThan(20);
    }

    @Test
    @DisplayName("没有日期就没有幂等键 → 直接拒绝，而不是悄悄用今天")
    void bucketRejectsNullDate() {
        assertThatThrownBy(() -> AuditSlaPolicy.hashBucket(1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("日期");
    }

    @Test
    @DisplayName("★ 抽样按比例单调：比例提高时，原集合是子集（5% ⊂ 10% ⊂ 20%）")
    void higherRateSupersetOfLowerRate() {
        LocalDate day = LocalDate.of(2026, 10, 9);
        List<Long> ids = snowflakeLikeIds(20000);

        List<Long> at5 = ids.stream().filter(id -> AuditSlaPolicy.shouldSample(id, day, 5)).toList();
        List<Long> at10 = ids.stream().filter(id -> AuditSlaPolicy.shouldSample(id, day, 10)).toList();
        List<Long> at20 = ids.stream().filter(id -> AuditSlaPolicy.shouldSample(id, day, 20)).toList();

        // 这条断言就是「收紧之后重新抽不会作废上次结论」的全部依据
        assertThat(at10).containsAll(at5);
        assertThat(at20).containsAll(at10);

        // 同时它们不能相等 —— 相等说明比例变了却没多抽，那「收紧」只是个数字
        assertThat(at10.size()).isGreaterThan(at5.size());
        assertThat(at20.size()).isGreaterThan(at10.size());
    }

    @Test
    @DisplayName("哈希把低位打散：命中率贴近设定比例（不是「每隔 20 个抽 1 个」的规律）")
    void rateIsApproximatelyRespected() {
        LocalDate day = LocalDate.of(2026, 10, 9);
        List<Long> ids = snowflakeLikeIds(20000);
        long hit = ids.stream().filter(id -> AuditSlaPolicy.shouldSample(id, day, 5)).count();
        double percent = hit * 100.0 / ids.size();
        // 区间刻意留宽：这条守的是「没有系统性偏离」（如命中率 50% 或 0.2%），
        // 不是「精确等于 5%」—— 钉死小数位只会让哈希实现的任何合法调整都变红。
        assertThat(percent).isBetween(3.5, 6.5);
    }

    // ---------------- shouldSample 的边界 ----------------

    @Test
    @DisplayName("比例 0 = 制度关闭：谁都不抽（而不是「按 0% 也至少抽 1 条」）")
    void zeroRateSamplesNothing() {
        LocalDate day = LocalDate.of(2026, 10, 9);
        for (long id : snowflakeLikeIds(2000)) {
            assertThat(AuditSlaPolicy.shouldSample(id, day, 0)).isFalse();
        }
    }

    @Test
    @DisplayName("比例 100 及以上 = 全抽；负数也当关闭处理")
    void fullAndNegativeRate() {
        LocalDate day = LocalDate.of(2026, 10, 9);
        assertThat(AuditSlaPolicy.shouldSample(1L, day, 100)).isTrue();
        assertThat(AuditSlaPolicy.shouldSample(1L, day, 120)).isTrue();
        assertThat(AuditSlaPolicy.shouldSample(1L, day, -5)).isFalse();
    }

    @Test
    @DisplayName("单条判定与「按桶号排序取前 K」是同一套哈希（两条路径不会打架）")
    void singleCheckMatchesBucketRanking() {
        LocalDate day = LocalDate.of(2026, 10, 9);
        List<Long> ids = snowflakeLikeIds(3000);

        // 「桶号 < 5」的那批
        List<Long> bySingle = ids.stream().filter(id -> AuditSlaPolicy.shouldSample(id, day, 5)).toList();
        // 「按桶号升序取前面所有桶号 < 5 的」—— 与按天取 K 条的任务侧算法同构
        List<Long> byRank = ids.stream()
                .filter(id -> AuditSlaPolicy.hashBucket(id, day) < 5)
                .toList();

        assertThat(bySingle).containsExactlyElementsOf(byRank);
    }

    // ---------------- targetSampleCount：每日抽几条 ----------------

    @Test
    @DisplayName("当天没放行任何东西 → 目标 0")
    void noReleaseNoTarget() {
        assertThat(AuditSlaPolicy.targetSampleCount(0, 5)).isZero();
    }

    @Test
    @DisplayName("比例被关掉（0）→ 目标 0，避免出现「比例 0% 却目标 3 条」的自相矛盾")
    void closedRateHasNoTarget() {
        assertThat(AuditSlaPolicy.targetSampleCount(500, 0)).isZero();
    }

    @Test
    @DisplayName("★ 放行不足 3 件 → 全查（少到不值得抽样），放行 3 件起就不再全查")
    void tinyDayIsFullySampled() {
        assertThat(AuditSlaPolicy.targetSampleCount(1, 5)).isEqualTo(1);
        assertThat(AuditSlaPolicy.targetSampleCount(2, 5)).isEqualTo(2);
        // 放行 3 件：按比例 5% 只算出 1 条，但被「每日至少 3 条」这条下限兜住 → 3 条（= 全查）
        assertThat(AuditSlaPolicy.targetSampleCount(3, 5)).isEqualTo(3);
    }

    @Test
    @DisplayName("按比例向上取整：放行 50 件 → ceil(2.5) = 3 条")
    void roundsUpByRate() {
        assertThat(AuditSlaPolicy.targetSampleCount(50, 5)).isEqualTo(3);
        assertThat(AuditSlaPolicy.targetSampleCount(100, 5)).isEqualTo(5);
    }

    @Test
    @DisplayName("每日不低于 3 条：放行 10 件时 5% 只算出 1 条，仍抽 3 条")
    void floorIsThreePerDay() {
        assertThat(AuditSlaPolicy.targetSampleCount(10, 5)).isEqualTo(3);
        assertThat(AuditSlaPolicy.targetSampleCount(19, 5)).isEqualTo(3);
    }

    @Test
    @DisplayName("每日不高于 20 条：放行 1000 件时 5% 算出 50 条，夹到 20 条")
    void ceilingIsTwentyPerDay() {
        assertThat(AuditSlaPolicy.targetSampleCount(1000, 5)).isEqualTo(20);
        assertThat(AuditSlaPolicy.targetSampleCount(10000, 40)).isEqualTo(20);
    }

    @Test
    @DisplayName("目标不超过当天放行量本身（放行 4 件、比例 40% 时不能抽 3 条以上）")
    void targetNeverExceedsReleased() {
        assertThat(AuditSlaPolicy.targetSampleCount(4, 40)).isEqualTo(3);
        assertThat(AuditSlaPolicy.targetSampleCount(3, 40)).isEqualTo(3);
    }

    @Test
    @DisplayName("抽中理由：放行量少 → 全查；放行量正常 → 基础抽查")
    void baseReasonFollowsVolume() {
        assertThat(AuditSlaPolicy.baseReason(0)).isEqualTo(AuditSlaPolicy.Reason.TINY_ALL);
        assertThat(AuditSlaPolicy.baseReason(2)).isEqualTo(AuditSlaPolicy.Reason.TINY_ALL);
        assertThat(AuditSlaPolicy.baseReason(3)).isEqualTo(AuditSlaPolicy.Reason.BASE);
        assertThat(AuditSlaPolicy.baseReason(500)).isEqualTo(AuditSlaPolicy.Reason.BASE);
    }

    @Test
    @DisplayName("每个抽中理由都有中文说明：队列里要给运营看「为什么抽到它」")
    void everyReasonHasLabel() {
        for (AuditSlaPolicy.Reason r : AuditSlaPolicy.Reason.values()) {
            assertThat(r.label()).isNotBlank();
        }
    }

    // ---------------- 自动收紧 ----------------

    private static AuditSlaPolicy.DayRate day(int dayOfMonth, int sampled, int violated) {
        return new AuditSlaPolicy.DayRate(LocalDate.of(2026, 10, dayOfMonth), sampled, violated);
    }

    @Test
    @DisplayName("没有历史数据 → 比例不变（不能在无证据时加码）")
    void noHistoryNoTightening() {
        assertThat(AuditSlaPolicy.tightenRateIfNeeded(5, null)).isEqualTo(5);
        assertThat(AuditSlaPolicy.tightenRateIfNeeded(5, List.of())).isEqualTo(5);
    }

    @Test
    @DisplayName("★ 观察期不够（只有 2 天数据）→ 不收紧，避免单日波动触发全站加码")
    void insufficientObservationWindow() {
        List<AuditSlaPolicy.DayRate> twoDays = List.of(day(8, 10, 5), day(9, 10, 5));
        assertThat(AuditSlaPolicy.tightenRateIfNeeded(5, twoDays)).isEqualTo(5);
    }

    @Test
    @DisplayName("连续 3 天违规率超标 → 比例翻倍")
    void threeBadDaysDoubles() {
        List<AuditSlaPolicy.DayRate> bad = List.of(day(7, 10, 3), day(8, 10, 4), day(9, 10, 2));
        assertThat(AuditSlaPolicy.tightenRateIfNeeded(5, bad)).isEqualTo(10);
    }

    @Test
    @DisplayName("★ 恰好 10% 不算超标（判据是「严格大于」）")
    void exactlyTenPercentIsNotOver() {
        List<AuditSlaPolicy.DayRate> atThreshold = List.of(day(7, 10, 1), day(8, 10, 1), day(9, 10, 1));
        assertThat(AuditSlaPolicy.tightenRateIfNeeded(5, atThreshold)).isEqualTo(5);
    }

    @Test
    @DisplayName("只有一天没超标 → 不收紧（是「连续」而不是「有过」）")
    void oneGoodDayBlocks() {
        List<AuditSlaPolicy.DayRate> mixed = List.of(day(7, 10, 3), day(8, 10, 0), day(9, 10, 3));
        assertThat(AuditSlaPolicy.tightenRateIfNeeded(5, mixed)).isEqualTo(5);
    }

    @Test
    @DisplayName("★ 只看最近 3 天：更早的坏数据不该永远压着比例")
    void onlyRecentThreeDaysCount() {
        List<AuditSlaPolicy.DayRate> history = List.of(
                day(1, 10, 9), day(2, 10, 9), day(3, 10, 9),   // 远古数据：超标
                day(7, 10, 0), day(8, 10, 0), day(9, 10, 0));  // 最近三天：全绿
        assertThat(AuditSlaPolicy.tightenRateIfNeeded(5, history)).isEqualTo(5);
    }

    @Test
    @DisplayName("抽样数为 0 的日子不占观察期（那天没抽，谈不上违规率）")
    void emptyDaysDoNotCount() {
        List<AuditSlaPolicy.DayRate> withEmpty = List.of(
                day(6, 0, 0), day(7, 10, 3), day(8, 0, 0), day(9, 10, 3));
        // 只剩两天有效样本 → 观察期不够 → 不收紧
        assertThat(AuditSlaPolicy.tightenRateIfNeeded(5, withEmpty)).isEqualTo(5);
    }

    @Test
    @DisplayName("已是加点比例时继续超标 → 再翻倍（10 → 20）")
    void doublingAgain() {
        List<AuditSlaPolicy.DayRate> bad = List.of(day(7, 10, 5), day(8, 10, 5), day(9, 10, 5));
        assertThat(AuditSlaPolicy.tightenRateIfNeeded(10, bad)).isEqualTo(20);
    }

    @Test
    @DisplayName("★ 比例为 0（人工关掉）时超标 → 从基础比例的倍数重新开启，而不是 0×2=0")
    void zeroRateTightensToBaseTimesTwo() {
        List<AuditSlaPolicy.DayRate> bad = List.of(day(7, 10, 5), day(8, 10, 5), day(9, 10, 5));
        assertThat(AuditSlaPolicy.tightenRateIfNeeded(0, bad)).isEqualTo(10);
    }

    @Test
    @DisplayName("★ 封顶 40%：再高就等于回到全量人工审核，那 SLA 本身就失效了")
    void tighteningIsCapped() {
        List<AuditSlaPolicy.DayRate> bad = List.of(day(7, 10, 5), day(8, 10, 5), day(9, 10, 5));
        assertThat(AuditSlaPolicy.tightenRateIfNeeded(20, bad)).isEqualTo(40);
        assertThat(AuditSlaPolicy.tightenRateIfNeeded(40, bad)).isEqualTo(40);
    }

    @Test
    @DisplayName("输入顺序不影响结论：内部按日期取最近 3 天")
    void orderDoesNotMatter() {
        List<AuditSlaPolicy.DayRate> shuffled = List.of(day(9, 10, 4), day(7, 10, 3), day(8, 10, 5));
        assertThat(AuditSlaPolicy.tightenRateIfNeeded(5, shuffled)).isEqualTo(10);
    }

    // ---------------- 单商家加点的回落 ----------------

    @Test
    @DisplayName("连续 3 次全绿 → 加点回落到基础（给误判过的商家一条出路）")
    void threeGreenRelaxes() {
        assertThat(AuditSlaPolicy.shouldRelaxEscalation(List.of(false, false, false))).isTrue();
    }

    @Test
    @DisplayName("★ 只要有一次判违规就不回落 —— 否则「长期盯着问题店」会失效")
    void anyViolationBlocksRelax() {
        assertThat(AuditSlaPolicy.shouldRelaxEscalation(List.of(false, true, false))).isFalse();
        assertThat(AuditSlaPolicy.shouldRelaxEscalation(List.of(true, false, false))).isFalse();
    }

    @Test
    @DisplayName("不足 3 次记录 → 不回落；null 也不回落")
    void notEnoughRecords() {
        assertThat(AuditSlaPolicy.shouldRelaxEscalation(List.of(false, false))).isFalse();
        assertThat(AuditSlaPolicy.shouldRelaxEscalation(null)).isFalse();
        assertThat(AuditSlaPolicy.shouldRelaxEscalation(List.of())).isFalse();
    }

    @Test
    @DisplayName("记录多于 3 条时只看最近 3 条（老违规不该永久钉着这家店）")
    void onlyLatestThreeMatter() {
        assertThat(AuditSlaPolicy.shouldRelaxEscalation(
                List.of(false, false, false, true, true, true))).isTrue();
    }

    // ---------------- 指标换算 ----------------

    @Test
    @DisplayName("违规率：样本为 0 时返回 0（「没抽到」不是「没问题」，但更不能算成违规）")
    void violationPercentOfEmptySample() {
        assertThat(AuditSlaPolicy.violationPercent(0, 0)).isZero();
        assertThat(AuditSlaPolicy.violationPercent(-1, 3)).isZero();
    }

    @Test
    @DisplayName("违规率四舍五入：1/3 → 33%，1/6 → 17%")
    void violationPercentRounds() {
        assertThat(AuditSlaPolicy.violationPercent(3, 1)).isEqualTo(33);
        assertThat(AuditSlaPolicy.violationPercent(6, 1)).isEqualTo(17);
        assertThat(AuditSlaPolicy.violationPercent(50, 1)).isEqualTo(2);
        assertThat(AuditSlaPolicy.violationPercent(10, 0)).isZero();
    }
}
