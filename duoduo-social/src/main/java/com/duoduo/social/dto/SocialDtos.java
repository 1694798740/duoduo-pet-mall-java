package com.duoduo.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 社交域（第四十七轮）请求 / 响应契约。
 *
 * <p>口径与全站一致：请求里<b>没有 userId</b>（身份只来自登录态）；ID 一律字符串
 * （{@code LongToStringJacksonModule}，T-39）；时间统一 ISO-8601 UTC 字符串。
 */
public final class SocialDtos {

    private SocialDtos() {
    }

    // ---------------- 常量 ----------------

    /** 消息业务类型：普通文本 / 商品卡 / 分享卡 / 个人卡 / 商品咨询 / 闲置咨询（第五十三轮） */
    public static final String BIZ_TEXT = "TEXT";
    public static final String BIZ_PRODUCT = "PRODUCT";
    public static final String BIZ_SHARE = "SHARE";
    public static final String BIZ_PROFILE_CARD = "PROFILE_CARD";
    public static final String BIZ_ASK = "ASK";
    /** 闲置咨询（第五十三轮）：C2C 详情页「问一问」，收件人=闲置归属卖家（服务端定人） */
    public static final String BIZ_LISTING = "LISTING";

    // ---------------- 常量：通知分类（与 t_social_notice.category 表注释一致，第五十五轮） ----------------

    /** 系统消息（运营向单人发的系统提示） */
    public static final String NOTICE_SYSTEM = "SYSTEM";
    /** 全站公告（收件人写 0：一行多读者，已读靠集合表） */
    public static final String NOTICE_ANNOUNCEMENT = "ANNOUNCEMENT";
    /** 好友消息（私信 senderRole = USER 时归属此类） */
    public static final String NOTICE_FRIEND = "FRIEND";
    /** 商家消息（私信 senderRole = SELLER / OPERATOR / ADMIN 时归属此类） */
    public static final String NOTICE_MERCHANT = "MERCHANT";
    /** 订单交易通知（交易域下单/付款/退款、履约域发货/物流/售后推送） */
    public static final String NOTICE_ORDER = "ORDER";

    /** 通知业务类型：私信到达（同会话未读合并为一条，避免连发多条把通知中心刷屏） */
    public static final String NOTICE_BIZ_MESSAGE = "MESSAGE";
    /** 客服消息（第七十九轮）：通知列表可见，但不计入通知未读（未读口径在 support 域，避免重复计数） */
    public static final String NOTICE_BIZ_SUPPORT = "SUPPORT";

    // ---------------- 请求 ----------------

    /**
     * 发消息。
     *
     * <p>请求体里<b>没有会话键</b>（服务端由双方 id 推导），卡片类也**不带展示字段** ——
     * 摘要与卡片视图由服务端去事实源现取，公开接口贴不出伪造卡片。
     * {@code bizType=ASK}（商品咨询）时 {@code receiverId} 以商品域归属卖家为准。
     */
    public record SendMessageRequest(
            @NotNull(message = "缺少 receiverId，无法确定接收人")
            Long receiverId,

            /** 缺省 TEXT */
            String bizType,

            /** 卡片 / 咨询类必填：商品 SPU、分享单或被分享用户 */
            Long bizId,

            /**
             * 可选：商品咨询会话上下文（第四十九轮）。
             *
             * <p>TEXT 消息带 productId 时落入该商品的咨询会话（会话键带商品段），
             * 与其他商品的咨询、与互关主会话互不串线；不带则落 {@code lo:hi} 主会话。
             * ASK 消息忽略此字段（会话商品恒取 bizId）。服务端校验商品存在。
             */
            Long productId,

            /**
             * 可选：闲置咨询会话上下文（第五十三轮）。
             *
             * <p>语义与 {@code productId} 完全平行：TEXT 消息带 listingId 时落入该闲置的
             * 咨询会话（会话键带闲置段 {@code :l{listingId}}）。LISTING 消息忽略此字段
             * （会话闲置恒取 bizId）。与 productId 互斥 —— 同时携带属于前端拼装错误，400。
             * 服务端校验闲置存在。
             */
            Long listingId,

            @Size(max = 1000, message = "消息内容不能超过 1000 字")
            String content,

            /** 媒体地址（bizType=IMAGE/VIDEO/EMOJI 时必填，来自 POST /api/uploads） */
            String mediaUrl,
            String thumbUrl,
            Integer width,
            Integer height,
            Integer durationMs,

            /** 引用的消息 id（2026-10-24）：必须同会话；服务端拼摘要，不信任端侧 */
            Long replyToId
    ) {
    }

    /** 标记通知已读（第二批启用） */
    public record NoticeReadRequest(
            String category,
            java.util.List<Long> ids,
            boolean all
    ) {
    }

    /**
     * 内部推送：单条通知（第五十五轮）。
     *
     * <p>{@code userId = 0} 表示全站公告（公共行，一行多读者，已读记集合表）。
     *
     * <p>这是<b>服务间契约</b>而不是用户输入 —— 字段在这里严格校验（分类白名单、
     * 长度上限、{@code jumpPage} 必须命中小程序真实页面），不合法直接 400。
     * 与「对用户宽容、给友好提示」相反：调用方是我们自己的服务，拼错了应当立刻炸出来，
     * 而不是宽容地写进库，让用户点到一个 404 页面。
     */
    public record NoticePushItem(
            @NotNull(message = "缺少收件人 userId（全站公告传 0）")
            Long userId,

            @NotBlank(message = "缺少通知分类")
            String category,

            @NotBlank(message = "缺少通知标题")
            String title,

            @NotBlank(message = "缺少通知正文")
            String content,

            /** 业务类型（ORDER_PLACED / ORDER_PAID / MESSAGE / …），可空 */
            String bizType,

            /** 业务单号（订单号 / 售后单号 / 会话键；雪花 ID 按字符串，T-39），可空 */
            String bizId,

            /** 点击落地页（如 {@code /pages/order-detail/order-detail?id=123}），可空；非空必须真实存在 */
            String jumpPage
    ) {
    }

    /** 内部推送：批量通知（同一收件人可一次多条；单批条数有上限） */
    public record NoticePushRequest(
            @NotNull(message = "缺少通知列表")
            java.util.List<NoticePushItem> items
    ) {
    }

    /** 内部推送：发全站公告（运营用；收件人由服务端固定为 0，不给调用方瞎填） */
    public record AnnounceRequest(
            @NotBlank(message = "缺少公告标题")
            String title,

            @NotBlank(message = "缺少公告正文")
            String content,

            String bizType,
            String bizId,
            String jumpPage
    ) {
    }

    // ---------------- 响应 ----------------

    /** 我与某人的关注关系：following=我关注了 TA，followedBy=TA 关注了我 */
    public record FollowState(
            boolean following,
            boolean followedBy,
            boolean mutual
    ) {
    }

    /** 某人的关注计数（公开页用） */
    public record FollowCounts(
            // 计数不是标识：显式按数字输出（record 组件不走字段名白名单，见 NumericLongSerializer）
            @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.duoduo.common.web.LongToStringJacksonModule.NumericLongSerializer.class)
            long followers,
            @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.duoduo.common.web.LongToStringJacksonModule.NumericLongSerializer.class)
            long following
    ) {
    }

    /**
     * 他人主页聚合视图：资料 + 计数 + 与当前用户的关系，一次请求拿齐 ——
     * 页面不必「counts + state + 昵称」三次拼装；关系字段在查看自己时恒为 false。
     * 关系字段用 viewer* 前缀，避免与 following 计数撞名（record 组件名即访问器名）。
     */
    public record ProfileView(
            Long userId,
            String nickname,
            String avatar,
            @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.duoduo.common.web.LongToStringJacksonModule.NumericLongSerializer.class)
            long followers,
            @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.duoduo.common.web.LongToStringJacksonModule.NumericLongSerializer.class)
            long following,
            boolean viewerFollowing,
            boolean viewerFollowedBy,
            boolean mutual
    ) {
    }

    /** 关注 / 粉丝列表项（昵称头像批量补全；mutual 供「互相关注」角标） */
    public record FollowView(
            Long userId,
            String nickname,
            String avatar,
            boolean mutual,
            String followedAt
    ) {
    }

    /**
     * 卡片视图（卡片类消息才有）：由服务端去事实源现取的**结构化**展示字段。
     * 前端据此渲染可点击卡片并决定跳转（商品详情 / 分享落地页 / 他人主页）。
     */
    public record CardView(
            String type,
            Long id,
            String title,
            String image,
            // 金额不是标识：显式按数字输出（前端 price/100 需要数字）
            @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.duoduo.common.web.LongToStringJacksonModule.NumericLongSerializer.class)
            Long minPrice,
            String nickname,
            String avatar
    ) {
    }

    /**
     * 消息视图。{@code mine} 是相对**当前查看者**的方向标记；卡片类消息
     * {@code card} 非空（TEXT / ASK 为 null，ASK 前端展示为咨询语境文本）。
     */
    public record MessageView(
            Long id,
            Long senderId,
            Long receiverId,
            String senderRole,
            String bizType,
            Long bizId,
            String content,
            String mediaUrl,
            String thumbUrl,
            Integer width,
            Integer height,
            Integer durationMs,
            String createdAt,
            boolean mine,
            boolean read,
            CardView card,
            /** 撤回时间（2026-10-24）；null=未撤回。撤回后 content 已是占位符、媒体已清空 */
            String recalledAt,
            /** 引用的消息 id；null=无引用 */
            Long replyToId,
            /** 服务端拼装的引用摘要（replyToId 非空时恒非 null，被引用消息已撤回时 excerpt=「原消息已撤回」） */
            QuotedView quoted
    ) {
    }

    /** 引用摘要（2026-10-24）：服务端拼装；不递归嵌套 */
    public record QuotedView(Long id, String bizType, String excerpt) {
    }

    /**
     * 会话列表项。{@code canSend} 是服务端算好的「我还能不能发」——
     * 互关 / 咨询会话恒 true；非互关且我已发过一条则 false（前端置灰 + 文案）。
     * 判定逻辑集中在服务端，前端只消费结果，避免两边规则漂移。
     */
    public record ConversationView(
            Long peerId,
            String nickname,
            String avatar,
            String lastContent,
            String lastBizType,
            Long lastBizId,
            String lastAt,
            @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.duoduo.common.web.LongToStringJacksonModule.NumericLongSerializer.class)
            long unread,
            boolean mutual,
            boolean canSend,
            /** 会话是否为商品咨询（豁免一条限制的可见证据） */
            boolean consulting,
            /** 商品咨询会话的上下文商品（主会话为 null，第四十九轮） */
            Long productId,
            String productTitle,
            String productImage,
            /** 闲置咨询会话的上下文闲置（主会话为 null，第五十三轮） */
            Long listingId,
            String listingTitle,
            String listingImage
    ) {
    }

    /** 会话内消息分页 */
    public record MessagePage(
            java.util.List<MessageView> items,
            @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.duoduo.common.web.LongToStringJacksonModule.NumericLongSerializer.class)
            long total,
            int page,
            int pageSize,
            /** 对方资料（会话头部用） */
            Long peerId,
            String peerNickname,
            String peerAvatar,
            boolean mutual,
            boolean canSend,
            boolean consulting,
            /** 商品咨询会话的上下文商品（主会话为 null，第四十九轮） */
            Long productId,
            /** 闲置咨询会话的上下文闲置（主会话为 null，第五十三轮） */
            Long listingId,
            /**
             * 本次会话的会话键（2026-10-23）。
             *
             * <p>实时通道的「正在输入」需要告诉服务端"哪段会话"：私信键由服务端拼装
             * （lo:hi + 上下文段），前端无从得知，所以随详情一起下发，客户端原样回传。
             * 它不含新信息 —— 由登录态 + 服务端定人保证必然包含自己。
             */
            String conversationId
    ) {
    }

    /**
     * 通知中心列表项（第五十轮）。定向通知与全站公告在接口层统一为同一视图：
     * {@code read} 对定向通知取行上 {@code readAt}，对公告取已读集合表。
     */
    public record NoticeView(
            Long id,
            String category,
            String title,
            String content,
            String bizType,
            String bizId,
            String jumpPage,
            /**
             * 网页端落地页（2026-10-18）：与 {@code jumpPage} 并列下发。
             *
             * <p>为什么需要两个：{@code jumpPage} 是**小程序路由**（`/pages/order-detail/order-detail?orderNo=…`），
             * 网页 SPA 没有这条路由，直接 navigate 就是「页面不存在」。
             * 而"通知该跳哪"是业务事实，只该在服务端定义一次 —— 前端各写一套映射迟早对不上。
             * 为空表示该通知在网页端没有对应页面（例如纯公告），此时前端**不跳转**，而不是跳到 404。
             */
            String webPath,
            boolean read,
            String createdAt
    ) {
    }

    /** 通知列表分页（定向 + 公告两源合并后切片） */
    public record NoticePage(
            java.util.List<NoticeView> items,
            long total,
            int page,
            int pageSize
    ) {
    }

    /**
     * 通知未读数（定向未读 + 我没读过的公告数）。
     *
     * <p>{@code count} 显式标注按数字输出：它是**计数**不是标识，前端要参与比较与
     * 显示（`count > 0`）；record 组件绕过 {@code BeanPropertyWriter} 白名单机制
     * （见 {@code LongToStringJacksonModule} 类注释），不标注会被当标识转成字符串
     * {@code "3"}，与同一接口族的 {@code PageResult.total}（数字）类型不一致。
     */
    public record UnreadCount(
            @com.fasterxml.jackson.databind.annotation.JsonSerialize(
                    using = com.duoduo.common.web.LongToStringJacksonModule.NumericLongSerializer.class)
            long count
    ) {
    }

    /** 内部推送结果：本次落库条数（同会话未读合并时，覆盖旧行也计 1） */
    public record NoticePushResult(
            @com.fasterxml.jackson.databind.annotation.JsonSerialize(
                    using = com.duoduo.common.web.LongToStringJacksonModule.NumericLongSerializer.class)
            long accepted
    ) {
    }

    /**
     * 未读合并口径（tabBar 角标用，第五十五轮）：通知 + 私信一次算清。
     *
     * <p>为什么合并口径必须落在服务端：角标数字由「通知未读」与「私信未读」两个来源相加。
     * 若让前端各算各的（前端算私信要拉一页会话自己求和，窗口外的会话就漏了），
     * 两处口径稍有不一致就会出现「角标显示 5、点进去只有 3」—— 用户不会怀疑口径，
     * 只会认为「未读数是错的」。服务端算一次，前端只负责显示。
     *
     * <p>{@code notice} / {@code message} 一并返回：前端做页内提示（如「消息 3 条」）时
     * 不必再发第二次请求，也保证与 {@code total} 同源同刻。
     */
    public record UnreadSummary(
            @com.fasterxml.jackson.databind.annotation.JsonSerialize(
                    using = com.duoduo.common.web.LongToStringJacksonModule.NumericLongSerializer.class)
            long notice,
            @com.fasterxml.jackson.databind.annotation.JsonSerialize(
                    using = com.duoduo.common.web.LongToStringJacksonModule.NumericLongSerializer.class)
            long message,
            @com.fasterxml.jackson.databind.annotation.JsonSerialize(
                    using = com.duoduo.common.web.LongToStringJacksonModule.NumericLongSerializer.class)
            long support,
            @com.fasterxml.jackson.databind.annotation.JsonSerialize(
                    using = com.duoduo.common.web.LongToStringJacksonModule.NumericLongSerializer.class)
            long total
    ) {
    }
}
