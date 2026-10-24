-- 49 · 商品审核历史与变更留痕（2026-10-24，A-08）
--
-- 问题：t_spu 上只有 audit_status / audit_reason / audit_at / audit_by 四列「当前值」，
-- 改判、多次驳回、谁在什么时候改了什么，全部不可追溯。运营点一次通过就把上一次驳回
-- 覆盖掉了，事后复盘只能看到最后一帧。
--
-- 方案：新增 append-only 的 t_spu_audit_log。只记「审核维度」的事件
-- （提交 / 编辑内容 / 重新提审 / 通过 / 驳回 / 抽查判违规），不记改价、改库存、上下架 ——
-- 那些是正交的高频动作，混进来只会把审核时间线稀释成操作流水。
--
-- 为什么是独立表而不是 t_spu 上加列：同一商品会被反复编辑 / 驳回 / 重提，
-- 加列只能记「最近一次」；审核历史本身就是一条时间线，天然是一对多。
--
-- 为什么变更是字段级 diff 而不是整行快照：整行快照会让「改了一个标题」淹没在
-- 二十个未变字段里，且存储随编辑次数线性膨胀。diff 只留真正变化的字段，
-- 每个值最多 2000 个码点（超长截断并打标），足以复盘。
--
-- 写入与业务同事务（strict）：历史写不进去，业务也不许成功 —— 这是审核留痕，
-- 不是可选的旁路日志（对比 E18 的通用审计，那个是 best-effort）。
--
-- 部署顺序：先执行本脚本，再发布商品服务（新代码会写这张表）。
-- 已有环境执行一次；新环境由 02 的建表语句覆盖。
SET NAMES utf8mb4;
USE `duoduo_product`;

CREATE TABLE IF NOT EXISTS t_spu_audit_log (
    id                BIGINT       NOT NULL COMMENT '雪花 id（同时是时间线顺序）',
    spu_id            BIGINT       NOT NULL COMMENT 't_spu.id',
    seller_id         BIGINT       NOT NULL DEFAULT 0 COMMENT '归属店铺主体 user id；0=平台自营（自营不写本表）',
    event             VARCHAR(20)  NOT NULL COMMENT 'SUBMIT/EDIT/RESUBMIT/APPROVE/REJECT/SAMPLE_REJECT',
    actor_type        VARCHAR(20)  NOT NULL COMMENT 'SELLER/STAFF/OPERATOR/SYSTEM',
    actor_id          BIGINT                COMMENT '操作人 user id；SYSTEM 为空',
    actor_phone       VARCHAR(20)           COMMENT '操作人手机号快照（人可能改号，历史要保留当时的值）',
    from_audit_status VARCHAR(20)           COMMENT '事件前的审核态；SUBMIT 为空',
    to_audit_status   VARCHAR(20)  NOT NULL COMMENT '事件后的审核态',
    audit_source      VARCHAR(16)           COMMENT 'AUTO/MANUAL/SAMPLE/SELF；NULL=转人工或重提',
    reason            VARCHAR(200)          COMMENT '驳回 / 抽查判违规原因',
    version_before    INT                   COMMENT '事件前版本；SUBMIT 为空',
    version_after     INT          NOT NULL COMMENT '事件后版本（版本是审核结论的锚点）',
    sampled_version   INT                   COMMENT '抽查判违规时：运营当时看到的是哪一版',
    changes           MEDIUMTEXT            COMMENT '字段级变更 JSON（每值最多 2000 码点）',
    created_at        TIMESTAMP    NOT NULL COMMENT '事件时间（UTC）',
    PRIMARY KEY (id),
    KEY idx_spu_id (spu_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品审核历史（append-only）';

-- 抽查任务记录「抽中时商品是哪一版」，供复核台提示「内容已变更」。
-- 刻意可空：本脚本之前生成的任务没有版本快照，回填会凭空制造「当时就是这一版」的假事实。
ALTER TABLE t_audit_sample
    ADD COLUMN spu_version INT NULL COMMENT '抽样时的商品版本快照（A-08）；NULL=旧任务未记录';
