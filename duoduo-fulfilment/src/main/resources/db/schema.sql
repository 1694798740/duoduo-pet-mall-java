-- 履约域表结构（H2 / MySQL 8 双兼容）
--
-- 约定（与交易域一致）：
--   * 主键使用雪花 ID（BIGINT），不用自增，便于后续分库分表
--   * 时间统一 TIMESTAMP（UTC）；金额（本域暂无需）统一 BIGINT 分
--   * 幂等一律靠**唯一索引**兜底，不依赖分布式锁：
--     评价的唯一键是 (order_id, user_id, spu_id) —— 即「一单里的一件商品只能评一次」，
--     而不是「一单一评」。一个订单常含多件商品（同卖家多件合并发货），
--     强迫用户只写一条评价会迫使他把对 A 的好评与对 B 的差评写在同一段文字里，
--     商品维度的评分随即失真。唯一键选错，事后要把数据拆开几乎不可能
--   * 状态字段用 VARCHAR 存枚举名（可读、便于排查），不用数字码
--   * 评分存 INTEGER（1-5），聚合靠 SQL（AVG / GROUP BY），不落冗余汇总列：
--     汇总列一旦写错就会长期错，而评价读取是低频页面，SQL 聚合成本可接受

DROP TABLE IF EXISTS t_review;

CREATE TABLE t_review (
    id               BIGINT       NOT NULL,
    -- 订单事实的快照键：订单号 + 订单主键。订单主键用于唯一索引，
    -- 订单号用于运维按单号直查（订单号来自交易域，本域不生成）
    order_id         BIGINT       NOT NULL,
    order_no         VARCHAR(32)  NOT NULL,
    user_id          BIGINT       NOT NULL,
    seller_id        BIGINT       NOT NULL,
    -- 评价对象：SPU 维度展示（商品详情页按 SPU 聚合），SKU 维度留档（便于看清是哪个规格）
    spu_id           BIGINT       NOT NULL,
    sku_id           BIGINT,
    -- 商品快照：商品后续改名 / 换图不影响历史评价的展示
    product_title    VARCHAR(200) NOT NULL,
    product_image    VARCHAR(500),
    -- 评分 1-5；1-2 差评、3 中评、4-5 好评（口径写死在服务层，便于统一解释）
    rating           INT          NOT NULL,
    content          VARCHAR(1000),
    -- 图片 URL 列表，JSON 数组字符串（最多 9 张，服务层校验）
    images           VARCHAR(2000),
    -- 匿名标记：仅影响**展示**，user_id 始终保留（信用分与风控需要真实主体）
    anonymous        TINYINT      NOT NULL DEFAULT 0,
    -- 追评：一次机会，追加内容与时间
    append_content   VARCHAR(1000),
    append_at        TIMESTAMP    NULL,
    -- 运营审核：评价也可能含违禁内容，保留审核状态位（当前默认 APPROVED，
    -- 审核工作台接入后改为 PENDING 起）
    audit_status     VARCHAR(20)  NOT NULL DEFAULT 'APPROVED',
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL,
    PRIMARY KEY (id),
    -- 一单一商品一评价：同一订单的同一件商品只允许一条评价（并发下也拦得住）
    CONSTRAINT uk_review_order_user_spu UNIQUE (order_id, user_id, spu_id)
);
CREATE INDEX idx_review_spu ON t_review (spu_id, created_at);
CREATE INDEX idx_review_user ON t_review (user_id, created_at);
CREATE INDEX idx_review_seller ON t_review (seller_id, created_at);
CREATE INDEX idx_review_order_no ON t_review (order_no);
-- 匿名与审核状态都参与列表过滤，单列索引足够（组合区分度低，不值得再建联合索引）
CREATE INDEX idx_review_audit ON t_review (audit_status);
