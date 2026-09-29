package com.duoduo.fulfilment.client.dto;

import java.util.List;

/**
 * 通知推送的服务间契约（第五十五轮）：与社交域 {@code SocialDtos.NoticePushItem} 对齐。
 *
 * <p>与本域既有的 {@code OrderFact} 同一取舍：跨服务只共享**契约形态**，不共享实现 jar ——
 * 依赖对方的 DTO 类会让两个服务必须同时发布，一处改名就让另一处编译不过。
 */
public final class NoticePushDtos {

    private NoticePushDtos() {
    }

    /**
     * 单条通知。
     *
     * @param userId   收件人（0 = 全站公告）
     * @param category SYSTEM / ANNOUNCEMENT / FRIEND / MERCHANT / ORDER
     * @param bizType  业务类型（AFTER_SALE_* / LOGISTICS_SIGNED / ...）
     * @param bizId    业务单号（订单号，字符串口径 T-39）
     * @param jumpPage 小程序落地页，必须真实存在（社交域按白名单校验）
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
