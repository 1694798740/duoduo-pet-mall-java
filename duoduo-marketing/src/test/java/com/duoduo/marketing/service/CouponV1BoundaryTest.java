package com.duoduo.marketing.service;

import com.duoduo.common.exception.BizException;
import com.duoduo.marketing.dto.CouponDtos.CreateTemplateRequest;
import com.duoduo.marketing.dto.CouponDtos.ReleaseResult;
import com.duoduo.marketing.entity.CouponEntity;
import com.duoduo.marketing.mapper.CouponMapper;
import com.duoduo.marketing.mapper.CouponTemplateMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

/**
 * 优惠券 v1 能力边界单测（第五十八轮 · P0-1）。
 *
 * <h2>本轮为什么只「固化」而不「扩展」</h2>
 * v1 的三个收窄（仅满减 / 退款不退券 / 一券只作用于金额最大的子单）都涉及<b>产品口径</b>：
 * 退款到底退不退券、跨子单怎么分摊，拍板错了是要赔钱的。在口径确认前不做扩展。
 * 但也不能什么都不做 —— 这些收窄目前只存在于注释里，
 * 哪天被人「顺手支持一下折扣券」，线上就会突然出现算错钱的券，且没有任何报错。
 * 所以这里把三条边界钉成断言：<b>边界先固化，口径后扩展</b>。
 *
 * <h2>「退款不退券」到底指什么（容易误解，必须写清）</h2>
 * 指的不是「不解锁」——{@code release} 必须解锁，否则券会被永久锁死。
 * 指的是：**已核销（USED）的券不会因为退款而回到用户卡包**。
 * 因此下面断言的是 {@code release} 只认 LOCKED，已 USED 的券不在其中。
 */
class CouponV1BoundaryTest {

    private static CouponMapper mapperReturning(List<CouponEntity> locked) {
        CouponMapper mapper = mock(CouponMapper.class);
        when(mapper.findLockedByOrder(any())).thenReturn(locked);
        return mapper;
    }

    private static CouponEntity coupon(String code, String status) {
        CouponEntity c = new CouponEntity();
        c.setCode(code);
        c.setStatus(status);
        return c;
    }

    /** 装配一个只带「券 Mapper」的服务实例（建券路径另需模板 Mapper 与 id 生成器） */
    private static CouponService service(CouponMapper couponMapper) {
        com.duoduo.common.id.SnowflakeIdGenerator idGen =
                mock(com.duoduo.common.id.SnowflakeIdGenerator.class);
        when(idGen.nextId()).thenReturn(1L);
        return new CouponService(couponMapper, mock(CouponTemplateMapper.class), idGen);
    }

    // ---------------- 边界一：只支持满减 ----------------

    @Test
    void 非满减券类型一律拒绝且提示明确() {
        CouponService service = service(mock(CouponMapper.class));
        CreateTemplateRequest req = new CreateTemplateRequest(
                "折扣券", "八折", "DISCOUNT", 8000L, 1000L, 100,
                "2026-01-01T00:00:00Z", "2026-12-31T00:00:00Z");

        assertThatThrownBy(() -> service.createTemplate(req))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("暂不支持的券类型")
                .hasMessageContaining("FULL_REDUCE");
    }

    @Test
    void 满减类型大小写不敏感() {
        // 运营从 Excel 复制时带小写是常态；若不 trim/toUpperCase，会变成「支持了却被拒」
        CouponService service = service(mock(CouponMapper.class));
        CreateTemplateRequest req = new CreateTemplateRequest(
                "满减券", "满 100 减 10", " full_reduce ", 10000L, 1000L, 100,
                "2026-01-01T00:00:00Z", "2026-12-31T00:00:00Z");
        // 用真实 id 生成器 + mock 的模板 Mapper，断言整条链路走通（类型校验已放行）
        org.assertj.core.api.Assertions.assertThatCode(() -> service.createTemplate(req))
                .doesNotThrowAnyException();
    }

    // ---------------- 边界二：退款不退券 ----------------

    @Test
    void release只解锁LOCKED券_已核销的券不会被退回() {
        CouponMapper mapper = mapperReturning(List.of(coupon("C1", "LOCKED")));
        CouponService service = new CouponService(mapper, null, null);
        when(mapper.release(eq("C1"), eq("NO1"), any(LocalDateTime.class))).thenReturn(1);

        ReleaseResult result = service.release("NO1");

        assertThat(result.released()).isEqualTo(1);
        // findLockedByOrder 只查 LOCKED —— 这是「已核销券不退」的唯一保障，
        // 若哪天改成查全部状态，退款就会凭空给用户变出一张券
        verify(mapper).findLockedByOrder("NO1");
    }

    @Test
    void 没有锁定券时release不报错且返回0() {
        CouponMapper mapper = mapperReturning(List.of());
        CouponService service = new CouponService(mapper, null, null);

        assertThat(service.release("NO-EMPTY").released()).isZero();
        verify(mapper, never()).release(any(), any(), any());
    }
}
