package com.duoduo.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.social.support.entity.SupportMessageEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 客服消息 Mapper。
 *
 * <p>只按 session_id 取数：会话归属在服务层先用 (sellerId, sessionId) 校验过，
 * 这里不再拼「是不是我」的条件 —— 两处都写会让「该报 403 还是 404」变得含糊。
 */
public interface SupportMessageMapper extends BaseMapper<SupportMessageEntity> {

    /** 频控：同会话同一发送方在窗口内的条数（用 UTC_TIMESTAMP 与写入侧的 UTC 时间对齐） */
    @Select("""
            SELECT COUNT(*) FROM t_support_message
            WHERE session_id = #{sessionId} AND sender_id = #{senderId}
              AND created_at >= DATE_SUB(UTC_TIMESTAMP(), INTERVAL #{seconds} SECOND)
            """)
    long countSentInWindow(@Param("sessionId") Long sessionId,
                           @Param("senderId") Long senderId,
                           @Param("seconds") int seconds);

    /** 会话内最大消息 id（标记已读时把游标推到最前） */
    @Select("SELECT COALESCE(MAX(id), 0) FROM t_support_message WHERE session_id = #{sessionId}")
    long maxMessageId(@Param("sessionId") Long sessionId);

    /**
     * 某发送方在游标之后发出的条数 —— 「对方还有 N 条没读」。
     *
     * <p>客服消息表没有 receiver_id，接收方由 session 的买卖双方反推；
     * 这里只需「谁发的 + 游标之后」，不涉及接收方。
     */
    @Select("""
            SELECT COUNT(*) FROM t_support_message
            WHERE session_id = #{sessionId} AND sender_id = #{senderId} AND id > #{afterId}
            """)
    long countSentAfter(@Param("sessionId") Long sessionId,
                        @Param("senderId") Long senderId,
                        @Param("afterId") long afterId);
}
