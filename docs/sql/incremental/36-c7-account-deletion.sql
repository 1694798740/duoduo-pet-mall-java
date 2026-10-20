-- ---------------------------------------------------------------------------
-- C7 · 账号注销（2026-10-20）
--
-- 背景：《用户隐私保护指引》4.2 已承诺「可申请注销账号，十五个工作日内办结」，
-- 但此前 t_user 既无注销状态、也无任何删除标记，承诺无法兑现（合规欠账）。
-- 设计全文见 docs/account-deletion-design.md。
--
-- 取舍：**软注销 + 匿名化，不做物理删号**。
--   · 交易信息（订单/支付/售后/审计）依法须自交易完成起留存不少于三年，
--     物理删行会连带毁掉这些记录；
--   · 但 PII（手机号、姓名、身份证掩码、openid、TOTP 密钥）必须清除。
-- 因此新增 account_status 状态机，执行阶段改写 PII 列、保留行本体。
--
-- 与既有 status 列的关系：status 管「封禁」（ACTIVE/BANNED），
-- account_status 管「注销」（ACTIVE/CANCELLING/DELETED），两者正交、互不覆盖。
-- ---------------------------------------------------------------------------

ALTER TABLE `t_user`
    ADD COLUMN `account_status`     VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
        COMMENT '账号注销状态：ACTIVE 正常 / CANCELLING 注销冷静期内（登录停用，可撤销）/ DELETED 已注销（PII 已清空，行保留）',
    ADD COLUMN `delete_requested_at` TIMESTAMP    DEFAULT NULL COMMENT '用户提交注销申请的 UTC 时间',
    ADD COLUMN `delete_scheduled_at` TIMESTAMP    DEFAULT NULL COMMENT '冷静期截止 UTC 时间（= 申请时间 + grace-days，默认 7 天）；调度到点执行',
    ADD COLUMN `delete_reason`       VARCHAR(200) DEFAULT NULL COMMENT '用户填写的注销原因（可选，用于产品改进）',
    ADD COLUMN `delete_executed_at`  TIMESTAMP    DEFAULT NULL COMMENT '实际执行注销的 UTC 时间（五域个人数据清理全部成功后写入）',
    ADD KEY `idx_user_account_status` (`account_status`, `delete_scheduled_at`);

-- 执行期把 phone 改写为 'x<userId>' 以释放唯一索引（允许同一手机号重新注册）。
-- 两个约束共同决定这个写法（与 AccountDeletionService.DELETED_PHONE_PREFIX 对齐）：
--   · 必须非空且唯一 —— 空串会让多个已注销账号撞上 uk_user_phone；
--   · 必须放得进 VARCHAR(20) —— 雪花 id 最长 19 位，'x' + 19 = 20 正好；
--     ★ 早前这里误写为 'del_'（4 + 19 = 23 会溢出，MySQL 严格模式直接报错），
--       已于 2026-10-20 按实际实现订正为 'x'。
