package com.duoduo.trade.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.trade.client.ListingOrderClient;
import com.duoduo.trade.client.OrderNotifier;
import com.duoduo.trade.dto.OrderDtos.ListingOrderCreated;
import com.duoduo.trade.dto.OrderDtos.ListingOrderPlacement;
import com.duoduo.trade.dto.OrderDtos.ListingSoldOrderView;
import com.duoduo.trade.entity.OrderEntity;
import com.duoduo.trade.entity.OrderItemEntity;
import com.duoduo.trade.mapper.OrderItemMapper;
import com.duoduo.trade.mapper.OrderMapper;
import com.duoduo.trade.support.OrderNoGenerator;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 闲置担保交易的交易域不变量（B-11 · B2）。
 *
 * <h2>这层钉什么</h2>
 * 商品域只管「锁」，本域管「订单事实」——这里断言四件一旦走样
 * 就直接是资金/货权事故的事：
 * <ol>
 *   <li><b>C2C 单的身份</b>：orderType=C2C、单件明细（listingId + 快照）、
 *       不冻结库存 —— 冻结对 C2C 是空集，谁给加上了谁就是在库存台账里造幽灵行。</li>
 *   <li><b>发货的准入</b>：只有「本人的闲置单」能从这条入口发货 ——
 *       商城单（NORMAL/SECKILL）从商家端走，冒名进来必须 422/403。</li>
 *   <li><b>卖出列表的归属</b>：seller_id 与 order_type=C2C 必须拼进查询，
 *       丢一个就是把别人的订单卖给别人看（同 OrderListFilterTest 的判据）。</li>
 *   <li><b>释放回调的边界</b>：只对 C2C 单回调商品域释放 ——
 *       商城单的关单/退款不该惊动任何闲置。</li>
 * </ol>
 */
class ListingOrderTradeServiceTest {

    private static final long BUYER = 820000000000000001L;
    private static final long SELLER = 820000000000000002L;
    private static final long LISTING_ID = 830000000000000001L;
    private static final String ORDER_NO = "202611081200000000001";

    private OrderMapper orderMapper;
    private OrderItemMapper orderItemMapper;
    private SettlementService settlementService;
    private ListingOrderClient listingOrderClient;
    private OrderService service;

    @BeforeAll
    static void initMybatisLambdaCache() {
        // LambdaQueryWrapper 的列名解析依赖实体元数据缓存（纯单测无 Spring 扫描）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), OrderEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), OrderItemEntity.class);
    }

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        orderItemMapper = mock(OrderItemMapper.class);
        settlementService = mock(SettlementService.class);
        listingOrderClient = mock(ListingOrderClient.class);
        SnowflakeIdGenerator idGenerator = mock(SnowflakeIdGenerator.class);
        OrderNoGenerator orderNoGenerator = mock(OrderNoGenerator.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        when(idGenerator.nextId()).thenReturn(840000000000000001L);
        when(orderNoGenerator.next()).thenReturn(ORDER_NO);

        service = new OrderService(orderMapper, orderItemMapper,
                mock(com.duoduo.trade.mapper.OrderStatusLogMapper.class),
                null, null, null, null, null, null,
                settlementService, null, idGenerator, orderNoGenerator,
                eventPublisher, null, null, null, null, null, null, null,
                listingOrderClient);
    }

    private static ListingOrderPlacement placement() {
        return new ListingOrderPlacement(BUYER, LISTING_ID, SELLER,
                "演示闲置：九成新猫爬架", 8800L,
                "收货人", "13800001234", "上海市静安区演示路 1 号", "idem-b11-1");
    }

    private static OrderEntity order(String orderType, String status) {
        OrderEntity order = new OrderEntity();
        order.setId(840000000000000001L);
        order.setOrderNo(ORDER_NO);
        order.setUserId(BUYER);
        order.setSellerId(SELLER);
        order.setOrderType(orderType);
        order.setStatus(status);
        order.setPayAmount(8800L);
        return order;
    }

    private static OrderItemEntity listingItem() {
        OrderItemEntity item = new OrderItemEntity();
        item.setId(840000000000000002L);
        item.setOrderId(840000000000000001L);
        item.setListingId(LISTING_ID);
        item.setTitle("演示闲置：九成新猫爬架");
        item.setPrice(8800L);
        item.setQuantity(1);
        item.setAmount(8800L);
        return item;
    }

    // ------------------------------------------------------------------
    // placeListingOrderTx：建单事实
    // ------------------------------------------------------------------

    @Test
    void 建单_幂等键已有C2C单_直接重放首单不重复建() {
        when(orderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(order("C2C", "PENDING_PAYMENT")));

        ListingOrderCreated created = service.placeListingOrderTx(placement());

        assertThat(created.orderNo()).isEqualTo(ORDER_NO);
        assertThat(created.payAmount()).isEqualTo(8800L);
        verify(orderMapper, never()).insert(any(OrderEntity.class));
        verify(orderItemMapper, never()).insert(any(OrderItemEntity.class));
    }

    @Test
    void 建单_字段齐备_落C2C订单与单件快照明细() {
        when(orderMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        service.placeListingOrderTx(placement());

        ArgumentCaptor<OrderEntity> orderCaptor = ArgumentCaptor.forClass(OrderEntity.class);
        verify(orderMapper).insert(orderCaptor.capture());
        OrderEntity order = orderCaptor.getValue();
        assertThat(order.getOrderType()).isEqualTo("C2C");
        assertThat(order.getUserId()).isEqualTo(BUYER);
        assertThat(order.getSellerId()).isEqualTo(SELLER);
        assertThat(order.getStatus()).isEqualTo("PENDING_PAYMENT");
        // 一口价：总额 = 应付 = 快照价（分），无运费/优惠/币
        assertThat(order.getTotalAmount()).isEqualTo(8800L);
        assertThat(order.getPayAmount()).isEqualTo(8800L);
        assertThat(order.getFreightAmount()).isZero();
        assertThat(order.getDiscountAmount()).isZero();
        assertThat(order.getReceiverName()).isEqualTo("收货人");
        assertThat(order.getIdempotentKey()).isEqualTo("idem-b11-1");
        assertThat(order.getExpireAt()).isNotNull();

        ArgumentCaptor<OrderItemEntity> itemCaptor = ArgumentCaptor.forClass(OrderItemEntity.class);
        verify(orderItemMapper).insert(itemCaptor.capture());
        OrderItemEntity item = itemCaptor.getValue();
        assertThat(item.getListingId()).isEqualTo(LISTING_ID);
        assertThat(item.getSpuId()).isNull();
        assertThat(item.getSkuId()).isNull();
        assertThat(item.getTitle()).isEqualTo("演示闲置：九成新猫爬架");
        assertThat(item.getQuantity()).isEqualTo(1);
        assertThat(item.getAmount()).isEqualTo(8800L);
    }

    @Test
    void 建单_载荷缺价格事实_400参数不合法() {
        ListingOrderPlacement bad = new ListingOrderPlacement(BUYER, LISTING_ID, SELLER,
                "标题", null, "收货人", "13800001234", "地址", "idem");

        assertThatThrownBy(() -> service.placeListingOrderTx(bad))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
        verify(orderMapper, never()).insert(any(OrderEntity.class));
    }

    // ------------------------------------------------------------------
    // shipListingOrder：发货准入
    // ------------------------------------------------------------------

    @Test
    void 发货_幽灵订单_404() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> service.shipListingOrder(ORDER_NO, SELLER, "顺丰", "SF-1"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void 发货_商城订单冒充闲置单_拒绝且不动状态() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order("NORMAL", "PAID"));

        assertThatThrownBy(() -> service.shipListingOrder(ORDER_NO, SELLER, "顺丰", "SF-1"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.ORDER_STATUS_ILLEGAL);
        verify(orderMapper, never()).updateById(any(OrderEntity.class));
    }

    @Test
    void 发货_非本人_403绝不动状态() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order("C2C", "PAID"));

        assertThatThrownBy(() -> service.shipListingOrder(ORDER_NO, BUYER, "顺丰", "SF-1"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(orderMapper, never()).updateById(any(OrderEntity.class));
    }

    @Test
    void 发货_本人已支付的闲置单_状态机推进SHIPPED并落物流() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order("C2C", "PAID"));
        when(settlementService.findByOrderId(anyLong())).thenReturn(null); // 走「缺托管记录」告警分支，不影响发货

        service.shipListingOrder(ORDER_NO, SELLER, "顺丰速运", "SF-123");

        ArgumentCaptor<OrderEntity> captor = ArgumentCaptor.forClass(OrderEntity.class);
        verify(orderMapper).updateById(captor.capture());
        OrderEntity shipped = captor.getValue();
        assertThat(shipped.getStatus()).isEqualTo("SHIPPED");
        assertThat(shipped.getLogisticsCompany()).isEqualTo("顺丰速运");
        assertThat(shipped.getLogisticsNo()).isEqualTo("SF-123");
        assertThat(shipped.getShippedAt()).isNotNull();
    }

    // ------------------------------------------------------------------
    // listListingSold：归属与类型过滤
    // ------------------------------------------------------------------

    @Test
    void 卖出列表_查询条件拼上C2C与卖家身份_明细映射进视图() {
        Page<OrderEntity> page = new Page<>(1, 10);
        page.setRecords(List.of(order("C2C", "PAID")));
        page.setTotal(1);
        when(orderMapper.selectPage(any(), any(Wrapper.class))).thenReturn(page);
        when(orderItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(listingItem()));

        var result = service.listListingSold(SELLER, null, 1, 10);

        // 归属判据看下发给数据库的条件，而不是看返回结果（返回对 == 条件丢也不报错的镜像问题）
        ArgumentCaptor<Wrapper<OrderEntity>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(orderMapper).selectPage(any(), wrapperCaptor.capture());
        String sql = wrapperCaptor.getValue().getSqlSegment();
        assertThat(sql).contains("seller_id", "order_type");

        assertThat(result.total()).isEqualTo(1L);
        ListingSoldOrderView view = result.items().get(0);
        assertThat(view.orderNo()).isEqualTo(ORDER_NO);
        assertThat(view.status()).isEqualTo("PAID");
        assertThat(view.listingId()).isEqualTo(LISTING_ID);
        assertThat(view.listingTitle()).isEqualTo("演示闲置：九成新猫爬架");
        assertThat(view.payAmount()).isEqualTo(8800L);
    }

    // ------------------------------------------------------------------
    // onListingRelease：释放回调的边界
    // ------------------------------------------------------------------

    @Test
    void 释放_商城单_绝不触碰商品域() {
        when(orderMapper.selectById(anyLong())).thenReturn(order("NORMAL", "CLOSED"));

        service.onListingRelease(new OrderService.ListingReleaseEvent(840000000000000001L, "用户取消"));

        verify(listingOrderClient, never()).releaseToOnSale(anyLong());
    }

    @Test
    void 释放_C2C单_凭明细里的listingId回调商品域() {
        when(orderMapper.selectById(anyLong())).thenReturn(order("C2C", "CLOSED"));
        when(orderItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(listingItem()));

        service.onListingRelease(new OrderService.ListingReleaseEvent(840000000000000001L, "超时未支付自动关单"));

        verify(listingOrderClient).releaseToOnSale(LISTING_ID);
    }

    @Test
    void 释放_商品域不可达_只记日志不抛() {
        when(orderMapper.selectById(anyLong())).thenReturn(order("C2C", "CLOSED"));
        when(orderItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(listingItem()));
        when(listingOrderClient.releaseToOnSale(anyLong())).thenThrow(new RuntimeException("connect timeout"));

        // 释放是 best-effort：关单/退款已是既成事实，回调失败绝不能反向打崩监听器
        org.assertj.core.api.Assertions.assertThatCode(() ->
                        service.onListingRelease(new OrderService.ListingReleaseEvent(840000000000000001L, "退款")))
                .doesNotThrowAnyException();
    }
}
