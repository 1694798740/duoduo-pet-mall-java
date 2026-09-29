package com.duoduo.trade.client.dto;

import java.util.List;

/**
 * 通知推送的服务间契约（第五十五轮）：与社交域 {@code SocialDtos.NoticePushItem} 对齐。
 *
 * <p><b>为什么各域自己定义一份，而不是共享社交域的 DTO 类</b>：域之间只共享
 * 「契约的形态」，不共享实现 jar。若直接依赖对方的 DTO 类，社交域一次字段改名
 * 就会让交易域编译不过 —— 两个服务的发布被绑在一起。这里有一份重复定义是**刻意的代价**：
 * 任何一侧改契约时，另一侧表现为「运行时报 400 并说清哪个字段」而不是「悄悄不同步」。
 */
public final class NoticePushDtos {

    private NoticePushDtos() {
    }

    /**
     * 单条通知。
     *
     * @param userId   收件人（0 = 全站公告）
     * @param category SYSTEM / ANNOUNCEMENT / FRIEND / MERCHANT / ORDER
     * @param bizType  业务类型（ORDER_PLACED / ORDER_PAID / ...）
     * @param bizId    业务单号（订单号等，字符串口径 T-39）
     * @param jumpPage 小程序落地页，必须是真实存在的页面（社交域按白名单校验）
     */
    public record Item(
            Long userId,
            String category,
            String title,
            String content,
            String bizType,
            String bizId,
            String jumpPage
    ) {
    }

    /** 批量推送请求 */
    public record PushRequest(List<Item> items) {
    }

    /** 推送结果：accepted = 服务端实际落库条数 */
    public record PushResult(long accepted) {
    }
}
