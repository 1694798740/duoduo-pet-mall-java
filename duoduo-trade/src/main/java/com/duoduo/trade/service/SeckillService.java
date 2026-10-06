package com.duoduo.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.trade.client.ProductGateway;
import com.duoduo.trade.client.UserAddressGateway;
import com.duoduo.trade.client.dto.AddressCallDtos.ReceiverSnapshot;
import com.duoduo.trade.client.dto.SkuSnapshot;
import com.duoduo.trade.dto.SeckillDtos.ActivityView;
import com.duoduo.trade.dto.SeckillDtos.CreateActivityRequest;
import com.duoduo.trade.dto.SeckillDtos.EditActivityRequest;
import com.duoduo.trade.dto.SeckillDtos.PlaceSeckillRequest;
import com.duoduo.trade.dto.SeckillDtos.PlaceSeckillResult;
import com.duoduo.trade.dto.SeckillDtos.SkuView;
import com.duoduo.trade.entity.OrderEntity;
import com.duoduo.trade.entity.OrderItemEntity;
import com.duoduo.trade.entity.OrderStatusLogEntity;
import com.duoduo.trade.entity.SeckillActivityEntity;
import com.duoduo.trade.entity.SeckillOrderEntity;
import com.duoduo.trade.entity.SeckillSkuEntity;
import com.duoduo.trade.mapper.OrderItemMapper;
import com.duoduo.trade.mapper.OrderMapper;
import com.duoduo.trade.mapper.OrderStatusLogMapper;
import com.duoduo.trade.mapper.SeckillActivityMapper;
import com.duoduo.trade.mapper.SeckillOrderMapper;
import com.duoduo.trade.mapper.SeckillSkuMapper;
import com.duoduo.trade.support.OrderNoGenerator;
import com.duoduo.trade.support.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 限时秒杀（A14）。
 *
 * <p><b>分层防线</b>（从外到内）：
 * <ol>
 *   <li>Redis Lua 预扣（活动余量 + 单人限购一次判掉）：挡住绝大多数无效流量，
 *       脚本原子，不存在「先 GET 再 DECR」的竞态；</li>
 *   <li>DB 原子扣减（{@code UPDATE ... WHERE available_stock > 0}）：
 *       正确性的最后防线，行锁串行化，影响行数为 0 即抢完，<b>绝不超卖</b>；</li>
 *   <li>t_order 幂等唯一索引：同一幂等键重复提交返回首次订单（replay）。</li>
 * </ol>
 *
 * <p><b>Redis 是软依赖</b>：连接失败 / 命令超时一律降级为纯 DB 路径（本地零依赖开发、
 * CI、Redis 故障都不影响正确性，只损失吞吐）。这也解释了为什么 trade 服务**不因
 * Redis 缺席而拒绝启动** —— 与 A11 的「order-mode=sync 时零 MQ 依赖」同一设计哲学。
 *
 * <p><b>秒杀库存与商品库存相互独立</b>：活动配额就是秒杀自己的「货」，
 * 下单不冻结商品库存（少一次跨服务写，秒杀链路更短）；关单 / 取消时回补配额。
 * 由此换来的边界：秒杀订单不产生 t_stock_freeze 台账，售后退款也不会回补
 * 商品库存 —— 配额在订单关闭时才回补，这是刻意的口径，不是遗漏。
 *
 * <p><b>已知边界</b>（如实记录）：
 * <ul>
 *   <li>Redis 预扣成功但事务提交失败（极少）：DB 配额没扣，Redis 计数偏低 → 少卖不超卖，
 *       与「宁可少卖不可超卖」的口径一致；</li>
 *   <li>compose 的 Redis 配了 allkeys-lru：余量键可能被逐出，首次访问时按 DB 值
 *       重新装载（{@link #ensureRedisStock}），LRU 逐出只会造成短时的纯 DB 放行。</li>
 * </ul>
 */
@Service
public class SeckillService {

    private static final Logger log = LoggerFactory.getLogger(SeckillService.class);

    private static final String ORDER_TYPE_SECKILL = "SECKILL";
    private static final String PHASE_UPCOMING = "UPCOMING";
    private static final String PHASE_ONGOING = "ONGOING";
    private static final String PHASE_ENDED = "ENDED";

    private static final String KEY_STOCK = "seckill:stock:";
    private static final String KEY_BOUGHT = "seckill:bought:";

    /**
     * 预扣脚本：KEYS[1]=活动余量，KEYS[2]=个人已购；ARGV[1]=限购件数。
     * 返回 1=预扣成功；-1=余量键不存在（调用方装载后重试一次）；-2=超出限购；-3=已抢完。
     */
    private static final String PRE_DEDUCT_LUA = """
            local stock = tonumber(redis.call('GET', KEYS[1]))
            if stock == nil then return -1 end
            local bought = tonumber(redis.call('GET', KEYS[2]) or '0')
            if bought >= tonumber(ARGV[1]) then return -2 end
            if stock <= 0 then return -3 end
            redis.call('DECR', KEYS[1])
            redis.call('INCR', KEYS[2])
            return 1
            """;

    /** 回滚脚本：DB 扣减失败时把预扣吐回去。余量键必须存在才 INCR（防逐出后复活成 1）。 */
    private static final String ROLLBACK_LUA = """
            if redis.call('EXISTS', KEYS[1]) == 1 then redis.call('INCR', KEYS[1]) end
            local bought = tonumber(redis.call('GET', KEYS[2]) or '0')
            if bought > 0 then redis.call('DECR', KEYS[2]) end
            return 1
            """;

    /** 回补脚本：订单关闭时余量 +1（键不存在则放弃 —— 会在下次访问时按 DB 重新装载）。 */
    private static final String RESTOCK_LUA = """
            if redis.call('EXISTS', KEYS[1]) == 1 then redis.call('INCR', KEYS[1]) end
            return 1
            """;

    private final SeckillActivityMapper activityMapper;
    private final SeckillSkuMapper seckillSkuMapper;
    private final SeckillOrderMapper seckillOrderMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderStatusLogMapper statusLogMapper;
    private final ProductGateway productGateway;
    private final UserAddressGateway userAddressGateway;
    private final OrderNoGenerator orderNoGenerator;
    private final SnowflakeIdGenerator idGenerator;
    private final StringRedisTemplate redis;
    private final long orderExpireMinutes;

    private final DefaultRedisScript<Long> preDeductScript = new DefaultRedisScript<>(PRE_DEDUCT_LUA, Long.class);
    private final DefaultRedisScript<Long> rollbackScript = new DefaultRedisScript<>(ROLLBACK_LUA, Long.class);
    private final DefaultRedisScript<Long> restockScript = new DefaultRedisScript<>(RESTOCK_LUA, Long.class);

    public SeckillService(SeckillActivityMapper activityMapper,
                          SeckillSkuMapper seckillSkuMapper,
                          SeckillOrderMapper seckillOrderMapper,
                          OrderMapper orderMapper,
                          OrderItemMapper orderItemMapper,
                          OrderStatusLogMapper statusLogMapper,
                          ProductGateway productGateway,
                          UserAddressGateway userAddressGateway,
                          OrderNoGenerator orderNoGenerator,
                          SnowflakeIdGenerator idGenerator,
                          StringRedisTemplate redis,
                          @Value("${duoduo.trade.order-expire-minutes:30}") long orderExpireMinutes) {
        this.activityMapper = activityMapper;
        this.seckillSkuMapper = seckillSkuMapper;
        this.seckillOrderMapper = seckillOrderMapper;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.statusLogMapper = statusLogMapper;
        this.productGateway = productGateway;
        this.userAddressGateway = userAddressGateway;
        this.orderNoGenerator = orderNoGenerator;
        this.idGenerator = idGenerator;
        this.redis = redis;
        this.orderExpireMinutes = orderExpireMinutes;
    }

    // ---------------- C 端查询 ----------------

    /** 活动列表（只含运营开关开启的活动），按开始时间倒序。 */
    public List<ActivityView> listActivities() {
        List<SeckillActivityEntity> activities = activityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivityEntity>()
                        .eq(SeckillActivityEntity::getStatus, SeckillActivityEntity.STATUS_ENABLED)
                        .orderByDesc(SeckillActivityEntity::getStartAt));
        return toViews(activities);
    }

    /**
     * 运营端全量列表（**含已下架**）。
     *
     * <p>与 C 端 {@link #listActivities()} 的区别只在过滤条件：运营必须能看到自己下架过的活动，
     * 否则「下架」是一次性操作 —— 活动从列表消失后没有任何入口能把它恢复上架。
     */
    public List<ActivityView> listActivitiesForAdmin() {
        List<SeckillActivityEntity> activities = activityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivityEntity>()
                        .orderByDesc(SeckillActivityEntity::getStartAt));
        return toViews(activities);
    }

    public ActivityView detail(Long activityId) {
        SeckillActivityEntity activity = requireEnabledActivity(activityId);
        return toViews(List.of(activity)).get(0);
    }

    private ActivityView toView(SeckillActivityEntity activity, List<SeckillSkuEntity> skus) {
        // 商品快照一次批量取回（每场活动只有几个 SKU，不存在 N+1）
        Set<Long> skuIds = skus.stream().map(SeckillSkuEntity::getSkuId).collect(java.util.stream.Collectors.toSet());
        Map<Long, SkuSnapshot> snapshots = skuIds.isEmpty()
                ? Map.of()
                : productGateway.loadSnapshots(new ArrayList<>(skuIds));

        List<SkuView> views = new ArrayList<>(skus.size());
        for (SeckillSkuEntity s : skus) {
            SkuSnapshot snap = snapshots.get(s.getSkuId());
            views.add(new SkuView(
                    s.getSkuId(),
                    snap == null ? "商品 " + s.getSkuId() : snap.spuTitle(),
                    snap == null ? null : snap.specText(),
                    snap == null ? null : snap.image(),
                    s.getSeckillPrice(),
                    s.getOriginalPrice(),
                    s.getTotalStock(),
                    s.getAvailableStock(),
                    s.getPerLimit()));
        }
        LocalDateTime now = nowUtc();
        String phase;
        if (now.isBefore(activity.getStartAt())) {
            phase = PHASE_UPCOMING;
        } else if (now.isAfter(activity.getEndAt())) {
            phase = PHASE_ENDED;
        } else {
            phase = PHASE_ONGOING;
        }
        return new ActivityView(activity.getId(), activity.getTitle(),
                activity.getStartAt(), activity.getEndAt(), phase,
                SeckillActivityEntity.STATUS_ENABLED.equals(activity.getStatus()), views);
    }

    private List<ActivityView> toViews(List<SeckillActivityEntity> activities) {
        if (activities.isEmpty()) {
            return List.of();
        }
        List<Long> ids = activities.stream().map(SeckillActivityEntity::getId).toList();
        // 全部活动的秒杀 SKU 一次查回，按 activityId 分组
        Map<Long, List<SeckillSkuEntity>> byActivity = new LinkedHashMap<>();
        for (SeckillSkuEntity s : seckillSkuMapper.selectList(
                new LambdaQueryWrapper<SeckillSkuEntity>().in(SeckillSkuEntity::getActivityId, ids))) {
            byActivity.computeIfAbsent(s.getActivityId(), k -> new ArrayList<>()).add(s);
        }
        return activities.stream()
                .map(a -> toView(a, byActivity.getOrDefault(a.getId(), List.of())))
                .toList();
    }

    // ---------------- C 端秒杀下单 ----------------

    @Transactional
    public PlaceSeckillResult place(Long userId, PlaceSeckillRequest request) {
        // 0) 幂等快路径：同一用户同一幂等键 → 返回首次订单（与普通下单同构）
        OrderEntity existing = findByIdempotentKey(userId, request.idempotentKey());
        if (existing != null && seckillOrderMapper.selectCount(
                new LambdaQueryWrapper<SeckillOrderEntity>().eq(SeckillOrderEntity::getOrderNo, existing.getOrderNo())) > 0) {
            return new PlaceSeckillResult(existing.getOrderNo(), existing.getPayAmount(),
                    existing.getPayAmount(), true);
        }

        // 1) 活动与 SKU 校验：存在、开关开启、正在进行
        SeckillActivityEntity activity = requireEnabledActivity(request.activityId());
        LocalDateTime now = nowUtc();
        if (now.isBefore(activity.getStartAt()) || now.isAfter(activity.getEndAt())) {
            throw BizException.businessRule("活动不在进行时间内，开抢时间：" + activity.getStartAt());
        }
        SeckillSkuEntity seckillSku = seckillSkuMapper.selectOne(new LambdaQueryWrapper<SeckillSkuEntity>()
                .eq(SeckillSkuEntity::getActivityId, request.activityId())
                .eq(SeckillSkuEntity::getSkuId, request.skuId()));
        if (seckillSku == null) {
            throw BizException.notFound("该活动中的秒杀商品");
        }

        // 2) Redis 预扣（软依赖）：挡掉超限与明显抢完的流量
        boolean preDeducted = preDeductQuietly(seckillSku, userId);

        // 3) 商品快照 + 收货地址（校验类前置：任何一步失败都还没碰配额）
        SkuSnapshot snap = productGateway.loadSnapshots(List.of(request.skuId())).get(request.skuId());
        if (snap == null || !snap.sellable()) {
            rollbackQuietly(preDeducted, seckillSku.getId(), userId);
            throw new BizException(ErrorCode.BUSINESS_RULE, "秒杀商品已下架或已售罄");
        }
        ReceiverSnapshot receiver = userAddressGateway.load(userId, request.addressId());
        if (receiver == null) {
            rollbackQuietly(preDeducted, seckillSku.getId(), userId);
            throw BizException.businessRule("收货地址不存在或不是你的地址");
        }

        // 4) DB 原子扣减配额（防超卖的兜底防线）
        if (seckillSkuMapper.deductStock(seckillSku.getId()) == 0) {
            rollbackQuietly(preDeducted, seckillSku.getId(), userId);
            throw new BizException(ErrorCode.STOCK_INSUFFICIENT, "手慢了，已经被抢完啦");
        }

        // 5) 建单（秒杀价快照；不走购物车、不冻结商品库存）
        try {
            String orderNo = orderNoGenerator.next();
            LocalDateTime expireAt = now.plusMinutes(orderExpireMinutes);

            OrderEntity order = new OrderEntity();
            order.setId(idGenerator.nextId());
            order.setOrderNo(orderNo);
            order.setUserId(userId);
            order.setSellerId(snap.sellerId() == null ? 0L : snap.sellerId());
            order.setOrderType(ORDER_TYPE_SECKILL);
            order.setStatus(com.duoduo.trade.support.OrderStatus.PENDING_PAYMENT.name());
            order.setTotalAmount(seckillSku.getSeckillPrice());
            order.setFreightAmount(0L);
            order.setDiscountAmount(0L);
            order.setPayAmount(seckillSku.getSeckillPrice());
            order.setReceiverName(receiver.receiverName());
            order.setReceiverPhone(receiver.receiverPhone());
            order.setReceiverAddress(receiver.fullAddress());
            order.setRemark("限时秒杀");
            order.setIdempotentKey(request.idempotentKey());
            order.setExpireAt(expireAt);
            order.setCreatedAt(now);
            order.setUpdatedAt(now);
            orderMapper.insert(order);

            OrderItemEntity item = new OrderItemEntity();
            item.setId(idGenerator.nextId());
            item.setOrderId(order.getId());
            item.setSpuId(snap.spuId());
            item.setSkuId(snap.skuId());
            item.setTitle(snap.spuTitle());
            item.setSpecText(snap.specText());
            item.setMainImage(snap.image());
            item.setPrice(seckillSku.getSeckillPrice());
            item.setQuantity(1);
            item.setAmount(seckillSku.getSeckillPrice());
            item.setRefundStatus("NONE");
            item.setCreatedAt(now);
            orderItemMapper.insert(item);

            SeckillOrderEntity seckillOrder = new SeckillOrderEntity();
            seckillOrder.setId(idGenerator.nextId());
            seckillOrder.setUserId(userId);
            seckillOrder.setActivityId(activity.getId());
            seckillOrder.setSeckillSkuId(seckillSku.getId());
            seckillOrder.setSkuId(request.skuId());
            seckillOrder.setOrderNo(orderNo);
            seckillOrder.setCreatedAt(now);
            seckillOrderMapper.insert(seckillOrder);

            OrderStatusLogEntity statusLog = new OrderStatusLogEntity();
            statusLog.setId(idGenerator.nextId());
            statusLog.setOrderId(order.getId());
            statusLog.setFromStatus(null);
            statusLog.setToStatus(com.duoduo.trade.support.OrderStatus.PENDING_PAYMENT.name());
            statusLog.setOperatorType("USER");
            statusLog.setOperatorId(userId);
            statusLog.setReason("秒杀下单（活动 " + activity.getTitle() + "）");
            statusLog.setCreatedAt(now);
            statusLogMapper.insert(statusLog);

            log.info("秒杀下单成功：user={} activity={} sku={} orderNo={} 预扣={}（true=Redis 挡量生效）",
                    userId, activity.getId(), request.skuId(), orderNo, preDeducted);
            return new PlaceSeckillResult(orderNo, seckillSku.getSeckillPrice(), seckillSku.getSeckillPrice(), false);
        } catch (RuntimeException e) {
            // 事务回滚后 DB 配额不受影响（同事务），但 Redis 预扣不归事务管，必须手动吐回
            rollbackQuietly(preDeducted, seckillSku.getId(), userId);
            throw e;
        }
    }

    // ---------------- 运营端 ----------------

    @Transactional
    public ActivityView createActivity(CreateActivityRequest request) {
        if (!request.endAt().isAfter(request.startAt())) {
            throw BizException.validation("活动结束时间必须晚于开始时间");
        }
        List<Long> skuIds = request.skus().stream().map(CreateActivityRequest.CreateSkuItem::skuId).toList();
        Map<Long, SkuSnapshot> snapshots = productGateway.loadSnapshots(skuIds);
        for (Long skuId : skuIds) {
            if (snapshots.get(skuId) == null) {
                throw BizException.notFound("商品规格（SKU " + skuId + "）");
            }
        }

        LocalDateTime now = nowUtc();
        SeckillActivityEntity activity = new SeckillActivityEntity();
        activity.setId(idGenerator.nextId());
        activity.setTitle(request.title().trim());
        activity.setStartAt(request.startAt());
        activity.setEndAt(request.endAt());
        activity.setStatus(SeckillActivityEntity.STATUS_ENABLED);
        activity.setCreatedAt(now);
        activity.setUpdatedAt(now);
        activityMapper.insert(activity);

        List<SeckillSkuEntity> created = new ArrayList<>(request.skus().size());
        for (CreateActivityRequest.CreateSkuItem s : request.skus()) {
            SeckillSkuEntity entity = new SeckillSkuEntity();
            entity.setId(idGenerator.nextId());
            entity.setActivityId(activity.getId());
            entity.setSkuId(s.skuId());
            entity.setSeckillPrice(s.seckillPrice());
            // 原价缺省取商品服务当前售价
            entity.setOriginalPrice(s.originalPrice() == null ? snapshots.get(s.skuId()).price() : s.originalPrice());
            entity.setTotalStock(s.totalStock());
            entity.setAvailableStock(s.totalStock());
            entity.setPerLimit(s.perLimit() == null ? 1 : s.perLimit());
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            seckillSkuMapper.insert(entity);
            created.add(entity);
        }

        // 余量装载进 Redis（软依赖，失败不回滚活动 —— 下次访问时懒装载）
        for (SeckillSkuEntity entity : created) {
            ensureRedisStock(entity.getId(), entity.getAvailableStock());
        }
        log.info("创建秒杀活动：id={} title={} skus={}", activity.getId(), activity.getTitle(), skuIds);
        return toView(activity, created);
    }

    @Transactional
    public ActivityView toggle(Long activityId, boolean enabled) {
        SeckillActivityEntity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw BizException.notFound("秒杀活动");
        }
        activity.setStatus(enabled ? SeckillActivityEntity.STATUS_ENABLED : SeckillActivityEntity.STATUS_DISABLED);
        activity.setUpdatedAt(nowUtc());
        activityMapper.updateById(activity);
        List<SeckillSkuEntity> skus = seckillSkuMapper.selectList(
                new LambdaQueryWrapper<SeckillSkuEntity>().eq(SeckillSkuEntity::getActivityId, activityId));
        return toView(activity, skus);
    }

    /**
     * 编辑活动（R74）：**只改标题与时间**。
     *
     * <p>为什么不改 SKU / 价格 / 配额：那些值背后是「已经扣掉的余量」与「已成交订单里的价格快照」，
     * 改了会让活动配置与既成事实对不上，且没有任何办法回溯修正已下单的人。
     * 要换商品就新建活动，把旧的**下架**（而不是改）。
     *
     * <p>时间可以改**任何方向**（含把已结束的活动延后、把进行中的活动提前收尾）——
     * 这是运营的正常诉求；但若活动已有成交，把开始时间推到未来会让「已卖出的活动」显示成「未开始」，
     * 语义上自相矛盾，故该情形显式拒绝。
     */
    @Transactional
    public ActivityView editActivity(Long activityId, EditActivityRequest request) {
        SeckillActivityEntity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw BizException.notFound("秒杀活动");
        }
        if (!request.endAt().isAfter(request.startAt())) {
            throw BizException.validation("活动结束时间必须晚于开始时间");
        }
        long orders = countOrders(activityId);
        if (orders > 0 && request.startAt().isAfter(nowUtc())) {
            throw BizException.businessRule("该活动已产生 " + orders + " 笔秒杀订单，开始时间不能改到未来"
                    + "（否则已成交的活动会显示成「未开始」）");
        }
        activity.setTitle(request.title().trim());
        activity.setStartAt(request.startAt());
        activity.setEndAt(request.endAt());
        activity.setUpdatedAt(nowUtc());
        activityMapper.updateById(activity);

        List<SeckillSkuEntity> skus = seckillSkuMapper.selectList(
                new LambdaQueryWrapper<SeckillSkuEntity>().eq(SeckillSkuEntity::getActivityId, activityId));
        log.info("编辑秒杀活动：id={} title={} 时间 {} ~ {}", activityId, activity.getTitle(),
                activity.getStartAt(), activity.getEndAt());
        return toView(activity, skus);
    }

    /**
     * 删除活动（R74）：**只要还有「未关闭」的秒杀订单就拒绝**（改提示去下架）。
     *
     * <p><b>判据是「有没有未关闭的订单」，不是「有没有订单」</b>：
     * 「待付款 / 已付款 / 已发货 / 已完成 / 已退款」都还挂着资金或履约事实，必须留档；
     * 而「已关闭（CLOSED）」= 从未付过一分钱的终态单。若一场活动的订单**全部已关闭**，
     * 它就没有任何需要追溯的资金链路了，应当允许运营把它删掉 ——
     * 否则「自测 / 演示各建一场活动、下单再取消」会让活动**只增不减**，
     * 运营列表最终堆满永远清不掉的垃圾（这正是 R74 要解决的那个问题本身）。
     *
     * <p>删除时连带清理该活动的 `t_seckill_sku` 行、**已关闭订单的 `t_seckill_order` 链接行**
     * 与 Redis 余量键；**`t_order` 行一律保留**（订单类型/备注写着「限时秒杀」，溯源信息不丢）。
     * 已关闭订单不会再触发配额回补（回补只发生在「迁往 CLOSED 的那一刻」），所以删链接行是安全的。
     */
    @Transactional
    public void deleteActivity(Long activityId) {
        SeckillActivityEntity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw BizException.notFound("秒杀活动");
        }
        long live = countLiveOrders(activityId);
        if (live > 0) {
            throw BizException.businessRule("该活动还有 " + live + " 笔订单未关闭（待付款 / 已付款 / 已发货 / 已完成），"
                    + "不能删除（订单要留档）；如果只是不想再卖，请改为「下架」");
        }
        List<SeckillOrderEntity> links = seckillOrderMapper.selectList(
                new LambdaQueryWrapper<SeckillOrderEntity>().eq(SeckillOrderEntity::getActivityId, activityId));
        if (!links.isEmpty()) {
            seckillOrderMapper.delete(new LambdaQueryWrapper<SeckillOrderEntity>()
                    .eq(SeckillOrderEntity::getActivityId, activityId));
            log.info("删除活动 {} 时连带清理 {} 笔已关闭订单的秒杀链接行（t_order 留档）", activityId, links.size());
        }
        List<SeckillSkuEntity> skus = seckillSkuMapper.selectList(
                new LambdaQueryWrapper<SeckillSkuEntity>().eq(SeckillSkuEntity::getActivityId, activityId));
        for (SeckillSkuEntity sku : skus) {
            seckillSkuMapper.deleteById(sku.getId());
            clearRedisStock(sku.getId());
        }
        activityMapper.deleteById(activityId);
        log.info("删除秒杀活动：id={} title={} 连带清理秒杀 SKU {} 行", activityId, activity.getTitle(), skus.size());
    }

    /** 该活动下的秒杀订单数（编辑侧的守卫：有成交就不许把开始时间推到未来） */
    private long countOrders(Long activityId) {
        return seckillOrderMapper.selectCount(
                new LambdaQueryWrapper<SeckillOrderEntity>().eq(SeckillOrderEntity::getActivityId, activityId));
    }

    /** 该活动下**尚未关闭**的订单数（必须到 t_order 取状态：链接表只存 order_no，不存状态） */
    private long countLiveOrders(Long activityId) {
        List<SeckillOrderEntity> links = seckillOrderMapper.selectList(
                new LambdaQueryWrapper<SeckillOrderEntity>().eq(SeckillOrderEntity::getActivityId, activityId));
        if (links.isEmpty()) {
            return 0;
        }
        List<String> orderNos = links.stream().map(SeckillOrderEntity::getOrderNo).toList();
        return orderMapper.selectCount(new LambdaQueryWrapper<OrderEntity>()
                .in(OrderEntity::getOrderNo, orderNos)
                .ne(OrderEntity::getStatus, OrderStatus.CLOSED.name()));
    }

    // ---------------- 关单回补（OrderService 超时关单 / 取消 / 强关时回调） ----------------
    /**
     * 订单关闭后的配额回补：DB 无条件 +1，Redis 尽力 +1。
     * 供 {@code OrderService} 的超时关单、用户取消、运营强关三条路径调用；
     * 幂等性由调用方保证（只有一次「从待付款 → 关闭」的状态迁移）。
     */
    public void onOrderClosed(OrderEntity order) {
        if (!ORDER_TYPE_SECKILL.equals(order.getOrderType())) {
            return;
        }
        SeckillOrderEntity seckillOrder = seckillOrderMapper.selectOne(
                new LambdaQueryWrapper<SeckillOrderEntity>().eq(SeckillOrderEntity::getOrderNo, order.getOrderNo()));
        if (seckillOrder == null) {
            log.warn("秒杀订单 {} 找不到配额台账，无法回补，请人工核对 t_seckill_order", order.getOrderNo());
            return;
        }
        seckillSkuMapper.restock(seckillOrder.getSeckillSkuId());
        try {
            redis.execute(restockScript, List.of(KEY_STOCK + seckillOrder.getSeckillSkuId()));
        } catch (Exception e) {
            log.warn("秒杀配额 Redis 回补失败（DB 已回补，余量键会在下次访问时重新装载）：{}", e.getMessage());
        }
        log.info("秒杀配额已回补：orderNo={} seckillSkuId={}", order.getOrderNo(), seckillOrder.getSeckillSkuId());
    }

    // ---------------- Redis 软依赖辅助 ----------------

    /**
     * 预扣一次。返回是否扣上了（false = Redis 不可用降级，或被限购 / 抢完拒绝）。
     * 限购与抢完在这里直接拒（省掉后续跨服务调用），Redis 不可用则放行到 DB 兜底。
     */
    private boolean preDeductQuietly(SeckillSkuEntity seckillSku, Long userId) {
        try {
            String stockKey = KEY_STOCK + seckillSku.getId();
            String boughtKey = KEY_BOUGHT + seckillSku.getId() + ":" + userId;
            for (int attempt = 0; attempt < 2; attempt++) {
                Long r = redis.execute(preDeductScript, List.of(stockKey, boughtKey),
                        String.valueOf(seckillSku.getPerLimit()));
                if (r != null && r == -1L) {
                    // 余量键不存在（首次访问 / 被 LRU 逐出）：按 DB 装载后重试一次
                    ensureRedisStock(seckillSku.getId(), seckillSku.getAvailableStock());
                    continue;
                }
                if (r != null && r == -2L) {
                    throw BizException.businessRule("已达到该商品的限购件数（" + seckillSku.getPerLimit() + " 件）");
                }
                if (r != null && r == -3L) {
                    throw new BizException(ErrorCode.STOCK_INSUFFICIENT, "手慢了，已经被抢完啦");
                }
                return r != null && r == 1L;
            }
            return false;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("秒杀 Redis 预扣不可用，降级为纯 DB 路径：{}", e.getMessage());
            return false;
        }
    }

    /** DB 扣减失败 / 建单失败时，把 Redis 预扣吐回去（尽力而为）。 */
    private void rollbackQuietly(boolean preDeducted, Long seckillSkuId, Long userId) {
        if (!preDeducted) {
            return;
        }
        try {
            redis.execute(rollbackScript,
                    List.of(KEY_STOCK + seckillSkuId, KEY_BOUGHT + seckillSkuId + ":" + userId));
        } catch (Exception e) {
            log.warn("秒杀 Redis 预扣回滚失败（DB 配额未扣，余量键会按 DB 重新装载）：{}", e.getMessage());
        }
    }

    /** 删除活动时清掉该秒杀 SKU 的余量键（尽力而为；键不存在也无所谓）。 */
    private void clearRedisStock(Long seckillSkuId) {
        try {
            redis.delete(KEY_STOCK + seckillSkuId);
        } catch (Exception e) {
            log.warn("秒杀余量键清理失败（活动与 SKU 行已删除，残留键只占内存）：{}", e.getMessage());
        }
    }

    /** 把 DB 余量装载进 Redis（覆盖写；竞态无害 —— DB 才是事实，计数偏差只影响限流精度）。 */    private void ensureRedisStock(Long seckillSkuId, Integer availableStock) {
        try {
            redis.opsForValue().set(KEY_STOCK + seckillSkuId, String.valueOf(availableStock),
                    1, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("秒杀余量装载 Redis 失败（将走纯 DB 路径）：{}", e.getMessage());
        }
    }

    // ---------------- 内部工具 ----------------

    private SeckillActivityEntity requireEnabledActivity(Long activityId) {
        SeckillActivityEntity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw BizException.notFound("秒杀活动");
        }
        if (!SeckillActivityEntity.STATUS_ENABLED.equals(activity.getStatus())) {
            throw BizException.businessRule("秒杀活动已下架");
        }
        return activity;
    }

    private OrderEntity findByIdempotentKey(Long userId, String idempotentKey) {
        return orderMapper.selectOne(new LambdaQueryWrapper<OrderEntity>()
                .eq(OrderEntity::getUserId, userId)
                .eq(OrderEntity::getIdempotentKey, idempotentKey)
                .last("LIMIT 1"));
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
