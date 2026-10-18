package com.duoduo.trade.payment.wechat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 微信支付配置（2026-10-18）。
 *
 * <h2>为什么全部走环境变量</h2>
 * 商户私钥、APIv3 密钥属于**支付凭据**：写进仓库或配置文件，一旦泄漏就是"可以替你收钱、替你退款"的级别。
 * 因此这里只定义占位符，真实值一律由环境变量 / 编排层注入；
 * 仓库里留下的只有默认值（空串），代码在启动时会**校验必填项并失败退出**（fail closed）。
 *
 * <h2>生产 / 沙箱如何切换</h2>
 * 沙箱（{@code SANDBOX}）与 MOCK 的实现**一行未删**，注册器按渠道代码取实现：
 *   · {@code duoduo.pay.wechat.enabled=false} → 微信渠道 bean 不注册，请求它会得到 422 并列出可用渠道；
 *   · {@code duoduo.pay.default-channel} → 前端不传渠道时的默认渠道（生产填 WECHAT_NATIVE，联调填 SANDBOX）。
 */
@Component
public class WechatPayConfig {

    private final boolean enabled;
    private final String appid;
    private final String mchid;
    private final String apiV3Key;
    private final String privateKeyPem;
    private final String merchantSerialNo;
    private final String platformCertPem;
    private final String notifyUrl;
    private final String h5SceneWapUrl;
    private final String h5SceneWapName;
    private final int expireMinutes;

    public WechatPayConfig(
            @Value("${duoduo.pay.wechat.enabled:false}") boolean enabled,
            @Value("${duoduo.pay.wechat.appid:}") String appid,
            @Value("${duoduo.pay.wechat.mchid:}") String mchid,
            @Value("${duoduo.pay.wechat.api-v3-key:}") String apiV3Key,
            @Value("${duoduo.pay.wechat.private-key-pem:}") String privateKeyPem,
            @Value("${duoduo.pay.wechat.merchant-serial-no:}") String merchantSerialNo,
            @Value("${duoduo.pay.wechat.platform-cert-pem:}") String platformCertPem,
            @Value("${duoduo.pay.wechat.notify-url:}") String notifyUrl,
            @Value("${duoduo.pay.wechat.h5-wap-url:}") String h5SceneWapUrl,
            @Value("${duoduo.pay.wechat.h5-wap-name:多多宠物商店}") String h5SceneWapName,
            @Value("${duoduo.pay.wechat.expire-minutes:15}") int expireMinutes) {
        this.enabled = enabled;
        this.appid = appid == null ? "" : appid.trim();
        this.mchid = mchid == null ? "" : mchid.trim();
        this.apiV3Key = apiV3Key == null ? "" : apiV3Key.trim();
        this.privateKeyPem = privateKeyPem == null ? "" : privateKeyPem.trim();
        this.merchantSerialNo = merchantSerialNo == null ? "" : merchantSerialNo.trim();
        this.platformCertPem = platformCertPem == null ? "" : platformCertPem.trim();
        this.notifyUrl = notifyUrl == null ? "" : notifyUrl.trim();
        this.h5SceneWapUrl = h5SceneWapUrl == null ? "" : h5SceneWapUrl.trim();
        this.h5SceneWapName = h5SceneWapName == null ? "多多宠物商店" : h5SceneWapName.trim();
        this.expireMinutes = Math.max(1, expireMinutes);
    }

    public boolean enabled() {
        return enabled;
    }

    public String appid() {
        return appid;
    }

    public String mchid() {
        return mchid;
    }

    public String apiV3Key() {
        return apiV3Key;
    }

    public String privateKeyPem() {
        return privateKeyPem;
    }

    public String merchantSerialNo() {
        return merchantSerialNo;
    }

    public String platformCertPem() {
        return platformCertPem;
    }

    public String notifyUrl() {
        return notifyUrl;
    }

    public String h5SceneWapUrl() {
        return h5SceneWapUrl;
    }

    public String h5SceneWapName() {
        return h5SceneWapName;
    }

    public int expireMinutes() {
        return expireMinutes;
    }

    /**
     * 启动时自检：启用了微信支付就必须配齐凭据，缺一项直接抛错。
     *
     * <p>为什么不"缺了就降级成沙箱"：那会让"以为在真实收款"变成"其实走的是模拟渠道"——
     * 与 {@code PaymentChannelRegistry} 拒绝未知渠道是同一个理由，宁可启动失败，也不能悄悄收错钱。
     */
    public void assertComplete() {
        if (!enabled) {
            return;
        }
        StringBuilder missing = new StringBuilder();
        if (appid.isEmpty()) missing.append(" appid");
        if (mchid.isEmpty()) missing.append(" mchid");
        if (apiV3Key.isEmpty()) missing.append(" api-v3-key");
        if (privateKeyPem.isEmpty()) missing.append(" private-key-pem");
        if (merchantSerialNo.isEmpty()) missing.append(" merchant-serial-no");
        if (platformCertPem.isEmpty()) missing.append(" platform-cert-pem");
        if (notifyUrl.isEmpty()) missing.append(" notify-url");
        if (missing.length() > 0) {
            throw new IllegalStateException("微信支付已启用但缺少必填配置：" + missing
                    + "（请通过环境变量注入，勿写入仓库）");
        }
        if (!notifyUrl.startsWith("https://")) {
            throw new IllegalStateException("微信支付回调地址必须是 https（当前：" + notifyUrl + "）");
        }
    }
}
