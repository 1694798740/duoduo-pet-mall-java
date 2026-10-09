-- ============================================================================
-- E15 · 资质有效期与提醒：用户库增量 DDL
-- （对已有环境执行；全新环境直接跑 01-duoduo-user.sql，两处已同步）
-- ============================================================================
SET NAMES utf8mb4;
USE `duoduo_user`;

-- ---------------------------------------------------------------------------
-- 一、t_merchant_material：每份材料自己的有效期
-- ---------------------------------------------------------------------------
-- 为什么有效期落在**材料**而不是只放店铺上一列：
--   提醒文案必须说清「是哪张证要到期了」——「您的营业执照将于 3 天后到期」是可执行的，
--   「您的店铺资质将于 3 天后到期」只会让商家去翻六份材料挨个看。行业经营许可证、
--   法人身份证与营业执照的到期日各不相同，压成一列就永久丢失了这个信息。
--
-- ★ 为什么 NULL 表示「长期有效」而不是「未填 = 立即过期」：
--   本表有存量行（E10/E12 提交的材料），加列那一刻它们全是 NULL。
--   若把 NULL 解释成过期，迁移完成的瞬间所有存量商家集体停业 —— 纯人为事故。
--   与 t_spu.audit_status 默认 APPROVED 是同一个取舍：**新增判定维度时，
--   默认值必须让存量数据维持原状**。
--
-- 幂等说明：MySQL 不支持 ADD COLUMN IF NOT EXISTS，重复执行会报 1060（刻意如此）。
--   判断是否已执行：
--     SELECT COLUMN_NAME FROM information_schema.COLUMNS
--      WHERE TABLE_SCHEMA='duoduo_user' AND TABLE_NAME='t_merchant_material'
--        AND COLUMN_NAME='expire_at';
ALTER TABLE `t_merchant_material`
    ADD COLUMN `expire_at` DATETIME NULL
        COMMENT '证件有效期至（UTC，含当日有效）；NULL = 长期有效（存量数据与不需有效期的材料）' AFTER `file_url`;

-- ---------------------------------------------------------------------------
-- 二、t_merchant：店铺级的到期判据与提醒状态
-- ---------------------------------------------------------------------------
-- license_expire_at 是**派生冗余列**：值 = 该店铺所有带有效期的材料里最早的那个到期日，
-- 由服务端在「保存材料 / 提交 / 续期」时重算并显式写入。冗余的理由有两条：
--   ① 定时扫描要按到期日取临界区间，JOIN 材料表取 MIN 会让索引失效；
--   ② 上架门禁是每写一次商品就要判一次的高频路径，不能顺带拉起六行材料。
--   派生列的风险是「材料改了、派生列忘更新」——因此重算只有**一个出口**
--   （MerchantOnboardingService#refreshLicenseExpireAt），与 t_spu 的可见性出口同一纪律。
--
-- expire_notify_stage 是**提醒去重档位**（T30/T7/EXPIRED），记「已经提醒到哪一档」
-- 而不是「提醒过没有」：扫描任务每轮都会看到同一批临期店铺，若只记布尔值，
-- 第二天就无处判断该不该再发；记档位后规则是「新档位比已记档位更深才发一条」，
-- 于是 30 天档发一条、7 天档再发一条，同档位反复扫到也不重复打扰。
-- 续期通过时清空，下一轮周期重新计数。
--
-- suspended_reason 与已有的 reject_reason **刻意分列**：一个是「申请被驳回、去改材料」，
--   一个是「已开张的店铺被停业」——处置对象与商家该做的动作都不同，
--   合并成一列后审核台就要靠 status 反推含义，读代码的人迟早会猜错。
--
-- 幂等说明同上：重复执行报 1060。
ALTER TABLE `t_merchant`
    ADD COLUMN `license_expire_at`   DATETIME     NULL
        COMMENT '店铺资质最早到期日（派生：材料 expire_at 的最小值）；NULL = 无到期概念（长期有效）' AFTER `status`,
    ADD COLUMN `expire_notify_stage` VARCHAR(10)  NULL
        COMMENT '已提醒到的档位 T30/T7/EXPIRED（提醒去重，只升档）；NULL = 本周期未提醒过' AFTER `license_expire_at`,
    ADD COLUMN `suspended_reason`    VARCHAR(255) NULL
        COMMENT '停业原因（SUSPENDED 时有值，如「经营资质已过期」）；与申请驳回原因 reject_reason 分列' AFTER `reject_reason`;

-- 到期扫描的主查询是「status='APPROVED' AND license_expire_at <= 某时刻」。
-- 已有 idx_status 只能过滤一半，补一条按到期日的索引让扫描走区间。
CREATE INDEX `idx_merchant_license_expire` ON `t_merchant` (`license_expire_at`);
