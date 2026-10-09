package com.duoduo.product.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 抽样强度状态（E28，单行表，id 固定 1）。
 *
 * <p>为什么比例要存在库里而不是配置里：附录 D 要求「抽中违规率连续 3 天超标 → 比例翻倍」。
 * 写在配置文件里，「自动收紧」就退化成「改配置 + 重启服务」—— 那不叫自动。
 * 放一行数据里，任务可以自己把它改掉，而且**改过什么看得见**（{@code tightenedAt}）。
 */
@TableName("t_audit_sample_rate")
public class AuditSampleRateEntity {

    /** 单行状态：固定 1（语义就是「全局唯一的一份」，给随机 id 会让人以为可以有第二行） */
    public static final long SINGLETON_ID = 1L;

    @TableId
    private Long id;
    private Integer sampleRatePercent;
    private LocalDateTime tightenedAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getSampleRatePercent() {
        return sampleRatePercent;
    }

    public void setSampleRatePercent(Integer sampleRatePercent) {
        this.sampleRatePercent = sampleRatePercent;
    }

    public LocalDateTime getTightenedAt() {
        return tightenedAt;
    }

    public void setTightenedAt(LocalDateTime tightenedAt) {
        this.tightenedAt = tightenedAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
