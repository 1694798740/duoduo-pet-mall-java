-- 用户服务表结构（H2 / MySQL 8 双兼容）
-- 约定：
--   * 主键使用雪花 ID（BIGINT，不用自增，便于后续分库分表）
--   * 金额统一 BIGINT（单位：分）
--   * 时间统一 TIMESTAMP(UTC)

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
    last_login_at   TIMESTAMP,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_phone UNIQUE (phone)
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
