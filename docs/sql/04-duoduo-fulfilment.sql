-- ============================================================================
-- 多多宠物商店 · 履约库：建库 + 建表（容器化初始化用）
-- ============================================================================
-- 表定义**直接取自** duoduo-fulfilment/src/main/resources/db/schema.sql（双兼容，无 ENGINE 子句，
-- MySQL 会套用服务端默认的 InnoDB + utf8mb4）。改动表结构请改该源文件并重新生成本文件。
-- SET NAMES 必须显式声明，否则中文会被双重编码（参见 I-08）。
-- ============================================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `duoduo_fulfilment`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `duoduo_fulfilment`;
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
    -- 运营审核：评价也可能含违禁内容；H-02 起后台可人工下架 / 恢复，A-15 起提交时先过机审
    audit_status     VARCHAR(20)  NOT NULL DEFAULT 'APPROVED',
    -- 审核结论说明（下架必填、恢复清空）、操作人快照与时间（UTC）、来源，供追责与列表展示
    audit_reason     VARCHAR(255),
    audited_by       BIGINT,
    audited_at       TIMESTAMP    NULL,
    -- AUTO 机审自动 / MANUAL 人工 / NULL 为 A-15 之前的历史数据
    audit_source     VARCHAR(20),
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
    -- 退货退款的寄回信息（第五十八轮 P1-5）：只有 RETURN_REFUND 会用到，
    -- 两个时刻即两个节点 —— 寄回（买家填单号）与签收（卖家确认，退款在此刻发生）
    return_courier      VARCHAR(64),
    return_tracking_no  VARCHAR(64),
    return_shipped_at   TIMESTAMP,
    return_received_at  TIMESTAMP,
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
-- 为什么单独建一张「节点流水」表，而不是在订单上加一个 track 字段：
--   轨迹是**一组**事实，不是**一个**事实。包裹从揽收到签收会陆续产生 N 条节点，
--   塞进订单字段只能存「最新一条」，买家想看的「什么时候到哪里了」直接丢失。
--   而且节点由外部推送、随时追加、还会重推，放在订单表里等于把订单变成流水的写入热点。
--
-- 与订单的关系是**弱引用**：本表只存 order_no / order_id 快照，
--   订单状态真相仍在交易域（本域不复制订单状态机）—— 同一事实两处存必然漂移。
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
    -- 第五十六轮：评论数（冗余列，事实源 t_share_comment）
    comment_count  INT          NOT NULL DEFAULT 0,
    -- 第五十六轮：软删时间（非 NULL = 已删除）。
    -- 必须软删不能物理删 —— 转发单的「原帖引用」是查询时回查原帖行得到的，
    -- 物理删掉原帖会让转发单的引用块变成空内容。
    deleted_at     TIMESTAMP    NULL,
    -- 第五十六轮：内容编辑时间；NULL = 未编辑过（前端据此显示「已编辑」）
    edited_at      TIMESTAMP    NULL,
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

-- ---------- 分享圈评论（第五十六轮） ----------
-- 二级结构：parent_comment_id IS NULL = 一级评论；非 NULL = 对某条一级评论的回复。
-- 刻意只做二级 —— 二级以上要处理「回复谁的回复」的折叠与 @ 链路，属另一类复杂度。
CREATE TABLE t_share_comment (
    id                BIGINT       NOT NULL,
    share_id          BIGINT       NOT NULL,
    user_id           BIGINT       NOT NULL,
    parent_comment_id BIGINT       NULL,
    content           VARCHAR(300) NOT NULL,
    like_count        INT          NOT NULL DEFAULT 0,
    -- 运营处理举报时「下架评论」置此列，不物理删（留痕，也保住回复链）
    deleted_at        TIMESTAMP    NULL,
    created_at        TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_share_comment_share ON t_share_comment (share_id, parent_comment_id, created_at);
CREATE INDEX idx_share_comment_parent ON t_share_comment (parent_comment_id);
CREATE INDEX idx_share_comment_user ON t_share_comment (user_id, created_at);

-- 评论点赞（与分享点赞同构：复合主键即唯一约束，并发重复插入撞键幂等）
CREATE TABLE t_share_comment_like (
    comment_id BIGINT    NOT NULL,
    user_id    BIGINT    NOT NULL,
    created_at TIMESTAMP NOT NULL,
    PRIMARY KEY (comment_id, user_id)
);

-- ---------- 分享圈举报（第五十六轮） ----------
-- SHARE 与 COMMENT 合成一张表：处理流程完全一致，拆两张等于把同一套运营逻辑写两遍。
CREATE TABLE t_share_report (
    id          BIGINT       NOT NULL,
    target_type VARCHAR(16)  NOT NULL,
    target_id   BIGINT       NOT NULL,
    reporter_id BIGINT       NOT NULL,
    reason      VARCHAR(200) NOT NULL,
    -- PENDING | ACCEPTED（成立，内容已下架）| REJECTED（不成立）
    status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    handled_by  BIGINT       NULL,
    handled_at  TIMESTAMP    NULL,
    handle_note VARCHAR(200) NULL,
    created_at  TIMESTAMP    NOT NULL,
    PRIMARY KEY (id),
    -- 同一人对同一对象只能举报一次（唯一键兜住并发重复提交）
    CONSTRAINT uk_share_report_once UNIQUE (target_type, target_id, reporter_id)
);
CREATE INDEX idx_share_report_status ON t_share_report (status, created_at);
CREATE INDEX idx_share_report_target ON t_share_report (target_type, target_id);

-- ---------- 分享圈话题标签（第五十六轮） ----------
CREATE TABLE t_share_topic (
    id          BIGINT      NOT NULL,
    -- 话题名（不含 # 号）；唯一键兜住「两人同时新建同名话题」的并发双写
    name        VARCHAR(30) NOT NULL,
    share_count INT         NOT NULL DEFAULT 0,
    created_at  TIMESTAMP   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_share_topic_name UNIQUE (name)
);
CREATE INDEX idx_share_topic_count ON t_share_topic (share_count);

-- 分享 ↔ 话题（一条分享最多 2 个话题；主键即去重键）
CREATE TABLE t_share_topic_rel (
    share_id BIGINT NOT NULL,
    topic_id BIGINT NOT NULL,
    PRIMARY KEY (share_id, topic_id)
);
-- 话题聚合页按话题查分享：主键左侧是 share_id，故补一条反向索引
CREATE INDEX idx_share_topic_rel_topic ON t_share_topic_rel (topic_id, share_id);

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
