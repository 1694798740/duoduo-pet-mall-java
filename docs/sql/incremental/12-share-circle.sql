-- ============================================================================
-- 第四十三轮 · 分享圈：履约库增量 DDL（对已有环境执行；全新环境直接跑 04 全量脚本）
-- ============================================================================
SET NAMES utf8mb4;
USE `duoduo_fulfilment`;

-- 商品分享（分享圈）：一条记录 = 「某用户带着自己写的评价分享了一个商品」。
-- 主键为雪花 ID（应用侧生成），与全库约定一致，不用自增。
CREATE TABLE IF NOT EXISTS t_product_share (
    id             BIGINT       NOT NULL,
    user_id        BIGINT       NOT NULL,
    -- 分享对象：SPU 维度（分享是口碑行为，不细分到规格）
    spu_id         BIGINT       NOT NULL,
    -- 商品快照：发布时点的标题 / 主图 / 最低价（分）。
    -- 商品后续改名换图不影响历史分享的展示（与评价快照同一不变性原理）
    product_title  VARCHAR(200) NOT NULL,
    product_image  VARCHAR(500),
    min_price      BIGINT       NOT NULL DEFAULT 0,
    -- 用户自撰评价内容（发布入口强制非空，服务层校验，上限 500 字）
    content        VARCHAR(500) NOT NULL,
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);
-- 信息流按发布时间倒序分页：联合索引直接覆盖排序
CREATE INDEX idx_share_time ON t_product_share (created_at);
CREATE INDEX idx_share_user ON t_product_share (user_id, created_at);
-- 商品维度的查询（如「这个商品被分享了多少次」）：低频运维口径，单列够用
CREATE INDEX idx_share_spu ON t_product_share (spu_id);
