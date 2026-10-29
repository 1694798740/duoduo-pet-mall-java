package com.duoduo.ai.agent.tool.impl;

import com.duoduo.ai.agent.tool.ToolContext;
import com.duoduo.ai.agent.tool.ToolResult;
import com.duoduo.ai.butler.service.NavigationService;
import com.duoduo.ai.route.RouteRegistry;
import com.duoduo.ai.route.RouteWhitelist;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * navigate_to_page 工具（B4）。
 *
 * <p>这组断言是「模型不能生成 URL」这条硬约束的直接证据：
 * 它只能给功能键，路径由注册表拼、由白名单校验；外链与未知键都必须被拒绝。
 */
class NavigateToPageToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RouteWhitelist whitelist = new RouteWhitelist();
    private final RouteRegistry registry = new RouteRegistry(whitelist);
    private final NavigationService navigation = new NavigationService(registry, whitelist);
    private final NavigateToPageTool tool = new NavigateToPageTool(navigation, mapper);

    @Test
    @DisplayName("游客打开需要登录的页面：返回 needLogin + 站内 next，而不是硬跳")
    void guestGetsLoginUrl() {
        ToolResult result = tool.execute(guest(), mapper.createObjectNode().put("feature", "ORDERS"));

        assertThat(result.success()).isFalse();
        assertThat(result.needLogin()).isTrue();
        assertThat(result.loginUrl()).isEqualTo("/login?next=%2Forders");
    }

    @Test
    @DisplayName("登录用户：返回双端路径与可点击动作")
    void loggedInGetsRoute() {
        ToolResult result = tool.execute(user(), mapper.createObjectNode().put("feature", "orders"));

        assertThat(result.success()).isTrue();
        assertThat(result.pageUrl()).isEqualTo("/orders");
        assertThat(result.data().path("miniPath").asText()).isEqualTo("pages/orders/orders");
        assertThat(result.actions()).hasSize(1);
        assertThat(result.actions().get(0).url()).isEqualTo("/orders");
    }

    @Test
    @DisplayName("未知功能键：拒绝并回可用清单，便于模型纠正")
    void unknownFeatureRejected() {
        ToolResult result = tool.execute(user(), mapper.createObjectNode().put("feature", "MY_WALLET"));

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("ROUTE_INVALID");
        assertThat(result.message()).contains("HOME");
    }

    @Test
    @DisplayName("缺少路径参数：拒绝（不能拼出半截地址）")
    void missingParamRejected() {
        ToolResult result = tool.execute(user(), mapper.createObjectNode().put("feature", "PRODUCT_DETAIL"));

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("ROUTE_INVALID");
        assertThat(result.message()).contains("PARAM_MISSING");
    }

    @Test
    @DisplayName("外链 next：被白名单拒绝")
    void externalNextRejected() {
        var args = mapper.createObjectNode().put("feature", "LOGIN");
        args.putObject("params").put("next", "https://evil.com");

        ToolResult result = tool.execute(user(), args);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("ROUTE_INVALID");
    }

    private ToolContext guest() {
        return new ToolContext(null, "USER", null, "s", "r", false);
    }

    private ToolContext user() {
        return new ToolContext(42L, "USER", null, "s", "r", true);
    }
}
