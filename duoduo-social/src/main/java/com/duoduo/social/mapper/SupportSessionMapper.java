package com.duoduo.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.social.support.entity.SupportSessionEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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
}
