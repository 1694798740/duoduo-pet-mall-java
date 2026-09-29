package com.duoduo.trade.client;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.trade.client.dto.NoticePushDtos.PushRequest;
import com.duoduo.trade.client.dto.NoticePushDtos.PushResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 社交域通知客户端（第五十五轮）：把订单事件落成买家的通知。
 *
 * <p><b>{@code contextId} 必须写</b>：Feign 以 {@code name} 作为注册键，
 * 同 name 的多个接口不区分 {@code contextId} 会互相覆盖，表现为启动即炸
 * （{@code BeanDefinitionOverrideException}）。本域目前只有一个指向 {@code duoduo-social}
 * 的接口，按说可以不写 —— 但仍然显式写上，是为了让「下一个人加第二个客户端」时
 * 不必先踩一次坑才学会这条规则。
 *
 * <p>内部凭据由 {@code InternalCallConfig} 统一注入 {@code X-Internal-Token}；
 * 走 Nacos 服务发现，<b>不经网关</b>。
 *
 * <p>调用方是 {@link OrderNotifier}（best-effort 包装），不在业务事务里直接调。
 */
@FeignClient(name = "duoduo-social", contextId = "socialNoticePush", path = "/api/socials/internal/notices")
public interface SocialNoticeClient {

    /** 批量推送通知（单批最多 100 条；服务端做全字段校验，非法整批不写并回 400） */
    @PostMapping
    ApiResponse<PushResult> push(@RequestBody PushRequest request);
}
