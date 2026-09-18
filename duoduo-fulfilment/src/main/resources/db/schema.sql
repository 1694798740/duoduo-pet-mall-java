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

DROP TABLE IF EXISTS t_after_sale;
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

-- ---------- 售后单（退款争议的立案与裁定，A3） ----------
-- 为什么单独建表而不是在订单上加几个字段：
--   一次争议有「谁提的、提的什么、卖家怎么答的、平台怎么判的、判的说明是什么」一串信息，
--   塞进订单表等于把订单变成流水账本；而且一个订单将来可能立多次不同类型的售后。
--
-- 钱不在这张表里：refund_amount 只是交易域退款结果的**回填**，
--   真正的资金动作由 t_settlement 的 CAS 保证只执行一次。
--
-- status 取值（见 AfterSaleStatus）：
--   PENDING          待卖家处理
--   SELLER_REJECTED  卖家已拒绝（买家可继续申请平台仲裁）
--   ARBITRATING      平台仲裁中
--   REFUNDED         已退款（终态）
--   CLOSED           仲裁驳回 / 关闭（终态）
--
-- 所有状态迁移都是 CAS 条件更新（把「当前必须是 X」写进 WHERE），
--   并发下（卖家点同意、运营点裁定）只会有一次生效 —— 幂等不靠锁，靠数据库行级原子性。
CREATE TABLE t_after_sale (
    id                BIGINT       NOT NULL,
    -- 订单事实的快照键：订单号由订单号派生稳定哈希（与评价域共用 OrderRefs，
    -- 保证同一订单在两个子域里得到同一个内部标识）
    order_id          BIGINT       NOT NULL,
    order_no          VARCHAR(32)  NOT NULL,
    -- 归属取自订单事实（内部接口），不接受请求体传入
    buyer_id          BIGINT       NOT NULL,
    seller_id         BIGINT       NOT NULL,
    -- REFUND_ONLY（仅退款）/ RETURN_REFUND（退货退款）
    type              VARCHAR(20)  NOT NULL,
    reason            VARCHAR(500) NOT NULL,
    -- 举证图片 URL 列表，JSON 数组字符串（只收 http/https 直链，服务层校验）
    images            VARCHAR(2000),
    status            VARCHAR(30)  NOT NULL,
    -- 卖家处理说明（AGREE 记动作 / REJECT 必填原因）
    seller_reply      VARCHAR(500),
    -- 仲裁结论与说明：裁定说明必填，是事后复盘与客诉处理的唯一凭据
    arbitrate_result  VARCHAR(20),
    arbitrate_note    VARCHAR(500),
    -- 裁定人（运营 userId）：只用于内部留痕，不对外返回
    arbitrated_by     BIGINT,
    -- 实际退款金额（分），由交易域退款结果回填；本域不自己算金额
    refund_amount     BIGINT,
    created_at        TIMESTAMP    NOT NULL,
    updated_at        TIMESTAMP    NOT NULL,
    PRIMARY KEY (id),
    -- 同一订单的同一类型售后只允许一条：重复立案是预期内的业务结果，
    -- 靠唯一索引兜底转 409，而不是靠「先查再插」在并发下漏掉
    CONSTRAINT uk_aftersale_order_type UNIQUE (order_id, type)
);
CREATE INDEX idx_aftersale_buyer ON t_after_sale (buyer_id, created_at);
-- 卖家端与运营工作台都按状态过滤列表，索引带上 status 才有用
CREATE INDEX idx_aftersale_seller ON t_after_sale (seller_id, status, created_at);
CREATE INDEX idx_aftersale_status ON t_after_sale (status, created_at);
-- 运维按订单号直查（订单号来自交易域，本域不生成）
CREATE INDEX idx_aftersale_order_no ON t_after_sale (order_no);
