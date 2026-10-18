package com.duoduo.social.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通知网页落地页映射（2026-10-18）。
 *
 * <p>这一层的失效方式是「跳到一个不存在的页面」——用户看到的是 404，
 * 而日志里什么都没有（前端路由兜底页不会报错）。所以三个方向都要钉：
 * 订单能跳对、私信能跳对、没有对应页面时**必须返回 null**（宁可不跳，也不跳 404）。
 */
class NoticeWebPathsTest {

    @Test
    @DisplayName("订单通知：用 bizId（订单号）生成网页路由，不用小程序 jumpPage")
    void 订单通知映射到订单详情() {
        assertThat(NoticeWebPaths.of("ORDER", "202610181002019756854",
                "/pages/order-detail/order-detail?orderNo=202610181002019756854"))
                .isEqualTo("/orders/202610181002019756854");
    }

    @Test
    @DisplayName("好友消息：从小程序 jumpPage 里取出 peerId，映射到网页会话页")
    void 好友消息映射到会话() {
        assertThat(NoticeWebPaths.of("FRIEND", "1:2", "/pages/chat/chat?peerId=370104871447302144"))
                .isEqualTo("/messages/370104871447302144");
    }

    @Test
    @DisplayName("公告 / 商家违规：网页端没有对应页面，必须返回 null（前端只标已读）")
    void 无对应页面返回空() {
        assertThat(NoticeWebPaths.of("ANNOUNCEMENT", null, null)).isNull();
        assertThat(NoticeWebPaths.of("MERCHANT", "1792306083352967", null)).isNull();
    }

    @Test
    @DisplayName("订单缺 bizId、好友缺 peerId：不能拼出半截路径")
    void 缺关键参数返回空() {
        assertThat(NoticeWebPaths.of("ORDER", null, "/pages/order-detail/order-detail")).isNull();
        assertThat(NoticeWebPaths.of("ORDER", "   ", null)).isNull();
        assertThat(NoticeWebPaths.of("FRIEND", "1:2", "/pages/chat/chat")).isNull();
        assertThat(NoticeWebPaths.of("FRIEND", "1:2", null)).isNull();
    }
}
