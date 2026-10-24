package com.duoduo.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.exception.BizException;
import com.duoduo.trade.dto.AnalyticsDtos;
import com.duoduo.trade.dto.AnalyticsDtos.SellerAnalytics;
import com.duoduo.trade.dto.AnalyticsDtos.Slice;
import com.duoduo.trade.dto.AnalyticsDtos.TrendPoint;
import com.duoduo.trade.dto.OrderDtos.OrderView;
import com.duoduo.trade.dto.OrderDtos.RefundResult;
import com.duoduo.trade.dto.SellerDtos.SellerSummary;
import com.duoduo.trade.dto.SellerDtos.SettlementPage;
import com.duoduo.trade.dto.SellerDtos.ShipRequest;
import com.duoduo.trade.dto.SettlementDtos.SettlementView;
import com.duoduo.trade.entity.OrderEntity;
import com.duoduo.trade.entity.SettlementEntity;
import com.duoduo.trade.mapper.OrderMapper;
import com.duoduo.trade.mapper.SettlementMapper;
import com.duoduo.trade.support.OrderStatus;
import com.duoduo.trade.support.TrendSeries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 卖家中心：卖家维度的订单履约、售后与资金对账。
 *
 * <p>四个刻意的边界：
 * <ol>
 *   <li><b>准入与归属分开</b>：{@link #requireSellerScope()} 回答「你是不是卖家」，
 *       {@link #requireOwnOrder} 回答「这单是不是你的」。前者挡住普通买家进商家端，
 *       后者挡住卖家 A 操作卖家 B 的订单 —— 只做前者是最常见的越权漏洞来源，
 *       因为「进了门」和「能动这单」根本不是一回事。</li>
 *   <li><b>状态机不在这里重写</b>：发货与退款都转调 {@link OrderService} 的
 *       {@code shipInternal} / {@code refundInternal}，与运营侧内部接口共用同一条路径。
 *       准入方式可以有多种，但「发货后资金不动、仍留在托管中」这条规则只能有一处实现。</li>
 *   <li><b>平台自营的可见范围</b>：运营 / 管理员额外可见 {@code seller_id = 0}。
 *       商品域 SPU 目前没有商家字段，全部自营销量挂在平台账号下，
 *       若不对运营开放，平台自己的订单就没有任何人能履约。
 *       第三方商家接入（SPU 带 seller_id）后应把这个口子收窄为纯代运营用途。</li>
 *   <li><b>不做商品管理</b>：{@code t_spu} 没有 {@code seller_id}，
 *       商品归属属于商品域的改造，强行在这里做只会把错的归属关系写进库里。</li>
 * </ol>
 */
@Service
public class SellerService {

    private static final Logger log = LoggerFactory.getLogger(SellerService.class);

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> SETTLEMENT_STATUSES = Set.of(
            SettlementEntity.STATUS_CUSTODY, SettlementEntity.STATUS_RELEASED, SettlementEntity.STATUS_REFUNDED);

    private final OrderMapper orderMapper;
    private final SettlementMapper settlementMapper;
    private final OrderService orderService;
    private final SettlementService settlementService;
    private final com.duoduo.trade.client.MembershipClient membershipClient;

    public SellerService(OrderMapper orderMapper, SettlementMapper settlementMapper,
                         OrderService orderService, SettlementService settlementService,
                         com.duoduo.trade.client.MembershipClient membershipClient) {
        this.orderMapper = orderMapper;
        this.settlementMapper = settlementMapper;
        this.orderService = orderService;
        this.settlementService = settlementService;
        this.membershipClient = membershipClient;
    }

    /**
     * 当前登录者的卖家身份与可见范围。
     *
     * @param sellerId      卖家自己的用户 ID
     * @param platformScope 是否额外包含平台自营（{@code seller_id = 0}）
     * @param visibleSellerIds 归属校验用的卖家 ID 集合，至少含 {@code sellerId}
     */
    public record SellerScope(Long sellerId, String role, boolean platformScope,
                              List<Long> visibleSellerIds, boolean staffMember) {
    }

    // ---------------- 准入 ----------------

    /**
     * 校验当前登录者可以进入商家端，并给出可见范围。
     *
     * <p>角色从网关透传的 {@code X-User-Role} 来，不由客户端声明 ——
     * 否则「我是卖家」就变成了请求里的一句话。
     */
    public SellerScope requireSellerScope() {
        LoginUser user = UserContext.require();
        if (!user.canAccessSellerConsole()) {
            // E13：角色快照不是卖家 → 再查一次成员身份（实时查，停用即时生效）。
            // 店员（现役 STAFF）以**店铺主体**（店主 userId）行动 —— 订单挂在店主 userId 下，
            // 店员的可见集合就是它。查不到（无店 / 已停用）→ 401 语义的 403。
            com.duoduo.trade.client.dto.MembershipInfo membership = membershipOf(user.userId());
            if (membership != null && membership.activeStaff()) {
                Long ownerUserId = Long.valueOf(membership.ownerUserId());
                return new SellerScope(ownerUserId, "STAFF", false, List.of(ownerUserId), true);
            }
            throw BizException.forbidden("该账号不是卖家，无法进入商家端；请先在个人中心完成实名并申请成为卖家");
        }
        List<Long> visible = new ArrayList<>(2);
        visible.add(user.userId());
        boolean platformScope = user.isOperator();
        if (platformScope) {
            visible.add(OrderService.PLATFORM_SELLER_ID);
        }
        return new SellerScope(user.userId(), user.role(), platformScope, visible, false);
    }

    /**
     * E13 · 查成员身份。Feign 调用失败按"非店员"处理 ——
     * 认证链路的下游故障不能把「拒绝」变成「放行」。
     */
    private com.duoduo.trade.client.dto.MembershipInfo membershipOf(Long userId) {
        try {
            return membershipClient.of(userId).data();
        } catch (Exception ex) {
            log.warn("成员身份查询失败，按非店员处理 userId={}", userId, ex);
            return null;
        }
    }

    // ---------------- 卖出订单 ----------------

    /** 卖出的订单列表：按卖家维度隔离，支持状态过滤与分页。 */
    /**
     * 我卖出的订单。
     *
     * @param keyword 订单号或商品标题（包含匹配）—— 商家最常按「哪件商品」找单，
     *                只给订单号输入框等于让他先记住 21 位数字
     */
    public PageResult<OrderView> listOrders(String status, String keyword, int page, int pageSize) {
        SellerScope scope = requireSellerScope();
        int pageNo = Math.max(1, page);
        int size = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        String normalized = normalizeOrderStatus(status);

        LambdaQueryWrapper<OrderEntity> wrapper = new LambdaQueryWrapper<OrderEntity>()
                .in(OrderEntity::getSellerId, scope.visibleSellerIds());
        if (normalized != null) {
            wrapper.eq(OrderEntity::getStatus, normalized);
        }
        applyOrderKeyword(wrapper, keyword);
        wrapper.orderByDesc(OrderEntity::getCreatedAt).orderByDesc(OrderEntity::getId);

        Page<OrderEntity> result = orderMapper.selectPage(new Page<>(pageNo, size), wrapper);
        return PageResult.of(orderService.toViews(result.getRecords()), result.getTotal(), pageNo, size);
    }

    /** 兼容旧调用（无关键字） */
    public PageResult<OrderView> listOrders(String status, int page, int pageSize) {
        return listOrders(status, null, page, pageSize);
    }

    /** 与买家端同口径：订单号 或 商品标题（参数化子查询，不拼串） */
    private void applyOrderKeyword(LambdaQueryWrapper<OrderEntity> wrapper, String keyword) {
        if (keyword == null || keyword.isBlank()) return;
        String kw = keyword.trim();
        wrapper.and(w -> w.like(OrderEntity::getOrderNo, kw)
                .or()
                .apply("id IN (SELECT order_id FROM t_order_item WHERE title LIKE {0})", "%" + kw + "%"));
    }

    /** 订单详情（卖家视角，含明细快照与资金托管状态） */
    public OrderView detail(String orderNo) {
        SellerScope scope = requireSellerScope();
        OrderEntity order = requireOwnOrder(scope, orderNo);
        return orderService.toViews(List.of(order)).get(0);
    }

    /**
     * 卖家发货。
     *
     * <p>资金<b>不会</b>因此到账：发货只说明货出去了，买家还没见到货 ——
     * 放款要等买家确认收货（或超时自动确认），见 {@link OrderService#confirmReceipt(String)}。
     */
    public OrderView ship(String orderNo, ShipRequest request) {
        SellerScope scope = requireSellerScope();
        OrderEntity order = requireOwnOrder(scope, orderNo);
        String reason = request == null ? "卖家发货" : request.describe();
        log.info("卖家 {} 对订单 {} 执行发货", scope.sellerId(), orderNo);
        // 物流信息同时落两处：订单字段（给买卖双方看）+ 状态日志 reason（给排查看）。
        // 只写日志的话，卖家事后在详情页看不到自己填的单号。
        String company = request == null ? null : request.logisticsCompany();
        String no = request == null ? null : request.logisticsNo();
        return orderService.shipInternal(order, reason, company, no);
    }

    /**
     * 卖家售后退款：已付款 / 已发货都可以，前提是卖家已确认收到退货。
     *
     * <p>与买家自助退款（仅未发货）的界限见 {@link OrderService#refundByBuyer}：
     * 已发货之后货在买家手上，系统无从判断退没退，
     * 所以这条路径的实质是「卖家确认收货后同意退款」，必须由卖家侧发起。
     */
    public RefundResult refund(String orderNo, String reason) {
        SellerScope scope = requireSellerScope();
        OrderEntity order = requireOwnOrder(scope, orderNo);
        log.info("卖家 {} 对订单 {} 执行售后退款，原因：{}", scope.sellerId(), orderNo, reason);
        return orderService.refundInternal(order, reason, "卖家售后退款（已确认退货）");
    }

    // ---------------- 资金对账 ----------------

    /**
     * 我的托管单 + 全量汇总。
     *
     * <p>汇总<b>不受分页与状态过滤影响</b>：分页只是「一次看不完」，
     * 而「托管中多少、已到账多少」是对账问题的答案。
     * 若让汇总跟着过滤条件走，用户勾一个「已放款」就会以为自己只赚了这么多。
     */
    /**
     * E22 · 我的钱包：待结算 / 冻结中 / 可提现 / 累计到账 / 欠款。
     *
     * <p>与 {@link #settlements} 的分工：那个回答"每一笔钱在哪"（明细台账），
     * 这个回答"我现在能取多少"（一个数）。两者口径同源，前端**不允许**从明细里自己加总 ——
     * 那样一旦冻结规则变化，页面就会给出错的数，而错的那个数恰好是用户最在意的。
     *
     * <p>店主的钱包就是"他自己的 userId 名下"的钱（租户键 = 店铺主体 userId，E20 口径）。
     */
    public com.duoduo.trade.dto.SettlementDtos.SellerWalletView wallet() {
        SellerScope scope = requireSellerScope();
        if (scope.staffMember()) {
            // 与 settlements 同一道门：钱是"店主的事"，店员是"干活的人"
            throw BizException.forbidden("店员账号不可查看结算与提现信息，请联系店主");
        }
        return settlementService.walletOf(UserContext.requireUserId());
    }

    /**
     * E22 · 我的结算账单（租户 × 周期）。
     *
     * <p>与 {@link #wallet()} 的关系：钱包回答"现在能取多少"，账单回答
     * "这个周期里钱是怎么动的"。账单的**快照段**直接复用钱包口径，
     * 所以同一时刻两个页面上的"可提现"必然是同一个数。
     *
     * <p>与 {@link #settlements} 一样挡掉店员：账单里含着抽佣总额，
     * 那既是店主的收入信息，也是平台的商业条款，不该由店员看到。
     */
    public com.duoduo.trade.dto.SettlementDtos.SettlementBill bill(String period) {
        SellerScope scope = requireSellerScope();
        if (scope.staffMember()) {
            throw BizException.forbidden("店员账号不可查看结算与提现信息，请联系店主");
        }
        return settlementService.billOf(UserContext.requireUserId(), period);
    }

    /**
     * 我的资金托管明细。
     *
     * @param keyword 订单号（包含匹配）。结算行本身不存订单号，只能先按订单号查出
     *                orderId 再过滤 —— 因此「关键字命不中」时**必须显式给出空结果**，
     *                不能把空集合交给 {@code in()}：MyBatis-Plus 会直接跳过这个条件，
     *                于是「搜一个不存在的单号」反而返回全部结算记录。
     *                <p>页面顶部的托管/已放款/已退款汇总**不受关键字影响**：
     *                那是「我的钱总共多少」，跟着搜索变会让人以为钱变少了。</p>
     */
    public SettlementPage settlements(String status, String keyword, int page, int pageSize) {
        SellerScope scope = requireSellerScope();
        if (scope.staffMember()) {
            // E13 · 店员不可见结算：托管资金是「钱」的视图，店员是「干活的人」。
            // 在准入层一刀切，而不是每个字段做脱敏 —— 部分可见的数字拼起来还是全景。
            throw BizException.forbidden("店员账号不可查看结算与提现信息，请联系店主");
        }
        int pageNo = Math.max(1, page);
        int size = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        String normalized = normalizeSettlementStatus(status);

        LambdaQueryWrapper<SettlementEntity> wrapper = new LambdaQueryWrapper<SettlementEntity>()
                .in(SettlementEntity::getSellerId, scope.visibleSellerIds());
        if (normalized != null) {
            wrapper.eq(SettlementEntity::getStatus, normalized);
        }
        if (keyword != null && !keyword.isBlank()) {
            List<Long> orderIds = orderMapper.selectList(new LambdaQueryWrapper<OrderEntity>()
                            .select(OrderEntity::getId)
                            .in(OrderEntity::getSellerId, scope.visibleSellerIds())
                            .like(OrderEntity::getOrderNo, keyword.trim()))
                    .stream().map(OrderEntity::getId).toList();
            if (orderIds.isEmpty()) {
                // 显式空结果（detail 见方法注释：空集合会让 in() 整体失效）。
                // 用「主键 = -1」而不是提前 return：汇总数字要继续按真实数据算，
                // 否则搜一个不存在的单号会让「可提现余额」显示成 0，看起来像钱没了。
                wrapper.eq(SettlementEntity::getId, -1L);
            } else {
                wrapper.in(SettlementEntity::getOrderId, orderIds);
            }
        }
        wrapper.orderByDesc(SettlementEntity::getCreatedAt).orderByDesc(SettlementEntity::getId);

        Page<SettlementEntity> result = settlementMapper.selectPage(new Page<>(pageNo, size), wrapper);
        List<SettlementEntity> rows = result.getRecords();
        Map<Long, String> orderNoById = orderNoOf(rows.stream().map(SettlementEntity::getOrderId).toList());
        List<SettlementView> items = rows.stream()
                .map(row -> settlementService.toView(row, orderNoById.get(row.getOrderId())))
                .toList();

        long[] custody = new long[2];
        long[] released = new long[2];
        long refundedAmount = 0L;
        for (Map<String, Object> row : settlementMapper.aggregateBySellers(scope.visibleSellerIds())) {
            String rowStatus = str(row, "status");
            long amount = lng(row, "amount_sum");
            long fee = lng(row, "fee_sum");
            if (SettlementEntity.STATUS_CUSTODY.equals(rowStatus)) {
                custody[0] += amount;
                custody[1] += fee;
            } else if (SettlementEntity.STATUS_RELEASED.equals(rowStatus)) {
                released[0] += amount;
                released[1] += fee;
            } else if (SettlementEntity.STATUS_REFUNDED.equals(rowStatus)) {
                refundedAmount += amount;
            }
        }
        return new SettlementPage(items, result.getTotal(), pageNo, size,
                custody[0], custody[1], custody[0] - custody[1],
                released[0], released[1], released[0] - released[1],
                refundedAmount);
    }

    /** 兼容旧调用（无关键字） */
    public SettlementPage settlements(String status, int page, int pageSize) {
        return settlements(status, null, page, pageSize);
    }

    /** 商家端概览：订单状态分布 + 资金三栏。 */
    public SellerSummary summary() {
        SellerScope scope = requireSellerScope();

        long pendingPayment = 0L;
        long pendingShip = 0L;
        long shipped = 0L;
        long completed = 0L;
        long refunded = 0L;
        long closed = 0L;
        for (Map<String, Object> row : orderMapper.countBySellerAndStatus(scope.visibleSellerIds())) {
            long count = lng(row, "cnt");
            String status = str(row, "status");
            if (OrderStatus.PENDING_PAYMENT.name().equals(status)) {
                pendingPayment += count;
            } else if (OrderStatus.PAID.name().equals(status)) {
                pendingShip += count;
            } else if (OrderStatus.SHIPPED.name().equals(status)) {
                shipped += count;
            } else if (OrderStatus.COMPLETED.name().equals(status)) {
                completed += count;
            } else if (OrderStatus.REFUNDED.name().equals(status)) {
                refunded += count;
            } else if (OrderStatus.CLOSED.name().equals(status)) {
                closed += count;
            }
        }

        long custodyAmount = 0L;
        long custodySellerAmount = 0L;
        long releasedAmount = 0L;
        long releasedSellerAmount = 0L;
        long refundedAmount = 0L;
        for (Map<String, Object> row : settlementMapper.aggregateBySellers(scope.visibleSellerIds())) {
            String status = str(row, "status");
            long amount = lng(row, "amount_sum");
            long fee = lng(row, "fee_sum");
            if (SettlementEntity.STATUS_CUSTODY.equals(status)) {
                custodyAmount += amount;
                custodySellerAmount += amount - fee;
            } else if (SettlementEntity.STATUS_RELEASED.equals(status)) {
                releasedAmount += amount;
                releasedSellerAmount += amount - fee;
            } else if (SettlementEntity.STATUS_REFUNDED.equals(status)) {
                refundedAmount += amount;
            }
        }

        return new SellerSummary(
                scope.sellerId(),
                scope.role(),
                scope.platformScope(),
                pendingPayment, pendingShip, shipped, completed, refunded, closed,
                pendingPayment + pendingShip + shipped + completed + refunded + closed,
                custodyAmount, custodySellerAmount,
                releasedAmount, releasedSellerAmount,
                refundedAmount,
                isoTs(LocalDateTime.now(ZoneOffset.UTC)));
    }

    // ---------------- 多维度图表统计（第六十六轮） ----------------

    /**
     * 商家端多维度统计（图表用）。
     *
     * <p><b>为什么与 {@link #summary()} 分成两个接口</b>：那种是「几张数字卡」，
     * 这种是「几组序列」。合成一个接口后，任何一张图取数失败都会把统计卡一起拖垮 ——
     * 而页面上「待发货 144」比「趋势图」重要得多，不该被图表连坐。
     *
     * <p>五个维度都走 SQL 聚合、且都在 {@code visibleSellerIds} 范围内，
     * 与订单列表、归属校验共用同一个范围定义 —— 图表的可见范围必须与明细一致，
     * 否则会出现「图上 100 笔、点进去只有 3 笔」这种最难解释的一致性问题。
     */
    public SellerAnalytics analytics() {
        SellerScope scope = requireSellerScope();
        List<Long> visible = scope.visibleSellerIds();

        // 维度一：订单状态分布（复用概览那套聚合，保证两处口径完全一致）
        List<Slice> status = new ArrayList<>();
        Map<String, Long> statusCount = new HashMap<>();
        for (Map<String, Object> row : orderMapper.countBySellerAndStatus(visible)) {
            statusCount.merge(str(row, "status"), lng(row, "cnt"), Long::sum);
        }
        for (OrderStatus s : OrderStatus.values()) {
            status.add(new Slice(s.label(), statusCount.getOrDefault(s.name(), 0L)));
        }

        // 维度二：近 14 天趋势（补零口径见 TrendSeries：稀疏结果直接画折线会把「没单的日子」跳过）
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate fromDay = today.minusDays(AnalyticsDtos.TREND_DAYS - 1L);
        List<TrendPoint> trend = TrendSeries.fill(
                orderMapper.dailyTrend(visible, fromDay.atStartOfDay()), fromDay, AnalyticsDtos.TREND_DAYS);

        // 维度三：商品销量 Top N
        List<Slice> topProducts = orderMapper.topProductsBySales(visible, AnalyticsDtos.TOP_PRODUCT_LIMIT).stream()
                .map(row -> new Slice(str(row, "name"), lng(row, "qty")))
                .toList();

        // 维度四：资金构成（复用概览的资金聚合，同样保证口径一致）
        Map<String, Long> fund = new HashMap<>();
        for (Map<String, Object> row : settlementMapper.aggregateBySellers(visible)) {
            fund.merge(str(row, "status"), lng(row, "amount_sum"), Long::sum);
        }
        List<Slice> fundComposition = List.of(
                new Slice("托管中", fund.getOrDefault(SettlementEntity.STATUS_CUSTODY, 0L)),
                new Slice("已放款", fund.getOrDefault(SettlementEntity.STATUS_RELEASED, 0L)),
                new Slice("已退回", fund.getOrDefault(SettlementEntity.STATUS_REFUNDED, 0L)));

        // 维度五：订单类型占比
        Map<String, Long> typeMix = new HashMap<>();
        for (Map<String, Object> row : orderMapper.countByOrderType(visible)) {
            typeMix.merge(str(row, "t"), lng(row, "cnt"), Long::sum);
        }
        List<Slice> orderTypeMix = List.of(
                new Slice("普通下单", typeMix.getOrDefault("NORMAL", 0L)),
                new Slice("秒杀", typeMix.getOrDefault("SECKILL", 0L)));

        return new SellerAnalytics(status, trend, topProducts, fundComposition, orderTypeMix,
                isoTs(LocalDateTime.now(ZoneOffset.UTC)));
    }

    /**
     * 按商品维度的经营统计（2026-10-24，商品分析页用）。
     *
     * <p>最多返回 200 个商品：商品分析是"看盘子"而不是"翻台账"，
     * 卖 200 个 SPU 以上的商家需要的是按类目汇总，不是无限分页。
     */
    public List<AnalyticsDtos.ProductStat> productStats() {
        SellerScope scope = requireSellerScope();
        return orderMapper.productStatsBySeller(scope.visibleSellerIds(), 200).stream()
                .map(row -> new AnalyticsDtos.ProductStat(
                        lng(row, "spuId"),
                        str(row, "name"),
                        lng(row, "qty"),
                        lng(row, "amount"),
                        lng(row, "refundQty")))
                .toList();
    }

    // ---------------- 内部实现 ----------------

    /**
     * 归属校验：订单必须落在当前卖家的可见范围内。
     *
     * <p>「订单不存在」与「订单不是你的」<b>刻意返回不同状态码</b>（404 / 403）。
     * 统一返回 404 更安全（不泄露订单号是否存在），但会让卖家在输错单号与
     * 真的无权访问之间无法区分，排查成本转嫁给用户。这里选择可诊断性：
     * 订单号是雪花 ID，不可枚举，泄露存在性的实际风险很低。
     */
    private OrderEntity requireOwnOrder(SellerScope scope, String orderNo) {
        OrderEntity order = orderMapper.selectOne(new LambdaQueryWrapper<OrderEntity>()
                .eq(OrderEntity::getOrderNo, orderNo));
        if (order == null) {
            throw BizException.notFound("订单");
        }
        if (!scope.visibleSellerIds().contains(order.getSellerId())) {
            throw BizException.forbidden("该订单不属于当前卖家，无权操作");
        }
        return order;
    }

    /** 批量取订单号：托管表只存 order_id，视图需要订单号 */
    private Map<Long, String> orderNoOf(Collection<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Map.of();
        }
        List<OrderEntity> orders = orderMapper.selectList(new LambdaQueryWrapper<OrderEntity>()
                .in(OrderEntity::getId, orderIds));
        Map<Long, String> map = new HashMap<>(orders.size() * 2);
        for (OrderEntity order : orders) {
            map.put(order.getId(), order.getOrderNo());
        }
        return map;
    }

    private static String normalizeOrderStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String trimmed = status.trim();
        if (OrderStatus.of(trimmed) == null) {
            throw BizException.validation("不支持的订单状态：" + trimmed);
        }
        return trimmed;
    }

    private static String normalizeSettlementStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String trimmed = status.trim();
        if (!SETTLEMENT_STATUSES.contains(trimmed)) {
            throw BizException.validation("不支持的资金状态：" + trimmed
                    + "（可选 " + String.join(" / ", SETTLEMENT_STATUSES) + "）");
        }
        return trimmed;
    }

    /**
     * 从聚合结果里取字符串，容错列名大小写。
     * 不同 JDBC 驱动 / 配置下 {@code AS cnt} 可能回传 {@code CNT}，
     * 写死小写会让概览在某个环境突然全为零 —— 这类问题极难定位。
     */
    private static String str(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (value == null) {
            value = row.get(column.toUpperCase());
        }
        return value == null ? null : String.valueOf(value);
    }

    private static long lng(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (value == null) {
            value = row.get(column.toUpperCase());
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private static String isoTs(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC).toString();
    }
}
