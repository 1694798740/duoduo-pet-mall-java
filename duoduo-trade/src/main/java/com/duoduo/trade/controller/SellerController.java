package com.duoduo.trade.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.BatchDtos;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.web.CsvExport;
import com.duoduo.trade.dto.AnalyticsDtos.SellerAnalytics;
import com.duoduo.trade.dto.OrderDtos.OrderView;
import com.duoduo.trade.dto.OrderDtos.RefundRequest;
import com.duoduo.trade.dto.OrderDtos.RefundResult;
import com.duoduo.trade.dto.SellerDtos.BatchShipRequest;
import com.duoduo.trade.dto.SellerDtos.SellerSummary;
import com.duoduo.trade.dto.SettlementDtos.SellerWalletView;
import com.duoduo.trade.dto.SettlementDtos.SettlementBill;
import com.duoduo.trade.dto.SettlementDtos.SettlementView;
import com.duoduo.trade.dto.SellerDtos.SettlementPage;
import com.duoduo.trade.dto.SellerDtos.ShipRequest;
import com.duoduo.trade.service.SellerService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 卖家中心接口（需登录 + 卖家角色）。
 *
 * <p>路径由网关按 {@code /api/seller/**} 路由到本服务；网关已完成鉴权，
 * 服务端从 {@code X-User-*} 取身份与角色，<b>请求体与路径里都没有 sellerId</b>。
 *
 * <p>控制器只做「参数绑定 + 调服务 + 包响应」；准入与归属校验都在
 * {@link SellerService} 里 —— 校验与业务动作必须相邻，否则新增入口时极易漏掉。
 */
@RestController
@RequestMapping("/api/seller")
public class SellerController {

    private final SellerService sellerService;

    public SellerController(SellerService sellerService) {
        this.sellerService = sellerService;
    }

    /**
     * 商家端概览：订单状态分布 + 资金三栏。
     *
     * <p>同时充当「我有没有权限进商家端」的探测接口：非卖家会拿到 403，
     * 前端据此引导去申请成为卖家，而不是进了一个到处都是 403 的空页面。
     */
    @GetMapping("/summary")
    public ApiResponse<SellerSummary> summary() {
        return ApiResponse.ok(sellerService.summary());
    }

    /**
     * 商家端多维度统计（图表用）。
     *
     * <p>与 {@code /summary} 分开是刻意的：统计卡比图表重要得多，
     * 图表取数异常不该把卡片一起拖下水。前端两个请求各自降级。
     */
    @GetMapping("/analytics")
    public ApiResponse<SellerAnalytics> analytics() {
        return ApiResponse.ok(sellerService.analytics());
    }

    /** 按商品的经营统计（2026-10-24）：销量 / 成交额 / 退款件数，商家端商品分析表用 */
    @GetMapping("/product-stats")
    public ApiResponse<java.util.List<com.duoduo.trade.dto.AnalyticsDtos.ProductStat>> productStats() {
        return ApiResponse.ok(sellerService.productStats());
    }

    /** 商品分析导出列（2026-10-28 · 矩阵 P3） */
    private static final java.util.List<String> PRODUCT_STATS_EXPORT_HEADERS = java.util.List.of(
            "商品ID", "商品名称", "成交件数", "成交金额(元)", "成交订单数", "退款件数", "退款金额(元)");

    /**
     * 导出商品分析 CSV：销量 / 成交额 / 退款件数 / 退款金额，按商品汇总。
     *
     * <p>与页面同口径：数据来自订单明细，退款不从销量里扣减；导出上限 5000 个商品。
     */
    @GetMapping("/product-stats/export")
    public void exportProductStats(HttpServletResponse response) throws java.io.IOException {
        CsvExport.write(response, "商品分析-" + CsvExport.stamp(), PRODUCT_STATS_EXPORT_HEADERS,
                sellerService.exportProductStatsRows());
    }

    /**
     * 按商品 / SKU 的退款统计（D-05）：退款件数 / 退款金额 / 涉及订单数，商家端售后页用。
     *
     * <p>只列有退款明细的 SKU，最多 200 条；口径来自订单明细的 {@code refund_status}，
     * 不按售后单金额分摊（售后单是订单级的，拆不到单品）。
     */
    @GetMapping("/product-refunds")
    public ApiResponse<java.util.List<com.duoduo.trade.dto.AnalyticsDtos.ProductRefundStat>> productRefunds() {
        return ApiResponse.ok(sellerService.productRefundStats());
    }

    /**
     * 卖出的订单列表。
     *
     * @param status  状态过滤（PENDING_PAYMENT / PAID / SHIPPED / COMPLETED / REFUNDED / CLOSED）
     * @param keyword 订单号或商品标题（包含匹配）
     */
    @GetMapping("/orders")
    public ApiResponse<PageResult<OrderView>> listOrders(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(sellerService.listOrders(status, keyword, page, pageSize));
    }

    /**
     * 订单导出（2026-10-28 · 导入导出第一批）：按当前筛选流式下载 CSV。
     *
     * <p>筛选参数与列表完全同名（{@code status / keyword}），前端只需把当前 URL 参数
     * 原样搬到导出地址，导出的就是「我眼前这一份」。
     */
    @GetMapping("/orders/export")
    public void exportOrders(@RequestParam(required = false) String status,
                             @RequestParam(required = false) String keyword,
                             HttpServletResponse response) throws java.io.IOException {
        java.util.List<OrderView> orders = sellerService.exportOrders(status, keyword);
        CsvExport.write(response, "商家订单-" + CsvExport.stamp(), ORDER_HEADERS,
                orders.stream().map(SellerController::orderRow).toList());
    }

    /**
     * 订单详情（卖家视角）。
     *
     * <p>2026-10-27 加订单号形状约束（21 位纯数字，与买家端 {@code OrderController} 同款）：
     * 不加约束时，{@code /orders/status-counts} 会被这里的 {@code {orderNo}} 捕获，
     * 然后倒在 Long 转换或「订单不存在」上 —— 表现为新端点静默 404。
     */
    @GetMapping("/orders/{orderNo:\\d{21}}")
    public ApiResponse<OrderView> orderDetail(@PathVariable String orderNo) {
        return ApiResponse.ok(sellerService.detail(orderNo));
    }

    /**
     * 卖家端订单页签角标（2026-10-27）：已发货/已完成/已关闭/已退款读过的会减掉。
     *
     * <p>与 {@code /summary} 分开：summary 是经营存量（不随看不看变化），这个是未读提醒。
     */
    @GetMapping("/orders/status-counts")
    public ApiResponse<java.util.List<com.duoduo.common.api.StatusCount>> orderStatusCounts() {
        return ApiResponse.ok(sellerService.orderStatusCounts());
    }

    /**
     * 卖家「看过某订单」上报（2026-10-27 · 阅后即消）。
     *
     * <p>幂等；返回最新分桶供前端覆盖本地乐观值，避免并发下停在错的数字上。
     */
    @PostMapping("/orders/{orderNo}/read")
    public ApiResponse<java.util.List<com.duoduo.common.api.StatusCount>> markOrderRead(
            @PathVariable String orderNo) {
        return ApiResponse.ok(sellerService.markOrderRead(orderNo));
    }

    /**
     * 卖家发货（已付款 → 已发货），物流信息可选。
     *
     * <p>发货后资金<b>仍在托管中</b>，放款要等买家确认收货。
     * 这个接口与运营侧的内部凭据接口共用同一段状态机，区别只在准入方式。
     */
    @PostMapping("/orders/{orderNo}/ship")
    public ApiResponse<OrderView> ship(@PathVariable String orderNo,
                                       @Valid @RequestBody(required = false) ShipRequest request) {
        return ApiResponse.ok(sellerService.ship(orderNo, request));
    }

    /**
     * 批量发货（2026-10-27 · 批量操作即已读）。
     *
     * <p>逐条结果 + 操作后的最新订单分桶：成功条目服务端写店铺已读，
     * 所以「已发货」不会因为自己的批量操作而新增未读。
     * 路径是字面量，不会与 {@code /orders/{orderNo:\d{21}}} 冲突。
     */
    @PostMapping("/orders/batch-ship")
    public ApiResponse<BatchDtos.Result> batchShip(@Valid @RequestBody BatchShipRequest request) {
        return ApiResponse.ok(sellerService.shipBatch(request));
    }

    /**
     * 卖家售后退款：已付款 / 已发货的订单都可以（前提是卖家已确认收到退货）。
     *
     * <p>已完成的订单退不了 —— 钱已经放款给卖家，
     * 那属于售后追偿 / 平台垫付，是另一条尚未实现的流程。
     */
    @PostMapping("/orders/{orderNo}/refund")
    public ApiResponse<RefundResult> refund(@PathVariable String orderNo,
                                            @Valid @RequestBody(required = false) RefundRequest request) {
        String reason = request == null ? null : request.reason();
        return ApiResponse.ok(sellerService.refund(orderNo, reason));
    }

    /**
     * 我的资金托管单（对账），附全量汇总。
     *
     * <p>status 可选 CUSTODY（托管中）/ RELEASED（已放款）/ REFUNDED（已退回买家）；
     * keyword 按订单号过滤；两者都<b>不影响</b>汇总数字，理由见服务层注释。
     */
    @GetMapping("/settlements")
    public ApiResponse<SettlementPage> settlements(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(sellerService.settlements(status, keyword, page, pageSize));
    }

    /**
     * 结算台账导出（2026-10-28 · 导入导出第一批）：按当前筛选导出逐笔托管明细。
     *
     * <p>与列表同筛选；文件按时间正序（账表口径），金额单位为元、两位小数。
     */
    @GetMapping("/settlements/export")
    public void exportSettlements(@RequestParam(required = false) String status,
                                  @RequestParam(required = false) String keyword,
                                  HttpServletResponse response) throws java.io.IOException {
        java.util.List<SettlementView> rows = sellerService.exportSettlements(status, keyword);
        CsvExport.write(response, "结算台账-" + CsvExport.stamp(), SETTLEMENT_HEADERS,
                rows.stream().map(SellerController::settlementRow).toList());
    }

    /**
     * E22 · 我的钱包：待结算 / 冻结中 / 可提现 / 累计到账 / 欠款。
     *
     * <p>与 {@link #settlements} 的分工：那个是明细台账（"每笔钱在哪"），
     * 这个是**一个数**（"我现在能取多少"）。口径与服务层同源，
     * 前端不要从明细里自己加总 —— 冻结规则一变，自己加的总就会与提现结果对不上。
     */
    @GetMapping("/settlements/wallet")
    public ApiResponse<SellerWalletView> wallet() {
        return ApiResponse.ok(sellerService.wallet());
    }

    /**
     * E22 · 我的结算账单（租户 × 周期）。
     *
     * <p>{@code period} 形如 {@code 2026-10}（整月）或 {@code 2026-10-05}（单日）；
     * 不传 = 当前月。格式非法返回 400 而不是回落到当前月 ——
     * 回落的后果是"我查 9 月、系统给我 10 月"，且不报错。
     *
     * <p>与钱包接口的关系见 {@code SellerService.bill}：账单快照段直接复用钱包口径。
     */
    @GetMapping("/settlements/bills")
    public ApiResponse<SettlementBill> bill(@RequestParam(required = false) String period) {
        return ApiResponse.ok(sellerService.bill(period));
    }

    // ---------------- CSV 导出列定义 ----------------

    private static final java.util.List<String> ORDER_HEADERS = java.util.List.of(
            "订单号", "店铺", "状态", "商品件数", "商品总额(元)", "运费(元)", "优惠(元)", "实付(元)",
            "收货人", "收货电话", "物流公司", "物流单号", "下单时间", "发货时间", "完成时间");

    private static final java.util.List<String> SETTLEMENT_HEADERS = java.util.List.of(
            "订单号", "商品金额(元)", "平台佣金(元)", "多多币补贴(元)", "卖家应得(元)",
            "状态", "解冻时间", "放款时间", "创建时间");

    /** 订单 → CSV 行；未装配的字段按空串出，导出不因单个缺失字段整份失败 */
    private static java.util.List<String> orderRow(OrderView order) {
        int itemCount = order.items() == null ? 0
                : order.items().stream().mapToInt(com.duoduo.trade.dto.OrderDtos.OrderItemView::quantity).sum();
        return java.util.List.of(
                text(order.orderNo()),
                text(order.storeName()),
                text(order.statusLabel()),
                String.valueOf(itemCount),
                CsvExport.yuan(order.totalAmount()),
                CsvExport.yuan(order.freightAmount()),
                CsvExport.yuan(order.discountAmount()),
                CsvExport.yuan(order.payAmount()),
                text(order.receiverName()),
                text(order.receiverPhone()),
                text(order.logisticsCompany()),
                text(order.logisticsNo()),
                CsvExport.beijingTime(order.createdAt()),
                CsvExport.beijingTime(order.shippedAt()),
                CsvExport.beijingTime(order.finishedAt()));
    }

    /** 结算行 → CSV 行 */
    private static java.util.List<String> settlementRow(SettlementView row) {
        return java.util.List.of(
                text(row.orderNo()),
                CsvExport.yuan(row.amount()),
                CsvExport.yuan(row.platformFee()),
                CsvExport.yuan(row.coinSubsidyAmount()),
                CsvExport.yuan(row.sellerAmount()),
                text(row.statusLabel()),
                CsvExport.beijingTime(row.releaseAt()),
                CsvExport.beijingTime(row.releasedAt()),
                CsvExport.beijingTime(row.createdAt()));
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
