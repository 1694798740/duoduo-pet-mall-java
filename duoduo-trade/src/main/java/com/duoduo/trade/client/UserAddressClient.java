package com.duoduo.trade.client;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.trade.client.dto.AddressCallDtos.ReceiverSnapshot;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 用户服务客户端（服务间调用，走 Nacos 服务发现 + 负载均衡，<b>不经网关</b>）。
 *
 * <p>只用于 A8 的一处场景：下单时按 {@code addressId} 取收货信息快照。
 *
 * <p><b>为什么只能由服务端取，不能让客户端把地址内容一起传上来</b>：
 * 如果下单请求里直接带收货人姓名 / 电话 / 地址文本，
 * 那这个请求就成了「用户能自己拼一份任意收货信息」的入口 ——
 * 地址簿的归属校验就形同虚设（刷单者可以用别人的地址下单，甚至填一份不存在的地址）。
 * 只接受 {@code addressId}、由服务端回查，归属校验才有意义。
 *
 * <p>{@code userId} 与 {@code addressId} 一起传：用户域会独立再校验一次归属
 * （见 {@code AddressService#snapshot}）。两个服务各校验一次不是冗余 ——
 * 交易域不能替用户域担保「这个地址是这人的」，用户域也不能假设交易域一定校验过。
 *
 * <p>内部凭据由 {@code InternalCallConfig} 统一注入 {@code X-Internal-Token}。
 */
@FeignClient(name = "duoduo-user", contextId = "userAddressClient", path = "/api/addresses")
public interface UserAddressClient {

    /** 按用户 + 地址 ID 取收货快照；地址不存在或不属于该用户时用户域返回 404 */
    @GetMapping("/internal/snapshot")
    ApiResponse<ReceiverSnapshot> snapshot(@RequestParam("userId") Long userId,
                                           @RequestParam("addressId") Long addressId);
}
