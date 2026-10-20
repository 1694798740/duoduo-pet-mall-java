package com.duoduo.social.dto;

/**
 * C7 · 账号注销时社交域的个人数据清理结果（服务间契约）。
 *
 * <p>调用方是用户域的注销编排（先五域清理、全部成功后才把 {@code t_user} 置
 * {@code DELETED}，见 {@code docs/account-deletion-design.md} 第三节）。
 * 本记录同时服务两种模式：
 * <ul>
 *   <li><b>预检（dryRun=true）</b>：只做 SELECT COUNT，不产生任何写；</li>
 *   <li><b>执行（dryRun 缺省 / false）</b>：真正删该用户的社交关系与消息。</li>
 * </ul>
 * 两个模式的数字口径逐字一致（同一个 WHERE 条件，COUNT 与 DELETE 对同一批行生效）。
 *
 * <h2>三个计数分别是什么</h2>
 * <ul>
 *   <li>{@code deleted} —— 物理删除的行数合计：关注关系
 *       （{@code t_social_follow}，「我关注别人」与「别人关注我」双向都删）、
 *       私信（{@code t_social_message}，收发两个方向都删）、
 *       公告已读记录（{@code t_social_notice_read}）。
 *       社交图谱与聊天内容是纯个人数据，无留存义务；</li>
 *   <li>{@code masked} —— 恒为 0：本域没有「要留但含 PII」的数据。
 *       通知本体（{@code t_social_notice}）是**公共行**（{@code userId = 0}），
 *       不属于任何个人，删已读集合即可，不碰公告行；</li>
 *   <li>{@code blocking} —— 恒为 0：社交关系不阻塞注销。
 *       关注是双向行，任何一方注销都只删自己那一半，不存在「未完成的事务」。</li>
 * </ul>
 *
 * <h2>幂等</h2>
 * DELETE 是幂等语句：重复调用不报错、不二次破坏，第二次各表命中 0 行、数字归零 ——
 * 注销调度「失败重试、断点恢复」依赖这一点（设计文档第六节）。
 *
 * @param userId   被注销的账号 id（服务间调用，参数即被操作对象）
 * @param domain   域标识，固定 {@code "social"}
 * @param deleted  物理删除的行数合计（dryRun 时为预估）
 * @param masked   脱敏的行数（本域恒 0）
 * @param blocking 会阻塞注销的行数（本域恒 0）
 */
public record PersonalDataCleanupResult(Long userId,
                                        String domain,
                                        int deleted,
                                        int masked,
                                        int blocking) {
}
