package com.duoduo.ai.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.duoduo.ai.dto.AiMemoryDtos.ConfirmTicket;
import com.duoduo.ai.dto.AiMemoryDtos.HighRiskConfirmRequest;
import com.duoduo.ai.dto.AiMemoryDtos.HighRiskUpdate;
import com.duoduo.ai.dto.AiMemoryDtos.LowRiskUpdate;
import com.duoduo.ai.dto.AiMemoryDtos.MemoryView;
import com.duoduo.common.exception.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * 长期记忆管理（2026-10-29 · 第二阶段）。
 *
 * <h2>两级风险</h2>
 * <ul>
 *   <li><b>低风险</b>（物种、预算、偏好类目/品牌）：对话里自动沉淀，接口直接改，用户可查看/删除；</li>
 *   <li><b>高风险</b>（过敏原、健康备注）：必须由用户显式确认。确认令牌绑定
 *       **规范化后的具体内容**，有效期 5 分钟、一次性消费 —— 这样「确认」不会变成
 *       「确认了一个可以被替换的请求」。</li>
 * </ul>
 *
 * <h2>审计</h2>
 * 每次变更写 {@code t_ai_memory_event}：谁、从什么来源、把哪个键从什么值改成了什么值。
 * 记忆会被注入提示词，没有流水的记忆等于无法解释的个性化。
 *
 * <h2>写入失败的口径</h2>
 * 这里的写是**用户主动发起的**请求，失败必须如实报错（与对话里的 best-effort 相反）：
 * 用户点了保存却没保存上，静默成功比报错更糟。
 */
@Service
public class AiMemoryService {

    private static final Logger log = LoggerFactory.getLogger(AiMemoryService.class);

    private static final int MAX_LIST_ITEMS = 10;
    private static final int MAX_ITEM_LENGTH = 20;
    private static final int MAX_HEALTH_NOTES = 500;
    private static final int MAX_NICKNAME = 20;
    private static final int CONFIRM_EXPIRE_SECONDS = 300;

    /** 与商品/槽位同一套物种枚举；非法值拒绝，不静默落库。 */
    private static final Set<String> SPECIES = Set.of(
            "cat", "dog", "small_pet", "bird", "fish", "reptile", "other");

    private static final String ACTION_HIGH_RISK = "MEMORY_HIGH_RISK";
    private static final String MEMORY_PREFERENCE = "PREFERENCE";
    private static final String MEMORY_HEALTH = "HEALTH";

    private final AiUserMemoryMapper userMapper;
    private final AiMemoryEventMapper eventMapper;
    private final ConfirmService confirmService;
    private final ObjectMapper mapper;
    private final Executor auditExecutor;

    public AiMemoryService(AiUserMemoryMapper userMapper,
                           AiMemoryEventMapper eventMapper,
                           ConfirmService confirmService,
                           ObjectMapper mapper,
                           @Qualifier("aiAuditExecutor") Executor auditExecutor) {
        this.userMapper = userMapper;
        this.eventMapper = eventMapper;
        this.confirmService = confirmService;
        this.mapper = mapper;
        this.auditExecutor = auditExecutor;
    }

    /** 读取当前记忆；没有记录时返回空视图（不是 404：新用户本来就没有记忆）。 */
    public MemoryView view(Long userId) {
        return toView(find(userId));
    }

    /** 低风险更新：只覆盖请求里非空的字段，未传的保持原值。 */
    public MemoryView updateLowRisk(Long userId, LowRiskUpdate request) {
        if (request == null) {
            throw BizException.validation("缺少记忆内容");
        }
        LowRiskUpdate normalized = normalizeLowRisk(request);

        AiUserMemoryEntity row = find(userId);
        boolean insert = row == null;
        if (insert) {
            row = newEntity(userId);
        }
        AiUserMemoryEntity before = copyOf(row);

        // 称呼与高风险快照共用 memory_json：它是单个对象，不能一半写列、一半写 JSON
        MemorySnapshot snapshot = parseSnapshot(row.getMemoryJson());

        if (normalized.species() != null) {
            row.setPreferredSpecies(normalized.species());
        }
        if (normalized.budgetMinCents() != null) {
            row.setBudgetMinCents(normalized.budgetMinCents());
        }
        if (normalized.budgetMaxCents() != null) {
            row.setBudgetMaxCents(normalized.budgetMaxCents());
        }
        if (normalized.categories() != null) {
            row.setPreferredCategories(json(normalized.categories()));
        }
        if (normalized.brands() != null) {
            row.setPreferredBrands(json(normalized.brands()));
        }
        String nextNickname = normalized.nickname() != null ? normalized.nickname() : snapshot.nickname();
        row.setMemoryJson(json(new MemorySnapshot(nextNickname, snapshot.allergens(), snapshot.healthNotes())));
        row.setConsentVersion("v1");
        save(row, insert);

        recordChanges(userId, before, row, MEMORY_PREFERENCE);
        recordIfChanged(userId, MEMORY_PREFERENCE, "nickname", snapshot.nickname(), nextNickname);
        return toView(row);
    }

    /** 删除记忆（幂等）：没有记录也返回成功，用户点两次不会看到「记录不存在」。 */
    public void delete(Long userId) {
        AiUserMemoryEntity row = find(userId);
        if (row == null) {
            return;
        }
        userMapper.deleteById(row.getId());
        event(userId, MEMORY_HEALTH, "all", json(row), null);
    }

    /**
     * 申请高风险写入：校验内容 → 签发一次性确认令牌。
     *
     * <p>令牌与内容绑定：用户确认的是「鸡肉、牛肉 + 这段健康备注」这一组具体值。
     */
    public ConfirmTicket requestHighRisk(Long userId, HighRiskUpdate request) {
        HighRiskUpdate normalized = normalizeHighRisk(request);
        JsonNode payload = mapper.valueToTree(normalized);
        String token = confirmService.issue(userId, ACTION_HIGH_RISK, payload);
        return new ConfirmTicket(token, CONFIRM_EXPIRE_SECONDS, normalized,
                "过敏原与健康信息属于高风险记忆，确认后才会写入");
    }

    /** 确认高风险写入：令牌一次性消费，内容必须与申请时一致。 */
    public MemoryView confirmHighRisk(Long userId, HighRiskConfirmRequest request) {
        if (request == null || request.token() == null || request.token().isBlank()) {
            throw BizException.validation("缺少确认令牌");
        }
        HighRiskUpdate normalized = normalizeHighRisk(
                new HighRiskUpdate(request.allergens(), request.healthNotes()));
        JsonNode payload = mapper.valueToTree(normalized);
        if (!confirmService.consume(userId, request.token(), ACTION_HIGH_RISK, payload)) {
            throw BizException.validation("确认已过期或内容已变更，请重新申请");
        }

        AiUserMemoryEntity row = find(userId);
        boolean insert = row == null;
        if (insert) {
            row = newEntity(userId);
        }
        MemorySnapshot snapshot = parseSnapshot(row.getMemoryJson());
        String before = row.getMemoryJson();
        // 高风险确认只覆盖过敏原与备注，称呼保持不变
        row.setMemoryJson(json(new MemorySnapshot(
                snapshot.nickname(), normalized.allergens(), normalized.healthNotes())));
        row.setConsentVersion("v1");
        save(row, insert);

        event(userId, MEMORY_HEALTH, "allergens+healthNotes", before, row.getMemoryJson());
        return toView(row);
    }

    // ---------------- 校验与归一 ----------------

    private LowRiskUpdate normalizeLowRisk(LowRiskUpdate request) {
        String species = trimToNull(request.species());
        if (species != null) {
            species = species.toLowerCase(Locale.ROOT);
            if (!SPECIES.contains(species)) {
                throw BizException.validation("物种不合法：" + request.species());
            }
        }
        Long min = request.budgetMinCents();
        Long max = request.budgetMaxCents();
        if (min != null && min < 0 || max != null && max < 0) {
            throw BizException.validation("预算不能为负数");
        }
        if (min != null && max != null && min > max) {
            throw BizException.validation("预算下限不能大于上限");
        }
        return new LowRiskUpdate(
                species,
                min,
                max,
                normalizeItems(request.categories(), "偏好类目"),
                normalizeItems(request.brands(), "偏好品牌"),
                normalizeNickname(request.nickname()));
    }

    /** 称呼：低风险，但同样限长；空串表示「不设置称呼」。 */
    private static String normalizeNickname(String nickname) {
        String value = trimToNull(nickname);
        if (value != null && value.length() > MAX_NICKNAME) {
            throw BizException.validation("称呼不能超过 " + MAX_NICKNAME + " 字");
        }
        return value;
    }

    private HighRiskUpdate normalizeHighRisk(HighRiskUpdate request) {
        HighRiskUpdate source = request == null ? new HighRiskUpdate(null, null) : request;
        List<String> allergens = normalizeItems(source.allergens(), "过敏原");
        String notes = trimToNull(source.healthNotes());
        if (notes != null && notes.length() > MAX_HEALTH_NOTES) {
            throw BizException.validation("健康备注不能超过 " + MAX_HEALTH_NOTES + " 字");
        }
        return new HighRiskUpdate(allergens == null ? List.of() : allergens, notes);
    }

    /** 去重、去空白、限长；null 表示「本次不修改」，空列表表示「清空」。 */
    private static List<String> normalizeItems(List<String> items, String label) {
        if (items == null) {
            return null;
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String item : items) {
            String value = trimToNull(item);
            if (value == null) {
                continue;
            }
            if (value.length() > MAX_ITEM_LENGTH) {
                throw BizException.validation(label + "单项不能超过 " + MAX_ITEM_LENGTH + " 字");
            }
            unique.add(value);
        }
        if (unique.size() > MAX_LIST_ITEMS) {
            throw BizException.validation(label + "最多 " + MAX_LIST_ITEMS + " 项");
        }
        return List.copyOf(unique);
    }

    // ---------------- 存储 ----------------

    private AiUserMemoryEntity find(Long userId) {
        return userMapper.selectOne(new LambdaQueryWrapper<AiUserMemoryEntity>()
                .eq(AiUserMemoryEntity::getUserId, userId)
                .last("LIMIT 1"));
    }

    private static AiUserMemoryEntity newEntity(Long userId) {
        AiUserMemoryEntity row = new AiUserMemoryEntity();
        row.setId(IdWorker.getId());
        row.setUserId(userId);
        return row;
    }

    private void save(AiUserMemoryEntity row, boolean insert) {
        row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        if (insert) {
            userMapper.insert(row);
        } else {
            userMapper.updateById(row);
        }
    }

    private MemoryView toView(AiUserMemoryEntity row) {
        if (row == null) {
            return new MemoryView(null, null, null, null,
                    List.of(), List.of(), List.of(), null, null, null, null);
        }
        MemorySnapshot snapshot = parseSnapshot(row.getMemoryJson());
        return new MemoryView(
                row.getUserId(),
                row.getPreferredSpecies(),
                row.getBudgetMinCents(),
                row.getBudgetMaxCents(),
                parseList(row.getPreferredCategories()),
                parseList(row.getPreferredBrands()),
                snapshot.allergens(),
                snapshot.healthNotes(),
                row.getConsentVersion(),
                row.getUpdatedAt() == null ? null
                        : row.getUpdatedAt().toInstant(ZoneOffset.UTC).toString(),
                snapshot.nickname());
    }

    private MemorySnapshot parseSnapshot(String json) {
        if (json == null || json.isBlank()) {
            return MemorySnapshot.empty();
        }
        try {
            MemorySnapshot value = mapper.readValue(json, MemorySnapshot.class);
            if (value == null) {
                return MemorySnapshot.empty();
            }
            return new MemorySnapshot(
                    value.nickname(),
                    value.allergens() == null ? List.of() : value.allergens(),
                    value.healthNotes());
        } catch (Exception e) {
            log.warn("记忆快照解析失败，按空处理: {}", e.getMessage());
            return MemorySnapshot.empty();
        }
    }

    /**
     * 记忆快照（memory_json 的结构）。
     *
     * <p>为什么称呼与高风险项放在同一个对象：它们同属「用户确认过的派生数据」，
     * 两个 JSON 列会让「读一次记忆」变成两次解析；合并后解析失败时也能整块降级。
     */
    private record MemorySnapshot(String nickname, List<String> allergens, String healthNotes) {
        private static MemorySnapshot empty() {
            return new MemorySnapshot(null, List.of(), null);
        }
    }

    private List<String> parseList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, mapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("记忆序列化失败", e);
        }
    }

    private static AiUserMemoryEntity copyOf(AiUserMemoryEntity row) {
        AiUserMemoryEntity copy = new AiUserMemoryEntity();
        copy.setId(row.getId());
        copy.setUserId(row.getUserId());
        copy.setPreferredSpecies(row.getPreferredSpecies());
        copy.setPreferredCategories(row.getPreferredCategories());
        copy.setPreferredBrands(row.getPreferredBrands());
        copy.setBudgetMinCents(row.getBudgetMinCents());
        copy.setBudgetMaxCents(row.getBudgetMaxCents());
        copy.setConsentVersion(row.getConsentVersion());
        copy.setMemoryJson(row.getMemoryJson());
        return copy;
    }

    // ---------------- 审计 ----------------

    private void recordChanges(Long userId, AiUserMemoryEntity before, AiUserMemoryEntity after,
                               String memoryType) {
        recordIfChanged(userId, memoryType, "preferredSpecies",
                before.getPreferredSpecies(), after.getPreferredSpecies());
        recordIfChanged(userId, memoryType, "budget",
                budgetOf(before), budgetOf(after));
        recordIfChanged(userId, memoryType, "preferredCategories",
                before.getPreferredCategories(), after.getPreferredCategories());
        recordIfChanged(userId, memoryType, "preferredBrands",
                before.getPreferredBrands(), after.getPreferredBrands());
    }

    private static String budgetOf(AiUserMemoryEntity row) {
        if (row.getBudgetMinCents() == null && row.getBudgetMaxCents() == null) {
            return null;
        }
        return row.getBudgetMinCents() + "~" + row.getBudgetMaxCents();
    }

    private void recordIfChanged(Long userId, String type, String key,
                                 String oldValue, String newValue) {
        if (Objects.equals(oldValue, newValue)) {
            return;
        }
        event(userId, type, key, oldValue, newValue);
    }

    /**
     * 写一条记忆流水。异步 + 失败只记日志：记忆已经落库，审计失败不能把
     * 用户的保存动作判成失败（与对话链路的 best-effort 同一口径）。
     */
    private void event(Long userId, String memoryType, String key,
                       String oldValue, String newValue) {
        String oldJson = oldValue == null ? null : quote(oldValue);
        String newJson = newValue == null ? null : quote(newValue);
        try {
            auditExecutor.execute(() -> {
                try {
                    AiMemoryEventEntity entity = new AiMemoryEventEntity();
                    entity.setId(IdWorker.getId());
                    entity.setUserId(userId);
                    entity.setMemoryType(memoryType);
                    entity.setMemoryKey(key);
                    entity.setOldValue(oldJson);
                    entity.setNewValue(newJson);
                    entity.setSource("API");
                    entity.setActor("USER");
                    eventMapper.insert(entity);
                } catch (Exception e) {
                    log.warn("记忆流水写入失败 userId={} key={}: {}", userId, key, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("记忆流水任务被拒绝 userId={} key={}: {}", userId, key, e.getMessage());
        }
    }

    /**
     * 旧值可能是裸字符串（列值），也可能已经是 JSON（快照）。
     * JSON 列要求合法文档，所以统一包成 JSON 字符串；已经合法的 JSON 原样保留。
     */
    private String quote(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                mapper.readTree(trimmed);
                return trimmed;
            } catch (Exception ignored) {
                // 不是合法 JSON，按字符串处理
            }
        }
        return json(raw);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
