package com.duoduo.user.controller;

import com.duoduo.user.service.MerchantOnboardingService;
import com.duoduo.user.support.MerchantMaterialStorage;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;

/**
 * 入驻材料的**鉴权读取**端点（E12）。
 *
 * <p>路径是 {@code /api/merchant-materials/{merchantId}/{storedName}} ——
 * 刻意挂在 {@code /api/**} 下：网关对它强制登录（不进白名单），
 * "是否该看这份材料"由 {@code MerchantOnboardingService.readMaterial}
 * 在服务端判断（店主本人 / 运营），越权 403。
 *
 * <p>不进 E8 的"专属路径族"是有意的：店主从商家站点（ent=merchant）看自己的材料、
 * 运营从后台站点（ent=admin）审核，两个入口都合法 —— 这里收的是**归属**，不是入口。
 */
@RestController
public class MerchantMaterialReadController {

    private final MerchantOnboardingService onboardingService;

    public MerchantMaterialReadController(MerchantOnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    @GetMapping("/api/merchant-materials/{merchantId}/{storedName}")
    public ResponseEntity<ByteArrayResource> read(@PathVariable("merchantId") long merchantId,
                                                  @PathVariable("storedName") String storedName) {
        MerchantMaterialStorage.ReadResult result =
                onboardingService.readMaterial(
                        com.duoduo.common.context.UserContext.requireUserId(), merchantId, storedName);
        try {
            byte[] bytes = Files.readAllBytes(result.file());
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(result.contentType()))
                    // 私有材料禁止共享缓存：CDN/代理不得存，浏览器仅本会话可用
                    .cacheControl(CacheControl.noStore())
                    .body(new ByteArrayResource(bytes));
        } catch (Exception ex) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
