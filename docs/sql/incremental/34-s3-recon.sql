-- ============================================================================
-- 34 · S3 钱与库存每日自动对账（第 60 轮）
-- 库：duoduo_trade
--
-- 为什么要有这两张表
-- ------------------
-- 对账的价值**不在于"再算一遍"**，而在于把「算的结果」留下来：
--   ① 没有留痕，就只能回答「现在对不对」，回答不了「昨天对不对、第一次不对是哪天」；
--   ② 差异是**工单的来源**：修一筆对不上的账需要有人去看、去改、去复核，
--      没有一张持久化的差异表，"改完了吗"就只能凭记忆；
--   ③ 阈值告警需要一个稳定的锚点：连续 3 天都有同类差异 = 系统性问题，
--      偶发 1 条 = 人工误操作。两者的处置完全不同，而只有历史数据能区分。
--
-- 为什么放在 duoduo_trade 而不是新建一个对账服务
-- ----------------------------------------------
-- 钱的侧全部数据（t_settlement / t_seller_debt / t_order / t_payment）与
-- 库存冻结台账（t_stock_freeze）本来就在交易域，就地取材即可；
-- 商品域的原始库存通过既有的内部批量快照接口读（对账**只读**，不反向写入）。
-- 新建一个服务只为了"看起来分得干净"，代价是又要复制一份跨库读模型 —— 不划算。
--
-- 口径
-- ----
-- biz_date 是 **UTC 业务日**（不是本地日），与全站时间口径一致；
-- 一天一个 (biz_date, scope) 唯一 —— 同一天重跑**覆盖**上一轮结果，不是累积。
-- 这一点很关键：若允许累积，"同一个差异"会在列表里出现 N 次，
-- 运维无法判断它是"一个问题"还是"一直在发生"。
-- 覆盖的实现是「先删旧轮次（连同其明细）再插新轮次」，靠唯一键兜底而不是靠先查后插。
-- ============================================================================

-- ---------- 对账轮次 ----------
-- status 三态，**FAILED 与 CLEAN 必须分得开**：
--   CLEAN   跑完了，一条差异都没有
--   DIRTY   跑完了，有差异（差异明细见 t_recon_diff）
--   FAILED  **没跑成**（例如商品域读不到 → 无法验证库存）
-- 把 FAILED 记成 CLEAN 是本功能最危险的失效方式：报表一片绿，而事实未知。
CREATE TABLE t_recon_run (
    id            BIGINT       NOT NULL COMMENT '雪花 id',
    biz_date      DATE         NOT NULL COMMENT 'UTC 业务日',
    scope         VARCHAR(20)  NOT NULL COMMENT 'MONEY 钱 / STOCK 库存',
    status        VARCHAR(20)  NOT NULL COMMENT 'CLEAN / DIRTY / FAILED',
    checked_count INT          NOT NULL DEFAULT 0 COMMENT '本轮实际校验的对象数（0 是一个可疑信号）',
    diff_count    INT          NOT NULL DEFAULT 0 COMMENT '本轮差异条数',
    high_count    INT          NOT NULL DEFAULT 0 COMMENT '其中 HIGH 级差异条数',
    started_at    TIMESTAMP    NOT NULL COMMENT '开始时间（UTC）',
    finished_at   TIMESTAMP    NULL COMMENT '结束时间（UTC）',
    message       VARCHAR(500) NULL COMMENT '失败原因或汇总说明',
    PRIMARY KEY (id),
    CONSTRAINT uk_recon_run_day_scope UNIQUE (biz_date, scope)
);

CREATE INDEX idx_recon_run_recent ON t_recon_run (started_at);

-- ---------- 差异明细 ----------
-- rule 是**规则代号**（ReconRule 枚举），不是自然语言 —— 代号稳定才能做统计与
-- 连续多日告警；文案改一个字不该让历史统计断掉。
--
-- uk_recon_diff (run_id, rule, ref_type, ref_id)：同一个对象上同一条规则只留一条。
-- 少了它，一次取数写重（或重跑没删干净）就会让明细翻倍，
-- 而"差异变多了"会被误读成"问题变严重了"。
CREATE TABLE t_recon_diff (
    id         BIGINT       NOT NULL COMMENT '雪花 id',
    run_id     BIGINT       NOT NULL COMMENT '所属轮次',
    rule       VARCHAR(48)  NOT NULL COMMENT '规则代号（ReconRule）',
    severity   VARCHAR(10)  NOT NULL COMMENT 'HIGH / MEDIUM',
    ref_type   VARCHAR(20)  NOT NULL COMMENT '差异主体类型：ORDER / SKU / SELLER',
    ref_id     BIGINT       NOT NULL COMMENT '差异主体 id（订单 id / SKU id / 卖家 userId）',
    expected   VARCHAR(60)  NULL COMMENT '口径上的应有值',
    actual     VARCHAR(60)  NULL COMMENT '库里实际的值',
    detail     VARCHAR(300) NULL COMMENT '人话说明（给运维看的那一行）',
    created_at TIMESTAMP    NOT NULL COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    CONSTRAINT uk_recon_diff UNIQUE (run_id, rule, ref_type, ref_id)
);

CREATE INDEX idx_recon_diff_run ON t_recon_diff (run_id, severity);
CREATE INDEX idx_recon_diff_ref ON t_recon_diff (ref_type, ref_id);
