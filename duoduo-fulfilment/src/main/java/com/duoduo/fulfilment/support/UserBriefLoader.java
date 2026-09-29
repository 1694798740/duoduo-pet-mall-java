package com.duoduo.fulfilment.support;

import com.duoduo.fulfilment.client.UserClient;
import com.duoduo.fulfilment.client.dto.UserBrief;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 昵称 / 头像批量装配件（第五十六轮抽取）。
 *
 * <p><b>为什么抽出来</b>：分享列表、评论列表、举报处理页都要「一批 userId → 昵称头像」，
 * 而这里有一条必须一致的降级策略 —— <b>用户服务不可用时降级为「用户 {id}」而不是让页面打不开</b>。
 * 复制三份的代价不是代码量，而是「有人改了一份、另两份没改」，页面上就会出现
 * 一会儿是昵称一会儿是「用户 710000000000000001」的割裂感。
 *
 * <p>批量而非逐条：一页 20 条逐条回源就是 20 次跨进程往返（详见 {@code UserClient} 的注释）。
 */
@Component
public class UserBriefLoader {

    private static final Logger log = LoggerFactory.getLogger(UserBriefLoader.class);

    private final UserClient userClient;

    public UserBriefLoader(UserClient userClient) {
        this.userClient = userClient;
    }

    /** 批量取昵称头像；任何异常都降级为空表（调用方用 {@link #nicknameOf} 得到「用户 {id}」） */
    public Map<Long, UserBrief> load(Collection<Long> userIds) {
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

    /** 展示昵称：拿不到就退化成「用户 {id}」，绝不返回 null（前端会渲染成空白） */
    public String nicknameOf(Map<Long, UserBrief> briefs, Long userId) {
        if (userId == null) {
            return null;
        }
        UserBrief brief = briefs.get(userId);
        return brief != null && brief.nickname() != null ? brief.nickname() : "用户" + userId;
    }

    public String avatarOf(Map<Long, UserBrief> briefs, Long userId) {
        UserBrief brief = userId == null ? null : briefs.get(userId);
        return brief == null ? null : brief.avatar();
    }
}
