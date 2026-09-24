package com.duoduo.trade.service;

import com.duoduo.common.enums.ErrorCode;
import com.duoduo.common.exception.BizException;
import com.duoduo.trade.dto.OrderDtos.DlqMessageView;
import com.duoduo.trade.mq.OrderPlaceProducer;
import org.apache.rocketmq.client.QueryResult;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 死信查询与重放（A11）。
 *
 * <p>死信 = 消费重试耗尽后被 broker 移入的 {@code %DLQ%<consumer-group>} 主题。
 * 按 {@code keys=idempotentKey} 查询（生产者发送时就把幂等键写进 keys）——
 * 运维从任务台账拿到幂等键，就能定位死信原文，不需要搭 dashboard。
 *
 * <p>重放 = 把死信原文原样发回主 topic。重放天然安全：消费者执行的还是同一份
 * {@code placeOrder}，订单侧 {@code uk_order_user_idem} 唯一索引保证「同一幂等键
 * 不会建出第二个订单」—— 重放只会把没做完的事做完，不会把做过的事再做一遍。
 */
@Service
@ConditionalOnProperty(name = "duoduo.trade.order-mode", havingValue = "queue")
public class DlqService {

    private static final Logger log = LoggerFactory.getLogger(DlqService.class);

    private final String namesrv;
    private final String consumerGroup;
    private final String topic;
    private final OrderPlaceProducer producer;

    public DlqService(@Value("${duoduo.trade.mq.namesrv}") String namesrv,
                      @Value("${duoduo.trade.mq.consumer-group}") String consumerGroup,
                      @Value("${duoduo.trade.mq.topic}") String topic,
                      OrderPlaceProducer producer) {
        this.namesrv = namesrv;
        this.consumerGroup = consumerGroup;
        this.topic = topic;
        this.producer = producer;
    }

    /** 按幂等键查死信（近 24 小时窗口）。查无返回空列表。 */
    public List<DlqMessageView> list(String idempotentKey) {
        DefaultMQAdminExt admin = newAdmin();
        try {
            admin.start();
            long end = System.currentTimeMillis();
            long begin = end - 24L * 3600 * 1000;
            QueryResult qr = admin.queryMessage(dlqTopic(), idempotentKey, 32, begin, end);
            List<DlqMessageView> out = new ArrayList<>();
            for (MessageExt m : qr.getMessageList()) {
                out.add(new DlqMessageView(
                        m.getMsgId(),
                        m.getKeys(),
                        m.getReconsumeTimes(),
                        m.getBody() == null ? null : new String(m.getBody(), StandardCharsets.UTF_8),
                        m.getStoreTimestamp()));
            }
            return out;
        } catch (org.apache.rocketmq.client.exception.MQClientException e) {
            if (e.getResponseCode() == 208) { // ResponseCode.QUERY_NOT_FOUND：窗口内没有命中消息
                return List.of();
            }
            log.error("死信查询失败 key={}", idempotentKey, e);
            throw new BizException(ErrorCode.DEGRADED, "死信查询暂不可用，请稍后再试", e);
        } catch (Exception e) {
            log.error("死信查询失败 key={}", idempotentKey, e);
            throw new BizException(ErrorCode.DEGRADED, "死信查询暂不可用，请稍后再试", e);
        } finally {
            admin.shutdown();
        }
    }

    /** 把该幂等键在死信里的消息原样发回主 topic，返回重放条数 */
    public int replay(String idempotentKey) {
        List<DlqMessageView> messages = list(idempotentKey);
        int replayed = 0;
        for (DlqMessageView m : messages) {
            producer.sendRaw(m.payload() == null ? new byte[0] : m.payload().getBytes(StandardCharsets.UTF_8),
                    m.idempotentKey());
            replayed++;
        }
        log.info("死信重放完成 key={} replayed={}", idempotentKey, replayed);
        return replayed;
    }

    private String dlqTopic() {
        return "%DLQ%" + consumerGroup;
    }

    private DefaultMQAdminExt newAdmin() {
        DefaultMQAdminExt admin = new DefaultMQAdminExt();
        admin.setNamesrvAddr(namesrv);
        // 每次调用独立实例名：DefaultMQAdminExt 按 instanceName 缓存客户端，同名复用会撞上一次未关干净的实例
        admin.setInstanceName("duoduo-dlq-inspect-" + System.nanoTime());
        return admin;
    }
}
