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
