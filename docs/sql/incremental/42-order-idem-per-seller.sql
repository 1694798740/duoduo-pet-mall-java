-- 42 · 拆单幂等键按卖家维度唯一（2026-10-22）
--
-- 背景（事故根因）：
--   一个购物车里有两家卖家的货时，placeOrder 会按卖家拆成多张子单，
--   而每张子单都写同一个客户端幂等键 idempotent_key。
--   唯一索引 uk_order_user_idem(user_id, idempotent_key) 于是把“第二张子单”
--   当成并发重复提交拦下 —— 撞的不是别人，是同一批拆单里的自己人。
--   更糟的是撞键分支当时在事务内“补偿后 return”，把第一张子单连同它的台账提交了，
--   但它的冻结库存已被补偿释放 → 订单永远付不进款。
--
-- 修复：唯一性应当是“同一用户 + 同一幂等键 + 同一卖家只允许一单”，
--       卖家维度必须进唯一索引。
--
-- 幂等：本脚本可重复执行（先探测索引列，已是三列则跳过）。

SET @idx_cols := (
    SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 't_order'
      AND INDEX_NAME = 'uk_order_user_idem'
);

SET @sql := IF(@idx_cols = 'user_id,idempotent_key,seller_id',
    'SELECT ''uk_order_user_idem 已含 seller_id，跳过'' AS result',
    'ALTER TABLE t_order DROP INDEX uk_order_user_idem, ADD UNIQUE KEY uk_order_user_idem (user_id, idempotent_key, seller_id)'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_order' AND INDEX_NAME = 'uk_order_user_idem'
GROUP BY INDEX_NAME;
