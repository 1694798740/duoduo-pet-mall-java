package com.duoduo.ai.dto.ai;

import java.util.List;

/**
 * 工具的统一返回结构。
 *
 * <p>工具失败也是**结构化**的：{@code success=false} + 明确的 {@code code}，
 * 让模型能据此决定「换个参数重试」还是「告诉用户先登录」，而不是让整轮对话 500。
 * 与既有 {@code ToolResult} 的区别：这里是**面向前端契约**的形态 ——
 * 多了 pageUrl（跳转）、actions（按钮）、badgePatch（红点）与登录态信息，
 * 交给卡片装配层直接序列化，不再二次加工。
 *
 * <p>JSON 字段名：{@code success,code,message,data,pageUrl,actions,badgePatch,needLogin,loginUrl}。
 *
 * @param success    是否成功
 * @param code       结果码（成功为 OK；失败为业务码，如 NEED_LOGIN / PARAM_MISSING）
 * @param message    面向用户的提示（成功时可空）
 * @param data       业务数据（由具体工具决定类型）
 * @param pageUrl    当前数据对应的 Web 页面地址
 * @param actions    可点击动作
 * @param badgePatch 红点补丁（无变化时为 null）
 * @param needLogin  是否因未登录而无法完成
 * @param loginUrl   需要登录时的登录地址（带 next，登录后可回到原页面）
 */
public record ToolResponse<T>(
        boolean success,
        String code,
        String message,
        T data,
        String pageUrl,
        List<AiAction> actions,
        BadgePatch badgePatch,
        boolean needLogin,
        String loginUrl
) {

    /**
     * 成功返回。actions 归一为不可变列表（null 视为空列表），
     * 避免前端拿到 null 后还要额外判空。
     */
    public static <T> ToolResponse<T> ok(T data, String pageUrl, List<AiAction> actions, BadgePatch badgePatch) {
        return new ToolResponse<>(true, "OK", null, data, pageUrl,
                actions == null ? List.of() : List.copyOf(actions), badgePatch, false, null);
    }

    /** 业务失败：不带跳转，也不带红点。 */
    public static <T> ToolResponse<T> fail(String code, String message) {
        return new ToolResponse<>(false, code, message, null, null, List.of(), null, false, null);
    }

    /** 需要登录：前端据此弹登录（loginUrl 带 next，登录后回到原页面）。 */
    public static <T> ToolResponse<T> needLogin(String loginUrl) {
        return new ToolResponse<>(false, "NEED_LOGIN", "请先登录", null, null, List.of(), null, true, loginUrl);
    }
}
