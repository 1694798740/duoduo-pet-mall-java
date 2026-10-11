package com.duoduo.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duoduo.common.audit.AuditTrail;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.common.security.perm.Permission;
import com.duoduo.trade.dto.OrderDtos.OrderView;
import com.duoduo.trade.dto.OrderDtos.RefundResult;
import com.duoduo.trade.dto.ProxyDtos.ProxyAftersaleRequest;
import com.duoduo.trade.dto.ProxyDtos.ProxyRequestCreate;
import com.duoduo.trade.dto.ProxyDtos.ProxyShipRequest;
import com.duoduo.trade.dto.ProxyDtos.ProxyWorkOrderPage;
import com.duoduo.trade.dto.ProxyDtos.ProxyWorkOrderView;
import com.duoduo.trade.entity.OrderEntity;
import com.duoduo.trade.entity.ProxyWorkOrderEntity;
import com.duoduo.trade.mapper.OrderMapper;
import com.duoduo.trade.mapper.ProxyWorkOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * E23 · 代运营（交易域）：工单授权 + 平台代第三方店铺操作订单。
 *
 * <h2>这个类要回答的问题</h2>
 * 「平台替一家不属于自己的店发货、退钱」—— 这件事本身是必要的（商家客服电话打不通、
 * 商家不会用后台、纠纷需要平台介入），但它必须与「平台随手动了别人的店」可区分。
 * 区分的方式只有一种：**在动作之前存在一份商家自己给出的授权**。
 *
 * <h2>为什么要单独建权限点，而不复用 {@code ORDER_OPS}</h2>
 * {@code ORDER_OPS} 是「平台对自己管辖范围内的事务做处置」；本类动的是
 * **第三方店铺的经营资产**。合成一个点之后，「平台能干什么」与
 * 「平台能替谁干什么」就再也分不开了 —— 将来要收紧其中一个，只能把两个一起收紧。
 *
 * <h2>三条不变量（写错任何一条都等于没有代运营）</h2>
 * <ol>
 *   <li><b>无工单不动作</b>：工单缺失 / 已消费 / 已作废 → 一律拒绝。
 *       「先做了再补工单」不是代运营，是事后编造授权证据。</li>
 *   <li><b>工单的租户必须等于目标的店铺主体</b>（{@code t_order.seller_id}）。
 *       这是安全关键的一条：**拿 A 店的工单动 B 店**是本类漏洞最典型的形态，
 *       而且它很隐蔽 —— 工单是真的、动作是真的、租户也是真的，只是对不上。</li>
 *   <li><b>工单必须与动作、与目标同时匹配</b>：申请的是「协助发货」，
 *       就不能拿去退款；给 8817 号订单的授权，就不能用在 8818 号订单上。</li>
 * </ol>
 *
 * <h2>★ 消费与动作在同一个事务里 —— 这是把表放在本库的全部理由</h2>
 * {@link #consume} 把工单置 {@code CONSUMED}，紧接着调
 * {@link OrderService#shipInternal} / {@link OrderService#refundInternal} 执行动作。
 * 动作抛异常 → 整个事务回滚 → 工单回到 {@code OPEN}，授权**没有被浪费**。
 * 若工单在别的服务（跨库），这两步之间就有窗口：工单已烧、动作没生效
 * （或反过来，动作生效了工单还是可用的）—— 那不是能靠补偿糊过去的东西。
 *
 * <h2>平台自营为什么不走这条路</h2>
 * 自营订单（{@code seller_id = 0}）平台本来就能直接处置（卖家端 / 内部接口都通），
 * 根本不需要授权。若允许代运营路径处理自营订单，等于**凭空造出一份"商家授权"**——
 * 而自营根本没有第三方商家，这份授权是假的。假授权会把工单表污染成一份
 * 「看起来有商家申请过」的记录，让这个机制唯一的价值（可核查的授权）
 * 在真正需要的时候已经不可信。所以本类**显式拒绝自营订单**，并把提示指向正确路径。
 */
@Service
public class ProxyOpsService {

    private static final Logger log = LoggerFactory.getLogger(ProxyOpsService.class);

    /** 工单号 / 事由的长度上限，与 DDL 对齐 —— 超长会被数据库截断或报错，不如在这里明确拒绝 */
    private static final int MAX_NO_LEN = 48;
    private static final int MAX_REASON_LEN = 255;
    /** 平台待办列表一次最多返回多少条：这是「待处理队列」不是「全量归档」，不需要翻页 */
    private static final int PLATFORM_LIST_LIMIT = 100;

    private final ProxyWorkOrderMapper workOrderMapper;
    private final OrderMapper orderMapper;
    private final OrderService orderService;
    private final SellerService sellerService;
    private final SnowflakeIdGenerator idGenerator;

    public ProxyOpsService(ProxyWorkOrderMapper workOrderMapper,
                           OrderMapper orderMapper,
                           OrderService orderService,
                           SellerService sellerService,
                           SnowflakeIdGenerator idGenerator) {
        this.workOrderMapper = workOrderMapper;
        this.orderMapper = orderMapper;
        this.orderService = orderService;
        this.sellerService = sellerService;
        this.idGenerator = idGenerator;
    }

    // ==================== 商家端：申请 / 查看 / 撤销 ====================

    /**
     * 店主（或店员，以其所属店铺主体身份）登记一条代运营申请。
     *
     * <p>{@code tenantId} 与 {@code requesterUserId} 全部来自登录态，
     * 请求体里给的一律不接受 —— 否则「商家申请」会变成「谁都能替别人申请」，
     * 授权凭证当场失去意义。
     */
    @Transactional
    public ProxyWorkOrderView request(ProxyRequestCreate req) {
        SellerService.SellerScope scope = sellerService.requireSellerScope();
        LoginUser me = UserContext.require();

        // ★ 平台账号不允许走这个入口：授权必须由被代理的一方给出。
        //   若平台既能授权又能执行，这套机制只是给越权操作加了一道填写框 ——
        //   审计记录会很完整，而它证明的事实并不存在。
        //   这里必须**显式拒绝**（403），不能让它带着自己的 userId 往下走、
        //   最后在订单归属那里得到一个 404：403 说的是「平台不能替商家授权」这条规则，
        //   404 会被读成「订单不存在」，让人去查数据而不是查流程。
        if (scope.platformScope()) {
            throw BizException.forbidden(
                    "代运营申请必须由店铺（店主或店员）发起；平台账号不能替商家作出授权");
        }

        String targetType = normalize(req == null ? null : req.targetType(), "targetType");
        String actionType = normalize(req == null ? null : req.actionType(), "actionType");
        String targetRef = requireText(req == null ? null : req.targetRef(), "目标对象", MAX_NO_LEN);
        String reason = requireText(req == null ? null : req.reason(), "申请事由", MAX_REASON_LEN);

        // 商品类申请走商品域同名入口。这里显式拒绝而不是「顺便也支持一下」：
        // 交易库里存的目标是订单号，商品库里的目标是 spuId，混在一张表里
        // 会让「target_ref 到底指什么」变成需要看 target_type 才知道的事，而
        // 一旦某处忘了看，就会出现「拿 spuId 去查订单」这种查不到又不报错的路径。
        if (!ProxyWorkOrderEntity.TARGET_ORDER.equals(targetType)) {
            throw BizException.validation(
                    "代运营申请（交易域）只支持 targetType=ORDER；商品类申请请走商家端商品接口下的同名入口");
        }
        if (!ProxyWorkOrderEntity.ACTION_SHIP.equals(actionType)
                && !ProxyWorkOrderEntity.ACTION_AFTERSALE.equals(actionType)) {
            throw BizException.validation("交易域代运营只支持 actionType=SHIP（代发货）或 AFTERSALE（代处理售后）");
        }

        // 订单必须属于申请人所在的店铺。用登录态的 sellerId 做归属判断 ——
        // 与此同时租户拦截器已经在 SQL 层追加了条件，他人的订单在这里根本查不出来，
        // 于是「不存在」与「不是你的」统一返回 404（与 E20 的防枚举口径一致）。
        OrderEntity order = findOrderByNo(targetRef);
        if (order == null || !Objects.equals(order.getSellerId(), scope.sellerId())) {
            throw BizException.notFound("订单");
        }
        requireNotSelfOperated(order, "代运营");

        LocalDateTime now = nowUtc();
        ProxyWorkOrderEntity wo = new ProxyWorkOrderEntity();
        wo.setId(idGenerator.nextId());
        wo.setWorkOrderNo(newWorkOrderNo(now));
        wo.setTenantId(scope.sellerId());
        wo.setTargetType(ProxyWorkOrderEntity.TARGET_ORDER);
        wo.setTargetRef(order.getOrderNo());
        wo.setActionType(actionType);
        wo.setReason(reason);
        wo.setRequesterUserId(me.userId());
        wo.setStatus(ProxyWorkOrderEntity.STATUS_OPEN);
        wo.setCreatedAt(now);
        wo.setUpdatedAt(now);
        workOrderMapper.insert(wo);
        log.info("代运营工单已登记：{} 租户 {} 目标 {}/{} 动作 {} 申请人 {}",
                wo.getWorkOrderNo(), wo.getTenantId(), wo.getTargetType(), wo.getTargetRef(),
                wo.getActionType(), wo.getRequesterUserId());
        return toView(wo);
    }

    /** 我（本店铺）的工单列表。按登录态过滤，不接受请求参数指定租户。 */
    public ProxyWorkOrderPage listMine(String status) {
        SellerService.SellerScope scope = sellerService.requireSellerScope();
        List<ProxyWorkOrderEntity> all = workOrderMapper.recentOfTenant(scope.sellerId(), PLATFORM_LIST_LIMIT);
        List<ProxyWorkOrderEntity> items = new ArrayList<>();
        for (ProxyWorkOrderEntity e : all) {
            if (status == null || status.isBlank() || status.equalsIgnoreCase(e.getStatus())) {
                items.add(e);
            }
        }
        long openCount = all.stream()
                .filter(e -> ProxyWorkOrderEntity.STATUS_OPEN.equals(e.getStatus()))
                .count();
        return new ProxyWorkOrderPage(toViews(items), openCount);
    }

    /**
     * 撤销自己尚未被执行的申请。
     *
     * <p><b>没有这个入口，授权就是不可撤回的</b>：商家误点一次「申请平台协助发货」，
     * 那条工单会一直 {@code OPEN}，任何一天都可能被某个运营拿去执行。
     * 一封发出去就收不回来的授权信不是授权，是漏洞。
     */
    @Transactional
    public ProxyWorkOrderView voidMine(String workOrderNo) {
        SellerService.SellerScope scope = sellerService.requireSellerScope();
        ProxyWorkOrderEntity wo = requireByNo(workOrderNo);
        // 不是自己的工单 → 404（同上：不区分「不存在」与「不是你的」）
        if (!Objects.equals(wo.getTenantId(), scope.sellerId())) {
            throw BizException.notFound("代运营工单");
        }
        if (!ProxyWorkOrderEntity.STATUS_OPEN.equals(wo.getStatus())) {
            throw BizException.validation("工单当前为「" + ProxyWorkOrderEntity.labelOf(wo.getStatus())
                    + "」，不能再撤销");
        }
        LocalDateTime now = nowUtc();
        if (workOrderMapper.markVoid(wo.getId(), now) != 1) {
            // CAS 失败 = 并发下已被执行或被撤销。此时**不能**当成成功返回：
            // 商家以为撤掉了，而实际可能已经被平台执行 —— 那是两件后果完全不同的事。
            throw BizException.conflict("工单状态已变化（可能已被平台执行），请刷新后确认");
        }
        wo.setStatus(ProxyWorkOrderEntity.STATUS_VOID);
        wo.setUpdatedAt(now);
        return toView(wo);
    }

    // ==================== 平台端：待办 / 读取 ====================

    /**
     * 平台查看工单待办（只读）。
     *
     * <p>权限是「进后台」（{@code platform:console}）而不是 {@code tenant:proxy-ops}：
     * 只读地看到「哪些店申请了什么」不改变任何状态，而**能不能做**由三个执行端点各自把关。
     * 口径是「进门与动数分开」—— 进门宽、动数严。
     */
    public ProxyWorkOrderPage listForPlatform(String status, Long tenantId, String targetRef) {
        OperatorGuard.requireOperator();
        List<ProxyWorkOrderEntity> all;
        if (targetRef != null && !targetRef.isBlank()) {
            all = workOrderMapper.openOnTarget(ProxyWorkOrderEntity.TARGET_ORDER, targetRef.trim());
            if (status != null && !status.isBlank()) {
                all = all.stream().filter(e -> status.equalsIgnoreCase(e.getStatus())).toList();
            }
        } else if (tenantId != null) {
            all = workOrderMapper.recentOfTenant(tenantId, PLATFORM_LIST_LIMIT);
            if (status != null && !status.isBlank()) {
                all = all.stream().filter(e -> status.equalsIgnoreCase(e.getStatus())).toList();
            }
        } else {
            all = workOrderMapper.selectList(new LambdaQueryWrapper<ProxyWorkOrderEntity>()
                    .eq(ProxyWorkOrderEntity::getTargetType, ProxyWorkOrderEntity.TARGET_ORDER)
                    .orderByDesc(ProxyWorkOrderEntity::getCreatedAt)
                    .last("LIMIT " + PLATFORM_LIST_LIMIT));
            if (status != null && !status.isBlank()) {
                all = all.stream().filter(e -> status.equalsIgnoreCase(e.getStatus())).toList();
            }
        }
        long openCount = all.stream()
                .filter(e -> ProxyWorkOrderEntity.STATUS_OPEN.equals(e.getStatus()))
                .count();
        return new ProxyWorkOrderPage(toViews(all), openCount);
    }

    // ==================== 平台端：三个白名单动作（本域两个） ====================

    /**
     * 代发货：平台持有第三方的有效工单，替它把订单发出去。
     */
    @Transactional
    public OrderView proxyShip(String orderNo, ProxyShipRequest req) {
        LoginUser operator = OperatorGuard.requirePerm(Permission.TENANT_PROXY_OPS);
        String workOrderNo = requireText(req == null ? null : req.workOrderNo(), "工单号", MAX_NO_LEN);
        String reason = requireText(req == null ? null : req.reason(), "执行说明", MAX_REASON_LEN);

        OrderEntity order = requireOrder(orderNo);
        requireNotSelfOperated(order, "代发货");

        ProxyWorkOrderEntity wo = requireUsable(workOrderNo,
                ProxyWorkOrderEntity.ACTION_SHIP, order);
        consume(wo, operator);

        String before = order.getStatus();
        String company = trimToNull(req.logisticsCompany());
        String shipNo = trimToNull(req.logisticsNo());
        AuditTrail.target(order.getId(), "ORDER");
        AuditTrail.tenant(order.getSellerId());
        AuditTrail.change(before, "SHIPPED");
        AuditTrail.note("代运营代发货｜工单 " + wo.getWorkOrderNo()
                + "｜申请事由：" + wo.getReason() + "｜执行说明：" + reason);

        OrderView view = orderService.shipInternal(order,
                "平台代运营代发货（工单 " + wo.getWorkOrderNo() + "）：" + reason,
                company, shipNo);
        log.info("代运营代发货完成：订单 {} 租户 {} 工单 {} 执行人 {}",
                order.getOrderNo(), order.getSellerId(), wo.getWorkOrderNo(), operator.userId());
        return view;
    }

    /**
     * 代处理售后：平台持有第三方的有效工单，替它确认收到退货并退款给买家。
     *
     * <p>最小版只做「同意退款」这一个动作 —— 「驳回」需要一套买家申诉与举证流程，
     * 不在本项范围内。少一个动作不会有歧义；把「驳回」草草塞进来才会有
     * —— 它会被当成「售后被驳回」的正式出口，而实际上没有任何申诉路径。
     */
    @Transactional
    public RefundResult proxyAftersale(String orderNo, ProxyAftersaleRequest req) {
        LoginUser operator = OperatorGuard.requirePerm(Permission.TENANT_PROXY_OPS);
        String workOrderNo = requireText(req == null ? null : req.workOrderNo(), "工单号", MAX_NO_LEN);
        String reason = requireText(req == null ? null : req.reason(), "执行说明", MAX_REASON_LEN);

        OrderEntity order = requireOrder(orderNo);
        requireNotSelfOperated(order, "代处理售后");

        ProxyWorkOrderEntity wo = requireUsable(workOrderNo,
                ProxyWorkOrderEntity.ACTION_AFTERSALE, order);
        consume(wo, operator);

        String before = order.getStatus();
        AuditTrail.target(order.getId(), "ORDER");
        AuditTrail.tenant(order.getSellerId());
        AuditTrail.change(before, "REFUNDED");
        AuditTrail.note("代运营代处理售后（同意退款）｜工单 " + wo.getWorkOrderNo()
                + "｜申请事由：" + wo.getReason() + "｜执行说明：" + reason);

        RefundResult result = orderService.refundInternal(order,
                reason, "平台代运营代处理售后（工单 " + wo.getWorkOrderNo() + "）");
        log.info("代运营代处理售后完成：订单 {} 租户 {} 工单 {} 执行人 {}",
                order.getOrderNo(), order.getSellerId(), wo.getWorkOrderNo(), operator.userId());
        return result;
    }

    // ==================== 判据（本类的核心） ====================

    /**
     * 校验工单对「这次动作 + 这个目标」是否构成有效授权。
     *
     * <p>顺序是刻意的：先判「授权本身能不能用」（存在 / 状态），再判「授权对不对得上」
     * （动作 / 目标 / 租户）。这样运营看到 400 时知道是工单的问题，
     * 看到 403 时知道是「拿错店的单子」—— 两类事故的处理方式完全不同，
     * 用同一个状态码盖住会让排查从「看一眼」变成「翻代码」。
     */
    private ProxyWorkOrderEntity requireUsable(String workOrderNo, String actionType, OrderEntity order) {
        ProxyWorkOrderEntity wo = requireByNo(workOrderNo);

        if (!ProxyWorkOrderEntity.STATUS_OPEN.equals(wo.getStatus())) {
            throw BizException.validation("代运营工单「" + wo.getWorkOrderNo() + "」当前为「"
                    + ProxyWorkOrderEntity.labelOf(wo.getStatus()) + "」，不能用于执行动作"
                    + "（授权一次性，已执行的不可重复使用）");
        }
        if (!ProxyWorkOrderEntity.TARGET_ORDER.equals(wo.getTargetType())) {
            throw BizException.validation("工单 " + wo.getWorkOrderNo() + " 的目标类型是 "
                    + wo.getTargetType() + "，不能用于订单操作");
        }
        if (!Objects.equals(wo.getActionType(), actionType)) {
            throw BizException.validation("工单 " + wo.getWorkOrderNo() + " 申请的是「"
                    + ProxyWorkOrderEntity.actionLabelOf(wo.getActionType()) + "」，与本次动作「"
                    + ProxyWorkOrderEntity.actionLabelOf(actionType) + "」不一致");
        }
        if (!Objects.equals(wo.getTargetRef(), order.getOrderNo())) {
            throw BizException.validation("工单 " + wo.getWorkOrderNo() + " 针对的是订单 "
                    + wo.getTargetRef() + "，不能用于订单 " + order.getOrderNo());
        }
        // ★ 安全关键：工单的租户必须等于该订单的店铺主体。租户键是店铺主体的 userId
        //   （t_order.seller_id），不是 t_merchant.id —— 两处口径一旦分开，
        //   就会出现「校验通过、动作落在别人家」这种最坏的组合。
        if (!Objects.equals(wo.getTenantId(), order.getSellerId())) {
            throw BizException.forbidden("工单 " + wo.getWorkOrderNo() + " 属于租户 "
                    + wo.getTenantId() + "，无权用于订单 " + order.getOrderNo()
                    + "（店铺主体 " + order.getSellerId() + "）");
        }
        return wo;
    }

    /**
     * 消费工单（一次性）。
     *
     * <p>CAS 返回 0 行 = 并发下已被别人消费（或刚被商家撤销）。
     * 此时必须**报错终止**，绝不能"当作没事继续执行动作"——
     * 那正好就是「一个授权做两次操作」，而且是并发场景，事后从日志里看不出破绽。
     */
    private void consume(ProxyWorkOrderEntity wo, LoginUser operator) {
        LocalDateTime now = nowUtc();
        if (workOrderMapper.consume(wo.getId(), operator.userId(), now) != 1) {
            throw BizException.conflict("代运营工单「" + wo.getWorkOrderNo()
                    + "」已被执行或已被撤销，请刷新后重新确认授权");
        }
        wo.setStatus(ProxyWorkOrderEntity.STATUS_CONSUMED);
        wo.setConsumedAt(now);
        wo.setConsumedBy(operator.userId());
    }

    private ProxyWorkOrderEntity requireByNo(String workOrderNo) {
        ProxyWorkOrderEntity wo = workOrderMapper.selectByNo(workOrderNo);
        if (wo == null) {
            throw BizException.notFound("代运营工单");
        }
        return wo;
    }

    /**
     * 平台自营订单不走代运营。
     *
     * <p>理由见类注释：自营没有第三方商家，这份「授权」是假的。
     * 提示必须指向**可执行的下一步**（卖家端 / 内部接口），而不是一句「不允许」——
     * 运营看到「不允许」只会反复试。
     */
    private static void requireNotSelfOperated(OrderEntity order, String action) {
        if (order.getSellerId() != null && order.getSellerId() == 0L) {
            throw BizException.businessRule("订单 " + order.getOrderNo()
                    + " 属于平台自营，平台可直接处置，无需（也不应）通过代运营"
                    + action + "；代运营只用于第三方店铺的受控代操作");
        }
    }

    private OrderEntity requireOrder(String orderNo) {
        OrderEntity order = findOrderByNo(orderNo);
        if (order == null) {
            throw BizException.notFound("订单");
        }
        return order;
    }

    /**
     * 按订单号取订单。
     *
     * <p><b>租户过滤在这里是「不用管」而不是「关掉了」</b>：平台运营的令牌不声明
     * {@code tid}（见 B-21），所以 E20 的租户拦截器对它们不追加条件 —— 这正是
     * 「平台看得到全部租户」的既有口径，本类不需要也不应该绕过它。
     * 商家侧调用时 {@code tid} 存在，过滤自动生效，他人的订单查不出来。
     */
    private OrderEntity findOrderByNo(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            throw BizException.validation("订单号不能为空");
        }
        return orderMapper.selectOne(new LambdaQueryWrapper<OrderEntity>()
                .eq(OrderEntity::getOrderNo, orderNo.trim()));
    }

    // ==================== 杂项 ====================

    /** 工单号：领域前缀 + 时间 + 随机尾。前缀让「这单该去哪张表找」在人工排查时一眼可见 */
    private String newWorkOrderNo(LocalDateTime now) {
        String stamp = now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        long tail = idGenerator.nextId() % 100000L;
        return ProxyWorkOrderEntity.NO_PREFIX_ORDER + stamp + "-" + String.format("%05d", tail);
    }

    private ProxyWorkOrderView toView(ProxyWorkOrderEntity e) {
        return ProxyWorkOrderView.of(e, isoTs(e.getConsumedAt()), isoTs(e.getCreatedAt()));
    }

    private List<ProxyWorkOrderView> toViews(List<ProxyWorkOrderEntity> list) {
        List<ProxyWorkOrderView> out = new ArrayList<>(list.size());
        for (ProxyWorkOrderEntity e : list) {
            out.add(toView(e));
        }
        return out;
    }

    /**
     * 必填文本校验。
     *
     * <p><b>不接受只有空白的值</b>：一个全是空格的「事由」在页面上看着像填了，
     * 在库里等于没填 —— 而"有没有事由"正是这套机制要留的证据之一。
     */
    private static String requireText(String value, String label, int maxLen) {
        String v = value == null ? null : value.trim();
        if (v == null || v.isEmpty()) {
            throw BizException.validation(label + "不能为空");
        }
        if (v.length() > maxLen) {
            throw BizException.validation(label + "过长（最多 " + maxLen + " 个字符）");
        }
        return v;
    }

    private static String normalize(String value, String label) {
        String v = value == null ? null : value.trim();
        if (v == null || v.isEmpty()) {
            throw BizException.validation(label + "不能为空");
        }
        return v.toUpperCase(java.util.Locale.ROOT);
    }

    private static String trimToNull(String value) {
        String v = value == null ? null : value.trim();
        return (v == null || v.isEmpty()) ? null : v;
    }

    private static String isoTs(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC).toString();
    }

    private static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
