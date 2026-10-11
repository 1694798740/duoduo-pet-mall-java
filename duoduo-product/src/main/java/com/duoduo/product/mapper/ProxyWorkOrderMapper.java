package com.duoduo.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.product.entity.ProxyWorkOrderEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 代运营工单数据访问（E23 · 商品域）。
 *
 * <p>与交易域的同名 Mapper 逐字同构（表也是同构的）。**刻意不做共享抽象**：
 * 两个服务之间没有代码依赖，为了复用一段 SQL 去建一个跨服务的公共模块，
 * 会让其中一方的任何改动都可能影响另一方 —— 而它们之间根本不存在需要协调的事实。
 * 真正的共享在**契约层**（列名、状态取值、判据顺序），那由 DDL 与类的注释保证。
 *
 * <p>{@link #consume} 的 {@code WHERE status = 'OPEN'} 是唯一的并发屏障：
 * 竞争下两方都读到 OPEN、都执行动作，就是**一个工单做了两次操作**。
 * 这类错误在单机测试里永远复现不出来，只会在真实并发里偶发。
 */
@Mapper
public interface ProxyWorkOrderMapper extends BaseMapper<ProxyWorkOrderEntity> {

    @Select("SELECT * FROM t_proxy_work_order WHERE work_order_no = #{no}")
    ProxyWorkOrderEntity selectByNo(@Param("no") String workOrderNo);

    /**
     * 按 id 消费工单（一次性）。
     *
     * @return 1 = 本次消费成立；0 = 已被消费 / 已作废（授权不再可用）
     */
    @Update("UPDATE t_proxy_work_order SET status = 'CONSUMED', consumed_at = #{at}, "
            + "consumed_by = #{by}, updated_at = #{at} "
            + "WHERE id = #{id} AND status = 'OPEN'")
    int consume(@Param("id") Long id, @Param("by") Long consumedBy, @Param("at") LocalDateTime at);

    /** 工单作废（只对 OPEN 生效，与消费同一套 CAS 约束） */
    @Update("UPDATE t_proxy_work_order SET status = 'VOID', updated_at = #{at} "
            + "WHERE id = #{id} AND status = 'OPEN'")
    int markVoid(@Param("id") Long id, @Param("at") LocalDateTime at);

    /** 某个目标商品上仍可用的工单（排查「为什么代操作被拒」时看这个） */
    @Select("SELECT * FROM t_proxy_work_order WHERE target_type = #{targetType} "
            + "AND target_ref = #{targetRef} AND status = 'OPEN' ORDER BY created_at DESC")
    List<ProxyWorkOrderEntity> openOnTarget(@Param("targetType") String targetType,
                                           @Param("targetRef") String targetRef);

    /** 某租户的工单（按时间倒序），运营查「这家店申请过什么」 */
    @Select("SELECT * FROM t_proxy_work_order WHERE tenant_id = #{tenantId} "
            + "ORDER BY created_at DESC LIMIT #{limit}")
    List<ProxyWorkOrderEntity> recentOfTenant(@Param("tenantId") Long tenantId,
                                             @Param("limit") int limit);
}
