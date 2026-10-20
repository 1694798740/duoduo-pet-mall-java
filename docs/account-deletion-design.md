# C7 · 账号注销功能设计与实施记录

> 日期：2026-10-20　｜　范围：用户域（duoduo-user）+ 交易 / 履约 / 营销 / 社交 / 商品五域 + 小程序 + 网页商城
> 状态：**本轮实施中**。本文档是设计依据与交付说明，代码改动以 git 提交为准。

---

## 一、为什么必须做（合规前提）

《用户隐私保护指引》4.2 已向用户承诺「可申请注销账号，十五个工作日内完成核查和处理」。
2026-10-20 实测：小程序个人中心**只有「退出登录」、无注销入口**，后端 `duoduo-user`
**无注销接口**，`t_user` 也**无任何删除/软删标记**——承诺与实现不一致，属合规欠账。

同时，微信平台规范要求小程序提供账号注销途径；《电子商务法》要求交易信息自交易完成之日起
保存不少于三年。两者叠加决定了本功能的设计基调：

> **个人数据可删、交易与审计记录必须留**——因此采用「软注销 + 匿名化」，不做物理删号。

---

## 二、现状调研结论（均以代码位置为证，非推断）

| 项 | 结论 |
| --- | --- |
| 用户状态字段 | `t_user.status` 仅 `ACTIVE` / `BANNED`，且 **BANNED 全仓库只读不写**（`AuthService` L180/L227/L291/L579 只判断不设置） |
| 删除标记 | `t_user` **无** `deleted` / `deleted_at`；全项目无统一软删约定（仅分享圈 `t_share_comment.deleted_at` 一例） |
| 令牌形态 | access = JWT 无状态（15min）；refresh = DB `t_refresh_token` 只存 SHA-256 哈希（7 天，可轮转吊销）；浏览器走 httpOnly Cookie，小程序走 storage 两个 key |
| 登出现状 | 网页端调 `POST /api/auth/logout`；**小程序「退出登录」只清本地 storage，从不通知后端**（`profile.js` L109-113） |
| 订单状态 | 6 态，`isFinal() = COMPLETED / REFUNDED / CLOSED`（`OrderStatus.java` L32-87） |
| 售后状态 | 7 态，终态 `REFUNDED` / `CLOSED`（`AfterSaleStatus.java` L45-78） |
| 商家身份 | `t_merchant.owner_user_id`（唯一索引）+ `t_merchant_member.user_id`（唯一索引，一个用户至多属于一个商家）——**注销店主账号会牵连店铺主体** |
| 跨模块机制 | Feign + `X-Internal-Token` 签名（`InternalCallAuth`），各域 `InternalTokenFilter` **显式列举**受保护前缀 |
| 调度机制 | `SchedLeaderLock` 选主 + `@Scheduled`，模板见 `CouponReleaseJob` |
| 建表脚本 | 权威源 `docs/sql/*.sql`（compose 挂载），增量在 `docs/sql/incremental/`，模块内 `resources/db/schema.sql` 为同源副本 |

---

## 三、状态机与字段

### 3.1 `t_user` 新增四列（增量 `36-c7-account-deletion.sql`）

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `account_status` | `VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'` | `ACTIVE` / `CANCELLING`（冷静期）/ `DELETED`（已注销）。与既有 `status`（ACTIVE/BANNED）正交：`status` 管封禁，`account_status` 管注销 |
| `delete_requested_at` | `TIMESTAMP NULL` | 用户提交注销申请的 UTC 时间 |
| `delete_scheduled_at` | `TIMESTAMP NULL` | 冷静期截止时间（= 申请时间 + `grace-days`，默认 7 天） |
| `delete_reason` | `VARCHAR(200) NULL` | 用户填写的注销原因（可选，用于产品改进） |
| `delete_executed_at` | `TIMESTAMP NULL` | 实际执行注销的 UTC 时间 |

索引：`KEY idx_user_account_status (account_status, delete_scheduled_at)`（供调度扫描）。

### 3.2 状态流转

```
               申请注销（密码校验 + 预检通过）
   ACTIVE ──────────────────────────────────► CANCELLING
     ▲                                          │
     │        撤销（手机号 + 密码，无需登录态）    │  delete_scheduled_at 到期
     └──────────────────────────────────────────┤
                                                ▼
                             执行（先跨模块清理，全部成功后才落 DELETED）
                                          DELETED
```

**关键顺序**：先把五域的个人数据清完，**全部成功**后才把 `t_user` 置 `DELETED`。
任何一步失败 → 保持 `CANCELLING` 并记录 `delete_error`，下一轮调度重试。
这样 `t_user.account_status` 是唯一的总开关，天然幂等、可重入、可断点恢复。

---

## 四、接口契约

### 4.1 用户侧（需登录态，走网关鉴权）

| 方法 | 路径 | 请求体 | 响应 | 说明 |
| --- | --- | --- | --- | --- |
| POST | `/api/users/me/delete-account` | `{password, reason?}` | `DeleteAccountView{scheduledAt, graceDays}` | 提交注销。**密码必须校验**（防误操作 + 防盗用会话）。预检不通过返回 **409 `ACCOUNT_DELETE_BLOCKED`** + `blockedItems[]` |
| DELETE | `/api/users/me/delete-account` | `{password}` | `{}` | 撤销注销申请，恢复 `ACTIVE`。**同时不需登录态也可走 4.2** |
| GET | `/api/users/me/delete-account` | — | `DeleteAccountView\|null` | 查询当前注销状态（未申请返回 null） |

### 4.2 撤销专用（**无需登录态**）

| 方法 | 路径 | 请求体 | 响应 | 说明 |
| --- | --- | --- | --- | --- |
| POST | `/api/auth/account-deletion/cancel` | `{phone, password}` | `{}` | 冷静期内账号已禁止登录，故撤销不依赖登录态，改用「手机号 + 密码」重新验证身份。仅对 `CANCELLING` 生效；`ACTIVE` 返回 409「账号未在注销流程中」 |

### 4.3 登录态拦截

`login` / `refresh` / `wechatLogin` 三处，在既有 `BANNED` 判断旁边新增：

```java
if ("CANCELLING".equals(entity.getAccountStatus())) {
    throw BizException.businessRule("账号正在注销冷静期内，登录已停用；如需恢复请使用「撤销注销」");
}
```

错误码统一 `ACCOUNT_DELETING`，端侧据此跳转撤销页而非普通登录页。

### 4.4 服务间（`X-Internal-Token` 签名，幂等可重入）

统一形态：`POST /api/<domain>/internal/users/{userId}/personal-data`，无 body，
响应 `ApiResponse<PersonalDataCleanupResult>`（`record PersonalDataCleanupResult(Long userId, String domain, int deleted, int masked)`）。

| 域 | 路径 | 动作 |
| --- | --- | --- |
| 交易 | `/api/orders/internal/users/{userId}/personal-data` | 删 `t_cart_item`；脱敏 `t_order` / `t_seckill_order` 的 `receiver_name` / `receiver_phone` |
| 履约 | `/api/aftersales/internal/users/{userId}/personal-data` | 删本人分享互动（`t_product_share` / `t_share_comment` / `t_share_like` / `t_share_favorite`）；`t_review` 保留（展示层已有「已注销用户」兜底） |
| 营销 | `/api/coupons/internal/users/{userId}/personal-data` | 删未使用 / 已锁定 / 已过期的 `t_coupon`；已核销的保留（订单侧已有留档） |
| 社交 | `/api/socials/internal/users/{userId}/personal-data` | 删 `t_social_follow`（关注与被关注双向）、`t_social_message`（收发）、`t_social_notice_read` |
| 商品 | `/api/listings/internal/users/{userId}/personal-data` | 删已下架的 `t_listing`（在售的会被阻塞预检挡掉，不会走到执行） |

每域还需把新前缀登记进本域 `InternalTokenFilter` 的列举列表（**显式列举，不用宽松匹配**，与既有约定一致）。

---

## 五、数据清理矩阵（执行阶段）

| 数据 | 动作 | 理由 |
| --- | --- | --- |
| `t_user` 行 | **保留**，`account_status=DELETED`；`phone→'x'+id`（释放唯一索引，允许同号再注册。★ 前缀必须单字符：`phone` 是 VARCHAR(20)，雪花 id 最长 19 位，`del_` 这类前缀会溢出；实现见 `AccountDeletionService.DELETED_PHONE_PREFIX`）；`nickname→'已注销用户'`；`avatar` / `real_name` / `id_card_masked` / `wx_openid` / `totp_secret` 置 `NULL`；`password_hash` 置随机不可用值 | 交易与审计记录要按 `user_id` 关联，不能物理删行；PII 必须清除 |
| `t_refresh_token` | 全部吊销（`revokeAllByUser`） | 立即掐断所有端的长会话 |
| `t_user_address` / `t_pet_profile` / `t_totp_recovery_code` | **物理删除** | 纯个人主动数据，无留存义务 |
| `t_merchant_member` | 置 `DISABLED` | ACTIVE 成员身份会被阻塞预检挡下；此处兜底历史数据 |
| 跨模块见 4.4 | 删除 / 脱敏 | 个人数据原则上删，交易关联数据脱敏 |
| `t_order` / `t_payment` / `t_after_sale` / `t_review` / `t_audit_log` | **保留** | 《电子商务法》交易信息留存 ≥3 年；审计表只追加不删 |

---

## 六、边界场景与处理

| 场景 | 处理 |
| --- | --- |
| **重复注销** | ★ **以实现为准：幂等而非报错**（2026-10-20 订正，原文档写的是 409）。`DELETED` → 409「账号已注销」；`CANCELLING` 再申请 → **200 并返回当前冷静期到期时间**，不报错。理由：用户刷新页面、或两处端同时点，返回"当前状态"比抛"请勿重复提交"更有用，且不会触发第二次执行（CAS 只接受 `ACTIVE→CANCELLING`）。`DELETED` 的手机号已释放，再登录走注册 |
| **注销中的订单 / 退款** | **阻塞**：存在任一非终态订单（`PENDING_PAYMENT` / `PAID` / `SHIPPED`）或进行中售后 → 409 并列出「订单号 + 状态」清单，用户需先完成或取消 |
| **商家身份** | **阻塞**：是店主（`t_merchant.owner_user_id`）或 ACTIVE 店员 → 提示先转让店铺 / 退出店铺；避免注销后店铺失去主体 |
| **在售闲置** | **阻塞**：`t_listing` 存在在售 → 提示先下架 |
| **多端登录态** | 申请成功即吊销全部 refresh 令牌；access 令牌 15 分钟自然过期；小程序 storage 由端侧 `clearSession` 清理；网页 Cookie 由注销接口响应清除 |
| **数据保留与删除** | 见第五节矩阵：个人数据删 / 交易数据脱敏留 / 审计留 |
| **异常中断恢复** | 执行顺序为「五域清理 → 全部成功 → 置 `DELETED`」；任一域失败保持 `CANCELLING` 并记 `delete_error`，下一轮调度重试；每步动作幂等（DELETE/UPDATE 可重复执行），重复跑不会二次破坏 |
| **撤销竞态** | 撤销与调度执行可能同时发生：两者都以 `account_status` 的 CAS 更新为前提（撤销只接受 `CANCELLING→ACTIVE`，执行只接受 `CANCELLING→DELETED`），后到者更新 0 行即视为对方已处理 |
| **权限** | 申请 / 撤销 / 查询均只允许本人；管理员无代注销端点（不可逆操作不走他人之手） |
| **防爆破** | 撤销端点暴露「手机号 + 密码」组合，与登录同一口径的错误提示（不区分账号不存在与密码错误） |

---

## 七、影响范围

| 层 | 文件 / 模块 | 改动 |
| --- | --- | --- |
| DB | `docs/sql/incremental/36-c7-account-deletion.sql`、`docs/sql/01-duoduo-user.sql`、`duoduo-user/src/main/resources/db/schema.sql` | `t_user` 加 5 列 + 1 索引 |
| 用户域 | `entity/UserEntity`、`dto/UserDtos`、`service/AccountDeletionService`（新增）、`service/AuthService`（登录三处拦截 + 撤销）、`controller/UserController` + `AuthController`（端点）、`service/AccountDeletionJob`（新增调度） | 核心 |
| 五域 | trade / fulfilment / marketing / social / product 各加 1 个 internal 端点 + service 方法 + `InternalTokenFilter` 前缀登记 | 数据清理 |
| 用户域 | `client/*Client`（5 个 Feign）+ `config/FeignInternalConfig`（若无则新增） | 编排调用 |
| 小程序 | `pages/account-delete/*`（新页面四件套）、`pages/profile/profile.js|wxml`（入口）、`utils/api.js`、`app.json` | 端侧 |
| 网页商城 | `frontend/apps/mall/src/pages/Me.tsx`（入口 + 弹窗）、`api-sdk` 的 `endpoints.ts` / `types.ts` | 端侧 |
| 测试 | `AccountDeletionServiceTest`（单测）、`scripts/account-deletion-smoke.ps1`（冒烟） | 验证 |

---

## 八、测试用例清单

**单元测试（`AccountDeletionServiceTest`）**

1. 申请注销：密码错误 → 401，不进入冷静期
2. 申请注销：密码正确 → `CANCELLING`，`delete_scheduled_at = now + 7d`，refresh 令牌全部吊销
3. 申请注销：有未终态订单 → 409 `ACCOUNT_DELETE_BLOCKED`，`blockedItems` 含订单号
4. 申请注销：是店主 → 409，提示转让店铺
5. 申请注销：有在售闲置 → 409
6. 重复申请：`CANCELLING` 再申请 → **200 幂等返回当前到期时间**（不报错，见第六节订正）；`DELETED` 再申请 → 409
7. 撤销：手机号或密码错误 → 401；账号 `ACTIVE` → 409「未在注销流程中」；`CANCELLING` → 恢复 `ACTIVE` 且令牌可重新签发
8. 执行：`t_user` PII 清空、`phone` 改写、`account_status=DELETED`
9. 执行：五域清理任一抛异常 → 保持 `CANCELLING`、记 `delete_error`、不落 `DELETED`（可重入）
10. 执行：重复执行第二次 → 幂等，不报错、不二次破坏
11. 登录拦截：`CANCELLING` 账号密码登录 / 微信登录 / refresh 均被拒
12. `DELETED` 账号：手机号可重新注册

**冒烟（`account-deletion-smoke.ps1`，绕过页面直打接口）**

13. 注册新用户 → 申请注销 → 登录被拒（`ACCOUNT_DELETING`）
14. 手机号 + 密码撤销 → 登录成功
15. 再次申请 → 手动把 `delete_scheduled_at` 改到过去 → 触发 Job → `account_status=DELETED`
16. 校验 `t_user` 手机号已改写、地址 / 购物车 / 宠物档案已删、订单仍在但收货人已脱敏
17. 不带 `X-Internal-Token` 调 5 个 internal 端点 → 全部 403

**端侧（真机 / 开发者工具）**

18. 小程序「我的 → 账号注销」入口可见；弹窗展示阻塞项与冷静期说明
19. 撤销入口可用（手机号 + 密码）
20. 网页商城个人中心同步可用

**冒烟脚本映射（`scripts/account-deletion-smoke.ps1`，共 12 项）**

| # | 用例 | 判据 |
| --- | --- | --- |
| 1 | 注册专用账号 | `POST /api/auth/register` 返回 accessToken |
| 2 | 预检干净账号 | `blocked=false` |
| 3 | 密码错误提交 | 被拒，且不进入冷静期 |
| 4 | 正常提交 | `accountStatus=CANCELLING` 且返回 `scheduledAt` |
| 5 | 冷静期登录 | 被拒，`code=ACCOUNT_DELETING` |
| 6 | 重复提交 | 被拒（状态机只接受 `ACTIVE`） |
| 7 | 撤销（手机号 + 密码） | 成功，无需登录态 |
| 8 | 撤销后登录 | 恢复成功 |
| 9 | 对未注销账号撤销 | 409 |
| 10 | 内部端点越权 | 不带 `X-Internal-Token` → 401/403 |

> 脚本**刻意不真正执行注销**（不把 `delete_scheduled_at` 改到过去）：执行态会改写 PII，
> 属不可逆操作，留在人工确认的第 15 项用例里做。

---

## 九、本轮实施记录（2026-10-20 端侧补齐）

后端（`AccountDeletionService` / `AccountDeletionJob` / 五域 `PersonalDataCleanup*` /
三个端点）此前已落地但**未提交**，本轮补齐的是「让功能真的能被用户用到」的部分：

| 缺口 | 处理 |
| --- | --- |
| `docs/sql/01-duoduo-user.sql`、`resources/db/schema.sql` 缺 5 列 | 已按增量 36 同步（新装环境否则启动即报未知列）；并已对运行库 `duoduo_user` 应用 ALTER |
| 文档与实现不一致 | ★ 增量脚本与设计文档都写着"phone 改写为 `del_<id>`"，而实现用 `x<id>` —— `phone` 是 `VARCHAR(20)`，雪花 id 最长 19 位，`del_` 会**溢出并在 MySQL 严格模式下报错**。以实现为准，文档已订正 |
| 小程序无入口 | 新增 `pages/account-delete/*` 四件套 + `profile` 页入口 + `app.json` 注册 + `api.js` 的 `accountDeletion` 四方法 |
| 小程序 `del` 不支持请求体 | `request.js` 的 `del(path, opts)` 改为 `del(path, data, opts)`（既有 4 处调用只传 path，行为不变） |
| 网页商城无入口 | `api-sdk` 新增 `userApi.deleteAccount.{precheck,request,cancel,cancelByPhone}` + 三个类型；`Me.tsx` 新增 `DeleteAccountPanel`（预检 → 密码确认 → 提交 / 撤销） |
| 网页 SDK `del` 不带 body | `client.ts` 的 `del` 对齐 `post/put`，支持 body |
| 无冒烟 | 新增 `scripts/account-deletion-smoke.ps1`（12 项） |

**仍未做（需人工介入，非阻断）**：真正执行注销的第 15 项用例（改写 PII 不可逆），
以及五域 `InternalTokenFilter` 的前缀登记复核。
