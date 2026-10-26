package com.duoduo.product.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.product.dto.ProductDtos.SellerProductBatchResultView;
import com.duoduo.product.dto.ProductDtos.SellerProductCreateRequest;
import com.duoduo.product.dto.ProductDtos.SellerProductDetailView;
import com.duoduo.product.dto.ProductDtos.SellerProductUpdateRequest;
import com.duoduo.product.dto.ProductDtos.SellerProductView;
import com.duoduo.product.dto.ProductDtos.SellerSkuPriceStockRequest;
import com.duoduo.product.dto.ProductDtos.SellerSkuUsageView;
import com.duoduo.product.dto.ProductDtos.SellerSkuStructureRequest;
import com.duoduo.product.dto.ProductDtos.SellerSkuStructureResultView;
import com.duoduo.product.service.SellerProductService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商家端 · 商品管理接口（{@code /api/seller/products/**}，M9 商品侧 + E14 上架审核）。
 *
 * <p>与交易侧 {@code /api/seller/**} 同一套准入模型：卖家 / 店员 / 运营 / 管理员；
 * 可见范围由登录态推导，接口不接受普通卖家指定的 sellerId。
 *
 * <p>网关与过滤器注意（踩过一次的坑）：
 * <ul>
 *   <li>网关必须把 {@code /api/seller/products/**} 路由到 duoduo-product，且<b>不加白名单</b> ——
 *       商品服务要靠 {@code X-User-*} 头识别「这是哪个卖家」；</li>
 *   <li>本服务的 {@code InternalTokenFilter} 只保护 {@code /api/products/internal/**} 与
 *       库存写路径，不保护本控制器 —— 内部凭据只能证明「你是自己人」，证明不了「这商品是你的」，
 *       归属判断必须走登录态。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/seller/products")
public class SellerProductController {

    private final SellerProductService sellerProductService;

    public SellerProductController(SellerProductService sellerProductService) {
        this.sellerProductService = sellerProductService;
    }

    /**
     * 我的商品列表（分页 + 标题关键词 + 审核状态）；
     * 运营 / 管理员可显式按 sellerId 筛选（不传＝全部），其余账号一律强制自己的店铺。
     */
    @GetMapping
    public ApiResponse<PageResult<SellerProductView>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String auditStatus,
            @RequestParam(required = false) Long sellerId,
            /** true = 回收站（A-09）；缺省 false = 正常商品 */
            @RequestParam(defaultValue = "false") boolean deleted,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(sellerProductService.list(
                keyword, auditStatus, sellerId, deleted, page, pageSize));
    }

    /**
     * 商品详情（商家视角，编辑回填用）。
     *
     * <p>路径用 {@code \d+} 限定：不加限定的话，任何一段非数字路径都会落到这里，
     * 然后倒在为它转 Long 上 → {@code NumberFormatException} → 500，
     * 而正确答案是 404（没有这个路径）。500 会把「拼错 URL」记成服务器故障。
     * 与公开详情 {@code ProductController} 同一处理。
     */
    @GetMapping("/{spuId:\\d+}")
    public ApiResponse<SellerProductDetailView> detail(@PathVariable Long spuId) {
        return ApiResponse.ok(sellerProductService.ownerDetail(spuId));
    }

    /**
     * 提交商品（E14）。
     *
     * <p>归属、供货类型、审核态全部由服务端决定（详见服务层注释）——
     * 请求体里给的 {@code sellerId} / {@code auditStatus} 一律不接受。
     */
    @PostMapping
    public ApiResponse<SellerProductView> create(@RequestBody SellerProductCreateRequest request) {
        return ApiResponse.ok(sellerProductService.create(request));
    }

    /** 上下架请求体：{@code {"action": "ON_SALE" | "OFF_SHELF"}} */
    public record StatusRequest(String action) {
    }

    @PostMapping("/{spuId}/status")
    public ApiResponse<SellerProductView> changeStatus(@PathVariable Long spuId,
                                                       @RequestBody StatusRequest request) {
        return ApiResponse.ok(sellerProductService.changeStatus(spuId, request == null ? null : request.action()));
    }

    /** 批量上下架请求体：{@code {"ids": ["<spuId>"], "action": "ON_SALE" | "OFF_SHELF"}}；ids 去重、上限 100 */
    public record BatchStatusRequest(List<Long> ids, String action) {
    }

    /**
     * 批量上下架（A-13）：逐条结果，部分失败不回滚。
     *
     * <p>HTTP 200 只表示「批量请求已被处理」，每条的成败看 {@code items[].success}。
     * 用整体 4xx 表达「其中一件失败」会让前端拿不到逐条原因，也会诱使调用方重试整批 ——
     * 已经成功的条目会被重复执行。只有请求本身不合法（未登录 / ids 空 / 超上限 / action 非法）才走 4xx。
     */
    @PostMapping("/batch-status")
    public ApiResponse<SellerProductBatchResultView> batchStatus(@RequestBody BatchStatusRequest request) {
        return ApiResponse.ok(sellerProductService.changeStatusBatch(
                request == null ? null : request.ids(),
                request == null ? null : request.action()));
    }

    /** 重新提审（E14）：被驳回且无需改内容的商品回到审核队列（申请复核） */
    @PostMapping("/{spuId}/resubmit")
    public ApiResponse<SellerProductView> resubmit(@PathVariable Long spuId) {
        return ApiResponse.ok(sellerProductService.resubmit(spuId));
    }

    /**
     * 删除商品（A-09，软删进回收站，30 天内可恢复）。
     *
     * <p>用 POST 动作式端点而不是 HTTP DELETE，与闲置删除（{@code POST /api/listings/{id}/delete}）
     * 保持一致：语义是「移入回收站」而非「资源消失」，恢复端点是它的逆操作。
     * 幂等：重复删除返回 200 与当前视图，不刷新保留期。
     */
    @PostMapping("/{spuId}/delete")
    public ApiResponse<SellerProductView> delete(@PathVariable Long spuId) {
        return ApiResponse.ok(sellerProductService.remove(spuId));
    }

    /** 从回收站恢复（A-09）：超过 30 天保留期返回业务错误；恢复后为下架态，需手动重新上架 */
    @PostMapping("/{spuId}/restore")
    public ApiResponse<SellerProductView> restore(@PathVariable Long spuId) {
        return ApiResponse.ok(sellerProductService.restore(spuId));
    }

    /**
     * 编辑请求体（E14 扩展为全部内容字段）。
     *
     * <p>字段<b>三态</b>：不传 / {@code null} = 不改；{@code ""} = 清空（副标题 / 详情 / 详情图可清空）；
     * 有值 = 替换。前端要把「用户没填」显式归一成 {@code null} 再上行 ——
     * 把「不改」传成空串，用户的原有内容会被清掉。
     *
     * <p><b>内容变更会触发重新审核</b>（第三方商品）：审核结论是针对「当时那段内容」作出的。
     */
    @PutMapping("/{spuId}")
    public ApiResponse<SellerProductView> update(@PathVariable Long spuId,
                                                 @RequestBody(required = false) SellerProductUpdateRequest request) {
        return ApiResponse.ok(sellerProductService.update(spuId, request));
    }

    /**
     * SKU 改价 / 改库存（2026-10-24）。
     *
     * <p>与「编辑」分开是刻意的：价格与库存是高频运营动作，**不触发内容重审**
     * （淘宝/天猫/有赞等平台的通行口径：改价改库存不重新审核，改标题/主图/详情才重审）。
     * 但仍要过归属校验、价格边界校验，并在同事务里同步 SPU 的 minPrice/maxPrice 冗余列。
     */
    @PutMapping("/{spuId}/skus/price-stock")
    public ApiResponse<SellerProductDetailView> updateSkuPriceStock(
            @PathVariable Long spuId,
            @RequestBody(required = false) SellerSkuPriceStockRequest request) {
        return ApiResponse.ok(sellerProductService.updateSkuPriceStock(spuId, request));
    }

    /**
     * SKU 规格结构保存（A-14）：全量集合语义。
     *
     * <p>请求里的 {@code skus} 就是保存后该商品应有的全部规格：{@code id} 为空 = 新增，
     * 库里有、请求里没有 = 删除。一次保存只触发一次重审 / 一次版本递增 / 一次价格区间重算。
     *
     * <p>口径差异：
     * <ul>
     *   <li>新增 / 删除 / 改规格名 / 换规格图 → 第三方商品重新审核，{@code version + 1}；</li>
     *   <li>仅改价格 / 库存 → 不重审、不升版本（与 {@code /skus/price-stock} 一致）；</li>
     *   <li>已有订单（或仍有冻结库存）的规格禁止删除，返回业务错误且不落任何改动。</li>
     * </ul>
     *
     * <p>为什么用 PUT 而不是拆成 POST / DELETE / PATCH 三个端点：结构编辑是一个整体，
     * 全量提交才能保证「保存后正好是用户看到的那一套」；拆开后任意一条失败都会留下半套规格。
     */
    @PutMapping("/{spuId}/skus")
    public ApiResponse<SellerSkuStructureResultView> updateSkuStructure(
            @PathVariable Long spuId,
            @RequestBody(required = false) SellerSkuStructureRequest request) {
        return ApiResponse.ok(sellerProductService.updateSkuStructure(spuId, request));
    }

    /**
     * 规格的订单占用（A-14，只读）：给规格弹窗提前标出「已有订单，不能删除」。
     *
     * <p>只返回被订单引用过的规格；列表外的规格可删。查询失败返回业务错误，
     * 前端展示为「占用情况暂时无法确认」——不把「查不到」说成「没有订单」。
     */
    @GetMapping("/{spuId}/skus/usage")
    public ApiResponse<List<SellerSkuUsageView>> skuUsage(@PathVariable Long spuId) {
        return ApiResponse.ok(sellerProductService.skuUsage(spuId));
    }
}
