package com.duoduo.social.client;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.social.client.dto.ListingCardBrief;
import com.duoduo.social.client.dto.WantResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 商品域闲置客户端（第五十三轮）：取闲置卡事实。
 *
 * <p>两个用途：①闲置咨询（LISTING）在服务端定位收件人（闲置归属卖家，不信前端）；
 * ②闲置卡片消息按 bizId 现取展示字段，公开接口贴不出伪造卡片。
 * 与 {@link ProductClient}（商城商品）分开声明的原因：两者的基础路径不同
 * （{@code /api/listings} vs {@code /api/products}），一个 Feign 接口一个基础路径。
 * contextId 必填：与 ProductClient 同 name，不区分 contextId 会注册同名
 * {@code FeignClientSpecification} 导致启动失败（第五十三轮实测）。
 */
@FeignClient(name = "duoduo-product", contextId = "listingCard", path = "/api/listings")
public interface ListingClient {

    @GetMapping("/internal/cards")
    ApiResponse<List<ListingCardBrief>> cards(@RequestParam("ids") String listingIds);

    /**
     * 「想要」上报（第五十四轮）：买家首次向闲置卖家发出咨询消息时计入 1 人。
     *
     * <p>幂等由商品域唯一键保证：同一买家对同一闲置重复上报返回 {@code counted=false}，
     * 不报错也不重复计数 —— 所以这里可以放心地在每次 LISTING 咨询时调用。
     *
     * <p>调用是 <b>best-effort</b>：失败只记日志（见 {@code MessageService#markWantQuietly}）。
     * 消息本身已经落库，为一个附属统计把整次发送判成失败，用户会看到「发送失败」
     * 但消息其实已经发出去了 —— 那比计数不准更难排查。
     */
    @PostMapping("/internal/{id}/want")
    ApiResponse<WantResult> markWant(@PathVariable("id") Long listingId,
                                     @RequestParam("buyerId") Long buyerId);
}
