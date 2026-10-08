package com.duoduo.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.user.dto.MerchantDtos.ApplicationSummary;
import com.duoduo.user.dto.MerchantDtos.ApplicationView;
import com.duoduo.user.dto.MerchantDtos.RejectReason;
import com.duoduo.user.dto.MerchantDtos.SaveDraftRequest;
import com.duoduo.user.entity.MerchantEntity;
import com.duoduo.user.entity.MerchantMaterialEntity;
import com.duoduo.user.entity.UserEntity;
import com.duoduo.user.mapper.MerchantMapper;
import com.duoduo.user.mapper.MerchantMaterialMapper;
import com.duoduo.user.mapper.UserMapper;
import com.duoduo.user.support.MerchantMaterialType;
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
    private static final int STORE_NAME_MIN = 2;
    private static final int STORE_NAME_MAX = 30;

    private final MerchantMapper merchantMapper;
    private final com.duoduo.user.mapper.MerchantMemberMapper memberMapper;
    private final MerchantMaterialMapper materialMapper;
    private final UserMapper userMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final ObjectMapper objectMapper;

    public MerchantOnboardingService(MerchantMapper merchantMapper,
                                     com.duoduo.user.mapper.MerchantMemberMapper memberMapper,
                                     MerchantMaterialMapper materialMapper,
                                     UserMapper userMapper,
                                     SnowflakeIdGenerator idGenerator,
                                     ObjectMapper objectMapper) {
        this.merchantMapper = merchantMapper;
        this.memberMapper = memberMapper;
        this.materialMapper = materialMapper;
        this.userMapper = userMapper;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
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
        return toView(entity);
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
        entity.setStatus(MerchantStatus.SUBMITTED.name());
        entity.setAppliedAt(now());
        entity.setRejectReason(null);        // 重提时旧的驳回原因已过时（历史在审核日志）
        entity.setUpdatedAt(now());
        merchantMapper.updateById(entity);
        log.info("入驻申请提交 merchantId={} userId={} status=SUBMITTED", entity.getId(), userId);
        return toView(entity);
    }

    // ---------------- 运营侧（E11 审核台的后端） ----------------

    public PageResult<ApplicationSummary> adminPage(int page, int pageSize, String statusFilter) {
        Page<MerchantEntity> p = merchantMapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<MerchantEntity>()
                        .eq(statusFilter != null && !statusFilter.isBlank(),
                                MerchantEntity::getStatus, statusFilter)
                        .orderByDesc(MerchantEntity::getAppliedAt)
                        .orderByDesc(MerchantEntity::getCreatedAt));
        Map<Long, String> phones = loadOwnerPhones(p.getRecords());
        List<ApplicationSummary> items = p.getRecords().stream()
                .map(m -> new ApplicationSummary(
                        String.valueOf(m.getId()), m.getName(), m.getStatus(),
                        String.valueOf(m.getOwnerUserId()),
                        phones.get(m.getOwnerUserId()),
                        m.getAppliedAt(), m.getReviewedAt()))
                .toList();
        return PageResult.of(items, p.getTotal(), page, pageSize);
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
            return toView(entity);
        }
        if (status != MerchantStatus.SUBMITTED) {
            throw BizException.businessRule("只有待审核的申请才能开始审核（当前 " + status + "）");
        }
        entity.setStatus(MerchantStatus.REVIEWING.name());
        entity.setUpdatedAt(now());
        merchantMapper.updateById(entity);
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
            return toView(entity);           // 幂等：重复通过返回现状
        }
        if (status != MerchantStatus.REVIEWING) {
            throw BizException.businessRule("只有审核中的申请可以通过（当前 " + status + "）");
        }
        entity.setStatus(MerchantStatus.APPROVED.name());
        entity.setReviewedAt(now());
        entity.setReviewedBy(operatorId);
        entity.setUpdatedAt(now());
        merchantMapper.updateById(entity);

        // 开店动作（幂等：重复 approve 时 member 已存在、role 已是 SELLER，均为无副作用）
        if (findMember(merchantId, entity.getOwnerUserId()) == null) {
            com.duoduo.user.entity.MerchantMemberEntity member =
                    new com.duoduo.user.entity.MerchantMemberEntity();
            member.setId(idGenerator.nextId());
            member.setMerchantId(merchantId);
            member.setUserId(entity.getOwnerUserId());
            member.setRole("OWNER");
            member.setStatus("ACTIVE");
            member.setCreatedAt(now());
            member.setUpdatedAt(now());
            try {
                memberMapper.insert(member);
            } catch (DuplicateKeyException ex) {
                // 并发 approve：member 已由另一请求写入 —— 结论一致，忽略
            }
        }
        UserEntity owner = userMapper.selectById(entity.getOwnerUserId());
        if (owner != null && !"SELLER".equals(owner.getRole())) {
            owner.setRole("SELLER");
            owner.setUpdatedAt(now());
            userMapper.updateById(owner);
        }
        log.info("入驻通过并开店 merchantId={} owner={} operator={}", merchantId, entity.getOwnerUserId(), operatorId);
        return toView(entity);
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
            // 幂等：结论一致（都是驳回），允许补全/更新原因后返回现状
            entity.setRejectReason(toJson(reasons));
            entity.setUpdatedAt(now());
            merchantMapper.updateById(entity);
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

    // ---------------- 内部 ----------------

    private MerchantEntity requireOwned(Long userId) {
        MerchantEntity entity = findByOwner(userId);
        if (entity == null) {
            throw BizException.notFound("入驻申请");
        }
        return entity;
    }

    private MerchantEntity requireMerchant(Long merchantId) {
        MerchantEntity entity = merchantMapper.selectById(merchantId);
        if (entity == null) {
            throw BizException.notFound("入驻申请");
        }
        return entity;
    }

    private MerchantEntity findByOwner(Long userId) {
        return merchantMapper.selectOne(new LambdaQueryWrapper<MerchantEntity>()
                .eq(MerchantEntity::getOwnerUserId, userId).last("LIMIT 1"));
    }

    private com.duoduo.user.entity.MerchantMemberEntity findMember(Long merchantId, Long userId) {
        return memberMapper.selectOne(new LambdaQueryWrapper<com.duoduo.user.entity.MerchantMemberEntity>()
                .eq(com.duoduo.user.entity.MerchantMemberEntity::getMerchantId, merchantId)
                .eq(com.duoduo.user.entity.MerchantMemberEntity::getUserId, userId)
                .last("LIMIT 1"));
    }

    private Map<MerchantMaterialType, String> loadMaterials(Long merchantId) {
        List<MerchantMaterialEntity> rows = materialMapper.selectList(
                new LambdaQueryWrapper<MerchantMaterialEntity>()
                        .eq(MerchantMaterialEntity::getMerchantId, merchantId));
        Map<MerchantMaterialType, String> out = new EnumMap<>(MerchantMaterialType.class);
        for (MerchantMaterialEntity row : rows) {
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
            if (!url.startsWith(UPLOAD_PREFIX)) {
                throw BizException.businessRule("材料必须通过本站上传通道提交（" + type.label() + "）");
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
        List<Long> ids = merchants.stream().map(MerchantEntity::getOwnerUserId).distinct().toList();
        return userMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(UserEntity::getId,
                        u -> u.getPhone() == null ? "" : mask(u.getPhone())));
    }

    private ApplicationView toView(MerchantEntity entity) {
        Map<String, String> materials = new LinkedHashMap<>();
        loadMaterials(entity.getId())
                .forEach((type, url) -> materials.put(type.name(), url));
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
                entity.getCreatedAt());
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
