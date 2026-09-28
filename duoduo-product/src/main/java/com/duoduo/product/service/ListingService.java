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

    public ListingDetail detail(Long id) {
        ListingEntity listing = listingMapper.selectById(id);
        if (listing == null
                || !STATUS_ON_SALE.equals(listing.getStatus())
                || !AUDIT_APPROVED.equals(listing.getAuditStatus())) {
            throw BizException.notFound("闲置商品");
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

    /** 首页「新鲜闲置」用：最近审核通过的若干条 */
    public List<ListingCard> latestApproved(int limit) {
        int size = Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
        LambdaQueryWrapper<ListingEntity> wrapper = visibleWrapper()
                .orderByDesc(ListingEntity::getCreatedAt)
                .orderByDesc(ListingEntity::getId);
        Page<ListingEntity> page = new Page<>(1, size, false);
        return toCards(listingMapper.selectPage(page, wrapper).getRecords());
    }

    private LambdaQueryWrapper<ListingEntity> visibleWrapper() {
        return new LambdaQueryWrapper<ListingEntity>()
                .eq(ListingEntity::getStatus, STATUS_ON_SALE)
                .eq(ListingEntity::getAuditStatus, AUDIT_APPROVED);
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
        if (!user.realNameVerified()) {
            throw BizException.forbidden("该操作需要先完成实名认证");
        }
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
        entity.setTitle(title);
        entity.setDescription(description);
        entity.setCategoryId(request.categoryId());
        entity.setPrice(price);
        entity.setOriginalPrice(originalPrice);
        entity.setConditionLevel(condition);
        entity.setCity(city);
        entity.setImages(images.isEmpty() ? null : String.join(",", images));
        entity.setSpecies(species);
        entity.setBreed(breed);
        // 以下四项服务端说了算，不接受客户端传值
        entity.setStatus(STATUS_ON_SALE);
        entity.setAuditStatus(AUDIT_PENDING);
        entity.setViewCount(0);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        listingMapper.insert(entity);

        log.info("用户 {} 发布闲置 {}「{}」（价格 {} 分），已进入待审核",
                user.userId(), entity.getId(), title, price);
        return toMineView(entity);
    }

    /**
     * 我的发布列表。
     *
     * <p>与公开列表的核心差别：<b>带出全部状态</b>（含 PENDING / REJECTED / OFF_SHELF / SOLD）。
     * 卖家必须看得到自己没通过审核的条目，否则发布完就「查无此项」。
     * 删选在此处不做 —— 这些是卖家自己的数据，不存在越权面。
     */
    public PageResult<ListingMineView> listMine(String status, int page, int pageSize) {
        LoginUser user = UserContext.require();
        int pageNo = Math.max(1, page);
        int size = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);

        LambdaQueryWrapper<ListingEntity> wrapper = new LambdaQueryWrapper<ListingEntity>()
                .eq(ListingEntity::getSellerId, user.userId());
        String filter = trimToNull(status);
        if (filter != null) {
            String normalized = filter.toUpperCase();
            if (!Set.of(STATUS_ON_SALE, STATUS_OFF_SHELF, STATUS_SOLD).contains(normalized)) {
                throw BizException.validation("status 仅支持 ON_SALE / OFF_SHELF / SOLD");
            }
            wrapper.eq(ListingEntity::getStatus, normalized);
        }
        // 待审核的排前面：卖家最需要处理的就是「还没通过」的那些
        wrapper.orderByDesc(ListingEntity::getCreatedAt).orderByDesc(ListingEntity::getId);

        Page<ListingEntity> result = listingMapper.selectPage(new Page<>(pageNo, size), wrapper);
        List<ListingMineView> views = result.getRecords().stream().map(this::toMineView).toList();
        return PageResult.of(views, result.getTotal(), pageNo, size);
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

        ListingEntity listing = listingMapper.selectById(id);
        if (listing == null) {
            throw BizException.notFound("闲置商品");
        }
        // 404 与 403 刻意分开：id 不存在 vs 存在但不是你的，是两种不同的用户处境
        if (!user.userId().equals(listing.getSellerId())) {
            throw BizException.forbidden("该闲置不属于当前账号");
        }
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
                listing.getSellerId());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ---------------- 运营审核（M10） ----------------

    private static final String AUDIT_PENDING = "PENDING";
    private static final String AUDIT_REJECTED = "REJECTED";

    /** 运营侧审核列表：按审核状态过滤，含未审核通过的内容（公开接口里这些是不可见的） */
    public PageResult<ListingAuditView> listForAudit(String auditStatus, int page, int pageSize) {
        OperatorGuard.requireOperator();
        int pageNo = Math.max(1, page);
        int size = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        LambdaQueryWrapper<ListingEntity> wrapper = new LambdaQueryWrapper<ListingEntity>();
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
        if (listing == null) {
            throw BizException.notFound("闲置商品");
        }
        if (AUDIT_REJECTED.equals(target) && trimToNull(reason) == null) {
            throw BizException.validation("驳回时必须填写原因");
        }
        listing.setAuditStatus(target);
        listing.setAuditReason(AUDIT_REJECTED.equals(target) ? trimToNull(reason) : null);
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
