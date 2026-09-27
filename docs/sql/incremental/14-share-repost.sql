-- ============================================================================
-- 第四十六轮 · 分享圈转发：履约库增量 DDL
-- （对已有环境执行；全新环境直接跑 04 全量脚本）
--
-- 根因：第四十三/四十四轮的「转发」只是调起微信原生转发面板，从未写后端数据 ——
--   · 分享圈列表里当然不会出现新动态（压根没提交）；
--   · 转发卡片落地页 path 带的是**原分享单 ID**，详情页只能按原发帖人渲染。
-- 修复：把「转发」建成数据模型里的一层 —— 转发 = 一条新分享行，指向它转的原帖。
-- ============================================================================
SET NAMES utf8mb4;
USE `duoduo_fulfilment`;

-- 被转发的原分享单 ID；NULL = 原创（第四十三轮起的历史数据全部是原创，语义安全）
ALTER TABLE t_product_share
    ADD COLUMN parent_share_id BIGINT NULL COMMENT '被转发的原分享单ID；NULL=原创';

-- 一人对同一条原帖只能转发一次。
-- MySQL 唯一索引把 NULL 视为互不相等，因此原创行（parent_share_id IS NULL）
-- 不受约束，一个用户可以有多条原创；只有「重复转发同一条」会撞键。
CREATE UNIQUE INDEX uk_share_user_parent ON t_product_share (user_id, parent_share_id);

-- 原帖的转发列表 / 转发链路回溯（低频，单列够用）
CREATE INDEX idx_share_parent ON t_product_share (parent_share_id);
