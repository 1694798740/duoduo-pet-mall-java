package com.duoduo.user.dto;

import java.util.List;

/**
 * 商家端「账号安全 · IP 白名单」的请求与视图（E-06）。
 */
public final class MerchantSecurityDtos {

    private MerchantSecurityDtos() {
    }

    /**
     * 白名单视图。
     *
     * @param enabled   是否启用
     * @param rules     规则列表（原样返回，前端直接回填输入框）
     * @param currentIp 服务端看到的**本次请求来源 IP**（网关 {@code X-Real-IP} 透传）
     *                  —— 展示它是为了让店主知道「我现在该把哪个地址加进去」，
     *                  而不是凭猜测填一个然后把自己锁在门外
     * @param published 本次保存后网关是否已能读到（false = Redis 不可用，稍后才生效）
     */
    public record IpWhitelistView(
            boolean enabled,
            List<String> rules,
            String currentIp,
            boolean published
    ) {
    }

    /**
     * 保存请求。
     *
     * @param enabled        必填：是否启用
     * @param rules          规则列表；启用时至少一条
     * @param confirmLockout 当前 IP 不在新名单里时，必须显式传 true 才允许保存
     *                       （防「配完就把自己锁在门外」——这是本功能最容易造成的伤害）
     */
    public record IpWhitelistRequest(
            Boolean enabled,
            List<String> rules,
            Boolean confirmLockout
    ) {
    }
}
