package com.duoduo.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.audit.AuditAction;
import com.duoduo.common.audit.AuditLogRecord;
import com.duoduo.common.audit.AuditOutcome;
import com.duoduo.common.audit.AuditTrail;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.common.security.perm.Permission;
import com.duoduo.user.dto.MerchantDtos.ApplicationSummary;
import com.duoduo.user.dto.MerchantDtos.ApplicationView;
import com.duoduo.user.dto.MerchantDtos.MerchantLicenseInfo;
import com.duoduo.user.dto.MerchantDtos.MerchantRiskInfo;
import com.duoduo.user.dto.MerchantDtos.RejectReason;
import com.duoduo.user.dto.MerchantDtos.SaveDraftRequest;
import com.duoduo.user.entity.MerchantEntity;
import com.duoduo.user.entity.MerchantMaterialEntity;
import com.duoduo.user.entity.UserEntity;
import com.duoduo.user.mapper.MerchantMapper;
import com.duoduo.user.mapper.MerchantMaterialMapper;
import com.duoduo.user.mapper.UserMapper;
import com.duoduo.user.support.LicenseExpiry;
import com.duoduo.user.support.MerchantMaterialStorage;
import com.duoduo.user.support.MerchantMaterialType;
import com.duoduo.user.support.MerchantRiskRules;
import com.duoduo.user.support.MerchantStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 商家入驻状态机（E10）。
 *
 * <p>流转规则与幂等语义见 {@link MerchantStatus} 的类注释；本类只做三件事：
 * <b>守状态机、守材料清单、把审核结论落到租户数据上</b>（开店 = member 写 OWNER + 角色升 SELLER）。
 * 材料内容是否合格属于运营人审（E11），服务端只校验「每类都有一份」与「文件引用形状合法」。
 */
@Service
public class MerchantOnboardingService {

    private static final Logger log = LoggerFactory.getLogger(MerchantOnboardingService.class);

    /** 材料引用只接受本平台上传通道的路径 —— 外链一张随时会失效的图，审核时看不到等于白交 */
    private static final String UPLOAD_PREFIX = "/uploads/";
    /** E12 · 私有读取路径前缀（鉴权端点，不走公开 nginx） */
    private static final String MATERIAL_READ_PREFIX = "/api/merchant-materials/";
    /** E12 · 审计动作：读取入驻材料 */
    public static final AuditAction ACTION_MATERIAL_READ = AuditAction.MERCHANT_MATERIAL_READ;
    private static final int STORE_NAME_MIN = 2;
    private static final int STORE_NAME_MAX = 30;

    /**
     * 「重新开业观察期」长度（天）—— E28 的必抽规则之一：
     * 恢复营业后这段时间内发布的商品 100% 进抽查队列。
     *
     * <p>窗口长度定义在这一处，跨服务只传「在不在窗口内」这个布尔值 ——
     * 把时间戳传出去，两个服务就得各自维护同一个数字，改的时候必然漏一边。
     */
    public static final int RESUME_WINDOW_DAYS = 7;

    /** E28 · 审核来源：系统按规则自动放行（没有人类审核人） */
    public static final String AUDIT_SOURCE_AUTO = "AUTO";
    /** E28 · 审核来源：运营人工判定 */
    public static final String AUDIT_SOURCE_MANUAL = "MANUAL";

    private final MerchantMapper merchantMapper;
    private final com.duoduo.user.mapper.MerchantMemberMapper memberMapper;
    private final MerchantMaterialMapper materialMapper;
    private final UserMapper userMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final MerchantMaterialStorage materialStorage;
    private final AuditLogService auditLogService;
    private final MerchantViolationService violationService;

    public MerchantOnboardingService(MerchantMapper merchantMapper,
                                     com.duoduo.user.mapper.MerchantMemberMapper memberMapper,
                                     MerchantMaterialMapper materialMapper,
                                     UserMapper userMapper,
                                     SnowflakeIdGenerator idGenerator,
                                     ObjectMapper objectMapper,
                                     MerchantMaterialStorage materialStorage,
                                     AuditLogService auditLogService,
                                     MerchantViolationService violationService) {
        this.merchantMapper = merchantMapper;
        this.memberMapper = memberMapper;
        this.materialMapper = materialMapper;
        this.userMapper = userMapper;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.materialStorage = materialStorage;
        this.auditLogService = auditLogService;
        this.violationService = violationService;
    }

    // ---------------- 商家侧 ----------------

    /**
     * 取（或隐式创建）当前用户的入驻申请。
     *
     * <p>「看一眼」就会建草稿，是刻意的：入驻表单需要一个稳定的落点，
     * 与其让前端猜「还没有申请」，不如第一次查看时就给一个 DRAFT。
     * 并发下 uk_owner 兜底，撞键后重读即得。
     */
    @Transactional
    public ApplicationView ensureApplication(Long userId) {
        MerchantEntity entity = findByOwner(userId);
        if (entity == null) {
            entity = new MerchantEntity();
            entity.setId(idGenerator.nextId());
            entity.setOwnerUserId(userId);
            entity.setStatus(MerchantStatus.DRAFT.name());
            entity.setIsolationLevel("STANDARD");
            entity.setName("待填写-" + userId);   // uk_name 非空 —— 草稿期用占位名，提交时强制改
            entity.setCreatedAt(now());
            entity.setUpdatedAt(now());
            try {
                merchantMapper.insert(entity);
            } catch (DuplicateKeyException ex) {
                entity = findByOwner(userId);      // 并发下另一请求已建
                if (entity == null) {
                    throw ex;
                }
            }
        }
        return toView(entity);
    }

    /** 保存草稿（店名 + 材料引用）。仅 DRAFT/REJECTED 可编辑 —— 送审后材料即冻结 */
    @Transactional
    public ApplicationView saveDraft(Long userId, SaveDraftRequest request) {
        MerchantEntity entity = requireOwned(userId);
        MerchantStatus status = MerchantStatus.valueOf(entity.getStatus());
        if (!status.canEditMaterials()) {
            throw BizException.businessRule("当前状态（" + status + "）不可修改申请材料");
        }
        if (request.storeName() != null) {
            validateStoreName(request.storeName());
            entity.setName(request.storeName().trim());
        }
        entity.setUpdatedAt(now());
        try {
            merchantMapper.updateById(entity);
        } catch (DuplicateKeyException ex) {
            throw BizException.conflict("店铺名已被使用，请换一个");
        }
        if (request.materials() != null) {
            upsertMaterials(entity.getId(), userId, request.materials());
        }
        if (request.materialExpiry() != null) {
            applyMaterialExpiry(entity.getId(), request.materialExpiry());
        }
        // E15：材料一变就重算店铺级到期日（派生列的唯一写入出口）。
        // 放在最后是因为它要读「刚写进去的」那批材料。
        entity.setLicenseExpireAt(refreshLicenseExpireAt(entity.getId()));
        merchantMapper.updateById(entity);
        return toView(entity);
    }

    /**
     * E15 · 资质续期：更新临期/已过期店铺的材料与有效期，重新送审。
     *
     * <p><b>与 saveDraft 的区别不是「能改什么」而是「谁能改、改完到哪去」</b>：
     * saveDraft 只服务还没开张的草稿（DRAFT/REJECTED），续期服务的是
     * <b>已经开张</b>的店铺（APPROVED 临期主动续、SUSPENDED 过期后补救）。
     * 两者都会走完整审核 —— 续期不是「交钱即续」，而是<b>换一份新证再核一遍</b>，
     * 否则商家可以拿一张过期的糊涂图把停业状态刷回来。
     *
     * <p>提交后状态回 {@code SUBMITTED}，由运营重新走 startReview → approve。
     * 通过时 {@link #approve} 会清空提醒档位，于是下一个有效期周期从零开始计。
     */
    @Transactional
    public ApplicationView renewal(Long userId, SaveDraftRequest request) {
        MerchantEntity entity = requireOwned(userId);
        MerchantStatus status = MerchantStatus.valueOf(entity.getStatus());
        if (!status.canRenew()) {
            throw BizException.businessRule("当前状态（" + status + "）不能发起资质续期；"
                    + "审核中的申请请等待结论，草稿请直接提交");
        }
        if (request != null && request.storeName() != null) {
            validateStoreName(request.storeName());
            entity.setName(request.storeName().trim());
        }
        if (request != null && request.materials() != null) {
            upsertMaterials(entity.getId(), userId, request.materials());
        }
        if (request != null && request.materialExpiry() != null) {
            applyMaterialExpiry(entity.getId(), request.materialExpiry());
        }
        LocalDateTime earliest = refreshLicenseExpireAt(entity.getId());
        // 续期的唯一实质要求：换完之后本店确实有了一段**未来**的有效期。
        // 不满足就驳回，否则商家提交一份和原来一样（甚至更早）的证，
        // 走完一圈审核回来发现照样停业 —— 白白消耗两边的精力。
        if (earliest != null && !earliest.isAfter(now())) {
            throw BizException.businessRule("续期后的资质有效期仍在过去（"
                    + earliest.toLocalDate() + "），请上传有效期内的新证件");
        }
        entity.setStatus(MerchantStatus.SUBMITTED.name());
        entity.setAppliedAt(now());
        entity.setLicenseExpireAt(earliest);
        entity.setUpdatedAt(now());
        merchantMapper.updateById(entity);
        // 新周期：提醒从零开始计 + 已进入续期审核、停业原因不再展示。
        // 这两列的"清空"必须走 clearNullableState —— updateById 不会写 null（见方法注释）
        clearNullableState(entity, now());

        // E28：续期的自动放行条件比首次入驻**更窄** —— 必须「没有任何新东西可审」。
        // 换材料就是「换一份新证再核一遍」的本意（E15 立下的口径），必须人工看；
        // 只改有效期同样算新信息：有效期是**声明**，审核要核的正是「声明与上传的证是否一致」，
        // 若改个日期就能自动通过，商家把一张过期执照的日期填到三年后即可自动复业 —— 那是绕过。
        boolean newEvidence = request != null
                && ((request.materials() != null && !request.materials().isEmpty())
                || (request.materialExpiry() != null && !request.materialExpiry().isEmpty()));
        boolean seenByHuman = newEvidence || hasEnforcementRecord(entity, status);
        if (seenByHuman) {
            log.info("资质续期提交 merchantId={} userId={} licenseExpireAt={} → 待审"
                            + "（提交了新证据={} 历史处置记录={}）",
                    entity.getId(), userId, earliest, newEvidence,
                    hasEnforcementRecord(entity, status));
        } else {
            applyApproval(entity, null, AUDIT_SOURCE_AUTO, now());
            log.info("资质续期提交 merchantId={} userId={} licenseExpireAt={} → 自动通过（未提交任何新证据）",
                    entity.getId(), userId, earliest);
        }
        return toView(entity);
    }

    /**
     * 这家店是否**已经有历史处置记录**（在营期被判过违规，或入驻申请曾被驳回）。
     *
     * <p>有记录就不许自动放行 —— 这条与商品侧「曾被判违规的商品改完仍需人看」是同一条纪律，
     * 理由是同一件事：一次人工结论被「重新提交」冲掉，等于没有结论。
     *
     * <p>特别是「违规停业」的店：它和「资质过期停业」走的是同一个续期入口，
     * 但资质可能本来就长期有效。若不给这道门，一家因违规被关掉的店只要点一次续期
     * （甚至请求体为空）就会自动复业 —— 平台自己作出的停业处罚被绕过去了。
     *
     * <p>{@code violationCount} 只增不减、唯一清零点是复业，所以它是可靠的「有前科」指标。
     */
    private boolean hasEnforcementRecord(MerchantEntity entity, MerchantStatus fromStatus) {
        if (entity.getViolationCount() != null && entity.getViolationCount() > 0) {
            return true;
        }
        return fromStatus == MerchantStatus.REJECTED;
    }

    /**
     * 提交送审。
     *
     * <p>幂等：已是 SUBMITTED 时直接返回现状（重复点击提交、网络重试都不产生副作用）；
     * REJECTED 可重提（闭环的一半）；DRAFT 正常提交；APPROVED/SUSPENDED/REVIEWING 拒绝。
     */
    @Transactional
    public ApplicationView submit(Long userId) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.notFound("用户");
        }
        if (!user.isRealNameVerified()) {
            throw BizException.businessRule("请先完成实名认证再提交入驻申请");
        }
        MerchantEntity entity = requireOwned(userId);
        MerchantStatus status = MerchantStatus.valueOf(entity.getStatus());
        if (status == MerchantStatus.SUBMITTED) {
            return toView(entity);           // 幂等：重复提交返回现状
        }
        if (!status.canSubmit()) {
            throw BizException.businessRule("当前状态（" + status + "）不能提交审核");
        }
        Map<MerchantMaterialType, String> materials = loadMaterials(entity.getId());
        List<MerchantMaterialType> missing = Arrays.stream(MerchantMaterialType.REQUIRED)
                .filter(t -> !materials.containsKey(t))
                .toList();
        if (!missing.isEmpty()) {
            throw BizException.businessRule("材料不齐全，缺少："
                    + missing.stream().map(MerchantMaterialType::label).collect(Collectors.joining("、")));
        }
        if (entity.getName() == null || entity.getName().startsWith("待填写-")) {
            throw BizException.businessRule("请先填写店铺名称");
        }
        // E15：提交时把店铺级到期日定格一次 —— 运营审的就是这一刻的材料与有效期
        LocalDateTime earliest = refreshLicenseExpireAt(entity.getId());
        entity.setLicenseExpireAt(earliest);
        entity.setStatus(MerchantStatus.SUBMITTED.name());
        entity.setAppliedAt(now());
        entity.setUpdatedAt(now());
        merchantMapper.updateById(entity);
        // 重提时旧的驳回原因已过时（历史在审核日志）—— 这一列是"NULL 有语义"的，
        // 必须走 clearNullableState 才真的写得进去，理由见该方法的注释
        clearNullableState(entity, now());

        // E28：先跑一遍风险规则。命中任何一条转人工，全不命中则**直接通过并开店** ——
        // 「人工审核不是上线的必经环节，而是抽样复核」（方案附录 D 的一句话口径）。
        // ★ 注意这里不是「跳过审核」，而是「审核延后成抽样」：
        //   自动放行的这次会被当天的抽查任务覆盖到（见 MerchantAuditSampleService）。
        // ★ REJECTED 重提必须人工再看：驳回是运营作出的结论，「随便改一处再重提」
        //   不能把它冲掉（与商品侧的同类修复同一条纪律）。
        List<MerchantRiskRules.Hit> risks = risksOf(entity, earliest);
        boolean seenByHuman = hasEnforcementRecord(entity, status);
        if (risks.isEmpty() && !seenByHuman) {
            applyApproval(entity, null, AUDIT_SOURCE_AUTO, now());
            log.info("入驻申请提交 merchantId={} userId={} → 自动放行并开店（未命中风险规则）",
                    entity.getId(), userId);
        } else {
            log.info("入驻申请提交 merchantId={} userId={} status=SUBMITTED（命中风险：{}，历史处置记录={}）",
                    entity.getId(), userId, risks.stream().map(MerchantRiskRules.Hit::code).toList(),
                    seenByHuman);
        }
        return toView(entity);
    }

    /**
     * 跑一遍入驻侧的风险规则（E28）。
     *
     * <p>材料剩余天数按**最早到期的那张证**算：一张营业执照还有三年、
     * 但行业许可证 20 天后到期 —— 这家店 20 天后就要被停业，当然该先看一眼。
     * 取「最紧迫的那张」而不是「有没有临期的」，是因为后者判不出紧迫程度。
     */
    private List<MerchantRiskRules.Hit> risksOf(MerchantEntity entity, LocalDateTime earliestExpire) {
        Long remaining = earliestExpire == null ? null : LicenseExpiry.remainingDays(earliestExpire, now());
        return MerchantRiskRules.risks(new MerchantRiskRules.Context(entity.getName(), remaining));
    }

    // ---------------- 运营侧（E11 审核台的后端） ----------------

    /**
     * 入驻审核台列表。
     *
     * @param keyword 店铺名 / 店主手机号 / 店主姓名（包含匹配）。
     *                运营接到商家电话时手里只有「店名」或「手机号」，
     *                让他自己去几十页里翻是纯粹的浪费。
     */
    public PageResult<ApplicationSummary> adminPage(int page, int pageSize, String statusFilter, String keyword) {
        LambdaQueryWrapper<MerchantEntity> wrapper = new LambdaQueryWrapper<MerchantEntity>()
                .eq(statusFilter != null && !statusFilter.isBlank(),
                        MerchantEntity::getStatus, statusFilter);
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            wrapper.apply("(name LIKE {0} OR owner_user_id IN "
                    + "(SELECT id FROM t_user WHERE phone LIKE {1} OR real_name LIKE {1}))", like, like);
        }
        wrapper.orderByDesc(MerchantEntity::getAppliedAt)
                .orderByDesc(MerchantEntity::getCreatedAt);
        Page<MerchantEntity> p = merchantMapper.selectPage(new Page<>(page, pageSize), wrapper);
        Map<Long, String> phones = loadOwnerPhones(p.getRecords());
        LocalDateTime current = now();
        List<ApplicationSummary> items = p.getRecords().stream()
                .map(m -> toSummary(m, phones, current))
                .toList();
        return PageResult.of(items, p.getTotal(), page, pageSize);
    }

    /** 兼容旧调用（无关键字） */
    public PageResult<ApplicationSummary> adminPage(int page, int pageSize, String statusFilter) {
        return adminPage(page, pageSize, statusFilter, null);
    }

    /**
     * 入驻审核台导出（2026-10-28 · 导入导出第一批）：与 {@link #adminPage} 同筛选、同字段。
     *
     * <p>同步导出最多 5 万行；证件材料**不进导出文件**（图片与链接无法在 CSV 里安全处理，
     * 且导出文件一旦流出，等于把一批营业执照打包带走）。
     */
    public List<ApplicationSummary> exportSummaries(String statusFilter, String keyword) {
        LambdaQueryWrapper<MerchantEntity> wrapper = new LambdaQueryWrapper<MerchantEntity>()
                .eq(statusFilter != null && !statusFilter.isBlank(),
                        MerchantEntity::getStatus, statusFilter);
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            wrapper.apply("(name LIKE {0} OR owner_user_id IN "
                    + "(SELECT id FROM t_user WHERE phone LIKE {1} OR real_name LIKE {1}))", like, like);
        }
        wrapper.orderByDesc(MerchantEntity::getAppliedAt)
                .orderByDesc(MerchantEntity::getCreatedAt)
                .last("LIMIT 50000");
        List<MerchantEntity> rows = merchantMapper.selectList(wrapper);
        Map<Long, String> phones = loadOwnerPhones(rows);
        LocalDateTime current = now();
        return rows.stream().map(m -> toSummary(m, phones, current)).toList();
    }

    /** 分页与导出的共用装配：保证导出文件与页面上的每一列都同源 */
    private ApplicationSummary toSummary(MerchantEntity m, Map<Long, String> phones, LocalDateTime current) {
        return new ApplicationSummary(
                String.valueOf(m.getId()), m.getName(), m.getStatus(),
                String.valueOf(m.getOwnerUserId()),
                phones.get(m.getOwnerUserId()),
                m.getAppliedAt(), m.getReviewedAt(),
                // E15：审核台要能一眼看出「这家店资质快到期了 / 已经过期被停业」，
                // 否则运营只能靠逐个点开详情才发现 —— 而停业店铺恰恰是最需要解释原因的
                m.getLicenseExpireAt(),
                LicenseExpiry.stageOf(m.getLicenseExpireAt(), current).name(),
                m.getSuspendedReason());
    }

    public ApplicationView adminDetail(Long merchantId) {
        return toView(requireMerchant(merchantId));
    }

    /** 开始审核：SUBMITTED → REVIEWING（幂等：已 REVIEWING 返回现状） */
    @Transactional
    public ApplicationView startReview(Long merchantId, Long operatorId) {
        MerchantEntity entity = requireMerchant(merchantId);
        MerchantStatus status = MerchantStatus.valueOf(entity.getStatus());
        if (status == MerchantStatus.REVIEWING) {
            // E18 · 幂等确认不构成事件（与 E18 之前同一口径）
            AuditTrail.skip("申请已是审核中，无实际变更");
            return toView(entity);
        }
        if (status != MerchantStatus.SUBMITTED) {
            throw BizException.businessRule("只有待审核的申请才能开始审核（当前 " + status + "）");
        }
        entity.setStatus(MerchantStatus.REVIEWING.name());
        entity.setUpdatedAt(now());
        merchantMapper.updateById(entity);
        audit(entity, status, MerchantStatus.REVIEWING, "人工开始审核");
        log.info("入驻审核开始 merchantId={} operator={}", merchantId, operatorId);
        return toView(entity);
    }

    /**
     * 通过并开店：REVIEWING → APPROVED。
     *
     * <p>「开店」= member 写 OWNER（幂等）+ 库内角色升 SELLER。
     * 商家侧令牌的轮转由商家自己调 activate 完成 —— 审核通过那一刻商家可能不在线，
     * 运营不能替他重签（E8：令牌入口固定为商家侧）。
     */
    @Transactional
    public ApplicationView approve(Long merchantId, Long operatorId) {
        MerchantEntity entity = requireMerchant(merchantId);
        MerchantStatus status = MerchantStatus.valueOf(entity.getStatus());
        if (status == MerchantStatus.APPROVED) {
            // E18 · 幂等：重复通过返回现状，不构成事件
            AuditTrail.skip("申请已是已通过，无实际变更");
            return toView(entity);           // 幂等：重复通过返回现状
        }
        if (status != MerchantStatus.REVIEWING) {
            throw BizException.businessRule("只有审核中的申请可以通过（当前 " + status + "）");
        }
        applyApproval(entity, operatorId, AUDIT_SOURCE_MANUAL, now());
        audit(entity, status, MerchantStatus.APPROVED, "人工审核通过并开店");
        log.info("入驻通过并开店 merchantId={} owner={} operator={}", merchantId, entity.getOwnerUserId(), operatorId);
        return toView(entity);
    }

    /**
     * 通过并开店 —— 人工 {@code approve} 与 E28 的**自动放行**共用这一段。
     *
     * <h2>为什么必须共用，而不是让自动放行自己写一套</h2>
     * 「开店」不是一个状态位，而是三件事一起成立：状态变 APPROVED、member 写 OWNER、
     * 用户角色升 SELLER。自动放行若绕过其中任何一件，就会出现
     * 「审核通过了但商家端进不去」（角色没升）或「订单归属查不到店铺」（member 没写）——
     * 这类问题在接口层看起来是 200，只有商家真去用才暴露。
     *
     * @param operatorId  审核人；传 {@code null} 表示系统自动放行（没有人类审核人）
     * @param auditSource {@link #AUDIT_SOURCE_AUTO} / {@link #AUDIT_SOURCE_MANUAL}
     */
    @Transactional
    public void applyApproval(MerchantEntity entity, Long operatorId, String auditSource, LocalDateTime now) {
        boolean wasSuspended = entity.getSuspendedReason() != null;
        entity.setStatus(MerchantStatus.APPROVED.name());
        entity.setReviewedAt(now);
        entity.setReviewedBy(operatorId);
        entity.setAuditSource(auditSource);
        entity.setUpdatedAt(now);
        merchantMapper.updateById(entity);
        if (operatorId == null) {
            // 系统自动放行没有人类审核人。updateById 的更新策略是 NOT_NULL，**不写 null** ——
            // 不清的话，续期自动通过后 reviewed_by 会留着上一次人工审核的那个人，
            // 事后复盘「这次是谁放的」会得到一个错误答案（而且没有任何报错提示这件事）。
            merchantMapper.update(null, new LambdaUpdateWrapper<MerchantEntity>()
                    .eq(MerchantEntity::getId, entity.getId())
                    .set(MerchantEntity::getReviewedBy, null));
        }
        // E15：审核通过 = 新的有效期成立，提醒档位清空，下个周期从零开始计。
        // 首次入驻时清空本就是空操作；续期通过时这一步决定「会不会再提醒一次」。
        // 停业原因同时清掉 —— 店铺已经恢复营业，再挂着停业理由只会误导运营与商家。
        clearNullableState(entity, now());

        // E28：从停业恢复 → 违规计数清零（否则「再犯一次即停业」，
        // 那不是我们的规则 —— 3 次是累计阈值，不是「停过一次就永远一次即停」）。
        if (wasSuspended) {
            violationService.resetViolations(entity.getId());
        }

        // 开店动作（幂等：重复 approve 时 member 已存在、role 已是 SELLER，均为无副作用）
        if (findMember(entity.getId(), entity.getOwnerUserId()) == null) {
            com.duoduo.user.entity.MerchantMemberEntity member =
                    new com.duoduo.user.entity.MerchantMemberEntity();
            member.setId(idGenerator.nextId());
            member.setMerchantId(entity.getId());
            member.setUserId(entity.getOwnerUserId());
            member.setRole("OWNER");
            member.setStatus("ACTIVE");
            member.setCreatedAt(now);
            member.setUpdatedAt(now);
            try {
                memberMapper.insert(member);
            } catch (DuplicateKeyException ex) {
                // 并发 approve：member 已由另一请求写入 —— 结论一致，忽略
            }
        }
        UserEntity owner = userMapper.selectById(entity.getOwnerUserId());
        if (owner != null && !"SELLER".equals(owner.getRole())) {
            owner.setRole("SELLER");
            owner.setUpdatedAt(now);
            userMapper.updateById(owner);
        }
    }

    /** 驳回：REVIEWING → REJECTED（结构化原因必填；已驳回时允许更新原因 —— 结论一致） */
    @Transactional
    public ApplicationView reject(Long merchantId, Long operatorId, List<RejectReason> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            throw BizException.businessRule("驳回必须给出结构化原因");
        }
        for (RejectReason r : reasons) {
            if (r.scope() == null || r.scope().isBlank()
                    || r.code() == null || r.code().isBlank()
                    || r.note() == null || r.note().isBlank()) {
                throw BizException.businessRule("驳回原因的 scope/code/note 均不能为空");
            }
        }
        MerchantEntity entity = requireMerchant(merchantId);
        MerchantStatus status = MerchantStatus.valueOf(entity.getStatus());
        if (status == MerchantStatus.REJECTED) {
            // 幂等：结论一致（都是驳回），允许补全/更新原因后返回现状。
            // ★ 这一支**要留痕**：它确实写了库（原因被补全/更新），是真实变更，
            // 与「什么都没发生」的幂等分支不同 —— 抑制它是错的口径。
            entity.setRejectReason(toJson(reasons));
            entity.setUpdatedAt(now());
            merchantMapper.updateById(entity);
            audit(entity, status, MerchantStatus.REJECTED, "重复驳回，补全/更新驳回原因");
            return toView(entity);
        }
        if (status != MerchantStatus.REVIEWING) {
            throw BizException.businessRule("只有审核中的申请可以驳回（当前 " + status + "）");
        }
        entity.setStatus(MerchantStatus.REJECTED.name());
        entity.setRejectReason(toJson(reasons));
        entity.setReviewedAt(now());
        entity.setReviewedBy(operatorId);
        entity.setUpdatedAt(now());
        merchantMapper.updateById(entity);
        audit(entity, status, MerchantStatus.REJECTED, "人工驳回，原因 " + reasons.size() + " 条");
        log.info("入驻驳回 merchantId={} operator={} reasons={} 条", merchantId, operatorId, reasons.size());
        return toView(entity);
    }

    /**
     * 商家侧激活（审核通过后调用）：角色已由审核动作升为 SELLER，这里负责**重签令牌**
     * （入口固定商家侧，见 UserController.entryAfterMerchantApply —— 复用同一条路径，
     * 避免出现第二种"重签语义"）。
     */
    @Transactional
    public UserEntity activateIfApproved(Long userId) {
        MerchantEntity entity = requireOwned(userId);
        if (!MerchantStatus.APPROVED.name().equals(entity.getStatus())) {
            throw BizException.businessRule("入驻申请尚未通过审核（当前 "
                    + entity.getStatus() + "）");
        }
        return userMapper.selectById(userId);
    }

    /**
     * E12 · 上传一份资质材料（私有通道）。
     *
     * <p>文件立即落到该商家的租户目录；与申请的绑定在 saveDraft 里完成（上传与
     * 填表解耦，重传覆盖靠 uk_merchant_type 的 upsert 语义）。
     * 查看即建草稿的 {@link #ensureApplication} 在这里复用：上传需要一个落点。
     */
    public MerchantMaterialStorage.Stored storeMaterial(long userId, String originalName, byte[] content) {
        MerchantEntity entity = requireOwned(userId);
        return materialStorage.store(entity.getId(), originalName, content);
    }

    /**
     * E12 · 读取一份资质材料（鉴权代理 + 下载留痕）。
     *
     * <p>授权口径：**店主本人**或**运营 / 管理员**。其余任何登录用户 → 403 ——
     * 网关只保证"登录了"，"是不是该看这份材料"必须在服务端判断。
     * 每次成功读取写一条审计（t_audit_log）：谁、何时、看了哪家店的哪份材料 ——
     * 材料泄露的排查入口就是这张表。
     */
    public MerchantMaterialStorage.ReadResult readMaterial(Long readerId, long merchantId, String storedName) {
        MerchantEntity entity = requireMerchant(merchantId);
        com.duoduo.common.context.LoginUser reader = com.duoduo.common.context.UserContext.require();
        // E16：原先这里手拼 "OPERATOR".equals(role) || "ADMIN".equals(role)，
        // 与 OperatorGuard / LoginUser 各写一份。改问权限点，口径回到矩阵一处。
        boolean isOperator = reader.hasPerm(Permission.MERCHANT_MATERIAL_READ);
        boolean isOwner = entity.getOwnerUserId() != null && entity.getOwnerUserId().equals(readerId);
        if (!isOperator && !isOwner) {
            log.warn("越权读取入驻材料被拒 reader={} merchantId={} role={}", readerId, merchantId, reader.role());
            throw BizException.forbidden("无权查看该入驻材料");
        }
        MerchantMaterialStorage.ReadResult result = materialStorage.read(merchantId, storedName);
        try {
            // E18 · 统一走 AuditLogService。这条是**读**留痕（不是写操作），
            // 因此没有配 @Audited 注解 —— 注解的语义是「后台写操作」，
            // 挂在 GET 上会让「审计页全是读记录」这种口径漂移没人拦得住。
            auditLogService.record(new AuditLogRecord(
                    readerId, reader.phone() == null ? "" : reader.phone(),
                    ACTION_MATERIAL_READ.code(), ACTION_MATERIAL_READ.category().name(),
                    AuditOutcome.SUCCESS.name(), merchantId, ACTION_MATERIAL_READ.targetType(),
                    entity.getOwnerUserId() == null ? 0L : entity.getOwnerUserId(),
                    null, null, null, com.duoduo.common.api.RequestIdHolder.get(),
                    storedName, LocalDateTime.now(ZoneOffset.UTC).toString()));
        } catch (Exception ex) {
            // 留痕失败不阻断读取 —— 但必须吵到日志里（审计静默丢失比不审计更危险）
            log.error("材料读取留痕失败 merchantId={} storedName={} reader={}", merchantId, storedName, readerId, ex);
        }
        return result;
    }

    // ---------------- 内部 ----------------

    /**
     * E15 · 店铺资质与营业状态（内部端点下发，供商品域做上架门禁）。
     *
     * <p>按<b>店主 userId</b> 查（商品域的归属也是店主 userId，两侧同一个键）。
     * 查不到（该用户没有入驻申请）返回 {@code null} —— 调用方据此放行：
     * 门禁是纵深防御的第二道，第一道是「过期即停业 + 商品联动下架」。
     * 下游故障时把「拒绝」当成默认，会让一次 Feign 抖动变成全平台商家都无法上架。
     */
    public MerchantLicenseInfo licenseInfoOf(Long ownerUserId) {
        if (ownerUserId == null) {
            return null;
        }
        MerchantEntity entity = findByOwner(ownerUserId);
        if (entity == null) {
            return null;
        }
        return new MerchantLicenseInfo(
                String.valueOf(entity.getId()),
                entity.getStatus(),
                LicenseExpiry.stageOf(entity.getLicenseExpireAt(), now()).name(),
                entity.getLicenseExpireAt(),
                entity.getSuspendedReason());
    }

    private MerchantEntity requireOwned(Long userId) {
        MerchantEntity entity = findByOwner(userId);
        if (entity == null) {
            throw BizException.notFound("入驻申请");
        }
        return entity;
    }

    /**
     * E28 · 商家风险画像（内部端点，商品域判「自动放行还是转人工」时用）。
     *
     * <p>无入驻记录（平台自营 / 从没申请过）返回 {@code null}，调用方按「无风险标记」处理 ——
     * 与 {@link #licenseInfoOf} 同一口径：查不到不等于有问题。
     */
    public MerchantRiskInfo riskInfoOf(Long ownerUserId) {
        if (ownerUserId == null) {
            return null;
        }
        MerchantEntity entity = findByOwner(ownerUserId);
        if (entity == null) {
            return null;
        }
        return new MerchantRiskInfo(
                String.valueOf(entity.getId()),
                entity.getViolationCount() == null ? 0 : entity.getViolationCount(),
                MerchantStatus.SUSPENDED.name().equals(entity.getStatus()),
                MerchantStatus.REJECTED.name().equals(entity.getStatus()),
                inResumeWindow(entity));
    }

    /**
     * 是否处于「重新开业观察期」。
     *
     * <p>判据是「当前 APPROVED 且最近一次审核在 {@value #RESUME_WINDOW_DAYS} 天内」——
     * 这是**可观测近似**：系统没有单独记录「从 SUSPENDED 恢复」这个事件，
     * {@code reviewed_at} 同时被「首次审核通过」与「停业后恢复」写入。
     * 于是首次入驻通过的商家也会被算进观察期。这是刻意的取舍：
     * 它只会让**更多**商品进抽查队列（宁可多抽），不会放过该抽的；
     * 而为区分这两种情况单独加一张事件表，收益远小于它带来的写路径复杂度。
     */
    private boolean inResumeWindow(MerchantEntity entity) {
        if (!MerchantStatus.APPROVED.name().equals(entity.getStatus())) {
            return false;
        }
        LocalDateTime reviewedAt = entity.getReviewedAt();
        return reviewedAt != null && !reviewedAt.isBefore(now().minusDays(RESUME_WINDOW_DAYS));
    }

    /**
     * E18 · 补充入驻审核动作的审计上下文。
     *
     * <p>留痕本身由 {@code AuditInterceptor} 统一采集（谁 / 何时 / 从哪来 / 成没成），
     * 这里只补三件注解拿不到的事：<b>目标是哪家店、属于哪个租户、状态从什么变成什么</b>。
     *
     * <p>租户 = 店铺主体 userId（与 {@code t_spu.seller_id} 同一个键）——
     * 不能填 {@code merchant.id}，否则「按租户检索」会查不到：
     * 平台其它地方用的是 ownerUserId，两套 id 混用会让审计结果凭空少一半。
     */
    private void audit(MerchantEntity entity, MerchantStatus before, MerchantStatus after, String note) {
        AuditTrail.target(entity.getId(), "MERCHANT");
        AuditTrail.tenant(entity.getOwnerUserId());
        AuditTrail.change(before.name(), after.name());
        AuditTrail.note(note);
    }

    private MerchantEntity requireMerchant(Long merchantId) {
        MerchantEntity entity = merchantMapper.selectById(merchantId);
        if (entity == null) {
            throw BizException.notFound("入驻申请");
        }
        return entity;
    }

    /**
     * 营业中店铺改名（第八十一轮）。
     *
     * <p>为什么敢开这个口子（此前 APPROVED 一律冻结）：店铺名不是资质材料 ——
     * 证照、类目、有效期才是「送审的样本」；店名只要满足全局唯一 + 长度/占位校验，
     * 改名不构成「边审边换材料」。但**必须留审计**：谁在什么时候把店名从什么改成了什么，
     * before/after 都要能查 —— 店名出现在订单、售后、结算等所有交易链路上，
     * 没有留痕的改名等于给「洗身份」开后门。
     *
     * <p>只允许 APPROVED（{@code canRenameStore}）：草稿/被驳回走 saveDraft 的材料路径；
     * 送审中改名会破坏「审核结论 ↔ 材料」对应关系；停业先走续期恢复营业。
     *
     * <p>改名即时生效：购物车分组、商品卡片、结算页显示的店名都是**实时查**
     * {@code t_merchant.name}（按店主 userId），不存在需要失效的缓存或快照副本 ——
     * 这是「展示字段不落副本」纪律的直接收益。
     */
    @Transactional
    public ApplicationView renameStore(Long userId, String newName) {
        MerchantEntity entity = requireOwned(userId);
        MerchantStatus status = MerchantStatus.valueOf(entity.getStatus());
        if (!status.canRenameStore()) {
            throw BizException.businessRule("当前状态（" + status + "）不能修改店铺名称；"
                    + "仅营业中的店铺支持改名，草稿/被驳回请在申请材料里修改");
        }
        validateStoreName(newName);
        String trimmed = newName.trim();
        String before = entity.getName();
        if (trimmed.equals(before)) {
            return toView(entity);   // 改成原名：幂等返回，不写库不留痕
        }
        entity.setName(trimmed);
        entity.setUpdatedAt(now());
        try {
            merchantMapper.updateById(entity);
        } catch (DuplicateKeyException ex) {
            throw BizException.conflict("店铺名已被使用，请换一个");
        }
        try {
            auditLogService.record(new AuditLogRecord(
                    userId, "",
                    "MERCHANT_STORE_RENAME", "MERCHANT",
                    AuditOutcome.SUCCESS.name(),
                    entity.getId(), "MERCHANT",
                    entity.getOwnerUserId() == null ? 0L : entity.getOwnerUserId(),
                    before, trimmed, null, com.duoduo.common.api.RequestIdHolder.get(),
                    "店铺改名", LocalDateTime.now(ZoneOffset.UTC).toString()));
        } catch (Exception ex) {
            log.error("店铺改名留痕失败 merchantId={} owner={}", entity.getId(), userId, ex);
        }
        return toView(entity);
    }

    /**
     * 店铺公开主页信息（第八十二轮）：仅「营业中（APPROVED）」可查。
     *
     * <p>为什么只放行 APPROVED：主页是「这家店在营业」的对外承诺——送审中/草稿/驳回/停业的店铺
     * 对外展示会误导买家（商品都不可见了，店铺页却还在）。与商品域「公开读只认在售+已过审」同一口径。
     * 其余状态一律抛 {@link com.duoduo.common.exception.BizException}（NOT_FOUND）。
     */
    public com.duoduo.user.dto.MerchantDtos.StorePublicView storeProfileOf(Long ownerUserId) {
        MerchantEntity entity = findByOwner(java.util.Objects.requireNonNull(ownerUserId, "ownerUserId"));
        if (entity == null || !MerchantStatus.APPROVED.name().equals(entity.getStatus())) {
            throw BizException.notFound("店铺");
        }
        return new com.duoduo.user.dto.MerchantDtos.StorePublicView(
                entity.getOwnerUserId(),
                entity.getName(),
                entity.getReviewedAt() == null ? null : entity.getReviewedAt().toString());
    }

    private MerchantEntity findByOwner(Long userId) {
        return merchantMapper.selectOne(new LambdaQueryWrapper<MerchantEntity>()
                .eq(MerchantEntity::getOwnerUserId, userId).last("LIMIT 1"));
    }

    /**
     * 批量取店铺名（内部端点）：按店主 userId 查 {@code t_merchant.name}。
     *
     * <p>为什么是批量：购物车一页可能涉及好几家店，逐店一次 Feign 就是 N+1 的跨进程版本。
     * 查不到的 id 直接跳过（自营 / 从未入驻的 userId 本就不该有店铺），调用方降级展示，
     * 不在这里抛 404 —— 批量接口逐个抛会把好请求一起拖死（与 {@code userBriefs} 同一口径）。
     */
    public java.util.List<com.duoduo.user.dto.MerchantDtos.StoreBrief> storeNamesOf(java.util.Collection<Long> ownerUserIds) {
        if (ownerUserIds == null || ownerUserIds.isEmpty()) {
            return java.util.List.of();
        }
        // 只认「营业中（APPROVED）」（2026-10-22 收口）：与店铺公开主页同一口径 ——
        // 送审中/DRAFT 的夹具店铺对外不可见，把它的名字发出去会让订单卡跳到一个 404 店铺页。
        return merchantMapper.selectList(new LambdaQueryWrapper<MerchantEntity>()
                        .in(MerchantEntity::getOwnerUserId, ownerUserIds)
                        .eq(MerchantEntity::getStatus, MerchantStatus.APPROVED.name()))
                .stream()
                .map(e -> new com.duoduo.user.dto.MerchantDtos.StoreBrief(e.getOwnerUserId(), e.getName()))
                .toList();
    }

    private com.duoduo.user.entity.MerchantMemberEntity findMember(Long merchantId, Long userId) {
        return memberMapper.selectOne(new LambdaQueryWrapper<com.duoduo.user.entity.MerchantMemberEntity>()
                .eq(com.duoduo.user.entity.MerchantMemberEntity::getMerchantId, merchantId)
                .eq(com.duoduo.user.entity.MerchantMemberEntity::getUserId, userId)
                .last("LIMIT 1"));
    }

    private List<MerchantMaterialEntity> loadMaterialRows(Long merchantId) {
        return materialMapper.selectList(
                new LambdaQueryWrapper<MerchantMaterialEntity>()
                        .eq(MerchantMaterialEntity::getMerchantId, merchantId));
    }

    private Map<MerchantMaterialType, String> loadMaterials(Long merchantId) {
        Map<MerchantMaterialType, String> out = new EnumMap<>(MerchantMaterialType.class);
        for (MerchantMaterialEntity row : loadMaterialRows(merchantId)) {
            out.put(MerchantMaterialType.parse(row.getMaterialType()), row.getFileUrl());
        }
        return out;
    }

    private void upsertMaterials(Long merchantId, Long userId, Map<String, String> materials) {
        for (Map.Entry<String, String> e : materials.entrySet()) {
            MerchantMaterialType type = MerchantMaterialType.parse(e.getKey());
            String url = e.getValue() == null ? "" : e.getValue().trim();
            if (url.isEmpty()) {
                continue;   // 空值 = 不修改该类（删材料走显式空串语义过于隐晦，v1 不支持删）
            }
            // E12：新上传一律走私有通道（/api/merchant-materials/{merchantId}/{storedName}），
            // 且路径里的 merchantId **必须是申请人自己的** —— 不校验归属的话，
            // 把别人的材料路径填进自己的申请就能"借用"别人的执照通过审核。
            // /uploads/ 前缀是 E10 时期的存量数据，只读兼容、不再产生新写入。
            if (url.startsWith(MATERIAL_READ_PREFIX)) {
                String pathMid = url.substring(MATERIAL_READ_PREFIX.length()).split("/")[0];
                if (!String.valueOf(merchantId).equals(pathMid)) {
                    throw BizException.businessRule("材料路径不属于当前申请（" + type.label() + "）");
                }
            } else if (!url.startsWith(UPLOAD_PREFIX)) {
                throw BizException.businessRule("材料必须通过本站私有上传通道提交（" + type.label() + "）");
            }
            MerchantMaterialEntity row = materialMapper.selectOne(
                    new LambdaQueryWrapper<MerchantMaterialEntity>()
                            .eq(MerchantMaterialEntity::getMerchantId, merchantId)
                            .eq(MerchantMaterialEntity::getMaterialType, type.name())
                            .last("LIMIT 1"));
            if (row == null) {
                row = new MerchantMaterialEntity();
                row.setId(idGenerator.nextId());
                row.setMerchantId(merchantId);
                row.setMaterialType(type.name());
                row.setCreatedAt(now());
            }
            row.setFileUrl(url);
            row.setUploadedBy(userId);
            row.setUpdatedAt(now());
            try {
                if (row.getCreatedAt() == null || materialMapper.selectById(row.getId()) == null) {
                    materialMapper.insert(row);
                } else {
                    materialMapper.updateById(row);
                }
            } catch (DuplicateKeyException ex) {
                throw BizException.conflict("材料保存冲突，请刷新后重试");
            }
        }
    }

    /**
     * E15 · 写入材料有效期。
     *
     * <p>三条语义（与 {@code materials} 保持同一套，前端不用记两套规则）：
     * <ul>
     *   <li><b>没出现的类型 = 不修改</b>（老前端不传这个字段，一切照旧）；</li>
     *   <li><b>空串 = 改为长期有效</b>（显式表达「这张证没有到期日」，
     *       与「不修改」必须能区分，否则填错日期就没法撤回了）；</li>
     *   <li><b>材料不存在时跳过</b>：有效期依附于材料，先传文件再填日期。
     *       这里不报错是因为前端可能一次提交里两项都有，顺序由服务端决定。</li>
     * </ul>
     */
    private void applyMaterialExpiry(Long merchantId, Map<String, String> expiryByType) {
        for (Map.Entry<String, String> e : expiryByType.entrySet()) {
            MerchantMaterialType type = MerchantMaterialType.parse(e.getKey());
            String raw = e.getValue();
            LocalDateTime expireAt = (raw == null || raw.isBlank()) ? null : parseExpireDate(raw, type);
            MerchantMaterialEntity row = materialMapper.selectOne(
                    new LambdaQueryWrapper<MerchantMaterialEntity>()
                            .eq(MerchantMaterialEntity::getMerchantId, merchantId)
                            .eq(MerchantMaterialEntity::getMaterialType, type.name())
                            .last("LIMIT 1"));
            if (row == null) {
                continue;           // 材料还没上传，等它传上来的那次再写
            }
            row.setExpireAt(expireAt);
            row.setUpdatedAt(now());
            materialMapper.updateById(row);
        }
    }

    /**
     * 把「NULL 本身有语义」的几个可空列<b>真正写进库</b>。
     *
     * <h2>★ 为什么不能靠 {@code updateById}</h2>
     * MyBatis-Plus 的默认更新策略是 {@code NOT_NULL}：实体字段为 {@code null} 时
     * 该列<b>根本不会出现在 UPDATE 语句里</b>。而下面这三列的「清空」恰恰是业务动作：
     * <ul>
     *   <li>{@code expire_notify_stage} —— 清空 = 提醒重新从零计数（续期提交 / 审核通过）；</li>
     *   <li>{@code suspended_reason} —— 清空 = 停业原因不再展示（店铺已恢复营业）；</li>
     *   <li>{@code reject_reason} —— 清空 = 上次驳回原因已过时（商家重新提交）。</li>
     * </ul>
     * 用 {@code updateById} 的失效方式极其隐蔽：接口返回 200、{@code status} 也确实变了，
     * 只有这几列悄悄留着旧值 —— 没有异常、没有日志、也不影响任何既有断言。
     * 本轮就是被 e2e 里「续期后档位应当清零」这两条断言抓出来的（S6/S7），
     * 靠读代码几乎不可能发现。
     *
     * <p><b>内存与库都要清</b>：{@code toView()} 读的是内存对象，只清库会让<b>响应体</b>
     * 里还带着旧值 —— 那是方向相反的同一个 bug（库对了、接口错）。
     *
     * <p>这三列在任何调用点被一起清掉都是安全的：能走到「提交 / 续期 / 通过」的店铺，
     * 其余两列本就应该为空（草稿没有停业原因，已开张的店没有驳回原因）。
     */
    private void clearNullableState(MerchantEntity entity, LocalDateTime now) {
        entity.setExpireNotifyStage(null);
        entity.setSuspendedReason(null);
        entity.setRejectReason(null);
        merchantMapper.update(null, new LambdaUpdateWrapper<MerchantEntity>()
                .eq(MerchantEntity::getId, entity.getId())
                .set(MerchantEntity::getExpireNotifyStage, null)
                .set(MerchantEntity::getSuspendedReason, null)
                .set(MerchantEntity::getRejectReason, null)
                .set(MerchantEntity::getUpdatedAt, now));
    }

    /**
     * E15 · 重算店铺级到期日（派生列 {@code license_expire_at} 的<b>唯一写入出口</b>）。
     *
     * <p>派生列最常见的坏法不是算错，而是「材料改了、派生列没跟着改」——
     * 于是审核台看到的有效期与扫描任务判定用的有效期是两回事。
     * 把重算收敛到一个方法、所有写材料的地方都调它，这类漂移就不可能发生。
     *
     * @return 该店铺最早的材料到期日；一份带有效期的材料都没有时返回 {@code null}（长期有效）
     */
    private LocalDateTime refreshLicenseExpireAt(Long merchantId) {
        return LicenseExpiry.earliest(loadMaterialRows(merchantId).stream()
                .map(MerchantMaterialEntity::getExpireAt)
                .toArray(LocalDateTime[]::new));
    }

    /**
     * E15 · 解析有效期字符串 → 到期时刻。
     *
     * <p>接受 {@code yyyy-MM-dd}（前端日期选择器）与 {@code yyyy-MM-ddTHH:mm}（带时间的调用方）。
     * ★ 只给到日的输入一律落到<b>当日 23:59:59</b>：「有效期至 2026-12-31」在常识里
     * 指这天结束才失效；存成当日 00:00:00 会让商家在到期日零点就被停业，
     * 等于凭空少用一天。
     *
     * <p>非法格式直接 400（校验类错误），不宽容 —— 日期解析失败的静默兜底
     * （比如当成长效）会让商家以为填上了，实际证照永远不过期。
     */
    private LocalDateTime parseExpireDate(String raw, MerchantMaterialType type) {
        String text = raw.trim();
        try {
            if (text.length() <= 10) {
                return java.time.LocalDate.parse(text).atTime(23, 59, 59);
            }
            String normalized = text.replace(' ', 'T');
            if (normalized.length() == 16) {
                normalized = normalized + ":59";
            }
            return LocalDateTime.parse(normalized);
        } catch (java.time.format.DateTimeParseException ex) {
            throw BizException.validation("「" + type.label() + "」的有效期格式不对（应为 yyyy-MM-dd）：" + raw);
        }
    }

    private void validateStoreName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.length() < STORE_NAME_MIN || name.length() > STORE_NAME_MAX) {
            throw BizException.businessRule("店铺名称长度须在 " + STORE_NAME_MIN + "-"
                    + STORE_NAME_MAX + " 字之间");
        }
        if (name.startsWith("待填写-")) {
            throw BizException.businessRule("请填写真实的店铺名称");
        }
    }

    private Map<Long, String> loadOwnerPhones(List<MerchantEntity> merchants) {
        if (merchants.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = merchants.stream()
                .map(MerchantEntity::getOwnerUserId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        // 空集合不能进 selectBatchIds：会拼出 `IN ( )`，JSqlParser 直接解析失败（接口 500）
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(UserEntity::getId,
                        u -> u.getPhone() == null ? "" : mask(u.getPhone())));
    }

    private ApplicationView toView(MerchantEntity entity) {
        // 一次查询供三处使用：材料 URL、有效期回填、店铺到期日。
        // ★ 这里**实时算**到期日，而不是读 t_merchant.license_expire_at：
        //   那一列是给扫描任务走索引用的冗余列，不是展示的真值来源。
        //   若展示读冗余列，任何「材料改了但派生列没跟着刷」的路径都会让商家
        //   在界面上看到旧日期 —— 而派生列存在的意义恰恰是让高频路径不必算，
        //   低频的展示路径没有这个必要。
        List<MerchantMaterialEntity> rows = loadMaterialRows(entity.getId());
        Map<String, String> materials = new LinkedHashMap<>();
        Map<String, String> expiry = new LinkedHashMap<>();
        for (MerchantMaterialEntity row : rows) {
            MerchantMaterialType type = MerchantMaterialType.parse(row.getMaterialType());
            materials.put(type.name(), row.getFileUrl());
            if (row.getExpireAt() != null) {
                // 回填给前端编辑框（yyyy-MM-dd）；不回填的话商家每次进来都看到空白日期，
                // 会以为上次没填上而重填一遍
                expiry.put(type.name(), row.getExpireAt().toLocalDate().toString());
            }
        }
        LocalDateTime expireAt = LicenseExpiry.earliest(rows.stream()
                .map(MerchantMaterialEntity::getExpireAt)
                .toArray(LocalDateTime[]::new));
        LocalDateTime current = now();
        return new ApplicationView(
                String.valueOf(entity.getId()),
                entity.getName(),
                entity.getStatus(),
                entity.getIsolationLevel(),
                fromJson(entity.getRejectReason()),
                materials,
                String.valueOf(entity.getOwnerUserId()),
                entity.getAppliedAt(),
                entity.getReviewedAt(),
                entity.getCreatedAt(),
                expireAt,
                LicenseExpiry.stageOf(expireAt, current).name(),
                // 无到期概念时回 null 而不是 0：「剩余 0 天」在界面上等于「今天到期」
                LicenseExpiry.hasExpiry(expireAt) ? LicenseExpiry.remainingDays(expireAt, current) : null,
                entity.getSuspendedReason(),
                expiry);
    }

    private List<RejectReason> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return Arrays.asList(objectMapper.readValue(json, RejectReason[].class));
        } catch (JsonProcessingException ex) {
            log.warn("驳回原因反序列化失败，按空处理：{}", ex.getMessage());
            return List.of();
        }
    }

    private String toJson(List<RejectReason> reasons) {
        try {
            return objectMapper.writeValueAsString(reasons);
        } catch (JsonProcessingException ex) {
            throw new BizException(com.duoduo.common.enums.ErrorCode.INTERNAL,
                    "驳回原因序列化失败", ex);
        }
    }

    private static String mask(String phone) {
        if (phone == null || phone.length() != 11) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
