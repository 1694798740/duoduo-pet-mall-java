package com.duoduo.common.audit;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * 审核 SLA 的**规则唯一出处**（E28）。
 *
 * <h2>为什么是一个纯函数类，而不是散在各服务里</h2>
 * 「抽哪几条」「抽几条」「什么时候自动收紧」这三件事，必须<b>可复现、可解释、可单测</b>。
 * 一旦它们散落在任务类、服务类、Controller 里，就会出现三种必然的坏结果：
 * <ul>
 *   <li>同一件商品在商品库里算作「被抽中」、在运营看到的队列里却不在（两处各算一遍）；</li>
 *   <li>「昨天抽了几条」无法回答 —— 因为规则依赖运行时的随机数，跑第二遍就是另一批；</li>
 *   <li>收紧逻辑无法验证 —— 想测「连续 3 天超标会翻倍」得先造 3 天的历史数据，
 *       而不是喂三个数进去看结果。</li>
 * </ul>
 * 因此这里只有静态方法、只有入参出参，没有任何 Spring 注释、不碰数据库。
 * 商品域与用户域**共用这一份**：两边抽的虽然是不一样的东西，但「怎么抽」必须是同一套规则。
 *
 * <h2>取样为什么用「哈希取模」而不是随机数</h2>
 * 用 {@code Random} 等于把「今天抽到了谁」变成不可复现的 —— 商家只要反复刷新、或者
 * 盯着运营的抽查节奏，就能推断出「什么时段发布不会被抽中」。用
 * {@code hash(主键 + 日期)} 取模有三条好处：
 * <ol>
 *   <li><b>同一天、同一批数据，算出来永远是同一批</b>（重跑任务不会换人，手动触发与
 *       定时触发结果一致，运营不会看到队列里突然多出一批一模一样的东西）；</li>
 *   <li><b>与「第几个发布」无关</b> —— 不是「每天前 N 件」，所以卡发布时间规避不了；</li>
 *   <li><b>相邻批次有连续性但不完全重合</b>：5% 与 10% 的取样集合是**包含关系**
 *       （比例提高时，原本被抽中的仍然在，只是多抽了一些），
 *       这让「收紧之后重新抽」不会把上次的复核结论作废。</li>
 * </ol>
 *
 * <h2>三条常量口径</h2>
 * <ul>
 *   <li>{@link #MIN_SAMPLE_PER_DAY} / {@link #MAX_SAMPLE_PER_DAY}：样本太小时
 *       「5%」等于没抽（放行 10 件抽 0.5 件），所以设下限；上限则防止某天集中放行时
 *       把运营一次性淹没（抽查的价值在发现违规，不在制造工作量）。</li>
 *   <li>{@link #TINY_DAY_THRESHOLD}：当日放行量不足 3 件就**全查** ——
 *       不是「至少 3 件」的意思，而是「少到不值得抽样」。</li>
 *   <li>{@link #TIGHTEN_VIOLATION_PERCENT} + {@link #TIGHTEN_DAYS}：抽中违规率连续
 *       3 天超过 10% 就翻倍。用「连续 3 天」而不是「单日超标」是因为单日样本可能只有
 *       3 条，其中 1 条违规就是 33%，一天波动不该触发全站加码。</li>
 * </ul>
 */
public final class AuditSlaPolicy {

    /** 基础抽样比例（%）。与 t_audit_sample_rate 的初始值一致 —— 两处必须同时改。 */
    public static final int BASE_RATE_PERCENT = 5;

    /** 每日最少抽几条（样本太小时「5%」等于没抽） */
    public static final int MIN_SAMPLE_PER_DAY = 3;

    /** 每日最多抽几条（避免把运营一次性淹没） */
    public static final int MAX_SAMPLE_PER_DAY = 20;

    /** 当日放行量低于此值 → 全查（少到不值得抽样） */
    public static final int TINY_DAY_THRESHOLD = 3;

    /** 加点抽取比例：有驳回 / 停业记录的商家 */
    public static final int ESCALATED_RATE_PERCENT = 20;

    /** 连续 3 次抽查全绿 → 从加点比例回落到基础比例 */
    public static final int GREEN_STREAK_TO_RELAX = 3;

    /** 收紧阈值：抽中违规率（%） */
    public static final int TIGHTEN_VIOLATION_PERCENT = 10;

    /** 收紧需要连续超标的天数 */
    public static final int TIGHTEN_DAYS = 3;

    /** 抽样比例上限（翻倍不能无限翻，否则等于回到「全部人工审核」） */
    public static final int MAX_RATE_PERCENT = 40;

    /** 误判率（复核判通过的比例）阈值：超过它说明自动放行规则太严，该放宽而不是收紧 */
    public static final int FALSE_POSITIVE_PERCENT = 20;

    private AuditSlaPolicy() {
    }

    /**
     * 抽中理由。存进 {@code t_audit_sample.reason}，让运营在队列里一眼看出
     * 「这条是随机抽到的」还是「本来就该重点盯的」—— 复核优先级由此可排。
     */
    public enum Reason {
        /** 基础比例抽中 */
        BASE("基础抽查"),
        /** 当日放行量不足 {@link #TINY_DAY_THRESHOLD} 件 → 全查 */
        TINY_ALL("当日放行量少·全查"),
        /** 新商家的前 3 件商品（必抽 100%） */
        NEW_SELLER("新商家前 3 件"),
        /** 恢复营业后 7 天内发布的商品（必抽 100%） */
        RESUME("恢复营业 7 天内"),
        /** 有驳回 / 停业记录的商家（加点抽取） */
        ESCALATED("重点商家");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 是否抽中。{@code ratePercent} 为 0 时永不抽中（便于关掉抽查），100 时全抽。
     *
     * <p>哈希的输入是「主键 + 日期」，用 murmur3 的收尾混合（finalizer）把低位打散 ——
     * 直接用 {@code id % 100} 会露出规律：主键是雪花 id，末几位近似递增，
     * 于是「同一批发布里每隔 20 个抽 1 个」就成了可预测的节奏。
     */
    public static boolean shouldSample(long bizId, LocalDate date, int ratePercent) {
        if (ratePercent <= 0) {
            return false;
        }
        if (ratePercent >= 100) {
            return true;
        }
        return hashBucket(bizId, date) < ratePercent;
    }

    /**
     * 把「主键 + 日期」映射到 {@code [0, 100)} 的一个桶号。
     *
     * <p>为什么对外暴露桶号而不是只给「中没中」：每天的抽样要满足
     * 「至少 N 条、最多 M 条」，这就要求先**全量算一遍桶号、按桶号升序排序、取前 K 条**，
     * 而不是顺次问每一条「你中了吗」。两者是同一套哈希 ——
     * 排序取前 K 的集合，恰好等于「桶号 < K 的那些条」，
     * 所以「单条判定」与「按天取 K 条」永远不会打架。
     *
     * <p>用 murmur3 的收尾混合（finalizer）把低位打散：直接用 {@code id % 100} 会露出规律 ——
     * 主键是雪花 id，末几位近似递增，于是「同一批发布里每隔 20 个抽 1 个」就成了可预测的节奏。
     */
    public static int hashBucket(long bizId, LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("抽样必须指定日期：没有日期就没有幂等键");
        }
        long mixed = mix(bizId ^ (date.toEpochDay() * 0x9E3779B97F4A7C15L));
        return (int) Math.floorMod(mixed, 100);
    }

    /** murmur3 的收尾混合：确定性、跨实例一致、低位被打散 */
    private static long mix(long z) {
        z = (z ^ (z >>> 33)) * 0xFF51AFD7ED558CCDL;
        z = (z ^ (z >>> 33)) * 0xC4CEB9FE1A85EC53L;
        return z ^ (z >>> 33);
    }

    /**
     * 当天应当抽几条（目标值，不是保证值）。
     *
     * <p>为什么不直接用 {@code ceil(放行量 × 比例)}：那样在放行量小的日子会算出 0 或 1，
     * 「每天至少 3 件」就落不了地；在放行量大的日子又会算出几十件，
     * 运营一天别的都别干了。所以先按比例算，再夹到 [MIN, MAX] 之间。
     */
    public static int targetSampleCount(int releasedCount, int ratePercent) {
        if (releasedCount <= 0) {
            return 0;
        }
        // 比例为 0 = 抽查制度被关闭（如平台主动暂停）。此时目标必须是 0：
        // 若仍返回 MIN_SAMPLE_PER_DAY，日志与指标会出现「比例 0%、目标 3 条」这种自相矛盾的数字，
        // 排查时最容易得出的错误结论是「关了但没生效」。
        if (ratePercent <= 0) {
            return 0;
        }
        if (releasedCount < TINY_DAY_THRESHOLD) {
            return releasedCount;
        }
        long byRate = (releasedCount * (long) ratePercent + 99) / 100;   // 向上取整
        int clamped = (int) Math.max(MIN_SAMPLE_PER_DAY, Math.min(MAX_SAMPLE_PER_DAY, byRate));
        return Math.min(clamped, releasedCount);
    }

    /**
     * 当天的抽中理由：放行量少 → 全查，否则是基础比例。
     *
     * <p>必抽（新商家 / 恢复营业 / 重点商家）不在这里判 —— 那三条依赖每一条记录自身的
     * 属性（是不是前 3 件、是不是刚复业、商家有没有污点），由任务逐条判定后覆盖本结果。
     */
    public static Reason baseReason(int releasedCount) {
        return releasedCount < TINY_DAY_THRESHOLD ? Reason.TINY_ALL : Reason.BASE;
    }

    /** 某一天的抽样结果，用于判断要不要收紧 */
    public record DayRate(LocalDate date, int sampled, int violated) {
    }

    /**
     * 按「抽中违规率」决定要不要自动收紧。
     *
     * @param currentRate 当前抽样比例（%）
     * @param recentDays  近若干天的抽样结果（顺序不限，内部会按日期取最近 {@link #TIGHTEN_DAYS} 天）
     * @return 新的抽样比例；不需要收紧时原样返回 {@code currentRate}
     *
     * <p><b>只紧不松</b>：回落不在这里做。理由是回落需要一个「已经好了多久」的观察期，
     * 而「放松」这件事一旦做错（在违规高峰把手松开），代价是不可逆的。
     * 收紧最多到 {@link #MAX_RATE_PERCENT}：再高就等于回到全量人工审核，
     * 那样 SLA 本身就失效了 —— 真到那一步，该做的是把自动放行规则改严，而不是继续加抽样。
     */
    public static int tightenRateIfNeeded(int currentRate, List<DayRate> recentDays) {
        if (recentDays == null || recentDays.isEmpty()) {
            return currentRate;
        }
        List<DayRate> last = recentDays.stream()
                .filter(d -> d.sampled() > 0)
                .sorted(Comparator.comparing(DayRate::date).reversed())
                .limit(TIGHTEN_DAYS)
                .toList();
        if (last.size() < TIGHTEN_DAYS) {
            return currentRate;   // 观察期不够，不轻易加码
        }
        boolean allOver = last.stream().allMatch(d -> d.violated() * 100 > TIGHTEN_VIOLATION_PERCENT * d.sampled());
        if (!allOver) {
            return currentRate;
        }
        return Math.min(MAX_RATE_PERCENT, Math.max(currentRate, BASE_RATE_PERCENT) * 2);
    }

    /**
     * 加重抽取是否该回落（连续 {@link #GREEN_STREAK_TO_RELAX} 次抽查全绿）。
     *
     * <p>与 {@link #tightenRateIfNeeded} 分开：那个调的是**全局比例**，这个调的是
     * **单个商家的加点倍数**。前者影响面大、只紧不松；后者只影响一家店，
     * 给它一条恢复正常的路径是合理的（否则一次误判会把这家店永久盯死）。
     *
     * @param recentResults 该商家最近若干次抽查的结论（true = 判违规），按时间倒序或正序均可
     */
    public static boolean shouldRelaxEscalation(List<Boolean> recentResults) {
        if (recentResults == null || recentResults.size() < GREEN_STREAK_TO_RELAX) {
            return false;
        }
        return recentResults.stream()
                .limit(GREEN_STREAK_TO_RELAX)
                .noneMatch(Boolean::booleanValue);
    }

    /** 抽中违规率（%）；样本为 0 时返回 0（「没抽到」不等于「没问题」，但也不能算成违规） */
    public static int violationPercent(int sampled, int violated) {
        if (sampled <= 0) {
            return 0;
        }
        return (int) Math.round(violated * 100.0 / sampled);
    }
}
