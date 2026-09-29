package com.duoduo.fulfilment.client;

import com.duoduo.common.api.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 社交域关注关系客户端（第五十六轮）：取「某用户关注的人」的 id 列表，供分享圈关注流使用。
 *
 * <p><b>{@code contextId} 必须写且必须唯一</b>：Feign 以 {@code name} 为注册键，
 * 同 name 的多个接口若共用默认 contextId 会互相覆盖，表现为**启动即炸**
 * （{@code BeanDefinitionOverrideException}，编译期完全无感）。本域已有
 * {@code SocialNoticeClient} 指向同一个 {@code duoduo-social}，两者必须各用各的。
 *
 * <p>凭据由 {@code InternalCallConfig} 注入 {@code X-Internal-Token}；走服务发现，不经网关。
 *
 * <p>调用方是 {@code ShareService} 的关注流分支。社交域不可用时**显式报错**
 * （「关注列表暂时获取不到，请稍后重试」）而<b>不是</b>退化成空列表 ——
 * 空列表会让用户看到「还没有关注的人」，然后去检查自己的关注列表，找不到问题。
 * 注意这条取舍只作用于「关注」流：「推荐」流不依赖本接口，
 * 用户不会因为社交域抖动就逛不了分享圈。
 */
@FeignClient(name = "duoduo-social", contextId = "socialFollowingIds", path = "/api/socials")
public interface SocialClient {

    /**
     * 取关注列表（id 数组，按关注时间倒序，服务端再夹一次上限）。
     *
     * <p>参数用 {@code Long} 而不是字符串：这是单个数值参数，不存在集合编码差异问题。
     */
    @GetMapping("/internal/following")
    ApiResponse<List<Long>> following(@RequestParam("userId") Long userId,
                                      @RequestParam("limit") int limit);
}
