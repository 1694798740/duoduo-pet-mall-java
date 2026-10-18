package com.duoduo.fulfilment.support;

import com.duoduo.fulfilment.client.ProductClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评分对账判据单测（2026-10-18）。
 *
 * <p>为什么必须逐个方向测：对账错一个方向都是静默的 ——
 * 只复位假数据会抹掉「有评价但没回写」的真数据；只补推会留下假 4.0 分。
 * 两条错误都不会报错，只会让首页与详情页继续不一致。
 */
class RatingReconcileTest {

    @Test
    @DisplayName("候选有、评价库没有 → 复位 0（清掉种子假数据）")
    void 假数据必须复位() {
        List<ProductClient.RatingPatch> patches = RatingReconcile.plan(
                List.of(new RatingReconcile.Candidate(1001L, 4)),
                List.of());

        assertThat(patches).hasSize(1);
        assertThat(patches.get(0).spuId()).isEqualTo(1001L);
        assertThat(patches.get(0).avgRating()).isEqualTo(0.0);
        assertThat(patches.get(0).ratingCount()).isZero();
    }

    @Test
    @DisplayName("候选有、评价库也有 → 用真实聚合覆盖（不是保留旧值）")
    void 有评价时用真实值() {
        List<ProductClient.RatingPatch> patches = RatingReconcile.plan(
                List.of(new RatingReconcile.Candidate(1101L, 2)),
                List.of(new RatingReconcile.Aggregate(1101L, 4.5, 2)));

        assertThat(patches).hasSize(1);
        assertThat(patches.get(0).avgRating()).isEqualTo(4.5);
        assertThat(patches.get(0).ratingCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("评价库有、商品域不知道 → 补推（回写曾失败的漏网）")
    void 漏网必须补推() {
        List<ProductClient.RatingPatch> patches = RatingReconcile.plan(
                List.of(),
                List.of(new RatingReconcile.Aggregate(1201L, 5.0, 1)));

        assertThat(patches).hasSize(1);
        assertThat(patches.get(0).spuId()).isEqualTo(1201L);
        assertThat(patches.get(0).ratingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("同一 SPU 不会生成两条补丁（去重后以真实值为准）")
    void 同一SPU只出一条补丁() {
        List<ProductClient.RatingPatch> patches = RatingReconcile.plan(
                List.of(new RatingReconcile.Candidate(1L, 3)),
                List.of(new RatingReconcile.Aggregate(1L, 4.0, 3),
                        new RatingReconcile.Aggregate(2L, 5.0, 1)));

        assertThat(patches).hasSize(2);
        assertThat(patches).extracting(ProductClient.RatingPatch::spuId).containsExactly(1L, 2L);
        assertThat(patches.get(0).avgRating()).isEqualTo(4.0);
        assertThat(patches.get(1).avgRating()).isEqualTo(5.0);
    }

    @Test
    @DisplayName("两边都为空 → 不产生任何补丁（不能凭空写 0）")
    void 空对空不产生补丁() {
        assertThat(RatingReconcile.plan(List.of(), List.of())).isEmpty();
    }
}
