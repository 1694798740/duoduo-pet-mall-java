package com.duoduo.ai.agent.tool;

import com.duoduo.ai.memory.ConfirmService;
import com.duoduo.ai.support.AiMetrics;
import com.duoduo.common.exception.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册中心（2026-10-28）。
 *
 * <p>所有工具执行都经过这里：参数校验、身份注入、写操作二次确认、幂等、审计。
 * 模型只能“申请”调用，不能直接执行写操作。
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);
    private static final int IDEM_TTL_MINUTES = 10;

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();
    private final ObjectMapper mapper;
    private final ConfirmService confirmService;
    private final StringRedisTemplate redis;
    private final AiMetrics metrics;

    public ToolRegistry(List<AgentTool> toolList, ObjectMapper mapper,
                        ConfirmService confirmService, StringRedisTemplate redis,
                        AiMetrics metrics) {
        for (AgentTool tool : toolList) {
            tools.put(tool.name(), tool);
        }
        this.mapper = mapper;
        this.confirmService = confirmService;
        this.redis = redis;
        this.metrics = metrics;
    }

    public List<ToolSpec> specs() {
        return tools.values().stream()
                .map(t -> new ToolSpec(t.name(), t.description(), t.inputSchema()))
                .toList();
    }

    /**
     * 工具执行入口：**所有 return 路径都经过这里**，成功率/失败率只在一处计数。
     * 参数校验失败、未知工具、确认令牌失效都算一次「工具调用失败」——
     * 看板要回答的是「模型这次申请有没有拿到东西」，而不是「代码有没有抛异常」。
     */
    public ToolResult execute(ToolCall call, ToolContext context) {
        ToolResult result = executeTracked(call, context);
        metrics.toolResult(result.success());
        return result;
    }

    private ToolResult executeTracked(ToolCall call, ToolContext context) {
        AgentTool tool = tools.get(call.name());
        if (tool == null) {
            return ToolResult.fail("UNKNOWN_TOOL", "未知工具：" + call.name());
        }

        JsonNode args;
        try {
            args = call.arguments() == null || call.arguments().isBlank()
                    ? mapper.createObjectNode()
                    : mapper.readTree(call.arguments());
            validateRequired(tool, args);
        } catch (Exception e) {
            return ToolResult.fail("INVALID_ARGS", "工具参数不合法：" + e.getMessage());
        }

        if (tool.writeOperation()) {
            String token = args.path("confirmToken").asText("");
            if (token.isBlank()) {
                String newToken = confirmService.issue(context.userId(), call.name(), args);
                return ToolResult.needConfirm(newToken, args);
            }
            if (!confirmService.consume(context.userId(), token, call.name(), args)) {
                return ToolResult.fail("CONFIRM_INVALID", "确认已过期，请重新确认");
            }
        }

        // 幂等键必须带上参数：同一轮里模型完全可能用不同筛选条件调两次同一个查询工具
        // （先查全部、再查待付款）。只按 requestId+name 做键，第二次会直接拿到第一次的缓存，
        // 用户看到的就是「筛选没生效」。
        String idemKey = "ai:tool:idem:" + context.requestId() + ":" + call.name() + ":"
                + Integer.toHexString(String.valueOf(call.arguments()).hashCode());
        String cached = redis.opsForValue().get(idemKey);
        if (cached != null) {
            try {
                return mapper.readValue(cached, ToolResult.class);
            } catch (Exception ignored) {
                // 缓存损坏则重新执行
            }
        }

        long start = System.currentTimeMillis();
        try {
            ToolResult result = tool.execute(context, args);
            redis.opsForValue().set(idemKey, mapper.writeValueAsString(result),
                    Duration.ofMinutes(IDEM_TTL_MINUTES));
            log.info("AI tool name={} session={} success={} cost={}ms",
                    call.name(), context.sessionId(), result.success(),
                    System.currentTimeMillis() - start);
            return result;
        } catch (BizException e) {
            return ToolResult.fail(e.getErrorCode().name(), e.getMessage());
        } catch (Exception e) {
            log.error("AI tool 执行失败 name={} session={}", call.name(), context.sessionId(), e);
            return ToolResult.fail("TOOL_ERROR", "工具暂时不可用，请稍后再试");
        }
    }

    private void validateRequired(AgentTool tool, JsonNode args) {
        JsonNode required = tool.inputSchema().path("required");
        if (!required.isArray()) {
            return;
        }
        for (JsonNode field : required) {
            String name = field.asText();
            JsonNode value = args.get(name);
            boolean missing = value == null || value.isNull()
                    || (value.isTextual() && value.asText().isBlank());
            if (missing) {
                throw new IllegalArgumentException("缺少参数 " + name);
            }
        }
    }
}
