package com.duoduo.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.user.dto.CoinDtos.CoinCampaignSaveRequest;
import com.duoduo.user.dto.CoinDtos.CoinCampaignView;
import com.duoduo.user.entity.CoinCampaignEntity;
import com.duoduo.user.entity.CoinCampaignUserEntity;
import com.duoduo.user.mapper.CoinCampaignMapper;
import com.duoduo.user.mapper.CoinCampaignUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

/**
 * 多多币节日活动（R-P1 第三轮）：倍率、预算、每人上限。
 *
 * <p><b>只做加成，不发基础币</b>：签到与购物返币的基础发放仍在 {@link CoinService}，
 * 本服务回答「在基础值之上还能多给多少」。这样活动可以随时上下线，
 * 而账本口径（1 币 = 1 分、12 个月批次、流水唯一键）一处不动。
 *
 * <p><b>预算与每人上限都必须原子占用</b>：两者的判定都写进条件 UPDATE 的 WHERE，
 * 绝不「先查余量再发」。预算不足或额度用完时**退回基础值**、不报错 ——
 * 加成是锦上添花，不该让用户签到失败。
 *
 * <p><b>0 的语义</b>：{@code budget_total = 0} / {@code per_user_cap = 0} 表示不限，
 * 与表默认值一致；运营要限额就填正数。
 */
@Service
public class CoinCampaignService {

    private static final Logger log = LoggerFactory.getLogger(CoinCampaignService.class);

    /** 业务时区：活动时间不带时区时按北京时间解释（与签到 / 任务同一口径） */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    /** 倍率下限：1.00 = 不加成；低于 1 等于「活动期间反而少发」，不允许 */
    private static final BigDecimal MIN_MULTIPLIER = new BigDecimal("1.00");
    private static final BigDecimal MAX_MULTIPLIER = new BigDecimal("10.00");

    private final CoinCampaignMapper campaignMapper;
    private final CoinCampaignUserMapper campaignUserMapper;
    private final SnowflakeIdGenerator idGenerator;

    public CoinCampaignService(CoinCampaignMapper campaignMapper,
                               CoinCampaignUserMapper campaignUserMapper,
                               SnowflakeIdGenerator idGenerator) {
        this.campaignMapper = campaignMapper;
        this.campaignUserMapper = campaignUserMapper;
        this.idGenerator = idGenerator;
    }

    // ==================== 查询 ====================

    /** 当前进行中的活动（状态 ENABLED 且 start_at <= now < end_at）；没有返回 null */
    public CoinCampaignEntity activeCampaign(LocalDateTime nowUtc) {
        return campaignMapper.selectOne(Wrappers.<CoinCampaignEntity>lambdaQuery()
                .eq(CoinCampaignEntity::getStatus, CoinCampaignEntity.STATUS_ENABLED)
                .le(CoinCampaignEntity::getStartAt, nowUtc)
                .gt(CoinCampaignEntity::getEndAt, nowUtc)
                .orderByDesc(CoinCampaignEntity::getStartAt)
                .last("LIMIT 1"));
    }

    /** 买家端「进行中的活动」；没有则返回 null（接口 data=null，前端按空态处理） */
    public CoinCampaignView activeView() {
        CoinCampaignEntity campaign = activeCampaign(nowUtc());
        return campaign == null ? null : toView(campaign);
    }

    /** 运营端活动全量列表（含已停用 / 已过期） */
    public List<CoinCampaignView> listForAdmin() {
        return campaignMapper.selectList(Wrappers.<CoinCampaignEntity>lambdaQuery()
                        .orderByDesc(CoinCampaignEntity::getStartAt)
                        .orderByDesc(CoinCampaignEntity::getId))
                .stream().map(this::toView).toList();
    }

    // ==================== 加成计算（签到 / 购物返币共用） ====================

    /** 签到加成后的发放数（向下取整，最低 1）；无活动 / 预算不足 / 超上限时退回基础值 */
    @Transactional
    public int signinBonus(Long userId, int baseCoins) {
        long granted = bonusOf(userId, baseCoins, true);
        return (int) Math.min(Integer.MAX_VALUE, granted);
    }

    /** 购物返币加成后的发放数（向下取整）；无活动 / 预算不足 / 超上限时退回基础值 */
    @Transactional
    public long earnBonus(Long userId, long baseCoins) {
        return bonusOf(userId, baseCoins, false);
    }

    private long bonusOf(Long userId, long baseCoins, boolean signin) {
        if (userId == null || baseCoins <= 0) {
            return baseCoins;
        }
        CoinCampaignEntity campaign = activeCampaign(nowUtc());
        if (campaign == null) {
            return baseCoins;
        }
        BigDecimal multiplier = signin ? campaign.getSigninMultiplier() : campaign.getEarnMultiplier();
        if (multiplier == null || multiplier.compareTo(MIN_MULTIPLIER) <= 0) {
            return baseCoins;
        }
        long total = BigDecimal.valueOf(baseCoins).multiply(multiplier)
                .setScale(0, RoundingMode.DOWN).longValue();
        if (signin) {
            total = Math.max(1L, total);
        }
        long extra = total - baseCoins;
        if (extra <= 0) {
            return baseCoins;
        }

        LocalDateTime now = nowUtc();
        ensureUserRow(campaign.getId(), userId, now);
        CoinCampaignUserEntity row = campaignUserMapper.selectOne(Wrappers.<CoinCampaignUserEntity>lambdaQuery()
                .eq(CoinCampaignUserEntity::getCampaignId, campaign.getId())
                .eq(CoinCampaignUserEntity::getUserId, userId));
        long awarded = row == null ? 0L : nz(row.getAwarded());
        long cap = nz(campaign.getPerUserCap());
        if (cap > 0 && awarded >= cap) {
            return baseCoins;
        }
        long grant = cap > 0 ? Math.min(extra, cap - awarded) : extra;
        if (grant <= 0) {
            return baseCoins;
        }
        // 先占每人额度（条件 UPDATE），再占活动预算；预算不足时回退额度并退回基础值
        if (campaignUserMapper.addAwarded(campaign.getId(), userId, grant, cap, now) == 0) {
            return baseCoins;
        }
        if (campaignMapper.consumeBudget(campaign.getId(), grant, now) == 0) {
            campaignUserMapper.subtractAwarded(campaign.getId(), userId, grant, now);
            log.info("活动预算不足，退回基础值：campaign={} userId={} 加成={}", campaign.getCode(), userId, grant);
            return baseCoins;
        }
        log.info("活动加成生效：campaign={} userId={} 基础={} 加成={} 实发={}",
                campaign.getCode(), userId, baseCoins, grant, baseCoins + grant);
        return baseCoins + grant;
    }

    private void ensureUserRow(Long campaignId, Long userId, LocalDateTime now) {
        CoinCampaignUserEntity row = new CoinCampaignUserEntity();
        row.setId(idGenerator.nextId());
        row.setCampaignId(campaignId);
        row.setUserId(userId);
        row.setAwarded(0L);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        campaignUserMapper.insertIgnore(row);
    }

    // ==================== 运营端写操作 ====================

    @Transactional
    public CoinCampaignView create(CoinCampaignSaveRequest request) {
        CoinCampaignEntity entity = new CoinCampaignEntity();
        entity.setId(idGenerator.nextId());
        entity.setStatus(CoinCampaignEntity.STATUS_ENABLED);
        entity.setBudgetUsed(0L);
        LocalDateTime now = nowUtc();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        apply(entity, request);
        campaignMapper.insert(entity);
        log.info("新建多多币活动：code={} {} ~ {}", entity.getCode(), entity.getStartAt(), entity.getEndAt());
        return toView(entity);
    }

    @Transactional
    public CoinCampaignView update(Long id, CoinCampaignSaveRequest request) {
        CoinCampaignEntity entity = require(id);
        apply(entity, request);
        entity.setUpdatedAt(nowUtc());
        campaignMapper.updateById(entity);
        return toView(entity);
    }

    /** 启停：只改运营开关，不动时间与预算（与「活动是否进行中」是两个维度） */
    @Transactional
    public CoinCampaignView changeStatus(Long id, boolean enabled) {
        CoinCampaignEntity entity = require(id);
        entity.setStatus(enabled ? CoinCampaignEntity.STATUS_ENABLED : CoinCampaignEntity.STATUS_DISABLED);
        entity.setUpdatedAt(nowUtc());
        campaignMapper.updateById(entity);
        log.info("多多币活动状态：id={} status={}", id, entity.getStatus());
        return toView(entity);
    }

    private CoinCampaignEntity require(Long id) {
        CoinCampaignEntity entity = id == null ? null : campaignMapper.selectById(id);
        if (entity == null) {
            throw BizException.notFound("多多币活动（id=" + id + "）");
        }
        return entity;
    }

    /** 把保存命令写进实体：时间转 UTC 存储，倍率限定 [1.00, 10.00]，预算 / 上限不得为负 */
    private void apply(CoinCampaignEntity entity, CoinCampaignSaveRequest request) {
        if (request == null) {
            throw BizException.validation("活动参数不能为空");
        }
        String code = requireText(request.code(), "活动编码", 40).toUpperCase(Locale.ROOT);
        String name = requireText(request.name(), "活动名称", 64);
        LambdaQueryWrapper<CoinCampaignEntity> codeQuery = Wrappers.<CoinCampaignEntity>lambdaQuery()
                .eq(CoinCampaignEntity::getCode, code);
        if (entity.getId() != null) {
            codeQuery.ne(CoinCampaignEntity::getId, entity.getId());
        }
        Long sameCode = campaignMapper.selectCount(codeQuery);
        if (sameCode != null && sameCode > 0) {
            throw BizException.conflict("活动编码已存在：" + code);
        }
        LocalDateTime startAt = parseTime(request.startAt(), "开始时间");
        LocalDateTime endAt = parseTime(request.endAt(), "结束时间");
        if (!endAt.isAfter(startAt)) {
            throw BizException.validation("活动结束时间必须晚于开始时间");
        }
        long budgetTotal = nonNegative(request.budgetTotal(), "总预算");
        long budgetUsed = nz(entity.getBudgetUsed());
        if (budgetTotal > 0 && budgetTotal < budgetUsed) {
            throw BizException.validation("总预算不能小于已用预算（已用 " + budgetUsed + " 币）");
        }
        entity.setCode(code);
        entity.setName(name);
        entity.setStartAt(startAt);
        entity.setEndAt(endAt);
        entity.setSigninMultiplier(multiplier(request.signinMultiplier(), "签到倍率"));
        entity.setEarnMultiplier(multiplier(request.earnMultiplier(), "返币倍率"));
        entity.setBudgetTotal(budgetTotal);
        entity.setPerUserCap(nonNegative(request.perUserCap(), "每人上限"));
    }

    private static String requireText(String raw, String field, int maxLength) {
        if (raw == null || raw.isBlank()) {
            throw BizException.validation(field + "不能为空");
        }
        String value = raw.trim();
        if (value.length() > maxLength) {
            throw BizException.validation(field + "过长（最多 " + maxLength + " 字）");
        }
        return value;
    }

    private static BigDecimal multiplier(BigDecimal raw, String field) {
        if (raw == null) {
            return MIN_MULTIPLIER;
        }
        if (raw.compareTo(MIN_MULTIPLIER) < 0 || raw.compareTo(MAX_MULTIPLIER) > 0) {
            throw BizException.validation(field + "必须在 1.00 ~ 10.00 之间");
        }
        return raw.setScale(2, RoundingMode.DOWN);
    }

    private static long nonNegative(Long raw, String field) {
        if (raw == null) {
            return 0L;
        }
        if (raw < 0) {
            throw BizException.validation(field + "不能为负数");
        }
        return raw;
    }

    /** 活动时间解析：带时区按其时区，不带按北京时间；统一转 UTC 存储 */
    static LocalDateTime parseTime(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw BizException.validation(field + "不能为空");
        }
        String value = raw.trim();
        try {
            if (value.endsWith("Z") || value.contains("+") || value.matches(".*[+-]\\d{2}:\\d{2}$")) {
                return OffsetDateTime.parse(value).atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
            }
            return LocalDateTime.parse(value).atZone(BUSINESS_ZONE)
                    .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw BizException.validation(field + "格式不正确（应为 ISO 时间，如 2026-10-19T10:00:00）");
        }
    }

    private CoinCampaignView toView(CoinCampaignEntity entity) {
        return new CoinCampaignView(
                String.valueOf(entity.getId()),
                entity.getCode(),
                entity.getName(),
                iso(entity.getStartAt()),
                iso(entity.getEndAt()),
                entity.getSigninMultiplier() == null ? MIN_MULTIPLIER : entity.getSigninMultiplier(),
                entity.getEarnMultiplier() == null ? MIN_MULTIPLIER : entity.getEarnMultiplier(),
                nz(entity.getBudgetTotal()),
                nz(entity.getBudgetUsed()),
                nz(entity.getPerUserCap()),
                entity.getStatus());
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    private static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /** 存储是 UTC 的 LocalDateTime，对外统一给 ISO-8601（带 Z），端侧一眼能换算 */
    private static String iso(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC).toString();
    }
}
