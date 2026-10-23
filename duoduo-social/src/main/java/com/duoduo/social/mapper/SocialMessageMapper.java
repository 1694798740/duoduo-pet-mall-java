package com.duoduo.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.social.entity.SocialMessageEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 私信 Mapper（第四十七轮）。
 *
 * <p>聚合类查询（会话列表的「每人最后一条」）用注解 SQL：
 * MyBatis-Plus 的条件构造器表达不了「按会话分组取最大 id 再回连」这类查询，
 * 硬拼在应用侧会把整张表拉回来 —— 聊天是本域最高频的读路径，必须让数据库做裁剪。
 */
public interface SocialMessageMapper extends BaseMapper<SocialMessageEntity> {

    /**
     * 会话列表：每个会话取最新一条消息。
     *
     * <p>先在会话维度聚合出最大 id（即最新一条），再回连取整行。
     * 「我参与」= 我发过或我收过。
     */
    @Select("""
            SELECT m.* FROM t_social_message m
            JOIN (
                SELECT conversation_id, MAX(id) AS max_id
                FROM t_social_message
                WHERE sender_id = #{userId} OR receiver_id = #{userId}
                GROUP BY conversation_id
            ) last ON last.max_id = m.id
            ORDER BY m.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<SocialMessageEntity> selectLastPerConversation(@Param("userId") Long userId,
                                                        @Param("limit") int limit,
                                                        @Param("offset") int offset);

    /** 会话列表总数（分页 total）：我参与的去重会话数 */
    @Select("""
            SELECT COUNT(DISTINCT conversation_id) FROM t_social_message
            WHERE sender_id = #{userId} OR receiver_id = #{userId}
            """)
    long countConversations(@Param("userId") Long userId);

    /** 各会话的未读数：我作为接收方、还没读的 */
    @Select("""
            SELECT conversation_id, COUNT(*) AS unread
            FROM t_social_message
            WHERE receiver_id = #{userId} AND read_at IS NULL
            GROUP BY conversation_id
            """)
    List<UnreadRow> selectUnreadGrouped(@Param("userId") Long userId);

    /**
     * 我的私信未读总数（第五十五轮）：tabBar 角标口径的一半。
     *
     * <p>为什么必须落到 SQL：角标刷新会在「每次回到前台 / 每 60 秒 / 每个 tab 页 onShow」
     * 反复触发，而此前前端只能拉一页会话（50 条）在内存里求和 —— 会话数超过一页时
     * 角标会偏小，且每次刷新都要传一页数据。一个 COUNT 就够了。
     */
    @Select("""
            SELECT COUNT(*) FROM t_social_message
            WHERE receiver_id = #{userId} AND read_at IS NULL
            """)
    long countUnread(@Param("userId") Long userId);

    /** 某会话里指定发送方的消息数（非互关「每人一条」的判定依据） */
    @Select("""
            SELECT COUNT(*) FROM t_social_message
            WHERE conversation_id = #{conversationId} AND sender_id = #{senderId}
            """)
    long countSentByMe(@Param("conversationId") String conversationId,
                       @Param("senderId") Long senderId);

    /** 某会话里是否出现过商品咨询（ASK）：出现过即视为咨询会话，双向不限条数 */
    @Select("""
            SELECT COUNT(*) FROM t_social_message
            WHERE conversation_id = #{conversationId} AND biz_type = 'ASK'
            """)
    long countAskInConversation(@Param("conversationId") String conversationId);

    /** 频控：窗口期内我在该会话发出的条数 */
    @Select("""
            SELECT COUNT(*) FROM t_social_message
            WHERE conversation_id = #{conversationId} AND sender_id = #{senderId}
              AND created_at >= UTC_TIMESTAMP() - INTERVAL #{windowSeconds} SECOND
            """)
    long countSentInWindow(@Param("conversationId") String conversationId,
                           @Param("senderId") Long senderId,
                           @Param("windowSeconds") int windowSeconds);

    /** 我已经发过言的会话集合（非互关「每人一条」的批量判定，避免逐会话 COUNT 的 N+1） */
    @Select("""
            SELECT DISTINCT conversation_id FROM t_social_message WHERE sender_id = #{userId}
            """)
    List<String> selectMySentConversations(@Param("userId") Long userId);

    /** 出现过商品咨询（ASK）的会话集合：咨询会话双向不限条数 */
    @Select("""
            SELECT DISTINCT conversation_id FROM t_social_message
            WHERE biz_type = 'ASK' AND (sender_id = #{userId} OR receiver_id = #{userId})
            """)
    List<String> selectAskConversations(@Param("userId") Long userId);

    /**
     * 该会话里「我作为收件人」读过（read_at 非空）的最大消息 id。
     *
     * <p>已读回执的判据：对端只要知道"读到了哪一条"，就能把之前的全标已读。
     * 用 MAX(id) 而不是 COUNT，是因为消息 id 单调递增，游标语义比条数更稳
     * （重复调用不会因为重复计数而错位）。
     */
    @Select("""
            SELECT COALESCE(MAX(id), 0) FROM t_social_message
            WHERE conversation_id = #{conversationId} AND receiver_id = #{userId} AND read_at IS NOT NULL
            """)
    long maxReadId(@Param("conversationId") String conversationId, @Param("userId") Long userId);

    /** 未读行投影（列名 conversation_id 经 map-underscore-to-camel-case 映射到 conversationId） */
    class UnreadRow {
        private String conversationId;
        private long unread;

        public String getConversationId() {
            return conversationId;
        }

        public void setConversationId(String conversationId) {
            this.conversationId = conversationId;
        }

        public long getUnread() {
            return unread;
        }

        public void setUnread(long unread) {
            this.unread = unread;
        }
    }
}
