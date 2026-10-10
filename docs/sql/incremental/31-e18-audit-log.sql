-- ============================================================================
-- E18 · 后台操作审计日志：覆盖全部后台写操作
--
-- 目标（《开发执行总纲》§2 E18）：
--   覆盖全部后台写操作（人 / 租户 / 动作 / 目标 / 前后值 / IP / requestId）；
--   资金与权限类不可删改；可按人 / 租户 / 时间检索。
--
-- 原表（A7）只有 6 列：actor_id / actor_phone / action / target_id / detail /
-- created_at。它能回答「谁给谁改了什么」，但回答不了三件事：
--   ① 这次操作属于哪个租户（第三方商家的动作与平台自营混在一张表里）；
--   ② 变更前后的具体值（detail 是人读摘要，无法机读比对）；
--   ③ 这次请求来自哪里（IP / 链路 ID），出事时无法把审计记录与访问日志对上。
--
-- ★ 新增列的默认值必须让存量数据维持原状（E15 的同一取舍）：
--   category 默认 'OTHER'、target_type 默认 ''、tenant_id 默认 0、outcome 默认 'SUCCESS'。
--   存量行不会被误判成「资金类」或「失败」——把旧数据解释成更严的档位只会制造假线索。
--
-- ★ target_id 保持 NOT NULL：**0 表示「平台级操作、无单一目标」**（如全站索引重建、
--   资质巡检）。用 NULL 表示同一个意思会让所有 `WHERE target_id = ?` 的查询语义分叉
--   （MySQL 里 `<>` 与 `=` 对 NULL 都不匹配，见项目既有口径）。
--
-- 执行说明：MySQL 不支持 `ADD COLUMN IF NOT EXISTS`，与 28/30 号脚本同口径 ——
-- 重复执行会报 1060（列已存在），**这是刻意的**：静默忽略会让「以为加了其实没加」查不出来。
-- ============================================================================

USE `duoduo_user`;

ALTER TABLE `t_audit_log`
    ADD COLUMN `category`     VARCHAR(20)  NOT NULL DEFAULT 'OTHER'
        COMMENT '动作类目：SECURITY/FUNDS/TENANT/CONTENT/OTHER；SECURITY 与 FUNDS 为不可删改档' AFTER `action`,
    ADD COLUMN `outcome`      VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS'
        COMMENT '结果：SUCCESS/FAILED（失败的越权尝试同样留痕）' AFTER `category`,
    ADD COLUMN `target_type`  VARCHAR(32)  NOT NULL DEFAULT ''
        COMMENT '目标类型：USER/MERCHANT/MATERIAL/PRODUCT/ORDER/…；空表示未细分' AFTER `target_id`,
    ADD COLUMN `tenant_id`    BIGINT       NOT NULL DEFAULT 0
        COMMENT '租户（店铺主体 userId）；0 = 平台级操作 / 自营' AFTER `target_type`,
    ADD COLUMN `before_value` VARCHAR(500)          DEFAULT NULL
        COMMENT '变更前值（结构化，供机读比对）；NULL = 该动作不适用' AFTER `tenant_id`,
    ADD COLUMN `after_value`  VARCHAR(500)          DEFAULT NULL
        COMMENT '变更后值；NULL = 该动作不适用' AFTER `before_value`,
    ADD COLUMN `ip`           VARCHAR(64)           DEFAULT NULL
        COMMENT '调用方 IP（网关 X-Real-IP；不用 X-Forwarded-For 首段，那是调用方自填的）' AFTER `after_value`,
    ADD COLUMN `request_id`   VARCHAR(64)           DEFAULT NULL
        COMMENT '链路 ID，用于把审计记录与访问日志对上' AFTER `ip`;

-- 检索口径：按时间 / 按人 / 按租户 / 按类目。三个复合索引都以 created_at 收尾，
-- 因为「检索」几乎总是带时间范围；只建单列索引会让「某人近一周做了什么」退化成回表扫。
ALTER TABLE `t_audit_log`
    ADD INDEX `idx_audit_created` (`created_at`),
    ADD INDEX `idx_audit_actor_created` (`actor_id`, `created_at`),
    ADD INDEX `idx_audit_tenant_created` (`tenant_id`, `created_at`),
    ADD INDEX `idx_audit_category_created` (`category`, `created_at`);

SELECT COUNT(*) AS audit_rows_after_migration FROM `t_audit_log`;
SHOW COLUMNS FROM `t_audit_log`;
