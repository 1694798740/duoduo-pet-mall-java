package com.duoduo.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.audit.AuditSlaPolicy;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.product.client.MerchantRiskClient;
import com.duoduo.product.client.dto.MerchantRiskInfo;
import com.duoduo.product.client.dto.MerchantViolationResult;
import com.duoduo.product.dto.AuditSlaDtos.AuditMetrics;
import com.duoduo.product.dto.AuditSlaDtos.AuditSampleView;
import com.duoduo.product.dto.AuditSlaDtos.SampleReport;
import com.duoduo.product.entity.AuditSampleEntity;
import com.duoduo.product.entity.AuditSampleRateEntity;
import com.duoduo.product.entity.SpuEntity;
import com.duoduo.product.mapper.AuditSampleMapper;
import com.duoduo.product.mapper.AuditSampleRateMapper;
import com.duoduo.product.mapper.SpuMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 商品侧·事后抽查（E28）。
 *
 * <h2>它在整条链路里的位置</h2>
 * <pre>
 *   商家提交 → 机检 / 风险规则 → ┬─ 未命中 → 自动放行（audit_source=AUTO）
 *                                 └─ 命中   → 待审（人工）
 *   自动放行的那批 ──（每日）──→ 抽查任务（本类）──→ 运营复核 ──┬─ 通过 → 记一笔，供后续加权
 *                                                              └─ 违规 → 商品驳回下架 + 商家违规计数 +1
 * </pre>
 *
 * <h2>三条不能违反的口径</h2>
 * <ol>
 *   <li><b>抽查不改变前台可见性</b>：抽中只生成一条复核任务，商品照常在卖。
 *       若抽中就下架，等于把「自动放行」偷偷退回了「强制前置审核」，只是延迟一天执行 ——
 *       商家会看到自己的商品毫无理由地消失，然后来找客服。</li>
 *   <li><b>复核是防篡改的</b>：任务一旦复核过就不能再改（只有 PENDING 能判），
 *       否则「误判率」这个指标可以被反复刷 —— 判错了就再点一次通过，指标永远好看。</li>
 *   <li><b>抽样必须可复现</b>：见 {@link AuditSlaPolicy} 类注释（为什么不用随机数）。</li>
 * </ol>
 *
 * <h2>为什么复核接口也在这一层</h2>
 * 与 {@code SellerProductService} 把审核台方法收在自己类里同一取向：
 * 「谁生成任务、谁判任务、判完动什么」放在一处，规则不会分叉。
 */
@Service
public class AuditSampleService {

    private static final Logger log = LoggerFactory.getLogger(AuditSampleService.class);

    public static final String BIZ_SPU = "SPU";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PASSED = "PASSED";
    public static final String STATUS_REJECTED = "REJECTED";

    /** 单轮扫描上限：一天放行量异常大（比如批量导入）时，宁可分几轮也不要把一次查询拖成慢 SQL */
    private static final int BATCH_LIMIT = 500;

    /** 新商家的「前 N 件商品」必抽 */
    private static final int NEW_SELLER_FIRST_N = 3;

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    /** 指标观察窗口（天） */
    private static final int METRICS_WINDOW_DAYS = 30;

    private final AuditSampleMapper sampleMapper;
    private final AuditSampleRateMapper rateMapper;
    private final SpuMapper spuMapper;
    private final MerchantRiskClient merchantRiskClient;
    private final SellerProductService sellerProductService;
    private final SnowflakeIdGenerator idGenerator;

    public AuditSampleService(AuditSampleMapper sampleMapper,
                              AuditSampleRateMapper rateMapper,
                              SpuMapper spuMapper,
                              MerchantRiskClient merchantRiskClient,
                              SellerProductService sellerProductService,
                              SnowflakeIdGenerator idGenerator) {
        this.sampleMapper = sampleMapper;
        this.rateMapper = rateMapper;
        this.spuMapper = spuMapper;
        this.merchantRiskClient = merchantRiskClient;
        this.sellerProductService = sellerProductService;
        this.idGenerator = idGenerator;
    }

    // ==================== 一、抽样 ====================

    /** 一条候选（待抽样的商品）与其命中理由 */
    private record Candidate(SpuEntity spu, AuditSlaPolicy.Reason reason, int bucket, boolean mandatory) {
    }

    /**
     * 按某一天生成抽查任务。
     *
     * <p><b>幂等</b>：靠 {@code uk_biz_date} 唯一键。调度重跑、运营手动触发、
     * 多实例兜底，都不会给同一件商品在同一天建出第二条任务 —— 重复的那些会撞键被跳过，
     * 计入 {@code skipped} 而不是抛异常。
     *
     * @param date 抽样日期（UTC）。传参而不是取当天，是为了能补跑历史某天、也让单测可控
     */
    public SampleReport sweepOnce(LocalDate date) {
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();

        // 候选集 = 当天**自动放行**的第三方商品。
        // 为什么只抽自动放行的：人工审过的不用再抽（那已经是人看过的），
        // 自营免审的也不用抽（平台自己的货不存在"自动放行判错"这回事）。
        List<SpuEntity> released = spuMapper.selectList(new LambdaQueryWrapper<SpuEntity>()
                .eq(SpuEntity::getSupplyType, "merchant")
                .eq(SpuEntity::getAuditSource, SellerProductService.AUDIT_SOURCE_AUTO)
                .ge(SpuEntity::getAuditAt, dayStart)
                .lt(SpuEntity::getAuditAt, dayEnd)
                .orderByAsc(SpuEntity::getId)
                .last("LIMIT " + BATCH_LIMIT));
        if (released.isEmpty()) {
            log.info("抽查（{}）：当日无自动放行商品，跳过", date);
            return new SampleReport(0, 0, 0, currentRatePercent(), List.of());
        }

        int rate = currentRatePercent();
        Map<Long, MerchantRiskInfo> riskBySeller = risksOf(released);
        Set<Long> newSellerTop = newSellerTopSpuIds(released);

        List<Candidate> mandatory = new ArrayList<>();
        List<Candidate> optional = new ArrayList<>();
        for (SpuEntity spu : released) {
            MerchantRiskInfo risk = riskBySeller.get(spu.getSellerId());
            int bucket = AuditSlaPolicy.hashBucket(spu.getId(), date);
            if (newSellerTop.contains(spu.getId())) {
                mandatory.add(new Candidate(spu, AuditSlaPolicy.Reason.NEW_SELLER, bucket, true));
            } else if (risk != null && risk.recentlyResumed()) {
                mandatory.add(new Candidate(spu, AuditSlaPolicy.Reason.RESUME, bucket, true));
            } else if (bucket < (risk != null && risk.flagged()
                    ? AuditSlaPolicy.ESCALATED_RATE_PERCENT : rate)) {
                // 命中基础比例就进候选池；有这个商家有污点则用 20% 的门槛（更容易进来）。
                // 「更容易进来」而不是「必进」—— 必抽的三条是穷举出来的，
                // 「有污点」是一个连续的风险信号，用更高的概率而不是 100% 更合理。
                AuditSlaPolicy.Reason reason = risk != null && risk.flagged()
                        ? AuditSlaPolicy.Reason.ESCALATED : AuditSlaPolicy.baseReason(released.size());
                optional.add(new Candidate(spu, reason, bucket, false));
            }
        }

        // 基础抽样的「至少 / 最多」只约束 optional 那部分：必抽是铁规则，不该被上限挤掉。
        int target = AuditSlaPolicy.targetSampleCount(released.size(), rate);
        int quota = Math.max(0, target - mandatory.size());
        List<Candidate> chosen = new ArrayList<>(mandatory);
        optional.stream()
                .sorted(Comparator.comparingInt(Candidate::bucket))
                .limit(quota)
                .forEach(chosen::add);

        int created = 0;
        int skipped = 0;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (Candidate c : chosen) {
            AuditSampleEntity row = new AuditSampleEntity();
            row.setId(idGenerator.nextId());
            row.setBizType(BIZ_SPU);
            row.setBizId(c.spu().getId());
            row.setSellerId(c.spu().getSellerId() == null ? 0L : c.spu().getSellerId());
            row.setSampleDate(date);
            row.setReason(c.reason().name());
            row.setStatus(STATUS_PENDING);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            try {
                sampleMapper.insert(row);
                created++;
            } catch (DuplicateKeyException ex) {
                skipped++;      // 同一天同一对象已抽过 —— 幂等，不是错误
            }
        }
        Map<AuditSlaPolicy.Reason, Long> byReason = chosen.stream()
                .collect(Collectors.groupingBy(Candidate::reason, LinkedHashMap::new, Collectors.counting()));
        List<String> reasons = byReason.entrySet().stream()
                .map(e -> e.getKey().label() + " " + e.getValue())
                .toList();
        log.info("抽查（{}）：候选 {}、目标 {}、生成 {}、跳过 {}、比例 {}% —— {}",
                date, released.size(), target, created, skipped, rate, String.join("｜", reasons));
        return new SampleReport(released.size(), created, skipped, rate, reasons);
    }

    /** 批量取商家风险画像（按 sellerId 去重，一次调用一个商家） */
    private Map<Long, MerchantRiskInfo> risksOf(List<SpuEntity> released) {
        Map<Long, MerchantRiskInfo> map = new HashMap<>();
        for (Long sellerId : released.stream().map(SpuEntity::getSellerId)
                .filter(id -> id != null && id != 0L).distinct().toList()) {
            try {
                MerchantRiskInfo info = merchantRiskClient.of(sellerId).data();
                if (info != null) {
                    map.put(sellerId, info);
                }
            } catch (Exception ex) {
                // 查不到不等于有风险：按「无标记」处理（与 SellerProductService#sellerFlagged 同一纪律）
                log.warn("抽查：商家风险画像查询失败，按无标记处理 sellerId={} : {}", sellerId, ex.getMessage());
            }
        }
        return map;
    }

    /**
     * 各商家「按上架时间升序的前 {@value #NEW_SELLER_FIRST_N} 件商品」的 id 集合。
     *
     * <p>逐个商家查一次（N+1），而不是一条 SQL 开窗：一整天自动放行的商家通常只有个位数，
     * 而开窗 SQL 在 MyBatis-Plus 里要手写 {@code last()} 拼字符串，
     * 可读性与可测性都不划算。这个取舍写在注释里，是为了将来量真上来了
     * 能一眼看出「这里是可以优化的」，而不是被当成疏忽。
     */
    private Set<Long> newSellerTopSpuIds(List<SpuEntity> released) {
        Set<Long> ids = new HashSet<>();
        for (Long sellerId : released.stream().map(SpuEntity::getSellerId)
                .filter(id -> id != null && id != 0L).distinct().toList()) {
            List<SpuEntity> top = spuMapper.selectList(new LambdaQueryWrapper<SpuEntity>()
                    .select(SpuEntity::getId)
                    .eq(SpuEntity::getSellerId, sellerId)
                    .orderByAsc(SpuEntity::getCreatedAt)
                    .orderByAsc(SpuEntity::getId)
                    .last("LIMIT " + NEW_SELLER_FIRST_N));
            top.forEach(s -> ids.add(s.getId()));
        }
        return ids;
    }

    // ==================== 二、运营侧：队列与复核 ====================

    /**
     * 抽查队列 / 已复核列表。
     *
     * @param status PENDING（默认，待复核）/ PASSED / REJECTED / null 表示全部
     */
    public PageResult<AuditSampleView> pageSamples(String status, String keyword, int page, int pageSize) {
        OperatorGuard.requireOperator();
        int p = Math.max(1, page);
        int size = Math.max(1, Math.min(pageSize <= 0 ? DEFAULT_PAGE_SIZE : pageSize, MAX_PAGE_SIZE));
        String target = normalizeStatus(status);

        LambdaQueryWrapper<AuditSampleEntity> wrapper = new LambdaQueryWrapper<>();
        if (target != null) {
            wrapper.eq(AuditSampleEntity::getStatus, target);
        }
        // 关键字（2026-10-19）：商品标题 / 抽查单号。抽查行不存标题快照，
        // 只能按 bizId 反查商品表 —— 因此这里是一条参数化子查询，不是拼串。
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            wrapper.apply("(biz_id IN (SELECT id FROM t_spu WHERE title LIKE {0}) OR CAST(id AS CHAR) LIKE {1})",
                    like, like);
        }
        // 待复核按「先来先做」升序（24h 响应时长的守门就是它），已复核按复核时间倒序看最近的
        if (STATUS_PENDING.equals(target) || target == null) {
            wrapper.orderByAsc(AuditSampleEntity::getCreatedAt);
        } else {
            wrapper.orderByDesc(AuditSampleEntity::getReviewedAt);
        }
        wrapper.orderByDesc(AuditSampleEntity::getId);

        Page<AuditSampleEntity> result = sampleMapper.selectPage(new Page<>(p, size), wrapper);
        return PageResult.of(toViews(result.getRecords()), result.getTotal(), p, size);
    }

    /** 兼容旧调用（无关键字） */
    public PageResult<AuditSampleView> pageSamples(String status, int page, int pageSize) {
        return pageSamples(status, null, page, pageSize);
    }

    /**
     * 复核一条抽查任务：PASS（没问题）/ REJECT（判违规）。
     *
     * <p>判违规时做三件事，顺序不能换：
     * <ol>
     *   <li>先把商品驳回并强制下架 —— 这是**止血**，必须最先做；</li>
     *   <li>再上报商家违规计数（可能触发商家级停业）—— 失败不回滚第 1 步（见客户端注释）；</li>
     *   <li>最后落复核结论 —— 它是记录，不能因为前两步失败就丢掉「这次判了违规」这个事实。</li>
     * </ol>
     */
    public AuditSampleView review(Long sampleId, String action, String note) {
        LoginUser operator = UserContext.require();
        OperatorGuard.requireOperator();

        AuditSampleEntity sample = sampleId == null ? null : sampleMapper.selectById(sampleId);
        if (sample == null) {
            throw BizException.notFound("抽查任务");
        }
        if (!STATUS_PENDING.equals(sample.getStatus())) {
            // 已复核过就不能再判：否则「误判率」这个指标可以被反复点通过刷平，
            // 而它恰恰是用来判断「自动放行规则是不是太严」的唯一依据。
            throw BizException.businessRule("该抽查任务已复核完成，不能重复判定");
        }
        String act = action == null ? "" : action.trim().toUpperCase();
        String target = switch (act) {
            case "PASS" -> STATUS_PASSED;
            case "REJECT" -> STATUS_REJECTED;
            default -> throw BizException.validation("action 仅支持 PASS / REJECT");
        };
        String reason = trimToNull(note);
        if (STATUS_REJECTED.equals(target) && reason == null) {
            throw BizException.validation("判违规时必须填写原因");
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (STATUS_REJECTED.equals(target)) {
            sellerProductService.rejectBySample(sample.getBizId(), reason);
            reportViolation(sample.getSellerId(), reason);
        }
        sample.setStatus(target);
        sample.setReviewNote(reason);
        sample.setReviewedBy(operator.userId());
        sample.setReviewedAt(now);
        sample.setUpdatedAt(now);
        sampleMapper.updateById(sample);
        log.info("抽查复核 {}：商品 {} 结论={}（复核人={} 原因={}）",
                sampleId, sample.getBizId(), target, operator.userId(), reason);
        return toViews(List.of(sample)).get(0);
    }

    /** 上报一次违规（best-effort：失败不回滚已经完成的商品动作，只记日志） */
    private void reportViolation(Long sellerId, String reason) {
        if (sellerId == null || sellerId == 0L) {
            return;
        }
        try {
            MerchantViolationResult result = merchantRiskClient
                    .markViolation(sellerId, reason == null ? "事后抽查判违规" : reason).data();
            if (result != null) {
                log.info("商家 {} 违规计数 → {}（本次是否触发停业={}）",
                        sellerId, result.violationCount(), result.suspended());
            }
        } catch (Exception ex) {
            log.error("商家违规上报失败 sellerId={} —— 商品已下架，但违规计数未累加，"
                    + "达阈值的商家级停业可能延后，需人工核查", sellerId, ex);
        }
    }

    // ==================== 三、自证指标 ====================

    /** 当前抽样比例（%）；单行状态缺失时回落到基础比例 */
    public int currentRatePercent() {
        AuditSampleRateEntity row = rateMapper.selectById(AuditSampleRateEntity.SINGLETON_ID);
        if (row == null || row.getSampleRatePercent() == null) {
            return AuditSlaPolicy.BASE_RATE_PERCENT;
        }
        return row.getSampleRatePercent();
    }

    /**
     * 算指标，并在达标条件成立时**自动收紧**抽样比例。
     *
     * <p>为什么收紧与算指标放在同一个方法：它们读的是同一份数据。
     * 拆成两个入口，就会出现「指标页显示 12% 违规率、比例却还是 5%」这种
     * 谁都解释不清的状态 —— 因为没人知道该先调哪个。
     *
     * <p>为什么误判率只展示、不自动动作：附录 D 里「误判率高 → 调整白名单」这件事，
     * 需要的是「把某条规则移出白名单」这种**细粒度**动作，而系统现在只有
     * 一个全局比例旋钮。用全局旋钮去响应误判率，就等于「因为某个类目误伤多，
     * 所以全站降低抽样」—— 那是拿一个更大的错误去修一个小错误。
     * 所以这里如实把数字摆出来，让人去改规则（{@code SpuRiskJudge}）。
     */
    public AuditMetrics metrics() {
        OperatorGuard.requireOperator();
        List<AuditSlaPolicy.DayRate> daily = dailyRates(METRICS_WINDOW_DAYS);
        int current = currentRatePercent();
        int next = AuditSlaPolicy.tightenRateIfNeeded(current, daily);
        boolean tightened = next > current;
        if (tightened) {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            rateMapper.update(null, new LambdaUpdateWrapper<AuditSampleRateEntity>()
                    .eq(AuditSampleRateEntity::getId, AuditSampleRateEntity.SINGLETON_ID)
                    .set(AuditSampleRateEntity::getSampleRatePercent, next)
                    .set(AuditSampleRateEntity::getTightenedAt, now)
                    .set(AuditSampleRateEntity::getUpdatedAt, now));
            log.warn("抽中违规率连续 {} 天超过 {}%，抽查比例自动收紧：{}% -> {}%",
                    AuditSlaPolicy.TIGHTEN_DAYS, AuditSlaPolicy.TIGHTEN_VIOLATION_PERCENT, current, next);
        }
        return metricsOf(daily, tightened, next);
    }

    /** 不触发收紧、只读的指标快照（任务与日志用，故不校验运营身份） */
    public AuditMetrics metricsSnapshot() {
        List<AuditSlaPolicy.DayRate> daily = dailyRates(METRICS_WINDOW_DAYS);
        return metricsOf(daily, false, currentRatePercent());
    }

    private AuditMetrics metricsOf(List<AuditSlaPolicy.DayRate> daily, boolean tightened, int rate) {
        int sampled = daily.stream().mapToInt(AuditSlaPolicy.DayRate::sampled).sum();
        int violated = daily.stream().mapToInt(AuditSlaPolicy.DayRate::violated).sum();
        int passed = Math.max(0, sampled - violated);
        return new AuditMetrics(
                METRICS_WINDOW_DAYS,
                sampled,
                violated,
                AuditSlaPolicy.violationPercent(sampled, violated),
                passed,
                avgResponseHours(),
                AuditSlaPolicy.violationPercent(sampled, passed),
                rate,
                tightened);
    }

    /**
     * 按天统计「已复核」的抽查结果。
     *
     * <p>只算已复核的：把还在队列里的算进去，会让「今天违规率飙升」变成
     * 「今天任务积压」的同义词，误触发收紧。
     *
     * <p>按 {@code sampleDate}（抽样日）而不是 {@code reviewedAt} 分组：
     * 「今天抽的那批里有多少违规」才是这条规则想问的；
     * 按复核日分组会把「今天集中补判了前几天的任务」算成今天的违规率。
     */
    private List<AuditSlaPolicy.DayRate> dailyRates(int windowDays) {
        LocalDate from = LocalDate.now(ZoneOffset.UTC).minusDays(windowDays);
        List<AuditSampleEntity> rows = sampleMapper.selectList(new LambdaQueryWrapper<AuditSampleEntity>()
                .select(AuditSampleEntity::getSampleDate, AuditSampleEntity::getStatus)
                .eq(AuditSampleEntity::getBizType, BIZ_SPU)
                .in(AuditSampleEntity::getStatus, List.of(STATUS_PASSED, STATUS_REJECTED))
                .ge(AuditSampleEntity::getSampleDate, from));
        Map<LocalDate, int[]> byDate = new LinkedHashMap<>();
        for (AuditSampleEntity row : rows) {
            if (row.getSampleDate() == null) {
                continue;
            }
            int[] counter = byDate.computeIfAbsent(row.getSampleDate(), k -> new int[2]);
            counter[0]++;
            if (STATUS_REJECTED.equals(row.getStatus())) {
                counter[1]++;
            }
        }
        return byDate.entrySet().stream()
                .map(e -> new AuditSlaPolicy.DayRate(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();
    }

    /** 平均复核响应时长（小时）。以「任务生成 → 复核完成」计，未复核的不计入 */
    private int avgResponseHours() {
        List<AuditSampleEntity> rows = sampleMapper.selectList(new LambdaQueryWrapper<AuditSampleEntity>()
                .select(AuditSampleEntity::getCreatedAt, AuditSampleEntity::getReviewedAt)
                .eq(AuditSampleEntity::getBizType, BIZ_SPU)
                .isNotNull(AuditSampleEntity::getReviewedAt)
                .orderByDesc(AuditSampleEntity::getReviewedAt)
                .last("LIMIT 200"));
        if (rows.isEmpty()) {
            return 0;
        }
        long total = 0;
        int counted = 0;
        for (AuditSampleEntity row : rows) {
            if (row.getCreatedAt() == null || row.getReviewedAt() == null) {
                continue;
            }
            total += Math.max(0, ChronoUnit.HOURS.between(row.getCreatedAt(), row.getReviewedAt()));
            counted++;
        }
        return counted == 0 ? 0 : (int) (total / counted);
    }

    // ==================== 四、视图组装 ====================

    private List<AuditSampleView> toViews(List<AuditSampleEntity> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, String> titles = new HashMap<>();
        List<Long> spuIds = rows.stream()
                .filter(r -> BIZ_SPU.equals(r.getBizType()) && r.getBizId() != null)
                .map(AuditSampleEntity::getBizId).distinct().toList();
        if (!spuIds.isEmpty()) {
            spuMapper.selectList(new LambdaQueryWrapper<SpuEntity>()
                            .select(SpuEntity::getId, SpuEntity::getTitle)
                            .in(SpuEntity::getId, spuIds))
                    .forEach(s -> titles.put(s.getId(), s.getTitle()));
        }
        return rows.stream().map(r -> new AuditSampleView(
                String.valueOf(r.getId()),
                r.getBizType(),
                String.valueOf(r.getBizId()),
                String.valueOf(r.getSellerId()),
                r.getSampleDate() == null ? null : r.getSampleDate().toString(),
                r.getReason(),
                reasonLabel(r.getReason()),
                r.getStatus(),
                titles.get(r.getBizId()),
                r.getReviewNote(),
                r.getReviewedBy() == null ? null : String.valueOf(r.getReviewedBy()),
                isoOf(r.getReviewedAt()),
                isoOf(r.getCreatedAt()),
                responseHours(r))).toList();
    }

    private static Long responseHours(AuditSampleEntity row) {
        if (row.getCreatedAt() == null || row.getReviewedAt() == null) {
            return null;
        }
        return Math.max(0, ChronoUnit.HOURS.between(row.getCreatedAt(), row.getReviewedAt()));
    }

    private static String reasonLabel(String reason) {
        if (reason == null) {
            return "";
        }
        try {
            return AuditSlaPolicy.Reason.valueOf(reason).label();
        } catch (IllegalArgumentException ex) {
            return reason;      // 老数据里的未知值原样展示，不要因为一个枚举对不上就让整页报错
        }
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return STATUS_PENDING;      // 默认看「待复核」—— 那是运营的待办
        }
        String s = status.trim().toUpperCase();
        if ("ALL".equals(s)) {
            return null;
        }
        return switch (s) {
            case STATUS_PASSED, STATUS_REJECTED, STATUS_PENDING -> s;
            default -> throw BizException.validation("status 仅支持 PENDING / PASSED / REJECTED / ALL");
        };
    }

    private static String isoOf(LocalDateTime time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC).toString();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
