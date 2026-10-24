-- 47 · 订单级库存预占台账（2026-10-24，B-03）
--
-- 解决什么问题：
--   冻结 / 释放 / 扣减原先只校验 t_sku.frozen_stock 这个**全局**数量。
--   同一 SKU 被多个订单同时冻结时，释放 A 单的请求可以把 B 单的冻结量放掉；
--   两个请求各自都返回成功，只有对账时才发现账对不上。
--   根因是商品域不知道「这份冻结属于哪个订单」。
--
-- 本表以 (order_id, sku_id) 为主键：
--   ① 主键即幂等键：同一订单对同一 SKU 只允许一条预占，重复冻结靠 INSERT IGNORE 去重；
--   ② 释放 / 扣减先按订单定位预占行，只操作本订单的那一份，杜绝跨订单误释放；
--   ③ 不跨库：表在商品库，订单 id 只是外部标识，不建外键、不 JOIN 交易库。
--
-- 幂等：CREATE TABLE IF NOT EXISTS；新环境由 02 尾部同款 DDL 覆盖。
SET NAMES utf8mb4;
USE `duoduo_product`;

CREATE TABLE IF NOT EXISTS t_sku_stock_reservation (
    order_id   BIGINT      NOT NULL                COMMENT '订单 id（交易域雪花 id，仅作外部标识，不建外键）',
    sku_id     BIGINT      NOT NULL                COMMENT 'SKU id',
    quantity   INT         NOT NULL                COMMENT '本订单预占数量（必须与交易域台账一致）',
    status     VARCHAR(16) NOT NULL DEFAULT 'FROZEN' COMMENT 'FROZEN / RELEASED / DEDUCTED',
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    updated_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间（UTC）',
    PRIMARY KEY (order_id, sku_id),
    KEY idx_reservation_sku_status (sku_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单级库存预占台账（B-03）';

-- 升级核对（执行本脚本后、重启商品服务前跑一次）：
--   1) 在途订单（t_stock_freeze.status='FROZEN'）必须已由 repair/47-backfill-stock-reservation.sql 补登记；
--   2) 任一 SKU 的「预占 FROZEN 汇总」应等于 t_sku.frozen_stock。
-- 注意：部署顺序必须是「建表 → 补登记 → 交易服务 → 商品服务」。
-- 若先升级商品服务，旧交易服务发来的请求没有 orderId，会被 400 拒绝；
-- 若补登记之后到商品服务重启之前又有新订单成立，需在维护窗口内完成，或重启前再跑一次补登记（可重复执行）。
