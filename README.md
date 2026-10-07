# 多多宠物商店 · Java 微服务版

> Java 21 · Spring Boot 3.2.5 · Spring Cloud 2023.0.1 · Spring Cloud Alibaba 2023.0.1.0 · MyBatis-Plus 3.5.5
> 依据《多多宠物商店 · 产品需求原型 PRD v0.1》重做，替代此前的 Node/Express 原型实现。
> 架构设计见 [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md)；开发看板见 [`docs/PLAN.md`](./docs/PLAN.md)。

**R59（2026-09-30，用户要求：按《未完成项清单》优先级全量开工）** — **券口径固化 / 调度选主 / 内部凭据升级 / 角色升级即时生效 / 发布限流 / 路径消歧 / 零单测模块补测 / 文档同步 / AI 加强 6 项**。

**① P0-2 定时任务选主（多实例去重跑）**。新增 `common-web` 的 `SchedLeaderLock` + 表 `docs/sql/incremental/23-r58-sched-leader.sql`（主键即锁名，`expire_at` 自动过期，崩溃/被杀无需显式释放）。
- **抢占两步且顺序不能反**：先 `UPDATE`（锁已过期或本就是自己持有 → 续约）→ 影响 0 行再 `INSERT`（首次运行）；撞唯一键即别人持有、本轮放弃。**先 SELECT 再写**在并发下会让两个实例都看到「没有这行」而双双写入 —— 唯一键是唯一可靠的仲裁者。
- 锁事务 `REQUIRES_NEW` **独立提交**：挂在业务事务里时业务回滚会把锁一起回滚，等于没上锁。
- 4 个既有定时任务接入；`duoduo.sched.leader-enabled=false` 可整体退回外部调度（XXL-Job 等），**零新增中间件**。

**② P0-3 内部凭据升级为 HMAC 签名**。`common-web` 新增 `InternalCallAuth`，5 个域的 `InternalTokenFilter` 统一接入：
- 头格式 `dd1.<caller>.<tsSeconds>.<nonce>.<签名>`，签名覆盖调用方 + 时间戳 + nonce —— 旧实现是**明文共享密钥相等即放行**，口令一旦泄露（日志 / 配置仓库）谁都能冒充内部服务；新实现即使被截获一条也只能重放一次、超出时间窗即失效。
- **失败关闭**：解析 / 验签异常一律拒绝，不放行兜底。冒烟脚本共用 `scripts/lib/internal-token.ps1` 生成凭据。

**③ P1-4 商家自助管理自己的商品**。`SellerProductService` 抽出 `requireOwned()`（**上下架与编辑共用同一条归属判定** —— 少一处就变成「上下架挡住、编辑没挡住」这类只在越权时才暴露的漏洞，而两处代码看起来几乎一样）；新增 `PUT /api/seller/products/{spuId}` 编辑**标题与主图**。字段**三态**：`null` = 不改 / `""` = 清空 / 有值 = 替换（把「不改」传成空串，用户原有主图会被清掉）。**价格 / 库存 / 类目刻意不开** —— 牵动 SKU、ES 索引与在途订单金额口径，属「改价即改合约」。标题变更后**不直接写 ES**，交给幂等的对账型同步任务收敛（直接写索引反而绕过它）。

**④ P1-5 退货物流**。`t_after_sale` 增寄回运单 / 签收四列（`docs/sql/incremental/24-r58-return-logistics.sql`），状态机新增 `WAITING_RETURN`（买家填寄回）→ `RETURNING`（卖家签收，**退款在这一刻才发生**）。**退款时点从「卖家同意」挪到「卖家确认收货」** —— 同意即退款意味着货没回来钱先走；而「等收货再退」若没有系统入口，就只是一句靠线下执行的空话。**刻意不建退货轨迹表**：正向轨迹的「签收」是订单交付终态，混进退货节点会让「已签收」在一张订单里有两种含义。新增 `POST /{id}/return-shipment`、`POST /{id}/return-receipt`，两态**同样可进仲裁**。

**⑤ P1-6 角色升级即时生效**。新增 `AuthCookieWriter`（`dd_at` / `dd_rt`）与 `AuthService.renewAccessToken`：
- **角色从库现读，不接受调用方传参** —— 允许传角色等于允许伪造。BANNED 抛 403。
- 升级场景**只覆盖访问令牌、不动刷新令牌**（不必逼用户重登）；**降级 / 运营改角色必须 `revokeAllByUser` 吊销全部刷新令牌** —— 权限变小却留着旧刷新令牌，等于降级可以被打回。
- 商家申请通过后在 `UserController.applyMerchant` 直接覆盖 Cookie；另加 `POST /api/users/me/merchant/token` 供前端静默换取。

**⑥ P1-7 闲置发布频率限流**。`BizException.rateLimited()`（**429**）与既有 `businessRule`（**422**）语义分开：429 = 内容没问题但发太快、422 = 改一改就能成功，混用会让前端把「等一分钟」提示成「填写有误」。`ListingService.checkPublishQuota()` 滑动 24h 计数（**含软删**，否则删了重发即可绕过）+ 最小间隔，阈值可配（默认 10 次/天、60 秒）。

**⑦ P1-8 路径变量形状约束**。`@GetMapping("/{orderNo}")` → `@GetMapping("/{orderNo:\d{21}}")`（订单号固定 21 位纯数字）：不限定形状时「明细」与其他子路径会互相抢占匹配，表现为随机 404/405。探针断言由 `st in (404,405)` **收紧为 `st == 405`**。

**⑧ P0-1 券 v1 口径**：维持「不支持的组合明确 422 拒绝」（不擅自放宽成静默降级 —— 用户会以为券生效、结算才发现没减），改用 marketing 模块**券相关 13 项有断言的单测**把行为固化（`CouponV1BoundaryTest` 4 项：非满减类型一律拒绝且文案明确 / 类型大小写不敏感 / `release` 只解锁 LOCKED、已核销的不退回 / 没有锁定券时不报错且返回 0；另有 `CouponStatusTest` 5 项、`CouponCodeGeneratorTest` 4 项），防止日后被顺手改成放行。

**⑨ P2-10 零单测模块补测**：新增网关 `JwtAuthGlobalFilterTest`（8：公开读放行 / 写路径拦 / 三段不被两段通配连带放行 / 微信绑定需登录 / **伪造身份头与 IP 头被剥离** / OPTIONS 放行）、`NoticeServicePushTest`（7：两阶段落库 —— 第 N 条非法则整批不写）、`UnreadServiceTest`（3）、`SnowflakeIdGeneratorTest`（6：首条断言 **id 超出 JS 安全整数**，为「前端禁止 `Number()` 强转」提供机器可检依据）、`AfterSaleServiceTest`（9）、`SellerProductServiceTest`（8）、`SchedLeaderLockTest` / `InternalCallAuthTest` / `RoleTokenEffectiveTest` / `AdminUserServiceRoleChangeTest` / `PetProfileBriefTest`。

**⑩ AI 加强 6 项（原 R57 选修 S1-S3 扩展）** —— 详见 `docs/PLAN.md` R59 段：宠物档案个性化（**只补充不覆盖**、裁剪视图不含病史）、政策知识库（**只写流程与入口、不写时效与赔付数字**）、快捷问题 / 追问 / 对比（**对比不进模型**，只并列客观差异）、限流多实例（`ObjectProvider<StringRedisTemplate>`，Redis 故障**冷却 60s** 再退回内存）、mock 走独立话术（**护栏可达**，否则护栏在检查自己的输出）、召回多样性（同款按标题去规格归一去重，保留更便宜那件）。小程序 `pages/ai` 已消费新字段：「大家都在问」快捷 chips（**只首屏显示**）+ 回答后的相关追问 chips，**换话题时 followUps 与 options 一起清掉** —— 后端下发而页面不渲染等于没做，接口探针永远测不到这一层。

**⑪ R59 补丁（全站联调暴露，同一轮内修完）** —— 三项都是「编译过了、单测绿了，只有真跑起来才现形」的问题：
- **内部凭据签名去掉请求路径**。原实现把 path 签进规范串，而 Feign 的 `RequestTemplate.path()` **取不到 `@FeignClient(path=...)` 的类级前缀**（实测 `path=/internal/skus/batch`，服务端实际是 `/api/products/internal/skus/batch`）→ 签出来的串两边永远不等 → 全站服务间调用 403，冒烟从 20/23 掉到 3/23。改为只签 `METHOD`（+ caller/ts/nonce），安全性不下降：nonce 一次性，且签了也不校验等于只增假失败面。同步改 10 处调用点 + 3 个脚本；新增单测「换路径不影响校验结果」。
- **`spring-boot-starter-jdbc` 标 `<optional>true</optional>`**。AI 服务没有数据库，`DataSourceAutoConfiguration` 因依赖被**传递**进来而启动失败（`Failed to determine a suitable driver class`）。且 `@ConditionalOnClass(JdbcTemplate.class)` 必须挂在**内层配置类**上 —— 挂在 `@Bean` 方法上时，Spring 为解析方法签名会先加载 `JdbcTemplate`，条件注解还没生效就 `NoClassDefFoundError`。
- **`NoResourceFoundException` 显式映射为 404**。加了路径形状约束（如 `{orderNo:\d{21}}`）后，形状不匹配的请求不再命中 Controller，转去查静态资源再抛此异常 → 被 `Exception` 兜底成 500。「不存在的订单」该是 404 而不是 500，否则前端重试逻辑全错。

**验证**：全模块 `mvn -o test` **210/0 失败**（ai 94 / common-web 21 / trade 19 / product 15 / user 15 / marketing 13 / social 10 / fulfilment 9 / gateway 8 / common 6）；前端 node 单测 **251/0**（既有 229 项全过 + 新增 `scripts/r59-ai-suggestions-test.js` 22 项，含 4 组反向验证）；小程序静态自检 **0 错 0 警**；`mvn -o -DskipTests package` BUILD SUCCESS 且 `unzip -l` 确认新类均在 jar 内；**每一项新能力均做反向验证**（故意改坏 → 断言转红 → 还原）；冒烟 `scripts/smoke.ps1` **55/0**、`scripts/wechat-auth-smoke.ps1` **26/0**。

**冒烟脚本的两条纪律（本轮踩出来的）**：
- **形态自适应靠问服务端，不靠读配置猜**。微信登录冒烟的场景② 有两种合法形态（注入了真实 AppID → 假 code 被微信侧拒 → 401 UNAUTHORIZED；没注入 → 外部依赖缺失降级 → 503 DEGRADED）。写死 503 会在配了凭据的机器上永远红，且把人往一个并不存在的毛病上引；改成读 `.env` 判断仍不稳（compose 是**环境变量注入**，`.env` 的位置/编码/键名都可能踩空）。最终按**实际返回**选分支，返回既非 401 也非 503 才算真失败。
- **断言总数守卫自己也要站对位置**。守卫原在「清理夹具」那条 Check **之前**，于是把那条还没执行的断言当成「被静默跳过」报红；且场景② 是互斥分支，声明数要扣掉未走的那几支。**守卫报警不等于真有问题 —— 先确认守卫本身对不对。**

**R57（2026-09-29，用户要求：「仅实现 6 项必修」AI 改造）** — **约束硬过滤 / 超时隔离 / 限流加固 / 输出护栏 / 澄清与失败出口 / 测试真绿**（必修 M1-M6；选修 S1-S3 按用户指令不做）。

**① M1 预算与品类的服务端硬过滤（本轮核心）**
- `ConstraintParser` 规则解析（正则，不押在模型身上）：预算上限/下限/区间 + 品类；**三种状态严格区分** —— 解析到（过滤）、没提（不过滤）、**提了但没解析出来（必须回复「我没听清你的预算数字，这次没按预算筛」，绝不静默当没提）**。
- `CategoryCatalog` 显式别名表解决「类目名 vs 口语」鸿沟：库里一级类目叫「宠物主粮」、用户说的是「猫粮/狗粮/口粮」，直接子串匹配必败。别名表要维护：漏配只是召回变窄，不会错推荐。
- **约束在商品域 SQL 里生效**（`min_price <= budgetMax`、`max_price >= budgetMin`、`category_id IN (...)`）：「预算等约束写在提示词里 = 没有约束」—— 过滤必须发生在数据库查询里，超预算的商品根本进不了候选池。价格语义是**重叠**：`budgetMin` 用 `max_price >= ?`（199~299 对「100 以上」有意义），写反会误杀。
- **★ 单测抓到真缺陷（不报错、只悄悄推荐错）**：「预算 200 元以内」被 MAX/MIN 两套正则**同位重匹配**成 min=max=200 → 99~159 元的便宜货被 `max_price >= 200` 误杀且无任何报错。修法：下限匹配跳过与上限命中区间重叠的命中。另修「最便宜的是哪个？」被信号词「便宜」误判成预算未解析（负向后视 `(?<!最)`）。

**② M2 召回独立超时**：`AiFeignConfig`（connect 300ms / read 1500ms），**刻意不加 `@Configuration`**（否则被组件扫描当全局配置）；商品域慢不再占住 AI 线程池。

**③ M3 限流键不可伪造 + 全站总配额**
- 网关**先剥离**客户端可写的 `X-Forwarded-For`/`X-Real-IP`，再写入 TCP 对端地址 —— **判据：凡是被下游当作事实使用的头，上游必须保证它只能由自己写入**。旧实现取 XFF 首段当限流键：换个假 IP 等于新用户、永不限流，而 LLM 配额真金白银。
- `IpRateLimiter` 加全站总配额（默认 300/分钟）防「分布式换键刷」；**两道判断都计数**（被单键挡住的流量也消耗全局额度，口径才不乱）。

**④ M4 输出护栏**：`ReplyGuard` 拦两类 —— 绝对化/承诺词（广告法口径；刻意不含「最好/最适合」这类主观推荐语，拦了 AI 就不会说人话）与**价格一致性**（正文价格必须能在候选 min/max 里找到，只认带 ¥/元/块 的数字防「2 岁」误伤）。命中后**替换为事实版回复而不是拒绝回答**（防 over-refusal）；mock 复用同一事实版生成器，两条路径价格口径天然一致；命中记 WARN + micrometer 计数（可选注入，监控缺席不阻断启动）。

**⑤ M5 失败出口 + 轻量澄清**
- 五类「答不了」（物流/退换/优惠/发票/支付）在调模型**之前**拦截，如实说「我查不到」+ 给入口 —— 这类问题没有数据源，交给模型只会得到听起来像真的编造（售后政策说错要担责）；顺带把「没货」与「我答不了」分开，用户不再一遍遍换说法试。
- 澄清：没澄清过 + 无硬约束 + （召回为空或 ≤6 字）→ 反问**一次**，给**可点选项**（`clarify.options` 的 label/send 分离，前端 chips 点一下直接发）；`clarified` 布尔随每次请求上行（服务端无会话状态，靠前端带上「已澄清过」）。

**⑥ M6 测试真绿**：预算断言从「仅 llm 模式」改**无条件必查**（旧版在 mock 下 SKIP，而 CI 永远跑 mock → **长期空转假绿**）；新增 11 项探针：区间重叠、**极小预算（1 元）必得空结果**（证明预算参数真的传进了 SQL 而不是被半路丢掉）、预算未解析告知、「最便宜」不误判、出口预判、澄清两次探针、**伪造 XFF 不能绕过限流**（每次换假 IP 夹进限流循环）。

**验证**：ai 单测 **29/0**（`ConstraintParserTest` 18 + `ReplyGuardTest` 11）+ 前端 `r57-ai-clarify-test` **23/0**（真实加载 api.js/ai.js 桩 wx.request 断言出参；含 3 组反向验证，并修掉谓词 `height` 误配 `line-height` 的洞）+ `ai-smoke` **37/0** + 回归 **346/0**（order 77 / address 82 / payment 71 / share-circle 74 / notice 42）+ 静态自检 0 错 0 警 + 三镜像重建 healthy。
**★ 部署教训（R52 二次踩坑）**：改完只 `compile` 不 `package` → `compose build` COPY 层缓存命中 → **旧 jar 进镜像、容器行为照旧**；必须重打 jar 并解包验证内容（`unzip -l` 见新类），别只看构建退出码。

**R56（2026-09-29，用户要求：优先「完善分享圈功能」）** — **评论+回复+评论点赞 / 关注流 / 删除·编辑自己的分享 + 举报（含运营处理台）/ 话题标签 #**。

**① 评论（全仓此前「零实现」）**
- 新增 `t_share_comment`（`parent_comment_id` 为 NULL = 一级评论，非 NULL = 回复）+ `t_share_comment_like`（复合主键防重）。**刻意只做二级** —— 二级以上要处理「回复谁的回复」的折叠与 @ 链路，属另一个量级。
- 计数口径：`t_product_share.comment_count` **一级与回复都算**（与卡片上那个数字的语义一致）；发表 +1，运营下架评论时 `-(1 + 其下未删回复数)`。
- **★ 评论列表用查询参数而不是 `/{id}/comments`**：网关白名单按**路径**匹配、**不区分 HTTP 方法**。列表若写成两段路径，要放行它就得加 `/api/shares/*/comments`，而那会**连带把「发表评论」放成匿名可调**（等于开放匿名 UGC 直写）。故：`GET /api/shares/comments?shareId=`（**一段**，落进既有白名单条目 → 匿名可读）+ `POST /api/shares/{id}/comments`（**两段** → 强制登录）。**路径形状本身就是这里的防线**，不是命名风格问题。
- 发布后 best-effort 推通知（复用 R55 的生产者端点）：一级评论通知分享作者；回复通知被回复者，**并同时通知分享作者**（批量端点一次带两条）；三种不推：评论自己的分享、回复自己的评论、分享作者就是被回复者。

**② 关注流**
- 分享在履约库、关注关系在社交库 —— **跨域**。social 新增内部端点 `GET /api/socials/internal/following?userId=&limit=`（凭据保护、**只返回 id**、服务端再夹一次上限 500）；fulfilment 新增 `SocialClient`（`contextId = "socialFollowingIds"`，与本域既有的 `socialNoticePush` 必须区分）。
- `GET /api/shares/feed?scope=FOLLOWING`。**未关注任何人时返回空列表，绝不退化成全站内容** —— 那会让用户以为自己关注的人发了一堆没看过的内容。
- 社交域不可用时**显式报错**（「关注列表暂时获取不到，请稍后重试」）而不是静默空列表：空列表会让用户去检查自己的关注列表，然后找不到问题。

**③ 删除 / 编辑 / 举报（含运营处理台）**
- **★ 删除是软删**（`t_product_share.deleted_at`），两个具体原因：① 转发单的「原帖引用」是查询时按 `parent_share_id` 回查原帖行得到的 —— **物理删掉原帖，转发单的引用块会变空**（用户视角像页面坏了）；② 举报留痕与计数需要「这条内容曾经存在」。所有读路径一律带 `deleted_at IS NULL`。按用户选定口径：原帖删除后转发单仍在，引用块显示**「原内容已被作者删除」**（服务端把 `parentContent` 置 null + `parentDeleted=true`）。
- 编辑**只能改文字与话题**（商品是发布时点的快照，改商品等于把这条分享换成另一件商品的评价 —— 那应该「删掉重发」）。`topics` 的**三态必须区分**：`null` = 不改 / `[]` = 清空 / 数组 = 替换 —— 混淆「不改」与「清空」会让用户**永远删不掉话题**（前端单测专门锁死这条）。
- 举报：`t_share_report`（SHARE 与 COMMENT 合成一张表，处理流程完全一致），唯一键 `(target_type, target_id, reporter_id)` 兜住并发重复提交；理由必填、不能举报自己、重复 409。**运营处理台**（`/admin/share-reports`，后台第 8 个页签）：列表**直接显示被举报内容摘要**（服务端 `targetExcerpt`）——要运营逐条点开才知道在举报什么的话，这个入口迟早没人用；弹窗**默认结论为「举报不成立」**（一次误点不会把别人的内容删掉）；裁定成立才下架（软删 + 回退话题 / 评论计数）。
- **明确不做**：自动判定（关键词 / 模型）与「被举报 N 次自动下架」—— 那需要误伤评估与申诉链路，是另一个量级的产品决策。

**④ 话题标签 `#`**
- `t_share_topic`（`name` 唯一 + `share_count`）+ `t_share_topic_rel`（主键即去重键）。发布 / 编辑最多 2 个；**服务端规范化是权威**：剥 `#`、去空白、去重、限长 30、非法名丢弃；**超上限是「截断」而不是「报错」**（用户多打一个词就发不出分享是糟糕体验）—— 故 DTO 上刻意**不加** `@Size(max = 2)`（注解会把截断变成 400，让服务层的截断逻辑永远不执行）。
- 「查不到就建」用 **INSERT 撞唯一键再回查**，不用「先 SELECT 再 INSERT」：两个用户同时首发同一个话题时，先查后插会让其中一个拿到 500 —— 而「两人同时发同一个话题」本来就是正常行为。
- 端点：`GET /api/shares/topics?limit=`（热门榜，公开）、`GET /api/shares/topics/{name}`（聚合页，公开，白名单**单独放行 `/api/shares/topics/*`**）。话题聚合用 **JOIN 一条 SQL** 而不是「先查关系表拿 id 再 `selectBatchIds`」：后者在「命中的分享有一部分已被删除」时这一页会少几条，分页页数越翻越诡异。

**⑤ ★ 本轮抓到的三个真缺陷（都是探针/单测当场抓到，不是事后猜的）**
1. **新计数字段漏进数字白名单**：`commentCount` / `replyCount` / `shareCount` 未列入 `LongToStringJacksonModule.NUMERIC_LONG_FIELDS` → 以**字符串**下发，前端比较退化成字典序（`"10" < "9"`），「话题按使用次数排序」直接排错。规律很稳定：**新增任何「前端会拿去比大小 / 加减」的计数字段，必须同时加进白名单**（同 R44 的 likeCount、同 R50 的 record 计数）。
2. **DTO 注解与设计直接冲突**：`ShareCreateRequest.topics` 上的 `@Size(max = 2)` 会让「多带一个话题」变成 **400 整单失败**，与「截断而非报错」的设计矛盾 —— 服务层的截断逻辑因此**永远不被执行**（写得漂亮但不可达的代码）。
3. **新建路径的回执缺昵称**：评论创建时把 briefs 传成了空表 → 回执里昵称是「用户3612…」这种长号，与随后重拉列表的显示不一致（用户视角是「刚发的评论显示错了」）。

**验证**：
- 新增 `scripts/r56-share-circle-smoke.py` **74 PASS / 0 FAIL**（A 话题 12 + B 评论与回复 15 + C 删除与编辑 18 + D 举报与运营处理 19 + E 关注流 7；含「原帖删除后转发单 `parentDeleted=true` 且不再下发原文」「关注流为空 ≠ 全站」「评论下架后 commentCount 回退」「非运营 403 / 重复处理 409」）。
- 新增 `scripts/r56-share-comment-test.js` **74 PASS / 0 FAIL**（真实加载 `api.js` + 四个页面 js，桩掉 `wx.request` 断言**前端实际发出了什么**：编辑话题三态、评论/回复/举报请求体、路径形状、19 位 id 未被 `Number()` 破坏、五处胶囊四条齐备、`MiniProgramPages` 与 `app.json` 一一对应；含反向验证「改坏 line-height 能被抓出」）。
- 新增 `scripts/e2e/share-report-ui-check.mjs` **24 PASS / 0 FAIL**（真实 Chromium 走运营处理台：列表显示摘要 / 弹窗默认**不**选中「下架」/ 说明必填 / 驳回后该行从「待处理」消失 / **驳回不误删内容** / 非运营看到准入提示；截图 `frontend/docs/screenshots/report_*.png`）。
- **回归 614 项全过** —— 接口探针 385（r55 42 · r54 26 · r53b 26 · r53 28 · r53-hotfix-chat 17 · r52 57 · r50 18 · r49 15 · share-circle 39 · social 43）+ 前端单测 229（角标 59 · r54 文案 23 · r56 74 · 请求体精度 19 · 闲置筛选 42 · 时间换算 12）。
- `scripts/check-miniprogram.js` **0 错误 / 0 告警**，并**修掉一处误报**：检查器原先靠一份硬编码别名表识别 `wx:for-item="tag"` 这类循环变量，漏一个就对**合法写法**报「字段可疑」；改为**从 WXML 里实际读出声明的别名**，并做两轮反向验证（裸标识符拼错仍能抓到 / 删掉声明后立刻重新报警 —— 证明确实是「读声明」而不是「一律放行」）。
- 前端 `tsc --noEmit` 零错误、`vite build` 通过（84 模块）。
- 9 容器健康。**改了共用模块 `duoduo-common-web`，8 个业务镜像全部重打**（R52 教训：只重建「改动的那个服务」会留下内嵌旧版 `common-web` 的容器），并解包校验产物里确实含新白名单字段。

**R55（2026-09-29，用户要求：暂缓担保交易与微信一键登录，优先「完善通知功能」）** — **收到他人消息时的数字未读角标 + 首页卡片式弹窗；通知体系从「零生产者」补成完整链路**。

**① 真正的缺口不是界面，是「没有生产者」**
- **实测结论**：`t_social_notice` 全库只有 2 处种子共 **3 条公告**；全仓搜 `internal/notices` **零命中** —— 设计稿里「订单 / 物流 / 售后各域推通知」的端点**从未实现**；`MessageService` 里搜 notice 也零命中 → **收到私信不产生任何通知**。小程序端的通知页、角标、已读 R50 就已就绪，但列表里**永远只有 3 条公告**。
- **新增内部端点**：`POST /api/socials/internal/notices`（批量推送）+ `POST /api/socials/internal/notices/announce`，由 `InternalTokenFilter` 保护（`/api/socials/internal/**`，**只写不读、不进网关**）。
- **两阶段落库**：先全校验（分类白名单 / 长度 / `jump_page` 真实性）再统一 insert —— 第 N 条非法则**整批不写**（失败 = 什么都没发生）。
- **★ `jump_page` 白名单**：`MiniProgramPages` 与 `app.json` 的 **28 个页面路径一一对应**，只校验路径段（query 不参与）。拼错路径**编译期不报错、运行日志也不报错**，只在用户点击时表现为「点了没反应」，故必须硬校验。
- **trade 域推 ORDER 通知**：下单 / 付款 / 发货 / 退款**四处**经 Feign 推送（`SocialNoticeClient`，**必配唯一 `contextId`** —— 沿用 R53「同名 Feign 客户端启动即炸」的教训）；一律用 `@TransactionalEventListener(phase = AFTER_COMMIT)`：事务没提交完就推，用户点进落地页会**查不到订单**。
- **fulfilment 域推售后与签收**：售后立案 / 同意 / 拒绝 / 升级 / 运营裁定（支持与驳回）**六处** + **物流签收**（只推 SIGNED）。签收的推送判据是「**本次真的写进去了**」（按首次 insert 成功推送），被唯一索引挡掉的重推**不重复推**。
- **best-effort 旁路纪律**：通知写入失败只 `log.warn`，**绝不影响主流程** —— 消息 / 订单此时已落库，若因附属写入把主流程判成失败，用户看到的「失败」与事实相反，会去重发。

**② ★ 私信不能被数两次（本轮实测发现的真实设计缺陷）**
- **现象**：合并未读角标显示 **7**，实际只有 **5** 条未读。
- **根因**：私信到达时会写一条 `bizType=MESSAGE` 的通知（作为「有人找你」的引导），而**同一批私信又按条计入私信未读**（`t_social_message.read_at`）→ **一条消息被数成 2**。
- **修法**：`NoticeService.unreadCount` **排除** `bizType=MESSAGE` 的通知行，合并口径固定为「**通知（不含私信）+ 私信未读**」；该通知行**仍留在列表里**当入口。
- **★ 顺带发现的 SQL 坑**：`biz_type <> 'MESSAGE'` 对 `biz_type IS NULL` 的行返回 **NULL（即不匹配）**，而**全站公告的 `biz_type` 恰好是 NULL** —— 少写那个分支会把**整类公告漏掉**。故写成 `biz_type IS NULL OR biz_type <> 'MESSAGE'`。

**③ 未读角标此前只在通知页刷新（所以别处看不见数字）**
- **实测**：全仓 `setTabBarBadge` **唯一调用点**是 `notification.js`，由该页 `onShow` 触发 → 用户在首页 / 分享圈 / 我的页面**完全看不到数字**。
- **修法**：收敛到唯一出口 `utils/unread.js`，新增 **4 类刷新点** —— `app.onShow`（冷启 + 回前台）+ 首页 / 分享圈 / 我的三个 tab 的 `onShow` + **60s 轮询**（`app.onHide` 清理定时器）。并发请求**单飞**（复用 `inflight`）；**请求失败返回 `-1` 而不是 `0`**，避免「网络抖一下就把未读提示抹掉」。
- **99 封顶**：微信 tabBar 角标超过两位会被裁成省略号，故 `>99 → '99+'`。
- **静态规则新增第 8 条**：全仓禁止绕过 `utils/unread.js` 直接调 `setTabBarBadge`（error 级；匹配前**先剥注释** —— 注释里提到这个名字正是 R54 那次假阴性的来源）。

**④ 首页卡片式弹窗（新组件 `components/notice-toast`）**
- 顶部滑入小卡片，**4 秒自动收起**、可点击进入对应页面；**同来源 5 分钟冷却**（key 取 `bizType:bizId`，无 bizId 退化为 `notice:id`，最多记 50 条），避免同一会话连发消息反复弹。
- z-index **900**（高于自绘导航栏的 200）；`detached` 时清定时器（否则页面销毁后定时器仍会回调）。

**⑤ 通知页分类与分页 + AI 契约死代码修复**
- 通知页新增**分类 chip**（全部 / 系统 / 公告 / 好友 / 商家 / 订单）与**触底分页**，`pageSize` 默认 20；空态文案按分类变化。
- 顺带修 `api.ai.capabilities` **死代码**（`onLoad` 从未调用它 → 服务不可用时用户敲完发送才失败）：不可用时 `aiReady=false`，提示**推进消息流**（**不做流内浮层条** —— 消息区高度是按 `calc(100vh - …)` 算出来的，插块会把底部输入条挤出屏幕），输入框与发送按钮联动禁用。

**验证**：新增 `scripts/r55-notice-smoke.py` **42 PASS / 0 FAIL**（A 内部端点凭据与契约 10 + B 私信→通知 9 + C 合并口径 4 + D 已读幂等与越权 5 + E 订单通知 9 + F 物流签收 3）；新增 `scripts/r55-unread-badge-test.js` **59 PASS / 0 FAIL**（A 角标口径 17 + B 全局刷新点 12（含**全仓 `setTabBarBadge` 唯一出口扫描**）+ C 定时器生命周期 9 + D 卡片冷却 9 + E 跳转口径 4 + F 结构与胶囊 CSS 8）；`scripts/check-miniprogram.js` 新增**第 8 条规则**（角标唯一出口）后仍 **0 错误 / 0 告警**。**回归 342 项全过**：`r50` 18 · `r49` 15 · `r53-hotfix-chat` 17 · `r54` 26 · `share-circle` 39 · `social` 43 · `r52` 57 · `r53` 28 · `r53b` 26 · `api-id-payload` 19 · `short-time` 12 · `flea-mine-filter` 42。三容器（social / trade / fulfilment）重建至 healthy。

**R54（2026-09-29，用户真机验收发现）** — **成色筛选胶囊框大于文字 / 导航栏残留冗余通知入口 / 「浏览」与「想要」两个计数口径被混成一个**。

**① 成色筛选的胶囊背景框大于文字（纯样式问题）**
- **根因（`scroll-view` 内 flex 子项默认按容器高拉伸）**：`.fm-chip` 原来只有 `padding` 与 `border-radius`，**没有显式高度、也没定 `line-height`** —— 高度由字号 + 内边距撑出；而 `.fm-chips` 是 `display:flex` 且落在 `scroll-view` 里，flex 子项默认 `align-items:stretch` 会被拉伸到与容器等高 → **背景框比文字高出一截，且各排高矮不一**（用户截图里「全部成色 / 全新 / 九成新 / 良好可用」宽窄与高矮都不统一，就是这个原因）；圆角大于半高时视觉上还会从胶囊退化成「胖椭圆」。
- **修改位置**：`miniprogram/pages/flea-market/flea-market.wxss` —— `.fm-chips` 加 `align-items: center`（**关键：阻止子项按容器高拉伸**）；`.fm-chip` 改为**显式定高**（`height: 48rpx`）+ `line-height: 1`（高度与字号解耦，字号再变框不变）+ `flex: none`（不被压缩、不折行）+ `display:inline-flex; align-items:center; justify-content:center` + `padding: 0 20rpx` + `border-radius: 24rpx`（**恰好等于半高，两端是半圆才是标准胶囊**）+ `white-space: nowrap`。
- **★ 防复发**：`scripts/check-miniprogram.js` 规则 7 增补 3 条**胶囊约定**（error 级）—— `.fm-chip` 必须有显式 `height`、必须 `line-height:1`、`.fm-chips` 必须 `align-items:center`；并用「故意删掉」反向验证，3 处全部命中。

**② 导航栏右上角残留冗余通知图标**
- **根因（同一功能有两个入口）**：`components/nav-bar` 内置了一个右上角消息图标（`nb-msg`，💬），点击 `navigateTo` 到 `/pages/notification/notification` —— 而该页**已经是 tabBar 的居中一项**（R50 重排后）。两个入口指向同一页面，用户会以为是两个不同功能。
- **修改位置**：`components/nav-bar/nav-bar.wxml` 删除 `.nb-msg` 图标块（`nb-right` 只留 `<slot name="right">` 插槽）、`nav-bar.js` 删 `msg` 属性与 `goNotice()` 方法、`nav-bar.wxss` 删 `.nb-msg` / `.nb-msg-hover` / `.nb-msg-icon` 三条规则；`pages/chat`、`pages/login`、`pages/notification` 三处 wxml 去掉 `msg="{{false}}"` 传参。
- **★ 只删导航栏那个，tabBar 入口必须保留**：删掉 tabBar 项会让通知页**再也进不去**（且不会报任何编译错误）。故新增页面级单测 `scripts/r54-miniprogram-copy-test.js` 的 A8 断言「tabBar 通知入口仍在」，并做反向验证（故意删掉 tabBar 项时检查确实报错）。

**③ 「多少人想要」与「浏览量」是两件事，此前被混成一个**
- **用户口径**：① **浏览** = 页面每被打开或刷新一次就 +1，**同一用户多次查看可累加**；② **想要** = 只按**咨询行为**统计，买家**首次**向卖家发咨询消息计入 1 人，同一买家后续再发消息不重复计数（**每位买家只记 1 次**）。
- **根因**：卡片上只显示一个 `viewCount`，文案却写作「N 人看过」—— 把可累加的**次数**说成**人数**；而「想要」根本没有独立的数据结构，也就不存在去重口径。
- **修改位置（后端）**：
  - **DDL**：`t_listing` 新增 `want_count INT NOT NULL DEFAULT 0`；新增**去重表** `t_listing_want (listing_id, buyer_id)`，**主键就是 `(listing_id, buyer_id)`**（增量脚本 `docs/sql/incremental/18-r54-listing-want.sql`，全量 DDL `docs/sql/02-duoduo-product.sql` 同步）。
  - **★ 去重必须落在数据库唯一键**：应用层「先查有没有、没有再加一」在并发下会双写；`ListingMapper.insertWantIgnore`（`INSERT IGNORE`）**只有真插进去（返回 1）才给 `want_count` 加 1**，天然幂等。`ListingService.markWant(listingId, buyerId)` 会先校验闲置存在（去重表无外键，防孤儿行），**重复上报返回 `false` 而不报错**。
  - **新端点**：`POST /api/listings/internal/{id}/want?buyerId=`（product 域，内部凭据保护）。**用 POST 而不是 GET**：这是一次**写**（登记 + 计数），GET 会被预取 / 重试 / 爬虫无意触发，把计数刷歪且难以归因。
  - **social 域**：`MessageService.send()` 在消息落库后、且 `bizType=LISTING` 时 best-effort 调 `markWant` —— **异常只 `log.warn`**：消息已经发出去、真实状态就是「已发送」，为一个附属统计把发送判成失败，会让用户看到与真实状态相反的提示。
  - **三处出参**（`ListingCard` / `ListingMineView` / `ListingOwnerView`）统一补 `wantCount`。
- **修改位置（前端）**：`pages/flea-market`、`pages/flea-detail`、`pages/flea-mine` 三处文案改为「**N 次浏览**」+「**N 人想要**」（两个计数并列展示）；`pages/flea-detail/flea-detail.json` 开启 `enablePullDownRefresh` + `backgroundTextStyle: dark`，`flea-detail.js` 新增 `onPullDownRefresh()` 走**静默重拉**（不切加载态、不闪屏、无 id 不发请求）→ 让「**刷新也算一次浏览**」真正落地。

**验证**：新增 `scripts/r54-listing-want-smoke.py` **26 PASS / 0 FAIL**（W 想要 20 项：初始 0 / 首次咨询 +1 / **同一买家重复咨询仍为 1** / **同会话 TEXT 不计** / 不同买家各计一次 / 详情·我的闲置·公开列表三处一致 / 内部端点无凭据 403 / 首次 `counted=true`、重复 `counted=false` 幂等 / 不存在闲置 404；V 浏览 4 项：**逐次 1→2→3 可累加** / 咨询不增浏览）；新增 `scripts/r54-miniprogram-copy-test.js` **23 PASS / 0 FAIL**（A 通知入口去重 9 项含 tabBar 入口仍在；B 计数文案 6 项；C 下拉刷新行为 8 项含「刷新重新请求详情」「静默不切加载态」「无 id 不发请求」）。回归全绿：`r53b` 26/26、`r53` 28/28、`r52` 57/57、`r53-hotfix-chat` 17/17、`api-id-payload-test.js` 19/19、`flea-mine-filter-test.js` 42/42、`short-time-test.js` 12/12（Asia/Shanghai）、静态自检 0 错 0 警；product / social 编译 0 错、镜像 Built、双容器 healthy；DDL 已落库并校验（`want_count int 0` + `t_listing_want` 主键 `(listing_id, buyer_id)`）。
**★ 本轮最重要的一条工程教训（假阴性比不检查更危险）**：规则 7 首版用正则匹配 CSS 声明时，**匹配到了注释里写出的同名声明** —— `.fm-chip` 的注释里写着「由 height 定高、line-height:1 让文字不额外撑行」、`.fm-chips` 的注释里写着「★ align-items:center 是必须的」，于是**故意把这几条真声明删掉后检查依然报 0 错**，给出「已验证」的假象。修法：新增 `stripCssComments()`，**先剥 CSS 注释再匹配**；重跑反向验证，3 处全部命中。
**真机验收（2026-09-29）**：用户在微信开发者工具点过一遍 —— 跳蚤市场 4 页 + 详情页新功能 + 近两轮修复（含本轮三修），**确认无问题，本轮收尾**。

**R53 热修二（2026-09-29，用户真机验收发现）** — **按钮文字折行 / 浏览量恒为 0 / 一个闲置同时出现在两个状态标签下**。

**① 按钮文字折行、尺寸忽大忽小（纯样式问题）**
- **根因**：小程序 `button` 组件**自带 28rpx/侧内边距**，与 `flex:1` 一起工作时文字框被挤窄，「加入购物车」「立即购买」就折成两行；「我的闲置」页筛选条 6 个标签**没有 `flex:none`**，被容器压缩后「审核中 / 审核失败 / 已下架 / 已售出」全部折行 —— 行数不同即行高不同，用户视角就是「按钮忽大忽小、与文字不协调」。
- **修改位置**：`miniprogram/app.wxss`（`.btn` / `.btn-mini` 加 `white-space: nowrap`，全局兜底）；`pages/product/product.wxss`（`.footer-share` 定宽 `120rpx`+`flex:none`、`.footer-btn` `flex:1`+`height:76rpx`+`nowrap`）；`pages/flea-detail/flea-detail.wxss`（`.fd-foot-buy` `flex:none`+`padding:0 28rpx`）；`pages/flea-mine/flea-mine.wxss`（筛选条改**不换行 + 横向滚动** `overflow-x:auto`，各成员 `flex:none`+`height:56rpx`+`nowrap`；徽章 / 操作按钮 / 时间同加 `flex:none`+`nowrap`，操作区 `flex-wrap:wrap`）。
- **取舍**：长文案不再靠折行容纳，而是**挤不下就横向滚动**（不压缩、不折行），保证所有按钮同一高度。

**② 浏览量恒为 0**
- **根因（一句话：从来没有人给它加过 1）**：全仓搜索 `incrementView|view_count = view_count|viewCount + 1` 在 `duoduo-*/src/main/` **结果为空** —— `t_listing.view_count` 只有字段定义、DTO 出参与种子数据，**没有任何写入代码**，所以永远是初始值。
- **修改位置**：`duoduo-product/.../mapper/ListingMapper.java` 新增原子自增 `@Update("UPDATE t_listing SET view_count = view_count + 1 WHERE id = #{id}")`（读-改-写会在并发下丢更新，计数必须走数据库侧原子自增）；`ListingService.detail(Long)` 在**可见性校验之后、装配返回之前**自增并把新值回填进本次响应（**首次查看即显示 1**，不必等第二次）。两处刻意取舍：只在公开详情端点 `GET /api/listings/{id}` 计数，`ownerDetail()` 不计（卖家自己看不该刷量）；自增异常只 `log.warn` 不阻断浏览（看商品不该因统计失败而失败）。前端 `pages/flea-market/flea-market.js` 的 `onTapItem` 置 `this._needRefresh = true`，返回列表时 `onShow` 重拉 —— 否则用户看完回来仍是旧计数，会以为统计没生效。

**③ 一个闲置同时出现在「审核中」和「在售」**
- **根因（前端拼参数漏了一个维度）**：服务端 `status`（在售/下架/已售）与 `auditStatus`（待审/通过/驳回）是**正交两维**（R53 原设计如此），新发布条目天然是 `ON_SALE + PENDING`；而小程序「在售」标签**只传了 `status=ON_SALE`、没带 `auditStatus=APPROVED`** → 待审条目被同时捞进「在售」。本轮按用户口径改为**互斥单状态**：一个商品只属于一个标签。
- **修改位置**：`pages/flea-mine/flea-mine.js` 新增 `FILTER_PARAMS` 构成**不重不漏**的划分 —— 审核两标签**只带 `auditStatus`、不带 `status`**（否则会漏掉被驳回时服务端同步落的 `OFF_SHELF` 件）；交易三标签**必须同时带 `auditStatus=APPROVED`**（未过审不算真正上架，驳回件也不等于卖家主动下架）；`load()` 改为按标签拼参数。同文件 `statusOf()`/`decorate()` 把卡片徽章由「审核态 + 交易态两个」改为**单一状态**（审核态优先，未过审显示「审核中」而非「在售」），上架/下架/重提三个按钮权限全部从同一对字段推导，杜绝「徽章说在售、却给了上架按钮」的错配；`onToggleStatus` 的就地更新改为重走 `decorate`。`flea-mine.wxml` 徽章由两个 `<text>` 改成一个。**服务端无需改动**（`mine/list` 本就支持两维组合）—— 这是纯粹的前端缺陷，故新增单测直接断言前端拼出的 URL。

**验证**：新增 `scripts/r53b-listing-view-status-smoke.py` **26 PASS / 0 FAIL**（V 浏览量 10 项：初始 0 / 404 不计数 / owner 不计数 / 逐次 1→2→3 / 我的闲置与公开列表同步；S 状态互斥 16 项：5 标签各命中恰好 1 个 + 反例固定「只传 `status=ON_SALE` 会同时命中待审条目」）；新增 `scripts/flea-mine-filter-test.js` **42 PASS / 0 FAIL**（真实加载 `flea-mine.js`、桩 `wx.request`，断言 5 个标签**实际拼出的查询串** + 6 组徽章单一性 + 5 组按钮权限 + 就地更新一致性）；`scripts/check-miniprogram.js` 新增**规则 7（error 级）**「按钮文字禁止换行」—— 按钮基类必须 `white-space:nowrap`、横向排布成员必须 `flex:none`，并用「故意改坏」反向验证（6 处全部命中，证明规则不空转）。回归：R53 探针 28/28、R52 探针 57/57、聊天热修探针 17/17、`api-id-payload-test.js` 19/19、`short-time-test.js` 12/12（Asia/Shanghai）、静态自检 0 错 0 警。

**R53 热修（2026-09-29，用户真机验收发现）** — **聊天消息发不出去（404）+ 消息时间差 8 小时**。
用户报障三条：① 给官方客服发消息提示「内容已删除」；② 给跳蚤市场卖家私信（已关注）任何消息都发不出去、控制台 `POST /api/socials/messages 404`；③ 北京时间 12:33 发出的消息显示 04:33。**排查后确认 ①② 是同一个根因，共两处缺陷**：

- **① ② 根因：前端把雪花 id 强转成 JS 数字，精度丢失 → 服务端查无此人 404**。`miniprogram/utils/api.js` 的 `message.send` 里写着 `receiverId: Number(receiverId)`、`bizId: Number(bizId)`、`productId`、`listingId` 四处 `Number()` 强转 —— 而 id 是 18~19 位十进制，超出 JS Number 的 **53 位有效精度**：`Number('710000000000000001') === 710000000000000000`（末位被吞）。服务端按这个错值查库必然 `404 用户不存在`，前端再把 `NOT_FOUND` 文案化成「内容不存在或已删除」——用户视角就是「消息怎么都发不出去」。**为什么"之前能发、现在发不出"**：`ASK` / `LISTING` 的**首条**由服务端按商品/闲置归属定收件人（不信前端 receiverId），把缺陷掩盖了；一旦会话内落到 `TEXT`（沿用前端 receiverId）就必然 404。**修复**：四处 id 一律 `String(...)` 原样上行（后端字段是 `Long`，Jackson 对数字串自动转换），缺省字段不上行；`share.publish` 与 `share-detail` 的 `spuId` 同族隐患一并修掉。
- **③ 根因：前端把后端声明的时区标记删掉再裸切片**。后端时间契约本来就是 **UTC + `Z`**（`LocalDateTime.now(ZoneOffset.UTC)` 落库 + `toInstant(ZoneOffset.UTC).toString()` 下发，探针实测 `createdAt=2026-09-29T04:38:19Z`），而 `api.js` 的 `shortTime` 是 `String(iso).replace('T',' ').replace('Z','')` 后正则裸切 —— 等于把 UTC 挂钟时间当北京时间展示，**全站统一少 8 小时**。且该函数被 **13 个页面**共用（聊天 / 会话列表 / 订单详情 / 优惠券 / 秒杀 / 分享圈 / 通知 / 关注列表 / 售后 / 我的闲置），故一次性全修。**修复**：重写为「按 UTC 解析 → 转**设备本地时区**展示」，手写逐段解析（而非 `new Date(str)`）以绕开 iOS JavaScriptCore 对后端纳秒精度 ISO 串的解析差异；纯日期与非法串原样返回。
- **★ 防复发（本轮最有价值的产出）**：这类缺陷**接口探针永远测不到** —— Python 传的 id 天然是字符串。故新增三道防线：① `scripts/check-miniprogram.js` 规则 6（**error 级静态硬检查**）：禁止对 `xxxId` 做 `Number()` 强转；② `scripts/api-id-payload-test.js` **真实加载 `api.js`、桩掉 `wx.request` 断言出参**（19 项）；③ `scripts/short-time-test.js` 时间换算单测（12 项，含「非 UTC 时区下输出不得等于裸切片值」的反回归断言）。

**验证**：新增 `scripts/r53-hotfix-chat-smoke.py` **17 PASS / 0 FAIL**（A 咨询会话收发 7 项 + B 闲置咨询收发 5 项 + C 时间契约 4 项，含失败模式固定「精度丢失 id 必 404」）+ `api-id-payload-test.js` **19/19** + `short-time-test.js` **12 PASS（Asia/Shanghai）/ 10 PASS（UTC）**；R53 探针 **28/28**、R52 探针 **57/57** 回归全绿；静态自检 0 错 0 警。
**副产发现（已记录，非缺陷）**：雪花 id 有约 **1/64** 概率恰为 64 的倍数 —— 此时有效位数 ≤53，双精度可精确表示，`Number()` **不丢位**。故复现用例必须按前置条件分支，否则会写出「有时绿有时红」的假测试。

**最近更新：2026-09-28（第五十三轮）** — **闲置详情卖家卡与问一问 + 详情购买按钮（过渡态）+ 我的闲置审核态体系**。
本轮三项：详情页能看到卖家并一键咨询、购买按钮过渡态引导、驳回-修改-重提闭环。

**① 闲置详情页卖家信息卡 + 「我想要」（问一问）**。此前详情页只有卖家描述文字，没有卖家身份、没有咨询入口。本轮：
- 后端新增跨域事实源 `GET /api/listings/internal/cards`（product 域，`InternalTokenFilter` 保护，返回 `InternalListingView` 含 `sellerId`），social 域新增 `ListingClient`（Feign，`path=/api/listings`）+ `ListingCardBrief` 契约副本；「我想要」发送 `bizType=LISTING` 消息，**收件人由服务端按 `listing.sellerId` 决定**（不信前端），与 ASK 同款纪律：咨询会话豁免「非互关一条」、幽灵闲置 404、自己问自己 422。
- **★ 会话上下文段升级为 `ConvContext` record**：统一 none / product / listing 三态拼装（商品段 `:p{spuId}` 与闲置段 `:l{listingId}` 平行、不共存），替换原 `Long contextProduct` 与 `productOfKey` 双实现；`productId` 与 `listingId` 同时携带 400。会话列表 / 会话页 / 已读全部支持闲置卡（LISTING 卡 `type=LISTING`，前端按类型分流 `images.listingImage`，列表前缀 🏷️）。
- **★ Feign 同名服务多客户端必须 `contextId`（本轮最大的坑，启动即炸）**：`ListingClient` 与既有 `ProductClient` 同为 `@FeignClient(name="duoduo-product")`，Spring Cloud OpenFeign 会为同名 name 注册 `FeignClientSpecification`，第二个注册时 `BeanDefinitionOverrideException` 直接启动失败（容器 unhealthy）。修法：两个 client 都加唯一 `contextId`（`productCard`/`listingCard`）——contextId 只影响 bean 命名不影响服务发现。
- **★ 前端聚合防 401 副作用**：卖家卡数据源复用他人主页公开端点 `api.social.follow.profile(sellerId)`（零新增契约），但它落 `/api/socials/**` 白名单外需登录——免登访客逛详情页时若直接发请求会 401 → `request.js` 清登录态 → 触发全局静默重登/跳登录，把免登用户拽去登录。修法：`loadSeller` **无令牌不发请求**，直接占位「卖家」，登录用户才拉完整资料。
- 小程序 `flea-detail` 重写：卖家信息卡（头像/昵称/「查看卖家主页 · 信用与动态」）+ 底部双按钮（立即购买 / 我想要）；`isOwner`（自己看自己的闲置）时按钮区换成引导去「我的闲置」。

**② 详情页「立即购买」过渡态**。完整 C2C 担保交易（下单/锁库存/资金托管/放款）属设计稿 Batch-3，且设计稿 §12-6 明确**上线前必须核实微信类目资质**——本轮不做假下单：按钮弹「担保交易即将上线」modal 并引导「问问卖家」，PLAN.md 已登记边界与前置条件。

**③ 我的闲置审核态体系（正交双维度）**。`status`（在售/下架/已售）与 `auditStatus`（PENDING/APPROVED/REJECTED）**正交**，列表筛选从 4 项扩为 6 项：（※ 服务端这两维仍正交；**前端展示口径**已在 R53 热修二改为**互斥单状态**，见本文首段）
- `GET /api/listings/mine/list` 新增 `auditStatus` 参数（白名单校验，非法 400），可与 `status` 组合过滤；**审核驳回时 `status` 同步落 `OFF_SHELF`**（`audit()` 修改）——驳回条目不再混入「在售」筛选。
- 新增 `GET /api/listings/mine/{id}`（**卖家视角详情**，`ListingOwnerView` 全集字段含审核原因；归属校验 404/403 刻意区分，`requireOwned` 统一收口）与 `POST /api/listings/{id}/resubmit`（**重新提审**：仅 REJECTED 可调，PENDING=「正在审核中」、APPROVED=「已通过无需重提」均 422 拒绝；重提=内容更新 + `auditStatus→PENDING` + `auditReason→null` + `status→ON_SALE`，实名与违禁词全部复检——发布/重提共用 `normalizeForWrite()` 闸门）。
- 小程序：`flea-mine` 筛选 6 项 + 「修改并重新提审」按钮（驳回条目直接点卡片进编辑页）；`flea-publish` 支持编辑模式（`?id=X&mode=edit` 拉取 owner 详情回填表单，提交走 `resubmit`，价格分↔元换算复用 `api.fen2yuan`）。

**验证**：新增 `scripts/r53-listing-detail-mine-smoke.py` **28/28 PASS**（A 审核维度过滤 5 项 + M LISTING 问一问 8 项 + R 驳回→修改→重提闭环 15 项，含运营账号真实审核）；R52 回归 **57/57**；product/social 编译 0 错；小程序静态自检 0 错 0 警；双容器 healthy。**状态码口径已固定**：参数校验错 400，业务规则违例 422（探针 M6/R7/R12 首轮预期写错已修正——服务端行为一直正确）。

**最近更新：2026-09-30（第五十八轮）** — **资金闭环 + 测试底盘 + 闲置编辑删除 + AI 问候身份出口**。
本轮源自全项目未完成项盘点（22 项），按 A→B→C→D 顺序推进；AI 相关仅在盘点阶段记录、其后按用户指令实现 D 项。

**① 平台垫付与卖家追偿（补齐 B-01）**。已放款订单退款此前只有一句文案「需走追责或平台垫付（尚未实现）」，买家拿不到钱、运营无动作、系统不留痕。已补成闭环：
- 新增 `t_seller_debt`（**`uk_seller_debt_order` 唯一键即幂等键**，`INSERT IGNORE` 撞键即判重）与 `SellerDebtService.compensate()`；托管单新增 `RELEASED → ADVANCED`（`CUSTODY → RELEASED → ADVANCED` 是**新增边不是回退**，资金状态机不可逆）。
- **幂等判定必须前置**于状态校验：否则重复垫付会撞上「只有已完成订单可垫付」而报 422，提示指向的原因与实际不符。
- **垫付不回补库存**：垫付是资金动作，不是退货 —— 钱退了、货没退、库存还加回去，是三重错账。
- 运营端点：`POST /api/admin/settlements/compensate`（orderNo + reason，均必填）、`GET /api/admin/settlements/debts`、`POST /debts/{id}/settle`（收回）、`POST /debts/{id}/write-off`（核销），收回/核销二次调用 409。

**② 退货入库幂等（补齐 B-02）**。新增 `t_sku_restock_log(ref_no, sku_id)`（**主键即去重键**），`StockChangeRequest` 增 `refNo`，交易域退货传 `restock:{orderId}`。重复提交时**如实返回「本轮加回 0 件」** —— 原实现会把上次的数量再说一遍，等于谎报回补。

**③ 闲置编辑与删除（补齐 B-14）**。新增 `POST /api/listings/{id}/edit` 与 `POST /api/listings/{id}/delete`：
- **路径必须三段**。网关白名单是 `/api/listings/*`（两段、**不区分 HTTP 方法**），写成两段（哪怕是 `PUT` / `DELETE`）就把写端点放成匿名可调 —— 探针用「匿名读详情放行」作对照组，证明三段被挡不是空转断言。
- **删除是软删**（`t_listing.deleted_at`，行留库供订单 / 会话 / 举报追溯）：`visibleWrapper()`、`detail()`、`requireOwned()`、`listMine()`、`markWant()`、`internalCards()`、运营审核队列七处全部排除软删，ES 侧 `SearchIndexer.indexListing` 对软删直接摘文档。
- 编辑回到 `PENDING`（审核结论针对旧内容）；**驳回件编辑后恢复 `ON_SALE`** —— 驳回时 `status` 已被压成 `OFF_SHELF`，而「先驳回再自己上架」被「上架需已过审」堵死，不在编辑里恢复就会出现「通过审核仍不可见且无入口放出来」。

**④ AI 问候 / 身份出口**。用户实测「你好，你是谁」返回「暂时没找到匹配的商品」——技术上没错（库里确实没有叫这名字的商品），但答非所问。已在 `ChatService` 补 ①b 出口（在「答不了」之后、召回之前）：
- 判据是「**剥掉问候词与语气词后还剩什么**」：剩空 = 问候；剩句命中**带「你」字**的身份问法 = 身份。只用「包含『你好』」当判据会把「你好，我想买猫粮」一起掐掉。
- 词表**刻意不含**「有什么功能 / 怎么用」：「这个猫爬架有什么功能」问的是商品不是助手；漏判只让用户多问一次，误判会让用户觉得助手听不懂人话，两者代价不对等。

**⑤ 测试与文档底盘**。交易域 +16、营销域 +9、AI 域 +11 项 JUnit；新增 `scripts/extract-api-contract.py` 从源码机械抽取端点写回 `docs/agent/04`（**241 个端点**，支持 `--check` 门禁，文档落后即退出码 1），接口契约不再手工维护。

**验证**：`r58-restock-idempotent-smoke.py` **10/0**、`r58-compensate-smoke.py` **22/0**、`r58-listing-edit-delete-smoke.py` **33/0**；AI 域单测 **40/0**（含反向验证：改坏词表后 4 项转红）；回归 `r54` 26、`r52` 57、`r53` 28、`r53b` 26、`settlement` 125 全绿；DDL 已落库校验；product / ai 双容器重建至 healthy。
三套新探针**均自带数据还原**：退货入库还原写的是**原始 `stock`** 而不是读到的「可售库存」（否则按 `frozen_stock` 每次把种子库存磨小 1）；垫付探针还原样本订单的 `COMPLETED|RELEASED` 并清欠款行（否则 `settlement-smoke` 的「已退款但托管单未终结」不变式会报出一笔来路不明的账）。相应地把该不变式改为允许 **`ADVANCED`**（平台垫付后的合法终态）。

**最近更新：2026-09-28（第五十二轮）** — **跳蚤市场发布链路补全 + 结算预览端点 + 405 修复**。
本轮源自第五十一轮小程序全流程验收报告的两条待修问题与一条验收建议（用户已采纳）。

**① 跳蚤市场补全发布链路（新增功能）**。此前商品域的闲置侧只有 2 个**只读**端点（列表 / 详情），没有发布、没有上下架，小程序端也没有跳蚤市场页面与入口 —— 即「只能看、不能发」。本轮补成完整 C2C 闭环：
- 后端新增 `POST /api/listings`（发布）、`GET /api/listings/mine/list`（我的发布，可按状态筛）、`POST /api/listings/{id}/status`（上架 / 下架）；新增 3 个 record：`ListingPublishRequest`、`ListingStatusRequest`、`ListingMineView`。
- `ListingService` 新增发布链路，校验顺序：**实名校验**（`real_name` 非空，未实名 403）→ 违禁词 → 单卖家条数限流（默认 200，可配）→ 成色默认 `GOOD` → 图片去空去重限 9 张 → 价格上限 99999 元。新发布一律落 `PENDING` 待审核，**不能自审**。
- 上下架是**幂等开关**（不设严格状态机）：`ON_SALE ⇄ OFF_SHELF` 都接受，`SOLD` 不可复活；从下架重新上架时要求 `auditStatus == APPROVED`（未过审 422）。
- **★ 安全设计就是路径本身**。网关白名单**按路径匹配、不区分 HTTP 方法**，所以「同一路径既有免登读又有需登录写」不可能共存 —— 若按直觉写 `/api/listings/**`，会把三条写端点一并放行成「匿名 UGC 直写入库」。因此采用与 `SharesService` 同款的**两段式路径隔离**：`/api/listings/search`（两段）与 `/api/listings/*`（两段，Ant 的 `*` **不跨 `/`**）免登只读；三条写路径分别是单段 `POST /api/listings` 与三段 `/api/listings/mine/list`、`/api/listings/{id}/status`，天然落不进白名单。`sellerId` / `status` / `auditStatus` **刻意不在请求体**，全由服务端决定 —— 探针实测传 `auditStatus=APPROVED` 与 `sellerId` 均被忽略。
- 详情可见性硬规则：只暴露 `status=ON_SALE 且 auditStatus=APPROVED`，否则 **404**（不是 403 —— 避免用状态码探测存在性）。
- 小程序新增 4 页（跳蚤市场列表 / 发布 / 我的闲置 / 闲置详情，16 个文件）+ 首页「跳蚤市场」入口 + 我的页「我的闲置」菜单；`api.js` 加 `listing` 命名空间，`images.js` 加 `listingImage()`（复用商品图池，省主包 2MB 额度）；新增手绘 `flea.png` 图标（纯 Python `zlib`+`struct` 生成 108×108 购物篮，318 字节，品牌色 `#4f6ef7`）。发布页含显式 `yuanToFen()` 元→分换算（正则校验，**不用 `parseFloat` 静默容忍脏输入**）；我的闲置页显示审核态徽章三色与**驳回原因**，上下架按钮用 `catchtap` 阻止冒泡。跳蚤市场走**原生导航栏**（非 tab 页必须有返回键，而 tabBar 硬上限 5 项已满）。

**② 结算预览端点缺失（中等）**。`POST /api/orders/preview` 此前不存在，带令牌请求回 500。已补端点，返回按卖家拆单后的 items / 商品总额 / 运费 / 券后应付。
- **★ 关键取舍：券试算必须走只读路径**。新增 `POST /api/coupons/internal/trial` 与 `marketingGateway.trial()`，**绝不复用 `lock`** —— `lock` 是跨服务**写**（真占券），若预览调它，用户每改一次数量就被占一次券，最后没提交还得干等 TTL 解锁，会把券白白锁死。`lock` 与 `trial` 共用同一份 `precheck()` 校验（存在 / 归属 / 状态 / 过期 / 未锁 / 门槛 / 抵扣），保证「预览能用」与「下单能用」结论一致。
- 券不可用（未达门槛等）**不抛错**，回 `couponMessage` 让用户知情即可，提交时再严格校验一次；预览请求体**不含** `idempotentKey` 与收货信息 —— 预览不建单、不占券、不写任何状态（探针连调 3 次后订单数与券状态均不变）。
- 金额口径 `payAmount = max(0, totalAmount + freightAmount − discountAmount)`，本阶段运费固定 0，券只作用于**商品总额最大**的那单。**探针实测：预览金额 == 真实下单金额**（同源口径）。

**③ 方法错误回 500 而非 405（轻微）**。根因是 `HttpRequestMethodNotSupportedException` 未被 `GlobalExceptionHandler` 覆盖。已修：`ErrorCode` 新增 `METHOD_NOT_ALLOWED`(405)，处理器新增对应 `@ExceptionHandler`（与既有 `UNSUPPORTED_MEDIA_TYPE`(415) 对称，日志记 requestId + method）。
- **★ 本轮最重要的坑（已写入踩坑档案）**：`duoduo-common-web` 是**多服务共用模块**，但首轮只重建了 product/trade/marketing/gateway 四个服务 —— **user / fulfilment / ai / social 四个模块 jar 内嵌的 `duoduo-common-web` 仍是旧版**（`PUT /api/users/me` 仍回 500）。用 Python `zipfile` 逐层解包核实「含 405 异常引用 = False」定位；随后 `mvn -pl ... -am install` **仅耗时 9 秒且跳过打包**（增量检测认为无需重打，jar 时间戳仍是 9-27）。最终改用 `mvn -q -o -DskipTests clean package -pl duoduo-user,duoduo-fulfilment,duoduo-ai,duoduo-social` 强制重打，四模块复检全为 `True`。**教训：改共用模块必须重建全部依赖方，且要验证构建产物内容而非只看退出码。**
- 已知语义（非缺陷，已在探针里固定契约）：`GET /api/orders/preview` 会被 `@GetMapping("/{orderNo}")` 捕获成订单号 `preview` → 回 404 而非 405。这是 Spring「只在**方法相同**时精确路径才优先于变量路径」的固有行为；`POST /preview` 与 `GET /{orderNo}` 方法不同故不冲突。

**验证**：新增 `scripts/r52-listing-preview-smoke.py` **57/57 PASS**（L 发布链路 14 项 + P 结算预览 13 项 + M 方法不支持 8 项）；扩展 `scripts/r51-full-acceptance.py` **155/155 PASS · 0 FAIL**（新增实名发布闭环 I8/I8b~I8h、预览口径自洽 C4/C4b/C4c、405 断言 H3b/M6）。14 容器全部 healthy。

**R52 热修（同日，用户真机验收发现）**：用户在开发者工具里发布闲置报 403「没有权限执行该操作」。排查链路：401 是没登录、403 是已登录但没资格 —— 后端 `publish` 的第一道校验就是**实名**（`real_name` 非空），而该账号 `real_name=NULL`。**根因是三个前端缺口叠加**：① 小程序**没有任何实名认证页面**（后端 `POST /api/users/me/realname` 一直就绪，移动端却无处可去）；② `flea-publish` 的 onLoad 注释写着「登录 + 实名两道都提前拦」，实际只拦了登录；③ `request.js` 的 `friendlyMessage` 把所有 `FORBIDDEN` 一刀切覆盖成通用文案，把后端可行动的提示「该操作需要先完成实名认证」吞掉了。修复：新增 `pages/realname` 4 件套（表单校验与后端 `@Pattern` 同口径：2-20 位中文姓名 + 18 位身份证号；**提交成功后立即 `refreshSession()` 静默换新令牌** —— 因为 `realNameVerified` 写在访问令牌 claim 里，而后端 refresh 会重读库签发新令牌，换完即发，不必强制重登；刷新失败才退回重新登录）；`flea-publish` 加实名预检（未实名显示引导卡替代表单，onShow 复查自动解锁）；`request.js` 的 FORBIDDEN 改为透传后端文案；`profile` 加「实名认证」菜单入口；`app.json` 28 页。**验证**：端到端探针 6/6（未实名 403+文案 → 实名 → 旧令牌仍 403 → 静默换令牌 → 发布 200 `auditStatus=PENDING` → 未过审公开详情 404）+ r52 回归 57/57 + 静态自检 0 错误。沙箱口径：实名只做格式校验，不接公安服务，身份证号仅存脱敏掩码。

**上线后需你确认（小程序侧，Agent 无法代做）**：跳蚤市场 4 个页面的真机/模拟器验收 —— 首页点「跳蚤市场」→ 发布一条闲置（含图片）→ 我的闲置看到 `待审核` → 下架 → 详情页正确显示「同款全新」推荐。

**第五十一轮（2026-09-28）** — **小程序全流程验收（登录注册 / 微信登录 / 支付 / 退款 / 发货 / 退货 / 售后 / 个人中心 / 跳蚤市场 / 关注通知分享点赞收藏）**。产出《小程序全面排查报告》《小程序修复清单》与问题清单；本章程两处待修问题（结算预览缺失、方法错误回 500）与跳蚤市场发布链路建议均在本轮（五十二轮）闭环。

**最近更新：2026-09-28（第五十轮）** — **通知中心上线 + tabBar 重排 + 全页面通知入口 + record 计数器类型缺陷根治**。
① **tabBar 重排**：用户要求「分类」移出底部导航（首页已有分类入口），空位换成「通知」并置于**居中第 3 位** —— 首页/分享圈/**通知**/购物车/我的；分类降为普通页（`back` 返回键，首页「全部分类」入口仍在），全局排查 `switchTab` 引用与三处自维护 tab 清单（index/notification/profile）同步。② **通知中心**（启用 R47 已建齐的 `t_social_notice` + `t_social_notice_read`）：后端 `duoduo-social` 新增 `NoticeController` 4 端点（列表 / 未读数 / 单条已读 / 全部已读），**定向通知与全站公告统一为一个视图**；已读统一记在集合表（`(notice_id, user_id)` 复合主键，撞键即幂等）—— 表上行本就没有已读列，**零 DDL 变更**；他人通知标已读返 **404**（不区分「不存在/无权限」，防枚举）。小程序新增 `pages/notification` 4 件套（未读蓝点 / 点击标已读并跳落地页 / 全部已读 / tabBar 角标 `setTabBarBadge` / 私信未读合计入口），种子 3 条公告（增量 SQL `17-notification-center.sql` + `06` 种子同步）。③ **通知入口全页面显示**：`nav-bar` 组件内置右上角图标（由 R48 的 📩 改回**短信样式 💬**），并新增 `back` 返回键属性 —— 全部 22 个页面改用自绘导航栏（`navigationStyle: custom`），聊天页/AI 页整页高度按导航栏高度扣减（`calc(100vh - navTotal)`）。④ **★ 根治 record 组件计数器类型缺陷（R47 遗留）**：`LongToStringJacksonModule` 的字段名白名单只对**普通 POJO** 生效，Java `record` 组件的属性由 `POJOPropertiesCollector` 的 record 分支直接构造、不流经 `BeanPropertyWriter` 替换 —— 于是 `UnreadCount.count` 下发字符串 `"3"` 而同一接口族 `PageResult.total` 是数字 `3`，**同语义两种类型**（R47 的 `FollowCounts.followers/following`、R49 的 `ConversationView.unread` 同病）。修法：模块新增公开的 `NumericLongSerializer`，record 里的计数/金额字段显式标注 `@JsonSerialize(using = …)`，本域 **9 处**已标注。
**验证**：新增 `scripts/r50-notice-smoke.py` **18/18**（含 `count` 必须为 JSON 数字的类型守卫，作为该缺陷的回归防线）+ 社交域回归 `social-smoke` **43/43** + `r49-ask-thread-smoke` **15/15** + `r48-ask-smoke` **8/8** + CI 子集 `id-precision` **56/56** 与 `smoke` **55/55** + 小程序静态自检 **0 错误**；自建核对脚本验证 tabBar=5、全部页面 `navigationStyle:custom` ↔ `<nav-bar>` 一一对应、`wx.switchTab` 无指向非 tab 页的残留、三处 tab 清单与 `app.json` 一致。
**踩坑**：① 新增接口漏 `@Param` import、`.select(X::class)` 是 Kotlin 写法，Java 编译期即失败 —— 先编译再部署，别等运行时；② 通知表**没有** `read_at` 列（那是私信表的），设计前先 `SHOW CREATE TABLE` 对齐实况而不是照着 schema 记忆写 SQL。

**最近更新：2026-09-28（第四十九轮）** — **问一问会话按商品隔离**。
修复「同一卖家多商品咨询串线」：会话键从 `lo:hi` 扩展为商品咨询场景下的 **`lo:hi:p{spuId}`**（服务端拼装，前端不可伪造）；ASK 恒取 bizId 作商品段，TEXT 请求体新增可选 `productId` 落回商品会话；**商品咨询会话整体豁免「非互关一条」**，主会话语义不变；会话列表 `ConversationView` 增 `productId/productTitle/productImage`（🛒 商品副标题），`wx:key` 升级为会话级唯一。无 DDL 变更。新探针 `scripts/r49-ask-thread-smoke.py` **15/15** + `social-smoke.py` **43/43**（㉓㉗ 改版）+ r48 8/8 + 分享圈 39/39。踩坑：chat-list 以 peerId 作 wx:key 在同 peer 多会话时会撞 key，必须会话级唯一。

**第四十八轮（2026-09-27）** — **问一问全站打通 + 私信入口上移**。
「我的」页私信入口从底部菜单上移为**右上角 📩 图标**（`nav-bar` 组件开启 `multipleSlots` 新增右侧命名插槽，位置按胶囊按钮实算）。修复「问一问」报「该商品没有卖家」：根因是 13 个自营商品 `seller_id=0`（R47 设计即 422），增量 SQL 16 新建**官方客服**账号（id `710000000000000001`，手机 `13900000002`）并把自营商品批量归属，「问一问」语义变为咨询官方客服——后端零改动，复用全部私信链路；种子卖家与官方客服补包内头像（`images/avatars/`）。新增探针 `scripts/r48-ask-smoke.py` **8/8**，R47 回归 **42/42**（⑳ 改版），静态自检 0 错误。分享圈下一阶段设计稿见 [`docs/design/分享圈设计需求文档.md`](./docs/design/分享圈设计需求文档.md)（评论体系 + 跳蚤市场 C2C）。

**第四十七轮（2026-09-27 · 第一批）** — **社交域上线：关注 + 私信 + 问一问**。
新增第 8 个业务服务 `duoduo-social`（社交域，:8087；compose 服务名 `duoduo-social-app`，容器 `duoduo-app-social`，库 `duoduo_social`）：**① 关注**——`t_social_follow` 复合主键 `(follower_id, followee_id)`，互相关注=两个方向各一行（不引入状态列，双向同步是 bug 温床），重复关注/取关幂等，关注自己 422、幽灵用户 404，计数不冗余直接 COUNT；**② 私信**——`t_social_message` 会话键 `"lo:hi"`（双方 userId 小者在前，双向聊天一个索引）；**非互关每人限发一条**（服务端按会话计数判定，前端只消费 `canSend` 结果），互关后自由聊；卡片消息（PRODUCT）摘要由服务端去事实源现取（请求体只带 bizType+bizId，公开接口贴不出伪造卡片）；频控 60s/10 条（429）；**③ 问一问**——商品详情页「问一问」按钮直进与卖家的咨询会话：`bizType=ASK` 的收件人由**商品归属卖家**决定（不信前端传值），咨询会话永久豁免「一条」限制；发现种子商品 `seller_id` 全为 0（含 merchant 类型），增量 SQL 15 把 5 个 merchant 商品的卖家归属回填到种子卖家；**④ 小程序**——新 4 页（他人主页/关注与粉丝/会话列表/聊天页，全部非 tab + 原生导航，聊天页 4s HTTP 轮询 + 进入即标已读），分享圈作者区 → 他人主页，「我的」页加私信/关注入口。注意 `following` 既是计数也是关系词，`ProfileView` 关系字段用 `viewerFollowing/viewerFollowedBy` 避开 record 组件重名。
**验证**：新增 `social-smoke.py` **42/42**（关注 12 项 + 私信 30 项，含幂等/互关/一条限制/ASK 服务端定人/豁免/频控/已读）+ 分享圈探针回归 **39/39** + 静态自检 0 错误 + wxml↔js 处理器核对全过；social/gateway/user/product 四容器重建健康（user/product jar 重打包带 `/internal/roles` 与 `InternalCardView.sellerId`），SQL 06/15 已落库。踩坑记录：Feign 必须带 `spring-cloud-starter-loadbalancer`，且内部接口必须挂 `X-Internal-Token` 拦截器——下游「取不到资料就降级为空」的容错会把 403 吞成业务 404，极难排查。

**第四十六轮（2026-09-27）** — **分享圈转发落库 + 转发身份修正**。
用户报两问题：① 转发他人分享后，转发动态没出现在分享圈列表；② 转发后看分享详情页，仍显示原发帖人自己的页面。**两问同源**：第四十四轮的「转发」只是 `open-type="share"` 调起微信原生面板、**从不写后端**——列表里自然没有新动态，卡片落点 `path` 带的是**原分享单 ID**，详情页只能按原发帖人渲染；数据模型里也没有「转发」这一层。
**修复**：把「转发」建成数据模型里的一层——`t_product_share` 增 `parent_share_id`（NULL=原创）+ 唯一键 `uk_share_user_parent (user_id, parent_share_id)`；新增 `POST /api/shares/{id}/repost`（需登录）插一条**以转发人为作者**的新分享行，商品快照**沿用原帖**，自转 422 / 重复转发 409 / 幽灵原帖 404；`ShareView` 增 `userId` + `shareType/parentId/parentNickname/parentContent`，并明确 `userId/nickname/avatar` 恒是「本条作者」（转发即转发人）；原帖引用按 `loadContext` 一次批量取（无 N+1）。前端「转发」改为**转发弹层**（附言 + 「转发到分享圈」写接口 + 「分享给好友」走原生、落点用**转发单 ID**），信息流/详情页新增「转发自 @原作者」+ 原评价引用块，详情页转发后 `redirectTo` 转发单详情。
**验证**：后端探针 `share-circle-smoke.py` **39/39** + 小程序 stub **16/16** + 静态自检 0 错误；fulfilment/gateway 容器重建健康，增量 SQL `14-share-repost.sql` 已执行。

**第四十五轮（2026-09-27 · 热修）** — **模拟器启动失败修复：tabBar 超 5 项**。
根因：微信 `tabBar.list` 硬上限 5 项，第四十四轮加分享圈成 6 项，`app.json` 校验不过（`["tabBar"]["list"] 不能超过 5 项`），工具拒绝启动模拟器。修复：tabBar 收敛为 **首页/分类/分享圈/购物车/我的**（订单 tab 移除，「我的 → 我的订单」入口替代）；orders 恢复原生导航栏（非 tab 页需返回键）；cart/after-sales/checkout/seckill 四处 `switchTab(orders)` 改 `navigateTo/redirectTo`；index/profile 的 tab 页清单同步。验证：app.json 合法且 tabBar=5、7 个 js 语法全过、switchTab 残留清零、静态自检 0 错误。

**第四十四轮（2026-09-26）** — **分享静默登录根治 + 分享圈升一级 tab + 点赞/收藏/转发**。
**Bug 根治**：未登录点分享"自动登录到不明账号"的根因是 `ensureLogin()` 未登录时走 `silentRelogin() → wx.login` 静默 code2Session（无感建号/登录到微信绑定账号），分享拦截被静默登录穿透。新增 `requireLogin()`（探测失败**直接去登录页**，不走静默链），分享/点赞/收藏等"以用户身份对外发声"的动作全部改用它；`ensureLogin` 静默链保留给加购/领券等本机动作。
**分享圈升一级 tab**：tabBar 第 6 项（生成 `tab_share` 双态图标）；页面改自绘导航栏 + 自绘下拉刷新（R41 惯例）；「我的」页入口移除；跳转收口 `switchTab`。
**互动能力**：`t_share_like`/`t_share_favorite`（复合主键防重）+ `t_product_share` 冗余计数列 + `POST /api/shares/{id}/like|favorite` toggle（需登录，两段路径不在白名单）；`ShareView` 增 likeCount/favoriteCount + 浏览者标记（匿名恒 false）；`likeCount/favoriteCount/count` 补进 Long 序列化数值白名单（计数被当标识转字符串会让前端比较/算术退化）。卡片互动条 ❤️/⭐/🔁：toggle 后回填状态与计数；转发按钮 `open-type=share` + `markForward` 记条目，卡片落点 `share-detail?id=`（**第四十六轮已改为转发弹层 + 写接口，见上**）。
**验证**：后端探针 `share-circle-smoke.py` 20/20 + 小程序 stub 12/12（未登录点分享**全程零次 wx.login**）+ 静态自检 0 错误；fulfilment 容器重建健康，增量 SQL `13-share-engagement.sql` 已执行。

**第四十三轮（2026-09-26）** — **分享两 Bug 修复 + 分享圈新功能**。
**Bug1 分享登录校验**：详情页「分享」从直接 `open-type="share"` 改为普通按钮过登录门（未登录 `ensureLogin` 引导登录）——微信转发面板只能由用户点按触发、无法程序化调起，登录校验只能做在"进入分享流程"这道门上；已登录弹出**分享面板**（评价 textarea → 转发好友 / 发布到分享圈两路）。
**Bug2 查看分享页无响应根治**：根因是分享 `path` 指向商品页自身，点卡片=刷新原页。新建独立落地页 `pages/share-detail`（`?spuId=` 好友直转 / `?id=` 分享单双入口，评价 + 商品卡 + 跳详情），分享落点全部改指它。
**新功能分享圈（参考拼多多「拼小圈」）**：履约域新增 `t_product_share` + `POST /api/shares`（需登录）/ `GET /api/shares/feed` / `GET /api/shares/{id}`（公开读）三端点；商品快照由服务端经商品域新内部接口 `/api/products/internal/cards` 取事实落库（请求体只带 spuId+content，公开信息流无法贴伪造商品卡）；昵称头像 Feign 批量补全；网关路由补 `/api/shares/**`、白名单只放两条只读路径（feed 两段路径与发布路径分开，杜绝连带放行）。小程序新建 `pages/share-circle` 信息流（下拉刷新/触底分页，公开可逛）+「我的」页入口。
**验证**：后端探针 `share-circle-smoke.py` 12/12（含未登录 401 / 快照 / 幽灵商品 404）+ 小程序 stub 17/17 + 静态自检 0 错误；product/fulfilment/gateway 三容器重建健康，增量 SQL `12-share-circle.sql` 已执行。

**第四十二轮（2026-09-26）** — **商品详情页升级：立即购买 + 展示丰富化 + 分享修复**。
① 立即购买：下单接口增可选 `buySkuId/buyQuantity` 直购分支（内存临时行、不读购物车、不清购物车，冻库/券/幂等与购物车结算同源）；新建 `pages/checkout` 确认单（地址/券/数量 → 提交即进收银台），详情页底部改「分享｜加入购物车｜立即购买」。② 展示丰富化：轮播 3→5 图、规格参数卡（品牌/分类/适用/过敏原，真实字段）、包装规格卡（SKU spec + 编码 + 库存，随选中规格联动）、图文详情（长文 + 3 配图）。③ 分享：卡片带价格与商品首图封面、新增朋友圈（`onShareTimeline`）；devtools 里"截图预览/查看分享页"是工具模拟行为，真机转发正常。
**验证**：后端 `direct-buy-smoke.py` 13/13 + 小程序 stub 24/24 + 静态自检 0 错误。

**第四十一轮（2026-09-26）** — **tab 切换顶部白条根治：5 个 tab 页改自绘导航栏**。
上一轮「页面 json 补 `navigationBarBackgroundColor`」未根治。本轮像素级再取证：状态栏 #4F6EF7 蓝、紧邻 44pt 行 **纯白 #FFFFFF 无标题** → 白条 = 原生导航栏标题行在切页时被重建为默认白。根治方案：**tab 页弃用原生导航栏**，新增 `components/nav-bar`（固定蓝条 + 流内占位，尺寸由 `utils/nav.js` 按胶囊位置反推）+ 5 页 `navigationStyle: custom`；原生栏不存在即无可闪。连带收口：category 全屏 fixed 布局按导航总高下移、orders 吸顶条同步下移、index 下拉刷新改自绘指示器（原生三点会被遮挡）。**验证**：17 JSON 合法 + JS 语法全过 + Node stub 11/11。

**第四十轮（2026-09-26）** — **tab 切换顶部白条闪烁修复（小程序）**。
录屏逐帧定位：白条 = 导航栏区域，切换后约 150ms 白底无标题、随后才套全局蓝。根因是 15 个页面 json 均未显式声明 `navigationBarBackgroundColor`，tab 切换时新页面导航栏先按缺省白色初始化。修复：全部页面 json 显式补 `navigationBarBackgroundColor: #4f6ef7` + `navigationBarTextStyle: white`。

**第三十九轮（2026-09-26）** — **页内沙箱收银台 + 购物车选券（小程序）**。
① 支付不再复制链接：新建页内收银台 `pages/pay/pay`（app.json 15 页），`pay.checkout()` 改为 `navigateTo` 进页，直接调公开端点 `POST /api/payments/sandbox/{payNo}`（与 Web 收银台同一条校验/入账路径），调用方回调经 `pendingHandlers` 暂存/取走透明交接、失败兜底轮询。三条路的取舍：`web-view` 强制 HTTPS + 业务域名预配置（localhost 永远配不了）；`wx.requestPayment` 需要真实微信渠道预下单参数（B-07 边界未接）；页内自绘是沙箱阶段唯一正解。
② 结算券生效：根因是**小程序端下单从未传 couponCode**（Web 端传了、后端锁券抵扣本来就正确，纯前端缺陷）。购物车新增选券行，可用性判断走服务端 `GET /api/coupons/mine/usable?amount=`（含 bestCouponCode 推荐），金额以服务端锁券计算为准；语义：用户「不使用」优先级最高（`_couponDeclined` 不被自动推荐复活）、已选券失效自动退回、下单成功重置。
**验证**：Node stub 7 场景全过（含拒绝选券优先级 bug 当场抓出并修复）+ 静态自检 0 错误。

**第三十八轮（2026-09-26）** — **未登录态按钮统一「去登录」+ 登录返回自动重拉（小程序）**。
全局排查错误盒 + 重试按钮：address / order-detail / orders / after-sales 四页未登录分支误显「重试」（orders 甚至落成空态误导），统一改为按 `needLogin` 标志显式区分 —— 未登录给**去登录**（`openLoginPage` 带去重），网络错误才给重试；address / order-detail 补 `onShow` 自动重拉（登录页 navigateBack 只触发 onShow，不清登录返回后停在错误页的断点）。coupons 此前已正确；index/product/category/seckill 的重试对应公开接口属合法场景。

**第三十七轮（2026-09-26）** — **订单列表商品缩略 + 我的售后入口/列表页（小程序）**。
① 订单列表卡片：从「共 N 件商品」升级为**商品缩略行**（最多 2 行：缩略图走 `images.skuImage` 解析层 + 标题 + ×数量，更多收口为「…等共 N 件」）；② 「我的」页新增**我的售后**入口（需登录）→ 新建 `pages/after-sales` 列表页（分页拉 `mine` 接口，点卡片跳订单详情看进度；空态给指路）——小程序至此补齐售后闭环的「找得到」一环。

**第三十六轮（2026-09-26）** — **多类型售后放开 + 售后举证图直传（A15）**。
① **多类型售后**：后端唯一键是 `(order_id, type)`，不同类型本就可各自立案 —— 小程序从「有任何售后就收起申请入口」改为**按已提交类型过滤可选类型**；订单详情售后进度改列表展示，入口只在两种类型都提交过才收起。Node stub 验证 4 路径全过。
② **图片上传链路（全站第一个 multipart 端点）**：`POST /api/uploads`（**需登录**；扩展名+Content-Type 双白名单 jpg/jpeg/png/webp/gif、≤5MB、UUID 落盘不信任原文件名）返回可直塞 `images` 的**绝对 http(s) 直链**；`GET /uploads/{filename}` **公开读**（`<image>` 标签发不出 Authorization 头，安全防线=服务端生成的 UUID 文件名 + 正则锁死防穿越）。**双前缀是安全设计**：网关白名单按路径、不区分方法，读写同前缀时给 GET 开白名单=匿名 POST。
存储为本地盘卷 `fulfilment-uploads:/data/uploads`（对象存储前的过渡态；Dockerfile 已预建目录并 chown，否则非 root 进程写卷 AccessDenied）。
小程序售后页新增举证图网格（选图→逐张上传→预览/删除；失败图原地标「上传失败」**不挪位**——中途 splice 会让在途上传回写错位），提交只带成功直链。
**验证**：新增 `scripts/upload-smoke.ps1` **8/8 全绿**（匿名 401 / 白名单只放读 / 上传→回读 roundtrip / 扩展名白名单）+ 探针证明上传直链被售后创建接口接受；小程序静态自检 0 错误。

**第三十五轮（2026-09-26）** — **登录/注册提示按路径各自准确 + 给可执行下一步**。
**根因**：前端 `friendlyMessage` 把**所有 401 一刀切**成「请先登录」，把后端的准确文案「手机号或密码不正确」盖掉了 → 用未注册手机号登录也会提示"请先登录"。
**修复**：① 401 **透出后端文案**（同一条修复让登录接口与业务接口都变准确）；② 手机号登录失败 → 页面内引导「可能未注册或密码错」+「**去注册新账号**」（**保留已填手机号**）+ modal；③ 注册撞号 → 「该手机号已注册」+「**去登录**」；④ 微信未绑定 422 → 未登录走「用手机号**登录或注册**并绑定」、已登录走「绑定到当前账号」。页面内引导卡片统一承载 4 种动作（bind/phone/register/login），**不依赖系统弹窗**。
**验证**：5 条路径 stub 驱动全通（含切页签保留手机号）。**设计取舍**：后端对"手机号不存在/密码错误"统一 401 是防枚举的刻意设计，前端不猜、只把两种可能与入口都摆出来。

**第三十四轮（2026-09-25）** — **未绑定提示「必然可见」加固（页面内引导 + modal 双保险）**。
用户复测显示：Console 已有 `[login] 微信登录失败 {status:422, code:BUSINESS_RULE}`（**判断逻辑与 422 分支都已命中**），但 `wx.showModal` 仍未呈现 → **问题在"系统弹窗"这个载体，不在判断逻辑**。
修复改为**不依赖单一载体**：422 时同时 ① 渲染**页面内引导卡片**（`data.bindTip` → 标题/说明/操作按钮，**不依赖任何 wx API，必然可见**）② 保留 `wx.showModal` 作第二重保险；卡片按钮按登录态给「绑定到当前账号」/「用手机号登录并绑定」。
**验证**（Node stub 驱动）：未登录 422 / 已登录 422 均 ✅ 落卡片 + 调用 modal，按钮行为正确。

**第三十三轮（2026-09-25）** — **解绑后一键登录「无提示」根治 + 地址表单输入框修复**。
**根因链**：`wx.login` 在个别环境既不 success 也不 fail → `getWxCode` **永不 settle** → `silentRelogin` 单飞标记 `_silentLoggingIn` **永久锁死** → 后续点击立即 `reject({needLogin:true})`（**无 status**）→ 绕过 422 引导分支只落一句 1.5 秒含糊 toast；叠加 `wechatLogin()` 在 `submitting` 时**静默 return**（锁死后连点毫无反应）。
**修复**：`getWxCode` 加 **10s 超时**；`submitting` 守卫**不再静默**（给"正在处理中"）；`handleWechatError` **所有失败分支改 modal**（含 needLogin/未知错误）+ `console.warn` 诊断。
**验证**（Node stub `wx`/`Page` 驱动页面方法）：4 种失败路径**全部产生可见 modal**（未登录 422 / **已登录 422** / 单飞冲突 / 503）；「`wx.login` 永不回调」实测 **1209ms 后正确 reject**，不再悬挂。
**地址表单**：`box-sizing: border-box` 下 input 默认内容高度被上下 padding 挤占 → 文字只露上半截；改为**显式 `height/line-height: 84rpx` + padding 只留左右**。

**第三十二轮（2026-09-25）** — **P2 订单售后入口 + P3 搜索高亮与纠错**。
① **售后入口**：新增 `pages/after-sale`（仅退款/退货退款 + 诉求 ≤500 字 + 已有售后则只展示进度）；订单详情展示售后进度并在 **SHIPPED/COMPLETED** 给「申请售后」。**实测修正**：后端规则是「发货后才能申请」（PAID 点了必 422）→ PAID 改为提示「卖家发货后可申请」。
② **搜索高亮与纠错**：新增 `GET /api/products/search/suggestion`（词表 = 品牌/分类/在售标题 **+ 2~4 字子串**，**同音优先**（拼音索引 O(1)）→ 形近（长度±1 桶 + 共享字 + 编辑距离），给不出靠谱候选就返回空）；前端标题按命中片段高亮（`utils/highlight.js` 分段渲染，**不用 rich-text，零注入面**）+ 零结果时「你是不是想搜 X」一键改搜。
**实测**：纠错 **6/6**（猫良→猫粮、喂食气→喂食器、冻千→冻干；正确词不乱建议）；售后 **7/7**（预下单 → 沙箱确认支付 SUCCESS → 发货 → 申请 200 → 重复 409）；自检 0 错误 0 告警。
**关键教训**：**词表粒度决定纠错能不能用** —— 只收整词时「猫粮/冻干」不在词表，纠错等于失效。

**第三十一轮（2026-09-25）** — **地址选择模式 + IK 分词器实测结论（不切换）+ 冒烟夹具治理**。
① **P0 地址选择模式**：购物车新增可点的「收货地址」行 → 地址簿**选择模式**（`?pick=1`）→ 经 `eventChannel` 回填，**本次结算即生效**（此前只能先去改默认地址）；秒杀保持"快"，不插选择步骤，但抢购成功后弹窗展示收货信息。
② **IK 中文分词器：实测后决定不切换** —— 插件可获取（官方非 GitHub 源 `release.infinilabs.com`，与 ES 8.11.4 严格同版本）、隔离容器验证加载成功、ES 正常启动；但**默认词典不含宠物垂直词**（`猫粮` 仍切成「猫/粮」，与 standard 相同）→ 装了没有预期收益，反而引入 A10 当初刻意规避的插件运维面。**保持现有 `match_phrase` 精确优先 + 分层加权 + 两段式**方案；将来要做词级语义，正确路径是「IK + 宠物领域词典 + 定期运营」。
③ **冒烟夹具治理**：清理 `微信冒烟-A/B` **16 个**（删除前确认零关联），并给两个冒烟脚本补**自清理**；库内其余 690+ 历史夹具**不盲删**（`铲屎官小林` 等昵称被 CI 断言依赖，见 `smoke.ps1:135`）。

**第三十轮（2026-09-25）** — **小程序体验闭环：地址簿 / 订单详情 / 微信绑定管理**。
补齐"只有 Web 端能做"的环节：① **收货地址簿**（`pages/address`，列表/新增/编辑/设默认/删除；**不做省市区级联**——数据包约 900KB 会挤爆 2MB 主包，改文本输入 + 与后端一致的本地校验）；② **订单详情页**（`pages/order-detail`，状态卡/明细/金额/**物流轨迹**/按状态操作/复制单号）；③ **「我的」页微信绑定管理**（显示 `wechatBound`，绑定/解绑，每次重新取 code）；④ **支付抽到 `utils/pay.js`**（列表与详情共用）；⑤ 结算无地址**直接跳地址簿**、订单卡片点进详情（按钮 `catchtap` 防冒泡）。
**实测**：14 项接口链路全通（地址 CRUD / `wechatBound` 三态 / 订单详情 / 物流）；自检 **0 错误 0 告警**（期间当场抓出旧方法名残留）。
**下一步**：P0 地址选择模式 / P1 **IK 分词器**（已给风险与回滚方案）/ P2 售后入口 / P3 搜索高亮纠错。

**第二十九轮（2026-09-25）** — **解绑/换绑接口 + ES 搜索完善 + 分类页溢出修复**。
① **新增账号解绑与换绑**：`POST /api/auth/wechat/unbind`（须持 code 证明持有该微信，否则 422；未绑定 200 幂等）、`POST /api/auth/wechat/rebind`（新微信已属他人 409；同微信 200 幂等；未绑定即首次绑定）；均需登录、**不在白名单**；`UserView` 仅新增 `wechatBound` 布尔（不下发 openid）。实测 13 项分支全通（含**库内置 NULL** —— 绕开 MyBatis-Plus `updateById` 忽略 null 的坑）；新增冒烟 `scripts/wechat-bind-smoke.ps1`（24 断言）。
② **ES 搜索完善**：多词 **AND**（「猫粮 2kg」精准 2 条）、显式 `sort=relevance`、**深分页 `from+size ≤ 10000` 拦截**、**结构化检索日志**；并给出六维优化清单（查询条件 / **IK 分词** / 排序 / 游标分页 / 高亮与纠错 / 可观测）。
③ **分类页标题溢出**：根因 `.cat-main` 缺 `min-width:0` 被长商品名顶宽 → 已补 + 卡片/标题兜底。

**第二十八轮（2026-09-25）** — **绑定被占用时异常被前端吞掉**：后端 409 判断正确（该微信已绑其他账号），但 `maybeBindWechat` 把失败归一成 `false`、且失败 toast 被「登录成功」覆盖 → 改用 `{bound,error}` 保留错误 + `notifyBindFailure` 走 **modal** 展示真实原因。

**第二十七轮（2026-09-25）** — **分类页卡片/图标超出屏幕**：复用全局 `.goods-*` 并靠页面覆盖尺寸，覆盖失效即退回 340rpx 双列基线（`340×2+左栏 200 > 750`）→ 改独立类名 `cg-*` + 单列 flex 布局。

**第二十六轮（2026-09-25）** — **登录成功后统一绑定微信**：绑定此前只挂在「先点微信登录被 422」这条路径上，用户直接点手机号登录就不会绑定（日志证实 2 次登录成功、0 次 bind）→ 登录成功后统一 `maybeBindWechat()`。

**第二十五轮（2026-09-25）** — **微信一键登录引导加固**：登录页缺 `onLoad` 会话校验，422 分支用"本地有无令牌字符串"判断登录态 → 残留过期令牌时 bind 401 → 静默重登 → 又 422 死循环 → 改为 `verifySession` + 可靠引导。

**第二十四轮（2026-09-25）** — **搜索相关性修复（结果与关键词不匹配）**。
三处根因叠加：① `multi_match` 写死 `operator: or` + `pet_text` 用 standard 分词器（中文**逐字切分**）→ 搜「喂食器」等价 `喂 OR 食 OR 器`，凡含「食」的商品全被召回；② 默认排序只有 `salesCount desc`，ES 显式 sort 后不再按相关性排 → 唯一精确命中被挤到最后；③ 商品占位图带品类文字、按 id 映射 → 图文不符放大观感。
修复：召回改**分层加权**（标题整串短语 6.0 / 标题分词全命中 3.0 / 副标题 2.0 / 描述 1.0 / 跨字段 AND 2.0 / 拼音 1.5 / 逐字兜底 0.1）+ **两段式**（严格为零才放宽）+ 默认排序加 `_score desc`；商品图去文字。
**实测**：「喂食器」4 条（精确项垫底）→ **1 条精确命中**；「零食」4→2、「猫粮」15→4 全部相关；「猫窝」零命中给相关建议；无关键词与 `sort=price_asc` 无回归。

**第二十三轮（2026-09-25）** — **微信一键登录「未绑定」闭环**。
用户反馈一键登录报错；服务端日志证明**微信侧换码成功**（`jscode2session 成功`），报错是紧随其后的 **422「该微信未绑定账号」** —— A13 刻意不做微信自动注册（避免无手机号孤儿账号），真缺陷是**引导不闭环**（文案指向小程序里不存在的入口）。已补：`api.wechatBind()` + 登录页四情形闭环（未绑定+未登录 → 手机号登录后**自动绑定当前微信**；未绑定+已登录 → 一次点击直接绑定；属他人 → 409 提示），并把后端 422 文案改为两端都准确。
**自测**：未绑定 422 → 绑定 200 → **再一键登录 200 + 同一 userId + 拿到令牌** → 幂等 200 → 抢占 409；临时账号已清理。

**第二十二轮（2026-09-25）** — **A14 界面修复与资源补齐：分类栏统一 + 图片 + 首页轮播**。
① **分类栏不一致**修掉三处根因：左栏缺 `flex:none` 被挤窄（分类名逐字竖排）、商品卡写死 320rpx 导致单/双列飘忽、`fetchProducts` 用 `loading` 丢弃**新请求**（快速切分类点了没反应）→ 统一为「左栏固定 200rpx + 卡片 48.6% 恒两列 + 同一套选中态 + 请求序号丢弃过期响应」。
② **图片补齐 34 张 / 397.5KB**（主包 19.4%）：真因是后端下发 `/img/products/*.svg`（相对路径 + 网关不托管 + `<image>` 不支持 SVG）→ 新增 `utils/images.js` 解析层（绝对 URL 原样、其余稳定映射随包位图）+ `scripts/gen-miniprogram-images.py` 统一模板生成（商品图/分类图标/tabBar 双态/首页入口/轮播/封面）。
③ **首页新增轮播图**：自动播放 + 手势滑动 + 左右箭头 + 胶囊指示器，配置数组集中在 `miniprogram/config/banners.js`，大屏媒体查询加高。
**自测**：`node scripts/check-miniprogram.js` **0 错误 / 0 告警**（新增「图片引用存在性」检查，覆盖 26 个引用）。

**第二十一轮（2026-09-25）** — **A14 排查修复：小程序全面体检后的缺陷修复**。
① **根因修复**：小程序登录拿不到令牌 —— 登录端点只回扁平 `UserView`、令牌走 httpOnly Cookie（浏览器方案）。新增 `/api/auth/{login,refresh,wechat}/token` 三个**纯增量**令牌端点（令牌随响应体下发，与浏览器端点共用同一 `AuthService`）+ 网关白名单逐条精确放行；**Web 与既有冒烟断言零改动**。
② **小程序缺陷修复**：支付补 `idempotentKey`（此前必 400）、支付结果字段 `codeUrl`/`payStatus`、秒杀 `phase`、商品图 `mainImage`、分类页分页（`bindscrolltolower`）、登录页双入口（新用户不再无路可走）、订单分页与取消入口、购物车按服务端勾选口径结算、悬挂 Promise / 轮询泄漏 / 401 去重等 28 项。
③ **新增自检**：`scripts/check-miniprogram.js`（JS 语法 + JSON 合法性 + WXML 事件绑定与 JS 方法对齐 + 数据字段粗筛），当前 **0 错误 / 0 告警**。
**自测**：`/api/auth/login/token` 实测返回令牌、Bearer 调 `/api/cart` 200（无令牌 401）、旧支付形状实测 400「idempotentKey 不能为空」、新形状通过校验；`mvn package` BUILD SUCCESS。

**第二十轮（2026-09-25）** — **A14 小程序全面重构 + 限时秒杀 已交付**。
① **小程序重构**：TabBar 五页（首页/分类/购物车/订单/我的）默认进首页；统一请求层（错误映射 / 401 跳登录）+ 设计 token；补齐**领券中心 / AI 导购 / 限时秒杀**三特色页；详情页「服务器内部异常」根因=旧页面请求不存在的子路径（被兜底成 500），新页面已按真实字段对接。
② **限时秒杀后端（交易域）**：三表 + Redis Lua 预扣（**软依赖可降级**）+ `UPDATE ... WHERE available_stock > 0` 行锁兜底**绝不超卖**；活动配额独立于商品库存，关单/取消回补；C 端 3 端点 + 运营端 2 端点；网关路由 + activities 精确只读白名单。
③ **网页端**：`/seckill` 页（浏览公开、抢购需登录 → 默认地址下单 → 订单详情付款）+ 导航入口。
**自测**：`scripts/seckill-smoke.ps1` **51/0 全绿**（幂等 replay / 限购 / 售罄 / 取消回补闭环 + **R74 运营编辑·删除 25 项，含套件自我清理**）；Web `tsc` + `vite build` 通过；冒烟口径 20 → **21 套 1505 项**。

**第十八轮（2026-09-24）** — **A11 RocketMQ 削峰 + Sentinel 限流 已交付**。
① **削峰通道（交易域）**：`POST /api/orders/queued`（受理即入队，返回任务视图 PENDING）+ `GET /api/orders/placement/{idempotentKey}`（任务查询）；**同步下单 `POST /api/orders` 一行未动** —— 前端与既有断言零破坏。消费者与控制器**共用同一份** `placeOrder(userId, request)`：削峰**不换**幂等与状态机语义，靠代码级复用而不是"两处写一致"。
② **任务台账 + 死信**：`t_order_place_task`（PENDING/PLACED/FAILED，唯一键幂等）；消费者失败**分类** —— 业务性失败不重试直接 FAILED，系统类退避重试（10s+30s）耗尽进 `%DLQ%duoduo-trade-order-place`；运营可按幂等键查死信（`GET /api/admin/mq/dlq`）并**一键重放**（原文 `sendRaw` 回主 topic，订单幂等键兜底重放安全）。
③ **Sentinel 限流**：`sentinel-core 1.8.6`，`FlowRule` 代码定义（资源 `order-place`，QPS 走环境变量），触发 → 既有 `RATE_LIMITED`(429)；**同步与队列端点共用同一资源**（防"限流把用户赶到队列端点绕开"）。
④ **RocketMQ 用原生 client（4.9.8）刻意不用 spring starter**；容器挂 `profile: mq`（namesrv+broker），`DUODUO_TRADE_ORDER_MODE=queue` 才创建 MQ Bean —— **sync 默认形态下 CI 与零依赖环境不背 MQ**。改形态必须 `up -d --force-recreate`（容器 env 固定）。
**自测**：新增 `scripts/mq-smoke.ps1` **47/0 全绿**（脚本自管 MQ 起停与 trade 模式切换，覆盖主链路 PENDING→PLACED / 入队幂等 / 业务失败 FAILED / **商品宕机制造死信 → 可查 → 重放 → PLACED 闭环** / 30 并发 burst 必现 429 / 自证非破坏）；全量回归 **20 套 1479 项 0 失败**（`ai-smoke` 24 项改为形态自适应 —— mock/llm 都是正确部署，只锁死绝不 unconfigured）；OpenAPI **125 → 129 端点**；容器 10 + 可选 MQ×2（profile mq）。工具层沉淀 **T-63（PS 5.1 原生传参剥引号）**、**T-64（容器名 vs 服务名）**。

**第十七轮（2026-09-24）** — **A9 AI 导购「多多」+ A10 Elasticsearch 检索 均已交付**；**A9 已用真实百炼 Key 跑通 `llm` 形态并切 `qwen-max`**。
① **A9 · 新服务 `duoduo-ai`（:8086，容器 `duoduo-app-ai`）**：**无状态**（不连库、v1 不落对话历史）。
`POST /api/duoduo/chat` + `GET /api/duoduo/capabilities`，两条都在网关白名单 —— **未登录可问答**是硬需求。
推荐**基于真实商品库**：Feign（Nacos + `X-Internal-Token`）调商品域
`GET /api/products/internal/candidates` 召回候选（视图**不含 `sellerId`**），再把候选喂给模型，
系统提示硬约束「只推荐候选列表内的商品」—— **结构上杜绝编造，不是事后用正则抓幻觉**。
三形态 `mock`（默认，用真实候选拼回复，整链可跑）/ `llm`（配 `DASHSCOPE_API_KEY`）/ `unconfigured`；
未配置或超时 → **503 `DEGRADED`**，每 IP 限流 → **429 `RATE_LIMITED`**（客户端不应自动重试）。
前端新增 `/ai` 对话页（**刻意不套 `RequireAuth`**）：气泡 + 推荐卡片直链商品详情 + 带角色回传历史。
② **A10 · 商品域接入单索引 `duoduo-search`**（ES 作为**可选加速层**，`profile=search` 默认不起）：
**拼音与同义词都在应用侧**（pinyin4j + 应用侧同义词替换 —— 按 ES 实测，查询侧挂 `synonym_graph`
会把 or 语义收成 phrase、**反而降低召回**）；`SearchSyncJob` 15s 扫 `updated_at` 变更（**兼补偿**）+
启动全量对齐 + 清孤儿；运营可查 `GET /api/admin/search/reconcile`、可 `POST /api/admin/search/reindex`；
**ES 不可用原地降级 MySQL**（并**禁用 ES 健康指示器** —— 否则 ES 一停会因容器 unhealthy 被健康门杀掉降级链路本身）。
ES 只返回 **id 顺序页**、卡片装配回 MySQL —— **权威数据只有库一份**，开关 ES 看到同一个商品世界。
③ **顺带修掉两个真缺陷**：`run-all-smoke.ps1` 的过滤器 `*-smoke.ps1` **漏跑最基础的 `smoke.ps1`**
（「一键全量」报 18 套却宣称全部通过）；`LongToStringJacksonModule` 白名单**漏列 `total` 等 20 余个
金额/计数字段** → `total` 以字符串下发，使冒烟断言 `$p.total -ge 3` 在 PowerShell 里**退化成字典序比较**而恒失败
（**类型错误伪装成业务失败**）。两处均已修复，并新增一条断言直接检查原始文本防复发（见 `docs/agent/08` **T-53 / T-56**）。
**自测**：`scripts/run-all-smoke.ps1` **19 套全绿**（`ai-smoke` 23/0、`search-smoke` 40/0）；
容器 **9 → 10** 全 healthy；OpenAPI **120 → 125 端点**；前端 `tsc --noEmit` 零错误。

**上一轮（2026-09-24 第十六轮）** — **A7 运营账号管理页完成**。
① **后端**：`GET /api/admin/users?role=&keyword=`（运营/管理员，分页 + 检索，**脱敏视图**——不下发实名姓名 / 身份证 / 完整手机号）
与 `POST /api/admin/users/{id}/role`（**仅 ADMIN 可改角色**；OPERATOR 改角色 403）。
三条硬规则：**不能给自己降权 422**、**不能降级最后一个 ADMIN 422**、每次变更写审计日志 `t_audit_log`
（谁给谁从什么改成什么，只追加不修改）。提权后目标用户**重新登录才生效**（响应 notice 提示——
访问令牌里的角色是签发时的快照）。同角色重复变更幂等 200、不产生审计事件。
② **DDL**：`t_audit_log` 实表（`docs/sql/incremental/09-a7-audit-log.sql` 幂等）。
③ **网关**：`/api/admin/users/**` 显式挂进 user 服务路由（不进白名单）。
④ **前端**：`/admin/operators` 运营账号页（AdminTabs 第 7 页签；检索 + 分页 + 改角色 Modal）。
**自测**：`scripts/admin-smoke.ps1` **49 → 72 项全绿**（PS5.1），新增 23 项 A7 断言；`tsc --noEmit` 零错误；
全量 `mvn clean install` 通过；CI 子集推送后自动复验。

**第十五轮续（2026-09-23）** — **A13 后端 code2Session 登录 + 微信绑定打通**。
① **DDL**：`t_user` 加 `wx_openid` + **唯一键 `uk_user_wxopenid`**（增量脚本 `docs/sql/incremental/08-a13-wechat-bind.sql`，
并发绑同一微信的最终防线靠索引）。
② **新端点**：`POST /api/auth/wechat`（免登，白名单只放行这一条）与 `POST /api/auth/wechat/bind`（登录态绑定）。
语义口径：未绑定微信登录 → **422** 引导先手机号登录再绑定（**不做自动注册**）；openid 已属他人 → **409**；
重复绑定同一 openid → **200 幂等**；未配置 AppID/Secret → **503 DEGRADED**（外部依赖缺失如实降级）。
③ **WechatGateway 三分支**：mock（`DUODUO_WECHAT_MOCK_ENABLED=true` 显式开启 + code 前缀 `mock:`，生产禁开）/
real（jscode2session）/ 未配置（503）；secret 只走环境变量，日志只记 openid 前 4 位。
**自测**：`scripts/wechat-auth-smoke.ps1` **26/26 全绿**（GUARD-OK）+ CI 子集回归 **356/0 无回归**。
**2026-09-25 更新**：用户已提供 AppID/Secret——user 容器已注入（`.env` 不入库）、`project.config.json` 换真实 AppID、`WECHAT_LOGIN_READY=true`，真实通道验证通过（假 code → 401）；待用户在微信开发者工具完成 wx.login 联调与真机预览。

**GitHub Actions 首跑已打通（2026-09-24，Run #5 ✅）**：编译检查 + 冒烟子集 356 项双 job 全绿。
首跑连续 4 轮失败暴露并修复两个真缺陷（详见 `docs/agent/08` **T-44/T-45/T-46**）：
冒烟脚本错误响应体读取的 PS5.1-only 写法（pwsh 7 需 ErrorDetails 优先 + 断言走解析后的 message），
以及增量 SQL 被容器初始化目录加载导致全新建库撞 1060（已移入 `docs/sql/incremental/`）。

**第十五轮（2026-09-23）** — **A6 契约导出与 CI + A13 小程序骨架（测试号先行）**。
① **OpenAPI 契约导出**：knife4j starter 挂 `duoduo-common-web`（5 个业务服务自动获得，网关 WebFlux 不依赖故不受影响），
各服务 `application.yml` 开 `springdoc`；新增 `scripts/export-openapi.ps1` 从 `/v3/api-docs` 导出
`docs/openapi/<service>.json` ×5 + `docs/openapi/endpoints.md` 端点摘要（**116 个端点**：user 22 / product 29 / trade 37 / fulfilment 19 / marketing 9），
与 `docs/agent/04` 逐条核对一致。**安全边界**：网关路由是显式前缀白名单，`/v3/api-docs` 与 `/doc.html` 不会出现在公网入口。
② **GitHub Actions CI**：`.github/workflows/ci.yml` 双 job —— `build`（后端 9 模块编译 + 前端 tsc/vite build）与
`smoke`（起 Docker 全栈 → 等 9 容器 healthy → pwsh 跑冒烟子集 smoke/trade/order/address/id-precision 共 **356 项** → 失败即 job 失败 + 日志 artifact）；
`scripts/ci-run.ps1` 为通用子集执行器（本地实跑 **356/0 全绿**，证明引入 knife4j 未改变任何接口行为）。
③ **A13 小程序骨架**：`miniprogram/`（project.config 测试号 + 请求封装 + 首页/详情/购物车/订单/登录 5 页面），
开发者工具勾「不校验合法域名」即可跑通「浏览 → 加购 → 取默认地址下单 → 沙箱支付轮询」主链路（登录暂用手机号回退，
`code2Session` 后端端点已在第十五轮续补齐）；详见 `miniprogram/README.md`。

**上一轮（2026-09-23 第十四轮）** — **收货地址簿（A8）完成 + 雪花 ID 精度缺陷（T-39）根治**。
用户域新增地址簿：6 个用户端点（列表 / 新增 / 修改 / 删除 / 设为默认 / 取默认）+
内部快照 `GET /api/addresses/internal/snapshot`（`X-Internal-Token` 保护、**不进网关**），
交易域下单支持「只传 `addressId`」并在下单瞬间写入收货信息**快照**（此后改 / 删地址簿都不影响历史订单）。
**归属校验统一返回 404**（不区分"不存在"与"无权限"，避免用状态码差异枚举他人地址 ID）。
新增 `docs/sql/incremental/07-a8-user-address.sql`、前端 `/addresses` 页 + 结算页地址二选一。

**同一轮根治了 T-39（雪花 ID 精度丢失，全站性缺陷）**：
雪花 ID 是 18~19 位整数，超出 JS `Number.MAX_SAFE_INTEGER`（16 位），以 JSON **number** 下发时
浏览器 `JSON.parse` 会**静默四舍五入**（`Number('...704') === '...700'`，不报错），
前端拿损坏值拼 URL → 服务端查不到 → **404**。**接口冒烟照不出来**（.NET 用 Int64/Decimal 解 JSON 不丢精度），
只有真实浏览器会踩；A3 曾局部踩过一次、A8 又踩了一次。
根治方案：`duoduo-common-web` 新增 **`LongToStringJacksonModule`**（随 `WebAutoConfiguration` 自动装配到全部业务服务）
—— **`Long`/`long`/`BigInteger` 默认转字符串，字段名命中白名单（金额 / 计数 / epoch 毫秒时间戳）的保持数字**。
判据是**字段语义**（这个值前端会不会参与运算），不是 Java 类型：全站金额字段（`price`/`payAmount`/…）**共 63 处都是 `Long`**，
一刀切会静默变成 `"12900"` 使前端 `price / 100` 算出 `NaN`，把页面金额全打成「¥NaN」——"用更大的 bug 换小 bug"。
**漏标方向是安全的**：新加 id 字段无需任何操作即受保护。前端 `types.ts` 的 **38 个 id 类字段**与 `endpoints.ts` 的 **25 处形参**同步由 `number` 改 `string`。

新增 `scripts/address-smoke.ps1`（**82 项**）与 `scripts/e2e/address-ui-check.mjs`（**64 项**真实 Chromium 断言），
另新增 **`scripts/id-precision-smoke.ps1`（56 项）跨域 ID 精度回归**（断言 JSON **原始文本**，绕开 `ConvertFrom-Json` 的精度掩盖）
与 `scripts/run-all-smoke.ps1`（一键跑全套并汇总）。
**接口冒烟累计 22 套 1624 项，浏览器 UI 断言累计 6 套 244 项。**
（第五十二轮新增 `r52-listing-preview-smoke.py` 57 项，并把 `r51-full-acceptance.py` 扩到 155 项。
历史口径：第十三轮 20 套 1479 项 → 第十四轮 A8「15 套 1286 项」是当时子集口径，两者不可直接相加。）
详见「十二、变更记录」。

**上一轮（第十三轮）** — **优惠券营销（A5）完成**：新增第 6 个业务服务 `duoduo-marketing`
（营销域 / 优惠券，:8085；compose 服务名 `duoduo-marketing-app`，容器 `duoduo-app-marketing`，镜像 `duoduo/duoduo-marketing:1.0.0`），
容器总数 **8 → 9**（3 中间件 + 6 业务服务），雪花 worker-id 补 **营销 5**。
公开只读领券中心 `GET /api/coupons/templates` + 领券（重复领取返回 `replay=true` 不报错）+ 我的券包 /
结算页可用券 + 运营建券；内部接口 lock / release / consume / sweep 由 `X-Internal-Token` 保护、**不进网关**，
交易域下单锁券、支付核销、取消 / 关单解锁直连 8085。
券码用**随机串**（`CP` + 14 位去混淆字符）而不用雪花（规避 JS `Number.MAX_SAFE_INTEGER` 精度坑，同 INV-16）；
锁 / 解 / 核销全部 **CAS 条件更新、不用分布式锁**（INV-1）；**折扣金额由交易域写入 `t_order.discount_amount`**（营销域不依赖 openfeign）。
前端新增 `/coupons`（领券中心 + 我的券包，**刻意不套 `RequireAuth`**）+ 结算页选券 + 订单详情优惠展示。
新增 `scripts/coupon-smoke.ps1`（**145 项断言**）与 `scripts/e2e/coupon-ui-check.mjs`（**43 项**真实 Chromium 断言）。
（随后第十四轮 A8 把接口冒烟推进到 **15 套 1286 项**、浏览器 UI 断言推进到 **6 套 244 项**。）
详见「十二、变更记录」。

**上一轮（第十二轮）** — **信用分（A4）完成**：履约域新增 `support/CreditModel`
（纯函数模型，权重表 **基础 60 + 履约完成率 18 + 评价质量 16 + 售后诚信 6 = 100**，启动即校验），
`GET /api/reviews/seller/{sellerId}/credit`（**公开只读**：分数 + 构成明细 + 原始输入）+
`POST /api/reviews/internal/credits/refresh`（内部凭据，批量重算回写）。
**交易域补一个只回计数的内部接口**（`/api/orders/internal/seller/{id}/stats`，不含收货人信息）；
**用户域补内部回写入口**（`/api/users/internal/{userId}/credit`，幂等靠「同值不写库」）。
三条刻意的取舍：**读接口实时计算**（不读 `t_user` 的副本，避免「库里 100、页面 82」的分裂）、
**交易域不可用一律 503 而非算成 0 单**、**因子得分之和恒等于总分**（结构上不可能对不上）。
定时回写任务**默认关闭**（后台写别的库会污染自测）。前端新增共用的 `CreditBadge` / `CreditPanel`，
挂在商品详情、闲置详情与「我的」三处；商品卡片补出 `sellerId`（记录为对旧约定的**显式例外**）。
新增 `scripts/credit-smoke.ps1`（**82 项断言**）。
接口冒烟累计 **13 套 1034 项**，浏览器 UI 断言累计 **4 套 137 项**。详见「十二、变更记录」。

**上一轮（第十一轮）** — 物流轨迹（A2）：履约域新增 `t_logistics_track`，
`POST /api/logistics/internal/{orderNo}/tracks`（内部凭据批量摄入，幂等靠唯一索引）+
`GET /api/logistics/orders/{orderNo}/tracks`（买家 / 卖家 / 运营，按发生时间正序）。
**交易域发货事务提交后补推「已揽收」**（`AFTER_COMMIT` 事件 + best-effort，通知失败绝不影响发货）。
买家与卖家订单详情页各挂**共用的时间线组件**。`scripts/logistics-smoke.ps1` 79 项 +
`scripts/e2e/logistics-ui-check.mjs` 44 项真实 Chromium 断言。

**上上轮（第十轮）** — 售后三端页面：买家 `/after-sales`（申请 / 我的 / 详情 + 申请平台仲裁）、
卖家 `/seller/after-sales`（同意即退款 / 拒绝必填原因）、运营 `/admin/after-sales`（裁定，说明必填）。
至此 A3 从接口到入口全链路打通。`scripts/e2e/aftersale-ui-check.mjs` 44 项断言。

**第九轮** — 售后仲裁工单后端：履约域 `t_after_sale` + 8 个端点；卖家拒绝后买家可申请平台仲裁，
运营裁定退款会**真的执行退款**；**已完成订单（钱已放款给卖家）的裁定退款被拒 422** 并提示走追偿（刻意边界，非缺陷）。
`scripts/aftersale-smoke.ps1` **101 项断言**。

---

## 一、运行方式（Docker 优先）

中间件与业务服务**全部容器化**，一条命令起全套：

### 环境准备（两件一次性配置，各一条命令）

```bash
# ① Docker 镜像源：解决"拉不到镜像"
#    （Docker 守护进程默认直连 Docker Hub，在国内网络常被拦；容器里那个 http.docker.internal 代理与它无关）
#    脚本会：备份 daemon.json → 合并写入 registry-mirrors → 重启 Docker Desktop → 用之前失败的镜像验证
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\docker-mirror.ps1

# ② 代码推送通道：本机到 GitHub 的 HTTPS 通道被拦，改走 SSH（443 端口）
#    无密钥则生成 → 打印公钥（贴到 https://github.com/settings/ssh/new）→ 再跑一次即自动切换 origin 并验证
bash scripts/ssh-push-setup.sh
```

> 来龙去脉与判据见 `docs/agent/08-环境运维与踩坑禁忌.md` 的 **T-65**（镜像源）与 **T-67**（推送通道）。
> ⚠️ `docker desktop restart` 内部会调用 `wsl.exe`，**在受管沙箱里被安全策略禁止** —— 所以"重启 Docker Desktop"这一步
> 需要你在本机点一下（脚本会明确提示），助手侧只能完成配置文件与验证命令的准备。

```bash
docker compose up -d          # 起 11 个容器（3 中间件 + 7 业务服务 + 1 网页端）
docker ps                     # 期望：11 个容器全是 healthy

# 可选：检索加速层（A10）。刻意挂 profile，默认不起 —— 不起时检索自动走 MySQL
docker compose --profile search up -d duoduo-es

# 可选：削峰队列（A11）。默认 `DUODUO_TRADE_ORDER_MODE=sync` 不需要 MQ；
# 切 queue：先起 MQ 两容器，再带环境变量重建 trade（改 env 必须 force-recreate）
docker compose --profile mq up -d rocketmq-namesrv rocketmq-broker
DUODUO_TRADE_ORDER_MODE=queue docker compose up -d --force-recreate duoduo-trade-app
```

### 网页端（正式版）：一条命令，不必常开命令行窗口（S1）

```bash
bash scripts/web-up.sh        # ① 构建前端产物 ② 打镜像 ③ 起容器（对外 8088）
```

打开 **http://localhost:8088/** 即可（运营账号 `13684295254` / `duoduo12345`）。
容器内部：nginx 托管打包后的静态文件，并**同源反代** `/api` 与 `/sandbox` 到网关 ——
浏览器始终同源，httpOnly Cookie 正常携带、无跨域预检、**后端 CORS 白名单无需改动**。

> **为什么不再用开发服务器**：`npm run dev` 起的服务**随终端/会话关闭就被回收**（曾导致"点开链接打不开"）；
> 正式版容器带 `restart: unless-stopped`，长期可用、开机自启，也不需要任何命令行窗口。
> 改了前端代码后重跑一次 `bash scripts/web-up.sh` 即可（约 30 秒）。
> 手动等价三步：`cd frontend && npm run build` → `docker compose build duoduo-web-app` → `docker compose up -d duoduo-web-app`。

| 容器 | 镜像 | 端口 | 说明 |
|---|---|---|---|
| `duoduo-mysql` | mysql:8.0.36 | 3306 | **五个库**：`duoduo_user` / `duoduo_product` / `duoduo_trade` / `duoduo_fulfilment` / `duoduo_marketing`（`nacos` 库由 Nacos 自建） |
| `duoduo-redis` | redis:7.2-alpine | 6379 | 缓存与分布式能力预留 |
| `duoduo-nacos` | nacos/nacos-server:v2.3.2 | 8848 / 9848 | 服务注册与配置；**已注册 8 个服务**（网关 + 7 个业务服务） |
| `duoduo-app-gateway` | duoduo/duoduo-gateway:1.0.0 | 8080 | 统一入口：路由 + JWT 前置校验 + 身份透传 + CORS |
| `duoduo-app-web` | duoduo/duoduo-web:1.0.0 | 8088 | **网页端（S1）**：nginx 托管前端产物 + 同源反代 `/api`、`/sandbox` 到网关；起法 `bash scripts/web-up.sh` |
| `duoduo-app-user` | duoduo/duoduo-user:1.0.0 | 8081 | 注册登录 / 令牌轮转 / 实名 / 宠物档案 |
| `duoduo-app-product` | duoduo/duoduo-product:1.0.0 | 8082 | 类目 / 品牌 / SPU-SKU / 检索 / 首页 / C2C 闲置 / **库存主数据** |
| `duoduo-app-trade` | duoduo/duoduo-trade:1.0.0 | 8083 | 购物车 / 下单拆单 / 库存冻结 / 订单状态机 / **支付（沙箱扫码）** / C2C 担保交易 |
| `duoduo-app-fulfilment` | duoduo/duoduo-fulfilment:1.0.0 | 8084 | 履约域：**评价与晒单 / 物流轨迹 / 售后仲裁 / 信用分** |
| `duoduo-app-marketing` | duoduo/duoduo-marketing:1.0.0 | 8085 | 营销域：**优惠券**（领券中心 / 我的券包 / 锁·解·核销 / 补偿调度） |
| `duoduo-app-ai` | duoduo/duoduo-ai:1.0.0 | 8086 | AI 导购「多多」：**候选召回（商品域）+ 对话编排**；未配百炼 Key 时 503 降级，mock 模式可整链跑通 |
| `duoduo-app-social` | duoduo/duoduo-social:1.0.0 | 8087 | 社交域（第四十七轮）：**关注 / 私信与卡片消息 / 商品咨询（问一问）**；通知中心（第二批） |
| `duoduo-es`（可选） | elasticsearch:8.11.4 | 9200 | **检索加速层（A10）**，compose `profile=search` 默认不起：`docker compose --profile search up -d duoduo-es`。不起时检索自动降级走 MySQL |
| `duoduo-mq-namesrv`（可选） | apache/rocketmq:5.3.2 | 9876 | **削峰队列（A11）NameServer**，compose `profile=mq` 默认不起；`JAVA_OPT_EXT` 压内存 |
| `duoduo-mq-broker`（可选） | apache/rocketmq:5.3.2 | 10911 | **削峰队列 Broker**（挂 `docs/mq/broker.conf`，`brokerIP1` 指向服务名）；trade 切 `queue` 模式才依赖它 |

**从零重建**（连数据卷一起删，会重新执行 `docs/sql/` 里的初始化脚本）：

```bash
docker compose down -v && docker compose up -d
```

### 构建

```bash
# Maven 构建（产出可执行 jar；Dockerfile 用它做镜像，不在容器内编译）
mvn -gs maven/settings.xml -B -DskipTests package

# 改了代码后重建镜像并重启对应服务
docker compose build duoduo-trade-app && docker compose up -d duoduo-trade-app
```

> 项目内 `maven/settings.xml` 使用阿里云镜像 + 项目内本地仓库，
> 因为机器上全局 `settings.xml` 的 `mirrorOf=*` 指向**已失效的公司私服** `100.wunding.com:1443`。
> 用项目内配置可以绕开，**不需要改任何全局配置**。

### 端到端冒烟（二十三套 1551 项接口断言 + 十七套真实浏览器 UI 断言 654 项 + 小程序无浏览器 72 项）

> **完整自测流程（环境/数据准备 → 功能点清单 → 逐项步骤与预期 → 排障表）见 [`docs/SELFTEST.md`](docs/SELFTEST.md)。**
> 改了 Java 代码请按 §「交付物核对」比对容器内 jar md5，别只看 `package` 的退出码。

> 一键全量回归：`powershell -NoProfile -ExecutionPolicy Bypass -File scripts\run-all-smoke.ps1`（逐套落盘 `logs/smoke/*.log`）。
> 其中 `search-smoke` **需要 ES**；ES 未运行时脚本会**显式跳过并在汇总里声明**，不会静默略过（见 `docs/agent/08` T-53）。
> CI 子集（本地 / GitHub Actions 同一入口）：`scripts/ci-run.ps1 -Scripts smoke.ps1,trade-smoke.ps1,order-smoke.ps1,address-smoke.ps1,id-precision-smoke.ps1`。
> **API 契约**：`scripts/export-openapi.ps1` 导出各服务 OpenAPI 至 `docs/openapi/`（含 `endpoints.md` 摘要，**129 个端点**与 `docs/agent/04` 逐条核对）；
> `docs/agent/04-接口契约全集.md` 的端点表由 `scripts/extract-api-contract.py` **从源码机械生成**（当前 **241 个端点**），
> 加 `--check` 即变门禁（文档落后于源码时退出码 1）—— 端点清单不再靠手工维护。
> GitHub Actions 见 `.github/workflows/ci.yml`（编译 + 冒烟子集）。

```bash
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke.ps1              # 55 项：用户域 + 商品域
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\trade-smoke.ps1        # 87 项：购物车
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\order-smoke.ps1        # 77 项：下单拆单 + 库存冻结
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\payment-smoke.ps1      # 72 项：支付幂等 + 状态机 + 超时关单
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\settlement-smoke.ps1   # 125 项：担保交易 + 资金托管 + 退款
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\pay-channel-smoke.ps1  # 62 项：沙箱支付渠道（扫码/验签/回调）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\seller-smoke.ps1       # 101 项：卖家中心（准入/归属隔离/发货/售后/结算）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\admin-smoke.ps1        # 72 项：平台管理后台（权限边界/审核/强关/对账/运营账号）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\review-smoke.ps1       # 59 项：履约域评价（内部接口/资格/匿名/追评/聚合）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\backfill-smoke.ps1     # 88 项：托管单回补（dryRun/幂等/挂账清零/控制组/REFUNDED 盲区）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\aftersale-smoke.ps1    # 101 项：售后仲裁（立案/卖家处理/升级/裁定退款/已放款边界/越权）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\logistics-smoke.ps1    # 79 项：物流轨迹（发货即揽收/乱序正序/幂等/批量全有或全无/越权/敏感字段）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\credit-smoke.ps1       # 82 项：信用分（基础分/权重表/因子和=总分/单调性/售后因子/回写幂等/凭据边界）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\coupon-smoke.ps1       # 145 项：优惠券（领券幂等/并发防超发/锁·解·核销 CAS/折扣落交易域/生命周期闭环/凭据边界/断言总数守卫）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\address-smoke.ps1      # 82 项：收货地址簿（CRUD/默认唯一性与删除顺延/归属统一 404/下单写快照/清理复原）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\id-precision-smoke.ps1 # 56 项：跨域 ID 精度回归（只断言 JSON 原始文本，绕开反序列化的精度掩盖）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\wechat-auth-smoke.ps1  # 26 项：微信登录绑定（未绑 422/未配置 503/幂等/抢绑 409/免登 401）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ai-smoke.ps1           # 37 项：AI 导购（形态自适应/推荐真实性/多轮追问/预算硬过滤与区间/极小预算空结果/预算未解析告知/出口预判/澄清反问/推荐上限/伪造 XFF 不能绕过限流/429）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\search-smoke.ps1       # 40 项：ES 检索（拼音与同义词容错/对账三空/停 ES 降级/**需 ES 在跑**）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\mq-smoke.ps1           # 47 项：削峰队列（入队 PENDING→PLACED/入队幂等/业务失败 FAILED/**死信可查可重放**/30 并发 429 限流/自管 MQ 起停与模式复原）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\seckill-smoke.ps1      # 51 项：限时秒杀（匿名边界/运营准入/下单幂等 replay/限购/售罄/DB 不负数/取消回补/运营编辑·删除与守卫/套件自我清理）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\upload-smoke.ps1      # 8 项：图片上传（白名单只放 GET/匿名上传 401/上传→回读 roundtrip/扩展名白名单）
python scripts/r58-restock-idempotent-smoke.py     # 10 项：退货入库幂等（同 refNo 三次只加一次 / 换 refNo 正常加 / 缺 refNo 400）
python scripts/r58-compensate-smoke.py             # 22 项：平台垫付与卖家追偿（闭环 / 重复 409 / 未放款拒 / 垫付**不回补库存** / 收回核销二次 409 / 非运营 401）
python scripts/r58-listing-edit-delete-smoke.py    # 33 项：闲置编辑与删除（编辑回待审 / 驳回件恢复在售 / 软删三处不可见 / 非本人 403 / **匿名 401（对照组证明白名单确实放行两段）** / 已售不可删）
python scripts/coupon-window-smoke.py               # 6 项：券领取窗口不变式（**列表可见 ⇒ 当下必可领**；未到开抢时间的券不得提前出现）

# ================= 前端真实浏览器 UI 自测（15 套） =================
# 一键跑：起 dev server → 跑 15 套 → 建/拆券夹具 → 收尾关进程，一条命令即可
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\e2e\run-ui-checks.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\e2e\run-ui-checks.ps1 -Only home   # 只跑自包含那套
# Playwright + Chromium 已装进 scripts/e2e/node_modules（仓库自包含，不依赖用户级 workspace）

# 也可手工跑（默认 UI_BASE=http://localhost:5173）：
cd frontend && node node_modules/vite/bin/vite.js --port 5173 --strictPort --host localhost   # 另开终端
# 注意必须走 localhost，不能是 127.0.0.1（不在 CORS 白名单）
cd .. && node scripts/e2e/home-ui-check.mjs           # 首页 7 项：非白屏 / 可点击 / 真点进详情 / 无非预期接口错误
node scripts/e2e/seller-ui-check.mjs                 # 商家端 29 项
node scripts/e2e/review-ui-check.mjs                 # 评价与晒单 24 项（含夹具后把商品评分**回写复原**，否则会给下一套留下「N 条评价」脏数据）
node scripts/e2e/aftersale-ui-check.mjs              # 售后三端 44 项（买家→卖家→运营整条链）
node scripts/e2e/logistics-ui-check.mjs              # 物流轨迹 44 项（买家/卖家同轨 + 空态 + 越权 + 终态仍在）
node scripts/e2e/address-ui-check.mjs                # 收货地址簿 64 项（含结算页两种模式与「改地址簿不改历史订单」）
node scripts/e2e/share-report-ui-check.mjs           # 举报处理台 24 项（摘要可见 / 默认不下架 / 驳回不误删 / 非运营拦截）
node scripts/e2e/notice-chat-follow-ui-check.mjs       # 通知 / 私信 / 关注 42 项（分类 chip **逐个真筛**、私信发送与非互关限一条、关注/粉丝名单、互关后恢复可发；自清理）
node scripts/e2e/seckill-ui-check.mjs                # 秒杀 63 项（新建活动 / **下架后仍列得出来并能重新上架** / 前台不可见 / 抢购→下单→库存递减 / 限购拦截 / 幂等重放 / 取消回补 / **后台编辑落库** / **有未关闭订单的活动删除被拒且行仍在** / **无订单活动可删** / **订单全关闭后可删且不留残留**）
node scripts/e2e/admin-entry-ui-check.mjs            # 后台入口 32 项（未登录/普通用户看不到入口 / 运营两条入口都能进运营概览 / **刷新·新开标签页·只带 dd_rt 重开浏览器后入口仍在** / next 回跳不被破坏）
node scripts/e2e/header-layout-ui-check.mjs          # 顶栏布局 88 项（4 个角色 × 6 档宽度：**导航一个标签都不许被截断** + 昵称不许折行 + 个人事务入口收进下拉但仍可达 + **下拉可用 Esc/点外部关闭**）
node scripts/e2e/seller-dashboard-ui-check.mjs        # 商家端概览 31 项（**五张真图表：canvas 非零 / 摘要与接口一致 / 悬停 500ms 内出数据 / 不许再用原生 title** / 权限边界）
node scripts/e2e/admin-charts-ui-check.mjs            # 平台后台图表 28 项（**折线/饼/柱状/分布四类图都在** + 悬停即时 + 数据与接口一致 + 普通用户 403）
node scripts/e2e/share-circle-ui-check.mjs            # 分享圈 21 项（**发布→信息流→详情评论→点赞 toggle→我的分享编辑/删除** + 话题聚合路由 + 未登录入口可发现性 + 无报错）
node scripts/e2e/my-listings-ui-check.mjs             # 我的闲置 47 项（发布→审核中→过审→在售→下架→重新上架→编辑回审核中→软删；未实名 403；公开页匿名可用）
UI_BASE=http://localhost:8088 node scripts/e2e/home-ui-check.mjs   # 网页容器验收（S1）：同一套测试换个入口跑，证明容器承载的是**真应用**（另可跑 header-layout / notice-chat-follow）
# 优惠券 43 项：夹具由一键运行器自动建/拆；手工跑则自己执行 coupon-ui-fixture.ps1 -Action create / drop

# ================= 小程序「无浏览器」自动化测试 =================
# 小程序逻辑层没有 DOM，真正要驱动的是 Page 实例的生命周期与 setData ——
# 因此不需要浏览器：runtime.js 用 node:vm 沙箱 + wx.* 桩 + 自实现 require，
# **直接加载仓库里真实的页面 js**（不是复制一份），页面改了而测试没改就会红。
node --test scripts/mp-harness/ai-page.test.js       # AI 页 4 项（快捷问题接住 / 真实发消息 / 换话题清追问）
node --test scripts/mp-harness/pages-smoke.test.js   # 30 个页面逐个挂载（33 项），挡住生命周期抛错与卸载清理写错
node --test scripts/lib/internal-token-parity.test.js # 内部凭据 HMAC：JS 侧与 PowerShell 侧对拍 4 项
node scripts/mp-harness/behavior.test.js             # 页面行为 31 项：长 id 按字符串上行 / 加购 PATCH 的 skuId 在 URL / 下单带 idempotentKey / 跳订单页用 navigateTo / 确认订单金额三行自洽 / 领券中心取数时机
# 前置：网关在跑（http://127.0.0.1:8080）
```

十四套脚本的共同约定（踩过坑才有的）：
- 每次运行使用**随机手机号**，可重复执行；
- 夹具（价格、库存、佣金）一律**从接口动态取**，不硬编码 —— 否则种子数据一改，测试就会「通过但其实是错的」；
- 凡改库必**先记录原值、复原写回原值**，并在结尾**自证**「库内数据已复原」；
- 跨轮次的断言按「本次测试开始时间」**限定范围**，不被功能上线前的历史数据误伤；
- 部分脚本加 `-SkipDbCheck` 可跳过改库段落，只跑接口断言。

### 交接资料（换人/换账号接管先看这里）

`docs/handover/` 下 9 篇：**00 交接总纲**（10 分钟接管）+ 上手环境 / 架构导航 / 环境变量与密钥（脱敏）/
数据库表结构 / 接口契约 / 前端手册 / 测试证据 / 风险与接手建议。
`manifest.json` 记录每篇在腾讯文档与乐享知识库的 id 与链接；云端两处已同步。

**AI Agent 接管包**（给 AI 读，与上面的人读版互补）：仓库根 `AGENTS.md` + `docs/agent/00~08`
（接管指令与硬规矩 / 领域模型 / 代码地图 / 架构不变式 / 接口契约全集 / 开发任务手册 /
测试门禁 / 待办与验收标准 / 环境与踩坑）。

### 降级方案：不开 Docker

中间件也可用免安装版跑（`scripts/start-infra.ps1` 起 MySQL/Redis，`scripts/start-local.ps1 -Mysql` 起三进程），
但这是**备用**路径：Docker Desktop 装在 `C:\Program Files\Docker`，其引擎是独立进程，
启动助手在部分 Windows 家庭版环境需要 WSL2（详见 `docs/GITHUB-PUSH-GUIDE.md` 与记忆留档）。

---

## 二、模块结构

```
duoduo-pet-mall-java/
├── pom.xml                     聚合父 POM：统一版本、编译参数（release 21）、插件管理
├── Dockerfile                  业务服务共用镜像（temurin:21-jre-alpine，非 root 运行）
├── docker-compose.yml          10 容器编排（中间件 + 7 个业务服务；另有可选 duoduo-es，profile=search）
├── maven/                      项目专用 Maven 配置（阿里云镜像）与本地环境变量脚本
├── docs/
│   ├── ARCHITECTURE.md         完整架构设计（服务拆分、分库分表、容量模型、降级矩阵、路线）
│   ├── PLAN.md                 开发看板：里程碑 / 端形态 / 支付渠道 / 问题与协助区 / 已知边界
│   ├── GITHUB-PUSH-GUIDE.md    推送指南
│   └── sql/                    容器初始化 SQL（00 建库授权 + 01~05 从零建库）与 incremental/（06~08 存量库增量，不进容器初始化目录，T-46）
├── scripts/                    一键启动、冒烟测试（22 套接口断言 + 7 套真实浏览器 UI 断言；run-all-smoke.ps1 一键全量 / ci-run.ps1 子集 / export-openapi.ps1 契约导出 / search-smoke.ps1 需 ES）
├── .github/workflows/ci.yml    GitHub Actions：编译 + 冒烟子集（A6）
├── miniprogram/                A13 买家端小程序骨架（测试号阶段，主链路复用网关契约；后端微信登录已就绪）
├── docs/openapi/               各服务 OpenAPI JSON + endpoints.md 端点摘要（A6 产物）
├── duoduo-common/              通用：统一响应 / 错误码 / 业务异常 / 雪花 ID / 用户上下文 / JWT
├── duoduo-common-web/          Servlet 侧基础设施：全局异常处理 / 请求 ID / 身份上下文透传
├── duoduo-gateway/             API 网关：路由 + JWT 前置校验 + 身份透传（WebFlux）
├── duoduo-user/                用户服务
├── duoduo-product/             商品服务（含库存主数据）
├── duoduo-trade/               交易服务（购物车 / 订单 / 冻结台账 / 支付渠道 / 资金托管）
├── duoduo-fulfilment/          履约服务（评价与晒单 / 物流轨迹 / 售后仲裁 / 信用分）
├── duoduo-marketing/           营销服务（优惠券：领券中心 / 我的券包 / 锁·解·核销 / 补偿调度）
├── duoduo-social/              社交服务（第四十七轮：关注 / 私信与卡片消息 / 商品咨询；通知中心第二批）
└── frontend/                   买家端 Web 商城（React 18 + TS + Vite，见第七节）
```

**分层纪律**：Controller 不写业务逻辑；Service 不依赖 `HttpServletRequest`；
数据访问统一走 Mapper（MyBatis-Plus），参数化查询，禁止字符串拼接 SQL。

---

## 三、已实现的接口

统一响应：`{"code","message","data","requestId","timestamp"}`；失败时 `code` 与 HTTP 状态码语义一致
（400 参数 / 401 未认证 / 403 无权限 / 404 不存在 / 409 冲突 / 422 业务规则 / 429 限流 / 503 降级）。

### 用户域（`duoduo-user`）

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| POST | `/api/auth/register` | 注册并直接登录（手机号唯一，冲突转 409） | 免 |
| POST | `/api/auth/login` | 密码登录；账号不存在与密码错误返回同一提示，防手机号枚举 | 免 |
| POST | `/api/auth/refresh` | 刷新令牌轮转：旧令牌立即吊销，签发新对 | 免 |
| POST | `/api/auth/logout` | 吊销刷新令牌并清 Cookie | 免 |
| GET / PATCH | `/api/users/me` | 当前用户资料 / 改昵称头像 | 需登录 |
| POST | `/api/users/me/realname` | 实名认证（身份证仅存掩码） | 需登录 |
| POST | `/api/users/me/merchant` | **申请成为卖家**（实名前置：未实名 422；已是卖家则幂等返回）；角色升级后**需重新登录**才生效 | 需登录 |
| GET / POST | `/api/pets` | 宠物档案列表 / 新建（首只自动设为当前宠物） | 需登录 |
| PUT / DELETE | `/api/pets/{id}` | 更新 / 删除档案（默认宠物自动顺延） | 需登录 |
| POST | `/api/pets/{id}/default` | 设为当前宠物 | 需登录 |
| GET / POST | `/api/addresses` | **收货地址簿**（A8）：列表（**默认地址排最前**）/ 新增（**首个自动成为默认**） | 需登录 |
| PUT / DELETE | `/api/addresses/{id}` | 修改 / 删除（**删默认会自动顺延**到剩下的一条） | 需登录 |
| POST | `/api/addresses/{id}/default` | 设为默认（事务内先清旧默认，库内恒有且仅有 1 条默认） | 需登录 |
| GET | `/api/addresses/default` | 取默认地址；**没有默认时返回 200 + `data=null`**（不是 404，前端据此渲染「去添加」） | 需登录 |
| GET | `/api/addresses/internal/snapshot` | **内部快照**（供交易域下单取收货信息）：`?userId=&addressId=`；`fullAddress` 与地址簿接口**逐字相同**（拼接口径只有一处） | **内部凭据**，不进网关 |

> **地址簿的两条纪律**：
> ① **归属校验统一返回 404** —— 改 / 删 / 设默认他人地址一律与「id 不存在」**同码同文案**，刻意不区分
> 「不存在」与「无权限」，避免用状态码差异枚举他人地址 ID（这与 `docs/agent/07` 原文写的「403」是有意偏离）。
> ② **下单写的是快照** —— 用 `addressId` 下单时，收货人 / 电话 / 完整地址在**下单瞬间**写入 `t_order.receiver_*`，
> 此后改 / 删地址簿**都不影响历史订单**。传了 `addressId` 就**只认它**（直填三字段被忽略），避免两套来源冲突。

### 商品域（`duoduo-product`）

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| GET | `/api/categories` · `/flat` · `/{id}` | 两级类目树 / 扁平列表 / 单个类目 | 免 |
| GET | `/api/home` | 首页聚合：类目 / 推荐 / 新品 / 品牌 / 闲置 / 物种精选 | 免 |
| GET | `/api/products` | 商品检索（参数见下表） | 免 |
| GET | `/api/products/{spuId}` | 商品详情：SKU、适用属性聚合、同类推荐 | 免 |
| GET | `/api/products/skus/batch?ids=2001,2002` | **批量 SKU 采购视图**（供交易服务调用；一次取几十个，避免 N 次跨服务调用） | 免 |
| POST | `/api/products/skus/stock/{freeze,release,deduct,restock}` | **库存写接口**（冻结 / 释放 / 扣减 / 退货入库），见下 | **内部凭据** |
| GET | `/api/listings/search` · `/api/listings/{id}` | C2C 闲置**列表**（只返回 `ON_SALE` + `APPROVED`）/ **详情** + 同款全新推荐。**详情每次访问浏览量 +1**（数据库侧原子自增，响应即最新值；卖家看自己的走 `mine/{id}` 不计）。**卡片的两个计数口径不同**：`viewCount` = 浏览次数（打开/下拉刷新各 +1，同一用户可累加）；`wantCount` = 想要人数（买家首次咨询时 +1，按买家去重，每人只记一次） | 免 |
| POST | `/api/listings` | **发布闲置**（落 `PENDING` 待审；需实名；`sellerId`/`status`/`auditStatus` 概不接受客户端传值） | 需登录 |
| GET | `/api/listings/mine/list` | **我的发布**（`status` 与 `auditStatus` 两维筛选：`ON_SALE`/`OFF_SHELF`/`SOLD` × `PENDING`/`APPROVED`/`REJECTED`，含驳回原因；`auditStatus` 非法值 400）。**服务端两维正交**，小程序 5 个标签按**互斥单状态**拼参（交易三标签必须同时带 `auditStatus=APPROVED`，否则待审件会同时出现在「审核中」与「在售」——见 R53 热修二） | 需登录 |
| GET | `/api/listings/mine/{id}` | **卖家视角详情**（`ListingOwnerView` 全集字段含审核原因；未过审也可读自己条目；非本人 403） | 需登录 |
| POST | `/api/listings/{id}/resubmit` | **重新提审**（仅 `REJECTED` 可调，PENDING/APPROVED 均 422；实名与违禁词复检；成功后回 `PENDING` + `ON_SALE`，清空驳回原因） | 需登录 |
| POST | `/api/listings/{id}/status` | **上架 / 下架**（`action=ON_SALE`/`OFF_SHELF`；幂等开关；上架要求已过审；他人闲置 403、不存在 404） | 需登录 |
| POST | `/api/listings/{id}/edit` | **编辑闲置**（需实名；内容变更即回 `PENDING` 重新送审——审核结论针对旧内容；驳回件编辑后 `status` 恢复 `ON_SALE`；已售出 422；非本人 403）。**三段路径**：白名单 `/api/listings/*` 不区分方法，两段会把写端点放成匿名可调 | 需登录 |
| POST | `/api/listings/{id}/delete` | **删除闲置（软删）**（写 `deleted_at` + 落 `OFF_SHELF`，行留库供订单 / 会话 / 举报追溯；七处读路径与 ES 索引全部排除软删；已售出 422；非本人 403） | 需登录 |
| GET | `/api/listings/internal/cards` | **闲置卡批量视图**（供社交域取 `sellerId` 等事实；`X-Internal-Token` 保护） | 内部凭据 |
| POST | `/api/listings/internal/{id}/want?buyerId=` | **「想要」上报**（供社交域在买家**首次**发咨询消息时调用；`INSERT IGNORE` 去重表，**重复上报返回 `counted=false` 且不重复计数**；闲置不存在 404。**刻意用 POST**：这是写操作，GET 会被预取/重试刷歪计数） | 内部凭据 |
| GET | `/api/meta/brands` · `/dictionaries` | 品牌列表 / 筛选字典 | 免 |

`GET /api/products` 查询参数：

| 参数 | 说明 |
|---|---|
| `keyword` | 关键词，命中标题或副标题；**含违禁词直接 422** |
| `categoryId` | 类目 id；传一级类目会**自动展开其下子类目** |
| `brandId` / `supplyType` | 品牌 / 供货类型（`self` 自营、`merchant` 第三方） |
| `minPrice` / `maxPrice` | 价格区间（单位：分），语义是**区间相交**而非「最低价落在区间内」 |
| `species` / `lifeStage` | 按宠物档案筛选（`cat`/`dog`，`BABY`/`ADULT`/`SENIOR`） |
| `inStockOnly` | 只看有货；判据是**可售余量**（`stock − frozen_stock`），被他人下单锁定的不算有货 |
| `sort` | `default` / `sales` / `price_asc` / `price_desc` / `rating` / `newest`，非法值返回 400 |
| `page` / `pageSize` | 页码从 1 开始，`pageSize` 上限 100 |

### 交易域（`duoduo-trade`）

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| GET | `/api/cart` | 购物车列表：实时价格 + 可售库存 + **失效原因码** + 已勾选合计 | 需登录 |
| GET | `/api/cart/count` | 件数角标（不查商品服务） | 需登录 |
| POST | `/api/cart/items` | 加购（同 SKU **累加数量**，不新增行） | 需登录 |
| PATCH | `/api/cart/items/{skuId}` | 改数量 | 需登录 |
| PATCH | `/api/cart/items/{skuId}/selection` | 勾选 / 取消勾选 | 需登录 |
| DELETE | `/api/cart/items/{skuId}` · `/api/cart/selected` | 删除一条 / 清空已勾选 | 需登录 |
| POST | `/api/orders/preview` | **结算预览**（只读）：返回按卖家拆单的 items / 商品总额 / 运费 / 券后应付；**不建单、不占券**；券不可用回 `couponMessage` 而非报错 | 需登录 |
| POST | `/api/orders` | **下单**（按卖家拆单 + 明细快照 + 冻结库存），必须带 `idempotentKey` | 需登录 |
| GET | `/api/orders` | 我的订单列表（可按 `status` 过滤，**每条内嵌资金托管信息**） | 需登录 |
| GET | `/api/orders/{orderNo}` | 订单详情（含明细快照 + 内嵌托管信息） | 需登录 |
| GET | `/api/orders/{orderNo}/settlement` | **查询该订单的资金托管状态**（托管中 / 已放款 / 已退回买家） | 需登录 |
| POST | `/api/orders/{orderNo}/pay` | **支付预下单**（必须带 `idempotentKey`）；`channel=sandbox` 返回**二维码内容与过期时间**，`channel=mock` 即时完成 | 需登录 |
| POST | `/api/orders/{orderNo}/cancel` | 取消（**仅待付款可取消**），并释放冻结 + 作废未支付支付单 | 需登录 |
| POST | `/api/orders/{orderNo}/confirm` | **确认收货**（已发货 → 已完成），并**放款给卖家** | 需登录 |
| POST | `/api/orders/{orderNo}/refund` | **买家自助退款**（仅「已付款且未发货」），资金退回买家 | 需登录 |
| POST | `/api/orders/{orderNo}/ship` | 发货（已付款 → 已发货） | **内部凭据** |
| POST | `/api/orders/internal/{orderNo}/refund` | **运营侧退款**（售后审核通过后执行，已付款 / 已发货均可） | **内部凭据** |
| POST | `/api/orders/internal/sweep` | 手动触发一轮补偿调度（超时关单 / 释放冻结 / 自动确认收货 / 入库补偿 / 支付单过期） | **内部凭据** |

### 支付渠道（`duoduo-trade` /payment）

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| POST | `/api/payments/callback/{channel}` | **渠道支付回调**（HMAC 验签 + 金额核对 + 幂等入账；时间戳过期 / 签名错误拒绝） | 渠道密钥验签 |
| POST | `/api/payments/sandbox/{payNo}` | **沙箱「扫码后确认支付」**（模拟用户在收银台点确认；走与回调同一套验签与入账逻辑） | 免（沙箱专属） |
| GET | `/api/payments/sandbox/pay/{payNo}` | **沙箱收银台页面**（二维码指向这里；展示单号 / 金额 / 状态，带确认按钮） | 免（扫码设备无登录态） |
| GET | `/api/payments/{payNo}` | 查询支付单状态（仅本人） | 需登录 |

### 卖家中心（`duoduo-trade` /api/seller，M5-9）

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| GET | `/api/seller/summary` | 经营概览：各状态订单数 + 托管/已放款/已退回金额（**过滤后汇总仍是全量**） | 卖家或运营 |
| GET | `/api/seller/orders` | 我的订单列表（按 `status` 过滤；**只返回归属自己的单**） | 卖家或运营 |
| GET | `/api/seller/orders/{orderNo}` | 订单详情（含买家收货信息与资金区） | 归属校验 |
| POST | `/api/seller/orders/{orderNo}/ship` | **发货**（填物流公司/单号，落 `t_order` 并回显）；资金**仍托管**，不触发放款 | 归属校验 |
| POST | `/api/seller/orders/{orderNo}/refund` | **售后退款**（已付款/已发货；已完成需追偿或平台垫付，返回 422） | 归属校验 |
| GET | `/api/seller/settlements` | 结算对账（分页 + 全量汇总金额） | 卖家或运营 |

**权限模型（刻意的三条边界）**：

- **准入与归属分开校验**。准入看角色（非卖家 403），归属看 `t_order.seller_id`。
  **订单不存在返回 404、存在但不是你的返回 403**——刻意区分：订单号是雪花 ID 不可枚举，
  若统一 404 会让越权探测看起来像「查无此单」。
- **状态机不重写**。发货与退款转调 `OrderService` 的 `shipInternal` / `refundInternal`，
  与内部凭据入口共用同一段逻辑，避免两处状态机各改各的。
- **运营可见平台自营**（`sellerId=0`），因为当前商品域没有商家字段，自营单没有真正的卖家账号可登录。
- 接口**不接受任何 `sellerId` 参数**——可见范围完全由登录态推导，杜绝「换个 ID 看别人的单」。

> `/api/seller/**` **不在网关白名单里**（必须带登录态），也不受 `InternalTokenFilter` 管辖。
> 内部凭据只能证明「你是自己人」，证明不了「这单是你的」。

购物车条目的失效原因码（机器可读，前端只管展示文案）：
`SKU_NOT_FOUND` / `OFF_SHELF` / `OUT_OF_STOCK` / `STOCK_NOT_ENOUGH`。
**失效条目不计入结算金额** —— 否则结算页金额与订单金额会对不上。

### 平台管理后台（`/api/admin/**`，M10）

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| GET | `/api/admin/reports/overview` | 运营报表：GMV / 总订单 / 已付款 / 退款率 / 转化率（全平台口径，SQL 聚合） | 运营 |
| GET | `/api/admin/settlements/summary` | 资金对账：托管中 / 已放款 / 已退回三组金额与笔数、佣金 | 运营 |
| POST | `/api/admin/settlements/compensate` | **平台垫付退款**（已完成订单退款：金额按买家实付记卖家欠款，托管单 `RELEASED→ADVANCED`，订单进 `REFUNDED`；**不回补库存**；`uk_seller_debt_order` 保证一笔订单只垫付一次，重复调用 409） | 运营 |
| GET | `/api/admin/settlements/debts` | 卖家欠款列表（可按 `status=OUTSTANDING/SETTLED/WRITTEN_OFF` 过滤） | 运营 |
| POST | `/api/admin/settlements/debts/{id}/settle` · `/write-off` | 欠款收回 / 核销（CAS 条件更新，二次调用 409） | 运营 |
| GET | `/api/admin/settlements/abnormal` | 异常挂账：已付款却缺托管单的订单（`LEFT JOIN` 排查） | 运营 |
| POST | `/api/admin/settlements/backfill?dryRun=` | **历史订单托管单回补**（A1 / I-14）：按订单状态推断资金状态（`PAID`/`SHIPPED`→`CUSTODY`、`COMPLETED`→`RELEASED`、`REFUNDED`→`REFUNDED`），金额取 `pay_amount`、佣金按当前 `PLATFORM_FEE_BPS` 重算并回传口径说明；`?dryRun=true` **只出计划、一行都不写库**；不带参数即执行；幂等靠 `uk_settle_order`（重复执行不产生重复单）；**不触碰已有托管单的订单**，也不给 `PENDING_PAYMENT`/`CLOSED` 补账（钱从未收过） | 运营 |
| GET/POST/PUT/DELETE | `/api/admin/categories`、`/{id}`、`/{id}/status` | 类目增改删 + 启停用；**后台列表含停用项**（可重新启用），公开接口仍只暴露 ACTIVE | 运营 |
| GET/POST/PUT/DELETE | `/api/admin/brands`、`/{id}`、`/{id}/status` | 品牌同上 | 运营 |
| GET | `/api/admin/listings` | 闲置审核工作台列表（含待审 / 已驳回，按 `auditStatus` 过滤） | 运营 |
| POST | `/api/admin/listings/{id}/audit` | 审核：`APPROVE` / `REJECT`（驳回必须填原因）；`audit_reason` / `audit_at` 落库留痕 | 运营 |
| GET | `/api/admin/orders`、`/{orderNo}` | 全平台订单列表（状态 / 订单号前缀过滤）与详情（无归属限制） | 运营 |
| POST | `/api/admin/orders/{orderNo}/force-close` | **强制关单**（仅待付款）：复用状态机合法流转 + 作废未支付支付单 + 释放冻结 + 状态日志记 OPERATOR | 运营 |
| GET | `/api/admin/users?role=&keyword=` | **运营账号列表**（A7）：分页 + 角色过滤 + 关键字（手机号/昵称模糊匹配）；**脱敏视图**——不下发实名姓名 / 身份证 / 完整手机号 | 运营/ADMIN |
| POST | `/api/admin/users/{id}/role` | **变更用户角色**（A7，**仅 ADMIN**）：角色白名单（USER/SELLER/OPERATOR/ADMIN）；自己降权 **422**、最后一个 ADMIN 不可降 **422**、同角色幂等 200 不留审计；每次变更写 `t_audit_log`；目标用户**重新登录后**新角色生效（notice 提示） | ADMIN |

**权限模型**：统一 `OperatorGuard.requireOperator()`（OPERATOR / ADMIN，忽略大小写）；
网关层 `/api/admin/**` 不进白名单（未登录 401），服务层校验角色（非运营 403）——两层各挡一种流量。
已付款 / 已发货订单**不开**后台干预口子，一律走退款 / 售后的合法流转。

### 履约域：评价与晒单（`duoduo-fulfilment` `/api/reviews`，M11/P2-1）

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| GET | `/api/reviews/product/{spuId}` | 商品评价分页（`rating` 星级过滤 / `hasImage` 只看带图） | **免登录** |
| GET | `/api/reviews/product/{spuId}/summary` | 评分汇总：平均分 / 星级分布 / 好评率 / 带图数 / 追评数（SQL 聚合） | **免登录** |
| POST | `/api/reviews` | 提交评价：`{orderNo, anonymous, items:[{spuId, skuId?, rating, content?, images?}]}`，一次可提交订单里多件 | 需登录 |
| POST | `/api/reviews/{id}/append` | 追评（每条评价一次，受追评窗口限制） | 仅本人 |
| GET | `/api/reviews/mine` | 我的评价（分页） | 需登录 |
| GET | `/api/reviews/order/{orderNo}` | 某订单下我的评价（详情页判断「还能补评哪几件」） | 需登录 |
| GET | `/api/reviews/reviewed?orderNos=a,b` | 我在这批订单里已评价过的订单号（订单列表页**一次**判断） | 需登录 |

**业务规则（写死在服务层，前端不重复实现）**：

- **只有本人已完成的订单能评价**：订单事实来自交易域内部接口 `GET /api/orders/internal/{orderNo}/fact`，
  返回**不含收货人姓名/电话/地址**（服务间最小必要字段）；请求体里没有任何 `userId` 可供伪造。
- **评的是订单里的商品**：`spuId` 必须出现在该订单明细中，否则 422 —— 挡住「拿 A 单给 B 商品刷好评」。
- **一单一商品一评**：唯一索引 `(order_id, user_id, spu_id)` 兜底，并发撞键转 **409**（不是 500）。
  刻意不是「一单一评」：一个订单常含多件商品，强迫写一条会把对 A 的好评与对 B 的差评挤进同一段文字。
- **图片只收 http/https 直链**（最多 9 张）：放开 `javascript:` / `data:` 等于把 XSS 入口交给任何能发评价的人。
- **匿名只影响展示**：昵称替换为「匿名用户」、头像置空，但真实 `user_id` 始终保留（信用分与风控需要真实主体）。

> 网关白名单**只放行只读的 `/api/reviews/product/**`**：未登录访客能看口碑（购物决策需要），
> 但提交 / 追评 / 我的评价一律强制登录。白名单按路径匹配，因此**不要把需登录的接口挪进 `product/` 子路径**。
>
> 跨域只读依赖两处（都是内部凭据保护，且都裁剪了字段）：交易域订单事实、
> 用户域 `GET /api/users/internal/briefs?ids=`（批量昵称头像，**只回 id/昵称/头像三个字段**）。

### 履约域：售后仲裁工单（`duoduo-fulfilment` `/api/aftersales`，A3）

在此之前，退款只有「卖家一键退」一种形态：买家想退款只能先私下沟通，卖家不同意就在系统里**无路可走**，
平台手里也没有任何可裁定的凭据（诉求是什么、何时提的、卖家怎么答的）。售后单把争议变成一条可查、可留痕、可裁定的记录。

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| POST | `/api/aftersales` | **买家立案**：`{orderNo, type, reason, images?}`。仅本人订单、仅「已发货 / 已完成」 | 需登录 |
| GET | `/api/aftersales/mine` | 我提起的售后（`status` 过滤 + 分页） | 需登录 |
| GET | `/api/aftersales/seller` | 我店铺的售后（可见范围由登录态推导，**不接受 `sellerId` 参数**） | 卖家 / 运营 |
| GET | `/api/aftersales/{id}` | 售后单详情（当事人或运营） | 需登录 |
| POST | `/api/aftersales/{id}/reply` | **卖家处理**：`{action: AGREE\|REJECT, reply?}`；`AGREE` **即执行退款**，`REJECT` 必填说明 | 该单卖家 |
| POST | `/api/aftersales/{id}/escalate` | **买家申请平台仲裁**（`PENDING` 或 `SELLER_REJECTED` 时可进入） | 该单买家 |
| GET | `/api/admin/aftersales` | 运营：全平台售后列表（`status` 过滤 + 分页） | 运营 |
| POST | `/api/admin/aftersales/{id}/arbitrate` | 运营裁定：`{result: APPROVE_REFUND\|REJECT, note}`，**`note` 必填**；`APPROVE_REFUND` 会真的执行退款 | 运营 |

**图片上传（A15，`duoduo-fulfilment` `/api/uploads`）**：`POST /api/uploads`（multipart `file`，需登录）→ `{url}` 绝对直链（评价晒单图 / 售后举证图共用）；`GET /uploads/{filename}` 公开读（UUID 文件名，正则防穿越）。白名单只放 `/uploads/**`，**上传必须登录**（白名单按路径不区分方法，别把上传也放进去）。

**状态机**：`PENDING`（待卖家处理）→ 卖家 `AGREE` 执行退款 / `REJECT` → `SELLER_REJECTED`；
买家可从 `PENDING` 或 `SELLER_REJECTED` `escalate` → `ARBITRATING`；运营裁定 → `REFUNDED` 或 `CLOSED`（两者都是终态）。
**不设「已驳回再改判」的边** —— 要改判只能新立案，这样每条售后单的结论永远只有一次，留痕不会自相矛盾。

几条刻意的规则：

- **钱不在这张表里**。裁定通过时调交易域**既有的**运营侧退款入口
  （`POST /api/orders/internal/{orderNo}/refund`），退款金额由那边回填 ——
  退款的状态机、托管 CAS、退货入库补偿在交易域只有一份实现，这里再写一条「售后专用退款」等于给自己造第二个事实来源。
- **顺序是先让钱动、再改售后单状态**。反过来会得到「售后单显示已退款、钱其实没退」这类最难查的账目分歧。
- **已完成订单（钱已放款给卖家）的裁定退款会被拒 422**，并把交易域的原话（需走追偿 / 平台垫付）透传给运营。
  这是项目已知边界 B-01，不是缺陷 —— 买家诉求该被受理，所以**立案**放行；但系统退不了这笔钱，只能走人工追偿。
- **状态迁移一律 CAS 条件更新**（把「当前必须是 X」写进 WHERE）：卖家点「同意」与运营点「裁定」同时到达时，
  数据库只让一个生效；钱那一侧的托管 CAS 会挡住第二次扣款。
- **唯一键是 `(order_id, type)`**，不是 `order_id` —— 同一订单「仅退款」被拒后，仍可另立「退货退款」。

自测记录：`scripts/aftersale-smoke.ps1` **101 项断言全绿**，覆盖 07 篇 A3 的六条验收标准
（非当事人 403 / 未发货 422 / 重复立案 409 / 裁定后订单与托管同步 REFUNDED / 缺裁定说明 422 / 全流程可查有留痕），
外加卖家拒绝→买家升级→运营裁定的完整闭环、卖家「同意即退款」这条独立资金路径、
同单不同类型可各自立案、越权读详情 403、卖家与运营列表准入与状态过滤，以及**已完成订单裁定退款被拒**的边界。
回归 `review-smoke.ps1` 59/0、`settlement-smoke.ps1` 125/0。

### 售后三端页面（`frontend/src/pages/`，A3 配套）

| 路由 / 位置 | 角色 | 内容 |
|---|---|---|
| `/after-sales/new?orderNo=…` | 买家 | 申请售后：类型（仅退款 / 退货退款，hint 写清两者区别）、诉求、举证图直链 |
| `/after-sales/:id` | 买家 | 详情：状态步骤条、卖家说明、裁定说明、退款金额；`PENDING`/`SELLER_REJECTED` 时可**申请平台仲裁** |
| `/after-sales` | 买家 | 我的售后（状态页签过滤 + 分页） |
| 订单列表 / 「我的」页 | 买家 | 「申请售后」只在**已发货 / 已完成**的订单上出现；未发货时买家本就能自助退款，走售后反而绕远 |
| `/seller/after-sales` | 卖家 | 店铺售后列表 + 处理弹窗：同意**立刻退款** / 拒绝**必填原因** |
| `/admin/after-sales` | 运营 | 全平台售后列表 + 裁定弹窗：支持退款（真的执行）/ 驳回，**说明必填** |

**关键实现决策**：

- **售后单 `id` 对外以字符串传输**。它是 18 位雪花值，远超 JS 安全整数上限（`9007199254740991`），
  `JSON.parse` 会静默改写成另一个数 —— 拿它拼 URL 必然打到不存在的单子。
  后端 `AfterSaleView.id` 类型是 `String`，前端从路由参数原样取用、**不做任何数值转换**。
- **弹窗默认结论是「驳回」而不是「支持退款」**：误点一下不会直接把钱退出去。
- **三端共用一份状态文案与配色**（`pages/afterSaleShared.tsx`）：各写一份必然漂移，
  而「同一状态在两个页面显示成两种说法」在用户眼里就是系统坏了。文案本身仍以服务端下发的 `statusLabel` 为准。
- **步骤条不按状态一一对应**：`SELLER_REJECTED` 是「卖家已处理完」而不是独立一阶段，硬映射会画出一条现实中不存在的路径。

自测记录：`scripts/e2e/aftersale-ui-check.mjs` **44 项断言全绿**（截图 `frontend/docs/screenshots/as_*.png`），
用真实 Chromium 走完整一条链：买家下单 → 申请售后 → 卖家拒绝（先验证空原因被拦）→ 买家申请平台仲裁
→ 运营裁定（先验证空说明被拦）→ 支持退款并执行 → 回到接口层确认**订单与托管同步 REFUNDED、退款金额等于实付**。

> ⚠️ 踩坑：前端路由是 `after-sales`（连字符），接口路径是 `/api/aftersales`（无连字符），两者**刻意不同**。
> 卖家端与运营端的入口链接曾写成 `/seller/aftersales`，命不中路由直接落到「页面不存在」——
> 这类问题 `tsc` 与 `vite build` 都发现不了，只有真实浏览器会现形。

### 履约域：物流轨迹（`duoduo-fulfilment` `/api/logistics`，A2）

| 端点 | 准入 | 说明 |
|---|---|---|
| `POST /api/logistics/internal/{orderNo}/tracks` | `X-Internal-Token` | 批量摄入轨迹节点（物流推送 / 交易域发货通知）；**直连服务，不经网关** |
| `GET /api/logistics/orders/{orderNo}/tracks` | 买家本人 / 该单卖家 / 运营 | 按发生时间正序返回；无轨迹回**空列表**（正常状态，不是 404） |

**节点字典 `LogisticsNode`（白名单，推送方只能选用不能自创）**：
`PICKED_UP` 已揽收 · `IN_TRANSIT` 运输中 · `ARRIVED_HUB` 到达分拨中心 ·
`OUT_FOR_DELIVERY` 派送中 · `SIGNED` 已签收 · `REJECTED` 已拒收 · `EXCEPTION` 物流异常。
中文文案由**服务端**下发（`nodeLabel`），前端不维护第二份映射 —— 两份映射迟早漂移。

**关键实现决策**：

- **幂等靠唯一索引 `uk_track_order_node_time`（订单号 + 节点码 + 发生时间）**，
  撞键即「跳过」，回执里 `inserted` / `skipped` 分开计数 —— 物流推送的常态就是失败重推 / 补推 /
  定时对齐，只回一个「成功」的话，重推与漏推在监控上看不出区别。
- **批量全有或全无（先全部校验、再逐条写）**：批次里混一条非法节点就整批 400 且一条都不落。
  若边校验边写，调用方无从知道前面几条落了没有，只能整体重推 —— 而重推又会被判成重复。
- **时间存「发生时间」而不是入库时间**，无时区写法**默认按 UTC 解释**（不是服务器本地时区）：
  按本地时区解释会让同一份报文在开发机（东八区）与容器（UTC）落成相差 8 小时的两条节点，
  唯一索引随之兜不住重复。
- **交易域发货后补推「已揽收」**：`AFTER_COMMIT` 事件 + best-effort —— 通知失败只记日志、
  绝不让发货失败；且**必须**在事务提交之后（否则履约域可能查到一个尚未提交的订单，
  反把节点写入判成「订单不存在」）。`occurredAt` 用**发货时间**而不是通知时刻，
  这样重推永远撞同一个唯一键。
- **轨迹是对真实运输过程的记录，不从属于订单状态机**：刻意不校验订单状态 ——
  订单可能因退款变 `REFUNDED` 而包裹仍在路上，按状态拦会把真实后续节点全部丢掉。
- **前端买家 / 卖家共用同一个时间线组件**（`components/LogisticsTimeline.tsx`），
  同一包裹不允许两边说法不一致；`shipped` 判定**同时看状态与 `shippedAt`**，
  否则买家一确认收货轨迹就整块消失，而签收记录恰恰是纠纷时最需要回看的。
- 路径**不挂在 `/api/orders/**` 下**：那一段整段路由给交易域，硬挂进去就得靠网关里的
  路由优先级特例来撑 —— 正是历史上造成过「新增端点只 404」的写法。

自测记录：`scripts/logistics-smoke.ps1` **79 项断言全绿**（含★断言总数守卫：任何一条 Check
因写法错误被静默跳过都会让守卫报失败），`scripts/e2e/logistics-ui-check.mjs` **44 项真实
Chromium 断言全绿**（截图 `frontend/docs/screenshots/lg_*.png`）。

### 营销域：优惠券（`duoduo-marketing` `/api/coupons`，A5）

第 6 个业务服务 `duoduo-marketing`（营销域 / 优惠券，:8085）。**领券中心是公开落地页**：
未登录也能看有什么券，点「立即领取」时才引导登录。

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| GET | `/api/coupons/templates` | **领券中心**：券模板列表 + `claimable` / `claimableReason`（能否领由**服务端**判定） | **免登录** |
| POST | `/api/coupons/templates/{id}/claim` | **领券**：重复领取不报错，返回 `replay=true` 与已有券码 | 需登录 |
| GET | `/api/coupons/mine?status=` | 我的券包（`status` 可选 `UNUSED` / `LOCKED` / `USED` / `EXPIRED`，不传为全部） | 需登录 |
| GET | `/api/coupons/mine/usable?amount=` | 结算页可用券：传商品总额，服务端返回每张券可用性与 `bestCouponCode` 最优券 | 需登录 |
| POST | `/api/coupons/admin/templates` | 建券模板（`OperatorGuard.requireOperator()`） | 运营 |

**内部接口**（`X-Internal-Token` 保护，**不进网关**，交易域直连 8085）：
`POST /api/coupons/internal/{lock,release,consume,sweep}` —— 依次对应「下单锁券 / 取消·关单解锁 / 支付核销 / 补偿扫描」；
`POST /api/coupons/internal/trial` —— **只读试算**（第五十二轮新增，供结算预览用，见下）。

**关键设计（为什么这样做）**：

- **券码用随机串而不用雪花主键**：`CP` + 14 位去混淆字符（字符表去掉 `0 / O / 1 / I`）——
  规避 JS `Number.MAX_SAFE_INTEGER` 精度坑（与既有 `AfterSaleView.id` 改 `String` 是同一个坑，项目内编号 INV-16）。
- **状态机** `UNUSED → LOCKED → USED`（`USED` 为终态）；`LOCKED` 可 release 回 `UNUSED`；`EXPIRED` 为终态。
- **锁券 / 核销 / 解锁全部走 CAS 条件更新**（判断写在 SQL 的 WHERE 里），**不用分布式锁**（INV-1）。
- **资金纪律**：营销域只算「能抵多少」，**折扣金额由交易域写入 `t_order.discount_amount`**；
  营销域是被调用方，**不依赖 openfeign**。
- **交易域接入点**：下单 `placeOrder` 先锁券（失败抛 422 带人话原因，如「满 200 元可用」）→ `paySuccess` 核销
  （核销失败则整笔支付不成立）→ `cancel` / `forceClose` / 超时关单**三处**解锁。
- **★ 试算与占券必须分开（第五十二轮）**：`lock` 是跨服务**写**（CAS 真占券），`trial` 是纯读。
  结算预览**只能**调 `trial` —— 否则用户每改一次数量就被占一次券，最后没提交还得干等 TTL 解锁。
  两者共用同一份 `precheck()`（存在 / 归属 / 状态 / 过期 / 未锁 / 门槛 / 抵扣），
  保证「预览说能用」与「下单真能用」结论一致；`trial` 用伪订单号 `__TRIAL__` 且**不落任何状态**。
- **网关白名单收紧为精确路径 `/api/coupons/templates`**：白名单按**路径**而非方法匹配，
  放行 `/api/coupons/**` 会把 `POST /api/coupons/templates/{id}/claim` 也放成匿名可调（与 `/api/reviews/product/**` 是同一条纪律）。
- **`COUPON_LOCK_TTL_MINUTES` 默认 35**：必须**略大于**交易域订单超时关单 30 分钟，
  避免「订单还活着、券已被别人领走」。

自测记录：`scripts/coupon-smoke.ps1` **145 项断言全绿**（含★断言总数守卫）——覆盖
领券幂等 / 并发防超发 / 锁·解·核销 CAS / 折扣落交易域 / 生命周期闭环，另加公开只读边界、内部凭据 403、
并发锁券、归属校验、门槛文案、券码非雪花、sweep 幂等、TTL 环境变量校验、运营建券准入、清理复原。
前端 `/coupons`（领券中心 + 我的券包，**刻意不套 `RequireAuth`**）+ 结算页选券 + 订单详情展示优惠金额与券码。

### 履约域：分享圈（`duoduo-fulfilment` `/api/shares`，R43 起 / R56 补全）

分享圈是「带用户评价的商品口碑内容」，与商品评价同性质 —— **未登录也要能逛**，
所以公开读与需登录写的分界线是这一块的核心设计，且**靠路径形状区分**
（网关白名单按路径匹配、**不区分方法**，见 `GatewaySecurityProperties` 里的逐条注解）。

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| GET | `/api/shares/feed?scope=` | 信息流；`scope=RECOMMEND`（默认）/ `FOLLOWING`（只看我关注的人，需登录） | **免登录**（RECOMMEND） |
| GET | `/api/shares/topics?limit=` | 热门话题榜（发布页「大家都在聊」） | **免登录** |
| GET | `/api/shares/topics/{name}` | 话题聚合页（该话题下的分享） | **免登录** |
| GET | `/api/shares/comments?shareId=&parentId=` | 评论列表；`parentId` 省略 = 一级评论（带回复数），传值 = 该评论的回复 | **免登录** |
| GET | `/api/shares/{id}` | 分享详情（分享落地页数据源） | **免登录** |
| GET | `/api/shares/mine/list` | 我的分享（编辑 / 删除入口的数据源） | 需登录 |
| POST | `/api/shares` | 发布（`spuId` + `content` + 最多 2 个 `topics`） | 需登录 |
| POST | `/api/shares/{id}/repost` | 转发到分享圈（落一条以**我**为作者的新分享） | 需登录 |
| POST | `/api/shares/{id}/edit` | 编辑自己的分享（**只改文字与话题**） | 需登录 |
| POST | `/api/shares/{id}/delete` | 删除自己的分享（**软删**） | 需登录 |
| POST | `/api/shares/{id}/like` · `/{id}/favorite` | 点赞 / 收藏 toggle | 需登录 |
| POST | `/api/shares/{id}/report` | 举报分享 | 需登录 |
| POST | `/api/shares/{id}/comments` | 发表评论 / 回复（`parentCommentId` 省略 = 一级评论） | 需登录 |
| POST | `/api/shares/comments/{commentId}/like` | 评论点赞 toggle | 需登录 |
| POST | `/api/shares/comments/{commentId}/report` | 举报评论 | 需登录 |

**运营（平台管理后台，落在履约域）**：

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| GET | `/api/admin/share-reports?status=` | 举报列表（带被举报内容摘要 `targetExcerpt`） | 运营 / 管理员 |
| POST | `/api/admin/share-reports/{id}/handle` | 处理：`ACCEPT`（成立 → 下架内容）/ `REJECT`，**说明必填** | 运营 / 管理员 |

**为什么公开读的这几条都是一段路径、而所有写路径都在两段以上**：这是刻意的。
白名单里放的是 `/api/shares/feed`、`/api/shares/topics`、`/api/shares/topics/*`、`/api/shares/*`
四条；`AntPathMatcher` 的 `*` **不跨 `/`**，所以 `/{id}/edit`、`/mine/list`、`/{id}/comments`、
`/comments/{id}/like` 这些写路径**永远落不进白名单**。
反面例子（也是本块最需要提醒后来者的地方）：若把评论列表做成 `GET /{id}/comments`（两段），
要放行它就得加 `/api/shares/*/comments` —— 那会**连带把 `POST /{id}/comments`（发表评论）
放成匿名可调**。命名在这里不是风格问题，是安全边界。

**内部接口**（`X-Internal-Token`，不经网关）：`POST /api/socials/internal/notices`
（评论 / 回复通知，**落在社交域**，见上节）；社交域的 `GET /api/socials/internal/following`
供本域关注流取「我关注的人」id 列表。

**其它要点**：`deleted_at` 软删（转发单的引用块要靠回查原帖才能显示，物理删会变空白）；
`edited_at` 与 `updated_at` 分开（后者会被点赞计数等非内容变更刷新，拿它当「已编辑」判据会误报）；
话题名 `name` 唯一（并发建同名话题靠唯一键 + 回查，而不是「先查再插」）；
举报唯一键 `(target_type, target_id, reporter_id)` 兜住并发重复提交。

---

### 社交域：通知中心与合并未读（`duoduo-social`，R50 上线 / R55 补生产者）

通知中心页面 R50 就已上线，但**此前没有任何业务生产者**（全库只有 3 条种子公告），
页面能打开、永远没有新通知。R55 把它补成完整链路（细节见顶部 R55 段）。

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| GET | `/api/socials/notices?page=&pageSize=&category=` | 通知列表（定向通知 + 全站公告合并视图；`category` 可选 `SYSTEM` / `ANNOUNCEMENT` / `FRIEND` / `MERCHANT` / `ORDER`） | 需登录 |
| GET | `/api/socials/notices/unread-count` | 通知未读数（**已排除私信通知行**） | 需登录 |
| GET | `/api/socials/notices/unread-summary` | **合并未读** `{notice, message, total}` —— tabBar 角标的**唯一数据源** | 需登录 |
| POST | `/api/socials/notices/{noticeId}/read` | 标记单条已读（幂等；他人 / 不存在 404，不区分以防枚举） | 需登录 |
| POST | `/api/socials/notices/read-all` | 全部已读（幂等） | 需登录 |
| GET | `/api/socials/messages/unread-count` | 私信未读数（**按条计**，对齐主流 App 的「3 条未读」语义） | 需登录 |

**内部接口**（`X-Internal-Token` 保护，**只写不读、不进网关**，trade / fulfilment 直连 8087）：
`POST /api/socials/internal/notices`（批量推送，返回本次落库条数 `accepted`）、
`POST /api/socials/internal/notices/announce`（全站公告；收件人由服务端固定为 `0`，契约里干脆不给这个字段）。

**关键设计（为什么这样做）**：

- **两阶段落库**：先把整批逐条校验（分类白名单 / 标题正文长度 / `jump_page` 必须落在
  `MiniProgramPages` 的 28 个真实页面内）再统一 insert —— 第 N 条非法则**整批不写**，
  失败就是「什么都没发生」，不会出现半批生效。
- **★ 合并口径落服务端，前端只显示**：`total = 通知（不含私信） + 私信未读`。私信到达会额外写一条
  `bizType=MESSAGE` 的通知行作为「有人找你」的入口，该行**必须从通知未读里排除** —— 否则一条消息会被
  数两次（通知 + 私信），角标凭空翻倍。**注意** `biz_type <> 'MESSAGE'` 对 `biz_type IS NULL` 返回 NULL
  （即不匹配），而**全站公告的 `biz_type` 恰好是 NULL**，故必须写 `biz_type IS NULL OR biz_type <> 'MESSAGE'`。
- **同会话连发合并为一条未读**：窗口内同 `bizType + bizId` 的未读行**原地 update**（id 不变、不新增行）；
  用户**读完后**对方再发会**新插一条**（保证永远有未读、能再次提醒）。合并的边界是「未读」而非「永久一条」。
- **推送一律 best-effort**：trade / fulfilment 侧只 `log.warn`，**异常绝不外抛** —— 消息 / 订单此时已落库，
  为附属通知把主流程判成失败，会让用户看到与事实相反的提示而去重发。
- **`AFTER_COMMIT` 才推**：交易域四处通知都在事务提交后发布 —— 事务没提交完就推，用户点进订单页会**查不到订单**。
- **签收只认「本次真写入」**：物流节点按**首次 insert 成功**才推，被唯一索引挡掉的重推**不重复推**。

小程序侧：`utils/unread.js`（**角标唯一出口**，60s 轮询 + 并发单飞 + 失败返回 `-1` 不清角标 + `>99 → '99+'`）、
`utils/notice.js`（跳转与弹窗冷却）、`components/notice-toast`（顶部滑入卡片，4s 自动收起）。
`scripts/check-miniprogram.js` **第 8 条规则**（error 级）全仓禁止绕过 `utils/unread.js` 直接调 `setTabBarBadge`。
自测：`scripts/r55-notice-smoke.py`（42 项）+ `scripts/r55-unread-badge-test.js`（59 项）。

---

## 四、支付：渠道抽象与沙箱扫码（M8-1）

支付的形态是**真实渠道的完整仿真**（只是不接真实的微信/支付宝商户通道）：

```
买家点「支付」(channel=sandbox)
   │ POST /api/orders/{orderNo}/pay     ← 预下单：落支付单 PENDING + 返回 codeUrl（二维码内容）与过期时间
   ▼
二维码（指向网关 /sandbox/pay/{payNo} 的收银台页面）
   │ 扫码设备打开收银台 → 看到 单号/金额/状态 → 点「模拟扫码支付」
   │ POST /api/payments/sandbox/{payNo} ← 与渠道回调走同一套验签 + 金额核对 + 幂等入账
   ▼
支付单 PENDING → SUCCESS（CAS）；订单 → 已付款；资金进平台托管（CUSTODY）
   ├─ 二维码过期（默认 15 分钟）→ 支付单 EXPIRED（补偿调度自动扫）
   └─ 订单被取消 / 超时关单 → 未支付支付单作废（CLOSED），二维码作废后不可再付
```

**`PaymentChannel` 接口**（`duoduo-trade/.../payment/`）：预下单 / 回调验签 / 查询三个动作，
`MOCK`（即时成功，供自动化测试与本地联调）与 `SANDBOX`（扫码形态）是两个实现；
将来接真实渠道 = 再加一个实现类，**订单与账本代码不动**。

**回调安全（真实渠道同款攻击面，全部有测试覆盖）**：
- HMAC-SHA256 验签（密钥来自环境变量 `SANDBOX_HMAC_KEY`，compose 提供开发默认值）；
- 时间戳窗口校验（默认 ±5 分钟，防重放）；
- **金额核对**：回调金额与支付单金额不一致 → 拒绝入账（422），防止「1 分钱付 100 元的单」；
- 幂等：重复回调只入账一次（先查快路径 + `uk_pay_channel_idem` 唯一索引兜并发 + CAS 状态迁移）。

**口径说明（用户已拍板）**：本项目用**沙箱支付**，不申请真实微信/支付宝商户资质。
沙箱渠道的「扣款」不会真实发生，但**从预下单到入账的每一环（含验签、金额核对、幂等、过期作废）
都与真实渠道一致**——将来若要接真实渠道，只需替换渠道实现并配置商户密钥。

---

## 五、C2C 担保交易（资金托管）

```
下单 ──冻结库存──→ 待付款
                    │ 支付成功（渠道回调/沙箱确认）
                    ▼
                 已付款 ──资金进平台托管（CUSTODY）──→ 已发货
                                                        │ 买家确认收货 / 发货满 N 天自动确认
                                                        ▼
                                                     已完成 ──资金放款给卖家（RELEASED）
   分支：待付款 ──取消/超时──→ 已关闭（钱从未收过，或已释放冻结）
         已付款/已发货 ──退款──→ 已退款（REFUNDED，钱原路退回买家）
```

**订单状态与资金状态是两条时间线**：订单状态回答「货走到哪了」，托管状态
（`t_settlement`）回答「钱现在在谁手里」。分开记之后，「这笔钱到底在谁手里」永远只有一个答案。

| 概念 | 位置 | 说明 |
|---|---|---|
| 订单状态 | `t_order.status` | `PENDING_PAYMENT` / `PAID` / `SHIPPED` / `COMPLETED` / `REFUNDED` / `CLOSED` |
| 支付单状态 | `t_payment.status` | `PENDING`（待支付，沙箱扫码中）/ `SUCCESS` / `EXPIRED` / `CLOSED` / `FAILED` |
| 托管状态 | `t_settlement.status` | `CUSTODY`（托管中）/ `RELEASED`（已放款）/ `REFUNDED`（已退回买家） |
| 冻结台账 | `t_stock_freeze.status` | `FROZEN` → `DEDUCTED` → `RESTOCK_PENDING` → `RESTOCKED`；或 `FROZEN` → `RELEASED` |
| 平台佣金 | `t_settlement.platform_fee` | 按 `duoduo.trade.platform-fee-bps`（默认 100 bp = 1%）计，**结算当刻的口径落库留档** |

几条刻意的规则：

- **资金状态迁移一律用 CAS**（`status = 'CUSTODY'` 写进 WHERE）：定时任务与买家请求可能同一秒到达，
  数据库保证同一笔钱只能出去一次，因此「重复确认收货」「重复退款」天然无副作用；
- **已放款的订单不能直接退款**：钱已到卖家手里，需走追偿或平台垫付（属另一条流程，见 `docs/PLAN.md` 的已知边界）；
- **退款界限在服务端强制**：已付款未发货才允许买家自助退款；已发货必须走售后（货在买家手上，系统无从判断有没有退回来）；
- **退款不阻塞入库**：钱该退就退，货回库是仓库侧的后续动作，由台账 `RESTOCK_PENDING → RESTOCKED` 与补偿调度兜住；
- **发货不等于放款**：买家没见到货之前，钱一直留在托管里；
- **订单关闭必须作废未支付支付单**：否则订单已取消、二维码还能扫出钱来（本期接通了此前一直缺失的 `markClosedByOrder`）。

### 内部接口的凭据

库存写接口、发货、运营侧退款与补偿调度属于**服务间或运营侧**调用，要求请求头 `X-Internal-Token`，
由 `InternalTokenFilter` 校验（**默认开启、失败关闭**）。密钥来自环境变量 `INTERNAL_TOKEN`，
compose 里提供开发默认值，**生产必须注入真实值**。

---

## 六、已落地的工程约束

- **认证**：JWT 短时效 access（15 min）+ 服务端 refresh（7 d）。refresh 只存 SHA-256 哈希并轮转，
  即使库被拖走也无法直接冒用；令牌只走 httpOnly Cookie，**禁止写入 localStorage 或 URL 参数**。
- **身份透传**：网关校验通过后写入 `X-User-*` 内部头，并**先剥离客户端自带的同名头**，防止伪造身份越权；
  业务服务禁止公网直连。服务内一律从 `UserContext` 取用户，**请求体与路径里没有 userId**。
- **边界校验**：所有入参用 Jakarta Validation 注解在 Controller 侧校验；校验失败统一转 400 + 字段级提示。
- **错误体系**：服务层只抛 `BizException`，由 `GlobalExceptionHandler` 统一转换；
  5xx 堆栈只进服务端日志，客户端只拿到规范结构 + requestId。
- **链路追踪基础**：每请求生成 `X-Request-Id`，写入响应头与日志 MDC，跨服务透传；日志为结构化 JSON。
- **分布式 ID**：雪花算法（含时钟回拨保护），各服务使用不同 worker-id（用户 1 / 商品 2 / 交易 3 / 履约 4 / 营销 5）。
  订单号不对外暴露雪花 ID，而是 `时间戳 + 随机 + 校验位`（雪花 ID 的规律性会暴露业务量）。
- **幂等靠唯一索引或 CAS，不靠分布式锁**：
  下单 `uk_order_user_idem`、支付 `uk_pay_channel_idem`、加购 `uk_cart_user_sku`、冻结 `uk_freeze_sku_order`、
  托管 `uk_settle_order`；放款与退款用条件 UPDATE 保证「同一笔钱只出去一次」。
- **库存原子性**：冻结 / 释放 / 扣减 / 入库都是**条件 UPDATE**（判断写进 WHERE），
  由数据库行级原子性防超卖；单次请求内的整批 SKU 在同一本地事务里**要么全成、要么全不成**。
- **跨服务副作用的处理**：库存变更发生在商品服务，**不受交易服务事务保护**，
  因此下单失败时会按逐项结果**精确补偿**；跨服务调用**不放在事务里发**（退款入库走事务提交后的事件），
  避免「远程已生效、本地回滚」造成对不上账；补偿调度作为最终兜底（可重入）。
- **状态机集中在枚举**：合法流转写在一处，非法流转一处拒绝；各接口再收窄自己的合法起点
  （例如已付款订单不允许买家自助取消，退款走独立的 `REFUNDED` 终态而不是复用「已关闭」）。
- **支付回调安全**：HMAC 验签 + 时间戳窗口 + 金额核对 + 幂等入账四道防线（见第四节）。
- **业务规则**：宠物档案的「出生日期 vs 生命阶段」矛盾时拒绝写入并要求确认（对应 PRD PET-05）。

---

## 七、买家端 Web 商城（`frontend/`，M6 / P1-c）

### 技术栈与启动

React 18.3 · react-router-dom 6.28 · TypeScript 5.6（strict + noUncheckedIndexedAccess）· Vite 5.4。
无状态管理库（两个 Context 够用）、无 UI 框架（一份手写 CSS 主题）、**无任何运行时依赖做二维码**（自实现编码器）。

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173，需后端 10 容器在跑（网关 :8080）
npm run build      # tsc --noEmit && vite build
```

### 页面清单（主交易闭环 10 页 + 优惠券 /coupons）

| 路由 | 页面 | 说明 |
|---|---|---|
| `/` | 首页 | `GET /api/home` 聚合一次拉全：类目 / 推荐 / 新品 / 闲置 / 品牌 |
| `/search` | 检索 | 筛选条件全部同步进 URL query，可分享可回退 |
| `/product/:id` | 商品详情 | SKU 选择、过敏原与宠物档案比对提示、同类推荐 |
| `/listings` · `/listing/:id` | 二手闲置 | 列表筛选 + 详情（担保交易规则说明；本期只浏览不下单） |
| `/login` | 登录/注册 | `?next=` 回跳原路径；一键生成体验账号（走真实注册） |
| `/cart` | 购物车 | 以 `skuId` 为键；失效条目标注原因且不可结算 |
| `/checkout` | 结算 | 幂等键进入页面即生成、失败不更换；成功清空已勾选 |
| `/orders` · `/orders/:orderNo` | 订单 | 7 状态页签；详情含二维码、3 秒轮询、资金托管明细 |
| `/pets` | 宠物档案 | 全 CRUD；生日与生命阶段一致性实时提示（对齐后端 422 规则） |
| `/me` | 我的 | 资料 / 实名（身份证只回显掩码） |
| `/coupons` | 优惠券 | 领券中心 + 我的券包双页签；**刻意不套 `RequireAuth`**（领券中心是公开落地页，未登录也看得到有什么券，点「立即领取」时才引导登录） |

### 关键实现决策

- **令牌零接触**：JWT 全在 httpOnly Cookie（`dd_at` 15 min / `dd_rt` 7 d），前端读不到也不需要读；
  登录态唯一判据是 `GET /api/users/me`，401 时用 `dd_rt` 单飞刷新并重放原请求一次。
- **开发代理避免 CORS**：`vite.config.ts` 把 `/api` 与 `/sandbox` 代理到 `VITE_DEV_API_TARGET ?? http://localhost:8080`，
  同源请求天然带上 Cookie，绕开 httpOnly Cookie 的 SameSite 与 CORS 组合问题。
- **金额全用「分」**：接口层不做换算，仅在展示层 `formatPrice`（分 → 元）。
- **筛选字典由后端驱动**：成色 / 物种 / 生命周期等下拉与标签从 `/api/meta/dictionaries` 取
  （后端下发的是码值，前端只做 code→label 映射），避免硬编码字典与后端漂移。
- **错误映射**：4xx 不重试、5xx 与网络错误退避重试 ≤3 次；`ApiError.isAuthError` 区分登录态失效；
  `toFriendlyMessage` 把后端 code 翻成可读文案，绝不把堆栈甩给用户。
- **零依赖二维码**：自实现 UTF-8 Byte 模式 + Reed-Solomon 纠错 + 8 掩码择优（ISO/IEC 18004），
  SVG 渲染。实测 21×21 模块矩阵在真实 Chromium 中清晰可扫。
- **顶栏的信息架构与收口顺序（第六十四轮）**：顶栏**只放「逛 / 买」一级动线**
  （首页 / 逛商品 / AI 导购 / 二手闲置 / 优惠券 / 秒杀），
  **个人事务（个人中心 / 我的订单 / 我的券包 / 我的宠物 / 收货地址）与两个工作台入口
  （商家端 / 进入管理后台）统一收进头像下拉**。
  这不是审美偏好而是硬约束：顶栏所在的 `.container` 最大宽度 1200px，
  一行可用约 1160px，而「9 项基础导航 + 商家端 + 管理后台」需要 812px，
  连同品牌 / 搜索 / 购物车 / 用户按钮共需约 1408px —— **在 1920px 屏幕上都不够**；
  且 `.nav-links` 是唯一可收缩项、滚动条又被刻意隐藏，被挤掉的标签会「静默消失」。
  配套三条样式纪律：`.brand` 与 `.header-right` **不参与收缩**（避免收缩量平摊导致
  「导航被切、搜索变窄、昵称折行」三处同时坏）、`.icon-btn` **禁止文字折行**并给昵称封顶
  88px + 省略号（昵称最长 20 字，不封顶会反过来把导航挤出去）、
  收口顺序是 **1200px 收起搜索框 → 1024px 收起品牌文字**（宁可收起搜索，也不切掉导航）。
  自测见 `scripts/e2e/header-layout-ui-check.mjs`（4 个角色 × 6 档宽度 80 项）。


自测记录：`tsc --noEmit` 零错误；curl 冒烟覆盖注册/加购/下单幂等/支付/托管/退款全链；
真实浏览器（Chromium 153）逐页验证 12 个路由的渲染与交互，含真实 UI 填单下单 → 模拟扫码 →
3 秒轮询自动刷新为「已付款」→ 托管明细金额核对（¥59.00 / 服务费 ¥0.59 / 卖家实收 ¥58.41）。
验证截图见 `frontend/docs/screenshots/`。

已知边界（对齐 `docs/PLAN.md`）：AI 导购「多多」已部署（服务 `duoduo-ai` `:8086`，形态由
`DUODUO_AI_MOCK_ENABLED` 决定：`true` 用真实候选拼回复、`false` 走 DashScope 真实模型），
Web 端 `/ai` 与小程序 `pages/ai` 均已上线、未登录也可问答（限流 20 次/分钟/IP）；
闲置发布与下单入口留待后续迭代；演示库**不预置演示账号**（登录页提供一键生成体验账号）。

---

## 八、商家端 Web（`frontend/src/pages/seller/`，M9 / P1-f）

与买家端**同一套工程、同一套登录态**（`/seller/**` 与 `/**` 共用 `frontend/`），只是按角色开入口。

| 路由 | 页面 | 说明 |
|---|---|---|
| `/seller` | 经营概览 | 六状态订单数 + 托管 / 已放款 / 已退回三栏资金；平台自营范围提示 |
| `/seller/orders` | 订单管理 | 状态页签过滤；列表卡片带买家收货信息；**立即发货**弹窗（物流公司 / 单号） |
| `/seller/orders/:orderNo` | 订单详情 | 状态步骤条 + 履约信息 + 资金区 + 发货 / 退款表单 |
| `/seller/settlements` | 结算对账 | 分页表格 + **全量汇总三栏**（过滤不变）；与买家端口径同源 |

**成为卖家**：实名认证后，在「我的」页点「申请成为卖家」（`POST /api/users/me/merchant`）。
角色快照写在 access token 内，**需重新登录**才能看到商家端入口（前端已在提示里写明，见 `docs/PLAN.md` B-08）。

**关键实现决策**：

- **`RequireSeller` 只校验登录、不拦角色**。前端拦角色是体验优化，不是安全边界——
  真正的边界在后端 `SellerService.requireSellerScope()`（403）与 `requireOwnOrder()`（404/403）。
  前端硬拦会造成「角色刚升级、token 还没换，入口就消失」的假故障。
- **`canAccessSellerConsole` 统一判定入口可见性**（Layout 导航 + 「我的」页快捷入口），
  角色字符串统一转大写比对，避免 `USER`/`user` 两种写法漂移。
- **概览与对账的汇总取全量、列表取过滤后**——分页只影响列表，不影响「我一共托管了多少钱」。
- **「已完成」的订单不显示退款按钮**（后端同样 422），钱已放款给卖家，需走追偿或平台垫付。

自测记录：`tsc --noEmit` 零错误、`vite build` 通过（60 模块）；后端 `scripts/seller-smoke.ps1` **84 项断言全绿**
（准入 401/403、实名前置 422、归属隔离、越权 403、重复发货 422、已完成退款 422、物流落库与回显、平台自营可见性、夹具自动复原）；
真实浏览器 `scripts/e2e/seller-ui-check.mjs` **29 项断言全绿**（逐页走概览 / 订单 / 状态过滤 / 一键发货 / 详情 / 对账 / 非卖家视角，
每页截图，并收集全部 HTTP 4xx/5xx 与 console 错误），截图见 `frontend/docs/screenshots/sel_*.png`。

两个只在浏览器里现形的坑（已写进脚本注释，别再踩第二次）：
- **前端必须用 `http://localhost:5173`**，不能用 `127.0.0.1` —— 网关 CORS 白名单只认 `localhost`，
  带 `127.0.0.1` 的 Origin 会被按非法跨域直接 403，页面只显示「没有权限执行该操作」，curl 复现不出来；
- **雪花 ID 有 18 位，超过 JS 安全整数范围**，`JSON.parse` 会悄悄把它变成另一个数。

---

## 九、平台管理后台 Web（`frontend/src/pages/admin/`，M10）

运营 / 管理员登录后，**两条入口**同时出现（共用 `isOperator()` 判定，见第六十三轮）：
顶栏导航的「管理后台」+ 头像下拉菜单的「进入管理后台」，都指向 `/admin`（运营概览）。
九个工作台共用一条标签导航：

| 路由 | 工作台 | 内容 |
|---|---|---|
| `/admin` | 运营概览 | 经营报表（GMV / 订单量 / 退款率 / 转化率）+ 资金对账三栏 + **异常挂账表**（已付款却缺托管单，需人工回补） |
| `/admin/audits` | 内容审核 | 待审 / 已通过 / 已驳回三视图；通过后公开可见，驳回必须填原因（留痕：原因 + 时间） |
| `/admin/categories` | 类目管理 | 增改删 + 启停用；状态列 + 按状态切换按钮；**停用项仍可见可恢复** |
| `/admin/brands` | 品牌管理 | 同类目 |
| `/admin/orders` | 订单干预 | 全平台订单（状态过滤 + 订单号前缀搜索）；**仅待付款**可强制关单（确认弹窗说明后果：作废支付单、释放冻结、写状态日志） |
| `/admin/after-sales` | 售后仲裁 | 全平台售后列表 + 裁定弹窗（支持退款**真的执行** / 驳回，说明必填） |
| `/admin/share-reports` | 举报处理 | 列表**直接显示被举报内容摘要**；弹窗默认结论为「举报不成立」（一次误点不会删掉别人的内容） |
| `/admin/operators` | 运营账号 | 脱敏列表 + 检索 + 改角色 Modal（**改角色仅 ADMIN 可操作**） |
| `/admin/seckill` | 秒杀活动 | 建活动（SKU / 秒杀价 / 库存 / 限购 / 起止时间）+ 上下架开关；**列表含已下架活动**（否则下架不可逆） |

**关键实现决策**（与商家端同一套哲学）：

- **入口只对有权限的登录用户显示**（未登录、普通买家都不显示）。未登录直接访问 `/admin`
  由 `RequireOperator` 送到 `/login?next=/admin`，登录成功后自动回到运营概览
  —— 这条 `?next=` 回跳是既有链路，与两条入口互不干扰（`scripts/e2e/admin-entry-ui-check.mjs` 已固化）。

- **`RequireOperator` 只校验登录、不拦角色**。非运营进入时页面统一渲染 `AdminDenied` 拦截页；
  真正的边界在后端 `OperatorGuard.requireOperator()`（403）。
- **`isOperator` 与后端 `LoginUser#isOperator()` 同口径**（忽略大小写，OPERATOR/ADMIN 均可），
  入口可见性与接口准入不会出现「页面让进、接口拒绝」的分叉。
- **后台与公开查询分离**：类目 / 品牌的后台列表走不过滤状态的专用方法，
  停用后运营仍可见、可重新启用；公开的树形 / 下拉接口仍只暴露 ACTIVE。

自测记录：后端 `scripts/admin-smoke.ps1` **49 项断言全绿**
（未登录 401 / 普通用户 403 / 运营放行，报表与对账字段，类目品牌增改删启停 + 停用可见，
审核通过 / 驳回留痕且被驳回内容公开不可见，强关全链含冻结释放与重复强关被拒，夹具自建自毁、账号用完降权）；
前端 `tsc --noEmit` 零错误、`vite build` 通过。

## 十、评价与晒单（`frontend/src/`，M11/P2-1）

| 路由 / 位置 | 内容 |
|---|---|
| 商品详情页评价区（`components/ReviewSection.tsx`） | 评分汇总（平均分 / 五星分布条 / 好评率 / 带图 / 追评）+ 星级与「有图」筛选 + 分页；匿名评价显示为「匿名用户」 |
| `/reviews/new?orderNo=…` | 按订单逐件评价：**已评过的商品不再出现在表单**，只列还差哪几件；大星号打分、内容、图片直链、匿名勾选 |
| `/reviews/mine` | 我的评价 + 追评弹窗（可否追评由服务端 `appendable` 决定，前端不自己算窗口期） |
| 订单列表（`/orders`） | 「已完成」订单显示**去评价 / 已评价**；已评状态由 `/api/reviews/reviewed` **一次批量**查回，不是每张卡片各发一次请求 |
| 「我的」页快捷入口 | 我的评价 |

**关键实现决策**：

- **评价入口只挂在订单上**，不在导航里另开「评价中心」：评价总是从「我买过的那单」出发，
  这与用户的真实心智一致（也是主流电商的形态）。
- **依赖数组用 `orderNos.join(',')` 而不是数组**：数组每次渲染都是新引用，
  会让 `useAsync` 每轮重发请求 —— 这是 React 里最容易被忽略的性能坑之一。
- **降级而非硬失败**：用户服务不可用时评价仍可看（昵称兜底为「用户 1234」）；
  图片 JSON 脏数据按无图处理，不让整页 500。

**评分是真实数据驱动的（第七十三轮）**：SPU 的 `rating` / `rating_count` 不再是种子假数据——
评价创建（仅**已收货/已完成**订单的本人可评，服务端逐单校验）后经 `ReviewCreated` 事件
（`@TransactionalEventListener(AFTER_COMMIT)`）→ `ReviewMapper.ratingAggregatesBySpu` 聚合 →
Feign `POST /api/products/internal/ratings/recompute`（`X-Internal-Token` HMAC 签名保护）→
回写 `t_spu`，best-effort 不阻断评价主流程；详情页头部评分改读评价域实时聚合
`/api/reviews/product/:id/summary`，**没有真实评价就如实显示「暂无评分」**；
商品卡评分行 `ratingCount > 0` 才渲染。反向护栏：`product-detail-ui-check.mjs`
断言「页面评分区必须与聚合接口逐字一致」，种子假数据（如 4.7/3240 条）无法回归。

自测记录：`scripts/review-smoke.ps1` **59 项断言全绿**（R73 起结尾自动把夹具 SPU 评分
用内部 recompute 写回原值，夹具不再污染 `t_spu`）；
真实浏览器 `scripts/e2e/review-ui-check.mjs` **24 项断言全绿**（截图 `frontend/docs/screenshots/rev_*.png`）：
备一笔已完成订单 → 订单列表看到「去评价」→ 评价页打分提交 → 我的评价看到内容与匿名标记 → 商品详情公开评价区看到该条。

---

## 十一、后续阶段：端形态与支付口径

### 端形态（用户端两端口，商家与平台都是 Web）

| 端 | 形态 | 面向谁 | 支付方式 |
|---|---|---|---|
| 买家端 | **微信小程序** | 买家 | 小程序内拉起支付（沙箱渠道） |
| 买家端 | **响应式网页** | 买家 | PC 上**扫码支付**（沙箱渠道：二维码 → 收银台 → 确认支付） |
| 商家端 | Web（商家服务平台） | 商家 / 卖家 | 商品、订单、发货、售后、结算对账 |
| 平台管理后台 | Web | 平台运营 | 类目品牌、内容审核、订单干预、资金对账、报表 |

### 支付口径（沙箱支付 · 用户已拍板）

支付用**沙箱渠道**：不申请真实微信/支付宝商户资质，资金不真实流转。
但支付链路的工程形态与真实渠道完全一致（预下单 / 二维码 / 收银台 / 回调验签 / 金额核对 / 幂等 / 过期作废），
将来切真实渠道只需新增 `PaymentChannel` 实现并配置商户密钥。

### 阶段路线

| 阶段 | 内容 | 状态 |
|---|---|---|
| **P0** | 工程骨架 + 公共模块 + 网关 + 用户服务 + 商品服务 | ✅ 完成（54 项冒烟） |
| **P1-a** | 容器化开发环境（中间件 + 业务服务全部容器化、Nacos 服务发现） | ✅ 完成（现已 10 容器 healthy，含履约 / 营销 / AI 服务；另有可选 `duoduo-es`） |
| **P1-b** | 交易服务：购物车 ✅ / 下单拆单 ✅ / 库存冻结 ✅ / 状态机 ✅ / 支付幂等 ✅ / C2C 担保交易 ✅ / 沙箱支付渠道 ✅ / **卖家中心 ✅** | ✅ 完成 |
| **P1-c** | 买家端 **Web 商城**（主交易闭环 10 页，接口指向网关 :8080） | ✅ 完成 |
| **P1-d** | 买家端 **微信小程序** | ⏳ |
| **P1-e** | ~~真实支付渠道~~ → **沙箱支付渠道**（已完成；真实渠道为可选后续） | ✅ |
| **P1-f** | **商家端 Web ✅ + 平台管理后台 Web ✅（M10：报表/对账/审核/类目品牌/订单干预）** | ✅ 完成 |
| **P2** | 履约服务（**评价与晒单 ✅ / 物流轨迹 ✅ / 售后仲裁 ✅ / 信用分 ✅**）+ 营销（**优惠券 ✅ A5**）+ XXL-Job 分布式任务 | 🔄 履约四块 + 营销已上线，XXL-Job 待做 |
| **P3** | AI 服务「多多」+ Elasticsearch 检索 + RocketMQ 削峰 + Sentinel 限流 + Seata 分布式事务 | ⏳ |
| **P4** | SkyWalking / Prometheus 可观测 + K8s 编排 + ~~JMeter 压测~~（**A12 已完成**：单机基线压测报告见 `docs/perf/A12-load-test-report.md`，sync ~100 单/s、queued 入队 ~1000 单/s、热点行实验 +40%） | 🔄 压测已完成，可观测与编排待做 |

---

## 十二、变更记录

### 2026-10-05（第六十六轮 · 图表体系重建：悬停即时 / 换主流图表组件 / 多维度 / 后台补图 + A 方案）

用户四项要求：① 柱状图悬停要**即时**（原为 2–3 秒）；② 用**主流图表组件**替换手写实现；
③ 订单状态分布**单一维度扩成多维度**；④ 平台后台补**分布图 / 折线图 / 柱状图 / 饼图**。
另要求一并实现 **A 方案**（Web 端补「我的闲置」）。

**① 悬停 2–3 秒的根因：用错了机制。**
原实现用 HTML 原生 `title` 属性做提示 —— **原生 title 的显示延迟由操作系统决定
（Windows 约 1–2 秒）且无法通过 CSS/JS 配置**。换成 ECharts 自绘 tooltip 后**实测 6–26ms**。

**② 图表组件：ECharts 6（按需引入 + 懒加载）**
- 只 `use` 用到的：`BarChart / LineChart / PieChart + Grid/Legend/Tooltip + CanvasRenderer`；
- `animation: false`（仪表盘不需要入场动画；动画期间悬停是无反馈的，反而显得卡）；
- **懒加载拆包**：新增 `components/lazy-charts.tsx` 作为 `React.lazy` 边界 ——
  构建产物核对：主包 237.07 kB（较改造前 +16 kB），ECharts 独立成
  `charts-*.js 558.14 kB`，**只有打开商家端/后台才会下载**，买家端首屏不为它买单。

**③ 多维度：从 1 个维度扩到 5 个**（`GET /api/seller/analytics`）
订单状态分布（柱）/ 近 14 天成交趋势（折线，**补零**）/ 商品销量 Top 8（横条）/
资金构成（饼）/ 订单类型占比（饼）。原手写的 `MiniBars` 与其 CSS **一并删除**
（留着就是留一个「下次再踩」的坑），并保留其历史教训注释。

**④ 平台后台补图**（`GET /api/admin/reports/charts`）
折线（近 14 天趋势）/ 饼（资金构成）/ 柱状（商品销量 Top 8）/ 分布（订单状态横条）。
**图表不参与页面的 loading 门** —— 报表卡比图表重要，图慢不该把数字一起拖住。

**★ 两个必须记下的坑**

1. **`long value` 被序列化成字符串**（`{"value":"144"}`），ECharts 会把它当类目名、直接画不出图。
   根因是 `LongToStringJacksonModule` **默认把所有 Long 转字符串**（保护雪花 id），
   只有白名单里的字段保持数字。按该模块自己写明的规矩把 `value` 加进白名单，
   并注明它是泛用名（将来若有 id 叫 `value`，请改字段名而不是删这一行）。
   ⚠️ 这一步改的是**共用模块**，因此按纪律**重建全部依赖方**：
   实测 `duoduo-gateway` 镜像**仍是 4 天前的** —— 但它只依赖 `duoduo-common`、
   jar 内 common-web 条目数为 **0**，Maven 跳过它是**正确行为**（不是漏构建）；
   其余 7 个服务逐模块比对宿主机与容器内 `/app/app.jar` **8/8 一致**。
2. **`mvn package` 不带 `clean` 会跳过没有源码改动的模块** → 它们的 jar 不链接新的
   `common-web`。这正是项目台账里记过的「增量检测认为无需重打」。

**★ 顺手修掉一个既存缺陷：Web《二手闲置》列表页对所有人 401**
后端第五十二轮把公开检索从裸 `/api/listings` 挪到了 `/api/listings/search`
（单段路径要让给 `POST /api/listings`，而网关白名单**不区分方法**），**前端漏改**，
于是该页一直报「身份未认证或登录已过期」——而十四套 e2e 此前恰好**没有一套访问过 `/listings`**，
长期没被发现。已修正并补断言；反向验证：改回旧路径后页面立刻复现该报错。

**A 方案：Web 端「我的闲置」**（发布 / 我的闲置 / 编辑三页）
**不套 `RequireSeller`** —— 发布与管理闲置只要求「登录 + 实名」，与「卖家身份」是两条线，
普通用户就是闲置卖家本人。筛选口径与小程序严格一致（**不重不漏划分**：
审核两标签只带 `auditStatus`，交易三标签必须同带 `auditStatus=APPROVED`）。
顺带给共享请求层加了 **multipart 支持**（`body instanceof FormData` 时不写 content-type、
不 JSON 序列化），这样图片上传也走**同一条请求路径**（401 单飞刷新、错误文案映射都在那里），
而不是在页面里另起一个 fetch。

**新增自测（3 套，共 106 项）**
- `seller-dashboard-ui-check.mjs` **31 项**：五张图 canvas 非零 / 摘要与接口一致 /
  **悬停 10–12ms 内出数据**（预算 500ms）/ **断言不许再用原生 title** / 普通用户 403；
- `admin-charts-ui-check.mjs` **28 项**：四类图都在 / 悬停 6–26ms / 数据与接口一致 / 403；
- `my-listings-ui-check.mjs` **47 项**：未登录回跳 → 发布 → 审核中 → 过审 → 在售 → 下架 →
  重新上架 → 编辑回审核中 → 软删（**库里 `deleted_at` 落库、行还在**）/ 未实名 403 /
  公开页匿名可用（守住上一条缺陷）。

**回归**：`npm run build` exit 0；`home` 7/0、`address` 64/0、`admin-entry` 32/0、
`header-layout` 80/0、`seckill` 45/0，加新增 3 套 —— **8 套 334 项全绿**。
UI 自测口径：十二套 454 项 → **十四套 539 项**。

**另记一处既存缺陷（本轮未修，已登记）**：C2C 闲置的**关键词检索恒为 0**。
ES 索引 `duoduo-search` 里有 **124 条 LISTING 文档、0 条 SPU**，
但都是历史自测遗留的旧状态；新建与刚过审的条目**没有进索引**，
而「ES 返回 0 命中」不是异常 ⇒ **不触发降级**，于是静默空结果。
修法属独立工作（闲置索引的增量同步/回补），且本轮的 e2e 已刻意绕开 ES 取数以免混淆归因。

### 2026-10-05（第六十五轮 · 商家端「订单状态分布」柱状图全平 —— 百分比高度解析失败）

用户报障「订单状态分布柱状图全部显示为平的、数值全为零」。

**★ 定性：数据是好的，塌掉的是渲染。** 实测（`GET /api/seller/summary` 与页面 DOM 同时读）：

| 状态 | 接口值 | 柱子 `style.height` | **实际渲染高度** |
|---|---|---|---|
| 待付款 | 0 | 3% | 3px |
| 待发货 | 144 | 52.75% | **3px** |
| 在途 | 20 | 7.33% | **3px** |
| 已完成 | 260 | 95.24% | **3px** |
| 已退款 | 104 | 38.10% | **3px** |
| 已关闭 | 273 | 100% | **3px** |

**根因（CSS 的循环依赖）**：柱高用百分比，而百分比高度只有在**父元素高度确定**时才能解析。
原实现把柱子直接放在 `.bar-col`（`height: auto`）里 —— 「父高由内容决定、内容高又依赖父高」，
浏览器按规范把百分比**退化成 `auto`**，于是**无论数值多大，所有柱子都塌成 `min-height: 3px`**。
`.bar-col` 的计算高度实测只有 25px（只剩标签的高度）。

⚠️ 这类缺陷**接口层与静态检查都测不出来**：接口 200、六个字段齐全且和为 801、
连 `style.height` 的百分比字符串都算对了 —— 只有真开浏览器量一次「实际渲染高度」才看得见。

**改动（2 个前端文件）**：
- `frontend/src/components/ui.tsx`：`MiniBars` 的柱子外包一层 `.bar-track`
  （`flex:1` 在高度确定的列里拿到确定高度，给百分比一个解析基准），并补注释说明「这层不是多余的」；
- `frontend/src/styles.css`：`.bar-col` 加 `height: 100%`，新增 `.bar-track`
  （`flex:1; min-height:0; display:flex; align-items:flex-end; justify-content:center`）。

修复后实测：待发货 47.5px / 在途 6.6px / 已完成 85.7px / 已退款 34.3px / 已关闭 90px（轨道 90px），
**与数值严格成正比**（最差一格相对偏差 0.1%）；值为 0 的「待付款」仍保持 3px 最小高度。

**新增自测** `scripts/e2e/seller-dashboard-ui-check.mjs`（**21 项 / 0 失败**，自包含）：
钉的是**不变式**而非当前数值 ——
① 柱高比例 == 数值比例（任取两根，相对偏差 < 10%）；
② 最高柱 ≥ 轨道 80%（不能塌）；
③ 值为 0 的柱子保持最小高度（不能把 0 画成有高度）；
④ 六状态之和 == 页面上「共 N 笔订单」（图与总数同源）；
⑤ 权限边界：普通用户进 `/seller` 看到入驻引导、顶栏无「商家端」、调 `/api/seller/summary` **403**。

**★ 反向验证**：去掉 `.bar-col` 的 `height:100%` → **精确报红 2 条**
（「最高柱 3px / 轨道 112px」、「最差一格相对偏差 278%」），还原后 21/0。

**顺手更正一处会误导人的注释**：`frontend/src/pages/Listings.tsx` 顶部原写
「本期不支持发布闲置（**没有对应后端接口**）」—— 与事实不符：后端 R52 已建齐六个闲置端点，
且只要求「登录 + 实名」、**不要求 SELLER 角色**，缺口在 Web 页面没接（小程序已有四页）。
已把注释改成准确表述，免得下一个人照它判断「做不了」。

**回归**：`npm run build`（tsc + vite）exit 0。UI 自测口径：十一套 433 项 → **十二套 454 项**。

### 2026-10-05（第六十四轮 · 顶栏标签被截断 / 昵称折行：收紧信息架构 + 补响应式收口）

用户报障「顶栏上有很多标签未能完整显示，部分标签被截断或缺失」。

**先量化再动手**（每项标签的 `getBoundingClientRect()` 与 `.nav-links` 可视框逐项比对，1920px 视口）：

| 身份 | 顶栏应显示 | 实际完整可见 | 被挤掉的标签 |
|---|---|---|---|
| 未登录访客 | 9 项 | 7 项 | 收货地址 · 我的订单 |
| 普通买家 | 9 项 | 8 项 | 我的订单 |
| 运营（含商家端 + 管理后台） | 11 项 | 8 项 | 我的订单 · 商家端 · 管理后台 |

**四条根因**：
1. **`--max-w: 1200px` 封住顶栏** → 一行可用仅约 1160px，而内容需要约 1408px。
   **把窗口拉到 1920px 也毫无改善**（实测 1920 与 1264 的数字完全相同）。
2. **`.nav-links` 是唯一的收缩承担者**（`overflow-x:auto` 使其 flex 自动最小尺寸变为 0）
   → 独自让出 218px（812 → 594），把末位标签推出可视区。
3. **滚动条被显式隐藏**（`scrollbar-width:none` + `::-webkit-scrollbar{display:none}`）且无溢出提示。
   ⚠️ 定性要准：**这些链接仍在 DOM 里、接口 200、跳转正常**，只是被推到可视区之外
   —— 是**布局 + 可发现性缺陷**，不是「标签没渲染」。这也解释了为什么接口层测不出来。
4. **`.header-right` 也在收缩且 `.icon-btn` 没有 `white-space: nowrap`** → 昵称折成两行
   （实测昵称高 32px）撑出固定 38px 的药丸外 —— 与小程序「按钮文字禁止折行」同一类缺陷。

**改动（2 个前端文件）**：

| 文件 | 变更 |
|---|---|
| `frontend/src/components/Layout.tsx` | `NAV` 收成 6 项一级动线；头像下拉补「收货地址」并把两个工作台入口移到个人事务之后；下拉加 `user-menu` 类名（测试定位用）；品牌文字加 `brand-text` 类名 |
| `frontend/src/styles.css` | `.brand` / `.header-right` 加 `flex:none`；`.nav-links` 加 `flex:1 1 auto; min-width:0`；`.icon-btn` 加 `white-space:nowrap` + `flex:none`；新增 `.icon-btn .small{max-width:88px;overflow:hidden;text-overflow:ellipsis}`；`.search-inline` 宽度改 `clamp(150px,18vw,224px)`；`.app-header-inner` gap 20→16px；新增断点 **≤1200px 收起搜索框、≤1024px 收起品牌文字**（原 900px 那条 `display:none` 已并入） |

**信息架构口径**（用户拍板）：顶栏 = 「逛 / 买」一级动线；个人事务与工作台入口收进头像下拉。
**入口是收起来不是删掉** —— 「收货地址」原本**不在下拉里**，本轮补进，并加断言钉住它仍可达。

**新增自测** `scripts/e2e/header-layout-ui-check.mjs`（**80 项 / 0 失败**，自包含、真实 Chromium）：
**4 个角色（未登录 / 买家 / 运营 / 超长昵称运营）× 6 档宽度（1920·1440·1280·1264·1152·1024）**
逐格断言「顶栏一个标签都不许被截断 + 昵称不许折行 + 应有导航项一个不少」，
另加信息架构可达性（从下拉点「收货地址」真能进入地址页）与工作台入口不回归。

**★ 反向验证**（两次，证明两半修复都必要）：
- **还原 `NAV`**（把三项加回顶栏）→ 断言精确报红并列出每个角色各自丢了哪几项。
- **还原 `styles.css` 到改动前**（保留已收窄的 NAV）→ 仍然报红：
  「需要 560px / 可用 559px」**只差 1px 就截断**、且昵称高 32px 撑出按钮。
  → 说明**只改信息架构不够、只改样式也不够，两半都必须改**。

**回归**：`npm run build`（tsc + vite）exit 0；`home` 7/0、`address` **64/0**（该项断言已随
信息架构 1:1 更新为「入口在头像下拉且顶栏不再重复」，**性质不变、强度未降、项数未漂移**）、
`seckill` 45/0、`admin-entry` 32/0。UI 自测口径由「十套 353 项」更新为 **十一套 433 项**。

**本轮踩到的两件事**（已写入 `docs/SELFTEST.md` 排障表）：
- **同名标签两处出现时必须限定断言作用域**：运营账号的顶栏与下拉里都有「商家端」，
  在 `.app-header` 里查会数到 2 个 → 断言假红。已给下拉加 `user-menu` 类名并在断言里限定作用域。
- **下拉菜单没有 Esc 关闭行为**（`Layout` 只在路由变化时 `setMenuOpen(false)`），
  于是断开的下拉浮层会盖住页面右上角的「+ 新增地址」按钮，Playwright 报
  `…nav-link 我的订单 … intercepts pointer events` —— 表象像「按钮点不动」，
  真因是浮层遮挡。测试里改为「再点一次头像收起菜单」。
  ⚠️ **未修**（不属本轮范围）：给菜单补 Esc 关闭是个独立的小改进项。

### 2026-10-05（第六十三轮 · 平台管理后台新增常驻入口「进入管理后台」）

用户报障：后台「只有登录后自动跳转才能进、关闭页面后就再也进不去了」。
定位后发现**顶栏入口其实已经存在且工作正常**（`Layout.tsx` 按 `isOperator()` 插入「管理后台」，
本轮截图②可见），真正的问题是**入口只有一条**——窄屏折叠进「…」或滚动后就找不到，
而用户的诉求恰恰是「稳定、随时能回来」。故把入口做成**两条同源同判**的路径：

- **改动文件**：`frontend/src/components/Layout.tsx`（**唯一一个改动文件**，前端路由与后端零改动）
  - 头像下拉菜单新增一条「进入管理后台」→ `/admin`（运营概览），紧跟「商家端」之后分组；
  - 顶栏那条「管理后台」保持原样。两条**共用同一个 `isOperator()` 判定**，不存在两套口径。
- **权限口径**（按用户拍板的四条规则）：未登录不可见、普通用户登录不可见、
  有权限的登录用户可见；未登录直奔 `/admin` 由既有 `RequireOperator` 送到
  `/login?next=/admin`，登录后自动回到运营概览。
- **★ 顺手挡掉一个隐性陷阱**：`store/auth.tsx` 的 `isAdmin` 写作
  `user.role === 'admin' || user.role === 'operator'`（**全小写**），而后端下发的是大写
  `OPERATOR`/`ADMIN` —— 它**永远返回 false**。本轮入口若用它，表现是「代码没错、入口就是不出现、
  也不报任何错」，属最难归因的一类缺陷。改动处已就地写明「必须用 `isOperator()`」。
  （该字段当前无任何消费方，故本轮**未改动**它，避免动无关逻辑；建议后续删除或改为忽略大小写。）
- **保留既有行为**：所谓「登录后自动跳进后台」实为 `?next=` 回跳链路（`Login.tsx:32/63`），
  并非硬编码跳转。新入口是普通 `<Link to="/admin">`，两者互不干扰 —— 已用 e2e 断言固化。
- **新增自测** `scripts/e2e/admin-entry-ui-check.mjs`（**32 项 / 0 失败**，真实 Chromium，自包含）：
  S1 未登录不显示入口且 `next` 精确等于 `/admin`；S2 普通买家顶栏与下拉都不显示、
  进 `/admin` 渲染拦截页；S3 运营两条入口都能落到运营概览，且**刷新后**、**同浏览器新开标签页**、
  **只带 `dd_rt` 的全新上下文（模拟关掉浏览器再打开）** 三种情形下入口都仍在且真能进去；
  S4 未登录直奔 `/admin` → 登录 → 自动回到 `/admin`，且回跳后顶栏入口同时还在。
- **反向验证**：把下拉入口的判定改成恒假 `false`（等价于误用那个永远是 false 的 `isAdmin`）→
  断言精确报红 2 条、且**只红这 2 条**（其余 30 条仍绿，证明没有级联假红）；还原后 32/0。
- **回归**：`npm run build`（tsc + vite）通过；`run-ui-checks.ps1 -Only home` **7/0 PASS**；
  `seckill-ui-check.mjs` **63/0**（改的是全局 Layout，故必须验后台页仍正常）。
- **踩坑**：首版断言「无 console 报错」把 `status of 401` 也算失败 → 5 条假红。
  401 是**设计内状态**（未登录时 `/api/users/me`、`/api/cart/count` 必然 401，页面各自兜底；
  「只带 dd_rt」场景更是靠一次 401 触发单飞刷新再重放），
  断言口径已对齐 `seckill-ui-check.mjs` 的 `!/favicon|status of 401/i` 过滤。
  另把「刷新后仍在」一类的断言**与上一步的点击结果解耦**（先显式 `goto('/admin')` 再 reload），
  否则「下拉入口坏了」会连带打红它、看起来像两个缺陷。

### 2026-10-05（第六十二轮 · 用户报障两处的最小修复：下单「应付 ¥0」+ 领券中心不刷新 / 无定时上架）

用户报障：① 下单时「应付金额」显示为 0；② 领券中心长期只有固定的两张券、不刷新、活动券不会自动上架。
定位为**三个独立缺陷**，均按「最小修改、不动无关逻辑」处理：

- **① 应付 ¥0 —— 是展示缺陷，不是算钱缺陷**（`miniprogram/pages/checkout/checkout.js`）。
  金额一律由服务端算（`api.order.place()` 只发 `couponCode`，不发金额），所以不可能真的扣 0 元。
  真因是 `payText` 只有一个写入口 `applyCoupon()`，而 `refreshCoupons()` 的三个分支
  （`_couponDeclined` / `bestCouponCode` / `chosenCoupon`）**漏掉了最常走的「无券可推」**——
  新账号 `GET /api/coupons/mine/usable?amount=18900` 实返 `{"coupons":[],"bestCouponCode":null}`，
  于是 `applyCoupon` 一次都不被调用，`payText` 永远停在 `data` 初值 `'0'`。
  修法：把三个金额行收成**唯一写入口** `syncAmounts()`；`recalc()` 先本地刷一遍再等服务端；
  补 `else` 分支；`.catch(() => {})` 改为 `.catch(() => this.applyCoupon(null))`（401/超时同样要刷新）。
- **② 活动券「到点自动上架」失效在读取那一半**（`duoduo-marketing/.../CouponTemplateMapper.java`）。
  运营侧 `POST /api/coupons/admin/templates` 一直支持传 `startAt`/`endAt`，
  但 `selectClaimable` 只判 `end_at >= now`，**漏了 `start_at <= now`** ——
  未到开抢时间的券提前出现在领券中心，而点领取被 `claimOne` 的窗口条件 422「尚未开始领取」。
  补上后列表与领取是同一套窗口条件，「到点自动上架」就是这条 SQL 的自然结果，**不需要定时任务**。
- **③ 领券中心「不刷新」是只有 `onLoad` 取数**（`miniprogram/pages/coupons/coupons.js|json`）。
  补 `onShow`（30 秒节流，时间戳写在**发起请求时**，否则 `onLoad`+`onShow` 会并发打两次）+ 下拉刷新。
- **测试**：`behavior.test.js` 行为断言 21 → **31 项**（新增「确认订单金额三行自洽」6 项、
  「领券中心取数时机」4 项）；新增 `scripts/coupon-window-smoke.py`（6 项，
  钉**不变式**：「列表里出现的每一条券当下都必须能领成功」——比钉条数值钱）；
  `coupon-smoke.ps1` 消除 60 秒补偿调度与 TTL 夹具的竞态（~0.5%/次的假阳性）。
  金额断言一律按**数值**比较，不比对 `'189'`/`'189.00'` 字符串形态。
- **反例优先**：把 `checkout.js` 暂存回旧版后新断言**精确变红**（`payText="0"`、`0 !== 59`）
  且「有推荐券」边界仍 PASS；把节流时间戳改回「响应后写」后首次进入**实测发 2 次**请求。
- **验证**：营销模块单测 17/0 且容器内 jar md5 一致 ｜ `coupon-smoke` **145/0** ｜
  小程序行为 **31/0** ｜ 静态自检 0 错 0 警 ｜ 券窗口探针 6/0 ｜
  券窗口真值表（未来/窗口内/已结束/停用）四场景行为全部正确。

### 2026-10-01（第六十轮 · 券放宽 + 性能压测 + 旧接口下线 + 两套测试环境 + 脚本迁移）

- **① 优惠券放宽（用户拍板「放宽」）** —— 原 v1 口径偏严，本轮放宽三条，并用单测固定住：
  - **门槛**：由「只看拆单后金额最大的那一子单」改为**按本次下单的商品总额判定**。
    此前买家把同一笔订单拆给两个卖家时，明明总额够，却因为单看某一单不够门槛而用不了券。
  - **抵扣**：由「只抵在最大的那一单」改为**按各子单金额比例分摊到每一单**。
    分摊取整丢掉的**余数一律补给金额最大的那一单**，因此各单抵扣之和**严格等于券面额**
    （不补余数会让用户少减几毛钱且查不出原因）。
  - **有效期**：由「秒级比较」改为**按自然日判定** —— 到期日当天 23:59:59 前仍可用。
    「券上写 10 月 1 日到期，1 号上午就不能用」是最容易被当成 bug 的一处。
  - **券归属不变**（只锁在归属单的订单号上，券码也只写在归属单）：分摊改的是「钱怎么算」，
    不是「券归谁」。新增 `CouponAllocationTest`（8 项，含「丢余数会导致总和小於面额」的反向验证）
    与 `CouponValidityTest`（4 项）。
- **② 性能压测（`scripts/perf/bench.py`，纯标准库）** —— 并发 10/25/50/100 × 10 秒，
  读/写两套基线（读 avg≤300 / P95≤600 / P99≤1000 ms；写 avg≤800 / P95≤1500 / P99≤3000 ms；错误率<0.1%）。
  峰值 100 并发下 6 个核心接口全部达标、错误率全 0：
  商品列表 1659 QPS / P99 175ms，商品搜索 1477 / 131ms，商品详情 1559 / 198ms，
  类目树 3782 / 69ms，闲置列表 1966 / 151ms，结算预览 2278 / 107ms。结果落 `logs/perf/r60-bench.json`。
  - **压测暴露并修掉一个真实缺陷**：`@GetMapping("/{spuId}")` 会把 `/api/products/home`
    这类非数字段也捕获进来，转 `Long` 失败 → 兜底成 **500**（应为 404）。已给 6 处
    同类路径加 `\d+` 形状约束（`AfterSaleController` / `ShareController` / `CategoryController` /
    `ListingController` / `AdminOrderController` / `ProductController`）。
  - 压测器自身两处「假绿」也一并修掉：每请求新建 TCP 连接会耗尽本机临时端口（改为每线程长连接）；
    只把 5xx 计为错误会让 401/422 被统计成成功（改为非 2xx 一律计失败）。
- **③ 旧接口下线** —— `POST /api/orders/{orderNo}/ship` **只验内部凭据、不验归属**
  （有内部凭据就能发任何人的货），已删除，收敛为卖家端单一入口
  `/api/seller/orders/{orderNo}/ship`（`requireSellerScope` + `requireOwnOrder`）。
  同步清理：网关内部凭据过滤器的注释、三处接口文档（`docs/handover/05` / `docs/openapi/endpoints.md` /
  `docs/agent/04`），`extract-api-contract.py --check` 转 PASS。
- **④ 两套测试环境都可实际运行**：
  - **小程序「无浏览器」运行时**（`scripts/mp-harness/`）：小程序逻辑层没有 DOM，真正要驱动的是
    Page 实例的生命周期与 setData。用 `node:vm` 沙箱 + `wx.*` 桩 + 自实现 require，**直接加载仓库里
    真实的页面 js**（不是复制一份），因此页面改了而测试没改就会红。
    `pages-smoke.test.js` 逐个挂载 30 个页面（33 项），`ai-page.test.js` 断言 AI 页的真实行为（4 项）。
  - **前端真实浏览器**：Playwright + Chromium 装进 `scripts/e2e/node_modules`（仓库自包含，
    不再依赖用户级 workspace），新增一键运行器 `scripts/e2e/run-ui-checks.ps1`
    （起 dev server → 跑 8 套 → 建/拆券夹具 → 收尾），**8 套全 PASS**。
- **⑤ 六套脚本迁移**（aftersale / backfill / credit / logistics / payment / review / settlement）：
  旧发货端点全部切到卖家正规入口，并补了 `scripts/lib/platform-operator.ps1`
  （自营订单 `seller_id=0` 归平台，**只有 OPERATOR 可见**，普通卖家是 403）。
  结果：aftersale 101 / backfill 88 / credit 82 / logistics 79 / review 59 / settlement 125 / payment 72，全 0 失败。

  **迁移中修掉的三个共性问题**（都是「看起来像环境问题，其实是脚本问题」）：
  - **PowerShell 5.1 的 BOM**：写回 .ps1 丢了 UTF-8 BOM，中文注释被解析成乱码 →
    `ParserError 字符串缺少终止符`。已批量补回。
  - **Node 同步派生态被禁**：本机 `spawnSync` / `execFileSync` 一律 `EBUSY errno -4082`（连 `cmd`/`where`
    都起不来），而**异步 `spawn` 正常**。6 套 e2e 卡在 `execFileSync('docker', ...)` 上，
    表象是「docker 不可用」。新增 `scripts/lib/docker-exec.mjs`（异步），全部改为 `await sql(...)`。
  - **雪花/长 id 已按字符串下发**：`"id":"5003"` 拿数字 `5003` 去 `===` 恒为 false，
    表现为「模板明明存在却说不可见」「取不到卖家 ID」。已统一为字符串比对
    （含 `bigId` 正则放宽为 `"?(\d{15,})"?`）。
- **新增对拍守卫**：`scripts/lib/internal-token.mjs` 是内部凭据 HMAC 的 JS 侧实现，
  与 PowerShell 侧 `scripts/lib/internal-token.ps1` 同一算法两份实现 —— 新增
  `internal-token-parity.test.js` 用固定输入比对两侧输出（4 项），算法漂移即红。
- **本轮质量口径**：后端单测 **222/0**（新增 12 项：券分摊 8 + 券有效期 4）；
  小程序无浏览器测试 **37/0** + 内部凭据对拍 **4/0**；前端真实浏览器 **8 套全 PASS**；
  主冒烟 **55/0**；七套业务冒烟全 0 失败；Docker 8/8 镜像与容器内 jar md5 **逐模块一致**。

### 2026-09-28（第五十二轮 · 跳蚤市场发布链路 + 结算预览 + 405 修复）

- **跳蚤市场补全发布链路**（源自第五十一轮验收建议「补全链路」）：后端从「仅 2 个只读端点」
  扩为完整 C2C 闭环 —— `POST /api/listings`（发布，落 `PENDING`）、`GET /api/listings/mine/list`
  （我的发布）、`POST /api/listings/{id}/status`（上/下架，幂等开关）。
  新增 record：`ListingPublishRequest` / `ListingStatusRequest` / `ListingMineView`。
- **★ 路径即安全设计**：闲置列表由裸 `/api/listings` 迁到 `/api/listings/search`，
  与 `/api/listings/*`（两段）一起做免登只读；三条写路径走单段 `POST /api/listings` 或三段
  `/api/listings/mine/list`、`/api/listings/{id}/status`。**网关白名单按路径匹配、不分方法**，
  若照直觉写 `/api/listings/**` 会把三条写端点一并放行成「匿名 UGC 直写入库」——
  这是 `duoduo-gateway/GatewaySecurityProperties` 里必须写清的一条纪律（与本轮 `SharesService`
  的两段式隔离同款）。`sellerId`/`status`/`auditStatus` 一律不接受客户端传值。
- **结算预览 `POST /api/orders/preview`**（源自第五十一轮问题 1）：返回按卖家拆单的
  items / 商品总额 / 运费 / 券后应付；**只读** —— 不建单、不占券。新增
  `PreviewOrderRequest` / `PreviewOrderPreview` / `PreviewOrderResult`、`OrderService.previewOrder`。
- **★ 券试算新增只读 `trial`**（`POST /api/coupons/internal/trial` + `marketingGateway.trial`）：
  `lock` 是跨服务**写**（占券），预览调它会导致用户每改一次数量就被占一次券、
  没提交还得等 TTL 解锁；`lock` 与 `trial` 共用 `precheck()` 保证口径一致。
  探针实测「预览金额 == 真实下单金额」，且连调 3 次后订单数与券状态均不变。
- **方法错误回 500 而非 405 修复**（源自第五十一轮问题 2）：`ErrorCode` 新增
  `METHOD_NOT_ALLOWED`(405)，`GlobalExceptionHandler` 新增 `HttpRequestMethodNotSupportedException`
  处理器（与既有 `UNSUPPORTED_MEDIA_TYPE`(415) 对称）。
- **★ 踩坑（重要）**：`duoduo-common-web` 是**多服务共用模块**，首轮只重建了 4 个服务，
  **user / fulfilment / ai / social 内嵌的副本仍是旧版**（`PUT /api/users/me` 仍回 500）；
  且 `mvn -pl ... -am install` 因增量检测**跳过打包**（jar 时间戳没变、9 秒结束）。
  改用 `mvn -q -o -DskipTests clean package -pl <四模块>` 强制重打，逐层解包复检
  「含 405 异常引用 = True」才通过。**改共用模块必须重建全部依赖方，并验证构建产物内容**。
- **小程序**：新增 4 页（跳蚤市场 / 发布 / 我的闲置 / 闲置详情，16 文件）+ 首页入口
  + 我的页「我的闲置」菜单 + `api.js` 的 `listing` 命名空间 + `images.js` 的 `listingImage()`
  + 手绘 `flea.png` 图标；发布页用显式 `yuanToFen()`（正则校验，不用 `parseFloat`）；
  我的闲置页显示三色审核徽章与驳回原因；跳蚤市场走**原生导航栏**（tabBar 已满 5 项）。
- **验证**：`scripts/r52-listing-preview-smoke.py` **57/57**；`scripts/r51-full-acceptance.py`
  **155/155 · 0 FAIL**；14 容器全部 healthy。

### 2026-09-25（第十九轮 · A12 压测：sync/queued 双通道 + 瓶颈定位）

- **压测落地（A12）**：JMeter 5.6.3 无头运行（`java -jar ApacheJMeter.jar` 直启，绕开 `jmeter.bat` 的
  Java 检测挂死），三份脚本入库 `scripts/perf/`：`order-pay-confirm.jmx`（注册→登录→加购→下单→
  支付→发货→确认全链路，每线程独立用户）、`order-queued-enqueue.jmx`（入队）、
  `order-pay-confirm-multisku.jmx`（热点行验证）+ `summarize_jtl.py`（分位数汇总）。
- **核心结论**：同步下单 **~100 单/s**（P99 1156ms @100 线程，瓶颈=订单落库链路）；
  queued 入队 **~1000 单/s** 全程 0 错误（P99 <130ms），消费排空 ≈112 单/s；
  **热点行实验**：库存扣减按 SKU 分散 3 行后吞吐 +40%、延迟减半（`t_sku` 热点行锁是主要串行点）。
  详见 `docs/perf/A12-load-test-report.md`；HTML 报告在 `logs/perf/*-report/`（gitignore 不入库）。
- **测试伪影（如实记录，非缺陷）**：队列档加购 422 系购物车同 SKU 累加（上限 200）在积压场景撞限；
  任务 FAILED 全部为「购物车中没有已勾选的商品」（消费耗时读购物车，高频连续入队所致）。
- **夹具已复原**：SKU 2001/2002/2003 库存写回 272/96/210、冻结归零；trade 限流与 order-mode 恢复
  compose 默认（100 QPS / sync）。

### 2026-09-23（第十四轮 · A8 收货地址簿 + T-39 雪花 ID 精度根治）

- **用户域新增收货地址簿（A8）**：`t_user_address`（`docs/sql/incremental/07-a8-user-address.sql`），
  6 个用户端点 —— `GET /api/addresses`（**默认地址排最前**）、`POST /api/addresses`、
  `PUT /api/addresses/{id}`、`DELETE /api/addresses/{id}`、`POST /api/addresses/{id}/default`、
  `GET /api/addresses/default`（**无默认时返回 200 + `data=null`**，不报错，前端据此渲染「去添加」）；
  另加内部快照 `GET /api/addresses/internal/snapshot`（`X-Internal-Token` + `InternalTokenFilter` 失败关闭，
  **不进网关白名单**，交易域直连 8081）。
- **业务规则**：① **首个地址自动成为默认**（未显式指定也是）；② 设默认时**在事务内先清旧默认再置新默认**，
  库内恒有且仅有 1 条默认；③ **删除默认地址时自动顺延**到剩下的一条，前端 toast 明确告知；
  ④ `fullAddress` 拼接**只有一处定义**（`AddressService.fullAddress()`）：省+市+区+明细直接相连，
  市名等于省名（直辖市）时只保留一次 —— 内部快照与地址簿接口走同一个方法，用冒烟断言「逐字相同」锁住。
- **归属校验统一 404**：改 / 删 / 设默认他人地址一律返回 **404**（与「id 不存在」同码同文案），
  **刻意不区分「不存在」与「无权限」**，避免用状态码差异枚举他人地址 ID。这是对 `docs/agent/07`
  原文「他人地址 → 403」的一处**有意偏离**，实现时统一为 404 并在此说明。
- **交易域接入**：下单请求新增可选 `addressId`；传了就**只认 `addressId`**（直填三字段被忽略，
  避免两套来源冲突），为空才回落旧逻辑。收货信息在**下单瞬间写入快照**（`t_order.receiver_*`），
  此后改 / 删地址簿**都不影响历史订单**（冒烟按字节比对锁住）。用不存在的 / 他人的 `addressId` 下单 →
  被拒且文案指向「地址不可用」。
- **★ T-39 根治（全站性 ID 精度缺陷）**：雪花 ID 是 18~19 位整数，超出 JS
  `Number.MAX_SAFE_INTEGER = 9007199254740991`（16 位）。以 JSON **number** 下发时，浏览器
  `JSON.parse` 会**静默四舍五入**——`Number('361084524781768704') === 361084524781768700`（末位 704→700，**不报错**）。
  前端拿这个已损坏的值拼 URL → 服务端按 id 查不到 → **404**，页面表现为「点按钮 → XX 不存在」。
  **接口冒烟照不出来**（PowerShell/.NET 用 `Int64`/`Decimal` 解 JSON 数字不丢精度），只有真实浏览器会踩；
  **A3 售后单曾局部踩过一次**（当时用 `String.valueOf` 单独修了 `AfterSaleView.id`，未上升到通用规则），**A8 又踩了一次**。
  根治：`duoduo-common-web` 新增 **`LongToStringJacksonModule`**，随 `WebAutoConfiguration`
  自动装配到全部业务服务的 Jackson 转换器（MVC 转换器 + 作为 Bean 供 Feign/RestTemplate 使用）。
- **为什么不是「见到 `Long` 就转字符串」**：全站金额字段（`price` / `payAmount` / `discountAmount` /
  `thresholdAmount` / `freight` / `balance` …）**共 63 处也是 `Long`**（单位：分），
  计数与 epoch 毫秒时间戳同样是 `Long`。一刀切会让金额变成 `"12900"`，
  前端 `formatPrice` 里的 `price / 100` **静默算出 `NaN`**，把页面金额全打成「¥NaN」——用更大的 bug 换掉小 bug。
  **判据是字段语义**（这个值前端会不会拿去做算术），不是 Java 类型。
  因此模块规则 = **「`Long`/`long`/`BigInteger` 默认转字符串」+「字段名命中 `NUMERIC_LONG_FIELDS`
  （金额 / 计数 / 时间戳三类）白名单的保持数字」**，用 `BeanSerializerModifier.changeProperties()`
  把对应字段的 writer 换回数值输出。**漏标方向是安全的**：新加 id 字段**不需要做任何事**就受保护；
  新加金额类字段才需要记得进白名单（漏了会 `NaN`，比 404 更容易被发现）。
  **反序列化与 `@PathVariable Long` 不受影响**（后者由 Spring 转换器处理，与 Jackson 无关），入参无需改动。
- **前端同步**：`types.ts` 的 **38 个 id 类字段**（`id`/`parentId`/`categoryId`/`brandId`/`sellerId`/`skuId`/
  `spuId`/`addressId`/`templateId`/`buyerId`/`userId`/`petId`）由 `number` 改 `string`；
  `endpoints.ts` **25 处**形参类型同步；受影响页面 13 个（`Cart`/`Checkout`/`ProductDetail`/`ListingDetail`/
  `Search`/`Categories`/`ReviewNew`/`Audits`/`Coupons`/`seller/Products`/`CreditBadge`/`ReviewSection`/`Me`）
  逐个修到 `tsc --noEmit` **零错误**。纪律写进 `types.ts` 顶部：id 只能**拼 URL 与相等比较**，
  **绝不做数值运算**（`Number(id)` / `parseInt` / `id + 1` / `id > 0` 全禁）。
  `ProductDetail` 的 `Number(id)` 改为直接用字符串并新增 `hasSpuId` 布尔哨兵；
  `Categories` 新增 `TOP_PARENT = '0'` 常量（这是**真正的数值语义**哨兵，与雪花 id 可区分）。
- **前端顺带修掉一个真缺陷（地址加载竞态）**：`Checkout.tsx` 的初始化守卫写成
  `addressList === null`，而本仓 `useAsync` 的初始 `data` 是 **`undefined`**，
  导致首次渲染就锁定「空地址簿」分支、把用户推进「手动填写」模式 —— 地址簿里的地址渲染出来了却一个都选不上，
  提交报「请填写收货人姓名」。改为 **`addrLoading` 门控 + 判 `undefined`**。
  UI e2e 抓到：修复前 5 条断言红，修复后全绿。
- **自测**：
  - `scripts/address-smoke.ps1` —— **82 项 / 0 失败**（含★断言总数守卫），覆盖四条验收 + 归属校验 /
    内部凭据边界（无 token 403、错误 token 403、经网关无登录态 401）/ 直辖市拼接去重 / 快照不回写 /
    顺延不留空默认 / 参数边界 / 清理复原。
  - `scripts/e2e/address-ui-check.mjs` —— **64 项 / 0 失败**（真实 Chromium），16 段覆盖未登录拦截、
    首个自动默认、设默认唯一性、编辑时复选框禁用、删除顺延 toast、结算页自动预选 + 下单、
    订单详情 == 地址簿、改 / 删地址簿后快照不变、切手动填写按钮不变死按钮。
  - **`scripts/id-precision-smoke.ps1`（新增）** —— **56 项 / 0 失败**，跨商品 / 用户 / 交易 / 营销四域，
    **直接断言 JSON 原始文本**（绕开 `ConvertFrom-Json` 对精度的掩盖）：id 类字段必须是**带引号的字符串**
    且与库内**逐字符相同**（末位不许被舍入），金额 / 计数 / 时间戳必须**仍是不带引号的数字**；
    并做端到端闭环：用响应里抠出的 id 反查，证明它"可用"而不只是"长得像字符串"。
  - **`scripts/run-all-smoke.ps1`（新增）** —— 一键跑全套并输出汇总表 + 失败清单。
  - **全套回归**：15 套接口冒烟 **1286 项全绿**。连带修了 2 处**因 T-39 修复而失效的旧断言**：
    `review-smoke.ps1` 从原始文本抠主键的正则 `"id":(\d+)` 匹配不到新形态 `"id":"361…"`
    （改为 `"?(\d+)"?` 兼容两种形态），`order-smoke.ps1` 的「缺收货人」用例补上"先加购"前置
    （否则会先撞上「购物车为空」的 422，是**同码不同因**的假绿灯）。
  - 前端 `tsc --noEmit` 零错误。
- **踩坑登记**：`docs/agent/08` 新增 **T-39**（雪花 ID 精度，判据「接口冒烟过、浏览器 4xx」）、
  **T-40**（一刀切字符串化会让金额变 `NaN`；白名单必须用 `Set.copyOf(Arrays.asList(...))`，
  `Set.of()` 遇重复元素在**运行期**抛异常）、**T-41**（改公共模块后业务 fat jar 未重打：
  `mvn package` 增量判定业务模块无变化 → 不重打 jar，必须 `mvn clean install` + `build --no-cache`
  + `up -d --force-recreate`；判据「改动没生效 + 构建很快」→ 查 jar 时间戳）、
  **T-42**（`useAsync` 的 `undefined` 被当成空列表：守卫判 `=== null` 是死代码，要用 `loading` 门控）。

### 2026-09-23（第十三轮 · A5 优惠券营销）

- **新增第 6 个业务服务 `duoduo-marketing`（营销域 / 优惠券，:8085）**：compose 第 9 个容器
  （服务名 `duoduo-marketing-app`，容器 `duoduo-app-marketing`，镜像 `duoduo/duoduo-marketing:1.0.0`），
  雪花 **worker-id=5**，网关路由 `/api/promotions/**` + `/api/coupons/**`。新库 `duoduo_marketing`。
- **两张表**：`t_coupon_template`（运营侧券模板主数据：名称 / 类型 / 门槛 / 抵扣 / 总量 / 已领量 / 起止时间 / 状态）+
  `t_coupon`（用户手上一张券：券码 / 用户 / 模板 / 状态 / 锁定订单号 / 核销订单号 / 领取时间 / 过期时间）。
  领券幂等靠唯一索引 `uk_coupon_user_tpl(user_id, template_id)`（一人一模板一张）；**总量防超发靠 CAS**
  （`claimed_count < total_count`）。种子模板 **5001**（满 50 减 5）/ **5002**（满 200 减 30）。
  建库脚本 `duoduo-marketing/src/main/resources/db/schema.sql` + `docs/sql/05-duoduo-marketing.sql`。
- **接口**：公开只读领券中心 `GET /api/coupons/templates`（每张券带 `claimable` / `claimableReason`，
  **能否领由服务端判定**）；需登录 `POST /api/coupons/templates/{id}/claim`（**重复领取不报错**，
  返回 `replay=true` 与已有券码）、`GET /api/coupons/mine?status=`、`GET /api/coupons/mine/usable?amount=`
  （服务端给 `bestCouponCode` 最优券）；运营 `POST /api/coupons/admin/templates`。
  **内部接口**（`X-Internal-Token`，**不进网关**，交易域直连 8085）`/api/coupons/internal/{lock,release,consume,sweep}`。
- **交易域接入**：`placeOrder` 先锁券（失败抛 422 带人话原因）→ `paySuccess` 核销（核销失败则整笔支付不成立）→
  `cancel` / `forceClose` / 超时关单**三处**解锁；`t_order` 增列 `coupon_code VARCHAR(32)`
  （增量脚本 `docs/sql/incremental/06-a5-coupon-columns.sql`；MySQL 不支持 `ADD COLUMN IF NOT EXISTS`，**重复执行报 1060 属预期**）。
- **三条纪律**：① **券码用随机串**（`CP` + 14 位去混淆字符，字符表去掉 `0/O/1/I`）而**不用雪花主键**（INV-16）；
  ② 锁 / 解 / 核销**全部 CAS 条件更新、不用分布式锁**（INV-1）；③ **营销域只算「能抵多少」，折扣金额由交易域写入
  `t_order.discount_amount`**，营销域是被调用方、**不依赖 openfeign**。
- **网关白名单收紧为精确路径 `/api/coupons/templates`**：白名单按**路径**而非方法匹配，
  放行 `/api/coupons/**` 会把 `POST .../claim` 也放成匿名可调（与既有 `/api/reviews/product/**` 同一纪律）。
- **前端**：新增 `frontend/src/pages/Coupons.tsx`（领券中心 + 我的券包双页签）、路由 `/coupons`（**刻意不套 `RequireAuth`**）；
  一级导航加「优惠券」、用户下拉与「我的」页加「我的券包」；结算页 `Checkout.tsx` 接入可用券查询与选择
  （用 `undefined`（未选择 → 采用服务端推荐）与 `null`（明确不用）区分，提交只传 `couponCode`，费用明细显示优惠与实付）；
  订单详情 `OrderDetail.tsx` 金额区展示优惠金额与券码（有券时才显示券码行）。新增 `CouponStatus` / `CouponType` /
  `CouponTemplateView` / `CouponView` / `CouponClaimResult` / `UsableCoupon` / `UsableCouponResult`，`OrderView` 加 `couponCode`。
- **自测**：新增 `scripts/coupon-smoke.ps1`（**145 项断言**，含★断言总数守卫）——① 领券幂等 ② 并发防超发
  ③ 锁·解·核销 CAS ④ 折扣落交易域 ⑤ 生命周期闭环，另加公开只读边界 / 内部凭据 403 / 并发锁券 / 归属校验 /
  门槛文案 / 券码非雪花 / sweep 幂等 / TTL 环境变量校验 / 运营建券准入 / 清理复原。
  前端 `tsc --noEmit` 零错误、`vite build` 通过（**79 模块**）。
  （该轮口径为 14 套 1204 项 + 5 套 180 项；**第十四轮 A8 已把累计推进到 15 套 1286 项 + 6 套 244 项**。）
- **本轮自测暴露并修复的 3 个缺陷**：
  1. **未登录访客被标记成「可领取」**：`toTemplateView` 没把登录态传进去，未登录时前端会渲染一个注定 401 的
     「立即领取」按钮 → 加 `loggedIn` 分支，未登录时 `claimable=false` + 原因文案「登录后可领取」。
  2. **`cancel` 路径漏了解锁券**：原先只有运营强制关单与超时关单会解锁，用户主动取消订单时券会一直卡在 LOCKED
     直到 TTL 兜底 → `cancel` 补 `releaseCouponQuietly(orderNo, "用户取消")`，三处调用统一带 `scene` 便于排障。
  3. **`OrderService` 构造器漏赋值** `marketingGateway`（编译报「可能尚未初始化变量」）；以及 `MarketingGateway.release`
     加 `scene` 参数用于日志与调用描述。
- **首跑与复跑**：`coupon-smoke.ps1` 首跑 **143 通过 / 2 失败**（脚本声明 145 项，断言总数守卫自证 145 项全部执行、
  无静默跳过）。失败的两项恰好是「未登录访客也标 `claimable=false`」与「原因文案为『登录后可领取』」——
  根因是 `loggedIn` 分支的修复**写进了源码、但 `duoduo-app-marketing` 容器没重建**（源码里该分支明明存在，
  跑的却是旧镜像，curl 与日志都看不到新分支）。执行
  `docker compose build duoduo-marketing-app && docker compose up -d duoduo-marketing-app` 重建后再复跑，
  **145/145、失败 0**。（**落盘脚本声明 145 项**，一切以断言总数守卫的自证数字为准。）
- ⚠️ **新踩的坑（已入 `08` 篇 T-37）**：**`docker compose build` 之后才补的代码不会进运行中的容器** ——
  补丁写完必须重新 `mvn package` + `docker compose build <svc> && docker compose up -d <svc>`，否则冒烟跑的还是旧镜像
  （表现为「代码明明改了、断言还是失败」，且 curl / 日志都看不到新分支）。
- **浏览器自测（新增）**：`scripts/e2e/coupon-ui-check.mjs` **43 项真实 Chromium 断言**（未登录态 / 领券 / 券包 /
  结算选券与费用口径 / 订单详情券码 / 取消退回 / 噪声）+ 配套夹具脚本 `scripts/e2e/coupon-ui-fixture.ps1`
  （`-Action create|drop`）。浏览器 UI 断言累计 **5 套 180 项**（原 4 套 137 项 + 新增 43 项）。
- ⚠️ **另两条新踩的坑（已入 `08` 篇 T-38）**：
  ① **本机 Node 无法创建子进程**：`spawnSync` / `execFileSync` 一律 `EBUSY errno -4082`（`cmd.exe` / `powershell.exe` /
  `docker.exe` 都试过，两版 Node、沙箱内外都一样）→ 那种「在 Node 里 `execFileSync('docker', ['exec','duoduo-mysql','mysql',…])`
  跑 SQL」的 e2e 夹具写法在本机跑不通。修法：把 SQL 从 Node 里彻底移出，改由 PowerShell `& docker exec` 完成
  （与 `coupon-smoke.ps1` 同一姿势），Node 脚本只做 HTTP + 浏览器。
  ② **未支付订单详情页的 `/settlement` 404 是已知良性行为**：`OrderService.settlementOf()` 对「没有托管记录」的订单
  按设计返回 404（`OrderView.settlement` 注释即写明「未支付时为 null」），页面自行忽略；记为 **P3 前端优化点**
  （未支付时可跳过这次请求），e2e 里已显式排除该 404 并注明理由。

### 2026-09-20（第十二轮 · A4 信用分）

- **履约域新增信用分模型 `support/CreditModel`**（单一事实来源）：纯函数，输入事实 + 权重 → 分数与因子明细，
  不碰 DB / HTTP。权重表 **基础 60 + 履约完成率 18 + 评价质量 16 + 售后诚信 6 = 100**，全部可环境变量覆盖，
  **启动即校验和为 100**（配错直接 fail-fast）。三条硬约定：**因子得分之和 == 总分**（结构上不可能对不上）、
  **无数据 = 基础分**（分母为 0 记 0 分，故无交易用户得 60，不返回 null）、**单调可解释**（不设负分项）。
- **三个端点**：`GET /api/reviews/seller/{sellerId}/credit`（**公开只读**：分数 + 分级文案 + 因子明细 + 原始输入，
  网关白名单精确放行 `/api/reviews/seller/*/credit`）+ `POST /api/reviews/internal/credits/refresh`
  （内部凭据，批量重算回写）+ 用户域 `POST /api/users/internal/{userId}/credit`（内部凭据回写，**同值不写库**）。
- **交易域补一个只回计数的内部接口** `/api/orders/internal/seller/{id}/stats`（完成 / 关闭 / 退款 / 总数，
  **不含收货人信息**）；**读接口实时计算**，不读 `t_user` 的副本，杜绝「库里 100、页面 82」的分裂。
- **两条刻意的取舍**：**交易域不可用一律 `DEGRADED` 503 而非算成 0 单**（「拿不到」≠「真的 0 单」）；
  **售后诚信只计 `arbitrate_result='APPROVE_REFUND'`**（卖家主动同意退款不算污点）。
- **前端两处展示**：`components/CreditBadge.tsx`（徽标 + `CreditPanel` 完整面板）；商品详情价格区、闲置详情规格表、
  「我的」页（移除静态 `user.creditScore`，改用实时面板）。
- **顺带收口**：用户域注册默认分由硬编码 `100` 改为可配置 `duoduo.user.credit-default`（默认 60），
  DDL `credit_score` 默认值同步为 60。
- **自测**：新增 `scripts/credit-smoke.ps1` **82 项**（基础分 / 权重表 / 因子和=总分 / 单调性 / 售后因子 /
  回写幂等 / 凭据边界 / 批量刷新，含断言总数守卫）；前端 `tsc --noEmit` 零错误、`vite build` 通过；
  全量编译 8 模块 BUILD SUCCESS，8 容器 healthy；**回归 13 套全绿**。
  接口冒烟累计 **13 套 1034 项**，UI 断言累计 **4 套 137 项**。
  （本轮回归顺带**修正一处旧口径**：`seller-smoke.ps1` 实为 **101 项**，此前文档误记为 84 项，
  累计总量随之由 1017 修正为 **1034**。）
- **四条新踩的坑**（详见 `08` 篇 T-33~T-36）：冒烟辅助函数少取一层 `.body`（数据在 `body.data` 下）；
  跨库 `INSERT…SELECT` 未写库限定名 → 插 0 行且不报错；TS `noUncheckedIndexedAccess` 下 `Record` 取值可空，
  `?? FALLBACK` 压不住，需用独立非空常量兜底；**函数签名与调用处参数个数不符时，PowerShell 会静默丢弃多余的
  实参**（`trade-smoke.ps1` 的 `Call` 少声明了 `$headers`，导致内部凭据头从未发出 → 内部批量 SKU 接口恒 403）。
- **顺带修复一个既有脚本缺陷**：`scripts/trade-smoke.ps1` 的 `Call` 函数补上 `[hashtable]$headers` 参数
  （其余 12 套脚本都已有），内部凭据头得以正确发送 —— 修复后 **87 通过 / 0 失败**（修复前 1/7）。

### 2026-09-20（第十一轮 · A2 物流轨迹）

- **履约域新表 `t_logistics_track`**：节点流水（订单号 + 节点码 + 发生时间 + 描述 + 留档报文），
  唯一键 `uk_track_order_node_time` 兜幂等，覆盖索引 `(order_no, occurred_at)` 直接服务「按单取全部节点正序」。
  节点码白名单见 `LogisticsNode`（7 种），推送方只能选用不能自创。
- **两个端点**：`POST /api/logistics/internal/{orderNo}/tracks`（批量摄入，`X-Internal-Token`，
  履约域**第一个**内部接口，新增本域自己的 `InternalTokenFilter`）+
  `GET /api/logistics/orders/{orderNo}/tracks`（买家本人 / 该单卖家 / 运营）。
- **交易域发货后补推「已揽收」**：`OrderShippedEvent` + `@TransactionalEventListener(AFTER_COMMIT)`
  + `LogisticsNotifier`（best-effort，**绝不抛出**）。两条发货路径（卖家中心 / 内部凭据）共用同一段状态机，
  事件在状态机本体发布，两边都不会漏。
- **前端买家 / 卖家订单详情各挂共用时间线组件** `components/LogisticsTimeline.tsx`：
  正序排列 + 头部「当前：××」常驻提示；未发货给说明性空态；已完成订单轨迹仍在；
  非当事人在订单页本就被挡，不泄露任何物流信息。
- **新增履约域 `OrderFacts` 统一取数口**：评价域与售后域各自那份「调交易域取订单事实」已经**悄悄漂移**
  （同一句「交易域不可用」，一处抛 422、一处抛 503）—— 统一收口，两域改用同一实现。
- **自测**：新增 `scripts/logistics-smoke.ps1` **79 项**（乱序正序 / 幂等 / 批量全有或全无 /
  无时区按 UTC / 越权 / 敏感字段 / 库存与归属复原）、`scripts/e2e/logistics-ui-check.mjs` **44 项**；
  前端 `tsc --noEmit` 零错误、`vite build` 通过（主包 171.5KB）。
  接口冒烟累计 **12 套 935 项**，UI 断言累计 **4 套 137 项**。
- **两条新踩的坑**（详见 `08` 篇 T-31 / T-32）：
  Write 工具生成的 `.ps1` 是 UTF-8 **无 BOM**，PowerShell 5.1 按 GBK 读 → 中文乱码且语法被撞坏
  （既有脚本都带 BOM，照做即可）；多行续行的 `Check "…" (…) -eq '…'` 里的 `-eq` 被当成参数名，
  该断言**在参数绑定阶段抛异常被跳过，而结果仍是 0 失败** —— 已加「断言总数守卫」防复发。

### 2026-09-20（第十轮 · A3 售后三端页面）

- **买家端 3 页**：`/after-sales`（我的售后 + 状态页签）/ `/after-sales/new?orderNo=`（申请：类型 + 诉求 + 举证图）
  / `/after-sales/:id`（详情：步骤条、卖家说明、裁定说明、退款金额 + 申请平台仲裁）。
  入口挂在**订单上**（已发货 / 已完成才显示）与「我的」页 —— 售后总是从「我买过的那单」出发，
  未发货时买家本就能自助退款，走售后反而绕远。
- **卖家端 1 页** `/seller/after-sales`：店铺售后列表 + 处理弹窗，`AGREE` 立刻退款 / `REJECT` 必填原因。
- **运营端 1 页** `/admin/after-sales`：全平台列表 + 裁定弹窗，支持退款（真的执行） / 驳回，**说明必填**。
- **契约修正：售后单 `id` 改为字符串传输**。它是 18 位雪花值，超过 `Number.MAX_SAFE_INTEGER`，
  `JSON.parse` 会静默改写；前端从路由参数原样取用、不做数值转换。
  后端 `AfterSaleView.id` 类型 `Long` → `String`，冒烟脚本同步改为正则提取。
- **三端共用件** `pages/afterSaleShared.tsx`：状态页签、类型文案、状态配色、进度步骤条 ——
  避免「同一状态在两个页面显示成两种说法」。
- **自测**：新增 `scripts/e2e/aftersale-ui-check.mjs` **44 项断言全绿**（真实 Chromium，
  买家→卖家→运营整条链，最后回接口层确认订单与托管同步 `REFUNDED`、退款金额等于实付）；
  前端 `tsc --noEmit` 零错误、`vite build` 通过；本轮改到了订单页 / 「我的」页 / 卖家端与后台共用件，
  故**三套 UI 自测全跑**（`review-ui-check` / `seller-ui-check` 均 `exit=0`）；
  回归 `aftersale-smoke 101/0`、`review-smoke 59/0`、`settlement-smoke 125/0`。
- **环境坑（本轮新踩 → 08 篇 T-27）**：dev server 报 `listen EACCES: permission denied 127.0.0.1:5173`，
  **不是端口被占、也不是沙箱拦的** —— 是 Windows **排除端口段**：Docker Desktop 启动后 WinNAT 保留
  `5149-5248`，5173 正在其中。查 `netsh int ipv4 show excludedportrange protocol=tcp`；
  改 `--port 3000` + `UI_BASE=http://localhost:3000` 即可（3000 本就在网关 `CORS_ORIGINS` 白名单里）。
- **本轮暴露并修复的 4 个问题**（全部是 `tsc` 与 `vite build` 发现不了的）：
  1. 前端路由是 `after-sales` 而入口链接写成 `/seller/aftersales` → 落到「页面不存在」。
     接口路径 `/api/aftersales` 与前端路由命名**刻意不同**，链接必须照路由写。
  2. `getByRole('button', { name: '处理' })` 是**子串匹配**，先命中页签「待卖家处理」，
     弹窗根本没打开 → 改成行内 `exact: true` 定位。
  3. 用「页面里还有没有『申请平台仲裁』几个字」判断按钮消失，被**成功 toast 的文案**误命中 → 改判按钮计数。
  4. 裁定弹窗默认结论是「驳回」（保守默认值），按钮名是「确认驳回」而不是「支持退款并执行」。
- **数据清理**：清掉前几轮崩溃遗留的 2 条 `PENDING` 售后挂单、4 个未降权的运营账号，
  并把 34 笔被测试改过归属的订单 `seller_id` 归还平台自营。

### 2026-09-18（第九轮 · A3 售后仲裁工单）

- **新增表** `t_after_sale`（履约库）：售后单含诉求、举证图、卖家处理说明、仲裁结论与说明、退款金额；
  唯一键 `(order_id, type)`，所有状态迁移走 CAS 条件更新。
- **新增 8 个端点**：`/api/aftersales`（立案）、`/mine`、`/seller`、`/{id}`、`/{id}/reply`、`/{id}/escalate`、
  `/api/admin/aftersales`、`/api/admin/aftersales/{id}/arbitrate`。两段前缀都**不进网关白名单**。
- **网关**：`/api/admin/aftersales/**` 加入履约域路由 —— 这是**必须显式列举**的一段
  （product / trade 的路由是按具体子路径列的，不是 `/api/admin/**` 兜底），漏了它不会报错，只会 404。
- **跨域**：售后裁定通过时调交易域**既有的**运营侧退款入口，不新开资金通路；
  `03` 篇服务间调用矩阵已登记。**先让钱动、再改售后单状态**。
- **资金边界（B-01）**：已完成订单的钱已放款给卖家，裁定退款会被交易域拒 **422** 并提示走追偿 / 垫付；
  售后单**保持原状态**，不落半个状态。立案仍然放行（买家诉求该被受理）。
- **买家求助路径**：卖家一直不处理时，买家可从 `PENDING` 直接 `escalate` 申请平台仲裁 ——
  否则售后就成了「卖家单方面决定要不要理你」。（这是对 `07` 篇草案的补全：草案只有卖家处理与运营裁定，
  没有「卖家拒绝后买家怎么办」这一步。）
- **重构（消重）**：把评价域私有的「订单号→内部标识哈希」与「图片直链校验/JSON 读写」抽到
  `support/OrderRefs` 与 `support/ImageUrls`，两个子域共用 ——
  同一规则两处实现一旦漂移（比如只在一处补协议白名单）就是安全缺口。
- **自测**：新增 `scripts/aftersale-smoke.ps1` **101 项断言全绿**；回归 `review-smoke 59/0`、`settlement-smoke 125/0`。
  后端 `BUILD SUCCESS`、8 容器 healthy。接口冒烟合计 **11 套 856 项**。
- **编译期暴露的问题**：重构 `ReviewService` 时漏改 `toView` 里的一处调用点，编译报
  `找不到符号: 方法 readImages(String)` —— 类内私有方法重命名靠编译器兜住，这正是保留编译检查的价值。
- **遗留（已在本轮后的第十轮补齐）**：三端页面当时未开工（第九轮交付为后端 + 端到端断言）。

（以下为历史轮次）

### 2026-09-18（第八轮 · A1 历史订单托管单回补 I-14 + 文档口径收敛）

- **新增接口**：`POST /api/admin/settlements/backfill?dryRun=`（运营，幂等）。扫描「已有资金事实、却没有账本」的订单，
  按订单状态推断资金状态：`PAID`/`SHIPPED` → `CUSTODY`、`COMPLETED` → `RELEASED`、`REFUNDED` → `REFUNDED`；
  金额取 `pay_amount`，放款时间取订单**真实的 `finished_at`**（而不是脚本执行时刻 —— 回补的意义是与历史对得上）。
- **参数走查询串而不是请求体**：首轮自测暴露真问题 —— 用 `@RequestBody` 时「无请求体 + 客户端默认 Content-Type」
  会被判成 **415**，运营用 curl 直接调这个运维接口就会踩到；查询串没有这个歧义，
  也与既有的 `?active=false` 写法一致。
- **审计口径**：返回结果带 `commissionBps` 与 `commissionNote` —— 历史单据没有留档结算当刻的费率，
  本次只能用当前 `PLATFORM_FEE_BPS` 重算，这个假设必须随结果一起说清楚。
- **实测影响面（回补前后）**：回补前交易库有 **3 笔 `COMPLETED` 订单缺托管单**（确认收货时放款找不到账本，
  只记了一行 ERROR）；回补后 `GET /api/admin/settlements/abnormal` **返回空**，退款单的缺口同样清零。
- **安全边界**：只碰 `s.id IS NULL` 的订单（已有托管单的原样不动）；不给 `PENDING_PAYMENT`/`CLOSED` 补账；
  逐条独立提交（补了 80 条后第 81 条出错不会回滚前 80 条）。
- **自测**：新增 `scripts/backfill-smoke.ps1` **88 项断言全绿**（权限三层 401/401/403、dryRun 不写库、
  三组金额与笔数精确增量、幂等重复执行、挂账清零、控制组逐字段未变、REFUNDED 盲区单独断言、夹具自建自毁）；
  回归 `settlement-smoke 125/0`、`payment-smoke 71/0`、`admin-smoke 49/0`。后端 `BUILD SUCCESS`、8 容器 healthy。
- **自测暴露并修复的缺陷**：
  ① **API 健壮性**：「无请求体 + 默认 Content-Type」调用回补返回 **415** → 参数改走查询串；
  ② **断言基准过远（同时报假失败与假通过）**：dryRun 的「金额一分未动」原先拿「创建夹具之前」的汇总做基准，
  把控制组自身确认收货的放款增量算到了 dryRun 头上（假失败），又恰好被删掉的三条托管单抵消（假通过）→
  改为**紧贴被测操作**取基准；
  ③ **0 值断言被空响应骗过**：`415` 时 `data` 为 null，`[int]$null -eq 0` 让「扫描到 0 条」假通过 →
  所有 0 值断言加 `$null -ne $body.data` 前置。
- **文档口径收敛**（本轮实测 vs 旧文）：容器 7 → **8**（补 `duoduo-app-fulfilment` 行）、
  业务库 3 → **4**、Nacos 注册 4 → **5** 个服务、冒烟 9 套 667 项 → **10 套 755 项**。

（以下为历史轮次）

### 2026-09-18（第七轮 · AI Agent 接管包 + 交接资料双渠道归档）

- 仓库根新增 `AGENTS.md`（AI 编码工具默认入口：三分钟自检 / 必读顺序 / 十条硬规矩 / 交付动作清单）。
- 新增 `docs/agent/00~08`（接管指令与硬规矩 C1–C10 · 领域模型 · 代码地图 · 架构不变式 INV-1~15 ·
  接口契约全集 · 开发任务手册 R1–R6 · 测试门禁 · 待办 A1–A13 · 环境与踩坑 T-01~T-24），
  并同步到乐享知识库「多多宠物商店知识库」（现共 20 篇）。
- 新增 `docs/agent/PROMPT-新AI接手指令.md`：换 AI 接手时的启动提示词（先理解、给计划、经确认再编码）。
- `docs/handover/` 9 篇双渠道归档（腾讯文档项目资产 + 乐享知识库），`manifest.json` 记录全部云端 id。

### 2026-09-18（第六轮 · 履约服务 M11/P2-1：评价与晒单）

- **新增服务**：`duoduo-fulfilment`（:8084）—— 按架构文档的拆分落位（发货 / 物流 / 售后 / 仲裁 / 评价 / 信用分同域），
  本轮先落地「评价与晒单」纵切；compose 第 8 个容器，网关路由 `/api/reviews/**`（白名单只放行只读 `product/**`）。
- **跨域只读**：交易域新增 `GET /api/orders/internal/{orderNo}/fact`（订单事实，**裁剪掉收货人信息**）；
  用户域新增 `GET /api/users/internal/briefs`（批量昵称头像，**只回三字段**）+ `InternalTokenFilter`。
- **评价规则**：仅本人已完成订单可评、`spuId` 须在订单明细、一单一商品一评（唯一索引 + 409）、
  图片 http/https 白名单、追评一次 + 窗口期、匿名只脱敏不删真实主体。
- **前端**：商品详情评价区、`/reviews/new` 逐件评价、`/reviews/mine` 追评、订单列表去评价入口。
- **自测**：`scripts/review-smoke.ps1` **59/0**；`scripts/e2e/review-ui-check.mjs` **20/0**；`tsc` 零错误、`vite build` 通过。
- **自测暴露并修复**：① 内部接口断言打错服务端口（无路由 500 被误读为过滤器故障）；
  ② **PowerShell 把 `$SPU?page=1` 整体当变量名**（`?` 是合法变量名字符）→ 一律写 `${SPU}?`；
  ③ 19 位雪花主键经 `ConvertFrom-Json` 丢精度导致假失败 → 断言改用业务键/原始文本提取；
  ④「商品不在该订单中」由 400 归位为 422（业务规则，不是参数格式错）。

（以下为历史轮次）

### 2026-09-18（第五轮 · 平台管理后台 M10）

- **后端**：`/api/admin/**` 13 个接口（运营报表 / 资金对账 / 类目品牌管理 / 闲置审核 / 订单查询与强关），
  统一 `OperatorGuard` 准入；`t_listing` 补 `audit_reason` / `audit_at` 审核留痕列；
  网关路由补 `/api/admin/**`（不进白名单）。
- **前端**：`pages/admin/` 5 个工作台（运营概览含对账、内容审核、类目、品牌、订单干预）+
  `RequireOperator` 守卫 + 按角色显示的「管理后台」入口。
- **自测**：新增 `scripts/admin-smoke.ps1` **49 项断言全绿**（夹具自建自毁、运营账号用完降权）。
- **自测暴露并修复**：① product 服务 `id-type: input` 下创建类目/品牌未赋主键报 500 → 显式雪花 id；
  ② 审核动词与状态常量比较错位，任何审核都被 422 → 动词/状态两层映射；
  ③ 后台列表复用只含 ACTIVE 的公开查询，停用后无法重新启用 → 专用后台列表 + DTO 带 `status`；
  ④ PS 5.1 中文脚本必须 UTF-8 BOM；`docker exec mysql` 控制台输出 GBK，中文等值断言要改走接口读回。

（以下为历史轮次）

### 2026-09-18（第四轮 · 卖家中心 M5-9 + 商家端 Web M9）

- **卖家账号体系**：`POST /api/users/me/merchant`（实名前置 422、幂等、角色写 `SELLER`）；
  `LoginUser` 修角色大小写 bug 并新增 `isSeller()` / `canAccessSellerConsole()`。
- **卖家中心 6 个接口**（`/api/seller/**`）：概览 / 订单列表 / 订单详情 / 发货 / 售后退款 / 结算对账。
  发货与退款**复用 `OrderService` 的 `shipInternal` / `refundInternal`**，状态机只有一份。
- **权限模型**：准入（角色 403）与归属（`seller_id`）分开校验；不存在 404、越权 403 刻意区分；
  接口不接受任何 `sellerId` 参数；运营可见平台自营 `sellerId=0`；聚合下推到 SQL，不全量捞回内存。
- **网关与过滤器**：trade 路由补 `/api/seller/**`（不在白名单，必须登录）；
  `InternalTokenFilter` 排除 `/api/seller/**`。
- **修复真实缺陷**：客户端发错 `Content-Type` 原本落到兜底返回 **500** → 新增
  `ErrorCode.UNSUPPORTED_MEDIA_TYPE` 与 handler，返回 **415**。
- **物流信息落库**：`t_order` 增加 `logistics_company` / `logistics_no`，`OrderView` 回显，
  买家与卖家详情页都显示 —— 此前只写进状态日志，卖家发货后在详情页看不到自己填的单号。
- **商家端 Web 4 页**（`/seller` · `/seller/orders` · `/seller/orders/:orderNo` · `/seller/settlements`），
  Layout 与「我的」页按角色显示入口；补 favicon，消除 `/favicon.ico` 404。
- **自测**：后端 6 模块 `BUILD SUCCESS`，4 个业务镜像重建、当时 7 容器 healthy；
  新增 `scripts/seller-smoke.ps1` **84 项断言全绿**；真实浏览器
  `scripts/e2e/seller-ui-check.mjs` **29 项断言全绿**（含截图）；
  前端 `tsc --noEmit` 零错误、`vite build` 通过。七套接口冒烟合计 **557 项**。
- **UI 自测暴露并修复**：物流信息没落订单表（只写进状态日志）；页面缺 favicon 导致 404。

### 2026-09-17（第三轮 · 买家端 Web 商城 M6 / P1-c）
- **新建 `frontend/`**（React 18 + TS strict + Vite 5）：12 路由覆盖主交易闭环 10 页；
  API 层（类型化客户端 + 端点清单 + 统一错误/重试/刷新）、通用组件（卡片/分页/弹窗/状态步骤/表格）、
  两个 store（auth 会话恢复 / 购物车角标）。
- **零依赖二维码组件**：自实现 QR 编码（Byte 模式 / L-M-Q-H 纠错 / 版本 1–10 / 8 掩码择优），
  服务于沙箱扫码支付；支付弹窗带 30 分钟倒计时 + 3 秒轮询（支付成功自动刷新订单状态）。
- **与后端口径逐一对齐**（以真实接口实测为准）：响应解包 `data`、分页嵌套于 `data`、
  金额单位分、成色/物种/生命周期字典码值、订单状态枚举、托管三态、幂等键语义。
- **两处自测暴露的前端缺陷修复**：① 闲置成色原先硬编码中文，而后端下发码值
  （`NEW`/`LIKE_NEW`/…），会导致筛选下发错值——改为字典驱动 + 常量兜底映射；
  ② 宠物建档表单未提示「生命阶段须与生日推算一致」（后端 422 规则）——
  表单增加实时一致性提示与一键修正，提交前拦截并说明原因。
- **登录页演示账号修正**：实测演示库 `1380000*` 段无预置账号（此前文档与数据不符），
  移除失效的快捷填充按钮，改为「一键生成体验账号」走真实注册流程。
- **验证手段**：curl 端到端冒烟（注册→加购→下单幂等→沙箱扫码→托管→退款，全绿）+
  真实 Chromium 逐页渲染验证（12 路由），截图归档 `frontend/docs/screenshots/`。

### 2026-09-18（沙箱支付渠道 M8-1）
- **支付口径定为沙箱支付**（用户拍板）：不接真实微信/支付宝商户渠道，资金不真实流转。
- **`PaymentChannel` 渠道抽象**：预下单 / 回调验签 / 查询三个动作；`MOCK`（即时成功）与
  `SANDBOX`（扫码形态）两个实现；`PaymentChannelRegistry` 统一注册。将来接真实渠道只加实现类。
- **支付改为「预下单」语义**：`POST /api/orders/{orderNo}/pay` 返回 `codeUrl`（二维码内容，
  指向网关的沙箱收银台）与过期时间（默认 15 分钟）；`MOCK` 渠道保持即时完成（自动化测试用）。
- **沙箱收银台**（`GET /api/payments/sandbox/pay/{payNo}`）：服务端渲染页面，展示单号 / 金额 / 状态，
  「模拟扫码支付」按钮走 `POST /api/payments/sandbox/{payNo}`，**与渠道回调共用同一套验签与入账逻辑**。
- **回调入口**（`POST /api/payments/callback/{channel}`）：HMAC-SHA256 验签 + 时间戳窗口（±5 分钟）
  + **金额核对**（不一致拒绝入账）+ 幂等（先查 + 唯一索引 + CAS）；重复回调只入账一次。
- **支付单生命周期**：`PENDING → SUCCESS / EXPIRED / CLOSED`；`t_payment` 增加 `code_url`、`expire_at`；
  过期扫描并入补偿调度；**订单取消/关单时作废未支付支付单**（接通了此前一直缺失的 `markClosedByOrder`，
  修复「订单已取消、二维码还能扫出钱来」的漏洞）。
- **六套端到端冒烟共 475 项断言全绿**：54 / 87 / 76 / 71 / 125 / **62（新增支付渠道）**。
- 修复自测暴露的问题：① 未知渠道/MOCK 回调拒绝用错了 400（应为 422 业务错误，与注册表 javadoc 一致）；
  ② 二维码内容默认指向交易服务 8083 直连地址（扫码设备不可达，改为网关 8080 并补网关路由与白名单）；
  ③ 收银台响应头缺 charset（严格客户端按 Latin-1 解码中文乱码，已声明 UTF-8）。

### 2026-09-17（第二轮）
- **C2C 担保交易（M5-8）**：买家付款后资金进平台托管（`t_settlement`，`uk_settle_order` 一单一条）；
  确认收货放款给卖家；退款原路退回买家；**发货满 N 天自动确认收货并放款**（补偿调度的一部分）。
  资金状态迁移全部用 CAS 条件更新，重复放款 / 重复退款天然无副作用。
- **订单状态机新增终态 `REFUNDED`**，并移除历史上那条被误用的 `PAID → CLOSED` 边
  （它既是售后退款通路，也就等于把「用户自助取消已付款订单」的口子一起打开了）。
- **退款两个入口**：买家自助（仅已付款未发货）/ 运营侧（已付款、已发货，走 `X-Internal-Token`）；
  已放款订单的退款被明确拒绝（需追偿或平台垫付）。
- **退货入库**：商品服务新增 `restock` 与 `POST /api/products/skus/stock/restock`；
  退款后货回库走台账 `RESTOCK_PENDING → RESTOCKED`，失败由补偿调度重试 —— **退款不因入库失败而回滚**。
- **`t_order` 增加 `refunded_at`**（退款不是「订单关闭」，资金动作需要独立时间戳）与
  `idx_order_auto_receipt`（自动收货扫描）索引。
- 修复自测暴露的问题：① 退货入库端点编译通过但运行时 404（新增端点无内部引用，编译发现不了，
  必须实调一次验证）；② 自测脚本里佣金取整用了 PowerShell 的「四舍五入」而非 Java 的整数除；
  ③ 跨轮次断言未按时间范围限定，被历史数据误伤。

### 2026-09-17（第一轮）
- **交易域全链路打通**：购物车（7 接口）→ 下单拆单（含明细快照与库存冻结）→ 支付（幂等）→ 发货 → 确认收货 → 超时关单。
- **商品服务新增库存写接口**：`/api/products/skus/stock/{freeze,release,deduct}`，原子条件更新 + 内部凭据保护。
- **可售口径统一**为 `stock − frozen_stock`（原「只看 stock>0」会把已被他人下单锁定的商品仍显示有货）。
- 修复自测暴露的三个真实缺陷：① 已付款订单可被用户自助取消；② 超时关单释放依据错用「台账自身的过期时间」；
  ③ 并发释放时「冻结量不足」被当作失败，导致台账永远卡在 FROZEN（改为冲正为 RELEASED 并留审计日志）。

### 更早
- 容器化开发环境：当时 7 容器全 healthy、Nacos 注册 4 服务、网关 `lb://` 生效、`down -v` 从零重建可复现。
- 修复中文「双重编码」缺陷：初始化 SQL 一律以 `SET NAMES utf8mb4;` 开头，
  否则中文会以 latin1 入库，表现为「数据看似存在、但中文关键词检索永远 0 命中」。
- 商品域：类目树 / 品牌 / SPU-SKU 检索（6 种排序）/ 价格区间区间相交语义 / 物种与生命阶段筛选 /
  违禁词 422 / C2C 闲置可见性控制。
- 用户域：注册登录 / httpOnly Cookie / 网关 401 拦截 / 身份透传 / 实名掩码 /
  宠物档案与「生命阶段矛盾」422。
