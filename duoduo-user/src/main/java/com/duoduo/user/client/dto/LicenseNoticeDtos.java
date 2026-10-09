package com.duoduo.user.client.dto;

import java.util.List;

/**
 * 通知推送的服务间契约（E15）：与社交域 {@code SocialDtos.NoticePushItem} 对齐。
 *
 * <p>只共享<b>契约形态</b>、不依赖对方的 DTO 类：依赖对方的 jar 会让两个服务必须
 * 同时发布，一处改名就让另一处编译不过。本域已有的 {@code MembershipInfo}、
 * 商品域的 {@code OrderFact} 都是同一取舍。
 */
public final class LicenseNoticeDtos {

    private LicenseNoticeDtos() {
    }

    /**
     * 单条通知。
     *
     * @param userId   收件人（这里恒为店主本人；不发给店员 —— 资质是店主的事）
     * @param category MERCHANT（商家消息）
     * @param bizType  业务类型，用于前端分流展示与将来统计
     * @param bizId    业务单号（入驻申请 id，字符串口径）
     * @param jumpPage 小程序落地页。<b>E15 的提醒刻意留空</b>：收件人主要在商家端网页
     *                 处理续期，而商家端是独立站点、不是小程序页面 —— 填一个不存在的小程序
     *                 路径会被社交域的白名单拒绝（400），填一个存在的又会把用户带到
     *                 一个跟续期无关的页面。留空 = 通知只作为提醒，落地靠商家端横幅。
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

    /** 批量推送请求（社交域单批上限 100 条） */
    public record PushRequest(List<Item> items) {
    }

    /** 推送结果：accepted = 服务端实际落库条数 */
    public record PushResult(long accepted) {
    }
}
