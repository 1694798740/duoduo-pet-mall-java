package com.duoduo.user.support;

import com.duoduo.common.security.ip.MerchantIpWhitelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * E-06 · 把商家的 IP 白名单写进 Redis（网关读的副本）。
 *
 * <h2>为什么是副本</h2>
 * 权威值在 {@code t_merchant}：可审计、重启不丢、与变更同事务。
 * 但判定发生在网关，网关不连业务库 —— 每个商家端请求查一次库既慢又把网关绑死在用户库上。
 * 与 E17 权限版本同一套做法。
 *
 * <h2>写失败为什么不能吞掉</h2>
 * {@link #publish} 返回 false 表示「网关现在还读不到新配置」。与权限版本的区别在于：
 * 权限版本的旧值最多多放过一会儿；白名单的旧值可能是**整块缺失**——
 * 店主以为开了白名单，实际什么都没拦。因此服务层必须把这个结果回给调用方，
 * 页面要如实显示「已保存，但网关暂未同步」。
 *
 * <p>Redis 不可用时冷却 60 秒，避免每次保存都等一次连接超时。
 */
@Component
public class MerchantIpWhitelistPublisher {

    private static final long REDIS_COOLDOWN_MILLIS = 60_000L;

    private static final Logger log = LoggerFactory.getLogger(MerchantIpWhitelistPublisher.class);

    private final StringRedisTemplate redis;
    private volatile long redisBrokenUntil = 0L;

    public MerchantIpWhitelistPublisher(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redis = redisProvider == null ? null : redisProvider.getIfAvailable();
    }

    /**
     * 写入 / 删除某店铺的白名单副本。
     *
     * @return true = 网关下一个请求即可读到；false = Redis 不可用或写入失败
     */
    public boolean publish(Long merchantId, boolean enabled, List<String> rules) {
        String key = MerchantIpWhitelist.key(merchantId);
        if (key == null) {
            return false;
        }
        if (redis == null) {
            log.debug("未配置 Redis，商家 IP 白名单不做广播 merchantId={}", merchantId);
            return false;
        }
        if (System.currentTimeMillis() < redisBrokenUntil) {
            return false;
        }
        try {
            if (enabled) {
                // 值可以是空串（启用但无规则）—— 网关按「失败关闭」处理，见 MerchantIpWhitelist
                redis.opsForValue().set(key, MerchantIpWhitelist.format(rules));
            } else {
                redis.delete(key);
            }
            return true;
        } catch (RuntimeException ex) {
            redisBrokenUntil = System.currentTimeMillis() + REDIS_COOLDOWN_MILLIS;
            log.warn("商家 IP 白名单写入 Redis 失败，{} 秒内不再尝试 merchantId={}: {}",
                    REDIS_COOLDOWN_MILLIS / 1000, merchantId, ex.getMessage());
            return false;
        }
    }
}
