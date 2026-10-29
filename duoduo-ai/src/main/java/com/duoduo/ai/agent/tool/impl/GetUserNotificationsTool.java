package com.duoduo.ai.agent.tool.impl;

import com.duoduo.ai.agent.tool.AgentTool;
import com.duoduo.ai.agent.tool.ToolArgs;
import com.duoduo.ai.agent.tool.ToolContext;
import com.duoduo.ai.agent.tool.ToolResult;
import com.duoduo.ai.agent.tool.ToolSchemas;
import com.duoduo.ai.butler.dto.ButlerViews.NotificationListData;
import com.duoduo.ai.butler.service.NavigationService;
import com.duoduo.ai.butler.service.NotificationQueryService;
import com.duoduo.ai.dto.ai.AiAction;
import com.duoduo.ai.dto.ai.BadgePatch;
import com.duoduo.ai.dto.ai.ToolResponse;
import com.duoduo.ai.route.ResolvedRoute;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 「我的通知」工具（阶段二）。
 *
 * <p><b>为什么它不是写操作</b>：标记已读是用户可见的低风险副作用，
 * 且发生在用户主动问「我有什么通知」时 —— 让他再点一次「确认已读」是多余的。
 * 真正的写操作（领券、下单、退款）才需要二次确认。
 *
 * <p><b>红点补丁只在真的标了已读时下发</b>：markedRead=0 时 badgePatch=null，
 * 前端不动角标。若这里无论查没查都下发一个数字，前端就会把「查询」误当成「已消」。
 */
@Component
public class GetUserNotificationsTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(GetUserNotificationsTool.class);

    private final NotificationQueryService notificationQueryService;
    private final NavigationService navigation;
    private final ObjectMapper mapper;

    public GetUserNotificationsTool(NotificationQueryService notificationQueryService,
                                    NavigationService navigation, ObjectMapper mapper) {
        this.notificationQueryService = notificationQueryService;
        this.navigation = navigation;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "get_user_notifications";
    }

    @Override
    public String description() {
        return "查询我的通知列表（默认只看未读），只把本次展示的前 N 条标记为已读，"
                + "返回通知内容、剩余未读数与红点补丁。需要登录。";
    }

    @Override
    public JsonNode inputSchema() {
        return ToolSchemas.GET_USER_NOTIFICATIONS;
    }

    @Override
    public ToolResult execute(ToolContext context, JsonNode args) {
        if (!context.loggedIn() || context.userId() == null) {
            return ToolResult.of(ToolResponse.needLogin(navigation.loginUrl("/ai")), mapper);
        }

        String category = ToolArgs.textUpper(args, "category");
        String read = ToolArgs.textOr(args, "read", "UNREAD");
        int limit = ToolArgs.intOr(args, "limit", 5, 1, 10);
        boolean markRead = ToolArgs.boolOr(args, "markRead", true);

        try {
            NotificationListData data = notificationQueryService.load(
                    context.userId(), category, read, limit, markRead);

            Map<String, String> routeParams = category == null ? Map.of() : Map.of("category", category);
            ResolvedRoute route = navigation.route("NOTIFICATIONS", routeParams);
            List<AiAction> actions = List.of(new AiAction(
                    AiAction.TYPE_NAVIGATE, "打开通知中心", route.webPath(), route.miniPath()));

            BadgePatch patch = data.markedRead() > 0
                    ? new BadgePatch(cap(data.unread()), null, null, null)
                    : null;

            return ToolResult.of(ToolResponse.ok(data, route.webPath(), actions, patch), mapper);
        } catch (IllegalArgumentException e) {
            return ToolResult.fail("INVALID_ARGS", e.getMessage());
        } catch (Exception e) {
            log.warn("通知查询失败 users={}", context.userId(), e);
            return ToolResult.fail("NOTIFICATION_QUERY_FAILED", "通知暂时取不到，请稍后再试");
        }
    }

    /** 角标是 Integer，未读数理论上不会超过 int；真超了也只是显示一个上限值，不该溢出成负数。 */
    private static int cap(long value) {
        return (int) Math.min(Math.max(value, 0), Integer.MAX_VALUE);
    }
}
