package com.duoduo.product.dto;

/**
 * C7 · 账号注销时商品域的个人数据清理结果（服务间契约）。
 *
 * <p>调用方是用户域的注销编排（先五域清理、全部成功后才把 {@code t_user} 置
 * {@code DELETED}，见 {@code docs/account-deletion-design.md} 第三节）。
 * 本记录同时服务两种模式：
 * <ul>
 *   <li><b>预检（dryRun=true）</b>：只做 SELECT COUNT，不产生任何写。
 *       用于「申请注销」时判断有没有在售闲置要把注销挡下；</li>
 *   <li><b>执行（dryRun 缺省 / false）</b>：真正删该用户已下架的闲置。</li>
 * </ul>
 * 两个模式的数字口径逐字一致（同一个 WHERE 条件，COUNT 与 DELETE 对同一批行生效）。
 *
 * <h2>三个计数分别是什么</h2>
 * <ul>
 *   <li>{@code deleted} —— 物理删除的行数：{@code status = OFF_SHELF} 的
 *       {@code t_listing}（按 {@code seller_id}）。已下架的闲置对买家不可见，
 *       行里剩下的标题 / 描述 / 图片是纯个人内容，随注销一起走；
 *       连软删行（{@code deleted_at} 非空）一并物理删 —— 账号注销是终态清理，
 *       软删只是日常下架的留痕手段；</li>
 *   <li>{@code masked} —— 恒为 0：本域没有「要留但含 PII」的数据。
 *       在售（{@code ON_SALE}）与已售出（{@code SOLD}）的行<b>刻意不动</b>：
 *       前者会被调用方的阻塞预检挡下、走不到执行；后者关联着真实订单，
 *       物理删会让别人的订单详情出现「查无此物」（同软删设计的理由）；</li>
 *   <li>{@code blocking} —— <b>会导致注销被挡下的行数</b>：在售闲置数
 *       （{@code status = ON_SALE}）。卖家有在售商品时不能注销账号，
 *       调用方据此返回 409 并提示先下架。</li>
 * </ul>
 *
 * <h2>幂等</h2>
 * DELETE 是幂等语句：重复调用不报错、不二次破坏，第二次命中 0 行、数字归零 ——
 * 注销调度「失败重试、断点恢复」依赖这一点（设计文档第六节）。
 *
 * @param userId   被注销的账号 id（= 卖家 {@code seller_id}，服务间调用，参数即被操作对象）
 * @param domain   域标识，固定 {@code "product"}
 * @param deleted  物理删除的行数（dryRun 时为预估）
 * @param masked   脱敏的行数（本域恒 0）
 * @param blocking 会阻塞注销的行数（dryRun 时同样只查不写）
 */
public record PersonalDataCleanupResult(Long userId,
                                        String domain,
                                        int deleted,
                                        int masked,
                                        int blocking) {
}
