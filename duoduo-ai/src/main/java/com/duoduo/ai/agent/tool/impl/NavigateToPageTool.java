package com.duoduo.ai.agent.tool.impl;

import com.duoduo.ai.agent.tool.AgentTool;
import com.duoduo.ai.agent.tool.ToolArgs;
import com.duoduo.ai.agent.tool.ToolContext;
import com.duoduo.ai.agent.tool.ToolResult;
import com.duoduo.ai.agent.tool.ToolSchemas;
import com.duoduo.ai.butler.dto.ButlerViews.RouteData;
import com.duoduo.ai.butler.service.NavigationService;
import com.duoduo.ai.dto.ai.AiAction;
import com.duoduo.ai.dto.ai.ToolResponse;
import com.duoduo.ai.route.AuthLevel;
import com.duoduo.ai.route.ResolvedRoute;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 「带我去某个页面」工具（阶段二）。
 *
 * <p><b>模型永远不生成 URL</b>：它只能给一个 feature 键，路径由 RouteRegistry 拼、
 * 由 RouteWhitelist 校验。这样即使提示词被注入，模型能做的也只是「请求一个已登记的功能」，
 * 不可能把用户导去 {@code https://evil}。
 *
 * <p><b>需要登录的页面不硬跳</b>：LOGIN 级别的功能在未登录时返回 NEED_LOGIN + loginUrl
 * （带 next，登录后回到目标页），让前端决定弹登录框 —— 直接把用户丢到登录墙，
 * 他既不知道为什么要登录，也找不回原来的意图。
 *
 * <p><b>未知 feature 要回清单</b>：模型自造键名是常态。失败的 message 里带上
 * 可用键列表，它下一轮就能自己纠正，而不是反复猜同一个错名字。
 */
@Component
public class NavigateToPageTool implements AgentTool {

    private final NavigationService navigation;
    private final ObjectMapper mapper;

    public NavigateToPageTool(NavigationService navigation, ObjectMapper mapper) {
        this.navigation = navigation;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "navigate_to_page";
    }

    @Override
    public String description() {
        return "把用户带到站内某个功能页。只能传服务端已登记的功能键与它的路径参数，"
                + "服务端返回 Web 路径与小程序路径；不会打开站外链接。";
    }

    @Override
    public JsonNode inputSchema() {
        return ToolSchemas.NAVIGATE_TO_PAGE;
    }

    @Override
    public ToolResult execute(ToolContext context, JsonNode args) {
        String feature = ToolArgs.textUpper(args, "feature");
        if (feature == null) {
            return ToolResult.fail("INVALID_ARGS", "缺少 feature 参数");
        }
        Map<String, String> params = ToolArgs.stringMap(args.path("params"));

        ResolvedRoute route;
        try {
            route = navigation.route(feature, params);
        } catch (IllegalArgumentException e) {
            // 参数缺失 / 越权地址 / 未知键都在这里被白名单挡下，把可用键回给模型便于纠正
            return ToolResult.fail("ROUTE_INVALID",
                    e.getMessage() + "；可用功能：" + navigation.describeFeatures());
        }

        if (route.auth() == AuthLevel.LOGIN && !context.loggedIn()) {
            return ToolResult.of(ToolResponse.needLogin(navigation.loginUrl(route.webPath())), mapper);
        }

        RouteData data = new RouteData(
                route.key(), route.label(), route.webPath(), route.miniPath(),
                route.auth().name(), false);
        List<AiAction> actions = List.of(new AiAction(
                AiAction.TYPE_NAVIGATE, "打开" + route.label(), route.webPath(), route.miniPath()));

        return ToolResult.of(ToolResponse.ok(data, route.webPath(), actions, null), mapper);
    }
}
