-- ============================================================================
-- E28 · 审核 SLA 落地（自动放行 + 事后抽查）：商品库 / 用户库增量 DDL
-- （对已有环境执行；全新环境直接跑 01-duoduo-user.sql 与 02-duoduo-product.sql，
--   四处已同步）
-- ============================================================================
-- 方案定稿见《开发执行总纲》附录 D。一句话口径：
--   人工审核不是上线的必经环节，而是**抽样复核**；默认放行，违规靠事后发现 + 快速回滚。
--
-- 本脚本只做「让这套规则可被记录、可被统计、可被复现」所需的最小数据面，
-- 不新增任何调度中间件、不新增消息通道 —— 全部复用既有能力。
-- ============================================================================
SET NAMES utf8mb4;

-- ============================================================================
-- 一、duoduo_product：商品侧
-- ============================================================================
USE `duoduo_product`;

-- ---------------------------------------------------------------------------
-- 1.1 t_spu：审核结论的**来源**（E28 新增）
-- ---------------------------------------------------------------------------
-- 为什么必须单独一列而不是从 audit_by 反推：
--   audit_by 为 NULL 有**三种**含义 —— ① 自营免审从未审过；② 自动放行（系统判定，
--   没有人类审核人）；③ E14 之前的历史数据（那时这列还不存在）。三者要做的事完全不同
--   （自营不用管 / 自动放行的要纳入抽查 / 历史的以后补标），压在一列上就无法区分。
--
-- 取值：AUTO（系统按规则自动放行）/ MANUAL（运营人工判定）/ SELF（自营免审，不需要审核）
--       NULL = E28 之前的历史数据（**刻意留 NULL，不回填**）
--
-- ★ 为什么 NULL 不回填成 APPROVED 的对应值：新增判定维度时，
--   默认值必须让存量数据维持原状。回填成 MANUAL 会凭空制造出「这批是人工审过的」
--   这个假事实，而实际上没人审过；留 NULL 才能让「以后要补标」这件事有据可查。
--
-- 幂等说明：MySQL 不支持 ADD COLUMN IF NOT EXISTS，重复执行报 1060（刻意如此）。判断是否已执行：
--   SELECT COLUMN_NAME FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA='duoduo_product' AND TABLE_NAME='t_spu' AND COLUMN_NAME='audit_source';
ALTER TABLE `t_spu`
    ADD COLUMN `audit_source` VARCHAR(10) NULL
        COMMENT '审核来源 AUTO(自动放行)/MANUAL(人工)/SELF(自营免审)；NULL=历史数据' AFTER `audit_by`;

-- ---------------------------------------------------------------------------
-- 1.2 t_audit_sample：事后抽查任务（E28 核心表）
-- ---------------------------------------------------------------------------
-- 为什么是独立表而不是在 t_spu 上加几列：
--   ① 抽查是**按天批量**产生的，一天可能给同一件商品建一条复核任务，
--      而商品本身只有一行 —— 加列只能记「最近一次」，历史复核记录全丢；
--   ② 复核结论（PASSED/REJECTED）与审核结论（audit_status）是**两件事**：
--      抽查判通过不改 audit_status（它本来就是 APPROVED 才被放行的），
--      抽查判违规才回写 audit_status=REJECTED。两件事混进一张表，语义立刻纠缠；
--   ③ 指标（违规率 / 复核时长 / 误判率）要按时间段聚合，独立表才能走索引。
--
-- ★ 唯一键 uk_biz_date（biz_type + biz_id + sample_date）是**幂等**的来源：
--   同一件商品同一天最多被抽中一次；调度重跑、手动触发、多实例兜底都不会重复建任务。
--   注意 sample_date 是 DATE 而不是 DATETIME —— 抽查的粒度就是「天」，
--   用时间戳做键会让每次重跑都建出新行，「幂等」就成了空话。
--
-- ★ reason 记录**为什么被抽中**：BASE(基础比例) / TINY_ALL(当日不足 3 件全查) /
--   NEW_SELLER(新商家前 3 件) / RESUME(恢复营业 7 天内) / ESCALATED(有驳回或停业记录)。
--   没有这一列，运营在队列里看到一条任务时无法判断它是「随机抽到的」
--   还是「本来就重点盯的」，复核优先级也就无从排序。
CREATE TABLE IF NOT EXISTS `t_audit_sample` (
    `id`          BIGINT       NOT NULL COMMENT '雪花 id（按字符串流转）',
    `biz_type`    VARCHAR(20)  NOT NULL COMMENT '抽查对象类型：SPU(商品) / MERCHANT(入驻申请)',
    `biz_id`      BIGINT       NOT NULL COMMENT '对象 id（SPU 用 t_spu.id；MERCHANT 用 t_merchant.id）',
    `seller_id`   BIGINT       NOT NULL DEFAULT 0
                  COMMENT '归属店铺主体 user id（SPU 用 t_spu.seller_id；MERCHANT 用 owner_user_id）；0=平台自营（不应被抽中）',
    `sample_date` DATE         NOT NULL COMMENT '抽样日期（UTC）；与 biz_id 组成幂等键',
    `reason`      VARCHAR(32)  NOT NULL
                  COMMENT '抽中理由 BASE/TINY_ALL/NEW_SELLER/RESUME/ESCALATED',
    `status`      VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                  COMMENT 'PENDING(待复核) / PASSED(复核通过) / REJECTED(复核判违规)',
    `review_note` VARCHAR(200) NULL     COMMENT '复核意见（判违规时必填，与审核驳回原因同一口径）',
    `reviewed_by` BIGINT       NULL     COMMENT '复核人（运营 user id）',
    `reviewed_at` DATETIME     NULL     COMMENT '复核时间（UTC）；与 created_at 之差即响应时长',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '任务生成时间（UTC）',
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_biz_date` (`biz_type`, `biz_id`, `sample_date`),
    KEY `idx_status_created` (`status`, `created_at`),
    KEY `idx_seller_date` (`seller_id`, `sample_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审核 SLA 事后抽查任务（E28）';

-- ---------------------------------------------------------------------------
-- 1.3 t_audit_sla_state：抽样强度（单行）
-- ---------------------------------------------------------------------------
-- 附录 D 第五节要求「系统自证」：抽中违规率连续 3 天 > 10% → 抽查比例翻倍。
-- 比例得有个存放处，而且必须是**能被自动改写**的一行数据，而不是配置项 ——
-- 配置写在 yml 里，自动收紧就变成了「改配置文件再重启」，那就不叫自动了。
--
-- id 固定为 1（单行表，不用雪花 id）：它的语义是「全局唯一的一份状态」，
-- 给它一个随机 id 只会让人以为可以有第二行。
--
-- ★ sample_rate_percent 默认 5 = 附录 D 的基础比例；翻倍后上界在代码里封顶（见 AuditSlaPolicy）。
CREATE TABLE IF NOT EXISTS `t_audit_sample_rate` (
    `id`                  BIGINT   NOT NULL COMMENT '固定 1（单行状态表）',
    `sample_rate_percent` INT      NOT NULL DEFAULT 5  COMMENT '当前商品抽查比例（%）；达标即维持，连续超标则翻倍',
    `tightened_at`        DATETIME NULL COMMENT '最近一次自动收紧时间（UTC）；NULL=从未收紧过',
    `updated_at`          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审核 SLA 抽样强度状态（E28，单行）';

INSERT INTO `t_audit_sample_rate` (`id`, `sample_rate_percent`)
VALUES (1, 5)
ON DUPLICATE KEY UPDATE `id` = `id`;

-- ---------------------------------------------------------------------------
-- 1.4 t_sched_leader：商品库已有（见 23-r58-sched-leader.sql），此处仅做存在性兜底
-- ---------------------------------------------------------------------------
-- 为什么还要写一遍：23 号脚本是「对已有环境」的增量，而全新环境走的是
-- 02-duoduo-product.sql（已同步）。这里重复一次是为了让**只跑本脚本**的环境
-- 也能安全地启动抽查任务 —— CREATE TABLE IF NOT EXISTS 本身幂等，无副作用。
CREATE TABLE IF NOT EXISTS `t_sched_leader` (
    `name`       VARCHAR(64)  NOT NULL COMMENT '任务名（全局唯一，如 audit-sample）',
    `owner`      VARCHAR(64)  NOT NULL COMMENT '持锁实例标识（hostname#pid）',
    `expire_at`  DATETIME     NOT NULL COMMENT '持锁到期时间（UTC）；过期即视为可抢占',
    `updated_at` DATETIME     NOT NULL COMMENT '最近一次抢占或续约时间（UTC）',
    PRIMARY KEY (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时任务选主：多实例部署时保证同一任务只跑一份';

-- ============================================================================
-- 二、duoduo_user：入驻与续期侧
-- ============================================================================
USE `duoduo_user`;

-- ---------------------------------------------------------------------------
-- 2.1 t_merchant：审核来源 + 违规计数
-- ---------------------------------------------------------------------------
-- audit_source 的口径与 t_spu.audit_source 完全一致（AUTO/MANUAL/NULL=历史）。
--   入驻侧没有 SELF —— 平台自营不存在入驻申请。
--
-- violation_count 是**商家级**违规累计，用于附录 D 第四节的「同一商家重复违规计数
--   → 升级为商家级停业」。为什么放在店铺行上而不是实时聚合抽查表：
--   升级判定发生在「每一次判违规」的那一刻，实时聚合要扫全表且跨库（商品库的抽查表
--   不在这个库），而它需要的只是一个可原子自增的计数器。
--
--   ★ 计数器必须**只增不减**：它是「这家店历史上被判过几次违规」的事实。
--     复核通过（PASSED）不能减 —— 那是「这次抽查没问题」，不是「以前的违规撤销了」。
--     唯一的重置点是商家级停业后的复业（人工重新审核通过），在那条路径上显式清零。
--
-- 幂等说明同上：重复执行报 1060。
ALTER TABLE `t_merchant`
    ADD COLUMN `audit_source`    VARCHAR(10) NULL
        COMMENT '入驻审核来源 AUTO(自动放行)/MANUAL(人工)；NULL=历史数据' AFTER `suspended_reason`,
    ADD COLUMN `violation_count` INT         NOT NULL DEFAULT 0
        COMMENT '商家级违规累计次数（E28）：判违规 +1，达阈值自动停业；复业时清零' AFTER `audit_source`;

-- 商家级停业判定要按 violation_count 阈值取数（虽有索引也测一遍更稳），
-- 并且审核台要按「违规次数」排序展示 —— 补一条索引。
CREATE INDEX `idx_merchant_violation` ON `t_merchant` (`violation_count`);

-- ---------------------------------------------------------------------------
-- 2.2 t_audit_sample：入驻侧抽查任务（结构与商品库同名表**完全一致**）
-- ---------------------------------------------------------------------------
-- ★ 为什么两个库各持一份而不是共用一个库：
--   服务边界是「每个服务只连自己的库」（23 号脚本的原话）。用户域服务读商品库
--   会直接破坏这条边界，而为了几个字段引入一次 Feign 调用，又把一个纯数据库读
--   变成了可能失败的网络调用 —— 抽不抽查不该取决于对方服务此刻是否健康。
--   两表结构由本脚本与全量脚本共同保证一致；差异只体现在 biz_type 的取值上
--   （商品库只写 SPU，用户库只写 MERCHANT），所以两边的数据天然不重叠。
CREATE TABLE IF NOT EXISTS `t_audit_sample` (
    `id`          BIGINT       NOT NULL COMMENT '雪花 id（按字符串流转）',
    `biz_type`    VARCHAR(20)  NOT NULL COMMENT '抽查对象类型：SPU(商品) / MERCHANT(入驻申请)',
    `biz_id`      BIGINT       NOT NULL COMMENT '对象 id（SPU 用 t_spu.id；MERCHANT 用 t_merchant.id）',
    `seller_id`   BIGINT       NOT NULL DEFAULT 0
                  COMMENT '归属店铺主体 user id（SPU 用 t_spu.seller_id；MERCHANT 用 owner_user_id）；0=平台自营（不应被抽中）',
    `sample_date` DATE         NOT NULL COMMENT '抽样日期（UTC）；与 biz_id 组成幂等键',
    `reason`      VARCHAR(32)  NOT NULL
                  COMMENT '抽中理由 BASE/TINY_ALL/NEW_SELLER/RESUME/ESCALATED',
    `status`      VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                  COMMENT 'PENDING(待复核) / PASSED(复核通过) / REJECTED(复核判违规)',
    `review_note` VARCHAR(200) NULL     COMMENT '复核意见（判违规时必填，与审核驳回原因同一口径）',
    `reviewed_by` BIGINT       NULL     COMMENT '复核人（运营 user id）',
    `reviewed_at` DATETIME     NULL     COMMENT '复核时间（UTC）；与 created_at 之差即响应时长',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '任务生成时间（UTC）',
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_biz_date` (`biz_type`, `biz_id`, `sample_date`),
    KEY `idx_status_created` (`status`, `created_at`),
    KEY `idx_seller_date` (`seller_id`, `sample_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审核 SLA 事后抽查任务（E28）';

-- ---------------------------------------------------------------------------
-- 2.3 t_audit_sample_rate：入驻侧抽样强度（单行；与商品库同名表结构一致）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_audit_sample_rate` (
    `id`                  BIGINT   NOT NULL COMMENT '固定 1（单行状态表）',
    `sample_rate_percent` INT      NOT NULL DEFAULT 5  COMMENT '当前入驻抽查比例（%）',
    `tightened_at`        DATETIME NULL COMMENT '最近一次自动收紧时间（UTC）；NULL=从未收紧过',
    `updated_at`          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审核 SLA 抽样强度状态（E28，单行）';

INSERT INTO `t_audit_sample_rate` (`id`, `sample_rate_percent`)
VALUES (1, 5)
ON DUPLICATE KEY UPDATE `id` = `id`;

-- ---------------------------------------------------------------------------
-- 2.4 t_sched_leader：★ 用户库此前**缺这张表**，本次补建
-- ---------------------------------------------------------------------------
-- 23-r58-sched-leader.sql 只给了 trade / fulfilment / marketing / product 四个库。
-- E15 的 MerchantLicenseJob 已经在用户库里调用 SchedLeaderLock，缺表时
-- SchedLeaderLock 的兜底是「返回 true（放行）」—— 也就是说选主一直是**静默失效**的：
-- 单实例跑没问题，多实例部署就会两个实例同时扫同一批店铺。
-- 它不会算错（任务本身幂等），所以一直没人发现 —— 这正是「静默兜底」的典型代价。
CREATE TABLE IF NOT EXISTS `t_sched_leader` (
    `name`       VARCHAR(64)  NOT NULL COMMENT '任务名（全局唯一，如 merchant-license-sweep）',
    `owner`      VARCHAR(64)  NOT NULL COMMENT '持锁实例标识（hostname#pid）',
    `expire_at`  DATETIME     NOT NULL COMMENT '持锁到期时间（UTC）；过期即视为可抢占',
    `updated_at` DATETIME     NOT NULL COMMENT '最近一次抢占或续约时间（UTC）',
    PRIMARY KEY (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时任务选主：多实例部署时保证同一任务只跑一份';
