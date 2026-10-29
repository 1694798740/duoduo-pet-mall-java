package com.duoduo.ai.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 长期记忆（2026-10-28）。
 *
 * <p>只读优先：对话不为「写记忆」付出可用性代价；写失败只记日志。
 * 记忆来源必须是用户明确表达或档案事实，不做推测。
 */
@Service
public class LongTermMemoryService {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryService.class);

    private final AiUserMemoryMapper userMapper;
    private final AiPetMemoryMapper petMapper;
    private final ObjectMapper objectMapper;

    public LongTermMemoryService(AiUserMemoryMapper userMapper,
                                 AiPetMemoryMapper petMapper,
                                 ObjectMapper objectMapper) {
        this.userMapper = userMapper;
        this.petMapper = petMapper;
        this.objectMapper = objectMapper;
    }

    public SlotState loadSlots(Long userId) {
        if (userId == null) {
            return SlotState.empty();
        }
        try {
            AiUserMemoryEntity user = userMapper.selectOne(
                    new LambdaQueryWrapper<AiUserMemoryEntity>()
                            .eq(AiUserMemoryEntity::getUserId, userId)
                            .last("LIMIT 1"));
            List<AiPetMemoryEntity> pets = petMapper.selectList(
                    new LambdaQueryWrapper<AiPetMemoryEntity>()
                            .eq(AiPetMemoryEntity::getUserId, userId)
                            .orderByAsc(AiPetMemoryEntity::getId));
            AiPetMemoryEntity pet = pets.isEmpty() ? null : pets.get(0);
            List<String> allergens = new ArrayList<>();
            for (AiPetMemoryEntity p : pets) {
                allergens.addAll(jsonList(p.getAllergens()));
            }
            // 2026-10-29：高风险记忆（用户确认过的过敏原）也要进槽位。
            // 少了这一步，用户在记忆页填的「对鸡肉过敏」在对话里完全不生效 ——
            // 那是「保存成功但不工作」里最难被用户理解的一种。
            if (user != null) {
                allergens.addAll(jsonStringArray(user.getMemoryJson(), "allergens"));
            }

            // 健康备注：用户确认过的高风险记忆优先；没有时回落到宠物档案里的备注。
            // 两者都是「参考事实」，Prompt 会明确说明不能替代兽医诊断。
            String healthNotes = user == null
                    ? null : jsonStringField(user.getMemoryJson(), "healthNotes");
            if ((healthNotes == null || healthNotes.isBlank()) && pet != null) {
                healthNotes = pet.getHealthNotes();
            }
            String nickname = user == null ? null : jsonStringField(user.getMemoryJson(), "nickname");
            return new SlotState(
                    user == null ? (pet == null ? null : pet.getSpecies())
                            : firstText(user.getPreferredSpecies(), pet == null ? null : pet.getSpecies()),
                    pet == null ? null : pet.getLifeStage(),
                    pet == null ? null : pet.getBreed(),
                    user == null ? null : user.getBudgetMinCents(),
                    user == null ? null : user.getBudgetMaxCents(),
                    null,
                    allergens.stream().distinct().toList(),
                    List.of(),
                    "NORMAL",
                    healthNotes,
                    nickname);
        } catch (Exception e) {
            log.warn("长期记忆读取失败 userId={}: {}", userId, e.getMessage());
            return SlotState.empty();
        }
    }

    /** 只把本轮明确表达的预算/物种写回；分类、品牌等留给后续运营配置。 */
    public void remember(Long userId, SlotState slots) {
        if (userId == null || slots == null) {
            return;
        }
        try {
            AiUserMemoryEntity entity = userMapper.selectOne(
                    new LambdaQueryWrapper<AiUserMemoryEntity>()
                            .eq(AiUserMemoryEntity::getUserId, userId)
                            .last("LIMIT 1"));
            boolean insert = entity == null;
            if (insert) {
                entity = new AiUserMemoryEntity();
                entity.setId(IdWorker.getId());
                entity.setUserId(userId);
            }
            if (slots.species() != null && !slots.species().isBlank()) {
                entity.setPreferredSpecies(slots.species());
            }
            if (slots.budgetMinCents() != null) {
                entity.setBudgetMinCents(slots.budgetMinCents());
            }
            if (slots.budgetMaxCents() != null) {
                entity.setBudgetMaxCents(slots.budgetMaxCents());
            }
            entity.setConsentVersion("v1");
            if (insert) {
                userMapper.insert(entity);
            } else {
                userMapper.updateById(entity);
            }
        } catch (Exception e) {
            log.warn("长期记忆写入失败 userId={}: {}", userId, e.getMessage());
        }
    }

    private List<String> jsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 从记忆快照（对象 JSON）里取一个字符串数组字段，例如 {@code allergens}。 */
    private List<String> jsonStringArray(String json, String field) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(json).path(field);
            if (!node.isArray()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            node.forEach(item -> {
                String value = item == null ? "" : item.asText("").trim();
                if (!value.isEmpty()) {
                    out.add(value);
                }
            });
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 从记忆快照里取一个字符串字段（如 {@code healthNotes}）。 */
    private String jsonStringField(String json, String field) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(json).path(field);
            if (!node.isTextual()) {
                return null;
            }
            String value = node.asText("").trim();
            return value.isEmpty() ? null : value;
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstText(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
