SET NAMES utf8mb4;
USE `duoduo_marketing`;

-- E-05 · 折扣券（2026-10-26）
--
-- 为什么加在模板表而不是券实例表：折率与封顶是「活动规则」，属于运营维护的主数据；
-- 用户手里的券只快照有效期（expire_at），规则始终以模板为准 —— 运营改折率会同时影响
-- 已领未用的券，这与「券码已经发给用户」并不冲突：用户看到的抵扣额本来就以下单时试算为准。
--
-- discount_percent：折后应付比例（百分比）。85 表示 8.5 折（付 85%，减 15%）。
--   刻意不存「折扣率 0.85」的浮点：全库金额口径是整数分，浮点会引入 0.1+0.2 类误差。
-- max_discount_amount：封顶抵扣（分）。0 = 不封顶。折扣券必须有封顶能力，
--   否则一张 8 折券在大额订单上会一次性减掉上万元。
ALTER TABLE t_coupon_template
    ADD COLUMN discount_percent INT NOT NULL DEFAULT 100
        COMMENT '折扣券折后应付比例（百分比）：85 = 8.5 折；仅 DISCOUNT 使用'
        AFTER discount_amount,
    ADD COLUMN max_discount_amount BIGINT NOT NULL DEFAULT 0
        COMMENT '折扣券封顶抵扣（分）：0 = 不封顶；仅 DISCOUNT 使用'
        AFTER discount_percent;
