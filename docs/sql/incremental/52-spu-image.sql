-- 52 · 商品图片表（2026-10-25，A-11）
--
-- 问题：商品图片只有 t_spu.main_image + t_spu.images 两列，后者是逗号串——
-- 顺序只能靠字符串位置，图片没有类型（主图 / 副图）与审核态，平台无法
-- 对单张图做判定，也无法在不重写整串的情况下调整顺序。
--
-- 方案：新增 t_spu_image，一条图片一行：类型（MAIN / GALLERY）、排序号、
-- 审核态（PENDING / APPROVED / REJECTED）。本期**双写**：
--   * 写侧：商品发布 / 编辑 / 审核 / 重提 / 抽查驳回 / 物理清理都在同一事务里
--     同步本表；t_spu.main_image / images 继续写，供旧读路径与回滚使用。
--   * 读侧：现有接口仍读 CSV（展示口径零变化）；表是排序 / 单图审核的
--     唯一权威来源，后续读路径可平滑切换。
--
-- 语义约定：
--   * MAIN 固定 sort_no = 0；GALLERY 从 1 连续递增；
--   * GALLERY 不含与主图相同的 URL（历史 CSV 里常把主图重复放在第一项，
--     前台读取时本来就会去重，这里保持同一展示口径）；
--   * audit_status 跟随商品审核态：商品通过 / 自动放行 → APPROVED，
--     待审 → PENDING，驳回 / 抽查判违规 → REJECTED。单图没有独立驳回原因，
--     因此不引入「部分图片被拒」这种商品层看不到的状态。
--
-- 回填：按 main_image 与 images 机械拆分，不猜内容；重复执行安全
-- （已有图片行的 SPU 整组跳过）。
--
-- 部署顺序：先执行本脚本，再发布商品服务（新代码会双写本表）。
SET NAMES utf8mb4;
USE `duoduo_product`;

CREATE TABLE IF NOT EXISTS t_spu_image (
    id           BIGINT       NOT NULL COMMENT '雪花 ID',
    spu_id       BIGINT       NOT NULL COMMENT 't_spu.id',
    url          VARCHAR(500) NOT NULL COMMENT '图片地址',
    image_type   VARCHAR(20)  NOT NULL COMMENT 'MAIN / GALLERY',
    sort_no      INT          NOT NULL DEFAULT 0 COMMENT '同类型内升序，0 = 主图',
    audit_status VARCHAR(20)  NOT NULL DEFAULT 'APPROVED' COMMENT 'PENDING / APPROVED / REJECTED（跟随商品审核态）',
    created_at   TIMESTAMP    NOT NULL COMMENT 'UTC',
    updated_at   TIMESTAMP    NOT NULL COMMENT 'UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_spu_image_slot (spu_id, image_type, sort_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品图片（A-11；与 t_spu.main_image/images 双写）';

-- 主图：每个 SPU 至多一行 MAIN
INSERT INTO t_spu_image (id, spu_id, url, image_type, sort_no, audit_status, created_at, updated_at)
SELECT UUID_SHORT(), s.id, TRIM(s.main_image), 'MAIN', 0,
       COALESCE(NULLIF(s.audit_status, ''), 'APPROVED'), NOW(), NOW()
FROM t_spu s
WHERE s.main_image IS NOT NULL AND TRIM(s.main_image) <> ''
  AND NOT EXISTS (SELECT 1 FROM t_spu_image i
                  WHERE i.spu_id = s.id AND i.image_type = 'MAIN');

-- 副图：拆分 CSV，去掉空项 / 主图重复项；sort_no 在过滤后重新连续编号。
-- ROW_NUMBER 放在过滤后的外层：否则主图恰好在第一项时，唯一一张副图的 sort 会从 2 开始，
-- 与服务层「从 1 连续」的写入口径不一致，下一次编辑就会无谓地整组重建。
INSERT INTO t_spu_image (id, spu_id, url, image_type, sort_no, audit_status, created_at, updated_at)
WITH RECURSIVE seq (n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 50
),
raw AS (
    SELECT s.id AS spu_id,
           TRIM(SUBSTRING_INDEX(SUBSTRING_INDEX(s.images, ',', seq.n), ',', -1)) AS url,
           seq.n AS ord,
           TRIM(s.main_image) AS main_image,
           COALESCE(NULLIF(s.audit_status, ''), 'APPROVED') AS audit_status
    FROM t_spu s
    JOIN seq ON seq.n <= 1 + (LENGTH(s.images) - LENGTH(REPLACE(s.images, ',', '')))
    WHERE s.images IS NOT NULL AND TRIM(s.images) <> ''
)
SELECT UUID_SHORT(), x.spu_id, x.url, 'GALLERY',
       ROW_NUMBER() OVER (PARTITION BY x.spu_id ORDER BY x.ord),
       x.audit_status, NOW(), NOW()
FROM (SELECT r.* FROM raw r
      WHERE r.url <> ''
        AND (r.main_image IS NULL OR r.url <> r.main_image)) x
WHERE NOT EXISTS (SELECT 1 FROM t_spu_image i
                  WHERE i.spu_id = x.spu_id AND i.image_type = 'GALLERY');
