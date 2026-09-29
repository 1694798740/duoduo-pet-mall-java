package com.duoduo.ai.client;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.ai.client.dto.CategoryBrief;
import com.duoduo.ai.client.dto.ProductCandidate;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 商品域客户端：候选召回（走 Nacos 服务发现 + 负载均衡，**不经网关**，与 OrderClient 同纪律）。
 *
 * <p>AI 导购的「真实商品」边界就在这条调用上：候选由商品域的检索逻辑给出
 * （{@code /api/products/internal/candidates}），本服务从不自己拼商品 ——
 * 拼出来的商品一没有价格事实、二没有库存事实，展示出去就是编造。
 *
 * <p><b>超时（第五十七轮 · M2）</b>：本客户端挂 {@link AiFeignConfig}，把 connect / read
 * 超时压到秒级以内。此前它用的是 Feign 默认（10s 级），而导购对话的目标响应是 1~3s ——
 * 商品域一慢，AI 的请求线程就被占住，用户看到的是「AI 挂了」，实际是别的服务慢。
 * 超时后走降级话术（见 {@code ChatService}），故障归因才清楚。
 *
 * <p><b>约束参数（第五十七轮 · M1）</b>：{@code budgetMin}/{@code budgetMax} 单位是**分**，
 * {@code categoryIds} 是逗号分隔的类目 id。它们在商品域变成 SQL 条件，
 * 而不是提示词里的一句提醒 —— 这是「预算不被越过」唯一可靠的做法。
 */
@FeignClient(name = "duoduo-product", path = "/api/products", configuration = AiFeignConfig.class)
public interface ProductSearchClient {

    /** 按用户消息召回候选（商品域侧做滑窗词元匹配 + 相关性重排；品类约束会展开子类目） */
    @GetMapping("/internal/candidates")
    ApiResponse<List<ProductCandidate>> candidates(@RequestParam("keyword") String keyword,
                                                   @RequestParam("limit") int limit,
                                                   @RequestParam(value = "budgetMin", required = false) Long budgetMin,
                                                   @RequestParam(value = "budgetMax", required = false) Long budgetMax,
                                                   @RequestParam(value = "categoryIds", required = false) String categoryIds);

    /** 类目简表：用于「用户话 → 品类」识别与澄清问题的可点选项 */
    @GetMapping("/internal/categories")
    ApiResponse<List<CategoryBrief>> categories();
}
