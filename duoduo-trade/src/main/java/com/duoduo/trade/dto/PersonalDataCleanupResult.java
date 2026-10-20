package com.duoduo.trade.dto;

/**
 * C7 · 账号注销时交易域的个人数据清理结果（服务间契约）。
 *
 * <p>调用方是用户域的注销编排（先五域清理、全部成功后才把 {@code t_user} 置
 * {@code DELETED}，见 {@code docs/account-deletion-design.md} 第三节）。
 * 本记录同时服务两种模式：
 * <ul>
 *   <li><b>预检（dryRun=true）</b>：只做 SELECT COUNT，不产生任何写。
 *       用于「申请注销」时判断有没有未完成的业务要把注销挡下；</li>
 *   <li><b>执行（dryRun 缺省 / false）</b>：真正删购物车、脱敏订单收货人。</li>
 * </ul>
 * 两个模式的数字口径逐字一致（同一个 WHERE 条件，COUNT 与 DELETE/UPDATE
 * 对同一批行生效），因此「预检说会删 N 行」与「执行后回报删了 N 行」必然对得上。
 *
 * <h2>三个计数分别是什么</h2>
 * <ul>
 *   <li>{@code deleted} —— 物理删除的行数（购物车条目）。购物车是纯个人主动数据，
 *       无任何留存义务，注销即删；</li>
 *   <li>{@code masked} —— 脱敏的行数（{@code t_order} 的收货人姓名 / 手机号）。
 *       订单行本体**不删**（《电子商务法》要求交易信息自交易完成起留存不少于三年，
 *       审计与买卖双方的事后追溯都按 {@code user_id} 关联），只把 PII 改写掉；</li>
 *   <li>{@code blocking} —— <b>会导致注销被挡下的行数</b>：未终态订单数
 *       （{@code status} 不在 {@code COMPLETED / REFUNDED / CLOSED}，含待付款）。
 *       调用方据此返回 409 并引导用户先完成或取消订单，执行阶段本就不该见到它 &gt; 0。</li>
 * </ul>
 *
 * <h2>幂等</h2>
 * DELETE 与 UPDATE 都是幂等语句：重复调用不报错、不二次破坏。
 * 第二次执行时购物车已空（删 0 行）、订单收货人已是脱敏值（MySQL 按「变更行数」
 * 计影响行数，同样更新为 0 行），响应数字随之归零 —— 这正是注销调度
 * 「失败重试、断点恢复」要求的语义（设计文档第六节「异常中断恢复」）。
 *
 * @param userId   被注销的账号 id（服务间调用，参数即被操作对象）
 * @param domain   域标识，固定 {@code "trade"}
 * @param deleted  物理删除的行数（dryRun 时为预估）
 * @param masked   脱敏的行数（dryRun 时为预估）
 * @param blocking 会阻塞注销的行数（dryRun 时同样只查不写）
 */
public record PersonalDataCleanupResult(Long userId,
                                        String domain,
                                        int deleted,
                                        int masked,
                                        int blocking) {
}
