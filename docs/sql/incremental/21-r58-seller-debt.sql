-- ============================================================================
-- 第五十八轮 · 已放款订单的平台垫付退款与卖家追偿（B-01）：交易库增量 DDL
-- （对已有环境执行；全新环境直接跑 03 全量脚本）
-- ============================================================================
SET NAMES utf8mb4;
USE `duoduo_trade`;

-- ---------------------------------------------------------------------------
-- t_seller_debt：卖家欠款（平台垫付后向卖家追偿的账）
-- ---------------------------------------------------------------------------
-- 为什么需要这张表（B-01 的根因）：钱一旦放款给卖家，托管单就是 RELEASED 终态，
-- 系统里「买家的钱」已经不存在了 —— 此时要退给买家，只能是**平台先垫付**，
-- 于是这笔钱就从「买家 ↔ 卖家」变成「平台 ↔ 卖家」的另一笔债。
-- 没有这张表，这笔债就只存在于运营的口头记录里，结算时无从抵扣、也无法对账。
--
-- 幂等靠唯一键 uk_seller_debt_order(order_id)：一个订单最多垫付一次。
-- 「先查有没有、没有再插」在并发下会双写，所以应用层用 INSERT IGNORE 撞唯一键，
-- 由数据库保证只有一次真正生效（与 t_sku_restock_log 同一手法）。
CREATE TABLE IF NOT EXISTS `t_seller_debt` (
    `id`          BIGINT       NOT NULL                COMMENT '雪花 id',
    `seller_id`   BIGINT       NOT NULL                COMMENT '欠款卖家',
    `order_id`    BIGINT       NOT NULL                COMMENT '关联订单 id',
    `order_no`    VARCHAR(32)  NOT NULL                COMMENT '订单号（冗余，便于对账与人工查询）',
    `amount`      BIGINT       NOT NULL                COMMENT '欠款金额（分）= 平台垫付给买家的金额',
    `status`      VARCHAR(20)  NOT NULL DEFAULT 'OUTSTANDING' COMMENT 'OUTSTANDING 未收回 / SETTLED 已收回 / WRITTEN_OFF 已核销',
    `reason`      VARCHAR(200)                         COMMENT '垫付原因（运营填写，进对账口径）',
    `created_by`  BIGINT                               COMMENT '操作人（运营 userId）',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（UTC）',
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_seller_debt_order` UNIQUE (`order_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '卖家欠款（平台垫付退款形成，主键去重用 order_id 唯一键）';

CREATE INDEX `idx_seller_debt_seller` ON `t_seller_debt` (`seller_id`, `status`);

-- ---------------------------------------------------------------------------
-- t_settlement 状态说明（无需改列：状态是 VARCHAR 存枚举名）
-- ---------------------------------------------------------------------------
-- 新增终态 ADVANCED：**平台垫付**。含义 = 钱已放款给卖家（RELEASED 是既成事实），
-- 平台自掏腰包把钱退给了买家，同时形成一笔卖家欠款（t_seller_debt）。
-- 它不是 RELEASED 的回退 —— RELEASED 那条边不可逆，ADVANCED 是在其之上新增的一条边，
-- 这样资金轨迹可完整回溯：CUSTODY → RELEASED → ADVANCED。
