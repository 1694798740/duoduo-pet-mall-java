package com.duoduo.ai.support;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 约束解析（第五十七轮 · M1）：把「预算 200 以内」这类话变成**可下推给数据库的条件**。
 *
 * <h2>为什么以规则为主，而不是交给大模型抽</h2>
 * 预算过滤是「错了用户一眼就能发现」的功能。若解析依赖 LLM，它就多了一个
 * 「模型今天不听话」的失败形态 —— 而这类失败**看起来像功能正常**（对话照样有回复，
 * 只是推荐里混进了超预算的货）。规则解析的价值不在于更准，而在于**失败方式明确**：
 * 抽不到就是抽不到，可以立刻告诉用户「我没听懂你的预算」。
 *
 * <p>行业做法也支持这个取舍：阿里云官方导购 Agent 让模型输出**限定枚举**再做后续处理，
 * 而不是让模型自由理解后直接执行。确定性交给代码，语言理解才是模型的事。
 *
 * <h2>三个必须区分的状态（这是本类设计的核心）</h2>
 * <ol>
 *   <li>{@code budgetMax} 有值 —— 用户明确说了上限，**按它过滤**；</li>
 *   <li>{@code budgetMax} 为 null 且 {@code budgetUnparsed = false} —— 用户根本没提预算，
 *       **不过滤**（这是正常情况，不是缺陷）；</li>
 *   <li>{@code budgetMax} 为 null 且 {@code budgetUnparsed = true} —— 用户**提了**预算但没解析出来
 *       （「便宜点就行」「两百来块」），此时**必须如实告知**，绝不能当成「没提预算」——
 *       那等于把「我以为我设了预算」变成静默失效。</li>
 * </ol>
 * 状态 3 是最容易被漏掉的一条：只看「解析结果为空」就把三种情况混成一种，
 * 用户会永远不知道自己那句话没生效。
 */
@Component
public class ConstraintParser {

    /**
     * 预算上限的表达：「200 元以内 / 不超过 200 / 预算 200 / 200 以下 / 最多 200 块 / 低于 200」。
     *
     * <p>数字允许小数（「199.9 元以内」）。
     */
    private static final Pattern BUDGET_MAX = Pattern.compile(
            "(?:预算|不超过|最多|至多|低于|少于|控制在|封顶)\\s*(\\d+(?:\\.\\d+)?)\\s*(?:元|块|rmb|RMB|￥|¥)?"
                    + "|(\\d+(?:\\.\\d+)?)\\s*(?:元|块|rmb|RMB|￥|¥)?\\s*(?:以内|以下|之内|内|封顶)");

    /** 预算下限：「200 元以上 / 超过 200 / 至少 200」 */
    private static final Pattern BUDGET_MIN = Pattern.compile(
            "(?:预算|至少|不低于|高于|超过|起步)\\s*(\\d+(?:\\.\\d+)?)\\s*(?:元|块|rmb|RMB|￥|¥)?"
                    + "|(\\d+(?:\\.\\d+)?)\\s*(?:元|块|rmb|RMB|￥|¥)?\\s*(?:以上|往上|出头)");

    /** 区间：「100-300 元」「100 到 300」 */
    private static final Pattern BUDGET_RANGE = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(?:元|块)?\\s*(?:-|~|—|到|至)\\s*(\\d+(?:\\.\\d+)?)\\s*(?:元|块)");

    /**
     * 「提了预算」的信号词：这些词出现但没有解析出数字 → 属于状态 3。
     *
     * <p>「便宜」带了负向后视 {@code (?<!最)}：「那最便宜的是哪个？」问的是**候选里最便宜的商品**，
     * 不是在表达预算 —— 不加这个限定，每一句「最便宜」都会被打上「我没听清你的预算」的前缀，
     * 把一句正常追问误判成「用户提了预算但没听懂」。
     */
    private static final Pattern BUDGET_MENTIONED = Pattern.compile(
            "预算|(?<!最)便宜|价格|价位|多少钱|元|块|划算");

    /** 预算的合理区间（元）：低于 1 元或高于 100 万元基本是误解析（可能是年份、数量） */
    private static final java.math.BigDecimal MIN_SANE_YUAN = java.math.BigDecimal.ONE;
    private static final java.math.BigDecimal MAX_SANE_YUAN = new java.math.BigDecimal("1000000");

    /**
     * 约束解析结果。
     *
     * @param budgetMin      预算下限（分）；null = 无
     * @param budgetMax      预算上限（分）；null = 无
     * @param categoryIds    命中的类目 id（可能多个）；空 = 无品类约束
     * @param categoryNames  命中的类目名（用于话术与日志，不必再查一次）
     * @param budgetUnparsed 用户**提了**预算但没解析出来 → 必须如实告知
     */
    public record Constraints(
            Long budgetMin,
            Long budgetMax,
            List<Long> categoryIds,
            List<String> categoryNames,
            boolean budgetUnparsed
    ) {
        public static Constraints empty() {
            return new Constraints(null, null, List.of(), List.of(), false);
        }

        public boolean hasBudget() {
            return budgetMin != null || budgetMax != null;
        }
    }

    private final CategoryCatalog categoryCatalog;

    public ConstraintParser(CategoryCatalog categoryCatalog) {
        this.categoryCatalog = categoryCatalog;
    }

    /**
     * 解析一句用户话。
     *
     * <p>区间优先于单向：用户写「100-300 元」时，BUDGET_MAX 的单边正则也可能命中
     * 「300 元」（若带了「以内」），所以先看区间，命中就跳过后两个。
     */
    public Constraints parse(String message) {
        String text = message == null ? "" : message;
        List<com.duoduo.ai.client.dto.CategoryBrief> matched = categoryCatalog.match(text);
        List<Long> categoryIds = matched.stream().map(c -> c.id()).toList();
        List<String> categoryNames = matched.stream().map(c -> c.name()).toList();

        Long min = null;
        Long max = null;
        boolean rangeHit = false;

        Matcher range = BUDGET_RANGE.matcher(text);
        if (range.find()) {
            Long lo = toCents(range.group(1));
            Long hi = toCents(range.group(2));
            if (lo != null && hi != null && lo <= hi) {
                min = lo;
                max = hi;
                rangeHit = true;
            }
        }
        if (!rangeHit) {
            // 上限先匹配，并记下它命中的文本区间；下限匹配时**跳过与上限重叠的命中**。
            //
            // 为什么必须做区间去重：「预算 200 元以内」里，BUDGET_MAX 和 BUDGET_MIN 的
            // 第一条分支都能命中「预算 200」—— 若不去重，min 和 max 会同时等于 200，
            // 于是「99~159 元」的商品被 max_price >= 200 误杀：用户说「200 以内」，
            // 最该推荐的便宜货反而消失，且无任何报错。重叠命中是同一个数字被两套
            // 正则各数了一次，不是用户真给了上下限。
            Matcher maxMatcher = BUDGET_MAX.matcher(text);
            String maxStr = null;
            int maxStart = -1;
            int maxEnd = -1;
            if (maxMatcher.find()) {
                maxStr = firstGroup(maxMatcher);
                maxStart = maxMatcher.start();
                maxEnd = maxMatcher.end();
            }
            Matcher minMatcher = BUDGET_MIN.matcher(text);
            String minStr = null;
            while (minStr == null && minMatcher.find()) {
                boolean overlapsMax = maxStart >= 0
                        && minMatcher.start() < maxEnd && minMatcher.end() > maxStart;
                if (!overlapsMax) {
                    minStr = firstGroup(minMatcher);
                }
            }
            max = toCents(maxStr);
            min = toCents(minStr);
            // 若只命中 MIN 而没命中 MAX，说明用户说的是下限，语义正确。
            if (min != null && max != null && min > max) {
                // 两个方向都抽到了但矛盾（「至少 300，不超过 200」）：以更严的上限为准，
                // 并把下限丢掉 —— 空集合比错误集合好，交给上层如实说明
                min = null;
            }
        }
        boolean unparsed = !rangeHit && min == null && max == null && BUDGET_MENTIONED.matcher(text).find();
        return new Constraints(min, max, categoryIds, categoryNames, unparsed);
    }

    /** 取正则里第一个非空的捕获组（两条分支写法共用；调用方已 find() 过，这里不再推进） */
    private static String firstGroup(Matcher m) {
        for (int i = 1; i <= m.groupCount(); i++) {
            if (m.group(i) != null && !m.group(i).isBlank()) {
                return m.group(i);
            }
        }
        return null;
    }

    /**
     * 「元」→「分」，并做合理性校验。
     *
     * <p>越界（0 元、10 万元）一律当没解析出来：这些数字更可能是年份、数量、规格，
     * 拿它们当预算过滤会把候选池清空，而用户会看到「没找到匹配的商品」——
     * 一个由解析错误伪装成的业务结果。
     */
    private static Long toCents(String yuan) {
        if (yuan == null || yuan.isBlank()) {
            return null;
        }
        try {
            java.math.BigDecimal value = new java.math.BigDecimal(yuan.trim());
            if (value.compareTo(MIN_SANE_YUAN) < 0 || value.compareTo(MAX_SANE_YUAN) > 0) {
                return null;
            }
            return value.multiply(java.math.BigDecimal.valueOf(100)).longValueExact();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
