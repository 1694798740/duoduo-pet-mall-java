package com.duoduo.trade.mq;

import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.trade.entity.OrderPlaceTaskEntity;
import com.duoduo.trade.mapper.OrderPlaceTaskMapper;
import com.duoduo.trade.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 削峰下单消息消费者（A11）。
 *
 * <p>消费动作 = 调用与同步端点<b>同一份</b> {@code placeOrder(userId, request)} ——
 * 这就是验收④「削峰不改变幂等与状态机语义」的实现方式：不是重写一遍「看起来等价」的逻辑，
 * 而是让两条路径共享同一份代码，改哪儿都一起变。
 *
 * <p>失败分两类，处置完全不同：
 * <ul>
 *   <li><b>业务性失败</b>（{@link BizException} 且非 DEGRADED：库存不足 / 已下架 / 购物车为空……）
 *       —— 重试一万次也不会有货，直接把任务标 FAILED（带原因）并 ACK，不进重试；</li>
 *   <li><b>系统类失败</b>（DEGRADED / 其它异常：下游抖动、网络错误……）
 *       —— 重试有意义，返回 RECONSUME_LATER 交给 broker 重试；耗尽后进死信，
 *       任务保持 PENDING，由运维经 DLQ 重放（重放安全：幂等键唯一索引兜底）。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "duoduo.trade.order-mode", havingValue = "queue")
public class OrderPlaceConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OrderPlaceConsumer.class);

    private final DefaultMQPushConsumer consumer;
    private final OrderService orderService;
    private final OrderPlaceTaskMapper taskMapper;
    private final ObjectMapper objectMapper;
    private volatile boolean running;

    public OrderPlaceConsumer(@Value("${duoduo.trade.mq.namesrv}") String namesrv,
                              @Value("${duoduo.trade.mq.topic}") String topic,
                              @Value("${duoduo.trade.mq.consumer-group}") String group,
                              @Value("${duoduo.trade.mq.max-reconsume:2}") int maxReconsume,
                              OrderService orderService,
                              OrderPlaceTaskMapper taskMapper,
                              ObjectMapper objectMapper) {
        this.orderService = orderService;
        this.taskMapper = taskMapper;
        this.objectMapper = objectMapper;
        this.consumer = new DefaultMQPushConsumer(group);
        this.consumer.setNamesrvAddr(namesrv);
        // 快速进死信：生产环境可调大；默认 2 次让「下游长时间故障」在 ~40s 内可见、可重放
        this.consumer.setMaxReconsumeTimes(maxReconsume);
        this.consumer.setConsumeThreadMin(8);
        this.consumer.setConsumeThreadMax(8);
        this.consumer.registerMessageListener((MessageListenerConcurrently) this::onMessage);
        try {
            this.consumer.subscribe(topic, "*");
        } catch (Exception e) {
            throw new IllegalStateException("MQ 消费者订阅失败", e);
        }
    }

    /** 消费入口：解析失败的消息直接丢弃（重放一条永远解析不了的消息没有意义），其余按业务/系统分类处置 */
    private ConsumeConcurrentlyStatus onMessage(List<MessageExt> msgs, ConsumeConcurrentlyContext context) {
        for (MessageExt msg : msgs) {
            OrderPlacePayload payload;
            try {
                payload = objectMapper.readValue(msg.getBody(), OrderPlacePayload.class);
            } catch (Exception e) {
                log.error("下单消息解析失败，丢弃 msgId={}", msg.getMsgId());
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }
            ConsumeConcurrentlyStatus status = consumeOne(msg, payload);
            if (status == ConsumeConcurrentlyStatus.RECONSUME_LATER) {
                return status;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    private ConsumeConcurrentlyStatus consumeOne(MessageExt msg, OrderPlacePayload payload) {
        Long userId = payload.userId();
        String key = payload.request() == null ? null : payload.request().idempotentKey();
        if (userId == null || key == null || key.isBlank()) {
            log.error("下单消息缺身份/幂等键，丢弃 msgId={}", msg.getMsgId());
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        }
        try {
            orderService.placeOrder(userId, payload.request());
            taskMapper.markPlaced(userId, key);
            log.info("削峰下单完成 userId={} idempotentKey={} msgId={}", userId, key, msg.getMsgId());
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        } catch (BizException e) {
            if (e.getErrorCode() == ErrorCode.DEGRADED) {
                log.warn("削峰下单遇依赖不可用，交回队列重试 userId={} key={} msgId={} reconsume={}/{}",
                        userId, key, msg.getMsgId(), msg.getReconsumeTimes(), consumer.getMaxReconsumeTimes());
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
            taskMapper.markFailed(userId, key, trimReason(e.getMessage() == null ? e.getErrorCode().name() : e.getMessage()));
            log.warn("削峰下单业务性失败 userId={} key={} code={} reason={}",
                    userId, key, e.getErrorCode(), e.getMessage());
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        } catch (Throwable t) {
            log.error("削峰下单系统异常 userId={} key={} msgId={}", userId, key, msg.getMsgId(), t);
            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
        }
    }

    private static String trimReason(String reason) {
        if (reason == null) {
            return "下单失败";
        }
        return reason.length() > 190 ? reason.substring(0, 190) : reason;
    }

    // ---- SmartLifecycle ----

    @Override
    public void start() {
        try {
            consumer.start();
            running = true;
            log.info("削峰下单消费者已启动 group={} maxReconsume={}",
                    consumer.getConsumerGroup(), consumer.getMaxReconsumeTimes());
        } catch (Exception e) {
            throw new IllegalStateException("MQ 消费者启动失败（order-mode=queue 需要 RocketMQ 可用）", e);
        }
    }

    @Override
    public void stop() {
        consumer.shutdown();
        running = false;
        log.info("削峰下单消费者已停止");
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
