-- 运营后台队列的「已查看」集合（2026-10-27 · 点击详情即消除）
--
-- 平台管理后台的五类待办里，评价 / 举报 / 售后仲裁都是「点开详情看过了」就该从
-- 导航角标里减掉的。已读事实必须由服务端保存：前端本地减一只能骗过当前这台机器，
-- 换台设备或刷新后数字会「复活」。
--
-- 与 t_after_sale_read 同构：复合主键 (user_id, source, biz_id) + INSERT IGNORE 幂等。
-- source 取值：REVIEW（评价）/ REPORT（举报）/ AFTER_SALE（售后仲裁）。
-- 一个表覆盖三类，是因为它们的语义完全相同（谁看过哪条），
-- 拆三张表只会让清理任务与权限核查各写三份。
--
-- 已有环境手工执行：
--   docker exec -i duoduo-mysql mysql -uroot -pduoduo_root duoduo_fulfilment \
--     < docs/sql/incremental/69-duoduo-fulfilment-admin-queue-read.sql
CREATE TABLE IF NOT EXISTS t_admin_queue_read (
    user_id BIGINT      NOT NULL,
    source  VARCHAR(20) NOT NULL,
    biz_id  BIGINT      NOT NULL,
    read_at TIMESTAMP   NOT NULL,
    PRIMARY KEY (user_id, source, biz_id)
);
