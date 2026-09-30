package com.duoduo.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.user.dto.UserDtos;
import com.duoduo.user.dto.UserDtos.PetUpsertRequest;
import com.duoduo.user.dto.UserDtos.PetView;
import com.duoduo.user.entity.PetProfileEntity;
import com.duoduo.user.mapper.PetProfileMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

/**
 * 宠物档案服务。
 *
 * <p>业务规则：
 * <ul>
 *   <li>一个账号可建多只宠物；首只自动设为「当前宠物」（PET-02）</li>
 *   <li>按出生日期推算生命阶段；与用户所选矛盾时 **拒绝写入并提示确认**，
 *       而不是默默采信用户的错误选择（PET-05）</li>
 *   <li>所有读写都带 userId 维度，杜绝越权访问他人档案</li>
 * </ul>
 */
@Service
public class PetProfileService {

    private static final Logger log = LoggerFactory.getLogger(PetProfileService.class);

    private final PetProfileMapper petMapper;
    private final SnowflakeIdGenerator idGenerator;

    public PetProfileService(PetProfileMapper petMapper, SnowflakeIdGenerator idGenerator) {
        this.petMapper = petMapper;
        this.idGenerator = idGenerator;
    }

    public List<PetView> listByUser(Long userId) {
        return petMapper.selectList(Wrappers.<PetProfileEntity>lambdaQuery()
                        .eq(PetProfileEntity::getUserId, userId)
                        .orderByDesc(PetProfileEntity::getIsDefault)
                        .orderByAsc(PetProfileEntity::getCreatedAt))
                .stream()
                .map(this::toView)
                .toList();
    }

    /**
     * 服务间读：AI 导购用的宠物档案（第五十八轮 · AI 加强项 S1）。
     *
     * <p>为什么单独给 AI 一个方法而不是复用 {@link #listByUser}：
     * 那份视图带生日 / 体重 / 性别 / **病史**，是给「我的宠物」页面看的；
     * 跨服务下发的字段一旦给出去就会在各处留下副本，裁剪必须在**出口**这一侧完成 ——
     * 让调用方自己挑字段，等于要求每个调用方都记得「不要病史」，迟早有人忘。
     *
     * <p>上限 3 只：导购只需要知道「这个人大致养什么」，
     * 把十几只宠物全拼进提示词只会稀释真正相关的那一只。
     */
    public List<UserDtos.PetBrief> briefsForAssistant(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return petMapper.selectList(Wrappers.<PetProfileEntity>lambdaQuery()
                        .eq(PetProfileEntity::getUserId, userId)
                        .orderByDesc(PetProfileEntity::getIsDefault)
                        .orderByAsc(PetProfileEntity::getCreatedAt)
                        .last("LIMIT 3"))
                .stream()
                .map(PetProfileService::toBrief)
                .toList();
    }

    private static UserDtos.PetBrief toBrief(PetProfileEntity entity) {
        return new UserDtos.PetBrief(
                entity.getId(),
                entity.getName(),
                entity.getSpecies(),
                entity.getBreed(),
                entity.getLifeStage(),
                entity.getAllergens() == null || entity.getAllergens().isBlank()
                        ? List.of()
                        : Arrays.stream(entity.getAllergens().split(",")).map(String::trim).toList()
        );
    }

    @Transactional
    public PetView create(Long userId, PetUpsertRequest request) {
        validateLifeStageConsistency(request);

        Long existingCount = petMapper.selectCount(Wrappers.<PetProfileEntity>lambdaQuery()
                .eq(PetProfileEntity::getUserId, userId));
        boolean asDefault = existingCount == 0 || Boolean.TRUE.equals(request.isDefault());

        if (asDefault) {
            clearDefault(userId);
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        PetProfileEntity entity = new PetProfileEntity();
        entity.setId(idGenerator.nextId());
        entity.setUserId(userId);
        entity.setName(request.name().trim());
        entity.setSpecies(request.species());
        entity.setBreed(request.breed());
        entity.setBirthday(request.birthday());
        entity.setLifeStage(resolveLifeStage(request));
        entity.setWeightKg(request.weightKg());
        entity.setGender(request.gender() == null ? "UNKNOWN" : request.gender());
        entity.setNeutered(Boolean.TRUE.equals(request.neutered()) ? 1 : 0);
        entity.setAllergens(joinAllergens(request.allergens()));
        entity.setMedicalHistory(request.medicalHistory());
        entity.setIsDefault(asDefault ? 1 : 0);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        petMapper.insert(entity);
        log.info("宠物档案创建 userId={} petId={} species={}", userId, entity.getId(), entity.getSpecies());
        return toView(entity);
    }

    @Transactional
    public PetView update(Long userId, Long petId, PetUpsertRequest request) {
        PetProfileEntity entity = requireOwned(userId, petId);
        validateLifeStageConsistency(request);

        entity.setName(request.name().trim());
        entity.setSpecies(request.species());
        entity.setBreed(request.breed());
        entity.setBirthday(request.birthday());
        entity.setLifeStage(resolveLifeStage(request));
        entity.setWeightKg(request.weightKg());
        entity.setGender(request.gender() == null ? "UNKNOWN" : request.gender());
        entity.setNeutered(Boolean.TRUE.equals(request.neutered()) ? 1 : 0);
        entity.setAllergens(joinAllergens(request.allergens()));
        entity.setMedicalHistory(request.medicalHistory());
        entity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));

        if (Boolean.TRUE.equals(request.isDefault())) {
            clearDefault(userId);
            entity.setIsDefault(1);
        }

        petMapper.updateById(entity);
        log.info("宠物档案更新 userId={} petId={}", userId, petId);
        return toView(entity);
    }

    @Transactional
    public PetView setDefault(Long userId, Long petId) {
        requireOwned(userId, petId);
        clearDefault(userId);
        PetProfileEntity entity = requireOwned(userId, petId);
        entity.setIsDefault(1);
        entity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        petMapper.updateById(entity);
        return toView(entity);
    }

    @Transactional
    public void delete(Long userId, Long petId) {
        PetProfileEntity entity = requireOwned(userId, petId);
        petMapper.deleteById(petId);

        // 删除的是当前宠物时，顺延一只为当前，避免推荐上下文丢失
        if (entity.getIsDefault() != null && entity.getIsDefault() == 1) {
            List<PetProfileEntity> rest = petMapper.selectList(Wrappers.<PetProfileEntity>lambdaQuery()
                    .eq(PetProfileEntity::getUserId, userId)
                    .orderByAsc(PetProfileEntity::getCreatedAt));
            if (!rest.isEmpty()) {
                PetProfileEntity next = rest.get(0);
                next.setIsDefault(1);
                next.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
                petMapper.updateById(next);
            }
        }
        log.info("宠物档案删除 userId={} petId={}", userId, petId);
    }

    private PetProfileEntity requireOwned(Long userId, Long petId) {
        PetProfileEntity entity = petMapper.selectById(petId);
        if (entity == null || !entity.getUserId().equals(userId)) {
            throw BizException.notFound("宠物档案");
        }
        return entity;
    }

    private void clearDefault(Long userId) {
        PetProfileEntity patch = new PetProfileEntity();
        patch.setIsDefault(0);
        petMapper.update(patch, Wrappers.<PetProfileEntity>lambdaUpdate()
                .eq(PetProfileEntity::getUserId, userId)
                .eq(PetProfileEntity::getIsDefault, 1));
    }

    private String resolveLifeStage(PetUpsertRequest request) {
        if (request.lifeStage() != null && !request.lifeStage().isBlank()) {
            return request.lifeStage();
        }
        Integer months = ageMonths(request.birthday());
        if (months == null) {
            return null;
        }
        return inferLifeStage(request.species(), months);
    }

    private void validateLifeStageConsistency(PetUpsertRequest request) {
        if (request.lifeStage() == null || request.lifeStage().isBlank()) {
            return;
        }
        Integer months = ageMonths(request.birthday());
        if (months == null) {
            return;
        }
        String inferred = inferLifeStage(request.species(), months);
        if (!inferred.equals(request.lifeStage())) {
            throw BizException.businessRule(
                    "按出生日期计算该宠物处于「" + label(inferred) + "」阶段，与所选「"
                            + label(request.lifeStage()) + "」不一致，请确认后再提交");
        }
    }

    /** 幼年 / 成年 / 老年的划分按物种区分：猫 12/96 月，犬 12/84 月 */
    static String inferLifeStage(String species, int ageMonths) {
        int seniorThreshold = "dog".equals(species) ? 84 : 96;
        if (ageMonths <= 12) {
            return "BABY";
        }
        return ageMonths >= seniorThreshold ? "SENIOR" : "ADULT";
    }

    private static String label(String stage) {
        return switch (stage) {
            case "BABY" -> "幼年";
            case "SENIOR" -> "老年";
            default -> "成年";
        };
    }

    private static Integer ageMonths(LocalDate birthday) {
        if (birthday == null || birthday.isAfter(LocalDate.now())) {
            return null;
        }
        Period period = Period.between(birthday, LocalDate.now());
        return period.getYears() * 12 + period.getMonths();
    }

    private static String joinAllergens(List<String> allergens) {
        if (allergens == null || allergens.isEmpty()) {
            return "";
        }
        return String.join(",", allergens.stream().map(String::trim).filter(s -> !s.isEmpty()).toList());
    }

    private PetView toView(PetProfileEntity entity) {
        Integer months = ageMonths(entity.getBirthday());
        return new PetView(
                entity.getId(),
                entity.getName(),
                entity.getSpecies(),
                entity.getBreed(),
                entity.getBirthday(),
                months,
                entity.getLifeStage() != null ? entity.getLifeStage()
                        : (months == null ? null : inferLifeStage(entity.getSpecies(), months)),
                entity.getWeightKg(),
                entity.getGender(),
                entity.getNeutered() != null && entity.getNeutered() == 1,
                entity.getAllergens() == null || entity.getAllergens().isBlank()
                        ? List.of()
                        : Arrays.stream(entity.getAllergens().split(",")).map(String::trim).toList(),
                entity.getMedicalHistory(),
                entity.getIsDefault() != null && entity.getIsDefault() == 1,
                entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString()
        );
    }
}
