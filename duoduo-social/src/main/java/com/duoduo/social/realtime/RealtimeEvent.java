package com.duoduo.social.realtime;

import java.time.Instant;

/**
 * 实时事件（2026-10-23）。
 *
 * <h2>设计原则：推送只是**信号**，消息本体仍走 HTTP</h2>
 * 事件里只带「哪个会话有新消息 / 对方读到哪了 / 对方正在输入」这类**路由与状态**信息，
 * 不带消息正文。客户端收到后立刻调已有的会话接口渲染，因此：
 * <ul>
 *   <li>消息渲染只存在一套实现（HTTP 接口），不会出现「推送渲染」与「拉取渲染」两套格式；</li>
 *   <li>{@code mine} 这类**跟随观察者变化**的字段不会错 —— 把发送者的视图直接推给接收方，
 *       会让对方看到自己发的消息被标成"我发的"；</li>
 *   <li>断线期间漏掉事件也不会丢消息：重连后照常拉取即可，实时通道无状态。</li>
 * </ul>
 *
 * @param type       MESSAGE / READ / TYPING / READY / PONG
 * @param biz        SUPPORT（客服） / DIRECT（私信）；READY / PONG 为 null
 * @param sessionId  客服会话 id（biz=SUPPORT）
 * @param conversationId 私信会话键（biz=DIRECT）
 * @param fromUserId 事件发起人（对端收到时用于区分是买家还是客服）
 * @param messageId  新消息 id（MESSAGE）
 * @param lastReadId 对端读到的最大消息 id（READ）
 */
public record RealtimeEvent(
        String type,
        String biz,
        String sessionId,
        String conversationId,
        String fromUserId,
        String messageId,
        String lastReadId,
        long at
) {

    public static final String MESSAGE = "MESSAGE";
    public static final String READ = "READ";
    public static final String TYPING = "TYPING";
    public static final String RECALL = "RECALL";
    public static final String READY = "READY";
    public static final String PONG = "PONG";

    public static RealtimeEvent message(String biz, String sessionId, String conversationId,
                                        Long fromUserId, Long messageId) {
        return new RealtimeEvent(MESSAGE, biz, sessionId, conversationId,
                fromUserId == null ? null : String.valueOf(fromUserId),
                messageId == null ? null : String.valueOf(messageId), null, now());
    }

    public static RealtimeEvent read(String biz, String sessionId, String conversationId,
                                     Long readerId, Long lastReadId) {
        return new RealtimeEvent(READ, biz, sessionId, conversationId,
                readerId == null ? null : String.valueOf(readerId), null,
                lastReadId == null ? null : String.valueOf(lastReadId), now());
    }

    public static RealtimeEvent typing(String biz, String sessionId, String conversationId, Long fromUserId) {
        return new RealtimeEvent(TYPING, biz, sessionId, conversationId,
                fromUserId == null ? null : String.valueOf(fromUserId), null, null, now());
    }

    /** 撤回（2026-10-24）：messageId 携带被撤回的消息 id，对端收到后回源刷新 */
    public static RealtimeEvent recall(String biz, String sessionId, String conversationId,
                                       Long fromUserId, Long messageId) {
        return new RealtimeEvent(RECALL, biz, sessionId, conversationId,
                fromUserId == null ? null : String.valueOf(fromUserId),
                messageId == null ? null : String.valueOf(messageId), null, now());
    }

    public static RealtimeEvent simple(String type) {
        return new RealtimeEvent(type, null, null, null, null, null, null, now());
    }

    private static long now() {
        return Instant.now().toEpochMilli();
    }
}
