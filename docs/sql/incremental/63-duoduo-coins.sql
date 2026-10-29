-- 63 · 多多币 P1 第一轮：账户 / 批次 / 流水 / 冻结 + 订单与结算快照列（R-P1）
--
-- 定位（docs/PLAN.md §7 R-P1，2026-10-26 口径固化）：
--   全站唯一积分「多多币」，1 币 = 1 分；独立于优惠券，两者可叠加。
--   金额顺序：商品总额 → 优惠券 → 多多币 → 运费 → 现金实付；币只抵商品金额，不抵运费。
--   单笔抵扣上限 = 券后商品金额的 50%；有效期 12 个月；完成订单 T+7 按「现金实付商品金额」
--   1 元 : 1 币 返币（向下取整）；退款退币、批次先到期先扣；平台承担币成本（商家结算不缩水）。
--
-- 落点：
--   * 账户 / 批次 / 流水 / 冻结在**用户域**（唯一权威）；
--   * 交易域只存订单上的币快照（用了多少币、抵了多少钱、何时返的币）；
--   * 结算域加「平台补贴」列 —— 币抵扣部分由平台补给商家，商家结算额不因币缩水。
--
-- 幂等：所有写操作走 uk_ledger_biz (user_id, biz_type, biz_no, direction) 唯一索引，
--       回调 / 调度重试不会重复发币或扣币；冻结表 uk_freeze_order 一单一冻结。
-- 并发：账户余额用条件 UPDATE（balance >= 待扣数）原子扣减，不依赖应用层先查后扣；
--       批次扣减与账户更新在同一事务。

-- ============ 用户域 ============

-- 账户：余额 = Σ 未过期批次剩余（每日对账校验）。balance 允许为负（CLAWBACK 防套利设计），
--       但 P1 没有触发扣回的路径（退款只发生在完成返币之前），负数只会来自人工调整。
CREATE TABLE t_coin_account (
    id            BIGINT    NOT NULL,
    user_id       BIGINT    NOT NULL,
    balance       BIGINT    NOT NULL DEFAULT 0 COMMENT '当前余额（币）= Σ未过期批次剩余',
    total_earned  BIGINT    NOT NULL DEFAULT 0 COMMENT '累计获得（币，含退款退回）',
    total_spent   BIGINT    NOT NULL DEFAULT 0 COMMENT '累计消耗（币，含冻结）',
    version       INT       NOT NULL DEFAULT 0 COMMENT '乐观锁版本号（条件更新配套）',
    created_at    TIMESTAMP NOT NULL,
    updated_at    TIMESTAMP NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_coin_account_user ON t_coin_account (user_id);

-- 批次：每笔获得的币一批，先到期先扣。到期日 12 个月（自然月对日，Asia/Shanghai 23:59:59）。
CREATE TABLE t_coin_lot (
    id          BIGINT    NOT NULL,
    user_id     BIGINT    NOT NULL,
    biz_type    VARCHAR(32)  NOT NULL COMMENT '来源：ORDER_EARN / ORDER_REFUND / EXPIRE_COMPENSATE / ADJUST',
    biz_no      VARCHAR(64)  NOT NULL COMMENT '来源单号（订单号 / 退款单号 / 调整单号）',
    amount      BIGINT    NOT NULL COMMENT '本批发放数量',
    remaining   BIGINT    NOT NULL DEFAULT 0 COMMENT '剩余数量',
    expire_at   TIMESTAMP NOT NULL COMMENT '到期时刻（过期后剩余清零、余额扣减）',
    created_at  TIMESTAMP NOT NULL,
    updated_at  TIMESTAMP NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_coin_lot_spend ON t_coin_lot (user_id, expire_at, remaining);
CREATE INDEX idx_coin_lot_expire ON t_coin_lot (expire_at, remaining);

-- 流水：唯一权威账本。amount 一律为正（ADJUST 允许负数表示调减）。
CREATE TABLE t_coin_ledger (
    id            BIGINT    NOT NULL,
    user_id       BIGINT    NOT NULL,
    direction     VARCHAR(16) NOT NULL COMMENT 'EARN/SPEND/REFUND/EXPIRE/CLAWBACK/ADJUST',
    amount        BIGINT    NOT NULL COMMENT '变动数量（ADJUST 允许负）',
    balance_after BIGINT    NOT NULL COMMENT '本笔之后的账户余额',
    biz_type      VARCHAR(32) NOT NULL COMMENT '业务类型（ORDER_FREEZE/ORDER_RELEASE/ORDER_REFUND/ORDER_EARN/LOT_EXPIRE/ADMIN_ADJUST…）',
    biz_no        VARCHAR(64) NOT NULL COMMENT '业务单号',
    remark        VARCHAR(255) NULL,
    created_at    TIMESTAMP NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_coin_ledger_biz ON t_coin_ledger (user_id, biz_type, biz_no, direction);
CREATE INDEX idx_coin_ledger_user ON t_coin_ledger (user_id, created_at);

-- 冻结：下单冻结所用币。支付成功 DEDUCTED（终态）；取消/超时 RELEASED（余额退回）。
CREATE TABLE t_coin_freeze (
    id          BIGINT    NOT NULL,
    user_id     BIGINT    NOT NULL,
    order_no    VARCHAR(32) NOT NULL,
    coins       BIGINT    NOT NULL,
    status      VARCHAR(16) NOT NULL COMMENT 'FROZEN / DEDUCTED / RELEASED',
    created_at  TIMESTAMP NOT NULL,
    updated_at  TIMESTAMP NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX uk_coin_freeze_order ON t_coin_freeze (order_no);
CREATE INDEX idx_coin_freeze_user ON t_coin_freeze (user_id, status);

-- 冻结时消耗的批次明细：退款退币要「退回原批次」，必须知道当初扣的是哪几批。
CREATE TABLE t_coin_freeze_lot (
    id         BIGINT   NOT NULL,
    freeze_id  BIGINT   NOT NULL,
    lot_id     BIGINT   NOT NULL,
    coins      BIGINT   NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_coin_freeze_lot ON t_coin_freeze_lot (freeze_id);

-- ============ 交易域 ============

-- 订单上的币快照：用了多少币 / 抵了多少钱 / 何时完成返币。
ALTER TABLE t_order
    ADD COLUMN coin_used BIGINT NOT NULL DEFAULT 0 COMMENT '本单使用的币数' AFTER coupon_code,
    ADD COLUMN coin_deduct_amount BIGINT NOT NULL DEFAULT 0 COMMENT '币抵扣金额（分，1币=1分）' AFTER coin_used,
    ADD COLUMN coin_earn_amount BIGINT NULL COMMENT '完成 T+7 实际返币数（返过才有值）' AFTER coin_deduct_amount,
    ADD COLUMN coin_earned_at TIMESTAMP NULL COMMENT '返币时间' AFTER coin_earn_amount;

-- 结算单：币抵扣部分由平台补贴给商家（商家结算额 = 现金实付 + 币抵扣，不因币缩水）。
ALTER TABLE t_settlement
    ADD COLUMN coin_subsidy_amount BIGINT NOT NULL DEFAULT 0 COMMENT '平台多多币补贴（分）' AFTER platform_fee;
