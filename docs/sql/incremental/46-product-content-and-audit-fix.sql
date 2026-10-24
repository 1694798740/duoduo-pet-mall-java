-- 46 · 商品内容能力 + 审核防绕过（2026-10-24，第九十三轮）
--
-- 两件事：
--  ① 持久化「曾被驳回」的事实：此前只读 audit_status，卖家第一次编辑后状态变 PENDING，
--     第二次编辑就丢失「驳回」记忆 → 命中 decideAudit 干净内容即自动通过。
--     新增 rejected_at（最近驳回时间，人工通过时清空）+ reject_count（累计驳回次数，只增）。
--  ② 补齐可编辑的商品内容：富文本详情、商品介绍、规格参数、包装规格。
--     detail 保留为「商品介绍」纯文本（历史数据兼容），detail_html 为新富文本正文。
--
-- 幂等：2026-10-24 起新环境由 02 尾部同款列覆盖；本脚本对已有环境执行。
SET NAMES utf8mb4;
USE `duoduo_product`;

ALTER TABLE t_spu
    ADD COLUMN intro        VARCHAR(2000) NULL COMMENT '商品介绍（纯文本，富文本详情之外的短介绍）',
    ADD COLUMN detail_html  MEDIUMTEXT    NULL COMMENT '富文本详情 HTML（服务端白名单消毒后落库）',
    ADD COLUMN spec_params  TEXT          NULL COMMENT '规格参数 JSON：[{"name":"","value":""}]',
    ADD COLUMN packaging    TEXT          NULL COMMENT '包装规格 JSON：[{"name":"","value":""}]',
    ADD COLUMN rejected_at  DATETIME      NULL COMMENT '最近一次被驳回的时间（人工通过时清空）',
    ADD COLUMN reject_count INT NOT NULL DEFAULT 0 COMMENT '累计被驳回次数（只增，运营参考）';
