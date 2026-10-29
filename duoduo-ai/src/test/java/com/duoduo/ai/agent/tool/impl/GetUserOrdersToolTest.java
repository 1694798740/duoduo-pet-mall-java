package com.duoduo.ai.agent.tool.impl;

import com.duoduo.ai.agent.tool.ToolContext;
import com.duoduo.ai.agent.tool.ToolResult;
import com.duoduo.ai.butler.dto.ButlerViews.OrderItem;
import com.duoduo.ai.butler.dto.ButlerViews.OrderListData;
import com.duoduo.ai.butler.service.NavigationService;
import com.duoduo.ai.butler.service.OrderQueryService;
import com.duoduo.ai.route.AuthLevel;
import com.duoduo.ai.route.ResolvedRoute;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** get_user_orders 工具（B4）：page_url / mini_path 必须来自路由字典。 */
class GetUserOrdersToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OrderQueryService queryService = mock(OrderQueryService.class);
    private final NavigationService navigation = mock(NavigationService.class);
    private final GetUserOrdersTool tool = new GetUserOrdersTool(queryService, navigation, mapper);

    @Test
    @DisplayName("游客：返回 needLogin，不查订单")
    void guestNeedsLogin() {
        when(navigation.loginUrl(anyString())).thenReturn("/login?next=%2Fai");

        ToolResult result = tool.execute(new ToolContext(null, "USER", null, "s", "r", false),
                mapper.createObjectNode());

        assertThat(result.needLogin()).isTrue();
        assertThat(result.loginUrl()).isNotBlank();
    }

    @Test
    @DisplayName("登录用户：列表页带筛选、条目带双端详情地址、不产生红点补丁")
    void injectsRoutePaths() {
        OrderListData data = new OrderListData(List.of(new OrderItem(
                "202610291154301234519", "SHIPPED", "已发货", 15800,
                "皇家猫粮 2kg 等 2 件", "2026-10-29T03:00:00Z",
                "/orders/202610291154301234519",
                "pages/order-detail/order-detail?orderNo=202610291154301234519")), 1, 1, 5);
        when(queryService.load(any(), any(), any(), anyInt())).thenReturn(data);
        when(navigation.route(eq("ORDERS"), anyMap())).thenReturn(new ResolvedRoute(
                "ORDERS", "/orders?status=SHIPPED", "pages/orders/orders", "我的订单", AuthLevel.LOGIN));

        var args = mapper.createObjectNode();
        args.put("status", "shipped");
        ToolResult result = tool.execute(new ToolContext(42L, "USER", null, "s", "r", true), args);

        assertThat(result.success()).isTrue();
        assertThat(result.pageUrl()).isEqualTo("/orders?status=SHIPPED");
        assertThat(result.badgePatch()).isNull();
        assertThat(result.actions()).hasSize(1);
        assertThat(result.data().path("items").get(0).path("amountCents").asLong()).isEqualTo(15800L);
        assertThat(result.data().path("items").get(0).path("miniPath").asText())
                .startsWith("pages/order-detail/");
    }
}
