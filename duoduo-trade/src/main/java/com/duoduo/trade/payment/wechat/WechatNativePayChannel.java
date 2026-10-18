package com.duoduo.trade.payment.wechat;

import com.duoduo.trade.payment.PaymentChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 微信 Native 支付（网页扫码）。
 *
 * <p>落点：前端把 {@code codeUrl} 渲染成二维码，用户用微信「扫一扫」付款。
 * 注意 {@code code_url} 形如 {@code wechatpay://pay.weixin.qq.com/...}，只有<b>微信</b>能识别，
 * 用系统相机或支付宝扫是扫不出来的 —— 这是产品形态决定的，不是缺陷。
 *
 * <p>仅当 {@code duoduo.pay.wechat.enabled=true} 时注册；不启用时请求本渠道会得到 422
 * 并列出当前可用渠道（沙箱 / MOCK 仍在），不会静默回退。
 */
@Component
@ConditionalOnProperty(prefix = "duoduo.pay.wechat", name = "enabled", havingValue = "true")
public class WechatNativePayChannel extends WechatPayChannelBase implements PaymentChannel {

    public static final String CODE = "WECHAT_NATIVE";

    public WechatNativePayChannel(WechatPayConfig config, ObjectMapper objectMapper) {
        super(config, objectMapper);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    protected PayType payType() {
        return PayType.NATIVE;
    }
}
