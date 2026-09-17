package com.duoduo.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.common.exception.BizException;
import com.duoduo.product.dto.ProductDtos.ListingCard;
import com.duoduo.product.dto.ProductDtos.ListingDetail;
import com.duoduo.product.dto.ProductDtos.ListingAuditView;
import com.duoduo.product.dto.ProductDtos.ListingQuery;
import com.duoduo.product.entity.ListingEntity;
import com.duoduo.product.mapper.ListingMapper;
import com.duoduo.product.support.BannedWordGuard;
import com.duoduo.product.support.Csv;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * C2C 闲置浏览。
 *
 * <p><b>可见性规则（重要）</b>：对外只暴露 {@code status=ON_SALE 且 auditStatus=APPROVED} 的闲置。
 * 未审核通过的内容一律不可见 —— 包括详情页直接按 id 访问，
 * 否则「先发布、再用 id 把链接发出去」就绕过了审核。
 * 这里的做法是返回 404 而不是 403，避免通过状态码探测出「这条 id 存在但未通过审核」。
 *
 * <p>发布/下架/编辑属卖家侧写链路，需要实名校验与内容审核，
 * 将在下一阶段随「卖家中心」一并实现（网关已预留 /api/seller/** 路由）。
 */
@Service
public class ListingService {

    private static final String STATUS_ON_SALE = "ON_SALE";
    private static final String AUDIT_APPROVED = "APPROVED";
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final Set<String> ALLOWED_SORTS =
            Set.of("default", "price_asc", "price_desc", "newest");

    private final ListingMapper listingMapper;
    private final ProductService productService;
    private final BannedWordGuard bannedWordGuard;

    public ListingService(ListingMapper listingMapper,
                          ProductService productService,
                          BannedWordGuard bannedWordGuard) {
        this.listingMapper = listingMapper;
        this.productService = productService;
        this.bannedWordGuard = bannedWordGuard;
    }

    public PageResult<ListingCard> search(ListingQuery query) {
        int pageNo = Math.max(1, query.page());
        int pageSize = query.pageSize() <= 0 ? DEFAULT_PAGE_SIZE : Math.min(query.pageSize(), MAX_PAGE_SIZE);

        if (query.minPrice() != null && query.maxPrice() != null && query.minPrice() > query.maxPrice()) {
            throw BizException.validation("最低价不能高于最高价");
        }
        bannedWordGuard.rejectIfHit(query.keyword(), "搜索词");

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
