package com.duoduo.trade.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.exception.BizException;
import com.duoduo.trade.entity.OrderEntity;
import com.duoduo.trade.mapper.OrderMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单列表筛选判据（D4：秒杀抢购记录）。
 *
 * <h2>这一层为什么要单测</h2>
 * 「按类型筛订单」有三种失效方式，而且**都不会报错**：
 * <ul>
 *   <li>条件没拼进 SQL —— 用户看到的是「我的抢购记录为空」，与「真的没抢到」一模一样；</li>
 *   <li>未知类型被静默接受 —— 前端传错一个词（如 {@code seckill} 小写），仍是空列表；</li>
 *   <li>用户维度条件丢失 —— 这一条最严重：会把别人的订单返回给你。</li>
 * </ul>
 * 所以断言直接看<b>下发给数据库的 SQL 片段</b>，而不是看返回结果（返回空列表时三者长得一样）。
 */
class OrderListFilterTest {

    private OrderMapper orderMapper;
    private OrderService service;

    @BeforeEach
    void setUp() {
        // LambdaQueryWrapper 的列名解析依赖 MyBatis-Plus 的实体元数据缓存；
        // 纯单测没有 Spring 上下文，必须手动初始化，否则 getSqlSegment() 抛
        // 「can not find lambda cache for this entity」——断言还没开始就已经跑不下去。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), OrderEntity.class);

        orderMapper = mock(OrderMapper.class);
        when(orderMapper.selectPage(any(), any())).thenReturn(new Page<OrderEntity>(1, 10));
        // listMine 只用到 orderMapper 与 UserContext，其余依赖传 null；
        // 若哪天 listMine 真的用到了别的依赖，这里会立刻 NPE —— 比悄悄放过要好。
        service = new OrderService(orderMapper,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
        UserContext.set(new LoginUser(9001L, "13800000000", "USER", true));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @SuppressWarnings("unchecked")
    private Wrapper<OrderEntity> capturedWrapper() {
        ArgumentCaptor<Wrapper<OrderEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(orderMapper).selectPage(any(), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("按 SECKILL 筛选时，SQL 里必须出现 order_type 条件")
    void 秒杀筛选必须下发orderType条件() {
        service.listMine(null, "SECKILL", 1, 10);

        String sql = capturedWrapper().getSqlSegment();
        assertThat(sql).contains("order_type");
        assertThat(((LambdaQueryWrapper<OrderEntity>) capturedWrapper()).getParamNameValuePairs())
                .containsValue("SECKILL");
    }

    @Test
    @DisplayName("不传类型时不得加 order_type 条件（否则会把普通订单藏起来）")
    void 不传类型时不加orderType条件() {
        service.listMine(null, null, 1, 10);

        assertThat(capturedWrapper().getSqlSegment()).doesNotContain("order_type");
    }

    @Test
    @DisplayName("未知订单类型必须报 400，而不是返回空列表")
    void 未知订单类型必须报错() {
        assertThatThrownBy(() -> service.listMine(null, "seckill", 1, 10))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("订单类型");
    }

    @Test
    @DisplayName("用户维度条件必须始终存在 —— 少了它就会把别人的订单返回给你")
    void 用户维度条件始终存在() {
        service.listMine(null, "SECKILL", 1, 10);

        String sql = capturedWrapper().getSqlSegment();
        assertThat(sql).contains("user_id");
        assertThat(((LambdaQueryWrapper<OrderEntity>) capturedWrapper()).getParamNameValuePairs())
                .containsValue(9001L);
    }
}
