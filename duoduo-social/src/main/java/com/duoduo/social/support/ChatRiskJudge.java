package com.duoduo.social.support;

import java.text.Normalizer;
import java.util.Collection;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 聊天内容风险判定（C-05，纯函数）。
 *
 * <h2>为什么与评价机审分开</h2>
 * 评价是公开内容，命中后转人工审核；聊天是实时对话，命中后必须**当场决定发不发**。
 * 两套处置语义不同，共用一份规则会让「评价转人工」和「消息发不出去」互相牵制。
 *
 * <h2>四类风险</h2>
 * <ul>
 *   <li>{@link Category#CONTACT} 联系方式（手机号 / QQ / 微信号）—— 默认**只告警不拦截**：
 *       售后沟通里交换电话是正常需求，一刀切会误伤；可配置成拦截。</li>
 *   <li>{@link Category#LINK} 站外链接 / 域名 —— 默认拦截；</li>
 *   <li>{@link Category#FRAUD} 资金诈骗词（转账 / 验证码 / 刷单 / 返利 / 套现…）—— 默认拦截；</li>
 *   <li>{@link Category#SENSITIVE} 运营配置的敏感词 —— 默认拦截。</li>
 * </ul>
 *
 * <p>归一化用 NFKC + 去空白 / 零宽 + 小写，挡「加 微 信」「１３８…」这类最省事的绕过；
 * 但**不会**把裸词「微信」「二维码」当风险 —— 「用微信支付很方便」是正常聊天。
 */
public final class ChatRiskJudge {

    public enum Category {
        CONTACT("联系方式"),
        LINK("站外链接"),
        FRAUD("资金/诈骗风险"),
        SENSITIVE("敏感词");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Verdict(boolean hit, Category category, String reason, String masked) {
        public static Verdict clean() {
            return new Verdict(false, null, null, null);
        }
    }

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern QQ = Pattern.compile("(?:qq|扣扣)(?:号)?[:：]?\\d{5,12}");
    private static final Pattern WECHAT = Pattern.compile("(?:加|添加)?(?:微信|vx|v信|weixin)(?:号)?[:：]?[a-z0-9_-]{5,20}");
    private static final Pattern LINK = Pattern.compile(
            "(?:https?://|www\\.)[a-z0-9./?=&_%+#-]+"
                    + "|[a-z0-9-]+\\.(?:com|cn|net|top|xyz|shop|vip|cc|io)(?![a-z0-9-])");
    private static final Pattern FRAUD = Pattern.compile(
            "转账|汇款|银行卡|验证码|刷单|返利|套现|保证金|安全账户|解冻|洗钱|博彩|代开发票|枪支|毒品|色情");

    private ChatRiskJudge() {
    }

    /**
     * 判定一段聊天内容。
     *
     * @param raw           原文（保留用于脱敏展示）
     * @param sensitiveWords 运营配置的敏感词（已归一化前）
     */
    public static Verdict judge(String raw, Collection<String> sensitiveWords) {
        if (raw == null || raw.isBlank()) {
            return Verdict.clean();
        }
        String normalized = normalize(raw);

        if (PHONE.matcher(normalized).find()) {
            return new Verdict(true, Category.CONTACT, Category.CONTACT.label(), maskPhone(raw));
        }
        if (QQ.matcher(normalized).find() || WECHAT.matcher(normalized).find()) {
            return new Verdict(true, Category.CONTACT, Category.CONTACT.label(), "联系方式已隐藏");
        }
        if (LINK.matcher(normalized).find()) {
            return new Verdict(true, Category.LINK, Category.LINK.label(), "[链接已隐藏]");
        }
        if (FRAUD.matcher(normalized).find()) {
            return new Verdict(true, Category.FRAUD, Category.FRAUD.label(), "风险词已隐藏");
        }
        if (sensitiveWords != null) {
            for (String word : sensitiveWords) {
                String w = normalize(word);
                if (!w.isEmpty() && normalized.contains(w)) {
                    return new Verdict(true, Category.SENSITIVE, Category.SENSITIVE.label(),
                            w.charAt(0) + "***");
                }
            }
        }
        return Verdict.clean();
    }

    /** NFKC + 去空白 / 零宽 + 小写（保留点、连字符：链接与微信号需要它们） */
    static String normalize(String raw) {
        String n = Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return n.replaceAll("[\\s\\u00A0\\u200B-\\u200D\\uFEFF]", "");
    }

    private static String maskPhone(String raw) {
        var matcher = PHONE.matcher(normalize(raw));
        if (!matcher.find()) {
            return "手机号已隐藏";
        }
        String phone = matcher.group();
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}
