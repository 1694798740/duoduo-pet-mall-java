-- ============================================================================
-- 第五十六轮 · 分享圈补全（评论 / 删除编辑 / 举报 / 话题标签）：履约库增量 DDL
-- （对已有环境执行；全新环境直接跑 04 全量脚本）
-- ============================================================================
SET NAMES utf8mb4;
USE `duoduo_fulfilment`;

-- ---------------------------------------------------------------------------
-- 1. t_product_share 补三列
-- ---------------------------------------------------------------------------
-- deleted_at：软删标记。**必须软删、不能物理删**，两个具体原因：
--   ① 转发单的「原帖引用」是查询时按 parent_share_id 回查原帖行得到的 —— 物理删掉原帖，
--      转发单的引用块会变成空内容（用户视角像页面坏了）；
--   ② 举报处理与计数需要「这条内容曾经存在」这个事实本身。
--   信息流 / 详情 / 话题聚合一律带 deleted_at IS NULL，软删对用户等价于删除。
-- edited_at：编辑时间。NULL = 从未编辑（前端据此显示「已编辑」）。
-- comment_count：评论数冗余列（信息流是高频读路径，不逐行 COUNT 子表）。
ALTER TABLE t_product_share
    ADD COLUMN deleted_at    TIMESTAMP NULL COMMENT '软删时间；非 NULL = 已删除',
    ADD COLUMN edited_at     TIMESTAMP NULL COMMENT '内容编辑时间；NULL = 未编辑',
    ADD COLUMN comment_count INT NOT NULL DEFAULT 0 COMMENT '评论数（冗余列，事实源 t_share_comment）';

-- ---------------------------------------------------------------------------
-- 2. 评论（二级：一级评论 + 其下回复）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_share_comment (
    id                BIGINT       NOT NULL,
    share_id          BIGINT       NOT NULL,
    user_id           BIGINT       NOT NULL,
    -- NULL = 一级评论；非 NULL = 对某条一级评论的回复。
    -- 刻意只做二级：二级以上要处理「回复谁的回复」的折叠与 @ 链路，属热度榜那类复杂度。
    parent_comment_id BIGINT       NULL,
    content           VARCHAR(300) NOT NULL,
    like_count        INT          NOT NULL DEFAULT 0,
    -- 运营处理举报时「下架评论」置此列，不物理删（留痕，也保住回复链）
    deleted_at        TIMESTAMP    NULL,
    created_at        TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);
-- 一级评论分页（公开读高频路径）
CREATE INDEX idx_share_comment_share ON t_share_comment (share_id, parent_comment_id, created_at);
-- 回复批量装配：一页一级评论一次 IN 查询
CREATE INDEX idx_share_comment_parent ON t_share_comment (parent_comment_id);
-- 「某用户发过的评论」（举报处理页 / 后续「我的评论」）
CREATE INDEX idx_share_comment_user ON t_share_comment (user_id, created_at);

-- 评论点赞（与分享点赞同构：复合主键即唯一约束，并发重复插入撞键幂等）
CREATE TABLE IF NOT EXISTS t_share_comment_like (
    comment_id BIGINT    NOT NULL,
    user_id    BIGINT    NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (comment_id, user_id)
);

-- ---------------------------------------------------------------------------
-- 3. 举报
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_share_report (
    id          BIGINT       NOT NULL,
    -- SHARE | COMMENT：两种举报对象合成一张表 —— 处理流程完全一致，
    -- 拆两张表等于把同一套运营逻辑写两遍。
    target_type VARCHAR(16)  NOT NULL,
    target_id   BIGINT       NOT NULL,
    reporter_id BIGINT       NOT NULL,
    reason      VARCHAR(200) NOT NULL,
    -- PENDING | ACCEPTED（成立，内容已下架）| REJECTED（不成立）
    status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    handled_by  BIGINT       NULL,
    handled_at  TIMESTAMP    NULL,
    handle_note VARCHAR(200) NULL,
    created_at  TIMESTAMP    NOT NULL,
    PRIMARY KEY (id),
    -- 同一人对同一对象只能举报一次：唯一键兜住并发重复提交
    -- （应用层「先查有没有、没有就插」在并发下会双写，与 R54 去重表同一教训）
    UNIQUE KEY uk_share_report_once (target_type, target_id, reporter_id)
);
-- 运营列表：按状态筛选 + 时间倒序
CREATE INDEX idx_share_report_status ON t_share_report (status, created_at);
CREATE INDEX idx_share_report_target ON t_share_report (target_type, target_id);

-- ---------------------------------------------------------------------------
-- 4. 话题标签
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_share_topic (
    id          BIGINT      NOT NULL,
    -- 话题名（不含 # 号）。唯一键兜住「两人同时新建同名话题」的并发双写。
    name        VARCHAR(30) NOT NULL,
    share_count INT         NOT NULL DEFAULT 0,
    created_at  TIMESTAMP   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_share_topic_name (name)
);
-- 热门话题榜（领话题按分享数倒序取）
CREATE INDEX idx_share_topic_count ON t_share_topic (share_count);

-- 分享 ↔ 话题（一条分享最多 2 个话题；主键即去重键）
CREATE TABLE IF NOT EXISTS t_share_topic_rel (
    share_id BIGINT NOT NULL,
    topic_id BIGINT NOT NULL,
    PRIMARY KEY (share_id, topic_id)
);
-- 话题聚合页按话题查分享：主键左侧是 share_id，故补一条反向索引
CREATE INDEX idx_share_topic_rel_topic ON t_share_topic_rel (topic_id, share_id);
