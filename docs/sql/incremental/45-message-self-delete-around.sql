-- 45 · 消息单向删除 + 引用跳转定位窗口（2026-10-25）
-- 单向删除：只对操作者自己隐藏，对方仍可见（与撤回的双向语义区分）。
-- 撤回仍是软删覆写（recalled_at），单向删除只落「谁在何时删了」的时间戳，内容不动。
USE duoduo_social;

ALTER TABLE t_support_message
  ADD COLUMN hidden_for_user_at DATETIME NULL COMMENT '用户侧单向删除时间（NULL=可见）',
  ADD COLUMN hidden_for_seller_at DATETIME NULL COMMENT '商家侧单向删除时间（NULL=可见）';

ALTER TABLE t_social_message
  ADD COLUMN hidden_for_sender_at DATETIME NULL COMMENT '发送方单向删除时间（NULL=可见）',
  ADD COLUMN hidden_for_receiver_at DATETIME NULL COMMENT '接收方单向删除时间（NULL=可见）';
