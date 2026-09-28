-- ============================================================================
-- 17 · 通知中心启用（第五十轮）：种子公告
-- t_social_notice / t_social_notice_read 两张表第四十七轮已随 06 建齐，
-- 本脚本只补「可看见的初始内容」——三条全站公告（user_id=0，公共行）。
-- 公告 ID 用保留段 730000000000000001~3，与官方客服账号（710000000000000001）同一套路，
-- 不会与应用侧雪花 ID 冲突；created_at 用 UTC_TIMESTAMP() 与全库口径一致。
-- 对已有环境执行；全新环境由 06 尾部同款种子覆盖。
-- ============================================================================
SET NAMES utf8mb4;
USE `duoduo_social`;

INSERT INTO t_social_notice (id, user_id, category, title, content, biz_type, biz_id, jump_page, created_at)
VALUES
  (730000000000000001, 0, 'ANNOUNCEMENT', '欢迎来到多多宠物商店',
   '这里有猫粮狗粮、罐头零食和宠物用品，下单后资金平台托管、确认收货才放款给商家，买得放心。', NULL, NULL, '/pages/index/index', UTC_TIMESTAMP()),
  (730000000000000002, 0, 'ANNOUNCEMENT', '分享圈上线：晒宠物、看种草',
   '把你家毛孩子的日常分享出来，别的铲屎官可以点赞、评论、收藏，还能一键跳到同款商品。', NULL, NULL, '/pages/share-circle/share-circle', UTC_TIMESTAMP()),
  (730000000000000003, 0, 'ANNOUNCEMENT', '问一问已开通：买前先问官方客服',
   '在商品页点「问一问」即可咨询，每个商品都有独立的咨询会话，官方客服在线答疑。', NULL, NULL, '/pages/index/index', UTC_TIMESTAMP());
