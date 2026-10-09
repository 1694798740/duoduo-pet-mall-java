package com.duoduo.user.support;

import com.duoduo.common.security.perm.PermVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * E17 · 把权限版本的**感知副本**写进 Redis，供网关比对。
 *
 * <h2>为什么是"副本"，权威值在库里</h2>
 * 权威值 {@code t_user.perm_version} 在用户库：可审计、重启不丢、与变更同事务。
 * 但校验发生在<b>网关上</b>，网关不连业务库 —— 每次请求去查库既慢又把网关绑死在用户库上。
 * 于是变更与签发时把当前值复制一份到 Redis，网关读它。
 *
 * <p>副本与权威值不同步时会发生什么，必须说清楚（这是本机制唯一的新失败面）：
 * <ul>
 *   <li>副本<b>落后</b>（写失败 / 被人删了）→ 网关读到旧值，与令牌里的新值不一致时
 *       ——注意此时令牌反而是新的、副本是旧的——请求会被判"过期"。这正是下面
 *       {@link #publish(Long, Long)} 的返回值要被 {@code AdminUserService} 当真的原因：
 *       写失败时它会退回「吊销刷新令牌」这条老路，而不是假装成功。</li>
 *   <li>副本<b>缺失</b>（键不存在 / Redis 整个不可用）→ 网关按"未知"放行（见 PermVersion 的取舍段）。</li>
 *   <li>副本<b>自愈</b>：每次签发令牌（登录 / 刷新 / 重签）都会按库里的最新值覆盖写一次，
 *       所以"落后"最多持续到对方下一次刷新，不会永久卡住。</li>
 * </ul>
 *
 * <h2>Redis 不可用时为什么要冷却</h2>
 * 与 AI 域的 {@code IpRateLimiter} 同一考虑：连不上时每次调用都要等连接超时，
 * 那会直接叠加到<b>登录</b>延迟上 —— 用户感到的是"登录变慢了"，而这只是为写一个数字。
 * 冷却期内直接跳过（返回失败），60 秒后再探一次。
 */
@Component
public class PermVersionPublisher {

    /** Redis 不可用后的冷却期：冷却内直接判失败，不再每次尝试 */
    private static final long REDIS_COOLDOWN_MILLIS = 60_000L;

    private static final Logger log = LoggerFactory.getLogger(PermVersionPublisher.class);

    private final StringRedisTemplate redis;
    private volatile long redisBrokenUntil = 0L;

    public PermVersionPublisher(ObjectProvider<StringRedisTemplate> redisProvider) {
        // getIfAvailable：容器里没有 Redis（本地零依赖跑单测 / 单实例部署）时为 null，退化为"写不进去"
        this.redis = redisProvider == null ? null : redisProvider.getIfAvailable();
    }

    /**
     * 写入（或覆盖）某用户的权限版本。
     *
     * <p><b>返回 false 表示"现在无法让网关感知到新版本"</b>，调用方必须据此走降级路径 ——
     * 不能吞掉：吞掉的表现是"运营界面上显示权限已收回，实际上旧令牌还能用"，
     * 而这正是 E17 要解决的问题本身。
     *
     * @return true = 副本已更新（网关下一个请求即能比对）；false = Redis 不可用或写入失败
     */
    public boolean publish(Long userId, Long version) {
        String key = PermVersion.key(userId);
        if (key == null) {
            return false;
        }
        if (redis == null) {
            log.debug("未配置 Redis，权限版本不做广播 userId={}", userId);
            return false;
        }
        if (System.currentTimeMillis() < redisBrokenUntil) {
            return false;
        }
        try {
            redis.opsForValue().set(key, PermVersion.format(PermVersion.orInitial(version)), PermVersion.TTL);
            return true;
        } catch (RuntimeException ex) {
            redisBrokenUntil = System.currentTimeMillis() + REDIS_COOLDOWN_MILLIS;
            log.warn("权限版本写入 Redis 失败，{} 秒内不再尝试 userId={}: {}",
                    REDIS_COOLDOWN_MILLIS / 1000, userId, ex.getMessage());
            return false;
        }
    }

    /**
     * 签发令牌时的**自愈**写入：失败只记日志，不影响签发。
     *
     * <p>为什么签发路径可以用"尽力而为"：令牌里本来就带着刚读出来的版本值，
     * 这次写入只是为了把副本修回来。写不进去不影响"令牌与库一致"这个事实。
     * 与变更路径（必须知道成功与否）区别就在这里。
     */
    public void publishQuietly(Long userId, Long version) {
        if (!publish(userId, version)) {
            log.debug("权限版本副本未能刷新（不影响本次签发）userId={} version={}",
                    userId, PermVersion.orInitial(version));
        }
    }

    /** 当前模式（排障用：一眼看出是"有 Redis"还是"没有 Redis"） */
    public String mode() {
        if (redis == null) {
            return "disabled";
        }
        return System.currentTimeMillis() < redisBrokenUntil ? "cooldown" : "redis";
    }

    /** 供排障使用：把副本时效直接暴露出来，避免"到底写没写"靠猜 */
    public Duration ttl() {
        return PermVersion.TTL;
    }
}
