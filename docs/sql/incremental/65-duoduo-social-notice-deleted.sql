-- 通知删除集合（2026-10-27 · 通知中心批量删除）
--
-- 为什么不是给 t_social_notice 加 deleted_at 一列：
--   公告是「一行多读者」（user_id = 0），加行级删除列就等于「谁先删、所有人都看不见」，
--   这是最典型的一类跨用户互相影响事故。
-- 删除在这里的语义是「对我不可见」，所以用 (notice_id, user_id) 集合表，与已读集合
-- t_social_notice_read 完全同构；复合主键保证幂等（重复删除撞主键即吞掉）。
--
-- 已有环境手工执行（新环境由 06-duoduo-social.sql 直接带入）：
--   docker exec -i duoduo-mysql mysql -uroot -pduoduo_root duoduo_social \
--     < docs/sql/incremental/65-duoduo-social-notice-deleted.sql
CREATE TABLE IF NOT EXISTS t_social_notice_deleted (
    notice_id   BIGINT    NOT NULL,
    user_id     BIGINT    NOT NULL,
    deleted_at  TIMESTAMP NOT NULL,
    PRIMARY KEY (notice_id, user_id)
);
