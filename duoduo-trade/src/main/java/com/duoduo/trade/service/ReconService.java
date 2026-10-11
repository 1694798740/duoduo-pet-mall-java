package com.duoduo.trade.service;

import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.common.web.sched.SchedLeaderLock;
import com.duoduo.trade.client.ProductGateway;
import com.duoduo.trade.client.dto.SkuSnapshot;
import com.duoduo.trade.entity.ReconDiffEntity;
import com.duoduo.trade.entity.ReconRunEntity;
import com.duoduo.trade.mapper.ReconDiffMapper;
import com.duoduo.trade.mapper.ReconRows;
import com.duoduo.trade.mapper.ReconRunMapper;
import com.duoduo.trade.mapper.ReconSourceMapper;
import com.duoduo.trade.service.ReconJudgement.DebtFact;
import com.duoduo.trade.service.ReconJudgement.Diff;
import com.duoduo.trade.service.ReconJudgement.FreezeFact;
import com.duoduo.trade.service.ReconJudgement.OrderFact;
import com.duoduo.trade.service.ReconJudgement.SettlementFact;
import com.duoduo.trade.service.ReconJudgement.SkuStockFact;
import com.duoduo.trade.support.ReconRule;
import com.duoduo.trade.support.ReconScope;
import com.duoduo.trade.support.ReconStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 钱与库存每日自动对账（S3）。
 *
 * <h2>这一层只做三件事</h2>
 * <ol>
 *   <li><b>取数</b>：把判据需要的事实从库里/商品域读出来（判据本身不认识数据库）；</li>
 *   <li><b>落库</b>：把结论与差异持久化，同一天同一范围<b>覆盖</b>而不是累积；</li>
 *   <li><b>提醒</b>：有差异就写 WARN、把"哪条规则、哪个对象"打进日志；没差异时不刷日志。</li>
 * </ol>
 *
 * <h2>三条写死的口径</h2>
 * <ol>
 *   <li><b>"没跑成"不许记成"全绿"</b>：商品域读不到 → {@link ReconStatus#FAILED}。
 *       报表可信是这套功能唯一的产出，把它换成"看起来干净"等于白做。</li>
 *   <li><b>校验对象数为 0 也算 FAILED</b>：0 个对象必然"没有差异"，
 *       而"真的没问题"与"什么都没看"在报表上长得一模一样。
 *       这条直接对应项目里已有的纪律「空结果与链路没接通长得一样」。</li>
 *   <li><b>同一天重跑覆盖</b>：先删旧（连同明细）再插新。累积会让同一个问题出现 N 次，
 *       而"差异变多了"会被读成"问题变严重了"。</li>
 * </ol>
 *
 * <h2>为什么放在交易域</h2>
 * 钱侧的数据全在本域（{@code t_settlement} / {@code t_order} / {@code t_seller_debt}），
 * 库存冻结台账（{@code t_stock_freeze}）也在本域；商品域的原始库存通过既有的内部
 * 批量快照接口**只读**读取。新建一个服务只为了"看起来分得干净"，代价是再复制一份读模型。
 */
@Service
public class ReconService {

    private static final Logger log = LoggerFactory.getLogger(ReconService.class);

    /** 选主锁的任务名（多实例部署时只有抢到锁的实例跑本轮） */
    private static final String TASK_NAME = "trade-recon";
    /** 锁的 TTL 必须大于单轮执行耗时，否则会出现"上一轮还没跑完、下一轮已接管" */
    private static final long LEADER_TTL_MS = 30 * 60 * 1000L;

    /** IN 子句分批：一次性塞几千个 id 会撞 SQL 长度与包大小上限 */
    private static final int IN_CHUNK = 300;
    /** 跨域批量取快照的分批（商品域接口有单次 id 数上限） */
    private static final int SKU_CHUNK = 200;
    /** 日志里最多展开几条差异明细（其余只报条数，避免刷屏） */
    private static final int LOG_DETAIL_LIMIT = 10;

    private final ReconSourceMapper sourceMapper;
    private final ReconRunMapper runMapper;
    private final ReconDiffMapper diffMapper;
    private final ProductGateway productGateway;
    private final SnowflakeIdGenerator idGenerator;
    private final SchedLeaderLock leaderLock;

    /** 回看窗口（天）。窗口之外的历史问题不会被发现 —— 明写的取舍，不是遗漏 */
    @Value("${duoduo.trade.recon.lookback-days:90}")
    private int lookbackDays;

    /** 订单已完成 → 放款的宽限小时数（确认收货与放款之间本来就有一小段延迟） */
    @Value("${duoduo.trade.recon.release-grace-hours:24}")
    private int releaseGraceHours;

    /** 冻结已过期多久算"补偿停摆"（默认 24h ≈ 远超一轮补偿周期） */
    @Value("${duoduo.trade.recon.freeze-stale-hours:24}")
    private int freezeStaleHours;

    public ReconService(ReconSourceMapper sourceMapper,
                        ReconRunMapper runMapper,
                        ReconDiffMapper diffMapper,
                        ProductGateway productGateway,
                        SnowflakeIdGenerator idGenerator,
                        SchedLeaderLock leaderLock) {
        this.sourceMapper = sourceMapper;
        this.runMapper = runMapper;
        this.diffMapper = diffMapper;
        this.productGateway = productGateway;
        this.idGenerator = idGenerator;
        this.leaderLock = leaderLock;
    }

    // ==================== 对外入口 ====================

    /**
     * 跑一轮对账并落库（同日同范围覆盖）。
     *
     * <p><b>刻意不把整个方法包在一个事务里</b>：取数阶段可能抛异常（商品域读不到），
     * 而那时我们要落的是 FAILED；若包在事务里，异常会把"落 FAILED"这件事一起回滚掉，
     * 于是当天报表上留着的仍是上一次的结论 —— 一个**看起来最新的旧结论**，
     * 比"今天没跑"更危险。
     */
    public ReconRunEntity run(ReconScope scope, LocalDate bizDate) {
        LocalDate day = bizDate == null ? LocalDate.now(ZoneOffset.UTC) : bizDate;
        LocalDateTime startedAt = LocalDateTime.now(ZoneOffset.UTC);
        long runId = idGenerator.nextId();

        List<Diff> diffs;
        int checked;
        ReconStatus status;
        String message;
        try {
            ReconOutcome outcome = scope == ReconScope.MONEY ? reconcileMoney() : reconcileStock();
            diffs = outcome.diffs();
            checked = outcome.checked();
            if (checked <= 0) {
                // ★ 0 个校验对象必然"没有差异"。这与"真的没问题"在报表上完全一样，
                //   因此必须显式区分 —— 见类注释第 2 条口径。
                status = ReconStatus.FAILED;
                message = "本轮校验对象数为 0：无法确认是「真的没问题」还是「链路没接通」";
            } else {
                status = ReconStatus.byDiff(diffs.size());
                message = summaryOf(scope, diffs, checked);
            }
        } catch (RuntimeException e) {
            log.error("对账未完成：scope={} bizDate={}", scope, day, e);
            diffs = List.of();
            checked = 0;
            status = ReconStatus.FAILED;
            message = "对账未完成：" + brief(e);
        }

        return persist(runId, scope, day, status, checked, diffs, startedAt, message);
    }

    /** 最近若干轮（后台列表） */
    public List<ReconRunEntity> recent(int limit) {
        int n = Math.max(1, Math.min(limit, 200));
        return runMapper.selectRecent(n);
    }

    /** 某轮的差异明细 */
    public List<ReconDiffEntity> diffsOf(Long runId, int limit) {
        int n = Math.max(1, Math.min(limit, 1000));
        return diffMapper.selectByRun(runId, n);
    }

    public ReconRunEntity findByDayAndScope(LocalDate bizDate, ReconScope scope) {
        return runMapper.selectByDayAndScope(bizDate, scope.name());
    }

    public ReconRunEntity findRun(Long runId) {
        return runMapper.selectRunById(runId);
    }

    /**
     * 每日自动对账。
     *
     * <p>选主（{@code SchedLeaderLock}）与订单补偿调度同一套：多实例部署时只有抢到锁的
     * 实例执行本轮。取不到锁时的默认动作是<b>放行</b>而不是跳过（见该类的注释：
     * 表不存在、库抖动时一律跳过会让任务静默停摆，而"多跑一遍"代价小得多）——
     * 对账天然可重入（同日覆盖），所以这条默认朝向是对账安全的。
     *
     * <p>两个范围各自 try/catch：库存对账依赖商品域，它失败不该让钱的对账也跟着没结果。
     */
    @Scheduled(cron = "${duoduo.trade.recon.cron:0 30 3 * * *}")
    public void scheduledDaily() {
        if (!leaderLock.tryAcquire(TASK_NAME, LEADER_TTL_MS)) {
            return;
        }
        try {
            LocalDate day = LocalDate.now(ZoneOffset.UTC);
            for (ReconScope scope : ReconScope.values()) {
                try {
                    ReconRunEntity run = run(scope, day);
                    log.info("对账完成：scope={} bizDate={} status={} 校验 {} 项 / 差异 {} 条（高危 {}）",
                            run.getScope(), run.getBizDate(), run.getStatus(),
                            run.getCheckedCount(), run.getDiffCount(), run.getHighCount());
                } catch (RuntimeException e) {
                    // 调度线程里必须吞掉异常，否则一次异常会让后续调度静默停摆
                    log.error("对账执行异常：scope={}", scope, e);
                }
            }
        } catch (RuntimeException e) {
            log.error("对账调度异常", e);
        }
    }

    // ==================== 钱 ====================

    private ReconOutcome reconcileMoney() {
        LocalDateTime from = windowStart();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        List<ReconRows.OrderRow> orders = new ArrayList<>(sourceMapper.selectOrders(from));
        List<ReconRows.SettlementRow> settlements = sourceMapper.selectSettlements(from);

        // ★ 补取"托管单指向、但不在窗口内"的订单。
        //   不补的话，一笔老订单的托管单会被判成"孤儿" —— 而那是一条假差异。
        //   代价只是一次按 id 的批量查，换来的是这条规则不会误报。
        Set<Long> known = new LinkedHashSet<>();
        for (ReconRows.OrderRow o : orders) {
            if (o.getOrderId() != null) {
                known.add(o.getOrderId());
            }
        }
        Set<Long> missing = new LinkedHashSet<>();
        for (ReconRows.SettlementRow s : settlements) {
            if (s.getOrderId() != null && !known.contains(s.getOrderId())) {
                missing.add(s.getOrderId());
            }
        }
        if (!missing.isEmpty()) {
            for (List<Long> chunk : chunk(missing, IN_CHUNK)) {
                orders.addAll(sourceMapper.selectOrdersByIds(chunk));
            }
        }

        Map<Long, ReconRows.SettlementRow> settleByOrder = new HashMap<>();
        for (ReconRows.SettlementRow s : settlements) {
            if (s.getOrderId() != null) {
                settleByOrder.put(s.getOrderId(), s);
            }
        }

        List<Diff> diffs = new ArrayList<>();
        Set<Long> orderIds = new LinkedHashSet<>();

        // ① 以订单为起点：该有托管吗？有的话两者相容吗？
        for (ReconRows.OrderRow o : orders) {
            if (o.getOrderId() == null) {
                continue;
            }
            orderIds.add(o.getOrderId());
            OrderFact order = orderFact(o);
            ReconRows.SettlementRow s = settleByOrder.get(o.getOrderId());
            ReconJudgement.addIfPresent(diffs, ReconJudgement.missingSettlement(order, s != null));
            if (s != null) {
                SettlementFact settle = settlementFact(s);
                ReconJudgement.addIfPresent(diffs, ReconJudgement.statusMismatch(order, settle));
                ReconJudgement.addIfPresent(diffs,
                        ReconJudgement.completedNotReleased(order, settle, now, releaseGraceHours));
                ReconJudgement.addIfPresent(diffs, ReconJudgement.releaseTimeMissing(settle));
                ReconJudgement.addIfPresent(diffs, ReconJudgement.amountMismatch(order, settle));
                ReconJudgement.addIfPresent(diffs, ReconJudgement.feeIllegal(settle));
            }
        }

        // ② 以托管单为起点：孤儿（订单不存在）。存在的那些已在 ① 覆盖，不重复判。
        for (ReconRows.SettlementRow s : settlements) {
            if (s.getOrderId() == null || orderIds.contains(s.getOrderId())) {
                continue;
            }
            SettlementFact settle = settlementFact(s);
            ReconJudgement.addIfPresent(diffs, ReconJudgement.orphanSettlement(settle, false));
            ReconJudgement.addIfPresent(diffs, ReconJudgement.releaseTimeMissing(settle));
            ReconJudgement.addIfPresent(diffs, ReconJudgement.feeIllegal(settle));
        }

        List<ReconRows.DebtRow> debts = sourceMapper.selectDebts(from);
        for (ReconRows.DebtRow d : debts) {
            ReconJudgement.addIfPresent(diffs,
                    ReconJudgement.debtInvalid(new DebtFact(nz(d.getOrderId()), nz(d.getAmount()), d.getStatus())));
        }

        int checked = orders.size() + settlements.size() + debts.size();
        return new ReconOutcome(diffs, checked);
    }

    // ==================== 库存 ====================

    private ReconOutcome reconcileStock() {
        LocalDateTime from = windowStart();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        List<ReconRows.FreezeRow> freezes = sourceMapper.selectFreezes(from);

        // 订单明细数量：按 (订单 × SKU) 聚合，与冻结数量逐条对照
        Set<Long> orderIds = new LinkedHashSet<>();
        for (ReconRows.FreezeRow f : freezes) {
            if (f.getOrderId() != null) {
                orderIds.add(f.getOrderId());
            }
        }
        Map<Long, Map<Long, Integer>> itemQty = new HashMap<>();
        for (List<Long> chunk : chunk(orderIds, IN_CHUNK)) {
            for (ReconRows.ItemQtyRow row : sourceMapper.selectItemQty(chunk)) {
                if (row.getOrderId() == null || row.getSkuId() == null) {
                    continue;
                }
                itemQty.computeIfAbsent(row.getOrderId(), k -> new HashMap<>())
                        .put(row.getSkuId(), (int) nz(row.getQuantity()));
            }
        }

        List<Diff> diffs = new ArrayList<>();
        for (ReconRows.FreezeRow f : freezes) {
            FreezeFact freeze = new FreezeFact(nz(f.getSkuId()), nz(f.getOrderId()),
                    f.getQuantity() == null ? 0 : f.getQuantity(), f.getStatus(), f.getExpireAt());
            ReconJudgement.addIfPresent(diffs, ReconJudgement.freezeLeak(freeze, f.getOrderStatus()));
            ReconJudgement.addIfPresent(diffs, ReconJudgement.freezeStale(freeze, now, freezeStaleHours));
            Integer qty = itemQty.getOrDefault(freeze.orderId(), Map.of()).get(freeze.skuId());
            ReconJudgement.addIfPresent(diffs, ReconJudgement.freezeQtyMismatch(freeze, qty));
        }

        // ---- 跨域：交易域冻结台账 与 商品域 frozen_stock ----
        Map<Long, Long> tradeFrozen = new HashMap<>();
        for (ReconRows.SkuFrozenRow row : sourceMapper.selectTradeFrozenBySku()) {
            if (row.getSkuId() != null) {
                tradeFrozen.put(row.getSkuId(), nz(row.getQuantity()));
            }
        }
        // ★ 候选集 = 交易域有冻结的 ∪ 商品域认为有冻结/库存异常的。
        //   只用前者的话，「交易域少记了冻结」这个方向永远测不到：
        //   商品域明明有冻结、交易域一条记录都没有时，那个 SKU 根本不会进入比对集。
        Set<Long> candidates = new LinkedHashSet<>(tradeFrozen.keySet());
        candidates.addAll(productGateway.frozenHolderSkuIds());

        Map<Long, SkuSnapshot> snapshots = new HashMap<>();
        for (List<Long> chunk : chunk(candidates, SKU_CHUNK)) {
            // 严格语义：读不到就抛（整轮记 FAILED），绝不把"读不到"当成"商品域没有这个 SKU"
            snapshots.putAll(productGateway.loadSnapshotsStrict(chunk));
        }
        for (Long skuId : candidates) {
            SkuSnapshot snap = snapshots.get(skuId);
            SkuStockFact fact = snap == null ? null
                    : new SkuStockFact(skuId, snap.stock(), snap.frozenStock());
            ReconJudgement.addIfPresent(diffs,
                    ReconJudgement.crossDomainDrift(skuId, tradeFrozen.getOrDefault(skuId, 0L), fact));
            if (fact != null) {
                ReconJudgement.addIfPresent(diffs, ReconJudgement.stockNegative(fact));
            }
        }

        int checked = freezes.size() + candidates.size();
        return new ReconOutcome(diffs, checked);
    }

    // ==================== 落库 ====================

    /**
     * 落库：先删该日该范围的历史轮次与明细，再插本轮。
     *
     * <p>{@code diff_count} / {@code high_count} **不是**用判据算出来的条数写的，
     * 而是插完明细后从 {@code t_recon_diff} 里数出来的（{@code syncCountsFromDiffs}）——
     * 唯一键（{@code run_id, rule, ref_type, ref_id}）有可能吃掉一条重复项，
     * 若报表显示的数字来自判据而不是来自表，就会出现「显示 5 条、点进去只有 4 条」。
     * 随后 {@code syncStatusFromCounts} 再按表里的实际条数定状态，
     * 保证 status 与明细永远自洽。
     */
    private ReconRunEntity persist(long runId, ReconScope scope, LocalDate day, ReconStatus status,
                                   int checked, List<Diff> diffs, LocalDateTime startedAt, String message) {
        diffMapper.deleteByRunOfDay(day, scope.name());     // 必须先删明细（子查询依赖 run 还在）
        runMapper.deleteByDayAndScope(day, scope.name());

        ReconRunEntity run = new ReconRunEntity();
        run.setId(runId);
        run.setBizDate(day);
        run.setScope(scope.name());
        run.setStatus(status.name());
        run.setCheckedCount(checked);
        run.setDiffCount(0);
        run.setHighCount(0);
        run.setStartedAt(startedAt);
        run.setFinishedAt(LocalDateTime.now(ZoneOffset.UTC));
        run.setMessage(message);
        runMapper.insert(run);

        for (Diff d : diffs) {
            ReconDiffEntity row = new ReconDiffEntity();
            row.setId(idGenerator.nextId());
            row.setRunId(runId);
            row.setRule(d.rule().name());
            row.setSeverity(d.severity());
            row.setRefType(d.refType());
            row.setRefId(d.refId());
            row.setExpected(trim(d.expected(), 60));
            row.setActual(trim(d.actual(), 60));
            row.setDetail(trim(d.detail(), 300));
            row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
            diffMapper.insert(row);
        }

        int stored = diffMapper.countByRun(runId);
        if (stored != diffs.size()) {
            // 不是致命错误（唯一键去重是设计的一部分），但必须留下痕迹：
            // 判据说 N 条、表里只有 M 条，意味着判据自身产出了重复项。
            log.warn("对账明细数不一致：判定 {} 条 / 落库 {} 条（runId={}，重复项被唯一键去重）",
                    diffs.size(), stored, runId);
        }
        runMapper.syncCountsFromDiffs(runId);
        if (status != ReconStatus.FAILED) {
            // 状态按表里的实际条数定，保证 status 与明细永远自洽；
            // FAILED（没跑成）刻意保留，不按条数改判 —— 0 条差异的 FAILED 与 CLEAN 必须分得开
            runMapper.syncStatusFromCounts(runId);
        }

        ReconRunEntity saved = runMapper.selectRunById(runId);
        alert(saved, stored, diffs);
        return saved;
    }

    /**
     * 提醒：有差异就把"哪条规则、哪个对象、应该是什么、实际是什么"打进 WARN 日志。
     *
     * <p>没有差异时**不打印**——每日一条"全部正常"的日志会让真正的那一条淹没在噪声里。
     *
     * <p>边界（明写）：本期不引入外部告警通道（短信 / 邮件 / 企微），
     * 提醒的可达形态是 ① WARN 日志（可被既有日志采集规则抓走）②后台「对账」页可查
     * ③ 差异明细里带 severity，高危单独计数。不引外部通道的理由与全项目一致：
     * 不为了一个告警多引入一个需要运维的中间件。
     */
    private void alert(ReconRunEntity run, int stored, List<Diff> diffs) {
        if (run == null) {
            return;
        }
        if (ReconStatus.FAILED.name().equals(run.getStatus())) {
            log.warn("★ 对账未完成（不可视为干净）：scope={} bizDate={} message={}",
                    run.getScope(), run.getBizDate(), run.getMessage());
            return;
        }
        if (stored <= 0) {
            return;
        }
        log.warn("★ 对账发现 {} 条差异（高危 {}）：scope={} bizDate={} runId={}",
                stored, run.getHighCount(), run.getScope(), run.getBizDate(), run.getId());
        diffs.stream()
                .sorted(Comparator.comparing((Diff d) -> d.severity()).thenComparing(d -> d.rule().name()))
                .limit(LOG_DETAIL_LIMIT)
                .forEach(d -> log.warn("  [{}] {} {}#{} 应为「{}」实为「{}」—— {}",
                        d.severity(), d.rule().name(), d.refType(), d.refId(),
                        d.expected(), d.actual(), d.detail()));
        if (diffs.size() > LOG_DETAIL_LIMIT) {
            log.warn("  （其余 {} 条见后台对账页 / t_recon_diff）", diffs.size() - LOG_DETAIL_LIMIT);
        }
    }

    private String summaryOf(ReconScope scope, List<Diff> diffs, int checked) {
        if (diffs.isEmpty()) {
            return "校验 " + checked + " 项，未发现差异";
        }
        long high = diffs.stream().filter(d -> "HIGH".equals(d.severity())).count();
        return "校验 " + checked + " 项，发现 " + diffs.size() + " 条差异（高危 " + high + "）";
    }

    // ==================== 小工具 ====================

    private LocalDateTime windowStart() {
        int days = lookbackDays <= 0 ? 90 : lookbackDays;
        return LocalDate.now(ZoneOffset.UTC).minusDays(days).atStartOfDay();
    }

    private static OrderFact orderFact(ReconRows.OrderRow o) {
        return new OrderFact(nz(o.getOrderId()), o.getStatus(), nz(o.getSellerId()),
                nz(o.getPayAmount()), o.getCompletedAt());
    }

    private static SettlementFact settlementFact(ReconRows.SettlementRow s) {
        return new SettlementFact(nz(s.getOrderId()), nz(s.getSellerId()), s.getStatus(),
                nz(s.getAmount()), nz(s.getPlatformFee()), s.getReleasedAt() != null);
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String brief(RuntimeException e) {
        String msg = e.getMessage();
        String text = e.getClass().getSimpleName() + (msg == null ? "" : "：" + msg);
        return trim(text, 400);
    }

    /** 把集合按固定大小切块（IN 子句与跨域批量接口都不能一次吃太多） */
    private static List<List<Long>> chunk(Collection<Long> ids, int size) {
        List<List<Long>> out = new ArrayList<>();
        List<Long> current = new ArrayList<>(size);
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            current.add(id);
            if (current.size() >= size) {
                out.add(current);
                current = new ArrayList<>(size);
            }
        }
        if (!current.isEmpty()) {
            out.add(current);
        }
        return out;
    }

    /** 一轮的结果：差异 + 校验对象数（后者用来把"没看"与"看了没问题"分开） */
    private record ReconOutcome(List<Diff> diffs, int checked) {
    }

    /** 规则清单（供后台/脚本核对"声明了几条、跑起来覆盖几条"） */
    public List<ReconRule> rulesOf(ReconScope scope) {
        return ReconRule.ofScope(scope);
    }
}
