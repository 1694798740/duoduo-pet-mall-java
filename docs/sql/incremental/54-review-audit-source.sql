-- A-15 · 评价机审来源（2026-10-26）
--
-- 为什么需要 audit_source：
--   机审自动放行 / 自动转人工与运营人工审核都会写 audit_status，
--   单看 audited_by 为空只能「猜测」是机器做的，无法审计「这条是谁放行的」。
--   显式记录来源后，后台列表可区分「机审自动」与「人工审核」，
--   也为将来接第三方内容安全服务留出同一列。
--
-- 取值：
--   AUTO   —— 机审自动（干净内容直接 APPROVED，命中风险转 PENDING）
--   MANUAL —— 运营人工审核（下架 / 通过 / 恢复）
--   NULL   —— A-15 之前的历史数据，未记录来源
--
-- 注意：这是可空列，不做 NOT NULL DEFAULT。历史行没有来源是事实，
-- 用一个假默认值会把「未知」伪装成「机审」，事后无法复盘。
SET NAMES utf8mb4;
USE `duoduo_fulfilment`;

ALTER TABLE t_review
    ADD COLUMN audit_source VARCHAR(20) NULL
        COMMENT '审核来源：AUTO 机审自动 / MANUAL 人工；NULL 为 A-15 之前的历史数据'
        AFTER audited_at;
