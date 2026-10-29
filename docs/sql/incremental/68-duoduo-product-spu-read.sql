-- 商品已读集合（2026-10-27 · 「阅后即消」角标）
--
-- 与 t_order_read / t_after_sale_read 同构（复合主键 + 幂等）。只影响角标，不改变商品任何业务状态。
--
-- 可消状态按角色不同（同一个状态对买卖双方含义相反）：
--   · 商家视角：APPROVED 可消（过审只是通知，不用卖家动）；
--               REJECTED / PENDING 必须留（驳回要改、待审要等，都是还没落定的事）
--   · 运营视角：APPROVED / REJECTED 可消（已经处理过的审核单）；
--               PENDING 必须留（待我审）
-- 这两个视角共用本表，靠 user_id 区分（商家看自己的 id，运营看运营 id）。
--
-- 已有环境手工执行：
--   docker exec -i duoduo-mysql mysql -uroot -pduoduo_root duoduo_product \
--     < docs/sql/incremental/68-duoduo-product-spu-read.sql
CREATE TABLE IF NOT EXISTS t_spu_read (
    user_id  BIGINT    NOT NULL,
    spu_id   BIGINT    NOT NULL,
    read_at  TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id, spu_id)
);
