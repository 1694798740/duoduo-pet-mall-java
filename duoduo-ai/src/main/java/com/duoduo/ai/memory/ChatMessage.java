package com.duoduo.ai.memory;

/**
 * 会话消息（Redis 中的轻量结构）。
 */
public record ChatMessage(String role, String content, long at) {
}
