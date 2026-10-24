package com.duoduo.product.service;

import com.duoduo.common.exception.BizException;
import com.duoduo.product.dto.StockDtos.Reason;
import com.duoduo.product.dto.StockDtos.StockChangeItem;
import com.duoduo.product.dto.StockDtos.StockChangeRequest;
import com.duoduo.product.dto.StockDtos.StockChangeResponse;
import com.duoduo.product.dto.StockDtos.StockChangeResult;
import com.duoduo.product.entity.SkuEntity;
import com.duoduo.product.entity.SkuStockReservationEntity;
import com.duoduo.product.mapper.SkuMapper;
import com.duoduo.product.mapper.SkuStockReservationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 订单级库存预占台账（B-03）单测。
 *
 * <p>为什么必须逐条钉住：释放 / 扣减原先只校验 SKU 上的**全局**冻结量，
 * 任意订单都能把别人订单冻住的库存放掉；两个订单各自成功、账却对不上。
 * 这类缺陷不会抛异常，只会在某一天表现为「用户付了款却扣不动库存」。
 */
class StockServiceTest {

    private static final long ORDER_A = 900000000000000001L;
    private static final long ORDER_B = 900000000000000002L;
    private static final long SKU = 2001L;

    private final SkuMapper skuMapper = mock(SkuMapper.class);
    private final SkuStockReservationMapper reservationMapper = mock(SkuStockReservationMapper.class);
    private final StockService service = spy(new StockService(skuMapper, reservationMapper));

    @BeforeEach
    void stubTransactionBoundary() {
        doNothing().when(service).markRollbackOnly();
    }

    @Test
    void freeze_release_deduct_requireOrderId() {
        for (String action : List.of(StockService.ACTION_FREEZE,
                StockService.ACTION_RELEASE, StockService.ACTION_DEDUCT)) {
            assertThatThrownBy(() -> service.apply(action, request(null, null, item(SKU, 1))))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("orderId");
        }
        verifyNoInteractions(reservationMapper, skuMapper);
    }

    @Test
    void freeze_newOrder_freezesStockAndReturnsQuantity() {
        when(reservationMapper.insertIgnore(eq(ORDER_A), eq(SKU), eq(2), any())).thenReturn(1);
        when(skuMapper.freezeStock(eq(SKU), eq(2), any())).thenReturn(1);

        StockChangeResponse response = service.apply(StockService.ACTION_FREEZE,
                request(ORDER_A, null, item(SKU, 2)));

        assertThat(response.allSuccess()).isTrue();
        assertThat(only(response).success()).isTrue();
        assertThat(only(response).quantity()).isEqualTo(2);
        verify(reservationMapper).insertIgnore(eq(ORDER_A), eq(SKU), eq(2), any());
        verify(skuMapper).freezeStock(eq(SKU), eq(2), any());
    }

    @Test
    void freeze_retrySameOrderAndQuantity_isIdempotent() {
        when(reservationMapper.insertIgnore(eq(ORDER_A), eq(SKU), eq(2), any())).thenReturn(0);
        when(reservationMapper.selectForUpdate(ORDER_A, SKU))
                .thenReturn(reservation(ORDER_A, SKU, 2, SkuStockReservationEntity.STATUS_FROZEN));

        StockChangeResponse response = service.apply(StockService.ACTION_FREEZE,
                request(ORDER_A, null, item(SKU, 2)));

        assertThat(response.allSuccess()).isTrue();
        assertThat(only(response).quantity()).isZero();
        verify(skuMapper, never()).freezeStock(anyLong(), anyInt(), any());
    }

    @Test
    void freeze_retryDifferentQuantity_isConflictAndChangesNothing() {
        when(reservationMapper.insertIgnore(eq(ORDER_A), eq(SKU), eq(3), any())).thenReturn(0);
        when(reservationMapper.selectForUpdate(ORDER_A, SKU))
                .thenReturn(reservation(ORDER_A, SKU, 2, SkuStockReservationEntity.STATUS_FROZEN));

        StockChangeResponse response = service.apply(StockService.ACTION_FREEZE,
                request(ORDER_A, null, item(SKU, 3)));

        assertThat(response.allSuccess()).isFalse();
        assertThat(only(response).reason()).isEqualTo(Reason.RESERVATION_QTY_MISMATCH);
        verify(skuMapper, never()).freezeStock(anyLong(), anyInt(), any());
        verify(service).markRollbackOnly();
    }

    @Test
    void freeze_afterRelease_isConflict() {
        when(reservationMapper.insertIgnore(eq(ORDER_A), eq(SKU), eq(2), any())).thenReturn(0);
        when(reservationMapper.selectForUpdate(ORDER_A, SKU))
                .thenReturn(reservation(ORDER_A, SKU, 2, SkuStockReservationEntity.STATUS_RELEASED));

        StockChangeResponse response = service.apply(StockService.ACTION_FREEZE,
                request(ORDER_A, null, item(SKU, 2)));

        assertThat(response.allSuccess()).isFalse();
        assertThat(only(response).reason()).isEqualTo(Reason.RESERVATION_STATE_CONFLICT);
        verify(skuMapper, never()).freezeStock(anyLong(), anyInt(), any());
    }

    @Test
    void freeze_insufficientStock_reportsReasonAndRollsBack() {
        when(reservationMapper.insertIgnore(eq(ORDER_A), eq(SKU), eq(2), any())).thenReturn(1);
        when(skuMapper.freezeStock(eq(SKU), eq(2), any())).thenReturn(0);
        when(skuMapper.selectById(SKU)).thenReturn(sku("ACTIVE"));

        StockChangeResponse response = service.apply(StockService.ACTION_FREEZE,
                request(ORDER_A, null, item(SKU, 2)));

        assertThat(response.allSuccess()).isFalse();
        assertThat(only(response).reason()).isEqualTo(Reason.INSUFFICIENT_STOCK);
        verify(reservationMapper).insertIgnore(eq(ORDER_A), eq(SKU), eq(2), any());
        verify(service).markRollbackOnly();
    }

    @Test
    void release_withoutReservation_isIdempotentNoOp() {
        when(reservationMapper.selectForUpdate(ORDER_A, SKU)).thenReturn(null);

        StockChangeResponse response = service.apply(StockService.ACTION_RELEASE,
                request(ORDER_A, null, item(SKU, 2)));

        assertThat(response.allSuccess()).isTrue();
        assertThat(only(response).quantity()).isZero();
        verify(skuMapper, never()).releaseStock(anyLong(), anyInt(), any());
    }

    @Test
    void release_frozen_releasesStockAndMarksReleased() {
        when(reservationMapper.selectForUpdate(ORDER_A, SKU))
                .thenReturn(reservation(ORDER_A, SKU, 2, SkuStockReservationEntity.STATUS_FROZEN));
        when(skuMapper.releaseStock(eq(SKU), eq(2), any())).thenReturn(1);
        when(reservationMapper.markReleased(eq(ORDER_A), eq(SKU), any())).thenReturn(1);

        StockChangeResponse response = service.apply(StockService.ACTION_RELEASE,
                request(ORDER_A, null, item(SKU, 2)));

        assertThat(response.allSuccess()).isTrue();
        assertThat(only(response).quantity()).isEqualTo(2);
        verify(reservationMapper).markReleased(eq(ORDER_A), eq(SKU), any());
    }

    @Test
    void release_withDifferentQuantity_isConflict() {
        when(reservationMapper.selectForUpdate(ORDER_A, SKU))
                .thenReturn(reservation(ORDER_A, SKU, 2, SkuStockReservationEntity.STATUS_FROZEN));

        StockChangeResponse response = service.apply(StockService.ACTION_RELEASE,
                request(ORDER_A, null, item(SKU, 1)));

        assertThat(response.allSuccess()).isFalse();
        assertThat(only(response).reason()).isEqualTo(Reason.RESERVATION_QTY_MISMATCH);
        verify(skuMapper, never()).releaseStock(anyLong(), anyInt(), any());
    }

    @Test
    void release_afterDeduct_isConflict() {
        when(reservationMapper.selectForUpdate(ORDER_A, SKU))
                .thenReturn(reservation(ORDER_A, SKU, 2, SkuStockReservationEntity.STATUS_DEDUCTED));

        StockChangeResponse response = service.apply(StockService.ACTION_RELEASE,
                request(ORDER_A, null, item(SKU, 2)));

        assertThat(response.allSuccess()).isFalse();
        assertThat(only(response).reason()).isEqualTo(Reason.RESERVATION_STATE_CONFLICT);
        verify(skuMapper, never()).releaseStock(anyLong(), anyInt(), any());
    }

    @Test
    void release_isScopedToItsOwnOrder() {
        when(reservationMapper.selectForUpdate(ORDER_B, SKU)).thenReturn(null);

        StockChangeResponse response = service.apply(StockService.ACTION_RELEASE,
                request(ORDER_B, null, item(SKU, 2)));

        assertThat(response.allSuccess()).isTrue();
        verify(reservationMapper, never()).selectForUpdate(eq(ORDER_A), eq(SKU));
        verify(skuMapper, never()).releaseStock(anyLong(), anyInt(), any());
    }

    @Test
    void deduct_frozen_reducesStockAndMarksDeducted() {
        when(reservationMapper.selectForUpdate(ORDER_A, SKU))
                .thenReturn(reservation(ORDER_A, SKU, 2, SkuStockReservationEntity.STATUS_FROZEN));
        when(skuMapper.deductStock(eq(SKU), eq(2), any())).thenReturn(1);
        when(reservationMapper.markDeducted(eq(ORDER_A), eq(SKU), any())).thenReturn(1);

        StockChangeResponse response = service.apply(StockService.ACTION_DEDUCT,
                request(ORDER_A, null, item(SKU, 2)));

        assertThat(response.allSuccess()).isTrue();
        assertThat(only(response).quantity()).isEqualTo(2);
        verify(reservationMapper).markDeducted(eq(ORDER_A), eq(SKU), any());
    }

    @Test
    void deduct_retry_isIdempotent() {
        when(reservationMapper.selectForUpdate(ORDER_A, SKU))
                .thenReturn(reservation(ORDER_A, SKU, 2, SkuStockReservationEntity.STATUS_DEDUCTED));

        StockChangeResponse response = service.apply(StockService.ACTION_DEDUCT,
                request(ORDER_A, null, item(SKU, 2)));

        assertThat(response.allSuccess()).isTrue();
        assertThat(only(response).quantity()).isZero();
        verify(skuMapper, never()).deductStock(anyLong(), anyInt(), any());
    }

    @Test
    void deduct_withoutReservation_fails() {
        when(reservationMapper.selectForUpdate(ORDER_A, SKU)).thenReturn(null);

        StockChangeResponse response = service.apply(StockService.ACTION_DEDUCT,
                request(ORDER_A, null, item(SKU, 2)));

        assertThat(response.allSuccess()).isFalse();
        assertThat(only(response).reason()).isEqualTo(Reason.RESERVATION_NOT_FOUND);
        verify(skuMapper, never()).deductStock(anyLong(), anyInt(), any());
        verify(service).markRollbackOnly();
    }

    @Test
    void deduct_afterRelease_isConflict() {
        when(reservationMapper.selectForUpdate(ORDER_A, SKU))
                .thenReturn(reservation(ORDER_A, SKU, 2, SkuStockReservationEntity.STATUS_RELEASED));

        StockChangeResponse response = service.apply(StockService.ACTION_DEDUCT,
                request(ORDER_A, null, item(SKU, 2)));

        assertThat(response.allSuccess()).isFalse();
        assertThat(only(response).reason()).isEqualTo(Reason.RESERVATION_STATE_CONFLICT);
        verify(skuMapper, never()).deductStock(anyLong(), anyInt(), any());
    }

    @Test
    void restock_stillRequiresRefNo() {
        assertThatThrownBy(() -> service.apply(StockService.ACTION_RESTOCK,
                request(null, " ", item(SKU, 2))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("refNo");
        verifyNoInteractions(reservationMapper, skuMapper);
    }

    @Test
    void restock_duplicate_doesNotAddStockAgain() {
        when(skuMapper.insertRestockLogIgnore("restock:" + ORDER_A, SKU, 2)).thenReturn(0);

        StockChangeResponse response = service.apply(StockService.ACTION_RESTOCK,
                request(null, "restock:" + ORDER_A, item(SKU, 2)));

        assertThat(response.allSuccess()).isTrue();
        assertThat(only(response).quantity()).isZero();
        verify(skuMapper, never()).restockStock(anyLong(), anyInt(), any());
    }

    private static StockChangeRequest request(Long orderId, String refNo, StockChangeItem... items) {
        return new StockChangeRequest(List.of(items), orderId, refNo);
    }

    private static StockChangeItem item(long skuId, int quantity) {
        return new StockChangeItem(skuId, quantity);
    }

    private static StockChangeResult only(StockChangeResponse response) {
        return response.results().get(0);
    }

    private static SkuStockReservationEntity reservation(long orderId, long skuId, int quantity, String status) {
        SkuStockReservationEntity entity = new SkuStockReservationEntity();
        entity.setOrderId(orderId);
        entity.setSkuId(skuId);
        entity.setQuantity(quantity);
        entity.setStatus(status);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }

    private static SkuEntity sku(String status) {
        SkuEntity entity = new SkuEntity();
        entity.setId(SKU);
        entity.setStatus(status);
        return entity;
    }
}
