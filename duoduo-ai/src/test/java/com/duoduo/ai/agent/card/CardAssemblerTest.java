package com.duoduo.ai.agent.card;

import com.duoduo.ai.agent.tool.ToolResult;
import com.duoduo.ai.butler.dto.ButlerViews.CouponItem;
import com.duoduo.ai.butler.dto.ButlerViews.CouponListData;
import com.duoduo.ai.butler.dto.ButlerViews.NotificationItem;
import com.duoduo.ai.butler.dto.ButlerViews.NotificationListData;
import com.duoduo.ai.butler.dto.ButlerViews.OrderItem;
import com.duoduo.ai.butler.dto.ButlerViews.OrderListData;
import com.duoduo.ai.butler.dto.ButlerViews.RouteData;
import com.duoduo.ai.dto.ai.AiAction;
import com.duoduo.ai.dto.ai.AiCard;
import com.duoduo.ai.dto.ai.BadgePatch;
import com.duoduo.ai.guardrail.LinkGuard;
import com.duoduo.ai.route.RouteWhitelist;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 卡片装配（B4）。
 *
 * <p>卡片内容必须全部来自工具结果：金额、订单号、跳转地址都不允许模型参与。
 * 这组断言同时保护「失败的工具不出空卡」与「card_refs 是权威清单」两条边界。
 */
class CardAssemblerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CardAssembler assembler =
            new CardAssembler(mapper, new LinkGuard(new RouteWhitelist()));

    @Test
    @DisplayName("四类卡片按 type 装配，并合并两路红点补丁")
    void assemblesAllCardTypesAndMergesBadge() {
        List<CardAssembler.ToolInvocation> invocations = List.of(
                new CardAssembler.ToolInvocation("get_user_notifications",
                        result(notificationData(), "/notifications", List.of(navAction()),
                                new BadgePatch(3, null, null, null))),
                new CardAssembler.ToolInvocation("get_user_orders",
                        result(orderData(), "/orders", List.of(), new BadgePatch(null, 2, null, null))),
                new CardAssembler.ToolInvocation("get_user_coupons",
                        result(couponData(), "/coupons", List.of(), null)),
                new CardAssembler.ToolInvocation("navigate_to_page",
                        result(routeData(), "/orders", List.of(), null)));

        CardAssembler.CardBundle bundle = assembler.assemble(invocations, List.of(), false);

        assertThat(bundle.cards()).hasSize(4);
        assertThat(bundle.cards()).extracting(AiCard::type)
                .containsExactlyInAnyOrder("notification_list", "order_list", "coupon_list", "action_grid");
        assertThat(bundle.badgePatch()).isNotNull();
        assertThat(bundle.badgePatch().notification()).isEqualTo(3);
        assertThat(bundle.badgePatch().order()).isEqualTo(2);

        AiCard.NotificationListCard notice = (AiCard.NotificationListCard) bundle.cards().stream()
                .filter(card -> card.type().equals("notification_list")).findFirst().orElseThrow();
        assertThat(notice.unreadCount()).isEqualTo(3);
        assertThat(notice.items()).hasSize(2);
        // 下游给的是带 Z 的 ISO 串，装配后必须成为可序列化的 LocalDateTime
        assertThat(notice.items().get(0).createdAt()).isNotNull();
        assertThat(notice.items().get(0).routeKey()).isEqualTo("ORDER_DETAIL");

        AiCard.OrderListCard order = (AiCard.OrderListCard) bundle.cards().stream()
                .filter(card -> card.type().equals("order_list")).findFirst().orElseThrow();
        assertThat(order.items().get(0).amountCents()).isEqualTo(15800L);
        assertThat(order.items().get(0).miniPath()).startsWith("pages/");
    }

    @Test
    @DisplayName("failed 工具不出卡片（NEED_LOGIN / TOOL_ERROR 都不能变成空卡）")
    void skipsFailedTools() {
        List<CardAssembler.ToolInvocation> invocations = List.of(
                new CardAssembler.ToolInvocation("get_user_notifications",
                        ToolResult.fail("NEED_LOGIN", "请先登录")),
                new CardAssembler.ToolInvocation("get_user_orders",
                        ToolResult.fail("TOOL_ERROR", "订单暂时取不到")));

        CardAssembler.CardBundle bundle = assembler.assemble(invocations, List.of(), false);

        assertThat(bundle.cards()).isEmpty();
        assertThat(bundle.badgePatch()).isNull();
    }

    @Test
    @DisplayName("card_refs 权威：只装配被引用的那张，未列出的不出现")
    void honorsCardRefs() {
        List<CardAssembler.ToolInvocation> invocations = List.of(
                new CardAssembler.ToolInvocation("get_user_notifications",
                        result(notificationData(), "/notifications", List.of(), null)),
                new CardAssembler.ToolInvocation("get_user_orders",
                        result(orderData(), "/orders", List.of(), null)));

        CardAssembler.CardBundle bundle = assembler.assemble(
                invocations, List.of("get_user_notifications"), true);

        assertThat(bundle.cards()).hasSize(1);
        assertThat(bundle.cards().get(0).type()).isEqualTo("notification_list");
    }

    @Test
    @DisplayName("card_refs 为空数组：明确表示「本轮不要卡片」")
    void emptyRefsMeansNoCards() {
        List<CardAssembler.ToolInvocation> invocations = List.of(
                new CardAssembler.ToolInvocation("get_user_notifications",
                        result(notificationData(), "/notifications", List.of(),
                                new BadgePatch(3, null, null, null))));

        CardAssembler.CardBundle bundle = assembler.assemble(invocations, List.of(), true);

        assertThat(bundle.cards()).isEmpty();
        assertThat(bundle.badgePatch()).isNull();
    }

    // ---------------- fixtures ----------------

    private NotificationListData notificationData() {
        return new NotificationListData(List.of(
                new NotificationItem(11L, "ORDER", "订单已发货", "你的猫粮已发出", "ORDER",
                        "202610291154301234519", false, "2026-10-29T03:00:00Z",
                        "/orders/202610291154301234519", "pages/order-detail/order-detail"),
                new NotificationItem(12L, "SYSTEM", "系统公告", "双十一活动", null, null,
                        true, "2026-10-28T03:00:00Z", null, null)), 5, 3, 2);
    }

    private OrderListData orderData() {
        return new OrderListData(List.of(new OrderItem("202610291154301234519", "SHIPPED",
                "已发货", 15800, "皇家猫粮 2kg 等 2 件", "2026-10-29T03:00:00Z",
                "/orders/202610291154301234519",
                "pages/order-detail/order-detail?orderNo=202610291154301234519")), 1, 1, 5);
    }

    private CouponListData couponData() {
        return new CouponListData(List.of(new CouponItem(9001L, "CPN1", "满 100 减 20",
                "FULL_REDUCTION", "满减券", 10000, 2000, 0, 0, "UNUSED", "未使用",
                "2026-11-20", false, "/coupons", "pages/coupons/coupons")), 1);
    }

    private RouteData routeData() {
        return new RouteData("ORDERS", "我的订单", "/orders", "pages/orders/orders", "LOGIN", false);
    }

    private AiAction navAction() {
        return new AiAction("NAVIGATE", "打开通知中心", "/notifications", "pages/notification/notification");
    }

    private ToolResult result(Object data, String pageUrl, List<AiAction> actions, BadgePatch badge) {
        return new ToolResult(true, "OK", null, mapper.valueToTree(data),
                false, null, pageUrl, actions, badge, false, null);
    }
}
