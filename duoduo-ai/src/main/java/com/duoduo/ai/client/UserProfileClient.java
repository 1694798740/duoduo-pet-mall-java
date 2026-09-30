package com.duoduo.ai.client;

import com.duoduo.ai.client.dto.PetBrief;
import com.duoduo.common.api.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 用户域客户端：宠物档案（第五十八轮 · AI 加强项 S1）。
 *
 * <p>与 {@link ProductSearchClient} 同一纪律：走 Nacos 服务发现**不经网关**，
 * 凭据由 {@link AiFeignConfig} 的拦截器签发（业务代码不碰密钥）。
 *
 * <p><b>为什么这条调用必须是 best-effort</b>：宠物档案只影响「推荐是否更贴合」，
 * 拿不到它时导购照常能工作（只是少一点个性化）。
 * 若把它当成强依赖，用户域一抖动，**连一句「猫粮推荐」都答不出来** ——
 * 用一份可选的个性化数据去换掉整条链路的可用性，是划不来的交换。
 */
@FeignClient(name = "duoduo-user", path = "/api/users", configuration = AiFeignConfig.class)
public interface UserProfileClient {

    @GetMapping("/internal/pet-profiles")
    ApiResponse<List<PetBrief>> petProfiles(@RequestParam("userId") Long userId);
}
