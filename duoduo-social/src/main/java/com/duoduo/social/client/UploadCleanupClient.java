package com.duoduo.social.client;

import com.duoduo.common.api.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * 媒体清理客户端（2026-10-23）：把「客服会话过期清理」腾出来的图片/视频从对象存储删掉。
 *
 * <h2>为什么由履约域代删，而不是社交域自己删</h2>
 * 上传是履约域的能力（{@code POST /api/uploads}），对象存储的选型、桶、公共域名全在它那边。
 * 社交域如果自己也配一套 COS 凭据，就会出现"两处配置"——一旦只改一处，
 * 清理逻辑看起来成功、实际删的是另一个桶（或者落到本地盘删了个空目录），
 * 属于**静默失败**里最难查的一类。所以这里只表达意图：「这些 URL 对应的对象可以删」。
 *
 * <p>接口是内网签名接口（{@code X-Internal-Token}），由 {@code InternalCallConfig} 统一签发。
 * 只接受 {@code uploads/} 前缀的对象，资质材料（{@code materials/}）不可能被误删。
 */
@FeignClient(name = "duoduo-fulfilment", contextId = "uploadCleanup", path = "/api/uploads")
public interface UploadCleanupClient {

    /** 返回真正删除的对象数；不存在的对象按「已删除」处理（幂等） */
    @PostMapping("/internal/delete")
    ApiResponse<Integer> delete(@RequestBody List<String> urls);
}
