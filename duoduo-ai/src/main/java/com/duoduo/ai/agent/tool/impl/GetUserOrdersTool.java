package com.duoduo.ai.agent.tool.impl;

import com.duoduo.ai.agent.tool.AgentTool;
import com.duoduo.ai.agent.tool.ToolArgs;
import com.duoduo.ai.agent.tool.ToolContext;
import com.duoduo.ai.agent.tool.ToolResult;
import com.duoduo.ai.agent.tool.ToolSchemas;
import com.duoduo.ai.butler.dto.ButlerViews.OrderListData;
import com.duoduo.ai.butler.service.NavigationService;
import com.duoduo.ai.butler.service.OrderQueryService;
import com.duoduo.ai.dto.ai.AiAction;
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
 * 「我的订单」工具（阶段二）。
 *
 * <p>每条订单的 {@code page_url} / {@code mini_path} 都由 RouteRegistry 注入（见
 * {@link OrderQueryService}），工具层不拼任何地址。
 *
 * <p><b>列表查询不产生 badgePatch</b>：按 Q4，列表只读不写。
 * 若 AI 展示一遍订单就把「未读」清掉，用户会以为交易提醒不见了；
 * 真正的已读发生在用户点进订单详情的那一刻（交易域的
 * {@code POST /api/orders/{orderNo}/read}）。
 */
@Component
public class GetUserOrdersTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(GetUserOrdersTool.class);

    private final OrderQueryService orderQueryService;
    private final NavigationService navigation;
    private final ObjectMapper mapper;

    public GetUserOrdersTool(OrderQueryService orderQueryService,
                             NavigationService navigation, ObjectMapper mapper) {
        this.orderQueryService = orderQueryService;
        this.navigation = navigation;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "get_user_orders";
    }

    @Override
    public String description() {
        return "查询我的订单列表（可按状态/关键词筛选），每条订单都带可直接跳转的 "
                + "Web 详情页与小程序详情页。需要登录；不执行支付、取消、退款等交易动作。";
    }

    @Override
    public JsonNode inputSchema() {
        return ToolSchemas.GET_USER_ORDERS;
    }

    @Override
    public ToolResult execute(ToolContext context, JsonNode args) {
        if (!context.loggedIn() || context.userId() == null) {
            return ToolResult.of(ToolResponse.needLogin(navigation.loginUrl("/ai")), mapper);
        }

        String status = ToolArgs.textUpper(args, "status");
        String keyword = ToolArgs.text(args, "keyword");
        int limit = ToolArgs.intOr(args, "limit", 5, 1, 20);

        try {
            OrderListData data = orderQueryService.load(context.userId(), status, keyword, limit);

            // 带筛选条件时把 status 一起带进列表页；Orders 页读的就是 ?status=
            Map<String, String> routeParams = status == null ? Map.of() : Map.of("status", status);
            ResolvedRoute route = navigation.route("ORDERS", routeParams);
            List<AiAction> actions = List.of(new AiAction(
                    AiAction.TYPE_NAVIGATE, "打开订单列表", route.webPath(), route.miniPath()));

            return ToolResult.of(ToolResponse.ok(data, route.webPath(), actions, null), mapper);
        } catch (IllegalArgumentException e) {
            return ToolResult.fail("INVALID_ARGS", e.getMessage());
        } catch (Exception e) {
            log.warn("订单查询失败 users={}", context.userId(), e);
            return ToolResult.fail("ORDER_QUERY_FAILED", "订单暂时取不到，请稍后再试");
        }
    }
}
