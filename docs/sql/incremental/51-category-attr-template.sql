-- 51 · 类目属性模板（2026-10-24，A-10）
--
-- 问题：规格参数是自由 KV —— 类目无法要求「净含量」必填，也无法约束
-- 「适用阶段必须从固定选项里选」，商品数据能填但不可校验。
--
-- 方案：每个类目一张属性模板（属性名 / 输入类型 / 是否必填 / 选项 / 排序），
-- 属性值仍存 t_spu.spec_params（KV，按属性名匹配）。模板只做
-- 「建议 + 必填 / 类型 / 选项校验」：历史自由 KV 继续可用，旧商品不改 spec_params
-- 时不阻断编辑（新增商品发布必须满足必填项）。
--
-- 为什么本期不做独立属性值表：验收口径只要求「必填缺失拒绝发布」；
-- 独立值表还要配套筛选、比价、历史回填，半套结构反而会制造两份事实。
--
-- 部署顺序：先执行本脚本，再发布商品服务。
SET NAMES utf8mb4;
USE `duoduo_product`;

CREATE TABLE IF NOT EXISTS t_category_attr (
    id          BIGINT        NOT NULL COMMENT '雪花 ID',
    category_id BIGINT        NOT NULL COMMENT '所属类目 t_category.id',
    name        VARCHAR(30)   NOT NULL COMMENT '属性名（商品 spec_params 按此名匹配）',
    input_type  VARCHAR(16)   NOT NULL DEFAULT 'TEXT' COMMENT 'TEXT / NUMBER / SELECT',
    is_required TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '1 = 发布 / 改规格时必填',
    options     VARCHAR(1000)          COMMENT 'SELECT 可选值 JSON 数组；其他类型为空',
    sort_no     INT           NOT NULL DEFAULT 0 COMMENT '越小越靠前',
    status      VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / INACTIVE',
    created_at  TIMESTAMP     NOT NULL COMMENT 'UTC',
    updated_at  TIMESTAMP     NOT NULL COMMENT 'UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_category_attr_name (category_id, name),
    KEY idx_category_attr (category_id, status, sort_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '类目属性模板（A-10）';
