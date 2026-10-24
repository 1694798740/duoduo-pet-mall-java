package com.duoduo.product.dto;

import java.util.List;

/**
 * 审核 SLA 的接口契约（E28）。
 *
 * <p>单独一个文件而不是塞进 {@code ProductDtos}：这一组 DTO 服务的是「事后抽查」这条
 * 与商品主流程平行的链路，混进去会让那个已经很大的类继续膨胀，
 * 而读代码的人找「抽查返回什么」时得先在商品 DTO 里翻半天。
 *
 * <p><b>所有 id 一律是 String</b>：雪花 id 超出 JS 的安全整数范围，
 * 前端 {@code Number()} 一次就会丢精度（本项目已在这上面栽过，见 id-precision 冒烟）。
 * 这条与 ProductDtos 同口径。
 */
public final class AuditSlaDtos {

    private AuditSlaDtos() {
    }

    /**
     * 一条抽查任务（运营在队列里看到的东西）。
     *
     * <p>{@code spuTitle} / {@code sellerId} 是**冗余带出**的：队列要一眼看出
     * 「这是哪件商品、谁的」，让前端为每一行再查一次商品与店铺，就是把一次列表请求
     * 变成 1 + N 次请求。
     */
    public record AuditSampleView(
            String id,
            String bizType,
            String bizId,
            String sellerId,
            String sampleDate,
            String reason,
            /** 抽中理由的中文说明（给运营看；不传枚举名让前端自己翻译） */
            String reasonLabel,
            String status,
            String spuTitle,
            String reviewNote,
            String reviewedBy,
            String reviewedAt,
            String createdAt,
            /** 从建任务到复核完成的小时数；未复核为 null */
            Long responseHours,
            /** 抽中时的商品版本快照（A-08）；null = 旧任务未记录，不做漂移判断 */
            Integer sampledVersion,
            /** 商品当前版本；商品已不存在时为 null */
            Integer currentVersion,
            /**
             * 抽中后卖家又改过内容（sampledVersion != currentVersion）。
             * 只提示、不阻断复核：运营据此判断「眼前的内容是否还是抽中时那一版」。
             */
            boolean drift
    ) {
    }

    public record ReviewRequest(String action, String note) {
    }

    /** 一轮抽查的结果（供日志与手工触发时回显） */
    public record SampleReport(int candidates, int created, int skipped, int ratePercent,
                               List<String> reasons) {
    }

    /**
     * SLA 自证指标（附录 D 第五节）。
     *
     * <p>{@code tightened} 表示「本轮评估已经把抽样比例翻倍了」——
     * 不暴露这个，运营看到比例从 5 变 10 只会以为是配置被改了。
     */
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
