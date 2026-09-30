package com.duoduo.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duoduo.common.context.LoginUser;
import com.duoduo.common.context.UserContext;
import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.common.id.SnowflakeIdGenerator;
import com.duoduo.product.dto.ProductDtos.ListingPublishRequest;
import com.duoduo.product.entity.ListingEntity;
import com.duoduo.product.mapper.ListingMapper;
import com.duoduo.product.support.BannedWordGuard;
import com.duoduo.product.support.search.EsSearchEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 闲置发布频率限流单测（第五十八轮 · P1-7）。
 *
 * <h2>为什么「总量上限」之外还要限频率</h2>
 * 只有「单账号 200 条」的总量上限时，一个账号可以在一分钟内把这 200 条全发完 ——
 * 审核队列是按条走的，峰值才是它被打垮的原因。这里两道闸门分别断言，
 * 将来谁拆掉其中一道，对应测试立刻转红。
 *
 * <h2>为什么断言 429 而不是「抛异常就行」</h2>
 * 429（{@link ErrorCode#RATE_LIMITED}）与 422（业务规则）的用户动作完全不同：
 * 前者是「等一会儿重试」，后者是「改内容」。用哪个码决定了前端给不给倒计时，
 * 所以断言必须精确到码。
 */
class ListingPublishRateLimitTest {

    private static final int MAX_PER_DAY = 3;
    private static final long MIN_INTERVAL_SECONDS = 60L;

    private final ListingMapper listingMapper = mock(ListingMapper.class);
    private final ProductService productService = mock(ProductService.class);
    private final BannedWordGuard bannedWordGuard = mock(BannedWordGuard.class);
    private final SnowflakeIdGenerator idGenerator = mock(SnowflakeIdGenerator.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<EsSearchEngine> esProvider = mock(ObjectProvider.class);

    private ListingService service() {
        return new ListingService(listingMapper, productService, bannedWordGuard, idGenerator,
                200, MAX_PER_DAY, MIN_INTERVAL_SECONDS, esProvider);
    }

    @BeforeEach
    void login() {
        UserContext.set(new LoginUser(710000000000000001L, "13900000001", "USER", true));
        when(idGenerator.nextId()).thenReturn(910000000000000001L);
    }

    @AfterEach
    void logout() {
        UserContext.clear();
    }

    private static ListingPublishRequest request() {
        return new ListingPublishRequest("九成新猫爬架", "用了三个月，搬家出", 1L,
                12000L, 20000L, "LIKE_NEW", "上海", List.of("https://img/a.jpg"), "CAT", "布偶");
    }

    private static ListingEntity lastPublishedAgo(long seconds) {
        ListingEntity entity = new ListingEntity();
        entity.setId(910000000000000000L);
        entity.setSellerId(710000000000000001L);
        entity.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(seconds));
        return entity;
    }

    @Test
    void 二十四小时内达到上限时拒绝发布() {
        // 第一次 count = 总量（未满），第二次 = 24h 内条数（已满）
        when(listingMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(5L, (long) MAX_PER_DAY);

        assertThatThrownBy(() -> service().publish(request()))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.RATE_LIMITED);

        verify(listingMapper, never()).insert(any());
    }

    @Test
    void 距上一条不足最小间隔时拒绝发布() {
        when(listingMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(5L, 0L);
        when(listingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(lastPublishedAgo(10));

        assertThatThrownBy(() -> service().publish(request()))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.RATE_LIMITED);

        verify(listingMapper, never()).insert(any());
    }

    @Test
    void 间隔已过且未到日上限时正常发布() {
        when(listingMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(5L, 1L);
        when(listingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(lastPublishedAgo(120));

        assertThatCode(() -> service().publish(request())).doesNotThrowAnyException();

        verify(listingMapper).insert(any(ListingEntity.class));
    }

    @Test
    void 首次发布不受间隔限制() {
        when(listingMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L, 0L);
        when(listingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThatCode(() -> service().publish(request())).doesNotThrowAnyException();

        verify(listingMapper).insert(any(ListingEntity.class));
    }

    @Test
    void 关闭间隔限制后只看日上限() {
        ListingService service = new ListingService(listingMapper, productService, bannedWordGuard,
                idGenerator, 200, MAX_PER_DAY, 0L, esProvider);
        when(listingMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(5L, 0L);
        when(listingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(lastPublishedAgo(1));

        assertThatCode(() -> service.publish(request())).doesNotThrowAnyException();
    }

    @Test
    void 限流命中时给的提示带上还要等多久() {
        when(listingMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(5L, 0L);
        when(listingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(lastPublishedAgo(10));

        assertThatThrownBy(() -> service().publish(request()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("秒后再试");
    }

    @Test
    void 总量上限仍是422_与频率限流的429区分开() {
        when(listingMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(200L);

        assertThatThrownBy(() -> service().publish(request()))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.BUSINESS_RULE);
    }
}
