package com.duoduo.user.client;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.user.client.dto.LicenseNoticeDtos.PushRequest;
import com.duoduo.user.client.dto.LicenseNoticeDtos.PushResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 社交域通知客户端（E15）：把资质临期 / 过期停业落成店主本人的通知。
 *
 * <p>这是全项目<b>第三个</b>给用户发通知的调用方（前两个是交易域与履约域，
 * 见 {@code duoduo-fulfilment} 的 {@code SocialNoticeClient}）—— 直接照同一套写，
 * 因为「谁在什么时候因为什么给人发通知」这件事，多一套写法就多一套要记住的差异。
 *
 * <p>{@code contextId} 必须写：Feign 以 {@code name} 为注册键，同 name 的多个接口
 * 不区分 {@code contextId} 会互相覆盖，表现为启动即炸（{@code BeanDefinitionOverrideException}）。
 * 本域目前只有一个指向 social 的接口，但保持显式写上 —— 下一个人加第二个接口时
 * 不需要回头补这一个。
 *
 * <p>凭据由 {@code InternalCallConfig} 注入；走服务发现，不经网关。
 * 调用方是 {@code MerchantLicenseNotifier}（best-effort 包装），不在业务事务里直接调。
 */
@FeignClient(name = "duoduo-social", contextId = "userSocialNoticePush",
        path = "/api/socials/internal/notices")
public interface SocialNoticeClient {

    /** 批量推送通知（单批最多 100 条；服务端逐字段校验，非法整批不写并回 400） */
    @PostMapping
    ApiResponse<PushResult> push(@RequestBody PushRequest request);
}
