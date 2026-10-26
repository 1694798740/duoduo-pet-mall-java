package com.duoduo.common.seller;

/**
 * F-04 · 店员操作权限清单（全项目唯一口径）。
 *
 * <p><b>为什么是"操作级"而不是"角色级"</b>：店主对店员的诉求从来不是
 * "他是不是管理员"，而是"这活儿能不能让他干"—— 最典型的是
 * 「只能发货、不能改价」。如果继续用 OWNER / STAFF 两级角色，任何一个
 * 店员要么全都能干、要么什么都干不了，店主只能靠"停用/启用"来回折腾。
 *
 * <p><b>键即落库值</b>（{@code t_merchant_member.permissions} 的逗号分隔项）：
 * 与 {@code AuditAction} 同一纪律 —— 枚举名是持久化契约，改名会让历史行与新行
 * 分成两套口径。新增权限只能追加，不能重命名既有键。
 *
 * <p><b>店主恒为全量、平台角色不受限</b>：权限只约束 STAFF 成员行；
 * 店主（role=SELLER）与运营 / 管理员走各自的既有通道，不读这张清单。
 */
public enum StaffPermission {

    /** 商品内容编辑：标题 / 图片 / 详情 / 类目 / 规格结构。 */
    PRODUCT_EDIT("商品内容编辑"),

    /** 商品改价 / 改库存：高频运营动作，但直接影响成交金额，单列。 */
    PRODUCT_PRICE("商品改价 / 改库存"),

    /** 商品上下架 / 删除 / 恢复 / 提审：改变"这件商品现在卖不卖"。 */
    PRODUCT_STATUS("商品上下架 / 删除"),

    /** 订单发货。 */
    ORDER_SHIP("订单发货"),

    /** 售后退款（卖家确认退货后同意退款）。 */
    ORDER_REFUND("售后退款"),

    /** 客服会话回复。 */
    SUPPORT_REPLY("客服回复");

    private final String label;

    StaffPermission(String label) {
        this.label = label;
    }

    /** 人读名称（前端配置页与 403 文案共用，避免两处各写一套中文）。 */
    public String label() {
        return label;
    }
}
