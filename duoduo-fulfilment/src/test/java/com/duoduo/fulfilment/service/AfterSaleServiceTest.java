package com.duoduo.fulfilment.service;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.exception.BizException;
import com.duoduo.fulfilment.client.FulfilmentNotifier;
import com.duoduo.fulfilment.client.OrderClient;
import com.duoduo.fulfilment.client.dto.RefundRequest;
import com.duoduo.fulfilment.client.dto.RefundResult;
import com.duoduo.fulfilment.dto.AfterSaleDtos.AfterSaleReplyRequest;
import com.duoduo.fulfilment.dto.AfterSaleDtos.AfterSaleReturnShipRequest;
import com.duoduo.fulfilment.dto.AfterSaleDtos.AfterSaleView;
import com.duoduo.fulfilment.entity.AfterSaleEntity;
import com.duoduo.fulfilment.mapper.AdminQueueReadMapper;
import com.duoduo.fulfilment.mapper.AfterSaleMapper;
import com.duoduo.fulfilment.support.AfterSaleStatus;
import com.duoduo.fulfilment.support.OrderFacts;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import feign.FeignException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 售后退货物流单测（第五十八轮 · P1-5，履约域第一批单测）。
 *
 * <h2>钉住的是「钱什么时候动」</h2>
 * 改造前：卖家同意的瞬间就退款，货寄没寄回系统里没有任何记录 ——
 * 于是「卖家确认收到退货后再退款」这句流程要求只能靠线下执行，
 * 真出了纠纷平台拿不到任何凭据。这里断言的是
 * <b>退货退款在卖家同意时不退款、在卖家签收时才退款</b>，
 * 而仅退款类型仍保持「同意即退款」（两条路径不能互相污染）。
 *
 * <h2>为什么单独测「重复寄回」</h2>
 * 运单号只登记一次。第二次提交必须让调用方明确知道「已经登记过了」，
 * 而不是静默返回成功 —— 静默成功会让用户以为第二次填的单号覆盖生效了，
 * 实际库里还是第一次那个（CAS 返回 0 行正是这个语义）。
 */
class AfterSaleServiceTest {

    private final AfterSaleMapper mapper = mock(AfterSaleMapper.class);
    private final OrderClient orderClient = mock(OrderClient.class);
    private final OrderFacts orderFacts = mock(OrderFacts.class);
    private final FulfilmentNotifier notifier = mock(FulfilmentNotifier.class);
    private final AfterSaleService service = new AfterSaleService(mapper, mock(AdminQueueReadMapper.class),
            orderClient, orderFacts,
            mock(com.duoduo.common.id.SnowflakeIdGenerator.class), new ObjectMapper(), notifier, 6);

    @BeforeEach
    void login() {
        // 服务层的 requireLogin 同时要求「参数非空」与「UserContext 有值」：
        // 前者是调用方约定，后者是防止脱离拦截器直接调服务。单测必须两个都给。
        UserContext.set(new LoginUser(101L, "13800000000", "SELLER", false));
    }

    @AfterEach
    void logout() {
        UserContext.clear();
    }

    private static AfterSaleEntity entity(long id, long buyerId, long sellerId, String type, String status) {
        AfterSaleEntity e = new AfterSaleEntity();
        e.setId(id);
        e.setOrderId(7000L + id);
        e.setOrderNo("ORDER-1");
        e.setBuyerId(buyerId);
        e.setSellerId(sellerId);
        e.setType(type);
        e.setStatus(status);
        e.setReason("尺码不合适");
        e.setCreatedAt(LocalDateTime.now());
        e.setUpdatedAt(LocalDateTime.now());
        return e;
    }

    private static RefundResult refunded(long amount) {
        return new RefundResult("ORDER-1", "REFUNDED", "已退款", amount, true,
                "REFUNDED", "已退款", false, null);
    }

    private static void stubRefund(OrderClient client, long amount) {
        when(client.refund(eq("ORDER-1"), any(RefundRequest.class)))
                .thenReturn(ApiResponse.ok(refunded(amount)));
    }

    @Test
    void 退货退款_卖家同意时不退款_只是等买家寄回() {
        when(mapper.selectById(9001L)).thenReturn(entity(9001L, 101L, 202L,
                AfterSaleStatus.TYPE_RETURN_REFUND, AfterSaleStatus.PENDING));
        when(mapper.markWaitingReturn(eq(9001L), any(), any())).thenReturn(1);

        service.reply(202L, 9001L, new AfterSaleReplyRequest("AGREE", null));

        // 关键：货还没回来，钱不能动 —— 这正是本次要补的流程缺口
        verify(orderClient, never()).refund(any(), any(RefundRequest.class));
        verify(mapper).markWaitingReturn(eq(9001L), any(), any());
        verify(notifier).notifyReturnAgreed(eq(101L), eq("ORDER-1"), eq(9001L));
    }

    @Test
    void 仅退款_卖家同意即退款_不受退货流程影响() {
        when(mapper.selectById(9002L)).thenReturn(entity(9002L, 101L, 202L,
                AfterSaleStatus.TYPE_REFUND_ONLY, AfterSaleStatus.PENDING));
        stubRefund(orderClient, 9900L);
        when(mapper.markRefundedBySeller(eq(9002L), any(), eq(9900L), any())).thenReturn(1);

        service.reply(202L, 9002L, new AfterSaleReplyRequest("AGREE", null));

        verify(orderClient).refund(eq("ORDER-1"), any(RefundRequest.class));
        verify(mapper).markRefundedBySeller(eq(9002L), any(), eq(9900L), any());
    }

    @Test
    void 买家填写寄回物流_状态转RETURNING且运单号入库() {
        when(mapper.selectById(9001L)).thenReturn(entity(9001L, 101L, 202L,
                AfterSaleStatus.TYPE_RETURN_REFUND, AfterSaleStatus.WAITING_RETURN));
        when(mapper.markReturning(eq(9001L), eq("顺丰"), eq("SF123"), any())).thenReturn(1);

        service.shipReturn(101L, 9001L, new AfterSaleReturnShipRequest("顺丰", "SF123"));

        verify(mapper).markReturning(eq(9001L), eq("顺丰"), eq("SF123"), any());
        // 通知卖家去收货：不通知的话这条单子会一直停在「待收货」
        verify(notifier).notifyReturnShipped(eq(202L), eq("ORDER-1"), eq(9001L), eq("SF123"));
    }

    @Test
    void 重复寄回转409_而不是静默成功() {
        when(mapper.selectById(9001L)).thenReturn(entity(9001L, 101L, 202L,
                AfterSaleStatus.TYPE_RETURN_REFUND, AfterSaleStatus.WAITING_RETURN));
        when(mapper.markReturning(eq(9001L), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.shipReturn(101L, 9001L,
                new AfterSaleReturnShipRequest("顺丰", "SF456")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已登记过寄回信息");
    }

    @Test
    void 卖家签收才退款() {
        // 第一次读到「待收货」，CAS 成功后再读就是「已退款」（服务层签收后会重新查一次）
        when(mapper.selectById(9001L)).thenReturn(
                entity(9001L, 101L, 202L, AfterSaleStatus.TYPE_RETURN_REFUND, AfterSaleStatus.RETURNING),
                entity(9001L, 101L, 202L, AfterSaleStatus.TYPE_RETURN_REFUND, AfterSaleStatus.REFUNDED));
        stubRefund(orderClient, 9900L);
        when(mapper.markReceivedRefunded(eq(9001L), eq(9900L), any())).thenReturn(1);

        AfterSaleView view = service.receiveReturn(202L, 9001L);

        verify(orderClient).refund(eq("ORDER-1"), any(RefundRequest.class));
        verify(mapper).markReceivedRefunded(eq(9001L), eq(9900L), any());
        verify(notifier).notifyAfterSaleRefunded(eq(101L), eq("ORDER-1"), eq(9001L), eq(9900L));
        assertThat(view.status()).isEqualTo(AfterSaleStatus.REFUNDED);
    }

    @Test
    void 寄回与签收都要校验归属() {
        AfterSaleEntity waiting = entity(9001L, 101L, 202L,
                AfterSaleStatus.TYPE_RETURN_REFUND, AfterSaleStatus.WAITING_RETURN);
        AfterSaleEntity returning = entity(9001L, 101L, 202L,
                AfterSaleStatus.TYPE_RETURN_REFUND, AfterSaleStatus.RETURNING);

        // 别人替买家寄回 → 403
        when(mapper.selectById(9001L)).thenReturn(waiting);
        assertThatThrownBy(() -> service.shipReturn(999L, 9001L,
                new AfterSaleReturnShipRequest("顺丰", "SF1")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只能对自己的售后单");

        // 别人替卖家签收 → 403（签收意味着退款，比寄回更敏感）
        when(mapper.selectById(9001L)).thenReturn(returning);
        assertThatThrownBy(() -> service.receiveReturn(999L, 9001L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只能处理自己店铺");
    }

    @Test
    void 仅退款的售后单不能走退货流程() {
        // 状态不对（REFUND_ONLY 不会进入 WAITING_RETURN），填寄回必须被明确拒绝
        when(mapper.selectById(9002L)).thenReturn(entity(9002L, 101L, 202L,
                AfterSaleStatus.TYPE_REFUND_ONLY, AfterSaleStatus.PENDING));

        assertThatThrownBy(() -> service.shipReturn(101L, 9002L,
                new AfterSaleReturnShipRequest("顺丰", "SF1")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("待买家寄回");
    }

    @Test
    void 退货过程中买家仍可申请仲裁() {
        when(mapper.selectById(9001L)).thenReturn(entity(9001L, 101L, 202L,
                AfterSaleStatus.TYPE_RETURN_REFUND, AfterSaleStatus.RETURNING));
        when(mapper.markArbitrating(eq(9001L), any())).thenReturn(1);

        service.escalate(101L, 9001L);

        // 货寄出后卖家一直不确认收货时，买家不能没有出路
        verify(mapper).markArbitrating(eq(9001L), any());
    }

    @Test
    void 交易域拒绝退款时不写结论() {
        when(mapper.selectById(9001L)).thenReturn(entity(9001L, 101L, 202L,
                AfterSaleStatus.TYPE_RETURN_REFUND, AfterSaleStatus.RETURNING));
        FeignException rejected = mock(FeignException.class);
        when(rejected.status()).thenReturn(422);
        when(orderClient.refund(eq("ORDER-1"), any(RefundRequest.class))).thenThrow(rejected);

        assertThatThrownBy(() -> service.receiveReturn(202L, 9001L))
                .isInstanceOf(BizException.class);
        // 钱没退成，绝不能留下「已签收并退款」的结论（账实不符）
        verify(mapper, never()).markReceivedRefunded(any(), any(), any());
    }
}
