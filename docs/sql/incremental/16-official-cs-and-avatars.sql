-- ============================================================================
-- 多多宠物商店 · 增量 16（第四十八轮）：官方客服账号 + 自营商品归属 + 卖家头像
-- 背景：① 自营（supply_type='self'）商品 seller_id=0，「问一问」一律
--         422「该商品暂无卖家，无法咨询」——用户预期是所有商品都能咨询。
--         把自营商品统一归属到「多多官方客服」账号后，问一问语义变为
--         「咨询官方客服」，复用全部既有私信链路（服务端定人不变）。
--       ② 种子卖家 / 新建账号 avatar 为空，聊天页与卖家卡片显示兜底图；
--         本次给种子卖家与官方客服补包内静态头像。
-- 方案：官方客服 id 使用保留段 710000000000000001（7 开头雪花段，不与
--       应用生成 id 冲突）；密码与测试账号一致（duoduo12345 的 BCrypt），
--       仅供联调，生产环境应另行生成并改密。
-- 对已有环境执行；全新环境在 01（DDL）+ 02（商品种子）之后同样执行。
-- ============================================================================
SET NAMES utf8mb4;
USE `duoduo_user`;

-- 1) 官方客服账号（幂等：存在则跳过）
INSERT INTO t_user (id, phone, password_hash, nickname, avatar, status, role, credit_score, created_at, updated_at)
SELECT 710000000000000001, '13900000002',
       '$2a$10$bg4JYRjcAg6hIEsmM.mt3.NWZzksEhsUA6CqEQFAb0xcnCjp9xe9G',
       '多多官方客服', '/images/avatars/cs-official.svg', 'ACTIVE', 'SELLER', 100, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM t_user WHERE phone = '13900000002');

-- 2) 种子卖家补头像（按手机号定位，不硬编码雪花 ID）
UPDATE t_user SET avatar = '/images/avatars/seller-a.svg'
WHERE phone = '13550931548' AND role = 'SELLER' AND (avatar IS NULL OR avatar = '');
UPDATE t_user SET avatar = '/images/avatars/seller-b.svg'
WHERE phone = '13765569756' AND role = 'SELLER' AND (avatar IS NULL OR avatar = '');

USE `duoduo_product`;

-- 3) 自营商品归属官方客服（幂等：只动仍为 0 的自营商品）
UPDATE t_spu
SET seller_id = (SELECT id FROM duoduo_user.t_user WHERE phone = '13900000002' AND role = 'SELLER')
WHERE supply_type = 'self' AND seller_id = 0;
