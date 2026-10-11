-- ============================================================================
-- E23 · 代运营最小版：交易库 + 商品库增量 DDL
-- （对已有环境执行；全新环境直接跑 02-duoduo-product.sql / 03-duoduo-trade.sql，
--   三处已同步 —— 务必保持同步，否则「老库能跑、新环境跑不起来」这类问题
--   只会在 CI 的空环境里现形，本机永远看不见）
-- ============================================================================
-- 一句话口径：平台对**第三方店铺**的经营资产（替他发货 / 替他裁售后 / 替他下架商品）
--   默认**没有任何直接权限**；唯一的例外是「该店铺店主本人提出、平台据此执行」的
--   受控代运营路径。这条例外必须留下两样东西：**授权（工单）** 与 **事由**。
--
-- 为什么工单要独立建表：
--   没有它，「平台替商家做了操作」就只是审计表里的一行 —— 审计回答的是
--   「谁在什么时候做了什么」，回答不了「商家到底有没有同意」。而后者才是
--   「代运营」与「越权操作」的分界线。工单是那个可校验的授权凭证。
--
-- ★ 为什么两张库各建一张同名表，而不是抽到 duoduo_user 做全局唯一：
--   三个动作分属两个服务，服务之间是**库隔离**的。若把工单表放 duoduo_user，
--   两个服务都得通过内部 HTTP 去核验+消费 —— 而「核验通过」与「动作执行」
--   将无法处在同一个本地事务里：核验完、动作还没做完时进程挂掉，工单已烧掉
--   而操作没生效（或反过来，操作生效了工单还是 OPEN）。
--   放在各自的库里，判据校验与动作执行在**同一个事务**内完成，不存在中间态。
--   代价是同名表出现两次 —— 但它们没有共享的事实：交易库的工单只能用于
--   订单类动作（target_type=ORDER），商品库的只能用于商品类动作（PRODUCT），
--   由各自的枚举约束住，**不存在「两个权威算同一件事」的问题**。
--   工单号带领域前缀（WO-ORD- / WO-PRD-）正是为了让这一点在人工排查时也一眼可见。
--
-- 状态机（刻意只有三个状态）：
--   OPEN ──执行成功──> CONSUMED     （一次性，防一个工单被反复使用）
--   OPEN ──人工作废──> VOID
--   CONSUMED / VOID 均为终态 —— 已消费的工单**不允许**回到 OPEN：
--   否则「这个工单用过没有」就变成一个需要看次数的问题，而次数是可以被绕过的。
-- ============================================================================
SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- t_proxy_work_order（duoduo_trade）：订单类代运营工单
-- ---------------------------------------------------------------------------
USE `duoduo_trade`;

-- 幂等说明：MySQL 不支持 CREATE TABLE IF NOT EXISTS + 独立索引的组合会被重复执行
-- 影响；这里用 IF NOT EXISTS 让重复执行安全（与 10-a11-order-place-task.sql 同口径）。
-- 注意 CREATE INDEX 没有 IF NOT EXISTS，所以索引**写在建表语句里**。
CREATE TABLE IF NOT EXISTS `t_proxy_work_order` (
    `id`                BIGINT       NOT NULL                COMMENT '雪花 id',
    `work_order_no`     VARCHAR(48)  NOT NULL                COMMENT '工单号（WO-ORD- 前缀，对外展示与人工核对用）',
    `tenant_id`         BIGINT       NOT NULL                COMMENT '被代运营的店铺主体 userId（= t_merchant.owner_user_id）',
    `target_type`       VARCHAR(16)  NOT NULL                COMMENT 'ORDER / PRODUCT（本库只允许 ORDER）',
    `target_ref`        VARCHAR(64)  NOT NULL                COMMENT '目标对象：ORDER 存订单号，PRODUCT 存 spuId 字符串',
    `action_type`       VARCHAR(32)  NOT NULL                COMMENT 'SHIP 代发货 / AFTERSALE 代处理售后 / OFF_SHELF 代下架',
    `reason`            VARCHAR(255) NOT NULL                COMMENT '事由（申请人必填；空事由的工单等于没有工单）',
    `requester_user_id` BIGINT       NOT NULL                COMMENT '申请人 userId —— 必须是该店铺店主本人，这是「授权」的判据',
    `status`            VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN 待执行 / CONSUMED 已执行 / VOID 已作废',
    `consumed_at`       DATETIME     NULL                    COMMENT '执行时刻（UTC）；非空即已消费',
    `consumed_by`       BIGINT       NULL                    COMMENT '执行人 userId（平台运营 / 管理员）',
    `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    `updated_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（UTC）',
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_proxy_wo_no` UNIQUE (`work_order_no`),
    -- 查「这个对象还有没有可用工单」是执行路径上的必经查询，单独给索引
    KEY `idx_proxy_wo_target` (`target_type`, `target_ref`, `status`),
    KEY `idx_proxy_wo_tenant` (`tenant_id`, `status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '代运营工单（E23）：第三方店铺经营资产的受控代操作凭证；一次性消费';

-- ---------------------------------------------------------------------------
-- t_proxy_work_order（duoduo_product）：商品类代运营工单
-- ---------------------------------------------------------------------------
-- 与交易库同名同构。列定义刻意**逐字一致**：将来若需要把两张表合并成
-- 一处（比如引入真正的工单中心），完全一致的形状能让迁移变成一次纯搬运，
-- 而不是「先对齐两套字段语义」的重构。
USE `duoduo_product`;

CREATE TABLE IF NOT EXISTS `t_proxy_work_order` (
    `id`                BIGINT       NOT NULL                COMMENT '雪花 id',
    `work_order_no`     VARCHAR(48)  NOT NULL                COMMENT '工单号（WO-PRD- 前缀）',
    `tenant_id`         BIGINT       NOT NULL                COMMENT '被代运营的店铺主体 userId',
    `target_type`       VARCHAR(16)  NOT NULL                COMMENT 'ORDER / PRODUCT（本库只允许 PRODUCT）',
    `target_ref`        VARCHAR(64)  NOT NULL                COMMENT '目标对象：PRODUCT 存 spuId 字符串',
    `action_type`       VARCHAR(32)  NOT NULL                COMMENT 'SHIP / AFTERSALE / OFF_SHELF（本库只允许 OFF_SHELF）',
    `reason`            VARCHAR(255) NOT NULL                COMMENT '事由（申请人必填）',
    `requester_user_id` BIGINT       NOT NULL                COMMENT '申请人 userId —— 必须是该店铺店主本人',
    `status`            VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN 待执行 / CONSUMED 已执行 / VOID 已作废',
    `consumed_at`       DATETIME     NULL                    COMMENT '执行时刻（UTC）',
    `consumed_by`       BIGINT       NULL                    COMMENT '执行人 userId（平台运营 / 管理员）',
    `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（UTC）',
    `updated_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（UTC）',
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_proxy_wo_no` UNIQUE (`work_order_no`),
    KEY `idx_proxy_wo_target` (`target_type`, `target_ref`, `status`),
    KEY `idx_proxy_wo_tenant` (`tenant_id`, `status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '代运营工单（E23 · 商品域）：第三方店铺商品受控代操作凭证；一次性消费';
