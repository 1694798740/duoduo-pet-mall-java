package com.duoduo.product.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 代运营工单（E23 · 商品域）：平台代第三方店铺操作商品的**授权凭证**。
 *
 * <h2>为什么必须有这张表
 * 平台经营自营商品时是「自己的事」，但**第三方店铺的商品不属于平台** ——
 * 替它发货、替它裁售后，动的都是别人的经营资产。没有凭证的代操作就是越权，
 * 而审计表回答不了这个问题：它记的是「谁在什么时候做了什么」，
 * 记不了「商家到底同不同意」。工单才是那句话的证据。
 *
 * <h2>四个必须同时成立的判据（缺一个都不算有效授权）
 * <ol>
 *   <li><b>存在且为 {@code OPEN}</b> —— 已消费 / 已作废的不能再用来执行动作；</li>
 *   <li><b>{@code tenant_id} == 目标商品的店铺主体</b> —— 这是安全关键的一条：
 *       拿 A 店的工单去动 B 店，是本类漏洞最典型的形态；</li>
 *   <li><b>{@code action_type} 与要执行的动作一致</b> —— 「申请的是协助发货，
 *       平台却拿去退款」必须挡住；</li>
 *   <li><b>{@code target_ref} 与目标对象一致</b> —— 一个商品的工单不能用来动另一个商品。</li>
 * </ol>
 * 判据 2 之所以只写「等于店铺主体」，是因为租户键就是<b>店铺主体的 userId</b>
 * （{@code t_spu.seller_id}，见 E20 的口径），不是 {@code t_merchant.id}。
 * 两处口径若分开，就会出现「校验通过、动作落在别人家」这种最坏的组合。
 *
 * <h2>一次性消费
 * 执行成功后置 {@code CONSUMED}（同一事务内）。已消费的**不允许**回到 {@code OPEN} ——
 * 一旦允许回退，「这个工单用过没有」就变成一个需要计次的问题，而次数是可以被绕过的。
 *
 * <h2>为什么表在商品库，而不是抽到 duoduo_user 做全局唯一
 * 核验工单与执行动作必须处在**同一个本地事务**里。若工单在别的服务，
 * 「核验通过」与「动作执行」之间就存在中间态：核验完、动作没做完时进程挂掉，
 * 工单已烧掉而操作没生效（或反过来，操作生效了工单还是 OPEN）。
 * 跨服务的补偿不是不能写，而是这里根本不需要 —— 表跟着动作走就没有这个问题。
 */
@TableName("t_proxy_work_order")
public class ProxyWorkOrderEntity {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_CONSUMED = "CONSUMED";
    public static final String STATUS_VOID = "VOID";

    public static final String TARGET_ORDER = "ORDER";
    public static final String TARGET_PRODUCT = "PRODUCT";

    public static final String ACTION_SHIP = "SHIP";
    public static final String ACTION_AFTERSALE = "AFTERSALE";
    public static final String ACTION_OFF_SHELF = "OFF_SHELF";

    /** 工单号领域前缀。交易域与商品域各一张表，前缀让「这单该去哪张表找」在人工排查时一眼可见 */
    public static final String NO_PREFIX_ORDER = "WO-ORD-";
    public static final String NO_PREFIX_PRODUCT = "WO-PRD-";

    @TableId
    private Long id;
    private String workOrderNo;
    /** 被代运营的店铺主体 userId（= owner_user_id） */
    private Long tenantId;
    private String targetType;
    /** PRODUCT 存 spuId 字符串（本库只允许 PRODUCT） */
    private String targetRef;
    private String actionType;
    private String reason;
    /** 申请人 userId —— 必须是该店铺店主本人 */
    private Long requesterUserId;
    private String status;
    private LocalDateTime consumedAt;
    private Long consumedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static String labelOf(String status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case STATUS_OPEN -> "待执行";
            case STATUS_CONSUMED -> "已执行";
            case STATUS_VOID -> "已作废";
            default -> status;
        };
    }

    /** 动作中文名：审计与日志里显示这个，页面上的枚举名对运营没有意义 */
    public static String actionLabelOf(String actionType) {
        if (actionType == null) {
            return "未知";
        }
        return switch (actionType) {
            case ACTION_SHIP -> "代发货";
            case ACTION_AFTERSALE -> "代处理售后";
            case ACTION_OFF_SHELF -> "代下架商品";
            default -> actionType;
        };
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getWorkOrderNo() { return workOrderNo; }
    public void setWorkOrderNo(String workOrderNo) { this.workOrderNo = workOrderNo; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public String getTargetRef() { return targetRef; }
    public void setTargetRef(String targetRef) { this.targetRef = targetRef; }
    public String getActionType() { return actionType; }
    public void setActionType(String actionType) { this.actionType = actionType; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Long getRequesterUserId() { return requesterUserId; }
    public void setRequesterUserId(Long requesterUserId) { this.requesterUserId = requesterUserId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getConsumedAt() { return consumedAt; }
    public void setConsumedAt(LocalDateTime consumedAt) { this.consumedAt = consumedAt; }
    public Long getConsumedBy() { return consumedBy; }
    public void setConsumedBy(Long consumedBy) { this.consumedBy = consumedBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
