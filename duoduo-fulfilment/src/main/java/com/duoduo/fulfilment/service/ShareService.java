package com.duoduo.fulfilment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.fulfilment.client.ProductClient;
import com.duoduo.fulfilment.client.SocialClient;
import com.duoduo.fulfilment.client.dto.ProductCardBrief;
import com.duoduo.fulfilment.client.dto.UserBrief;
import com.duoduo.fulfilment.dto.ShareDtos;
import com.duoduo.fulfilment.dto.ShareDtos.EngagementState;
import com.duoduo.fulfilment.dto.ShareDtos.ShareCreateRequest;
import com.duoduo.fulfilment.dto.ShareDtos.ShareEditRequest;
import com.duoduo.fulfilment.dto.ShareDtos.ShareView;
import com.duoduo.fulfilment.dto.ShareDtos.TopicView;
import com.duoduo.fulfilment.entity.ShareEntity;
import com.duoduo.fulfilment.mapper.ShareEngagementMapper;
import com.duoduo.fulfilment.mapper.ShareMapper;
import com.duoduo.fulfilment.support.UserBriefLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 分享圈服务（第四十三轮；第五十六轮补评论联动 / 删除编辑 / 关注流 / 话题）。
 *
 * <p>规则集中在这里，控制器只做参数绑定：
 * <ol>
 *   <li><b>发布需登录</b>：身份只来自登录态，请求体里没有 userId 可供伪造；</li>
 *   <li><b>商品快照以商品域事实为准</b>：标题 / 主图 / 最低价从商品域取，
 *       请求体只带 spuId —— 否则公开信息流里能贴任意伪造的商品卡；</li>
 *   <li><b>商品必须存在</b>：不存在即 404，不允许给幽灵商品发分享；</li>
 *   <li><b>浏览公开</b>：分享是商品口碑内容，未登录访客也该能逛能看
 *       （与商品评价同一公开读口径）；</li>
 *   <li><b>★ 删除是软删</b>（第五十六轮）：所有读路径一律带 {@code deleted_at IS NULL}。
 *       不能物理删的原因见 {@link ShareEntity#getDeletedAt()} ——
 *       转发单的「原帖引用」要靠回查原帖行才能显示；</li>
 *   <li><b>★ 关注流为空就是空</b>：没关注任何人时返回空列表，
 *       <b>绝不退化成全站内容</b> —— 那会让用户以为「关注流坏了」，
 *       更糟的是他会以为自己关注的人发了一堆没看过的内容。</li>
 * </ol>
 */
@Service
public class ShareService {

    private static final Logger log = LoggerFactory.getLogger(ShareService.class);

    private static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_PAGE_SIZE = 10;

    /** 关注流一次最多取多少个「我关注的人」（与社交域上限一致，见社交域 followingIds 注释） */
    private static final int FOLLOWING_LIMIT = 500;

    private final ShareMapper shareMapper;
    private final ShareEngagementMapper engagementMapper;
    private final ProductClient productClient;
    private final SocialClient socialClient;
    private final ShareTopicService topicService;
    private final UserBriefLoader briefLoader;
    private final SnowflakeIdGenerator idGenerator;
    private final int maxContentLength;

    public ShareService(ShareMapper shareMapper,
                        ShareEngagementMapper engagementMapper,
                        ProductClient productClient,
                        SocialClient socialClient,
                        ShareTopicService topicService,
                        UserBriefLoader briefLoader,
                        SnowflakeIdGenerator idGenerator,
                        @Value("${duoduo.share.max-content-length:500}") int maxContentLength) {
        this.shareMapper = shareMapper;
        this.engagementMapper = engagementMapper;
        this.productClient = productClient;
        this.socialClient = socialClient;
        this.topicService = topicService;
        this.briefLoader = briefLoader;
        this.idGenerator = idGenerator;
        this.maxContentLength = maxContentLength;
    }

    // ---------------- 写：发布分享（需登录） ----------------

    /**
     * 发布分享。
     *
     * <p>商品快照在发布时点落库：商品域查不到该商品直接 404（不存在给幽灵商品
     * 发分享的分支）；查得到就按当时的样子存 —— 商品后续改名换图不影响历史分享。
     *
     * <p>话题（第五十六轮）：规范化后挂载；挂载在分享落库之后，
     * 因为关系表要引用分享 id。
     */
    public ShareView create(Long userId, ShareCreateRequest request) {
        if (userId == null) {
            throw BizException.unauthorized("请先登录后再分享");
        }
        if (request.spuId() == null) {
            throw BizException.validation("缺少 spuId，无法确定分享的商品");
        }
        String content = normalizeContent(request.content());
        List<String> topics = topicService.normalize(request.topics());

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
        entity.setCommentCount(0);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        shareMapper.insert(entity);
        topicService.attach(entity.getId(), topics);

        log.info("用户 {} 发布了商品 {} 的分享（{}，话题 {}）",
                userId, request.spuId(), entity.getId(), topics);
        // 走统一装配而不是手拼视图：话题 / 互动标记都要读真库，
        // 手拼过一次「忘了带上话题」——返回体与随后拉到的详情不一致，前端会以为发布失败
        return assemble(userId, List.of(entity)).get(0);
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
     * <p>三条拒绝规则：原帖必须存在（404；<b>已被删除的原帖也不可转发</b>）；
     * 不能转发自己（业务规则）；同一原帖只能转一次（409；
     * 并发窗口由唯一键 {@code uk_share_user_parent} 兜住）。
     */
    public ShareView repost(Long userId, Long shareId, String content) {
        if (userId == null) {
            throw BizException.unauthorized("请先登录后再转发");
        }
        ShareEntity origin = requireVisible(shareId);
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
        entity.setCommentCount(0);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        shareMapper.insert(entity);

        log.info("用户 {} 转发了分享 {}（转发单 {}）", userId, shareId, entity.getId());
        return assemble(userId, List.of(entity)).get(0);
    }

    // ---------------- 写：编辑 / 删除自己的分享（第五十六轮） ----------------

    /**
     * 编辑自己的分享：<b>只改文字与话题</b>。
     *
     * <p>不允许改商品：商品是发布时点的快照，改了等于把这条分享换成另一件商品的评价 ——
     * 那应该是「删掉重发」。也不允许把原创改成转发（结构变更不在编辑语义内）。
     *
     * <p>话题的三个取值必须区分开：{@code null} = 不改；空列表 = 清空；
     * 非空 = 替换成这一批。混淆「不改」与「清空」会让用户永远删不掉话题。
     */
    public ShareView edit(Long userId, Long shareId, ShareEditRequest request) {
        ShareEntity entity = requireOwned(userId, shareId, "编辑");

        boolean repost = entity.getParentShareId() != null;
        String content = repost ? normalizeNote(request.content()) : normalizeContent(request.content());
        entity.setContent(content);
        entity.setEditedAt(LocalDateTime.now(ZoneOffset.UTC));
        entity.setUpdatedAt(entity.getEditedAt());
        shareMapper.updateById(entity);

        if (request.topics() != null) {
            topicService.replace(shareId, topicService.normalize(request.topics()));
        }

        log.info("用户 {} 编辑了分享 {}（话题处理：{}）",
                userId, shareId, request.topics() == null ? "未改动" : "已替换");
        return assemble(userId, List.of(shareMapper.selectById(shareId))).get(0);
    }

    /**
     * 删除自己的分享：<b>软删</b>，并回退话题计数。
     *
     * <p>删除后：信息流 / 详情 / 话题聚合一律看不到它（对用户等价于删除）；
     * 但别人转发过它的转发单仍然存在，引用块显示「原内容已被作者删除」
     * —— 这正是用户选定的口径。
     */
    public void delete(Long userId, Long shareId) {
        ShareEntity entity = requireOwned(userId, shareId, "删除");
        entity.setDeletedAt(LocalDateTime.now(ZoneOffset.UTC));
        entity.setUpdatedAt(entity.getDeletedAt());
        shareMapper.updateById(entity);
        topicService.detachAll(shareId);
        log.info("用户 {} 删除了分享 {}", userId, shareId);
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
        requireVisible(shareId);
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

    /**
     * 分享圈信息流（公开）。
     *
     * <p>{@code scope}：{@code RECOMMEND}（默认，全站）或 {@code FOLLOWING}
     * （只看我关注的人，第五十六轮）。关注流需要登录 —— 匿名用户没有「我关注的人」，
     * 与其返回空不如明确要求登录。
     */
    public PageResult<ShareView> feed(Long viewerId, String scope, int page, int pageSize) {
        int pageNo = Math.max(1, page);
        int size = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);

        LambdaQueryWrapper<ShareEntity> wrapper = new LambdaQueryWrapper<ShareEntity>()
                .isNull(ShareEntity::getDeletedAt);
        if (ShareDtos.SCOPE_FOLLOWING.equalsIgnoreCase(scope)) {
            if (viewerId == null) {
                throw BizException.unauthorized("关注流需要先登录");
            }
            List<Long> authorIds = followingAuthorIds(viewerId);
            if (authorIds.isEmpty()) {
                // 空就是空：绝不退化成全站内容（见类注释第 6 条）
                return PageResult.of(List.of(), 0, pageNo, size);
            }
            wrapper.in(ShareEntity::getUserId, authorIds);
        }
        Page<ShareEntity> result = shareMapper.selectPage(new Page<>(pageNo, size),
                wrapper.orderByDesc(ShareEntity::getCreatedAt).orderByDesc(ShareEntity::getId));
        List<ShareView> views = assemble(viewerId, result.getRecords());
        return PageResult.of(views, result.getTotal(), pageNo, size);
    }

    /** 分享详情（公开）：分享落地页 {@code share-detail?id=} 的数据源 */
    public ShareView detail(Long viewerId, Long id) {
        ShareEntity entity = requireVisible(id);
        return assemble(viewerId, List.of(entity)).get(0);
    }

    /** 我的分享（需登录）：管理页数据源（编辑 / 删除入口挂在这里） */
    public PageResult<ShareView> mine(Long userId, int page, int pageSize) {
        if (userId == null) {
            throw BizException.unauthorized("请先登录");
        }
        int pageNo = Math.max(1, page);
        int size = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        Page<ShareEntity> result = shareMapper.selectPage(new Page<>(pageNo, size),
                new LambdaQueryWrapper<ShareEntity>()
                        .eq(ShareEntity::getUserId, userId)
                        .isNull(ShareEntity::getDeletedAt)
                        .orderByDesc(ShareEntity::getCreatedAt)
                        .orderByDesc(ShareEntity::getId));
        return PageResult.of(assemble(userId, result.getRecords()), result.getTotal(), pageNo, size);
    }

    /** 话题聚合页（公开）：按话题查分享，带当前浏览者的互动标记 */
    public PageResult<ShareView> topicFeed(Long viewerId, String topicName, int page, int pageSize) {
        topicService.requireValidName(topicName);
        var topic = topicService.findByName(topicName);
        if (topic == null) {
            throw BizException.notFound("话题");
        }
        int pageNo = Math.max(1, page);
        int size = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        List<ShareEntity> records = shareMapper.selectPageByTopic(topic.getId(), (pageNo - 1) * size, size);
        long total = shareMapper.countByTopic(topic.getId());
        return PageResult.of(assemble(viewerId, records), total, pageNo, size);
    }

    /** 热门话题榜（公开）：发布页的「大家都在聊」 */
    public List<TopicView> hotTopics(int limit) {
        return topicService.hot(limit);
    }

    // ---------------- 内部：可见性与归属 ----------------

    /**
     * 取一条<b>可见</b>的分享（存在且未删除），否则 404。
     *
     * <p>公开给同包服务用（{@code ShareCommentService} 判评论目标、举报服务判可举报对象）：
     * 「已删除的内容算不存在」这条口径必须只有一处定义，否则评论区与信息流会出现
     * 「列表看不到、但评论还能发」这种自相矛盾的状态。
     */
    public ShareEntity requireVisible(Long shareId) {
        if (shareId == null) {
            throw BizException.notFound("分享");
        }
        ShareEntity entity = shareMapper.selectById(shareId);
        if (entity == null || entity.getDeletedAt() != null) {
            throw BizException.notFound("分享");
        }
        return entity;
    }

    /** 取一条属于当前用户的可见分享；不是本人则 403（不区分「不存在」是为了防枚举） */
    private ShareEntity requireOwned(Long userId, Long shareId, String action) {
        if (userId == null) {
            throw BizException.unauthorized("请先登录");
        }
        ShareEntity entity = requireVisible(shareId);
        if (!Objects.equals(entity.getUserId(), userId)) {
            // 他人分享：与「不存在」同码同文案，避免用状态码差异枚举他人分享 id
            throw BizException.notFound("分享");
        }
        return entity;
    }

    /**
     * 「我关注的人」的 id 列表（best-effort：社交域挂掉不影响「推荐」流，但关注流要显式失败）。
     *
     * <p>为什么这里**不**像商品快照那样静默降级成空列表：空列表会让用户看到
     * 「还没有关注的人」，而真实情况是服务不可用 —— 用户会去检查自己的关注列表，
     * 找不到问题。明确报错并允许重试，才是诚实的做法。
     */
    private List<Long> followingAuthorIds(Long viewerId) {
        try {
            var response = socialClient.following(viewerId, FOLLOWING_LIMIT);
            List<Long> ids = response == null ? null : response.data();
            return ids == null ? List.of() : ids;
        } catch (Exception e) {
            log.warn("取关注列表失败 viewerId={}", viewerId, e);
            throw BizException.businessRule("关注列表暂时获取不到，请稍后重试");
        }
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

    // ---------------- 内部：装配 ----------------

    /** 一页分享的完整装配：作者 + 原帖 + 当前浏览者互动 + 话题 */
    private List<ShareView> assemble(Long viewerId, List<ShareEntity> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        ShareContext ctx = loadContext(records);
        EngagementSets engagement = loadEngagement(viewerId, records);
        Map<Long, List<String>> topics = topicService.namesOfBatch(
                records.stream().map(ShareEntity::getId).toList());
        List<ShareView> views = new ArrayList<>(records.size());
        for (ShareEntity entity : records) {
            views.add(toView(entity, ctx, engagement.liked(), engagement.favorited(), topics, viewerId));
        }
        return views;
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
        return new ShareContext(briefLoader.load(userIds), parents);
    }

    /** 装配上下文：作者昵称表 + 原帖行表（转发才有原帖） */
    private record ShareContext(Map<Long, UserBrief> briefs, Map<Long, ShareEntity> parents) {
    }

    private ShareView toView(ShareEntity entity, ShareContext ctx,
                             Map<Long, Boolean> liked, Map<Long, Boolean> favorited,
                             Map<Long, List<String>> topics, Long viewerId) {
        UserBrief brief = ctx.briefs().get(entity.getUserId());
        Long parentId = entity.getParentShareId();
        ShareEntity parent = parentId == null ? null : ctx.parents().get(parentId);
        // 原帖已被删除：内容置 null（尊重删除意图），由 parentDeleted 让前端换提示语。
        // 注意这里**不能**只在 parent == null 时才置空 —— 软删行依然查得到，
        // 漏判就会把已删除的原帖内容照样展示出来，等于删除没生效。
        boolean parentDeleted = parentId != null && (parent == null || parent.getDeletedAt() != null);
        String parentContent = parentDeleted || parent == null ? null : parent.getContent();
        String parentNickname = parentId == null ? null
                : (parentDeleted ? "原分享" : briefLoader.nicknameOf(ctx.briefs(), parent.getUserId()));
        return new ShareView(
                entity.getId(),
                // 作者恒为本条分享的作者：转发时是转发人本人（第四十六轮修复点之一）
                entity.getUserId(),
                entity.getSpuId(),
                entity.getProductTitle(),
                entity.getProductImage(),
                entity.getMinPrice(),
                entity.getContent(),
                briefLoader.nicknameOf(ctx.briefs(), entity.getUserId()),
                brief == null ? null : brief.avatar(),
                iso(entity.getCreatedAt()),
                nz(entity.getLikeCount()),
                nz(entity.getFavoriteCount()),
                nz(entity.getCommentCount()),
                liked.containsKey(entity.getId()),
                favorited.containsKey(entity.getId()),
                parentId == null ? ShareDtos.TYPE_ORIGINAL : ShareDtos.TYPE_REPOST,
                parentId,
                parentNickname,
                parentContent,
                parentDeleted,
                entity.getEditedAt() != null,
                topics.getOrDefault(entity.getId(), List.of()),
                viewerId != null && Objects.equals(viewerId, entity.getUserId()));
    }

    private long nz(Integer value) {
        return value == null ? 0L : value.longValue();
    }

    private String iso(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC).toString();
    }
}
