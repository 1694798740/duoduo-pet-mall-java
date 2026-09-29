package com.duoduo.fulfilment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.fulfilment.dto.ShareDtos.TopicView;
import com.duoduo.fulfilment.entity.ShareTopicEntity;
import com.duoduo.fulfilment.mapper.ShareTopicMapper;
import com.duoduo.fulfilment.mapper.ShareTopicRelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 分享话题标签服务（第五十六轮）。
 *
 * <p>职责边界：本服务只管「话题」这一侧（规范化、挂载、替换、热门榜、按名查），
 * <b>不管分享列表的组装</b> —— 话题聚合页要返回带动互动标记的 {@code ShareView}，
 * 那是 {@code ShareService} 的活。这样切分的直接好处是两者不互相依赖，
 * 不会出现「ShareService 调 TopicService、TopicService 又调回 ShareService」的循环。
 *
 * <p>三条设计取舍：
 * <ol>
 *   <li><b>「查不到就建」用 INSERT 撞键回查，不用先 SELECT 再 INSERT</b>：
 *       两个用户同时首发同一个话题时，先查后插会让其中一个拿到 500；
 *       撞唯一键回查则两人都成功，这本来就是正常行为；</li>
 *   <li><b>话题名规范在服务端，不信任前端</b>：前端会按空格切分、自动补 #，
 *       但那只是输入体验 —— 服务端仍要自己剥 #、去空白、去重、限长；</li>
 *   <li><b>计数是冗余列，回退用 GREATEST 防负漂移</b>（与点赞计数同一纪律）。</li>
 * </ol>
 */
@Service
public class ShareTopicService {

    private static final Logger log = LoggerFactory.getLogger(ShareTopicService.class);

    /** 单条分享最多带的话题数（产品口径：多到 5 个以上就不再是「话题」而是堆关键词） */
    public static final int MAX_TOPICS_PER_SHARE = 2;

    private static final int MAX_NAME_LENGTH = 30;

    private final ShareTopicMapper topicMapper;
    private final ShareTopicRelMapper relMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final int maxTopics;

    public ShareTopicService(ShareTopicMapper topicMapper,
                             ShareTopicRelMapper relMapper,
                             SnowflakeIdGenerator idGenerator,
                             @Value("${duoduo.share.max-topics:2}") int maxTopics) {
        this.topicMapper = topicMapper;
        this.relMapper = relMapper;
        this.idGenerator = idGenerator;
        this.maxTopics = maxTopics <= 0 ? MAX_TOPICS_PER_SHARE : maxTopics;
    }

    // ---------------- 规范化 ----------------

    /**
     * 规范化话题列表：剥 # 号、去首尾空白、按原始顺序去重、校验长度与字符、截到上限。
     *
     * <p><b>超上限是「截断」而不是「报错」</b>：用户多打了一个话题却整条分享发不出去，
     * 是糟糕的体验。前端已经在输入时拦一道，服务端这层只做兜底，
     * 兜底的方式选择「结果可预期」而不是「整单失败」。
     *
     * <p>非法字符（空白、#、控制字符）与超长名一律<b>丢弃该话题</b>而非报错，理由同上；
     * 但若清理后一个都不剩，就是一个合法结果（无话题的分享是允许的）。
     */
    public List<String> normalize(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String item : raw) {
            if (item == null) {
                continue;
            }
            String name = item.trim();
            // 剥掉用户自己打的 #（前端多半已经剥过一次，这里兜底）
            while (name.startsWith("#")) {
                name = name.substring(1).trim();
            }
            if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
                continue;
            }
            if (illegal(name)) {
                continue;
            }
            seen.add(name);
            if (seen.size() >= maxTopics) {
                break;
            }
        }
        return List.copyOf(seen);
    }

    /** 话题名不允许空白 / # / 控制字符：它们会让前端按 # 解析时切错，也会让展示串行 */
    private boolean illegal(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isWhitespace(c) || c == '#' || Character.isISOControl(c)) {
                return true;
            }
        }
        return false;
    }

    // ---------------- 挂载 / 替换 ----------------

    /** 发布时挂话题（分享刚落库，此前没有任何关系行） */
    public void attach(Long shareId, List<String> topics) {
        if (topics == null || topics.isEmpty()) {
            return;
        }
        for (String name : topics) {
            Long topicId = requireTopicId(name);
            if (relMapper.insertIgnore(shareId, topicId) > 0) {
                topicMapper.bumpShareCount(topicId, 1);
            }
        }
    }

    /**
     * 编辑时替换话题（第五十六轮）。
     *
     * <p>做法是「先算差集、再逐条加 / 减」，不是「全删再全加」：
     * 后者会让话题计数抖动（先 -1 再 +1），在并发编辑下甚至可能因为中间态把计数算错。
     * 差集还有一个好处 —— 只改文字不动话题的编辑，这里一次写都不会发生。
     */
    public void replace(Long shareId, List<String> topics) {
        List<String> next = topics == null ? List.of() : topics;
        Set<Long> current = new HashSet<>(relMapper.topicIdsOf(shareId));
        if (current.isEmpty() && next.isEmpty()) {
            return;
        }
        // 先解析新话题的名字 → id（含按需新建）
        Map<String, Long> nextIds = new LinkedHashMap<>();
        for (String name : next) {
            nextIds.put(name, requireTopicId(name));
        }
        Set<Long> nextSet = new HashSet<>(nextIds.values());

        // 移除不再需要的
        relMapper.deleteByShare(shareId);
        for (Long topicId : current) {
            if (!nextSet.contains(topicId)) {
                topicMapper.bumpShareCount(topicId, -1);
            }
        }
        // 挂上新的（current 里已有的不重复计数）
        for (Long topicId : nextSet) {
            relMapper.insertIgnore(shareId, topicId);
            if (!current.contains(topicId)) {
                topicMapper.bumpShareCount(topicId, 1);
            }
        }
    }

    /** 分享被删除时：把它用过的话题计数各 -1（话题本身保留，历史话题不因内容下架而消失） */
    public void detachAll(Long shareId) {
        List<Long> topicIds = relMapper.topicIdsOf(shareId);
        relMapper.deleteByShare(shareId);
        for (Long topicId : topicIds) {
            topicMapper.bumpShareCount(topicId, -1);
        }
    }

    /**
     * 取话题 id；不存在就建。
     *
     * <p><b>撞唯一键不是错误</b>：并发下另一个请求刚建好同名话题，我们回查即可 ——
     * 这比「先查再插」少一次往返，也没有竞态窗口。
     */
    private Long requireTopicId(String name) {
        ShareTopicEntity existing = topicMapper.selectOne(new LambdaQueryWrapper<ShareTopicEntity>()
                .eq(ShareTopicEntity::getName, name));
        if (existing != null) {
            return existing.getId();
        }
        ShareTopicEntity entity = new ShareTopicEntity();
        entity.setId(idGenerator.nextId());
        entity.setName(name);
        entity.setShareCount(0);
        entity.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        try {
            topicMapper.insert(entity);
            return entity.getId();
        } catch (DuplicateKeyException e) {
            ShareTopicEntity raced = topicMapper.selectOne(new LambdaQueryWrapper<ShareTopicEntity>()
                    .eq(ShareTopicEntity::getName, name));
            if (raced == null) {
                throw e;
            }
            log.info("话题 {} 被并发创建，复用已有行 id={}", name, raced.getId());
            return raced.getId();
        }
    }

    // ---------------- 读 ----------------

    /** 单条分享的话题名（按话题 id 升序 = 创建顺序，展示稳定） */
    public List<String> namesOf(Long shareId) {
        List<Long> ids = relMapper.topicIdsOf(shareId);
        if (ids.isEmpty()) {
            return List.of();
        }
        return topicMapper.selectBatchIds(ids).stream()
                .map(ShareTopicEntity::getName)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 批量取话题名（信息流装配用）。
     *
     * <p>两次查询搞定一页：一次取关系行，一次取话题名 —— 不做逐条查询（N+1）。
     */
    public Map<Long, List<String>> namesOfBatch(List<Long> shareIds) {
        if (shareIds == null || shareIds.isEmpty()) {
            return Map.of();
        }
        String ids = shareIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        List<Map<String, Object>> rels = relMapper.relsOf(ids);
        if (rels.isEmpty()) {
            return Map.of();
        }
        Set<Long> topicIds = new LinkedHashSet<>();
        for (Map<String, Object> rel : rels) {
            topicIds.add(asLong(rel.get("topic_id")));
        }
        Map<Long, String> names = new LinkedHashMap<>();
        for (ShareTopicEntity topic : topicMapper.selectBatchIds(new ArrayList<>(topicIds))) {
            names.put(topic.getId(), topic.getName());
        }
        Map<Long, List<String>> result = new LinkedHashMap<>();
        for (Map<String, Object> rel : rels) {
            Long shareId = asLong(rel.get("share_id"));
            String name = names.get(asLong(rel.get("topic_id")));
            if (shareId == null || name == null) {
                continue;
            }
            result.computeIfAbsent(shareId, k -> new ArrayList<>()).add(name);
        }
        return result;
    }

    /** 按名查话题；不存在返回 null（聚合页据此给 404） */
    public ShareTopicEntity findByName(String name) {
        List<String> normalized = normalize(List.of(name == null ? "" : name));
        if (normalized.isEmpty()) {
            return null;
        }
        return topicMapper.selectOne(new LambdaQueryWrapper<ShareTopicEntity>()
                .eq(ShareTopicEntity::getName, normalized.get(0)));
    }

    /** 热门话题榜（按被使用次数倒序；次数相同按 id 倒序保证顺序稳定） */
    public List<TopicView> hot(int limit) {
        int size = limit <= 0 ? 10 : Math.min(limit, 50);
        return topicMapper.selectList(new LambdaQueryWrapper<ShareTopicEntity>()
                        .orderByDesc(ShareTopicEntity::getShareCount)
                        .orderByDesc(ShareTopicEntity::getId)
                        .last("LIMIT " + size))
                .stream()
                .map(t -> new TopicView(t.getName(), t.getShareCount() == null ? 0L : t.getShareCount()))
                .toList();
    }

    /** 非法名直接拒绝（供聚合页入口做显式 400，而不是静默查不到） */
    public void requireValidName(String name) {
        if (normalize(List.of(name == null ? "" : name)).isEmpty()) {
            throw BizException.validation("话题名不合法（1~" + MAX_NAME_LENGTH + " 个字符，不含空格与 #）");
        }
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(String.valueOf(value));
    }
}
