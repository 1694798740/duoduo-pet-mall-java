-- 商品域表结构（H2 / MySQL 8 双兼容）
-- 约定：
--   * 主键使用雪花 ID（BIGINT），不用自增，便于后续分库分表
--   * 金额统一 BIGINT（单位：分）；价格区间冗余在 SPU 上，避免列表页 JOIN SKU
--   * 时间统一 TIMESTAMP（UTC）
--   * 多值属性（适用物种 / 生命阶段 / 过敏原）用逗号分隔字符串存储，
--     因为这些字段只在「筛选」与「适配度展示」时读取，且取值集合很小；
--     生产若改为 ES 检索，这些字段会在索引中展开成 keyword 数组

DROP TABLE IF EXISTS t_listing;
DROP TABLE IF EXISTS t_sku;
DROP TABLE IF EXISTS t_spu_image;
DROP TABLE IF EXISTS t_spu;
DROP TABLE IF EXISTS t_brand;
DROP TABLE IF EXISTS t_category_attr;
DROP TABLE IF EXISTS t_category;

-- ---------- 类目：两级树（parent_id=0 为一级） ----------
CREATE TABLE t_category (
    id          BIGINT       NOT NULL,
    parent_id   BIGINT       NOT NULL DEFAULT 0,
    name        VARCHAR(50)  NOT NULL,
    depth       INT          NOT NULL DEFAULT 1,
    sort_no     INT          NOT NULL DEFAULT 0,
    icon        VARCHAR(200),
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_category_parent ON t_category (parent_id);

-- ---------- 类目属性模板（A-10） ----------
-- 只定义「这个类目要求填哪些属性、什么类型、可选值」，属性值仍存在 t_spu.spec_params（KV）；
-- 不建值表：历史自由 KV 需继续可读可编辑，模板只做发布/编辑时的校验闸门。
CREATE TABLE t_category_attr (
    id          BIGINT       NOT NULL,
    category_id BIGINT       NOT NULL,
    name        VARCHAR(30)  NOT NULL,
    input_type  VARCHAR(16)  NOT NULL DEFAULT 'TEXT',
    is_required TINYINT      NOT NULL DEFAULT 0,
    options     VARCHAR(1000),
    sort_no     INT          NOT NULL DEFAULT 0,
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_category_attr_name UNIQUE (category_id, name)
);
CREATE INDEX idx_category_attr ON t_category_attr (category_id, status, sort_no);

-- ---------- 品牌 ----------
CREATE TABLE t_brand (
    id          BIGINT       NOT NULL,
    name        VARCHAR(50)  NOT NULL,
    logo        VARCHAR(300),
    country     VARCHAR(30),
    story       VARCHAR(1000),
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);

-- ---------- SPU：标准化商品单元 ----------
CREATE TABLE t_spu (
    id           BIGINT       NOT NULL,
    title        VARCHAR(200) NOT NULL,
    subtitle     VARCHAR(200),
    category_id  BIGINT       NOT NULL,
    brand_id     BIGINT,
    -- self=自营 / merchant=第三方商家；C2C 闲置走 t_listing，不在此表
    supply_type  VARCHAR(20)  NOT NULL DEFAULT 'self',
    main_image   VARCHAR(300),
    images       VARCHAR(2000),
    detail       VARCHAR(4000),
    status       VARCHAR(20)  NOT NULL DEFAULT 'ON_SALE',
    sales_count  INT          NOT NULL DEFAULT 0,
    rating       DECIMAL(3,2) NOT NULL DEFAULT 5.00,
    rating_count INT          NOT NULL DEFAULT 0,
    -- 价格区间冗余：列表页与价格排序只读 SPU，不 JOIN SKU（避免 N+1）
    min_price    BIGINT       NOT NULL DEFAULT 0,
    max_price    BIGINT       NOT NULL DEFAULT 0,
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL,
    -- 卖家归属（t_user.id；0 = 平台自营）。第三方商家接入后由入驻流程写入
    seller_id    BIGINT       NOT NULL DEFAULT 0,
    version      INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);
CREATE INDEX idx_spu_category ON t_spu (category_id, status);
CREATE INDEX idx_spu_brand ON t_spu (brand_id);
CREATE INDEX idx_spu_sales ON t_spu (sales_count);
CREATE INDEX idx_spu_price ON t_spu (min_price);
CREATE INDEX idx_spu_seller ON t_spu (seller_id, status);

-- ---------- 商品图片（A-11） ----------
-- 一条图片一行：类型 / 排序 / 审核态。与 t_spu.main_image、images(CSV) 双写；
-- 读路径仍读 CSV，表是排序与单图审核的权威来源。
CREATE TABLE t_spu_image (
    id           BIGINT       NOT NULL,
    spu_id       BIGINT       NOT NULL,
    url          VARCHAR(500) NOT NULL,
    image_type   VARCHAR(20)  NOT NULL,
    sort_no      INT          NOT NULL DEFAULT 0,
    audit_status VARCHAR(20)  NOT NULL DEFAULT 'APPROVED',
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_spu_image_slot ON t_spu_image (spu_id, image_type, sort_no);

-- ---------- SKU：可售最小单元（库存与价格落在这一层） ----------
CREATE TABLE t_sku (
    id                BIGINT       NOT NULL,
    spu_id            BIGINT       NOT NULL,
    sku_code          VARCHAR(60)  NOT NULL,
    spec_json         VARCHAR(500),
    image             VARCHAR(300),
    price             BIGINT       NOT NULL,
    market_price      BIGINT       NOT NULL DEFAULT 0,
    -- stock 为可售库存，frozen_stock 为下单未支付的冻结库存；两者由交易服务维护
    stock             INT          NOT NULL DEFAULT 0,
    frozen_stock      INT          NOT NULL DEFAULT 0,
    is_food           TINYINT      NOT NULL DEFAULT 0,
    target_species    VARCHAR(60),
    target_life_stage VARCHAR(60),
    allergens         VARCHAR(300),
    status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at        TIMESTAMP    NOT NULL,
    updated_at        TIMESTAMP    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_sku_code UNIQUE (sku_code)
);
CREATE INDEX idx_sku_spu ON t_sku (spu_id);

-- ---------- 订单级库存预占台账（B-03） ----------
-- 释放 / 扣减按 (order_id, sku_id) 定位本订单的冻结，不再只校验 SKU 上的全局冻结量。
CREATE TABLE t_sku_stock_reservation (
    order_id   BIGINT      NOT NULL,
    sku_id     BIGINT      NOT NULL,
    quantity   INT         NOT NULL,
    status     VARCHAR(16) NOT NULL DEFAULT 'FROZEN',
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (order_id, sku_id)
);
CREATE INDEX idx_reservation_sku_status ON t_sku_stock_reservation (sku_id, status);

-- ---------- C2C 闲置 ----------
CREATE TABLE t_listing (
    id               BIGINT       NOT NULL,
    seller_id        BIGINT       NOT NULL,
    title            VARCHAR(200) NOT NULL,
    description      VARCHAR(2000),
    category_id      BIGINT,
    price            BIGINT       NOT NULL,
    original_price   BIGINT       NOT NULL DEFAULT 0,
    -- NEW / LIKE_NEW / GOOD / FAIR
    condition_level  VARCHAR(20)  NOT NULL DEFAULT 'GOOD',
    city             VARCHAR(50),
    images           VARCHAR(2000),
    species          VARCHAR(20),
    breed            VARCHAR(50),
    status           VARCHAR(20)  NOT NULL DEFAULT 'ON_SALE',
    -- PENDING / APPROVED / REJECTED —— 发布需人工审核，审核通过前不对外展示
    audit_status     VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    -- 审核结论要可追溯：驳回原因 + 审核时间，不能只留一个状态码
    audit_reason     VARCHAR(200),
    audit_at         TIMESTAMP,
    view_count       INT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_listing_seller ON t_listing (seller_id);
CREATE INDEX idx_listing_status ON t_listing (status, audit_status);
CREATE INDEX idx_listing_price ON t_listing (price);
