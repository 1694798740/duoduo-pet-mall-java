package com.duoduo.social.dto;

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

    /** 消息业务类型：普通文本 / 商品卡 / 分享卡 / 个人卡 / 商品咨询 */
    public static final String BIZ_TEXT = "TEXT";
    public static final String BIZ_PRODUCT = "PRODUCT";
    public static final String BIZ_SHARE = "SHARE";
    public static final String BIZ_PROFILE_CARD = "PROFILE_CARD";
    public static final String BIZ_ASK = "ASK";

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

            @Size(max = 1000, message = "消息内容不能超过 1000 字")
            String content
    ) {
    }

    /** 标记通知已读（第二批启用） */
    public record NoticeReadRequest(
            String category,
            java.util.List<Long> ids,
            boolean all
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
            String createdAt,
            boolean mine,
            boolean read,
            CardView card
    ) {
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
            String productImage
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
            Long productId
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
}
