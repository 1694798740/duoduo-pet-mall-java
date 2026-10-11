package com.duoduo.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.trade.entity.ProxyWorkOrderEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 代运营工单数据访问（E23 · 交易域）。
 *
 * <p><b>消费走 CAS，不走「先查后改」</b>：{@link #consume} 的 {@code WHERE status = 'OPEN'}
 * 是唯一的并发屏障。竞争下两方都读到 OPEN、都执行动作，就是**一个工单做了两次操作** ——
 * 而这类错误在单机测试里永远复现不出来，只会在真实并发里偶发，
 * 并且表现为「同一笔订单被发两次货」这种业务事故，而不是一条报错。
 *
 * <p>{@code consume} 返回 0 行有两种可能：工单不存在、或已被别人消费。
 * 调用方**必须**把这两种都当成「这份授权不可用」处理，不许把 0 当成「无所谓」放过去。
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

    /** 某个目标对象上仍可用的工单（排查「为什么代操作被拒」时看这个） */
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
