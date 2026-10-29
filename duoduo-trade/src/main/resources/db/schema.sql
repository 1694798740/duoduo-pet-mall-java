-- 交易域表结构（H2 / MySQL 8 双兼容）
--
-- 约定：
--   * 主键使用雪花 ID（BIGINT），不用自增，便于后续分库分表
--   * 金额统一 BIGINT（单位：分）；时间统一 TIMESTAMP（UTC）
--   * 订单明细保存「下单时刻快照」，商品改价改名不影响历史订单
--   * 幂等一律靠**唯一索引**兜底，不依赖分布式锁；
--     分布式锁只用来减少冲突，不作为正确性保障
--   * 状态字段用 VARCHAR 存枚举名（可读、便于排查），不用数字码

DROP TABLE IF EXISTS t_settlement;
DROP TABLE IF EXISTS t_payment;
DROP TABLE IF EXISTS t_stock_freeze;
DROP TABLE IF EXISTS t_order_status_log;
DROP TABLE IF EXISTS t_order_item;
DROP TABLE IF EXISTS t_order;
DROP TABLE IF EXISTS t_cart_item;

-- ---------- 购物车条目 ----------
-- 同一用户同一 SKU 只保留一行，重复加购走「数量累加」而非插新行。
CREATE TABLE t_cart_item (
    id          BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    spu_id      BIGINT       NOT NULL,
    sku_id      BIGINT       NOT NULL,
    quantity    INT          NOT NULL DEFAULT 1,
    selected    INT          NOT NULL DEFAULT 1,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_cart_user_sku ON t_cart_item (user_id, sku_id);
CREATE INDEX idx_cart_user ON t_cart_item (user_id);

-- ---------- 订单（已按卖家拆单：一个订单只属于一个卖家） ----------
-- refunded_at 与 closed_at 刻意分开：退款不代表「订单被关闭」，
-- 资金动作必须有独立时间戳，否则对账时无法区分「没付过款」和「付了又退了」。
CREATE TABLE t_order (
    id                BIGINT       NOT NULL,
    order_no          VARCHAR(32)  NOT NULL,
    user_id           BIGINT       NOT NULL,
    seller_id         BIGINT       NOT NULL,
    order_type        VARCHAR(20)  NOT NULL DEFAULT 'NORMAL',
    status            VARCHAR(30)  NOT NULL,
    total_amount      BIGINT       NOT NULL,
    freight_amount    BIGINT       NOT NULL DEFAULT 0,
    discount_amount   BIGINT       NOT NULL DEFAULT 0,
    pay_amount        BIGINT       NOT NULL,
    receiver_name     VARCHAR(50)  NOT NULL,
    receiver_phone    VARCHAR(20)  NOT NULL,
    receiver_address  VARCHAR(300) NOT NULL,
    remark            VARCHAR(200),
    idempotent_key    VARCHAR(64),
    expire_at         TIMESTAMP    NOT NULL,
    paid_at           TIMESTAMP,
    shipped_at        TIMESTAMP,
    finished_at       TIMESTAMP,
    closed_at         TIMESTAMP,
    refunded_at       TIMESTAMP,
    close_reason      VARCHAR(100),
    logistics_company VARCHAR(50),
    logistics_no      VARCHAR(64),
    created_at        TIMESTAMP    NOT NULL,
    updated_at        TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_order_no ON t_order (order_no);
CREATE UNIQUE INDEX uk_order_user_idem ON t_order (user_id, idempotent_key);
CREATE INDEX idx_order_user ON t_order (user_id, status);
CREATE INDEX idx_order_seller ON t_order (seller_id, status);
CREATE INDEX idx_order_expire ON t_order (status, expire_at);
-- 自动确认收货的扫描条件（已发货 + 发货时间）走这条索引
CREATE INDEX idx_order_auto_receipt ON t_order (status, shipped_at);

-- ---------- 订单明细（下单时刻快照） ----------
CREATE TABLE t_order_item (
    id             BIGINT        NOT NULL,
    order_id       BIGINT        NOT NULL,
    spu_id         BIGINT        NOT NULL,
    sku_id         BIGINT        NOT NULL,
    title          VARCHAR(200)  NOT NULL,
    spec_text      VARCHAR(200),
    main_image     VARCHAR(300),
    price          BIGINT        NOT NULL,
    quantity       INT           NOT NULL,
    amount         BIGINT        NOT NULL,
    refund_status  VARCHAR(20)   NOT NULL DEFAULT 'NONE',
    created_at     TIMESTAMP     NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_item_order ON t_order_item (order_id);
CREATE INDEX idx_item_sku ON t_order_item (sku_id);

-- ---------- 订单状态流转日志（可追溯，支撑对账与客诉） ----------
CREATE TABLE t_order_status_log (
    id             BIGINT       NOT NULL,
    order_id       BIGINT       NOT NULL,
    from_status    VARCHAR(30),
    to_status      VARCHAR(30)  NOT NULL,
    operator_type  VARCHAR(20)  NOT NULL,
    operator_id    BIGINT,
    reason         VARCHAR(200),
    created_at     TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_status_log_order ON t_order_status_log (order_id, created_at);

-- ---------- 库存冻结台账 ----------
-- 只记「本服务冻结了多少」，真实库存在商品服务；
-- 一个 (sku_id, order_id) 只允许一条，保证重复下单不会重复冻结。
--
-- status 取值：
--   FROZEN          下单已冻结
--   RELEASED        取消 / 超时关单已释放
--   DEDUCTED        支付成功，库存已真实扣减
--   RESTOCK_PENDING 退款已成立，等待把货补回库存（失败由补偿调度重试）
--   RESTOCKED       已入库回补完成
CREATE TABLE t_stock_freeze (
    id          BIGINT       NOT NULL,
    sku_id      BIGINT       NOT NULL,
    order_id    BIGINT       NOT NULL,
    quantity    INT          NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    expire_at   TIMESTAMP    NOT NULL,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_freeze_sku_order ON t_stock_freeze (sku_id, order_id);
CREATE INDEX idx_freeze_expire ON t_stock_freeze (status, expire_at);
CREATE INDEX idx_freeze_order ON t_stock_freeze (order_id);

-- ---------- 支付单 ----------
-- (channel, idempotent_key) 唯一：同一渠道同一幂等键的重复回调只入账一次。
--
-- status 取值（真实渠道与沙箱渠道共用同一套）：
--   INIT     已创建、尚未向渠道预下单
--   PENDING  已预下单，等待用户支付（沙箱/真实渠道的常态）
--   SUCCESS  渠道已确认收款
--   FAILED   渠道明确失败
--   EXPIRED  超时未支付（由补偿调度置入）
--   CLOSED   订单被取消/关闭，支付单随之作废
--
-- code_url 是渠道给的**二维码内容**（微信 Native 给 weixin:// 串，沙箱给收银台地址），
-- 由前端渲染成二维码。刻意不在服务端生成图片：与真实渠道返回形态一致，
-- 且省掉一个图片依赖。
CREATE TABLE t_payment (
    id                BIGINT       NOT NULL,
    pay_no            VARCHAR(32)  NOT NULL,
    order_id          BIGINT       NOT NULL,
    user_id           BIGINT       NOT NULL,
    channel           VARCHAR(20)  NOT NULL,
    amount            BIGINT       NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    idempotent_key    VARCHAR(64)  NOT NULL,
    channel_trade_no  VARCHAR(64),
    code_url          VARCHAR(500),
    fail_reason       VARCHAR(200),
    expire_at         TIMESTAMP,
    paid_at           TIMESTAMP,
    created_at        TIMESTAMP    NOT NULL,
    updated_at        TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_pay_no ON t_payment (pay_no);
CREATE UNIQUE INDEX uk_pay_channel_idem ON t_payment (channel, idempotent_key);
CREATE INDEX idx_pay_order ON t_payment (order_id);
-- 支付单超时扫描（补偿调度）走这条索引
CREATE INDEX idx_pay_expire ON t_payment (status, expire_at);

-- ---------- C2C 担保交易资金托管 ----------
-- 买家付款后钱进平台托管（CUSTODY），确认收货后打给卖家（RELEASED），
-- 退款则退回买家（REFUNDED）。一个订单只有一条托管记录。
-- 资金状态迁移一律用 CAS（status = 'CUSTODY' 写进 WHERE），
-- 由数据库保证「同一笔钱只能出去一次」，不依赖分布式锁。
CREATE TABLE t_settlement (
    id            BIGINT       NOT NULL,
    order_id      BIGINT       NOT NULL,
    seller_id     BIGINT       NOT NULL,
    amount        BIGINT       NOT NULL,
    platform_fee  BIGINT       NOT NULL DEFAULT 0,
    status        VARCHAR(20)  NOT NULL,
    release_at    TIMESTAMP,
    released_at   TIMESTAMP,
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_settle_order ON t_settlement (order_id);
CREATE INDEX idx_settle_seller ON t_settlement (seller_id, status);

-- ---------- 削峰下单任务台账（A11） ----------
-- 与 MySQL DDL（docs/sql/03-duoduo-trade.sql / incremental/10）同构，H2 单测/本地口径
CREATE TABLE t_order_place_task (
    id             BIGINT       NOT NULL,
    user_id        BIGINT       NOT NULL,
    idempotent_key VARCHAR(64)  NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    fail_reason    VARCHAR(200),
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_place_task_user_idem ON t_order_place_task (user_id, idempotent_key);
CREATE INDEX idx_place_task_user ON t_order_place_task (user_id, created_at);

-- ---------- 限时秒杀（A14） ----------
-- 活动与秒杀价独立于商品价：秒杀下单不走购物车，金额直接取秒杀价快照。
-- 活动配额（available_stock）是秒杀自己的「货」，与商品服务库存相互独立 ——
-- 秒杀下单不冻结商品库存，超时关单/取消时回补的是这里的配额。
DROP TABLE IF EXISTS t_seckill_order;
DROP TABLE IF EXISTS t_seckill_sku;
DROP TABLE IF EXISTS t_seckill_activity;
CREATE TABLE t_seckill_activity (
    id          BIGINT       NOT NULL,
    title       VARCHAR(100) NOT NULL,
    start_at    TIMESTAMP    NOT NULL,
    end_at      TIMESTAMP    NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ENABLED',
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_seckill_act_start ON t_seckill_activity (start_at);
CREATE TABLE t_seckill_sku (
    id              BIGINT       NOT NULL,
    activity_id     BIGINT       NOT NULL,
    sku_id          BIGINT       NOT NULL,
    seckill_price   BIGINT       NOT NULL,
    original_price  BIGINT       NOT NULL,
    total_stock     INT          NOT NULL,
    available_stock INT          NOT NULL,
    per_limit       INT          NOT NULL DEFAULT 1,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_seckill_sku_act_sku ON t_seckill_sku (activity_id, sku_id);
CREATE INDEX idx_seckill_sku_sku ON t_seckill_sku (sku_id);
-- 秒杀下单台账：订单与活动配额的关联（回补配额的依据）+ 单人限购判重
CREATE TABLE t_seckill_order (
    id              BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL,
    activity_id     BIGINT       NOT NULL,
    seckill_sku_id  BIGINT       NOT NULL,
    sku_id          BIGINT       NOT NULL,
    order_no        VARCHAR(32)  NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_seckill_order_no ON t_seckill_order (order_no);
CREATE INDEX idx_seckill_order_user ON t_seckill_order (user_id, activity_id);

-- ---------- 异步导出任务（71 · 矩阵第 9 项） ----------
CREATE TABLE t_export_job (
    id                 BIGINT       NOT NULL,
    scope              VARCHAR(16)  NOT NULL,
    module             VARCHAR(24)  NOT NULL,
    params             VARCHAR(500),
    status             VARCHAR(12)  NOT NULL,
    total_rows         INT          NOT NULL DEFAULT 0,
    row_count          INT          NOT NULL DEFAULT 0,
    file_name          VARCHAR(120),
    file_path          VARCHAR(300),
    file_size          BIGINT       NOT NULL DEFAULT 0,
    error_message      VARCHAR(500),
    created_by         BIGINT       NOT NULL,
    created_at         TIMESTAMP    NOT NULL,
    started_at         TIMESTAMP,
    finished_at        TIMESTAMP,
    expires_at         TIMESTAMP,
    download_count     INT          NOT NULL DEFAULT 0,
    last_downloaded_at TIMESTAMP,
    PRIMARY KEY (id)
);
CREATE INDEX idx_export_job_owner ON t_export_job (created_by, created_at);
CREATE INDEX idx_export_job_expiry ON t_export_job (expires_at);
CREATE INDEX idx_export_job_status ON t_export_job (status, created_at);
