package com.duoduo.fulfilment.service;

import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.fulfilment.client.dto.OrderFact;
import com.duoduo.fulfilment.dto.LogisticsDtos.TrackIngestRequest;
import com.duoduo.fulfilment.dto.LogisticsDtos.WaybillCreateRequest;
import com.duoduo.fulfilment.dto.LogisticsDtos.WaybillView;
import com.duoduo.fulfilment.entity.LogisticsTrackEntity;
import com.duoduo.fulfilment.entity.WaybillEntity;
import com.duoduo.fulfilment.mapper.LogisticsTrackMapper;
import com.duoduo.fulfilment.mapper.WaybillMapper;
import com.duoduo.fulfilment.support.LogisticsNode;
import com.duoduo.fulfilment.support.LogisticsProvider;
import com.duoduo.fulfilment.support.OrderFacts;
import com.duoduo.fulfilment.support.SimulatedLogisticsProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-05 · 电子面单。
 *
 * <p>守四组事实：
 * <ul>
 *   <li><b>准入</b>：只有该订单卖家 / 平台运营能申请，买家与别的卖家 403；</li>
 *   <li><b>幂等</b>：同一订单重复申请返回同一张面单，不生成第二个号；</li>
 *   <li><b>状态门禁</b>：只有待发货 / 已发货能申请；已完成订单不能补面单；</li>
 *   <li><b>模拟推进</b>：按已有节点决定下一节点，不重复推「已揽收」；真实渠道不本地编节点。</li>
 * </ul>
 */
class WaybillServiceTest {

    private final WaybillMapper waybillMapper = mock(WaybillMapper.class);
    private final LogisticsTrackMapper trackMapper = mock(LogisticsTrackMapper.class);
    private final OrderFacts orderFacts = mock(OrderFacts.class);
    private final LogisticsService logisticsService = mock(LogisticsService.class);
    private final SnowflakeIdGenerator idGenerator = new SnowflakeIdGenerator(1, 1);
    private final LogisticsProvider provider = new SimulatedLogisticsProvider(idGenerator);
    private final WaybillService service = new WaybillService(
            waybillMapper, trackMapper, orderFacts, provider, logisticsService, idGenerator);

    @BeforeEach
    void defaults() {
        when(trackMapper.listByOrderNo(anyString())).thenReturn(List.of());
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    private static LoginUser seller(long id) {
        return new LoginUser(id, "13800000000", "SELLER", false);
    }

    private static LoginUser operator() {
        return new LoginUser(900L, "13900000000", "OPERATOR", true);
    }

    private static LoginUser buyer(long id) {
        return new LoginUser(id, "13700000000", "USER", false);
    }

    private static OrderFact paidOrder(long buyerId, long sellerId) {
        return new OrderFact("ORD-B05-1", buyerId, sellerId, "PAID", "待发货", null, List.of());
    }

    @Test
    void 卖家申请面单_生成模拟运单并落库() {
        UserContext.set(seller(101L));
        when(orderFacts.require("ORD-B05-1")).thenReturn(paidOrder(201L, 101L));

        WaybillView view = service.create(new WaybillCreateRequest("ORD-B05-1", "yto"));

        assertThat(view.simulated()).isTrue();
        assertThat(view.mode()).isEqualTo("SIMULATED");
        assertThat(view.courierCode()).isEqualTo("YTO");
        assertThat(view.courierName()).isEqualTo("圆通速递");
        assertThat(view.waybillNo()).startsWith("YTO").hasSize(15);
        assertThat(view.status()).isEqualTo("CREATED");
        assertThat(view.statusLabel()).isEqualTo("已生成");

        ArgumentCaptor<WaybillEntity> captor = ArgumentCaptor.forClass(WaybillEntity.class);
        verify(waybillMapper).insert(captor.capture());
        WaybillEntity saved = captor.getValue();
        assertThat(saved.getOrderNo()).isEqualTo("ORD-B05-1");
        assertThat(saved.getSellerId()).isEqualTo(101L);
        assertThat(saved.getMode()).isEqualTo("SIMULATED");
        assertThat(saved.getWaybillNo()).isEqualTo(view.waybillNo());
    }

    @Test
    void 重复申请_返回同一张面单() {
        UserContext.set(seller(101L));
        when(orderFacts.require("ORD-B05-1")).thenReturn(paidOrder(201L, 101L));
        WaybillEntity existing = waybill("ORD-B05-1", "SF123456789012", "SIMULATED", "CREATED");
        when(waybillMapper.selectByOrderNo("ORD-B05-1")).thenReturn(existing);

        WaybillView view = service.create(new WaybillCreateRequest("ORD-B05-1", null));

        assertThat(view.waybillNo()).isEqualTo("SF123456789012");
        verify(waybillMapper, never()).insert(any());
    }

    @Test
    void 买家与别的卖家申请_403() {
        when(orderFacts.require("ORD-B05-1")).thenReturn(paidOrder(201L, 101L));

        UserContext.set(buyer(201L));
        assertThatThrownBy(() -> service.create(new WaybillCreateRequest("ORD-B05-1", null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只有该订单卖家");

        UserContext.set(seller(202L));
        assertThatThrownBy(() -> service.create(new WaybillCreateRequest("ORD-B05-1", null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只有该订单卖家");
        verify(waybillMapper, never()).insert(any());
    }

    @Test
    void 运营可以代申请面单() {
        UserContext.set(operator());
        when(orderFacts.require("ORD-B05-1")).thenReturn(paidOrder(201L, 101L));

        WaybillView view = service.create(new WaybillCreateRequest("ORD-B05-1", "jd"));

        assertThat(view.courierCode()).isEqualTo("JD");
        assertThat(view.courierName()).isEqualTo("京东物流");
    }

    @Test
    void 已完成订单不能申请面单() {
        UserContext.set(seller(101L));
        when(orderFacts.require("ORD-B05-1")).thenReturn(
                new OrderFact("ORD-B05-1", 201L, 101L, "COMPLETED", "已完成", "2026-10-01T00:00:00Z", List.of()));

        assertThatThrownBy(() -> service.create(new WaybillCreateRequest("ORD-B05-1", null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能申请电子面单");
        verify(waybillMapper, never()).insert(any());
    }

    @Test
    void 不支持的快递编码_400且不落库() {
        UserContext.set(seller(101L));
        when(orderFacts.require("ORD-B05-1")).thenReturn(paidOrder(201L, 101L));

        assertThatThrownBy(() -> service.create(new WaybillCreateRequest("ORD-B05-1", "FAKE")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不支持的快递公司");
        verify(waybillMapper, never()).insert(any());
    }

    @Test
    void 模拟推进_没有揽收节点时先补揽收() {
        WaybillEntity waybill = waybill("ORD-B05-1", "SF123456789012", "SIMULATED", "CREATED");
        when(waybillMapper.selectByOrderNo("ORD-B05-1")).thenReturn(waybill);

        WaybillView view = service.advance("ORD-B05-1");

        ArgumentCaptor<TrackIngestRequest> captor = ArgumentCaptor.forClass(TrackIngestRequest.class);
        verify(logisticsService).ingest(eq("ORD-B05-1"), captor.capture());
        assertThat(captor.getValue().nodes()).hasSize(1);
        assertThat(captor.getValue().nodes().get(0).nodeCode()).isEqualTo(LogisticsNode.PICKED_UP);
        assertThat(view.status()).isEqualTo("PICKED_UP");
        verify(waybillMapper).updateStatus(eq(waybill.getId()), eq("PICKED_UP"), any());
    }

    @Test
    void 模拟推进_已揽收后走运输中() {
        WaybillEntity waybill = waybill("ORD-B05-1", "SF123456789012", "SIMULATED", "PICKED_UP");
        when(waybillMapper.selectByOrderNo("ORD-B05-1")).thenReturn(waybill);
        when(trackMapper.listByOrderNo("ORD-B05-1")).thenReturn(List.of(track("ORD-B05-1", LogisticsNode.PICKED_UP)));

        WaybillView view = service.advance("ORD-B05-1");

        ArgumentCaptor<TrackIngestRequest> captor = ArgumentCaptor.forClass(TrackIngestRequest.class);
        verify(logisticsService).ingest(eq("ORD-B05-1"), captor.capture());
        assertThat(captor.getValue().nodes().get(0).nodeCode()).isEqualTo(LogisticsNode.IN_TRANSIT);
        assertThat(view.status()).isEqualTo("IN_TRANSIT");
    }

    @Test
    void 模拟推进_派送后签收() {
        WaybillEntity waybill = waybill("ORD-B05-1", "SF123456789012", "SIMULATED", "OUT_FOR_DELIVERY");
        when(waybillMapper.selectByOrderNo("ORD-B05-1")).thenReturn(waybill);
        when(trackMapper.listByOrderNo("ORD-B05-1")).thenReturn(List.of(
                track("ORD-B05-1", LogisticsNode.PICKED_UP),
                track("ORD-B05-1", LogisticsNode.IN_TRANSIT),
                track("ORD-B05-1", LogisticsNode.OUT_FOR_DELIVERY)));

        WaybillView view = service.advance("ORD-B05-1");

        ArgumentCaptor<TrackIngestRequest> captor = ArgumentCaptor.forClass(TrackIngestRequest.class);
        verify(logisticsService).ingest(eq("ORD-B05-1"), captor.capture());
        assertThat(captor.getValue().nodes().get(0).nodeCode()).isEqualTo(LogisticsNode.SIGNED);
        assertThat(view.status()).isEqualTo("SIGNED");
    }

    @Test
    void 已签收_不再推进() {
        WaybillEntity waybill = waybill("ORD-B05-1", "SF123456789012", "SIMULATED", "SIGNED");
        when(waybillMapper.selectByOrderNo("ORD-B05-1")).thenReturn(waybill);
        when(trackMapper.listByOrderNo("ORD-B05-1")).thenReturn(List.of(track("ORD-B05-1", LogisticsNode.SIGNED)));

        service.advance("ORD-B05-1");

        verify(logisticsService, never()).ingest(anyString(), any());
        verify(waybillMapper, never()).updateStatus(any(), anyString(), any());
    }

    @Test
    void 真实渠道面单_不本地推进() {
        WaybillEntity waybill = waybill("ORD-B05-1", "SF123456789012", "EXTERNAL", "CREATED");
        when(waybillMapper.selectByOrderNo("ORD-B05-1")).thenReturn(waybill);

        assertThatThrownBy(() -> service.advance("ORD-B05-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("真实渠道");
        verify(logisticsService, never()).ingest(anyString(), any());
    }

    @Test
    void 没有面单_404() {
        assertThatThrownBy(() -> service.advance("ORD-B05-1"))
                .isInstanceOf(BizException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void 面单查询_当事人与运营可见_无关者403() {
        WaybillEntity waybill = waybill("ORD-B05-1", "SF123456789012", "SIMULATED", "CREATED");
        when(orderFacts.require("ORD-B05-1")).thenReturn(paidOrder(201L, 101L));
        when(waybillMapper.selectByOrderNo("ORD-B05-1")).thenReturn(waybill);

        UserContext.set(buyer(201L));
        assertThat(service.forParty("ORD-B05-1").waybillNo()).isEqualTo("SF123456789012");

        UserContext.set(seller(101L));
        assertThat(service.forParty("ORD-B05-1").courierName()).isEqualTo("顺丰速运");

        UserContext.set(operator());
        assertThat(service.forParty("ORD-B05-1").orderNo()).isEqualTo("ORD-B05-1");

        UserContext.set(buyer(999L));
        assertThatThrownBy(() -> service.forParty("ORD-B05-1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只能查看");
    }

    private static WaybillEntity waybill(String orderNo, String waybillNo, String mode, String status) {
        WaybillEntity entity = new WaybillEntity();
        entity.setId(1L);
        entity.setOrderNo(orderNo);
        entity.setOrderId(2L);
        entity.setSellerId(101L);
        entity.setCourierCode("SF");
        entity.setCourierName("顺丰速运");
        entity.setWaybillNo(waybillNo);
        entity.setMode(mode);
        entity.setStatus(status);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }

    private static LogisticsTrackEntity track(String orderNo, String code) {
        LogisticsTrackEntity entity = new LogisticsTrackEntity();
        entity.setId(1L);
        entity.setOrderNo(orderNo);
        entity.setNodeCode(code);
        entity.setNodeText(code);
        entity.setOccurredAt(LocalDateTime.now());
        return entity;
    }
}
