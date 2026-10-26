package com.duoduo.product.dto;

import java.util.List;

/**
 * 商品审核历史（A-08）的读模型。
 *
 * <p>与前端的契约刻意保持「已翻译」：事件与操作人类型都带中文 label，
 * 审核台不需要再维护一份与后端枚举同步的映射表（那种映射一旦漂移，
 * 界面上就会出现一串 SUBMIT / SELLER 之类的原始值）。
 */
public final class SpuAuditDtos {

    private SpuAuditDtos() {
    }

    /**
     * 一个字段的变更。
     *
     * @param before    变更前的展示值（可为 null；详情类字段已转纯文本）
     * @param truncated 该值超过 2000 码点被截断
     */
    public record FieldChange(
            String field,
            String label,
            String before,
            String after,
            boolean truncated
    ) {
    }

    /**
     * 一条审核历史事件。
     *
     * <p>雪花 id 一律字符串：JS 的 Number 只有 53 位精度，19 位 id 直接比较会串号。
     */
    public record SpuAuditLogView(
            String id,
            String event,
            String eventLabel,
            String actorType,
            String actorLabel,
            String actorId,
            String actorPhone,
            String fromAuditStatus,
            String toAuditStatus,
            String auditSource,
            String reason,
            Integer versionBefore,
            Integer versionAfter,
            Integer sampledVersion,
            List<FieldChange> changes,
            String createdAt
    ) {
    }

    /**
     * H-03 · 跨商品变更历史的一条（运营后台独立页）。
     *
     * <p>与 {@link SpuAuditLogView} 的区别：那条只属于某个商品的详情时间线；
     * 这一条额外带商品 id / 标题 / 归属店铺，才能在没有「先选商品」的情况下展示与检索。
     */
    public record SpuChangeHistoryItem(
            String id,
            String spuId,
            String spuTitle,
            String sellerId,
            String event,
            String eventLabel,
            String actorType,
            String actorLabel,
            String actorId,
            String actorPhone,
            String fromAuditStatus,
            String toAuditStatus,
            String reason,
            Integer versionBefore,
            Integer versionAfter,
            List<FieldChange> changes,
            String createdAt
    ) {
    }
}
