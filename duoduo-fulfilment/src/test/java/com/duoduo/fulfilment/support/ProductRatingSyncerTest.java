package com.duoduo.fulfilment.support;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.fulfilment.client.ProductClient;
import com.duoduo.fulfilment.mapper.ReviewMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H-02 · 评价可见性变化后的商品评分回写。
 *
 * <p>核心是「下架最后一条通过评价」这个反向路径：聚合查询对已无通过评价的 SPU
 * 返回空行，若只按结果集推补丁，商品页会停在旧评分 —— 这里钉住必须显式推 0。
 */
class ProductRatingSyncerTest {

    private final ReviewMapper mapper = mock(ReviewMapper.class);
    private final ProductClient productClient = mock(ProductClient.class);
    private final ProductRatingSyncer syncer = new ProductRatingSyncer(mapper, productClient);

    @SuppressWarnings("unchecked")
    private List<ProductClient.RatingPatch> capturedPatches() {
        ArgumentCaptor<List<ProductClient.RatingPatch>> captor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(productClient).recomputeRatings(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("下架最后一条评价：聚合为空也要推 0 分 / 0 条，不能停在旧评分")
    void 聚合为空时推零() {
        when(mapper.ratingAggregatesBySpu(any())).thenReturn(List.of());
        when(productClient.recomputeRatings(any())).thenReturn(ApiResponse.ok(1));

        syncer.onReviewVisibilityChanged(new ProductRatingSyncer.ReviewVisibilityChanged(List.of(9L)));

        List<ProductClient.RatingPatch> patches = capturedPatches();
        assertThat(patches).hasSize(1);
        assertThat(patches.get(0).spuId()).isEqualTo(9L);
        assertThat(patches.get(0).avgRating()).isZero();
        assertThat(patches.get(0).ratingCount()).isZero();
    }

    @Test
    @DisplayName("多商品：有聚合按真实值推，无聚合补 0")
    void 多商品混合聚合() {
        when(mapper.ratingAggregatesBySpu(any())).thenReturn(List.of(
                Map.<String, Object>of("spuId", 9L, "cnt", 2L, "avgRating", 4.5)));
        when(productClient.recomputeRatings(any())).thenReturn(ApiResponse.ok(2));

        syncer.onReviewVisibilityChanged(new ProductRatingSyncer.ReviewVisibilityChanged(List.of(9L, 10L, 9L)));

        List<ProductClient.RatingPatch> patches = capturedPatches();
        assertThat(patches).hasSize(2);
        assertThat(patches).anySatisfy(p -> {
            assertThat(p.spuId()).isEqualTo(9L);
            assertThat(p.avgRating()).isEqualTo(4.5);
            assertThat(p.ratingCount()).isEqualTo(2);
        });
        assertThat(patches).anySatisfy(p -> {
            assertThat(p.spuId()).isEqualTo(10L);
            assertThat(p.avgRating()).isZero();
            assertThat(p.ratingCount()).isZero();
        });
    }

    @Test
    @DisplayName("商品域不可用：best-effort 只告警，不把异常抛回评价主流程")
    void 回写失败不抛异常() {
        when(mapper.ratingAggregatesBySpu(any())).thenReturn(List.of());
        when(productClient.recomputeRatings(any())).thenThrow(new RuntimeException("product down"));

        assertThatCode(() -> syncer.onReviewVisibilityChanged(
                new ProductRatingSyncer.ReviewVisibilityChanged(List.of(11L)))).doesNotThrowAnyException();
    }
}
