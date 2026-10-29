package com.duoduo.ai.route;

import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 路由白名单：AI 输出的任何跳转目标都必须先过这里。
 *
 * <p>为什么必须有白名单：卡片里的按钮地址最终会由**模型/工具的数据**决定，
 * 而模型输出属于不可信输入。没有白名单时，一次提示词注入就能让 AI 给出
 * {@code https://evil.example} 或者 {@code /product/../../admin} 这样的链接，
 * 前端照单全收就是开放重定向 / 越权入口。
 *
 * <p>实现要点：
 * <ul>
 *   <li>Web 与小程序两端**同时**校验 —— 只校验一端等于留了一扇后门；</li>
 *   <li>正则全部是静态编译的 {@link Pattern}，绝不把用户输入拼进正则，
 *       否则「正则注入」会让白名单自己失效；</li>
 *   <li>路径（{@code ?} 之前）与查询串**分开校验**：路径只比白名单模板，
 *       查询串逐个值解码后再查协议 —— 否则 {@code /login?next=https%3A%2F%2Fevil}
 *       会因为「编码后看不见 https://」而被放行；</li>
 *   <li>查询值里的 {@code ..} 不再一律拒绝：搜索词「..」属于正常输入，
 *       编码后也不具备穿越能力，真正需要拦的是协议与协议相对地址。</li>
 * </ul>
 *
 * <p>本类注册为 Spring {@code @Component}，便于后续工具通过注入复用；
 * 校验方法是无状态的，也可以直接静态调用。
 */
@Component
public class RouteWhitelist {

    /** Web 端精确静态路径（不含参数，含根路径）。 */
    private static final Set<String> WEB_EXACT = Set.of(
            "/",
            "/search",
            "/product",
            "/cart",
            "/checkout",
            "/orders",
            "/after-sales",
            "/notifications",
            "/coupons",
            "/coins",
            "/favorites",
            "/history",
            "/pets",
            "/me",
            "/support",
            "/seckill",
            "/listings",
            "/listings/new",
            "/listings/mine",
            "/messages",
            "/share",
            "/addresses",
            "/login",
            "/ai",
            "/store",
            "/user"
    );

    /**
     * Web 端模板路径（带 ID 的详情页）。
     * 全部静态编译，且只允许「纯数字 ID」—— 订单号固定 21 位（雪花 ID 十进制长度）。
     */
    private static final List<Pattern> WEB_TEMPLATES = List.of(
            Pattern.compile("^/product/\\d+$"),
            Pattern.compile("^/store/\\d+$"),
            Pattern.compile("^/user/\\d+$"),
            Pattern.compile("^/orders/\\d{21}$"),
            Pattern.compile("^/after-sales/\\d+$"),
            Pattern.compile("^/listings/\\d+$"),
            Pattern.compile("^/share/\\d+$"),
            Pattern.compile("^/messages/\\d+$")
    );

    /** 小程序端精确页面路径（不含查询串）。 */
    private static final Set<String> MINI_EXACT = Set.of(
            "pages/index/index",
            "pages/search/search",
            "pages/product/product",
            "pages/cart/cart",
            "pages/checkout/checkout",
            "pages/orders/orders",
            "pages/order-detail/order-detail",
            "pages/after-sales/after-sales",
            "pages/after-sale/after-sale",
            "pages/notification/notification",
            "pages/coupons/coupons",
            "pages/coins/coins",
            "pages/profile/profile",
            "pages/support/support",
            "pages/seckill/seckill",
            "pages/flea-market/flea-market",
            "pages/flea-publish/flea-publish",
            "pages/flea-mine/flea-mine",
            "pages/chat-list/chat-list",
            "pages/share-circle/share-circle",
            "pages/address/address",
            "pages/login/login",
            "pages/ai/ai"
    );

    /** 同时校验 Web 与小程序路径；任一端不合规则整条路由作废。 */
    public void assertAllowed(String webPath, String miniPath) {
        assertWebPath(pathPart(webPath), webPath, miniPath);
        assertQuery(queryPart(webPath), webPath, miniPath, "Web");

        assertMiniPath(pathPart(miniPath), webPath, miniPath);
        assertQuery(queryPart(miniPath), webPath, miniPath, "小程序");
    }

    /**
     * 校验「站内跳转目标」：登录 next、卡片回跳等把用户带回本站的场景。
     *
     * <p>与 {@link #assertAllowed} 的差别是：这是**目标本身**而不是一条完整路由，
     * 所以既接受 Web 路径（{@code /orders/123}）也接受小程序路径（{@code pages/orders/orders}）。
     *
     * <p>刻意不允许查询串与锚点：{@code next} 里再嵌一个 {@code next}
     * （{@code /login?next=https://evil}）是一条很常见的重定向链绕过，
     * 从源头掐断比在每一跳上做判断更可靠。
     */
    public void assertInternalPath(String path) {
        if (path == null || path.isBlank()) {
            throw reject(path, null, "跳转目标为空");
        }
        if (path.indexOf('?') >= 0 || path.indexOf('#') >= 0) {
            throw reject(path, null, "跳转目标不允许携带查询串或锚点");
        }
        if (path.startsWith("/")) {
            assertWebPath(path, path, null);
        } else if (path.startsWith("pages/")) {
            assertMiniPath(path, path, null);
        } else {
            throw reject(path, null, "跳转目标必须是站内路径（/ 或 pages/ 开头）");
        }
    }

    /** Web 路径校验：单斜杠开头、无穿越、命中精确集合或模板。 */
    private void assertWebPath(String only, String webPath, String miniPath) {
        assertNotBlank(only, webPath, miniPath, "Web");
        assertNoPseudoScheme(only, webPath, miniPath, "Web");
        if (!only.startsWith("/") || only.startsWith("//")) {
            throw reject(webPath, miniPath, "Web 路径必须以单个 / 开头");
        }
        if (only.contains("..") || only.contains("\\")) {
            throw reject(webPath, miniPath, "Web 路径包含目录穿越或反斜杠");
        }
        if (!WEB_EXACT.contains(only) && WEB_TEMPLATES.stream().noneMatch(p -> p.matcher(only).matches())) {
            throw reject(webPath, miniPath, "Web 路径不在白名单内: " + only);
        }
    }

    /** 小程序路径校验：pages/ 开头、无穿越、命中精确集合。 */
    private void assertMiniPath(String only, String webPath, String miniPath) {
        assertNotBlank(only, webPath, miniPath, "小程序");
        assertNoPseudoScheme(only, webPath, miniPath, "小程序");
        if (!only.startsWith("pages/") || only.startsWith("/")) {
            throw reject(webPath, miniPath, "小程序路径必须以 pages/ 开头");
        }
        if (only.contains("..") || only.contains("\\")) {
            throw reject(webPath, miniPath, "小程序路径包含目录穿越或反斜杠");
        }
        if (!MINI_EXACT.contains(only)) {
            throw reject(webPath, miniPath, "小程序路径不在白名单内: " + only);
        }
    }

    /**
     * 查询串校验：按 {@code &}、{@code =} 拆成键值，逐值**先解码再查**。
     *
     * <p>必须在解码后查：注册表写入的是百分号编码值，{@code https://evil}
     * 会变成 {@code https%3A%2F%2Fevil}，直接在编码串上找 {@code https://} 永远找不到。
     */
    private void assertQuery(String query, String webPath, String miniPath, String kind) {
        if (query == null || query.isEmpty()) {
            return;
        }
        for (String pair : query.split("&", -1)) {
            int eq = pair.indexOf('=');
            String rawValue = eq >= 0 ? pair.substring(eq + 1) : "";
            String value = decode(rawValue, webPath, miniPath, kind);
            if (value.isEmpty()) {
                continue;
            }
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.contains("http://") || lower.contains("https://")) {
                throw reject(webPath, miniPath, kind + " 查询值包含外链协议: " + value);
            }
            if (lower.contains("javascript:") || lower.contains("data:")) {
                throw reject(webPath, miniPath, kind + " 查询值包含可执行伪协议: " + value);
            }
            // 协议相对地址 //evil.com 在浏览器里等价于 https://evil.com
            if (value.startsWith("//") || value.contains("\\")) {
                throw reject(webPath, miniPath, kind + " 查询值包含协议相对地址或反斜杠: " + value);
            }
            assertNoControlChar(value, webPath, miniPath, kind);
        }
    }

    /** 解码查询值；非法百分号编码直接拒绝，避免解析结果与真实跳转不一致。 */
    private String decode(String value, String webPath, String miniPath, String kind) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw reject(webPath, miniPath, kind + " 查询串编码非法: " + value);
        }
    }

    private void assertNotBlank(String only, String webPath, String miniPath, String kind) {
        if (only == null || only.isBlank()) {
            throw reject(webPath, miniPath, kind + " 路径为空");
        }
    }

    /** 外链协议与伪协议检查（路径部分）。 */
    private void assertNoPseudoScheme(String only, String webPath, String miniPath, String kind) {
        String lower = only.toLowerCase(Locale.ROOT);
        if (lower.contains("http://") || lower.contains("https://")) {
            throw reject(webPath, miniPath, kind + " 路径包含外链协议");
        }
        if (lower.contains("javascript:") || lower.contains("data:")) {
            throw reject(webPath, miniPath, kind + " 路径包含可执行伪协议");
        }
        assertNoControlChar(only, webPath, miniPath, kind);
    }

    /**
     * 控制字符检查：{@code \r\n} 能让日志与真实地址不一致，
     * {@code \t}、NUL 在部分解析器里会被截断，一律不允许进入路由。
     */
    private void assertNoControlChar(String value, String webPath, String miniPath, String kind) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw reject(webPath, miniPath, kind + " 包含控制字符");
            }
        }
    }

    /** 取 {@code ?} 之前的路径部分（无查询串时原样返回）。 */
    private String pathPart(String value) {
        if (value == null) {
            return null;
        }
        int idx = value.indexOf('?');
        return idx >= 0 ? value.substring(0, idx) : value;
    }

    /** 取 {@code ?} 之后的查询串（无查询串时返回空串）。 */
    private String queryPart(String value) {
        if (value == null) {
            return "";
        }
        int idx = value.indexOf('?');
        return idx >= 0 ? value.substring(idx + 1) : "";
    }

    private IllegalArgumentException reject(String webPath, String miniPath, String reason) {
        return new IllegalArgumentException(
                "ROUTE_NOT_ALLOWED: webPath=" + webPath + ", miniPath=" + miniPath + ", reason=" + reason);
    }
}
