# 多多宠物商店 · 完整自测手册

> 适用环境：本机 Docker（Windows + PowerShell 5.1），仓库 `D:\智能体项目\duoduo-pet-mall-java`
> 目标读者：需要在自己机器上**从头到尾把全站功能完整验一遍**的人
> 配套：命令速查见 `docs/PLAN.md` §7；架构与不变式见 `docs/agent/03`

---

## 0. 先读这一页（3 分钟上手）

### 0.1 测试分三层，别一上来就跑全量

| 层 | 干什么 | 耗时 | 什么时候跑 |
|---|---|---|---|
| **体检** | 静态自检（两个守卫脚本，不需要容器）+ 编译 + 核心冒烟 3 套 | ~5 分钟 | 每次改完代码 |
| **域级** | 只跑你改的那个域对应的冒烟 + 前端 e2e | ~3 分钟/套 | 提测前 |
| **全量** | 32 套冒烟（2026-10-13 实测 **19 分 00 秒**）+ 契约 + 小程序 + 浏览器 e2e（实测 **10 分 14 秒**）+ 压测 | 约半小时 | 交付前 |

> ★ **跑全量时别中途离开**：这套脚本是长任务，若所在的终端/会话被回收，
> 它会**卡在半途且不报错**（表现是日志停止增长），此时数据库里可能留下夹具。
> 判断依据是**日志还在不在增长**，而不是「屏幕不动了」。

### 0.2 判定一律看**退出码**，不要靠读中文输出

所有脚本都用退出码表达结果（`0` = 通过，非 `0` = 失败）。原因很实在：
PowerShell 5.1 默认按控制台代码页解码输出，脚本与 node 输出的中文一旦编码对不上，
汇总行会变成 `NO-RESULT` —— 明明全过却显示「没结果」。所以：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\order-smoke.ps1
echo $LASTEXITCODE      # 必须是 0
```

### 0.3 一条最重要的纪律：**测试脚本必须自还原**

下单会冻结库存，待付款订单要等 **30 分钟**超时才被补偿调度关掉并回补库存。
只要某个脚本中途失败、或被你手动中断，这些订单就留在库里，后果是：

1. 库存被冻住 —— **下一个脚本**撞「库存不足 422」，报错的地方和出错的地方不是同一个；
2. 三套冒烟（order / payment / settlement）里有「库里不应残留 FROZEN 冻结行」这类
   **全局不变式**断言，残留一多就直接失败，看着像代码回归，其实是数据脏。

> 真实案例：连跑几轮压测后库里攒了 **754 笔待付款订单 + 752 条 FROZEN**，
> 三套冒烟全红。清干净之后三套立刻恢复 **77 / 72 / 125 项全通过** —— 代码一行没改。

**所以每次跑测试前先做 §2 的数据体检，这一步不能省。**

---

## 1. 环境准备

### 1.1 前置软件

| 软件 | 版本 | 本机位置 / 说明 |
|---|---|---|
| Docker Desktop | 任意较新版 | 需能跑 `docker compose` |
| JDK | 21 | `C:\Program Files\Java\latest\jdk-21\jdk21` |
| Maven | 3.9.4 | `D:\devsoft\apache-maven-3.9.4` |
| Node.js | 22.x | 前端与小程序测试用 |
| Python | 3.13 | 压测与契约校验用（纯标准库，无需装包） |

### 1.2 拉起全部容器

```powershell
cd D:\智能体项目\duoduo-pet-mall-java
docker compose up -d
# 需要 ES 检索（商品搜索那套冒烟）时额外起：
docker compose --profile search up -d duoduo-es
```

### 1.3 改了 Java 代码后必须重建镜像

```powershell
$J='D:\智能体项目\duoduo-pet-mall-java'; $M='D:\devsoft\apache-maven-3.9.4'
& 'C:\Program Files\Java\latest\jdk-21\jdk21\bin\java.exe' "-Dclassworlds.conf=$M\bin\m2.conf" `
  "-Dmaven.home=$M" "-Dmaven.multiModuleProjectDirectory=$J" `
  -classpath "$M\boot\plexus-classworlds-2.7.0.jar" `
  org.codehaus.plexus.classworlds.launcher.Launcher -gs maven/settings.xml -B -DskipTests package

docker compose build
docker compose up -d
```

> ⚠️ **不要只看 `package` 的退出码**。退出码 0 也可能打出的是旧 jar。
> 必须按 §4.8 逐模块比对容器内 `/app/app.jar` 与宿主机 jar 的 md5，8/8 一致才算数。

### 1.4 健康检查（四条全过才继续）

```powershell
docker ps                                   # 预期：14 个容器，状态全部 healthy
curl.exe -s http://127.0.0.1:8080/api/categories          # 预期：返回 JSON，code=SUCCESS
curl.exe -s http://127.0.0.1:8080/api/products?pageSize=1 # 预期：有商品数据
curl.exe -s http://127.0.0.1:9200/_cluster/health         # 若起了 ES：返回 JSON
```

服务端口：网关 **8080**、用户 **8081**、商品 **8082**、交易 **8083**、履约 **8084**、营销 **8085**。

### 1.5 前端与小程序

```powershell
cd frontend
npm install          # 首次
npm run build        # = tsc --noEmit && vite build，必须零报错
npm run dev          # http://localhost:5173（必须用 localhost，不能 127.0.0.1 —— CORS 白名单）
```

---

## 2. 数据准备（最容易踩的一节，别跳过）

### 2.1 体检：先看库脏不脏

```bash
docker exec duoduo-mysql mysql -uroot -pduoduo_root -N -B -e "
SELECT '待付款订单', COUNT(*) FROM duoduo_trade.t_order WHERE status='PENDING_PAYMENT';
SELECT 'FROZEN冻结行', COUNT(*) FROM duoduo_trade.t_stock_freeze WHERE status='FROZEN';
SELECT '未完结托管单', COUNT(*) FROM duoduo_trade.t_settlement WHERE status='CUSTODY';"
```

**健康的库**：待付款 ≤ 少量（几笔）、FROZEN = **0**、托管单数与你的预期相符。

如果待付款有几十上百笔，先看是谁的：

```bash
docker exec duoduo-mysql mysql -uroot -pduoduo_root -N -B -e "
SELECT o.user_id, u.phone, COUNT(*) FROM duoduo_trade.t_order o
  JOIN duoduo_user.t_user u ON u.id = o.user_id
 WHERE o.status='PENDING_PAYMENT' GROUP BY o.user_id ORDER BY 3 DESC;"
```

### 2.2 清场：把遗留的待付款订单取消掉

```bash
python scripts/perf/cleanup-leftovers.py --phones 13948183318,13911151185
```

脚本只做「登录 → 列待付款 → 逐笔取消」，全程走**业务接口**不直接改库，
所以库存回补、冻结台账释放、状态日志都由服务端按正常逻辑完成，不会留下半截状态。

清完再跑一次 §2.1 的体检确认 FROZEN 归零。

> 不想手动一个个找手机号？也可以什么都不做，等 **30 分钟**补偿调度自动关单
> （调度每 60 秒扫一批，批量 200 单）。想立刻生效就手动触发一轮：
> `curl.exe -X POST http://127.0.0.1:8083/api/orders/internal/sweep -H "X-Internal-Token: duoduo-local-dev-internal-token"`
> —— 但注意它只关「已过 expire_at」的订单，刚建的那些照样要等到期。

### 2.3 兜底：从零重建

数据实在清不干净、或者你想从完全干净的状态开始：

```powershell
docker compose down -v      # ⚠️ 会删数据卷，所有数据重新初始化
docker compose up -d
```

初始化脚本在 `docs/sql/`，容器首次启动自动执行。

### 2.4 测试账号约定

| 角色 | 账号 | 说明 |
|---|---|---|
| 通用测试口令 | `duoduo12345`（部分老脚本用 `duoduo123`） | 冒烟脚本可 `--Password` 覆盖 |
| 官方客服 | 手机 `13900000002`，id `710000000000000001` | 种子数据自带 |
| 压测账号 | 默认 `13945673533` | 登录失败会自动换随机号注册 |
| 运营 / 卖家账号 | **脚本自助注册 + 库内提权**，跑完降回 USER | 绝不去改既有账号的角色 —— 那是最难排查的一类脏数据 |

需要手工造一个运营账号时（进管理后台用）：

```bash
# 1) 注册
curl.exe -X POST http://127.0.0.1:8080/api/auth/register -H "Content-Type: application/json" ^
  -d "{\"phone\":\"13912345678\",\"password\":\"duoduo12345\",\"nickname\":\"手工运营\"}"
# 2) 库内提权（运营角色没有自助申请入口，也不该有）
docker exec duoduo-mysql mysql -uduoduo -pduoduo -D duoduo_user -e "UPDATE t_user SET role='OPERATOR' WHERE phone='13912345678';"
# 3) 重新登录（角色是在登录时写进令牌的，不重登仍是 USER）
```

---

## 3. 功能点清单（功能 → 用哪套脚本验 → 多少项）

### 3.1 接口冒烟（一键运行器收录 33 套 = 32 套 PowerShell + 1 套 Node；本轮实测 32 套全过 + 1 套按设计跳过）

| # | 功能域 | 脚本 | 项数 |
|---|---|---|---|
| 1 | 全链路基础（网关 + 用户域 + 商品域） | `smoke.ps1` | 62 |
| 2 | 购物车 | `trade-smoke.ps1` | 87 |
| 3 | 下单 / 拆单 / 库存冻结 | `order-smoke.ps1` | 77 |
| 4 | 支付 / 订单状态机 / 超时关单 | `payment-smoke.ps1` | 72 |
| 5 | 担保交易 / 资金托管 / 放款 / 退款 / 自动收货（含 E22 佣金与 T+7） | `settlement-smoke.ps1` | 149 |
| 6 | 沙箱支付渠道 / 预下单 / 回调验签 | `pay-channel-smoke.ps1` | 62 |
| 7 | 卖家中心（入驻 / 归属隔离 / 发货 / 对账） | `seller-smoke.ps1` | 107 |
| 8 | 平台管理后台（权限 / 报表 / 审核 / 强关 / 运营账号） | `admin-smoke.ps1` | 75 |
| 9 | 后台 2FA + IP 白名单（E21） | `admin-2fa-smoke.ps1` | 56 |
| 10 | 评价与晒单 | `review-smoke.ps1` | 62 |
| 11 | 历史订单托管单回补 | `backfill-smoke.ps1` | 92 |
| 12 | 售后仲裁工单 | `aftersale-smoke.ps1` | 101 |
| 13 | 图片上传 | `upload-smoke.ps1` | 8 |
| 14 | 物流轨迹 | `logistics-smoke.ps1` | 79 |
| 15 | 信用分 | `credit-smoke.ps1` | 82 |
| 16 | 优惠券 | `coupon-smoke.ps1` | 145 |
| 17 | 收货地址簿 | `address-smoke.ps1` | 82 |
| 18 | 跨域 ID 精度（只断言 JSON 原始文本） | `id-precision-smoke.ps1` | 56 |
| 19 | 微信登录绑定 | `wechat-auth-smoke.ps1` | 26 |
| 20 | 微信绑定 | `wechat-bind-smoke.ps1` | 24 |
| 21 | AI 导购 | `ai-smoke.ps1` | 39 |
| 22 | ES 商品检索（**需 ES 在跑**，否则按设计跳过） | `search-smoke.ps1` | 40 |
| 23 | 限时秒杀 | `seckill-smoke.ps1` | 51 |
| 24 | 消息队列 / 死信 / 重放 | `mq-smoke.ps1` | 48 |
| 25 | 后台操作审计（E18） | `audit-log-smoke.ps1` | 58 |
| 26 | 审核 SLA 与事后抽查（E28） | `audit-sla-smoke.ps1` | 62 |
| 27 | 权限版本即时生效（E17） | `perm-version-smoke.ps1` | 34 |
| 28 | 越权回归矩阵（E19，含 **§7b 钱的口径（S5）**） | `privilege-matrix-smoke.ps1` | 115 |
| 29 | 租户隔离硬化（E20） | `tenant-isolation-smoke.ps1` | 37 |
| 30 | 代运营工单（E23） | `proxy-ops-smoke.ps1` | 121 |
| 31 | **钱与库存每日自动对账（S3）** | `recon-smoke.ps1` | 53 |
| 32 | 三端入口收窄（Node 冒烟，**不进上面的一键运行器**） | `e8-entry-scope-smoke.mjs` | 以退出码为准 |
| 33 | Python 探针（同样不进一键运行器） | `r58-restock-idempotent-smoke.py` / `r58-compensate-smoke.py` / `r58-listing-edit-delete-smoke.py` | 10 / 22 / 33 |
| 34 | **租户健康度面板（E24）** | `tenant-health-smoke.ps1` | 90 |
| 35 | **多卖家拆单幂等 / 支付入账**（Python 探针，不进一键运行器） | `split-order-idem-smoke.py` | 18 |
| 36 | **冻结台账 ↔ 商品域对账守卫**（Node，直接读库判两个来源） | `check-stock-freeze-recon.mjs` | 6 |

> ★ **本表的项数已在 2026-10-13 全套重跑后刷新。** 此后若有脚本变动，**要么重跑全套把总数实算一遍再改，
> 要么就留着不改** —— 绝不允许**只把总数手改 +N**（那又是在写一个没人核的数字）。

> 项数会随迭代增长，**以脚本自己打印的「通过 N 项」为准**，本表只用来定位该跑哪一套。
> **最近一次实测（2026-10-13，`EXIT=0`，耗时 19 分 00 秒；原始日志是本地产物
> `logs/s5-fix-final-smoke.txt`，注意 `logs/` 已在 `.gitignore` 里、不进仓库；跑
> `scripts\run-all-smoke.ps1` 即可逐套复现）**：
> 运行器汇总 **共 32 套 / 通过 32 套 / 失败 0 套**，即 **32 套全部真跑了**
> （**31 套 PowerShell 合计 2212 项** + **1 套 Node `e8-entry-scope-smoke.mjs` 以退出码为准**）。
> ★ 这是**首次拿到 32/32 全绿**：同一天更早的一轮是 31/32，唯一红的 `smoke.ps1` 是**被越权矩阵的夹具残留
> 挤红的**（见 README §十二「五、夹具残留的两轮拉锯」），本轮已修到根因。
> ★ 运行器的「共 N 套」口径 = **实际执行**（读 `run-all-smoke.ps1:130` 的 `$results.Count` 确认过），
> **按设计跳过的那套不在里面**，另行打印「已跳过 N 套」并逐条点名：
> 本次为 `search-smoke.ps1`（ES 未运行）。**所有计数都不含它。**
>
> ★ **本条曾写「合计 2086 项」，已弃用。** 两个错都要记：
> ① 那个数字**当时是手填的**，不是算出来的 —— 手填的数字没有任何东西去核它，写错也不会有检查变红；
> ② 我后来重算时又用 `awk` 按套件名求和，正则写成 `^[a-z0-9-]+-smoke\.ps1`，
> **漏掉了没有连字符前缀的 `smoke.ps1`**，于是得数少了 62 —— **又是「静默少算一套」**，
> 而且这次是我自己的统计脚本犯的（跟 `check-all.ps1` 头注里那个 `*-smoke.ps1` 通配符坑是**同一个**：
> 要求名字里带连字符，就会把最基础的那套漏掉）。
> 现在一律**先取运行器原文，再逐套列举求和，并交叉核对两个来源是否相等**；
> 生成验收包的那支脚本（`.workbuddy/build-acceptance-doc.py`）也只是搬运与求和，**不再手填任何数字**。

### 3.2 前端 / 小程序 / 性能

| 类别 | 入口 | 覆盖 |
|---|---|---|
| 小程序静态自检 | `node scripts/check-miniprogram.js` | 8 条规则，0 错 0 警 |
| 小程序前端契约单测 | `scripts\*-test.js`（7 个） | id 精度 19 / 闲置筛选 42 / AI 澄清 23 / 分享圈 74 / 角标 59 / 通知入口 23 / 时间换算 12 |
| 小程序无浏览器 | `node --test scripts/mp-harness/*.test.js`、`node scripts/mp-harness/behavior.test.js` | 页面挂载 33 + AI 页 4 + 页面行为 31 + 内部凭据对拍 4 = **72 项** |
| 前端真实浏览器 | `scripts\e2e\run-ui-checks.ps1` | **22 套**（首页 / **商品详情页** / 地址簿 / 商家端 / **商家端看板** / 评价 / 售后 / 物流 / 举报台 / 优惠券 / **秒杀** / **后台入口** / **顶栏布局** / **后台图表** / **我的闲置** / **分享圈** / **通知·私信·关注** / 审核 SLA / 商品审核 / 资质有效期 / 商家店员 / 审核台）。单套调试：`-Only <名字>` |
| ⚠️ e2e 假红的头号原因是**数据脏** | — | e2e 断言「商品图零裂图」。若历史冒烟**中途被中断**，会残留一条带 `https://example.com/…` 占位图的夹具评价；它挂在种子商品上 → **首页与详情页同时报裂图**，看起来像代码回归。判据：`SELECT COUNT(*) FROM t_review WHERE images LIKE '%example.com%';`（应为 0）。`review-smoke.ps1` 已加**开机清扫**自动处理 |
| 网页端容器（S1） | `bash scripts/web-up.sh`；验收用 `UI_BASE=http://localhost:8088` 跑 3 套 e2e | 1 个容器（8088）；home 9 + header-layout 88 + notice-chat-follow 50 全绿（同源反代、SPA 兜底、静态缓存、上传体积均已断言） |
| 接口契约 | `python scripts/extract-api-contract.py --check` | 端点总数由脚本写在契约文档末尾（`合计 N 个端点`）——**这里不抄数字**（抄来的必然落后，且没有检查能发现） |
| 性能压测 | `python scripts/perf/bench.py --check` | 9 个核心接口 × 并发梯度 |
| 后端单测 | `mvn -o test` | **715 项 / 69 个测试类**（11 个模块；2026-10-12 实测 0 失败） |

---

## 4. 自动化测试：逐步执行与预期结果

### 4.1 静态自检与编译

```powershell
node scripts/check-miniprogram.js        # 预期：0 errors, 0 warnings
cd frontend; npm run build               # 预期：tsc 与 vite 均无报错，退出码 0
```

`check-miniprogram.js` 的 8 条规则里最值钱的两条：
雪花 id **禁止** `Number()` / `parseInt()`（18~19 位超出 JS 53 位精度）；
按钮文字禁止折行。这两条在浏览器里表现为「消息发不出去」「按钮文字换行」，很难联想到代码。

### 4.2 后端单元测试

```powershell
# 用 java 驱动 Maven launcher（本机 mvn.cmd 在沙箱里不可用）
& 'C:\Program Files\Java\latest\jdk-21\jdk21\bin\java.exe' "-Dclassworlds.conf=$M\bin\m2.conf" ... -B test
```

**预期**：`BUILD SUCCESS`，**715 项测试 0 失败**（69 个测试类；2026-10-12 实测）。

### 4.3 接口冒烟

```powershell
# 一键全量（含 ES 前置检查：ES 未运行时**显式跳过** search-smoke 并列入汇总，不静默略过）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\run-all-smoke.ps1

# 只跑某一套（文件名模糊匹配）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\run-all-smoke.ps1 -Only order
```

**预期**：结尾打印「共 N 套，通过 N 套，失败 0 套 / 全部通过」，退出码 0。
日志逐套落在 `logs/smoke/*.log`（UTF-16 编码，用 PowerShell 读或先转码再看）。

### 4.4 接口契约校验

```bash
python scripts/extract-api-contract.py --check
```

**预期**：`PASS`，且**端点总数与源码一致**（总数由脚本写在契约文档末尾的 `合计 N 个端点`；本文档刻意不复述那个数字 —— 契约文档自身有 `--check` 门禁，而手抄在别处的数字没有任何检查会去核对它）。改了端点/字段必须重跑，它会拦住「文档与代码分家」。

### 4.5 小程序「无浏览器」自动化

不需要浏览器，只需网关在跑：

```bash
node --test scripts/mp-harness/pages-smoke.test.js     # 30 个页面逐个挂载（33 项）
node --test scripts/mp-harness/ai-page.test.js         # AI 页（4 项）
node --test scripts/lib/internal-token-parity.test.js  # 内部凭据 HMAC JS/PowerShell 对拍（4 项）

# 注意：behavior.test.js 是**自带汇总与退出码**的独立运行器（31 项），
# 直接跑它，不要套 `node --test`（套上去会被当成「一个文件 = 一项」，项数显示为 1）。
node scripts/mp-harness/behavior.test.js               # 页面行为（31 项）
```

**预期**：全部 pass，0 fail（合计 72 项）。
这套 harness 直接 `require` 仓库里**真实的页面 js**（不是复制一份），
所以页面改坏了这里立刻红 —— 这是它比「截图比对」值钱的地方。

### 4.6 前端真实浏览器 e2e

```powershell
# 一键：起 dev server → 跑 22 套 → 建/拆券夹具 → 收尾
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\e2e\run-ui-checks.ps1
powershell ... -File scripts\e2e\run-ui-checks.ps1 -Only home     # 只跑自包含那套
```

**预期**：**22 套全 PASS**（2026-10-11 实测 972 项断言，退出码 0）。判定同样看退出码。

> ★ **跑 e2e 不再弄脏工作区（S8 起）**：20 套会落盘截图的套件默认写到
> `scripts/e2e/.shots/`（已 gitignore），**不再写进受版本控制的 `frontend/docs/screenshots/`**。
> 验证方式：跑完任意一套后 `git status --porcelain frontend/docs/screenshots/` 应为空。
> 要真的更新文档配图时才显式 `$env:SHOTS='frontend/docs/screenshots'`。细则见 §4.10d。

> ★ **e2e 假红的第一大原因是数据脏，不是代码**：两套页面同时报错时先查库里有没有残留夹具
> （见 §6 第 23 条：`SELECT COUNT(*) FROM t_review WHERE images LIKE '%example.com%';` 应为 0）。
>
> ★★ **e2e 假红的第二大原因是「与冒烟并行跑」**（2026-10-12 实测，11 条 FAIL）：
> `scripts/mq-smoke.ps1` 是唯一会 `docker restart` 业务容器的冒烟脚本（重启 `duoduo-app-product` /
> `duoduo-app-trade` 让它们重连 RocketMQ broker）。若此刻 e2e 正在打这些服务，网关会返 **503**，
> 于是「无 5xx / 无非预期 4xx」类断言**集体假红**。
>
> **识别特征**：**多个不同服务同时 5xx**（`/api/home` + `/api/categories` + `merchant.localhost:8091/api/seller/*`），
> 且 `docker ps` 里某容器显示 `Up About a minute`（其余是 `Up N hours`）。
> **处置**：`docker inspect --format '{{.State.StartedAt}}' <容器>` 对一下时间戳；确认是重启窗口就**串行重跑**，
> 不要当产品缺陷去改代码。**纪律：`run-all-smoke.ps1` 与 `run-ui-checks.ps1` 必须串行。**

其中 9 套是**自包含**的（不需要外部夹具，可单独跑）：
`home`（9）、`product-detail`（20）、`admin-entry`（74）、`header-layout`（80）、`seller-dashboard`（31）、
`admin-charts`（36）、`my-listings`（47）、`seckill`（45）、`address`（64）；
其余 7 套的前提各自写在脚本头注释里（优惠券那套由一键运行器自动建/拆夹具）。

**商品详情页套件**（`scripts/e2e/product-detail-ui-check.mjs`，20 项，第七十三轮）单独跑：

```powershell
cd frontend && node node_modules/vite/bin/vite.js --port 5173 --strictPort --host localhost   # 另开终端
cd .. && node scripts/e2e/product-detail-ui-check.mjs
```

覆盖：身份标签（多多自营/品牌入驻，与小程序同规则 supplyType）、「商品介绍」图片、
主图点击放大（弹层 / Esc / 关闭按钮）、缩略图切换、「包装规格」行、
**评分真实数据驱动**（页面评分区与评价域聚合接口逐字一致，种子假数据无法回归）、裂图与 console 健康。

**后台入口套件**（`scripts/e2e/admin-entry-ui-check.mjs`，**74 项**）单独跑：

```powershell
cd frontend && node node_modules/vite/bin/vite.js --port 5173 --strictPort --host localhost   # 另开终端
cd .. && node scripts/e2e/admin-entry-ui-check.mjs
```

它自己建运营与买家夹具（随机手机号 + 提权走库），不需要任何前置数据。
关键断言是 S3b：**只带 `dd_rt` 的全新浏览器上下文**（模拟关掉浏览器再打开）——
用来证明「入口关掉页面后还能再回来」。

**S5 段（2026-10-12 新增，账号管理「改角色」）**：现场建**两个 ADMIN + 一个普通用户**夹具，
覆盖四件事（明细见 §8 末尾"已经闭合的项"）：

1. **UI 反向**：OPERATOR 能看列表，但行内没有「改角色」按钮、显示「仅 ADMIN」，页顶有说明；
2. **UI 正向**：ADMIN 点「改角色」改成功，且页面展示的是**服务端 notice 原文**
   （含「下一个请求即生效」与「无需重新登录」）——回表核对 role、并核对审计行
   `actor|USER_ROLE_CHANGE|USER -> SELLER|target`；
3. **接口层**：OPERATOR 403（文案点明仅限超管）/ OPERATOR + 非法角色**仍是 403**
   （先判权后校验参数）/ ADMIN + 非法角色 422 / 不存在用户 404 / 幂等 200 且**不新增审计** /
   自己降权 422 且文案是「不能给自己降权」；
4. **并发不变式**：两名 ADMIN **同时**降对方 → 恰好一条成功、库里 `role='ADMIN'` 仍 ≥ 1、
   失败方带回「至少保留一名超级管理员」。
   ★ 这一条是为了钉住一个**实测缺陷**：原先用 `selectCount` 统计 ADMIN（普通 SELECT，
   在 InnoDB REPEATABLE READ 下是一致性快照读、不加锁）→ 两条都读到 2 → 都放行 →
   **平台一个管理员都不剩**。修法是改成 `SELECT id … FOR UPDATE` 加锁读。
   反向证据：修复前该段 4 条红（`status=200/200`、`ADMIN=0`），修复后 74/0。

### 4.7 性能压测

```bash
python scripts/perf/bench.py --concurrency 10,25,50,100 --duration 10   # 跑全梯度并落盘
python scripts/perf/bench.py --check                                    # 只判达标，退出码 0/1
```

基线：读接口 avg≤300 / P95≤600 / P99≤1000ms；写接口 avg≤800 / P95≤1500 / P99≤3000ms；错误率 <0.1%。
**预期**：「基线判定：全部达标」，退出码 0。

结果落 `logs/perf/r60-bench.json`。脚本自带三重自还原：开跑前清历史遗留、
每档跑完取消本档订单、收尾再全库扫一遍兜底。跑完请顺手做一次 §2.1 体检确认 FROZEN 归零。

### 4.8 交付物核对（改了 Java 必做）

```bash
export MSYS_NO_PATHCONV=1      # 不加这行，Git Bash 会把 /app/app.jar 转成 Windows 路径
for m in gateway user product trade fulfilment marketing social ai; do
  echo -n "$m 宿主机: "; md5sum duoduo-$m/target/*.jar | awk '{print $1}'
  echo -n "$m 容器内: "; docker exec duoduo-app-$m md5sum /app/app.jar | awk '{print $1}'
done
```

**预期**：8 个模块 md5 **全部一致**。

### 4.9 运维脚本自检（E25；改开关 / 动备份相关代码后必做）

四条命令，**全部看退出码**（`0` = 通过）。它们在 `scripts\check-all.ps1` 的 [0/5] 段里也会自动跑。

```powershell
# ① 运维手册一致性守卫（纯静态，不需要 Docker）：文档引用的每个 scripts 路径必须存在；
#    docs/OPS.md 的开关表与 scripts\ops-switch.ps1 的清单**双向一致**；
#    且 scripts\db-backup.ps1 / ops-restore.ps1 里**承诺过的判据都还在**（见下方 [3/3]）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\lib\check-ops-doc.ps1
#    预期：exit 0，输出「清单 15 项；文档表 15 项」+ 两向一致（15 条 OK）
#          + 「备份/恢复判据 11/11 条在位」
#    ★ [3/3] 那 11 条是**防判据被悄悄删掉**：少一半判据的症状正好是"看起来还在跑、结论全绿"
#      反向验证：`-BackupScript <改坏的副本>` 把 5 条改坏 ⇒ 「6/11 条在位」+ 5 条 FAIL + exit 1

# ② 运行期开关四层校验（清单 → compose 透传 → 服务 yml → 容器实际）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ops-switch.ps1 -Check
#    预期：exit 0，输出「静态校验：通过 35 条，失败 0 条」
#    ★ 35 = 33（15 项 × 2 条 + 自证 3 条）+ 2（container_name 自证，2026-10-12 新增）
#    ★ 标记数为 0 / container_name 解析到 0 个 都会报红 —— 那是解析失效，不是"没有开关"

# ③ 一屏全栈状态（需要 Docker）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ops-status.ps1
#    预期：合计「OK 26 ／ 需留意 1 ／ 失败 0」，exit 0（需留意项 = 备份根未配置时的 WARN）
#    ★ 冷启动后第一次跑可能出现「网关 → 商品域（列表）不可达：操作超时」——
#      先手动 curl 预热两次再跑（详见铁律 12）。实测 2026-10-12：冷机首跑 1 次 FAIL，
#      预热后复跑即 OK 26/1/0

# ④ 备份恢复演练（需要 Docker + 至少一份含 rowCounts 与 volumes 的备份）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ops-restore.ps1
#    预期：产物哈希 6/6 一致；六库逐表一致（58 张表 / 约 2 万行，**以实测为准**）、差异 0；
#          上传文件卷 2 个 / 129 个文件逐文件 sha256 一致（清单指纹与备份侧同一个：
#          `fulfilment-uploads` 757b80894195 / `merchant-materials` 09730e5c623b）；
#          两个卷各打一条「容器内临时目录已清（回读 ls -d = 无此目录）」；exit 0
#    结尾必须是「演练结论：全绿 —— 这份备份可以恢复，且库内容与备份那一刻逐表一致、上传文件逐文件一致。」
```

> **2026-10-13 起备份范围 = 六库 + 两个上传文件卷**（`fulfilment-uploads` 商品/评价图片、
> `merchant-materials` E12 入驻资质材料）。判据同形：**两个来源**（容器内 `sha256sum` vs 宿主机 `Get-FileHash`）
> + **逐文件清单指纹**；卷的真实名带 compose 项目前缀 ⇒ **按后缀反查**而不是拼字符串
> （拼错的表现是「复制出一个空目录然后宣布成功」）。

**判据自证纪律（这几条是踩过坑加的，改脚本时别去掉）**：

| 情形 | 正确处置 | 为什么 |
|---|---|---|
| Docker 引擎取不到 / MySQL 容器没跑 | **报「这是环境问题，不是备份问题」并退出** | 否则后面每一步都失败，结论会显示「产物不全 / 导入失败」，看着像**备份损坏**（实测踩中：Docker Desktop 中途退出） |
| manifest 缺 `rowCounts` | **拒绝出结论** | 那是恢复演练的唯一强判据；降级成「命令没报错 = 成功」等于装作验过 |
| manifest `status != OK` | **拒绝用它做演练** | 一份自己都认为失败的备份，不该被用来「证明恢复没问题」 |
| 比对了 **0 张表** | **判失败** | 「空结果」与「链路没接通」长得一样，这是本项目反复栽的一类 |
| manifest 声明了 sha256 却一份都没验成 | **判失败（判据层静默失效）** | 实测踩中：`ConvertFrom-Json` 得到的是 `PSCustomObject`，对它调 `.ContainsKey()` 抛 `MethodNotFound` 且被 `Continue` 吃掉 → 哈希这一层**静默没验**，只留一行 WARN |
| 恢复库 vs dump 前源表做整表校验和比对 | **不要做** | 心跳表 `t_sched_leader`（每 2 分钟自改）必然不等 → 备份完全正确却每次演练都假红（实测 2 处） |
| 第 ④ 层「容器里真生效」的**容器名** | **从 compose 读 `container_name`，不猜名**；解析不到 ⇒ 判失败 | 原实现用公式猜 `duoduo-app-<x>`，只对 `duoduo-xxx-app` 成立（`duoduo-mq-broker` / `duoduo-es` 全猜错）。猜错时 `Get-ContainerEnv` 返回 `$null` → 退回「`.env`（容器未读到，可能未重建）」，**若值恰等于默认值则人眼无法分辨**（2026-10-12 修） |
| 用 `docker exec` 的**退出码**判断"容器里有没有这个变量" | **不要用**。改用「输出是否含 `No such container`」判断 | 实测：容器不存在时 `docker exec` 打 stderr 但 `$LASTEXITCODE` **仍为 0** —— 退出码这道防线是空的 |
| 心跳表 `t_sched_leader` 用 `CHECKSUM` 判 | **不要做**，改用「声明持续改写表 + 行数/主键集合」，日志打 `[PASS~]` | **实测约每 15 秒自改一次** `expire_at/updated_at`，而备份窗口有 30 秒 ⇒ 产物/before/after **注定**互不相同。原三方比对会**每次报红**（狼来了），报到最后真正的坏备份被淹没。名单解析出 0 张 ⇒ 判红（防名单静默失效）。**宁假红勿假绿** |
| append-only 表在备份窗口内被插入时，拿 `before` 行数卡产物 | **不要卡**，只按「源库 ↔ 产物」两来源判，并打 `[PASS*]` 点名 | 否则"备份期间刚好来了一条"会变成假红 —— 与心跳表同一族的狼来了 |
| `docker cp` 落进容器的文件用业务用户 `rm -rf` | **不行**：那批文件属主是 **root**，`docker exec` 默认以业务用户跑 ⇒ 删不掉，且 `rm` 只在 stderr 报 `Permission denied`、输出被 `Out-Null` 吞掉 ⇒「删失败」与「删成功」无从分辨 | 实测踩中：演练每轮都在容器 `/tmp` 留一份 root 属主的副本，是**回读自证**把它揪出来的（那句 `ls -d` 千万别删）。修法：该段 `docker exec` 统一加 `-u 0`（临时目录是我们自己建的、只放我们自己的副本）。★ 清理成功也要打一条 `[OK] … 容器内临时目录已清` —— **静默的成功与"这段根本没跑"长得一模一样** |
| 空字符串传给 `[Parameter(Mandatory)][string]` 参数 | **加 `[AllowEmptyString()]`** | 实测踩中：反向验证把持续改写表清空后，指纹函数收到空串直接抛「无法将参数绑定到参数 Text」——而且是在判据算完、日志还没打那一刻炸 ⇒ 整轮备份没了结论 |
| manifest 缺 `volumes` 段 / **一个上传卷都没验成** | **判失败，不许降级成警告** | 只备了库、没备文件 = 恢复出来「订单在、图没了」；把「没备」当成「验过了」是最坏的一种绿 |
| 抽验「全部恢复完再一起比」（所有抽样表恢复进同一个临时库） | **不行**，必须**恢复一张立刻比一张** | dump 每段都以 `DROP TABLE IF EXISTS` 开头 ⇒ **跨库同名的表互相覆盖**（本项目 `t_sched_leader` 5 库各一张、`t_audit_sample` / `t_audit_sample_rate` / `t_proxy_work_order` 各 2 库）⇒ 先恢复的那张「拿别人的数据跟自己比」：实测**假红**（`duoduo_product.t_sched_leader` 报 `行数 源2/产物1`，读到的其实是 marketing 那张）与**假绿**（两张 `t_audit_sample_rate` 内容恰好相同、校验和都是 `2851413293`，读错表也照样 PASS）都出现过。修法：抽验主逻辑抽成 `Invoke-SampleCompare`，恢复成功即比对 |


### 4.10a 文档「飞书吞行/吞字」自检（改 README / docs/*.md 后必做）

```powershell
# 仓库内全部 .md 的两条「只在飞书现形」的书写陷阱（纯静态，不需要 Docker）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\lib\check-md-bare-tick.ps1
#    预期：exit 0，输出两行 [OK]
#      形态一（列表续行以裸反引号开头）：0 处 —— 飞书导入不会因此吞行
#      形态二（表格单元格行内代码含未转义竖线）：0 处 —— 飞书导入不会因此吞字
#
#  ── 形态一：列表续行以行首裸反引号开头 ──
#    判据：行首（允许空格缩进）连续 >= 2 个反引号 且 该行是列表项续行；
#    飞书表现：**整行**被吞（GitHub / 腾讯文档都正常）。
#  ★ 为什么不能用「整行反引号总数为奇数」当判据：
#    那一行 = 两个反引号 + 空格 + 两个反引号 + 表达式 + 两个反引号 + 空格 + 两个反引号，
#    一共 10 个反引号，是**偶数**、自我配对后可全消，
#    但它在**导入飞书时确实会整行吞掉**（2026-10-12 实测丢过一行）。
#  ★ 为什么不能用「续行以行内代码开头即违规」：全仓命中 85 处，全是正常写法
#    （列表项后的说明行用行内代码引用标识符）—— 那种守卫会变成噪音，最后没人看。
#
#  ── 形态二：表格单元格里行内代码含未转义竖线 ──
#    判据：表格行（去空白后以 | 开头）+ **处于成对反引号之间**的**未转义** | ；
#    飞书表现：该单元格**从竖线处被截断**（其余渲染器都正常）。
#    正确写法：把竖线转义成 \|，即 `a|b` → `a\|b`。
#  ★ 实现上**不能先按竖线切单元格再判**：切分本身就把那个裸竖线当分隔符吃掉了，
#    切开后每格都"干净"、判据恒假（第一版就这么漏判过，靠反向验证才发现）。
#  ★ 该形态全仓本就全部正确转义（命中数恒为 0），属于「新写的内容容易踩」，
#    不是「存量有噪音」，因此可以放心判。

# 反向验证（证明守卫真的在判；注入一行违规 -> 必须 exit 1 并点名该行）
#   形态一：注入「列表续行以 `` 开头」的那一行 -> [FAIL] 形态一 … + README.md:<行号>
#   形态二：注入 `| x | 含 `node_modules|target|dist` 的一行 |` -> [FAIL] 形态二 … + README.md:<行号>
#   两种都验过一次（2026-10-12）：形态一精确报 README.md:1959、形态二精确报 README.md:11。
```

### 4.10b 图表主题接入点契约自检（S10；改 `chartTheme.ts` / `charts.tsx` 后必做）

```powershell
# currentChartThemeMode() 是「全站图表主题的唯一接入点」——四条约定一起守（纯静态 + 真 import 该模块）
node scripts\lib\check-chart-theme-contract.mjs
#    预期：exit 0，末尾「结果：PASS=13 FAIL=0」
#    四条约定（任何一条失守，「将来做全局深色主题只改这一处」就变成空话）：
#      ① 函数真的读 document.documentElement.dataset.theme（不是写死 light 的死代码）；
#      ② 回落正确：只有精确的 dark 才返回 dark；未设置 / 空串 / 其他值 / 无 document 一律 light；
#      ③ charts.tsx 在**组件内**求值 —— 出现模块级 `const T = chartTheme();` 会报红，
#         因为模块级常量只在首次加载算一次，主题切了图表不会跟着变；
#      ④ 代码里不出现 prefers-color-scheme（会造成浅底 + 深轴线的不可读组合）。
#    ★ 判据分两层：动态（替换 globalThis.document 后真调用，验 7 种取值）+ 静态（读源码文本验 ①②③④）。
#    ★ 源文件扫到 0 行直接判违规（「什么都没扫到」与「扫了、结果干净」长得一样）。

# 反向验证（证明守卫真的在判，注入违规 -> 必须 exit 1 并点名）
#   注入①  把函数体改回 `return 'light';`                      -> [FAIL] ★ data-theme="dark" → dark
#   注入②  charts.tsx 里加模块级 `const T = chartTheme();`      -> [FAIL] ③ 没有模块级 const T …
#   注入③  代码里加 matchMedia('(prefers-color-scheme: dark)') -> [FAIL] ④ 代码中不出现 prefers-color-scheme
#   三种都验过一次（2026-10-12）：各 exit 1 且精确点名；还原后复跑 13/13 PASS。
```

配套 e2e：`node scripts\e2e\admin-charts-ui-check.mjs` 第 ④ 段（该套现在 **36 项**）——
其中「★ dark 与 light 的 canvas 像素指纹不同」是「dark 一套配色不是死代码」的端到端证据。
判据用 **canvas 像素抽样指纹**而非截图文件哈希：同一像素重新编码成 PNG 的字节也可能不同（压缩非确定性），
拿文件哈希会假红。

### 4.10c 网页端全站未读角标自检（S7；改 `store/app.tsx` / 商城 `Layout.tsx` 后必做）

```powershell
# 走一键运行器（★ 不要直接 node xxx-ui-check.mjs —— 那会打默认的 :5173 dev server 而报 ERR_CONNECTION_REFUSED）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\e2e\run-ui-checks.ps1 `
  -Only notice-chat-follow -UiBase http://localhost:8088
#    预期：通过 50 项，失败 0 项；末行「notice-chat-follow : PASS」
#    -UiBase 会把 UI_BASE 交给子进程，且不再启动/管理 dev server（容器已在跑）
```

**这一节守的三件事**：

| 判据 | 静默失效会长什么样 |
|---|---|
| **角标出现在首页顶栏**（而不只在通知页） | 只在通知页刷新的老毛病会复发 —— 用户在首页永远看不到数字，但通知页一切正常 |
| **角标数字 == `unread-summary.total`** | 前端自己 `notice + message` 两处相加，服务端改了合并口径（如排除 `bizType=MESSAGE`）就悄悄不一致 |
| **未登录访客无角标** | 未登录时请求 401 → 全局静默重登/跳登录，或被当成 0 个未读而把访客也显示成已登录态 |

新增的一段（④）关键行：

```
[PASS] ★ ④ 首页顶栏就出现未读角标（不只在通知页 —— 这是 S7 的核心承诺）
[PASS] ★ ④ 角标数字 == unread-summary 的 total（两端同口径，没有各算一套）
[PASS] ④ 未登录访客没有头像菜单、也就没有未读角标
```

**实现口径（与小程序对齐，改动前先读这三条）**：

1. **唯一数据源**是 `GET /api/socials/notices/unread-summary`（服务端已合并 `notice + message`）。Web 端**不自己相加**。
2. **刷新点三处**：打开头像菜单 / `visibilitychange` 回前台 / **60 秒兜底轮询**（与小程序同频）。请求**单飞**（并发复用同一个 in-flight Promise）。
3. **失败不清零** —— 网络抖一下就把未读抹掉，比数字停在旧值更让人困惑。未登录则清角标。
4. ★ **轮询的启停由消费方驱动**：`AppProvider` 在 `AuthProvider` **外层**（见 `main.tsx` 嵌套顺序），拿不到 `useAuth` —— 所以 Provider 只提供状态与实现，由商城端 `Layout.tsx` 按 `loggedIn` 决定启停（后台/商家端不需要买家未读角标）。

### 4.10d e2e 截图输出契约自检（S8；新增 e2e 套件 / 改截图落点后必做）

```powershell
# 纯静态（只读 scripts/e2e/*-ui-check.mjs + scripts/lib/e2e-shots.mjs + .gitignore），不需要 Docker
node scripts\lib\check-e2e-shots.mjs
#    预期：exit 0，输出
#      「被检查的 e2e 套件数：22（其中会落盘截图的：20）」
#      「[OK]   五项全通过 —— 跑回归不会再把仓库弄脏」
```

**守的是什么**：22 套 e2e 里有 20 套会落盘截图，过去全部写到**受版本控制**的
`frontend/docs/screenshots/`（其中 9 套还写死 `D:/…` 绝对路径）。于是**每跑一次回归，工作区就多出
上百个 modified / untracked 的 PNG**，且内容随真实数据与时间戳变化 —— `git status` 里再也分不清
「我改了代码」和「我刚跑过回归」。现在默认落点收口到 `scripts/lib/e2e-shots.mjs` 的 `shotsDir()`
（默认 `scripts/e2e/.shots/`，已 gitignore）。

| 判据 | 静默失效会长什么样 |
|---|---|
| ① 收口模块存在且导出 `shotsDir()` | 模块被删/改名 → 所有套件 import 失败，但如果没人跑 e2e 就无人发现 |
| ② **凡会落盘截图的套件**都恰好一次 `const SHOTS = shotsDir();` | 新写一套 e2e 时照抄旧脚本的硬编码路径 → 再次污染工作区（这正是本项要防的回归） |
| ② 分母（会落盘截图的套件数）必须 > 0 | `screenshot(` 被重构掉后，判据 ② 变成永真 —— **检查还在、却不再检查任何东西** |
| ③ 不出现 `frontend/docs/screenshots` 字面量 | 连注释里当「示例」写一次都会被抄走 |
| ④ 不出现写死盘符的绝对路径 | 换机器/换检出目录即失效，且失效方式是**静默写到别处** |
| ⑤ `.gitignore` 真的忽略了 `scripts/e2e/.shots/` | ★ **最容易被漏掉的一条**：只改脚本不改 `.gitignore`，等于把污染换个地方 —— 抄 `shotsDir()` 是抄不出 `.gitignore` 的 |

**反向验证**（证明守卫真在判；沙箱现场建 / 跑完自毁；**脚本已入库，可随时重跑**）：

```powershell
node scripts\lib\check-e2e-shots-reverse.mjs
#    预期：SUMMARY: 9 cases, 0 failed
#    注入① 删掉 e2e-shots.mjs                -> exit 1（① 缺少收口模块）
#    注入② 某套退回硬编码受控目录            -> exit 1（②③ 点名该文件与行号）
#    注入③ 加一行 D:\… 绝对路径              -> exit 1（④）
#    注入④ .gitignore 去掉 scripts/e2e/.shots/ -> exit 1（⑤）
#    注入⑤ 清掉全部 screenshot( 调用          -> exit 1（② 在空转）
#    注入⑥ 清空套件目录                      -> exit 1（「什么都没扫到」≠ 通过）
#    另含：基线 exit 0、还原后复跑 exit 0、沙箱自毁确认
```

> ⚠️ 反向验证脚本必须用**异步** `spawn`：本机 Node 的同步派生态（`spawnSync` / `execFileSync`）
> 一律 `EBUSY errno -4082`，表现为 `status=null` 且无任何输出 —— 看起来像「守卫没跑」，
> 排查方向会整个跑偏（改用仓库自带的 `scripts/lib/docker-exec.mjs` 的 `run()`）。

**要归档文档配图时**：显式指定落点，例如
`$env:SHOTS='frontend/docs/screenshots'; powershell ... scripts\e2e\run-ui-checks.ps1`。

### 4.10e 云端同步暂存副本新鲜度自检（#343；改了要上云的文档 / 做过云端同步后必做）

上云（腾讯文档 / 飞书 / 资料库）上传的**不是源文件本身**，而是 `.workbuddy/sync-staging/` 下的副本。
于是有一条**不会报错的失效链**：`源文件改了 → 忘了重新导出副本 → 上传的还是旧副本 → 云端显示「导入成功」`。
**「导入成功」只说明传输没问题，不说明内容是最新的** —— 2026-10-12 复核实测抓到 **4 份落后**
（README 源 421,472B / 云端副本 416,126B；PLAN、SELFTEST、项目简报同理）。

```powershell
# ① 先设两个环境变量（暂存目录 + 工作区根目录；6 份源文件在工作区根，不在仓库里）
$env:DUODUO_SYNC_STAGING = 'C:\Users\16947\WorkBuddy\2026-09-18-17-36-12\.workbuddy\sync-staging'
$env:DUODUO_WORKSPACE    = 'C:\Users\16947\WorkBuddy\2026-09-18-17-36-12'

# ② 从源文件重建全部副本（导出后脚本会**立刻自验**，不通过即 exit 1）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\lib\sync-staging.ps1 -Action export
#    预期：exit 0，输出「已导出 11 份副本」+「实际比对份数：11 / 映射 11」+ 11 行 [OK]

# ③ 只验不发（上传前跑这一步）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\lib\sync-staging.ps1 -Action verify
#    预期：exit 0；任一副本 != 源文件则 exit 1 并点名该份（**[STALE] 源 X B / 副本 Y B**）

# ④ 不需要暂存目录的那一半（CI 用这个）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\lib\sync-staging.ps1 -Action manifest
#    预期：exit 0，输出「仓库侧映射 5 条全部存在且与约定一致」
```

| action | 做什么 | 判据 |
|---|---|---|
| `export` | 从**源文件**重建全部副本（副本从此不是"手工维护的第二份真相"） | 导出后**立刻自验** |
| `verify` | 逐份比 **SHA256**，副本 != 源文件即判失败（**不是 WARN、不降级**） | **自报比对数**；为 0 直接判违规 |
| `manifest` | 只校验映射表自身没腐烂（条数 > 0、仓库侧源文件都存在、约定的 5 份仓库文档一份不漏） | CI 里跑（不需要暂存目录） |

★ **`exit 3` = 本机不适用，不是失败**：没给 `-StagingDir`（或目录不存在）时脚本打 `[N/A]` 并 exit **3**
—— 刻意**不返回 0**，因为「没跑」不能长得像「跑过且通过」。

★ **`check-all.ps1 [0/5]` 里的第三态**：配了 `DUODUO_SYNC_STAGING` 就真跑并记 `[PASS]/[FAIL]`；
没配则记 **`[N/A]`** 并在汇总末尾单独计数（"另有 N 项在本机不适用 —— 它们没有被执行，不要当成通过"）。
两条分支都实测过（`[N/A]` 那次 exit 0、配好之后 `[PASS]` exit 0）。

★ **比对器的假红源：HTML 数字实体**。源文件里可能残留 `&#x7684;` 这类实体（历史上被转义过一次），
文档站与飞书渲染成 `的`、回读拿到的也是**字符**；不解码就会把「源有实体、云端有字符」判成缺失
（实测 `项目简报` 28 处、最长连续未覆盖 21 字符，全部越过阈值）→ 两个比对器
（`feishu_verify_content.py` / `tdoc_verify_content.py`）现在**两侧都先解码数字实体**再归一化，
且**只解数字实体**（`&#x…;` / `&#…;`），不动 `&amp;` 这类命名实体（源里可能真就这么写）。

### 4.10 双端功能对照表自检（S6；动前端页面 / 改 tabBar 后必做）

```powershell
# docs/S6-DUAL-CLIENT.md 与两端源码的一致性守卫（纯静态，不需要 Docker）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\lib\check-dual-client.ps1
#    预期：exit 0，输出
#      「文档引用的页面全部存在，tabBar 双向一致，缺口清单仍成立。」
#    三条判据：
#      ① 文档里引用的每个 miniprogram/pages/<x>/<x> 与 pages/<X>.tsx 必须真实存在；
#      ② 文档标为 tab 的小程序页集合 == miniprogram/app.json 的 tabBar.list（双向）；
#      ③ §3 缺口清单仍成立（D1 网页端无个人主页 / D2 小程序无宠物档案页）。
#    ★ 缺口一旦被补齐，守卫会报红提醒把该 D 项从「缺口」改为「已完成」——
#      这是**设计如此**，不是误报。

# 反向验证（证明守卫真的在判，跑完自毁沙箱）
powershell -NoProfile -ExecutionPolicy Bypass -File .workbuddy\tmp-e25\dual-reverse-check.ps1
#    预期：SUMMARY: 8 cases, 0 failed
```

**这一节守的三件事，以及为什么非守不可**：

| 判据 | 静默失效会长什么样 |
|---|---|
| 页面路径存在性 | 文档里写着一个早已改名的目录，没人发现；新人照文档找页面找不到 |
| tabBar 双向一致 | tabBar 第 6 项会被微信框架**直接拒绝启动**（上限 5 项）；文档与代码不一致 → 改错地方 |
| 缺口清单自证 | 缺口被补齐后文档仍写着「缺失」，误导后续排期 |

**踩过的坑（写守卫时实测）**：

| 坑 | 现象 | 正确写法 |
|---|---|---|
| `& grep` | PowerShell 下报 `CommandNotFoundException`，`$ErrorActionPreference='Stop'` 时直接炸整个脚本 | 用 `Select-String`（内建，任何宿主都有） |
| `Get-ChildItem -Path <裸目录> -Recurse -Include '*.tsx'` | PS 5.1 下**可能静默返回空** → 命中数恒为 0，断言"永远成立" | 改用 `-File -Recurse` + 手工按 `.Extension` 过滤 |
| 读重定向日志 | `bytes[1] -eq 0` 判 UTF-16LE，在**文件带 BOM** 时恒假（`bytes[0..1] = ff fe`）→ 中文全乱、文案匹配恒不命中 | 先判 `ff fe` BOM 头 |
| 反向验证的夹具 | 只建一个空 `UserProfile.tsx` **不会**触发断言（守卫 grep 的是**内容**） | 夹具要写含关键字的真实代码 |


### 4.11 第三方依赖安全扫描（S13；升级依赖后必做）

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\lib\check-dependency-security.ps1
#    预期：exit 0，输出
#      「无低于安全下限的依赖，声明可复现，供应链卫生无异常。」
```

**为什么不用 OWASP dependency-check / `npm audit`**：
两者都**必须联网**（前者首次要下数 GB 的 NVD 数据库，后者要打 registry 的 advisory 接口）。
CI 与本地验收环境都不保证外网可达 → 挂进流水线只会得到一个"平时绿、需要时连不上"的摆设。
**铁律 21：`FAILED`（没跑成）是一等状态** —— 一个"跑不起来就跳过"的扫描等于没扫描。

**三层判据**：

| 层 | 判什么 | 违规示例 |
|---|---|---|
| ① 已知漏洞版本底线 | 本地规则表（10 条：npm 6 / maven 4）里的依赖，版本不得低于安全下限；**每条注明 CVE 与修至版本** | `vite` 降到 `4.0.0`（< 5.4.11） |
| ② 版本锁定 | 依赖声明不得是 `*` / `latest` / 空串（工作区内 `@duoduo/*` 用 `*` 是 npm workspaces 惯例，豁免）；`frontend/package-lock.json` 必须存在 | `react: "latest"`；删掉 lock 文件 |
| ③ 供应链卫生 | 不得配非白名单 registry；不得有 `preinstall`/`postinstall`/`install` 钩子（npm 供应链投毒最常见落点）；Maven settings 不得有 `http://` 明文仓库 | `.npmrc` 指向第三方源；`postinstall: curl … \| sh` |

**规则表维护**：升级依赖后若某条不再适用，连同注释一起删除；新依赖受 CVE 影响则新增一行。
**不要**为了"让扫描过"而调低下限 —— 下限一律填官方修复版本。

**反向验证**：`SUMMARY: 8 cases, 0 failed`（含基线绿 / npm 降版红 / maven 降版红 /
`latest` 红 / 删 lock 红 / 加 postinstall 红 / 非白名单源红 / 依赖全删→判据静默失效红）。


### 4.12 对象存储抽象层自检（S9；改上传链路 / 动 `duoduo-common-web/.../storage` 后必做）

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\lib\check-storage-guard.ps1
#    预期：exit 0，输出
#      「[storage-guard] 通过：4 层判据全绿，扫描 3 个调用方文件」
```

**它防的是什么**：S9 之后「图片/材料落在哪」从写死在两个类里的本地盘，变成了可切换的
`ObjectStorage`。这类改造最典型的退化是**有人绕过抽象层直接碰本地盘**
（新写一段 `Files.copy(in, uploadsDir.resolve(...))`、或把 `java.nio.file.Path` 塞回接口签名里）。
它**不会自己报错** —— 表现是"配置改成了 COS，但某些新功能依然落在容器卷里"，
两处都"看起来正常"。**不进 CI ＝ 迟早静默腐烂。**

| 层 | 判什么 |
|---|---|
| ① | `ObjectStorage` 接口不得出现 `java.nio.file / Files / Path`（抽象没被污染） |
| ② | 三个调用方（`UploadController` / `MerchantMaterialReadController` / `MerchantMaterialStorage`）不得出现 `Files.copy\|write\|readAllBytes\|createDirectories\|FileSystemResource` |
| ③ | 配置一致性：`duoduo.storage.type` 的**默认值必须是 `local`**；compose 必须透传 `UPLOADS_PUBLIC_BASE_URL` 与 4 个 `COS_*` |
| ④ | ★ **正对照**：`LocalFsObjectStorage` 里**必须**能扫到 `java.nio.file` —— 否则前几层的"干净"只是因为**没读到文件** |

**两个实测踩到的坑（都已写进脚本注释）**：

- **上溯目录层数写错**：脚本在 `scripts\lib\` 下，要 `Split-Path -Parent` **三次**才是仓库根；
  写成两次得到 `scripts\`，所有 `Test-Path` 全落空。
  ★ **正是"扫描数为 0 判违规"这一条当场把它揪出来的** —— 若没有这条，脚本会输出一片"全部通过"。
- **必须先剥注释再匹配**：这些类的注释里**故意**写着"为什么不能用 `java.nio.file.Path`"、
  "与改造前 `Files.readAllBytes` 同量级"这类反向说明 —— 全文匹配会把"解释为什么不用"判成"用了"。

**反向验证**：把 `duoduo.storage.type` 的默认值改成 `cos` ⇒ 应**恰好在第 ③ 层报红并点名**
（实测输出：`[FAIL] duoduo.storage.type 默认 = 'cos' —— 默认必须是 local…`），还原后复绿。

**配套工具（S9 收尾）**：

```powershell
# 存量图片/材料迁移到对象存储：幂等 / 可停可续 / 逐文件双源 sha256 核对
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\migrate-uploads-to-cos.ps1            # 干跑（只读桶，不写任何对象）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\migrate-uploads-to-cos.ps1 -Apply     # 真跑
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\migrate-uploads-to-cos.ps1 -VerifyOnly # 只核对（桶内 vs 卷内）
#    ★ 判据是「卷里那份的 sha256」与「桶里**回读**那份的 sha256」相等 ——
#      不是"上传接口返回 200 就算成功"（内容被截断在只看返回码的写法里完全看不出来）。
#    ★ 三种模式由 **-Apply 一个开关**决定写不写桶；干跑时一个字节都不写。
#      （2026-10-13 实测踩过：第一版把条件写成 `-not $VerifyOnly`，默认干跑把 132 个对象写进了真实桶。）

# 通用 COS 客户端（运维脚本复用；纯 .NET 实现签名 v5，无需装 COSCMD）
. scripts\lib\cos-tool.ps1
$cfg = Get-CosConfig -Role public          # 或 -Role private
Invoke-CosHead -Cfg $cfg -Key 'uploads/<uuid>.png'
```


---

## 5. 手工业务验收（按角色走一遍）

自动化覆盖的是「接口对不对」，下面这些是「用起来顺不顺」。
建议在浏览器（http://localhost:5173）和小程序里各走一遍。

### 5.1 买家主流程

| 步骤 | 操作 | 预期结果 |
|---|---|---|
| 1 | 注册新账号（手机号 + 密码 + 昵称） | 注册成功；重复注册同一手机号被拒绝或幂等 |
| 2 | 登录 | 进入首页；刷新后仍登录（令牌未丢） |
| 3 | 首页浏览商品，下拉刷新 | 商品列表有数据；**每次下拉浏览量 +1**（口径是「次」，可累加） |
| 4 | 搜索「猫粮」 | 命中相关商品；无结果时有空态提示，不是白屏 |
| 5 | 进商品详情，切规格 | 价格/库存随规格变化；**想要人数按买家去重**（同一个人反复点不涨） |
| 6 | 加入购物车 | 角标变化；购物车页数量正确；**同规格累加数量而不是新增一行** |
| 7 | 结算预览 | 金额、运费、券抵扣正确；没选地址时提示先填地址 |
| 8 | 新增收货地址，设为默认 | 默认地址唯一；删除默认后自动顺延给另一条 |
| 9 | 下单 | 生成订单号；库存被冻结（可用量减少）；重复提交同一幂等键不重复建单 |
| 10 | 支付（沙箱） | 拿到支付二维码；订单进入「待发货」；**同一订单重复支付被拒** |
| 11 | 30 分钟不支付 | 订单被自动关闭，库存回补，券退回 |
| 12 | 卖家发货后确认收货 | 资金从托管转为放款；订单完成 |
| 13 | 评价晒单 | 提交后商品详情出现评价；**追评**单独展示；匿名评价昵称脱敏 |
| 14 | 申请退款（未发货 / 已发货） | 未发货可直接退；已发货进售后流程 |
| 15 | 我的订单列表按状态筛选 | 各状态条数与操作一致；已关闭订单不能再支付/取消 |
| 16 | **网页商城顶栏头像角标（S7）** | 在**任意页**（不只通知页）顶栏头像就有未读数字；数字 == `GET /api/socials/notices/unread-summary` 的 `total`（**服务端合并口径，Web 不另算一套**）；点开菜单后「通知中心（N）/私信（N）」分项与角标同源；切到别的标签页再切回来数字会刷新；未登录访客没有头像菜单、也没有角标 |

### 5.2 卖家 / 商家端

| 步骤 | 操作 | 预期结果 |
|---|---|---|
| 1 | 商家入驻（先实名） | 未实名被拒；重复入驻幂等 |
| 2 | 发布商品与规格 | 审核中状态；后台可审 |
| 3 | 商家端订单列表 | **只看到自己的订单**；别人的订单 403 或不可见 |
| 4 | 发货 | 物流轨迹自动生成「已揽收」；订单转待收货 |
| 5 | 处理售后退款 | 同意 / 拒绝都有记录；已放款订单走另一条边界 |
| 6 | 结算对账 | 金额 = 货款 − 平台佣金；与托管单一致 |

### 5.3 平台运营 / 管理后台

| 步骤 | 操作 | 预期结果 |
|---|---|---|
| 1 | 普通用户访问后台 | **403**（OperatorGuard 拦住），不是 401 |
| 2 | 运营账号登录后台 | 放行；能看到全平台报表与资金对账 |
| 3 | 内容审核（闲置 / 晒单 / 举报） | 通过 / 驳回生效；驳回理由可见 |
| 4 | 类目与品牌维护 | 增删改生效；商品挂载正确 |
| 5 | 订单干预：强制关单 | 关单成功且**冻结库存被释放**；重复关单被状态机拒绝（不是 500） |
| 6 | 优惠券管理 | 建券 → 发放 → 核销闭环；并发领取不超发 |
| 7 | **秒杀活动管理**（本轮新增） | 新建活动（标题 / 起止时间 / 参与 SKU 与秒杀价）→ 列表可见 → **下架** → 仍能列出来并重新上架 → 前台不可见 |
| 8 | 账号管理 | 运营可查脱敏列表但**改角色 403**；ADMIN 才能提权 |

### 5.4 小程序端专属核对

| 检查点 | 预期 |
|---|---|
| 长 id 显示 | 订单号 / 商品 id 在任何地方都按**字符串**处理，不能出现 `710000000000000000` 这种精度丢失 |
| 时间显示 | 所有时间按 UTC 解析后转本地，不能整体差 8 小时 |
| 按钮文字 | 任何按钮都不折行（挤不下宁可横向滚动） |
| 胶囊标签 | 高度与文字匹配，不能「框比字大」 |
| 页面跳转 | 从「我的」进订单页能返回；非 tab 页有原生返回键 |
| 未读角标 | 只有一个来源，不会把私信数两次：小程序走 `utils/unread.js`；**网页端走 `shared/src/store/app.tsx`**（读同一个 `unread-summary`），两端**服务端同一口径** |

---

## 6. 常见问题排查（症状 → 根因 → 做法）

| # | 症状 | 根因 | 做法 |
|---|---|---|---|
| 1 | 冒烟报「★ 交易库没有残留 FROZEN 台账行 → 992」 | **上一轮测试遗留的待付款订单**冻着库存，全局不变式失败 | 做 §2.1 体检 + §2.2 清场，再重跑。代码很可能没问题 |
| 2 | 下单返回 422 库存不足，但后台看库存明明够 | 库存被别的脚本冻住了 | 同上，先清场 |
| 3 | 压测下单错误率 17%，一堆 429 | Sentinel 限流（`order-place` 默认 100 QPS）在正常工作，是压测打太快 | 压测已内置限速 80 rps；手工调高需 `DUODUO_TRADE_PLACE_QPS` 重建 trade |
| 4 | 跑了压测之后，后面所有脚本都失败 | 压测没清干净（本轮已修：三重自还原） | 清场；确认 `bench.py` 结尾没有「警告：N 笔订单未取消」 |
| 5 | PowerShell 报 `ParserError 字符串缺少终止符` | `.ps1` 的 **UTF-8 BOM 丢了**，Windows 老版 shell 把中文注释解析坏 | 改完 `.ps1` 一定要确认 BOM 还在（Python 写回会丢） |
| 6 | 汇总显示 `NO-RESULT`，但单套明明过了 | 中文输出编码对不上 | 判定改用退出码；或脚本里设 `[Console]::OutputEncoding = UTF8` |
| 7 | `$order += Get-ChildItem ... \| ForEach{...}` 得到错误文件名 | **管道优先级高于 `+=`** | 整体 `@( )` 包住 |
| 8 | `$ErrorActionPreference='Stop'` 下循环第一套就中断 | 本机命令往 stderr 写一点东西即被判终止错误 | 调用处临时置 `Continue` |
| 9 | `spawnSync docker` 报 `EBUSY errno -4082`，看着像 docker 坏了 | 本机 Node **同步派生态被禁**（连 `cmd`、`where` 都起不来） | 一律用异步 `spawn`，走 `scripts/lib/docker-exec.mjs` |
| 10 | 小程序测试里「接口 200 且有数据，页面却当失败」 | `wx.request` 桩给 GET 带了 body，undici 直接抛错 | 桩里 GET 不带 body |
| 11 | 小程序测试报 `getApp()` 返回 `{}`，页面读 `globalData.user` 崩 | 没先加载 `app.js` | harness 里必须先加载 app.js 再挂页面 |
| 12 | 小程序页面 `await` 永久挂住 | 未知 wx API 的链桩 `then` 不是 undefined | 链桩的 `then` 必须返回 undefined |
| 13 | 消息发不出去 / 商品 id 变成另一个数 | 雪花 id 被 `Number()` 转过，18~19 位超出 JS 53 位精度 | 全链路按**字符串**流转；`check-miniprogram.js` 规则 6 会拦 |
| 14 | 页面上时间比真实时间少 8 小时 | 把 UTC 挂钟当本地时间（`replace('Z','')` 后裸切片） | 按 UTC 解析再转本地；`short-time-test.js` 会验 |
| 15 | 微信登录测试在别人机器上永远红 | 同一接口有**多种合法形态**：配了真实 AppID → 401；没配 → 503 降级 | 按**实际返回**选分支，落在两者之外才算真失败 |
| 16 | `search-smoke` 没跑但汇总说全通过 | ES 没起 | 运行器会**显式跳过并列入汇总**；要跑就 `docker compose --profile search up -d duoduo-es` |
| 17 | `git push` 报 `CRYPT_E_REVOCATION_OFFLINE` | TLS 吊销检查抖动 | **直接重试即可**，不要关 `sslVerify` |
| 18 | 容器内 jar md5 与宿主机对不上 | ① 忘了 `docker compose build`；② Git Bash 路径转换 | 加 `export MSYS_NO_PATHCONV=1` 后重比 |
| 19 | 前端 e2e 连不上（`ERR_CONNECTION_REFUSED`） | dev server 没起 | 一键运行器会自动起；手工跑记得 `npm run dev -- --port 5173` |
| 20 | `docker compose build trade` 报 no such service | 服务名不对 | 正确名字是 `duoduo-trade-app` |
| 21 | 断言「闲置数据应为 8 条」红了，实际 42 条 | 断言写死了**当前条数**而不是不变性质 | 改成 `-ge 8` 之类钉「性质」的写法 |
| 22 | 前端页面计数排序错乱（"10" < "9"） | 计数字段被序列化成**字符串**下发 | 加进 `LongToStringJacksonModule.NUMERIC_LONG_FIELDS` |
| 23 | **e2e 的 `home` 与 `product-detail` 同时报「商品图零裂图」失败** | **数据脏**：上一轮冒烟被中断，残留一条带 `https://example.com/…` 占位图的**夹具评价**，挂在种子商品上 → 首页与详情页都渲染出裂图。**看起来像两套页面同时回归，实际是数据** | `SELECT COUNT(*) FROM t_review WHERE images LIKE '%example.com%';` 应为 0；`review-smoke.ps1` 现在**开机自动清扫**，也可手工 `DELETE FROM t_review WHERE images LIKE '%example.com%';` |
| 24 | 全量冒烟跑到一半**卡住不报错**、日志不再增长 | 承载它的终端/会话被回收（长任务挂在壳里）；**不是脚本自己挂** | 判断依据是**日志还在不在增长**；重跑时别中途离开，或改用一键自检（`check-all.ps1`）逐段跑 |
| 25 | `extract-api-contract.py --check` 报「文档落后于源码」 | 加了新端点但没重新生成契约 | 跑 `python scripts/extract-api-contract.py`（**加了接口就必须跑**，否则这条检查迟早红） |
| 23 | 压测第二档 0 样本 | 夹具只按一档的量准备 | 池子要按「预算 × 并发档数」给 |
| 24 | 直连 `127.0.0.1:8081` / `:8082` 一律 404，连累 address / credit / id-precision / review / wechat-auth 五套冒烟 | **宿主上有别的进程占了 `127.0.0.1:8081/8082`**（比 docker-proxy 的 `0.0.0.0:8081/8082` 更具体，抢走 loopback 流量）。`netstat -ano \| findstr :8081` 看 PID，再 `tasklist /FI "PID eq <pid>"` 认人 | 换一条能到容器的地址重跑：`-User http://<本机局域网IP>:8081`、`-Product http://<本机局域网IP>:8082`；或先关掉占用进程。**别改脚本默认值**——别的机器上没有这个占用 |
| 25 | `docker compose --profile search up -d duoduo-es` 报 `bind: An attempt was made to access a socket in a way forbidden by its access permissions` | Windows 把 9200 划进了**保留端口区间**（`netsh interface ipv4 show excludedportrange protocol=tcp`，本机是 9149–9248） | ES 不需要发布宿主端口，容器间走 `duoduo-es:9200` 即可。用 `docker run --network duoduo-pet-mall-java_default` 起、**不带 `-p`**；`run-all-smoke` 探不到 9200 会显式跳过 search 套 |
| 26 | 券冒烟偶发红在「（夹具）券已被人为改成『锁定超时』」 | 补偿调度每 60 秒扫一次，可能正好落在「改成超时」与「断言超时」之间把券解掉（约 0.5%/次） | 已修：夹具重做一次 + 判据改取**终态**（「超时锁不会被永久锁死」）。若仍红，看券是不是被别的脚本占了 |
| 27 | 新写的 UI 套件「功能全对但断言全红」，失败信息全是 `Failed to load resource: ... 401` | **断言把设计内状态当成了缺陷**。未登录时 `/api/users/me`、`/api/cart/count` 必然 401（页面各自兜底）；「只带 dd_rt」场景更是**靠一次 401 触发单飞刷新再重放** | 过滤掉它：`consoleErrors.filter((e) => !/favicon\|status of 401/i.test(e))`（`seckill-ui-check.mjs` 既有口径）。**页面报错里真正的 `pageerror` 一律不能放过**。判据：401 是「未带令牌的正常往返」，500/`pageerror` 才是缺陷 |
| 28 | 反向验证时「改坏一处、红了三条」，看起来像三个缺陷 | **断言之间耦合**：后一条依赖前一条的点击结果（下拉入口一旦不存在，脚本停在首页，`reload` 之后自然看不到后台页） | 让每条断言**自证**：需要「在 /admin 刷新」就先显式 `page.goto('/admin')` 再 `reload()`，不要沿用上一步的落点。级联假红会把归因引偏 —— 与「不重不漏划分」是同一条纪律 |
| 29 | UI 自动化里「入口不存在」时脚本不报 FAIL，而是等 30 秒后抛异常、连汇总行都没打出来 | 直接对可能不存在的元素 `click()`，Playwright 会等到超时抛错 | **先数再点**：`const n = await link.count(); check(..., n === 1); if (n === 1) { await link.click(); ... } else { check(..., false, '入口不存在，无从点击'); }`。反向验证尤其需要这条，否则拿不到可归因的失败信息 |
| 30 | 断言「下拉菜单里有 X」，实际有两个却报 `found 2`；或反过来明明有却查不到 | **同一标签可能在两处出现**（运营账号的「商家端」顶栏与下拉里都有），在 `.app-header` 这种大作用域里查会把两处都数进来 | 给浮层加稳定类名（本项目是 `.user-menu`），断言一律**限定在目标容器内**：`page.locator('.app-header .user-menu').getByRole('link', { name: '商家端', exact: true })` |
| 31 | 点击某个按钮一直超时，日志写 `… <a href="/orders" class="nav-link">我的订单</a> … intercepts pointer events`，看起来像「按钮点不动 / 按钮坏了」 | **浮层遮挡**，不是按钮的问题：右上角展开的头像下拉（176px 宽、`zIndex:80`）正好压住了页面右上角的按钮；而 `Layout` 的菜单**只在路由变化时收起，没有 Esc 关闭** | 测试里显式收起浮层：`await page.locator('.app-header button.icon-btn').first().click()`（再点一次头像）。**判据：Playwright 说「被某某元素拦截点击」时，先想「谁盖住了它」，别去怀疑被点元素本身**。（给菜单补 Esc 关闭仍是一个待办的小改进） |
| 32 | 图表**看起来数据全是 0**（柱子全平），但接口 200、字段齐全、数值也非零，连元素上的 `style="height:52.75%"` 都算对了 | **百分比高度解析失败**：柱高是 `height: X%`，而父元素 `.bar-col` 的 `height` 是 `auto` —— 「父高由内容决定、内容高又依赖父高」形成循环依赖，浏览器按规范把百分比**退化成 `auto`**，于是所有柱子一起塌成 `min-height`。实测 `.bar-col` 计算高只有 25px | **给柱子一个高度确定的容器**：外层 `flex:1` 的轨道 + 列 `height:100%`（两者缺一不可）。**判据：凡是「按比例画高度」的地方，先确认它的父链上有一个 definite height** —— `auto` 高度父元素里的百分比 = 不生效。这类缺陷接口层与静态检查都测不出来，只能开浏览器量实际渲染高度 |
| 33 | 图表**画不出来 / 柱子全空**，但接口数据看着完全正常 | `long value` 被序列化成了**字符串**（`{"name":"待发货","value":"144"}`）—— ECharts 会把字符串当**类目名**而不是数值。根因是 `LongToStringJacksonModule` **默认把所有 Long 转字符串**（保护雪花 id），只有 `LongToStringJacksonModule.NUMERIC_LONG_FIELDS` 白名单里的字段保持数字 | 把该字段名加进白名单（模块注释里写明的规矩：**这个值前端会拿去做加减/乘除/比大小吗？会就加**）。⚠️ 改的是**共用模块** ⇒ 必须重建全部依赖方，且**逐模块比对宿主机 jar 与容器内 `/app/app.jar`**；注意判据是「**该模块是否依赖被改的模块**」——网关只依赖 `duoduo-common`、jar 内 common-web 条目数为 0，它没被重建是**正确**的，不是漏构建 |
| 34 | UI 套件里点某个按钮，日志说元素被 `<a href="/orders" class="nav-link">…` 之类的浮层元素拦截（`intercepts pointer events`） | **浮层遮挡**，不是被点元素坏了：展开的头像下拉（176px、`zIndex:80`）正好压在页面右上角的按钮上；而 `Layout` 的菜单**只在路由变化时收起、没有 Esc 关闭** | 测试里显式收起浮层：再点一次头像。**判据：Playwright 说「被某某元素拦截点击」时，先想「谁盖住了它」** |
| 35 | 写 e2e 时请求返回 `undefined`，断言无法判定（既不是通过也不是明确失败） | 用了**不存在的路径或参数名**：例如公开闲置检索其实是 `/api/listings/search`（裸 `/api/listings` 是发布写端点），把 `data.items` 读成 `undefined` 后 `?? 0` 会**静默变成 0**、令断言失去意义 | 断言里**不要用 `?? 0` 兜底掩盖形状错误**：先断言「结构存在且类型正确」（如 `typeof data.total === 'number'`），再做业务判定。写路径前先看后端 Controller 的 `@GetMapping`，别照抄页面注释 |

---

## 7. 交付前门禁（DoD，缺一不可）

- [ ] `node scripts/check-miniprogram.js` → 0 错 0 警
- [ ] `frontend: npm run build` → 退出码 0
- [ ] `scripts\check-all.ps1` 的 `[0/5]` 静态守卫**全部**退出码 0（**不写死个数**，按实际：
      断言绑定 / UTF-8 BOM / 网关路由覆盖 / Mapper SQL 实体 / 运维手册一致性 / 双端功能对照表 /
      依赖安全 / **对象存储抽象层（S9）** / 文档飞书吞行吞字 / 图表主题接入点 / e2e 截图输出 /
      云端暂存副本新鲜度）。★ 清单里允许出现 **`[N/A]`**（本机不适用的项，如未配 `DUODUO_SYNC_STAGING`），
      但**不允许把 `[N/A]` 写成 `[PASS]`** —— 汇总会单独把 N/A 数出来。
- [ ] `mvn -o test` → BUILD SUCCESS（**735 项 / 72 个测试类**，2026-10-13 实测；
      715 = S9 之前的基线，+20 = 新增 CosSignerTest 7 / LocalFsObjectStorageTest 8 / CosObjectStorageLiveTest 5，
      其中 Live 那 5 项在常规跑里按设计**跳过**，只有设了 `COS_LIVE_TEST=true` 才执行）
- [ ] `scripts\run-all-smoke.ps1` → **共 32 套 / 通过 32 套 / 失败 0 套**（另有 `search-smoke` 允许按设计跳过，但**必须在汇总里被点名**）
- [ ] `python scripts/extract-api-contract.py --check` → PASS（**加了接口就必须先重新生成**）
- [ ] `node --test scripts/mp-harness/*.test.js` + `scripts/lib/*.test.js` → 0 fail
- [ ] `scripts\e2e\run-ui-checks.ps1` → **22 套全 PASS**
- [ ] `python scripts/perf/bench.py --check` → 全部达标
- [ ] 8 个模块容器内 jar md5 与宿主机一致
- [ ] 跑完做一次数据体检：FROZEN = 0，且**没有残留的 `example.com` 评价夹具**
- [ ] `README.md`、`docs/PLAN.md` 与代码**同一次提交**更新

> ★ **这些数字只是「最近一次实测的参考值」，判据一律是退出码**：
> 加了一套测试、或某套多项断言，数字都会变，**但只要没失败就不该判红**；
> 反过来，**数字对得上也不代表通过** —— 必须看退出码。
> （写死数字当门禁，会在加测试后变成一句假话；`check-all.ps1` 的套件数就是从目录实算的。）

---

## 8. 已知未覆盖清单（自动化测不到的部分）

> 这一节**不是待办列表**，而是「哪里看不到绿灯、因此必须由人（或另一次专门演练）来确认」。
> 有了它，交付时才不会把「没测」误当成「测过了」。每一项都注明**现在的替代手段**，
> 以便判断风险是否可接受。**清单本身也要维护**：某项被自动化覆盖后，从这里移到 §3/§4。

| # | 未覆盖的部分 | 为什么自动化测不到 | 现在的替代手段 |
|---|---|---|---|
| 1 | 微信小程序**真机表现**（导航栏、胶囊、真机字体渲染） | 小程序只能跑在微信运行时里，CI 里没有 | `check-miniprogram.js` 8 条静态规则 + `mp-harness` 72 项行为断言 + **用户在开发者工具/真机点一遍**（B-20） |
| 2 | 微信**真实渠道**支付回调、订阅消息下发 | 需真实商户号与线上回调地址；沙箱环境拿不到 | `pay-channel-smoke.ps1` 62 项（扫码/验签/回调幂等，用沙箱渠道）+ 真实链路留待上线前用商户号验 |
| 3 | 平台后台 2FA 的**真实认证器 App** 交互 | 断言能验 TOTP 算法与状态机，验不了"用户拿手机扫一次" | `admin-2fa-smoke.ps1` 56 项（含 RFC 6238 官方测试向量对拍） |
| 4 | ES 检索链路的**宿主机侧**可达性 | **设计如此**：`docker port duoduo-es` 为空（compose 只给 `profiles:[search]` 且不映射端口），宿主机 `127.0.0.1:9200` 必然不通 | 容器内 `_cluster/health` = green；`search-smoke` 按设计**显式跳过并在汇总里点名**（不许静默略过） |
| 5 | 深色主题的**视觉可读性**（对比度是否够看） | 能断言"像素变了""没引 prefers-color-scheme"，断言不了"好不好看" | `check-chart-theme-contract.mjs` 13 项 + `admin-charts` e2e 的 canvas 像素指纹 + **用户肉眼确认**（S10 是否做全站深色仍未拍板） |
| 6 | 超出容量模型的**极端压测梯度** | 单机环境跑不到生产量级；且压太久会污染环境数据 | `python scripts/perf/bench.py --check` 9 接口梯度 + A12 压测记录 + 容量模型对照 |
| 7 | 三站点在**真实域名 + HTTPS** 下的行为（Cookie 作用域、跨站、证书） | 目前全是 `*.localhost` 明文，规则与真实域名不同 | `header-layout` 88 项覆盖多宽度与多角色；真实域名待部署时验（B-18 部署位置未定） |
| 8 | **多实例水平扩展**下的限流 / 秒杀 / 选主 | 本环境所有服务单实例；跨副本一致性测不出来 | 秒杀走 Redis 预扣（单点原子）、限流键不可伪造（网关先剥后写）、补偿调度有分布式选主 —— 逻辑上按多副本设计，但**未做多副本实测** |
| 9 | 主流云文档（飞书 / 腾讯文档 / 资料库）的**导入保真度** | 是外部平台行为，只能事后比对 | 逐行指纹比对（`feishu_verify_content.py` / `tdoc_verify_content.py`）+ 两条吞行守卫（`check-md-bare-tick.ps1`）。★ 比对器必须**先归一化"假红源"**（链接语法降级、HTML 数字实体），否则"内容在、却判缺失"。★ **"传的是副本"这一层**由 §4.10e 的新鲜度守卫守（源改了没重导副本，云端会一直显示"导入成功"） |
| 10 | 备份演练的**生产盘位** | `db-backup.ps1` 的 `$DefaultBackupRoot='D:\duoduo-backup'` 是**占位默认值**，目录故意未建；演练走 `DUODUO_BACKUP_ROOT` 临时根 | 演练判据（产物 SHA256 + 逐表行数 + 拒绝降级报成功）已验证；**盘位待用户拍板**（S4） |
| 11 | AI 导购的**回答质量**（好不好、够不够准） | 能测契约、护栏、约束是否生效，测不了语义质量 | R57 的 6 条护栏断言（约束硬过滤/超时降级/输出合规/价格一致性）+ 澄清反问；质量评估需人工抽样 |
| 12 | e2e 对**真实数据状态**的依赖 | 断言打的是真库真数据，历史脏数据会让它假红 | §6 的数据体检（`example.com` 夹具评价 = 0、FROZEN = 0）+ 每套 e2e 现场建/跑完自毁夹具 + **冒烟与 e2e 必须串行** |
| 13 | 云端上传的**平台授权态**（腾讯文档连接器 `not_connected`） | 授权在宿主侧、由用户完成；脚本能探测到状态但**不能自己修** | 凭据 provider 探测（`GET http://127.0.0.1:4690/mcp/internal/tencent-docs/tokens`）= `personal.not_connected` ⇒ 判定为**授权问题而非内容问题**，如实上报并给用户明确指示；**飞书 / 资料库走各自独立通道，互不影响**（本轮飞书 11/11 指纹 clean、资料库 11 份全部 `KS_IMPORT_OK`，腾讯文档待授权后补传） |

**已经闭合、从本清单移出的项（留档，避免重复怀疑）**：

- 「后台改角色仅 ADMIN」的**浏览器级**覆盖 —— 此前库内没有 ADMIN 账号，UI 断言无从构造；
  2026-10-12 起由 `admin-entry-ui-check.mjs` 的 **S5** 现场建 ADMIN 夹具补齐（UI 正/反向 + 接口层
  + 审计回表 + 并发不变式，74 项）。顺带揪出两个真问题：**服务端「最后一个 ADMIN 不可降级」
  在并发下失效**（`selectCount` 是快照读、不加锁 → 两名 ADMIN 同时降对方会降成 0 个），
  以及 `admin-smoke.ps1` 里那条**名不副实的断言**（actor==target 实际测的是"不能给自己降权"，
  只比状态码 ⇒ 两条规则混为一谈的假绿）。
- 「资料库上传」 —— 上游 `get-upload-credential` 长期返回 `TEMPORARY_ERROR`（用体积 19 KB 同样失败、
  12s/20s 多次重试无效、直连同一接口同样报错 ⇒ 判定服务端故障，与本机配置无关）。
  **2026-10-13 上游恢复**：11 份全部 `KS_IMPORT_OK`（COS 直传 + `import-local-file-async`）。

