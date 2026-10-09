-- ============================================================================
-- 多多宠物商店 · 用户库生产 DDL（MySQL 8.0）
-- ============================================================================
-- 与 duoduo-user/src/main/resources/db/schema.sql 保持同构，
-- 差异：生产使用 InnoDB + utf8mb4 + 显式字符集与时区；本地 H2 用同一逻辑结构以便零依赖验证。
--
-- 命名约定：t_ 前缀；主键为雪花 ID（BIGINT，不用自增，为分库分表铺路）；
--           时间统一 UTC（TIMESTAMP），由应用层写入，避免依赖数据库时区设置。
-- ============================================================================

-- 注意：必须显式声明客户端字符集。
-- 若执行本文件的客户端按 latin1 发送（容器 entrypoint 下曾发生），中文会被「双重编码」写入：
--   UTF-8 字节 E78CAB 被按 Latin-1 解释成 6 个字符再存为 UTF-8，
--   结果是数据看似存在、但中文关键词检索永远命中 0 条。
SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `duoduo_user`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `nacos`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE `duoduo_user`;

-- ---------------------------------------------------------------------------
-- 账号
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_user` (
    `id`              BIGINT       NOT NULL COMMENT '雪花 ID',
    `phone`           VARCHAR(20)  NOT NULL COMMENT '手机号，全平台唯一（ACC-01）',
    `password_hash`   VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希，禁止存明文',
    `nickname`        VARCHAR(50)  NOT NULL,
    `avatar`          VARCHAR(500)          DEFAULT NULL,
    `status`          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/BANNED（ACC-05）',
    `role`            VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT 'USER/OPERATOR/ADMIN',
    `real_name`       VARCHAR(50)           DEFAULT NULL,
    `id_card_masked`  VARCHAR(30)           DEFAULT NULL COMMENT '仅存掩码，完整号码不落库',
    `wx_openid`       VARCHAR(64)           DEFAULT NULL COMMENT '微信 openid（A13，绑定后写入；未绑定为 NULL）',
    `credit_score`    INT          NOT NULL DEFAULT 60 COMMENT '信用分（A4），默认 = CreditModel 基础分，与「无数据用户得基础分」对齐',
    `last_login_at`   TIMESTAMP             DEFAULT NULL,
    `created_at`      TIMESTAMP    NOT NULL,
    `updated_at`      TIMESTAMP    NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_phone` (`phone`),
    UNIQUE KEY `uk_user_wxopenid` (`wx_openid`),
    KEY `idx_user_created_at` (`created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户账号';

-- ---------------------------------------------------------------------------
-- 刷新令牌（只存哈希 + 支持轮转与强制下线）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_refresh_token` (
    `id`          VARCHAR(64)  NOT NULL COMMENT 'jti',
    `user_id`     BIGINT       NOT NULL,
    `token_hash`  VARCHAR(64)  NOT NULL COMMENT 'SHA-256，令牌本身不落库',
    `expires_at`  TIMESTAMP    NOT NULL,
    `revoked`     TINYINT      NOT NULL DEFAULT 0,
    `user_agent`  VARCHAR(300)          DEFAULT NULL,
    `created_at`  TIMESTAMP    NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_refresh_user` (`user_id`),
    KEY `idx_refresh_expires` (`expires_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '刷新令牌';

-- ---------------------------------------------------------------------------
-- 宠物档案（推荐与 AI 导购的核心上下文）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_pet_profile` (
    `id`               BIGINT        NOT NULL,
    `user_id`          BIGINT        NOT NULL,
    `name`             VARCHAR(50)   NOT NULL,
    `species`          VARCHAR(20)   NOT NULL COMMENT 'cat/dog/other',
    `breed`            VARCHAR(50)            DEFAULT NULL,
    `birthday`         DATE                   DEFAULT NULL,
    `life_stage`       VARCHAR(20)            DEFAULT NULL COMMENT 'BABY/ADULT/SENIOR',
    `weight_kg`        DECIMAL(6, 2)          DEFAULT NULL,
    `gender`           VARCHAR(10)            DEFAULT NULL COMMENT 'MALE/FEMALE/UNKNOWN',
    `neutered`         TINYINT       NOT NULL DEFAULT 0,
    `allergens`        VARCHAR(500)  NOT NULL DEFAULT '' COMMENT '逗号分隔',
    `medical_history`  VARCHAR(1000)          DEFAULT NULL,
    `is_default`       TINYINT       NOT NULL DEFAULT 0 COMMENT '当前宠物，作为推荐默认上下文',
    `created_at`       TIMESTAMP     NOT NULL,
    `updated_at`       TIMESTAMP     NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_pet_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '宠物档案';

-- ---------------------------------------------------------------------------
-- 收货地址簿（A8）
-- ---------------------------------------------------------------------------
-- 省 / 市 / 区拆开存、明细单独一列：下单最终要的是「拼好的一段文本」，
-- 但拆开存才支持后续「按城市算运费」「按省筛选」这类能力。
-- 拼接口径由 AddressService.fullAddress() 一处维护，避免每个调用方各拼一遍、拼出三种格式。
--
-- 唯一默认地址不建唯一索引：MySQL 没有partial index，
-- 若用 `UNIQUE(user_id, is_default)`，一个用户就只能有一条 is_default=0 的地址 —— 与需求正好相反。
-- 改为「服务层在同一事务内先清旧默认、再置新默认」，并用 idx_address_user 保证扫描代价恒定为 O(当前用户地址数)。
CREATE TABLE IF NOT EXISTS `t_user_address` (
    `id`             BIGINT       NOT NULL COMMENT '雪花 ID',
    `user_id`        BIGINT       NOT NULL COMMENT '归属用户；所有读写都带此维度，杜绝越权',
    `receiver_name`  VARCHAR(50)  NOT NULL COMMENT '收货人姓名',
    `receiver_phone` VARCHAR(20)  NOT NULL COMMENT '收货手机号',
    `province`       VARCHAR(30)  NOT NULL COMMENT '省 / 直辖市',
    `city`           VARCHAR(30)  NOT NULL COMMENT '市',
    `district`       VARCHAR(30)  NOT NULL COMMENT '区 / 县',
    `detail`         VARCHAR(200) NOT NULL COMMENT '详细地址（街道门牌）',
    `label`          VARCHAR(20)           DEFAULT NULL COMMENT '标签（家 / 公司 / 学校），仅供展示',
    `is_default`     TINYINT      NOT NULL DEFAULT 0 COMMENT '默认地址；同一用户至多一条为 1，由服务层在同一事务内保证',
    `created_at`     TIMESTAMP    NOT NULL,
    `updated_at`     TIMESTAMP    NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_address_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '收货地址簿';

-- ============================================================================
-- 运营审计日志（A7）
--
-- 记录「谁给谁改了什么」：角色变更等运营敏感操作逐条落库。
-- 只追加、不更新、不删除（无 updated_at）：审计日志的价值在于不可篡改的顺序历史，
-- 提供 UPDATE 语义只会诱使人去「修正历史」。
--
-- detail 存紧凑变更摘要（如 `USER -> OPERATOR`），结构化扩展留待埋点表
-- t_analytics_event（仍预留），不在这里过早设计。
-- ============================================================================
CREATE TABLE IF NOT EXISTS `t_audit_log` (
    `id`          BIGINT       NOT NULL COMMENT '雪花 ID',
    `actor_id`    BIGINT       NOT NULL COMMENT '操作人用户 ID',
    `actor_phone` VARCHAR(20)  NOT NULL COMMENT '操作人手机号（审计追责用，库表非日志通道）',
    `action`      VARCHAR(50)  NOT NULL COMMENT '动作标识（如 USER_ROLE_CHANGE）',
    `target_id`   BIGINT       NOT NULL COMMENT '目标对象 ID（如被改角色的用户）',
    `detail`      VARCHAR(500) NOT NULL COMMENT '变更明细（old -> new）',
    `created_at`  TIMESTAMP    NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_audit_actor` (`actor_id`),
    KEY `idx_audit_target` (`target_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '运营审计日志';

-- ============================================================================
-- 第三方商家租户（E9 建表 / E10 状态机 / E13 成员 / E15 资质有效期）
-- ============================================================================
-- ★ 这三张表原先只在 incremental/25-e9-merchant.sql、26-e10-merchant-material.sql 里，
--   本文件漏了同步 —— 结果是「已有环境升级正常、全新环境（docker compose 初始化、
--   CI 的干净库）建不出表」。docker 的 entrypoint 只执行 docs/sql/ 根目录下的 .sql，
--   不递归 incremental/，所以这个缺口不会自己暴露。E15 要在这些表上加列时才发现，
--   此处补齐（结构与最新增量脚本一致）。
--
-- 设计要点：
--   * 表落在 duoduo_user 库：入驻申请、店主与成员都是「账号域」的事实，
--     与 user 服务现有的角色升级同处一个事务边界。
--   * 本表只管**第三方商家**。平台自营保持既有语义（seller_id = 0，见
--     duoduo-common 的 SellerOwnership）—— 自营不是入驻来的，塞进本表
--     反而要给「唯一店主 / 邀请成员 / 审核状态机」这些第三方专属约束开例外。
--   * isolation_level 预留（P3 租户隔离硬化用）：当前所有第三方商家同一档 STANDARD。
-- ============================================================================
CREATE TABLE IF NOT EXISTS `t_merchant` (
    `id`                  BIGINT       NOT NULL COMMENT '雪花 id（按字符串流转，前端禁止 Number()）',
    `name`                VARCHAR(64)  NOT NULL COMMENT '店铺名（入驻时填写，审核通过即开店名）',
    `owner_user_id`       BIGINT       NOT NULL COMMENT '店主用户 id（t_user.id）',
    `status`              VARCHAR(20)  NOT NULL DEFAULT 'DRAFT'
                          COMMENT 'DRAFT/SUBMITTED/REVIEWING/APPROVED/REJECTED/SUSPENDED（状态机在 E10）',
    `license_expire_at`   DATETIME     NULL
                          COMMENT '店铺资质最早到期日（派生：材料 expire_at 的最小值）；NULL = 无到期概念（E15）',
    `expire_notify_stage` VARCHAR(10)  NULL
                          COMMENT '已提醒到的档位 T30/T7/EXPIRED（提醒去重，只升档）；NULL = 本周期未提醒过（E15）',
    `isolation_level`     VARCHAR(20)  NOT NULL DEFAULT 'STANDARD' COMMENT '租户隔离档位（P3 预留，当前恒 STANDARD）',
    `reject_reason`       VARCHAR(255) NULL COMMENT '最近一次申请驳回原因（结构化 JSON，E10）',
    `suspended_reason`    VARCHAR(255) NULL COMMENT '停业原因（SUSPENDED 时有值，如资质过期，E15）',
    `applied_at`          DATETIME     NULL COMMENT '最近一次提交时间',
    `reviewed_at`         DATETIME     NULL COMMENT '最近一次审核时间',
    `reviewed_by`         BIGINT       NULL COMMENT '最近一次审核人（运营 user id）',
    `created_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_owner` (`owner_user_id`),
    UNIQUE KEY `uk_name` (`name`),
    KEY `idx_status` (`status`),
    KEY `idx_merchant_license_expire` (`license_expire_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '第三方商家租户（E9/E10/E15）';

-- 成员表：一个用户至多属于一个商家（v1 口径，跨店兼职是将来有真实需求再放宽的事）
CREATE TABLE IF NOT EXISTS `t_merchant_member` (
    `id`          BIGINT      NOT NULL COMMENT '雪花 id',
    `merchant_id` BIGINT      NOT NULL COMMENT '所属商家（t_merchant.id）',
    `user_id`     BIGINT      NOT NULL COMMENT '成员用户 id（t_user.id）',
    `role`        VARCHAR(20) NOT NULL COMMENT 'OWNER（店主）/ STAFF（店员，E13）',
    `status`      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED（E13 停用）',
    `invited_by`  BIGINT      NULL COMMENT '邀请人（店主 user id；店主自建为 NULL）',
    `created_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user` (`user_id`),
    KEY `idx_member_merchant` (`merchant_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商家成员（E9 建表，E13 子账号使用）';

-- 资质材料：每类一份（重传覆盖）。审核台永远看到最新提交 ——
-- 历史版本审计走 binlog/留痕，不在这里堆版本行。
CREATE TABLE IF NOT EXISTS `t_merchant_material` (
    `id`            BIGINT       NOT NULL COMMENT '雪花 id',
    `merchant_id`   BIGINT       NOT NULL COMMENT '所属申请（t_merchant.id）',
    `material_type` VARCHAR(40)  NOT NULL COMMENT '材料类型（6 类，见 MerchantMaterialType）',
    `file_url`      VARCHAR(512) NOT NULL COMMENT '材料文件引用（E12 起为私有通道 /api/merchant-materials/{merchantId}/{storedName}）',
    `expire_at`     DATETIME     NULL
                    COMMENT '证件有效期至（UTC，含当日有效）；NULL = 长期有效（E15）',
    `original_name` VARCHAR(255) NULL COMMENT '上传时的原始文件名（仅展示用，不做安全依据）',
    `uploaded_by`   BIGINT       NOT NULL COMMENT '上传人（店主 user id）',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_merchant_type` (`merchant_id`, `material_type`),
    KEY `idx_material_merchant` (`merchant_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '入驻资质材料（E10/E15）';

-- ============================================================================
-- 说明：以下表结构在后续阶段（P1~P3）加入，此处预留，避免 DBA 反复评审
--   t_address        收货地址（**已落地**，见上方 t_user_address，A8）
--   t_audit_log      运营审计日志（**已落地**，见上方，A7）
--   t_category       类目树
--   t_product / t_sku  商品与规格（自营）
--   t_listing        个人闲置（C2C）
--   t_cart_item      购物车
--   t_order / t_order_item  订单（按 user_id 分库分表，16 库 × 64 表）
--   t_payment / t_refund    支付与退款
--   t_shipment       物流
--   t_aftersale      售后与纠纷
--   t_review         评价
--   t_coupon / t_user_coupon  优惠券
--   t_wallet / t_wallet_transaction  资金
--   t_settlement     结算单（禁止直接改余额，必须走结算单）
--   t_kb_doc / t_chat_session / t_chat_message  AI 知识库与会话
--   t_analytics_event  埋点
-- ============================================================================
