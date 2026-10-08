-- E10 · 入驻材料表
--
-- 每类材料一张（重传覆盖）：uk_merchant_type 保证「同一申请同一类只有最新一份」，
-- 审核台看到的永远是最新提交 —— 历史版本审计如有需要，走 binlog/留痕表而不是 here。
-- file_url 现阶段指向公开的 /uploads/**（A15 通道）；E12 资质材料私有通道落地后
-- 整列迁移到私有存储并按租户分目录 —— 本表结构不需要变，只换存储语义。

CREATE TABLE IF NOT EXISTS duoduo_user.t_merchant_material (
    id            BIGINT       NOT NULL COMMENT '雪花 id',
    merchant_id   BIGINT       NOT NULL COMMENT '所属申请（t_merchant.id）',
    material_type VARCHAR(40)  NOT NULL COMMENT '材料类型（6 类，见 MerchantMaterialType）',
    file_url      VARCHAR(512) NOT NULL COMMENT '材料文件引用（现阶段 /uploads/**；E12 迁私有通道）',
    original_name VARCHAR(255) NULL COMMENT '上传时的原始文件名（仅展示用，不做安全依据）',
    uploaded_by   BIGINT       NOT NULL COMMENT '上传人（店主 user id）',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_merchant_type (merchant_id, material_type),
    KEY idx_merchant (merchant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '入驻资质材料（E10）';
