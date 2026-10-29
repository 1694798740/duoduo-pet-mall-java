package com.duoduo.ai.route;

/**
 * 解析完成的路由：模板参数已替换、并已通过白名单校验的最终路径。
 *
 * <p>只携带「可展示、可跳转」的字段，不携带 params 列表 ——
 * 调用方拿到它就应该能直接拼出 actions 里的 url / miniPath。
 *
 * @param key      路由键
 * @param webPath  已编码参数的 Web 路径，例如 {@code /product/123}
 * @param miniPath 已编码参数的小程序路径，例如 {@code pages/product/product?spuId=123}
 * @param label    中文名
 * @param auth     访问级别
 */
public record ResolvedRoute(
        String key,
        String webPath,
        String miniPath,
        String label,
        AuthLevel auth
) {
}
