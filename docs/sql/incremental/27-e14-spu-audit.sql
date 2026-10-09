-- ============================================================================
-- E14 · 第三方商品上架审核：商品库增量 DDL
-- （对已有环境执行；全新环境直接跑 02-duoduo-product.sql，两处已同步）
-- ============================================================================
SET NAMES utf8mb4;
USE `duoduo_product`;

-- ---------------------------------------------------------------------------
-- t_spu 补审核维度四列
-- ---------------------------------------------------------------------------
-- 为什么把审核做成**独立列**而不是往 status 里塞新值：
--   status 是「交易维度」（在售 / 下架 / 卖家可控），审核是「合规维度」（平台判定）。
--   两者正交：商品可以「已过审但被卖家下架」，也会出现「驳回件等卖家改内容」。
--   挤进同一列后无法表达这两种组合，而且任何新增状态值都会让散落各处的
--   status='ON_SALE' 判定的语义跟着变 —— 那是最难排查的一类改动。
--   这套「交易维度 + 审核维度双列」的口径与 t_listing（C2C 闲置）一致，
--   全站的商品审核语义只有一套解释。
--
-- ★ 默认值为什么是 'APPROVED'（而不是 t_listing 那样的 'PENDING'）：
--   ① 审核链是**新增能力**，本表已有存量商品（平台自营）全部在售中。
--      默认 PENDING 会让它们在迁移完成的瞬间整店消失 —— 这是纯人为事故；
--   ② **平台自营商品免审**：平台不需要审核自己发布的商品，supply_type='self'
--      的商品 audit_status 恒为 APPROVED。
--   ③ 第三方商家（supply_type='merchant'）新建或重大变更时，由服务端**显式**
--      写入 PENDING —— 绝不依赖列默认值来决定「要不要审」。
--   t_listing 之所以默认 PENDING，是因为那张表从建立起就是 UGC 审核口径，没有存量包袱。
--
-- 幂等说明：MySQL 不支持 ADD COLUMN IF NOT EXISTS，重复执行会报 1060。
--   这是刻意的（宁可第二次执行报错停下，也不要静默做别的事）。判断是否已执行：
--     SELECT COLUMN_NAME FROM information_schema.COLUMNS
--      WHERE TABLE_SCHEMA='duoduo_product' AND TABLE_NAME='t_spu'
--        AND COLUMN_NAME IN ('audit_status','audit_reason','audit_at','audit_by');
ALTER TABLE `t_spu`
    ADD COLUMN `audit_status` VARCHAR(20)  NOT NULL DEFAULT 'APPROVED'
        COMMENT '审核维度：PENDING 待审 / APPROVED 已通过 / REJECTED 已驳回；自营恒 APPROVED（免审）' AFTER `status`,
    ADD COLUMN `audit_reason` VARCHAR(200) NULL
        COMMENT '最近一次驳回原因（仅 REJECTED 有值，驳回必填；通过时清空）' AFTER `audit_status`,
    ADD COLUMN `audit_at`     DATETIME     NULL
        COMMENT '最近一次审核时间（UTC）' AFTER `audit_reason`,
    ADD COLUMN `audit_by`     BIGINT       NULL
        COMMENT '最近一次审核人（运营/管理员 user id）—— 留痕，便于追责与改判追溯' AFTER `audit_at`;

-- 审核台的主查询是「按审核状态筛 + 看交易状态」，店铺侧是「按卖家 + 审核状态筛」。
-- 已有 idx_spu_seller(seller_id, status) 覆盖后者的一半，这里补审核维度；
-- 门店规模下这两条索引足以，不再多建。
CREATE INDEX `idx_spu_audit` ON `t_spu` (`audit_status`, `status`);
