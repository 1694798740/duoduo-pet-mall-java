package com.duoduo.fulfilment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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
     */
    public record ShareCreateRequest(
            @NotNull(message = "缺少 spuId，无法确定分享的商品")
            Long spuId,

            @Size(max = 500, message = "分享内容不能超过 500 字")
            String content
    ) {
    }

    // ---------------- 响应 ----------------

    /**
     * 分享视图（信息流 / 详情页共用）。
     *
     * <p>{@code productTitle / productImage / minPrice} 是发布时点的商品快照，
     * 商品后续改名换图不影响历史分享的展示。互动四字段（第四十四轮）：
     * 计数是冗余列读数；{@code liked / favorited} 是**当前浏览者**的标记，
     * 未登录（匿名浏览）恒为 false。
     */
    public record ShareView(
            Long id,
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
            boolean liked,
            boolean favorited
    ) {
    }

    /** 点赞 / 收藏 toggle 结果：动作后的状态 + 计数（前端直接回填，无需再拉详情） */
    public record EngagementState(
            boolean active,
            long count
    ) {
    }
}
