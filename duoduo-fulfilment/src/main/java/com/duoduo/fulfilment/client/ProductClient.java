package com.duoduo.fulfilment.client;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.fulfilment.client.dto.ProductCardBrief;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 商品域客户端：批量取商品快照，用于分享圈发布时落快照（第四十三轮）。
 *
 * <p>内部调用由 {@code X-Internal-Token} 保护（见 {@code InternalCallConfig}），
 * 商品域侧由它自己的 {@code InternalTokenFilter} 校验。
 *
 * <p>为什么由服务端取快照而不是信任前端传标题 / 图片：分享圈是公开信息流，
 * 若快照字段来自请求体，任何人都能把「9.9 元猫粮」的卡片贴在奢侈品商品 id 上 ——
 * 快照必须以商品域事实为准，请求体里只留用户自己写的话（content）。
 */
@FeignClient(name = "duoduo-product", path = "/api/products")
public interface ProductClient {

    /**
     * 批量取商品快照；参数为逗号分隔的 SPU ID。
     *
     * <p>参数用字符串而不是 {@code List<Long>}：与 {@link UserClient#briefs} 同一理由，
     * 固定成单值后两端集合编码行为确定。
     */
    @GetMapping("/internal/cards")
    ApiResponse<List<ProductCardBrief>> cards(@RequestParam("ids") String spuIds);
}
