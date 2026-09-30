package com.duoduo.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.product.dto.ProductDtos.ListingCard;
import com.duoduo.product.dto.ProductDtos.ListingDetail;
import com.duoduo.product.dto.ProductDtos.ListingMineView;
import com.duoduo.product.dto.ProductDtos.ListingPublishRequest;
import com.duoduo.product.dto.ProductDtos.ListingAuditView;
import com.duoduo.product.dto.ProductDtos.InternalListingView;
import com.duoduo.product.dto.ProductDtos.ListingOwnerView;
import com.duoduo.product.dto.ProductDtos.ListingQuery;
import com.duoduo.product.entity.ListingEntity;
import com.duoduo.product.mapper.ListingMapper;
import com.duoduo.product.support.BannedWordGuard;
import com.duoduo.product.support.Csv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * C2C 闲置（浏览读链路 + 卖家发布写链路）。
 *
 * <p><b>可见性规则（重要）</b>：对外只暴露 {@code status=ON_SALE 且 auditStatus=APPROVED} 的闲置。
 * 未审核通过的内容一律不可见 —— 包括详情页直接按 id 访问，
 * 否则「先发布、再用 id 把链接发出去」就绕过了审核。
 * 这里的做法是返回 404 而不是 403，避免通过状态码探测出「这条 id 存在但未通过审核」。
 *
 * <p>写链路（发布 / 上下架 / 我的发布）走 {@code /api/listings} 下的受保护路径：
 * 发布者是<b>普通买家</b>（C2C，不是商家），因此不能挂在要求卖家角色的 {@code /api/seller/**} 下；
 * 但写路径必须落在网关白名单之外 —— 白名单按路径匹配、<b>不区分方法</b>，
 * 一旦把写端点放进免登路径就是「匿名 UGC 直写入库」。
 * 具体路径划分见 {@link ListingController} 的类注释。
 */
@Service
public class ListingService {

    private static final String STATUS_ON_SALE = "ON_SALE";
    private static final String STATUS_OFF_SHELF = "OFF_SHELF";
    private static final String STATUS_SOLD = "SOLD";
    private static final String AUDIT_APPROVED = "APPROVED";
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final Set<String> ALLOWED_SORTS =
            Set.of("default", "price_asc", "price_desc", "newest");
    private static final Set<String> ALLOWED_CONDITIONS =
            Set.of("NEW", "LIKE_NEW", "GOOD", "FAIR");

    /** 单条闲置最多图片数：与 images 列 2000 字符的容量匹配（一张 URL 约 50~80 字符） */
    private static final int MAX_IMAGES = 9;
    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_DESCRIPTION_LENGTH = 2000;
    private static final long MAX_PRICE_CENTS = 9_999_900L;

    private final ListingMapper listingMapper;
    private final ProductService productService;
    private final BannedWordGuard bannedWordGuard;
    private final SnowflakeIdGenerator idGenerator;
    private final int maxItemsPerSeller;
    private final org.springframework.beans.factory.ObjectProvider<
            com.duoduo.product.support.search.EsSearchEngine> esSearchProvider;
    private static final Logger log = LoggerFactory.getLogger(ListingService.class);

    public ListingService(ListingMapper listingMapper,
                          ProductService productService,
                          BannedWordGuard bannedWordGuard,
                          SnowflakeIdGenerator idGenerator,
                          @Value("${duoduo.product.listing.max-per-seller:200}") int maxItemsPerSeller,
                          org.springframework.beans.factory.ObjectProvider<
                                  com.duoduo.product.support.search.EsSearchEngine> esSearchProvider) {
        this.listingMapper = listingMapper;
        this.productService = productService;
        this.bannedWordGuard = bannedWordGuard;
        this.idGenerator = idGenerator;
        this.maxItemsPerSeller = maxItemsPerSeller;
        this.esSearchProvider = esSearchProvider;
    }

    public PageResult<ListingCard> search(ListingQuery query) {
        int pageNo = Math.max(1, query.page());
        int pageSize = query.pageSize() <= 0 ? DEFAULT_PAGE_SIZE : Math.min(query.pageSize(), MAX_PAGE_SIZE);

        if (query.minPrice() != null && query.maxPrice() != null && query.minPrice() > query.maxPrice()) {
            throw BizException.validation("最低价不能高于最高价");
        }
        bannedWordGuard.rejectIfHit(query.keyword(), "搜索词");

        // A10 · ES 优先，任何 ES 异常原地降级回 MySQL（与 ProductService.search 同一降级纪律）
        var esSearch = esSearchProvider.getIfAvailable();
        if (esSearch != null && StringUtils.hasText(query.keyword())) {
            try {
                var hits = esSearch.searchListings(query, pageNo, pageSize);
                List<ListingEntity> records = hits.ids().isEmpty()
                        ? List.of()
                        : orderByIds(listingMapper.selectBatchIds(hits.ids()), hits.ids());
                return PageResult.of(toCards(records), hits.total(), pageNo, pageSize);
            } catch (Exception ex) {
                log.warn("ES 闲置检索失败，降级 MySQL（设计内行为）: {}", ex.getMessage());
            }
        }

        LambdaQueryWrapper<ListingEntity> wrapper = visibleWrapper();

        String keyword = trimToNull(query.keyword());
        if (keyword != null) {
            wrapper.and(w -> w.like(ListingEntity::getTitle, keyword)
                    .or().like(ListingEntity::getDescription, keyword));
        }
        String city = trimToNull(query.city());
        if (city != null) {
            wrapper.eq(ListingEntity::getCity, city);
        }
        String species = trimToNull(query.species());
        if (species != null) {
            wrapper.eq(ListingEntity::getSpecies, species);
        }
        String condition = trimToNull(query.conditionLevel());
        if (condition != null) {
            if (!Set.of("NEW", "LIKE_NEW", "GOOD", "FAIR").contains(condition)) {
                throw BizException.validation("conditionLevel 仅支持 NEW / LIKE_NEW / GOOD / FAIR");
            }
            wrapper.eq(ListingEntity::getConditionLevel, condition);
        }
        if (query.minPrice() != null) {
            wrapper.ge(ListingEntity::getPrice, query.minPrice());
        }
        if (query.maxPrice() != null) {
            wrapper.le(ListingEntity::getPrice, query.maxPrice());
        }
        applySort(wrapper, query.sort());
        // 稳定分页：同价同时间的闲置必须有确定顺序，否则翻页会出现重复或漏项
        wrapper.orderByDesc(ListingEntity::getId);

        Page<ListingEntity> page = listingMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        return PageResult.of(toCards(page.getRecords()), page.getTotal(), pageNo, pageSize);
    }

    /**
     * 闲置公开详情（免登可读）。
     *
     * <p><b>浏览量（{@code view_count}）在此自增</b>（第五十三轮热修）：
     * 口径是「<b>页面每被打开或刷新一次即 +1，同一用户多次查看可累加</b>」——
     * 所以它是「浏览次数」而不是「看过的人次去重」。想要「按人去重的意向人数」走
     * {@link #markWant(Long, Long)}（{@code want_count}），两者不可互相顶替。
     * 这个方法是「对外可见详情」的唯一出口 ——
     * 未过审 / 已下架 / 已售的条目在上面已 404 抛错，不会走到这里，因此计数天然不脏；
     * 卖家视角详情走 {@link #ownerDetail(Long)}（另一个方法），自己的后台查看不计数。
     * 详情接口被别处复用会虚增计数，故调用方只允许 {@code GET /api/listings/{id}}。
     *
     * <p>自增后把返回值同步为「新值」，让本次响应就是最新计数（前端不必再拉一次列表）。
     */
    public ListingDetail detail(Long id) {
        ListingEntity listing = listingMapper.selectById(id);
        if (listing == null
                || !STATUS_ON_SALE.equals(listing.getStatus())
                || !AUDIT_APPROVED.equals(listing.getAuditStatus())
                || listing.getDeletedAt() != null) {
            // 已删除按「不存在」处理：删掉的东西就该查不到，
            // 返回「已删除」反而会把「曾经有过什么」泄露给无关的人。
            throw BizException.notFound("闲置商品");
        }

        // 原子自增 + 回填本次响应值；DB 写失败不应阻断浏览（计数是附属信息），故只记日志
        try {
            listingMapper.incrementViewCount(id);
            listing.setViewCount((listing.getViewCount() == null ? 0 : listing.getViewCount()) + 1);
        } catch (Exception e) {
            log.warn("浏览量自增失败 listingId={}", id, e);
        }

        List<String> images = new ArrayList<>(Csv.split(listing.getImages()));
        if (images.isEmpty() && listing.getImages() == null) {
            images = List.of();
        }

        // 同款全新：C2C 闲置最大的转化入口就是「加一点钱买全新的」
        return new ListingDetail(
                toCard(listing),
                listing.getDescription(),
                images,
                productService.byCategory(listing.getCategoryId(), null, 4));
    }

    /**
     * 「想要」上报（第五十四轮）：社交域在买家**首次**向该闲置卖家发出咨询消息时调用。
     *
     * <p>与 {@link #detail(Long)} 的浏览量是<b>两个口径</b>，不要混：
     * <ul>
     *   <li>{@code view_count}（浏览次数）：页面每被打开或刷新一次 +1，<b>同一人可累加</b>；</li>
     *   <li>{@code want_count}（想要人数）：按<b>咨询行为</b>统计，<b>同一买家对同一闲置只计 1 次</b>。</li>
     * </ul>
     * 混用的后果很具体：拿浏览量当「想要」，卖家会把「路过看一眼」当成购买意向，据此备货或让价都会失真。
     *
     * <p>三个刻意的设计：
     * <ol>
     *   <li><b>去重交给数据库唯一键</b>（{@link ListingMapper#insertWantIgnore}）：只有真的插进去
     *       （返回 1）才给 {@code want_count} 加一。同一买家反复咨询、反复点「我想要」都不会重复计数 ——
     *       应用层「先查后写」在并发下会双写，这里不给它留口子。</li>
     *   <li><b>重复上报返回 false 而不是报错</b>：调用方是「发消息」主链路，
     *       统计去重这种附属逻辑不该让用户的消息发不出去。</li>
     *   <li><b>先校验闲置存在</b>：去重表没有外键，不校验就会攒下指向幽灵闲置的孤儿行，
     *       而这类脏数据事后极难排查（统计口径会永久偏大且看不出原因）。</li>
     * </ol>
     *
     * @return {@code true} = 本次是新增（计数 +1）；{@code false} = 该买家已在册（未计数）
     */
    public boolean markWant(Long listingId, Long buyerId) {
        if (listingId == null || buyerId == null) {
            throw BizException.validation("listingId 与 buyerId 均不能为空");
        }
        Long exists = listingMapper.selectCount(
                new LambdaQueryWrapper<ListingEntity>()
                        .eq(ListingEntity::getId, listingId)
                        // 已软删的闲置不该再攒「想要」：条目对买家已不可见，计数却还在涨，
                        // 卖家会在「我的发布」里看到一个与实际供给不符的意向数。
                        .isNull(ListingEntity::getDeletedAt));
        if (exists == null || exists == 0) {
            throw BizException.notFound("闲置商品");
        }
        int inserted = listingMapper.insertWantIgnore(listingId, buyerId, LocalDateTime.now(ZoneOffset.UTC));
        if (inserted <= 0) {
            return false;
        }
        listingMapper.incrementWantCount(listingId);
        log.info("闲置 {} 新增一位想要买家 {}（首次咨询）", listingId, buyerId);
        return true;
    }

    /** 首页「新鲜闲置」用：最近审核通过的若干条 */
    public List<ListingCard> latestApproved(int limit) {
        int size = Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
        LambdaQueryWrapper<ListingEntity> wrapper = visibleWrapper()
                .orderByDesc(ListingEntity::getCreatedAt)
                .orderByDesc(ListingEntity::getId);
        Page<ListingEntity> page = new Page<>(1, size, false);
        return toCards(listingMapper.selectPage(page, wrapper).getRecords());
    }

    /**
     * 公开可见的闲置（第五十八轮补软删条件）。
     *
     * <p>{@code deleted_at IS NULL} 必须写在这里 —— 它是「唯一一处定义公开可见性」的地方。
     * 若分散到各调用处，迟早会漏掉一条路径，于是用户能在某个列表里点开一条已删除的闲置，
     * 看到的却是空内容（这类缺陷没有任何报错，只能靠用户投诉发现）。
     */
    private LambdaQueryWrapper<ListingEntity> visibleWrapper() {
        return new LambdaQueryWrapper<ListingEntity>()
                .eq(ListingEntity::getStatus, STATUS_ON_SALE)
                .eq(ListingEntity::getAuditStatus, AUDIT_APPROVED)
                .isNull(ListingEntity::getDeletedAt);
    }

    // ---------------- 写：发布 / 上下架 / 我的发布（卖家侧） ----------------

    /**
     * 发布一条闲置。
     *
     * <p>四条关键约束（每条都对应一个真实可被绕过的口子）：
     * <ol>
     *   <li><b>必须实名</b>：C2C 发布是真实供给行为，出问题时平台要能追到人。
     *       未实名 → 403「该操作需要先完成实名认证」。</li>
     *   <li><b>卖家身份取自令牌</b>，绝不从请求体读 —— 否则任何人都能替别人发布。</li>
     *   <li><b>审核态由服务端定</b>：一律落 {@code PENDING}。若接受客户端传入
     *       {@code auditStatus=APPROVED}，人工审核链当场失效。</li>
     *   <li><b>违禁词在写库前拦</b>：标题 + 描述一起过 {@link BannedWordGuard}，
     *       而不是等运营审核时才看 —— 违规内容连库都不该进。</li>
     * </ol>
     *
     * <p>发布成功后返回的是<b>卖家视角</b>条目（带 PENDING 状态），
     * 不是公开卡片：此刻它在公开列表里还查不到，回一个「公开视角」会让卖家以为发布失败了。
     */
    public ListingMineView publish(ListingPublishRequest request) {
        LoginUser user = UserContext.require();
        requireRealName(user);
        NormalizedListing normalized = normalizeForWrite(request);

        Long existing = listingMapper.selectCount(new LambdaQueryWrapper<ListingEntity>()
                .eq(ListingEntity::getSellerId, user.userId()));
        if (existing != null && existing >= maxItemsPerSeller) {
            throw BizException.businessRule(
                    "单个账号最多发布 " + maxItemsPerSeller + " 件闲置，请先下架部分商品");
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ListingEntity entity = new ListingEntity();
        entity.setId(idGenerator.nextId());
        entity.setSellerId(user.userId());
        applyNormalized(entity, normalized);
        // 以下四项服务端说了算，不接受客户端传值
        entity.setStatus(STATUS_ON_SALE);
        entity.setAuditStatus(AUDIT_PENDING);
        entity.setViewCount(0);
        entity.setWantCount(0);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        listingMapper.insert(entity);

        log.info("用户 {} 发布闲置 {}「{}」（价格 {} 分），已进入待审核",
                user.userId(), entity.getId(), normalized.title(), normalized.price());
        return toMineView(entity);
    }

    /**
     * 重新提审（第五十三轮）：被驳回的闲置修改内容后再次进入审核。
     *
     * <p>状态机口径（与 {@link #audit} 配套）：
     * <ul>
     *   <li><b>只有 REJECTED 能重提</b> —— PENDING 是「正在审」，重复提交只会打乱审核队列；
     *       APPROVED 是「已通过」，内容已公开，想改东西应该下架后发新的一条。</li>
     *   <li><b>重提 = 内容更新 + 回到发布态</b>：auditStatus 置回 PENDING、清空驳回原因、
     *       status 回 ON_SALE（驳回时被 audit() 落到 OFF_SHELF）——
     *       语义上等同重新发布了一条，审核链从头走。</li>
     *   <li><b>实名与违禁词全部复检</b>：重提是新的供给行为，与首次发布同一套闸门，
     *       不因为「曾经通过实名」就免检。</li>
     *   <li><b>归属校验与 404/403 区分</b>：照抄上下架的范式（{@link #changeStatus}）。</li>
     * </ul>
     */
    public ListingMineView resubmit(Long id, ListingPublishRequest request) {
        LoginUser user = UserContext.require();
        requireRealName(user);
        NormalizedListing normalized = normalizeForWrite(request);

        ListingEntity listing = requireOwned(id, user);
        if (STATUS_SOLD.equals(listing.getStatus())) {
            throw BizException.businessRule("该闲置已售出，无法重新提审");
        }
        if (!AUDIT_REJECTED.equals(listing.getAuditStatus())) {
            throw BizException.businessRule(resubmitBlockReason(listing.getAuditStatus()));
        }

        applyNormalized(listing, normalized);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        listing.setStatus(STATUS_ON_SALE);
        listing.setAuditStatus(AUDIT_PENDING);
        listing.setAuditReason(null);
        listing.setUpdatedAt(now);
        listingMapper.updateById(listing);

        log.info("用户 {} 重新提审闲置 {}「{}」，回到待审核", user.userId(), id, normalized.title());
        return toMineView(listing);
    }

    // ---------------- 写：编辑 / 删除（第五十八轮 · B-14） ----------------

    /**
     * 编辑自己的闲置（内容变更 → 重新送审）。
     *
     * <h2>规则（v1 口径，写在代码里以免各端各理解一套）
     * <ol>
     *   <li><b>只能改内容</b>：标题 / 描述 / 价格 / 成色 / 图片 / 城市 / 品类，
     *       卖家身份、审核结论、计数一律服务端说了算；</li>
     *   <li><b>改完必须重新审核</b>（{@code auditStatus → PENDING}）：审核结论是针对
     *       「当时那一段内容」作出的，内容变了结论就失效。放它继续挂着 APPROVED，
     *       等于让卖家可以「先过审、再改成违规内容」；</li>
     *   <li><b>重新审核期间不对外可见</b>：公开列表与详情都要求 APPROVED，
     *       所以回到 PENDING 后自然消失，不需要额外下线动作；</li>
     *   <li><b>已售出的不能编辑</b>：交易已完成，改描述会动摇交易凭证；</li>
     *   <li><b>驳回态请走 {@link #resubmit}</b>：那条路径语义更清楚（重提 = 修改 + 再送审），
     *       这里也放行但同样落 PENDING，两条路的终点一致。</li>
     * </ol>
     */
    public ListingMineView edit(Long id, ListingPublishRequest request) {
        LoginUser user = UserContext.require();
        requireRealName(user);
        NormalizedListing normalized = normalizeForWrite(request);

        ListingEntity listing = requireOwned(id, user);
        if (STATUS_SOLD.equals(listing.getStatus())) {
            throw BizException.businessRule("该闲置已售出，无法再编辑");
        }

        applyNormalized(listing, normalized);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        // 注意顺序：先取旧审核态再重置 —— 写成 setAuditStatus(PENDING) 之后再判 REJECTED，
        // 判断永远为假，恢复在售就成了永不执行的死代码（第五十八轮自查抓到）。
        boolean wasRejected = AUDIT_REJECTED.equals(listing.getAuditStatus());
        listing.setAuditStatus(AUDIT_PENDING);
        listing.setAuditReason(null);
        // 驳回件编辑后要跟着恢复在售：驳回时 status 已被压成 OFF_SHELF（见 audit），
        // 而「先驳回、再自己上架」这条路被 changeStatus 的「上架需已过审」堵死 ——
        // 也就是说 REJECTED 件不可能停在 ON_SALE。若不在这里恢复，卖家改完通过审核
        // 仍是不可见状态，且没有任何入口能把它放出来。
        if (wasRejected) {
            listing.setStatus(STATUS_ON_SALE);
        }
        listing.setUpdatedAt(now);
        listingMapper.updateById(listing);

        log.info("用户 {} 编辑闲置 {}「{}」，内容变更 → 回到待审核", user.userId(), id, normalized.title());
        return toMineView(listing);
    }

    /**
     * 删除自己的闲置（**软删**）。
     *
     * <p>为什么软删而不是物理删：闲置成交后会与订单、会话、举报形成引用关系，
     * 物理删会让别人的订单页与聊天记录出现「查无此物」；审核与举报留痕也需要
     * 「这条内容曾经存在」。删除只写 {@code deleted_at}，行留在库里。
     *
     * <p>已售出的一律不允许删（交易凭证不能抹）。这是 v1 的唯一限制，
     * 将来若要做「删除后仍可审计的回收站」，应当在这个方法里扩展而不是放宽条件。
     */
    public void remove(Long id) {
        LoginUser user = UserContext.require();
        ListingEntity listing = requireOwned(id, user);
        if (STATUS_SOLD.equals(listing.getStatus())) {
            throw BizException.businessRule("该闲置已售出，不能删除（交易凭证需要留存）");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        listing.setDeletedAt(now);
        listing.setUpdatedAt(now);
        // 删除即下线：不从公开列表以外的地方再冒出来（status 保持原值，审计可追溯原状态）
        listing.setStatus(STATUS_OFF_SHELF);
        listingMapper.updateById(listing);
        log.info("用户 {} 删除（软删）闲置 {}", user.userId(), id);
    }

    /**
     * 我的发布列表。
     *
     * <p>与公开列表的核心差别：<b>带出全部状态</b>（含 PENDING / REJECTED / OFF_SHELF / SOLD）。
     * 卖家必须看得到自己没通过审核的条目，否则发布完就「查无此项」，卖家只能反复重发。
     * 删选在此处不做 —— 这些是卖家自己的数据，不存在越权面。
     *
     * <p>两个过滤维度（第五十三轮）：<b>status</b>（在售 / 下架 / 已售，交易维度）与
     * <b>auditStatus</b>（待审 / 通过 / 驳回，审核维度）正交组合。此前被驳回的条目
     * 挂在 ON_SALE 名下（发布即 ON_SALE）却不在公开列表出现，卖家在「在售」里看到
     * 一条实际不可见的闲置会一头雾水 —— 审核维度过滤就是给「审核中 / 审核失败」
     * 分类标签用的。
     */
    public PageResult<ListingMineView> listMine(String status, String auditStatus, int page, int pageSize) {
        LoginUser user = UserContext.require();
        int pageNo = Math.max(1, page);
        int size = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);

        LambdaQueryWrapper<ListingEntity> wrapper = new LambdaQueryWrapper<ListingEntity>()
                .eq(ListingEntity::getSellerId, user.userId())
                // 已删除的不出现在「我的发布」：v1 不提供恢复入口，让它继续可编辑会造成
                // 「改了也看不见」的困惑。软删行仍留在库里供审计与举报追溯。
                .isNull(ListingEntity::getDeletedAt);
        String filter = trimToNull(status);
        if (filter != null) {
            String normalized = filter.toUpperCase();
            if (!Set.of(STATUS_ON_SALE, STATUS_OFF_SHELF, STATUS_SOLD).contains(normalized)) {
                throw BizException.validation("status 仅支持 ON_SALE / OFF_SHELF / SOLD");
            }
            wrapper.eq(ListingEntity::getStatus, normalized);
        }
        String auditFilter = trimToNull(auditStatus);
        if (auditFilter != null) {
            String normalized = auditFilter.toUpperCase();
            if (!Set.of(AUDIT_PENDING, AUDIT_APPROVED, AUDIT_REJECTED).contains(normalized)) {
                throw BizException.validation("auditStatus 仅支持 PENDING / APPROVED / REJECTED");
            }
            wrapper.eq(ListingEntity::getAuditStatus, normalized);
        }
        // 待审核的排前面：卖家最需要处理的就是「还没通过」的那些
        wrapper.orderByDesc(ListingEntity::getCreatedAt).orderByDesc(ListingEntity::getId);

        Page<ListingEntity> result = listingMapper.selectPage(new Page<>(pageNo, size), wrapper);
        List<ListingMineView> views = result.getRecords().stream().map(this::toMineView).toList();
        return PageResult.of(views, result.getTotal(), pageNo, size);
    }

    /**
     * 卖家视角详情（第五十三轮）：编辑重提的回填数据源。
     *
     * <p>公开详情对未过审内容 404 是<b>对的</b>（防状态码探测），但卖家自己必须能
     * 读回自己被驳回的内容才能修改重提 —— 归属校验在这里补上，路径本身在白名单之外。
     */
    public ListingOwnerView ownerDetail(Long id) {
        LoginUser user = UserContext.require();
        return toOwnerView(requireOwned(id, user));
    }

    /**
     * 归属校验统一收口（第五十八轮补软删判断）。
     *
     * <p>404（不存在 / 已删除）与 403（不是你的）刻意分开，是两种不同的用户处境。
     * 已删除的条目对卖家也按「不存在」处理 —— 删除是不可逆动作（v1 不提供恢复入口），
     * 让它在「我的发布」里继续可编辑会造成「改了也看不见」的困惑。
     */
    private ListingEntity requireOwned(Long id, LoginUser user) {
        ListingEntity listing = listingMapper.selectById(id);
        if (listing == null || listing.getDeletedAt() != null) {
            throw BizException.notFound("闲置商品");
        }
        if (!user.userId().equals(listing.getSellerId())) {
            throw BizException.forbidden("该闲置不属于当前账号");
        }
        return listing;
    }

    /**
     * 上下架一条闲置。
     *
     * <p>准入与归属检查照抄商家端 {@code SellerProductService#changeStatus} 的范式：
     * 归属看数据表字段（不看客户端传的 sellerId）、404 与 403 刻意区分、
     * 不认识的动作一律 400（绝不静默当成下架）。
     *
     * <p>两条额外规则（C2C 闲置特有）：
     * <ul>
     *   <li><b>上架要求已过审</b>：{@code ON_SALE} 目标态必须 {@code auditStatus=APPROVED}，
     *       否则「先发布成 PENDING、再自己上架」就绕过了审核。
     *       下架则不限 —— 卖家主动撤下自己的内容永远是权利，不需要先过审。</li>
     *   <li><b>SOLD 不可自行复活</b>：已卖出的闲置不该被卖家改回在售，
     *       否则同一件东西会被卖第二次。</li>
     * </ul>
     *
     * <p>幂等开关：目标态与当前态相同时<b>不写库</b>（也不报错），
     * 前端重复点按不会产生无意义的 updated_at 抖动。
     */
    public ListingMineView changeStatus(Long id, String action) {
        LoginUser user = UserContext.require();
        String target = normalizeAction(action);

        ListingEntity listing = requireOwned(id, user);
        if (STATUS_SOLD.equals(listing.getStatus())) {
            throw BizException.businessRule("该闲置已售出，无法再上下架");
        }
        if (STATUS_ON_SALE.equals(target) && !AUDIT_APPROVED.equals(listing.getAuditStatus())) {
            throw BizException.businessRule(auditBlockReason(listing.getAuditStatus()));
        }

        if (!target.equals(listing.getStatus())) {
            String from = listing.getStatus();
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            listing.setStatus(target);
            listing.setUpdatedAt(now);
            listingMapper.updateById(listing);
            log.info("闲置 {} 由卖家 {} 上下架：{} -> {}", id, user.userId(), from, target);
        }
        return toMineView(listing);
    }

    /** 上架被审核状态挡住时的提示语。要告诉卖家「去等审核」还是「去改内容」，不能只说「不行」。 */
    private String auditBlockReason(String auditStatus) {
        if (AUDIT_REJECTED.equals(auditStatus)) {
            return "该闲置已被驳回，请修改内容后重新发布";
        }
        return "该闲置正在审核中，审核通过后才能上架";
    }

    private String normalizeAction(String action) {
        if (trimToNull(action) == null) {
            throw BizException.validation("action 不能为空（ON_SALE / OFF_SHELF）");
        }
        String normalized = action.trim().toUpperCase();
        if (!STATUS_ON_SALE.equals(normalized) && !STATUS_OFF_SHELF.equals(normalized)) {
            throw BizException.validation("action 仅支持 ON_SALE / OFF_SHELF");
        }
        return normalized;
    }

    /**
     * 发布 / 重提共用的写闸门：实名 + 内容规范化 + 违禁词复检。
     *
     * <p>抽成一段的原因不是「复用几行代码」，而是<b>两条链路必须同强度</b>：
     * 重提是新的供给行为，若只复用一半校验，「发布时拦违禁词、重提时不拦」
     * 就是白送一个绕过通道。
     */
    private void requireRealName(LoginUser user) {
        if (!user.realNameVerified()) {
            throw BizException.forbidden("该操作需要先完成实名认证");
        }
    }

    /** 规范化后的写侧内容（发布与重提共用）；字段语义见 {@link #publish} */
    private record NormalizedListing(
            String title,
            String description,
            Long categoryId,
            long price,
            long originalPrice,
            String conditionLevel,
            String city,
            List<String> images,
            String species,
            String breed
    ) {
    }

    private NormalizedListing normalizeForWrite(ListingPublishRequest request) {
        if (request == null) {
            throw BizException.validation("请求体不能为空");
        }
        String title = requireText(request.title(), "标题", MAX_TITLE_LENGTH);
        String description = optionalText(request.description(), "描述", MAX_DESCRIPTION_LENGTH);
        String city = optionalText(request.city(), "城市", 50);
        String species = optionalText(request.species(), "物种", 20);
        String breed = optionalText(request.breed(), "品种", 50);

        long price = requirePrice(request.price(), "价格");
        long originalPrice = request.originalPrice() == null ? 0L : request.originalPrice();
        if (originalPrice < 0) {
            throw BizException.validation("原价不能为负数");
        }

        String condition = normalizeCondition(request.conditionLevel());
        List<String> images = normalizeImages(request.images());

        // 违禁词：标题与描述合并送检（命中即抛，连库都不进）
        bannedWordGuard.rejectIfHit(title, "闲置标题");
        if (description != null) {
            bannedWordGuard.rejectIfHit(description, "闲置描述");
        }
        return new NormalizedListing(title, description, request.categoryId(), price,
                originalPrice, condition, city, images, species, breed);
    }

    /** 把规范化内容落到实体（发布 = 填空实体；重提 = 覆盖旧内容，categoryId 表单不含则保持原值） */
    private void applyNormalized(ListingEntity entity, NormalizedListing n) {
        entity.setTitle(n.title());
        entity.setDescription(n.description());
        if (n.categoryId() != null) {
            entity.setCategoryId(n.categoryId());
        }
        entity.setPrice(n.price());
        entity.setOriginalPrice(n.originalPrice());
        entity.setConditionLevel(n.conditionLevel());
        entity.setCity(n.city());
        entity.setImages(n.images().isEmpty() ? null : String.join(",", n.images()));
        entity.setSpecies(n.species());
        entity.setBreed(n.breed());
    }

    /** 重提被当前审核状态挡住时的提示语：告诉卖家该等还是不用等，不能只说「不行」 */
    private String resubmitBlockReason(String auditStatus) {
        if (AUDIT_PENDING.equals(auditStatus)) {
            return "该闲置正在审核中，请等待审核结果";
        }
        if (AUDIT_APPROVED.equals(auditStatus)) {
            return "该闲置已通过审核，无需重新提交";
        }
        return "该闲置当前状态不支持重新提审";
    }

    private String normalizeCondition(String condition) {
        String value = trimToNull(condition);
        if (value == null) {
            return "GOOD";
        }
        String normalized = value.toUpperCase();
        if (!ALLOWED_CONDITIONS.contains(normalized)) {
            throw BizException.validation("conditionLevel 仅支持 NEW / LIKE_NEW / GOOD / FAIR");
        }
        return normalized;
    }

    /** 图片：去空、去重、限数量。空列表允许（无图闲置可以发，展示端有占位） */
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
            throw BizException.validation(field + "长度不能超过 " + maxLength + " 个字符");
        }
        return text;
    }

    private String optionalText(String value, String field, int maxLength) {
        String text = trimToNull(value);
        if (text == null) {
            return null;
        }
        if (text.length() > maxLength) {
            throw BizException.validation(field + "长度不能超过 " + maxLength + " 个字符");
        }
        return text;
    }

    /** 价格校验：必填、正数、上限（与 t_listing.price NOT NULL 对齐，不用 0 元占位） */
    private long requirePrice(Long price, String field) {
        if (price == null) {
            throw BizException.validation(field + "不能为空");
        }
        if (price <= 0) {
            throw BizException.validation(field + "必须大于 0");
        }
        if (price > MAX_PRICE_CENTS) {
            throw BizException.validation(field + "超出上限（最高 " + (MAX_PRICE_CENTS / 100) + " 元）");
        }
        return price;
    }

    private ListingMineView toMineView(ListingEntity listing) {
        return new ListingMineView(
                listing.getId(),
                listing.getTitle(),
                listing.getPrice() == null ? 0L : listing.getPrice(),
                listing.getConditionLevel(),
                listing.getCity(),
                Csv.split(listing.getImages()).stream().findFirst().orElse(null),
                listing.getSpecies(),
                listing.getStatus(),
                statusLabelOf(listing.getStatus()),
                listing.getAuditStatus(),
                listing.getAuditReason(),
                listing.getViewCount() == null ? 0 : listing.getViewCount(),
                listing.getWantCount() == null ? 0 : listing.getWantCount(),
                listing.getCreatedAt() == null ? null
                        : listing.getCreatedAt().toInstant(ZoneOffset.UTC).toString());
    }

    /** 卖家视角详情（编辑回填用）：MineView 的全部字段 + 描述 / 全部图片 / 类目 */
    private ListingOwnerView toOwnerView(ListingEntity listing) {
        return new ListingOwnerView(
                listing.getId(),
                listing.getTitle(),
                listing.getDescription(),
                listing.getCategoryId(),
                listing.getPrice() == null ? 0L : listing.getPrice(),
                listing.getOriginalPrice() == null ? 0L : listing.getOriginalPrice(),
                listing.getConditionLevel(),
                listing.getCity(),
                Csv.split(listing.getImages()),
                listing.getSpecies(),
                listing.getBreed(),
                listing.getStatus(),
                statusLabelOf(listing.getStatus()),
                listing.getAuditStatus(),
                listing.getAuditReason(),
                listing.getViewCount() == null ? 0 : listing.getViewCount(),
                listing.getWantCount() == null ? 0 : listing.getWantCount(),
                listing.getCreatedAt() == null ? null
                        : listing.getCreatedAt().toInstant(ZoneOffset.UTC).toString());
    }

    private static String statusLabelOf(String status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case STATUS_ON_SALE -> "在售";
            case STATUS_OFF_SHELF -> "已下架";
            case STATUS_SOLD -> "已售出";
            default -> status;
        };
    }

    /** A10：按 ES 返回的 id 顺序还原记录顺序 */
    private List<ListingEntity> orderByIds(List<ListingEntity> listings, List<Long> orderedIds) {
        java.util.Map<Long, ListingEntity> byId = listings.stream()
                .collect(java.util.stream.Collectors.toMap(ListingEntity::getId, l -> l, (a, b) -> a));
        return orderedIds.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    private void applySort(LambdaQueryWrapper<ListingEntity> wrapper, String sort) {
        String value = (sort == null || sort.isBlank()) ? "default" : sort.trim();
        if (!ALLOWED_SORTS.contains(value)) {
            throw BizException.validation("不支持的排序方式：" + value
                    + "，可选 " + String.join("/", ALLOWED_SORTS));
        }
        switch (value) {
            case "price_asc" -> wrapper.orderByAsc(ListingEntity::getPrice);
            case "price_desc" -> wrapper.orderByDesc(ListingEntity::getPrice);
            case "newest" -> wrapper.orderByDesc(ListingEntity::getCreatedAt);
            default -> wrapper.orderByDesc(ListingEntity::getCreatedAt);
        }
    }

    private List<ListingCard> toCards(List<ListingEntity> listings) {
        if (listings == null || listings.isEmpty()) {
            return List.of();
        }
        return listings.stream().map(this::toCard).toList();
    }

    private ListingCard toCard(ListingEntity listing) {
        String cover = Csv.split(listing.getImages()).stream().findFirst().orElse(null);
        return new ListingCard(
                listing.getId(),
                listing.getTitle(),
                listing.getPrice(),
                listing.getOriginalPrice() == null ? 0L : listing.getOriginalPrice(),
                listing.getConditionLevel(),
                listing.getCity(),
                cover,
                listing.getSpecies(),
                listing.getBreed(),
                listing.getViewCount() == null ? 0 : listing.getViewCount(),
                listing.getWantCount() == null ? 0 : listing.getWantCount(),
                listing.getSellerId());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ---------------- 服务间读（social 域「问一问」LISTING 消息用） ----------------

    /**
     * 批量取闲置卡事实（第五十三轮）：社交域发 LISTING 消息时服务端定收件人 +
     * 组装卡片展示字段。幽灵 id 静默跳过 —— 调用方对缺失卡片有降级（卡片区空白），
     * 不值得为一个过期 id 让整个请求 404。
     */
    public List<InternalListingView> internalCards(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return listingMapper.selectBatchIds(ids).stream()
                // 已删除的闲置不给卡片：聊天里的引用卡片会指向一条打不开的详情。
                // 静默跳过（不报错）—— 调用方对缺失卡片有降级处理。
                .filter(l -> l.getDeletedAt() == null)
                .map(l -> new InternalListingView(
                        l.getId(),
                        l.getTitle(),
                        Csv.split(l.getImages()).stream().findFirst().orElse(null),
                        l.getPrice(),
                        l.getSellerId()))
                .toList();
    }

    // ---------------- 运营审核（M10） ----------------

    private static final String AUDIT_PENDING = "PENDING";
    private static final String AUDIT_REJECTED = "REJECTED";

    /** 运营侧审核列表：按审核状态过滤，含未审核通过的内容（公开接口里这些是不可见的） */
    public PageResult<ListingAuditView> listForAudit(String auditStatus, int page, int pageSize) {
        OperatorGuard.requireOperator();
        int pageNo = Math.max(1, page);
        int size = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        // 已软删的不进审核队列：通过它也不可见（读路径一律排除 deleted_at），
        // 让运营白审一遍是纯粹的无效动作。
        LambdaQueryWrapper<ListingEntity> wrapper = new LambdaQueryWrapper<ListingEntity>()
                .isNull(ListingEntity::getDeletedAt);
        if (auditStatus != null && !auditStatus.isBlank()) {
            wrapper.eq(ListingEntity::getAuditStatus, auditStatus.trim().toUpperCase());
        }
        wrapper.orderByAsc(ListingEntity::getAuditStatus)
                .orderByDesc(ListingEntity::getCreatedAt)
                .orderByDesc(ListingEntity::getId);
        Page<ListingEntity> result = listingMapper.selectPage(new Page<>(pageNo, size), wrapper);
        List<ListingAuditView> views = result.getRecords().stream().map(this::toAuditView).toList();
        return PageResult.of(views, result.getTotal(), pageNo, size);
    }

    /**
     * 审核一条闲置：APPROVE / REJECT。
     *
     * <p>驳回必须填原因 —— 否则卖家不知道自己为什么被拒，审核结论也留不下可追溯的解释。
     * 审核是可重入的（重复通过 / 驳回覆盖上一次结论），方便运营改判。
     */
    public ListingAuditView audit(Long id, String action, String reason) {
        OperatorGuard.requireOperator();
        // 对外契约是动词（APPROVE / REJECT），库内落状态（APPROVED / REJECTED）
        String act = action == null ? "" : action.trim().toUpperCase();
        String target = switch (act) {
            case "APPROVE" -> AUDIT_APPROVED;
            case "REJECT" -> AUDIT_REJECTED;
            default -> throw BizException.validation("action 仅支持 APPROVE / REJECT");
        };
        ListingEntity listing = listingMapper.selectById(id);
        if (listing == null || listing.getDeletedAt() != null) {
            throw BizException.notFound("闲置商品");
        }
        if (AUDIT_REJECTED.equals(target) && trimToNull(reason) == null) {
            throw BizException.validation("驳回时必须填写原因");
        }
        listing.setAuditStatus(target);
        listing.setAuditReason(AUDIT_REJECTED.equals(target) ? trimToNull(reason) : null);
        if (AUDIT_REJECTED.equals(target) && STATUS_ON_SALE.equals(listing.getStatus())) {
            // 驳回即不可售（第五十三轮）：status 同步落 OFF_SHELF。
            // 公开可见性由 auditStatus=APPROVED 把守（驳回本来就不可见），
            // 这里改 status 是让卖家视角语义一致 —— 被驳回的条目不该出现在「在售」筛选里，
            // 让卖家误以为东西还挂着卖。
            listing.setStatus(STATUS_OFF_SHELF);
        }
        listing.setAuditAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        listing.setUpdatedAt(listing.getAuditAt());
        listingMapper.updateById(listing);
        return toAuditView(listing);
    }

    private ListingAuditView toAuditView(ListingEntity listing) {
        return new ListingAuditView(
                listing.getId(),
                listing.getSellerId(),
                listing.getTitle(),
                listing.getPrice() == null ? 0L : listing.getPrice(),
                listing.getConditionLevel(),
                listing.getCity(),
                listing.getSpecies(),
                listing.getAuditStatus(),
                listing.getAuditReason(),
                listing.getCreatedAt() == null ? null
                        : listing.getCreatedAt().toInstant(java.time.ZoneOffset.UTC).toString());
    }
}
