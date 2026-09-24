package com.duoduo.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duoduo.common.exception.BizException;
import com.duoduo.trade.dto.OrderDtos.PlaceOrderRequest;
import com.duoduo.trade.dto.OrderDtos.PlaceTaskView;
import com.duoduo.trade.entity.OrderPlaceTaskEntity;
import com.duoduo.trade.mapper.OrderPlaceTaskMapper;
import com.duoduo.trade.mq.OrderPlacePayload;
import com.duoduo.trade.mq.OrderPlaceProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 削峰下单编排（A11）：受理入队 + 任务查询。只在 order-mode=queue 时创建。
 *
 * <p>入队幂等与同步链路的幂等快路径同构：同一用户同一幂等键重复入队，
 * 返回已有任务而不是产生第二条消息 —— 用户双击 / 网络重试在这里就被吃掉。
 *
 * <p>受理顺序刻意为「先插任务、再发消息」：消息发送是同步确认（SEND_OK 才算受理成功），
 * 发送失败当场把任务标 FAILED 并向调用方 503 —— 不留「说受理了却永远没人消费」的暗账。
 */
@Service
@ConditionalOnProperty(name = "duoduo.trade.order-mode", havingValue = "queue")
public class OrderPlaceService {

    private static final Logger log = LoggerFactory.getLogger(OrderPlaceService.class);

    private final OrderPlaceTaskMapper taskMapper;
    private final OrderPlaceProducer producer;
    private final com.duoduo.common.id.SnowflakeIdGenerator idGenerator;

    public OrderPlaceService(OrderPlaceTaskMapper taskMapper, OrderPlaceProducer producer,
                             com.duoduo.common.id.SnowflakeIdGenerator idGenerator) {
        this.taskMapper = taskMapper;
        this.producer = producer;
        this.idGenerator = idGenerator;
    }

    /** 受理一笔削峰下单：幂等入队 → 同步发送 → 返回任务视图 */
    public PlaceTaskView enqueue(Long userId, PlaceOrderRequest request) {
        String key = request.idempotentKey();

        // 入队幂等快路径
        OrderPlaceTaskEntity existing = findByUserKey(userId, key);
        if (existing != null) {
            return toView(existing);
        }

        OrderPlaceTaskEntity task = new OrderPlaceTaskEntity();
        task.setId(idGenerator.nextId());
        task.setUserId(userId);
        task.setIdempotentKey(key);
        task.setStatus(OrderPlaceTaskEntity.STATUS_PENDING);
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException e) {
            // 并发双击撞唯一索引：返回已存在的那条任务（与同步幂等快路径同构）
            return toView(findByUserKey(userId, key));
        }

        // 发送失败：任务当场 FAILED，调用方拿 503 —— 受理成功必须以「消息进了 broker」为准
        try {
            producer.send(new OrderPlacePayload(userId, request));
        } catch (BizException e) {
            taskMapper.markFailed(userId, key, "入队失败：消息队列暂不可用");
            throw e;
        }

        log.info("削峰下单已受理 userId={} idempotentKey={}", userId, key);
        OrderPlaceTaskEntity after = findByUserKey(userId, key);
        return after == null ? toView(task) : toView(after);
    }

    /** 任务查询（本人）：查无返回 null，由控制器决定 404 */
    public PlaceTaskView placement(Long userId, String idempotentKey) {
        OrderPlaceTaskEntity task = findByUserKey(userId, idempotentKey);
        return task == null ? null : toView(task);
    }

    private OrderPlaceTaskEntity findByUserKey(Long userId, String key) {
        return taskMapper.selectOne(new LambdaQueryWrapper<OrderPlaceTaskEntity>()
                .eq(OrderPlaceTaskEntity::getUserId, userId)
                .eq(OrderPlaceTaskEntity::getIdempotentKey, key));
    }

    private static PlaceTaskView toView(OrderPlaceTaskEntity task) {
        return new PlaceTaskView(task.getIdempotentKey(), task.getStatus(), task.getFailReason(),
                task.getCreatedAt() == null ? null : task.getCreatedAt().toString());
    }
}
