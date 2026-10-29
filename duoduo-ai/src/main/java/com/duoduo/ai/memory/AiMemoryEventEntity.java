package com.duoduo.ai.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 记忆变更流水（表 {@code t_ai_memory_event}）。
 *
 * <p>记忆会被注入提示词，属于**敏感派生数据**：只存最终态等于放弃了「为什么变成这样」。
 * 每次写入/覆盖/删除都追加一条，旧值与新值都留档，用户申诉与运营排查都靠它。
 */
@TableName("t_ai_memory_event")
public class AiMemoryEventEntity {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long userId;

    /** PREFERENCE / HEALTH / PET … */
    private String memoryType;

    /** preferredSpecies / budget / allergens / healthNotes … */
    private String memoryKey;

    /** JSON：变更前的值（首次写入为 null） */
    private String oldValue;

    /** JSON：变更后的值（删除时为 null） */
    private String newValue;

    /** CHAT / PROFILE / TOOL / ADMIN / MIGRATION */
    private String source;

    /** USER / SYSTEM / ADMIN */
    private String actor;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getMemoryType() {
        return memoryType;
    }

    public void setMemoryType(String memoryType) {
        this.memoryType = memoryType;
    }

    public String getMemoryKey() {
        return memoryKey;
    }

    public void setMemoryKey(String memoryKey) {
        this.memoryKey = memoryKey;
    }

    public String getOldValue() {
        return oldValue;
    }

    public void setOldValue(String oldValue) {
        this.oldValue = oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public void setNewValue(String newValue) {
        this.newValue = newValue;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
