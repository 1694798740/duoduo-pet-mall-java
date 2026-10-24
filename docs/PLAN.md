# 多多宠物商店 · 项目开发计划（看板）

> **最新一轮（2026-10-24 · 第九十三轮）**：**商品管理四项改造 —— 审核防绕过 / 富文本与自定义参数 / 主图优先 / 评价与经营分析** ——
> ① **审核绕过修复**：根因是「是否曾被驳回」只读当前 `audit_status`，卖家第一次编辑把 REJECTED 改成 PENDING 后该事实消失，
> 第二次编辑即被自动放行。改为持久化标记 `rejected_at`（人工/抽查驳回落时间、**人工通过清空**）+ `reject_count`；
> 审核四列改显式写（含 null，修掉「已通过 + 旧驳回原因」）；编辑不再无条件恢复在售（主动下架不悄悄上架）。
> ② **编辑能力补齐**：新增 `intro` / `detail_html` / `spec_params` / `packaging` 四列与编辑 UI；富文本为自研轻量编辑器
> （contentEditable，无新增前端依赖），服务端 **jsoup 白名单消毒**（script/onerror/javascript: 全剥离），端侧再用 DOMParser 洗一遍；
> 商家端「编辑」与「发布」两处同款，商城 Web + 小程序详情均渲染。
> ③ **图片顺序**：详情图集改为主图恒第一（服务端归一 + 端侧兜底），修掉「首页卡片 A 图、点进去先看 B 图」。
> ④ **评价与经营分析**：新商家页「商品分析」——逐商品销量/成交额/退款件数/评分/评价数/好评率 + KPI；
> 销量走 `t_order_item` 聚合（**不读 `t_spu.sales_count`**，该列无写入链路）；新增 `GET /api/seller/product-stats`；
> 卖家列表回带 `rating/ratingCount`；评价面板复用商城组件（好评=4 星及以上，同一口径）。
> SQL：`incremental/46`。验证：`product-manage-r93-smoke.mjs` **25/0**（驳→两连编辑仍 PENDING、通过后清标记、
> 注入串被消毒、主图顺序、主动下架保持下架）、`product-manage-r93-ui-check.mjs` **19/0**、商品模块单测 **120/0**
> （新增 2 条回归）、既有 `product-audit-ui-check` **36/0**；三应用 tsc 0 错误、容器 healthy。
> 文档：工作区《商品管理改造与近两周待办-2026-10-24.md》。

> **上轮（2026-10-24 · 第九十二轮）**：**消息交互终版 —— 点击式录音 / 单向删除 / 时间分隔 / 自定义弹窗 / 引用跳转（im 迭代收尾打磨）** ——
> 客服 + 私信双通道、web 三页 + 小程序两页同批对齐：
> ① **录音改点击式**：废弃「按住说话」（总提示太短），点麦克风开始 → 可**暂停 / 继续 / 取消 / 结束并发送**；
> web MediaRecorder 只累计「正在录」片段时长（pause 期间不计），小程序 RecorderManager pause/resume 对齐；<600ms 仍拒。
> ② **弃用浏览器原生弹窗**：web 撤回等确认全部换 useConfirm 自定义弹窗（项目记忆已固化「所有项目一律不用原生弹窗」）。
> ③ **时间展示**：10 分钟内连续消息不重复显示时间；超 10 分钟在消息流中央插分隔条 —— 当天 HH:mm /
> 昨天·前天「昨天/前天 HH:mm」/ 超 3 天「M月D日 HH:mm」（web `chatSeparatorLabel`、小程序 `chatTimeLabel` UTC+8 口径）。
> ④ **消息操作**：移动端长按 / web 悬停「···」弹同款菜单（引用 / 删除 / 撤回 / 多选）；**单向删除**（仅本人不可见、
> 对方仍可见，支持多选 / 全选）与撤回（双向、留痕覆写、整行居中「你/对方撤回了一条消息」）语义严格分列 ——
> 两张消息表各加两个 hidden 时间戳列（`hidden_for_user_at`/`hidden_for_seller_at`、`hidden_for_sender_at`/`hidden_for_receiver_at`），
> 读取路径按查看方 isNull 过滤（aroundId 窗口同滤），删除幂等返回本次隐藏条数。
> ⑤ **引用跳转**：点引用卡滚回原消息并闪烁高亮；原消息不在当前窗口时走 **aroundId 定位窗口**
> （服务端返回「目标往前 half+1 + 往后 half 条」一页，目标必在窗口内），support thread / seller thread / 私信 conversation 三路齐备。
> SQL：`incremental/45-message-self-delete-around.sql`。
> 验证：新冒烟 `scripts/message-self-delete-around-smoke.mjs` **30/0**（客服侧单向删除与对端可见性 / 幂等计数 /
> 双向各管各视角 / aroundId 窗口含目标且正序、不漏已删 / 私信侧 A-B 互删不串扰）；回归 9 套全绿
> （chat-recall 25、realtime 17、r49 15、r50 18、r53 17、r55 41+1 跳过、lifecycle 32、read 24、split 22）；
> mall/merchant tsc 干净、三容器 healthy。★ 顺手修两处陈年回归债：r50 种子公告丢失重放 17 号种子（数据缺口非代码）、
> r55 E4 ship 路径 M5-9 起已改卖家归属校验（自营无卖家账号，改 skip 并注明 F 块已覆盖该链路）。
> **★ 部署侧事故（2026-10-24，用户验收时发现「页面还是旧版」）**：根因是 **web 镜像只 COPY 宿主机的
> `apps/{app}/dist`，不在容器内构建**（见 `frontend/Dockerfile` 头部约定）；本轮只跑了 `tsc --noEmit`
> 与 `docker compose build`，跳过了 `npm run build`，于是把 8 小时前的旧 dist 打进镜像（构建仅 16s 即为征兆）。
> 定位三证据：容器内 `assets/*.js` mtime 早于源码修改时间、产物 grep 不到新类名（`chat-time-sep`/`msg-menu-trigger`）、
> `docker compose build` 日志无前端编译步骤。修复：`npm run build:mall` + `build:merchant` → 核对 dist mtime 与
> 新标记 → `compose build` + `up -d --force-recreate` → 确认 index.html 引用新 hash（`main-BQ0v8Dz3.js` /
> `merchant-CorQTNv6.js`）且 `Cache-Control: no-store`。**防复发：改前端后一律走 `scripts/web-up.sh`**
> （内部即 npm build → compose build → up），禁止直接 `compose build` 前端镜像。
> 新增 e2e `scripts/e2e/r92-message-ui-check.mjs` **18/0**（打 8088 容器产物：无「按住说话」/ 麦克风 SVG /
> 点击出录音条并可取消 / ··· 菜单四动作 / 分隔条与多选样式存在 / 无 JS 异常），确保「接口改了、UI 没变」这类
> 事故能被自动化抓住；既有 `support-ux-ui-check` 53/0 同轮复跑通过。
> **★ 2026-10-24 第三批缺陷修复（用户验收，2ff3065）**：① **暗色菜单文字不可读** —— `.msg-menu button`
> 用了 `var(--text-1, #23262d)`，但主题只定义 `--text/--text-2/--text-3`，暗色下恒落深色 fallback（深底深字）；
> 改 `var(--text)` + hover 用 `--surface-2`。② **菜单被聊天滚动容器裁剪**（对方消息只露空框）—— 原为行内
> absolute，被 `.sup-body{overflow:auto}` 裁掉；改 `createPortal` 到 body + `position:fixed`，按触发按钮视口
> 坐标定位、按菜单真实尺寸边界夹取（越界翻转/内推），滚动与 resize 即关闭。③ **录音直接结束误报「太短」**
> —— `onstop` 只读 `activeMsRef`，正在录的最后一段从未结算（先暂停再结束才成功，正是此因）；`finish()` 在
> stop 前补结算当前段（暂停态不重复加）+ inactive 幂等守卫。④ **撤回权限复核**：三端 web 页面与小程序均判
> `mine`，后端 support/social 双侧再校验 `senderId`，**无疏漏**；e2e 断言对方消息菜单只有 3 项（无撤回），
> 冒烟证实越权撤回 403。e2e 扩到 **29/0**（暗色对比度 ≥4.5:1、菜单在 body/不裁/在视口内、录音直发消息 +1）。

> **上轮（2026-10-24 · 第九十一轮）**：**消息撤回 / 引用 / 语音（im 迭代第二批收官）** ——
> 按 `docs/im-realtime-iteration-plan.md` 顺序落地三能力，客服 + 私信双通道、web 三页 + 小程序两页同批对齐：
> ① **撤回**：软删留痕（`recalled_at` + 内容覆写「[消息已撤回]」+ 媒体字段置空，数据库层不可读，不靠前端隐藏）；
> 仅发送者本人 + 120s 窗口 + 幂等；WS 新增 RECALL 信号实时通知对端；会话摘要同步改撤回文案。
> ② **引用**：`reply_to_id` 单列；必须同会话（跨会话 400）；摘要在**读取路径服务端拼装**（文本前 60 字、
> 媒体用 `[图片]/[视频]/[表情]/[语音]/[卡片]` 占位、已撤回显示「原消息已撤回」），端侧摘要不可注入、不递归。
> ③ **语音**：新 bizType `VOICE`（`mediaUrl` + `durationMs` 1–60000ms 服务端校验）；上传白名单扩
> aac/mp3 + 音频 MIME（音频豁免体积上限、扩展名/MIME 一致性按「音频/非音频」二分判断——★ audio/mp4
> 的 mp4 同为视频扩展名，原写法会误拒）；web MediaRecorder（webm/mp4 自动探测、<600ms 丢弃、60s 自动停）、
> 小程序 RecorderManager（aac）+ InnerAudioContext 播放；按住说话 + 长按气泡操作单（引用/撤回）。
> SQL：`incremental/44-message-recall-quote-voice.sql`（两表加 `recalled_at` / `reply_to_id`）。
> **★ 本轮抓出一个关键 bug**：`LambdaUpdateWrapper.eq(col, null)` 生成 `recalled_at = null` 永不匹配 →
> 撤回「假成功」（API 返回占位符但库未落）；改 `.isNull(...)` 并用 MySQL general log + 冒烟反向验证。
> **★ 教训**：mvn 调用若 JAVA_HOME 未设会 exit 127 且被 `| tail` 吞掉退出码，包装后 `$?` 是 tail 的——
> 构建退出码必须直接判，构建产物 mtime 必须与源码编辑时间对照。
> 验证：新冒烟 `scripts/chat-recall-quote-voice-smoke.mjs` **25/0**（撤回留痕/RECALL 信号/越权 403/
> 幂等/引用摘要/媒体占位/跨会话拒绝/语音上传/时长边界/客服侧可见）；回归 session-ui 39/0、ux 53/0、
> seller 40/0、notice 50/0、read 24/0、lifecycle 32/0、realtime 17/0；web 双端 tsc+build 通过、容器 healthy。

> **上轮（2026-10-23 · 第九十轮）**：**聊天实时化（WebSocket 大迭代）** ——
> 客服 + 私信从轮询升级原生 WS 实时推送（`duoduo-social/.../realtime/`）：60s 一次性票据鉴权
> + 网关升级前 Redis 预检（防 101 假成功）、纯信号推送（WS 只传 MESSAGE/READ/TYPING/READY，
> 端侧收信号走既有 HTTP 拉取，单渲染路径）、Redis Pub/Sub 多实例扇出、对端库回源防越权。
> 双向正在输入（700ms 服务端节流）、双向已读回执（maxReadId 游标）、零感知推送；
> web 三页（Support/SupportInbox/Chat）+ 小程序两页接入（引用计数共享连接、指数退避重连）。
> 迭代总方案（实时+语音+撤回+引用）见 `docs/im-realtime-iteration-plan.md`。
> 验证：`scripts/realtime-smoke.mjs` **17/0**（520 并发 2.3s 建齐、推送 32ms、越权不转发、
> 无效票据网关拒绝）；回归 session-ui 39/0、ux 53/0、seller 40/0、read 24/0、notice 50/0。

> **上轮（2026-10-22 · 第八十五轮）**：**十问题批次（用户逐题反馈的 Web 端体验改造）** ——
> ① 自营订单咨询不再报「店铺不存在」（sellerId=0/null 统一落官方客服，ORDER 卡分支同兜底）；
> ② 店铺页补淘宝式排序（综合/销量/价格/最新）与店内关键词搜索（`storeProducts` 增 `keyword`，本店范围 like，不进 ES）；
> ③ 订单卡片显示店铺名并可跳店铺（trade 经 Feign `tradeStoreNameClient` 批量取店名，自营固定「多多自营」，user 域只回 APPROVED 防夹具死链）；
> ④ 客服会话名改店铺名（social `sellerDisplayOf`，自营保持官方客服昵称）+ 订单卡直达对应店铺会话；
> ⑤ 客服会话逐条「未读」琥珀标记（判据 `m.mine && !m.read`）+「已读」回执改为「我方未读=0」；
> ⑥ 聊天加号菜单二级化（图片/视频/表情/商品四入口 + 表情二级面板），移除外置🛍；
> ⑦ 发商品「当前浏览」改名「正在看」并回源真实商品卡；
> ⑧ 商家工作台导航角标（四接口聚合 + 30s 轮询 + `aria-hidden` 防破坏可访问名）；
> ⑨ 通知中心分类未读数（`unread-by-category` 五类分桶）+ 三端未读方案文档 `docs/notification-unread-badge-design.md`；
> ⑩ 图片灯箱（缩放/下载）+ 视频自绘播放弹窗（控制条/音量/全屏；视频核实已在 COS，无需迁移）。
> 验证：定向 `scripts/web10-batch-check.py` **9/0**；e2e store-page **17/0** / notice-chat-follow **50/0** / seller **40/0** / cart-batch **31/0** / product-detail **19/0**；support-read **20/0**、support-split **22/0**；trade 单测 **119/0**、social **16/0**；user/product/social/trade/商城/商家六容器重建 healthy。

> **上一轮（2026-10-21 · 第六批）**：**小程序端暗夜模式（A2 四端收官）** ——
> `app.json` 开 `darkmode` + `theme.json` 两档（原生导航/tabBar/窗口背景）；`app.wxss`
> 暗色块覆盖 10 个 token（正文 #c9cdd6 与 Web 同档柔和度）+ 新增 --gray-weak/--warn-weak；
> 自绘导航栏（26 页在用）品牌蓝压暗 #2b3550；27 个页面 wxss 约 90 处硬编码色批量映射为
> 语义变量（浅色取值＝原值，浅色零变化；`color:#fff` 白字 26 处刻意保留）。
> 新守卫 `check-miniprogram-dark.mjs` 25/0（含反向验证：注入 2 处破坏恰好 2 红）；
> `check-miniprogram.js` 0/0。真机手感待用户侧在开发者工具确认。

> **上一轮（2026-10-21 · 第五批）**：**商品详情页重排** —— 用户报"两栏只占页面一半、拥挤狭窄"。
> 实测定位：全站 `--max-w: 1200px` 在 2K/4K 逻辑宽下只占视口约一半（截图内容宽恰 47% ＝ 1200/2560），
> 且旧结构把介绍+大图×3+规格+包装四张长卡全堆左列、右列大量留白。重排为标准电商结构：
> 顶部两栏（主图 45% | 购买信息 55%）→ 介绍通栏（大图 auto-fit 两列）→ 规格双栏 → 评价/推荐。
> `--max-w` 提到 1320px；缩略图抽 `.pd-thumb` 类。tsc 通过；2560/1440/1024/768 四档视口实测
> 无横向溢出；`product-detail-ui-check` 19/0、`header-layout-ui-check` 109/0、主题守卫 55/0。
> 三端产物已重建部署。

> **上一轮（2026-10-21 · 第四批）**：**暗夜模式两起修复** —— ① 导航栏白底（`.app-header`
> 写死 `rgba(255,255,255,.92)`，深色档无覆盖）→ 抽成 `--header-bg` 变量；② 文字过亮
> （深色 `--text` 亮度≈213）→ 压到 `#c9cdd6`，次级/strong 色同步降档，对比度 13:1 → 约 10:1。
> 连坐修复：4 处硬编码白底（btn-danger / btn-accent-outline / 两处渐变 / hero-pet）、
> **Toast 深色档反色 bug**（白字压浅底）、弹窗遮罩加深。守卫 49 → **55** 项（新增 header 与
> Toast 断言，当场拦下一次 `--c-10894c` 重复定义）。`check-web-theme.mjs` 55/0、
> `theme-ui-check.mjs` 13/0、暗夜首页截图人工复核。三端共用样式一次修复，dist 已重建、容器已更新。

> **上一轮（2026-10-21 · 第三批）**：**图片资源优化** —— ① **图片压缩已落地**：
> `UploadController` 返回的图片 URL 统一带
> `?imageMogr2/auto-orient/thumbnail/1600x1600>/format/webp/quality/82/strip`，
> 配置项 `UPLOADS_IMAGE_SUFFIX`（留空即关闭、默认空）。实测商品图 20766B → 6584B（-68.3%），
> 端到端 `check-image-compression.mjs` 8/0，含 GIF 不动效的独立断言与「置空即红」的反向验证。
> 基础图片处理 ≤10TB/月 免费，不新增成本。
> ② **CDN / 生命周期 / 防盗链三项给出可执行方案**（`docs/image-delivery-optimization.md`），
> 但**未实施**：CDN 卡在「无已备案域名」这一前置；桶级配置卡在现有 AK 无权限
> （`?lifecycle`/`?referer`/`?cors`/`?acl` 全 403 —— 这是刻意的最小权限设计，不应为配规则给应用密钥加 `cos:*`）。
> ③ 成本测算：月下行 100GB 场景下，直连 50.5 元 → 仅压缩 15.5 元 → 压缩+CDN（85% 命中）约 9.7 元。
> ④ 修掉一处透传缺失：`docker-compose.yml` 未映射 `UPLOADS_IMAGE_SUFFIX`，只在 `.env` 加变量容器收不到。

> **上一轮（2026-10-21 · 第二批）**：**个人中心三件事** —— ① 页面重排为三区
> （身份 → 信用与认证 → 账号与隐私；旧版左列半屏空白、右列堆五张卡）；
> ② **头像不显示/保存失败根因＝腾讯云 COS 账户欠费（HTTP 451 整桶停服）**，读和写一起挂；
> 已把报错从误导性的「请稍后重试」改为「欠费停用，请充值」并让 UploadController 原样透出
> 存储层消息；充值恢复后零代码改动即全量恢复（实测上传→落库→网关可读）；
> ③ **撤销注销表单被浏览器自动填充**（非代码赋值，是没声明 autoComplete）→
> 表单 `autoComplete="off"` + 密码框 `new-password`，撤销区只在冷静期渲染，结果就地 Alert 展示。
> 新增 e2e `me-page-ui-check.mjs` 17/0 + 静态守卫 `check-me-page.mjs` 13/0（含反向验证）。

> **最新一轮（2026-10-21）· 四件事连续交付**：
> ① **Web 账号注销入口**（`Me.tsx` 的 `DeleteAccountPanel` 三态 + 独立二次确认 Modal + 提交后 `logout()`，
>    文案与小程序端逐条对齐）；② **Web 暗夜模式**（`theme.tsx` 三态 Provider + `matchMedia` + `colorScheme` 同步，
>    `styles.css` 成对定义 26 个语义变量 + 59 个 `--c-<hex>` 派生变量，商城/后台/商家三端接入，
>    守卫 `check-web-theme.mjs` 49 项、e2e `theme-ui-check.mjs` 13 项真实浏览器实测）；
> ③ **问客服直连**（`SupportController` 下发官方客服 userId、复用 `/api/socials/messages/**`，
>    网页 `SupportEntry` 与小程序「我的 → 在线客服」两端直跳会话；
>    ★ 抓到静默失败：配置指向的客服账号在库里不存在而会话端点仍回 200 → 加 `briefs` 回源校验 + 503 `SUPPORT_AGENT_NOT_READY`，
>    并补跑增量 16 建账号 + 32 个自营商品归属回填）；④ **支付资质调研**（`docs/payment-qualification-alipay-vs-wechat.md`：
>    换支付宝不能解决微信小程序收款 —— 平台规则层面就禁止；支付宝无照档信用卡日限额 1 万，正常经营仍需执照，
>    **办个体工商户是唯一同时解开两家限制的动作**）。
> **顺带修掉真故障**：小程序 `pages/address/address.js` 的 `onInput` / `pickLabel` / `validate` / `save`
> 在做「坐标系修复」时被整段吞掉（花括号配对替换吃掉文件尾部）—— 症状是「输入框打不进字、点保存没反应」，
> 静态自检的「事件未定义」规则抓出，已原样补回。

> **本轮（2026-10-22 · 六十三）· 支付成功却一直是「待支付」已修复**：根因不在回调，在下单 ——
> 多卖家购物车拆成多张子单、每张写同一个 `idempotent_key`，唯一索引不含卖家维度 ⇒ 第二张必撞键；
> 撞键分支又在**事务内**补偿后 `return`，把第一张子单提交了而它的冻结库存已被释放 ⇒
> 商品域 `frozen_stock=0` 而台账仍 `FROZEN` ⇒ 支付时 `deductStock` 永远失败。
> 修：索引加 `seller_id`、撞键改「整体回滚 + 事务外回放整批」、幂等查询改 List；
> 顺手堵死**同一订单反复开收款入口**（用户点两次支付会被收两次钱）。
> 现场已修数到 `PAID` / 台账 `DEDUCTED` / 托管 `CUSTODY 14880`，多收的 148.80 元已原路退回。
> 新增 `split-order-idem-smoke` **18/0**、`check-stock-freeze-recon` **6/6**（修复前 4/6，反向验证只红 1 条）。

> 更新时间：2026-09-29（**R57 已完成 · 必修 6 项全绿**：AI 导购对标行业共识改造 —— **M1 约束硬过滤**（预算/品类在商品域 **SQL 里生效**，提示词约束的只是措辞不是可见性；`ConstraintParser` 规则解析三状态「解析到 / 没提 / 提了没听懂必须告知」）+ **M2 召回独立超时**（300ms/1.5s，与 LLM 15s 分开）+ **M3 限流键不可伪造**（网关**先剥离再写入** `X-Real-IP`；登录用 userId）+ **全站总配额** + **M4 输出护栏**（绝对化用语 + 价格与候选一致性；命中换事实版回复而非拒绝）+ **M5 失败出口**（物流/售后类「答不了」给入口）+ **澄清反问一次给可点选项**（`clarify` + 前端 chips）+ **M6 测试真绿**（预算断言从「仅 llm 模式」改为**无条件必查**——CI 跑 mock 导致其长期空转假绿）+ 单测 29 + 前端 23 + 冒烟 37 + 回归 346 全绿；**★ 单测当场抓到两个真缺陷**：「预算 200 以内」被 MAX/MIN 两套正则同位重匹配成 min=max=200（便宜货被误杀）、「最便宜的是哪个？」被当成预算未解析）｜**R56 已完成 · 自测 172 项 + 回归 614 项全绿**：分享圈补全 —— 评论与回复与评论点赞、关注流（跨域取「我关注的人」）、删除/编辑自己的分享（**软删**；原帖删除后转发单引用块显示「原内容已被作者删除」）、举报与**运营处理台**（后台第 8 个页签）、话题标签 `#`；**★ 本轮抓到三个真缺陷**：新计数字段漏进数字白名单（`commentCount`/`replyCount`/`shareCount` 下发成字符串 → 「按次数排序」退化成字典序）、DTO 的 `@Size(max=2)` 把「截断」变成 400、评论创建回执缺昵称。R57 AI 必修+加强 待办）｜**R55 已完成**（自测 101 项 + 回归 342 项全绿：通知体系补全 —— 补上此前**从未实现的生产者**端点、私信落库即写通知、**通知 + 私信合并未读口径落服务端**、tabBar 角标收敛到唯一出口 `utils/unread.js` 并新增 4 类刷新点、首页卡片式弹窗 `components/notice-toast`、通知页分类 chip 与触底分页；**★ 实测发现并修掉「一条私信被数两次」**：私信会另写一条 `bizType=MESSAGE` 通知行当入口，该行必须从通知未读里排除，且 `<> 'MESSAGE'` 对 `IS NULL` 返回 NULL → 必须显式写 `IS NULL OR`。R55 目标口径（用户点选）：分享圈四项全做 / 角标「通知 + 私信合并成一个数字」/ 首页顶部滑入小卡片 4 秒自动收起 / AI 必修 + 建议加强；R56 分享圈、R57 AI 待办）｜R54（**已完成 · 真机验收已通过**）：成色胶囊框过大 / 导航栏冗余通知入口 / 浏览与想要两个计数口径分开——**用户真机验收报障三条**：① 跳蚤市场成色筛选标签「全部成色/全新/九成新/良好可用」的胶囊背景框过大、未贴合文字；② 小程序右上角残留一个通知图标，与导航栏通知按钮重复；③ 「多少人想要」计数口径不对 —— 用户明确要求拆成两个：**浏览**（页面每被打开或刷新一次 +1，**同一用户可累加**）与**想要**（只按**咨询行为**统计，买家首次咨询计 1 人，同一买家不重复计数）。**逐条根因**：① `.fm-chip` 没有**显式高度**也没定 `line-height`（高度由字号 + 内边距撑出），且 `.fm-chips` 是 `display:flex` 又落在 `scroll-view` 里 —— flex 子项默认 `align-items:stretch` **会被拉伸到与容器等高** → 背景框比文字高出一截、各排高矮不一，圆角大于半高时还会从胶囊退化成「胖椭圆」；纯样式问题。② 导航栏 `nb-msg` 💬 与 tabBar 居中「通知」页**指向同一页面**（R50 重排后形成重复入口）。③ 卡片只有 `viewCount` 一个数、文案却写「N 人看过」—— **把可累加的「次数」说成「人数」**，而「想要」根本没有独立结构，也就不存在去重口径。**修复**：① `.fm-chips` 加 `align-items:center`（阻止拉伸）+ `.fm-chip` **显式定高** `height:48rpx` / `line-height:1` / `flex:none` / `border-radius:24rpx`（恰为半高）；② 删 `nav-bar` 三件套的 `.nb-msg` 图标与 `goNotice()`、三处页面清掉 `msg="{{false}}"`，**tabBar 通知入口必须保留**（删掉会让通知页再也进不去且无编译报错）；③ DDL 新增 `t_listing.want_count` + **去重表 `t_listing_want(listing_id, buyer_id)`（主键即去重键）**，`ListingMapper.insertWantIgnore`（`INSERT IGNORE`）**只有真插进去（返回 1）才给计数器 +1**（应用层「先查再写」并发下会双写），新增 `POST /api/listings/internal/{id}/want`（**刻意用 POST**：写操作，GET 会被预取/重试刷歪），social 域 `MessageService.send` 在 LISTING 消息落库后 best-effort 上报（**异常只 warn**——消息已发出，不该因附属统计判成发送失败），三处 DTO 补 `wantCount`，前端三页改「N 次浏览 + N 人想要」，详情页开 `enablePullDownRefresh` 让「刷新也算一次」落地。**★ 防复发**：`check-miniprogram.js` 规则 7 增补 3 条胶囊约定（error 级，反向验证 3/3 命中）+ 新增 `r54-listing-want-smoke.py`（26 项）+ `r54-miniprogram-copy-test.js`（23 项，含「tabBar 通知入口仍在」与下拉刷新行为）。**验证**：r54 探针 **26/0** + 前端单测 **23/0** + 回归 r53b **26/26** · r53 **28/28** · r52 **57/57** · 聊天热修 **17/17** · api-id-payload **19/19** · flea-mine-filter **42/42** · short-time **12/12** + 静态自检 **0 错 0 警** + 双容器 healthy + DDL 落库校验通过。**★ 本轮最重要的教训：静态规则的假阴性比不检查更危险** —— 规则 7 首版用正则匹配声明时**匹配到了注释里写出的同名声明**，故意删掉真声明后仍报 0 错，给出「已验证」的假象；修法是匹配前**先剥 CSS 注释**（`stripCssComments()`）。 ——以下为上一轮（R53 热修二）：——用户真机验收报障三条：① 按钮文字长了折行、忽大忽小（我的闲置页尤甚）；② 闲置「0 人看过」查看后仍为 0；③ 同一件闲置同时出现在「审核中」与「在售」。**逐条根因**：① 小程序 button **自带 28rpx/侧内边距**，与 `flex:1` 一起挤压文字框 → 长文案折两行；我的闲置筛选条 6 个标签缺 `flex:none` 被压窄，「审核中/审核失败/已下架/已售出」全折行 —— **行数不同即行高不同**，这就是「忽大忽小」；纯样式问题。② **全仓搜不到任何写入 `view_count` 的代码**（只有字段定义 / DTO 出参 / 种子数据）——`t_listing.view_count` 永远是初始值。③ 服务端 `status` 与 `auditStatus` **正交两维**（新发布条目天然 `ON_SALE + PENDING`），而小程序「在售」标签**只传 `status=ON_SALE`、漏了 `auditStatus=APPROVED`** → 待审件被同时捞进「在售」（**服务端一直正确，缺陷在前端拼参数**）。**修复**：① `app.wxss` 的 `.btn`/`.btn-mini` 加 `white-space:nowrap` 兜底 + product / flea-detail / flea-mine 三处页面显式定尺寸与 `flex:none`，筛选条改「**不换行 + 横向滚动**」；② `ListingMapper` 新增**数据库侧原子自增**（读-改-写并发会丢更新）+ `ListingService.detail` 在可见性校验后自增并把新值回填本次响应（**首次查看即 1**），只在公开详情计数、`ownerDetail` 不计、异常只 `warn` 不阻断；③ `flea-mine` 新增 `FILTER_PARAMS` 构成**不重不漏**划分（审核两标签只带 `auditStatus`，交易三标签**必须同带 `auditStatus=APPROVED`**），徽章改**单一状态**（审核态优先），上架 / 下架 / 重提三个按钮权限同源推导。**★ 防复发**：`check-miniprogram.js` 新增**规则 7**（error 级：按钮禁换行 + 横向成员 `flex:none`，已用「故意改坏」**反向验证 6 处全命中**）+ 新增 `flea-mine-filter-test.js`（**真实加载页面、桩 `wx.request` 断言实际查询串** 42 项）+ 新增 `r53b-listing-view-status-smoke.py` 26 项。**验证**：r53b **26/0** + flea-mine-filter **42/0** + 回归 R53 **28/28** · R52 **57/57** · 聊天热修 **17/17** + 静态自检 **0 错**。**教训：接口探针测不到「前端拼参数」；「有字段、有出参、没写入」是最隐蔽的一类缺陷。** ——以下为上一轮（R53 热修）：——用户真机验收报障三条：官方客服会话「内容已删除」、跳蚤市场私信「任何消息都发不出 + 控制台 404」、12:33 发出显示 04:33。**排查确认 ①② 同源**：`miniprogram/utils/api.js` 的 `message.send` 对 `receiverId`/`bizId`/`productId`/`listingId` 做 `Number()` 强转 —— id 是 18~19 位十进制，超出 JS Number 的 53 位有效精度（`Number('710000000000000001') === 710000000000000000`），服务端按错值查库 → `404 用户不存在` → 前端把 NOT_FOUND 文案化成「内容不存在或已删除」。**之所以"之前能发"**：ASK/LISTING 首条由服务端按商品/闲置归属定人（忽略前端 receiverId），掩盖了缺陷；落到 TEXT 才暴露。**③ 根因**：后端时间契约本就是 **UTC + `Z`**（`LocalDateTime.now(ZoneOffset.UTC)` + `toInstant(ZoneOffset.UTC).toString()`），前端 `shortTime` 却 `replace('Z','')` 主动删标记后裸切片 → **全站 13 个页面统一少 8 小时**（函数被聊天/订单/优惠券/秒杀/分享圈/通知等共用）。**修复**：四处 id 一律 `String()` 上行（后端 Long 自动转换）、`share.publish` 与 `share-detail` 的 `spuId` 同族隐患一并修；`shortTime` 重写为「按 UTC 解析 → 转设备本地时区」（手写逐段解析，绕开 iOS JSC 对纳秒 ISO 串的解析差异）。**★ 防复发三道防线**：`check-miniprogram.js` 新增规则 6（error 级，禁止对 `xxxId` 做 `Number()`）+ `api-id-payload-test.js`（真实加载 api.js、桩掉 `wx.request` 断言出参 19 项）+ `short-time-test.js`（时区换算 12 项，含反回归断言）。**验证**：`r53-hotfix-chat-smoke.py` **17/0** + `api-id-payload-test.js` **19/19** + `short-time-test.js` **12（北京）/10（UTC）** + R53 探针 28/28 + R52 探针 57/57 + 静态自检 0 错。**关键教训：探针只测后端 = 假绿** —— 凡是「前端拼装请求体」的契约（id 类型、时区语义），必须有一条直接执行前端代码的单测。以下为第五十三轮内容：——第五十三轮 · 闲置详情卖家卡与问一问 + 详情购买按钮过渡态 + 我的闲置审核态体系——**① 详情页卖家卡与「我想要」（LISTING 问一问全链路）**：product 域新增跨域事实源 `GET /api/listings/internal/cards`（InternalTokenFilter 保护，`InternalListingView` 含 `sellerId`），social 域新增 `ListingClient` + `ListingCardBrief` 契约副本；`bizType=LISTING` 收件人由服务端按 `listing.sellerId` 定（不信前端）、咨询会话豁免「非互关一条」、会话键上下文段升级为 **`ConvContext` record**（none/product/listing 三态统一拼装，商品段 `:p{spuId}` 与闲置段 `:l{listingId}` 平行不共存，双参同带 400）；会话列表/会话页/已读全链路支持闲置卡（type=LISTING，前端分流 `images.listingImage`，列表前缀 🏷️）。**★ 本轮最大的坑（启动即炸）**：`ListingClient` 与既有 `ProductClient` 同为 `@FeignClient(name="duoduo-product")`，同名 `FeignClientSpecification` 注册冲突 `BeanDefinitionOverrideException`，social 容器 unhealthy —— **同一服务多个 Feign 接口必须各配唯一 `contextId`**（`productCard`/`listingCard`），contextId 只影响 bean 命名不影响服务发现。**★ 前端防 401 副作用**：卖家卡数据源复用他人主页公开端点 `api.social.follow.profile(sellerId)` 聚合（零新增后端契约），但 profile 落 `/api/socials/**` 需登录——免登访客逛详情页会 401 → 清登录态 → 触发全局静默重登/跳登录；修法 `loadSeller` **无令牌不发请求**直接占位「卖家」。**② 购买按钮过渡态**：完整 C2C 担保交易（下单/锁定/托管/放款）属设计稿 Batch-3，且设计稿 §12-6 明确**上线前必须核实微信类目资质**——本轮按钮弹「担保交易即将上线」modal 并引导问一问，不做假下单（边界已登记任务清单）。**③ 我的闲置审核态体系（正交双维度）**：`mine/list` 新增 `auditStatus` 白名单参数（PENDING/APPROVED/REJECTED，可与 `status` 组合过滤，非法 400）；**审核驳回时 `status` 同步落 `OFF_SHELF`**（驳回条目不再混入「在售」）；新增 `GET /api/listings/mine/{id}`（卖家视角详情 `ListingOwnerView` 全集字段含驳回原因；`requireOwned` 统一收口，404/403 刻意区分）与 `POST /api/listings/{id}/resubmit`（仅 REJECTED 可调，PENDING/APPROVED 均 422；重提=内容更新 + 回 PENDING + status→ON_SALE + 清驳回原因，实名与违禁词全部复检——发布/重提共用 `normalizeForWrite()` 闸门）；小程序 `flea-mine` 筛选扩 6 项 + 「修改并重新提审」按钮（驳回条目直接进编辑页）、`flea-publish` 编辑模式（`?id=&mode=edit` 拉 owner 详情回填，`fen2yuan` 分↔元换算）。**验证**：新增 `scripts/r53-listing-detail-mine-smoke.py` **28/28 PASS**（A 审核维度过滤 5 + M LISTING 问一问 8 + R 驳回→修改→重提闭环 15，运营账号真实审核链）+ R52 回归 **57/57** + 双容器 healthy + 静态自检 0 错。**状态码口径已固定**：参数校验错 400 / 业务规则违例 422（探针 M6/R7/R12 首轮预期笔误已修正——服务端行为一直正确）。以下为第五十二轮内容：——第五十二轮 · 跳蚤市场发布链路补全 + 结算预览端点 + 405 修复——**① 发布链路**：后端由「仅 2 个只读端点」补成完整 C2C 闭环 —— `POST /api/listings`（发布）、`GET /api/listings/mine/list`（我的发布）、`POST /api/listings/{id}/status`（上/下架），新增 3 个 record（`ListingPublishRequest`/`ListingStatusRequest`/`ListingMineView`）+ `ListingService` 6 个私有校验方法（实名校验→违禁词→限流→默认成色 GOOD→图片去重限 9→价格上限 99999 元）；**安全设计是路径本身**：`/api/listings/search` 与 `/api/listings/*`（均两段）免登只读，三条写路径全部三段（`/mine/list`、`/{id}/status`）或单段（`POST /api/listings`）——因为网关白名单**按路径匹配、不区分 HTTP 方法**，若写 `/api/listings/**` 会把三条写端点一起放行成「匿名 UGC 直写入库」。**② 结算预览**：新增 `POST /api/orders/preview`，新增 `PreviewOrderRequest`/`PreviewOrderPreview`/`PreviewOrderResult`、`OrderService.previewOrder`；**关键取舍**：券试算走新增的**只读** `trial`（`POST /api/coupons/internal/trial`），绝不复用 `lock` —— `lock` 是跨服务写（占券），预览若调它，用户每改一次数量就被占一次券，最后没提交还得等 TTL 解锁；`precheck` 被 `lock`/`trial` 共用，保证「预览能用」与「下单能用」结论一致。**③ 405 修复**：`ErrorCode` 新增 `METHOD_NOT_ALLOWED`(405)、`GlobalExceptionHandler` 新增 `HttpRequestMethodNotSupportedException` handler。**★ 本轮最重要的坑**：`duoduo-common-web` 是**多服务共用模块**，首轮只重建了 product/trade/marketing/gateway 四个服务，**user/fulfilment/ai/social 内嵌的 `common-web` 仍是旧版**（`PUT /api/users/me` 仍回 500）；且 `mvn -pl ... -am install` 因增量检测 9 秒跳过打包（jar 时间戳没变）—— 改用 `mvn -q -o -DskipTests clean package -pl <四个模块>` 强制重打，逐层解包复检 `含 405 异常引用 = True` 才通过。**教训：改共用模块必须重建全部依赖方，且要验证构建产物内容而非只看退出码。** **④ 小程序**：新增 4 页（跳蚤市场列表 / 发布表单 / 我的闲置 / 闲置详情，共 16 文件）+ 首页入口 + 我的页「我的闲置」菜单 + `api.js` 的 `listing` 命名空间 + `images.js` 的 `listingImage`（复用商品图池省主包）+ 手绘 `flea.png` 图标（纯 Python zlib+struct，318 字节，品牌色 #4f6ef7）。**验证**：`r52-listing-preview-smoke.py` **57/57 PASS** + `r51-full-acceptance.py` **155/155 PASS 0 FAIL**（H3b 缺陷闭环）。**R52 热修（用户真机验收发现）**：发布闲置 403「没有权限执行该操作」——根因是三个前端缺口叠加：① 小程序**无实名认证页**（后端 `POST /api/users/me/realname` 一直就绪）；② `flea-publish` 只预检了登录没预检实名；③ `request.js` 把 `FORBIDDEN` 一刀切覆盖成通用文案、吞掉后端「该操作需要先完成实名认证」。修复：新增 `pages/realname`（成功后立即 `refreshSession()` 静默换新令牌——`realNameVerified` 在访问令牌 claim 里，后端 refresh 重读库签发，换完即发不必重登）+ 发布页实名预检引导卡（onShow 复查）+ FORBIDDEN 透传后端文案 + profile 实名入口，app.json 28 页。端到端探针 6/6 + r52 回归 57/57 + 静态自检 0 错误。以下为第五十轮内容：——第五十轮 · 通知中心上线 + tabBar 重排 + 全页面通知入口 + record 计数器类型缺陷根治——**① tabBar**：分类移出（首页已有入口）、通知升为**居中第 3 位**（首页/分享圈/通知/购物车/我的），分类降普通页带返回键；`switchTab` 引用与 index/notification/profile 三处 tab 清单全局同步。**② 通知中心**：启用 R47 已建齐的 `t_social_notice`/`t_social_notice_read`（零 DDL），`NoticeController` 4 端点，定向通知与公告统一视图、已读统一记集合表、他人通知 404；小程序 `pages/notification` 4 件套 + tabBar 角标 + 种子 3 公告（SQL 17 + 06）。**③ 全页面通知入口**：`nav-bar` 内置右上角 💬（R48 的 📩 改回短信样式）+ 新增 `back` 返回键，22 页统一自绘导航（chat/ai 高度扣 `navTotal`）。**④ ★ record 计数器类型根治**：字段名白名单只对普通 POJO 生效，record 走 `POJOPropertiesCollector` 分支绕过 writer 替换 → `UnreadCount.count` 是 `"3"` 而 `PageResult.total` 是 `3`（R47 `FollowCounts`、R49 `ConversationView.unread` 同病）；模块新增公开 `NumericLongSerializer`，record 计数/金额字段显式标注，本域 9 处已标注。**验证**：`r50-notice-smoke.py` 18/18 + social 43/43 + r49 15/15 + r48 8/8 + CI 子集 id-precision 56/56 与 smoke 55/55 + 静态自检 0 错误 + 自建一致性核对全过。以下为第四十五轮内容：——第四十五轮 · 模拟器启动失败热修——**根因**：微信 tabBar 上限 5 项，第四十四轮加分享圈成 6 项，`app.json` 静态校验不过（`["tabBar"]["list"] 不能超过 5 项`），工具拒绝启动模拟器。**修复**：tabBar 收敛为 首页/分类/分享圈/购物车/我的（订单 tab 移除，「我的 → 我的订单」入口替代）；orders 恢复原生导航栏（非 tab 页需返回键，R41 自绘栏改造还原）；cart/after-sales/checkout/seckill 四处 `switchTab(orders)` 改 `navigateTo|redirectTo`；index/profile 的 tab 页清单同步。验证：app.json 合法且 tabBar=5、7 个改动 js 语法全过、switchTab 残留清零、静态自检 0 错误。以下为第四十四轮内容：——分享静默登录根治 + 分享圈升一级 tab + 点赞/收藏/转发。
> **当前进度（2026-10-12 · 最新）**：**容器侧验证已补跑完毕（Docker 恢复后）** —— ① 开关第④层「容器里真生效」确认成立（清单 15 项中 10 项来源为「容器实际生效」）；② `ops-status` 全栈一屏 **OK 26 / WARN 1 / FAIL 0**；③ `ops-restore` 恢复演练 **哈希 6/6 + 58 表 / 19,242 行 / 差异 0**（同一次跑出）；④ 备份 6/6、耗时 1 分 59 秒。过程中**实测揪出并修掉开关第④层的脆弱判据**：原先靠公式**猜容器名**（只对 `duoduo-xxx-app` 成立，`duoduo-mq-broker` / `duoduo-es` 全猜错），猜错不报错、只静默退回「.env（容器未读到）」→ 改为**读 compose 声明的真实 `container_name`** + 两条自证断言，反向验证 **5/5**。开关自检 33 → **35 条全 OK**。
> 上一轮：**E25 已完成 —— 按《开发执行总纲》，P4 收官**（运维与回滚手册 `docs/OPS.md` + 三个运维脚本 `ops-switch` / `ops-status` / `ops-restore` + 手册门禁 `check-ops-doc`）。核心产出不是"写一篇文档"，而是把「有开关」从文档措辞变成**四层逐层可核的实物**：实测查出 **4 处开关写了但不生效**（yml 有占位符但 compose 没透传 / 连 yml 占位符都没有 / compose 硬编码字面量），全部修掉并对每个断言做了**反向验证**（改坏必报红、还原必回绿）。同轮修掉 3 个真缺陷：恢复演练的**哈希判据静默没验**（`PSCustomObject` 无 `ContainsKey`）、Docker 退出被误报成「备份损坏」、`Measure-Object -Property` 对 hashtable 静默失效。验证：开关校验 33/0 + 反向 6/6、一屏状态 OK 26 / WARN 1 / FAIL 0、恢复演练 58 表 / 19,242 行 / 差异 0、手册门禁反向 7/7。**至此 P0~P4 全部收口，S1~S4 全部落地；下一步进入用户的一轮完整验收**（交付：测试报告 + 完整用例 + 照做即可的指引）。本轮详情见 §6 变更记录首条。

> 上一轮（2026-10-12 同日）：**E24 / S4 已完成** —— E24 租户健康度面板（四维 / 三态 / 样本门槛 / 新套件 `tenant-health-smoke.ps1` **90/0**）与 S4 数据库自动备份（`scripts/db-backup.ps1`，六库 58 表 / 3.85 MB，从"只有人工 dump"变成一条可自证的命令）。更早：**S3 每日对账**（`t_recon_run` / `t_recon_diff` + 13 条判据 + 03:30 选主调度；`recon-smoke.ps1` **53/0**；★ 同轮抓到网关路由漏 `/api/admin/recon/**` → 固化成 `check-gateway-routes.ps1`）。此前：**E23 完成 —— P3 收官**（代运营最小版，`proxy-ops-smoke.ps1` **121/0**）；**P3：E16 ~ E23 全部 ✅**。
> 工程：`D:\智能体项目\duoduo-pet-mall-java`（Java 微服务正式版）
> 运行方式：**Docker 优先**（中间件 + 业务服务全部容器化，已跑通）
> 远端：https://github.com/1694798740/duoduo-pet-mall-java （私有）

---

## 0. 这个看板怎么用

- 三个状态：**已完成** / **进行中** / **未开始**；被卡住的标 **阻塞**。
- 最下方「**问题与协助区**」是我遇到障碍的地方。**需要你出手**的会写清「需要你做什么」；
  你处理完把该行状态改成 **已协助**，我下一轮会先自检该问题是否真的解决，再继续干活。
- 我自己解决掉的也会留档（标 **已自愈**），方便你复盘，不需要你操作。

---

## 1. 里程碑

| # | 里程碑 | 状态 | 说明 |
|---|---|---|---|
| M0 | 工程骨架 + 分层与错误体系 | ✅ 已完成 | duoduo-common / common-web / gateway / user / product 五模块 |
| M1 | 用户域 | ✅ 已完成 | 注册登录、httpOnly Cookie、401 拦截、身份透传、实名掩码、宠物档案 |
| M2 | 商品域 | ✅ 已完成 | 类目树、品牌、SPU-SKU 检索（6 排序）、价格区间、物种/生命阶段筛选、C2C 闲置 |
| M3 | 端到端冒烟（54 项） | ✅ 已完成 | 免安装模式 54/54；容器化模式复跑 54/54 |
| M4 | **Docker 化开发环境** | ✅ **已完成** | **7 容器全健康**；Nacos 注册 **4 服务**；从零重建数据卷可复现 |
| M5 | **P1-b 交易服务** | ✅ 已完成 | 购物车 / 下单拆单 / 库存冻结 / 订单状态机 / 支付幂等 / C2C 担保交易 / **卖家中心（M5-9）** |
| M6 | 买家端 · **Web 商城** | ✅ 已完成 | 主交易闭环 10 页（React 18 + TS + Vite）；PC 端二维码扫码支付 + 3 秒轮询；**真实浏览器逐页验证通过** |
| M7 | 买家端 · **微信小程序** | 🔄 进行中 | 骨架 + 后端 code2Session 登录均已交付（第十五轮）；**AppID/Secret 已配置（2026-09-25）**：user 容器已注入凭据、`project.config.json` 换真实 AppID、`WECHAT_LOGIN_READY=true`、真实通道验证通过（假 code → 401 非 503）；**A14 全面重构已落地（第二十轮）**：TabBar 五页框架 + 领券中心 / AI 导购 / 限时秒杀三特色页 + 请求层与设计 token 统一；待用户在微信开发者工具完成 wx.login 真机联调 |
| M8 | **沙箱支付渠道** | ✅ 完成 | 渠道抽象 + 扫码预下单 + 收银台 + 回调验签 + 幂等（M8-1 自测 62 项全绿）；真实渠道留接口位 |
| M9 | 商家端 · **Web（商家服务平台）** | ✅ 已完成 | 概览 / 订单 / 订单详情 / 结算对账 4 页；发货与售后退款走真实权限校验；自测 **82 通过 / 0 失败** |
| M10 | 平台管理后台 · **Web** | ✅ 完成 | 类目品牌维护、内容审核、订单干预、资金对账、报表 |
| M11 | 营销 / AI 导购 / 履约 / 检索 / 压测 | 🔄 进行中 | **履约域四块已落地**（新建 `duoduo-fulfilment`）：P2-1 评价与晒单、**A3 售后仲裁（含三端页面）**、**A2 物流轨迹（含买卖双方时间线）**、**A4 信用分（权重模型 + 前端展示）**；**A5 优惠券营销已落地**（新建第 6 个业务服务 `duoduo-marketing`，:8085，含前端领券中心 / 券包 / 结算选券）；**A8 收货地址簿已落地**（用户域地址簿 + 交易域下单写收货快照 + 前端 `/addresses` 与结算页二选一），同轮**根治 T-39 雪花 ID 精度缺陷**（`LongToStringJacksonModule` 全站统一按字符串下发 64 位标识，金额/计数/时间戳按字段名白名单保持数字）；**A9 AI 导购「多多」已落地**（新建第 7 个业务服务 `duoduo-ai`，:8086，**无状态**；真实候选召回 + 三形态 + 限流/降级 + 前端 `/ai` 免登录可用）；**A10 ES 检索已落地**（商品域单索引 `duoduo-search`，拼音/同义词应用侧容错、增量兼补偿、对账与重建、**ES 不可用降级 MySQL**）；**A9 `llm` 形态已用真实百炼 Key 联调通过并切 `qwen-max`**（T-60/T-61 两缺陷修复）；**A11 RocketMQ 削峰 + Sentinel 限流已落地**（异步下单通道 `POST /api/orders/queued` + 任务台账 + 死信可查可重放 + `order-place` 资源 QPS 限流，MQ 容器挂 `profile: mq`）；**A12 压测已完成**（sync ~100 单/s / queued 入队 ~1000 单/s / 热点行 +40%，报告 `docs/perf/A12-load-test-report.md`）；**A14 限时秒杀已落地（第二十轮）**：交易域三表 + Redis Lua 预扣（软依赖可降级）+ DB 原子扣减兜底防超卖 + C 端 3 端点 / 运营端 2 端点 + 关单回补钩子 + Web `/seckill` 页，自测 `seckill-smoke.ps1` **26 通过 / 0 失败**；余下：XXL-Job；A13 AppID 已配置、真机联调中 |

---

## 2. 端形态（2026-09-17 你确认的口径）

**一句话：买家两端口（小程序 + 网页），商家与平台都是 Web。**

| 端 | 形态 | 面向谁 | 主要能力 | 支付方式 |
|---|---|---|---|---|
| 买家端 · 微信小程序 | 微信小程序 | 买家 | 浏览、搜索、购物车、下单、订单、售后 | 展示支付二维码 / 拉起支付（沙箱） |
| 买家端 · Web | 响应式网页 | 买家 | 同上（PC 与手机浏览器） | 展示**支付二维码**（沙箱）；将来可换真实渠道 |
| 商家端 | Web | 商家 / 卖家 | 商品上下架、订单处理、发货、售后、结算对账 | 收款侧，不出钱 |
| 平台管理后台 | Web | 平台运营 | 类目品牌、内容审核（C2C 闲置）、订单干预、资金对账、报表 | — |

**已经为它铺好的地基**（本阶段已完成，接前端时只需对接接口）：

- 身份：网关统一鉴权 + `X-User-*` 透传，多端共用同一套登录（小程序侧后续接 `code2Session`）
- 资金：`t_settlement` 资金账本 + `SettlementView`，买家端可直接显示「资金托管中 / 已放款 / 已退回买家」
- 支付：预下单返回**二维码内容 + 过期时间**，前端只需把它渲染成二维码；支付结果轮询接口已提供
- 状态：订单状态与托管状态都给出**中文文案**，前端不必各维护一份映射
- 分页/错误：统一 `PageResult` 与 `{ error: { code, message, details }, requestId }`

---

## 3. 支付渠道：当前采用「沙箱支付」

**你的决定（2026-09-17）**：真实微信 / 支付宝收款需要营业执照、企业实名、备案域名与一堆密钥，
手续太重 —— **改用沙箱支付**，把「能扫码、能收到支付回调、能被验签、能幂等入账」这套**工程链路**做实，
真实渠道的资质问题不再作为项目阻塞项。

### 3.1 沙箱支付做成什么样

| 能力 | 说明 | 为什么这样做 |
|---|---|---|
| **渠道抽象** | `PaymentChannel` 接口：预下单 / 校验回调。`MOCK`（预下单即成功）与 `SANDBOX`（完整流程）是它的两个实现 | 将来接真实渠道时**只加一个实现类**，订单、库存、账本、回调处理全都不用改 |
| **预下单** | 下单后调用支付接口 → 返回 `payNo` / `codeUrl`（二维码内容）/ `expireAt`，订单仍是「待付款」 | 与微信 Native、支付宝当面付的返回形态一致：渠道给你一个字符串，商家自己渲染二维码 |
| **收银台** | 沙箱渠道的 `codeUrl` 指向一个**沙箱收银台页面**，打开可见金额与「确认支付」 | 现在就能肉眼验证整条流程；将来换成真实渠道的收款页，前端代码不用动 |
| **回调验签** | 回调入口 `POST /api/payments/callback/{channel}`：**签名校验 → 落库 → 驱动订单**。沙箱用 HMAC-SHA256 + 时间戳防重放 | 这是真实渠道最难写对的一段，先在沙箱里把它写对、测到 |
| **幂等入账** | 支付单 `(channel, idempotent_key)` 唯一索引 + 状态 CAS；重复回调只入账一次 | 渠道回调天然会重复投递（渠道重试、网络抖动），靠唯一索引兜底比「先查再插」可靠 |
| **支付单超时** | 预下单即写入 `expire_at`，超时未支付由补偿调度置为 `EXPIRED` | 否则会出现「订单已超时关闭、支付单还挂着能付」的矛盾状态 |

### 3.2 将来若要接真实微信 / 支付宝（备查，**当前不阻塞**）

材料清单仍留档在协助区 I-12 的折叠附件里，需要时把 `SANDBOX` 换成真实实现即可 ——
订单、冻结台账、资金托管、回调处理这些**都已经写成渠道无关**的了。
唯一真正需要外部条件的只有两处：渠道预下单调用、回调验签（各自的 SDK 与证书）。

---

## 4. 任务清单

### 已完成

| 任务 | 产出 / 证据 |
|---|---|
| 微服务骨架与分层纪律 | Controller 不写业务逻辑、Service 不依赖 HttpServletRequest、Mapper 参数化 |
| 用户域全部接口 | 网关 401 拦截、身份头先剥离再写入、refresh 令牌存 SHA-256 并轮转 |
| 商品域全部接口 | 违禁词 422、非法排序 400、未审核闲置不可见 |
| 54 项端到端冒烟脚本 | `scripts/smoke.ps1`，每次随机手机号可重复跑 |
| GitHub 推送能力恢复 | 本地与远端逐字节一致（blob 哈希全等） |
| 项目资产分类归档 | 按日期分目录 + 99-历史版本，写入维护规则 |
| 容器化中间件 | MySQL 8.0.36 / Redis 7.2 / Nacos 2.3.2 三容器健康 |
| 容器数据口径对齐 UTC | `TZ` + `--default-time-zone` 与应用 `serverTimezone=UTC` 同口径 |
| 商品库初始化 SQL 生成 | `docs/sql/02-duoduo-product.sql`（由模块内 schema.sql + data.sql 拼装） |
| **业务服务容器化** | Dockerfile（temurin:21-jre-alpine，非 root 运行）+ `.dockerignore` + compose 四个 app 服务；四镜像已构建 |
| **Nacos 服务发现接入** | 网关改用 `lb://` 负载均衡；已注册 `duoduo-user` / `duoduo-product` / `duoduo-trade` / `duoduo-gateway` 四服务 |
| **容器化模式跑通 54 项冒烟** | `scripts/smoke.ps1` → **54 通过 / 0 失败** |
| **中文双重编码缺陷修复** | 见 I-08；`SET NAMES utf8mb4` 已写入 3 个初始化脚本，从零重建数据卷复验通过 |
| **M5-1 `duoduo-trade` 模块骨架** | 模块接入父 POM（6 模块反应堆）；`TradeApplication`、`TradeServiceConfig`、三档配置；BUILD SUCCESS |
| **M5-1 交易库 DDL** | 7 张表：`t_cart_item` / `t_order` / `t_order_item` / `t_order_status_log` / `t_stock_freeze` / `t_payment` / `t_settlement`；已在容器 MySQL 实际执行并校验 |
| **M5-2 交易服务容器接入** | compose 增加 `duoduo-trade-app`（:8083）；**7 容器全 healthy**、Nacos 注册、启动 2.7s |
| **M5-3 购物车** | 商品服务新增批量 SKU 接口；交易服务引入 OpenFeign；7 个接口；**自测 87 通过 / 0 失败** |
| **M5-4 下单拆单** | 订单 4 实体 + Mapper + `OrderStatus` + `OrderNoGenerator` + `ProductGateway`；幂等靠 `uk_order_user_idem`；明细存下单快照；**自测 76 通过 / 0 失败** |
| **M5-5 库存冻结与释放** | 库存操作改为**原子条件更新**（判断写进 WHERE，不用分布式锁）；服务间凭据 Filter **失败关闭**；可售判定改为 `stock − frozen_stock` |
| **M5-6 订单状态机 + 超时关单** | `/ship`（内部凭据）、`/confirm`；`@Scheduled` 超时关单自动关单并释放冻结（**可重入**）；`/internal/sweep` 手动触发 |
| **M5-7 支付幂等** | `PaymentEntity`；**三层防线**（先查 → 订单状态 → `uk_pay_channel_idem`）；**先落支付单再扣库存**；成功后台账置 `DEDUCTED` |
| **M5-8 C2C 担保交易（资金托管与放款）** | `SettlementEntity` / CAS 状态迁移 / `SettlementService`；支付成功**资金进平台托管** → 确认收货**放款给卖家** → 退款**退回买家**；平台佣金按基点计算并落库留档；订单新增终态 `REFUNDED`；**退货入库**与 `RESTOCK_PENDING → RESTOCKED` 台账补偿；**发货满 N 天自动确认收货并放款**；**自测 125 通过 / 0 失败** |
| **M8-1 沙箱支付渠道** | `PaymentChannel` 抽象（预下单 / 回调验签 / 查询）+ 注册表；`MOCK`（即时）与 `SANDBOX`（扫码）两实现；`pay()` 改预下单语义，返回二维码内容（指向网关收银台）与过期时间；回调入口 HMAC-SHA256 验签 + 时间戳窗口 + **金额核对** + 幂等 CAS 入账；沙箱收银台页面；支付单 `PENDING → SUCCESS/EXPIRED/CLOSED` 生命周期；订单关单时**作废未支付支付单**（接通此前缺失的 `markClosedByOrder`，堵住「订单已取消、二维码还能扫出钱」）；**自测 62 通过 / 0 失败，六套合计 475 项全绿** |
| **M6 买家端 Web 商城（P1-c）** | 新建 `frontend/`（React 18 + TS strict + Vite 5）；12 路由 / 10 页主交易闭环；API 层（401 单飞刷新 + 5xx 退避重试 + 错误映射）；**零依赖二维码组件**（Byte 模式 + Reed-Solomon + 8 掩码择优）；PC 端扫码支付 + 3 秒轮询；`tsc --noEmit` 零错误、`vite build` 通过；curl 全链冒烟 + **真实 Chromium 逐页验证 12 路由**（含真实 UI 填单下单 → 模拟扫码 → 轮询自动变已付款 → 托管金额核对 ¥59.00/¥0.59/¥58.41），截图 13 张见 `frontend/docs/screenshots/` |
| **M5-9 卖家中心（后端）** | 卖家账号体系（`applyMerchant` 实名前置 + 幂等）；`/api/seller/**` 6 个接口（概览 / 订单列表 / 订单详情 / 发货 / 售后退款 / 结算对账）；**归属校验取代内部凭据**（越权 403 / 不存在 404 刻意区分）；运营可见平台自营 `sellerId=0`；物流信息落订单表并回显；**自测 84 通过 / 0 失败** |
| **M9 商家端 Web（前端）** | `frontend/src/pages/seller/` 4 页（Dashboard / Orders / OrderDetail / Settlements）+ `shared.tsx`；`sellerApi` 端点层；`RequireSeller` 路由守卫（只校验登录，真正的权限边界仍在后端 403）；Layout 与「我的」页按角色显示入口；`tsc --noEmit` 零错误、`vite build` 通过（60 模块）；**真实浏览器 UI 自测 29 项全绿**（截图见 `frontend/docs/screenshots/sel_*.png`） |
| **M10 平台管理后台** | 后端：`/api/admin/**` 13 个接口 —— 运营报表（GMV/订单量/退款率/转化率）、资金对账（托管汇总 + 异常挂账 LEFT JOIN 排查）、类目/品牌增改删+启停用（后台列表含停用项，公开接口仍只暴露 ACTIVE）、闲置审核（通过/驳回留痕：原因+时间，公开只可见 APPROVED）、订单干预（全平台列表/详情 + 仅待付款可强关，冻结释放、支付单作废、状态日志记 OPERATOR）；`OperatorGuard`（user-common，`isOperator()` 与登录态同口径）；网关 `/api/admin/**` 路由（不进白名单）；`t_listing` 补 `audit_reason` / `audit_at` 追溯列。前端：`pages/admin/` 5 页（运营概览含对账 / 内容审核 / 类目 / 品牌 / 订单干预）+ `AdminTabs` + `RequireOperator`；Layout 按角色显示「管理后台」入口。**自测 `scripts/admin-smoke.ps1` 49 通过 / 0 失败**（覆盖 401/403 边界、审核留痕、强关状态机拒绝、夹具自建自毁）；`tsc` 零错误、`vite build` 通过 |
| **M11 / P2-1 评价与晒单（履约域）** | **新建 `duoduo-fulfilment`（8084）** 服务：POM 入父反应堆、三档配置、雪花 worker-id=4、compose 第 8 个容器、网关路由 `/api/reviews/**`（**只放行只读 `/product/**`**）。评价接口 7 个：商品评价分页（星级/有图过滤）、评分汇总（平均分/星级分布/好评率/带图/追评计数，SQL 聚合）、提交评价（一单可多件商品）、追评（一次 + 窗口期）、我的评价、按订单查我的评价、已评价订单集合（订单列表页批量判断）。**规则**：只有本人**已完成**订单可评（订单事实由交易域内部接口提供，**不含收货人信息**）；`spuId` 必须在该订单明细中；唯一索引 `(order_id,user_id,spu_id)` 兜底一单一商品一评（并发撞键转 409）；图片只收 http/https（挡 XSS scheme）；匿名只影响展示、真实 user_id 保留。**用户域**新增内部只读接口 `/api/users/internal/briefs`（批量昵称头像，凭据保护）+ 新建 `InternalTokenFilter`。**交易域**新增 `/api/orders/internal/{orderNo}/fact`（裁剪字段的订单事实）。前端：商品详情页评价区（汇总 + 筛选 + 分页 + 匿名脱敏）、`/reviews/new` 按订单逐件打分（已评过的不再出现）、`/reviews/mine` 含追评弹窗、订单列表「去评价 / 已评价」批量判断。**自测 `scripts/review-smoke.ps1` 59 通过 / 0 失败** + **`scripts/e2e/review-ui-check.mjs` 真实 Chromium 20 通过 / 0 失败**（截图 `rev_*.png`） |
| **A1 · 历史订单托管单回补（I-14）** | **新增 `duoduo-trade` 的 `POST /api/admin/settlements/backfill?dryRun=`**（运营，幂等）：扫描「已有资金事实、却没有账本」的订单，按订单状态推断资金状态（`PAID`/`SHIPPED`→`CUSTODY`、`COMPLETED`→`RELEASED`、`REFUNDED`→`REFUNDED`），金额取 `pay_amount`，放款时间取订单**真实的 `finished_at`**；`?dryRun=true` 只出计划一行不写库；幂等靠 `uk_settle_order`；只碰 `s.id IS NULL` 的订单、不给 `PENDING_PAYMENT`/`CLOSED` 补账；逐条独立提交。**实测效果**：回补前 3 笔 `COMPLETED` 挂账 → 回补后 `abnormal` 返回空、退款单缺口同样清零。**自测 `scripts/backfill-smoke.ps1` 88 通过 / 0 失败**；回归 `settlement-smoke 125/0`、`payment-smoke 71/0`、`admin-smoke 49/0` |
| **A3 · 售后仲裁工单** | **新建 `t_after_sale`（履约库）+ 8 个端点**：买家立案（仅本人订单、仅已发货/已完成）、我的售后、店铺售后、详情、卖家处理（AGREE 即退款 / REJECT 必填说明）、买家申请平台仲裁、运营全平台列表、运营裁定（APPROVE_REFUND 会真的退款 / REJECT，**说明必填**）。**钱不在这张表里**：裁定通过调交易域既有的运营侧退款入口，退款金额回填；**先让钱动再改状态**；状态迁移全部 CAS 条件更新；唯一键 `(order_id, type)`。**B-01 边界**：已完成订单（钱已放款）裁定退款被交易域拒 422 并提示走追偿，售后单保持原状态。**重构**：评价域私有的「订单号哈希」与「图片校验/JSON 读写」抽到 `support/OrderRefs` + `support/ImageUrls` 共用。**自测 `scripts/aftersale-smoke.ps1` 101 通过 / 0 失败**；回归 `review-smoke 59/0`、`settlement-smoke 125/0` |
| **A3 配套 · 售后三端页面（第十轮）** | **买家 3 页**：`/after-sales`（我的售后 + 状态页签）、`/after-sales/new?orderNo=`（类型 + 诉求 + 举证图）、`/after-sales/:id`（步骤条 + 卖家说明 + 裁定说明 + 退款金额 + 申请平台仲裁）；入口挂在**订单**（仅已发货/已完成）与「我的」页。**卖家 1 页** `/seller/after-sales`（同意即退款 / 拒绝必填原因）。**运营 1 页** `/admin/after-sales`（裁定，说明必填；弹窗默认结论为「驳回」——误点不会把钱退出去）。**契约修正**：售后单 `id` 改为**字符串**传输（18 位雪花超过 JS 安全整数上限，`JSON.parse` 会静默改写）。**共用件** `pages/afterSaleShared.tsx`（状态页签 / 类型文案 / 配色 / 步骤条）。**自测 `scripts/e2e/aftersale-ui-check.mjs` 真实 Chromium 44 通过 / 0 失败**（买家→卖家→运营整条链，末了回接口层确认订单与托管同步 `REFUNDED`、退款金额等于实付）；`tsc` 零错误、`vite build` 通过；回归 `aftersale-smoke 101/0`、`review-smoke 59/0`、`settlement-smoke 125/0`。**已无遗留** |
| **A2 · 物流轨迹（第十一轮）** | **履约域新表 `t_logistics_track`**（订单号 + 节点码 + 发生时间 + 描述 + 留档报文；唯一键 `uk_track_order_node_time` 兜幂等，覆盖索引 `(order_no, occurred_at)` 服务正序查询；节点码白名单 `LogisticsNode` 7 种）。**两个端点**：`POST /api/logistics/internal/{orderNo}/tracks`（批量摄入，`X-Internal-Token`，**履约域第一个内部接口**，本域自建 `InternalTokenFilter`；批量全有或全无）+ `GET /api/logistics/orders/{orderNo}/tracks`（买家本人 / 该单卖家 / 运营，按发生时间正序；**无轨迹回空列表而非 404**）。**交易域发货后补推「已揽收」**：`OrderShippedEvent` + `@TransactionalEventListener(AFTER_COMMIT)` + `LogisticsNotifier`（best-effort，**绝不抛出**，不阻断发货）；两条发货路径（卖家中心 / 内部凭据）共用同一段状态机，事件在状态机本体发布，两边都不漏。**前端共用件** `components/LogisticsTimeline.tsx`：正序排列 + 头部「当前：××」常驻提示；未发货给说明性空态；已完成订单轨迹仍在；非当事人在订单页本就被挡，不泄露物流信息；买家 / 卖家订单详情各挂一份。**顺带收口**：履约域新增 `OrderFacts` 统一取数口（评价域与售后域各自那份「调交易域取订单事实」已悄悄漂移——同一句「交易域不可用」一处抛 422、一处抛 503——统一为同一实现）。**自测 `scripts/logistics-smoke.ps1` 79 通过 / 0 失败**（含★**断言总数守卫**：任何一条 Check 因写法错误被静默跳过都会让守卫报失败）+ **`scripts/e2e/logistics-ui-check.mjs` 真实 Chromium 44 通过 / 0 失败**（买家/卖家同轨 + 空态 + 越权 + 终态仍在）；`tsc` 零错误、`vite build` 通过（主包 171.5KB） |
| **A5 · 优惠券营销（第十三轮）** | **新建第 6 个业务服务 `duoduo-marketing`（:8085）**：POM 入父反应堆、三档配置、雪花 **worker-id=5**、compose 第 **9** 个容器（服务名 `duoduo-marketing-app` / 容器名 `duoduo-app-marketing` / 镜像 `duoduo/duoduo-marketing:1.0.0`）、新库 `duoduo_marketing`（`t_coupon_template` + `t_coupon`）。**公开只读** `GET /api/coupons/templates`（领券中心，含 `claimable` / `claimableReason`，**能否领由服务端判定**）；**需登录** `POST /api/coupons/templates/{id}/claim`（**重复领取不报错**，返回 `replay=true` 与已有券码）、`GET /api/coupons/mine?status=`、`GET /api/coupons/mine/usable?amount=`（服务端给 `bestCouponCode`）；**运营** `POST /api/coupons/admin/templates`（`OperatorGuard`）；**内部**（`X-Internal-Token`，**不进网关**，交易域直连 8085）`/api/coupons/internal/{lock,release,consume,sweep}`。**三条纪律**：① 券码用**随机串**（`CP` + 14 位去混淆字符，字符表去 `0/O/1/I`）而**不用雪花**（规避 JS `Number.MAX_SAFE_INTEGER` 精度坑，同 INV-16）；② 锁 / 解 / 核销**全部 CAS 条件更新、不用分布式锁**（INV-1）；③ **营销域只算「能抵多少」，折扣金额由交易域写入 `t_order.discount_amount`**（被调用方、**不依赖 openfeign**）。**交易域接入**：下单 `placeOrder` 先锁券（失败抛 422 带人话原因）→ `paySuccess` 核销（核销失败则整笔支付不成立）→ `cancel` / `forceClose` / 超时关单**三处**解锁；`t_order` 增列 `coupon_code VARCHAR(32)`（增量脚本 `docs/sql/incremental/06-a5-coupon-columns.sql`）。**网关白名单收紧为精确路径 `/api/coupons/templates`**（白名单按**路径**而非方法匹配，放通配会把领券 / 券包 / 内部接口一起放成匿名可调，与既有 `/api/reviews/product/**` 同一纪律）。**前端**：新增 `pages/Coupons.tsx`（领券中心 + 我的券包双页签）、路由 `/coupons`（**刻意不套 `RequireAuth`**）；一级导航 + 用户下拉 + 「我的」页加快捷入口；结算页选券（`undefined`=未选择→用服务端推荐 / `null`=明确不用，提交只传 `couponCode`）、订单详情金额区展示优惠与券码。**自测 `scripts/coupon-smoke.ps1` 145 通过 / 0 失败**（含★断言总数守卫）+ **`scripts/e2e/coupon-ui-check.mjs` 真实 Chromium 43 通过 / 0 失败**（配套夹具 `scripts/e2e/coupon-ui-fixture.ps1 -Action create\|drop`）；`tsc --noEmit` 零错误、`vite build` 通过（**79 模块**） |
| **A8 · 收货地址簿 + T-39 精度根治（第十四轮）** | **用户域新表 `t_user_address`**（`docs/sql/incremental/07-a8-user-address.sql`；**6 个用户端点**：列表（默认排最前）/ 新增 / 修改 / 删除 / 设为默认 / 取默认 + **内部快照** `GET /api/addresses/internal/snapshot`（`X-Internal-Token` + 本域 `InternalTokenFilter` 失败关闭、**不进网关**））。**四条业务规则**：① 首个地址自动成为默认；② 设默认在**事务内**先清旧默认再置新，库内恒有且仅有 1 条默认；③ **删默认自动顺延**并 toast 告知；④ `fullAddress` 拼接**只有一处定义**（`AddressService.fullAddress()`，直辖市省=市只留一次），内部快照与地址簿接口**逐字相同**（冒烟锁死）。**归属校验统一 404**（改/删/设默认他人地址与"id 不存在"**同码同文案**，刻意不区分"不存在"与"无权限"以免用状态码差异枚举他人地址 ID —— 这是对 `docs/agent/07` 原文「403」的**有意偏离**）。**交易域接入**：下单新增可选 `addressId`，传了就**只认它**（直填三字段被忽略，避免两套来源冲突）；收货信息在**下单瞬间写快照**（`t_order.receiver_*`），此后改/删地址簿**不影响历史订单**。**★ T-39 雪花 ID 精度根治**：18~19 位雪花 id 超出 JS `Number.MAX_SAFE_INTEGER`（16 位），以 JSON number 下发会被浏览器 `JSON.parse` **静默四舍五入**（`Number('...704') === '...700'`，不报错）→ 前端拼 URL → 服务端查不到 → **404**；**接口冒烟照不出来**（.NET 用 Int64/Decimal 解 JSON 不丢精度），**A3 曾局部踩过一次、A8 又踩一次**。方案：`duoduo-common-web` 新增 **`LongToStringJacksonModule`**（随 `WebAutoConfiguration` 自动装配到全部业务服务）—— `Long`/`long`/`BigInteger` 默认转字符串，**字段名命中 `NUMERIC_LONG_FIELDS` 白名单（金额/计数/epoch 毫秒时间戳）的保持数字**；**判据是字段语义不是 Java 类型**（全站金额字段 **63 处都是 `Long`**，一刀切会让前端 `price / 100` 算出 `NaN`、页面金额全打成「¥NaN」）。**漏标方向安全**：新加 id 字段无需任何操作即受保护。前端 `types.ts` **38 个 id 类字段** + `endpoints.ts` **25 处形参**改 `string`，13 个页面逐个修到 `tsc --noEmit` 零错误；纪律写进 `types.ts` 顶部（id 只拼 URL 与相等比较，**绝不做数值运算**）。**顺带修掉一个真缺陷**：`Checkout.tsx` 初始化守卫写成 `addressList === null`，而本仓 `useAsync` 初始 `data` 是 **`undefined`** → 首次渲染即锁定"空地址簿"分支、把用户推进手动填写模式（地址渲染出来了却一个都选不上）→ 改为 **`addrLoading` 门控 + 判 `undefined`**（见 T-42）。**自测**：`scripts/address-smoke.ps1` **82/0**（含★断言总数守卫）+ `scripts/e2e/address-ui-check.mjs` **64/0**（真实 Chromium，16 段）+ **`scripts/id-precision-smoke.ps1`（新增）56/0**（跨四域，**直接断言 JSON 原始文本**绕开 `ConvertFrom-Json` 的精度掩盖）+ `scripts/run-all-smoke.ps1`（新增，一键全套并汇总）；**全套 15 套 1286 项全绿**，连带修 2 处因 T-39 而失效的旧断言（`review-smoke` 抠主键正则要允许引号；`order-smoke` 的「缺收货人」用例补"先加购"前置，否则会撞上「购物车为空」的**同码不同因**假绿灯）。踩坑登记 **T-39 / T-40 / T-41 / T-42** |
| **R57 · AI 导购必修 6 项（约束硬过滤 / 超时隔离 / 限流加固 / 输出护栏 / 澄清与出口 / 测试真绿）**（2026-09-29） | 用户指令「仅实现 6 项必修」。**交付**：① **M1** `ConstraintParser`（规则解析预算上下界/品类，三状态：解析到/没提/**提了没听懂必须告知**）+ `CategoryCatalog` 别名表（库里叫「宠物主粮」、用户说「猫粮」，子串匹配必败）→ 商品域 `internalCandidates` **SQL 过滤**（`min_price<=budgetMax`、`max_price>=budgetMin` 重叠语义、`category_id IN`）；② **M2** 召回 Feign 独立超时 300ms/1500ms（`AiFeignConfig` 刻意不加 `@Configuration`）；③ **M3** 网关**先剥离客户端伪造的 `X-Forwarded-For`/`X-Real-IP` 再写 TCP 对端地址**（判据：凡被下游当事实用的头，上游必须保证只能由自己写入）+ 限流键改 userId/可信 IP + **全站总配额 300/min**（两道判断都计数）；④ **M4** `ReplyGuard`（绝对化词表 + 价格必须能在候选找到；命中**换事实版回复而非拒绝**——防 over-refusal；micrometer 可选注入不阻断启动）；⑤ **M5** 出口预判（物流/退换/优惠/支付五类「答不了」不调模型给入口）+ 澄清（关键信息全缺+没澄清过+召回空或≤6字 → 反问一次，`ClarifyView` 选项 label/send 分离，前端 chips 可点）；⑥ **M6** 预算断言改**无条件必查**（旧版挂「仅 llm 模式」而 CI 跑 mock → 长期空转假绿）+ 极小预算空结果探针（证明 SQL 过滤真生效）+ 护栏/解析 JUnit 单测 + 伪造 XFF 探针 + 前端澄清单测。**★ 单测当场抓到两个真缺陷**：「预算 200 以内」被 MAX/MIN 两套正则**同位重匹配**成 min=max=200（99~159 元便宜货被 `max_price>=200` 误杀，且无任何报错）——修法：MIN 匹配跳过与 MAX 命中区间的重叠；「最便宜的是哪个？」被当成预算未解析（信号词「便宜」加负向后视 `(?<!最)`）。**★ 部署教训**：改完只 `compile` 不 `package` → 旧 jar 进镜像、容器行为照旧（冒烟 5 项失败全是「新逻辑没生效」）——`compose build` 的 COPY 层缓存命中，**必须重新 `package` 并解包验证 jar 内容**（R52 同款教训二次踩坑）。**自测 89 项新增**（ai 单测 29 + r57-ai-clarify-test 23 + ai-smoke 37）+ **回归 346 项全绿**（order 77 / address 82 / payment 71 / share-circle 74 / notice 42）+ 静态自检 0 错 0 警 |
| **R56 · 分享圈补全（评论 / 关注流 / 删除编辑 / 举报+运营台 / 话题）**（2026-09-29） | 用户要求「完善分享圈功能」，四项全做。**实测缺口**：全仓搜 `comment` **零命中** —— 评论链完全不存在；发布后无法收回（无删除 / 编辑）；无举报；无话题。**交付**：① **评论 + 回复 + 评论点赞**（`t_share_comment` 二级 + `t_share_comment_like`；`comment_count` **一级与回复都算**；**★ 评论列表用查询参数 `GET /api/shares/comments?shareId=` 而不是 `/{id}/comments`** —— 后者要放行就得在白名单加 `/api/shares/*/comments`，那会**连带把「发表评论」放成匿名可调**：路径形状本身就是安全边界）；② **关注流**（social 新增内部端点 `GET /api/socials/internal/following`（凭据 + 只返 id + 服务端再夹上限 500）→ fulfilment 新增 `SocialClient`（`contextId` 唯一）→ `feed?scope=FOLLOWING`；**未关注任何人返回空，绝不退化成全站内容**；社交域不可用**显式报错**而非静默空列表 —— 空列表会让用户去检查自己的关注列表然后找不到问题）；③ **删除 / 编辑 + 举报**（**软删** `deleted_at`：转发单引用块靠回查原帖才能显示，物理删会变空白；编辑只改文字与话题，`topics` **三态** null = 不改 / [] = 清空 / 数组 = 替换；举报 `t_share_report` 把 SHARE 与 COMMENT 合成一张表 + 唯一键兜并发重复；**运营处理台** `/admin/share-reports` 为后台第 8 页签：列表**直接显示被举报内容摘要**、弹窗**默认结论为「举报不成立」**（误点不会删内容）、裁定成立才软删并回退话题 / 评论计数）；④ **话题 `#`**（`t_share_topic` + `t_share_topic_rel`；最多 2 个；服务端规范化是权威（剥 `#` / 去重 / 限长 / 非法丢弃）；**超上限截断而非报错** → DTO 刻意不加 `@Size`；「查不到就建」用 **INSERT 撞唯一键再回查**；聚合页用 **JOIN 一条 SQL** 而不是「先查 id 再 selectBatchIds」）。**★ 三个真缺陷（探针 / 单测当场抓到）**：`commentCount` / `replyCount` / `shareCount` 漏进 `NUMERIC_LONG_FIELDS` → 以**字符串**下发、「按次数排序」退化成字典序；`@Size(max = 2)` 与「截断」设计冲突，致服务层截断逻辑**永不执行**；评论创建把 briefs 传成空表，致回执昵称是「用户3612…」长号。**自测 172 项**（`r56-share-circle-smoke` 74 + `r56-share-comment-test` 74 + `e2e/share-report-ui-check` 24 真实 Chromium）+ **回归 614 项全过**（接口探针 385 + 前端单测 229）+ 静态自检 0 错 0 警（**并修掉一处误报**：`wx:for-item` 别名改为从 WXML 实际读取，含两轮反向验证）+ 前端 `tsc` 0 错 / `vite build` 84 模块 + 9 容器 healthy（**改了共用模块 → 8 个业务镜像全部重打**并解包校验产物含新白名单字段） |
| **R55 · 通知体系补全（生产者 + 合并未读 + 角标 + 首页弹窗）**（2026-09-29） | 用户诉求：「收到他人消息显示带数字的未读图标 + 主页小卡片式弹窗」。**实测缺口**：① 通知**没有任何业务生产者**（`t_social_notice` 仅 3 条种子公告；`internal/notices` 全仓搜零命中；`MessageService` 搜 notice 零命中）→ 页面能打开、**永远没有新通知**；② 角标**只在通知页刷新**（全仓 `setTabBarBadge` 唯一调用点就是 `notification.js`）。**交付**：social 新增 `POST /api/socials/internal/notices`（批量推送，凭据保护，**两阶段落库：第 N 条非法则整批不写**）+ `/announce`（全站公告，收件人服务端固定为 0）、`GET /api/socials/notices/unread-summary`（**合并未读，角标唯一数据源**）、`GET /api/socials/messages/unread-count`；`NoticeService` 新增 `pushBatch/announce/pushMessageNotice`，`support/MiniProgramPages` **硬校验 `jump_page`** 必须落在与 `app.json` 一一对应的 28 个真实页面内（拼错路径编译与日志都不报错，只表现为「点了没反应」）；trade 侧 4 处（下单 / 付款 / 发货 / 退款）与 fulfilment 侧 7 处（售后 6 + 物流签收 1）经 Feign 推 ORDER 通知（**`contextId` 必须唯一** + `@TransactionalEventListener(AFTER_COMMIT)` + **best-effort 只 warn**；签收判据是「本次真写入」）。小程序新增 `utils/unread.js`（**角标唯一出口**：60s 轮询 / 并发单飞 / 失败返 `-1` 不清角标 / `>99 → '99+'`）、`utils/notice.js`、`components/notice-toast`（顶部滑入、4s 自动收起、同来源 5min 冷却），刷新点 4 类（`app.onShow` + 首页/分享圈/我的三 tab `onShow` + 轮询，`onHide` 清定时器），通知页加**分类 chip**（6 项）与**触底分页**，顺带修 `api.ai.capabilities` **死代码**。**★ 本轮最有价值的产出是实测发现的真实设计缺陷**：私信到达会另写一条 `bizType=MESSAGE` 通知行当「有人找你」的入口，而同一批私信**又按条计入私信未读** → **一条消息被数两次**（角标 7 实际只有 5）；修法是从通知未读里**排除该行**（合并口径固定为「通知（不含私信）+ 私信未读」），并顺带发现 SQL `<> 'MESSAGE'` 对 `biz_type IS NULL` 返回 NULL（**而全站公告的 `biz_type` 恰是 NULL**）→ 必须写成 `IS NULL OR <>`。**自测 101 项**（`r55-notice-smoke` 42 + `r55-unread-badge-test` 59）+ **回归 342 项全过**（r50 18 · r49 15 · r53-hotfix-chat 17 · r54 26 · share-circle 39 · social 43 · r52 57 · r53 28 · r53b 26 · api-id-payload 19 · short-time 12 · flea-mine-filter 42）+ 静态自检 0 错 0 警（新增**第 8 条规则**：角标必须走统一 `utils/unread.js`）+ 三容器（social / trade / fulfilment）healthy |
| **R54 · 成色胶囊框 / 冗余通知入口 / 浏览与想要两口径**（2026-09-29） | 用户真机验收三修：① 成色筛选胶囊背景框过大 → `.fm-chip` **显式定高**（`height:48rpx` + `line-height:1` + 半径=半高）+ `.fm-chips` `align-items:center`（**`scroll-view` 内 flex 子项默认按容器高拉伸**是根因）；② 导航栏 💬 与 tabBar 通知页重复 → 删 `nav-bar` 图标与 `goNotice()`，**tabBar 入口保留**；③ 「浏览」与「想要」拆分 → 新 `t_listing.want_count` + **去重表 `t_listing_want(listing_id,buyer_id)`（主键即去重键）** + `INSERT IGNORE` **只在返回 1 时 +1**（应用层「先查再写」并发双写）+ 新 `POST /api/listings/internal/{id}/want`（**写操作刻意用 POST**）+ social 侧 best-effort 上报（**异常只 warn**）+ 三页文案「N 次浏览 / N 人想要」+ 详情页 `enablePullDownRefresh`。**★ 教训：静态规则假阴性比不检查更危险**（正则匹配到注释里的同名声明 → 须 `stripCssComments()` 先剥注释再匹配）。**自测**：`r54-listing-want-smoke` 26 · `r54-miniprogram-copy-test` 23 · 回归 `r53b` 26 · `r53` 28 · `r52` 57 · `r53-hotfix-chat` 17 · `api-id-payload` 19 · `flea-mine-filter` 42 · `short-time` 12 · 静态自检 0 错 0 警 · 双容器 healthy。**真机验收**：用户 2026-09-29 在微信开发者工具点过一遍（跳蚤市场 + 详情页新功能 + 近两轮修复），**确认无问题、本轮收尾** |
| **R53 系列 · 闲置详情卖家卡 / 审核态体系 / 聊天热修 / 本轮三修**（2026-09-28~29） | **第五十三轮主交付**：详情页卖家卡 + LISTING 问一问（`ConvContext` 三态会话键、**Feign 同 name 必须配 `contextId`**）+ 详情购买过渡态 + 我的闲置审核态体系（驳回→修改→重提闭环，新增 `mine/{id}` 与 `resubmit`）。**R53 热修**：聊天 404（前端 `Number()` 强转雪花 id 丢位）+ 消息时间差 8 小时（`shortTime` 删时区标记后裸切片）→ 新增三道防线（静态规则 6 + `api-id-payload-test.js` + `short-time-test.js`）。**R53 热修二**：按钮文字折行（`nowrap` + `flex:none` 定为全局约定）、浏览量恒 0（`ListingMapper` 数据库侧原子自增 + `detail()` 回填本次响应）、闲置状态重复显示（`FILTER_PARAMS` 互斥划分 + 单一状态徽章）→ 新增静态规则 7 + `flea-mine-filter-test.js` + `r53b-listing-view-status-smoke.py`。**累计自测全绿**：`r53` 28 · `r53-hotfix-chat` 17 · `r53b` 26 · `r52` 57 · `api-id-payload` 19 · `short-time` 12 · `flea-mine-filter` 42 · 静态自检 0 错 0 警 |
| **E28 · 审核 SLA 落地：默认放行 + 事后抽查**（2026-10-09） | 入驻与商品审核由**强制前置人工**改为**默认自动放行 + 按规则事后抽查**。数据面：`t_spu.audit_source`、`t_merchant.audit_source` + `violation_count`、两库各持 `t_audit_sample`(`uk_biz_date` 幂等 / `sample_date` 必须 DATE) 与单行 `t_audit_sample_rate`、用户库**补建 `t_sched_leader`**（此前缺表 → E15 选主一直静默失效）。规则：`AuditSlaPolicy`（`duoduo-common` 纯函数）= 基础 5% / 每日 3~20 / 不足 3 全查 / 必抽三类（新商家头 3 件、恢复营业 7 天内、有污点加重 20%）/ 连续 3 天违规率 **> 10%** 翻倍封顶 40%；取样 `hash(bizId+epochDay)`（可复现、不可卡时间规避、比例提高时集合成**包含关系**）。处置：复核**不可重入**；判合规**不改前台可见性**；判违规 → 商品 `REJECTED` + 强制下架 / 店铺条件更新停业 + 联动下架；`violation_count` **DB 原子自增且只增不减**，唯一重置点是复业。后台第 10 个工作台「抽查复核」（两域 + 四状态 + 六项指标 + 判违规必填依据）。**★ 堵两个绕过**：自动放行**必须写 `audit_at`**（否则抽查永久静默空转：接口 200、`created=0`、无日志）、**曾判违规者改一处即自动放行**（`applyContentReaudit` 加 `wasRejected` 短路；入驻侧 `submit()`/`renewal()` 加 `hasEnforcementRecord`，否则点一次续期即绕过停业处罚）。**★ 另抓到一次假绿验证**：只比对容器/宿主 jar md5（两边都是旧包）→ 必须**拆包看内容**。**验证**：`mvn -o test` **458/0**（新增 69）+ `audit-sla-smoke.ps1` **54/0** + `e2e/audit-sla-ui-check.mjs` **56/0** + 三端构建与产物校验 + 容器内代码拆包核验 |
| **E18 · 后台操作审计日志**（2026-10-10） | **一次后台写操作 = 一条不可删改的记录**。用「`HandlerInterceptor` + `@Audited` 注解」而不是 AOP（项目无 `spring-boot-starter-aop`，加同族拦截器零新依赖，且**能拿到响应状态码**）。`duoduo-common`：`AuditAction`（39 动作，**枚举名即落库值**，历史 9 个逐字不变）/ `AuditCategory`（**SECURITY·FUNDS 不可删改**，解析失败折叠到最严档）/ `AuditOutcome` / `AuditLogRecord`（**按码点**截断）/ `AuditTrail`（ThreadLocal 上下文 + 唯一抑制口 `skip()`）/ `AuditSink` / `@Audited`；`duoduo-common-web`：`AuditInterceptor`；`duoduo-user`：`AuditLogService`（唯一写入口 + 检索）/ `AdminAuditLogController` / `AuditLogInternalController` / `LocalAuditSink`；三域各一 `AuditLogClient`（`contextId` 区分）。**★ 四条铁律**：① 审计拦截器**排在权限拦截器之前**（Spring 只为 `preHandle` 成功的拦截器回调 `afterCompletion`，反了"越权被拒"就不留痕）；② **失败判定必须同时看响应状态码**（业务异常被全局处理器吃掉后 `ex==null`，只看 ex 会把"被拒"记成成功）；③ 时间戳解析失败**用接收时刻兜底**，不因一个格式错误丢整条记录；④ **审计是旁路**，写入失败只打 ERROR、绝不外抛，跨域转发失败**降级写本域日志**。DDL `incremental/31-e18-audit-log.sql`（14 列 + 4 索引，**存量 188 行全落 `OTHER/SUCCESS/0`**）。前端后台「操作审计」页 `/admin/operation-audits`（**只读、无操作列**；不可删改**由服务端下发布尔值**）。**★ 修掉 3 个真实/潜在缺陷**：`AuditLogService.search` 的 `.eq(hasText(x),col,x.trim())` 让**打开审计页的默认查询必炸 NPE 500**（Java 会先求值所有实参）；冒烟 `@($null).Count==1` **假通过**；`docker compose build` 不跑 Maven → **旧 jar 打新镜像**。**验证**：单测 **579/0**（新增 72）+ **4 组反向验证恰好红对应条数** + `audit-log-smoke.ps1` **58/58**（5 静态 + 10 组端到端，含 **37/37 写端点注解覆盖**）+ 三站点深链 200 |
| **E19 · 权限越权回归矩阵**（2026-10-10） | **一条命令回答「现在到底防住了哪些越权」**。此前这些断言散在 seller / order / audit-sla / audit-log 四套件里，没有单一出口 —— 想拼出一张表得翻四份日志。新增 `scripts/privilege-matrix-smoke.ps1`（**79 条断言 / 10 节 / 8 个模块**，被 `run-all-smoke.ps1` 与 `check-all.ps1` **按文件名通配自动收录** = 纳入一键自检）。**★ 三条断言纪律**：① **每条「越权被拒」都必须配一条「有权者能过」的正对照**（只有反例的矩阵在"整条链路坏掉、所有人都 403"时照样全绿，那时它测的不是权限而是服务还活着没有）；② **网关层与服务端层必须分别断言**（E16 的核心结论：只断言网关态时入口会**先**返 403，注解没注册 / 漏写照样绿 → 所以 §4 一律**直连 8081/8082/8083** 并自己写 `X-User-*` 头）；③ **状态码必须写具体值**，写 `-ne 200` 会把 400 / 401 / 500 一并算成"越权被正确拒绝"。**★ 最强形式**：同一个 `userId`（真实店主），只把角色在 `X-User-Role` 里改成 USER → 403，差异因此**只可能来自角色判定**。**8 个模块**：收货地址横向（404 刻意不区分无权限与不存在）/ 子账号**纵向**（店员做店主动作 403、跨店 404、平台运营走统管端点）/ **后台入口边界** / **服务端判权（直连）** / 商品横向 + **E28 审核双路**（干净内容 `AUTO` 自动放行且**必须写 `audit_at`** vs 标题含联系方式转人工 `PENDING`）/ 审核台纵向（**卖家不能自审自己的商品**）/ 订单横向 + 自营归属（`seller_id=0` 卖家看不到也发不了货）/ 审计日志纵向。**验证**：**79/79 全绿** + **反向验证精确命中**（临时注释掉 `SellerProductService.audit` 里的 `requireOperator()` → 只有那 2 条服务端直连断言变红，网关层那 2 条**依旧绿**，正是"只测网关会假通过"的活证据）+ 全量冒烟 **27 套** + 断言总数守卫 GUARD-OK |
| **E20 · 租户隔离硬化**（2026-10-10） | **从「谁在操作」走到「这些数据属于谁」**（E19 管接口级、E20 管行级）。**★ 先纠正口径**：`seller_id`/`tenant_id` 存的是**店铺主体 userId**（`t_user.id` = `t_merchant.owner_user_id`），**不是 `t_merchant.id`**（实测 8/8 命中 `t_user.id`、0/8 命中 `t_merchant.id`）；`SellerOwnership` 类注释「值即 `t_merchant.id`」**说反了**（代码本来是对的）—— 不纠正就会照注释加错维度。**带租户列 = 10 张物理表 / 9 个表名**（`t_audit_sample` 在 product 与 user 各一张、**同名列** → 「表名→列名」映射安全，但这条前提写进代码并被断言）。**四层落地**：① 口径层 `duoduo-common/tenant`（`TenantContext`＝`ThreadLocal`+对外只暴露 `Optional`，**0 是有效租户**＝自营、未设置＝不追加条件；`TenantTables`＝表→列唯一权威+表名归一；`TenantKeys`＝Redis 键统一前缀，**收口而非改名**）；② 传播层（访问令牌加 `tid`，**刷新令牌刻意不带**、刷新时**重新解析**；网关**先剥离客户端同名 `X-Tenant-Id` 再按 `tid` 写入**，`tid` 空时**不写头**而不是写 `0`；`TenantContextInterceptor` 排在 `UserContextInterceptor` 之后、`AuditInterceptor` 之前，`preHandle`/`afterCompletion` 两处清理）；③ 数据层（MP `TenantLineInnerInterceptor` 按服务**白名单接入**，**必须排在分页插件之前**，非法头 **400 响亮拒绝**）；④ 搜索层（ES 写 `sellerId` + 检索加租户过滤；**今天所有既有链路都不生效**，价值是让「将来接 ES」成为安全的默认 → 只做源码级静态断言，不假装它现在拦得住什么）。**★ 两条测试纪律**：验数据层**必须直连服务端口**（走网关时伪造头会被剥离，拦截器在不在根本测不到，E16 同坑）且探针要选**自身零过滤**的接口（`/api/products/internal/cards` 走 `selectBatchIds`）；**每条「被隔离」必配一条「未声明时可见」正对照**。**验证**：`tenant-isolation-smoke.ps1` **37/37**（GUARD-OK）+ `TenantContextTest` 9 项 + 全仓单测 **588/0** + 三处容器**拆包核验**到新类 |
| **E21 · 平台后台两步验证 + IP 白名单**（2026-10-10） | **解决的问题**：平台账号能审商品 / 关订单 / 改权限 / 看全站资金，只靠密码时一次撞库或一次钓鱼就足以拿到整个后台。<br>**★ 强制点放在登录链路而非网关**：已启用 2FA 的账号登录必须带动态码或恢复码，否则**拿不到任何令牌** —— 没有令牌网关本来就全拦，因此 `JwtAuthGlobalFilter` **不需要**再加"这枚令牌算不算数"的判断（判定点越少越不易分裂）。<br>**★ 刷新令牌带 `tfv` 声明 + 启用时吊销全部刷新令牌**（两道互补防线）—— 否则"启用 2FA 之前签发的刷新令牌"能把这道门绕开整整 7 天；此处**刻意不做向后兼容**（放行无声明的老令牌 = 2FA 形同虚设）。<br>**★ 微信登录对已启用 2FA 的账号直接拒绝**：那条通道没有"第二步"的输入位，放行即成后门。<br>**三个安全细节**：① 防重放 = 记录并**条件更新**已用时间步（先读后写会在并发下把同一枚码放行两次）；② 恢复码**只存哈希 + 用一次作废一枚 + 失败不消耗**（否则攻击者能用错误输入把码耗光）；③ 已启用时**拒绝重新 setup**（允许覆盖密钥等于给出一条降级路径）。<br>**IP 白名单（默认关）**：只收窄 `/api/admin/**`，只看 TCP 对端（不看 `X-Forwarded-For`），支持精确地址 / IPv4 CIDR / 前缀通配；**开关打开但名单为空 = 拒绝全部**（失败关闭）。刻意不用 `InetAddress.getByName`（对非 IP 字符串会做 DNS 解析）。<br>**★ 本轮挖出一个真 bug**：`AuthService.login` 在 2FA 校验后调用 `updateById(entity)` **整行回写**，用快照里的旧 `totp_last_step` 覆盖了新值 → **防重放静默失效**（除重放那一条外其余断言全部照常通过）；修法 = 只 set `lastLoginAt` / `updatedAt`。沉淀：**同一请求里既读同一行又改同一行，不能用整行回写**。<br>**验证**：`admin-2fa-smoke.ps1` **54/54** + 单测 **604/0**（TOTP 16 项含 RFC 6238 官方向量、网关白名单 6 项）+ 前端 `tsc` / 三端构建 / 两项静态检查全绿。 |

### 进行中

| 任务 | 当前进展 |
|---|---|
| ✅ **M11 剩余块：信用分（A4）已交付**（2026-09-20，第十二轮） | 履约域**四块已全部落地**：评价与晒单、售后仲裁（A3，含三端页面）、物流轨迹（A2，含买卖双方时间线）、**信用分（A4）** —— 同域内已无低成本可做项，下一项须新起服务或中间件 |
| ✅ **M11 剩余块：优惠券营销（A5）已交付**（2026-09-23，第十三轮） | **新增第 6 个业务服务 `duoduo-marketing`（:8085，雪花 worker-id=5）**：领券中心 / 券包 / 内部锁·解·核销 / TTL 补偿调度 + 前端领券中心与结算选券；容器 **8 → 9**（3 中间件 + 6 业务服务）。v1 边界：券不随退款退回、只实现满减、一张券只作用于拆单后商品总额最大的那一单 |
| M11 其他：XXL-Job（~~ES 检索（A10）~~ ✅ / ~~RocketMQ + Sentinel（A11）~~ ✅ / ~~压测（A12）~~ ✅） | A10/A11/A12 均已交付（第十七/十八/十九轮）；仅剩 XXL-Job（B-05） |
| **A9 AI 导购「多多」（已交付，仅剩凭据联调）** | ✅ **第十七轮已交付**：新建 `duoduo-ai`（:8086，容器 `duoduo-app-ai`，**无状态**——不连库、v1 不落对话历史）；`POST /api/duoduo/chat` + `GET /api/duoduo/capabilities`（网关白名单含这两条，**未登录可问答**）；Feign 调商品域 `GET /api/products/internal/candidates` 取**真实候选**（视图**不含 `sellerId`**），系统提示硬约束「只推荐候选内商品」；三形态 `mock`/`llm`/`unconfigured`，未配或超时 → **503 `DEGRADED`**，每 IP 限流 → **429 `RATE_LIMITED`**；前端 `/ai` 免登录对话页。**自测 `ai-smoke.ps1` 16/0**。**待用户提供**百炼 DashScope API Key（仅影响 `llm` 形态联调） |
| **A10 ES 检索（已交付）** | ✅ **第十七轮已交付**：商品域单索引 `duoduo-search`（`docType` ∈ PRODUCT/LISTING）；**拼音与同义词都在应用侧**（pinyin4j 2.5.1 + `SearchSynonyms.variants()`，**按 ES 实测不挂同义词分析器**；**拼音库必须选 Central 上存在的 —— T-59**）；`SearchSyncJob` 15s 增量兼补偿 + 启动全量对齐 + 清孤儿；`GET /api/admin/search/reconcile` / `POST /api/admin/search/reindex`（`OperatorGuard`）；ES 不可用**原地降级 MySQL**（`ObjectProvider` 可选注入 + 写操作前置 `ready()` 守卫 + **禁用 ES 健康指示器**以免健康门杀掉降级链路）；ES 只返回 id 页、卡片回库装配（权威数据只有库一份）。**自测 `search-smoke.ps1` 40/0**（需 ES） |
| ✅ **A7 运营账号管理页已交付**（2026-09-24，第十六轮） | 用户域两端点（`GET /api/admin/users` 脱敏分页列表 + `POST /api/admin/users/{id}/role` 仅 ADMIN 改角色）+ `t_audit_log` 审计落库 + 网关 `/api/admin/users/**` 路由 + 前端 `/admin/operators`（AdminTabs 第 7 页签）；三条硬规则：自己降权 422 / 最后一个 ADMIN 不可降 422 / 每次变更写审计；提权后需重新登录生效（notice 提示）。**自测 `admin-smoke.ps1` 49→72 项全绿（PS5.1）** |
| ✅ **A6 契约导出与 CI 已交付**（2026-09-23，第十五轮） | **OpenAPI 导出**：knife4j starter 挂 `duoduo-common-web`（5 业务服务自动获得，网关不受影响；`/v3/api-docs` 与 `/doc.html` 不进网关白名单）+ `scripts/export-openapi.ps1` → `docs/openapi/`（5 份 JSON + endpoints.md，**116 端点**与 04 篇核对一致）；**CI**：`.github/workflows/ci.yml` 双 job（编译 + Docker 全栈冒烟子集 smoke/trade/order/address/id-precision 共 **356 项**，失败即 job 失败 + 日志 artifact）+ `scripts/ci-run.ps1` 通用子集执行器（本地实跑 356/0 全绿） |
| ✅ **A13 小程序骨架已落地**（2026-09-23，第十五轮，测试号阶段） | 用户批准「代码先写」：`miniprogram/` 5 页面 + 请求封装，**零后端改动**跑通「浏览 → 加购 → 取默认地址下单 → 沙箱支付轮询」（登录暂用手机号回退）；**余下**：后端 `POST /api/auth/wechat`（code2Session，凭据走环境变量）+ openid 绑定 + 冒烟；真机预览（待 AppID） |
| ✅ **A13 后端登录打通（code2Session + 绑定）**（2026-09-23，第十五轮续） | `t_user` 加 `wx_openid` + **唯一键 `uk_user_wxopenid`**（增量脚本 `docs/sql/incremental/08-a13-wechat-bind.sql`）；新端点 `POST /api/auth/wechat`（免登，白名单只放行这一条）+ `POST /api/auth/wechat/bind`（登录态绑定）；`WechatGateway` 三分支（mock 显式开启 / real jscode2session / 未配置 503 DEGRADED）；未绑定 422、openid 属他人 409、重复绑定幂等 200、空 code 400；secret 只走环境变量、日志只记 openid 前 4 位。**自测：`scripts/wechat-auth-smoke.ps1` 26/26 全绿（GUARD-OK）+ CI 子集 356/0 无回归**。小程序侧待 `WECHAT_LOGIN_READY=true` 联调；真机预览待 AppID |
| ✅ **A7 运营账号管理页已交付**（2026-09-24，第十六轮） | 用户域两端点（`GET /api/admin/users` 脱敏分页列表 + `POST /api/admin/users/{id}/role` 仅 ADMIN 改角色）+ `t_audit_log` 审计落库 + 网关 `/api/admin/users/**` 路由 + 前端 `/admin/operators`（AdminTabs 第 7 页签）；三条硬规则：自己降权 422 / 最后一个 ADMIN 不可降 422 / 每次变更写审计；提权后需重新登录生效（notice 提示）。**自测 `admin-smoke.ps1` 49→72 项全绿（PS5.1）** |
| **A13 买家端微信小程序（收尾，待用户提供凭据）** | 用户 2026-09-18 拍板：**排期，允许先开始编码**；**小程序 AppID 暂未提供（待用户提供）** → 骨架 + 后端登录均已就绪，真机预览等 AppID |

### 未开始（按你确认的端形态排期）

| # | 任务 | 依赖 | 说明 |
|---|---|---|---|
| **R57** | **AI 必修 + 加强** | 无 | 用户已给出改进计划并要求「必修 + 建议加强」（不含流式输出）。**必修**：A1 结构化约束服务端硬过滤（`ChatService:72` 召回 `candidates(keyword,8)` **零过滤**，预算/物种只写在 prompt 里靠模型自律）→ 新增 `QueryConstraintParser`；A2 **CI 假绿**：`ai-smoke.ps1:176/179/181` 预算断言「仅 llm 形态有意义」→ **CI 无 Key 走 mock，该断言一直被 SKIP**，而 CI 是唯一跑全量的地方 → 改为**与形态无关的无条件断言**（本地实测是 llm：`DUODUO_AI_MOCK_ENABLED=false` + `qwen-max` + 真 Key，`mode:"llm"`）；A3 限流键取 XFF 首段（`AiController:73-79`）**可伪造绕过** + `IpRateLimiter` map 无淘汰；A4 prompt 注入面（UGC 商品标题直接拼进 system prompt）；A5 `DashScopeClient` 手拼 JSON 未转义、响应朴素解析；A6 召回 Feign 无独立超时且与生成共用 15s → 拆「召回 3s / 生成 12s」。**加强**：B1 结合宠物档案个性化（AI 现在连 `userId` 都不传）、B2 小程序快捷问题 chips（Web `AiGuru.tsx` 已有 4 条，小程序缺）、B3 会话持久化（前端 `HISTORY_LIMIT=6` 且服务端不落库 → 刷新即丢）。**不做**：流式输出、商品对比、拍照找货 |
| M7 | 买家端 微信小程序 | M6 的接口封装 + 小程序 AppID | 登录接 `code2Session`；支付复用同一套预下单 + 轮询接口 |
| M11+ | 运营账号管理页（提权目前靠 SQL） | M10 ✅ | 后台可视化开通/停用运营账号 |
| B-11 | **闲置担保交易闭环（C2C Batch-3）** | 闲置详情「立即购买」按钮（R53 过渡态已上线） | 下单/锁库存/资金托管/放款全链路。**前置硬条件：核实微信小程序类目与虚拟支付资质**（设计稿 §12-6）——资质不齐则方案改为「线下交易提示」。R53 购买按钮当前弹「担保交易即将上线」并引导问一问 |

---

## 5. 问题与协助区

> 你处理完把「状态」改成 **已协助** 即可，我会先验证再继续。

| # | 问题 | 状态 | 需要你做什么 | 我的验证方式 |
|---|---|---|---|---|
| I-01 | Docker Desktop 我无法代启动：家庭版必须走 WSL2，启动要调 `wsl.exe`，而它在沙箱黑名单且禁止绕过 | **已协助**（2026-09-15 你手动双击启动） | 无（若我提示引擎又停了，帮我双击一次） | `docker info` 能返回 Server 版本 |
| I-02 | `docker` 凭据助手不在 PATH，拉镜像失败 | **已自愈** | 无需操作 | 用独立配置目录 `D:\duoduo-infra\docker-nocreds`（不含 credsStore）绕开 |
| I-03 | 沙箱禁止我用 `Start-Process` 起 java 进程 | **已自愈** | 无需操作 | 改为容器化由 `docker compose` 起服务 |
| I-04 | 后台 PowerShell 命令中的中文路径按 ANSI 解析被破坏 | **已自愈** | 无需操作 | 改为前台执行；产物落在正确路径 |
| I-05 | `cmd.exe` 被沙箱禁止，无法用 `mvn.cmd` 构建 | **已自愈** | 无需操作 | 用 `java` 驱动 Maven launcher（`plexus-classworlds`） |
| I-06 | 沙箱侧 `github.com` 的 git 端点间歇 502 | **已自愈** | 无需操作 | 改用 `api.github.com` 增量同步 + 逐文件 blob 哈希比对 |
| I-07 | 容器构建需拉 `eclipse-temurin` 基础镜像 | **已自愈** | 无需操作 | 镜像已拉到本地，四个业务镜像构建成功 |
| I-08 | 容器化后冒烟 2 项失败：中文关键词检索命中 0 条 | **已自愈**（2026-09-17） | 无需操作 | 根因：初始化 SQL 被按 **latin1** 执行，中文**双重编码**入库。修法：脚本开头加 `SET NAMES utf8mb4`。复验：库内 `HEX` = `E9BAA6`、检索命中 4 条、冒烟 54/54 |
| I-09 | 购物车自测脚本自身有缺陷，一度把种子数据改坏（**测试代码问题**） | **已自愈**（2026-09-17） | 无需操作 | ① PowerShell 哈希表按**键类型**判等 → 21 条断言误报；② 改库复原写死数字 → 每跑一次污染一次。修法：字符串键 + 先读原值再写回 + 结尾自证数据已复原 |
| I-10 | 下单存在**幽灵冻结**风险：库存冻结在商品服务，不受本服务事务保护 | **已自愈**（2026-09-17） | 无需操作 | 原实现只在「整批全部成功」时记录补偿列表，**部分成功时已冻成功的 SKU 被漏记** → 库存永久冻住。修法：按逐项结果精确补偿；商品侧批次变更改为**整批原子** |
| I-11 | 订单生命周期三处真实缺陷（已付款可自助取消 / 超时关单不释放冻结 / 并发释放被记成失败） | **已自愈**（2026-09-17） | 无需操作 | 三处均由自测暴露并修复，且用断言固定住。`PAID → CLOSED` 那条被误用的边已在 M5-8 移除，退款改为独立终态 `REFUNDED` |
| I-12 | 真实微信 / 支付宝扫码支付需要商户资质与密钥 | **已协助**（2026-09-17 你决定改用**沙箱支付**，资质不再需要） | **无需操作**。材料清单仍留在下方折叠区，将来要接真实收款时再取 | 沙箱支付链路已按真实渠道的形态实现并自测通过：预下单返回二维码内容 → 收银台确认支付 → **签名回调** → 幂等入账 → 订单转已付款 → 资金进托管。将来把 `SANDBOX` 换成真实实现即可 |
| I-13 | 退货入库端点**编译通过但运行时 404**（新增端点无内部引用，编译期发现不了） | **已自愈**（2026-09-17） | 无需操作 | 现象：`NoResourceFoundException`，退款后入库一直失败、台账卡在 `RESTOCK_PENDING`。根因：源码里 `/restock` 路由在编辑过程中丢失，而它没有任何内部调用方。修法：补回路由 + 重建镜像；复验：调度 `restocked=2`，台账迁移到 `RESTOCKED`。**教训**：新增对外端点必须实调一次 |
| I-14 | **历史订单缺托管单**（M5-8 上线前的订单没有 `t_settlement` 记录） | **已自愈（2026-09-18，A1 完成）** | 无需你操作 | 影响：这些订单确认收货时「放款」会找不到托管单（只记 ERROR，不影响买家收货）。**修法**：新增 `POST /api/admin/settlements/backfill?dryRun=`（运营、幂等）按订单状态推断资金状态。**实测复验**：回补前开发库有 3 笔 `COMPLETED` 缺账本，执行后 `GET /api/admin/settlements/abnormal` 返回**空**；`scripts/backfill-smoke.ps1` 88 项断言全绿（含幂等、控制组、REFUNDED 盲区）。⚠️ 生产上线时**仍需在真实数据上跑一次**（先 `dryRun=true` 核对计划） |
| I-16 | 客户端发错 `Content-Type`（如 POST 无 body 时带 `x-www-form-urlencoded`）落到兜底 handler → 返回 **500** | **已自愈**（2026-09-18） | 无需操作 | 客户端错误不应是 5xx，会污染告警。修法：新增 `ErrorCode.UNSUPPORTED_MEDIA_TYPE` 与 `handleUnsupportedMediaType`，返回 **415**；由卖家冒烟断言固定 |
| I-15 | 支付渠道首轮自测暴露**三处缺陷**（M8-1 实现期） | **已自愈**（2026-09-18） | 无需操作 | ① **未知渠道 / MOCK 回调拒绝用了 400**（应为 422 业务错误，注册表 javadoc 与实现不一致）→ 补错误码 `PAYMENT_CHANNEL_UNKNOWN` 并改用之；② **二维码内容默认指向交易服务 8083 直连地址**——扫码设备不可达，且网关缺 `/sandbox/pay` 路由 → 改为网关 8080 + 补路由与白名单；③ **订单取消 / 关单不作废未支付支付单**——`markClosedByOrder` 在 Mapper 里定义了却从未被调用，订单已取消、二维码还能扫出钱来 → 在取消与调度关单两条路径都接上。三处均已由 62 项自测断言固定 |
| I-17 | 履约域（M11/P2-1）自测暴露**三个工具层隐患** | **已自愈**（2026-09-18） | 无需操作 | ① 内部接口断言**打错服务端口**（连履约 8084 打用户域路径 → 无路由 500，看着像过滤器坏了）；② **PowerShell 把 `$SPU?page=1` 整体当变量名**（`?` 是合法的变量名字符），展开成 `=1&pageSize=50`，服务端收到 `spuId="=1&pageSize=50"` → 必须写 `${SPU}?`；③ **19 位雪花主键经 `ConvertFrom-Json` 丢精度**，出现「数据在库里、断言找不到」的假失败 → 断言改用业务键，需要主键时从**原始响应文本**正则提取 |
| I-18 | **`git push` 通道受阻**：`github.com` 的 git 端点报 `CONNECT tunnel failed, response 502` / `Empty reply from server`（代理只放通 `api.github.com`） | **已自愈**（2026-09-18） | 无需操作 | 改走 **REST Git Data API** 推送成功（远端 `main = af28bba`）；工具 `.workbuddy/tools/push_via_api.py`（令牌取自 git 凭据管理器，不落盘、不回显）。注意：本地与远端 **SHA 分叉但内容一致**，网络恢复后执行 `git fetch origin main && git reset --hard origin/main` 对齐即可 |
| I-19 | 信用分（A4）自测暴露**四个工具层隐患 + 两个实现问题** | **已自愈**（2026-09-20） | 无需操作 | **工具层**：① 冒烟辅助函数少取一层 `.body`（响应包络是 `{status,body,…}`，数据在 `body.data` 下）→ 一批断言假失败；② 跨库 `INSERT…SELECT` 未写库限定名（`FROM t_order` 打到不存在的表）→ **插 0 行且不报错**，改 `FROM duoduo_trade.t_order`；③ 前端 TS `noUncheckedIndexedAccess` 下 `Record` 取值可空，`?? FALLBACK` 压不住 → 用**独立声明的非空常量**兜底；④ **`trade-smoke.ps1` 的 `Call` 少声明 `$headers` 参数**，调用处多传的实参被 PowerShell 静默丢弃 → 内部凭据头从未发出、内部批量 SKU 接口恒 403（7 条断言连带失败）→ 补齐参数后 87/0。**实现层**：⑤ 注册默认分硬编码 `100`，与 `CreditModel` 基础分 60 不一致 → 改可配置 `credit-default`（默认 60）并同步 DDL；⑥ `AfterSaleMapper` 漏 `@Select` import（编译期即失败），顺带把 `parseIds` 的 `NumberFormatException` 转 400。以上均已由 82 项冒烟 + **回归 13 套全绿**固定（详见 `08` 篇 **T-33 ~ T-36**）；顺带修正 `seller-smoke` 旧口径 84 → **101**（累计 935/1017 → **1034**） |
| I-20 | A11 死信链路自测暴露**两个工具层隐患 + 一个运维盲区** | **已自愈**（2026-09-24） | 无需操作 | ① **PS 5.1 给原生 exe 传参剥内嵌引号**：`curl.exe -d '{"a":"b"}'` 到 curl 手里变 `{a:b}` → 服务端 400；`mq-smoke.ps1` 首版**登录 JSON 被剥 → 登录失败 → burst 全 401**，限流断言「limited=0 且 ok=0」差点被误读成"限流没生效" → body 一律落临时文件走 `-d @file`，cookie jar 校验改为查**非 `#` 会话行**（详见 `08` 篇 **T-63**）；② **`docker stop` 误用 compose 服务名**（`duoduo-product-app`）→ `No such container`，报错又被 `Out-Null` 吞掉 → 商品域根本没停，死信断言连环假失败 → 原生命令只用**容器名**，停完必须显式断言（**T-64**）；③ **网关 404 的一次误判**：`/api/admin/mq/**` 路由加进 `duoduo-gateway` 的 yml 后只重打了**镜像**没重打 **jar**（Dockerfile 复制 `target/*.jar`）→ 重建容器仍是旧路由 → 教训：**改 yml 先 `mvn -pl <module> package` 再 `docker compose build`**，缺一不可 |

<details>
<summary><b>附：真实渠道备查（当前不需要你提供，将来要接真实收款时再取）</b></summary>

- **微信支付**：商户号 `mchid`、APIv3 密钥、商户 API 证书（`apiclient_cert.pem` / `apiclient_key.pem`）、
  关联 AppID（小程序 / 公众号）、可公网访问的 HTTPS 回调域名
- **支付宝**：AppID、应用私钥、支付宝公钥（或证书模式三件套）、已签约产品（电脑网站支付 / 当面付）
- **前置资质**：微信支付商户号需营业执照（个人主体申请不到）；支付宝需企业实名认证；回调域名需备案

</details>

---

## 6. 本轮变更记录

### 2026-10-23（七十）· 修复：非文字消息发送后不恢复「结束咨询」

| 项 | 内容 |
| --- | --- |
| 现象 | 结束会话后发图片 / 视频 / 表情包 / 商品卡，消息能发出但底部仍显示「当前会话已结束」，按钮不回来；只有文字（含输入框里的 emoji）正常 |
| 根因 | `setEndedSessionId(null)` 只写在**文字发送**那一条成功分支里。四条路径（文字 / 媒体 / 表情包 / 商品卡）各自独立调 `supportApi.send`，非文字三条漏了状态重置 —— 典型的「N 条分支只清理了 1 条」 |
| 修改 | 四条路径收敛到**唯一成功出口** `deliver(payload)`：内部调用 `supportApi.send` → `setEndedSessionId(null)` → 刷新会话与列表。新增消息类型只可能改这一个函数，结构上不可能再漏 |
| 验证 | `support-session-ui-check` 新增 8 条：贴纸、图片各走「结束 → 发送 → 小字消失 / 按钮恢复 / 服务端 status=OPEN」；**反向验证**：临时删掉重置语句 → 相关断言变红（21/3），恢复后 **39/0** |

### 2026-10-23（六十九）· 客服二期跟进：发送后定位 / 视频控制条悬浮 / 「结束咨询」交互

| 项 | 实现 |
| --- | --- |
| 发送后不定位 | 根因：分页窗口固定 30 条，长会话里再发一条数组长度仍是 30（挤掉最老一条），上一版用 `items.length` 做 effect 依赖 → 不触发。修：依赖改为**最后一条消息 id**（商城客服 / 商家工作台 / 私信 / 小程序两端共 5 处）。复现脚本用 41 条消息验证：修复前 `n=30, lastVisible=false`，修复后 `lastVisible=true` |
| 视频控制条 | 控制条由 flex 子项改为 `position:absolute; bottom:0` 浮层：`linear-gradient` + `backdropFilter:blur(10px)` 磨砂，不再占视频高度；视频全屏 `height:100%` 铺满整屏。新增 `controlsVisible` + `wake()`：5 秒无操作 `opacity:0; pointerEvents:none`，`pointermove/pointerdown/点击视频` 重新显示 |
| 结束咨询交互 | 点击后不再弹 toast、不再可重复点击：`endedSessionId` 记录本次结束的会话 → 按钮立即消失 + 会话流插入 `.sup-system`「当前会话已结束」；`send()` 成功即清空该状态（服务端此时已重开），按钮与提示一并恢复。小程序同口径（`ended` + `.chat-system`） |
| 验证 | `support-session-ui-check` **31/0**（新增发送定位、结束咨询三步断言）、`support-ux-ui-check` **53/0**（新增控制条定位/遮挡/模糊/渐隐/唤醒 6 项）；回归 support-read 24/0、seller 40/0、notice-chat-follow 50/0 |
| 教训 | 分页列表的「有没有新消息」不能用长度判断 —— 窗口滑动会让长度恒定；要用**末条 id**（与上次记录对比）。这条同样适用于小程序 `setData` 后的滚动判据 |

### 2026-10-23（六十八）· 客服会话二期：视频封面 / 定位未读 / 四档筛选 / 关闭规则 / 30 天清理 / 小程序对齐

| 项 | 实现 |
| --- | --- |
| 视频封面 | 根因：`uploadChatMedia()` 从不产出 `thumbUrl`，`t_support_message.thumb_url` 恒空。修：`captureVideoPoster()` 解码视频 → seek 0.1s（避开首帧黑场）→ canvas 缩至 ≤520 宽 → JPEG 0.82 → 走同一上传接口；`MediaBubble` 无封面时用 `<video src="{url}#t=0.1">` 画首帧兜底（历史消息零回填）。小程序用 `chooseMedia` 的 `thumbTempFilePath`（`media.uploadPoster`） |
| 定位最新未读 | 根因两层：① `.sup-body` 是内部滚动容器，旧实现「条数变化滚到底」常滚到窗口；② 新实现首版失效 —— `loading=true` 时渲染 `LoadingBlock`，`bodyRef` 为空，等 `setLoading(false)` 后依赖 `[thread?.sessionId]` 没变不重跑（诊断钩子实测 `hasBody:false`）。修：依赖加 `loading`；第一条未读打 `data-first-unread`，`scrollIntoView({block:'center'})`；`atBottomRef` 让轮询只在底部时跟随 |
| 商家四档筛选 | `listForSeller` 增 `filter`：ALL / UNREAD（`unread_for_seller>0`）/ ANSWERED（`=0`，含已关闭）/ CLOSED；`onlyUnread` 旧参数兼容。商家端按钮改常量四档，空态分档文案 |
| 关闭规则 | 新增 `closed_at`（增量 43）；`POST /api/support/close` 仅买家可用；商家旧 status 接口保留路径但恒 403（404 会让人以为接口消失）；关闭后客服回复 422；买家侧 `status` 由**服务端**抹成 null（不是前端不显示）；`sendByUser` 重开时用 `LambdaUpdateWrapper.set(closedAt,null)` 显式清空（MP `updateById` 跳过 null 字段）；`SupportMaintenanceService` 每 5 分钟 `UPDATE ... WHERE status='OPEN' AND last_message_at < now-24h`，条件在 SQL 里所以多实例幂等 |
| 30 天清理 | 判据 `status='CLOSED' AND closed_at < now-30d AND last_message_at < now-30d`；顺序：收集 `/uploads/` URL → 调履约域批量删对象 → **全部成功才删库**（失败保留行下轮重试，反之会留孤儿文件）→ 删消息 → 带条件删会话。履约域新增 `POST /api/uploads/internal/delete`（`publicKeyOf` 剥 query、只认 `/uploads/`、拒 `..`），内部前缀同步登记进 `InternalTokenFilter` |
| 手动触发 | `POST /api/socials/internal/support-sweep?close=&cleanup=`（内网签名），供运维补跑与自测 |
| 小程序对齐 | 去掉 3 处「会话已关闭」+ 输入禁用；加「结束咨询」；`scrollToLatest`；视频 `poster`；未读改 `.msg-unread` 琥珀；新增表情/贴纸面板（`utils/emoji.js` 56+12，`config.STATIC_BASE` 指向 Web 静态资源） |
| 后续 IM 设计 | `docs/support-im-followup-design.md`：语音（格式矩阵与两方案取舍）/ 引用（`meta_json` 快照 + `around` 定位）/ 撤回（软撤回 + 未读收敛 + 通知残留），含变更清单与验收 |
| 验证 | 新增 `support-lifecycle-smoke.py` **32/0**、`e2e/support-session-ui-check.mjs` **25/0**；回归 `support-ux-ui-check` 47/0、`support-read` 24/0、`support-split` 22/0、`notice-chat-follow` 50/0、`seller` 40/0；两模块 mvn package 通过，四容器重建 healthy |
| 教训 | ① React 里「数据到了但容器还没挂载」会让 effect 空跑一次且不再重试 —— 依赖里必须带上"挂载条件"（本例是 `loading`）；② 页面内 `fetch` 造数会被 nginx 注入的 `X-Duoduo-Entry` 锁死身份（商城令牌访问商家接口一律 403「当前登录入口无权访问该接口」），跨端造数必须走 Node 直连网关；③ 气泡里新增 `<video>` 后，`document.querySelector('video')` 会先命中气泡，全屏断言必须限定 `[role="dialog"] video`；④ `open(p,'w')` 会在校验参数前就截断文件 —— 脚本改文件一律用 Edit，不用 open/write |

### 2026-10-23（六十七）· 客服二次修复：精确会话 / 店铺归属 / 媒体体验（二次验收 Q1~Q10 复盘）

| 项 | 实现 |
| --- | --- |
| Q1 精确定位会话 | 根因两层：① `SupportEntry` 把 `sellerId=0` 当「不是店铺」丢弃，自营入口没带参；② `Support.tsx` 首屏 effect 依赖 `[]`，且只 `setActiveSellerId(入参)`，没拿服务端归一化后的真实 `sellerId` 回填。修：`0` 也带参；依赖改 `[sellerIdParam]`；`loadThread` 后用 `data.sellerId` 回填 + `loadSessions()` 补拉（首次咨询的会话是这次调用才创建的）；`activeItemRef` + `scrollIntoView({block:'nearest'})`。`OrderDetail` 的咨询按钮、`ProductDetail` 自营商品同步传 `'0'` |
| Q3 订单卡 | 删除卡片上的订单号，店铺名前置为 `.sup-store-link` + `aria-hidden` 的 `›`；订单号只在详情页（`订单 {orderNo}`）出现。e2e 断言「卡片不含订单号**数值**」，不只断言标签 |
| Q4 店铺维度归属 | 新增 `client/dto/MembershipBrief` + `UserClient.merchantMembership`；`SellerSupportController.requireSellerId` → `requireStoreOwnerId`：成员身份优先（店主/在职店员都解析到 `ownerUserId`），仅 `SELLER` 角色按自身 userId 兜底（自营客服无入驻行），其余 403；用户域异常降级 + warn。店员 `13952746517` 实测返回本店会话 1 条（`sellerId=370518346074034176`） |
| Q5 未读样式 | 删 `tag tag-warn` 容器，改时间行内 ` · 未读`（`.sup-unread{color:var(--warn)}`），与「已读」同一行同一位置；买家端/商家端同步 |
| Q6 会话高度 + 表情面板 | `.sup-body` 由 `max-height:52vh` 改 `height:clamp(320px,60vh,620px)`；`ChatMediaMenu` 面板改 `fixed` 锚点定位（绕开祖先 `overflow:hidden`）、`maxHeight:min(58vh,420px)`、`overflowY:auto`；表情 16→56、贴纸 6→12（两端 `public/stickers` 补齐）；补 `Escape` 与外部点击关闭 |
| Q10 图片/视频 | 灯箱与播放器均去 `.card` 白容器，改 `rgba(6,8,12,.55)` + `blur(2px)` 轻蒙层；灯箱原生 `wheel`（`passive:false`）实现 Ctrl+滚轮 0.3x~6x、拖动平移、双击复位；进度条 16px 命中区 + window 级 `pointermove/pointerup` 跟手拖动；全屏作用于播放器盒子（`height:calc(100vh-56px)`、`objectFit:contain`），关闭按钮移入全屏元素内部（此前被 `<video>` 盖住点不到）；暗色浮条按钮显式透明底（`.btn-ghost` 背景是白的，白底白字）；`▶⏸⛶` 等字符换内联 SVG（Windows 字体渲染成 tofu） |
| 未选会话禁发 | 根因：发送函数只判 `content`，服务端把 `sellerId=null` 口径兜底到官方客服，于是「没选会话」的消息进了「多多客服」。修：四个发送入口全部判 `activeSellerId`；未选中时不渲染输入区（正文 `Empty`「请选择一个会话」，底栏一行提示）；商家端 `SupportInbox` 同改 |
| 私信排查 | 结论：无同类问题。路由必须 `:peerId`、页面判空、加载失败整页替换、服务端 `requireUserExists` 拒绝不存在的收件人。e2e 固化两条断言（列表页无输入区；对不存在 receiverId 发送返回非 SUCCESS） |
| 验证 | 新增 `scripts/e2e/support-ux-ui-check.mjs` **47/0**；回归 `support-read` 20/0、`support-split` 22/0、`notice-chat-follow` 50/0、`seller` 40/0、`product-detail` 19/0、`store-page` 17/0、`cart-batch` 通过、`web10-batch-check` 9/0；三端 typecheck + build 通过；web/merchant 容器重建 healthy |
| 教训 | ① 浮层不关掉会吞掉落在它上面的滚轮事件，症状看起来像「缩放功能坏了」，实则是**上一步没关面板**（e2e 必须先断言面板可关闭）；② 断言 `elementFromPoint` 的 `tagName` 在按钮内含 SVG 后会变 `path/svg`，要 `closest('button')` 回溯，否则把「修好了」判成红；③ 全屏元素在浏览器顶层，渲染在它之外的固定定位按钮会被盖住，必须放进全屏元素内部；④ `.btn-ghost` 不改背景色，暗色容器里必须显式透明底 |

### 2026-10-22（六十四）· 十问题批次：咨询/店铺/客服/未读体验改造（Q1~Q10）

| 项 | 实现 |
| --- | --- |
| Q1 自营咨询报「店铺不存在」 | 根因：自营订单 `seller_id=0`，前端把 0 当店铺 id 拼进 `?sellerId=0`，social 侧按店铺找会话失败。修：`SupportService.ensureForUser/sendByUser` 里 `target = (sellerId==null||sellerId==0) ? officialCsUserId : sellerId`；ORDER 分支同兜底；前端 `OrderDetail.tsx`/`SupportEntry.tsx` sellerId 为 0/空时**不带** sellerId 参数 |
| Q2 店铺页排序/搜索 | `ProductController.storeProducts` 增 `keyword`（service 内本店范围 `like` 过滤，不进 ES）；`Store.tsx` 增排序胶囊（综合/销量/价格↑↓/最新）+ 搜索框（Enter 触发、可清除），空态区分「无商品」与「无匹配」 |
| Q3 订单卡片店铺名 | `OrderService` 经 Feign `StoreNameClient`（`contextId=tradeStoreNameClient`，防与既有同服务客户端冲突）批量取店名 → `OrderView.storeName`；自营（0/null）固定「多多自营」不查库；user 域 `storeNamesOf` 只回 `APPROVED`（DRAFT 夹具店铺不外发，前端无店名不给链接，杜绝死链）；`Orders.tsx` 卡片头部渲染店铺名 Link |
| Q4 会话名改店铺名 | social 新增 `client/dto/StoreBrief.java` + `UserClient.storeNames()`；`toSessionView` 增店名映射参数，`sellerDisplayOf`：店铺名 > 昵称 > 「店铺{id}」，**自营客服保持昵称**；`loadStoreNames` 批量、排除 0 与官方客服、失败降级空 Map。四处调用点接线 |
| Q5 逐条「未读」标记 | 买家端 `Support.tsx` 与商家端 `SupportInbox.tsx`：`unreadTag = m.mine && !m.read` 渲染琥珀 `tag-warn`（非红色，区别于报错）；「已读」回执改为 `isLastMine && myUnreadCount===0` |
| Q6 加号菜单二级化 | `chat-media.tsx` 的 `ChatMediaMenu` 改 `panel: 'root'|'emoji'` 两层：根层四入口（图片/视频/表情/商品），表情进二级面板；`onProduct` 可选回调。商城 `Chat.tsx`/`Support.tsx` 移除外置 🛍，商品入口收编进菜单（Chat 仅在有商品上下文时启用） |
| Q7「正在看」真实商品卡 | `product-picker.tsx` TAB 改名；current 分支改为 `productApi.detail(currentProductId)` 回源，映射 `{id,title,image,price}`，`ProductCard` 无 status 不硬造 |
| Q8 商家端导航角标 | `MerchantRoot.tsx` 新增 `useNavBadges`（四接口 `Promise.allSettled`、30s 轮询、路由切换刷新）+ `NavBadge` 胶囊；`aria-hidden` 防改变按钮可访问名破坏 `getByRole exact` 断言 |
| Q9 分类未读数 | `NoticeService.unreadByCategory` 五类分桶（与 unreadCount 同口径，排除私信/客服）→ `GET /api/socials/notices/unread-by-category`；`Notifications.tsx` chip 角标（>99 显示 99+，`aria-hidden`）；方案文档 `docs/notification-unread-badge-design.md`（三端链路 + 两条纪律：角标数字必须服务端分桶、私信/客服通知列表可见不计数） |
| Q10 灯箱与视频弹窗 | `chat-media.tsx`：IMAGE 点击 → `ImageLightbox`（0.4~4 缩放、下载）；VIDEO → 海报+自绘播放按钮+时长角标 → `VideoPlayerModal`（播放/进度 seek/静音/音量/全屏/保存，纯 CSS 实现，不引播放器库）；`downloadMedia` fetch blob 优先、CORS 失败退 `window.open`。核实视频文件已在 COS（`STORAGE_TYPE=cos`，mp4 不追加 imageMogr2 后缀），无需迁移 |
| 验证 | 定向 `scripts/web10-batch-check.py` **9/0**（登录 / Q1 会话落官方客服 / Q3 storeName 下发与自营值 / Q1 ORDER 卡发送 / Q4 第三方会话名=店铺名、自营保持客服 / Q9 五类齐备）；e2e `store-page` 17/0、`notice-chat-follow` 50/0、`seller` 40/0、`cart-batch` 31/0、`product-detail` 19/0；`support-read` 20/0、`support-split` 22/0；`mvn test` trade 119/0、social 16/0；六容器重建 healthy，`NO_PROXY=*` curl 实测排序升降序与关键词命中 |
| 教训 | ① 角标 span 必须加 `aria-hidden`，否则按钮可访问名变化让 `getByRole(..., {exact:true})` 假红（两处都踩）；② 测试构造器加依赖要数参数（OrderListFilterTest 19 个 null）；③ cart-batch 三处脆弱点修复：取消弹窗后等遮罩 hidden、确认文案接受「清空/删除已选」两态、空车自愈夹具（登录+补 SKU 3003） |

### 2026-10-22（六十三）· 修「支付成功但订单仍是待支付」：多卖家拆单撞幂等键

| 项 | 实现 |
| --- | --- |
| 现象 | 支付宝沙箱真付款成功（两笔 `ALIPAY_PC` 均 SUCCESS），订单 `202610221304559969466` 一直 `PENDING_PAYMENT`；兜底调度每分钟重试并报「扣减库存失败：SKU 3010/3020 库存不足」，而实查 `stock=479/260`、`frozen_stock=0`（库存根本不缺） |
| 根因① | 多卖家购物车按卖家拆单，**每张子单都写同一个 `idempotent_key`**，而唯一索引是 `(user_id, idempotent_key)` → 第二张子单必然撞键。撞的不是并发，是同一批拆单的自己人。索引改 `(user_id, idempotent_key, seller_id)`（增量 `42-order-idem-per-seller.sql`，可重复执行） |
| 根因② | 撞键分支在**事务内**「补偿释放库存 + return replay」→ 第一张子单连同行/台账一起被提交，但它的冻结量已被释放。商品域 `frozen_stock=0`、交易域台账 `FROZEN`，支付时 `deductStock` 要求 `frozen_stock >= qty` → 永远扣不动。改为 `setRollbackOnly()` + 抛 `DuplicateOrderKeyException`，由**事务外**入口重查并回放整批 |
| 根因③ | 同一订单可反复开出新的支付单（`pay()` 只按订单状态拦），**用户点两次支付就被收两次钱**。修：开新单前先查该订单是否已有 SUCCESS 支付单（有则拒绝），同渠道存在未过期 PENDING 则**复用同一张**；回调/查询兜底遇到「订单已由另一张支付单入账」时置 `CLOSED` + 留痕，不再无休止重试 |
| 幂等查询 | `findByIdempotentKey`（`selectOne`，多行会抛 TooManyResults）→ `findAllByIdempotentKey`（List）；`replay()` 支持整批回放 |
| 数据修复 | `docs/sql/repair/2026-10-22-frozen-mismatch.sql`：补回被误释放的冻结量 → 重复收款那笔置 CLOSED → 保留那笔回 PENDING 并延长有效期待兜底重认领。**不手工改订单状态**，托管/券核销/状态日志都由真实入账链路产生。结果：订单 `PAID`、台账 `DEDUCTED`、`stock 477/259`、`frozen 0`、托管单 `CUSTODY 14880` |
| 重复收款退款 | 第二笔 148.80 元经沙箱 `alipay.trade.refund` 原路退回（`fund_change=Y`，`refundNo=dup-202610221308521149245`），支付单 `CLOSED` + fail_reason 写明已退款 |
| 守卫 | 新增 `scripts/check-stock-freeze-recon.mjs` **6/6**：台账 FROZEN 汇总 == `t_sku.frozen_stock`（双向）、每单最多一笔 SUCCESS 支付单、无待退款重复收款、已付/已关单无 FROZEN 残留、待付款单必有 FROZEN 台账。修复前实测 **4/6**，恰好红在那两条事故症状上；反向验证（人为把 92001 冻结量改成 3）**只红 1 条、其余全绿** |
| 验证 | 新增 `scripts/split-order-idem-smoke.py` **18/18**：两家卖家拆 2 单 → 同键重放回整批 2 张（不是 1 张、不 500）→ 不重复冻结 → 台账与商品域一致 → 两张都能 MOCK 支付到 PAID；`direct-buy-smoke` **14/0**（同时修了脚本自身两处假绿：夹具 SKU 2006 已不存在、断言误用后一次 GET 的状态码）；`mvn -pl duoduo-trade test` **119/0** |

### 2026-10-22（六十二）· 修复第三方商品错挂「多多自营」+ 自营补进店入口

| 项 | 实现 |
| --- | --- |
| 根因① | `SupplyTag` 只认 `'merchant'`，库中真实取值是 `self`/`third`（select distinct 实测）→ 所有第三方商品落 else 分支挂「多多自营」；首页闲置区（`secondhand`）同样中招。修：以 `self` 为白名单，`secondhand` 单列「个人闲置」，其余「品牌入驻」；小程序 `product.wxml` 同步修 |
| 根因② | `MetaController` 字典与 `search()` 校验写 `merchant`，库中无此值 → 搜索「第三方商家」静默空结果、正确的 `third` 反被 422。修：字典/校验/`ProductQuery` 类型/搜索页兜底字典统一 `self`/`third` |
| 自营进店 | 自营卡/详情页/购物车组头「多多自营」可点 → `/store/0`（第三方跳 `/store/{sellerId}`；自营卡 sellerId 是客服 id 不能当店主 id） |
| 验证 | 新 e2e `supply-tag-ui-check.mjs` **15/15**（含「第三方卡不含多多自营」反向断言、自营跳 /store/0）；回归 cart ×2 轮 29 项全过、详情 19/0、店铺页 17/0 |
| 数据 | cart-batch 每轮净删 1 行，多轮后夹具耗尽致起手超时（非回归）——补回 SKU 3010/3020/92001 |

### 2026-10-22（六十一）· 店铺主页（参考淘宝店铺页）

| 项 | 实现 |
| --- | --- |
| 用户域 | `GET /api/users/stores/{ownerUserId}` 公开只读 `StorePublicView(ownerUserId, name, openedAt)`；仅 APPROVED 可查、其余 404；不含资质/违规/停业等运营侧信息 |
| 网关 | 白名单精确加 `/api/users/stores/*`（两段，Ant `*` 不跨 `/`）；单测补放行 + 不连带放行两组断言（31/0） |
| 商品域 | `GET /api/products/store/{sellerId}`：`visibleSpuWrapper()` + sellerId 过滤 + `applySort` + 分页；**不进 ES**（无 sellerId 维度 filter，防越权展示）；**sellerId=0 走 `supply_type='self'`**（自营 seller_id 落客服账号 id，库中无 0 号行）；复用 `toCards` 店铺名自动带上 |
| 前端 | 路由 `/store/:sellerId`（公开）+ `Store.tsx`（页头 + 网格 + 加载更多分页）；api-sdk 增 `storeProducts` / `storeInfo` / `StorePublicView` |
| 入口三处 | 商品卡店名（span+stopPropagation，防嵌套 a）、详情页店名（Link，仅第三方）、购物车组头「进店 ›」（preventDefault+stopPropagation，防触发组全选） |
| 移动端 | 网格 auto-fill 210px 窄屏自动 2 列；页头 flex 不溢出；按钮分页不受移动端视口伸缩影响 |
| 数据修正 | 喵星人旗舰店（测试）`t_merchant.status` 脏值 `ACTIVE` → `APPROVED`（枚举外值导致公开主页 404） |
| 验证 | e2e `store-page-ui-check.mjs` **17/17**；回归 `cart-batch-ui-check` 全过、`product-detail-ui-check` 19/0；单测网关 31/0、商品可见性 10/0 |
| 部署坑 | 镜像打本地 `target/*.jar`：只 `compile` 不 `package` 时 `--build` 仍打进旧 jar —— 必须先 package（与第八十轮「force-recreate 不重建」同属「构建链断点」类陷阱） |
| 边界 | 店铺 logo/简介/招牌图：`t_merchant` 无这些列，本期只展示店名+开店时间+在售计数，不做 DDL；小程序端店铺主页未做（同口径接口已就绪）；分页上限复用 MAX_PAGE_SIZE=100 / MAX_RESULT_WINDOW |

### 2026-10-22（六十）· 购物车按店铺分组 + 结算明细实时汇总优惠 + 店铺名可修改

| 项 | 实现 |
| --- | --- |
| 分组键 | `CartItemView.sellerId`（自营恒 0，第三方为店主 userId）；分组基于 `orderedItems`，第五十九轮的顺序快照护栏对分组同样生效 |
| 分组 UI | 每组一个 `section`：店级复选框（indeterminate 半选）+ 店名 + 自营徽标 + 「N 件 · 已选 ¥x」；组内 `CartRow` 包 `.stack` |
| 店名通道 | user 域 `GET /api/users/internal/store-names?ownerIds=` → product 域 `StoreNameClient`（`contextId=productStoreNameClient`）→ `SkuPurchaseView.storeName` / `ProductCard.storeName` → trade 域透传进 `CartItemView.storeName` |
| 自营店名 | `ProductService.SELF_STORE_NAME = "多多自营"`（自营不查库）；第三方取 `t_merchant.name`，查不到降级为 `null`、前端回退「品牌入驻」 |
| 结算明细 | `couponApi.usable(selectedAmount)` 实时查询 → 取 `bestCouponCode` 券 → `payableAmount = selectedAmount - discountAmount`（下限 0）；吸底栏共用该值并显示「已优惠 ¥x」 |
| 口径 | 金额不在前端累加，`selectedAmount` 一律取服务端值；面板标注「实际优惠金额以下单页为准」 |
| 店名可改 | `PATCH /api/users/me/merchant-application/store-name`；`MerchantStatus.canRenameStore()` 仅 `APPROVED`；`validateStoreName` 复用；撞名 → `DuplicateKeyException` → 「店铺名已被使用」；审计 `MERCHANT_STORE_RENAME` 含 before/after；同值幂等 |
| 商家端 | `Account.tsx` 新增 `StoreRenamePanel`（APPROVED 才显示「修改店名」，改后 `refreshMe`） |
| 展示场景 | 商品卡 `StoreNameText`（`ProductCardView` 的 `goods-meta` 用店名替换品牌名）、`ProductDetail`、`Cart` 组头、`Checkout` 按 sellerId 分组 + 文案更正为「按店拆单、券按比例分摊（R60）」 |
| 验证 | `cart-batch-ui-check.mjs` 扩至 **29 项全过**（新增自营/第三方组名、结算明细面板、明细合计 ¥0.00 / ¥913.00）；`GET /api/cart` 返回 `store` 字段正确；改名两次均 200 且审计两条；`GET /api/products` 卡片带 `store` |
| 边界 | 券无 `merchant_id/scope`，暂无「店铺优惠」维度（预留累加点）；小程序端商家无改名入口；改名未限频；店名不进订单快照 |
| 部署坑 | 商家端 `build:merchant` 在 monorepo 连续构建下偶发 `emptyDir` 失败，单独重跑即恢复；`merchant.localhost:8091` 走系统代理会 502（代理不解析该域名），核对须 `NO_PROXY=*` |

### 2026-10-22（五十九）· 修复购物车「全选后顺序被打乱」

| 项 | 实现 |
| --- | --- |
| 根因 | `CartService.list()` 按 `updated_at DESC` 排序，而 `updateSelected()` / `updateQuantity()` 都会写 `updated_at` → 勾选这个纯选择动作改写了排序键；前端逐条串行 PATCH + 每次重取列表，最后一条被 PATCH 的必被顶到最前 |
| 后端修复 | 排序改为 `created_at DESC, id DESC`（只依赖加入购物车时间，雪花 id 兜底同毫秒）——`CartService.list()` 一行改动 |
| 前端护栏 | `Cart.tsx` 新增 `orderRef` 顺序快照：首次渲染锁定 skuId 序列，后续 reload 一律按该序列重排，新加购追加尾部 |
| 验证 | `cart-batch-ui-check.mjs` 扩至 **23 项全过**（新增 10 次逐字顺序断言：基线 / 全选 / 取消 / 全选×3 轮 / 改数量） |
| 反向验证 | 临时改回 `updated_at` + 旁路前端护栏 → 第 3 轮全选恰好抓到顺序漂移（仅该条红）；恢复后核对容器内 `CartService.class` 为 `getCreatedAt` |
| 部署坑 | `docker compose up -d --force-recreate` 不重建镜像，必须 `--build`；核对要解包 `/app/app.jar` 的 `BOOT-INF/classes` |

### 2026-10-22（五十八）· 购物车批量操作与删除确认

| 项 | 实现 |
| --- | --- |
| 删除已选 | 前端接线已有 `DELETE /api/cart/selected`；底部「删除已选（n）」0 件禁用 |
| 确认弹窗 | 单条删除与批量删除均走 Modal；全选时文案升级「清空已选商品/全部删除」 |
| 全选半选 | indeterminate 中间态；全选仍为逐条串行 PATCH（无批量接口） |
| 同步 | 写后统一 reload + refreshCart，件数/合计/角标联动 |
| 验证 | UI e2e 14 项全过（cart-batch-ui-check.mjs）；tsc 0 错 |

### 2026-10-22（五十七）· 足迹页重构：按天分组 + 分页 + 批量删除 + 搜索（对标淘宝）

| 项 | 实现 |
| --- | --- |
| 分组 | 前端按北京自然日分桶（`bjDateKey`/`historyGroupLabel`），组标题「今天/昨天/M月D日（N件）」吸顶 |
| 分页 | 每页 50 条，IntersectionObserver 自动加载 + 按钮兜底，前端按 id 兜底去重 |
| 删除 | 后端新增 `DELETE /api/history/{spuId}` 与 `/api/history/batch?ids=`（deleteViews 恒带 userId）；前端勾选 + Modal 确认 |
| 搜索 | 标题本地过滤；下架商品置灰 + 角标；空态「去逛逛」 |
| 验证 | 接口专项 15/0（含越权反向）、媒体+收藏回归 15/0、UI 6/0、tsc 0 错、主题守卫 55/0 |

### 2026-10-22（五十六）· 足迹按时间倒序 + 卡片显示浏览时间

| 项 | 实现 |
| --- | --- |
| 排序 | `listViews` 本就 `orderByDesc(viewed_at)`；专项验证确认（浏览 3 件 → 返回倒序、时间递减） |
| 时间下发 | `PickerProduct` 加 `viewedAt`（UTC ISO 带 Z；仅足迹下发，收藏/已购为 null） |
| 前端 | 足迹卡片「浏览于 今天/昨天/MM-DD HH:mm」，走 `time.ts` 北京时间统一口径 |
| 验证 | 接口专项 `history-order-verify.py` 9/0；媒体+收藏回归 15/0；UI `history-ui-verify.mjs` 3/0 |
| 排障 | Bash 里 mvn 缺 JAVA_HOME 会静默 exit 0（假绿）→ 打包后核对 target jar 时间戳 |

### 2026-10-22（五十五）· 双身份链路：跨站身份闸门 + 入口去重 + 实名跳转修复

| 项 | 实现 |
| --- | --- |
| 双身份 | 同一账号（同一 userId）在商城/工作台均可登录；工作台提供「去商城个人中心」与「返回商城」，携带 `from=merchant&expect=<userId>` |
| 身份闸门 | 商城 Layout：`expect` 与当前商城登录为同一账号 → 直接放行（清参数、保留 hash）；不同或未登录 → 清商城会话 + `/login?reason=expired&next=…` |
| 登录提示 | 商城与商家登录页在 `reason=expired` 时显示「登录已过期，请重新登录」 |
| 个人中心 | 商城头像下拉「个人中心」先调 `userApi.me()` 验证会话，失败即登出并跳过期登录页 |
| 去重 | 下拉菜单中「我的收藏夹 / 我的足迹」重复两遍，删掉一份 |
| 实名按钮 | 根因：非卖家时 `MerchantShell` 对所有子路由渲染同一引导卡，`/me` 也被吞掉。改为直接跳 `${MALL_URL}/me?from=merchant&expect=<id>#realname`；商城锚点保留 |
| 验证 | 同账号→商城首页仍登录；不同账号/未登录→`/login?reason=expired` 且提示可见；实名跳转落 `/me#realname`；SELLER 可正常使用商城个人中心；菜单各 1 项；登录隔离 11/0、媒体 15/0、主题 55/0 |

### 2026-10-22（五十四）· 移除跨站「商城个人中心」入口（角色边界修正）

| 项 | 实现 |
| --- | --- |
| 商家账号页 | 删除「去商城个人中心」外链（原先会带着工作台账号 id 跳到商城 `/me`），改为静态说明：两端账号独立、工作台不跳商城个人中心 |
| 返回商城 | 去掉 `from/expect` 参数，恢复为纯导航 |
| 商城 | 删除「账号不一致」提示条与 `expect` 解析（任意 URL 都能触发这类提示，本身也不该由商城解释另一端身份） |
| 验证 | 账号页无跨站入口且有边界说明；商城 `/me?from=merchant&expect=…` 不再出现提示；登录隔离 **11/0**；主题守卫 55/0 |

### 2026-10-22（五十三）· 待办复核：两条 e2e、售后上传口径、二维码守卫

| 项 | 实现 |
| --- | --- |
| C1 e2e | 均为用例过期：商品发布改为主图地址输入 + 点「添加地址」；分享删除补「确认弹窗内再点删除」。结果 36/0、21/0 |
| C3 上传口径 | `ImageUrls` 允许 `http(s)` 与站内 `/uploads/`（拒绝任意 `/`）；三站点 nginx 增 `/uploads/` 反代；`imageMogr2` 仅在 COS 后端追加 |
| 二维码 | 实为 2026-10-18 已换 `qrcode` 库；本轮补 `scripts/check-qrcode.mjs`（定位图案/时序/分隔带/内容差异）6/0 |
| 复核结论 | C7 注销页、D1 个人主页、D3 小程序搜索结果页**均已存在**，台账过期；C2 夹具清理因无法区分验收账号，暂不批量执行 |

### 2026-10-22（五十二）· 端侧视频压缩 + 跨站账号提示 + 入口可发现性

| 项 | 实现 |
| --- | --- |
| 端侧压缩 | `chat-media.tsx`：时长 >60 秒直接拒绝；>6MB 走 canvas + MediaRecorder 重录（高≤720/宽≤1280/30fps/1.6Mbps/96k），优先 mp4 否则 webm；压缩失败/不支持/产物不更小 → 回退原视频；压缩与上传均显示百分比 |
| 服务端 | 视频不再按大小拒绝（`max-video-size-mb` 形同保留）；白名单加 webm；Range 覆盖 webm；nginx 上限 512m |
| 小程序 | `utils/media.js`：`wx.getVideoInfo` 校验时长、`wx.compressVideo(medium)` 压缩、失败回退；两页复用 |
| 跨站账号 | 商家端跳商城带 `from=merchant&expect=<userId>`；商城检测到不是同一账号时显示提示 + 「切换为工作台账号登录」（退出商城会话）/「继续使用当前账号」 |
| 客服入口 | 商家工作台「客服信息」页签对所有角色可见；运营/店员进入显示职责说明页（不再静默消失）；平台后台按职责不承载客服接待 |
| 个人中心 | 新增「我的商品」卡片（收藏夹/足迹），头像下拉同步加入口，页面 `/favorites` `/history` 已存在 |
| 验证 | 压缩 13.2MB/30s → **5.52MB（-58%）**，进度 3%→6%→…；64.8s 视频被拒；角色页签/说明页、跨站提示与切换、入口跳转均实测 |

### 2026-10-21（五十一）· 客服 C4：订单卡片 + 内置贴纸

| 项 | 实现 |
| --- | --- |
| 订单事实 | 社交域新增 `OrderClient`（Feign + 内部签名）读 `GET /api/orders/internal/{orderNo}/fact`；只认单号，快照由服务端落 `meta_json` |
| 归属校验 | 订单 `userId` 必须等于当前用户，否则 403（实测他人订单被拒） |
| 前端 | 订单详情「咨询订单」先发卡再跳客服；订单卡买家端可跳详情，商家端只读（防越权跳转） |
| 贴纸 | 6 张内置 SVG（`apps/mall/public/stickers`，商家 publicDir 共享），以 EMOJI 消息发送 |
| 验证 | `media-favorite-smoke.py` **15/0**（新增订单卡 3 条）；其余回归 20/0、22/0、11/0；主题守卫 55/0、25/0 |
| 限制 | 真实视频已验（用户提供 81MB 录屏 → 转码 720p/60s 5.5MB → 上传 COS → 播放走带正常）；81MB 原文件会被 20MB 上限拒绝，属设计行为 |

### 2026-10-21（五十）· 客服 B/C 批：多媒体消息 + 四类商品来源

| 项 | 实现 |
| --- | --- |
| 上传 | `UploadController` 支持 mp4（20MB），`GET /uploads/**` 支持 Range（206）；nginx 体积上限 25m；视频不追加图片处理参数 |
| 数据 | 增量 39（消息媒体 6 列）、增量 40（`t_product_favorite` / `t_product_view`） |
| 社交域 | 客服（用户/商家）与私信统一支持 `IMAGE/VIDEO/EMOJI`；媒体地址白名单校验；列表摘要用「[图片]/[视频]/[表情]」 |
| 商品域 | 收藏切换/状态/列表、足迹记录/列表/清空；商品详情旁路记录足迹 |
| 交易域 | `GET /api/orders/purchased-products`（按 spu 去重，仅已付款/发货/完成） |
| 前端 | 共享 `ChatMediaMenu`/`MediaBubble`（三页复用）、`ProductPicker`（当前浏览/购物车/收藏/已购）、收藏与足迹页面、商品详情收藏按钮 |
| 小程序 | 客服与私信页 `chooseMedia`（相册+拍摄）+ 上传 + 图片预览 + 视频播放 |
| 未做 | **C4 订单卡片**（范围取舍）；真实视频播放与小程序的真机验收；贴纸类表情包 |

### 2026-10-21（四十九）· 客服消息接入通知中心

| 项 | 实现 |
| --- | --- |
| 写入 | `NoticeService.pushSupportNotice`：商家回复时写 `category=MERCHANT`、`bizType=SUPPORT` 通知，同会话合并；`jumpPage=/pages/support/support?sellerId=…` |
| 落地页 | `NoticeWebPaths` 增 SUPPORT → `/support?sellerId=…` |
| 计数 | SUPPORT 与 MESSAGE 一同从「通知未读」排除（未读口径仍在 support 会话上），`total = notice + message + support` |
| 已读 | 买家打开客服会话 → 通知同步已读；通知中心「全部已读」→ 客服未读一并清零；菜单「通知中心」角标 = notice + support |
| 验证 | `support-read-smoke.py` **20/0**；UI：菜单角标 1、通知列表「商家」分类可见、点击直达 `/support?sellerId=…`、读后归零 |

### 2026-10-21（四十八）· 客服 A 批：未读/已读、筛选、越权修复

| 项 | 实现 |
| --- | --- |
| 数据 | 增量 38：`t_support_session` 增双向读游标（`user_last_read_id`/`seller_last_read_id`），按历史 `read_at` 回填 |
| 后端 | `SupportService`：标记已读推进游标并把未读置 0；会话详情返回 `peerLastReadId`/`myUnreadCount`；`listForSeller` 支持 `onlyUnread`；买家发消息时关闭会话自动重开 |
| 前端 | 商家端筛选改「全部/未读」；`Number()` 归约修 `0000…`；点开即清零（乐观更新）；最后一条我方消息显示「已读 / 未读 N 条」；商品卡改工作台只读弹窗（不外链）；取消首条自动带卡 |
| 隐患 | 列表自动选中第一条会误标已读 → 改为「列表只展示，点开才算读」 |
| 验证 | 新增 `scripts/support-read-smoke.py` **16/0**；`support-split-smoke.py` 22/0；Playwright 角标 1→0、回执「未读 1 条」、弹窗不跳转 |

### 2026-10-21（四十七）· 三端登录态隔离 + 时间口径 + 工作台账号中心

| 项 | 实现 |
| --- | --- |
| 根因（隔离） | 三站共用 Cookie 名 `dd_at`/`dd_rt`，而 **Cookie 按主机名隔离、不区分端口** —— `localhost:8088` 与 `localhost:8091` 共用一个罐，登录一端覆盖另一端 |
| 修复（隔离） | `AuthCookieWriter` 按 `X-Duoduo-Entry` 分名：`dd_at`（商城）/ `dd_at_m`（商家）/ `dd_at_a`（后台）；网关 `resolveToken` 同步按入口取名。**商家/后台不回退基础名**（否则商家未登录会被商城会话顶替），商城保留回退兼容老会话 |
| 个人中心 | 商家端新增 `/seller/account` 账号中心（当前工作台身份：角色/手机/账号 ID/注册时间/店铺），顶栏头像与「个人中心」改为站内路由；不再跨站跳商城 `/me`（那是另一套登录态，会显示商城账号） |
| 时间 | 后端统一 UTC 序列化，前端原先 `iso.replace('T',' ').slice()` 直接截串 → 早 8 小时。新增 `shared/lib/time.ts`（Asia/Shanghai，`formatBjShort/formatBjDateTime`），客服、私信列表、聊天页、通知中心统一改走它 |
| 客服图 | 商家端产物缺 `/img/products/*`（图池只在商城 public）→ 商家 vite `publicDir` 指向 `apps/mall/public`，两端同源同图 |
| 验证 | 新增 `scripts/auth-isolation-smoke.py` **11/0**（同罐两枚令牌并存、身份不同、商家不回退、商城回退正常）；`support-split-smoke.py` 22/0；Playwright 实测运营账号个人中心显示自身、客服账号可见 10 个会话、气泡时间 17:32（北京时间）、卡片 4 张 0 裂图 |

### 2026-10-21（四十六）· 客服消息独立化：自营/第三方分治，接待移到商家工作台

| 项 | 实现 |
| --- | --- |
| 数据 | 增量 37：`t_support_session`（唯一键 `seller_id+user_id`）+ `t_support_message` + 迁移台账；历史商品咨询/客服私信迁出并从 `t_social_message` 删除（本次 1 会话 / 2 消息，残留 0） |
| 后端 | 新子包 `com.duoduo.social.support`：`SupportService` + 用户侧 `/api/support/**` + 商家侧 `/api/seller/support/**`（`isSeller()` 准入，sellerId 由登录态推导）；旧 `/api/socials/support/**` 删除 |
| 区分口径 | **不用类型字段**：`sellerId == officialCsUserId` 即自营；第三方为商品归属卖家。同一套表与接口，只在 sellerId 分叉 |
| 规则 | 客服**移除**「非互关限一条」（60 秒 20 条频控保留）；普通私信保留；商品/闲置咨询豁免不变；闲置只走私信，不进客服 |
| 前端 | 商城新增 `/support`；商家工作台新增「客服信息」页签（`/seller/support`）；平台后台**不新增任何入口**；小程序新增 `pages/support`，商品「问一问」与「我的→问客服」改道 |
| 未读 | `UnreadSummary` 增加 `support`，总数 = 通知 + 私信 + 客服；单测同步（16/0） |
| 验证 | `scripts/support-split-smoke.py` **22/0**；反向验证（去掉角色校验）**21/1** 且总数不变；三端 `npm run build` 通过；小程序暗夜守卫 25/0 |
| 遗留 | ① `MessageService.BIZ_ASK` 老通道未清理（旧客户端兜底）② 店员（STAFF）暂不可用客服工作台（需成员域回源）③ 商家端会话列表暂无关键词搜索 |

### 2026-10-20（四十五）· A1 地址改造：三级联动 + 定位回填

| 项 | 实现 |
| --- | --- |
| 数据 | `shared/src/data/regions.json` 三级区划（31/342/2978，111KB），裁自公开 pcas 并**直辖市归一**（无「市辖区」）；`regions.ts` 动态 import 单列 chunk（gzip 26.5KB，按需） |
| 网页 | `Addresses.tsx` 三个 `<select>`：换省清市/区、换市清区；历史值不在数据内时自动补选项（编辑不清空） |
| 小程序 | 原生 `picker mode="region"`（**零数据包**，解决 2MB 主包顾虑）+ `wx.getLocation` |
| 定位 | 后端 `GeoController` 代理高德（Key 只在 `AMAP_WEB_KEY`）；网关 `/api/geo/**` 需登录、不进白名单；未配 Key 返回 `configured:false`（200），三条失败路径都不阻断填表 |
| 测试 | 新增 `address-cascade-ui-check` 22 项（含**懒加载**与**换省清空**断言）；`address-ui-check` 改 `selectOption` 逐级填，65 项；反向验证无效 Key → 200 + 字段 null；未登录 → 401 |
| 回归 | header-layout 109 / home 9 全绿；首屏 JS 235.4→239.3KB |

### 2026-10-20（五十一）· 新增「附近位置」快选入口（wx.choosePoi）

| 项 | 实现 |
| --- | --- |
| 动机 | 自动定位有偏差、地图选点要拖地标；`wx.choosePoi` 打开微信原生「所在位置」列表，点一下即带入，是三个入口里最快的 |
| 接口核实 | 官方文档确认 `wx.choosePoi` **未废弃**；与 `getLocation`/`chooseLocation` 同在 2022-04-18 起准入开通清单，需在「接口设置」单独申请，且 2022-07-14 后须在 `requiredPrivateInfos` 声明 |
| 定位 | **并列入口不替换**：POI 只能选"已存在的地标"，门牌号精度仍需 `chooseLocation` 拖地标 → 三个入口并存（定位 / 选点 / 附近位置） |
| 回调分支 | `type=2`（精确位置，经纬度有效）→ 走逆地理通道；`type=1`（仅城市，**经纬度不可用**）→ 只填市，省/区手动选，避免写成空省空区 |
| 改动 | `app.json` 加 `choosePoi` 声明；`address.js` 新增 `chooseNearbyPoi()`（预检 + `onLocateBlocked('附近位置')` + 取消静默）；`address.wxml` 加第三个按钮 |
| 守卫 | `check-miniprogram-location.mjs` 24 → **32/0**（新增 6 条 POI 断言）；反向验证：改坏 `res.type === 2` → 恰好 1 红 31 绿，恢复后 32/0 |
| 同轮订正 | 20:00 曾把 `chooseMedia` 误写入 `requiredPrivateInfos`（该字段只收 8 个位置类枚举）→ **模拟器启动失败**；23:55 移除并把守卫改为负向断言（枚举外不得出现任何值），32 → **35/0**，反向验证恰好 2 红 33 绿。chooseMedia 的合规出口是后台《隐私保护指引》声明（已声明），无需代码动作 |

### 2026-10-20（五十）· 修复：小程序定位 / 地图选点失败（隐私接口未声明）

| 项 | 实现 |
| --- | --- |
| 根因 | `app.json` 缺 `requiredPrivateInfos` 与 `permission["scope.userLocation"].desc`；未声明时 `wx.getLocation`/`wx.chooseLocation` 直接失败，与授权无关 |
| 声明 | 补 `requiredPrivateInfos: ["getLocation","chooseLocation"]` + `permission` 描述（注意键名带点） |
| 权限层 | `utils/location.js`：`ensureLocationAuth()` 三态预检、`classifyLocationError()` 纯函数 7 类、`describeLocationError()` 文案、`canOpenSetting()` |
| 兜底 | 失败统一 `onLocateBlocked()`：准确原因 + 必要时「去设置」并自动重试 + `pageScrollTo` 到 `.addr-region-field`；省市区 picker 不依赖定位，永远可用 |
| 守卫 | 新增 `scripts/check-miniprogram-location.mjs` **24/0**（声明/预检/收口/9 组 errMsg 分类/文案兜底） |
| 待你操作 | 公众平台申请「地理位置」接口权限 + 配置用户隐私保护指引（位置信息），真机必需 |

### 2026-10-20（四十九）· 修复：小程序绑定微信即被登出

| 项 | 实现 |
| --- | --- |
| 根因①（配置） | `WECHAT_SECRET` 与 `WECHAT_APPID` 不匹配：微信返回 `40125 invalid appsecret`，所有真实 wx.login 换取必失败 |
| 根因②（后端） | 微信失败一律映射 401 `UNAUTHORIZED`，与「未登录」不可区分 |
| 根因③（端侧） | `request.js` 见 401 即 `clearSession()` → 绑定失败导致登出 |
| 修复 | 新增 `WECHAT_CODE_INVALID`(400) / `WECHAT_CONFIG_ERROR`(503)，`WechatGateway.toException()` 纯函数分类；小程序只把真正 401 当登录失效，并按新码给专属文案 |
| 工具 | 新增 `scripts/wechat-config-check.mjs`（校验 AppID/Secret 配对，3 秒出结论） |
| 测试 | 单测 5/5；`wechat-auth-smoke` 26/0（新增 CODE_INVALID / CONFIG_ERROR 两支，断言"不得返回 401"）；`wechat-bind-smoke` 24/0 |
| 待你操作 | 微信公众平台重置 AppSecret → 更新 `.env` → 重启 user 容器 → 跑自检脚本 |

### 2026-10-20（四十八）· 网页端地图选点（高德 JSAPI 2.0）

| 项 | 实现 |
| --- | --- |
| 组件 | `apps/mall/src/components/MapPicker.tsx`：动态注入 JSAPI 2.0（首次打开才加载、单例）、360px 容器、可拖 Marker + 点图移动、确认回填 |
| 坐标系 | 地图坐标为 GCJ-02 ⇒ 确认后按 `coord=gcj02` 调逆地理（不能再转） |
| Key 分发 | 新增 `GET /api/geo/jsapi-config`（登录态返回 key + securityCode）；**不打进前端产物**，换 Key 只改环境变量重启；未登录 401 |
| 实测 | AMap 2.0 加载成功、容器生成 canvas、无 console 错误；确认 → 广东省/深圳市/龙岗区/花园坪C区一巷8号 |
| 降级 | 无 Key/超时/加载失败 → 明确原因 + 确认按钮置灰，可继续用候选或手选 |
| 测试 | `address-cascade` **30/0**（真实地图分支）；另有桩分支覆盖 Key 不可用时的集成逻辑 |
| 自查记录 | 最初把 Key 写成 31 位导致 `Error key!`，误判为域名未授权；换成 32 位即正常 —— 先校验凭据字面量 |

### 2026-10-20（四十七）· 定位偏差修复（坐标系）+ 附近候选 / 地图选点

| 项 | 实现 |
| --- | --- |
| 根因 | 浏览器给 WGS-84、高德要 GCJ-02；直传错位 589 米（深圳花园坪→龙城公园）、555 米（天安门→西长安街）。**不是接口数据问题** |
| 修复 | `CoordTransform`（纯函数）+ 5 项单测；`/api/geo/regeo` 增 `coord=gcj02\|wgs84`；网页传 wgs84、小程序传 gcj02（避免二次偏移） |
| 候选 | 逆地理 `extensions=all` 的 POI 作为候选，后端按距离升序取 8 条；前端「名称 · 距离」可点选；复用同次响应不额外扣配额 |
| 小程序选点 | 新增 `wx.chooseLocation`（原生地图、可拖地标、无需 Key、不占配额） |
| 网页地图选点 | **未实现**：需高德 JS API Key + 安全密钥（当前只有 Web 服务 / 小程序两种），已登记后续 |
| 测试 | 单测 5/5；`address-cascade` 23→26（候选数量 / 距离排序 / 点选生效）；address 65 / header 109 / home 9 全绿 |

### 2026-10-20（四十六）· 高德双 Key 落位（Web 服务 / 微信小程序）

| 项 | 实现 |
| --- | --- |
| 服务端 | `AMAP_WEB_KEY`（Web 服务类型）→ `.env` → compose → `duoduo.geo.amap-key`；仅 `GeoController` 使用，网页端「定位当前地址」生效 |
| 小程序 | `AMAP_MINI_KEY`（小程序类型）→ `scripts/miniprogram-amap-key.mjs` 生成 `miniprogram/config/amap.js`（gitignored）→ `libs/amap-wx.js`（官方 SDK 1.2.0）→ `pages/address` 逆地理 |
| 不混用 | 实测：Web Key + REST = 200；**Mini Key 裸调 REST = `USERKEY_PLAT_NOMATCH`(10009)**；Mini Key + SDK 平台参数 = 200 |
| 不硬编码 | 两个 Key 都只在 `.env`；代码无字面量；仓库只提交 `config/amap.example.js`；`--check` 模式可校验配置与 `.env` 一致 |
| 降级 | 小程序 SDK 失败（域名未加白名单等）自动回落 `GET /api/geo/regeo`（后端 Web Key），定位不中断 |
| 待你操作 | 微信公众平台 → 服务器域名 → request 合法域名 加 `https://restapi.amap.com` |

### 2026-10-20（四十四）· 修复：头像面板缩放动画从未生效

| 项 | 实现 |
| --- | --- |
| 根因① | 条件渲染 + 展开时 `setMenuState('open')` 与挂载同一提交 → 元素首帧即带 `.is-open`，浏览器无「起始计算样式」，transition 不触发、`getAnimations()` 为空。实测 +60ms 即 `opacity=1 / transform=none / anims=[]` |
| 根因② | `'closing'` 期间仍挂 `is-open`（判据 `!== 'closed'`）→ 移开后先等 200ms 才摘类，淡出被推迟，观感"愣一下再消失" |
| 修复 | 面板**常驻 DOM**，只用宿主 `is-open` 切显隐（closed 态真实存在）；三态机简化为布尔，关闭不再需要定时器，仅保留 80ms 进入意图延迟 |
| 参数 | 按录屏校准：ffmpeg 抽 60fps + 以悬停前帧为基线差分 → 约 150–200ms 淡入＋轻微放大、无位移、**无过冲** ⇒ `scale(0.88)` / `--dur-base` / `--ease-out`（弃用 `--ease-spring` 的 1.32 过冲） |
| e2e | 103 → **109 项**：新增 rAF 连续采样（≥3 个缩放中间帧 / min scale ≤0.92 / 透明度中间值 / 单调递增至 1）；「已关闭」判据由 `count()` 改可见性+可交互性（常驻后 count 恒 1，否则假绿）；address 同步 |
| 反向验证 | 产物 CSS 改 `scale(.88)`→`scale(1)`：恰好 4 红 / 105 绿 / 总数仍 109；恢复后哈希一致、复跑 109/0 |
| 回归 | address 65 / my-listings 52 / notice-chat-follow 50 / admin-entry 74 / motion 22 / home 9 全绿 |

### 2026-10-20（四十三）· 头像面板缩小 ＋ B 站式缩放动画

| 项 | 实现 |
| --- | --- |
| 尺寸 | 面板宽 300→256px；头部竖排→**横排**（头像 64→44px 左、昵称/身份右，B 站同构）；条目 padding 9→6px、字号 14→13px、图标 20→18px；整体高度约省 1/4 |
| 动画 | 起始态 `scale(0.97)`→**`scale(0.88)` 且去掉位移**，origin top right 不变 —— 从右上角纯缩放「长出来」；展开曲线 `--ease-out`→`--ease-spring`（轻微过冲，**四十四轮按录屏改回 `--ease-out`**：B 站无过冲）；收起对称收回；时长保持 `--dur-base` 200ms |
| e2e | ②b 段 3 项：transform 复位 / 展开引用 `--ease-spring` / 收起态规则含 `scale(0.88)`。曲线与缩小态读**样式表规则**而非 computed style（cubic-bezier 自带逗号会切碎按逗号解析；面板卸载后无计算样式可读）。修测试盲区：Esc 后指针停按钮上 `mouseenter` 不重发，重开需先移开再移回。全套件 100 → **103 项全过** |
| 回归 | my-listings 52 / notice-chat-follow 50 / admin-entry 74 / motion 22 全 EXIT=0 |

### 2026-10-20（四十二）· 顶栏头像改版：B 站式悬浮个人面板

| 项 | 实现 |
| --- | --- |
| 触发 | `button.icon-btn.avatar-btn` 42px 正圆只放头像；昵称移到 `aria-label`（读屏与 e2e 定位不回退） |
| 状态机 | `closed/open/closing` 三态：悬浮 80ms 意图延迟展开；离开 200ms 淡出（`closing`）再卸载，期间回来即取消；定时器挂 ref。点击＝打开（幂等）非 toggle —— 悬停已展开时再 toggle 会立即误关 |
| 桥接 | `.user-entry::after` 12px 透明判定区盖住 10px 视觉间隙（左右外扩 8px），穿越不闪断 |
| 动画 | opacity + translateY/scale + visibility 过渡，全部走动效令牌（`--dur-base`/`--ease-out`）；visibility 延迟生效实现「淡出完才不可交互」 |
| 内容 | 大头像 64px + 昵称 + 身份/手机掩码 + 四组条目（图标+文案+角标+箭头）+ 退出登录 |
| 定位 | 面板锚宿主右缘向左展开（贴右边缘不溢出）+ max-width 兜底；z-index 90 |
| ARIA | 刻意不用 role=menu/menuitem（会覆盖 link 角色，既有 getByRole('link') 全失效） |

**e2e**：`header-layout` 88 → **100 项**（新增 ⑤b 12 项：圆形无昵称 / 悬停展开 / 桥接穿越 /
内容完整 / 先淡出再卸载 / 快速进出终态正确；「昵称不折行」→「头像按钮正圆」）。
适配三处旧断言：`admin-entry` 登录证据改 aria-label；`my-listings` 关闭改 Esc；
`notice-chat-follow` 的 `/私信（\d+）/` 为 2026-10-18 角标化时漏改的旧正则，改结构化断言。

**回归**：header-layout 100/0、admin-entry、home、address、my-listings、live-refresh、
notice-chat-follow、motion、seckill 97、pagination-search 全绿；
share-circle「删除后列表消失」为基线已复现的历史失败（记待查）。

### 2026-10-20（四十一）· 三端动效与渲染性能统一

**评估基线**（改造前实测）：2432 行样式表仅 14 处 transition / 6 处 keyframes；
时长散落 0.15/0.16/0.18/0.2 四种；`.btn` 是 `transition: all`（每帧重排的反模式）；
`prefers-reduced-motion` 全仓仅 1 处；27 个 `<img>` 仅 1 处 `loading="lazy"`。

**改动清单**

| 层 | 内容 | 文件 |
| --- | --- | --- |
| 令牌 | 时长 5 档 / 缓动 3 曲线 / 组合过渡 9 条（含 `--t-press`） | `packages/shared/src/styles.css` `:root` |
| 替换 | 14 处裸 transition → 令牌；消灭 `transition: all` | 同上 |
| 微交互 | 按压回弹（80ms）、纯色按钮按压压暗、卡片图片缓推、表格行过渡、`.card-hover`、`.route-enter`、`.stagger`（前 8 张错峰、封顶 224ms） | 同上 + 三端入口组件 |
| 降级 | 全局 `prefers-reduced-motion` 收口：压到 0.01ms（非 `none`）+ 位移归零 | 同上 |
| 渲染 | 25 处 img 补 `loading="lazy"`+`decoding="async"`（顶栏头像/详情主图保持 eager）；`.goods-card` `contain: layout style`；`will-change` 0 处；预留 `.virt-list` | 三端 + `shared/components/ui.tsx` |

**两条硬约束（写进代码注释，违反即坏）**

- `.route-enter` 只能包页面内容容器；包到带 `position: sticky` 顶栏的外层会让 sticky 退化。
- 入场动画填充模式必须 `backwards`；`both` 会把 transform 钉死，`:hover` 从此失效。

**体积 A/B（同构建、vite 口径）**：mall JS 233.68→234.37KB、CSS 32.57→35.88KB；
admin 123.11→123.28KB；merchant 85.03→85.20KB。

**回归与验证**

- 新增 `scripts/e2e/motion-ui-check.mjs`（22 项）：令牌齐全 / 引用令牌 / 全站 `transition:all`=0 /
  悬浮与图片缓推实测 / route-in 填充模式 backwards / 懒加载占比 / containment /
  表格行过渡 / **reduced-motion 下时长 <5ms 且位移归零**。
  反向验证：容器内把 `--dur-fast` 改 0.19s → 恰好 1 红 21 绿、总数不减。
- 三端 tsc + build 全绿 → 镜像重建重启 healthy → 13 个受影响 e2e EXIT=0
  （home / header / pagination-search / seckill 97 / seckill-price / product-detail /
  my-listings / aftersale / review / seller / admin-charts / live-refresh / admin-entry 74）。
- 静态守卫 13 项全绿；1440/1180/900 截图复核布局无破坏。
- **与本轮无关的历史失败**（基线复现确认）：`share-circle`（删除后列表消失）、
  `product-audit`（发布后无「正在等待平台审核」）→ 记入待查；`coupon` 缺夹具前置。

**顺手修的历史遗留**：`admin-entry-ui-check.mjs` ① 仍点已删除的「查询」按钮、用旧占位符
（上轮搜索统一后输入即搜）→ 已适配；② 夹具昵称固定导致同名账号越攒越多（搜索恒多行、
ADMIN 恒超 2 个、并发不变式走不到）→ 昵称带跑批标记 + 启动自愈清理（清了 4 个遗留 ADMIN）。

### 2026-10-19（四十）· 秒杀添加商品功能修复（搜索污染根因 ＋ 移除手输 SKU ID ＋ 表单布局）

**用户反馈**：首次用「犬」搜索商品可正常返回多个结果；选择其中一个作为秒杀商品后，再用「犬」搜索
**只剩已选商品**，其他匹配商品消失；切换关键字为「猫」也无法返回相关商品。要求：定位搜索条件污染 /
状态未重置 / 组件复用的根因，明确影响范围，给出修复方案与验证步骤；同时**移除手动输入 SKU ID 的能力**
（改为仅通过商品搜索选择）并同步校验、提交数据与界面提示；最后优化表单布局（标签、输入框、按钮和
错误提示对齐、间距统一、适配常见分辨率）并给出验证步骤。

**① 根因：UI 状态机靠「数组空不空」推断，必然漏重置**
- 选商品面板原来用「当前钻取的 SPU 数组（`pickSpus`）空不空」判断该显示搜索结果还是某个商品的规格列表；
  而 `searchProducts()` 只更新 `pickResults`、**不清 `pickSpus`**。于是**一次「看规格」之后，任何后续
  搜索都仍渲染上一个商品的规格列表** —— 用户看到的就是「选了商品后再搜，只剩已选那个；换关键词也不变」。
- 影响范围：仅「新建秒杀活动」弹窗的选商品助手（编辑弹窗没有这个面板）。`openCreate` 未重置
  `pickKeyword`/`pickResults` 等还加剧了跨次污染。

**② 修复：显式两态 + 整体复位**
- 删除 `pickSpus` state，改显式 `pickStage: 'search' | 'sku'`；`searchProducts()` 一进来先
  `setPickStage('search'); setPickSkus([])`；`loadSkus()` 设 `'sku'`。
- 新增 `resetPicker()`（关键词/结果/钻取/错误/busy/阶段全部复位），`openCreate` / `closeModal` /
  `openPicker` 三处调用 —— 开弹窗、换一行、重新打开都从干净态开始。
- `applySku()` 回填 `skuTitle`/`skuSpec`（仅回显用），并把面板复位到搜索态。

**③ 移除手输 SKU ID（同步校验 / 提交 / 提示）**
- 删除 `<Field label="SKU ID">` 输入框（placeholder「19 位 ID，可用右侧『选商品』回填」）。
- 行内改为 `.sk-picked` 已选回显卡：商品名（title 全文）+ 规格 + 可售库存 + 现价 + SKU；
  未选时给「尚未选择商品」引导 + 「选商品」按钮，选中后按钮变「重新选择」。
- `save()` 校验文案：`第 N 个商品（SKU xxx）` → `第 N 个商品（商品名）`；空列表提示
  「请先通过『选商品』添加至少一个秒杀商品」。提交数据结构不变（仍是 `skuId`，只是来源改了）。

**④ 表单布局对齐**
- 秒杀价/原价/配额/限购与两个时间字段统一走 `.sk-row`
  （`grid-template-columns: repeat(auto-fit, minmax(150px, 1fr))`）：同一容器宽度共用列轨道，
  标签与输入框跨行对齐；不写死断点，窄屏自动收敛列数。
- `Modal` 新增可选 `wide`（860px）：新建弹窗字段多，520px 会把输入框挤成锯齿。
- 校验错误从页面顶部改渲染在**弹窗内**（`actionError` 原来被模态遮罩挡死，运营点「创建」
  只看到「什么都没发生」，会以为按钮坏了）。

**⑤ 验证（含用户点名的三个场景）**
- `seckill-ui-check` 新增「选商品助手」段 **21 条**（76 → **97/0**）：
  - 首次搜索：搜「犬」返回 ≥2 个商品，且是商品卡片形态（带「看规格」入口）；
  - 选择商品后重复搜索：看规格 → 用这个 → 重新打开 → 再搜「犬」仍返回 ≥2 个商品，
    且停在搜索结果态（没有残留上一个商品的规格列表）；
  - 切换关键字：改搜「猫」返回猫相关商品，且不含已选商品（等结果时按标题含「猫」判定，
    避免拿到请求在飞的旧数据下结论）；
  - 无手输 SKU ID：无「SKU ID」标签框、无「19 位 ID」提示框，空态有引导文案；
  - 表单：网格轨道、标签与输入框左边缘对齐、四个输入框等宽（精确定位到含「活动配额」的行，
    弹窗里有两个 `.sk-row`）；
  - 错误路径：**重开一个全新弹窗**（当前弹窗已选商品、表单合法，直接点创建会真建数据），
    只填标题就创建 → 错误提示在弹窗内、页面顶部无残留；
  - 重开弹窗时选商品状态已重置（`resetPicker` 的另一面）。
- `seckill-price-ui-check` 适配新流程 18/0（旧脚本点的「选商品回填」按钮已删除，不适配必挂 ——
  UI 重构后必须全局 grep 旧 locator）。
- `pagination-search-ui-check` 51/0；静态守卫 3/3；三端 `tsc` + `vite build` 全绿；
  admin 镜像重建 healthy。
- 宽/窄视口截图（1440 / 1180 / 900）：网格均 4 列、弹窗 `scrollWidth == clientWidth` 无溢出。

**⑥ 过程中两个测试自伤（已修复并记入铁律 M 组）**
- 在**已选商品**的弹窗里点「创建」会真的把活动建进库（两次跑批各误建一条
  `【UI自测】选商品助手 …`，手工 DELETE + 查库自证 0 残留）。错误路径必须用全新弹窗。
- 弹窗里有**两个** `.sk-row`，`.sk-row .field` 把两行字段混数 → 等宽断言假红。
  改用 `filter({ hasText: '活动配额' })` 精确定位，并加「定位唯一」前置断言。

### 2026-10-19（三十九）· 秒杀页布局与交互修复（用户截图反馈四项）

**用户原话指向的四个问题**：①「新建活动」按钮位置不合理、被挤到页面顶部；②「全部/进行中/未开始/
已结束/已下架」这些**筛选按钮**无法点击；③ 时间格样式不统一（前半黑后半灰）；④ 阶段/开关控件全部重叠。
另附两条通用约定：文字超出容器用省略号 + 悬停提示；内容超出可视区要能横向滚动。

**① 按钮位置**
- 原来按钮与 `<h2>` 同在标题行（`row-between row-wrap`），窄屏下按钮被挤到页面最顶部、离列表老远。
  改为：标题行只放标题；「+ 新建活动」移到**筛选行右侧**，正对着下面的表格。
- 空态文案同步从「点右上角」改为「点右侧」。

**② 筛选从「假按钮」变成真按钮**
- 根因：那五个数字原来是 `<span className="seckill-stat">`，**纯展示、没有 onClick** ——
  「看起来能点却不能点」比没有更糟。改为 `<button className="seckill-filter">`，带
  `aria-pressed` 选中态与 `title` 说明，点击真的过滤列表（与关键字搜索叠加）。
- 新增 `PhaseFilter` 类型（`ALL/ONGOING/UPCOMING/ENDED/OFF`）。注意 **`OFF`（已下架）与三个 phase
  不是同一维度**：phase 由时间推出，enabled 是运营开关，「上架 + 已结束」完全正常，所以是五个并列口径。
- 计数一律按**未筛选的全量**算：跟着当前筛选变的话，点「已结束」后数字自己缩水，看着像数据错了。
- 空态文案按筛选口径分别给出（「没有未开始的秒杀活动」+「切换到全部看看」），不是空白页。

**③ 时间格同色**：两段统一 `var(--text-2)`，去掉第二段的 `.muted`；`至` 用 `.sk-time-sep` 稍淡但同色系，
整格 `nowrap` + `title`（`start 至 end`）。

**④ 阶段/开关不堆叠**：`.row row-wrap` 在窄列下会把两个标签换行成上下两行，观感就是「重叠/堆叠」。
改为 `.sk-cell-tags`（`flex` + `flex-wrap: nowrap` + `gap:6px`）。

**两条通用约定的落地**
- **省略号 + title**：活动标题 `.sk-activity-title`、秒杀商品每行 `.seckill-admin-skus li`
  都是 `overflow:hidden; text-overflow:ellipsis; white-space:nowrap`，且元素上带 `title` 全文。
  商品行由「折成三四行」改为「一行放完 + 省略号」——折行会把行高撑爆，正是"挤在一起"的观感来源。
- **横向滚动**：`.table-wrap` 本来就有 `overflow-x:auto`，但全局 `.table-wrap > .table`
  的 `min-width:720px` 把 6 列全压扁了，根本滚不起来。新增
  `.table-wrap > table.seckill-admin-table { min-width:1240px }`。
  ★ **选择器必须比全局规则更具体**：第一版写成单类 `.seckill-admin-table`（特异性 0,1,0），
  被 `.table-wrap > .table`（0,2,0）**静默压掉**，页面不报错、只是列又被压扁 ——
  是 e2e 的 `scrollWidth > clientWidth` 断言把它揪出来的。

**验证**
- `seckill-ui-check` 新增「布局与交互」段 **12 条**（65 → **76/0**）：按钮在页签下方的位置关系、
  筛选点击后 `aria-pressed` / 列表真的只剩该阶段 / 进行中的活动被筛掉 / 空态文案 / 时间两段同色
  （`getComputedStyle` 比对）、两个标签同一行且不重叠（boundingBox）、标题与商品行的
  「title + ellipsis」样式契约、`scrollWidth > clientWidth && overflow-x:auto`。
- `pagination-search-ui-check` 51/0（原 49）：改写了依赖旧 `.seckill-stats` 类名的那条，
  并补「筛选 chip 是真 button（5 个）」与「按钮在页签之后」两条 DOM 顺序断言。
- 回归 7 套全绿：秒杀价 18/0、售后 44/0、我的闲置 52/0、商家端 40/0、店员 24/0、审核 SLA 56/0、举报台 32/0。
- 三端 `tsc` + `vite build` 全绿；admin 镜像重建后 healthy；静态守卫 3/3。

### 2026-10-19（三十八）· 秒杀页三端重构 ＋ 全站分页/搜索统一

**用户诉求（原话要点）**：秒杀活动页（用户端/商家端/平台后台）修元素重叠、隐藏秒杀活动 ID、时间去掉 `T`、
明确「余量」= 秒杀活动自身剩余库存；三端所有列表补数字页码（1、2、3…）＋ 上一页/下一页；
我的订单、我的售后、我的闲置、平台后台、商家端等缺失搜索处补搜索，并保持交互一致。

**① 秒杀页（后台重构为主）**
- 布局拆三段：标题行（标题 + 新建按钮）／`.seckill-stats` 统计条／`.list-toolbar`（左搜索、右口径说明），
  不再是「标题、按钮、筛选挤在一行」——重叠的直接成因。
- **活动 ID 全删**（列表列、编辑弹窗、删除确认弹窗）：19 位数字对运营无信息量，只会挤爆单元格。
- 时间列统一 `utcToBj(iso).replace('T',' ')`：**服务端仍下发 ISO**（契约不动），只在展示层去掉 `T`。
- 「余量」定死为**秒杀活动自身剩余库存**：`sum(availableStock) / sum(totalStock)`，表头写「活动余量」，
  配 `SeckillRemainStat`（大号数字 + 进度条），`title` 明示「不是商品 SKU 库存」。详情列不再混用 SKU 库存。
- 统计条把「进行中／待开始／已结束」与总活动数并排，页面顶部一眼可读。

**② 分页统一（三端 27 处）**
- `packages/shared/components/ui.tsx` 重写 `Pagination`：**上一页 ＋ 数字页码 ＋ 下一页 ＋ 共 N 条 · 第 x/y 页**。
  页码用 `pageWindow(page, pageCount, span=2)` 窗口法：始终含首页与末页，中间按当前页 ±2，空隙插 `…`
  （后台有几百页的列表，平铺会撑爆工具栏；只给前后翻页又违背「直接跳页」的要求）。
- 替换 4 处手写分页（我的闲置 / 关注 / 我的分享 / 通知），并统一**渲染条件**为「有数据就渲染」——
  原先多处写成 `total > pageSize` 才渲染，导致「共 0 条」时连总数都看不到，三端观感不一致。
- 数字页码按钮带 `aria-current="page"`，容器是 `nav[aria-label="分页"]`（可被 e2e 直查）。

**③ 搜索统一（用户端 3 + 后台 12 + 商家端 4）**
- 新增 `SearchBar`（右侧 × 清空）＋ `useDebounced(350ms)`：**输入即搜**，Enter 立即生效；
  不再要求点「搜索」按钮——列表搜索是持续筛选，多一次点击只会让人以为没生效。
- **一律服务端筛**：分页存在时前端过滤只能看到当前页，必然漏。后端 10 个接口新增 `keyword` 参数
  （全部保留无关键字重载，旧调用不破）：订单（订单号 / 商品标题子查询）、售后（售后单号 / 订单号）、
  我的闲置、后台订单、后台售后、后台商家、后台成员、后台审核样本、后台商品审核、后台举报、商家订单/售后/结算。
- 参数化一律走 `.apply("... LIKE {0}", "%"+kw+"%")`，不拼串；结算空命中用 `eq(id, -1)` 而不是提前 return，
  保证「汇总金额」仍按真实数据算（提前 return 会连汇总一起清零）。
- URL 搜索词用 `setParams(next, { replace: true })`：避免每敲一个字 push 一条历史。

**④ 修掉的两个真缺陷（本轮实测暴露）**
- **`selectBatchIds(空集合)` → `IN ( )` → 500**。后台成员接口按关键字筛出 0 条时，
  `merchantMapper.selectBatchIds(empty)` 生成 `WHERE id IN ( )`，JSqlParser 直接解析失败，
  接口 500 而不是返回空页。已给 `MerchantStaffService.adminPage` / `MerchantOnboardingService.loadOwnerPhones`
  加空集合守卫（空集合走 `Map.of()`），并复查全仓其余 `selectBatchIds` 调用均有守卫。
  **教训：空结果是正常业务态，不是异常态；一条「查不到」的路径必须真跑一次。**
- **e2e 清理脚本库名写错**：`t_after_sale` / `t_logistics_track` 在 `duoduo_fulfilment`，不在 `duoduo_trade`。
  写错的后果不是「少删两张表」，而是**清理段中途抛错 → 后面的表与账号全不删 → 夹具整批留在库里**。
  改为按实物库名删除，并给每条删除加容错（`del()` 记录 WARN 不中断），最后用自证查询兜底。

**⑤ 验证**
- 新增 `scripts/e2e/pagination-search-ui-check.mjs`：接口 + 真实 Chromium 双轨，**49 项全绿**。
  覆盖：11 笔订单凑 2 页 → 数字页码 1/2 可点、点 2 换页、上一页回退、搜索收敛到 1 条且页码回 1/1、
  乱码空态文案；三端「有搜索框 / 有分页条」；秒杀页无 19 位 ID、无 `T`、有活动余量列与进度条、布局不重叠；
  跑完逐表删除 + 自证 0 残留 + SKU 冻结库存回补。
- **401 判定收紧而非放宽**：未登录时前端会探活 `/api/users/me`、`/api/cart/count`、`/api/auth/refresh`，
  这些 401 是「没登录」的正常答复。原先的「无 4xx/5xx」会把它们报红（假红），
  一律放行又会漏掉真问题。改为**按请求是否携带 `dd_at`/`dd_rt` Cookie 区分**：
  无 Cookie 的 401 计数忽略，**带 Cookie 仍 401 照样报红**。
- 回归：`seckill-ui-check`、`seckill-price-ui-check` 18/0、`aftersale-ui-check`、`my-listings-ui-check`、
  `seller-ui-check` 40/0、`merchant-staff-ui-check`、`audit-sla-ui-check` 56/0、`share-report-ui-check` 32/0。
- 三端 `tsc --noEmit` + `vite build` 全绿；`duoduo-user` `mvn -o -pl -am package` 通过；
  四镜像重建（user / web / admin / merchant）后容器 healthy。
- 门禁提醒：`run-ui-checks.ps1` 需带 `-UiBase http://localhost:8088`，否则套件默认打 `localhost:5173`（Vite dev），
  没起 dev server 就会以「navigating to ... 5173」整体失败——**这是基址没传，不是功能回归**。

### 2026-10-19（三十七）· 秒杀价规则（< 原价、> 0.01 元）＋ 时间双重换算修复

- 后端 `SeckillService.createActivity`：`seckillPrice <= 1` 分拒（>0.01 元）；`seckillPrice >= 原售价` 拒
  （**严格小于**）。原售价取「将要落库的值」（`originalPrice ?? snap.price()`），校验与存储同一数字。
  新增 `yuan(cents)` 仅用于报错文案（库里是分，避免把 12600 读成一万二千六）。
- 前端 admin 秒杀页：输入 `min=0.02` + 随原价变化的 hint；保存前逐行校验（分整数比较）并点名 SKU；
  回填默认价夹在 `(0.01 元, 原价)` 区间；**校验错误从页面顶部移入弹窗**（原来被遮罩挡住=点了没反应）。
- ★ 同修真 bug：前端已 BJT→UTC，后端自 `f0e835c` 起把裸串按北京时间解析 ⇒ **双重减 8 小时**。
  改为前端提交带 `+08:00` 的 ISO 串，服务端走「有时区按绝对时刻」分支，只换算一次。
- 验证：`seckill-smoke.ps1` §5.6b 新增 7 条 → **89/0**；新增 `seckill-price-ui-check.mjs` → **18/18**；
  **反向验证**：摘掉后端校验后「= 原价」由 400 变 **200**，恢复后回 400。
  兼容：冒烟原「秒杀价 250 元 > 现价 129 元」的配置本身违反新规 → 改 120 元，券叠加断言随之改算。
  `seckill-ui-check` 65 项回归全绿；夹具全清（活动/账号/SKU 孤儿行均 0）。

### 2026-10-19（三十六）· 实时刷新铺到订单/售后 ＋ 后端校验接口级用例

接（三十五）列出的两个未完成项：

- 补 2：`hooks.ts` 新增 `useLiveRefresh`（dataBus + visibilitychange + 仅可见时的兜底轮询，
  回调走 ref 持有避免重复订阅）与 `useAsync.reloadSilently`（不置 loading、不清 error，不闪骨架屏）；
  `reload()` 顺手修正为清掉残留的 silent 标记（否则紧跟静默刷新的「重试」会没有加载反馈）。
  接入 `OrderDetail`（订单+结算两路）、`Orders`、`AfterSales`、`AfterSaleDetail`。
- 连带：未支付/已关闭订单不再请求 `/settlement`（本就无托管记录，必然 404；加轮询后会变成每 30s 一次 404）。
- 补 1：临时 OPERATOR 直打接口四条用例 —— 超库存 400 / 限购>配额 400 / 限购 11>配额 5 400 / 合法 200；
  被拒请求零落库，夹具全清（活动、账号、令牌均自证 0）。
- 验证：新增 `scripts/e2e/live-refresh-ui-check.mjs`，浏览器 **21/21**；**反向验证**摘掉列表页钩子
  → 恰好 2 条红、其余 19 条绿 → 恢复后 21/21；过程中修掉一条**假绿**（「已关闭」是筛选栏文案，
  整页文本判定恒真，已改为只读订单卡片上的状态标签）。秒杀 UI 65/65、售后全链路、两个静态守卫均绿。
- 附带修正：`seckill-ui-check.mjs` 的时间夹具改为显式带 `Z` —— 北京时间口径上线后，
  不带时区的串会被当成北京时间，原来的 UTC 串会整体偏 8 小时（表现为活动「8 小时前已结束」、按钮 disabled）。
  后端 `parseActivityTime` 两种写法都支持（带时区按绝对时刻，不带按北京时间）。

### 2026-10-19（三十五）· 秒杀五问题修复 ＋ 全站实时刷新统一方案

- 统一方案：`shared/lib/dataBus.ts` 全站数据变更广播（写操作成功 notifyDataChanged，秒杀 Provider 与
  商品详情订阅重拉；原有 60s 轮询/visibilitychange 兜底不变，广播只把延迟降到 0）。
  写点：下单、支付成功×2、买家取消（详情+列表）、秒杀抢购成功。
- ① 取消订单秒杀状态即时刷新（根因＝取消不触发任何刷新，只有 60s 轮询）。
- ② 详情页 `seckillOver`（配额抢完或被限购）→ 秒杀条保留、价格与加购/立即购买退回原价口径；
  规格按钮配额抢完不再挂秒杀价。秒杀配额与 SKU 库存两套口径从此各走各的。
- ③ 后台时间改北京时间口径（输入/列表/编辑 + 提交 ±8h 换算 UTC），新建默认当天 00:00–23:59。
- ④ 选商品回填带出当前可售库存并展示；配额 max=库存、限购 1~min(10,配额)；保存前逐行校验；
  后端 createActivity 同规则硬校验（超库存/限购超配额 400）。
- ⑤ 回填价格单位修复：sku.price 是分，回填 ÷100（15800 分→158 元；秒杀价八折 126.4 元）。
- 验证：浏览器 15/15；trade 编译过；三端 typecheck/build/镜像重建；3 个静态守卫绿；夹具已清理。

### 2026-10-19（三十四）· 头像交互打磨：单入口 + 右上角即时同步

- 顶栏此前不渲染头像（只有 👤）——「上传后右上角不变」的根因；现改为有头像渲染 user.avatar 圆图。
- 头像选完即存：上传/贴地址/点 × 立即 PATCH + refreshMe，右上角无需刷新同步（''=清除）。
- ImageUploader 单图字段单入口：已有图不再渲染上传方块，缩略图可点重选 + 悬停遮罩文案
  （头像「编辑头像」，其余「更换图片」）；文件选择框挪到根节点。
- 连带修：Me 表单回填 effect 依赖 [user]，refreshMe 换引用会把编辑中的昵称冲掉 → 只在换账号时回填。
- 验证：浏览器 9/9（含清除落库、地址路径同步）；对 Logo/商品主图等 max=1 字段一致生效。

### 2026-10-19（三十三）· 图片上传全面重构：统一「本地上传 + URL 上传」

- 技术栈确认：React 18 + Vite + TS + 自研组件库（非 Vue / 无 Element Plus、antd），**无第三方上传组件可复用**；
  评价晒图那套是页面私有逻辑，故抽成共用组件。
- 新增 `packages/shared/src/components/ImageUploader.tsx`：缩略图列表 / 本地多选 / 地址转存 / 删除 /
  失败点名 / 上限 / 圆形 / `onBusyChange`；本地 → `/api/uploads`，http(s) → `/api/uploads/from-url`，
  `/` 开头本站路径原样采纳（商家历史商品图）。
- 接入 7 处：商城头像、售后举证、闲置发布、评价晒图（删除页面内 150 行自写实现）、后台品牌 Logo、
  商家发布商品（主图 + 详情图）、商家编辑商品（`imagesText` → `images: string[]`）。
- 修两处真实缺陷：① `max=1` 时选新图应**替换**而非「再加一张」（原来已有头像时点选图无反应）；
  ② 同一表单两个上传器共用一个布尔量会被覆盖 ⇒ 改为按字段记 `{main, detail}`，任一在传即忙。
- 验证：三端 typecheck + build 通过；浏览器 15/15（头像落库回显、闲置发布、品牌 Logo、商家发布弹窗）；
  4 个前端静态守卫全绿；售后举证与商家编辑弹窗无夹具，仅类型/构建覆盖。

### 2026-10-19（三十二）· 修复秒杀页与首页封面图不一致

- 根因：占位图按 id 种子从 12 张图池选图；秒杀列表把 skuId 当 spuId 位传给 skuImage ⇒ 种子不同选到不同图。
- 修：Web Seckill.tsx 传 spuId；小程序 seckill.js / index.js 同型问题（传 null 回退 skuId）一并修。
- 验证：首页栏目图 = 秒杀页图 = 详情页主图 = p05.jpg，三处一致。

### 2026-10-19（三十一）· 全站秒杀标识与价格 + 首页「每日秒杀」栏目

- SeckillProvider 全站唯一秒杀数据源（拉 activities 按 SPU 建索引，60s 轮询 + 回前台刷新，失败保旧值）。
- 统一组件 SeckillTag / SeckillPrice（秒杀价 + 原价划线标注）/ SeckillCountdown（走秒，到点触发刷新）/
  SeckillProgress；接入商品卡、详情页、秒杀列表、AI 推荐卡、分享圈商品卡。
- 详情页秒杀规格走「马上抢」（seckill place），隐藏加购与数量（秒杀按件抢）；秒杀规格不因商品库存被禁选。
- parseServerTime/formatServerTime：无时区服务端时间按 UTC 解析（浏览器默认本地 → 早 8h）。
- 首页 DailySeckill 栏目：hero 正下方，时间 + 秒杀价/划线原价 + 进度 + 倒计时，无活动不渲染。
- 后端：seckillPrice 补进金额白名单（此前字符串下发）+ 回归测试 seckillPriceStaysNumeric。
- 验证：浏览器 18 断言全过（倒计时实测走秒）；seckill-smoke 82/0；trade 107/0；白名单 5/0；前端三守卫绿。

### 2026-10-19（三十）· 修复秒杀列表「商品不存在」：SKU / SPU 主键混用

- 根因：秒杀标题链接 `/product/${sku.skuId}`，商品详情按 SPU 寻址 ⇒ `/api/products/2001` 404；
  列表数据本身正常（按 skuId 取快照），所以「看得到、点不开」。
- 修：SkuView 下发 spuId（SkuSnapshot 已有）；前端 types 增 spuId、列表按 spuId 跳转，null 时标题不可点。
- 守：新增 `scripts/lib/check-product-link-spu.mjs`（禁止用 skuId 拼 /product/ 链接，扫描数 0 判红）并接入 check-all。
- 验证：404/200 双地址对照；浏览器 5 个链接均 /product/1001 且详情正常；守卫反向注入恰好红 1 处；冒烟 82/0。

### 2026-10-19（二十九）· 秒杀紧急修复（支付入账 + 时区）

- completePayment 对 SECKILL 跳过冻结台账扣减（秒杀单此前永远无法支付）。
- 活动时间时区感知解析（naive 输入按 Asia/Shanghai），place 窗口校验自此与展示一致；存量活动 -8h 修正。
- 冒烟 82/0；交易单测 107/0。

### 2026-10-18（二十八）· 微信支付接入（Native/JSAPI/H5）+ 二维码修复

- 新增 payment/wechat：Config / Crypto（纯 JDK 签名验签解密）/ ChannelBase + 三渠道（@ConditionalOnProperty）。
- SPI：PrepayCommand 增 openId、clientIp；PrepayResult 增 prepayId；新增 refund default 方法。
- PayRequest.channel 可空（走 duoduo.pay.default-channel），新增可选 clientIp；ErrorCode 增 PAYMENT_CHANNEL_ERROR。
- 二维码：自研矩阵不合标 ⇒ 改用 qrcode 库；pyzbar 解码实测通过。
- 沙箱与 MOCK 全部保留，仅靠环境变量切换。
- 验证：WechatPayCallbackTest 8/8；交易模块 107/0。联调待商户凭据 + 公网 HTTPS 回调地址。

### 2026-10-18（二十七）· 已关闭秒杀订单不再占用限购名额

- 根因：限购由 Redis 计数判定，关单回补只回余量不回个人计数 ⇒ 计数只增不减，名额被占死。
- 修法：RESTOCK_LUA 关单同时回吐个人计数；预扣遇「超限」先查 DB 未关闭单数仲裁，未达限则回吐自愈重试。
- 口径：退款订单仍计入限购（确曾成交）。
- 验证：seckill-smoke 76/0（新增 7 条：抢→关单→同用户再抢成功→未关闭仍占名额→收尾）。

### 2026-10-18（二十六）· 售后类通知直达售后详情页

- 根因：AFTER_SALE_* 通知的 biz_id 是订单号、jump_page 是小程序售后列表页 ⇒ 网页只能落到订单详情。
- 修法：推送侧 biz_id 改存售后单 id（8 个方法加 afterSaleId 参数）；NoticeWebPaths 对 AFTER_SALE_* 生成
  /after-sales/{id}；存量 87 条通知按「订单号→最新售后单」回填。
- 验证：NoticeWebPathsTest 5/5；履约单测 20/0；浏览器点通知直达 /after-sales/{id}。

### 2026-10-18（二十五）· 头像菜单新增「我的售后」+ 详情返回入口

- 头像下拉新增「我的售后」（紧跟我的订单）；售后详情页头部补「返回列表」。
- 售后列表/状态/分页本体经核对完整，未改数据链路。
- 验证：浏览器 9/9（菜单入口 → 列表展示 → 详情 → 返回列表闭环）。
- 测试数据：测试账号一笔演示订单置为已完成并创建演示售后单（待卖家处理）。

### 2026-10-18（二十四）· 「去支付」改绿色渐变

- 新增 `.btn-pay`：`linear-gradient(135deg,#4cc463,#1b8a43)` + 内高光 + 绿色投影，白字。
- 只用于「去支付」（列表 + 详情）；查看详情保持青石实心，申请售后未改动。
- 验证：计算样式渐变两层色、白字；绿 vs 青石最大通道差 67。

### 2026-10-18（二十三）· 订单按钮六档配色

- 发现重复：`去评价` 与 `去支付` 同为 btn-primary；`查看详情` 与客服同为灰描边。
- 调整：查看详情→青实心（btn-accent）；去评价→浅橙（btn-brand-soft）；申请售后→青描边（btn-accent-outline）。
- 六档：橙实心/红实心/青实心/浅橙/青描边/灰描边；问客服保持不变。
- 验证：PENDING / PAID / COMPLETED 三状态计算样式逐项核对。

### 2026-10-18（二十二）· 订单按钮改为实心双色

- 待付款：去支付改 `btn-primary`（实心橙＋白字）；取消订单改新增 `.btn-danger-solid`（实心红＋白字）。
- 其他状态「查看详情」保持描边；「申请退款」继续用描边红 `.btn-danger`，形成两级危险层级。
- 列表与详情两处一致；浏览器计算样式验证 6/6。

### 2026-10-18（二十一）· 通知入口只留数字角标 + 未读数即时更新

- 菜单去掉 🔔/💬，仅在有未读时显示行内数字角标（`.badge-inline`），无未读不渲染。
- 未读刷新根因：仅登录/回前台/开菜单/60s 轮询，下单与支付没有触发。
- 修法：Checkout 下单后、OrderDetail 三处支付成功后 `await refreshUnread()`；Layout 监听路由变化刷新；
  兜底轮询 60s→10s（覆盖他人触发的事件）。
- 边界：自己的动作即时；卖家发货等 ≤10s；零延迟需 SSE（未做）。
- 验证：浏览器 5/5（0→1→2，菜单无图标有数字）。

### 2026-10-18（二十）· 通知中心：角标即时刷新 + 网页落地页 + 图标角标

- 角标：`Notifications` 已读/全部已读后 `await refreshUnread()`，不再等 60 秒轮询。
- 404：`jump_page` 是小程序路由；新增 `NoticeView.webPath`（`NoticeWebPaths` 映射：
  ORDER→`/orders/{orderNo}`、FRIEND→`/messages/{peerId}`，其余 null 不跳转），单测 4/4。
- 入口：头像下拉「通知中心（1）」改为 🔔 + `.badge-dot`（私信同步改 💬）。
- 浏览器 9/9：角标 1→0、通知落订单详情、无括号数字。

### 2026-10-18（十九）· 原生 confirm/alert 替换为站内统一弹窗

- 新增 `@duoduo/shared/components/confirm`：`ConfirmProvider` + `useConfirm` / `useAlert`，Promise 化，
  复用站内 `Modal`；确定/取消/Esc/× 行为与原生等价，`danger` 用危险色。
- 三个入口均挂载 Provider；`Orders.tsx` 取消订单、`ShareMine.tsx` 删除分享改用自定义弹窗。
- 全仓库不再有 `window.confirm/alert/prompt`。三端 typecheck + 构建通过；Playwright 6/6（无原生 dialog）。

### 2026-10-18（十八）· 订单按钮统一 + 问客服入口（占位）+ 客服方案评估

- 新增 `SupportEntry` 组件，商品详情 / 订单列表 / 订单详情三处统一「问客服」入口；
  明确提示人工客服未开放，替代路径为 AI 导购（不伪造在线）。
- 订单操作统一文案与顺序；各状态可用条件写入评估文档附表。
- 客服直连方案评估：不建议全员可见可回复；建议认领 + 唯一责任人 + 审计。
- 核对：用户截图中的「只有去支付」为旧标签页缓存，线上实测三按钮齐全且取消可用。

### 2026-10-18（十七）· 秒杀可用券 + 订单卡片直接取消 + 签到需求入池

- 秒杀券：确认是**设计限制**（DTO 无券字段、折扣写死 0）。放开为可选 couponCode，
  锁券后建单、实付=秒杀价−抵扣、失败整单不成立、失败/撞车立即解锁；网页与小程序自动选最优券。
  `seckill-smoke` 69/0（新增 9 条：用券成功、金额算术、落库、取消回券、活动收尾）。
- 订单卡片：待付款状态新增「取消订单」，二次确认后调用取消并刷新；其余状态按钮口径已写明。
- 需求池：新增 §7 R-P1 每日签到与多多币（仅记录，未实现）。

### 2026-10-18（十六）· 评分一致性修复 + 评价图片双通道（本地/外链转存 COS）

- **评分根因**：首页读 `t_spu.rating/rating_count` 冗余列（种子假数据），详情读评价库汇总；
  冗余列只在新增评价时回写 ⇒ 历史假数据永不纠正。
- **修法**：商品域加 `GET /api/products/internal/ratings/candidates`；履约域加双向对账
  （启动 + 每小时）：候选有/评价无 → 复位 0；评价有/候选无 → 补推。回写改为只更新两列。
- **验证**：31 个假评分复位（1101 4.8/126 → 0/0）；1001 保持 5.0/1；
  card 与 summary 逐项一致；review-smoke 62/0；review-ui-check 24/0；
  模块单测 product 118/0、fulfilment 20/0。
- **图片**：新增 `POST /api/uploads/from-url`（公网校验、不跟随重定向、魔数校验、体积/超时限制）；
  评价页支持「+ 本地上传」与「地址转存」，均落 COS。负例：内网地址 400、HTML 400。

### 2026-10-18（十五）· 测试商品补齐（每类 5 个自营）+ 分类名去英文前缀

- 新增 `docs/sql/incremental/35-seed-test-products.sql`（30 SPU / 36 SKU，全部 `self` + `seller_id=0`，
  只 INSERT IGNORE 不改既有行），并同步并入 `02-duoduo-product.sql` 以覆盖 `down -v` 重建。
- 覆盖 18 个子类；10 个 SPU 双规格；价格 19.9~1299 元，可支撑筛选/排序/分页。
- `Home.tsx` 不再把 `icon` 字段当文本渲染；分类名原本已是中文，无需改库。
- 验证：分类查询 7/5/5/5/5/5、分页与排序/筛选正确、详情 SKU 正确、ES 全量重建后搜索命中 6 条、
  浏览器首页无英文前缀。

### 2026-10-18（十四）· 验收首跑两条红的根因与修复（均为判据/夹具假设错）

- `tenant-health-smoke`：租户 212 > limit 200 触发截断，脚本却用返回行求和比全量汇总 ⇒ 改为双形态断言（截断态断言 `tenantCount=totalTenants`、`行数=limit`，并打 `[PASS*]`）。复跑 90/0。
- `admin-entry` e2e：常驻 ADMIN 使"并发前恰有 2 名 ADMIN"不成立 ⇒ 验收账号降为 OPERATOR。复跑 74/0。
- 产品代码零改动；两条都未放宽断言，而是让判据适配事实形态。

### 2026-10-18（十三）· D4 秒杀抢购记录补齐（双端）＋ 台账纠错

- **纠正台账**：`S6-DUAL-CLIENT.md` 原写「小程序秒杀页含我的抢购记录」，实测双端都没有 —— 已改文档并明确写出这处不实记载。
- **后端**：`GET /api/orders` 新增 `orderType`（白名单 NORMAL/SECKILL/C2C，未知值 400）；`OrderService.listMine` 增重载保留旧签名。
- **网页**：`/orders?type=SECKILL` 提示条 + 查看全部；秒杀页加「我的抢购记录 →」；订单类型标签中文化。
- **小程序**：秒杀页加入口；订单页 `onLoad` 读 type，顶部提示条 + 查看全部。
- **守卫**：`check-dual-client.ps1` 扩为 D1/D2/D4 双向断言（反向验证恰好 1 红 / exit 1）。
- **验证**：单测 4/4；`seckill-smoke.ps1` 60/0（新增 7 条，含接口 total 与库内 COUNT 两来源一致）；前端 typecheck + 三端构建通过。

### 2026-10-13（十二）· 遗留项收口：A-1 越权收窄、备份自动化、特权夹具账号处置、S6-D1/D2b/D3 补齐

**A-1 / PLAN B-23（真实可达的越权，已修）**
- `SellerProductService.update()` 补上 `requireProxyForThirdParty(spu, scope, "编辑")` ——
  E23 当初**刻意只拦上下架**（理由：不借一次收窄顺手改变更多行为），把"编辑"登记为遗留项，
  本轮把欠账还上。不收窄的后果：运营可绕过代运营工单直接改第三方商品的标题/副标题/主图。
- 该私有方法改成带 `action` 参数：提示语按动作分流（"上下架"指向 `proxy-off-shelf` 工单入口；
  "编辑"没有也不该有工单入口 ⇒ 提示"由店主自行维护"）。
- 测试：新增 `A1_*` 3 条（越权被拒 + 不落库 / 自营正对照 / 店主正对照）；
  **显式改写**了原 `运营可以跨店查看与编辑` 那条用例（原断言是"允许"），
  拆成"读放行 + 写 403"两句 —— 改写而不是删除，是为了保住"这里曾经允许过"这段历史。
- 接口级实测：`proxy-ops-smoke.ps1` 新增 3.6/3.7/3.8 三段（网关层 403 + 不落库 + 自营正对照 +
  服务端层直连 403 + 同样不落库），**127 项通过 / 0 失败**。

**★ 同一次改动里顺带查出一个真缺陷（已修）**
冒烟里"（复原）自营夹具副标题已置空"这条断言当场抓到的：
`subtitle` / `images` / `detail` 三个**可清空**字段走 `spuMapper.updateById(spu)`，
而 MyBatis-Plus 的更新策略是 **NOT_NULL —— 它不写 null 字段** ⇒
「传 "" 清空」**接口 200、审计有记录、库里纹丝不动**。
（类注释早把"字段三态：null=不改 / ""=清空 / 有值=替换"写成契约，但只有 title 与 mainImage
的"不允许清空"被测过，三个可清空字段是盲区。）
修法：三个字段各记一个 `clearXxx` 标志，落库后**显式 `set(col, null)`**（与既有 audit_source 那段同型）；
补 2 条单测（清空时必须多发一条显式 UPDATE；只改标题时不许多发）。

**B-1 备份自动化（唯一有不可逆数据损失风险的一项，已闭环）**
- 注册每日计划任务 `DuoduoDbBackup`（03:00，`-StartWhenAvailable` 补跑、1 小时执行上限），
  备份根 `D:\duoduo-backup`（该目录此前已存在并有 run，说明盘位事实上已定）。
- **注册后立刻试跑**：`LastTaskResult = 0`，耗时 45 秒；下次 `2026-10-14 03:00`。
- ★ **必须说清的局限**：实测本机**只有一块物理盘**（Disk 0，C/D/G 都是它的分区）⇒
  备份与项目同在 D 盘，**挡误删、不挡硬盘坏**。要异介质保护需另配（网络位置 / NAS / 对象存储）。

**B-2 特权夹具账号（暴露面已从 946 降到 1）**
- 实测：`OPERATOR 510 + SELLER 434 = 944`（2026-10-10 记录是 199+139）⇒
  **"根因已在 E18 修掉"这句话至少在部分脚本上不成立**，暴露面一直在扩大。
- 新增 `scripts/lib/fixture-role-sweep.ps1`：默认干跑，`-Apply` 才写库；
  **写库前先把 (id, role) 落成回滚 SQL**；降权后按两个来源复核（影响行数 == 目标数、目标集合里特权归零）。
- **保护账号自证**：唯一的真账号（`363903492038987776` / `13684295254` / 2026-10-01 建）
  必须在目标集合外，否则中止执行。
- 执行结果：**降权 945 个**，全库从 `OPERATOR 510 / SELLER 434` 变成 **`USER 3009 / OPERATOR 1`**；
  回滚脚本落档 `D:\duoduo-backup\fixture-sweep\rollback-*.sql`。
- **根治（不只清存量）**：`run-all-smoke.ps1` 新增"**运行前后 diff**"收尾 ——
  开跑前读一份特权 id 清单，跑完再读一份，**差集**就是本轮新增的夹具账号，交给 sweep 降权。
  ★ 刻意用 diff 而不是"按昵称/手机号猜夹具"：diff 只看这次运行新产生的，天然不误伤既有账号，
  也不依赖会变的昵称约定。
  实测：全量冒烟跑中涨到 `14 OPERATOR + 13 SELLER`，收尾降到 `1|0|0`。

**S6-D1 / D2b / D3 三个功能缺口补齐**
- **D3（小程序独立搜索结果页）**：新增 `miniprogram/pages/search/search`（4 文件）+
  `app.json` 注册；首页 `onSearch` 改为 `navigateTo`（**关键词必须 encodeURIComponent**）；
  分类页右栏顶部加「在『当前分类』中搜索」入口。**不占 tabBar**（已满 5 项）。
- **D1（网页端个人主页，后端零改动）**：`api-sdk` 加 `followApi.profile()` + `UserProfileView`；
  新增 `pages/UserProfile.tsx` + 路由 `/user/:id`；三处入口补齐
  （`Follow.tsx` 昵称可点 / `ListingDetail.tsx` 「查看卖家主页」/ 分享卡组件作者名可点）。
- **D2b（小程序宠物档案引导文案）**：`profile.wxml` 加一条静态提示 + `.menu-note` 样式，
  刻意不占 `menu-item` 的跳转位。
- **守卫同步 + 修掉守卫自身一个设计缺陷**：`check-dual-client.ps1` 的 ③ 原本**无条件**断言
  "缺口仍在"（grep 源码必须命中 0）⇒ D1/D3 一旦补齐，这一层**永远不可能转绿** ——
  "修好了反而过不了"的守卫，结局只能是被整体注释掉。改为**双向**：
  文档说「待补」⇒ 断言代码里没有；文档说「已完成」⇒ 断言代码里有。
  反向验证：把 D1 临时改回"待补"（代码里其实有）⇒ **恰好那一条报红并点名**，还原后复绿。

**工具坑（再现，已写进注释）**
- `docker exec` **不继承宿主环境变量** ⇒ `MYSQL_PWD` 必须用 `docker exec -e` 显式传，
  否则 mysql 回 `1045 Access denied (using password: NO)`，而报错里只有一句 Access denied，
  完全看不出是密码根本没送到。
- `$ErrorActionPreference='Stop'` 会把 mysql 往 stderr 打的
  "Using a password on the command line interface can be insecure" 当成终止性错误掀掉整个脚本。

**实测汇总**：全量冒烟 **32 套 / 通过 32 套 / 失败 0**（1 套按设计跳过并点名）；
`proxy-ops-smoke` **127 项 / 0 失败**；前端三应用 `tsc + vite` 全绿；
`check-dual-client` / `check-frontend-imports` / `check-bundle-isolation` 全 exit 0。

### 2026-10-13（十一）· S9 收尾：冒烟覆盖两种存储实现、备份链感知存储介质、存量迁移器 + COS 客户端

**承接上一轮**（`aa3fec8`：`ObjectStorage` 抽象层 + COS 零依赖客户端）。本轮把「接入」这件事收干净。

**T1 · `db-backup.ps1` 卷清单同步**（**切 COS 之前必须先做**）
- 读 `STORAGE_TYPE` 决定卷的角色：`local ⇒ primary`（卷＝生产存储，卷出问题判红）；
  `cos ⇒ legacy`（卷是迁移期遗留，改记 `GAP` 不判红）。
- 新增 **`[4c/7] 生产存储覆盖度自证`**：`type=cos` 时**显式说出"本次备份没覆盖桶内对象"**并给两条补法。
- `Record` 扩成**三态**（`PASS` / `FAIL` / **`GAP`**）；manifest 新增 `storage` 段与 **`OK_WITH_GAP`** 状态。
- ★ 为什么必须做：切 COS 后备份链会呈现**最危险**的状态 ——
  「脚本照跑、汇总照绿，但备的东西已经不是生产用的那份了」。
- **实测**：`local ⇒ status=OK / volumesRole=primary`；
  `cos ⇒ status=OK_WITH_GAP / volumesRole=legacy` + `[GAP]` 点名 + 照发告警。

**T2 · `upload-smoke.ps1` 扩成"两种实现各跑一遍"**
- 先读**容器内** `STORAGE_TYPE` 认清后端，再按后端分叉断言落点：
  `local` 要求文件**在**卷里（正对照）；`cos` 要求文件**不在**卷里 + url 指向桶域名。
- 新增 **`⑧` 夹具自毁**：`local` 删卷内文件（`-u 0` + 回读确认）；`cos` 用 .NET 自写签名 DELETE 桶内对象。
- ★ 为什么必须加：①~⑥ 在两种后端下**完全一样**（这正是抽象层要的），
  于是「配了 cos 却因缺密钥回落 local」会**全绿** —— 新判据就是堵这个假绿的。
- **实测**：**local 11/11、cos 12/12 全过**。

**T3 · 守卫挂进 `check-all.ps1`** 的 `[0/5]`（在"依赖安全自检"之后），并同步两处环节说明。
**实测**：全量回归里 `[storage-guard] 通过…扫描 3 个调用方文件` + `[PASS] 对象存储抽象层自检 exit=0`。

**T5 · 存量迁移**
- 新增 `scripts/lib/cos-tool.ps1`：纯 .NET 的 COS 最小客户端（Put/Get/Head/Delete），
  与 Java 侧 `CosSigner` 同一算法（★ `StringToSign` 带 `sha1` 前缀与 KeyTime 中间段；
  ★ `Authorization` 必须 `TryAddWithoutValidation`，.NET 的标准校验过不了）。
- 新增 `scripts/migrate-uploads-to-cos.ps1`：**幂等**（桶内已一致则跳过）/ **可停可续** /
  **逐文件双源 sha256 核对**（判据是"卷里那份"与"桶里**回读**那份"相等，
  不是"上传返回 200 就算成功"）。
- **实测**：**132 个文件桶内与卷内逐文件 sha256 全部一致**；
  **端到端三角验证**（同一张图三个来源）= 桶直连匿名 **200** + 应用回读 **200** + 卷内**不存在**。
- 事后按「备份清单 ↔ 卷内实况」两个来源比对，揪出并清掉 1 个早期冒烟遗留的夹具文件
  （卷与桶各删一次并回读确认）。

**★ 本轮修掉的三个自身缺陷（都已写进注释/提交信息防回潮）**
1. **迁移器"干跑"其实在写桶**：上传条件写成 `-not $VerifyOnly` ⇒ 默认干跑把 132 个对象写进了真实桶，
   而汇总还打印"没有写入任何对象"。⇒ 改为**只有 `-Apply` 决定写不写**，并加 `-VerifyOnly` / `-Apply` 互斥断言。
   **「干跑」必须真的一个字节都不写** —— 否则它比不跑更危险（给了你"没动过"的错误信心）。
2. **`Authorization` 不能走 .NET 标准校验**：`Headers.Add` 按 `scheme + 参数` 语法校验，
   COS 的 `q-sign-algorithm=...` 过不了 ⇒ 必须 `TryAddWithoutValidation`。
3. **`.ps1` 必须带 UTF-8 BOM**（又一次踩到）：临时脚本没带 BOM ⇒ PS 5.1 按 GBK 读中文 ⇒ 解析失败，
   且报错位置指在别处。⇒ 固定流程：写完 → 补 BOM → `Parser::ParseFile` → 再运行。

**守卫反向验证**：把 `duoduo.storage.type` 默认值改成 `cos` ⇒ **恰好第 ③ 层报红并点名**，还原后复绿。

**文档**：`README.md` 第十二节 S9 条目补"收尾"段；`docs/SELFTEST.md` 新增 §4.12（对象存储抽象层自检）
并更新 DoD 清单与单测基线（715 → **735 项 / 72 个测试类**）。

**现状**：线上仍跑 `local`（刻意默认）；存量 132 个文件已入桶并核过；**切换 / 回退各一条配置**。

### 2026-10-13（十）· S5：「涉及钱」的口径固化成**算术**断言（越权矩阵 **82 → 115 项**；并修掉一处「自愈误删本轮夹具」）

> 对应《优化建议与待确认事项》**S5**（关键规则固化成自动防呆检查），与 E19 越权矩阵同处一个出口
> ——`scripts/privilege-matrix-smoke.ps1`。此前该矩阵 82 条测的全是「**谁能不能做**」，
> 这一节补的是「**钱和库存算得对不对**」。

**问题**：本项目反复栽的缺陷有一类不在权限上 —— 幂等键重放扣两次、并发抢最后 1 件卖出两单、
优惠券没落到订单明细、退款金额大于实付。这类问题**权限全对、状态码全 200**，
只断言「成功 / 失败」永远抓不到，必须**回表读数再算一遍**。

**新增 §7b 共 32 条 + 1 条「起始自愈」守卫（82 → 115），四条口径**：

| # | 口径 | 断言要点 |
|---|---|---|
| ① | 幂等重放只扣一次 | 同 `idempotentKey` 再下单 → 200 且**同一订单号**；`t_order` 该键**恰 1 张**；`t_order_place_task` **≤ 1**；**冻结量只 +1**；收尾取消并自证冻结复原 |
| ② | 并发抢最后 1 件恰好成一单 | 现场建**仅 1 件可售**的 SKU，`Start-Job` **真并发**下单 ⇒ 恰一个 200；`frozen_stock = 1`、`stock - frozen = 0` **不为负**；收尾 `Remove-SkuFixtures` + 回表自证 0 |
| ③ | 券分摊 | 券模板 `ACTIVE`（满 0 减 500 分）→ 领券 → 预览 `pay = total + freight - discount` → 带券下单 → **回表 4 个数做加减** + `discount == 500` + **明细合计 == 商品总额** → 收尾**先删 `t_coupon` 再删模板**并自证都为 0 |
| ④ | 全库金额不变量 | `refund_amount <= pay_amount`、`pay_amount = total + freight - discount`、`discount <= total`；**必须报比对笔数**，`checked == 0` **判失败** |

**自己踩的 6 个坑**（全是断言写法，不是产品缺陷）：① `t_order_place_task` 只在
`order-mode=queue` 下写行 ⇒ 断言改 `≤ 1` 并注明「0 是当前正常值」；② SKU 收尾**只断言没删** ⇒
补 `Remove-SkuFixtures`；③ 券模板状态猜 `ONLINE` ⇒ 实测只有 `ACTIVE`（**别猜枚举，先查库**）；
④ `"$oc[4]"` 展开成 `NULL[4]` ⇒ 改 `"$($oc[4])"`；⑤ `duodoo_fulfilment` 拼错 ⇒ 静默空结果、断言误红；
⑥ 券收尾先删模板删不掉（券还引用着）⇒ 先删 `t_coupon`。

**反向验证（第一跑是假的）**：
- 就地改成 `$pvDiscount -eq 501` ⇒ 跑全量 = **恰好 1 条 FAIL（改坏的那条）+ 其余 114 全绿 +
  `[GUARD-OK] 115 项全部执行` + exit 1**。
- ★ **副本不能挪目录**：脚本用 `$PSScriptRoot` dot-source `lib\platform-operator.ps1` /
  `lib\seller-fixture.ps1` / `lib\catalog-fixture.ps1`；副本放 `.tmp-rev\` ⇒ **三个辅助库全丢** ⇒
  夹具集体崩 + 几十条 FAIL（**所有请求 `requestId` 一模一样**是识别特征），看上去像「产品坏了一大片」。
  那次 `exit=1` 差点被当成反向验证成功。**处置**：副本放回 `scripts\` 同目录（名字避开 `*smoke*.ps1`
  通配）；判据 = **「恰好那一条红 + 其余全绿 + 断言总数不减少」**，不是「exit=1」。

**★ 夹具残留的两轮拉锯（这一节记的是我自己的错）**：
- **事故**：§7b 首跑时 ② 并发段的 `raceSku` 只断言、没真删，留下 1 条 `FIX-*` 挂在宿主 SPU 1001 下
  ⇒ `smoke.ps1` 的「商品详情含 2 个 SKU」断言被挤红（**红的是与越权无关的套件**）。
- **误诊**：我加的「起始自愈」**位置放错了**（放在 §7b 段头，而 §7 的 `Resolve-SkuFixtures` 在本段
  **之前**已现场建了 3 个 SKU）⇒ 自愈查到的 3 条**恰恰是本轮刚建的**，被当成"上次遗留的"删了。
  症状极具欺骗性：每次跑都打印「发现 3 条上次遗留的夹具 SKU」。
- **定位**：`diff` 旧版快照（确认只改了收尾自证、**删除行为没变**）+ 核行号（`Resolve` 522 / 自愈 588）
  + 跑前跑后各查一次库（跑前 0 条、自愈却"查到 3 条" ⇒ 只能是本轮刚建的）。
- **修法**：① 自愈**前移**到 §0 之后、任何夹具创建之前（位置对了，库里本来干净时它必须查到 **0 条**）；
  ② 收尾自证改成**全库口径** `WHERE sku_code LIKE 'FIX-%'`（原来查 `id IN ($createdSkus)`，
  **看不见漏网的**）。教训：**修脏数据的守卫放在"建完夹具之后"，就会把「本轮的」当成「上一轮的」**。

**实测**：`privilege-matrix-smoke.ps1` **115/0、exit 0**（起始自愈 `cleared=0`、收尾自证全库 `left=0`）；
改坏副本 **114/115 + 点名 1 条 + exit 1**。

**全套回归（首次拿到 32/32 全绿）**：`run-all-smoke.ps1` **共 32 套 / 通过 32 套 / 失败 0 套**、`EXIT=0`、
耗时 19 分 00 秒（`search-smoke` 按设计跳过并在汇总里显式点名）；**31 套 PowerShell 合计 2212 项、失败 0**
（逐套求和实算）；上一轮被夹具残留挤红的 `smoke.ps1` 回到 62/0。
`docs/SELFTEST.md` 套件表第 28 行 82 → **115**，并**按本次全套实算刷新了「最近一次实测」段**
（该表的规矩是"要么重跑全套实算再改、要么不改" —— 本轮选了前者）。

### 2026-10-13（九）· S4 延伸：上传文件卷纳入备份与恢复演练（**顺带修掉 3 个判据层缺陷 + 反查时又抓到 1 个**）

> 编号说明：本轮**不是** 附录 A 的 **S9**（`S9 = 图片改对象存储`，仍是长期项、未做）。
> 这是 **S4 数据库自动备份**的延伸 —— 把「用户上传的文件」也纳入同一条备份/恢复链。

**问题（结构性）**
- 备份范围一直是「六库 mysqldump」。但用户上传的**文件**不在库里：`fulfilment-uploads`
  （商品 / 评价图片）、`merchant-materials`（E12 入驻资质材料，私有卷）。
  只备库 ⇒ 恢复出来是「订单在、图没了」；资质材料更是**丢了不可补**（商家要重传、平台要重审）。
- 更麻烦的是这类缺失**不会自己喊**：备份跑完照样报「成功」，演练照样全绿 —— 直到真的要用那份备份。

**做法：把卷也纳入同一条链，判据与库侧同形**
| 位置 | 做什么 | 判据 |
|---|---|---|
| `db-backup.ps1` `[4b/7]` | 逐文件 `sha256sum` 清单 + 把字节复制进 run 目录；`manifest.volumes` 落库 | **两个来源**：容器内 `sha256sum` vs 宿主机 `Get-FileHash` 逐文件一致；**外加清单指纹** |
| `ops-restore.ps1` `[5b/6]` | 把副本回灌到容器内**临时目录**并逐文件核对 | 缺 `volumes` 段 ⇒ **判红**；**一个卷都没验成** ⇒ **判红**；核对完删临时目录并**回读自证** |

- ★ **卷的真实名字带 compose 项目前缀**（`<项目>_<卷名>`）⇒ **按后缀反查**（`docker volume ls` + `-match '(^\|_)<卷名>$'`），
  而不是拼字符串 —— 拼错的表现是「复制出一个空目录然后宣布成功」。
- ★ 演练**只回灌到容器内临时目录**，绝不写回原卷（与"恢复进临时库、不碰业务库"同一个道理）。
- ★ 空卷**不算失败但必须说出来**（`[注意]` 黄字 + 清单指纹）—— 空卷的"备份成功"没有信息量。

**★ 顺带修掉 3 个判据层缺陷（都是"实测才现形"的那一类）**
1. **心跳表用 `CHECKSUM` 判，注定每次报红**。实测 `duoduo_product.t_sched_leader` 的选主续租
   **约每 15 秒**改一次 `expire_at/updated_at`（6 秒探针里就观察到一次跳变），而备份窗口
   （取 before → dump → 取 after）有 30 秒 ⇒ 窗口内**必然**发生 ≥2 次改写 ⇒ 产物 / before / after
   **三者注定互不相同** ⇒ 备份完全正确却每次都红。这正是"狼来了"，报到最后真正的坏备份被淹没。
   处置：新增**持续改写表**名单 `$ChurningTableNames`（按表名声明，运行时解析成 (库, 表) 对），
   名单内的表改判 **行数 + 主键集合**（主键列**现查** `information_schema.KEY_COLUMN_USAGE`，
   不写死列名），日志打 `[PASS~]` **点名**、绝不静默当普通 PASS；名单解析出 0 张 ⇒ **判红**
   （防名单静默失效）。同族问题：append-only 表在窗口内被插入时，`before` 行数也不该拿去卡产物
   ⇒ 只按「源库 ↔ 产物」两来源判并打 `[PASS*]`。**原则：宁假红勿假绿。**
2. **`rm -rf` 静默失败**。`docker cp` 落进容器的文件**属主是 root**，而 `docker exec` 默认以业务用户
   （`duoduo`）跑 ⇒ 删不掉；`rm` 只在 stderr 报 `Permission denied`，而输出被 `Out-Null` 丢掉
   ⇒「删失败」与「删成功」无从分辨。实测踩中：演练每轮都在容器 `/tmp` 留一份 root 属主的副本，
   是**回读自证**那句 `ls -d` 把它揪出来的。修法：该段 `docker exec` 统一加 `-u 0`
   （临时目录是我们自己建的、只放我们自己的副本）。
3. **空串绑到 `[Parameter(Mandatory)][string]` 会抛异常**。反向验证把持续改写表清空后，指纹函数收到
   空串直接抛「无法将参数绑定到参数 Text」—— 而且是在判据算完、日志还没打的那一刻炸 ⇒ 整轮备份没了结论。
   修法：`[AllowEmptyString()]`。同时补两条兜底：**顶层 `trap`**（未捕获异常也要落一条告警 +
   `_ALERT.txt` 再 exit 1 —— 备份失败必须留下产物，否则夜间无人值守跑失败等于没有备份）+
   **单条样本的判据代码 try/catch**（一张表炸了不该掀掉整轮）。

**★ 反查时又抓到 1 个既有缺陷：抽验的「同名表」在同一个临时库里互相覆盖**
抽验把**所有**抽样表都恢复进同一个 `duoduo_backup_verify` 库，而每段 dump 都以 `DROP TABLE IF EXISTS`
开头 ⇒ **跨库同名的表互相覆盖**，库里只剩最后恢复的那一张。本项目 4 个跨库同名的表：
`t_sched_leader`（5 库各一张）、`t_audit_sample` / `t_audit_sample_rate` / `t_proxy_work_order`（各 2 库）。
同一轮只要抽到其中两个，先恢复的那张就变成「拿别人的数据跟自己比」：
- **假红**（实测）：抽到 `duoduo_product.t_sched_leader`（dump 里 2 行）与 `duoduo_marketing.t_sched_leader`
  （1 行），marketing 恢复在后 ⇒ 判 product 时读到的其实是 marketing 那张，报
  `行数 源2/产物1；主键集合 源指纹 c93c5710cddb / 产物指纹 92dca19ad1fb`（`92dca19ad1fb` 经复核是
  marketing 那张的指纹）—— 备份完全正确却报红。
- **假绿**（同一机制的另一面）：另一轮同时抽到 `duoduo_product.t_audit_sample_rate` 与
  `duoduo_user.t_audit_sample_rate`，两张表内容**恰好相同**（各 1 行 / 校验和都是 `2851413293`）
  ⇒ 即使读错了表也照样 PASS。这种「结论恰好对」比假红更危险 —— 没人会去查它。
处置：**抽验主逻辑抽成 `Invoke-SampleCompare`，改成「恢复一张立刻比一张」**
（恢复成功即调用），库里任一时刻只可能有刚恢复的那一张，冲突消失。

**★ 给这些判据装上"防悄悄删掉"的静态守卫**：`check-ops-doc.ps1` 新增 `[3/3]`，逐条断言
`db-backup` / `ops-restore` 里承诺过的 **11 条判据**还在（trap / 名单非空 / 主键现查 / 名单自证 /
允许空串 / 样本 try-catch / manifest 写 volumes / 缺 volumes 段判红 / 一个卷都没验成判红 / `-u 0` /
**抽验「恢复一张立刻比一张」**），**一条都没扫到 ⇒ 判违规**。少一半判据的症状正好是
「看起来还在跑、结论全绿」。脚本新增 `-BackupScript` / `-RestoreScript` 两个参数，**专供反向验证**指向一份改坏的副本。

**验证（全部实测，2026-10-13）**
- 备份：`[4b/7]` 两卷 **27 + 102 = 129 个文件 / 2.12 MB**，容器内与宿主机逐文件 sha256 一致；
  `[PASS~] duoduo_marketing.t_sched_leader`（主键 `name` / 1 行 / 指纹 `92dca19ad1fb`）；
  抽验 **9 张 / 失败 0**；**exit 0**。
- 恢复演练：产物哈希 **6/6**；逐表 **58 张表 / 21,831 行 / 差异 0**；上传卷 **2 个 / 129 个文件**一致；
  临时库与容器临时目录**都已清干净**；**exit 0**。
- **反向验证 ①（心跳表判据）**：改坏的副本清空被抽中的 4 张持续改写表 → 4 条
  `[FAIL] ... 持续改写表，稳定投影也不一致 —— 行数 源N/产物0/beforeN；主键集合 源指纹 X / 产物指纹 e3b0c44298fc`，
  `抽 44 张 / 失败 4 张`，汇总与 `_ALERT.txt` 都产出，**exit 1**。
- **反向验证 ②（顶层 trap）**：故意 `throw` → 打出「脚本被未捕获的异常中断」+ 位置信息，
  落 `_ALERT.txt`，**exit 1**（而不是静默死掉、什么产物都不留）。
- **反向验证 ③（静态守卫）**：把副本里的 trap / 名单 / `AllowEmptyString` / 样本 try-catch / **抽验调用点**
  五项改坏 → `备份/恢复判据 6/11 条在位` + 5 条 `[FAIL] ... 判据不见了：<原因>`，**exit 1**；
  还原后 **11/11 在位**、**exit 0**。
- **同名表修复的验证（刻意把抽样数调到 40，不等运气）**：本轮 **42 张 / 失败 0 / exit 0**，
  且**必然**抽到 4 个库各自的 `t_sched_leader` —— 四条各自独立通过、指纹互不相同
  （product 2 行 `c93c5710cddb` / trade 1 行 `fb4f492bd2e9` / user 2 行 `3a21593c6ced` /
  marketing 1 行 `92dca19ad1fb`）；同一轮的 product 与 user 的 `t_audit_sample_rate` 也各按**自己的**
  数据比过；总时 **2 分 13 秒**。按修复前的逻辑，这一轮里必然有 3 条是错的。
  修复后 `check-ops-doc.ps1` 复跑为 `备份/恢复判据 11/11 条在位`、**exit 0**。

### 2026-10-13（八）· 云端同步：给「暂存副本」装新鲜度守卫（**#343，实测抓到 4 份落后**）

**问题（结构性，不是某一次的疏忽）**
- 上云（腾讯文档 / 飞书 / 资料库）上传的**不是源文件本身**，而是 `.workbuddy/sync-staging/` 下的一批**副本**
  （统一命名、避开中文路径与水印问题）。这条链上有一环**没有任何检查覆盖**：
  `源文件改了 → 忘了重新导出副本 → 上传的还是旧副本 → 云端显示「导入成功」`。
- **「导入成功」只说明传输没问题，不说明内容是最新的。** 2026-10-12 复核时实测抓到 **4 份落后**：
  README 源 421,472B / 副本 416,126B；PLAN 563,808 / 545,816；SELFTEST 65,225 / 48,812；
  项目简报 497,745 / 454,511。四份都"导入成功"过。

**做法：把这条链拆成两半，各给一个能自动判的出口**（`scripts/lib/sync-staging.ps1`）
| action | 做什么 | 判据 |
|---|---|---|
| `export` | 从**源文件**重建全部副本（副本从此不是"手工维护的第二份真相"） | 导出后**立刻自验**，不通过即 exit 1 |
| `verify` | 逐份比 **SHA256**，副本 != 源文件即判失败（**不是 WARN、不降级**） | **自报比对数**；为 0 直接判违规 |
| `manifest` | 只校验映射表自身没腐烂（条数 > 0、仓库侧源文件都存在、约定的 5 份仓库文档一份不漏） | CI 跑这个（不需要暂存目录） |

- **映射表是唯一出处**（11 份：5 份仓库文档 + 6 份工作区文档）。★ 路径一律**正斜杠** ——
  这个脚本也要在 CI（ubuntu-latest + pwsh）里跑，`Join-Path $repo 'docs\PLAN.md'` 在 Linux 下解析不到文件。
- **`exit 3` = 本机不适用**（没给暂存目录 / 目录不存在）：刻意**不返回 0**，因为「没跑」不能长得像「跑过且通过」。

**★ 第三态 `N/A` 进 `check-all.ps1`**：新增 `RecordNa()`，`ok` 记 `$null`、打印 `[N/A]` + 原因，
汇总里单独计数并追加一行「另有 N 项在本机不适用 —— 它们没有被执行，不要当成通过」。
为什么不判 PASS（会把「没跑」写成「跑过且通过」）、为什么不判 FAIL（本机的取舍不该拦住宿主的全量自检）——
两条分支都实测过：未配 env 时 `[N/A]` + exit 0；配好后 `[PASS]` + exit 0。

**★ 顺带修掉比对器的一个假红源：HTML 数字实体**
- 源文件里可能残留 `&#x7684;` 这类数字实体（历史上被转义过一次），文档站与飞书渲染成 `的`、
  回读拿到的也是**字符**；不解码就会把「源有实体、云端有字符」判成缺失。
- 实测：`项目简报` 28 处实体，导致 21 字符连续未覆盖（阈值是 8）→ 被误判为「有缺失」。
- 处置：`feishu_verify_content.py` / `tdoc_verify_content.py` **两侧都先解码数字实体**再归一化，
  **只解数字实体**（`&#x…;` / `&#…;`），**不动 `&amp;` 这类命名实体**（源里可能真就这么写的）。
  自证：`norm('&#x4E0D;&#x6548;')` → `不效`、`norm('&amp; 原样')` → `&amp;原样`。

**本轮云端终态（实测）**
- **飞书 11 份 / 11 份指纹 clean**（重导 4 份 → 删旧副本 → 回读目录确认 11 份、无重复；目录 `QIcxfye7VlEJJCdwGbRcb3hknog`）。
- **资料库 11 份全部 `KS_IMPORT_OK`** —— 长期故障的上游（`get-upload-credential` 恒返 `TEMPORARY_ERROR`）**已恢复**，
  这是资料库第一次真正上传成功（「唯一未闭环项」就此闭合）。
- **腾讯文档本轮未能核对**：宿主凭据 provider 探测结果为
  `{"personal":{"available":false,"reason":"not_connected"},"enterprise":{"available":false,"reason":"connector_disabled"}}`
  ⇒ 判定为**授权问题而非内容问题**（与本机配置无关），已如实上报并给出用户操作步骤。
  ★ 三个平台走**各自独立通道**，一个不可用不影响其余两个 —— 这也是本轮能"部分完成"而不必整体等待的原因。

**验证**：`manifest` exit 0（仓库侧 5 条全在）；`verify` 未配 env 时 exit 3（`[N/A]`）、配好后 exit 0（11/11 SHA256 一致）；
`check-all.ps1` 静态段两条分支各跑一次均 exit 0；飞书指纹 11/11 clean；资料库 11/11 `KS_IMPORT_OK`。

### 2026-10-12（七）· S8 后台账号管理全链路覆盖（**顺带挖出两个真缺陷**）

**背景**：`admin-entry-ui-check.mjs` 原来只验后台入口与登录（32 项），改角色这条链路**只有单测与冒烟各半覆盖**，
页面上「谁能看到按钮、点了会发生什么」一直没有端到端断言；且 SELFTEST 缺一份「自动化测不到的部分」的显式清单。

**做了什么**
- e2e `scripts/e2e/admin-entry-ui-check.mjs` 新增 **S5 段（32 → 74 项）**，四件事各自成组：
  ① **UI 正反向**：`OPERATOR` 看到行内「仅 ADMIN」、无「改角色」按钮、页顶有权限提示；`ADMIN` 点「改角色」→ 选新角色 → 确认 → 断言 toast 与 `.alert-success` 文案，并截图；
  ② **接口层**：`OPERATOR` → 403（理由含「仅限超级管理员」）／`OPERATOR` 冒充（带 VIP 头）→ 仍 403／`ADMIN` 传非法角色 → 422／不存在用户 → 404／幂等重复提交 → 200 且 notice 含「未做变更」且审计条数不变／自降权 → 422 且文案含「不能给自己降权」；
  ③ **回表**：`t_user.role` 真改成 `SELLER`，且 `t_audit_log` 落一行 `USER_ROLE_CHANGE`（`USER -> SELLER`，actor 为操作者）；
  ④ **并发不变式**：两名 ADMIN 用 `Promise.all` 同时降对方 → **恰好 1 条 200、1 条 422**，且回表 `ADMIN >= 1`。
- 夹具按 E 组纪律现场建（`newAccount()` 注册 → 提权 → 重新登录）并跑完自毁（`DELETE t_refresh_token / t_audit_log / t_user` + 断言残留 0）。

**★ 实测抓到的两个真缺陷（都不是测试写错）**
1. **服务端「最后一个 ADMIN 不可降级」在并发下完全失效 ⇒ 平台可以一个管理员都不剩**。
   首跑 S5d 是红的：`status=200/200`、回表 `role='ADMIN'` 计数 **0**。根因＝`AdminUserService` 用
   `selectCount` 统计 ADMIN，而 **InnoDB REPEATABLE READ 下 `selectCount` 是快照读、不加锁**，
   两个事务各读到 `count=2` → 都判定"还有别人"→ 都放行。
   **修法**：`UserMapper` 新增 `selectAdminIdsForUpdate()`（`SELECT id FROM t_user WHERE role = 'ADMIN' FOR UPDATE`）
   —— 加锁读能读到别人刚提交的版本，且后到者会阻塞、重扫时只剩 1 人 → 422。
   ★ 不用 `SELECT COUNT(*) … FOR UPDATE`：它的加锁行为依赖执行计划（走覆盖索引可能不加行锁），**只取 id 行数即 `size()`**。
2. **`admin-smoke.ps1` 里一条「名不副实的假绿断言」**。原断言用 `actor == target` 去调「降级最后一个 ADMIN」，
   期望 422、否则期望 200 —— 但 `actor == target` **先命中「不能给自己降权」**（同样 422），
   于是**两条规则混为一谈、无论哪条生效都绿**，且 `else`（期望 200）分支**永远不可达**。
   ★ 更本质的是：**「最后一个 ADMIN 不可降级」只能在并发下被走到** —— 能过权限门的 actor 必是 ADMIN，
   若库里只剩 1 名 ADMIN，target 只能是 actor 自己。故该规则的正确覆盖点就在 S5d。
   已改写为断言服务端理由文案（`$r.body.message` 含「不能给自己降权」）+ 回表未被改动。

**顺带修掉的一处口径矛盾**
- `frontend/apps/admin/src/pages/admin/Operators.tsx` 的确认弹窗原写「被改用户**重新登录后**新角色才生效」，
  与 E17 实际口径（**旧令牌下一个请求即失效、客户端刷新一次就换到新角色、无需重新登录**）相反。
  用户是在界面上据此决定"要不要让 TA 重登"，**文案必须与 notice 同源** → 已改齐。

**数字变化（均实测）**：e2e `admin-entry` 32 → **74**（S5 段修复前 4 条红、修复后 74/0）；
`admin-smoke.ps1` 72 → **75**；`AdminUserServiceRoleChangeTest` **11/11**；全量 `mvn test` **715 项 / 69 类 / 0 失败**；
后台镜像已重建、拆包核验新文案与新 Mapper 在 `BOOT-INF/classes/` 内。

**未覆盖清单**：`docs/SELFTEST.md` 新增 §8「已知未覆盖清单（自动化测不到的部分）」12 项，逐项写「为什么测不到 + 现在的替代手段」，
末尾记录本轮**已闭合、从清单移出**的两项（S5 段、并发不变式）。★ 该清单的用途是**防止把"没测"当成"测过了"**。

### 2026-10-12（六）· e2e 截图落点收口（跑回归不再弄脏工作区）+ 第 9 个静态守卫

**问题（实测）**
- 22 套 e2e 里 **20 套会落盘截图**，全部写到 **受版本控制**的 `frontend/docs/screenshots/`；
  其中 **9 套写死了 `D:/智能体项目/duoduo-pet-mall-java/...` 绝对路径**（换机器 / 换检出目录即失效）。
- 后果不是"多几个文件"，而是**每跑一次回归工作区就多出上百个 modified / untracked 的 PNG**：
  PNG 内容随真实数据与时间戳变化（`follow_page.png` 一次只差 5 字节、`notice_list.png` 差 707 字节），
  于是 `git status` 里再也分不清「我改了代码」和「我刚跑过回归」。
- 另有一个**跨机器不可移植**的同类问题：8 套脚本的 `PW_CORE` 与 `coupon` 的临时文件路径也写死盘符。

**改法**
- 新增 `scripts/lib/e2e-shots.mjs` —— 截图落点的**唯一出口** `shotsDir()`：
  默认 `<仓库>/scripts/e2e/.shots/`（**已 gitignore**），`SHOTS=` 环境变量可覆盖（归档配图时才用），
  **目录不存在自动创建**（此前 8 套脚本没自己 mkdir，全靠仓库里那个目录恰好存在）。
- 20 套脚本一律改为 `const SHOTS = shotsDir();`；顺带把 8 套的 `PW_CORE` 改为
  按脚本自身目录解析（`path.join(here, 'node_modules/playwright-core/index.mjs')`）、
  `coupon` 的临时文件改按 `REPO_ROOT` 解析 —— 至此 `scripts/e2e/*.mjs` 里**写死盘符 0 处**。
- `.gitignore` 增加 `scripts/e2e/.shots/`。
- 新增静态守卫 `scripts/lib/check-e2e-shots.mjs`（**第 9 个**），已接 `check-all.ps1` 的 `[0/5]` 段与 CI。

**★ 守卫的关键判据是 ⑤（脚本侧与 `.gitignore` 侧必须成对）**
- 「默认落到被 ignore 的目录」这个结论依赖两件事同时成立：① 每套都真的走 `shotsDir()`；
  ② `.gitignore` 真的忽略了那个目录。**只改 ① 不改 ②，等于把污染换个地方** ——
  新增一套 e2e 的人照抄 `const SHOTS = shotsDir();`，是**抄不出** `.gitignore` 那一行的。
- 另有判据 ② 的**空转自证**：一旦所有 `screenshot(` 调用被重构掉，判据 ② 就退化成永真 ——
  「检查还在、却不再检查任何东西」的哨兵必须自己报出来（分母必须 > 0）。
- 反向验证 **9/9**（沙箱现场建 / 跑完自毁）：删收口模块 → ①；某套退回硬编码受控目录 → ②③ 点名文件与行号；
  加绝对盘符路径 → ④；`.gitignore` 去掉默认落点 → ⑤；清掉全部 `screenshot(` → ② 空转；清空套件目录 → 「什么都没扫到 ≠ 通过」。
- ⚠️ 反向验证脚本必须用**异步** `spawn`：本机 Node 的同步派生态（`spawnSync` / `execFileSync`）
  一律 `EBUSY errno -4082`（`status=null` 且**无任何输出**，看起来像"守卫没跑"）—— 首版就栽在这里。

**验证**
- 改造后实跑一套 e2e（`notice-chat-follow`，走容器入口 8088）→ **50 项全过 / 0 失败**；
  跑完 `git status --porcelain frontend/docs/screenshots/` **为空**（这正是本项要的证据）；
  截图落在 `scripts/e2e/.shots/`（5 张）且被 `git check-ignore` 命中。
- 22 套脚本 `node --check` 全通过（ok=22 bad=0）。

**顺带修掉一个 CI 隐患：图表主题守卫在 CI 上"跑不起来"**
- `check-chart-theme-contract.mjs` 要**真 import 一个 `.ts` 模块**（靠 Node 22 起默认的 strip types）。
  此前它只接在 `check-all.ps1`（本地 Node 22）里，**CI 的 `node-version` 是 20** ——
  一旦哪天加进 CI，它会 `ERR_UNKNOWN_FILE_EXTENSION`；若再顺手 catch 掉，就变成"跑不起来就跳过"
  （等于没有检查，铁律 21）。
- 处置：**CI 的 Node 20 → 22**（与开发机对齐，顺带消除"本地绿、CI 红"这一类差异），
  并把**两个 `.mjs` 守卫都加进 CI**（放在 `npm ci` 之前 —— 它们只读源码，不需要装依赖）。

### 2026-10-12（五）· S7 网页端全站未读角标（对齐小程序口径）

**做了什么**
- `api-sdk`：`types.ts` 新增 `UnreadSummary { notice, message, total }`；`endpoints.ts` 的 `noticeApi` 新增
  `unreadSummary()` → `GET /api/socials/notices/unread-summary`（**服务端已合并的 total，前端不自己相加**）。
- `shared/src/store/app.tsx`（整体重写）：新增未读状态与实现 —— `unread` / `refreshUnread` / `clearUnread` /
  `startUnreadPolling` / `stopUnreadPolling`，`UNREAD_POLL_MS = 60 * 1000`。**请求单飞**（`useRef` 存 in-flight Promise，
  并发调用复用同一个）；**失败刻意不清零**（网络抖一下就把未读抹掉，比停在旧值更让人困惑）；未登录清角标。
- `apps/mall/src/components/Layout.tsx`：顶栏头像按钮内挂**全站角标**（`>99 → '99+'`）；菜单里的
  「通知中心（N）」「私信（N）」改读同一份状态（**删掉了此前那套「打开菜单时现取」的独立实现**）；
  刷新点三处 —— 打开头像菜单 / `visibilitychange` 回前台 / 60 秒兜底轮询。

**★ 一个结构性约束（改这块之前先读）**
- `AppProvider` 在 `AuthProvider` **外层**（`main.tsx` 的嵌套顺序），Provider 里拿不到 `useAuth`。
  因此 **Provider 只提供状态与实现，轮询的启停由消费方驱动**（商城端 `Layout.tsx` 按 `loggedIn` 启停）。
  好处是三端共用 Provider 时，后台端 / 商家端不会被动获得「买家未读」的轮询。

**验证**
- `npm run typecheck` 三端 0 错；`check-frontend-imports` 467 条全解析；`check-bundle-isolation` 三端隔离通过。
- 重建 `duoduo-web-app`（healthy）→ e2e `notice-chat-follow` **50 项全过 / 0 失败**（原 42 → 50，新增 ④ 段 8 项）：
  断言「**首页**顶栏就有角标（不只在通知页 —— 这是 S7 的核心承诺）」、「角标数字 == `unread-summary` 的 `total`」、
  「未登录访客没有头像菜单、也就没有角标」。
- 自检方法见 `docs/SELFTEST.md` §4.10c。

### 2026-10-12（四）· S15 行尾符规范化（`.gitattributes`）+ S10 图表主题接入点加固 · 顺带抓到两处「假绿」

**S15 · 仓库行尾统一为 LF（提交 `064ddeb`）**
- 现状实测：仓库当时**没有 `.gitattributes`**，1197 个被追踪文件里 **122 个 CRLF / 908 个 LF**（近乎一半一半）。
  行尾漂移让同一文件在不同机器上检出结果不一致，`git diff` / 打补丁的兜底路径不可用。
- 新增 `.gitattributes`：`* text=auto eol=lf`；二进制显式声明 `binary`（png / jpg / jar / …）；
  `*.bat` 与 `*.cmd` 保留 CRLF（本仓目前没有这类文件）；文本类型逐个显式 `text eol=lf`（防内容嗅探误判）。
  扩展名分布已核过：CRLF 集中在 `.java`(49) / `.json`(24) / `.wxml`(18) / `.ps1`(12) 等，
  **没有任何需要 CRLF 的类型**；`.sh` 本就是 LF（必须保持）。
- 一次性 renormalize：**122 个文件 CRLF → LF**（工作区与索引同时落地）。
- 判据（三条，均实测）：
  ① `git diff --cached --ignore-cr-at-eol` 为空 ⇒ 改动只有行尾；
  ② 逐文件比对 `HEAD(去 CR)` 与新内容：**122/122 完全一致，非行尾变化 0**；
  ③ 二进制被改 0；`.ps1` 的 UTF-8 BOM 丢失 0。
- 复跑 7 个静态守卫全部通过（其中 `check-smoke-static` 的「63 个 `.ps1` 全部带 BOM」证明 LF 化无副作用）。

**★ 顺带抓到「假绿」第 1 处：守卫抓到了 README 自己（提交 `ad8377d`）**
- 行尾规范化后复跑 `check-md-bare-tick.ps1`，报出 `README.md:1987` 命中**形态二**：
  两形态对照表的示例格里，行内代码中夹了一个未转义的竖线 —— 这会让该行导入飞书时**从竖线处被截断**。
  也就是说，「本条规则要防的事」被写进了本文档自己身上。
- 处置：表格内只留转义写法，把反例移到**表格外正文**（正文里裸竖线不触发飞书表格分隔符，也不触发守卫）。
- 复跑结果：116 份 `.md` / 42287 行，形态一 0 处、形态二 0 处，通过。

**S10（前半）· 图表主题接入点加固 —— 把「将来只改一处」从口号变成可核的实物**
- `currentChartThemeMode()` 从**写死 `'light'`** 改为**读 `<html data-theme>`**：
  只有精确 `dark` 返回 `dark`；其余（未设置 / 空串 / 其他值 / 无 `document`）一律回落 `light`。
  当前应用没有任何地方写 `data-theme`，所以**运行时行为与改前完全一致**（不引入半成品深色）。
- 同步修掉一个会让「接入点」名不副实的隐患：`charts.tsx` 的配色常量原是**模块级**
  （`const T = chartTheme()` 在文件顶层），只在首次加载算一次 —— 主题切了图表**不会跟着变**。
  改为**组件内求值**，并把 `T` 放进 `useMemo` 依赖（`chartTheme()` 返回 `CHART_THEMES[mode]`
  的同一对象引用，因此不会造成每次渲染重算 option）。
- 新增静态守卫 `scripts/lib/check-chart-theme-contract.mjs`（**第 8 个**），
  接入 `check-all.ps1 [0/5]` 与 CI：动态替换 `globalThis.document` 后真调用验 7 种取值；
  静态验四条约定（含「不得回到模块级常量」）。**三种注入各 exit 1 且精确点名**，还原后 13/13 PASS。
- e2e `admin-charts-ui-check.mjs` 新增第 ④ 段（该套 **36 项**）：用 `addInitScript` 在页面脚本执行前
  设 `data-theme="dark"`，断言 **canvas 像素抽样指纹与 light 不同** —— 这是「dark 那套配色不是死代码」
  的端到端证据（凡降级/兜底必写能走到那个分支的测试）。判据刻意**不用截图文件的哈希**：
  同一像素重新编码成 PNG 的字节也可能不同（压缩非确定性），拿文件哈希会假红。

**★ 顺带抓到「假绿」第 2 处：云端同步的暂存副本落后于源文件（已登记待修）**
- 核对 11 份同步文档时发现 `.workbuddy/sync-staging/` 里 **4 份落后于源文件**
  （README / SELFTEST / PLAN / 项目简报），而上一轮的验证台账报 **11/11 clean** ——
  因为比对用的是「**旧暂存副本 vs 旧云端文档**」，两边都是旧的，**恒等式必然成立**。
- 飞书上的 README 实读确认仍是**更早一版**（旧标题「同一族还有第二种形态 —— 续行以裸反引号开头」，
  没有两形态对照表）。⇒ 「云端内容正确」不能只靠「本地副本 == 云端」，
  必须先保证「**暂存副本 == 当前源文件**」。已登记为独立事项。

**★ 顺带记录：e2e 会把截图写回仓库（已登记待修）**
- 11 个 e2e 脚本的 `SHOTS` 是**硬编码绝对路径**（指向 `frontend/docs/screenshots`），
  默认把截图写回仓库 ⇒ 每次跑回归都可能把该目录下 110 张 PNG 变成 modified，淹没真实改动。
  已登记：默认输出改到 `scripts/e2e/.shots/`（gitignored），需要更新配图时用 `SHOTS=` 显式指定。

### 2026-10-12（三）· 云端同步发现「飞书吞行」→ 修源文件 + 装上第 7 个静态守卫

**一句话**：给云端三平台做**逐行指纹比对**时，发现 README 有 **1 行在飞书里整行消失**
（腾讯文档与 GitHub 都正常）。逐变量排除后定位到**行首裸反引号**这个触发条件，
修掉源文件，并把它固化成静态守卫 `check-md-bare-tick.ps1`（已接 `check-all.ps1` 与 CI）。

**现象与定位（这是本轮最有价值的部分）**：

- 该段落是一个跨行列表项，第二行以**两个反引号**开头：
  两个反引号 + 空格 + 两个反引号 + 表达式 + 两个反引号 + 空格 + 两个反引号。
- GitHub 渲染正常、腾讯文档正常、**只有飞书把第三行整行吞掉**。
- 逐变量排除（换行位置 / 是否有内联代码 / 是否有嵌套反引号 / 是否加空行）：

| 变量 | 改法 | 结果 |
| --- | --- | --- |
| 换行位置 | 三行并成一行 | 仍吞 |
| 嵌套反引号 | 保留 `` `└ ${cat.name}` `` | 仍吞 |
| 加空行分隔 | 列表项之间加空行 | 仍吞 |
| **去掉行首裸反引号** | 改成「不换行、也不用双反引号包表达式」 | **通过 ✅** |

**判据（写进守卫，且必须按这个口径）**：

- ✅ 正确判据 = **行首（允许空格缩进）连续出现 ≥2 个反引号** 且 该行是**列表项续行**。
- ❌ 错误判据一 = 「整行反引号总数为奇数」：那一行一共 **10 个反引号是偶数**，
  自我配对后可全消，但它在飞书导入时**确实**会吞行（首版守卫因此漏判）。
- ❌ 错误判据二 = 「续行以行内代码开头即违规」：全仓命中 **85 处**，全是正常写法
  （列表项后的说明行用行内代码引用标识符）—— 那种守卫会变成噪音，最后没人看。

**防复发**：新增 `scripts/lib/check-md-bare-tick.ps1`（扫描 116 份 .md / 42,149 行），
双向前提都实测过：干净仓库 **exit 0**；注入一行违规 → **exit 1 并点名**
`README.md:1959`（反向验证命中）。已接 `check-all.ps1 [0/5]`（守卫从 6 个变 **7 个**）与 CI。
同轮把「**列表项的续行不要以反引号开头**」写进 README 的「约定的写法」第 4 条。

**同轮修掉的校验器假红**：`feishu_verify_content.py` 原先按归一化后**严格子串**匹配，
而飞书回读会把 `[文本](./路径.md)` 降级成纯文本 `文本.md`（丢掉方括号与 `./`）→
两条"链路完整却被判缺行"的假红（README 第 5 行、项目简报第 594 行）。
已按「先把 `[text](target)` 折叠成 `text`、两侧都去掉 `./`」的口径修正，**只比文字不比链接语法**。

**云端同步终态**：腾讯文档 **11 份**（逐行指纹 **11/11 clean**）、飞书 **11 份**（修正口径后 **11/11 clean**）、
目录两边均**无重复**。资料库因上游 `TEMPORARY_ERROR` 未恢复，**仍未上传**（非本地配置/体积问题）。

**★ 同轮后半段：守卫上线后立刻抓到「形态二」—— 表格单元格里行内代码含未转义竖线**

守卫装好、跑通之后，我顺手又跑了一次云端逐行比对，**飞书立刻报出 1 行缺失** ——
而这一行**是我自己刚写进《遗留项与待办总清单》的新内容**，说明这条规则确实"新写的内容很容易踩"。

- 触发写法：表格行里 `` | a | 扫全仓 `.md`（跳过 `node_modules|target|dist`），判「x」 | ``
  —— 单元格里的 `node_modules|target|dist` 是**行内代码**，但其中的 `|` **没转义**；
- 飞书表现：把该 `|` 当成**单元格分隔符**，于是**从竖线之后的内容全部丢失**（该格被截断）；
- GitHub 与腾讯文档**都能正确识别**（当作代码内容），**只有飞书不行**。

最小复现（4 个探针表，各导入飞书回读）：

| 探针 | 写法 | 飞书回读 |
| --- | --- | --- |
| A | 含**未转义**竖线的行内代码 | **内容被截断** ❌ |
| B | 同样内容但竖线**转义**成 `\|` | 正常 ✅ |
| C | 单元格内无竖线 | 正常 ✅ |
| D | 普通行内代码（无竖线） | 正常 ✅ |

**处置**：把守卫扩成**两条形态**（同一个脚本、同一个"只在飞书现形"的族）：
形态一＝列表续行以行首裸反引号开头（吞**整行**）；形态二＝表格单元格行内代码含未转义竖线（吞**该格后半**）。
全仓复扫：形态一 **0 处**、形态二 **0 处**（存量本就全部正确转义成 `\|`）。
反向验证也补了形态二：注入 `` | x | 含 `a|b` 的一行 | `` → **exit 1 并精确点名 `README.md:11`**。

★ 形态二实现上的一个坑（**靠反向验证才发现**）：第一版先按竖线**切单元格**再判
——切分本身就把那个裸竖线当分隔符吃掉了，切开后每格都"干净"、**判据恒假**（注入违规却 exit 0）。
正确做法是**不切单元格**，直接扫整行、维护一个「是否处于成对反引号之间」的状态。
★ 同轮还把《遗留项与待办总清单》与《项目简报》**重导两端各一次**（重导必然产生新 id，
流程＝先导入 → 回读目录 → 删旧副本），终态仍是**两边各 11 份、逐行指纹 11/11 clean、均无重复**。

### 2026-10-12（续）· 容器侧验证补跑 + 修掉开关第④层的「猜容器名」判据

**一句话**：Docker 恢复后补跑容器侧缺失的 4 项验证（全部通过），过程中**实测揪出开关第④层的一个脆弱判据**
——「容器里真生效」原先靠**猜容器名**（`duoduo-app-<x>` 公式），只对 `duoduo-xxx-app` 命名成立，
`duoduo-mq-broker` / `duoduo-es` 全猜错；猜错不报错、只静默退回「.env（容器未读到，可能未重建）」。
已改为**读 compose 声明的真实 `container_name`**，并加两条自证断言 + 反向验证 5/5。

**补跑结果（此前因 Docker 无法启动而挂起）**：

| 项 | 结果 |
| --- | --- |
| 开关第④层「容器里真生效」 | 清单 15 项中 **10 项**来源为「容器实际生效」（另 5 项走默认值），33 条 → **35 条自检全 OK** |
| `ops-status.ps1` 全栈一屏 | **OK 26 ／ 需留意 1 ／ 失败 0**，exit 0（WARN＝备份根未配置）。★ 冷机首跑出现 1 次「网关→商品域 操作超时」，手动预热后复跑即绿（铁律 12） |
| `ops-restore.ps1` 恢复演练 | **哈希 6/6 + 58 表 / 19,242 行 / 差异 0**，同一次跑出，exit 0；临时库自证清理 |
| `db-backup.ps1` 备份 | 六库 dump **6/6**、declared-vs-present 通过、抽验 7 表三方比对 0 失败、耗时 **1 分 59 秒**、产物 3.86 MB |

**修复细节（`scripts/ops-switch.ps1`）**：

* `Read-ComposeSwitches` 新增解析**服务级 `container_name`**，返回 `ContainerByService` 映射；
* 第④层改为 `$compose.ContainerByService[$row.Service]`，**不再用 `-replace` 公式猜名**；
* `Get-ContainerEnv` 去掉不可信的 `$LASTEXITCODE -ne 0` 判断（**实测：容器不存在时 `docker exec`
  打 stderr 但退出码仍为 0**），改为检查输出是否含 `No such container`；
* 新增两条自证：`compose 里能解析出 container_name`（解析到 0 个 ⇒ 判失败）、
  `开关清单涉及的每个服务都能解析到 container_name`（缺谁点名谁，并说明「第④层对它是盲区」）。

**反向验证 5/5**：A0 基线绿 / A1 删掉清单内服务的 `container_name` ⇒ **精确报红 1 条且零噪音** /
A2 改成不存在的名字 ⇒ 静态仍绿（名字合法，运行时才暴露，符合预期）/ A3 全删 ⇒ 报红 / A4 还原 ⇒ 回绿。

**同轮踩到的两个夹具坑（已写进 SELFTEST §4.9）**：
① `Get-Content -Raw` + `Set-Content` 改 compose 会把 CRLF 变 LF 并动缩进 → `# OPS-SWITCH` 标记不再紧贴透传行，
一次性报出 13 条无关失败把真信号埋掉；**正确做法是行级读写、保留原行尾**。
② 临时 PS 脚本忘了 UTF-8 BOM → PS 5.1 按 GBK 读，中文全乱、引号错位（`意外的标记`）。
另：PS 5.1 对 `[regex]::Replace` 的静态重载参数解析有缺陷（把正则里的 `(?m)` / `(\r?\n)` 当命令），改用 `-replace`。

**验证**：`check-ops-doc` **15/15** exit 0（改了 `OPS.md` 与 `ops-switch.ps1` 后仍双向一致）；
`ops-switch -Check` **35/0**；`check-all.ps1 [0/5]` 静态守卫段 **6/6 PASS**；全量冒烟与全量 e2e 见下方「同轮回归」。

**★ 同轮实测发现（已固化成铁律 45 / SELFTEST §4.6）**：**冒烟与 e2e 不能并行跑**。
`scripts/mq-smoke.ps1` 是唯一会 `docker restart` 业务容器的冒烟脚本（重启 `duoduo-app-product` /
`duoduo-app-trade` 让它们重连 RocketMQ broker）；我起初把冒烟与 e2e 并行启动，结果 e2e 恰在重启窗口
打这些服务 → 网关返 **503** → 「无 5xx / 无非预期 4xx」类断言**集体假红 11 条**。
识别特征：**多个不同服务同时 5xx**（`/api/home` + `/api/categories` + `merchant.../api/seller/*`）
＋ `docker ps` 里某容器 `Up About a minute`（其余 `Up N hours`）。处置：`docker inspect --format
'{{.State.StartedAt}}'` 对时间戳 → 确认是重启窗口就**串行重跑**，**不要当产品缺陷去改代码**。

### 2026-10-12（S6 双端功能对照表 + S13 第三方依赖安全扫描 · 两个"没有编译期征兆"的缺口各自装上静态守卫）

**一句话**：新增 `docs/S6-DUAL-CLIENT.md`（小程序 30 页 / 网页商城 31 组件 / 34 条路由逐项对齐）
+ `docs/SELFTEST.md` §4.10 / §4.11 + 两个离线守卫
（`check-dual-client.ps1` 双端一致性、`check-dependency-security.ps1` 依赖安全），
双双接进 `check-all.ps1` 的 [0/5] 段与 CI。

**S6 · 双端功能对照表 —— 回答"同一件事两端各自做到哪一步"**

把 60+ 个页面逐行对齐后，差异被归成四类：**A 平台能力差异**（微信登录/`wx.requestPayment`/调起系统实名
只有小程序；宠物档案只有网页）、**B 页面形态差异**（小程序 `chat-list`+`chat` ↔ 网页 `/messages`+`/messages/:peerId`，
小程序 `pay` 独立页 ↔ 网页 `/checkout` 内联）、**C 入口位置差异**（「分类」小程序是独立页、网页是首页区块）、
**D 真实缺口**。

**D 类共 4 条，全部落进对照表 §3**：

| 编号 | 内容 | 判决 |
| --- | --- | --- |
| D1 | 个人主页（看别人）——小程序有 `user-profile` 三处入口，**网页端 grep 命中 0** | **待补**：网页端 C2C 链路断在「看到人 → 进主页」这一跳 |
| D2 | 宠物档案 `/pets` —— 网页有，小程序**零命中** | **有意不做**（tabBar 已满 5 项，低频录入型功能桌面端更合适）；但小程序「我的」应加一句引导文案（拆出 D2b 待补） |
| D3 | 独立搜索结果页 —— 网页有 `/search`，小程序是**首页原地过滤**，且分类页内搜不了 | **待补**（用 `navigateTo`，不占 tab 位） |
| D4 | 秒杀「我的抢购记录」网页端缺失 | 低优，可并入 D3 同一次改动 |

**守卫 `check-dual-client.ps1` 的三条判据**：① 文档引用的每个小程序页面目录 / 网页组件**.tsx** 必须真实存在；
② 文档标为 tab 的页集合 == `app.json` 的 `tabBar.list`（**双向**）；③ §3 缺口清单**自证仍成立**
（缺口被补齐时主动报红，提醒把 D 项改为「已完成」）。反向验证 **8/8**。

**★ 写这个守卫时连踩三个"判据静默失效"**，全部记进 SELFTEST §4.10：
`& grep` 在 PowerShell 下直接抛 `CommandNotFoundException`；
`Get-ChildItem -Path <裸目录> -Recurse -Include '*.tsx'` 在 PS 5.1 下**静默返回空**（命中数恒 0 → 断言"永远成立"）；
读重定向日志时用 `bytes[1] -eq 0` 判 UTF-16LE，在文件**带 BOM** 时恒假（`bytes[0..1] = ff fe`）→ 中文全乱、文案匹配恒不命中。

**S13 · 第三方依赖安全扫描 —— 为什么刻意不用 OWASP dependency-check / `npm audit`**

两者都**必须联网**（前者首次要下数 GB 的 NVD 数据库，后者要打 registry 的 advisory 接口），
而 CI 与本地验收环境都不保证外网可达 → 挂进流水线只会得到一个"平时绿、需要时连不上"的摆设。
**铁律 21：`FAILED`（没跑成）是一等状态** —— 跑不起来就跳过的扫描等于没扫描。改为三层**确定性离线**判据：

| 层 | 判什么 |
| --- | --- |
| ① 已知漏洞版本底线 | 本地规则表 **10 条**（npm 6 / maven 4），**每条注明 CVE 与官方修至版本**；低于下限即违规 |
| ② 版本锁定 | 禁止 `*` / `latest` / 空串（`@duoduo/*` 工作区内部依赖豁免）；`frontend/package-lock.json` 必须存在 |
| ③ 供应链卫生 | 不得配非白名单 registry；不得有 `preinstall`/`postinstall`/`install` 钩子；Maven settings 不得有 `http://` 明文仓库 |

**实测结果（全绿）**：npm 比到 6/6 条规则（vite 5.4.11 / react-router-dom 6.28.0 / react 18.3.1 /
react-dom 18.3.1 / echarts 6.1.0 / typescript 5.6.3）、maven 比到 4/4 条
（jjwt 0.12.5 / mybatis-plus 3.5.5 / spring-boot 3.2.5 / spring-cloud 2023.0.1）、
37 条依赖声明全部为版本范围或工作区链接、4 份 `package.json` 均无 install 钩子、无自定义 registry。
反向验证 **8/8**（含基线绿 / npm 降版红 / maven 降版红 / `latest` 红 / 删 lock 红 /
加 `postinstall` 红 / 非白名单源红 / 依赖全删→判据静默失效红）。

**验证**：6 个静态守卫全部 exit 0（`check-smoke-static` / `check-gateway-routes` /
`check-mapper-sql-entities` 435 文件 / `check-ops-doc` / `check-dual-client` / `check-dependency-security`）；
两个反向验证各 8/8；`check-all.ps1` 语法解析 0 错。

### 2026-10-12（E25 运维与回滚手册 · 把「有开关」从文档措辞变成四层可核的实物）

**一句话**：新增 `docs/OPS.md`（三站点部署 / 入口开关 / 备份恢复演练 / 数据清理与回滚）
+ 三个运维脚本（`scripts/ops-switch.ps1` 开关唯一权威清单、`scripts/ops-status.ps1` 一屏全栈状态、
`scripts/ops-restore.ps1` 备份恢复演练）+ 一个门禁（`scripts/lib/check-ops-doc.ps1`，
已接进 `check-all.ps1` 与 CI）。实测：开关静态校验 **通过 33 / 失败 0**、一屏状态
**OK 26 / 需留意 1 / 失败 0（退出码 0）**、恢复演练 **58 张表 / 19,242 行 / 差异 0（退出码 0）**、
反向验证 **7/7**（含 6/6 开关反向 case）。

**为什么必须做**：此前「有开关」这句话**只有一半是真的**。写一篇开关大全不等于开关能用 ——
一个开关要真能用，必须**四层逐层核到实物**：① 清单登记 → ② compose 真透传（`${NAME:-…}`）
→ ③ 服务 yml 真读（`${NAME:…}`）→ ④ 容器里真生效。**E25 落地前实测到 4 处不可达，且全是静默型**：

| # | 开关 | 症状 | 根因 |
|---|---|---|---|
| ① | `duoduo.security.api-metrics-enabled`（E24） | 面板接口错误率恒 0，不报错 | yml 有占位符，但 compose **没透传** |
| ② | `entryScopeEnabled`（E8） | 三端入口隔离**关不掉也开不了** | **连 yml 占位符都没有**，只有代码默认值 |
| ③ | `adminIpWhitelistEnabled` + `adminIpWhitelist`（E21） | 「IP 白名单可配置」在运维侧**根本不成立** | 同上，没有 yml 占位符 |
| ④ | `DUODUO_SEARCH_ENABLED` | `.env` 里改了不生效 | compose 里是**硬编码 `'true'`**（引号包住的字面量） |

**修法**：补 3 条网关 yml 环境变量绑定（`DUODUO_ENTRY_SCOPE_ENABLED` / `DUODUO_ADMIN_IP_WHITELIST_ENABLED` /
`DUODUO_ADMIN_IP_WHITELIST`）+ compose 6 处透传（含把 ④ 的硬编码改成 `${…:-true}`）+ **给 compose
里所有可调项加 `# OPS-SWITCH` 标记**（标记必须紧贴透传行上一行，解析器用**反引用**保证左右同名）。

**★ 本轮挖出并修掉的三个真缺陷**：

1. **恢复演练的哈希层「静默没验」**：`ConvertFrom-Json` 产出的是 `PSCustomObject`，对它调
   `.ContainsKey('sha256')` 会抛 `MethodNotFound`，而 `$ErrorActionPreference='Continue'` 把它当
   非终止错误吃掉 → `$wantSha` 恒为空 → 只留一行 WARN「没有 sha256」，而 manifest 明明 **6/6 都写了**。
   修法：`$d.PSObject.Properties.Name -contains 'sha256'`；并把「声明了 sha256 却一份都没验成」
   从 WARN **升级为硬失败**（那与"根本没有这条判据"是两件事）。
2. **环境问题被误报成备份问题**：Docker Desktop 在演练中途退出时，`[4/6]` 报「导入失败 / docker cp 失败」，
   看着像**备份损坏**。修法：新增 `[0/6]` 前置检查（引擎可用 + MySQL 容器在跑），失败时明确输出
   「这是环境问题，不是备份问题」并退出。
3. **`Measure-Object -Property Rows` 对 hashtable 静默失效**：`$tables` 的元素是 `@{}`，
   `Rows` 只是**键**不是属性 → 抛 `GenericMeasurePropertyNotFound`，走错误流、**脚本不失败**，
   只把"合计行数"渲染成空串（输出看着"有个位置空着"）。修法：`foreach` 累加。

**另一处必须记下的判据设计错误（形式上更强、实质上不成立）**：曾给恢复演练加「整表 `CHECKSUM TABLE`
快照」比对，实测报 **2 处假红**（`duoduo_product.t_sched_leader`、`duoduo_trade.t_sched_leader`）——
心跳表每 2 分钟自改，**跨时刻比必然不等**，备份完全正确却每次演练都红。已移除，并写下注释说明为什么；
正确防线在 `db-backup.ps1` 的抽验（那里有独立时间点，做三方比对）。

**验证**：开关静态校验 33/0 + 反向 **6/6**（compose 硬编码 / 默认值不一致 / `.env` 无效应项 / 未登记标记，
四个 case 各自精确报红，还原后复跑回绿）；一屏状态 OK 26 / WARN 1 / FAIL 0；备份（新脚本）退出码 0；
恢复演练 58 张表 / 19,242 行 / 差异 0 + 产物哈希 **6/6 一致**；手册门禁反向 **7/7**
（引用不存在的脚本 / 表漏一项 / 表多一项 / 标记块被删，四种改坏都精确报红，还原后 SHA256 一致）。

### 2026-10-12（E24 租户健康度面板 · 一屏看四件事，且不骗人）

**一句话**：后台新增「租户健康度」一屏（订单 / 资金 / 售后 / 接口错误率四个维度）+
网关逐日租户请求计数三个端点 + 新套件 `tenant-health-smoke.ps1` **90/0** + 新单测 149·41·95 全 0 失败。

**三条「别骗人」的规矩**：① **三态**（`OK` / `DEGRADED` / `UNKNOWN`）—— 没数据不许显示成"健康"；
② **样本门槛** —— 样本不足时不给比例，给 `null` 而不是 `0`；③ **4xx 不判级** —— 客户端错误不计入
服务健康（否则用户输错手机号会把整站标红）。**降级方向**也是刻意选的：任一子查询失败 → `DEGRADED`
且 `reasons` 说明，不静默。

**★ 修掉三个真缺陷**：① MyBatis **注解 SQL 无 `<script>` 却写 XML 实体**（`>=` 写成 `&gt;=`）——
编译期、启动期全无征兆，只在 MP 的 `TenantLineInnerInterceptor` 用 JSqlParser 解析时以 500 暴露
（新增静态守卫 `check-mapper-sql-entities.ps1` 拦这类）；② 框架异常缺 handler → 静默变 500；
③ `Comparator` 升序 + `reversed()` 作用域写错导致排序反了。顺带**查出 README 里"389 个端点"
是抄来的错数字**，改为一律不抄（契约文档自身有 `--check` 门禁，手抄在别处的数字无人核对）。

### 2026-10-12（S4 数据库自动备份 · 从「只有人工 dump」变成一条可自证的命令）

**一句话**：`scripts/db-backup.ps1` 一次跑完 ① 回表读源库快照 → ② mysqldump 六库 →
③ 产物自证 → ④ **从产物切表恢复进临时库、与源表比对行数 + `CHECKSUM TABLE`** →
⑤ 滚动保留最近 N 份 → ⑥ 失败即告警。实测六库 **58 张表 / 3.85 MB / 1 分 40 秒**，
退出码 0；抽验 9 张（随机 3 + 每个库的最后一张）**失败 0**。

**为什么这件事必须做**：全项目**唯一**有「不可逆数据损失」风险的一项。此前所有验证与修复
（含 E26 全量删号）都建立在"库坏了能从备份恢复"之上，而那个前提**只靠人工临时 dump 维持** ——
人工动作没有门禁、不会自证、失败时也不会喊人。台账里那句"备份盘位已自行拍板为 `D:\duoduo-backup`"
是凭印象写的：该目录不存在、脚本也不存在。

**★ 三个「看起来对、其实错」的坑（都是本轮实测抓出来的）**：

1. **PowerShell 函数返回单元素数组会被静默展开**。`Invoke-Sql` 返回 `@('duoduo_user')`，
   调用方拿到的是**字符串**，于是 `$r[0]` 变成了**取第一个字符** —— `duoduo_user` → `d`、
   `8.0.36` → `8`。全程**不报错**，症状是"六个库都说不存在"。
   写法：`return ,$res`（前置逗号 = 不要再展开一层）。
2. **切片必须带上 dump 的 preamble**。被抽中的表若正好是 dump 里的**最后一张**，
   它的切片会一路走到文件末尾、把收尾块 `/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;` 也包进来，
   而 `@OLD_TIME_ZONE` 只在 preamble 里赋过值 → 恢复当场报
   `ERROR 1231: Variable 'time_zone' can't be set to the value of 'NULL'`。
   前两张表抽中时**看不出问题**（切片到下一张表标记就截断了）—— 典型的"抽样能过不代表链路对"。
   ⇒ 所以现在**每个库的最后一张表永远强制入选抽验**（边界用例不能靠运气覆盖）。
3. **不能只跟"恢复那一刻的源库"比**。`duoduo_marketing.t_sched_leader` 是选主心跳表
   （`expire_at = updated_at + 2min`，每 2 分钟被改写），校验和**每次都不同** ——
   备份完全正确却天天报红，典型"狼来了"。⇒ 改成**三方比对**：抽样在 dump **之前**定、
   并同时取一次源侧校验和，产物只要与 **before 或 after** 之一一致即通过；
   两边都不一致才判 FAIL，且三个值全部打出来。

**其它已固化的纪律**：`-Databases` 里任一个库在 `information_schema` 查不到 → **硬失败**
（防"库改名后静默少备一个"）；每张表都要能在产物里找到 + 数据段标记齐全（declared-vs-present）；
临时校验库现场建、跑完自毁，**建不起或删不掉都报红并回读自证**；容器内暂存目录 `/tmp/dbbak`
开工与收工各清一次并回读；滚动删除只认 `yyyy-MM-dd_HHmmss` 命名的目录，别的一概不碰；
失败时落 `_ALERT.txt` 并可 POST 到 `-AlertWebhook`（告警本身失败不覆盖真原因）。

**怎么验（照做即可）**：
- 正常：`powershell -NoProfile -ExecutionPolicy Bypass -File scripts\db-backup.ps1 -BackupRoot <某个真实存在的目录>` → 退出码 0，`runs\<时间戳>\backup.log` 末段全 PASS。
- 滚动：连跑 2 次加 `-KeepRuns 2` → `runs\` 下只剩 2 个目录。
- 失败路径：`-BackupRoot Z:\nope` → 退出码 **1**，并打印"改哪一行"的处置。
- 注册每日任务：`scripts\db-backup-register.ps1 -BackupRoot <盘>`（**需你指定盘**，见遗留项 B-1）。

**遗留**：备份**根目录**仍是待老板拍板的占位默认值（`$DefaultBackupRoot`，脚本顶部一行可改）；
`-AlertWebhook` 未配置（当前只落盘 + 打印）。

### 2026-10-11（S3 钱与库存每日自动对账 + 提醒）

**一句话**：每天凌晨系统自己把「交易库里的钱和库存」重算一遍，
和「商品域 / 托管台账实际记的」逐笔比 —— 对不上就落一条差异明细并告警；
**没跑成**（依赖服务挂了）必须如实记成「未完成」，不许记成「全绿」。

**为什么这件事必须做**：E22 之后钱变成了「可提现 / 待结算 / 冻结中 / 累计到账」四栏，
每一栏都是**多张表推导出来的结论**，任何一处写错都不会报错、只会让某个人的余额长期错一点点；
库存同理（冻结台账 + 商品域 `frozen_stock` 是两个不同的存储，靠跨服务调用同步，中间失败会**静默残留**）。
这类缺陷的共性是**没有报错、没有异常、没人报障**，只能靠"每天把两个来源比一遍"发现。
S2 已经建立了调度单实例假设（`SchedLeaderLock`），S3 复用同一套选主。

**★ 三条设计裁决（都是"怎么失败"的选择，不是实现细节）**

1. **对账 ≠ 把同一个函数算两次**。每条规则必须比**两个不同来源**；
   拿同一个计算函数调两遍是恒等式，不是检查。唯一例外是 `MONEY_FEE_ILLEGAL`，
   它**故意不比费率**（见第 2 条），只查三个与策略无关的结构性非法（费率为负 / 佣金大于金额 / 金额为负）。
2. **★ 钱是"政策的函数"，不是"当前费率的函数"**。
   第一版拿「库里存的 `platform_fee`」与「**今天的**费率」比 —— 本地库实测**92 条费率不符 + 375 条自营被抽佣**，
   **全部是假差异**：那些单据结算时费率还是 1%（12 900 的金额收 129），
   另一些早于 E22 的自营免佣规则。`SettlementEntity` 的注释里其实早就写着
   「费率会随运营策略调整，而历史单据必须保留**结算当刻**的口径」。
   裁决：**费率类比较属于回归测试（settlement-smoke），不属于每日对账**；
   每日对账只比"两个来源对同一事实的记录是否一致"。
   已把这条写进 `ReconRule` 类注释与单测 `钱_费率历史不产生假差异()`（12 900 收 129 必须 `isEmpty`）。
3. **`FAILED` 是一等状态**（三态 `CLEAN` / `DIRTY` / `FAILED`）。
   最危险的失败模式是**把"没跑"记成"全绿"** —— 报表一片绿而事实未知。
   所以：取数抛异常 / `checkedCount == 0` / 读依赖服务失败，一律落 `FAILED`，
   并且 `syncStatusFromCounts` 带 `AND status <> 'FAILED'`，`FAILED` 永不被重新标成 `CLEAN`。
   真验证方式：`docker stop duoduo-app-product` 后跑 STOCK 对账 → 必须是 `FAILED`（冒烟第 11 节就是这么做的）。

**交付物**

| 面 | 内容 |
|---|---|
| 表 | `t_recon_run`（`uk_recon_run_day_scope` 唯一键）/ `t_recon_diff`（`uk_recon_diff(run_id, rule, ref_type, ref_id)`） |
| 判据层 | `ReconScope`（钱/库存两范围）/ `ReconStatus`（三态）/ `ReconRule`（13 条：钱 8 + 库存 5）/ `ReconJudgement`（纯函数，无 DB / 无 Spring / 无时钟） |
| 服务层 | `ReconService`（`ReconSourceMapper` 取数 + `ProductGateway` 严格读 + `persist` + `alert`）、`@Scheduled(cron)` 每日 03:30、`SchedLeaderLock` 选主 |
| 跨域 | 商品域新增 `GET /api/products/internal/skus/frozen-holders`（反查"有冻结或负库存"的 SKU）；`SkuSnapshot` / `SkuPurchaseView` 暴露**原始** `stock` / `frozenStock` |
| 接口 | `GET /api/admin/recon/runs`、`GET /api/admin/recon/runs/{id}/diffs`、`GET /api/admin/recon/rules`、`POST /api/admin/recon/runs`（唯一写端点，`@Audited(RECON_RUN_MANUAL)`，`OperatorGuard.requireOperator()`） |
| 单测 | `ReconJudgementTest` **16/0**（含**声明-执行守卫**：`ReconRule.ALL` 每条都必须同时有命中用例与不命中用例） |
| 冒烟 | `scripts/recon-smoke.ps1` **53/0**（12 小节，见下） |
| 静态守卫 | 新增 `scripts/lib/check-gateway-routes.ps1`（见"★ 本轮抓到的第 1 个缺陷"） |

**★ 本轮抓到的 2 个缺陷**

1. **网关路由漏配 → 27 项断言全 404（首轮冒烟 26 通过 / 27 失败）**。
   控制器**已经编译进镜像**（`unzip -l` 能看到 `AdminReconController.class`）、
   trade 服务也活着（直连 8083 匿名返回 401），但经网关的请求**全部 404** ——
   因为网关路由是按**具体子路径**列举的（`Path=/api/cart/**,…`），`/api/admin/recon/**` 漏了。
   这是本文件第 5 次记同一个坑（A11 / E14 / E18 / A3 之后）。
   排查时它和「服务没起来」「接口没写」长得**一模一样**，所以修完立刻固化成静态检查：
   `check-gateway-routes.ps1` 把「类级基路径 + 方法级路径」拼成 **250 条实际路径**逐条要求被网关模式覆盖，
   并已接入 **CI 第一步** 与 `check-all.ps1` 的 `[0/5]`。
   负对照已验证：临时删掉 recon 那段路由 → 它**精确报出** `/api/admin/recon/rules`、`/runs`、`/runs/{runId}/diffs` 三条。
2. **`checked_count == 0` 必须判 `FAILED`**（编码期发现）。
   0 个对象必然「没有差异」，于是"什么都没看"与"看了且没问题"在报表上完全一样 ——
   和铁律 2「空结果与链路没接通长得一样」同族。冒烟第 3 节专门断言 `checkedCount > 0`。

**其他值得记的取舍**

- **同日重跑覆盖而非累积**：先 `deleteByRunOfDay` 再 `deleteByDayAndScope`，靠唯一键而不是应用层查后写。
- **计数必须回表读**：`syncCountsFromDiffs` 在插入后重新 `COUNT(*)` ——
  `uk_recon_diff` 可能去重，否则报表说 5 条、点开只有 4 条。
- **跨域两向覆盖**：交易域只能送去"它知道有 FROZEN 的 SKU"，那个方向**永远发现不了**「交易域漏记冻结」；
  所以加了反查端点 `frozen-holders`，候选集 = 两天来源的并集。
- **严格读 vs 宽松读**：`ProductGateway` 原有的宽松读把 4xx 折成空 Map（购物车场景是对的：
  "这个商品不存在"是业务事实）；对账新增 `loadSnapshotsStrict` / `frozenHolderSkuIds` / `callStrict`，
  **任何**异常都抛 503 DEGRADED —— 对账里"读不到"≠"确实没有"，把前者当成后者会把一次调用失败变成 N 条假差异。
- **为什么暴露原始两列而不是 `availableStock`**：出参 `availableStock = max(0, stock - frozenStock)` 会把
  `frozenStock > stock` 这个异常**夹成 0**，前端只显示"售罄" —— 异常被一个看起来很合理的降级掩盖掉了。
- **两个范围独立成败**：钱和库存分开落 `t_recon_run`，否则一次商品服务抖动会让整个对账 `FAILED`，
  连带把一个本来完全正确的钱对账结论也变成"未知"。
- **提醒边界**：本轮不外接告警通道（WARN 日志 + 后台页 + 严重度计数），已在代码注释里写明这是刻意的边界。
- **审计动作码在收件方翻译**：新增 `RECON_RUN_MANUAL` 后**必须一起重建 `duoduo-user`**
  （`AuditAction.parse()` 跑在 user 域的 `AuditLogService` 里），否则落库成 `UNKNOWN`。
  冒烟第 10 节同时断言「`RECON_RUN_MANUAL` 有记录」与「近 20 分钟无 `UNKNOWN`」。

**冒烟 12 小节**：① DDL 就绪 ② 规则清单**源码声明 vs 运行时**（正则数源码、比对 `/rules` 返回）③ 判权（买家 403 ×3 / 匿名 401 / 直连端口 401）
④ 干净基线（`checkedCount > 0`、非 `FAILED`、报表条数 == 明细表条数）
⑤~⑦ **三对「造差异 → 断言命中 → 复原 → 断言消失」**（托管金额 ≠ 订单实付 / 放款状态缺放款时间 / 跨域冻结不一致 + `frozen > stock`）
⑧ 僵尸冻结（现场插 `'95'` 段夹具行，带唯一键守卫）⑨ 同日覆盖重跑 + 无孤儿明细
⑩ 入参边界（非法范围 400 / 非法日期 400 / 不存在的轮次 404 / 不传日期=今天）
⑪ **手动触发留痕 + 无 `UNKNOWN`** ⑫ **停 `duoduo-app-product` → STOCK 必须 `FAILED` 且带失败原因，同时 MONEY 不受影响**，随后 `docker start` + 等健康 + 复跑不再是 `FAILED`
⑬ 收尾自证复原（托管金额回原值 / 无 `95` 段残留 / 临时提权的运营已降回 `USER`）

### 2026-10-11（E23 代运营最小版 · **P3 最后一项，P3 收官**）

**一句话**：平台要替第三方店铺做三件事（代发货 / 代处理售后 / 代下架），
唯一的合法入口是**店主本人给出的一张一次性工单**，且每一步都留痕。

**为什么这件事必须做**：E27 之后 `t_spu.seller_id` 指向真实第三方卖家、`t_order` 也按店铺拆单，
于是"平台账号"这个身份在**代码里**突然拥有了对别人经营资产的处置权 ——
`SellerService.requireOwned` / `SellerProductService.requireOwned` 对平台运营一律放行（平台管自己的货）。
这在自营年代是对的，在自营 + 第三方混营之后就是**越权**。E23 做两件事：
**把默认路径收窄**（平台不再能直接动第三方的订单 / 商品），
**再开一条受控例外**（持工单可代操作），而不是简单地把口子焊死 —— 焊死会让真实业务需求（店主请平台帮忙发货）无路可走。

**交付物**

| 面 | 内容 |
|---|---|
| 表 | `t_proxy_work_order` —— **交易库与商品库各一张，列名逐列相同**（见下面"为什么不共用一张表"） |
| 交易域 | `ProxyOpsService`（判据矩阵）/ `SellerProxyController`（店主申请、查看、撤销）/ `AdminProxyController`（平台待办、代发货、代处理售后） |
| 商品域 | 同名三件套 + 代下架；`SellerProductService` 新增收窄判据与 `forceOffShelfByProxy` |
| 权限 | 新权限点 `TENANT_PROXY_OPS`（`tenant:proxy-ops`），只授 ADMIN 与 OPERATOR |
| 审计 | 新动作码 `ORDER_PROXY_SHIP`(CONTENT) / `AFTERSALE_PROXY_PROCESS`(FUNDS) / `PRODUCT_PROXY_OFF_SHELF`(CONTENT) |
| 单测 | `ProxyOpsJudgementTest` 交易 16 + 商品 16；`SellerProductServiceTest` 新增 5 → 商品域 54 |
| 冒烟 | 新套件 `scripts/proxy-ops-smoke.ps1`（13 节，**121 项断言**） |

**口径覆盖（对照执行总纲 E23 的三句要求）**

- **「平台后台店铺数据只读查看」**：已有端点覆盖 —— 订单侧 `GET /api/admin/orders`、`/{orderNo}`
  本就无归属限制（隔离加在**写**上，不是读上）；商品侧 `GET /api/admin/products`、`/{spuId}` 同理。
  本轮新增的是**工单待办**的只读视图：`GET /api/admin/orders/proxy-work-orders`（可按 `tenantId` / `targetRef` 筛）、
  `GET /api/admin/products/proxy-work-orders`。口径是「**进门宽、动数严**」：
  待办列表只要平台身份就能看（不改变任何状态），**执行**才要 `tenant:proxy-ops`。
- **「三个动作需关联工单 + 填事由」**：工单由店主在商家端申请（`reason` 必填、非空白），
  平台执行时还要再填一次**执行说明**（同样必填、非空白）—— 两级事由分开记：
  「店主为什么请平台做」与「平台凭什么现在做」是两件事，合成一段会丢掉一半信息。
- **「自营不受限、第三方默认不可操作（断言）」**：自营正对照（200）与第三方默认阻断（403）
  在**网关层与服务端层分别断言**，并各配"被拒后状态没被改"的反向断言。

**六个判据（"授权"不是一个布尔值，是六个条件同时成立）**

1. 工单存在；2. 状态为 `OPEN`；3. `target_type` 对得上；4. `action_type` 对得上；
5. `target_ref` 就是本次的目标；6. `tenant_id` == 目标的店铺主体 userId。

顺序是刻意的：先判 **授权能不能用**（400 / 404），再判 **授权对不对得上**（403）。
这样运营看到 400 知道是工单本身的问题、看到 403 知道是"拿错店的单子" ——
两类事故的处理方式完全不同，用一个状态码盖住会把"看一眼就能排查"变成"翻代码"。

**一次性消费用 CAS，且必须在同一个本地事务里**
`UPDATE ... WHERE id = ? AND status = 'OPEN'`，影响行数 0 就是 `409 CONFLICT`，**必须中止**；
"先查状态再改"在并发下会让一张授权执行两次。
消费与动作放在同一个事务里 —— 这正是工单表要**每个域各放一张**、
而不是放到共享的 `duoduo_user` 库的原因：跨服务就只能"先消费再调用"，
中间任何失败都会留下一张被烧掉却没干事的工单，而那不是补偿能糊过去的东西。

**同时收窄一条已经变味的既有行为**：`changeStatus` 里平台运营原本可以直接改任何商品的状态。
现在只有 `supply_type` 与 `seller_id` 两个信号**都**指向自营时才放行，
任一信号指向第三方就走代运营。顺带把商品域里"是不是自营"的口径**收敛成一个出口**
（`SellerProductService.isPlatformSelf`）—— 此前 `ProxyOpsService` 自己又写了一份 `seller_id = 0`，
两份判据迟早会漂移，而漂移的症状是"一边放行、一边拦"，各自看都自洽。

**验证**
- 单测：`ProxyOpsJudgementTest` 16 + 16，`SellerProductServiceTest` 54 —— 全绿；
  每条"被拒"都配"有权者能过"的正对照（只断言拒绝的话，把方法改成永远 403 也能全绿）。
- 冒烟 `proxy-ops-smoke.ps1` **121/0**，含这几条安全关键断言：
  - 拿 A 店的工单动 B 店的订单 → 403，**且被拒后订单与工单都是原样**（授权没被浪费）；
  - 同一张工单、同一个动作，归属对上就能过 → 200（证明上一条被拒的唯一原因就是归属）；
  - 工单再用一次 → 400；已撤销 / 已消费的工单不可用；
  - 卖家 / 买家调用平台侧代运营端点 → 403；平台账号自己申请工单 → 403；
  - 拿交易域工单去商品域执行 → 404（两库工单不通用）；
  - 被拒的尝试**也留痕**（`outcome=FAILED`），留痕里带工单号、类别分 CONTENT / FUNDS。

**本轮抓到的两个真缺陷（都是"只有真跑起来才现形"）**

1. **审计动作码全部落库成 `UNKNOWN`**。原因：`AuditAction.parse()` 是在**接收方**
   （`duoduo-user` 的 `AuditLogService`）执行的，而这一轮只重建了 trade / product 两个服务，
   `duoduo-user` 内嵌的还是旧的 `duoduo-common`。
   教训：**新增审计动作码 = 新增一个"接收方也要认识"的契约**，
   只重建发送方会得到一份能查、但查不出内容的台账（`action=UNKNOWN`），而这不会让任何接口报错。

2. **正对照"运营直接下架自营商品 → 200"返回 405**。原因：夹具用
   `SELECT ... WHERE seller_id = 0` 去找自营商品，而本机库被清理过、在售 SPU 只剩两条，
   两条都被挑去当"S 的第三方商品"了 → 自营那条是空的 → 请求打到
   `/api/seller/products//status`（URL 里 spuId 为空）→ 405。
   405 长得像"接口用错了"，真正的原因却是"夹具没建成"。
   修复：自营夹具**现场建、跑完自毁**（`'97'` 段位），并在建完立刻回读复核、建不起来当场报红 ——
   不再依赖库里的存量数据。

**遗留（已登记，本轮刻意不做）**：`SellerProductService.update()` / `delete()` 仍允许平台运营
改 / 删第三方商品。收窄它影响面与回滚方式都与上下架不同，应当单独评估，
而不是借一次收窄顺手改变更多行为（顺手扩范围会让"这次上线改了什么"变得说不清）。

### 2026-10-11（P3 收尾 · 修复「6% 佣金从未真正生效」的配置覆盖缺陷）

**这是上一轮 E22 交付后，全量冒烟抓出来的真缺陷，也是本轮最有价值的一条。**

**现象**：`backfill-smoke.ps1` 报 `期望 774 分 -> 129`、`佣金费率 -> 100`。
`129 = FeeOf(12900, 100)` —— 运行时费率**还是 100 bp（1%）**。

**根因**：`duoduo-trade/src/main/resources/application.yml` 里写着
`platform-fee-bps: ${PLATFORM_FEE_BPS:100}`，而 `SettlementService` 上是
`@Value("${duoduo.trade.platform-fee-bps:600}")`。**yml 里的值会覆盖代码里的默认值**，
所以 E22 的头号规则（6% 抽佣）在运行时从来没生效过。

**为什么上一轮没被发现**（三个盲区叠在一起，这条比 bug 本身更重要）：
1. **单元测试**用反射注入 `platformFeeBps`，绕过了配置绑定 → 测的是"算法对不对"，不是"配置生效没有"；
2. **`settlement-smoke`** 的夹具订单全是**平台自营**（`seller_id = 0`）→ 按规则免佣 → 期望值与实际值都是 0，
   断言恒真，两侧一起错也看不出来；
3. 同一个盲区让 `backfill-smoke` 里"佣金按 N bp 重算"的断言**退化成"佣金 == 0"**，
   而我把脚本常量改成 600 之后，它才开始报错 —— 也就是说：**是"把常量改对"这个动作暴露了缺陷**，
   在此之前那条断言一直在骗人。

**修复**（4 处，缺一处都还会复发）：
- `application.yml`：`platform-fee-bps` 默认 `100 → 600`，并新增 `settlement-hold-days: ${SETTLEMENT_HOLD_DAYS:7}`，
  让 T+7 从"靠代码默认值"变成"配置里看得见"；注释里写明**必须与 `@Value` 默认值一致，否则 yml 静默覆盖**。
- `backfill-smoke.ps1`：夹具订单 A / B 改挂**真实第三方卖家**（否则佣金断言退化为自营免佣恒 0），
  并加"夹具确实改成了第三方"的正对照 —— 自证输入侧真被写过。
- `seller-smoke.ps1`：**删掉一条算了却没人用的期望值**（`$feeA`）。它算的是订单 A 的第三方佣金，
  但订单 A 的托管单是在支付时（`seller_id = 0`）创建、佣金当刻写死，事后才改归属**不会重算** ——
  写成断言只会是假绿。改为在 §12 断言：那里的商品是**下单前**就划给卖家的，
  佣金确实按第三方抽 → 新增 8 条真实断言（发货后 / 放款后各一轮，含"事后调费率不改历史单"）。
- 文档纠错：`README.md`、`docs/CODE-WIKI.md`、`docs/handover/03-环境变量与密钥（脱敏）.md`
  里"默认 100 bp = 1%"已与事实不符，一并改为 600 bp = 6%。

**验证**：重建镜像并用 `docker inspect` 比对容器 `.Image` sha 与新构建镜像 sha（一致，确认跑的是新包）；
`backfill-smoke` **92/0**、`settlement-smoke` **149/0**、`seller-smoke` **107/0**；
单测 `duoduo-common` 176 + `duoduo-trade` 49 = **225/0**。

**沉淀为铁律 16**：**配置默认值只写在代码里不算配好** —— 只要 yml / compose / 环境变量里还有一份，
就以那一份为准。凡"改了默认值"必须同时确认运行时真的变了（取值接口或断言），
否则测试会全绿而线上还是旧行为。

### 2026-10-11（E22 佣金与结算规则落地 · P3 第 7 项 —— 含结算账单与商家端钱包页）

**解决的问题**：交易一直在跑，但"平台赚多少、卖家什么时候能取"没有一条可复核的口径 ——
抽佣规则散在代码里、放款即等于可提现、卖家看不到自己的钱卡在哪一段、平台与商家两边各算一遍。

**四条规则一起落地**（改一个就要动另一处，所以一次说完）：

1. **抽佣 6%（600 bp）**：`SettlementService.platformFeeOf(sellerId, amount)` 是**唯一口径**，
   四舍五入到分：`(amount * bps + 5000) / 10000`（直接整除会把每笔都少收一点点，
   放大到百万笔就是可观的差额，而且对账时差异均匀散布、几乎无法定位）。
   金额用 `long` 参与乘法，`int` 在 2 万元 × 100 的量级就会溢出。
   费率走配置 `duoduo.trade.platform-fee-bps`，配 0 即全平台免佣（回滚开关）。
   **自营（seller_id = 0）免佣** —— 佣金从左手交到右手，既没有真实收入，
   又会让"第三方到底贡献了多少佣金"这个真正要回答的问题失去答案。
2. **T+7 可提现**：`released_at`（记账时刻）与 `release_at`（可提现时刻）**从此不相等**。
   合成一个字段就表达不了"钱已经是他的、但还不能取"这个中间状态 —— 而那正是"售后期冻结"要的东西。
   冻结期与售后期取同一个值（`duoduo.trade.settlement-hold-days`，默认 7），
   因为两者本就该一致：**冻结的意义就是盖住退款窗口**。
3. **欠款抵扣**：可提现 = 已解冻 − 未结欠款，且不小于 0（负数会被理解成"平台欠我"，与事实相反）。
   **可提现只算 RELEASED，不算 ADVANCED**：垫付的钱卖家根本没拿到，把它算进去会出现"越退款越有钱"的错账。
4. **结算账单（租户 × 周期）**：`SettlementService.billOf(sellerId, period)` 是**唯一口径**，
   商家端 `GET /api/seller/settlements/bills` 与平台端 `GET /api/admin/settlements/bills` 都调它。
   账单分两段：**本期发生额**（按事件时间严格过滤，任何一天重跑都是同一个数 —— 财务要的可复核性）
   与**余额快照**（与钱包页同源，所以同一时刻两处显示的可提现必然是同一个数）。
   快照段刻意**不**做"期末回溯" —— 那需要按 `updated_at` 反推历史状态，等于养出第二套口径。

**双端同源是本轮的纪律，不是巧合**：钱包与账单在服务层只有一个方法，
商家端与平台端都调它。两边各算一遍是本项目明令禁止的模式（E20「判定点只有一个」的同一条纪律）——
分叉的后果是"平台说能提 500、商家看到 480"，而那种分歧在技术上无解。

**★ 本轮最重要的发现：record 组件绕过 Long 序列化白名单（T-40 同类缺陷，已根治三处）**
- `LongToStringJacksonModule` 的字段名白名单靠 `BeanSerializerModifier.changeProperties` 生效，
  而 **record 的属性不流经那条路径** —— record 里的 `long` 一律被当成标识、以**字符串**下发。
- 于是 `SettlementView.amount` / `SettlementPage.custodySellerAmount` 这些金额字段
  下发的是 `"25800"`，前端 `types.ts` 却声明为 `number`。JS 里 `"25800" / 100` 因隐式转换"看着正常"，
  但 `+` 会变成字符串拼接、`===` 恒 false、`.toFixed()` 直接抛错 —— 靠格式化函数恰好用了 `/` 才没炸。
- **修法**（模块注释里写明的既定方案）：金额/计数字段逐个标注
  `@JsonSerialize(using = NumericLongSerializer.class)`；`sellerId` / `tenantId` 这类**标识**刻意不标
  （雪花值超 JS 安全整数，必须保持字符串）。已修 `SettlementView` / `SettlementPage` /
  `SellerWalletView` / `SettlementBill` 四个 record。
- **抓到它的正是新增的 e2e 断言**：`walletApi.pendingAmount === ledgerApi.custodySellerAmount`
  在两个值都是字符串时是 `"25800" === "25800"`（本应恒真却因比较写法而暴露类型），
  而 `netWithdrawableAmount === Math.max(0, withdrawable - debt)` 这类**算术断言**直接假 ——
  字符串减法给出的是数字，于是不等。**教训：金额断言必须做一次算术，纯相等比较探不出类型错。**

**商家端页面（E22-5）**：`/seller/settlements` 从「资金对账」改为「结算与提现」，分三块：
- **钱包四栏**（可提现 / 待结算 / 冻结中 / 累计到账）：唯一权威口径，前端**不从明细自己加总** ——
  冻结/抵扣规则一变，自己加的总就与提现结果对不上，而对不上的那个数恰好是用户最在意的。
- **本期账单**：入账笔数与金额、平台佣金、放款、解冻、退回、垫付；有欠款时单独解释
  "为什么毛额有钱却提不出来"（藏在数字里没人看得懂）。
- **托管台账**：原有逐笔明细与全量三栏汇总，口径不变。
- 钱包/账单/明细三个请求**各自独立 useAsync**：明细取数失败不该把「我能取多少钱」一起拖下水。

**接口清单（新增 4 个，全部走网关鉴权）**
| 端点 | 权限 | 说明 |
| --- | --- | --- |
| `GET /api/seller/settlements/wallet` | 卖家（店员 403） | 待结算/冻结/可提现/欠款/下次解冻 |
| `GET /api/seller/settlements/bills?period=` | 卖家（店员 403） | `yyyy-MM` 或 `yyyy-MM-dd`；不传=当前月 |
| `GET /api/admin/settlements/wallets?tenantId=` | `finance:settlement`（仅 ADMIN） | 与商家端同一段计算 |
| `GET /api/admin/settlements/bills?tenantId=&period=` | `finance:settlement`（仅 ADMIN） | 与商家端同一段计算 |

**周期参数的刻意取舍**：只认 `yyyy-MM` / `yyyy-MM-dd` 两种形状，**非法一律 400，绝不静默回落到当前月**
—— 回落的后果是"我查 9 月、系统给 10 月"，页面一切正常，只有答案是错的（最难被发现的一类）。
也不接受"上个月""最近30天"这类相对表达：账单要被归档、要被复核，
一个写着"最近30天"的账单在三个月后打开时已经不是同一个区间了。
账单的 `periodStart` 含、`periodEnd` **不含**（左闭右开），"10 月的最后一秒"才不会同时落进两个月。

**顺带修掉的夹具缺陷**：`settlement-smoke.ps1` 的 `Register` 函数体里混进了一段运营会话创建
（此前某次编辑的残留），导致**每次注册夹具账号都额外建一个没人用的运营账号**且从不回收 ——
已移除；本轮新增的财务夹具（ADMIN）按夹具三纪律收尾降回 USER 并清审计行。

**验证**
- 单测 **49/0**（`duoduo-trade`）：新增 `SettlementServiceFeeTest` 5 项（6% 抽佣 / 自营免佣 /
  费率 0 回滚开关 / 金额不合法给 0 不抛异常 / 大额不溢出 / 售后期天数是「唯一口径」）
  与 `SettlementBillPeriodTest` 11 项（月周期左闭右开 / 12 月跨年 / 日周期 / 缺省当前月 /
  **7 种非法周期一律 400** / 费用段逐字段落位 / 列名大小写容错 / 快照段复用钱包口径 / 欠款 null→0 /
  聚合为 null 时账单全 0 / 快照时刻是「现在」而不是周期末）。
- 冒烟 `settlement-smoke.ps1` **149/0**（原 130 + 新增 19）：E22 佣金口径、T+7、钱包接口与不串数据、
  账单周期回述与左闭右开、账单自洽、**账单余额段与钱包逐分一致（同源）**、非法周期 400、
  运营查账单 403（财务权限点生效）、财务夹具提权正对照、**平台端与商家端账单逐分一致**、
  **自营（tenantId=0）账单本期确有入账**（防「聚合恒空被当成正常」）、
  **账单与库内独立复算逐分一致（笔数/金额）**。
- e2e `seller-ui-check.mjs` **40/0**（原 25 + 新增 15）：接口层先钉口径（钱包=台账同源、净额=毛额−欠款、
  账单周期与入账笔数、账单快照=钱包、账单自洽），UI 层断言四栏与账单区块真的渲染。
- 前端：`npm run typecheck` 三应用全过；`check-frontend-imports.mjs` 460 条说明符全可解析；
  `check-bundle-isolation.mjs` 三端产物互不包含。
- 顺带跑通：`check-smoke-static.ps1` 49 个脚本全合规（含 BOM 与 Check 判据规则）。

**遗留（下一轮）**：~~S3 钱与库存每日自动对账 + 提醒~~ —— **✅ 已完成 2026-10-11**（见本文件 §6 首条；
`recon-smoke.ps1` 53/0 + 单测 16 + 新增网关路由覆盖静态守卫）。
另：**S18「AI 空推荐兜底口径」已由 E23 顺带作答**（见本轮 §6 首条）：模型**明确给空数组** → 如实转达保持空；
**没给结构化 id / id 全对不上** → 兜底前 3 条。两者以前在代码里长得一样（都是空），现用 `structured` 标志分开。
另：`SellerSummary` / `AdminDtos.SettlementSummary` 等其余 record 仍有同款字符串化隐患
（本期只修了 E22 触及的四个），行为靠 `formatPrice` 的隐式转换侥幸正确 —— 待单独一轮统一根治。

### 2026-10-10（E21 平台后台两步验证 + IP 白名单 · P3 第 6 项）

**解决的问题**：平台账号一个账号能审商品、关订单、改权限、看全站资金。只靠密码时，
一次撞库或一次钓鱼就足以拿到整个后台；两步验证让"拿到密码"不再等于"拿到后台"。

**★ 强制点放在登录链路，而不是网关**（本轮最关键的设计决定）：
- 已启用 2FA 的账号登录必须带动态码或恢复码，否则**拿不到任何令牌**。没有令牌，网关本来就什么都放行不了，
  因此**不需要**再给 `JwtAuthGlobalFilter` 加一条"这枚令牌算不算数"的判断 —— 判定点越少，口径越不易分裂。
- **刷新令牌带 `tfv` 声明** + **启用 2FA 时吊销该账号全部刷新令牌**（两道互补防线：
  吊销清存量会话，声明兜住吊销那一刻正在飞行中的请求）。这一处**刻意不做向后兼容**。
- **微信登录对已启用 2FA 的账号直接拒绝**：那条通道没有第二步的输入位。

**三个安全细节**：① 防重放用**条件更新**（`WHERE totp_last_step IS NULL OR < 本次步号`）原子推进；
② 恢复码只存哈希、用一次作废一枚、失败不消耗；③ 已启用时拒绝重新 setup。

**★ 本轮冒烟挖出一个真 bug**：`AuthService.login` 在 2FA 校验通过后 `updateById(entity)` 整行回写，
而 `entity` 是校验**之前**的快照 —— 刚写入的 `totp_last_step` 被旧值覆盖 → **防重放静默失效**
（同一枚动态码可反复登录，而除"重放"那一条外的断言全部照常通过）。
修法：只 `set(lastLoginAt)` + `set(updatedAt)`。

**验证**：`admin-2fa-smoke.ps1` **54/54**；单测 **604/0**；前端 `tsc` + 三端构建 + 两项静态检查全绿。

### 2026-10-10（E20 收尾 · 全量回归：两类旧断言没跟上行为变化）

E20 镜像下跑全量回归（28 套，`search` 需 ES 被显式跳过），暴露 4 处红，**均非产品缺陷**：

- **跨店越权由 403 变 404（`privilege-matrix` 3 条 + `seller-smoke` 5 条）**：
  「看 / 改 / 下架他人商品」「另一卖家查看 / 发货 / 退款订单 A」期望 403、实返 **404**。
  E20 的租户行级过滤让越权者在 SQL 层就查不到该行 → 按防枚举口径返 404。
  被拒的事实不变（「商品状态未被改动」、§6 发货正对照仍绿），属**拒绝方式升级**。
  顺带理顺口径：`seller-smoke` 原有「不存在(404) vs 非你的(403)」的刻意区分，E20 后统一为 404
  —— 这正是防枚举想要的。断言改 403 → 404，并写明**不可**放宽成「403 或 404 皆可」（否则放开成 200 时失去判别力）。
  ★ 顺带**全局排查**仓内 50 多处 `-eq 403`，其余均属「角色 / 凭据 / 网关入口」类，不受租户过滤影响
  （`aftersale` 101/0、`review` 61/0、`seckill` 51/0 实测全绿印证）。
- **拿「这一次调度做了几张」当判据（`coupon` 1 条 + `settlement` 2 条）**：
  `sweep.expired=0`、`sweep.autoCompleted=0 / fundsReleased=0`，但同行终态断言全绿。
  **定时调度抢跑**所致（时序竞态，全量跑得慢更易踩中 → 「单跑绿、全量红」）。
  两套脚本各自的另一条同类断言早已用「或调度已先行处理」写法，这两处漏了 → 补齐为**终态判据**。

修后：`privilege-matrix` **82/0**、`seller` **99/0**、`coupon` **145/0**、`settlement` **125/0**；
静态守卫 **48 脚本 PASS**；全量 **28 套全绿**。

★ **E20 反向验证**（交付前最后一道）：临时注掉 `ProductServiceConfig` 的数据层租户注册 → 重建部署 product
→ 跑 `tenant-isolation-smoke` **恰好红 §3 那 3 条**（=0 只看自营 / =第三方只看自己 / =空店铺看不见任何），
**正对照「未声明租户 → 两个都看得见」保持绿** → 「只有正对照会假绿」的设计意图成立；
还原 + 重建部署后复跑 **37/37**。

### 2026-10-10（#277 续 2 + E20 · P3 第 5 项：全量冒烟首次真跑起来，顺手把「静默误绑定」做成永久守卫）

**一、#277 收口（`18be1a8` → 运行 `38039084959`）**

`编译检查` ✅、**`冒烟子集` 首次 success**（此前一直 failure）；`全量冒烟` 仍红，但这次**真跑起来了** ——
汇总表从「26 套 (无汇总行)」变成 **共 27 套，通过 22 套，失败 5 套**。四个根因：

| # | 问题 | 为什么现在才现形 | 修法 |
| -- | -- | -- | -- |
| ⑤ | `mq-smoke` 32/13，**连带** `order-smoke` 67/10、`payment-smoke` 64/8 | `$env:TEMP` **在 Linux 上不存在** → `Join-Path $env:TEMP …` 全部抛 `Cannot bind argument to parameter 'Path' because it is null` → **限流段 30 发一发都没发出去**（`limited=0`/`ok=0` 假红灯），且临时文件没清、**冻结没释放** → `order`/`payment` 的「可售 316 vs 320」「冻结量从 0 开始」全跟着红。`mq-smoke` **不在 `SMOKE_SCRIPTS`** → 「本机绿、子集绿、只在全量红」 | `[System.IO.Path]::GetTempPath()`；`mq-smoke` 纳入 `SMOKE_SCRIPTS` |
| ⑥ | `mq-smoke` ★首条任务 30s 未到 `PLACED` | 该套自管环境时把 trade **重建**为 queue 模式 → JVM 全新 + RocketMQ 消费者组 rebalance，**第一条消息的延迟是冷启动**、不是链路延迟。本机消费者早热 → 本机绿、CI 红，且红在 **SLA 断言**上，看上去像「削峰变慢了」 | 加**冷启动预热**：一条**独立买家**的任务把消费者焐热（预算 180s、不计入 SLA），随后取消释放冻结。用独立买家是为了不在正式买家的订单列表里留订单（否则「订单已产生且为 1 条」被顶成 2，**把环境问题伪装成计数问题**） |
| ⑦⑧ | `coupon` 144/1、`seckill` 50/1，各一条 `… -> 10/10/2027 08:54:14` | **与 ④a 同一个** pwsh 7 `ConvertFrom-Json` 把 ISO 串自动转 `[datetime]` 的坑 | 改断言**原始报文**（coupon 是「所有 `endAt` 全部合规」而不是挑一个） |

**二、新增永久守卫 `scripts/lib/check-smoke-static.ps1`**（接进 CI **编译检查 job 第一步**，纯静态、比编译还便宜）

- **规则 1**：`Check` 判据不得是「裸的比较表达式」。实测 `Check "x" (管道).Count -eq 0 "extra"` 会被 PowerShell
  **静默误绑定** —— `$ok` 拿到 `(管道).Count`、`-eq 0` 被吞，于是「四个令牌全在」照样打印 `[FAIL]`
  （**今天新写的租户套件就踩了这一条，且不报任何错**）。而仓库里常见的 `Check "x" ((f) -eq 'str')`
  是**安全**的（实测不等时返回 `False`，不是「永远为真」）→ **不要为了统一风格去改那 50 处**。
- **规则 2**：`.ps1` 必须带 **UTF-8 BOM**（项目铁律，此前**无任何自动校验**）。顺手补上唯一漏网的
  `scripts/docker-mirror.ps1`。规则只扫自己的脚本（排除 `scripts/e2e/node_modules` 里 Playwright 的 9 个）。
- **反向验证**：故意违规的假仓库 → **恰好 2 项违规、exit 1** 并给出改法；真仓库 → 48 个脚本全绿。
  失败方向也确认过：不加外层括号的写法（`(F) -eq '0'`）是**响亮报错**，不会静默通过。

**三、E20 租户隔离硬化**（详见 §4 已完成表 E20 行）

口径纠正（租户键 = **店铺主体 userId**，不是 `t_merchant.id`）→ 四层落地（口径/传播/数据/搜索）→
`tenant-isolation-smoke.ps1` **37/37** + `TenantContextTest` 9 项 + 全仓单测 **588/0**。

**验证**：`coupon 145/0`、`seckill 51/0`、`mq 48/0`、`order 77/0`、`payment 72/0`、`tenant-isolation 37/0`；
三处容器**拆包核验**（`BOOT-INF/lib/duoduo-common-1.0.0.jar` 内 `TenantTables`/`TenantContext`/`TenantKeys`、
`duoduo-common-web-1.0.0.jar` 内 `TenantContextInterceptor`/`TenantLineInterceptors` 均在）。

### 2026-10-10（#277 GitHub Actions「两三页全红」定位与修复 —— 两个「只有空库 / 干净检出才现形」的坑）

**症状（API 实测，非推测）**：仓库共 **99 次运行、97 次失败**；最后成功停在 `2026-09-27 02:50`，
从 `feat(social): 新增社交域` 起**再无一次绿**。
★ 关键判据：**`编译检查`（后端 10 模块 + 前端 tsc/vite）从头到尾全绿** —— 主线代码没问题；
红的恒定是「**起全栈**」那一步（容器全栈没起来，冒烟压根没开始跑）。

**根因① mysql 全新初始化崩溃：`ERROR 1048 Column 'seller_id' cannot be null`**
`docs/sql/02-duoduo-product.sql` 的归属回填写成 `SET seller_id = (SELECT id FROM t_user WHERE phone=…)`：
子查询**查不到人时返回 NULL**，而 `seller_id` 是 `NOT NULL` → 整份全新初始化在此中断。
而这三个手机号（`13550931548` / `13765569756` / `13900000002`）在 `01-duoduo-user.sql` 里**根本不存在**
（只在回填语句自身出现）—— 是「测试账号清零」留下的死引用。
**行号一路漂移（257 → 289 → 337）说明它一直没被修掉。**
**杀伤力**：本机是老库、数据早就在 → **本地永远看不到**；CI 每轮都是空库 → **每轮必炸**，
而症状只表现为「docker compose up 失败」，极难往种子上想。
**修法**：改为 `UPDATE t_spu s JOIN duoduo_user.t_user u ON u.phone=… SET s.seller_id = u.id`
—— 语义正是「有账号就回填、没有就跳过」，幂等可重放、绝不 NULL 赋值；`incremental/15` 同款写法一并改。

**根因② 前端镜像构建失败：`"/apps/mall/dist": not found`**
`frontend/Dockerfile` **刻意不在容器内构建**（复用宿主依赖缓存），直接 `COPY apps/${APP}/dist`；
而 `frontend/.gitignore` 排除 `dist/` → **干净检出没有 dist**。
三个站点加入 compose 时，CI 的 `smoke` / `smoke-full` 两个 job 只跑 `mvn package` 就 `compose up --build`，
**从没构建前端** → 三个前端镜像全部构建失败 → compose 中止。本机 dist 建过一次就一直躺着 → **又是「本机绿、CI 炸」**。
**修法**：两个 job 在起全栈**之前**各加 `Set up Node 20` + `npm ci && npm run build`；
另把 `privilege-matrix-smoke.ps1` 补进 `SMOKE_SCRIPTS`（E19 新套件不能不进 CI，见 ci.yml 文件头 E27 教训）。

**根因①的独立验证**：`docker run --rm mysql:8.0.36` 挂 `docs/sql` 做**全新初始化** →
00–06 全部跑完、`ready for connections`、零 ERROR（顺带确认 `incremental/` 被 entrypoint 有意忽略，T-46 的口径仍成立）。

**顺带修掉 E19 自身的「跨套件污染」**：E19 用了 `Resolve-SkuFixtures`（会在自营 SPU 1001 下「缺啥建啥」）
却**从未调用 `Remove-SkuFixtures`** —— 而 `catalog-fixture.ps1` 文件头恰好写着
「夹具越跑越多正是历轮把种子库搞乱的老路」。
后果：SPU 1001 挂着 3 个夹具 SKU → **`smoke.ps1` 的「商品详情应有 2 个 SKU」被挤红（实得 4）**，
红的是那个与越权毫无关系的套件。历轮还遗留 7 个「矩阵夹具」SPU。
**修法**：§9 收尾由「仅下架」改为**真删除 + 自证**（`Remove-SpuFixtures` / `Remove-SkuFixtures` +
`COUNT(*)==0` 断言）；删除前先核实订单 / 购物车 / 审核抽样三条引用链全为 0 再动手。
`address-smoke` 的前置断言在不一致时改为**打印占用该 SKU 的未关闭订单**（单号/用户/状态），让失败自带归因。

**★ 一个值得记的插曲**：新加的「收尾自证」断言第一次跑就红了，报 `left=3 created=3` ——
它抓到的是**我自己**：补 `Remove-SkuFixtures` 调用时漏写了那一行。
**断言的价值恰在这里：它把「我以为我删了」变成了一条会红的机器结论。**

**验证**：矩阵 **82/82**（含 4 条收尾自证）、`smoke.ps1` **62/0**、`address-smoke` **82/0**；
库内 SPU 1001 恰好 2 个 SKU、93/97/99 段夹具全归零。

### 2026-10-10（E19 · P3 第 4 项：权限越权回归矩阵 —— 把"防住了什么"变成机器结论）

**为什么要这一轮**：E16 定了"谁能做什么"的唯一口径，E17 让它即时生效，E18 让它留痕。
但三者都只保证**机制本身**成立，「机制真的被用在了每一个该用的地方」没有任何机器结论。
三类缺口的共同点是**失败方向全部指向「静默可用」**，日常点页面点不出来：
注解写漏 → 端点静默变成公开，接口 200、无异常、无日志；
归属校验写漏 → 别人的数据可读可改；
入口裁剪写漏 → 跨端令牌能进对面站点。
更要紧的是**没有单一出口**：断言散在 seller / order / audit-sla / audit-log 四套件里，
想回答"现在到底防住了哪些越权"得翻四份日志 —— 而**回答不了的问题等于没在守**。

**交付**：`scripts/privilege-matrix-smoke.ps1`，**79 条断言 / 10 节 / 8 个模块**，
被 `run-all-smoke.ps1` 与 `check-all.ps1` **按文件名通配自动收录**（= 总纲要求的"纳入一键自检"）。

| 节 | 模块 | 覆盖 |
|---|---|---|
| §1 | 收货地址（user） | 改/删/设默认他人地址 → **404**（刻意不区分"无权限"与"不存在"，防 ID 枚举）+ 负向生效验证 |
| §2 | 商家子账号（user） | **纵向**：店员做店主动作（拉名单 / 邀请 / 停用）→ 403；**横向**：跨店停用店员 → 404；平台运营走统管端点 |
| §3 | 后台入口（网关） | 商家令牌进 `/api/admin/**`、买家令牌进 `/api/seller/**` → 403；正对照：合法入口 200 |
| §4 | 服务端判权（**直连**） | 注解式与命令式两条路径 × USER/SELLER/OPERATOR 三态；**同一 userId 只改角色** → 差异只能来自角色判定；无身份头 → 401 |
| §5 | 商品（product） | 跨店看/改/上下架 → 403 且状态未被改动；**E28 双路**：干净内容 `AUTO` 自动放行（**必须写 `audit_at`**）vs 命中联系方式转人工 `PENDING` |
| §6 | 审核台（product） | 商家/买家令牌 → 403；**直连**：USER → 403、**卖家本人不能自审自己的商品** → 403；正对照：运营审核通过 200 → 过审后买家可见 |
| §7 | 订单（trade） | 买家跨用户查/取消 → 404；**自营订单 `seller_id=0`**：卖家列表里没有、发货被拒 |
| §8 | 审计日志（user） | 买家 / 商家 → 403；运营 → 200（E18 刻意开放）；**不可删改**：对审计发起 DELETE 非 2xx |
| §9 | 收尾 | 运营降权 + 清审计行 + 全库 OPERATOR 数不增 + 夹具商品下架 + 断言总数守卫 |

**★ 三条断言纪律（写在这里，改动前先读）**
1. **每条「越权被拒」都必须配一条「有权者能过」的正对照。**
   只有反例的矩阵在"整条链路坏掉、所有人都 403"时照样全绿 ——
   那时它测的不是权限，而是服务还活着没有。
2. **网关层与服务端层必须分别断言。** 这是 E16 的核心结论：
   只断言网关态时，入口收窄会**先**返 403，于是"注解有没有注册、命令式判权有没有生效"
   完全没被测到 —— 注解压根没加也照样绿。所以 §4 一律**直连 8081/8082/8083**，
   自己写 `X-User-*` 身份头。
3. **状态码必须写具体值。** 写 `-ne 200` 会把 400（参数校验先拦）、401（令牌失效）、
   500（服务炸了）一并算成"越权被正确拒绝"。只在设计上确实允许两种答案时
   （跨店不可见 = 403 或 404）才写成闭合集合，并在断言名里注明理由。

**★ 本轮把三条纪律各自撞了一次（都是脚本自身的缺陷，不是产品缺陷）**
- **夹具取错字段**：邀请店员响应是 `data.memberId` 而不是 `data.id`。取错**不会报错**，
  只会让后面所有"停用成员"的调用打到 `/api/seller/staff//disable` 上拿回 **404** ——
  而 404 与 403 在这里看起来**都像"越权被挡住了"**，是一条典型的假通过。
- **断言锁在旧口径上**：我按 E14 的旧语义断言"第三方提交后审核态 = PENDING"，
  而 E28 已经把它改成**默认放行**。真相是 `AUTOMATIC/APPROVED`。
  **修法不是把期望值改宽**，而是**两条路各建一个商品**：
  干净内容走 `AUTO`（并断言 `audit_at` 已写），标题含手机号走转人工 `PENDING` ——
  断言因此比原来更强（同时守住自动放行与转人工两侧）。
- **不带 `Content-Type` 先撞 415**：`POST /api/orders/{no}/cancel` 的请求体是
  `@RequestBody(required=false)`，但**不传 Content-Type 会先返回 415**
  （UNSUPPORTED_MEDIA_TYPE）→ "越权被拒"的断言拿到一个与权限完全无关的状态码。
  传一个空对象既合法，也让 403/404 成为这条链路上唯一可能的结果。

**★ 反向验证（本轮最有价值的一条证据）**：临时注释掉
`SellerProductService.audit()` 里的 `OperatorGuard.requireOperator()` → 重打包部署 →
E19 **77 通过 / 2 失败**，红的**恰好**是那两条服务端直连断言
（`USER 调商品审核 → 403`、`卖家本人调商品审核 → 403`）；
而**网关层那两条依旧是绿的** —— 因为入口先挡住了。
这正是纪律 2 的活证据：**如果 E19 只测网关层，这次判权被拆掉将完全无感。**
随后代码原样还原（`git diff` 为空）、重新打包部署、复跑 **79/79 全绿**。

**与既有套件的覆盖关系（刻意保留的重叠）**：seller-smoke 的商品归属 403、
order-smoke 的跨用户订单 404、audit-sla-smoke 的 E16 直连三角色、audit-log-smoke 的
审计角色对照 —— 这几处与本套件重叠，是**有意保留**的：矩阵要能被单独读懂，
不能要求读者先去翻另外四份日志才能拼出一张表。代价是改归属逻辑时这几处要一起改，
换来的是单一出口。

### 2026-10-10（E18 · P3 第 3 项：后台操作审计日志 —— 一次写操作 = 一条不可删改的记录）

**为什么要这一轮**：E16/E17 把"谁能做什么"、"什么时候生效"都收口了，但**发生了什么**没有留痕。
后台 37 个写端点（改角色、强关订单、退款裁定、审核改判、资质停业……）此前只有零星几处手写 insert，
覆盖不全、字段不一、还可能漏掉"被拒绝的那次尝试"。审计的价值恰恰在**被拒绝的那次**。

**★ 为什么用「拦截器 + 注解」而不是 AOP**：项目本来就没有 `spring-boot-starter-aop`。
为一条横切关注点引一套代理机制，代价是启动期多一层代理、排查时多一层调用栈；
而 Spring MVC 的 `HandlerInterceptor` 已经在用（`UserContextInterceptor` / 权限拦截器），
加一个同族成员**零新依赖**，且**能拿到响应状态码**（这是 AOP 拿不到的）。

**★ 四个写错就一定出事的点（每条都有对应断言 + 反向验证）**

1. **审计拦截器必须注册在权限拦截器之前**。Spring 只为 `preHandle` 返回 true 的拦截器回调
   `afterCompletion` —— 顺序反了，"越权被拒"这条**最该留痕**的记录压根不会产生。
2. **失败判定必须同时看响应状态码**。业务异常被 `GlobalExceptionHandler` 消费掉之后
   `ex == null`，只看 `ex` 会把**被拒绝的操作**记成 `SUCCESS`。
3. **不可删改的判据是类目**：`SECURITY`（权限/身份）与 `FUNDS`（资金）两档；
   **解析失败折叠到最严档 SECURITY** —— 与「未知角色折叠为 USER」同一原则：
   失败方向必须指向更安全的一侧。
4. **枚举名即落库值**。E18 之前已经有 9 个动作标识写进过 `t_audit_log`，
   这 9 个必须逐字不变，否则历史检索会分叉成两套词表。

**★ 审计是旁路，不是业务链路的一部分**：写入失败只打 ERROR、**绝不外抛**
（记账失败不该让"改角色"这件事失败）；跨域（product / trade / fulfilment）经 Feign 转发，
**转发失败降级写本域日志**（宁可落在错的库里，也不能丢）。

**交付物**

- `duoduo-common`：`AuditAction`（39 个动作标识）/ `AuditCategory` / `AuditOutcome` /
  `AuditLogRecord`（超长**按码点**截断，`substring` 会把代理对切半）/ `@Audited` /
  `AuditTrail`（ThreadLocal 上下文 + 唯一抑制口 `skip(reason)`）/ `AuditSink`
- `duoduo-common-web`：`AuditInterceptor`
- `duoduo-user`：`AuditLogService`（**唯一写入口 + 检索**）/ `AdminAuditLogController`
  （`/api/admin/audit-logs`）/ `AuditLogInternalController`（跨域转发）/ `LocalAuditSink`
- product / trade / fulfilment：各一个 `AuditLogClient`（`contextId` 区分，同名会启动崩溃）
- DDL `docs/sql/incremental/31-e18-audit-log.sql`：14 列 + 4 索引。
  **存量 188 行全部落 `OTHER/SUCCESS/0`**（新增维度不让存量数据变形）
- 前端：后台「操作审计」页 `/admin/operation-audits`，**只读、无任何修改/删除入口**；
  「不可删改」这个标记**由服务端下发布尔值**，前端不自行判断
- 网关：`/api/admin/audit-logs` 显式路由（网关按子路径枚举，不走 `/api/admin/**` 兜底）

**★ 本轮修掉 3 个真实/潜在缺陷**（都不在"写新功能"的路径上，是测试把它们挖出来的）

1. **`AuditLogService.search` 打开页面就 500**：`.eq(hasText(x), col, x.trim())` ——
   **Java 会先求值所有实参再调用**，所以不带动作过滤（= 打开审计页的**默认查询**）时
   `x.trim()` 直接 NPE。修法：先算成局部变量。单测新增「只给时间范围不许 NPE」回归用例。
2. **冒烟脚本假通过**：`@($resp.body.data.items).Count -gt 0` 在请求失败（`$null`）时等于 1
   → 三条断言**无条件通过**。修法：加 `Items()` 守卫统一兜底。
3. **`docker compose build` 用旧 jar 打新镜像**：根 Dockerfile 用 `ARG JAR_FILE` 复制宿主机 jar，
   而我只跑了 `mvn test` 没跑 `package` → 43 秒构建完成、镜像里是旧代码。
   铁律重申：**改完代码必须先 `clean package` 再 build 镜像，验收必须拆包看内容**。

**验证**

- 单测 **579/0**（新增 72：common 39 / common-web 15 / user 18）
- **4 组反向验证全部「恰好红对应条数」且集合互不相交**：
  只看 ex 不看状态码 → 红 3；类目未知折叠到 OTHER → 红 4；`start` 不覆盖 → 红 1；`record` 外抛 → 红 1
- 冒烟 `scripts/audit-log-smoke.ps1` **58/58**（含断言总数守卫）：
  5 条静态断言（历史 9 标识保留 / 全仓无审计表 UPDATE·DELETE·TRUNCATE / Mapper 无 update·delete /
  **后台写端点 `@Audited` 覆盖率 37/37** / 只有 `AuditLogService` 与 `LocalAuditSink` 触碰 Mapper）
  + 10 组端到端（成功留痕 12 字段核对、越权被拒留痕为 FAILED、422 记 FAILED、幂等不新增、
  按人/动作/类目/目标检索、时间参数边界、判权 403、immutable 双向）
- CI：`SMOKE_SCRIPTS` 13 → **14 套**
- 前端：`typecheck` 三端全绿、`vite build` 通过、两个产物校验工具全绿、
  三站点真实站点验证（`/admin/operation-audits` 深链 **200** 且路由确实在线上产物里）

### 2026-10-09（E17 · P3 第 2 项：权限即时生效 —— 角色变更从「最长 15 分钟」收敛到「下一个请求」）

**目标**：访问令牌里的角色是**签发时的快照**，改完库里的角色后旧令牌仍带旧角色活到自然过期（15 分钟）。
升级场景下这只是"不方便"，**降级场景下这是"权限收不回来"**。E17 让变更**下一个请求即生效**，
并且**不需要对方重新登录**。

**① 机制：令牌带 `pv`（权限版本），权威值在库、Redis 只作感知副本**

| 位置 | 内容 | 口径 |
|---|---|---|
| `t_user.perm_version` | 权威值（`docs/sql/incremental/30-e17-perm-version.sql`） | 与变更同事务、可审计、重启不丢；`NOT NULL DEFAULT 0` 与 `PermVersion.INITIAL` 对齐 |
| `duoduo:perm:ver:{userId}` | Redis 感知副本 | 用户服务写、网关读（网关刻意不连业务库）；键名 / TTL / 解析规则**只有一处定义**（`duoduo-common` 的 `PermVersion`） |
| 令牌 `pv` 声明 | 访问令牌 | 签发时从库内现读；**刷新令牌刻意不带** —— 若刷新也校验，版本过期时连刷新都做不了（401 → 刷新 → 401 死循环） |

**② 三类边界情形（本机制唯一的新失败面，逐条写死）**

- **Redis 查不到 → 放行**：失败方向指向可用；一次缓存抖动不该等于"全站已登录用户集体掉线"。
  代价只是退回 E17 之前的行为。
- **变更时副本写不进去 → 退回吊销刷新令牌**，并把结果如实写进响应文案 ——
  否则出现"运营界面写着权限已收回、实际旧令牌还能用"的假象。
- **副本落后 → 签发时自愈**（每次登录 / 刷新 / 重签都按库内值覆盖写一次），不会永久卡住。
- Redis 不可用时**冷却 60 秒**（与 AI 域 `IpRateLimiter` 同一考虑）：不能把连接超时叠到**登录**延迟上。

**③ 网关侧判定（`PermVersionChecker` + `JwtAuthGlobalFilter`）**

- 不一致 → **401（不是 403）**：401 才触发前端"刷新并重放"，403 不会 → 写成 403 就得手动重登，丢掉"无需重登"。
- 独立 code **`PERM_VERSION_STALE`**：与"登录真过期"分开，排障时能分清两件事。
- **无 `pv` 的老令牌不校验**（与 E8 的 `ent` 同姿势）；★ 反过来把"没有声明"当成版本 0 是**最危险**的错法
  （所有老令牌会因 0 ≠ 当前版本被判过期）。兼容窗口内此判断**零成本**（连 Redis 都不读）。
- 接口返回 `Mono<Optional<Long>>` 而不是"可空值 / 空 Mono"：★ Reactor 的 `Mono.empty()` 是**会被 downstream 跳过**
  的信号，用它表示"未知"会让"放行"分支根本不执行（请求挂住）；用 `Optional` 把"未知"变成真实信号值。
- 短超时 150ms + 失败即"未知"；**不做本地缓存**（缓存会直接削弱"下一个请求即生效"）。

**④ 刻意不做**：命令式判权点（约 55 处 `requireOperator()`）不逐个改注解（与"生效时机"正交，改了只增 diff 与漏验证风险）；
不引阻塞式 Redis 模板（WebFlux 上会卡事件循环）。

**⑤ 顺带拆掉一个"看起来更安全"的陷阱**：`changeRole` 原来**无条件**吊销刷新令牌。
E17 之后若顺手保留，表现是"版本收口已生效、却还是把人踢去重登"——
这行很容易被当成"多加一道保险"加回来，所以单测专门钉住 `广播成功时不吊销刷新令牌`（反向验证恰好红它 + 提权那条）。

**⑥ 交付物**

| 类别 | 文件 |
|---|---|
| 新增（common） | `security/perm/PermVersion`（键名/TTL/解析唯一口径） |
| 新增（user） | `support/PermVersionPublisher`（带降级冷却的副本写入） |
| 新增（gateway） | `support/PermVersionChecker`（契约）/ `support/RedisPermVersionChecker`（reactive 读 + 短超时） |
| 新增（测试） | `PermVersionTest` 7 / `JwtTokenServicePermVersionTest` 7 / `PermVersionPublisherTest` 6 + 网关 7 + user 组 4 |
| 新增（冒烟/工具） | `scripts/perm-version-smoke.ps1` 34 项、`scripts/lib/legacy-jwt.mjs`（独立手签对照令牌） |
| 新增（DDL） | `docs/sql/incremental/30-e17-perm-version.sql`（`01-duoduo-user.sql` 与 `db/schema.sql` 已同步） |
| 改动 | `JwtTokenService`（`pv` 声明 + 兼容重载）、`UserEntity`、`AuthService`（签发/重签带版本 + 自愈回写）、`AdminUserService`（原子自增 + 广播 + 条件降级）、`GatewaySecurityProperties`（开关）、`JwtAuthGlobalFilter`、两个 `pom.xml`、两份 `application.yml`、`docker-compose.yml`（网关/用户服务接 Redis）、`.github/workflows/ci.yml`（子集 12 → **13 套**）、`README.md` |

**⑦ 验证**：单测 **506/0**（新增 31）；**三处反向验证均"恰好红对应条数"**（网关不比对 → 2 条；无条件吊销 → 2 条；
无声明当成 0 → common 2 + gateway 1）；冒烟 `perm-version-smoke.ps1` **34/34** 且已进 CI 子集；全量冒烟与全量 e2e 见「十二、变更记录」同条。
★ 本轮实测暴露的打包陷阱：`mvn -pl <模块>` **不带 `-am`** 会拿本地仓库里的过期 `duoduo-common` jar，
表现出来是一串"找不到符号 `PermVersion`"（像是新写的类不存在）。

### 2026-10-09（E16 · P3 第 1 项：RBAC 权限点体系 —— 判权口径收敛到一处）

**目标**：把「谁能做什么」从**散落在 6 个模块的 20+ 处判权**（裸角色字符串比较 / 手拼 `"OPERATOR".equals(role)` / 各处自写默认值），
收敛成 `role → perms[]` **一张矩阵** + **声明式校验**。**硬约束：行为零变化** —— 谁原来能做什么，改完一模一样。

**新增（`duoduo-common` / `duoduo-common-web`）**

- `security/perm/Permission`：18 个权限点，命名 `域:对象:动作`；清单 `ALL` 用 `LinkedHashSet + unmodifiableSet` ——
  `Set.of` / `Set.copyOf` 的**迭代顺序不稳定**，而测试要按序遍历（失败信息要读得出"第几条挂掉"）
- `security/perm/RolePermissionMatrix`：**唯一权威矩阵**。ADMIN=全部；OPERATOR=全部**减去「改角色 + 资金」**（与旧 `isAdmin()` 门等价）；
  SELLER=仅 `seller:console`；USER=空。**未知角色折叠为 USER**（失败方向必须指向拒绝）。角色归一化只做一次（旧代码 `equalsIgnoreCase` 与 `toUpperCase` 混用）
- `security/perm/RequirePerm`：注解，**方法级优先于类级**；刻意**不提供**「类级默认 + 方法级豁免」（豁免语义最容易变成绕过漏洞）
- `web/PermissionInterceptor`：**必须注册在 `UserContextInterceptor` 之后** —— 顺序颠倒的表现是「带注解的端点一律 401」，很容易被误读成"鉴权更严了"

**改造（口径收敛；调用点刻意不动）**

- `LoginUser`：`isOperator()` / `canAccessSellerConsole()` 改为查矩阵，新增 `hasPerm()` ——
  **约 55 处 `OperatorGuard.requireOperator()` 调用点一行未改，语义已统一到矩阵**
- `OperatorGuard.requireOperator()` → 委托 `requirePerm(platform:console)`；**403 文案逐字保留**（`SellerProductServiceTest` 断言了原句）
- `AdminUserService`：`actor.isAdmin()` → `hasPerm(user:role:manage)`；`ALLOWED_ROLES` → 引用 `RolePermissionMatrix.KNOWN_ROLES`（消掉重复的一份角色清单）
- `MerchantOnboardingService.readMaterial`：手拼 `"OPERATOR".equals(role) || "ADMIN".equals(role)` → `hasPerm(merchant:material:read)`
- `AdminAuditSlaController`：4 处命令式判权 → 类级 `@RequirePerm(Permission.AUDIT_SLA)`（**首个注解式落地**）

**测试**：`RolePermissionMatrixTest` **17 条** —— 真值表（4 角色 × 18 权限点，**独立于实现书写**）、真值表 key 与 `Permission.ALL` 一一对应、
反射校验常量都在 `ALL`（防「加了常量忘了登记」）、**★ 等价性（矩阵 vs 旧 `isOperator`/`isAdmin`/`canAccessSellerConsole` 逐角色一致）**、
归一化 / 未知角色降级 / `null` 权限点拒绝 / 不可变性。
**★ 反向验证**：故意给 SELLER 加 `platform:console` → 测试**恰好红 3 条**，改回即全绿。

**冒烟**：`audit-sla-smoke.ps1` 54 → **62 项**（新增 8 条权限断言）。
**★ 关键手法：验注解必须直连服务端口** —— 走网关会被 E8 的入口收窄**先**返 403（那个 403 与注解无关 → 假通过）；
直连 product `8082` / user `8081` 做双向断言（USER·SELLER → 403、OPERATOR → 200、未登录 → 401、商家端门槛）。
探测用**只读的 `/samples`** 而非 `/metrics`（后者会顺带写库，而此刻自毁刚把比例复位）。

**顺手修**：`t_user.role` 的 DDL 注释与 `UserEntity` 注释**都漏了实际在用的 `SELLER`**（同一块事实两个说法）。
**刻意不做**：其余命令式调用点**不逐个改注解**（改名制造大面积 diff 与漏验证风险，收益为零 —— 口径已统一在矩阵）；前端 `roles.ts` 保持角色判定（**体验层**，边界始终在后端）。

**★ 顺带修掉 E28 的一处遗留（全量回归抓出来的）**：全量 e2e 跑出 `merchant-audit` / `product-audit` 两套红，
归因是 **E28 把审核改成「默认放行」后这两套的夹具假设（"提交后必落 PENDING"）失效** —— 干净内容现在直接 APPROVED，审核台队列空了，后续断言链全部落空。
**不是 E16 引入的**（E16 行为零变化），而是 **E28 提交时没跑全量 e2e** 留下的欠账（CI 至今无 e2e 作业 → 与「套件不在 CI 里 = 迟早静默腐烂」同型）。
修法：夹具**故意命中风险规则** → 转人工 → 才落 PENDING：
· `product-audit` 商品标题 → `E14审核夹具商品-微信abc12345-<ts>`（命中 `SpuRiskJudge.CONTACT_INFO`）
· `merchant-audit` 店铺名 → `审核台官方夹具店-<ts>`（命中 `MerchantRiskRules.IMPERSONATION_NAME`）
两套单独重跑：`product-audit` **36/0**、`merchant-audit` **27/0**。

**验证**：单测 **475/0**（新增 17）；冒烟 **62/0**；**全量冒烟 24 套 0 失败**；**全量 e2e 22 套全绿**；改的是**被所有服务共用的判权组件** → 按纪律跑**全量回归**；容器重建后**拆包核验**确认新类确实在包内。

---

### 2026-10-09（E28 审核 SLA 落地：默认放行 + 事后抽查）

**一句话**：把入驻（E11）与商品（E14）的审核从**强制前置人工**改为**默认自动放行 + 按规则事后抽查**
（方案见《开发执行总纲》附录 D，用户确认）。人工审核不再是上线必经环节，而是**抽样复核**。

**① 数据面**（`docs/sql/incremental/29-e28-audit-sla.sql`，全量脚本 `01-duoduo-user.sql` / `02-duoduo-product.sql` 已同步）

- `duoduo_product.t_spu.audit_source`：`AUTO` / `MANUAL` / `SELF` / **`NULL`=历史（刻意不回填，回填会凭空制造"这批是人工审过的"假事实）**；
- `duoduo_product.t_audit_sample` + `t_audit_sample_rate`（**单行表 id 固定 1** —— 比例必须能被自动改写，写进 yml 就成"改配置再重启"，那不叫自动收紧）；
- `duoduo_user.t_merchant.audit_source` + `violation_count`（**只增不减**，唯一重置点是复业）+ `idx_merchant_violation`；
- `duoduo_user` 侧同名 `t_audit_sample` / `t_audit_sample_rate` —— **两库各持一份**：服务边界是"每个服务只连自己的库"，抽不抽查不该取决于对方此刻是否健康；
- ★ **`duoduo_user.t_sched_leader` 补建**：此前缺表 → `SchedLeaderLock` 兜底"返回 true（放行）" → E15 的 `MerchantLicenseJob` 选主**一直静默失效**（任务幂等所以不算错，也正是没人发现的原因）。补建后日志出现「资质到期扫描任务已启用」；
- 幂等键 `uk_biz_date(biz_type, biz_id, sample_date)`，`sample_date` **必须是 DATE**（DATETIME 会让每次重跑都建新行，"幂等"成空话）。

**② 自动放行判定**（`SpuRiskJudge` / `MerchantRiskRules`，纯函数 + 单测）

- 四类风险转人工：违禁类目 / 联系方式 / 价格异常 / **此前被驳回或判过违规**。
- ★ 判定**必须放在内容字段都填好之后**（`create()` 里挪到 `setSellerId` 之后）：提前落状态会让判定读到全空值，空值不命中任何规则 → **全部商品都被自动放行**，且测试很难发现（看起来"都过了"）。
- ★ 联系方式正则收紧：原 `(?:\+?86[- ]?)?(?:0\d{2,3}[- ]?)?\d{7,8}` 会把「净重 12345678 克」判成座机 → 改为「手机号（可含 `+86`/分隔符）」或「**带区号**的座机」。

**③ 抽查规则**（`AuditSlaPolicy`，放 `duoduo-common`：纯函数、无 Spring 注解、两域共用）

- 基础 **5%**；每日**至少 3 最多 20**；当日可抽量 **< 3 则全查**；目标数**上取整**且不超当日放行量；`ratePercent<=0 → 0`（否则出现"比例 0% 却目标 3 条"）。
- **必抽**（不占 optional 配额）：`NEW_SELLER` 新商家头 3 件 / `RESUME` 恢复营业 7 天内 / 有污点 `ESCALATED` 按 **20%**。
- 取样 **`hash(bizId + epochDay) % 100`**（murmur3 finalizer 打散雪花低位），**不用随机数**：可复现、不可卡发布时间规避、比例提高时集合成**包含关系**（5% ⊂ 10% ⊂ 20% —— 这是"收紧不作废上次结论"的全部依据）。
- **自动收紧**：连续 3 天抽中违规率 **> 10%（严格大于）** → 翻倍，封顶 **40%**；抽样为 0 的日子不占观察期。
- 手动入口 `POST /api/admin/audit-sla/sweep` 抽**昨天**那批（与定时器同口径，避免"点两次看到两个结果"）；不看开关、不抢选主锁。

**④ ★ 两个被堵死的绕过路径（本轮最重要的产出）**

1. **自动放行必须写 `audit_at`**：候选集按 `audit_at ∈ [当天,次日)` 筛。`create()` 原为恒 `setAuditAt(null)` → 任务每天照跑、`created=0`、**接口 200 / 无异常 / 无日志**，看起来只是"今天没有可抽的" —— 而它恰恰是"免事前审核"的全部对价。修复 4 处（`create` / `applyContentReaudit` 两分支 / `update` 显式清空 / `resubmit`）。
2. **曾判违规者不得自动放行**：`applyContentReaudit` 对所有已放行商品重跑判定，而判定里没有"你曾被判过违规"这个前提 → **改一个错别字即可自动重新上线**。加 `wasRejected` 短路；**同一条纪律同步入驻侧**：`submit()` / `renewal()` 加 `hasEnforcementRecord`（`violationCount>0 || fromStatus==REJECTED`）—— 否则**因违规停业的店，点一次续期（甚至空请求体）就能自动复业**（它与"资质过期停业"共用同一续期入口）。

**⑤ 复核与处置**：**不可重入**（只有 PENDING 能判，再判 422 —— 否则误判率可被反复点通过刷平）；判合规**不改前台可见性**（抽中 ≠ 下架）；判违规 = 商品 `REJECTED` + 强制下架 + `audit_source=SAMPLE`，再跨域上报违规计数（失败不回滚止血动作），最后落结论。入驻侧判违规走 `suspendByAudit`（**资质造假是主体资格问题，直接停业、不攒次数**），停业用**条件更新** `WHERE status='APPROVED'`（`changed=0` 时不许发通知、不许写留痕），并**联动下架该店全部商品**。`violation_count` 用 `setSql("violation_count = violation_count + 1")` **原子自增**（避免读-改-写丢计数）。Feign 故障/查不到一律**放行**（纵深防御第二道门）。

**⑥ 前端**：后台第 10 个工作台「抽查复核」（`/admin/audit-sla`）—— 两域切换 + 四状态 + 六张指标卡 + 复核弹窗（判违规**必须填依据**，不填按钮禁用）；**指标与队列分开取**（读指标会顺带触发自动收紧评估，不该跟翻页反复执行）。`AuditSlaPolicy` 用 `hash(bizId, date)` 保证"同一天重复跑结果一致"。

**⑦ ★ 本轮还抓到一次"验证本身是假绿"**：改完代码后比对"容器内 jar 与宿主 jar 的 md5"，**逐字节一致**，于是判定"已部署"。但**两边都是旧包** —— 打包发生在改代码**之前**（jar 16:41 / 源码 16:42:48），之后只跑了 `mvn test`（重编 classes 未重打包）。改用**拆包核对**才验出来（`javap` 数关键调用：旧包 **2** 处、新包 **4** 处）。**纪律升级**：验证部署一律"**拆包看内容**"，不比大小/时间/哈希。

**验证**：`mvn -o test` **458/0**（新增 69：`AuditSlaPolicyTest` 35 / `SpuRiskJudgeTest` 21 / `MerchantRiskRulesTest` 13 / `MerchantViolationServiceTest` 12）；
新增 `scripts/audit-sla-smoke.ps1` **54/0**（已进 CI 子集，子集 11 → 12 套）；新增 `scripts/e2e/audit-sla-ui-check.mjs` **56/0**；
三端 `tsc + build` 通过、两个产物校验工具通过（447 条 import 全部可解析 / 三端产物互不包含）；镜像重建后**拆包核验**容器内代码确为最新。
**顺手治了一处会自己烂掉的文档**：`check-all.ps1` 里写死的"17 套 / 23 套 / 228 项"改成**从目录实算**（写死的数字在加套件后变成假话，而没人会记得改它）。

### 2026-10-09（E27 接口冒烟夹具与数据漂移根治；顺带修掉 E29 无品牌商品 500）

**为什么要有这一轮**：E14 收尾时发现全量冒烟 9 套红，逐套归因后确认**全部非代码回归**。
但"红着"本身就是问题 —— 它们既不在 CI 子集里、本地也不是天天跑，**没有任何人会看到它们变红**；
而且这种红会训练出"反正有几套是红的"的心态，真回归出现时反而没人看。
目标因此不是"把红改成绿"，而是**让断言不再依赖环境里恰好有什么数据**。

**① 夹具化**（新增 `scripts/lib/`，三条硬纪律：现场建 / 跑完自毁 / **建不起来就显式报红**）

- `seller-fixture.ps1`：库内直建 `t_merchant`(APPROVED) + `t_merchant_member`(OWNER) + `t_user.role=SELLER`
  （与 `scripts/lib/e2e-seller-fixture.mjs` 同构），三步回读复核 → seller / aftersale / logistics / credit 共用。
- `catalog-fixture.ps1`：五类 SKU（可售×2 / 小库存 / 售罄 / 已下架）+ 关键词商品 SPU + **无品牌商品 SPU**。
- `listing-fixture.ps1`：闲置 1 已过审 + 1 待审 + 1 已驳回。
- id 段位 `97 / 98 / 99` 与种子、雪花都不重叠 → 「库里冒出这个号」一眼可辨是夹具。

**② 断言数据驱动**：九套（smoke / trade / order / ai / review / seller / aftersale / logistics / credit）
的固定种子件数与固定种子 id 断言，改为「先查库内实际值再断言」或「自建夹具自证」。
写死条数钉的是"当前有几条数据"，环境一变就红，而它想守的性质（如"列表只返回已过审的那批"）与条数无关。

**③ CI 从"有人管"这一层收口**：子集 **5 → 11 套**（补 seller / aftersale / logistics / credit / ai / review）；
新增 **`smoke-full` 作业**（push main 跑 `run-all-smoke.ps1` 全量）。
子集只解决"反馈快"，解决不了"没人跑"—— E10 删掉旧端点后这批套件静默红了好几轮，根因即此。
**顺带修掉一个一直坏着的 CI 门禁**：容器健康等待写死 `healthy -eq 10`，服务数涨到 14 后这道门
**永远不可能满足**，每轮 CI 都干等 10 分钟后失败（改为「数量达下限 + 全部 healthy」）。

**④ ★ 夹具反过来挖出的真实生产缺陷（E29，P0 级）**：**无品牌商品的商品列表 / 详情 / AI 候选召回全部 500**。

- **根因**：`ProductService.toCards()` 取品牌名时没判空 → `brandNames.get(null)`；
  `CatalogLookup.names()` 在「一个 id 都没查到」时返回 `Map.of()`，**不可变 Map 的 `get(null)` 直接抛
  `NullPointerException`**（不是返回 null）。品牌自 E14 起是**可选**字段（第三方卖家不填即 NULL）→ 正常路径。
- **为什么一直没被测出来（最值得记的一点）**：只要同页有**任意一条**商品带品牌，
  返回的就是允许 null key 的 `HashMap`，一切正常。而种子数据每条都带品牌 ——
  于是**单测（打桩用了可变 Map，基座注释还断言"真实的 CatalogLookup 返回的是 HashMap"，而那句是假的）
  与冒烟（种子都带品牌）双双假绿**。这是一次典型的"测试自己把缺陷挡住了"。
- **修复**：① `toCards` 补判空（与 `SellerProductService` 三处既有写法对齐）；
  ② `CatalogLookup.names()` 空结果改为返回可变空 `HashMap`，让「查不到」与「id 为 null」同形。
- **验证**：单测 +6（`CatalogLookupTest` 5 + `ProductVisibilityTest` 1）并做**反向验证** ——
  临时摘掉判空，**恰好红掉那 1 条**、其余 9 条全绿；`smoke.ps1` 另加 **5 条端到端守卫**。
  重建镜像后逐模块比对容器与宿主 jar **md5 一致**。

**⑤ 脚本级缺陷（同样只有断言真跑才暴露）**：`review-smoke.ps1` 第 11 节给内部 recompute 传的是
**顶层单元素数组**，PowerShell 管道会**枚举**数组 → `@(@{...})` 被序列化成 `{...}` 而非 `[{...}]`，
服务端 `@RequestBody List<RatingPatch>` 直接 **400**。修复：`ConvertTo-Json` 改 `-InputObject`。

**⑥ 测试端假红一并修掉**：`ai-smoke` 的「预算没听懂时如实告知」曾因**外部大模型超时**假红 ——
`DashScopeClient` 逐个备选模型试（每个 15s），两个都超时就接近 30s，恰好撞上客户端 30s 超时，
**服务端其实已经正确降级并答完**。修复：对话请求超时放宽到 75s + 仅在「传输层没拿到响应」(status=0) 时重试 1 次
（4xx/5xx 是有意义的信号，绝不重试）；失败信息从"只打印 reply"改为**带 status**。

**结果**：九套全绿 —— seller 99/0、aftersale 101/0、logistics 79/0、credit 82/0、trade 87/0、
order 77/0、smoke 62/0、ai 39/0、review 61/0；夹具残留 0（`spu 97*` / `sku 99*` / `listing 98*`）；
后端单测 **368/0**（362 + 6）；全量 23 套回归见 `logs/e27-runall.log`。

### 2026-10-09（E15 · P2 第 7 项：资质有效期与提醒，**P2 收官**）

**问题**：E10 收上了六份资质材料，但**只有"有没有"没有"到没到期"**。证照过期既不会提醒商家，
也不影响任何功能 —— 平台只能靠人工翻材料表；而"一家的营业执照三个月前就过期了"这种事，
在几十家商家之后就必然漏看。E15 把有效期变成**会被系统执行的事实**。

**数据模型：有效期落在材料，不落在店铺**

| 列 | 位置 | 含义 |
|---|---|---|
| `expire_at` | `t_merchant_material` | **每份材料各一份** —— 提醒必须说清**哪张证**要到期 |
| `license_expire_at` | `t_merchant` | **派生冗余** = 所有带有效期材料的**最早**到期日，唯一写入出口 `refreshLicenseExpireAt()` |
| `expire_notify_stage` | `t_merchant` | 已发到哪一档：`T30 / T7 / EXPIRED` |
| `suspended_reason` | `t_merchant` | 停业原因（区分"资质过期"与历史状态） |

- **NULL = 长期有效，不是"已过期"**：加列那一刻存量行全是 NULL，若把 NULL 解释成过期，
  迁移瞬间**全部存量商家集体停业**。与 `t_spu.audit_status` 默认 `APPROVED` 同一取舍 ——
  **新增判定维度时，默认值必须让存量数据维持原状**。（实测：增量 DDL 执行后 58 家商家状态分布一字未变。）
- **到期日含当日有效**：`yyyy-MM-dd` 一并落库为当日 `23:59:59`。
- **展示路径刻意不读派生列** —— `toView()` 实时按材料行算；低频路径没必要接受"材料改了、派生列没刷"的风险。
- 增量 `docs/sql/incremental/28-e15-license-expiry.sql`（4 列 + `idx_merchant_license_expire`）；
  顺手补上 `01-duoduo-user.sql` 漏同步的三张商家表（compose **不递归 `incremental/`**，全新库/CI 建不出表）。

**三条业务规则（每条对应一种真实绕过手法）**

1. **过期停业用条件更新**（`WHERE status='APPROVED'`）—— 扫描与商家续期可能撞在同一瞬间，
   按 id 覆盖会把商家刚提交的续期（已变 `SUBMITTED`）静默压回 `SUSPENDED`，且两边都不报错。
2. **停业联动下架在售商品，但门禁只拦「上架」** —— 停业期仍应能下架、改价、清库存
   （那是在为恢复营业做准备）；一刀切拦住所有写操作，商家的第一反应是"系统坏了"而不是"我该续期了"。
3. **续期不自动通过** —— 回 `SUBMITTED` 重走审核，否则拿一张过期证照的截图就能把停业状态刷回来；
   且续期提交的有效期**仍在过去**时直接 422（走完审核回来照样停业，白白消耗双方精力）。

**两个刻意的取舍**：提醒去重靠**档位**而非布尔值（`expire_notify_stage` 记 `T30/T7/EXPIRED`，
**新档位更深才发一条** —— 只记"提醒过没有"的话，第二天就无处判断该不该再发，只能变成每日骚扰或再也不发）；
**通知失败刻意不记档位**（记了就永远不再发，而商家其实没收到；不记则下一轮重试，最多重复一条）。
**纵深防御第二道门**：商品域上架前查用户域"这家店能不能卖东西"，**查不通一律放行** ——
第一道门（过期即停业 + 联动下架）不依赖用户域可用性；若第二道门在 Feign 抖动时改成"拒绝"，
一次跨域超时就让全平台商家都无法上架。

**侧写**：`LicenseExpiry` 是到期语义的**唯一定义处**（档位 / 剩余天数按**日历日**算 / 最早到期日）；
`MerchantLicenseJob`（选主 + 单轮 200 家上限 + 异常不外抛，6 小时一轮）+ **运营手动巡检入口**
`POST /api/admin/merchants/license-sweep`（共用 `sweepOnce()`，跑生产同一条路径；
不然改完材料想确认后果只能掐表等下一轮）；跨域通道用**已扩到 `duoduo-social` 的 Feign**（刻意不带小程序落地页）
+ 用户域 → 商品域批量停售 `/api/products/internal/seller/{id}/off-shelf`。

**★ 两个由测试驱动暴露并修掉的真实缺陷**

1. **`duoduo-product` 容器跑的是旧 jar**。E15 新增的内部停售端点与上架门禁源码都在，镜像从未重建 ——
   表现是"联动下架 404"+"上架被 E14 审核门禁拦下"，两个症状看起来毫不相干，实际同一个原因，
   且**只有 e2e 断言"商品真的被下架"才会红**。→ 全量重建 11 个镜像 + 逐模块比对 jar md5。
2. **MyBatis-Plus `updateById` 不写 null 字段**（默认策略 `NOT_NULL`）。"续期后清提醒档位 / 清停业原因 /
   重提时清过时驳回原因"这三个**清空**动作全部静默失效：接口 200、`status` 也变了，只有那几列悄悄留着旧值 ——
   没有异常、没有日志，也**不影响任何既有断言**。修法：新增 `clearNullableState()`（显式 wrapper 更新，
   **并同时清内存** —— `toView()` 读内存对象，只清库会让**响应体**带着旧值）；
   单测补齐到**断言 SET 子句**（原来只断言内存字段，而内存里 `setXxx(null)` 恒为 null，**无论实现对不对都恒绿**）。
   （踩到的坑：`getSqlSegment()` 只返回 **WHERE** 段，SET 子句要看 `getSqlSet()`。）

**验证**：后端单测 **362/0**（E15 新增 57）；新增真实浏览器 e2e `scripts/e2e/merchant-license-ui-check.mjs`
**71/71**（长期有效基线 → T30 横幅与提醒 → **同档不重复发** → 升档再发 → 过期停业 + 联动下架 + 审计留痕 +
上架 422 → 双端横幅 → 续期回 `SUBMITTED` 且仍不能上架 → 通过后放行 → 续期拒绝过去日期**且事务回滚** → UI 卫生）；
**全量 e2e 21 套全 PASS**；两个前端校验工具通过；容器与宿主 jar **逐模块 md5 一致**。

### 2026-10-08~09（P2 前五项补记：E9 租户实体 / E10 入驻申请 / E11 审核台 / E12 材料私有通道 / E13 子账号）

> 这五项此前只记在《开发执行总纲》与《项目简报》，本仓 PLAN 未同步 —— 补记在此，避免文档落后于代码。

- **E9** `t_merchant` / `t_merchant_member` 建表（user 库）；`duoduo-common` 新增 `SellerOwnership`
  把"自营"语义集中成单一权威（`SELF_SELLER_ID=0` + 判定），trade 域常量改为引用。
- **E10** 入驻改成状态机（草稿 → 送审 → 通过 / 驳回 → 重提）：6 类材料清单校验、驳回原因结构化
  （`[{scope,code,note}]`）、重复提交幂等；**封死"实名即开卖家"的旧端点**（无审核开店路径不复存在）。
- **E11** 后台 `/admin/merchants` 入驻审核台：材料大图预览 + 一致性核对提示 + 逐条结构化驳回；
  概览页待办入口卡（**刻意不进顶栏** —— 顶栏宽度已到上限，再加一项会静默截断）。
- **E12** 资质材料私有通道：独立私有卷 + 按租户分目录 + 鉴权代理（店主 / 运营可读，其余 403）+
  **每次下载留痕**（`t_audit_log`）+ 借用他人材料路径被拒。
- **E13** 商家子账号：店主按手机号邀请店员，**成员身份不写进令牌**（否则"停用"要等令牌 15 分钟过期），
  改为每次实时查 —— 代价是一次 Feign，换来停用即时生效；平台侧有跨租户处置台。

### 2026-10-09（E14 · P2 第 6 项：第三方商品上架审核）

**问题**：E14 之前，"商品能否被买家看到"只有 `t_spu.status` 一个开关，而它由**卖家自己**控制 ——
上架即在售。P2 引入第三方商家后，任何入驻商家都能直接上架任意商品，平台无人工把关环节。

**方案：审核维度与交易维度正交 + 可见性单点收口**

| 维度 | 字段 | 谁控制 | 取值 |
|---|---|---|---|
| 交易 | `status` | 卖家 | `ON_SALE` / `OFF_SHELF` |
| 审核 | `audit_status` | 平台 | `PENDING` / `APPROVED` / `REJECTED` |

- 可见性判据 = **在售 且 已过审**，由 `ProductService.visibleSpuWrapper()` **单点定义**，
  覆盖 MySQL 7 条读路径 + ES 文档与查询过滤 + 交易侧采购视图；
- 未过审商品 **详情返 404**（与"不存在"同码，防状态码探测）；
- **平台自营免审**：判据是供货类型（`supplyType='self'`）而非"谁在操作"，`auditStatus` 恒 `APPROVED`；
- 增量脚本 `docs/sql/incremental/27-e14-spu-audit.sql`（+4 列 + `idx_spu_audit`）。
  列默认 `APPROVED`（**不是** `t_listing` 的 `PENDING`）：存量商品在售中、审核是新增能力、自营免审；
  第三方由服务端**显式**写 `PENDING`，不依赖列默认值。

**三条业务规则（每条对应一种真实绕过手法）**

1. **内容变更再审** —— 审核结论针对"当时那段内容"。不重审＝允许"先过审、再改违规内容"。
2. **上架门禁** —— 目标态为在售时要求已过审，否则 422。没有它，"先提交 `PENDING`、再自己上架"即绕过整条链。
   422 提示语区分"去等审核"与"去改内容"，不能只说"不行"。
3. **驳回必须填原因** —— 否则卖家不知为何被拒，也留不下可追溯的解释。

**两个刻意的取舍**：审核**可重入**（允许改判；堵死只会把改判逼成改库，留下无法追溯的结论）；
**通过时不动 `status`**（卖家提交时期望在售；若他自己下架过就保持下架，尊重其选择）。

**侧写**：商品域新增 Feign 客户端（实时查成员身份 —— 店员停用必须即时生效，故不进令牌快照、不缓存）；
`SellerProductService` 新增 `SellerScope`/`requireSellerScope()`/`requireOwned()`（自营商品 `seller_id=0` 显式拦截
—— 归属快照在令牌里，`userId==sellerId` 判定会因令牌未轮转而误判）；平台侧审核台 `listForAudit/auditDetail/audit`
（`OperatorGuard.requireOperator()`），网关新增 `/api/admin/products/**` 路由（不进白名单）。

**顺带修掉两个既有缺陷**：`list()` 把 null 塞进 `wrapper.eq(sellerId)` 生成 `seller_id = NULL`（永不匹配 → 空列表）；
主图清空原本静默通过 → 改为 400。

**★ 只在"真启动"才现形的缺陷**：漏 `spring-cloud-starter-loadbalancer`。**单测全绿、`package` 全绿** ——
Mockito 打桩不解析服务发现；只有容器启动炸 `No Feign Client for loadBalancing defined`，服务 unhealthy。
（与 R52「改共用模块必须重建依赖方」、R57「只 compile 不 package 会进旧 jar」同属"验证产物本身"家族。）

**验证**：`mvn test` **305/0**（+45：`SellerProductServiceTest` 36 + `ProductVisibilityTest` 9）；
`scripts/e2e/product-audit-ui-check.mjs` **36/36**（发布→不可见→**先 reindex 让索引确有文档再断言检索不命中**
→门禁 422→通过→上架后可见→改内容回待审→驳回→重提→再通过；收尾删夹具商品，避免在审核队列留永久待办）；
**全量 e2e 20 套全 PASS**；`check-frontend-imports`（438 条）/`check-bundle-isolation` 通过；
三端 `tsc --noEmit` 零错误、`vite build` 通过；容器与宿主 jar **逐模块 md5 一致**。

**★ 自测暴露的遗留（非本轮引入，登记 E27）**：本机 24 套接口冒烟中 **9 套红**，逐套归因后确认与 E14 无关 ——
① `seller`/`aftersale`/`logistics`/`credit` 仍调用 **E10 已删除**的 `POST /api/users/me/merchant`（夹具 404 → 全链级联，
且这 4 套**不在 CI 子集内**，自 E10 起一直未被发现）；② `smoke`/`trade`/`order`/`ai`/`review` 的断言依赖固定种子数据，
而本机库经多轮清理后种子 SPU 由 8+ 件降到 **2 件**、`t_listing` **0 条**、SKU `2004/2005` 与 SPU `1003/1004` 已不存在
（CI 用全新库初始化，不受影响）。判定依据：失败项无一落在本轮改动文件上，且均可用"库内数据不存在"直接解释。

### 2026-10-07（S1：网页端「正式版」容器 —— 不再依赖开发服务器）

> 来源：《优化建议与待确认事项》**S1**。目标：网页**一条命令长期可用**，不再需要命令行窗口，
> 消除"开发服务器随会话被回收 → 用户点开链接打不开"（上一轮真实发生过的交付失误）。

**方案对比（为什么选 A′）**

| 方案 | 做法 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| A 容器内多阶段构建 | node 构建 + nginx 托管 | 镜像自包含、CI 友好 | **本机拉不到 `node:22-alpine`**（Docker Hub 不可达）、`npm ci` 数分钟 | ✗ 本机不可行 |
| **A′ 宿主构建 + COPY dist** | 宿主 `npm run build` → 镜像只含 nginx + dist | **与项目既有约定一致**（各服务镜像都是"宿主产 jar → COPY"）；构建秒级；离线可用 | 需宿主 Node（本机已有）；改前端后要重跑一次脚本 | ✅ **采用** |
| B 把 dist 挂载进 nginx 容器 | volume 挂宿主目录 | 改前端免重建镜像 | 依赖宿主路径、可移植性差、与既有约定不符 | 备选 |
| C 守护化开发服务器 / `vite preview` | 继续用开发态服务 | 改动最小 | 仍是开发态（性能/稳定性差），不解决诉求 | ✗ |

**实现（5 处）**：`frontend/Dockerfile`（nginx:1.27-alpine + COPY dist + HEALTHCHECK）、
`frontend/nginx.conf`（SPA 兜底 + `/api`、`/sandbox` 同源反代 + `client_max_body_size 16m` + 静态长缓存 + index 不缓存）、
`frontend/.dockerignore`、`docker-compose.yml` 新增 `duoduo-web-app`（8088→80，`restart: unless-stopped`，依赖网关）、
`scripts/web-up.sh`（一条命令：构建 → 打镜像 → 起容器 → 自检）。

**为什么不改后端**：前端构建时 `VITE_API_BASE` 留空即**同源**，由 nginx 反代 `/api` 到网关 →
浏览器始终同源 → httpOnly Cookie 正常携带、无跨域预检、**CORS 白名单一个字都不用改**。

**三个坑（都已写进源码注释）**
1. **`.dockerignore` 把 `dist` 排除了** → 构建报 `"/dist": not found`。dist 正是要被托管的产物，不能排除。
2. **`proxy_set_header Host $host` 导致 403**：`$host` **不带端口**，而浏览器发的 `Origin` 是 `http://localhost:8088`；
   网关据此判定"Origin 与 Host 不同源"→ 按跨域处理 → 8088 不在白名单 → 匿名访问 `/api/auth/refresh` 返回 **403**。
   同一份代码在开发态不出现（vite proxy 用 `changeOrigin` 转发成 `localhost:8080`）→ 属**"换了入口才暴露"**的问题，
   也再次说明「换入口/改共用件必须重跑既有套件」。改用 **`$http_host`**（带端口）后恢复 401。
3. **本机 Docker Hub 不可达**（`registry-1.docker.io` 与多个常见镜像源均 000），只有 `docker.1panel.live` 可达 →
   经该源拉取 `nginx:1.27-alpine` 并 retag 成本地标准名。**属一次性应急手段**，长期应给 Docker Desktop 配代理或镜像源。

**验证（可核验）**
- 容器：`duoduo-app-web` **Up (healthy)**、`0.0.0.0:8088->80`；`docker compose up -d` **全栈 11 容器全 healthy**。
- 接口层：首页 200 + `Server: nginx/1.27.5` + index `no-store`；`/assets/*` 30 天 `immutable`；
  **同源** `/api/products` → 200 真实 JSON；SPA 路由 `/messages/123456` → 200（不是 404）；
  `nginx -T` 确认 `client_max_body_size 16m` 与 3 处反代指向网关。
- 浏览器层：**对 8088 跑既有套件（不新写测试）** —— home 9/0、header-layout 88/0、notice-chat-follow 42/0
  → 证明容器承载的是**真应用**（含登录 Cookie、写操作、角色界面）。
- 无回归：开发态（5173）home 9/0、header-layout 88/0 仍全绿。

**遗留**：① 改前端后需重跑 `scripts/web-up.sh`（刻意不做热更新——正式版不该带开发态特性）；
② 首次构建依赖本地已有 `nginx:1.27-alpine`（见坑 3）；③ 未做 HTTPS/域名/对外暴露（本机自用；对外部署见 D10）。

### 2026-10-07（第七十四轮续 · R74-3~R74-6：恒 false 的 isAdmin / 通知·私信·关注 e2e / Web 私信页 / 图表主题）

> 用户指令：按既定计划依次推进剩余待办，每项说明改动、涉及文件与注意点。**顺序按依赖做了一处调整**：
> 「通知/私信/关注的浏览器套件」原本排在「Web 私信页」之前，但 Web 端**当时根本没有私信页面**
> （`App.tsx` 只有 `/notifications`、`/follow`，全仓搜 `socials/messages` 零命中），
> 先写套件只能测接口、测不到界面 —— 所以**先做 Web 私信页（R74-5），再补套件（R74-4）**。

**R74-3 · 消灭恒为 false 的 `isAdmin`**
- 事实：`store/auth.tsx` 里的 `isAdmin: user?.role === 'admin' || 'operator'` 走**全小写**比较，
  而库内角色是**大写**存储（`ADMIN`/`OPERATOR`）→ **恒为 false 且不报错**；当时**没有任何消费者**
  （只有 `Layout.tsx` 里一句「别用它」的注释），属于埋着的坑。
- 做法：**直接删除该字段**，并在 store 顶部写明「这里刻意没有 isAdmin」+ 为什么。
  **删字段本身即防线** —— 将来谁再写 `useAuth().isAdmin`，`tsc` 直接编译失败，不会再静默失效。
- 顺带收敛：`Operators.tsx` 里那份页面私有的 `(user?.role ?? '').toUpperCase() === 'ADMIN'` 抽成
  `pages/admin/shared.tsx` 的 **`isAdminRole()`**，与 `isOperator()` 相邻同源（角色判定不允许各写一份）。
- 验证：`tsc --noEmit` 零错误（**证明无残留引用**）、`vite build` 通过；
  `admin-entry-ui-check` 32/0、`header-layout-ui-check` 88/0。
- 遗留：`/admin/operators` 页的「仅管理员可改角色」控件**仍属 UI 未覆盖**（库内没有 ADMIN 账号，
  浏览器断言无从构造）；其规则由 `admin-smoke.ps1` 在接口层覆盖（自己降权 422 / 最后一个 ADMIN 不可降 422）。

**R74-5 · Web 端私信页（会话列表 + 会话详情）**
- 新增 `pages/Messages.tsx`（列表）与 `pages/Chat.tsx`（详情），路由 `/messages`、`/messages/:peerId`（均 `RequireAuth`）；
  `api/endpoints.ts` 新增 `messageApi`，`api/types.ts` 补 `ConversationView` / `ChatMessageView` / `MessagePageData`；
  头像下拉加「私信」入口（**R75 起升级为全站未读角标** —— 见下文「R75-1」：不再只在打开菜单时现取，
  改为顶栏头像上挂角标 + 三处刷新；合并口径**仍由服务端 `unread-summary` 负责，Web 不另建一套**）。
- 两条必须显式呈现的服务端口径：**`canSend=false` 时禁用输入并说明原因**（非互关每人限发一条，咨询会话豁免）——
  否则用户点发送后静默失败，会以为「消息发丢了」；**`mine` 用服务端下发的字段**，前端不自己比 userId。
- v1 边界（刻意不做，避免半成品）：实时推送（用「刷新」按钮 + 发送后自动拉取）；富卡片消息渲染（`bizType != TEXT` 按文本展示）。
  会话键的上下文段（`productId` / `listingId`）**只透传不拼装** —— 会话键是服务端的事实。

**R74-4 · 新增「通知 / 私信 / 关注」浏览器套件**（`scripts/e2e/notice-chat-follow-ui-check.mjs`，**R74 时 42 项 → R75 起 50 项**，自包含）
- 为什么必须补：这三条是每天都会走的动线，而 17 套 e2e 里覆盖为 **0**。代价已经出现 ——
  **Web 通知页的分类 chip 从 R67 起就是错的**：写的是后端不存在的 `AFTERSALE` / `SOCIAL` / `NOTICE`，
  于是「售后 / 互动 / 公告」**一律筛出空列表**，而真实存在的 `FRIEND` / `MERCHANT` 只能显示英文原名。
  根因是**前端自己发明了一套分类**，而分类是业务事实（服务端白名单 `SYSTEM/ANNOUNCEMENT/FRIEND/MERCHANT/ORDER` 才是唯一来源）。
  本套件把 chip **逐个真筛**（含曾经失效的那几个），这个缺陷因此再也回不去。
- 覆盖：① 通知（夹具推送 3 类 → chip 逐个真筛 → 全部已读后未读归零）；② 私信（列表 → 会话 → 发送 → **非互关限一条**：
  页面禁用输入 + 说明原因，且**服务端同样拒绝**第二条）；③ 关注与粉丝（两个名单各自出现对方；**互关后 `canSend` 恢复**）；
  ④ **全站未读角标**（R75 新增：在**首页**顶栏就能读到角标 → 数字 == `unread-summary.total` → 未登录访客无角标）。
- 两个夹具坑（都已写进源码注释）：**内部端点不经网关** —— `/api/socials/internal/**` 只认内部签名、
  没有用户会话，经网关调用会被网关判成未登录直接 **401**（不是内部过滤器的 403），套件改为**直连 social:8087**；
  **雪花 id 必须按字符串传** —— 首版写了 `Number(meId)`，19 位超出 JS 安全整数被静默改写末位，
  服务端随即查不到该用户 → 404「用户不存在」（**T-39 老坑**）。
- 收尾自清理：删除本套件造的通知 / 私信 / 关注三类数据（通知没有删除端点，走 SQL；用户账号与既有数据不动）。

**R74-6 · 图表主题：单一来源 + 深色变体（刻意不跟随系统）**
- 现状核查：图表配色**本来就已经集中**在 `components/charts.tsx` 一处（PALETTE / 轴线 / tooltip），
  且**应用本身没有深色主题**（`styles.css` 只有一套浅色变量，无 `data-theme`、无 `prefers-color-scheme` 分支）。
- 因此本轮做的不是「让图表变深」（那会做出**浅色卡片 + 深色图表**的不可读半成品），而是：
  新建 `components/chartTheme.ts` —— **全站图表配色的唯一来源**，含 `light` / `dark` 两套值，
  并把接入点收成**一个函数** `currentChartThemeMode()`（当前恒 `light`，将来接全局主题只改这一处）；
  `charts.tsx` 改为全部从主题取值，**文件内已无任何硬编码色值**（grep 为 0）。
- **一条明确的约定**：图表主题**跟随应用主题，不跟随操作系统的 `prefers-color-scheme`**。
  `admin-charts-ui-check` 新增 3 条断言钉住它（用 `colorScheme: 'dark'` 的上下文打开后台页：
  canvas 正常渲染 / 无 `data-theme` / 图表所在卡片仍是浅色底亮度 > 0.8）—— 这是**防「半修复」的闸门**：
  将来谁只在图表里加个系统深色分支，这里会红。
- 参见 §9 **B-18**（全局深色主题刻意不做，前置是整套 CSS 变量 + `data-theme` 切换）。

**本轮合计自测**：`header-layout 88` / `address 65` / `admin-charts 31` / `notice-chat-follow 42` 全绿；
`tsc --noEmit` 零错误、`vite build` 通过（ECharts 仍为独立 chunk，主包未因此变大）；
全量 **17 套 654 项**（口径同步 README / SELFTEST）。

### 2026-10-06（第七十四轮续 · R74-2：头像下拉菜单补「Esc / 点击外部」关闭）

> 来源：简报 §三 登记的 (b) —— 右上角头像下拉**只能用「切换到别的页面」收起**，按 Esc 关不掉、点菜单外面也关不掉，
> 而菜单固定在右上角且 `zIndex` 高于页面，**正好压住页面右上角的按钮**，用户只能靠点别的链接跳走或刷新才能弄掉它。

**改动**（`frontend/src/components/Layout.tsx`）：给下拉的**容器加 ref 并挂两条监听**（仅在菜单打开时注册，关闭即摘）——
`keydown` 的 `Escape` 关闭并把**焦点还给触发按钮**（键盘用户关掉菜单后不该丢位置）；`mousedown` 判断「点击目标是否在容器内」，
在容器外则关闭。触发按钮补 `aria-haspopup="menu"` / `aria-expanded`（既是无障碍语义，也给 e2e 一个稳定的定位钩子）。
两条约定与 `components/ui.tsx` 的 `Modal` 同姿势（**改一处别忘另一处**）：Esc 关闭、点外部关闭。

**一个必须踩对的细节**：`mousedown` 的「内部/外部」判定容器**必须把触发按钮一起包住** ——
若容器只包菜单本身，点按钮会先被判成「外部点击」把菜单关掉、紧接着 `onClick` 又把它打开，表现为「点一下没反应」。

**验证**：`scripts/e2e/header-layout-ui-check.mjs` **80 → 88 项全绿**（新增 8 条：aria 打开、Esc 关闭、aria 复位、
焦点回到按钮、点外部关闭、点内部不被误判、以及每个用例的「前置：已打开」）；`tsc --noEmit` 零错误、`vite build` 通过。

**★ 反向验证又抓到两处「断言自身」的缺陷**（这类问题不反向验证根本看不见）：
1. **假通过**：「点击外部关闭」最初点的是 `brand`（一个 `Link`）—— 而**路由变化本来就会收起菜单**（既有逻辑），
   于是停用新代码后这条断言**依然通过**。改点顶栏搜索框的放大镜（纯 `span`，不跳转、不换路由）才真正测到新逻辑。
   判据：**断言点的动作不能同时被别的机制满足**，否则测的是别人的行为。
2. **级联空转**：原用例是一条龙顺序写下来的，停用新逻辑后 Esc 那条失败、菜单仍是打开状态，
   下一个 `click` 把 toggle 又切回关闭态 → 「点外部」用例的**前置状态已被污染**，断言空转通过。
   改法：每个用例**自建前置**（回首页 → 打开 → **断言确已打开**）再验证行为，即「断言自证不级联」在**同一个用例内部**也适用。
反向验证的期望结果：停用新逻辑时**恰好红 3 条**（Esc 关闭 / aria 复位 / 点外部关闭），实测与期望一致。

### 2026-10-06（第七十四轮 · 搁置推送后转待办：后台秒杀活动「编辑 / 删除」+ 清理历史自测活动）

> 用户指令：**暂时搁置推送相关工作**（保留代码与进度，不删不重构），转做其余待办；先梳理清单再依次推进，
> 每项说明改动 / 涉及文件 / 遗留；**不得擅自实现推送逻辑**。
> 清单梳理结论：工作区《未完成项清单（2026-09-30）》22 项**已全部销项**；真实剩余项取自「简报 §三登记项 + 产品 backlog」，
> 并逐条回查代码/库现状剔除已被后续轮次顺带修好的项（例如 R66 登记的「二手闲置搜不到关键词」——
> 实测 `/api/listings/search?keyword=猫` 200 且有真实结果，ES 内 LISTING 124 条 = 69 待审 + 48 通过 + 7 驳回，已与库对齐）。
> 本轮先做优先级最高的 **R74-1**。

**背景**：后台秒杀页只有「新建 / 上下架」，**没有编辑与删除**——运营填错标题或时间只能重建，
而自测残留的活动**永远清不掉**（库里已累积 18 条，其中 14 条是 `冒烟-` / `【UI自测】` 遗留）。

**① 后端两个新端点**（`AdminSeckillController` + `SeckillService`，`PUT /api/admin/seckill/activities/{id}`、
`DELETE /api/admin/seckill/activities/{id}`，均 `OperatorGuard`）：
- **编辑只开「标题 + 时间」**（`EditActivityRequest`）：商品 / 秒杀价 / 配额**刻意不开** ——
  它们背后是**已经扣掉的余量**与**订单里的价格快照**，改了会让「活动配置」与「已卖出去的事实」对不上
  （例：配额 10 调成 5 而已卖出 8 件），且没有任何办法回溯已下单的人。要换商品请新建活动 + 下架旧的。
- **删除的硬守卫**：该活动**已产生秒杀订单则拒绝**（422，提示「订单要留档，请改为下架」）。
  不做连带删订单：秒杀订单是真实成交与资金事实（`t_order` / `t_seckill_order` 都挂着它），
  删活动等于把这条链的根拔掉 —— 订单详情查不到来源、关单回补找不到活动，属**不可逆的数据破坏**。
  仅当从未成交时允许删除，并**连带清理 `t_seckill_sku` 行**（不留孤儿行）+ 清 Redis 余量键（`clearRedisStock`）。
- **编辑的时间方向**：允许任意方向（延后 / 提前收尾是运营正常诉求）；但**已有成交时不允许把开始时间推到未来**
  —— 那会让「已卖出的活动」显示成「未开始」，语义自相矛盾。

**② 前端**（`pages/admin/Seckill.tsx` + `api/endpoints.ts` + `api/types.ts`）：操作列由「单个上下架按钮」扩为
**下架 / 上架 · 编辑 · 删除** 三键；编辑弹窗**只有 3 个输入框**（标题 + 起止时间）并在弹窗内写明「为何不能改商品与配额」；
删除弹窗**主按钮是「取消」**（危险动作不做默认项，沿用举报台的既有约定），说明文案限定「只能删从未成交的活动」。

**③ 探针（临时脚本，未入库）20/0**：覆盖 匿名 401 / 普通用户 403 / 运营 200、编辑后**落库复核**（不只看响应体）、
结束早于开始 → 400、不存在 → 404、空标题 → 400、**有订单删除 → 422 且原因含「下架」**、重复删除 → 404、
删除后活动行与 SKU 行**双清零**。

**④ 契约口径补记（此前散落，这里定钉）**：本仓 **校验类错误 = 400**（`VALIDATION_ERROR`，含 `BizException.validation`
与 `@Valid` 失败），**业务规则类 = 422**（`BUSINESS_RULE`，如「有订单不能删」）。探针初版把两者都写成 422 → 假红，
按真实契约修正后才全绿（**又一次印证：断言写错时，先怀疑断言**）。

**⑤ 双向判别（防「守卫被写成无条件拒绝」）**：同端点两向都测 —— 无订单的活动**必须真能删掉**（200 + 行消失），
有订单的**必须被拒**（422 + 行仍在）；编辑侧同理（改到未来被拒 / 按原值编辑放行 200）。

**⑥ 脏数据清理**：先 `mysqldump` 备份 `t_seckill_activity` + `t_seckill_sku` 到
`scripts/backup/r74_seckill_20261006_234337.sql`（6402 字节，36 个唯一 id = 18 活动 + 18 SKU 行，**抽验被删 id 均在备份内**），
再**走新端点**逐条删除 14 条从未成交的活动（14/14 成功，同时验证了端点与 Redis 清理路径）；
**4 条有订单的保留**并确保**已下架**（C 端 0 条可见）。结果：活动 18 → 4、秒杀 SKU 行 18 → 4、无主 SKU 行 0。

**自测**：`scripts/seckill-smoke.ps1` **26 → 45 项全绿**（新增 19 项，含「编辑已落库」与「有订单删除被拒」）；
`scripts/e2e/seckill-ui-check.mjs` **45 → 61 项全绿**（新增 16 项浏览器断言，含弹窗只有 3 个输入框、删除弹窗主按钮是取消、
被拒后行仍在、无订单可删通链路）；`docker compose build` 后**容器内 jar 与宿主逐模块 md5 一致**
（`5434d3bac36b27633b855c88b3592b95`），新端点无凭据返回 **401 而非 404**（路由已生效）。

**⑦ 全量回归抓到的第二个真缺陷（与 R74-1 无关，但必须记）**：16 套全量跑完，`product-detail-ui-check` 红 1 项
（「不得残留种子假数据 / 无『条评价』文案」）。取数定位：`t_review` **已空**但 **`t_spu 1001` 残留 `rating=5.00 / rating_count=1`** ——
R73 建起来的「评价 → 商品评分」**真实回写链**在套件收尾时没被复原：`scripts/e2e/review-ui-check.mjs` 只删了评价行，
**回写结果留在商品上**。R73 当时把同一个盲区补在了 `scripts/review-smoke.ps1`（PS 冒烟），**漏了这个浏览器套件**。
后果具有欺骗性：**隔轮假红** —— 本轮的 review 套件排在 product-detail **之后**，所以本轮才红、上一轮是绿的，
排查方向很容易被带到「代码回归」上（这正是 T-系列里「脏数据会伪装成代码回归」的又一实例；**先做数据体检**）。
**修法**：套件在造夹具**之前**记录 `t_spu` 的评分原值，收尾时删评价 + **走内部 `ratings/recompute` 把评分改回原值**
（顺带验证回写端点可用，比直接 UPDATE 更有价值），并新增 3 条断言：原值已记录 / 复原补丁被接受（`applied ≥ 1`）/
**复原后库内值等于原值**。修完现状：`review-ui-check` 21 → **24 项全绿**、`product-detail-ui-check` **恢复 20/0**、
`t_spu` 无残留行。**全量口径更新为 16 套 601 项。**

**⑧ 自查发现的「本项遗留」当场做掉**：初版守卫写的是「已有订单一律不可删」，跑完全量回归后发现**库里活动从 4 涨到 5**
—— 每跑一次测试就多一场「永远删不掉」的活动，等于把用户最初报的那个问题（清不掉）推迟两轮后原样复现。
于是把判据改精确为「**有没有未关闭的订单**」：订单全部 `CLOSED`（从未付款的终态单）的活动可删，
只要还有待付款 / 已付款 / 已发货 / 已完成就继续拒绝。删除时连带清理 `t_seckill_order` 链接行与 Redis 余量键，
**`t_order` 一律留档**（探针专门断言「删活动后 3 笔订单仍在库」）。同时让两个套件**自我清理**：
`seckill-smoke.ps1` 收尾取消自身剩余订单并把活动删掉、`seckill-ui-check.mjs` 在收尾取消后调用新端点删活动。
**顺序教训**：`seckill-ui-check.mjs` 里「删除被拒」这一段**必须排在「收尾取消订单」之前** ——
否则订单已全关闭、删除会**真的成功**，弹窗随即关闭，后续按弹窗定位的断言连片超时（第一次跑就是这样红的）。
已在代码注释里写明这个顺序依赖。清理后终态：**活动 0 场 / 秒杀 SKU 0 行 / 链接 0 行，`t_order` 里 18 笔秒杀单完整留档。**

### 2026-10-06（第七十三轮 · 五项修复：两端商品详情对齐 + 评价真数据化 + 自营订单归属 + 脏数据清理）

> 用户六截图报障五项：① 小程序缺自营/入驻标识；② 两端详情页三处不一致（介绍图 / 主图交互 / 规格参数）；
> ③ 评价体系疑似假数据 + 资格门禁；④（严重）5555 的自营订单运营商家端待发货查不到；⑤ 清理测试订单脏数据。

**① 小程序身份标识**：`miniprogram/pages/product` 标题上方补 badge 行（`supplyType === 'merchant' ? 品牌入驻 : 多多自营`），
判定规则与样式语义与 Web `SupplyTag` 一致；详情数据补 `supplyType` 透传。

**② 两端详情对齐**：Web `ProductDetail.tsx` 补「商品介绍」图片（与小程序共用 `detailImages` 池，同池同序）、
主图点击放大 Lightbox（`Modal` 组件：Esc + 关闭按钮双路径、缩略图切换、计数器）、
新增「包装规格」卡（spec 键值对 / SKU 编码 / 当前库存，与小程序 `refreshSkuInfo` 逐行同构同序）。

**③ 评价真数据化（本轮最重的链路）**：
- **根因**：`t_spu.rating`/`rating_count` 是种子假列（18 行全带 4.x 分/几千条），全仓**没有任何写入链**——
  Web 头部「4.7 / 3240 条评价」是种子数据，而评价区只有 1 行探针，「评分与列表对不上」。
- **修复链**：`ReviewService.create` → 发布 `ReviewCreated` 事件 → `ProductRatingSyncer`
  （`@TransactionalEventListener(AFTER_COMMIT)`）→ `ReviewMapper.ratingAggregatesBySpu` 聚合 →
  Feign `POST /api/products/internal/ratings/recompute`（product 域新端点，`X-Internal-Token` 保护，
  防御性校验：空/负/>5 跳过）→ 回写 `t_spu`；best-effort，不阻断评价主流程。
- **展示侧**：详情页头部评分改读评价域实时聚合 `summary`（total=0 → 如实「暂无评分」）；
  商品卡评分行 `ratingCount>0` 才渲染。
- **资格门禁**（服务端本就齐备，本轮运行时验证）：仅本人 + 仅已完成 + 商品须在单内 + 一单一商品一评（409）+ 追评一次；
  探针实测未登录 401 / 他人订单 403「只能评价自己的订单」。
- **数据修复**：`t_spu` 18 行假评分清零 + ES 全量重建同步；**探针实测 recompute 端点**（403 反向 + 签名正向 applied=1）。

**④ 自营订单不进运营待发货（严重）**：
- **根因（DB 实证）**：`t_spu.seller_id` 承载的是**咨询归属**（R48 把自营 SPU 回填成官方客服 `710000000000000001`），
  而 `purchaseViews()` 把它当**交易归属**透传给下单分组 → 订单 `seller_id=710...`；
  运营可见范围是 `{自己 userId, PLATFORM_SELLER_ID=0}` → 永远看不见。
- **修复**：`purchaseViews()` 对 `supplyType='self'` **恒返 0**（交易归属口径；`t_spu.seller_id` 不动，
  问一问 `peerId` 依赖它指向官方客服）；social 域 ASK 路由改按 `supplyType` 定官方客服（防御性双保险，结果不变）。
- **数据修复**：5555 订单 `seller_id` → 0。**运行时验证**：运营登录 `GET /api/seller/orders?status=PAID`，
  订单 `202610061003140475574` 命中（sellerId=0）；`/api/seller/summary` `pendingShipCount=1`。

**⑤ 脏数据清理**（备份先行：`scripts/backup/r73_*.sql` 三份，trade 8 表 + t_review + t_social_notice）：
- 删除：测试/压测订单 **5770**（仅保留真实用户宠友5555 一单）+ 从属 `t_order_item` 5860 / 状态日志 12453 /
  支付 1984 / 结算 702 / 库存冻结 5830 / 秒杀单 30 / 探针评价 1 / 测试用户 ORDER_* 通知 5645 + **孤儿状态日志 52**
  （订单早已被删但日志残留的历史脏数据）；删除后从属表与幸存订单严格对齐。
- 复核：5555 的「我的订单」与其 2 条订单通知完好；通知表其余类型（私信/售后/分享）**不属于订单关联，未动**。

**验证**：后端单测 228/0；`mvn clean package` + 容器 jar md5 逐模块 MATCH（product/social/fulfilment）；
小程序静态 0 错 0 警 + mp-harness 72 项；`tsc` 干净；**新增 `product-detail-ui-check.mjs` 20 项全绿**
（badge / 介绍图 / Lightbox 双关闭 / 缩略图切换 / 包装规格 / 评分区与聚合接口逐字一致 / 裂图 / console）；
**全量十六套 582 项全绿**。

**★ 本轮教训两条**：
1. **「测试脚本自毁」必须覆盖所有写入点**：review-smoke 自建夹具评价后只删 `t_review`，
   R73 新回写链会把聚合值留在 `t_spu` —— 脚本已补「夹具前记录原值 → 结尾内部 recompute 写回」，
   自毁承诺重新闭合（本次因意外执行发现：PowerShell stdout 故障时脚本**实际在跑**，输出丢失≠没执行）。
2. **归属语义拆开后要全线对齐**：「咨询归属」（`t_spu.seller_id`→官方客服）与「交易归属」（采购视图→0）
   是两个正交概念，混用一处就会造出「运营看不见自营订单」这类隐蔽缺陷；改动前先 DB 实证归属链，
   改动时保留 chat peerId 的依赖不被破坏。

### 2026-10-06（第七十二轮 · 缺陷修复：分享圈 Web 入口对匿名访客不可见）

> 用户报障：Web 端找不到「分享圈」入口。功能本体（R71）完好，是**入口可发现性缺陷**。

**排查结论**：分享圈在 Web 端**已实现**（R71：路由 `/share`、`/share/topic/:name`、`/share/mine`、
`/share/:id` 四条 + 三个页面 + 组件，套件 18/0 绿），但入口只挂在**头像下拉**里 ——
而头像下拉**只在登录后渲染**；顶栏又因 R64 截断红线刻意不放。结果：**匿名访客在整个页面
找不到任何分享圈入口**。权限/路由/功能开关均无问题 —— 是纯「登录态分支漏入口」。

**修复**（`frontend/src/components/Layout.tsx` 未登录分支）：匿名头部从单个「登录 / 注册」按钮
改为「分享圈」链接 + 登录按钮并排。信息流本身匿名可逛（公开读），放这个入口与后端权限口径一致；
仍未放顶栏 NAV（截断红线不变），只修未登录分支。顺带清理 `ShareCircle.tsx` 一个 TS6133 未用变量。

**测试**：`share-circle-ui-check.mjs` 追加 ⑦ 组 3 项断言（未登录页头含入口 / 点击到达 `/share` /
无 console 报错），18 → **21 项**；**回归 `header-layout` 80/0**（未登录头部结构变了必须回归）。
UI 口径：**15 套 560 项**。`npm run build`（tsc+vite）exit 0。



> 后续计划 P1 执行轮。交付：分享圈 Web 端（信息流/详情评论/发布/我的分享/话题聚合）；
> **连带修复一个比分享圈更严重的既存缺陷：全平台关键词搜索恒 0**。

**① 分享圈 Web 端**（后端 `/api/shares/**` 端点 R43-R56 已齐备，本轮纯前端）
| 文件 | 内容 |
|---|---|
| `pages/ShareCircle.tsx` | 信息流（推荐/关注 scope 切换）+ 发布表单（**商品必选**——分享圈的内容锚点是对某件商品的评价；话题最多 2 个）+ 热门话题榜；**追加式翻页** |
| `pages/ShareDetail.tsx` | 详情 + 商品快照卡 + 评论（一级分页，回复折叠按需拉取——请求数不随评论数线性增长）+ 转发弹窗；未登录时输入框替换为登录引导（不让用户打完字才被 401 弹回） |
| `pages/ShareMine.tsx` | 我的分享：编辑（**不能改商品**——商品是快照；topics 三态语义 null=不改/[]=清空）+ 删除（软删） |
| `components/share.tsx` | 共享卡片：转发引用块（原帖删除显示提示语）、已编辑标记、商品图走统一解析层 |
| 入口 | 头像下拉「分享圈 / 我的分享」——**刻意不进顶栏**：R64 实测顶栏 6 项是截断红线（运营账号再加一项必截断） |

**② 连带修复：全平台关键词搜索恒 0（比分享圈更严重）**
写发布表单要搜商品，一测发现 `keyword=狗粮` 返回 0 —— 深挖出**商品/闲置关键词检索在所有端都坏着**：
- 排查链：ES 索引里有 18 条 PRODUCT 文档、字段值正确（`_count?q=docType:PRODUCT`=18），
  但应用查询 **0 命中** → 连「仅过滤、无关键词」都 0 命中 → 问题在 **term 过滤器匹配不上**；
- **根因**：实际索引的 mapping 是**动态映射**生成的 —— `docType`/`status` 成了 `text`
  （代码 `ensureIndex()` 定义的是 `keyword`，但索引不是它建的/建失败后被动态映射顶替）；
  text 字段的 token 是小写 `product`，代码的 `term("docType","PRODUCT")` 精确匹配**永远落空**；
- 这同时解释了 R69 登记的「闲置检索恒 0」—— 同一个根因（我此前「索引里 124 条 LISTING、没有 SPU」的
  归因是**错的**：当时查的是 `docType:SPU`，而常量是 `PRODUCT`；reindex 无效也因为它只重灌文档、不改已存在字段类型）；
- **修复**：删除错误索引 → `POST /api/admin/search/reindex`（ensureIndex 建显式 mapping + fullSync 全量灌数）；
  修复后 `keyword=狗粮` → 3 条正确结果，**闲置检索同步恢复**（`keyword=猫` → 6 条）。
- ⚠️ 教训：**「索引里的字段类型」要用 `_mapping` 核对，不能只看文档数**；动态映射顶替显式 mapping
  是 ES 最常见的静默故障源（term 对 text 精确匹配 = 大小写敏感）。

**③ 测试**：新增 `share-circle-ui-check.mjs` 18 项（自包含，夹具自建自清）——
发布→信息流→详情评论→点赞 toggle→我的分享编辑/删除→话题聚合路由→无报错。
UI 口径：**15 套 557 项**；home 9/0；`npm run build` exit 0。

### 2026-10-06（第七十轮 · 模型切换 qwen3.8-flash + 多模型自动故障切换）

> 用户要求：① 把模型切到有免费额度的 `qwen3.8-flash`；② 说明额度用尽时能否自动切换备选模型。

**① 切换（配置动作 + 实测验证）**
`.env` 的 `DUODUO_AI_MODEL` 从 `qwen-max` 改为 `qwen3.8-flash` → `docker compose up -d duoduo-ai-app`
（compose 把变量**注入容器环境**，env 变了才会重建容器，这就是生效方式）。
切换前先用容器内 Key 逐个实测四个模型：`qwen3.8-flash` ✓ / `qwen3.7-flash-2026-07-15` ✓ /
`kimi-k3` ✓（200） / `qwen-max` ✗ 403 FreeTierOnly。
切换后 `/api/duoduo/chat` 实测 **`mode=llm`**（真·模型生成，不再是 R69 的 fallback）。

**② 多模型自动切换（`DUODUO_AI_MODEL` 支持逗号分隔的优先级列表）**
原实现是单模型：额度用尽只能靠人改配置重启。现改为**列表化故障切换**，规则如下：
- **优先顺序 = 书写顺序**（左 → 右）；
- **触发条件**：403（额度用尽/无权限）、404（模型不存在）、429（限流）、5xx、超时、回复为空
  —— 换模型可能好转的都切；
- **不切换**：401（Key 本身无效）—— 换模型救不了凭据，立即按凭据问题降级，不浪费时间逐个试；
- **粘性记忆**：成功过的模型被记住，下一次请求直接从它开始（避免每次先撞一遍坏模型）；
  主力恢复后自然回切；
- 全部不可用 → 仍 DEGRADED，由 ChatService 降级为召回拼装（R69）。

**验证（真实场景反向验证）**：把主力故意配成不存在的模型 `nonexistent-model-xyz` →
请求自动落到 `qwen3.8-flash`，日志完整记录「模型 404 model_not_found → LLM 已切换 → 调用成功」，
HTTP 200、`mode=llm`。**当前生效配置**：
`DUODUO_AI_MODEL=qwen3.8-flash,qwen3.7-flash-2026-07-15,kimi-k3`（主力 + 两个备选，均来自免费额度）。

改动：`duoduo-ai/.../DashScopeClient.java`（模型列表化 + 故障切换 + 粘性记忆）、
`.env.example`（列表形式说明）、本地 `.env`（未提交，gitignore）。

### 2026-10-05（第六十九轮 · AI 导购 503：DashScope 免费额度用尽 + 降级策略）

> 两端（Web / 小程序）AI 导购均报「AI 服务暂时不可用，请稍后再试」。

**排查链**：AI 容器 healthy、配置为**真实模式**（`DASHSCOPE_API_KEY` 已配、`DUODUO_AI_MOCK_ENABLED=false`、
模型 `qwen-max`）→ AI 服务日志 `LLM 调用失败 status=403` → 容器内直连 DashScope 复现 403 →
**拿到错误体**：`AllocationQuota.FreeTierOnly`「Free quota exhausted」——
**该 API Key 的免费额度已用尽，且账号处于「仅用免费额度」模式**。

**定性：外部凭据额度问题，不是代码缺陷。** 原代码在非 2xx 时只记 `status=403`、
抛通用的「AI 服务暂时不可用」—— 排障时看不到原因，只能手工复现；且「请稍后再试」是误导
（额度用尽不会自己恢复）。恢复**真·AI 生成**的唯一办法是账号侧动作：
**到阿里云百炼控制台充值，或关闭「仅用免费额度」模式**（外部凭据属用户资产，不代操作）。

**代码侧两项修复**：
1. `DashScopeClient`：非 2xx 时**记录上游错误体**（截 300 字符）—— 状态码只有 401/403/429 一个数字，
   「额度用尽」与「key 无效」的排障方向完全不同；只记状态码等于让下次排障再手工复现一遍。
2. `ChatService`：LLM 不可用（DEGRADED）时**降级为召回拼装回复**而不是整段 503 ——
   推荐仍来自**真实商品库**（与 mock 共享召回链路），mode 标为 `fallback`、
   回复开头如实说明「AI 生成暂时不可用」。只接 DEGRADED，其他业务异常照常抛出。

**验证**：`POST /api/duoduo/chat` 由 503 → **200**，`mode=fallback`，
回复带真实商品推荐（麦富迪 成犬牛肉味狗粮 5kg ¥189 / 皇家 小型犬幼犬粮 3kg ¥229）；
Web 端 `/ai` 页实测：不再出现「AI 服务暂时不可用」，推荐卡正常渲染；日志可见上游错误体。
**额度恢复后无需改代码**：LLM 调用成功即自动回到 `mode=llm`（fallback 只在 DEGRADED 时触发）。

改动：`duoduo-ai/.../DashScopeClient.java`（错误体入日志）、`duoduo-ai/.../ChatService.java`（降级分支）。

### 2026-10-05（第六十八轮 · 分类「└」残留符号 + 商品图四角黑边：两端一致性修复）

> 用户报障两点：① 分类筛选里出现「└」疑似乱码；② 商品图与容器不贴合、四周有黑边（要求两端一并修）。

**① 「└」的来源：前端写死的树形前缀，不是乱码、更不在数据里。**
数据库 `t_category.name` 逐条核对为干净值（宠物主粮 / 猫主粮 / …）；
`Search.tsx:145` 写死了 ``{cat.depth > 0 ? `└ ${cat.name}` : cat.name}`` ——
用「└」在**平铺的筛选行**里表达父子层级。用户视角这就是乱码，且筛选行本就按「父在前、子随后」排序，前缀没有信息增量。
**修**：去掉前缀，只显示 `cat.name`。

**② 图片黑边的来源：图源文件本身 defective，不是 CSS。**
R67 从小程序拷来的 12 张 JPG，实测 600×600、**四角纯黑 (0,0,0)**、黑区沿边侵入 56px ——
原图是「圆角卡片贴在黑底上导出」的产物（多半由带透明通道的源图转 JPG 时透明被填黑）。
两端共用同一份文件，所以小程序同样有黑角；CSS 的 `object-fit` 修不了「内容里自带的黑」。
**修**：逐行扫描找黑区（亮度<40），用**行内最近有效色横向插值**填充（背景是平滑渐变，插值不留痕），
每张约 3604 像素；**修完同步回小程序**（`miniprogram/images/products/`），
md5 比对两端 **12/12 逐字节一致** —— 图源修一次，两端同时干净。

**验证**：`/search` 页「└」已消失、chip 文本为纯分类名、裂图 0；home 套件 9/0（含零裂图断言）。

**改动位置**：`frontend/src/pages/Search.tsx`（去前缀，1 行）；
`frontend/public/img/products/p01..p12.jpg` 与 `miniprogram/images/products/p01..p12.jpg`（图源修复，两端同步）。

### 2026-10-05（第六十六轮 · 图表体系重建 + A 方案）

> 用户四项要求：① 悬停即时（原 2–3 秒）；② 换主流图表组件；③ 状态分布扩成多维度；
> ④ 平台后台补分布图/折线图/柱状图/饼图。另要求一并实现 **A 方案**（Web「我的闲置」）。

**① 悬停 2–3 秒的根因：用错了机制。** 原实现用 HTML 原生 `title` ——
其显示延迟**由操作系统决定（Windows 约 1–2 秒）且无法用 CSS/JS 配置**。
换 ECharts 自绘 tooltip 后实测 **6–26ms**。

**② 组件选型：ECharts 6，按需引入 + 懒加载**

| 文件 | 变更 |
|---|---|
| `frontend/src/components/charts.tsx`（新增） | ECharts 封装：只 `use` Bar/Line/Pie + Grid/Legend/Tooltip + CanvasRenderer；`animation:false`；tooltip `transitionDuration:0`；四类图共用一个 `Chart`（`type` 切换）；空数据显式出「暂无数据」；容器带 `role="img"` + 可读摘要（canvas 对读屏是黑箱） |
| `frontend/src/components/lazy-charts.tsx`（新增） | `React.lazy` 边界：**ECharts 不进买家端首屏包**。构建核对：主包 237.07 kB（+16 kB），`charts-*.js` 558.14 kB 独立成 chunk |
| `frontend/src/components/ui.tsx` | **删除**手写 `MiniBars`（连同 `.bars/.bar-*` 样式），保留历史教训注释 |

**③ 多维度：1 → 5**（`GET /api/seller/analytics`）
订单状态分布 / 近 14 天成交趋势（折线，**补零**）/ 商品销量 Top 8 / 资金构成（饼）/ 订单类型占比（饼）。

**④ 平台后台补图**（`GET /api/admin/reports/charts`）
折线 / 饼 / 柱状 / 分布，四类齐全；**图表不参与页面 loading 门**（报表卡比图重要）。

**后端（duoduo-trade，全部 SQL 聚合、零 DDL、不改现有端点契约）**

| 文件 | 变更 |
|---|---|
| `dto/AnalyticsDtos.java`（新增） | `Slice(name,value)` / `TrendPoint(date,orderCount,gmv)` / `SellerAnalytics` / `AdminCharts`；`TREND_DAYS=14`、`TOP_PRODUCT_LIMIT=8` |
| `support/TrendSeries.java`（新增） | 趋势**补零**口径集中一处（两份实现迟早只改一处，然后两个页面横轴不一致） |
| `mapper/OrderMapper.java` | 新增 `dailyTrend` / `topProductsBySales` / `countByOrderType`；`sellerIds` 传 `null` 即全平台，刻意不写两份 SQL |
| `service/SellerService.java` | 新增 `analytics()`：五维度，全部走 `visibleSellerIds()` 与明细同范围 |
| `service/AdminOpsService.java` | 新增 `charts()`：四维度（`OperatorGuard` 把关） |
| 两个 Controller | 各加一个 GET 端点 |

**★ 坑一：`long value` 被序列化成字符串**（`{"value":"144"}`）→ ECharts 当类目名、直接画不出图。
根因：`LongToStringJacksonModule` **默认把所有 Long 转字符串**（保护雪花 id），只有白名单字段保持数字。
按该模块自己写明的规矩把 `value` 加进白名单，并注明它是泛用名。
⚠️ 改的是**共用模块** → 按纪律重建全部依赖方；实测 `duoduo-gateway` 镜像仍是 4 天前，
但它只依赖 `duoduo-common`、jar 内 common-web 条目数 **0** ⇒ Maven 跳过它是**正确行为**；
其余 7 服务逐模块比对宿主机与容器内 `/app/app.jar` **8/8 一致**。

**★ 坑二：`mvn package` 不带 `clean` 会跳过没有源码改动的模块** → jar 不链接新的 `common-web`
（台账里记过的「增量检测认为无需重打」，又踩一次）。

**★ 顺手修掉一个既存缺陷：Web《二手闲置》页对所有人 401**
后端 R52 把公开检索从裸 `/api/listings` 挪到 `/api/listings/search`（单段路径要让给
`POST /api/listings`，白名单**不区分方法**），**前端漏改**，页面一直报「身份未认证或登录已过期」；
而十四套 e2e 此前**没有一套访问过 `/listings`**。已修 + 补断言 + 反向验证。

**A 方案：Web 端「我的闲置」**（`/listings/mine`、`/listings/new`、`/listings/:id/edit`）
**不套 `RequireSeller`**：发布与管理闲置只要求「登录 + 实名」，与「卖家身份」是两条线。
筛选口径与小程序严格一致（审核两标签只带 `auditStatus`；交易三标签必须同带 `auditStatus=APPROVED`）。
顺带给共享请求层加 **multipart 支持**，让图片上传也走同一条请求路径（401 单飞刷新在里面）。

**新增自测 3 套 106 项**：`seller-dashboard` 31 / `admin-charts` 28 / `my-listings` 47。
UI 自测口径：十二套 454 项 → **十四套 539 项**；`npm run build` exit 0；
回归 8 套 334 项全绿。

**登记未修**：C2C 闲置**关键词检索恒为 0**（ES 索引里 124 条 LISTING 全是历史遗留、
新建条目未同步，且「0 命中」不是异常 ⇒ 不降级）；修法属独立工作。

### 2026-10-05（第六十五轮 · 商家端「订单状态分布」柱状图全平：百分比高度解析失败）

> 用户报障「柱状图全部显示为平的、数值全为零」。
> **定性：数据是好的，塌掉的是渲染** —— 接口 200、六个字段齐全且和为 801，
> 连 `style.height` 的百分比字符串都算对了，唯独实际渲染高度全是 3px。

**根因：CSS 的循环依赖。** 柱高用百分比，而百分比高度只有在**父元素高度确定**时才能解析。
原实现把柱子直接放在 `.bar-col`（`height: auto`）里 —— 父高由内容决定、内容高又依赖父高，
浏览器按规范把百分比**退化成 `auto`**，于是无论数值多大都塌成 `min-height: 3px`。
实测 `.bar-col` 的计算高度只有 25px（只剩标签的高度）。

| 状态 | 接口值 | `style.height` | 实际渲染 |
|---|---|---|---|
| 待发货 | 144 | 52.75% | **3px** |
| 在途 | 20 | 7.33% | **3px** |
| 已完成 | 260 | 95.24% | **3px** |
| 已退款 | 104 | 38.10% | **3px** |
| 已关闭 | 273 | 100% | **3px** |

**改动（2 个前端文件）**：`ui.tsx` 的 `MiniBars` 给柱子外包一层 `.bar-track`；
`styles.css` 给 `.bar-col` 加 `height:100%` 并新增 `.bar-track`。
修复后实测 47.5 / 6.6 / 85.7 / 34.3 / 90 px（轨道 90px），**与数值严格成正比**（最差偏差 0.1%）。

**新增自测** `scripts/e2e/seller-dashboard-ui-check.mjs`（**21 项 / 0 失败**，自包含）：
钉的是不变式而不是当前数值 —— 柱高比例 == 数值比例（偏差 < 10%）／最高柱 ≥ 轨道 80%／
0 值保持最小高度（不能把 0 画成有高度）／六状态之和 == 页面「共 N 笔订单」／
权限边界：普通用户进 `/seller` 看到入驻引导、顶栏无「商家端」、`/api/seller/summary` **403**。

**★ 反向验证**：去掉 `.bar-col` 的 `height:100%` → 精确报红 2 条（3px vs 轨道 112px、偏差 278%），还原后 21/0。

**顺手更正会误导人的注释**：`Listings.tsx` 顶部原写「本期不支持发布闲置（**没有对应后端接口**）」，
与事实不符 —— 后端 R52 已建齐六个闲置端点且只要求「登录 + 实名」、**不要求 SELLER 角色**，
缺口在 Web 页面没接（小程序已有 flea-publish / flea-mine 四页）。已改为准确表述。

**回归**：`npm run build` exit 0。UI 自测口径：十一套 433 项 → **十二套 454 项**。

**本轮同时理清的一处「设计 vs 缺陷」判断**（未改代码，结论供后续决策）：
「普通用户看不到商家端入口」是**刻意的**（角色门禁 SELLER/OPERATOR/ADMIN，
且普通用户可经「实名 + 申请成为卖家」自助升级）；真正的缺口是
**C2C 闲置卖家在 Web 端没有任何管理入口** —— 那不是权限问题，而是 Web 页面没接
（后端就绪、小程序已有），且 C2C **根本没有成交链路**（无 listing 订单表，担保交易 B-11 暂缓），
所以把自营商家端开放给普通用户只会得到一个「0 笔订单的空壳」。

### 2026-10-05（第六十四轮 · 顶栏标签被截断 / 昵称折行：收紧信息架构 + 补响应式收口）

> 用户报障原话：「顶栏上有很多标签未能完整显示，部分标签被截断或缺失」。
> 本轮先**量化**再动手，改完再做**两次反向验证**（分别还原信息架构与样式），
> 结论是**两半修复都必要**。

**实测（每项标签 `getBoundingClientRect()` 与 `.nav-links` 可视框逐项比对，1920px 视口）**

| 身份 | 顶栏应显示 | 实际完整可见 | 被挤掉的标签 |
|---|---|---|---|
| 未登录访客 | 9 项 | 7 项 | 收货地址 · 我的订单 |
| 普通买家 | 9 项 | 8 项 | 我的订单 |
| 运营（+ 商家端 + 管理后台） | 11 项 | 8 项 | 我的订单 · 商家端 · 管理后台 |

**四条根因**

1. **`--max-w: 1200px` 封住顶栏**（`styles.css:31`）→ 一行可用约 1160px，内容需约 1408px。
   **拉宽窗口毫无改善**：1920px 与 1264px 的实测数字完全相同。
2. **`.nav-links` 是唯一的收缩承担者**：`overflow-x:auto` 使 flex 自动最小尺寸变为 0
   → 独自让出 218px（812 → 594）。
3. **滚动条被显式隐藏**（`scrollbar-width:none` + `::-webkit-scrollbar{display:none}`）且无溢出提示。
   ⚠️ **定性要准**：这些链接仍在 DOM 里、接口 200、跳转正常，只是被推出可视区 ——
   属**布局 / 可发现性缺陷**，不是「标签没渲染」。这也是接口层测不出来的原因。
4. **`.header-right` 可收缩且 `.icon-btn` 无 `white-space:nowrap`** → 昵称折两行
   （实测昵称高 32px）撑出固定 38px 的药丸 —— 与小程序「按钮文字禁止折行」同一类。

**改动（2 个前端文件；前端路由与后端零改动）**

| 文件 | 变更 |
|---|---|
| `frontend/src/components/Layout.tsx` | `NAV` 收成 6 项「逛 / 买」一级动线；头像下拉补「收货地址」、两个工作台入口移到个人事务之后；下拉加 `user-menu` 类名；品牌文字加 `brand-text` 类名 |
| `frontend/src/styles.css` | `.brand`/`.header-right` 加 `flex:none`；`.nav-links` 加 `flex:1 1 auto; min-width:0`；`.icon-btn` 加 `white-space:nowrap` + `flex:none` + `.small{max-width:88px;…ellipsis}`；`.search-inline` 改 `clamp(150px,18vw,224px)`；`.app-header-inner` gap 20→16；新增断点 **≤1200px 收起搜索框、≤1024px 收起品牌文字** |

**信息架构口径**（用户拍板）：顶栏 = 「逛 / 买」一级动线；个人事务与工作台入口收进头像下拉。
**「收货地址」原本不在下拉里 → 本轮补进**（收起来 ≠ 删掉，并加断言钉住仍可达）。

**新增自测** `scripts/e2e/header-layout-ui-check.mjs`（**80 项 / 0 失败**，自包含）：
4 个角色（未登录 / 买家 / 运营 / **超长昵称运营**）× 6 档宽度（1920 · 1440 · 1280 · 1264 · 1152 · 1024）
逐格断言「顶栏一个标签都不许被截断 + 昵称不许折行 + 应有项一个不少」，
外加信息架构可达性与工作台入口不回归。

**★ 反向验证（两次）**

- **还原 `NAV`**（三项加回顶栏）→ 断言精确报红，并如实列出每个角色各自丢了哪几项。
- **还原 `styles.css` 到改动前**（NAV 保持已收窄）→ **仍然报红**：
  「需要 560px / 可用 559px」**只差 1px**，且昵称高 32px 撑出按钮。
  → **只改信息架构不够、只改样式也不够，两半都必须改。**

**回归**：`npm run build` exit 0；`home` 7/0、`address` **64/0**（该项断言 1:1 更新为
「入口在头像下拉且顶栏不再重复」，**性质不变、强度未降、项数未漂移**）、`seckill` 45/0、
`admin-entry` 32/0。UI 自测口径：十套 353 项 → **十一套 433 项**。

**本轮新踩的两条**（已入 `docs/SELFTEST.md` 排障表）

- **同名标签两处出现必须限定断言作用域**：运营账号顶栏与下拉里都有「商家端」，
  在 `.app-header` 里查会数到 2 → 断言假红。已给下拉加 `user-menu` 并在断言里限定。
- **下拉菜单没有 Esc 关闭行为**（`Layout` 只在路由变化时 `setMenuOpen(false)`）→
  浮层盖住页面右上角的「+ 新增地址」按钮，Playwright 报 `…intercepts pointer events`，
  表象像「按钮点不动」，真因是浮层遮挡。测试改为「再点一次头像收起」。
  ⚠️ **未修**：给菜单补 Esc 关闭是独立的小改进项。

### 2026-10-05（第六十三轮 · 平台管理后台新增常驻入口「进入管理后台」）

> 用户报障原话：「目前只有登录后自动跳转才能进入后台、关闭页面后无法再次进入」，
> 要求加一个**稳定、可随时返回**的入口。
> 排查结论：顶栏那条入口**本来就存在且工作正常**（`Layout.tsx` 按 `isOperator()` 条件插入，
> 用户提供的截图②可见）；「无法再次进入」的实际成因是**入口只有一条** ——
> 窄屏被折叠进「…」、或用户只是没找到。故本轮不是修 bug，而是**把入口做厚**。

**用户拍板的四条口径**（一次问清，避免返工）：未登录不可见 / 普通用户不可见 /
有权限的登录用户可见 / 位置为「顶栏 + 头像下拉都加」。

| 文件 | 变更 |
|---|---|
| `frontend/src/components/Layout.tsx` | **唯一改动文件**。头像下拉菜单新增一条「进入管理后台」→ `/admin`（置于「商家端」之后，两个工作台入口相邻）；顶栏那条保持原样。两条**共用同一个 `isOperator()`**。文件头注释补上「为什么做成两条」 |
| `scripts/e2e/admin-entry-ui-check.mjs`（新增） | 真实 Chromium **32 项**，四组场景 S1–S4（见下） |

**前端路由与后端：零改动。** `/admin` → `AdminDashboardPage`（运营概览）早已注册于
`App.tsx:303-310`，外层 `RequireOperator` 守卫齐全；不需要新路由、不需要动网关。

**★ 顺手挡掉一个隐性陷阱：`store/auth.tsx` 的 `isAdmin` 永远为 false**

```ts
isAdmin: user?.role === 'admin' || user?.role === 'operator',   // 全小写
```

后端下发的是大写 `OPERATOR` / `ADMIN`（`t_user.role` 存储亦为大写）。
若本轮入口图省事用 `isAdmin`，表现会是「代码看着没错、入口就是不出现、控制台也不报错」——
属于本项目最警惕的「有字段、有出参、没写入」同类缺陷。
改动处已就地写明「必须用 `isOperator()`」。该字段**当前没有任何消费方**，
故按「不动无关逻辑」原则**本轮不改它**，仅登记：应删除，或改为 `isOperator()` 的别名。

**「登录后自动跳转后台」这条既有行为的真相**：它并非硬编码跳转，而是 `?next=` 回跳链路
（`Login.tsx:32` 读 `next`、`:63` 登录后 `navigate(next, { replace: true })`）——
用户之所以「登录后自动进后台」，是因为他先访问了 `/admin`，被守卫带去
`/login?next=/admin`。新入口是普通 `<Link to="/admin">`，两者走的是不同触发器，天然不冲突；
S4 专门把这条链路钉住。

**e2e 四组场景（32 项）**

| 组 | 覆盖 |
|---|---|
| S1 | 未登录：顶栏无入口、无头像下拉；访问 `/admin` 落到 `/login` 且 **`next` 精确等于 `/admin`** |
| S2 | 普通买家登录：顶栏与下拉**都不**显示入口（并断言下拉本体确实展开了，避免空断言）；`/admin` 渲染 `AdminDenied` 而非跳登录 |
| S3 | 运营登录：两条入口都能落到「运营概览」；**在后台页刷新后入口仍在**；**同 context 新开标签页（关掉页面再打开）入口仍在** |
| S3b | **只带 `dd_rt` 的全新上下文**（模拟关掉浏览器再打开，`dd_at` 15 分钟短时效已失效）→ 靠 401 触发单飞刷新后入口仍在，且点进去真能打开运营概览 |
| S4 | 未登录直奔 `/admin` → 登录 → **自动回到 `/admin`**，且回跳后顶栏入口同时还在（两条路径不冲突） |

**★ 反向验证**：把下拉入口的判定改成恒假 `false`（**等价于误用那个永远为 false 的 `isAdmin`**）→
断言精确报红 **2 条**，且**只红这 2 条**（其余 30 条仍绿）。首版曾有 3 条红，
第 3 条是「刷新后仍在」耦合了上一步的点击结果 —— 已改为**先显式 `goto('/admin')` 再 reload**，
把级联假红消掉（否则一个缺陷看起来像两个，归因会被引偏）。还原后 32/0。

**★ 本轮踩的坑：把「没报错」断言写成了假阳性**

首版每条场景都加了「无 console 报错」，结果 **5 条假红**，内容全是
`Failed to load resource: ... 401`。401 是**设计内状态**而非缺陷：
未登录时 `/api/users/me`、`/api/cart/count` 必然 401（页面各自兜底，见 `Layout.tsx` 顶部注释）；
S3b 更是**靠一次 401 触发单飞刷新再重放**。断言口径已对齐
`seckill-ui-check.mjs` 的 `!/favicon|status of 401/i` 过滤 ——
**守卫报警先确认守卫本身对不对**，否则噪声一起，真正的 `pageerror` 也会被忽略。

**回归**：`npm run build`（`tsc --noEmit && vite build`）exit 0；
`run-ui-checks.ps1 -Only home` **7/0 PASS**；`seckill-ui-check.mjs` **45/0**
（改的是全局 Layout，必须验后台页与 C 端仍正常）。
UI 自测口径由「九套 321 项」更新为 **十套 353 项**。

### 2026-10-05（第六十二轮 · 两个用户报障的最小修复：下单「应付 ¥0」+ 领券中心不刷新/无定时上架）

> 用户报障原话：① 下单时「应付金额」显示为 0；② 领券中心长期只有固定的两张券，不会刷新，
> 也没有活动券自动上架。要求**最小化修改**，保持现有风格与目录结构，不顺带重构。
> 结论：是**三个独立缺陷**（1 个前端金额写入、2 个券窗口/刷新），非资金故障 —— 下单金额一律由服务端算。

| 文件 | 变更 |
|---|---|
| `miniprogram/pages/checkout/checkout.js` | `payText` 从「只有 `applyCoupon()` 会写」收成**唯一写入口** `syncAmounts()`；`recalc()` 先本地刷一遍三行金额再等服务端；`refreshCoupons()` 补上最常走的 `else`（无推荐券）分支；`.catch(() => {})` 改为 `.catch(() => this.applyCoupon(null))` |
| `duoduo-marketing/.../mapper/CouponTemplateMapper.java` | `selectClaimable` 补 `start_at <= #{now}` —— 领取窗口**两头都要判**，原来只有 `end_at >= now` |
| `duoduo-marketing/.../service/CouponService.java` | 补 javadoc：窗口两头过滤即「到点自动上架」，不需要定时任务 |
| `miniprogram/pages/coupons/coupons.js` | 补 `onShow`（30 秒节流）+ `onPullDownRefresh`；节流时间戳写在**发起请求时**（写在响应回来后会让 onLoad/onShow 并发打两次） |
| `miniprogram/pages/coupons/coupons.json` | 开 `enablePullDownRefresh` |
| `scripts/mp-harness/behavior.test.js` | 行为断言 21 → **31 项**：新增「确认订单金额三行自洽（无券/有券两边界）」6 项 + 「领券中心取数时机（上界+下界）」4 项 |
| `scripts/coupon-window-smoke.py`（新增） | 券窗口不变式探针 6 项：**列表可见 ⇒ 当下必可领** |
| `scripts/coupon-smoke.ps1` | TTL 夹具消除 60 秒定时调度竞态（重做一次 + 判据改取**终态**），消掉一类 ~0.5%/次的假阳性 |

**三个缺陷的根因**

1. **「应付 ¥0」是展示 bug，不是算钱 bug。** `payText` 只有一个写入口 `applyCoupon()`，
   而 `refreshCoupons()` 的三个分支是 `if (_couponDeclined) / else if (bestCouponCode) / else if (chosenCoupon)` ——
   **「没选券 + 服务端也没推荐券」这条最常见的分支没有任何人调用 `applyCoupon`**，
   `payText` 就永远停在 `data` 初值 `'0'`。WXML 里「应付」出现两处（明细行 + 提交栏），都读同一个 `payText`。
   实测新账号 `GET /api/coupons/mine/usable?amount=18900` → `{"coupons":[],"bestCouponCode":null}`，
   走的正是这条分支。`.catch(() => {})` 吞掉 401/超时后同样不刷新，是第二条复现路径。
2. **活动券定时上架失效在「读取」那一半。** 运营侧 `POST /api/coupons/admin/templates` 一直支持传 `startAt`/`endAt`，
   但列表 SQL 只判 `end_at >= now`，于是未到开抢时间的券**提前出现**在领券中心；
   而领取侧 `claimOne` 是 `start_at <= now AND end_at >= now` —— 看得见、点下去 422「尚未开始领取」。
   补上 `start_at <= now` 后列表与领取同一套窗口条件，「到点自动上架」是这条 SQL 的自然结果，不需要定时任务。
3. **领券中心「不刷新」是只有 `onLoad` 取数**：`onLoad` 一个页面实例只跑一次，去登录/去详情再回来看到的还是旧数据。

**★ 反例优先：先证明新断言能抓住旧缺陷，再宣称修好**

- 缺陷 1：`git stash` 掉 `checkout.js` 后新断言**精确变红**（`payText="0"`、`0 !== 59`），
  且「有推荐券」那条边界仍 PASS → 证明缺陷只发生在「无券」分支，不是断言写宽了。
- 缺陷 3：把节流时间戳改回「响应后写」→ 首次进入**实测发 2 次**请求（28 项 / 失败 3 项），恢复后 31/0。
- 断言风格：金额一律按**数值**比较（`Number(payText) === amountFen/100`），
  不比对 `'189'` / `'189.00'` 字符串形态 —— 把 `fen2yuan` 的显示格式抄进测试只会带来假红。

**★ 把「不变式」写进探针，而不是写死条数**

`scripts/coupon-window-smoke.py` 钉的是「**列表里出现的每一条券，当下都必须能领成功**」。
这条比「列表里有几张券」值钱得多：列表与领取是两段独立 SQL，时间条件只要有一点点不一致，
就会出现「看得见、点下去领不到」，而接口日志里每条都是合法的 4xx，最难归因。

**验证（本轮实测）**

- 后端单测：`mvn -o -pl duoduo-marketing -am test` **17/0**；`package` 后与容器内 `/app/app.jar` **md5 一致**。
- 券窗口真值表（运行时，插夹具券 5003 逐场景验，验完删除）：
  未来开抢→**不出现**｜窗口内→出现｜已结束→不出现｜停用→不出现；
  且旧谓词 `status='ACTIVE' AND end_at>=now` 会把它列出来，而直接领它是 **422「尚未开始领取」** —— 正是「看得见领不到」。
- `scripts/coupon-smoke.ps1` **145/0**（`[GUARD-OK] 145 项全部执行，无静默跳过`）。
- 小程序行为断言 **31/0**；静态自检 **0 错 0 警**；券窗口探针 **6/0**。
- 全量 23 套冒烟本轮的 10 套红**全部归因于环境**，与本次改动无关，逐条已定位（见下）。

**⚠️ 本轮环境侧发现（不属代码缺陷，但会让冒烟假红，需先修环境再看结果）**

| 症状 | 根因（实测证据） |
|---|---|
| 直连 `127.0.0.1:8081/8082` 一律 404，连累 address / credit / id-precision / review / wechat-auth 五套 | **`WorkBuddy.exe`（PID 18676）占用了 `127.0.0.1:8081` 与 `:8082`**，比 docker-proxy 的 `0.0.0.0:8081/8082` 更具体，抢走了 loopback 流量。`netstat -ano` + `tasklist` 可证；换局域网 IP `192.168.0.103:8081/8082` 立即 200 |
| `search-smoke` 被跳过、ai 套件 12 项红 | ES/宿主端口问题 + **DashScope 返回 403**（AI 服务日志 `LLM 调用失败 status=403` 共 12 条，与 12 项失败一一对应）。AI 服务按设计降级为 `DEGRADED`，是**凭据/额度**问题，不是代码问题 |
| order / payment 两套共 18 项红 | 上游 `mq-smoke` 死信用例的**脏数据**（其自证已报 `2001 冻结量 0 -> 2`）：库存与冻结量断言全部对不上 |
| `mq-smoke` 6 项红（死信查不到、replay 401） | 会话被挂起约 90 分钟，期间商品域一直被停着，恢复时**运营 Cookie 会话已过期**（replay 返回 401） |

### 2026-10-01（第六十一轮 · 剩余任务全量收口 + 完整自测手册）

> 用户指令：先梳理整体进度、列出**所有**未完成项并逐项说明剩余工作 → **全部完成** → 给出
> 一份完整自测流程（环境/数据准备 · 功能点清单 · 逐项步骤与预期 · 排障建议）。
> 盘点出的 5 项（#206~#210）已全部完成，详见下表。

| 文件 | 变更 |
|---|---|
| `duoduo-trade/.../dto/SeckillDtos.java` | `ActivityView` 增加 `enabled` —— 没有它前端无法区分「从未上架」与「已下架」，下架就成了不可逆操作 |
| `duoduo-trade/.../service/SeckillService.java` | 新增 `listActivitiesForAdmin()`：**不过滤 enabled**，运营列表才能看见已下架的活动并重新上架 |
| `duoduo-trade/.../controller/AdminSeckillController.java` | `list()` 改走 `listActivitiesForAdmin()` |
| `frontend/src/pages/admin/Seckill.tsx`（新增）、`shared.tsx`、`App.tsx`、`api/endpoints.ts`、`api/types.ts` | 后台第 9 个页签「秒杀活动」：列表（含已下架）/ 新建（标题·起止时间·参与 SKU 与秒杀价）/ 上架·下架开关 |
| `scripts/e2e/seckill-ui-check.mjs`（新增） | 秒杀真实浏览器 e2e **45 项**：新建 / **下架后仍列得出来并能重新上架** / 前台不可见 / 抢购→下单→库存递减 / 限购拦截 / 幂等重放 / 取消回补 |
| `scripts/mp-harness/runtime.js` | 新增 `stub()` 与 `matchStub()`（请求级打桩），供行为断言驱动真实页面 js |
| `scripts/mp-harness/behavior.test.js`（新增） | 小程序页面行为 **21 项**：长 id 按字符串上行 / 加购 PATCH 的 skuId 在 URL / 下单带 `idempotentKey` / 跳订单页用 `navigateTo` 而非 `switchTab` |
| `duoduo-trade/.../service/CartService.java`、`mapper/CartItemMapper.java` | **并发加购三个真缺陷修复**（详见下） |
| `duoduo-trade/src/test/.../CartDuplicateKeyTest.java`（新增） | 6 项：唯一键冲突识别不能只认 `DuplicateKeyException`，且不能误吞别的表/别的错误 |
| `scripts/perf/bench.py` | 补齐**真实写链路**（加购 / 下单 / 支付发起）+ `Pacer` 限速 + 加购数量守恒对账 + **三重自还原** |
| `scripts/perf/cleanup-leftovers.py`（新增） | 测试环境清场工具：按手机号取消遗留待付款订单（走业务接口，不改库） |
| `docs/SELFTEST.md`（新增） | **完整自测手册**：环境/数据准备 → 功能点清单 → 逐项步骤与预期 → 23 条排障表 → 交付门禁 |
| `README.md`、`docs/PLAN.md` | 按约定同步：项数口径（接口 1551 / 浏览器 321 / 小程序无浏览器 62）、新增脚本条目、自测手册入口 |

**★ 本轮靠压测抓出的三个真缺陷（并发加购）**

| # | 症状 | 根因 | 修法 |
|---|---|---|---|
| 1 | 120 次并发加购 9 次 500 | 只 catch `DuplicateKeyException`，MyBatis 还会抛别的包装类型 | `isCartUniqueConflict()` 遍历 cause 链判 `Duplicate entry` + `uk_cart_user_sku` |
| 2 | 改成「先查后写」后出现**死锁** | 失败的 INSERT 留下唯一索引锁，后续事务互相等 | 改成单条 `INSERT ... ON DUPLICATE KEY UPDATE quantity = quantity + VALUES(quantity)` |
| 3 | 200 次**全部返回 200**，购物车却只多了 46 件 | `@Transactional` + MySQL RR：一致读快照看不到他事务已提交的插入，回查恒为 null；既有的读-改-写分支并发下**丢更新** | 删掉 `mergeInto`，永远走数据库侧原子 upsert |

第 3 条最危险：**错误率 / P99 / QPS 三项全绿，只有「数量守恒对账」看得见**。
对账已固化进 `bench.py`（成功次数 == 购物车增量），修完实测 200 == 200。

**★ 本轮最重要的教训：脏数据会伪装成代码回归**

全量冒烟一度 20/23，三套红在「库里不应残留 FROZEN 冻结行」。查下来是压测遗留的
**754 笔待付款订单 + 752 条 FROZEN**（下单冻库存，要等 30 分钟超时才释放）。
清干净后三套立刻恢复 **77 / 72 / 125 项全通过，代码一行没改**。
由此加了三道防线：压测开跑前自愈、每档收尾、收尾全库兜底；外加 `cleanup-leftovers.py`。

**验证（全绿）**：后端单测 **228/0** ｜ 接口冒烟 **23 套 1551 项 0 失败** ｜
契约 `--check` PASS（241 端点）｜ 小程序无浏览器 **62/0** ｜ 前端真实浏览器 **9 套 321 项全 PASS** ｜
压测 `--check` 全部达标且跑完 FROZEN = 0 ｜ 静态自检 0 错 0 警 ｜ 14 容器 healthy。

### 2026-10-01（第六十轮 · 券放宽 + 性能压测 + 旧接口下线 + 两套测试环境 + 六套脚本迁移）

> 用户一次性派下五项，要求按顺序执行、每项说明「改了什么 / 怎么验的 / 结果」。
> 其中第 3 项（旧接口下线）依赖第 5 项（脚本迁移），故**先迁移脚本再删端点**。

**① 优惠券放宽（用户拍板「放宽」）**

| 规则 | 放宽前 | 放宽后 |
|---|---|---|
| 门槛 | 只看拆单后**金额最大那一子单** | 按本次下单的**商品总额**判定 |
| 抵扣 | 只抵在**最大那一单** | 按各子单金额**比例分摊到每一单**，取整余数补给最大单（各单之和严格等于券面额） |
| 有效期 | 秒级比较（到期日当天上午就不能用） | **按自然日**判定，到期日 23:59:59 前仍可用 |

券**归属不变**：仍只锁在归属单的订单号上，券码仍只写在归属单 —— 分摊改的是「钱怎么算」，不是「券归谁」。
新增 `CouponAllocationTest`（8 项，含「丢余数 → 总和小於面额」的反向验证）与 `CouponValidityTest`（4 项）。

**② 性能压测**：`scripts/perf/bench.py`（纯标准库，不依赖 JMeter）。
并发 10/25/50/100 × 10 秒，读/写两套基线（读 avg≤300 / P95≤600 / P99≤1000 ms；写 avg≤800 / P95≤1500 / P99≤3000 ms；错误率<0.1%）。
100 并发峰值：商品列表 1659 QPS / P99 175ms、搜索 1477 / 131ms、详情 1559 / 198ms、类目树 3782 / 69ms、闲置列表 1966 / 151ms、结算预览 2278 / 107ms，**错误率全 0**。结果落 `logs/perf/r60-bench.json`。
- **压测暴露并修掉真实缺陷**：`@GetMapping("/{spuId}")` 会捕获 `/api/products/home` 这类非数字段 → 转 `Long` 失败 → 兜底 **500**（应为 404）。已给 6 处同类路径加 `\d+` 形状约束。
- 压测器自身两处假绿同修：每请求新建 TCP 连接耗尽本机临时端口（改每线程长连接）；只把 5xx 计错会让 401/422 被算成成功（改非 2xx 一律计失败）。

**③ 旧接口下线**：`POST /api/orders/{orderNo}/ship` **只验内部凭据、不验归属**（有凭据就能发任何人的货）已删除，
收敛为 `/api/seller/orders/{orderNo}/ship`（`requireSellerScope` + `requireOwnOrder`）。过滤器注释与三处接口文档同步清理，`extract-api-contract.py --check` 转 PASS。

**④ 两套测试环境**
- 小程序「无浏览器」运行时 `scripts/mp-harness/`：`node:vm` 沙箱 + `wx.*` 桩 + 自实现 require，**直接加载仓库里真实的页面 js**。
  `pages-smoke.test.js` 逐个挂载 30 个页面（33 项），`ai-page.test.js` 断言 AI 页真实行为（4 项）。
- 前端真实浏览器：Playwright + Chromium 装进 `scripts/e2e/node_modules`（仓库自包含），新增一键运行器 `scripts/e2e/run-ui-checks.ps1`，**8 套全 PASS**。

**⑤ 六套脚本迁移**（aftersale / backfill / credit / logistics / payment / review / settlement）：旧发货端点全部切到卖家正规入口；
新增 `scripts/lib/platform-operator.ps1`（自营订单 `seller_id=0` 归平台，**只有 OPERATOR 可见**）。
结果：101 / 88 / 82 / 79 / 59 / 125 / 72，全 0 失败。

**迁移中修掉的三个共性问题**（都表现为「环境问题」，实为脚本问题）：
1. **PowerShell 5.1 的 BOM**：写回 .ps1 丢了 UTF-8 BOM，中文注释被解析成乱码 → `ParserError 字符串缺少终止符`。已批量补回。
2. **Node 同步派生态被禁**：本机 `spawnSync`/`execFileSync` 一律 `EBUSY errno -4082`（连 `cmd`/`where` 都起不来），而**异步 `spawn` 正常**。
   新增 `scripts/lib/docker-exec.mjs`（异步），6 套 e2e 的 `sql()` 全部改为 `await`。
3. **雪花/长 id 已按字符串下发**：`"id":"5003"` 拿数字 `5003` 去 `===` 恒 false。已统一字符串比对，`bigId` 正则放宽为 `"?(\d{15,})"?`。

**本轮质量口径**：后端单测 **222/0**（+12）；小程序无浏览器 **37/0** + 内部凭据对拍 **4/0**；
前端真实浏览器 **8 套全 PASS**；`run-all-smoke.ps1` **23 套全通过 / 0 失败**；Docker 8/8 jar md5 逐模块一致。

### 2026-09-25（第二十五轮 · 微信一键登录引导加固）

> 用户反馈「微信一键登录还是不行」+ 截图（`/api/auth/wechat/token 422`，屏幕无任何引导）。

- **判定**：422 说明令牌端点已生效；真正缺陷是**引导不闭环** —— 登录页**缺 `onLoad` 会话校验**，422 分支只用 `api.getToken()`（本地是否有令牌**字符串**）判断"已登录" → 残留过期令牌时被误判 → 直接 `bind` → 401 → 静默重登 → 又 422，**死循环且不提示**。
- **修复**：新增 `onLoad/verifySession`（`ensureSession` 校验真实会话，无效令牌立即 `clearSession`，以 `loggedIn` 取代字符串判断）；422 分支给「绑定当前账号（+换个账号退路）」与「用手机号登录并绑定」两条可执行路径；`bindCurrentWechat` 遇 401/令牌失效不再静默，改为清会话 + 切表单 + 弹「登录已过期」。
- **实测**：`bind` 无令牌/无效令牌均 401；`wechat/token` 未绑定 422 且文案更新；自检 0 错误 0 告警。提交 `b1a7c1d`。

### 2026-09-25（第二十六轮 · 登录成功后统一绑定微信）

> 用户反馈：用测试账号登录后**没有提示"已绑定微信"**。

- **证据**：user 容器日志有 `用户登录成功`（12:08:29 测试账号、12:09:22 新注册账号），**全程 0 次 `wechat/bind` 调用**；`t_user.wx_openid` 仍为 NULL。
- **根因**：绑定只挂在 `pendingWechatBind`（"先点微信登录被 422 拦下"才置位）这条路径上；用户直接点手机号登录 / 手动切 tab 都不设该标记 → 登录成功但从未绑定。
- **修复**：`submitPhone` 登录成功后**统一调用 `maybeBindWechat()`** —— 从 422 转来则直接绑定（意图明确），否则弹「绑定当前微信？」（去绑定/暂不）；绑定失败不影响登录成功。提交 `91514fd`。

### 2026-09-25（第二十七轮 · 分类页卡片/图标超出屏幕）

- **根因**：本页复用全局 `.goods-*` 类名并靠页面覆盖尺寸，覆盖一旦失效（样式缓存/编译顺序）就退回全局基线 `340rpx` → `340×2 + 左栏 200 = 880rpx > 750rpx`，右列被挤出屏幕、封面被放大到 340rpx。
- **修复（解耦 + 单列）**：商品区改用独立类名 `cg-*`（不再引用任何 `.goods-*`），单列横向卡、宽度全交给 flex。提交 `cd10c29`。

### 2026-09-25（第二十八轮 · 绑定被占用时异常被前端吞掉）

> 用户反馈：用另一账号点「去绑定」，Console 有 `409 (Conflict)`，界面却直接显示「登录成功」。

- **判定**：**后端判断正确**（409 = 该微信已绑定其他账号），是前端吞了异常：① `maybeBindWechat` 把失败归一成 `false`（错误对象被丢弃）；② 内部 toast 被随后的「登录成功」toast 覆盖。库内实证：该微信已绑在测试账号 `136****2050`（openid 前缀/长度与日志一致）。
- **修复**：`maybeBindWechat` 返回 `{bound, error}` 保留错误；新增 `notifyBindFailure` 用 **modal** 展示真实原因（409/503/令牌失效各有文案），关闭后再跳转；`bindCurrentWechat` 成功不再自己 toast。提交 `c7a9ea5`。

### 2026-09-29（R57 · AI 导购必修 6 项：M1 约束硬过滤 / M2 超时隔离 / M3 限流加固 / M4 输出护栏 / M5 澄清与出口 / M6 测试真绿）

> 用户指令（两阶段）：先调研淘宝「问一问」、京东「京言」等主流电商 AI 的架构与提示词逻辑并出改造计划
> （已交付《AI改造计划（调研+方案+现状对比）.md》，工作区），后指令「**仅实现 6 项必修要求，完成这 6 项后即视为
> 任务达成，无需处理任何选修或额外内容**」。开工前向用户修正了计划里 M1 的范围：**「生命阶段倾向」不在商品表里，
> 做不了 SQL 条件（本质属选修 S1），M1 实际落地为「预算区间 + 品类 + 在售」**。

**六项交付与关键取舍**：

- **M1 约束硬过滤**：`ConstraintParser`（正则解析「200 元以内 / 100-300 / 不超过 150」，三状态：解析到 / 没提 /
  **提了没听懂 → 必须告知「（我没听清你的预算数字，这次没按预算筛）」，绝不静默当没提**）；`CategoryCatalog`
  显式别名表（6 类）解决「类目名 vs 口语」鸿沟，5 分钟缓存、取不到降级不做品类过滤（比报错好）；预算与品类
  下推商品域 `internalCandidates` 在 **SQL 里过滤** —— 「预算写在提示词里 = 没有约束」，过滤必须发生在数据库查询里。
  价格语义：`budgetMax → min_price <= ?`、`budgetMin → max_price >= ?`（**重叠语义**，写反会误杀/误放）。
- **M2 超时隔离**：`AiFeignConfig`（`Request.Options(300ms, 1500ms)`），**刻意不加 `@Configuration`**
  （否则被组件扫描当全局配置），经 `@FeignClient(configuration=...)` 只作用于召回客户端 —— 商品域慢不再占住 AI 线程池。
- **M3 限流不可伪造 + 双层配额**：网关 `JwtAuthGlobalFilter` **先剥离**客户端可写的 `X-Forwarded-For`/`X-Real-IP`
  再写入 TCP 对端地址（**判据：凡是被下游当作事实使用的头，上游必须保证它只能由自己写入**）；
  `AiController.clientKey` 登录用 `u:{userId}`、未登录用网关写的 `X-Real-IP`、兜底 remoteAddr；
  `IpRateLimiter` 加 `__global__` 全站总配额 300/min，**两道判断都计数**（被单键挡住的流量也消耗全局额度，口径才不乱）。
- **M4 输出护栏**：`ReplyGuard` 拦两类 —— 绝对化/承诺词（广告法口径，刻意**不含**「最好/最适合」这类主观推荐语）与
  价格一致性（正文价格必须能在候选 min/max 里找到，只认带 ¥/元/块 的数字防「2 岁」误伤）；命中后
  **替换为事实版回复（`factsReply`）而不是拒绝回答**（行业共识：护栏的代价是 over-refusal）；
  mock 复用同一生成器，两条路径价格口径天然一致；命中记 WARN（稳定前缀 `AI护栏拦截`）+ micrometer 计数
  （`ObjectProvider` 可选注入，监控缺席不阻断启动）。
- **M5 失败出口 + 轻量澄清**：五类「答不了」（物流/退换/优惠/发票/支付）在调模型**之前**拦截 —— 这类问题没有
  数据源，交给模型只会得到听起来像真的编造（售后政策说错要担责），如实给入口既诚实又省配额；澄清三条件
  （没澄清过 + 无硬约束 + 召回空或 ≤6 字）同时满足才问，`ClarifyView.options` 的 label 与 send 分离，
  前端 `tapOption` 直接发 send 文本；`_clarifiedSent` 放实例不放 data（不参与渲染）。
- **M6 测试真绿**：`ai-smoke.ps1` 预算断言从「仅 llm 模式」改**无条件必查**（旧版在 mock 下 SKIP，而 CI 永远 mock
  → **长期空转假绿**——本仓已两次踩这类坑）；新增：区间重叠断言、**极小预算（1 元）必得空结果**（这是无条件断言的
  「反向验证」——证明预算参数真的传进了 SQL，而不是被半路丢掉）、预算未解析告知、「最便宜」不误判、出口预判、
  澄清两次探针、**伪造 XFF 不能绕过限流**（每次换假 IP 夹在限流循环里）；新增 `ConstraintParserTest`（18 项）+
  `ReplyGuardTest`（11 项）+ `r57-ai-clarify-test.js`（23 项，真实加载 api.js 与 ai.js，含 3 组反向验证）。

**★ 本轮单测当场抓到的两个真缺陷（都不报错、只悄悄推荐错）**：

1. **同位重匹配**：「预算 200 元以内」里 `BUDGET_MAX` 与 `BUDGET_MIN` 的首分支都能命中「预算 200」→
   min=max=200 → 「99~159 元」的便宜货被 `max_price >= 200` 误杀（用户说 200 以内，最该推的便宜货消失）。
   修法：MIN 匹配**跳过与 MAX 命中区间重叠的命中**（同一个数字被两套正则各数了一次，不是用户真给了上下限）。
2. **「最便宜」误判**：信号词表里的「便宜」把「那最便宜的是哪个？」判成「预算没听懂」，每句追问都被打上道歉前缀。
   修法：负向后视 `(?<!最)便宜`。

**★ 部署教训（R52 教训二次踩坑）**：改完代码只 `mvn compile` 不 `package` → `compose build` 的 COPY 层缓存命中
→ **旧 jar 进镜像、容器行为照旧** —— 首轮冒烟 5 项失败全是「新逻辑没生效」（mode=llm 的容器跑着没有 off-topic/
clarify 的旧代码）。修法：`mvn package` 重打三模块 + 解包 `unzip -l` 验证 jar 含 `ConstraintParser.class` 等新类
再 build/up。**验证构建产物内容，别只看退出码。**

**验证**：ai 单测 **29/0** + 前端 `r57-ai-clarify-test` **23/0**（含反向验证 3/3 命中，并修掉谓词 `height` 误配
`line-height` 的洞）+ `ai-smoke` **37/0**（11 项新增）+ 回归 **346/0**（order 77 / address 82 / payment 71 /
share-circle 74 / notice 42）+ 静态自检 **0 错 0 警** + 三容器重建 healthy。

### 2026-09-30（R59 · 未完成项清单收口：券口径 / 选主 / 内部凭据 / 商家商品与退货物流 / 角色即时生效 / 发布限流 / 路径消歧 / 零单测模块 / 文档同步 / AI 加强 6 项）

> 用户指令：先输出「未完成项清单」（任务名 / 涉及文件 / 完成状态 / 未完成原因），确认后**按优先级全量开工**，
> 保持既有代码风格，完成后**逐项汇总改动 + 指出仍需人工确认的地方**。清单见工作区
> 《未完成项清单（2026-09-30 复核版）.md》（22 项 + 暂缓项）。

#### P0（安全与正确性底线）

- **#1 券 v1 口径**：维持「不支持的组合**明确 422 拒绝**」这一既有行为（不擅自放宽成静默降级 ——
  静默降级会让用户以为券生效了、结算时才发现没减），改用**有断言的单测**把它固化下来：
  `CouponV1BoundaryTest`（非满减类型一律拒绝且文案明确 / 类型大小写不敏感 / `release` 只解锁 LOCKED、
  已核销的不退回 / 没有锁定券时不报错且返回 0）+ `CouponStatusTest` / `CouponCodeGeneratorTest`
  = marketing 券相关共 **13 项**，
  避免以后有人「顺手改成放行」而无人察觉。**口径本身仍需产品确认**（见文末人工确认项）。
- **#2 调度选主（多实例重复跑）**：新增 `common-web` 的 `SchedLeaderLock` + 表
  `docs/sql/incremental/23-r58-sched-leader.sql`（主键即锁名，`expire_at` 自动过期）。
  抢占语义**两步且顺序不能反**：先 UPDATE（续约 / 接管过期锁）→ 影响 0 行再 INSERT（首次运行），
  撞唯一键即表示别人持有、本轮放弃。**先 SELECT 再写**在并发下会让两个实例都看到「没这行」而双双写入。
  锁事务 `REQUIRES_NEW` **独立提交**（不能挂在业务事务里 —— 业务回滚会把锁一起回滚，等于没上锁）。
  4 个既有定时任务接入，`duoduo.sched.leader-enabled=false` 可整体退回外部调度（零新增中间件）。
- **#3 内部凭据升级**：服务间调用从「明文共享密钥相等即放行」改为 **HMAC 签名**
  `dd1.<caller>.<tsSeconds>.<nonce>.<签名>`（签名覆盖调用方 + 时间戳 + nonce），5 个域的
  `InternalTokenFilter` 统一换成 `common-web` 的 `InternalCallAuth`；带时间窗与 nonce，
  **失败关闭**（解析/验签异常一律拒绝，不放行兜底）。冒烟脚本共用 `scripts/lib/internal-token.ps1` 生成。

#### P1

- **#4 商家自助管理自己的商品**：`SellerProductService` 抽出 `requireOwned()`（**上下架与编辑共用同一条归属判定** ——
  少一处就变成「上下架挡住、编辑没挡住」这类只在越权时才暴露的漏洞，而两处代码看起来几乎一样）；
  新增 `PUT /api/seller/products/{spuId}` 编辑**标题与主图**。字段**三态**：`null` = 不改 / `""` = 清空 /
  有值 = 替换（把「不改」传成空串，用户原有主图会被清掉）。**价格 / 库存 / 类目刻意不开**：
  那牵动 SKU、ES 索引与在途订单金额口径，属「改价即改合约」。标题变更后**不直接写 ES**，
  交给幂等的对账型同步任务收敛（直接写索引反而绕过它）。单测 `SellerProductServiceTest`（8）。
- **#5 退货物流**：`t_after_sale` 增寄回运单 / 签收四列（`docs/sql/incremental/24-r58-return-logistics.sql`），
  状态机新增 `WAITING_RETURN`（买家填寄回）→ `RETURNING`（卖家签收，**退款在这一刻才发生**）。
  **退款时点从「卖家同意」挪到「卖家确认收货」** —— 同意即退款意味着货没回来钱先走；而「等收货再退」
  若没有系统入口，就只是一句靠线下执行的空话。**刻意不建退货轨迹表**：正向轨迹的「签收」是订单交付终态，
  混进退货节点会让「已签收」在一张订单里有两种含义，是对账与前端展示的长期隐患；退货流程真正需要的
  只有「寄回 / 收到」两个事实。新增 `POST /{id}/return-shipment`、`POST /{id}/return-receipt`，
  两态**同样可进仲裁**（货寄出后卖家一直不确认收货时买家不能没有出路）。
- **#6 角色升级即时生效**：新增 `AuthCookieWriter`（`dd_at` / `dd_rt`），`renewAccessToken`
  **从库现读角色**（拒绝调用方传角色 —— 传参等于允许伪造）、BANNED 抛 403；商家申请通过后
  **只覆盖访问令牌 Cookie，不动刷新令牌**（升级场景无需逼用户重登）。
  **降级 / 运营改角色必须吊销全部刷新令牌**（`revokeAllByUser`）—— 权限变小却留着旧刷新令牌，
  等于降级可被打回。新增 `POST /api/users/me/merchant/token` 供前端静默换取。
  单测 `RoleTokenEffectiveTest`（6）+ `AdminUserServiceRoleChangeTest`（6）。
- **#7 闲置发布频率限流**：`BizException.rateLimited()`（**429**）与 `businessRule`（**422**）语义分开 ——
  429 是「内容没问题但发太快」、422 是「改一改就能成功」，混用会让前端把「等一分钟」提示成「填写有误」。
  `ListingService.checkPublishQuota()`：滑动 24h 计数（**含软删**，否则删了重发即可绕过）+ 最小间隔，
  阈值可配（默认 10 次/天、60 秒）。单测 `ListingPublishRateLimitTest`（7）。
- **#8 路径消歧**：`@GetMapping("/{orderNo}")` → `@GetMapping("/{orderNo:\\d{21}}")`（订单号固定 21 位纯数字）。
  否则 `/api/orders/xxx` 这类「明细」与「其他子路径」会互相抢占匹配，表现为随机 404/405。
  冒烟断言由 `st in (404,405)` **收紧为 `st == 405`**（宽松断言等于没断言）。
- **#9 旧内部端点收敛**：**未开工** —— 需先迁移 6 套冒烟脚本，见文末人工确认项。

#### P2 / P3

- **#10 零单测模块补单测**：新增 `JwtAuthGlobalFilterTest`（8：公开读放行 / 写路径拦 /
  三段不被两段通配连带放行 / 微信绑定需登录 / **伪造身份头与 IP 头被剥离** / OPTIONS 放行）、
  `NoticeServicePushTest`（7：两阶段落库 —— 第 N 条非法则整批不写、落地页白名单、公告收件人固定 0）、
  `UnreadServiceTest`（3：角标不重叠）、`SnowflakeIdGeneratorTest`（6：首条断言
  **生成的 id 超出 JS 安全整数** → 前端禁止 `Number()` 强转有了机器可检的依据）、
  `AfterSaleServiceTest`（9）、`SellerProductServiceTest`（8）。
- **#14 过时文档**：`README.md` 里「AI 导购未部署」改为已上线（`DUODUO_AI_MOCK_ENABLED` 决定形态）。
  **#15 归档**：`docs/PROJECT-STATUS.md` → `docs/archive/`，同步 `GITHUB-PUSH-GUIDE` 与 `handover/08`。
  **#16 待办同步**：`docs/agent/07` 追加 R57/R58，`docs/待协助问题.md` H-01 销项。

#### AI 加强（#17~#22，原 R57「加强 3 项」扩展）

- **#17 宠物档案个性化**：AI 侧新增 `UserProfileClient` + `PetBrief`，用户域新增
  `GET /api/users/internal/pet-profiles` 与**裁剪视图**（**不含病史 / 生日 / 体重**，
  越少字段越安全）；`PetContext` 三条硬边界：**档案只补充不覆盖**（用户明说品类时不注入）、
  取不到就当没有、**只说档案里有的事实**。
- **#18 政策知识库**：`PolicyKnowledge` 5 条，**只写流程与入口、不写时效与赔付数字**
  （那些是配置项，写死就是另一种编造）；**条目顺序 = 匹配优先级**，仲裁条目排最前
  （否则「卖家不同意退款」会先命中含「退款」的退货条目）。
- **#19 快捷问题 / 追问 / 对比**：`Suggestions`。**对比不进模型** —— 只用候选客观字段并列，
  不写「哪个更好」（需要成分 / 适配数据，服务没有）。**前端必须消费**（后端下发而页面不接 = 没做，
  接口探针永远测不到）：小程序 `pages/ai` 新增「大家都在问」快捷 chips（**只在首屏显示** ——
  聊过之后再显示类目级问题会打断话题）+ 回答后的相关追问 chips（与澄清选项同形、颜色更淡以区分
  「AI 在问你」与「你还可以问」）；**换话题时 followUps 与 options 一起清掉**（旧追问是针对上一轮候选的）。
  新增 `scripts/r59-ai-suggestions-test.js` **22 项**（含 4 组反向验证）。
- **#20 限流多实例**：`IpRateLimiter` 改 `ObjectProvider<StringRedisTemplate>`（有 Redis 就共享窗口，
  否则内存）；Redis 报错**冷却 60 秒**再回退，不每次重试（超时会叠加到每个对话）。
- **#21 mock 护栏可达性**：`MockReply` 走**独立话术** —— 原先 mock 复用 `ReplyGuard.factsReply`，
  护栏检查的是自己的输出 → **永不命中**（这就是「有护栏、没验证过」的典型）。
- **#22 召回去重多样性**：`RecallDiversifier` 按「标题去规格后缀」归一去重、同款保留更便宜那件
  （原始取 12 → 去重后留 8），避免整屏同款不同规格。

#### 联调补丁（编译过了、单测绿了，只有容器真跑起来才现形的三项）

- **补丁 1 · 内部凭据签名去掉请求路径**：原实现把 path 签进 HMAC 规范串，而 Feign 的
  `RequestTemplate.path()` **取不到 `@FeignClient(path="/api/products")` 的类级前缀**
  （实测客户端 `path=/internal/skus/batch`、服务端实际路径 `/api/products/internal/skus/batch`）
  → 两边签出来的串永远不等 → **全站服务间调用 403**，冒烟从 20/23 掉到 3/23。
  改为只签 `METHOD`（+ caller / ts / nonce）：安全性不下降（nonce 一次性，且「签了但服务端不校验」
  只会增加假失败面）。同步改 10 处调用点 + `internal-token.ps1` / `r54` / `r55` 三个脚本；
  新增单测「换路径不影响校验结果」。
- **补丁 2 · `spring-boot-starter-jdbc` 标 `<optional>true</optional>`**：AI 服务没有数据库，
  依赖被**传递**进来 → `DataSourceAutoConfiguration` 启动失败（`Failed to determine a suitable driver class`）。
  且 `@ConditionalOnClass(JdbcTemplate.class)` 必须挂在**内层配置类**上 —— 挂在 `@Bean` 方法上时，
  Spring 为解析方法签名会先加载 `JdbcTemplate`，条件注解还没生效就 `NoClassDefFoundError`。
- **补丁 3 · `NoResourceFoundException` 显式映射为 404**：加了路径形状约束（如 `{orderNo:\d{21}}`）后，
  形状不匹配的请求不再命中 Controller，转去查静态资源再抛该异常 → 被 `Exception` 兜底成 **500**。
  「不存在的订单」必须是 404，否则前端的重试 / 空态判断全错。新增 `GlobalExceptionHandlerTest` 2 项。

**★ 本轮教训**：① **mock 形态必须有自己的出口**，否则护栏 / 断言全在自查自纠；
② **429 与 422 不能混**；③ 断言**收紧**才有意义（`in (404,405)` → `== 405`）；
④ 新增类必须 `unzip -l` 验进 jar（R52 教训第三次复述）；
⑤ **后端下发 ≠ 前端接了** —— `quickQuestions` / `followUps` 服务端早就返回，页面不渲染等于没做，
而接口探针永远测不到；⑥ **反向验证也可能空转**：两个 chips 区块写法相同时，
全文件 `includes` 会把「改坏了的那段」之外的同名字段也算作命中 —— 反向验证必须**按块取片段**判定。

⑦ **形态自适应要问服务端，不要读配置猜形态** —— 微信登录冒烟场景② 有两种合法形态
  （注入真实 AppID → 假 code 被微信侧拒 → 401；未注入 → 外部依赖缺失 → 503 降级）。写死 503
  在配了凭据的机器上永远红；改读 `.env` 判断仍不稳（compose 是**环境变量注入**，
  `.env` 的位置 / 编码 / 键名都可能踩空）。最终按**实际返回**选分支，非 401 非 503 才算真失败；
⑧ **守卫报警不等于真有问题，先确认守卫本身对不对** —— 断言总数守卫原放在「清理夹具」那条 Check
  **之前**，于是把那条还没执行的断言当成「被静默跳过」报红；且互斥分支的声明数要扣掉未走的那几支。

**验证**：全模块 `mvn -o test` 全绿（common 6 / **common-web 21** / gateway 8 / user 15 / product 15 /
trade 19 / fulfilment 9 / marketing 13 / **ai 94** / social 10 = **210/0**）；前端 node 单测
**251/0**（既有的 229 项全过 + 新增 `r59-ai-suggestions-test` 22 项）；小程序静态自检 **0 错 0 警**；
`mvn -o -DskipTests package` **BUILD SUCCESS** 且新类均在 jar 内；每一项均做**反向验证**
（故意改坏 → 断言转红 → 还原）。
**容器联调**：`docker compose build` 8 个业务镜像全部 Built；`docker compose up -d` 14 容器全 healthy；
**逐模块 md5 比对宿主机 jar 与容器内 `/app/app.jar` 8/8 一致**（镜像里跑的确实是最新代码 ——
只看构建退出码不算数）。冒烟 `smoke.ps1` **55/0**、`wechat-auth-smoke.ps1` **26/0**。

### 2026-09-30（R58 · 资金闭环 / 测试底盘 / 闲置编辑删除 / AI 问候身份出口）

> 用户指令：先做全项目未完成项盘点（AI 相关仅记录、不改），再按 A→B→C→D 顺序无中断推进。

- **A1 退货入库幂等**：新增 `t_sku_restock_log(ref_no, sku_id)`（**主键即去重键**）+ `INSERT IGNORE`；
  `StockChangeRequest` 增 `refNo`，`ProductGateway.restock` 传 `restock:{orderId}`。重复提交回执**如实返回 0 件**
  （原实现会把上次的数量再说一遍，等于谎报回补）。探针 `r58-restock-idempotent-smoke.py` **10/0**。
- **A2 平台垫付与卖家追偿**：新增 `t_seller_debt`（`uk_seller_debt_order` 唯一键）、`SellerDebtService`
  （**幂等判定前置**于状态校验，否则重复垫付会报「当前状态 REFUNDED」这种误导提示）、托管单新增
  `RELEASED → ADVANCED`（`CUSTODY → RELEASED → ADVANCED` 是**新增边不是回退**）。垫付**不回补库存**（资金动作 ≠ 退货）。
  运营端点 `POST /api/admin/settlements/compensate`、`GET /debts`、`POST /debts/{id}/settle|write-off`。探针 **20/0**。
- **B 测试底盘**：交易域 +16（OrderStatus / SettlementStatus / SellerDebtService）、营销域 +9（CouponStatus /
  CouponCodeGenerator）、AI 域 +11（GreetingIdentityExit）；新增 `scripts/extract-api-contract.py`
  （从源码机械抽取端点写回 `docs/agent/04`，**241 个端点**，支持 `--check` 门禁，落后即退出码 1）。
- **C 闲置编辑与删除（B-14）**：`POST /api/listings/{id}/edit`、`POST /api/listings/{id}/delete`。
  **路径必须三段** —— 白名单是 `/api/listings/*`（两段、不区分方法），两段会把写端点放成匿名可调。
  删除为**软删**（`t_listing.deleted_at`，行留库供订单/会话/举报追溯），`visibleWrapper()` /
  `detail()` / `requireOwned()` / `listMine()` / `markWant()` / `internalCards()` / 运营审核队列全部排除软删，
  ES 侧 `SearchIndexer.indexListing` 对软删直接摘文档。编辑回到 `PENDING`；**驳回件编辑后恢复 `ON_SALE`**
  （否则通过审核仍不可见且无入口放出来）。探针 `r58-listing-edit-delete-smoke.py` **33/0**（含反向用例：
  匿名读详情对照组证明白名单确实放行两段，从而三段被挡不是空转断言）。
- **D AI 问候 / 身份出口**：`ChatService` 新增 ①b 出口（在「答不了」之后、召回之前）。
  判据是「**剥掉问候词与语气词后还剩什么**」：剩空 = 问候；剩句命中带「你」字的身份问法 = 身份。
  身份词表**刻意不含**「有什么功能 / 怎么用」—— 「这个猫爬架有什么功能」问的是商品不是助手，
  而漏判只让用户多问一次、误判会让用户觉得助手听不懂人话，两者代价不对等。
  单测 11 项（4 条反向）+ 端到端 7 句冒烟全对；**反向验证**：改坏词表后 4 项转红。

**★ 本轮教训**：① 方法引用写成 `ListingEntity::getDeletedAt()`（多带括号）是**语法错误**，
javac 报「需要')'或','」—— 位置指向 `getDeletedAt` 的右括号，容易误判成类型问题；
② 探针里 `SELECT (deleted_at IS NOT NULL) AND status` 会把 status 字符串转数字**恒为 0**，断言假失败。

### 2026-09-29（R56 · 分享圈补全：评论 / 关注流 / 删除编辑 / 举报与运营处理台 / 话题标签）

> 用户要求（第三段）：「优先完善分享圈功能」，四项全做 —— ① 评论 + 回复 + 评论点赞；② 关注流（推荐 / 关注双 tab）；③ 删除 / 编辑自己的分享 + 举报；④ 话题标签 `#`。

**★ 决策点（动手前先问、避免返工的两条）**：① 举报做到哪一层 → 用户选**完整闭环**（含运营处理台页面）；② 别人转发过你的分享后你删了原帖，转发单里的引用块怎么显示 → 用户选**「原内容已被作者删除」**。
第二条必须先定，原因写在代码里：**转发单的「原帖引用」是查询时按 `parent_share_id` 回查原帖行得到的**（不是快照）—— 删除语义晚定，这块就要返工。

#### 一、根因 / 缺口（实测确认）
1. **评论链完全不存在**：全仓搜 `comment` 在业务代码里**零命中**（命中的全是 SQL 字段注释与 `node_modules`）—— 表、接口、页面、样式全都没有。
2. **发布后无法收回**：无删除、无编辑。`t_product_share.updated_at` 字段早在第四十三轮就建了，但注释里写着「预留（暂不提供编辑）」，从未被写入。
3. **无举报**：业务侧零命中（`AdminReportController` 是运营报表，与此无关）。
4. **无话题**：零命中（`topic` 命中全是 RocketMQ 的 topic）。

#### 二、修复

**DDL（`docs/sql/incremental/19-r56-share-circle.sql` + 04 全量同步）**
- `t_product_share` 加 `deleted_at`（软删）、`edited_at`（编辑时刻，与 `updated_at` **刻意分开**：后者会被点赞计数等非内容变更刷新，拿它当「已编辑」判据会误报）、`comment_count`。
- 新表：`t_share_comment`（`parent_comment_id` NULL = 一级）、`t_share_comment_like`、`t_share_report`（SHARE + COMMENT 一张表）、`t_share_topic`、`t_share_topic_rel`。

**评论（`ShareCommentService` + `ShareCommentController`）**
- 二级结构：回复的 `parentCommentId` 必须是**同一条分享下、未被删除的一级评论**；「回复一条回复」→ 422（明确不做三级）；跨分享回复 → 400（几乎总是前端把上下文串了，比 404 好定位）。
- 计数口径：**一级与回复都算**（与卡片上那个数字的语义一致）；发表 +1，运营下架评论时 `-(1 + 其下未删回复数)`（父评论删掉后回复在 UI 上已不可达，故一并回退）。
- **★ 路径形状即安全边界**：评论列表 `GET /api/shares/comments?shareId=`（一段 → 落进已有白名单 → 匿名可读），发表 `POST /api/shares/{id}/comments`（两段 → 强制登录）。若列表写成两段，要放行就得加 `/api/shares/*/comments`，**那会连带放行匿名 POST 评论**。
- 通知复用 R55 的生产者端点（best-effort）：一级评论 → 分享作者；回复 → 被回复者**并同时**通知分享作者（批量端点一次带两条）；三种不推：评论自己的分享、回复自己的评论、作者即被回复者。

**关注流（`ShareService.feed` + social 内部端点）**
- social 新增 `FollowInternalController` + `FollowService.followingIds(userId, limit)`：**只返回 id**、按关注时间倒序、**服务端再夹一次上限 500** —— 调用方要拿这批 id 去拼 `WHERE user_id IN (...)`，不夹上限会变成超长 IN 列表（既慢又易撞 MySQL 包大小限制）。
- fulfilment 新增 `SocialClient`（`contextId = "socialFollowingIds"`；本域已有 `socialNoticePush`，不区分会启动即炸）。
- `feed?scope=FOLLOWING`：**未关注任何人返回空列表，绝不退化成全站内容**（那会让用户以为自己关注的人发了一堆没看过的内容，是最糟的降级方向）。社交域不可用**显式报错**「关注列表暂时获取不到，请稍后重试」，而不是静默空列表 —— 空列表会让用户去检查自己的关注列表，然后找不到问题。

**删除 / 编辑 / 举报（`ShareService` + `ShareReportService`）**
- **软删**：所有读路径一律 `deleted_at IS NULL`；删除时同步回退话题计数。两个具体原因写进了实体注释：① 转发单引用块靠回查原帖；② 举报留痕与计数需要「这条内容曾存在」。
- `requireVisible(shareId)` 是**唯一**的「已删除 = 不存在」判定口，公开给同包服务用 —— 否则会出现「列表看不到、但评论还能发」这种自相矛盾的状态。
- 编辑只改文字与话题；**`topics` 三态**：`null` = 不改、`[]` = 清空、数组 = 替换（混淆「不改」与「清空」会让用户永远删不掉话题，前端单测专门锁死）。
- 举报：理由必填、不能举报自己（那是删除，不该占运营队列）、重复 409（唯一键兜并发）、幽灵对象 404。**运营 `ACCEPT` 才下架**（软删 + 回退计数），`REJECT` 只记结论；重复处理 409（否则处理人 / 时间会被覆盖，审计链断掉）。
- **运营处理台**（`ShareAdminReportController` + `frontend/src/pages/admin/ShareReports.tsx`，后台第 8 页签）：列表**直接显示被举报内容摘要**（服务端 `targetExcerpt`）；弹窗**默认结论为「举报不成立」**（与售后裁定弹窗同一取舍：误点一次不会把别人的内容删了）。
- **明确不做**：自动判定（关键词 / 模型）、「被举报 N 次自动下架」—— 需要误伤评估与申诉链路，是另一个量级的产品决策。

**话题（`ShareTopicService`）**
- 服务端规范化是**权威**：剥 `#`、去首尾空白、去重、限长 30、含空白 / `#` / 控制字符的丢弃；**超上限是截断而不是报错** → DTO 上刻意**不加** `@Size(max = 2)`。
- 「查不到就建」用 **INSERT 撞唯一键再回查**，不用「先 SELECT 再 INSERT」：两人同时首发同一话题时，先查后插会让其中一个拿到 500，而那本来就是正常行为。
- 编辑用**差集替换**（先算差集再逐条加 / 减），不用「全删再全加」：后者会让计数抖动（先 -1 再 +1），并发的中间态还可能算错。
- 聚合页用 **JOIN 一条 SQL**（`ShareMapper.selectPageByTopic`）+ 独立 count，而不是「先查关系表拿 id 再 selectBatchIds」：后者在「命中的分享有一部分已被删除」时这一页会**少几条**，分页页数越翻越诡异。
- 白名单新增 `/api/shares/topics/*`（话题聚合是两段路径）；`/api/shares/topics` 已被既有的 `/api/shares/*` 覆盖。**必须写 `/topics/*` 而不是 `/*/*`** —— 后者会把 `/{id}/edit`、`/delete`、`/report`、`/{id}/comments` 四条写路径一起放成匿名可调。

**小程序**
- 分享详情页：评论区（一级评论 + 回复展开 + 评论点赞 + 回复输入）、话题胶囊、举报入口（**只对他人内容显示**）、固定的底部输入条（页面尾部留等高空位）。
- 分享圈：**推荐 / 关注双 tab**（切「关注」先 `requireLogin`，切换时清空列表 —— 不清空会让上一段范围的内容短暂留在屏上）、卡片带评论数、话题胶囊、举报入口、「我的分享 ›」入口。
- 新页 `pages/share-mine`（我的分享：编辑弹层 + 二次确认删除）与 `pages/share-topic`（话题聚合 / 话题广场，无 `name` 时显示热门榜）；`profile` 页加「我的分享」入口（分享圈里已有一个，但「我的东西」用户第一反应是来「我的」页找）。
- 内联循环别名（`wx:for-item="tag"` / `"rep"`）已按静态检查的规则声明，不再触发「字段可疑」误报。

#### 三、★ 本轮抓到的三个真缺陷（全部由探针 / 单测当场抓到，不是事后猜的）
1. **新计数字段漏进数字白名单**：`commentCount` / `replyCount` / `shareCount` 未列入 `LongToStringJacksonModule.NUMERIC_LONG_FIELDS` → 以**字符串**下发。后果具体：前端 `count > 0` 之类比较退化成**字典序**（`"10" < "9"`），而「话题按使用次数排序」直接排错。修法：加进白名单并写明规律 —— **新增任何「前端会拿去比大小 / 加减」的计数字段，必须同时加进白名单**（同 R44 的 likeCount、同 R50 的 record 计数）。**探针的报错方式很典型**：Python 里 `'5' >= 2` 直接 TypeError，一眼可见 —— 如果只在前端做格式化，这类错会静默变成「排序莫名其妙」。
2. **DTO 注解与设计直接冲突**：`ShareCreateRequest.topics` 上的 `@Size(max = 2)` 让「多带一个话题」变成 **400 整单失败**，与「截断而非报错」的设计矛盾 —— 服务层的截断逻辑因此**永远不被执行**（一段写得漂亮但不可达的代码）。
3. **新建路径的回执缺昵称**：评论创建时把 briefs 传成空表 → 回执里昵称是「用户3612…」这种长号，与随后重拉列表的显示不一致（用户视角是「刚发的评论显示错了」）。教训：**「写成功」的回执要用与「读列表」同一套装配**（`assemble()`），手拼一次就会漏字段。

#### 四、验证
- 新增 `scripts/r56-share-circle-smoke.py` **74 PASS / 0 FAIL**：A 话题 12（含剥 # / 去重 / 超 2 个截断 / 非法名丢弃 / 热门榜计数 / 聚合页免登可读 / 替换后旧话题计数回退）+ B 评论与回复 15（含免登可读列表、回复回复 422、跨分享回复 400、点赞幂等、两处 commentCount 一致）+ C 删除与编辑 18（含越权 404、**原帖删除后转发单 `parentDeleted=true` 且 `parentContent=null`**、话题三态、转发单可清空附言）+ D 举报与运营处理 19（含重复举报 409、举报自己 422、非运营 403、**裁定成立才下架**、**评论下架后 commentCount 回退**、重复处理 409）+ E 关注流 7（含**未关注任何人返回空且 ≠ 全站**、取关后回到空、非法 scope 按默认处理）。
- 新增 `scripts/r56-share-comment-test.js` **74 PASS / 0 FAIL**：真实加载 `api.js` + 四个页面 js、桩掉 `wx.request`，断言**前端实际发出了什么**（编辑话题三态、评论 / 回复 / 举报请求体、路径形状、19 位 id 未被 `Number()` 破坏）+ 页面行为（编辑回填与切分 / 删除二次确认 / 切范围先登录且清列表 / 评论追加与回复挂载 / 话题页两种形态）+ **五处胶囊四条齐备**（显式高度 + `line-height:1` + 半径 = 半高 + 容器 `align-items:center`，剥 CSS 注释后匹配，并做反向验证）+ 页面清单与后端白名单一一对应。
- 新增 `scripts/e2e/share-report-ui-check.mjs` **24 PASS / 0 FAIL**（真实 Chromium）：运营处理台列表显示摘要 / 弹窗默认**不**选中「下架」/ 说明必填拦截 / **驳回后该行从「待处理」消失**（同时证明处理完列表真的刷新了）/ 接口层复核状态与处理人 / **驳回不误删内容** / 非运营看到准入提示。
- **回归 614 项全过**：接口探针 385（r55 42 · r54 26 · r53b 26 · r53 28 · r53-hotfix 17 · r52 57 · r50 18 · r49 15 · share-circle 39 · social 43）+ 前端单测 229（角标 59 · r54 文案 23 · r56 74 · 请求体精度 19 · 闲置筛选 42 · 时间换算 12）。
- 静态自检 `check-miniprogram.js` **0 错误 / 0 告警**，并**修掉一处误报**（见教训 3）。
- 前端 `tsc --noEmit` 零错误、`vite build` 通过（84 模块）。
- 9 容器健康。**改了共用模块 `duoduo-common-web` → 8 个业务镜像全部重打**（R52 教训），并解包校验产物确实含新白名单字段。

#### 五、教训
1. **跨域过滤要设计好「取哪一批」**：关注流若把关注列表原样塞进 `IN`，关注上千人就会变成超长 SQL。**上限要夹在被调用方**（调用方自觉不可靠），并把「取不到」与「真的没关注」区分开 —— 这两种情况的正确处理完全相反（一个该报错、一个该给空态）。
2. **「删除」在有引用关系时不是删除一行**：转发单靠回查原帖显示引用块，所以物理删会让别人的页面坏掉。**先问「谁引用我」，再决定软删还是硬删**。
3. **静态检查的误报会把告警变成噪声**：原检查器靠一份硬编码别名表识别 `wx:for-item="tag"`，漏一个就对**合法写法**报「字段可疑」。改为**从 WXML 里实际读出声明的别名**，并做两轮反向验证（裸标识符拼错仍能抓到 / 删掉声明后立刻重新报警）。**误报比漏报更伤**：噪声一多，下一次真有问题的告警也会被忽略。
4. **DTO 注解也是业务规则的一部分**：`@Size(max=2)` 与「截断」是两种互斥的产品行为。写完服务层的容错分支后，要回头确认它**是否可达** —— 不可达的容错等于没有。

### 2026-09-29（R55 · 通知体系补全：生产者 + 合并未读 + 数字角标 + 首页卡片弹窗）

> 用户要求：**暂缓担保交易与微信一键登录**，优先「完善分享圈」+「完善通知功能」——收到他人消息时显示带数字（如 1、2、3、4）的未读图标，并在主页增加小卡片式弹窗提示；另评估 AI 是否还有需修复/加强之处。

本轮做**通知**（分享圈 R56、AI R57 顺次跟进）。用户已点选锁定口径：**未读角标 = 通知 + 私信合并成一个数字**（显示在 tabBar「通知」上）；**首页提示 = 顶部滑入小卡片，4 秒自动收起、可点进**。

#### 一、根因（全部实测确认）
1. **缺的不是界面，是「没有生产者」**。`t_social_notice` 全库只有 2 处种子共 **3 条公告**；全仓搜 `internal/notices` **零命中**，搜 `MessageService` 里的 notice **零命中** —— 设计稿里「trade / fulfilment 各域推通知」的端点**从未实现**，也就是**没有任何服务会往通知表里写行**。R50 时通知页 / 未读数 / 已读都已就绪，但列表里永远只有那 3 条公告。
2. **角标只在通知页刷新**。全仓 `setTabBarBadge` **唯一调用点**是 `notification.js`，由该页 `onShow` 触发 —— 用户在首页 / 分享圈 / 我的页面**完全看不到数字**，而这三个页面恰恰是用户待得最久的地方。
3. **私信未读与通知未读本是两套数**，没有「合并」的地方 —— 而用户要的是一个数字。

#### 二、修复

**后端 · 补生产者（social）**
- 新增 `POST /api/socials/internal/notices`（批量推送，返回 `accepted`）+ `POST /api/socials/internal/notices/announce`（全站公告，收件人服务端固定为 `0`）。整个 `/api/socials/internal/**` 由 `InternalTokenFilter` 保护，**缺凭据一律 403**；**只写不读**（读一律走用户侧接口，带登录态 + 归属校验）。
- **两阶段落库**：`pushBatch` 先把整批逐条校验（分类白名单 / 标题正文长度 / `jump_page` 真实性）再统一 insert —— **第 N 条非法则整批不写**，调用方不必担心「一半成功」。
- **★ `jump_page` 必须硬校验**：新增 `support/MiniProgramPages`（28 个页面路径，与 `app.json` 一一对应，只校验路径段、query 不参与）。拼错路径**编译期不报错、运行日志也不报错**，只在用户点击时表现为「点了没反应」——这类缺陷只能靠白名单挡住。
- **私信落库即写通知**：`MessageService.send()` 末尾 best-effort 调 `pushMessageNotice(entity)`，按 `senderRole` 分流 `FRIEND` / `MERCHANT`，**同会话未读合并**（窗口内同 `bizType + bizId` 原地 update，id 不变；用户读完后对方再发会新插一条 —— 合并的边界是「未读」而非「永久一条」）。
- **合并未读落服务端**：新增 `UnreadService.summary(userId)` → `{notice, message, total}`，`total = notice + message`。挂在 `GET /api/socials/notices/unread-summary`（不新建控制器：角标讲的就是「通知中心的未读」，私信只是恰好也贡献未读，前端也只需要一个刷新入口）。

**后端 · 补生产者（trade / fulfilment）**
- trade 侧 4 处：`placeOrder` 末尾（**只在真实建单后**，replay 路径不推）、`completePayment`（CAS 之后）、`shipInternal`（推给**买家**）、`doRefund`。新 `SocialNoticeClient`（`contextId = "socialNoticePush"`）+ `OrderNotifier` 翻译层（best-effort，唯一 try/catch）。
- fulfilment 侧 7 处：售后立案 / 同意 / 拒绝 / 升级 / 运营裁定支持 / 运营裁定驳回，加**物流签收**（只推 `SIGNED`）。
- **一律 `@TransactionalEventListener(phase = AFTER_COMMIT)`**：事务没提交完就推，用户点进落地页会**查不到订单**。
- **★ 签收的推送判据是「本次真的写进去了」**：`ingest` 改为收集**真正 insert 成功**的节点行，重推被唯一索引挡掉时**不重复推**。
- **Feign 同 name 多客户端必须配唯一 `contextId`**：沿用 R53「启动即炸（`BeanDefinitionOverrideException`）」的教训，本次三个新客户端全部显式声明。
- **best-effort 旁路纪律**：通知写入失败只 `log.warn` / `log.error`，**绝不影响主流程** —— 消息 / 订单此时已落库，为附属通知把主流程判成失败，用户看到的「失败」与事实相反，会去重发（同 R54 `markWant`、同 `LogisticsNotifier`）。

**小程序**
- 新增 `utils/unread.js` —— **角标的唯一出口**：`NOTICE_TAB_INDEX`、60s 轮询（单例 `pollTimer` + 并发单飞 `inflight`）、`badgeText(count)`（0 → 清空、`>99 → '99+'`，**微信 tabBar 角标超过两位会被裁成省略号**）、**失败返回 `-1` 而不是 `0`**（避免「网络抖一下就把未读提示抹掉」）、未登录返回全 0 并清角标。
- **4 类刷新点**：`app.onShow`（冷启 + 回前台）、首页 / 分享圈 / 我的三个 tab 的 `onShow`、轮询；`app.onHide` 停轮询（**定时器必须清理**）。
- 新增 `utils/notice.js`（跳转与弹窗公共逻辑）+ `components/notice-toast`（顶部滑入卡片：`AUTO_HIDE_MS=4000`、`LEAVE_MS=260`、z-index **900** > 自绘导航栏 200、`detached` 清定时器；**同来源 5 分钟冷却**，key 取 `bizType:bizId`，退化 `notice:id`，最多记 50 条）。
- 通知页新增**分类 chip**（全部 / 系统 / 公告 / 好友 / 商家 / 订单）与**触底分页**（`pageSize` 默认 20），空态文案按分类变化。
- 顺带修 `api.ai.capabilities` **死代码**（`onLoad` 从未调用 → 服务不可用时用户敲完才失败）：不可用时 `aiReady=false`，提示**推进消息流**（**不做流内浮层条** —— 消息区高度是 `calc(100vh - …)` 算出来的，插块会把底部输入条挤出屏幕），输入框与发送按钮联动禁用。

#### 三、★ 实测发现并修掉的真实设计缺陷：一条私信被数两次
- **现象**：探针首跑时合并未读角标显示 **7**，实际只有 **5** 条未读。
- **根因**：私信到达会写一条 `bizType=MESSAGE` 的通知行（作为「有人找你」的列表入口），而**同一批私信又按条计入私信未读**（`t_social_message.read_at`）→ **一条消息被数成 2**。
- **修法**：`NoticeService.unreadCount` **排除** `bizType=MESSAGE` 的通知行，合并口径固定为「**通知（不含私信）+ 私信未读**」；该通知行**仍留在列表里**当引导。私信未读保持**按条计**（对齐主流 App 的「3 条未读」语义）。
- **★ 顺带发现的 SQL 坑**：`biz_type <> 'MESSAGE'` 对 `biz_type IS NULL` 的行返回 **NULL（即不匹配）**，而**全站公告的 `biz_type` 恰好是 NULL** —— 少写那个分支会把**整类公告漏掉**。故写成 `biz_type IS NULL OR biz_type <> 'MESSAGE'`。

#### 四、验证
- **新增** `scripts/r55-notice-smoke.py` **42 通过 / 0 失败**：A 内部端点凭据与契约 10（含无凭据 403、非法 `jump_page` 整批不写、批次上限）+ B 私信→通知 9 + C 合并口径 4 + D 已读幂等与越权 5 + E 订单通知 9 + F 物流签收 3。
- **新增** `scripts/r55-unread-badge-test.js` **59 通过 / 0 失败**：A 角标口径 17 + B 全局刷新点 12（含**全仓 `setTabBarBadge` 唯一出口扫描**）+ C 定时器生命周期 9 + D 卡片冷却 9 + E 跳转口径 4 + F 结构与胶囊 CSS 8。
- **静态规则** `scripts/check-miniprogram.js` 新增**第 8 条**（error 级）：全仓禁止绕过 `utils/unread.js` 直接调 `setTabBarBadge` / `removeTabBarBadge` → **0 错误 / 0 告警**。
- **回归 342 项全过**：`r50` 18 · `r49` 15 · `r53-hotfix-chat` 17 · `r54` 26 · `share-circle` 39 · `social` 43 · `r52` 57 · `r53` 28 · `r53b` 26 · `api-id-payload` 19 · `short-time` 12 · `flea-mine-filter` 42。三容器（social / trade / fulfilment）重建至 **healthy**。

#### 五、教训
1. **「功能已经上线」不等于「链路是通的」**：通知页 / 角标 / 已读 R50 就全做完了，唯一缺的是**生产者** —— 页面能打开、数据永远不变，是这类「半截功能」的典型症状。**排查时应先问「谁往里写」，而不是先看界面。**
2. **同一个事实有两套计数时，先假设它们会重复**：私信既是「消息未读」又触发「通知未读」，一上线就把角标翻倍。凡是新增冗余计数入口，都要显式指定「谁是权威口径」并写进注释。
3. **静态规则的匹配范围要跟着坑走**：规则 8 匹配 `setTabBarBadge` 时同样踩到「注释里同名」的假阴性 → 复用 `stripComments()` 先剥注释（R54 教训的第二次复用）。
4. **SQL 的 NULL 语义是隐形分支**：`<> 'X'` 天然漏掉 NULL 行，而 NULL 往往正是「默认 / 公告类」数据 —— 写否定条件时**必须显式想一遍 NULL**。

### 2026-09-29（R54 · 成色胶囊框过大 / 导航栏冗余通知入口 / 浏览与想要两个计数口径分开）

**用户真机验收报障三条**（附 2 张标注截图）：① 跳蚤市场成色筛选标签的胶囊背景框过大、没有贴合文字；② 小程序右上角残留一个通知图标，点进去是消息通知页，而导航栏里已经有通知按钮了；③ 「多少人想要」的统计口径不对 —— 用户给出明确口径：**「多少次浏览」= 页面每被打开或刷新一次即累计一次（同一用户多次查看可累加）**；**「多少人想要」= 只按咨询行为统计**（买家首次向卖家发咨询消息计入 1 人，同一买家后续再发不重复计数）。

#### 一、根因（逐条）
1. **胶囊框过大**：`.fm-chip` **没有显式高度、也没定 `line-height`**，高度由字号 + 内边距撑出；`.fm-chips` 是 `display:flex` 且位于 `scroll-view` 内 —— **flex 子项默认 `align-items: stretch`，会被拉伸到与容器等高**，于是背景框比文字高出一截、各排高矮不一；圆角大于半高时视觉上还会从胶囊退化成「胖椭圆」。**纯样式问题，无服务端参与。**
2. **冗余通知入口**：`components/nav-bar` 内置的右上角 💬（`nb-msg`）`navigateTo` 到 `/pages/notification/notification`，而该页**已经是 tabBar 的居中一项**（R50 重排后）—— 同一功能两个入口，用户会以为是两件事。
3. **计数口径混淆**：卡片只出参一个 `viewCount`，文案写作「N 人看过」—— 把**可累加的「次数」**说成**「人数」**；「想要」没有独立数据结构，因此也不存在「按买家去重」的口径。

#### 二、修复
- **胶囊（`flea-market.wxss`）**：`.fm-chips` 加 `align-items: center`（**关键：阻止子项按容器高拉伸**）；`.fm-chip` 改为**显式定高**（`height: 48rpx`）+ `line-height: 1`（**高度与字号解耦**，字号再变框不变）+ `flex: none`（不被压缩、不折行）+ `display:inline-flex; align-items:center; justify-content:center` + `padding: 0 20rpx` + `border-radius: 24rpx`（**恰好等于半高**）+ `white-space: nowrap`。
- **通知入口（`components/nav-bar` 三件套 + 三处 wxml）**：`nav-bar.wxml` 删除 `.nb-msg` 图标块（`nb-right` 只留 `<slot name="right">`）、`nav-bar.js` 删 `msg` 属性与 `goNotice()`、`nav-bar.wxss` 删 `.nb-msg` / `.nb-msg-hover` / `.nb-msg-icon`；`pages/chat`、`pages/login`、`pages/notification` 去掉 `msg="{{false}}"` 传参。**★ tabBar 通知入口必须保留** —— 删掉它会让通知页**再也进不去**，而且不会有任何编译错误。
- **两个计数口径（后端）**：
  - **DDL**：`t_listing` 加 `want_count INT NOT NULL DEFAULT 0`；新增去重表 `t_listing_want (listing_id, buyer_id)`，**主键就是 `(listing_id, buyer_id)`**（增量 `docs/sql/incremental/18-r54-listing-want.sql`，全量 DDL 同步）。
  - **★ 去重落在数据库唯一键**：应用层「先查有没有、没有再加一」在并发下会双写；`ListingMapper.insertWantIgnore`（`INSERT IGNORE`）**只有真插进去（返回 1）才 `incrementWantCount`**，天然幂等。`ListingService.markWant` 先校验闲置存在（去重表无外键，防孤儿行），**重复上报返回 `false` 而不报错**。
  - **新端点 `POST /api/listings/internal/{id}/want?buyerId=`**（内部凭据保护）。**刻意用 POST**：这是**写**（登记 + 计数），GET 会被预取 / 重试 / 爬虫无意触发，把计数刷歪且难归因。出参 `{"counted": true|false}`。
  - **social 域**：`MessageService.send()` 在 `messageMapper.insert` 之后，若 `bizType=LISTING` 则 best-effort 上报 —— **异常只 `log.warn`**（消息已落库、真实状态就是「已发送」，为附属统计把发送判成失败会让用户看到与真实状态相反的提示）。
  - **出参**：`ListingCard` / `ListingMineView` / `ListingOwnerView` 三处统一补 `wantCount`。
- **两个计数口径（前端）**：`flea-market` / `flea-detail` / `flea-mine` 三处文案改为「**N 次浏览**」+「**N 人想要**」并列；`flea-detail.json` 开 `enablePullDownRefresh` + `backgroundTextStyle: dark`，`flea-detail.js` 新增 `onPullDownRefresh()` 走**静默重拉**（不切加载态、不闪屏、无 id 不发请求）→ 让「**刷新也算一次浏览**」落地。

#### 三、验证
| 测试 | 结果 |
|---|---|
| `scripts/r54-listing-want-smoke.py`（新增） | **26 PASS / 0 FAIL**（W 想要 20 项：初始 0 / 首次咨询 +1 / **同一买家重复咨询仍为 1** / **同会话 TEXT 不计** / 不同买家各计一次 / 三处出参一致 / 无凭据 403 / `counted` 首次 true 重复 false / 不存在 404；V 浏览 4 项：**逐次 1→2→3 可累加** / 咨询不增浏览） |
| `scripts/r54-miniprogram-copy-test.js`（新增） | **23 PASS / 0 FAIL**（A 通知入口去重 9 项含 **tabBar 入口仍在**；B 计数文案 6 项；C 下拉刷新 8 项含刷新重新请求 / 静默不切态 / 无 id 不发请求） |
| `check-miniprogram.js` 规则 7 增补 3 条胶囊约定（error 级） | `.fm-chip` 显式 `height` + `line-height:1`、`.fm-chips` `align-items:center`；**「故意删掉」反向验证 3/3 命中** |
| 回归：`r53b` / `r53` / `r52` / `r53-hotfix-chat` / `api-id-payload` / `flea-mine-filter` / `short-time` | 26/26 · 28/28 · 57/57 · 17/17 · 19/19 · 42/42 · 12/12（Asia/Shanghai） |
| `check-miniprogram.js` + 编译 + 容器 + DDL | 0 错 0 警；product/social 编译 0 错、镜像 Built、双容器 healthy；`want_count int 0` + `t_listing_want` 主键 `(listing_id, buyer_id)` 校验通过 |

#### 四、教训
1. **★ 静态规则的假阴性比不检查更危险**：规则 7 首版用正则匹配 CSS 声明，**匹配到了注释里写出的同名声明** —— 我在 `.fm-chip` 注释里写「由 height 定高、line-height:1 让文字不额外撑行」、在 `.fm-chips` 注释里写「★ align-items:center 是必须的」，于是**故意删掉这几条真声明后检查依然报 0 错**，给出「已验证」的假象。修法：新增 `stripCssComments()`，**先剥 CSS 注释再匹配**；重跑反向验证，3 处全部命中。**任何「通过」的检查都必须在剥掉解释性文本之后仍然成立。**
2. **两个口径绝不能混用**：浏览量可累加、想要人数按人去重 —— 混用会让卖家把「路过看一眼」当成购买意向。凡是给人看的计数，必须先明确**「计数单位是次数还是人」**。
3. **去重必须靠数据库唯一键**：应用层判断在并发下不可靠；`INSERT IGNORE` + 主键去重键 + 「只在返回 1 时 +1」是唯一稳妥写法，顺带天然幂等。
4. **入口去重不是「全删」**：两个入口指向同一功能时要判断**哪个是导航骨架、哪个是残留**；删错一个会让页面彻底进不去，且没有任何编译期报错 —— 必须有页面级断言（如「tabBar 通知项仍在」）。
5. **`scroll-view` 内 flex 子项的默认行为要显式覆盖**：不写 `align-items` 就会被拉伸；胶囊类元素四条齐备才稳定（显式 `height` + `line-height:1` + `border-radius = height/2` + 容器 `align-items:center`）。

### 2026-09-29（R53 热修二 · 按钮折行 / 浏览量恒 0 / 闲置状态重复显示）

**用户真机验收报障三条**：① 按钮文字长了会折行、尺寸忽大忽小，我的闲置页尤其差（附 2 张标注截图）；② 闲置商品显示「0 人看过」，用户查看后仍是 0；③ 同一件闲置同时出现在「审核中」和「在售」两个标签下（用户明确口径：**一个商品只属于一个状态**）。

#### 一、根因（逐条）
1. **按钮折行**：小程序 `button` 组件**自带 28rpx/侧内边距**，与 `flex:1` 一起工作时文字框被挤窄 → 「加入购物车」「立即购买」折两行；我的闲置筛选条 6 个标签无 `flex:none`，被容器压缩后「审核中/审核失败/已下架/已售出」全部折行 —— **行数不同即行高不同**，这就是「忽大忽小、与文字不协调」的来源。纯样式问题，无服务端参与。
2. **浏览量恒 0**：全仓搜索 `incrementView|view_count = view_count|viewCount + 1` 在 `duoduo-*/src/main/` **结果为空** —— `t_listing.view_count` 只有字段定义、DTO 出参与种子数据，**从来没有任何写入代码**。
3. **状态重复显示**：服务端 `status`（在售/下架/已售）与 `auditStatus`（待审/通过/驳回）**正交两维**（R53 原设计如此），新发布条目天然是 `ON_SALE + PENDING`；而小程序「在售」标签**只传 `status=ON_SALE`、漏了 `auditStatus=APPROVED`** → 待审条目被同时捞进「在售」。**服务端行为一直正确，缺陷在前端拼参数。**

#### 二、修复
- **按钮（`app.wxss` + 4 个页面 wxss）**：`app.wxss` 的 `.btn`/`.btn-mini` 加 `white-space:nowrap` 作全局兜底；`product.wxss` 底部四按钮显式定尺寸（`.footer-share` 定宽 `120rpx`；`.footer-btn` `height:76rpx` + `padding:0 12rpx`）；`flea-detail.wxss` 购买按钮 `flex:none` + `padding:0 28rpx`；`flea-mine.wxss` 筛选条改**不换行 + 横向滚动**（`overflow-x:auto`，成员 `flex:none` + `height:56rpx`），徽章/操作按钮/时间同加 `flex:none` + `nowrap`，操作区 `flex-wrap:wrap`。**取舍：长文案不再靠折行容纳，而是挤不下就横向滚动 —— 保证所有按钮同一高度。**
- **浏览量（`ListingMapper` + `ListingService`）**：mapper 新增**数据库侧原子自增** `@Update("UPDATE t_listing SET view_count = view_count + 1 WHERE id = #{id}")`（读-改-写在并发下会丢更新）；`ListingService.detail(Long)` 在**可见性校验之后、装配返回之前**自增并把新值回填本次响应（**首次查看即显示 1**，不必等第二次）。两处刻意取舍：只在公开详情 `GET /api/listings/{id}` 计数，`ownerDetail()` 不计（卖家自己看不该刷量）；自增异常只 `log.warn` 不阻断浏览。前端 `flea-market.js onTapItem` 置 `_needRefresh`，返回列表 `onShow` 重拉（否则看完回来仍是旧值，会以为统计没生效）。
- **状态互斥（`flea-mine.js` / `.wxml`）**：新增 `FILTER_PARAMS` 构成**不重不漏**划分 —— 审核两标签**只带 `auditStatus` 不带 `status`**（否则会漏掉被驳回时服务端同步落的 `OFF_SHELF` 件），交易三标签**必须同时带 `auditStatus=APPROVED`**；`statusOf()`/`decorate()` 把徽章改为**单一状态**（审核态优先：未过审显示「审核中」而非「在售」），上架/下架/重提三个按钮权限从同一对字段推导（杜绝「徽章说在售、却给了上架按钮」），`onToggleStatus` 就地更新改为重走 `decorate`；wxml 徽章由两个 `<text>` 收成一个。**服务端无需改动。**

#### 三、验证
| 测试 | 结果 |
|---|---|
| `scripts/r53b-listing-view-status-smoke.py`（新增） | **26 PASS / 0 FAIL**（V 浏览量 10 项 + S 状态互斥 16 项，含反例固定） |
| `scripts/flea-mine-filter-test.js`（新增） | **42 PASS / 0 FAIL**（真实加载 flea-mine.js、桩 wx.request 断言 5 标签实际查询串 + 徽章单一性 + 按钮权限 + 就地更新） |
| `check-miniprogram.js` 规则 7（新增，error 级） | 按钮禁换行 + 横向成员 `flex:none`；**已用「故意改坏」反向验证 —— 6 处全部命中** |
| 回归：`r53-listing-detail-mine` / `r52-listing-preview` / `r53-hotfix-chat` | 28/28 · 57/57 · 17/17 |
| `api-id-payload-test.js` / `short-time-test.js` / `check-miniprogram.js` | 19/19 · 12/12 · 0 错 0 警 |

#### 四、教训
1. **纯样式缺陷也能被静态规则守住**：`white-space:nowrap` + `flex:none` 这类「一句话约定」一旦被后续改动抹掉就会复发，且 JS 侧完全测不到 —— 写成 error 级静态规则最划算。新增规则必须做**反向验证**（故意改坏能报错），否则可能是空转规则。
2. **接口探针测不到「前端拼参数」**：问题③ 服务端一直正确，探针永远绿；必须有直接执行前端代码的单测（`flea-mine-filter-test.js` 桩 `wx.request` 断言实际 URL）。
3. **「有字段、有出参、没写入」是最隐蔽的一类缺陷**：搜代码（不是搜文档、不是看表结构）才能确认「从来没人给它加过 1」。

### 2026-09-29（R53 热修 · 聊天消息发不出去 404 + 消息时间差 8 小时）

**用户真机验收报障三条**：① 官方客服会话里已发消息/新消息提示「内容已删除」；② 跳蚤市场私信（已关注对方）任何消息都发不出去，控制台 `POST /api/socials/messages 404`；③ 北京时间 12:33 发出的消息显示 04:33（差 8 小时）。

#### 一、定位（全部实测确认，非推测）
1. 查库：最新一条 `created_at = 2026-09-29 04:33:46`（北京 12:33 发出）→ **落库值就是 UTC**；`docker exec` 确认 MySQL 与 JVM 容器 **TZ 均为 UTC**。
2. 临时探针复现：`receiverId` 传「精度丢失的数字」→ **404「用户不存在」**；传完整字符串 → **200**。`Number('710000000000000001') === 710000000000000000`（末位被吞）。
3. 锁定 `miniprogram/utils/api.js` `message.send` 的四处 `Number()` 强转（receiverId / bizId / productId / listingId）。
4. 时间侧：后端下发 `createdAt=2026-09-29T04:38:19Z`（**带 Z，时区声明明确**），而 `shortTime` 主动 `replace('Z','')` 后裸切片 → 展示 UTC 挂钟时间。

#### 二、根因
- **①② 同源**：雪花 id 18~19 位 > JS Number 53 位有效精度 → 前端强转丢位 → 服务端查无此人 404 → 前端把 `NOT_FOUND` 文案化成「内容不存在或已删除」。**被掩盖的原因**：ASK/LISTING 首条由服务端按商品/闲置归属定收件人，忽略了前端传的 receiverId；只有落到 TEXT（沿用前端 receiverId）才必然 404 → 这解释了「之前能发、现在发不出」。
- **③**：后端契约本来就是 UTC + `Z`，前端把时区标记删掉再按字面展示 → 全站 13 个页面统一少 8 小时（`shortTime` 被聊天/会话列表/订单详情/优惠券/秒杀/分享圈/通知/关注列表/售后/我的闲置共用）。

#### 三、修复
- `api.js message.send`：四个 id 字段一律 `String(...)` 原样上行（后端 `Long` 对数字串自动转换）；缺省字段不上行（避免把 `"undefined"` 当 id）。
- `api.js shortTime`：重写为「按 UTC 解析 → 转**设备本地时区**展示」；手写逐段解析（不用 `new Date(str)`）以绕开 iOS JavaScriptCore 对 Backend 纳秒精度 ISO 串的解析差异；纯日期/非法串原样返回。
- `api.js share.publish` + `pages/share-detail`：`spuId` 同族隐患一并改为字符串。
- `pages/chat/chat.wxml`：空态文案按上下文区分（闲置咨询 / 商品咨询）。
- **防复发**：`scripts/check-miniprogram.js` 新增规则 6（**error 级**）—— 禁止对 `xxxId` 做 `Number()` 强转。

#### 四、验证
| 测试 | 结果 |
|---|---|
| `scripts/r53-hotfix-chat-smoke.py`（新增） | **17 PASS / 0 FAIL**（A 咨询会话收发 + B 闲置咨询收发 + C 时间契约，含失败模式固定） |
| `scripts/api-id-payload-test.js`（新增） | **19 PASS / 0 FAIL**（真实加载 api.js、桩 wx.request 断言出参 id 为字符串） |
| `scripts/short-time-test.js`（新增） | **12 PASS（Asia/Shanghai）/ 10 PASS（UTC）** |
| `r53-listing-detail-mine-smoke.py` / `r52-listing-preview-smoke.py`（回归） | 28/28 · 57/57 |
| `check-miniprogram.js` | 0 错误 / 0 告警 |

#### 五、教训（已写入项目长期笔记）
1. **探针只测后端 = 假绿**：凡是「前端拼装请求体」的契约（id 类型、时区语义），必须有一条**直接执行前端代码**的单测 —— Python 传的 id 天生是字符串，永远测不到 `Number()` 强转。
2. 服务端「不信前端、自己定人」的容错会**掩盖**前端缺陷，只在特定路径暴露 —— 排查要找「能让缺陷现形的那条路径」。
3. 雪花 id 有约 **1/64** 概率恰为 64 的倍数（有效位 ≤53，双精度可精确表示、`Number()` 不丢位）→ 复现用例必须按前置条件分支，否则会写出「有时绿有时红」的假测试。

### 2026-09-28（第五十三轮 · 详情页卖家卡 + 购买按钮过渡态 + 我的闲置审核态体系）

**三项**：① 闲置详情页卖家信息卡 + 「我想要」（LISTING 问一问全链路）；② 详情页「立即购买」过渡态；③ 我的闲置审核态体系（驳回→修改→重提闭环）。

#### 一、详情页卖家卡与 LISTING 问一问（#127）
- **跨域事实源**：product 域新增 `GET /api/listings/internal/cards`（`InternalListingController`，InternalTokenFilter 保护，`InternalListingView` 含 `sellerId`）；social 域新增 `ListingClient`（Feign，`path=/api/listings`）+ `ListingCardBrief` 契约副本。
- **消息链路**：`SendMessageRequest` 加 `listingId`（与 `productId` 互斥，同带 400）；`bizType=LISTING` 收件人=服务端按 `listing.sellerId` 定（不信前端），自己问自己 422、幽灵闲置 404；咨询会话豁免「非互关一条」；会话键上下文段 `lo:hi:l{listingId}`（与商品段 `:p{spuId}` 平行）。
- **★ ConvContext record**：统一会话上下文段拼装（none/product/listing 三态 + `suffix()` + `present()`），替换原 `Long contextProduct` 与 `productOfKey` 双实现；`conversations()`/`conversation()` 双通道批量取卡（商品+闲置），LISTING 卡 `type=LISTING`。
- **★ Feign contextId 冲突（启动即炸的坑）**：`ListingClient` 与 `ProductClient` 同 `@FeignClient(name="duoduo-product")` → 同名 `FeignClientSpecification` 注册冲突 → social 容器 unhealthy。修法：两 client 各配唯一 `contextId`。**教训：同一服务挂多个同 name Feign 接口，必须 contextId 区分；容器 unhealthy 要第一时间 `docker logs` 看启动日志而不是猜。**
- **★ 前端 401 副作用防护**：卖家卡复用 `api.social.follow.profile(sellerId)` 公开端点聚合（零新增契约），但免登访客请求它会 401 → 清登录态 → 触发静默重登/跳登录。修法：`loadSeller` 无令牌不发请求，直接占位「卖家」。
- 小程序：`flea-detail` 重写（卖家卡 + 底部「立即购买/我想要」双按钮；owner 视角引导去我的闲置）；`chat`/`chat-list` 支持闲置卡与双上下文。

#### 二、购买按钮过渡态（#128）
弹「担保交易即将上线」modal 引导问一问，不做假下单。**完整担保交易属 Batch-3，前置条件：核实微信虚拟支付/类目资质**（设计稿 §12-6）。已登记「未开始」任务清单。

#### 三、我的闲置审核态体系（#129）
- `mine/list` 加 `auditStatus` 白名单参数（可与 `status` 正交组合）；**`audit()` 驳回时同步落 `OFF_SHELF`**。
- 新增 `GET /api/listings/mine/{id}`（owner 详情，`ListingOwnerView`；`requireOwned` 统一收口，404/403 刻意区分）与 `POST /api/listings/{id}/resubmit`（仅 REJECTED；发布/重提共用 `normalizeForWrite()` 闸门：实名+内容校验+违禁词复检）。
- 小程序：`flea-mine` 筛选 6 项（全部/审核中/在售/审核失败/已下架/已售）+ 重提按钮；`flea-publish` 编辑模式。

**验证**：`scripts/r53-listing-detail-mine-smoke.py` **28/28 PASS**（A5 + M8 + R15，运营真实审核链）+ R52 回归 57/57 + 编译 0 错 + 静态自检 0 错 + 双容器 healthy。运营账号 `13688774031` 密码哈希已同步为测试密码（历史种子密码不同导致登录失败，已修）。

### 2026-09-28（第四十九轮 · 问一问会话按商品隔离——同卖家多商品不串线）

> 用户 bug：官方客服卖多件商品，在商品 A「问一问」发「你好」后，再从商品 B「问一问」——新打开的会话仍显示商品 A 的历史。预期每件商品独立会话。

- **根因**：R47 会话键 `conversation_id = "lo:hi"` 只按**用户对**拼装（MessageService#conversationId），ASK 咨询也落同一键 —— 同一买家对同一卖家（尤其官方客服一个人扛 13 个自营商品）的所有咨询必然混在一个会话里。这是 R47 模型遗留缺陷，数据无损坏。
- **修复方案（会话键带商品段）**：`lo:hi` → 商品咨询场景扩展为 **`lo:hi:p{spuId}`**（VARCHAR(64) 实测最长 60，够用）：
  - `send()`：ASK 恒取 bizId 作商品段；**TEXT 请求体新增可选 `productId`**（服务端校验商品存在 404），带则落该商品咨询会话，不带落主会话 —— 会话键由服务端拼装，前端无法伪造到任意会话（lo:hi 由登录态+服务端定人保证包含自己）；
  - **豁免规则细化**：商品咨询会话整体豁免「非互关一条」，主会话（互关私聊/商品卡分享）语义不变（㉓-2 用例锁死）；`countAskInConversation` 保留兼容 R47 旧数据；
  - `conversations()`：解析会话键商品段，批量取商品卡，`ConversationView` 尾部新增 `productId/productTitle/productImage`（失败降级 null 不挡列表）；
  - `conversation()/markRead()`：签名加可选 productId，thread 按同规则拼键读取。
- **前端适配**：chat 页整段对话（ASK 首条 + 后续 TEXT）都带 productId，进/读/发/已读全落商品会话；chat-list `wx:key` 从 peerId 改为 `peerId-productId` 会话级唯一（同一 peer 现在可有 1+N 个会话），goChat 透传 productId，列表项新增 🛒 商品副标题。
- **兼容性**：无 DDL 变更（键是 VARCHAR 值，变长即可）；R47 旧会话保持原样不迁移（开发环境历史无价值），新咨询自动按商品分。
- **验证**：新探针 `scripts/r49-ask-thread-smoke.py` **15/15**（两商品两会话独立、互不串线、主会话纯净、列表带商品标题、自由聊豁免、已读按会话生效）+ `social-smoke.py` **43/43**（㉓ 改版：商品会话豁免+主会话仍受限；㉗ 频控带 productId）+ `r48-ask-smoke.py` 8/8 + `share-circle-smoke.py` 39/39 + 静态自检 0 错误。social 容器重建健康。

### 2026-09-27（第四十八轮 · 问一问全站打通 + 私信入口上移 + 分享圈设计稿）

> 用户三需求：① 私信入口从「我的」底部菜单移到右上角（短信/通知图标样式）；② 修复「问一问」报「该商品没有卖家」，生成完整卖家测试数据并验证聊天；③ 分享圈设计需求文档（商品分享+评论、跳蚤市场 C2C，对标闲鱼）。

- **② 根因判定（不是数据丢了，是设计现状）**：全库 18 个 SPU 中 13 个 `supply_type='self'` 自营商品 `seller_id=0`，R47 设计即「无归属 → 422 该商品暂无卖家」（冒烟脚本 ⑳ 还把 422 当预期断言）；merchant 5 个早已回填无恙。用户点自营商品必报错。
- **② 修复方案（后端零改动）**：新建「多多官方客服」账号（增量 16，id 保留段 `710000000000000001`，手机号 `13900000002`，密码与测试账号同源 hash），13 个自营商品按手机号定位批量归属 → 「问一问」语义变为咨询官方客服，复用全部私信链路与服务端定人纪律。同时给两个种子卖家补包内头像（`images/avatars/seller-*.svg`），官方客服带头像（cs-official.svg，猫脸+耳麦）。
- **② 踩坑两枚**：①手机号校验正则 `^1[3-9]\d{9}$`，`10000000001`（第二位 0）与被历史测试占用的 `13900000001` 都不可用 → 落定 `13900000002`；②bcrypt hash 不能抄种子卖家（密码不明），正确姿势是**现场注册一次性账号取系统自产 hash** 再赋给官方客服，源号即删。
- **① 实现**：`nav-bar` 组件开启 `multipleSlots`，新增右侧命名插槽（胶囊左侧定位：`windowWidth - rect.left + 8` 实算，兜底 96px）；「我的」页右上角 📩 图标（slot 内 tap 走页面 `go`），底部 💬私信菜单移除。
- **③ 设计稿**：`docs/design/分享圈设计需求文档.md`——评论两级模型（`t_share_comment`）+ 集市 C2C 全链路（复用 `t_listing` 审核流与 `t_settlement` 担保 CAS，新增 LISTING 消息类型与 C2C 订单），四批次实施 + 合规红线（tabBar 5 项上限、iOS 虚拟支付规避、类目资质待核实）。
- **验证**：新探针 `scripts/r48-ask-smoke.py` **8/8**（自营 ASK 200 且收件人=官方客服、客服登录/回复/会话/未读、商家回归）+ R47 回归 `social-smoke.py` **42/42**（⑳ 用例改版：自营 422→200 官方客服）+ 静态自检 0 错误；后端零改动、容器无需重建，SQL 16 已落库。

### 2026-09-27（第四十七轮 · 第一批：社交域上线——关注 / 私信 / 问一问）

> 用户需求：① 修复分享好友问题；② 关注功能（说明存储方案）；③ 私信：互关自由聊、未互关仅一条；④ 站内分享（个人卡/商品/分享圈内容）给已关注用户；⑤ 消息通知（小铃铛 + 分类 + 订单通知）；⑥ 商品详情「问一问」直咨询卖家（无需互关）。经确认四项决策：**独立 duoduo-social 域 / HTTP 轮询 / 站内通知中心 / 分两批交付**。本批交付 ①②③⑥ + 前端；④⑤（通知中心 + 站内卡片）第二批。

- **新服务 `duoduo-social`（:8087，库 `duoduo_social`，雪花 worker-id 6）**：通知需要跨域聚合（交易/履约/私信/公告），聊天高频读写不应拖累 user 域登录链路，故独立成域。
- **数据模型**（`docs/sql/06-duoduo-social.sql`，通知表一次建齐）：
  - `t_social_follow` 复合主键 `(follower_id, followee_id)`：**互相关注 = 两个方向各一行**，一条 COUNT 判定，不引入状态列（双向同步是 bug 温床）；重复关注/取关幂等；计数不冗余直接 COUNT。
  - `t_social_message`：**会话键 `"lo:hi"`**（双方 userId 小者在前）——双向聊天一个索引搞定；`sender_role` 发送时点快照（第二批通知分流好友/商家消息用）；`biz_type`（TEXT/PRODUCT/SHARE/PROFILE_CARD/ASK）+ `biz_id` + `read_at`。
  - `t_social_notice` / `t_social_notice_read`（第二批启用）。
- **规则集中服务端**（前端只消费结果，避免两端规则漂移）：
  - **非互关每人限发一条**（按会话计数）；**咨询会话（出现 ASK）永久豁免**，否则问一问不可用；
  - **ASK 收件人 = 商品归属卖家**（经商品域内部卡取 `sellerId`），不信前端传的 receiverId；自营（sellerId=0）422、自问 422、幽灵商品 404；
  - **卡片摘要服务端取事实**：请求体只带 `bizType+bizId`，展示字段由服务端去事实源现取（与分享圈同一纪律）；SHARE/PROFILE_CARD 第二批开放前 422；
  - **频控**：同一会话 60s/10 条（429），咨询会话同样生效；
  - `canSend` 服务端算好下发，前端置灰 + 文案。
- **发现并修复的数据缺口**：种子 SPU 的 `seller_id` 全为 0（INSERT 未含该列，merchant 类型也 0）→ 问一问无处可发。增量 `15-ask-seller-backfill.sql` 把 1009/1010/1015/1017/1018 按手机号定位回填到种子 SELLER（跨库子查询，不硬编码雪花 ID）；02 种子文件尾部同口径补写。
- **踩坑两枚（都已根治并记入代码注释）**：
  1. Feign 服务名调用必须 `spring-cloud-starter-loadbalancer`（缺失启动即抛 No Feign Client for loadBalancing）；
  2. 内部接口必须挂 `X-Internal-Token` 拦截器（`InternalCallConfig`）——下游「取不到资料就降级为空」的容错会把 403 吞成业务 404「用户不存在」，极难排查；另 `Map.of()` 对 `get(null)` 抛 NPE，TEXT 消息 `bizId=null` 查卡前必须判空。
- **小程序（4 新页，全部非 tab + 原生导航）**：`user-profile`（他人主页聚合 profile 接口：资料+计数+关系一次拿齐；关系字段名 `viewerFollowing/viewerFollowedBy` 避开 record 组件与 following 计数重名）、`follow-list`（我关注的/我的粉丝双 tab + 互关角标 + 私信直达）、`chat-list`（未读徽标 + canSend 文案 + consulting 标签）、`chat`（气泡流 + 4s 轮询 + 卡片消息跳详情 + 进入即标已读 + 非互关禁言提示；问一问入口 `?peerId=&productId=&ask=1` 首条按 ASK 发）。商品 footer 加「问一问」（requireLogin；自营商品提示不支持）；分享圈作者区 catchtap → 他人主页（requireLogin）；「我的」加 💬私信 / 👥关注与粉丝（登录可见）。
- **验证**：`social-smoke.py` **42/42**（关注 12：401/自转 422/幽灵 404/幂等/互关/列表/计数/主页聚合；私信 30：401/一条限制/回复/互关解锁/卡片类型 422/ASK 定人·豁免·404/空内容/自聊 422/频控 429/未读/正序/已读）+ 分享圈探针回归 **39/39** + 静态自检 0 错误 + wxml↔js 处理器核对全过；social/gateway/user/product 四容器重建 healthy（user 补 `/internal/roles`、product 内部卡补 `sellerId`）。
- **第二批（待办）**：通知中心接口 + trade/fulfilment Feign 推送（下单/付款/退款/售后/物流，旁路降级）+ fulfilment 分享单内部端点 + 小程序通知页与铃铛入口 + 解锁 SHARE/PROFILE_CARD 站内分享。

### 2026-09-27（第四十六轮 · 分享圈转发落库 + 转发身份修正）

> 用户报两个问题：① 转发他人分享后，转发动态未出现在分享圈列表；② 转发后查看分享详情页，仍显示原发帖人自己的分享页。

- **根因（两问同源）**：第四十四轮的「转发」只是 `open-type="share"` 调起微信原生转发面板，**从不写后端** —— 所以①列表里不会出现新动态（压根没提交），②卡片落点 `path` 带的是**原分享单 ID**，详情页只能按原发帖人渲染。数据模型里也没有「转发」这一层（`t_product_share` 只有发布人，无 parent）。
- **修复（把「转发」建成数据模型里的一层）**：
  1. `t_product_share` 增 `parent_share_id`（NULL=原创）+ 唯一键 `uk_share_user_parent (user_id, parent_share_id)` + `idx_share_parent`（增量 `docs/sql/incremental/14-share-repost.sql`，全量 `schema.sql` 与 `04-duoduo-fulfilment.sql` 同步）；
  2. `POST /api/shares/{id}/repost`（需登录）：插一条**以转发人为作者**的新分享行；商品快照**直接沿用原帖**（转发引用的是原帖内容，重查商品域会让同一内容两处快照不一致，还白担一次跨域抖动）；拒绝自转（422）、重复转发（409，唯一键兜并发窗口）、幽灵原帖（404）；
  3. `ShareView` 增 `userId` 与转发四字段 `shareType/parentId/parentNickname/parentContent`；明确 `userId/nickname/avatar` 恒是「本条分享的作者」——转发时即转发人本人；
  4. 原帖引用按新 `loadContext` **一次批量取**（一页 10 条转发 = 1 次原帖查询 + 1 次昵称查询，无 N+1）。
- **前端**：分享圈「转发」按钮不再直接开微信面板，改为**转发弹层**（附言 + 「转发到分享圈」走写接口 + 「分享给好友」走原生、落点用**转发单 ID**）；信息流与详情页新增「转发自 @原作者」+ 原评价引用块；转发成功后刷新列表（我的转发出现在信息流最前）；详情页转发后 `redirectTo` 转发单详情，一眼看到身份是「我」。
- **验证**：后端探针 **39/39**（新增 ⑪未登录转发 401 / ⑫作者=转发人+快照沿用+原帖引用 / ⑬信息流出现转发动态 / ⑭转发单详情身份=转发人且原帖仍为原创 / ⑮重复 409 / ⑯自转 422 / ⑰幽灵 404 / ⑱空附言纯转发 200）；前端 stub **16/16**（弹层流程、落点一次性消费、未登录拦截、409 文案透出、详情页 redirectTo）；`node --check` 3 个改动 js + 小程序静态自检 0 错误 0 告警；SQL 14 已落库并校验（列 + 唯一键 + 索引）；fulfilment 与 gateway 容器重建后健康。
- **教训**：凡「用户可见的社交动作」都必须先在服务端有**事实行** —— 只调起微信原生能力等于把动作留在客户端，列表与详情永远对不上。

### 2026-09-27（第四十五轮 · 模拟器启动失败热修：tabBar 超 5 项）

> 用户报模拟器启动失败，截图报错：`app.json: ["tabBar"]["list"] 不能超过 5 项`。

- **根因**：微信 tabBar **硬上限 5 项**——第四十四轮把分享圈加进 tabBar 后成 6 项，`app.json` 静态校验不过，开发者工具直接拒绝启动模拟器（与环境配置/依赖/端口/资源无关）。
- **修复**：① tabBar 收敛为 **首页/分类/分享圈/购物车/我的**（订单 tab 移除，「我的 → 我的订单」入口本就存在）；② orders 回归普通页面并**恢复原生导航栏**（非 tab 页推进式打开需要返回键；R41 的自绘栏改造随之还原：json 去 `navigationStyle: custom`、wxml 去 nav-bar 与 `navTop` 吸顶偏移、js 去 `getNavMetrics`）；③ **switchTab 收口**——`switchTab` 对非 tab 页静默失败，cart 结算后 / after-sales 指路 / checkout 支付后 / seckill 下单后四处改 `navigateTo|redirectTo`；④ index `TAB_PAGES` 与 profile `tabPages` 清单同步（orders 出、share-circle 入）。
- **验证**：app.json 合法且 `tabBar.length === 5`、tab 页全部注册；7 个改动 js `node --check` 全过；`switchTab.*orders` 残留清零；静态自检 0 错误 0 告警。提交 `e064e79` 已推送并验证远端。
- **教训**：往 tabBar 加第 6 个页面前必须先减一个；tab 成员变更必须全局排查 `switchTab` 与各页自维护的 tab 清单。

### 2026-09-26（第四十四轮 · 分享静默登录根治 + 分享圈升一级 tab + 点赞/收藏/转发）

> 用户两点需求：① 未登录仍可触发分享且点分享会自动静默登录到不明账号，要求拦截并引导主动登录；② 分享圈从「我的」移到导航栏作一级页面，定位公共空间，支持点赞/收藏/转发。

- **Bug 根治（静默登录穿透）**。链路复盘：分享按钮 → `ensureLogin()` → `ensureSession` 失败 → `silentRelogin()` → `refreshSession` 失败 → `api.wxLogin()`——微信环境 `wx.login` 无需用户点击，code2Session 会**无感创建或登录到微信绑定的账号**，于是"未登录"点分享反而变成了登录到某个不明账号、分享面板照常弹出。修复：`app.js` 新增 `requireLogin()`——探测失败**一律直接去登录页**（`openLogin`，登录成功经 `finishLogin` 回跳兑现等待方），全程不触碰静默链；分享面板入口、分享圈点赞/收藏全部改用它。`ensureLogin` 的静默链保留给加购/领券等本机动作（无感恢复是那里的特性）。
- **分享圈升一级 tab（第四十四轮）**。tabBar 第 6 项（首页/分类/**分享圈**/购物车/订单/我的），双态图标 `share.png/share-on.png` 由 `gen-miniprogram-images.py` 新增 `tab_share`（对话气泡 + 三圈友点）生成；页面改 `navigationStyle: custom` + `nav-bar` 自绘导航（R41 教训：tab 页原生导航栏切页被重建）+ 自绘下拉刷新指示器（`loading="{{refreshing}}"`，index 同款）；「我的」页原入口移除；`product` 面板与 `share-detail` 的跳转改 `wx.switchTab`（navigateTo 对 tab 页无效）。
- **互动能力（点赞 / 收藏 / 转发）**。后端：`t_share_like`/`t_share_favorite` 两张同构表（`(share_id, user_id)` 复合主键 = 唯一约束，并发撞键幂等）+ `t_product_share` 冗余计数列（信息流高频读不逐行 COUNT；回退 `GREATEST(0, n-1)` 防负漂移）；`POST /api/shares/{id}/like|favorite` toggle（需登录；两段路径天然不在 `/api/shares/*` 白名单内，仍由网关强制登录）；`ShareView` 增 `likeCount/favoriteCount` 与浏览者标记 `liked/favorited`（公开端点可选身份：带令牌透传 X-User-* 则回填，匿名恒 false）；批量取标记集合（`IN` 一次查回，不逐行查）。序列化：`likeCount/favoriteCount/count` 补进 `LongToStringJacksonModule` 数值白名单——计数被当标识转成字符串会让前端 `===`/算术退化（T-40 同类缺陷，顺手根治）。小程序：卡片互动条 ❤️/⭐（catchtap 防冒泡；toggle 后按响应回填状态与计数 + toast）/ 🔁转发按钮（`open-type="share"` + `markForward` 先记条目 → `onShareAppMessage` 落点 `share-detail?id=`，标题带分享者与评价；页面级转发落分享圈 tab）。
- **验证**：后端探针 `share-circle-smoke.py` **20/20**（新增：未登录点赞 401 / 点赞 toggle 来回 / 登录浏览 `liked=true` / 匿名浏览 `liked=false` 且计数可见 / 收藏 toggle / 幽灵分享点赞 404 / 计数为 JSON 数字）；小程序 stub **12/12**（B1 未登录点分享去登录页且**全程零次 wx.login** / B2 主动登录后兑现 / B3 已登录直通 / C1-C7 信息流互动与转发落点）；静态自检 0 错误 0 告警；fulfilment 容器重建 healthy；增量 SQL `13-share-engagement.sql` 已在运行库执行。

### 2026-09-26（第四十三轮 · 分享两 Bug 修复 + 分享圈新功能）

> 用户三点需求：① 未登录用户也能执行分享，需登录态校验 + 引导登录；② 分享后点「查看分享页」无响应（页面只刷新停留在原商品页）；③ 参考拼多多「拼小圈」新增分享动态页（信息流展示用户商品分享，每条带用户自撰评价）。指令：先修 Bug 再做新功能。

- **Bug1 分享登录校验**。根因：详情页底部「分享」按钮直接 `open-type="share"`，微信转发面板**只能由用户点按 open-type 按钮触发**（程序化调不起），按钮点击前没有任何代码介入机会。修法：改普通按钮 `bindtap="onShareTap"` 过登录门——未登录 `ensureLogin()` 引导登录页；已登录弹出**分享面板**，面板里的「转发给好友」才是真正的 `open-type="share"` 按钮（登录校验做在"进入分享流程"这道门上，面板天然只对已登录用户可达）。
- **Bug2 查看分享页无响应**。根因：`onShareAppMessage` 的 `path` 就是商品详情页自身，devtools 点开分享卡片 = 重新打开同一个页面，看起来"只刷新、无反应"。修法：新建独立落地页 `pages/share-detail`，两种 query 入口——`?spuId=`（好友直转，展示"好友向你分享了这个商品" + 商品卡）与 `?id=`（分享单，展示分享者昵称/头像 + 自撰评价 + 商品快照卡）；两处都给「查看商品」（跳详情）/「逛逛分享圈」按钮；落地页自身也可再转发（分享单转 `?id=`、直转继续转 `?spuId=`）。
- **新功能分享圈（后端）**。`duoduo-fulfilment` 新增 `t_product_share`（雪花 ID，商品快照四列 + content ≤500 字 + created/updated；增量 `12-share-circle.sql`，全量 04 与 `schema.sql` 源文件同步）。**快照以商品域事实为准**：新增商品域内部接口 `GET /api/products/internal/cards`（InternalTokenFilter 保护，只回 id/标题/主图/最低价四字段），发布时履约域经 Feign `ProductClient` 取快照落库——请求体只带 `spuId + content`，杜绝"9.9 元猫粮卡片贴在任意商品 id 上"的伪造面；商品不存在 404、商品域不可用则拒绝发布（写操作不静默降级成空白卡）。三端点：`POST /api/shares`（需登录，`UserContext.requireUserId()`）/ `GET /api/shares/feed`（公开，按时间倒序分页）/ `GET /api/shares/{id}`（公开）；昵称头像复用 `loadBriefs` 批量补全 + 用户服务不可用降级「用户 {id}」。**网关**：fulfilment 路由 Path 补 `/api/shares/**`；白名单只放 `/api/shares/feed` 与 `/api/shares/*` 两条只读路径并留注释——白名单按路径匹配不区分方法，feed 刻意用两段路径与发布路径（单段）分开，绝不能写 `/api/shares` 或 `/api/shares/**`。
- **新功能分享圈（小程序）**。详情页分享面板（底部弹层：评价 textarea 500 字 + 「转发给好友」/「发布到分享圈」两路 + 「先去分享圈逛逛」链接 + 遮罩关闭）；`api.js` 新增 `share` 命名空间（publish/feed/detail）；新建 `pages/share-circle` 信息流页（卡片 = 昵称/头像 + 时间 + 评价 + 商品快照卡；点商品卡 catchtap 跳详情、点整条跳 share-detail；下拉刷新 + 触底分页 noMore 判定；公开可逛无需登录）；「我的」页新增「💬 分享圈」入口。app.json 16 → 18 页。
- **验证**：后端探针 `scripts/share-circle-smoke.py` **12/12**（未登录发布 401/登录发布返回服务端快照/昵称 Feign 补全/免登录信息流与详情/幽灵商品 404/空白内容 400/不存在分享 404，夹具自动清理）；小程序 stub **17/17**（登录两分支/弹层两路/发布成功失败/转发落点/信息流装配与分页/落地页双入口/再转发路径）；静态自检 0 错误 0 告警；product/fulfilment/gateway 三容器重建健康，增量 SQL 已在运行库执行。

### 2026-09-26（第四十二轮 · 商品详情页升级：立即购买 + 展示丰富化 + 分享修复）

> 用户三点需求：① 底部双按钮 + 立即购买下单流程（可选券/选地址）；② 丰富商品展示（轮播/详情长图/规格参数/包装规格）；③ 分享"只截图、查看分享页无反应"。

- **① 立即购买（后端直购分支 + 前端确认单）**。后端 `PlaceOrderRequest` 增可选 `buySkuId/buyQuantity`（成对校验，数量 1-200）：下单主流程里直购走**内存临时行**（`directBuyRow`，不落 `t_cart_item`），后续快照校验/拆单分组/建单/库存冻结/券锁定与购物车结算**同一段代码**，唯一差别是第 5 步清购物车按 `directBuy` 跳过——"立即购买"绝不污染用户购物车。小程序新建 `pages/checkout` 确认单：地址（复用 address pick 模式）/ 券（服务端 `usable` 判定 + `_couponDeclined` 优先级）/ 数量步进（库存封顶）/ 金额汇总；提交成功直接 `pay.checkout` 进页内收银台，支付成功/超时回落订单列表。详情页底部改「分享｜加入购物车｜立即购买」。
- **② 展示丰富化**：轮播 3→5 图（`images.gallery` 扩容，零新增包体）；新增**规格参数**卡（品牌/分类/适用宠物/适用阶段/过敏原提示——全部来自后端真实字段，没有的行不出，不编造）与**包装规格**卡（SKU `spec` 键值对 + SKU 编码 + 当前库存，**随选中规格联动**）；商品介绍升级为**图文详情**（detail 长文 + 3 张配图 `detailImages`，与轮播错开取图）。
- **③ 分享**。定位：devtools 转发面板的卡片预览**本来就是页面截图**、"转发成功/查看分享页"是工具模拟行为（真机上点分享弹出系统转发面板、好友点卡片按 `path` 进商品详情）——代码本身没有"只截图"的实现。本轮把分享做扎实：卡片标题带价格、封面用商品首图（1:1 本地位图，真机有效）、新增 `onShareTimeline`（右上角菜单 → 分享到朋友圈）。
- **验证**：后端 `scripts/direct-buy-smoke.py` **13/13**（直购下单成功/金额=单价×数量/购物车条目数零变化/购物车结算原路径回归/缺 `buyQuantity` 400/不存在 SKU 404/订单详情可查）；小程序 stub **24/24**（结算页取 SKU/金额/券三段优先级/数量边界/直购参数/进收银台；详情页 5 图/参数卡/联动/跳转/分享卡）；静态自检 0 错误 0 告警。trade 容器已重建健康。

### 2026-09-26（第四十一轮 · tab 切换顶部白条根治：5 个 tab 页改自绘导航栏）

> 用户二次反馈同一白条问题（附截图）。上一轮只给 15 个页面 json 补了 `navigationBarBackgroundColor`，未能根治 —— 本轮重新取证后改结构。

- **再取证（像素级）**：截图中状态栏区域 = #4F6EF7（蓝），紧邻其下的 44pt 行 = **纯白 #FFFFFF 且无标题**（实测 71px @1.62px/pt ≈ 43.8pt）。视频逐帧确认三次切换各闪约 160ms 后恢复。状态栏恒蓝 + 标题行变白 → 白条就是**原生导航栏的标题行被重建为默认白色**。
- **为什么上一轮的办法不够**：开发者工具中导航栏是模拟层，切 tab 时该层随页面重建，与页面 json 里声明什么颜色无关（页面内容与状态栏都正常，只有这一行重建）。真实设备同理：原生导航栏由客户端在切页时重建。
- **根治**：5 个 tab 页（index / category / cart / orders / profile）改用**自绘导航栏** —— `navigationStyle: custom` + 新增 `components/nav-bar`（固定蓝条 + 流内占位块）；尺寸由 `utils/nav.js` 按胶囊位置反推（`(胶囊 top - 状态栏高) * 2 + 胶囊高`，取不到则兜底 44px）。原生导航栏不再存在 → 没有可闪烁之物；非 tab 页保持原生（推进式跳转无此问题，且需要返回键）。
- **连带收口**：① category 页整页 `position: fixed` 布局，改为按导航总高下发 `top`（否则内容顶到自绘栏底下）；② orders 页吸顶筛选条 `top` 同步下移；③ index 页下拉刷新改**自绘指示器**（原生三点指示器会被自绘导航栏遮挡，自绘栏在 `loading` 时显示白环）。
- **验证**：17 个 JSON 合法；5 个改动 JS 语法全过；Node stub **11/11**（尺寸计算 iPhone 15 Pro=42/96、接口不可用兜底 44/98、组件 attached 写入、category/orders 的 navTop=96、index 下拉 refreshing 开关 + stopPullDownRefresh）。

### 2026-09-26（第四十轮 · tab 切换顶部白条闪烁修复）

> 用户录屏反馈：底部标签切换页面时，手机顶部突然闪现一条白色色带随后消失。

- **定位过程**：ffmpeg 全帧率抽帧（185 帧）+ 帧差定位三次切换发生在第 60/78/97 帧；逐行亮度分析确认白条位置 = **导航栏区域（状态栏下方、胶囊那一行）**，切换后约 150ms（59-63 帧）导航栏渲染为白底且无标题，随后才涂上蓝色与标题。状态栏全程保持蓝色 —— 排除滚动重置/页面背景缺失/过渡动画，锁定为**导航栏初始化时序**。
- **根因**：15 个页面 json **都没有显式声明 `navigationBarBackgroundColor`**，只依赖 app.json 全局 `window` 配置。tab 切换时新页面导航栏先按缺省白色初始化、样式合并在下一拍才生效 → 白条闪现（开发者工具模拟器上明显）。
- **修复**：全部 15 个页面 json 显式补 `"navigationBarBackgroundColor": "#4f6ef7"` + `"navigationBarTextStyle": "white"`（与全局一致；非 tab 页顺带受益，风格声明收口到页面级）。
- **验证**：16 个 JSON 全部合法；修复后切换 tab 导航栏即按蓝色初始化，无白条（开发者工具重新编译后切换五 tab 验证）。

### 2026-09-26（第三十九轮 · 页内沙箱收银台 + 购物车选券）

> 用户反馈：① 支付要复制链接到浏览器才能付，要求小程序内直接拉起支付；② 结算时优惠券不生效（59 元商品持 5 元无门槛券仍付 59）。

- **① 页内沙箱收银台 `pages/pay/pay`（app.json 15 页）**。先说清三条路为何只有一条能走：`web-view` 强制 HTTPS 且业务域名要在小程序后台预配置，`http://localhost` **永远配不了**；`wx.requestPayment` 需要真实微信渠道的预下单参数（B-07 边界，渠道未接）；所以正确做法是**页内自绘收银台**：`pay.checkout()` 从「复制链接弹窗」改为 `navigateTo('/pages/pay/pay?payNo=...&orderNo=...')`，收银台直接调 `POST /api/payments/sandbox/{payNo}`（**公开端点**，与 Web 服务端渲染收银台同一条校验/入账路径，不新开后门）。**回调链透明交接**：`pay.js` 用 `pendingHandlers` 暂存调用方 onSuccess/onTimeout，收银台 `takeHandlers` 取走——调用方（订单列表/详情）零改动；确认失败兜底走原轮询。页面注明「沙箱环境模拟支付，不产生真实扣款」。
- **② 购物车选券**。根因排查：后端 OrderService 锁券抵扣逻辑本来就正确，**Web 端 Checkout 也传了 couponCode，是小程序端下单从没传过** → 纯前端缺陷。修法：购物车地址行下新增**选券行**（🎫 券名 + 已减金额 + 「券后以支付页为准」）；可用性判断全走服务端 `GET /api/coupons/mine/usable?amount=`（返回逐张 usable/reason + 服务端选好的 bestCouponCode/bestDiscountAmount），前端只把 `couponCode` 传给 `api.order.place`，**金额以服务端锁券时计算为准**，前端不自行算折扣。**语义规则**：默认选服务端推荐最优券；用户点「不使用优惠券」置 `_couponDeclined` **优先级最高**（不被自动推荐覆盖——stub 测试当场抓出旧写法会复活已拒绝状态，已修为三段优先级：拒绝 > 推荐 > 保留已选且失效自动退回）；下单成功重置选券态。
- **验证**：静态自检 0 错误 0 告警（23 js / 15 wxml）；Node stub 7 场景全过——A1 有最优券默认选中、A2 拒绝后不被复活、A3 已选券金额变化失效自动退回、A4 下单携带 couponCode，B0 无券隐藏行、B1 零元不拉接口、B2/B3 收银台成功/兜底轮询。

### 2026-09-26（第三十八轮 · 未登录态按钮统一「去登录」+ 登录返回自动重拉）

> 用户反馈：地址簿未登录时在「登录后管理收货地址」下显示的是**重试**按钮（截图），要求修复并全局排查统一。

- **全局排查结论**：错误盒 + 重试按钮共 6 页 —— **address / order-detail / orders / after-sales** 四页未登录分支有误（coupons 已正确用「去登录」；index/product/category/seckill 的重试对应公开接口，未登录可浏览，属合法场景不在误判之列）。orders 更隐蔽：未登录时把 error 清空 → 落成「还没有相关订单」空态误导。
- **统一修法**：catch 的 needLogin 分支显式置 `needLogin: true`；错误盒按钮 `wx:if="{{needLogin}}"` 显示**去登录**（`api.openLoginPage()`，带去重），`wx:else` 才是重试。address / order-detail 额外补 `onShow` 自动重拉 —— 登录页成功后是 `navigateBack` 回来源页，只触发 onShow 不触发 onLoad，不清「登录返回后停在错误页」的体验断点；onShow 只在 `needLogin && getToken()` 时重拉，避免每次回页多发请求。
- **验证**：静态自检 0 错误；Node stub 4 场景全过（未登录→needLogin 标志 / 登录返回自动重拉 / 网络错误仍走重试 / 订单列表未登录明确提示）。

### 2026-09-26（第三十七轮 · 订单列表商品缩略 + 我的售后入口/列表页）

> 用户两点反馈：① 订单列表卡片只显示「共 N 件商品」，没图没名；② 「我的」页没有售后入口，找不到售后功能。搜索入口维持现状。

- **① 订单列表商品缩略**：`listMine` 的 `OrderView` 本就带 `items`（含 title/specText/mainImage），纯前端改造。卡片最多展示 **2 个商品行**（100rpx 缩略图经 `images.skuImage` 解析层 + 标题单行截断 + ×数量），更多用「…等共 N 件商品」收口，实付金额右对齐独立一行 —— 列表卡片保持紧凑，详情留给详情页。
- **② 我的售后**：`profile` 页「我的订单」下新增入口（`wx:if logged`，接口要求令牌）；新建 `pages/after-sales/after-sales` 列表页 —— 分页拉 `GET /api/aftersales/mine`，卡片 = 类型 + 状态标签 + 诉求（≤2 行）+ 卖家回复 + 单号/时间；**点卡片跳对应订单详情**（售后进度、物流都在详情页，不重复造详情页）；空态/未登录给「去我的订单」指路（申请售后入口在订单详情，按订单状态出现）。app.json 注册（13 → 14 页）。
- **验证**：小程序静态自检 0 错误 0 告警（22 js / 14 wxml）；Node stub 5 场景全过（列表渲染缺 Label 兜底 / 未登录 / 空列表 / 商品缩略 2 行+图片走解析层 / 空 items 不炸）。

### 2026-09-26（第三十六轮 · 多类型售后放开 + 售后举证图直传链路 A15）

> 用户指令「继续完成未完成的任务」—— 工作日志遗留两项低优先待办：① 同一订单多类型售后（此前保守：有任何售后就收起入口）；② 小程序售后**图片凭证上传**（此前只提示「去 Web 端粘贴直链」，而 Web 端也是粘贴，全系统根本没有上传链路）。

- **① 多类型售后（纯前端，后端规则早已就绪）**：唯一键 `(order_id, type)` 冲突才 409，**不同类型可各自立案**。
  `after-sale` 页从「查到一条就不给表单」改为**按已提交类型过滤可选类型**（`remainingTypes`），已提交的售后单列表展示 + 「每种类型只能提交一次」提示；两种都提过才完全收表单。
  `order-detail` 页售后进度改列表展示，**入口只在两种类型都提交过才收起**（`aftersales.length < 2`）。
  Node stub 验证 4 路径：无售后→2 可选 / 已提退款→只剩退货 / 两种都提→无可选 / 查询失败→仍给全部（后端 409 兜底）。
- **② 图片上传链路 A15（从零新建，全系统第一个 multipart 端点）**：
  - 后端 `fulfilment/UploadController`：`POST /api/uploads`（**登录**，multipart `file`，扩展名+Content-Type 双白名单 jpg/jpeg/png/webp/gif，≤5MB，UUID 落盘不信任原文件名）+ `GET /uploads/{filename}`（**公开读**，正则锁死 UUID 文件名防穿越，`Cache-Control: 30d`）。URL base 优先级：显式配置 > `X-Forwarded-*` > 请求 Host。存储=本地盘（对象存储前的过渡态，落卷 `fulfilment-uploads:/data/uploads`）。
  - **双前缀是安全设计**：网关白名单**按路径、不区分方法**，读写同前缀时给 GET 开白名单=匿名 POST。白名单只放 `/uploads/**`，`POST /api/uploads` 必须登录。
  - 网关：fulfilment 路由追加 `/api/uploads/**,/uploads/**`；`GatewaySecurityProperties` 白名单追加 `/uploads/**`。
  - 小程序：`request.js` 新增 `upload()`（wx.uploadFile 对齐 request 行为：同一 Bearer 头/包络/401 处理）；售后页举证图网格（选图→逐张上传→预览/删除，失败原地标「上传失败」不挪位——中途 splice 会让在途上传回写错位），提交只带成功直链、有失败图/上传中明确拦截。
  - **坑（T-66）**：Dockerfile 通用镜像 + 命名卷挂到 `/data/uploads`，卷初始化属主 root，非 root（uid 10001）进程写盘 `AccessDeniedException` → 500。修法：镜像内 `mkdir -p /data/uploads && chown`（空命名卷首次挂载会继承镜像目录属主），**且必须先删容器再删卷**（stop 不释放挂载）。
  - **坑（T-65）**：bash 里 `mvn.cmd` 无 `JAVA_HOME` 时**静默什么都不做、exit 0**——编译验证必须看 class 产物 mtime，不能只看退出码。
- **验证（真实服务）**：`mvn package` + 重建 fulfilment/gateway 容器；新增 `scripts/upload-smoke.ps1` **8/8 全绿**（404 白名单生效 / 匿名 401 / 上传 200+url 格式 / 回读 200 image/png / .txt 400 / 令牌连通）；Python 探针实证**上传直链被售后创建接口的 `ImageUrls.validate` 接受**（带图+假订单号 → 404「订单不存在」而非 400 图片拒绝）。小程序静态自检 0 错误；Node stub 场景 4+5 全过。

### 2026-09-26（第三十五轮 · 登录/注册提示按路径各自准确 + 给可执行下一步）

**用户反馈**：用**未注册**手机号直接登录，提示是「**请先登录**」；而走「微信一键登录 → 微信未绑定 → 手机号登录」时却能注册并绑定成功。诉求：两条路径的提示各自准确 —— 直接手机号登录且未注册 → 提示**先注册**；微信路径未绑定 → 引导**手机号登录或注册并绑定**。

**根因（实测确认）**

| 层 | 实际行为 |
|---|---|
| 后端 | 未注册手机号登录 → **401** + message「**手机号或密码不正确**」；已注册但密码错 → **同为 401 + 同一文案**（**防用户枚举的刻意设计**，合理） |
| 前端 | `friendlyMessage('UNAUTHORIZED', msg)` → `return '请先登录'` —— **一刀切把后端准确文案盖掉了** ✗ 这就是"未注册却提示请先登录"的直接原因 |

**修复**

| 文件 | 改动 |
|---|---|
| `utils/request.js` | 401 **透出后端文案**（`message \|\| '登录已过期，请重新登录'`）：登录接口 → 「手机号或密码不正确」；业务接口 → 「身份未认证或登录已过期」——同一条修复让两边都变准确 |
| `pages/login/login.js` | 新增 `handlePhoneAuthError(err, mode)`：**401** → 页面内引导「手机号登录失败：可能未注册或密码输错」+ 动作「去注册新账号」+ modal；**注册撞号 409** → 「该手机号已注册」+ 动作「去登录」+ modal；其余 → 提示条 + toast 双通道 |
| `pages/login/login.js` | `bindTip` 升级为**通用页面内引导**，动作扩展为 `bind` / `phone` / `register` / `login`；切页签时**保留已填手机号** |
| `pages/login/login.wxml` | 引导按钮文案改为 `actionText`，`action` 为空时不渲染按钮 |

**验证（Node stub 驱动页面方法）**

| 场景 | 结果 |
|---|---|
| ① 未注册手机号登录（401） | ✅ 提示条「手机号登录失败 / 手机号或密码不正确。如果是第一次使用，先去注册…」+ 按钮「去注册新账号」+ modal「去注册」 |
| ② 点「去注册新账号」 | ✅ `mode='register'` 且**手机号保留**（不用重打） |
| ③ 注册撞号（409） | ✅ 提示条「该手机号已注册 / 直接登录即可」+ 按钮「去登录」；点后 `mode='phone'` 且手机号保留 |
| ④ 微信未绑定 + 未登录（422） | ✅ 「用手机号**登录或注册**后，会自动把这个微信绑定到该账号」+ 按钮「用手机号登录并绑定」 |
| ⑤ 微信未绑定 + 已登录（422） | ✅ 按钮「绑定到当前账号」 |

> 设计取舍：后端不区分「手机号不存在 / 密码错误」是安全设计（防枚举），所以前端**不去猜**，而是把两种可能都写清并给出对应入口 —— 既不泄信息，也不让用户卡住。


### 2026-09-25（第三十四轮 · 未绑定提示「必然可见」加固：页面内引导 + modal 双保险）

**用户复测反馈**：解绑后点「微信一键登录」，Console 里有两条 —— 一条 `422`，一条 **`[login] 微信登录失败 {status:422, code:'BUSINESS_RULE', message:'该微信未绑定账号…'}`**，但**提示框仍未弹出**。

**这一步的价值**：日志证明**判断逻辑与 422 分支都已命中**（上轮加的诊断日志起作用了），因此问题**不在 `status` 判断**，而在 **`wx.showModal` 这个载体本身没能呈现**（系统弹窗可能被环境状态/竞态吞掉 —— 这类问题在真机上难以稳定复现，也不该靠"再试一次"赌运气）。

**修复策略（不依赖单一载体）**

| 层 | 载体 | 特性 |
|---|---|---|
| 第一层（**必然可见**） | `data.bindTip` → **页面内引导卡片**（微信按钮正下方，标题 + 说明 + 操作按钮 + 可关闭） | **不依赖任何 wx API**，只要 `setData` 成功就一定渲染 |
| 第二层（保险） | `wx.showModal` | 保留，正常环境下体验更轻 |

卡片按钮按登录态自适应：
- 已登录（`loggedIn=true`）→「**绑定到当前账号**」→ `bindCurrentWechat()`，成功 toast「已绑定微信」
- 未登录 →「**用手机号登录并绑定**」→ 切 `mode='phone'` 并置 `pendingWechatBind`（登录成功后自动绑定）

**验证（Node stub 驱动页面方法）**

| 场景 | 结果 |
|---|---|
| 未登录 + 422 | ✅ `bindTip={title:微信还没绑定账号, content:…, action:phone}` + `wx.showModal` 已调用 |
| 已登录 + 422 | ✅ `bindTip.action='bind'` + `wx.showModal` 已调用 |
| 点卡片按钮（bind） | ✅ 走 `bindCurrentWechat()` → 成功清空卡片 + toast |
| 点卡片按钮（phone） | ✅ 切手机号表单并置 `pendingWechatBind` |


### 2026-09-25（第三十三轮 · 解绑后「微信一键登录无提示」根治 + 地址表单输入框修复）

#### 一、解绑后点一键登录：只有 422、界面无提示（根因链与修复）

**现象**：解绑微信 → 再点「微信一键登录」→ Console 有 `POST /api/auth/wechat/token 422`，但界面**没有任何提示**。

**根因链（两条静默路径叠加）**

| 环节 | 问题 | 后果 |
|---|---|---|
| `api.getWxCode()` | `wx.login` 在个别环境**既不回调 success 也不回调 fail**，Promise **永不 settle** | 调用方的 loading/submitting 被永久锁死 |
| `app.silentRelogin()` | 单飞标记 `_silentLoggingIn` 因此**永久为 true**，后续调用直接 `reject({needLogin:true})` —— **该错误没有 `status` 字段** | 绕过 422 的专用引导分支 |
| `login.handleWechatError()` | 未特判 `needLogin`，落到末尾 `wx.showToast(...)` | toast 仅 1.5 秒且易被后续操作顶掉 → 观感"无任何提示" |
| `login.wechatLogin()` | `if (this.data.submitting) return;` —— **静默返回** | submitting 一旦被锁死，**之后每次点击都毫无反应**（像按钮坏了） |

**修复**

| 文件 | 改动 |
|---|---|
| `utils/api.js` | `getWxCode(timeoutMs=10000)` 加**超时保护**：`wx.login` 不回调时 10 秒后 reject「微信授权超时」（带 settled 标记防重复 settle） |
| `pages/login/login.js` | ① `submitting` 守卫改为 `wx.showToast('正在处理中，请稍候')`，**不再静默**；② `handleWechatError` 的 **503 / 未知错误（含单飞冲突）全部改用 `wx.showModal`**（modal 不会被顶掉），未知错误并给「用手机号登录」出口；③ 加 `console.warn('[login] 微信登录失败', {status, code, message})` 作为诊断线索 |

**验证方式（可复现，不依赖真机）**：Node 里 stub `wx` API + `Page`，直接驱动 `handleWechatError` —— 4 种失败路径**全部产生可见提示**：

| 场景 | 结果 |
|---|---|
| 未登录 + 422 | ✅ MODAL「微信还没绑定账号 / 用手机号登录或注册后，会自动把这个微信绑定到该账号」 |
| **已登录 + 422（本次用户情形）** | ✅ MODAL「微信还没绑定账号 / 当前账号已登录。要把这个微信绑定到当前账号吗？」 |
| 单飞冲突（无 status 的 `needLogin`） | ✅ MODAL「一键登录未完成 / 正在重新登录」（修复前是含糊 toast） |
| 503 未配置 | ✅ MODAL「微信登录暂不可用 / 请改用手机号登录」 |

另用「`wx.login` 永不回调」实测：**1209ms 后正确 reject**（测试用 1.2s 超时）→ 不再存在永久悬挂。

#### 二、收货地址表单：输入框文字显示不完整（修复）

**根因**：`.field-input` 写了 `width:100% + box-sizing: border-box`，而小程序 `input` 的**默认内容高度**在 border-box 下会被上下 `padding: 20rpx` 挤占 → 文字只露出上半截（截图里"请填写收货人姓名""11 位手机号""如 广东省"都被裁）。

**修复**（`pages/address/address.wxss`）：input 改用**显式高度**——`height: 84rpx; line-height: 84rpx; padding: 0 24rpx`（高度已给定，padding 只管左右）；`textarea` 保持 `min-height: 160rpx + padding`（其行为正常，截图可证）。

> 说明：登录页 / AI 页的 input 用的是 `padding` 撑高且**未加 box-sizing**，实测显示正常（截图佐证），故**不动**，避免引入视觉回归。


### 2026-09-25（第三十二轮 · P2 订单售后入口 + P3 搜索高亮与纠错）

#### 一、P2 订单售后入口（已实现）

| 文件 | 内容 |
|---|---|
| `pages/after-sale/*`（新） | 售后类型（**仅退款 / 退货退款**）+ 诉求（≤500 字，本地校验与后端一致）+ 提交；进页面先查该订单是否已有售后，**有则只展示进度、不给重复提交入口** |
| `pages/order-detail/*` | 展示该订单售后进度（类型/状态/诉求/卖家回复）；**SHIPPED / COMPLETED** 显示「申请售后」 |
| `utils/api.js` | 新增 `aftersale.create / mine / detail` |

**实测中发现并修正的产品细节**：后端业务规则是「**发货后**才能申请售后」（未发货时 422「订单当前状态为「待付款」，发货后才能申请售后」）。第一版我在 `PAID` 状态也给了入口 → 用户填完表单必然失败。已改为：`PAID` 只提示「卖家发货后可申请售后」，入口仅 `SHIPPED / COMPLETED` 显示。
另外拿到第二条规则文案：**同订单同类型**重复提交 → 409「该订单的此类售后已立案，请勿重复提交」。因此小程序端在已有售后时不再给入口（如需另一种类型，可在 Web 端提交）——**保守但不会误操作**。

#### 二、P3 搜索高亮 + 纠错（已实现）

| 部分 | 实现 |
|---|---|
| **纠错端点**（后端） | `GET /api/products/search/suggestion?keyword=猫良` → `{"suggestion":"猫粮"}`；路径用**两段**避免与 `/{spuId}` 冲突；白名单 `/api/products/**` 已覆盖，无需改网关 |
| **词表**（零维护） | 品牌名 + 分类名 + 在售标题切词，**外加每个词的 2~4 字连续子串**；预建**拼音索引**（同音 O(1)）与**长度索引**（形近只扫 ±1 桶） |
| **判定** | ① 拼音完全相同（中文错误绝大多数是拼音输入法选错字）→ ② 形近：长度差 ≤1、共享至少一汉字、编辑距离 ≤ max(1,len/3)；**给不出靠谱候选返回空**（宁可不建议） |
| **前端高亮** | `utils/highlight.js` 把标题切成命中/未命中片段，WXML 用 `wx:for` 渲染 `.hl`；**刻意不用 rich-text**（拼 HTML 有注入面） |
| **前端提示** | 完全搜不到时调一次建议接口 → 空态里显示「你是不是想搜「猫粮」？」→ 点击一键改搜；慢响应会校验关键词是否已变，避免覆盖新结果 |

**关键教训（实测得出）**：**词表粒度决定纠错能不能用**。第一版只收「标题按空格切出的整词」→ 切出来是「幼猫粮」「冻干双拼全价猫粮」这类长词，用户打的「猫粮」「冻干」不在词表 → 纠错永远给不出建议，甚至把「猫粮」当错词错配成「猫主粮」。加入 2~4 字子串后立刻正确。

#### 三、实测

| 项 | 结果 |
|---|---|
| 搜索纠错 6 例 | ✅ 猫良→**猫粮**、喂食气→**喂食器**、冻千→**冻干**；猫粮 / 猫砂盆 / zzz不存在 → **空**（不硬凑） |
| 售后链路 7 步 | ✅ 登录 → 下单 → 预下单 → **沙箱确认支付 SUCCESS** → 模拟发货 SHIPPED → **申请售后 200（PENDING/仅退款）** → 我的售后 1 条 orderNo 匹配 → 重复提交 **409** |
| 小程序静态自检 | ✅ 21 js / 13 wxml / 104 文件 / 0 错误 0 告警（自检脚本同时补了 `wx:for-item` 别名白名单，消除 `seg` 误报） |

**待办（低优先）**：小程序端售后**图片凭证上传**（需先有上传链路，当前提示在 Web 端补充）；订单详情「同一订单两种售后类型」的完整支持。


### 2026-09-25（第三十一轮 · 地址选择模式 + IK 分词器实测结论 + 冒烟夹具治理）

#### 一、P0 地址选择模式（已实现）

| 改动 | 文件 | 说明 |
|---|---|---|
| 地址簿「选择模式」 | `pages/address/*` | `?pick=1` 进入：顶部提示条 + 卡片描边可点，选中后经 **`eventChannel` 回传**并返回 |
| 购物车地址行 | `pages/cart/*` | 新增可点的「收货地址」行（显示收货人/电话/完整地址）；点进选择模式，选中即**本次结算生效**；下单优先用选中地址、否则回落默认地址 |
| 秒杀收货信息确认 | `pages/seckill/*` | 秒杀讲"快"，**不插选地址步骤**；抢购成功后弹窗展示收货信息，避免手快寄错（要换地址走「购物车 → 地址」或「我的 → 收货地址」） |

> 为什么用 `eventChannel` 而不是全局变量：避免留下"下一次结算还沿用上一次选的地址"的幽灵状态。

#### 二、IK 中文分词器：实测结论 = **不切换**（数据说话）

| 步骤 | 结果 |
|---|---|
| 插件获取 | ✅ 走官方非 GitHub 源 `release.infinilabs.com`（**GitHub 源 404**，这正是当初判定的最大变数） |
| 包校验 | ✅ `name=analysis-ik`、`version=8.11.4`、`elasticsearch.version=8.11.4`（与 ES **严格同版本**） |
| 隔离容器验证 | ✅ `bin/elasticsearch-plugin list` → `analysis-ik`；临时 ES 容器正常启动（10 秒就绪） |
| **分词效果** | ❌ **无预期收益**：`猫粮` → ik_smart `['猫','粮']`（与 standard 相同）；`喂食器` → `['喂','食器']`（改善有限）；`冻干鸡肉粒` → `['冻','干','鸡肉','粒']`（部分改善） |

**结论**：IK 主词典是通用词典，**不含"猫粮/喂食器/冻干"这类垂直词** → 装了等于白装，却要承担「插件版本与 ES 绑定、容器重建要带插件」的运维面（A10 compose 注释里当初**刻意**规避了这一面）。

**保持现状**：现有「`match_phrase` 精确优先 + 分层加权 + 两段式兜底」已达成目标（实测 `喂食器` 第 1 条即精确命中）。
**将来若要做词级语义**：正确路径 = **IK + 宠物领域词典（猫粮/犬粮/喂食器/冻干/猫砂/磨牙棒/化毛膏/猫抓板…）+ 定期运营**，三者缺一不可；实施步骤与风险回滚见交付说明，插件包已清理（不入库）。

#### 三、冒烟夹具治理

- **已清理**：`微信冒烟-A/B` 共 **16 个**（删除前确认**零关联**：无地址/购物车/订单/秒杀单）。
- **从源头止住**：给 `scripts/wechat-auth-smoke.ps1` 补**自清理**（此前跑一次留 2 个账号，堆了 16 个）；`wechat-bind-smoke.ps1` 本就自带。
- **不盲删其余 690+ 历史夹具**：`铲屎官小林`、`买家甲` 等昵称是**被 CI 冒烟断言依赖的固定夹具**（`scripts/smoke.ps1:135` 断言 `data.nickname -eq '铲屎官小林'`），盲删会直接把 CI 弄红 → 需按脚本逐个核对依赖后再清（列为待办）。
- **踩坑**：`docker exec mysql ... -e "DELETE ... LIKE '中文%'"` **不带 `--default-character-set=utf8mb4` 时匹配不到任何行**（第一次删除"成功"但 0 行受影响）——中文条件必须带字符集参数。


### 2026-09-25（第三十轮 · 小程序体验闭环：地址簿 / 订单详情 / 微信绑定管理）

> 目标：补齐"只有 Web 端能做"的环节，让只用小程序的用户能走完整主流程。

| # | 交付 | 文件 | 说明 |
|---|---|---|---|
| 1 | 收货地址簿 | `pages/address/*`（新） | 列表 / 新增 / 编辑 / 设为默认 / 删除；本地校验与后端一致（手机号正则、长度上限）；**不做省市区级联**（数据包约 900KB 会挤爆 2MB 主包）→ 三个文本输入 + 常用标签 |
| 2 | 订单详情页 | `pages/order-detail/*`（新） | 状态卡（含有效期提示）/ 商品明细 / 金额（总额·运费·优惠·实付）/ 收货信息 / **物流轨迹** / 按状态显示「去支付·取消订单」「确认收货」/ 复制单号；下拉刷新 |
| 3 | 微信绑定管理 | `pages/profile/*` | 菜单显示 `wechatBound`（已绑定/未绑定）；绑定走 `wechatBind`、解绑走 `wechatUnbind`（**每次重新 `wx.login` 取 code**，code 一次性）；解绑二次确认 |
| 4 | 支付模块抽取 | `utils/pay.js`（新） | `checkout`（发起+收银台+轮询）/ `startPolling` / `stopPolling`；订单列表与详情页共用，删除列表页里那份重复实现 |
| 5 | 入口打通 | `pages/cart/*`、`pages/seckill/*`、`pages/orders/*` | 无默认地址时弹窗**直接跳地址簿**；订单卡片可点进详情，卡内按钮改 `catchtap` 防冒泡 |
| 6 | 契约与工具 | `utils/api.js`、`utils/request.js` | 补 `put`、地址 CRUD、物流、`getWxCode()`、`wechatUnbind/Rebind`；`login.js` 复用 `getWxCode` 去掉重复实现 |

**技术选型与约束**：延续原生小程序、零新增依赖、后端**零新增端点**；主包 2MB 上限决定了"不做省市区数据包"；一次性 code 决定了绑定/解绑/换绑必须各自重新取码。

**实测（14 项全通）**：地址空列表 → 新增（首条自动默认）→ 默认查询 → 编辑 → 再列表；`/users/me` 的 `wechatBound` 三态 `false→true→false` 且**不含 openid**；下单 → 订单详情（items/payAmount/receiver）→ 物流轨迹（未发货 total=0）。自检 **0 错误 0 告警**（期间自检当场抓出 `profile.wxml` 里 `addressTip` 旧方法名残留，已修）。

**下一步计划**：P0 地址"选择模式"（结算临时换地址，而非只能改默认）｜P1 **IK 分词器**（含风险/回滚，见交付说明）｜P2 订单售后入口｜P3 搜索高亮与纠错。


### 2026-09-25（第二十九轮 · 解绑/换绑接口 + ES 搜索完善 + 分类页溢出修复）

#### 一、账号解绑与换绑接口（新增）

| 端点 | 入参 | 校验规则 | 关系更新 | 失败返回 |
|---|---|---|---|---|
| `POST /api/auth/wechat/unbind` | `{code}` | 需登录；code→openid 必须**等于当前账号绑定的 openid** | `wx_openid = NULL`（`LambdaUpdateWrapper.set(null)`） | 401 未登录/code 无效；**422** code 属他人微信；503 微信不可用；**未绑定 → 200 幂等** |
| `POST /api/auth/wechat/rebind` | `{code}`（新微信） | 需登录；新 openid **未被他账号占用** | `wx_openid = 新 openid`（旧关系被覆盖即释放） | 401；**409** 新微信已属他人；503；**新=旧 → 200 幂等**；未绑定 → 首次绑定 200 |

- **为什么带 code**：解绑是破坏性动作（解绑后该微信可被他人绑定），只验登录态不够 —— 必须证明"操作者确实持有这个微信"。
- **实现坑（已实测验证）**：置空必须用 `LambdaUpdateWrapper.set(field, null)`；MyBatis-Plus `updateById` **默认忽略 null 字段**，直接 `setWxOpenid(null)` 会「看起来成功、库里没清」。
- **安全**：`UserView` 只新增 `wechatBound` 布尔，**绝不下发 openid**；两端点**不在网关白名单**。
- **自测**：新增 `scripts/wechat-bind-smoke.ps1`（24 断言）；本地等价 HTTP 实测 **13 项分支全通**。

#### 二、ES 搜索完善（已落地 4 项 + 六维优化清单）

| 维度 | 已落地 | 待办建议 |
|---|---|---|
| 查询条件 | **多词 AND**（空格/逗号分隔），`猫粮 2kg` → 精确 2 条 | 引号短语、排除词 `-xx`、字段限定 `品牌:渴望` |
| 分词 | standard（中文逐字）+ 拼音 ngram 路 | **接 IK 分词器**（`ik_smart`）按词切分；`title` 加 `keyword` 子字段做完全匹配加权 |
| 排序 | `_score → salesCount → refId`；显式 `sort=relevance` | 自营/新客加权、时间衰减 |
| 分页 | **深分页保护** `from+size ≤ 10000`（超出 400 + 明确文案，两引擎统一入口拦截） | 更深分页改 `search_after` 游标 |
| 结果准确性 | 分层加权 + 两段式兜底；同义词应用侧扩展 | 命中高亮 `highlight`、拼写纠错 did-you-mean；用日志 `兜底=true` 的词反哺同义词 |
| 可观测性 | **结构化检索日志**（keyword/引擎/命中/兜底/页/返回/耗时） | 落库做搜索分析（零结果词、无点击词） |

实测：「猫粮 2kg」2 条全中、「冻干 鸡肉」1 条、「喂食器」1 条精确、`sort=relevance` 与默认一致、`page=600` 被拦（400）、`page=500` 边界放行、无关键词仍按销量降序（无回归）、日志可见。

#### 三、分类页分类标签/标题溢出（修复）

`.cat-main` 是 flex 子项，默认 `min-width: auto` → 被内部长商品名"顶宽"，右栏连同卡片一起超出屏幕（上一轮只给 `.cg-body` 加了 `min-width:0`，漏了外层）。修复：`.cat-main{min-width:0}` + `.cg-card{max-width:100%;box-sizing:border-box}` + `.cg-name{word-break:break-word}`。


### 2026-09-25（第二十四轮 · 搜索相关性修复：结果与关键词不匹配）

> 用户反馈（附截图）：搜「喂食器」返回猫罐头/鸡肉条零食/冻干鸡肉粒/自动喂食器，**只有 1 条对且排最后**。

#### 三处根因（均有实测证据）

| # | 根因 | 证据 |
|---|---|---|
| 1 | `multi_match` 写死 **`operator: or`**，而 `pet_text` 用 standard 分词器（中文**逐字切分**）→ 「喂食器」= `喂 OR 食 OR 器`，含任一字的商品全召回 | 实测召回 4 条，3 条含「食」；对照 MySQL 路径 `title/subtitle LIKE %喂食器%` 只会命中 1 条 |
| 2 | 默认排序只有 `salesCount desc`，**ES 显式 sort 后不再按相关性排** | 实测顺序 1008→1011→1009→1015，精确项「霍曼 自动喂食器」（销量 860 最低）垫底 |
| 3 | 商品占位图上画了品类文字，且按 id 映射（与真实商品不一一对应） | 截图：猫罐头配「领养关注」、喂食器配「逗猫玩具」，放大「不匹配」观感 |

#### 修复

| 文件 | 改动 |
|---|---|
| `duoduo-product/…/search/EsSearchEngine.java` | ① 召回改**分层加权**：标题整串短语 6.0 / 标题分词全命中 3.0 / 副标题短语 2.0 / 描述短语 1.0 / 跨字段 AND 2.0 / 拼音 1.5 / **逐字兜底 0.1**；同义词变体权重减半；② 新增 `searchStrictThenLoose`：**严格召回为零才启用兜底**（两段式，宁可 1 条精确也不 4 条里对 1 条）；③ 默认排序 `_score desc → salesCount desc → refId desc`（无关键词时 `_score` 相同 → 退化为销量降序，不影响导航页；显式 sort 仍尊重用户选择）；④ 校正类注释（索引侧并没有 synonym filter） |
| `scripts/gen-miniprogram-images.py` | 商品图去掉品类文字（纯图形 + 柔光白圆 + 装饰弧），消除图文不符；重生成后 **352.7 KB / 17.2%** |

#### 自测（重建容器后经网关实测）

| 关键词 | 修复前 | 修复后 |
|---|---|---|
| 喂食器 | 4 条（精确项排第 4） | **1 条：霍曼 自动喂食器 4L** |
| 零食 | 4 条（混入喂食器/罐头） | 2 条（均为零食/猫零食） |
| 猫粮 | 15 条（混入犬粮/喂食器/化毛膏） | **4 条全是猫粮** |
| 猫窝（严格 0 命中） | — | 11 条相关建议（兜底生效，不空手） |
| 无关键词 / `sort=price_asc` | 销量降序 / 生效 | **无回归** |

`mvn -pl duoduo-product -am package` BUILD SUCCESS；小程序静态自检 0 错误 / 0 告警。**改动只在查询构造，索引无需重建。**

### 2026-09-25（第二十三轮 · 微信一键登录「未绑定」闭环 + 文案校正）

> 用户反馈：微信一键登录报错（附截图）。**诊断结论：登录链路完好，是「该微信未绑定账号」（422）。**

#### 证据链（服务端日志）

- `19:13:08 WechatGateway: jscode2session 成功 openidPrefix=oqYQ len=28` —— 微信侧换码成功（AppID/Secret、工具链路均正常）
- `19:13:08 GlobalExceptionHandler: code=BUSINESS_RULE msg=该微信未绑定账号…` —— 用户所见「报错」= HTTP 422
- 19:25 / 19:30 两次点击同样结果 → 说明引导无法执行，用户只能反复点

#### 根因

`/api/auth/wechat` 对未绑定微信**不做自动注册**（A13 刻意设计：微信授权拿不到手机号，自动注册会造出无手机号孤儿账号）。
真缺陷在**引导不闭环**：后端 422 文案让用户「去『账号与安全』绑定」——小程序端没有该入口；客户端仅弹提示，无下一步。

#### 修复

| 文件 | 改动 |
|---|---|
| `miniprogram/utils/api.js` | 新增 `wechatBind(code)` → `POST /api/auth/wechat/bind`（登录态；后端 A13 已有此端点） |
| `miniprogram/pages/login/login.js` | 四情形闭环：①已绑定→一键登录 ②未绑定+未登录→引导手机号，**登录成功后自动 `wx.login` 取新 code 绑定当前微信**（code 一次性，不可复用失败那次） ③未绑定+已登录→**一次点击直接绑定** ④该微信属他人→409 提示 |
| `miniprogram/pages/login/login.wxml/.wxss` | 绑定流程顶部提示条「用手机号登录/注册后，会自动把当前微信绑定到这个账号」 |
| `duoduo-user/…/AuthService.java` | 422 文案改为「请先用手机号登录或注册，登录成功后即可绑定微信」（对 Web 与小程序两端都准确） |

#### 自测（真实 HTTP，含 mock 通道；临时账号已清理）

`未绑定一键登录 422` → `登录态绑定 200` → **`绑定后再一键登录 200 + 同一 userId + accessToken`** → `重复绑定 200 幂等` → `他人抢占 409`；`mvn -pl duoduo-user -am package` BUILD SUCCESS + 容器重建；小程序静态自检 0 错误 / 0 告警。

### 2026-09-25（第二十二轮 · A14 界面修复与资源补齐：分类栏统一 + 图片 + 首页轮播）

> 用户反馈：①点击不同分类时选中态/高亮/排版各不相同；②商品列表、详情页、分类图标、首页标签图标缺图（允许用网上素材），要求尺寸比例统一、风格一致；③首页限时秒杀/领券栏目上方加轮播图（自动播放、平滑切换、指示器、左右控制、手势滑动、配置数组、大屏适配）。

#### 分类栏不一致 · 三处根因与修法

| 现象 | 根因 | 修复（文件） |
|---|---|---|
| 某些分类下左栏文字**逐字竖排** | `.cat-side` 只有 width、没有 `flex: none`，被右侧内容挤窄；`.cat-item` 无 `white-space: nowrap` | `category.wxss`：左栏 `flex: 0 0 200rpx` 不收缩；每项改 flex 横排（图标+单行文案 ellipsis） |
| 商品卡**有时单列有时双列** | `.cat-main .goods-card` 写死 320rpx，与左栏宽度相加超出可用宽度 → 换行结果随内容变化 | 卡片宽度改**容器百分比 48.6%**（248rpx × 2 + 14rpx 间距 = 510rpx），封面 250rpx 近 1:1 |
| 连点分类「点了没反应/停在旧分类」 | `fetchProducts` 开头 `if (loading) return` 丢弃的是**新请求** | 改**请求序号** `_seq`：新请求照发，过期响应丢弃 |
| 选中态观感不统一 | 旧选中态仅左蓝条+灰底 | 统一「浅蓝底 + 蓝左条 6rpx + 主色加粗」，未选中左条透明（切换不跳动）；chips 同步同一套配色 |

#### 图片补齐 · 34 张 / 397.5 KB（主包 19.4%）

- **根因**：后端 `t_spu.main_image` = `/img/products/1001.svg` —— 相对路径（`<image>` 不拼域名）、网关(8080)/商品服务(8082) **均不托管 `/img`**（实测 404/500）、且 `<image>` **不支持 SVG**。三者叠加 → 全站白图 + 控制台 `Failed to load`。
- **修法**：新增 `miniprogram/utils/images.js` 解析层（**绝对 https URL 原样使用**，将来接 CDN 前端零改动；其余按 id 稳定映射随包位图）+ `scripts/gen-miniprogram-images.py` 统一模板生成（色板与 CSS 变量对齐）。
- **产物**：`products/p01–p12.jpg`（600×600）、`category/{bowl,bone,box,medical,chip,heart,default}.png`（键名对齐后端 `t_category.icon`）、`tabbar/*.png`（5 tab 双态 81×81）、`entry/*.png`（4 张）、`banners/b01–b04.jpg`（1000×427）、`share/cover.jpg`。
- **接入**：首页/分类商品流、购物车缩略图、**详情页图集（swiper 3 图 + 缩略图条 + 全屏预览）**、秒杀 SKU 缩略图、`app.json` tabBar 双态图标。

#### 首页轮播（Banner）

`swiper` 自动播放（4s / 420ms 过渡）+ 手势滑动 + 左右圆形箭头（环形步进）+ 自绘胶囊指示器；配置集中在 **`miniprogram/config/banners.js`**（`interval/duration/autoplay/list[{id,image,title,subtitle,url,overlay}]`）；点击自动识别 tabBar 页；高度 320rpx 与素材同比例，`@media (min-width:500px)` 加高到 400rpx 适配平板/桌面。位置在搜索栏下、快捷入口上，**下方秒杀预告与商品流零改动**。

#### 自测

`node scripts/check-miniprogram.js` → **0 错误 / 0 告警**（16 js / 10 wxml / 90 文件 / **26 个图片引用**）；自检新增第 5 条规则：**引用的 `images/**` 必须真实存在**（含 app.json 的 tabBar 图标）。所有 34 张图生成对照表逐张目视确认（`logs/mp-images-check.png`，不入库）。

### 2026-09-25（第二十一轮 · A14 排查修复：小程序全面体检后的缺陷修复）

> 用户指令：对现有代码全面检查与修复（语法/逻辑/异常/边界/安全/冗余），保持原有功能不变，完成后列出问题、根因、方案与待人工确认项。

#### 后端（duoduo-user / duoduo-gateway）· 纯增量，不动既有契约

| 文件 | 变更 |
|---|---|
| `AuthController` | 新增 `POST /api/auth/login/token`、`/api/auth/refresh/token`、`/api/auth/wechat/token`：**令牌随响应体下发**（`LoginResponse`），与浏览器端点共用同一份 `AuthService`。刻意用新增端点而非改既有响应体——后者会让 Web（读扁平 UserView）与既有冒烟断言同时失效 |
| `GatewaySecurityProperties` | 白名单逐条精确补入上述三条（Ant 匹配下 `/api/auth/login` 不覆盖 `/api/auth/login/token`） |
| `docs/agent/04` | 白名单清单 + 端点表同步三行（端点总数 129 → **132**） |

#### 小程序（miniprogram/）· 按严重度修复

| 级别 | 问题 | 修复 |
|---|---|---|
| **P0** | 登录拿不到令牌（`setToken(undefined)` → `ensureSession` 恒判未登录） | 改用 `/api/auth/*/token`；`api.js` 增加 `acceptSession` 明确报错，不再静默变「未登录」 |
| **P0** | 支付缺 `idempotentKey`（后端 `@NotBlank`）必然 400 | `order.pay()` 补幂等键 |
| **P0** | 支付结果字段错（读 `payUrl`/`status`） | 改用 `codeUrl`/`payStatus`；沙箱立即成功分支也处理 |
| **P1** | 秒杀读 `status`（后端是 `phase`）→ 页面整体不可用 | 全量改读 `phase` + `phaseLabel`；dataset 只传标量 id/phase |
| **P1** | 商品图读 `image`（后端 `mainImage`）、`sales`→`salesCount`、`wx:key=spuId`→`id` | 首页/分类页字段与 key 全部对齐 |
| **P1** | 分类页分页失效（列表在 scroll-view 内，`onReachBottom` 不触发） | 改 `bindscrolltolower`；`.cat-wrap` 改 `position:fixed` 撑满可见视口（自动扣 tabBar 与安全区） |
| **P1** | 登录页只有微信入口，新用户必 422 无路可走 | 登录页双入口（微信 + 手机号登录/注册），422 引导切手机号；`app.registerBy` 支持小程序内自助开户 |
| **P1** | 券包「去登录」调用了不存在的 `goLogin` | 补 `goLogin`；不可领时展示服务端 `claimableReason` |
| **P1** | 订单固定 page=1（超 20 单看不到）、无取消/确认确认框 | 分页 + `onReachBottom`；取消订单/确认收货均二次确认；新增取消入口 |
| **P1** | 购物车自算合计、忽略 `selected`/`valid` | 合计改用服务端 `selectedAmount`/`selectedCount`；新增勾选交互；失效条目标注原因并禁选 |
| **P2** | `ensureLogin()` Promise 可能永久悬挂 | `navigateTo` 失败即 reject；登录页 `onUnload` 调 `cancelLogin()` 拒绝等待方 |
| **P2** | 轮询定时器泄漏、连续支付叠加 | 句柄挂实例 + `onHide/onUnload` 清理 + 防重入 |
| **P2** | 401 连开多个登录页；评价昵称字段错；订单状态缺 REFUNDED；`totalQuantity` 不存在；profile 把接口失败当未登录；AI 消息无上限/`wx:key=index` | 401 去重与「已在登录页」判断；`nickname`；用服务端 `statusLabel`；件数本地求和；catch 分类处理；消息上限 60 条 + 稳定 id |
| **P3** | 图片无懒加载、`inset` 兼容性、首页无下拉刷新、骨架屏数组内联、dataset 传大对象、删除无确认、键盘遮挡输入框 | 全部修复（含 AI 页键盘高度适配） |
| 安全/配置 | `BASE_URL` 硬编码；缺 `lazyCodeLoading`/`networkTimeout`；token 双令牌分离存储 | 三档环境常量（dev/lan/prod）+ 显式注释 HTTPS 要求；补两项配置；新增 `clearSession` 统一清理 |

#### 新增自检工具

`scripts/check-miniprogram.js`：①JS 语法 ②JSON 合法性 ③WXML 事件绑定与 JS 方法对齐（本次「goLogin 未定义」即此类）④WXML 数据字段粗筛。当前结果 **0 错误 / 0 告警**。

#### 自测与验证（实测，非推断）

| 验证项 | 结果 |
|---|---|
| `POST /api/auth/login/token`（经网关） | 200，`data` 含 `user/accessToken/refreshToken/expiresInSeconds` |
| `GET /api/cart` 带 Bearer | 200；不带令牌 → 401（证明小程序通道打通） |
| `POST /api/orders/{no}/pay` 旧形状 `{channel}` | **400 VALIDATION**「idempotentKey 不能为空」（复现旧缺陷） |
| 同上 + `idempotentKey` | 通过校验（→ 404 订单不存在），修复生效 |
| `POST /api/auth/refresh/token` 无效令牌 | 401（白名单放行且逻辑正确） |
| 小程序静态自检 | 14 js / 10 wxml / 50 文件 **0 错误 0 告警** |
| `mvn -pl duoduo-user,duoduo-gateway -am package` | BUILD SUCCESS |

**遗留（需人工确认/后续）**：`docs/openapi/*` 需重跑 `scripts/export-openapi.ps1` 刷新（本会话工具集无终端脚本执行能力）；小程序内地址簿页面与订单详情页未做（属新功能）；tabBar 图标需设计资源；真机 UI（分类页布局、安全区）待实机确认。

### 2026-09-25（第二十轮 · A14 小程序全面重构 + 限时秒杀）

> 用户指令：小程序存在结构性缺陷与功能缺失，要求 ①TabBar 五页框架 ②修复详情页「服务器内部异常」③补齐券/AI/秒杀等缺失功能 ④同步补后端接口 ⑤网页端同步。方案确认后选「全部含秒杀」。

#### A14-1~3 · 小程序重构（纯前端，miniprogram/）

| 文件 | 变更 |
|---|---|
| `app.json` | 重写：10 页注册 + 5 Tab（首页/分类/购物车/订单/我的），启动默认首页 |
| `utils/request.js` / `utils/api.js` | 新建请求层（错误映射 / 401 跳登录 / token 管理）+ 全量 API 分域封装 |
| `app.wxss` | 重写：CSS 变量设计 token + 卡片/骨架屏/空态等公共样式 |
| 首页/分类/我的 | 重写或新建：搜索条 + 三宫格入口（秒杀/领券/AI）+ 秒杀预告横滑 + 分类树 + 渐变头部 |
| 购物车/订单 | 重写：步进器 + 状态 Tab 筛选 + 沙箱支付轮询 |
| 商品详情 | 重写 + **字段归一化**：后端 `{card, detail, images, skus}` 映射为页面 shape（需求②修复） |
| 领券/秒杀/AI | 新建三特色页，契约与后端 DTO 逐一核对；14 个 JS 语法检查全过 |

#### A14-4 · 限时秒杀后端（交易域）

| 文件 | 变更 |
|---|---|
| `duoduo-trade/pom.xml` + `application.yml` + compose | 新增 `spring-boot-starter-data-redis`（**软依赖**：Redis 不可用自动降级纯 DB 路径） |
| `docs/sql/03-duoduo-trade.sql` / `incremental/11-a14-seckill.sql` / `db/schema.sql` | 三处同步：`t_seckill_activity` / `t_seckill_sku` / `t_seckill_order`，并已在 duoduo-mysql 执行 |
| `SeckillService` 等 8 个新类 | 实体×3 / Mapper×3 / DTO / Service / C 端 Controller / 运营端 Controller |
| `OrderService` | 超时关单 / 用户取消 / 运营强关三路径挂 `seckillService.onOrderClosed()` 回补配额钩子 |
| 网关 | trade 路由补 `/api/seckill/**`、`/api/admin/seckill/**`；白名单补 activities 两级**精确只读**路径 |
| `frontend/src/pages/Seckill.tsx` 等 | Web 端 `/seckill` 页 + 路由 + 导航「秒杀」+ API/类型/样式；`npm run build` 通过 |

**秒杀架构要点**：Redis Lua 预扣（余量 + 单人限购一次判掉）挡量是**优化**；正确性由 `UPDATE t_seckill_sku SET available_stock=available_stock-1 WHERE available_stock>0` 单条 UPDATE 行锁兜底，**绝不超卖**。活动配额独立于商品库存（下单不冻结商品库存），关单/取消回补配额。秒杀单 order_type=SECKILL，支付/关单/状态机复用既有链路。

**自测**：`seckill-smoke.ps1` **26 通过 / 0 失败**（匿名边界 / 运营准入 / 下单幂等 / 限购 / 售罄兜底 / DB 不负数 / 取消回补 / 再抢成功 / 最终配额=0）。需求②根因定位：详情页报错来自旧页面请求不存在的子路径（被全局兜底成 500）；后端 `GET /api/products/{id}` 实测正常，新详情页已按真实字段对接。

**遗留**：运营端建活动页（Web 管理后台）暂缺，可先用接口/SQL 建活动；真机联调仍待用户微信开发者工具操作。

### 2026-09-25（第十九轮 · A12 压测与容量验收）

> 用户指令：「驱动已经修复完成了，你现在可以执行了」—— 恢复 A12 执行（此前因蓝屏停机码 0x10E 暂停，显卡驱动已由用户修复）。
> 交付：`docs/perf/A12-load-test-report.md`（压测报告）+ `scripts/perf/` 四件套；logs/perf 全部 gitignore 不入库。

#### A12 · 压测与容量验收（交易域主链路）

| 文件 | 变更 |
|---|---|
| `scripts/perf/order-pay-confirm.jmx`（**新**） | 全链路 6 步：注册（Once Only，409 属预期）→ 登录 → 加购 → 下单（同步）→ 支付 MOCK → 发货（`X-Internal-Token` 头）→ 确认收货；每线程独立用户（JSR223 生成 `139%08d`），`${__P(THREADS/RAMP/DURATION)}` 参数化 |
| `scripts/perf/order-queued-enqueue.jmx`（**新**） | 队列通道变体：`POST /api/orders/queued` 入队（排空速率由 `t_order_place_task` DB 侧计量） |
| `scripts/perf/order-pay-confirm-multisku.jmx`（**新**） | 热点行验证：JSR223 按线程分散 3 个 SKU |
| `scripts/perf/summarize_jtl.py`（**新**） | JTL → 吞吐/P50-P95-P99/错误率（总体 + 分环节 + 错误样例） |
| `docs/perf/A12-load-test-report.md`（**新**） | 压测报告：双通道阶梯数据、瓶颈定位、容量模型对齐、优化建议、复原记录 |

**核心结论**：① 同步下单 **~100 单/s** 平台期（延迟随并发线性恶化，100 线程下单 P99 1156ms；加购/发货/确认稳定 10-22ms）——瓶颈在订单落库链路，非 Web 层；② queued 入队 **~1000 单/s** 全程 0 错误（P99 20→128ms 平缓），消费排空 ≈**112 单/s**（与同步上限自洽，队列只转移等待不提高落库上限）；③ 热点行实验：3 SKU 分散后吞吐 **+40%**（114→159 单/s）、下单 P99 **128→84ms**——`t_sku` 热点行排他锁是下单串行化的主要来源。

**容量模型对齐（ARCHITECTURE 9.2）**：读路径实测不构成瓶颈 ✅；写服务 500-800 QPS 为独立部署请求口径，本机共宿主（JMeter+13 容器同机）单实例实测订单口径 ~100/s，差距主因=每订单 10+ 次跨域 DB 写 + 热点行锁 + 单实例 MySQL，**不否定模型**，已给出 4 条优化建议（热点拆分 / 事务瘦身 / 消费并发 / 生产复测）。

**测试伪影（如实记录）**：队列档加购 422 = 购物车同 SKU 累加（`MAX_QUANTITY=200`）在积压场景撞限；任务 FAILED 全为「购物车中没有已勾选的商品」——均非系统缺陷。

**自测**：冒烟 931 请求 0 失败；阶梯 8 轮（sync/queued 各 4 档 × 5 分钟）+ 热点实验 1 轮全部完成并出 HTML 报告；夹具复原复核（2001/2002/2003 = 272/96/210、frozen 0、限流 100、sync）。工具层经验：`jmeter.bat` Java 检测失败会 `pause` 挂死（用 `java -jar ApacheJMeter.jar` 直启绕过）；JMX 手写必须守两条铁律（元素后必带 `<hashTree/>`、`HTTPsampler.Files` 必须 `HTTPFileArgs` 类型）。

### 2026-09-24（第十八轮 · A11 RocketMQ 削峰 + Sentinel 限流）

> 用户指令：「那按照开发计划完成接下来的开发任务吧。」—— 按手册顺序（A11~A13 最后做、A12 依赖 A11 定型）锁定 **A11**。
> 口径变化：容器 **10 → 12**（`profile: mq` 增 namesrv+broker，默认 `docker compose up -d` 仍 10）、OpenAPI **125 → 129 端点**（trade 37 → 41）、冒烟 **19 套 → 20 套**（新增 `mq-smoke.ps1` 47 项）。

#### A11 · RocketMQ 削峰 + Sentinel 限流（交易域）

| 文件 | 变更 |
|---|---|
| `duoduo-trade/pom.xml`（**改**） | 加 `rocketmq-client` / `rocketmq-tools 4.9.8`（原生 client，**刻意不用 spring starter** —— Boot3 兼容轮换频繁，本域只需一 topic 的生产/消费/DLQ 查询）+ `sentinel-core 1.8.6`（版本对齐 SCA 2023.0.1.0） |
| `duoduo-trade/.../application.yml`（**改**） | `duoduo.trade.{order-mode:${DUODUO_TRADE_ORDER_MODE:sync}, mq.{namesrv,topic,consumer-group,max-reconsume}, place-qps-limit:${DUODUO_TRADE_PLACE_QPS:100}}` |
| `.../entity/OrderPlaceTaskEntity` + `mapper/OrderPlaceTaskMapper`（**新**） | 任务台账 `t_order_place_task`（`PENDING/PLACED/FAILED`，唯一键 `uk_place_task_user_idem`）；`markPlaced` / `markFailed`（只认 PENDING，幂等兜底） |
| `.../mq/OrderPlaceProducer`（**新**） | `@ConditionalOnProperty(order-mode=queue)` + `SmartLifecycle`；同步发送 3s 超时重试 2；`sendRaw(byte[], keys)` 供死信重放复用；keys = idempotentKey（DLQ 按键查询的前提） |
| `.../mq/OrderPlaceConsumer`（**新**） | group `duoduo-trade-order-place`，`maxReconsumeTimes=2`；失败**分类**：业务性（`BizException` 非 DEGRADED）→ `markFailed` + CONSUME_SUCCESS 不重试；系统类 → RECONSUME_LATER（10s+30s）→ 耗尽进 `%DLQ%duoduo-trade-order-place` |
| `.../support/OrderPlaceGuard`（**新**） | Sentinel `FlowRule` **代码定义**（资源 `order-place`，QPS 来自环境变量），`SphU.entry` + BlockException → 既有 `RATE_LIMITED`(429)；同步与队列端点**共用同一资源**（防"限流把用户赶到队列端点"） |
| `.../service/OrderPlaceService`（**新**，queue only） | `enqueue`（幂等快查 → insert PENDING → DuplicateKey 返回已有 → 发 MQ，发送失败 markFailed+503）+ `placement` 任务查询 |
| `.../service/DlqService`（**新**，queue only） | `DefaultMQAdminExt.queryMessage`（独立 instanceName `System.nanoTime()`，响应码 208 → 空表）按 key 查 DLQ；`replay` = `sendRaw` 原文发回主 topic（重放安全由订单幂等键唯一索引兜底） |
| `.../service/OrderService`（**改**） | 重构出 `placeOrder(userId, request)` 带 `@Transactional` 重载 —— 控制器与消费者**共用同一份**下单代码（验收④靠复用保证，不靠"两处写一致"） |
| `.../controller/OrderController` + `controller/AdminMqController`（**新/改**） | `place()` 包 Guard；新增 `POST /api/orders/queued`（受理即入队返回 PENDING）与 `GET /api/orders/placement/{key}`；`/api/admin/mq` 死信查询/重放（`OperatorGuard`，网关路由显式补 `/api/admin/mq/**`） |
| `docker-compose.yml` + `docs/mq/broker.conf`（**新/改**） | `rocketmq-namesrv`/`rocketmq-broker` 挂 `profile: ["mq"]`（`apache/rocketmq:5.3.2`，`JAVA_OPT_EXT` 压内存 256m/512m，healthcheck 就绪门）；trade 环境变量三件套；`brokerIP1` 指向服务名 |
| `docs/sql/03-duoduo-trade.sql` + `incremental/10-a11-order-place-task.sql` + `db/schema.sql`（**新/改**） | `t_order_place_task` 建表三处同步（全量 DDL / 增量幂等脚本 / H2 测试 schema） |
| `scripts/mq-smoke.ps1`（**新**） | **47/0 全绿**；脚本**自管** profile mq 起停、trade queue↔sync 切换与结尾复原；覆盖主链路/入队幂等/业务失败/死信查+重放/429 限流/自证非破坏 |

**自测**：`mq-smoke.ps1` 47/0；全量回归 **20 套全绿**（详见「十二、变更记录」口径）；`mvn -q -DskipTests package` 通过。工具层教训沉淀 `08` 篇 **T-63（PS 5.1 原生传参剥引号）**、**T-64（容器名 vs 服务名）**，见 **I-20**。

### 2026-09-24（第十七轮 · A9 AI 导购「多多」+ A10 Elasticsearch 检索）

> 用户指令：「请直接执行任务 A9 和 A10，两项任务一并完成，无需再次确认。」—— 本轮的实质是**新建第 7 个业务服务**（`duoduo-ai`）+ **给商品域接入一层可选检索加速**。
> 口径变化：容器 **9 → 10**（另有可选 `duoduo-es`）、OpenAPI **120 → 125 端点**、冒烟 **16 套 → 19 套**。

#### A9 · AI 导购「多多」（新服务 `duoduo-ai` :8086）

| 文件 | 变更 |
|---|---|
| `pom.xml`（**改**） | 父反应堆加 `<module>duoduo-ai</module>`（reactor 9 → **10 项**） |
| `duoduo-ai/pom.xml`（**新增**） | 依赖 common / common-web / web / validation / actuator / nacos-discovery / openfeign + loadbalancer；**刻意无 DB 依赖** |
| `duoduo-ai/.../AiApplication.java`（**新增**） | `@SpringBootApplication @EnableDiscoveryClient @EnableFeignClients(basePackages="com.duoduo.ai.client")` |
| `duoduo-ai/.../resources/application.yml`（**新增**） | `duoduo.ai.{api-key:DASHSCOPE_API_KEY, base-url, model:qwen-plus, mock-enabled:true, timeout-seconds:15, rate-limit-per-minute:20}`、`duoduo.internal.token` |
| `duoduo-ai/.../client/{ProductSearchClient, dto/ProductCandidate}.java`（**新增**） | Feign 调商品域候选召回；**候选视图不含 `sellerId`**（裁剪字段） |
| `duoduo-ai/.../config/FeignInternalConfig.java`（**新增**） | `RequestInterceptor` 注入 `X-Internal-Token` |
| `duoduo-ai/.../dto/AiDtos.java`（**新增**） | `ChatTurn{role, content}`（**带角色**，服务端只认 `user`）、`ChatRequest`/`ChatResponse`/`ProductRecommendation`/`CapabilityView` |
| `duoduo-ai/.../support/IpRateLimiter.java`（**新增**） | 每 IP 固定窗口限流（键取 `X-Forwarded-For` 首段）→ 429 `RATE_LIMITED` |
| `duoduo-ai/.../support/DashScopeClient.java`（**新增**） | JDK `HttpClient` 直连 OpenAI 兼容 REST，**不引 SDK**；超时/非 2xx → 503 `DEGRADED`；日志只记耗时与状态 |
| `duoduo-ai/.../service/ChatService.java`（**新增**） | `SYSTEM_PROMPT` 五条硬规则（**只能推荐候选列表内商品** / 价格分→元 / 引导登录 / 无候选如实说 / 150 字内）；三形态 `mock`/`llm`/`unconfigured` |
| `duoduo-ai/.../controller/AiController.java`（**新增**） | `POST /api/duoduo/chat`、`GET /api/duoduo/capabilities` |
| `duoduo-product/.../controller/ProductController.java` + `service/ProductService.java`（**改**） | 新增 `GET /api/products/internal/candidates?keyword=&limit=`：压掉标点后切 **2~3 字滑窗词元** OR 匹配 title/subtitle，按「命中词元数 + 销量」重排取前 N（默认 8、上限 10） |
| `duoduo-gateway/.../application.yml`（**改**） | 新增 `duoduo-ai` 路由（`Path=/api/duoduo/**`）；product 路由加 `/api/admin/search/**` |
| `docker-compose.yml`（**改**） | 新增 `duoduo-ai-app`（容器 `duoduo-app-ai`，8086）+ gateway `depends_on` |
| 前端 `types.ts` / `endpoints.ts` / `pages/AiGuru.tsx`（**新增**）/ `App.tsx` / `components/Layout.tsx`（**改**） | `/ai` 对话页（**刻意不套 `RequireAuth`**）：气泡 + 推荐卡片 + 带角色回传历史（`HISTORY_LIMIT=6`）+ 429 友好提示 + 四个快捷问题 + 未登录底部引导；一级导航加「AI 导购」（未登录也显示） |
| `scripts/ai-smoke.ps1`（**新增**） | **16 项**：推荐 id 逐个经公开详情验证 / 标题与库内**逐字同源** / 无 `sellerId` 泄露 / 空与超长 400 / 连发 30 次**必现 429** / `capabilities` 口径一致 |

#### A10 · Elasticsearch 检索（商品域）

| 文件 | 变更 |
|---|---|
| `duoduo-product/pom.xml`（**改**） | 加 `spring-boot-starter-data-elasticsearch` + 拼音库（先引 `com.github.promeg:tinypinyin:2.0.3`，**同轮内改判为 `com.belerweb:pinyin4j:2.5.1`** —— 前者不在 Maven Central，见第十七轮「CI 三连红」记录与 T-59） |
| `duoduo-product/.../application.yml`（**改**） | `spring.elasticsearch.uris`（连接超时 300ms / socket 2s）；`duoduo.search.{enabled:false, sweep-interval-ms:15000, synonyms:…}`；**`management.health.elasticsearch.enabled=false`**（ES 缺席不得拖垮容器健康检查，否则健康门会杀掉降级链路本身） |
| `duoduo-product/.../ProductApplication.java`（**改**） | 加 `@EnableScheduling` |
| `duoduo-product/.../support/search/SearchSynonyms.java`（**新增**） | 同义词分组解析 + `variants(keyword)` 生成**不含原文**的替换变体（去重、上限 8）。类注释记录「为什么不挂 ES 同义词分析器」的实测依据 |
| `duoduo-product/.../support/search/SearchIndexer.java`（**新增**） | 单索引 `duoduo-search`（`pet_text` / `pinyin_ngram` / `pinyin_query` 三分析器，ngram 2~10 且**两侧都切分**）；`ready()` 守卫（**所有写操作前置**）；`refreshIndex()` |
| `duoduo-product/.../support/search/EsSearchEngine.java`（**新增**） | 检索与筛选全量平移（`status=ON_SALE`、listing 加 `auditStatus=APPROVED`）；关键字=**原文分支 + 各同义词变体分支**，每支含 multi_match 与拼音 match；**ES 只返回 id 顺序页** |
| `duoduo-product/.../support/search/SearchSyncJob.java`（**新增**） | 15s 增量扫 `updated_at`（**兼补偿**）+ 启动 `ApplicationReadyEvent` 全量对齐 + 清孤儿 + `status()` |
| `duoduo-product/.../controller/SearchAdminController.java`（**新增**） | `/api/admin/search/reconcile` + `/reindex`（`OperatorGuard`）；**只挂 `@ConditionalOnProperty`**（`@ConditionalOnBean` 依赖 bean 注册顺序、可能随机 404） |
| `duoduo-product/.../service/{ProductService, ListingService}.java`（**改**） | `ObjectProvider<EsSearchEngine>` 可选注入；ES 异常**原地降级 MySQL（只记 WARN）** |
| `docker-compose.yml`（**改**） | 新增 `duoduo-es`（`profiles: ["search"]`，elasticsearch:8.11.4，single-node + security off + 512m 堆 + healthcheck）；product 加 `DUODUO_SEARCH_ENABLED/DUODUO_ES_URL/DUODUO_SEARCH_SWEEP_MS` |
| `scripts/search-smoke.ps1`（**新增**） | **40 项**：就绪检查 → 运营会话与接口可用性（匿名 401 / 普通 403 / 运营 200）→ 夹具（含**标题自检**）→ 全量重建与对账（三空）→ 拼音容错（**含 MySQL 反证 + 负例**）→ 同义词容错 → 上下架一致性 → 降级（停 ES → 仍 200 → 恢复自动重生效）→ 清理与孤儿回收 |

#### 顺带修掉的两个真缺陷（不是新功能）

| 缺陷 | 根因 | 修法 |
|---|---|---|
| **`run-all-smoke.ps1` 名不副实**：「一键全量」报 18 套全部通过，而目录里有 19 套 —— 最基础的 `smoke.ps1` **从来没被跑过** | 用 `-Filter '*-smoke.ps1'` 收集脚本，**通配符要求名字里带 `-s`**，`smoke.ps1` 不匹配 | 改 `-Filter '*smoke.ps1'`（仍显式排除自身防递归）。**「全量通过」的套数必须与目录实际套数对得上** |
| **`LongToStringJacksonModule` 白名单漏列**（见 `docs/agent/08` **T-56**） | `PageResult.total` 等 **20 余个金额/计数字段**（`total`/`gmv`/`custodyAmount`/`platformFee`/`*Count` …）不在 `NUMERIC_LONG_FIELDS` → 被当"标识"转成**字符串**下发；于是 `smoke.ps1` 的 `$p.total -ge 3` 在 PowerShell 里**退化成字典序比较**（`"15" -ge "3"` 为 False）而恒失败 —— **类型错误伪装成业务失败** | 用脚本把「DTO 里所有 `long` 字段」与白名单做**差集**逐个按语义判据核对，补齐金额/计数/时长字段；并在 `smoke.ps1` **新增一条断言直接检查原始文本**（`"total"\s*:\s*\d`），让同类问题**当场现形**而不是伪装成业务失败。**必须走 T-41 全流程**（`clean install` + `build --no-cache` + `up -d --force-recreate`） |
| 附带：`ai-smoke.ps1` 取值路径写错 | `ProductDetail` 是 `{card, detail, …}`，脚本读了 `data.title`（不存在）→ 断言恒失败 | 改 `data.card.title` |

#### 文档与工程

| 文件 | 变更 |
|---|---|
| `scripts/run-all-smoke.ps1`（**改**） | 支持过滤；**ES 前置检查**（ES 未运行时**显式跳过并列入汇总**，不静默略过）；过滤器 bug 修复 |
| `scripts/export-openapi.ps1`（**改**） | 服务 5 → **6**；产出 `docs/openapi/ai.json`（**125 端点**） |
| `.github/workflows/ci.yml`（**改**） | 模块 9 → 10、容器 9 → 10；注释里冒烟套数同步 |
| `README.md` / `AGENTS.md` / `docs/PLAN.md`（**改**） | 容器数、端口、模块数、冒烟口径、端点总数、目录树、变更记录 |
| `docs/agent/02 · 04 · 05 · 06 · 07 · 08`（**改**） | 代码地图（AI 域 + 检索支撑件 + 新增脚本）、契约（§7.1 AI 域、候选召回、对账/重建、错误码 `RATE_LIMITED`/`DEGRADED`）、任务手册（无状态服务参照）、门禁表（AI 与检索两行）、A9/A10 验收落地、**新增 T-47~T-56 十个坑** |
| `docs/handover/00 · 01 · 02 · 05 · 07 · 08`（**改**） | 交接包口径与自测资产同步（10 容器 / 125 端点 / 开关 ES 的说明 / AI 与检索章节 / 历史缺陷表） |

#### CI 三连红与修复（同轮内发现，T-59）

A10 落库后的三次 push（`0927f7c` / `b713fd9` / `993d46f`）CI **全部失败、且都在 46s~1min 内**（正常 4m51s），全部挂在 build job 的「后端编译」——而本地全绿。这才是本轮真正要紧的缺陷：

| 环节 | 结论 |
|---|---|
| 根因 | 新依赖 `com.github.promeg:tinypinyin:2.0.3` **不在 Maven Central**（`repo1.maven.org/maven2/com/github/promeg/` 整个 404 —— TinyPinyin 只发布在已停服的 JCenter）。本机不报错是因为**全局 `settings.xml`（`maven/conf/settings.xml`）配了 aliyun 镜像且已缓存**（`_remote.repositories` 记录来源 `aliyunmaven`）——**「依赖不存在」被本地镜像与暖缓存完全掩盖** |
| 修复 | `duoduo-product` 换 `com.belerweb:pinyin4j:2.5.1`（Central 在架十余年、零传递依赖）；`SearchIndexer.pinyin()` 改 `PinyinHelper.toHanyuPinyinStringArray`（无音调、小写、多音字取首个读音）。**不选「给 pom 加第三方仓库」来绕** —— 那是把「依赖不存在」转嫁给每个构建环境 |
| 验证 | 重建 product 容器后 `search-smoke.ps1` **40/0**（拼音召回 `youquanliang`、非子串负例、停/启 ES 降级全过 —— 多音字行为与原库一致）、`smoke.ps1` **55/0**；再以「**`-gs` 压掉全局镜像 + 全新 localRepository**」复刻 CI 的 Central-only 解析路径验证（见 08 篇 T-59 的做法） |
| 排障判据 | **本地绿 + CI 秒挂 + 挂在 mvn package → 先怀疑依赖在 Central 不存在，而不是怀疑代码**。验真一步到位：`curl -o /dev/null -w "%{http_code}" https://repo1.maven.org/maven2/<groupId路径>/<artifactId>/<version>/<artifactId>-<version>.pom` |
| **远端终验** | 修复提交 `ced79c9` 推送后，远端 Actions run #11 **Success / 总时长 4m 24s**（编译 54s + 冒烟子集 3m 22s），**三连红闭环**；用户以 Actions 页截图确认。附带观察：两条 Node.js 20 deprecated 警告为 GitHub 平台侧对 `actions/*@v4` 的 runtime 强制升级提示（node20 → node24），**不失败、非本仓代码问题**，后续升 action 大版本即可消除 |

**自测**：全量 `mvn -DskipTests clean install` **10 模块 BUILD SUCCESS**；改公共模块后按 T-41 走 `build --no-cache` + `up -d --force-recreate`（6 个依赖 `common-web` 的服务）→ **10 个容器全 healthy**；`scripts/run-all-smoke.ps1` **共 19 套 / 通过 19 套 / 失败 0 套（`ALL_EXIT=0`，合计 1424 项）** —— 含 `smoke.ps1` 55/0（**修复前它从未被"一键全量"跑过，且有一条失败断言**）、`ai-smoke` 16/0、`search-smoke` 40/0；product 启动日志 `ES 索引已创建 index=duoduo-search（同义词由应用侧替换，不挂分析器）` + `ES 全量对齐完成 实体总数=28 成功=28`；ES `_settings` 确认 `max_ngram_diff: 10` 与三分析器；`search-smoke` 实测下架 **15.3s** 消失 / 重新上架 **15.3s** 回归、停 ES 仍 200、恢复 **1.6s** 自动重生效；序列化修复实况核验：`"total":15`（数字）、`marketPrice` 数字；OpenAPI 重导出 **125 端点**；前端 `tsc --noEmit` 零错误。**以上在 pinyin4j 替换后全部复跑过一遍（见 CI 三连红与修复）**。

**遗留**：① A9 的 `llm` 形态**待用户提供**百炼（DashScope）**API Key** 联调（`mock`/`unconfigured` 为设计内行为，不阻塞）；② `admin-smoke` 仍未入 CI 子集（维持 356 项口径）；③ `docs/openapi/*.json` 中 AI 域的 schema 以 `mock` 形态导出，凭据接入后建议重导一次。

#### A9 补完：DashScope Key 验证与 llm 形态联调（用户提供 Key 后）

用户提供百炼（DashScope）API Key 后，先验证 Key 有效性，再把 A9 从「mock 可用」推进到「llm 真跑通」。

| 环节 | 结论 |
|---|---|
| Key 验证 | 真 Key `200`（0.70s，`content:"连通"`）；**错 Key `401 invalid_api_key`（反证鉴权确实生效）**；`qwen-plus` 可用、无需换模型 |
| **缺陷 1（T-60）** | 接真模型后暴露：正文只推符合预算的 2 款，`recommendations` 却下发全部 4 条候选（含超预算 328 元款）——**卡片与话术互相打脸**。根因：提示词说「候选里的商品」被理解成「把候选都给出去」，且服务端把**整个候选列表**原样下发，模型的选择结果没用上 |
| 修复 1 | 提示词明确「候选是**可选集合**不是必推清单」+ 让模型**显式输出选中 id** `{"ids":[...]}`，服务端拿 id 到候选里**反查**（`selectByIds`），不在候选中的丢弃 —— 把「不得编造」从提示词约束升级成**类型约束**；选择场景加 `max_tokens=512` |
| **缺陷 2（T-61）** | 多轮**第二轮之后全失效**：「那最便宜的是哪个？」回「暂时没找到匹配的商品」——历史传了等于白传。根因：召回是**关键词词元匹配**，而 `chat()` 只拿**当前消息**当关键词，追问句不含商品词 → 召回必空 |
| 修复 2 | 新增 `recallKeyword()`：把历史里**用户**提问由近及远并入召回查询串（**多轮**而非仅上一轮 —— 连续追问时上一轮同样无商品词；**只并用户不并助手** —— 助手回复含商品标题会把召回带偏） |
| 变更文件 | `duoduo-ai/.../service/ChatService.java`（核心）、`.../support/DashScopeClient.java`（`maxTokens` 重载）、`scripts/ai-smoke.ps1`（16→**23 项**）、**新增 `.env.example`**（`.gitignore` 早写了「只提交 .env.example」，文件一直缺）。**对外契约未变**，前端零改动 |
| 自测 | **mock 形态 `ai-smoke` 23/0**；**llm 形态 23/1**（唯一失败是脚本断言的「mock 模式标识」，llm 下必然不符；**关键的「推荐不超预算」断言 PASS**）；**全量 `run-all-smoke.ps1` 19 套全绿 / 0 失败（1464 项）**；前端 `npm run build` 82 模块零错误。真机用例：预算 200/50 元、三轮连续追问、「汽车轮胎」无匹配、无 history 对照组 |
| 启用方式 | `export DASHSCOPE_API_KEY=... && export DUODUO_AI_MOCK_ENABLED=false && docker compose up -d --force-recreate duoduo-ai-app`（密钥只走环境变量，不落盘）；`capabilities` 应返回 `"mode":"llm"` |
| 遗留 | 🔐 **Key 需轮换**（明文出现在对话中，请吊销重建）；模型可选 `qwen-max`；对话仍不落库（v1 设计）；流式输出未做 |

### 2026-09-24（第十六轮 · A7 运营账号管理页）

| 文件 | 变更 |
|---|---|
| `docs/sql/01-duoduo-user.sql`（**改**） | 预留注释兑现为实表：`t_audit_log`（id 雪花 / actor_id / actor_phone / action / target_id / detail / created_at；**只追加、无 updated_at**——审计价值在不可篡改的顺序历史）；预留清单同步标注已落地 |
| `docs/sql/incremental/09-a7-audit-log.sql`（**新增**） | `CREATE TABLE IF NOT EXISTS` 幂等风格（T-46 判据通过：全新表，与从零 DDL 不冲突，可安全进 initdb.d） |
| `duoduo-user/.../db/schema.sql`（**改**） | 同步 `t_audit_log`（H2/单测口径） |
| `duoduo-user/.../entity/AuditLogEntity.java` + `mapper/AuditLogMapper.java`（**新增**） | 审计日志实体与 Mapper |
| `duoduo-user/.../config/UserServiceConfig.java`（**改**） | 补 MyBatis-Plus 分页拦截器（`maxLimit=100` 与交易域同口径；运营列表首次用到分页） |
| `duoduo-user/.../dto/UserDtos.java`（**改**） | 加 `AdminUserView`（**脱敏**：无实名姓名/身份证/完整手机号）、`RoleChangeRequest`、`RoleChangeResult`（notice 含「重新登录生效」） |
| `duoduo-user/.../service/AdminUserService.java`（**新增**） | 列表：`OperatorGuard.requireOperator()` + role 过滤 + keyword 模糊匹配手机号/昵称 + 分页；改角色：两层权限门（进后台 OPERATOR/ADMIN → 改角色仅 ADMIN）、角色白名单、**自己降权 422**、**最后一个 ADMIN 不可降 422**（先数后改）、同角色幂等 200 不留审计、写 `t_audit_log`、notice 提示重新登录生效 |
| `duoduo-user/.../controller/AdminUserController.java`（**新增**） | `GET /api/admin/users` + `POST /api/admin/users/{id}/role`（guard 在 Service 层，与 AdminListingController 同先例） |
| `duoduo-gateway/.../application.yml`（**改**） | user 路由显式挂 `/api/admin/users/**`（不进白名单；漏挂只 404 不报错——显式列举纪律） |
| `frontend/src/api/types.ts` + `endpoints.ts`（**改**） | A7 三类型 + `adminApi.adminUsers` / `adminChangeRole` |
| `frontend/src/pages/admin/Operators.tsx`（**新增**） | 检索（role/keyword）+ 分页表格 + 改角色 Modal（notice 原样展示）；改角色入口仅 ADMIN 可见（后端 403 是硬边界，前端隐藏只是少撞一次墙） |
| `frontend/src/App.tsx` + `pages/admin/shared.tsx`（**改**） | 路由 `/admin/operators`（RequireOperator）+ AdminTabs 第 7 页签「运营账号」 |
| `scripts/admin-smoke.ps1`（**改**） | 49 → **72 项**：新增 A7 23 项断言（401/403/脱敏/检索/两层权限门/422 三连/提权 + notice/旧令牌快照/重登生效/审计逐字段/幂等不留痕/最后 ADMIN 动态口径/夹具自建自毁） |
| `docs/agent/04`（**改**） | 端点表加 2 行（脱敏与 422/403/幂等语义） |
| `docs/openapi/user.json` + `endpoints.md`（**改**，重导出） | user 22 → **26**（A13 +2、A7 +2），全站 116 → **120 端点**，与 04 篇保持一致 |
| `docs/agent/07`（**改**） | A7 标 ✅（实际交付 + 自测） |
| `docs/agent/02` + `docs/handover/00/01/07`（**改**） | 冒烟口径 1312 → **1335**；admin-smoke 条目与 A7 证据 |
| `README.md` + `AGENTS.md`（**改**） | 冒烟基线 16 套 1335 项 |

**自测**：`t_audit_log` 增量脚本实跑（DDL-OK）；全量 `mvn clean install` EXIT=0；user/gateway 镜像重建 + 容器 healthy；`scripts/admin-smoke.ps1` **72/0 全绿（PS5.1）**——首跑曾 11 项失败，根因是**脚本取值路径错误**（认证响应 `data` 直接是 `UserView`（令牌走 httpOnly Cookie），误写 `data.user.id` → `[long]$null`=0 → URL id=0 → 404 连锁），修正为 `data.id` 后全绿；前端 `tsc --noEmit` 零错误。

**遗留**：admin-smoke 暂不入 CI 子集（子集维持 356 项口径；如需可将 admin-smoke 加入，代价约 +2 分钟/次）。

### 2026-09-23（第十五轮 · A6 契约导出与 CI + A13 小程序骨架）

| 文件 | 变更 |
|---|---|
| `pom.xml`（**改**） | dependencyManagement 加 `knife4j-openapi3-jakarta-spring-boot-starter:4.5.0`（版本号属性原有但从未被引用） |
| `duoduo-common-web/pom.xml`（**改**） | 引入 knife4j starter —— 业务服务全继承（common-web 仅业务服务依赖、网关不依赖），**一处声明全站生效** |
| `duoduo-{user,product,trade,fulfilment,marketing}/src/main/resources/application.yml`（**改** ×5） | 追加 `springdoc`（api-docs 开 / swagger-ui 关）+ `knife4j` 配置块；注释写明安全边界（网关显式白名单不路由 `/v3/api-docs`、`/doc.html`） |
| `scripts/export-openapi.ps1`（**新增**） | 从 5 个服务的 `/v3/api-docs` 导出 JSON + 生成 `endpoints.md` 端点摘要（人读，与 04 篇比对用） |
| `docs/openapi/*.json ×5 + endpoints.md`（**新增产物**） | user 22 / product 29 / trade 37 / fulfilment 19 / marketing 9 = **116 端点**，与 04 篇逐条核对一致 |
| `.github/workflows/ci.yml`（**新增**） | 双 job：`build`（JDK21 编译 9 模块 + Node20 前端 tsc/vite build）；`smoke`（起 Docker 全栈 → 等 9 容器 healthy → pwsh 冒烟子集 → 失败即 job 失败 → 日志 artifact）。适配点：`curl.exe` shim（脚本里的 Windows 习惯）、`ci-run.ps1` 在 Linux 自动切 `pwsh` |
| `scripts/ci-run.ps1`（**新增**） | 通用冒烟子集执行器：`-Scripts` 指定脚本集，逐套落盘 `logs/ci/`，兼容两种汇总行格式，失败清单逐条打印，**禁止传入自身 / run-all-smoke**（防递归） |
| `miniprogram/**`（**新增，A13 骨架**） | `project.config.json`（测试号 touristappid）+ `config.js`（BASE_URL / WECHAT_LOGIN_READY 开关）+ `utils/api.js`（包络解包 / Bearer 令牌 / 401 跳登录 / 错误文案，与 Web 端 client.ts 同口径；ID 一律字符串）+ 5 页面（index 检索 / product 详情加购 / cart 结算下单（取默认地址 `addressId`，A8 语义）/ orders 沙箱支付预下单+轮询 / login 手机号回退）+ `README.md`（3 步跑起来 + 下一轮待办） |
| `docs/agent/07-待办事项与验收标准.md`（**改**） | A6 标记 ✅（实际交付 / 自测 / CI Linux 适配点 / 待验证：Actions 首跑）；A13 改 🔄 并记第十五轮进展；取任务顺序更新 |
| `README.md`（**改**） | 顶部第十五轮说明；冒烟节加 CI / 契约导出入口；目录树加 `.github`、`miniprogram`、`docs/openapi` |
| `AGENTS.md`（**改**） | 冒烟基线行补 CI 子集入口 |
| `docs/agent/02-代码地图与现状.md`（**改**） | 脚本资产表加 `export-openapi.ps1` / `ci-run.ps1` / `ci.yml` / `docs/openapi` |
| `docs/handover/00、01、07`（**改**） | 进度快照加 A6/A13；上手步骤加 CI 与契约导出；测试证据加 CI 子集 |

**自测**：`mvn clean install` 9 模块 EXIT=0；fat jar 内确认含 springdoc/knife4j（T-41 判据：看 jar 而非退出码）；重建 9 容器全 healthy；5 服务 `/v3/api-docs` 全 200；本地实跑 CI 子集 **356/0 全绿**（smoke 54 / trade 87 / order 77 / address 82 / id-precision 56）；workflow YAML 解析校验通过。

**遗留**：① CI 在 GitHub Actions 的**首次实跑**待观察（私有仓库需确认 Actions 已启用，免费额度每月 2000 分钟）；② A13 下一轮做 `code2Session` 后端端点；③ 云端台账 v18 同步因**腾讯文档连接器未连接**（`personal=not_connected`，需用户在连接器页授权）暂挂起。

### 2026-09-23（第十五轮续 · A13 后端 code2Session 登录 + 连接器打通与台账 v18）

| 文件 | 变更 |
|---|---|
| `docs/sql/incremental/08-a13-wechat-bind.sql`（**新增**） | `t_user` 加 `wx_openid VARCHAR(64) NULL` + **唯一键 `uk_user_wxopenid`**；存量库增量脚本，重复执行报 1060 属预期（与 06 同口径） |
| `docs/sql/01-duoduo-user.sql` + `duoduo-user/.../db/schema.sql`（**改**） | t_user 建表语句同步加列与唯一键（新库口径一致） |
| `duoduo-user/.../entity/UserEntity.java`（**改**） | 加 `wxOpenid` 字段 + getter/setter |
| `duoduo-user/.../dto/UserDtos.java`（**改**） | 加 `WechatLoginRequest(@NotBlank String code)` |
| `duoduo-user/.../support/WechatGateway.java`（**新增**） | code2Session 客户端三分支：mock（`duoduo.wechat.mock-enabled=true` 显式开启 + code 前缀 `mock:`，生产禁开）→ real（`java.net.http.HttpClient` 调 jscode2session，errcode≠0 → 401）→ 未配置（**503 DEGRADED**，不用 422 错怪调用方）；secret 只走环境变量，日志只记 openid 前 4 位 + 长度 |
| `duoduo-user/.../service/AuthService.java`（**改**） | 加 `wechatLogin`（未绑定 → 422 引导先登录再绑定，**不做自动注册**——微信授权拿不到手机号；封禁账号同样拒绝）与 `wechatBind`（同 openid 幂等 200 / openid 属他人 409，并发由唯一索引兜底）+ `findByOpenid` |
| `duoduo-user/.../controller/AuthController.java`（**改**） | 加 `POST /api/auth/wechat`（免登；小程序无 Cookie，令牌只走响应体）与 `POST /api/auth/wechat/bind`（`UserContext.requireUserId()`） |
| `duoduo-gateway/.../GatewaySecurityProperties.java`（**改**） | 白名单加 `/api/auth/wechat` 一条；**`/wechat/bind` 刻意不放行**（需登录态，放行等于可把任意微信绑到自己账号） |
| `duoduo-user/src/main/resources/application.yml`（**改**） | 加 `duoduo.wechat.{appid,secret,mock-enabled}`，均走环境变量（mock 开关用 `DUODUO_WECHAT_MOCK_ENABLED`，A5 命名纪律） |
| `docker-compose.yml`（**改**） | `duoduo-user-app` environment 加 `WECHAT_APPID` / `WECHAT_SECRET`（默认空）/ `DUODUO_WECHAT_MOCK_ENABLED`（**本地/CI 默认 true**，生产必须去掉） |
| `scripts/wechat-auth-smoke.ps1`（**新增**） | 26 项断言：未绑 422 + 引导文案 / 降级 503 + DEGRADED / 绑定 200 + 库内校验 / 幂等 200 / 绑定后登录同 userId / 抢绑 409 且不篡改 / 空 code 400×2 / bind 免登 401 / 响应不含 openid 全文；动态断言总数守卫 |
| `docs/agent/04-接口契约全集.md`（**改**） | 白名单行加 `/api/auth/wechat`（bind 不放行注释）；端点表加 2 行（422/503/409/幂等语义） |
| `docs/agent/07-待办事项与验收标准.md`（**改**） | A13 段补「后端补齐（已完成）」：DDL / 端点 / 三分支 / 自测 26 项 / 余下真机待 AppID |
| `miniprogram/README.md`（**改**） | 头部改为「后端已就绪、`WECHAT_LOGIN_READY` 待开」；待办清单更新 |

**自测**：DDL 实跑并验证列 + 唯一索引存在；`mvn clean install` 9 模块通过；重建 user + gateway 容器 healthy；**`wechat-auth-smoke.ps1` 26/26 全绿（GUARD-OK）**；CI 子集回归 **356/0 全绿**（54/87/77/82/56，与 A6 基线一致）。

**本轮其他进展**：腾讯文档连接器已由用户授权打通（`tdoc_init` READY + 实调 `manage.query_file_info` 验证通过），**云端台账 v17 → v18 覆盖写成功**（`base_version 17 → new_version 18`）。

### 2026-09-24（第十五轮续 · CI 首跑打通：4 轮失败定位与修复）

| 提交 | 内容 |
|---|---|
| `12d155b` | 依据 Run #1/#2 日志定位：① 16 套冒烟脚本错误响应体读取是 PS5.1-only 写法（CI pwsh 7 读不到）→ 统一双路径兜底；② mysql healthcheck 加 `start_period 120s`；③ setup-java v4→v5 |
| `f7a2248` | Run #3 诊断日志抓到真凶：**增量 SQL（06/08）被容器初始化目录加载，全新建库必撞 1060 → mysql 容器退出 → 依赖等待赛跑时输时赢**（Run #2 侥幸过）。修复：06/07/08 移入 `docs/sql/incremental/`（不进挂载目录），docs 同步路径；登记 **T-46**，顺带补登 **T-44** |
| `5be6a67` | Run #4 仍挂同两条 → 本地下载 pwsh 7.4 实测：PS7 的 ErrorDetails.Message 是**多行 + \u 转义**形态（curl 实锤服务端发的是真中文），且响应流已被 PS7 释放（ReadAsStringAsync 必抛异常）。修复：ReadErrBody 改 ErrorDetails 优先；address「拒单文案」断言改匹配 `body.message`（JSON 解码后是真中文）。**本地 pwsh 7.4 预演 smoke 54/0 + address 82/0** |
| **Run #5 ✅** | **GitHub Actions 首次全绿**：编译检查 ✅ + 冒烟子集 356 项 ✅（`5be6a67`）。A6 的「CI 待验证」正式关闭；agent 08 踩坑登记 **T-44 / T-45 / T-46** |

### 2026-09-23（第十四轮 · A8 收货地址簿 + T-39 雪花 ID 精度根治）

| 文件 | 变更 |
|---|---|
| `duoduo-common-web/.../LongToStringJacksonModule.java`（**新增，本轮核心**） | **全站 ID 精度保护**。规则 =「`Long`/`long`/`BigInteger` **默认转字符串**」+「字段名命中 `NUMERIC_LONG_FIELDS` 白名单（金额 / 计数 / epoch 毫秒时间戳三类）的**保持数字**」，用 `BeanSerializerModifier.changeProperties()` 把命中白名单的字段 writer 换成 `NumericLongPropertyWriter`（`gen.writeNumber`）绕过全局 ToString 序列化器。**判据是字段语义**（前端会不会拿它做算术），不是 Java 类型 —— 全站金额字段 63 处都是 `Long`，一刀切会让前端 `price / 100` 算出 `NaN`。**漏标方向安全**：新加 id 字段无需任何操作即受保护。⚠ 白名单**必须用 `Set.copyOf(Arrays.asList(...))`**：`Set.of()` 遇重复元素在**运行期**抛 `IllegalArgumentException: duplicate element`（编译期不报）。反序列化与 `@PathVariable Long` 不受影响 |
| `duoduo-common-web/.../WebAutoConfiguration.java`（**改**） | 新增 `@Bean LongToStringJacksonModule`（供 Feign / RestTemplate 等非 MVC 场景），并在 `extendMessageConverters` 里给每个 `MappingJackson2HttpMessageConverter` 挂上该模块；两者都挂是刻意的 —— 前者让"隐藏规则"显式出现在装配清单里，读代码的人不必翻 `extendMessageConverters` 才知道 id 是字符串 |
| `duoduo-user/`（**A8 新增文件**） | `entity/UserAddressEntity`、`mapper/UserAddressMapper`、`service/AddressService`（**`fullAddress()` 是全站唯一拼接处**：省+市+区+明细直接相连，直辖市省=市只留一次；设默认/删除在**事务内**保证"恒有且仅有 1 条默认"）、`controller/AddressController`（6 用户端点 + `GET /internal/snapshot`）、`support/InternalTokenFilter`（内部凭据，失败关闭）；`dto/UserDtos` 增地址相关 DTO；`resources/db/schema.sql` 增表 |
| `docs/sql/incremental/07-a8-user-address.sql`（**新增**） | `t_user_address`（user_id / 收货人 / 电话 / 省市区 / 明细 / 标签 / 是否默认 / 时间戳）；`uk_addr_user_default` 之类约束 + 覆盖索引 `(user_id, is_default)` |
| `duoduo-trade/.../client/{UserAddressClient,UserAddressGateway}.java`、`client/dto/AddressCallDtos.java`（**新增**） | 交易域直连 8081 调内部快照接口取收货信息（`X-Internal-Token`，**不进网关**）；查不到或归属不符 → 拒单（归属校验**必须由服务端做**，这也是接口只收 `addressId` 不收地址内容的原因） |
| `duoduo-trade/.../{service/OrderService.java,dto/OrderDtos.java}`（**改**） | 下单新增可选 `addressId`：传了**只认它**（直填三字段被忽略，避免两套来源冲突），为空才回落旧逻辑；收货信息在**下单瞬间写快照**；`receiver*` 三字段上的 `@NotBlank` **移除**（否则"只传 addressId"的新用法会直接 400），改在服务层显式校验抛 `BizException.validation` → **400 / `VALIDATION_ERROR`**（⚠ 与"购物车为空"的 **422 / `BUSINESS_RULE`** 不是同一码，冒烟里刻意区分以防假绿灯） |
| `frontend/src/api/types.ts`（**改**） | 顶部「约定」扩写为完整章节（`MAX_SAFE_INTEGER` 说明 + `Number('...704') === '...700'` 示例 + 两条纪律 + 禁止项）；**38 个 id 类字段**由 `number` 改 `string`（`id`/`parentId`/`categoryId`/`brandId`/`sellerId`/`skuId`/`spuId`/`addressId`/`templateId`/`buyerId`/`userId`/`petId`）；`ProductQuery.categoryId`/`brandId` 同步 |
| `frontend/src/api/endpoints.ts`（**改**） | **25 处** id 形参类型 `number` → `string`；新增 `addressApi`（list / create / update / remove / setDefault / getDefault） |
| `frontend/src/pages/Addresses.tsx`（**新增**） | 地址簿页：`LABEL_PRESETS`（家/公司/学校）、`PHONE_RE` 校验、新建时 `isDefault: addresses.length === 0`（首个自动默认）、编辑已是默认时复选框 `disabled`、删除确认 Modal 提示「历史订单保存的是下单当时的地址快照」 |
| `frontend/src/pages/Checkout.tsx`（**改，含真缺陷修复**） | 地址二选一（单选列表 vs 手动填写）：payload 三元确保**只传其中一种**、两种分支都必须存在 `<form id="checkout-form">`（否则提交按钮变死）。**★ 修复地址加载竞态**：初始化守卫原写 `addressList === null`，而 `useAsync` 初始 `data` 是 **`undefined`** → 首次渲染即锁定"空地址簿"分支、把用户推进手动填写模式（地址渲染出来了却选不上、提交报"请填写收货人姓名"）→ 改为 **`addrLoading` 门控 + 判 `undefined`**；"正在加载…"占位同步由 `addrLoading && addressList === null`（恒 false 的死代码）改为 `addrLoading`。见 T-42 |
| `frontend/src/pages/{Cart,ProductDetail,ListingDetail,Search,Categories,ReviewNew,admin/Audits,Coupons,seller/Products,Me}.tsx`、`components/{CreditBadge,ReviewSection}.tsx`（**改**） | id 类型改 string 后的逐文件收口（共 13 个页面 / 组件）。要点：`ProductDetail` 的 `Number(id)` 改为直接用字符串 + 新增 `hasSpuId` 布尔哨兵（`Number.isFinite(id)` 一并改写）；`Categories` 新增 `TOP_PARENT = '0'` 常量（这是**真正的数值语义**哨兵，与雪花 id 可区分）；`Search` 新增 `rawId()`（只做非空校验不转数字，专供 `categoryId`/`brandId`） |
| `scripts/address-smoke.ps1`（**新增**） | **82 项断言 / 0 失败**（含★断言总数守卫）。覆盖 07 篇四条验收 + 归属校验（刻意统一 404）+ 内部凭据边界（无 token 403 / 错误 token 403 / 经网关无登录态 401）+ 直辖市拼接去重 + 首条自动默认 + 设默认唯一性 + 删默认顺延 + 快照不回写 + 参数边界 + 清理复原。⚠ `Sql` 函数上方注明**别用 `Sql` 回读中文**（PS 5.1 按 CP936 解码 mysql 的 UTF-8 输出会变「鐜嬩簲」），中文一律走 `HEX()` 或接口 JSON |
| `scripts/e2e/address-ui-check.mjs`（**新增**） | **64 项断言 / 0 失败**（真实 Chromium）。16 段：夹具自检 / 买家账号 / 未登录访客被 `RequireAuth` 拦截 / 登录 / 空地址簿 / 新增第一条（首个自动默认）/ 新增第二条（不抢默认）/ 设为默认（UI 唯一性）/ 编辑（默认复选框禁用）/ 删除默认（toast 告知顺延）/ 结算页自动预选默认 + 下单 / 订单详情 == 地址簿 / 改地址簿后快照不变 / 切手动填写（提交按钮不能变死按钮）/ 清理 / 前端噪声。**必须 `localhost`**（网关 CORS 白名单只认它）；`idOf()` 接收整个响应对象（误传地址对象会得到空串 → URL 变 `/api/addresses/` → `NoResourceFoundException` 500）；跨段取 id 必须**实时重拉**而非用旧快照（默认已顺延，旧 id 会 404） |
| `scripts/id-precision-smoke.ps1`（**新增**） | **56 项断言 / 0 失败**（含★守卫）。跨商品 / 用户 / 交易 / 营销四域，**核心手法：只对 `$resp.raw` 做正则断言，不做反序列化** —— 因为 PowerShell/.NET 用 `Int64`/`Decimal` 解 JSON 数字**不丢精度**，这正是所有既有冒烟"照不出 T-39"的原因。断言分三类：① id 类字段必须是**带引号的字符串**且与库内**逐字符相同**（末位不许被舍入）；② 金额/计数/epoch 时间戳必须**仍是不带引号的数字**（防"一刀切"误伤）；③ 端到端用响应里抠出的 id 反查，证明它"可用"而不只是"长得像字符串"。含"自证前提"断言：该 id 确实超过 16 位（否则脚本没有存在意义） |
| `scripts/run-all-smoke.ps1`（**新增**） | 一键跑全套冒烟并输出汇总表 + 失败清单（**排除自身**，否则自我递归）。逐套落盘 `logs/smoke/*.log` |
| `scripts/{review,order}-smoke.ps1`（**改**） | 因 T-39 修复而失效的 2 处旧断言：① `review-smoke` 从原始文本抠主键的正则 `"id":(\d+)` 匹配不到新形态 `"id":"361…"`（引号挡住了）→ 改 `"?(\d+)"?` 兼容两种形态；② `order-smoke` 的「缺收货人」用例**补上"先加购"前置**（否则会先撞上「购物车为空」的 422，是**同码不同因**的假绿灯），并加断言确认 `code == 'VALIDATION_ERROR'` 而不仅看状态码 |
| `docs/agent/08-环境运维与踩坑禁忌.md`（**改**） | 新增 **T-39**（雪花 ID 精度：判据「接口冒烟过、浏览器 4xx」→ 先怀疑 id 精度，抓包看末位是不是变成 0）、**T-40**（一刀切字符串化会让金额变 `NaN`；白名单必须用 `Set.copyOf(Arrays.asList(...))`）、**T-41**（改公共模块后业务 fat jar 未重打：`mvn package` 增量判定业务模块无变化 → 不重打 jar → 容器里跑的还是旧代码；判据「改动没生效 + 构建很快（几秒 EXIT=0）」→ **查 `target/*.jar` 的 mtime，别查退出码**；修法 `mvn clean install` + `build --no-cache` + `up -d --force-recreate`）、**T-42**（`useAsync` 的 `undefined` 被当成空列表：守卫判 `=== null` 是死代码，要用 `loading` 门控）、**T-43**（`git push` 报 `CONNECT tunnel failed, response 502` 看起来像网络全断，实为代理变量未生效：须在**同一条 Bash 命令内显式 `export` 小写** `https_proxy`/`http_proxy` 再 `git push`，推完用 `git ls-remote` 哈希复核；另注 `api.github.com` 通 ≠ `github.com` 通，GitHub connector 对私有仓可能返 404） |

### 2026-09-23（第十三轮 · A5 优惠券营销）

| 文件 | 变更 |
|---|---|
| `duoduo-marketing/`（**新增整个模块，第 6 个业务服务**） | POM 入父反应堆（reactor 变 **9 模块**）；`MarketingApplication`（`@MapperScan`）；`config/MarketingServiceConfig`（分页 + 雪花 **worker-id=5**，与用户 1 / 商品 2 / 交易 3 / 履约 4 错开）；`application.yml` / `-local` / `-mysql` 三档；`controller/{CouponTemplateController,CouponController,CouponInternalController}`；`service/CouponService` + `service/CouponReleaseJob`（TTL 补偿）；`entity/{CouponTemplateEntity,CouponEntity}` + `mapper/{CouponTemplateMapper,CouponMapper}`；`support/{CouponCodeGenerator,CouponStatus,InternalTokenFilter}`；`dto/CouponDtos`；`resources/db/schema.sql` |
| `duoduo-marketing/.../resources/application.yml`（**新增**） | 端口走 **`DUODUO_MARKETING_PORT`**（**刻意不用通用名 `MARKETING_PORT`**，避免宿主环境已有同名变量时静默改端口）；`COUPON_LOCK_TTL_MINUTES`（默认 **35**，**略大于**交易域订单超时关单 30，避免「订单还活着、券已被别人领走」）、`COUPON_RELEASE_SWEEP_MS`（60000）、`COUPON_RELEASE_SWEEP_DELAY_MS`（30000）、`COUPON_RELEASE_BATCH`（200）；内部凭据复用 `INTERNAL_TOKEN` |
| `docs/sql/05-duoduo-marketing.sql`、`docs/sql/incremental/06-a5-coupon-columns.sql`（**新增**）、`docs/sql/00-init-databases-and-user.sql`（**改**） | 新库 `duoduo_marketing`：`t_coupon_template`（运营券模板：名称/描述/类型/门槛/抵扣/总量/已领量/起止/状态）、`t_coupon`（用户券：券码/用户/模板/状态/锁定订单号/核销订单号/领取时间/过期时间），**领券幂等靠唯一索引 `uk_coupon_user_tpl(user_id, template_id)`**（一人一模板一张）；种子模板 **5001**（新客立减 5 元，满 50 减 5，1000 张）/ **5002**（满 200 减 30）；交易域增量列 `t_order.coupon_code VARCHAR(32)`（**MySQL 不支持 `ADD COLUMN IF NOT EXISTS`，重复执行报 1060 属预期**） |
| `duoduo-trade/.../client/{MarketingClient,MarketingGateway}.java`、`client/dto/CouponCallDtos.java`（**新增**） | 交易域按「**营销域是被调用方**」的纪律做**反向**取数：交易域直连 8085 调内部接口（`X-Internal-Token`，**不进网关**）；`MarketingGateway.release` 带 `scene` 参数用于日志与调用描述 |
| `duoduo-trade/.../{service/OrderService.java,dto/OrderDtos.java,entity/OrderEntity.java,resources/db/schema.sql}`（**改**） | 下单 `placeOrder` 先**锁券**（失败抛 422 带人话原因，如「满 200 元可用」）→ `paySuccess` **核销**（核销失败则整笔支付不成立）→ `cancel` / `forceClose` / 超时关单**三处**解锁（统一带 `scene` 便于排障）；`OrderView` 增 `couponCode` / `discountAmount` |
| `duoduo-gateway/.../application.yml`、`config/GatewaySecurityProperties.java`（**改**） | 新路由 `duoduo-marketing` → `Path=/api/promotions/**,/api/coupons/**`（**`/api/promotions/**` 是预留路径，确实写在配置里**）；白名单**只放行精确路径 `/api/coupons/templates`**，**不是** `/api/coupons/**` —— 白名单按**路径**而非方法匹配，放通配会把「领券」「我的券包」「内部锁券 / 核销 / 解锁」一起放成匿名可调（与既有 `/api/reviews/product/**` 同一纪律，INV-14） |
| `docker-compose.yml`（**改**） | 第 **9** 个容器 `duoduo-marketing-app`（`container_name: duoduo-app-marketing`，镜像 `duoduo/duoduo-marketing:1.0.0`，:8085，`worker-id=5`）；网关依赖加营销 |
| `frontend/src/pages/Coupons.tsx`（**新增**） | 领券中心 + 我的券包双页签（券模板卡片显示门槛 / 有效期 / `claimableReason`；券包按 `UNUSED/LOCKED/USED/EXPIRED` 筛选） |
| `frontend/src/{App.tsx,components/Layout.tsx}`、`pages/{Checkout,OrderDetail,Me}.tsx`、`api/{types,endpoints}.ts`（**改**） | 路由 `/coupons`（**刻意不套 `RequireAuth`**：领券中心是公开落地页，未登录也看得到有什么券，点「立即领取」时才引导登录）；一级导航加「优惠券」、用户下拉与「我的」页加「我的券包」；结算页选券（`undefined`=未选择→用服务端推荐 / `null`=明确不用；费用明细显示优惠与实付；提交只传 `couponCode`）；订单详情金额区展示优惠金额与券码（**有券时才显示券码行**）；新增 `CouponStatus` / `CouponType` / `CouponTemplateView` / `CouponView` / `CouponClaimResult` / `UsableCoupon` / `UsableCouponResult`，`OrderView` 加 `couponCode` |
| `scripts/coupon-smoke.ps1`（**新增**） | **145 项断言**（含★断言总数守卫）：① 领券幂等（重复领返回 `replay=true` 不报错）② 并发防超发 ③ 锁·解·核销 CAS ④ 折扣落交易域 ⑤ 生命周期闭环；另加公开只读边界 / 内部凭据 403 / 门槛文案（「满 200 元可用」）/ 券码非雪花 / sweep 幂等 / TTL 环境变量校验 / 运营建券准入 / 清理复原 |
| `scripts/e2e/coupon-ui-check.mjs` + `scripts/e2e/coupon-ui-fixture.ps1`（**新增**） | **43 项真实 Chromium 断言**（未登录态 / 领券 / 券包 / 结算选券与费用口径 / 订单详情券码 / 取消退回 / 噪声）；夹具脚本 `-Action create\|drop` 建 / 删测试数据 |
| `README.md`、`docs/agent/02·03·04·05·06·07·08`、`docs/PLAN.md`、`docs/PROJECT-STATUS.md`、`docs/ARCHITECTURE.md`、`docs/handover/00~08`、`docs/待协助问题.md` | 接口章节（新增「营销域：优惠券」）、模块与代码地图、不变式举例、契约全集（3 组端点 + 内部 4 端点 + DTO + 状态机）、开发任务手册、门禁表（改优惠券 → `coupon-smoke.ps1` + `coupon-ui-check.mjs`）、待办 A5 标记完成、容器 / 端口 / 脚本口径（8 → 9 容器、14 套 1204 项 + 5 套 180 项）、踩坑 **T-37 / T-38** |

**自测**：
- `scripts/coupon-smoke.ps1` **145 通过 / 0 失败**（守卫确认无静默跳过）；
- `scripts/e2e/coupon-ui-check.mjs` **43 通过 / 0 失败**（真实 Chromium，夹具由 PowerShell 建 / 删）；
- 前端 `tsc --noEmit` 零错误、`vite build` 通过（**79 模块**）；
- 全量编译 **九模块 BUILD SUCCESS**；重建 `duoduo-marketing-app` 容器后 **9 容器 healthy**；
- 接口冒烟累计 **14 套 1204 项**（新增 `coupon-smoke` 145 项），浏览器 UI 断言累计 **5 套 180 项**
  （原 4 套 137 项 = `review-ui 20` + `seller-ui 29` + `logistics-ui 44` + `aftersale-ui 44`；新增 `coupon-ui 43`）。

**第十三轮自测暴露并修复的 5 个问题**（前 3 个是代码缺陷，后 2 个是工程 / 环境坑）：
1. **未登录访客被标记成「可领取」**：`toTemplateView` 没把登录态传进去，未登录时前端会渲染一个注定 401 的
   「立即领取」按钮 → 加 `loggedIn` 分支，未登录时 `claimable=false` + 原因文案「登录后可领取」。
2. **`cancel` 路径漏了解锁券**：原先只有运营强制关单与超时关单会解锁，用户主动取消订单时券会一直卡在 `LOCKED`
   直到 TTL 兜底 → `cancel` 补 `releaseCouponQuietly(orderNo, "用户取消")`，三处调用统一带 `scene` 便于排障。
3. **`OrderService` 构造器漏赋值 `marketingGateway`**（编译报「可能尚未初始化变量」，编译期即拦截）；
   `MarketingGateway.release` 补 `scene` 参数用于日志。
4. **「改完源码没重建容器，冒烟假红」**（**同一坑本轮第二次踩到**）：`loggedIn` 修复写进源码后
   `duoduo-app-marketing` 容器没重建，`coupon-smoke.ps1` 首跑 **143 通过 / 2 失败**，失败项恰是
   「未登录访客应标 `claimable=false`」与「原因文案为『登录后可领取』」—— 而源码里该分支明明存在。
   排查路径：读源码确认分支存在 → 确认调用方已传 `userId != null` → 判断「**代码对但容器是旧的**」。
   修法：`docker compose build duoduo-marketing-app` + `up -d`，重跑后 **145/145 全绿**。
   教训：**改了源码必须重建对应容器再跑冒烟；`docker compose build` 之后补的改动不会进运行中的容器**。
5. **未支付订单详情页的 `/settlement` 404**（P3 前端优化点，**与 A5 无关**）：`OrderService.settlementOf()` 对
   「没有托管记录」的订单**按设计**返回 404（`OrderView.settlement` 注释即写明「未支付时为 null」），页面自行忽略；
   e2e 里已**显式排除该已知良性 404 并注明理由**。未支付时可直接跳过这次请求（记为 P3，暂不改）。

> ⚠️ **两条新踩的坑（第十三轮，已入 `08` 篇 T-37 / T-38）**：
> 1. **T-37 `docker compose build` 之后补的代码不会进运行中的容器**：表现为「代码明明改了、断言还是失败」，
>    curl / 日志都看不到新分支。**修法**：补丁写完必须重新 `mvn package` + `docker compose build <svc> && docker compose up -d <svc>`。
> 2. **T-38 本机 Node 无法创建子进程**：`spawnSync` / `execFileSync` 一律 `EBUSY errno -4082`
>    （`cmd.exe` / `powershell.exe` / `docker.exe` 都试过，两版 Node、沙箱内外都一样）→ 那种「在 Node 里
>    `execFileSync('docker', ['exec','duoduo-mysql','mysql',…])` 跑 SQL」的 e2e 夹具写法在本机跑不通。
>    **修法**：把 SQL 从 Node 里彻底移出 —— 夹具建 / 删由 `scripts/e2e/coupon-ui-fixture.ps1` 用 PowerShell
>    `& docker exec` 完成（与 `coupon-smoke.ps1` 同一姿势），Node 脚本只做 HTTP + 浏览器。

### 2026-09-20（第十二轮 · A4 信用分）

| 文件 | 变更 |
|---|---|
| `duoduo-fulfilment/.../support/CreditModel.java`（**新增**） | **信用分权重模型（单一事实来源）**：纯函数，输入事实 + 权重 → 分数与因子明细，不碰 DB / HTTP。权重表 `base 60 + 完成率 18 + 评价 16 + 售后 6 = 100`，启动校验和（架构错直接 fail-fast）；三条硬约定：**因子和 == 总分**、**无数据 = 基础分**、**单调可解释**（不设负分项） |
| `duoduo-fulfilment/.../service/CreditService.java`（**新增**） | 取数编排：交易域订单计数（Feign）+ 本域评价聚合 + 售后污点计数 → `CreditModel` 算分 → 回写用户域；**实时计算不读 `t_user` 副本**；交易域不可用抛 `ErrorCode.DEGRADED` 503（不按 0 单算） |
| `duoduo-fulfilment/.../service/CreditRefreshJob.java`（**新增**） | 定时兜底刷新（枚举已知卖家 → 逐个重算回写），后台任务独立、幂等、失败仅跳过并记日志 |
| `duoduo-fulfilment/.../controller/CreditController.java`（**新增**） | `GET /api/reviews/seller/{sellerId}/credit`（**公开只读**：分数 + 分级文案 + 因子明细 + 原始输入）；`sellerId=0` 表示平台自营 |
| `duoduo-fulfilment/.../controller/CreditInternalController.java`（**新增**） | `POST /api/reviews/internal/credits/refresh?userIds=&limit=`（`X-Internal-Token`；不传 `userIds` 刷全部已知卖家）；**没有「直接设分」入口**（分数只能由权重算出）；`userIds` 非法 → 400（不落成 500） |
| `duoduo-fulfilment/.../dto/CreditDtos.java`（**新增**） | `CreditView` / `CreditFactor` / `CreditInputs` / `CreditRefreshResult`（读接口只暴露「分数 + 可解释输入」，不含任何身份字段） |
| `duoduo-fulfilment/.../client/dto/{SellerOrderStats,CreditWriteRequest,CreditWriteResult}.java`（**新增**） | 跨域契约副本（交易域订单计数入参 / 用户域回写入参结果） |
| `duoduo-fulfilment/.../client/{OrderClient,UserClient}.java`、`mapper/{ReviewMapper,AfterSaleMapper}.java`、`support/InternalTokenFilter.java`、`resources/application.yml`（**改**） | OrderClient 加 `sellerStats`；UserClient 加 `writeCredit`；Mapper 加 `aggregateBySeller` / `countAdverseBySeller` / `distinctSellerIds`；Filter 放行内部信用分路径；权重走环境变量（默认和为 100） |
| `duoduo-trade/.../controller/OrderController.java`、`dto/OrderDtos.java`、`service/OrderService.java`（**改**） | 新增 **`GET /api/orders/internal/seller/{sellerId}/stats`**（内部凭据）：只回**订单计数**（完成/关闭/退款/总数），**不含收货人信息**（INV-9） |
| `duoduo-user/.../controller/UserController.java`、`dto/UserDtos.java`、`service/AuthService.java`（**改**） | 新增 `POST /api/users/internal/{userId}/credit`（`X-Internal-Token`，**同值不写库**，回 `changed` / `previousScore`）；注册默认分由硬编码 `100` 改为可配置 `duoduo.user.credit-default`（默认 60） |
| `duoduo-user/.../resources/application.yml`、`resources/db/schema.sql`、`docs/sql/01-duoduo-user.sql`（**改**） | `credit_score` 默认值 `100 → 60`（与 `CreditModel` 基础分对齐，杜绝「库里 100、信用分页 60」的分裂） |
| `duoduo-gateway/.../config/GatewaySecurityProperties.java`（**改**） | 白名单加精确路径 `/api/reviews/seller/*/credit`（`*` 单段通配；写接口仍在白名单之外，INV-14） |
| `duoduo-product/.../{dto/ProductDtos.java,service/ProductService.java,controller/ProductController.java}`（**改**） | `ProductCard` 增 `Long sellerId`（**显式公开例外**：商品/闲置详情页展示卖家信用所必需，与公开 `ListingCard.sellerId` 一致） |
| `frontend/src/components/CreditBadge.tsx`（**新增**） | `CreditBadge`（行内徽标，失败回 `null` 不打断页面）+ `CreditPanel`（「我的」页完整面板：大号分数 + 因子进度条 + 原始输入 + 计算时刻） |
| `frontend/src/api/{types,endpoints}.ts`、`pages/{ProductDetail,ListingDetail,Me}.tsx`（**改**） | `CreditView` 等类型 + `creditApi.seller(sellerId)`；商品页价格区挂徽标、闲置页规格表加「卖家信用」行、「我的」页改用实时计算的 `CreditPanel`（移除静态 `user.creditScore` 展示） |
| `scripts/credit-smoke.ps1`（**新增**） | **82 项断言**：A4 四条验收标准（无数据得基础分 / 区间 0~100 / 单调可解释 / 因子和=总分）+ 公开只读与凭据边界 + 售后只计 `APPROVE_REFUND` + 回写幂等 + 批量刷新 + 夹具复原；含★**断言总数守卫** |
| `README.md`、`docs/agent/02·03·04·06·07·08`、`docs/PLAN.md` | 接口章节（新增「履约域：信用分」）、代码地图、跨服务调用矩阵（2 条新调用）、不变式（INV-17）、契约全集（3 端点 + DTO + 权重表 + 业务规则）、门禁表（改信用分 → `credit-smoke.ps1`）、待办 A4 标记完成、踩坑 T-33~T-35 |

**自测**：
- `scripts/credit-smoke.ps1` **82 通过 / 0 失败**（守卫确认无静默跳过）；
- 前端 `tsc --noEmit` 零错误、`vite build` 通过；
- 全量编译 **8 模块 BUILD SUCCESS**；重建 `user/trade/fulfilment/gateway/product` 容器后 **8 容器 healthy**；
- 回归 7 套全绿：`smoke 54/0`、`review-smoke 59/0`、`aftersale-smoke 101/0`、`logistics-smoke 79/0`、`settlement-smoke 125/0`、`admin-smoke 49/0`、`seller-smoke 101/0`。
- 接口冒烟累计 **13 套 1034 项**，浏览器 UI 断言累计 **4 套 137 项**。
  （本轮回归顺带**修正一处旧口径**：`seller-smoke.ps1` 实为 **101 项**，此前文档误记为 84 项，
  累计总量随之由 1017 修正为 **1034**。）

> ⚠️ **四条新踩的坑（第十二轮，已入 `08` 篇 T-33~T-36）**：
> 1. **冒烟辅助函数少取一层 `.body`**：响应是 `{status, body, …}`，业务数据在 `body.data` 下；把取值抽成函数后
>    写成 `$resp.data.x` 恒为空，导致一批断言假失败（原始响应无此错，**重构成函数后才暴露**）。
> 2. **跨库 `INSERT … SELECT` 未写库限定名**：连接默认库是履约库，`FROM t_order` 打到不存在的表 → 插 0 行且不报错。
>    改 `FROM duoduo_trade.t_order`，并养成「造完夹具先 `COUNT(*)`」的习惯。
> 3. **TS `noUncheckedIndexedAccess` 下 `Record` 取值可空**：`?? FALLBACK` 若在同一 `Record` 里取，仍判可空 →
>    改用**独立声明的非空常量**兜底。
> 4. **函数签名与调用处参数个数不符时，PowerShell 会静默丢弃多余实参**（不报错，也不进 `$args` 报错）：
>    `trade-smoke.ps1` 的 `Call` 只声明了 4 个参数，第 5 个 headers 被静默吞掉 → 内部凭据头从未发出，
>    内部批量 SKU 接口恒 **403**（7 条断言连带失败）。**修法**：`Call` 补 `[hashtable]$headers` 参数
>    （其余 12 套脚本本就都有）；修复后 **87 通过 / 0 失败**。

**第十二轮暴露并修复的 2 个实现问题**（非脚本问题）：
1. **注册默认分与新模型冲突**：`AuthService` 硬编码 `creditScore=100`，与 `CreditModel` 基础分 60 不一致 →
   改为可配置 `credit-default`（默认 60），DDL 默认值同步为 60。
2. **`AfterSaleMapper` 漏 `@Select` import**：编译期即失败，已补；顺带把 `parseIds` 的 `NumberFormatException`
   转成 400（非法 `userIds` 不再以 500 示人）。

### 2026-09-20（第十一轮 · A2 物流轨迹）

| 文件 | 变更 |
|---|---|
| `duoduo-fulfilment/.../entity/LogisticsTrackEntity.java`、`mapper/LogisticsTrackMapper.java`（**新增**） | 轨迹节点流水实体 + Mapper（按单查正序、批量插入；无状态迁移，纯追加） |
| `duoduo-fulfilment/.../support/LogisticsNode.java`（**新增**） | 节点码白名单（7 种：`PICKED_UP` / `IN_TRANSIT` / `ARRIVED_HUB` / `OUT_FOR_DELIVERY` / `DELIVERED` / `EXCEPTION` / 自定义留位）——**推送方只能选用不能自创**，非法节点码 422 |
| `duoduo-fulfilment/.../service/LogisticsService.java`、`controller/LogisticsController.java`、`controller/LogisticsInternalController.java`（**新增**） | 摄入（批量、全有或全无、幂等撞唯一键跳过）+ 查询（正序、越权分层）。**履约域第一个内部接口** |
| `duoduo-fulfilment/.../support/InternalTokenFilter.java`（**新增**） | 本域内部凭据 Filter（校验 `X-Internal-Token`，**失败关闭**），与用户域那份同口径 |
| `duoduo-fulfilment/.../support/OrderFacts.java`（**新增**） | 统一取数口：把评价域与售后域各自那份「调交易域取订单事实」收口（两处已漂移，同一句「交易域不可用」一处 422、一处 503）；两域改用同一实现 |
| `duoduo-trade/.../client/LogisticsClient.java`、`client/LogisticsNotifier.java`、`client/dto/TrackIngestPayload.java`（**新增**） | 交易域发货后**补推「已揽收」**：`@TransactionalEventListener(AFTER_COMMIT)` 触发，Feign 调履约域内部接口；best-effort，**绝不抛出**，不阻断发货 |
| `duoduo-trade/.../service/OrderService.java` | 两条发货路径（卖家中心 / 内部凭据）共用同一段状态机，`OrderShippedEvent` 在状态机本体发布，两边都不漏 |
| `duoduo-gateway/.../application.yml` | 新增 `/api/logistics/orders/**` 路由到 `duoduo-fulfilment`（内部接口 `/api/logistics/internal/**` **不进网关**，交易域直连服务） |
| `frontend/src/components/LogisticsTimeline.tsx`（**新增**） | 共用时间线组件：正序 + 「当前：××」常驻提示 + 未发货说明性空态；行内 `data-node-code` 稳定测试钩子 |
| `frontend/src/pages/OrderDetail.tsx`、`pages/seller/OrderDetail.tsx` | 买家 / 卖家订单详情各挂一份时间线（时间线置于最显眼处——用户来看的就是「货到哪了」） |
| `frontend/src/api/{types,endpoints}.ts` | `LogisticsTrackView` 类型 + `logisticsApi.tracks(orderNo)` |
| `scripts/logistics-smoke.ps1`（**新增**） | **79 项断言**：发货即揽收 / 乱序入库仍按时间正序返回 / 重复推送幂等 / 批量全有或全无 / 无时区按 UTC / 越权 / 敏感字段裁剪 / 库存与归属复原；含★**断言总数守卫** |
| `scripts/e2e/logistics-ui-check.mjs`（**新增**） | 真实 Chromium **44 项断言**：买家/卖家同轨、未发货空态、非当事人被挡、已完成订单轨迹仍在；**6 张截图** `lg_*.png` |
| `README.md` | 新增「物流轨迹」章节；冒烟口径 11 套 856 → **12 套 935 项**，UI 断言 3 套 93 → **4 套 137 项**；第十一轮变更记录 |

**自测**：`logistics-smoke.ps1` **79 通过 / 0 失败**（守卫确认无静默跳过）；
`logistics-ui-check.mjs` **44 通过 / 0 失败**；前端 `tsc --noEmit` 零错误、`vite build` 通过（主包 171.5KB）；
回归 `review-smoke 59/0`、`aftersale-smoke 101/0`、`settlement-smoke 125/0`。8 容器 healthy。
接口冒烟累计 **12 套 935 项**，浏览器 UI 断言累计 **4 套 137 项**。

> ⚠️ **两条新踩的坑（第十一轮，已入 `08` 篇 T-31 / T-32）**：
> 1. **Write 工具生成的 `.ps1` 是 UTF-8 无 BOM**，PowerShell 5.1 按 GBK 读 → 中文乱码且语法被撞坏。
>    既有脚本都带 BOM，新建脚本照做即可（无 BOM 时用 `UTF8Encoding($true)` 补写）。
> 2. **多行续行里的 `Check "…" (…) -eq '…'` 断言会被静默跳过**：续行让 `-eq` 落到下一行被当成**参数名**，
>    参数绑定阶段抛异常 → 该断言跳过，但脚本**仍报 0 失败**。已加「**断言总数守卫**」防复发
>    ——任何一条 Check 因写法错误被跳过都会让守卫报失败。

**第十一轮暴露并修复的 2 个问题**（都是自测脚本自身写法问题，非实现缺陷）：
1. **运营断言前提写错**：订单详情按归属拦截，运营不是当事人，页面根本没渲染 → 换成更有价值的断言
   「**确认收货后轨迹不该消失**」。
2. **console 过滤漏排 404**：本测试刻意触发的 404（非当事人访问）没被排除，误报为「非预期 HTTP 错误」→ 补进白名单。

### 2026-09-20（第十轮 · A3 售后三端页面）

| 文件 | 变更 |
|---|---|
| `frontend/src/pages/afterSaleShared.tsx`（**新增**） | 三端共用：状态页签、类型文案与 hint、状态配色、进度步骤条、图片直链拆分 |
| `frontend/src/pages/{AfterSales,AfterSaleNew,AfterSaleDetail}.tsx`（**新增**） | 买家：我的售后 / 申请 / 详情（含申请平台仲裁） |
| `frontend/src/pages/seller/AfterSales.tsx`、`admin/AfterSales.tsx`（**新增**） | 卖家处理弹窗（AGREE 即退款 / REJECT 必填原因）；运营裁定弹窗（支持退款 / 驳回，说明必填） |
| `frontend/src/api/{types,endpoints}.ts` | `AfterSaleView` + 4 个请求类型；`afterSaleApi` 6 个方法、`adminApi` 2 个方法。**`id` 类型为 `string`** |
| `frontend/src/App.tsx` | 新增 5 条路由：`/after-sales`、`/after-sales/new`、`/after-sales/:id`、`/seller/after-sales`、`/admin/after-sales` |
| `frontend/src/pages/{Orders,Me}.tsx`、`seller/{Dashboard,Orders}.tsx`、`admin/shared.tsx` | 入口：「申请售后」只挂在已发货/已完成的订单上；「我的」页加「我的售后」；卖家概览与订单页加「售后处理」；后台标签页加「售后仲裁」 |
| `duoduo-fulfilment/.../dto/AfterSaleDtos.java`、`service/AfterSaleService.java` | **`AfterSaleView.id` 由 `Long` 改为 `String`** —— 18 位雪花超过 `Number.MAX_SAFE_INTEGER`，`JSON.parse` 会静默改写 |
| `scripts/e2e/aftersale-ui-check.mjs`（**新增**） | 真实 Chromium **44 项断言**：买家申请 → 卖家拒绝（空原因被拦）→ 买家申请仲裁 → 运营裁定（空说明被拦）→ 支持退款 → 回接口层确认 REFUNDED；**12 张截图** `as_*.png` |
| `README.md` | 新增「售后三端页面」章节；冒烟口径 2 套 49 → **3 套 93 项** UI 断言；第十轮变更记录 |

**自测**：`aftersale-ui-check.mjs` **44 通过 / 0 失败**；前端 `tsc --noEmit` 零错误、`vite build` 通过；
本轮改到了订单页 / 「我的」页 / 卖家端与后台共用件，故**三套 UI 自测全跑**：`review-ui-check` / `seller-ui-check` 均 `exit=0`；
回归 `aftersale-smoke 101/0`、`review-smoke 59/0`、`settlement-smoke 125/0`。8 容器 healthy。
接口冒烟 **11 套 856 项** + 浏览器 UI 断言 **3 套 93 项**。

> ⚠️ **环境坑（第十轮新踩，已入 08 篇 T-27）**：本机前端 dev server 起不来，报
> `listen EACCES: permission denied 127.0.0.1:5173` —— **不是端口被占、也不是沙箱拦的**，
> 是 Windows **排除端口段**：Docker Desktop 启动后 WinNAT 保留 `5149-5248`，5173 正在其中。
> 查 `netsh int ipv4 show excludedportrange protocol=tcp`；改 `--port 3000` + `UI_BASE=http://localhost:3000` 即可，
> **3000 本就在网关 `CORS_ORIGINS` 默认白名单里，不动任何配置**。

**本轮暴露并修复的 4 个问题（`tsc` 与 `vite build` 都发现不了，只有真实浏览器能现形）**：

1. **前端路由与入口链接命名不一致**：路由是 `after-sales`（连字符），链接写成 `/seller/aftersales`
   → 直接落到「页面不存在」。接口路径 `/api/aftersales` 与前端路由**刻意不同**，链接必须照路由写。
2. **`getByRole` 的 `name` 是子串匹配**：`{name:'处理'}` 先命中页签「待卖家处理」，弹窗根本没打开 →
   改为行内 `exact: true` 定位。
3. **用页面文本判断按钮是否消失会被 toast 误伤**：成功 toast 文案就是「已申请平台仲裁」，
   `includes` 恒为真 → 改判 `getByRole(...).count() === 0`。
4. **弹窗默认值是保守项**：裁定弹窗默认结论是「驳回」，按钮名是「确认驳回」而不是「支持退款并执行」。

**数据清理**：清掉前几轮崩溃遗留的 2 条 `PENDING` 售后挂单、4 个未降权的运营账号，
并把 34 笔被测试改过归属的订单 `seller_id` 归还平台自营（seller_id=0）。

### 2026-09-18（第九轮 · A3 售后仲裁工单）

| 文件 | 变更 |
|---|---|
| `duoduo-fulfilment/.../entity/AfterSaleEntity.java`、`mapper/AfterSaleMapper.java`（**新增**） | 售后单实体（诉求 / 举证图 / 卖家说明 / 仲裁结论与说明 / 裁定人 / 退款金额）；Mapper 5 个 **CAS 条件更新**（卖家同意退款、运营裁定退款、卖家拒绝、买家申请仲裁、运营驳回） |
| `duoduo-fulfilment/.../support/AfterSaleStatus.java`（**新增**） | 状态机单一事实来源：状态常量 + 中文文案 + 类型常量与文案 + 终态判定。**不设「已驳回再改判」的边**（要改判只能新立案，留痕不会自相矛盾） |
| `duoduo-fulfilment/.../support/OrderRefs.java`、`support/ImageUrls.java`（**新增**） | 从评价域抽出的共用件：订单号→稳定内部标识的哈希、图片直链校验与 JSON 读写。抽出的理由是**一致性**——同一订单在两个子域必须得到同一个 id，同一安全规则只应有一处实现 |
| `duoduo-fulfilment/.../service/AfterSaleService.java`（**新增**） | 核心规则：归属与订单状态取自订单事实；仅 `SHIPPED`/`COMPLETED` 可立案（422）；唯一键撞键 409；卖家只能处理自己店铺（403）；拒绝必填说明（422）；买家可从 `PENDING`/`SELLER_REJECTED` 申请仲裁（**对 07 草案的补全**：草案没写「卖家拒绝后买家怎么办」）；运营裁定说明必填（422）；**先让钱动再改状态**；Feign 三类失败分别映射 404 / 422（透传对端原话）/ 503 |
| `duoduo-fulfilment/.../controller/AfterSaleController.java`、`AdminAfterSaleController.java`、`dto/AfterSaleDtos.java`（**新增**） | 6 个用户端端点 + 2 个运营端端点；请求体**无** buyerId/sellerId/status；视图**不含** `arbitratedBy`（裁定人属平台内部信息） |
| `duoduo-fulfilment/.../client/OrderClient.java`、`client/dto/{RefundRequest,RefundResult}.java` | 新增跨服务调用：复用交易域**既有的**运营侧退款入口（不新开资金通路）；契约副本按 R3 约定手写，不共享 jar |
| `duoduo-fulfilment/.../service/ReviewService.java` | 重构：删掉私有的 `orderIdOf` / `validateImages` / `writeImages` / `readImages`，改用 `support/OrderRefs` 与 `support/ImageUrls` |
| `duoduo-fulfilment/src/main/resources/db/schema.sql`、`docs/sql/04-duoduo-fulfilment.sql` | 新增 `t_after_sale` + 4 条索引；容器已实际执行 DDL（`t_review` 数据保留） |
| `duoduo-fulfilment/src/main/resources/application.yml` | 新增 `duoduo.aftersale.max-images`（默认 6，**刻意少于评价的 9**：举证要的是关键几张，不是图集） |
| `duoduo-gateway/src/main/resources/application.yml` | 履约域路由补 `/api/admin/aftersales/**`（**必须显式列举**：product/trade 是按具体子路径列的，不是 `/api/admin/**` 兜底；漏了不报错、只 404）；两段都不进白名单 |
| `scripts/aftersale-smoke.ps1`（**新增**） | **101 项断言**：A3 六条验收标准 + 卖家拒绝→买家升级→运营裁定闭环 + 卖家「同意即退款」独立资金路径 + 同单不同类型可各自立案 + 越权 403 + 列表准入与状态过滤 + **已完成订单裁定退款被拒（B-01）** + 归属夹具复原与库存复原 |
| `README.md`、`docs/agent/02·03·04·06·07` | 接口清单与章节（新增「履约域：售后仲裁工单」）、代码地图、跨服务调用矩阵、契约全集（端点 + DTO + 枚举 + 业务规则）、门禁表（改履约域 → review + aftersale 两套）、待办 A3 标记完成 |
| `docs/待协助问题.md` | 留档新增 S-03（A3 已交付）；待你处理项不变（百炼 API Key、小程序 AppID、信用分权重口径） |

**自测结论**：后端 `BUILD SUCCESS`；重建 fulfilment / gateway 镜像并重启（**8 容器 healthy**）；
新增 `scripts/aftersale-smoke.ps1` **101 通过 / 0 失败**；回归 `review-smoke.ps1` **59/0**、
`settlement-smoke.ps1` **125/0**。十一套接口冒烟合计 **856 项**。前端本轮无改动（未跑 `tsc`/`build`）。

**本轮暴露与修复**

1. **编译期抓到的重构遗漏**：抽出 `ImageUrls` 后漏改 `ReviewService.toView()` 里的一处 `readImages(...)` 调用 →
   `找不到符号: 方法 readImages(String)`。类内私有方法替换靠编译器兜住，这正是「每改一个模块做一次编译检查」的价值。
2. **网关路由是「显式列举」而非前缀兜底**：`/api/admin/**` 并不存在统一路由（product / trade 各自列出具体子路径），
   所以运营侧售后工作台必须显式加进履约域路由，否则**不报错、只 404** —— 新增端点后先用匿名请求确认拿到 401 而不是 404，
   这一步比等冒烟更早发现问题。
3. **19 位雪花 id 精度坑的第二次踩点预防**：售后单 id 一律从**原始响应文本**正则提取后拼 URL，
   买家 id 断言改为「库内两边读字符串再比」，而不是拿 JSON 反序列化出来的数字去比 —— 后者会恒不等（假失败）。

**对 07 篇 A3 草案的两处工程化修正（实现为准）**
- 补 `POST /api/aftersales/{id}/escalate`（买家申请平台仲裁）：草案只有「卖家处理」与「运营裁定」，
  没有「卖家拒绝后买家怎么办」这一步，缺了它流程就断了。
- 「缺说明」判 **422** 而不是用 `@NotBlank` 判 400：这是**流程要求**（这个动作不允许这样提交），
  不是字段格式错。立案的 `reason` 则走注解判 400（字段级必填）。两档分界的理由写在 `AfterSaleDtos` 的类注释里。

### 2026-09-18（第八轮 · A1 历史订单托管单回补 I-14 + 文档口径收敛）

| 文件 | 变更 |
|---|---|
| `duoduo-trade/.../service/SettlementBackfillService.java`（**新增**） | 回补核心：按订单状态推断资金状态（`PAID`/`SHIPPED`→`CUSTODY`、`COMPLETED`→`RELEASED`、`REFUNDED`→`REFUNDED`）；金额取 `pay_amount`；放款时间取订单**真实 `finished_at`**；只碰 `s.id IS NULL`；不给 `PENDING_PAYMENT`/`CLOSED` 补账；**逐条独立提交**（补了 80 条后第 81 条出错不回滚前 80 条）；幂等靠 `uk_settle_order`，撞键记 `skipped` 而非抛错 |
| `duoduo-trade/.../mapper/OrderMapper.java` | 新增 `findOrdersNeedingSettlementBackfill()`：比 `findOrdersWithoutSettlement()` 多覆盖 `REFUNDED`、不 LIMIT、带出 `finished_at`（异常列表给人看，回补要一次补齐）；只取少数字段返回 `Map`，不让收货人信息进内存 |
| `duoduo-trade/.../dto/AdminDtos.java` | 新增 `BackfillItem` / `BackfillResult`（含审计字段 `commissionBps` / `commissionNote`：历史费率不可还原，口径必须随结果说清） |
| `duoduo-trade/.../controller/AdminSettlementController.java` | 新增 `POST /api/admin/settlements/backfill`；**参数走查询串 `?dryRun=` 而非请求体**（见下方缺陷 ①） |
| `duoduo-trade/.../service/SettlementService.java` | 新增 `platformFeeBps()` 访问器：费率只留一个事实来源，避免回补服务自己再 `@Value` 一遍导致静默不一致 |
| `scripts/backfill-smoke.ps1`（**新增**） | **88 项断言**：权限三层（网关 401 / 直连服务 401 / 普通用户 403）、dryRun 不写库、三组金额与笔数**精确增量**、幂等重复执行不增行、挂账清零、控制组逐字段未变、**REFUNDED 盲区单独断言**、放款时间 = 订单 `finishedAt`、夹具自建自毁 |
| `README.md` | 容器表补 `duoduo-app-fulfilment`（7 → **8** 行）、库数 3 → **4**、Nacos 注册 4 → **5** 服务、冒烟 9 套 667 项 → **10 套 755 项**；§三 补回补接口；新增本轮变更记录 |
| `docs/PLAN.md` | 本文件：更新时间、§4 任务清单补 A1、§4 进行中补 A9/A13 排期口径、I-14 改「已自愈」、§7 命令速查修正容器数与端口、新增本轮变更记录 |
| `docs/agent/02`、`docs/agent/04`、`docs/agent/07` | 代码地图补新文件、契约补新端点与 DTO、待办 A1 标记完成 |

**自测结论**：后端 `BUILD SUCCESS`；重建 trade 镜像并重启（**8 容器 healthy**）；
新增 `scripts/backfill-smoke.ps1` **88 通过 / 0 失败**；回归 `settlement-smoke.ps1` **125/0**、
`payment-smoke.ps1` **71/0**、`admin-smoke.ps1` **49/0**；前端本轮无改动（未跑 `tsc`/`build`）。
十套接口冒烟合计 **755 项**。

**自测暴露并修复的缺陷（三条，都值钱）**

1. **API 健壮性缺陷**：回补接口原用 `@RequestBody(required = false)`，
   客户端「无请求体 + 默认 `Content-Type`」调用时返回 **415** ——
   而运营用 curl 直接调这个运维接口正是最常见姿势。修法：参数改走查询串 `?dryRun=`，
   与既有 `?active=false` 写法一致，彻底消除 Content-Type 这个变量。
2. **断言基准取得太远 → 同时产生假失败与假通过**：dryRun 的「金额一分未动」原先拿「创建夹具之前」的汇总做基准：
   控制组 D 自己确认收货造成的放款增量被算到 dryRun 头上（**假失败**），
   而 A/B/C 三条托管单被删造成的托管额下降恰好把同类偏差抵消（**假通过**）。
   修法：基准**紧贴被测操作**取（`$sumBeforeDry` / `$sumBeforeReal`）。
   教训：跨越多步副作用的断言，基准必须贴着断言点，否则偶合会伪装成正确。
3. **0 值断言被空响应骗过**：步骤 2 的 415 使得 `data` 为 `null`，
   `[int]$null -eq 0` 判定为真 → 「重复执行扫描到 0 条」**假通过**，
   掩盖了「请求根本没成功」这个事实。修法：所有 0 值断言加 `$null -ne $body.data` 前置条件。

**本期踩坑新增（待并入 `docs/agent/08`）**
- **内部接口不能经网关调用**：`/api/orders/internal/sweep` 走 8080 会被网关按「未登录」拦成 401，
  必须**直连交易服务 8083** 并带 `X-Internal-Token`（与 I-13 同源：先确认打的是哪个服务）。
- `docker exec mysql` 的告警行会被 PowerShell 当成 `NativeCommandError` 混进 stdout，
  `Sql` 辅助函数按 `Warning` 过滤的做法要继续保持。

**推送状态**：本轮提交 `9d0abc8` 已正常 `git push`（`28119c7..9d0abc8  main -> main`）。
I-18 记录的「`github.com` git 端点 502」本轮**未复现**，且 REST API 推送造成的
**本地 / 远端 SHA 分叉已消除**（远端与本地同为 `9d0abc8`，无需再 `git fetch && git reset --hard`）。
`git push` 仍失败时按 I-18 走 `.workbuddy/tools/push_via_api.py` 备用通道。

### 2026-09-18（第六轮 · 履约域 M11/P2-1：评价与晒单）

| 文件 | 变更 |
|---|---|
| `duoduo-fulfilment/`（**新增整个模块**） | POM 入父反应堆；`FulfilmentApplication`（`@EnableFeignClients` + `@MapperScan`）；`FulfilmentServiceConfig`（分页 + 雪花 **worker-id=4**）；`application.yml` / `-local` / `-mysql` 三档；复用同一份 `Dockerfile` |
| `duoduo-fulfilment/.../entity/ReviewEntity.java`、`mapper/ReviewMapper.java` | 评价实体（含追评列、审核位）；聚合 SQL（`aggregateBySpu` / `starDistribution`）+ 批量查询（`reviewedOrderNos` / `reviewedSpuIds`） |
| `duoduo-fulfilment/.../service/ReviewService.java` | 核心规则：订单事实校验（归属 / 已完成）、`spuId` 必须在订单明细、评分 1-5、图片 http/https 白名单 ≤9 张、追评一次 + 窗口期、匿名脱敏、碰撞唯一键转 409、用户服务不可用时**降级**为无昵称而非整页失败 |
| `duoduo-fulfilment/.../controller/ReviewController.java`、`dto/ReviewDtos.java` | 7 个接口；请求体**无 userId**；响应时间统一 ISO-8601 UTC |
| `duoduo-fulfilment/.../client/OrderClient.java`、`UserClient.java`、`config/InternalCallConfig.java` | 服务间只读调用（Nacos + Feign，不经网关），统一注入 `X-Internal-Token` |
| `duoduo-fulfilment/src/main/resources/db/schema.sql`、`docs/sql/04-duoduo-fulfilment.sql`（**新增**） | `t_review`：唯一键 `(order_id,user_id,spu_id)`（**一单一商品一评**，不是一单一评）；`order_id` 由订单号派生稳定哈希（不把本域主键绑在对方字符串格式上） |
| `docs/sql/00-init-databases-and-user.sql` | 新增 `duoduo_fulfilment` 库与授权 |
| `duoduo-user/.../support/InternalTokenFilter.java`（**新增**）、`AuthService`、`UserController`、`UserDtos.UserBrief` | 内部只读接口 `GET /api/users/internal/briefs?ids=`（批量昵称头像，**只回三字段**）；批量而非逐条，避免跨进程 N+1 |
| `duoduo-trade/.../dto/OrderDtos.OrderFactView`、`OrderService.internalOrderFact`、`OrderController` | 内部只读接口 `GET /api/orders/internal/{orderNo}/fact`：**裁剪掉收货人信息**的订单事实 |
| `duoduo-gateway/.../config/GatewaySecurityProperties.java` | 白名单新增 `/api/reviews/product/**`（**只放行只读子路径**，写接口仍强制登录） |
| `docker-compose.yml` | 第 8 个容器 `duoduo-fulfilment-app`（:8084）；用户容器补 `INTERNAL_TOKEN`；网关依赖加履约 |
| `frontend/src/components/ReviewSection.tsx`（**新增**） | 商品评价区：汇总（平均分 / 星级分布条 / 好评率 / 带图 / 追评）+ 星级与有图筛选 + 分页 + 匿名脱敏展示 |
| `frontend/src/pages/ReviewNew.tsx`（**新增**） | 按订单逐件评价：**已评过的商品不再出现在表单**，只列待评项；星级大按钮；图片直链输入；匿名勾选 |
| `frontend/src/pages/MyReviews.tsx`（**新增**） | 我的评价 + 追评弹窗；可否追评由服务端 `appendable` 决定（前端不自己算窗口期） |
| `frontend/src/pages/Orders.tsx`、`ProductDetail.tsx`、`Me.tsx`、`App.tsx`、`api/types.ts`、`api/endpoints.ts` | 已完成订单显示「去评价 / 已评价」（**一次批量查** `reviewed`）；详情页挂评价区；「我的」加快捷入口；两条新路由；`reviewApi` + 类型 |
| `scripts/review-smoke.ps1`（**新增**） | **59 项断言**：内部接口保护与字段裁剪、公开读/登录写边界、评价资格（未完成 422 / 他人订单 403 / 不在订单 422）、参数边界、幂等 409、匿名脱敏、追评一次、聚合口径、夹具自建自毁 |
| `scripts/e2e/review-ui-check.mjs`（**新增**） | 真实 Chromium **20 项断言**：订单列表入口 → 评价页打分提交 → 我的评价 → 商品详情公开评价区（含匿名展示），4 张截图 `rev_*.png` |

**自测结论**：8 容器全 healthy（新增履约服务）；`scripts/review-smoke.ps1` **59 通过 / 0 失败**；
`scripts/e2e/review-ui-check.mjs` **20 通过 / 0 失败**；前端 `tsc --noEmit` 零错误、`vite build` 通过。

**自测暴露并修复的缺陷**：

1. **断言失败其实打错了服务**：内部接口保护断言直连履约服务 8084 却打用户域的路径，
   拿到的是「无此路由」的 500，看起来像过滤器坏了 → 修正为直连对端服务（8081）。
   教训与 I-13 同源：**断言失败先确认打的是哪个服务**。
2. **PowerShell 把 `$SPU?page=1&pageSize=50` 整体当成变量名**（`?` 允许出现在变量名中），
   展开成 `=1&pageSize=50`，服务端收到 `spuId = "=1&pageSize=50"` 报类型转换错。
   修法：一律写 `${SPU}?`。这是继「中文 BOM」「雪花 ID 精度」之后的第三个 PowerShell 隐形坑。
3. **19 位雪花主键经 `ConvertFrom-Json` 丢精度**（与前端 `JSON.parse` 同类），
   导致「数据明明在库里、断言却找不到」→ 断言一律改用**业务键**（订单号），
   需要主键时从**原始响应文本**正则提取。
4. **`商品不在该订单中` 的语义归位**：原用 `validation`（400）返回，
   但它其实是业务规则不满足 → 改 `businessRule`（422）。400 与 422 的区别直接决定
   前端提示策略与告警口径，混用会让真正的参数 bug 淹没在业务分支里。

### 2026-09-18（第五轮 · 平台管理后台 M10）

| 文件 | 变更 |
|---|---|
| `duoduo-common/.../context/OperatorGuard.java`（**新增**） | 运营准入统一入口：`requireOperator()`（未登录 401 已被网关拦，非运营 **403**），`isOperator()` 与 `LoginUser` 同口径（忽略大小写，OPERATOR/ADMIN 均可） |
| `duoduo-product/.../controller/AdminCatalogController.java`（**新增**） | `/api/admin/categories`、`/api/admin/brands`：增 / 改 / 删 / 启停用 / 后台列表（**含停用项**） |
| `duoduo-product/.../controller/AdminListingController.java`（**新增**） | `/api/admin/listings`：审核工作台列表（含未通过内容）+ `POST /{id}/audit`（APPROVE / REJECT，驳回必须填原因） |
| `duoduo-product/.../service/CategoryService.java` | 新增管理方法；**创建显式赋雪花 id**（product 服务 `id-type: input`，此前 insert 直接报 id 为 null）；`flatForAdmin()` / `adminBrands()` 不过滤状态——停用后后台仍可见、可重新启用 |
| `duoduo-product/.../service/ListingService.java` | 新增 `listForAudit` / `audit`：审核动词（APPROVE/REJECT）与库内状态（APPROVED/REJECTED）分离；`audit_reason` / `audit_at` 落库留痕 |
| `duoduo-product/.../entity/ListingEntity.java`、`db/schema.sql`、`docs/sql/02-duoduo-product.sql` | `t_listing` 增加 `audit_reason`、`audit_at`（容器已 ALTER，从零建库脚本同步） |
| `duoduo-product/.../dto/ProductDtos.java` | `CategorySaveRequest` / `BrandSaveRequest` / `ListingAuditRequest` / `ListingAuditView`；`CategoryNode` / `BrandView` 增加 `status`（后台要展示停用项） |
| `duoduo-trade/.../service/OrderService.java` | 新增 `adminList` / `adminDetail`（无归属限制，准入由 OperatorGuard 把关）；`forceClose`（仅待付款：关单 + 作废未支付支付单 + 释放冻结 + 状态日志记 OPERATOR，**复用既有状态机与补偿语义**） |
| `duoduo-trade/.../controller/AdminOrderController.java`（**新增**） | `/api/admin/orders`（列表/详情/强关） |
| `duoduo-trade/.../service/AdminOpsService.java`（**新增**） | 资金对账：托管汇总（SQL 聚合）+ 异常挂账（`LEFT JOIN` 找「已付款却缺托管单」）+ 运营报表（GMV / 订单量 / 退款率 / 转化率） |
| `duoduo-trade/.../controller/AdminSettlementController.java`、`AdminReportController.java`（**新增**） | `/api/admin/settlements/**`、`/api/admin/reports/overview` |
| `duoduo-trade/.../dto/AdminDtos.java`（**新增**） | `SettlementSummary` / `AbnormalSettlement` / `AdminReport` / `ForceCloseRequest` |
| `duoduo-trade/.../mapper/OrderMapper.java`、`SettlementMapper.java` | 新增全平台聚合 SQL（`countAllByStatus` / `sumPaidAmount` / `findOrdersWithoutSettlement`） |
| `duoduo-gateway/.../application.yml` | 路由补 `/api/admin/**`（product + trade，**不进白名单**） |
| `frontend/src/api/endpoints.ts`、`types.ts` | 新增 `adminApi`（14 个端点）与后台类型 |
| `frontend/src/pages/admin/`（**新增**） | `shared.tsx`（isOperator / AdminTabs / AdminDenied / AdminStat）、`Dashboard.tsx`（报表 + 资金对账 + 异常挂账表）、`Audits.tsx`（待审/通过/驳回三视图 + 驳回原因弹窗）、`Categories.tsx`、`Brands.tsx`（状态列 + 按状态切换启停按钮）、`Orders.tsx`（状态过滤 + 订单号前缀搜索 + 强关确认弹窗） |
| `frontend/src/App.tsx`、`components/Layout.tsx` | `RequireOperator` 守卫 + 5 条 `/admin/**` 路由；导航与用户菜单按角色显示「管理后台」 |
| `scripts/admin-smoke.ps1`（**新增**） | **49 项断言**：401/403 边界、报表与对账、类目品牌增改删启停（含停用后仍可见）、审核通过/驳回留痕（中文经接口读回无乱码）、强关全链（冻结释放、重复强关被拒、买家可见关单原因）、夹具自建自毁、运营账号用完降回 USER |

**自测结论**：后端 `BUILD SUCCESS`；重建 product / trade 镜像并重启（7 容器 healthy）；
`scripts/admin-smoke.ps1` **49 通过 / 0 失败**；前端 `tsc --noEmit` 零错误、`vite build` 通过。

**自测暴露并修复的缺陷**：

1. **类目 / 品牌创建报 500**（`Column 'id' cannot be null`）→ product 服务 MyBatis-Plus `id-type: input`，
   实体不自动生成主键；创建时显式赋雪花 id（与种子数据的 SQL 赋值同一口径）。
2. **审核 action 契约错位**：服务端拿动词与状态常量比较（`APPROVE` ≠ `APPROVED`），任何审核请求都被 422 →
   明确「对外动词、库内状态」两层，switch 显式映射。
3. **停用即消失**：后台类目/品牌列表复用只含 ACTIVE 的公开查询，停用后运营再也找不到它、无法重新启用 →
   后台列表改走不过滤状态的 `flatForAdmin()` / `adminBrands()`，DTO 增加 `status` 字段，前端按状态切换「启用/停用」按钮。
4. **PS 5.1 无 BOM 乱码**：脚本含中文必须 UTF-8 with BOM，否则被按 GBK 解析直接语法报错；
   `docker exec mysql` 的控制台输出走 GBK，**中文等值断言会假失败** —— 库内断言只判状态与非空，
   中文内容改走接口（JSON UTF-8 链路干净）读回比对。

**踩到的协作坑（写进脚本注释）**：运营账号由脚本自助注册 + SQL 提权 + 结尾降回 USER，
保证脚本可反复执行且不碰种子数据；审核夹具用大数段显式 id（避开雪花段），用完即删并自证复原。

### 2026-09-17（第三轮 · 买家端 Web 商城 M6 / P1-c）

| 文件 | 变更 |
|---|---|
| `frontend/`（**新增整个工程**） | React 18 + TS 5.6（strict）+ Vite 5；12 路由 / 10 页主交易闭环；`package.json` / `tsconfig.json` / `vite.config.ts` / `index.html` / `.env.example` |
| `frontend/src/api/` | `client.ts` 类型化客户端（解包 `data`、401 单飞 refresh 并重放、5xx 与网络退避 ≤3 次、4xx 不重试、`ApiError` 分类）；`types.ts` 对齐 Java DTO；`endpoints.ts` 端点清单 |
| `frontend/src/components/` | `ui.tsx`（卡片/分页/弹窗/状态步骤/Toast）、`Layout.tsx`、`hooks.ts`、`QRCode.tsx`（**零依赖 QR 编码器**：Byte 模式 + Reed-Solomon + 8 掩码择优） |
| `frontend/src/store/` | `auth.tsx`（令牌在 httpOnly Cookie，登录态唯一判据是 `/users/me`）、`app.tsx`（购物车角标） |
| `frontend/src/pages/` | Home / Search / ProductDetail / Listings / ListingDetail / Login / Pets / Me / Cart / Checkout / Orders / OrderDetail（二维码 + 3 秒轮询 + 托管明细）/ NotFound |
| `docs/PROJECT-STATUS.md` | 澄清演示账号**仅存在于 Node 原型种子数据**，Java 库不预置（此前误把 Node 版账号当 Java 版使用） |

**自测结论**：`tsc --noEmit` 零错误、`vite build` exit 0（55 模块）；curl 全链冒烟（注册 → 加购 → 下单幂等复放 → 沙箱扫码 → 托管 → 退款）全绿；
真实 Chromium 逐页验证 12 路由，含真实 UI 填单下单 → 模拟扫码 → 3 秒轮询自动刷新为已付款 → 托管金额核对（¥59.00 / 服务费 ¥0.59 / 卖家实收 ¥58.41）。

**自测暴露并修复的前端缺陷**：① 闲置成色硬编码中文而后端下发码值（`NEW`/`LIKE_NEW`/…），会下发错值 → 改字典驱动 + 常量兜底；
② 宠物建档未提示「生命阶段须与生日推算一致」（后端 422）→ 表单实时提示 + 一键修正 + 提交前拦截；
③ 登录页演示账号按钮引用 Node 原型种子账号（Java 库无）→ 改「一键生成体验账号」走真实注册。

### 2026-09-18（第四轮 · 卖家中心 M5-9 + 商家端 Web M9）

| 文件 | 变更 |
|---|---|
| `duoduo-common/.../context/LoginUser.java` | 修角色大小写 bug（`equalsIgnoreCase`）；新增 `isSeller()` / `canAccessSellerConsole()`（卖家 / 运营） |
| `duoduo-user/.../service/AuthService.java`、`controller/UserController.java` | 新增 `applyMerchant`：实名前置（未实名 422）+ 幂等（已是卖家直接返回）+ 写 `role=SELLER`；端点 `POST /api/users/me/merchant` |
| `duoduo-trade/.../service/OrderService.java` | 抽出 **`shipInternal`** / **`refundInternal`**（发货与退款的状态机只留一处），`toViews` 改 public 供卖家侧复用，避免「买家看到已放款、卖家看到托管中」的口径分叉 |
| `duoduo-trade/.../service/SellerService.java`、`controller/SellerController.java` | **新增**：`SellerScope` 可见范围模型；`requireSellerScope`（非卖家 403）+ `requireOwnOrder`（不存在 404 / 越权 **403**）；6 个接口 |
| `duoduo-trade/.../dto/SellerDtos.java` | **新增**：`ShipRequest` / `SellerSummary` / `SettlementPage` |
| `duoduo-trade/.../mapper/OrderMapper.java`、`SettlementMapper.java` | 新增 SQL 侧聚合（`countBySellerAndStatus` / `aggregateBySellers`），避免把全量订单捞回内存数 |
| `duoduo-trade/.../support/InternalTokenFilter.java` | 排除 `/api/seller/**`——内部凭据只能证明「你是自己人」，证明不了「这单是你的」 |
| `duoduo-gateway/.../application.yml` | trade 路由补 `/api/seller/**`（**不加白名单**：卖家端必须带登录态） |
| `duoduo-common/.../enums/ErrorCode.java`、`duoduo-common-web/.../GlobalExceptionHandler.java` | **新增** `UNSUPPORTED_MEDIA_TYPE`（415）——客户端发错 Content-Type 原本落到兜底返回 **500**，属真实缺陷 |
| `frontend/src/api/endpoints.ts`、`types.ts` | 新增 `sellerApi` 与 3 个类型；`userApi.applyMerchant` |
| `frontend/src/pages/seller/`（**新增**） | `shared.tsx` / `Dashboard.tsx` / `Orders.tsx` / `OrderDetail.tsx` / `Settlements.tsx` |
| `frontend/src/App.tsx`、`components/Layout.tsx`、`pages/Me.tsx` | 新增 `RequireSeller` 与 4 条 `/seller/**` 路由；导航与「我的」按角色显示商家端入口 |
| `scripts/seller-smoke.ps1`（**新增**） | 12 段场景 / **84 项断言**，含越权、归属隔离、重复发货、已完成订单退款边界、平台自营可见性、物流落库回显、夹具自动复原 |
| `scripts/e2e/seller-ui-check.mjs`（**新增**） | 真实 Chromium UI 自测：**29 项断言**。自动备夹具（卖家账号 + 两笔归属订单），逐页走概览 / 订单 / 过滤 / 一键发货 / 详情 / 对账 / 非卖家视角，每页截图，并收集全部 HTTP 4xx/5xx 与 console 错误 |
| `duoduo-trade/.../entity/OrderEntity.java`、`dto/OrderDtos.java`、`service/OrderService.java`、`SellerService.java`；`db/schema.sql`、`docs/sql/03-duoduo-trade.sql` | 物流信息落库：`t_order` 增加 `logistics_company` / `logistics_no`，`OrderView` 回显，`shipInternal` 写入（内部接口不传则为空） |
| `frontend/src/api/types.ts`、`pages/OrderDetail.tsx`、`pages/seller/OrderDetail.tsx` | 买家与卖家详情页都显示物流公司与单号（同一份事实的两个视角） |
| `frontend/public/favicon.svg`、`index.html` | 补 favicon，消除 `/favicon.ico` 404 |

**自测结论**：后端 6 模块 `BUILD SUCCESS`；重建 4 个业务镜像并重启（7 容器 healthy）；
`scripts/seller-smoke.ps1` **84 通过 / 0 失败**；真实浏览器 UI 自测 `scripts/e2e/seller-ui-check.mjs` **29 通过 / 0 失败**；
前端 `tsc --noEmit` 零错误、`vite build` 通过（60 模块）。

**自测暴露并修复的缺陷**：

1. **错误 Content-Type 导致 500**（应为 415）→ 新增 `ErrorCode.UNSUPPORTED_MEDIA_TYPE` 与 handler，客户端错误不再污染 5xx 告警。
2. **首轮脚本场景设计错误**：订单 B 未发货就直接确认收货（状态机只允许 SHIPPED→COMPLETED，故 422），
   连带把「已完成订单退款被拒」误判成 200 且钱真的退了 → 修正为先发货再确认，两项随即转绿。
3. **物流信息只写进状态日志，没落在订单上**（UI 自测发现：卖家发货后详情页看不到自己填的单号）→
   `t_order` 增加 `logistics_company` / `logistics_no`，`OrderView` 回显，卖家与买家详情页都显示；
   冒烟补两条断言（视图回显 + 落库），杜绝「只写流水不写事实」。
4. **页面缺 favicon**，浏览器每次请求 `/favicon.ico` 拿 404 → 补 `public/favicon.svg` 并在 `index.html` 声明。

**踩到的环境坑（已写进脚本注释）**：① 前端**必须用 `http://localhost:5173` 访问**，
用 `127.0.0.1` 时 Origin 不在网关 CORS 白名单里，Spring 直接 403，前端只显示「没有权限执行该操作」，
而 curl 不带 Origin 头复现不出来；② **雪花 ID 有 18 位，超过 `Number.MAX_SAFE_INTEGER`**，
JavaScript 里 `JSON.parse` 会把它改成另一个数，拿去写 `seller_id` 会得到「夹具改成功了、卖家却一笔都看不到」的假象，
必须按原始文本用正则提取。

### 2026-09-18（沙箱支付渠道 M8-1）

| 文件 | 变更 |
|---|---|
| `duoduo-trade/.../payment/PaymentChannel.java`、`PaymentChannelRegistry.java`、`MockPaymentChannel.java`、`SandboxPaymentChannel.java` | **新增** 渠道抽象层：预下单 / 回调验签 / 查询三动作；注册表统一管理；MOCK 即时成功、SANDBOX 扫码形态 |
| `duoduo-trade/.../entity/PaymentEntity.java`、`mapper/PaymentMapper.java` | 支付单新增 `code_url` / `expire_at` 字段与 `PENDING` / `EXPIRED` 状态；Mapper 补 CAS 方法（幂等入账 / 过期作废 / 关单作废） |
| `duoduo-trade/.../service/OrderService.java` | `pay()` 改**预下单**语义（渠道差异收敛到一处）；`handleChannelCallback`（验签 + 金额核对 + 幂等入账 + 建托管）；`sandboxPay` / `sandboxPayPreview` / `getPaymentView`；取消与调度关单两条路径**作废未支付支付单**；补偿调度增加支付单过期扫描 |
| `duoduo-trade/.../controller/PaymentController.java` | **新增**：回调入口 `/api/payments/callback/{channel}`、沙箱确认支付 `/sandbox/{payNo}`、收银台页面 `/sandbox/pay/{payNo}`、支付单查询 `/{payNo}` |
| `duoduo-common/.../enums/ErrorCode.java` | 新增 `PAYMENT_CHANNEL_UNKNOWN`（422）、`PAYMENT_AMOUNT_MISMATCH`（422） |
| `duoduo-gateway/.../application.yml`、`GatewaySecurityProperties.java` | 路由补 `/sandbox/pay/**`；白名单放开收银台与沙箱确认（扫码设备无登录态，现实中谁扫码谁付款） |
| `duoduo-trade/.../resources/application.yml` | 新增 `duoduo.pay.sandbox.*`（二维码有效期 / HMAC 密钥 / 收银台地址，均可环境变量覆盖） |
| `duoduo-trade/.../resources/db/schema.sql` 与 `docs/sql/03-duoduo-trade.sql` | `t_payment` 增加 `code_url`、`expire_at`；容器脚本重新生成并落库 |
| `scripts/pay-channel-smoke.ps1` | **新增** 支付渠道自测（62 项断言：预下单 / 二维码 / 验签攻击面 / 金额篡改 / 重复回调 / 过期作废 / 关单作废 / 越权） |
| `README.md` | 按约定同步：新增支付渠道接口表与第四节「渠道抽象与沙箱扫码」；六套冒烟 |

### 2026-09-17（C2C 担保交易 M5-8 与支付口径调整）

| 文件 | 变更 |
|---|---|
| `docs/PLAN.md` | 本文件：支付口径由「真实渠道」改为**沙箱支付**；M8 改为沙箱支付渠道并解除阻塞；I-12 改「已协助」并把材料清单降级为备查；端形态表支付列同步；新增已知边界 B-07 |
| `README.md` | 按约定同步（支付方式章节、沙箱支付流程、新增接口与自测脚本） |
| `duoduo-product/.../mapper/SkuMapper.java` | 新增 `restockStock`（退货入库原子更新，**不校验在售状态**——库存是物理事实） |
| `duoduo-product/.../dto/StockDtos.java`、`service/StockService.java`、`controller/StockController.java` | 新增 `restock` 动作与 `POST /api/products/skus/stock/restock` |
| `duoduo-trade/.../entity/SettlementEntity.java`、`mapper/SettlementMapper.java`、`support/SettlementStatus.java`、`dto/SettlementDtos.java`、`service/SettlementService.java` | **新增** 资金托管：CAS 状态迁移、佣金计算、托管视图 |
| `duoduo-trade/.../entity/OrderEntity.java`、`support/OrderStatus.java` | 新增 `refundedAt` 与终态 `REFUNDED`；移除被误用的 `PAID → CLOSED` 边 |
| `duoduo-trade/.../service/OrderService.java` | 支付成功建托管；确认收货放款；买家自助退款与运营侧退款；入库走事务提交后事件；补偿调度扩展自动确认收货与入库补偿 |
| `duoduo-trade/.../controller/OrderController.java` | 新增 `/refund`、`/internal/{orderNo}/refund`、`/{orderNo}/settlement` |
| `duoduo-trade/.../resources/application.yml` | 新增 `platform-fee-bps`、`auto-receipt-days` |
| `duoduo-trade/.../resources/db/schema.sql` 与 `docs/sql/03-duoduo-trade.sql` | `t_order` 增加 `refunded_at` 与 `idx_order_auto_receipt`；由源文件重新生成容器脚本 |
| `scripts/settlement-smoke.ps1` | **新增** 担保交易自测（125 项断言） |
| `Dockerfile` / `.dockerignore` / `duoduo-gateway/pom.xml` | 更早引入，本轮仍全程验证通过 |

---

## 7. 一键命令速查

```powershell
# 起全部中间件 + 业务服务（Docker 优先）
cd D:\智能体项目\duoduo-pet-mall-java
docker compose up -d
docker ps                                  # 10 个容器应全为 healthy
# 需要 ES 检索（A10）时额外起：docker compose --profile search up -d duoduo-es
# 一键全量回归（套件由 scripts/*smoke.ps1 自发现，含 ES 前置检查：ES 未运行时显式跳过 search-smoke 并列入汇总）
#   当前：接口冒烟 23 套（PowerShell）+ 1 套 node（e8-entry-scope-smoke.mjs）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\run-all-smoke.ps1

# 端到端冒烟（55 项，网关 + 用户域 + 商品域；含「分页 total 以 JSON 数字下发」类型断言）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke.ps1

# 购物车端到端自测（87 项，需登录，会临时改库并自动复原）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\trade-smoke.ps1

# 下单 / 拆单 / 库存冻结 端到端自测（77 项，含安全断言与部分冻结失败补偿）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\order-smoke.ps1

# 支付 / 订单状态机 / 超时关单 端到端自测（71 项）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\payment-smoke.ps1

# 担保交易 / 资金托管 / 放款 / 退款 / 自动确认收货 端到端自测（125 项）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\settlement-smoke.ps1

# 沙箱支付渠道 / 预下单 / 回调验签 端到端自测（62 项）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\pay-channel-smoke.ps1

# 卖家中心 / 商家端：准入、归属隔离、发货、售后退款、结算对账（101 项，会临时改夹具并自动复原）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\seller-smoke.ps1

# 平台管理后台：权限边界、报表对账、类目品牌、内容审核、订单强关（72 项，夹具自建自毁）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\admin-smoke.ps1

# 审核 SLA 事后抽查（54 项；自动放行留 audit_at / 命中风险转人工 / sweep 幂等 / 判违规真下架 / 判合规不下架 / 复核不可重入 / 指标自洽 / 入驻判违规直接停业 + 联动下架 / 夹具自毁）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\audit-sla-smoke.ps1

# 履约域：评价与晒单（59 项；含内部接口保护、字段裁剪、匿名脱敏、追评与聚合口径）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\review-smoke.ps1

# 历史订单托管单回补（88 项；权限三层 / dryRun 预演不写库 / 幂等 / 挂账清零 / 控制组 / REFUNDED 盲区）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\backfill-smoke.ps1

# 售后仲裁工单（101 项；立案 / 卖家处理 / 买家申请仲裁 / 运营裁定退款 / 已放款边界 B-01 / 越权）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\aftersale-smoke.ps1

# 图片上传（8 项；白名单只放 GET / 匿名上传 401 / 上传→回读 roundtrip / 扩展名白名单）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\upload-smoke.ps1

# 物流轨迹（79 项；发货即揽收 / 乱序正序 / 幂等 / 批量全有或全无 / 无时区按 UTC / 越权 / 敏感字段 / 含断言总数守卫）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\logistics-smoke.ps1

# 信用分（82 项；基础分 / 权重表 / 因子和=总分 / 单调性 / 回写幂等 / 凭据边界）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\credit-smoke.ps1

# 优惠券（145 项；领券幂等 / 并发防超发 / 锁·解·核销 CAS / 折扣落交易域 / 生命周期闭环 / 清理复原）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\coupon-smoke.ps1

# 收货地址簿（82 项；CRUD / 默认唯一性与删除顺延 / 归属校验统一 404 / 下单写快照 / 清理复原）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\address-smoke.ps1

# 跨域 ID 精度回归（56 项；只断言 JSON 原始文本，绕开反序列化的精度掩盖；含断言总数守卫）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\id-precision-smoke.ps1

# 微信登录绑定（26 项；未绑 422 / 未配置降级 503 / 绑定幂等 / 抢绑 409 / 免登 401）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\wechat-auth-smoke.ps1

# AI 导购（16 项；推荐真实性 / 无 sellerId 泄露 / 匿名可问答 / 400 边界 / 429 限流）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ai-smoke.ps1

# ES 检索（40 项；**需 ES 在跑**：docker compose --profile search up -d duoduo-es）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\search-smoke.ps1

# 限时秒杀（26 项；匿名边界 / 运营准入 / 下单幂等 replay / 限购 / 售罄 / 取消回补）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\seckill-smoke.ps1

# 钱与库存每日自动对账（S3；53 项 12 小节。**注意第 12 节会真的把商品服务停掉再启动**，约 1 分钟）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\recon-smoke.ps1

# ---------- 静态守卫（不需要容器 / 不需要编译，几秒） ----------
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\lib\check-smoke-static.ps1      # 断言绑定 + .ps1 必须带 UTF-8 BOM
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\lib\check-gateway-routes.ps1    # S3 起：250 条服务端 /api/ 路径必须都被网关 Path 模式覆盖
#   ↑ 后者挡的是「服务加了接口、忘了在网关加路径段」—— 唯一表现是 404，与「服务没起来」「接口没写」长得一样（已栽 5 次）。
#     改网关 application.yml 之后**必须重建网关 jar 与镜像**才不会白改。

# ---------- 小程序自检 + 前端契约单测（纯本地 Node/Python，不需要容器） ----------
node scripts/check-miniprogram.js                 # 静态自检 8 条规则（含雪花 id 禁 Number()、按钮禁折行、胶囊必须显式定高、角标必须走统一 utils）；0 错 0 警
node scripts/api-id-payload-test.js               # 请求体 id 精度：真实加载 api.js + 桩 wx.request 断言出参，19 项
node scripts/flea-mine-filter-test.js             # 我的闲置筛选互斥 + 单一状态徽章：断言实际查询串与渲染结果，42 项
node scripts/r57-ai-clarify-test.js               # R57：AI 澄清交互（clarified 上行 / 选项渲染 / tapOption 直发 / 旧选项清除），23 项
node scripts/r56-share-comment-test.js             # R56：评论/关注流/删除编辑/举报/话题 的前端出参与页面行为，74 项
node scripts/r55-unread-badge-test.js             # R55：角标口径 + 全局刷新点 + 定时器生命周期 + 卡片冷却，59 项
node scripts/r54-miniprogram-copy-test.js         # R54：通知入口去重（tabBar 项仍在）+ 浏览/想要文案 + 下拉刷新行为，23 项
node scripts/short-time-test.js                   # 时间换算（UTC → 设备本地时区），12 项；验北京时区先设 TZ=Asia/Shanghai

# ---------- 端到端接口探针（R5x 系列，需容器在跑；Python） ----------
python scripts/r56-share-circle-smoke.py          # R56：话题/评论/删除编辑/举报与运营处理/关注流，74 项
python scripts/r55-notice-smoke.py                # R55：通知生产者内部端点 + 私信→通知 + 合并未读口径 + 订单/物流推送，42 项
python scripts/r54-listing-want-smoke.py          # R54：浏览（可累加）与想要（按买家去重）两个口径，26 项
python scripts/r53b-listing-view-status-smoke.py  # R53 热修二：浏览量自增 + 状态互斥，26 项
python scripts/r53-listing-detail-mine-smoke.py   # R53：详情卖家卡 + 审核态体系，28 项
python scripts/r53-hotfix-chat-smoke.py           # R53 热修：聊天 404 + 时间契约，17 项
python scripts/r52-listing-preview-smoke.py       # R52：发布链路 + 结算预览 + 405，57 项

# ---------- A12 压测（JMeter 5.6.3，工具在会话 tools/ 下；jtl 与 HTML 报告落 logs/perf/，不入库） ----------
# ⚠️ 必须直接 java -jar 启动（jmeter.bat 检测不到 JDK21 时会 pause 挂死）；临时放大限流：DUODUO_TRADE_PLACE_QPS=5000 重建 trade
cd C:\Users\<会话目录>\tools\apache-jmeter-5.6.3\bin
java -jar ApacheJMeter.jar -n -t D:\智能体项目\duoduo-pet-mall-java\scripts\perf\order-pay-confirm.jmx `
     -l D:\智能体项目\duoduo-pet-mall-java\logs\perf\step100.jtl -e -o D:\智能体项目\duoduo-pet-mall-java\logs\perf\step100-report `
     -JTHREADS=100 -JRAMP=30 -JDURATION=300 -j D:\智能体项目\duoduo-pet-mall-java\logs\perf\step100-run.log
# 队列通道换 order-queued-enqueue.jmx（trade 需 DUODUO_TRADE_ORDER_MODE=queue）；汇总：python scripts/perf/summarize_jtl.py <jtl...>

# ---------- R60 压测（scripts/perf/bench.py，纯标准库，无外部依赖；结果落 logs/perf/r60-bench.json） ----------
# 并发梯度 10/25/50/100 × 10s；读/写两套基线；--check 供 CI 用（不达标退出码 1）
python scripts/perf/bench.py --concurrency 10,25,50,100 --duration 10   # 跑全梯度并落盘（默认梯度 10,25,50）
python scripts/perf/bench.py --check                                    # 只判定达标与否，退出码 0/1
python scripts/perf/bench.py --out logs/perf/自定义.json                  # 换落盘路径

# 手动触发一轮托管单回补（先 dryRun 看计划，再执行；运营账号的 Cookie 需自行带上）
# curl.exe -X POST "http://127.0.0.1:8080/api/admin/settlements/backfill?dryRun=true"

# ---------- 八套真实浏览器 UI 自测（一键：起服务 → 跑 8 套 → 建/拆券夹具 → 收尾） ----------
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\e2e\run-ui-checks.ps1          # 8 套全跑
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\e2e\run-ui-checks.ps1 -Only home  # 只跑自包含那套
# 手工跑（默认 UI_BASE=http://localhost:5173，必须走 localhost 不能 127.0.0.1 —— CORS 白名单）
cd D:\智能体项目\duoduo-pet-mall-java\frontend
node node_modules/vite/bin/vite.js --port 5173 --strictPort --host localhost   # 另开一个终端
cd .. && node scripts/e2e/home-ui-check.mjs          # 首页 7 项（非白屏 / 可点击 / 真点进详情 / 无非预期接口错误）
node scripts/e2e/address-ui-check.mjs               # 收货地址簿 64 项
node scripts/e2e/seller-ui-check.mjs                # 商家端 29 项
node scripts/e2e/review-ui-check.mjs                # 评价与晒单 21 项
node scripts/e2e/aftersale-ui-check.mjs             # 售后三端 44 项
node scripts/e2e/logistics-ui-check.mjs             # 物流轨迹 44 项
node scripts/e2e/share-report-ui-check.mjs          # 举报处理台 24 项
node scripts/e2e/coupon-ui-check.mjs                # 优惠券 43 项（夹具由一键运行器自动建/拆；手工跑用 coupon-ui-fixture.ps1 -Action create|drop）
node scripts/e2e/seckill-ui-check.mjs               # 秒杀 45 项（新建活动 / **下架后仍列得出来并能重新上架** / 前台不可见 / 抢购→下单→库存递减 / 限购拦截 / 幂等重放 / 取消回补）
# 合计 9 套 321 项

# ================= 测试环境清场（跑测试前的「数据准备」，别跳过） =================
# 先体检：待付款订单与 FROZEN 冻结行应为 0（否则下一套脚本会撞「库存不足」，
#         三套含全局不变式的冒烟会直接红 —— 看着像代码回归，其实是数据脏）
docker exec duoduo-mysql mysql -uroot -pduoduo_root -N -B -e "SELECT COUNT(*) FROM duoduo_trade.t_stock_freeze WHERE status='FROZEN';"
# 清场：按手机号取消遗留待付款订单（走业务接口，不改库；手机号用上面的体检 SQL 查）
python scripts/perf/cleanup-leftovers.py --phones 139xxxxxxxx,139yyyyyyyy

# ================= 完整自测流程（人工逐项验收） =================
# 见 docs/SELFTEST.md：环境/数据准备 → 功能点清单 → 逐项步骤与预期 → 23 条排障表 → 交付门禁

# ---------- 小程序「无浏览器」自动化测试（不需要浏览器，只需网关在跑） ----------
node --test scripts/mp-harness/pages-smoke.test.js     # 30 个页面逐个挂载（33 项）：生命周期抛错 / 卸载清理写错
node --test scripts/mp-harness/ai-page.test.js         # AI 页 4 项：快捷问题接住 / 真实发消息 / 换话题清追问
node --test scripts/lib/internal-token-parity.test.js  # 内部凭据 HMAC：JS 侧与 PowerShell 侧对拍 4 项
node scripts/mp-harness/behavior.test.js               # 页面行为 21 项（**自带汇总与退出码，直接跑，别套 node --test**）

# ---------- 买家端 Web 商城（frontend/） ----------
cd D:\智能体项目\duoduo-pet-mall-java\frontend
npm install                 # 首次
npm run dev                 # http://localhost:5173（需后端 10 容器在跑，网关 :8080）
npm run build               # tsc --noEmit && vite build
# 改后端地址：设 VITE_DEV_API_TARGET 环境变量（默认 http://localhost:8080）

# 从零重建（连数据卷一起删，会重新执行 docs/sql 初始化）
docker compose down -v; docker compose up -d

# 沙箱内调用 docker 必须带这两行（凭据助手不在 PATH）
$env:PATH = 'C:\Program Files\Docker\Docker\resources\bin;' + $env:PATH
$env:DOCKER_CONFIG = 'D:\duoduo-infra\docker-nocreds'

# 构建（沙箱里 cmd.exe 被禁，不能用 mvn.cmd —— 必须用 java 驱动 Maven launcher）
$J='D:\智能体项目\duoduo-pet-mall-java'; $M='D:\devsoft\apache-maven-3.9.4'
& 'D:\jdk\jdk21\bin\java.exe' "-Dclassworlds.conf=$M\bin\m2.conf" "-Dmaven.home=$M" `
  "-Dmaven.multiModuleProjectDirectory=$J" -classpath "$M\boot\plexus-classworlds-2.7.0.jar" `
  org.codehaus.plexus.classworlds.launcher.Launcher -gs maven/settings.xml -B -DskipTests package

# 只往容器里补执行某个初始化脚本（不用重建数据卷）
docker cp <ascii目录>\03-duoduo-trade.sql duoduo-mysql:/tmp/03.sql
docker exec duoduo-mysql sh -c 'mysql --default-character-set=utf8mb4 -uroot -pduoduo_root < /tmp/03.sql'

# 手动触发一轮补偿调度（超时关单 / 释放冻结 / 自动确认收货 / 入库补偿 / 支付单过期）
curl.exe -X POST http://127.0.0.1:8083/api/orders/internal/sweep -H "X-Internal-Token: duoduo-local-dev-internal-token"

# ============ S4 数据库自动备份（六库 dump + 抽验 + 滚动保留 + 失败告警）============
# 跑一次（备份根目录必须给一个**真实存在**的目录；脚本顶部的 $DefaultBackupRoot 只是占位默认值）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\db-backup.ps1 -BackupRoot 'E:\duoduo-backup'
#   → runs\<yyyy-MM-dd_HHmmss>\ 下有 6 个 .sql + manifest.json + backup.log；退出码 0 = 全绿
#   → 退出码 1 = 有失败项，已落 _ALERT.txt（配 -AlertWebhook 可推飞书/钉钉）
# 只备某个库 / 调抽验张数 / 调保留份数
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\db-backup.ps1 -BackupRoot 'E:\duoduo-backup' -Databases duoduo_trade -VerifySamples 5 -KeepRuns 14
# 注册成每天 03:00 的计划任务（刻意排在 03:30 对账选主之前；注册后会自动试跑一次并回读结果）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\db-backup-register.ps1 -BackupRoot 'E:\duoduo-backup'
# 单表恢复示例（拿产物里的某张表，恢复进指定库）
docker exec -i duoduo-mysql mysql -uroot -pduoduo_root -D duoduo_trade < runs\<时间戳>\duoduo_trade.sql
```

> 服务端口：网关 **8080**、用户 **8081**、商品 **8082**、交易 **8083**、履约 **8084**、营销 **8085**。

---

## 7.5 交接包（2026-09-18 建立，换账号接管用）

> 当前账号 token 耗尽前整理的一套**可独立接管**的资料，共 9 篇，本地在 `docs/handover/`，
> 云端同步到两处（腾讯文档「项目资产（按时间归档）/2026-09-18 交接包」与乐享知识库「多多宠物商店知识库」）。
> 逐篇 file_id / entry_id / url 见 `docs/handover/manifest.json`。

| 篇 | 主题 |
|---|---|
| 00 | 交接总纲（10 分钟接管：进度快照 / 下一步 / 命令速查 / 9 篇导航 / 密钥与权限指引） |
| 01 | 快速上手与环境搭建（9 容器、构建专用姿势、常见故障 8 条） |
| 02 | 架构与模块导航（服务拓扑 / 模块对照 / 跨域调用 / 一笔订单的数据流） |
| 03 | 环境变量与密钥（脱敏，四把钥匙与轮换建议） |
| 04 | 数据库与表结构（五库、表清单、状态枚举字典、口径约定） |
| 05 | 接口清单与契约（按域速查、认证与放行边界、错误码表） |
| 06 | 前端开发手册（目录职责、四大页面域、截图索引、浏览器专属坑） |
| 07 | 测试与验收证据（14 套 1204 项 + 5 套 180 项、脚本四条约定、历史缺陷） |
| 08 | 风险、已知边界与接手建议（I-14 等遗留、不做清单、8 条值钱的坑、接手顺序） |

**7.6 AI Agent 接管包（2026-09-18 新增，给 AI 读）**

> 与上文「人读交接包」并存、互补：人读版讲背景与取舍，AI 版讲**可执行的规则、契约与门禁**。
> 入口：仓库根 `AGENTS.md`（多数 AI 编码工具会自动读取）→ 指向 `docs/agent/00~08`。

| 篇 | 主题 |
|---|---|
| 00 | AGENT 接管指令（硬规矩 C1–C10、工作循环、完成定义 DoD） |
| 01 | 项目目标与领域模型（参与者/领域对象/主流程/术语表） |
| 02 | 代码地图与现状（文件级索引：想改什么去哪个文件） |
| 03 | 架构与不变式（INV-1~17，历史缺陷换来的硬约束） |
| 04 | 接口契约全集（端点×认证×DTO 字段×枚举×错误码，机械抽取自源码） |
| 05 | 开发任务手册（6 个配方 + 代码骨架，照抄即可） |
| 06 | 测试与验收门禁（按改动类型选必跑脚本 + 断言写法约定） |
| 07 | 待办事项与验收标准（A1~A13，逐项含接口草案与可执行验收标准） |
| 08 | 环境运维与踩坑禁忌（T-01~T-64 症状→根因→做法、10 条禁忌） |

---

**同轮一并修正的文档一致性问题**：README 容器数 7→8（当前状态口径）、冒烟合计 665→667、
`ARCHITECTURE.md` Java 17→21、`PROJECT-STATUS.md` 加「已废弃」横幅、MEMORY 台账链接修正（原指向已删除的 v1 文档）。

---

## 8. 约定（不随迭代改变）

- 金额一律整数「分」；时间一律 ISO8601 UTC
- 令牌只走 httpOnly Cookie，禁止 localStorage / URL 参数
- 错误结构 `{ error: { code, message, details }, requestId }`，不暴露堆栈
- 用户身份只从网关注入的 `X-User-*` 取，绝不接受客户端传 userId
- 中间件与业务服务一律优先容器化
- **SQL 初始化脚本一律以 `SET NAMES utf8mb4;` 开头**（防双重编码）
- **每次推送 GitHub 前必须更新 `README.md`**（与代码改动放在同一次提交里）
- 幂等一律靠**唯一索引或 CAS 条件更新**兜底，分布式锁只用于减少冲突，不作为正确性保障
- 状态用 VARCHAR 存**枚举名**（可读、便于排查）；状态文案由服务端给出，前端不重复维护映射
- **支付渠道必须可替换**：订单、库存、资金账本、回调处理一律写成渠道无关的；渠道差异只允许出现在 `PaymentChannel` 实现类里

---

## 9. 已知边界（按当前阶段刻意不做，需要时再补）

> **2026-10-11 逐条复核**：本表此前积压了 **10 条"已经做完了却还写着没做"** 的行（B-01/02/04/05/08/09/10/12/13/14/15），
> 症状是**每一行都在低估系统能力**，而且"什么时候必须处理"一栏写着"做 X 前必须先 Y"——而 X 早就做完了。
> 已逐条回查代码/DDL/提交并改成事实（证据写在行内）。
> **教训**：边界清单和功能清单一样会腐烂，而且**没有门禁** —— 它不会自己变红，只会安静地骗人。
> 新增/关闭边界时请同时写**证据**（类名、脚本名、提交号），否则下一个人没法判断这行是不是还有效。

| # | 边界 | 为什么现在不做 | 什么时候必须处理 |
|---|---|---|---|
| B-01 | ~~退款只覆盖「托管中」的资金；**已完成订单的退款需向卖家追偿或平台垫付**~~ **已解决（第五十八轮）** | 已按「平台先垫付给买家 + 在 `t_seller_debt` 记一笔卖家欠款 + 可收回/可核销」落地（`SellerDebtService`、`incremental/21-r58-seller-debt.sql`、`AdminSettlementController` 的垫付入口）；重复点不记两遍债 | 已无欠账 |
| B-02 | ~~退货入库若出现「客户端已生效、响应丢失」，重试可能重复入库~~ **已解决（第五十八轮）** | 已给入库加幂等键：`t_sku_restock_log` 主键 `(ref_no, sku_id)`，入库前先撞主键；**缺 `refNo` 直接 400**（宁可报错也不留「看起来成功实际双加」的口子）。探针 `scripts/r58-restock-idempotent-smoke.py` | 已无欠账 |
| B-03 | **服务间凭据用共享密钥** ~~`X-Internal-Token`~~ **软件侧已升级（第五十九轮）**；生产若需 mTLS / 服务网格授权仍需基础设施配合 | 已改为 `dd1.<caller>.<ts>.<nonce>.<HMAC 签名>`（覆盖调用方 + 时间戳 + nonce），**失败关闭**；`common-web/InternalCallAuth` + 各域 `InternalTokenFilter`。生产环境要更强的通道认证（mTLS）时再做 | 上生产前（做的是基础设施侧，不是代码侧） |
| B-04 | ~~**商品域 SPU / SKU 仍无 `seller_id`**——所以「商家管理自己的商品」本轮不做~~ **本条已作废（字段早就有了，功能也做完了）** | `t_spu.seller_id BIGINT NOT NULL DEFAULT 0`（`0`＝平台自营）自第四十七轮就已建好并回填，另有索引 `idx_spu_seller(seller_id, status)`；「商家自助上下架 / 编辑自己的商品」已在第五十九轮与 E14 商品上架审核中落地（`SellerProductService.requireSellerScope` / `requireOwned`） | 无（若日后改「一商品多卖家」的归属模型，届时另议） |
| B-05 | ~~补偿调度用 `@Scheduled`（单实例）~~ **已解决（第五十九轮）** | `common-web/sched/SchedLeaderLock`：抢占式续约（先 `UPDATE` 续约、影响 0 行再 `INSERT`）、锁事务 `REQUIRES_NEW` 独立提交、`expire_at` 自动过期；`duoduo.sched.leader-enabled=false` 可退回外部调度。E28 的抽查调度（`t_sched_leader`，用户库此前缺表）同样走它 | 只有要换外部调度平台（XXL-Job，见 S12）时才需要动 |
| B-06 | ~~历史订单缺托管单（见 I-14）~~ **已解决（2026-09-18 A1）** | 已提供幂等回补接口 `POST /api/admin/settlements/backfill`，开发库缺口已清零 | 生产上线时在真实数据上执行一次（先 `dryRun=true` 核对计划） |
| B-07 | **支付走沙箱渠道，不是真实资金渠道**（不入账、不结算到银行卡） | 你的决定：真实渠道资质门槛过高，先用沙箱把工程链路做实 | **上线真实收款前**：实现 `PaymentChannel` 的微信 / 支付宝版本，材料清单见协助区折叠附件 |
| B-08 | ~~**角色升级后需重新登录才生效**~~ **已解决（第五十九轮 + E17）** | 升级：`AuthService.renewAccessToken` **从库现读角色**（不接受调用方传参）→ 下一个请求即换新角色，不必重登；降级 / 运营改角色：**必须吊销全部刷新令牌**（权限变小不能留旧凭证）。E17 再加 `pv`（`permVersion`）**从网关侧**保证变更后下一个请求即生效（401 `PERM_VERSION_STALE`，客户端刷新一次即换新） | 已无欠账 |
| B-09 | ~~仍缺**退货物流**（退货退款的货怎么寄回、怎么签收）~~ **已解决（第五十九轮）** | `AfterSaleStatus` 新增 `WAITING_RETURN` → `RETURNING` 两态，**退款时点从「卖家同意」挪到「卖家确认收货」**；新增 `POST /{id}/return-shipment`（买家寄回）与 `POST /{id}/return-receipt`（卖家确认收货）。**刻意不建退货轨迹表** —— 免得「已签收」一词两义（与正向物流轨迹不是一回事） | 已无欠账（若日后要接入真实快递面单/轨迹，再另立表） |
| B-10 | ~~旧的内部凭据端点 `/api/orders/{no}/ship` 与 `/internal/**` **保留**~~ **已解决（第六十轮）** | `POST /api/orders/{orderNo}/ship`（**只验内部凭据、不验归属** = 有凭据就能发任何人的货）**已删除**，收敛为 `/api/seller/orders/{orderNo}/ship`（`requireSellerScope` + `requireOwnOrder`）；7 套脚本已先迁移，`extract-api-contract.py --check` 转 PASS。各域 `/internal/**` 仍在，但它们收 `X-Internal-Token` 且**不进网关**，属正常形态 | 已无欠账 |
| B-11 | **优惠券 v1：券不随退款退回**（退款金额为订单实付金额） | 券的资金语义（退回 / 作废 / 部分退）需要与交易域退款流程一起重设计，v1 先保证「折扣只算一次、钱数对得上」 | 做「退款退券」前必须与交易域退款流程一并设计 |
| B-12 | **优惠券 v1：只实现满减**（无折扣率 / 兑换 / 会员 / 积分）——~~且一张券只作用于拆单后商品总额最大的那一单~~ **后者已放宽（第六十轮，用户拍板）** | 门槛改按**本次下单商品总额**判定；抵扣按各子单金额**比例分摊到每一单**，取整余数补给金额最大那一单（**各单之和严格等于券面额**）；有效期改按**自然日**判定。券归属未变（仍只锁归属单订单号）。仍需保持的边界：只有满减这一种券型 | 出现「折扣率 / 兑换 / 会员 / 积分」类需求时，那是新增券型，不是放宽本条 |
| B-13 | ~~`GET /api/orders/preview` 回 404 而非 405~~ **已解决（第五十九轮）** | 已给 `/{orderNo}` 加形状约束 `@GetMapping("/{orderNo:\\d{21}}")`，拼错的 `GET /preview` 现在落到 **405**（路径命中了只支持 POST 的 `/preview`）；探针断言同时**从「404/405 均可」收紧为「必须是 405」** | 已无欠账 |
| B-14 | ~~**闲置发布后无「编辑」与「删除」**，只有上/下架~~ **已解决（第五十八轮）** | 编辑已上线：**改完内容重新送审**（堵住「先过审再改成违规内容」），价格/库存等敏感字段不在可编辑范围。删除是**软删**（`deleted_at`）——转发单与被引用的聊天记录里不会变成空白。同一轮还给发布加了频率限流（原 B-15） | 已无欠账 |
| B-15 | ~~**闲置发布限流是「单卖家条数上限」**（默认 200），不是频率限流~~ **已解决（第五十九轮）** | `ListingService.checkPublishQuota()` 加了**滑动 24h 计数（含软删，删了重发不算绕过）+ 最小间隔**（默认 10 次/天、60 秒）；新增 `BizException.rateLimited()` → **429**（与 422 语义分开）。条数上限同时保留 | 阈值可配，按上线后的风控数据再调 |
| B-16 | **秒杀活动的删除判据是「有没有未关闭订单」**（R74 定稿）：订单**全部已关闭**（CLOSED = 从未付款的终态单）的活动**可以删**；只要还有待付款 / 已付款 / 已发货 / 已完成订单就**只能下架** | 已关闭订单没有任何资金与履约链路需要追溯，若也禁止删除，则「自测/演示建一场、下单再取消」会让活动**只增不减**（R74 之前库里已积 18 场清不掉）；而未关闭订单必须留档，删活动会把「订单来源」这条链的根拔掉 | 若将来要连**未关闭订单**一起清（例如客服批量清理恶意下单），须先定规则：谁有权、是否要留审计、是否走资金回退。**不要就地放宽 `deleteActivity` 的判据**（要另开端点，把「删订单」这件事显式化） |
| B-16b | 删除活动时**连带清理 `t_seckill_order` 链接行**（仅当该活动订单全为 CLOSED），**`t_order` 一律留档** | 链接行只用于「关单时回补配额」，而回补只发生在「迁往 CLOSED 的那一刻」——订单已关闭后再无回补调用，删链接行安全；`t_order` 保留 `order_type='SECKILL'` 与备注，溯源信息不丢 | 若将来要做「秒杀活动维度」的成交报表，应从 `t_order` 取（而不是依赖链接表） |
| B-17 | **秒杀活动编辑只开放「标题 + 时间」**，商品 / 秒杀价 / 配额不可改 | 余量已扣、订单已存价格快照，改配置会让「活动配置」与既成事实不一致 | 若要做「追加配额」这类真实需求，应新增**独立的追加动作**（记一条配额变更流水），而不是把字段开放成可编辑 |
| B-18 | **全局深色主题未做**（`styles.css` 只有一套浅色变量，无 `data-theme` / `prefers-color-scheme`） | 做深色要动的是一整套：CSS 变量分层、组件逐个核对对比度、图表、图片、阴影等都要跟；只做其中一块（例如只让图表变深）会做出**浅底 + 深图**的不可读组合 | 要做得先落**全局主题**（CSS 变量 + `data-theme` 切换 + 系统偏好默认），再把 `components/chartTheme.ts` 的 `currentChartThemeMode()` 接上去（图表侧只剩这一处改动）。**在此之前不要给图表单独加 `prefers-color-scheme` 分支** —— `admin-charts-ui-check` 有断言专门拦这个半修复 |
| B-19 | **租户表白名单是显式清单，不做「列名叫 `seller_id` 就自动纳入」**（E20） | 自动发现的失效方向是**静默放开**：① 新加的租户表列名不叫 `seller_id`（例如叫 `tenant_id`、`store_id`）→ 漏；② 某张表虽然列名叫 `seller_id` 但语义不是租户（比如「推荐人」「业务员」）→ 错。两种错都**不报错**，表现都是「隔离上线了、某张表其实没隔离」。显式清单改起来只多打一行字，却让每张表的隔离状态**可被 grep 到** | 新增带租户维度的表时，必须同步在 `TenantTables` 里申报并在对应 `XxxServiceConfig` 的 `forTables(...)` 里列出（漏了不会有任何报错）。**另：本清单按「表名 → 列名」做 key，等价于断言「同名 = 同列」**（当前 `t_audit_sample` 在 product 与 user 各一张、都叫 `seller_id`，所以成立）。若将来出现**同名但列名不同**的租户表，后写的会静默覆盖先写的 → 那时必须改成按 `(库, 表)` 二元组定位（`sharedTableNames()` / `physicalTableCount()` 与单测就是为拦住这一刻而留的） |
| B-20 | **ES 侧的租户过滤今天不拦任何东西**（E20 只在 `SearchIndexer` 写入 `sellerId`、`EsSearchEngine` 追加租户 term） | 商家端「我的商品」走 MySQL，不走 ES；商城端买家请求本身没有租户上下文（`tenantId` 为空 → 不追加条件）。所以这条过滤**在所有既有链路上都不生效**，只是为「将来商家端接 ES 检索」准备的默认安全 | 一旦出现**商家端**的 ES 检索路径，此过滤会自动生效 —— 但必须补一条**端到端**断言（今天只有源码级静态断言）：`POST /api/admin/search/reindex` 后带租户头搜「别人的商品」应搜不到。**不要因为「现在用不上」把它删掉** |
| B-21 | **赠送/代运营/子账号等场景下的「跨租户合法读」靠的是角色而非租户列** | 平台运营（ADMIN/OPERATOR）与代运营的跨店可见性由**权限点**决定；E20 的租户列是「同一请求内追加行级条件」的**第二层**，不是唯一防线 —— 令牌里平台角色**不声明 `tid`**（`resolveTenantId` 第一段就返回 null），所以后台不会因为租户过滤少看数据 | 若将来要做「代运营只能看被授权的那几家店」，那需要的是**多租户白名单**语义（`tid` 从单值变集合），而不是复用当前的单值模型 —— 届时应新增列/上下文类型，**不要在 `TenantContext` 里塞集合**（会把「未设置=不追加条件」这条安全默认搞成一个半真半假的集合分支） |
| B-22 | **未启用 2FA 的平台账号仍然可以登录**（E21） | 这一项落地的是"**已绑定即强制 + 显著引导未绑定者去绑定**"，而不是"未绑定即拒绝"。原因：存量平台账号若因为"没绑定"被一律挡在门外，等于把所有人锁死 —— 而**绑定入口本身也需要登录态**，于是形成死锁（要进后台先绑 2FA、要绑 2FA 先进后台）。当前用"绑定入口挂在 `/api/auth/2fa/**`（不在 `/api/admin/**` 之下）"来解开这个环 | 若要收紧成"未绑定即拒绝"，需要先提供一条**绑定专用令牌**通道（只允许访问 `/api/auth/2fa/**`），再把"账号是 OPERATOR/ADMIN 且未绑定"的登录也拦下。届时应新增令牌类型或声明，**不要**把 `/api/admin/**` 放行给未绑定的令牌（那等于回到今天） |
| B-23 | **`PUT /api/seller/products/{spuId}`（编辑商品）仍允许平台运营修改第三方店铺的商品**（E23 收窄时**刻意没顺手扩范围**） | 上下架（`changeStatus`）已收窄为「第三方必须凭工单」，编辑没有 —— 于是存在一条**绕开工单**的路径：平台运营在商家站点登录（登录口**刻意**对运营开放，注释写明「用于代运营与排障」）→ `ent=merchant` 过网关 → `requireSellerScope()` 给出 `platformScope=true` → `requireOwned` 放行 → 而 `update()` **不调用** `requireProxyForThirdParty` → 改第三方商品成功。**为什么不借 E23 一起改**：编辑的**影响面与回滚方式**都与上下架不同（改了内容要不要重新送审？要不要留档改前值？），硬塞进一次收窄会让「这次上线改了什么」说不清 | 用户验收轮**之后**单独评估（改法很明确：把 `requireProxyForThirdParty` 接到 `update()` 上，并补一条「无工单改第三方商品 → 403」的实测）。**在此之前不要单独改这一处** —— 会动到已通过验收的基线 |
| B-24 | **Long 的「按字段名保持数字」白名单：新加金额/计数字段若名字不在名单里，会被静默字符串化成 `"12900"`**（`LongToStringJacksonModule`） | 判据是**字段名**不是 Java 类型（全站金额字段 63 处都是 `Long`，一刀切会让前端 `price/100` 算出 `NaN`）。**2026-10-11 实测更正**：该白名单对**普通 POJO 与 record 同样生效**（`LongToStringJacksonModuleTest` 4/4；源码里原写着「record 不流经 `changeProperties`、必须逐个标注」，**该说法已作废**）。因此真正的漏法只有一种：字段名没登记（如新写的 `extraFee`），**不报任何错**，只表现为前端 `===` 恒 false / `toFixed` 抛错 | 新增「前端会拿去做加减乘除或比大小」的 long 字段时，**把它加进 `NUMERIC_LONG_FIELDS`**（不要给单个 record 打补丁——那是可选的保险丝，不是必需补偿）。另：既有 34 处显式 `@JsonSerialize(NumericLongSerializer)` 保留不动 |


---

## 7. 需求池（未排期）

### R-P1 · 每日签到与「多多币」体系（2026-10-18 你提出；**仅记录，本次不实现**）

**口径（按你的原话固化）**

- 每日签到得「多多币」；连续签到额外赠送；全站「积分」统一改称「多多币」。
- 抵扣：**100 多多币 = 1 元**（即 1 币 = 1 分）。
- 可与**所有优惠券叠加**，适用于**所有商品类型（含秒杀）**。
- 消费按**实际支付金额 1:1** 得币：实付 100 元 → 100 币；365 元 → 365 币。

**实现前必须拍板的规则**

1. 连签奖励梯度（第 2~7 天各多少；断签是否清零）。
2. 单笔抵扣上限（是否有「不超过订单金额 x%」；能否抵到 0）。
3. 币的有效期与扣减顺序（先扣快过期）。
4. 退款/取消时币的回收规则（退币还是作废；已抵扣部分如何退）。
5. 是否支持补签、每人每日上限、防刷（同设备 / 同 IP）。
6. 签到是否同时发券（与券体系联动）。

**影响面**

- 用户域：币账户 + 流水（幂等键＝用户 + 业务单号 + 类型）。
- 营销域：签到日历、连签计数、发币任务。
- 交易域：金额计算顺序改为「商品总额 − 券 − 币」；秒杀链路复用同一处（本次已支持券叠加）。
- 三端 UI：签到入口、我的多多币、结算页抵扣开关。
- 对账/报表：发放、消耗、退回三类流水必须能对平。

**建议实施顺序**：账户与流水 → 签到发币 → 下单抵扣 → 支付后 1:1 返还 → 三端展示 → 对账。

### R-P2 · 在线客服「问客服」（2026-10-18 你提出；**入口已上线，会话功能未实现**）

**现状**：商品详情 / 订单列表 / 订单详情三处已有统一「问客服」入口，点开明确告知"人工客服建设中，暂未开放接待"，并提供 AI 导购替代。

**评估结论**：不建议「所有平台人员可见、均可回复」直接上线 —— 责任归属（多口径、承诺无主）与响应归属（首响/解决算谁的、重复回复）会先于技术出问题。完整评估见工作区《问客服-直连平台消息方案评估-2026-10-18.md》。

**建议模型**：一会话一责任人；认领即锁定；只有当前责任人能对客发言（服务端拒绝协作者发送）；转交留审计；手机号/地址默认脱敏；新增 `support:agent` / `support:supervisor` 权限点。

**需你拍板**：① 归属模型；② 服务时间与离线承诺；③ 补偿权限上限；④ 脱敏范围与二次授权。

**若实施的分阶段**：P1 会话 + 认领 + 唯一发言权 + 审计 + 脱敏 → P2 转交 / SLA 看板 / 标准话术 / 满意度 → P3 技能组路由与订单售后联动。验收要点见评估文档第五节。
