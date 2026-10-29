-- =========================================================
-- 72 · AI 导购 RAG + Agent + 记忆（2026-10-28）
-- 归属：duoduo_product（商品 AI 属性）、duoduo_ai（会话/记忆/知识库）
-- =========================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `duoduo_ai`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

GRANT ALL PRIVILEGES ON `duoduo_ai`.* TO 'duoduo'@'%';
FLUSH PRIVILEGES;

-- ---------------------------------------------------------
-- 1. 商品 AI 属性（duoduo_product 库）
-- ---------------------------------------------------------
USE `duoduo_product`;

CREATE TABLE IF NOT EXISTS `t_spu_ai_profile` (
  `spu_id` BIGINT NOT NULL COMMENT '商品 SPU ID',
  `species` VARCHAR(16) NOT NULL DEFAULT 'other'
    COMMENT '主适用物种：cat/dog/small_pet/bird/fish/reptile/other',
  `life_stages` VARCHAR(64) NOT NULL DEFAULT ''
    COMMENT '适用生命阶段：BABY,ADULT,SENIOR，逗号分隔',
  `breed_sizes` VARCHAR(64) NOT NULL DEFAULT ''
    COMMENT '适用体型：SMALL,MEDIUM,LARGE，逗号分隔',
  `allergens` JSON NULL COMMENT '过敏原/忌口，如 ["鸡肉","谷物"]',
  `function_tags` JSON NULL COMMENT '功能标签，如 ["肠胃敏感","美毛","关节"]',
  `package_size` VARCHAR(32) DEFAULT NULL COMMENT '包装规格，如 2kg / 10L',
  `package_weight_g` INT DEFAULT NULL COMMENT '标准化克数，便于筛选大袋/小袋',
  `content_summary` TEXT NULL COMMENT '给模型看的商品摘要（非营销文案）',
  `search_text` MEDIUMTEXT NULL COMMENT '标题+副标题+属性+摘要，供 BM25',
  `source_version` BIGINT NOT NULL DEFAULT 0 COMMENT '商品版本，用于增量同步',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`spu_id`),
  KEY `idx_species_stage` (`species`, `life_stages`),
  KEY `idx_package_weight` (`package_weight_g`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='商品 AI 属性（RAG 结构化过滤用）';

-- ---------------------------------------------------------
-- 2. 长期记忆（duoduo_ai 库）
-- ---------------------------------------------------------
USE `duoduo_ai`;

CREATE TABLE IF NOT EXISTS `t_ai_user_memory` (
  `id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `preferred_species` VARCHAR(16) DEFAULT NULL,
  `preferred_categories` JSON NULL,
  `preferred_brands` JSON NULL,
  `budget_min_cents` BIGINT DEFAULT NULL,
  `budget_max_cents` BIGINT DEFAULT NULL,
  `purchase_cycle_days` INT DEFAULT NULL,
  `last_purchase_at` DATETIME(3) DEFAULT NULL,
  `consent_version` VARCHAR(16) DEFAULT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='用户长期偏好记忆（AI 域派生）';

CREATE TABLE IF NOT EXISTS `t_ai_pet_memory` (
  `id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `pet_id` BIGINT NOT NULL COMMENT '用户域宠物档案 ID',
  `pet_name` VARCHAR(64) DEFAULT NULL,
  `species` VARCHAR(16) DEFAULT NULL,
  `breed` VARCHAR(64) DEFAULT NULL,
  `birthday` DATE DEFAULT NULL,
  `weight_kg` DECIMAL(5,2) DEFAULT NULL,
  `life_stage` VARCHAR(16) DEFAULT NULL COMMENT 'BABY/ADULT/SENIOR',
  `allergens` JSON NULL,
  `health_notes` TEXT NULL,
  `source` VARCHAR(16) NOT NULL DEFAULT 'PROFILE',
  `confidence` DECIMAL(3,2) NOT NULL DEFAULT 0.80,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_pet` (`user_id`, `pet_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='宠物长期记忆（AI 域派生，用户可查看/删除）';

-- ---------------------------------------------------------
-- 3. 宠物百科知识库
-- ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_ai_knowledge_doc` (
  `id` BIGINT NOT NULL,
  `doc_key` VARCHAR(64) NOT NULL,
  `title` VARCHAR(200) NOT NULL,
  `species` VARCHAR(16) DEFAULT NULL,
  `breed` VARCHAR(64) DEFAULT NULL,
  `life_stage` VARCHAR(16) DEFAULT NULL,
  `topic` VARCHAR(32) DEFAULT NULL,
  `urgency` VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
  `content` MEDIUMTEXT NOT NULL,
  `source` VARCHAR(200) DEFAULT NULL,
  `source_url` VARCHAR(500) DEFAULT NULL,
  `version` INT NOT NULL DEFAULT 1,
  `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doc_key` (`doc_key`),
  KEY `idx_topic` (`species`, `life_stage`, `topic`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='宠物百科知识文档';

CREATE TABLE IF NOT EXISTS `t_ai_knowledge_chunk` (
  `id` BIGINT NOT NULL,
  `doc_id` BIGINT NOT NULL,
  `chunk_no` INT NOT NULL,
  `content` TEXT NOT NULL,
  `token_count` INT DEFAULT NULL,
  `embedding_status` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  `es_synced_at` DATETIME(3) DEFAULT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doc_chunk` (`doc_id`, `chunk_no`),
  KEY `idx_embedding_status` (`embedding_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='知识文档切片（ES 索引事实源）';

-- ---------------------------------------------------------
-- 4. 会话、消息、工具审计、反馈
-- ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_ai_chat_session` (
  `session_id` VARCHAR(64) NOT NULL,
  `user_id` BIGINT DEFAULT NULL,
  `summary` TEXT NULL,
  `turn_count` INT NOT NULL DEFAULT 0,
  `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  `started_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `last_active_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`session_id`),
  KEY `idx_user_time` (`user_id`, `last_active_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='AI 会话审计（运行时状态在 Redis）';

CREATE TABLE IF NOT EXISTS `t_ai_chat_message` (
  `id` BIGINT NOT NULL,
  `session_id` VARCHAR(64) NOT NULL,
  `role` VARCHAR(16) NOT NULL,
  `content` MEDIUMTEXT NULL,
  `tool_name` VARCHAR(64) DEFAULT NULL,
  `tool_call_id` VARCHAR(64) DEFAULT NULL,
  `tool_args` JSON NULL,
  `tool_result` JSON NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_session_time` (`session_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='AI 会话消息审计/回放';

CREATE TABLE IF NOT EXISTS `t_ai_tool_call_log` (
  `id` BIGINT NOT NULL,
  `request_id` VARCHAR(64) NOT NULL,
  `session_id` VARCHAR(64) NOT NULL,
  `user_id` BIGINT DEFAULT NULL,
  `tool_name` VARCHAR(64) NOT NULL,
  `args_json` JSON NULL,
  `result_json` JSON NULL,
  `status` VARCHAR(16) NOT NULL,
  `latency_ms` INT DEFAULT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_request_tool` (`request_id`, `tool_name`),
  KEY `idx_session_time` (`session_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='工具调用审计（幂等与回放）';

CREATE TABLE IF NOT EXISTS `t_ai_feedback` (
  `id` BIGINT NOT NULL,
  `session_id` VARCHAR(64) NOT NULL,
  `message_id` BIGINT DEFAULT NULL,
  `user_id` BIGINT DEFAULT NULL,
  `rating` TINYINT NOT NULL COMMENT '1=有用 0=无用',
  `comment` VARCHAR(500) DEFAULT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='对话反馈（评测与 badcase 回流）';

-- ---------------------------------------------------------
-- 5. 宠物百科种子（先放 6 条，后续由运营补充）
-- ---------------------------------------------------------
INSERT INTO `t_ai_knowledge_doc`
  (`id`, `doc_key`, `title`, `species`, `life_stage`, `topic`, `urgency`, `content`, `source`, `version`, `status`)
VALUES
  (900000000000000001, 'dog-diarrhea-red-flags', '狗狗腹泻：居家观察与就医红线', 'dog', NULL, 'health', 'RED_FLAG',
   '狗狗腹泻先观察精神状态、食欲饮水、排便次数与性状。轻微软便且精神正常时，可先禁食 4-6 小时、少量多次补水，并保留原饮食观察 24 小时。出现便血/黑便、持续呕吐超过 24 小时、精神沉郁、不吃不喝、明显腹痛、抽搐或误食毒物，应立即就医。幼犬腹泻进展快，建议更早就医。不要自行使用人用药。',
   '平台宠物健康知识库（兽医审核）', 1, 'ACTIVE'),
  (900000000000000002, 'kitten-feeding-basics', '幼猫喂养：月龄与主粮选择', 'cat', 'BABY', 'feeding', 'NORMAL',
   '幼猫 4 周龄前以母乳或幼猫配方奶为主；4-8 周逐步引入幼猫湿粮/离乳粮；2-12 月龄选择标注幼猫全价的主粮，蛋白质与热量需求高于成猫。换粮用 7 天过渡法：旧粮 75% → 50% → 25% → 0。幼猫不建议长期吃成猫粮。',
   '平台宠物营养知识库', 1, 'ACTIVE'),
  (900000000000000003, 'cat-litter-selection', '猫砂怎么选：结团、粉尘与除臭', 'cat', NULL, 'supplies', 'NORMAL',
   '猫砂优先看结团速度、粉尘量、除臭方式与猫咪接受度。膨润土结团好但粉尘相对高；豆腐砂粉尘低、可冲厕但结团略弱；混合砂兼顾结团与低粉尘。多猫家庭优先大袋装与高除臭款；换砂要逐步混入旧砂，避免猫咪拒用。',
   '平台宠物用品知识库', 1, 'ACTIVE'),
  (900000000000000004, 'food-transition-7days', '换粮为什么需要 7 天过渡', 'cat', NULL, 'feeding', 'NORMAL',
   '突然换粮容易引起软便、呕吐或拒食。建议 7 天过渡：第 1-2 天新粮 25%，第 3-4 天 50%，第 5-6 天 75%，第 7 天全换。肠胃敏感宠物可延长到 10-14 天。过渡期间观察排便与食欲，出现持续腹泻应减慢或暂停。',
   '平台宠物营养知识库', 1, 'ACTIVE'),
  (900000000000000005, 'pet-allergy-basics', '宠物食物过敏：常见过敏原与选粮', 'cat', NULL, 'health', 'WATCH',
   '犬猫常见食物过敏原包括牛肉、鸡肉、乳制品、谷物、鸡蛋与鱼类。怀疑食物过敏时，应在兽医指导下做 8-12 周排除饮食试验，选择单一蛋白/水解蛋白主粮，并严格避免零食与餐桌食物。不要仅凭一次软便判断过敏。',
   '平台宠物健康知识库（兽医审核）', 1, 'ACTIVE'),
  (900000000000000006, 'dog-joint-care', '狗狗关节养护：体重、运动与营养', 'dog', 'ADULT', 'health', 'WATCH',
   '关节养护首先控制体重，避免超重增加关节负担；保持规律、低冲击运动（游泳、慢走），避免频繁跳跃与急停。可关注含葡萄糖胺、软骨素、Omega-3 的关节配方，但营养补充不能替代兽医诊断。出现跛行、起立困难、触碰疼痛应及时就医。',
   '平台宠物健康知识库（兽医审核）', 1, 'ACTIVE')
ON DUPLICATE KEY UPDATE `updated_at` = CURRENT_TIMESTAMP(3);
