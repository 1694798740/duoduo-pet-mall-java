package com.duoduo.trade.service;

import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.OperatorGuard;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.trade.client.OrderNotifier;
import com.duoduo.trade.entity.OrderEntity;
import com.duoduo.trade.entity.SellerDebtEntity;
import com.duoduo.trade.entity.SettlementEntity;
import com.duoduo.trade.mapper.OrderMapper;
import com.duoduo.trade.mapper.SellerDebtMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 平台垫付与卖家追偿单测（第五十八轮 · B-01）。
 *
 * <p>为什么必须给这段逻辑写单测：它是**唯一一条能让「已放款」的钱再退回买家**的通路，
 * 一旦出错就是平台白掏钱（重复垫付）或卖家被凭空记账（对未放款订单垫付）。
 * 这两类错误都不会抛异常、也不会有日志，只有对账时才发现 —— 所以用断言把边界钉死。
 *
 * <p>覆盖的边界：① 只有已完成订单可垫付；② 原因必填；③ 重复垫付 409 且金额不翻倍；
 * ④ 资金 CAS 失败时（不是已放款）必须拒绝；⑤ 欠款金额 = 买家实付；⑥ best-effort 通知失败不影响结果。
 */
class SellerDebtServiceTest {

    private SellerDebtMapper debtMapper;
    private OrderMapper orderMapper;
    private SettlementService settlementService;
    private OrderNotifier orderNotifier;
    private SellerDebtService service;

    private static OrderEntity completedOrder(long payAmount) {
        OrderEntity order = new OrderEntity();
        order.setId(1001L);
        order.setOrderNo("NO1001");
        order.setStatus("COMPLETED");
        order.setSellerId(9001L);
        order.setUserId(8001L);
        order.setPayAmount(payAmount);
        return order;
    }

    @BeforeEach
    void setUp() {
        debtMapper = mock(SellerDebtMapper.class);
        orderMapper = mock(OrderMapper.class);
        settlementService = mock(SettlementService.class);
        orderNotifier = mock(OrderNotifier.class);
        SnowflakeIdGenerator idGenerator = mock(SnowflakeIdGenerator.class);
        when(idGenerator.nextId()).thenReturn(7001L);
        service = new SellerDebtService(debtMapper, orderMapper, settlementService,
                orderNotifier, idGenerator);
    }

    /** 运营身份是静态方法，用 mockStatic 注入（不影响其他用例） */
    private static MockedStatic<OperatorGuard> asOperator() {
        LoginUser operator = mock(LoginUser.class);
        when(operator.userId()).thenReturn(999L);
        MockedStatic<OperatorGuard> guard = mockStatic(OperatorGuard.class);
        guard.when(OperatorGuard::requireOperator).thenReturn(operator);
        return guard;
    }

    @Test
    void 垫付成功_欠款金额等于买家实付() {
        try (MockedStatic<OperatorGuard> guard = asOperator()) {
            OrderEntity order = completedOrder(39700L);
            when(orderMapper.selectOne(any())).thenReturn(order);
            when(debtMapper.selectByOrderId(1001L)).thenReturn(null);
            when(debtMapper.insertIgnore(any())).thenReturn(1);
            when(settlementService.advance(eq(1001L), any())).thenReturn(true);
            when(debtMapper.selectByOrderId(1001L)).thenReturn(null, debt(39700L, "OUTSTANDING"));

            var view = service.compensate("NO1001", "卖家失联");

            assertThat(view.amount()).isEqualTo(39700L);
            assertThat(view.status()).isEqualTo("OUTSTANDING");
            assertThat(view.orderNo()).isEqualTo("NO1001");
            verify(settlementService).advance(eq(1001L), any());
        }
    }

    @Test
    void 只有已完成订单可垫付_未放款订单被拒() {
        try (MockedStatic<OperatorGuard> guard = asOperator()) {
            OrderEntity paid = completedOrder(10000L);
            paid.setStatus("PAID");
            when(orderMapper.selectOne(any())).thenReturn(paid);
            when(debtMapper.selectByOrderId(anyLong())).thenReturn(null);

            assertThatThrownBy(() -> service.compensate("NO1001", "理由"))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("只有「已完成」的订单");
            verify(debtMapper, never()).insertIgnore(any());
        }
    }

    @Test
    void 垫付原因必填_否则拒绝() {
        try (MockedStatic<OperatorGuard> guard = asOperator()) {
            assertThatThrownBy(() -> service.compensate("NO1001", "   "))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("垫付原因必填");
        }
    }

    /** ★ 重复垫付是最贵的错误：钱会真的出去两次，而欠款只记一次 */
    @Test
    void 重复垫付被拒_且不产生第二笔债() {
        try (MockedStatic<OperatorGuard> guard = asOperator()) {
            OrderEntity order = completedOrder(20000L);
            when(orderMapper.selectOne(any())).thenReturn(order);
            when(debtMapper.selectByOrderId(1001L)).thenReturn(debt(20000L, "OUTSTANDING"));

            assertThatThrownBy(() -> service.compensate("NO1001", "再垫一次"))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("已垫付过");

            verify(debtMapper, never()).insertIgnore(any());
            verify(settlementService, never()).advance(anyLong(), any());
        }
    }

    @Test
    void 资金不是已放款状态时拒绝_不留下悬空欠款() {
        try (MockedStatic<OperatorGuard> guard = asOperator()) {
            OrderEntity order = completedOrder(20000L);
            when(orderMapper.selectOne(any())).thenReturn(order);
            when(debtMapper.selectByOrderId(1001L)).thenReturn(null);
            when(debtMapper.insertIgnore(any())).thenReturn(1);
            when(settlementService.advance(eq(1001L), any())).thenReturn(false);
            SettlementEntity settlement = mock(SettlementEntity.class);
            when(settlement.getStatus()).thenReturn("REFUNDED");
            when(settlementService.findByOrderId(1001L)).thenReturn(settlement);

            assertThatThrownBy(() -> service.compensate("NO1001", "理由"))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("无法垫付");
        }
    }

    /** 通知失败不能把已成立的垫付判成失败（钱已经出去了，状态必须一致） */
    @Test
    void 通知失败不影响垫付结果() {
        try (MockedStatic<OperatorGuard> guard = asOperator()) {
            OrderEntity order = completedOrder(5000L);
            when(orderMapper.selectOne(any())).thenReturn(order);
            when(debtMapper.selectByOrderId(1001L)).thenReturn(null, debt(5000L, "OUTSTANDING"));
            when(debtMapper.insertIgnore(any())).thenReturn(1);
            when(settlementService.advance(eq(1001L), any())).thenReturn(true);
            org.mockito.Mockito.doThrow(new RuntimeException("通知服务不可用"))
                    .when(orderNotifier).notifyRefunded(anyLong(), any(), anyLong());

            var view = service.compensate("NO1001", "理由");
            assertThat(view.status()).isEqualTo("OUTSTANDING");
        }
    }

    @Test
    void 收回与核销_二次操作被拒() {
        try (MockedStatic<OperatorGuard> guard = asOperator()) {
            when(debtMapper.markSettled(eq(7001L), any())).thenReturn(1);
            when(debtMapper.selectById(7001L)).thenReturn(debt(1000L, "SETTLED"));
            assertThat(service.settle(7001L).status()).isEqualTo("SETTLED");

            when(debtMapper.markSettled(eq(7001L), any())).thenReturn(0);
            assertThatThrownBy(() -> service.settle(7001L))
                    .isInstanceOf(BizException.class);
        }
    }

    private static SellerDebtEntity debt(long amount, String status) {
        SellerDebtEntity e = new SellerDebtEntity();
        e.setId(7001L);
        e.setOrderId(1001L);
        e.setOrderNo("NO1001");
        e.setSellerId(9001L);
        e.setAmount(amount);
        e.setStatus(status);
        e.setReason("理由");
        return e;
    }
}
