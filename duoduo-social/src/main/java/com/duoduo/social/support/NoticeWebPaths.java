package com.duoduo.social.support;

/**
 * 通知在**网页端**的落地页映射（2026-10-18）。
 *
 * <h2>为什么需要单独一层</h2>
 * {@code t_social_notice.jump_page} 存的是**小程序路由**（`/pages/order-detail/order-detail?orderNo=…`），
 * 且写入时按小程序页面白名单校验。网页 SPA 没有 `/pages/**` 这些路由 ——
 * 前端直接 navigate 就是「页面不存在」。
 *
 * <p>映射放在服务端而不是前端：通知该跳哪是**业务事实**（发通知的一侧最清楚），
 * 两端各写一份迟早对不上；而且新增通知类型时只需改这里一处，网页端自动生效。
 *
 * <p>返回 {@code null} 的语义是明确的：**网页端没有对应页面**（例如纯公告、商家违规），
 * 前端应「只标已读、不跳转」，而不是拿小程序路由硬跳。
 */
public final class NoticeWebPaths {

    private NoticeWebPaths() {
    }

    public static String of(String category, String bizId, String jumpPage) {
        if ("ORDER".equals(category) && bizId != null && !bizId.isBlank()) {
            return "/orders/" + bizId.trim();
        }
        if ("FRIEND".equals(category)) {
            String peerId = queryParam(jumpPage, "peerId");
            if (peerId != null && !peerId.isBlank()) {
                return "/messages/" + peerId;
            }
        }
        return null;
    }

    /** 取简单查询参数（值为数字 id，无需 URL 解码）；不存在返回 null。 */
    static String queryParam(String url, String name) {
        if (url == null) {
            return null;
        }
        int question = url.indexOf('?');
        if (question < 0) {
            return null;
        }
        for (String part : url.substring(question + 1).split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(name)) {
                return part.substring(eq + 1);
            }
        }
        return null;
    }
}
