package com.duoduo.ai.butler.service;

import com.duoduo.ai.butler.dto.ButlerViews.OrderItem;
import com.duoduo.ai.butler.dto.ButlerViews.OrderListData;
import com.duoduo.ai.client.OrderClient;
import com.duoduo.ai.client.dto.ButlerOrderDtos.OrderItemView;
import com.duoduo.ai.client.dto.ButlerOrderDtos.OrderPage;
import com.duoduo.ai.client.dto.ButlerOrderDtos.OrderView;
import com.duoduo.ai.route.ResolvedRoute;
import com.duoduo.common.api.ApiResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 订单查询服务（阶段二 · get_user_orders 的业务实现）。
 *
 * <p><b>page_url / mini_path 由路由字典注入</b>：每条订单都通过
 * {@link NavigationService#route} 解析 {@code ORDER_DETAIL}，
 * 拿到的是**已过白名单校验**的 Web 与小程序路径。
 * 不让调用方自己拼 {@code /orders/ + orderNo} 的原因：拼链接的地方多一处，
 * 就多一处「模型给个假订单号也能拼出链接」的机会；而白名单还会校验订单号形状（21 位纯数字），
 * 格式不对的订单号在这里就会被拒绝，而不是让用户点进一个 404。
 *
 * <p><b>列表查询不清红点</b>：按最终方案 Q4，列表只读不写。
 * 「看过某笔订单」的角标消除发生在订单详情页（{@code POST /api/orders/{orderNo}/read}），
 * 由用户真正进入详情时触发 —— AI 转述一遍列表不等于用户看过了每一单。
 */
@Service
public class OrderQueryService {

    private static final int MAX_LIMIT = 20;
    private static final int KEYWORD_MAX = 64;

    private static final Set<String> STATUSES = Set.of(
            "PENDING_PAYMENT", "PAID", "SHIPPED", "COMPLETED", "CLOSED", "REFUNDED");

    private final OrderClient orderClient;
    private final NavigationService navigation;

    public OrderQueryService(OrderClient orderClient, NavigationService navigation) {
        this.orderClient = orderClient;
        this.navigation = navigation;
    }

    /**
     * 查询我的订单。
     *
     * @param status  PENDING_PAYMENT / PAID / SHIPPED / COMPLETED / CLOSED / REFUNDED；null = 全部
     * @param keyword 订单号或商品标题关键词（null = 不过滤）
     * @param limit   返回条数上限（1~20）
     */
    public OrderListData load(Long userId, String status, String keyword, int limit) {
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        String normalizedStatus = normalizeStatus(status);
        String normalizedKeyword = normalizeKeyword(keyword);

        OrderPage page = unwrap(orderClient.list(userId, normalizedStatus, normalizedKeyword, 1, size),
                "订单列表");
        List<OrderItem> items = page.items() == null ? List.of() : page.items().stream()
                .map(this::toItem)
                .toList();
        return new OrderListData(items, page.total(), page.page(), page.pageSize());
    }

    private OrderItem toItem(OrderView view) {
        // 双端路径必须成对来自路由表：任何一端单独拼都会在另一端的平台上 404
        ResolvedRoute detail = navigation.route("ORDER_DETAIL", Map.of("orderNo", view.orderNo()));
        return new OrderItem(
                view.orderNo(),
                view.status(),
                view.statusLabel(),
                view.payAmount(),
                summarize(view.items()),
                view.createdAt(),
                detail.webPath(),
                detail.miniPath());
    }

    /**
     * 商品摘要：「第一个商品标题 等 N 件」。
     *
     * <p>件数按 quantity 求和而不是按行数：一行 3 件的订单说「等 1 件」是错的，
     * 用户会拿这个数字去和详情页核对。
     */
    private static String summarize(List<OrderItemView> items) {
        if (items == null || items.isEmpty()) {
            return "（无商品明细）";
        }
        String first = items.get(0).title();
        if (first == null || first.isBlank()) {
            first = "商品";
        }
        int quantity = items.stream()
                .mapToInt(item -> Math.max(1, item.quantity()))
                .sum();
        return quantity <= 1 ? first : first + " 等 " + quantity + " 件";
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String value = status.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(value)) {
            throw new IllegalArgumentException("订单状态不合法：" + status);
        }
        return value;
    }

    /** 关键词只接受真正的检索词：超长（模型把整句话塞进来）直接拒绝，提示它改用短词。 */
    private static String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String value = keyword.trim();
        if (value.length() > KEYWORD_MAX) {
            throw new IllegalArgumentException("订单关键词不能超过 " + KEYWORD_MAX + " 字");
        }
        return value;
    }

    private static <T> T unwrap(ApiResponse<T> response, String what) {
        if (response == null || response.data() == null) {
            throw new IllegalStateException(what + "返回空数据");
        }
        return response.data();
    }
}
