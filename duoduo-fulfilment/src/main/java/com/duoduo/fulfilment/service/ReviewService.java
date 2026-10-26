package com.duoduo.fulfilment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.audit.AuditTrail;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.fulfilment.client.UserClient;
import com.duoduo.fulfilment.client.dto.OrderFact;
import com.duoduo.fulfilment.client.dto.UserBrief;
import com.duoduo.fulfilment.dto.ReviewDtos.AdminReviewView;
import com.duoduo.fulfilment.dto.ReviewDtos.ItemReview;
import com.duoduo.fulfilment.dto.ReviewDtos.ReviewAuditRequest;
import com.duoduo.fulfilment.dto.ReviewDtos.ReviewCreateRequest;
import com.duoduo.fulfilment.dto.ReviewDtos.ReviewCreateResult;
import com.duoduo.fulfilment.dto.ReviewDtos.ReviewSummary;
import com.duoduo.fulfilment.dto.ReviewDtos.ReviewView;
import com.duoduo.fulfilment.entity.ReviewEntity;
import com.duoduo.fulfilment.mapper.ReviewMapper;
import com.duoduo.fulfilment.support.ImageUrls;
import com.duoduo.fulfilment.support.OrderFacts;
import com.duoduo.fulfilment.support.OrderRefs;
import com.duoduo.fulfilment.support.ProductRatingSyncer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 评价服务。
 *
 * <p>规则集中在这里，控制器只做参数绑定：
 * <ol>
 *   <li><b>只有本人已完成的订单能评价</b>：订单事实从交易域取（内部凭据），
 *       归属与状态全部以事实为准，请求里没有任何 userId 供伪造；</li>
 *   <li><b>评的是订单里的商品</b>：{@code spuId} 必须出现在该订单明细中，
 *       否则就是「拿 A 单给别人家的 B 商品刷好评」；</li>
 *   <li><b>一单一商品一评</b>：唯一索引兜底，重复提交返回 409（不是 500）；</li>
 *   <li><b>追评一次</b>，且在窗口期内。</li>
 * </ol>
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    /** 只有已完成的订单可评价：货没到手就评价，等于把刷单的口子开在明面上 */
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String AUDIT_PENDING = "PENDING";
    private static final String AUDIT_APPROVED = "APPROVED";
    private static final String AUDIT_REJECTED = "REJECTED";
    private static final String ANONYMOUS_NICKNAME = "匿名用户";
    private static final String ACTION_APPROVE = "APPROVE";
    private static final String ACTION_REJECT = "REJECT";
    /** 后台搜索关键词上限：这是「一屏评价」的检索，不是全表导出 */
    private static final int MAX_KEYWORD_LENGTH = 100;

    private static final int MIN_RATING = 1;
    private static final int MAX_RATING = 5;
    /** 好评判定线：4 星及以上（口径写在这里，前后端不各写一份） */
    private static final int GOOD_RATING = 4;

    private static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_PAGE_SIZE = 10;

    private final ReviewMapper reviewMapper;
    private final OrderFacts orderFacts;
    private final UserClient userClient;
    private final SnowflakeIdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final int maxContentLength;
    private final int maxImages;
    private final int appendWindowDays;

    public ReviewService(ReviewMapper reviewMapper,
                         OrderFacts orderFacts,
                         UserClient userClient,
                         SnowflakeIdGenerator idGenerator,
                         ObjectMapper objectMapper,
                         ApplicationEventPublisher eventPublisher,
                         @Value("${duoduo.review.max-content-length:1000}") int maxContentLength,
                         @Value("${duoduo.review.max-images:9}") int maxImages,
                         @Value("${duoduo.review.append-window-days:30}") int appendWindowDays) {
        this.reviewMapper = reviewMapper;
        this.orderFacts = orderFacts;
        this.userClient = userClient;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.maxContentLength = maxContentLength;
        this.maxImages = maxImages;
        this.appendWindowDays = appendWindowDays;
    }

    // ---------------- 写：提交评价 ----------------

    /**
     * 提交评价（可一次提交订单里的多件商品）。
     *
     * <p>整批在一个事务里：买家点一次「提交」，要么都成，要么都不成 ——
     * 部分成功会让前端无法给出确定提示（「3 件里成功了 2 件，第 3 件为什么失败」）。
     * 但**重复评价**是例外：批量补评时其中一件已评过，不该拖垮其它几件，
     * 因此遇到唯一键冲突时**跳过该件**并如实回报，由前端提示「已跳过 N 件」。
     */
    @Transactional
    public ReviewCreateResult create(Long userId, ReviewCreateRequest request) {
        if (userId == null) {
            throw BizException.unauthorized("请先登录后再评价");
        }
        OrderFact fact = orderFacts.require(request.orderNo());
        requireOwnOrder(fact, userId);
        requireCompleted(fact);

        Long orderId = OrderRefs.orderIdOf(fact.orderNo());
        Map<Long, OrderFact.Item> itemsBySpu = indexItemsBySpu(fact);
        Set<Long> alreadyReviewed = Set.copyOf(reviewMapper.reviewedSpuIds(orderId, userId));

        List<ReviewEntity> toInsert = new ArrayList<>();
        int skipped = 0;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        boolean anonymous = Boolean.TRUE.equals(request.anonymous());

        for (ItemReview item : request.items()) {
            Long spuId = item.spuId();
            if (spuId == null) {
                throw BizException.validation("缺少 spuId，无法确定评价对象");
            }
            OrderFact.Item orderItem = itemsBySpu.get(spuId);
            if (orderItem == null) {
                // 语义上是**业务规则**不满足（该订单里没有这件商品），不是参数格式错 ——
                // 因此用 422 而不是 400：客户端错误要分得清「字段写错」与「规则不允许」，
                // 前者是调用方 bug，后者是正常业务分支，前端提示与告警策略都不同
                throw BizException.businessRule("商品 " + spuId + " 不在该订单中，无法评价");
            }
            if (alreadyReviewed.contains(spuId)) {
                skipped++;
                log.info("订单 {} 的商品 {} 已评价过，跳过（用户 {}）", fact.orderNo(), spuId, userId);
                continue;
            }
            validateRating(item.rating());
            String content = normalizeContent(item.content());
            List<String> images = ImageUrls.validate(item.images(), maxImages);

            ReviewEntity entity = new ReviewEntity();
            entity.setId(idGenerator.nextId());
            entity.setOrderId(orderId);
            entity.setOrderNo(fact.orderNo());
            entity.setUserId(userId);
            entity.setSellerId(fact.sellerId() == null ? 0L : fact.sellerId());
            entity.setSpuId(spuId);
            entity.setSkuId(item.skuId() != null ? item.skuId() : orderItem.skuId());
            entity.setProductTitle(orderItem.title());
            entity.setProductImage(orderItem.mainImage());
            entity.setRating(item.rating());
            entity.setContent(content);
            entity.setImages(ImageUrls.toJson(objectMapper, images));
            entity.setAnonymous(anonymous ? 1 : 0);
            entity.setAuditStatus(AUDIT_APPROVED);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            toInsert.add(entity);
        }

        if (toInsert.isEmpty()) {
            throw BizException.conflict("该订单的商品都已评价过");
        }
        try {
            toInsert.forEach(reviewMapper::insert);
        } catch (DuplicateKeyException e) {
            // 并发下唯一索引兜底：两个人同时点提交，后到的这一条会撞键。
            // 这里如实转成 409 而不是 500 —— 撞键是**预期内的业务结果**，不是服务故障。
            throw BizException.conflict("该商品已评价过，请勿重复提交");
        }

        // 评分回写（第七十三轮）：AFTER_COMMIT 触发 —— 事务内发布、提交后才执行，
        // 保证商品域拿到的聚合一定是「已提交」的评价；同步失败只告警，不拖垮主流程
        eventPublisher.publishEvent(new ProductRatingSyncer.ReviewCreated(
                toInsert.stream().map(ReviewEntity::getSpuId).toList()));

        // 昵称统一在本批插入后补一次，避免逐条查用户服务
        Map<Long, UserBrief> briefs = loadBriefs(List.of(userId));
        List<ReviewView> enriched = toInsert.stream()
                .map(e -> toView(e, briefs, now))
                .toList();
        log.info("用户 {} 对订单 {} 提交了 {} 条评价，跳过 {} 件", userId, fact.orderNo(), enriched.size(), skipped);
        return new ReviewCreateResult(enriched.size(), enriched);
    }

    /**
     * 追评：一次机会，且在窗口期内。
     *
     * <p>为什么限制窗口：追评的意义是「用过一段时间后再补一句」。
     * 不限期会让评价区出现相隔几年的追加内容挂在同一条评价下，
     * 读者无法判断这些信息是否还有效。
     */
    @Transactional
    public ReviewView append(Long userId, Long reviewId, String content) {
        if (userId == null) {
            throw BizException.unauthorized("请先登录后再追评");
        }
        ReviewEntity entity = reviewMapper.selectById(reviewId);
        if (entity == null) {
            throw BizException.notFound("评价");
        }
        if (!userId.equals(entity.getUserId())) {
            // 越权与不存在刻意区分：评价 id 是雪花值不可枚举，这里明说「不是你的」更有助于排查
            throw BizException.forbidden("只能追评自己的评价");
        }
        String text = normalizeContent(content);
        if (text == null) {
            throw BizException.validation("追评内容不能为空");
        }
        if (entity.getAppendContent() != null && !entity.getAppendContent().isBlank()) {
            throw BizException.conflict("每条评价只能追评一次");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (appendWindowDays > 0 && entity.getCreatedAt() != null
                && entity.getCreatedAt().plusDays(appendWindowDays).isBefore(now)) {
            throw BizException.businessRule("已超过追评期限（" + appendWindowDays + " 天）");
        }
        entity.setAppendContent(text);
        entity.setAppendAt(now);
        entity.setUpdatedAt(now);
        reviewMapper.updateById(entity);
        Map<Long, UserBrief> briefs = loadBriefs(List.of(entity.getUserId()));
        return toView(entity, briefs, now);
    }

    // ---------------- 读 ----------------

    /**
     * 商品评价分页（公开）。
     *
     * <p>只返回审核通过的：评价区是公开内容，未过审内容出现在这里等于把审核位架空。
     */
    public PageResult<ReviewView> listByProduct(Long spuId, Integer rating, Boolean hasImage,
                                                int page, int pageSize) {
        int pageNo = Math.max(1, page);
        int size = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);

        LambdaQueryWrapper<ReviewEntity> wrapper = new LambdaQueryWrapper<ReviewEntity>()
                .eq(ReviewEntity::getSpuId, spuId)
                .eq(ReviewEntity::getAuditStatus, AUDIT_APPROVED);
        if (rating != null) {
            validateRating(rating);
            wrapper.eq(ReviewEntity::getRating, rating);
        }
        if (Boolean.TRUE.equals(hasImage)) {
            wrapper.isNotNull(ReviewEntity::getImages).ne(ReviewEntity::getImages, "");
        }
        wrapper.orderByDesc(ReviewEntity::getCreatedAt).orderByDesc(ReviewEntity::getId);

        Page<ReviewEntity> result = reviewMapper.selectPage(new Page<>(pageNo, size), wrapper);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Map<Long, UserBrief> briefs = loadBriefs(result.getRecords().stream()
                .map(ReviewEntity::getUserId).distinct().toList());
        List<ReviewView> views = result.getRecords().stream()
                .map(e -> toView(e, briefs, now))
                .toList();
        return PageResult.of(views, result.getTotal(), pageNo, size);
    }

    /** 商品评分汇总（公开）：平均分、星级分布、好评率、带图数、追评数 */
    public ReviewSummary summary(Long spuId) {
        Map<String, Object> agg = reviewMapper.aggregateBySpu(spuId);
        long total = asLong(agg, "total");
        long good = asLong(agg, "goodCount");
        long imageCount = asLong(agg, "imageCount");
        long appendCount = asLong(agg, "appendCount");

        List<Long> starCounts = new ArrayList<>(List.of(0L, 0L, 0L, 0L, 0L));
        for (Map<String, Object> row : reviewMapper.starDistribution(spuId)) {
            int star = (int) asLong(row, "rating");
            if (star >= MIN_RATING && star <= MAX_RATING) {
                starCounts.set(star - 1, asLong(row, "cnt"));
            }
        }
        BigDecimal avg = total == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(asDouble(agg, "avgRating")).setScale(1, RoundingMode.HALF_UP);
        String goodRate = total == 0
                ? "0%"
                : BigDecimal.valueOf(good * 100.0 / total).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros()
                        .toPlainString() + "%";
        return new ReviewSummary(spuId, total, avg.toPlainString(), goodRate, starCounts, imageCount, appendCount);
    }

    /** 我的评价（分页） */
    public PageResult<ReviewView> listMine(Long userId, int page, int pageSize) {
        if (userId == null) {
            throw BizException.unauthorized("请先登录");
        }
        int pageNo = Math.max(1, page);
        int size = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        Page<ReviewEntity> result = reviewMapper.selectPage(new Page<>(pageNo, size),
                new LambdaQueryWrapper<ReviewEntity>()
                        .eq(ReviewEntity::getUserId, userId)
                        .orderByDesc(ReviewEntity::getCreatedAt)
                        .orderByDesc(ReviewEntity::getId));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Map<Long, UserBrief> briefs = loadBriefs(List.of(userId));
        List<ReviewView> views = result.getRecords().stream()
                .map(e -> toView(e, briefs, now))
                .toList();
        return PageResult.of(views, result.getTotal(), pageNo, size);
    }

    /** 某订单下我的评价（用于详情页展示「你已评价」与补评入口） */
    public List<ReviewView> myReviewsOfOrder(Long userId, String orderNo) {
        if (userId == null) {
            throw BizException.unauthorized("请先登录");
        }
        List<ReviewEntity> entities = reviewMapper.selectList(new LambdaQueryWrapper<ReviewEntity>()
                .eq(ReviewEntity::getUserId, userId)
                .eq(ReviewEntity::getOrderNo, orderNo)
                .orderByAsc(ReviewEntity::getId));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Map<Long, UserBrief> briefs = loadBriefs(List.of(userId));
        return entities.stream().map(e -> toView(e, briefs, now)).toList();
    }

    /**
     * 我在这些订单里已评价过的订单号（订单列表页一次判断，避免逐单请求）。
     *
     * <p>订单号数量设上限：这是「一页订单」的场景，无上限的话就成了
     * 用逗号拼几百个单号来压库的入口。
     */
    public List<String> reviewedOrderNos(Long userId, List<String> orderNos) {
        if (userId == null) {
            throw BizException.unauthorized("请先登录");
        }
        if (orderNos == null || orderNos.isEmpty()) {
            return List.of();
        }
        List<String> distinct = orderNos.stream()
                .filter(no -> no != null && !no.isBlank())
                .distinct()
                .limit(100)
                .toList();
        if (distinct.isEmpty()) {
            return List.of();
        }
        return reviewMapper.reviewedOrderNos(userId, distinct);
    }

    // ---------------- 运营后台：评价管理（H-02） ----------------

    /**
     * 全平台评价列表（运营）。
     *
     * <p>与公开列表的关键区别：这里<b>不</b>过滤 {@code audit_status=APPROVED} ——
     * 待审 / 已下架的评价恰恰是运营要处理的队列；筛选由调用方显式指定。
     *
     * @param auditStatus PENDING / APPROVED / REJECTED；空 = 全部
     * @param rating      1-5；不传 = 全部星级
     * @param keyword     评价正文 / 追评 / 商品标题 / 订单号（包含匹配）
     */
    public PageResult<AdminReviewView> listAllForAdmin(String auditStatus, Integer rating, String keyword,
                                                       int page, int pageSize) {
        OperatorGuard.requireOperator();
        String status = normalizeAuditStatus(auditStatus);
        if (rating != null) {
            validateRating(rating);
        }
        String kw = trimToNull(keyword);
        if (kw != null && kw.length() > MAX_KEYWORD_LENGTH) {
            throw BizException.validation("搜索关键词不能超过 " + MAX_KEYWORD_LENGTH + " 字");
        }

        int pageNo = Math.max(1, page);
        int size = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        LambdaQueryWrapper<ReviewEntity> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(ReviewEntity::getAuditStatus, status);
        }
        if (rating != null) {
            wrapper.eq(ReviewEntity::getRating, rating);
        }
        if (kw != null) {
            wrapper.and(w -> w.like(ReviewEntity::getContent, kw)
                    .or().like(ReviewEntity::getAppendContent, kw)
                    .or().like(ReviewEntity::getProductTitle, kw)
                    .or().like(ReviewEntity::getOrderNo, kw));
        }
        wrapper.orderByDesc(ReviewEntity::getCreatedAt).orderByDesc(ReviewEntity::getId);

        Page<ReviewEntity> result = reviewMapper.selectPage(new Page<>(pageNo, size), wrapper);
        Map<Long, UserBrief> briefs = loadBriefs(result.getRecords().stream()
                .map(ReviewEntity::getUserId).distinct().toList());
        List<AdminReviewView> views = result.getRecords().stream()
                .map(e -> toAdminView(e, briefs))
                .toList();
        return PageResult.of(views, result.getTotal(), pageNo, size);
    }

    /**
     * 人工审核评价：REJECT = 下架（必须填原因），APPROVE = 恢复 / 通过。
     *
     * <p>三条硬规则：
     * <ol>
     *   <li>下架必须写原因：事后复盘与客诉处理只能靠它，和售后裁定说明同口径；</li>
     *   <li>已处于目标态时幂等成功且<b>零写库</b>（重复点击不刷新审核时间）；</li>
     *   <li>状态迁移用条件 UPDATE（{@code WHERE audit_status <> target}），
     *       并发下只有一次生效；0 行时重读，已是目标态按幂等成功，否则 409。</li>
     * </ol>
     *
     * <p>可见性变化在事务提交后触发商品评分重算：下架一条评价后，
     * 商品页的 {@code rating/rating_count} 必须同步下降（甚至归零），
     * 否则前台会出现「评价区少了一条、评分没变」的矛盾数据。
     */
    @Transactional
    public AdminReviewView audit(Long id, ReviewAuditRequest request) {
        LoginUser operator = OperatorGuard.requireOperator();
        if (id == null) {
            throw BizException.validation("评价 id 不能为空");
        }
        if (request == null) {
            throw BizException.validation("请求体不能为空");
        }
        String action = request.action() == null ? "" : request.action().trim().toUpperCase(java.util.Locale.ROOT);
        if (!ACTION_APPROVE.equals(action) && !ACTION_REJECT.equals(action)) {
            throw BizException.validation("审核动作只能是 APPROVE（恢复）或 REJECT（下架）");
        }
        String reason = trimToNull(request.reason());
        if (ACTION_REJECT.equals(action) && reason == null) {
            throw BizException.businessRule("下架评价必须填写原因");
        }

        ReviewEntity entity = reviewMapper.selectById(id);
        if (entity == null) {
            throw BizException.notFound("评价");
        }
        String current = entity.getAuditStatus();
        String target = ACTION_REJECT.equals(action) ? AUDIT_REJECTED : AUDIT_APPROVED;
        Map<Long, UserBrief> briefs = loadBriefs(List.of(entity.getUserId()));
        if (target.equals(current)) {
            // 幂等：已经是目标态，不刷新审核时间，也不产生第二条事实
            return toAdminView(entity, briefs);
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int rows = reviewMapper.update(null, new LambdaUpdateWrapper<ReviewEntity>()
                .eq(ReviewEntity::getId, id)
                .ne(ReviewEntity::getAuditStatus, target)
                .set(ReviewEntity::getAuditStatus, target)
                // 恢复时显式清空原因：MP 默认 NOT_NULL，不写 null 会把旧原因留在库里
                .set(ReviewEntity::getAuditReason, ACTION_REJECT.equals(action) ? reason : null)
                .set(ReviewEntity::getAuditedBy, operator.userId())
                .set(ReviewEntity::getAuditedAt, now)
                .set(ReviewEntity::getUpdatedAt, now));
        if (rows == 0) {
            ReviewEntity latest = reviewMapper.selectById(id);
            if (latest == null) {
                throw BizException.notFound("评价");
            }
            if (target.equals(latest.getAuditStatus())) {
                return toAdminView(latest, loadBriefs(List.of(latest.getUserId())));
            }
            throw BizException.conflict("评价状态已被其他人修改，请刷新后重试");
        }

        entity.setAuditStatus(target);
        entity.setAuditReason(ACTION_REJECT.equals(action) ? reason : null);
        entity.setAuditedBy(operator.userId());
        entity.setAuditedAt(now);
        entity.setUpdatedAt(now);

        // 审计补充：目标评价 + 状态变化（拦截器负责「谁 / 何时 / IP / 结果」）
        AuditTrail.target(id, "REVIEW");
        AuditTrail.change(current == null ? "-" : current, target);

        // 事务提交后重算商品评分（下架后可能归零，见 ProductRatingSyncer）
        eventPublisher.publishEvent(new ProductRatingSyncer.ReviewVisibilityChanged(List.of(entity.getSpuId())));
        log.info("运营 {} {} 评价 {}（商品 {}），原因={}",
                operator.userId(), action, id, entity.getSpuId(), reason);
        return toAdminView(entity, briefs);
    }

    private String normalizeAuditStatus(String status) {
        String value = trimToNull(status);
        if (value == null) {
            return null;
        }
        String upper = value.toUpperCase(java.util.Locale.ROOT);
        if (!AUDIT_PENDING.equals(upper) && !AUDIT_APPROVED.equals(upper) && !AUDIT_REJECTED.equals(upper)) {
            throw BizException.validation("审核状态只能是 PENDING / APPROVED / REJECTED");
        }
        return upper;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ---------------- 内部：校验与装配 ----------------

    private void requireOwnOrder(OrderFact fact, Long userId) {
        if (!userId.equals(fact.userId())) {
            throw BizException.forbidden("只能评价自己的订单");
        }
    }

    private void requireCompleted(OrderFact fact) {
        if (!STATUS_COMPLETED.equals(fact.status())) {
            throw BizException.businessRule(
                    "订单当前状态为「" + fact.statusLabel() + "」，确认收货后才能评价");
        }
    }

    private Map<Long, OrderFact.Item> indexItemsBySpu(OrderFact fact) {
        if (fact.items() == null || fact.items().isEmpty()) {
            throw BizException.businessRule("订单没有商品明细，无法评价");
        }
        // 同一 SPU 出现多次时保留第一条（下单快照里的规格差异不影响评价归属）
        return fact.items().stream()
                .filter(i -> i.spuId() != null)
                .collect(Collectors.toMap(OrderFact.Item::spuId, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
    }

    private void validateRating(int rating) {
        if (rating < MIN_RATING || rating > MAX_RATING) {
            throw BizException.validation("评分必须在 " + MIN_RATING + "-" + MAX_RATING + " 星之间");
        }
    }

    private String normalizeContent(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > maxContentLength) {
            throw BizException.validation("评价内容不能超过 " + maxContentLength + " 字");
        }
        return trimmed;
    }

    // 图片直链的校验与 JSON 读写已抽到 support/ImageUrls：
    // 售后举证图（A3）用的是同一条安全规则（只收 http/https、脏数据宽容读）。
    // 这类「同一规则两处实现」的东西一旦漂移，就是安全缺口 —— 比如某天只在一处补了协议白名单。

    /**
     * 批量取昵称头像。
     *
     * <p>用户服务不可用时**降级**为无昵称展示（前端兜底「用户 1234」），
     * 而不是让整个评价区打不开：评价正文是本地数据，不该被一个远程依赖拖死。
     */
    private Map<Long, UserBrief> loadBriefs(Collection<Long> userIds) {
        List<Long> ids = userIds == null ? List.of()
                : userIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            var response = userClient.briefs(ids.stream().map(String::valueOf).collect(Collectors.joining(",")));
            List<UserBrief> briefs = response == null ? null : response.data();
            if (briefs == null) {
                return Map.of();
            }
            return briefs.stream().collect(Collectors.toMap(UserBrief::id, Function.identity(), (a, b) -> a));
        } catch (Exception e) {
            log.warn("取用户昵称失败，降级为无昵称展示 userIds={}", ids, e);
            return Map.of();
        }
    }

    private boolean isAnonymous(ReviewEntity entity) {
        return entity.getAnonymous() != null && entity.getAnonymous() == 1;
    }

    private ReviewView toView(ReviewEntity entity, Map<Long, UserBrief> briefs, LocalDateTime now) {
        boolean anonymous = isAnonymous(entity);
        UserBrief brief = briefs.get(entity.getUserId());
        String nickname = anonymous ? ANONYMOUS_NICKNAME
                : (brief != null && brief.nickname() != null ? brief.nickname() : "用户" + entity.getUserId());
        String avatar = anonymous ? null : (brief == null ? null : brief.avatar());
        return new ReviewView(
                entity.getId(),
                entity.getOrderNo(),
                entity.getSpuId(),
                entity.getSkuId(),
                entity.getProductTitle(),
                entity.getProductImage(),
                entity.getRating() == null ? 0 : entity.getRating(),
                entity.getContent(),
                ImageUrls.fromJson(objectMapper, entity.getImages()),
                anonymous,
                nickname,
                avatar,
                entity.getAppendContent(),
                iso(entity.getAppendAt()),
                iso(entity.getCreatedAt()),
                isAppendable(entity, now));
    }

    private AdminReviewView toAdminView(ReviewEntity entity, Map<Long, UserBrief> briefs) {
        boolean anonymous = isAnonymous(entity);
        UserBrief brief = briefs.get(entity.getUserId());
        String nickname = anonymous ? ANONYMOUS_NICKNAME
                : (brief != null && brief.nickname() != null ? brief.nickname() : "用户" + entity.getUserId());
        return new AdminReviewView(
                entity.getId(),
                entity.getOrderNo(),
                entity.getUserId(),
                entity.getSellerId(),
                entity.getSpuId(),
                entity.getSkuId(),
                entity.getProductTitle(),
                entity.getProductImage(),
                entity.getRating() == null ? 0 : entity.getRating(),
                entity.getContent(),
                ImageUrls.fromJson(objectMapper, entity.getImages()),
                anonymous,
                nickname,
                entity.getAppendContent(),
                iso(entity.getAppendAt()),
                iso(entity.getCreatedAt()),
                entity.getAuditStatus(),
                entity.getAuditReason(),
                entity.getAuditedBy(),
                iso(entity.getAuditedAt()));
    }

    private boolean isAppendable(ReviewEntity entity, LocalDateTime now) {
        boolean alreadyAppended = entity.getAppendContent() != null && !entity.getAppendContent().isBlank();
        if (alreadyAppended || entity.getCreatedAt() == null) {
            return false;
        }
        if (appendWindowDays <= 0) {
            return true;
        }
        return !entity.getCreatedAt().plusDays(appendWindowDays).isBefore(now);
    }

    private String iso(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC).toString();
    }

    private long asLong(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private double asDouble(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        if (value == null) {
            return 0d;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return 0d;
        }
    }
}
