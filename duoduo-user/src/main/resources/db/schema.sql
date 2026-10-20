-- 用户服务表结构（H2 / MySQL 8 双兼容）
-- 约定：
--   * 主键使用雪花 ID（BIGINT，不用自增，便于后续分库分表）
--   * 金额统一 BIGINT（单位：分）
--   * 时间统一 TIMESTAMP(UTC)

DROP TABLE IF EXISTS t_audit_log;
DROP TABLE IF EXISTS t_user_address;
DROP TABLE IF EXISTS t_pet_profile;
DROP TABLE IF EXISTS t_refresh_token;
DROP TABLE IF EXISTS t_user;

CREATE TABLE t_user (
    id              BIGINT       NOT NULL,
    phone           VARCHAR(20)  NOT NULL,
    password_hash   VARCHAR(100) NOT NULL,
    nickname        VARCHAR(50)  NOT NULL,
    avatar          VARCHAR(500),
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    role            VARCHAR(20)  NOT NULL DEFAULT 'USER',
    real_name       VARCHAR(50),
    id_card_masked  VARCHAR(30),
    -- 信用分（A4）：默认值 = 信用分模型的基础分（CreditModel 的 base，60）。
    -- 与「无数据用户得基础分」对齐 —— 新注册用户的库内值与实时计算的信用分必须一致，
    -- 否则会变成「资料页显示 100、信用分页显示 60」这种谁也不知道哪个对的分裂。
    credit_score    INT          NOT NULL DEFAULT 60,
    -- 微信 openid（A13）：绑定后写入；唯一索引兜底「同一微信并发绑到两个账号」（INV-1）。
    wx_openid       VARCHAR(64),
    -- 权限版本（E17）：角色变更时 +1，访问令牌带签发时的值，网关比对不一致即拒绝。
    -- 初始 0 与 PermVersion.INITIAL 对齐（避免"默认值是 0、起点却是 1"两套口径）。
    perm_version    BIGINT       NOT NULL DEFAULT 0,
    -- 账号注销状态机（C7）：与 status（封禁）正交。
    --   ACTIVE 正常 / CANCELLING 冷静期内（登录停用、可撤销）/ DELETED 已注销（PII 清空、行保留）
    account_status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    delete_requested_at TIMESTAMP,
    delete_scheduled_at TIMESTAMP,
    delete_reason       VARCHAR(200),
    delete_executed_at  TIMESTAMP,
    last_login_at   TIMESTAMP,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_phone UNIQUE (phone),
    CONSTRAINT uk_user_wxopenid UNIQUE (wx_openid)
);

CREATE TABLE t_refresh_token (
    id          VARCHAR(64) NOT NULL,
    user_id     BIGINT      NOT NULL,
    token_hash  VARCHAR(64) NOT NULL,
    expires_at  TIMESTAMP   NOT NULL,
    revoked     TINYINT     NOT NULL DEFAULT 0,
    user_agent  VARCHAR(300),
    created_at  TIMESTAMP   NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_refresh_user ON t_refresh_token (user_id);

CREATE TABLE t_pet_profile (
    id               BIGINT       NOT NULL,
    user_id          BIGINT       NOT NULL,
    name             VARCHAR(50)  NOT NULL,
    species          VARCHAR(20)  NOT NULL,
    breed            VARCHAR(50),
    birthday         DATE,
    life_stage       VARCHAR(20),
    weight_kg        DECIMAL(6,2),
    gender           VARCHAR(10),
    neutered         TINYINT      NOT NULL DEFAULT 0,
    allergens        VARCHAR(500) NOT NULL DEFAULT '',
    medical_history  VARCHAR(1000),
    is_default       TINYINT      NOT NULL DEFAULT 0,
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_pet_user ON t_pet_profile (user_id);

-- 收货地址簿（A8）。一个账号可存多个地址，其中最多一个「默认地址」。
-- 省 / 市 / 区 拆开存、明细单独一列：订单端最终要的是**拼好的一段文本**，
-- 但地址簿里拆开存才能支持「按城市算运费」「按省做筛选」这类后续能力。
-- 拼接口径由 AddressService.fullAddress() 一处维护，避免每个调用方各拼一遍。
CREATE TABLE t_user_address (
    id              BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL,
    receiver_name   VARCHAR(50)  NOT NULL,
    receiver_phone  VARCHAR(20)  NOT NULL,
    province        VARCHAR(30)  NOT NULL,
    city            VARCHAR(30)  NOT NULL,
    district        VARCHAR(30)  NOT NULL,
    detail          VARCHAR(200) NOT NULL,
    label           VARCHAR(20),
    is_default      TINYINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_address_user ON t_user_address (user_id);

-- 后台操作审计日志（A7 建表 / E18 扩列）：只追加、不更新、不删除。
-- 与 01-duoduo-user.sql / incremental/09 + 31 同源，口径一致。
CREATE TABLE t_audit_log (
    id           BIGINT       NOT NULL,
    actor_id     BIGINT       NOT NULL,
    actor_phone  VARCHAR(20)  NOT NULL,
    action       VARCHAR(50)  NOT NULL,
    category     VARCHAR(20)  NOT NULL DEFAULT 'OTHER',
    outcome      VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS',
    target_id    BIGINT       NOT NULL,
    target_type  VARCHAR(32)  NOT NULL DEFAULT '',
    tenant_id    BIGINT       NOT NULL DEFAULT 0,
    before_value VARCHAR(500)          DEFAULT NULL,
    after_value  VARCHAR(500)          DEFAULT NULL,
    ip           VARCHAR(64)           DEFAULT NULL,
    request_id   VARCHAR(64)           DEFAULT NULL,
    detail       VARCHAR(500) NOT NULL,
    created_at   TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_audit_actor ON t_audit_log (actor_id);
CREATE INDEX idx_audit_target ON t_audit_log (target_id);
CREATE INDEX idx_audit_created ON t_audit_log (created_at);
CREATE INDEX idx_audit_actor_created ON t_audit_log (actor_id, created_at);
CREATE INDEX idx_audit_tenant_created ON t_audit_log (tenant_id, created_at);
CREATE INDEX idx_audit_category_created ON t_audit_log (category, created_at);
