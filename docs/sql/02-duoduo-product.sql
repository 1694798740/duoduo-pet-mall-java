-- ============================================================================
-- 多多宠物商店 · 商品库：建表 + 种子数据（容器化初始化用）
-- ============================================================================
-- 本文件由以下两处拼装而成，内容保持一致：
--   duoduo-product/src/main/resources/db/schema.sql
--   duoduo-product/src/main/resources/db/data.sql
-- 改动表结构时请改上述源文件并重新生成本文件，避免两处漂移。
-- ============================================================================

-- 注意：必须显式声明客户端字符集，否则中文会被「双重编码」写入
-- （客户端按 latin1 发送 UTF-8 字节 → 库里存成乱码 → 中文关键词检索永远 0 命中）。
SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `duoduo_product`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `duoduo_product`;
-- 商品域表结构（H2 / MySQL 8 双兼容）
-- 约定：
--   * 主键使用雪花 ID（BIGINT），不用自增，便于后续分库分表
--   * 金额统一 BIGINT（单位：分）；价格区间冗余在 SPU 上，避免列表页 JOIN SKU
--   * 时间统一 TIMESTAMP（UTC）
--   * 多值属性（适用物种 / 生命阶段 / 过敏原）用逗号分隔字符串存储，
--     因为这些字段只在「筛选」与「适配度展示」时读取，且取值集合很小；
--     生产若改为 ES 检索，这些字段会在索引中展开成 keyword 数组

DROP TABLE IF EXISTS t_listing_want;
DROP TABLE IF EXISTS t_listing;
DROP TABLE IF EXISTS t_sku;
DROP TABLE IF EXISTS t_spu;
DROP TABLE IF EXISTS t_brand;
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
    -- E14 · 审核维度（与 status 正交，口径同 t_listing）：
    --   交易维度 status 归卖家（在售 / 下架），审核维度 audit_status 归平台（合规判定）。
    --   默认 APPROVED 的理由见 incremental/27-e14-spu-audit.sql 的注释：
    --   存量商品在售、自营商品免审，只有第三方商家新建/重大变更才显式落 PENDING。
    audit_status VARCHAR(20)  NOT NULL DEFAULT 'APPROVED',
    audit_reason VARCHAR(200),
    audit_at     DATETIME,
    audit_by     BIGINT,
    -- E28 · 审核来源：AUTO(按规则自动放行) / MANUAL(运营人工) / SELF(自营免审)。
    --   NULL = E28 之前的历史数据，刻意不回填 —— 回填会凭空制造「这批人工审过」的假事实。
    audit_source VARCHAR(10),
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
    PRIMARY KEY (id)
);
CREATE INDEX idx_spu_category ON t_spu (category_id, status);
CREATE INDEX idx_spu_brand ON t_spu (brand_id);
CREATE INDEX idx_spu_sales ON t_spu (sales_count);
CREATE INDEX idx_spu_price ON t_spu (min_price);
CREATE INDEX idx_spu_seller ON t_spu (seller_id, status);
-- E14：审核台按「审核状态 + 交易状态」筛（增量脚本 27-e14-spu-audit.sql）
CREATE INDEX idx_spu_audit ON t_spu (audit_status, status);

-- ---------- E28 · 审核 SLA 事后抽查（增量脚本 29-e28-audit-sla.sql） ----------
-- 为什么抽查任务是独立表而不是 t_spu 上再加几列：
--   抽查按天批量产生（同一商品可被不同日期各抽中一次），加列只能记「最近一次」；
--   且复核结论与审核结论是两件事（抽查判通过不改 audit_status）。
-- ★ uk_biz_date 是幂等的来源：同一天同一对象最多一条，重跑/多实例/手动触发都不会重复建。
CREATE TABLE t_audit_sample (
    id          BIGINT       NOT NULL,
    biz_type    VARCHAR(20)  NOT NULL COMMENT 'SPU(商品) / MERCHANT(入驻申请)',
    biz_id      BIGINT       NOT NULL COMMENT '对象 id（t_spu.id / t_merchant.id）',
    seller_id   BIGINT       NOT NULL DEFAULT 0 COMMENT '归属店铺主体 user id；0=平台自营',
    sample_date DATE         NOT NULL COMMENT '抽样日期（UTC），与 biz_id 组成幂等键',
    reason      VARCHAR(32)  NOT NULL COMMENT 'BASE/TINY_ALL/NEW_SELLER/RESUME/ESCALATED',
    status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PASSED/REJECTED',
    review_note VARCHAR(200),
    reviewed_by BIGINT,
    reviewed_at DATETIME,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_date (biz_type, biz_id, sample_date),
    KEY idx_status_created (status, created_at),
    KEY idx_seller_date (seller_id, sample_date)
);

-- 抽样强度（单行，id 固定 1）：附录 D 第五节「连续 3 天违规率超标 → 比例翻倍」要有个
-- 能被**自动改写**的落点；写进配置文件就变成「改配置再重启」，那就不叫自动收紧。
CREATE TABLE t_audit_sample_rate (
    id                  BIGINT   NOT NULL COMMENT '固定 1（单行状态表）',
    sample_rate_percent INT      NOT NULL DEFAULT 5,
    tightened_at        DATETIME,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);
INSERT INTO t_audit_sample_rate (id, sample_rate_percent) VALUES (1, 5);

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

-- ---------- 退货入库幂等去重表（第五十八轮 · B-02） ----------
-- restock 没有像 freeze/release 那样的天然幂等护栏：重复调用会真的多加库存且不报错。
-- 现实中最易撞上「调用已生效、响应丢失」→ 交易侧判失败 → 补偿调度再调一次 → 库存凭空多一份。
-- 去重键即主键 (ref_no, sku_id)，判据是 INSERT IGNORE 是否真的插入（affected=1），
-- 幂等性落在数据库唯一键上，不依赖应用层「先查有没有」（并发下会双写）。
CREATE TABLE t_sku_restock_log (
    ref_no      VARCHAR(64) NOT NULL                COMMENT '入库请求幂等号（交易侧给出，如 restock:{orderId}）',
    sku_id      BIGINT      NOT NULL                COMMENT 'SKU id',
    quantity    INT         NOT NULL DEFAULT 0      COMMENT '本次实际加回的数量（审计用）',
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    PRIMARY KEY (ref_no, sku_id)
);
CREATE INDEX idx_restock_log_sku ON t_sku_restock_log (sku_id);

-- ---------- 订单级库存预占台账（B-03） ----------
-- 释放 / 扣减原先只校验 t_sku.frozen_stock 这个全局数量，同一 SKU 被多单冻结时，
-- 释放 A 单会把 B 单的冻结量也放掉。本表用 (order_id, sku_id) 主键把冻结归属到订单：
-- 主键即幂等键，重复冻结 INSERT IGNORE 去重；释放 / 扣减只操作本订单那一行。
CREATE TABLE t_sku_stock_reservation (
    order_id   BIGINT      NOT NULL                COMMENT '订单 id（交易域雪花 id，仅作外部标识，不建外键）',
    sku_id     BIGINT      NOT NULL                COMMENT 'SKU id',
    quantity   INT         NOT NULL                COMMENT '本订单预占数量',
    status     VARCHAR(16) NOT NULL DEFAULT 'FROZEN' COMMENT 'FROZEN / RELEASED / DEDUCTED',
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    updated_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间（UTC）',
    PRIMARY KEY (order_id, sku_id),
    KEY idx_reservation_sku_status (sku_id, status)
);

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
    -- 浏览次数：页面每被打开或刷新一次 +1（同一人可累加）
    -- 软删时间（第五十八轮 · B-14）：非 NULL 即已删除。**必须软删不能物理删** ——
    -- 闲置成交后会与订单、会话、举报产生引用，物理删会让别人的页面出现「查无此物」；
    -- 审核与举报留痕也需要「这条内容曾经存在」。所有读路径一律带 deleted_at IS NULL。
    deleted_at       TIMESTAMP    NULL,
    view_count       INT          NOT NULL DEFAULT 0,
    -- 想要人数：按咨询行为去重统计（同一买家对同一闲置只计 1 次）；与 view_count 是两个口径
    want_count       INT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_listing_seller ON t_listing (seller_id);
CREATE INDEX idx_listing_status ON t_listing (status, audit_status);
CREATE INDEX idx_listing_price ON t_listing (price);

-- 闲置「想要」去重表：主键 (listing_id, buyer_id) 就是「一人一次」的硬约束。
-- 上报走 INSERT IGNORE，只有真插进去才给 t_listing.want_count 加 1 —— 幂等由库保证。
CREATE TABLE t_listing_want (
    listing_id BIGINT    NOT NULL,
    buyer_id   BIGINT    NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (listing_id, buyer_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;


-- ---------- 代运营工单（E23 · 商品域） ----------
-- 平台代第三方店铺下架商品的**授权凭证**：没有工单的强制下架 = 越权。
-- 与交易库（03-duoduo-trade.sql）那张同名同构，列定义刻意逐字一致 ——
-- 将来若要合并成一处，迁移就是一次纯搬运，而不是先对齐两套字段语义的重构。
-- 放在商品库而不是 duoduo_user：核验工单与执行下架必须在**同一个本地事务**内完成，
-- 跨服务的「核验通过 / 动作执行」之间存在中间态，不是能用补偿糊过去的东西。
CREATE TABLE t_proxy_work_order (
    id                BIGINT       NOT NULL COMMENT '雪花 id',
    work_order_no     VARCHAR(48)  NOT NULL COMMENT '工单号（WO-PRD- 前缀）',
    tenant_id         BIGINT       NOT NULL COMMENT '被代运营的店铺主体 userId（= owner_user_id）',
    target_type       VARCHAR(16)  NOT NULL COMMENT 'ORDER / PRODUCT（本库只允许 PRODUCT）',
    target_ref        VARCHAR(64)  NOT NULL COMMENT '目标对象：PRODUCT 存 spuId 字符串',
    action_type       VARCHAR(32)  NOT NULL COMMENT 'SHIP / AFTERSALE / OFF_SHELF（本库只允许 OFF_SHELF）',
    reason            VARCHAR(255) NOT NULL COMMENT '事由（申请人必填）',
    requester_user_id BIGINT       NOT NULL COMMENT '申请人 userId —— 必须是该店铺店主本人',
    status            VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN / CONSUMED / VOID',
    consumed_at       TIMESTAMP    NULL COMMENT '执行时刻（UTC）',
    consumed_by       BIGINT       NULL COMMENT '执行人 userId（运营 / 管理员）',
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（UTC）',
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_proxy_wo_no ON t_proxy_work_order (work_order_no);
CREATE INDEX idx_proxy_wo_target ON t_proxy_work_order (target_type, target_ref, status);
CREATE INDEX idx_proxy_wo_tenant ON t_proxy_work_order (tenant_id, status);

-- ============================================================
-- 商品域种子数据（local profile 每次启动重建）
-- 说明：
--   * 时间写死为固定值，保证多次启动结果一致，便于断言
--   * ID 使用小整数，便于人工核对；生产由雪花算法生成
--   * 刻意包含「无货商品」与「待审核 / 已驳回闲置」，用于验证可见性规则
-- ============================================================

-- ---------- 类目（两级） ----------
INSERT INTO t_category (id, parent_id, name, depth, sort_no, icon, status, created_at, updated_at) VALUES
(1,   0, '宠物主粮',    1, 1, 'bowl',    'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(2,   0, '宠物零食',    1, 2, 'bone',    'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(3,   0, '宠物用品',    1, 3, 'box',     'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(4,   0, '健康医疗',    1, 4, 'medical', 'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(5,   0, '智能设备',    1, 5, 'chip',    'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(6,   0, '活体与领养',  1, 6, 'heart',   'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(101, 1, '猫主粮',      2, 1, NULL, 'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(102, 1, '犬主粮',      2, 2, NULL, 'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(103, 1, '冻干与湿粮',  2, 3, NULL, 'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(201, 2, '猫零食',      2, 1, NULL, 'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(202, 2, '犬零食',      2, 2, NULL, 'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(203, 2, '训练奖励',    2, 3, NULL, 'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(301, 3, '猫砂与猫厕',  2, 1, NULL, 'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(302, 3, '食具水具',    2, 2, NULL, 'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(303, 3, '出行牵引',    2, 3, NULL, 'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(304, 3, '窝垫与猫爬架',2, 4, NULL, 'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(401, 4, '体内外驱虫',  2, 1, NULL, 'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(402, 4, '营养保健',    2, 2, NULL, 'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(403, 4, '清洁护理',    2, 3, NULL, 'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(501, 5, '智能饮水机',  2, 1, NULL, 'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(502, 5, '自动喂食器',  2, 2, NULL, 'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(503, 5, '智能猫砂盆',  2, 3, NULL, 'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(601, 6, '猫咪领养',    2, 1, NULL, 'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(602, 6, '犬只领养',    2, 2, NULL, 'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00');

-- ---------- 品牌 ----------
INSERT INTO t_brand (id, name, logo, country, story, status, created_at, updated_at) VALUES
(1, '麦富迪',   '/img/brand/1.svg', '中国', '国产全价粮代表，自有工厂，主打高性价比与可追溯原料。', 'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(2, '皇家',     '/img/brand/2.svg', '法国', '兽医渠道起家，按品种与年龄段细分配方的处方级营养方案。', 'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(3, '渴望',     '/img/brand/3.svg', '加拿大', '高肉含量、低温慢煮工艺，主打无谷高蛋白配方。', 'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(4, '网易严选', '/img/brand/4.svg', '中国', 'ODM 直连工厂，弱化品牌溢价，主打透明供应链。', 'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(5, '小佩',     '/img/brand/5.svg', '中国', '智能硬件品牌，饮水机、喂食器、猫砂盆全屋联动。', 'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(6, '霍曼',     '/img/brand/6.svg', '中国', '宠物智能家电，主打静音与易拆洗结构。', 'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(7, '卫仕',     '/img/brand/7.svg', '中国', '宠物营养保健，覆盖化毛、关节、皮肤被毛等细分需求。', 'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00'),
(8, '未卡',     '/img/brand/8.svg', '中国', '设计驱动的宠物生活品牌，主打猫抓板与家居化用品。', 'ACTIVE', '2026-01-01 00:00:00', '2026-01-01 00:00:00');

-- ---------- SPU ----------
INSERT INTO t_spu (id, title, subtitle, category_id, brand_id, supply_type, main_image, images, detail, status, sales_count, rating, rating_count, min_price, max_price, created_at, updated_at) VALUES
(1001, '麦富迪 冻干双拼全价猫粮 2kg', '成猫全价粮 冻干+主粮双拼', 101, 1, 'self', '/img/products/1001.svg', '/img/products/1001.svg,/img/products/1001-2.svg', '全价猫粮，符合 AAFCO 成猫营养标准。冻干颗粒占比约 8%，适口性更好。开袋后请密封并置于阴凉干燥处。', 'ON_SALE', 5230, 4.85, 1280, 12900, 13900, '2026-03-01 10:00:00', '2026-08-20 10:00:00'),
(1002, '皇家 幼猫粮 K36 2kg', '4-12 月龄幼猫专用', 101, 2, 'self', '/img/products/1002.svg', '/img/products/1002.svg', '针对幼猫消化系统与免疫发育设计，颗粒小、易咀嚼。建议按体重分餐喂食，并保证充足饮水。', 'ON_SALE', 4120, 4.78, 960, 15800, 15800, '2026-03-05 10:00:00', '2026-08-18 10:00:00'),
(1003, '渴望 六种鱼全阶段猫粮 1.8kg', '高蛋白无谷配方', 101, 3, 'self', '/img/products/1003.svg', '/img/products/1003.svg', '含六种深海鱼，动物蛋白占比高。对鱼类过敏的猫咪请勿选。换粮期建议 7 天渐进过渡。', 'ON_SALE', 1860, 4.92, 540, 32800, 33800, '2026-02-10 10:00:00', '2026-08-21 10:00:00'),
(1004, '网易严选 全价冻干猫粮 1.5kg', '0 谷物添加', 101, 4, 'self', '/img/products/1004.svg', '/img/products/1004.svg', '性价比路线全价粮，配料表公开可追溯，适合作为日常主粮。', 'ON_SALE', 3120, 4.61, 1120, 9900, 9900, '2026-04-01 10:00:00', '2026-08-19 10:00:00'),
(1005, '麦富迪 成犬牛肉味狗粮 5kg', '中大型犬成犬主粮', 102, 1, 'self', '/img/products/1005.svg', '/img/products/1005.svg', '牛肉配方，颗粒直径约 14mm，适合中大型犬咀嚼。含关节呵护成分。', 'ON_SALE', 2680, 4.72, 730, 18900, 18900, '2026-03-12 10:00:00', '2026-08-15 10:00:00'),
(1006, '皇家 小型犬幼犬粮 3kg', '断奶至 10 月龄', 102, 2, 'self', '/img/products/1006.svg', '/img/products/1006.svg', '小型犬幼犬专用，颗粒小且含抗氧化复合物，支持免疫系统发育。', 'ON_SALE', 1430, 4.80, 410, 22900, 22900, '2026-03-20 10:00:00', '2026-08-12 10:00:00'),
(1007, '渴望 原味犬粮 2kg', '全阶段高蛋白犬粮', 102, 3, 'self', '/img/products/1007.svg', '/img/products/1007.svg', '高肉含量配方，适合运动量较大的犬只。当前批次已售罄，补货中。', 'ON_SALE', 960, 4.88, 260, 28800, 28800, '2026-02-18 10:00:00', '2026-08-22 10:00:00'),
(1008, '麦富迪 猫罐头 85g×12罐', '湿粮主食罐 鸡肉味', 103, 1, 'self', '/img/products/1008.svg', '/img/products/1008.svg', '湿粮含水量高，适合饮水不足的猫咪补充水分。开罐后请冷藏并于 24 小时内食用完。', 'ON_SALE', 6410, 4.66, 2130, 6900, 6900, '2026-03-25 10:00:00', '2026-08-17 10:00:00'),
(1009, '未卡 冻干鸡肉粒 100g', '单一蛋白源 训练零食', 201, 8, 'merchant', '/img/products/1009.svg', '/img/products/1009.svg', '单一鸡肉蛋白源，适合作为训练奖励。对鸡肉过敏的宠物请勿喂食。', 'ON_SALE', 2870, 4.55, 890, 3900, 3900, '2026-04-10 10:00:00', '2026-08-16 10:00:00'),
(1010, '卫仕 猫用化毛膏 120g', '帮助排出毛球', 201, 7, 'merchant', '/img/products/1010.svg', '/img/products/1010.svg', '针对长毛猫换毛期设计，帮助毛球随粪便排出。持续呕吐毛球请及时就医。', 'ON_SALE', 1990, 4.42, 640, 4500, 4500, '2026-04-15 10:00:00', '2026-08-11 10:00:00'),
(1011, '麦富迪 鸡肉条训练零食 300g', '低脂高蛋白', 203, 1, 'self', '/img/products/1011.svg', '/img/products/1011.svg', '适合日常训练与奖励，可撕成小块分次喂食。零食总量建议不超过每日热量 10%。', 'ON_SALE', 4520, 4.70, 1560, 2900, 2900, '2026-04-02 10:00:00', '2026-08-14 10:00:00'),
(1012, '网易严选 混合猫砂 6L×4包', '豆腐砂+膨润土混合', 301, 4, 'self', '/img/products/1012.svg', '/img/products/1012.svg', '混合配方兼顾结团速度与低粉尘，可冲厕所。建议每日清理结团并每周整盆更换。', 'ON_SALE', 8320, 4.74, 3240, 5900, 5900, '2026-03-28 10:00:00', '2026-08-22 10:00:00'),
(1013, '小佩 全自动猫砂盆 MAX2', '自动铲屎 除臭 大空间', 503, 5, 'self', '/img/products/1013.svg', '/img/products/1013.svg', '自动感应铲屎，支持 App 记录如厕次数与体重。整机可拆洗，适配 6.5kg 以内猫咪。', 'ON_SALE', 320, 4.58, 96, 199900, 219900, '2026-05-01 10:00:00', '2026-08-20 10:00:00'),
(1014, '小佩 智能饮水机 SOLO', '无线水泵 静音循环', 501, 5, 'self', '/img/products/1014.svg', '/img/products/1014.svg', '循环活水提升饮水量，三重过滤。滤芯建议每 30 天更换一次。', 'ON_SALE', 1540, 4.63, 720, 24900, 24900, '2026-04-20 10:00:00', '2026-08-19 10:00:00'),
(1015, '霍曼 自动喂食器 4L', '定时定量 双向出粮', 502, 6, 'merchant', '/img/products/1015.svg', '/img/products/1015.svg', '4L 大容量粮仓，支持 App 设置每日多餐。断电后依靠电池维持计划出粮。', 'ON_SALE', 860, 4.49, 310, 39900, 39900, '2026-05-10 10:00:00', '2026-08-13 10:00:00'),
(1016, '卫仕 猫用复合维生素 200片', '日常营养补充', 402, 7, 'self', '/img/products/1016.svg', '/img/products/1016.svg', '补充多种维生素与微量元素，适用于自制粮或挑食猫咪。保健食品不能替代药物治疗。', 'ON_SALE', 2310, 4.51, 880, 6800, 6800, '2026-04-25 10:00:00', '2026-08-10 10:00:00'),
(1017, '小佩 猫咪牵引绳 胸背式', '防挣脱设计', 303, 5, 'merchant', '/img/products/1017.svg', '/img/products/1017.svg', '胸背式受力分散，减少颈部压迫。请先在家中让猫咪适应穿戴。', 'ON_SALE', 1120, 4.36, 420, 7900, 7900, '2026-05-15 10:00:00', '2026-08-09 10:00:00'),
(1018, '未卡 瓦楞纸猫抓板 加大号', '可翻面 双面可用', 304, 8, 'merchant', '/img/products/1018.svg', '/img/products/1018.svg', '高密度瓦楞纸，双面可翻用，配赠猫薄荷。建议配合抓挠引导使用。', 'ON_SALE', 3050, 4.44, 1180, 5900, 5900, '2026-05-20 10:00:00', '2026-08-08 10:00:00');

-- ---------- SKU ----------
-- is_food=1 的商品才需要过敏原提示；1007 的两个规格库存均为 0，用于验证「无货」与 inStockOnly 筛选
INSERT INTO t_sku (id, spu_id, sku_code, spec_json, image, price, market_price, stock, frozen_stock, is_food, target_species, target_life_stage, allergens, status, created_at, updated_at) VALUES
(2001, 1001, 'DD1001-2KG', '{"规格":"2kg","口味":"冻干双拼"}', '/img/sku/2001.svg', 12900, 15900, 320, 0, 1, 'cat', 'ADULT', '鸡肉,谷物', 'ACTIVE', '2026-03-01 10:00:00', '2026-08-20 10:00:00'),
(2002, 1001, 'DD1001-4KG', '{"规格":"4kg","口味":"冻干双拼"}', '/img/sku/2002.svg', 13900, 19900, 96, 0, 1, 'cat', 'ADULT', '鸡肉,谷物', 'ACTIVE', '2026-03-01 10:00:00', '2026-08-20 10:00:00'),
(2003, 1002, 'DD1002-2KG', '{"规格":"2kg"}', '/img/sku/2003.svg', 15800, 18800, 210, 0, 1, 'cat', 'BABY', '鸡肉,谷物', 'ACTIVE', '2026-03-05 10:00:00', '2026-08-18 10:00:00'),
(2004, 1003, 'DD1003-1.8KG', '{"规格":"1.8kg"}', '/img/sku/2004.svg', 32800, 36800, 42, 0, 1, 'cat', 'BABY,ADULT,SENIOR', '鱼类', 'ACTIVE', '2026-02-10 10:00:00', '2026-08-21 10:00:00'),
(2005, 1003, 'DD1003-5.4KG', '{"规格":"5.4kg"}', '/img/sku/2005.svg', 33800, 39800, 18, 0, 1, 'cat', 'BABY,ADULT,SENIOR', '鱼类', 'ACTIVE', '2026-02-10 10:00:00', '2026-08-21 10:00:00'),
(2006, 1004, 'DD1004-1.5KG', '{"规格":"1.5kg"}', '/img/sku/2006.svg', 9900, 12900, 480, 0, 1, 'cat', 'ADULT', '鸡肉', 'ACTIVE', '2026-04-01 10:00:00', '2026-08-19 10:00:00'),
(2007, 1005, 'DD1005-5KG', '{"规格":"5kg","口味":"牛肉"}', '/img/sku/2007.svg', 18900, 22900, 160, 0, 1, 'dog', 'ADULT,SENIOR', '牛肉,谷物', 'ACTIVE', '2026-03-12 10:00:00', '2026-08-15 10:00:00'),
(2008, 1006, 'DD1006-3KG', '{"规格":"3kg"}', '/img/sku/2008.svg', 22900, 26900, 130, 0, 1, 'dog', 'BABY', '鸡肉,谷物', 'ACTIVE', '2026-03-20 10:00:00', '2026-08-12 10:00:00'),
(2009, 1007, 'DD1007-2KG', '{"规格":"2kg"}', '/img/sku/2009.svg', 28800, 32800, 0, 0, 1, 'dog', 'BABY,ADULT,SENIOR', '鱼类', 'ACTIVE', '2026-02-18 10:00:00', '2026-08-22 10:00:00'),
(2010, 1008, 'DD1008-12', '{"规格":"85g×12罐","口味":"鸡肉"}', '/img/sku/2010.svg', 6900, 8900, 620, 0, 1, 'cat', 'ADULT,SENIOR', '鸡肉', 'ACTIVE', '2026-03-25 10:00:00', '2026-08-17 10:00:00'),
(2011, 1009, 'DD1009-100G', '{"规格":"100g"}', '/img/sku/2011.svg', 3900, 4900, 740, 0, 1, 'cat', 'BABY,ADULT,SENIOR', '鸡肉', 'ACTIVE', '2026-04-10 10:00:00', '2026-08-16 10:00:00'),
(2012, 1010, 'DD1010-120G', '{"规格":"120g"}', '/img/sku/2012.svg', 4500, 5900, 350, 0, 1, 'cat', 'ADULT', NULL, 'ACTIVE', '2026-04-15 10:00:00', '2026-08-11 10:00:00'),
(2013, 1011, 'DD1011-300G', '{"规格":"300g"}', '/img/sku/2013.svg', 2900, 3900, 880, 0, 1, 'dog', 'BABY,ADULT,SENIOR', '鸡肉', 'ACTIVE', '2026-04-02 10:00:00', '2026-08-14 10:00:00'),
(2014, 1012, 'DD1012-4PK', '{"规格":"6L×4包"}', '/img/sku/2014.svg', 5900, 7900, 1200, 0, 0, 'cat', NULL, NULL, 'ACTIVE', '2026-03-28 10:00:00', '2026-08-22 10:00:00'),
(2015, 1013, 'DD1013-MAX2', '{"颜色":"白色","版本":"标准版"}', '/img/sku/2015.svg', 199900, 249900, 12, 0, 0, 'cat', NULL, NULL, 'ACTIVE', '2026-05-01 10:00:00', '2026-08-20 10:00:00'),
(2016, 1013, 'DD1013-MAX2-PRO', '{"颜色":"白色","版本":"带除臭模块"}', '/img/sku/2016.svg', 219900, 269900, 5, 0, 0, 'cat', NULL, NULL, 'ACTIVE', '2026-05-01 10:00:00', '2026-08-20 10:00:00'),
(2017, 1014, 'DD1014-SOLO', '{"颜色":"白色"}', '/img/sku/2017.svg', 24900, 29900, 240, 0, 0, 'cat', NULL, NULL, 'ACTIVE', '2026-04-20 10:00:00', '2026-08-19 10:00:00'),
(2018, 1015, 'DD1015-4L', '{"容量":"4L"}', '/img/sku/2018.svg', 39900, 46900, 88, 0, 0, 'cat', NULL, NULL, 'ACTIVE', '2026-05-10 10:00:00', '2026-08-13 10:00:00'),
(2019, 1016, 'DD1016-200', '{"规格":"200片"}', '/img/sku/2019.svg', 6800, 8800, 410, 0, 0, 'cat', NULL, NULL, 'ACTIVE', '2026-04-25 10:00:00', '2026-08-10 10:00:00'),
(2020, 1017, 'DD1017-M', '{"尺寸":"M 号"}', '/img/sku/2020.svg', 7900, 9900, 160, 0, 0, 'cat', NULL, NULL, 'ACTIVE', '2026-05-15 10:00:00', '2026-08-09 10:00:00'),
(2021, 1017, 'DD1017-L', '{"尺寸":"L 号"}', '/img/sku/2021.svg', 7900, 9900, 0, 0, 0, 'cat', NULL, NULL, 'OFF_SHELF', '2026-05-15 10:00:00', '2026-08-09 10:00:00'),
(2022, 1018, 'DD1018-XL', '{"规格":"加大号"}', '/img/sku/2022.svg', 5900, 7900, 430, 0, 0, 'cat', NULL, NULL, 'ACTIVE', '2026-05-20 10:00:00', '2026-08-08 10:00:00');

-- ---------- C2C 闲置 ----------
-- 3009 待审核、3010 已驳回：对外必须不可见（含按 id 直访），用于验证可见性规则
INSERT INTO t_listing (id, seller_id, title, description, category_id, price, original_price, condition_level, city, images, species, breed, status, audit_status, view_count, created_at, updated_at) VALUES
(3001, 90001, '小佩饮水机 SOLO 全新未拆', '买重复了，全新未拆封，包装完好，同城可自提。', 501, 19900, 24900, 'NEW', '上海', '/img/listings/3001.svg', 'cat', '英短', 'ON_SALE', 'APPROVED', 326, '2026-08-05 09:00:00', '2026-08-05 09:00:00'),
(3002, 90002, '霍曼自动喂食器 4L 九成新', '用了两个月，功能正常，已清洁消毒，附原包装与充电线。', 502, 29900, 39900, 'LIKE_NEW', '北京', '/img/listings/3002.svg', 'cat', '中华田园猫', 'ON_SALE', 'APPROVED', 512, '2026-08-06 09:00:00', '2026-08-06 09:00:00'),
(3003, 90003, '网易严选混合猫砂 未拆封 2 包', '囤多了，单包 59 元购入，两包一起出。', 301, 3900, 5900, 'NEW', '杭州', '/img/listings/3003.svg', 'cat', '布偶', 'ON_SALE', 'APPROVED', 188, '2026-08-07 09:00:00', '2026-08-07 09:00:00'),
(3004, 90004, '麦富迪冻干双拼猫粮 2kg 临期', '保质期还有 10 个月，家里猫换粮了吃不完，低价出。', 101, 6900, 12900, 'GOOD', '广州', '/img/listings/3004.svg', 'cat', '橘猫', 'ON_SALE', 'APPROVED', 640, '2026-08-08 09:00:00', '2026-08-08 09:00:00'),
(3005, 90005, '未卡瓦楞纸猫抓板 加大号', '用了两周，猫咪不感兴趣，基本全新，可翻面再用。', 304, 2900, 5900, 'GOOD', '成都', '/img/listings/3005.svg', 'cat', '美短', 'ON_SALE', 'APPROVED', 275, '2026-08-09 09:00:00', '2026-08-09 09:00:00'),
(3006, 90006, '小佩猫咪牵引绳 胸背式 M 号', '带猫出门用过两次，猫实在抗拒，转给有需要的家长。', 303, 4500, 7900, 'LIKE_NEW', '深圳', '/img/listings/3006.svg', 'cat', '暹罗', 'ON_SALE', 'APPROVED', 143, '2026-08-10 09:00:00', '2026-08-10 09:00:00'),
(3007, 90007, '小佩智能猫砂盆 MAX2 带原包装', '搬家急出，成色很好，功能全部正常，可当面验货。', 503, 159900, 199900, 'LIKE_NEW', '上海', '/img/listings/3007.svg', 'cat', '英短', 'ON_SALE', 'APPROVED', 918, '2026-08-11 09:00:00', '2026-08-11 09:00:00'),
(3008, 90008, '皇家幼猫粮 K36 3.5kg 全新', '双十一囤的，小猫已经长大换粮，全新未开封。', 101, 11900, 15800, 'NEW', '武汉', '/img/listings/3008.svg', 'cat', '中华田园猫', 'ON_SALE', 'APPROVED', 402, '2026-08-12 09:00:00', '2026-08-12 09:00:00'),
(3009, 90009, '小佩饮水机 SOLO 待审核示例', '这条数据 audit_status=PENDING，接口不应返回它。', 501, 18900, 24900, 'NEW', '西安', '/img/listings/3009.svg', 'cat', '英短', 'ON_SALE', 'PENDING', 0, '2026-08-13 09:00:00', '2026-08-13 09:00:00'),
(3010, 90010, '已驳回闲置示例', '这条数据 audit_status=REJECTED，接口不应返回它。', 304, 1000, 5900, 'FAIR', '重庆', '/img/listings/3010.svg', 'cat', '未知', 'ON_SALE', 'REJECTED', 0, '2026-08-14 09:00:00', '2026-08-14 09:00:00');

-- ---------- 商家商品卖家归属（第四十七轮） ----------
-- t_spu.seller_id 默认 0（自营）；merchant 商品的归属卖家在用户域种子之后回填。
-- 「问一问」（ASK）按商品归属卖家定收件人，没有归属的商品无法咨询。
-- 与 incremental/15-ask-seller-backfill.sql 保持同一口径，跨环境可重放。
--
-- ★★ 这里必须用 **JOIN**，绝不能用 `SET seller_id = (SELECT ...)` 子查询 ★★
--    子查询查不到人时返回的是 **NULL**，而 seller_id 是 NOT NULL ——
--    于是「回填目标不存在」会把整份**全新初始化**打断在这里：
--        ERROR 1048 (23000) at line 337: Column 'seller_id' cannot be null
--    这条错误的杀伤力在于它**只在新库上现形**：
--      · 本机是老库，数据早就有了，本地永远看不到（我本人一直以为是「历史问题」）；
--      · CI 每轮都是空库 → **每轮必红**，而且它藏在 mysql 容器日志里，
--        表面症状只是「docker compose up 失败」，极难往种子上想。
--    JOIN 的语义恰好是我们要的：「有这个账号就回填，没有就什么都不做」——
--    幂等、可重放、且不会因为数据缺失而崩。
--    （第 337/289/257 行是同一个错在不同轮次的行号漂移，说明它一直没被修掉。）
UPDATE t_spu s
JOIN duoduo_user.t_user u ON u.phone = '13550931548' AND u.role = 'SELLER'
SET s.seller_id = u.id
WHERE s.id IN (1009, 1010);

UPDATE t_spu s
JOIN duoduo_user.t_user u ON u.phone = '13765569756' AND u.role = 'SELLER'
SET s.seller_id = u.id
WHERE s.id IN (1015, 1017, 1018);

-- ---------- 自营商品归属官方客服（第四十八轮） ----------
-- 自营（supply_type='self'）商品 seller_id=0 时「问一问」一律 422；
-- 归属到「多多官方客服」后语义变为咨询官方客服，复用全部私信链路。
-- 官方客服账号由 incremental/16-official-cs-and-avatars.sql 创建（含头像），
-- 与增量 16 保持同一口径，跨环境可重放。
-- （同一条 JOIN 铁律：账号不在就保持 seller_id=0，不做 NULL 赋值。）
UPDATE t_spu s
JOIN duoduo_user.t_user u ON u.phone = '13900000002' AND u.role = 'SELLER'
SET s.seller_id = u.id
WHERE s.supply_type = 'self' AND s.seller_id = 0;

-- ---------------------------------------------------------------------------
-- 2026-10-18 · 测试商品种子（与 incremental/35 同源，保证 down -v 重建后仍在）
-- 全部自营（supply_type=self, seller_id=0）；INSERT IGNORE 可重复执行。
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO t_spu (id,title,subtitle,category_id,brand_id,supply_type,main_image,images,detail,status,audit_status,audit_reason,audit_at,audit_by,audit_source,sales_count,rating,rating_count,min_price,max_price,created_at,updated_at,seller_id) VALUES (1101,'渴望 六种鱼全价猫粮','成猫无谷配方 高蛋白低敏',101,3,'self','/img/products/1101.svg','/img/products/1101.svg,/img/products/1101-2.svg','六种深海鱼配方，动物蛋白占比高，适合成猫日常主食。开袋后请密封并置于阴凉干燥处。','ON_SALE','APPROVED',NULL,NULL,NULL,NULL,866,4.8,126,29900,39900,DATE_SUB('2026-10-18 12:00:00', INTERVAL 3 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 3 DAY),0);
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3001,1101,'DD1101-3001','{"规格":"1.8kg"}','/img/sku/3001.svg',29900,35900,120,0,1,'cat','ADULT','鱼类','ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 3 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 3 DAY));
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3002,1101,'DD1101-3002','{"规格":"2.7kg"}','/img/sku/3002.svg',39900,46900,86,0,1,'cat','ADULT','鱼类','ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 3 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 3 DAY));
INSERT IGNORE INTO t_spu (id,title,subtitle,category_id,brand_id,supply_type,main_image,images,detail,status,audit_status,audit_reason,audit_at,audit_by,audit_source,sales_count,rating,rating_count,min_price,max_price,created_at,updated_at,seller_id) VALUES (1102,'网易严选 全价成猫粮 2kg','鸡肉配方 日常囤货款',101,4,'self','/img/products/1102.svg','/img/products/1102.svg,/img/products/1102-2.svg','以鸡肉为主要蛋白来源，颗粒大小适中，适合一岁以上成猫。换粮请按七天比例过渡。','ON_SALE','APPROVED',NULL,NULL,NULL,NULL,1420,4.6,311,12900,26900,DATE_SUB('2026-10-18 12:00:00', INTERVAL 6 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 6 DAY),0);
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3003,1102,'DD1102-3003','{"规格":"2kg"}','/img/sku/3003.svg',12900,15900,260,0,1,'cat','ADULT','鸡肉,谷物','ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 6 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 6 DAY));
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3004,1102,'DD1102-3004','{"规格":"5kg"}','/img/sku/3004.svg',26900,32900,140,0,1,'cat','ADULT','鸡肉,谷物','ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 6 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 6 DAY));
INSERT IGNORE INTO t_spu (id,title,subtitle,category_id,brand_id,supply_type,main_image,images,detail,status,audit_status,audit_reason,audit_at,audit_by,audit_source,sales_count,rating,rating_count,min_price,max_price,created_at,updated_at,seller_id) VALUES (1103,'麦富迪 牛肉双拼犬粮 5kg','中大型犬 牛肉+果蔬双拼',102,1,'self','/img/products/1103.svg','/img/products/1103.svg,/img/products/1103-2.svg','牛肉粒与主粮双拼，添加果蔬纤维，适合中大型成犬。请按体重参考表喂食。','ON_SALE','APPROVED',NULL,NULL,NULL,NULL,733,4.7,98,15900,26900,DATE_SUB('2026-10-18 12:00:00', INTERVAL 9 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 9 DAY),0);
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3005,1103,'DD1103-3005','{"规格":"5kg"}','/img/sku/3005.svg',15900,19900,180,0,1,'dog','ADULT','牛肉,谷物','ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 9 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 9 DAY));
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3006,1103,'DD1103-3006','{"规格":"10kg"}','/img/sku/3006.svg',26900,33900,75,0,1,'dog','ADULT','牛肉,谷物','ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 9 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 9 DAY));
INSERT IGNORE INTO t_spu (id,title,subtitle,category_id,brand_id,supply_type,main_image,images,detail,status,audit_status,audit_reason,audit_at,audit_by,audit_source,sales_count,rating,rating_count,min_price,max_price,created_at,updated_at,seller_id) VALUES (1104,'皇家 小型成犬粮 A3 3kg','10kg 以下小型犬专用',102,2,'self','/img/products/1104.svg','/img/products/1104.svg,/img/products/1104-2.svg','针对小型成犬的咀嚼与消化特点设计，颗粒偏小。开袋后建议一个月内食用完。','ON_SALE','APPROVED',NULL,NULL,NULL,NULL,512,4.5,64,18900,18900,DATE_SUB('2026-10-18 12:00:00', INTERVAL 12 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 12 DAY),0);
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3007,1104,'DD1104-3007','{"规格":"3kg"}','/img/sku/3007.svg',18900,21900,150,0,1,'dog','ADULT','鸡肉,谷物','ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 12 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 12 DAY));
INSERT IGNORE INTO t_spu (id,title,subtitle,category_id,brand_id,supply_type,main_image,images,detail,status,audit_status,audit_reason,audit_at,audit_by,audit_source,sales_count,rating,rating_count,min_price,max_price,created_at,updated_at,seller_id) VALUES (1105,'未卡 冻干鸡肉粒 500g','主食伴侣 高适口性',103,8,'self','/img/products/1105.svg','/img/products/1105.svg,/img/products/1105-2.svg','整块鸡胸肉冻干，可单独投喂或拌入主粮。开封后请冷藏并尽快食用。','ON_SALE','APPROVED',NULL,NULL,NULL,NULL,398,4.9,52,6900,6900,DATE_SUB('2026-10-18 12:00:00', INTERVAL 15 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 15 DAY),0);
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3008,1105,'DD1105-3008','{"规格":"500g"}','/img/sku/3008.svg',6900,8900,210,0,1,'cat','ADULT','鸡肉','ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 15 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 15 DAY));
INSERT IGNORE INTO t_spu (id,title,subtitle,category_id,brand_id,supply_type,main_image,images,detail,status,audit_status,audit_reason,audit_at,audit_by,audit_source,sales_count,rating,rating_count,min_price,max_price,created_at,updated_at,seller_id) VALUES (1201,'麦富迪 鸡胸肉干 500g','低脂高蛋白 犬用零食',202,1,'self','/img/products/1201.svg','/img/products/1201.svg,/img/products/1201-2.svg','整片鸡胸肉低温烘干，无额外添加糖盐。适合作为日常奖励或训练辅助。','ON_SALE','APPROVED',NULL,NULL,NULL,NULL,921,4.7,143,3900,3900,DATE_SUB('2026-10-18 12:00:00', INTERVAL 2 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 2 DAY),0);
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3009,1201,'DD1201-3009','{"规格":"500g"}','/img/sku/3009.svg',3900,4900,320,0,1,'dog','ADULT','鸡肉','ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 2 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 2 DAY));
INSERT IGNORE INTO t_spu (id,title,subtitle,category_id,brand_id,supply_type,main_image,images,detail,status,audit_status,audit_reason,audit_at,audit_by,audit_source,sales_count,rating,rating_count,min_price,max_price,created_at,updated_at,seller_id) VALUES (1202,'未卡 猫条 混合口味 30支','金枪鱼/鸡肉/三文鱼三种口味',201,8,'self','/img/products/1202.svg','/img/products/1202.svg,/img/products/1202-2.svg','细腻肉泥质地，适合作为互动奖励或拌粮增味。每日建议不超过两支。','ON_SALE','APPROVED',NULL,NULL,NULL,NULL,2103,4.8,402,2990,2990,DATE_SUB('2026-10-18 12:00:00', INTERVAL 4 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 4 DAY),0);
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3010,1202,'DD1202-3010','{"规格":"30支"}','/img/sku/3010.svg',2990,3990,480,0,1,'cat','ADULT','鱼类,鸡肉','ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 4 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 4 DAY));
INSERT IGNORE INTO t_spu (id,title,subtitle,category_id,brand_id,supply_type,main_image,images,detail,status,audit_status,audit_reason,audit_at,audit_by,audit_source,sales_count,rating,rating_count,min_price,max_price,created_at,updated_at,seller_id) VALUES (1203,'皇家 犬用洁齿骨 7支','中型犬 日常口腔清洁',202,2,'self','/img/products/1203.svg','/img/products/1203.svg,/img/products/1203-2.svg','通过咀嚼摩擦帮助减少牙菌斑，适合十公斤以上成犬。请在主人看护下喂食。','ON_SALE','APPROVED',NULL,NULL,NULL,NULL,447,4.4,58,4590,4590,DATE_SUB('2026-10-18 12:00:00', INTERVAL 8 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 8 DAY),0);
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3011,1203,'DD1203-3011','{"规格":"7支"}','/img/sku/3011.svg',4590,5590,190,0,1,'dog','ADULT','鸡肉','ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 8 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 8 DAY));
INSERT IGNORE INTO t_spu (id,title,subtitle,category_id,brand_id,supply_type,main_image,images,detail,status,audit_status,audit_reason,audit_at,audit_by,audit_source,sales_count,rating,rating_count,min_price,max_price,created_at,updated_at,seller_id) VALUES (1204,'网易严选 冻干蛋黄粒 120g','猫用 拌粮增香',201,4,'self','/img/products/1204.svg','/img/products/1204.svg,/img/products/1204-2.svg','单一蛋黄原料冻干，颗粒酥松，可捏碎拌入主粮。开封后请密封防潮。','ON_SALE','APPROVED',NULL,NULL,NULL,NULL,654,4.6,87,3590,3590,DATE_SUB('2026-10-18 12:00:00', INTERVAL 11 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 11 DAY),0);
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3012,1204,'DD1204-3012','{"规格":"120g"}','/img/sku/3012.svg',3590,4290,240,0,1,'cat','ADULT','蛋类','ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 11 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 11 DAY));
INSERT IGNORE INTO t_spu (id,title,subtitle,category_id,brand_id,supply_type,main_image,images,detail,status,audit_status,audit_reason,audit_at,audit_by,audit_source,sales_count,rating,rating_count,min_price,max_price,created_at,updated_at,seller_id) VALUES (1205,'麦富迪 训练小饼干 300g','犬猫通用 一口一粒',203,1,'self','/img/products/1205.svg','/img/products/1205.svg,/img/products/1205-2.svg','小颗粒设计便于多次奖励，适合基础指令训练。请搭配正餐控制总摄入。','ON_SALE','APPROVED',NULL,NULL,NULL,NULL,388,4.3,41,1990,1990,DATE_SUB('2026-10-18 12:00:00', INTERVAL 14 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 14 DAY),0);
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3013,1205,'DD1205-3013','{"规格":"300g"}','/img/sku/3013.svg',1990,2590,350,0,1,'dog','BABY','鸡肉,谷物','ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 14 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 14 DAY));
INSERT IGNORE INTO t_spu (id,title,subtitle,category_id,brand_id,supply_type,main_image,images,detail,status,audit_status,audit_reason,audit_at,audit_by,audit_source,sales_count,rating,rating_count,min_price,max_price,created_at,updated_at,seller_id) VALUES (1301,'未卡 豆腐猫砂 6L×4包','低粉尘 可冲厕',301,8,'self','/img/products/1301.svg','/img/products/1301.svg,/img/products/1301-2.svg','植物纤维压制，结团快、粉尘低。建议铺砂厚度五至七厘米，每日清理结团。','ON_SALE','APPROVED',NULL,NULL,NULL,NULL,1266,4.7,205,8990,8990,DATE_SUB('2026-10-18 12:00:00', INTERVAL 5 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 5 DAY),0);
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3014,1301,'DD1301-3014','{"规格":"6L×4包"}','/img/sku/3014.svg',8990,10900,200,0,0,'cat','ADULT',NULL,'ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 5 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 5 DAY));
INSERT IGNORE INTO t_spu (id,title,subtitle,category_id,brand_id,supply_type,main_image,images,detail,status,audit_status,audit_reason,audit_at,audit_by,audit_source,sales_count,rating,rating_count,min_price,max_price,created_at,updated_at,seller_id) VALUES (1302,'霍曼 膨润土混合猫砂 10L','结团快 除味持久',301,6,'self','/img/products/1302.svg','/img/products/1302.svg,/img/products/1302-2.svg','膨润土与豆腐砂混合，兼顾结团强度与低粉尘。请避免大量冲入马桶。','ON_SALE','APPROVED',NULL,NULL,NULL,NULL,742,4.5,96,6990,6990,DATE_SUB('2026-10-18 12:00:00', INTERVAL 10 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 10 DAY),0);
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3015,1302,'DD1302-3015','{"规格":"10L"}','/img/sku/3015.svg',6990,8990,180,0,0,'cat','ADULT',NULL,'ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 10 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 10 DAY));
INSERT IGNORE INTO t_spu (id,title,subtitle,category_id,brand_id,supply_type,main_image,images,detail,status,audit_status,audit_reason,audit_at,audit_by,audit_source,sales_count,rating,rating_count,min_price,max_price,created_at,updated_at,seller_id) VALUES (1303,'小佩 陶瓷双碗食盆','防滑底座 可拆洗',302,5,'self','/img/products/1303.svg','/img/products/1303.svg,/img/products/1303-2.svg','陶瓷内胆不易挂油，碗架高度适中，适合中小型犬猫。请定期清洗并晾干。','ON_SALE','APPROVED',NULL,NULL,NULL,NULL,315,4.6,47,5900,9900,DATE_SUB('2026-10-18 12:00:00', INTERVAL 7 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 7 DAY),0);
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3016,1303,'DD1303-3016','{"规格":"单碗"}','/img/sku/3016.svg',5900,6900,150,0,0,'cat','ADULT',NULL,'ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 7 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 7 DAY));
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3017,1303,'DD1303-3017','{"规格":"双碗"}','/img/sku/3017.svg',9900,11900,110,0,0,'cat','ADULT',NULL,'ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 7 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 7 DAY));
INSERT IGNORE INTO t_spu (id,title,subtitle,category_id,brand_id,supply_type,main_image,images,detail,status,audit_status,audit_reason,audit_at,audit_by,audit_source,sales_count,rating,rating_count,min_price,max_price,created_at,updated_at,seller_id) VALUES (1304,'未卡 反光牵引绳 1.5m','夜间反光 中型犬适用',303,8,'self','/img/products/1304.svg','/img/products/1304.svg,/img/products/1304-2.svg','织带内置反光丝，夜间遛狗更易被看见。请定期检查卡扣与缝线磨损。','ON_SALE','APPROVED',NULL,NULL,NULL,NULL,208,4.4,33,5900,5900,DATE_SUB('2026-10-18 12:00:00', INTERVAL 13 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 13 DAY),0);
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3018,1304,'DD1304-3018','{"规格":"1.5m"}','/img/sku/3018.svg',5900,7900,160,0,0,'dog','ADULT',NULL,'ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 13 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 13 DAY));
INSERT IGNORE INTO t_spu (id,title,subtitle,category_id,brand_id,supply_type,main_image,images,detail,status,audit_status,audit_reason,audit_at,audit_by,audit_source,sales_count,rating,rating_count,min_price,max_price,created_at,updated_at,seller_id) VALUES (1305,'网易严选 四季猫窝 中号','可拆洗 半封闭设计',304,4,'self','/img/products/1305.svg','/img/products/1305.svg,/img/products/1305-2.svg','半封闭结构给猫安全感，垫面可拆洗。适合十斤以内成猫，避免暴晒。','ON_SALE','APPROVED',NULL,NULL,NULL,NULL,176,4.5,28,12900,12900,DATE_SUB('2026-10-18 12:00:00', INTERVAL 16 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 16 DAY),0);
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3019,1305,'DD1305-3019','{"规格":"中号"}','/img/sku/3019.svg',12900,15900,90,0,0,'cat','ADULT',NULL,'ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 16 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 16 DAY));
INSERT IGNORE INTO t_spu (id,title,subtitle,category_id,brand_id,supply_type,main_image,images,detail,status,audit_status,audit_reason,audit_at,audit_by,audit_source,sales_count,rating,rating_count,min_price,max_price,created_at,updated_at,seller_id) VALUES (1401,'卫仕 猫用体内外驱虫滴剂 3支','一月一次 全谱驱虫',401,7,'self','/img/products/1401.svg','/img/products/1401.svg,/img/products/1401-2.svg','滴于颈后皮肤，按月使用。孕期、哺乳期及病猫请先咨询执业兽医。','ON_SALE','APPROVED',NULL,NULL,NULL,NULL,1032,4.7,174,8900,8900,DATE_SUB('2026-10-18 12:00:00', INTERVAL 1 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 1 DAY),0);
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3020,1401,'DD1401-3020','{"规格":"3支"}','/img/sku/3020.svg',8900,10900,260,0,0,'cat','ADULT',NULL,'ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 1 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 1 DAY));
INSERT IGNORE INTO t_spu (id,title,subtitle,category_id,brand_id,supply_type,main_image,images,detail,status,audit_status,audit_reason,audit_at,audit_by,audit_source,sales_count,rating,rating_count,min_price,max_price,created_at,updated_at,seller_id) VALUES (1402,'卫仕 犬用体外驱虫滴剂 3支','中型犬适用 防蜱防蚤',401,7,'self','/img/products/1402.svg','/img/products/1402.svg,/img/products/1402-2.svg','按体重选择剂量，滴于肩胛间皮肤。使用后三日内避免洗澡。','ON_SALE','APPROVED',NULL,NULL,NULL,NULL,588,4.6,92,9900,9900,DATE_SUB('2026-10-18 12:00:00', INTERVAL 6 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 6 DAY),0);
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3021,1402,'DD1402-3021','{"规格":"3支"}','/img/sku/3021.svg',9900,11900,220,0,0,'dog','ADULT',NULL,'ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 6 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 6 DAY));
INSERT IGNORE INTO t_spu (id,title,subtitle,category_id,brand_id,supply_type,main_image,images,detail,status,audit_status,audit_reason,audit_at,audit_by,audit_source,sales_count,rating,rating_count,min_price,max_price,created_at,updated_at,seller_id) VALUES (1403,'卫仕 复合益生菌 30袋','调理肠胃 犬猫通用',402,7,'self','/img/products/1403.svg','/img/products/1403.svg,/img/products/1403-2.svg','含多种活性菌株，可拌入常温食物。请勿用热水冲调，以免影响活性。','ON_SALE','APPROVED',NULL,NULL,NULL,NULL,864,4.8,131,6900,12900,DATE_SUB('2026-10-18 12:00:00', INTERVAL 9 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 9 DAY),0);
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3022,1403,'DD1403-3022','{"规格":"30袋"}','/img/sku/3022.svg',6900,8900,300,0,1,'cat','ADULT','乳制品','ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 9 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 9 DAY));
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3023,1403,'DD1403-3023','{"规格":"60袋"}','/img/sku/3023.svg',12900,15900,150,0,1,'cat','ADULT','乳制品','ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 9 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 9 DAY));
INSERT IGNORE INTO t_spu (id,title,subtitle,category_id,brand_id,supply_type,main_image,images,detail,status,audit_status,audit_reason,audit_at,audit_by,audit_source,sales_count,rating,rating_count,min_price,max_price,created_at,updated_at,seller_id) VALUES (1404,'麦富迪 关节软骨素 60片','中老年犬 关节营养补充',402,1,'self','/img/products/1404.svg','/img/products/1404.svg,/img/products/1404-2.svg','含软骨素与葡萄糖胺，适合活动量下降的中老年犬。请按体重调整用量。','ON_SALE','APPROVED',NULL,NULL,NULL,NULL,271,4.4,36,11900,11900,DATE_SUB('2026-10-18 12:00:00', INTERVAL 17 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 17 DAY),0);
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3024,1404,'DD1404-3024','{"规格":"60片"}','/img/sku/3024.svg',11900,14900,130,0,1,'dog','SENIOR','禽类','ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 17 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 17 DAY));
INSERT IGNORE INTO t_spu (id,title,subtitle,category_id,brand_id,supply_type,main_image,images,detail,status,audit_status,audit_reason,audit_at,audit_by,audit_source,sales_count,rating,rating_count,min_price,max_price,created_at,updated_at,seller_id) VALUES (1405,'小佩 宠物洁耳液 120ml','温和清洁 犬猫通用',403,5,'self','/img/products/1405.svg','/img/products/1405.svg,/img/products/1405-2.svg','滴入耳道后轻揉耳根，让宠物自行甩出污物。耳道破损时请勿使用。','ON_SALE','APPROVED',NULL,NULL,NULL,NULL,493,4.5,61,3900,3900,DATE_SUB('2026-10-18 12:00:00', INTERVAL 20 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 20 DAY),0);
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3025,1405,'DD1405-3025','{"规格":"120ml"}','/img/sku/3025.svg',3900,4900,240,0,0,'cat','ADULT',NULL,'ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 20 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 20 DAY));
INSERT IGNORE INTO t_spu (id,title,subtitle,category_id,brand_id,supply_type,main_image,images,detail,status,audit_status,audit_reason,audit_at,audit_by,audit_source,sales_count,rating,rating_count,min_price,max_price,created_at,updated_at,seller_id) VALUES (1501,'小佩 智能饮水机 2代 1.8L','循环过滤 静音水泵',501,5,'self','/img/products/1501.svg','/img/products/1501.svg,/img/products/1501-2.svg','四重过滤循环供水，缺水自动断电。滤芯建议每四周更换一次。','ON_SALE','APPROVED',NULL,NULL,NULL,NULL,1075,4.7,188,24900,24900,DATE_SUB('2026-10-18 12:00:00', INTERVAL 2 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 2 DAY),0);
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3026,1501,'DD1501-3026','{"规格":"1.8L"}','/img/sku/3026.svg',24900,29900,120,0,0,'cat','ADULT',NULL,'ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 2 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 2 DAY));
INSERT IGNORE INTO t_spu (id,title,subtitle,category_id,brand_id,supply_type,main_image,images,detail,status,audit_status,audit_reason,audit_at,audit_by,audit_source,sales_count,rating,rating_count,min_price,max_price,created_at,updated_at,seller_id) VALUES (1502,'霍曼 无线水泵饮水机 2L','无线供电 摆位自由',501,6,'self','/img/products/1502.svg','/img/products/1502.svg,/img/products/1502-2.svg','无线水泵设计，摆放不受电源线限制。请使用原装适配器充电。','ON_SALE','APPROVED',NULL,NULL,NULL,NULL,402,4.5,55,19900,19900,DATE_SUB('2026-10-18 12:00:00', INTERVAL 8 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 8 DAY),0);
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3027,1502,'DD1502-3027','{"规格":"2L"}','/img/sku/3027.svg',19900,24900,100,0,0,'cat','ADULT',NULL,'ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 8 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 8 DAY));
INSERT IGNORE INTO t_spu (id,title,subtitle,category_id,brand_id,supply_type,main_image,images,detail,status,audit_status,audit_reason,audit_at,audit_by,audit_source,sales_count,rating,rating_count,min_price,max_price,created_at,updated_at,seller_id) VALUES (1503,'小佩 智能喂食器 3L','定时定量 断电续航',502,5,'self','/img/products/1503.svg','/img/products/1503.svg,/img/products/1503-2.svg','支持定时定量出粮，断电后仍可按计划出粮。仅适用干粮，颗粒直径建议五至十二毫米。','ON_SALE','APPROVED',NULL,NULL,NULL,NULL,693,4.6,114,39900,49900,DATE_SUB('2026-10-18 12:00:00', INTERVAL 4 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 4 DAY),0);
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3028,1503,'DD1503-3028','{"规格":"3L"}','/img/sku/3028.svg',39900,45900,80,0,0,'cat','ADULT',NULL,'ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 4 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 4 DAY));
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3029,1503,'DD1503-3029','{"规格":"5L"}','/img/sku/3029.svg',49900,56900,60,0,0,'cat','ADULT',NULL,'ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 4 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 4 DAY));
INSERT IGNORE INTO t_spu (id,title,subtitle,category_id,brand_id,supply_type,main_image,images,detail,status,audit_status,audit_reason,audit_at,audit_by,audit_source,sales_count,rating,rating_count,min_price,max_price,created_at,updated_at,seller_id) VALUES (1504,'霍曼 双仓定时喂食器 5L','双仓分粮 适合多宠',502,6,'self','/img/products/1504.svg','/img/products/1504.svg,/img/products/1504-2.svg','双仓独立出粮，可分别设定份量，适合两只宠物家庭。请定期清洁粮仓。','ON_SALE','APPROVED',NULL,NULL,NULL,NULL,158,4.4,22,29900,29900,DATE_SUB('2026-10-18 12:00:00', INTERVAL 12 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 12 DAY),0);
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3030,1504,'DD1504-3030','{"规格":"5L"}','/img/sku/3030.svg',29900,35900,70,0,0,'dog','ADULT',NULL,'ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 12 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 12 DAY));
INSERT IGNORE INTO t_spu (id,title,subtitle,category_id,brand_id,supply_type,main_image,images,detail,status,audit_status,audit_reason,audit_at,audit_by,audit_source,sales_count,rating,rating_count,min_price,max_price,created_at,updated_at,seller_id) VALUES (1505,'小佩 智能猫砂盆 标准版','自动清理 除味抑菌',503,5,'self','/img/products/1505.svg','/img/products/1505.svg,/img/products/1505-2.svg','感应到猫离开后自动清理，配套专用砂使用效果更佳。请按说明定期深度清洁。','ON_SALE','APPROVED',NULL,NULL,NULL,NULL,241,4.5,39,129900,129900,DATE_SUB('2026-10-18 12:00:00', INTERVAL 5 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 5 DAY),0);
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3031,1505,'DD1505-3031','{"规格":"标准版"}','/img/sku/3031.svg',129900,149900,40,0,0,'cat','ADULT',NULL,'ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 5 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 5 DAY));
INSERT IGNORE INTO t_spu (id,title,subtitle,category_id,brand_id,supply_type,main_image,images,detail,status,audit_status,audit_reason,audit_at,audit_by,audit_source,sales_count,rating,rating_count,min_price,max_price,created_at,updated_at,seller_id) VALUES (1601,'猫咪领养服务包 基础版','含基础体检、首针疫苗与驱虫',601,NULL,'self','/img/products/1601.svg','/img/products/1601.svg,/img/products/1601-2.svg','领养前完成健康检查与首针疫苗，提供三十天健康咨询。请先到店与猫咪互动确认匹配。','ON_SALE','APPROVED',NULL,NULL,NULL,NULL,156,4.9,31,29900,29900,DATE_SUB('2026-10-18 12:00:00', INTERVAL 1 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 1 DAY),0);
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3032,1601,'DD1601-3032','{"服务":"基础版（体检+疫苗）"}','/img/sku/3032.svg',29900,32900,30,0,0,'cat','BABY',NULL,'ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 1 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 1 DAY));
INSERT IGNORE INTO t_spu (id,title,subtitle,category_id,brand_id,supply_type,main_image,images,detail,status,audit_status,audit_reason,audit_at,audit_by,audit_source,sales_count,rating,rating_count,min_price,max_price,created_at,updated_at,seller_id) VALUES (1602,'猫咪领养服务包 安心版','含绝育、芯片与半年咨询',601,NULL,'self','/img/products/1602.svg','/img/products/1602.svg,/img/products/1602-2.svg','在基础版之上增加绝育与芯片植入，并提供半年健康咨询。领养需签署领养协议。','ON_SALE','APPROVED',NULL,NULL,NULL,NULL,88,5.0,19,69900,69900,DATE_SUB('2026-10-18 12:00:00', INTERVAL 7 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 7 DAY),0);
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3033,1602,'DD1602-3033','{"服务":"安心版（绝育+芯片）"}','/img/sku/3033.svg',69900,75900,20,0,0,'cat','ADULT',NULL,'ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 7 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 7 DAY));
INSERT IGNORE INTO t_spu (id,title,subtitle,category_id,brand_id,supply_type,main_image,images,detail,status,audit_status,audit_reason,audit_at,audit_by,audit_source,sales_count,rating,rating_count,min_price,max_price,created_at,updated_at,seller_id) VALUES (1603,'幼猫领养服务包 含首月粮','适合两到六月龄幼猫',601,NULL,'self','/img/products/1603.svg','/img/products/1603.svg,/img/products/1603-2.svg','包含基础体检、首针疫苗与首月主粮，帮助幼猫平稳过渡到新家。','ON_SALE','APPROVED',NULL,NULL,NULL,NULL,203,4.8,44,39900,39900,DATE_SUB('2026-10-18 12:00:00', INTERVAL 3 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 3 DAY),0);
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3034,1603,'DD1603-3034','{"服务":"幼猫版（含首月粮）"}','/img/sku/3034.svg',39900,43900,25,0,0,'cat','BABY',NULL,'ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 3 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 3 DAY));
INSERT IGNORE INTO t_spu (id,title,subtitle,category_id,brand_id,supply_type,main_image,images,detail,status,audit_status,audit_reason,audit_at,audit_by,audit_source,sales_count,rating,rating_count,min_price,max_price,created_at,updated_at,seller_id) VALUES (1604,'犬只领养服务包 基础版','含体检、疫苗与牵引礼包',602,NULL,'self','/img/products/1604.svg','/img/products/1604.svg,/img/products/1604-2.svg','领养前完成基础体检与疫苗，赠送牵引绳与食盆。建议先进行两次互动探访。','ON_SALE','APPROVED',NULL,NULL,NULL,NULL,74,4.7,15,39900,39900,DATE_SUB('2026-10-18 12:00:00', INTERVAL 10 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 10 DAY),0);
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3035,1604,'DD1604-3035','{"服务":"基础版（体检+疫苗）"}','/img/sku/3035.svg',39900,43900,18,0,0,'dog','ADULT',NULL,'ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 10 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 10 DAY));
INSERT IGNORE INTO t_spu (id,title,subtitle,category_id,brand_id,supply_type,main_image,images,detail,status,audit_status,audit_reason,audit_at,audit_by,audit_source,sales_count,rating,rating_count,min_price,max_price,created_at,updated_at,seller_id) VALUES (1605,'犬只领养服务包 安心版','含训导课程与半年咨询',602,NULL,'self','/img/products/1605.svg','/img/products/1605.svg,/img/products/1605-2.svg','包含基础训导课程与半年行为咨询，适合首次养犬家庭。领养需签署领养协议。','ON_SALE','APPROVED',NULL,NULL,NULL,NULL,41,4.8,9,89900,89900,DATE_SUB('2026-10-18 12:00:00', INTERVAL 16 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 16 DAY),0);
INSERT IGNORE INTO t_sku (id,spu_id,sku_code,spec_json,image,price,market_price,stock,frozen_stock,is_food,target_species,target_life_stage,allergens,status,created_at,updated_at) VALUES (3036,1605,'DD1605-3036','{"服务":"安心版（训导+咨询）"}','/img/sku/3036.svg',89900,96900,12,0,0,'dog','ADULT',NULL,'ACTIVE',DATE_SUB('2026-10-18 12:00:00', INTERVAL 16 DAY),DATE_SUB('2026-10-18 12:00:00', INTERVAL 16 DAY));
