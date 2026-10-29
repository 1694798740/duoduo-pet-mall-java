-- 64 · 多多币 P2：签到日历（R-P2 第一百二十六轮）
--
-- 设计要点：
--   * **一天一行**：`uk_coin_signin (user_id, sign_date)` 唯一索引是防刷的最终闸门 ——
--     服务层先查重只是友好提示，并发双击 / 脚本重放都由这个索引兜底（INSERT IGNORE 撞键即拒）。
--   * **sign_date 存 Asia/Shanghai 本地日**（DATE 类型，不是 UTC 时刻）：
--     「今天签了没」是用户日历直觉问题，必须按北京时间对日；存储用 DATE 避免时区换算错位。
--   * **continuous_days 冗余快照**：连签天数直接落在当日行上，
--     「连签 3/7 天加发」只需要读昨天一行的快照（+1），不用回扫全月历史；
--     日历查询也直接按行渲染，天然与发放记录一致。
--   * 奖励口径（PLAN §7 默认值）：每日 5 币；连签达 3 天那笔 +10、达 7 天那笔 +30 ——
--     是「达 3/7 天的当天那一次」额外加发，不是每天都有；断签（昨天没签）连签重置为 1。

CREATE TABLE IF NOT EXISTS t_coin_signin (
    id              BIGINT       NOT NULL COMMENT '雪花主键',
    user_id         BIGINT       NOT NULL COMMENT '用户 ID',
    sign_date       DATE         NOT NULL COMMENT '签到日（Asia/Shanghai 本地日）',
    coins           INT          NOT NULL COMMENT '当日实际发放币数（含连签加发）',
    continuous_days INT          NOT NULL COMMENT '当日连签天数快照（断签重置为 1）',
    created_at      DATETIME     NOT NULL COMMENT '创建时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_coin_signin (user_id, sign_date),
    KEY idx_coin_signin_user (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '多多币签到日历（R-P2）';
