package com.duoduo.social.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duoduo.common.exception.BizException;
import com.duoduo.social.dto.SocialDtos.NoticePage;
import com.duoduo.social.dto.SocialDtos.NoticeView;
import com.duoduo.social.dto.SocialDtos.UnreadCount;
import com.duoduo.social.entity.SocialNoticeEntity;
import com.duoduo.social.entity.SocialNoticeReadEntity;
import com.duoduo.social.mapper.SocialNoticeMapper;
import com.duoduo.social.mapper.SocialNoticeReadMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 通知中心服务（第五十轮启用；表结构第四十七轮已建齐）。
 *
 * <p>规则集中在这里，控制器只做参数绑定：
 * <ol>
 *   <li><b>定向通知与公告统一为一个视图</b>：定向行（user_id = 我）与公告行
 *       （user_id = 0，一行多读者）的行上都没有已读列 —— 已读统一记在集合表
 *       (notice_id, user_id)，撞复合主键即已读，两种来源一套逻辑，前端无感；</li>
 *   <li><b>归属即一切</b>：定向通知只有收件人能读/能标记，他人行按 404 返回
 *       （与地址簿同一纪律：不区分「不存在」与「无权限」，避免枚举他人通知 ID）；</li>
 *   <li><b>已读幂等</b>：重复标记不报错（撞复合主键按已读吞掉）；</li>
 *   <li><b>两源合并分页</b>：v1 通知量级小（定向 + 公告各取前 page*pageSize 条内存归并切片），
 *       量级上来后再改 SQL UNION 分页 —— 先做对，再做大。</li>
 * </ol>
 */
@Service
public class NoticeService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final SocialNoticeMapper noticeMapper;
    private final SocialNoticeReadMapper noticeReadMapper;

    public NoticeService(SocialNoticeMapper noticeMapper, SocialNoticeReadMapper noticeReadMapper) {
        this.noticeMapper = noticeMapper;
        this.noticeReadMapper = noticeReadMapper;
    }

    // ---------------- 读：列表 / 未读数（需登录） ----------------

    public NoticePage list(Long userId, int page, int pageSize, String category) {
        int p = Math.max(page, 1);
        int size = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);

        // 两源各取前 p*size 条（足够覆盖切片窗口），内存归并 —— 见类注释第 4 条
        List<SocialNoticeEntity> directed = selectSource(userId, p * size, category);
        List<SocialNoticeEntity> announcements = selectSource(0L, p * size, category);

        List<SocialNoticeEntity> merged = new ArrayList<>(directed);
        merged.addAll(announcements);
        Set<Long> readIds = readIds(userId, merged);
        merged.sort(Comparator
                .comparing(SocialNoticeEntity::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(SocialNoticeEntity::getId, Comparator.reverseOrder()));

        long total = countSource(userId, category) + countSource(0L, category);

        int from = Math.min((p - 1) * size, merged.size());
        int to = Math.min(from + size, merged.size());
        List<NoticeView> items = merged.subList(from, to).stream()
                .map(n -> toView(n, readIds))
                .collect(Collectors.toList());
        return new NoticePage(items, total, p, size);
    }

    public UnreadCount unreadCount(Long userId) {
        long directedTotal = countSource(userId, null);
        long announcementTotal = countSource(0L, null);
        long readCount = 0;
        if (directedTotal + announcementTotal > 0) {
            List<Long> allIds = new ArrayList<>();
            noticeMapper.selectList(new LambdaQueryWrapper<SocialNoticeEntity>()
                            .in(SocialNoticeEntity::getUserId, List.of(userId, 0L))
                            .select(SocialNoticeEntity::getId))
                    .forEach(n -> allIds.add(n.getId()));
            if (!allIds.isEmpty()) {
                readCount = noticeReadMapper.selectCount(new LambdaQueryWrapper<SocialNoticeReadEntity>()
                        .eq(SocialNoticeReadEntity::getUserId, userId)
                        .in(SocialNoticeReadEntity::getNoticeId, allIds));
            }
        }
        return new UnreadCount(directedTotal + announcementTotal - readCount);
    }

    // ---------------- 写：标记已读（需登录，幂等） ----------------

    /** 标记单条已读：公告与定向通知统一写集合表。他人/不存在一律 404。 */
    public void markRead(Long userId, Long noticeId) {
        SocialNoticeEntity notice = noticeMapper.selectById(noticeId);
        if (notice == null || !(userId.equals(notice.getUserId()) || notice.getUserId() == 0L)) {
            throw BizException.notFound("通知");
        }
        insertRead(userId, noticeId);
    }

    /** 全部已读：我的定向通知 + 全部公告，未读的批量补进集合表 */
    public void readAll(Long userId) {
        List<SocialNoticeEntity> mine = selectSource(userId, MAX_PAGE_SIZE, null);
        List<SocialNoticeEntity> announcements = selectSource(0L, MAX_PAGE_SIZE, null);
        Set<Long> alreadyRead = readIds(userId, mine, announcements);
        for (SocialNoticeEntity n : mine) {
            insertRead(userId, n.getId(), alreadyRead);
        }
        for (SocialNoticeEntity n : announcements) {
            insertRead(userId, n.getId(), alreadyRead);
        }
    }

    // ---------------- 内部 ----------------

    private List<SocialNoticeEntity> selectSource(Long userId, int limit, String category) {
        LambdaQueryWrapper<SocialNoticeEntity> wrapper = new LambdaQueryWrapper<SocialNoticeEntity>()
                .eq(SocialNoticeEntity::getUserId, userId)
                .last("LIMIT " + Math.max(limit, 1));
        if (category != null && !category.isBlank()) {
            wrapper.eq(SocialNoticeEntity::getCategory, category.trim());
        }
        wrapper.orderByDesc(SocialNoticeEntity::getCreatedAt);
        return noticeMapper.selectList(wrapper);
    }

    private long countSource(Long userId, String category) {
        LambdaQueryWrapper<SocialNoticeEntity> wrapper = new LambdaQueryWrapper<SocialNoticeEntity>()
                .eq(SocialNoticeEntity::getUserId, userId);
        if (category != null && !category.isBlank()) {
            wrapper.eq(SocialNoticeEntity::getCategory, category.trim());
        }
        return noticeMapper.selectCount(wrapper);
    }

    /** 我在某批通知里的已读集合（一次查询，避免逐条 N+1） */
    private Set<Long> readIds(Long userId, List<SocialNoticeEntity> notices) {
        if (notices.isEmpty()) {
            return Set.of();
        }
        List<Long> ids = notices.stream().map(SocialNoticeEntity::getId).collect(Collectors.toList());
        return noticeReadMapper.selectList(new LambdaQueryWrapper<SocialNoticeReadEntity>()
                        .eq(SocialNoticeReadEntity::getUserId, userId)
                        .in(SocialNoticeReadEntity::getNoticeId, ids))
                .stream().map(SocialNoticeReadEntity::getNoticeId).collect(Collectors.toSet());
    }

    private Set<Long> readIds(Long userId, List<SocialNoticeEntity> a, List<SocialNoticeEntity> b) {
        List<SocialNoticeEntity> all = new ArrayList<>(a);
        all.addAll(b);
        return readIds(userId, all);
    }

    /** 写已读集合（幂等）：已在集合中跳过；并发撞复合主键按已读吞掉 */
    private void insertRead(Long userId, Long noticeId, Set<Long> alreadyRead) {
        if (alreadyRead.contains(noticeId)) {
            return;
        }
        insertRead(userId, noticeId);
    }

    private void insertRead(Long userId, Long noticeId) {
        SocialNoticeReadEntity read = new SocialNoticeReadEntity();
        read.setNoticeId(noticeId);
        read.setUserId(userId);
        read.setReadAt(nowUtc());
        try {
            noticeReadMapper.insert(read);
        } catch (DuplicateKeyException e) {
            // 撞复合主键 = 已读过，幂等返回
        }
    }

    private NoticeView toView(SocialNoticeEntity notice, Set<Long> readIds) {
        return new NoticeView(
                notice.getId(),
                notice.getCategory(),
                notice.getTitle(),
                notice.getContent(),
                notice.getBizType(),
                notice.getBizId(),
                notice.getJumpPage(),
                readIds.contains(notice.getId()),
                utc(notice.getCreatedAt())
        );
    }

    private static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static String utc(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC).toString();
    }
}
