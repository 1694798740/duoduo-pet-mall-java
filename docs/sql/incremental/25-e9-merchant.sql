-- E9 · 租户/店铺与成员实体（P2 第一项）
--
-- 设计要点：
--   * 表落在 duoduo_user 库：入驻申请、店主与成员都是"账号域"的事实，
--     与 user 服务现有的 applyMerchant / 角色升级同处一个事务边界。
--   * 本表只管**第三方商家**。平台自营保持既有语义（seller_id = 0，见
--     duoduo-common 的 SellerOwnership）—— 自营不是入驻来的，塞进本表
--     反而要给"唯一店主 / 邀请成员 / 审核状态机"这些第三方专属约束开例外。
--   * isolation_level 预留（P3 租户隔离硬化用）：当前所有第三方商家同一档
--     STANDARD；将来出现需要更强隔离的大客户时无需改表。
--   * 状态机字段在此建好（DRAFT→SUBMITTED→REVIEWING→APPROVED/REJECTED→SUSPENDED），
--     状态流转与校验在 E10（入驻申请）实现，本表只承载数据形状。

CREATE TABLE IF NOT EXISTS duoduo_user.t_merchant (
    id              BIGINT       NOT NULL COMMENT '雪花 id（按字符串流转，前端禁止 Number()）',
    name            VARCHAR(64)  NOT NULL COMMENT '店铺名（入驻时填写，审核通过即开店名）',
    owner_user_id   BIGINT       NOT NULL COMMENT '店主用户 id（t_user.id）',
    status          VARCHAR(20)  NOT NULL DEFAULT 'DRAFT'
                    COMMENT 'DRAFT/SUBMITTED/REVIEWING/APPROVED/REJECTED/SUSPENDED（状态机在 E10）',
    isolation_level VARCHAR(20)  NOT NULL DEFAULT 'STANDARD' COMMENT '租户隔离档位（P3 预留，当前恒 STANDARD）',
    reject_reason   VARCHAR(255) NULL COMMENT '最近一次驳回原因（结构化，E10）',
    applied_at      DATETIME     NULL COMMENT '最近一次提交时间',
    reviewed_at     DATETIME     NULL COMMENT '最近一次审核时间',
    reviewed_by     BIGINT       NULL COMMENT '最近一次审核人（运营 user id）',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_owner (owner_user_id),
    UNIQUE KEY uk_name (name),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '第三方商家租户（E9）';

-- 成员表（E13 子账号体系的数据基础，本表先建）：一个用户至多属于一个商家（v1 口径，
-- 跨店兼职是将来有真实需求再放宽的事，唯一键先把口子收住）。
CREATE TABLE IF NOT EXISTS duoduo_user.t_merchant_member (
    id          BIGINT      NOT NULL COMMENT '雪花 id',
    merchant_id BIGINT      NOT NULL COMMENT '所属商家（t_merchant.id）',
    user_id     BIGINT      NOT NULL COMMENT '成员用户 id（t_user.id）',
    role        VARCHAR(20) NOT NULL COMMENT 'OWNER（店主）/ STAFF（店员，E13）',
    status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED（E13 停用）',
    invited_by  BIGINT      NULL COMMENT '邀请人（店主 user id；店主自建为 NULL）',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user (user_id),
    KEY idx_merchant (merchant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商家成员（E9 建表，E13 子账号使用）';
