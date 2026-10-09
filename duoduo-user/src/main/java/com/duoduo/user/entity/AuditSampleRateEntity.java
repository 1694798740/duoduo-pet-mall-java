package com.duoduo.user.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 抽样强度状态（E28，单行表，id 固定 1）。
 *
 * <p>放在库里而不是配置里，是为了让「连续超标自动收紧」真的能自动执行 ——
 * 写配置就退化成「改配置 + 重启」。与商品库同名表结构一致、各自维护：
 * 两边的放行量级差着数量级，共用一个比例只会让其中一边过松或过紧。
 */
@TableName("t_audit_sample_rate")
public class AuditSampleRateEntity {

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
