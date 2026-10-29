package com.duoduo.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.trade.dto.SettlementDtos.SellerWalletView;
import com.duoduo.trade.dto.SettlementDtos.SettlementBill;
import com.duoduo.trade.dto.SettlementDtos.SettlementView;
import com.duoduo.trade.entity.SettlementEntity;
import com.duoduo.trade.mapper.SellerDebtMapper;
import com.duoduo.trade.mapper.SettlementMapper;
import com.duoduo.trade.support.SettlementStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 资金托管服务：C2C 担保交易的钱路径。
 *
 * <p>三个动作，各自的幂等依据都不同，必须说清楚：
 * <ol>
 *   <li><b>创建托管</b>：靠唯一索引 {@code uk_settle_order}。
 *       支付回调会重复投递，重复创建只可能有一条落库，撞车方读取已存在那条即可；</li>
 *   <li><b>放款</b>：靠 CAS（{@code status = 'CUSTODY'} 写进 WHERE）。
 *       「确认收货」与「自动确认收货」可能同时发生，只有一个能改成功；</li>
 *   <li><b>退款</b>：同样靠 CAS。已放款的钱不在这里退 ——
 *       钱已经到卖家手里，须走追偿/平台垫付，那是另一条流程，这里刻意让它改不动。</li>
 * </ol>
 *
 * <p><b>刻意不做的事</b>：本服务不修改任何余额、不调用渠道退款接口。
 * 它只维护「这笔钱现在归谁」的账本状态。真正的资金出账
 * （微信/支付宝退款、卖家提现打款）属于渠道对接层，见计划中的支付渠道接入。
 * 这样拆分的好处是：账本逻辑可以脱离渠道独立测试与对账，
 * 而渠道替换（沙箱 → 生产、加一个新渠道）不会碰到账本代码。
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final SettlementMapper settlementMapper;
    private final SnowflakeIdGenerator idGenerator;
    /**
     * E22 · 欠款查询（用于"可提现 = 已解冻 − 未结欠款"）。
     *
     * <p>★ 刻意注入 **Mapper** 而不是 {@code SellerDebtService}：后者反过来依赖本类
     * （它垫付时要调 {@code advance}），若这里再依赖它就成了**循环依赖**。
     * 除开"启动即报错"这个还算友好的失败形式，更实际的理由是 ——
     * 本类只需要"某卖家欠了多少"这一个聚合值，不值得把两个服务的生命周期绑在一起。
     */
    private final SellerDebtMapper sellerDebtMapper;

    /**
     * 平台佣金费率，单位「基点」（1 bp = 万分之一）。
     *
     * <p>E22 起默认 **600 bp = 6%**（此前是 100 bp = 1%）。可通过
     * {@code duoduo.trade.platform-fee-bps} 覆盖，配 0 即全平台免佣。
     *
     * <p>费率做成配置而不是常量：它是**商业口径**，会随运营策略调整；
     * 而历史单据必须保留"结算当刻"的费率（见 {@code SettlementEntity.platformFee}），
     * 所以调整费率只影响之后的订单，不会把已结算的账重算一遍。
     */
    @Value("${duoduo.trade.platform-fee-bps:600}")
    private int platformFeeBps;

    /**
     * E22 · 售后期（天）：放款后资金要等这么久才可提现（T+N 里的 N）。
     *
     * <p>为什么必须有这段冻结：订单"确认收货"只是买家点了按钮，而售后退款窗口还在。
     * 若确认收货即可提现，卖家可以在售后期内把钱提走、再拒绝退款 ——
     * 平台的垫付（B-01）就从"偶发风险"变成"可被系统性利用的漏洞"。
     * 冻结期与售后期取同一个值，是因为两者本就该一致：**冻结的意义就是盖住退款窗口**。
     */
    @Value("${duoduo.trade.settlement-hold-days:7}")
    private int settlementHoldDays;

    public SettlementService(SettlementMapper settlementMapper,
                             SellerDebtMapper sellerDebtMapper,
                             SnowflakeIdGenerator idGenerator) {
        this.settlementMapper = settlementMapper;
        this.sellerDebtMapper = sellerDebtMapper;
        this.idGenerator = idGenerator;
    }

    /**
     * 创建托管单（支付成功时调用）。
     *
     * <p>幂等：先查一次走快路径；并发下由 {@code uk_settle_order} 兜底，
     * 撞车方读取已存在的那条返回 —— 不抛异常，因为「已经建过了」对调用方而言就是成功。
     *
     * <p>R-P1 · {@code amount} = 买家实付现金 + 币抵扣 = 券后商品金额，
     * {@code coinSubsidy} 是其中由平台补贴的部分 —— 商家按券后商品金额收钱，
     * 不因买家用币少收一分；补贴单独成列，让「平台补贴合计 = 订单币抵扣合计」
     * 成为一条可以直接 SUM 出来的对账。
     */
    @Transactional
    public SettlementEntity openCustody(Long orderId, Long sellerId, long amount, long coinSubsidy,
                                        LocalDateTime now) {
        SettlementEntity existing = findByOrderId(orderId);
        if (existing != null) {
            return existing;
        }
        SettlementEntity entity = new SettlementEntity();
        entity.setId(idGenerator.nextId());
        entity.setOrderId(orderId);
        entity.setSellerId(sellerId);
        entity.setAmount(amount);
        entity.setCoinSubsidyAmount(Math.max(0L, coinSubsidy));
        entity.setPlatformFee(platformFeeOf(sellerId, amount));
        entity.setStatus(SettlementEntity.STATUS_CUSTODY);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            settlementMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            SettlementEntity winner = findByOrderId(orderId);
            if (winner != null) {
                return winner;
            }
            throw e;
        }
        log.info("订单 {} 资金已进入平台托管：金额 {} 分，平台佣金 {} 分，卖家可得 {} 分",
                orderId, amount, entity.getPlatformFee(), amount - entity.getPlatformFee());
        return entity;
    }

    /**
     * 计算平台佣金。
     *
     * <p>用「加半个单位再整除」实现四舍五入到「分」：
     * 直接整除会把每笔都少收一点点，单笔看不出来，放大到百万笔就是可观的差额，
     * 而且对账时差异会均匀散布在所有订单上，几乎不可能定位。
     *
     * <p>金额用 {@code long} 参与乘法，避免 {@code int} 溢出：
     * 一笔 2 万元（2_000_000 分）的订单乘以 100 就已经接近 int 上限的量级，
     * 电商大额订单（家电、整箱宠粮）完全可能触发。
     */
    public long platformFeeOf(Long sellerId, long amount) {
        if (amount <= 0 || platformFeeBps <= 0 || isPlatformSelf(sellerId)) {
            return 0L;
        }
        return (amount * platformFeeBps + 5000L) / 10000L;
    }

    /**
     * 是否平台自营（{@code seller_id = 0}）。
     *
     * <p>E22 · 自营**不抽佣**：自营是平台自己卖货，佣金从左手交到右手 ——
     * 既没有真实收入，又会让「平台佣金」这个指标虚增，于是
     * "第三方到底贡献了多少佣金"这个真正要回答的问题失去答案。
     * 判据只认 {@code seller_id = 0}，与 {@code TenantContext} 里
     * "0 是有效租户 = 自营"是同一个口径。
     */
    private static boolean isPlatformSelf(Long sellerId) {
        return sellerId != null && sellerId == 0L;
    }

    /** E22 · 当前生效的售后期（天）。暴露出来是为了让视图与测试断言都能引用同一个来源 */
    public int settlementHoldDays() {
        return settlementHoldDays;
    }

    /**
     * E22 · 卖家钱包：待结算 / 冻结中 / 可提现 / 累计到账 / 未结欠款。
     *
     * <p><b>这里是"卖家能取多少钱"的唯一定义</b>。平台侧的账单、商家端的钱包页、
     * 将来的提现接口，都必须走这一个方法 —— 各算一遍的后果是三个页面给出三个数，
     * 而用户只会相信最小的那个，于是前两个就成了"系统在骗人"。
     *
     * <p>两个容易写错的地方，逐条说明：
     * <ol>
     *   <li><b>可提现只算 {@code RELEASED}，不含 {@code ADVANCED}</b>。
     *       ADVANCED 的语义是"平台已经垫付给买家"，那笔钱卖家根本没拿到，
     *       同时还欠了平台一笔（见 {@code t_seller_debt}）。把它算进可提现
     *       就会出现"越被退款、余额越多"的错账。</li>
     *   <li><b>欠款要抵扣，且结果不小于 0</b>。欠款大于余额时，卖家是净欠平台钱；
     *       这时"可提现"应该是 0 而不是负数 —— 负数在页面上的呈现（"-120 元可提现"）
     *       会被理解成"平台欠我"，与事实正好相反。</li>
     * </ol>
     *
     * <p>金额一律是"卖家可得"（{@code amount − platform_fee}），不含佣金。
     */
    public SellerWalletView walletOf(Long sellerId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Map<String, Object> row = settlementMapper.walletAggregate(sellerId, now);
        long pending = longOf(row, "pendingAmount");
        long frozen = longOf(row, "frozenAmount");
        long withdrawable = longOf(row, "withdrawableAmount");
        long released = longOf(row, "releasedAmount");

        Long debtRaw = sellerDebtMapper.sumOutstanding(sellerId);
        long debt = debtRaw == null ? 0L : debtRaw;

        return new SellerWalletView(
                pending,
                frozen,
                withdrawable,
                released,
                debt,
                Math.max(0L, withdrawable - debt),
                isoOfColumn(row.get("nextReleaseAt")),
                settlementHoldDays);
    }

    private static long longOf(Map<String, Object> row, String key) {
        Object raw = row == null ? null : row.get(key);
        if (raw instanceof Number n) {
            return n.longValue();
        }
        // 列名大小写容错：MySQL 回传的是别名原样，但换驱动/换库（H2、PG）
        // 可能全大写，届时四个口径会静默变成 0 —— 账单全零比报错更难被发现。
        raw = row == null ? null : row.get(key.toUpperCase());
        return raw instanceof Number n2 ? n2.longValue() : 0L;
    }

    /**
     * E22 · 结算账单（租户 × 周期）。
     *
     * <p><b>双端同源</b>：商家端 {@code GET /api/seller/settlements/bills} 与
     * 平台端 {@code GET /api/admin/settlements/bills} 都调这一个方法。
     * 两边各算一遍是本项目明令禁止的模式（E20「判定点只有一个」的同一条纪律）：
     * 分叉的后果是"商家看到的账单"和"平台看到的账单"对不上，而这种事情一旦发生，
     * 处理成本是无限的 —— 谁都拿不出一个可以让对方信服的数。
     *
     * @param sellerId 租户键 = 店铺主体 userId
     * @param period   {@code yyyy-MM}（整月）或 {@code yyyy-MM-dd}（单日）；留空 = 当前月
     */
    public SettlementBill billOf(Long sellerId, String period) {
        LocalDate[] range = parsePeriod(period);
        LocalDate start = range[0];
        LocalDate end = range[1]; // 左闭右开
        LocalDateTime startAt = start.atStartOfDay();
        LocalDateTime endAt = end.atStartOfDay();

        Map<String, Object> flow = settlementMapper.billFlowAggregate(sellerId, startAt, endAt);

        // 快照段刻意复用钱包口径（walletAggregate），而不是另写一套"期末回溯"：
        // 期末回溯需要按 updated_at 反推历史状态，等于养出第二套口径。
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Map<String, Object> stock = settlementMapper.walletAggregate(sellerId, now);
        Long debtRaw = sellerDebtMapper.sumOutstanding(sellerId);

        return new SettlementBill(
                periodOf(start, end),
                startAt.toInstant(ZoneOffset.UTC).toString(),
                endAt.toInstant(ZoneOffset.UTC).toString(),
                sellerId,
                longOf(flow, "orderAmount"),
                longOf(flow, "platformFeeAmount"),
                longOf(flow, "sellerEarningAmount"),
                longOf(flow, "orderCount"),
                longOf(flow, "releasedAmount"),
                longOf(flow, "releasedSellerAmount"),
                longOf(flow, "releasedCount"),
                longOf(flow, "unfrozenAmount"),
                longOf(flow, "unfrozenCount"),
                longOf(flow, "refundedAmount"),
                longOf(flow, "refundedCount"),
                longOf(flow, "advancedAmount"),
                longOf(flow, "advancedCount"),
                longOf(stock, "pendingAmount"),
                longOf(stock, "frozenAmount"),
                longOf(stock, "withdrawableAmount"),
                debtRaw == null ? 0L : debtRaw,
                now.toInstant(ZoneOffset.UTC).toString());
    }

    /**
     * 解析周期串 → {@code [起点, 终点)}（左闭右开）。
     *
     * <p>只认两种形状，**不做模糊匹配**：
     * <ul>
     *   <li>{@code yyyy-MM} → 该自然月，如 {@code 2026-10} → [10-01, 11-01)；</li>
     *   <li>{@code yyyy-MM-dd} → 该自然日，如 {@code 2026-10-05} → [10-05, 10-06)。</li>
     * </ul>
     * 为什么不接受"上个月""最近30天"这类相对表达：账单要被归档、要被复核，
     * 一个写着"最近30天"的账单在三个月后打开时已经不是一个确定的区间了。
     *
     * <p>格式非法一律 {@code 400}（校验错误），不静默回落到当前月 ——
     * 回落会让"我查的是 9 月、系统给我看的是 10 月"变成一个不报错的错答案。
     */
    private static LocalDate[] parsePeriod(String period) {
        if (period == null || period.isBlank()) {
            LocalDate first = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
            return new LocalDate[]{first, first.plusMonths(1)};
        }
        String text = period.trim();
        try {
            if (text.matches("\\d{4}-\\d{2}")) {
                LocalDate first = YearMonth.parse(text).atDay(1);
                return new LocalDate[]{first, first.plusMonths(1)};
            }
            if (text.matches("\\d{4}-\\d{2}-\\d{2}")) {
                LocalDate day = LocalDate.parse(text);
                return new LocalDate[]{day, day.plusDays(1)};
            }
        } catch (DateTimeParseException e) {
            // 形如 2026-13（月份越界）会在解析时抛，统一归到下面的 400
            throw BizException.validation("周期格式不正确：" + text + "，应为 yyyy-MM 或 yyyy-MM-dd");
        }
        throw BizException.validation("周期格式不正确：" + text + "，应为 yyyy-MM 或 yyyy-MM-dd");
    }

    /** 由区间反推规范化的周期串（月周期给 {@code yyyy-MM}，日周期给 {@code yyyy-MM-dd}） */
    private static String periodOf(LocalDate start, LocalDate end) {
        if (start.plusMonths(1).equals(end) && start.getDayOfMonth() == 1) {
            return YearMonth.from(start).toString();
        }
        return start.toString();
    }

    /**
     * 聚合结果里的 {@code release_at} 经 MyBatis 映射后是 {@link java.sql.Timestamp}，
     * 而视图层统一用"UTC 的 ISO 串"表达时间（与 {@link #toView} 一致）。
     * 直接 {@code toString()} 会输出 {@code 2026-10-18 12:00:00.0} 这种带空格的形态 ——
     * 前端 `new Date(...)` 在部分浏览器上会解析失败，而失败后是"Invalid Date"这种
     * 界面上很难归因的表现。所以在这里转一次。
     */
    private static String isoOfColumn(Object raw) {
        if (raw instanceof java.sql.Timestamp ts) {
            return isoTs(ts.toLocalDateTime());
        }
        if (raw instanceof LocalDateTime ldt) {
            return isoTs(ldt);
        }
        return null;
    }

    /**
     * 当前生效的佣金费率（基点）。
     *
     * <p>暴露给回补服务用：它必须把「用了什么费率」写进审计信息里，
     * 而费率只应有一个事实来源 —— 让它自己再 {@code @Value} 一遍，
     * 就会出现「换个配置项名字、一边改了另一边没改」的静默不一致。
     */
    public int platformFeeBps() {
        return platformFeeBps;
    }

    /**
     * 放款给卖家（确认收货 / 自动确认收货时调用）。
     *
     * @return true = 本次真的放款了；false = 已经不是托管中（已放款或已退款）
     */
    public boolean release(Long orderId, LocalDateTime now) {
        // E22：放款即记账，但**可提现时刻**推迟到 now + 售后期（T+N）。
        //   released_at = now（钱已划到卖家名下）
        //   release_at  = now + N（什么时候能取）
        // 两者不再相等 —— 这正是"售后期冻结"的落地方式。
        return settlementMapper.markReleased(orderId, now, now.plusDays(settlementHoldDays)) == 1;
    }

    /**
     * 退回买家（退款时调用）。
     *
     * @return true = 本次真的退款了；false = 不是托管中（已放款或已退款）
     */
    public boolean refund(Long orderId, LocalDateTime now) {
        return settlementMapper.markRefunded(orderId, now) == 1;
    }

    /**
     * 平台垫付（第五十八轮 · B-01）：把托管单从「已放款给卖家」置为「平台垫付」。
     *
     * <p>语义：钱已打给卖家是既成事实，平台另出一笔钱退给买家，
     * 因此托管单记 {@code ADVANCED}（不是把 RELEASED 撤回），同时在 {@code t_seller_debt}
     * 记一笔卖家欠款。资金轨迹可完整回溯：{@code CUSTODY → RELEASED → ADVANCED}。
     *
     * <p>CAS 保证「同一笔钱只垫付一次」：重复调用影响 0 行，返回 false。
     *
     * @return true = 本次真的垫付了；false = 不是「已放款」状态（未放款 / 已退款 / 已垫付）
     */
    public boolean advance(Long orderId, LocalDateTime now) {
        return settlementMapper.markAdvanced(orderId, now) == 1;
    }

    public SettlementEntity findByOrderId(Long orderId) {
        return settlementMapper.selectOne(new LambdaQueryWrapper<SettlementEntity>()
                .eq(SettlementEntity::getOrderId, orderId));
    }

    /** 批量取托管单，供订单列表装配视图（避免逐单查询造成 N+1） */
    public Map<Long, SettlementEntity> mapByOrderIds(Collection<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Map.of();
        }
        List<SettlementEntity> list = settlementMapper.selectList(new LambdaQueryWrapper<SettlementEntity>()
                .in(SettlementEntity::getOrderId, orderIds));
        Map<Long, SettlementEntity> map = new HashMap<>();
        for (SettlementEntity entity : list) {
            map.put(entity.getOrderId(), entity);
        }
        return map;
    }

    /** 装配视图；{@code orderNo} 由调用方传入（托管表只存 order_id，不冗余订单号） */
    public SettlementView toView(SettlementEntity entity, String orderNo) {
        if (entity == null) {
            return null;
        }
        long amount = nz(entity.getAmount());
        long fee = nz(entity.getPlatformFee());
        return new SettlementView(
                orderNo,
                entity.getSellerId(),
                amount,
                fee,
                nz(entity.getCoinSubsidyAmount()),
                amount - fee,
                entity.getStatus(),
                SettlementStatus.labelOf(entity.getStatus()),
                isoTs(entity.getReleaseAt()),
                isoTs(entity.getReleasedAt()),
                isoTs(entity.getCreatedAt()));
    }

    private static String isoTs(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC).toString();
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }
}
