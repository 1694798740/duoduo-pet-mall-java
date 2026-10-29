package com.duoduo.trade.service;

import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.trade.client.OrderNotifier;
import com.duoduo.trade.entity.PaymentEntity;
import com.duoduo.trade.entity.PaymentRefundEntity;
import com.duoduo.trade.mapper.OrderMapper;
import com.duoduo.trade.mapper.PaymentMapper;
import com.duoduo.trade.mapper.PaymentRefundMapper;
import com.duoduo.trade.payment.PaymentChannel;
import com.duoduo.trade.payment.PaymentChannelRegistry;
import com.duoduo.trade.support.OrderNoGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 重复收款自动退款单测（B-04 · 第一百二十六轮）。
 *
 * <p>这条链路是**钱直接从平台账上退回买家**的动作，两类错误都不会抛异常：
 * 重复退款（台账撞键没挡住、CAS 没挡住）与漏退款（渠道一次失败就被当成终态）。
 * 所以边界必须用断言钉死：
 * ① 检出落库字段完整且幂等；② 执行成功只落一次账；③ 渠道失败/处理中**必须保持 INIT**
 * （补偿哲学：把「暂时失败」固化成终态等于宣布这笔钱永远退不回去）；
 * ④ 支付单不存在绝不退款（来历不明的钱不能退）；⑤ CAS 落败的并发重提无害。
 */
class PaymentRefundServiceTest {

    private PaymentRefundMapper refundMapper;
    private PaymentMapper paymentMapper;
    private OrderMapper orderMapper;
    private PaymentChannelRegistry channelRegistry;
    private PaymentChannel channel;
    private OrderNoGenerator orderNoGenerator;
    private OrderNotifier orderNotifier;
    private ApplicationEventPublisher eventPublisher;
    private PaymentRefundService service;

    @BeforeEach
    void setUp() {
        refundMapper = mock(PaymentRefundMapper.class);
        paymentMapper = mock(PaymentMapper.class);
        orderMapper = mock(OrderMapper.class);
        channelRegistry = mock(PaymentChannelRegistry.class);
        channel = mock(PaymentChannel.class);
        when(channelRegistry.require("SANDBOX")).thenReturn(channel);
        orderNoGenerator = mock(OrderNoGenerator.class);
        when(orderNoGenerator.next()).thenReturn("R20261026000000000001");
        SnowflakeIdGenerator idGenerator = mock(SnowflakeIdGenerator.class);
        when(idGenerator.nextId()).thenReturn(9001L);
        orderNotifier = mock(OrderNotifier.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new PaymentRefundService(refundMapper, paymentMapper, orderMapper,
                channelRegistry, idGenerator, orderNoGenerator, orderNotifier, eventPublisher, 200);
    }

    private static PaymentEntity duplicatePayment() {
        PaymentEntity payment = new PaymentEntity();
        payment.setId(1001L);
        payment.setPayNo("PAY-DUP");
        payment.setOrderId(2001L);
        payment.setUserId(3001L);
        payment.setChannel("SANDBOX");
        payment.setAmount(15800L);
        payment.setStatus(PaymentEntity.STATUS_CLOSED);
        payment.setFailReason("重复支付：订单已由支付单 PAY-WIN 入账，本笔需退款");
        return payment;
    }

    private static PaymentEntity winningPayment() {
        PaymentEntity payment = new PaymentEntity();
        payment.setPayNo("PAY-WIN");
        payment.setAmount(15800L);
        return payment;
    }

    // ---------------- 检出落库 ----------------

    @Test
    void 检出落库_字段完整且幂等登记() {
        when(refundMapper.insertIgnore(any(PaymentRefundEntity.class))).thenReturn(1);

        boolean inserted = service.openAutoRefund(duplicatePayment(), winningPayment());

        assertThat(inserted).isTrue();
        ArgumentCaptor<PaymentRefundEntity> captor = ArgumentCaptor.forClass(PaymentRefundEntity.class);
        verify(refundMapper).insertIgnore(captor.capture());
        PaymentRefundEntity refund = captor.getValue();
        assertThat(refund.getId()).isEqualTo(9001L);
        assertThat(refund.getRefundNo()).isEqualTo("R20261026000000000001");
        assertThat(refund.getPayNo()).isEqualTo("PAY-DUP");
        assertThat(refund.getOrderId()).isEqualTo(2001L);
        assertThat(refund.getUserId()).isEqualTo(3001L);
        assertThat(refund.getChannel()).isEqualTo("SANDBOX");
        assertThat(refund.getAmount()).isEqualTo(15800L);
        assertThat(refund.getStatus()).isEqualTo("INIT");
    }

    @Test
    void 检出落库_撞唯一索引幂等跳过且不报错() {
        when(refundMapper.insertIgnore(any(PaymentRefundEntity.class))).thenReturn(0);

        boolean inserted = service.openAutoRefund(duplicatePayment(), winningPayment());

        assertThat(inserted).isFalse();
        verify(refundMapper).insertIgnore(any(PaymentRefundEntity.class));
    }

    @Test
    void 检出落库_金额为空的支付单按0落库不抛NPE() {
        PaymentEntity payment = duplicatePayment();
        payment.setAmount(null);
        when(refundMapper.insertIgnore(any(PaymentRefundEntity.class))).thenReturn(1);

        assertThatCode(() -> service.openAutoRefund(payment, winningPayment())).doesNotThrowAnyException();
    }

    // ---------------- 执行 ----------------

    private PaymentRefundEntity pendingRefund() {
        PaymentRefundEntity refund = new PaymentRefundEntity();
        refund.setId(9001L);
        refund.setRefundNo("R20261026000000000001");
        refund.setPayNo("PAY-DUP");
        refund.setOrderId(2001L);
        refund.setUserId(3001L);
        refund.setChannel("SANDBOX");
        refund.setAmount(15800L);
        refund.setStatus("INIT");
        refund.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return refund;
    }

    private void stubPendingList(PaymentRefundEntity refund) {
        when(refundMapper.selectList(any())).thenReturn(List.of(refund));
        PaymentEntity payment = new PaymentEntity();
        payment.setPayNo("PAY-DUP");
        payment.setAmount(15800L);
        when(paymentMapper.selectOne(any())).thenReturn(payment);
    }

    @Test
    void 执行成功_以退款单号为幂等号调渠道并落账() {
        PaymentRefundEntity refund = pendingRefund();
        stubPendingList(refund);
        when(channel.refund(any())).thenReturn(
                new PaymentChannel.RefundResult("R20261026000000000001", "SANDBOX-REFUND-1", "SUCCESS", true));
        when(refundMapper.markSuccess(eq("R20261026000000000001"), anyString(), any())).thenReturn(1);

        int[] result = service.executePending(LocalDateTime.now(ZoneOffset.UTC));

        assertThat(result).isEqualTo(new int[]{1, 0});
        ArgumentCaptor<PaymentChannel.RefundCommand> captor =
                ArgumentCaptor.forClass(PaymentChannel.RefundCommand.class);
        verify(channel).refund(captor.capture());
        assertThat(captor.getValue().payNo()).isEqualTo("PAY-DUP");
        assertThat(captor.getValue().refundNo()).isEqualTo("R20261026000000000001");
        assertThat(captor.getValue().amount()).isEqualTo(15800L);
        assertThat(captor.getValue().totalAmount()).isEqualTo(15800L);
        verify(refundMapper).markSuccess(eq("R20261026000000000001"), eq("SANDBOX-REFUND-1"), any());
        verify(refundMapper, never()).markFailReason(anyString(), anyString(), any());
    }

    @Test
    void 执行成功_只通知买家一次且带订单号() {
        PaymentRefundEntity refund = pendingRefund();
        stubPendingList(refund);
        com.duoduo.trade.entity.OrderEntity order = new com.duoduo.trade.entity.OrderEntity();
        order.setId(2001L);
        order.setOrderNo("NO2001");
        when(orderMapper.selectById(2001L)).thenReturn(order);
        when(channel.refund(any())).thenReturn(
                new PaymentChannel.RefundResult("R20261026000000000001", "SANDBOX-REFUND-1", "SUCCESS", true));
        when(refundMapper.markSuccess(anyString(), anyString(), any())).thenReturn(1);

        service.executePending(LocalDateTime.now(ZoneOffset.UTC));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue())
                .isInstanceOf(PaymentRefundService.PaymentRefundNoticeEvent.class);
        PaymentRefundService.PaymentRefundNoticeEvent event =
                (PaymentRefundService.PaymentRefundNoticeEvent) captor.getValue();
        assertThat(event.userId()).isEqualTo(3001L);
        assertThat(event.orderNo()).isEqualTo("NO2001");
        assertThat(event.amount()).isEqualTo(15800L);
    }

    @Test
    void 并发落败_CAS返回0时不重复落账但渠道侧同号幂等() {
        PaymentRefundEntity refund = pendingRefund();
        stubPendingList(refund);
        when(channel.refund(any())).thenReturn(
                new PaymentChannel.RefundResult("R20261026000000000001", "SANDBOX-REFUND-1", "SUCCESS", true));
        when(refundMapper.markSuccess(anyString(), anyString(), any())).thenReturn(0);

        int[] result = service.executePending(LocalDateTime.now(ZoneOffset.UTC));

        // 渠道调用已发生（同号幂等重提，无害），但台账只承认先到的那一次
        assertThat(result[0]).isEqualTo(1);
        verify(refundMapper, never()).markFailReason(anyString(), anyString(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void 渠道抛异常_台账保持INIT并记录原因_绝不静默丢钱() {
        PaymentRefundEntity refund = pendingRefund();
        stubPendingList(refund);
        when(channel.refund(any())).thenThrow(new RuntimeException("渠道连接超时"));

        int[] result = service.executePending(LocalDateTime.now(ZoneOffset.UTC));

        assertThat(result).isEqualTo(new int[]{0, 1});
        verify(refundMapper).markFailReason(eq("R20261026000000000001"), eq("渠道退款失败：渠道连接超时"), any());
        verify(refundMapper, never()).markSuccess(anyString(), anyString(), any());
    }

    @Test
    void 渠道受理未到终态_保持INIT等下轮确认() {
        PaymentRefundEntity refund = pendingRefund();
        stubPendingList(refund);
        when(channel.refund(any())).thenReturn(
                new PaymentChannel.RefundResult("R20261026000000000001", "SANDBOX-REFUND-1", "PROCESSING", false));

        int[] result = service.executePending(LocalDateTime.now(ZoneOffset.UTC));

        assertThat(result).isEqualTo(new int[]{0, 1});
        verify(refundMapper).markFailReason(eq("R20261026000000000001"), anyString(), any());
        verify(refundMapper, never()).markSuccess(anyString(), anyString(), any());
    }

    @Test
    void 渠道返回空_按处理中对待不落账() {
        PaymentRefundEntity refund = pendingRefund();
        stubPendingList(refund);
        when(channel.refund(any())).thenReturn(null);

        int[] result = service.executePending(LocalDateTime.now(ZoneOffset.UTC));

        assertThat(result).isEqualTo(new int[]{0, 1});
        verify(refundMapper, never()).markSuccess(anyString(), anyString(), any());
    }

    @Test
    void 支付单不存在_绝不退款并标记人工核对() {
        PaymentRefundEntity refund = pendingRefund();
        when(refundMapper.selectList(any())).thenReturn(List.of(refund));
        when(paymentMapper.selectOne(any())).thenReturn(null);

        int[] result = service.executePending(LocalDateTime.now(ZoneOffset.UTC));

        assertThat(result).isEqualTo(new int[]{0, 1});
        verify(channelRegistry, never()).require(anyString());
        verify(refundMapper).markFailReason(eq("R20261026000000000001"), eq("支付单不存在，需人工核对"), any());
        verify(refundMapper, never()).markSuccess(anyString(), anyString(), any());
    }

    @Test
    void 空列表_零副作用() {
        when(refundMapper.selectList(any())).thenReturn(List.of());

        int[] result = service.executePending(LocalDateTime.now(ZoneOffset.UTC));

        assertThat(result).isEqualTo(new int[]{0, 0});
        verify(paymentMapper, never()).selectOne(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void 失败原因超长被裁剪_不撑爆字段() {
        PaymentRefundEntity refund = pendingRefund();
        stubPendingList(refund);
        when(channel.refund(any())).thenThrow(new RuntimeException("x".repeat(400)));

        service.executePending(LocalDateTime.now(ZoneOffset.UTC));

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(refundMapper).markFailReason(eq("R20261026000000000001"), reason.capture(), any());
        assertThat(reason.getValue().length()).isLessThanOrEqualTo(255);
    }
}
