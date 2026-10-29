package com.duoduo.social.support.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.social.client.ProductClient;
import com.duoduo.social.client.UserClient;
import com.duoduo.social.client.dto.ProductCardBrief;
import com.duoduo.social.client.dto.UserBrief;
import com.duoduo.social.mapper.SupportMessageMapper;
import com.duoduo.social.client.OrderClient;
import com.duoduo.social.service.NoticeService;
import com.duoduo.social.mapper.SupportSessionMapper;
import com.duoduo.social.support.dto.SupportDtos;
import com.duoduo.social.support.dto.SupportDtos.MessageView;
import com.duoduo.social.support.dto.SupportDtos.ProductCard;
import com.duoduo.social.support.dto.SupportDtos.SellerSendRequest;
import com.duoduo.social.support.dto.SupportDtos.SendRequest;
import com.duoduo.social.support.dto.SupportDtos.SessionView;
import com.duoduo.social.support.dto.SupportDtos.ThreadView;
import com.duoduo.social.support.entity.SupportMessageEntity;
import com.duoduo.social.support.entity.SupportSessionEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 客服域服务（自营客服 + 第三方商家客服）。
 *
 * <h2>两类客服怎么区分</h2>
 * <b>不引入类型字段</b>：{@code seller_id} 就是接待方。
 * <ul>
 *   <li>第三方商家：{@code seller_id} = 商品归属卖家（{@code t_spu.seller_id}）；</li>
 *   <li>自营：{@code seller_id} = 官方客服账号 id（增量 16 已把自营商品归到该账号）。</li>
 * </ul>
 * 于是「是不是自营客服」= {@code sellerId == officialCsUserId}，与商品域口径同源，
 * 不会出现「商品归自营、会话却算商家」的错位。
 *
 * <h2>与私信的边界</h2>
 * 客服没有「互相关注后才能继续聊」这套社交规则。原因是它约束的是**陌生人社交**，
 * 而用户找客服是消费场景里的正当诉求 —— 把防骚扰规则套上去，等于让用户发完一句话
 * 就必须等客服回关，问题根本问不完。
 *
 * <h2>闲置为什么不在本域</h2>
 * 闲置（跳蚤市场）是买卖双方的个人交易，平台不介入履约与售后，
 * 因此只走私信「聊一聊/问一问」，不进客服流程 —— 这条边界写在
 * {@code MessageService} 的 LISTING 分支里，不在本类里加例外。
 */
@Service
public class SupportService {

    private static final Logger log = LoggerFactory.getLogger(SupportService.class);

    private static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_CONTENT = 1000;
    private static final int PREVIEW_MAX = 120;

    /** 用户侧频控：客服场景常连发补充说明，比私信（10 条/分）宽一倍 */
    private static final int USER_RATE_LIMIT = 20;
    /** 商家侧频控：更宽，批量接待时同一会话可能连续回多条 */
    private static final int SELLER_RATE_LIMIT = 60;
    private static final int RATE_WINDOW_SECONDS = 60;

    private final SupportSessionMapper sessionMapper;
    private final SupportMessageMapper messageMapper;
    private final UserClient userClient;
    private final ProductClient productClient;
    private final SnowflakeIdGenerator idGenerator;
    private final NoticeService noticeService;
    private final OrderClient orderClient;
    private final com.duoduo.social.realtime.RealtimePublisher realtime;
    private final com.duoduo.social.support.ChatModeration chatModeration;
    private final Long officialCsUserId;

    public SupportService(SupportSessionMapper sessionMapper,
                          SupportMessageMapper messageMapper,
                          UserClient userClient,
                          ProductClient productClient,
                          SnowflakeIdGenerator idGenerator,
                          NoticeService noticeService,
                          OrderClient orderClient,
                          com.duoduo.social.realtime.RealtimePublisher realtime,
                          com.duoduo.social.support.ChatModeration chatModeration,
                          @Value("${duoduo.social.official-cs-user-id:710000000000000001}")
                          Long officialCsUserId) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.userClient = userClient;
        this.productClient = productClient;
        this.idGenerator = idGenerator;
        this.noticeService = noticeService;
        this.orderClient = orderClient;
        this.realtime = realtime;
        this.chatModeration = chatModeration;
        this.officialCsUserId = officialCsUserId;
    }

    // ---------------- 用户侧 ----------------

    /** 取或建我与某商家（缺省=自营客服）的会话 */
    public SessionView ensureForUser(Long userId, Long sellerId) {
        // sellerId=0 也是自营：交易域 t_order.seller_id 对自营订单落 0（历史口径），
        // 咨询订单把 0 传上来时必须归到官方客服，否则用户表查无此人报「店铺不存在」（2026-10-22 修复）。
        Long target = (sellerId == null || sellerId == 0L) ? officialCsUserId : sellerId;
        Map<Long, UserBrief> briefs = loadBriefs(List.of(target, userId));
        UserBrief seller = briefs.get(target);
        if (seller == null) {
            if (Objects.equals(target, officialCsUserId)) {
                // 客服账号不存在时不能假装接通：给独立错误码，端侧退回「去问 AI 导购」
                throw new BizException(ErrorCode.SUPPORT_AGENT_NOT_READY,
                        "客服账号（id=" + officialCsUserId + "）未就绪，请先执行种子数据");
            }
            throw BizException.notFound("店铺");
        }
        SupportSessionEntity session = ensureSession(target, userId);
        return toSessionView(session, briefs, loadStoreNames(List.of(target)), true);
    }

    /** 我的全部客服会话（不区分自营/商家，按最后消息倒序） */
    public List<SessionView> listForUser(Long userId) {
        List<SupportSessionEntity> rows = sessionMapper.selectList(
                new LambdaQueryWrapper<SupportSessionEntity>()
                        .eq(SupportSessionEntity::getUserId, userId)
                        .orderByDesc(SupportSessionEntity::getLastMessageAt)
                        .orderByDesc(SupportSessionEntity::getId));
        Map<Long, UserBrief> briefs = loadBriefs(rows.stream()
                .flatMap(s -> java.util.stream.Stream.of(s.getSellerId(), s.getUserId()))
                .toList());
        return rows.stream().map(s -> toSessionView(s, briefs,
                loadStoreNames(rows.stream().map(SupportSessionEntity::getSellerId).toList()),
                true)).toList();
    }

    public ThreadView userThread(Long userId, Long sellerId, int page, int pageSize) {
        return userThread(userId, sellerId, page, pageSize, null);
    }

    /** {@code aroundId} 非空 = 引用跳转定位：返回以该消息为中心的一页（2026-10-25） */
    public ThreadView userThread(Long userId, Long sellerId, int page, int pageSize, Long aroundId) {
        SessionView session = ensureForUser(userId, sellerId);
        return thread(session, page, pageSize, aroundId, userId, true);
    }

    /** 单向删除（2026-10-25）：只对用户本人隐藏，商家仍可见。幂等；返回本次隐藏条数 */
    public int deleteByUser(Long userId, List<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (Long id : messageIds) {
            SupportMessageEntity msg = requireMessage(id);
            SupportSessionEntity session = requireSession(msg.getSessionId());
            if (!Objects.equals(session.getUserId(), userId)) {
                throw BizException.forbidden("该会话不属于当前账号");
            }
            if (msg.getHiddenForUserAt() != null) {
                continue; // 幂等：已删过的不重复计
            }
            SupportMessageEntity patch = new SupportMessageEntity();
            patch.setId(msg.getId());
            patch.setHiddenForUserAt(now());
            n += messageMapper.updateById(patch);
        }
        return n;
    }

    /** 用户发消息；{@code productId} 非空时附带商品卡上下文（自营/第三方商品都走这里） */
    public MessageView sendByUser(Long userId, SendRequest request) {
        String type = normalizeType(request.type());
        boolean media = "IMAGE".equals(type) || "VIDEO".equals(type) || "EMOJI".equals(type)
                || "VOICE".equals(type);

        Long sellerId = request.sellerId();
        Long target = (sellerId == null || sellerId == 0L) ? officialCsUserId : sellerId;
        String orderSnapshot = null;
        String orderNo = null;
        if ("ORDER".equals(type)) {
            // 订单卡：只认单号，事实回交易域取；归属必须是当前用户
            if (isBlank(request.orderNo())) {
                throw BizException.validation("缺少订单号");
            }
            OrderClient.OrderFactBrief fact = orderFact(request.orderNo().trim());
            if (!Objects.equals(fact.userId(), userId)) {
                throw BizException.forbidden("该订单不属于当前账号");
            }
            target = fact.sellerId();
            if (target == null || target == 0L) {
                // 自营订单在交易域 seller_id=0：订单卡会话固定归到官方客服
                target = officialCsUserId;
            }
            orderNo = fact.orderNo();
            orderSnapshot = orderSnapshot(fact);
        }
        ensureForUser(userId, target); // 卖家/客服账号存在性校验（并顺手建会话）

        ProductCardBrief product = null;
        if ("PRODUCT".equals(type) || request.productId() != null) {
            if (request.productId() == null) {
                throw BizException.validation("缺少商品 ID");
            }
            product = requireProduct(request.productId());
            type = "PRODUCT";
            // 商品卡必须与服务端认定的一致：否则用户可以在给 A 店的会话里塞 B 店商品
            if (!Objects.equals(product.sellerId(), target)) {
                throw BizException.validation("该商品不属于当前会话的店铺");
            }
        }
        if (media && isBlank(request.mediaUrl())) {
            throw BizException.validation("缺少媒体地址，请重新上传");
        }
        if (media && !isSafeMediaUrl(request.mediaUrl())) {
            throw BizException.validation("媒体地址不合法");
        }
        if ("VOICE".equals(type)) {
            // 语音（2026-10-24）：时长由端侧实测回传，服务端只卡上限（60 秒）
            if (request.durationMs() == null || request.durationMs() <= 0
                    || request.durationMs() > VOICE_MAX_MS) {
                throw BizException.validation("语音时长无效（最长 60 秒），请重新录制");
            }
            if (isBlank(request.content())) {
                request = new SendRequest(request.sellerId(), "[语音]", request.productId(), request.type(),
                        request.mediaUrl(), request.thumbUrl(), request.width(), request.height(),
                        request.durationMs(), request.orderNo(), request.replyToId());
            }
        }

        String text = normalizeContent(request.content());
        // ORDER 的文案由订单号生成，这里不能按「空内容」拒绝
        if (text.isEmpty() && product == null && !media && !"ORDER".equals(type)) {
            throw BizException.validation("消息内容不能为空");
        }
        if (text.isEmpty() && product != null) {
            text = "我想了解一下「" + product.title() + "」";
        }
        if (text.isEmpty() && "ORDER".equals(type) && orderNo != null) {
            text = "咨询订单 · 尾号 " + (orderNo.length() > 4 ? orderNo.substring(orderNo.length() - 4) : orderNo);
        }
        // C-05：聊天质检（站外链接 / 诈骗词 / 敏感词拦截；联系方式默认只告警）
        chatModeration.enforce(text, "support-user");

        SupportSessionEntity session = ensureSession(target, userId);
        // 引用（2026-10-24）：必须同会话，摘要由服务端拼装，不信任端侧
        Long replyToId = requireReply(session.getId(), request.replyToId());
        // 会话关闭后买家再发消息自动重开（2026-10-23 规则收紧后仍然保留）：
        // 关闭只是**客服侧**的收尾标记，买家全程无感；他能继续发，就说明咨询还没结束。
        if ("CLOSED".equalsIgnoreCase(session.getStatus())) {
            sessionMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SupportSessionEntity>()
                    .eq(SupportSessionEntity::getId, session.getId())
                    .set(SupportSessionEntity::getStatus, "OPEN")
                    // ★ 必须显式置 null：MP 的 updateById 会跳过 null 字段，闭合时间就永远清不掉，
                    //   30 天清理会把这个「其实还在用」的会话删掉
                    .set(SupportSessionEntity::getClosedAt, null)
                    .set(SupportSessionEntity::getUpdatedAt, now()));
            session.setStatus("OPEN");
            session.setClosedAt(null);
        }
        if (messageMapper.countSentInWindow(session.getId(), userId, RATE_WINDOW_SECONDS) >= USER_RATE_LIMIT) {
            throw new BizException(ErrorCode.RATE_LIMITED, "发送太频繁，请稍后再试");
        }

        SupportMessageEntity entity = insert(session.getId(), userId, "USER", text, type,
                product == null ? null : product.id(),
                request.mediaUrl(), request.thumbUrl(),
                request.width(), request.height(), request.durationMs(), orderSnapshot, replyToId);
        touchSession(session, previewOf(type, text), true);
        // 实时推送：只通知**对端**有新消息，正文由客户端回源拉取（见 RealtimeEvent 注释）
        pushRealtime(target, "SUPPORT", session.getId(), null, userId, entity.getId());
        return toMessageView(entity, userId, product, null);
    }

    /** 用户标记已读 */
    public long markReadByUser(Long userId, Long sessionId) {
        SupportSessionEntity session = requireSession(sessionId);
        if (!Objects.equals(session.getUserId(), userId)) {
            throw BizException.forbidden("该会话不属于当前账号");
        }
        long n = markMessagesRead(sessionId, userId);
        // 点开即清零：不依赖「是否回复」，也不等下一次轮询 —— 与微信一致
        long cursor = messageMapper.maxMessageId(sessionId);
        SupportSessionEntity patch = new SupportSessionEntity();
        patch.setId(sessionId);
        patch.setUserLastReadId(cursor);
        patch.setUnreadForUser(0);
        patch.setUpdatedAt(now());
        sessionMapper.updateById(patch);
        // 通知中心里那条「客服消息」同步标已读，避免列表亮着红点但会话已读
        try {
            noticeService.markSupportNoticesRead(userId, sessionId);
        } catch (Exception e) {
            log.warn("同步客服通知已读失败 sessionId={}", sessionId, e);
        }
        // 已读回执实时推给客服（对方聊天框里的「未读」立刻变「已读」）
        pushRealtimeRead(session.getSellerId(), sessionId, userId, cursor);
        return n;
    }

    /**
     * 通知中心「全部已读」：把该用户所有客服会话一并读完。
     *
     * <p>为什么归到这里：客服消息已经出现在通知中心，用户点「全部已读」的预期是
     * 「这一屏都清掉」；如果只清通知、会话角标还亮着，就是另一种形式的自相矛盾。
     */
    public void markAllReadForUser(Long userId) {
        List<SupportSessionEntity> rows = sessionMapper.selectList(
                new LambdaQueryWrapper<SupportSessionEntity>()
                        .eq(SupportSessionEntity::getUserId, userId)
                        .gt(SupportSessionEntity::getUnreadForUser, 0));
        for (SupportSessionEntity row : rows) {
            markReadByUser(userId, row.getId());
        }
    }

    public long unreadForUser(Long userId) {
        return sessionMapper.sumUnreadForUser(userId);
    }

    /**
     * 买家主动结束本次咨询（2026-10-23）。
     *
     * <h2>为什么只有买家能关</h2>
     * 关闭是「用户这边问完了」的表达，不是客服驱逐用户的手段 —— 客服一旦有关闭权，
     * 就会出现「不想答就关会话」的操作空间。客服侧只保留**看见**状态与**筛选**能力。
     *
     * <h2>关闭后买家无感</h2>
     * 买家侧接口一律不回 status（见 {@link #toSessionView}），前端也没有任何关闭提示；
     * 他随时可以继续发消息，发送时服务端自动重开。
     *
     * <p>幂等：已关闭时重复调用不再刷新 {@code closed_at} ——
     * 否则用户反复点「结束咨询」就能把 30 天清理窗口无限推后。
     */
    public void closeByUser(Long userId, Long sessionId) {
        SupportSessionEntity session = requireSession(sessionId);
        if (!Objects.equals(session.getUserId(), userId)) {
            throw BizException.forbidden("该会话不属于当前账号");
        }
        LocalDateTime now = now();
        sessionMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SupportSessionEntity>()
                        .eq(SupportSessionEntity::getId, sessionId)
                        .eq(SupportSessionEntity::getStatus, "OPEN")
                        .set(SupportSessionEntity::getStatus, "CLOSED")
                        .set(SupportSessionEntity::getClosedAt, now)
                        .set(SupportSessionEntity::getUpdatedAt, now));
    }

    // ---------------- 商家侧（工作台） ----------------

    /**
     * 店铺的客服会话列表。
     *
     * <p>{@code sellerId} 由调用方从登录态推导（控制器负责），本方法不做「我是谁」判断 ——
     * 这样它也能被内部对账脚本按指定店铺复用，而权限仍只有一处（控制器）。
     *
     * <h2>筛选（2026-10-23 扩展）</h2>
     * <ul>
     *   <li>{@code ALL}：全部；</li>
     *   <li>{@code UNREAD}：还有待回（{@code unread_for_seller > 0}）；</li>
     *   <li>{@code ANSWERED}：已处理 —— 没有待回（{@code unread_for_seller = 0}），含已关闭的；</li>
     *   <li>{@code CLOSED}：已关闭（仅客服可见的状态视图）。</li>
     * </ul>
     * 「已处理」按「是否还有待回」判定，而不是按「有没有回复过」：
     * 后者要再查一次消息表，且「买家又追问了一句」之后仍会被算作已处理 —— 与运营心智相反。
     *
     * @param filter 兼容旧参数：{@code onlyUnread=true} 等价于 {@code UNREAD}（老前端不发 filter）
     */
    public PageResult<SessionView> listForSeller(Long sellerId, String status, String filter,
                                                 boolean onlyUnread, int page, int pageSize) {
        int p = Math.max(1, page);
        int size = Math.max(1, Math.min(pageSize <= 0 ? DEFAULT_PAGE_SIZE : pageSize, MAX_PAGE_SIZE));
        LambdaQueryWrapper<SupportSessionEntity> wrapper = sellerSessionWrapper(sellerId, status, filter, onlyUnread);
        Page<SupportSessionEntity> result = sessionMapper.selectPage(new Page<>(p, size), wrapper);
        Map<Long, UserBrief> briefs = loadBriefs(result.getRecords().stream()
                .flatMap(s -> java.util.stream.Stream.of(s.getSellerId(), s.getUserId(), s.getAssigneeId()))
                .toList());
        List<SessionView> views = result.getRecords().stream()
                .map(s -> toSessionView(s, briefs,
                        loadStoreNames(result.getRecords().stream()
                                .map(SupportSessionEntity::getSellerId).toList()),
                        false)).toList();
        return PageResult.of(views, result.getTotal(), p, size);
    }

    /**
     * 商家客服会话导出（2026-10-28 · 矩阵 P3）：与列表同筛选，最多 5 万行。
     *
     * <p>只导出会话元信息（买家、未读数、状态、处理人、最后消息时间），
     * <b>不含聊天正文</b> —— 客服沟通内容是买卖双方隐私，导出报表不需要也不应该带走。
     * 超过上限明确 409，不静默截断，避免运营按一份少行的表做排班。
     */
    public List<SessionView> exportForSeller(Long sellerId, String status, String filter, boolean onlyUnread) {
        LambdaQueryWrapper<SupportSessionEntity> wrapper = sellerSessionWrapper(sellerId, status, filter, onlyUnread);
        long total = sessionMapper.selectCount(wrapper);
        if (total > 50_000) {
            throw BizException.conflict("会话超过 5 万条，请先按状态或筛选条件缩小范围");
        }
        List<SupportSessionEntity> rows = sessionMapper.selectList(wrapper.last("LIMIT 50000"));
        Map<Long, UserBrief> briefs = loadBriefs(rows.stream()
                .flatMap(s -> java.util.stream.Stream.of(s.getSellerId(), s.getUserId(), s.getAssigneeId()))
                .toList());
        Map<Long, String> storeNames = loadStoreNames(rows.stream()
                .map(SupportSessionEntity::getSellerId).toList());
        return rows.stream().map(s -> toSessionView(s, briefs, storeNames, false)).toList();
    }

    /** 商家会话查询条件：列表与导出共用，防止两处筛选口径漂移。 */
    private LambdaQueryWrapper<SupportSessionEntity> sellerSessionWrapper(Long sellerId, String status,
                                                                         String filter, boolean onlyUnread) {
        LambdaQueryWrapper<SupportSessionEntity> wrapper = new LambdaQueryWrapper<SupportSessionEntity>()
                .eq(SupportSessionEntity::getSellerId, sellerId)
                .orderByDesc(SupportSessionEntity::getLastMessageAt)
                .orderByDesc(SupportSessionEntity::getId);
        String st = status == null ? "" : status.trim().toUpperCase();
        if (!st.isEmpty()) {
            wrapper.eq(SupportSessionEntity::getStatus, st);
        }
        String f = filter == null || filter.isBlank()
                ? (onlyUnread ? "UNREAD" : "ALL")
                : filter.trim().toUpperCase();
        switch (f) {
            case "UNREAD" -> wrapper.gt(SupportSessionEntity::getUnreadForSeller, 0);
            case "ANSWERED" -> wrapper.eq(SupportSessionEntity::getUnreadForSeller, 0);
            case "CLOSED" -> wrapper.eq(SupportSessionEntity::getStatus, "CLOSED");
            default -> { /* ALL */ }
        }
        return wrapper;
    }

    /** 兼容旧签名的重载（内部脚本/旧调用点不传 filter） */
    public PageResult<SessionView> listForSeller(Long sellerId, String status, boolean onlyUnread,
                                                 int page, int pageSize) {
        return listForSeller(sellerId, status, null, onlyUnread, page, pageSize);
    }

    public ThreadView sellerThread(Long sellerId, Long sessionId, int page, int pageSize) {
        return sellerThread(sellerId, sessionId, page, pageSize, null);
    }

    /** {@code aroundId} 非空 = 引用跳转定位（2026-10-25） */
    public ThreadView sellerThread(Long sellerId, Long sessionId, int page, int pageSize, Long aroundId) {
        SupportSessionEntity session = requireOwnedSession(sellerId, sessionId);
        Map<Long, UserBrief> briefs = loadBriefs(List.of(session.getSellerId(), session.getUserId()));
        return thread(toSessionView(session, briefs, loadStoreNames(List.of(session.getSellerId())), false),
                page, pageSize, aroundId, sellerId, false);
    }

    /** 单向删除（2026-10-25）：只对商家侧隐藏，用户仍可见。幂等 */
    public int deleteBySeller(Long sellerId, Long sessionId, List<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return 0;
        }
        requireOwnedSession(sellerId, sessionId);
        int n = 0;
        for (Long id : messageIds) {
            SupportMessageEntity msg = requireMessage(id);
            if (!Objects.equals(msg.getSessionId(), sessionId)) {
                throw BizException.validation("该消息不属于当前会话");
            }
            if (msg.getHiddenForSellerAt() != null) {
                continue;
            }
            SupportMessageEntity patch = new SupportMessageEntity();
            patch.setId(msg.getId());
            patch.setHiddenForSellerAt(now());
            n += messageMapper.updateById(patch);
        }
        return n;
    }

    public MessageView sendBySeller(Long sellerId, Long sessionId, SellerSendRequest request) {
        SupportSessionEntity session = requireOwnedSession(sellerId, sessionId);
        requireNotClosed(session);
        String type = normalizeType(request.type());
        boolean media = "IMAGE".equals(type) || "VIDEO".equals(type) || "EMOJI".equals(type)
                || "VOICE".equals(type);
        String text = normalizeContent(request.content());
        if (text.isEmpty() && !media) {
            throw BizException.validation("回复内容不能为空");
        }
        if (media && isBlank(request.mediaUrl())) {
            throw BizException.validation("缺少媒体地址，请重新上传");
        }
        if (media && !isSafeMediaUrl(request.mediaUrl())) {
            throw BizException.validation("媒体地址不合法");
        }
        if ("VOICE".equals(type)) {
            if (request.durationMs() == null || request.durationMs() <= 0
                    || request.durationMs() > VOICE_MAX_MS) {
                throw BizException.validation("语音时长无效（最长 60 秒），请重新录制");
            }
            if (text.isEmpty()) {
                text = "[语音]";
            }
        }
        // C-05：客服回复同样过质检（防止客服把买家导流到站外 / 发送诈骗话术）
        chatModeration.enforce(text, "support-seller");
        // 引用（2026-10-24）：必须同会话
        Long replyToId = requireReply(sessionId, request.replyToId());
        if (messageMapper.countSentInWindow(sessionId, sellerId, RATE_WINDOW_SECONDS) >= SELLER_RATE_LIMIT) {
            throw new BizException(ErrorCode.RATE_LIMITED, "发送太频繁，请稍后再试");
        }
        SupportMessageEntity entity = insert(sessionId, sellerId, "SELLER", text, type, null,
                request.mediaUrl(), request.thumbUrl(),
                request.width(), request.height(), request.durationMs(), null, replyToId);
        touchSession(session, previewOf(type, text), false);
        pushRealtime(session.getUserId(), "SUPPORT", sessionId, null, sellerId, entity.getId());
        // 通知中心可见性：客服会话独立成表后，用户在通知中心看不到任何痕迹。
        // best-effort：通知写失败不能把「已发出的回复」判成失败（否则用户会重发）。
        try {
            noticeService.pushSupportNotice(session.getUserId(), session.getSellerId(),
                    session.getId(), previewOf(type, text));
        } catch (Exception e) {
            log.warn("客服通知写入失败 sessionId={}（不影响回复）", session.getId(), e);
        }
        return toMessageView(entity, sellerId, null, null);
    }

    public long markReadBySeller(Long sellerId, Long sessionId) {
        SupportSessionEntity session = requireOwnedSession(sellerId, sessionId);
        long n = markMessagesRead(sessionId, sellerId);
        long cursor = messageMapper.maxMessageId(sessionId);
        SupportSessionEntity patch = new SupportSessionEntity();
        patch.setId(sessionId);
        patch.setSellerLastReadId(cursor);
        patch.setUnreadForSeller(0);
        patch.setUpdatedAt(now());
        sessionMapper.updateById(patch);
        // 已读回执实时推给买家：他发出的最后一条立刻变成「已读」
        pushRealtimeRead(session.getUserId(), sessionId, sellerId, cursor);
        return n;
    }

    public long unreadForSeller(Long sellerId) {
        return sessionMapper.sumUnreadForSeller(sellerId);
    }

    /**
     * 关闭 / 重开会话 —— **已移除**（2026-10-23）。
     *
     * <p>关闭权归买家：客服一旦能关，就有「不想答就关掉」的空间。买家关闭后，
     * 他再发消息会自动重开（{@link #sendByUser}），因此客服侧也不需要「重开」。
     * 控制器保留旧路径并返回 403，文案说明口径变化。
     */

    // ---------------- 内部 ----------------

    /**
     * 推送一条「有新消息」信号（best-effort）。
     *
     * <p>推送失败**绝不能**影响发消息：消息已经落库，若因 Redis/连接问题把发送判失败，
     * 用户会重发，对方就会收到两条一样的消息。实时通道是增强，不是主链路。
     */
    private void pushRealtime(Long toUserId, String biz, Long sessionId, String conversationId,
                              Long fromUserId, Long messageId) {
        try {
            realtime.publish(toUserId, com.duoduo.social.realtime.RealtimeEvent.message(
                    biz, sessionId == null ? null : String.valueOf(sessionId),
                    conversationId, fromUserId, messageId));
        } catch (Exception e) {
            log.warn("实时消息推送失败 sessionId={} to={}（不影响发送）", sessionId, toUserId, e);
        }
    }

    private void pushRealtimeRead(Long toUserId, Long sessionId, Long readerId, long lastReadId) {
        try {
            realtime.publish(toUserId, com.duoduo.social.realtime.RealtimeEvent.read(
                    "SUPPORT", String.valueOf(sessionId), null, readerId, lastReadId));
        } catch (Exception e) {
            log.warn("已读回执推送失败 sessionId={} to={}（不影响已读）", sessionId, toUserId, e);
        }
    }

    private SupportSessionEntity ensureSession(Long sellerId, Long userId) {
        SupportSessionEntity existing = sessionMapper.selectByPair(sellerId, userId);
        if (existing != null) {
            return existing;
        }
        LocalDateTime now = now();
        SupportSessionEntity entity = new SupportSessionEntity();
        entity.setId(idGenerator.nextId());
        entity.setSellerId(sellerId);
        entity.setUserId(userId);
        entity.setStatus("OPEN");
        entity.setLastMessageAt(now);
        entity.setLastMessagePreview("");
        entity.setUnreadForUser(0);
        entity.setUnreadForSeller(0);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            sessionMapper.insert(entity);
            return entity;
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            // 并发首次进入：唯一索引兜底，回读已存在的那条（不把冲突抛给用户）
            SupportSessionEntity again = sessionMapper.selectByPair(sellerId, userId);
            if (again != null) {
                return again;
            }
            throw ex;
        }
    }

    private SupportMessageEntity insert(Long sessionId, Long senderId, String role,
                                        String content, String bizType, Long bizId,
                                        String mediaUrl, String thumbUrl,
                                        Integer width, Integer height, Integer durationMs,
                                        String metaJson, Long replyToId) {
        SupportMessageEntity entity = new SupportMessageEntity();
        entity.setId(idGenerator.nextId());
        entity.setSessionId(sessionId);
        entity.setSenderId(senderId);
        entity.setSenderRole(role);
        entity.setContent(content);
        entity.setBizType(bizType);
        entity.setBizId(bizId);
        entity.setMediaUrl(mediaUrl);
        entity.setThumbUrl(thumbUrl);
        entity.setWidth(width);
        entity.setHeight(height);
        entity.setDurationMs(durationMs);
        entity.setMetaJson(metaJson);
        entity.setReplyToId(replyToId);
        entity.setCreatedAt(now());
        messageMapper.insert(entity);
        return entity;
    }

    /** 更新会话冗余字段：最后消息时间/摘要 + 对方未读 +1 */
    private void touchSession(SupportSessionEntity session, String content, boolean fromUser) {
        SupportSessionEntity patch = new SupportSessionEntity();
        patch.setId(session.getId());
        patch.setLastMessageAt(now());
        patch.setLastMessagePreview(abbreviate(content));
        patch.setUpdatedAt(now());
        sessionMapper.updateById(patch);
        if (fromUser) {
            sessionMapper.bumpUnreadForSeller(session.getId(), 1);
        } else {
            sessionMapper.bumpUnreadForUser(session.getId(), 1);
        }
    }

    private long markMessagesRead(Long sessionId, Long readerId) {
        SupportMessageEntity patch = new SupportMessageEntity();
        patch.setReadAt(now());
        return messageMapper.update(patch, new LambdaQueryWrapper<SupportMessageEntity>()
                .eq(SupportMessageEntity::getSessionId, sessionId)
                .ne(SupportMessageEntity::getSenderId, readerId)
                .isNull(SupportMessageEntity::getReadAt));
    }

    private ThreadView thread(SessionView session, int page, int pageSize, Long viewerId, boolean userSide) {
        return thread(session, page, pageSize, null, viewerId, userSide);
    }

    /**
     * 会话消息读取。
     *
     * <p>{@code aroundId} 非空（2026-10-25）：引用跳转定位 —— 目标消息可能远在
     * 当前分页窗口之外，此时不再按页取「最新一页」，而是取「该消息往前 half 条 +
     * 往后 half 条」组成的一页，前端拿到的窗口必然包含被引用消息。
     *
     * <p>可见性（2026-10-25 单向删除）：本人已删除的消息在任何读取路径都不出现；
     * 对方视角不受影响（hidden_* 按查看方分列）。
     */
    private ThreadView thread(SessionView session, int page, int pageSize, Long aroundId,
                              Long viewerId, boolean userSide) {
        int p = Math.max(1, page);
        int size = Math.max(1, Math.min(pageSize <= 0 ? DEFAULT_PAGE_SIZE : pageSize, MAX_PAGE_SIZE));
        List<SupportMessageEntity> ordered;
        long total;
        if (aroundId != null) {
            int half = size / 2;
            // 窗口 = [aroundId 往前 half+1 条（含本条）, 之后 half 条]，正序返回
            List<SupportMessageEntity> before = messageMapper.selectList(visibleQuery(session.id(), userSide)
                    .le(SupportMessageEntity::getId, aroundId)
                    .orderByDesc(SupportMessageEntity::getId)
                    .last("LIMIT " + (half + 1)));
            List<SupportMessageEntity> after = messageMapper.selectList(visibleQuery(session.id(), userSide)
                    .gt(SupportMessageEntity::getId, aroundId)
                    .orderByAsc(SupportMessageEntity::getId)
                    .last("LIMIT " + half));
            java.util.Collections.reverse(after); // 统一成倒序，走下面的公共 reverse 变正序
            ordered = new ArrayList<>(before);
            ordered.addAll(after);
            total = ordered.size();
        } else {
            Page<SupportMessageEntity> result = messageMapper.selectPage(new Page<>(p, size),
                    visibleQuery(session.id(), userSide)
                            .orderByDesc(SupportMessageEntity::getId));
            ordered = new ArrayList<>(result.getRecords());
            total = result.getTotal();
        }
        java.util.Collections.reverse(ordered); // 分页取最新一页，展示按时间正序

        Map<Long, ProductCardBrief> products = loadProducts(ordered);
        Map<Long, SupportDtos.QuotedView> quoted = quotedOf(ordered);
        List<MessageView> items = ordered.stream()
                .map(m -> toMessageView(m, viewerId,
                        m.getBizId() == null ? null : products.get(m.getBizId()),
                        m.getReplyToId() == null ? null : quoted.get(m.getReplyToId())))
                .toList();
        // 已读回执：对端读到哪条 + 我发出的还有几条没被读。
        // 游标在会话行上，这里按主键回读一次（PK 查询，轮询频率下可忽略）。
        SupportSessionEntity raw = requireSession(session.id());
        long peerCursor = userSide ? nzLong(raw.getSellerLastReadId()) : nzLong(raw.getUserLastReadId());
        long myUnread = messageMapper.countSentAfter(session.id(), viewerId, peerCursor);
        return new ThreadView(session.id(), session.sellerId(), session.sellerName(), session.sellerAvatar(),
                session.userId(), session.userName(), session.userAvatar(),
                // 同 toSessionView：会话状态只对客服可见，买家侧恒为 null
                userSide ? null : session.status(),
                peerCursor, myUnread, items, total, p, size);
    }

    /** 会话内按查看方过滤「本人已单向删除」消息的公共查询前缀（2026-10-25） */
    private LambdaQueryWrapper<SupportMessageEntity> visibleQuery(Long sessionId, boolean userSide) {
        LambdaQueryWrapper<SupportMessageEntity> wrapper = new LambdaQueryWrapper<SupportMessageEntity>()
                .eq(SupportMessageEntity::getSessionId, sessionId);
        if (userSide) {
            wrapper.isNull(SupportMessageEntity::getHiddenForUserAt);
        } else {
            wrapper.isNull(SupportMessageEntity::getHiddenForSellerAt);
        }
        return wrapper;
    }

    private SupportSessionEntity requireSession(Long sessionId) {
        SupportSessionEntity session = sessionId == null ? null : sessionMapper.selectById(sessionId);
        if (session == null) {
            throw BizException.notFound("客服会话");
        }
        return session;
    }

    private SupportSessionEntity requireOwnedSession(Long sellerId, Long sessionId) {
        SupportSessionEntity session = requireSession(sessionId);
        if (!Objects.equals(session.getSellerId(), sellerId)) {
            // 用 403 而不是 404：会话确实存在，只是不属于你 —— 排查时这个区别很重要
            throw BizException.forbidden("该会话不属于当前店铺");
        }
        return session;
    }

    private void requireNotClosed(SupportSessionEntity session) {
        if ("CLOSED".equalsIgnoreCase(session.getStatus())) {
            throw BizException.businessRule("该会话已关闭，请重新开启后再回复");
        }
    }

    private ProductCardBrief requireProduct(Long productId) {
        try {
            var response = productClient.cards(String.valueOf(productId));
            List<ProductCardBrief> cards = response == null ? null : response.data();
            if (cards != null && !cards.isEmpty()) {
                return cards.get(0);
            }
        } catch (Exception e) {
            log.warn("取商品卡失败 productId={}", productId, e);
        }
        throw BizException.notFound("商品");
    }

    private Map<Long, ProductCardBrief> loadProducts(Collection<SupportMessageEntity> messages) {
        List<Long> ids = messages.stream()
                .filter(m -> "PRODUCT".equals(m.getBizType()))
                .map(SupportMessageEntity::getBizId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            var response = productClient.cards(ids.stream().map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(",")));
            List<ProductCardBrief> cards = response == null ? null : response.data();
            if (cards == null || cards.isEmpty()) {
                return Map.of();
            }
            Map<Long, ProductCardBrief> map = new HashMap<>();
            cards.forEach(c -> map.putIfAbsent(c.id(), c));
            return map;
        } catch (Exception e) {
            // 商品域挂了不该让整个会话打不开：卡片降级为无，文字消息照常显示
            log.warn("批量取商品卡失败 ids={}", ids, e);
            return Map.of();
        }
    }

    private Map<Long, UserBrief> loadBriefs(Collection<Long> ids) {
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        try {
            var response = userClient.briefs(distinct.stream().map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(",")));
            List<UserBrief> briefs = response == null ? null : response.data();
            if (briefs == null) {
                return Map.of();
            }
            Map<Long, UserBrief> map = new HashMap<>();
            briefs.forEach(b -> map.putIfAbsent(b.id(), b));
            return map;
        } catch (Exception e) {
            log.warn("批量取用户资料失败 ids={}", distinct, e);
            return Map.of();
        }
    }

    private SessionView toSessionView(SupportSessionEntity s, Map<Long, UserBrief> briefs,
                                      Map<Long, String> storeNames, boolean userSide) {
        UserBrief seller = briefs.get(s.getSellerId());
        UserBrief user = briefs.get(s.getUserId());
        UserBrief assignee = s.getAssigneeId() == null ? null : briefs.get(s.getAssigneeId());
        long unread = userSide ? nz(s.getUnreadForUser()) : nz(s.getUnreadForSeller());
        return new SessionView(
                s.getId(), s.getSellerId(),
                sellerDisplayOf(s.getSellerId(), seller, storeNames),
                seller == null ? null : seller.avatar(),
                s.getUserId(),
                user == null || user.nickname() == null ? "用户" + s.getUserId() : user.nickname(),
                user == null ? null : user.avatar(),
                s.getLastMessagePreview(),
                s.getLastMessageAt() == null ? null : s.getLastMessageAt().toInstant(ZoneOffset.UTC).toString(),
                unread,
                // ★ 买家侧不回状态（2026-10-23）：关闭只是客服侧的收尾标记，对用户必须全程无感。
                //   在**服务端**抹掉而不是靠前端「不显示」，这样任何客户端都拿不到这个字段，
                //   也不会有人手滑把「已关闭」渲染出来。
                userSide ? null : s.getStatus(),
                // C-04：处理人属于客服内部协作信息，买家侧同样在服务端抹掉
                userSide || s.getAssigneeId() == null ? null : String.valueOf(s.getAssigneeId()),
                userSide || assignee == null || assignee.nickname() == null ? null : assignee.nickname());
    }

    /**
     * 接待方展示名（2026-10-22 · 会话名店铺名优先）：
     * 第三方商家显示店铺名（t_merchant.name，「喵星人旗舰店」），而不是店主昵称（「喵星人店主」）——
     * 买家心智里是在「跟店聊」，昵称改了店名没改就会对不上。店铺名查不到（未入驻/降级）回退昵称。
     * 自营客服不是店铺，保持昵称（「多多官方客服」）。
     */
    private String sellerDisplayOf(Long sellerId, UserBrief seller, Map<Long, String> storeNames) {
        if (sellerId != null && !Objects.equals(sellerId, officialCsUserId)
                && sellerId != 0L && storeNames.containsKey(sellerId)) {
            String storeName = storeNames.get(sellerId);
            if (storeName != null && !storeName.isBlank()) {
                return storeName;
            }
        }
        return seller == null || seller.nickname() == null ? "店铺" + sellerId : seller.nickname();
    }

    /** 会话集合的店铺名批量快照（一次跨域，列表与线程共用）；失败降级为空（回退昵称） */
    private Map<Long, String> loadStoreNames(Collection<Long> sellerIds) {
        List<Long> distinct = sellerIds.stream()
                .filter(Objects::nonNull)
                .filter(id -> id != 0L && !Objects.equals(id, officialCsUserId))
                .distinct()
                .toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        try {
            var response = userClient.storeNames(distinct.stream()
                    .map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(",")));
            List<com.duoduo.social.client.dto.StoreBrief> rows = response == null ? null : response.data();
            if (rows == null || rows.isEmpty()) {
                return Map.of();
            }
            Map<Long, String> map = new HashMap<>();
            rows.forEach(b -> map.putIfAbsent(b.ownerUserId(), b.name()));
            return map;
        } catch (Exception e) {
            log.warn("批量取店铺名失败 ids={}", distinct, e);
            return Map.of();
        }
    }

    private MessageView toMessageView(SupportMessageEntity m, Long viewerId, ProductCardBrief product,
                                      SupportDtos.QuotedView quoted) {
        ProductCard card = product == null ? null
                : new ProductCard(product.id(), product.title(), product.mainImage(), product.minPrice());
        return new MessageView(m.getId(), m.getSenderId(), m.getSenderRole(), m.getContent(),
                m.getBizType(), m.getBizId(),
                m.getMediaUrl(), m.getThumbUrl(), m.getWidth(), m.getHeight(), m.getDurationMs(),
                m.getMetaJson(),
                m.getCreatedAt() == null ? null : m.getCreatedAt().toInstant(ZoneOffset.UTC).toString(),
                Objects.equals(m.getSenderId(), viewerId), m.getReadAt() != null, card,
                m.getRecalledAt() == null ? null : m.getRecalledAt().toInstant(ZoneOffset.UTC).toString(),
                m.getReplyToId(), quoted);
    }

    /** 取订单事实；交易域没有这张单时给 404 而不是 500 */
    private OrderClient.OrderFactBrief orderFact(String orderNo) {
        try {
            ApiResponse<OrderClient.OrderFactBrief> response = orderClient.fact(orderNo);
            OrderClient.OrderFactBrief fact = response == null ? null : response.data();
            if (fact == null) {
                throw BizException.notFound("订单");
            }
            return fact;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("取订单事实失败 orderNo={}", orderNo, e);
            throw BizException.businessRule("订单信息暂时读取不到，请稍后重试");
        }
    }

    /**
     * 订单卡快照：只落展示需要的字段，保留发送时点的状态。
     *
     * <p>不做实时联动是刻意的：客服看到的是「用户发这张卡时订单是什么状态」，
     * 而不是每次渲染都跨域查一次交易库（那会让消息列表变成 N 次远程调用）。
     */
    private String orderSnapshot(OrderClient.OrderFactBrief fact) {
        try {
            java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
            meta.put("orderNo", fact.orderNo());
            meta.put("status", fact.status());
            meta.put("statusLabel", fact.statusLabel());
            int count = fact.items() == null ? 0 : fact.items().size();
            meta.put("itemCount", count);
            java.util.List<java.util.Map<String, Object>> items = new java.util.ArrayList<>();
            if (fact.items() != null) {
                fact.items().stream().limit(3).forEach(it -> {
                    java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("title", it.title());
                    row.put("image", it.mainImage());
                    row.put("price", it.price());
                    row.put("quantity", it.quantity());
                    items.add(row);
                });
            }
            meta.put("items", items);
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(meta);
        } catch (Exception e) {
            log.warn("订单快照序列化失败 orderNo={}", fact.orderNo(), e);
            return null;
        }
    }

    /** 消息类型白名单（缺省 TEXT）。只认这七种，其余直接 400，不做"宽容解析"。 */
    private String normalizeType(String raw) {
        String type = raw == null || raw.isBlank() ? "TEXT" : raw.trim().toUpperCase();
        return switch (type) {
            case "TEXT", "PRODUCT", "IMAGE", "VIDEO", "EMOJI", "ORDER", "VOICE" -> type;
            default -> throw BizException.validation("不支持的消息类型");
        };
    }

    /** 语音上限 60 秒（与端侧录制限制一致，防直连接口灌长音频） */
    private static final int VOICE_MAX_MS = 60_000;
    /** 撤回窗口：2 分钟（与设计稿一致） */
    private static final long RECALL_WINDOW_SECONDS = 120;
    /** 撤回后的占位正文：预览/引用摘要有内容可显示，原始文本与媒体已被清除 */
    private static final String RECALLED_PLACEHOLDER = "[消息已撤回]";

    /**
     * 校验并放行引用（2026-10-24）：被引用消息必须存在且属于同一会话。
     * 摘要在读取路径服务端拼装（{@link #quotedOf}），这里只做资格校验。
     */
    private Long requireReply(Long sessionId, Long replyToId) {
        if (replyToId == null) {
            return null;
        }
        SupportMessageEntity quoted = messageMapper.selectById(replyToId);
        if (quoted == null || !Objects.equals(quoted.getSessionId(), sessionId)) {
            throw BizException.validation("引用的消息不存在或不属于当前会话");
        }
        return replyToId;
    }

    /**
     * 撤回（2026-10-24）：仅发送者本人 + 2 分钟窗口 + 幂等。
     *
     * <p>软删：行保留（审计/对账要求指得出实物），content 覆写为占位符、
     * 媒体与快照字段清空 —— 原文在数据库层就不可读，不依赖前端隐藏。
     */
    public MessageView recallByUser(Long userId, Long messageId) {
        SupportMessageEntity msg = requireMessage(messageId);
        SupportSessionEntity session = requireSession(msg.getSessionId());
        if (!Objects.equals(session.getUserId(), userId)) {
            throw BizException.forbidden("该会话不属于当前账号");
        }
        return doRecall(session, msg, userId, true);
    }

    public MessageView recallBySeller(Long sellerId, Long sessionId, Long messageId) {
        requireOwnedSession(sellerId, sessionId);
        SupportMessageEntity msg = requireMessage(messageId);
        if (!Objects.equals(msg.getSessionId(), sessionId)) {
            throw BizException.validation("该消息不属于当前会话");
        }
        return doRecall(requireSession(sessionId), msg, sellerId, false);
    }

    private MessageView doRecall(SupportSessionEntity session, SupportMessageEntity msg,
                                 Long actorId, boolean userSide) {
        if (!Objects.equals(msg.getSenderId(), actorId)) {
            throw BizException.forbidden("只能撤回自己发送的消息");
        }
        if (msg.getRecalledAt() != null) {
            // 幂等：重复撤回返回成功（消息已经是撤回态）
            return toMessageView(msg, actorId, null, null);
        }
        if (msg.getCreatedAt() == null
                || msg.getCreatedAt().isBefore(now().minusSeconds(RECALL_WINDOW_SECONDS))) {
            throw BizException.businessRule("超过 2 分钟的消息不能再撤回");
        }
        Long peer = userSide ? session.getSellerId() : session.getUserId();
        // 先推送再落库？不行 —— 推送只是信号，对端会立刻回源；必须先写后推。
        messageMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SupportMessageEntity>()
                .eq(SupportMessageEntity::getId, msg.getId())
                // ★ eq(col, null) 会生成 `recalled_at = null` 永不匹配 → 更新 0 行，必须 isNull
                .isNull(SupportMessageEntity::getRecalledAt)
                .set(SupportMessageEntity::getRecalledAt, now())
                .set(SupportMessageEntity::getContent, RECALLED_PLACEHOLDER)
                .set(SupportMessageEntity::getMediaUrl, null)
                .set(SupportMessageEntity::getThumbUrl, null)
                .set(SupportMessageEntity::getMetaJson, null));
        // 会话摘要同步为撤回文案（不改未读、不顶时间）
        SupportSessionEntity patch = new SupportSessionEntity();
        patch.setId(session.getId());
        patch.setLastMessagePreview(RECALLED_PLACEHOLDER);
        patch.setUpdatedAt(session.getUpdatedAt());
        sessionMapper.updateById(patch);
        msg.setRecalledAt(now());
        msg.setContent(RECALLED_PLACEHOLDER);
        msg.setMediaUrl(null);
        msg.setThumbUrl(null);
        msg.setMetaJson(null);
        try {
            realtime.publish(peer, com.duoduo.social.realtime.RealtimeEvent.recall(
                    "SUPPORT", String.valueOf(session.getId()), null, actorId, msg.getId()));
        } catch (Exception e) {
            log.warn("撤回信号推送失败 messageId={}（不影响撤回）", msg.getId(), e);
        }
        return toMessageView(msg, actorId, null, null);
    }

    private SupportMessageEntity requireMessage(Long messageId) {
        SupportMessageEntity msg = messageId == null ? null : messageMapper.selectById(messageId);
        if (msg == null) {
            throw BizException.notFound("消息");
        }
        return msg;
    }

    /**
     * 引用摘要拼装（2026-10-24）：按当前页消息的 replyToId 批量回源，服务端生成摘要
     * （文本前 60 字；媒体/卡片用类型占位）。被引用消息已撤回时摘要为「原消息已撤回」；
     * 引用不存在/越权（理论不可能，requireReply 拦过）降级为 null。
     * 不递归：被引用消息自身的引用不再展开。
     */
    private Map<Long, SupportDtos.QuotedView> quotedOf(List<SupportMessageEntity> messages) {
        List<Long> ids = messages.stream()
                .map(SupportMessageEntity::getReplyToId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, SupportDtos.QuotedView> map = new HashMap<>();
        for (SupportMessageEntity q : messageMapper.selectBatchIds(ids)) {
            String excerpt;
            if (q.getRecalledAt() != null) {
                excerpt = "原消息已撤回";
            } else {
                excerpt = switch (q.getBizType() == null ? "" : q.getBizType()) {
                    case "IMAGE" -> "[图片]";
                    case "VIDEO" -> "[视频]";
                    case "EMOJI" -> "[表情]";
                    case "VOICE" -> "[语音]";
                    case "PRODUCT", "ORDER" -> "[卡片]";
                    default -> {
                        String text = q.getContent() == null ? "" : q.getContent();
                        yield text.length() <= 60 ? text : text.substring(0, 60);
                    }
                };
            }
            map.put(q.getId(), new SupportDtos.QuotedView(q.getId(), q.getBizType(), excerpt));
        }
        return map;
    }

    /** 媒体地址必须是本站上传接口产出的 http(s) 地址（拒绝 javascript:/data: 之类） */
    private boolean isSafeMediaUrl(String url) {
        if (url == null || url.length() > 500) {
            return false;
        }
        return url.startsWith("http://") || url.startsWith("https://") || url.startsWith("/uploads/");
    }

    /** 会话列表/通知里的摘要：媒体消息用占位文案，不把 URL 当正文展示 */
    private String previewOf(String type, String text) {
        if ("IMAGE".equals(type)) {
            return "[图片]";
        }
        if ("VIDEO".equals(type)) {
            return "[视频]";
        }
        if ("EMOJI".equals(type)) {
            return "[表情]";
        }
        if ("VOICE".equals(type)) {
            return "[语音]";
        }
        return text;
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }

    private String normalizeContent(String content) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.length() > MAX_CONTENT) {
            throw BizException.validation("消息内容不能超过 " + MAX_CONTENT + " 字");
        }
        return trimmed;
    }

    private String abbreviate(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= PREVIEW_MAX ? content : content.substring(0, PREVIEW_MAX);
    }

    private static long nzLong(Long v) {
        return v == null ? 0L : v;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
