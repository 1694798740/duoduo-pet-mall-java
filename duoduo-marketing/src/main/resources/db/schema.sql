-- 营销域表结构（H2 / MySQL 8 双兼容）
--
-- 约定（与交易域 / 履约域一致）：
--   * 主键使用雪花 ID（BIGINT），不用自增，便于后续分库分表
--   * 时间统一 TIMESTAMP（UTC）；金额统一 BIGINT「分」，绝不用浮点
--   * 幂等一律靠**唯一索引**兜底，不依赖分布式锁（INV-1）
--   * 状态字段用 VARCHAR 存枚举名（可读、便于排查），不用数字码
--
-- A5 的两张表刻意拆开：
--   模板（t_coupon_template）是运营维护的主数据，一张模板可被领 N 张；
--   实例（t_coupon）才是「用户手上那一张」，状态机在它身上。
--   合成一张表会让「改模板文案」牵动用户的券数据，也会让唯一索引无从定义。

DROP TABLE IF EXISTS t_coupon;
DROP TABLE IF EXISTS t_coupon_template;

-- ---------------------------------------------------------------------------
-- 券模板：运营创建，买家领取的依据
-- ---------------------------------------------------------------------------
CREATE TABLE t_coupon_template (
    id                  BIGINT       NOT NULL,
    name                VARCHAR(100) NOT NULL,
    description         VARCHAR(500),
    -- 券类型。v1 只实现 FULL_REDUCE（满减）：折扣券涉及「百分比 × 金额」的取整与封顶口径，
    -- 在没有产品拍板前不引入未实现的分支 —— 传其他值一律 422 并明确提示「暂不支持」。
    type                VARCHAR(20)  NOT NULL DEFAULT 'FULL_REDUCE',
    -- 满减门槛（分）：订单商品总额 >= 门槛才能用，否则 422
    threshold_amount    BIGINT       NOT NULL DEFAULT 0,
    -- 抵扣金额（分）：满减券的固定抵扣额
    discount_amount     BIGINT       NOT NULL DEFAULT 0,
    -- 发放总量与已领数量。已领靠 UPDATE ... SET claimed_count = claimed_count + 1
    -- 配合 WHERE claimed_count < total_count 做 CAS 发放，不靠「先查再插」。
    total_count         INT          NOT NULL DEFAULT 0,
    claimed_count       INT          NOT NULL DEFAULT 0,
    -- 领取窗口：不在这个区间内不可领
    start_at            TIMESTAMP    NOT NULL,
    end_at              TIMESTAMP    NOT NULL,
    -- 状态：ACTIVE（可领）/ DISABLED（停用）。停用只影响**新领取**，已领到的券仍可用
    -- （把用户手里的券凭空作废是最招投诉的一种做法）。
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at          TIMESTAMP    NOT NULL,
    updated_at          TIMESTAMP    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_tpl_status_window (status, start_at, end_at)
);

-- ---------------------------------------------------------------------------
-- 券实例：用户手上那一张券
-- ---------------------------------------------------------------------------
CREATE TABLE t_coupon (
    id              BIGINT       NOT NULL,
    -- 券码：对外唯一标识，下单时凭它锁定。用随机串而非主键——主键出到前端会被
    -- JS 的 Number.MAX_SAFE_INTEGER 静默改精度（INV-16），券码天然规避这个坑。
    code            VARCHAR(32)  NOT NULL,
    template_id     BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL,
    -- 状态机（support/CouponStatus）：UNUSED → LOCKED → USED
    --                                      ↘ EXPIRED（过期 / 解锁后已过期）
    status          VARCHAR(20)  NOT NULL DEFAULT 'UNUSED',
    claimed_at      TIMESTAMP    NOT NULL,
    -- 有效期截止：领券时按模板的 end_at 快照下来，之后运营改模板不影响已领的券
    expire_at       TIMESTAMP    NOT NULL,
    -- 锁定记录：被哪个订单锁住、什么时候。解锁时清掉，让券回到 UNUSED
    locked_order_no VARCHAR(32),
    locked_at       TIMESTAMP    NULL,
    -- 核销记录：被哪个订单用掉、什么时候。核销是终态，不可回退
    used_order_no   VARCHAR(32),
    used_at         TIMESTAMP    NULL,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    PRIMARY KEY (id),
    -- 领券幂等：**同一用户同一模板只能领一张**（v1 口径）。
    -- 靠唯一索引而不是「先查再插」——并发点击领券时后者必然漏。
    UNIQUE KEY uk_coupon_user_tpl (user_id, template_id),
    UNIQUE KEY uk_coupon_code (code),
    KEY idx_coupon_user_status (user_id, status),
    -- 服务解锁兜底扫描用：找「锁定超过 TTL 仍未支付」的券
    KEY idx_coupon_release (status, locked_at)
);
