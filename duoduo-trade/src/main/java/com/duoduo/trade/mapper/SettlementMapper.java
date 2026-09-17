package com.duoduo.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.trade.entity.SettlementEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 资金托管单数据访问。
 *
 * <p>放款与退款都写成 <b>CAS（compare-and-set）条件更新</b>：
 * 把「当前必须是托管中」写进 WHERE，由数据库保证「同一笔钱只能出去一次」。
 *
 * <p>为什么不用「先查状态再更新」：
 * <ul>
 *   <li>查与改之间有窗口。自动确认收货的定时任务和买家的「确认收货」请求
 *       完全可能同一秒到达，两边都查到 CUSTODY，就会各自放款一次；</li>
 *   <li>CAS 之后，第二次调用影响 0 行，调用方据此判定「已经放过了」——
 *       这正是可重入的实现方式，比加分布式锁简单且没有锁失效的风险。</li>
 * </ul>
 *
 * <p>返回 0 行有两种含义（已放款 / 已退款），调用方需要再查一次才能区分，
 * 但那一次查询只用于**生成提示文案**，不参与判断。
 */
@Mapper
public interface SettlementMapper extends BaseMapper<SettlementEntity> {

    /**
     * 放款给卖家：仅「托管中」可放。
     *
     * @return 1 = 本次放款成功，0 = 已不是托管中（已放款 / 已退回）
     */
    @Update("UPDATE t_settlement SET status = 'RELEASED', release_at = #{at}, released_at = #{at}, "
            + "updated_at = #{at} "
            + "WHERE order_id = #{orderId} AND status = 'CUSTODY'")
    int markReleased(@Param("orderId") Long orderId, @Param("at") LocalDateTime at);

    /**
     * 退回买家：仅「托管中」可退。
     *
     * <p>已放款的钱不能靠这一行代码退回 —— 钱已经打给卖家了，
     * 要退就得走「向卖家追偿」或平台垫付，那是另一条业务流程（M5-9 售后追偿），
     * 这里刻意让它返回 0 行，把不可能的操作挡在数据库层。
     *
     * @return 1 = 本次退款成功，0 = 已不是托管中
     */
    @Update("UPDATE t_settlement SET status = 'REFUNDED', updated_at = #{at} "
            + "WHERE order_id = #{orderId} AND status = 'CUSTODY'")
    int markRefunded(@Param("orderId") Long orderId, @Param("at") LocalDateTime at);
}
