package com.duoduo.social.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * Redis 订阅端：把频道里的事件推给**本实例**持有的连接。
 *
 * <p>订阅容器在 Redis 不可用时会持续重连并打日志，但不会阻止应用启动 ——
 * 实时能力可以降级，业务接口不能因此起不来。
 */
@Component
public class RealtimeSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(RealtimeSubscriber.class);

    private final ConnectionRegistry registry;
    private final ObjectMapper objectMapper;

    public RealtimeSubscriber(ConnectionRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String body = new String(message.getBody());
            RealtimePublisher.Envelope envelope =
                    objectMapper.readValue(body, RealtimePublisher.Envelope.class);
            if (envelope == null || envelope.toUserId() == null) {
                return;
            }
            registry.send(envelope.toUserId(), envelope.event());
        } catch (Exception e) {
            log.warn("实时事件消费失败：{}", e.getMessage());
        }
    }

    @Configuration
    public static class Config {

        @Bean
        public RedisMessageListenerContainer realtimeListenerContainer(
                RedisConnectionFactory factory, RealtimeSubscriber subscriber) {
            RedisMessageListenerContainer container = new RedisMessageListenerContainer();
            container.setConnectionFactory(factory);
            container.addMessageListener(subscriber, new ChannelTopic(RealtimePublisher.CHANNEL));
            return container;
        }
    }
}
