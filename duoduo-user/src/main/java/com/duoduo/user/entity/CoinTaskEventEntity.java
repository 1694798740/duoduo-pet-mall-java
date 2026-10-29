package com.duoduo.user.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 多多币任务事件（R-P1 第三轮）：同日同任务同业务对象只算一次。
 *
 * <p>{@code uk_coin_task_event (user_id, task_code, biz_date, biz_id)} 是防刷的最终闸门 ——
 * 同一商品反复打开、同一订单重复回调，都会被 INSERT IGNORE 撞键挡掉，
 * 进度不会重复 +1。服务层先查进度只是友好提示，最终一致由这条唯一索引保证。
 */
@TableName("t_coin_task_event")
public class CoinTaskEventEntity {

    @TableId
    private Long id;
    private Long userId;
    private String taskCode;
    private LocalDate bizDate;
    /** 业务对象：spuId / shareId / orderNo / reviewId（至少是事件名，不可为空） */
    private String bizId;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getTaskCode() { return taskCode; }
    public void setTaskCode(String taskCode) { this.taskCode = taskCode; }
    public LocalDate getBizDate() { return bizDate; }
    public void setBizDate(LocalDate bizDate) { this.bizDate = bizDate; }
    public String getBizId() { return bizId; }
    public void setBizId(String bizId) { this.bizId = bizId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
