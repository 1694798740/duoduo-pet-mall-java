package com.duoduo.user.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duoduo.common.security.ip.MerchantIpWhitelist;
import com.duoduo.user.entity.MerchantEntity;
import com.duoduo.user.mapper.MerchantMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * E-06 · 启动时重发商家 IP 白名单副本。
 *
 * <h2>为什么需要它</h2>
 * Redis 是副本：被清空（重启未持久化 / 运维 flush）、或换了 Redis 实例之后，
 * 所有已启用白名单的店铺会**静默退回不限制**——页面还写着「已启用」，
 * 网关却一条都不拦。这种「配置看着在、实际不生效」的失效最难被发现，
 * 所以服务每次启动都按库里的权威值重发一遍，把窗口压缩到一次重启。
 *
 * <p>只发「已启用」的店，并**删除**未启用的键：处理历史脏数据（关掉开关时 Redis 里残留着旧键）。
 */
@Component
public class MerchantIpWhitelistBootstrap {

    private static final Logger log = LoggerFactory.getLogger(MerchantIpWhitelistBootstrap.class);

    private final MerchantMapper merchantMapper;
    private final MerchantIpWhitelistPublisher publisher;

    public MerchantIpWhitelistBootstrap(MerchantMapper merchantMapper,
                                        MerchantIpWhitelistPublisher publisher) {
        this.merchantMapper = merchantMapper;
        this.publisher = publisher;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void republishOnStartup() {
        List<MerchantEntity> stores;
        try {
            stores = merchantMapper.selectList(new LambdaQueryWrapper<MerchantEntity>()
                    .select(MerchantEntity::getId, MerchantEntity::getIpWhitelistEnabled,
                            MerchantEntity::getIpWhitelist));
        } catch (RuntimeException ex) {
            // 启动期读库失败不该让服务起不来（白名单是收紧项，缺失方向是「不限制」）
            log.warn("商家 IP 白名单启动重发跳过：读取商家表失败 {}", ex.getMessage());
            return;
        }
        int enabled = 0;
        int failed = 0;
        for (MerchantEntity store : stores) {
            if (store == null || store.getId() == null) {
                continue;
            }
            boolean on = store.getIpWhitelistEnabled() != null && store.getIpWhitelistEnabled() == 1;
            if (!on) {
                publisher.publish(store.getId(), false, List.of());
                continue;
            }
            enabled++;
            if (!publisher.publish(store.getId(), true, MerchantIpWhitelist.parse(store.getIpWhitelist()))) {
                failed++;
            }
        }
        log.info("商家 IP 白名单启动重发：已启用 {} 家，写入失败 {} 家", enabled, failed);
    }
}
