package com.duoduo.ai.agent;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 游客鉴权与配额（B4）。
 *
 * <p>这组断言保护的是「游客最多 3 轮」这条**产品承诺**：
 * 计数器读错、Cookie 不签发、Redis 故障时把游客锁死，都会直接变成用户投诉。
 */
class GuestIdentityServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private GuestIdentityService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        service = new GuestIdentityService(redis, false);
    }

    @Test
    @DisplayName("首次访问：无 Cookie 时签发 HttpOnly + SameSite=Lax 的设备标识")
    void issuesCookieWhenAbsent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        GuestIdentityService.GuestIdentity identity = service.resolve(request, response);

        assertThat(identity.fresh()).isTrue();
        assertThat(identity.deviceId()).matches(
                "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
        String cookie = response.getHeader("Set-Cookie");
        assertThat(cookie).contains("duoduo_guest=" + identity.deviceId());
        assertThat(cookie).contains("HttpOnly");
        assertThat(cookie).contains("SameSite=Lax");
        assertThat(cookie).contains("Max-Age=86400");
    }

    @Test
    @DisplayName("已有合法 Cookie：复用原设备标识，且不重复下发")
    void reusesValidCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        service.resolve(request, response);
        String deviceId = response.getHeader("Set-Cookie").split(";")[0].split("=")[1];

        MockHttpServletRequest second = new MockHttpServletRequest();
        second.setCookies(new Cookie("duoduo_guest", deviceId));
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();

        GuestIdentityService.GuestIdentity identity = service.resolve(second, secondResponse);

        assertThat(identity.fresh()).isFalse();
        assertThat(identity.deviceId()).isEqualTo(deviceId);
        assertThat(secondResponse.getHeader("Set-Cookie")).isNull();
    }

    @Test
    @DisplayName("非法 deviceId：拒绝复用并重新签发（防 Redis Key 注入）")
    void rejectsForgedDeviceId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("duoduo_guest", "abc:def"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        GuestIdentityService.GuestIdentity identity = service.resolve(request, response);

        assertThat(identity.fresh()).isTrue();
        assertThat(identity.deviceId()).isNotEqualTo("abc:def");
    }

    @Test
    @DisplayName("配额：没记录 = 3 轮；已用 3 轮 = 0 轮")
    void computesRemaining() {
        when(values.get(anyString())).thenReturn(null);
        assertThat(service.remaining("2f6b0f4e-1c1e-4c37-9f0e-0d4b1c2a3e4f"))
                .isEqualTo(GuestIdentityService.MAX_GUEST_TURNS);

        when(values.get(anyString())).thenReturn("3");
        assertThat(service.remaining("2f6b0f4e-1c1e-4c37-9f0e-0d4b1c2a3e4f")).isZero();
    }

    @Test
    @DisplayName("消费一轮：首次写入补 24h 过期时间")
    void consumeSetsExpiryOnFirstWrite() {
        when(values.increment(anyString())).thenReturn(1L);
        when(redis.getExpire(anyString())).thenReturn(-1L);

        service.consume("2f6b0f4e-1c1e-4c37-9f0e-0d4b1c2a3e4f");

        verify(values).increment("ai:guest:turns:2f6b0f4e-1c1e-4c37-9f0e-0d4b1c2a3e4f");
        verify(redis).expire(anyString(), org.mockito.ArgumentMatchers.any(java.time.Duration.class));
    }

    @Test
    @DisplayName("Redis 故障：配额 fail-open（不把游客锁死、不抛异常）")
    void failsOpenWhenRedisDown() {
        when(values.get(anyString())).thenThrow(new IllegalStateException("redis down"));
        when(values.increment(anyString())).thenThrow(new IllegalStateException("redis down"));

        assertThat(service.remaining("2f6b0f4e-1c1e-4c37-9f0e-0d4b1c2a3e4f"))
                .isEqualTo(GuestIdentityService.MAX_GUEST_TURNS);
        service.consume("2f6b0f4e-1c1e-4c37-9f0e-0d4b1c2a3e4f");
        // 不抛异常即通过；Chromium 之外的环境也可能没有 Micrometer，这里只断言不炸
    }

    @Test
    @DisplayName("非法 deviceId：既不读也不写 Redis")
    void neverTouchesRedisForInvalidId() {
        assertThat(service.remaining("bad id")).isEqualTo(GuestIdentityService.MAX_GUEST_TURNS);
        service.consume("bad id");
        verify(values, never()).get(anyString());
        verify(values, never()).increment(anyString());
    }
}
