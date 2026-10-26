package com.duoduo.social.support.dto;

import java.util.List;

/** 客服域对外视图（自营客服与第三方商家客服共用一套结构）。 */
public final class SupportDtos {

    private SupportDtos() {
    }

    /**
     * 会话摘要。
     *
     * <p>同时带买卖双方的展示字段：用户侧只用到 seller*，商家侧只用到 user*，
     * 但合成一个结构可以让工作台与用户页共用同一套渲染逻辑，少一份对齐成本。
     */
    public record SessionView(
            Long id,
            Long sellerId,
            String sellerName,
            String sellerAvatar,
            Long userId,
            String userName,
            String userAvatar,
            String lastMessage,
            String lastMessageAt,
            long unread,
            String status,
            /** C-04：当前处理人 userId；null=未认领。买家侧恒为 null（内部协作信息不外泄） */
            String assigneeId,
            /** C-04：当前处理人昵称；未认领 / 买家侧为 null */
            String assigneeName
    ) {
    }

    /** 商品上下文（只带渲染必需字段；价格用于卡片上的「¥xx 起」） */
    public record ProductCard(Long productId, String title, String image, Long price) {
    }

    public record MessageView(
            Long id,
            Long senderId,
            String senderRole,
            String content,
            String bizType,
            Long bizId,
            /** 媒体地址（IMAGE / VIDEO / EMOJI / VOICE） */
            String mediaUrl,
            String thumbUrl,
            Integer width,
            Integer height,
            Integer durationMs,
            /** 卡片快照 JSON（商品/订单） */
            String metaJson,
            String createdAt,
            boolean mine,
            boolean read,
            ProductCard card,
            /** 撤回时间（2026-10-24）；null=未撤回。撤回后 content 已是占位符、媒体已清空 */
            String recalledAt,
            /** 引用的消息 id；null=无引用 */
            Long replyToId,
            /** 服务端拼装的引用摘要（replyToId 非空时恒非 null，被引用消息已撤回时 excerpt=「原消息已撤回」） */
            QuotedView quoted
    ) {
    }

    /** 引用摘要（2026-10-24）：服务端拼装，不信任端侧传摘要；不递归嵌套 */
    public record QuotedView(Long id, String bizType, String excerpt) {
    }

    /** 会话详情（消息按时间正序） */
    public record ThreadView(
            Long sessionId,
            Long sellerId,
            String sellerName,
            String sellerAvatar,
            Long userId,
            String userName,
            String userAvatar,
            String status,
            /** 对端读到的最大消息 id（已读回执判据） */
            Long peerLastReadId,
            /** 我方已发出、对端尚未读到的条数；0 = 全部已读 */
            long myUnreadCount,
            List<MessageView> items,
            long total,
            int page,
            int pageSize
    ) {
    }

    /**
     * 用户发消息。{@code sellerId} 缺省 = 自营客服。
     *
     * <p>{@code type} 缺省 TEXT；IMAGE / VIDEO / EMOJI 必须带 {@code mediaUrl}
     * （由 {@code POST /api/uploads} 生成），PRODUCT 用 {@code productId} 定卡。
     */
    public record SendRequest(Long sellerId, String content, Long productId, String type,
                              String mediaUrl, String thumbUrl,
                              Integer width, Integer height, Integer durationMs,
                              /** type=ORDER 时必填：订单号（服务端回交易域校验归属并生成快照） */
                              String orderNo,
                              /** 引用的消息 id（2026-10-24）：必须同会话；服务端拼摘要 */
                              Long replyToId) {
    }

    /** 商家发消息（第七十九轮 B 批：与用户侧一致，支持图片/视频/表情） */
    public record SellerSendRequest(String content, String type,
                                    String mediaUrl, String thumbUrl,
                                    Integer width, Integer height, Integer durationMs,
                                    /** 引用的消息 id（2026-10-24）：必须同会话 */
                                    Long replyToId) {
    }

    /** 单向删除（2026-10-25）：批量隐藏本人视角的消息，对方仍可见 */
    public record DeleteRequest(java.util.List<Long> messageIds) {
    }

    // ---------------- C-04 · 客服工单 ----------------

    /** 转交候选人（本店在职成员；服务端下发，前端不自己拼名单） */
    public record TicketMemberView(String userId, String name, String avatar, String role) {
    }

    /** 一条处理记录（append-only） */
    public record TicketLogView(
            Long id,
            /** CLAIM / TRANSFER / RELEASE */
            String action,
            String actionLabel,
            String fromAssigneeId,
            String fromAssigneeName,
            String toAssigneeId,
            String toAssigneeName,
            String actorId,
            String actorName,
            String note,
            String createdAt) {
    }

    /**
     * 工单视图：当前处理人 + 可转交成员 + 处理记录。
     *
     * <p>一次返回三块，是为了让工作台点开「处理」就拿到全部信息；
     * 拆成三个接口会让转交弹窗在弱网下先出现空名单、再补一次请求。
     */
    public record TicketView(
            Long sessionId,
            String status,
            String assigneeId,
            String assigneeName,
            String assignedAt,
            java.util.List<TicketMemberView> members,
            java.util.List<TicketLogView> logs) {
    }

    /** 转交请求：toUserId 必须在本店在职成员名单里；note 可选（转交原因） */
    public record TicketTransferRequest(Long toUserId, String note) {
    }
}
