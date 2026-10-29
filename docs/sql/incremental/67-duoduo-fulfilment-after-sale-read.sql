-- 售后已读集合（2026-10-27 · 「阅后即消」角标）
--
-- 与 t_order_read 同构（复合主键 + 幂等）。只影响角标，不改变售后单任何业务状态。
--
-- 可消状态：仅终态 REFUNDED / CLOSED。其余（待卖家处理 / 已拒绝 / 待寄回 / 待收货 / 仲裁中）
-- 都是「还没走完的事」，看过也必须继续提醒 —— 与订单侧「待支付/待发货/待收货不减」同一条规则。
--
-- 已有环境手工执行：
--   docker exec -i duoduo-mysql mysql -uroot -pduoduo_root duoduo_fulfilment \
--     < docs/sql/incremental/67-duoduo-fulfilment-after-sale-read.sql
CREATE TABLE IF NOT EXISTS t_after_sale_read (
    user_id       BIGINT    NOT NULL,
    after_sale_id BIGINT    NOT NULL,
    read_at       TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id, after_sale_id)
);
