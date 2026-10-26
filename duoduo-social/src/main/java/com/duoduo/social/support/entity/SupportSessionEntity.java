package com.duoduo.social.support.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 客服会话（自营客服 / 第三方商家客服共用一张表）。
 *
 * <p>归属键是 {@code (seller_id, user_id)}，不是私信那套 {@code "lo:hi"} 字符串键 ——
 * 这样「客服会话混进私信列表」在数据层就不可能发生：两张表、
 * 两套索引，私信查询根本看不见这里的行。
 *
 * <p>{@code seller_id} 的语义是**接待方**：
 * <ul>
 *   <li>第三方商家：商品归属卖家（{@code t_spu.seller_id}）；</li>
 *   <li>自营：官方客服账号 id（自营商品的 seller_id 在增量 16 里已归到该账号）。</li>
 * </ul>
 * 因此「区分自营客服与商家客服」不需要额外类型字段 —— 看 seller_id 是不是客服账号即可，
 * 少一个可能与事实不一致的枚举。
 */
@TableName("t_support_session")
public class SupportSessionEntity {

    @TableId
    private Long id;

    /** 接待方（店铺主体 userId / 自营客服账号 id） */
    private Long sellerId;
    /** 咨询用户 */
    private Long userId;

    /** OPEN / CLOSED */
    private String status;

    /**
     * 关闭时刻（2026-10-23）。
     *
     * <p>不能拿 {@code last_message_at} 代替：「关闭」这个动作本身不产生消息，
     * 两个时间点必然不同；30 天清理的判据是「关闭满 30 天」，必须有自己的时间戳。
     * 买家再发消息会**清空**它（会话重新打开）。
     */
    private LocalDateTime closedAt;

    /**
     * 当前处理人（C-04）：NULL = 未认领。
     *
     * <p>它**不是访问控制**：店主与店员本来就能看到全店会话；这个字段只回答
     * 「现在是谁在接这个买家」，用于工作台排序、避免撞单与交接追溯。
     */
    private Long assigneeId;
    private LocalDateTime assignedAt;

    private LocalDateTime lastMessageAt;
    private String lastMessagePreview;

    /** 冗余未读计数：列表页不做 N+1 子查询，标记已读时与消息行同步修正 */
    private Integer unreadForUser;
    private Integer unreadForSeller;

    /**
     * 双方各自「读到的最大消息 id」（第七十八轮 A 批）。
     *
     * <p>为什么不逐条查 read_at：会话列表与轮询每几秒一次，逐条统计会把
     * 最简单的一次「已读」变成 N 次扫描。游标只回答一个问题——
     * 「对方读到哪了」，已读/未读、未读条数都从它推出来。
     */
    private Long userLastReadId;
    private Long sellerLastReadId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSellerId() {
        return sellerId;
    }

    public void setSellerId(Long sellerId) {
        this.sellerId = sellerId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(LocalDateTime closedAt) {
        this.closedAt = closedAt;
    }

    public Long getAssigneeId() {
        return assigneeId;
    }

    public void setAssigneeId(Long assigneeId) {
        this.assigneeId = assigneeId;
    }

    public LocalDateTime getAssignedAt() {
        return assignedAt;
    }

    public void setAssignedAt(LocalDateTime assignedAt) {
        this.assignedAt = assignedAt;
    }

    public LocalDateTime getLastMessageAt() {
        return lastMessageAt;
    }

    public void setLastMessageAt(LocalDateTime lastMessageAt) {
        this.lastMessageAt = lastMessageAt;
    }

    public String getLastMessagePreview() {
        return lastMessagePreview;
    }

    public void setLastMessagePreview(String lastMessagePreview) {
        this.lastMessagePreview = lastMessagePreview;
    }

    public Integer getUnreadForUser() {
        return unreadForUser;
    }

    public void setUnreadForUser(Integer unreadForUser) {
        this.unreadForUser = unreadForUser;
    }

    public Integer getUnreadForSeller() {
        return unreadForSeller;
    }

    public void setUnreadForSeller(Integer unreadForSeller) {
        this.unreadForSeller = unreadForSeller;
    }

    public Long getUserLastReadId() {
        return userLastReadId;
    }

    public void setUserLastReadId(Long userLastReadId) {
        this.userLastReadId = userLastReadId;
    }

    public Long getSellerLastReadId() {
        return sellerLastReadId;
    }

    public void setSellerLastReadId(Long sellerLastReadId) {
        this.sellerLastReadId = sellerLastReadId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
