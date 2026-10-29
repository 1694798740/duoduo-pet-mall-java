package com.duoduo.ai.route;

import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 路由注册表：AI 与前端之间唯一的「页面名字 → 真实路径」字典。
 *
 * <p>为什么不在提示词里教模型写路径：让模型直接生成 {@code /product/123} 之类的字符串，
 * 一旦路径规则变化（前端改目录、小程序改页面），提示词、工具、卡片、前端要同时改，
 * 且模型每次都可能写错一个字符。这里改成「模型只挑 key」，路径由服务端拼。
 *
 * <p>三条硬约束（顺序不能换）：
 * <ol>
 *   <li><b>先校验原始值</b>：参数值来自模型抽取的槽位（关键词、订单号等），属于不可信文本。
 *       编码只能防「结构被破坏」，防不了「值本身就是一个外链」——
 *       {@code next=https://evil} 编码后变成 {@code https%3A%2F%2Fevil}，
 *       白名单在编码串上再也找不到 {@code https://}。所以必须在编码**之前**
 *       按参数语义校验原值（见 {@link #sanitizeParam}）。</li>
 *   <li><b>再编码</b>：统一 {@link URLEncoder#encode(String, java.nio.charset.Charset)}，
 *       并把表单风格的 {@code +} 改回 {@code %20} —— 空格在路径/查询里都应是 %20。</li>
 *   <li><b>最后过白名单</b>：返回前统一调用 {@link RouteWhitelist#assertAllowed(String, String)}，
 *       作为「拼装逻辑被人改坏」时的兜底防线。</li>
 * </ol>
 *
 * <p>{@link #get(String)} 返回 {@link Optional}（不抛异常）：调用方（工具层）需要把
 * 「未知 key」转成结构化的失败结果交给模型重试，而不是让整轮对话 500。
 */
@Component
public class RouteRegistry {

    /** 模板占位符：{spuId} / {orderNo} 等，只允许字母数字下划线，避免奇怪字符进入替换逻辑。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    /**
     * 路径型参数的字符白名单：ID 只可能是数字（雪花 ID / 自增 ID）或带 {@code -} 的编码，
     * 绝不允许 {@code /}、{@code .}、{@code ?}、{@code #} —— 少了这些字符，
     * {@code ../admin}、{@code /product/1?x=} 之类在进入 URL 之前就被挡下。
     */
    private static final Pattern SAFE_PATH_PARAM = Pattern.compile("^[A-Za-z0-9_-]+$");

    /** 登录回跳参数名：值是「站内路径」而不是普通文本，需要单独按路径规则校验。 */
    private static final String PARAM_NEXT = "next";

    /** 静态路由表：key → 条目。LinkedHashMap 保证声明顺序（便于排查与文档生成）。 */
    private static final Map<String, RouteEntry> ROUTES;

    static {
        Map<String, RouteEntry> m = new LinkedHashMap<>();

        // 首页 / 搜索 / 商品
        m.put("HOME", new RouteEntry("HOME", "/", "pages/index/index", "首页",
                List.of(), AuthLevel.PUBLIC));
        m.put("SEARCH", new RouteEntry("SEARCH", "/search", "pages/search/search", "搜索",
                List.of("keyword"), AuthLevel.PUBLIC));
        m.put("PRODUCT_DETAIL", new RouteEntry("PRODUCT_DETAIL",
                "/product/{spuId}", "pages/product/product?spuId={spuId}", "商品详情",
                List.of("spuId"), AuthLevel.PUBLIC));

        // 交易链路
        m.put("CART", new RouteEntry("CART", "/cart", "pages/cart/cart", "购物车",
                List.of(), AuthLevel.LOGIN));
        m.put("CHECKOUT", new RouteEntry("CHECKOUT", "/checkout", "pages/checkout/checkout", "结算",
                List.of(), AuthLevel.LOGIN));
        m.put("ORDERS", new RouteEntry("ORDERS", "/orders", "pages/orders/orders", "我的订单",
                List.of("status"), AuthLevel.LOGIN));
        m.put("ORDER_DETAIL", new RouteEntry("ORDER_DETAIL",
                "/orders/{orderNo}", "pages/order-detail/order-detail?orderNo={orderNo}", "订单详情",
                List.of("orderNo"), AuthLevel.LOGIN));
        m.put("AFTER_SALES", new RouteEntry("AFTER_SALES",
                "/after-sales", "pages/after-sales/after-sales", "售后列表",
                List.of("status"), AuthLevel.LOGIN));
        m.put("AFTER_SALE_DETAIL", new RouteEntry("AFTER_SALE_DETAIL",
                "/after-sales/{id}", "pages/after-sale/after-sale?id={id}", "售后详情",
                List.of("id"), AuthLevel.LOGIN));
        // 物流不再单独开页面：复用订单详情（物流信息在详情页内），避免小程序多一个空壳页
        m.put("LOGISTICS", new RouteEntry("LOGISTICS",
                "/orders/{orderNo}", "pages/order-detail/order-detail?orderNo={orderNo}", "物流详情",
                List.of("orderNo"), AuthLevel.LOGIN));

        // 通知 / 营销 / 资产
        m.put("NOTIFICATIONS", new RouteEntry("NOTIFICATIONS",
                "/notifications", "pages/notification/notification", "通知中心",
                List.of("category"), AuthLevel.LOGIN));
        m.put("COUPONS", new RouteEntry("COUPONS", "/coupons", "pages/coupons/coupons", "领券中心",
                List.of("tab"), AuthLevel.PUBLIC));
        m.put("COINS", new RouteEntry("COINS", "/coins", "pages/coins/coins", "多多币",
                List.of(), AuthLevel.LOGIN));

        // 个人中心聚合页（收藏 / 足迹 / 宠物档案 / 个人中心共用 pages/profile/profile）
        m.put("FAVORITES", new RouteEntry("FAVORITES", "/favorites", "pages/profile/profile", "我的收藏",
                List.of(), AuthLevel.LOGIN));
        m.put("HISTORY", new RouteEntry("HISTORY", "/history", "pages/profile/profile", "我的足迹",
                List.of(), AuthLevel.LOGIN));
        m.put("PETS", new RouteEntry("PETS", "/pets", "pages/profile/profile", "宠物档案",
                List.of(), AuthLevel.LOGIN));
        m.put("PROFILE", new RouteEntry("PROFILE", "/me", "pages/profile/profile", "个人中心",
                List.of(), AuthLevel.LOGIN));
        m.put("SUPPORT", new RouteEntry("SUPPORT", "/support", "pages/support/support", "客服",
                List.of(), AuthLevel.LOGIN));
        m.put("ADDRESSES", new RouteEntry("ADDRESSES", "/addresses", "pages/address/address", "收货地址",
                List.of(), AuthLevel.LOGIN));

        // 秒杀 / 闲置 / 社交
        m.put("SECKILL", new RouteEntry("SECKILL", "/seckill", "pages/seckill/seckill", "限时秒杀",
                List.of(), AuthLevel.PUBLIC));
        m.put("FLEA_MARKET", new RouteEntry("FLEA_MARKET",
                "/listings", "pages/flea-market/flea-market", "闲置市场",
                List.of(), AuthLevel.PUBLIC));
        m.put("FLEA_PUBLISH", new RouteEntry("FLEA_PUBLISH",
                "/listings/new", "pages/flea-publish/flea-publish", "发布闲置",
                List.of(), AuthLevel.LOGIN));
        m.put("FLEA_MINE", new RouteEntry("FLEA_MINE",
                "/listings/mine", "pages/flea-mine/flea-mine", "我的闲置",
                List.of(), AuthLevel.LOGIN));
        m.put("MESSAGES", new RouteEntry("MESSAGES", "/messages", "pages/chat-list/chat-list", "私信",
                List.of(), AuthLevel.LOGIN));
        m.put("SHARE_CIRCLE", new RouteEntry("SHARE_CIRCLE",
                "/share", "pages/share-circle/share-circle", "分享圈",
                List.of(), AuthLevel.PUBLIC));

        // 登录兜底与 AI 自身
        m.put("LOGIN", new RouteEntry("LOGIN",
                "/login?next={next}", "pages/login/login?next={next}", "登录",
                List.of("next"), AuthLevel.PUBLIC));
        m.put("AI", new RouteEntry("AI", "/ai", "pages/ai/ai", "AI 管家",
                List.of(), AuthLevel.PUBLIC));

        ROUTES = Collections.unmodifiableMap(m);
    }

    private final RouteWhitelist whitelist;

    public RouteRegistry(RouteWhitelist whitelist) {
        this.whitelist = whitelist;
    }

    /** 按 key 查条目；未知 key 返回 {@link Optional#empty()}（调用方自行决定如何降级）。 */
    public Optional<RouteEntry> get(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(ROUTES.get(key));
    }

    /**
     * 解析路由：把模板里的占位符替换成**URL 编码后**的参数值。
     *
     * @param key    路由键
     * @param params 参数名 → 参数值（可为 null，表示无参）
     * @return 已校验通过的最终路径
     * @throws IllegalArgumentException key 未知、参数缺失、或拼出的路径未过白名单
     */
    public ResolvedRoute resolve(String key, Map<String, String> params) {
        RouteEntry entry = get(key)
                .orElseThrow(() -> new IllegalArgumentException("ROUTE_KEY_UNKNOWN: " + key));

        Map<String, String> safe = params == null ? Map.of() : params;
        String web = render(entry.webPath(), safe, key);
        String mini = render(entry.miniPath(), safe, key);
        // status/tab/category/keyword 这类筛选项不是路径占位符，按查询串追加；
        // 不追加就会让「查看待付款订单」跳到未筛选的订单列表，用户还要再点一次。
        web = appendQuery(web, entry.webPath(), entry, safe);
        mini = appendQuery(mini, entry.miniPath(), entry, safe);

        // 白名单是最后一道闸：编码后的值仍可能是越权地址（例如 orderNo 传 ../admin）
        whitelist.assertAllowed(web, mini);

        return new ResolvedRoute(entry.key(), web, mini, entry.label(), entry.auth());
    }

    /** 所有已注册的路由键（声明顺序）。工具层用它生成「可用功能」提示，避免另抄一份清单。 */
    public Set<String> keys() {
        return ROUTES.keySet();
    }

    /** 模板所有 Web 路径（含 {param} 模板，不含具体值）。 */
    public Set<String> allWebPaths() {
        Set<String> out = new LinkedHashSet<>();
        for (RouteEntry e : ROUTES.values()) {
            out.add(e.webPath());
        }
        return Collections.unmodifiableSet(out);
    }

    /** 模板所有小程序路径（含 {param} 模板，不含具体值）。 */
    public Set<String> allMiniPaths() {
        Set<String> out = new LinkedHashSet<>();
        for (RouteEntry e : ROUTES.values()) {
            out.add(e.miniPath());
        }
        return Collections.unmodifiableSet(out);
    }

    /**
     * 逐个占位符替换。
     *
     * <p>缺失参数直接抛异常：与其拼出 {@code /product/} 这种半截地址让前端白屏，
     * 不如让工具层立刻拿到明确的失败码（PARAM_MISSING）去追问用户。
     *
     * <p>用 {@link Matcher#appendReplacement} 而不是字符串 replace，是为了保证
     * 参数值里的 {@code $}、{@code \} 不会被当成替换语法解释。
     */
    private String render(String template, Map<String, String> params, String key) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder(template.length() + 32);
        while (matcher.find()) {
            String name = matcher.group(1);
            String raw = params.get(name);
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("PARAM_MISSING: key=" + key + ", param=" + name);
            }
            // 先校验原值再编码：编码之后外链协议就「看不见」了，白名单也就失效了
            matcher.appendReplacement(sb, Matcher.quoteReplacement(encode(sanitizeParam(key, name, raw))));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 路径占位符参数的**原值**校验（编码之前）。
     *
     * <p>两类参数语义完全不同：
     * <ul>
     *   <li>{@code next} 回跳地址：值本身是一条站内路径，必须过
     *       {@link RouteWhitelist#assertInternalPath(String)}。少了这一步，
     *       {@code /login?next=https://evil} 就是现成的开放重定向 ——
     *       编码会把 {@code https://} 变成 {@code https%3A%2F%2F}，白名单再也查不出来；</li>
     *   <li>其余 ID 类参数（{@code spuId / orderNo / id}）：只允许
     *       {@link #SAFE_PATH_PARAM}，把 {@code ../}、{@code ?}、{@code #} 提前挡在 URL 之外。</li>
     * </ul>
     */
    private String sanitizeParam(String key, String name, String raw) {
        String value = raw.trim();
        if (PARAM_NEXT.equals(name)) {
            // next 同时出现在 Web 模板与小程序模板，两种站内路径都接受
            whitelist.assertInternalPath(value);
            return value;
        }
        if (!SAFE_PATH_PARAM.matcher(value).matches()) {
            throw new IllegalArgumentException("PARAM_UNSAFE: key=" + key + ", param=" + name);
        }
        return value;
    }

    /**
     * 查询参数值的**原值**校验（编码之前）。
     *
     * <p>查询值编码后结构上不可能越权（{@code &}、{@code =}、{@code #} 都会变成百分号编码），
     * 所以只拦两类：控制字符，以及本身带协议 / 协议相对地址的值 ——
     * 后者一旦被前端当成跳转目标就是开放重定向，而 {@code //evil.com} 在浏览器里
     * 等价于 {@code https://evil.com}。
     *
     * <p>刻意不拦 {@code ..}：搜索词里的「..」是正常输入，编码后在查询值里也不具备穿越能力。
     */
    private String sanitizeQueryValue(String key, String name, String raw) {
        String value = raw.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("http://") || lower.contains("https://")
                || lower.contains("javascript:") || lower.contains("data:")
                || value.startsWith("//") || value.contains("\\")) {
            throw new IllegalArgumentException("PARAM_UNSAFE: key=" + key + ", param=" + name);
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new IllegalArgumentException("PARAM_UNSAFE: key=" + key + ", param=" + name);
            }
        }
        return value;
    }

    /**
     * 参数编码：UTF-8 百分号编码，空格编码成 {@code %20}。
     *
     * <p>{@link URLEncoder} 按 {@code application/x-www-form-urlencoded} 规则把空格编成 {@code +}，
     * 那个 {@code +} 只该出现在表单请求体里；放进路径或查询串会被部分端当成字面加号。
     */
    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * 把「声明了 params 但模板里没有占位符」的参数追加成查询串。
     * 例如 ORDERS 的 status、NOTIFICATIONS 的 category、COUPONS 的 tab。
     * 模板里已有占位符的参数（orderNo/spuId/next）不重复追加。
     */
    private String appendQuery(String path, String template, RouteEntry entry, Map<String, String> params) {
        if (entry.params().isEmpty() || params.isEmpty()) {
            return path;
        }
        StringBuilder query = new StringBuilder();
        for (String name : entry.params()) {
            if (template.contains("{" + name + "}")) {
                continue; // 已被 render 替换进路径，不再重复追加查询串
            }
            String value = params.get(name);
            if (value == null || value.isBlank()) {
                continue;
            }
            if (query.length() > 0) {
                query.append('&');
            }
            query.append(encode(name)).append('=').append(encode(sanitizeQueryValue(entry.key(), name, value)));
        }
        if (query.length() == 0) {
            return path;
        }
        return path + (path.contains("?") ? '&' : '?') + query;
    }
}
