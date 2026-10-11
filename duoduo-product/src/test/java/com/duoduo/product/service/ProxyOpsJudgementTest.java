package com.duoduo.product.service;

import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.product.dto.ProxyDtos.ProxyOffShelfRequest;
import com.duoduo.product.entity.ProxyWorkOrderEntity;
import com.duoduo.product.entity.SpuEntity;
import com.duoduo.product.mapper.ProxyWorkOrderMapper;
import com.duoduo.product.mapper.SpuMapper;
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
 * E23 · 代运营工单判据矩阵（商品域）。
 *
 * <h2>与交易域同名测试的关系</h2>
 * 两张表、两套代码，判据必须**逐条各自钉住**。历史上最贵的缺陷就是
 * 「一处口径改了、另一处忘了」—— 两个服务的代码不共享，能共享的只有
 * <b>契约</b>（列名、状态取值、判据顺序），而契约只能靠两边各有一套等价的断言来维持。
 * 所以这里不做「交易域测过了、商品域跳过」的省事处理。
 *
 * <h2>每条"被拒"都配一条"有权者能过"</h2>
 * 只断言"拒绝了"是不够的：把整个方法改成永远抛 403 也能让所有否定断言变绿。
 * 正对照证明的是「通道本身是通的，被拒是因为这个具体原因」。
 */
class ProxyOpsJudgementTest {

    private static final long TENANT = 9101L;
    private static final long OTHER_TENANT = 9102L;
    private static final long SPU_ID = 603001L;

    private final ProxyWorkOrderMapper workOrderMapper = mock(ProxyWorkOrderMapper.class);
    private final SpuMapper spuMapper = mock(SpuMapper.class);
    private final SellerProductService sellerProductService = mock(SellerProductService.class);
    private final ProxyOpsService service = new ProxyOpsService(
            workOrderMapper, spuMapper, sellerProductService, mock(SnowflakeIdGenerator.class));

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ---------------- 夹具 ----------------

    private void loginAs(String role) {
        UserContext.set(new LoginUser(7001L, "13800000001", role, true));
    }

    private static SpuEntity spu(long sellerId) {
        return spu(sellerId, "merchant");
    }

    /**
     * 供货类型要能单独指定。
     *
     * <p>「自营」在库里由**两个**信号共同刻画：归属列 {@code seller_id = 0} 与
     * 供货类型 {@code supply_type}（权威口径见 {@code SellerProductService.isPlatformSelf}）。
     * 只给 seller_id 的旧夹具默认 merchant，那正好落在「两个信号打架」的模糊区 ——
     * 于是「自营不能走代运营」这条断言测的其实是模糊区的行为，而不是自营。
     */
    private static SpuEntity spu(long sellerId, String supplyType) {
        SpuEntity spu = new SpuEntity();
        spu.setId(SPU_ID);
        spu.setSellerId(sellerId);
        spu.setTitle("第三方宠物粮");
        spu.setStatus("ON_SALE");
        spu.setSupplyType(supplyType);
        spu.setAuditStatus("APPROVED");
        return spu;
    }

    private static ProxyWorkOrderEntity ticket(String action, String targetRef, long tenant) {
        ProxyWorkOrderEntity wo = new ProxyWorkOrderEntity();
        wo.setId(88011L);
        wo.setWorkOrderNo("WO-PRD-20261011000000-00001");
        wo.setTenantId(tenant);
        wo.setTargetType(ProxyWorkOrderEntity.TARGET_PRODUCT);
        wo.setTargetRef(targetRef);
        wo.setActionType(action);
        wo.setReason("该商品被投诉，店主请求平台协助下架");
        wo.setRequesterUserId(91011L);
        wo.setStatus(ProxyWorkOrderEntity.STATUS_OPEN);
        wo.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return wo;
    }

    private void stubSpu(SpuEntity s) {
        when(spuMapper.selectById(SPU_ID)).thenReturn(s);
    }

    private void stubTicket(ProxyWorkOrderEntity wo) {
        when(workOrderMapper.selectByNo(wo.getWorkOrderNo())).thenReturn(wo);
        when(workOrderMapper.consume(eq(wo.getId()), anyLong(), any())).thenReturn(1);
    }

    private static ProxyOffShelfRequest req(String no) {
        return new ProxyOffShelfRequest(no, "已与店主电话确认，代为下架");
    }

    private static ErrorCode codeOf(Throwable t) {
        return ((BizException) t).getErrorCode();
    }

    // ---------------- 1. 权限 ----------------

    @Test
    @DisplayName("卖家调用代下架 → 403，且不触碰工单表")
    void sellerCannotProxyOffShelf() {
        loginAs("SELLER");
        assertThatThrownBy(() -> service.proxyOffShelf(SPU_ID, req("WO-ANY")))
                .isInstanceOf(BizException.class)
                .extracting(ProxyOpsJudgementTest::codeOf)
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(workOrderMapper, never()).selectByNo(anyString());
    }

    // ---------------- 2. 工单本身能不能用 ----------------

    @Test
    @DisplayName("工单不存在 → 404")
    void unknownTicket() {
        loginAs("OPERATOR");
        stubSpu(spu(TENANT));
        when(workOrderMapper.selectByNo("WO-NOT-EXIST")).thenReturn(null);
        assertThatThrownBy(() -> service.proxyOffShelf(SPU_ID, req("WO-NOT-EXIST")))
                .isInstanceOf(BizException.class)
                .extracting(ProxyOpsJudgementTest::codeOf)
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("工单已被消费 → 400，且不下架商品")
    void consumedTicket() {
        loginAs("OPERATOR");
        stubSpu(spu(TENANT));
        ProxyWorkOrderEntity wo = ticket(ProxyWorkOrderEntity.ACTION_OFF_SHELF,
                String.valueOf(SPU_ID), TENANT);
        wo.setStatus(ProxyWorkOrderEntity.STATUS_CONSUMED);
        when(workOrderMapper.selectByNo(wo.getWorkOrderNo())).thenReturn(wo);

        assertThatThrownBy(() -> service.proxyOffShelf(SPU_ID, req(wo.getWorkOrderNo())))
                .isInstanceOf(BizException.class)
                .extracting(ProxyOpsJudgementTest::codeOf)
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
        verify(sellerProductService, never()).forceOffShelfByProxy(anyLong(), anyString());
    }

    @Test
    @DisplayName("工单已被店主撤销（VOID）→ 400")
    void voidedTicket() {
        loginAs("OPERATOR");
        stubSpu(spu(TENANT));
        ProxyWorkOrderEntity wo = ticket(ProxyWorkOrderEntity.ACTION_OFF_SHELF,
                String.valueOf(SPU_ID), TENANT);
        wo.setStatus(ProxyWorkOrderEntity.STATUS_VOID);
        when(workOrderMapper.selectByNo(wo.getWorkOrderNo())).thenReturn(wo);

        assertThatThrownBy(() -> service.proxyOffShelf(SPU_ID, req(wo.getWorkOrderNo())))
                .isInstanceOf(BizException.class)
                .extracting(ProxyOpsJudgementTest::codeOf)
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("工单的目标是另一件商品 → 400（一张工单只能动一个目标）")
    void ticketForAnotherProduct() {
        loginAs("OPERATOR");
        stubSpu(spu(TENANT));
        ProxyWorkOrderEntity wo = ticket(ProxyWorkOrderEntity.ACTION_OFF_SHELF, "603999", TENANT);
        when(workOrderMapper.selectByNo(wo.getWorkOrderNo())).thenReturn(wo);

        assertThatThrownBy(() -> service.proxyOffShelf(SPU_ID, req(wo.getWorkOrderNo())))
                .isInstanceOf(BizException.class)
                .extracting(ProxyOpsJudgementTest::codeOf)
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("申请的是代发货（订单类动作），却拿来下架商品 → 400")
    void actionMismatch() {
        loginAs("OPERATOR");
        stubSpu(spu(TENANT));
        ProxyWorkOrderEntity wo = ticket(ProxyWorkOrderEntity.ACTION_SHIP,
                String.valueOf(SPU_ID), TENANT);
        when(workOrderMapper.selectByNo(wo.getWorkOrderNo())).thenReturn(wo);

        assertThatThrownBy(() -> service.proxyOffShelf(SPU_ID, req(wo.getWorkOrderNo())))
                .isInstanceOf(BizException.class)
                .extracting(ProxyOpsJudgementTest::codeOf)
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("工单的目标类型不是商品（如订单）→ 400")
    void targetTypeMismatch() {
        loginAs("OPERATOR");
        stubSpu(spu(TENANT));
        ProxyWorkOrderEntity wo = ticket(ProxyWorkOrderEntity.ACTION_OFF_SHELF,
                String.valueOf(SPU_ID), TENANT);
        wo.setTargetType(ProxyWorkOrderEntity.TARGET_ORDER);
        when(workOrderMapper.selectByNo(wo.getWorkOrderNo())).thenReturn(wo);

        assertThatThrownBy(() -> service.proxyOffShelf(SPU_ID, req(wo.getWorkOrderNo())))
                .isInstanceOf(BizException.class)
                .extracting(ProxyOpsJudgementTest::codeOf)
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    // ---------------- 3. 安全关键：租户错配 ----------------

    @Test
    @DisplayName("★ 拿 A 店的工单动 B 店的商品 → 403（本机制最典型的漏洞形态）")
    void tenantMismatch_isForbidden() {
        loginAs("OPERATOR");
        stubSpu(spu(OTHER_TENANT));
        ProxyWorkOrderEntity wo = ticket(ProxyWorkOrderEntity.ACTION_OFF_SHELF,
                String.valueOf(SPU_ID), TENANT);
        when(workOrderMapper.selectByNo(wo.getWorkOrderNo())).thenReturn(wo);

        assertThatThrownBy(() -> service.proxyOffShelf(SPU_ID, req(wo.getWorkOrderNo())))
                .isInstanceOf(BizException.class)
                .extracting(ProxyOpsJudgementTest::codeOf)
                .isEqualTo(ErrorCode.FORBIDDEN);
        // A 店的授权**没有被消费掉**：否则 A 店白丢一次授权、B 店什么也没发生，
        // 而账面上看不出任何异常
        verify(workOrderMapper, never()).consume(anyLong(), anyLong(), any());
        verify(sellerProductService, never()).forceOffShelfByProxy(anyLong(), anyString());
    }

    // ---------------- 4. 自营不走代运营 ----------------

    @Test
    @DisplayName("平台自营商品（supply_type=self 且 seller_id=0）不能走代运营 → 422（那份「商家授权」是假的）")
    void selfOperatedProductRejected() {
        loginAs("OPERATOR");
        stubSpu(spu(0L, "self"));
        ProxyWorkOrderEntity wo = ticket(ProxyWorkOrderEntity.ACTION_OFF_SHELF,
                String.valueOf(SPU_ID), 0L);
        when(workOrderMapper.selectByNo(wo.getWorkOrderNo())).thenReturn(wo);

        assertThatThrownBy(() -> service.proxyOffShelf(SPU_ID, req(wo.getWorkOrderNo())))
                .isInstanceOf(BizException.class)
                .extracting(ProxyOpsJudgementTest::codeOf)
                .isEqualTo(ErrorCode.BUSINESS_RULE);
        verify(sellerProductService, never()).forceOffShelfByProxy(anyLong(), anyString());
    }

    /**
     * 两个自营信号**打架**时（seller_id=0 但 supply_type=merchant）按「第三方」对待。
     *
     * <p>这条守的是判据的方向性：判重（要求一张工单）的代价是多走一步流程，
     * 判轻（当成自营直接处置）的代价是越权。既然两个信号里有一个指向第三方，
     * 就必须按第三方走 —— 反过来"只要 seller_id 是 0 就当自营"会把
     * 「归属列被改错/被清空」变成一条免授权通道。
     *
     * <p>这条断言同时钉住了「口径只有一个出口」：判据从
     * {@code SellerProductService.isPlatformSelf} 取，而不是本类再写一份。
     */
    @Test
    @DisplayName("seller_id=0 但 supply_type=merchant（信号打架）→ 按第三方处理，持有效工单可下架")
    void ambiguousSelfOperatedSignalsTreatedAsThirdParty() {
        loginAs("OPERATOR");
        stubSpu(spu(0L, "merchant"));
        ProxyWorkOrderEntity wo = ticket(ProxyWorkOrderEntity.ACTION_OFF_SHELF,
                String.valueOf(SPU_ID), 0L);
        when(workOrderMapper.selectByNo(wo.getWorkOrderNo())).thenReturn(wo);
        when(workOrderMapper.consume(anyLong(), anyLong(), any())).thenReturn(1);

        service.proxyOffShelf(SPU_ID, req(wo.getWorkOrderNo()));

        verify(workOrderMapper).consume(eq(wo.getId()), eq(7001L), any());
        verify(sellerProductService).forceOffShelfByProxy(eq(SPU_ID), anyString());
    }

    // ---------------- 5. 入参必填 ----------------

    @Test
    @DisplayName("工单号为空 → 400")
    void blankWorkOrderNo() {
        loginAs("OPERATOR");
        assertThatThrownBy(() -> service.proxyOffShelf(SPU_ID, req("  ")))
                .isInstanceOf(BizException.class)
                .extracting(ProxyOpsJudgementTest::codeOf)
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("执行说明为空 → 400")
    void blankReason() {
        loginAs("OPERATOR");
        stubSpu(spu(TENANT));
        assertThatThrownBy(() -> service.proxyOffShelf(SPU_ID,
                new ProxyOffShelfRequest("WO-PRD-X", "   ")))
                .isInstanceOf(BizException.class)
                .extracting(ProxyOpsJudgementTest::codeOf)
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("商品不存在 → 404")
    void unknownProduct() {
        loginAs("OPERATOR");
        when(spuMapper.selectById(SPU_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.proxyOffShelf(SPU_ID, req("WO-PRD-X")))
                .isInstanceOf(BizException.class)
                .extracting(ProxyOpsJudgementTest::codeOf)
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ---------------- 6. 并发：一个授权只能做一次操作 ----------------

    @Test
    @DisplayName("★ 并发下工单已被别人消费（CAS 返回 0 行）→ 409，且不下架")
    void concurrentConsumeConflicts() {
        loginAs("OPERATOR");
        stubSpu(spu(TENANT));
        ProxyWorkOrderEntity wo = ticket(ProxyWorkOrderEntity.ACTION_OFF_SHELF,
                String.valueOf(SPU_ID), TENANT);
        when(workOrderMapper.selectByNo(wo.getWorkOrderNo())).thenReturn(wo);
        when(workOrderMapper.consume(eq(wo.getId()), anyLong(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.proxyOffShelf(SPU_ID, req(wo.getWorkOrderNo())))
                .isInstanceOf(BizException.class)
                .extracting(ProxyOpsJudgementTest::codeOf)
                .isEqualTo(ErrorCode.CONFLICT);
        verify(sellerProductService, never()).forceOffShelfByProxy(anyLong(), anyString());
    }

    // ---------------- 7. 正对照：有权者能过 ----------------

    @Test
    @DisplayName("★ 正对照：运营持本店有效工单代下架 → 成功，且工单被消费")
    void operatorWithValidTicket_succeeds() {
        loginAs("OPERATOR");
        stubSpu(spu(TENANT));
        ProxyWorkOrderEntity wo = ticket(ProxyWorkOrderEntity.ACTION_OFF_SHELF,
                String.valueOf(SPU_ID), TENANT);
        stubTicket(wo);

        service.proxyOffShelf(SPU_ID, req(wo.getWorkOrderNo()));

        verify(workOrderMapper).consume(eq(wo.getId()), eq(7001L), any());
        // 必须走 SellerProductService 的写路径，而不是绕过它直接 updateById ——
        // 绕过会让「商品状态变更」这件事出现两处实现，迟早分叉
        verify(sellerProductService).forceOffShelfByProxy(eq(SPU_ID), anyString());
        assertThat(wo.getStatus()).isEqualTo(ProxyWorkOrderEntity.STATUS_CONSUMED);
    }

    @Test
    @DisplayName("正对照：管理员同样有权（矩阵里 ADMIN 也持有 tenant:proxy-ops）")
    void adminAlsoAllowed() {
        loginAs("ADMIN");
        stubSpu(spu(TENANT));
        ProxyWorkOrderEntity wo = ticket(ProxyWorkOrderEntity.ACTION_OFF_SHELF,
                String.valueOf(SPU_ID), TENANT);
        stubTicket(wo);

        service.proxyOffShelf(SPU_ID, req(wo.getWorkOrderNo()));
        verify(workOrderMapper).consume(eq(wo.getId()), eq(7001L), any());
    }
}
