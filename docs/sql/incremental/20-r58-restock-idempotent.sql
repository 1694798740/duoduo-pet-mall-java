-- ============================================================================
-- 第五十八轮 · 退货入库幂等（B-02）：商品库增量 DDL
-- （对已有环境执行；全新环境直接跑 02 全量脚本）
-- ============================================================================
SET NAMES utf8mb4;
USE `duoduo_product`;

-- ---------------------------------------------------------------------------
-- t_sku_restock_log：退货入库的**幂等去重表**（主键即去重键）
-- ---------------------------------------------------------------------------
-- 为什么必须有这张表：restock 与 freeze / release 不同，它没有天然的幂等护栏 ——
-- 冻结是「冻结量足够才成功」，第二次调用自然失败；而「库存加回」重复执行会真的多加一次，
-- 且不会报任何错。现实里最容易撞上的是「调用已生效、响应丢失」：
-- 商品侧库存已经加回，交易侧却当成失败，下一轮补偿调度再调一次 → 库存凭空多一份。
-- 这种多出来的库存用户侧看不见，只能靠对账发现，且无法归因。
--
-- 为什么去重键是 (ref_no, sku_id)：ref_no 由调用方（交易服务）给出，语义是「一次入库请求」；
-- 同一请求里多个 SKU 各占一行。判据是**插入是否真的发生**（INSERT IGNORE 后 affected=1），
-- 只有新插入的行才执行库存加回 —— 幂等性落在数据库唯一键上，不依赖应用层的「先查有没有」，
-- 后者在并发下会双写。
CREATE TABLE IF NOT EXISTS `t_sku_restock_log` (
    `ref_no`      VARCHAR(64)  NOT NULL                COMMENT '入库请求幂等号（交易侧给出，如 restock:{orderId}）',
    `sku_id`      BIGINT       NOT NULL                COMMENT 'SKU id',
    `quantity`    INT          NOT NULL DEFAULT 0      COMMENT '本次实际加回的数量（审计用）',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    PRIMARY KEY (`ref_no`, `sku_id`),
    KEY `idx_restock_log_sku` (`sku_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '退货入库幂等去重表（主键即去重键，防止响应丢失导致的重复入库）';
