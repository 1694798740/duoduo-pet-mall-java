-- 71 · 异步导出任务（矩阵第 9 项 · 超过 5 万行走异步 + 生成后站内通知）
--
-- 设计要点：
--   * **状态只前进**：PENDING → RUNNING → SUCCESS / FAILED。
--     置 RUNNING 用 `WHERE status='PENDING'` 条件更新（CAS），多实例抢同一个任务只会有一方成功。
--   * **文件落在服务本地卷**（trade-exports），过期由调度删行 + 删文件；
--     下载必须带登录态且只能下载自己创建的任务（归属即一切，与通知 / 地址簿同一纪律）。
--   * **失败必须留原因**：error_message 是运营自助排障的唯一线索，
--     不能只把状态置 FAILED 而让人去翻日志。
--   * **row_count 是进度**：每写一页更新一次；total_rows 在创建时按同一筛选 count 出来，
--     前端据此显示百分比 —— 不额外再查一次库。
--   * 导出本身是只读动作，不写审计；但文件含全量收货信息，所以下载端点必须 OperatorGuard + 归属校验。
--
-- 已有环境手工执行：
--   docker exec -i duoduo-mysql mysql -uroot -pduoduo_root duoduo_trade \
--     < docs/sql/incremental/71-duoduo-trade-export-job.sql
SET NAMES utf8mb4;
USE `duoduo_trade`;

CREATE TABLE IF NOT EXISTS t_export_job (
    id                 BIGINT       NOT NULL COMMENT '雪花主键',
    scope              VARCHAR(16)  NOT NULL COMMENT '入口域：ADMIN / SELLER',
    module             VARCHAR(24)  NOT NULL COMMENT '导出模块：ORDERS（后续可扩展）',
    params             VARCHAR(500) NULL COMMENT '筛选参数 JSON（创建时快照，后续列表口径变化不影响历史任务）',
    status             VARCHAR(12)  NOT NULL COMMENT 'PENDING / RUNNING / SUCCESS / FAILED / EXPIRED',
    total_rows         INT          NOT NULL DEFAULT 0 COMMENT '按筛选预估总行数（创建时 count）',
    row_count          INT          NOT NULL DEFAULT 0 COMMENT '已写出行数（每页更新一次）',
    file_name          VARCHAR(120) NULL COMMENT '下载文件名',
    file_path          VARCHAR(300) NULL COMMENT '服务本地文件绝对路径',
    file_size          BIGINT       NOT NULL DEFAULT 0 COMMENT '文件字节数',
    error_message      VARCHAR(500) NULL COMMENT '失败原因（脱敏后的可读文案）',
    created_by         BIGINT       NOT NULL COMMENT '创建人 userId（下载归属判据）',
    created_at         TIMESTAMP    NOT NULL,
    started_at         TIMESTAMP    NULL,
    finished_at        TIMESTAMP    NULL,
    expires_at         TIMESTAMP    NULL COMMENT '文件过期时间（创建成功起 7 天）',
    download_count     INT          NOT NULL DEFAULT 0,
    last_downloaded_at TIMESTAMP    NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_export_job_owner ON t_export_job (created_by, created_at);
CREATE INDEX idx_export_job_expiry ON t_export_job (expires_at);
CREATE INDEX idx_export_job_status ON t_export_job (status, created_at);
