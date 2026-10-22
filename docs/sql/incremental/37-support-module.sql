-- ============================================================================
-- 多多宠物商店 · 增量 37：客服消息独立化（自营客服 + 第三方商家客服）
--
-- 背景：客服会话此前以普通私信的形式存在（t_social_message），
--       于是①会混进私信列表 ②受「非互关限一条」的社交规则约束 ③平台运营端没有接待界面。
-- 本增量把客服拆成独立两张表，并把历史客服/商品咨询消息迁过来，迁完从私信表删除。
--
-- 归属口径（与商品域同源）：
--   第三方商家 → seller_id = t_spu.seller_id
--   自营       → seller_id = 官方客服账号 710000000000000001（增量 16 已把自营商品归给它）
--   会话唯一键 → (seller_id, user_id)：一人对一个接待方一条会话，商品作为消息上下文
--
-- 幂等：临时表重算 + NOT EXISTS 判重，可重复执行；重复执行时第二次不会再迁任何行。
-- 顺序：先备份 t_social_message（备份脚本口径见 scripts/backup），再执行本脚本。
-- ============================================================================
SET NAMES utf8mb4;
USE `duoduo_social`;

-- ---------- 1. 建表 ----------
CREATE TABLE IF NOT EXISTS duoduo_social.t_support_session (
    id                   BIGINT       NOT NULL,
    seller_id            BIGINT       NOT NULL COMMENT '接待方：店铺主体 userId / 自营客服账号',
    user_id              BIGINT       NOT NULL COMMENT '咨询用户',
    status               VARCHAR(20)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN / CLOSED',
    last_message_at      TIMESTAMP    NOT NULL,
    last_message_preview VARCHAR(120) NOT NULL DEFAULT '',
    unread_for_user      INT          NOT NULL DEFAULT 0,
    unread_for_seller    INT          NOT NULL DEFAULT 0,
    created_at           TIMESTAMP    NOT NULL,
    updated_at           TIMESTAMP    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_support_session_pair (seller_id, user_id),
    KEY idx_support_session_user (user_id, last_message_at),
    KEY idx_support_session_seller (seller_id, last_message_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '客服会话（自营/第三方共用）';

CREATE TABLE IF NOT EXISTS duoduo_social.t_support_message (
    id          BIGINT        NOT NULL,
    session_id  BIGINT        NOT NULL,
    sender_id   BIGINT        NOT NULL,
    sender_role VARCHAR(20)   NOT NULL COMMENT 'USER / SELLER / SYSTEM',
    content     VARCHAR(1000) NOT NULL,
    biz_type    VARCHAR(20)   NOT NULL DEFAULT 'TEXT' COMMENT 'TEXT / PRODUCT',
    biz_id      BIGINT        NULL COMMENT 'biz_type=PRODUCT 时为 spuId',
    read_at     TIMESTAMP     NULL,
    created_at  TIMESTAMP     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_support_msg_session (session_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '客服消息';

CREATE TABLE IF NOT EXISTS duoduo_social.t_support_migration_log (
    old_message_id BIGINT    NOT NULL,
    new_message_id BIGINT    NOT NULL,
    seller_id      BIGINT    NOT NULL,
    user_id        BIGINT    NOT NULL,
    migrated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (old_message_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '客服迁移台账（原私信 id → 新客服消息 id）';

-- ---------- 2. 计算待迁行：每条私信 → (seller_id, user_id) ----------
DROP TEMPORARY TABLE IF EXISTS tmp_support_rows;
CREATE TEMPORARY TABLE tmp_support_rows (
    msg_id      BIGINT        NOT NULL,
    seller_id   BIGINT        NOT NULL,
    user_id     BIGINT        NOT NULL,
    sender_id   BIGINT        NOT NULL,
    receiver_id BIGINT        NOT NULL,
    biz_type    VARCHAR(20)   NOT NULL,
    biz_id      BIGINT        NULL,
    content     VARCHAR(1000) NOT NULL,
    read_at     TIMESTAMP     NULL,
    created_at  TIMESTAMP     NOT NULL,
    new_msg_id  BIGINT        NULL,
    PRIMARY KEY (msg_id)
);

INSERT INTO tmp_support_rows
    (msg_id, seller_id, user_id, sender_id, receiver_id, biz_type, biz_id, content, read_at, created_at)
SELECT m.id,
       s.seller_id,
       CASE WHEN s.seller_id = m.receiver_id THEN m.sender_id ELSE m.receiver_id END,
       m.sender_id, m.receiver_id, m.biz_type, m.biz_id, m.content, m.read_at, m.created_at
FROM duoduo_social.t_social_message m
JOIN duoduo_product.t_spu s
  ON s.id = CAST(SUBSTRING_INDEX(m.conversation_id, ':p', -1) AS UNSIGNED)
WHERE m.conversation_id LIKE '%:p%'
  AND NOT EXISTS (SELECT 1 FROM duoduo_social.t_support_migration_log l WHERE l.old_message_id = m.id);

-- 与官方客服账号的「主会话」（无商品段）：迁移到自营客服
INSERT INTO tmp_support_rows
    (msg_id, seller_id, user_id, sender_id, receiver_id, biz_type, biz_id, content, read_at, created_at)
SELECT m.id, 710000000000000001,
       CASE WHEN m.receiver_id = 710000000000000001 THEN m.sender_id ELSE m.receiver_id END,
       m.sender_id, m.receiver_id, m.biz_type, m.biz_id, m.content, m.read_at, m.created_at
FROM duoduo_social.t_social_message m
WHERE m.conversation_id NOT LIKE '%:p%'
  AND (m.sender_id = 710000000000000001 OR m.receiver_id = 710000000000000001)
  AND NOT EXISTS (SELECT 1 FROM duoduo_social.t_support_migration_log l WHERE l.old_message_id = m.id);

-- ---------- 3. 分配新主键 ----------
UPDATE tmp_support_rows SET new_msg_id = UUID_SHORT() WHERE new_msg_id IS NULL;

-- ---------- 4. 归并会话（seller_id, user_id） ----------
DROP TEMPORARY TABLE IF EXISTS tmp_support_sessions;
CREATE TEMPORARY TABLE tmp_support_sessions (
    new_id          BIGINT       NOT NULL,
    seller_id       BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL,
    last_at         TIMESTAMP    NOT NULL,
    first_at        TIMESTAMP    NOT NULL,
    preview         VARCHAR(120) NOT NULL,
    unread_user     INT          NOT NULL,
    unread_seller   INT          NOT NULL,
    PRIMARY KEY (new_id),
    UNIQUE KEY uk_pair (seller_id, user_id)
);

INSERT INTO tmp_support_sessions
    (new_id, seller_id, user_id, last_at, first_at, preview, unread_user, unread_seller)
SELECT UUID_SHORT(), r.seller_id, r.user_id, MAX(r.created_at), MIN(r.created_at), '',
       SUM(CASE WHEN r.receiver_id = r.user_id AND r.read_at IS NULL THEN 1 ELSE 0 END),
       SUM(CASE WHEN r.receiver_id = r.seller_id AND r.read_at IS NULL THEN 1 ELSE 0 END)
FROM tmp_support_rows r
GROUP BY r.seller_id, r.user_id;

-- 已存在的会话复用原 id（重复执行 / 与新数据并存时不会插重复）
UPDATE tmp_support_sessions t
    JOIN duoduo_social.t_support_session s
      ON s.seller_id = t.seller_id AND s.user_id = t.user_id
SET t.new_id = s.id;

INSERT INTO duoduo_social.t_support_session
    (id, seller_id, user_id, status, last_message_at, last_message_preview,
     unread_for_user, unread_for_seller, created_at, updated_at)
SELECT t.new_id, t.seller_id, t.user_id, 'OPEN', t.last_at, '',
       t.unread_user, t.unread_seller, t.first_at, t.last_at
FROM tmp_support_sessions t
WHERE NOT EXISTS (SELECT 1 FROM duoduo_social.t_support_session s
                  WHERE s.seller_id = t.seller_id AND s.user_id = t.user_id);

-- 会话摘要 = 该会话最后一条消息内容
-- ★ 用中间表存「每个会话的最新一条」，不要让同一条 SQL 两次引用临时表：
--   MySQL 对 TEMPORARY 表在同一语句里重复引用会直接报 "Can't reopen table"。
DROP TEMPORARY TABLE IF EXISTS tmp_support_last;
CREATE TEMPORARY TABLE tmp_support_last (
    seller_id BIGINT NOT NULL,
    user_id   BIGINT NOT NULL,
    msg_id    BIGINT NOT NULL,
    PRIMARY KEY (seller_id, user_id)
);
INSERT INTO tmp_support_last (seller_id, user_id, msg_id)
SELECT seller_id, user_id, MAX(msg_id) FROM tmp_support_rows GROUP BY seller_id, user_id;

UPDATE duoduo_social.t_support_session s
    JOIN tmp_support_last l ON l.seller_id = s.seller_id AND l.user_id = s.user_id
    JOIN tmp_support_rows r ON r.msg_id = l.msg_id
SET s.last_message_preview = LEFT(r.content, 120);

-- ---------- 5. 迁消息 ----------
INSERT INTO duoduo_social.t_support_message
    (id, session_id, sender_id, sender_role, content, biz_type, biz_id, read_at, created_at)
SELECT r.new_msg_id, t.new_id, r.sender_id,
       CASE WHEN r.sender_id = r.seller_id THEN 'SELLER' ELSE 'USER' END,
       r.content,
       CASE WHEN r.biz_type IN ('ASK', 'PRODUCT') AND r.biz_id IS NOT NULL THEN 'PRODUCT' ELSE 'TEXT' END,
       CASE WHEN r.biz_type IN ('ASK', 'PRODUCT') THEN r.biz_id ELSE NULL END,
       r.read_at, r.created_at
FROM tmp_support_rows r
    JOIN tmp_support_sessions t
      ON t.seller_id = r.seller_id AND t.user_id = r.user_id;

-- ---------- 6. 台账 + 删除私信原行 ----------
INSERT INTO duoduo_social.t_support_migration_log (old_message_id, new_message_id, seller_id, user_id)
SELECT r.msg_id, r.new_msg_id, r.seller_id, r.user_id
FROM tmp_support_rows r
WHERE NOT EXISTS (SELECT 1 FROM duoduo_social.t_support_migration_log l
                  WHERE l.old_message_id = r.msg_id);

DELETE m FROM duoduo_social.t_social_message m
    JOIN tmp_support_rows r ON r.msg_id = m.id;

-- ---------- 7. 对账（执行后人工核对这两组数字） ----------
-- 迁移行数：      SELECT COUNT(*) FROM duoduo_social.t_support_migration_log;
-- 新消息行数：    SELECT COUNT(*) FROM duoduo_social.t_support_message;
-- 私信残留客服行：SELECT COUNT(*) FROM duoduo_social.t_social_message
--                 WHERE sender_id = 710000000000000001 OR receiver_id = 710000000000000001;
--                 （应为 0）
-- 会话未读合计：  SELECT SUM(unread_for_user), SUM(unread_for_seller) FROM duoduo_social.t_support_session;
