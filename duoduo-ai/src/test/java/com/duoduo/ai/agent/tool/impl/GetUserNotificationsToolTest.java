package com.duoduo.ai.agent.tool.impl;

import com.duoduo.ai.agent.tool.ToolContext;
import com.duoduo.ai.agent.tool.ToolResult;
import com.duoduo.ai.butler.dto.ButlerViews.NotificationItem;
import com.duoduo.ai.butler.dto.ButlerViews.NotificationListData;
import com.duoduo.ai.butler.service.NavigationService;
import com.duoduo.ai.butler.service.NotificationQueryService;
import com.duoduo.ai.route.AuthLevel;
import com.duoduo.ai.route.ResolvedRoute;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** get_user_notifications 工具（B4）。 */
class GetUserNotificationsToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final NotificationQueryService queryService = mock(NotificationQueryService.class);
    private final NavigationService navigation = mock(NavigationService.class);
    private final GetUserNotificationsTool tool =
            new GetUserNotificationsTool(queryService, navigation, mapper);

    private static final ResolvedRoute ROUTE = new ResolvedRoute(
            "NOTIFICATIONS", "/notifications", "pages/notification/notification", "通知中心", AuthLevel.LOGIN);

    @Test
    @DisplayName("游客：不查数据、返回 needLogin + 站内登录地址")
    void guestNeedsLogin() {
        when(navigation.loginUrl(anyString())).thenReturn("/login?next=%2Fai");

        ToolResult result = tool.execute(guestContext(), mapper.createObjectNode());

        assertThat(result.success()).isFalse();
        assertThat(result.needLogin()).isTrue();
        assertThat(result.loginUrl()).isEqualTo("/login?next=%2Fai");
    }

    @Test
    @DisplayName("登录用户：下发 pageUrl、动作与红点补丁；data 含 markedRead")
    void returnsStructuredResult() {
        NotificationListData data = new NotificationListData(
                List.of(new NotificationItem(11L, "ORDER", "订单已发货", "摘要", "ORDER",
                        "202610291154301234519", true, "2026-10-29T03:00:00Z",
                        "/orders/202610291154301234519", "pages/order-detail/order-detail")),
                5, 3, 2);
        when(queryService.load(any(), any(), any(), anyInt(), anyBoolean())).thenReturn(data);
        when(navigation.route(eq("NOTIFICATIONS"), anyMap())).thenReturn(ROUTE);

        var args = mapper.createObjectNode();
        args.put("category", "ORDER");
        ToolResult result = tool.execute(userContext(), args);

        assertThat(result.success()).isTrue();
        assertThat(result.pageUrl()).isEqualTo("/notifications");
        assertThat(result.actions()).hasSize(1);
        assertThat(result.actions().get(0).miniPath()).isEqualTo("pages/notification/notification");
        assertThat(result.badgePatch()).isNotNull();
        assertThat(result.badgePatch().notification()).isEqualTo(3);
        assertThat(result.data().path("markedRead").asInt()).isEqualTo(2);
        assertThat(result.data().path("items")).hasSize(1);
    }

    @Test
    @DisplayName("参数非法：返回 INVALID_ARGS，而不是 TOOL_ERROR")
    void invalidArgsAreClassified() {
        when(queryService.load(any(), any(), any(), anyInt(), anyBoolean()))
                .thenThrow(new IllegalArgumentException("通知分类不合法"));

        ToolResult result = tool.execute(userContext(), mapper.createObjectNode().put("category", "orders"));

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("INVALID_ARGS");
    }

    private ToolContext guestContext() {
        return new ToolContext(null, "USER", null, "sess", "req", false);
    }

    private ToolContext userContext() {
        return new ToolContext(42L, "USER", null, "sess", "req", true);
    }
}
