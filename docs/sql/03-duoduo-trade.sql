-- ============================================================================
-- 多多宠物商店 · 交易库：建库 + 建表（容器化初始化用）
-- ============================================================================
-- 表定义**直接取自** duoduo-trade/src/main/resources/db/schema.sql（双兼容，无 ENGINE 子句，
-- MySQL 会套用服务端默认的 InnoDB + utf8mb4）。改动表结构请改该源文件并重新生成本文件。
-- SET NAMES 必须显式声明，否则中文会被双重编码（参见 I-08）。
-- ============================================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `duoduo_trade`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `duoduo_trade`;
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
    -- 本单使用的优惠券券码（未用券为 NULL）。A5 新增。
    -- 为什么必须落库：订单详情要展示「抵了多少、用的哪张券」，取消 / 关单要凭它去营销域解锁、
    -- 支付成功要凭它核销。只记金额不记券码，「这 30 块是哪张券抵的」就永远答不上来。
    coupon_code       VARCHAR(32),
    -- 多多币快照（R-P1）：本单使用的币数与抵扣金额（1 币 = 1 分，只抵商品金额不抵运费）。
    -- 为什么必须落库：取消 / 关单要凭 coin_used 释放冻结，支付成功要凭它确认扣减与补贴商家，
    -- 完成 T+7 返币要凭它算「现金实付商品金额」。只算不存，「这单到底用了多少币」
    -- 在回调重放时就没法幂等地回答。
    coin_used         BIGINT       NOT NULL DEFAULT 0,
    coin_deduct_amount BIGINT      NOT NULL DEFAULT 0,
    coin_earn_amount  BIGINT,
    coin_earned_at    TIMESTAMP,
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
-- 幂等键唯一性必须带卖家维度：多卖家购物车会拆成多张子单，子单共享同一个客户端幂等键。
-- 只按 (user_id, idempotent_key) 唯一，第二张子单必然撞键（2026-10-22 事故根因）。
CREATE UNIQUE INDEX uk_order_user_idem ON t_order (user_id, idempotent_key, seller_id);
CREATE INDEX idx_order_user ON t_order (user_id, status);
CREATE INDEX idx_order_seller ON t_order (seller_id, status);
CREATE INDEX idx_order_expire ON t_order (status, expire_at);
-- 自动确认收货的扫描条件（已发货 + 发货时间）走这条索引
CREATE INDEX idx_order_auto_receipt ON t_order (status, shipped_at);

-- ---------- 订单明细（下单时刻快照） ----------
CREATE TABLE t_order_item (
    id             BIGINT        NOT NULL,
    order_id       BIGINT        NOT NULL,
    -- C2C 闲置单（B-11）：listing_id 必填、spu_id/sku_id 为 NULL（闲置不在 SKU 体系）；
    -- 商城订单相反：spu_id/sku_id 必填、listing_id 为 NULL。互斥由写入端保证。
    listing_id     BIGINT        NULL                COMMENT '闲置 id（C2C 闲置单专有，B-11）',
    spu_id         BIGINT        NULL                COMMENT 'SPU id（C2C 闲置单为 NULL）',
    sku_id         BIGINT        NULL                COMMENT 'SKU id（C2C 闲置单为 NULL）',
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
CREATE INDEX idx_item_listing ON t_order_item (listing_id);

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
    code_url          MEDIUMTEXT,
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

-- ---------- 重复收款自动退款台账（B-04） ----------
-- 同一订单被第二张支付单多收的钱：检出时（支付单置 CLOSED 且写明重复支付）在**同一事务**
-- 里落一条退款台账，由补偿调度经渠道退款接口自动退回买家（原路退回）。
-- 状态机：INIT → SUCCESS（唯一成功终态）；渠道不可用/处理中保持 INIT 由调度重试。
-- 幂等：uk_refund_pay_no 一张支付单最多一笔退款（INSERT IGNORE 撞唯一索引）；
--       渠道级幂等靠 refund_no（微信 out_refund_no / 支付宝 out_request_no）。
CREATE TABLE t_payment_refund (
    id                BIGINT       NOT NULL,
    refund_no         VARCHAR(32)  NOT NULL,
    pay_no            VARCHAR(32)  NOT NULL,
    order_id          BIGINT       NOT NULL,
    user_id           BIGINT       NOT NULL,
    channel           VARCHAR(32)  NOT NULL,
    channel_trade_no  VARCHAR(64)  NULL,
    amount            BIGINT       NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    fail_reason       VARCHAR(255) NULL,
    channel_refund_no VARCHAR(64)  NULL,
    refunded_at       TIMESTAMP    NULL,
    created_at        TIMESTAMP    NOT NULL,
    updated_at        TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_refund_no ON t_payment_refund (refund_no);
CREATE UNIQUE INDEX uk_refund_pay_no ON t_payment_refund (pay_no);
CREATE INDEX idx_refund_user ON t_payment_refund (user_id, created_at);
CREATE INDEX idx_refund_status ON t_payment_refund (status, created_at);

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
    -- 平台多多币补贴（R-P1）：amount 已含币抵扣部分（商家结算不因币缩水），
    -- 这一列单记「其中多少是平台替买家出的币」，供「平台补贴 = 订单币抵扣合计」对账。
    coin_subsidy_amount BIGINT NOT NULL DEFAULT 0,
    status        VARCHAR(20)  NOT NULL,
    release_at    TIMESTAMP,
    released_at   TIMESTAMP,
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_settle_order ON t_settlement (order_id);
CREATE INDEX idx_settle_seller ON t_settlement (seller_id, status);

-- ---------- 卖家欠款（第五十八轮 · B-01：平台垫付后的追偿账） ----------
-- 钱一旦放款给卖家，托管单就是 RELEASED 终态，「买家的钱」在账上已经不存在了。
-- 此时要退款给买家，只能是**平台先垫付** —— 于是这笔钱从「买家 ↔ 卖家」变成「平台 ↔ 卖家」的债。
-- 没有这张表，这笔债只存在于口头记录里：结算时无从抵扣、对账时对不上、也没法追责。
-- 唯一键 uk_seller_debt_order(order_id) 保证一单最多垫付一次（幂等落在数据库，不靠应用层先查）。
CREATE TABLE t_seller_debt (
    id          BIGINT      NOT NULL COMMENT '雪花 id',
    seller_id   BIGINT      NOT NULL COMMENT '欠款卖家',
    order_id    BIGINT      NOT NULL COMMENT '关联订单 id',
    order_no    VARCHAR(32) NOT NULL COMMENT '订单号（冗余，便于对账与人工查询）',
    amount      BIGINT      NOT NULL COMMENT '欠款金额（分）= 平台垫付给买家的金额',
    status      VARCHAR(20) NOT NULL DEFAULT 'OUTSTANDING' COMMENT 'OUTSTANDING 未收回 / SETTLED 已收回 / WRITTEN_OFF 已核销',
    reason      VARCHAR(200) COMMENT '垫付原因（运营填写，进对账口径）',
    created_by  BIGINT      COMMENT '操作人（运营 userId）',
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    updated_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（UTC）',
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_seller_debt_order ON t_seller_debt (order_id);
CREATE INDEX idx_seller_debt_seller ON t_seller_debt (seller_id, status);

-- ---------- 代运营工单（E23） ----------
-- 平台对第三方店铺的经营资产（替他发货 / 替他裁售后 / 替他下架）默认无权直接操作；
-- 唯一的例外必须有**授权凭证**与**事由** —— 工单就是那个凭证。
-- 没有它，「平台替商家做了操作」在库里只是一行审计记录，回答不了「商家同意了吗」。
-- 一次性消费（OPEN → CONSUMED）：状态只有三个，已消费的**不允许**回到 OPEN。
-- 商品库有一张同名同构的表（见 02-duoduo-product.sql），理由见 incremental/33。
CREATE TABLE t_proxy_work_order (
    id                BIGINT       NOT NULL COMMENT '雪花 id',
    work_order_no     VARCHAR(48)  NOT NULL COMMENT '工单号（WO-ORD- 前缀）',
    tenant_id         BIGINT       NOT NULL COMMENT '被代运营的店铺主体 userId（= owner_user_id）',
    target_type       VARCHAR(16)  NOT NULL COMMENT 'ORDER / PRODUCT（本库只允许 ORDER）',
    target_ref        VARCHAR(64)  NOT NULL COMMENT '目标对象：ORDER 存订单号',
    action_type       VARCHAR(32)  NOT NULL COMMENT 'SHIP / AFTERSALE / OFF_SHELF',
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

-- ---------- 削峰下单任务台账（A11） ----------
-- 异步下单的中间态落点：受理 PENDING / 成功 PLACED / 业务失败 FAILED（带原因）。
-- 幂等靠唯一键（与订单表幂等键同构）：双击/重试重复入队撞索引，返回已有任务。
-- 系统类失败在重试耗尽进死信期间任务保持 PENDING —— 「还没做完」和「做不成了」是两回事。
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
-- ---------- 对账轮次与差异明细（S3：钱与库存每日自动对账） ----------
-- 口径详见 docs/sql/incremental/34-s3-recon.sql：一天一个 (biz_date, scope) 唯一，
-- 同日重跑覆盖而非累积；FAILED（没跑成）必须与 CLEAN（跑了且干净）分得开。
CREATE TABLE t_recon_run (
    id            BIGINT       NOT NULL,
    biz_date      DATE         NOT NULL,
    scope         VARCHAR(20)  NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    checked_count INT          NOT NULL DEFAULT 0,
    diff_count    INT          NOT NULL DEFAULT 0,
    high_count    INT          NOT NULL DEFAULT 0,
    started_at    TIMESTAMP    NOT NULL,
    finished_at   TIMESTAMP    NULL,
    message       VARCHAR(500) NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_recon_run_day_scope ON t_recon_run (biz_date, scope);
CREATE INDEX idx_recon_run_recent ON t_recon_run (started_at);
CREATE TABLE t_recon_diff (
    id         BIGINT       NOT NULL,
    run_id     BIGINT       NOT NULL,
    rule       VARCHAR(48)  NOT NULL,
    severity   VARCHAR(10)  NOT NULL,
    ref_type   VARCHAR(20)  NOT NULL,
    ref_id     BIGINT       NOT NULL,
    expected   VARCHAR(60)  NULL,
    actual     VARCHAR(60)  NULL,
    detail     VARCHAR(300) NULL,
    created_at TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_recon_diff ON t_recon_diff (run_id, rule, ref_type, ref_id);
CREATE INDEX idx_recon_diff_run ON t_recon_diff (run_id, severity);
CREATE INDEX idx_recon_diff_ref ON t_recon_diff (ref_type, ref_id);

-- ---------- 异步导出任务（71 · 矩阵第 9 项） ----------
-- 超过 5 万行的导出不再同步返回文件，而是落一行任务、后台分页写盘、完成后推站内通知。
-- 文件生存期 7 天，过期由调度删行 + 删文件；下载必须登录且只能下自己创建的任务。
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
