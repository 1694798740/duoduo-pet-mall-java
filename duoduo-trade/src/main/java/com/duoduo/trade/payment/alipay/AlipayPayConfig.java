package com.duoduo.trade.payment.alipay;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 支付宝支付配置（2026-10-22，Web 端「电脑网站支付」）。
 *
 * <h2>为什么全部走环境变量</h2>
 * 与 {@code WechatPayConfig} 同一条铁律：应用私钥是「能替你收钱」级别的凭据，
 * 仓库里只有占位符（空串默认值），真实值由环境变量注入；
 * 启用时启动自检必填项，缺一项直接启动失败（fail closed），绝不降级成沙箱蒙混。
 *
 * <h2>沙箱 / 生产切换</h2>
 * 只换两个配置：{@code gateway}（网关地址）与密钥对。
 * <ul>
 *   <li>沙箱：{@code https://openapi-sandbox.dl.alipaydev.com/gateway.do}，
 *       在支付宝开放平台「沙箱环境」页生成沙箱应用密钥，用沙箱买家账号付款；</li>
 *   <li>生产：{@code https://openapi.alipay.com/gateway.do}，
 *       需签约「电脑网站支付」产品（见产品文档对资质的要求）。</li>
 * </ul>
 * 代码完全一致 —— 签名、报文、回调验签在两个环境没有任何差别。
 */
@Component
public class AlipayPayConfig {

    private final boolean enabled;
    private final String appId;
    private final String privateKeyPem;
    private final String alipayPublicKeyPem;
    private final String gateway;
    private final String notifyUrl;
    private final String returnUrl;
    private final int expireMinutes;

    public AlipayPayConfig(
            @Value("${duoduo.pay.alipay.enabled:false}") boolean enabled,
            @Value("${duoduo.pay.alipay.app-id:}") String appId,
            @Value("${duoduo.pay.alipay.private-key-pem:}") String privateKeyPem,
            @Value("${duoduo.pay.alipay.alipay-public-key-pem:}") String alipayPublicKeyPem,
            @Value("${duoduo.pay.alipay.gateway:https://openapi-sandbox.dl.alipaydev.com/gateway.do}") String gateway,
            @Value("${duoduo.pay.alipay.notify-url:}") String notifyUrl,
            @Value("${duoduo.pay.alipay.return-url:}") String returnUrl,
            @Value("${duoduo.pay.alipay.expire-minutes:15}") int expireMinutes) {
        this.enabled = enabled;
        this.appId = appId == null ? "" : appId.trim();
        this.privateKeyPem = privateKeyPem == null ? "" : privateKeyPem.trim();
        this.alipayPublicKeyPem = alipayPublicKeyPem == null ? "" : alipayPublicKeyPem.trim();
        this.gateway = gateway == null || gateway.isBlank()
                ? "https://openapi-sandbox.dl.alipaydev.com/gateway.do" : gateway.trim();
        this.notifyUrl = notifyUrl == null ? "" : notifyUrl.trim();
        this.returnUrl = returnUrl == null ? "" : returnUrl.trim();
        this.expireMinutes = Math.max(1, expireMinutes);
    }

    public boolean enabled() {
        return enabled;
    }

    public String appId() {
        return appId;
    }

    public String privateKeyPem() {
        return privateKeyPem;
    }

    public String alipayPublicKeyPem() {
        return alipayPublicKeyPem;
    }

    public String gateway() {
        return gateway;
    }

    public String notifyUrl() {
        return notifyUrl;
    }

    public String returnUrl() {
        return returnUrl;
    }

    public int expireMinutes() {
        return expireMinutes;
    }

    /**
     * 启动自检：启用了支付宝渠道就必须配齐凭据，缺一项直接启动失败。
     *
     * <p>与微信渠道同一条理由：宁可启动失败，也不能"以为在真实收款、其实钱没收到"。
     * 回调地址放宽到 http：沙箱网关对回调地址不强制 https（微信强制），
     * 但生产环境建议仍然配置 https。
     */
    public void assertComplete() {
        if (!enabled) {
            return;
        }
        StringBuilder missing = new StringBuilder();
        if (appId.isEmpty()) missing.append(" app-id");
        if (privateKeyPem.isEmpty()) missing.append(" private-key-pem");
        if (alipayPublicKeyPem.isEmpty()) missing.append(" alipay-public-key-pem");
        if (notifyUrl.isEmpty()) missing.append(" notify-url");
        if (missing.length() > 0) {
            throw new IllegalStateException("支付宝支付已启用但缺少必填配置：" + missing
                    + "（请通过环境变量注入，勿写入仓库）");
        }
    }
}
