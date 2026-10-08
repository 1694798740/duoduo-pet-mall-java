package com.duoduo.user.support;

import java.util.Set;

/**
 * 入驻申请状态机（E10）。
 *
 * <h2>流转图</h2>
 * <pre>
 *  DRAFT ──submit──▶ SUBMITTED ──start-review──▶ REVIEWING ──approve──▶ APPROVED
 *                      ▲                            │
 *                      │                         reject（结构化原因）
 *                      └─────────resubmit──────── REJECTED
 *  APPROVED ──suspend（E15：资质过期/违规）──▶ SUSPENDED
 * </pre>
 *
 * <h2>三条不显然的规则（写在这里避免被"优化"掉）</h2>
 * <ol>
 *   <li><b>REJECTED → SUBMITTED 允许</b>（重提是闭环的一半）：驳回不是终态，
 *       否则被驳回的商家永远进不来，只会去重新注册账号绕审核 —— 那更糟。</li>
 *   <li><b>DRAFT/REJECTED 之外一律不能改材料</b>：SUBMITTED 之后材料就是"送审的样本"，
 *       商家若能边审边换图，审核结论与材料就失去对应关系。</li>
 *   <li><b>approve/reject 幂等</b>：对已产生结论的申请重复执行同一结论 → 返回现状不报错
 *       （运营双击、网络重试都会发生）；对结论不同的操作（APPROVED 后 reject）→ 422。</li>
 * </ol>
 */
public enum MerchantStatus {

    DRAFT,
    SUBMITTED,
    REVIEWING,
    APPROVED,
    REJECTED,
    SUSPENDED;

    /** 提交：只有草稿与被驳回的申请可以进入送审 */
    public boolean canSubmit() {
        return this == DRAFT || this == REJECTED;
    }

    /** 送审后材料与主体信息冻结（驳回后解冻，允许修改后重提） */
    public boolean canEditMaterials() {
        return this == DRAFT || this == REJECTED;
    }

    /** 是否已有审核结论（幂等判断用：重复执行同一结论放行，结论不同的操作拒绝） */
    public boolean hasDecision() {
        return this == APPROVED || this == REJECTED;
    }

    /** 审核起点：只有 SUBMITTED 能开始审核 */
    public static final Set<MerchantStatus> REVIEWABLE = Set.of(SUBMITTED, REVIEWING);
}
