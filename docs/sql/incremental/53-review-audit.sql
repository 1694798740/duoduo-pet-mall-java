-- 53 · 评价人工审核（2026-10-26，H-02 / A-15）
--
-- 问题：t_review.audit_status 自建表起恒为 APPROVED，评价没有下架入口 ——
-- 违规、广告、泄露隐私的评价只能靠改库处理，且公开评价区 / 评分汇总没有区分口径的依据。
--
-- 方案：沿用既有的 audit_status（PENDING / APPROVED / REJECTED），补齐「谁、何时、为什么」
-- 三个事实列。运营后台「评价管理」把 APPROVED 改为 REJECTED 即为下架，恢复时写回 APPROVED。
--
-- 语义约定：
--   * audit_status 只由服务端写；下架必须填 audit_reason（事后复盘唯一凭据）；
--   * 恢复时 audit_reason 显式清空（MyBatis-Plus 默认不写 null，必须显式 set）；
--   * audited_by 存运营 userId 快照（与商品审核历史同一口径，用户改昵称不影响可读）；
--   * 公开评价列表 / 评分汇总 / 卖家信用分 / 商品评分回写**只统计 APPROVED**；
--     既有查询已带该过滤，本次不改变口径，只补反向同步（下架后把商品评分重算回真实值）。
--
-- 部署顺序：先执行本脚本，再发布履约服务（新代码会读写三列）。
SET NAMES utf8mb4;
USE `duoduo_fulfilment`;

ALTER TABLE t_review
    ADD COLUMN audit_reason VARCHAR(255) NULL COMMENT '审核结论说明：下架必填，恢复时清空' AFTER audit_status,
    ADD COLUMN audited_by   BIGINT       NULL COMMENT '最近一次审核操作的运营 userId 快照' AFTER audit_reason,
    ADD COLUMN audited_at   TIMESTAMP    NULL DEFAULT NULL COMMENT '最近一次审核时间（UTC）' AFTER audited_by;
