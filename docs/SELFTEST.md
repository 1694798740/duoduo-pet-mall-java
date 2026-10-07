# 多多宠物商店 · 完整自测手册

> 适用环境：本机 Docker（Windows + PowerShell 5.1），仓库 `D:\智能体项目\duoduo-pet-mall-java`
> 目标读者：需要在自己机器上**从头到尾把全站功能完整验一遍**的人
> 配套：命令速查见 `docs/PLAN.md` §7；架构与不变式见 `docs/agent/03`

---

## 0. 先读这一页（3 分钟上手）

### 0.1 测试分三层，别一上来就跑全量

| 层 | 干什么 | 耗时 | 什么时候跑 |
|---|---|---|---|
| **体检** | 静态自检 + 编译 + 核心冒烟 3 套 | ~5 分钟 | 每次改完代码 |
| **域级** | 只跑你改的那个域对应的冒烟 + 前端 e2e | ~3 分钟/套 | 提测前 |
| **全量** | 23 套冒烟 + 契约 + 小程序 + 浏览器 e2e + 压测 | ~40 分钟 | 交付前 |

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

### 3.1 接口冒烟（23 套，`scripts\*smoke.ps1`）

| # | 功能域 | 脚本 | 项数 |
|---|---|---|---|
| 1 | 全链路基础（网关 + 用户域 + 商品域） | `smoke.ps1` | 55 |
| 2 | 购物车 | `trade-smoke.ps1` | 87 |
| 3 | 下单 / 拆单 / 库存冻结 | `order-smoke.ps1` | 77 |
| 4 | 支付 / 订单状态机 / 超时关单 | `payment-smoke.ps1` | 72 |
| 5 | 担保交易 / 资金托管 / 放款 / 退款 / 自动收货 | `settlement-smoke.ps1` | 125 |
| 6 | 沙箱支付渠道 / 预下单 / 回调验签 | `pay-channel-smoke.ps1` | 62 |
| 7 | 卖家中心（入驻 / 归属隔离 / 发货 / 对账） | `seller-smoke.ps1` | 101 |
| 8 | 平台管理后台（权限 / 报表 / 审核 / 强关） | `admin-smoke.ps1` | 72 |
| 9 | 评价与晒单 | `review-smoke.ps1` | 59 |
| 10 | 历史订单托管单回补 | `backfill-smoke.ps1` | 88 |
| 11 | 售后仲裁工单 | `aftersale-smoke.ps1` | 101 |
| 12 | 图片上传 | `upload-smoke.ps1` | 8 |
| 13 | 物流轨迹 | `logistics-smoke.ps1` | 79 |
| 14 | 信用分 | `credit-smoke.ps1` | 82 |
| 15 | 优惠券 | `coupon-smoke.ps1` | 145 |
| 16 | 收货地址簿 | `address-smoke.ps1` | 82 |
| 17 | 跨域 ID 精度 | `id-precision-smoke.ps1` | 56 |
| 18 | 微信登录绑定 | `wechat-auth-smoke.ps1` | 26 |
| 19 | 微信绑定 | `wechat-bind-smoke.ps1` | 24 |
| 20 | AI 导购 | `ai-smoke.ps1` | 37 |
| 21 | ES 商品检索（**需 ES 在跑**） | `search-smoke.ps1` | 40 |
| 22 | 限时秒杀 | `seckill-smoke.ps1` | 51 |
| 23 | 消息队列 | `mq-smoke.ps1` | 47 |
| 24 | Python 探针（不进上面的一键运行器） | `r58-restock-idempotent-smoke.py` 10 / `r58-compensate-smoke.py` 22 / `r58-listing-edit-delete-smoke.py` 33 | 65 |

> 项数会随迭代增长，**以脚本自己打印的「通过 N 项」为准**，本表只用来定位该跑哪一套。
> 当前实测：一键运行器 23 套合计 **1551 项**，全部通过。

### 3.2 前端 / 小程序 / 性能

| 类别 | 入口 | 覆盖 |
|---|---|---|
| 小程序静态自检 | `node scripts/check-miniprogram.js` | 8 条规则，0 错 0 警 |
| 小程序前端契约单测 | `scripts\*-test.js`（7 个） | id 精度 19 / 闲置筛选 42 / AI 澄清 23 / 分享圈 74 / 角标 59 / 通知入口 23 / 时间换算 12 |
| 小程序无浏览器 | `node --test scripts/mp-harness/*.test.js`、`node scripts/mp-harness/behavior.test.js` | 页面挂载 33 + AI 页 4 + 页面行为 31 + 内部凭据对拍 4 = **72 项** |
| 前端真实浏览器 | `scripts\e2e\run-ui-checks.ps1` | 17 套 654 项（首页 / **商品详情页** / 地址簿 / 商家端 / 评价 / 售后 / 物流 / 举报台 / 优惠券 / **秒杀** / **后台入口** / **顶栏布局** / **商家端看板** / **后台图表** / **我的闲置** / **分享圈** / **通知·私信·关注**） |
| 接口契约 | `python scripts/extract-api-contract.py --check` | 241 个端点 |
| 性能压测 | `python scripts/perf/bench.py --check` | 9 个核心接口 × 并发梯度 |
| 后端单测 | `mvn -o test` | 228 项 |

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

**预期**：`BUILD SUCCESS`，228 项测试 0 失败。

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

**预期**：`PASS`，241 个端点与源码一致。改了端点/字段必须重跑，它会拦住「文档与代码分家」。

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
# 一键：起 dev server → 跑 15 套 → 建/拆券夹具 → 收尾
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\e2e\run-ui-checks.ps1
powershell ... -File scripts\e2e\run-ui-checks.ps1 -Only home     # 只跑自包含那套
```

**预期**：17 套全 PASS，合计 654 项。判定同样看退出码。

其中 9 套是**自包含**的（不需要外部夹具，可单独跑）：
`home`（9）、`product-detail`（20）、`admin-entry`（32）、`header-layout`（80）、`seller-dashboard`（31）、
`admin-charts`（28）、`my-listings`（47）、`seckill`（45）、`address`（64）；
其余 7 套的前提各自写在脚本头注释里（优惠券那套由一键运行器自动建/拆夹具）。

**商品详情页套件**（`scripts/e2e/product-detail-ui-check.mjs`，20 项，第七十三轮）单独跑：

```powershell
cd frontend && node node_modules/vite/bin/vite.js --port 5173 --strictPort --host localhost   # 另开终端
cd .. && node scripts/e2e/product-detail-ui-check.mjs
```

覆盖：身份标签（多多自营/品牌入驻，与小程序同规则 supplyType）、「商品介绍」图片、
主图点击放大（弹层 / Esc / 关闭按钮）、缩略图切换、「包装规格」行、
**评分真实数据驱动**（页面评分区与评价域聚合接口逐字一致，种子假数据无法回归）、裂图与 console 健康。

**后台入口套件**（`scripts/e2e/admin-entry-ui-check.mjs`，32 项）单独跑：

```powershell
cd frontend && node node_modules/vite/bin/vite.js --port 5173 --strictPort --host localhost   # 另开终端
cd .. && node scripts/e2e/admin-entry-ui-check.mjs
```

它自己建运营与买家夹具（随机手机号 + 提权走库），不需要任何前置数据。
关键断言是 S3b：**只带 `dd_rt` 的全新浏览器上下文**（模拟关掉浏览器再打开）——
用来证明「入口关掉页面后还能再回来」。

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
| 未读角标 | 只有一个来源（`utils/unread.js`），不会把私信数两次 |

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
- [ ] `mvn -o test` → BUILD SUCCESS（228 项）
- [ ] `scripts\run-all-smoke.ps1` → 23 套全通过，0 失败
- [ ] `python scripts/extract-api-contract.py --check` → PASS
- [ ] `node --test scripts/mp-harness/*.test.js` + `scripts/lib/*.test.js` → 0 fail
- [ ] `scripts\e2e\run-ui-checks.ps1` → 15 套全 PASS
- [ ] `python scripts/perf/bench.py --check` → 全部达标
- [ ] 8 个模块容器内 jar md5 与宿主机一致
- [ ] 跑完做一次数据体检：FROZEN = 0
- [ ] `README.md`、`docs/PLAN.md` 与代码**同一次提交**更新
