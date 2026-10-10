-- ============================================================================
-- E21 · 平台后台两步验证（TOTP）：用户库增量 DDL
-- （对已有环境执行；全新环境直接跑 01-duoduo-user.sql，两处已同步）
-- ============================================================================
-- 一句话口径：平台运营 / 管理员的账号可以绑定一个 TOTP 密钥（认证器 App），
--   绑定之后登录必须再输一次 6 位动态码；**刷新令牌不能绕过这一步** ——
--   否则一枚在启用 2FA 之前签发的刷新令牌（有效期 7 天）就能把这道门整个绕开。
--
-- 与网关的关系：本项**不在网关上判定**。原因是"是否已完成第二步"由登录端点决定，
--   未通过就拿不到访问令牌 —— 网关那边自然什么都放行不了（没有令牌一律 401），
--   不需要再在网关上加一条"这枚令牌到底算不算数"的判断。判定点越少，口径越不容易分裂。
--
-- 算法口径（步长 30 秒 / 6 位 / ±1 步容差）见 com.duoduo.common.security.totp.Totp；
-- 本脚本只落"密钥 + 状态 + 已用时间步"这三件事。
-- ============================================================================
SET NAMES utf8mb4;

USE `duoduo_user`;

-- 幂等说明：MySQL 不支持 ADD COLUMN IF NOT EXISTS，重复执行会报 1060（刻意如此，
-- 与 30-e17-perm-version.sql 同一口径）—— 静默忽略更有害：它会让"这次到底改没改"
-- 变得无法从输出判断。
--
-- 列设计说明（每一条都是"换个写法就会静默出错"的地方）：
--   · totp_secret 允许为 NULL = 未绑定。**刻意不用空串表示未绑定**：
--     空串在 Base32 解码里属于"合法但无意义"的输入，一旦某处走了"非 null 就当已绑定"
--     的分支，就会变成"用空密钥算出一个永远不对的码"，表现是"绑了但一直提示验证码错误"，
--     比直接报错难查得多。
--   · totp_enabled 与 totp_secret 分成两个字段，是因为中间有"已生成密钥、用户还没验证"
--     的过渡态：只有 enable 校验通过后才置 1。若只留 secret 一个字段，就分不清
--     "刚生成还没扫"和"真的绑好了"—— 用户刷新一次二维码就等于把旧密钥作废，
--     而他可能已经在 App 里存过旧的了。
--   · totp_last_step 记录最近一次**成功校验**的时间步，用于防重放：同一枚码在 ±30 秒
--     窗口内可以被重复提交，不记步号就无法拒绝"被截获后原样重放"。允许 NULL（从未成功校验）。
-- ---------------------------------------------------------------------------
ALTER TABLE `t_user`
    ADD COLUMN `totp_secret` VARCHAR(64) NULL
        COMMENT 'E21 · TOTP 共享密钥（Base32 无填充）；NULL = 未绑定'
        AFTER `perm_version`,
    ADD COLUMN `totp_enabled` TINYINT(1) NOT NULL DEFAULT 0
        COMMENT 'E21 · 是否已完成绑定验证（0=已生成密钥但未验证 / 1=已启用，登录时强制校验）'
        AFTER `totp_secret`,
    ADD COLUMN `totp_bound_at` DATETIME NULL
        COMMENT 'E21 · 绑定完成时间（UTC）；NULL = 未绑定'
        AFTER `totp_enabled`,
    ADD COLUMN `totp_last_step` BIGINT NULL
        COMMENT 'E21 · 最近一次成功校验的时间步（防同一枚码重放）；NULL = 从未成功校验'
        AFTER `totp_bound_at`;

-- 恢复码：绑定 2FA 后一次性下发若干枚，用于"手机丢了 / 换机没导出来"的情况。
--
-- 为什么单独一张表，而不是塞进 t_user 的一列 JSON：
--   ① 恢复码**用一次作废一枚**，需要按枚记录 used_at —— 单列 JSON 要"读-改-写"整串，
--      并发使用两枚码时必然丢更新，而丢更新的方向恰好是"作废失败"，即越用越不安全；
--   ② 恢复码**只存哈希**（与口令同一纪律）：库被读走时，明文码不能跟着一起泄。
--      明文只在生成那一刻返回给用户一次，之后服务端自己也不认。
--
-- 索引 (user_id, used_at)：所有查询都是"取某用户的未用恢复码"，这一条就够；
-- 不给 code_hash 建索引 —— 校验恢复码是按 user_id 取该用户的若干枚再逐条比对，
-- 没有"按码反查用户"的查询（也刻意不提供，那等于给出一个枚举入口）。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_totp_recovery_code` (
    `id`         BIGINT   NOT NULL COMMENT '雪花 ID',
    `user_id`    BIGINT   NOT NULL COMMENT '所属账号',
    `code_hash`  CHAR(64) NOT NULL COMMENT '恢复码明文的 SHA-256 十六进制；禁止存明文',
    `used_at`    DATETIME          DEFAULT NULL COMMENT '使用时间（UTC）；非 NULL = 已用，不可再次登录',
    `created_at` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_recovery_user` (`user_id`, `used_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = 'E21 · TOTP 恢复码（一次性，只存哈希）';
