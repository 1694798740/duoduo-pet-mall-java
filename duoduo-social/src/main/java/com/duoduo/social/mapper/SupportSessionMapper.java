package com.duoduo.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.social.support.entity.SupportSessionEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 客服会话 Mapper。
 *
 * <p>按 {@code (seller_id, user_id)} 唯一定位 —— 一人对一个商家只有一条会话，
 * 商品上下文挂在消息上而不是拆会话（工作台不该被同一用户的多件商品刷屏）。
 */
public interface SupportSessionMapper extends BaseMapper<SupportSessionEntity> {

    @Select("""
            SELECT * FROM t_support_session
            WHERE seller_id = #{sellerId} AND user_id = #{userId}
            LIMIT 1
            """)
    SupportSessionEntity selectByPair(@Param("sellerId") Long sellerId, @Param("userId") Long userId);

    /** 用户侧未读总数（角标口径的一项） */
    @Select("SELECT COALESCE(SUM(unread_for_user), 0) FROM t_support_session WHERE user_id = #{userId}")
    long sumUnreadForUser(@Param("userId") Long userId);

    /** 商家侧未读总数（工作台页签角标） */
    @Select("SELECT COALESCE(SUM(unread_for_seller), 0) FROM t_support_session WHERE seller_id = #{sellerId}")
    long sumUnreadForSeller(@Param("sellerId") Long sellerId);

    /**
     * 未读计数原子增减。
     *
     * <p>刻意用 {@code unread = unread + 1} 而不是「先读后写」：
     * 用户与商家同时发消息时，读-改-写会丢一次自增，表现为「未读数永远差一条」，
     * 而这种偏差在手工点几下时几乎测不出来。
     */
    @Update("""
            UPDATE t_support_session
            SET unread_for_user = GREATEST(unread_for_user + #{delta}, 0)
            WHERE id = #{id}
            """)
    int bumpUnreadForUser(@Param("id") Long id, @Param("delta") int delta);

    @Update("""
            UPDATE t_support_session
            SET unread_for_seller = GREATEST(unread_for_seller + #{delta}, 0)
            WHERE id = #{id}
            """)
    int bumpUnreadForSeller(@Param("id") Long id, @Param("delta") int delta);

    /**
     * 自动关闭：双方超过 {@code hours} 小时没有新消息的 OPEN 会话。
     *
     * <p>用「一条 UPDATE + WHERE」而不是「查出来再逐条改」：
     * 扫描是分钟级任务，逐条写会把一次扫描变成上千次往返；而且条件写在 WHERE 里，
     * 多实例同时跑也只会有一个生效（另一个 affected=0），天然幂等，不需要分布式锁。
     *
     * <p>{@code closed_at} 用 {@code COALESCE} 保护：已经是「关→买家重开→再超时」的会话，
     * 重开时已清空该列，这里写入新时刻；极端并发下也不会把已存在的关闭时间改小。
     */
    @Update("""
            UPDATE t_support_session
            SET status = 'CLOSED', closed_at = COALESCE(closed_at, #{now}), updated_at = #{now}
            WHERE status = 'OPEN' AND last_message_at < #{threshold}
            """)
    int closeIdleSessions(@Param("threshold") LocalDateTime threshold, @Param("now") LocalDateTime now);

    /**
     * 清理候选：已关闭且关闭时间早于阈值（= 关闭满 N 天）。
     *
     * <p>额外要求 {@code last_message_at} 也早于阈值 —— 关闭后若还有消息（数据被旁路写入），
     * 它就不算「双方无往来」，不删。宁可少删一条，也不删掉还在用的会话。
     */
    @Select("""
            SELECT * FROM t_support_session
            WHERE status = 'CLOSED' AND closed_at IS NOT NULL AND closed_at < #{threshold}
              AND last_message_at < #{threshold}
            ORDER BY closed_at ASC
            LIMIT #{limit}
            """)
    java.util.List<SupportSessionEntity> selectExpired(@Param("threshold") LocalDateTime threshold,
                                                       @Param("limit") int limit);

    /** 物理删除时的二次校验：只有仍处于「已关闭且过期」状态才允许删（防与重开并发） */
    @org.apache.ibatis.annotations.Delete("""
            DELETE FROM t_support_session
            WHERE id = #{id} AND status = 'CLOSED' AND closed_at IS NOT NULL AND closed_at < #{threshold}
            """)
    int deleteExpired(@Param("id") Long id, @Param("threshold") LocalDateTime threshold);

    // ---------------- C-04 · 工单认领 / 转交 / 释放 ----------------

    /**
     * 认领：只有未认领时成功（并发认领只有一个请求 affected=1）。
     * 归属条件 seller_id 同时写在 WHERE 里，越权调用不可能改到别家会话。
     */
    @Update("""
            UPDATE t_support_session
            SET assignee_id = #{actorId}, assigned_at = #{now}, updated_at = #{now}
            WHERE id = #{id} AND seller_id = #{ownerId} AND assignee_id IS NULL
            """)
    int claimTicket(@Param("id") Long id, @Param("ownerId") Long ownerId,
                    @Param("actorId") Long actorId, @Param("now") LocalDateTime now);

    /**
     * 转交：动作前的处理人必须仍是 {@code fromAssignee}（NULL 表示未认领时可指定处理人）。
     * 条件更新保证「两个店员同时转交」只有一个成功，另一个收到 409。
     */
    @Update("""
            <script>
            UPDATE t_support_session
            SET assignee_id = #{toAssignee}, assigned_at = #{now}, updated_at = #{now}
            WHERE id = #{id} AND seller_id = #{ownerId}
            <choose>
              <when test="fromAssignee != null">AND assignee_id = #{fromAssignee}</when>
              <otherwise>AND assignee_id IS NULL</otherwise>
            </choose>
            </script>
            """)
    int transferTicket(@Param("id") Long id, @Param("ownerId") Long ownerId,
                       @Param("fromAssignee") Long fromAssignee,
                       @Param("toAssignee") Long toAssignee,
                       @Param("now") LocalDateTime now);

    /** 释放：当前处理人归还为未认领；只有仍是 {@code fromAssignee} 时成功 */
    @Update("""
            UPDATE t_support_session
            SET assignee_id = NULL, assigned_at = NULL, updated_at = #{now}
            WHERE id = #{id} AND seller_id = #{ownerId} AND assignee_id = #{fromAssignee}
            """)
    int releaseTicket(@Param("id") Long id, @Param("ownerId") Long ownerId,
                      @Param("fromAssignee") Long fromAssignee,
                      @Param("now") LocalDateTime now);
}
