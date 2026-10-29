-- 70 · 多多币 P3 第一轮：站内任务 + 节日活动（R-P1 第三轮）
--
-- 设计要点（与 63 / 64 同一套口径）：
--   * **任务进度一天一行**：`uk_coin_task_progress (user_id, task_code, biz_date)` 唯一索引 ——
--     进度与「是否已发奖」都落在这一行上，`rewarded=0 → 1` 用条件 UPDATE 推进，
--     并发只可能成功一次，因此「同一任务每天只发一次」是索引级事实，不靠应用层先查后写。
--   * **任务事件去重**：`uk_coin_task_event (user_id, task_code, biz_date, biz_id)` ——
--     同一商品 / 同一内容每天只算一次（biz_id = spuId / shareId / orderNo），INSERT IGNORE 撞键即跳过。
--   * **新人任务是「永久一次」**：biz_date 固定为 1970-01-01（哨兵日），
--     与日常任务的「今天」天然分开；重复触发同样被唯一键挡住。
--   * **活动预算必须原子扣减**：`budget_used + extra <= budget_total` 写进 UPDATE 的 WHERE，
--     预算不足时退回基础值（不报错、不超发）。budget_total / per_user_cap = 0 表示不限。
--   * 币是钱：任务奖励与活动加成全部走 t_coin_ledger（biz_type=COIN_TASK），流水唯一键继续兜底幂等。
--
-- 已有环境手工执行：
--   docker exec -i duoduo-mysql mysql -uroot -pduoduo_root duoduo_user \
--     < docs/sql/incremental/70-duoduo-coins-tasks.sql
SET NAMES utf8mb4;
USE `duoduo_user`;

-- ---------- 1. 任务定义 ----------
CREATE TABLE IF NOT EXISTS t_coin_task_def (
    id           BIGINT       NOT NULL COMMENT '雪花主键',
    code         VARCHAR(40)  NOT NULL COMMENT '任务编码（对外稳定标识）',
    name         VARCHAR(64)  NOT NULL COMMENT '任务名称（流水备注用）',
    task_type    VARCHAR(16)  NOT NULL COMMENT 'DAILY/NEWCOMER',
    action_type  VARCHAR(32)  NOT NULL COMMENT '动作类型：浏览商品 / 收藏 / 加购 / 发布 / 评价 / 首单…',
    target_count INT          NOT NULL DEFAULT 1 COMMENT '达成目标所需次数',
    reward_coins INT          NOT NULL COMMENT '达成后一次发放的币数',
    daily_cap    INT          NOT NULL DEFAULT 0 COMMENT '进度上限（0 表示按 target_count）',
    enabled      TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用：1=启用',
    sort_no      INT          NOT NULL DEFAULT 0 COMMENT '排序号（小的在前）',
    description  VARCHAR(255) NULL COMMENT '任务说明（展示给用户）',
    created_at   DATETIME     NOT NULL COMMENT '创建时间（UTC）',
    updated_at   DATETIME     NOT NULL COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_coin_task_def_code (code),
    KEY idx_coin_task_def_sort (enabled, sort_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '多多币任务定义';

-- ---------- 2. 任务进度（一人一任务一日一行） ----------
CREATE TABLE IF NOT EXISTS t_coin_task_progress (
    id           BIGINT      NOT NULL COMMENT '雪花主键',
    user_id      BIGINT      NOT NULL COMMENT '用户 ID',
    task_code    VARCHAR(40) NOT NULL COMMENT '任务编码',
    biz_date     DATE        NOT NULL COMMENT '业务日（Asia/Shanghai；新人任务固定 1970-01-01）',
    progress     INT         NOT NULL DEFAULT 0 COMMENT '已完成次数',
    rewarded     TINYINT     NOT NULL DEFAULT 0 COMMENT '是否已发奖：0=未发 1=已发（条件更新只成功一次）',
    reward_coins INT         NOT NULL DEFAULT 0 COMMENT '实际发放币数（含活动加成时为最终值）',
    created_at   DATETIME    NOT NULL COMMENT '创建时间（UTC）',
    updated_at   DATETIME    NOT NULL COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_coin_task_progress (user_id, task_code, biz_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '多多币任务进度';

-- ---------- 3. 任务事件（同日同业务对象只算一次） ----------
CREATE TABLE IF NOT EXISTS t_coin_task_event (
    id         BIGINT      NOT NULL COMMENT '雪花主键',
    user_id    BIGINT      NOT NULL COMMENT '用户 ID',
    task_code  VARCHAR(40) NOT NULL COMMENT '任务编码',
    biz_date   DATE        NOT NULL COMMENT '业务日',
    biz_id     VARCHAR(64) NOT NULL COMMENT '业务对象（spuId / shareId / orderNo / reviewId）',
    created_at DATETIME    NOT NULL COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_coin_task_event (user_id, task_code, biz_date, biz_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '多多币任务事件（防重复计数）';

-- ---------- 4. 节日活动 ----------
CREATE TABLE IF NOT EXISTS t_coin_campaign (
    id                BIGINT        NOT NULL COMMENT '雪花主键',
    code              VARCHAR(40)   NOT NULL COMMENT '活动编码',
    name              VARCHAR(64)   NOT NULL COMMENT '活动名称',
    start_at          DATETIME      NOT NULL COMMENT '开始时间（UTC）',
    end_at            DATETIME      NOT NULL COMMENT '结束时间（UTC）',
    signin_multiplier DECIMAL(4, 2) NOT NULL DEFAULT 1.00 COMMENT '签到倍率（1.00 = 不加成）',
    earn_multiplier   DECIMAL(4, 2) NOT NULL DEFAULT 1.00 COMMENT '购物返币倍率（1.00 = 不加成）',
    budget_total      BIGINT        NOT NULL DEFAULT 0 COMMENT '活动总预算（币；0 = 不限）',
    budget_used       BIGINT        NOT NULL DEFAULT 0 COMMENT '已用预算（币；条件 UPDATE 原子累加）',
    per_user_cap      BIGINT        NOT NULL DEFAULT 0 COMMENT '每人加成上限（币；0 = 不限）',
    status            VARCHAR(16)   NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED / DISABLED',
    created_at        DATETIME      NOT NULL COMMENT '创建时间（UTC）',
    updated_at        DATETIME      NOT NULL COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_coin_campaign_code (code),
    KEY idx_coin_campaign_active (status, start_at, end_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '多多币节日活动';

-- ---------- 5. 活动每人累计（每人上限的事实来源） ----------
CREATE TABLE IF NOT EXISTS t_coin_campaign_user (
    id          BIGINT   NOT NULL COMMENT '雪花主键',
    campaign_id BIGINT   NOT NULL COMMENT '活动 ID',
    user_id     BIGINT   NOT NULL COMMENT '用户 ID',
    awarded     BIGINT   NOT NULL DEFAULT 0 COMMENT '该用户在本活动已获加成（币）',
    created_at  DATETIME NOT NULL COMMENT '创建时间（UTC）',
    updated_at  DATETIME NOT NULL COMMENT '更新时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_coin_campaign_user (campaign_id, user_id),
    KEY idx_coin_campaign_user_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '多多币活动每人累计';

-- ---------- 6. 种子任务（9 条，enabled=1） ----------
-- 浏览类 +2/个（target 3 → 6 币）；收藏 / 加购 +5；晒单 +20；评价 +10；新人一次性 20 / 50 / 20。
INSERT IGNORE INTO t_coin_task_def
    (id, code, name, task_type, action_type, target_count, reward_coins, daily_cap,
     enabled, sort_no, description, created_at, updated_at)
VALUES
    (730000000000000001, 'BROWSE_PRODUCT', '浏览商品', 'DAILY', 'BROWSE_PRODUCT', 3, 6, 6, 1, 10,
     '浏览 3 个商品详情，每个停留 ≥15 秒', NOW(), NOW()),
    (730000000000000002, 'BROWSE_SHARE', '逛分享圈', 'DAILY', 'BROWSE_SHARE', 3, 6, 6, 1, 20,
     '逛 3 篇分享圈内容，每篇停留 ≥10 秒', NOW(), NOW()),
    (730000000000000003, 'FAVORITE', '收藏商品', 'DAILY', 'FAVORITE', 1, 5, 5, 1, 30,
     '收藏 1 件商品', NOW(), NOW()),
    (730000000000000004, 'CART_ADD', '加入购物车', 'DAILY', 'CART_ADD', 1, 5, 5, 1, 40,
     '把 1 件商品加入购物车', NOW(), NOW()),
    (730000000000000005, 'SHARE_PUBLISH', '发布晒单/分享', 'DAILY', 'SHARE_PUBLISH', 1, 20, 20, 1, 50,
     '发布 1 条晒单 / 分享', NOW(), NOW()),
    (730000000000000006, 'REVIEW_APPROVED', '评价过审', 'DAILY', 'REVIEW_APPROVED', 1, 10, 10, 1, 60,
     '提交 1 条评价并通过审核', NOW(), NOW()),
    (730000000000000007, 'NEWCOMER_PROFILE', '完善资料', 'NEWCOMER', 'PROFILE_COMPLETE', 1, 20, 20, 1, 70,
     '完善个人资料（新人一次性）', NOW(), NOW()),
    (730000000000000008, 'NEWCOMER_FIRST_ORDER', '首单', 'NEWCOMER', 'FIRST_ORDER', 1, 50, 50, 1, 80,
     '完成首笔订单（新人一次性）', NOW(), NOW()),
    (730000000000000009, 'NEWCOMER_FIRST_REVIEW', '首评', 'NEWCOMER', 'FIRST_REVIEW', 1, 20, 20, 1, 90,
     '完成首次评价（新人一次性）', NOW(), NOW());
