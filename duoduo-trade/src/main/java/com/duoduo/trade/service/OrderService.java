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
import com.duoduo.trade.dto.OrderDtos.PlaceOrderRequest;
import com.duoduo.trade.dto.OrderDtos.PlaceOrderResult;
import com.duoduo.trade.entity.CartItemEntity;
import com.duoduo.trade.entity.OrderEntity;
import com.duoduo.trade.entity.OrderItemEntity;
import com.duoduo.trade.entity.OrderStatusLogEntity;
import com.duoduo.trade.entity.StockFreezeEntity;
import com.duoduo.trade.mapper.CartItemMapper;
import com.duoduo.trade.mapper.OrderItemMapper;
import com.duoduo.trade.mapper.OrderMapper;
import com.duoduo.trade.mapper.OrderStatusLogMapper;
import com.duoduo.trade.mapper.StockFreezeMapper;
import com.duoduo.trade.support.OrderNoGenerator;
import com.duoduo.trade.support.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * 订单服务：下单（含拆单）与取消（含库存释放）。
 *
 * <p>五个刻意的工程决策：
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
    private static final String OP_CREATE = "创建订单";
    private static final int SELECTED = 1;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderStatusLogMapper statusLogMapper;
    private final StockFreezeMapper stockFreezeMapper;
    private final CartItemMapper cartItemMapper;
    private final ProductGateway productGateway;
    private final SnowflakeIdGenerator idGenerator;
    private final OrderNoGenerator orderNoGenerator;

    @Value("${duoduo.trade.order-expire-minutes:30}")
    private int orderExpireMinutes;

    @Value("${duoduo.trade.max-items-per-order:50}")
    private int maxItemsPerOrder;

    public OrderService(OrderMapper orderMapper,
                        OrderItemMapper orderItemMapper,
                        OrderStatusLogMapper statusLogMapper,
                        StockFreezeMapper stockFreezeMapper,
                        CartItemMapper cartItemMapper,
                        ProductGateway productGateway,
                        SnowflakeIdGenerator idGenerator,
                        OrderNoGenerator orderNoGenerator) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.statusLogMapper = statusLogMapper;
        this.stockFreezeMapper = stockFreezeMapper;
        this.cartItemMapper = cartItemMapper;
        this.productGateway = productGateway;
        this.idGenerator = idGenerator;
        this.orderNoGenerator = orderNoGenerator;
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

        // 4) 按卖家拆单（当前全部自营 → 只有一个分组）
        Map<Long, List<CartItemEntity>> groups = rows.stream()
                .collect(Collectors.groupingBy(r -> PLATFORM_SELLER_ID,
                        LinkedHashMap::new, Collectors.toList()));

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

    // ---------------- 取消（含释放冻结） ----------------

    @Transactional
    public OrderView cancel(String orderNo, String reason) {
        Long userId = UserContext.requireUserId();
        OrderEntity order = findByOrderNo(userId, orderNo);
        if (order == null) {
            throw BizException.notFound("订单");
        }
        OrderStatus current = OrderStatus.of(order.getStatus());
        if (current == null || !current.canTransitionTo(OrderStatus.CLOSED)) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL,
                    "订单当前状态（" + OrderStatus.labelOf(order.getStatus()) + "）不允许取消");
        }

        LocalDateTime now = nowUtc();
        String closeReason = (reason == null || reason.isBlank()) ? "用户取消" : reason.trim();
        order.setStatus(OrderStatus.CLOSED.name());
        order.setClosedAt(now);
        order.setCloseReason(closeReason);
        order.setUpdatedAt(now);
        orderMapper.updateById(order);
        writeStatusLog(order.getId(), current.name(), OrderStatus.CLOSED.name(), OPERATOR_USER, userId, closeReason, now);

        releaseOrderFreeze(order, now);
        return toViews(List.of(order)).get(0);
    }

    /**
     * 释放某订单仍处于 FROZEN 的冻结。
     *
     * <p>逐条落状态，因此**可重入**：已释放的不会被重复释放。
     * 若商品服务此刻不可用，只记录 ERROR 而**不**让取消失败 ——
     * 用户的取消意图应当被尊重，未释放的台账行保持 FROZEN 供后续重试
     * （超时关单的补偿调度会扫描它们，见 M5-6）。
     */
    private void releaseOrderFreeze(OrderEntity order, LocalDateTime now) {
        List<StockFreezeEntity> freezes = stockFreezeMapper.selectList(
                new LambdaQueryWrapper<StockFreezeEntity>()
                        .eq(StockFreezeEntity::getOrderId, order.getId())
                        .eq(StockFreezeEntity::getStatus, StockFreezeEntity.STATUS_FROZEN));
        if (freezes.isEmpty()) {
            return;
        }
        List<StockChangeItem> items = freezes.stream()
                .map(f -> new StockChangeItem(f.getSkuId(), f.getQuantity()))
                .toList();
        StockChangeOutcome outcome = productGateway.release(order.getId(), items);

        Set<Long> succeeded = new LinkedHashSet<>();
        if (outcome != null && outcome.results() != null) {
            for (StockChangeResult r : outcome.results()) {
                if (r.success()) {
                    succeeded.add(r.skuId());
                }
            }
        }
        int releasedCount = 0;
        for (StockFreezeEntity freeze : freezes) {
            if (succeeded.contains(freeze.getSkuId())) {
                freeze.setStatus(StockFreezeEntity.STATUS_RELEASED);
                freeze.setUpdatedAt(now);
                stockFreezeMapper.updateById(freeze);
                releasedCount++;
            }
        }
        if (releasedCount < freezes.size()) {
            log.error("订单 {} 取消后仍有 {} 条库存冻结未释放（商品服务异常），台账保持 FROZEN 待重试",
                    order.getOrderNo(), freezes.size() - releasedCount);
        }
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

    /** 批量装配订单视图（明细一次查回，避免逐单查询） */
    private List<OrderView> toViews(List<OrderEntity> orders) {
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
                    isoTs(order.getCreatedAt()),
                    isoTs(order.getExpireAt()),
                    isoTs(order.getClosedAt()),
                    order.getCloseReason()));
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

    private static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /** 已成功冻结的 SKU 与数量，用于异常补偿 */
    private record FrozenRef(Long skuId, Integer quantity) {
    }
}
