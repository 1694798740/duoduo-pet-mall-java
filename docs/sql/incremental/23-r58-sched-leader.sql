-- ============================================================================
-- 第五十八轮 · 定时任务分布式选主（P0-2）：四个业务库增量 DDL
-- （对已有环境执行；全新环境直接跑对应的 02~05 全量脚本）
-- ============================================================================
SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- t_sched_leader：让「同一时刻只有一个实例在跑这个任务」
-- ---------------------------------------------------------------------------
-- 为什么需要它：`@Scheduled` 是**进程内**调度，多实例部署时每个实例都会扫一遍。
-- 现有 4 个补偿任务（订单超时关单 / 券锁定释放 / 信用分回写 / ES 增量同步）
-- 都设计成可重入，重复跑**不会算错**，代价是：
--   ① 对数据库与对端服务的压力按实例数放大；
--   ② 日志里出现 N 份同样的「补偿报告」，排查时无法判断到底谁在做；
--   ③ 「扫到就立刻改状态」的动作多实例并发会互相覆盖彼此的扫描窗口，
--      让「这轮补偿了多少」变成一笔糊涂账。
--
-- 为什么不引 XXL-Job / ShedLock：那要引入新中间件与运维面。本表用**数据库唯一键**
-- 做选主，零新增组件 —— 各服务本来就连着自己的库，这张表放在各自的库里。
-- 生产若上了 XXL-Job，把 `duoduo.sched.leader-enabled` 置 false 即可退回外部调度。
--
-- 四个业务服务各自的库都要有（每个服务只访问自己的库，表名相同即可）。

CREATE TABLE IF NOT EXISTS `duoduo_trade`.`t_sched_leader` (
    `name`       VARCHAR(64)  NOT NULL COMMENT '任务名（全局唯一，如 order-sweep）',
    `owner`      VARCHAR(64)  NOT NULL COMMENT '持锁实例标识（hostname#pid）',
    `expire_at`  DATETIME     NOT NULL COMMENT '持锁到期时间（UTC）；过期即视为可抢占',
    `updated_at` DATETIME     NOT NULL COMMENT '最近一次抢占或续约时间（UTC）',
    PRIMARY KEY (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时任务选主：多实例部署时保证同一任务只跑一份';

CREATE TABLE IF NOT EXISTS `duoduo_fulfilment`.`t_sched_leader` (
    `name`       VARCHAR(64)  NOT NULL COMMENT '任务名（全局唯一，如 credit-refresh）',
    `owner`      VARCHAR(64)  NOT NULL COMMENT '持锁实例标识（hostname#pid）',
    `expire_at`  DATETIME     NOT NULL COMMENT '持锁到期时间（UTC）；过期即视为可抢占',
    `updated_at` DATETIME     NOT NULL COMMENT '最近一次抢占或续约时间（UTC）',
    PRIMARY KEY (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时任务选主：多实例部署时保证同一任务只跑一份';

CREATE TABLE IF NOT EXISTS `duoduo_marketing`.`t_sched_leader` (
    `name`       VARCHAR(64)  NOT NULL COMMENT '任务名（全局唯一，如 coupon-release）',
    `owner`      VARCHAR(64)  NOT NULL COMMENT '持锁实例标识（hostname#pid）',
    `expire_at`  DATETIME     NOT NULL COMMENT '持锁到期时间（UTC）；过期即视为可抢占',
    `updated_at` DATETIME     NOT NULL COMMENT '最近一次抢占或续约时间（UTC）',
    PRIMARY KEY (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时任务选主：多实例部署时保证同一任务只跑一份';

CREATE TABLE IF NOT EXISTS `duoduo_product`.`t_sched_leader` (
    `name`       VARCHAR(64)  NOT NULL COMMENT '任务名（全局唯一，如 search-sync）',
    `owner`      VARCHAR(64)  NOT NULL COMMENT '持锁实例标识（hostname#pid）',
    `expire_at`  DATETIME     NOT NULL COMMENT '持锁到期时间（UTC）；过期即视为可抢占',
    `updated_at` DATETIME     NOT NULL COMMENT '最近一次抢占或续约时间（UTC）',
    PRIMARY KEY (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定时任务选主：多实例部署时保证同一任务只跑一份';
