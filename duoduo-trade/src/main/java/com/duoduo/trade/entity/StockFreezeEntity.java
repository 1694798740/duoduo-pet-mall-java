package com.duoduo.trade.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 库存冻结台账。
 *
 * <p>真实库存在商品服务，本表只记「本订单冻结了哪些 SKU、多少件、什么状态」。
 * 它是**补偿与释放的依据**：取消订单、超时关单时按这张表逐条释放；
 * 退款时又按这张表把货补回库存。
 *
 * <p>{@code (skuId, orderId)} 唯一：保证同一订单对同一 SKU 只冻结一次，
 * 重复下单不会重复冻结。
 *
 * <p>状态机：
 * <pre>
 *   FROZEN ──支付成功──→ DEDUCTED ──退款──→ RESTOCK_PENDING ──入库成功──→ RESTOCKED
 *      │                                                              ↑
 *      └──取消/超时──→ RELEASED                                    失败则留在 PENDING，
 *                                                              由补偿调度重试
 * </pre>
 *
 * <p><b>为什么入库要拆成 PENDING → RESTOCKED 两步，而不是退款时直接调一次入库接口</b>：
 * 商品的入库接口不像 freeze/release 那样自带幂等护栏（冻结量不足即视为重复），
 * 重复调用会真的把库存加多。拆成台账状态迁移之后：
 * <ul>
 *   <li>退款**不会因为商品服务抖动而失败** —— 买家的钱该退就退，
 *       入库是仓库侧的后续动作，不该阻塞退款；</li>
 *   <li>入库失败有了**可见的落点**：台账上写着 {@code RESTOCK_PENDING}，
 *       补偿调度会重试，不需要人工去翻日志猜哪一单的货没回库；</li>
 *   <li>入库成功即置 {@code RESTOCKED}，重试时只挑 PENDING 的行，不会重复入库。</li>
 * </ul>
 *
 * <p><b>已知边界</b>：若入库请求「商品服务已生效、但响应在网络上丢失」，
 * 重试会造成重复入库。彻底解决需要给入库也带幂等键（商品侧建
 * 「已入库记录表」按 {@code (ref_no, sku_id)} 去重），属跨服务幂等的完整版，
 * 已记入计划的手续项，当前用「台账状态 + 对账」兜住。
 */
@TableName("t_stock_freeze")
public class StockFreezeEntity {

    public static final String STATUS_FROZEN = "FROZEN";
    public static final String STATUS_DEDUCTED = "DEDUCTED";
    public static final String STATUS_RELEASED = "RELEASED";
    /** 退款已成立、等待商品服务入库回补 */
    public static final String STATUS_RESTOCK_PENDING = "RESTOCK_PENDING";
    /** 已入库回补完成 */
    public static final String STATUS_RESTOCKED = "RESTOCKED";

    @TableId
    private Long id;
    private Long skuId;
    private Long orderId;
    private Integer quantity;
    private String status;
    private LocalDateTime expireAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSkuId() { return skuId; }
    public void setSkuId(Long skuId) { this.skuId = skuId; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getExpireAt() { return expireAt; }
    public void setExpireAt(LocalDateTime expireAt) { this.expireAt = expireAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
