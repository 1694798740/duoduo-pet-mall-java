package com.duoduo.social.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.social.client.ListingClient;
import com.duoduo.social.client.ProductClient;
import com.duoduo.social.client.UserClient;
import com.duoduo.social.client.dto.ListingCardBrief;
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
 *       否则「咨询」会被当成向任意用户发消息的通道；闲置咨询（LISTING，第五十三轮）同款纪律；</li>
 *   <li><b>非互关会话每人限发一条</b>；咨询会话豁免（否则问一问不可用）。
 *       并发窗口可能多发一条，后果只是多一条消息，无资金/库存风险，不值得加锁；</li>
 *   <li><b>频控</b>：同一会话窗口期内限条数（防骚扰，429）。</li>
 * </ol>
 *
 * <p>会话上下文（第四十九轮引入、第五十三轮扩展）：咨询会话在主键后追加一段 ——
 * 商品咨询 {@code lo:hi:p{spuId}}、闲置咨询 {@code lo:hi:l{listingId}}，
 * 两类上下文互斥且与纯数字 userId 无歧义。上下文由服务端拼装，前端只能通过
 * send 的 productId / listingId 或会话查询参数参与，无法伪造到任意会话。
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
    private final ListingClient listingClient;
    private final SnowflakeIdGenerator idGenerator;
    private final NoticeService noticeService;
    private final int maxLength;
    private final int rateWindowSeconds;
    private final int rateLimit;

    public MessageService(SocialMessageMapper messageMapper,
                          FollowService followService,
                          UserClient userClient,
                          ProductClient productClient,
                          ListingClient listingClient,
                          SnowflakeIdGenerator idGenerator,
                          NoticeService noticeService,
                          @Value("${duoduo.social.message.max-length:1000}") int maxLength,
                          @Value("${duoduo.social.message.rate-window-seconds:60}") int rateWindowSeconds,
                          @Value("${duoduo.social.message.rate-limit:10}") int rateLimit) {
        this.messageMapper = messageMapper;
        this.followService = followService;
        this.userClient = userClient;
        this.productClient = productClient;
        this.listingClient = listingClient;
        this.idGenerator = idGenerator;
        this.noticeService = noticeService;
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
        if (request.productId() != null && request.listingId() != null) {
            // 两类咨询上下文互斥：同时携带意味着前端拼装错误，宁可 400 也不能猜一个落进去
            throw BizException.validation("productId 与 listingId 不能同时携带");
        }
        String bizType = normalizeBizType(request.bizType());
        Long bizId = request.bizId();
        String content = normalizeContent(request.content());
        if (SocialDtos.BIZ_TEXT.equals(bizType) && content.isEmpty()) {
            throw BizException.validation("消息内容不能为空");
        }

        // 卡片 / 咨询类：先取事实，再定收件人
        ProductCardBrief product = null;
        ListingCardBrief listing = null;
        // 咨询会话上下文（第四十九轮商品 / 第五十三轮闲置）：ASK/LISTING 恒取 bizId；
        // TEXT 可显式携带（进该商品/闲置的咨询会话）
        ConvContext context = ConvContext.none();
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
                context = ConvContext.product(bizId);
            }
        } else if (SocialDtos.BIZ_LISTING.equals(bizType)) {
            // 闲置咨询（第五十三轮）：与商品咨询同款纪律 —— 收件人按闲置归属定，不信前端
            if (bizId == null) {
                throw BizException.validation("缺少闲置 ID");
            }
            listing = requireListingCard(bizId);
            receiverId = listing.sellerId();
            if (receiverId == null || receiverId == 0L) {
                throw BizException.businessRule("该闲置暂无卖家，无法咨询");
            }
            if (receiverId.equals(userId)) {
                throw BizException.businessRule("这是你自己发布的闲置，无需咨询");
            }
            content = content.isEmpty() ? "我想了解一下「" + listing.title() + "」" : content;
            context = ConvContext.listing(bizId);
        } else if (SocialDtos.BIZ_SHARE.equals(bizType) || SocialDtos.BIZ_PROFILE_CARD.equals(bizType)) {
            // 分享卡 / 个人卡依赖履约域内部端点，第二批开放
            throw BizException.businessRule("该消息类型即将开放");
        } else if (SocialDtos.BIZ_TEXT.equals(bizType) && request.productId() != null) {
            // 商品会话内的后续闲聊：显式带 productId 才能落回同一商品会话
            context = ConvContext.product(request.productId());
            requireProductCard(context.productId());
        } else if (SocialDtos.BIZ_TEXT.equals(bizType) && request.listingId() != null) {
            // 闲置会话内的后续闲聊（第五十三轮）：语义与商品段完全平行
            context = ConvContext.listing(request.listingId());
            requireListingCard(context.listingId());
        }
        requireUserExists(receiverId);

        String conversationId = conversationId(userId, receiverId, context);

        // 条数规则：咨询会话（带上下文段）整体豁免；主会话仍按「非互关每人限发一条」
        // （countAskInConversation 兼容第四十七轮旧数据：主会话里的历史 ASK 也豁免）
        boolean exempt = context.present()
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
        // 卡片类：用户附言优先，否则用商品/闲置标题兜底（摘要稳定，卡片视图读取时再取事实）
        String cardTitle = product != null ? product.title() : listing != null ? listing.title() : null;
        entity.setContent(content.isEmpty() && cardTitle != null ? cardTitle : content);
        entity.setCreatedAt(now);
        messageMapper.insert(entity);
        log.info("用户 {} 向用户 {} 发送 {} 消息（会话 {}）", userId, receiverId, bizType, conversationId);

        // 「想要」上报（第五十四轮）：只有 LISTING（= 向闲置卖家发出的咨询）计入想要人数。
        //  * 为什么只认 LISTING：ASK/product 是商城商品咨询、TEXT 是会话内闲聊 ——
        //    只有「点了我想要」这一次才是明确的意向表达。同会话后续消息不会再计，
        //    且去重键在商品域，即使重复调用也只记一次。
        //  * 为什么放在 insert 之后：消息先落库再计意向，避免「统计成功、消息失败」的假象。
        if (SocialDtos.BIZ_LISTING.equals(bizType) && bizId != null) {
            markWantQuietly(bizId, userId);
        }

        // 私信到达通知（第五十五轮）：给收件人写一条 FRIEND / MERCHANT。
        //  * 为什么压在最后：消息已落库，通知只是「提醒」——提醒写入失败必须吞掉
        //    （见 notifyReceiverQuietly 注释），所以放在「发送已经成立」之后；
        //  * 为什么在 social 内部而不是让前端再调一次：收件人的提醒不该依赖发送方的网络，
        //    前端多调一次就多一个「消息发出去了、通知没写」的失败窗口。
        notifyReceiverQuietly(entity);

        return toView(entity, userId, cardView(bizType, bizId, product, listing));
    }

    /**
     * 私信到达通知（第五十五轮）：best-effort，失败只记日志。
     *
     * <p>与 {@link #markWantQuietly} 同一条纪律，但理由更硬：消息此时**已经落库**，
     * 若因附属写入失败把整次发送判为失败，用户看到的「发送失败」与真实状态相反 ——
     * 用户会重发，于是对方收到两条一样的消息。宁可少一条提醒，也不能多一条重复消息。
     */
    private void notifyReceiverQuietly(SocialMessageEntity entity) {
        try {
            noticeService.pushMessageNotice(entity);
        } catch (Exception e) {
            log.warn("私信通知写入失败 messageId={} receiverId={}（不影响消息发送）",
                    entity.getId(), entity.getReceiverId(), e);
        }
    }

    /**
     * 「想要」上报（第五十四轮）：best-effort，失败只记日志。
     *
     * <p>这里刻意<b>吞掉</b>异常，理由不是「省事」而是优先级：
     * 消息此时已经落库并即将返回成功，若因为一个附属统计把整次发送判为失败，
     * 用户看到的「发送失败」与真实状态相反（消息其实发出去了）—— 那比计数不准更难排查。
     * 商品域挂了也不影响聊天，这符合「聊天是主链路、统计是附属」的取舍。
     */
    private void markWantQuietly(Long listingId, Long buyerId) {
        try {
            listingClient.markWant(listingId, buyerId);
        } catch (Exception e) {
            log.warn("想要人数上报失败 listingId={} buyerId={}（不影响消息发送）", listingId, buyerId, e);
        }
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

        // 咨询会话（会话键带上下文段）：批量取商品卡 / 闲置卡做会话副标题（失败降级为 null，不挡列表）
        List<Long> convProducts = new java.util.ArrayList<>();
        List<Long> convListings = new java.util.ArrayList<>();
        for (SocialMessageEntity e : rows) {
            ConvContext ctx = contextOfKey(e.getConversationId());
            if (ctx.productId() != null) {
                convProducts.add(ctx.productId());
            } else if (ctx.listingId() != null) {
                convListings.add(ctx.listingId());
            }
        }
        Map<Long, ProductCardBrief> convCards = loadProductCardsByIds(convProducts);
        Map<Long, ListingCardBrief> convListingCards = loadListingCardsByIds(convListings);

        List<ConversationView> views = rows.stream()
                .map(e -> {
                    String convId = e.getConversationId();
                    Long peer = peerOf(e, userId);
                    UserBrief brief = briefs.get(peer);
                    boolean isMutual = mutual.contains(peer);
                    ConvContext ctx = contextOfKey(convId);
                    ProductCardBrief card = ctx.productId() == null ? null : convCards.get(ctx.productId());
                    ListingCardBrief listingCard = ctx.listingId() == null ? null : convListingCards.get(ctx.listingId());
                    // 能发 = 互关 / 咨询会话（带上下文段恒豁免，consulting 含历史 ASK 会话）/ 我还没发过第一条
                    boolean canSend = isMutual || consulting.contains(convId)
                            || ctx.present() || !sentByMe.contains(convId);
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
                            consulting.contains(convId) || ctx.present(),
                            ctx.productId(),
                            card == null ? null : card.title(),
                            card == null ? null : card.mainImage(),
                            ctx.listingId(),
                            listingCard == null ? null : listingCard.title(),
                            listingCard == null ? null : listingCard.mainImage());
                })
                .toList();
        return PageResult.of(views, total, pageNo, size);
    }

    /** 与某人的双向消息（时间正序返回，分页取**最新**一页）；productId/listingId 非空时读对应咨询会话 */
    public MessagePage conversation(Long userId, Long peerId, Long productId, Long listingId,
                                    int page, int pageSize) {
        if (peerId == null) {
            throw BizException.validation("缺少对方用户");
        }
        if (productId != null && listingId != null) {
            throw BizException.validation("productId 与 listingId 不能同时携带");
        }
        if (productId != null) {
            requireProductCard(productId); // 幽灵商品 404，与会话键拼装同源
        }
        if (listingId != null) {
            requireListingCard(listingId); // 幽灵闲置 404（第五十三轮，同款纪律）
        }
        ConvContext queryContext = productId != null ? ConvContext.product(productId)
                : listingId != null ? ConvContext.listing(listingId) : ConvContext.none();
        String conversationId = conversationId(userId, peerId, queryContext);
        int pageNo = Math.max(1, page);
        int size = normalizeSize(pageSize);
        Page<SocialMessageEntity> result = messageMapper.selectPage(new Page<>(pageNo, size),
                new LambdaQueryWrapper<SocialMessageEntity>()
                        .eq(SocialMessageEntity::getConversationId, conversationId)
                        .orderByDesc(SocialMessageEntity::getId));
        // 分页取「最新一页」，展示按时间正序
        List<SocialMessageEntity> ordered = result.getRecords().reversed();

        boolean mutual = followService.mutual(userId, peerId);
        ConvContext keyContext = contextOfKey(conversationId);
        boolean consulting = keyContext.present() || messageMapper.countAskInConversation(conversationId) > 0;
        boolean canSend = mutual || consulting
                || messageMapper.countSentByMe(conversationId, userId) == 0;

        Map<Long, UserBrief> briefs = followService.loadBriefs(List.of(peerId));
        UserBrief peer = briefs.get(peerId);

        Map<Long, ProductCardBrief> cards = loadProductCards(ordered);
        Map<Long, ListingCardBrief> listingCards = loadListingCards(ordered);
        // bizId 为 null 的普通文本消息不能直接 Map.get(null)：不可变 Map（Map.of）对 null 键查询抛 NPE
        List<MessageView> views = ordered.stream()
                .map(e -> toView(e, userId,
                        cardView(e.getBizType(), e.getBizId(),
                                e.getBizId() == null ? null : cards.get(e.getBizId()),
                                e.getBizId() == null ? null : listingCards.get(e.getBizId()))))
                .toList();
        return new MessagePage(views, result.getTotal(), pageNo, size,
                peerId,
                peer == null || peer.nickname() == null ? "用户" + peerId : peer.nickname(),
                peer == null ? null : peer.avatar(),
                mutual, canSend, consulting, keyContext.productId(), keyContext.listingId());
    }

    /** 打开会话：把「对方发给我、还没读」的批量置已读（幂等）；productId/listingId 语义同上 */
    public long markRead(Long userId, Long peerId, Long productId, Long listingId) {
        SocialMessageEntity patch = new SocialMessageEntity();
        patch.setReadAt(LocalDateTime.now(ZoneOffset.UTC));
        return messageMapper.update(patch, new LambdaQueryWrapper<SocialMessageEntity>()
                .eq(SocialMessageEntity::getConversationId,
                        conversationId(userId, peerId, productId != null ? ConvContext.product(productId)
                                : listingId != null ? ConvContext.listing(listingId) : ConvContext.none()))
                .eq(SocialMessageEntity::getReceiverId, userId)
                .isNull(SocialMessageEntity::getReadAt));
    }

    /**
     * 我的私信未读总数（第五十五轮）：tabBar 角标口径的一半（另一半是通知，见 {@link UnreadService}）。
     *
     * <p>此前小程序要拉 50 条会话在内存里求和：会话数超过一页时角标会偏小，
     * 且这个数字在「回到前台 / 60 秒轮询 / 每个 tab 页 onShow」都会被重算一次 ——
     * 一个 COUNT 既准确又便宜。
     */
    public long unreadCount(Long userId) {
        return messageMapper.countUnread(userId);
    }

    // ---------------- 内部：校验与装配 ----------------

    private String normalizeBizType(String bizType) {
        String type = bizType == null || bizType.isBlank() ? SocialDtos.BIZ_TEXT : bizType.trim();
        return switch (type) {
            case SocialDtos.BIZ_TEXT, SocialDtos.BIZ_ASK, SocialDtos.BIZ_PRODUCT,
                 SocialDtos.BIZ_SHARE, SocialDtos.BIZ_PROFILE_CARD, SocialDtos.BIZ_LISTING -> type;
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

    /** 闲置卡必须取得到：闲置咨询都指向真实闲置（404，第五十三轮，与商品同款纪律） */
    private ListingCardBrief requireListingCard(Long listingId) {
        Map<Long, ListingCardBrief> cards = loadListingCardsByIds(List.of(listingId));
        ListingCardBrief brief = cards.get(listingId);
        if (brief == null) {
            throw BizException.notFound("闲置商品");
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

    /** 批量取闲置卡事实（LISTING 消息，第五十三轮）；降级纪律与商品卡相同 */
    private Map<Long, ListingCardBrief> loadListingCards(Collection<SocialMessageEntity> messages) {
        List<Long> listingIds = messages.stream()
                .filter(e -> SocialDtos.BIZ_LISTING.equals(e.getBizType()))
                .map(SocialMessageEntity::getBizId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return loadListingCardsByIds(listingIds);
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

    private Map<Long, ListingCardBrief> loadListingCardsByIds(Collection<Long> listingIds) {
        List<Long> ids = listingIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            var response = listingClient.cards(ids.stream().map(String::valueOf).collect(Collectors.joining(",")));
            List<ListingCardBrief> cards = response == null ? null : response.data();
            if (cards == null) {
                return Map.of();
            }
            return cards.stream().collect(Collectors.toMap(ListingCardBrief::id, Function.identity(), (a, b) -> a));
        } catch (Exception e) {
            log.warn("取闲置卡失败，降级为无卡片 listingIds={}", ids, e);
            return Map.of();
        }
    }

    private com.duoduo.social.dto.SocialDtos.CardView cardView(String bizType, Long bizId,
                                                               ProductCardBrief brief, ListingCardBrief listing) {
        if (bizId == null) {
            return null;
        }
        boolean productLike = SocialDtos.BIZ_ASK.equals(bizType) || SocialDtos.BIZ_PRODUCT.equals(bizType);
        if (productLike && brief != null) {
            // ASK / PRODUCT 的卡片统一按商品卡渲染（前端跳商城详情）
            return new CardView(
                    SocialDtos.BIZ_PRODUCT, brief.id(), brief.title(), brief.mainImage(),
                    brief.minPrice(), null, null);
        }
        if (SocialDtos.BIZ_LISTING.equals(bizType) && listing != null) {
            // 闲置卡（第五十三轮）：type=LISTING，前端跳闲置详情
            return new CardView(
                    SocialDtos.BIZ_LISTING, listing.id(), listing.title(), listing.mainImage(),
                    listing.price(), null, null);
        }
        return null;
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
     * 会话键（第四十九轮商品 / 第五十三轮闲置）：咨询会话在主键后追加上下文段 ——
     * 商品 {@code lo:hi:p{spuId}}、闲置 {@code lo:hi:l{listingId}}。
     *
     * <p>同一对用户可以同时有「主会话 + 每件商品/闲置一个咨询会话」；键由服务端拼装，
     * 前端只能通过 send 的 productId / listingId 或会话查询参数参与，无法伪造到任意会话
     * （lo:hi 由登录态 + 服务端定人保证必然包含自己）。两类段互斥；段标记是单字母且
     * userId 为纯数字，因此 {@code ":p"} / {@code ":l"} 的解析无歧义。
     * VARCHAR(64)：2×19 位 id + 2 分隔 + 1 标记 + 19 位 = 60，够用。
     */
    private String conversationId(Long a, Long b, ConvContext context) {
        String base = conversationId(a, b);
        return base + context.suffix();
    }

    /**
     * 会话上下文段：主会话为 {@link #none()}；商品咨询带 productId、闲置咨询带 listingId。
     * 两类段互斥（send 入口已校验，同时携带 400）。
     */
    private record ConvContext(Long productId, Long listingId) {

        static ConvContext none() {
            return new ConvContext(null, null);
        }

        static ConvContext product(Long productId) {
            return new ConvContext(productId, null);
        }

        static ConvContext listing(Long listingId) {
            return new ConvContext(null, listingId);
        }

        /** 是否带上下文段（咨询会话豁免「非互关一条」的判定依据） */
        boolean present() {
            return productId != null || listingId != null;
        }

        String suffix() {
            if (productId != null) {
                return ":p" + productId;
            }
            if (listingId != null) {
                return ":l" + listingId;
            }
            return "";
        }
    }

    /** 从会话键解析上下文段（主会话返回 none）——userId 纯数字保证 ":p"/":l" 模式无歧义 */
    private ConvContext contextOfKey(String conversationKey) {
        if (conversationKey == null) {
            return ConvContext.none();
        }
        int idxP = conversationKey.lastIndexOf(":p");
        int idxL = conversationKey.lastIndexOf(":l");
        // 两段不会共存（拼装互斥），取命中的那一段
        if (idxP < 0 && idxL < 0) {
            return ConvContext.none();
        }
        try {
            if (idxP > idxL) {
                return ConvContext.product(Long.parseLong(conversationKey.substring(idxP + 2)));
            }
            return ConvContext.listing(Long.parseLong(conversationKey.substring(idxL + 2)));
        } catch (NumberFormatException e) {
            return ConvContext.none();
        }
    }

    private String iso(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC).toString();
    }
}
