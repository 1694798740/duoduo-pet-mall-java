-- 44 · 消息撤回 / 引用 / 语音（2026-10-24 第九十一轮）
-- 两张消息表同构扩展；撤回为软删（保留行做审计，content 覆写为占位符、媒体字段清空）。

ALTER TABLE t_support_message
  ADD COLUMN recalled_at DATETIME NULL COMMENT '撤回时间；NULL=未撤回',
  ADD COLUMN reply_to_id BIGINT NULL COMMENT '引用的消息 id（同会话内）';

ALTER TABLE t_social_message
  ADD COLUMN recalled_at DATETIME NULL COMMENT '撤回时间；NULL=未撤回',
  ADD COLUMN reply_to_id BIGINT NULL COMMENT '引用的消息 id（同会话内）';
