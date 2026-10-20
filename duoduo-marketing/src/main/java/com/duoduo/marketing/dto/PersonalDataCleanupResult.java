package com.duoduo.marketing.dto;

/**
 * C7 · 账号注销时营销域的个人数据清理结果（服务间契约）。
 *
 * <p>调用方是用户域的注销编排（先五域清理、全部成功后才把 {@code t_user} 置
 * {@code DELETED}，见 {@code docs/account-deletion-design.md} 第三节）。
 * 本记录同时服务两种模式：
 * <ul>
 *   <li><b>预检（dryRun=true）</b>：只做 SELECT COUNT，不产生任何写。
 *       用于「申请注销」时判断有没有未完成的业务要把注销挡下；</li>
 *   <li><b>执行（dryRun 缺省 / false）</b>：真正删未使用 / 已锁定 / 已过期的券。</li>
 * </ul>
 * 两个模式的数字口径逐字一致（同一个 WHERE 条件，COUNT 与 DELETE 对同一批行生效），
 * 因此「预检说会删 N 行」与「执行后回报删了 N 行」必然对得上。
 *
 * <h2>三个计数分别是什么</h2>
 * <ul>
 *   <li>{@code deleted} —— 物理删除的行数（{@code t_coupon} 中 status 为
 *       {@code UNUSED / LOCKED / EXPIRED} 的行）。券是用户领进来的营销权益，
 *       未核销就不涉及任何交易事实，注销即删；</li>
 *   <li>{@code masked} —— 恒 {@code 0}。本域没有「留行脱敏」的数据；</li>
 *   <li>{@code blocking} —— 恒 {@code 0}。<b>本域不阻塞注销</b>：锁定的券只占用
 *       一张券，不会卡住任何流程（超时未支付由补偿调度解锁），没有「先处理完才能注销」的项。</li>
 * </ul>
 *
 * <h2>为什么已核销（{@code USED}）的券留下</h2>
 * 核销即抵扣，券已经随订单进了资金链路 —— 订单详情要展示「本单用了哪张券、
 * 抵了多少」，删掉券码就让这句回答失去依据（设计文档 4.4：已核销的保留，
 * 订单侧已有留档）。
 *
 * <h2>幂等</h2>
 * DELETE 是幂等语句：重复调用不报错、不二次破坏。第二次执行时可删的券已空
 * （删 0 行），响应数字随之归零 —— 这正是注销调度「失败重试、断点恢复」
 * 要求的语义（设计文档第六节「异常中断恢复」）。
 *
 * @param userId   被注销的账号 id（服务间调用，参数即被操作对象）
 * @param domain   域标识，固定 {@code "marketing"}
 * @param deleted  物理删除的行数（dryRun 时为预估）
 * @param masked   脱敏的行数（本域恒 0）
 * @param blocking 会阻塞注销的行数（本域恒 0）
 */
public record PersonalDataCleanupResult(Long userId,
                                        String domain,
                                        int deleted,
                                        int masked,
                                        int blocking) {
}
