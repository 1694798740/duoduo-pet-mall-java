-- ============================================================================
-- 多多宠物商店 · 社交域（第四十七轮）：关注关系 / 私信 / 通知中心
-- 对已有环境执行；全新环境直接跑 00（建库）+ 本文件（建表）
-- ============================================================================
SET NAMES utf8mb4;
USE `duoduo_social`;

-- ---------- 关注关系（第四十七轮） ----------
-- 一行 = 「follower 关注了 followee」。互相关注 = 两个方向各存在一行，
-- 一条 COUNT(*)=2 即可判定，不引入状态列 —— 状态列要双向同步，是 bug 温床。
-- 复合主键即唯一约束：重复关注撞键，按「已关注」幂等；取关 = 删行（删 0 行也是幂等）。
CREATE TABLE t_social_follow (
    follower_id  BIGINT    NOT NULL,   -- 关注者（我）
    followee_id  BIGINT    NOT NULL,   -- 被关注者（对方）
    created_at   TIMESTAMP NOT NULL,
    PRIMARY KEY (follower_id, followee_id)
);
-- 粉丝列表 / 「谁关注了我」按 followee 前缀走主键即可；
-- 这里补一列 followee 维度索引便于按被关注者统计与判定「对方是否关注我」
CREATE INDEX idx_follow_followee ON t_social_follow (followee_id);

-- ---------- 私信 / 卡片消息（第四十七轮） ----------
-- 会话键 conversation_id = "lo:hi"（双方 userId 数值小的在前）：
--   主会话（互关私聊 / 商品卡分享）= "lo:hi"；
--   商品咨询会话（第四十九轮）= "lo:hi:p{spuId}"，每件商品独立会话，互不串线。
-- 双向聊天只需这一个索引就能按会话拉取；若只靠 (sender, receiver) 得 UNION 两次查询。
-- 卡片消息（biz_type != TEXT）的摘要 content 由服务端去事实源现取，请求体只带 bizId ——
-- 与分享圈「快照以服务端事实为准」同一纪律，公开接口贴不出伪造卡片。
CREATE TABLE t_social_message (
    id               BIGINT        NOT NULL,   -- 雪花（应用侧生成）
    conversation_id  VARCHAR(64)   NOT NULL,
    sender_id        BIGINT        NOT NULL,
    receiver_id      BIGINT        NOT NULL,
    -- 发送时点的发送方角色快照：USER / SELLER / OPERATOR / ADMIN。
    -- 通知中心据此把私信分流到「好友消息」或「商家消息」，不必反向查用户域。
    sender_role      VARCHAR(20)   NOT NULL,
    -- TEXT=普通文本；PRODUCT=商品卡；SHARE=分享卡；PROFILE_CARD=个人卡；ASK=商品咨询
    biz_type         VARCHAR(20)   NOT NULL,
    biz_id           BIGINT        NULL,       -- 商品 SPU / 分享单 / 被分享用户
    content          VARCHAR(1000) NOT NULL,
    read_at          TIMESTAMP     NULL,       -- NULL = 未读（接收方打开会话时批量置时间）
    created_at       TIMESTAMP     NOT NULL,
    PRIMARY KEY (id)
);
-- 拉会话：一次索引扫描
CREATE INDEX idx_msg_conv_time ON t_social_message (conversation_id, created_at);
-- 会话列表：我参与的消息（去重靠应用侧聚合）
CREATE INDEX idx_msg_receiver_time ON t_social_message (receiver_id, created_at);
CREATE INDEX idx_msg_sender_time ON t_social_message (sender_id, created_at);

-- ---------- 通知中心（第四十七轮，第二批启用；表结构一次建齐避免二次迁移） ----------
-- 定向通知一行只属于一个人，已读直接在行上打 read_at；
-- 公告是「一行多读者」（user_id=0 的公共行），无法在行上记录谁读了，
-- 所以单独用已读集合表 —— 接口层把两者统一成同一个视图，前端无感。
CREATE TABLE t_social_notice (
    id          BIGINT        NOT NULL,
    user_id     BIGINT        NOT NULL,   -- 收件人；0 = 全站公告（公共行）
    -- SYSTEM 系统消息 / ANNOUNCEMENT 全站公告 / FRIEND 好友消息 / MERCHANT 商家消息 / ORDER 订单交易
    category    VARCHAR(20)   NOT NULL,
    title       VARCHAR(100)  NOT NULL,
    content     VARCHAR(500)  NOT NULL,
    biz_type    VARCHAR(30)   NULL,       -- ORDER_PLACED / ORDER_PAID / ORDER_REFUNDED
                                          -- / AFTER_SALE_* / LOGISTICS_NODE / ...
    biz_id      VARCHAR(64)   NULL,       -- 订单号 / 售后单号 / 分享单（雪花 ID 转字符串，T-39）
    jump_page   VARCHAR(150)  NULL,       -- 点击落地页 path
    created_at  TIMESTAMP     NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_notice_user_cat_time ON t_social_notice (user_id, category, created_at);

-- 公告已读集合（notice_id, user_id）
CREATE TABLE t_social_notice_read (
    notice_id   BIGINT    NOT NULL,
    user_id     BIGINT    NOT NULL,
    read_at     TIMESTAMP NOT NULL,
    PRIMARY KEY (notice_id, user_id)
);

-- ---------- 通知中心种子公告（第五十轮启用页面；与增量 17 同款，全新环境由本文件直接带入） ----------
INSERT INTO t_social_notice (id, user_id, category, title, content, biz_type, biz_id, jump_page, created_at)
VALUES
  (730000000000000001, 0, 'ANNOUNCEMENT', '欢迎来到多多宠物商店',
   '这里有猫粮狗粮、罐头零食和宠物用品，下单后资金平台托管、确认收货才放款给商家，买得放心。', NULL, NULL, '/pages/index/index', UTC_TIMESTAMP()),
  (730000000000000002, 0, 'ANNOUNCEMENT', '分享圈上线：晒宠物、看种草',
   '把你家毛孩子的日常分享出来，别的铲屎官可以点赞、评论、收藏，还能一键跳到同款商品。', NULL, NULL, '/pages/share-circle/share-circle', UTC_TIMESTAMP()),
  (730000000000000003, 0, 'ANNOUNCEMENT', '问一问已开通：买前先问官方客服',
   '在商品页点「问一问」即可咨询，每个商品都有独立的咨询会话，官方客服在线答疑。', NULL, NULL, '/pages/index/index', UTC_TIMESTAMP());
