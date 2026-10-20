package com.duoduo.fulfilment.dto;

/**
 * C7 · 账号注销时履约域的个人数据清理结果（服务间契约）。
 *
 * <p>调用方是用户域的注销编排（先五域清理、全部成功后才把 {@code t_user} 置
 * {@code DELETED}，见 {@code docs/account-deletion-design.md} 第三节）。
 * 本记录同时服务两种模式：
 * <ul>
 *   <li><b>预检（dryRun=true）</b>：只做 SELECT COUNT，不产生任何写。
 *       用于「申请注销」时判断有没有未完成的业务要把注销挡下；</li>
 *   <li><b>执行（dryRun 缺省 / false）</b>：真正删分享互动四张表的个人数据。</li>
 * </ul>
 * 两个模式的数字口径逐字一致（同一个 WHERE 条件，COUNT 与 DELETE 对同一批行生效），
 * 因此「预检说会删 N 行」与「执行后回报删了 N 行」必然对得上。
 *
 * <h2>三个计数分别是什么</h2>
 * <ul>
 *   <li>{@code deleted} —— 物理删除的行数合计：{@code t_product_share}（本人分享）、
 *       {@code t_share_comment}（本人评论 / 回复）、{@code t_share_like}（本人点赞）、
 *       {@code t_share_favorite}（本人收藏）。分享圈内容是用户主动发表的 UGC，
 *       无留存义务，注销即删；</li>
 *   <li>{@code masked} —— 恒 {@code 0}。本域没有「留行脱敏」的数据：
 *       要留的评价（{@code t_review}）整行不动，展示层已有「已注销用户」兜底，
 *       售后单同样留档，都不需要改写列；</li>
 *   <li>{@code blocking} —— <b>会导致注销被挡下的行数</b>：进行中售后数
 *       （{@code t_after_sale.status} 不在 {@code REFUNDED / CLOSED}）。
 *       调用方据此返回 409 并引导用户先等售后结案，执行阶段本就不该见到它 &gt; 0。</li>
 * </ul>
 *
 * <h2>为什么 {@code t_review} 不删</h2>
 * 评价与订单、商品评分强绑定（删评价会让商品的历史评分对不上），且展示层对
 * 已注销作者已有「已注销用户」兜底 —— 物理删评价的收益是零，代价是评分体系失真。
 *
 * <h2>幂等</h2>
 * DELETE 都是幂等语句：重复调用不报错、不二次破坏。第二次执行时四张表里
 * 该用户的行已空（删 0 行），响应数字随之归零 —— 这正是注销调度
 * 「失败重试、断点恢复」要求的语义（设计文档第六节「异常中断恢复」）。
 *
 * @param userId   被注销的账号 id（服务间调用，参数即被操作对象）
 * @param domain   域标识，固定 {@code "fulfilment"}
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
