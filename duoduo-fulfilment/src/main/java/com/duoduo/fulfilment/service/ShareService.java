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
import java.util.function.Function;
import java.util.stream.Collectors;

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
        Map<Long, UserBrief> briefs = loadBriefs(List.of(userId));
        return toView(entity, briefs, Map.of(), Map.of());
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
        Map<Long, UserBrief> briefs = loadBriefs(result.getRecords().stream()
                .map(ShareEntity::getUserId).distinct().toList());
        var engagement = loadEngagement(viewerId, result.getRecords());
        List<ShareView> views = result.getRecords().stream()
                .map(e -> toView(e, briefs, engagement.liked(), engagement.favorited()))
                .toList();
        return PageResult.of(views, result.getTotal(), pageNo, size);
    }

    /** 分享详情（公开）：分享落地页 {@code share-detail?id=} 的数据源 */
    public ShareView detail(Long viewerId, Long id) {
        ShareEntity entity = shareMapper.selectById(id);
        if (entity == null) {
            throw BizException.notFound("分享");
        }
        Map<Long, UserBrief> briefs = loadBriefs(List.of(entity.getUserId()));
        var engagement = loadEngagement(viewerId, List.of(entity));
        return toView(entity, briefs, engagement.liked(), engagement.favorited());
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
     * 批量取昵称头像（与 {@code ReviewService} 同一降级策略）：
     * 用户服务不可用时降级为「用户 {id}」，不让整个分享圈打不开。
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

    private ShareView toView(ShareEntity entity, Map<Long, UserBrief> briefs,
                             Map<Long, Boolean> liked, Map<Long, Boolean> favorited) {
        UserBrief brief = briefs.get(entity.getUserId());
        String nickname = brief != null && brief.nickname() != null
                ? brief.nickname()
                : "用户" + entity.getUserId();
        return new ShareView(
                entity.getId(),
                entity.getSpuId(),
                entity.getProductTitle(),
                entity.getProductImage(),
                entity.getMinPrice(),
                entity.getContent(),
                nickname,
                brief == null ? null : brief.avatar(),
                iso(entity.getCreatedAt()),
                nz(entity.getLikeCount()),
                nz(entity.getFavoriteCount()),
                liked.containsKey(entity.getId()),
                favorited.containsKey(entity.getId()));
    }

    private long nz(Integer value) {
        return value == null ? 0L : value.longValue();
    }

    private String iso(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC).toString();
    }
}
