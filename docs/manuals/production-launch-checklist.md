# 生产上线检查清单（I-01 准备）

> 目标：从本机 Docker 迁到一台可公网访问的生产 / 预生产主机，完成 HTTPS、支付、备份与验收。
> 当前状态：**已按方案 A 部署到试用机（IP + 端口内测）**，域名 / ICP / HTTPS 待办（见第 0 节）。
> 最后更新：2026-10-28

---

## 0. 需要你提供 / 确认（阻塞项）

| # | 事项 | 你需要给什么 | 为什么阻塞 |
|---|---|---|---|
| 1 | 云主机 | **已部署（方案 A，2026-10-28）**：阿里云 ECS `8.163.71.44`（4C/7.4G/40G，Alinux 3），Docker 26.1.3；`/opt/duoduo` 下 14 容器 healthy | 规格已达标；建议后续加 100G 数据盘（非阻塞） |
| 2 | 域名 | 已注册 `duoduopet.xyz`（阿里云 / HiChina DNS，实名认证通过，2027-10-28 到期）；**当前没有任何 A 记录**（仅 SOA） | 需加 `mall.` / `merchant.` / `admin.duoduopet.xyz` 三条 A → `8.163.71.44` |
| 3 | HTTPS | 控制台显示 SSL 未开启；**80 已放行并作为商城内测入口，443 未启用**；ICP 备案状态待确认 | Cookie、2FA 都要求 HTTPS |
| 4 | 备份目标 | **已确认可用（2026-10-28 实测）**：私有桶 `duoduo-mall-private-1374887902`（ap-guangzhou），签名 / 上传 / HEAD / 删除全部验证通过 | I-02：脚本已就绪，生产只需配 cron |
| 5 | ~~微信支付~~ | **无限期延后（2026-10-28 拍板），不列为部署阻塞项** | 不做，不再规划 |
| 6 | 微信小程序 | AppID / Secret、订阅消息模板 ID、高德小程序 Key | C-06/G-05/G-03 |
| 7 | ~~支付宝（可选）~~ | **不做（2026-10-28 拍板）** | 不列为待办 |
| 8 | 对象存储（可选） | COS SecretId/SecretKey、公开桶、私有桶、区域 | 图片与资质材料；不配则回落本地盘 |
| 9 | 安全清理 | **已完成（2026-10-28 用户确认）：GitHub 只读令牌已吊销、腾讯云密钥已轮换** | 无需再操作 |
| 10 | 测试数据 | 夹具店铺已于 2026-10-27 停业降权（272 家 SUSPENDED）；生产是沿用现有库还是新库初始化，需你确认 | 影响生产数据干净度 |
| 11 | ICP 备案 | 大陆阿里云 ECS 上用域名开 80/443 必须备案（个人/企业均可）；备案通过后 30 天内做公安备案 | 未备案时域名访问会被拦，不是代码问题 |
| 12 | 安全组 | 放行 22 / 80 / 8088 / 8090 / 8091（22 与 8090/8091 限来源 IP）；3306 / 6379 / 8848 不对公网开放 | 80/8088/8090/8091 已放行；443 待 HTTPS 启用 |

> 第 9 项已于 2026-10-28 完成：令牌已吊销、密钥已轮换。

> **当前部署形态（2026-10-28 · 方案 A）**：已部署到 `8.163.71.44`，通过 IP + 端口访问；
> 商城 `http://8.163.71.44`（80，主入口；`:8088` 保留兼容旧链接，部分 WiFi 网络封非标准端口）、
> 商家 `:8091`、后台 `:8090`（限用户 IP），外网三端登录 200，浏览器实访通过。
> 域名 / ICP / HTTPS / 80-443 反代尚未启用，待备案与解析完成后再切正式入口。

---

## 1. 主机初始化

```bash
# 以 Ubuntu 22.04 为例
sudo apt update && sudo apt install -y docker.io docker-compose-plugin git nginx certbot python3-certbot-nginx
sudo usermod -aG docker $USER   # 重新登录生效
```

- 防火墙只开放 22 / 80 / 443；**不要**把 MySQL 3306、Redis 6379、Nacos 8848、各服务 808x 暴露到公网。
- 生产 compose 只暴露网关 8080 与三个前端入口；通过 Nginx 反代到 80/443。
- 建议把 Docker 数据目录放到数据盘（`/data/docker`），避免系统盘被镜像 / 卷撑满。
- **资源上限待主机规格确定后再补**：`docker-compose.yml` 目前只做了日志轮转（20MB × 5 份/容器），
  未预设 `deploy.resources.limits` —— 在 8G 主机上把 JVM 堆压到 128M 会比不限制更糟，
  等主机 CPU / 内存确定后按实际容量一次性配置。

---

## 2. 代码与配置

```bash
git clone git@github.com:1694798740/duoduo-pet-mall-java.git
cd duoduo-pet-mall-java
cp .env.example .env
```

### 2.1 必改的默认值

| 变量 | 生产要求 |
|---|---|
| `INTERNAL_TOKEN` | 随机 32+ 字符，**不能**用 `duoduo-local-dev-internal-token` |
| `DUODUO_JWT_SECRET` | 随机 32+ 字符，**不能**用默认 dev secret |
| `MYSQL_ROOT_PASSWORD` | 强随机密码；同时同步 `DB_PASSWORD`（如有） |
| `NACOS_PASSWORD` | 强随机密码（如使用 Nacos） |
| `DUODUO_SEARCH_ENABLED` | `true`（生产必须启用 ES） |
| `DUODUO_AI_MOCK_ENABLED` | `false` 且配置 `DASHSCOPE_API_KEY`（若要真实 AI） |
| `DUODUO_WECHAT_MOCK_ENABLED` | `false`（真实微信登录） |
| `WECHAT_PAY_ENABLED` | **不适用：真实支付无限期延后（2026-10-28），保持 `false`，生产用沙箱** |
| `PAY_DEFAULT_CHANNEL` | `SANDBOX`（真实支付不做） |
| `COOKIE_SECURE` | **HTTPS 生产必须 `true`**；本地 http 保持 `false`（否则浏览器不保存 Cookie） |
| `COOKIE_SAME_SITE` | `Lax`（默认）；`None` 必须同时 `COOKIE_SECURE=true`，配错会自动按 Lax 处理 |
| `DUODUO_ADMIN_IP_WHITELIST_ENABLED` | 建议 `true`，并先配全白名单（为空会拒绝全部后台访问） |
| `STORAGE_TYPE` | `cos`（配齐 5 个 COS 键）或 `local`（明确接受单机盘） |

> 真实密钥只放 `.env`（已在 `.gitignore`），不要写进 compose / 文档 / 聊天。

### 2.2 支付 / 小程序

- **真实支付无限期延后（2026-10-28 拍板）**：不配置商户号 / 证书 / 回调地址，生产保持沙箱渠道；
  `WECHAT_PAY_NOTIFY_URL` 不填、支付回调节点不对外开。
- 小程序 `WECHAT_APPID` / `WECHAT_SECRET` 按实际填写。
- 订阅消息模板 ID 填到小程序配置（见 C-06 / G-05 文档）。

---

## 3. 启动

```bash
# 先起基础依赖
docker compose up -d mysql redis nacos
# 起 ES（生产必须）
docker compose --profile search up -d duoduo-es
# 构建并起业务
docker compose up -d --build
# 查看健康
docker ps --format '{{.Names}}\t{{.Status}}'
```

- 首次启动会执行各库 `schema.sql` / 初始化 SQL；**生产不要重复执行 `docs/sql/incremental` 里已应用的脚本**。
- 等待全部容器 healthy（尤其是 user / product / trade / social / gateway）。

---

## 4. 反向代理与 HTTPS

Nginx 三站点（示例）：

| 域名 | 上游 | 说明 |
|---|---|---|
| `mall.example.com` | `127.0.0.1:8088` | 商城（买家） |
| `merchant.example.com` | `127.0.0.1:8091` | 商家工作台 |
| `admin.example.com` | `127.0.0.1:8090` | 平台后台（建议叠加 IP 白名单） |

```bash
sudo certbot --nginx -d mall.example.com -d merchant.example.com -d admin.example.com
```

- 强制 HTTP → HTTPS。
- 三个站点必须是**不同主机名**：Cookie 是主机级作用域，换端口无法隔离登录态。
- 后台域名建议只对公司出口 IP 开放（Nginx allow/deny 或安全组）。

### 4.1 每日备份到 COS（I-02）

- 脚本：`scripts/db-backup-cos.sh`（Linux 原生；只用 docker / curl / openssl / gzip，
  **不需要 coscli / 官方 SDK**，签名与 `CosSigner.java` 同一套 v5-sha1 算法）。
- 前置：`.env` 配 `COS_SECRET_ID` / `COS_SECRET_KEY` / `COS_REGION` / `COS_BUCKET_PRIVATE`。
  建议用子用户密钥，只授 `PutObject / HeadObject / DeleteObject`，范围限定私有桶 `db-backup/*`；
  脚本**不列桶、不读对象**，没有 `ListBucket` / `GetObject` 也能跑。
  若与应用上传共用同一把密钥，CAM 策略必须**同时包含** `db-backup/*`（见 `docs/COS-配置说明.md` §3.2）。
- 权限已核验（2026-10-28）：STS `GetCallerIdentity` = `CAMUser`（子账号）；
  `db-backup/*` 上传 / HEAD / 删除通过；列举桶 403（无 `ListBucket`）；探针对象已清理。
- 先自检（不写任何东西）：`bash scripts/db-backup-cos.sh --check`
  —— 返回 404 = 桶可达且签名有效；403 = 密钥或签名不对。
- 手动跑：`bash scripts/db-backup-cos.sh`
  —— 六库 dump → gzip → 上传 → 远端 HEAD 逐文件比对字节数；任一步失败退出码 1。
- 产物：`cos://<private-bucket>/db-backup/<YYYY-MM-DD_HHmmss>/`
  含六个 `*.sql.gz` 与 `_manifest.txt`（每库 sha256 / 表数 / 字节数）。
- 校验口径：源库 `information_schema` 表数 = 产物 `CREATE TABLE` 条数；`gzip -t` 通过；
  远端 `Content-Length` = 本地字节数。空库 / 库不存在直接失败，不会「备份了个空文件还说成功」。
- 定时（每天 03:00，示例）：

  ```bash
  # crontab -e
  0 3 * * * cd /opt/duoduo && bash scripts/db-backup-cos.sh >> /var/log/duoduo-backup.log 2>&1
  ```

- 保留：本地 `BACKUP_DIR`（默认 `/var/backups/duoduo`）只留最近 7 次；
  **远端不删**，由桶生命周期规则回收 —— **已配置（2026-10-28）**：
  `db-backup-expire-30d`（前缀 `db-backup/`，当前版本 30 天删除，开启）。
- 查某次备份是否落地：`bash scripts/db-backup-cos.sh --stat db-backup/<时间戳>/_manifest.txt`
- 恢复（在测试库先演练）：

  ```bash
  gunzip -c duoduo_user.sql.gz | docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" duoduo-mysql mysql -uroot
  ```

- 实测记录（2026-10-28）：六库 23/19/19/15/3/9 张表，产物 1.42MB / 43KB / 533KB / 44KB / 4KB / 162KB，
  上传后 HEAD 逐一对齐；测试对象已全部删除（`--stat` 确认不存在）。

---

## 5. 上线前验收（必须全绿）

### 5.1 自动化

```bash
# 只读健康冒烟（Linux 原生，不登录、不写库；部署后先跑这个）
bash scripts/smoke-linux.sh

# 单测
mvn -o -q test
# 前端构建
cd frontend && npm run build
# 核心冒烟 / UI（需要 pwsh + Playwright，仅限预生产 / 开发环境；生产只跑只读检查）
NO_PROXY="*" node scripts/order-smoke...（按实际脚本名）
NO_PROXY="*" node scripts/e2e/checkout-payment-j03-ui-check.mjs
NO_PROXY="*" node scripts/search-sync-a12-smoke.mjs
NO_PROXY="*" node scripts/staff-permissions-f04-smoke.mjs
NO_PROXY="*" node scripts/e2e/merchant-2fa-f05-ui-check.mjs
```

### 5.2 人工 / 真机

| 项 | 验收 |
|---|---|
| 小程序 | 登录、定位、暗色、录音、富文本、图片灯箱（G-03 / C-03 / J-04） |
| 订阅消息 | 下单 / 发货 / 退款通知能收到（C-06/G-05） |
| 备份 | `bash scripts/db-backup-cos.sh --check` 通过；跑一次全量并用 `--stat` 确认远端存在；恢复到测试库一致（I-02） |
| 安全 | 默认口令全部改掉；后台仅白名单 IP 可访问；令牌吊销与密钥轮换已完成（2026-10-28） |

### 5.3 回滚

- 部署前备份：`scripts/db-backup.ps1` 或 `mysqldump` 全库 + 上传目录。
- 应用回滚：保留上一版镜像 tag；`docker compose up -d` 指定旧 tag。
- 数据回滚：只恢复备份，**不要**手工改资金 / 订单表。

---

## 6. 上线后前 72 小时

- 每 2 小时看：容器健康、5xx、退款积压、ES 索引文档数。
- 每天：备份校验、审计日志巡检、租户健康度高风险项。
- 出现资金 / 权限异常：先止血（关闭渠道 / 停用账号），再定位，不要直接改库。

---

## 7. 与待办表的对应

| 待办 | 本文档位置 |
|---|---|
| I-01 生产部署 | 第 1~4 节 |
| I-02 备份第二存储 | 第 0 节 #4、第 4.1 节、第 5.2 节 |
| I-05 / I-06 令牌与密钥 | ✅ 2026-10-28 已完成（无需再操作） |
| ~~B-01 / B-02 真实支付~~ | 无限期延后（2026-10-28 拍板），不列入上线验收 |
| C-06 / G-05 订阅消息 | 第 2.2 节 |
| C-03 / G-03 / J-04 真机 | 第 5.2 节 |
| I-03 夹具店铺清理 | 第 0 节 #10 |
