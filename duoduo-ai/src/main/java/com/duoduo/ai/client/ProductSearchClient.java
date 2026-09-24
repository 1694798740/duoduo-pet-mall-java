package com.duoduo.ai.client;

import com.duoduo.common.api.ApiResponse;
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
 */
@FeignClient(name = "duoduo-product", path = "/api/products")
public interface ProductSearchClient {

    /** 按用户消息召回候选（商品域侧做滑窗词元匹配 + 相关性重排） */
    @GetMapping("/internal/candidates")
    ApiResponse<List<ProductCandidate>> candidates(@RequestParam("keyword") String keyword,
                                                   @RequestParam("limit") int limit);
}
