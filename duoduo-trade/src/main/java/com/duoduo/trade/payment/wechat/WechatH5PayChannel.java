package com.duoduo.trade.payment.wechat;

import com.duoduo.trade.payment.PaymentChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 微信 H5 支付（手机上的非微信浏览器）。
 *
 * <p>预下单拿到 {@code h5_url}，前端直接跳转即可（微信会拉起支付中间页）。
 * 与 Native / JSAPI 共用同一条回调与入账链路，<b>错误处理与订单状态更新完全一致</b>。
 *
 * <p>两个硬约束（微信侧规定，不是我们的实现限制）：
 * <ol>
 *   <li>必须传 {@code payer_client_ip}（真实客户端 IP），缺失时这里<b>直接报错</b>而不是填假 IP ——
 *       填假 IP 只会被风控判为异常交易；</li>
 *   <li>{@code wap_url} / {@code wap_name} 需与商户平台配置的 H5 支付域名一致。</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(prefix = "duoduo.pay.wechat", name = "enabled", havingValue = "true")
public class WechatH5PayChannel extends WechatPayChannelBase implements PaymentChannel {

    public static final String CODE = "WECHAT_H5";

    public WechatH5PayChannel(WechatPayConfig config, ObjectMapper objectMapper) {
        super(config, objectMapper);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    protected PayType payType() {
        return PayType.H5;
    }
}
