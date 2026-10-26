package com.duoduo.fulfilment.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.audit.AuditAction;
import com.duoduo.common.audit.Audited;
import com.duoduo.common.context.UserContext;
import com.duoduo.fulfilment.dto.ReviewDtos.ReviewAppendRequest;
import com.duoduo.fulfilment.dto.ReviewDtos.ReviewCreateRequest;
import com.duoduo.fulfilment.dto.ReviewDtos.ReviewCreateResult;
import com.duoduo.fulfilment.dto.ReviewDtos.ReviewReplyRequest;
import com.duoduo.fulfilment.dto.ReviewDtos.ReviewSummary;
import com.duoduo.fulfilment.dto.ReviewDtos.ReviewView;
import com.duoduo.fulfilment.dto.ReviewDtos.ReviewedOrders;
import com.duoduo.fulfilment.dto.ReviewDtos.SellerReviewView;
import com.duoduo.fulfilment.service.ReviewService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * 评价接口。
 *
 * <p>公开读 / 登录写的分界线：
 * <ul>
 *   <li>{@code /product/**} —— <b>公开</b>。评价是购物决策信息，未登录访客也该看到
 *       （否则第一次来的人无法判断口碑）。网关白名单只放行这一段；</li>
 *   <li>其余 —— <b>需登录</b>，且写操作的归属校验在服务层完成，
 *       请求体里没有 userId 可供伪造。</li>
 * </ul>
 *
 * <p>控制器只做「参数绑定 + 调服务 + 包响应」。
 */
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    // ---------------- 公开 ----------------

    /**
     * 商品评价分页（公开）。
     *
     * @param rating   按星级过滤（1-5），不传为全部
     * @param hasImage true 只看带图评价（「有图有真相」是最高频的筛选诉求）
     */
    @GetMapping("/product/{spuId}")
    public ApiResponse<PageResult<ReviewView>> listByProduct(
            @PathVariable Long spuId,
            @RequestParam(required = false) Integer rating,
            @RequestParam(required = false) Boolean hasImage,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(reviewService.listByProduct(spuId, rating, hasImage, page, pageSize));
    }

    /** 商品评分汇总（公开）：平均分 / 星级分布 / 好评率 / 带图数 / 追评数 */
    @GetMapping("/product/{spuId}/summary")
    public ApiResponse<ReviewSummary> summary(@PathVariable Long spuId) {
        return ApiResponse.ok(reviewService.summary(spuId));
    }

    // ---------------- 需登录 ----------------

    /** 提交评价（一次可提交订单里多件商品；只有本人已完成的订单可评） */
    @PostMapping
    public ApiResponse<ReviewCreateResult> create(@Valid @RequestBody ReviewCreateRequest request) {
        return ApiResponse.ok(reviewService.create(UserContext.requireUserId(), request));
    }

    /** 追评（每条评价一次） */
    @PostMapping("/{id}/append")
    public ApiResponse<ReviewView> append(@PathVariable Long id,
                                          @Valid @RequestBody ReviewAppendRequest request) {
        return ApiResponse.ok(reviewService.append(UserContext.requireUserId(), id, request.content()));
    }

    /** 我的评价（分页） */
    @GetMapping("/mine")
    public ApiResponse<PageResult<ReviewView>> mine(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(reviewService.listMine(UserContext.requireUserId(), page, pageSize));
    }

    /** 某订单下我的评价（详情页展示「你已评价」与补评入口） */
    @GetMapping("/order/{orderNo}")
    public ApiResponse<List<ReviewView>> myReviewsOfOrder(@PathVariable String orderNo) {
        return ApiResponse.ok(reviewService.myReviewsOfOrder(UserContext.requireUserId(), orderNo));
    }

    /**
     * 我在这批订单里已评价过的订单号。
     *
     * <p>订单列表页一次判断，避免「一页 20 单 → 20 次请求」。
     * 参数用逗号分隔（而非重复 query），与项目里其它批量接口保持同一种编码约定。
     */
    @GetMapping("/reviewed")
    public ApiResponse<ReviewedOrders> reviewed(@RequestParam("orderNos") String orderNos) {
        List<String> list = Arrays.stream(orderNos.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        return ApiResponse.ok(new ReviewedOrders(
                reviewService.reviewedOrderNos(UserContext.requireUserId(), list)));
    }

    // ---------------- 商家端（A-16） ----------------

    /**
     * 我店铺的评价（商家端，需登录 + 卖家角色）。
     *
     * <p>可见范围由登录态推导，**没有 sellerId 参数**；只返回审核通过的评价。
     * 匿名评价的昵称由服务端脱敏，且不返回 userId / avatar —— 商家无法借列表反查买家身份。
     *
     * @param replied true 只看已回复 / false 只看待回复 / 不传 全部
     * @param rating  1-5；不传 = 全部星级
     * @param keyword 评价正文 / 追评 / 商品标题 / 订单号（包含匹配）
     */
    @GetMapping("/seller")
    public ApiResponse<PageResult<SellerReviewView>> seller(
            @RequestParam(required = false) Boolean replied,
            @RequestParam(required = false) Integer rating,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(reviewService.listForSeller(
                UserContext.requireUserId(), replied, rating, keyword, page, pageSize));
    }

    /**
     * 商家回复评价（一条评价一次，回复后不可修改）。
     *
     * <p>按 E18 纪律留痕：这是公开内容的写操作，审计要能回答「哪家店、什么时候、
     * 回复了哪条评价」。归属校验在服务层（越权 403 / 状态不允许 422 / 重复回复 409）。
     */
    @PostMapping("/{id:\\d+}/reply")
    @Audited(action = AuditAction.REVIEW_REPLY)
    public ApiResponse<SellerReviewView> reply(@PathVariable Long id,
                                               @Valid @RequestBody ReviewReplyRequest request) {
        return ApiResponse.ok(reviewService.reply(UserContext.requireUserId(), id, request.content()));
    }
}
