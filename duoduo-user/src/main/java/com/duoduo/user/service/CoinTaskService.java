package com.duoduo.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.user.dto.CoinDtos.CoinMutationResult;
import com.duoduo.user.dto.CoinDtos.CoinTaskDefAdminView;
import com.duoduo.user.dto.CoinDtos.CoinTaskDefUpdateRequest;
import com.duoduo.user.dto.CoinDtos.CoinTaskReportRequest;
import com.duoduo.user.dto.CoinDtos.CoinTaskView;
import com.duoduo.user.entity.CoinTaskDefEntity;
import com.duoduo.user.entity.CoinTaskEventEntity;
import com.duoduo.user.entity.CoinTaskProgressEntity;
import com.duoduo.user.mapper.CoinTaskDefMapper;
import com.duoduo.user.mapper.CoinTaskEventMapper;
import com.duoduo.user.mapper.CoinTaskProgressMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 多多币站内任务（R-P1 第三轮）：日常任务 + 新人任务。
 *
 * <p><b>为什么用「站内真实行为」而不是看广告</b>：本项目没有广告与短视频供给，
 * 硬做「看广告得币」只会变成假按钮。浏览商品 / 逛分享圈 / 收藏 / 加购 / 晒单 / 评价，
 * 都是站内本来就会发生、且服务端能校验的行为。
 *
 * <p><b>三道防刷闸</b>：
 * <ol>
 *   <li>{@code uk_coin_task_event (user_id, task_code, biz_date, biz_id)} —— 同一商品 / 同一内容
 *       每天只算一次，重复上报撞键即跳过；</li>
 *   <li>进度行 {@code progress < cap} 条件自增 —— 每日上限（min(target_count, daily_cap)）不可能被冲破；</li>
 *   <li>发奖闸门 {@code rewarded = 0 → 1} 条件更新 —— 同一进度行只可能发出一次奖励，
 *       发币本身再由流水唯一键（COIN_TASK / taskCode:bizDate）二次兜底。</li>
 * </ol>
 *
 * <p><b>停留时长在服务端判定</b>：浏览商品 ≥15 秒、逛分享圈 ≥10 秒，不足不计进度 ——
 * 客户端上报的只是「看到了什么、待了多久」，能不能算由这里说了算。
 *
 * <p><b>新人任务是一次性</b>：进度落在哨兵日 1970-01-01，与日常任务的「今天」天然分开，
 * 不会因为跨天又被算一次。
 */
@Service
public class CoinTaskService {

    private static final Logger log = LoggerFactory.getLogger(CoinTaskService.class);

    /** 业务时区：任务按 Asia/Shanghai 自然日刷新（与签到同一口径） */
    private static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");
    /** 新人任务的哨兵业务日：一次任务永久有效，不随自然日滚动 */
    static final LocalDate NEWCOMER_BIZ_DATE = LocalDate.of(1970, 1, 1);

    /** 浏览类任务的停留时长门槛（秒）：服务端校验，不是客户端说了算 */
    private static final Map<String, Integer> BROWSE_DWELL_SECONDS = Map.of(
            "BROWSE_PRODUCT", 15,
            "BROWSE_SHARE", 10);

    /** 站内事件 → 任务编码（内部接口只认这几个事件） */
    private static final Map<String, String> EVENT_TASKS = Map.of(
            "FAVORITE", "FAVORITE",
            "CART_ADD", "CART_ADD",
            "SHARE_PUBLISH", "SHARE_PUBLISH",
            "REVIEW_APPROVED", "REVIEW_APPROVED",
            "NEWCOMER_PROFILE", "NEWCOMER_PROFILE",
            "FIRST_ORDER", "NEWCOMER_FIRST_ORDER",
            "FIRST_REVIEW", "NEWCOMER_FIRST_REVIEW");

    private final CoinTaskDefMapper taskDefMapper;
    private final CoinTaskProgressMapper progressMapper;
    private final CoinTaskEventMapper eventMapper;
    private final CoinService coinService;
    private final SnowflakeIdGenerator idGenerator;

    public CoinTaskService(CoinTaskDefMapper taskDefMapper,
                           CoinTaskProgressMapper progressMapper,
                           CoinTaskEventMapper eventMapper,
                           CoinService coinService,
                           SnowflakeIdGenerator idGenerator) {
        this.taskDefMapper = taskDefMapper;
        this.progressMapper = progressMapper;
        this.eventMapper = eventMapper;
        this.coinService = coinService;
        this.idGenerator = idGenerator;
    }

    // ==================== 买家侧 ====================

    /** 任务中心列表：启用的任务全部返回（未开始的任务 progress=0 也要出现） */
    public List<CoinTaskView> tasksOf(Long userId) {
        return taskDefMapper.selectList(Wrappers.<CoinTaskDefEntity>lambdaQuery()
                        .eq(CoinTaskDefEntity::getEnabled, true)
                        .orderByAsc(CoinTaskDefEntity::getSortNo)
                        .orderByAsc(CoinTaskDefEntity::getId))
                .stream().map(def -> viewOf(userId, def)).toList();
    }

    /**
     * 浏览类任务上报（浏览商品 / 逛分享圈）。
     *
     * <p>只接受这两个任务：其余任务由站内真实动作经内部接口驱动，
     * 让客户端直接上报会变成「自己给自己发币」的入口。
     */
    @Transactional
    public CoinTaskView report(Long userId, CoinTaskReportRequest request) {
        if (request == null) {
            throw BizException.validation("上报内容不能为空");
        }
        String taskCode = normalize(request.taskCode());
        if (taskCode.isEmpty()) {
            throw BizException.validation("taskCode 必填");
        }
        if (request.bizId() == null || request.bizId().isBlank()) {
            throw BizException.validation("bizId 必填（商品 ID / 内容 ID）");
        }
        Integer threshold = BROWSE_DWELL_SECONDS.get(taskCode);
        if (threshold == null) {
            throw BizException.businessRule("该任务不支持客户端上报：" + taskCode);
        }
        CoinTaskDefEntity def = requireEnabled(taskCode);
        int dwell = request.dwellSeconds() == null ? 0 : request.dwellSeconds();
        if (dwell < threshold) {
            throw BizException.businessRule("停留时长不足（" + threshold + " 秒），本次不计进度");
        }
        return recordProgress(userId, def, bizDateOf(def), request.bizId().trim());
    }

    /** 站内事件驱动（内部接口）：收藏 / 加购 / 晒单 / 评价 / 新人任务 */
    @Transactional
    public CoinTaskView handleEvent(Long userId, String event, String bizId) {
        String normalizedEvent = normalize(event);
        String taskCode = EVENT_TASKS.get(normalizedEvent);
        if (taskCode == null) {
            throw BizException.validation("不支持的任务事件：" + event);
        }
        CoinTaskDefEntity def = requireEnabled(taskCode);
        String actualBizId = bizId == null || bizId.isBlank() ? normalizedEvent : bizId.trim();
        return recordProgress(userId, def, bizDateOf(def), actualBizId);
    }

    // ==================== 运营端 ====================

    /** 任务定义全量（含停用），运营端配置页用 */
    public List<CoinTaskDefAdminView> defsForAdmin() {
        return taskDefMapper.selectList(Wrappers.<CoinTaskDefEntity>lambdaQuery()
                        .orderByAsc(CoinTaskDefEntity::getSortNo)
                        .orderByAsc(CoinTaskDefEntity::getId))
                .stream().map(this::toAdminView).toList();
    }

    /** 更新任务开关 / 奖励币数 / 进度上限（null 字段表示不改） */
    @Transactional
    public CoinTaskDefAdminView updateDef(String code, CoinTaskDefUpdateRequest request) {
        String normalized = normalize(code);
        CoinTaskDefEntity def = findDef(normalized);
        if (def == null) {
            throw BizException.notFound("任务定义（" + code + "）");
        }
        if (request == null) {
            throw BizException.validation("任务更新内容不能为空");
        }
        if (request.rewardCoins() != null) {
            if (request.rewardCoins() < 0) {
                throw BizException.validation("奖励币数不能为负数");
            }
            def.setRewardCoins(request.rewardCoins());
        }
        if (request.dailyCap() != null) {
            if (request.dailyCap() < 0) {
                throw BizException.validation("进度上限不能为负数");
            }
            def.setDailyCap(request.dailyCap());
        }
        if (request.enabled() != null) {
            def.setEnabled(request.enabled());
        }
        def.setUpdatedAt(nowUtc());
        taskDefMapper.updateById(def);
        log.info("多多币任务定义更新：code={} enabled={} reward={} cap={}",
                def.getCode(), def.getEnabled(), def.getRewardCoins(), def.getDailyCap());
        return toAdminView(def);
    }

    // ==================== 内部实现 ====================

    /**
     * 记一次任务事件并推进进度。
     *
     * <p>事件插入成功才推进进度（唯一键挡住同对象重复计数）；
     * 进度达上限后不再自增（条件 UPDATE 返回 0），但仍会把当前状态回给调用方。
     */
    private CoinTaskView recordProgress(Long userId, CoinTaskDefEntity def, LocalDate bizDate, String bizId) {
        LocalDateTime now = nowUtc();
        CoinTaskEventEntity event = new CoinTaskEventEntity();
        event.setId(idGenerator.nextId());
        event.setUserId(userId);
        event.setTaskCode(def.getCode());
        event.setBizDate(bizDate);
        event.setBizId(bizId);
        event.setCreatedAt(now);
        if (eventMapper.insertIgnore(event) > 0) {
            int cap = effectiveTarget(def);
            int inserted = 0;
            if (findProgress(userId, def.getCode(), bizDate) == null) {
                CoinTaskProgressEntity row = new CoinTaskProgressEntity();
                row.setId(idGenerator.nextId());
                row.setUserId(userId);
                row.setTaskCode(def.getCode());
                row.setBizDate(bizDate);
                row.setProgress(1);
                row.setRewarded(false);
                row.setRewardCoins(0);
                row.setCreatedAt(now);
                row.setUpdatedAt(now);
                // 并发首次上报：撞唯一键就当「行已存在」，转走条件自增
                inserted = progressMapper.insertIgnore(row) > 0 ? 1 : 0;
            }
            if (inserted == 0) {
                progressMapper.incrementProgress(userId, def.getCode(), bizDate, cap, now);
            }
            CoinTaskProgressEntity current = findProgress(userId, def.getCode(), bizDate);
            if (current != null && nz(current.getProgress()) >= cap
                    && !Boolean.TRUE.equals(current.getRewarded())) {
                award(userId, def, bizDate, bizId);
            }
        }
        return viewOf(userId, def);
    }

    /**
     * 发奖（在调用方事务内执行）：rewarded 0 → 1 条件更新只可能成功一次，
     * 成功后走 {@link CoinService#awardTaskCoins} 记 COIN_TASK 流水与 12 个月批次。
     * 任何一步失败整体回滚 —— 不存在「标了已领、币没到账」的中间态。
     */
    private boolean award(Long userId, CoinTaskDefEntity def, LocalDate bizDate, String bizId) {
        int coins = Math.max(0, nz(def.getRewardCoins()));
        if (progressMapper.markRewarded(userId, def.getCode(), bizDate, coins, nowUtc()) == 0) {
            return false;
        }
        if (coins <= 0) {
            log.warn("任务奖励币数为 0，仅标记已领取：code={} userId={}", def.getCode(), userId);
            return true;
        }
        CoinMutationResult result = coinService.awardTaskCoins(userId, def.getCode(), bizDate, coins);
        if (!result.applied()) {
            log.warn("任务奖励流水已存在（幂等命中）：code={} bizDate={} bizId={}", def.getCode(), bizDate, bizId);
        }
        log.info("多多币任务奖励：userId={} code={} bizDate={} coins={}", userId, def.getCode(), bizDate, coins);
        return true;
    }

    private CoinTaskDefEntity requireEnabled(String taskCode) {
        CoinTaskDefEntity def = findDef(taskCode);
        if (def == null) {
            throw BizException.businessRule("任务不存在：" + taskCode);
        }
        if (Boolean.FALSE.equals(def.getEnabled())) {
            throw BizException.businessRule("任务已停用：" + taskCode);
        }
        return def;
    }

    private CoinTaskDefEntity findDef(String taskCode) {
        if (taskCode == null || taskCode.isBlank()) {
            return null;
        }
        return taskDefMapper.selectOne(Wrappers.<CoinTaskDefEntity>lambdaQuery()
                .eq(CoinTaskDefEntity::getCode, taskCode)
                .last("LIMIT 1"));
    }

    private CoinTaskProgressEntity findProgress(Long userId, String taskCode, LocalDate bizDate) {
        return progressMapper.selectOne(Wrappers.<CoinTaskProgressEntity>lambdaQuery()
                .eq(CoinTaskProgressEntity::getUserId, userId)
                .eq(CoinTaskProgressEntity::getTaskCode, taskCode)
                .eq(CoinTaskProgressEntity::getBizDate, bizDate)
                .last("LIMIT 1"));
    }

    private CoinTaskView viewOf(Long userId, CoinTaskDefEntity def) {
        CoinTaskProgressEntity row = findProgress(userId, def.getCode(), bizDateOf(def));
        int progress = row == null ? 0 : nz(row.getProgress());
        boolean rewarded = row != null && Boolean.TRUE.equals(row.getRewarded());
        return new CoinTaskView(
                def.getCode(),
                def.getName(),
                def.getTaskType(),
                def.getActionType(),
                nz(def.getTargetCount()),
                progress,
                nz(def.getRewardCoins()),
                rewarded,
                progress >= effectiveTarget(def),
                def.getDescription());
    }

    private CoinTaskDefAdminView toAdminView(CoinTaskDefEntity def) {
        return new CoinTaskDefAdminView(
                String.valueOf(def.getId()),
                def.getCode(),
                def.getName(),
                def.getTaskType(),
                def.getActionType(),
                nz(def.getTargetCount()),
                nz(def.getRewardCoins()),
                nz(def.getDailyCap()),
                Boolean.TRUE.equals(def.getEnabled()),
                nz(def.getSortNo()),
                def.getDescription());
    }

    /** 业务日：日常任务取今天（Asia/Shanghai），新人任务固定哨兵日 */
    private static LocalDate bizDateOf(CoinTaskDefEntity def) {
        return CoinTaskDefEntity.TASK_TYPE_NEWCOMER.equals(def.getTaskType())
                ? NEWCOMER_BIZ_DATE
                : LocalDate.now(BIZ_ZONE);
    }

    /** 进度上限 = min(target_count, daily_cap)；daily_cap=0 时按 target_count */
    private static int effectiveTarget(CoinTaskDefEntity def) {
        int target = Math.max(1, nz(def.getTargetCount()));
        int cap = nz(def.getDailyCap());
        return cap > 0 ? Math.min(target, cap) : target;
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
