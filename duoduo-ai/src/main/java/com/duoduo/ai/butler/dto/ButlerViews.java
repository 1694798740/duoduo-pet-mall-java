package com.duoduo.ai.butler.dto;

import java.util.List;

/**
 * 全能管家工具的 data 载荷（阶段二）。
 *
 * <p><b>为什么这里不直接用 {@code AiCard}</b>：卡片是「回复层」的产物，
 * 由卡片装配阶段按 card_refs 生成；工具层只负责给出**事实**。
 * 两层分开后，同一个工具结果既能装配成卡片，也能被后续的
 * 「红点补丁」「已读回执」复用，不必反序列化卡片来取数据。
 *
 * <p>字段名与 {@code AiCard} 的同名条目保持一致，装配阶段是纯字段搬运，
 * 不做二次语义推断（那正是「AI 说错金额」的来源）。
 */
public final class ButlerViews {

    private ButlerViews() {
    }

    // ---------------- 通知 ----------------

    /**
     * 通知条目。
     *
     * @param summary  正文摘要（截断），完整正文留在通知中心
     * @param createdAt ISO-8601 UTC 字符串，装配成卡片时再转 LocalDateTime
     * @param pageUrl   Web 落地页；null 表示网页端没有对应页面（只展示、不跳转）
     * @param miniPath  小程序页面路径（已去掉前导 /，可直接 wx.navigateTo）
     */
    public record NotificationItem(
            Long id,
            String category,
            String title,
            String summary,
            String bizType,
            String bizId,
            boolean read,
            String createdAt,
            String pageUrl,
            String miniPath
    ) {
    }

    /**
     * 通知列表载荷。
     *
     * @param total      命中筛选条件的总条数（不是本次返回条数）
     * @param unread     当前通知未读数（不含私信/客服，与角标同口径）
     * @param markedRead 本次实际标记已读的条数；0 表示没有改变任何状态
     */
    public record NotificationListData(
            List<NotificationItem> items,
            long total,
            long unread,
            int markedRead
    ) {
        public NotificationListData {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    // ---------------- 优惠券 ----------------

    /**
     * 券条目：金额全部是整数分，直接来自营销域，AI 侧不做换算。
     *
     * @param claimable 我的券包里恒为 false（已领取的券不能再领）；
     *                  保留该字段是为了与卡片契约对齐，将来领券中心卡片复用同一结构
     */
    public record CouponItem(
            Long templateId,
            String code,
            String name,
            String type,
            String typeLabel,
            long thresholdAmount,
            long discountAmount,
            int discountPercent,
            long maxDiscountAmount,
            String status,
            String statusLabel,
            String expireAt,
            boolean claimable,
            String pageUrl,
            String miniPath
    ) {
    }

    /** 券包载荷：total 是筛选后的总数，items 只取前 limit 条。 */
    public record CouponListData(List<CouponItem> items, int total) {
        public CouponListData {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    // ---------------- 订单 ----------------

    /**
     * 订单条目。
     *
     * @param amountCents  实付金额（分）= payAmount，不是商品总额
     * @param itemsSummary 形如「皇家猫粮 2kg 等 3 件」，供卡片一行展示
     * @param pageUrl      由 RouteRegistry 注入的 Web 详情页
     * @param miniPath     由 RouteRegistry 注入的小程序详情页
     */
    public record OrderItem(
            String orderNo,
            String status,
            String statusText,
            long amountCents,
            String itemsSummary,
            String createdAt,
            String pageUrl,
            String miniPath
    ) {
    }

    /** 订单列表载荷。 */
    public record OrderListData(List<OrderItem> items, long total, int page, int pageSize) {
        public OrderListData {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    // ---------------- 导航 ----------------

    /**
     * 路由载荷：只暴露「可展示、可跳转」的字段。
     *
     * @param auth       PUBLIC / LOGIN，前端据此决定是否先弹登录
     * @param needLogin  当前请求者是否因未登录而不能直达（已登录时为 false）
     */
    public record RouteData(
            String feature,
            String label,
            String webPath,
            String miniPath,
            String auth,
            boolean needLogin
    ) {
    }
}
