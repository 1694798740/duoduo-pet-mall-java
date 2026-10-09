package com.duoduo.user.support;

import com.duoduo.common.security.perm.PermVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E17 · 权限版本副本发布器的单测。
 *
 * <h2>为什么要单独测这个"写缓存"的小组件</h2>
 * 它的返回值不是装饰：{@code AdminUserService} 拿它决定"要不要退回吊销刷新令牌"。
 * 两类缺陷都只有在这里才看得见 ——
 * <ol>
 *   <li><b>写失败却返回 true</b>：运营看到"已即时生效"，实际网关照旧放行旧令牌。
 *       这是"看起来收回了权限、其实没收"的假象，比不引入本机制更危险。</li>
 *   <li><b>Redis 慢/挂时每次都去连</b>：把连接超时叠加到<b>登录</b>上，
 *       用户感到"登录变慢了"，而这只为写一个数字。</li>
 * </ol>
 * 因此这里既钉返回值语义，也钉"失败后进入冷却、不再反复尝试"。
 */
class PermVersionPublisherTest {

    private static final long USER_ID = 710000000000000001L;

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);

    private PermVersionPublisher publisherWithRedis() {
        when(provider.getIfAvailable()).thenReturn(redis);
        when(redis.opsForValue()).thenReturn(ops);
        return new PermVersionPublisher(provider);
    }

    @Test
    void 写入成功返回true_键与存活时间都按共享口径() {
        PermVersionPublisher publisher = publisherWithRedis();

        assertThat(publisher.publish(USER_ID, 5L)).isTrue();

        // 键名与 TTL 必须来自 PermVersion —— 网关是拿同一份口径去读的
        verify(ops).set(eq(PermVersion.key(USER_ID)), eq("5"), eq(PermVersion.TTL));
    }

    @Test
    void 版本为空时按初始版本写_而不是写出字符串null() {
        PermVersionPublisher publisher = publisherWithRedis();

        publisher.publish(USER_ID, null);

        // 写出 "null" 的后果：网关解析失败 → 判为"未知" → 放行（校验静默失效）
        verify(ops).set(eq(PermVersion.key(USER_ID)), eq("0"), any());
    }

    @Test
    void 没有Redis时返回false_不假装成功() {
        when(provider.getIfAvailable()).thenReturn(null);
        PermVersionPublisher publisher = new PermVersionPublisher(provider);

        assertThat(publisher.publish(USER_ID, 5L)).isFalse();
        assertThat(publisher.mode()).isEqualTo("disabled");
    }

    @Test
    void 写入异常返回false_并进入冷却不再反复尝试() {
        PermVersionPublisher publisher = publisherWithRedis();
        when(redis.opsForValue()).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("boom"));

        assertThat(publisher.publish(USER_ID, 5L)).isFalse();
        // 冷却期内直接判失败：不能每次都去等一次连接超时（那会变成"登录变慢"）
        assertThat(publisher.publish(USER_ID, 6L)).isFalse();

        verify(redis, times(1)).opsForValue();
        assertThat(publisher.mode()).isEqualTo("cooldown");
    }

    @Test
    void 空用户id直接失败_不拼出共用假键() {
        PermVersionPublisher publisher = publisherWithRedis();

        assertThat(publisher.publish(null, 5L)).isFalse();
        verify(redis, times(0)).opsForValue();
    }

    @Test
    void 尽力而为的发布不会把异常抛给签发路径() {
        PermVersionPublisher publisher = publisherWithRedis();
        when(redis.opsForValue()).thenThrow(new RuntimeException("boom"));

        // 签发令牌时只是"顺手把副本修回来"，写不进去不影响"令牌与库一致"这个事实
        assertThatCode(() -> publisher.publishQuietly(USER_ID, 5L)).doesNotThrowAnyException();
    }
}
