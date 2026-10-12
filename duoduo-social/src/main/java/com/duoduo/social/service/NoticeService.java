package com.duoduo.social.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.social.dto.SocialDtos;
import com.duoduo.social.dto.SocialDtos.AnnounceRequest;
import com.duoduo.social.dto.SocialDtos.NoticePage;
import com.duoduo.social.dto.SocialDtos.NoticePushItem;
import com.duoduo.social.dto.SocialDtos.NoticeView;
import com.duoduo.social.dto.SocialDtos.UnreadCount;
import com.duoduo.social.entity.SocialMessageEntity;
import com.duoduo.social.entity.SocialNoticeEntity;
import com.duoduo.social.entity.SocialNoticeReadEntity;
import com.duoduo.social.mapper.SocialNoticeMapper;
import com.duoduo.social.mapper.SocialNoticeReadMapper;
import com.duoduo.social.support.MiniProgramPages;
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
 *
 * <p><b>第五十五轮：补齐「生产者」</b>。此前本域只有读路径与种子公告 —— 页面能打开、
 * 永远没有新通知，因为<b>没有任何服务往里写</b>。本轮补上两个来源：
 * <ul>
 *   <li>{@link #pushBatch}：内部端点（交易域 / 履约域 / 分享圈推事件）；</li>
 *   <li>{@link #pushMessageNotice}：私信到达（本域内部产生，同会话未读合并为一条）。</li>
 * </ul>
 */
@Service
public class NoticeService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 单批推送上限：内部端点是**批量写入**入口，必须有上界，防止一次调用把库写爆 */
    private static final int MAX_PUSH_BATCH = 100;

    /** 合并判定回看条数：同一来源下历史已读行可能累积，取最近的若干条足够找到未读那条 */
    private static final int MERGE_LOOKBACK = 20;

    /** 列长上限（与 t_social_notice DDL 对齐：title 100 / content 500 / biz_type 30 / biz_id 64 / jump_page 150） */
    private static final int TITLE_MAX = 100;
    private static final int CONTENT_MAX = 500;
    private static final int BIZ_TYPE_MAX = 30;
    private static final int BIZ_ID_MAX = 64;

    /** 合法分类（与表注释一致）：内部推送写错分类直接 400，不静默落库 */
    private static final Set<String> CATEGORIES = Set.of(
            SocialDtos.NOTICE_SYSTEM, SocialDtos.NOTICE_ANNOUNCEMENT,
            SocialDtos.NOTICE_FRIEND, SocialDtos.NOTICE_MERCHANT, SocialDtos.NOTICE_ORDER);

    /** 私信分流依据：这几种角色发来的消息归「商家消息」，其余归「好友消息」 */
    private static final Set<String> MERCHANT_ROLES = Set.of("SELLER", "OPERATOR", "ADMIN");

    private final SocialNoticeMapper noticeMapper;
    private final SocialNoticeReadMapper noticeReadMapper;
    private final SnowflakeIdGenerator idGenerator;

    public NoticeService(SocialNoticeMapper noticeMapper,
                         SocialNoticeReadMapper noticeReadMapper,
                         SnowflakeIdGenerator idGenerator) {
        this.noticeMapper = noticeMapper;
        this.noticeReadMapper = noticeReadMapper;
        this.idGenerator = idGenerator;
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

    /**
     * 通知未读数 —— <b>用于 tabBar 角标</b>（定向未读 + 我没读过的公告数）。
     *
     * <p><b>必须排除私信通知（bizType=MESSAGE，第五十五轮）</b>：私信到达会写一条
     * MESSAGE 通知，而同一批私信**又**按条计入「私信未读」（t_social_message.read_at）。
     * 两处都算，一条消息会被数成两个未读 —— 角标 2 实际只有 1 条消息，
     * 这正是第五十五轮实测发现的问题。合并口径固定为
     * 「通知（不含私信）+ 私信未读」，所以这里要把私信通知摘出去。
     *
     * <p>它仍然留在通知列表里（见 {@link #list}）：作为「有人找你」的引导，点击直达聊天。
     * 也就是说：<b>列表看得到、但不再重复计数</b>。
     */
    public UnreadCount unreadCount(Long userId) {
        long directedTotal = countForBadge(userId, null);
        long announcementTotal = countForBadge(0L, null);
        long readCount = 0;
        if (directedTotal + announcementTotal > 0) {
            List<Long> allIds = new ArrayList<>();
            noticeMapper.selectList(new LambdaQueryWrapper<SocialNoticeEntity>()
                            .in(SocialNoticeEntity::getUserId, List.of(userId, 0L))
                            .and(excludeMessageNotice())
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

    // ---------------- 写：推送（第五十五轮，内部端点 + 私信） ----------------

    /**
     * 批量推送通知（内部端点）：交易域 / 履约域 / 分享圈把「事件」落成收件人的通知。
     *
     * <p><b>先全校验、再统一落库</b>：如果边校验边插，第 3 条非法时前 2 条已经写进去了，
     * 调用方拿到 400 以为「整批没成」—— 于是要么漏掉那 2 条，要么重推一次造成重复。
     * 两阶段让「失败 = 什么都没发生」，语义干净。
     *
     * <p><b>不做合并去重</b>：事件型通知（下单一次、发货一次）本身一对一。
     * 调用方重复推同一事件属于调用方的 bug，应该暴露而不是掩盖；
     * 真正需要合并的高频来源（私信）由 {@link #pushMessageNotice} 单独处理。
     */
    public long pushBatch(List<NoticePushItem> items) {
        if (items == null || items.isEmpty()) {
            throw BizException.validation("通知列表不能为空");
        }
        if (items.size() > MAX_PUSH_BATCH) {
            throw BizException.validation("单次最多推送 " + MAX_PUSH_BATCH + " 条通知");
        }
        LocalDateTime now = nowUtc();
        List<SocialNoticeEntity> entities = new ArrayList<>(items.size());
        for (NoticePushItem item : items) {
            entities.add(toEntity(item, now));
        }
        for (SocialNoticeEntity entity : entities) {
            noticeMapper.insert(entity);
        }
        return entities.size();
    }

    /**
     * 发全站公告（运营用）：收件人由服务端固定为 0，不给调用方填 ——
     * 公告写错收件人 = 全站可见，代价太大，不如把这个字段从契约里去掉。
     */
    public long announce(AnnounceRequest request) {
        return pushBatch(List.of(new NoticePushItem(
                0L,
                SocialDtos.NOTICE_ANNOUNCEMENT,
                request.title(),
                request.content(),
                request.bizType(),
                request.bizId(),
                request.jumpPage())));
    }

    /**
     * 私信到达通知：发完消息后给收件人写一条 FRIEND / MERCHANT 通知。
     *
     * <p>规则：
     * <ol>
     *   <li><b>按发送方角色分流</b>：卖家 / 运营 / 管理员 → 商家消息，普通用户 → 好友消息。
     *       角色取消息行上的快照（发送时定格），所以通知分类与消息行永远是同刻一致，
     *       不会出现「当时是用户、后来成了卖家，历史通知跟着变」；</li>
     *   <li><b>同一会话的未读合并成一条</b>：通知中心是「事件流」不是「消息流」。
     *       对方连发 10 条，语义上是「TA 有话说」，不是 10 个事件 —— 不合并的话，
     *       未读角标会随聊天条数线性膨胀（用户看到 27，点进去只来自两个人，
     *       第一反应是「未读数坏了」）。合并的边界是「未读」：用户读完之后，
     *       下一条消息会重新派生一条新通知，所以不会永远只显示一条。</li>
     * </ol>
     *
     * <p>由 {@link MessageService} 以 best-effort 调用：写通知失败绝不能影响消息发送结果
     * （消息已落库，此时若把发送判为失败，用户看到的「发送失败」与事实相反）。
     */
    public void pushMessageNotice(SocialMessageEntity message) {
        Long receiverId = message.getReceiverId();
        String category = MERCHANT_ROLES.contains(message.getSenderRole())
                ? SocialDtos.NOTICE_MERCHANT : SocialDtos.NOTICE_FRIEND;
        String title = SocialDtos.NOTICE_MERCHANT.equals(category) ? "商家消息" : "好友消息";
        String content = abbreviate(message.getContent(), CONTENT_MAX);
        String bizId = message.getConversationId();
        String jumpPage = "/pages/chat/chat?peerId=" + message.getSenderId();
        LocalDateTime now = nowUtc();

        SocialNoticeEntity unread = findUnread(receiverId, category, SocialDtos.NOTICE_BIZ_MESSAGE, bizId);
        if (unread != null) {
            // 原地更新：id 不变 → 已读集合里的记录依然指向这条（不会凭空多出一条未读）
            unread.setTitle(title);
            unread.setContent(content);
            unread.setJumpPage(jumpPage);
            unread.setCreatedAt(now);
            noticeMapper.updateById(unread);
            return;
        }
        SocialNoticeEntity entity = new SocialNoticeEntity();
        entity.setId(idGenerator.nextId());
        entity.setUserId(receiverId);
        entity.setCategory(category);
        entity.setTitle(title);
        entity.setContent(content);
        entity.setBizType(SocialDtos.NOTICE_BIZ_MESSAGE);
        entity.setBizId(bizId);
        entity.setJumpPage(jumpPage);
        entity.setCreatedAt(now);
        noticeMapper.insert(entity);
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

    /**
     * 内部推送单条 → 实体（含全部校验）。
     *
     * <p>校验强度是「服务间」而不是「面向用户」：长度超限、分类不在白名单、
     * 落地页不是真实页面 —— 一律 400 直说，不做静默截断或丢弃。
     * 理由：这些都是调用方的拼装错误，安静地修正只会把问题推迟到用户点击那一刻才暴露，
     * 而那时服务端已经没有痕迹可查了。
     */
    private SocialNoticeEntity toEntity(NoticePushItem item, LocalDateTime now) {
        Long userId = item.userId();
        if (userId == null || userId < 0) {
            throw BizException.validation("缺少收件人 userId（全站公告传 0）");
        }
        String category = optionalText(item.category(), 20, "通知分类");
        if (category == null || !CATEGORIES.contains(category)) {
            throw BizException.validation("通知分类不合法（应为 SYSTEM/ANNOUNCEMENT/FRIEND/MERCHANT/ORDER）：" + category);
        }
        String jumpPage = optionalText(item.jumpPage(), 150, "通知落地页");
        if (!MiniProgramPages.isValid(jumpPage)) {
            throw BizException.validation(
                    "通知落地页不是小程序真实页面：" + MiniProgramPages.pathOf(jumpPage)
                            + "（新增页面需同步 MiniProgramPages 清单）");
        }
        SocialNoticeEntity entity = new SocialNoticeEntity();
        entity.setId(idGenerator.nextId());
        entity.setUserId(userId);
        entity.setCategory(category);
        entity.setTitle(requireText(item.title(), TITLE_MAX, "通知标题"));
        entity.setContent(requireText(item.content(), CONTENT_MAX, "通知正文"));
        entity.setBizType(optionalText(item.bizType(), BIZ_TYPE_MAX, "通知业务类型"));
        entity.setBizId(optionalText(item.bizId(), BIZ_ID_MAX, "通知业务单号"));
        entity.setJumpPage(jumpPage);
        entity.setCreatedAt(now);
        return entity;
    }

    /**
     * 同一来源下「还没被读过」的通知：有则原地更新，无则新插一条 —— 私信合并的判定点。
     *
     * <p>回看最近 {@value #MERGE_LOOKBACK} 条：同一来源的历史已读行会累积
     * （读完之后再来消息就会新插一条），但**未读最多一条**（合并语义保证），
     * 所以只要在最近若干条里找到第一条未读即可，不必扫全表。
     */
    private SocialNoticeEntity findUnread(Long userId, String category, String bizType, String bizId) {
        List<SocialNoticeEntity> rows = noticeMapper.selectList(new LambdaQueryWrapper<SocialNoticeEntity>()
                .eq(SocialNoticeEntity::getUserId, userId)
                .eq(SocialNoticeEntity::getCategory, category)
                .eq(SocialNoticeEntity::getBizType, bizType)
                .eq(SocialNoticeEntity::getBizId, bizId)
                .orderByDesc(SocialNoticeEntity::getId)
                .last("LIMIT " + MERGE_LOOKBACK));
        if (rows.isEmpty()) {
            return null;
        }
        Set<Long> read = readIds(userId, rows);
        return rows.stream().filter(n -> !read.contains(n.getId())).findFirst().orElse(null);
    }

    private static String requireText(String value, int max, String label) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw BizException.validation(label + "不能为空");
        }
        if (trimmed.length() > max) {
            throw BizException.validation(label + "不能超过 " + max + " 字");
        }
        return trimmed;
    }

    private static String optionalText(String value, int max, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw BizException.validation(label + "不能超过 " + max + " 字");
        }
        return trimmed;
    }

    /** 通知摘要裁剪：正文列 VARCHAR(500)，而私信正文上限 1000 字 —— 超出按字数截断加省略号 */
    private static String abbreviate(String text, int max) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.isEmpty()) {
            return "（无内容）";
        }
        return normalized.length() <= max ? normalized : normalized.substring(0, max - 1) + "…";
    }

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

    /** 计入角标的通知数（排除私信通知，理由见 {@link #unreadCount}） */
    private long countForBadge(Long userId, String category) {
        LambdaQueryWrapper<SocialNoticeEntity> wrapper = new LambdaQueryWrapper<SocialNoticeEntity>()
                .eq(SocialNoticeEntity::getUserId, userId)
                .and(excludeMessageNotice());
        if (category != null && !category.isBlank()) {
            wrapper.eq(SocialNoticeEntity::getCategory, category.trim());
        }
        return noticeMapper.selectCount(wrapper);
    }

    /**
     * 「排除私信通知」的 WHERE 片段。
     *
     * <p>⚠️ 一个必须写出来的坑：SQL 里 {@code biz_type <> 'MESSAGE'} 对
     * {@code biz_type IS NULL} 的行返回的是 <b>NULL（即「不匹配」）</b>，
     * 而全站公告的 biz_type 恰好就是 NULL —— 少写 isNull 分支会把公告整类漏掉，
     * 线上表现为「公告的未读数永远为 0」，且不报任何错。所以条件必须是
     * {@code biz_type IS NULL OR biz_type <> 'MESSAGE'}。
     */
    private static java.util.function.Consumer<LambdaQueryWrapper<SocialNoticeEntity>> excludeMessageNotice() {
        return w -> w.isNull(SocialNoticeEntity::getBizType)
                .or().ne(SocialNoticeEntity::getBizType, SocialDtos.NOTICE_BIZ_MESSAGE);
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
