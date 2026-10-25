-- 50 · 商品软删 / 回收站（2026-10-24，A-09）
--
-- 问题：卖家只能下架，不能删除；废弃或违规商品永久占着「我的商品」列表，
-- 越积越多。平台侧也缺少「删除后 30 天内可恢复」的兜底。
--
-- 方案：t_spu 增加 deleted_at（软删时间）。所有读路径一律带 deleted_at IS NULL；
-- 回收站列表反过来只看非空。删除只写这一列，不物理删行 —— 订单明细、评价、
-- 审核历史都按 spu_id 引用商品，物理删会让历史页面变成「查无此物」。
--
-- 30 天清理：定时任务物理删除 deleted_at 早于 cutoff 的商品与 SKU；
-- 审核历史 t_spu_audit_log 保留（append-only 审计语义，不随商品清理）。
--
-- 为什么删除不参与 A-07 的 version：与上下架同理，是正交的生命周期动作；
-- 并发写由「所有写路径的 WHERE 都带 deleted_at IS NULL」兜底，不制造 409 噪音。
--
-- 部署顺序：先执行本脚本，再发布商品服务（新代码会读写这一列）。
SET NAMES utf8mb4;
USE `duoduo_product`;

ALTER TABLE t_spu
    ADD COLUMN deleted_at TIMESTAMP NULL COMMENT '软删时间（A-09）；非空=回收站中，所有读路径排除',
    ADD INDEX idx_spu_recycle (seller_id, deleted_at, updated_at),
    ADD INDEX idx_spu_deleted (deleted_at);
