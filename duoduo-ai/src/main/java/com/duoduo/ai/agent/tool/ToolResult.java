package com.duoduo.ai.agent.tool;

import com.duoduo.ai.dto.ai.AiAction;
import com.duoduo.ai.dto.ai.BadgePatch;
import com.duoduo.ai.dto.ai.ToolResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 工具结果。失败也必须是结构化结果，让模型能根据 code 决定下一步，而不是让整轮对话 500。
 *
 * <p><b>为什么这里也带 pageUrl / actions / badgePatch</b>：
 * 它们不是给模型看的「话术」，而是**交给卡片装配层的事实**——
 * 模型只允许输出 reply + card_refs（见最终落地文档 Q5），
 * 若把跳转地址与红点值塞进模型上下文再让它复述，等于把「可被篡改的数字」放进了回复里。
 * 工具把事实算好、放在结果外层，Agent 直接取用，模型一个字都不需要编。
 *
 * <p>前 6 个组件是原有契约（模型可见的执行结果），后 5 个与
 * {@link ToolResponse} 一一对应，由 {@link #of(ToolResponse, ObjectMapper)} 转换而来。
 *
 * <p><b>Jackson 兼容</b>：本类会整体序列化进 Redis 幂等缓存，
 * 新增组件对旧缓存是「缺字段 → null / false」，反序列化不会失败。
 */
public record ToolResult(
        boolean success,
        String code,
        String message,
        JsonNode data,
        boolean needConfirm,
        String confirmToken,
        String pageUrl,
        List<AiAction> actions,
        BadgePatch badgePatch,
        boolean needLogin,
        String loginUrl
) {

    /** actions 归一为不可变列表：前端与 Agent 都不需要判空。 */
    public ToolResult {
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    public static ToolResult ok(JsonNode data) {
        return new ToolResult(true, "OK", null, data, false, null,
                null, List.of(), null, false, null);
    }

    public static ToolResult fail(String code, String message) {
        return new ToolResult(false, code, message, null, false, null,
                null, List.of(), null, false, null);
    }

    public static ToolResult needConfirm(String token, JsonNode preview) {
        return new ToolResult(false, "NEED_CONFIRM", "需要用户确认", preview, true, token,
                null, List.of(), null, false, null);
    }

    /**
     * 需要登录。{@code loginUrl} 可以为 null（调用方拿不到可信的站内回跳地址时宁可不给，
     * 也不要把一个可疑地址塞进登录页）。
     *
     * <p>注意这里没有无参重载：记录组件本身就有 {@code needLogin()} 访问器，
     * 同签名的静态方法会与它冲突（编译期直接报「存取方法无效」）。
     */
    public static ToolResult needLogin(String loginUrl) {
        return new ToolResult(false, "NEED_LOGIN", "请先登录", null, false, null,
                null, List.of(), null, true, loginUrl);
    }

    /**
     * 把工具层契约 {@link ToolResponse} 转成模型可见的执行结果。
     *
     * <p>之所以要转而不是直接返回 ToolResponse：ToolRegistry 需要
     * {@code success/code/needConfirm/confirmToken} 这几个既有字段来决定
     * 「是否重试、是否走二次确认」，这些字段 ToolResponse 里没有。
     * data 用 ObjectMapper 转成 JsonNode，避免 record 泛型在缓存反序列化时丢类型。
     */
    public static ToolResult of(ToolResponse<?> response, ObjectMapper mapper) {
        if (response == null) {
            return fail("TOOL_ERROR", "工具没有返回结果");
        }
        JsonNode payload = response.data() == null ? null : mapper.valueToTree(response.data());
        return new ToolResult(
                response.success(),
                response.code(),
                response.message(),
                payload,
                false,
                null,
                response.pageUrl(),
                response.actions(),
                response.badgePatch(),
                response.needLogin(),
                response.loginUrl());
    }
}
