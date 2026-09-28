# R52 计划：补全发布链路 + 修复两个验收问题

## 目标（用户指令：「补全发布链路，修复问题」）

1. **发布链路**（新增功能）：后端 `POST /api/listings`（发布）+ `GET /api/listings/mine`（我的发布）
   + `POST /api/listings/{id}/status`（上架/下架）；小程序跳蚤市场页 + 发布表单 + 我的闲置。
2. **问题 1（中等）**：补 `POST /api/orders/preview` 结算预览（items/商品总额/运费/券后应付，只读不写）。
3. **问题 2（轻微）**：路径存在但方法错误回 500 → 应回 405。

## 规划门禁

### A 无条件核心
- [x] 需求→步骤映射：见下方 Wave 划分，每条需求有对应改动点
- [x] 签名一致性：DTO 字段名与前端 `utils/api.js` 调用一致
- [x] 引用点实码 trace：白名单改动追溯 `GatewaySecurityProperties` + `application.yml` + `application-local.yml` 三处
- [x] 无占位符

### B 条件触发
- [x] **权限/不可回滚**（命中）：发布端点是写路径。**关键决策** —— `/api/listings/**` 当前在白名单
      （只读浏览免登），若新增写端点仍挂在该路径下会被连带放行（白名单按路径匹配、不区分方法）。
      → 必须把白名单从 `/api/listings/**` 收紧为 `/api/listings`（列表）+ `/api/listings/{id}` 形态。
      注意 AntPathMatcher `*` 不跨 `/`：
        - `/api/listings`     ← 两段？不，这是**单段**路径（/api + /listings）→ 保留免登（列表是浏览）
        - `/api/listings/*`   ← 匹配 `/api/listings/3001`（详情，免登 OK）
                              但同时匹配 `/api/listings/mine`！→ **必须改名为三段路径**，见下
      **定案**：把我的发布改为 `GET /api/listings/mine/list`（三段），避开 `/api/listings/*`。
      下架用 `POST /api/listings/{id}/status`（三段），`*` 不跨 `/` → 天然落白名单外。
- [x] **DB 写入**（命中）：`t_listing` insert/update，字段非空约束见 DDL，全部显式赋值
- [x] 生产配置改动：网关白名单是安全边界，改了必须实测「匿名调写端点 → 401」

### C 规模触发
- [x] 改动文件数 8（ListingService / ListingController / ProductDtos / OrderController /
      OrderService / OrderDtos / GlobalExceptionHandler / ErrorCode + 网关 3 文件 + 小程序 5 文件）
      → 已达「同操作 ≥3 文件」批量线，按 Wave 分批 + 每批 CHECKPOINT

## 设计决策记录

### D1. 发布端点路径与白名单收紧（安全边界）
- **现状风险**：`/api/listings/**` 免登。若把 `POST /api/listings` 直接挂上去 = 匿名 UGC 直写入库。
- **决策**：白名单由 `/api/listings/**` 改为 **`/api/listings`** + **`/api/listings/*`** 两条。
  - 列表 `/api/listings`（GET，免登，浏览无副作用）✓
  - 详情 `/api/listings/3001`（GET，免登）✓ 落在 `/api/listings/*`
  - 发布 `POST /api/listings` → **同一路径、不同方法**，白名单不区分方法 → **仍会被放行！**
    ⚠️ 这是 `SharesService` 已踩过的坑。`/api/shares` 的做法是：列表接口叫 `/api/shares/feed`（两段），
    发布接口叫 `/api/shares`（单段），白名单只放 `/api/shares/feed` + `/api/shares/*`，于是单段的
    `POST /api/shares` 落白名单外。
    → **照抄该范式**：浏览列表保持现有 `GET /api/listings`（单段）不动，但白名单里**单段路径必须移除**，
      改为只放两段与三段的只读形态。可现状 `GET /api/listings` 就是单段啊。
    **最终定案**：白名单放 `/api/listings/*`（两段，覆盖详情）**但要排除 mine**。
      → 改名为 `GET /api/listings/mine/list`（三段），`*` 不跨 `/`，落白名单外。✓
      → `POST /api/listings`（单段）在 `/api/listings` 这条白名单条目里。
      → 所以**单段条目必须从白名单删除**，只留 `/api/listings/*`。
      → 但小程序列表页调的是 `GET /api/listings`（单段）→ 会变成需要登录！
      **权衡**：浏览免登是既定设计（与商品浏览同性质，验收也已固化断言）。
        两难：单段路径上同时有「免登读」和「强制登录写」。
      **采用 SharesService 的成熟解法**：给浏览列表**另起两段路径** `/api/listings/search`，
        白名单放 `/api/listings/search` + `/api/listings/*`（详情），单段 `/api/listings` 只留 POST（强制登录）。
        - `GET /api/listings/search` 免登 ✓（与 `/api/shares/feed` 完全同构）
        - `GET /api/listings/3001` 免登 ✓
        - `POST /api/listings` 强制登录 ✓
        - `GET /api/listings/mine/list` 强制登录 ✓（三段，* 不跨 /）
        - `POST /api/listings/{id}/status` 强制登录 ✓（三段）
        **兼容**：保留 `GET /api/listings`（单段）作为**同义别名**指向同一个 search 方法，
          但白名单不含单段 → 该别名从此需要登录。小程序端同时改用 search，故无破坏。
          **但**：验收脚本里已有匿名查 `GET /api/listings` 的断言 → 会 FAIL。
            保留兼容的代价是改验收脚本 + 语义不一致。
          **最终**：**不保留单段 GET 别名**，直接迁移到 `/api/listings/search`，同步更新小程序与验收脚本。
            「一个端点一个语义」，路径与方法一一对应，不再有「同路径不同方法信任级别不同」的隐蔽面。

### D2. 发布是否需要实名
- `UserContext.requireRealNameVerified()` 已备好，注释明写「发布闲置、提现等能力」。
- **决策**：启用实名校验。理由：C2C 发布是真实的商品供给行为，且类注释早就声明该能力需实名校验。
- **风险**：测试账号若未实名会被 403 挡住。→ 实测确认测试账号 `duoduo12345` 用户的实名状态，
  若未实名则验收脚本里先走实名流程，或按现状报 403 并断言（把 403 也当契约测）。

### D3. 新发布的审核状态
- 库默认 `audit_status='PENDING'`，只暴露 `ON_SALE && APPROVED`。
- **决策**：发布落 `PENDING`（显式赋值，不依赖 DB 默认），即「发布后需运营审核才可见」。
  这是现有可见性规则的直接推论，也是最安全的默认。

### D4. 下架/上架的状态机
- 照抄 `SellerProductService.normalizeAction()` 范式：只认 `ON_SALE` / `OFF_SHELF`，
  不认识的动作 400（绝不静默当成下架）；用「幂等开关」（目标态==当前态则跳过写库）。
- 额外约束：`SOLD` 状态不可被卖家自行改回 `ON_SALE`（已卖出的闲置不该重新上架）。
- 未审核通过（PENDING/REJECTED）的闲置**允许下架**（卖家主动撤下是自己的权利），
  但**不允许上架**（会绕过审核）—— 上架要求 `auditStatus == APPROVED`。

### D5. `/api/orders/preview` 与 `@GetMapping("/{orderNo}")` 的路径竞争
- Spring 的 `PathPattern`/`AntPathMatcher` 精确段优先于变量段，`/preview` 是 POST 且 `/{orderNo}` 是 GET，
  方法不同本就不冲突；即使同方法，精确路径也优先。**仍实测确认**。

### D6. preview 的只读性
- 复刻 `placeOrder` 第 2~4.5 步（快照校验 / resolveReceiver / 按 seller 拆单 / 券计算），**不写任何表**。
- 券：`marketingGateway.lock()` 会写库（占券）→ preview **不能** lock。改为：
  调 `marketingGateway` 的**只读试算**能力；若不存在，则**只做本地金额计算 + 提示券在提交时校验**。
  → 先读 MarketingGateway，确认有无只读试算接口。

### D7. 405 修复
- `GlobalExceptionHandler` 补 `@ExceptionHandler(HttpRequestMethodNotSupportedException.class)` → 405。
- `ErrorCode` 无 `METHOD_NOT_ALLOWED` 枚举 → **新增** `METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, ...)`，
  与既有 `UNSUPPORTED_MEDIA_TYPE` 对称。照抄 `handleUnsupportedMediaType` 的注释论证。

## Wave 划分

- **Wave 1**（后端闲置发布）：ProductDtos + ListingService + ListingController
- **Wave 2**（网关白名单收紧）：GatewaySecurityProperties + application.yml + application-local.yml
- **Wave 3**（问题 2 · 405）：ErrorCode + GlobalExceptionHandler
- **Wave 4**（问题 1 · preview）：先读 MarketingGateway 定券口径 → OrderDtos + OrderService + OrderController
- **Wave 5**（构建 + 重建容器 + 接口冒烟）
- **Wave 6**（小程序：跳蚤市场页 + 发布表单 + 我的闲置 + api.js + app.json + 首页入口）
- **Wave 7**（验收脚本扩展 + 回归 + 文档 + 提交推送）

## 失败计数

### Wave 1–5 CHECKPOINT（后端完成 + 实测通过）
- 构建：`mvn -o -T 1C -DskipTests install` 六模块成功（27s）
- 容器：product / trade / marketing / gateway 四个重建，全 healthy
- 冒烟：`scripts/r52-listing-preview-smoke.py` → **PASS 57 / FAIL 0**
- 关键安全断言实测结果：
  - 匿名 `GET /api/listings/search` → 200（免登浏览保持）
  - 匿名 `POST /api/listings` → **401**（白名单收紧生效，写端点不再被连带放行）
  - 未实名发布 → 403；已实名发布 → 200 且落 `PENDING`
  - 传 `auditStatus=APPROVED` / `sellerId` 均被忽略（服务端说了算）
  - 未过审闲置：匿名详情 404、不在公开列表
  - 他人改我的闲置 403 vs 不存在 404（刻意区分）
  - 预览只读性：连调 3 次不产生订单、不占券（券仍 UNUSED）
  - **预览金额 == 真实下单金额**（同源口径验证通过）
  - 405 修复：4 个方法错配场景全 405，原先的 500 已消失

### 已知语义（非缺陷，已在冒烟中固化为断言）
- `GET /api/orders/preview` 会被 `@GetMapping("/{orderNo}")` 捕获成订单号 `preview` → 404。
  Spring 只在**方法相同**时才让精确路径优先；`POST /preview` 是独立映射，不受影响。
  已写入 M3 断言固定该契约，避免日后误判为回归。

### 探针自伤修正（3 处，非产品问题）
1. 实名端点写错：`/api/users/real-name` → 实际 `/api/users/me/realname`
2. 分页字段名写错：`records` → 本域实为 `items`（加了 `page_items()` 兼容两者）
3. URL 中文未 encode：`city=上海` 直拼致 `'ascii' codec can't encode` → 加 `urllib.parse.quote`

