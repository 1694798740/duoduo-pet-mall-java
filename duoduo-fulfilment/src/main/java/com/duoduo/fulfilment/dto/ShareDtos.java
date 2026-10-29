package com.duoduo.fulfilment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 分享圈（第四十三轮）请求 / 响应契约。
 *
 * <p>口径与 {@link ReviewDtos} 一致：
 * <ul>
 *   <li>请求里<b>没有 userId</b>：身份只来自登录态（网关校验后透传 {@code X-User-*}）；</li>
 *   <li>商品快照由服务端从商品域取事实，请求体里只有用户自己写的话 ——
 *       快照若来自前端，「9.9 元猫粮」的卡片就能贴在任意商品 id 上；</li>
 *   <li>时间统一返回 ISO-8601 UTC 字符串。</li>
 * </ul>
 */
public final class ShareDtos {

    private ShareDtos() {
    }

    // ---------------- 请求 ----------------

    /**
     * 发布分享：商品 + 用户自撰评价内容。
     *
     * <p>content 必填：分享圈是「带评价的商品分享」信息流，空内容的转发
     * 走微信原生转发（商品页 {@code onShareAppMessage}），不占分享圈版面。
     *
     * <p>topics（第五十六轮）：话题标签，**不带 # 号**，最多 2 个。
     *
     * <p><b>这里刻意不加 {@code @Size(max = 2)}</b>：注解会让「多带了一个话题」
     * 变成 400 整单失败，而正确行为是**截断到 2 个并照常发布** ——
     * 用户多打了一个词就发不出分享，是糟糕的体验。数量、去重、长度与字符白名单
     * 一律在服务层处理（见 {@code ShareTopicService.normalize}）。
     */
    public record ShareCreateRequest(
            @NotNull(message = "缺少 spuId，无法确定分享的商品")
            Long spuId,

            @Size(max = 500, message = "分享内容不能超过 500 字")
            String content,

            List<String> topics
    ) {
    }

    /**
     * 编辑自己的分享（第五十六轮）。
     *
     * <p><b>只能改文字与话题，不能改商品</b>：商品是快照，改商品等于把这条分享的内容
     * 换成了另一件商品的评价 —— 那应该是「删掉重发」。转发单同样不可编辑商品
     * （它的商品沿用的是原帖），这里一并挡住。
     *
     * <p>topics 传 null = 不改话题；传空列表 = 清空话题（与「不改」语义必须区分开，
     * 否则用户永远删不掉话题）。上限同样不在注解里（理由见 {@link ShareCreateRequest}）。
     */
    public record ShareEditRequest(
            @Size(max = 500, message = "分享内容不能超过 500 字")
            String content,

            List<String> topics
    ) {
    }

    /**
     * 发表评论 / 回复（第五十六轮）。
     *
     * <p>{@code parentCommentId} 为 null = 一级评论；非 null = 回复某条一级评论
     * （服务端会校验它确实是一级评论，不允许「回复一条回复」——二级结构一旦放开，
     * 折叠与 @ 链路就要重做）。
     */
    public record CommentCreateRequest(
            @Size(max = 300, message = "评论不能超过 300 字")
            String content,

            Long parentCommentId
    ) {
    }

    /** 举报（第五十六轮）：理由必填 —— 无理由的举报让运营无从判断，等于把成本转嫁给运营 */
    public record ReportRequest(
            @Size(max = 200, message = "举报理由不能超过 200 字")
            String reason
    ) {
    }

    /** 运营处理举报：ACCEPT（成立 → 内容下架）/ REJECT（不成立），说明必填 */
    public record ReportHandleRequest(
            @NotNull(message = "缺少处理结论")
            String action,

            @Size(max = 200, message = "处理说明不能超过 200 字")
            String note
    ) {
    }

    /**
     * 运营批量处理举报（2026-10-27 · 批量操作即已读）。
     *
     * <p>结论与说明对选中的举报共用一份；逐条结果见 {@code BatchDtos.Result}。
     * 已处理过的一条会逐条失败（409），不影响其它条。
     */
    public record ReportBatchHandleRequest(
            @jakarta.validation.constraints.NotEmpty(message = "ids 不能为空")
            java.util.List<Long> ids,
            @NotNull(message = "缺少处理结论")
            String action,
            @Size(max = 200, message = "处理说明不能超过 200 字")
            String note
    ) {
    }

    /**
     * 转发分享（第四十六轮）：只带转发人自己的附言。
     *
     * <p>请求体里<b>没有 spuId 也没有商品快照</b>：转发的对象是原帖，商品快照与原帖
     * 完全一致（服务端直接沿用），前端无从伪造 —— 否则「9.9 元猫粮」的卡片能被贴到
     * 任意一条转发上。附言可空（纯转发），非空则上限 500 字。
     */
    public record RepostRequest(
            @Size(max = 500, message = "转发附言不能超过 500 字")
            String content
    ) {
    }

    // ---------------- 响应 ----------------

    /** 分享类型：原创 / 转发（前端据此决定是否渲染「转发自 @原作者」引用块） */
    public static final String TYPE_ORIGINAL = "ORIGINAL";
    public static final String TYPE_REPOST = "REPOST";

    /** 信息流范围（第五十六轮）：推荐 = 全站，关注 = 只看我关注的人 */
    public static final String SCOPE_RECOMMEND = "RECOMMEND";
    public static final String SCOPE_FOLLOWING = "FOLLOWING";

    /** 举报对象类型（第五十六轮） */
    public static final String REPORT_TARGET_SHARE = "SHARE";
    public static final String REPORT_TARGET_COMMENT = "COMMENT";

    /** 举报处理状态（第五十六轮） */
    public static final String REPORT_PENDING = "PENDING";
    public static final String REPORT_ACCEPTED = "ACCEPTED";
    public static final String REPORT_REJECTED = "REJECTED";

    /**
     * 分享视图（信息流 / 详情页共用）。
     *
     * <p>{@code productTitle / productImage / minPrice} 是发布时点的商品快照，
     * 商品后续改名换图不影响历史分享的展示。互动四字段（第四十四轮）：
     * 计数是冗余列读数；{@code liked / favorited} 是**当前浏览者**的标记，
     * 未登录（匿名浏览）恒为 false。
     *
     * <p><b>转发四字段</b>（第四十六轮）：{@code shareType} 区分原创/转发；
     * 转发时 {@code parentId} 是原帖 ID，{@code parentNickname / parentContent}
     * 是原帖的作者与评价内容（引用展示）。{@code nickname / avatar / userId}
     * 始终是**本条分享的作者** —— 转发时即转发人本人。
     */
    public record ShareView(
            Long id,
            /** 本条分享的作者：原创 = 发帖人，转发 = 转发人（第四十六轮明确） */
            Long userId,
            Long spuId,
            String productTitle,
            String productImage,
            Long minPrice,
            String content,
            /** 展示昵称：用户服务不可用时降级为「用户 {id}」 */
            String nickname,
            String avatar,
            String createdAt,
            long likeCount,
            long favoriteCount,
            /** 评论数（第五十六轮；冗余列读数） */
            long commentCount,
            boolean liked,
            boolean favorited,
            /** ORIGINAL | REPOST（第四十六轮） */
            String shareType,
            /** 被转发的原帖 ID；原创为 null（第四十六轮） */
            Long parentId,
            /** 原帖作者昵称；原创为 null（第四十六轮） */
            String parentNickname,
            /**
             * 原帖评价内容（引用展示）；原创为 null。
             *
             * <p><b>第五十六轮</b>：原帖被作者删除时此字段刻意**置为 null** ——
             * 删除意图必须被尊重（用户选了「显示原内容已被作者删除」的口径），
             * 由 {@code parentDeleted} 让前端把引用块换成提示语。
             */
            String parentContent,
            /** 原帖是否已被作者删除（第五十六轮）；原创恒为 false */
            boolean parentDeleted,
            /** 是否已被编辑过（第五十六轮；前端显示「已编辑」） */
            boolean edited,
            /** 话题名列表（不含 # 号；第五十六轮，最多 2 个） */
            List<String> topics,
            /**
             * 是不是「我发的」（第五十六轮）。
             *
             * <p>服务端算而不是前端比：前端要自己比就得先知道自己的雪花 id，
             * 而这个 id 在 JS 里是 19 位数字（精度敏感）——能不算的地方就不算。
             * 匿名浏览恒为 false。
             */
            boolean mine
    ) {
    }

    /**
     * 评论视图（第五十六轮）。
     *
     * <p>一级评论与回复共用一个结构：回复多出 {@code replyToNickname}（「回复 @某人」）。
     * {@code replyCount} 只有一级评论有意义（回复恒为 0）。
     */
    public record CommentView(
            Long id,
            Long shareId,
            Long userId,
            String nickname,
            String avatar,
            String content,
            String createdAt,
            long likeCount,
            boolean liked,
            /** 是否本人所发（服务端判定，理由同 ShareView.mine） */
            boolean mine,
            /** 被回复者的昵称；一级评论为 null */
            String replyToNickname,
            /** 该评论下的回复条数；回复本身恒为 0 */
            long replyCount
    ) {
    }

    /** 话题视图（第五十六轮）：话题名 + 被使用次数（聚合页头部与热门榜共用） */
    public record TopicView(
            String name,
            long shareCount
    ) {
    }

    /** 举报视图（第五十六轮）：用户侧只回执，运营侧带被举报内容的摘要以便直接判断 */
    public record ReportView(
            Long id,
            String targetType,
            Long targetId,
            Long reporterId,
            String reason,
            String status,
            String createdAt,
            Long handledBy,
            String handledAt,
            String handleNote,
            /**
             * 被举报内容的摘要（运营侧用）。
             *
             * <p>刻意带上：举报列表若不显示内容，运营得逐条点开才知道在举报什么 ——
             * 处理效率低到没人愿意处理，举报入口就成了摆设。内容已删除时给
             * 「（内容已被作者删除）」而不是空串。
             */
            String targetExcerpt,
            /** 被举报内容的作者 id（运营侧判断「是不是同一个人在反复被举报」） */
            Long targetAuthorId,
            /** 被举报内容所属分享 id（举报评论时用于跳转核对） */
            Long targetShareId,
            /** 当前运营是否已看过（列表橙色未读印记；2026-10-27） */
            boolean read
    ) {
    }

    /** 点赞 / 收藏 toggle 结果：动作后的状态 + 计数（前端直接回填，无需再拉详情） */
    public record EngagementState(
            boolean active,
            long count
    ) {
    }
}
