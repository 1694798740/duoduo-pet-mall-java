package com.duoduo.user.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.context.UserContext;
import com.duoduo.user.service.MerchantOnboardingService;
import com.duoduo.user.support.MerchantMaterialStorage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * 入驻材料上传（E12 · 私有通道）。
 *
 * <p>与公开图片通道（{@code POST /api/uploads}，A15）的关键差别：
 * 文件落在**私有卷**（不挂公开 nginx 路径），读取必须经
 * {@code GET /api/merchant-materials/**} 的鉴权代理 ——
 * 营业执照 + 身份证 + 银行账户是足以开出一家假公司的一整套信息，
 * "URL 难猜"不构成访问控制。
 */
@RestController
@RequestMapping("/api/users/me/merchant-application")
public class MerchantMaterialUploadController {

    private final MerchantOnboardingService onboardingService;

    public MerchantMaterialUploadController(MerchantOnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    /**
     * 上传一份资质材料（multipart，字段名 {@code file}）。
     *
     * <p>返回的 {@code path} 是**鉴权读取路径**（不是公开 URL）：
     * 把它填进 saveDraft 的 materials 即完成与申请的绑定。
     */
    @PostMapping("/materials")
    public ApiResponse<Map<String, String>> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw com.duoduo.common.exception.BizException.businessRule("请选择要上传的文件");
        }
        MerchantMaterialStorage.Stored stored = onboardingService.storeMaterial(
                UserContext.requireUserId(),
                file.getOriginalFilename() == null ? "material" : file.getOriginalFilename(),
                file.getBytes());
        return ApiResponse.ok(Map.of(
                "path", stored.readPath(),
                "storedName", stored.storedName(),
                "originalName", stored.originalName()));
    }
}
