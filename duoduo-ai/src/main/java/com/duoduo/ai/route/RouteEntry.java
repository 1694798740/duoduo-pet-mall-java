package com.duoduo.ai.route;

import java.util.List;

/**
 * 路由字典条目（静态声明，不含真实参数值）。
 *
 * <p>webPath / miniPath 都是**模板**：花括号里是参数名，与 {@link #params} 一一对应。
 * 之所以要求参数名显式列出，是因为「缺失参数」必须在服务端就报错 ——
 * 让模型漏填一个 spuId 就拼出一个 /product/ 的空链接，前端只能白屏。
 *
 * @param key      路由键（大写下划线，模型与工具只认它）
 * @param webPath  Web 端路径模板，例如 {@code /product/{spuId}}
 * @param miniPath 小程序路径模板，例如 {@code pages/product/product?spuId={spuId}}
 * @param label    给用户看的中文名
 * @param params   模板参数名（顺序即声明顺序；空列表表示无参）
 * @param auth     访问级别
 */
public record RouteEntry(
        String key,
        String webPath,
        String miniPath,
        String label,
        List<String> params,
        AuthLevel auth
) {

    /** 参数列表归一为不可变列表，避免注册表被外部改写。 */
    public RouteEntry {
        params = params == null ? List.of() : List.copyOf(params);
    }
}
