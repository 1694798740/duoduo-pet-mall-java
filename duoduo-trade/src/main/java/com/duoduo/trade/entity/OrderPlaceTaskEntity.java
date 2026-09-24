package com.duoduo.trade.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 下单任务台账（A11 · 削峰队列）。
 *
 * <p>异步化之后，「下单请求被接受」与「订单真正落库」是两个时刻 ——
 * 中间这段的不确定性必须有一个可查询的落点，否则用户交了请求却不知道发生了什么：
 *
 * <ul>
 *   <li>{@link #STATUS_PENDING}：已受理入队，等消费者执行（系统类失败在重试期间一直停留在这里）；</li>
 *   <li>{@link #STATUS_PLACED}：消费者执行成功，订单已建（按幂等键查订单即可）；</li>
 *   <li>{@link #STATUS_FAILED}：业务性失败（库存不足 / 购物车为空 / 已下架……），带原因，不重试。</li>
 * </ul>
 *
 * <p>幂等：唯一键 {@code uk_place_task_user_idem(user_id, idempotent_key)} ——
 * 与订单表的幂等键同构。用户双击 / 网络重试重复入队，第二条会撞唯一索引，
 * 服务端直接返回已有任务，不产生第二条消息。
 */
@TableName("t_order_place_task")
public class OrderPlaceTaskEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PLACED = "PLACED";
    public static final String STATUS_FAILED = "FAILED";

    @TableId
    private Long id;
    private Long userId;
    private String idempotentKey;
    private String status;
    /** 业务性失败原因（PLACED / PENDING 时为 null） */
    private String failReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getIdempotentKey() { return idempotentKey; }
    public void setIdempotentKey(String idempotentKey) { this.idempotentKey = idempotentKey; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
