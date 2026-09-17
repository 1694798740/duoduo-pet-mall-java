package com.duoduo.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.trade.client.ProductGateway;
import com.duoduo.trade.client.dto.SkuSnapshot;
import com.duoduo.trade.client.dto.StockChangeDtos.StockChangeItem;
import com.duoduo.trade.client.dto.StockChangeDtos.StockChangeOutcome;
import com.duoduo.trade.client.dto.StockChangeDtos.StockChangeResult;
import com.duoduo.trade.dto.OrderDtos.OrderItemView;
import com.duoduo.trade.dto.OrderDtos.OrderView;
import com.duoduo.trade.dto.OrderDtos.PayRequest;
import com.duoduo.trade.dto.OrderDtos.PayResult;
import com.duoduo.trade.dto.OrderDtos.PlaceOrderRequest;
import com.duoduo.trade.dto.OrderDtos.PlaceOrderResult;
import com.duoduo.trade.dto.OrderDtos.RefundResult;
import com.duoduo.trade.dto.OrderDtos.SweepResult;
import com.duoduo.trade.dto.SettlementDtos.SettlementView;
import com.duoduo.trade.entity.CartItemEntity;
import com.duoduo.trade.entity.OrderEntity;
import com.duoduo.trade.entity.OrderItemEntity;
import com.duoduo.trade.entity.OrderStatusLogEntity;
import com.duoduo.trade.entity.PaymentEntity;
import com.duoduo.trade.entity.SettlementEntity;
import com.duoduo.trade.entity.StockFreezeEntity;
import com.duoduo.trade.mapper.CartItemMapper;
import com.duoduo.trade.mapper.OrderItemMapper;
import com.duoduo.trade.mapper.OrderMapper;
import com.duoduo.trade.mapper.OrderStatusLogMapper;
import com.duoduo.trade.mapper.PaymentMapper;
import com.duoduo.trade.mapper.StockFreezeMapper;
import com.duoduo.trade.payment.PaymentChannel;
import com.duoduo.trade.payment.PaymentChannelRegistry;
import com.duoduo.trade.payment.SandboxPaymentChannel;
import com.duoduo.trade.support.OrderNoGenerator;
import com.duoduo.trade.support.OrderStatus;
import com.duoduo.trade.support.SettlementStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 订单服务：下单（含拆单）、取消（含库存释放）、支付、发货收货、退款与补偿调度。
 *
 * <p>七个刻意的工程决策：
 * <ol>
 *   <li><b>幂等靠唯一索引</b>：{@code uk_order_user_idem(user_id, idempotent_key)}。
 *       先查一次是为了快速返回（常规重复提交走这条路），
 *       并发下的极端情况由唯一索引兜底（捕获 DuplicateKey 后返回已有订单）。</li>
 *   <li><b>按卖家拆单</b>：一个订单只属于一个卖家。当前商品域尚无商家字段，
 *       全部落在 {@link #PLATFORM_SELLER_ID}（平台自营）；第三方商家接入后
 *       只需改分组维度，订单与明细结构不用动。</li>
 *   <li><b>明细存快照</b>：标题 / 规格 / 图片 / 单价在**下单那一刻**拷进订单明细，
 *       商品后续改价改名都不影响历史订单。</li>
 *   <li><b>跨服务副作用必须补偿</b>：库存冻结发生在商品服务，**不受本服务事务保护**。
 *       因此一旦本方法后面抛异常，必须在 catch 里显式把已冻结的部分释放掉 ——
 *       光靠 {@code @Transactional} 回滚会留下「订单没建成、库存却被冻着」的幽灵占用。</li>
 *   <li><b>取消可重入</b>：释放只处理 {@code FROZEN} 状态的台账行并逐条落状态，
 *       因此「部分失败后重试」是安全的（已释放的不会被重复释放，重复释放会被商品服务拒绝）。</li>
 *   <li><b>钱与订单分开记</b>：资金去向写在 {@code t_settlement}，由
 *       {@link SettlementService} 用 CAS 维护。订单状态回答「货走到哪了」，
 *       托管状态回答「钱现在在谁手里」—— 两条时间线分开，出问题时才答得上来。</li>
 *   <li><b>退款不阻塞入库</b>：买家的钱该退就退，货回库是仓库侧的后续动作，
 *       失败由台账 {@code RESTOCK_PENDING} 与补偿调度兜住，不拿仓库的抖动卡住买家。</li>
 * </ol>
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    /**
     * 平台自营的卖家 ID。
     * 商品域 SPU 目前没有商家字段，自营商品统一归到平台账号下。
     */
    public static final long PLATFORM_SELLER_ID = 0L;

    private static final String ORDER_TYPE_NORMAL = "NORMAL";
    private static final String OPERATOR_USER = "USER";
    private static final String OPERATOR_SELLER = "SELLER";
    private static final String OPERATOR_SYSTEM = "SYSTEM";
    private static final String OPERATOR_OPERATOR = "OPERATOR";
    private static final String OP_CREATE = "创建订单";
    /** 与商品服务约定的失败原因码（见 StockDtos.Reason）：冻结量不足 */
    private static final String REASON_FREEZE_NOT_ENOUGH = "FREEZE_NOT_ENOUGH";
    private static final int SELECTED = 1;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;
    /** 订单明细的退款标记（下单时初始为 NONE） */
    private static final String REFUND_STATUS_REFUNDED = "REFUNDED";

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderStatusLogMapper statusLogMapper;
    private final StockFreezeMapper stockFreezeMapper;
    private final PaymentMapper paymentMapper;
    private final CartItemMapper cartItemMapper;
    private final ProductGateway productGateway;
    private final SettlementService settlementService;
    private final PaymentChannelRegistry channelRegistry;
    private final SnowflakeIdGenerator idGenerator;
    private final OrderNoGenerator orderNoGenerator;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${duoduo.trade.order-expire-minutes:30}")
    private int orderExpireMinutes;

    @Value("${duoduo.trade.max-items-per-order:50}")
    private int maxItemsPerOrder;

    /** 单轮超时关单最多处理多少单，避免一次把库压住 */
    @Value("${duoduo.trade.sweep-batch-size:200}")
    private int sweepBatchSize;

    /**
     * 发货后多少天自动确认收货（并放款给卖家）。配 0 或负数即关闭该行为。
     * 这是主流电商的标配：不能指望买家每次都主动点「确认收货」，
     * 否则卖家的钱会被无限期占在托管里。
     */
    @Value("${duoduo.trade.auto-receipt-days:7}")
    private int autoReceiptDays;

    public OrderService(OrderMapper orderMapper,
                        OrderItemMapper orderItemMapper,
                        OrderStatusLogMapper statusLogMapper,
                        StockFreezeMapper stockFreezeMapper,
                        PaymentMapper paymentMapper,
                        CartItemMapper cartItemMapper,
                        ProductGateway productGateway,
                        SettlementService settlementService,
                        PaymentChannelRegistry channelRegistry,
                        SnowflakeIdGenerator idGenerator,
                        OrderNoGenerator orderNoGenerator,
                        ApplicationEventPublisher eventPublisher) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.statusLogMapper = statusLogMapper;
        this.stockFreezeMapper = stockFreezeMapper;
        this.paymentMapper = paymentMapper;
        this.cartItemMapper = cartItemMapper;
        this.productGateway = productGateway;
        this.settlementService = settlementService;
        this.channelRegistry = channelRegistry;
        this.idGenerator = idGenerator;
        this.orderNoGenerator = orderNoGenerator;
        this.eventPublisher = eventPublisher;
    }

    // ---------------- 下单 ----------------

    @Transactional
    public PlaceOrderResult placeOrder(PlaceOrderRequest request) {
        Long userId = UserContext.requireUserId();

        // 1) 幂等快路径：同一用户同一幂等键 → 直接返回首次产生的订单
        OrderEntity existing = findByIdempotentKey(userId, request.idempotentKey());
        if (existing != null) {
            return replay(userId, existing);
        }

        // 2) 取购物车中已勾选的条目
        List<CartItemEntity> rows = cartItemMapper.selectList(new LambdaQueryWrapper<CartItemEntity>()
                .eq(CartItemEntity::getUserId, userId)
                .eq(CartItemEntity::getSelected, SELECTED));
        if (rows.isEmpty()) {
            throw BizException.businessRule("购物车中没有已勾选的商品，无法下单");
        }
        if (rows.size() > maxItemsPerOrder) {
            throw BizException.businessRule("单次下单最多 " + maxItemsPerOrder + " 种商品，请分批结算");
        }

        // 3) 取快照并逐条校验（存在 / 未下架 / 余量足够）
        Map<Long, SkuSnapshot> snapshots = productGateway.loadSnapshots(
                rows.stream().map(CartItemEntity::getSkuId).toList());
        for (CartItemEntity row : rows) {
            SkuSnapshot snap = snapshots.get(row.getSkuId());
            int quantity = row.getQuantity() == null ? 0 : row.getQuantity();
            if (snap == null) {
                throw BizException.notFound("商品规格（SKU " + row.getSkuId() + "）");
            }
            if (!snap.sellable()) {
                throw new BizException(ErrorCode.BUSINESS_RULE,
                        "商品「" + safeTitle(snap) + "」已下架或已售罄，请从购物车移除后重试");
            }
            if (quantity > snap.availableStock()) {
                throw new BizException(ErrorCode.STOCK_INSUFFICIENT,
                        "商品「" + safeTitle(snap) + "」库存不足，当前最多可购 " + snap.availableStock() + " 件");
            }
        }

        // 4) 按卖家拆单：归属来自商品域 t_spu.seller_id（随 SKU 快照下发）。
        //    商品域没归属字段时这里只能全部写 0（自营）；现在按真实卖家分组，
        //    第三方商家的订单才能落进他的商家端。快照缺归属（理论上不该发生）回退自营 0。
        Map<Long, List<CartItemEntity>> groups = rows.stream()
                .collect(Collectors.groupingBy(r -> {
                    SkuSnapshot snap = snapshots.get(r.getSkuId());
                    Long sellerId = snap == null ? null : snap.sellerId();
                    return sellerId == null ? PLATFORM_SELLER_ID : sellerId;
                }, LinkedHashMap::new, Collectors.toList()));

        LocalDateTime now = nowUtc();
        LocalDateTime expireAt = now.plusMinutes(orderExpireMinutes);
        List<OrderEntity> created = new ArrayList<>(groups.size());
        // 已在商品服务成功冻结的记录，用于异常时补偿释放
        List<FrozenRef> frozenRefs = new ArrayList<>();

        try {
            for (Map.Entry<Long, List<CartItemEntity>> entry : groups.entrySet()) {
                created.add(createOne(userId, entry.getKey(), entry.getValue(), snapshots,
                        request, now, expireAt, frozenRefs));
            }
        } catch (DuplicateKeyException e) {
            // 并发下同一幂等键撞车：唯一索引拦下了第二条，回滚并返回首次那条
            releaseQuietly(frozenRefs, "幂等撞车补偿");
            OrderEntity dup = findByIdempotentKey(userId, request.idempotentKey());
            if (dup != null) {
                return replay(userId, dup);
            }
            throw e;
        } catch (RuntimeException e) {
            // 关键补偿：库存冻结在商品服务，不受本事务回滚保护
            releaseQuietly(frozenRefs, "下单失败补偿");
            throw e;
        }

        // 5) 下单成功，清掉已结算的购物车条目
        cartItemMapper.deleteBatchIds(rows.stream().map(CartItemEntity::getId).toList());

        List<OrderView> views = toViews(created);
        long totalPay = views.stream().mapToLong(OrderView::payAmount).sum();
        return new PlaceOrderResult(views, views.size(), totalPay, false);
    }

    /** 创建单个订单：订单头 + 明细快照 + 库存冻结 + 冻结台账 + 状态日志 */
    private OrderEntity createOne(Long userId, Long sellerId, List<CartItemEntity> rows,
                                  Map<Long, SkuSnapshot> snapshots, PlaceOrderRequest request,
                                  LocalDateTime now, LocalDateTime expireAt, List<FrozenRef> frozenRefs) {
        long totalAmount = 0L;
        for (CartItemEntity row : rows) {
            SkuSnapshot snap = snapshots.get(row.getSkuId());
            totalAmount += snap.price() * (row.getQuantity() == null ? 0 : row.getQuantity());
        }
        // 运费与优惠本阶段固定为 0；营销接入后由促销服务计算，口径不变（应付 = 商品 + 运费 − 优惠）
        long freightAmount = 0L;
        long discountAmount = 0L;

        OrderEntity order = new OrderEntity();
        order.setId(idGenerator.nextId());
        order.setOrderNo(orderNoGenerator.next());
        order.setUserId(userId);
        order.setSellerId(sellerId);
        order.setOrderType(ORDER_TYPE_NORMAL);
        order.setStatus(OrderStatus.PENDING_PAYMENT.name());
        order.setTotalAmount(totalAmount);
        order.setFreightAmount(freightAmount);
        order.setDiscountAmount(discountAmount);
        order.setPayAmount(totalAmount + freightAmount - discountAmount);
        order.setReceiverName(request.receiverName().trim());
        order.setReceiverPhone(request.receiverPhone().trim());
        order.setReceiverAddress(request.receiverAddress().trim());
        order.setRemark(request.remark() == null ? null : request.remark().trim());
        order.setIdempotentKey(request.idempotentKey());
        order.setExpireAt(expireAt);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        orderMapper.insert(order);

        // 明细快照
        for (CartItemEntity row : rows) {
            SkuSnapshot snap = snapshots.get(row.getSkuId());
            int quantity = row.getQuantity() == null ? 0 : row.getQuantity();
            OrderItemEntity item = new OrderItemEntity();
            item.setId(idGenerator.nextId());
            item.setOrderId(order.getId());
            item.setSpuId(snap.spuId());
            item.setSkuId(snap.skuId());
            item.setTitle(snap.spuTitle());
            item.setSpecText(snap.specText());
            item.setMainImage(snap.image());
            item.setPrice(snap.price());
            item.setQuantity(quantity);
            item.setAmount(snap.price() * quantity);
            item.setRefundStatus("NONE");
            item.setCreatedAt(now);
            orderItemMapper.insert(item);
        }

        // 冻结库存（跨服务；失败即抛，由外层补偿）
        // 注意：这批是**逐个 SKU 顺序冻结**的，不是整批原子。若中途某个 SKU 失败，
        // 前面已成功的那些已经真实冻在商品服务上了，必须精确记入补偿列表，
        // 否则会出现「订单没建成、库存却被永久冻住」的幽灵占用。
        List<StockChangeItem> freezeItems = rows.stream()
                .map(r -> new StockChangeItem(r.getSkuId(), r.getQuantity()))
                .toList();
        StockChangeOutcome outcome = productGateway.freeze(order.getId(), freezeItems);
        Set<Long> frozenOk = succeededSkuIds(outcome);
        for (StockChangeItem item : freezeItems) {
            if (!frozenOk.contains(item.skuId())) {
                continue;
            }
            frozenRefs.add(new FrozenRef(item.skuId(), item.quantity()));
            StockFreezeEntity freeze = new StockFreezeEntity();
            freeze.setId(idGenerator.nextId());
            freeze.setSkuId(item.skuId());
            freeze.setOrderId(order.getId());
            freeze.setQuantity(item.quantity());
            freeze.setStatus(StockFreezeEntity.STATUS_FROZEN);
            freeze.setExpireAt(expireAt);
            freeze.setCreatedAt(now);
            freeze.setUpdatedAt(now);
            stockFreezeMapper.insert(freeze);
        }
        if (outcome == null || !outcome.allSuccess()) {
            throw new BizException(ErrorCode.STOCK_INSUFFICIENT,
                    "下单失败：" + describeFailure(outcome, snapshots));
        }

        writeStatusLog(order.getId(), null, order.getStatus(), OPERATOR_USER, userId, OP_CREATE, now);
        return order;
    }

    // ---------------- 查询 ----------------

    public PageResult<OrderView> listMine(String status, int page, int pageSize) {
        Long userId = UserContext.requireUserId();
        int pageNo = Math.max(1, page);
        int size = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        if (status != null && !status.isBlank() && OrderStatus.of(status.trim()) == null) {
            throw BizException.validation("不支持的订单状态：" + status.trim());
        }

        LambdaQueryWrapper<OrderEntity> wrapper = new LambdaQueryWrapper<OrderEntity>()
                .eq(OrderEntity::getUserId, userId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(OrderEntity::getStatus, status.trim());
        }
        wrapper.orderByDesc(OrderEntity::getCreatedAt).orderByDesc(OrderEntity::getId);

        Page<OrderEntity> result = orderMapper.selectPage(new Page<>(pageNo, size), wrapper);
        return PageResult.of(toViews(result.getRecords()), result.getTotal(), pageNo, size);
    }

    public OrderView detail(String orderNo) {
        Long userId = UserContext.requireUserId();
        OrderEntity order = findByOrderNo(userId, orderNo);
        if (order == null) {
            throw BizException.notFound("订单");
        }
        return toViews(List.of(order)).get(0);
    }

    // ---------------- 运营查询（M10，无归属限制；调用方须先过 OperatorGuard） ----------------

    /**
     * 运营全平台订单列表：可按状态 / 订单号模糊过滤。
     *
     * <p>不做归属校验 —— 这是后台运营视角，准入由控制层的
     * {@code OperatorGuard.requireOperator()} 统一把关。
     */
    public PageResult<OrderView> adminList(String status, String orderNo, int page, int pageSize) {
        int pageNo = Math.max(1, page);
        int size = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        if (status != null && !status.isBlank() && OrderStatus.of(status.trim()) == null) {
            throw BizException.validation("不支持的订单状态：" + status.trim());
        }

        LambdaQueryWrapper<OrderEntity> wrapper = new LambdaQueryWrapper<OrderEntity>();
        if (status != null && !status.isBlank()) {
            wrapper.eq(OrderEntity::getStatus, status.trim());
        }
        if (orderNo != null && !orderNo.isBlank()) {
            wrapper.likeRight(OrderEntity::getOrderNo, orderNo.trim());
        }
        wrapper.orderByDesc(OrderEntity::getCreatedAt).orderByDesc(OrderEntity::getId);

        Page<OrderEntity> result = orderMapper.selectPage(new Page<>(pageNo, size), wrapper);
        return PageResult.of(toViews(result.getRecords()), result.getTotal(), pageNo, size);
    }

    /** 运营按订单号查详情（无归属限制），查不到返回 404 */
    public OrderView adminDetail(String orderNo) {
        OrderEntity order = findAnyByOrderNo(orderNo);
        if (order == null) {
            throw BizException.notFound("订单");
        }
        return toViews(List.of(order)).get(0);
    }

    // ---------------- 取消（含释放冻结） ----------------

    @Transactional
    public OrderView cancel(String orderNo, String reason) {
        Long userId = UserContext.requireUserId();
        OrderEntity order = findByOrderNo(userId, orderNo);
        if (order == null) {
            throw BizException.notFound("订单");
        }
        OrderStatus current = OrderStatus.of(order.getStatus());
        // 用户自助取消**只允许从「待付款」**发起。
        // 注意不要图省事写成 current.canTransitionTo(CLOSED)：状态表里 PAID → CLOSED 是合法边，
        // 但那条边是留给「退款关单」的（需走售后流程），不是给用户直接反悔用的。
        // 早先就是踩了这个坑：已付款订单被用户直接取消，钱与货的归属立刻说不清。
        if (current != OrderStatus.PENDING_PAYMENT) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL,
                    "订单当前状态（" + OrderStatus.labelOf(order.getStatus()) + "）不允许取消；已付款订单请走售后申请退款");
        }

        LocalDateTime now = nowUtc();
        String closeReason = (reason == null || reason.isBlank()) ? "用户取消" : reason.trim();
        order.setStatus(OrderStatus.CLOSED.name());
        order.setClosedAt(now);
        order.setCloseReason(closeReason);
        order.setUpdatedAt(now);
        orderMapper.updateById(order);
        writeStatusLog(order.getId(), current.name(), OrderStatus.CLOSED.name(), OPERATOR_USER, userId, closeReason, now);

        // 作废该订单所有未完成的支付单：否则「订单已关闭、二维码还能扫出钱来」，
        // 收款入口虽然也会校验订单状态拒绝入账，但直接消除矛盾状态更省事也更好排查
        paymentMapper.markClosedByOrder(order.getId(), "订单已取消：" + closeReason, now);

        int[] release = releaseOrderFreeze(order.getId(), now);
        if (release[1] > 0) {
            log.error("订单 {} 取消后有 {} 条冻结未释放，已留台账待补偿调度重试", order.getOrderNo(), release[1]);
        }
        return toViews(List.of(order)).get(0);
    }

    /**
     * 运营强制关单（平台管理后台 M10）：只针对「待付款」订单。
     *
     * <p>与用户自助取消走同一条状态机与资金路径（作废未支付支付单 + 释放冻结），
     * 区别只在于：操作者身份是运营（记 {@code OPERATOR} 日志），且不校验 userId ——
     * 运营处理的是「任何人的异常订单」。
     * 已付款 / 已发货的订单**不可**在这里强关：钱已在托管或已放款，需要走退款 / 售后 / 追偿，
     * 若图省事把 PAID → CLOSED 这条边放给运营，钱与货的归属会立刻说不清（历史已踩过这个坑）。
     */
    @Transactional
    public OrderView forceClose(String orderNo, String reason) {
        OrderEntity order = findAnyByOrderNo(orderNo);
        if (order == null) {
            throw BizException.notFound("订单");
        }
        OrderStatus current = OrderStatus.of(order.getStatus());
        if (current != OrderStatus.PENDING_PAYMENT) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL,
                    "订单当前状态（" + OrderStatus.labelOf(order.getStatus()) + "）不允许强制关单；已付款/已发货订单请走退款或售后流程");
        }
        LocalDateTime now = nowUtc();
        String closeReason = (reason == null || reason.isBlank()) ? "平台干预关单" : reason.trim();
        order.setStatus(OrderStatus.CLOSED.name());
        order.setClosedAt(now);
        order.setCloseReason(closeReason);
        order.setUpdatedAt(now);
        orderMapper.updateById(order);
        writeStatusLog(order.getId(), current.name(), OrderStatus.CLOSED.name(), OPERATOR_OPERATOR,
                null, closeReason, now);

        paymentMapper.markClosedByOrder(order.getId(), "平台干预关单：" + closeReason, now);
        int[] release = releaseOrderFreeze(order.getId(), now);
        if (release[1] > 0) {
            log.error("订单 {} 运营强关后有 {} 条冻结未释放，已留台账待补偿调度重试", order.getOrderNo(), release[1]);
        }
        return toViews(List.of(order)).get(0);
    }

    /**
     * 释放某订单仍处于 FROZEN 的冻结，返回 {@code [成功数, 失败数]}。
     *
     * <p>逐条落状态，因此**可重入**：已释放的不会被重复释放。
     * 若商品服务此刻不可用，只记录 ERROR 而**不**让取消失败 ——
     * 用户的取消意图应当被尊重；未释放的台账行保持 FROZEN，
     * 由超时关单的补偿调度（{@link #sweepExpiredOrders()}）在后续轮次重试。
     */
    private int[] releaseOrderFreeze(Long orderId, LocalDateTime now) {
        List<StockFreezeEntity> freezes = stockFreezeMapper.selectList(
                new LambdaQueryWrapper<StockFreezeEntity>()
                        .eq(StockFreezeEntity::getOrderId, orderId)
                        .eq(StockFreezeEntity::getStatus, StockFreezeEntity.STATUS_FROZEN));
        if (freezes.isEmpty()) {
            return new int[]{0, 0};
        }
        List<StockChangeItem> items = freezes.stream()
                .map(f -> new StockChangeItem(f.getSkuId(), f.getQuantity()))
                .toList();
        StockChangeOutcome outcome = productGateway.release(orderId, items);
        Set<Long> released = succeededSkuIds(outcome);
        // 「冻结量不足」= 商品服务那边这份冻结已经不存在了。
        // 在本订单的台账仍记 FROZEN 的前提下，最可能的成因是**并发释放**
        // （定时调度与手动触发、或上一轮已释放成功但事务未提交就被重试）。
        // 此时把台账冲正为 RELEASED 才是与事实一致的做法：
        // 否则这一行会永远卡在 FROZEN，每轮调度都会重试失败并刷 ERROR 日志，真正的异常反而被淹没。
        // 冲正同时打 WARN 留痕，便于需要时回溯。
        Set<Long> alreadyReleased = failedSkuIdsWithReason(outcome, REASON_FREEZE_NOT_ENOUGH);
        if (!alreadyReleased.isEmpty()) {
            log.warn("订单 {} 有 {} 个 SKU 的冻结在商品服务侧已不存在（并发释放或重复释放），台账冲正为 RELEASED",
                    orderId, alreadyReleased.size());
        }

        int releasedCount = 0;
        for (StockFreezeEntity freeze : freezes) {
            Long skuId = freeze.getSkuId();
            if (released.contains(skuId) || alreadyReleased.contains(skuId)) {
                freeze.setStatus(StockFreezeEntity.STATUS_RELEASED);
                freeze.setUpdatedAt(now);
                stockFreezeMapper.updateById(freeze);
                releasedCount++;
            }
        }
        return new int[]{releasedCount, freezes.size() - releasedCount};
    }

    /** 从库存变更结果里挑出「因指定原因失败」的 SKU（用于区分可冲正的失败与真故障） */
    private static Set<Long> failedSkuIdsWithReason(StockChangeOutcome outcome, String reason) {
        if (outcome == null || outcome.results() == null) {
            return Set.of();
        }
        return outcome.results().stream()
                .filter(r -> !r.success() && reason.equals(r.reason()))
                .map(StockChangeResult::skuId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** 支付成功后把该订单的冻结台账置为 DEDUCTED（库存已真实扣减） */
    private void markFreezeDeducted(Long orderId, LocalDateTime now) {
        List<StockFreezeEntity> freezes = stockFreezeMapper.selectList(
                new LambdaQueryWrapper<StockFreezeEntity>()
                        .eq(StockFreezeEntity::getOrderId, orderId)
                        .eq(StockFreezeEntity::getStatus, StockFreezeEntity.STATUS_FROZEN));
        for (StockFreezeEntity freeze : freezes) {
            freeze.setStatus(StockFreezeEntity.STATUS_DEDUCTED);
            freeze.setUpdatedAt(now);
            stockFreezeMapper.updateById(freeze);
        }
    }

    // ---------------- 支付（幂等） ----------------

    /**
     * 发起支付（预下单）。
     *
     * <p>渠道不同，返回的 {@code payStatus} 不同：
     * <ul>
     *   <li><b>即时渠道（MOCK）</b>：预下单即收款完成，返回 {@code SUCCESS}；
     *       用于自动化测试与最简联调，不依赖任何外部条件；</li>
     *   <li><b>沙箱 / 真实渠道</b>：预下单只拿到二维码内容（{@code codeUrl}），
     *       返回 {@code PENDING}，钱到没到<b>必须以渠道回调为准</b>——
     *       把「预下单成功」当成「已付款」是支付系统最经典的严重缺陷。</li>
     * </ul>
     *
     * <p>幂等的三层防线（从外到内）：
     * <ol>
     *   <li><b>先查</b>：同一 {@code (channel, idempotentKey)} 已存在 → 直接返回首次结果；</li>
     *   <li><b>订单状态</b>：订单已不是「待付款」→ 拒绝，防止对同一订单二次收款；</li>
     *   <li><b>唯一索引</b>：{@code uk_pay_channel_idem} 兜住并发插入。</li>
     * </ol>
     */
    @Transactional
    public PayResult pay(String orderNo, PayRequest request) {
        Long userId = UserContext.requireUserId();
        OrderEntity order = findByOrderNo(userId, orderNo);
        if (order == null) {
            throw BizException.notFound("订单");
        }

        PaymentChannel channel = channelRegistry.require(request.channel());

        PaymentEntity existing = findPayment(channel.code(), request.idempotentKey());
        if (existing != null) {
            return replayPayment(order, existing);
        }

        OrderStatus current = OrderStatus.of(order.getStatus());
        if (current == null || !current.canTransitionTo(OrderStatus.PAID)) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL,
                    "订单当前状态（" + OrderStatus.labelOf(order.getStatus()) + "）不允许支付");
        }

        LocalDateTime now = nowUtc();
        PaymentEntity payment = new PaymentEntity();
        payment.setId(idGenerator.nextId());
        payment.setPayNo(orderNoGenerator.next());
        payment.setOrderId(order.getId());
        payment.setUserId(userId);
        payment.setChannel(channel.code());
        payment.setAmount(nz(order.getPayAmount()));
        payment.setStatus(PaymentEntity.STATUS_INIT);
        payment.setIdempotentKey(request.idempotentKey());
        payment.setCreatedAt(now);
        payment.setUpdatedAt(now);

        // 先落支付单（INIT），再动库存。
        // 顺序很关键：若并发下唯一索引拦下了本次插入，此时还**没有**任何库存变更，
        // 直接返回冲突即可；反过来先扣库存再插单，一旦插入失败就会留下「库存扣了、订单没付款」的残留。
        try {
            paymentMapper.insert(payment);
        } catch (DuplicateKeyException e) {
            PaymentEntity winner = findPayment(channel.code(), request.idempotentKey());
            if (winner != null) {
                throw BizException.conflict("该支付请求已处理，请先查询订单状态（支付单号 " + winner.getPayNo() + "）");
            }
            throw BizException.conflict("检测到重复的支付请求正在处理中，请稍后查询订单状态");
        }

        // 预下单：向渠道登记这笔支付，拿到二维码内容与支付单过期时间。
        // 渠道差异被收敛在这一处 —— 换渠道不改订单、库存、账本的任何代码。
        PaymentChannel.PrepayResult prepay = channel.prepay(new PaymentChannel.PrepayCommand(
                payment.getPayNo(), order.getOrderNo(), nz(order.getPayAmount()),
                "多多宠物商店订单 " + order.getOrderNo()));
        payment.setCodeUrl(prepay.codeUrl());
        payment.setExpireAt(prepay.expireAt());

        if (channel.instantSuccess()) {
            // 即时渠道：预下单即视为收款完成，走与渠道回调**完全相同**的入账路径
            return completePayment(order, payment, "MOCK-" + payment.getPayNo(), now, false);
        }

        // 沙箱 / 真实渠道：支付单转 PENDING，前端用 codeUrl 渲染二维码，等待渠道回调
        payment.setStatus(PaymentEntity.STATUS_PENDING);
        payment.setUpdatedAt(now);
        paymentMapper.updateById(payment);
        return toPayResult(order, payment, false);
    }

    /**
     * 渠道回调入口（沙箱与真实渠道共用）。
     *
     * <p>验签 / 防重放在 {@link PaymentChannel#verifyCallback} 里做；
     * 这里做剩下的四件事：<b>幂等重放</b>、<b>金额核对</b>、<b>订单状态校验</b>、<b>入账</b>。
     * 金额核对必须独立于验签 —— 签名只证明「这条回调来自渠道」，不证明「金额没被改过」。
     */
    @Transactional
    public PayResult handleChannelCallback(String channelCode, String rawBody, Map<String, String> headers) {
        PaymentChannel channel = channelRegistry.require(channelCode);
        if (channel.instantSuccess()) {
            // MOCK 不存在「稍后回调」；收到它的回调只有配错渠道或伪造两种可能，都该拒绝
            throw new BizException(ErrorCode.BUSINESS_RULE,
                    "渠道 " + channel.code() + " 不支持支付回调：它在预下单时即完成收款");
        }
        PaymentChannel.CallbackPayload payload = channel.verifyCallback(rawBody, headers);

        PaymentEntity payment = paymentMapper.selectOne(new LambdaQueryWrapper<PaymentEntity>()
                .eq(PaymentEntity::getPayNo, payload.payNo()));
        if (payment == null) {
            throw BizException.notFound("支付单");
        }
        OrderEntity order = orderMapper.selectById(payment.getOrderId());
        if (order == null) {
            throw BizException.notFound("支付单对应的订单");
        }

        // 幂等重放：渠道回调必然重复投递（重试 / 网络抖动），「已成功」是常态路径而非异常
        if (PaymentEntity.STATUS_SUCCESS.equals(payment.getStatus())) {
            return toPayResult(order, payment, true);
        }
        if (!PaymentEntity.STATUS_PENDING.equals(payment.getStatus())
                && !PaymentEntity.STATUS_INIT.equals(payment.getStatus())) {
            throw new BizException(ErrorCode.BUSINESS_RULE,
                    "支付单当前状态（" + payment.getStatus() + "）不允许入账");
        }
        if (!"SUCCESS".equals(payload.status())) {
            throw BizException.validation("暂不支持处理非成功状态的支付回调：" + payload.status());
        }
        if (payload.amount() != nz(payment.getAmount())) {
            throw new BizException(ErrorCode.PAYMENT_AMOUNT_MISMATCH,
                    "回调金额（" + payload.amount() + " 分）与支付单金额（" + nz(payment.getAmount())
                            + " 分）不一致，已拒绝入账");
        }
        OrderStatus current = OrderStatus.of(order.getStatus());
        if (current == null || !current.canTransitionTo(OrderStatus.PAID)) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL,
                    "订单当前状态（" + OrderStatus.labelOf(order.getStatus()) + "）不允许支付入账");
        }
        return completePayment(order, payment, payload.channelTradeNo(), payload.paidAt(), false);
    }

    /**
     * 沙箱收银台的「模拟支付」动作：构造一条与真实渠道回调**格式完全相同**的签名报文，
     * 再交给 {@link #handleChannelCallback} 走同一套验签与入账 ——
     * 验签路径因此是被真实使用的，而不是摆着好看的死代码。
     *
     * <p>不校验登录：现实中「扫了二维码的人就是付款人」，收银台页面在扫码设备上打开，
     * 那台设备上没有买家的登录态。金额一律取库内值，不信任请求参数。
     */
    @Transactional
    public PayResult sandboxPay(String payNo) {
        PaymentEntity payment = paymentMapper.selectOne(new LambdaQueryWrapper<PaymentEntity>()
                .eq(PaymentEntity::getPayNo, payNo));
        if (payment == null) {
            throw BizException.notFound("支付单");
        }
        if (!SandboxPaymentChannel.CODE.equals(payment.getChannel())) {
            throw BizException.validation("仅沙箱渠道（SANDBOX）支持收银台模拟支付，当前渠道：" + payment.getChannel());
        }
        OrderEntity order = orderMapper.selectById(payment.getOrderId());
        if (order == null) {
            throw BizException.notFound("支付单对应的订单");
        }
        if (PaymentEntity.STATUS_SUCCESS.equals(payment.getStatus())) {
            return toPayResult(order, payment, true);
        }
        if (!PaymentEntity.STATUS_PENDING.equals(payment.getStatus())) {
            throw new BizException(ErrorCode.BUSINESS_RULE,
                    "支付单当前状态（" + payment.getStatus() + "）不允许支付");
        }
        SandboxPaymentChannel sandbox =
                (SandboxPaymentChannel) channelRegistry.require(SandboxPaymentChannel.CODE);
        Map<String, String> callback = sandbox.buildSignedCallback(
                payment.getPayNo(), "SANDBOX-" + payment.getPayNo(), nz(payment.getAmount()));
        return handleChannelCallback(SandboxPaymentChannel.CODE, callback.get("body"), callback);
    }

    /** 查询自己的支付单（扫码后前端轮询用；不是自己的支付单一律 404，不暴露存在性） */
    public PayResult getPaymentView(String payNo) {
        Long userId = UserContext.requireUserId();
        PaymentEntity payment = paymentMapper.selectOne(new LambdaQueryWrapper<PaymentEntity>()
                .eq(PaymentEntity::getPayNo, payNo));
        if (payment == null || !payment.getUserId().equals(userId)) {
            throw BizException.notFound("支付单");
        }
        OrderEntity order = orderMapper.selectById(payment.getOrderId());
        if (order == null) {
            throw BizException.notFound("支付单对应的订单");
        }
        return toPayResult(order, payment, false);
    }

    /**
     * 收银台预览（只读、无副作用）：给沙箱收银台页面渲染金额与单号用。
     * 只认 SANDBOX 渠道 —— 收银台是沙箱的自有页面，不该替别的渠道展示。
     */
    public PayResult sandboxPayPreview(String payNo) {
        PaymentEntity payment = paymentMapper.selectOne(new LambdaQueryWrapper<PaymentEntity>()
                .eq(PaymentEntity::getPayNo, payNo));
        if (payment == null || !SandboxPaymentChannel.CODE.equals(payment.getChannel())) {
            throw BizException.notFound("支付单");
        }
        OrderEntity order = orderMapper.selectById(payment.getOrderId());
        if (order == null) {
            throw BizException.notFound("支付单对应的订单");
        }
        return toPayResult(order, payment, false);
    }

    // ---------------- 发货与收货（状态机后半段） ----------------

    /**
     * 发货（已付款 → 已发货）。
     *
     * <p>调用方是<b>卖家侧 / 运营侧系统</b>，由 {@code InternalTokenFilter} 校验内部凭据。
     * 卖家账号体系要到卖家中心（M5-9）才落地，在那之前用内部凭据把口子关上，
     * 而不是把「任何人登录后都能替别人发货」留在线上。
     */
    @Transactional
    public OrderView ship(String orderNo) {
        OrderEntity order = findAnyByOrderNo(orderNo);
        if (order == null) {
            throw BizException.notFound("订单");
        }
        return shipInternal(order, "卖家发货", null, null);
    }

    /**
     * 发货的状态机本体：<b>调用方已完成归属校验</b>。
     *
     * <p>拆出来是为了让「内部凭据调用」与「卖家中心调用」（M5-9）共用同一段状态机 ——
     * 两条入口只差在准入方式，状态流转、资金不动、日志留痕必须完全一致，
     * 否则会出现「从商家端发货不写日志」「内部接口发货跳过状态校验」这类分叉。
     *
     * @param logisticsCompany 物流公司（可空；内部接口不传）
     * @param logisticsNo      物流单号（可空）
     */
    @Transactional
    public OrderView shipInternal(OrderEntity order, String reason,
                                  String logisticsCompany, String logisticsNo) {
        OrderStatus current = OrderStatus.of(order.getStatus());
        if (current == null || !current.canTransitionTo(OrderStatus.SHIPPED)) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL,
                    "订单当前状态（" + OrderStatus.labelOf(order.getStatus()) + "）不允许发货");
        }
        LocalDateTime now = nowUtc();
        String company = trimToNull(logisticsCompany);
        String no = trimToNull(logisticsNo);
        order.setLogisticsCompany(company);
        order.setLogisticsNo(no);
        order.setStatus(OrderStatus.SHIPPED.name());
        order.setShippedAt(now);
        order.setUpdatedAt(now);
        orderMapper.updateById(order);
        writeStatusLog(order.getId(), current.name(), OrderStatus.SHIPPED.name(),
                OPERATOR_SELLER, order.getSellerId(), reason, now);

        // 资金仍在托管中：发货不等于钱该给卖家 —— 买家还没见到货。
        // 这一句日志是为了在排查「钱为什么还没到账」时能直接看到答案。
        SettlementEntity settlement = settlementService.findByOrderId(order.getId());
        if (settlement == null) {
            log.error("订单 {} 已发货但缺少资金托管记录，放款时会失败，需人工核对", order.getOrderNo());
        } else {
            log.info("订单 {} 已发货，资金仍在托管中（{} 分），待买家确认收货后放款",
                    order.getOrderNo(), nz(settlement.getAmount()));
        }
        return toViews(List.of(order)).get(0);
    }

    /** 确认收货（已发货 → 已完成）。完成后订单流程终结，且**资金放款给卖家**。 */
    @Transactional
    public OrderView confirmReceipt(String orderNo) {
        Long userId = UserContext.requireUserId();
        OrderEntity order = findByOrderNo(userId, orderNo);
        if (order == null) {
            throw BizException.notFound("订单");
        }
        OrderStatus current = OrderStatus.of(order.getStatus());
        if (current == null || !current.canTransitionTo(OrderStatus.COMPLETED)) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL,
                    "订单当前状态（" + OrderStatus.labelOf(order.getStatus()) + "）不允许确认收货");
        }
        LocalDateTime now = nowUtc();
        order.setStatus(OrderStatus.COMPLETED.name());
        order.setFinishedAt(now);
        order.setUpdatedAt(now);
        orderMapper.updateById(order);
        writeStatusLog(order.getId(), current.name(), OrderStatus.COMPLETED.name(), OPERATOR_USER, userId, "买家确认收货", now);
        releaseFunds(order, now, "买家确认收货");
        return toViews(List.of(order)).get(0);
    }

    /**
     * 把托管中的资金放款给卖家，返回是否**本次真的放款了**。
     *
     * <p>放款失败<b>不抛异常</b>：确认收货是买家的权利，
     * 不该因为资金侧的问题让买家「点了没反应」。失败只记 ERROR/WARN，
     * 由对账与运营侧跟进 —— 钱仍留在托管中，不会凭空消失。
     *
     * <p>可重入：CAS 只让第一次成功，第二次影响 0 行，
     * 于是「买家点确认收货」和「定时任务自动确认收货」同一秒到达也不会放款两次。
     */
    private boolean releaseFunds(OrderEntity order, LocalDateTime now, String scene) {
        if (settlementService.release(order.getId(), now)) {
            log.info("订单 {} {} 完成，资金已放款给卖家 {}（托管单已置 RELEASED）",
                    order.getOrderNo(), scene, order.getSellerId());
            return true;
        }
        SettlementEntity settlement = settlementService.findByOrderId(order.getId());
        if (settlement == null) {
            log.error("订单 {} {} 完成，但缺少资金托管记录，无法放款，需人工核对", order.getOrderNo(), scene);
        } else {
            log.warn("订单 {} {} 完成，但托管单已是 {} 状态，跳过放款（可重入）",
                    order.getOrderNo(), scene, settlement.getStatus());
        }
        return false;
    }

    // ---------------- 退款（资金退回买家） ----------------

    /**
     * 买家自助退款：<b>仅「已付款、卖家未发货」</b>的订单。
     *
     * <p>为什么把界限画在「未发货」：
     * 未发货时，货还在卖家手里，退款不会造成任何纠纷 —— 钱退回去，卖家不发货，两清。
     * 一旦发货，货在买家手上，系统无从判断货有没有退回来，
     * 此时让买家一键退款就等于平台单方面承担了全部风险。
     * 所以已发货之后的退款必须走售后流程，由运营侧凭内部凭据执行（即
     * {@link #refundByOperator(String, String)}），前提是卖家已确认收到退货。
     *
     * <p>这条界限必须落在**服务端**：前端只显示正确的按钮是挡不住直接调接口的。
     */
    @Transactional
    public RefundResult refundByBuyer(String orderNo, String reason) {
        Long userId = UserContext.requireUserId();
        OrderEntity order = findByOrderNo(userId, orderNo);
        if (order == null) {
            throw BizException.notFound("订单");
        }
        OrderStatus current = OrderStatus.of(order.getStatus());
        if (current != OrderStatus.PAID) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL, buyerRefundRejection(order.getStatus()));
        }
        return doRefund(order, reason, OPERATOR_USER, userId, "买家申请退款（未发货）", nowUtc());
    }

    /**
     * 运营侧退款（售后审核通过后执行）：已付款 / 已发货的订单都可以。
     * 由 {@code InternalTokenFilter} 校验内部凭据，属于卖家/运营侧动作。
     */
    @Transactional
    public RefundResult refundByOperator(String orderNo, String reason) {
        OrderEntity order = findAnyByOrderNo(orderNo);
        if (order == null) {
            throw BizException.notFound("订单");
        }
        return refundInternal(order, reason, "运营侧退款（售后处理）");
    }

    /**
     * 售后退款的状态机本体：<b>调用方已完成归属校验</b>。
     * 与 {@link #shipInternal} 同理 —— 准入方式可以有两种，退款的资金路径只有一条。
     */
    @Transactional
    public RefundResult refundInternal(OrderEntity order, String reason, String defaultReason) {
        OrderStatus current = OrderStatus.of(order.getStatus());
        if (current != OrderStatus.PAID && current != OrderStatus.SHIPPED) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL,
                    current == OrderStatus.COMPLETED
                            ? "订单已完成、款项已放款给卖家，无法直接退款；需走追责或平台垫付（售后追偿尚未实现）"
                            : "订单当前状态（" + OrderStatus.labelOf(order.getStatus()) + "）不允许退款");
        }
        return doRefund(order, reason, OPERATOR_SELLER, order.getSellerId(), defaultReason, nowUtc());
    }

    /** 买家自助退款被拒时，按状态给出**可操作**的提示，而不是一句「不允许」 */
    private static String buyerRefundRejection(String status) {
        OrderStatus current = OrderStatus.of(status);
        if (current == OrderStatus.PENDING_PAYMENT) {
            return "订单尚未支付，无需退款；请直接取消订单";
        }
        if (current == OrderStatus.SHIPPED) {
            return "订单已发货，未发货前的自助退款通道已关闭；请在售后入口申请退货退款";
        }
        if (current == OrderStatus.COMPLETED) {
            return "订单已完成、款项已放款给卖家；请在售后入口申请售后，由平台介入处理";
        }
        return "订单当前状态（" + OrderStatus.labelOf(status) + "）不支持自助退款";
    }

    /**
     * 退款的共同路径。
     *
     * <p>顺序刻意如此（每一步都有原因）：
     * <ol>
     *   <li><b>先动钱</b>：CAS 把托管从 CUSTODY 改为 REFUNDED。这一步是整笔退款的
     *       「唯一真相」—— 它成功才说明钱真的能退；已经放款或已退过的会返回 0 行，
     *       直接拒绝，不会出现「订单标了退款但钱没退」；</li>
     *   <li>再改订单与明细，写状态日志；</li>
     *   <li>最后把台账从 DEDUCTED 改为 {@code RESTOCK_PENDING}（货待回库），
     *       并发出退款事件 —— 真正的入库在**事务提交之后**异步做。</li>
     * </ol>
     *
     * <p><b>为什么入库不放在事务里</b>：
     * 入库是一次跨服务远程调用。放在事务里，一旦对方响应慢，
     * 本地的订单行锁就被一直持着，买家的其他操作会跟着卡住；
     * 更要命的是「远程已生效、本地事务回滚」这种情况会让库存与台账对不上。
     * 拆成「事务内只写台账状态 + 事务提交后异步入库」之后，
     * 退款不再依赖商品服务的可用性，失败也有 {@code RESTOCK_PENDING} 这个可见落点。
     */
    private RefundResult doRefund(OrderEntity order, String reason, String operatorType, Long operatorId,
                                  String defaultReason, LocalDateTime now) {
        OrderStatus current = OrderStatus.of(order.getStatus());
        if (current == null || !current.canTransitionTo(OrderStatus.REFUNDED)) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL,
                    "订单当前状态（" + OrderStatus.labelOf(order.getStatus()) + "）不允许退款");
        }
        String refundReason = (reason == null || reason.isBlank()) ? defaultReason : reason.trim();
        long refundAmount = nz(order.getPayAmount());

        // 1) 资金：托管中才能退回买家
        if (!settlementService.refund(order.getId(), now)) {
            SettlementEntity settlement = settlementService.findByOrderId(order.getId());
            if (settlement == null) {
                throw new BizException(ErrorCode.BUSINESS_RULE,
                        "订单缺少资金托管记录，无法退款，请联系客服处理");
            }
            throw new BizException(ErrorCode.CONFLICT,
                    "该订单资金已是「" + SettlementStatus.labelOf(settlement.getStatus()) + "」状态，不能重复退款");
        }

        // 2) 订单与明细
        order.setStatus(OrderStatus.REFUNDED.name());
        order.setRefundedAt(now);
        order.setCloseReason(refundReason);
        order.setUpdatedAt(now);
        orderMapper.updateById(order);

        List<OrderItemEntity> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItemEntity>()
                .eq(OrderItemEntity::getOrderId, order.getId()));
        for (OrderItemEntity item : items) {
            item.setRefundStatus(REFUND_STATUS_REFUNDED);
            orderItemMapper.updateById(item);
        }

        // 3) 台账：已扣减 → 待回库
        List<StockFreezeEntity> deducted = stockFreezeMapper.selectList(
                new LambdaQueryWrapper<StockFreezeEntity>()
                        .eq(StockFreezeEntity::getOrderId, order.getId())
                        .eq(StockFreezeEntity::getStatus, StockFreezeEntity.STATUS_DEDUCTED));
        for (StockFreezeEntity freeze : deducted) {
            freeze.setStatus(StockFreezeEntity.STATUS_RESTOCK_PENDING);
            freeze.setUpdatedAt(now);
            stockFreezeMapper.updateById(freeze);
        }

        writeStatusLog(order.getId(), current.name(), OrderStatus.REFUNDED.name(),
                operatorType, operatorId, refundReason, now);

        // 4) 立刻响应里如实说明「货还没回库」（入库在事务提交后做，见 onOrderRefunded）
        boolean pending = !deducted.isEmpty();
        eventPublisher.publishEvent(new OrderRefundedEvent(order.getId()));

        log.info("订单 {} 已退款 {} 分（操作方 {}，原因：{}），待回库 SKU {} 个",
                order.getOrderNo(), refundAmount, operatorType, refundReason, deducted.size());
        return new RefundResult(
                order.getOrderNo(),
                order.getStatus(),
                OrderStatus.labelOf(order.getStatus()),
                refundAmount,
                true,
                SettlementEntity.STATUS_REFUNDED,
                SettlementStatus.labelOf(SettlementEntity.STATUS_REFUNDED),
                pending,
                isoTs(now));
    }

    /** 查询某订单的资金托管状态（仅本人可见） */
    public SettlementView settlementOf(String orderNo) {
        Long userId = UserContext.requireUserId();
        OrderEntity order = findByOrderNo(userId, orderNo);
        if (order == null) {
            throw BizException.notFound("订单");
        }
        SettlementEntity settlement = settlementService.findByOrderId(order.getId());
        if (settlement == null) {
            throw BizException.notFound("资金托管记录");
        }
        return settlementService.toView(settlement, order.getOrderNo());
    }

    /**
     * 退款已提交的领域事件（订单 ID）。
     *
     * <p>用事件而不是直接调用，是为了让入库发生在<b>事务提交之后</b>：
     * 见 {@link #doRefund} 的注释。
     */
    public record OrderRefundedEvent(Long orderId) {
    }

    /**
     * 退款提交后回补库存（退货入库）。
     *
     * <p>{@code AFTER_COMMIT} 是刻意的：对外的效果是「退款先成立，货再回库」。
     * 入库失败不会把退款回滚掉（钱该退就退），只把台账留在
     * {@code RESTOCK_PENDING}，由 {@link #sweepExpiredOrders()} 重试，
     * 因此不存在「货没回库、系统里也查不到哪一单」的情况。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderRefunded(OrderRefundedEvent event) {
        try {
            restockPending(event.orderId());
        } catch (RuntimeException e) {
            log.error("订单 {} 退款后入库回补异常，台账保持 RESTOCK_PENDING 待补偿调度重试", event.orderId(), e);
        }
    }

    /** 把某订单仍处于 RESTOCK_PENDING 的台账逐条入库回补 */
    private int[] restockPending(Long orderId) {
        List<StockFreezeEntity> pendings = stockFreezeMapper.selectList(
                new LambdaQueryWrapper<StockFreezeEntity>()
                        .eq(StockFreezeEntity::getOrderId, orderId)
                        .eq(StockFreezeEntity::getStatus, StockFreezeEntity.STATUS_RESTOCK_PENDING));
        return restockRows(pendings);
    }

    /**
     * 执行入库回补，返回 {@code [成功条数, 失败条数]}。
     *
     * <p>幂等性来自**调用方只挑 RESTOCK_PENDING 的行**：成功即置 RESTOCKED，
     * 下一轮不会再被选中。商品侧的入库接口本身没有幂等护栏（不像冻结量不足那样能自证重复），
     * 所以「只调一次」这件事必须由台账状态来保证。
     */
    private int[] restockRows(List<StockFreezeEntity> pendings) {
        if (pendings == null || pendings.isEmpty()) {
            return new int[]{0, 0};
        }
        Long orderId = pendings.get(0).getOrderId();
        List<StockChangeItem> items = pendings.stream()
                .map(f -> new StockChangeItem(f.getSkuId(), f.getQuantity()))
                .toList();
        StockChangeOutcome outcome;
        try {
            outcome = productGateway.restock(orderId, items);
        } catch (RuntimeException e) {
            log.error("退货入库调用失败（订单 {}），台账保持 RESTOCK_PENDING，待补偿调度重试", orderId, e);
            return new int[]{0, pendings.size()};
        }
        Set<Long> succeeded = succeededSkuIds(outcome);
        LocalDateTime now = nowUtc();
        int done = 0;
        for (StockFreezeEntity freeze : pendings) {
            if (succeeded.contains(freeze.getSkuId())) {
                freeze.setStatus(StockFreezeEntity.STATUS_RESTOCKED);
                freeze.setUpdatedAt(now);
                stockFreezeMapper.updateById(freeze);
                done++;
            }
        }
        if (done < pendings.size()) {
            log.error("退货入库 {}/{} 条成功（订单 {}），其余留在 RESTOCK_PENDING 待重试",
                    done, pendings.size(), orderId);
        } else {
            log.info("订单 {} 退货入库完成，{} 个 SKU 已回补", orderId, done);
        }
        return new int[]{done, pendings.size() - done};
    }

    // ---------------- 补偿调度（超时关单 / 自动收货 / 入库补偿） ----------------

    /**
     * 补偿调度：把「时间到了该发生、但没有外部触发」的事做掉。
     *
     * <p>四个步骤，全部**可重入**：
     * <ol>
     *   <li><b>超时关单</b>：待付款且已过期的订单 → CLOSED；</li>
     *   <li><b>释放冻结</b>：已关闭订单仍 FROZEN 的台账 → RELEASED。
     *       这一步不只处理本轮关掉的单，也覆盖「上几轮关单成功、但释放时商品服务挂了」的遗留 ——
     *       它是「库存不会永久冻住」的最终保障；</li>
     *   <li><b>自动确认收货</b>：已发货且超过 N 天的订单 → COMPLETED，并把托管资金**放款给卖家**。
     *       没有这一步，买家不点确认收货就能把卖家的钱无限期占在托管里；</li>
     *   <li><b>入库补偿</b>：退款时写入的 RESTOCK_PENDING 台账 → 重试入库回补。</li>
     * </ol>
     *
     * <p>可重入不是靠「恰好执行一次」（做不到），而是靠每一步的状态判据：
     * 关单只认「待付款」、释放只认 FROZEN、自动收货只认「已发货且超期」、
     * 入库只认 RESTOCK_PENDING。重复执行天然无副作用，因此不需要分布式锁，
     * 也不需要担心调度重复触发。
     *
     * <p>单实例开发环境用 {@code @Scheduled} 足够；生产多实例必须交给 XXL-Job 之类的
     * 分布式调度（否则每个实例都会扫一遍 —— 虽然可重入不会错，但会浪费与放大压力）。
     */
    @Scheduled(fixedDelayString = "${duoduo.trade.sweep-interval-ms:60000}",
            initialDelayString = "${duoduo.trade.sweep-initial-delay-ms:30000}")
    @Transactional
    public void scheduledSweep() {
        try {
            SweepResult result = sweepExpiredOrders();
            if (result.scanned() > 0 || result.closed() > 0 || result.released() > 0 || result.releaseFailed() > 0
                    || result.autoCompleted() > 0 || result.restocked() > 0 || result.restockFailed() > 0) {
                log.info("补偿调度：扫描 {} 单，关闭 {} 单，释放 {} 条冻结（失败 {}），"
                                + "自动收货 {} 单（放款 {} 笔），入库回补 {} 条（失败 {}）",
                        result.scanned(), result.closed(), result.released(), result.releaseFailed(),
                        result.autoCompleted(), result.fundsReleased(), result.restocked(), result.restockFailed());
            }
        } catch (RuntimeException e) {
            // 调度线程里必须吞掉异常，否则一次异常会让后续调度静默停摆
            log.error("补偿调度执行异常", e);
        }
    }

    /**
     * 真正执行一轮补偿调度（关单 / 释放 / 自动收货 / 入库补偿）。
     *
     * <p>注意：{@code @Transactional} 加在<b>本方法</b>上是刻意的 ——
     * 调度入口 {@link #scheduledSweep()} 内部调用本方法属于「自调用」，
     * Spring 的事务代理不生效，所以事务边界必须由被调用的这一层自己声明，
     * 且调度入口也标注了 {@code @Transactional} 以覆盖自调用路径。
     */
    @Transactional
    public SweepResult sweepExpiredOrders() {
        LocalDateTime now = nowUtc();
        int closed = 0;

        // 1) 关单：待付款且已过期
        List<OrderEntity> expired = orderMapper.selectList(new LambdaQueryWrapper<OrderEntity>()
                .eq(OrderEntity::getStatus, OrderStatus.PENDING_PAYMENT.name())
                .le(OrderEntity::getExpireAt, now)
                .orderByAsc(OrderEntity::getExpireAt)
                .last("LIMIT " + Math.max(1, sweepBatchSize)));
        for (OrderEntity order : expired) {
            String reason = "超时未支付，系统自动关闭";
            order.setStatus(OrderStatus.CLOSED.name());
            order.setClosedAt(now);
            order.setCloseReason(reason);
            order.setUpdatedAt(now);
            orderMapper.updateById(order);
            writeStatusLog(order.getId(), OrderStatus.PENDING_PAYMENT.name(), OrderStatus.CLOSED.name(),
                    OPERATOR_SYSTEM, null, reason, now);
            paymentMapper.markClosedByOrder(order.getId(), reason, now);
            closed++;
        }

        // 1.5) 支付单过期：PENDING 且已过自身过期时间的，二维码作废。
        // 只动 PENDING（CAS），已成功 / 已作废的不受影响；过期支付单对应的订单
        // 仍处于待付款时，买家可以重新发起支付（新支付单、新二维码）。
        List<PaymentEntity> stalePayments = paymentMapper.selectList(new LambdaQueryWrapper<PaymentEntity>()
                .eq(PaymentEntity::getStatus, PaymentEntity.STATUS_PENDING)
                .le(PaymentEntity::getExpireAt, now)
                .last("LIMIT " + Math.max(1, sweepBatchSize)));
        int paymentsExpired = 0;
        for (PaymentEntity stale : stalePayments) {
            paymentsExpired += paymentMapper.markExpired(stale.getPayNo(), now);
        }
        if (paymentsExpired > 0) {
            log.info("补偿调度：{} 张支付单超时未支付，已置为 EXPIRED（二维码作废）", paymentsExpired);
        }

        // 2) 释放：所有仍为 FROZEN 且**所属订单已关闭**的台账行（含历史遗留），按订单分组逐单释放。
        //
        // 判据刻意用「订单状态」而不是「台账自己的 expire_at」：
        // 台账上的 expire_at 与订单的过期时间是同一件事的两份副本，一旦两者不同步
        // （改期、补数据、时区处理差异都会造成），按台账判就会出现「订单已关闭、库存却一直冻着」。
        // 订单状态是唯一真相，释放必须以它为准。
        List<StockFreezeEntity> pendingFreezes = stockFreezeMapper.selectList(
                new LambdaQueryWrapper<StockFreezeEntity>()
                        .eq(StockFreezeEntity::getStatus, StockFreezeEntity.STATUS_FROZEN)
                        .orderByAsc(StockFreezeEntity::getCreatedAt)
                        .last("LIMIT " + (Math.max(1, sweepBatchSize) * 4)));
        Set<Long> candidateOrderIds = pendingFreezes.stream()
                .map(StockFreezeEntity::getOrderId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        int released = 0;
        int releaseFailed = 0;
        for (Long orderId : candidateOrderIds) {
            OrderEntity order = orderMapper.selectById(orderId);
            if (order == null) {
                // 订单不存在（数据异常）：不擅自释放，留证据给人查
                log.error("库存冻结台账指向的订单 {} 不存在，跳过释放，请人工核对", orderId);
                continue;
            }
            if (!OrderStatus.CLOSED.name().equals(order.getStatus())) {
                // 订单还没关闭（正常在途），冻结本就该保留
                continue;
            }
            int[] result = releaseOrderFreeze(orderId, now);
            released += result[0];
            releaseFailed += result[1];
        }
        if (releaseFailed > 0) {
            log.error("补偿调度：{} 条冻结未能释放，台账保持 FROZEN，下轮重试（常见成因：商品服务不可用，"
                    + "或该 SKU 冻结量已被并发释放且原因码非 FREEZE_NOT_ENOUGH，需人工核对）", releaseFailed);
        }

        // 3) 自动确认收货：发货已满 N 天，视为买家默认收货，并放款给卖家。
        //    判据是「已发货 + shipped_at 超期」，同样是幂等的：完成后状态变 COMPLETED，下轮不会再被扫到。
        //    这一步是「卖家的钱不会被无限期占在托管里」的保障。
        int autoCompleted = 0;
        int fundsReleased = 0;
        if (autoReceiptDays > 0) {
            LocalDateTime deadline = now.minusDays(autoReceiptDays);
            List<OrderEntity> overdue = orderMapper.selectList(new LambdaQueryWrapper<OrderEntity>()
                    .eq(OrderEntity::getStatus, OrderStatus.SHIPPED.name())
                    .le(OrderEntity::getShippedAt, deadline)
                    .orderByAsc(OrderEntity::getShippedAt)
                    .last("LIMIT " + Math.max(1, sweepBatchSize)));
            for (OrderEntity order : overdue) {
                String reason = "发货满 " + autoReceiptDays + " 天，系统自动确认收货";
                order.setStatus(OrderStatus.COMPLETED.name());
                order.setFinishedAt(now);
                order.setUpdatedAt(now);
                orderMapper.updateById(order);
                writeStatusLog(order.getId(), OrderStatus.SHIPPED.name(), OrderStatus.COMPLETED.name(),
                        OPERATOR_SYSTEM, null, reason, now);
                autoCompleted++;
                if (releaseFunds(order, now, "自动确认收货")) {
                    fundsReleased++;
                }
            }
        }

        // 4) 入库补偿：退款时写入的 RESTOCK_PENDING 台账，重试把货补回库存。
        //    退款不因入库失败而回滚（钱该退就退），所以这里必须有兜底重试，
        //    否则「钱退了、货没回库」就成了系统里看不见的差额。
        List<StockFreezeEntity> pendingRestocks = stockFreezeMapper.selectList(
                new LambdaQueryWrapper<StockFreezeEntity>()
                        .eq(StockFreezeEntity::getStatus, StockFreezeEntity.STATUS_RESTOCK_PENDING)
                        .orderByAsc(StockFreezeEntity::getCreatedAt)
                        .last("LIMIT " + (Math.max(1, sweepBatchSize) * 4)));
        Map<Long, List<StockFreezeEntity>> restockByOrder = pendingRestocks.stream()
                .collect(Collectors.groupingBy(StockFreezeEntity::getOrderId,
                        LinkedHashMap::new, Collectors.toList()));
        int restocked = 0;
        int restockFailed = 0;
        for (List<StockFreezeEntity> rows : restockByOrder.values()) {
            int[] result = restockRows(rows);
            restocked += result[0];
            restockFailed += result[1];
        }
        if (restockFailed > 0) {
            log.error("补偿调度：{} 条退货入库未能完成，台账保持 RESTOCK_PENDING，下轮重试", restockFailed);
        }

        return new SweepResult(expired.size(), closed, released, releaseFailed,
                autoCompleted, fundsReleased, restocked, restockFailed);
    }

    // ---------------- 内部实现 ----------------

    private OrderEntity findByIdempotentKey(Long userId, String key) {
        return orderMapper.selectOne(new LambdaQueryWrapper<OrderEntity>()
                .eq(OrderEntity::getUserId, userId)
                .eq(OrderEntity::getIdempotentKey, key));
    }

    private OrderEntity findByOrderNo(Long userId, String orderNo) {
        return orderMapper.selectOne(new LambdaQueryWrapper<OrderEntity>()
                .eq(OrderEntity::getUserId, userId)
                .eq(OrderEntity::getOrderNo, orderNo));
    }

    /** 不带用户范围查订单：仅用于卖家侧 / 运营侧动作（发货），调用方已由内部凭据保护 */
    private OrderEntity findAnyByOrderNo(String orderNo) {
        return orderMapper.selectOne(new LambdaQueryWrapper<OrderEntity>()
                .eq(OrderEntity::getOrderNo, orderNo));
    }

    private PaymentEntity findPayment(String channel, String idempotentKey) {
        return paymentMapper.selectOne(new LambdaQueryWrapper<PaymentEntity>()
                .eq(PaymentEntity::getChannel, channel.trim().toUpperCase())
                .eq(PaymentEntity::getIdempotentKey, idempotentKey));
    }

    /** 支付幂等重放：返回首次支付的结果，不产生任何副作用 */
    private PayResult replayPayment(OrderEntity order, PaymentEntity payment) {
        return toPayResult(order, payment, true);
    }

    /**
     * 确认收款（入账）：即时渠道的预下单路径与渠道回调路径共用这一段，
     * 保证两条路径对「订单 / 库存 / 托管」的副作用完全一致。
     *
     * <p>第一步先用 <b>CAS</b> 把支付单置为 SUCCESS（只有 INIT / PENDING 能被置入）。
     * 并发回调只可能有一个成功，落败的一方把库里的最终状态读出来按重放返回 ——
     * 这比「先扣库存再改状态」安全：库存扣减发生在 CAS 之后，
     * 两个并发回调不会都走到扣库存那一步。
     *
     * <p>扣库存失败会连 CAS 一起回滚（同一事务），客户端可重新发起支付。
     */
    private PayResult completePayment(OrderEntity order, PaymentEntity payment,
                                      String channelTradeNo, LocalDateTime paidAt, boolean replay) {
        LocalDateTime now = nowUtc();
        int marked = paymentMapper.markSuccess(payment.getPayNo(), channelTradeNo, now);
        if (marked == 0) {
            // 并发已有一方入账：读出最终状态按重放返回，不再产生任何副作用
            PaymentEntity winner = paymentMapper.selectById(payment.getId());
            return toPayResult(order, winner == null ? payment : winner, true);
        }

        // 扣减库存：库存与冻结量同时减少；失败则整批回滚（含上面的 CAS），本次支付不成立
        List<StockFreezeEntity> freezes = stockFreezeMapper.selectList(
                new LambdaQueryWrapper<StockFreezeEntity>()
                        .eq(StockFreezeEntity::getOrderId, order.getId())
                        .eq(StockFreezeEntity::getStatus, StockFreezeEntity.STATUS_FROZEN));
        if (freezes.isEmpty()) {
            throw new BizException(ErrorCode.BUSINESS_RULE, "订单没有可扣减的库存冻结记录，请联系客服处理");
        }
        List<StockChangeItem> items = freezes.stream()
                .map(f -> new StockChangeItem(f.getSkuId(), f.getQuantity()))
                .toList();
        StockChangeOutcome outcome = productGateway.deduct(order.getId(), items);
        if (outcome == null || !outcome.allSuccess()) {
            throw new BizException(ErrorCode.STOCK_INSUFFICIENT,
                    "扣减库存失败：" + describeFailure(outcome, Map.of()));
        }

        // 同步内存对象，供返回视图使用（库内状态已由上面的 CAS 落好）
        payment.setStatus(PaymentEntity.STATUS_SUCCESS);
        payment.setChannelTradeNo(channelTradeNo);
        payment.setPaidAt(paidAt == null ? now : paidAt);

        OrderStatus current = OrderStatus.of(order.getStatus());
        order.setStatus(OrderStatus.PAID.name());
        order.setPaidAt(now);
        order.setUpdatedAt(now);
        orderMapper.updateById(order);
        writeStatusLog(order.getId(), current == null ? "UNKNOWN" : current.name(), OrderStatus.PAID.name(),
                OPERATOR_USER, order.getUserId(),
                "支付成功（渠道 " + payment.getChannel() + "，流水号 " + channelTradeNo + "）", now);
        markFreezeDeducted(order.getId(), now);

        // 钱进平台托管（C2C 担保交易的核心语义）：
        // 此刻买家已经付了钱，但卖家还不能拿到 —— 要等买家确认收货。
        // 托管单的创建是幂等的（uk_settle_order），重复支付回调不会建出两条。
        settlementService.openCustody(order.getId(), order.getSellerId(), nz(order.getPayAmount()), now);

        return toPayResult(order, payment, replay);
    }

    private PayResult toPayResult(OrderEntity order, PaymentEntity payment, boolean replay) {
        return new PayResult(
                order.getOrderNo(),
                payment.getPayNo(),
                payment.getChannel(),
                nz(payment.getAmount()),
                payment.getStatus(),
                order.getStatus(),
                OrderStatus.labelOf(order.getStatus()),
                replay,
                payment.getCodeUrl(),
                isoTs(payment.getExpireAt()));
    }

    /** 幂等重放：返回该幂等键首次产生的订单 */
    private PlaceOrderResult replay(Long userId, OrderEntity order) {
        List<OrderView> views = toViews(List.of(order));
        return new PlaceOrderResult(views, views.size(),
                views.stream().mapToLong(OrderView::payAmount).sum(), true);
    }

    private void writeStatusLog(Long orderId, String from, String to,
                                String operatorType, Long operatorId, String reason, LocalDateTime now) {
        OrderStatusLogEntity entity = new OrderStatusLogEntity();
        entity.setId(idGenerator.nextId());
        entity.setOrderId(orderId);
        entity.setFromStatus(from);
        entity.setToStatus(to);
        entity.setOperatorType(operatorType);
        entity.setOperatorId(operatorId);
        entity.setReason(reason);
        entity.setCreatedAt(now);
        statusLogMapper.insert(entity);
    }

    /**
     * 批量装配订单视图（明细与托管单都一次查回，避免逐单查询造成 N+1）。
     *
     * <p>开放给卖家中心（{@code SellerService}）复用：买家端与商家端看的是同一份订单事实，
     * 「明细快照 + 托管状态」的装配口径必须只有一处实现，
     * 否则迟早出现「买家看到已放款、卖家看到托管中」这种对不上账的分歧。
     */
    public List<OrderView> toViews(List<OrderEntity> orders) {
        if (orders == null || orders.isEmpty()) {
            return List.of();
        }
        List<Long> orderIds = orders.stream().map(OrderEntity::getId).toList();
        Map<Long, List<OrderItemEntity>> itemsByOrder = orderItemMapper.selectList(
                        new LambdaQueryWrapper<OrderItemEntity>()
                                .in(OrderItemEntity::getOrderId, orderIds)
                                .orderByAsc(OrderItemEntity::getId))
                .stream()
                .collect(Collectors.groupingBy(OrderItemEntity::getOrderId));
        Map<Long, SettlementEntity> settlements = settlementService.mapByOrderIds(orderIds);

        List<OrderView> views = new ArrayList<>(orders.size());
        for (OrderEntity order : orders) {
            List<OrderItemView> items = itemsByOrder
                    .getOrDefault(order.getId(), List.of())
                    .stream()
                    .map(i -> new OrderItemView(
                            i.getSkuId(), i.getSpuId(), i.getTitle(), i.getSpecText(), i.getMainImage(),
                            nz(i.getPrice()), i.getQuantity() == null ? 0 : i.getQuantity(), nz(i.getAmount())))
                    .toList();
            views.add(new OrderView(
                    order.getOrderNo(),
                    order.getSellerId(),
                    order.getOrderType(),
                    order.getStatus(),
                    OrderStatus.labelOf(order.getStatus()),
                    nz(order.getTotalAmount()),
                    nz(order.getFreightAmount()),
                    nz(order.getDiscountAmount()),
                    nz(order.getPayAmount()),
                    order.getReceiverName(),
                    order.getReceiverPhone(),
                    order.getReceiverAddress(),
                    order.getRemark(),
                    items,
                    settlementService.toView(settlements.get(order.getId()), order.getOrderNo()),
                    isoTs(order.getCreatedAt()),
                    isoTs(order.getExpireAt()),
                    isoTs(order.getPaidAt()),
                    isoTs(order.getShippedAt()),
                    isoTs(order.getFinishedAt()),
                    isoTs(order.getClosedAt()),
                    isoTs(order.getRefundedAt()),
                    order.getCloseReason(),
                    order.getLogisticsCompany(),
                    order.getLogisticsNo()));
        }
        return views;
    }

    /** 从库存变更结果里挑出「真的冻成功了」的 SKU（用于精确补偿） */
    private static Set<Long> succeededSkuIds(StockChangeOutcome outcome) {
        if (outcome == null || outcome.results() == null) {
            return Set.of();
        }
        return outcome.results().stream()
                .filter(StockChangeResult::success)
                .map(StockChangeResult::skuId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** 补偿释放：失败只记日志，不再向外抛 —— 否则会掩盖真正的原始异常 */
    private void releaseQuietly(List<FrozenRef> refs, String scene) {
        if (refs.isEmpty()) {
            return;
        }
        try {
            List<StockChangeItem> items = refs.stream()
                    .map(r -> new StockChangeItem(r.skuId(), r.quantity()))
                    .toList();
            StockChangeOutcome outcome = productGateway.release(null, items);
            if (outcome == null || !outcome.allSuccess()) {
                log.error("{}：库存释放未全部成功，需人工核对 {}", scene, describeFailure(outcome, Map.of()));
            } else {
                log.info("{}：已释放 {} 条预冻结库存", scene, items.size());
            }
        } catch (RuntimeException e) {
            log.error("{}：释放预冻结库存时商品服务不可用，需人工核对 {}", scene, refs, e);
        }
    }

    private String describeFailure(StockChangeOutcome outcome, Map<Long, SkuSnapshot> snapshots) {
        if (outcome == null || outcome.results() == null) {
            return "库存服务无响应";
        }
        return outcome.results().stream()
                .filter(r -> !r.success())
                .map(r -> {
                    SkuSnapshot snap = snapshots.get(r.skuId());
                    String name = snap == null ? ("SKU " + r.skuId()) : safeTitle(snap);
                    return "「" + name + "」" + reasonText(r.reason());
                })
                .collect(Collectors.joining("；"));
    }

    private static String reasonText(String reason) {
        if (reason == null) {
            return "库存变更失败";
        }
        return switch (reason) {
            case "SKU_NOT_FOUND" -> "商品不存在";
            case "SKU_INACTIVE" -> "商品已下架";
            case "INSUFFICIENT_STOCK" -> "库存不足";
            case "FREEZE_NOT_ENOUGH" -> "冻结量不足";
            default -> reason;
        };
    }

    private static String safeTitle(SkuSnapshot snap) {
        return snap.spuTitle() == null ? ("SKU " + snap.skuId()) : snap.spuTitle();
    }

    private static String isoTs(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC).toString();
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    /** 空串一律按「没填」处理，避免库里出现只有空格的物流公司名 */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /** 已成功冻结的 SKU 与数量，用于异常补偿 */
    private record FrozenRef(Long skuId, Integer quantity) {
    }
}
