package com.duoduo.ai.guardrail;

import com.duoduo.ai.route.RouteWhitelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 链接护栏（2026-10-29 · 阶段三）：模型正文里不允许出现任何「模型自己写的地址」。
 *
 * <h2>为什么输出侧还要再拦一次</h2>
 * 提示词已经要求「不要写网址」，路由也做了白名单，但那两道都在**工具路径**上。
 * 模型完全可以在正文里自由发挥出 {@code https://evil.com}、{@code javascript:...}
 * 或 {@code /admin} —— 前端若把它渲染成可点链接，就是一次现成的开放重定向。
 * 输出侧统一清洗，是最后一道、也是唯一覆盖「纯文本」的防线。
 *
 * <h2>为什么是「屏蔽」而不是「整段重写」</h2>
 * 把整段回复换掉会让用户莫名其妙（尤其是明明答案正确、只是多贴了一个链接）。
 * 这里只替换掉不安全的那一段，其余内容保留，并在日志里留痕便于排查提示词漂移。
 *
 * <h2>为什么站内路径要放行</h2>
 * 「你的订单在 /orders」这类表述本身没错，且白名单内的路径确实存在。
 * 只有**未登记**的路径（如 {@code /admin}）才需要屏蔽 —— 那是模型编出来的入口。
 */
@Component
public class LinkGuard {

    private static final Logger log = LoggerFactory.getLogger(LinkGuard.class);

    /** 外链与伪协议：整体替换，避免把 URL 的一部分留下来拼成新地址。 */
    private static final Pattern EXTERNAL = Pattern.compile(
            "(?i)(?:https?:\\/\\/[^\\s，。；、）】)】\"'<>]+"
                    + "|www\\.[^\\s，。；、）】)】\"'<>]+"
                    + "|javascript:[^\\s，。；、）】)】\"'<>]+"
                    + "|data:[^\\s，。；、）】)】\"'<>]+)");

    /**
     * 站内路径：首段必须以字母开头。
     *
     * <p>这条限制是为了避开「1/2 罐」「A/B 测试」这类正常文本：
     * 前者斜杠后是数字、后者斜杠前是字母，两种情况都不会被当成路径。
     */
    private static final Pattern INTERNAL_PATH = Pattern.compile(
            "(?<![A-Za-z0-9_])/[A-Za-z][A-Za-z0-9_\\-]*(?:/[A-Za-z0-9_\\-]+)*"
                    + "(?:\\?[^\\s，。；、）】)】\"'<>]*)?");

    private static final String MASK_EXTERNAL = "（外部链接已屏蔽）";
    private static final String MASK_PATH = "（页面）";

    private final RouteWhitelist whitelist;

    public LinkGuard(RouteWhitelist whitelist) {
        this.whitelist = whitelist;
    }

    /**
     * 清洗回复正文。
     *
     * @param reply 模型回复（已从 JSON 信封里取出 reply 字段，不含 card_refs）
     * @return 可直接展示的文本
     */
    public String sanitize(String reply) {
        if (reply == null || reply.isBlank()) {
            return reply;
        }

        String cleaned = EXTERNAL.matcher(reply).replaceAll(MASK_EXTERNAL);

        Matcher matcher = INTERNAL_PATH.matcher(cleaned);
        StringBuilder sb = new StringBuilder(cleaned.length());
        int maskedPaths = 0;
        while (matcher.find()) {
            String candidate = matcher.group();
            if (allowed(candidate)) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(candidate));
            } else {
                maskedPaths++;
                matcher.appendReplacement(sb, MASK_PATH);
            }
        }
        matcher.appendTail(sb);

        if (maskedPaths > 0) {
            log.warn("AI 回复出现未登记路径，已屏蔽 {} 处", maskedPaths);
        }
        return sb.toString();
    }

    /**
     * 只屏蔽外链与伪协议，不动站内路径。
     *
     * <p>用于**卡片展示字段**（通知标题、订单摘要、券名）：这些文本来自用户或商家，
     * 可能本身就带 URL。前端一旦做了「URL 自动识别」，卡片就会变成可点外链 ——
     * 卡片数据虽然是服务端装配的，但内容的可信度并不比模型回复高。
     */
    public String sanitizeText(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        return EXTERNAL.matcher(text).replaceAll(MASK_EXTERNAL);
    }

    /** 查询串不参与路径白名单判定：{@code /orders?status=pending} 的路径部分是 {@code /orders}。 */
    private boolean allowed(String candidate) {
        String path = candidate;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        try {
            whitelist.assertInternalPath(path);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
