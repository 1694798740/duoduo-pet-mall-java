-- F-04 · 店员操作权限细分（2026-10-26）
--
-- 背景：E13 引入的店员只有 OWNER / STAFF 两级角色，店员一旦被邀请就能改全部商品、
-- 改价、发货、退款、回复客服 —— 店主无法表达「只能发货、不能改价」这类真实诉求。
--
-- 口径：
--   * permissions 为逗号分隔的权限键（StaffPermission 枚举名），顺序固定；
--   * NULL  = F-04 之前的历史行 → 按「全量」解释（升级当天店员可用范围不缩小）；
--   * ''    = 显式「仅查看」（可以进工作台看数据，但不能执行任何写操作）；
--   * 店主（t_merchant_member.role = OWNER）恒为全量，本列对店主无意义。
--
-- 权限键：
--   PRODUCT_EDIT    商品内容编辑（标题 / 图片 / 详情 / 类目 / 规格结构）
--   PRODUCT_PRICE   商品改价 / 改库存
--   PRODUCT_STATUS  商品上下架 / 删除 / 恢复 / 提审
--   ORDER_SHIP      订单发货
--   ORDER_REFUND    售后退款
--   SUPPORT_REPLY   客服回复
--
-- 回滚：ALTER TABLE t_merchant_member DROP COLUMN permissions;
-- 注：回滚只会丢失权限配置，不影响成员身份与店铺归属；历史行为回到「全量」。
ALTER TABLE `t_merchant_member`
    ADD COLUMN `permissions` VARCHAR(255) NULL
        COMMENT '店员权限键（逗号分隔；NULL=历史行按全量，空串=仅查看）'
        AFTER `status`;
