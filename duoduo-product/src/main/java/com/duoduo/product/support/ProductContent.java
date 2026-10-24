package com.duoduo.product.support;

import com.duoduo.product.dto.ProductDtos;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

import java.util.ArrayList;
import java.util.List;

/**
 * 商品内容字段的净化与序列化（2026-10-24）。
 *
 * <h2>为什么服务端必须消毒</h2>
 * 富文本详情最终会以 HTML 渲染在商城页面上。只在编辑器里做清理不够 ——
 * 直接 `PUT /api/seller/products/{id}` 传一段 `<img onerror=...>` 就能绕过前端，
 * 而这段 HTML 会被所有买家加载。因此落库前统一走 jsoup 白名单：
 * <ul>
 *   <li>标签：只保留排版类标签（段落/标题/列表/表格/图片/链接/代码块）；</li>
 *   <li>属性：只保留 {@code a[href,target,rel]} 与 {@code img[src,alt,width,height]}；</li>
 *   <li>协议：jsoup {@code preserveRelativeLinks=false} + 协议白名单，
 *       {@code javascript:} / {@code data:} 一律被剥掉。</li>
 * </ul>
 * 这条纪律与「审核只看内容、不看端」一致：凡是被公开读取的字段，脏数据不能入库。
 */
public final class ProductContent {

    /** 富文本详情最大长度（消毒后的字符数）；超出直接拒绝而不是静默截断 */
    public static final int DETAIL_HTML_MAX = 20000;
    /** 商品介绍（纯文本）最大长度 */
    public static final int INTRO_MAX = 2000;
    /** 规格参数 / 包装规格最多条数 */
    public static final int KV_MAX_ITEMS = 20;
    /** 单条 KV 的键 / 值长度上限 */
    public static final int KV_NAME_MAX = 30;
    public static final int KV_VALUE_MAX = 200;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Safelist SAFELIST = Safelist.relaxed()
            // relaxed 默认已含 a/img/表格/列表等；这里补常用排版标签
            .addTags("figure", "figcaption", "span", "div", "h1", "h2", "h3", "h4", "hr", "s", "u")
            .addAttributes("a", "target", "rel")
            .addAttributes("img", "width", "height")
            // 只允许 http/https/相对地址；javascript:/data: 会被 jsoup 按协议白名单丢弃
            .addProtocols("a", "href", "http", "https")
            .addProtocols("img", "src", "http", "https");

    private ProductContent() {
    }

    /**
     * 消毒 HTML。空白输入返回 null（等价于「没有富文本详情」，端侧回落纯文本介绍）。
     *
     * <p>用 {@code Jsoup.clean} 而不是自己写正则：HTML 的正则清洗永远漏，
     * 一个变形标签就能绕过；jsoup 走的是真正的解析器，标签一旦解析出来就没法"藏"。
     */
    public static String sanitizeHtml(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = Jsoup.clean(raw, "", SAFELIST);
        // jsoup 会对空文档输出空串；统一归一为 null
        String text = Jsoup.parse(cleaned).text();
        if (text.isBlank() && !cleaned.contains("<img")) {
            return null;
        }
        if (cleaned.length() > DETAIL_HTML_MAX) {
            throw new IllegalArgumentException("富文本详情不能超过 " + DETAIL_HTML_MAX + " 个字符");
        }
        return cleaned;
    }

    /** 富文本的纯文本摘要（违禁词检查用：只看标签里的字，避免标签名误命中） */
    public static String htmlText(String html) {
        return html == null ? null : Jsoup.parse(html).text();
    }

    /** KV 列表归一：去空键、去首尾空白、截断超长值、限制条数 */
    public static List<ProductDtos.KvItem> normalizeKv(List<ProductDtos.KvItem> items, String label) {
        if (items == null) {
            return null;
        }
        List<ProductDtos.KvItem> out = new ArrayList<>();
        for (ProductDtos.KvItem item : items) {
            if (item == null) {
                continue;
            }
            String name = item.name() == null ? "" : item.name().trim();
            String value = item.value() == null ? "" : item.value().trim();
            if (name.isEmpty() && value.isEmpty()) {
                continue; // 空行：UI 上留的空输入框，忽略而不是报错
            }
            if (name.isEmpty()) {
                throw new IllegalArgumentException(label + "的「参数名」不能为空");
            }
            if (name.length() > KV_NAME_MAX) {
                throw new IllegalArgumentException(label + "的参数名不能超过 " + KV_NAME_MAX + " 个字");
            }
            if (value.length() > KV_VALUE_MAX) {
                throw new IllegalArgumentException(label + "的参数值不能超过 " + KV_VALUE_MAX + " 个字");
            }
            out.add(new ProductDtos.KvItem(name, value));
        }
        if (out.size() > KV_MAX_ITEMS) {
            throw new IllegalArgumentException(label + "最多 " + KV_MAX_ITEMS + " 条");
        }
        return out;
    }

    /** 序列化为 JSON 文本；空列表返回 null（库里不存 "[]" 这种噪音） */
    public static String toJson(List<ProductDtos.KvItem> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(items);
        } catch (Exception e) {
            throw new IllegalStateException("规格参数序列化失败", e);
        }
    }

    /** 反序列化；脏数据（历史手工改库）降级为空列表而不是让详情页 500 */
    public static List<ProductDtos.KvItem> parseKv(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<List<ProductDtos.KvItem>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
