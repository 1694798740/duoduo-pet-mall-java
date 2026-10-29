# 冲刺方案：备份、批量导入导出、未读状态

> 2026-10-27 · 对应最新冲刺指令的第一批交付物（方案先行，确认后开工）。
> 本文只解决“怎么做”，不含具体业务代码；落地时按模块拆 PR/轮次。

---

## 1. 服务器数据备份方案（第二块盘 + OSS）

### 1.1 目标

| 指标 | 目标 | 说明 |
|---|---|---|
| RPO | ≤ 24 小时（有条件可到 5–10 分钟） | 每日全量 + binlog 增量 |
| RTO | ≤ 2 小时 | 单库恢复到可用 |
| 保留 | 本地 7 日 + 异地 30 日 + 月度 12 份 | 本地快、异地防勒索/误删 |
| 覆盖 | MySQL、上传文件、`.env`、Nginx 配置 | 只备库不备附件，恢复后商品图全丢 |

### 1.2 分层选型（推荐组合）

1. **云主机整盘快照（每日 1 次，保留 7 天）**
   - 云厂商控制台/API 开启自动快照；成本低、整机可回滚。
   - 只防“整机级”事故，不能替代库级备份（快照回滚会同时回滚代码与配置）。
2. **MySQL 逻辑全量（每日 02:00）**
   - `mysqldump --single-transaction --quick --routines --triggers --events --set-gtid-purged=OFF`。
   - 单库一个 `.sql.gz`；`t_user` 等敏感表可加 `--where` 或整库加密。
   - 数据量继续增长（> 20GB 或备份窗口 > 30 分钟）时换 **Percona XtraBackup** 物理热备。
3. **binlog 增量（可选，RPO 5–10 分钟）**
   - 自建 MySQL：`mysqlbinlog` 定时拉取 + 归档；云数据库：开启 binlog 备份。
   - 恢复顺序：最近全量 → 全量之后的 binlog 重放。
4. **上传文件与配置**
   - `/data/uploads`、`/data/merchant-materials`、`docker-compose.yml`、`.env`、Nginx 配置。
   - 用 **restic** 做去重 + 加密 + 保留策略，仓库放 OSS（S3 兼容）。
5. **异地对象存储（OSS）**
   - 本地备份完成后再 `rclone copy` 或 `restic backup` 到 OSS；开启版本控制 + 生命周期（30 天转低频 / 90 天归档）。
   - OSS 使用独立 RAM 子账号，只给 `PutObject/GetObject/ListBucket`，AK 只放在服务器 `/etc/duoduo/backup.env`，权限 600。

### 1.3 目录与保留

```
/data/backup/
  mysql/2026-10-27/duoduo_*.sql.gz
  files/2026-10-27/uploads.tar.zst
  config/2026-10-27/config.tar.gz
  logs/backup-2026-10-27.log
```

- 本地保留 7 天；每周日的一份打 `weekly` 标记保留 4 周；每月 1 日保留 12 个月。
- 清理只删“带日期且超过保留期”的目录，不用 `rm -rf` 通配。

> 已提供可执行脚本：[scripts/backup/backup-all.sh](../../scripts/backup/backup-all.sh) 与
> [backup.env.example](../../scripts/backup/backup.env.example)；按 1.4 配置 cron 即可。

### 1.4 定时与失败告警

- `systemd timer` 或 `cron`：
  - `02:00` 全量备份（脚本 `scripts/backup/backup-all.sh`）
  - `02:30` 校验：`gzip -t`、dump 头部包含 `CREATE TABLE`、文件大小 > 阈值
  - `03:00` 上传 OSS；上传失败重试 3 次（指数退避）
- 告警：备份失败 / 文件过小 / OSS 上传失败 → 企业微信/飞书 Webhook（项目已有通知能力，可复用）。
- **每周一次恢复演练**：把最近备份恢复到临时库 `duoduo_restore_check`，跑行数比对（`t_order`、`t_user`、`t_spu`）与关键表 `CHECKSUM TABLE`，成功后删临时库。

### 1.5 恢复步骤（写进运维手册）

1. 停写入口（Nginx 维护页 / 停 gateway）。
2. 新建空库，`gunzip -c xxx.sql.gz | mysql`。
3. 如需追平：按时间点重放 binlog。
4. 恢复上传目录与 `.env`。
5. 起服务，跑 `scripts/run-all-smoke.ps1` 的最小集（order/product/payment）。
6. 核对订单数、支付单数、库存冻结台账。

### 1.6 不做什么

- 不用 Redis 做主数据备份（缓存可重建）。
- 不把备份放在同一块盘；不在容器内跑备份（容器重建会丢脚本与凭据）。
- 不把 AK/密码写进仓库或镜像。

---

## 2. 批量导入 / 导出方案

### 2.1 总体原则

- **模板先行**：后端生成模板（含表头、示例行、字段说明、下拉校验），前端直接下载。
- **前端负责体验，后端负责真相**：前端解析后先预览与行级校验；后端收到 JSON 必须**重新校验**（归属、枚举、必填、长度、重复）。
- **逐行结果**：部分成功不回滚；返回 `{total, successCount, failedCount, items:[{rowNo,id,success,errorCode,message}]}`。
- **导出按当前筛选**：前端把当前筛选条件传给后端，后端流式生成，不在前端拼全量数据。
- **权限与租户**：卖家只能导自己店；运营按入口权限；所有导入导出写审计（谁、何时、条件、条数）。

### 2.2 技术选型

| 场景 | 选型 | 理由 |
|---|---|---|
| 前端解析 xlsx/csv | `xlsx`（SheetJS） | 纯前端、模板预览快；大文件不占后端 |
| 后端 xlsx 读写 | **EasyExcel 4.x**（`com.alibaba:easyexcel`） | SAX 流式、低内存、API 简单；比 POI 少踩 OOM |
| 后端 CSV | Apache Commons CSV 1.11 | 引号/换行/编码处理成熟；也可手写但易错 |
| 导出文件流 | Spring `ResponseEntity<StreamingResponseBody>` 或 `HttpServletResponse` | 不把整表读进内存 |
| 异步大文件 | 现有 DB 选主锁 + 任务表（沿用项目调度风格） | 不引入 XXL-Job |

依赖（后端 `pom.xml`）：

```xml
<dependency>
  <groupId>com.alibaba</groupId>
  <artifactId>easyexcel</artifactId>
  <version>4.0.3</version>
</dependency>
<dependency>
  <groupId>org.apache.commons</groupId>
  <artifactId>commons-csv</artifactId>
  <version>1.11.0</version>
</dependency>
```

前端（`frontend/package.json`）：`"xlsx": "^0.18.5"`（仅管理端按需引入，避免进买家首屏）。

### 2.3 导入流程

```
下载模板 → 用户填表 → 前端 xlsx 解析为 JSON → 预览（前 20 行 + 错误标红）
  → 确认导入 → POST /api/{scope}/{module}/import {rows, batchKey}
  → 后端逐行校验 + 逐行入库（单行独立事务）→ 返回逐行结果
  → 前端展示成功/失败；失败行可导出 CSV 修正后重传
```

关键规则：

- 单次上限：同步 500 行；超过走异步任务（返回 `jobId`，前端轮询进度）。
- 幂等：`batchKey`（前端生成 UUID）+ 业务唯一键（如手机号、商品编码）双重防重。
- 错误码：`VALIDATION`（字段）、`DUPLICATE`（已存在）、`FORBIDDEN`（越权）、`BUSINESS_RULE`（状态不允许）。
- 文件安全：只接受 `.xlsx/.csv`，大小 ≤ 5MB；不执行宏；CSV 公式注入防护（以 `= + - @` 开头的单元格前加 `'`）。
- 权限：员工导入仅店主；商品导入仅卖家；入驻导入仅运营。

### 2.4 导出流程

```
前端带当前筛选（status/keyword/dateRange/sellerId）请求
  → 后端分页游标流式写 CSV/XLSX
  → Content-Disposition: attachment; filename*=UTF-8''...
  → 前端 Blob 下载
```

- CSV：UTF-8 BOM，Excel 直接打开不乱码。
- XLSX：EasyExcel `ExcelWriter`，每 1000 行 flush 一次。
- 最大行数：同步 50,000 行；超过提示缩小筛选或走异步导出（生成后站内通知下载链接）。
- 敏感字段：手机号/地址按角色脱敏；运营导出全量需审计。

### 2.5 各模块落地清单

| 模块 | 导入 | 导出 | 说明 |
|---|---|---|---|
| 商品管理 | ✅ 已有 CSV（A-17） | ✅ 已有 CSV | 可补 xlsx 模板；保持 CSV 兼容 |
| 员工管理 | ⬜ 待做 | ⬜ 待做 | 模板：手机号/姓名/权限；导入即发邀请，已存在成员跳过或更新权限（需拍板） |
| 入驻审核 | ⬜ 待做 | ⬜ 待做 | 只导入文本字段；营业执照等材料仍走上传，不进 Excel |
| 订单管理 | — | ⬜ 待做 | 卖家/运营按筛选导出；订单号、状态、金额、买家、时间 |
| 对账/结算 | — | ⬜ 待做 | 按账期导出账单；金额整数分，导出转元并保留两位 |
| 售后 | — | ⬜ 待做 | 按状态/时间导出，含处理结论 |

---

## 3. “预览卡片即已读”通用状态管理

### 3.1 数据模型（已有）

已读集合表：`(user_id, biz_id)` 复合主键 + `INSERT IGNORE`，例如
`t_order_read` / `t_after_sale_read` / `t_spu_read` / `t_admin_queue_read`。

- 列表接口**当前页一次 `IN` 查询**已读集合，逐条下发 `read`。
- 角标端点只对“可消状态”排除已读；持续提醒状态（待支付/待发货/待审核）不受影响。
- 排序：SQL 里 `ORDER BY (EXISTS(read)) ASC, 时间 DESC`，未读优先。

### 3.2 点击预览（前端伪代码）

```ts
function openPreview(item) {
  setPreview(item);                       // 先渲染预览，不等网络
  if (isRead(item)) return;               // 已读：不重复上报、不动角标

  const dismissible = RULES[module].shouldDismiss(item.status);
  markLocalRead(item.id);                 // 橙点立即消失
  if (dismissible) decrementLocal(item.status); // 角标乐观 -1

  try {
    const counts = await api.markRead(item.id);   // 服务端权威
    setCounts(toCountMap(counts));                // 覆盖本地，纠正并发
    emitBadgeChanged();                           // 父级菜单同刻收敛
  } catch {
    unmarkLocalRead(item.id);                     // 失败回滚橙点
    if (dismissible) await reloadCounts();        // 回读服务端，不硬减
  }
}
```

铁律：

1. **只有“打开预览/详情”这一个入口**上报已读，避免多个入口各写一遍。
2. 橙点消失与角标 -1 是两件事：橙点对所有状态消失；数字只对“可消状态”减。
3. 失败必须回滚，不做假账；下一次轮询用服务端值兜底。
4. 幂等：重复打开同一条不重复写库、不重复减。

### 3.3 批量操作（前端伪代码）

```ts
async function runBatch(ids, action) {
  const result = await api.batch(ids, action);
  const successIds = result.items.filter(i => i.success).map(i => i.id);

  markLocalRead(successIds);              // 成功条目的橙点消失
  if (result.counts?.length) {
    setCounts(toCountMap(result.counts)); // 服务端返回最新分桶，直接覆盖
  } else {
    applyBatchDismissLocally(successIds); // 兜底：只扣可消状态，且不低于 0
    reloadCounts();                       // 再回读一次纠正
  }

  clearSelection();                       // 批量栏收起、全选取消
  reloadList();
  emitBadgeChanged();
  showResult(result);                     // 成功/失败逐条列出
}
```

后端契约：

```java
// 不加整批事务：逐条独立，部分失败不回滚
for (id : ids) {
  try {
    doAction(id);                 // 复用单条状态机与权限校验
    markRead(userId, id);         // 成功才写已读
    items.add(ok(id));
  } catch (BizException e) {
    items.add(fail(id, e));       // 失败不写已读、不清橙点
  }
}
return Result(items, latestCounts()); // 最新分桶随响应返回
```

### 3.4 各模块现状与缺口

| 模块 | 橙点 | 预览/点击即读 | 批量即读 |
|---|---|---|---|
| 商家商品管理 | ✅ | ✅ 预览弹窗 | ✅ 批量上下架 |
| 商家订单 | ✅ | ✅ 预览弹窗 | ✅ 批量发货 |
| 商家售后 | ⬜ | ⬜ 目前只有处理弹窗 | ✅ 批量同意/拒绝 |
| 运营订单/售后/评价/举报/商品/内容审核 | ⬜ 目前只有导航角标 | ✅ 打开弹窗上报已读（部分） | ✅ 6 页已接 |
| 秒杀 / 员工 / 入驻审核 | ⬜ | ⬜ | ⬜ 待补 |

结论：**“预览即已读 + 批量即已读”的通用件已经具备**，剩余是把橙点与 `read` 字段补齐到售后与运营列表，以及给秒杀/员工/入驻补批量入口。

---

## 4. 本轮待确认事项

1. **“数据清理功能”具体指哪一个？**
   - A. 回收站自动清理（A-09）
   - B. 147 家历史夹具店铺清理（I-03）
   - C. 另有一个“数据清理”页面/接口/表，需要按名称删除
2. **多多币“完工”范围**：
   - A. 现有账户/抵扣/签到闭环收尾即可
   - B. 追加第三轮站内任务 + 节日活动
3. **导入导出首批范围**：
   - A. 商品 + 员工 + 订单 + 对账
   - B. 仅商品 + 员工
   - C. 全部（含入驻）
