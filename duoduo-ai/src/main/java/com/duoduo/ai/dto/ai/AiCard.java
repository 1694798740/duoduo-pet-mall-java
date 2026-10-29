package com.duoduo.ai.dto.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 回复里的卡片（面向前端渲染的最小结构化数据）。
 *
 * <p>用 sealed interface + 嵌套 record，而不是 Map/JsonNode 拼装：
 * <ul>
 *   <li>卡片种类是**封闭集合**，编译器能保证穷尽处理，新增一种必须显式改这里；</li>
 *   <li>每张卡片都有 {@code type()} 作为判别字段，前端按它选渲染组件；</li>
 *   <li>{@code type()} 是方法而不是 record 组件，Jackson 默认不会把它当属性输出，
 *       所以四个实现都加了 {@code @JsonProperty("type")} 把方法暴露成 JSON 字段。</li>
 * </ul>
 *
 * <p>本阶段的卡片只描述「展示什么」，不含权限判断与数据加载 ——
 * 数据由阶段二/三的工具装配阶段填充。
 */
public sealed interface AiCard
        permits AiCard.NotificationListCard, AiCard.CouponListCard,
                AiCard.OrderListCard, AiCard.ActionGridCard {

    /** 卡片类型判别字段，取值：notification_list / coupon_list / order_list / action_grid。 */
    @JsonProperty("type")
    String type();

    /** 通知列表卡片。 */
    record NotificationListCard(
            String title,
            Integer unreadCount,
            List<NotificationItem> items,
            List<AiAction> actions
    ) implements AiCard {
        @Override
        @JsonProperty("type")
        public String type() {
            return "notification_list";
        }
    }

    /** 优惠券列表卡片（领券中心 / 我的券包共用）。 */
    record CouponListCard(
            String title,
            List<CouponItem> items,
            List<AiAction> actions
    ) implements AiCard {
        @Override
        @JsonProperty("type")
        public String type() {
            return "coupon_list";
        }
    }

    /** 订单列表卡片。 */
    record OrderListCard(
            String title,
            List<OrderItem> items,
            List<AiAction> actions
    ) implements AiCard {
        @Override
        @JsonProperty("type")
        public String type() {
            return "order_list";
        }
    }

    /** 动作宫格卡片：没有业务数据、只给「去哪儿」的入口集合。 */
    record ActionGridCard(
            String title,
            List<GridItem> items
    ) implements AiCard {
        @Override
        @JsonProperty("type")
        public String type() {
            return "action_grid";
        }
    }

    /**
     * 通知条目。
     * {@code routeKey} 保留路由键而不只给 URL，便于前端按 key 做埋点/二次跳转。
     */
    record NotificationItem(
            Long id,
            String category,
            String title,
            String summary,
            String bizType,
            String bizId,
            Boolean read,
            LocalDateTime createdAt,
            String pageUrl,
            String miniPath,
            String routeKey
    ) {
    }

    /**
     * 优惠券条目。
     * 金额一律整数分（与全站口径一致）；折扣券用 {@code discountPercent}（如 8.5 表示 8.5 折），
     * 折扣券的封顶金额用 {@code maxDiscountAmount}。
     */
    record CouponItem(
            Long templateId,
            String code,
            String name,
            String type,
            String typeLabel,
            Long thresholdAmount,
            Long discountAmount,
            Integer discountPercent,
            Long maxDiscountAmount,
            String status,
            String statusLabel,
            LocalDateTime expireAt,
            Boolean claimable,
            String pageUrl,
            String miniPath
    ) {
    }

    /** 订单条目：只给列表需要的字段，详情走 orderNo 跳转。 */
    record OrderItem(
            String orderNo,
            String status,
            String statusText,
            Long amountCents,
            String itemsSummary,
            LocalDateTime createdAt,
            String pageUrl,
            String miniPath
    ) {
    }

    /** 宫格条目：文案 + 两个平台的跳转目标。 */
    record GridItem(
            String label,
            String url,
            String miniPath
    ) {
    }
}
