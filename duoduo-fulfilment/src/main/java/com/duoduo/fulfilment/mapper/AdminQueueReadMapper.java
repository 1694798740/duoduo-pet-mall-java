package com.duoduo.fulfilment.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 运营后台队列的「已查看」集合（2026-10-27）。
 *
 * <p>只有写入一个动作：读侧不经过本 Mapper，而是由各域自己的计数 SQL
 * 用 {@code NOT EXISTS} 关联这张表 —— 队列口径必须留在掌握那张业务表的 Mapper 里，
 * 否则一个「评价算不算待办」的规则会被拆成两处。
 *
 * <p>幂等靠复合主键 + {@code INSERT IGNORE}：重复点开同一条不会报错、也不会刷新时间。
 */
@Mapper
public interface AdminQueueReadMapper {

    /** source 取值：REVIEW / REPORT / AFTER_SALE */
    @Insert("INSERT IGNORE INTO t_admin_queue_read (user_id, source, biz_id, read_at) "
            + "VALUES (#{userId}, #{source}, #{bizId}, #{at})")
    int markRead(@Param("userId") Long userId,
                 @Param("source") String source,
                 @Param("bizId") Long bizId,
                 @Param("at") LocalDateTime at);
}
