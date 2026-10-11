package com.duoduo.trade.service;

import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.trade.dto.ProxyDtos.ProxyAftersaleRequest;
import com.duoduo.trade.dto.ProxyDtos.ProxyShipRequest;
import com.duoduo.trade.entity.OrderEntity;
import com.duoduo.trade.entity.ProxyWorkOrderEntity;
import com.duoduo.trade.mapper.OrderMapper;
import com.duoduo.trade.mapper.ProxyWorkOrderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E23 · 代运营工单判据矩阵（交易域）。
 *
 * <h2>为什么这一组必须逐条单测，而不是靠冒烟</h2>
 * 代运营的唯一价值就是「无有效授权不得动别人的订单」。授权判据有 6 个维度
 * （工单存在 / 状态 / 目标类型 / 动作 / 目标 / 租户），冒烟很难把
 * <b>每一种错配</b>都走一遍，而<b>错配恰恰是这里唯一的失败形态</b> ——
 * 判据写漏一个维度，表现不是报错，而是「授权被放过」，
 * 系统一切正常，直到某天有人拿 A 店的工单动了 B 店。
 *
 * <h2>每条"被拒"都配一条"有权者能过"</h2>
 * 只断言"拒绝了"是不够的：把整个方法改成永远抛 403 也能让所有否定断言变绿。
 * 所以每组都有一条正对照（{@link #operatorWithValidTicket_ship_succeeds} 等），
 * 它们证明的是「通道本身是通的，被拒是因为这个具体原因」。
 *
 * <h2>断言状态码而不是异常消息</h2>
 * 消息会随文案调整而变，状态码是本项目对外承诺的契约
 * （400 参数 / 403 无权限 / 404 不存在 / 409 冲突 / 422 规则）。
 */
class ProxyOpsJudgementTest {

    private static final long TENANT = 9001L;
    private static final long OTHER_TENANT = 9002L;
    private static final String ORDER_NO = "PO202610110000001";

    private final ProxyWorkOrderMapper workOrderMapper = mock(ProxyWorkOrderMapper.class);
    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final OrderService orderService = mock(OrderService.class);
    private final SellerService sellerService = mock(SellerService.class);
    private final ProxyOpsService service = new ProxyOpsService(
            workOrderMapper, orderMapper, orderService, sellerService,
            mock(SnowflakeIdGenerator.class));

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ---------------- 夹具 ----------------

    private static LoginUser user(String role) {
        return new LoginUser(7001L, "13800000001", role, true);
    }

    private void loginAs(String role) {
        UserContext.set(user(role));
    }

    private static OrderEntity order(long sellerId) {
        OrderEntity o = new OrderEntity();
        o.setId(555001L);
        o.setOrderNo(ORDER_NO);
        o.setSellerId(sellerId);
        o.setStatus("PAID");
        return o;
    }

    private static ProxyWorkOrderEntity ticket(String action, String targetRef, long tenant) {
        ProxyWorkOrderEntity wo = new ProxyWorkOrderEntity();
        wo.setId(88001L);
        wo.setWorkOrderNo("WO-ORD-20261011000000-00001");
        wo.setTenantId(tenant);
        wo.setTargetType(ProxyWorkOrderEntity.TARGET_ORDER);
        wo.setTargetRef(targetRef);
        wo.setActionType(action);
        wo.setReason("店主外出，客服电话委托平台协助发货");
        wo.setRequesterUserId(90011L);
        wo.setStatus(ProxyWorkOrderEntity.STATUS_OPEN);
        wo.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return wo;
    }

    private void stubOrder(OrderEntity o) {
        when(orderMapper.selectOne(any())).thenReturn(o);
    }

    private void stubTicket(ProxyWorkOrderEntity wo) {
        when(workOrderMapper.selectByNo(wo.getWorkOrderNo())).thenReturn(wo);
        when(workOrderMapper.consume(eq(wo.getId()), anyLong(), any())).thenReturn(1);
    }

    private static ProxyShipRequest shipReq(String no) {
        return new ProxyShipRequest(no, "已按店主电话确认，代为发货", "顺丰速运", "SF9988776655");
    }

    private static ErrorCode codeOf(Throwable t) {
        return ((BizException) t).getErrorCode();
    }

    // ---------------- 1. 权限 ----------------

    @Test
    @DisplayName("卖家调用代发货 → 403，且不触碰工单表")
    void sellerCannotProxyShip() {
        loginAs("SELLER");
        // 注意：连订单与工单都没 stub —— 若实现先查库再判权，这里会 NPE/返回 404，
        // 而正确答案是 403。类型不对的失败比断言失败更值得看。
        assertThatThrownBy(() -> service.proxyShip(ORDER_NO, shipReq("WO-ANY")))
                .isInstanceOf(BizException.class)
                .extracting(ProxyOpsJudgementTest::codeOf)
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(workOrderMapper, never()).selectByNo(anyString());
    }

    @Test
    @DisplayName("普通买家调用代发货 → 403")
    void buyerCannotProxyShip() {
        loginAs("USER");
        assertThatThrownBy(() -> service.proxyShip(ORDER_NO, shipReq("WO-ANY")))
                .isInstanceOf(BizException.class)
                .extracting(ProxyOpsJudgementTest::codeOf)
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    // ---------------- 2. 工单本身能不能用 ----------------

    @Test
    @DisplayName("工单不存在 → 404")
    void unknownTicket() {
        loginAs("OPERATOR");
        stubOrder(order(TENANT));
        when(workOrderMapper.selectByNo("WO-NOT-EXIST")).thenReturn(null);
        assertThatThrownBy(() -> service.proxyShip(ORDER_NO, shipReq("WO-NOT-EXIST")))
                .isInstanceOf(BizException.class)
                .extracting(ProxyOpsJudgementTest::codeOf)
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("工单已被消费 → 400（授权一次性，不可重复使用）")
    void consumedTicket() {
        loginAs("OPERATOR");
        stubOrder(order(TENANT));
        ProxyWorkOrderEntity wo = ticket(ProxyWorkOrderEntity.ACTION_SHIP, ORDER_NO, TENANT);
        wo.setStatus(ProxyWorkOrderEntity.STATUS_CONSUMED);
        when(workOrderMapper.selectByNo(wo.getWorkOrderNo())).thenReturn(wo);

        assertThatThrownBy(() -> service.proxyShip(ORDER_NO, shipReq(wo.getWorkOrderNo())))
                .isInstanceOf(BizException.class)
                .extracting(ProxyOpsJudgementTest::codeOf)
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
        // ★ 被拒时**不得**动订单状态 —— 「拒绝了」与「拒绝了但动作还是执行了」
        //   是两回事，而后者才是真正的越权
        verify(orderService, never()).shipInternal(any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("工单已被店主撤销（VOID）→ 400")
    void voidedTicket() {
        loginAs("OPERATOR");
        stubOrder(order(TENANT));
        ProxyWorkOrderEntity wo = ticket(ProxyWorkOrderEntity.ACTION_SHIP, ORDER_NO, TENANT);
        wo.setStatus(ProxyWorkOrderEntity.STATUS_VOID);
        when(workOrderMapper.selectByNo(wo.getWorkOrderNo())).thenReturn(wo);

        assertThatThrownBy(() -> service.proxyShip(ORDER_NO, shipReq(wo.getWorkOrderNo())))
                .isInstanceOf(BizException.class)
                .extracting(ProxyOpsJudgementTest::codeOf)
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("工单的目标对象是另一笔订单 → 400（一张工单只能动一个目标）")
    void ticketForAnotherOrder() {
        loginAs("OPERATOR");
        stubOrder(order(TENANT));
        ProxyWorkOrderEntity wo = ticket(ProxyWorkOrderEntity.ACTION_SHIP, "PO202610110000999", TENANT);
        when(workOrderMapper.selectByNo(wo.getWorkOrderNo())).thenReturn(wo);

        assertThatThrownBy(() -> service.proxyShip(ORDER_NO, shipReq(wo.getWorkOrderNo())))
                .isInstanceOf(BizException.class)
                .extracting(ProxyOpsJudgementTest::codeOf)
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("申请的是代处理售后，却拿去代发货 → 400（动作必须一一对应）")
    void actionMismatch() {
        loginAs("OPERATOR");
        stubOrder(order(TENANT));
        ProxyWorkOrderEntity wo = ticket(ProxyWorkOrderEntity.ACTION_AFTERSALE, ORDER_NO, TENANT);
        when(workOrderMapper.selectByNo(wo.getWorkOrderNo())).thenReturn(wo);

        assertThatThrownBy(() -> service.proxyShip(ORDER_NO, shipReq(wo.getWorkOrderNo())))
                .isInstanceOf(BizException.class)
                .extracting(ProxyOpsJudgementTest::codeOf)
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("工单的目标类型不是订单（如商品）→ 400")
    void targetTypeMismatch() {
        loginAs("OPERATOR");
        stubOrder(order(TENANT));
        ProxyWorkOrderEntity wo = ticket(ProxyWorkOrderEntity.ACTION_SHIP, ORDER_NO, TENANT);
        wo.setTargetType(ProxyWorkOrderEntity.TARGET_PRODUCT);
        when(workOrderMapper.selectByNo(wo.getWorkOrderNo())).thenReturn(wo);

        assertThatThrownBy(() -> service.proxyShip(ORDER_NO, shipReq(wo.getWorkOrderNo())))
                .isInstanceOf(BizException.class)
                .extracting(ProxyOpsJudgementTest::codeOf)
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    // ---------------- 3. 安全关键：租户错配 ----------------

    @Test
    @DisplayName("★ 拿 A 店的工单动 B 店的订单 → 403（本机制最典型的漏洞形态）")
    void tenantMismatch_isForbidden() {
        loginAs("OPERATOR");
        stubOrder(order(OTHER_TENANT));
        ProxyWorkOrderEntity wo = ticket(ProxyWorkOrderEntity.ACTION_SHIP, ORDER_NO, TENANT);
        when(workOrderMapper.selectByNo(wo.getWorkOrderNo())).thenReturn(wo);

        assertThatThrownBy(() -> service.proxyShip(ORDER_NO, shipReq(wo.getWorkOrderNo())))
                .isInstanceOf(BizException.class)
                .extracting(ProxyOpsJudgementTest::codeOf)
                .isEqualTo(ErrorCode.FORBIDDEN);
        // 同时必须**没有**消费掉那张工单 —— 否则 A 店白白失去一次授权，
        // 而 B 店什么也没发生，账面上却看不出任何异常
        verify(workOrderMapper, never()).consume(anyLong(), anyLong(), any());
        verify(orderService, never()).shipInternal(any(), anyString(), any(), any());
    }

    // ---------------- 4. 自营不走代运营 ----------------

    @Test
    @DisplayName("平台自营订单（seller_id=0）不能走代运营 → 422（那份「商家授权」是假的）")
    void selfOperatedOrderRejected() {
        loginAs("OPERATOR");
        stubOrder(order(0L));
        ProxyWorkOrderEntity wo = ticket(ProxyWorkOrderEntity.ACTION_SHIP, ORDER_NO, 0L);
        when(workOrderMapper.selectByNo(wo.getWorkOrderNo())).thenReturn(wo);

        assertThatThrownBy(() -> service.proxyShip(ORDER_NO, shipReq(wo.getWorkOrderNo())))
                .isInstanceOf(BizException.class)
                .extracting(ProxyOpsJudgementTest::codeOf)
                .isEqualTo(ErrorCode.BUSINESS_RULE);
        verify(orderService, never()).shipInternal(any(), anyString(), any(), any());
    }

    // ---------------- 5. 入参必填 ----------------

    @Test
    @DisplayName("工单号为空 → 400")
    void blankWorkOrderNo() {
        loginAs("OPERATOR");
        assertThatThrownBy(() -> service.proxyShip(ORDER_NO, shipReq("   ")))
                .isInstanceOf(BizException.class)
                .extracting(ProxyOpsJudgementTest::codeOf)
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("执行说明为空 → 400（全空格的说明在页面上像填了、在库里等于没填）")
    void blankReason() {
        loginAs("OPERATOR");
        stubOrder(order(TENANT));
        assertThatThrownBy(() -> service.proxyShip(ORDER_NO,
                new ProxyShipRequest("WO-ORD-X", "   ", null, null)))
                .isInstanceOf(BizException.class)
                .extracting(ProxyOpsJudgementTest::codeOf)
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    // ---------------- 6. 并发：一个授权只能做一次操作 ----------------

    @Test
    @DisplayName("★ 并发下工单已被别人消费（CAS 返回 0 行）→ 409，且动作不执行")
    void concurrentConsumeConflicts() {
        loginAs("OPERATOR");
        stubOrder(order(TENANT));
        ProxyWorkOrderEntity wo = ticket(ProxyWorkOrderEntity.ACTION_SHIP, ORDER_NO, TENANT);
        when(workOrderMapper.selectByNo(wo.getWorkOrderNo())).thenReturn(wo);
        // 读的时候还是 OPEN，消费时才发现被别人抢先 —— 这正是并发窗口的形状
        when(workOrderMapper.consume(eq(wo.getId()), anyLong(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.proxyShip(ORDER_NO, shipReq(wo.getWorkOrderNo())))
                .isInstanceOf(BizException.class)
                .extracting(ProxyOpsJudgementTest::codeOf)
                .isEqualTo(ErrorCode.CONFLICT);
        verify(orderService, never()).shipInternal(any(), anyString(), any(), any());
    }

    // ---------------- 7. 正对照：有权者能过 ----------------

    @Test
    @DisplayName("★ 正对照：运营持本店有效工单代发货 → 成功，且工单被消费")
    void operatorWithValidTicket_ship_succeeds() {
        loginAs("OPERATOR");
        OrderEntity o = order(TENANT);
        stubOrder(o);
        ProxyWorkOrderEntity wo = ticket(ProxyWorkOrderEntity.ACTION_SHIP, ORDER_NO, TENANT);
        stubTicket(wo);

        service.proxyShip(ORDER_NO, shipReq(wo.getWorkOrderNo()));

        // 工单必须被消费掉：授权是一次性的，能重复用就等于没有「一次性」这回事
        verify(workOrderMapper).consume(eq(wo.getId()), eq(7001L), any());
        // 发货必须走与卖家端**同一段状态机**（shipInternal），不能另写一套
        verify(orderService).shipInternal(eq(o), anyString(), eq("顺丰速运"), eq("SF9988776655"));
        assertThat(wo.getStatus()).isEqualTo(ProxyWorkOrderEntity.STATUS_CONSUMED);
    }

    @Test
    @DisplayName("★ 正对照：运营持本店有效工单代处理售后 → 成功")
    void operatorWithValidTicket_aftersale_succeeds() {
        loginAs("OPERATOR");
        OrderEntity o = order(TENANT);
        stubOrder(o);
        ProxyWorkOrderEntity wo = ticket(ProxyWorkOrderEntity.ACTION_AFTERSALE, ORDER_NO, TENANT);
        stubTicket(wo);

        service.proxyAftersale(ORDER_NO, new ProxyAftersaleRequest(
                wo.getWorkOrderNo(), "店主确认已收到退货，代为退款"));

        verify(workOrderMapper).consume(eq(wo.getId()), eq(7001L), any());
        verify(orderService).refundInternal(eq(o), anyString(), anyString());
    }

    @Test
    @DisplayName("正对照：管理员同样有权（矩阵里 ADMIN 也持有 tenant:proxy-ops）")
    void adminAlsoAllowed() {
        loginAs("ADMIN");
        stubOrder(order(TENANT));
        ProxyWorkOrderEntity wo = ticket(ProxyWorkOrderEntity.ACTION_SHIP, ORDER_NO, TENANT);
        stubTicket(wo);

        service.proxyShip(ORDER_NO, shipReq(wo.getWorkOrderNo()));
        verify(workOrderMapper).consume(eq(wo.getId()), eq(7001L), any());
    }
}
