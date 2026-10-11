package com.duoduo.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duoduo.common.audit.AuditTrail;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.common.security.perm.Permission;
import com.duoduo.product.dto.ProductDtos.SellerProductView;
import com.duoduo.product.dto.ProxyDtos.ProxyOffShelfRequest;
import com.duoduo.product.dto.ProxyDtos.ProxyRequestCreate;
import com.duoduo.product.dto.ProxyDtos.ProxyWorkOrderPage;
import com.duoduo.product.dto.ProxyDtos.ProxyWorkOrderView;
import com.duoduo.product.entity.ProxyWorkOrderEntity;
import com.duoduo.product.entity.SpuEntity;
import com.duoduo.product.mapper.ProxyWorkOrderMapper;
import com.duoduo.product.mapper.SpuMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * E23 · 代运营（商品域）：工单授权 + 平台代第三方店铺下架商品。
 *
 * <h2>本类同时是「收窄既有口子」的那一刀</h2>
 * 在此之前，平台运营可以通过商家端 {@code POST /api/seller/products/{spuId}/status}
 * **直接下架任何第三方店铺的商品** —— 因为商品域的 {@code requireSellerScope()}
 * 给运营的可见范围是 {@code platformScope=true}，归属校验对平台一律放行。
 * 当时这没问题（商品还没有第三方归属），但 E27 引入第三方供货之后，
 * 「平台能改别人的商品」就从「平台管自己的货」变成了**越权**。
 *
 * <p>收窄后的口径：运营改商品状态只允许作用于**平台自营**（{@code seller_id = 0}）；
 * 要动第三方的商品，必须持该店主的有效工单走本类。
 * 与交易域订单侧是**同一套判据**（那边运营商默认就看不到第三方的订单，
 * 所以不需要额外收窄，只需要补上受控例外）。
 *
 * <h2>三条不变量（与交易域逐条对应）</h2>
 * <ol>
 *   <li><b>无工单不动作</b>：缺失 / 已消费 / 已作废 → 拒绝；</li>
 *   <li><b>工单租户必须等于商品的店铺主体</b>（{@code t_spu.seller_id}）。
 *       拿 A 店的工单动 B 店的商品是本类漏洞最典型的形态 —— 而且很隐蔽：
 *       工单是真的、动作是真的、租户也是真的，只是对不上；</li>
 *   <li><b>工单的 actions / target 必须同时匹配</b>：申请协助上架某件商品的授权，
 *       不能用来下架它，更不能用来动另一件。</li>
 * </ol>
 *
 * <h2>★ 消费与动作在同一个事务里</h2>
 * {@link #consume} 置 {@code CONSUMED} 之后紧接写商品状态。动作抛异常 → 整体回滚 →
 * 工单回到 {@code OPEN}，授权没被浪费。若工单在别的服务（跨库），这两步之间就有窗口，
 * 而那不是能靠补偿糊过去的东西。
 */
@Service
public class ProxyOpsService {

    private static final Logger log = LoggerFactory.getLogger(ProxyOpsService.class);

    private static final int MAX_NO_LEN = 48;
    private static final int MAX_REASON_LEN = 255;
    private static final int PLATFORM_LIST_LIMIT = 100;

    private final ProxyWorkOrderMapper workOrderMapper;
    private final SpuMapper spuMapper;
    private final SellerProductService sellerProductService;
    private final SnowflakeIdGenerator idGenerator;

    public ProxyOpsService(ProxyWorkOrderMapper workOrderMapper,
                           SpuMapper spuMapper,
                           SellerProductService sellerProductService,
                           SnowflakeIdGenerator idGenerator) {
        this.workOrderMapper = workOrderMapper;
        this.spuMapper = spuMapper;
        this.sellerProductService = sellerProductService;
        this.idGenerator = idGenerator;
    }

    // ==================== 商家端：申请 / 查看 / 撤销 ====================

    /**
     * 店主（或店员，以其所属店铺主体身份）登记一条商品类代运营申请。
     *
     * <p>平台账号**不允许**调这个入口：授权必须由被代理的一方给出。
     * 若平台既能授权又能执行，这套机制只是给越权操作加了一道填写框 ——
     * 审计记录会很完整，而它证明的事实并不存在。
     */
    @Transactional
    public ProxyWorkOrderView request(ProxyRequestCreate req) {
        Long ownerUserId = sellerProductService.requireStoreOwnerUserId();
        LoginUser me = UserContext.require();

        String targetType = normalize(req == null ? null : req.targetType(), "targetType");
        String actionType = normalize(req == null ? null : req.actionType(), "actionType");
        String targetRef = requireText(req == null ? null : req.targetRef(), "目标对象", MAX_NO_LEN);
        String reason = requireText(req == null ? null : req.reason(), "申请事由", MAX_REASON_LEN);

        // 订单类申请走交易域同名入口。显式拒绝而不是"顺便也支持一下"：
        // 两库的 target_ref 指代不同的东西（订单号 vs spuId），混在一张表里
        // 会让「target_ref 到底指什么」变成需要先看 target_type 才知道的事，
        // 一旦某处忘了看，就会出现「拿 spuId 去查订单」这种查不到又不报错的路径。
        if (!ProxyWorkOrderEntity.TARGET_PRODUCT.equals(targetType)) {
            throw BizException.validation(
                    "代运营申请（商品域）只支持 targetType=PRODUCT；订单类申请请走商家端订单接口下的同名入口");
        }
        if (!ProxyWorkOrderEntity.ACTION_OFF_SHELF.equals(actionType)) {
            throw BizException.validation("商品域代运营只支持 actionType=OFF_SHELF（代下架商品）");
        }

        Long spuId = parseSpuId(targetRef);
        SpuEntity spu = spuMapper.selectById(spuId);
        if (spu == null || !Objects.equals(nz(spu.getSellerId()), ownerUserId)) {
            // 「不存在」与「不是你的」统一 404：商品 id 不可枚举，但一旦区分就会
            // 把「这个 id 真实存在」这个事实泄露出去 —— 与订单侧防枚举口径一致。
            throw BizException.notFound("商品");
        }
        requireNotSelfSupplied(spu, "代运营");

        LocalDateTime now = nowUtc();
        ProxyWorkOrderEntity wo = new ProxyWorkOrderEntity();
        wo.setId(idGenerator.nextId());
        wo.setWorkOrderNo(newWorkOrderNo(now));
        wo.setTenantId(ownerUserId);
        wo.setTargetType(ProxyWorkOrderEntity.TARGET_PRODUCT);
        wo.setTargetRef(String.valueOf(spu.getId()));
        wo.setActionType(actionType);
        wo.setReason(reason);
        wo.setRequesterUserId(me.userId());
        wo.setStatus(ProxyWorkOrderEntity.STATUS_OPEN);
        wo.setCreatedAt(now);
        wo.setUpdatedAt(now);
        workOrderMapper.insert(wo);
        log.info("代运营工单（商品域）已登记：{} 租户 {} 目标 PRODUCT/{} 动作 {} 申请人 {}",
                wo.getWorkOrderNo(), wo.getTenantId(), wo.getTargetRef(),
                wo.getActionType(), wo.getRequesterUserId());
        return toView(wo);
    }

    /** 我（本店铺）的工单列表。按登录态过滤，不接受请求参数指定租户。 */
    public ProxyWorkOrderPage listMine(String status) {
        Long ownerUserId = sellerProductService.requireStoreOwnerUserId();
        List<ProxyWorkOrderEntity> all = workOrderMapper.recentOfTenant(ownerUserId, PLATFORM_LIST_LIMIT);
        List<ProxyWorkOrderEntity> items = filterByStatus(all, status);
        long openCount = all.stream()
                .filter(e -> ProxyWorkOrderEntity.STATUS_OPEN.equals(e.getStatus()))
                .count();
        return new ProxyWorkOrderPage(toViews(items), openCount);
    }

    /**
     * 撤销自己尚未被执行的申请。
     *
     * <p>没有这个入口，授权就不可撤回：商家误点一次「申请平台协助下架」，
     * 那条工单会一直 {@code OPEN}，任何一天都可能被某个运营拿去执行。
     * 一封发出去就收不回来的授权信不是授权，是漏洞。
     */
    @Transactional
    public ProxyWorkOrderView voidMine(String workOrderNo) {
        Long ownerUserId = sellerProductService.requireStoreOwnerUserId();
        ProxyWorkOrderEntity wo = requireByNo(workOrderNo);
        if (!Objects.equals(wo.getTenantId(), ownerUserId)) {
            throw BizException.notFound("代运营工单");
        }
        if (!ProxyWorkOrderEntity.STATUS_OPEN.equals(wo.getStatus())) {
            throw BizException.validation("工单当前为「" + ProxyWorkOrderEntity.labelOf(wo.getStatus())
                    + "」，不能再撤销");
        }
        LocalDateTime now = nowUtc();
        if (workOrderMapper.markVoid(wo.getId(), now) != 1) {
            // CAS 失败 = 并发下已被执行或被撤销。不能当成成功返回：
            // 商家以为撤掉了、而实际可能已被平台执行，是两件后果完全不同的事。
            throw BizException.conflict("工单状态已变化（可能已被平台执行），请刷新后确认");
        }
        wo.setStatus(ProxyWorkOrderEntity.STATUS_VOID);
        wo.setUpdatedAt(now);
        return toView(wo);
    }

    // ==================== 平台端：待办 / 执行 ====================

    /**
     * 平台查看商品类工单待办（只读）。
     *
     * <p>权限是「进后台」（{@code platform:console}）而不是 {@code tenant:proxy-ops}：
     * 只读地看到「哪些店申请了什么」不改变任何状态，而**能不能做**由执行端点把关。
     * 口径是「进门与动数分开」—— 进门宽、动数严。
     */
    public ProxyWorkOrderPage listForPlatform(String status, Long tenantId, String targetRef) {
        OperatorGuard.requireOperator();
        List<ProxyWorkOrderEntity> all;
        if (targetRef != null && !targetRef.isBlank()) {
            all = workOrderMapper.openOnTarget(ProxyWorkOrderEntity.TARGET_PRODUCT, targetRef.trim());
        } else if (tenantId != null) {
            all = workOrderMapper.recentOfTenant(tenantId, PLATFORM_LIST_LIMIT);
        } else {
            all = workOrderMapper.selectList(new LambdaQueryWrapper<ProxyWorkOrderEntity>()
                    .eq(ProxyWorkOrderEntity::getTargetType, ProxyWorkOrderEntity.TARGET_PRODUCT)
                    .orderByDesc(ProxyWorkOrderEntity::getCreatedAt)
                    .last("LIMIT " + PLATFORM_LIST_LIMIT));
        }
        List<ProxyWorkOrderEntity> items = filterByStatus(all, status);
        long openCount = all.stream()
                .filter(e -> ProxyWorkOrderEntity.STATUS_OPEN.equals(e.getStatus()))
                .count();
        return new ProxyWorkOrderPage(toViews(items), openCount);
    }

    /**
     * 代下架：平台持有第三方的有效工单，替它把商品下架。
     *
     * <p>幂等口径：商品已经是 {@code OFF_SHELF} 时不再改写（{@link SellerProductService}
     * 内部只在状态确实不同才落库），但工单**照常消费** —— 目标是「让它不在售」，
     * 该目标已达成，授权就该用掉。若为了"省一次消费"而放行，就会出现
     * 「已经下架的商品上永远挂着一条可用的授权」，那才是真正的风险。
     */
    @Transactional
    public SellerProductView proxyOffShelf(Long spuId, ProxyOffShelfRequest req) {
        LoginUser operator = OperatorGuard.requirePerm(Permission.TENANT_PROXY_OPS);
        String workOrderNo = requireText(req == null ? null : req.workOrderNo(), "工单号", MAX_NO_LEN);
        String reason = requireText(req == null ? null : req.reason(), "执行说明", MAX_REASON_LEN);

        SpuEntity spu = spuMapper.selectById(spuId);
        if (spu == null) {
            throw BizException.notFound("商品");
        }
        requireNotSelfSupplied(spu, "代下架");

        ProxyWorkOrderEntity wo = requireUsable(workOrderNo, spu);
        consume(wo, operator);

        String before = spu.getStatus();
        AuditTrail.target(spu.getId(), "PRODUCT");
        AuditTrail.tenant(spu.getSellerId());
        AuditTrail.change(before, "OFF_SHELF");
        AuditTrail.note("代运营代下架｜工单 " + wo.getWorkOrderNo()
                + "｜申请事由：" + wo.getReason() + "｜执行说明：" + reason);

        SellerProductView view = sellerProductService.forceOffShelfByProxy(spuId,
                "平台代运营代下架（工单 " + wo.getWorkOrderNo() + "）：" + reason);
        log.info("代运营代下架完成：商品 {} 店铺 {} 工单 {} 执行人 {}",
                spuId, spu.getSellerId(), wo.getWorkOrderNo(), operator.userId());
        return view;
    }

    // ==================== 判据（本类的核心） ====================

    /**
     * 校验工单对「这次动作 + 这个目标」是否构成有效授权。
     *
     * <p>顺序是刻意的：先判「授权本身能不能用」（存在 / 状态），再判「授权对不对得上」
     * （目标类型 / 动作 / 目标 / 租户）。这样运营看到 400 时知道是工单的问题、
     * 看到 403 时知道是「拿错店的单子」—— 两类事故的处理方式完全不同，
     * 用同一个状态码盖住会让排查从「看一眼」变成「翻代码」。
     */
    private ProxyWorkOrderEntity requireUsable(String workOrderNo, SpuEntity spu) {
        ProxyWorkOrderEntity wo = requireByNo(workOrderNo);

        if (!ProxyWorkOrderEntity.STATUS_OPEN.equals(wo.getStatus())) {
            throw BizException.validation("代运营工单「" + wo.getWorkOrderNo() + "」当前为「"
                    + ProxyWorkOrderEntity.labelOf(wo.getStatus()) + "」，不能用于执行动作"
                    + "（授权一次性，已执行的不可重复使用）");
        }
        if (!ProxyWorkOrderEntity.TARGET_PRODUCT.equals(wo.getTargetType())) {
            throw BizException.validation("工单 " + wo.getWorkOrderNo() + " 的目标类型是 "
                    + wo.getTargetType() + "，不能用于商品操作");
        }
        if (!ProxyWorkOrderEntity.ACTION_OFF_SHELF.equals(wo.getActionType())) {
            throw BizException.validation("工单 " + wo.getWorkOrderNo() + " 申请的是「"
                    + ProxyWorkOrderEntity.actionLabelOf(wo.getActionType()) + "」，与本次动作「"
                    + ProxyWorkOrderEntity.actionLabelOf(ProxyWorkOrderEntity.ACTION_OFF_SHELF)
                    + "」不一致");
        }
        if (!Objects.equals(wo.getTargetRef(), String.valueOf(spu.getId()))) {
            throw BizException.validation("工单 " + wo.getWorkOrderNo() + " 针对的是商品 "
                    + wo.getTargetRef() + "，不能用于商品 " + spu.getId());
        }
        // ★ 安全关键：工单的租户必须等于该商品的店铺主体。租户键是店铺主体的 userId
        //   （t_spu.seller_id），不是 t_merchant.id —— 两处口径一旦分开，
        //   就会出现「校验通过、动作落在别人家」这种最坏的组合。
        if (!Objects.equals(wo.getTenantId(), nzOrNull(spu.getSellerId()))) {
            throw BizException.forbidden("工单 " + wo.getWorkOrderNo() + " 属于租户 "
                    + wo.getTenantId() + "，无权用于商品 " + spu.getId()
                    + "（店铺主体 " + nz(spu.getSellerId()) + "）");
        }
        return wo;
    }

    /**
     * 消费工单（一次性）。
     *
     * <p>CAS 返回 0 行 = 并发下已被别人消费。此时必须**报错终止**，
     * 绝不能"当作没事继续执行动作"—— 那正好就是「一个授权做两次操作」，
     * 而且是并发场景，事后从日志里看不出破绽。
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
     * 平台自营商品不走代运营。
     *
     * <p>自营没有第三方商家，这份「授权」是假的。假授权会把工单表污染成一份
     * 「看起来有商家申请过」的记录，让这个机制唯一的价值（可核查的授权）
     * 在真正需要的时候已经不可信。提示指向可执行的下一步，而不是一句「不允许」。
     *
     * <p>判据**不在这里自己写一份**：直接复用 {@link SellerProductService#isPlatformSelf}
     * ——「自营」的定义只能有一个出口。本类曾经独立地判 {@code seller_id = 0}，
     * 与 {@code SellerProductService} 的收窄判据是两份会各自漂移的拷贝；
     * 这类"同一个概念、两处实现"的判据一旦漂移，症状就是「一边放行、一边拦」，
     * 而两边各自看都自洽。
     */
    private static void requireNotSelfSupplied(SpuEntity spu, String action) {
        if (SellerProductService.isPlatformSelf(spu)) {
            throw BizException.businessRule("商品 " + spu.getId()
                    + " 属于平台自营，平台可直接处置（商家端商品接口），无需（也不应）通过代运营"
                    + action + "；代运营只用于第三方店铺的受控代操作");
        }
    }

    private static List<ProxyWorkOrderEntity> filterByStatus(List<ProxyWorkOrderEntity> all, String status) {
        if (status == null || status.isBlank()) {
            return all;
        }
        List<ProxyWorkOrderEntity> items = new ArrayList<>();
        for (ProxyWorkOrderEntity e : all) {
            if (status.equalsIgnoreCase(e.getStatus())) {
                items.add(e);
            }
        }
        return items;
    }

    private static Long parseSpuId(String ref) {
        try {
            return Long.valueOf(ref);
        } catch (NumberFormatException ex) {
            // 不静默当成"查不到"：一个非数字的 spuId 是**请求写错了**，
            // 与「这个商品不存在」是两回事，混在一起会让调用方一直以为数据被删了。
            throw BizException.validation("商品目标必须是数字形式的 spuId，收到：" + ref);
        }
    }

    private String newWorkOrderNo(LocalDateTime now) {
        String stamp = now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        long tail = idGenerator.nextId() % 100000L;
        return ProxyWorkOrderEntity.NO_PREFIX_PRODUCT + stamp + "-" + String.format("%05d", tail);
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
     * 必填文本校验。**不接受只有空白的值**：一个全是空格的「事由」在页面上看着像填了，
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
        return v.toUpperCase(Locale.ROOT);
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    /** 用于与工单租户比较：null 与 0 语义不同，不能都折成 0 */
    private static Long nzOrNull(Long value) {
        return value == null ? null : value;
    }

    private static String isoTs(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC).toString();
    }

    private static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
