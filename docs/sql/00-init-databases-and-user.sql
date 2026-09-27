-- ============================================================================
-- 多多宠物商店 · 容器化初始化：建库 + 建账号 + 授权
-- ============================================================================
-- 由 docker-compose.yml 挂载到 MySQL 容器的 /docker-entrypoint-initdb.d，
-- 仅在数据卷为空的**首次启动**时执行（文件名前缀 00 保证最先执行）。
--
-- 账号口径：应用统一使用 duoduo / duoduo 连接（见各服务 application-mysql.yml）。
--           生产环境必须换成独立账号 + 最小权限 + 密钥托管，禁止沿用本文件的开发口令。
-- ============================================================================

-- 注意：必须显式声明客户端字符集，否则中文会被「双重编码」写入
-- （客户端按 latin1 发送 UTF-8 字节 → 库里存成乱码 → 中文关键词检索永远 0 命中）。
SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `duoduo_user`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `duoduo_product`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `duoduo_trade`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `duoduo_fulfilment`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `duoduo_marketing`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `duoduo_social`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `nacos`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 应用账号：只授予业务库权限，不给 nacos 库
CREATE USER IF NOT EXISTS 'duoduo'@'%' IDENTIFIED BY 'duoduo';
GRANT ALL PRIVILEGES ON `duoduo_user`.*       TO 'duoduo'@'%';
GRANT ALL PRIVILEGES ON `duoduo_product`.*    TO 'duoduo'@'%';
GRANT ALL PRIVILEGES ON `duoduo_trade`.*      TO 'duoduo'@'%';
GRANT ALL PRIVILEGES ON `duoduo_fulfilment`.* TO 'duoduo'@'%';
GRANT ALL PRIVILEGES ON `duoduo_marketing`.*  TO 'duoduo'@'%';
GRANT ALL PRIVILEGES ON `duoduo_social`.*     TO 'duoduo'@'%';
FLUSH PRIVILEGES;
