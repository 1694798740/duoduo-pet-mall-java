package com.duoduo.product.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.product.dto.ProductDtos.ListingCard;
import com.duoduo.product.dto.ProductDtos.ListingDetail;
import com.duoduo.product.dto.ProductDtos.ListingMineView;
import com.duoduo.product.dto.ProductDtos.ListingPublishRequest;
import com.duoduo.product.dto.ProductDtos.ListingQuery;
import com.duoduo.product.dto.ProductDtos.ListingStatusRequest;
import com.duoduo.product.service.ListingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C2C 闲置接口（读链路公开 + 写链路需登录）。
 *
 * <p><b>路径划分是安全设计，不是命名偏好。</b>网关白名单按路径匹配、<b>不区分 HTTP 方法</b>，
 * 因此「同一个路径上既有免登读、又有需登录写」是不可能的组合 ——
 * 那样写端点会被连带放行，变成匿名 UGC 直写入库。
 * 这里沿用分享圈（{@code /api/shares/feed} + {@code /api/shares/*}）已验证的两段式方案：
 *
 * <table border="1">
 *   <caption>路径 / 方法 / 信任级别</caption>
 *   <tr><th>路径</th><th>方法</th><th>说明</th></tr>
 *   <tr><td>{@code /api/listings/search}</td><td>GET</td>
 *       <td><b>免登</b>。列表浏览与商品浏览同性质，强制登录等于掐断 C2C 的引流入口。</td></tr>
 *   <tr><td>{@code /api/listings/{id}}</td><td>GET</td>
 *       <td><b>免登</b>。详情；落在白名单的 {@code /api/listings/*} 里。
 *           未过审的闲置此处返回 404（不是 403），避免用状态码探测存在性。</td></tr>
 *   <tr><td>{@code /api/listings}</td><td>POST</td>
 *       <td><b>强制登录</b>。发布。单段路径<b>刻意不进白名单</b> ——
 *           这正是把列表读端点挪到 {@code /search} 的原因：腾出单段路径给写端点。</td></tr>
 *   <tr><td>{@code /api/listings/mine/list}</td><td>GET</td>
 *       <td><b>强制登录</b>。我的发布。用<b>三段</b>路径躲开 {@code /api/listings/*} ——
 *           AntPathMatcher 的 {@code *} 不跨 {@code /}，两段的 {@code /mine} 会被详情白名单漏放行。</td></tr>
 *   <tr><td>{@code /api/listings/{id}/status}</td><td>POST</td>
 *       <td><b>强制登录</b>。上下架。同样三段，{@code *} 不跨 {@code /} 故落白名单外。</td></tr>
 * </table>
 *
 * <p>运营审核仍在 {@code /api/admin/listings/**}（见 {@link AdminListingController}）。
 */
@RestController
@RequestMapping("/api/listings")
public class ListingController {

    private final ListingService listingService;

    public ListingController(ListingService listingService) {
        this.listingService = listingService;
    }

    // ---------------- 读：公开 ----------------

    /**
     * 闲置列表（免登）。
     *
     * <p>路径从裸 {@code /api/listings} 挪到 {@code /search}：单段路径要让给 {@code POST /api/listings}
     * （发布），而白名单不区分方法，两者不可能共存于同一路径。
     * 与分享圈的 {@code /api/shares/feed} 是同一个解法。
     */
    @GetMapping("/search")
    public ApiResponse<PageResult<ListingCard>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String species,
            @RequestParam(required = false) String conditionLevel,
            @RequestParam(required = false) Long minPrice,
            @RequestParam(required = false) Long maxPrice,
            @RequestParam(defaultValue = "default") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {

        ListingQuery query = new ListingQuery(keyword, city, species, conditionLevel,
                minPrice, maxPrice, sort, page, pageSize);
        return ApiResponse.ok(listingService.search(query));
    }

    /** 闲置详情（免登）；未过审的一律 404 */
    @GetMapping("/{id}")
    public ApiResponse<ListingDetail> detail(@PathVariable Long id) {
        return ApiResponse.ok(listingService.detail(id));
    }

    // ---------------- 写：需登录（网关白名单之外） ----------------

    /**
     * 发布闲置（需登录 + 需实名）。
     *
     * <p>请求体见 {@link ListingPublishRequest}：不含 sellerId / status / auditStatus，
     * 三者一律由服务端决定 —— 归属与审核态不能交给客户端自证。
     */
    @PostMapping
    public ApiResponse<ListingMineView> publish(@RequestBody ListingPublishRequest request) {
        return ApiResponse.ok(listingService.publish(request));
    }

    /**
     * 我的发布（需登录）。
     *
     * <p>路径刻意写成三段 {@code /mine/list} 而不是两段 {@code /mine}：
     * 白名单里的 {@code /api/listings/*} 只匹配一段通配，两段路径会被误放行成匿名可读。
     */
    @GetMapping("/mine/list")
    public ApiResponse<PageResult<ListingMineView>> mine(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(listingService.listMine(status, page, pageSize));
    }

    /** 上下架（需登录 + 需归属）；请求体 {@code {"action": "ON_SALE" | "OFF_SHELF"}} */
    @PostMapping("/{id}/status")
    public ApiResponse<ListingMineView> changeStatus(@PathVariable Long id,
                                                     @RequestBody ListingStatusRequest request) {
        return ApiResponse.ok(listingService.changeStatus(id, request == null ? null : request.action()));
    }
}
