# 多多宠物商店 · 运维与回滚手册

> 适用环境：本机 Docker（Windows + PowerShell 5.1），仓库 `D:\智能体项目\duoduo-pet-mall-java`
> 目标读者：需要在**不读源码**的前提下把系统起起来、改一个开关、备份/恢复数据、或在出事时回滚的人
> 配套：自测流程见 `docs/SELFTEST.md`；架构与不变式见 `docs/agent/03-架构与不变式.md`；命令速查见 `docs/PLAN.md` §7
> 本文受门禁保护：`scripts/lib/check-ops-doc.ps1` 会断言「文中出现的每个脚本路径都真实存在」且「本文的开关表与 `scripts/ops-switch.ps1` 的清单双向一致」

---

## 0. 先读这一页（运维的全部入口只有 4 个脚本）

出问题时的第一反应不应该是「翻源码」，而是按下面这张表拿结论。四个脚本都用**退出码**表达结果（`0` = 通过，非 `0` = 有问题）。

| 我想做的事 | 一条命令 | 退出码含义 |
|---|---|---|
| 看全栈现在是否正常 | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ops-status.ps1` | 0 = 必需项全 OK；1 = 有 FAIL |
| 看有哪些开关、现在是什么值 | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ops-switch.ps1` | 0 = 清单与实物四层一致 |
| 改一个运行期开关 | `... -File scripts\ops-switch.ps1 -Set NAME=VALUE` | 0 = 已写入 `.env` 并打印需重建的容器 |
| 做一次数据库备份 | `... -File scripts\db-backup.ps1 -BackupRoot <盘>` | 0 = 全绿并产出 manifest；1 = 有失败项且落 `_ALERT.txt` |
| 验证备份真能恢复 | `... -File scripts\ops-restore.ps1` | 0 = 全绿且逐表行数与备份那一刻一致；1 = 任一步失败 |
| 交付前跑全部门禁 | `... -File scripts\check-all.ps1` | 0 = 全绿 |

> ★ **为什么不写一篇"开关大全"文档，而要写 `ops-switch.ps1`**：本项目实测到过 **4 处"开关写了但不生效"**
> —— 代码里有字段、yml 里有 `${...}`，但 compose 没透传，于是在 `.env` 里改了、重启了、行为没变、**不报错也不告警**。
> 只写文档列举开关，等于自欺。所以判据做成**四层逐层核到实物**（见 §2）。

---

## 1. 三站点部署（商城 8088 / 后台 8090 / 商家 8091）

### 1.1 组成

前端是 monorepo，构建出**三个互相隔离的应用**，各带独立 nginx，跑在三个容器里。三端用**不同主机名**访问，因为登录 Cookie 按主机名隔离 —— 这正是「同一个浏览器能同时登录买家、后台运营、商家」的前提。

| 端 | 服务名（compose） | 容器名 | 站点地址 | 对应后端 |
|---|---|---|---|---|
| 买家商城 | `duoduo-mall-web` | `duoduo-web-mall` | `http://localhost:8088` | `/api/**`（经网关 8080） |
| 平台后台 | `duoduo-admin-web` | `duoduo-web-admin` | `http://admin.localhost:8090` | `/api/admin/**` |
| 商家中心 | `duoduo-merchant-web` | `duoduo-web-merchant` | `http://merchant.localhost:8091` | `/api/seller/**` |

> ★ **三端隔离的两道防线，别搞混**：
> ① **入口标记**（`DUODUO_ENTRY_SCOPE_ENABLED`）—— 网关只收窄 `/api/admin/**` 与 `/api/seller/**`，让「后台面/商家面」的接口只接受对应入口来的请求。它**挡误用、不挡恶意**（客户端可以自报入口）。
> ② **角色判权** —— 真正的防线。每条被拒的操作都配一个「有权者能过」的正对照，且**网关层与服务端层分别断言**。判权唯一权威是服务端的 `RolePermissionMatrix`。

### 1.2 一条命令起全套网页端

```powershell
bash scripts/web-up.sh
```

它会：构建三端产物 → 各打一个镜像（`frontend/Dockerfile` 的 `COPY apps/${APP}/dist` 保证**镜像里只有本端产物**）→ 起 3 个容器。改完前端代码要重新跑它。

### 1.3 只想起后端做接口验证

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-infra.ps1     # MySQL/Redis/Nacos/ES/MQ 等基础设施
# 需要 ES（A10 检索）或 MQ（A11 削峰）时带 profile：
docker compose --profile search --profile mq up -d
```

> 铁律 —— **改完代码必须 `mvn clean package` 再 build 镜像**（Dockerfile 不编译，直接吃 jar）；核验镜像内容要**嵌套解包** `BOOT-INF/lib/*.jar`。

### 1.4 起完之后先跑一次体检

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ops-status.ps1
```

它一屏给出：容器是否都健康、网关能否打到后端、三站点是否应答、MySQL 六库是否齐、Redis 是否通、开关是否四层一致、备份根是否已配。**退出码即结论。**

---

## 2. 入口开关（运行期可调项的唯一权威清单）

### 2.1 判据：一个开关要真能用，必须**四层逐层核到实物**

```
① 清单登记（scripts/ops-switch.ps1 的 $SwitchList）
        ↓ 必须
② compose 真透传（docker-compose.yml 里带 `# OPS-SWITCH` 标记、形如 ${NAME:-默认值}）
        ↓ 必须
③ 服务 yml 真读（<模块>/src/main/resources/application.yml 里有 ${NAME:...}）
        ↓ （起容器后）
④ 容器里真生效（docker exec <容器> printenv <NAME>）
```

`scripts/ops-switch.ps1 -Check` 对 ①②③ 逐项断言，并做**反向**校验（compose 里标了标记的每一项都必须登记；`.env` 里每一项都必须被 compose 引用过，否则改了不生效）。它还会**自证解析器本身有效**（标记数为 0 ⇒ 报红，而不是"没有开关"）。可进 CI，不依赖 Docker。

### 2.2 怎么改一个开关

```powershell
# 1) 写入 .env（可一次传多个 -Set，脚本会拒绝写"不被 compose 引用"的项）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ops-switch.ps1 -Set DUODUO_API_METRICS_ENABLED=false

# 2) 按它打印的提示重建**对应**容器（改环境变量只需重建容器，不必重新打镜像）
docker compose up -d --force-recreate --no-deps duoduo-gateway-app

# 3) 复跑确认容器里真的生效
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ops-switch.ps1
```

### 2.3 开关清单

下表由 `scripts/ops-switch.ps1` 的 `$SwitchList` 渲染而来；门禁 `scripts/lib/check-ops-doc.ps1` 保证两者**双向一致**（改了一处忘了另一处会报红）。

<!-- OPS-SWITCH-TABLE-BEGIN -->

| 开关 | 归属服务 | 类别 | 默认 | 打开 / 有值 | 关掉 / 置空 |
|---|---|---|---|---|---|
| `DUODUO_PERM_VERSION_ENABLED` | gateway | 开关 | `true` | 改完角色下一个请求即生效（E17） | 退回旧行为：角色变更要等令牌过期（≤15 分钟）。授权判定本身不受影响 |
| `DUODUO_ENTRY_SCOPE_ENABLED` | gateway | 开关 | `true` | 后台面 / 商家面接口只接受对应入口（E8） | ★ 三端**入口隔离失效**，仅排障时临时关 |
| `DUODUO_API_METRICS_ENABLED` | gateway | 开关 | `true` | 逐日记「带店铺身份的请求」的调用数与 4xx/5xx（E24 面板数据源） | ★ 面板「接口错误率」恒为 0，且**不报错**（静默失效） |
| `DUODUO_ADMIN_IP_WHITELIST_ENABLED` | gateway | 开关 | `false` | 只允许名单内来源访问 `/api/admin/**`（E21） | 后台对任意来源开放（当前默认） |
| `DUODUO_ADMIN_IP_WHITELIST` | gateway | 名单 | *(空)* | 逗号分隔：精确地址 / CIDR / 前缀通配 | ★ 空名单 + 上一条打开 = **拒绝全部后台访问**（失败关闭，刻意如此） |
| `DUODUO_SEARCH_ENABLED` | product | 开关 | `true` | 搜索走 ES 优先，失败自动降级 MySQL（A10） | 直接走 MySQL（ES 不参与） |
| `DUODUO_TRADE_ORDER_MODE` | trade | 模式 | `sync` | `queue` = 下单走削峰队列（需先起 mq profile） | `sync` = 同步落库（CI/本地默认，零 MQ 依赖） |
| `DUODUO_TRADE_PLACE_QPS` | trade | 阈值 | `100` | 下单入口 QPS 上限（Sentinel 限流） | 调 0 等于封死下单入口 |
| `DUODUO_WECHAT_MOCK_ENABLED` | user | 开关 | `true` | 微信登录走 mock 通道（本地/CI）（A13） | 走真实微信通道（需注入 `WECHAT_APPID` / `WECHAT_SECRET`），否则微信登录不可用 |
| `DUODUO_AI_MOCK_ENABLED` | ai | 开关 | `true` | AI 导购走本地 mock 回复 | 调用真实大模型（需注入 `DASHSCOPE_API_KEY`），否则 AI 导购不可用 |
| `DUODUO_AI_MODEL` | ai | 取值 | `qwen-plus` | AI 导购使用的大模型名 | 换成别的模型名即切换模型（改完重建 ai 容器） |
| `DUODUO_AI_RATE_LIMIT` | ai | 阈值 | `20` | 单用户每分钟 AI 调用上限 | 调大 = 更容易被单个用户刷满 |
| `DUODUO_AI_GLOBAL_RATE_LIMIT` | ai | 阈值 | `300` | 全站每分钟 AI 调用总上限（防换 IP 刷） | 调 0 = AI 入口整体不可用 |
| `DUODUO_AI_RECALL_READ_TIMEOUT_MS` | ai | 阈值 | `1500` | 商品召回跨域读超时（毫秒），超时降级为无召回答复 | 调小 = 更早降级；调大 = 更易拿到结果但更慢 |
| `DUODUO_AI_TIMEOUT_SECONDS` | ai | 阈值 | `15` | 单次 AI 对话超时秒数，超时按 DEGRADED(503) 返回 | 调大 = 更易拿到长回复但占用线程更久 |

<!-- OPS-SWITCH-TABLE-END -->

> ★ `DUODUO_BACKUP_ROOT` 不在上表里 —— 它不是"运行期开关"，而是备份脚本的**输入参数**（放哪个盘由老板拍板，见 §3）。

---

## 3. 备份与恢复演练

### 3.1 为什么"有备份"和"能恢复"是两件事

「我们有备份」只需要文件存在；「能把系统恢复起来」需要：产物能被 mysql 吃进去、字符集对、表依赖顺序对、恢复出来的内容与备份那一刻一致。**这些只有真跑一次才知道。** 而"真跑一次"如果不带判据，就又退化成「命令没报错 = 成功了」。

所以 S4 拆成**两个脚本 + 一个共用判据**：

| 脚本 | 干什么 | 判据 |
|---|---|---|
| `scripts/db-backup.ps1` | 六库 `mysqldump` → 产物自证 → 从产物切片抽验 → 滚动保留 → 失败告警 | ① **声明过的每一张表**都得在产物里找到（declared-vs-present）；② 抽验用「从产物切表恢复进临时库 vs 源表」做**三方比对** |
| `scripts/ops-restore.ps1` | 把一份备份**真的恢复一次**（恢复进 `zz_restore_*` 临时库，验完自动删） | ① 产物文件 **SHA256** 与 manifest 一致；② **逐表行数**与 manifest 的 `rowCounts` 快照逐张一致 |

两者共用同一个 `rowCounts` 快照：**备份侧写下它、恢复侧核对它**。`scripts/db-backup-register.ps1` 负责把备份注册成每天 03:00 的计划任务（刻意排在 03:30 对账选主之前），并在注册后**立刻试跑一次并回读结果**。

### 3.2 跑一次备份

```powershell
# -BackupRoot 必须给一个真实存在的目录！
#   脚本顶部的 $DefaultBackupRoot 只是**占位默认值**，不是已定方案 —— 备份盘位置待老板拍板。
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\db-backup.ps1 -BackupRoot 'D:\duoduo-backup'

# 产物：<BackupRoot>\runs\<yyyy-MM-dd_HHmmss>\
#         duoduo_user.sql … duoduo_marketing.sql   六库 dump
#         manifest.json                            指纹（含 rowCounts 行数快照 + 每份 dump 的 sha256）
#         backup.log / _ALERT.txt(仅失败时)
# 退出码 0 = 全绿；1 = 有失败项。加 -AlertWebhook <url> 可推飞书/钉钉。
```

注册成每天自动备份：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\db-backup-register.ps1 -BackupRoot 'D:\duoduo-backup'
# 撤掉：Unregister-ScheduledTask -TaskName DuoduoDbBackup -Confirm:$false
```

### 3.3 恢复演练（验证这份备份真能用）

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ops-restore.ps1
# 用最近一份；也可 -BackupDir <run目录> 指定某一份； -Keep 保留临时库供人工比对
```

判据自证纪律（这几条都是踩过坑加的，别去掉）：

- manifest 里**没有 `rowCounts`** ⇒ **拒绝出结论**，而不是降级成「恢复命令没报错 = 成功」；
- `status != OK` 的备份 ⇒ **拒绝用它做演练**（一份自己都认为失败的备份不该被用来证明恢复没问题）；
- **比对了 0 张表** ⇒ **判失败**（「空结果」与「链路没接通」长得一样）；
- **刻意不比整表 `CHECKSUM TABLE`** —— 跨时刻比不成立：拿"恢复库 vs 产物"比是**恒等式**；拿"恢复库 vs dump 前源表"比时，心跳表 `t_sched_leader`（每 2 分钟自改）**必然不等**，会让每次演练都假红。正确做法在 db-backup 的抽验里（那里有独立时间点，做三方比对，判据成立）。

### 3.4 一次演练通过长什么样

演练结论行只有两种，看它即可：

```
 演练结论：全绿 —— 这份备份可以恢复，且内容与备份那一刻逐表一致。
 演练结论：失败 N 处 —— 这份备份不可信，请先查清再依赖它。
```

### 3.5 真正出事时的恢复（人工，逐步照做）

1. **先停写**：`bash scripts/web-up.sh` 起的三个站点容器停掉，或把网关摘掉流量，避免恢复期间继续写入。
2. **定位备份**：`<BackupRoot>\runs\<时间戳>\`，**先看 `manifest.json` 的 `status` 是否为 `OK`**。
3. **先演练**：`scripts\ops-restore.ps1 -BackupDir <该目录>` —— 全绿再往下走。**不要跳过这步直接覆盖生产库。**
4. **导回生产库**（`<db>` 为 `duoduo_user` 等六库之一）：
   ```powershell
   docker cp "<run目录>\<db>.sql" duoduo-mysql:/tmp/<db>.sql
   docker exec -i duoduo-mysql sh -c "mysql -uroot -pduoduo_root --default-character-set=utf8mb4 <db> < /tmp/<db>.sql"
   ```
5. **回读确认**：对每个库跑一次逐表 `COUNT(*)`，与 manifest 的 `rowCounts` 比。
6. **恢复服务**：重新起三站点与网关，跑 `scripts\ops-status.ps1` 与 `scripts\check-all.ps1`。

> 备份/恢复的**离线兜底**（不含 Docker 的纯文件级搬运）见 `scripts/backup-offline.sh`。

---

## 4. 数据清理与回滚

### 4.1 数据清理的边界（口径先定，再动手）

清理是**不可逆**操作。本项目的口径：

- 六库之间**无物理外键**（`information_schema.referential_constraints = 0`），所以可以安全 `TRUNCATE` 单表；
- 清理**只清业务数据、不动基础主数据**（SPU/SKU/类目/品牌、券模板、秒杀活动、话题字典、调度锁表、平台主运营账号本体）；
- 清理脚本**逐表执行并记录影响行数**，删完**双向复核**：目标表必须为 0，保留表必须与执行前一致。

已归档的清理脚本：

| 脚本 | 用途 |
|---|---|
| `scripts/e26-clean-all-accounts.sql` | E26 全部删号（保留平台主运营账号 + 基础主数据） |
| `scripts/t2-clean-third-party.sql` | 只清第三方（非自营）业务数据 |
| `scripts/t2b-clean-test-accounts.sql` | 只清测试期账号（只降权/删号，保留其它数据） |

> ★ **动手前必须先有备份**：清理的整个前提就是"库坏了能从备份恢复"。所以顺序永远是
> **先 `db-backup.ps1` → 再 `ops-restore.ps1` 演练确认这份备份可用 → 才动清理脚本**。
> 清理脚本的头部注释里必须写清「前置：已全量备份并抽验通过」。

### 4.2 回滚（代码 / 配置 / 数据，三种不同的回滚）

| 回滚对象 | 怎么做 | 注意事项 |
|---|---|---|
| **代码** | `git revert <commit>` 或 `git checkout <上一个好提交>` → `mvn clean package` → 重建镜像 | 不许 `git push --force` 覆盖别人的提交；回滚也要重建镜像，否则跑的还是旧 jar |
| **运行期开关** | `scripts\ops-switch.ps1 -Set NAME=<原值>` → 重建对应容器 | 只改环境变量**不必**重新打镜像；改完必须复跑 `ops-switch.ps1`（不带 `-Check`）确认容器里真生效 |
| **数据** | 按 §3.5 从备份恢复 | **先演练再覆盖**；恢复期间必须停写 |

### 4.3 出事时的第一分钟：先拿事实，不要先猜

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ops-status.ps1      # 全栈现在什么状态
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ops-switch.ps1      # 开关是不是被人改过
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\check-all.ps1       # 静态门禁 + 核心冒烟
```

三条都看一眼再决定动手方向。**判定一律看退出码，不要靠读中文输出**（PS 5.1 代码页不对时汇总行会变成 `NO-RESULT`，明明全过却显示没结果）。

---

## 附：本文档的自我保护

本文受 `scripts/lib/check-ops-doc.ps1` 门禁：

1. **文中出现的每个 `scripts/...` 路径都必须真实存在**（防文档里写一个早已删除的脚本）；
2. **§2.3 开关表（标记块内）与 `scripts/ops-switch.ps1` 的清单双向一致**（少一个、多一个都报红）。

改开关清单的标准动作：**先改 `ops-switch.ps1` 的 `$SwitchList` → 再同步本文 §2.3 的表 → 跑 `check-ops-doc.ps1`**。
