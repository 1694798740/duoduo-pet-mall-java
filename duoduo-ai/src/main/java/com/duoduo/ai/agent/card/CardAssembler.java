package com.duoduo.ai.agent.card;

import com.duoduo.ai.agent.tool.ToolResult;
import com.duoduo.ai.butler.dto.ButlerViews.CouponItem;
import com.duoduo.ai.butler.dto.ButlerViews.CouponListData;
import com.duoduo.ai.butler.dto.ButlerViews.NotificationItem;
import com.duoduo.ai.butler.dto.ButlerViews.NotificationListData;
import com.duoduo.ai.butler.dto.ButlerViews.OrderItem;
import com.duoduo.ai.butler.dto.ButlerViews.OrderListData;
import com.duoduo.ai.dto.ai.AiCard;
import com.duoduo.ai.dto.ai.BadgePatch;
import com.duoduo.ai.guardrail.LinkGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 卡片装配器（2026-10-29 · 阶段三）。
 *
 * <h2>唯一的装配入口</h2>
 * 按最终落地文档 Q5：**卡片数据必须由服务端装配，模型只输出 reply + card_refs**。
 * 本类就是那个「装配」的地方：输入是本轮真实执行过的工具调用，
 * 输出是 {@code AiCard} 列表与合并后的红点补丁。
 *
 * <h2>模型能做什么、不能做什么</h2>
 * <ul>
 *   <li>模型只能在 {@code card_refs} 里**引用**已调用成功的工具名；</li>
 *   <li>它引用不到的工具不会被装配（防「模型凭空要求一张订单卡」）；</li>
 *   <li>卡片里的金额、订单号、券 ID 全部来自工具结果，模型没有改写入口。</li>
 * </ul>
 *
 * <h2>失败的工具不出卡片</h2>
 * 工具返回 {@code success=false}（含 NEED_LOGIN / INVALID_ARGS）时，data 本来就是空的；
 * 若仍装配一张空卡片，用户会看到「我的订单（0）」这种误导性内容 —— 直接跳过。
 *
 * <h2>为什么同类卡片只留第一张</h2>
 * 同一轮里模型可能先查「全部订单」再查「待付款订单」。两张订单卡并列会让用户
 * 以为重复了；保留第一次（通常对应最先表述的意图），第二张的筛选结果由正文说明。
 */
@Component
public class CardAssembler {

    private static final Logger log = LoggerFactory.getLogger(CardAssembler.class);

    /** 一屏最多 4 张卡：再多会淹没回复正文，也超出小程序卡片的可视高度。 */
    private static final int MAX_CARDS = 4;

    private final ObjectMapper mapper;
    private final LinkGuard linkGuard;

    public CardAssembler(ObjectMapper mapper, LinkGuard linkGuard) {
        this.mapper = mapper;
        this.linkGuard = linkGuard;
    }

    /**
     * 装配卡片。
     *
     * @param invocations        本轮真实执行过的工具调用（工具名 + 结果）
     * @param cardRefs           模型声明的卡片引用（工具名列表）
     * @param refsAuthoritative  true = 模型成功输出了信封，card_refs 就是权威清单（空即无卡）；
     *                           false = 模型没按格式输出，退回「按成功工具自动装配」
     */
    public CardBundle assemble(List<ToolInvocation> invocations,
                               List<String> cardRefs,
                               boolean refsAuthoritative) {
        if (invocations == null || invocations.isEmpty()) {
            return CardBundle.empty();
        }
        Set<String> refs = cardRefs == null
                ? Set.of()
                : cardRefs.stream()
                        .filter(java.util.Objects::nonNull)
                        .map(v -> v.trim().toLowerCase(Locale.ROOT))
                        .filter(v -> !v.isEmpty())
                        .collect(Collectors.toSet());

        List<AiCard> cards = new ArrayList<>();
        List<AiCard.GridItem> grid = new ArrayList<>();
        BadgePatch badge = null;
        boolean notificationDone = false;
        boolean couponDone = false;
        boolean orderDone = false;

        for (ToolInvocation invocation : invocations) {
            ToolResult result = invocation.result();
            if (result == null || !result.success()) {
                continue;
            }
            String tool = invocation.tool() == null ? "" : invocation.tool().toLowerCase(Locale.ROOT);
            if (refsAuthoritative && !refs.contains(tool)) {
                continue;
            }
            badge = merge(badge, result.badgePatch());

            try {
                switch (tool) {
                    case "get_user_notifications" -> {
                        if (!notificationDone) {
                            cards.add(notificationCard(result));
                            notificationDone = true;
                        }
                    }
                    case "get_user_coupons" -> {
                        if (!couponDone) {
                            cards.add(couponCard(result));
                            couponDone = true;
                        }
                    }
                    case "get_user_orders" -> {
                        if (!orderDone) {
                            cards.add(orderCard(result));
                            orderDone = true;
                        }
                    }
                    case "navigate_to_page" -> {
                        AiCard.GridItem item = gridItem(result);
                        if (item != null) {
                            grid.add(item);
                        }
                    }
                    default -> {
                        // 商品类工具走既有的 recommendations 通道，不在这里装配
                    }
                }
            } catch (RuntimeException e) {
                // 单个卡片装配失败不能拖垮整轮回复：丢掉这张卡，正文照常返回
                log.warn("卡片装配失败 tool={} code={}: {}", tool, result.code(), e.getMessage());
            }
        }

        if (!grid.isEmpty()) {
            cards.add(new AiCard.ActionGridCard("快捷入口", List.copyOf(grid)));
        }
        if (cards.size() > MAX_CARDS) {
            cards = new ArrayList<>(cards.subList(0, MAX_CARDS));
        }
        return new CardBundle(cards, badge);
    }

    // ---------------- 各卡片 ----------------

    private AiCard notificationCard(ToolResult result) {
        NotificationListData data = mapper.convertValue(result.data(), NotificationListData.class);
        List<AiCard.NotificationItem> items = data.items().stream()
                .map(this::toCardItem)
                .toList();
        return new AiCard.NotificationListCard("通知", cap(data.unread()), items, result.actions());
    }

    private AiCard.CouponListCard couponCard(ToolResult result) {
        CouponListData data = mapper.convertValue(result.data(), CouponListData.class);
        List<AiCard.CouponItem> items = data.items().stream()
                .map(v -> new AiCard.CouponItem(
                        v.templateId(), v.code(), linkGuard.sanitizeText(v.name()),
                        v.type(), v.typeLabel(),
                        v.thresholdAmount(), v.discountAmount(), v.discountPercent(),
                        v.maxDiscountAmount(), v.status(), v.statusLabel(),
                        parseTime(v.expireAt()), v.claimable(), v.pageUrl(), v.miniPath()))
                .toList();
        return new AiCard.CouponListCard("我的优惠券", items, result.actions());
    }

    private AiCard.OrderListCard orderCard(ToolResult result) {
        OrderListData data = mapper.convertValue(result.data(), OrderListData.class);
        List<AiCard.OrderItem> items = data.items().stream()
                .map(v -> new AiCard.OrderItem(
                        v.orderNo(), v.status(), v.statusText(), v.amountCents(),
                        v.itemsSummary() == null ? null : linkGuard.sanitizeText(v.itemsSummary()),
                        parseTime(v.createdAt()), v.pageUrl(), v.miniPath()))
                .toList();
        return new AiCard.OrderListCard("我的订单", items, result.actions());
    }

    private AiCard.GridItem gridItem(ToolResult result) {
        JsonNode data = result.data();
        if (data == null || data.isNull()) {
            return null;
        }
        String label = data.path("label").asText("");
        String web = result.pageUrl();
        String mini = data.path("miniPath").asText(null);
        if (label.isBlank() || web == null) {
            return null;
        }
        return new AiCard.GridItem(label, web, mini);
    }

    private AiCard.NotificationItem toCardItem(NotificationItem v) {
        return new AiCard.NotificationItem(
                v.id(), v.category(),
                linkGuard.sanitizeText(v.title()),
                v.summary() == null ? null : linkGuard.sanitizeText(v.summary()),
                v.bizType(), v.bizId(),
                v.read(), parseTime(v.createdAt()), v.pageUrl(), v.miniPath(),
                routeKey(v.pageUrl()));
    }

    // ---------------- 工具方法 ----------------

    /**
     * 红点补丁合并：同一路角标多次出现时**后写覆盖**（后一次是更新的读数），
     * 没出现的那一路保持前值；全为 null 时整体为 null，前端据此判断「没有变化」。
     */
    private static BadgePatch merge(BadgePatch current, BadgePatch next) {
        if (next == null) {
            return current;
        }
        if (current == null) {
            return next;
        }
        return new BadgePatch(
                next.notification() != null ? next.notification() : current.notification(),
                next.order() != null ? next.order() : current.order(),
                next.afterSale() != null ? next.afterSale() : current.afterSale(),
                next.support() != null ? next.support() : current.support());
    }

    /**
     * ISO-8601 → LocalDateTime。下游给的是 UTC 带 Z/偏移的字符串
     * （{@code 2026-10-29T03:00:00Z}），也可能是没有偏移的本地时间。
     * 解析失败返回 null：卡片少一个时间字段，总好过整张卡装配失败。
     */
    private static LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        try {
            return OffsetDateTime.parse(text).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(text);
            } catch (DateTimeParseException e) {
                return null;
            }
        }
    }

    /**
     * 由落地页反推路由键：卡片需要 {@code routeKey} 做埋点与二次跳转，
     * 而工具结果里只有 URL。这里只做「前缀 → 键」的固定映射，
     * 不解析参数、不做匹配猜测 —— 认不出来就返回 null，前端按 URL 展示即可。
     */
    private static String routeKey(String pageUrl) {
        if (pageUrl == null) {
            return null;
        }
        if (pageUrl.startsWith("/orders/")) {
            return "ORDER_DETAIL";
        }
        if (pageUrl.startsWith("/orders")) {
            return "ORDERS";
        }
        if (pageUrl.startsWith("/after-sales/")) {
            return "AFTER_SALE_DETAIL";
        }
        if (pageUrl.startsWith("/after-sales")) {
            return "AFTER_SALES";
        }
        if (pageUrl.startsWith("/messages")) {
            return "MESSAGES";
        }
        if (pageUrl.startsWith("/support")) {
            return "SUPPORT";
        }
        if (pageUrl.startsWith("/notifications")) {
            return "NOTIFICATIONS";
        }
        if (pageUrl.startsWith("/coupons")) {
            return "COUPONS";
        }
        return null;
    }

    private static Integer cap(long value) {
        return (int) Math.min(Math.max(value, 0), Integer.MAX_VALUE);
    }

    /** 本轮执行过的一次工具调用（工具名 + 结果）。 */
    public record ToolInvocation(String tool, ToolResult result) {
    }

    /**
     * 装配结果。
     *
     * @param cards      要下发的卡片（可能为空列表）
     * @param badgePatch 合并后的红点补丁；null 表示本轮没有任何角标变化
     */
    public record CardBundle(List<AiCard> cards, BadgePatch badgePatch) {

        public CardBundle {
            cards = cards == null ? List.of() : List.copyOf(cards);
        }

        public static CardBundle empty() {
            return new CardBundle(List.of(), null);
        }
    }
}
