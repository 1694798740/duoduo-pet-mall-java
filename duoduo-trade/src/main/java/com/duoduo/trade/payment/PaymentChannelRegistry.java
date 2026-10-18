package com.duoduo.trade.payment;

import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 支付渠道注册表：按渠道代码找到对应实现。
 *
 * <p>实现类由 Spring 收集（{@code List<PaymentChannel>} 注入），
 * 新增渠道只需加一个 {@code @Component} 实现类，**不改任何调用方代码**。
 *
 * <p>一个刻意的行为：<b>未知渠道直接拒绝，绝不回退到默认渠道</b>。
 * 「渠道没匹配上就用 MOCK 顶上」看着方便，实质是把「用户以为在付真钱」
 * 变成了「系统拿模拟渠道记了一笔成功」—— 线上出现这种事的后果比报错严重得多。
 */
@Component
public class PaymentChannelRegistry {

    private static final Logger log = LoggerFactory.getLogger(PaymentChannelRegistry.class);

    private final Map<String, PaymentChannel> channels;

    /** 默认渠道（{@code duoduo.pay.default-channel}）：前端不传渠道时使用；为空则不提供默认，仍按未知渠道拒绝 */
    private final String defaultChannel;

    public PaymentChannelRegistry(List<PaymentChannel> implementations,
                                  @org.springframework.beans.factory.annotation.Value(
                                          "${duoduo.pay.default-channel:}") String defaultChannel) {
        this.defaultChannel = defaultChannel == null ? "" : defaultChannel.trim().toUpperCase();
        Map<String, PaymentChannel> map = new LinkedHashMap<>();
        for (PaymentChannel channel : implementations) {
            String key = channel.code().trim().toUpperCase();
            PaymentChannel previous = map.put(key, channel);
            if (previous != null) {
                // 同代码两个实现属于配置错误，直接失败关闭，别让运行期随机选一个
                throw new IllegalStateException("支付渠道代码重复：" + key
                        + "（" + previous.getClass().getSimpleName() + " 与 " + channel.getClass().getSimpleName() + "）");
            }
        }
        this.channels = Collections.unmodifiableMap(map);
        log.info("已加载支付渠道：{}", describe());
    }

    /** 取渠道实现；未知渠道抛 422 并列出当前可用渠道（便于前端与排查） */
    public PaymentChannel require(String code) {
        String key = code == null ? "" : code.trim().toUpperCase();
        if (key.isEmpty() && !defaultChannel.isEmpty()) {
            // 前端没指定渠道 → 用配置里的默认（生产 WECHAT_NATIVE / 联调 SANDBOX）
            key = defaultChannel;
        }
        PaymentChannel channel = channels.get(key);
        if (channel == null) {
            throw new BizException(ErrorCode.PAYMENT_CHANNEL_UNKNOWN,
                    "不支持的支付渠道：" + code + "；当前可用：" + String.join(" / ", channels.keySet()));
        }
        return channel;
    }

    public Set<String> codes() {
        return channels.keySet();
    }

    /** 供启动日志与排障用：把「渠道代码 + 是否即时成功 + 实现类」一次说清 */
    public String describe() {
        return channels.values().stream()
                .map(c -> c.code() + (c.instantSuccess() ? "[预下单即成功]" : "[走回调]")
                        + "=" + c.getClass().getSimpleName())
                .collect(Collectors.joining("，"));
    }
}
