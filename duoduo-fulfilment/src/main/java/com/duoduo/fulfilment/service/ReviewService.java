package com.duoduo.fulfilment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.fulfilment.client.UserClient;
import com.duoduo.fulfilment.client.dto.OrderFact;
import com.duoduo.fulfilment.client.dto.UserBrief;
import com.duoduo.fulfilment.dto.ReviewDtos.ItemReview;
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
    private static final String AUDIT_APPROVED = "APPROVED";
    private static final String ANONYMOUS_NICKNAME = "匿名用户";

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
