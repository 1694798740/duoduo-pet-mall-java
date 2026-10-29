-- 订单已读集合（2026-10-27 · 「阅后即消」角标）
--
-- 背景：列表页签角标此前直接取「该状态的订单总数」，于是商家/买家把「已关闭」「已退款」
-- 这一类**终态**订单翻来覆去看过之后，角标永远挂在那里（用户原话：看完角标常驻）。
--
-- 拆法：把角标分成两类
--   · 持续提醒（待支付/待发货/待收货）—— 存量事实，读过也必须保留；
--   · 阅后即消（已关闭/已退款 ↓买家；已发货/已完成/已关闭/已退款 ↓卖家）—— 存量中「我还没看过」的。
-- 后者需要逐条已读状态，因此本表与通知的 t_social_notice_read 同构：
--   (user_id, order_id) 复合主键，撞主键即幂等。
--
-- 为什么一张表同时覆盖买家与卖家：order_id 全局唯一，同一个用户对同一订单只可能有一种角色，
-- 不需要再加 role 列（加了反而要保证两处不会写重）。
--
-- 已有环境手工执行：
--   docker exec -i duoduo-mysql mysql -uroot -pduoduo_root duoduo_trade \
--     < docs/sql/incremental/66-duoduo-trade-order-read.sql
CREATE TABLE IF NOT EXISTS t_order_read (
    user_id   BIGINT    NOT NULL,
    order_id  BIGINT    NOT NULL,
    read_at   TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id, order_id)
);
