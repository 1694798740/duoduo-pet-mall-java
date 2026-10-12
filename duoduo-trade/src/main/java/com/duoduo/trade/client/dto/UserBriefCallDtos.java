package com.duoduo.trade.client.dto;

/**
 * E24 · 用户域简况的**调用侧**形状（只为给租户行补一个可读的店名）。
 *
 * <p>字段全部可空：这是一份"装饰性"数据，缺字段时面板显示租户 id 即可，
 * 不该让整个面板失败（见 {@code UserBriefClient} 的注释）。
 */
public final class UserBriefCallDtos {

    private UserBriefCallDtos() {
    }

    public record UserBrief(Long id, String nickname, String avatar) {
    }
}
