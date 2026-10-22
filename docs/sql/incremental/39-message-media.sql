-- ============================================================================
-- 多多宠物商店 · 增量 39：消息媒体字段（第七十九轮 B 批）
--
-- 客服与私信统一扩展：图片 / 视频 / 表情包 / 订单卡都靠这几列承载。
-- 渲染约定：
--   biz_type = IMAGE / VIDEO / EMOJI / ORDER / PRODUCT / TEXT
--   media_url  服务端生成的存储地址（不接受任意外链）
--   thumb_url  视频封面（端侧截帧，可为空 → 用 <video preload=metadata> 兜底）
--   meta_json  卡片快照（标题/价格/订单号后四位等），商品下架时仍有内容可展示
-- ============================================================================
SET NAMES utf8mb4;
USE `duoduo_social`;

DROP PROCEDURE IF EXISTS add_col_if_missing;
DELIMITER $$
CREATE PROCEDURE add_col_if_missing(IN tbl VARCHAR(64), IN col VARCHAR(64), IN ddl VARCHAR(255))
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = 'duoduo_social' AND TABLE_NAME = tbl AND COLUMN_NAME = col) THEN
        SET @s = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', ddl);
        PREPARE stmt FROM @s;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

CALL add_col_if_missing('t_support_message', 'media_url',   'VARCHAR(500) NULL');
CALL add_col_if_missing('t_support_message', 'thumb_url',   'VARCHAR(500) NULL');
CALL add_col_if_missing('t_support_message', 'width',       'INT NULL');
CALL add_col_if_missing('t_support_message', 'height',      'INT NULL');
CALL add_col_if_missing('t_support_message', 'duration_ms', 'INT NULL');
CALL add_col_if_missing('t_support_message', 'meta_json',   'VARCHAR(1000) NULL');

CALL add_col_if_missing('t_social_message', 'media_url',   'VARCHAR(500) NULL');
CALL add_col_if_missing('t_social_message', 'thumb_url',   'VARCHAR(500) NULL');
CALL add_col_if_missing('t_social_message', 'width',       'INT NULL');
CALL add_col_if_missing('t_social_message', 'height',      'INT NULL');
CALL add_col_if_missing('t_social_message', 'duration_ms', 'INT NULL');
CALL add_col_if_missing('t_social_message', 'meta_json',   'VARCHAR(1000) NULL');

DROP PROCEDURE IF EXISTS add_col_if_missing;
