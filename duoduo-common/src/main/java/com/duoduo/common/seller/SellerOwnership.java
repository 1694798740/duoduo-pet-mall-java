package com.duoduo.common.seller;

/**
 * 卖家归属语义的**单一权威**（E9）。
 *
 * <h2>为什么需要它</h2>
 * 在引入 {@code t_merchant} 之前，商品/交易/履约/社交四个域都直接写
 * {@code sellerId == 0} 这个字面量来表达"平台自营"—— 同一个业务事实
 * （自营的租户标识是 0）散落成十几处魔法数。它的问题不是"能跑不能跑"，
 * 而是**语义不可搜索、口径不可审计**：将来 E10/E14 给第三方商品加上真实
 * 归属 id 后，谁也无法保证没人把某个"刚好是 0"的默认值误当成自营。
 *
 * <h2>口径（本类就是口径本身）</h2>
 * <ul>
 *   <li><b>平台自营</b>：{@code sellerId == 0}。自营不入驻（t_merchant 只管第三方），
 *       因此自营在租户体系里是"没有行的租户"—— 用保留值 0 表达，既不动存量数据，
 *       也不必在 t_merchant 里为它开"唯一店主/审核状态机"这些第三方专属约束的例外。</li>
 *   <li><b>第三方商家</b>：{@code sellerId > 0}，值即<b>店铺主体 userId</b>
 *       （= {@code t_merchant.owner_user_id}，E10 起由入驻流程产生）。</li>
 * </ul>
 *
 * <h2>★ 口径纠正（E20）</h2>
 * 本类此前写的是"值即 {@code t_merchant.id}"，<b>那是错的</b>：两处都是
 * bigint + 雪花 id，写反了不会报任何错，只会让按租户键做的过滤
 * "过滤掉不该过滤的"或"什么都没过滤掉"。库内实测（2026-10-10，抽样 8 条
 * 非 0 的 {@code t_audit_log.tenant_id}）：命中 {@code t_user.id} <b>8/8</b>、
 * 命中 {@code t_merchant.id} <b>0/8</b>；{@code t_spu.seller_id}
 * （如 {@code 367208473877942272}）即对应店铺的 {@code owner_user_id}。
 * 三处代码（建 SPU / 建抽查任务 / 种子回填脚本）本来就是按 owner userId 写的，
 * 只有注释说反了 —— 所以这是**注释与代码各说一套**，不是代码 bug。
 * E20 起"租户键 = 店铺主体 userId"同时是 {@link com.duoduo.common.tenant.TenantContext}
 * 与 {@link com.duoduo.common.tenant.TenantTables} 的口径，三处必须一致。
 *
 * <h2>改动纪律</h2>
 * 四个域（product / trade / fulfilment / social）的既有判断已全部改为引用本类；
 * **今后任何新代码不得再写 {@code sellerId == 0} 字面量**，一律用
 * {@link #isSelfSeller(Long)}。这是"把特例集中到单一策略类"的落点 ——
 * 集中之后才有资格谈"将来收敛为租户判定"。
 *
 * <p>E20 就是那个"将来"：租户维度已收敛为 {@code TenantContext} + {@code TenantTables}，
 * 但<b>本类仍是"自营 = 0"这个事实的唯一出处</b>（{@code TenantTables} 只描述"列在哪"，
 * 不描述"谁是自营"）。两者的分工：本类管**值域与语义**，租户包管**传播与过滤**。
 */
public final class SellerOwnership {

    /** 平台自营的租户标识：既有数据口径（t_spu.seller_id = 0），保持不变 */
    public static final long SELF_SELLER_ID = 0L;

    private SellerOwnership() {
    }

    /** 是否平台自营（sellerId 为 0 或 null —— 快照缺归属时按 E 口径回退自营） */
    public static boolean isSelfSeller(Long sellerId) {
        return sellerId == null || sellerId == SELF_SELLER_ID;
    }

    /** 是否第三方商家租户 */
    public static boolean isMerchantSeller(Long sellerId) {
        return !isSelfSeller(sellerId);
    }
}
