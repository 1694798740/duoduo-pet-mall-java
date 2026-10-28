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
2. 公有桶的对象键前缀固定为 `uploads/`，私有桶固定为 `materials/`；**不要手工改前缀**，内部删除与 URL 解析都按它定位。
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
范围：两个桶的 uploads/* 与 materials/*（或整桶对象级）
禁止：cos:GetService、cos:ListBucket、桶级 cos:*、PutBucketAcl、PutBucketPolicy
```

理由：应用只需要对象读写；授予桶级权限意味着密钥泄露后对方能把桶改成公有读——这是数据事故，不是配置便利。

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

---

## 4. 公有桶 / 私有桶截图对应关系

> 说明：本轮对话未收到截图附件，下表以**当前 `.env` 与运行配置的实际值**建立对应；
> 你补发截图后，可按“控制台可见字段”逐项核对。若截图与下表不一致，以 `.env` 为准，并在变更记录登记。

| 核对项 | 截图应显示（公有桶） | 截图应显示（私有桶） | 本项目配置 |
|---|---|---|---|
| 桶名 | `duoduo-mall-public-1374887902` | `duoduo-mall-private-1374887902` | `COS_BUCKET_PUBLIC` / `COS_BUCKET_PRIVATE` |
| 地域 | 广州 `ap-guangzhou` | 广州 `ap-guangzhou` | `COS_REGION=ap-guangzhou` |
| 访问权限 | **公有读私有写** | **私有读写** | 公有桶匿名 GET；私有桶仅鉴权接口 |
| 所属 APPID | `1374887902` | `1374887902` | 桶名后缀与之一致 |
| 对象前缀 | `uploads/` | `materials/` | `ObjectStorage.PREFIX_PUBLIC/PRIVATE` |
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
