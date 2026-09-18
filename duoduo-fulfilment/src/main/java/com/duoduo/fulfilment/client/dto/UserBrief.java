package com.duoduo.fulfilment.client.dto;

/**
 * 用户公开资料（与 {@code com.duoduo.user.dto.UserDtos.UserBrief} 对齐）。
 *
 * <p>只有 id / 昵称 / 头像 —— 手机号、实名信息、角色、信用分都不在契约里。
 * 跨服务共享 DTO 的诱惑很大，但代价是「一处加字段，两个服务都要重发」；
 * 这里只保留展示所必需的字段，契约由冒烟测试守住字段名与语义。
 */
public record UserBrief(
        Long id,
        String nickname,
        String avatar
) {
}
