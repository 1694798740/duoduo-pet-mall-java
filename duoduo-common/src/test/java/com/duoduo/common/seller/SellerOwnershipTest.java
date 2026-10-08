package com.duoduo.common.seller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 卖家归属语义单测（E9）。
 *
 * <h2>为什么给两个这么"简单"的判定写测试</h2>
 * {@code isSelfSeller(null) == true} 这条是<b>刻意设计</b>而不是随手写的宽容：
 * 订单快照缺归属时按 E 口径回退自营（OrderService 的既有行为）——
 * 若把 null 当第三方，快照缺归属的旧订单会从"运营可见的自营单"变成"谁都不是的单"。
 * 这里把口径钉死，防止将来有人在"修 NPE"时顺手把 null 语义改掉。
 */
class SellerOwnershipTest {

    @Test
    void 自营判定_零值与null都算自营() {
        assertThat(SellerOwnership.isSelfSeller(0L)).isTrue();
        assertThat(SellerOwnership.isSelfSeller(null)).isTrue();
    }

    @Test
    void 第三方判定_正值为商家租户() {
        assertThat(SellerOwnership.isMerchantSeller(710000000000000001L)).isTrue();
        assertThat(SellerOwnership.isSelfSeller(710000000000000001L)).isFalse();
    }

    @Test
    void 负值不属于任何一侧_显式为自营口径之外() {
        // sellerId 理论上不会是负数；若出现（脏数据/上游 bug），
        // isSelfSeller 必须返回 false —— 宁可让这条数据在归属校验处显式失败，
        // 也不能悄悄滑进"平台自营"这个权限最宽的桶里
        assertThat(SellerOwnership.isSelfSeller(-1L)).isFalse();
    }

    @Test
    void 自营常量与trade域既有常量同源() {
        // OrderService.PLATFORM_SELLER_ID 已改为引用本常量；这断言钉住"同源"本身，
        // 防止将来有人在 trade 里重新定义一个字面量 0
        assertThat(SellerOwnership.SELF_SELLER_ID).isEqualTo(0L);
    }
}
