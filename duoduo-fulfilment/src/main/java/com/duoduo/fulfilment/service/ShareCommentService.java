package com.duoduo.fulfilment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.fulfilment.client.FulfilmentNotifier;
import com.duoduo.fulfilment.client.dto.UserBrief;
import com.duoduo.fulfilment.dto.ShareDtos.CommentCreateRequest;
import com.duoduo.fulfilment.dto.ShareDtos.CommentView;
import com.duoduo.fulfilment.dto.ShareDtos.EngagementState;
import com.duoduo.fulfilment.entity.ShareCommentEntity;
import com.duoduo.fulfilment.entity.ShareEntity;
import com.duoduo.fulfilment.mapper.ShareCommentMapper;
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
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 分享评论服务（第五十六轮）。
 *
 * <p>规则集中在这里，控制器只做参数绑定：
 * <ol>
 *   <li><b>二级结构</b>：一级评论 + 其下回复。回复的 {@code parentCommentId} 必须指向
 *       <b>同一条分享下、未被删除的一级评论</b> —— 允许「回复一条回复」会让
 *       折叠与 @ 链路立刻变成另一个量级的工作，v1 明确不做；</li>
 *   <li><b>评论数口径（一级与回复都算）由本服务维护</b>：发表即 +1，
 *       运营下架时 -(1 + 其下未删回复数)。放在这里而不是数据库触发器：
 *       计数与业务动作同源，读代码就能确认它什么时候变；</li>
 *   <li><b>通知 best-effort</b>：发完评论后推通知，失败只记日志 ——
 *       评论已经落库，为一条提醒把「评论成功」判成失败，会让用户重发一遍；</li>
 *   <li><b>不给自己发通知</b>：评论自己 / 回复自己一律跳过，否则通知中心会出现
 *      自己给自己发的提醒。</li>
 * </ol>
 */
@Service
public class ShareCommentService {

    private static final Logger log = LoggerFactory.getLogger(ShareCommentService.class);

    private static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final ShareCommentMapper commentMapper;
    private final ShareEngagementMapper engagementMapper;
    private final ShareMapper shareMapper;
    private final ShareService shareService;
    private final UserBriefLoader briefLoader;
    private final FulfilmentNotifier notifier;
    private final SnowflakeIdGenerator idGenerator;
    private final int maxContentLength;

    public ShareCommentService(ShareCommentMapper commentMapper,
                               ShareEngagementMapper engagementMapper,
                               ShareMapper shareMapper,
                               ShareService shareService,
                               UserBriefLoader briefLoader,
                               FulfilmentNotifier notifier,
                               SnowflakeIdGenerator idGenerator,
                               @Value("${duoduo.share.max-comment-length:300}") int maxContentLength) {
        this.commentMapper = commentMapper;
        this.engagementMapper = engagementMapper;
        this.shareMapper = shareMapper;
        this.shareService = shareService;
        this.briefLoader = briefLoader;
        this.notifier = notifier;
        this.idGenerator = idGenerator;
        this.maxContentLength = maxContentLength;
    }

    // ---------------- 写：发表评论 / 回复（需登录） ----------------

    /**
     * 发表一级评论（{@code parentCommentId} 为空）或回复（非空）。
     *
     * <p>评论数 +1 与通知推送都在这里：
     * <ul>
     *   <li>计数是<b>主流程的一部分</b>（信息流卡片上的数字要立刻对得上），
     *       所以不用 try/catch 吞掉；</li>
     *   <li>通知是<b>附属动作</b>，交给 {@code FulfilmentNotifier} 吞异常。</li>
     * </ul>
     */
    public CommentView create(Long userId, Long shareId, CommentCreateRequest request) {
        if (userId == null) {
            throw BizException.unauthorized("请先登录后再评论");
        }
        ShareEntity share = shareService.requireVisible(shareId);
        String content = normalizeContent(request.content());

        ShareCommentEntity parent = null;
        if (request.parentCommentId() != null) {
            parent = commentMapper.selectById(request.parentCommentId());
            if (parent == null || parent.getDeletedAt() != null) {
                throw BizException.notFound("要回复的评论");
            }
            if (!Objects.equals(parent.getShareId(), shareId)) {
                // 明确 400 而不是 404：这类错误几乎总是前端把上下文串了，
                // 给出「不属于同一条分享」比「不存在」更容易定位
                throw BizException.validation("要回复的评论不属于这条分享");
            }
            if (parent.getParentCommentId() != null) {
                throw BizException.businessRule("只能回复一级评论，暂不支持回复回复");
            }
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ShareCommentEntity entity = new ShareCommentEntity();
        entity.setId(idGenerator.nextId());
        entity.setShareId(shareId);
        entity.setUserId(userId);
        entity.setParentCommentId(request.parentCommentId());
        entity.setContent(content);
        entity.setLikeCount(0);
        entity.setCreatedAt(now);
        commentMapper.insert(entity);

        // 评论数：一级与回复都算（与卡片上那个数字的语义一致）
        engagementMapper.bumpCommentCount(shareId, 1);

        notifyQuietly(share, entity, parent);

        log.info("用户 {} 评论了分享 {}（评论 {}，父评论 {}）",
                userId, shareId, entity.getId(), request.parentCommentId());
        // 回执必须带昵称头像：前端拿这条直接 unshift 进列表，空昵称会渲染成「用户3612…」那种长号，
        // 与随后重拉列表里的显示不一致，看起来像「刚发的评论显示错了」
        List<Long> briefIds = new ArrayList<>();
        briefIds.add(entity.getUserId());
        if (parent != null) {
            briefIds.add(parent.getUserId());
        }
        return toView(entity, share, briefLoader.load(briefIds), Map.of(), parent, 0L, userId);
    }

    /**
     * 发表后的通知（best-effort，绝不抛出）。
     *
     * <p>发给谁：
     * <ul>
     *   <li><b>一级评论</b> → 分享作者；</li>
     *   <li><b>回复</b> → 被回复评论的作者；若该人不是分享作者，**同时**也通知分享作者 ——
     *       「有人在你分享下面聊起来了」对作者同样有意义，而批量端点一次就能带上两条。</li>
     * </ul>
     *
     * <p>三种不推的情况：自己评论自己的分享、自己回复自己、分享作者就是被回复者（去重）。
     */
    private void notifyQuietly(ShareEntity share, ShareCommentEntity comment, ShareCommentEntity parent) {
        if (share == null) {
            return;
        }
        Long shareAuthor = share.getUserId();
        Long actor = comment.getUserId();
        String actorName = briefLoader.nicknameOf(briefLoader.load(List.of(actor)), actor);

        if (parent == null) {
            if (!Objects.equals(shareAuthor, actor)) {
                notifier.notifyShareCommented(shareAuthor, share.getId(), actorName, comment.getContent());
            }
            return;
        }
        Long parentAuthor = parent.getUserId();
        Set<Long> targets = new LinkedHashSet<>();
        if (parentAuthor != null && !Objects.equals(parentAuthor, actor)) {
            targets.add(parentAuthor);
        }
        if (shareAuthor != null && !Objects.equals(shareAuthor, actor) && !targets.contains(shareAuthor)) {
            targets.add(shareAuthor);
        }
        for (Long target : targets) {
            if (Objects.equals(target, parentAuthor)) {
                notifier.notifyShareReplied(target, share.getId(), actorName, comment.getContent());
            } else {
                notifier.notifyShareCommented(target, share.getId(), actorName, comment.getContent());
            }
        }
    }

    // ---------------- 写：评论点赞 toggle（需登录） ----------------

    /** 与分享点赞同一套语义：事实行的存在性就是状态，重复点击撞主键幂等 */
    public EngagementState toggleLike(Long userId, Long commentId) {
        if (userId == null) {
            throw BizException.unauthorized("请先登录");
        }
        ShareCommentEntity comment = commentMapper.selectById(commentId);
        if (comment == null || comment.getDeletedAt() != null) {
            throw BizException.notFound("评论");
        }
        boolean active = commentMapper.likeExists(commentId, userId) > 0;
        int delta;
        if (active) {
            int removed = commentMapper.deleteLike(commentId, userId);
            delta = removed > 0 ? -1 : 0;
        } else {
            int inserted = commentMapper.insertLike(commentId, userId);
            delta = inserted > 0 ? 1 : 0;
        }
        if (delta != 0) {
            commentMapper.bumpLikeCount(commentId, delta);
        }
        ShareCommentEntity fresh = commentMapper.selectById(commentId);
        long count = fresh == null || fresh.getLikeCount() == null ? 0 : fresh.getLikeCount();
        return new EngagementState(!active, count);
    }

    // ---------------- 读：公开 ----------------

    /**
     * 评论列表。
     *
     * <p>{@code parentId} 为空 = 一级评论分页（每条带回复数）；非空 = 该评论下的回复分页。
     * 合成一个端点是因为两者的过滤、排序、装配逻辑完全一样，只差一个 where 条件 ——
     * 拆两个端点会把同一段装配逻辑写两遍。
     */
    public PageResult<CommentView> list(Long viewerId, Long shareId, Long parentId, int page, int pageSize) {
        shareService.requireVisible(shareId);
        int pageNo = Math.max(1, page);
        int size = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);

        LambdaQueryWrapper<ShareCommentEntity> wrapper = new LambdaQueryWrapper<ShareCommentEntity>()
                .eq(ShareCommentEntity::getShareId, shareId)
                .isNull(ShareCommentEntity::getDeletedAt);
        if (parentId == null) {
            wrapper.isNull(ShareCommentEntity::getParentCommentId);
        } else {
            wrapper.eq(ShareCommentEntity::getParentCommentId, parentId);
        }
        Page<ShareCommentEntity> result = commentMapper.selectPage(new Page<>(pageNo, size),
                wrapper.orderByAsc(ShareCommentEntity::getCreatedAt).orderByAsc(ShareCommentEntity::getId));

        // 一级列表需要回复数；回复列表不需要（回复本身没有下级）
        Map<Long, Long> replyCounts = parentId == null
                ? countReplies(result.getRecords())
                : Map.of();

        // 回复列表要显示「回复 @某某」，得把被回复的父评论作者一并取来
        Map<Long, ShareCommentEntity> parents = parentId == null
                ? Map.of()
                : loadParents(result.getRecords());

        ShareEntity share = shareMapper.selectById(shareId);
        Map<Long, UserBrief> briefs = loadAllBriefs(result.getRecords(), parents);
        Map<Long, Boolean> liked = loadLiked(viewerId, result.getRecords());

        List<CommentView> views = new ArrayList<>(result.getRecords().size());
        for (ShareCommentEntity entity : result.getRecords()) {
            ShareCommentEntity parent = entity.getParentCommentId() == null
                    ? null : parents.get(entity.getParentCommentId());
            views.add(toView(entity, share, briefs, liked, parent,
                    replyCounts.getOrDefault(entity.getId(), 0L), viewerId));
        }
        return PageResult.of(views, result.getTotal(), pageNo, size);
    }

    // ---------------- 内部：校验与装配 ----------------

    private String normalizeContent(String content) {
        if (content == null) {
            throw BizException.validation("评论内容不能为空");
        }
        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            throw BizException.validation("评论内容不能为空");
        }
        if (trimmed.length() > maxContentLength) {
            throw BizException.validation("评论不能超过 " + maxContentLength + " 字");
        }
        return trimmed;
    }

    /** 批量取一级评论的回复数（一次 IN 查询，不逐条 COUNT） */
    private Map<Long, Long> countReplies(List<ShareCommentEntity> records) {
        List<Long> ids = records.stream().map(ShareCommentEntity::getId).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        String csv = ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        Map<Long, Long> counts = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : commentMapper.countReplies(csv)) {
            Object key = row.get("parent_comment_id");
            Object value = row.get("cnt");
            if (key == null || value == null) {
                continue;
            }
            counts.put(asLong(key), asLong(value));
        }
        return counts;
    }

    private Map<Long, ShareCommentEntity> loadParents(List<ShareCommentEntity> replies) {
        List<Long> parentIds = replies.stream()
                .map(ShareCommentEntity::getParentCommentId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (parentIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, ShareCommentEntity> map = new java.util.LinkedHashMap<>();
        for (ShareCommentEntity parent : commentMapper.selectBatchIds(parentIds)) {
            map.put(parent.getId(), parent);
        }
        return map;
    }

    private Map<Long, UserBrief> loadAllBriefs(List<ShareCommentEntity> records,
                                               Map<Long, ShareCommentEntity> parents) {
        Collection<Long> ids = new ArrayList<>();
        records.forEach(r -> ids.add(r.getUserId()));
        parents.values().forEach(p -> ids.add(p.getUserId()));
        return briefLoader.load(ids);
    }

    private Map<Long, Boolean> loadLiked(Long viewerId, List<ShareCommentEntity> records) {
        if (viewerId == null || records.isEmpty()) {
            return Map.of();
        }
        String csv = records.stream().map(r -> String.valueOf(r.getId()))
                .collect(java.util.stream.Collectors.joining(","));
        Map<Long, Boolean> map = new java.util.LinkedHashMap<>();
        for (Long id : commentMapper.likedCommentIds(viewerId, csv)) {
            map.put(id, true);
        }
        return map;
    }

    private CommentView toView(ShareCommentEntity entity, ShareEntity share, Map<Long, UserBrief> briefs,
                               Map<Long, Boolean> liked, ShareCommentEntity parent,
                               long replyCount, Long viewerId) {
        return new CommentView(
                entity.getId(),
                entity.getShareId(),
                entity.getUserId(),
                briefLoader.nicknameOf(briefs, entity.getUserId()),
                briefLoader.avatarOf(briefs, entity.getUserId()),
                entity.getContent(),
                entity.getCreatedAt() == null ? null : entity.getCreatedAt().toInstant(ZoneOffset.UTC).toString(),
                entity.getLikeCount() == null ? 0L : entity.getLikeCount(),
                liked.containsKey(entity.getId()),
                // 「是不是我发的」由服务端算：前端要自己比就得先知道自己的雪花 id（19 位，JS 精度敏感）
                viewerId != null && Objects.equals(viewerId, entity.getUserId()),
                parent == null ? null : briefLoader.nicknameOf(briefs, parent.getUserId()),
                replyCount);
    }

    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}
