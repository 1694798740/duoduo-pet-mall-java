package com.duoduo.social.realtime;

import com.duoduo.social.mapper.SupportSessionMapper;
import com.duoduo.social.support.entity.SupportSessionEntity;
import org.springframework.stereotype.Component;

/**
 * 「我是谁 → 该推给谁」的解析（2026-10-23）。
 *
 * <h2>为什么不让客户端传 peerUserId</h2>
 * 客户端传什么就推给什么，等于给了一个「向任意用户投递事件」的接口 ——
 * 即使事件只是"某某正在输入"，也足以用来做骚扰、探测在线状态。
 * 这里一律**从库里的事实反推对端**：客服会话按 {@code seller_id/user_id} 判定，
 * 私信按会话键里的两个 userId 判定，且必须包含当前用户，否则丢弃。
 */
@Component
public class RealtimePeerResolver {

    private final SupportSessionMapper sessionMapper;

    public RealtimePeerResolver(SupportSessionMapper sessionMapper) {
        this.sessionMapper = sessionMapper;
    }

    /** 客服会话的对端；当前用户不是会话参与方时返回 null */
    public Long supportPeer(Long me, String sessionId) {
        Long id = parseLong(sessionId);
        if (me == null || id == null) {
            return null;
        }
        SupportSessionEntity session = sessionMapper.selectById(id);
        if (session == null) {
            return null;
        }
        if (me.equals(session.getSellerId())) {
            return session.getUserId();
        }
        if (me.equals(session.getUserId())) {
            return session.getSellerId();
        }
        return null;
    }

    /** 私信会话的对端；键形如 {@code lo:hi} / {@code lo:hi:p123} / {@code lo:hi:l456} */
    public Long directPeer(Long me, String conversationId) {
        if (me == null || conversationId == null) {
            return null;
        }
        String[] parts = conversationId.split(":");
        if (parts.length < 2) {
            return null;
        }
        Long a = parseLong(parts[0]);
        Long b = parseLong(parts[1]);
        if (a == null || b == null) {
            return null;
        }
        if (me.equals(a)) {
            return b;
        }
        if (me.equals(b)) {
            return a;
        }
        return null;
    }

    private Long parseLong(String raw) {
        try {
            return raw == null || raw.isBlank() ? null : Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
