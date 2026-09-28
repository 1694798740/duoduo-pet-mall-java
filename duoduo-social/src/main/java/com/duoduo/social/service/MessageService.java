package com.duoduo.social.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.social.client.ProductClient;
import com.duoduo.social.client.UserClient;
import com.duoduo.social.client.dto.ProductCardBrief;
import com.duoduo.social.client.dto.UserBrief;
import com.duoduo.social.client.dto.UserRoleBrief;
import com.duoduo.social.dto.SocialDtos;
import com.duoduo.social.dto.SocialDtos.CardView;
import com.duoduo.social.dto.SocialDtos.ConversationView;
import com.duoduo.social.dto.SocialDtos.MessagePage;
import com.duoduo.social.dto.SocialDtos.MessageView;
import com.duoduo.social.dto.SocialDtos.SendMessageRequest;
import com.duoduo.social.entity.SocialMessageEntity;
import com.duoduo.social.mapper.SocialMessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 私信服务（第四十七轮）。
 *
 * <p>规则集中在这里，控制器只做参数绑定：
 * <ol>
 *   <li><b>会话键 {@code "lo:hi"}</b>：双方 userId 数值小的在前，与业务无关的纯数值约定 ——
 *       双向聊天只需一个索引即可按会话拉取；</li>
 *   <li><b>卡片消息摘要以服务端事实为准</b>：请求体只带 bizType + bizId，展示字段与摘要
 *       由本域去事实源现取（与分享圈同一纪律）；</li>
 *   <li><b>商品咨询（ASK）的收件人由商品归属卖家决定</b>，不信前端传的 userId ——
 *       否则「咨询」会被当成向任意用户发消息的通道；</li>
 *   <li><b>非互关会话每人限发一条</b>；咨询会话豁免（否则问一问不可用）。
 *       并发窗口可能多发一条，后果只是多一条消息，无资金/库存风险，不值得加锁；</li>
 *   <li><b>频控</b>：同一会话窗口期内限条数（防骚扰，429）。</li>
 * </ol>
 */
@Service
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);

    private static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final SocialMessageMapper messageMapper;
    private final FollowService followService;
    private final UserClient userClient;
    private final ProductClient productClient;
    private final SnowflakeIdGenerator idGenerator;
    private final int maxLength;
    private final int rateWindowSeconds;
    private final int rateLimit;

    public MessageService(SocialMessageMapper messageMapper,
                          FollowService followService,
                          UserClient userClient,
                          ProductClient productClient,
                          SnowflakeIdGenerator idGenerator,
                          @Value("${duoduo.social.message.max-length:1000}") int maxLength,
                          @Value("${duoduo.social.message.rate-window-seconds:60}") int rateWindowSeconds,
                          @Value("${duoduo.social.message.rate-limit:10}") int rateLimit) {
        this.messageMapper = messageMapper;
        this.followService = followService;
        this.userClient = userClient;
        this.productClient = productClient;
        this.idGenerator = idGenerator;
        this.maxLength = maxLength;
        this.rateWindowSeconds = rateWindowSeconds;
        this.rateLimit = rateLimit;
    }

    // ---------------- 写：发消息（需登录） ----------------

    public MessageView send(Long userId, SendMessageRequest request) {
        if (request.receiverId() == null) {
            throw BizException.validation("缺少 receiverId，无法确定接收人");
        }
        Long receiverId = request.receiverId();
        if (receiverId.equals(userId)) {
            throw BizException.businessRule("不能给自己发消息");
        }
        String bizType = normalizeBizType(request.bizType());
        Long bizId = request.bizId();
        String content = normalizeContent(request.content());
        if (SocialDtos.BIZ_TEXT.equals(bizType) && content.isEmpty()) {
            throw BizException.validation("消息内容不能为空");
        }

        // 卡片 / 咨询类：先取事实，再定收件人
        ProductCardBrief product = null;
        // 商品咨询会话上下文（第四十九轮）：ASK 恒取 bizId；TEXT 可显式携带（进该商品的咨询会话）
        Long contextProduct = null;
        if (SocialDtos.BIZ_ASK.equals(bizType) || SocialDtos.BIZ_PRODUCT.equals(bizType)) {
            if (bizId == null) {
                throw BizException.validation("缺少商品 ID");
            }
            product = requireProductCard(bizId);
            if (SocialDtos.BIZ_ASK.equals(bizType)) {
                // 收件人 = 商品归属卖家（服务端定人，不信前端）
                receiverId = product.sellerId();
                if (receiverId == null || receiverId == 0L) {
                    throw BizException.businessRule("该商品暂无卖家，无法咨询");
                }
                if (receiverId.equals(userId)) {
                    throw BizException.businessRule("这是你自己的商品，无需咨询");
                }
                content = content.isEmpty() ? "我想了解一下「" + product.title() + "」" : content;
                // 咨询会话按商品隔离：同一卖家卖多件商品，每件一个独立会话
                contextProduct = bizId;
            }
        } else if (SocialDtos.BIZ_SHARE.equals(bizType) || SocialDtos.BIZ_PROFILE_CARD.equals(bizType)) {
            // 分享卡 / 个人卡依赖履约域内部端点，第二批开放
            throw BizException.businessRule("该消息类型即将开放");
        } else if (SocialDtos.BIZ_TEXT.equals(bizType) && request.productId() != null) {
            // 商品会话内的后续闲聊：显式带 productId 才能落回同一商品会话
            contextProduct = request.productId();
            requireProductCard(contextProduct);
        }
        requireUserExists(receiverId);

        String conversationId = conversationId(userId, receiverId, contextProduct);

        // 条数规则：商品咨询会话整体豁免；主会话仍按「非互关每人限发一条」
        // （countAskInConversation 兼容第四十七轮旧数据：主会话里的历史 ASK 也豁免）
        boolean exempt = contextProduct != null
                || messageMapper.countAskInConversation(conversationId) > 0;
        if (!exempt && !followService.mutual(userId, receiverId)
                && messageMapper.countSentByMe(conversationId, userId) > 0) {
            throw BizException.businessRule("互相关注后才能继续聊天，先去关注 TA 吧");
        }
        // 频控（对咨询会话同样生效，防骚扰）
        if (messageMapper.countSentInWindow(conversationId, userId, rateWindowSeconds) >= rateLimit) {
            throw new BizException(ErrorCode.RATE_LIMITED, "发送太频繁，请稍后再试");
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        SocialMessageEntity entity = new SocialMessageEntity();
        entity.setId(idGenerator.nextId());
        entity.setConversationId(conversationId);
        entity.setSenderId(userId);
        entity.setReceiverId(receiverId);
        entity.setSenderRole(loadRole(userId));
        entity.setBizType(bizType);
        entity.setBizId(bizId);
        // 卡片类：用户附言优先，否则用商品标题兜底（摘要稳定，卡片视图读取时再取事实）
        entity.setContent(content.isEmpty() && product != null ? product.title() : content);
        entity.setCreatedAt(now);
        messageMapper.insert(entity);
        log.info("用户 {} 向用户 {} 发送 {} 消息（会话 {}）", userId, receiverId, bizType, conversationId);

        return toView(entity, userId, cardView(bizType, bizId, product));
    }

    // ---------------- 读：会话列表 / 会话消息 / 已读 ----------------

    /** 会话列表：每会话最新一条 + 未读数 + 对方资料 + 我是否还能发 */
    public PageResult<ConversationView> conversations(Long userId, int page, int pageSize) {
        int pageNo = Math.max(1, page);
        int size = normalizeSize(pageSize);
        List<SocialMessageEntity> rows = messageMapper.selectLastPerConversation(userId, size, (pageNo - 1) * size);
        long total = messageMapper.countConversations(userId);

        Map<String, Long> unread = unreadByConversation(userId);
        List<Long> peers = rows.stream().map(e -> peerOf(e, userId)).distinct().toList();
        Map<Long, UserBrief> briefs = followService.loadBriefs(peers);
        Set<Long> mutual = followService.mutualSet(userId, peers);
        Set<String> sentByMe = new java.util.HashSet<>(messageMapper.selectMySentConversations(userId));
        Set<String> consulting = new java.util.HashSet<>(messageMapper.selectAskConversations(userId));

        // 商品咨询会话（会话键带商品段）：批量取商品卡做会话副标题（失败降级为 null，不挡列表）
        List<Long> convProducts = rows.stream()
                .map(e -> productOfKey(e.getConversationId()))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, ProductCardBrief> convCards = loadProductCardsByIds(convProducts);

        List<ConversationView> views = rows.stream()
                .map(e -> {
                    String convId = e.getConversationId();
                    Long peer = peerOf(e, userId);
                    UserBrief brief = briefs.get(peer);
                    boolean isMutual = mutual.contains(peer);
                    Long convProduct = productOfKey(convId);
                    ProductCardBrief card = convProduct == null ? null : convCards.get(convProduct);
                    // 能发 = 互关 / 咨询会话（商品会话恒豁免，consulting 含历史 ASK 会话）/ 我还没发过第一条
                    boolean canSend = isMutual || consulting.contains(convId)
                            || convProduct != null || !sentByMe.contains(convId);
                    return new ConversationView(
                            peer,
                            brief == null || brief.nickname() == null ? "用户" + peer : brief.nickname(),
                            brief == null ? null : brief.avatar(),
                            e.getContent(),
                            e.getBizType(),
                            e.getBizId(),
                            iso(e.getCreatedAt()),
                            unread.getOrDefault(convId, 0L),
                            isMutual,
                            canSend,
                            consulting.contains(convId) || convProduct != null,
                            convProduct,
                            card == null ? null : card.title(),
                            card == null ? null : card.mainImage());
                })
                .toList();
        return PageResult.of(views, total, pageNo, size);
    }

    /** 与某人的双向消息（时间正序返回，分页取**最新**一页）；productId 非空时读商品咨询会话 */
    public MessagePage conversation(Long userId, Long peerId, Long productId, int page, int pageSize) {
        if (peerId == null) {
            throw BizException.validation("缺少对方用户");
        }
        if (productId != null) {
            requireProductCard(productId); // 幽灵商品 404，与会话键拼装同源
        }
        String conversationId = conversationId(userId, peerId, productId);
        int pageNo = Math.max(1, page);
        int size = normalizeSize(pageSize);
        Page<SocialMessageEntity> result = messageMapper.selectPage(new Page<>(pageNo, size),
                new LambdaQueryWrapper<SocialMessageEntity>()
                        .eq(SocialMessageEntity::getConversationId, conversationId)
                        .orderByDesc(SocialMessageEntity::getId));
        // 分页取「最新一页」，展示按时间正序
        List<SocialMessageEntity> ordered = result.getRecords().reversed();

        boolean mutual = followService.mutual(userId, peerId);
        boolean productConv = productOfKey(conversationId) != null;
        boolean consulting = productConv || messageMapper.countAskInConversation(conversationId) > 0;
        boolean canSend = mutual || consulting
                || messageMapper.countSentByMe(conversationId, userId) == 0;

        Map<Long, UserBrief> briefs = followService.loadBriefs(List.of(peerId));
        UserBrief peer = briefs.get(peerId);

        Map<Long, ProductCardBrief> cards = loadProductCards(ordered);
        // bizId 为 null 的普通文本消息不能直接 Map.get(null)：不可变 Map（Map.of）对 null 键查询抛 NPE
        List<MessageView> views = ordered.stream()
                .map(e -> toView(e, userId,
                        cardView(e.getBizType(), e.getBizId(),
                                e.getBizId() == null ? null : cards.get(e.getBizId()))))
                .toList();
        return new MessagePage(views, result.getTotal(), pageNo, size,
                peerId,
                peer == null || peer.nickname() == null ? "用户" + peerId : peer.nickname(),
                peer == null ? null : peer.avatar(),
                mutual, canSend, consulting, productOfKey(conversationId));
    }

    /** 打开会话：把「对方发给我、还没读」的批量置已读（幂等）；productId 非空时读商品咨询会话 */
    public long markRead(Long userId, Long peerId, Long productId) {
        SocialMessageEntity patch = new SocialMessageEntity();
        patch.setReadAt(LocalDateTime.now(ZoneOffset.UTC));
        return messageMapper.update(patch, new LambdaQueryWrapper<SocialMessageEntity>()
                .eq(SocialMessageEntity::getConversationId, conversationId(userId, peerId, productId))
                .eq(SocialMessageEntity::getReceiverId, userId)
                .isNull(SocialMessageEntity::getReadAt));
    }

    // ---------------- 内部：校验与装配 ----------------

    private String normalizeBizType(String bizType) {
        String type = bizType == null || bizType.isBlank() ? SocialDtos.BIZ_TEXT : bizType.trim();
        return switch (type) {
            case SocialDtos.BIZ_TEXT, SocialDtos.BIZ_ASK, SocialDtos.BIZ_PRODUCT,
                 SocialDtos.BIZ_SHARE, SocialDtos.BIZ_PROFILE_CARD -> type;
            default -> throw BizException.validation("不支持的消息类型");
        };
    }

    private String normalizeContent(String content) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.length() > maxLength) {
            throw BizException.validation("消息内容不能超过 " + maxLength + " 字");
        }
        return trimmed;
    }

    private int normalizeSize(int pageSize) {
        return pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
    }

    /** 商品卡必须取得到：问一问 / 商品卡都指向真实商品（404） */
    private ProductCardBrief requireProductCard(Long spuId) {
        Map<Long, ProductCardBrief> cards = loadProductCardsByIds(List.of(spuId));
        ProductCardBrief brief = cards.get(spuId);
        if (brief == null) {
            throw BizException.notFound("商品");
        }
        return brief;
    }

    /** 批量取商品卡事实（卡片视图读取时现取；失败降级为无卡片，不让整个会话打不开） */
    private Map<Long, ProductCardBrief> loadProductCards(Collection<SocialMessageEntity> messages) {
        List<Long> spuIds = messages.stream()
                .filter(e -> SocialDtos.BIZ_ASK.equals(e.getBizType()) || SocialDtos.BIZ_PRODUCT.equals(e.getBizType()))
                .map(SocialMessageEntity::getBizId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return loadProductCardsByIds(spuIds);
    }

    private Map<Long, ProductCardBrief> loadProductCardsByIds(Collection<Long> spuIds) {
        List<Long> ids = spuIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            var response = productClient.cards(ids.stream().map(String::valueOf).collect(Collectors.joining(",")));
            List<ProductCardBrief> cards = response == null ? null : response.data();
            if (cards == null) {
                return Map.of();
            }
            return cards.stream().collect(Collectors.toMap(ProductCardBrief::id, Function.identity(), (a, b) -> a));
        } catch (Exception e) {
            log.warn("取商品卡失败，降级为无卡片 spuIds={}", ids, e);
            return Map.of();
        }
    }

    private com.duoduo.social.dto.SocialDtos.CardView cardView(String bizType, Long bizId, ProductCardBrief brief) {
        if (brief == null || bizId == null) {
            return null;
        }
        boolean productLike = SocialDtos.BIZ_ASK.equals(bizType) || SocialDtos.BIZ_PRODUCT.equals(bizType);
        if (!productLike) {
            return null;
        }
        return new CardView(
                SocialDtos.BIZ_PRODUCT, brief.id(), brief.title(), brief.mainImage(),
                brief.minPrice(), null, null);
    }

    private void requireUserExists(Long userId) {
        try {
            var response = userClient.briefs(String.valueOf(userId));
            List<UserBrief> briefs = response == null ? null : response.data();
            if (briefs == null || briefs.isEmpty()) {
                throw BizException.notFound("用户");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // 用户域不可用：发消息是写操作，但对方存在性无法确认 —— 宁可让用户稍后再试
            log.warn("校验接收人失败 userId={}", userId, e);
            throw BizException.businessRule("对方资料暂时获取不到，请稍后再试");
        }
    }

    /** 发送方角色快照（通知按它分流好友 / 商家消息）；用户域不可用降级 USER */
    private String loadRole(Long userId) {
        try {
            var response = userClient.roles(String.valueOf(userId));
            List<UserRoleBrief> roles = response == null ? null : response.data();
            if (roles != null && !roles.isEmpty() && roles.get(0).role() != null) {
                return roles.get(0).role();
            }
        } catch (Exception e) {
            log.warn("取发送方角色失败，降级 USER userId={}", userId, e);
        }
        return "USER";
    }

    private Map<String, Long> unreadByConversation(Long userId) {
        return messageMapper.selectUnreadGrouped(userId).stream()
                .collect(Collectors.toMap(SocialMessageMapper.UnreadRow::getConversationId,
                        SocialMessageMapper.UnreadRow::getUnread, (a, b) -> a));
    }

    private Long peerOf(SocialMessageEntity e, Long userId) {
        return e.getSenderId().equals(userId) ? e.getReceiverId() : e.getSenderId();
    }

    private MessageView toView(SocialMessageEntity e, Long viewerId, com.duoduo.social.dto.SocialDtos.CardView card) {
        return new MessageView(
                e.getId(),
                e.getSenderId(),
                e.getReceiverId(),
                e.getSenderRole(),
                e.getBizType(),
                e.getBizId(),
                e.getContent(),
                iso(e.getCreatedAt()),
                e.getSenderId().equals(viewerId),
                e.getReadAt() != null,
                card);
    }

    /** 会话键：数值小的在前（纯数值约定，与业务无关）——主会话（互关私聊 / 商品卡分享） */
    private String conversationId(Long a, Long b) {
        return a.longValue() <= b.longValue() ? a + ":" + b : b + ":" + a;
    }

    /**
     * 会话键（第四十九轮）：商品咨询会话在主键后追加商品段 {@code lo:hi:p{spuId}}。
     *
     * <p>同一对用户可以同时有「主会话 + 每件商品一个咨询会话」；键由服务端拼装，
     * 前端只能通过 send 的 productId / thread 的查询参数参与，无法伪造到任意会话
     * （lo:hi 由登录态 + 服务端定人保证必然包含自己）。VARCHAR(64)：2×19 位 id + 2 分隔
     * + "p" + 19 位 = 60，够用。
     */
    private String conversationId(Long a, Long b, Long productId) {
        String base = conversationId(a, b);
        return productId == null ? base : base + ":p" + productId;
    }

    /** 从会话键解析商品段（主会话返回 null）——纯数字 userId 保证 ":p" 模式无歧义 */
    private Long productOfKey(String conversationKey) {
        if (conversationKey == null) {
            return null;
        }
        int idx = conversationKey.lastIndexOf(":p");
        if (idx < 0) {
            return null;
        }
        try {
            return Long.parseLong(conversationKey.substring(idx + 2));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String iso(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC).toString();
    }
}
