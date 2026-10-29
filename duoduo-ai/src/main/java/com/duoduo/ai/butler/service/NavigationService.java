package com.duoduo.ai.butler.service;

import com.duoduo.ai.route.ResolvedRoute;
import com.duoduo.ai.route.RouteRegistry;
import com.duoduo.ai.route.RouteWhitelist;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 导航服务：所有工具产出链接的**唯一出口**。
 *
 * <p>为什么工具不自己拼字符串：拼链接的地方只要有一处漏了白名单校验，
 * 整条「模型不能生成 URL」的防线就等于不存在。全部收敛到这里之后，
 * 只有 {@link RouteRegistry#resolve} 一条路径能产出地址。
 *
 * <p>登录地址也是同一套规则：{@code next} 必须是已登记的站内路径，
 * 否则退回 AI 页 —— 开放重定向在「登录回跳」这个经典位置最容易被利用。
 */
@Service
public class NavigationService {

    /** next 的兜底值：登录完成后回到 AI 管家，用户不会迷路。 */
    private static final String DEFAULT_NEXT = "/ai";

    private final RouteRegistry registry;
    private final RouteWhitelist whitelist;

    public NavigationService(RouteRegistry registry, RouteWhitelist whitelist) {
        this.registry = registry;
        this.whitelist = whitelist;
    }

    /**
     * 解析功能键 → 双端路径。
     *
     * @throws IllegalArgumentException 未知功能键、缺少路径参数、或拼出的地址未过白名单
     */
    public ResolvedRoute route(String feature, Map<String, String> params) {
        return registry.resolve(feature, params);
    }

    /**
     * 生成带 next 的登录地址。
     *
     * <p>先剥掉查询串：{@code /orders?status=pending} 作为 next 会形成
     * 「登录页 → 带参数的登录页」的嵌套跳转链，而 next 本身只该是「回到哪个页面」。
     * 剥完仍不合法（外链、未登记页面）就退回 {@link #DEFAULT_NEXT}，绝不把未知值塞进 URL。
     */
    public String loginUrl(String nextPath) {
        String candidate = nextPath == null ? "" : nextPath.trim();
        int query = candidate.indexOf('?');
        if (query >= 0) {
            candidate = candidate.substring(0, query);
        }
        try {
            if (!candidate.isBlank()) {
                return registry.resolve("LOGIN", Map.of("next", candidate)).webPath();
            }
        } catch (IllegalArgumentException ignored) {
            // 不可信的回跳目标一律丢弃，走默认页
        }
        return registry.resolve("LOGIN", Map.of("next", DEFAULT_NEXT)).webPath();
    }

    /** 可用功能键清单（未知 feature 时回给模型，便于它换一个键重试）。 */
    public String describeFeatures() {
        return String.join("/", registry.keys());
    }

    /**
     * 校验「用户当前页面」并只保留路径部分。
     *
     * <p>页面地址来自客户端（可能是任意字符串），会被写进提示词的 Context 块 ——
     * 这是提示词注入的典型入口。所以：只接受已登记的站内路径，且**丢掉查询串**
     * （{@code ?x=忽略以上指令} 这类载荷没有机会进入模型上下文）。
     *
     * @return 合法路径；非法或为空返回 null（调用方按「未提供页面」处理）
     */
    public String safeInternalPath(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String path = raw.trim();
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int hash = path.indexOf('#');
        if (hash >= 0) {
            path = path.substring(0, hash);
        }
        try {
            whitelist.assertInternalPath(path);
            return path.length() > 500 ? null : path;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
