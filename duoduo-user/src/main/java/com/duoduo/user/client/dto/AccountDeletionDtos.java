package com.duoduo.user.client.dto;

/**
 * C7 · 账号注销时「个人数据清理」的服务间契约。
 *
 * <p>五域（交易 / 履约 / 营销 / 社交 / 商品）各返回一份，字段完全一致 ——
 * 调用方（{@code AccountDeletionService}）因此可以用同一段代码处理五个域，
 * 新增第六个域时不必再改编排逻辑。
 *
 * <p>与各域自己的 DTO 类<b>不互相依赖</b>：依赖对方的 jar 会让两个服务必须同时发布，
 * 一处改名就让另一处编译不过（同 {@code LicenseNoticeDtos} 的取舍）。
 * 两侧只靠 JSON 字段名对齐，Feign 反序列化不关心类来自哪个包。
 */
public final class AccountDeletionDtos {

    private AccountDeletionDtos() {
    }

    /**
     * 一次清理（或一次预检）的回执。
     *
     * @param userId  被清理的用户
     * @param domain  域标识（{@code trade} / {@code fulfilment} / {@code marketing} /
     *                {@code social} / {@code product}），日志与排查用
     * @param deleted 删除的行数（个人主动数据：购物车、地址、关注、消息、券、分享互动…）
     * @param masked  脱敏的行数（交易关联数据：订单收货人姓名电话…）
     * @param blocking 会导致注销被挡下的行数（未终态订单 / 进行中售后 / 在售闲置）。
     *                 <b>只有预检模式（{@code dryRun=true}）下这个字段才有意义</b>，
     *                 执行模式调用时它同样被计算，但调用方不依赖它。
     */
    public record PersonalDataCleanupResult(
            Long userId,
            String domain,
            int deleted,
            int masked,
            int blocking
    ) {
    }
}
