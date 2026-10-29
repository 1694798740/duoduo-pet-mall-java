package com.duoduo.ai.dto.ai;

/**
 * 卡片/回复上的可点击动作。
 *
 * <p>字段刻意只有「文案 + 目标」，不含权限、不含业务参数：URL 由服务端路由表拼好，
 * 前端与小程序只负责按平台挑 {@code url} 或 {@code miniPath}，不做二次拼装。
 *
 * <p>{@code type} 当前取值：{@code NAVIGATE}（跳转）、{@code CONFIRM}（需要用户确认的动作）、
 * {@code LOGIN}（先登录）。用字符串而不是枚举，是为了让它在提示词/工具 schema 里
 * 保持可读，并允许后续新增类型时不破坏反序列化。
 *
 * <p>JSON 字段名与 record 组件同名（camelCase）：{@code type,label,url,miniPath}。
 *
 * @param type     动作类型
 * @param label    按钮文案
 * @param url      Web 端地址（可为 null）
 * @param miniPath 小程序路径（可为 null）
 */
public record AiAction(
        String type,
        String label,
        String url,
        String miniPath
) {

    public static final String TYPE_NAVIGATE = "NAVIGATE";
    public static final String TYPE_CONFIRM = "CONFIRM";
    public static final String TYPE_LOGIN = "LOGIN";
}
