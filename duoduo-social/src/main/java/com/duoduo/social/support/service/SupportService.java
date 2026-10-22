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
    private final Long officialCsUserId;

    public SupportService(SupportSessionMapper sessionMapper,
                          SupportMessageMapper messageMapper,
                          UserClient userClient,
                          ProductClient productClient,
                          SnowflakeIdGenerator idGenerator,
                          NoticeService noticeService,
                          OrderClient orderClient,
                          @Value("${duoduo.social.official-cs-user-id:710000000000000001}")
                          Long officialCsUserId) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.userClient = userClient;
        this.productClient = productClient;
        this.idGenerator = idGenerator;
        this.noticeService = noticeService;
        this.orderClient = orderClient;
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
        SessionView session = ensureForUser(userId, sellerId);
        return thread(session, page, pageSize, userId, true);
    }

    /** 用户发消息；{@code productId} 非空时附带商品卡上下文（自营/第三方商品都走这里） */
    public MessageView sendByUser(Long userId, SendRequest request) {
        String type = normalizeType(request.type());
        boolean media = "IMAGE".equals(type) || "VIDEO".equals(type) || "EMOJI".equals(type);

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

        SupportSessionEntity session = ensureSession(target, userId);
        // 会话被商家关闭后，买家再发消息自动重开：否则用户卡在「已关闭」里问不下去，
        // 而重开本来也不产生任何数据风险（状态字段而已）。
        if ("CLOSED".equalsIgnoreCase(session.getStatus())) {
            SupportSessionEntity reopen = new SupportSessionEntity();
            reopen.setId(session.getId());
            reopen.setStatus("OPEN");
            reopen.setUpdatedAt(now());
            sessionMapper.updateById(reopen);
            session.setStatus("OPEN");
        }
        if (messageMapper.countSentInWindow(session.getId(), userId, RATE_WINDOW_SECONDS) >= USER_RATE_LIMIT) {
            throw new BizException(ErrorCode.RATE_LIMITED, "发送太频繁，请稍后再试");
        }

        SupportMessageEntity entity = insert(session.getId(), userId, "USER", text, type,
                product == null ? null : product.id(),
                request.mediaUrl(), request.thumbUrl(),
                request.width(), request.height(), request.durationMs(), orderSnapshot);
        touchSession(session, previewOf(type, text), true);
        return toMessageView(entity, userId, product);
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

    // ---------------- 商家侧（工作台） ----------------

    /**
     * 店铺的客服会话列表。
     *
     * <p>{@code sellerId} 由调用方从登录态推导（控制器负责），本方法不做「我是谁」判断 ——
     * 这样它也能被内部对账脚本按指定店铺复用，而权限仍只有一处（控制器）。
     */
    public PageResult<SessionView> listForSeller(Long sellerId, String status, boolean onlyUnread,
                                                 int page, int pageSize) {
        int p = Math.max(1, page);
        int size = Math.max(1, Math.min(pageSize <= 0 ? DEFAULT_PAGE_SIZE : pageSize, MAX_PAGE_SIZE));
        LambdaQueryWrapper<SupportSessionEntity> wrapper = new LambdaQueryWrapper<SupportSessionEntity>()
                .eq(SupportSessionEntity::getSellerId, sellerId)
                .orderByDesc(SupportSessionEntity::getLastMessageAt)
                .orderByDesc(SupportSessionEntity::getId);
        String st = status == null ? "" : status.trim().toUpperCase();
        if (!st.isEmpty()) {
            wrapper.eq(SupportSessionEntity::getStatus, st);
        }
        if (onlyUnread) {
            wrapper.gt(SupportSessionEntity::getUnreadForSeller, 0);
        }
        Page<SupportSessionEntity> result = sessionMapper.selectPage(new Page<>(p, size), wrapper);
        Map<Long, UserBrief> briefs = loadBriefs(result.getRecords().stream()
                .flatMap(s -> java.util.stream.Stream.of(s.getSellerId(), s.getUserId()))
                .toList());
        List<SessionView> views = result.getRecords().stream()
                .map(s -> toSessionView(s, briefs,
                        loadStoreNames(result.getRecords().stream()
                                .map(SupportSessionEntity::getSellerId).toList()),
                        false)).toList();
        return PageResult.of(views, result.getTotal(), p, size);
    }

    public ThreadView sellerThread(Long sellerId, Long sessionId, int page, int pageSize) {
        SupportSessionEntity session = requireOwnedSession(sellerId, sessionId);
        Map<Long, UserBrief> briefs = loadBriefs(List.of(session.getSellerId(), session.getUserId()));
        return thread(toSessionView(session, briefs, loadStoreNames(List.of(session.getSellerId())), false),
                page, pageSize, sellerId, false);
    }

    public MessageView sendBySeller(Long sellerId, Long sessionId, SellerSendRequest request) {
        SupportSessionEntity session = requireOwnedSession(sellerId, sessionId);
        requireNotClosed(session);
        String type = normalizeType(request.type());
        boolean media = "IMAGE".equals(type) || "VIDEO".equals(type) || "EMOJI".equals(type);
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
        if (messageMapper.countSentInWindow(sessionId, sellerId, RATE_WINDOW_SECONDS) >= SELLER_RATE_LIMIT) {
            throw new BizException(ErrorCode.RATE_LIMITED, "发送太频繁，请稍后再试");
        }
        SupportMessageEntity entity = insert(sessionId, sellerId, "SELLER", text, type, null,
                request.mediaUrl(), request.thumbUrl(),
                request.width(), request.height(), request.durationMs(), null);
        touchSession(session, previewOf(type, text), false);
        // 通知中心可见性：客服会话独立成表后，用户在通知中心看不到任何痕迹。
        // best-effort：通知写失败不能把「已发出的回复」判成失败（否则用户会重发）。
        try {
            noticeService.pushSupportNotice(session.getUserId(), session.getSellerId(),
                    session.getId(), previewOf(type, text));
        } catch (Exception e) {
            log.warn("客服通知写入失败 sessionId={}（不影响回复）", session.getId(), e);
        }
        return toMessageView(entity, sellerId, null);
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
        return n;
    }

    public long unreadForSeller(Long sellerId) {
        return sessionMapper.sumUnreadForSeller(sellerId);
    }

    /** 关闭 / 重开会话（商家侧；用户再发消息会自动保持关闭，需商家重开） */
    public void setStatus(Long sellerId, Long sessionId, String status) {
        SupportSessionEntity session = requireOwnedSession(sellerId, sessionId);
        String st = "OPEN".equalsIgnoreCase(status) ? "OPEN" : "CLOSED";
        SupportSessionEntity patch = new SupportSessionEntity();
        patch.setId(session.getId());
        patch.setStatus(st);
        patch.setUpdatedAt(now());
        sessionMapper.updateById(patch);
    }

    // ---------------- 内部 ----------------

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
                                        String metaJson) {
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
        int p = Math.max(1, page);
        int size = Math.max(1, Math.min(pageSize <= 0 ? DEFAULT_PAGE_SIZE : pageSize, MAX_PAGE_SIZE));
        Page<SupportMessageEntity> result = messageMapper.selectPage(new Page<>(p, size),
                new LambdaQueryWrapper<SupportMessageEntity>()
                        .eq(SupportMessageEntity::getSessionId, session.id())
                        .orderByDesc(SupportMessageEntity::getId));
        List<SupportMessageEntity> ordered = new ArrayList<>(result.getRecords());
        java.util.Collections.reverse(ordered); // 分页取最新一页，展示按时间正序

        Map<Long, ProductCardBrief> products = loadProducts(ordered);
        List<MessageView> items = ordered.stream()
                .map(m -> toMessageView(m, viewerId,
                        m.getBizId() == null ? null : products.get(m.getBizId())))
                .toList();
        // 已读回执：对端读到哪条 + 我发出的还有几条没被读。
        // 游标在会话行上，这里按主键回读一次（PK 查询，轮询频率下可忽略）。
        SupportSessionEntity raw = requireSession(session.id());
        long peerCursor = userSide ? nzLong(raw.getSellerLastReadId()) : nzLong(raw.getUserLastReadId());
        long myUnread = messageMapper.countSentAfter(session.id(), viewerId, peerCursor);
        return new ThreadView(session.id(), session.sellerId(), session.sellerName(), session.sellerAvatar(),
                session.userId(), session.userName(), session.userAvatar(), session.status(),
                peerCursor, myUnread, items, result.getTotal(), p, size);
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
                s.getStatus());
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

    private MessageView toMessageView(SupportMessageEntity m, Long viewerId, ProductCardBrief product) {
        ProductCard card = product == null ? null
                : new ProductCard(product.id(), product.title(), product.mainImage(), product.minPrice());
        return new MessageView(m.getId(), m.getSenderId(), m.getSenderRole(), m.getContent(),
                m.getBizType(), m.getBizId(),
                m.getMediaUrl(), m.getThumbUrl(), m.getWidth(), m.getHeight(), m.getDurationMs(),
                m.getMetaJson(),
                m.getCreatedAt() == null ? null : m.getCreatedAt().toInstant(ZoneOffset.UTC).toString(),
                Objects.equals(m.getSenderId(), viewerId), m.getReadAt() != null, card);
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

    /** 消息类型白名单（缺省 TEXT）。只认这六种，其余直接 400，不做"宽容解析"。 */
    private String normalizeType(String raw) {
        String type = raw == null || raw.isBlank() ? "TEXT" : raw.trim().toUpperCase();
        return switch (type) {
            case "TEXT", "PRODUCT", "IMAGE", "VIDEO", "EMOJI", "ORDER" -> type;
            default -> throw BizException.validation("不支持的消息类型");
        };
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
