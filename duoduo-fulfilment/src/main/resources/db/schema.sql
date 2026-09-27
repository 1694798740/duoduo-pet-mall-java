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

DROP TABLE IF EXISTS t_logistics_track;
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

-- ---------- 物流轨迹节点（A2） ----------
-- 为什么单独建表而不是在订单上加字段：
--   一次运输会产生多条节点，数量事先不可知；塞进订单只能存成 JSON 大字段，
--   于是「查某条节点」「按类型统计」都退化成字符串匹配 —— 那是把结构化数据写成了日志。
--
-- 幂等键是 uk_track_order_node_time（订单号 + 节点码 + 发生时间）：
--   物流推送的常见形态就是失败重推 / 补推 / 定时全量对齐，同一条节点被推两次是正常的。
--   靠唯一索引把重复静默吃掉，而不是「先查再插」—— 后者在并发重推下必然漏。
--
-- 时间存 occurred_at（节点**发生**时间）而不是入库时间：
--   推送可能延迟或补推，入库时间只反映我们何时收到，反映不了包裹何时到。
--   排序与展示一律用 occurred_at，全部按 UTC 存（INV-12）。
--
-- node_code 取值见 LogisticsNode（白名单，推送方只能选用不能自创）：
--   PICKED_UP / IN_TRANSIT / ARRIVED_HUB / OUT_FOR_DELIVERY / SIGNED / REJECTED / EXCEPTION
--
-- raw 是上报方的原始报文（JSON），只入库**不对外返回** —— 用于轨迹被质疑时回溯，
--   以及将来对接真实快递公司报文时不必改表结构。
CREATE TABLE t_logistics_track (
    id           BIGINT       NOT NULL,
    -- 订单事实的快照键：order_id 由订单号派生稳定哈希（与评价 / 售后共用 OrderRefs，
    -- 保证同一订单在三个子域里得到同一个内部标识）
    order_id     BIGINT       NOT NULL,
    order_no     VARCHAR(32)  NOT NULL,
    node_code    VARCHAR(30)  NOT NULL,
    -- 节点描述原文（推送方给的细节）；推送方没给时用节点名的标准文案兜底
    node_text    VARCHAR(200) NOT NULL,
    occurred_at  TIMESTAMP    NOT NULL,
    raw          VARCHAR(1000),
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL,
    PRIMARY KEY (id),
    -- 幂等键：同订单 + 同节点 + 同发生时间 = 同一条事实，重复推送撞这里
    CONSTRAINT uk_track_order_node_time UNIQUE (order_no, node_code, occurred_at)
);
-- 查询固定是「按订单号取全部节点并按时间正序」，这个联合索引直接覆盖排序
CREATE INDEX idx_track_order_time ON t_logistics_track (order_no, occurred_at);

-- ---------- 商品分享（分享圈，第四十三轮；第四十六轮加转发层） ----------
-- 一条记录 = 「某用户带着自己写的评价分享了一个商品」。
-- 商品字段是发布时点的快照：商品后续改名换图，历史分享仍按分享那一刻的样子展示。
-- 转发 = 又一次分享：转发行有 parent_share_id 指向它转的原帖，user 是转发人。
CREATE TABLE t_product_share (
    id             BIGINT       NOT NULL,
    user_id        BIGINT       NOT NULL,
    -- 第四十六轮：被转发的原分享单 ID；NULL = 原创
    parent_share_id BIGINT      NULL,
    -- 分享对象：SPU 维度（分享是口碑行为，不细分到规格）
    spu_id         BIGINT       NOT NULL,
    -- 商品快照：发布时点的标题 / 主图 / 最低价（分）
    product_title  VARCHAR(200) NOT NULL,
    product_image  VARCHAR(500),
    min_price      BIGINT       NOT NULL DEFAULT 0,
    -- 用户自撰评价内容（原创发布入口强制非空；转发时是转附言，可为空串）
    content        VARCHAR(500) NOT NULL,
    -- 第四十四轮：互动计数（冗余列，事实源是 t_share_like / t_share_favorite）
    like_count     INT          NOT NULL DEFAULT 0,
    favorite_count INT          NOT NULL DEFAULT 0,
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL,
    PRIMARY KEY (id),
    -- 第四十六轮：一人对同一条原帖只能转发一次。
    -- MySQL 唯一索引把 NULL 视为互不相等 —— 原创行不受约束，只有重复转发才撞键。
    CONSTRAINT uk_share_user_parent UNIQUE (user_id, parent_share_id)
);
-- 信息流按发布时间倒序分页：联合索引直接覆盖排序
CREATE INDEX idx_share_time ON t_product_share (created_at);
CREATE INDEX idx_share_user ON t_product_share (user_id, created_at);
-- 商品维度的查询（如「这个商品被分享了多少次」）：低频运维口径，单列够用
CREATE INDEX idx_share_spu ON t_product_share (spu_id);
-- 原帖的转发列表 / 转发链路回溯（第四十六轮）
CREATE INDEX idx_share_parent ON t_product_share (parent_share_id);

-- ---------- 分享圈互动（点赞 / 收藏，第四十四轮） ----------
-- 一人一分享一票：复合主键即唯一约束，并发重复插入撞键幂等。
-- 计数冗余在 t_product_share（信息流高频读），这两张表是事实源。
CREATE TABLE t_share_like (
    share_id   BIGINT    NOT NULL,
    user_id    BIGINT    NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (share_id, user_id)
);
CREATE INDEX idx_share_like_user ON t_share_like (user_id);

CREATE TABLE t_share_favorite (
    share_id   BIGINT    NOT NULL,
    user_id    BIGINT    NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (share_id, user_id)
);
CREATE INDEX idx_share_fav_user ON t_share_favorite (user_id);
