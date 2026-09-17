package com.duoduo.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duoduo.trade.entity.SettlementEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

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

    /**
     * 按托管状态汇总某（几）个卖家的资金，供商家端对账用。
     *
     * <p>三个聚合量刻意一次查回：金额、佣金、笔数。
     * 「可得 = 金额 − 佣金」留给服务层算，因为佣金口径（含四舍五入）属于领域规则，
     * 写进 SQL 就等于把规则藏进了一句很难被测试到的字符串里。
     *
     * @return 形如 {@code [{status: "CUSTODY", cnt: 3, amount_sum: 5900, fee_sum: 59}, ...]}
     */
    @Select("<script>"
            + "SELECT status, COUNT(*) AS cnt, "
            + "COALESCE(SUM(amount), 0) AS amount_sum, "
            + "COALESCE(SUM(platform_fee), 0) AS fee_sum "
            + "FROM t_settlement "
            + "WHERE seller_id IN "
            + "<foreach collection='sellerIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "GROUP BY status"
            + "</script>")
    List<Map<String, Object>> aggregateBySellers(@Param("sellerIds") Collection<Long> sellerIds);

    /** 全平台托管资金汇总（运营对账），与 {@link #aggregateBySellers} 同为聚合但不过滤卖家 */
    @Select("SELECT status, COUNT(*) AS cnt, "
            + "COALESCE(SUM(amount), 0) AS amount_sum, "
            + "COALESCE(SUM(platform_fee), 0) AS fee_sum "
            + "FROM t_settlement GROUP BY status")
    List<Map<String, Object>> aggregateAll();
}
