package com.duoduo.ai.agent.tool.impl;

import com.duoduo.ai.agent.tool.ToolContext;
import com.duoduo.ai.agent.tool.ToolResult;
import com.duoduo.ai.butler.dto.ButlerViews.CouponItem;
import com.duoduo.ai.butler.dto.ButlerViews.CouponListData;
import com.duoduo.ai.butler.service.CouponQueryService;
import com.duoduo.ai.butler.service.NavigationService;
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

/** get_user_coupons 工具（B4）：金额必须原样透传，不允许 AI 侧换算。 */
class GetUserCouponsToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CouponQueryService queryService = mock(CouponQueryService.class);
    private final NavigationService navigation = mock(NavigationService.class);
    private final GetUserCouponsTool tool = new GetUserCouponsTool(queryService, navigation, mapper);

    private static final ResolvedRoute ROUTE = new ResolvedRoute(
            "COUPONS", "/coupons", "pages/coupons/coupons", "领券中心", AuthLevel.PUBLIC);

    @Test
    @DisplayName("游客：返回 needLogin，不查券包")
    void guestNeedsLogin() {
        when(navigation.loginUrl(anyString())).thenReturn("/login?next=%2Fai");

        ToolResult result = tool.execute(new ToolContext(null, "USER", null, "s", "r", false),
                mapper.createObjectNode());

        assertThat(result.needLogin()).isTrue();
        assertThat(result.loginUrl()).isNotBlank();
    }

    @Test
    @DisplayName("登录用户：模板 ID / 门槛 / 抵扣 / 折率原样下发，无红点补丁")
    void returnsRawAmounts() {
        CouponListData data = new CouponListData(List.of(
                new CouponItem(9001L, "CPN1", "满 100 减 20", "FULL_REDUCTION", "满减券",
                        10000, 2000, 0, 0, "UNUSED", "未使用", "2026-11-20", false,
                        "/coupons", "pages/coupons/coupons"),
                new CouponItem(9002L, "CPN2", "8.5 折券", "DISCOUNT", "折扣券",
                        0, 0, 85, 5000, "UNUSED", "未使用", "2026-11-21", false,
                        "/coupons", "pages/coupons/coupons")), 2);
        when(queryService.load(any(), any(), anyInt())).thenReturn(data);
        when(navigation.route(eq("COUPONS"), anyMap())).thenReturn(ROUTE);

        ToolResult result = tool.execute(new ToolContext(42L, "USER", null, "s", "r", true),
                mapper.createObjectNode());

        assertThat(result.success()).isTrue();
        assertThat(result.pageUrl()).isEqualTo("/coupons");
        assertThat(result.badgePatch()).isNull();
        assertThat(result.data().path("items")).hasSize(2);
        assertThat(result.data().path("items").get(0).path("templateId").asLong()).isEqualTo(9001L);
        assertThat(result.data().path("items").get(0).path("thresholdAmount").asLong()).isEqualTo(10000L);
        assertThat(result.data().path("items").get(1).path("discountPercent").asInt()).isEqualTo(85);
    }
}
