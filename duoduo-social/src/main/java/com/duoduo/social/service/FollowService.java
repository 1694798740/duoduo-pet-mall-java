package com.duoduo.social.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.exception.BizException;
import com.duoduo.social.client.UserClient;
import com.duoduo.social.client.dto.UserBrief;
import com.duoduo.social.dto.SocialDtos.FollowCounts;
import com.duoduo.social.dto.SocialDtos.FollowState;
import com.duoduo.social.dto.SocialDtos.FollowView;
import com.duoduo.social.dto.SocialDtos.ProfileView;
import com.duoduo.social.entity.SocialFollowEntity;
import com.duoduo.social.mapper.SocialFollowMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 关注服务（第四十七轮）。
 *
 * <p>规则集中在这里，控制器只做参数绑定：
 * <ol>
 *   <li><b>互相关注 = 两个方向各一行</b>：不引入「关系状态」列 —— 状态列要双向同步，
 *       A 侧改了 B 侧没改就是脏数据；两条行各自独立存在，天然一致；</li>
 *   <li><b>幂等靠复合主键</b>：重复关注撞键按「已关注」处理，取关删 0 行也算成功；
 *       与点赞 toggle 不同，关注是「单向表态」，重复点击没有「取消」语义，
 *       取消走独立的 DELETE 接口；</li>
 *   <li><b>计数不冗余</b>：粉丝数 / 关注数直接 COUNT —— 主页与列表页都是低频查询，
 *       省掉双向同步的一致性负担；</li>
 *   <li><b>目标必须存在</b>：关注一个不存在的用户没有意义（404）。</li>
 * </ol>
 */
@Service
public class FollowService {

    private static final Logger log = LoggerFactory.getLogger(FollowService.class);

    private static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 「我关注的人」id 列表的默认 / 上限条数（第五十六轮，见 {@link #followingIds}） */
    private static final int DEFAULT_FOLLOWING_LIMIT = 200;
    private static final int MAX_FOLLOWING_LIMIT = 500;

    private final SocialFollowMapper followMapper;
    private final UserClient userClient;

    public FollowService(SocialFollowMapper followMapper, UserClient userClient) {
        this.followMapper = followMapper;
        this.userClient = userClient;
    }

    // ---------------- 写：关注 / 取关（需登录） ----------------

    public FollowState follow(Long userId, Long targetId) {
        requireNotSelf(userId, targetId);
        requireUserExists(targetId);
        SocialFollowEntity existing = selectOne(userId, targetId);
        if (existing == null) {
            SocialFollowEntity entity = new SocialFollowEntity();
            entity.setFollowerId(userId);
            entity.setFolloweeId(targetId);
            entity.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
            followMapper.insert(entity);
            log.info("用户 {} 关注了用户 {}", userId, targetId);
        }
        return state(userId, targetId);
    }

    public FollowState unfollow(Long userId, Long targetId) {
        requireNotSelf(userId, targetId);
        int removed = followMapper.delete(new LambdaQueryWrapper<SocialFollowEntity>()
                .eq(SocialFollowEntity::getFollowerId, userId)
                .eq(SocialFollowEntity::getFolloweeId, targetId));
        if (removed > 0) {
            log.info("用户 {} 取消关注用户 {}", userId, targetId);
        }
        return state(userId, targetId);
    }

    // ---------------- 读：列表 / 状态 / 计数 ----------------

    /** 我关注的人（按关注时间倒序；mutual 供「互相关注」角标） */
    public PageResult<FollowView> following(Long userId, int page, int pageSize) {
        Page<SocialFollowEntity> result = followMapper.selectPage(new Page<>(Math.max(1, page), normalizeSize(pageSize)),
                new LambdaQueryWrapper<SocialFollowEntity>()
                        .eq(SocialFollowEntity::getFollowerId, userId)
                        .orderByDesc(SocialFollowEntity::getCreatedAt));
        return toPageResult(userId, result, SocialFollowEntity::getFolloweeId);
    }

    /** 我的粉丝（谁关注了我） */
    public PageResult<FollowView> followers(Long userId, int page, int pageSize) {
        Page<SocialFollowEntity> result = followMapper.selectPage(new Page<>(Math.max(1, page), normalizeSize(pageSize)),
                new LambdaQueryWrapper<SocialFollowEntity>()
                        .eq(SocialFollowEntity::getFolloweeId, userId)
                        .orderByDesc(SocialFollowEntity::getCreatedAt));
        return toPageResult(userId, result, SocialFollowEntity::getFollowerId);
    }

    /** 我与某人的关注关系（他人主页「关注」按钮的状态源） */
    public FollowState state(Long viewerId, Long targetId) {
        boolean following = selectOne(viewerId, targetId) != null;
        boolean followedBy = selectOne(targetId, viewerId) != null;
        return new FollowState(following, followedBy, following && followedBy);
    }

    /** 某人的关注计数（公开；不冗余、直接 COUNT，主页低频查询无所谓） */
    public FollowCounts counts(Long userId) {
        Long followers = followMapper.selectCount(new LambdaQueryWrapper<SocialFollowEntity>()
                .eq(SocialFollowEntity::getFolloweeId, userId));
        Long following = followMapper.selectCount(new LambdaQueryWrapper<SocialFollowEntity>()
                .eq(SocialFollowEntity::getFollowerId, userId));
        return new FollowCounts(nz(followers), nz(following));
    }

    /**
     * 「我关注的人」的 id 列表（第五十六轮，供分享圈的**关注流**使用）。
     *
     * <p><b>为什么要设上限</b>：调用方（履约域）拿这批 id 去拼
     * {@code WHERE user_id IN (...)}。关注几千人时这条 SQL 会变成超长 IN 列表，
     * 既慢又容易撞 MySQL 的包大小限制 —— 宁可只取最近关注的一批，
     * 让「关注流」在最坏情况下少几条内容，也不要让整个信息流查不出来。
     * 上限取 500：远超普通用户的关注数，同时 IN 列表仍在安全范围内。
     *
     * <p>只返回 id，不返回昵称头像 —— 调用方要的是过滤条件，不是展示数据。
     */
    public List<Long> followingIds(Long userId, int limit) {
        int capped = limit <= 0 ? DEFAULT_FOLLOWING_LIMIT : Math.min(limit, MAX_FOLLOWING_LIMIT);
        if (userId == null) {
            return List.of();
        }
        return followMapper.selectList(new LambdaQueryWrapper<SocialFollowEntity>()
                        .select(SocialFollowEntity::getFolloweeId)
                        .eq(SocialFollowEntity::getFollowerId, userId)
                        .orderByDesc(SocialFollowEntity::getCreatedAt)
                        .last("LIMIT " + capped))
                .stream()
                .map(SocialFollowEntity::getFolloweeId)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 他人主页聚合视图：资料 + 计数 + 我与 TA 的关系（一次拿齐）。
     *
     * <p>查看自己时关系恒为 false —— 复合主键决定了「自己关注自己」根本存不进去，
     * 这里显式短路而不是依赖查询结果，语义更直白。
     */
    public ProfileView profile(Long viewerId, Long targetId) {
        FollowCounts counts = counts(targetId);
        FollowState state = viewerId != null && !viewerId.equals(targetId)
                ? state(viewerId, targetId)
                : new FollowState(false, false, false);
        UserBrief brief = loadBriefs(List.of(targetId)).get(targetId);
        return new ProfileView(
                targetId,
                brief == null || brief.nickname() == null ? "用户" + targetId : brief.nickname(),
                brief == null ? null : brief.avatar(),
                counts.followers(),
                counts.following(),
                state.following(),
                state.followedBy(),
                state.mutual());
    }

    // ---------------- 内部：供消息域复用 ----------------

    /** 互相关注判定（私信「每人一条」规则的解锁条件） */
    public boolean mutual(Long a, Long b) {
        return selectOne(a, b) != null && selectOne(b, a) != null;
    }

    /**
     * 批量互关判定：返回 peers 中与 {@code userId} 互相关注的子集。
     *
     * <p>会话列表一页 10 条，逐条调 {@link #mutual} 就是 20 次查询（N+1）；
     * 这里两次 IN 查询取交集。
     */
    public Set<Long> mutualSet(Long userId, Collection<Long> peers) {
        List<Long> ids = peers.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Set.of();
        }
        Set<Long> iFollow = followMapper.selectList(new LambdaQueryWrapper<SocialFollowEntity>()
                        .eq(SocialFollowEntity::getFollowerId, userId)
                        .in(SocialFollowEntity::getFolloweeId, ids)).stream()
                .map(SocialFollowEntity::getFolloweeId)
                .collect(Collectors.toSet());
        if (iFollow.isEmpty()) {
            return Set.of();
        }
        Set<Long> followsMe = followMapper.selectList(new LambdaQueryWrapper<SocialFollowEntity>()
                        .eq(SocialFollowEntity::getFolloweeId, userId)
                        .in(SocialFollowEntity::getFollowerId, iFollow)).stream()
                .map(SocialFollowEntity::getFollowerId)
                .collect(Collectors.toSet());
        Set<Long> mutual = new HashSet<>(iFollow);
        mutual.retainAll(followsMe);
        return mutual;
    }

    // ---------------- 内部：校验与装配 ----------------

    private void requireNotSelf(Long userId, Long targetId) {
        if (targetId == null) {
            throw BizException.validation("缺少目标用户");
        }
        if (targetId.equals(userId)) {
            throw BizException.businessRule("不能关注自己");
        }
    }

    /** 目标必须存在：关注 / 给不存在的人发消息都没有意义（404） */
    private void requireUserExists(Long targetId) {
        if (loadBriefs(List.of(targetId)).isEmpty()) {
            throw BizException.notFound("用户");
        }
    }

    private SocialFollowEntity selectOne(Long followerId, Long followeeId) {
        return followMapper.selectOne(new LambdaQueryWrapper<SocialFollowEntity>()
                .eq(SocialFollowEntity::getFollowerId, followerId)
                .eq(SocialFollowEntity::getFolloweeId, followeeId));
    }

    /**
     * 批量取昵称头像（与履约域同一降级策略）：用户服务不可用降级为「用户 {id}」，
     * 不让整个关注列表打不开。
     */
    public Map<Long, UserBrief> loadBriefs(Collection<Long> userIds) {
        List<Long> ids = userIds.stream().filter(Objects::nonNull).distinct().toList();
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

    private PageResult<FollowView> toPageResult(Long userId, Page<SocialFollowEntity> result,
                                                java.util.function.Function<SocialFollowEntity, Long> peerOf) {
        List<Long> targets = result.getRecords().stream().map(peerOf).toList();
        Set<Long> mutual = mutualSet(userId, targets);
        Map<Long, UserBrief> briefs = loadBriefs(targets);
        List<FollowView> views = result.getRecords().stream()
                .map(e -> {
                    Long peer = peerOf.apply(e);
                    UserBrief brief = briefs.get(peer);
                    return new FollowView(
                            peer,
                            brief == null || brief.nickname() == null ? "用户" + peer : brief.nickname(),
                            brief == null ? null : brief.avatar(),
                            mutual.contains(peer),
                            iso(e.getCreatedAt()));
                })
                .toList();
        return PageResult.of(views, result.getTotal(), (int) result.getCurrent(), (int) result.getSize());
    }

    private int normalizeSize(int pageSize) {
        return pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private long nz(Long value) {
        return value == null ? 0L : value;
    }

    private String iso(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC).toString();
    }
}
