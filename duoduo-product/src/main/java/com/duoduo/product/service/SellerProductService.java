package com.duoduo.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.product.client.MembershipClient;
import com.duoduo.product.client.MerchantLicenseClient;
import com.duoduo.product.client.MerchantRiskClient;
import com.duoduo.product.client.dto.MembershipInfo;
import com.duoduo.product.client.dto.MerchantLicenseInfo;
import com.duoduo.product.client.dto.MerchantRiskInfo;
import com.duoduo.product.dto.ProductDtos.AdminProductAuditView;
import com.duoduo.product.dto.ProductDtos.SellerProductCreateRequest;
import com.duoduo.product.dto.ProductDtos.SellerProductDetailView;
import com.duoduo.product.dto.ProductDtos.SellerProductUpdateRequest;
import com.duoduo.product.dto.ProductDtos.SellerProductView;
import com.duoduo.product.dto.ProductDtos.SellerSkuInput;
import com.duoduo.product.dto.ProductDtos.SellerSkuView;
import com.duoduo.product.entity.SkuEntity;
import com.duoduo.product.entity.SpuEntity;
import com.duoduo.product.mapper.SkuMapper;
import com.duoduo.product.mapper.SpuMapper;
import com.duoduo.product.support.BannedWordGuard;
import com.duoduo.product.support.CatalogLookup;
import com.duoduo.product.support.Csv;
import com.duoduo.product.support.SpuRiskJudge;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 商家端 · 商品管理 + 平台侧 · 商品上架审核（E14）。
 *
 * <h2>这一版新增了什么（相对「只管理已有商品」的旧版）</h2>
 * <ol>
 *   <li><b>商家可以提交商品了</b>：旧版刻意不做新建（B-04 边界），因为新建涉及
 *       类目 / 品牌 / SKU / 价格 / 库存的完整表单与审核；E14 把这条边界解除，
 *       表单与审核链一并落地。</li>
 *   <li><b>审核维度</b>（{@code audit_status}）：提交 → 平台审核 → 上架；
 *       未过审商品在前台<b>一律不可见</b>（由 {@code ProductService.visibleSpuWrapper}
 *       在全部公开读路径上把守，本类只管写侧的审核状态机）。</li>
 *   <li><b>内容变更再审</b>：审核结论是针对「当时那一段内容」作出的，
 *       内容变了结论就失效 —— 否则卖家可以「先过审、再改成违规内容」。</li>
 *   <li><b>店员可以管理商品</b>（E13 遗留边界）：店员以<b>店铺主体</b>（店主 userId）行动，
 *       与交易域 {@code SellerService} 同一套判定。</li>
 * </ol>
 *
 * <h2>三条贯穿全类的口径（改动前先读）</h2>
 * <ol>
 *   <li><b>交易维度与审核维度正交</b>：{@code status}（在售 / 下架，卖家可控）
 *       与 {@code auditStatus}（PENDING / APPROVED / REJECTED，平台判定）是两列。
 *       公开可见性要求<b>两者同时满足</b>。挤进一列就无法表达
 *       「已过审但被卖家下架」与「驳回件等卖家改内容」这两种真实组合。</li>
 *   <li><b>平台自营免审</b>：{@code supplyType='self'} 的商品（运营建的、seller_id=0）
 *       auditStatus 恒为 APPROVED。平台不需要审核自己 —— 给自营也加一道审核，
 *       只会让运营自己给自己点通过。判据是<b>供货类型</b>而不是「谁在操作」：
 *       运营去改第三方商品时，那个商品仍要重新走审核。</li>
 *   <li><b>准入（你是不是卖家）与归属（这商品是不是你的）分开</b>：
 *       准入看 {@link #requireSellerScope()}，归属看 {@link #requireOwned}。
 *       商品不存在返回 404、存在但不是你的返回 403 —— 刻意区分，
 *       不让越权探测伪装成「查无此单」（与交易域同一条纪律）。</li>
 * </ol>
 *
 * <h2>为什么平台侧方法（listForAudit / audit）也在这个类里</h2>
 * 与 C2C 闲置 {@code ListingService} 同一取向：一套商品数据的读写规则集中在一处，
 * 审核与编辑对同一批字段的理解不会分叉。运营侧方法的准入由 {@code OperatorGuard} 把关。
 */
@Service
public class SellerProductService {

    private static final Logger log = LoggerFactory.getLogger(SellerProductService.class);

    private static final String STATUS_ON_SALE = "ON_SALE";
    private static final String STATUS_OFF_SHELF = "OFF_SHELF";

    private static final String AUDIT_PENDING = "PENDING";
    private static final String AUDIT_APPROVED = "APPROVED";
    private static final String AUDIT_REJECTED = "REJECTED";

    private static final String SUPPLY_SELF = "self";
    private static final String SUPPLY_MERCHANT = "merchant";

    /** 平台自营在 {@code t_spu.seller_id} 上的哨兵值（不是一个真实的店铺 id） */
    private static final long PLATFORM_SELF_SELLER_ID = 0L;

    // ---------------- E28 · 审核来源与自动放行 ----------------

    /** 系统按规则自动放行（没有人类审核人） */
    public static final String AUDIT_SOURCE_AUTO = "AUTO";
    /** 运营人工判定 */
    public static final String AUDIT_SOURCE_MANUAL = "MANUAL";
    /** 自营免审：不需要审核这回事，与「自动放行」刻意分开记 */
    public static final String AUDIT_SOURCE_SELF = "SELF";
    /** 事后抽查判定（系统） */
    public static final String AUDIT_SOURCE_SAMPLE = "SAMPLE";

    /**
     * 价格基准的取样上限。
     *
     * <p>算「同类均价」时只取最近 {@value} 件在售商品，而不是全量 AVG：
     * 这条规则是**加分项**（多看一眼），不是判据。为它做一次全表聚合
     * 会让商品提交这条高频写路径依赖商品表的规模 ——
     * 卖掉第一万件商品之后，提交一次商品就要扫全表，代价与收益完全不成比例。
     */
    private static final int PRICE_BENCHMARK_SAMPLE = 200;

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int TITLE_MAX = 60;
    private static final int SUBTITLE_MAX = 60;
    private static final int IMAGE_MAX = 500;
    private static final int DETAIL_MAX = 4000;
    private static final int MAX_IMAGES = 9;
    private static final int MAX_SKUS = 20;
    private static final int SPEC_NAME_MAX = 30;
    private static final int MAX_STOCK = 999_999;
    private static final long MAX_PRICE_CENTS = 9_999_900L;

    private final SpuMapper spuMapper;
    private final SkuMapper skuMapper;
    private final MembershipClient membershipClient;
    private final MerchantLicenseClient merchantLicenseClient;
    private final MerchantRiskClient merchantRiskClient;
    private final SnowflakeIdGenerator idGenerator;
    private final BannedWordGuard bannedWordGuard;
    private final CatalogLookup catalogLookup;
    private final ObjectMapper objectMapper;

    public SellerProductService(SpuMapper spuMapper,
                                SkuMapper skuMapper,
                                MembershipClient membershipClient,
                                MerchantLicenseClient merchantLicenseClient,
                                MerchantRiskClient merchantRiskClient,
                                SnowflakeIdGenerator idGenerator,
                                BannedWordGuard bannedWordGuard,
                                CatalogLookup catalogLookup,
                                ObjectMapper objectMapper) {
        this.spuMapper = spuMapper;
        this.skuMapper = skuMapper;
        this.membershipClient = membershipClient;
        this.merchantLicenseClient = merchantLicenseClient;
        this.merchantRiskClient = merchantRiskClient;
        this.idGenerator = idGenerator;
        this.bannedWordGuard = bannedWordGuard;
        this.catalogLookup = catalogLookup;
        this.objectMapper = objectMapper;
    }

    // ---------------- 准入与归属 ----------------

    /**
     * 当前登录者的卖家身份与可见范围（E14 起与交易域 {@code SellerService.SellerScope} 同构）。
     *
     * @param ownerUserId   本店主体（店主 user id）；运营 / 管理员为 {@code null}
     * @param platformScope 是否平台视角（运营 / 管理员）：可跨店管理全部商品
     * @param staffMember   是否店员（E13）：权限来自「以店铺主体行动」，不来自角色
     */
    private record SellerScope(Long ownerUserId, boolean platformScope, boolean staffMember) {
    }

    /**
     * 校验当前登录者可以使用商家端商品管理，并给出可见范围。
     *
     * <p>角色从网关透传的 {@code X-User-Role} 来，不由客户端声明 ——
     * 否则「我是卖家」就变成了请求里的一句话。
     *
     * <p>角色不是卖家时<b>再查一次成员身份</b>（实时、不缓存）：店员停用必须即时生效。
     * 查不到（无店 / 已停用）按非卖家处理。
     */
    private SellerScope requireSellerScope() {
        LoginUser user = UserContext.require();
        if (user.isAdmin() || user.isOperator()) {
            return new SellerScope(null, true, false);
        }
        if (user.canAccessSellerConsole()) {
            return new SellerScope(user.userId(), false, false);
        }
        MembershipInfo membership = membershipOf(user.userId());
        if (membership != null && membership.activeStaff()) {
            // 店员以店铺主体（店主 userId）行动：商品归属写的是店主，
            // 这与「订单挂在店主 userId 下」是同一个口径
            return new SellerScope(Long.valueOf(membership.ownerUserId()), false, true);
        }
        throw BizException.forbidden("该账号不是卖家，无法使用商家端");
    }

    /**
     * 查店铺成员身份。Feign 调用失败按「非店员」处理 ——
     * 认证链路的下游故障不能把「拒绝」变成「放行」。
     */
    private MembershipInfo membershipOf(Long userId) {
        try {
            return membershipClient.of(userId).data();
        } catch (Exception ex) {
            log.warn("成员身份查询失败，按非店员处理 userId={}", userId, ex);
            return null;
        }
    }

    /**
     * 取出商品并校验归属（准入在调用处已做）。
     *
     * <p>抽成一处是因为上下架、编辑、重提、查看详情都要走同一条判定：
     * 少一处就会变成「上下架挡住了、编辑没挡住」这类只在越权时才暴露的漏洞，
     * 而代码上看两处几乎一样。
     *
     * <p><b>平台自营商品（seller_id=0）对商家一律 403</b>：
     * 自营商品不属于任何第三方店铺，若不显式拦，「seller_id=0」会被
     * 「ownerUserId 恰好也算出 0」之类的边界糊过去，第三方就有机会改到自营商品。
     */
    private SpuEntity requireOwned(Long spuId, SellerScope scope) {
        SpuEntity spu = spuMapper.selectById(spuId);
        if (spu == null) {
            throw BizException.notFound("商品");
        }
        if (scope.platformScope()) {
            return spu;
        }
        long actual = nz(spu.getSellerId());
        if (actual == 0L || actual != scope.ownerUserId()) {
            // 存在但不是你的：明确 403。商品 ID 是雪花 ID 不可枚举，
            // 统一 404 只会帮攻击者消除信号
            throw BizException.forbidden("该商品不属于当前账号");
        }
        return spu;
    }

    // ---------------- 商家侧：我的商品 ----------------

    /**
     * 我的商品列表（分页 + 标题关键词 + 审核状态筛选）。
     *
     * <p>运营 / 管理员可见全部（可选按 sellerId 过滤），为平台管理后台复用同一接口留口；
     * 普通卖家与店员无条件强制 {@code seller_id = 店铺主体}，请求里的 sellerId 参数不生效 ——
     * 「换个 ID 看别人的商品」这条路从一开始就不存在。
     */
    public PageResult<SellerProductView> list(String keyword, String auditStatus, Long sellerId,
                                              int page, int pageSize) {
        SellerScope scope = requireSellerScope();

        int p = Math.max(1, page);
        int size = Math.max(1, Math.min(pageSize <= 0 ? DEFAULT_PAGE_SIZE : pageSize, MAX_PAGE_SIZE));

        LambdaQueryWrapper<SpuEntity> wrapper = new LambdaQueryWrapper<>();
        if (scope.platformScope()) {
            // ★ 修掉一个既有缺陷：旧版把 forcedSellerId = sellerId 直接塞进 eq()，
            //   而 MyBatis-Plus 的 eq 对 null **不会跳过条件**，会生成 `seller_id = NULL`
            //   （永不匹配）—— 也就是「运营不传 sellerId 想看成全部商品，得到的却是空列表」。
            //   这里改成「传了才筛」，语义与注释里写的「为空则看全部」终于一致。
            if (sellerId != null) {
                wrapper.eq(SpuEntity::getSellerId, sellerId);
            }
        } else {
            wrapper.eq(SpuEntity::getSellerId, scope.ownerUserId());
        }
        String kw = trimToNull(keyword);
        if (kw != null) {
            wrapper.like(SpuEntity::getTitle, kw);
        }
        String audit = normalizeAuditFilter(auditStatus);
        if (audit != null) {
            wrapper.eq(SpuEntity::getAuditStatus, audit);
        }
        wrapper.orderByDesc(SpuEntity::getUpdatedAt).orderByDesc(SpuEntity::getId);

        Page<SpuEntity> result = spuMapper.selectPage(new Page<>(p, size), wrapper);
        List<SpuEntity> records = result.getRecords();
        Map<Long, int[]> skuStats = skuStatsBySpuIds(records.stream().map(SpuEntity::getId).toList());

        List<SellerProductView> views = records.stream()
                .map(spu -> toView(spu, skuStats.getOrDefault(spu.getId(), new int[]{0, 0})))
                .toList();
        return PageResult.of(views, result.getTotal(), p, size);
    }

    /** 商家视角详情（编辑回填用；公开详情对未过审商品 404，卖家必须读得回自己的东西） */
    public SellerProductDetailView ownerDetail(Long spuId) {
        SellerScope scope = requireSellerScope();
        SpuEntity spu = requireOwned(spuId, scope);
        return toDetailView(spu, skusOf(spuId));
    }

    // ---------------- 商家侧：提交 / 编辑 / 上下架 / 重提 ----------------

    /**
     * 提交一个商品（E14 新增）。
     *
     * <p><b>服务端决定三件事，请求体说了不算</b>：
     * <ul>
     *   <li><b>供货类型</b>：运营 / 管理员提交的算平台自营（{@code self}），
     *       其他人提交的算第三方（{@code merchant}）；</li>
     *   <li><b>归属</b>：第三方商品归「店铺主体」（店主 userId），
     *       店员提交的商品也归店铺而不是店员个人 —— 与订单归属同一口径，
     *       否则店主停掉店员后，那人提交的商品会变成谁都管不了的孤儿；</li>
     *   <li><b>审核态</b>：第三方一律 {@code PENDING}（必须审核）；
     *       自营一律 {@code APPROVED}（平台免审）。若接受客户端传
     *       {@code auditStatus=APPROVED}，整条人工审核链当场失效。</li>
     * </ul>
     *
     * <p><b>为什么提交后 status 就落 ON_SALE</b>：与 C2C 闲置同一口径 ——
     * 交易维度先按「卖家希望它在售」落，可见性由审核维度把守。
     * 好处是审核通过的那一刻商品<b>自动出现在前台</b>，卖家不需要再点一次上架
     * （「审核通过了却还看不见」是最典型的客服工单来源）。
     */
    @Transactional(rollbackFor = Exception.class)
    public SellerProductView create(SellerProductCreateRequest request) {
        SellerScope scope = requireSellerScope();
        if (request == null) {
            throw BizException.validation("请求体不能为空");
        }

        String title = requireText(request.title(), "商品标题", TITLE_MAX);
        String subtitle = optionalText(request.subtitle(), "副标题", SUBTITLE_MAX);
        String mainImage = requireText(request.mainImage(), "商品主图", IMAGE_MAX);
        String detail = optionalText(request.detail(), "商品详情", DETAIL_MAX);
        List<String> images = normalizeImages(request.images());
        requireCategoryExists(request.categoryId());
        requireBrandExists(request.brandId());
        List<NormalizedSku> skus = normalizeSkus(request.skus());

        // 违禁词在写库前拦：违规内容连库都不该进（与闲置发布同一纪律）。
        // 审核是「人看得过来才叫审核」，把最脏的挡在入口能显著减轻审核队列。
        bannedWordGuard.rejectIfHit(title, "商品标题");
        if (subtitle != null) {
            bannedWordGuard.rejectIfHit(subtitle, "商品副标题");
        }
        if (detail != null) {
            bannedWordGuard.rejectIfHit(detail, "商品详情");
        }

        boolean selfSupplied = scope.platformScope();
        Long ownerUserId = selfSupplied ? 0L : scope.ownerUserId();
        long spuId = idGenerator.nextId();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        SpuEntity spu = new SpuEntity();
        spu.setId(spuId);
        spu.setTitle(title);
        spu.setSubtitle(subtitle);
        spu.setCategoryId(request.categoryId());
        spu.setBrandId(request.brandId());
        spu.setSupplyType(selfSupplied ? SUPPLY_SELF : SUPPLY_MERCHANT);
        spu.setMainImage(mainImage);
        spu.setImages(images.isEmpty() ? null : String.join(",", images));
        spu.setDetail(detail);
        spu.setStatus(STATUS_ON_SALE);
        spu.setSalesCount(0);
        // 评分从 0 起步（而不是 DDL 里的 5.00）：t_spu.rating / rating_count 的唯一写入口
        // 是评价域的聚合回写，种子期的 5.00 是历史假数据。0 + count=0 让展示层
        // 按「有真实评价才显示评分」正确处理新商品
        spu.setRating(BigDecimal.ZERO.setScale(2));
        spu.setRatingCount(0);
        spu.setMinPrice(skus.stream().mapToLong(NormalizedSku::price).min().orElse(0L));
        spu.setMaxPrice(skus.stream().mapToLong(NormalizedSku::price).max().orElse(0L));
        spu.setSellerId(ownerUserId);

        // ★ 审核维度**放在最后**定：自动放行判定要读上面那批内容字段
        //   （标题 / 副标题 / 详情 / 类目 / 最低价）。提前落状态就会变成
        //   「先判后写、判的全是空值」—— 而空值恰好不命中任何风险规则，
        //   于是**所有商品都会被自动放行**，而且测试很难发现（因为它看起来"都过了"）。
        AuditDecision decision = decideAudit(spu);
        boolean autoApproved = decision.autoApprove();
        spu.setAuditStatus(autoApproved ? AUDIT_APPROVED : AUDIT_PENDING);
        spu.setAuditSource(autoApproved
                ? (selfSupplied ? AUDIT_SOURCE_SELF : AUDIT_SOURCE_AUTO)
                : null);
        spu.setAuditReason(null);
        // ★ 自动放行**也必须**写 audit_at —— 抽查的候选集就是「当天自动放行的第三方商品」，
        //   筛的正是 `audit_at ∈ [当天, 次日)`（见 AuditSampleService.sweepOnce）。
        //   这里留 null 不会报任何错：抽查任务照跑、返回 created=0，看起来只是「今天没有可抽的」，
        //   实际上是这条制度的入口被自己堵死了 —— 而它恰恰是「免除事前人工审核」的全部对价。
        spu.setAuditAt(autoApproved ? now : null);
        spu.setAuditBy(null);
        spu.setCreatedAt(now);
        spu.setUpdatedAt(now);
        spuMapper.insert(spu);

        int seq = 0;
        for (NormalizedSku sku : skus) {
            skuMapper.insert(toSkuEntity(spuId, ++seq, sku, now));
        }

        log.info("{} 提交商品 {}「{}」（供货={} 归属={} 审核态={} 来源={} 规格数={}）",
                scope.staffMember() ? "店员" : (selfSupplied ? "运营" : "店主"),
                spuId, title, spu.getSupplyType(), ownerUserId, spu.getAuditStatus(),
                spu.getAuditSource() == null ? "转人工" : spu.getAuditSource(), skus.size());
        return toView(spu, new int[]{skus.size(), skus.stream().mapToInt(NormalizedSku::stock).sum()});
    }

    /**
     * 编辑自己的商品（E14 把可编辑字段从「标题 + 主图」放开到全部<b>内容字段</b>）。
     *
     * <h2>字段三态（沿用旧版语义，未变）</h2>
     * {@code null} = 不改、{@code ""} = 清空（仅主图/副标题等可空字段）、有值 = 替换。
     * 把「不改」当成「清空」会让用户永远删不掉主图；反过来则改了没反应。
     *
     * <h2>为什么内容一改就要重新审核</h2>
     * 审核结论是针对「当时那一段内容」作出的。放它继续挂着 APPROVED，
     * 等于让卖家可以「先过审、再改成违规内容」—— 这是内容审核最常见的绕过手法。
     * 于是：<b>内容有实质变化 → auditStatus 回 PENDING</b>，公开列表与详情
     * 都要求 APPROVED，所以商品自然从前台消失，不需要额外的下线动作。
     *
     * <h2>价格与库存为什么不在这里改</h2>
     * 它们在 SKU 上，且牵动 ES 索引、在途订单的金额口径与库存冻结对账 ——
     * 属于「改价即改合约」的范畴，不做成卖家随手可改的字段（这条边界是旧版就定下的，
     * E14 不放开）。本期要调价走下架后重新提交。
     */
    public SellerProductView update(Long spuId, SellerProductUpdateRequest request) {
        SellerScope scope = requireSellerScope();
        if (request == null) {
            throw BizException.validation("请求体不能为空");
        }
        SpuEntity spu = requireOwned(spuId, scope);

        boolean changed = false;
        if (request.title() != null) {
            String normalized = requireText(request.title(), "商品标题", TITLE_MAX);
            if (!normalized.equals(spu.getTitle())) {
                bannedWordGuard.rejectIfHit(normalized, "商品标题");
                spu.setTitle(normalized);
                changed = true;
            }
        }
        if (request.subtitle() != null) {
            String normalized = trimToNull(request.subtitle());
            if (normalized != null && normalized.length() > SUBTITLE_MAX) {
                throw BizException.validation("副标题不能超过 " + SUBTITLE_MAX + " 个字");
            }
            if (!java.util.Objects.equals(normalized, spu.getSubtitle())) {
                if (normalized != null) {
                    bannedWordGuard.rejectIfHit(normalized, "商品副标题");
                }
                spu.setSubtitle(normalized);
                changed = true;
            }
        }
        if (request.mainImage() != null) {
            String normalized = trimToNull(request.mainImage());
            if (normalized != null && normalized.length() > IMAGE_MAX) {
                throw BizException.validation("主图地址不能超过 " + IMAGE_MAX + " 个字符");
            }
            if (normalized == null) {
                // 主图是列表页与搜索结果里唯一的视觉锚点，清空等于让商品变成一片空白
                throw BizException.validation("商品主图不能为空");
            }
            if (!normalized.equals(spu.getMainImage())) {
                spu.setMainImage(normalized);
                changed = true;
            }
        }
        if (request.images() != null) {
            List<String> images = normalizeImages(request.images());
            String joined = images.isEmpty() ? null : String.join(",", images);
            if (!java.util.Objects.equals(joined, spu.getImages())) {
                spu.setImages(joined);
                changed = true;
            }
        }
        if (request.detail() != null) {
            String normalized = optionalText(request.detail(), "商品详情", DETAIL_MAX);
            if (!java.util.Objects.equals(normalized, spu.getDetail())) {
                if (normalized != null) {
                    bannedWordGuard.rejectIfHit(normalized, "商品详情");
                }
                spu.setDetail(normalized);
                changed = true;
            }
        }
        if (request.categoryId() != null && !request.categoryId().equals(spu.getCategoryId())) {
            requireCategoryExists(request.categoryId());
            spu.setCategoryId(request.categoryId());
            changed = true;
        }
        if (request.brandId() != null && !request.brandId().equals(spu.getBrandId())) {
            requireBrandExists(request.brandId());
            spu.setBrandId(request.brandId());
            changed = true;
        }

        if (!changed) {
            return toView(spu, skuStatsOf(spuId));
        }
        List<SpuRiskJudge.Hit> risks = applyContentReaudit(spu);
        spu.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        spuMapper.updateById(spu);
        // ★ updateById 的更新策略是 NOT_NULL —— 它**不写 null 字段**。
        //   而「转人工」时 audit_source 恰恰要写回 NULL（上一次结论的来源已作废）。
        //   不显式 set 的话，界面上会看到「待审 + 来源=AUTO」这种自相矛盾的组合：
        //   接口 200、状态也变了，只有那一列悄悄留着旧值，无异常、无日志、断言也抓不到。
        if (!risks.isEmpty() && isMerchantSupplied(spu)) {
            spuMapper.update(null, new LambdaUpdateWrapper<SpuEntity>()
                    .eq(SpuEntity::getId, spuId)
                    .set(SpuEntity::getAuditSource, null)
                    .set(SpuEntity::getAuditAt, null));
        }
        log.info("商品 {} 由 {} 编辑：内容变更 → {}（来源={} 命中风险={}）",
                spuId, scope.staffMember() ? "店员" : (scope.platformScope() ? "运营" : "店主"),
                spu.getAuditStatus(), spu.getAuditSource() == null ? "转人工" : spu.getAuditSource(),
                risks.stream().map(SpuRiskJudge.Hit::code).toList());
        return toView(spu, skuStatsOf(spuId));
    }

    /**
     * 上架 / 下架（幂等开关）。
     *
     * <p>为什么用「开关 + 幂等」而不是严格状态机：上下架可逆、无资金动作，
     * 重复请求与并发点击天然无副作用；真正要拦的是越权、目标状态非法，
     * 以及<b>绕过审核</b>。
     *
     * <p><b>E14 门禁</b>：目标态 {@code ON_SALE} 要求 {@code auditStatus=APPROVED}，
     * 否则「先提交成 PENDING、再自己上架」就绕过了整条审核链。
     * <b>E15 再加一道</b>：店铺资质必须有效（见 {@link #requireShopActive}）。
     * 下架不限 —— 卖家主动撤下自己的商品永远是权利，不需要先过审、也不看资质。
     * 自营商品 auditStatus 恒 APPROVED、且没有资质概念，两道门禁对它都透明。
     */
    public SellerProductView changeStatus(Long spuId, String action) {
        SellerScope scope = requireSellerScope();
        String target = normalizeAction(action);

        SpuEntity spu = requireOwned(spuId, scope);
        // ★ E23 收窄：`requireOwned` 对平台运营一律放行（platformScope），
        //   这在商品还没有第三方归属时是对的，但 E27 之后
        //   「平台能改别人的商品」就是越权。第三方商品必须走代运营 + 工单。
        requireProxyForThirdParty(spu, scope);
        if (STATUS_ON_SALE.equals(target)) {
            requireShopActive(scope);
            if (isMerchantSupplied(spu) && !AUDIT_APPROVED.equals(spu.getAuditStatus())) {
                throw BizException.businessRule(auditBlockReason(spu.getAuditStatus()));
            }
        }

        if (!target.equals(spu.getStatus())) {
            String from = spu.getStatus();
            spu.setStatus(target);
            spu.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            spuMapper.updateById(spu);
            log.info("商品 {} 由卖家 {} 上下架：{} -> {}", spuId, UserContext.require().userId(), from, target);
        }
        return toView(spu, skuStatsOf(spuId));
    }

    // ---------------- E23 · 代运营（商品域）的两个入口 ----------------

    /**
     * 当前登录者所属的**店铺主体 userId**（代运营申请用）。
     *
     * <p>平台运营（{@code platformScope}）在此被**显式拒绝**，而不是让它带着自己的
     * userId 往下走、最后在归属校验那里得到一个 404。两者的区别很重要：
     * 403 说清的是「平台账号不能替商家授权」这个规则，
     * 而 404 会被读成「这个商品不存在」，让人去查数据而不是查流程。
     *
     * <p>店员允许通过：店员以店铺主体身份行动是既有口径（E13），
     * 而且店员本就是店主授权出来的 —— 它不构成「平台替商家授权」这一例外。
     */
    public Long requireStoreOwnerUserId() {
        SellerScope scope = requireSellerScope();
        if (scope.platformScope() || scope.ownerUserId() == null) {
            throw BizException.forbidden(
                    "代运营申请必须由店铺（店主或店员）发起；平台账号不能替商家作出授权");
        }
        return scope.ownerUserId();
    }

    /**
     * 代运营专用：强制下架（E23）。
     *
     * <p><b>为什么单独开一个方法，而不是复用 {@link #changeStatus}</b>：
     * 后者带着「平台运营只能动自营商品」的门禁（{@link #requireProxyForThirdParty}），
     * 而这正是代运营要合法跨过的那条线。复用就必须在方法里加一个
     * 「是不是代运营调用」的开关参数 —— 那种开关迟早会在某个调用点被传错，
     * 而传错的方向恰好是「越权被放行」。
     *
     * <p><b>这里为什么不做归属与授权校验</b>：调用方
     * {@code ProxyOpsService.proxyOffShelf} 已经在**同一个事务内**完成了
     * 工单完整性 + 租户一致性校验并消费了工单。把校验复制一份到这里，
     * 只会得到两份会各自漂移的判据；本方法把自己限制为「纯粹的写」，
     * 并且只被代运营调用。
     *
     * <p>幂等：状态已经是 {@code OFF_SHELF} 时不再落库，但仍返回视图 ——
     * 目标是「让它不在售」，已达成即成功。
     */
    @Transactional
    public SellerProductView forceOffShelfByProxy(Long spuId, String reason) {
        SpuEntity spu = spuMapper.selectById(spuId);
        if (spu == null) {
            throw BizException.notFound("商品");
        }
        if (isPlatformSelf(spu)) {
            throw BizException.businessRule("商品 " + spuId
                    + " 属于平台自营，不能通过代运营下架（平台可直接处置）");
        }
        if (!STATUS_OFF_SHELF.equals(spu.getStatus())) {
            String from = spu.getStatus();
            spu.setStatus(STATUS_OFF_SHELF);
            spu.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            spuMapper.updateById(spu);
            log.info("代运营下架商品 {}（店铺 {}）：{} -> {}，原因：{}",
                    spuId, spu.getSellerId(), from, STATUS_OFF_SHELF, reason);
        }
        return toView(spu, skuStatsOf(spuId));
    }

    /**
     * E23 · 平台运营对**第三方店铺**的商品没有直接处置权。
     *
     * <h2>这条规则是从哪里来的</h2>
     * 商品域给平台运营的可见范围是 {@code platformScope = true}，归属校验一律放行。
     * 在商品还没有第三方归属的年代，这没问题 —— 平台管的就是自己的货。
     * 但从 E27 起 {@code t_spu.seller_id} 会指向真实第三方卖家，
     * 同一段代码的含义就从「平台管自己的货」变成了「平台能改别人的商品」。
     * 这不是新增限制，而是把一个**已经变味**的既有行为收回到它原本的语义上。
     *
     * <h2>为什么只拦上/下架，不顺手拦编辑与删除</h2>
     * 因为这一项（E23）要解决的是「代下架」这一个动作的直接路径。
     * 把编辑、删除、改价一并拦掉当然更严，但那是另一个决定（影响面、回滚方式、
     * 运营既有习惯都不同），应当单独评估，而不是借一次收窄顺手改变更多行为 ——
     * 顺手扩大改动范围，会让「这次上线到底改了什么」变得说不清。
     * 同类风险已登记为遗留项。
     */
    private static void requireProxyForThirdParty(SpuEntity spu, SellerScope scope) {
        if (!scope.platformScope()) {
            // 店主 / 店员：归属由 requireOwned 判，不属于本次收窄范围
            return;
        }
        if (isPlatformSelf(spu)) {
            // 平台自营：平台管自己的货，不需要任何授权
            return;
        }
        throw BizException.forbidden("商品 " + spu.getId() + " 属于第三方店铺（主体 "
                + spu.getSellerId() + "），平台不能直接上下架；如确需处理，"
                + "请由该店铺店主在商家端发起代运营申请，再由平台持工单执行"
                + "（POST /api/admin/products/{spuId}/proxy-off-shelf）");
    }

    /**
     * 这个商品是不是**平台自营**。
     *
     * <h2>为什么是两个条件的「与」，而不是只看 seller_id</h2>
     * 本域对「自营 / 第三方」的**权威口径是 {@code t_spu.supply_type}**
     * （见 {@code ProductService} 里"平台自营商品（supply_type='self'）"的注释，
     * 以及本类的 {@link #isMerchantSupplied}）；{@code seller_id = 0} 只是自营在
     * 归属列上的哨兵值，是一个**派生**信号。
     *
     * <p>两个信号在今天的库里是一致的，但一致是**数据现状**，不是**定义**。
     * 只看 {@code seller_id} 的话，一旦某个自营商品被挂到平台客服账号名下
     * （非 0 的 seller_id，supply_type 仍是 self），它就会被判成第三方：
     * 平台会突然"处置不了自己的货"，而且报错信息会言之凿凿地说它属于第三方店铺 ——
     * 这种错不在安全上危险，但极难排查，因为它只在数据出现偏差时才现形。
     *
     * <p>所以这里取**两个信号的与**：只有当"归属是哨兵值"**且**"供货类型不是商家供货"
     * 时才算自营。任一信号指向第三方，就按第三方对待 —— 这个方向是刻意选的：
     * 判重的代价是多一张工单，判轻的代价是越权。
     */
    public static boolean isPlatformSelf(SpuEntity spu) {
        return nz(spu.getSellerId()) == PLATFORM_SELF_SELLER_ID && !isMerchantSupplied(spu);
    }

    /**
     * E15 门禁：店铺当前处于可营业状态，否则不许上架。
     *
     * <h2>为什么门禁落在「上架」而不是「所有商品操作」</h2>
     * 停业（资质过期）时商家仍应能管理自己的商品：下架、改价、清理库存都不该被拦 ——
     * 那是他在为恢复营业做准备。真正必须拦住的是<b>把商品重新摆出去卖</b>。
     * 若一刀切拦住所有写操作，商家的第一反应是「系统坏了」而不是「我该续期了」。
     *
     * <h2>★ 查不到 / 查不通时放行</h2>
     * 这是纵深防御的<b>第二道</b>门：第一道是「过期即停业 + 商品联动下架」，
     * 它不依赖用户域是否可用。若这里在 Feign 抖动时改成「拒绝」，
     * 一次跨域超时就会让全平台商家都无法上架 —— 用一个可自愈的小问题
     * 换一个全站停工，代价完全不成比例。故障会以 WARN 留在日志里。
     */
    private void requireShopActive(SellerScope scope) {
        if (scope.platformScope() || scope.ownerUserId() == null) {
            return;                     // 运营 / 自营：不走入驻流程，没有资质概念
        }
        MerchantLicenseInfo license;
        try {
            license = merchantLicenseClient.of(scope.ownerUserId()).data();
        } catch (Exception ex) {
            log.warn("店铺资质查询失败，本次上架按放行处理 ownerUserId={} : {}",
                    scope.ownerUserId(), ex.getMessage());
            return;
        }
        if (license == null || license.canSell()) {
            return;                     // 没有入驻记录（历史数据）或状态正常
        }
        throw BizException.businessRule("店铺当前状态为「" + statusLabel(license.status())
                + "」，无法上架商品" + (license.suspendedReason() == null
                ? "；请先完成资质审核" : "：" + license.suspendedReason()));
    }

    /** 把店铺状态翻译成给商家看的话（内部枚举值直接甩给商家等于没提示） */
    private static String statusLabel(String status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case "SUSPENDED" -> "已停业（资质过期或违规）";
            case "SUBMITTED" -> "资质审核中";
            case "REVIEWING" -> "资质审核中";
            case "REJECTED" -> "资质申请已驳回";
            case "DRAFT" -> "入驻申请未提交";
            default -> status;
        };
    }

    /**
     * E15 · 内部：把某店铺的<b>全部在售商品一次性下架</b>（资质过期联动停售）。
     *
     * <h2>为什么这是「命令式」而不是「条件式」</h2>
     * 另一种做法是让商品可见性多查一个条件（「所在店铺资质有效」），
     * 但那样每次商品列表、每次搜索都要跨域问一次用户域 —— 成本高、耦合紧，
     * 而且一个跨域调用超时就能让整个商城前台搜不出任何东西。
     * 这里选择<b>在停业那一刻把状态改掉</b>：判断只发生一次，
     * 之后前台的读路径完全不知道自己背后发生过什么。
     *
     * <h2>只动 status，不动 audit_status</h2>
     * 与 E14「审核通过不动 status」互为镜像，理由相同：交易维度与合规维度是两个维度，
     * 一次停业不改变「这个商品曾经被审核通过」这个事实。续期通过后商家重新上架即可，
     * <b>不需要重新送审</b> —— 内容一个字没改，再走一遍审核等于白审。
     *
     * <h2>索引同步</h2>
     * 只更新 {@code updated_at}，由 {@code SearchSyncJob} 的 15 秒补偿扫描自动重索引 ——
     * 与商家手动上下架走的是同一条路（{@link #changeStatus} 也只更新时间戳）。
     * 不在这里直接写 ES：搜索是「尽力而为」的旁路，让商品写路径依赖 ES 可用性，
     * 就成了「搜索挂了商家就下不了架」。
     *
     * @param sellerId 店铺主体（t_merchant.owner_user_id）
     * @return 实际被下架的商品数（幂等：已在架下的不会被重复计入）
     */
    public int offShelfAllBySeller(Long sellerId) {
        if (sellerId == null || sellerId == 0L) {
            // 平台自营（seller_id = 0 是既有语义，不是一个真实的店铺 id）
            // 不走入驻流程、没有资质概念 —— 传 0 进来只能是调用方搞错了，直接空转
            log.warn("忽略对自营（sellerId=0）的停售指令");
            return 0;
        }
        int rows = spuMapper.update(null, new LambdaUpdateWrapper<SpuEntity>()
                .eq(SpuEntity::getSellerId, sellerId)
                .eq(SpuEntity::getStatus, STATUS_ON_SALE)
                .set(SpuEntity::getStatus, STATUS_OFF_SHELF)
                .set(SpuEntity::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC)));
        log.info("店铺 {} 因资质停业，在售商品下架 {} 件", sellerId, rows);
        return rows;
    }

    /**
     * 重新提审（E14）：被驳回的商品<b>不改内容</b>，直接回到审核队列。
     *
     * <p>为什么需要它（而不是只靠编辑触发再审）：
     * 驳回可能是误判（图片被判违规但实际合规），此时卖家没有内容可改 ——
     * 只给「改内容才能重提」，卖家就只能随便改一个字来凑，
     * 反而制造了无意义的版本噪音。这条路径是「申请复核」。
     *
     * <p>状态机口径：
     * <ul>
     *   <li>只有 REJECTED 能重提：PENDING 是「正在审」（重复提交只会打乱审核队列），
     *       APPROVED 是「已通过」（想改内容走编辑，那条路自己会回到 PENDING）；</li>
     *   <li>重提 = 清空驳回原因 + 回到 PENDING + status 恢复 ON_SALE
     *       （驳回时 status 已被审核动作压成 OFF_SHELF）。</li>
     * </ul>
     */
    public SellerProductView resubmit(Long spuId) {
        SellerScope scope = requireSellerScope();
        SpuEntity spu = requireOwned(spuId, scope);
        if (AUDIT_PENDING.equals(spu.getAuditStatus())) {
            throw BizException.businessRule("该商品正在审核中，请等待审核结果");
        }
        if (AUDIT_APPROVED.equals(spu.getAuditStatus())) {
            throw BizException.businessRule("该商品已通过审核，无需重新提交；如需修改请直接编辑");
        }
        spu.setAuditStatus(AUDIT_PENDING);
        spu.setAuditReason(null);
        spu.setStatus(STATUS_ON_SALE);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        spu.setUpdatedAt(now);
        // ★ 用显式 set(null) 而不是 updateById：MyBatis-Plus 的更新策略是 NOT_NULL，
        //   updateById 不会把 audit_source 写回 NULL，结果就是「待审 + 来源=上一次的结论」
        //   这种自相矛盾的组合会一直挂在审核台上（接口 200，无异常，断言也抓不到）。
        //   「重新提审」的语义是「上一次结论已作废」，来源标记必须跟着一起作废。
        spuMapper.update(null, new LambdaUpdateWrapper<SpuEntity>()
                .eq(SpuEntity::getId, spuId)
                .set(SpuEntity::getAuditStatus, AUDIT_PENDING)
                .set(SpuEntity::getAuditReason, null)
                .set(SpuEntity::getAuditSource, null)
                .set(SpuEntity::getAuditAt, null)
                .set(SpuEntity::getStatus, STATUS_ON_SALE)
                .set(SpuEntity::getUpdatedAt, now));
        log.info("商品 {} 由卖家重新提审，回到待审核", spuId);
        return toView(spu, skuStatsOf(spuId));
    }

    // ---------------- 平台侧：上架审核台 ----------------

    /**
     * 审核台列表：按审核状态筛选的<b>第三方</b>商品。
     *
     * <p>为什么只列第三方商品：自营商品的 auditStatus 恒为 APPROVED（平台免审），
     * 把它们混进审核队列，运营每次都要在「与自己无关的商品」里翻找真正的待办。
     * 「看全部商品」是商品管理视图的需求，不在审核台。
     *
     * <p>审核结论是按时间先后处理的，所以<b>待审的排最前</b>：列表默认顺序若按更新时间倒排，
     * 一个刚被编辑过的已通过商品会插到待办前面，运营很容易漏掉真正等着审的那几条。
     */
    public PageResult<AdminProductAuditView> listForAudit(String auditStatus, Long sellerId,
                                                          int page, int pageSize) {
        OperatorGuard.requireOperator();
        int p = Math.max(1, page);
        int size = Math.max(1, Math.min(pageSize <= 0 ? DEFAULT_PAGE_SIZE : pageSize, MAX_PAGE_SIZE));

        LambdaQueryWrapper<SpuEntity> wrapper = new LambdaQueryWrapper<SpuEntity>()
                .eq(SpuEntity::getSupplyType, SUPPLY_MERCHANT);
        if (sellerId != null) {
            wrapper.eq(SpuEntity::getSellerId, sellerId);
        }
        String audit = normalizeAuditFilter(auditStatus);
        if (audit != null) {
            wrapper.eq(SpuEntity::getAuditStatus, audit);
        }
        wrapper.orderByAsc(SpuEntity::getAuditStatus)
                .orderByDesc(SpuEntity::getUpdatedAt)
                .orderByDesc(SpuEntity::getId);

        Page<SpuEntity> result = spuMapper.selectPage(new Page<>(p, size), wrapper);
        List<SpuEntity> records = result.getRecords();
        Map<Long, List<SkuEntity>> skusBySpu = skusBySpuIds(records.stream().map(SpuEntity::getId).toList());
        Map<Long, String> categoryNames = catalogLookup.categoryNames(
                records.stream().map(SpuEntity::getCategoryId).toList());
        Map<Long, String> brandNames = catalogLookup.brandNames(
                records.stream().map(SpuEntity::getBrandId).toList());

        List<AdminProductAuditView> views = records.stream()
                .map(spu -> toAuditView(spu,
                        skusBySpu.getOrDefault(spu.getId(), List.of()),
                        categoryNames.get(spu.getCategoryId()),
                        spu.getBrandId() == null ? null : brandNames.get(spu.getBrandId())))
                .toList();
        return PageResult.of(views, result.getTotal(), p, size);
    }

    /** 审核台详情（按 id 取单条，含全部背审材料） */
    public AdminProductAuditView auditDetail(Long spuId) {
        OperatorGuard.requireOperator();
        SpuEntity spu = spuMapper.selectById(spuId);
        if (spu == null) {
            throw BizException.notFound("商品");
        }
        Map<Long, String> categoryNames = catalogLookup.categoryNames(List.of(spu.getCategoryId()));
        Map<Long, String> brandNames = catalogLookup.brandNames(
                spu.getBrandId() == null ? List.of() : List.of(spu.getBrandId()));
        return toAuditView(spu, skusOf(spuId),
                categoryNames.get(spu.getCategoryId()),
                spu.getBrandId() == null ? null : brandNames.get(spu.getBrandId()));
    }

    /**
     * 审核一个商品：APPROVE / REJECT。
     *
     * <p><b>驳回必须填原因</b> —— 否则卖家不知道自己为什么被拒，审核结论也留不下可追溯的解释。
     *
     * <p><b>审核可重入</b>（重复通过 / 驳回覆盖上一次结论）：运营改判是真实场景，
     * 「已审过就不能再改」会把改判逼成直接改库。
     *
     * <p><b>驳回同时把 status 压成 OFF_SHELF</b>：公开可见性本来由 auditStatus 把守
     * （驳回即不可见），改 status 是为了让卖家视角的语义一致 ——
     * 被驳回的商品不该挂在「在售」筛选里，让卖家以为货还挂着卖。
     *
     * <p>通过时<b>不动 status</b>：卖家提交时期望的就是在售，
     * 审核通过即自动出现在前台（若它同时被卖家手动下架过，保持下架 —— 尊重卖家的选择）。
     */
    public AdminProductAuditView audit(Long spuId, String action, String reason) {
        LoginUser operator = UserContext.require();
        OperatorGuard.requireOperator();

        String act = action == null ? "" : action.trim().toUpperCase();
        String target = switch (act) {
            case "APPROVE" -> AUDIT_APPROVED;
            case "REJECT" -> AUDIT_REJECTED;
            default -> throw BizException.validation("action 仅支持 APPROVE / REJECT");
        };
        SpuEntity spu = spuMapper.selectById(spuId);
        if (spu == null) {
            throw BizException.notFound("商品");
        }
        if (!isMerchantSupplied(spu)) {
            // 自营商品免审，不存在「审核」这个动作；走到这里说明调用方按错了对象
            throw BizException.businessRule("平台自营商品无需审核");
        }
        if (AUDIT_REJECTED.equals(target) && trimToNull(reason) == null) {
            throw BizException.validation("驳回时必须填写原因");
        }

        spu.setAuditStatus(target);
        spu.setAuditReason(AUDIT_REJECTED.equals(target) ? trimToNull(reason) : null);
        if (AUDIT_REJECTED.equals(target) && STATUS_ON_SALE.equals(spu.getStatus())) {
            spu.setStatus(STATUS_OFF_SHELF);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        spu.setAuditAt(now);
        spu.setAuditBy(operator.userId());
        // E28：无论结论是通过还是驳回，来源都是「人工」—— 它回答的是
        // 「这条结论是怎么来的」，而不是「结论是什么」。
        spu.setAuditSource(AUDIT_SOURCE_MANUAL);
        spu.setUpdatedAt(now);
        spuMapper.updateById(spu);

        log.info("商品 {} 审核结论={}（审核人={} 原因={}）", spuId, target, operator.userId(),
                spu.getAuditReason());
        Map<Long, String> categoryNames = catalogLookup.categoryNames(List.of(spu.getCategoryId()));
        return toAuditView(spu, skusOf(spuId), categoryNames.get(spu.getCategoryId()), null);
    }

    // ---------------- 内部实现 ----------------

    /** 第三方供货（需要审核）；自营免审 */
    private static boolean isMerchantSupplied(SpuEntity spu) {
        return SUPPLY_MERCHANT.equals(spu.getSupplyType());
    }

    /**
     * E28 · 事后抽查判违规：把商品打回 REJECTED 并强制下架。
     *
     * <p>与人工审核 {@link #audit} 落的是同一批列，但<b>来源记 SAMPLE</b>。
     * 这个区分在复盘时是关键：人工驳回说明「送审时就该拦住」，
     * 抽查驳回说明「自动放行把它判错了」—— 后者才是要拿去调规则的信号。
     * 两者混记成一个值，就再也分不清「规则太松」与「商家太狡猾」。
     *
     * <p>不做 {@code OperatorGuard} 校验：调用方（抽查复核）是运营接口，已经校验过一次；
     * 这里再校验一次会让「系统自动判定」这条路径没有合法调用方。
     */
    public void rejectBySample(Long spuId, String reason) {
        SpuEntity spu = spuId == null ? null : spuMapper.selectById(spuId);
        if (spu == null || !isMerchantSupplied(spu)) {
            return;             // 自营商品不进抽查队列；查不到就什么都不做
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        // 显式 set 每一个要改的列（包括把 audit_source 写成 SAMPLE）：
        // updateById 不写 null，而这里恰好要覆盖旧值 —— 用 UpdateWrapper 一次说清。
        spuMapper.update(null, new LambdaUpdateWrapper<SpuEntity>()
                .eq(SpuEntity::getId, spuId)
                .set(SpuEntity::getAuditStatus, AUDIT_REJECTED)
                .set(SpuEntity::getAuditReason, trimToNull(reason))
                .set(SpuEntity::getAuditSource, AUDIT_SOURCE_SAMPLE)
                .set(SpuEntity::getAuditAt, now)
                .set(SpuEntity::getStatus, STATUS_OFF_SHELF)
                .set(SpuEntity::getUpdatedAt, now));
        log.info("商品 {} 抽查判违规 → 已驳回并强制下架（原因={}）", spuId, reason);
    }

    /**
     * 内容变更后的审核态重置（E14 的核心规则，E28 起改为**先做自动放行判定**）。
     *
     * <p>对第三方商品：重跑一遍风险判定 ——
     * <ul>
     *   <li>未命中任何风险规则 → 直接 {@code APPROVED} + 来源 AUTO，
     *       商品改完就还在前台卖（E28 的口径：人工审核不再是必经环节）；</li>
     *   <li>命中 → 回到 {@code PENDING} 并清空来源标记，等运营看；</li>
     *   <li><b>曾被判过违规（当前 {@code REJECTED}）→ 一律转人工</b>，
     *       不看内容是否干净：一件已经被判定不合格的商品，卖家改完仍需人确认。</li>
     * </ul>
     * 两条路都会**恢复在售**（{@code status → ON_SALE}）：驳回时 status 被压成 OFF_SHELF，
     * 且「先驳回、再自己上架」被 changeStatus 的门禁堵死，也就是说 REJECTED 件不可能停在
     * ON_SALE。若不在这里恢复，卖家改完并通过审核后仍是不可见状态，且没有任何入口能把它放出来。
     *
     * <p>对自营商品：保持 APPROVED（平台免审，不存在「重新审核」这回事），返回空命中列表。
     *
     * @return 命中的风险（空列表 = 自动放行）。调用方据此决定要不要把 audit_source 显式清空
     */
    private List<SpuRiskJudge.Hit> applyContentReaudit(SpuEntity spu) {
        if (!isMerchantSupplied(spu)) {
            return List.of();
        }
        // ★ 曾被判过违规的商品（人工驳回 / 事后抽查判违规）改完内容**仍然转人工**。
        //   不这么做的话，「随便改一个字」就能把 REJECTED 冲掉并重新自动上线 ——
        //   人工驳回与抽查判违规就都成了摆设，而这恰恰是最容易被绕过的那种绕过：
        //   接口不报错、状态看起来还更好了（待审 → 在售），没有断言盯着就发现不了。
        boolean wasRejected = AUDIT_REJECTED.equals(spu.getAuditStatus());
        AuditDecision decision = wasRejected
                ? new AuditDecision(false, List.of(new SpuRiskJudge.Hit(
                        "PREVIOUSLY_REJECTED", "该商品此前被驳回或判违规，修改后需人工确认")))
                : decideAudit(spu);
        spu.setStatus(STATUS_ON_SALE);
        spu.setAuditReason(null);
        if (decision.autoApprove()) {
            spu.setAuditStatus(AUDIT_APPROVED);
            spu.setAuditSource(AUDIT_SOURCE_AUTO);
            // 同 create()：自动放行要留下「这次结论发生的时间」，抽查才有得抽
            spu.setAuditAt(LocalDateTime.now(ZoneOffset.UTC));
        } else {
            // 回到「未审核」：audit_at 是「上一次结论的时间」，转人工后那段结论已作废，
            // 留着它还会让这条商品被当成「今天刚自动放行」抽进复核队列（就自相矛盾了）
            spu.setAuditStatus(AUDIT_PENDING);
            spu.setAuditSource(null);
            spu.setAuditAt(null);
        }
        return decision.risks();
    }

    // ---------------- E28 · 自动放行判定 ----------------

    /**
     * 自动放行判定的结果。
     *
     * @param autoApprove 是否可以直接放行（自营恒 true）
     * @param risks       命中的风险规则；{@code autoApprove} 为 true 时必为空
     */
    private record AuditDecision(boolean autoApprove, List<SpuRiskJudge.Hit> risks) {
    }

    /**
     * 判定一个商品能不能自动放行（E28）。
     *
     * <p>规则本身在 {@link SpuRiskJudge} 里（纯函数、可单测），这里只负责**取数**：
     * 类目名、同类在售均价、商家风险标记。三处取数都做了兜底 ——
     * <b>取不到就当作「不构成风险」</b>，理由写在各私有方法上。
     *
     * <p>为什么判定结果不是「拒绝」而是「转人工」：自动放行是一次**押注** ——
     * 押这件商品没问题。命中风险特征时，押注的期望收益变负，于是退回人工，
     * 而不是拒绝商家。把「需要多看一眼」做成「发不出去」，是对商家最粗暴的一种处理。
     */
    private AuditDecision decideAudit(SpuEntity spu) {
        if (!isMerchantSupplied(spu)) {
            return new AuditDecision(true, List.of());     // 自营恒免审
        }
        Long categoryId = spu.getCategoryId();
        List<SpuRiskJudge.Hit> hits = SpuRiskJudge.risks(new SpuRiskJudge.Context(
                categoryNameOf(categoryId),
                spu.getTitle(),
                spu.getSubtitle(),
                spu.getDetail(),
                nz(spu.getMinPrice()),
                categoryAvgMinPrice(categoryId),
                sellerFlagged(spu.getSellerId())));
        return new AuditDecision(hits.isEmpty(), hits);
    }

    /**
     * 类目名。取不到返回 null —— {@link SpuRiskJudge#hitRestrictedCategory} 对 null 返回 false，
     * 也就是「取不到类目名 = 不构成前置许可风险」。
     *
     * <p>为什么这个方向的兜底是对的：类目名只用来判「是不是前置许可类」，
     * 而取不到名（类目被删、缓存未热）是**瞬时**状态。把它当成高风险，
     * 会让一次缓存抖动把整批商品推进待审队列，而运营翻完发现全都没问题。
     */
    private String categoryNameOf(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        try {
            return catalogLookup.categoryNames(List.of(categoryId)).get(categoryId);
        } catch (Exception ex) {
            log.warn("类目名查询失败，本次不按前置许可类判定 categoryId={} : {}", categoryId, ex.getMessage());
            return null;
        }
    }

    /**
     * 同类在售商品的均价（分）。样本不足返回 null（不参与「价格异常」判定）。
     *
     * <p>只取最近 {@value #PRICE_BENCHMARK_SAMPLE} 件：这条规则是加分项而不是判据，
     * 不该让商品提交依赖一次全表聚合。取不到数据时判「不异常」是刻意的 ——
     * 若把「算不出均价」也算异常，新类目下的**第一批商品永远进不了自动放行**，
     * 而它们恰恰是最需要自动化的（新类目上架最密集）。
     */
    private Long categoryAvgMinPrice(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        try {
            List<SpuEntity> peers = spuMapper.selectList(new LambdaQueryWrapper<SpuEntity>()
                    .select(SpuEntity::getMinPrice)
                    .eq(SpuEntity::getCategoryId, categoryId)
                    .eq(SpuEntity::getSupplyType, SUPPLY_MERCHANT)
                    .eq(SpuEntity::getStatus, STATUS_ON_SALE)
                    .gt(SpuEntity::getMinPrice, 0)
                    .last("LIMIT " + PRICE_BENCHMARK_SAMPLE));
            if (peers == null || peers.isEmpty()) {
                return null;
            }
            long sum = peers.stream().mapToLong(p -> nz(p.getMinPrice())).sum();
            return sum / peers.size();
        } catch (Exception ex) {
            log.warn("同类均价查询失败，本次不按价格异常判定 categoryId={} : {}", categoryId, ex.getMessage());
            return null;
        }
    }

    /**
     * 商家是否命中「此前有驳回或停业记录」。
     *
     * <p><b>查不到 / 查不通一律按「无标记」</b>：这是纵深防御式的加分项 ——
     * 真正的防线是事后抽查与违规升级停业，它们不依赖用户域是否可用。
     * 若这里在 Feign 抖动时改成「查不到就转人工」，用户域一次重启就会让
     * 全平台的商品提交全部堆进待审队列 —— 一个可自愈的小问题换来一次审核积压。
     * 故障以 WARN 留在日志里。
     */
    private boolean sellerFlagged(Long sellerId) {
        if (sellerId == null || sellerId == 0L) {
            return false;               // 平台自营：没有店铺，也就没有店铺风险画像
        }
        try {
            MerchantRiskInfo risk = merchantRiskClient.of(sellerId).data();
            return risk != null && risk.flagged();
        } catch (Exception ex) {
            log.warn("商家风险画像查询失败，本次按「无标记」处理 sellerId={} : {}",
                    sellerId, ex.getMessage());
            return false;
        }
    }

    /** 上架被审核状态挡住时的提示语。要告诉卖家「去等审核」还是「去改内容」，不能只说「不行」。 */
    private String auditBlockReason(String auditStatus) {
        if (AUDIT_REJECTED.equals(auditStatus)) {
            return "该商品未通过审核，请修改内容后重新提交（或申请重新审核）";
        }
        return "该商品正在审核中，审核通过后才能上架";
    }

    /** 审核状态筛选值白名单：不认识的值直接 400，绝不静默当成「不筛选」 */
    private String normalizeAuditFilter(String auditStatus) {
        String value = trimToNull(auditStatus);
        if (value == null) {
            return null;
        }
        String normalized = value.toUpperCase();
        if (!Set.of(AUDIT_PENDING, AUDIT_APPROVED, AUDIT_REJECTED).contains(normalized)) {
            throw BizException.validation("auditStatus 仅支持 PENDING / APPROVED / REJECTED");
        }
        return normalized;
    }

    /** 目标状态白名单：不认识的动作直接 400，绝不静默当成下架 */
    private String normalizeAction(String action) {
        if (action == null) {
            throw BizException.validation("action 不能为空（ON_SALE / OFF_SHELF）");
        }
        String normalized = action.trim().toUpperCase();
        if (!STATUS_ON_SALE.equals(normalized) && !STATUS_OFF_SHELF.equals(normalized)) {
            throw BizException.validation("action 仅支持 ON_SALE / OFF_SHELF");
        }
        return normalized;
    }

    private void requireCategoryExists(Long categoryId) {
        if (categoryId == null) {
            throw BizException.validation("商品类目不能为空");
        }
        if (!catalogLookup.categories(List.of(categoryId)).containsKey(categoryId)) {
            throw BizException.validation("商品类目不存在");
        }
    }

    private void requireBrandExists(Long brandId) {
        if (brandId != null && !catalogLookup.brands(List.of(brandId)).containsKey(brandId)) {
            throw BizException.validation("品牌不存在");
        }
    }

    /** 规范化后的 SKU 写侧数据 */
    private record NormalizedSku(String specName, long price, int stock, String image) {
    }

    private List<NormalizedSku> normalizeSkus(List<SellerSkuInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw BizException.validation("商品至少需要一个规格（价格与库存填在规格上）");
        }
        if (inputs.size() > MAX_SKUS) {
            throw BizException.validation("最多支持 " + MAX_SKUS + " 个规格");
        }
        List<NormalizedSku> result = new ArrayList<>(inputs.size());
        Set<String> seen = new HashSet<>();
        for (SellerSkuInput input : inputs) {
            if (input == null) {
                throw BizException.validation("规格项不能为空");
            }
            long price = requirePrice(input.price());
            int stock = input.stock() == null ? 0 : input.stock();
            if (stock < 0) {
                throw BizException.validation("库存不能为负数");
            }
            if (stock > MAX_STOCK) {
                throw BizException.validation("库存不能超过 " + MAX_STOCK);
            }
            String spec = optionalText(input.specName(), "规格名", SPEC_NAME_MAX);
            String label = spec == null ? "默认" : spec;
            if (!seen.add(label)) {
                // 规格名重复在库里不会报错，但会让卖家在订单里分不清买的是哪个 —— 必须在入口拦
                throw BizException.validation("规格名重复：" + label);
            }
            result.add(new NormalizedSku(label, price, stock,
                    optionalText(input.image(), "规格图片", IMAGE_MAX)));
        }
        return result;
    }

    /**
     * 组装 SKU 实体。
     *
     * <p>{@code sku_code} 由服务端生成（{@code SPU{spuId}-{序号}}）：这张表上有唯一键，
     * 让卖家自己填编码会变成「两个人填了同一个编码 → 第二条插入失败」的客服问题，
     * 而这个编码对消费者完全不可见，没有让卖家参与的价值。
     *
     * <p>{@code spec_json} 写成 {@code {"规格": "..."}}：与既有商品展示口径一致
     * （{@code ProductService.specText} 渲染成「规格:2kg」）。
     */
    private SkuEntity toSkuEntity(long spuId, int seq, NormalizedSku sku, LocalDateTime now) {
        SkuEntity entity = new SkuEntity();
        entity.setId(idGenerator.nextId());
        entity.setSpuId(spuId);
        entity.setSkuCode("SPU" + spuId + "-" + seq);
        entity.setSpecJson(specJsonOf(sku.specName()));
        entity.setImage(sku.image());
        entity.setPrice(sku.price());
        entity.setMarketPrice(0L);
        entity.setStock(sku.stock());
        entity.setFrozenStock(0);
        entity.setIsFood(0);
        entity.setStatus("ACTIVE");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private String specJsonOf(String specName) {
        try {
            return objectMapper.writeValueAsString(Map.of("规格", specName));
        } catch (Exception ex) {
            // 单键 Map 的序列化不会失败；真失败也不能让商品建不出来 —— 退化成纯文本
            log.warn("规格 JSON 序列化失败，退化为纯文本：{}", specName, ex);
            return specName;
        }
    }

    /** 从 spec_json 里取回规格名（审核台展示用；解析不了就原样返回） */
    private String specNameOf(String specJson) {
        String raw = trimToNull(specJson);
        if (raw == null) {
            return null;
        }
        try {
            Map<String, String> spec = objectMapper.readValue(specJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {
                    });
            return spec.values().stream().findFirst().orElse(raw);
        } catch (Exception ignored) {
            return raw;
        }
    }

    private long requirePrice(Long price) {
        if (price == null) {
            throw BizException.validation("价格不能为空");
        }
        if (price <= 0) {
            throw BizException.validation("价格必须大于 0");
        }
        if (price > MAX_PRICE_CENTS) {
            throw BizException.validation("价格超出上限（最高 " + (MAX_PRICE_CENTS / 100) + " 元）");
        }
        return price;
    }

    /** 图片：去空、去重、限数量。空列表允许（详情图可以没有，主图必填另判） */
    private List<String> normalizeImages(List<String> images) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>();
        for (String image : images) {
            String value = trimToNull(image);
            if (value != null && !cleaned.contains(value)) {
                cleaned.add(value);
            }
        }
        if (cleaned.size() > MAX_IMAGES) {
            throw BizException.validation("最多上传 " + MAX_IMAGES + " 张图片");
        }
        return cleaned;
    }

    private String requireText(String value, String field, int maxLength) {
        String text = trimToNull(value);
        if (text == null) {
            throw BizException.validation(field + "不能为空");
        }
        if (text.length() > maxLength) {
            throw BizException.validation(field + "不能超过 " + maxLength + " 个字");
        }
        return text;
    }

    private String optionalText(String value, String field, int maxLength) {
        String text = trimToNull(value);
        if (text == null) {
            return null;
        }
        if (text.length() > maxLength) {
            throw BizException.validation(field + "不能超过 " + maxLength + " 个字");
        }
        return text;
    }

    private List<SkuEntity> skusOf(Long spuId) {
        return skuMapper.selectList(new LambdaQueryWrapper<SkuEntity>()
                .eq(SkuEntity::getSpuId, spuId)
                .orderByAsc(SkuEntity::getId));
    }

    /** 按 SPU 分组取 SKU（审核列表用，避免 N+1） */
    private Map<Long, List<SkuEntity>> skusBySpuIds(Collection<Long> spuIds) {
        Set<Long> distinct = distinctIds(spuIds);
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return skuMapper.selectList(new LambdaQueryWrapper<SkuEntity>()
                        .in(SkuEntity::getSpuId, distinct)
                        .orderByAsc(SkuEntity::getId)).stream()
                .collect(Collectors.groupingBy(SkuEntity::getSpuId));
    }

    /**
     * 按 SPU 聚合 SKU 数量与库存总和（一次查询，避免列表 N+1）。
     * 数组下标 0 = SKU 数量，1 = 库存总和（物理库存 stock，不含冻结口径）。
     */
    private Map<Long, int[]> skuStatsBySpuIds(Collection<Long> spuIds) {
        Set<Long> distinct = distinctIds(spuIds);
        if (distinct.isEmpty()) {
            return Map.of();
        }
        List<SkuEntity> skus = skuMapper.selectList(new LambdaQueryWrapper<SkuEntity>()
                .in(SkuEntity::getSpuId, distinct));
        return skus.stream().collect(Collectors.groupingBy(
                SkuEntity::getSpuId,
                Collectors.reducing(new int[]{0, 0},
                        sku -> new int[]{1, sku.getStock() == null ? 0 : sku.getStock()},
                        (a, b) -> new int[]{a[0] + b[0], a[1] + b[1]})));
    }

    private int[] skuStatsOf(Long spuId) {
        return skuStatsBySpuIds(List.of(spuId)).getOrDefault(spuId, new int[]{0, 0});
    }

    private static Set<Long> distinctIds(Collection<Long> ids) {
        if (ids == null) {
            return Set.of();
        }
        return ids.stream().filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private SellerProductView toView(SpuEntity spu, int[] stats) {
        return new SellerProductView(
                spu.getId(),
                spu.getTitle(),
                spu.getMainImage(),
                spu.getStatus(),
                statusLabelOf(spu.getStatus()),
                spu.getAuditStatus(),
                auditLabelOf(spu.getAuditStatus()),
                spu.getAuditReason(),
                nz(spu.getSellerId()),
                nz(spu.getMinPrice()),
                nz(spu.getMaxPrice()),
                spu.getSalesCount() == null ? 0 : spu.getSalesCount(),
                stats[0],
                stats[1]);
    }

    private SellerProductDetailView toDetailView(SpuEntity spu, List<SkuEntity> skus) {
        return new SellerProductDetailView(
                spu.getId(),
                spu.getTitle(),
                spu.getSubtitle(),
                spu.getCategoryId(),
                spu.getBrandId(),
                spu.getMainImage(),
                Csv.split(spu.getImages()),
                spu.getDetail(),
                spu.getSupplyType(),
                spu.getStatus(),
                statusLabelOf(spu.getStatus()),
                spu.getAuditStatus(),
                auditLabelOf(spu.getAuditStatus()),
                spu.getAuditReason(),
                isoOf(spu.getAuditAt()),
                nz(spu.getSellerId()),
                nz(spu.getMinPrice()),
                nz(spu.getMaxPrice()),
                skus.stream().map(this::toSkuView).toList());
    }

    private AdminProductAuditView toAuditView(SpuEntity spu, List<SkuEntity> skus,
                                              String categoryName, String brandName) {
        return new AdminProductAuditView(
                spu.getId(),
                spu.getSellerId(),
                spu.getTitle(),
                spu.getSubtitle(),
                spu.getMainImage(),
                Csv.split(spu.getImages()),
                spu.getDetail(),
                spu.getCategoryId(),
                categoryName,
                spu.getBrandId(),
                brandName,
                spu.getSupplyType(),
                spu.getStatus(),
                spu.getAuditStatus(),
                auditLabelOf(spu.getAuditStatus()),
                spu.getAuditReason(),
                isoOf(spu.getAuditAt()),
                nz(spu.getMinPrice()),
                nz(spu.getMaxPrice()),
                skus.stream().map(this::toSkuView).toList(),
                isoOf(spu.getCreatedAt()));
    }

    private SellerSkuView toSkuView(SkuEntity sku) {
        return new SellerSkuView(
                sku.getId(),
                specNameOf(sku.getSpecJson()),
                nz(sku.getPrice()),
                sku.getStock() == null ? 0 : sku.getStock(),
                sku.getImage(),
                sku.getStatus());
    }

    /** 状态文案由服务端给出，前端不维护映射表 */
    private String statusLabelOf(String status) {
        return switch (status == null ? "" : status) {
            case STATUS_ON_SALE -> "在售";
            case STATUS_OFF_SHELF -> "已下架";
            default -> status;
        };
    }

    private String auditLabelOf(String auditStatus) {
        return switch (auditStatus == null ? "" : auditStatus) {
            case AUDIT_PENDING -> "待审核";
            case AUDIT_APPROVED -> "已通过";
            case AUDIT_REJECTED -> "已驳回";
            default -> auditStatus;
        };
    }

    private static String isoOf(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC).toString();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }
}
