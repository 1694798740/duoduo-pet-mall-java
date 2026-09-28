package com.duoduo.marketing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.marketing.dto.CouponDtos.ClaimResult;
import com.duoduo.marketing.dto.CouponDtos.ConsumeResult;
import com.duoduo.marketing.dto.CouponDtos.CouponView;
import com.duoduo.marketing.dto.CouponDtos.CreateTemplateRequest;
import com.duoduo.marketing.dto.CouponDtos.LockRequest;
import com.duoduo.marketing.dto.CouponDtos.LockResult;
import com.duoduo.marketing.dto.CouponDtos.ReleaseResult;
import com.duoduo.marketing.dto.CouponDtos.SweepResult;
import com.duoduo.marketing.dto.CouponDtos.TemplateView;
import com.duoduo.marketing.dto.CouponDtos.UsableCoupon;
import com.duoduo.marketing.dto.CouponDtos.UsableResult;
import com.duoduo.marketing.entity.CouponEntity;
import com.duoduo.marketing.entity.CouponTemplateEntity;
import com.duoduo.marketing.mapper.CouponMapper;
import com.duoduo.marketing.mapper.CouponTemplateMapper;
import com.duoduo.marketing.support.CouponCodeGenerator;
import com.duoduo.marketing.support.CouponStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 券服务：领券、我的券包、结算页可用券，以及给交易域用的锁券 / 解锁 / 核销。
 *
 * <p><b>本服务不碰钱</b>：它只回答「这张券能不能用、能抵多少」，并把券自身的状态机管住。
 * 折扣金额由交易域写进 {@code t_order.discount_amount}，应付金额与资金托管也都在交易域 ——
 * 一旦营销域开始算「应付多少」，同一个数字就会有两个来源，对不上时无从判断谁对。
 *
 * <p>三条硬规则：
 * <ol>
 *   <li><b>领券幂等靠唯一索引</b>（{@code uk_coupon_user_tpl}）：同一用户同一模板只能有一张。</li>
 *   <li><b>状态迁移一律 CAS</b>：锁券 / 解锁 / 核销都把「当前状态必须是 X」写进 WHERE
 *       （INV-1，禁止分布式锁）。并发下先查后写必然漏。</li>
 *   <li><b>失败要给人话</b>：券不可用的每一种原因都有明确文案，由交易域原样转给用户。</li>
 * </ol>
 */
@Service
public class CouponService {

    private static final Logger log = LoggerFactory.getLogger(CouponService.class);

    private static final String TYPE_LABEL_FULL_REDUCE = "满减券";

    private final CouponMapper couponMapper;
    private final CouponTemplateMapper templateMapper;
    private final SnowflakeIdGenerator idGenerator;

    public CouponService(CouponMapper couponMapper,
                         CouponTemplateMapper templateMapper,
                         SnowflakeIdGenerator idGenerator) {
        this.couponMapper = couponMapper;
        this.templateMapper = templateMapper;
        this.idGenerator = idGenerator;
    }

    // ---------------- 领券中心 ----------------

    /**
     * 可领取的券模板列表（公开只读）。
     *
     * <p>{@code claimable} 对未登录访客一律为 {@code false} 且原因是「登录后可领取」：
     * 「已领过」这件事依赖用户身份，没有身份就不能说「你没领过」——
     * 说错方向的代价是用户点了半天才发现要领不了。
     */
    public List<TemplateView> claimableTemplates() {
        LocalDateTime now = nowUtc();
        Long userId = currentUserIdOrNull();
        List<CouponTemplateEntity> templates = templateMapper.selectClaimable(now);
        if (templates.isEmpty()) {
            return List.of();
        }
        Map<Long, CouponEntity> claimed = userId == null
                ? Map.of()
                : couponMapper.selectList(new LambdaQueryWrapper<CouponEntity>()
                        .eq(CouponEntity::getUserId, userId))
                        .stream()
                        .collect(Collectors.toMap(CouponEntity::getTemplateId, Function.identity(), (a, b) -> a));
        List<TemplateView> views = new ArrayList<>(templates.size());
        for (CouponTemplateEntity t : templates) {
            views.add(toTemplateView(t, now, claimed.containsKey(t.getId()), userId != null));
        }
        return views;
    }

    @Transactional
    public ClaimResult claim(Long templateId) {
        Long userId = UserContext.requireUserId();
        LocalDateTime now = nowUtc();

        CouponTemplateEntity template = templateMapper.selectById(templateId);
        if (template == null) {
            throw BizException.notFound("优惠券");
        }
        if (!CouponTemplateEntity.STATUS_ACTIVE.equals(template.getStatus())) {
            throw BizException.businessRule("该优惠券已停用，无法领取");
        }
        if (now.isBefore(template.getStartAt())) {
            throw BizException.businessRule("该优惠券尚未开始领取（" + isoTs(template.getStartAt()) + " 开始）");
        }
        if (now.isAfter(template.getEndAt())) {
            throw BizException.businessRule("该优惠券的领取活动已结束");
        }

        // 幂等快路径：已领过就直接回执（用户重复点击是常态，不该走到占位再撞索引）
        CouponEntity existing = couponMapper.findByUserAndTemplate(userId, templateId);
        if (existing != null) {
            return toClaimResult(existing, template, true);
        }

        // 先占额度（CAS），再插券。两步之间若撞唯一索引，必须把额度还回去（见 unclaimOne）
        if (templateMapper.claimOne(templateId, now) == 0) {
            throw BizException.businessRule("该优惠券已被领完");
        }
        CouponEntity coupon = new CouponEntity();
        coupon.setId(idGenerator.nextId());
        coupon.setCode(CouponCodeGenerator.next());
        coupon.setTemplateId(templateId);
        coupon.setUserId(userId);
        coupon.setStatus(CouponStatus.UNUSED.name());
        coupon.setClaimedAt(now);
        // 有效期在领券这一刻从模板快照下来：之后运营改模板窗口，不该让已领到手的券跟着变
        coupon.setExpireAt(template.getEndAt());
        coupon.setCreatedAt(now);
        coupon.setUpdatedAt(now);
        try {
            couponMapper.insert(coupon);
        } catch (DuplicateKeyException e) {
            // 并发重复领取：额度已占，必须归还，否则库会凭空少一张券
            templateMapper.unclaimOne(templateId, now);
            CouponEntity winner = couponMapper.findByUserAndTemplate(userId, templateId);
            if (winner == null) {
                // 理论上不可能：唯一索引冲突说明有一行存在。真发生了说明数据异常，如实抛出
                throw BizException.conflict("领取过于频繁，请稍后重试");
            }
            log.info("用户 {} 重复领取模板 {} 被唯一索引拦下，已归还额度", userId, templateId);
            return toClaimResult(winner, template, true);
        }
        log.info("用户 {} 领取券模板 {}，券码 {}", userId, templateId, coupon.getCode());
        return toClaimResult(coupon, template, false);
    }

    // ---------------- 我的券包 / 结算页可用券 ----------------

    /**
     * 我的券包。
     *
     * <p>{@code status} 为空时返回全部（含已用 / 已过期），由前端分页签展示；
     * 传了就只返回该状态。传了非法状态一律 400，而不是返回空列表 ——
     * 「筛选条件写错了」和「你确实没有券」是两件完全不同的事。
     */
    public List<CouponView> mine(String status) {
        Long userId = UserContext.requireUserId();
        if (status != null && !status.isBlank() && CouponStatus.of(status.trim()) == null) {
            throw BizException.validation("不支持的券状态：" + status.trim());
        }
        LambdaQueryWrapper<CouponEntity> wrapper = new LambdaQueryWrapper<CouponEntity>()
                .eq(CouponEntity::getUserId, userId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(CouponEntity::getStatus, status.trim());
        }
        wrapper.orderByDesc(CouponEntity::getCreatedAt).orderByDesc(CouponEntity::getId);
        List<CouponEntity> coupons = couponMapper.selectList(wrapper);
        return toCouponViews(coupons);
    }

    /**
     * 结算页可用券（按订单商品金额筛选）。
     *
     * <p>只回「未使用且未过期」的券，并为每张给出 {@code usable} 与不能用的原因：
     * 用户在结算页看到「这张券还差 20 元可用」比看到一张灰掉的券有用得多。
     *
     * <p>{@code bestCouponCode} 由服务端挑（抵扣额最大；并列时先过期的优先）：
     * 「默认帮用户选最省钱的一张」只该有一个实现，前端与后端各选一次就会出现
     * 「页面显示已选 A、实际下单锁的是 B」。
     */
    public UsableResult usable(long amount) {
        Long userId = UserContext.requireUserId();
        if (amount < 0) {
            throw BizException.validation("订单金额不能为负数");
        }
        LocalDateTime now = nowUtc();
        List<CouponEntity> candidates = couponMapper.selectUsable(userId, now);
        Map<Long, CouponTemplateEntity> templates = loadTemplates(candidates);

        List<UsableCoupon> rows = new ArrayList<>(candidates.size());
        for (CouponEntity coupon : candidates) {
            CouponTemplateEntity t = templates.get(coupon.getTemplateId());
            if (t == null) {
                continue;
            }
            boolean ok = amount >= nz(t.getThresholdAmount());
            rows.add(new UsableCoupon(
                    coupon.getCode(),
                    t.getName(),
                    nz(t.getThresholdAmount()),
                    nz(t.getDiscountAmount()),
                    isoTs(coupon.getExpireAt()),
                    ok,
                    ok ? null : ("订单金额未达门槛，满 " + fenToYuan(t.getThresholdAmount()) + " 元可用")));
        }
        // 抵扣额大的在前；并列时先到期的在前（先帮用户用掉快过期的）
        rows.sort(Comparator
                .comparingLong(UsableCoupon::discountAmount).reversed()
                .thenComparing(UsableCoupon::expireAt, Comparator.nullsLast(Comparator.naturalOrder())));

        UsableCoupon best = rows.stream().filter(UsableCoupon::usable).findFirst().orElse(null);
        return new UsableResult(amount, rows,
                best == null ? null : best.code(),
                best == null ? 0L : best.discountAmount());
    }

    // ---------------- 服务间：锁券 / 解锁 / 核销 ----------------

    /**
     * 锁券（交易域下单时调用）。
     *
     * <p><b>不抛业务异常，而是返回 {@link LockResult}</b>：调用方（交易域）要把
     * 「券用不了」翻译成下单失败的具体原因给用户看。抛异常会让 Feign 变成
     * {@code FeignException}，交易域只能拿到一个 HTTP 状态码，
     * 「满 200 减 30，你还差 20 元」这句话就丢了 —— 而它恰恰是用户唯一需要的信息。
     *
     * <p>门槛判断用的是调用方传进来的 {@code orderAmount}：营销域不持有购物车与 SKU 价，
     * 让它反过来查订单等于在两个服务之间再开一条查询通道，且两边金额口径还可能不一致。
     */
    @Transactional
    public LockResult lock(LockRequest request) {
        // 校验与试算共用同一条链路：试算若自己再写一遍规则，
        // 迟早出现「预览说能用、下单说不能用」—— 那正是这个接口要消灭的问题。
        Precheck precheck = precheck(request);
        if (!precheck.result().success()) {
            return precheck.result();
        }

        LocalDateTime now = nowUtc();
        LockResult verdict = precheck.result();
        // CAS：只有 UNUSED 且未过期的那一行会被改成 LOCKED，并发下只有一单能成功
        int affected = couponMapper.lock(verdict.code(), request.userId(), request.orderNo(), now);
        if (affected == 0) {
            return LockResult.reject("该优惠券刚刚被其他订单占用，请重新选择");
        }
        log.info("券 {} 已被订单 {} 锁定，抵扣 {} 分（订单金额 {} 分，门槛 {} 分）",
                verdict.code(), request.orderNo(), verdict.discountAmount(),
                request.orderAmount(), precheck.threshold());
        return verdict;
    }

    /**
     * 券试算（<b>只读</b>，结算预览用）：跑完锁券前的全部校验并算出抵扣额，<b>不写任何数据</b>。
     *
     * <p>与 {@link #lock} 共用 {@link #precheck}，因此「预览能不能用」与「下单能不能用」
     * 由同一份规则回答 —— 两条链路各写一遍判断，是「预览说能用、下单说不行」的必然后果。
     *
     * <p>语义差别只在最后一步：{@code lock} 会把券置为 LOCKED 完成占用，
     * 试算只是把结论告诉调用方，因此这里不需要订单号、也不产生任何副作用。
     */
    public LockResult trial(String code, Long userId, long orderAmount) {
        return precheck(new LockRequest(code, userId, TRIAL_ORDER_NO, orderAmount)).result();
    }

    /**
     * 锁券 / 试算共用的前置校验与抵扣额计算（<b>纯读</b>）。
     *
     * <p>返回 {@link LockResult} 而不是抛异常：券不可用的原因（未达门槛 / 已使用 / 已被占用）
     * 是要直接给用户看的一句话，抛异常会让它退化成 HTTP 状态码。
     *
     * <p>额外带出门槛值：锁券成功时它只用于日志（`满 X 元可用` 的 X），
     * 试算路径不需要它，但两条路径共用同一个返回值最省事、也最不容易走偏。
     */
    private Precheck precheck(LockRequest request) {
        LocalDateTime now = nowUtc();
        CouponEntity coupon = couponMapper.findByCode(request.code().trim());
        if (coupon == null) {
            return new Precheck(LockResult.reject("优惠券不存在"), 0L);
        }
        if (!coupon.getUserId().equals(request.userId())) {
            return new Precheck(LockResult.reject("该优惠券不属于当前用户"), 0L);
        }
        CouponStatus status = CouponStatus.of(coupon.getStatus());
        if (status == null) {
            return new Precheck(LockResult.reject("优惠券状态异常（" + coupon.getStatus() + "），请联系客服"), 0L);
        }
        if (status == CouponStatus.USED) {
            return new Precheck(LockResult.reject("该优惠券已被使用"), 0L);
        }
        if (status == CouponStatus.EXPIRED || coupon.getExpireAt().isBefore(now)) {
            return new Precheck(LockResult.reject("该优惠券已过期"), 0L);
        }
        if (status == CouponStatus.LOCKED) {
            return new Precheck(LockResult.reject("该优惠券已被其他订单占用，请重新选择"), 0L);
        }

        CouponTemplateEntity template = templateMapper.selectById(coupon.getTemplateId());
        if (template == null) {
            return new Precheck(LockResult.reject("优惠券对应的活动已下线"), 0L);
        }
        long threshold = nz(template.getThresholdAmount());
        if (request.orderAmount() < threshold) {
            return new Precheck(LockResult.reject(
                    "订单金额未达门槛，该券满 " + fenToYuan(threshold) + " 元可用"), threshold);
        }
        long discount = Math.min(nz(template.getDiscountAmount()), request.orderAmount());
        return new Precheck(LockResult.ok(coupon.getCode(), discount), threshold);
    }

    /** 前置校验的内部返回值：判定结果 + 门槛值（仅日志用） */
    private record Precheck(LockResult result, long threshold) {
    }

    /**
     * 试算用的占位订单号。
     *
     * <p>试算不写库，订单号不参与任何判断，但 {@link LockRequest#orderNo()} 有
     * {@code @NotBlank} 约束 —— 给一个显式常量比给 {@code null} 更清楚地表达
     * 「这条链路根本不需要订单号」，也避免日后有人误以为试算可以带真实订单号进来。
     */
    private static final String TRIAL_ORDER_NO = "__TRIAL__";

    /**
     * 解锁（交易域关单 / 取消 / 超时时调用）。
     *
     * <p>{@code released=0} 是常态（大多数订单没用券），不是错误 ——
     * 因此这里返回计数而不抛异常。券回到 {@code UNUSED} 后可再次使用。
     */
    @Transactional
    public ReleaseResult release(String orderNo) {
        LocalDateTime now = nowUtc();
        List<CouponEntity> locked = couponMapper.findLockedByOrder(orderNo.trim());
        int released = 0;
        for (CouponEntity coupon : locked) {
            // 带上 orderNo 的 CAS：只解自己锁的那张，不会误伤被别的订单锁住的券
            released += couponMapper.release(coupon.getCode(), orderNo.trim(), now);
        }
        if (released > 0) {
            log.info("订单 {} 已解锁 {} 张券", orderNo, released);
        }
        return new ReleaseResult(released);
    }

    /**
     * 核销（交易域支付成功时调用）。USED 是终态，重复核销影响 0 行。
     *
     * <p><b>为什么核销必须与支付成功绑定、而不是下单成功</b>：券抵扣的是真金白银，
     * 订单没付钱就把券核销掉，用户付不付款都损失了一张券。
     */
    @Transactional
    public ConsumeResult consume(String orderNo) {
        LocalDateTime now = nowUtc();
        List<CouponEntity> locked = couponMapper.findLockedByOrder(orderNo.trim());
        int consumed = 0;
        for (CouponEntity coupon : locked) {
            consumed += couponMapper.consume(coupon.getCode(), orderNo.trim(), now);
        }
        if (consumed > 0) {
            log.info("订单 {} 已核销 {} 张券", orderNo, consumed);
        }
        return new ConsumeResult(consumed);
    }

    // ---------------- 运营建券 ----------------

    /** 运营创建券模板（准入由控制层的 OperatorGuard 把关） */
    @Transactional
    public TemplateView createTemplate(CreateTemplateRequest request) {
        LocalDateTime now = nowUtc();
        String type = request.type().trim().toUpperCase();
        if (!CouponTemplateEntity.TYPE_FULL_REDUCE.equals(type)) {
            throw BizException.businessRule("暂不支持的券类型：" + request.type() + "（当前仅支持满减券 FULL_REDUCE）");
        }
        LocalDateTime startAt = parseTs(request.startAt(), "领取开始时间");
        LocalDateTime endAt = parseTs(request.endAt(), "领取结束时间");
        if (!endAt.isAfter(startAt)) {
            throw BizException.validation("领取结束时间必须晚于开始时间");
        }
        if (request.discountAmount() > request.thresholdAmount() && request.thresholdAmount() > 0) {
            throw BizException.validation("抵扣金额不能大于满减门槛");
        }

        CouponTemplateEntity entity = new CouponTemplateEntity();
        entity.setId(idGenerator.nextId());
        entity.setName(request.name().trim());
        entity.setDescription(request.description() == null ? null : request.description().trim());
        entity.setType(type);
        entity.setThresholdAmount(request.thresholdAmount());
        entity.setDiscountAmount(request.discountAmount());
        entity.setTotalCount(request.totalCount());
        entity.setClaimedCount(0);
        entity.setStartAt(startAt);
        entity.setEndAt(endAt);
        entity.setStatus(CouponTemplateEntity.STATUS_ACTIVE);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        templateMapper.insert(entity);
        log.info("运营创建券模板 {}（{}，满 {} 分减 {} 分，共 {} 张）",
                entity.getId(), entity.getName(), entity.getThresholdAmount(),
                entity.getDiscountAmount(), entity.getTotalCount());
        return toTemplateView(entity, now, false, true);
    }

    // ---------------- 补偿调度（由 CouponReleaseJob 触发） ----------------

    /**
     * 一轮补偿：解锁「锁定超过 TTL 仍未支付」的券 + 把过期未用的券标记为 EXPIRED。
     *
     * <p>解锁必须有兜底，不能只靠交易域主动调 release：
     * 下单成功但用户直接关掉浏览器、交易域进程重启、对端调用失败……
     * 这些情况下主动调用根本不会发生。没有兜底，券会被永久锁死 ——
     * 用户看着卡包里一张「已锁定」的券用不了，运营在后台也查不出任何异常。
     *
     * <p>可重入：解锁只认 LOCKED、标记过期只认 UNUSED，重复执行天然无副作用。
     */
    @Transactional
    public SweepResult sweep(int lockTtlMinutes, int batchSize) {
        LocalDateTime now = nowUtc();
        LocalDateTime deadline = now.minusMinutes(Math.max(1, lockTtlMinutes));
        List<CouponEntity> stale = couponMapper.findLockedBefore(deadline, Math.max(1, batchSize));
        int released = 0;
        for (CouponEntity coupon : stale) {
            released += couponMapper.release(coupon.getCode(), coupon.getLockedOrderNo(), now);
        }
        if (released > 0) {
            log.warn("券补偿调度：解锁 {} 张超时锁定的券（锁定早于 {}）", released, isoTs(deadline));
        }
        int expired = couponMapper.markExpired(now);
        if (expired > 0) {
            log.info("券补偿调度：{} 张过期券已标记为 EXPIRED", expired);
        }
        return new SweepResult(stale.size(), released, expired);
    }

    // ---------------- 内部实现 ----------------

    private List<CouponView> toCouponViews(List<CouponEntity> coupons) {
        Map<Long, CouponTemplateEntity> templates = loadTemplates(coupons);
        List<CouponView> views = new ArrayList<>(coupons.size());
        for (CouponEntity coupon : coupons) {
            CouponTemplateEntity t = templates.get(coupon.getTemplateId());
            views.add(new CouponView(
                    coupon.getCode(),
                    coupon.getTemplateId(),
                    t == null ? null : t.getName(),
                    t == null ? null : t.getType(),
                    t == null ? null : typeLabel(t.getType()),
                    t == null ? 0L : nz(t.getThresholdAmount()),
                    t == null ? 0L : nz(t.getDiscountAmount()),
                    coupon.getStatus(),
                    CouponStatus.labelOf(coupon.getStatus()),
                    isoTs(coupon.getClaimedAt()),
                    isoTs(coupon.getExpireAt()),
                    coupon.getLockedOrderNo(),
                    coupon.getUsedOrderNo()));
        }
        return views;
    }

    /** 一次性把涉及到的模板查回来，避免逐张券查库造成 N+1 */
    private Map<Long, CouponTemplateEntity> loadTemplates(List<CouponEntity> coupons) {
        List<Long> ids = coupons.stream()
                .map(CouponEntity::getTemplateId)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return templateMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(CouponTemplateEntity::getId, Function.identity(), (a, b) -> a));
    }

    private ClaimResult toClaimResult(CouponEntity coupon, CouponTemplateEntity template, boolean replay) {
        return new ClaimResult(
                coupon.getCode(),
                coupon.getTemplateId(),
                template.getName(),
                nz(template.getThresholdAmount()),
                nz(template.getDiscountAmount()),
                isoTs(coupon.getExpireAt()),
                replay);
    }

    private TemplateView toTemplateView(CouponTemplateEntity t, LocalDateTime now, boolean claimed, boolean loggedIn) {
        boolean active = CouponTemplateEntity.STATUS_ACTIVE.equals(t.getStatus());
        int remaining = Math.max(0, nzInt(t.getTotalCount()) - nzInt(t.getClaimedCount()));
        String reason;
        boolean claimable;
        if (!active) {
            reason = "该券已停用";
            claimable = false;
        } else if (now.isBefore(t.getStartAt())) {
            reason = "尚未开始领取";
            claimable = false;
        } else if (now.isAfter(t.getEndAt())) {
            reason = "领取已结束";
            claimable = false;
        } else if (remaining <= 0) {
            reason = "已被领完";
            claimable = false;
        } else if (claimed) {
            reason = "你已领取过";
            claimable = false;
        } else if (!loggedIn) {
            // 未登录访客一律不算「可领取」：领券接口需要登录态，
            // 把一个注定 401 的按钮标成可点，用户点完只会以为系统坏了。
            // 这与「商品评价未登录也能看」不冲突 —— 那是只读，这是写。
            reason = "登录后可领取";
            claimable = false;
        } else {
            reason = null;
            claimable = true;
        }
        return new TemplateView(
                t.getId(),
                t.getName(),
                t.getDescription(),
                t.getType(),
                typeLabel(t.getType()),
                nz(t.getThresholdAmount()),
                nz(t.getDiscountAmount()),
                nzInt(t.getTotalCount()),
                nzInt(t.getClaimedCount()),
                remaining,
                isoTs(t.getStartAt()),
                isoTs(t.getEndAt()),
                t.getStatus(),
                claimable,
                reason);
    }

    private static String typeLabel(String type) {
        if (CouponTemplateEntity.TYPE_FULL_REDUCE.equals(type)) {
            return TYPE_LABEL_FULL_REDUCE;
        }
        return type == null ? "优惠券" : type;
    }

    /** 分转元文案：只用于给用户的提示，绝不参与计算 */
    private static String fenToYuan(Long fen) {
        long v = nz(fen);
        if (v % 100 == 0) {
            return String.valueOf(v / 100);
        }
        return String.format("%.2f", v / 100.0);
    }

    private static LocalDateTime parseTs(String value, String field) {
        try {
            return LocalDateTime.parse(value.trim().replace("Z", ""));
        } catch (DateTimeParseException e) {
            throw BizException.validation(field + " 格式不正确，应为 ISO8601（如 2026-12-31T23:59:59）");
        }
    }

    private static Long currentUserIdOrNull() {
        var user = UserContext.get();
        return user == null ? null : user.userId();
    }

    private static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static String isoTs(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC).toString();
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    private static int nzInt(Integer value) {
        return value == null ? 0 : value;
    }
}
