# 生产上线检查清单（I-01 准备）

> 目标：从本机 Docker 迁到一台可公网访问的生产 / 预生产主机，完成 HTTPS、支付、备份与验收。
> 当前状态：**清单已就绪，等待你提供主机、域名与凭据**（见第 0 节）。
> 最后更新：2026-10-26

---

## 0. 需要你提供 / 确认（阻塞项）

| # | 事项 | 你需要给什么 | 为什么阻塞 |
|---|---|---|---|
| 1 | 云主机 | 公网 IP、SSH 用户 / 密钥、操作系统（建议 Ubuntu 22.04+）、配置（建议 ≥4C8G / 100GB SSD） | 没有主机无法部署 |
| 2 | 域名 | 商城 / 商家 / 后台三个域名（如 `mall.example.com`、`merchant.example.com`、`admin.example.com`）及 DNS 管理权 | HTTPS 与入口隔离依赖独立主机名 |
| 3 | HTTPS | 是否用 Let's Encrypt 自动签发；或提供已有证书 | 支付回调、Cookie、2FA 都要求 HTTPS |
| 4 | 备份目标 | 第二块盘路径，或对象存储桶（COS/OSS/S3）与只写凭据 | I-02：库与备份不能同盘 |
| 5 | 微信支付 | 商户号、API v3 Key、商户私钥、证书序列号、平台证书、AppID、回调域名 | B-01/B-02：没有凭据只能用沙箱 |
| 6 | 微信小程序 | AppID / Secret、订阅消息模板 ID、高德小程序 Key | C-06/G-05/G-03 |
| 7 | 支付宝（可选） | APPID、应用私钥、支付宝公钥、回调地址 | 若生产要开支付宝渠道 |
| 8 | 对象存储（可选） | COS SecretId/SecretKey、公开桶、私有桶、区域 | 图片与资质材料；不配则回落本地盘 |
| 9 | 安全清理 | 确认 GitHub 只读令牌已吊销、腾讯云密钥已轮换 | I-05/I-06：P0 |
| 10 | 测试数据 | 是否允许清理 147 家夹具店铺（I-03，先备份再跑） | 影响生产数据干净度 |

> 第 9 项即使不部署也要先做：令牌 / 密钥曾出现在聊天或日志里，属于已泄露状态。

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
| `WECHAT_PAY_ENABLED` | `true` 且填齐 6 个支付凭据 |
| `PAY_DEFAULT_CHANNEL` | `WECHAT_NATIVE`（生产）；联调可保持 `SANDBOX` |
| `DUODUO_ADMIN_IP_WHITELIST_ENABLED` | 建议 `true`，并先配全白名单（为空会拒绝全部后台访问） |
| `STORAGE_TYPE` | `cos`（配齐 5 个 COS 键）或 `local`（明确接受单机盘） |

> 真实密钥只放 `.env`（已在 `.gitignore`），不要写进 compose / 文档 / 聊天。

### 2.2 支付 / 小程序

- 微信支付回调必须是 **公网 HTTPS**：`WECHAT_PAY_NOTIFY_URL=https://mall.example.com/api/payments/wechat/notify`。
- 小程序 `WECHAT_APPID` / `WECHAT_SECRET` 与支付 AppID 可以不同，按实际填写。
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

---

## 5. 上线前验收（必须全绿）

### 5.1 自动化

```bash
# 单测
mvn -o -q test
# 前端构建
cd frontend && npm run build
# 核心冒烟 / UI（按 SELFTEST.md 的顺序）
NO_PROXY="*" node scripts/order-smoke...（按实际脚本名）
NO_PROXY="*" node scripts/e2e/checkout-payment-j03-ui-check.mjs
NO_PROXY="*" node scripts/search-sync-a12-smoke.mjs
NO_PROXY="*" node scripts/staff-permissions-f04-smoke.mjs
NO_PROXY="*" node scripts/e2e/merchant-2fa-f05-ui-check.mjs
```

### 5.2 人工 / 真机

| 项 | 验收 |
|---|---|
| 小程序 | 登录、支付、定位、暗色、录音、富文本、图片灯箱（G-03 / C-03 / J-04） |
| 微信支付 | 真实扫码 / JSAPI 支付 + 回调入账 + 退款（B-01/B-02） |
| 订阅消息 | 下单 / 发货 / 退款通知能收到（C-06/G-05） |
| 备份 | 手动备份 → 恢复到测试库 → 数据一致（I-02） |
| 安全 | 默认口令全部改掉；后台仅白名单 IP 可访问；旧令牌已吊销 |

### 5.3 回滚

- 部署前备份：`scripts/db-backup.ps1` 或 `mysqldump` 全库 + 上传目录。
- 应用回滚：保留上一版镜像 tag；`docker compose up -d` 指定旧 tag。
- 数据回滚：只恢复备份，**不要**手工改资金 / 订单表。

---

## 6. 上线后前 72 小时

- 每 2 小时看：容器健康、5xx、支付回调失败、退款积压、ES 索引文档数。
- 每天：备份校验、审计日志巡检、租户健康度高风险项。
- 出现资金 / 权限异常：先止血（关闭渠道 / 停用账号），再定位，不要直接改库。

---

## 7. 与待办表的对应

| 待办 | 本文档位置 |
|---|---|
| I-01 生产部署 | 第 1~4 节 |
| I-02 备份第二存储 | 第 0 节 #4、第 5.2 节 |
| I-05 / I-06 令牌与密钥 | 第 0 节 #9、第 2.1 节 |
| B-01 / B-02 微信支付 | 第 2.2 节、第 5.2 节 |
| C-06 / G-05 订阅消息 | 第 2.2 节 |
| C-03 / G-03 / J-04 真机 | 第 5.2 节 |
| I-03 夹具店铺清理 | 第 0 节 #10 |
