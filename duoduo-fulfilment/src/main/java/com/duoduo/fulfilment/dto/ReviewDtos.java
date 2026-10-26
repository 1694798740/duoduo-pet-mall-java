package com.duoduo.fulfilment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 履约域（评价与晒单）请求 / 响应契约。
 *
 * <p>设计口径：
 * <ul>
 *   <li>评分区间用注解声明（1-5），但**业务区间**仍由服务层再校一次 ——
 *       注解只拦 HTTP 入口，服务层还可能被内部调用；两处都写不算重复，算分工。</li>
 *   <li>请求里<b>没有 userId</b>：身份只来自登录态（网关校验后透传 {@code X-User-*}）。</li>
 *   <li>时间统一返回 ISO-8601 UTC 字符串，与其它域一致。</li>
 * </ul>
 */
public final class ReviewDtos {

    private ReviewDtos() {
    }

    // ---------------- 请求 ----------------

    /**
     * 提交评价：一次提交可包含订单里的多个商品。
     *
     * <p>为什么不是「一单一评」而是按商品逐件评：一个订单常含多件商品
     * （同卖家多件合并发货），强迫只写一条评价会迫使买家把对 A 的好评与对 B 的差评
     * 写进同一段文字，商品维度的评分随即失真。
     *
     * <p>允许只评其中一部分：买家可以今天评 A、明天补评 B，不强制一次评完。
     */
    public record ReviewCreateRequest(
            @NotBlank(message = "订单号不能为空")
            String orderNo,

            /** 是否匿名展示（只影响展示，真实 user_id 始终保留） */
            Boolean anonymous,

            @NotEmpty(message = "至少评价一件商品")
            @Valid
            List<ItemReview> items
    ) {
    }

    /** 单件商品的评价内容 */
    public record ItemReview(
            @Min(value = 1, message = "评分最低 1 星")
            @Max(value = 5, message = "评分最高 5 星")
            int rating,

            @Size(max = 1000, message = "评价内容过长")
            String content,

            /** 图片 URL 列表（最多 9 张，由服务层按配置校验） */
            @Size(max = 9, message = "最多上传 9 张图片")
            List<String> images,

            Long spuId,
            Long skuId
    ) {
    }

    /** 追评请求 */
    public record ReviewAppendRequest(
            @NotBlank(message = "追评内容不能为空")
            @Size(max = 1000, message = "追评内容过长")
            String content
    ) {
    }

    // ---------------- 响应 ----------------

    /**
     * 评价视图（对外）。
     *
     * <p>匿名评价在**读取时**脱敏：昵称替换为「匿名用户」、头像置空，
     * 但 {@code userId} 保留真实值以便前端「我评过」判断与后续风控 ——
     * 少见的取舍说明：匿名是**展示规则**，不是数据删除。
     */
    public record ReviewView(
            Long id,
            String orderNo,
            Long spuId,
            Long skuId,
            /** 商品快照标题（下单时点的商品名，商品改名不影响历史评价） */
            String productTitle,
            String productImage,
            int rating,
            String content,
            List<String> images,
            boolean anonymous,
            /** 展示昵称：匿名评价为脱敏值 */
            String nickname,
            String avatar,
            String appendContent,
            String appendAt,
            String createdAt,
            /** 追加评价是否还在允许窗口内（前端据此决定是否显示「追评」入口） */
            boolean appendable
    ) {
    }

    /**
     * 商品评分汇总。
     *
     * <p>{@code starCounts} 用长度为 5 的数组（下标 0 对应 1 星）而不是 Map：
     * 前端画五星分布条要按顺序取，Map 的键顺序在 JSON 里不保证，
     * 用数组把「顺序」固化进契约，省掉前端自己排序。
     */
    public record ReviewSummary(
            Long spuId,
            long total,
            /** 平均分，保留一位小数（用字符串避免浮点在前端被再截一次） */
            String avgRating,
            /** 好评率（4-5 星占比），形如 "92.5%" */
            String goodRate,
            /** 下标 0 = 1 星 … 下标 4 = 5 星 */
            List<Long> starCounts,
            long imageCount,
            long appendCount
    ) {
    }

    /** 「我在这批订单里已评价过的订单号」，供订单列表页一次判断 */
    public record ReviewedOrders(List<String> orderNos) {
    }

    /** 提交结果：成功落库的评价（前端据此提示「N 件商品评价成功」） */
    public record ReviewCreateResult(
            int created,
            List<ReviewView> reviews
    ) {
    }

    // ---------------- 运营后台：评价管理（H-02） ----------------

    /**
     * 评价审核请求。
     *
     * <p>只允许两个动作：{@code REJECT}（下架）与 {@code APPROVE}（恢复 / 通过）。
     * 下架必须填原因 —— 它是事后复盘与客诉处理的唯一凭据；恢复时原因清空。
     * 动作取值不放进注解枚举：未知值由服务层返回 400 并给出可选值，比 Jackson 反序列化报错更好读。
     */
    public record ReviewAuditRequest(
            @NotBlank(message = "审核动作不能为空")
            String action,

            @Size(max = 255, message = "审核说明不能超过 255 字")
            String reason
    ) {
    }

    /**
     * 运营后台的评价条目：比公开视图多带审核态、审核说明、操作人与时间，
     * 以及真实 userId（匿名只对买家/卖家展示脱敏；运营追责需要真实主体）。
     *
     * <p>昵称仍按匿名规则脱敏为「匿名用户」—— 运营看的是内容与处置，不需要把匿名用户
     * 的真实昵称展示在列表里；需要追责时用 userId 定位，在用户域按授权流程查询。
     */
    public record AdminReviewView(
            Long id,
            String orderNo,
            Long userId,
            Long sellerId,
            Long spuId,
            Long skuId,
            String productTitle,
            String productImage,
            int rating,
            String content,
            List<String> images,
            boolean anonymous,
            String nickname,
            String appendContent,
            String appendAt,
            String createdAt,
            String auditStatus,
            String auditReason,
            Long auditedBy,
            String auditedAt
    ) {
    }
}
