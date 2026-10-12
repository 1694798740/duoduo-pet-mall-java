package com.duoduo.trade.service;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.metrics.TenantApiMetrics;
import com.duoduo.trade.client.AfterSaleStatsClient;
import com.duoduo.trade.client.UserBriefClient;
import com.duoduo.trade.client.dto.AfterSaleStatsCallDtos.TenantAfterSaleStat;
import com.duoduo.trade.client.dto.AfterSaleStatsCallDtos.TenantAfterSaleStats;
import com.duoduo.trade.client.dto.UserBriefCallDtos.UserBrief;
import com.duoduo.trade.dto.TenantHealthDtos.TenantHealthPanel;
import com.duoduo.trade.dto.TenantHealthDtos.TenantHealthPlatform;
import com.duoduo.trade.dto.TenantHealthDtos.TenantHealthRow;
import com.duoduo.trade.mapper.OrderMapper;
import com.duoduo.trade.mapper.SellerDebtMapper;
import com.duoduo.trade.mapper.SettlementMapper;
import com.duoduo.trade.support.TenantHealthJudgement;
import com.duoduo.trade.support.TenantHealthJudgement.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * E24 · 租户健康度面板：把**三个存储**里的四类事实拼成"一行一个租户"的巡检视图。
 *
 * <h2>四个维度与它们的来源</h2>
 * <pre>
 *   订单量 / 成交额   交易库 t_order                              GROUP BY seller_id
 *   售后率            履约库 t_after_sale（服务间内部接口）         GROUP BY seller_id
 *   结算异常          交易库 t_settlement / t_seller_debt / t_order 三类**存量**异常
 *   接口错误率        Redis duoduo:api:stat:*（网关逐日计数）      按天 HGETALL 合并
 * </pre>
 *
 * <h2>★ 时间窗的口径（这是最容易出错的地方，逐条写死）</h2>
 * <ul>
 *   <li><b>统一 UTC 自然日</b>：容器时区全为 UTC，全站时间口径一致（INV-12）。
 *       不用本地时区，也不在 SQL 里偷偷 +8 —— 那种"藏在查询里的时区假设"最难排查。</li>
 *   <li><b>窗口是"含首含尾的 N 个自然日"</b>：{@code days=7} 且今天为 10-12 时，
 *       窗口＝{@code [10-06T00:00, 10-13T00:00)}。终点取**次日零点**（开区间）——
 *       写成 {@code <= 今天 23:59:59} 会漏掉最后一秒的那笔单，
 *       而这类"少一笔"的现象永远不会有人去查。</li>
 *   <li><b>订单/售后按 {@code created_at}</b>（业务发生时刻），不是当前状态 ——
 *       与 E22 账单「本期发生额」、S3 对账的 {@code biz_date} 同一口径。</li>
 *   <li><b>结算异常与接口指标里，欠款 / 缺托管单是"存量"不带窗口</b>：
 *       一笔三个月前垫付、至今没收回的债今天仍然挂在平台账上，
 *       用窗口滤掉它会让面板显示"这家店很干净"。同理"超期托管"是
 *       以"现在"为基准回看（{@code now - 30 天} 之前仍在托管）。</li>
 * </ul>
 *
 * <h2>★ 降级策略：哪一路可以缺、哪一路不能</h2>
 * <ul>
 *   <li><b>售后 / 接口指标取不到 → 硬失败（503）</b>。它们是**判据**：
 *       少了售后率就判不出 WATCH，少了接口指标就判不出接口类风险 ——
 *       而面板照样会给出一个"正常"的结论。宁可整页报错，也不出一份会误导的绿表。
 *       这正是本项目反复强调的「'空结果'与'链路没接通'长得一样」。</li>
 *   <li><b>店铺名取不到 → 显式降级</b>，响应里 {@code nameResolved=false}。
 *       名字是展示装饰，不参与判级；缺了只是难看。</li>
 *   <li><b>没有任何一路是静默降级</b>：两种情况都在响应里有明确标记或直接报错。</li>
 * </ul>
 *
 * <h2>★ 面板里出现哪些租户</h2>
 * 六路数据的 {@code seller_id} 的**并集**：窗口内有订单、窗口内有售后、
 * 有未收回欠款、有超期托管、有缺托管单、或窗口内有接口调用的租户。
 * 从没做过生意、也没调过接口的"休眠店铺"不会出现 —— 它们不是"不健康"，
 * 而是**没有可判断的数据**，混进列表只会稀释注意力。
 * （面板里的 {@code NO_DATA} 档位指的是另一回事：**有动静但两个维度都是零**的租户，
 * 例如窗口内只调过接口、一单没成交。）
 */
@Service
public class TenantHealthService {

    private static final Logger log = LoggerFactory.getLogger(TenantHealthService.class);

    private final OrderMapper orderMapper;
    private final SettlementMapper settlementMapper;
    private final SellerDebtMapper sellerDebtMapper;
    private final AfterSaleStatsClient afterSaleStatsClient;
    private final UserBriefClient userBriefClient;
    /** 可能为 null：本地零依赖开发时不连 Redis。取不到接口指标时**硬失败**（见类注释） */
    private final StringRedisTemplate redis;

    public TenantHealthService(OrderMapper orderMapper,
                               SettlementMapper settlementMapper,
                               SellerDebtMapper sellerDebtMapper,
                               AfterSaleStatsClient afterSaleStatsClient,
                               UserBriefClient userBriefClient,
                               org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> redisProvider) {
        this.orderMapper = orderMapper;
        this.settlementMapper = settlementMapper;
        this.sellerDebtMapper = sellerDebtMapper;
        this.afterSaleStatsClient = afterSaleStatsClient;
        this.userBriefClient = userBriefClient;
        this.redis = redisProvider == null ? null : redisProvider.getIfAvailable();
    }

    /**
     * 生成面板。
     *
     * @param daysArg  窗口天数（1~{@link TenantHealthJudgement#MAX_DAYS}），null = 30
     * @param limitArg 租户行数上限（1~{@link TenantHealthJudgement#MAX_LIMIT}），null = 50
     */
    public TenantHealthPanel panel(Integer daysArg, Integer limitArg) {
        OperatorGuard.requireOperator();

        int days = daysArg == null ? TenantHealthJudgement.DEFAULT_DAYS : daysArg;
        if (days < 1 || days > TenantHealthJudgement.MAX_DAYS) {
            // 上限不是拍脑袋：接口指标的键只活 TenantApiMetrics.KEEP_DAYS 天，
            // 允许更长的窗口等于允许读到"已经过期所以看起来没有数据"的日子 —— 那会让错误率静默变小
            throw BizException.validation("窗口天数应在 1~" + TenantHealthJudgement.MAX_DAYS
                    + " 之间（收到 " + days + "）—— 上限由接口指标的键存活期决定");
        }
        int limit = limitArg == null ? TenantHealthJudgement.DEFAULT_LIMIT : limitArg;
        if (limit < 1 || limit > TenantHealthJudgement.MAX_LIMIT) {
            throw BizException.validation("租户行数上限应在 1~" + TenantHealthJudgement.MAX_LIMIT
                    + " 之间（收到 " + limit + "）");
        }

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate fromDay = today.minusDays(days - 1L);
        // 开区间终点：次日零点（见类注释，写成 <= 今日末秒会漏单）
        LocalDateTime from = fromDay.atStartOfDay();
        LocalDateTime to = today.plusDays(1).atStartOfDay();

        Map<Long, Bucket> buckets = new HashMap<>();

        // ① 订单（交易库，窗口内）
        for (Map<String, Object> row : orderMapper.tenantHealthOrders(from, to)) {
            Long sellerId = lngOf(row, "sellerId");
            if (sellerId == null) {
                continue;
            }
            Bucket b = bucket(buckets, sellerId);
            b.orderCount = orZero(lngOf(row, "cnt"));
            b.paidCount = orZero(lngOf(row, "paidCnt"));
            b.gmv = orZero(lngOf(row, "gmv"));
        }

        // ② 售后（履约库，窗口内，跨域内部接口）—— 取不到即硬失败
        for (TenantAfterSaleStat stat : fetchAfterSaleStats(from, to)) {
            Long sellerId = stat.sellerId();
            if (sellerId == null) {
                // 契约要求 sellerId 必填；缺了说明这份响应对不上契约，
                // 按硬失败处理而不是算到 0 号租户（自营）头上
                throw new BizException(ErrorCode.DEGRADED,
                        "售后统计响应缺少 sellerId，无法归属到租户；请检查履约域版本与契约是否一致");
            }
            Bucket b = bucket(buckets, sellerId);
            b.afterSaleCount = orZero(stat.total());
            b.afterSaleRefunded = orZero(stat.refunded());
            b.afterSaleOpen = orZero(stat.openCnt());
            b.afterSaleAdverse = orZero(stat.adverse());
        }

        // ③ 结算异常（交易库，三类存量）
        for (Map<String, Object> row : sellerDebtMapper.outstandingBySeller()) {
            Long sellerId = lngOf(row, "sellerId");
            if (sellerId == null) {
                continue;
            }
            Bucket b = bucket(buckets, sellerId);
            b.debtCount = orZero(lngOf(row, "cnt"));
            b.debtAmount = orZero(lngOf(row, "amount"));
        }
        for (Map<String, Object> row : settlementMapper.staleCustodyBySeller(
                LocalDateTime.now(ZoneOffset.UTC).minusDays(TenantHealthJudgement.STALE_CUSTODY_DAYS))) {
            Long sellerId = lngOf(row, "sellerId");
            if (sellerId == null) {
                continue;
            }
            Bucket b = bucket(buckets, sellerId);
            b.staleCustodyCount = orZero(lngOf(row, "cnt"));
            b.staleCustodyAmount = orZero(lngOf(row, "amount"));
        }
        for (Map<String, Object> row : orderMapper.missingSettlementBySeller()) {
            Long sellerId = lngOf(row, "sellerId");
            if (sellerId == null) {
                continue;
            }
            bucket(buckets, sellerId).missingSettlementCount = orZero(lngOf(row, "cnt"));
        }

        // ④ 接口指标（Redis，窗口内逐日合并）—— 取不到即硬失败
        mergeApiMetrics(buckets, fromDay, today);

        // ⑤ 汇总 + 判级 + 排序（在**全量**租户上做，再按 limit 截断）
        List<TenantHealthRow> all = new ArrayList<>(buckets.size());
        for (Map.Entry<Long, Bucket> e : buckets.entrySet()) {
            all.add(toRow(e.getKey(), e.getValue(), null));
        }
        all.sort(TenantHealthJudgement.DISPLAY_ORDER);

        int totalTenants = all.size();
        List<TenantHealthRow> shown = all.size() <= limit ? all : all.subList(0, limit);
        boolean truncated = totalTenants > shown.size();

        // ⑥ 店铺名（装饰性，允许显式降级）：只为**要展示的那几行**去查，而不是全量
        NameResult names = resolveNames(shown);
        if (names.resolved()) {
            List<TenantHealthRow> named = new ArrayList<>(shown.size());
            for (TenantHealthRow row : shown) {
                named.add(toRow(row.tenantId(), buckets.get(row.tenantId()), names.byId().get(row.tenantId())));
            }
            shown = named;
        }

        return new TenantHealthPanel(
                List.copyOf(shown),
                platformOf(all),
                days,
                fromDay.toString(),
                today.toString(),
                limit,
                totalTenants,
                truncated,
                names.resolved(),
                LocalDateTime.now(ZoneOffset.UTC).toInstant(ZoneOffset.UTC).toString());
    }

    // ---------------- 跨域：售后 ----------------

    /**
     * 取售后聚合。**失败即抛 503**，并把"是哪一路断了"写进 message ——
     * 运维看到 message 就知道该去看履约域，而不是去查面板自己。
     */
    private List<TenantAfterSaleStat> fetchAfterSaleStats(LocalDateTime from, LocalDateTime to) {
        try {
            ApiResponse<TenantAfterSaleStats> resp = afterSaleStatsClient.tenantStats(from.toString(), to.toString());
            if (resp == null || resp.data() == null) {
                throw new BizException(ErrorCode.DEGRADED,
                        "售后统计响应为空（履约域返回体结构异常）");
            }
            List<TenantAfterSaleStat> rows = resp.data().rows();
            return rows == null ? List.of() : rows;
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // 降级必须显式（铁律：兜底/降级不许静默）。这里**不**返回空列表 ——
            // 空列表会让这一维全部变成 0，而面板照样给出一份看起来正常的报告，
            // 那是"用错误结论代替错误提示"
            log.warn("租户健康度：售后统计取不到（履约域不可达）", ex);
            throw new BizException(ErrorCode.DEGRADED,
                    "售后统计取不到：履约域不可达或内部凭据校验未通过（" + ex.getClass().getSimpleName() + "）");
        }
    }

    // ---------------- Redis：接口指标 ----------------

    /**
     * 把窗口内每天的租户接口计数合并到桶里。
     *
     * <p>读法是**按窗口逐天算出键**（{@code duoduo:api:stat:{日}}）再 {@code HGETALL}，
     * 不做 {@code KEYS} / {@code SCAN}：一天一个键的结构本来就是为这一点设计的
     * （读一整天的全租户快照 = 一次往返，与租户数量无关）。{@code days} 天就是 {@code days} 次。
     */
    private void mergeApiMetrics(Map<Long, Bucket> buckets, LocalDate fromDay, LocalDate toDay) {
        if (redis == null) {
            throw new BizException(ErrorCode.DEGRADED,
                    "接口指标取不到：本服务未连接 Redis（duoduo:api:stat:* 是面板第四维的唯一来源）");
        }
        int badFields = 0;
        for (LocalDate day = fromDay; !day.isAfter(toDay); day = day.plusDays(1)) {
            String key = TenantApiMetrics.dayKey(day);
            Map<Object, Object> entries;
            try {
                entries = redis.opsForHash().entries(key);
            } catch (RuntimeException ex) {
                // 与售后同理：取不到就报错，不把"无数据"当成"没出错"
                log.warn("租户健康度：接口指标读取失败 key={}", key, ex);
                throw new BizException(ErrorCode.DEGRADED,
                        "接口指标取不到：Redis 读取失败（" + ex.getClass().getSimpleName() + "）");
            }
            if (entries == null || entries.isEmpty()) {
                continue;
            }
            for (Map.Entry<Object, Object> e : entries.entrySet()) {
                String field = e.getKey() == null ? null : e.getKey().toString();
                long tenantId = TenantApiMetrics.tenantOf(field);
                String facet = TenantApiMetrics.facetOf(field);
                if (tenantId < 0 || facet == null) {
                    // 形状不对的字段：记数、跳过。一条脏字段不该让整个面板 500，
                    // 但也**不能静默** —— 日志里有痕迹，排障时能查到
                    badFields++;
                    continue;
                }
                long value = parseCount(e.getValue());
                Bucket b = bucket(buckets, tenantId);
                switch (facet) {
                    case TenantApiMetrics.FIELD_TOTAL -> b.apiTotal += value;
                    case TenantApiMetrics.FIELD_SERVER_ERROR -> b.apiServerError += value;
                    case TenantApiMetrics.FIELD_CLIENT_ERROR -> b.apiClientError += value;
                    default -> badFields++;
                }
            }
        }
        if (badFields > 0) {
            log.warn("租户健康度：接口指标里有 {} 个字段形状不合法，已跳过（键结构见 TenantApiMetrics）", badFields);
        }
    }

    private static long parseCount(Object raw) {
        if (raw == null) {
            return 0L;
        }
        try {
            long v = Long.parseLong(raw.toString().trim());
            return Math.max(v, 0L);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    // ---------------- 店铺名（装饰性，显式降级） ----------------

    private record NameResult(boolean resolved, Map<Long, String> byId) {
    }

    private NameResult resolveNames(List<TenantHealthRow> shown) {
        if (shown.isEmpty()) {
            return new NameResult(true, Map.of());
        }
        String ids = shown.stream().map(r -> String.valueOf(r.tenantId()))
                .reduce((a, b) -> a + "," + b).orElse("");
        try {
            ApiResponse<List<UserBrief>> resp = userBriefClient.briefs(ids);
            if (resp == null || resp.data() == null) {
                log.warn("租户健康度：店铺名响应为空，本次按未取到处理（面板仍可用）");
                return new NameResult(false, Map.of());
            }
            Map<Long, String> byId = new LinkedHashMap<>();
            for (UserBrief brief : resp.data()) {
                if (brief != null && brief.id() != null) {
                    byId.put(brief.id(), brief.nickname());
                }
            }
            return new NameResult(true, byId);
        } catch (RuntimeException ex) {
            // 名字是装饰：明确标记"没取到"，而不是把整页打成 503（见 TenantHealthPanel#nameResolved）
            log.warn("租户健康度：店铺名取不到（用户域不可达），本次以租户 id 展示", ex);
            return new NameResult(false, Map.of());
        }
    }

    // ---------------- 组装 ----------------

    // 展示排序已移到判据层（TenantHealthJudgement.DISPLAY_ORDER）。
    // 排序是「谁最需要人看」的直接表达，与判级是同一个口径问题；放在那边它才能被单测钉住
    // —— 这里曾经把「rank 越大越靠前」写成 `comparingInt`（升序），
    //    结果「正常」排最前、「风险」沉底，而数据少时只表现为「乱序 1 处」。

    private static TenantHealthRow toRow(long tenantId, Bucket b, String name) {
        long abnormal = TenantHealthJudgement.settlementAbnormalCount(
                b.debtCount, b.staleCustodyCount, b.missingSettlementCount);
        Level level = TenantHealthJudgement.level(
                b.paidCount, abnormal, b.afterSaleCount, b.apiServerError, b.apiTotal);
        return new TenantHealthRow(
                tenantId,
                name,
                b.orderCount,
                b.paidCount,
                b.gmv,
                b.afterSaleCount,
                b.afterSaleRefunded,
                b.afterSaleOpen,
                b.afterSaleAdverse,
                TenantHealthJudgement.percentText(TenantHealthJudgement.afterSaleRate(b.afterSaleCount, b.paidCount)),
                b.debtCount,
                b.debtAmount,
                b.staleCustodyCount,
                b.staleCustodyAmount,
                b.missingSettlementCount,
                abnormal,
                b.apiTotal,
                b.apiServerError,
                b.apiClientError,
                TenantHealthJudgement.percentText(TenantHealthJudgement.apiErrorRate(b.apiServerError, b.apiTotal)),
                level.name());
    }

    /**
     * 平台汇总，在**全量**租户上算（不受 limit 影响）。
     *
     * <p>比例一律**先求和再相除**，不是把各租户的百分比取平均：
     * 后者会让一家只有 1 单的小店和一家 1 万单的大店权重相同，
     * 算出来的"平台售后率"在小店密集时能离谱到 5 倍。
     */
    private static TenantHealthPlatform platformOf(List<TenantHealthRow> all) {
        long orders = 0, gmv = 0, afterSale = 0, paid = 0, apiTotal = 0, apiError = 0, abnormal = 0, risky = 0, watch = 0;
        for (TenantHealthRow r : all) {
            orders += r.orderCount();
            gmv += r.gmv();
            afterSale += r.afterSaleCount();
            paid += r.paidCount();
            apiTotal += r.apiTotal();
            apiError += r.apiServerError();
            abnormal += r.settlementAbnormalCount();
            Level level = Level.of(r.level());
            if (level == Level.RISK) {
                risky++;
            } else if (level == Level.WATCH) {
                watch++;
            }
        }
        return new TenantHealthPlatform(all.size(), orders, gmv, afterSale,
                TenantHealthJudgement.percentText(TenantHealthJudgement.afterSaleRate(afterSale, paid)),
                abnormal,
                apiTotal,
                TenantHealthJudgement.percentText(TenantHealthJudgement.apiErrorRate(apiError, apiTotal)),
                risky, watch);
    }

    private static Bucket bucket(Map<Long, Bucket> buckets, long tenantId) {
        return buckets.computeIfAbsent(tenantId, id -> new Bucket());
    }

    /** 可变累加器（只在一次 panel() 调用内使用，不跨线程） */
    private static final class Bucket {
        long orderCount;
        long paidCount;
        long gmv;
        long afterSaleCount;
        long afterSaleRefunded;
        long afterSaleOpen;
        long afterSaleAdverse;
        long debtCount;
        long debtAmount;
        long staleCustodyCount;
        long staleCustodyAmount;
        long missingSettlementCount;
        long apiTotal;
        long apiServerError;
        long apiClientError;
    }

    // ---------------- 列名容错取数 ----------------

    /**
     * 列名大小写容错取数。
     *
     * <p>{@code @Select} 里写了驼峰别名，但不同 JDBC 驱动 / 数据库可能回传
     * {@code SELLERID} 或按原样大小写 —— 本项目已经因为这一点栽过一次
     * （{@code AdminOpsService#str}：「列名大小写容错……写死会全零」）。
     * 那种失效特别难发现：所有指标一起变 0，看起来像"这个窗口没数据"。
     */
    private static Long lngOf(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (value == null) {
            value = row.get(column.toUpperCase(Locale.ROOT));
        }
        if (value == null) {
            return null;
        }
        return value instanceof Number number ? number.longValue() : null;
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * 供冒烟脚本自证用：把"服务端认为的窗口上限"暴露成一个可断言的值。
     *
     * <p>脚本要断言"30 天以上被拒"，如果自己写死 30，那个断言在阈值被调整后
     * 只会变成一条假红（或者更糟：改宽之后仍然绿）。让服务端回答这个问题，
     * 判据才是"服务端拒绝超出上限的窗口"，而不是"客户端记得上限是多少"。
     * 与 S3 的 {@code /rules}（"一共声明了几条规则"由服务回答）同一手法。
     */
    public Map<String, Object> limits() {
        OperatorGuard.requireOperator();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("maxDays", TenantHealthJudgement.MAX_DAYS);
        out.put("defaultDays", TenantHealthJudgement.DEFAULT_DAYS);
        out.put("maxLimit", TenantHealthJudgement.MAX_LIMIT);
        out.put("defaultLimit", TenantHealthJudgement.DEFAULT_LIMIT);
        out.put("staleCustodyDays", TenantHealthJudgement.STALE_CUSTODY_DAYS);
        out.put("metricKeepDays", TenantApiMetrics.KEEP_DAYS);
        out.put("afterSaleWatchPercent", TenantHealthJudgement.AFTER_SALE_WATCH_PERCENT);
        out.put("apiErrorRiskPercent", TenantHealthJudgement.API_ERROR_RISK_PERCENT);
        out.put("apiErrorWatchPercent", TenantHealthJudgement.API_ERROR_WATCH_PERCENT);
        // ★ 样本门槛也必须回显：只给百分比阈值的话，前端无法解释「4 单里出 4 笔售后（100%）
        //   为什么没被判关注」。缺了这两个数，页面只能把规则讲一半，用户会以为面板漏报。
        out.put("afterSaleMinBase", TenantHealthJudgement.AFTER_SALE_MIN_BASE);
        out.put("apiMinBase", TenantHealthJudgement.API_MIN_BASE);
        // 判级档位声明清单（前端不再抄一份；也避免"加了档位但没人知道"）
        out.put("levels", new TreeSet<>(Level.ALL.stream().map(Enum::name).toList()));
        out.put("settlementAbnormalSources",
                List.of("debtOutstanding", "staleCustody", "missingSettlement"));
        return out;
    }
}
