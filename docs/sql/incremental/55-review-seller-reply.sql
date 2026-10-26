SET NAMES utf8mb4;
USE `duoduo_fulfilment`;

-- A-16 · 商家回复评价（2026-10-26）
-- 一条评价最多一次回复：靠 reply_content 是否为空判断，不另建回复表。
-- 回复是公开内容，展示在商品评价区；匿名评价仍只暴露脱敏昵称，回复不携带买家身份。
ALTER TABLE t_review
    ADD COLUMN reply_content VARCHAR(500) NULL
        COMMENT '商家回复内容（每条评价最多一次）'
        AFTER append_at,
    ADD COLUMN reply_at TIMESTAMP NULL
        COMMENT '商家回复时间（UTC）'
        AFTER reply_content,
    ADD COLUMN replied_by BIGINT NULL
        COMMENT '回复的商家账号 userId（与 seller_id 同口径；平台自营由运营代回复）'
        AFTER reply_at;
