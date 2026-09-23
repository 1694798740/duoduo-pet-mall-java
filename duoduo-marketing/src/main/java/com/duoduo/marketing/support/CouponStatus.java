package com.duoduo.marketing.support;

/**
 * 券状态机（单一事实来源）。
 *
 * <p>把状态与文案收在一处，是为了让「列表页签」「详情展示」「服务层校验」三处永远同源 ——
 * 与项目其它状态（订单 / 售后）同一原则。
 *
 * <pre>
 *   UNUSED ──lock──▶ LOCKED ──consume──▶ USED（终态）
 *     ▲                │
 *     └────release─────┘（关单 / 取消 / 锁定超时）
 *
 *   任意非终态 ──过期──▶ EXPIRED
 * </pre>
 *
 * <p>两条硬规则：
 * <ol>
 *   <li><b>USED 是终态</b>。核销后不可再用、不可解锁 —— 券已随订单进了资金链路，
 *       退回去会让「退款时券要不要还」变成一个没有答案的问题（v1 明确：券不退回）。</li>
 *   <li><b>LOCKED 必须能回到 UNUSED</b>。锁券发生在下单、解锁发生在关单 / 超时，
 *       这两条路径之间隔着「用户关掉浏览器」这种不可控事件，
 *       所以除了交易域主动调 release，还必须有一个按 TTL 扫描的兜底任务。</li>
 * </ol>
 */
public enum CouponStatus {

    /** 未使用：可被下单锁定 */
    UNUSED("未使用"),
    /** 已锁定：被某个未支付订单占用，不能再被别的订单锁 */
    LOCKED("已锁定"),
    /** 已使用：核销终态 */
    USED("已使用"),
    /** 已过期 */
    EXPIRED("已过期");

    private final String label;

    CouponStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 按名字解析；未知返回 {@code null}（调用方决定是 400 还是按默认处理） */
    public static CouponStatus of(String name) {
        if (name == null) {
            return null;
        }
        for (CouponStatus status : values()) {
            if (status.name().equalsIgnoreCase(name.trim())) {
                return status;
            }
        }
        return null;
    }

    /** 取中文文案；未知状态原样返回，绝不在展示层静默吞掉未知值 */
    public static String labelOf(String name) {
        CouponStatus status = of(name);
        return status == null ? name : status.label;
    }

    /** 是否为终态（终态不可再迁移，重复操作一律 409） */
    public boolean terminal() {
        return this == USED || this == EXPIRED;
    }

    /** 是否可被下单锁定 */
    public boolean lockable() {
        return this == UNUSED;
    }

    /** 是否可被解锁（回到未使用） */
    public boolean releasable() {
        return this == LOCKED;
    }

    /** 是否可被核销 */
    public boolean consumable() {
        return this == LOCKED;
    }
}
