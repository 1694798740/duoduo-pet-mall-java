-- A8 · 收货地址簿 建表（幂等，可重复执行）
SET NAMES utf8mb4;
USE `duoduo_user`;

CREATE TABLE IF NOT EXISTS `t_user_address` (
    `id`             BIGINT       NOT NULL COMMENT '雪花 ID',
    `user_id`        BIGINT       NOT NULL COMMENT '归属用户',
    `receiver_name`  VARCHAR(50)  NOT NULL COMMENT '收货人姓名',
    `receiver_phone` VARCHAR(20)  NOT NULL COMMENT '收货手机号',
    `province`       VARCHAR(30)  NOT NULL COMMENT '省 / 直辖市',
    `city`           VARCHAR(30)  NOT NULL COMMENT '市',
    `district`       VARCHAR(30)  NOT NULL COMMENT '区 / 县',
    `detail`         VARCHAR(200) NOT NULL COMMENT '详细地址',
    `label`          VARCHAR(20)           DEFAULT NULL COMMENT '标签（家/公司）',
    `is_default`     TINYINT      NOT NULL DEFAULT 0 COMMENT '默认地址；同一用户至多一条为 1',
    `created_at`     TIMESTAMP    NOT NULL,
    `updated_at`     TIMESTAMP    NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_address_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '收货地址簿';

SELECT 'DDL-OK' AS result, COUNT(*) AS address_table_rows
FROM information_schema.tables
WHERE table_schema = 'duoduo_user' AND table_name = 't_user_address';
