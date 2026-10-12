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
    /**
     * E22：{@code release_at} 与 {@code released_at} 从此**不再相等** ——
     * <ul>
     *   <li>{@code released_at} = **记账时刻**：钱已划到卖家名下，是"事实发生"的时间；</li>
     *   <li>{@code release_at}  = **可提现时刻** = 记账时刻 + 售后期（默认 7 天，T+7）。</li>
     * </ul>
     * 两个时刻分开存的意义：前者属于资金轨迹，后者回答"什么时候能取"。
     * 合成一个字段就表达不了"钱已经是他的、但还不能取"这个中间状态 ——
     * 而那正是"售后期冻结"要的东西。
     */
    @Update("UPDATE t_settlement SET status = 'RELEASED', release_at = #{releaseAt}, released_at = #{at}, "
            + "updated_at = #{at} "
            + "WHERE order_id = #{orderId} AND status = 'CUSTODY'")
    int markReleased(@Param("orderId") Long orderId,
                     @Param("at") LocalDateTime at,
                     @Param("releaseAt") LocalDateTime releaseAt);

    /**
     * E22 · 卖家钱包聚合：一次查询算清四项口径 + 下次解冻时间。
     *
     * <p>为什么用 SQL 聚合而不是把单子拉进内存再分类：这是**钱包页与账单页的主查询**，
     * 单量会随时间线性增长（一个卖家跑一年就是几千条托管单），
     * 而钱包恰恰是打开最频繁的页面之一。聚合下推给数据库只返回一行。
     *
     * <p><b>四项口径的定义（改动时四处要一起改，否则总和会对不上）</b>：
     * <ul>
     *   <li>{@code pendingAmount}（待结算）= {@code CUSTODY}：钱还在平台，尚未放款；</li>
     *   <li>{@code frozenAmount}（冻结中）= {@code RELEASED/ADVANCED} 且未到 {@code release_at}：
     *       钱已记在卖家名下，但还在售后期内，**取不出去**；</li>
     *   <li>{@code withdrawableAmount}（可提现）= {@code RELEASED} 且已到 {@code release_at}。
     *       <b>只算 RELEASED，不算 ADVANCED</b> —— ADVANCED 是"平台已垫付给买家"，
     *       那笔钱卖家并没拿到，且反向形成了一笔欠款（{@code t_seller_debt}），
     *       把它算作可提现会出现"越退款越有钱"的错账；</li>
     *   <li>{@code releasedAmount}（累计到账）= 两者之和，用于展示"一共赚了多少"。</li>
     * </ul>
     *
     * <p>金额一律取 **{@code amount - platform_fee}**（卖家可得），而不是 {@code amount}：
     * 佣金是平台的收入，从来不属于卖家 —— 若这里算的是 {@code amount}，
     * 卖家看到的余额会比实际能提的多出佣金那一块，而提现时又对不上。
     *
     * <p>{@code release_at IS NULL} 归入"冻结中"：它只可能出现在历史数据里
     * （E22 之前放款的单据没有这个时间）。判为冻结而不是可提现，
     * 是因为**失败方向要指向"先别让他取"** —— 真要放行，补一次数据即可；
     * 反之若误判成可提现、钱被取走，就追不回来了。
     */
    @Select("""
            <script>
            SELECT
              COALESCE(SUM(CASE WHEN status = 'CUSTODY' THEN amount - platform_fee ELSE 0 END), 0) AS pendingAmount,
              COALESCE(SUM(CASE WHEN status IN ('RELEASED','ADVANCED')
                                 AND (release_at IS NULL OR release_at &gt; #{now})
                                THEN amount - platform_fee ELSE 0 END), 0) AS frozenAmount,
              COALESCE(SUM(CASE WHEN status = 'RELEASED'
                                 AND release_at IS NOT NULL AND release_at &lt;= #{now}
                                THEN amount - platform_fee ELSE 0 END), 0) AS withdrawableAmount,
              COALESCE(SUM(CASE WHEN status IN ('RELEASED','ADVANCED')
                                THEN amount - platform_fee ELSE 0 END), 0) AS releasedAmount,
              MIN(CASE WHEN status IN ('RELEASED','ADVANCED')
                        AND release_at IS NOT NULL AND release_at &gt; #{now}
                       THEN release_at END) AS nextReleaseAt
            FROM t_settlement
            WHERE seller_id = #{sellerId}
            </script>
            """)
    Map<String, Object> walletAggregate(@Param("sellerId") Long sellerId,
                                        @Param("now") LocalDateTime now);

    /**
     * E22 · 结算账单的**本期发生额**聚合（租户 × 周期）。
     *
     * <h2>为什么每一段都按"事件时间"过滤，而不是按单据当前状态</h2>
     * 账单要回答的是"这个周期里发生了什么"，而不是"现在长什么样"。
     * 一笔 10 月创建、11 月放款的订单，必须落在 10 月的"本期入账"里，
     * 同时出现在 11 月的"本期放款"里 —— 两个周期各记一次，记账方向不同。
     * 若改用"当前状态"过滤，同一笔钱会全部挤在它最后变成的那个状态上，
     * 于是任何一期的账单都和当期实际业务量对不上，且越往前的期错得越离谱。
     *
     * <h2>每个时间列的语义（改口径时逐条对照）</h2>
     * <ul>
     *   <li>{@code created_at} —— 钱进托管（买家付款）的时刻；</li>
     *   <li>{@code released_at} —— **记账**放款给卖家的时刻（保留原值，
     *       即使这单后来被垫付成 {@code ADVANCED}，它当初确实放过款）；</li>
     *   <li>{@code release_at} —— 售后期结束、转为**可提现**的时刻（T+N 到期日）；</li>
     *   <li>{@code updated_at} —— 退回买家 / 平台垫付 这两条路径**没有独立时间列**，
     *       只能用 {@code updated_at} 近似。这是已知的口径妥协：
     *       它只在这两种终态下被写入，且写入后不会再变，所以对"本期是否发生"是准确的；
     *       但若将来需要"退款发生的确切时刻"，应当补一个 {@code refunded_at} 列。</li>
     * </ul>
     *
     * <p>全部走 {@code CASE WHEN} 一趟扫表返回一行：账单是财务页面，
     * 一个租户跑一年就是几千条托管单，分组查四次不如聚合查一次 ——
     * 后者还保证了四个口径来自**同一个快照**，不会出现"查完退款、放款数又变了"。
     */
    @Select("""
            <script>
            SELECT
              COALESCE(SUM(CASE WHEN created_at &gt;= #{start} AND created_at &lt; #{end}
                                THEN amount ELSE 0 END), 0) AS orderAmount,
              COALESCE(SUM(CASE WHEN created_at &gt;= #{start} AND created_at &lt; #{end}
                                THEN platform_fee ELSE 0 END), 0) AS platformFeeAmount,
              COALESCE(SUM(CASE WHEN created_at &gt;= #{start} AND created_at &lt; #{end}
                                THEN amount - platform_fee ELSE 0 END), 0) AS sellerEarningAmount,
              COALESCE(SUM(CASE WHEN created_at &gt;= #{start} AND created_at &lt; #{end}
                                THEN 1 ELSE 0 END), 0) AS orderCount,
              COALESCE(SUM(CASE WHEN released_at &gt;= #{start} AND released_at &lt; #{end}
                                THEN amount ELSE 0 END), 0) AS releasedAmount,
              COALESCE(SUM(CASE WHEN released_at &gt;= #{start} AND released_at &lt; #{end}
                                THEN amount - platform_fee ELSE 0 END), 0) AS releasedSellerAmount,
              COALESCE(SUM(CASE WHEN released_at &gt;= #{start} AND released_at &lt; #{end}
                                THEN 1 ELSE 0 END), 0) AS releasedCount,
              COALESCE(SUM(CASE WHEN status = 'RELEASED'
                                 AND release_at &gt;= #{start} AND release_at &lt; #{end}
                                THEN amount - platform_fee ELSE 0 END), 0) AS unfrozenAmount,
              COALESCE(SUM(CASE WHEN status = 'RELEASED'
                                 AND release_at &gt;= #{start} AND release_at &lt; #{end}
                                THEN 1 ELSE 0 END), 0) AS unfrozenCount,
              COALESCE(SUM(CASE WHEN status = 'REFUNDED'
                                 AND updated_at &gt;= #{start} AND updated_at &lt; #{end}
                                THEN amount ELSE 0 END), 0) AS refundedAmount,
              COALESCE(SUM(CASE WHEN status = 'REFUNDED'
                                 AND updated_at &gt;= #{start} AND updated_at &lt; #{end}
                                THEN 1 ELSE 0 END), 0) AS refundedCount,
              COALESCE(SUM(CASE WHEN status = 'ADVANCED'
                                 AND updated_at &gt;= #{start} AND updated_at &lt; #{end}
                                THEN amount ELSE 0 END), 0) AS advancedAmount,
              COALESCE(SUM(CASE WHEN status = 'ADVANCED'
                                 AND updated_at &gt;= #{start} AND updated_at &lt; #{end}
                                THEN 1 ELSE 0 END), 0) AS advancedCount
            FROM t_settlement
            WHERE seller_id = #{sellerId}
            </script>
            """)
    Map<String, Object> billFlowAggregate(@Param("sellerId") Long sellerId,
                                          @Param("start") LocalDateTime start,
                                          @Param("end") LocalDateTime end);

    /**
     * 平台垫付（第五十八轮 · B-01）：仅「已放款给卖家」的托管单可垫付。
     *
     * <p>为什么前置状态是 RELEASED 而不是 CUSTODY：钱已经打给卖家是<b>既成事实</b>，
     * 改不回去。垫付是平台另出一笔钱给买家，不是把原来那笔钱撤回。
     * 若这里放宽到 CUSTODY，就会出现「钱还在平台上、却记了一笔卖家欠款」的错账 ——
     * 卖家被凭空记了一笔他没拿到的钱。
     *
     * @return 1 = 本次垫付成功，0 = 不是已放款状态（未放款 / 已退款 / 已垫付）
     */
    @Update("UPDATE t_settlement SET status = 'ADVANCED', updated_at = #{at} "
            + "WHERE order_id = #{orderId} AND status = 'RELEASED'")
    int markAdvanced(@Param("orderId") Long orderId, @Param("at") LocalDateTime at);

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

    /**
     * E24 · 每个租户「超期仍托管」的笔数与金额（按卖家分组）。
     *
     * <h2>为什么「托管很久」本身就是异常</h2>
     * 正常链路里 {@code CUSTODY} 只存在于「买家已付款、尚未确认收货」这段：
     * 买家确认收货 → 放款；发货后自动确认收货的定时任务也会兜住（见 {@code OrderService}）。
     * 也就是说，**一笔托管单正常不可能长期停在 {@code CUSTODY}**。
     * 停住意味着补偿调度没跑、或放款时抛异常被吞掉了 —— 买家早已确认收货、
     * 卖家却拿不到钱，而系统里没有任何地方报错。这正是健康度面板要抓的东西。
     *
     * <h2>为什么阈值取 30 天而不是 7 天</h2>
     * 这个数字是**判据的一部分**，写死在这里（而不是让调用方随便传）会让"为什么是 30"
     * 变成一个可以回答的问题：7 天是售后期冻结的口径（E22 的 T+7），
     * 而冻结发生在放款**之后** —— 拿它当托管期的阈值，会把"买家刚付款一周还没收货"
     * 这种完全正常的单判成异常。30 天给了「自动确认收货 + 售后 + 退款」整条链路
     * 足够宽的余量：真超过 30 天还在托管，不可能是流程还在走。
     *
     * <p>阈值由调用方以 {@code before} 传入（{@code now - 30 天}），
     * 而不是在 SQL 里写 {@code NOW() - INTERVAL 30 DAY}：容器时区 / 会话时区
     * 一旦不一致，SQL 里的 {@code NOW()} 与 Java 侧的 {@code LocalDateTime.now(UTC)}
     * 会差 8 小时，而这种差异在判据上表现为"边界那几笔时有时无"。
     * 全站时间只有一个来源：Java 侧的 UTC。
     */
    @Select("SELECT seller_id AS sellerId, COUNT(*) AS cnt, "
            + "COALESCE(SUM(amount), 0) AS amount "
            + "FROM t_settlement "
            // ★ 同上：无 <script> 的注解 SQL 里必须写字面量 <，写 &lt; 会原样进库并让 JSqlParser 解析失败。
            + "WHERE status = 'CUSTODY' AND created_at < #{before} "
            + "GROUP BY seller_id")
    List<Map<String, Object>> staleCustodyBySeller(@Param("before") LocalDateTime before);
}
