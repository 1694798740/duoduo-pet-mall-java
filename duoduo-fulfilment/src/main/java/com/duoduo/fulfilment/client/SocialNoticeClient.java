package com.duoduo.fulfilment.client;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.fulfilment.client.dto.NoticePushDtos.PushRequest;
import com.duoduo.fulfilment.client.dto.NoticePushDtos.PushResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 社交域通知客户端（第五十五轮）：把售后进度与物流终态落成买家的通知。
 *
 * <p><b>{@code contextId} 必须写</b>：Feign 以 {@code name} 为注册键，同 name 的多个接口
 * 不区分 {@code contextId} 会互相覆盖，表现为启动即炸（{@code BeanDefinitionOverrideException}）。
 * 本域指向 {@code duoduo-social} 的接口已有两个（本接口 + 第五十六轮的
 * {@link SocialClient} 关注列表），各自显式声明 —— 不加就会被覆盖。
 *
 * <p>凭据由 {@code InternalCallConfig} 注入 {@code X-Internal-Token}；走服务发现，不经网关。
 * 调用方是 {@link FulfilmentNotifier}（best-effort 包装），不在业务路径上直接调。
 */
@FeignClient(name = "duoduo-social", contextId = "socialNoticePush", path = "/api/socials/internal/notices")
public interface SocialNoticeClient {

    /** 批量推送通知（单批最多 100 条；服务端逐字段校验，非法整批不写并回 400） */
    @PostMapping
    ApiResponse<PushResult> push(@RequestBody PushRequest request);
}
