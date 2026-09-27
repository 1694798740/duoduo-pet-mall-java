package com.duoduo.fulfilment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.fulfilment.client.ProductClient;
import com.duoduo.fulfilment.client.UserClient;
import com.duoduo.fulfilment.client.dto.ProductCardBrief;
import com.duoduo.fulfilment.client.dto.UserBrief;
import com.duoduo.fulfilment.dto.ShareDtos;
import com.duoduo.fulfilment.dto.ShareDtos.EngagementState;
import com.duoduo.fulfilment.dto.ShareDtos.ShareCreateRequest;
import com.duoduo.fulfilment.dto.ShareDtos.ShareView;
import com.duoduo.fulfilment.entity.ShareEntity;
import com.duoduo.fulfilment.mapper.ShareEngagementMapper;
import com.duoduo.fulfilment.mapper.ShareMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 分享圈服务（第四十三轮）。
 *
 * <p>规则集中在这里，控制器只做参数绑定：
 * <ol>
 *   <li><b>发布需登录</b>：身份只来自登录态，请求体里没有 userId 可供伪造；</li>
 *   <li><b>商品快照以商品域事实为准</b>：标题 / 主图 / 最低价从商品域取，
 *       请求体只带 spuId —— 否则公开信息流里能贴任意伪造的商品卡；</li>
 *   <li><b>商品必须存在</b>：不存在即 404，不允许给幽灵商品发分享；</li>
 *   <li><b>浏览公开</b>：分享是商品口碑内容，未登录访客也该能逛能看
 *       （与商品评价同一公开读口径）。</li>
 * </ol>
 */
@Service
public class ShareService {

    private static final Logger log = LoggerFactory.getLogger(ShareService.class);

    private static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_PAGE_SIZE = 10;

    private final ShareMapper shareMapper;
    private final ShareEngagementMapper engagementMapper;
    private final ProductClient productClient;
    private final UserClient userClient;
    private final SnowflakeIdGenerator idGenerator;
    private final int maxContentLength;

    public ShareService(ShareMapper shareMapper,
                        ShareEngagementMapper engagementMapper,
                        ProductClient productClient,
                        UserClient userClient,
                        SnowflakeIdGenerator idGenerator,
                        @Value("${duoduo.share.max-content-length:500}") int maxContentLength) {
        this.shareMapper = shareMapper;
        this.engagementMapper = engagementMapper;
        this.productClient = productClient;
        this.userClient = userClient;
        this.idGenerator = idGenerator;
        this.maxContentLength = maxContentLength;
    }

    // ---------------- 写：发布分享（需登录） ----------------

    /**
     * 发布分享。
     *
     * <p>商品快照在发布时点落库：商品域查不到该商品直接 404（不存在给幽灵商品
     * 发分享的分支）；查得到就按当时的样子存 —— 商品后续改名换图不影响历史分享。
     */
    public ShareView create(Long userId, ShareCreateRequest request) {
        if (userId == null) {
            throw BizException.unauthorized("请先登录后再分享");
        }
        if (request.spuId() == null) {
            throw BizException.validation("缺少 spuId，无法确定分享的商品");
        }
        String content = normalizeContent(request.content());

        ProductCardBrief brief = requireProduct(request.spuId());

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ShareEntity entity = new ShareEntity();
        entity.setId(idGenerator.nextId());
        entity.setUserId(userId);
        entity.setSpuId(request.spuId());
        entity.setProductTitle(brief.title() == null ? "" : brief.title());
        entity.setProductImage(brief.mainImage() == null ? "" : brief.mainImage());
        entity.setMinPrice(brief.minPrice() == null ? 0L : brief.minPrice());
        entity.setContent(content);
        entity.setLikeCount(0);
        entity.setFavoriteCount(0);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        shareMapper.insert(entity);

        log.info("用户 {} 发布了商品 {} 的分享（{}）", userId, request.spuId(), entity.getId());
        ShareContext ctx = loadContext(List.of(entity));
        return toView(entity, ctx, Map.of(), Map.of());
    }

    // ---------------- 写：转发（第四十六轮，需登录） ----------------

    /**
     * 转发一条分享到分享圈。
     *
     * <p>转发 = <b>又一次分享</b>，不是「引用展示」：这里真的插入一条新记录 ——
     * {@code userId = 转发人}、{@code parentShareId = 原帖}。于是：
     * <ol>
     *   <li>信息流按发布时间倒序，转发行出现在最前 —— 修复「转发后列表里看不到」；</li>
     *   <li>详情页按 {@code userId} 渲染，转发人打开自己的转发行看到的就是「我」
     *       —— 修复「详情页仍显示原发帖人自己的分享页」；</li>
     *   <li>商品快照<b>直接沿用原帖</b>，不重新查商品域 —— 转发引用的是原帖内容，
     *       重新拉商品会让同一条内容在原创与转发两处快照不一致；也顺带省掉一次
     *       跨域调用，商品域抖动不影响转发。</li>
     * </ol>
     *
     * <p>三条拒绝规则：原帖必须存在（404）；不能转发自己（业务规则）；
     * 同一原帖只能转一次（409；并发窗口由唯一键 {@code uk_share_user_parent} 兜住）。
     */
    public ShareView repost(Long userId, Long shareId, String content) {
        if (userId == null) {
            throw BizException.unauthorized("请先登录后再转发");
        }
        ShareEntity origin = shareMapper.selectById(shareId);
        if (origin == null) {
            throw BizException.notFound("分享");
        }
        if (userId.equals(origin.getUserId())) {
            throw BizException.businessRule("这是你自己的分享，不用转发");
        }
        Long duplicated = shareMapper.selectCount(new LambdaQueryWrapper<ShareEntity>()
                .eq(ShareEntity::getUserId, userId)
                .eq(ShareEntity::getParentShareId, shareId));
        if (duplicated != null && duplicated > 0) {
            throw BizException.conflict("你已经转发过这条分享了");
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ShareEntity entity = new ShareEntity();
        entity.setId(idGenerator.nextId());
        entity.setUserId(userId);
        entity.setParentShareId(shareId);
        // 商品快照沿用原帖：转发引用的是原帖内容，不是「转发那一刻的商品」
        entity.setSpuId(origin.getSpuId());
        entity.setProductTitle(origin.getProductTitle());
        entity.setProductImage(origin.getProductImage());
        entity.setMinPrice(origin.getMinPrice() == null ? 0L : origin.getMinPrice());
        entity.setContent(normalizeNote(content));
        entity.setLikeCount(0);
        entity.setFavoriteCount(0);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        shareMapper.insert(entity);

        log.info("用户 {} 转发了分享 {}（转发单 {}）", userId, shareId, entity.getId());
        return toView(entity, loadContext(List.of(entity)), Map.of(), Map.of());
    }

    // ---------------- 互动：点赞 / 收藏 toggle（需登录，第四十四轮） ----------------

    /**
     * 点赞 / 收藏共用一次切换逻辑：事实行的**存在性**就是状态，
     * 计数列随动作原子回写（GREATEST 防负漂移）。
     *
     * <p>并发语义：同一用户同一分享重复点击，两个请求都可能先查后插 ——
     * 第二个插入会撞复合主键，按「已点赞」幂等处理，计数只加一次。
     */
    public EngagementState toggleLike(Long userId, Long shareId) {
        return toggleEngagement(userId, shareId, true);
    }

    public EngagementState toggleFavorite(Long userId, Long shareId) {
        return toggleEngagement(userId, shareId, false);
    }

    private EngagementState toggleEngagement(Long userId, Long shareId, boolean like) {
        requireShare(shareId);
        boolean active = like
                ? engagementMapper.likeExists(shareId, userId) > 0
                : engagementMapper.favoriteExists(shareId, userId) > 0;
        int delta;
        if (active) {
            int removed = like
                    ? engagementMapper.deleteLike(shareId, userId)
                    : engagementMapper.deleteFavorite(shareId, userId);
            delta = removed > 0 ? -1 : 0;
        } else {
            int inserted = like
                    ? engagementMapper.insertLike(shareId, userId)
                    : engagementMapper.insertFavorite(shareId, userId);
            // INSERT IGNORE：并发重复插入返回 0 —— 状态按已激活、计数不加
            delta = inserted > 0 ? 1 : 0;
        }
        if (delta != 0) {
            if (like) {
                engagementMapper.bumpLikeCount(shareId, delta);
            } else {
                engagementMapper.bumpFavoriteCount(shareId, delta);
            }
        }
        ShareEntity fresh = shareMapper.selectById(shareId);
        long count = fresh == null ? 0
                : (like ? nz(fresh.getLikeCount()) : nz(fresh.getFavoriteCount()));
        return new EngagementState(!active, count);
    }

    // ---------------- 读：公开 ----------------

    /** 分享圈信息流（公开）：按发布时间倒序分页；登录浏览者带 liked/favorited 标记 */
    public PageResult<ShareView> feed(Long viewerId, int page, int pageSize) {
        int pageNo = Math.max(1, page);
        int size = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        Page<ShareEntity> result = shareMapper.selectPage(new Page<>(pageNo, size),
                new LambdaQueryWrapper<ShareEntity>()
                        .orderByDesc(ShareEntity::getCreatedAt)
                        .orderByDesc(ShareEntity::getId));
        ShareContext ctx = loadContext(result.getRecords());
        var engagement = loadEngagement(viewerId, result.getRecords());
        List<ShareView> views = result.getRecords().stream()
                .map(e -> toView(e, ctx, engagement.liked(), engagement.favorited()))
                .toList();
        return PageResult.of(views, result.getTotal(), pageNo, size);
    }

    /** 分享详情（公开）：分享落地页 {@code share-detail?id=} 的数据源 */
    public ShareView detail(Long viewerId, Long id) {
        ShareEntity entity = shareMapper.selectById(id);
        if (entity == null) {
            throw BizException.notFound("分享");
        }
        ShareContext ctx = loadContext(List.of(entity));
        var engagement = loadEngagement(viewerId, List.of(entity));
        return toView(entity, ctx, engagement.liked(), engagement.favorited());
    }

    // ---------------- 内部：校验与装配 ----------------

    /** 商品快照必须取得到：查不到即 404（不存在给幽灵商品发分享的分支） */
    private ProductCardBrief requireProduct(Long spuId) {
        List<ProductCardBrief> cards;
        try {
            var response = productClient.cards(String.valueOf(spuId));
            cards = response == null ? null : response.data();
        } catch (Exception e) {
            // 商品域不可用时不静默降级：发布是写操作，落一条没有快照的分享
            // 会让信息流出现空白卡片，宁可让用户稍后再试
            log.warn("取商品快照失败 spuId={}", spuId, e);
            throw BizException.businessRule("商品信息暂时获取不到，请稍后再试");
        }
        if (cards == null || cards.isEmpty() || cards.get(0).id() == null) {
            throw BizException.notFound("商品");
        }
        return cards.get(0);
    }

    private String normalizeContent(String content) {
        if (content == null) {
            throw BizException.validation("分享内容不能为空");
        }
        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            throw BizException.validation("分享内容不能为空");
        }
        if (trimmed.length() > maxContentLength) {
            throw BizException.validation("分享内容不能超过 " + maxContentLength + " 字");
        }
        return trimmed;
    }

    /**
     * 转发附言：<b>可空</b>（纯转发，不写话也算转发）。
     *
     * <p>空串落库而不是 NULL —— 列 {@code content} 是 NOT NULL，且展示层用
     * 「空串 = 没写附言」判断是否渲染附言块，语义直接。
     */
    private String normalizeNote(String content) {
        if (content == null) {
            return "";
        }
        String trimmed = content.trim();
        if (trimmed.length() > maxContentLength) {
            throw BizException.validation("转发附言不能超过 " + maxContentLength + " 字");
        }
        return trimmed;
    }

    /** toggle 前置：分享必须存在（点赞一个不存在的分享没有意义） */
    private void requireShare(Long shareId) {
        if (shareMapper.selectById(shareId) == null) {
            throw BizException.notFound("分享");
        }
    }

    /**
     * 批量取当前浏览者的 liked / favorited 集合。
     *
     * <p>匿名浏览（viewerId = null）返回空集合 —— 信息流是公开的，
     * 但「我赞过没赞过」只有登录了才知道。
     */
    private EngagementSets loadEngagement(Long viewerId, List<ShareEntity> records) {
        if (viewerId == null || records.isEmpty()) {
            return new EngagementSets(Map.of(), Map.of());
        }
        String ids = records.stream().map(e -> String.valueOf(e.getId()))
                .collect(Collectors.joining(","));
        Map<Long, Boolean> liked = engagementMapper.likedShareIds(viewerId, ids).stream()
                .collect(Collectors.toMap(Function.identity(), s -> true, (a, b) -> a));
        Map<Long, Boolean> favorited = engagementMapper.favoritedShareIds(viewerId, ids).stream()
                .collect(Collectors.toMap(Function.identity(), s -> true, (a, b) -> a));
        return new EngagementSets(liked, favorited);
    }

    private record EngagementSets(Map<Long, Boolean> liked, Map<Long, Boolean> favorited) {
    }

    /**
     * 装配上下文：本条作者 + 原帖作者<b>一次</b>批量取昵称（避免逐条回源 = N+1）。
     *
     * <p>原帖只在**转发**时需要：先批量查出原帖行（拿到原帖作者 id 与评价内容），
     * 再把两类作者 id 合并成一次 {@code loadBriefs} 调用。信息流一页 10 条转发
     * 也只是 1 次原帖查询 + 1 次昵称查询。
     */
    private ShareContext loadContext(List<ShareEntity> records) {
        List<Long> parentIds = records.stream()
                .map(ShareEntity::getParentShareId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, ShareEntity> parents = parentIds.isEmpty() ? Map.of()
                : shareMapper.selectBatchIds(parentIds).stream()
                        .collect(Collectors.toMap(ShareEntity::getId, Function.identity(), (a, b) -> a));
        List<Long> userIds = Stream
                .concat(records.stream().map(ShareEntity::getUserId),
                        parents.values().stream().map(ShareEntity::getUserId))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return new ShareContext(loadBriefs(userIds), parents);
    }

    /** 装配上下文：作者昵称表 + 原帖行表（转发才有原帖） */
    private record ShareContext(Map<Long, UserBrief> briefs, Map<Long, ShareEntity> parents) {
    }

    /**
     * 批量取昵称头像（与 {@code ReviewService} 同一降级策略）：
     * 用户服务不可用时降级为「用户 {id}」，不让整个分享圈打不开。
     */
    private Map<Long, UserBrief> loadBriefs(Collection<Long> userIds) {
        List<Long> ids = userIds == null ? List.of()
                : userIds.stream().filter(Objects::nonNull).distinct().toList();
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

    private ShareView toView(ShareEntity entity, ShareContext ctx,
                             Map<Long, Boolean> liked, Map<Long, Boolean> favorited) {
        UserBrief brief = ctx.briefs().get(entity.getUserId());
        Long parentId = entity.getParentShareId();
        ShareEntity parent = parentId == null ? null : ctx.parents().get(parentId);
        return new ShareView(
                entity.getId(),
                // 作者恒为本条分享的作者：转发时是转发人本人（第四十六轮修复点之一）
                entity.getUserId(),
                entity.getSpuId(),
                entity.getProductTitle(),
                entity.getProductImage(),
                entity.getMinPrice(),
                entity.getContent(),
                nicknameOf(ctx.briefs(), entity.getUserId()),
                brief == null ? null : brief.avatar(),
                iso(entity.getCreatedAt()),
                nz(entity.getLikeCount()),
                nz(entity.getFavoriteCount()),
                liked.containsKey(entity.getId()),
                favorited.containsKey(entity.getId()),
                parentId == null ? ShareDtos.TYPE_ORIGINAL : ShareDtos.TYPE_REPOST,
                parentId,
                // 原帖已不存在（当前无删除入口，防御性兜底）时给个中性署名，不留空
                parentId == null ? null
                        : (parent == null ? "原分享" : nicknameOf(ctx.briefs(), parent.getUserId())),
                parent == null ? null : parent.getContent());
    }

    private String nicknameOf(Map<Long, UserBrief> briefs, Long userId) {
        if (userId == null) {
            return null;
        }
        UserBrief brief = briefs.get(userId);
        return brief != null && brief.nickname() != null ? brief.nickname() : "用户" + userId;
    }

    private long nz(Integer value) {
        return value == null ? 0L : value.longValue();
    }

    private String iso(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC).toString();
    }
}
