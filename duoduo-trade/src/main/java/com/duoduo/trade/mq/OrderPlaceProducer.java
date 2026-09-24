package com.duoduo.trade.mq;

import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 削峰下单消息生产者（A11）。
 *
 * <p>只在 {@code duoduo.trade.order-mode=queue} 时创建（{@link ConditionalOnProperty}）——
 * sync 模式（默认 / CI）下 MQ 相关 Bean 根本不实例化，不连 NameServer、不占资源。
 *
 * <p>同步发送（{@code producer.send}）：入队端点必须知道「消息确实进了 broker」
 * 才能对用户说「已受理」；发失败当场把任务标 FAILED 并对调用方 503，
 * 不留「受理了却永远没人消费」的暗账。keys 带幂等键，死信按它查询与重放。
 */
@Component
@ConditionalOnProperty(name = "duoduo.trade.order-mode", havingValue = "queue")
public class OrderPlaceProducer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OrderPlaceProducer.class);

    private final DefaultMQProducer producer;
    private final ObjectMapper objectMapper;
    private final String topic;
    private volatile boolean running;

    public OrderPlaceProducer(@Value("${duoduo.trade.mq.namesrv}") String namesrv,
                              @Value("${duoduo.trade.mq.topic}") String topic,
                              ObjectMapper objectMapper) {
        this.topic = topic;
        this.objectMapper = objectMapper;
        this.producer = new DefaultMQProducer("duoduo-trade-order-producer");
        this.producer.setNamesrvAddr(namesrv);
        // 同步发送：3 秒超时 + 失败本地重试 2 次；仍失败就向上抛，入队端点按 503 处理
        this.producer.setSendMsgTimeout(3000);
        this.producer.setRetryTimesWhenSendFailed(2);
    }

    /** 同步发送。失败抛异常 —— 调用方（OrderPlaceService）负责把任务标 FAILED 并向调用方降级 */
    public void send(OrderPlacePayload payload) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(payload);
            sendRaw(body, payload.request().idempotentKey());
            log.info("削峰下单已入队 userId={} idempotentKey={}",
                    payload.userId(), payload.request().idempotentKey());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.DEGRADED, "下单队列暂不可用，请稍后再试", e);
        }
    }

    /** 按原始字节发送（死信重放复用：原文原样发回，keys 保持幂等键不动） */
    public void sendRaw(byte[] body, String keys) {
        try {
            Message msg = new Message(topic, null, keys, body);
            SendResult result = producer.send(msg);
            if (result.getSendStatus() != SendStatus.SEND_OK) {
                throw new IllegalStateException("MQ 发送状态异常: " + result.getSendStatus());
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.DEGRADED, "下单队列暂不可用，请稍后再试", e);
        }
    }

    // ---- SmartLifecycle：随 Spring 容器起停，优雅停机时不丢在途消息 ----

    @Override
    public void start() {
        try {
            producer.start();
            running = true;
            log.info("削峰下单生产者已启动 topic={}", topic);
        } catch (Exception e) {
            throw new IllegalStateException("MQ 生产者启动失败（order-mode=queue 需要 RocketMQ 可用）", e);
        }
    }

    @Override
    public void stop() {
        producer.shutdown();
        running = false;
        log.info("削峰下单生产者已停止");
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
