package com.duoduo.fulfilment.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A-15 · 评价机审（纯规则，可单测）。
 *
 * <p><b>策略：机器只做「放行 / 转人工」，不做自动下架。</b>
 * 干净内容直接 {@code APPROVED}；命中风险的转 {@code PENDING} 交运营裁定。
 * 理由：自动下架是「机器替人做最终裁决」，一旦误判就是买家内容消失、评分被扣，
 * 申诉成本远高于让运营多看一眼；而机器放行干净内容是安全的 ——
 * 运营仍可随时人工下架（H-02）。
 *
 * <p>规则分五类，按「误伤代价从低到高」排序：
 * <ol>
 *   <li>联系方式（手机号 / 座机）—— 站外导流的硬证据；</li>
 *   <li>站外链接 / 域名；</li>
 *   <li>导流话术（加微信 / 私聊 / 扫码加 / QQ 群号）；</li>
 *   <li>广告与违禁品词（刷单 / 返现 / 代开发票等）；</li>
 *   <li>运营配置的敏感词（{@code duoduo.review.sensitive-words}，逗号分隔，可空）。</li>
 * </ol>
 *
 * <p>刻意<b>不</b>把「微信」「二维码」这类裸词当风险：
 * 「用微信支付很方便」「包装上有二维码」是正常评价，误伤会制造大量无意义的待审。
 *
 * <p>归一化：NFKC（全角转半角）+ 去空白 / 零宽字符 + 小写，
 * 防止「加 微 信」「１３８００１３８０００」这类最简单的绕过。
 * 但这不是反作弊系统：真正的对抗（同音字、图片二维码）不在此处解决，
 * 命中与否都只是「转人工」的信号，不产生下架后果。
 */
@Component
public class ReviewRiskJudge {

    /** 审核来源：机审自动。与人工审核（MANUAL）共同写入 t_review.audit_source */
    public static final String SOURCE_AUTO = "AUTO";

    /** audit_reason 列 255 字；留出余量给「机审命中：」前缀与后续追加说明 */
    private static final int MAX_REASON_LENGTH = 200;

    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern MOBILE = Pattern.compile("1[3-9]\\d{9}");
    private static final Pattern LANDLINE = Pattern.compile("0\\d{9,11}");
    private static final Pattern LINK = Pattern.compile(
            "(https?://|www\\.)|[a-z0-9][a-z0-9-]{0,62}\\.(com|cn|net|org|top|xyz|vip|cc|io|tv|shop|site|club)(?![a-z0-9])");
    private static final Pattern QQ = Pattern.compile("qq(群|号)?[:：]?\\d{5,11}");

    private static final List<String> DIVERSION_WORDS = List.of(
            "加微信", "微信号", "加v信", "加vx", "vx号", "vx：", "加v", "私聊",
            "扫码加", "扫码领", "qq群", "qq号", "加我好友");
    private static final List<String> AD_WORDS = List.of(
            "刷单", "返现", "好评返", "兼职", "代购", "代拍", "全网最低", "低价出", "加v领");
    private static final List<String> PROHIBITED_WORDS = List.of(
            "枪支", "毒品", "迷药", "代开发票", "办假证", "野生动物");

    private final List<String> sensitiveWords;

    public ReviewRiskJudge(@Value("${duoduo.review.sensitive-words:}") String configuredWords) {
        this.sensitiveWords = parseWords(configuredWords);
    }

    /**
     * 判定一段或多段评价文本（正文 / 追评）。
     *
     * @return 命中第一类风险即返回，便于运营看到最直接的依据；全干净返回 {@link Verdict#clean()}
     */
    public Verdict judge(String... texts) {
        if (texts == null) {
            return Verdict.clean();
        }
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            String normalized = normalize(text);
            if (normalized.isEmpty()) {
                continue;
            }
            Verdict verdict = inspect(normalized);
            if (verdict.risky()) {
                return verdict;
            }
        }
        return Verdict.clean();
    }

    private Verdict inspect(String text) {
        Matcher digits = DIGITS.matcher(text);
        while (digits.find()) {
            String run = digits.group();
            if (MOBILE.matcher(run).matches()) {
                return Verdict.risky("机审命中：联系方式（手机号 " + mask(run) + "）");
            }
            if (LANDLINE.matcher(run).matches()) {
                return Verdict.risky("机审命中：联系方式（座机 " + mask(run) + "）");
            }
        }
        if (LINK.matcher(text).find()) {
            return Verdict.risky("机审命中：站外链接");
        }
        if (QQ.matcher(text).find()) {
            return Verdict.risky("机审命中：站外导流（QQ 号）");
        }
        for (String word : DIVERSION_WORDS) {
            if (text.contains(word)) {
                return Verdict.risky("机审命中：站外导流（关键词：" + word + "）");
            }
        }
        for (String word : AD_WORDS) {
            if (text.contains(word)) {
                return Verdict.risky("机审命中：广告话术（关键词：" + word + "）");
            }
        }
        for (String word : PROHIBITED_WORDS) {
            if (text.contains(word)) {
                return Verdict.risky("机审命中：违禁品（关键词：" + word + "）");
            }
        }
        for (String word : sensitiveWords) {
            if (text.contains(word)) {
                return Verdict.risky("机审命中：敏感词（" + word + "）");
            }
        }
        return Verdict.clean();
    }

    /** 手机号保留前 3 后 4、座机保留前 3 后 2：审核原因要能定位，但不把完整号码再抄一遍 */
    private static String mask(String digits) {
        if (digits.length() <= 7) {
            return digits.charAt(0) + "****" + digits.charAt(digits.length() - 1);
        }
        int tail = digits.length() >= 11 ? 4 : 2;
        return digits.substring(0, 3) + "****" + digits.substring(digits.length() - tail);
    }

    private static String normalize(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (Character.isWhitespace(c) || c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\uFEFF') {
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** 词表按中英文逗号 / 顿号 / 分号 / 空白切分，去重且忽略空项（运维在 env 里手写时常混用分隔符） */
    private static List<String> parseWords(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> words = new LinkedHashSet<>();
        for (String part : raw.split("[,，、;；\\s]+")) {
            String word = normalize(part);
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        return new ArrayList<>(words);
    }

    /** 机审结论：risky=true 时 reason 必定非空，可直接写入 audit_reason */
    public record Verdict(boolean risky, String reason) {

        public static Verdict clean() {
            return new Verdict(false, null);
        }

        public static Verdict risky(String reason) {
            String value = reason == null ? "机审命中风险内容" : reason.trim();
            if (value.length() > MAX_REASON_LENGTH) {
                value = value.substring(0, MAX_REASON_LENGTH);
            }
            return new Verdict(true, value);
        }
    }
}
