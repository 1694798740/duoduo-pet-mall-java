package com.duoduo.ai.support;

import com.duoduo.ai.client.dto.ProductCandidate;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 输出护栏（第五十七轮 · M4）：模型回复在给用户看之前过一遍。
 *
 * <h2>挡什么
 * <ol>
 *   <li><b>绝对化 / 承诺类用语</b>：「绝对有效」「保证最便宜」「100% 无副作用」这类话
 *       在电商文案里直接对应广告法风险（也用不着真违法 —— 平台自己就不该这么说话）；</li>
 *   <li><b>价格与事实不一致</b>：模型正文里说的价，必须能在候选商品里找到。
 *       现实中最常见的形态是「正文说 199、卡片上是 328」——
 *       正文和卡片互相打脸，用户不知道该信哪个。</li>
 * </ol>
 *
 * <h2>命中后怎么办：替换成「事实版回复」，而不是拒绝回答
 * 行业文献里明确写了护栏的代价是 over-refusal（过度拒绝）——用户问一件正常的事，
 * 却收到一句「我不能回答这个问题」，体验极差且毫无必要。所以这里的处理是：
 * <b>丢弃模型那段话术，改用它依据的候选事实重新组织一句客观陈述</b>
 * （{@link #factsReply}）。用户仍然拿到有用的回答、价格全是真实数字，
 * 只是不再有夸张措辞。既挡住了风险，也不用对用户说「不」。
 *
 * <h2>可观测
 * 每次拦截都记 WARN 日志（稳定前缀便于 grep）+ 计数器（{@code /actuator/metrics} 可见）。
 * <b>护栏必须可度量</b>：没有计数就没法回答「最近护栏到底拦了多少、拦的是什么」，
 * 也就无法判断是模型变差了还是护栏过严了 —— 而后者会静默地把可用性磨掉。
 */
@Component
public class ReplyGuard {

    private static final Logger log = LoggerFactory.getLogger(ReplyGuard.class);

    /** 日志前缀：稳定字符串，便于日志系统按它做告警与统计 */
    public static final String LOG_PREFIX = "AI护栏拦截";

    /**
     * 绝对化 / 承诺类词表。
     *
     * <p>判据是「这句话在电商文案里会被平台/监管认定为绝对化或承诺性表述」。
     * 刻意**不含**「最好/最适合你」这类主观推荐语 —— 那在导购场景里是正常表达，
     * 拦掉它们会让 AI 说话像机器人（over-refusal 的另一种形态）。
     */
    private static final List<String> RISKY_PHRASES = List.of(
            "绝对", "保证", "100%", "百分之百", "百分百",
            "最低价", "全网最", "全城最", "史上最", "最便宜", "永不",
            "包退包换", "无效退款", "假一赔十", "包治", "根治", "治愈", "治疗", "药到病除"
    );

    /** 「¥199」「199 元」「199块」：只认带货币信号的数字，避免把「2 岁」「3 袋」当价格 */
    private static final Pattern PRICE_TEXT = Pattern.compile(
            "[¥￥]\\s*(\\d+(?:\\.\\d+)?)|(\\d+(?:\\.\\d+)?)\\s*(?:元|块钱|块)");

    private final Map<String, AtomicLong> hits = new ConcurrentHashMap<>();
    private final MeterRegistry meterRegistry;

    public ReplyGuard(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        // 用 ObjectProvider：没有注册表实现时（例如裁掉了 micrometer）也不该启动失败 ——
        // 护栏是安全能力，不能因为「监控不在了」就整个服务起不来。
        // provider 本身为 null（纯单测环境）同样容忍 —— 护栏的行为不依赖监控的存在。
        this.meterRegistry = meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();
    }

    /**
     * 检查结果。
     *
     * @param blocked  是否被拦截
     * @param reason   拦截原因（用于日志与计数标签）
     * @param reply    最终应该发给用户的回复：未拦截时是原回复，拦截时是事实版回复
     */
    public record Verdict(boolean blocked, String reason, String reply) {
    }

    /**
     * 过一遍护栏。
     *
     * <p>检查顺序：合规词优先（它更严重），再查价格一致性。两个都命中时只报第一个 ——
     * 一次拦截只需要一个理由，堆两条原因反而让日志难读。
     */
    public Verdict inspect(String reply, List<ProductCandidate> candidates) {
        String text = reply == null ? "" : reply;
        List<ProductCandidate> list = candidates == null ? List.of() : candidates;

        String risky = firstRiskyPhrase(text);
        if (risky != null) {
            record("compliance", risky);
            return new Verdict(true, "合规词：" + risky, factsReply(list));
        }

        Set<String> allowed = candidatePriceTexts(list);
        List<String> offending = unmatchedPrices(text, allowed);
        if (!offending.isEmpty()) {
            record("price-mismatch", offending.get(0));
            return new Verdict(true, "价格不一致：" + String.join("/", offending), factsReply(list));
        }
        return new Verdict(false, null, text);
    }

    /** 首个命中的违规词；没有则 null */
    private static String firstRiskyPhrase(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String phrase : RISKY_PHRASES) {
            if (text.contains(phrase) || lower.contains(phrase.toLowerCase(Locale.ROOT))) {
                return phrase;
            }
        }
        return null;
    }

    /**
     * 候选里出现过的价格文本（元，去掉无意义的「.0」）。
     *
     * <p>把 minPrice 与 maxPrice 都放进来：卡片上展示的是「N 元起」，
     * 但模型也可能引用区间上限。
     */
    private static Set<String> candidatePriceTexts(List<ProductCandidate> candidates) {
        Set<String> set = new LinkedHashSet<>();
        for (ProductCandidate c : candidates) {
            set.add(toYuanText(c.minPrice()));
            set.add(toYuanText(c.maxPrice()));
        }
        set.remove("");
        return set;
    }

    /** 分 → 元的展示文本，与 {@link #factsReply} 保持一致（避免「199.0」和「199」判成两个价） */
    private static String toYuanText(long cents) {
        long yuan = cents / 100;
        long rest = Math.abs(cents % 100);
        if (rest == 0) {
            return String.valueOf(yuan);
        }
        return yuan + "." + String.format("%02d", rest);
    }

    /**
     * 找出回复里「不是任何一个候选价格」的金额。
     *
     * <p>只认带 ¥/元/块 的数字：模型说「2 岁」「3 袋」不该被当成价格，
     * 把普通数字一律当价格会让护栏天天误报。
     */
    private static List<String> unmatchedPrices(String text, Set<String> allowed) {
        List<String> offending = new ArrayList<>();
        Matcher m = PRICE_TEXT.matcher(text);
        while (m.find()) {
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String normalized = raw.endsWith(".0") ? raw.substring(0, raw.length() - 2) : raw;
            if (!allowed.contains(normalized) && !allowed.contains(raw)) {
                offending.add(raw);
            }
        }
        return offending;
    }

    /**
     * 事实版回复：只用候选里的客观事实说话（前 3 条）。
     *
     * <p>这个方法同时也是 **mock 模式的回复生成器**（{@code ChatService} 复用它）——
     * 两条路径共用一处，就不会出现「mock 说的价」和「护栏回退说的价」不一致的诡异情况。
     */
    public static String factsReply(List<ProductCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "暂时没找到匹配的商品。换个说法试试，比如「幼犬狗粮」「猫零食」？";
        }
        StringBuilder sb = new StringBuilder("为你找到 " + candidates.size() + " 款相关商品：");
        int top = Math.min(3, candidates.size());
        for (int i = 0; i < top; i++) {
            ProductCandidate c = candidates.get(i);
            sb.append("「").append(c.title()).append("」").append(toYuanText(c.minPrice())).append("元起");
            if (!c.inStock()) {
                sb.append("（暂时缺货）");
            }
            sb.append("；");
        }
        sb.append("如需下单，请登录后在商品页加购。");
        return sb.toString();
    }

    /** 记日志 + 计数器；计数器标签分 reason，便于分别观察「合规」与「价格」两类 */
    private void record(String reason, String detail) {
        hits.computeIfAbsent(reason, k -> new AtomicLong()).incrementAndGet();
        log.warn("{} reason={} detail={}", LOG_PREFIX, reason, detail);
        if (meterRegistry != null) {
            try {
                meterRegistry.counter("duoduo.ai.guard.blocked", "reason", reason).increment();
            } catch (RuntimeException e) {
                // 监控不该影响主流程：计数失败就算了，日志已经留痕
                log.debug("护栏计数失败: {}", e.getMessage());
            }
        }
    }

    /** 各原因的拦截次数（供探针与排障读取） */
    public Map<String, Long> stats() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, AtomicLong> e : hits.entrySet()) {
            out.put(e.getKey(), e.getValue().get());
        }
        return out;
    }

    /** 违规词表规模（供测试断言「词表不是空的」） */
    public int riskyPhraseCount() {
        return RISKY_PHRASES.size();
    }
}
