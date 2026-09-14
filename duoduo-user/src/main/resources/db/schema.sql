-- 用户服务表结构（H2 / MySQL 8 双兼容）
-- 约定：
--   * 主键使用雪花 ID（BIGINT，不用自增，便于后续分库分表）
--   * 金额统一 BIGINT（单位：分）
--   * 时间统一 TIMESTAMP(UTC)

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
    credit_score    INT          NOT NULL DEFAULT 100,
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
