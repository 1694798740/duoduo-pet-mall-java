-- ============================================================================
-- B-11 · 闲置担保交易（C2C）t_order_item 对无 SKU 单件的承载
-- 幂等：全部为 DDL 变更，重复执行 MODIFY/ADD COLUMN 会在 MySQL 8 报同一列存在
-- 的错误 —— 由部署脚本控制只执行一次；这里不做存储过程式判断（项目惯例：
-- 增量 SQL 只执行一次，执行前备份、执行后核对）。
-- ============================================================================

-- 1) spu_id / sku_id 放开 NOT NULL：闲置是「一物一价」的个人转卖，不在 SKU 体系里，
--    C2C 订单明细只带 listing_id + 标题/价格快照。商城订单两列仍然必填
--    （NOT NULL 约束放开后，写入端各自保证：商城单必带，闲置单必空 —— 代码层已互斥）。
ALTER TABLE t_order_item
    MODIFY COLUMN spu_id BIGINT NULL COMMENT 'SPU id（C2C 闲置单为 NULL，与 listing_id 互斥）',
    MODIFY COLUMN sku_id BIGINT NULL COMMENT 'SKU id（C2C 闲置单为 NULL，与 listing_id 互斥）';

-- 2) listing_id：闲置归属列 —— 关单/退款钩子凭它找到该释放的闲置；
--    卖出列表、订单详情回链也读它。
ALTER TABLE t_order_item
    ADD COLUMN listing_id BIGINT NULL COMMENT '闲置 id（C2C 单）' AFTER order_id;

CREATE INDEX idx_item_listing ON t_order_item (listing_id);

-- 验证（执行后人工核对一次）：
--   SHOW COLUMNS FROM t_order_item LIKE '%_id';
--   预期 order_id NOT NULL / spu_id NULL / sku_id NULL / listing_id NULL
