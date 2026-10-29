package com.duoduo.ai.butler.service;

import com.duoduo.ai.butler.dto.ButlerViews.NotificationItem;
import com.duoduo.ai.butler.dto.ButlerViews.NotificationListData;
import com.duoduo.ai.client.NoticeClient;
import com.duoduo.ai.client.dto.ButlerNoticeDtos.BatchRequest;
import com.duoduo.ai.client.dto.ButlerNoticeDtos.BatchResult;
import com.duoduo.ai.client.dto.ButlerNoticeDtos.NoticePage;
import com.duoduo.ai.client.dto.ButlerNoticeDtos.NoticeView;
import com.duoduo.ai.client.dto.ButlerNoticeDtos.UnreadSummary;
import com.duoduo.common.api.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 通知查询服务（阶段二 · get_user_notifications 的业务实现）。
 *
 * <h2>核心规则：只消「看过的」，不消「没看的」</h2>
 * 用户问「我有什么通知」，AI 展示前 N 条 —— 他确实看到了这 N 条，
 * 所以把这 N 条标为已读是符合直觉的。但**绝不能**顺手把整个列表批量已读：
 * 那会把用户还没翻到的通知也一起清掉，红点消失、内容再也找不回来。
 * 因此这里只提交**本次返回的、且当时未读的**具体 id（Q4：按业务 ID 消红点）。
 *
 * <h2>未读数与角标同口径</h2>
 * {@code badge_patch.notification} 取通知域的 {@code unread}（已排除私信与客服，
 * 两者各自单独计数）。若这里再算一遍「列表里未读的条数」，就会出现
 * 「AI 说剩 2 条、手机角标还是 5」—— 角标只有一个计算口，就在通知域。
 *
 * <h2>标记失败不能拖垮查询</h2>
 * 用户要的是「我的通知有什么」，标已读只是顺带。标记失败时如实返回列表、
 * markedRead=0、badgePatch=null，让前端保持原角标；不能因为一次写失败就报「通知取不到」。
 */
@Service
public class NotificationQueryService {

    private static final Logger log = LoggerFactory.getLogger(NotificationQueryService.class);

    /** 卡片展示上限：通知是摘要，不是通知中心（传 50 条既读不完，也会误标已读）。 */
    private static final int MAX_LIMIT = 10;

    /** 正文摘要长度：卡片一行放不下 500 字，完整正文留在通知中心。 */
    private static final int SUMMARY_MAX = 60;

    private static final Set<String> CATEGORIES = Set.of(
            "ORDER", "FRIEND", "MERCHANT", "SYSTEM", "ANNOUNCEMENT");

    private final NoticeClient noticeClient;

    public NotificationQueryService(NoticeClient noticeClient) {
        this.noticeClient = noticeClient;
    }

    /**
     * 查询通知并按需标记已读。
     *
     * @param category 分类过滤（null = 全部）
     * @param read     UNREAD / READ / ALL（null 或 ALL = 全部）
     * @param limit    返回并允许标记已读的条数（1~10）
     * @param markRead 是否把本次展示的未读通知标为已读
     */
    public NotificationListData load(Long userId, String category, String read, int limit, boolean markRead) {
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        String cat = normalizeCategory(category);
        String readFilter = normalizeRead(read);

        NoticePage page = unwrap(noticeClient.list(userId, 1, size, cat, readFilter, null), "通知列表");
        // 再夹一次 size：即使下游忽略了 pageSize 多返回几条，也绝不能把没展示的标成已读
        List<NoticeView> raw = page.items() == null
                ? List.of()
                : page.items().stream().limit(size).toList();

        // 只收集「这次真的要展示给用户」的未读 id；已读的、空 id 的都不提交
        List<Long> unreadIds = raw.stream()
                .filter(v -> !v.read())
                .map(NoticeView::id)
                .filter(Objects::nonNull)
                .toList();

        BatchResult batch = null;
        if (markRead && !unreadIds.isEmpty()) {
            try {
                batch = unwrap(noticeClient.batch(userId, new BatchRequest("READ", unreadIds, false)),
                        "通知已读");
            } catch (RuntimeException e) {
                // 标记失败只降级，不阻断查询 —— 用户要的是内容，不是已读回执
                log.warn("通知标记已读失败 users={} ids={}", userId, unreadIds.size(), e);
            }
        }

        int marked = batch == null ? 0 : (int) Math.min(batch.affected(), Integer.MAX_VALUE);
        long unread = batch != null ? batch.unread() : currentUnread(userId);

        boolean allReadNow = marked > 0;
        List<NotificationItem> items = raw.stream()
                .map(v -> toItem(v, allReadNow || v.read()))
                .toList();
        return new NotificationListData(items, page.total(), unread, marked);
    }

    /** 当前通知未读数：失败时返回 0（只影响一个展示数字，不该让整次查询失败）。 */
    private long currentUnread(Long userId) {
        try {
            UnreadSummary summary = unwrap(noticeClient.unreadSummary(userId), "未读数");
            return summary.notice();
        } catch (RuntimeException e) {
            log.warn("读取通知未读数失败 users={}", userId, e);
            return 0;
        }
    }

    private NotificationItem toItem(NoticeView view, boolean read) {
        return new NotificationItem(
                view.id(),
                view.category(),
                view.title(),
                abbreviate(view.content()),
                view.bizType(),
                view.bizId(),
                read,
                view.createdAt(),
                view.webPath(),
                miniPath(view.jumpPage()));
    }

    /**
     * 小程序路径归一：通知域存的是 {@code /pages/...}（小程序页面清单的写法），
     * 而路由字典与前端约定用 {@code pages/...}（wx.navigateTo 不需要前导斜杠）。
     * 在这里统一一次，避免每个消费方各剥一次、剥错一次。
     */
    private static String miniPath(String jumpPage) {
        if (jumpPage == null || jumpPage.isBlank()) {
            return null;
        }
        String path = jumpPage.trim();
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private static String abbreviate(String text) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.isEmpty()) {
            return "";
        }
        return normalized.length() <= SUMMARY_MAX
                ? normalized
                : normalized.substring(0, SUMMARY_MAX - 1) + "…";
    }

    /** 分类白名单：模型可能自造 "orders"/"order" 这类值，直接拒绝比查出一片空更能让它纠正。 */
    private static String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        String value = category.trim().toUpperCase(Locale.ROOT);
        if (!CATEGORIES.contains(value)) {
            throw new IllegalArgumentException("通知分类不合法：" + category);
        }
        return value;
    }

    private static String normalizeRead(String read) {
        if (read == null || read.isBlank() || "ALL".equalsIgnoreCase(read.trim())) {
            return null;
        }
        String value = read.trim().toUpperCase(Locale.ROOT);
        if (!"UNREAD".equals(value) && !"READ".equals(value)) {
            throw new IllegalArgumentException("读状态不合法：" + read);
        }
        return value;
    }

    private static <T> T unwrap(ApiResponse<T> response, String what) {
        if (response == null || response.data() == null) {
            throw new IllegalStateException(what + "返回空数据");
        }
        return response.data();
    }
}
