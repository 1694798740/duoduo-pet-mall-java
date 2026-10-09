package com.duoduo.user.dto;

/**
 * 入驻侧审核 SLA 的接口契约（E28）。
 *
 * <p>与商品侧 {@code AuditSlaDtos} 分开：两边服务的对象不同（一个是店铺、一个是商品），
 * 共用一份 DTO 就得塞进一堆「这个字段只对 X 有意义」的注释，读代码的人迟早会传错。
 */
public final class MerchantAuditSlaDtos {

    private MerchantAuditSlaDtos() {
    }

    /** 一条入驻抽查任务 */
    public record AuditSampleView(
            String id,
            String bizType,
            String bizId,
            String sampleDate,
            String reason,
            String reasonLabel,
            String status,
            /** 店铺名（冗余带出，避免前端为每一行再查一次） */
            String storeName,
            String reviewNote,
            String reviewedBy,
            String reviewedAt,
            String createdAt,
            Long responseHours
    ) {
    }

    public record ReviewRequest(String action, String note) {
    }

    public record SampleReport(int candidates, int created, int skipped, int ratePercent) {
    }

    /** SLA 自证指标（口径与商品侧一致：违规率 / 平均复核时长 / 误判率） */
    public record AuditMetrics(
            int windowDays,
            int sampled,
            int violated,
            int violationPercent,
            int passed,
            int avgResponseHours,
            int falsePositivePercent,
            int currentRatePercent,
            boolean tightened
    ) {
    }
}
