package com.duoduo.ai.client.dto;

import java.util.List;

/**
 * 通知域（duoduo-social）在 AI 侧的最小只读视图。
 *
 * <p>为什么不在 duoduo-ai 里依赖 duoduo-social 的 jar：跨服务共享 DTO 会把两个
 * 服务的编译期绑死 —— 通知域改一个字段，AI 也要跟着重发版。这里只声明
 * **AI 真正用到的字段**（裁剪原则见 AGENTS.md 硬规矩 4），多余的字段由 Jackson 忽略。
 *
 * <p>字段名与服务端 JSON 一一对应；服务端会把 Long 型 id 序列化成字符串，
 * Jackson 反序列化时会自动转回 {@code Long}，无需额外处理。
 */
public final class ButlerNoticeDtos {

    private ButlerNoticeDtos() {
    }

    /** 通知列表（两源合并后的分页视图）。 */
    public record NoticePage(List<NoticeView> items, long total, int page, int pageSize) {
    }

    /**
     * 单条通知。
     *
     * <p>{@code webPath} 由通知域单独映射（小程序 {@code jumpPage} 在 Web 端不存在），
     * 为 null 表示该通知在网页端没有落地页 —— 前端应「只标已读、不跳转」。
     */
    public record NoticeView(
            Long id,
            String category,
            String title,
            String content,
            String bizType,
            String bizId,
            String jumpPage,
            String webPath,
            boolean read,
            String createdAt
    ) {
    }

    /** 批量已读请求：{@code all=false} 表示只处理 ids 里这些具体通知。 */
    public record BatchRequest(String action, List<Long> ids, boolean all) {
    }

    /** 批量结果：affected = 本次实际标记条数；unread = 操作后的通知未读数。 */
    public record BatchResult(long affected, long unread) {
    }

    /** 合并未读：notice 不含私信与客服（各自域单独计数）。 */
    public record UnreadSummary(long notice, long message, long support, long total) {
    }
}
