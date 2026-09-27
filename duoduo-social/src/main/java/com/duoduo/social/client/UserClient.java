package com.duoduo.social.client;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.social.client.dto.UserBrief;
import com.duoduo.social.client.dto.UserRoleBrief;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 用户域客户端：批量取公开资料（昵称 / 头像）与角色快照。
 *
 * <p>只取展示所需的最小字段集（与履约域 UserClient 同一口径）。
 * 参数用逗号分隔字符串而非集合：两端集合参数的编码规则在不同 Feign 版本下有差异，
 * 固定单值后行为确定。
 */
@FeignClient(name = "duoduo-user", path = "/api/users")
public interface UserClient {

    /** 批量取昵称与头像（列表页补全展示，避免逐条回源的 N 次往返） */
    @GetMapping("/internal/briefs")
    ApiResponse<List<UserBrief>> briefs(@RequestParam("ids") String userIds);

    /** 批量取角色（发消息时落 sender_role 快照，通知按它分流好友 / 商家消息） */
    @GetMapping("/internal/roles")
    ApiResponse<List<UserRoleBrief>> roles(@RequestParam("ids") String userIds);
}
