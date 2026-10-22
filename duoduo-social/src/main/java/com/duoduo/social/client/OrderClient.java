package com.duoduo.social.client;

import com.duoduo.common.api.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

/**
 * 交易域客户端（第七十九轮 C 批）：订单卡片需要「这张单是谁的、什么状态、买了什么」。
 *
 * <p>为什么不让前端把订单快照直接传上来：卡片内容会展示给客服，
 * 若由调用方提供，任何人都能伪造一张「我已购买 XXX」的卡片。
 * 订单事实只能由持有它的交易域给出 —— 前端只传单号。
 */
@FeignClient(name = "duoduo-trade", contextId = "orderFact", path = "/api/orders")
public interface OrderClient {

    @GetMapping("/internal/{orderNo}/fact")
    ApiResponse<OrderFactBrief> fact(@PathVariable("orderNo") String orderNo);

    /** 交易域订单事实的裁剪视图（只取发卡片需要的字段） */
    record OrderFactBrief(String orderNo, Long userId, Long sellerId, String status,
                          String statusLabel, List<Item> items) {
        public record Item(Long spuId, String title, String mainImage, long price, int quantity) {
        }
    }
}
