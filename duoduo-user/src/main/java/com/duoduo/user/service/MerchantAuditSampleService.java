package com.duoduo.user.service;

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
import com.duoduo.user.dto.MerchantAuditSlaDtos.AuditMetrics;
import com.duoduo.user.dto.MerchantAuditSlaDtos.AuditSampleView;
import com.duoduo.user.dto.MerchantAuditSlaDtos.SampleReport;
import com.duoduo.user.entity.AuditSampleEntity;
import com.duoduo.user.entity.AuditSampleRateEntity;
import com.duoduo.user.entity.MerchantEntity;
import com.duoduo.user.mapper.AuditSampleMapper;
import com.duoduo.user.mapper.AuditSampleRateMapper;
import com.duoduo.user.mapper.MerchantMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 入驻侧·事后抽查（E28）。
 *
 * <h2>与商品侧的三处差异（都是刻意的）</h2>
 * <ol>
 *   <li><b>没有「必抽 100%」那三条</b>：新商家前 3 件**商品**、恢复营业 7 天内发布的**商品** ——
 *       这两条的对象都是商品。入驻侧唯一可比的是「新店铺」，而新店铺**全部都**是刚放行的，
 *       给它设「必抽」等于全量必查，等于把 SLA 又退回人工前置审核。
 *       所以入驻侧只有基础比例 + 「当日不足 3 家全查」。</li>
 *   <li><b>判定结果直接停业</b>：商品违规可以攒次数（一件商品出格，未必说明整店有问题），
 *       入驻资质造假是**主体资格**问题 —— 执照是假的，它家所有商品的可信度都归零。</li>
 *   <li><b>比例独立维护</b>：两边的放行量级差着数量级，共用一个比例会让其中一边过松或过紧。</li>
 * </ol>
 *
 * <h2>「放行时间」用 reviewed_at 而不是新加一列</h2>
 * 自动放行会写 {@code reviewed_at}（时间）+ {@code reviewed_by = null}（没有人类审核人）
 * 这一对组合。再加一个 {@code audit_at} 列只会让两列的语义永远需要对表 ——
 * 而 {@code reviewed_at} 本来就是「这家店最后一次被审核/放行是什么时候」，正是这里要的东西。
 */
@Service
public class MerchantAuditSampleService {

    private static final Logger log = LoggerFactory.getLogger(MerchantAuditSampleService.class);

    public static final String BIZ_MERCHANT = "MERCHANT";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PASSED = "PASSED";
    public static final String STATUS_REJECTED = "REJECTED";

    private static final int BATCH_LIMIT = 200;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int METRICS_WINDOW_DAYS = 30;

    private final AuditSampleMapper sampleMapper;
    private final AuditSampleRateMapper rateMapper;
    private final MerchantMapper merchantMapper;
    private final MerchantViolationService violationService;
    private final SnowflakeIdGenerator idGenerator;

    public MerchantAuditSampleService(AuditSampleMapper sampleMapper,
                                      AuditSampleRateMapper rateMapper,
                                      MerchantMapper merchantMapper,
                                      MerchantViolationService violationService,
                                      SnowflakeIdGenerator idGenerator) {
        this.sampleMapper = sampleMapper;
        this.rateMapper = rateMapper;
        this.merchantMapper = merchantMapper;
        this.violationService = violationService;
        this.idGenerator = idGenerator;
    }

    // ==================== 一、抽样 ====================

    /**
     * 按某一天生成入驻抽查任务（幂等：靠 {@code uk_biz_date}）。
     *
     * <p>候选集 = 当天**自动放行**的店铺（{@code audit_source='AUTO'} 且放行时间落在当天）。
     * 人工审过的不抽 —— 那已经是人看过的。
     */
    public SampleReport sweepOnce(LocalDate date) {
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();

        List<MerchantEntity> released = merchantMapper.selectList(new LambdaQueryWrapper<MerchantEntity>()
                .eq(MerchantEntity::getAuditSource, MerchantOnboardingService.AUDIT_SOURCE_AUTO)
                .isNotNull(MerchantEntity::getReviewedAt)
                .ge(MerchantEntity::getReviewedAt, dayStart)
                .lt(MerchantEntity::getReviewedAt, dayEnd)
                .orderByAsc(MerchantEntity::getId)
                .last("LIMIT " + BATCH_LIMIT));
        if (released.isEmpty()) {
            log.info("入驻抽查（{}）：当日无自动放行店铺，跳过", date);
            return new SampleReport(0, 0, 0, currentRatePercent());
        }

        int rate = currentRatePercent();
        AuditSlaPolicy.Reason baseReason = AuditSlaPolicy.baseReason(released.size());
        int target = AuditSlaPolicy.targetSampleCount(released.size(), rate);

        // 与商品侧同一套取样：算桶号 → 命中比例者入池 → 按桶号升序取前 N。
        // 用同一套哈希（AuditSlaPolicy）而不是各写一份，是为了「比例提高时集合是包含关系」
        // 这条性质在两个域都成立 —— 否则收紧前后抽到的是两批互不相干的人。
        List<MerchantEntity> picked = released.stream()
                .map(m -> Map.entry(m, AuditSlaPolicy.hashBucket(m.getId(), date)))
                .filter(e -> e.getValue() < rate)
                .sorted(Comparator.comparingInt(Map.Entry::getValue))
                .limit(target)
                .map(Map.Entry::getKey)
                .toList();

        int created = 0;
        int skipped = 0;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (MerchantEntity m : picked) {
            AuditSampleEntity row = new AuditSampleEntity();
            row.setId(idGenerator.nextId());
            row.setBizType(BIZ_MERCHANT);
            row.setBizId(m.getId());
            row.setSellerId(m.getOwnerUserId() == null ? 0L : m.getOwnerUserId());
            row.setSampleDate(date);
            row.setReason(baseReason.name());
            row.setStatus(STATUS_PENDING);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            try {
                sampleMapper.insert(row);
                created++;
            } catch (DuplicateKeyException ex) {
                skipped++;
            }
        }
        log.info("入驻抽查（{}）：候选 {}、目标 {}、生成 {}、跳过 {}、比例 {}%",
                date, released.size(), target, created, skipped, rate);
        return new SampleReport(released.size(), created, skipped, rate);
    }

    // ==================== 二、运营侧：队列与复核 ====================

    /**
     * 入驻抽查列表。
     *
     * @param keyword 店铺名 / 抽查单号（包含匹配）。抽查行不存店名快照，
     *                只能按 bizId 反查 t_merchant —— 参数化子查询，不拼串。
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
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            wrapper.apply("(biz_id IN (SELECT id FROM t_merchant WHERE name LIKE {0}) OR CAST(id AS CHAR) LIKE {1})",
                    like, like);
        }
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
     * 复核一条入驻抽查任务。判违规时：**直接停业 + 联动下架**（不攒次数，理由见类注释）。
     */
    public AuditSampleView review(Long sampleId, String action, String note) {
        LoginUser operator = UserContext.require();
        OperatorGuard.requireOperator();

        AuditSampleEntity sample = sampleId == null ? null : sampleMapper.selectById(sampleId);
        if (sample == null) {
            throw BizException.notFound("抽查任务");
        }
        if (!STATUS_PENDING.equals(sample.getStatus())) {
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
            violationService.suspendByAudit(sample.getSellerId(), reason);
        }
        sample.setStatus(target);
        sample.setReviewNote(reason);
        sample.setReviewedBy(operator.userId());
        sample.setReviewedAt(now);
        sample.setUpdatedAt(now);
        sampleMapper.updateById(sample);
        log.info("入驻抽查复核 {}：店铺 {} 结论={}（复核人={} 原因={}）",
                sampleId, sample.getBizId(), target, operator.userId(), reason);
        return toViews(List.of(sample)).get(0);
    }

    // ==================== 三、自证指标 ====================

    public int currentRatePercent() {
        AuditSampleRateEntity row = rateMapper.selectById(AuditSampleRateEntity.SINGLETON_ID);
        if (row == null || row.getSampleRatePercent() == null) {
            return AuditSlaPolicy.BASE_RATE_PERCENT;
        }
        return row.getSampleRatePercent();
    }

    /** 算指标并（必要时）自动收紧。口径与商品侧一致，见 {@code AuditSampleService#metrics} 的注释 */
    public AuditMetrics metrics() {
        OperatorGuard.requireOperator();
        List<AuditSlaPolicy.DayRate> daily = dailyRates();
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
            log.warn("入驻抽查违规率连续 {} 天超过 {}%，比例自动收紧：{}% -> {}%",
                    AuditSlaPolicy.TIGHTEN_DAYS, AuditSlaPolicy.TIGHTEN_VIOLATION_PERCENT, current, next);
        }
        return metricsOf(daily, tightened, next);
    }

    public AuditMetrics metricsSnapshot() {
        return metricsOf(dailyRates(), false, currentRatePercent());
    }

    private AuditMetrics metricsOf(List<AuditSlaPolicy.DayRate> daily, boolean tightened, int rate) {
        int sampled = daily.stream().mapToInt(AuditSlaPolicy.DayRate::sampled).sum();
        int violated = daily.stream().mapToInt(AuditSlaPolicy.DayRate::violated).sum();
        int passed = Math.max(0, sampled - violated);
        return new AuditMetrics(METRICS_WINDOW_DAYS, sampled, violated,
                AuditSlaPolicy.violationPercent(sampled, violated), passed, avgResponseHours(),
                AuditSlaPolicy.violationPercent(sampled, passed), rate, tightened);
    }

    private List<AuditSlaPolicy.DayRate> dailyRates() {
        LocalDate from = LocalDate.now(ZoneOffset.UTC).minusDays(METRICS_WINDOW_DAYS);
        List<AuditSampleEntity> rows = sampleMapper.selectList(new LambdaQueryWrapper<AuditSampleEntity>()
                .select(AuditSampleEntity::getSampleDate, AuditSampleEntity::getStatus)
                .eq(AuditSampleEntity::getBizType, BIZ_MERCHANT)
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

    private int avgResponseHours() {
        List<AuditSampleEntity> rows = sampleMapper.selectList(new LambdaQueryWrapper<AuditSampleEntity>()
                .select(AuditSampleEntity::getCreatedAt, AuditSampleEntity::getReviewedAt)
                .eq(AuditSampleEntity::getBizType, BIZ_MERCHANT)
                .isNotNull(AuditSampleEntity::getReviewedAt)
                .orderByDesc(AuditSampleEntity::getReviewedAt)
                .last("LIMIT 200"));
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
        Map<Long, String> names = new HashMap<>();
        List<Long> ids = new ArrayList<>();
        rows.forEach(r -> ids.add(r.getBizId()));
        if (!ids.isEmpty()) {
            merchantMapper.selectList(new LambdaQueryWrapper<MerchantEntity>()
                            .select(MerchantEntity::getId, MerchantEntity::getName)
                            .in(MerchantEntity::getId, ids))
                    .forEach(m -> names.put(m.getId(), m.getName()));
        }
        return rows.stream().map(r -> new AuditSampleView(
                String.valueOf(r.getId()),
                r.getBizType(),
                String.valueOf(r.getBizId()),
                r.getSampleDate() == null ? null : r.getSampleDate().toString(),
                r.getReason(),
                reasonLabel(r.getReason()),
                r.getStatus(),
                names.get(r.getBizId()),
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
            return reason;
        }
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return STATUS_PENDING;
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
