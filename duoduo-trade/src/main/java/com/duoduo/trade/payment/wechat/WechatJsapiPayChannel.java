package com.duoduo.trade.payment.wechat;

import com.duoduo.trade.payment.PaymentChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 微信 JSAPI 支付（微信小程序 / 公众号内）。
 *
 * <p>预下单拿到 {@code prepay_id}，小程序端调 {@code wx.requestPayment} 直接起收银台，
 * <b>不需要二维码</b> —— 因此在小程序里应当走这个渠道，而不是 Native。
 *
 * <p>前置条件：用户必须已完成微信登录，{@code prepay} 时会校验 openid 是否存在。
 */
@Component
@ConditionalOnProperty(prefix = "duoduo.pay.wechat", name = "enabled", havingValue = "true")
public class WechatJsapiPayChannel extends WechatPayChannelBase implements PaymentChannel {

    public static final String CODE = "WECHAT_JSAPI";

    public WechatJsapiPayChannel(WechatPayConfig config, ObjectMapper objectMapper) {
        super(config, objectMapper);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    protected PayType payType() {
        return PayType.JSAPI;
    }
}
