package com.duoduo.product.service;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.common.api.PageResult;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.product.client.TradeClient;
import com.duoduo.product.client.dto.ListingOrderCreated;
import com.duoduo.product.client.dto.ListingOrderPlacement;
import com.duoduo.product.client.dto.ListingSoldOrderView;
import com.duoduo.product.dto.ProductDtos.ListingOrderRequest;
import com.duoduo.product.entity.ListingEntity;
import com.duoduo.product.mapper.ListingMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 闲置担保交易核心不变量（B-11 · B1 失败测试先行：本类先红，B2 实现后转绿）。
 *
 * <h2>三条不变量，谁拆测试立刻转红</h2>
 * <ol>
 *   <li><b>单件防超卖靠 CAS，不靠先查后改</b>：锁定的本质是
 *       {@code UPDATE ... WHERE status='ON_SALE' AND audit_status='APPROVED' AND deleted_at IS NULL}
 *       的条件更新 —— 影响行数为 0 就是被买走/下架/删除，任何「查完再改」的写法
 *       在并发窗口里都会一物两单，这里用 0 行用例钉死它。</li>
 *   <li><b>锁定必须可补偿</b>：委托交易域建单失败（拒绝或不可达）时，必须把
 *       SOLD 回滚成 ON_SALE 再抛 —— 一次远程抖动锁死一条真实闲置，
 *       比下单失败更坏：卖家看不见、买家买不了，也没人知道。</li>
 *   <li><b>释放必须幂等</b>：取消 / 支付超时 / 未发货退款三个钩子可能对同一单
 *       重复触发（以及两个钩子几乎同时到达），重复释放必须安全返回而不是抛错。</li>
 * </ol>
 *
 * <h2>为什么发货/卖出列表只测透传</h2>
 * 归属与订单状态的事实表（t_order）在交易域：校验放交易域一处，
 * 商品域若再校验一遍，两处口径迟早漂移 —— 测试只钉「带上登录态转发」。
 */
class ListingOrderServiceTest {

    private static final long BUYER = 720000000000000001L;
    private static final long SELLER = 720000000000000002L;
    private static final long LISTING_ID = 730000000000000001L;
    private static final String ORDER_NO = "202611081200000000001";

    private final ListingMapper listingMapper = mock(ListingMapper.class);
    private final TradeClient tradeClient = mock(TradeClient.class);
    private final ListingOrderService service = new ListingOrderService(listingMapper, tradeClient);

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private static ListingEntity listing(String status, String auditStatus) {
        ListingEntity entity = new ListingEntity();
        entity.setId(LISTING_ID);
        entity.setSellerId(SELLER);
        entity.setTitle("演示闲置：九成新猫爬架");
        entity.setPrice(8800L);
        entity.setStatus(status);
        entity.setAuditStatus(auditStatus);
        return entity;
    }

    private static ListingOrderRequest request() {
        return new ListingOrderRequest(LISTING_ID, "收货人", "13800001234", "上海市静安区演示路 1 号", "idem-key-1");
    }

    // ------------------------------------------------------------------
    // placeOrder：正常路径与快照纪律
    // ------------------------------------------------------------------

    @Test
    void 下单_CAS命中_把服务端快照事实交给交易域并返回订单号() {
        when(listingMapper.selectById(LISTING_ID)).thenReturn(listing("ON_SALE", "APPROVED"));
        when(listingMapper.update(any(), any())).thenReturn(1);
        when(tradeClient.placeListingOrder(any()))
                .thenReturn(ApiResponse.ok(new ListingOrderCreated(ORDER_NO, 8800L)));

        ListingOrderCreated created = service.placeOrder(BUYER, request());

        assertThat(created.orderNo()).isEqualTo(ORDER_NO);
        assertThat(created.payAmount()).isEqualTo(8800L);

        // 载荷里的每一个事实都来自服务端：买家=登录态、卖家=闲置归属、价格与标题=闲置快照
        var captor = org.mockito.ArgumentCaptor.forClass(ListingOrderPlacement.class);
        verify(tradeClient).placeListingOrder(captor.capture());
        ListingOrderPlacement placement = captor.getValue();
        assertThat(placement.buyerId()).isEqualTo(BUYER);
        assertThat(placement.listingId()).isEqualTo(LISTING_ID);
        assertThat(placement.sellerId()).isEqualTo(SELLER);
        assertThat(placement.title()).isEqualTo("演示闲置：九成新猫爬架");
        assertThat(placement.priceFen()).isEqualTo(8800L);
        assertThat(placement.receiverName()).isEqualTo("收货人");
        assertThat(placement.receiverPhone()).isEqualTo("13800001234");
        assertThat(placement.receiverAddress()).isEqualTo("上海市静安区演示路 1 号");
        assertThat(placement.idempotentKey()).isEqualTo("idem-key-1");
    }

    @Test
    void 下单_CAS零行_说明刚被买走或下架_422且不进交易域() {
        when(listingMapper.selectById(LISTING_ID)).thenReturn(listing("ON_SALE", "APPROVED"));
        when(listingMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.placeOrder(BUYER, request()))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.BUSINESS_RULE);

        verify(tradeClient, never()).placeListingOrder(any());
    }

    @Test
    void 幽灵闲置_下单404() {
        when(listingMapper.selectById(LISTING_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.placeOrder(BUYER, request()))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);

        verify(tradeClient, never()).placeListingOrder(any());
    }

    @Test
    void 买自己发布的闲置_422() {
        when(listingMapper.selectById(LISTING_ID)).thenReturn(listing("ON_SALE", "APPROVED"));

        assertThatThrownBy(() -> service.placeOrder(SELLER, request()))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.BUSINESS_RULE);

        verify(listingMapper, never()).update(any(), any());
        verify(tradeClient, never()).placeListingOrder(any());
    }

    @Test
    void 已下架的闲置_即便审核通过_422不触碰状态() {
        when(listingMapper.selectById(LISTING_ID)).thenReturn(listing("OFF_SHELF", "APPROVED"));

        assertThatThrownBy(() -> service.placeOrder(BUYER, request()))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.BUSINESS_RULE);

        verify(listingMapper, never()).update(any(), any());
        verify(tradeClient, never()).placeListingOrder(any());
    }

    @Test
    void 未过审的闲置_422不触碰状态() {
        when(listingMapper.selectById(LISTING_ID)).thenReturn(listing("ON_SALE", "PENDING"));

        assertThatThrownBy(() -> service.placeOrder(BUYER, request()))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.BUSINESS_RULE);

        verify(listingMapper, never()).update(any(), any());
        verify(tradeClient, never()).placeListingOrder(any());
    }

    @Test
    void 交易域拒绝建单_补偿回滚SOLD_异常原码上抛() {
        when(listingMapper.selectById(LISTING_ID)).thenReturn(listing("ON_SALE", "APPROVED"));
        when(listingMapper.update(any(), any())).thenReturn(1);
        when(tradeClient.placeListingOrder(any()))
                .thenReturn(ApiResponse.fail("BUSINESS_RULE", "下单失败"));

        assertThatThrownBy(() -> service.placeOrder(BUYER, request()))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.BUSINESS_RULE);

        // 第二次条件更新 = 补偿回滚（SOLD → ON_SALE）：锁了就必须能解开
        verify(listingMapper, times(2)).update(any(), any());
    }

    @Test
    void 交易域不可达_也要补偿回滚_并按内部错误上抛() {
        when(listingMapper.selectById(LISTING_ID)).thenReturn(listing("ON_SALE", "APPROVED"));
        when(listingMapper.update(any(), any())).thenReturn(1);
        when(tradeClient.placeListingOrder(any())).thenThrow(new RuntimeException("connect timeout"));

        assertThatThrownBy(() -> service.placeOrder(BUYER, request()))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL);

        verify(listingMapper, times(2)).update(any(), any());
    }

    @Test
    void 请求缺listingId_400参数不合法() {
        ListingOrderRequest bad = new ListingOrderRequest(null, "收货人", "13800001234", "地址", "idem");

        assertThatThrownBy(() -> service.placeOrder(BUYER, bad))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);

        verify(tradeClient, never()).placeListingOrder(any());
    }

    @Test
    void 请求缺收货三要素或幂等键_400参数不合法() {
        ListingOrderRequest bad = new ListingOrderRequest(LISTING_ID, "", "13800001234", "地址", "idem");

        assertThatThrownBy(() -> service.placeOrder(BUYER, bad))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);

        ListingOrderRequest noKey = new ListingOrderRequest(LISTING_ID, "收货人", "13800001234", "地址", " ");
        assertThatThrownBy(() -> service.placeOrder(BUYER, noKey))
                .isInstanceOf(BizException.class);

        verify(tradeClient, never()).placeListingOrder(any());
    }

    // ------------------------------------------------------------------
    // releaseToOnSale：取消 / 超时 / 退款钩子的落点
    // ------------------------------------------------------------------

    @Test
    void 释放闲置_SOLD转ON_SALE成功返回true() {
        when(listingMapper.update(any(), any())).thenReturn(1);

        assertThat(service.releaseToOnSale(LISTING_ID)).isTrue();
    }

    @Test
    void 释放闲置_已不是SOLD_幂等返回false不抛() {
        when(listingMapper.update(any(), any())).thenReturn(0);

        assertThat(service.releaseToOnSale(LISTING_ID)).isFalse();
    }

    // ------------------------------------------------------------------
    // listSold / ship：透传纪律（事实与校验在交易域）
    // ------------------------------------------------------------------

    @Test
    void 卖出列表_把卖家身份与分页透传交易域() {
        PageResult<ListingSoldOrderView> page = PageResult.of(
                List.of(new ListingSoldOrderView(ORDER_NO, "PAID", LISTING_ID,
                        "演示闲置：九成新猫爬架", 8800L, null, null, LocalDateTime.now())),
                1L, 1, 10);
        when(tradeClient.soldOrders(SELLER, null, 1, 10)).thenReturn(ApiResponse.ok(page));

        PageResult<ListingSoldOrderView> result = service.listSold(SELLER, null, 1, 10);

        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.items().get(0).orderNo()).isEqualTo(ORDER_NO);
        verify(tradeClient).soldOrders(eq(SELLER), eq(null), eq(1), eq(10));
    }

    @Test
    void 卖出列表_交易域失败_不吞错误() {
        when(tradeClient.soldOrders(anyLong(), any(), anyInt(), anyInt()))
                .thenReturn(ApiResponse.fail("DEGRADED", "交易域不可用"));

        assertThatThrownBy(() -> service.listSold(SELLER, null, 1, 10))
                .isInstanceOf(BizException.class);
    }

    @Test
    void 卖家发货_带登录态与物流信息转发交易域() {
        when(tradeClient.shipListingOrder(ORDER_NO, SELLER, "顺丰速运", "SF-123"))
                .thenReturn(ApiResponse.ok(null));

        service.ship(SELLER, ORDER_NO, "顺丰速运", "SF-123");

        verify(tradeClient).shipListingOrder(ORDER_NO, SELLER, "顺丰速运", "SF-123");
    }

    @Test
    void 卖家发货_交易域拒绝时_原码透传上抛不吞错() {
        when(tradeClient.shipListingOrder(anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(ApiResponse.fail("ORDER_STATUS_ILLEGAL", "只有 PAID 状态可发货"));

        assertThatThrownBy(() -> service.ship(SELLER, ORDER_NO, "顺丰速运", "SF-123"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_STATUS_ILLEGAL);
    }
}
