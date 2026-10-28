# 腾讯云 COS 配置说明（开发者对接版）

> 适用版本：2026-10 当前主干（提交 `441c9e5` 之后）。
> 读者：需要接入上传、图片展示、资质材料或排查存储问题的开发者。
> 结论先行：本项目用**两个 COS 桶**——公开读桶放商品/评价/售后/聊天媒体，私有桶放入驻资质；
> 两者共用**同一个子账号密钥**，通过环境变量注入，代码零 SDK、自签 HMAC-SHA1。
> **任何情况下不要把私有桶指向公开桶，也不要给应用密钥桶级 `cos:*` 权限。**

---

## 1. COS 在本项目中的用途

| 数据类型 | 存放桶 | 访问方式 | 典型业务 |
|---|---|---|---|
| 商品图（主图/详情图/富文本内图） | **公有桶** | 公网直读 | 商城首页、搜索、详情、商家端 |
| 评价晒图、售后举证图 | **公有桶** | 公网直读 | 评价区、售后详情 |
| 客服/私信图片、视频、语音 | **公有桶** | 公网直读 | Web 聊天、小程序聊天 |
| 用户头像（上传后） | **公有桶** | 公网直读 | 个人中心、评价昵称旁 |
| 商家入驻资质（营业执照、法人身份证、银行账户、许可证 PDF） | **私有桶** | **只能经鉴权接口读取** | 商家入驻、运营审核、租户健康度 |
| 数据库备份（运维，2026-10-28） | **私有桶** | 仅运维脚本 / 控制台，**不经任何应用接口** | 每日六库 dump（`scripts/db-backup-cos.sh`） |

**为什么必须两个桶**：公开图要求匿名可读（`<img>` 发不出鉴权头），资质材料要求匿名绝不可读。
这两件事在同一条权限策略里无法安全共存，因此拆成两个桶、两个 Bean、两套根目录。
代码里用 `@Qualifier("publicObjectStorage")` / `@Qualifier("privateObjectStorage")` 区分，不靠参数切换。

---

## 2. 整体集成方式

### 2.1 链路

```
上传（公开）
  浏览器/小程序 ──POST /api/uploads──▶ 网关 ──▶ duoduo-fulfilment
                                              └─ publicObjectStorage.put("uploads/<uuid>.<ext>")
                                                     └─ PUT https://<公有桶>.cos.<region>.myqcloud.com/uploads/<uuid>.<ext>

读取（公开）
  浏览器/小程序 ──GET https://<公有桶>.cos.<region>.myqcloud.com/uploads/<uuid>.<ext>[?imageMogr2/...]
  （本地盘模式回落为 GET /uploads/<uuid>.<ext>，由 fulfilment 读本地卷）

上传（私有）
  商家端 ──POST /api/merchant-materials──▶ 网关 ──▶ duoduo-user
                                              └─ privateObjectStorage.put("materials/<merchantId>/<uuid>.<ext>")

读取（私有）
  运营/商家 ──GET /api/merchant-materials/<merchantId>/<uuid>.<ext>──▶ duoduo-user（鉴权 + 归属校验）
                                              └─ privateObjectStorage.get(...)  → 以流返回
```

### 2.2 实现要点

| 要点 | 说明 |
|---|---|
| 零 SDK | 只用 JDK 21 `HttpClient` + 自研 `CosSigner`（HMAC-SHA1），无 COS SDK 依赖，离线可构建 |
| 签名有效期 | 单次请求 900 秒；带 60 秒时钟回拨容忍 |
| 虚拟主机风格 | `https://<bucket>.cos.<region>.myqcloud.com/<key>`；桶名不能含点号（TLS 通配会失配） |
| 双后端 | `STORAGE_TYPE=local`（默认）与 `cos`；配置不全时**回落 local 并打 WARN（点名缺哪个键）** |
| 对外契约不变 | 无论本地盘还是 COS，上传都返回 `<base>/uploads/<uuid>.<ext>`，存量 URL 不失效 |
| 图片处理 | COS 模式追加 `UPLOADS_IMAGE_SUFFIX`（imageMogr2）；GIF/视频/语音**不追加** |
| 私有读 | 不走签名 URL 外发，统一经 `duoduo-user` 的鉴权接口读流，避免链接被转发后长期可读 |

### 2.3 代码位置

| 文件 | 作用 |
|---|---|
| `duoduo-common-web/.../storage/ObjectStorage.java` | 存储接口；`PREFIX_PUBLIC="uploads/"`、`PREFIX_PRIVATE="materials/"` |
| `duoduo-common-web/.../storage/ObjectStorageAutoConfiguration.java` | 按 `duoduo.storage.type` 装配两个 Bean；配置不全时回落 local |
| `duoduo-common-web/.../storage/CosObjectStorage.java` | COS 读写删探测、错误码解析、451 欠费提示 |
| `duoduo-common-web/.../storage/CosSigner.java` | HMAC-SHA1 签名 |
| `duoduo-common-web/.../storage/LocalFsObjectStorage.java` | 本地盘实现（默认，CI/开发用） |
| `duoduo-fulfilment/.../controller/UploadController.java` | `POST /api/uploads`、`GET /uploads/{file}`、`POST /api/uploads/from-url`、内部删除 |
| `duoduo-user/.../support/MerchantMaterialStorage.java` | 私有桶读写；key 为 `materials/{merchantId}/{uuid}.{ext}` |
| `duoduo-user/.../controller/MerchantMaterialReadController.java` | 私有材料鉴权读取 |
| `docker-compose.yml` | 只向 `duoduo-user-app` 与 `duoduo-fulfilment-app` 注入 COS 变量 |
| `.env` / `.env.example` | 配置入口（`.env` 已在 `.gitignore`） |

---

## 3. 两项关键配置

### 3.1 桶名（`COS_BUCKET_PUBLIC` / `COS_BUCKET_PRIVATE`）

**含义**：COS 桶在腾讯云的全局唯一名称，格式 `<自定义名>-<APPID>`，必须与地域一起才能定位。
**当前项目取值**：

| 变量 | 当前值 | 访问属性 | 用途 |
|---|---|---|---|
| `COS_BUCKET_PUBLIC` | `duoduo-mall-public-1374887902` | **公有读、私有写** | 所有买家可见的图片/视频/语音 |
| `COS_BUCKET_PRIVATE` | `duoduo-mall-private-1374887902` | **私有读写** | 入驻资质材料 |
| `COS_REGION` | `ap-guangzhou` | — | 两个桶同地域 |

**在项目中的使用位置**：

| 位置 | 用法 |
|---|---|
| `.env` | 唯一配置源；`docker compose` 读取后注入容器 |
| `docker-compose.yml`（user / fulfilment 两个服务） | `COS_BUCKET_PUBLIC: ${COS_BUCKET_PUBLIC:-}`、`COS_BUCKET_PRIVATE: ${COS_BUCKET_PRIVATE:-}` |
| `duoduo-fulfilment/application.yml` | `duoduo.storage.cos.bucket-public: ${COS_BUCKET_PUBLIC:}` |
| `duoduo-user/application.yml` | `duoduo.storage.cos.bucket-private: ${COS_BUCKET_PRIVATE:}`（同时保留 public 键以通过完整性校验） |
| 运行时 | 拼成 host：`<bucket>.cos.<region>.myqcloud.com`，见 `CosObjectStorage` 构造函数 |

**关键约束**：
1. 桶名**必须带 APPID 后缀**，且与 `COS_REGION` 匹配；写错表现为 `NoSuchBucket` 或 TLS 证书错误。
2. 应用的对象键前缀固定为 `uploads/`（公有桶）与 `materials/`（私有桶）；**不要手工改前缀**，内部删除与 URL 解析都按它定位。
   **唯一例外**是运维备份前缀 `db-backup/`（私有桶，2026-10-28 起）：由 `scripts/db-backup-cos.sh`
   写入，应用代码不读写、也没有任何对外接口暴露它 —— 它不属于 `ObjectStorage` 的对象模型。
3. **私有桶绝不能设为公有读**；公有桶也不要开公有写。

### 3.2 子账号凭证（`COS_SECRET_ID` / `COS_SECRET_KEY`）

**含义**：腾讯云 CAM **子账号**的 API 密钥（SecretId 是身份标识，SecretKey 是签名私钥）。
**用途**：服务端对 COS 发起 PUT/GET/HEAD/DELETE 时计算签名；**不参与前端请求**，永不下发浏览器或小程序。

**在项目中的使用位置**：

| 位置 | 说明 |
|---|---|
| `.env` | `COS_SECRET_ID` / `COS_SECRET_KEY`（值不入库、不进 Git） |
| `docker-compose.yml` | 仅注入 `duoduo-user-app`、`duoduo-fulfilment-app` |
| `application.yml` | `duoduo.storage.cos.secret-id/secret-key: ${COS_SECRET_ID}/${COS_SECRET_KEY}` |
| `ObjectStorageAutoConfiguration` | 缺失任一键 → 回落 local 并 WARN |
| `CosSigner` | 用 SecretKey 做 HMAC-SHA1，产出 `Authorization` 头 |

**权限要求（CAM 策略）**：

```
允许：cos:PutObject、cos:GetObject、cos:HeadObject、cos:DeleteObject
范围：公有桶 uploads/*；私有桶 materials/*；私有桶 db-backup/*（运维备份）
禁止：cos:GetService、cos:ListBucket、桶级 cos:*、PutBucketAcl、PutBucketPolicy
```

理由：应用只需要对象读写；授予桶级权限意味着密钥泄露后对方能把桶改成公有读——这是数据事故，不是配置便利。

> **备份的权限（2026-10-28）**：`scripts/db-backup-cos.sh` 只做 PUT / HEAD / DELETE，
> **不列桶、不读对象内容**，所以最小权限可以只授 `PutObject + HeadObject + DeleteObject`，
> 范围限定在私有桶 `db-backup/*`。如果应用与备份共用一个子账号密钥（当前 `.env` 就是如此），
> 策略里必须**同时包含** `db-backup/*`，否则备份会 403 `AccessDenied`；
> 反过来，给备份单独建一把只能写 `db-backup/*` 的密钥更符合最小权限。

**凭证纪律**：
- 不用主账号密钥；
- 不写进仓库、文档、日志、聊天；只在 `.env` 或部署环境的 Secret 中；
- 轮换时：控制台新建密钥 → 更新 `.env` → 重建两个服务 → 验证上传/读取 → 禁用旧密钥。

### 3.3 配套变量（容易漏）

| 变量 | 当前值 | 作用 |
|---|---|---|
| `STORAGE_TYPE` | `cos` | 切到 COS；改回 `local` 即回退本地盘（无需改代码） |
| `UPLOADS_PUBLIC_BASE_URL` | `https://duoduo-mall-public-1374887902.cos.ap-guangzhou.myqcloud.com` | 上传接口返回给前端的图片 base；**COS 模式下必须设为公有桶域名**，否则返回的 URL 指向应用自身 |
| `UPLOADS_IMAGE_SUFFIX` | `?imageMogr2/auto-orient/thumbnail/1600x1600>/format/webp/quality/82/strip` | 数据万象处理参数；本地模式必须留空 |
| `UPLOADS_MAX_MB` | `5` | 图片体积上限（视频/语音不卡体积） |

**验证配置是否真正生效**：启动日志会打印
`对象存储后端 = COS（public-bucket=... host=...）`。
若看到 `对象存储后端 = local` 且此前以为已切 COS，说明有键缺失，WARN 里会点名。

### 3.4 数据库备份到私有桶（运维，2026-10-28）

与应用上传完全独立的一条链路，避免「备份把图片存储搞坏」或反过来：

| 项 | 说明 |
|---|---|
| 脚本 | `scripts/db-backup-cos.sh`（Linux 原生：docker / curl / openssl / gzip，**零 SDK**） |
| 桶与前缀 | 只用**私有桶**的 `db-backup/<YYYY-MM-DD_HHmmss>/`，绝不放公有桶 |
| 签名 | 与 `CosSigner` 同一套 v5-sha1；有效期 900 秒，与 §2.2 一致 |
| 权限 | `PutObject + HeadObject + DeleteObject`（`db-backup/*`）；**不需要** `GetObject` / `ListBucket` |
| 完整性 | 源库表数 = 产物 `CREATE TABLE` 数；`gzip -t`；远端 HEAD 字节数 = 本地；`_manifest.txt` **最后上传**（它存在才算本次完整） |
| 自检 | `bash scripts/db-backup-cos.sh --check`：HEAD 探测对象，**404 = 桶可达且签名有效** |
| 定时 | `0 3 * * *`（见 `docs/manuals/production-launch-checklist.md` §4.1） |
| 生命周期 | 在控制台给 `db-backup/` 配 30 天过期；脚本**不删远端**，避免误删 |
| 与应用的关系 | 不经过 `ObjectStorage`、不改 `uploads/` / `materials/` 前缀；私有桶保持私有读写 |

> 注意：`--check` 用 HEAD 而不是 GET，就是为了不要求 `GetObject` —— 备份密钥可以只写不读。
> 私有桶的备份对象**没有**任何对外读取接口，恢复只能通过控制台或运维机的签名工具。

---

## 4. 公有桶 / 私有桶截图对应关系

> 说明：2026-10-28 已收到控制台截图（两个桶均位于广州、私有桶为私有读写）；
> 下表以**实际可访问性 + 当前 `.env`** 为准 —— 桶名直链返回 403/404 表示存在但无权列举，
> `NoSuchBucket` 才表示桶名错。若截图与下表不一致，以 `.env` 为准，并在变更记录登记。

| 核对项 | 截图应显示（公有桶） | 截图应显示（私有桶） | 本项目配置 |
|---|---|---|---|
| 桶名 | `duoduo-mall-public-1374887902` | `duoduo-mall-private-1374887902` | `COS_BUCKET_PUBLIC` / `COS_BUCKET_PRIVATE` |
| 地域 | 广州 `ap-guangzhou` | 广州 `ap-guangzhou` | `COS_REGION=ap-guangzhou` |
| 访问权限 | **公有读私有写** | **私有读写** | 公有桶匿名 GET；私有桶仅鉴权接口 |
| 所属 APPID | `1374887902` | `1374887902` | 桶名后缀与之一致 |
| 对象前缀 | `uploads/` | `materials/`（另有运维备份 `db-backup/`，应用不读写） | `ObjectStorage.PREFIX_PUBLIC/PRIVATE`；备份见 §3.4 |
| 典型对象 | `uploads/<uuid>.jpg`、`.mp4`、`.aac` | `materials/<merchantId>/<uuid>.pdf` | 上传代码 |
| 访问入口 | `https://duoduo-mall-public-1374887902.cos.ap-guangzhou.myqcloud.com/uploads/...` | 仅 `GET /api/merchant-materials/{merchantId}/{name}`（JWT + 归属校验） | — |
| 图片处理 | 控制台“数据处理/图片处理”可用 | 不需要 | `UPLOADS_IMAGE_SUFFIX` |
| 生命周期 | 可选（未配置也不影响） | 建议配置（材料长期保留） | 见 `docs/image-delivery-optimization.md` |

**三分钟核对法**（不看截图也能确认）：
1. 公开桶直链应返回 200：`curl -I --noproxy '*' https://duoduo-mall-public-1374887902.cos.ap-guangzhou.myqcloud.com/`（403/404 属正常，说明桶存在但无权列举；`NoSuchBucket` 才是桶名错）。
2. 私有桶直链访问一个存在的对象应返回 **403**；若返回 200，说明权限配错了，立即停止上线。
3. 私有材料接口未登录应返回 **401**，用他人 merchantId 访问应返回 **403/404**。

---

## 5. 开发者对接步骤（从零到可用）

1. **确认 `.env` 六项齐全**：`STORAGE_TYPE=cos`、`COS_SECRET_ID`、`COS_SECRET_KEY`、`COS_REGION`、`COS_BUCKET_PUBLIC`、`COS_BUCKET_PRIVATE`，外加 `UPLOADS_PUBLIC_BASE_URL`。
2. `docker compose up -d --force-recreate duoduo-user-app duoduo-fulfilment-app`。
3. 看日志：`docker logs --tail 50 duoduo-app-fulfilment | grep "对象存储后端"`，应为 COS。
4. 传一张图：`POST /api/uploads`（需登录），返回 `https://<公有桶>.../uploads/<uuid>.jpg?...`。
5. 打开该 URL，应 200 且 `content-type: image/webp`（若配了 image-suffix）。
6. 商家端上传一份资质 PDF，再用未登录请求访问 `/api/merchant-materials/...`，应 401。
7. 删除测试对象（内部接口或控制台），确认不影响其他图片。

---

## 6. 故障排查

| 现象 | 最可能原因 | 处理 |
|---|---|---|
| 启动日志仍 `local` | 五个键任一为空 / `STORAGE_TYPE` 不是 `cos` | 看 WARN 点名的键；补齐后重建容器 |
| `SignatureDoesNotMatch` | SecretKey 错、系统时钟偏差大、代理改写 Host | 校对密钥与环境；确认 `NO_PROXY`、无中间代理改 Host |
| `AccessDenied` | CAM 策略缺 action（常见漏 `HeadObject`） | 按 §3.2 补对象级权限 |
| `NoSuchBucket` | 桶名缺 APPID 后缀 / 地域不符 | 对照控制台“存储桶列表”的完整名称与地域 |
| HTTP **451** | 腾讯云账号欠费停服 | 控制台充值；这是账号级故障，重试无用，读写会一起失败 |
| 上传成功但页面裂图 | `UPLOADS_PUBLIC_BASE_URL` 未设为公有桶域名 | 改配置并重建 fulfilment |
| 私有材料能匿名打开 | 私有桶被设为公有读，或读接口未鉴权 | 立即改回私有；检查 `MerchantMaterialReadController` 的归属校验 |
| GIF 不动 | 给 GIF 追加了 imageMogr2 | 代码已跳过 GIF；检查是否手工拼了参数 |

---

## 7. 变更影响清单（换桶/换地域/换域名前必读）

图片 URL 是**绝对地址落库**的（商品图、评价 images JSON、头像、售后举证）。
因此：

| 变更 | 影响 | 必须做的事 |
|---|---|---|
| 换公有桶 / 换地域 | 存量 URL 全部失效 | 先做对象迁移，再改 `COS_BUCKET_PUBLIC`/`UPLOADS_PUBLIC_BASE_URL`，最后批量改库 |
| 换 CDN 域名 | 同上 | 保留源站可读一段时间，双写/回源验证后再切 |
| 换子账号密钥 | 无数据影响 | 新密钥生效并验证后，再禁用旧密钥 |
| 改对象前缀 | 存量读不到 | 不要把 `uploads/`、`materials/` 当前缀参数化 |

---

## 8. 安全红线（评审会查）

1. 私有桶不得公有读；公开桶不得公有写。
2. 应用密钥只授对象级 4 个 action，不授桶级权限、不授列举。
3. 密钥只存在于 `.env` / 部署 Secret；仓库、文档、日志、聊天中出现即视为泄露，必须轮换。
4. 私有材料读取必须校验“当前登录人是否属于该 merchantId”，不能只靠 URL 猜不到。
5. 上传必须校验扩展名与 MIME 一致、文件名由服务端生成（UUID），防止路径穿越与伪装文件。
6. 不在图片 URL 上放长期签名；私有内容走鉴权代理，不做公开预签名链接。
7. 数据库备份只能进**私有桶**的 `db-backup/`，禁止放公有桶；备份密钥不应具备 `ListBucket` 或桶级权限。

---

## 9. 配置核查记录（2026-10-28）

用当前 `.env` 的 `COS_SECRET_ID/KEY` 做了一次只读核查 + 一次可清理的写探针：

| 核查项 | 方法 | 结果 | 判定 |
|---|---|---|---|
| 密钥身份 | 腾讯云 STS `GetCallerIdentity` | `Type=CAMUser`，AccountId `100043864168`，UserId `100053680511` | ✅ 是子账号，不是主账号 |
| 私有桶签名与连通 | `bash scripts/db-backup-cos.sh --check` | HEAD 探针返回 404（对象不存在，签名有效） | ✅ 桶名/地域/密钥正确 |
| `db-backup/*` 写权限 | 上传 5 字节探针 → `--stat` 校验 → `--delete` | 上传成功、HEAD 5 字节、删除成功 | ✅ Put/Head/Delete 均具备 |
| 是否过度授权 | `GET /?prefix=db-backup/`（ListBucket） | HTTP 403 `AccessDenied` | ✅ 未授予列举桶权限 |
| 生命周期规则 | `GET /?lifecycle` + 控制台截图（2026-10-28） | API 403（无读取权限）；控制台仅见「历史版本 30 天」「碎片 30 天」两条**整桶**规则，**无 `db-backup/` 对象过期规则** | ❌ **未完成**：现有规则不会删除当前版本的备份对象 |

探针对象 `db-backup/manual/2026-10-28_143711-cos_probe.txt` 已删除并确认清理。

### 9.1 生命周期现状（2026-10-28 控制台截图）

私有桶当前**已开启版本控制**，生命周期里只有两条整桶规则：

| 规则 | 范围 | 实际作用 | 是否覆盖备份 |
|---|---|---|---|
| `RegularlyClean_History...` | 整个存储桶 | 清理无历史版本的删除标记；**历史版本**文件删除 30 天 | ❌ 只管旧版本，不删当前版本 |
| `删除碎片` | 整个存储桶 | 未完成分片上传的碎片删除 30 天 | ❌ 与完整对象无关 |

**缺口**：没有任何规则让 `db-backup/` 下的**当前版本对象**在 30 天后过期。备份会无限累积；
且因版本控制开启，脚本 `--delete` 只产生删除标记，空间不会立即释放。

**补做步骤**：控制台 → 私有桶 → 基础配置 → 生命周期 → 添加规则：
- 规则名称：`db-backup-expire-30d`
- 应用范围：**指定前缀** `db-backup/`
- 规则内容：**对象过期删除 30 天**（当前版本）
- 历史版本：已有整桶 30 天规则，可不重复；若要严格 30 天物理清理，可另加「历史版本 1 天」并限定同一前缀
- 碎片：已有整桶规则，无需重复
- 状态：开启，保存

**验收**：规则列表出现 `db-backup-expire-30d`（指定前缀 `db-backup/`、对象过期 30 天、状态开启）。
注意生命周期为异步执行；版本控制下「可见性 30 天 + 物理清理再由历史版本规则延后」属正常。
