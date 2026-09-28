package com.duoduo.trade.dto;

import com.duoduo.trade.dto.SettlementDtos.SettlementView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 交易域 · 订单 DTO 集合。
 *
 * <p>约定：金额一律整数「分」；时间一律 ISO8601 UTC 字符串（如 {@code 2026-09-17T05:00:00Z}）。
 */
public final class OrderDtos {

    private OrderDtos() {
    }

    /**
     * 下单请求。
     *
     * <p><b>没有 userId 字段</b> —— 身份只从网关透传的 {@code X-User-*} 取。
     *
     * <p>{@code idempotentKey} 由客户端生成（建议 UUID），用于「重复提交只下一个单」：
     * 同一用户用同一个键再次提交会直接返回**首次**产生的订单，而不是再下一单。
     *
     * <p><b>收货信息有两种传法（A8）</b>：
     * <ol>
     *   <li><b>地址簿方式</b>（推荐）：只传 {@code addressId}，服务端去用户域取地址并写入订单快照。
     *       三个 {@code receiver*} 字段可省略。</li>
     *   <li><b>直填方式</b>（兼容旧客户端）：传 {@code receiverName / receiverPhone / receiverAddress}，
     *       不传 {@code addressId}。</li>
     * </ol>
     * 两者都传时以 {@code addressId} 为准，直填字段被忽略 ——
     * 必须有一个确定性的优先级，否则「地址簿里改了地址、结算页还留着旧的直填值」
     * 会拼出一份谁也不知道从哪来的收货信息。
     *
     * <p>之所以不让 {@code addressId} 与三个直填字段合并成「地址对象」：那会破坏旧客户端的请求体，
     * 而 A8 的验收标准里明确要求「新字段可选，旧字段仍可用」。
     */
    public record PlaceOrderRequest(
            @Size(max = 50, message = "收货人姓名过长") String receiverName,

            @Pattern(regexp = "^1[3-9]\\d{9}$", message = "收货手机号格式不正确") String receiverPhone,

            @Size(max = 300, message = "收货地址过长") String receiverAddress,

            /**
             * 使用地址簿中的地址下单（可空）。非空时忽略上方三个直填字段。
             *
             * <p>归属校验在用户域完成：带上当前登录用户去查这个地址，
             * 查不到（不存在 / 不是自己的）一律拒单。交易域<b>不</b>自己做归属判断 ——
             * 地址数据在用户域，交易域无从判断，硬判断只会写出一份会过期的副本。
             */
            Long addressId,

            @Size(max = 200, message = "备注过长") String remark,

            /**
             * 本次结算要使用的优惠券券码（可空）。
             *
             * <p>只传券码、<b>不传优惠金额</b>：抵扣多少由营销域按券模板算出，
             * 再由交易域写进 {@code discount_amount}。
             * 一旦接受客户端传金额，就等于把「能抵多少」的决定权交给了浏览器 ——
             * 那是所有优惠券漏洞里最经典的一种。
             *
             * <p>v1 口径：一张券只作用于<b>本次拆单后商品总额最大的那个订单</b>
             * （并列时取最先拆出的）。购物车含多家卖家时，其余订单不享受该券。
             */
            @Size(max = 32, message = "优惠券码过长") String couponCode,

            /**
             * 「立即购买」（直购）的 SKU 与数量（可空，两者必须成对出现）。
             *
             * <p>非空时本次下单**不使用购物车**：直接按该 SKU × 数量结算，且下单成功后
             * <b>不会</b>改动用户的购物车 —— 这是「立即购买」与「购物车结算」唯一但关键的差别。
             * 商品快照、拆单、库存冻结、券锁定的口径两条路径完全一致（同一段代码）。
             *
             * <p>为什么要放在下单接口里而不是新开一个端点：下单后的所有副作用
             * （幂等键、库存冻结、券锁定、状态机）必须与购物车结算<b>严格同源</b>，
             * 另开端点等于把这些语义复制一份，迟早出现两条路径不一致的订单。
             */
            Long buySkuId,

            @Min(value = 1, message = "购买数量至少 1 件")
            @Max(value = 200, message = "单次直购最多 200 件")
            Integer buyQuantity,

            @NotBlank(message = "idempotentKey 不能为空，用于防止重复下单")
            @Size(max = 64, message = "idempotentKey 过长") String idempotentKey
    ) {
    }

    /**
     * 结算预览请求：字段与 {@link PlaceOrderRequest} 的「结算相关」子集一致，
     * 但<b>没有 idempotentKey</b> —— 预览不落库，幂等键对它毫无意义，
     * 要求前端为一个只读操作生成一个幂等键是纯粹的负担。
     *
     * <p>收货信息也<b>刻意不参与</b>：预览算的是钱，不是地址。
     * 把地址塞进来会让「地址填错了」变成「连价格都看不到」，
     * 而用户改地址时价格本来就不该变 —— 那属于提交阶段的校验（见 placeOrder 第 3.5 步）。
     */
    public record PreviewOrderRequest(
            @Size(max = 32, message = "couponCode 过长") String couponCode,
            Long buySkuId,
            @Min(value = 1, message = "购买数量至少为 1")
            @Max(value = 200, message = "单次购买数量过大") Integer buyQuantity
    ) {
    }

    /**
     * 结算预览的一条明细（按卖家分组后的单笔订单）。
     *
     * <p>为什么要按卖家拆开返回，而不是只给一个总数：
     * 结算页要展示「这一单来自 A 店、那一单来自 B 店」，且券只作用在其中一单上 ——
     * 只给总数的话，前端根本无法解释「为什么抵扣额是这么多」。
     */
    public record PreviewOrderPreview(
            Long sellerId,
            List<OrderItemView> items,
            long totalAmount,
            long freightAmount,
            long discountAmount,
            long payAmount
    ) {
    }

    /**
     * 结算预览结果（只读，不产生任何写操作）。
     *
     * <p>金额口径与真实下单<b>严格同源</b>：都来自同一套
     * {@code 应付 = 商品总额 + 运费 − 优惠}（运费本阶段固定 0）。
     * 预览若自己算一遍，迟早与下单结果对不上 —— 那正是这个接口存在的意义所在。
     */
    public record PreviewOrderResult(
            List<PreviewOrderPreview> orders,
            int orderCount,
            long totalAmount,
            long freightAmount,
            long discountAmount,
            long payAmount,
            /** 券实际作用在哪一单（未用券为 null），前端据此在对应那一单上显示抵扣 */
            Long couponSellerId,
            String couponCode,
            /** 券不可用时的原因（可用或无券为 null）；预览**不拦**这条，只如实告知 */
            String couponMessage
    ) {
    }

    /** 订单明细（下单时的快照，商品后续改价改名都不影响这里） */
    public record OrderItemView(
            Long skuId,
            Long spuId,
            String title,
            String specText,
            String mainImage,
            long price,
            int quantity,
            long amount
    ) {
    }

    /**
     * 订单事实（服务间只读视图）。
     *
     * <p>与 {@link OrderView} 的关键差别：<b>不含收货人信息</b>。
     * 履约域校验「这单能不能评价」只需要知道「归属谁、是否已完成、买了什么」；
     * 顺手把收货人姓名 / 电话 / 地址也返回，等于把一份隐私数据多复制进一个不需要它的服务 ——
     * 服务间接口应当按「调用方真正需要什么」裁剪字段，而不是图省事返回整份视图。
     */
    public record OrderFactView(
            String orderNo,
            Long userId,
            Long sellerId,
            String status,
            String statusLabel,
            String finishedAt,
            List<OrderItemView> items
    ) {
    }

    /**
     * 某卖家的订单状态计数（服务间只读，供履约域算信用分的「履约完成率」因子）。
     *
     * <p>只回**计数**不回订单：算一个比率只需要几个整数，
     * 拉回订单列表既浪费带宽，又会把收货人姓名 / 电话 / 地址带进一个根本不需要它们的服务。
     * 这与 {@link OrderFactView} 的裁剪原则是同一条（INV-9）。
     *
     * <p>为什么把六个状态都列出来而不是只给「完成 / 未完成」：
     * 信用分口径将来若从「完成率」改成「净完成率」（扣掉退款）或引入加权，
     * 调用方不必再让交易域改接口 —— 把原始事实交给调用方，口径留在调用方一处演进。
     */
    public record SellerOrderStats(
            Long sellerId,
            int pendingPaymentOrders,
            int paidOrders,
            int shippedOrders,
            int completedOrders,
            int closedOrders,
            int refundedOrders,
            int totalOrders
    ) {
    }

    public record OrderView(
            String orderNo,
            Long sellerId,
            String orderType,
            String status,
            /** 状态中文文案，由服务端给出，前端不必维护映射表 */
            String statusLabel,
            long totalAmount,
            long freightAmount,
            long discountAmount,
            /** 本单使用的优惠券券码；未用券为 null */
            String couponCode,
            long payAmount,
            String receiverName,
            String receiverPhone,
            String receiverAddress,
            String remark,
            List<OrderItemView> items,
            /** 资金托管信息（未支付时为 null）；买家详情页据此显示「资金托管中 / 已放款」 */
            SettlementView settlement,
            String createdAt,
            String expireAt,
            String paidAt,
            String shippedAt,
            String finishedAt,
            String closedAt,
            /** 退款时间（钱退回买家的时刻）；未退款为 null */
            String refundedAt,
            String closeReason,
            /**
             * 物流公司与单号（发货后才有值）。
             * 买家端与卖家端共用同一个 OrderView：买家要看「我的货到哪了」，
             * 卖家要看「我这单发的什么」——同一份事实的两个视角，不该各存一份。
             */
            String logisticsCompany,
            String logisticsNo
    ) {
    }

    /**
     * 下单结果。
     * {@code idempotentReplay=true} 表示本次请求命中了幂等键，返回的是已有订单（没有新建）。
     */
    public record PlaceOrderResult(
            List<OrderView> orders,
            int orderCount,
            long totalPayAmount,
            boolean idempotentReplay
    ) {
    }

    /**
     * 削峰下单任务视图（A11）。
     *
     * <p>「已受理」与「已落库」是两个时刻：调用方拿到 PENDING 只代表消息进了队列，
     * 订单要等消费完成 —— 之后凭幂等键轮询本视图，PLACED 后按常规订单接口查询。
     * FAILED 时 {@code failReason} 与同步端点会抛的业务异常同文案（库存不足 / 已下架……）。
     */
    public record PlaceTaskView(
            String idempotentKey,
            String status,
            String failReason,
            String createdAt
    ) {
    }

    /**
     * 死信消息视图（A11，运营查询用）。
     * {@code payload} 是消息原文（JSON：userId + 下单请求）—— 给运维排障看，不进业务展示面。
     */
    public record DlqMessageView(
            String msgId,
            String idempotentKey,
            int reconsumeTimes,
            String payload,
            long storeTimestamp
    ) {
    }

    /** 死信重放请求：按幂等键定位死信（与任务台账、订单幂等键同口径） */
    public record DlqReplayRequest(
            @NotBlank(message = "idempotentKey 不能为空")
            @Size(max = 64, message = "idempotentKey 过长") String idempotentKey
    ) {
    }

    /** 死信重放结果 */
    public record DlqReplayResult(
            String idempotentKey,
            int replayed
    ) {
    }

    /** 取消订单请求 */
    public record CancelRequest(
            @Size(max = 100, message = "取消原因过长") String reason
    ) {
    }

    /**
     * 支付请求（本阶段为模拟渠道，支付即时成功）。
     *
     * <p>{@code idempotentKey} 由客户端生成：真实支付渠道的回调会重复投递，
     * 同一 {@code (channel, idempotentKey)} 只允许成功一次。
     * 重复提交返回首次结果，{@code idempotentReplay=true} 可用于区分。
     */
    public record PayRequest(
            @NotBlank(message = "支付渠道不能为空")
            @Size(max = 20, message = "支付渠道过长") String channel,

            @NotBlank(message = "idempotentKey 不能为空，用于防止重复扣款")
            @Size(max = 64, message = "idempotentKey 过长") String idempotentKey
    ) {
    }

    /**
     * 支付结果。
     *
     * <p>{@code payStatus} 的取值：
     * {@code SUCCESS}（即时渠道已入账）/ {@code PENDING}（沙箱与真实渠道：已预下单，等用户扫码支付）。
     * {@code PENDING} 时前端用 {@code codeUrl} 渲染二维码、用 {@code expireAt} 做倒计时，
     * 并轮询 {@code GET /api/payments/{payNo}} 直到终态。
     */
    public record PayResult(
            String orderNo,
            String payNo,
            String channel,
            long amount,
            String payStatus,
            String orderStatus,
            String orderStatusLabel,
            boolean idempotentReplay,
            /** 二维码内容：即时渠道为 mock:// 串；沙箱为收银台地址；微信 Native 为 weixin:// 串 */
            String codeUrl,
            /** 支付单过期时间（ISO-8601 UTC），过期后二维码失效 */
            String expireAt
    ) {
    }

    /**
     * 超时关单 / 自动确认收货（补偿调度）的执行结果。
     * 运维排障与自测都靠它把「扫了多少、关了多少、放款多少」说清楚。
     */
    public record SweepResult(
            /** 本轮扫描到的超时待付款订单数 */
            int scanned,
            /** 成功关单的订单数 */
            int closed,
            /** 关单后成功释放冻结的条数 */
            int released,
            /** 释放失败、仍留在 FROZEN 台账待下次重试的条数 */
            int releaseFailed,
            /** 本轮因「发货满 N 天」自动确认收货的订单数 */
            int autoCompleted,
            /** 自动确认收货时成功放款给卖家的笔数 */
            int fundsReleased,
            /** 本轮成功入库回补（退款后的货回库）的条数 */
            int restocked,
            /** 入库回补失败、留在 RESTOCK_PENDING 待下轮重试的条数 */
            int restockFailed
    ) {
    }

    /**
     * 退款请求。
     *
     * <p>买家自助退款只允许「已付款且卖家未发货」的订单；
     * 已发货之后要走售后流程（由运营侧入口执行），因为货在买家手里，
     * 系统无从判断货有没有退回来 —— 这一点必须在接口层挡住，
     * 而不是指望前端只显示正确的按钮。
     */
    public record RefundRequest(
            @Size(max = 100, message = "退款原因过长") String reason
    ) {
    }

    /**
     * 退款结果。
     *
     * <p>{@code fundsRefunded} 表示资金是否真的从托管退回买家；
     * {@code restockPending} 表示货的回库是否还需要后台补偿 ——
     * 这两个刻意分开表达：「钱退了、货还没回库」是完全正常的一种中间状态，
     * 用一个布尔量糊在一起，前端就没法给出准确提示。
     */
    public record RefundResult(
            String orderNo,
            String orderStatus,
            String orderStatusLabel,
            long refundAmount,
            boolean fundsRefunded,
            String settlementStatus,
            String settlementStatusLabel,
            boolean restockPending,
            String refundedAt
    ) {
    }
}
