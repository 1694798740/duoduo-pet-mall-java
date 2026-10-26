# 多多宠物商店 · Java 微服务版

> Java 21 · Spring Boot 3.2.5 · Spring Cloud 2023.0.1 · Spring Cloud Alibaba 2023.0.1.0 · MyBatis-Plus 3.5.5
> 依据《多多宠物商店 · 产品需求原型 PRD v0.1》重做，替代此前的 Node/Express 原型实现。
> 架构设计见 [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md)；开发看板见 [`docs/PLAN.md`](./docs/PLAN.md)。

**2026-10-26（九十六）· share-circle e2e 修绿（J-02）**

- **结论**：历史「删除后列表不消失」不是功能缺陷；脚本默认打 Vite dev server（5173），
  而验证环境是容器站点（8088），一跑就连接拒绝。
- **修复**：`share-circle-ui-check.mjs` 默认 `UI_BASE` 改为已部署商城站点；开发态显式覆盖。
- **验证**：复跑 **21/0**（发布 / 话题 / 评论 / 点赞 / 编辑 / 删除后列表消失 / 话题聚合 / 未登录入口）。

**2026-10-26（九十五）· 商品变更历史独立页（H-03）**

- **问题**：A-08 只能先知道商品 id 再看时间线；运营复盘「谁把哪件商品改成了什么」没有检索入口。
- **接口**：`GET /api/admin/products/change-history`，按商品 / 标题关键词 / 事件 / 店铺过滤并分页，
  每条带商品标题、店铺归属、事件 / 操作人 / 版本与字段级改前改后。
- **口径**：数据仍取 append-only `t_spu_audit_log`；商品标题用当前值定位，事件当时的标题以 diff 为准。
- **前端**：运营后台新增「变更历史」页与导航（紧邻商品审核），筛选 + 分页 +「查看改动」弹窗。
- **边界**：只读、仅运营；卖家 403、未登录 401；自营商品不进本表。
- **验证**：product 单测 **265/0**（新增 2）、三端构建全绿；H-03 冒烟 **19/0**、H-03 UI e2e **11/0**；
  product / admin-web 镜像重建，17 容器 healthy。

**2026-10-26（九十四）· 三端 UI 对齐审计（G-04）**

- **结论**：客服一期的三类 UI 遗留（未读标记 / 二级菜单 / 灯箱）已随聊天迭代全部落地，本轮只做逐页对照与证据固化。
- **未读**：买家 Web 顶栏 + 菜单 + 列表 + 逐条；商家 Web 导航角标 + 会话行；小程序 tabBar 合并角标 + 列表 + 逐条。
- **二级菜单**：买家 / 商家 Web 共用 `ChatMediaMenu`（图片 / 视频 / 表情 / 商品 → 表情 + 贴纸）；
  小程序用「😀 / ＋ / 🎤」常驻入口 + 表情 / 贴纸页签，属移动端交互适配。
- **灯箱**：买家 / 商家 Web 共用 `ImageLightbox` + `VideoPlayerModal`；小程序图片走 `wx.previewImage`。
- **验证**：`support-ux-ui-check.mjs` **53/0**（未读样式 / 二级菜单 / 灯箱缩放 / 视频全屏 / 商家端会话）；
  小程序为代码级对照，真机视觉归 G-03 外部条件。

**2026-10-26（九十三）· 店员权限细分（F-04）**

- **问题**：店员只有 OWNER / STAFF 两级角色，一旦被邀请就能改全部商品、改价、发货、退款、回复客服，
  店主无法表达「只能发货、不能改价」。
- **权限模型**：6 个操作级权限键 —— 商品内容编辑 / 改价改库存 / 上下架删除 / 订单发货 / 售后退款 / 客服回复；
  店主恒为全量，按店员成员行单独配置。
- **存储口径**：`t_merchant_member.permissions` 逗号分隔；`NULL` = 历史行按全量（升级不缩小范围），
  空串 = 显式「仅查看」；新邀请默认全量。
- **接口**：`POST /api/seller/staff/{memberId}/permissions`（OWNER-only，审计 `UPDATE_STAFF_PERMISSIONS`）；
  未知键 / 缺字段 400，店主行 422，幂等重复提交不写库。
- **门禁**：商品新建 / 编辑 / 复制 / CSV 导入、改价、上下架、发货、退款、客服回复分别按权限判定；
  规格结构保存同时要编辑权 + 改价权；只读接口对在职店员不设限。
- **前端**：店员管理页新增「权限」列与配置弹窗（全选 / 清空），保存后立即生效。
- **数据脚本**：`incremental/59-staff-permissions.sql`。
- **验证**：common **149/0**、user **175/0**、product **263/0**、trade / social 单测全绿、三端构建全绿；
  F-04 冒烟 **37/0**、F-04 UI e2e **11/0**；回归 C-04 **32/0**、E13 店员体系 **24/0**；
  user / product / trade / social / merchant-web 镜像重建，17 容器 healthy。

**2026-10-26（九十二）· 售后按商品 / SKU 退款金额统计（D-05）**

- **问题**：售后页只有订单级售后单，看不出「哪个商品 / 规格退得最多」；商品分析只有退款件数、没有金额。
- **口径**：取订单明细（下单 SKU 快照），`refund_status IN (REFUNDING, REFUNDED)` 计入退款件数 /
  退款金额（明细行金额）/ 涉及订单数（按订单去重）；不在售后单上按比例分摊。
- **接口**：`GET /api/seller/product-refunds`，按 `spu_id + sku_id` 分组，上限 200 条，归属沿用卖家可见范围。
- **前端**：售后页顶部新增「退款商品 / SKU 统计」卡片：合计退款金额与件数 + 逐 SKU 明细。
- **序列化**：`quantity / refundQty / refundOrderCount` 补入 Long 计数白名单，避免端侧求和变字符串拼接。
- **验证**：trade 单测 **119/0**、common **149/0**、common-web **64/0（5 skipped）**、商家端 tsc 全绿；
  D-05 冒烟 **17/0**、D-05 UI e2e **12/0**；回归 A-17 冒烟 **53/0**；trade / merchant-web 镜像重建，
  17 容器 healthy。
- **风险**：退款金额是订单明细快照金额，不是售后单实际退款额；部分退款 / 扣运费未拆到 SKU 时会有偏差。

**2026-10-26（九十一）· 聊天内容质检 / 敏感词拦截（C-05）**

- **问题**：客服与私信没有内容质检，站外链接、转账 / 验证码、刷单返利等可以原样发出，
  等运营发现时对方已经读完。
- **规则**：`ChatRiskJudge` 四类风险 —— 联系方式、站外链接 / 域名、资金诈骗词、配置敏感词；
  NFKC + 去空白 / 零宽归一化，挡「加 微 信」「１３８…」。
- **处置**：链接 / 诈骗 / 敏感词 422 拦截且消息不落库；手机号 / QQ / 微信默认只告警，
  `duoduo.chat.block-contact=true` 可切换拦截。绝不静默改字。
- **范围**：买家客服消息、客服回复、私信三处统一走 `ChatModeration`，日志记录分类与脱敏片段。
- **配置**：`duoduo.chat.moderation-enabled / block-contact / sensitive-words`。
- **验证**：social 单测 **36/0**（新增 10）、common **149/0**、common-web **64/0（5 skipped）**；
  C-05 冒烟 **16/0**、C-05 UI e2e **11/0**；回归客服生命周期 **32/0**、C-04 **32/0**；
  social 镜像重建，17 容器 healthy。
- **风险**：只检查文字，图片 / 语音里的二维码与违规内容不识别；告警落地为日志，未接告警平台。

**2026-10-26（九十）· 客服工单认领 / 转交 / 审计（C-04）**

- **问题**：店主与店员共用客服工作台，但没有「谁在接」的概念 —— 可能重复回复，
  交接班说不清进度，出问题也查不到谁转给了谁。
- **数据**：`t_support_session` 增加 `assignee_id / assigned_at`；新增 append-only
  `t_support_ticket_log`（CLAIM / TRANSFER / RELEASE + 前后处理人 + 操作者 + 备注）。
- **认领**：条件 UPDATE（`assignee_id IS NULL`）保证并发只有一人成功；重复认领幂等；
  其他人收到 409 与「被谁认领」；已关闭会话不可认领。
- **转交 / 释放**：仅当前处理人或店主可操作；目标必须是本店在职成员
  （用户域新增 `/internal/merchant-members`，店主始终在名单里）。
- **不是访问控制**：处理人只表示「谁在接」，全店仍可见会话；买家侧在服务端抹掉该字段。
- **前端**：会话行显示「处理人 / 未认领」，聊天头部「工单」弹窗内认领 / 释放 / 转交，
  处理记录逐条展示操作者、前后处理人与备注。
- **数据脚本**：`incremental/58-support-ticket.sql`。
- **验证**：social 单测 **26/0**（新增 10）、common **149/0**、common-web **64/0（5 skipped）**、
  商家端 tsc 全绿；C-04 冒烟 **32/0**、C-04 UI e2e **17/0**；回归客服生命周期 **32/0**；
  social / user / merchant-web 镜像重建，17 容器 healthy。
- **踩坑**：新 Mapper 必须放在 `@MapperScan` 覆盖的 `com.duoduo.social.mapper`；
  老店铺可能没有 OWNER 成员行，成员名单要显式补店主。

**2026-10-26（八十九）· 电子面单 / 物流适配层（B-05）**

- **问题**：发货只能手填物流公司与单号，没有平台面单凭据；轨迹只能靠外部手工推送，
  本地买家页的「物流轨迹」几乎永远是空的。
- **适配层**：`LogisticsProvider` 接口 + `SimulatedLogisticsProvider`（编码 + 12 位雪花低位）；
  快递公司白名单 `Couriers`（SF/YTO/ZTO/STO/YD/JD/EMS）。接快递鸟/菜鸟时只替换实现。
- **数据**：新增 `t_waybill`，`uk_waybill_order` 保证一单一张面单；重复申请返回同一张，
  并发撞唯一键后回读；`mode=SIMULATED/EXTERNAL`，状态 CREATED → … → SIGNED。
- **接口**：`POST /api/logistics/waybills`（卖家/运营，订单须 PAID/SHIPPED，幂等）、
  `GET /api/logistics/orders/{no}/waybill`、`GET /api/logistics/couriers`；
  内部 `POST /api/logistics/internal/waybills/{no}/advance`（模拟推进，HMAC 凭据）。
- **发货衔接**：用面单号走既有发货接口，交易域补推「已揽收」；
  `WaybillSimulationJob` 默认 30 秒一步推进到签收，只对模拟面单生效。
- **前端**：商家端发货弹窗新增快递下拉 + 「获取电子面单」，自动回填公司/单号并明示「模拟面单」；
  订单详情时间线展示四个节点。
- **数据脚本**：`incremental/57-waybill.sql`；`schema.sql` 与 `docs/sql/04` 同步。
- **验证**：fulfilment 单测 **79/0**（新增 15）、common **149/0**、common-web **64/0（5 skipped）**、
  商家端 tsc 全绿；B-05 冒烟 **36/0**、B-05 UI e2e **20/0**；回归 A-16 冒烟 **55/0**、A-16 UI **29/0**；
  fulfilment / merchant-web 镜像重建，17 容器 healthy。
- **踩坑**：内部接口不经网关，冒烟必须直连履约域 `8084` 并带 HMAC 凭据；
  走网关 `8080` 会先被 JWT 过滤器拦成 401。

**2026-10-26（八十八）· 商品复制 / CSV 导入导出 / 曝光-点击诊断（A-17）**

- **复制**：`POST /api/seller/products/{id}/copy`，复制内容与全部规格，标题追加「（副本）」，
  **副本默认下架**、销量/评分归零；复用 `create` 保证类目模板、违禁词、审核判定、图片表与审核历史不被绕过。
  跨店复制租户层 404、平台运营复制第三方商品 403、回收站商品 404。
- **导出**：`GET /api/seller/products/export`，一个规格一行，16 列分组模板；UTF-8 BOM、RFC4180 转义、
  公式注入防护；普通卖家只导出本店商品，平台视角上限 1000 件。
- **导入**：`POST /api/seller/products/import`，按「分组键」聚合多规格；逐组独立事务、部分成功，
  HTTP 200 只代表处理完成；上限 2MB / 500 行 / 100 个商品；`GET /import-template` 提供模板。
  商家端商品管理新增「导入 CSV / 导出 CSV」与行内「复制」，导入弹窗展示逐组行号与原因。
- **统计**：新增 `t_product_daily_stat`，公开列表 / 搜索 / 店铺 / 首页记曝光（请求内去重），
  详情成功打开记点击；统计写失败只 WARN，不影响读路径。
- **诊断**：`GET /api/seller/products/diagnostics?days=30`（上限 90），返回曝光 / 点击 / 点击率与
  「先拦路后优化」建议；交易域 `product-stats` 新增 `orderCount`，商品分析页合并计算点击成交率
  （无点击显示「—」）。
- **数据**：`incremental/56-product-csv-diagnostics.sql`；`schema.sql` 与 `docs/sql/02` 同步。
- **验证**：product 单测 **259/0**（新增 20）、trade **119/0**、common **149/0**、common-web **64/0（5 skipped）**；
  三端 `tsc --noEmit` 全绿；A-17 冒烟 **53/0**、A-17 UI e2e **25/0**；
  回归 A-13 冒烟 **53/0**、A-16 冒烟 **55/0**、A-16 UI **29/0**；
  product / trade / merchant-web 镜像重建，17 容器 healthy。
- **踩坑**：`exposure / clicks` 未进 `LongToStringJacksonModule` 白名单 → 以字符串下发，
  前端 `+` 变拼接；已与 `orderCount` 一并加入计数白名单。

**2026-10-26（八十七）· 商家回复评价（A-16）：一条评价一次回复，匿名评价不泄身份**

- **问题**：评价区一直单向 —— 商家没有公开回应入口，误会、差评、售后承诺只能私下处理，
  「商家已回复」这个最基本的信任信号缺失。
- **模型**：`t_review` 新增 `reply_content / reply_at / replied_by`；**一条评价最多一次回复**，
  首次回复后不可修改 / 删除（409），回复不改变评分、审核态与追评。
- **门禁**：只有 `APPROVED` 评价可回复（`PENDING` → 422 待审核，`REJECTED` → 422 已下架）；
  商家只能回复本店评价（跨店在租户过滤层 404，服务层 `requireReplyScope` 第二道防线）；
  普通买家 403、未登录 401；平台自营（`seller_id=0`）允许运营代回复。
- **内容安全**：回复复用 `ReviewRiskJudge`，命中手机号 / 链接 / 导流话术 / 违禁词直接 422 退回
  （回复区无人工审核队列）；空白 / 超 500 字 400。并发用条件 UPDATE（`reply_content IS NULL`）兜底。
- **接口与 DTO**：`GET /api/reviews/seller`（只含 APPROVED，`replied / rating / keyword` 筛选）、
  `POST /api/reviews/{id}/reply`；`SellerReviewView` 不含 `userId / avatar / sellerId`，匿名昵称脱敏为「匿名用户」。
- **前端**：商家端新增「评价管理」（仅店主可见）：待回复 / 已回复 / 全部三页签 + 搜索 + 站内回复弹窗；
  买家商品详情与「我的评价」展示「商家回复 · 时间」。
- **数据**：`incremental/55-review-seller-reply.sql`；动作登记 `REVIEW_REPLY`。
- **验证**：fulfilment 单测 **64/0**（新增 13）、common **149/0**、common-web **64/0（5 skipped）**；
  A-16 冒烟 **55/0**、A-16 UI e2e **29/0**；回归 H-02 冒烟 **47/0**、H-02 UI **18/0**、
  A-15 冒烟 **45/0**、A-15 UI **24/0**、评价接口 **62/0**、买家评价 UI **24/0**；
  fulfilment / user / web / merchant-web 镜像重建，17 容器 healthy。

**2026-10-26（八十六）· 评价机审（A-15）：干净内容自动放行，命中风险转人工，机器不自动下架**

- **问题**：评价提交后直接 APPROVED 公开，联系方式 / 站外链接 / 导流话术只能靠运营事后翻列表；
  等人工看到时内容已经展示过，「没翻到」等于没审。
- **策略**：机审只做「放行 / 转人工」，绝不自动下架。干净内容直接 APPROVED（`audit_source=AUTO`），
  命中风险转 `PENDING` 并写明原因，最终由运营在 H-02 页面通过 / 下架。
- **规则**：`ReviewRiskJudge` 纯函数，五类风险 —— 手机号 / 座机、站外链接 / 域名、导流话术、
  广告与违禁品词、运营配置敏感词（`duoduo.review.sensitive-words`）。NFKC + 去空白 / 零宽归一化，
  防「加 微 信」「全角手机号」；不把裸词「微信」「二维码」当风险；审核原因里的手机号脱敏。
- **服务层**：提交与追评都过机审；追评命中时整条评价回 PENDING 并触发评分重算；
  已下架评价拒绝追评（422）；机审转人工时显式清空 `audited_by`（MP 默认不写 null）。
- **数据**：`incremental/54-review-audit-source.sql` 补 `audit_source`（AUTO / MANUAL / NULL 历史）。
- **UI**：后台新增「待审核」Tab 与「通过」动作、状态列展示审核来源；买家「我的评价」显示
  「审核中 / 已下架」及说明；提交后提示「其中 N 条审核中」。
- **验证**：fulfilment 单测 **51/0**（新增 17）、common **149/0**、common-web **64/0（5 skipped）**；
  A-15 冒烟 **45/0**、A-15 UI e2e **24/0**；回归 H-02 冒烟 **47/0**、H-02 UI **18/0**、
  评价接口 **62/0**、买家评价 UI **24/0**；fulfilment / web / admin-web 镜像重建，17 容器 healthy。

**2026-10-26（八十五）· 评价管理（H-02）：违规评价可下架 / 恢复，公开口径与商品评分同步重算**

- **问题**：`t_review.audit_status` 恒为 APPROVED，后台没有评价入口 —— 广告导流 / 人身攻击 / 泄露隐私的评价只能改库；
  公开评价区、评分汇总、卖家信用分虽已按 APPROVED 过滤，却没有路径把已公开评价变成 REJECTED。
- **接口**：`GET /api/admin/reviews`（审核态 / 星级 / 关键词 / 分页）与 `POST /api/admin/reviews/{id}/audit`
  （`REJECT` 下架、原因必填；`APPROVE` 恢复）。条件 UPDATE 保证并发只有一次生效；已处于目标态幂等成功且零写库，
  并发状态变化 0 行后重读仍非目标态则 409。动作登记 `REVIEW_AUDIT` 并写审计台账。
- **口径与补偿**：公开读路径本来只统计 APPROVED，本轮不改口径；新增 `ReviewVisibilityChanged` 事件，
  下架 / 恢复后按 SPU 重算评分，**聚合为空时显式推 0 分 / 0 条**（下架最后一条评价后商品页不能停在旧评分）。
- **数据**：`incremental/53-review-audit.sql` 补 `audit_reason / audited_by / audited_at`；恢复时显式清空原因。
- **后台**：新增「评价管理」标签页：审核态 / 星级 / 关键词筛选，详情含正文、晒图、追评与审核台账；
  下架走站内弹窗且原因必填，恢复走 `useConfirm`；操作审计页补 `REVIEW_AUDIT` 中文映射。
- **验证**：fulfilment 单测 **34/0**（新增 11）、common **149/0**、common-web **64/0（5 skipped）**；
  H-02 冒烟 **47/0**、后台 UI e2e **18/0**；回归评价接口 **62/0**、买家评价 UI **24/0**、网关路由 **313/313**；
  fulfilment / gateway / user / admin-web 镜像重建，17 个容器 healthy，夹具零残留。
- **踩坑**：新增 `AuditAction` 必须同步重建审计表所在域（`duoduo-user`），否则收件方用旧枚举解析会静默落成 `UNKNOWN`。

**2026-10-26（八十四）· SKU 增删改（A-14）：全量集合保存 + 订单占用禁删 + 结构变更重审**

- **问题**：SKU 只能在发布时创建，卖家加 / 删规格只能删商品重发，销量、评价、审核历史一起丢；
  直接放开删除又会让卖过的规格从订单里失去追溯对象。
- **接口**：`PUT /api/seller/products/{spuId}/skus` 全量集合语义（`id` 空 = 新增，
  库里有请求没有 = 删除）；一次保存只触发一次重审 / 一次版本递增 / 一次价格区间重算；
  `version` 冲突 409 且整单零写入。新增 `GET /{spuId}/skus/usage` 只读返回 SKU → 订单数，
  UI 打开弹窗即标出「已有 N 笔订单，不可删除」。
- **删除门禁**：交易域 `t_order_item`（卖过没有）+ 商品域 `frozen_stock`（在途冻结）两组判据缺一不可；
  跨域走 Feign + `X-Internal-Token`，交易域不可用时**拒绝删除（fail-closed）**。
- **审核联动**：新增 / 删除 / 改名 / 换规格图 → 重审 + `version + 1`，规格名一并送进风险判定；
  仅改价 / 库存 → 不重审、不升版本；无变化零写。审核历史追加 `field=skus` 的前后 diff。
- **前端**：商品列表新增独立「规格」弹窗；结构变更 `useConfirm` 明说重新审核，
  仅改价 / 库存 toast 明说免审；有订单的行删除按钮禁用。
- **验证**：product 单测 **239/0**（A-14 新增 15）、trade **119/0**、A-14 冒烟 **56/0**、UI e2e **26/0**；
  回归 r93 **43/0**、A-09 **64/0**、A-11 **52/0**、A-12 **56/0**、A-13 **53/0**、
  商品管理 UI **22/0**、A-09 UI **19/0**；product / trade / merchant Web 镜像重建，容器 healthy，夹具与 ES 零残留。

**2026-10-25（八十三）· 商品批量上下架（A-13）：整体 200 + 逐条结果，部分失败不回滚**

- **问题**：卖家只能逐件上下架，整理一批商品要发几十个请求；若整批包事务，一件未过审会把已成功的条目一起回滚，
  卖家只能重新猜哪些生效。
- **接口**：`POST /api/seller/products/batch-status`（`{ids, action}`）；ids 去重保序、上限 100；
  **整体恒 200**，成败只看逐条 `success`，每条带 `status / statusLabel / errorCode / message`。
- **语义**：逐条复用单品门禁（归属、代运营限制、上架需 APPROVED + 店铺资质有效、下架不限、软删 404、不动 version）；
  `SellerScope` 只解析一次。**刻意不加事务** —— 部分失败不回滚；重复 id 去重，已处于目标态幂等成功且不写库。
- **前端**：仅「我的商品」显示选择列与批量栏；勾选只作用于当前页，支持全选本页 / 清空；
  切页、搜索、切审核筛选均清空选择。`useConfirm` 明示「部分失败不回滚」，结果弹窗逐条展示成功状态 / 失败原因。
- **验证**：product 单测 **224/0**（A-13 新增 7）、A-13 冒烟 **53/0**、UI e2e **24/0**；
  回归 r93 **43/0**、A-09 **64/0**、A-11 **52/0**、A-12 **56/0**、商品管理 UI **22/0**、A-09 UI **19/0**；
  product / merchant Web 镜像重建，17 个容器 healthy，A13 商品与 ES 文档零残留。

**2026-10-25（八十二）· 搜索索引自动同步（A-12）：3 秒扫描 + 失败重试 + 形态自愈，变更 5 秒内可搜**

- **问题**：发布 / 编辑 / 上下架 / 审核不触发 ES，索引只能手工重建；A-09 验收实测到约 15s 增量窗口，
  极短命周期的「创建 → 删除」还会留残文档。
- **方案**：`SearchSyncJob` 每 3s 扫 `updated_at` 窗口（回退 2s 重叠），SKU 变动连带其 SPU；
  单条失败进 pending 下轮重试；启动 / 索引重建 / 人工 reindex 走全量对齐并清孤儿。物理清理后
  `SpuPurged` 事件在事务提交后立即摘文档，失败进 pending 由增量补刀。
- **自愈**：`ensureIndex` 每轮校验映射形态（`docType` keyword、`titlePinyin` = `pinyin_ngram`）——
  索引被删后任何写入都会让 ES 用**动态 mapping** 静默建回同名索引，只看 `exists` 会永久认为就绪。
- **选主**：日常租约 `max(15s, 3×间隔)`，全量 120s；新增 `@PreDestroy` 优雅释放，滚动发布不必等 TTL；
  全量结束把扫描基准钉在起点，锁等待期的变更不漏。ES 数据改挂命名卷 `es-data`。
- **验证**：common-web 单测 **64/0**（5 skipped）、product **217/0**；A-12 冒烟 **56/0**
  （十类变更 2790–3352ms 全部 ≤5s，含失败重试、删索引自愈、ES 停机再启、零残留）；
  回归 A-09 **64/0**、A-10 **56/0**、A-11 **52/0**、r93 **43/0**、商品管理 UI **22/0**；
  17 个容器 healthy；对账 59=59、missing/extra/stale=0。

**2026-10-25（八十一）· 商品图片表（A-11）：排序 / 类型 / 审核态独立成表，CSV 双写兼容**

- **问题**：`t_spu.images` 是逗号串，只有录入顺序，没有主图 / 副图类型与单张图片审核态；
  排序只能整串重写，图片审核只能跟着商品走。
- **方案**：新增 `t_spu_image`（`incremental/52`）：`image_type(MAIN/GALLERY)`、`sort_no`、
  `audit_status`，唯一索引 `(spu_id, image_type, sort_no)`；MAIN 恒 sort 0，GALLERY 从 1 连续。
  递归 CTE 幂等回填历史 CSV，主图重复 / 空项剔除，审核态跟随 SPU。
- **双写**：发布 / 编辑后 `SpuImageService.sync` 按 `main_image + images` 同步；形状不变只改审核态
  （ID 稳定），形状变化才重建。**本轮 CSV 仍是权威读路径**，图片表先接管排序与审核态，避免同时切换两套读口径。
- **审核 / 清理**：通过 / 驳回 / 重提 / 抽查判违规同步图片审核态；软删保留图片行，回收站物理清理时
  同事务删除，杜绝孤儿。删除类目一并清理其属性模板（A-10 遗留）。
- **验证**：商品单测 **207/0**（A-11 新增 11）、A-11 冒烟 **52/0**；回归 r93 **43/0**、A-10 **56/0**、
  A-08 **52/0**、A-09 **64/0**、商品管理 UI **22/0**；product 镜像重建，17 个容器 healthy，
  回填与双写、审核联动、purge 级联均通过回库断言。

**2026-10-25（八十）· 类目属性模板（A-10）：必填缺失拒绝发布，老商品不被后来新增的模板阻断**

- **问题**：规格参数是自由 KV，运营无法要求「净含量必填」「适用阶段只能选固定值」，发布质量与后续筛选没有可依赖口径。
- **方案**：新增 `t_category_attr`（`incremental/51`，`category_id + name` 唯一索引）；**不建属性值表**，
  值仍存 `t_spu.spec_params`、按属性名匹配，模板只做发布 / 改规格时的校验闸门，存量商品零回填。
- **校验**：新建始终校验；编辑仅在**改规格或换类目**时校验。必填缺失一次性汇总；
  NUMBER 用 `BigDecimal` 判数字；SELECT 必须在选项内；停用项立即失效、公开接口不可见。
- **接口**：`GET /api/categories/{id}/attrs`（公开只读启用项）；运营
  `GET|POST /api/admin/categories/{id}/attrs`、`PUT|DELETE /api/admin/category-attrs/{id}`、
  `PUT /api/admin/category-attrs/{id}/status`；四个动作独立审计。
- **前端**：后台类目行新增「属性模板」维护弹窗；商家发布 / 编辑弹窗按模板渲染 TEXT / NUMBER / SELECT，
  与自定义 KV 合并提交；顺带修复类目搜索的 TDZ 崩溃。
- **验证**：商品单测 **196/0**（A-10 新增 15）、A-10 冒烟 **56/0**、A-10 UI e2e **35/0**；
  回归 r93 **43/0**、A-08 **52/0**、A-09 **64/0**、audit-sla **62/0**、商品管理 UI **22/0**；
  product / user / gateway 与三端 Web 镜像重建，17 个容器 healthy，模板与 `spec_params` 回库断言通过。

**2026-10-24（七十九）· 商品删除与回收站（A-09）：软删 30 天可恢复，前台/审核/检索处处不可见**

- **问题**：卖家只能下架不能删除，废弃或违规商品长期堆在「我的商品」；误删也没有恢复机制。
- **方案**：`t_spu.deleted_at` 软删（`incremental/50`）；删除只写这一列并把状态压成 `OFF_SHELF`，**审核态不动**；
  30 天保留期内可恢复（恢复后仍为下架态），超期由每日任务物理删除 SPU+SKU，**审核历史保留**；
  删除 / 恢复不参与乐观锁版本，并发安全由 `WHERE deleted_at IS NULL` 条件更新兜底。
- **可见性**：前台详情/列表/ES 命中回表、购物车可售性、收藏与足迹、商家列表与审核队列、抽检候选、代运营、
  类目均价基准全部排除回收站；回收站独立视图返回 `deletedAt/purgeAt`。
- **接口**：`POST /api/seller/products/{id}/delete`、`POST /api/seller/products/{id}/restore`、
  `GET /api/seller/products?deleted=true`、`POST /api/admin/products/recycle/purge`。
- **前端**：商家端「我的商品 / 回收站」双视图；删除用自定义确认弹窗（无原生 confirm），恢复后保持下架并提示重新上架。
- **验证**：商品单测 **181/0**（A-09 新增 11）、A-09 冒烟 **64/0**、商家端 e2e **19/0**；
  回归 r93 **43/0**、audit-sla **62/0**、审核 e2e **48/0**；17 个容器 healthy，回库断言删除/恢复/清理均生效。

**2026-10-24（七十八）· 商品审核历史与变更留痕（A-08）：改判与多次驳回可追溯，抽检提示版本漂移**

- **问题**：`t_spu` 只有审核四列「当前值」，运营改判一次就把上一次驳回覆盖；抽查任务也不记录抽样时的版本，
  卖家抽中后改内容，运营可能对着旧版本下结论。
- **方案**：新增 append-only `t_spu_audit_log`（`incremental/49`），记录提交 / 编辑 / 重提 / 通过 / 驳回 /
  抽查判违规六类事件、操作人快照、前后状态与版本、字段级 diff；五条写路径全部与业务**同事务**，自营免审不写。
- **读接口**：`GET /api/admin/products/{spuId}/history?limit=50`（运营/管理员，未知商品 404，上限 100，新事件在前）。
- **抽检**：`t_audit_sample.spu_version` 记录抽样时版本（旧任务留空，不凭空回填）；复核台提示
  「内容已变更 v抽中 → v当前」，只提示不阻断。
- **前端**：审核台新增「审核历史」时间线（状态流转 / 操作人 / 版本 / 驳回原因 / 字段级前后对比）；
  抽检页新增漂移徽标与复核弹窗警示。
- **验证**：商品单测 **170/0**、A-08 冒烟 **52/0**、审核 e2e **48/0**、r93 **43/0**、audit-sla **62/0**；
  三端构建 + 镜像重建，站点均 200。回库断言 5 条事件顺序、actor 与 diff 正确，夹具零残留。

**2026-10-24（七十七）· 商品编辑/审核乐观锁（A-07）：并发冲突返回 409，不再整行覆盖**

- **问题**：编辑走 `updateById` 整行回写，审核按 id 定列更新；并发时编辑可把刚落的 `REJECTED` / 驳回原因
  覆盖回 `APPROVED`，双方都 200。
- **方案**：`t_spu` 增加 `version`（`incremental/48`）；编辑与审核必须带调用方读到的版本，不一致 **409** 且不写库；
  成功写入是一条 `WHERE version=?` + `version=version+1` 的条件更新，影响 0 行即冲突。重新提审以 `REJECTED`
  状态为前置并原子递增版本；抽查判违规同样受版本保护。上下架 / 改价不参与版本，改为定列更新，避免 409 噪音。
- **前端**：商家端用打开编辑时的详情快照做比对与版本来源（保存时不再二次拉取），409 提示关闭重开；
  审核台审核带版本，409 时刷新列表重新核对。
- **验证**：商品单测 **150/0**（A-07 新增 9 条）、r93 冒烟 **43/0**、权限矩阵 **116/0**、代运营 **128/0**、
  UI e2e 36/0 · 11/0 · 71/0 · 22/0；回库断言版本递增、冲突零落库。
- **兼容性**：编辑 / 审核接口为破坏性变更（缺 `version` → 400），仓库内前端与脚本已全部适配。

**2026-10-24（七十六）· 库存预占补上订单级台账（B-03）：冻结/释放/扣减必须带 orderId**

- **资金风险修复**：商品域原先只按全局 `frozen_stock` 条件更新，释放 A 单可能动到 B 单的冻结量；
  交易侧 `ProductGateway` 还把 orderId 丢成了 null。现在新增 `t_sku_stock_reservation`
  （主键 `(order_id, sku_id)`，FROZEN/RELEASED/DEDUCTED），freeze/release/deduct 一律校验订单归属与数量。
- **语义**：释放无预占 → 幂等成功（+0）；扣减无预占 → `RESERVATION_NOT_FOUND`（资金方向不许静默）；
  重复冻结/错量/状态冲突分别返回 `RESERVATION_STATE_CONFLICT` / `RESERVATION_QTY_MISMATCH`；整批失败即回滚。
- **部署**：`incremental/47` 建表 + `repair/47` 回填；顺序为「建表 → 回填（有 FROZEN 才需要）→ 交易 → 商品」。
- **验证**：新探针 `stock-order-reservation-smoke.mjs` **23/0**、对账 `check-stock-freeze-recon.mjs` **8/8**、
  `order-smoke` **79/0**、商品单测 **141/0**、交易单测 **119/0**；`payment-smoke` 72/0、`pay-channel-smoke` 62/0、
  `split-order-idem-smoke` 18/18、r58 10/10。顺手修了支付状态闸顺序（历史断言不改）与 r93 冒烟启动自清理。

**2026-10-24（七十五）· 商品管理重构 P0：审核台看全内容 / SKU 改价改库存 / 分级重审**

- **审核台**：详情图集、商品介绍、富文本详情（HTML 渲染）、规格参数、包装规格全部可见。
- **自动审核**：联系方式/站外链接规则纳入富文本与自定义参数，富文本写微信号不再绕过转人工。
- **改价改库存**：新增 `PUT /api/seller/products/{spuId}/skus/price-stock`，不触发内容重审，
  同步 `minPrice/maxPrice`；商家端编辑弹窗新增「价格与库存」区。
- **验证**：冒烟 34/0、商家端 e2e 22/0、审核台 e2e 11/0、单测 124/0、既有审核 e2e 36/0。
- 重构方案与两周待办：工作区《商品管理重构方案-2026-10-24.md》《商品管理重构-两周待办-2026-10-24.md》。

**2026-10-24（七十四）· 商品管理四项改造：审核防绕过 / 富文本与自定义参数 / 主图优先 / 评价与经营分析**

- **审核防绕过**：新增 `rejected_at` / `reject_count` 持久化「曾被驳回」，人工通过时清空；修掉
  「驳回后编辑两次即自动通过」与「已通过仍留旧驳回原因」；编辑不再把卖家主动下架的商品悄悄上架。
- **编辑能力**：商品介绍 `intro`、富文本详情 `detail_html`（自研轻量编辑器 + 服务端 jsoup 白名单消毒）、
  规格参数 `spec_params`、包装规格 `packaging`（KV 结构，≤20 条）；商家编辑与发布两处均可用。
- **图片顺序**：详情图集恒为「主图 + 副图…」，Web 与小程序一致。
- **评价与分析**：商家端新增「商品分析」页——逐商品销量/成交额/退款/评分/好评率 + KPI；新增
  `GET /api/seller/product-stats`；商品列表回带评分。销量取订单明细，不读无写入链路的 `sales_count`。
- 验证：冒烟 25/0、UI e2e 19/0、单测 120/0、既有审核 e2e 36/0。

**2026-10-24（七十三）· 消息交互终版：点击式录音 / 单向删除 / 时间分隔 / 引用跳转（im 迭代收官）**

- **录音点击式**：点麦克风开始 → 暂停 / 继续 / 取消 / 结束并发送；web 只累计「正在录」片段时长，
  小程序 RecorderManager 对齐；「按住说话」全链路下线。
- **单向删除**：仅本人不可见、对方仍可见（与撤回双向语义严格分列）；客服 + 私信双通道，按查看方
  分列 hidden 时间戳（`incremental/45`），读取与定位窗口统一过滤；支持多选 / 全选批量删除，幂等。
- **消息操作**：移动端长按 / web 悬停「···」同款菜单（引用 / 删除 / 撤回 / 多选）；撤回文案整行居中；
  web 确认弹窗统一 useConfirm（项目规范：一律不用浏览器原生弹窗）。
- **时间分隔**：10 分钟内连续消息不重复显示时间；超阈值中央插分隔条（当天 / 昨天·前天 / M月D日 HH:mm）。
- **引用跳转**：点引用卡滚回原消息并高亮；原消息不在窗口时走 **aroundId 定位窗口**
  （服务端返回「目标往前 half+1 + 往后 half」一页）。
- 验证：新增 `scripts/message-self-delete-around-smoke.mjs` **30/0**；回归 9 套全绿（25/17/15/18/17/41/32/24/22）；
  web 双端 tsc 通过、容器 healthy。方案见 [`docs/im-realtime-iteration-plan.md`](./docs/im-realtime-iteration-plan.md)。

**2026-10-24（七十二）· 消息撤回 / 引用 / 语音（im 迭代第二批）**

- **撤回**：仅发送者本人 + 120 秒窗口 + 幂等；软删留痕（`recalled_at` 落库、content 覆写
  「[消息已撤回]」、媒体字段置空——数据库层不可读，不靠前端隐藏）；WS 新增 `RECALL` 信号，
  对端实时感知；会话摘要同步改撤回文案。客服 / 私信双通道 + 商家侧接口齐备。
- **引用**：长按 / 悬停气泡「引用」→ 输入框上摘要条；`reply_to_id` 必须同会话（跨会话 400）；
  摘要在**读取路径服务端拼装**（文本前 60 字、媒体用 `[图片]/[视频]/[表情]/[语音]/[卡片]` 占位、
  已撤回显示「原消息已撤回」），端侧摘要不可注入、不递归。
- **语音**：新 `VOICE` 类型（`durationMs` 1–60000ms 服务端校验）；上传白名单扩 aac/mp3 + 音频 MIME
  （音频豁免体积上限）；web MediaRecorder 按住说话（<600ms 丢弃、60s 自动停），小程序
  RecorderManager(aac) + InnerAudioContext 播放；气泡波形条 + 时长。
- SQL：`docs/sql/incremental/44-message-recall-quote-voice.sql`（客服/私信两表加 `recalled_at`、`reply_to_id`）。
- 验证：新增 `scripts/chat-recall-quote-voice-smoke.mjs` **25/0**（撤回留痕 / RECALL 信号 / 越权 /
  幂等 / 引用摘要 / 跨会话拒绝 / 语音边界）；回归 session-ui 39/0、ux 53/0、seller 40/0、notice 50/0、
  read 24/0、lifecycle 32/0、realtime 17/0；web 双端重建部署。

**2026-10-23（七十一）· 聊天实时化：WebSocket 推送 / 已读回执 / 正在输入（500+ 并发）**

- 客服 + 私信聊天从轮询升级为**原生 WebSocket 实时推送**：`duoduo-social/.../realtime/`
  （一次性 60s 票据鉴权、网关升级前 Redis 预检防假成功、纯信号推送——WS 只传信号，
  前端收信号后走既有 HTTP 拉取，单渲染路径；Redis Pub/Sub 支持多实例扇出）。
- 双向「正在输入」（服务端 700ms 节流 + 前端 3s 消失）、双向已读回执（`maxReadId` 游标）、
  新消息零感知推送；web 三页 + 小程序两页全部接入（引用计数共享连接、指数退避重连）。
- 迭代总方案（实时 + 语音 + 撤回 + 引用）见 [`docs/im-realtime-iteration-plan.md`](./docs/im-realtime-iteration-plan.md)。
- 验证：新增 `scripts/realtime-smoke.mjs` **17/0**（含 520 并发连接 2.3s 建齐、推送 32ms、
  越权不转发、无效票据网关拒绝）；回归 session-ui 39/0、ux 53/0、seller 40/0、
  read 24/0、notice 50/0。

**2026-10-23（七十）· 修复：非文字消息发送后不恢复「结束咨询」**

- 根因：`setEndedSessionId(null)` 只写在文字发送分支；图片 / 视频 / 表情包 / 商品卡三条路径各调各的
  `supportApi.send`，漏了状态重置。
- 修法：四条路径收敛到唯一成功出口 `deliver(payload)`（发送成功 → 撤下结束提示 → 刷新），
  新增消息类型无法再漏。
- 验证：新增 8 条断言（贴纸 / 图片各走「结束→发送→提示消失/按钮恢复/服务端 OPEN」）；
  反向验证临时去掉重置会变红，恢复后 **39/0**。

**2026-10-23（六十九）· 客服二期跟进：发送后定位 / 视频控制条悬浮磨砂 / 结束咨询交互**

- **发送后立即定位**：分页窗口固定 30 条，长会话里再发一条**数组长度不变**（挤掉最老一条），
  旧 effect 依赖 `items.length` 因此不触发。改为依赖**最后一条消息 id**（5 处：商城客服/私信/
  商家工作台/小程序两页），发送后立刻滚到新消息。
- **视频控制条**：由「占位的底栏」改为**绝对定位浮层**（底部渐变 + `backdrop-filter: blur(10px)`），
  视频铺满整个容器；5 秒无操作渐隐（`opacity/pointer-events` 过渡），移动鼠标、触摸或点击视频重新出现。
- **结束咨询**：点击后按钮**立即消失**且不可重复点击；会话流内插入居中小字「当前会话已结束」
  （不再用会消失的 toast）；用户再次发送消息后按钮恢复、提示撤下。小程序同口径。
- **验证**：`support-session-ui-check` **31/0**、`support-ux-ui-check` **53/0**；
  回归 support-read 24/0、seller 40/0、notice-chat-follow 50/0。

**2026-10-23（六十八）· 客服会话二期：视频封面 / 定位未读 / 四档筛选 / 关闭规则 / 30 天清理**

- **视频封面**：`uploadChatMedia` 端侧抓帧（seek 0.1s 避开黑场 → canvas ≤520 宽 → JPEG）随消息落 `thumb_url`；
  历史消息用 `<video src="{url}#t=0.1">` 画首帧兜底；小程序用 `chooseMedia` 的 `thumbTempFilePath`。
- **定位最新未读**：`.sup-body` 是内部滚动容器，改为「第一条未读 `scrollIntoView` + 无未读到底」，
  并修正「`loading` 期间容器未挂载导致 effect 空跑」的问题（依赖补 `loading`）。
- **商家四档筛选**：全部 / 未读 / **已处理（没有待回，含已关闭）** / **已关闭**。
- **关闭规则**：新增 `closed_at`；只有买家能关（客服接口恒 403）；关闭后客服禁发；
  买家侧 `status` 由服务端抹成 `null`（全程无感）；再发自动重开并清空关闭时间；24h 静默自动结束。
- **30 天清理**：关闭满 30 天且无往来 → 删除会话与消息，并调履约域删 COS 对象；
  **对象删干净才删库**，失败留待下轮；静态贴纸等非 `/uploads/` 资源不会被误删。
- **小程序对齐**：去掉关闭文案与禁用、加「结束咨询」、`scrollToLatest`、视频 `poster`、
  未读琥珀色、新增表情/贴纸面板（56+12，与 Web 同一清单）。
- **后续 IM 设计**：`docs/support-im-followup-design.md`（语音 / 引用 / 5 分钟撤回，本期不实现）。
- **验证**：`support-lifecycle-smoke.py` **32/0**、`e2e/support-session-ui-check.mjs` **25/0**；
  回归 support-ux-ui 47/0、support-read 24/0、support-split 22/0、notice-chat-follow 50/0、seller 40/0。

**2026-10-23（六十七）· 客服二次修复：精确会话 / 店铺维度归属 / 媒体体验**

- **Q1 精确定位会话**：`sellerId=0` 之前被前端当「不是店铺」丢弃，自营入口只能落到列表页；
  现在 `0` 也带参，进页后用服务端归一化的真实 `sellerId` 回填高亮、补拉会话列表并滚动到可见。
- **Q3 订单卡**：卡片上的订单号移除，店铺名前置为链接 + `›`；订单号只在订单详情页出现。
- **Q4 店铺维度归属**：商家端接待身份从「登录账号」改为「登录账号 → 店铺主体 `ownerUserId`」
  （用户域 `/internal/merchant-membership` 回源），店主与在职店员共用同一份会话；运营/普通用户仍 403。
- **Q5 未读样式**：去掉 `tag` 容器，改时间行内纯文字「 · 未读」，琥珀色，与「已读」同一位置（右下角）。
- **Q6 会话区与表情面板**：会话区 `52vh → clamp(320px,60vh,620px)`；表情面板 `fixed` 锚点定位、
  限高可滚动、`Esc`/点空白可关闭；表情 16→56、贴纸 6→12。
- **Q10 图片/视频**：灯箱与播放器去掉白色容器，改轻量半透明蒙层；图片支持 `Ctrl+滚轮` 0.3x~6x 缩放、
  拖动平移、双击复位；视频进度条可点击/按住拖动（window 级指针监听）、全屏铺满整屏、全屏下关闭按钮可点。
- **未选会话禁发**：没选会话时不再渲染输入区（正文与底栏都给出提示），四个发送入口全部判空；
  根因是服务端把 `sellerId=null` 兜底成「官方客服」，此前会把消息投进「多多客服」会话。
- **私信排查**：无同类问题（路由必须带 `:peerId`、页面判空、服务端拒绝不存在的收件人），已加断言锁定。
- **验证**：新增 `scripts/e2e/support-ux-ui-check.mjs` **47/0**；回归 support-read 20/0、support-split 22/0、
  notice-chat-follow 50/0、seller 40/0、product-detail 19/0、store-page 17/0、cart-batch 通过、
  web10-batch-check 9/0；三端 typecheck/build 通过，web 与 merchant 容器重建 healthy。

**2026-10-22（六十四）· 十问题批次：咨询/店铺/客服/未读体验改造（Q1~Q10）**

- **Q1 自营订单咨询报「店铺不存在」**：自营订单 `seller_id=0` 被当店铺 id 查询。修：social 侧
  `sellerId==null||0` 统一落官方客服（ORDER 卡分支同兜底）；前端 sellerId 为 0/空时**不带**参数。
- **Q2 店铺页排序/搜索**：`storeProducts` 增 `keyword`（本店范围 like，不进 ES）；`Store.tsx`
  增综合/销量/价格/最新排序胶囊 + 店内搜索框，空态区分「无商品/无匹配」。
- **Q3 订单卡片店铺名**：trade 经 Feign `tradeStoreNameClient` 批量取店名 → `OrderView.storeName`，
  自营固定「多多自营」；user 域只回 `APPROVED` 店铺（防夹具死链）；卡片店名可点跳 `/store/{sellerId}`。
- **Q4 会话名改店铺名**：social `sellerDisplayOf` 店铺名优先、自营客服保持昵称；订单卡直达对应店铺会话。
- **Q5/Q6/Q7 聊天体验**：逐条「未读」琥珀标记（`m.mine && !m.read`）；加号菜单二级化
  （图片/视频/表情/商品四入口），移除外置🛍；发商品「当前浏览」→「正在看」并回源真实商品卡。
- **Q8 商家端导航角标**：四接口聚合 + 30s 轮询；角标 `aria-hidden` 防 `getByRole exact` 假红。
- **Q9 通知分类未读数**：`GET /api/socials/notices/unread-by-category` 五类分桶（与徽标同口径）；
  通知中心分类 chip 角标；三端未读方案见 `docs/notification-unread-badge-design.md`。
- **Q10 图片灯箱 + 视频弹窗**：图片点击放大（缩放/下载）；视频自绘播放弹窗（进度/音量/全屏/保存）；
  视频文件核实已在 COS，无需迁移。
- **验证**：`scripts/web10-batch-check.py` **9/0**；e2e store-page 17/0、notice-chat-follow 50/0、
  seller 40/0、cart-batch 31/0、product-detail 19/0；support-read 20/0、support-split 22/0；
  trade 单测 119/0、social 16/0；六容器重建 healthy。

**2026-10-22（六十三）· 支付成功却一直「待支付」：多卖家拆单撞幂等键，已修复**

- **现象**：支付宝沙箱真实付款成功（两笔 `ALIPAY_PC` 均 SUCCESS），订单仍停在 `PENDING_PAYMENT`；
  兜底调度每分钟重试并报「扣减库存失败：SKU 3010/3020 库存不足」，而库里 `stock=479/260`、
  `frozen_stock=0` —— **库存根本不缺，缺的是冻结量**。所以问题不在回调，在下单那一刻。
- **根因链**：多卖家购物车按卖家拆成多张子单，而每张子单都写同一个客户端 `idempotent_key`，
  唯一索引却是 `(user_id, idempotent_key)` ⇒ **第二张子单必然撞键**（撞的是同一批拆单的自己人）。
  撞键分支又在**事务内**「补偿释放库存 → `return replay`」⇒ 第一张子单连同行与台账一起提交，
  但它的冻结量已被释放 ⇒ 商品域 `frozen_stock=0`、交易域台账 `FROZEN`，支付时
  `deductStock` 要求 `frozen_stock >= qty`，永远扣不动。
- **修复**：① 唯一索引加 `seller_id`（`docs/sql/incremental/42-order-idem-per-seller.sql`，可重复执行）；
  ② 撞键改为 `setRollbackOnly()` + 抛内部信号，由**事务外**入口重查并回放整批（要么整批成立、要么整批不成立）；
  ③ 幂等查询 `selectOne` → `selectList`（多行会抛 `TooManyResultsException`）；
  ④ 顺手堵死**同一订单反复开收款入口** —— 开新单前查该订单是否已有 SUCCESS 支付单、同渠道未过期
  `PENDING` 直接复用；回调/查询兜底遇到「订单已由另一张支付单入账」置 `CLOSED` 并留痕，不再无休止重试。
- **数据修复**：`docs/sql/repair/2026-10-22-frozen-mismatch.sql` 只把两个来源摆回自洽，**不手工改订单状态**，
  让兜底调度走完整入账链路（托管/券核销/状态日志都真实产生）。终态：订单 `PAID`、台账 `DEDUCTED`、
  `stock 477/259`、`frozen 0`、托管单 `CUSTODY 14880`；被重复收取的 148.80 元经
  `alipay.trade.refund` 原路退回（`fund_change=Y`）。
- **验证**：新增 `scripts/split-order-idem-smoke.py` **18/18**（两家卖家拆 2 单 → 同键重放回整批 →
  不重复冻结 → 台账与商品域一致 → 两张都能支付到 PAID）；新增
  `scripts/check-stock-freeze-recon.mjs` **6/6**（修复前 **4/6**，恰好红在两条事故症状上；
  反向验证：人为制造冻结量偏差只红 1 条）；`direct-buy-smoke` **14/0**（同时修掉脚本自身两处假绿）；
  `mvn -pl duoduo-trade test` **119/0**。

**2026-10-19（四十）· 秒杀添加商品功能修复（搜索污染根因 ＋ 移除手输 SKU ID ＋ 表单布局）**

- **根因：搜索条件被「钻取态」污染**。选商品面板原来用「钻取数组空不空」判断该显示搜索结果还是某个商品的
  规格列表，而搜索只更新搜索结果、**不清钻取态** —— 一次「看规格」之后，再搜任何关键词都仍渲染上一个
  商品的规格列表。用户看到的就是「选了商品后再搜，只剩已选那个；换关键词也不变」。影响范围仅
  「新建秒杀活动」弹窗的选商品助手。修复＝显式两态 `pickStage`（`search`/`sku`）＋ 搜索前清钻取 ＋
  开弹窗/换行/重开整体复位（`resetPicker`），不再靠「某个数组空不空」推断当前视图。
- **手输 SKU ID 的能力已移除**：19 位 ID 不该由人手输入（背不出来、抄错要到保存才报错）。唯一入口改为
  「选商品」：搜索 → 看规格 → 用这个；选中后在行内回显商品名、规格、可售库存、现价与 SKU。
  校验与提示同步调整：报错点名商品名而不是 19 位 ID；空列表提示「请先通过『选商品』添加至少一个秒杀商品」。
- **表单布局对齐**：秒杀价/原价/配额/限购与两个时间字段统一走 `.sk-row` 网格
  （`repeat(auto-fit, minmax(150px, 1fr))`）—— 同一容器宽度共用列轨道，标签与输入框跨行对齐，
  不写死断点也自适应窄屏；新建弹窗加宽（860px）；**校验错误改渲染在弹窗内**（原来在页面顶部、
  被模态遮罩挡死，运营点「创建」只以为按钮坏了）。

**验证**：`seckill-ui-check` 新增「选商品助手」段 **21 条 → 97/0**（无手输框、首次搜索 ≥2、选后重搜同关键词
仍 ≥2 且停在搜索态、换「猫」返回猫相关商品、网格对齐与等宽、错误在弹窗内、重开弹窗状态重置）；
`seckill-price-ui-check` 适配新选商品流程 18/0；`pagination-search-ui-check` 51/0；静态守卫 3/3；
宽/窄视口（1440/1180/900）截图确认网格 4 列、弹窗无溢出。

**2026-10-19（三十九）· 秒杀页布局与交互修复（用户截图反馈四项）**

- **「+ 新建活动」不再被挤到页面顶部**：按钮从标题行移到**筛选行右侧**，正对着表格；空态文案同步改为「点右侧」。
- **阶段筛选变成真按钮**：那五个数字原来是纯展示的 `<span>`（点了没反应 —— 「看起来能点却不能点」比没有更糟）。
  现在是 `<button>`，带 `aria-pressed` 选中态，点击**真的过滤列表**（与关键字搜索叠加）。
  注意 `已下架` 与三个 phase **不是同一维度**（phase 由时间推出、enabled 是运营开关），所以是五个并列口径；
  计数一律按未筛选的全量算，否则点「已结束」后数字自己缩水，看着像数据错了。
- **时间格同色**：前后两段统一颜色，不再前半黑后半灰；`nowrap` + `title` 显示完整起止时间。
- **阶段/开关不再堆叠**：两个标签改 `flex + nowrap`，窄列下不再换行成上下两行。
- **文字超出 → 省略号 + 悬停看全文**：活动标题、每条秒杀商品都是「一行放完 + `text-overflow:ellipsis`」，
  元素上带 `title`。商品行不再折成三四行把行高撑爆。
- **内容超出 → 横向滚动**：`.table-wrap` 的 `overflow-x:auto` 一直在，但全局 `min-width:720px` 把 6 列全压扁、
  根本滚不起来。改为页面级 `min-width:1240px`。★ 选择器必须比全局规则更具体（单类会被
  `.table-wrap > .table` 静默压掉，页面不报错、只是列又被压扁）——是 e2e 的
  `scrollWidth > clientWidth` 断言揪出来的。

**验证**：`seckill-ui-check` 新增布局与交互段 **12 条 → 76/0**（含按钮位置关系、筛选点击后列表真的变、
时间两段 `getComputedStyle` 同色、两个标签 boundingBox 不重叠、省略号样式契约、横向滚动判据）；
`pagination-search-ui-check` 51/0；回归 7 套全绿；三端 `tsc` + `vite build` 全绿；静态守卫 3/3。

**2026-10-19（三十八）· 秒杀活动页三端重构 ＋ 全站分页/搜索统一**

**① 秒杀活动页（用户端 / 商家端 / 平台后台）**
- 布局拆成三段：**标题行**（右侧按钮）／**统计条**（全部 / 进行中 / 未开始 / 已结束 / 已下架）／
  **工具栏**（左搜索、右口径说明）。此前三样挤在同一行，窄屏直接重叠。
- **隐藏秒杀活动 ID**：列表列、编辑弹窗、删除确认弹窗里的 19 位数字全部移除 —— 运营看它没有任何决策价值，只会挤爆单元格。
- **时间去掉 `T`**：服务端仍下发 ISO（契约不动），展示层统一 `utcToBj(iso).replace('T',' ')`；
  页面口径明确写「时间均为北京时间」，提交时换算成带 `+08:00` 的 ISO 串。
- **「余量」定义唯一**：本场活动自身剩余库存 `sum(availableStock)/sum(totalStock)`，**不是商品 SKU 库存**。
  表格列头写「活动余量」，单元格用数字 + 进度条（`SeckillRemainStat`），`title` 写明「不是商品 SKU 库存」；
  C 端进度条文案同步收敛为「剩 N 件」。

**② 分页统一（三端 27 处）**：`@duoduo/shared` 的 `Pagination` 重写为
**上一页 ＋ 数字页码 ＋ 下一页 ＋ 共 N 条 · 第 x/y 页**。页码用 `pageWindow()` 窗口法
（恒含首页与末页、当前页 ±2、空隙显示 `…`），既满足「直接跳页」，又不会把几百页平铺撑爆工具栏。
替换掉 4 处手写分页，并把渲染条件统一成「有数据就渲染」（原先 `total > pageSize` 判据会让「共 0 条」时连总数都看不到）。

**③ 搜索统一（用户端 3 处 / 后台 12 处 / 商家端 4 处）**：新增 `SearchBar` + `useDebounced(350ms)`，
**输入即搜**、Enter 立即生效、右侧 × 清空。**一律服务端筛**（分页下前端过滤只能看到当前页，必然漏），
后端 10 个接口新增 `keyword`（保留无关键字重载，旧调用不破），全部走参数化 `LIKE {0}`，不拼串；
搜索词进 URL 时用 `replace`，避免每敲一个字 push 一条历史。

**④ 同轮修掉的两个真缺陷**
- **`selectBatchIds(空集合)` → `WHERE id IN ( )` → 接口 500**：后台成员接口按关键字筛出 0 条时触发
  （MyBatis-Plus 的 JSqlParser 解析空 IN 直接抛异常）。已加空集合守卫，并复查全仓其余调用。
- **e2e 清理脚本库名写错**：`t_after_sale` / `t_logistics_track` 在 `duoduo_fulfilment` 而非 `duoduo_trade`，
  写错的后果是**清理段中途抛错 → 剩余表与账号全不删 → 夹具整批留在库里**。已改按实物库名并逐条容错。

**验证**：新增 `scripts/e2e/pagination-search-ui-check.mjs` **49/49 全绿**（接口 + 真实 Chromium 双轨：
11 笔订单凑 2 页 → 数字页码跳转 / 上一页回退 / 搜索收敛并回第 1 页 / 空态文案；三端有搜索框与分页条；
秒杀页无 19 位 ID、无 `T`、有活动余量列；跑完逐表清理 + 自证 0 残留 + SKU 冻结库存回补）。
回归 `seckill-ui-check`、`seckill-price-ui-check` 18/0、`aftersale-ui-check` 44/0、`my-listings-ui-check` 52/0、
`seller-ui-check` 40/0、`merchant-staff-ui-check`、`audit-sla-ui-check` 56/0、`share-report-ui-check` 32/0。
三端 `tsc --noEmit` + `vite build` 全绿，四镜像重建后容器 healthy。

> 跑 e2e 时注意：`run-ui-checks.ps1` 要带 `-UiBase http://localhost:8088`，否则套件默认打 `localhost:5173`（Vite dev），
> 未启动 dev server 时会以「navigating to ... 5173」整体失败 —— **这是基址没传，不是功能回归**。

**E8（2026-10-08，P1 最后一步：入口边界加固）** — **登录令牌加入口声明，网关据此收窄跨端专属路径。P1「三平台独立部署」阶段收口。**

**解决什么问题（以及能解决到什么程度）**：三个站点各有登录页，但账号中心是同一个 —— 商城登录签发的令牌在令牌层面对三端一视同仁。一旦 Cookie 隔离哪天失效、或令牌以 Bearer 形态被带出浏览器，它就能直接去调后台/商家的接口。E8 把**入口**（mall/admin/merchant）写进令牌的 `ent` 声明，网关据此收窄两组**跨端专属路径族**。

**诚实的边界（必须先说清，否则会被误当成强隔离）**：
- 入口标识由各站点 nginx 注入（覆盖客户端同名头），但网关**无法分辨**「nginx 注入」还是「直连网关自塞」—— **能自己登录的人可以声明自己是任意入口**。它防的是**令牌被跨入口误用**与**泄露后的可用范围**，防不了恶意方。
- 真正的防线仍是角色与归属校验（服务端 OperatorGuard）。入口边界只是在它前面多收窄一层。要做成强边界需要 nginx 侧 HMAC 签名或网络拓扑信任 —— 成本与当前威胁模型不匹配，**刻意不做**，理由留档在 `duoduo-common` 的 `ClientEntry` 类注释里。

**实现要点**：
- **refresh 令牌同样写 ent**，刷新时入口**从刷新令牌继承** —— 刷新一次就能把入口洗掉的话，这个字段形同虚设。
- **向后兼容**：老令牌没有 `ent` 声明、小程序直连网关签发的令牌、命令行夹具的令牌 → `entry=null` → **不受入口限制**。「加一个字段」不能等于上线当天把在线用户与小程序全部踢下线。
- **网关只收窄两组专属族**（`/api/admin/**`→admin、`/api/seller/**`→merchant），其余默认放行 —— 给每个入口穷举白名单，漏一条的后果是那个功能直接 403；而"专属族"的归属是稳定事实。配置开关 `entry-scope-enabled` 可整体退回。
- **403/401 语义分清**：入口不匹配 → 403（身份清楚，只是这一端不该调它），前端**不跳登录**；无效令牌 → 401（前端走刷新）。把入口拒绝写成 401，前端会陷入「刷新 → 仍 401 → 再刷新」的死循环。
- **入驻成功重签的令牌固定为商家入口**：买家→卖家的身份转换完成后，用户接下来的动作必然在商家站点；沿用请求头入口会出现「入驻成功了，商家端全线 403」且报错指不到原因。
- 三站点 nginx 与三个 vite dev proxy 注入 `X-Duoduo-Entry`（开发态与正式版口径一致）；CORS 白名单补三端 dev 端口（5180/5190），并注明生产是同源部署、白名单只服务开发态。

**验收（全部实测）**：
- 单测 +9：网关过滤器 **17/17**（跨端 403 主断言、无 ent 兼容、伪造入口头剥离、开关退回、刷新继承）；
- 新增冒烟 `scripts/e8-entry-scope-smoke.mjs` **17/17**：三端正向放行、**验收主断言（商城令牌调 `/api/admin/**` → 403）**、刷新后入口未被洗掉、直连网关兼容、401/403 分界；
- **全量 e2e 17/17 绿** —— 入口边界没有误伤任何正常功能；
- `e8-entry-scope-smoke` 纳入 `run-all-smoke.ps1` 冒烟清单；E7 的两个产物校验工具纳入 `check-all.ps1` 门禁。

**至此 P1「三平台独立部署」6/6 全部完成**。下一步进入 **P2 入驻与租户**。

**E7（2026-10-08，用户要求：抽公共包 + 三工程）** — **前端改造成 monorepo：共用层抽成 `packages/*`，三个应用各自独立成 `apps/*`（源码 / 构建 / 产物三者都分开）**。

**为什么分两步做**：重排目录、依赖与构建一次做完，中途一旦停下就会留下"构建变红"的半成品 —— 那种状态比不开始更糟。所以第一步先把共用层抽干净（三入口暂留 `apps/mall`），第二步再把后台与商家拆成独立应用。

**目录（`frontend/` 即 monorepo 根，npm workspaces = `packages/*` + `apps/*`）**：

| 包 | 内容 | 依赖方向 |
|---|---|---|
| `packages/api-sdk` | 类型化 HTTP 客户端 + 端点清单 + 类型定义 + 图片工具 | **零 React 依赖**（将来小程序 / 开放平台可直接复用） |
| `packages/shared` | 认证与应用 store、共用组件库、角色判定、全局样式 | **单向**依赖 `api-sdk` |
| `apps/mall` | 商城：入口 `index.html`（**独立应用，80 模块**） | 依赖上面两个包 |
| `apps/admin` | 平台管理后台：入口 `admin.html`（独立应用，644 模块） | 同上 |
| `apps/merchant` | 第三方商家平台：入口 `merchant.html`（独立应用，642 模块；含 `pages/merchant` 工作台外壳 + `pages/seller` 各业务页） | 同上 |

**做法**：
- 共用包**不预构建**，直接吃 TS 源码：Vite `resolve.alias` + tsconfig `paths` 指向各包 `src` —— 省掉"先 build 包再 build 应用"这一步，也就少一类"改了 packages 忘了重编"的假通过。
- **300 处 import 机械改写**（`api/*` → `@duoduo/api-sdk/*`、`components|store|lib|styles.css` → `@duoduo/shared/*`），随后**用脚本把 398 条 import 说明符逐个解析、验证目标真实存在**。这一步先抓出 **11 处同目录形式的引用**（`./components/ui`、`./store/auth` —— 不匹配「`../` 开头」的改写规则，会静默留在原处，而目标文件已搬走）；E7-2 拆应用时又抓出 **3 处跨应用引用**。这类错误只在构建时才炸、提示还是"找不到模块"，极易被误判成"包没装"；**手工改必漏**。
- **E7-2 拆分暴露的 3 处隐藏耦合（已修）**：① 商城 `Me.tsx` 绕道 `pages/seller/shared` 只为取一个中立判定 → 直引 `@duoduo/shared/lib/roles`；② `afterSaleShared` 被**三端共用**（mall 3 页 + admin 1 页 + merchant 1 页）→ 提升为 `packages/shared/components/afterSale`；③ admin/merchant 拆出后**丢了 public 目录**（favicon 请求 404）→ 各自补回。
- **依赖分化**：`apps/mall` 移除 `echarts` —— 实测只有后台与商家用图表，商城产物里不再出现这个 559 KB 的库。
- 路径映射（查旧文档时对照）：`frontend/src/api/*` → `packages/api-sdk/src/*`；`frontend/src/{store,components,lib,styles.css}` → `packages/shared/src/*`。其中 `components/Layout.tsx` 是**商城专属**路由外壳（只被 `App.tsx` 引用），留在 `apps/mall/src/components/`。
- 应用根变了：Vite 只从**应用根**读 `.env`，故 `.env.example` 随迁到 `apps/mall/`。
- 三镜像共用一份 Dockerfile，用两个构建参数区分：`APP`（`mall|admin|merchant`，决定装哪份产物）× `NGINX_CONF`。

**两个新增的可复用校验工具（`scripts/lib/`，可进 CI）**：
1. `check-frontend-imports.mjs` —— 逐条解析全部 import 说明符（相对路径 + `@duoduo/*` 别名），验证目标真实存在；
2. `check-bundle-isolation.mjs` —— **从 entry HTML 出发收集引用闭包**（跟随 `<script>`、`modulepreload`、chunk 间静态与动态 import），断言 ① **dist 内没有闭包之外的 `.js`**（有则是"摘不干净"的残留或误打包）② 闭包内**不含其他端的特征串**。特征串一律取**字符串字面量**而非标识符名 —— 生产构建会 minify，标识符会改名而字符串会保留。

**验收（全部实测）**：三应用 `tsc --noEmit` + `vite build` 全绿（mall **80** 模块 / admin 644 / merchant 642）；**398 条 import 说明符全部可解析**；**产物隔离校验通过**（商城产物中 `/api/admin/`、`/api/seller/`、后台与商家文案、`echarts` 命中数均为 **0**）；三站点镜像重建后三入口 HTML 与宿主构建产物**逐字节一致**；**全量 e2e 17 套全绿**。

**两个踩坑记录（都值得下次绕开）**：
1. `vite.config.ts` 注释里写了 `packages/*/src`，其中 `*/` **提前闭合了块注释** → esbuild 直接语法报错（`Expected ";" but found "@"`）。注释里不要出现裸的 `*/`。
2. 本机 `npm install` 会卡在 esbuild 的 postinstall —— 即本仓已知的 `spawnSync ... EBUSY errno -4082`（同步派生态在受限环境下不可用）。改用 `npm install --ignore-scripts` 即可，`@esbuild/win32-x64` 平台包会正常安装，构建不受影响。

**下一步**：**E8 入口边界加固**（登录令牌加"入口标识" `aud` + 网关按入口裁剪允许路径 + CORS 按站点分列）—— 做完即 **P1「三平台独立部署」阶段收口**，进入 P2 入驻审核与租户化。

**E6 / E6b（2026-10-08，用户要求：三平台**完全独立部署**）** — **商城 / 平台管理后台 / 第三方商家平台各自独立站点、独立端口、独立登录，互不共享会话；顺带修掉门禁「假通过」缺陷**。

**① 三站点落地（E5 后台 + E6 商家）**。单应用**多入口**构建：Vite `rollupOptions.input = { main: index.html, admin: admin.html, merchant: merchant.html }`，同一份源码产出三个 HTML 入口，各挂各自的 Root（`AdminRoot` / `MerchantRoot`）。compose 新增 `duoduo-admin-web-app`（**8090**）与 `duoduo-merchant-web-app`（**8091**）；商城站点删掉 9 个后台页面 + 6 个商家页面的导入与路由。
> **E7-2 起已不再是"多入口"**：后台与商家各自迁成独立应用（`apps/admin`、`apps/merchant`），**各自构建、各自产物**；上面这段描述的是 E5/E6 当时的过渡实现，保留作沿革记录。
- **隔离必须换主机名，不能只换端口**：Cookie 是**主机级**作用域（不带 `Domain` 时 host-only）且**不区分端口** —— `localhost:8088` 与 `localhost:8090` 是同一个 Cookie 域，端口隔离等于没隔离。故后台走 `admin.localhost:8090`、商家走 `merchant.localhost:8091`（浏览器按 RFC 6761 把 `*.localhost` 解析到本机）。
- **后台 / 商家各有自己的登录页**：此前未登录时提示「去商城登录」，那是**平台依赖商城**的耦合点；现在入口、页面、运行站点都在各自那一侧，商城下线不影响另两端。后端账号体系仍是同一套（一个账号中心），但**入口与会话分开**。
- **角色判定抽到中立模块** `frontend/packages/shared/src/lib/roles.ts`（`isOperator` / `isAdminRole` / `canAccessSellerConsole`）：原先商城只要引用一个判定函数，就会把整个后台 / 商家 UI 打进商城产物 —— 抽出来后才真正「摘干净」，并用**产物闭包扫描**（跟随 entry HTML 的静态导入闭包逐 chunk 搜特征串）验证。**E7-1 起该文件已随共用层迁到 `packages/shared/src/lib/`**（旧路径 `frontend/src/lib/roles.ts`）。

**② E6b：五套 e2e 改指向独立站点**。后台与商家从商城摘除后，仍通过**商城入口**驱动后台 / 商家页面的套件会红 —— 那是**预期变化**，不是回归。逐套改为各站点的**独立地址 + 独立登录页**（引入 `ADMIN_UI_BASE` / `MERCHANT_UI_BASE`）：
- `seckill`（65 项）、`share-report`（32 项）、`admin-charts`（32 项）→ 后台站点 `admin.localhost:8090`；`aftersale`（44 项，卖家页 + 运营页**分别**指向两个站点）、`logistics`（44 项）→ 商家站点 `merchant.localhost:8091`。
- **Cookie 注入的 domain 也必须一起改**：注入到 `localhost` 后浏览器在 `admin.localhost` 上根本不会带上它（表现是「会话丢了、停在登录页」），很容易被误读成页面坏了。
- **顺手修掉两处测试脆弱性**：`share-report` 原先依赖**硬编码的历史运营账号** `13688774031`，而该账号已被 T2b 清库删除 → 改为**随机手机号 + 库提权**自建夹具（符合本仓「每个用例自建前置」的纪律）；`seckill` / `share-report` / `admin-charts` 的运营夹具**收尾降权回 USER** —— 此前每跑一次就在库里留一个**永久 OPERATOR**，而 OPERATOR 正是能登录平台后台的角色，等于每个测试账号都是一个多余的后台入口。
- `web-up.sh` 从「起 1 个站点」改为「**起 3 个站点并逐个自检**」（一条命令 = 三端就绪）。

**③ 修掉门禁「假通过」缺陷**。`scripts/e2e/run-ui-checks.ps1` 此前**没有退出码**（恒以 0 结束），而上层 `check-all.ps1` 只看子进程退出码 → **套件已经红了、门禁却报通过**。这种假通过比红灯更危险：红灯会被修，假通过会被信。已补 `exit 1` 并用**反向验证**确认（停用新逻辑应恰好红掉对应几条）。

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
- **★ 网页商城同口径对齐（2026-10-12 补）**：`Web` 端此前只做到「打开头像菜单时现取一次未读数」，且是**自己把通知数与私信数相加**（等于把服务端的合并口径又抄了一份）。现改为读同一个 `GET /api/socials/notices/unread-summary` 的 `total`，并把角标挂到**顶栏头像**上（**任意页面可见**，不只在通知页）。刷新点三处：打开头像菜单 / `visibilitychange` 回前台 / **60 秒兜底轮询**；请求单飞、**失败不清零**、未登录清角标 —— 与小程序逐条一致。实现落在 `shared/src/store/app.tsx`（状态与实现）与 `apps/mall/src/components/Layout.tsx`（按登录态启停），自检见 `docs/SELFTEST.md` §4.10c。

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
① **tabBar 重排**：用户要求「分类」移出底部导航（首页已有分类入口），空位换成「通知」并置于**居中第 3 位** —— 首页/分享圈/**通知**/购物车/我的；分类降为普通页（`back` 返回键，首页「全部分类」入口仍在），全局排查 `switchTab` 引用与三处自维护 tab 清单（index/notification/profile）同步。② **通知中心**（启用 R47 已建齐的 `t_social_notice` + `t_social_notice_read`）：后端 `duoduo-social` 新增 `NoticeController` 4 端点（列表 / 未读数 / 单条已读 / 全部已读），**定向通知与全站公告统一为一个视图**；已读统一记在集合表（`(notice_id, user_id)` 复合主键，撞键即幂等）—— 表上行本就没有已读列，**零 DDL 变更**；他人通知标已读返 **404**（不区分「不存在/无权限」，防枚举）。小程序新增 `pages/notification` 4 件套（未读蓝点 / 点击标已读并跳落地页 / 全部已读 / tabBar 角标 `setTabBarBadge` / 私信未读合计入口），种子 3 条公告（增量 SQL `17-notification-center.sql` + `06` 种子同步）。③ **通知入口全页面显示**：`nav-bar` 组件内置右上角图标（由 R48 的 📩 改回**短信样式 💬**），并新增 `back` 返回键属性 —— 全部 22 个页面改用自绘导航栏（`navigationStyle: custom`），聊天页/AI 页整页高度按导航栏高度扣减（`calc(100vh - navTotal)`）。④ **★ 根治 record 组件计数器类型缺陷（R47 遗留）**：`LongToStringJacksonModule` 的字段名白名单只对**普通 POJO** 生效，Java `record` 组件的属性由 `POJOPropertiesCollector` 的 record 分支直接构造、不流经 `BeanPropertyWriter` 替换 —— 于是 `UnreadCount.count` 下发字符串 `"3"` 而同一接口族 `PageResult.total` 是数字 `3`，**同语义两种类型**（R47 的 `FollowCounts.followers/following`、R49 的 `ConversationView.unread` 同病）。修法：模块新增公开的 `NumericLongSerializer`，record 里的计数/金额字段显式标注 `@JsonSerialize(using = …)`，本域 **9 处**已标注。★ **2026-10-11 更正**：本段对**机制**的归因（「白名单只对普通 POJO 生效、record 不流经那条路径」）经实验推翻 —— 见下文「**金额 / 计数字段的类型口径**」；当时观察到的现象是真的，但"record 必须逐个标注"这个结论不成立（白名单对 POJO 与 record 一视同仁）。
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
**三端各自独立**（E6）：平台管理后台 **http://admin.localhost:8090/console**、第三方商家平台 **http://merchant.localhost:8091/workspace** ——
三个站点**会话互不共享**（Cookie 是主机级作用域且不区分端口，所以隔离靠的是**主机名**而不是端口）。
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
| `duoduo-app-admin-web` | duoduo/duoduo-web:1.0.0-admin | 8090 | **平台管理后台（E5）**：**独立应用 `apps/admin` 的产物** + `nginx-admin.conf`，独立主机名 `admin.localhost`（短地址 `/console`）；入口 `admin.html`，**自有登录页**，会话与商城**完全隔离** |
| `duoduo-app-merchant-web` | duoduo/duoduo-web:1.0.0-merchant | 8091 | **第三方商家平台（E6）**：**独立应用 `apps/merchant` 的产物** + `nginx-merchant.conf`，独立主机名 `merchant.localhost`（短地址 `/workspace`）；入口 `merchant.html`，**自有登录页**，会话与商城、后台**互不共享** |
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

### 端到端冒烟（32 套接口冒烟 = 31 套 PowerShell + 1 套 Node；22 套真实浏览器 UI 断言；小程序无浏览器 72 项）

> **套件数量口径**：`scripts/*smoke.ps1` 共 **32 个**（实算：`ls *smoke.ps1 | grep -v '^run-all-smoke.ps1$' | wc -l`），
> 由 `run-all-smoke.ps1` **按文件名通配自发现**（它会排除自身）；
> 另有 1 个 Node 冒烟 `scripts/e8-entry-scope-smoke.mjs` 显式列出。
> 其中 `search-smoke.ps1` 需要 ES，未启动时**显式跳过并在汇总里说出来** ——
> 此时汇总打印「共 32 套，通过 32 套，失败 0 套 / 已跳过 1 套」（静默跳过会让「全部通过」变成一句谎话）。
> 浏览器 e2e 由 `scripts/e2e/*-ui-check.mjs` **自发现**（22 套）。
> `scripts/check-all.ps1` 里的套件数**从目录实算**，不再写死 —— 写死的数字在加套件之后会变成假话，而没人会记得改它。
> CI 的「冒烟子集」是**显式清单**（`SMOKE_SCRIPTS`），新套件不会自动进 CI：要按"它的失效方向是不是静默"决定加不加。

> **✅ 本地库数据漂移已根治（2026-10-09 · E27 完成）**：一键全量冒烟此前在本地库上有 **9 套红**，
> 经逐套归因**全部非代码回归**，现已从根上消掉，不再需要"看到红先判断是不是漂移"：
> ① **4 套夹具过期**（`seller` / `aftersale` / `logistics` / `credit`）——它们曾调用 **E10 已删除**的
> `POST /api/users/me/merchant`；② **5 套依赖固定种子数据**（`smoke` / `trade` / `order` / `ai` / `review`）——
> 本地库清理后种子商品由 8+ 件降到 2 件、`t_listing` 为 0 条。
>
> **做法（三层，全部落地）**：
> 1. **夹具化**：`scripts/lib/{seller,catalog,listing}-fixture.ps1` 现场建前置、跑完自毁，
>    与种子数据彻底解耦；夹具建不起来会**显式报红**（否则后面几条会因"库内本来就是 0 条"静默通过）；
> 2. **断言数据驱动**：计数类断言改成「先查库内实际值再断言」，不再写死"首页 6 条"这类数字 ——
>    写死条数钉的是"当前有几条数据"，环境一变就红，而它想守的其实是「只返回已过审的那批」；
> 3. **全员进 CI**：CI 子集 5 → 11 套，并新增 **`smoke-full` 作业**（push 到 main 跑全量）——
>    子集只解决"反馈快"，解决不了"没人跑"，这是 E10→E27 之间那批套件静默变红的直接原因。
>
> **★ 本轮最重要的产出是夹具反过来挖出的真实缺陷**：**无品牌商品会让商品列表 / 商品详情 /
> AI 候选召回全部 500**（`CatalogLookup` 空结果返回不可变 `Map.of()`，而 `Map.get(null)` 直接抛 NPE；
> 只要同页有一条带品牌就被掩盖，种子数据全带品牌 → 单测与冒烟双双假绿）。修复与验证见下方变更记录。
> 判断"是否本轮引入"的方法仍然适用：看失败项是否落在本轮改动文件上（`git status` 逐条比对），
> 以及失败是否可由「库内数据不存在」直接解释（例如 `SELECT COUNT(*) FROM t_spu`）。

> **完整自测流程（环境/数据准备 → 功能点清单 → 逐项步骤与预期 → 排障表）见 [`docs/SELFTEST.md`](docs/SELFTEST.md)。**
> 改了 Java 代码请按 §「交付物核对」比对容器内 jar md5，别只看 `package` 的退出码。

> 一键全量回归：`powershell -NoProfile -ExecutionPolicy Bypass -File scripts\run-all-smoke.ps1`（逐套落盘 `logs/smoke/*.log`）。
> 其中 `search-smoke` **需要 ES**；ES 未运行时脚本会**显式跳过并在汇总里声明**，不会静默略过（见 `docs/agent/08` T-53）。
> CI 子集（本地 / GitHub Actions 同一入口，**17 套**）：`scripts/ci-run.ps1 -Scripts smoke.ps1,trade-smoke.ps1,order-smoke.ps1,address-smoke.ps1,id-precision-smoke.ps1,seller-smoke.ps1,aftersale-smoke.ps1,logistics-smoke.ps1,credit-smoke.ps1,ai-smoke.ps1,review-smoke.ps1,audit-sla-smoke.ps1,perm-version-smoke.ps1,audit-log-smoke.ps1,privilege-matrix-smoke.ps1,tenant-isolation-smoke.ps1,admin-2fa-smoke.ps1`。

### 数据库备份（S4，2026-10-12 落地；**2026-10-13 起含上传文件卷**）

> 全项目**唯一**有「不可逆数据损失」风险的一项：此前所有验证与修复（含 E26 全量删号）
> 都建立在"库坏了能从备份恢复"之上，而那个前提**只靠人工临时 dump 维持**。

```powershell
# 跑一次：六库 mysqldump + 两个上传文件卷 → 产物自证 → 从产物切表恢复进临时库做三方比对 → 滚动保留 → 失败告警
#   -BackupRoot 必须给一个真实存在的目录（脚本顶部 $DefaultBackupRoot 只是占位默认值，不是已定方案）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\db-backup.ps1 -BackupRoot 'E:\duoduo-backup'
#   → runs\<yyyy-MM-dd_HHmmss>\：6 个 .sql + uploads\<卷名>\… + manifest.json + backup.log；退出码 0 = 全绿
#   → 退出码 1 = 有失败项，落 _ALERT.txt；配 -AlertWebhook 可推飞书/钉钉
# 注册成每天 03:00 的计划任务（排在 03:30 对账选主之前；注册后自动试跑一次并回读结果）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\db-backup-register.ps1 -BackupRoot 'E:\duoduo-backup'
```

> **抽验怎么做的（这是它区别于"导完就报成功"的地方）**：从**产物**里切出该表的整段
> （含 dump 的 preamble，否则最后一张表会带上 `SET TIME_ZONE=@OLD_TIME_ZONE` 而恢复报 `ERROR 1231`）→
> 恢复进临时库 → 与源表比对**行数 + `CHECKSUM TABLE`**。抽样 = 随机 K 张 **+ 每个库的最后一张表**
> （切片边界必须占位，不能靠运气）；校验和做**三方比对**（产物 vs dump 前 vs 恢复时），
> 因为 `t_sched_leader` 这类心跳表**实测约每 15 秒就自改一次** —— 只跟"当下源库"比会导致备份正确却天天报红。
> ★ 光有"三方比对"还不够：`t_sched_leader` 的改写间隔比备份窗口短 ⇒ 三方**注定**互不相同。
> 所以维护一份**持续改写表**名单（`$ChurningTableNames`），名单内的表改判 **行数 + 主键集合**并于日志打
> `[PASS~]` 点名（名单解析出 0 张即判红，防名单静默失效）；append-only 表在窗口内被插入时，
> 行数只按「源库↔产物」两来源判并打 `[PASS*]`。**原则：宁假红勿假绿。**
> **文件卷**：`fulfilment-uploads`（商品/评价图片）+ `merchant-materials`（E12 资质材料，丢了不可补）
> 一并备份，判据同形 —— **容器内 sha256 vs 宿主机 sha256 逐文件一致 + 清单指纹**；
> 卷名带 compose 项目前缀 ⇒ **按后缀反查**而不是拼字符串；恢复演练只把字节灌回容器内**临时目录**，绝不写回原卷。
> 实测（2026-10-13）：六库 **58 张表 / 6 份 dump + 2 个卷 129 个文件 / 1 分 53 秒**，抽验 9 张失败 0。
> **PR 上跑子集（反馈快），push 到 main 跑全量**（`ci.yml` 的 `smoke-full` 作业直接调 `run-all-smoke.ps1`）——
> 两者缺一不可：只有子集会漏（E10→E27 的教训），只有全量会慢到没人愿意等。
> **API 契约**：`scripts/export-openapi.ps1` 导出各服务 OpenAPI 至 `docs/openapi/`（含 `endpoints.md` 摘要，**252 个端点**与 `docs/agent/04` 逐条核对）；
> `docs/agent/04-接口契约全集.md` 的端点表由 `scripts/extract-api-contract.py` **从源码机械生成**，
> **端点总数由脚本写在文档末尾**（`合计 N 个端点`）—— 这里刻意**不再抄一份**：
> 抄来的数字必然落后，而「这里写 389、脚本算 336」这种不一致**没有任何检查能发现**
> （契约文档自身有 `--check` 门禁，但门禁只覆盖它管的那一份文档）。
> 加 `--check` 即变门禁（文档落后于源码时退出码 1）—— 端点清单不再靠手工维护。
> GitHub Actions 见 `.github/workflows/ci.yml`（编译 + 冒烟子集）。

### 运维与回滚（E25，2026-10-12 落地）

> 完整手册见 **`docs/OPS.md`**（三站点部署 / 入口开关 / 备份恢复演练 / 数据清理与回滚）。
> 这里只列**入口**。四个脚本都用**退出码**表达结果（`0` = 通过）。

```powershell
# 一屏看全栈：容器 / 网关→后端 / 三站点 8088·8090·8091 / MySQL 六库 / Redis / 开关四层 / 备份根
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ops-status.ps1
#   → 实测输出「OK 26 ／ 需留意 1 ／ 失败 0」，退出码 0。站点探针**只看状态码且经网关**，不直连服务端口。

# 看有哪些运行期开关、现在是什么值；-Check 只做静态校验（可进 CI，不依赖 Docker）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ops-switch.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ops-switch.ps1 -Check
# 改一个开关：写 .env 并打印需重建的容器（改环境变量只需重建容器，不必重新打镜像）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ops-switch.ps1 -Set DUODUO_API_METRICS_ENABLED=false
docker compose up -d --force-recreate --no-deps duoduo-gateway-app

# 恢复演练：把一份备份**真的恢复一次**，验完自动删临时库
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ops-restore.ps1
#   → 实测：产物哈希 6/6 一致 + 六库 58 张表 / 19,242 行 / 差异 0，退出码 0

# 交付前全部门禁（含 docs/OPS.md 一致性守卫）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\check-all.ps1
```

> ★ **为什么给"开关"单独写脚本，而不写一篇开关大全**：实测到过 **4 处「写了但不生效」**
> —— 代码里有字段、yml 里有 `${...}`，但 compose 没透传；于是 `.env` 里改了、重启了、行为没变、
> **不报错也不告警**（`entryScopeEnabled` 与 `adminIpWhitelistEnabled` 甚至**连 yml 占位符都没有**，
> `DUODUO_SEARCH_ENABLED` 在 compose 里是硬编码 `'true'`，`.env` 覆盖不了）。
> 所以判据做成**四层逐层核到实物**：清单登记 → compose 真透传 → 服务 yml 真读 → 容器里真生效；
> `ops-switch.ps1 -Check` 对前三层逐项断言并做**反向**校验（compose 标了 `# OPS-SWITCH` 的必须登记；
> `.env` 里每项必须真被 compose 引用过），且**自证解析器有效**（标记数为 0 ⇒ 报红，而不是"没有开关"）。
>
> **★ 第 ④ 层踩过的坑（2026-10-12 修）**：它原先靠**猜容器名**——`"duoduo-app-" + (service 去掉
> `^duoduo-` 与 `-app$`)。这个公式**只对 `duoduo-xxx-app` 成立**：`duoduo-es`、`rocketmq-broker`
> 全猜错。猜错**不报错**，只静默退回「`.env`（容器未读到，可能未重建）」——若值又恰好等于默认值，
> 人眼分不出「没读到」与「真的没覆盖」。已改为**读 compose 声明的真实 `container_name`**（17/17
> 全部解析成功），并加两条自证断言：解析到 0 个 ⇒ 直接判失败；清单涉及的服务缺谁点名谁。
> 顺带实测出一条反直觉结论：**`docker exec` 的退出码不可信** —— 容器不存在时它会打 stderr，
> 但 `$LASTEXITCODE` **仍是 0**，所以判据必须看输出内容而非退出码。

### 双端功能对照与依赖安全（S6 / S13，2026-10-12 落地）

> 这两项的共同点：**缺陷没有任何编译期征兆** —— "小程序少做一个页面"、"某个依赖版本有 CVE"
> 都不会让构建失败，只能靠文档 + 静态断言守。两个守卫都是**纯离线**的，已接进
> `check-all.ps1` 的 `[0/5]` 段与 CI。

```powershell
# S6 · 双端功能对照表：小程序 30 页 / 网页商城 31 组件 / 34 条路由逐项对齐
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\lib\check-dual-client.ps1
#   → 文档见 docs/S6-DUAL-CLIENT.md，退出码 0

# S13 · 第三方依赖安全扫描：漏洞版本底线 + 版本锁定 + 供应链卫生
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\lib\check-dependency-security.ps1
#   → 退出码 0
```

> ★ **S6 回答的问题**：同一个功能，小程序和网页各自做到哪一步。差异分四类 ——
> **A 平台能力**（微信登录 / `wx.requestPayment` / 调起系统实名 只有小程序）、
> **B 页面形态**（小程序 `pay` 是独立收银台页，网页在 `/checkout` 内联完成）、
> **C 入口位置**（「分类」小程序是独立页，网页是首页区块 + `/search?categoryId=`）、
> **D 真实缺口**（4 条：D1 网页端无个人主页 = C2C 断链；D2 小程序无宠物档案页（**有意不做**）；
> D3 小程序无独立搜索结果页；D4 秒杀记录网页端缺）。守卫守三件事：文档引用的页面必须真实存在、
> **tabBar 与 `app.json` 双向一致**、缺口清单**自证仍成立**（缺口被补齐时会主动报红提醒改文档）。

> ★ **S13 为什么不用 OWASP dependency-check / `npm audit`**：两者都**必须联网**
> （前者首次要下数 GB 的 NVD 数据库，后者要打 registry 的 advisory 接口），
> 而 CI 与本地验收环境都不保证外网可达 → 挂进流水线只会得到一个"平时绿、需要时连不上"的摆设。
> **`FAILED`（没跑成）是一等状态** —— 跑不起来就跳过的扫描等于没扫描。改为三层确定性判据：
> ① 本地漏洞版本底线表（**10 条**，每条注明 CVE 与官方修至版本）；② 版本锁定（禁 `*`/`latest`，必须留 lock 文件）；
> ③ 供应链卫生（非白名单 registry / `postinstall` 钩子 / `http://` 明文仓库）。
> 两个守卫都对每条断言做过**反向验证**（故意改坏必报红、还原必回绿），各 **8/8 全绿**。

> ★ **恢复演练的判据**（刻意**不比整表 `CHECKSUM TABLE`**）：① 产物文件 **SHA256** 与 manifest 一致；
> ② **逐表行数**与 manifest 的 `rowCounts` 快照一致；③ `manifest.volumes` 的每个上传卷**逐文件 sha256 + 清单指纹**一致。
> 跨时刻比校验和不成立 —— 拿"恢复库 vs 产物"比是**恒等式**；
> 拿"恢复库 vs dump 前源表"比时心跳表 `t_sched_leader`（**实测约每 15 秒自改**）**必然不等**，会让备份完全正确却每次演练都假红
> （实测踩中 2 处）。"行数相同但内容被改"由 `db-backup.ps1` 的抽验负责（那里有独立时间点，做三方比对）。
> 演练还会**拒绝出结论**而不是降级报成功：manifest 缺 `rowCounts` / 缺 `volumes` / `status != OK` /
> **比对了 0 张表** / **一个上传卷都没验成** ⇒ 判失败。

### e2e 截图输出契约（S8，2026-10-12 落地）

> 22 套 e2e 里有 **20 套会落盘截图**，过去全部写到**受版本控制**的 `frontend/docs/screenshots/`
> （其中 9 套还写死 `D:/智能体项目/duoduo-pet-mall-java/…` 绝对路径）。后果不是"多几个文件"，
> 而是**每跑一次回归，工作区就多出上百个 modified / untracked 的 PNG**（内容随真实数据与时间戳变化）——
> `git status` 里再也分不清「我改了代码」和「我刚跑过回归」。
> 现在默认落点收口到 `scripts/lib/e2e-shots.mjs` 的 `shotsDir()`（默认 `scripts/e2e/.shots/`，已 gitignore）；
> 真要更新文档配图时显式 `SHOTS=frontend/docs/screenshots`。

```powershell
node scripts\lib\check-e2e-shots.mjs
#   → 退出码 0；输出「被检查的 e2e 套件数：22（其中会落盘截图的：20）」+ 五项判据
#   启动前先扫到 0 份套件 ⇒ 直接判违规（「什么都没扫到」≠「扫了、结果干净」）
```

> ★ **这条守卫的关键在判据 ⑤**：只改脚本、不改 `.gitignore`，等于**把污染换个地方** ——
> 新增一套 e2e 的人照抄 `const SHOTS = shotsDir();`，是**抄不出** `.gitignore` 那一行的。
> 所以守卫必须**同时**断言「脚本侧都走收口」与「`.gitignore` 真的忽略了那个目录」。
> 另有判据 ② 的**空转自证**：一旦所有 `screenshot(` 调用被重构掉，判据 ② 会变成永真 ——
> 这类「检查还在、却不再检查任何东西」的哨兵会自己报出来。
> 六种注入各 exit 1 且精确点名、还原后复跑 exit 0（**9/9**，沙箱现场建/跑完自毁）。

### 云端同步暂存副本与新鲜度守卫（#343，2026-10-13 落地）

> **问题**：上云（腾讯文档 / 飞书 / 资料库）上传的**不是源文件本身**，而是
> `.workbuddy/sync-staging/` 下的一批**副本**（统一命名、避开中文路径问题）。于是存在一条
> **不会报错的失效链**：`源文件改了 → 忘了重新导出副本 → 上传的还是旧副本 → 云端显示「导入成功」`。
> **「导入成功」只说明传输没问题，不说明内容是最新的。** 2026-10-12 复核时实测抓到 **4 份落后**
> （README / PLAN / SELFTEST / 项目简报），其中 README 源 421,472B 而云端副本停在 416,126B。
>
> **做法**：把这条链拆成两半，各给一个能自动判的出口 —— `scripts/lib/sync-staging.ps1`
>
> | action | 做什么 | 判据 |
> |---|---|---|
> | `export` | 从**源文件**重建全部副本（副本从此不是"手工维护的第二份真相"） | 导出后**立刻自验**，不通过即 exit 1 |
> | `verify` | 逐份比 **SHA256**，副本 != 源文件即判失败（**不是 WARN、不降级**） | **自报比对数**；为 0 直接判违规 |
> | `manifest` | 只校验映射表自身没腐烂（条数 > 0、仓库侧源文件都存在、约定的 5 份仓库文档一份不漏） | CI 里跑这个（不需要开发者本机的暂存目录） |
>
> ★ **`N/A` 是第三态，必须报出来**：暂存目录在开发者本机，CI 与干净检出都没有。
> `check-all.ps1` 配了 `DUODUO_SYNC_STAGING` 就真跑；没配就记 **`[N/A]`** 并写明原因，
> **绝不写成 PASS**（把"没跑"写成"跑过且通过"是这个项目里最贵的错误之一）。
>
> ★ **映射表是唯一出处**：新增一份要上云的文档，必须在映射表里加一行，否则 verify 看不见它、
> 云端会一直拿着一份旧副本而没有任何人报错 —— CI 的 `manifest` 那一步就是为了让这件事**有人报错**。


```bash
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke.ps1              # 62 项：用户域 + 商品域（含 E27 的闲置/无品牌商品夹具守卫）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\trade-smoke.ps1        # 87 项：购物车
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\order-smoke.ps1        # 79 项：下单拆单 + 库存冻结（含订单级预占台账断言）
NO_PROXY="*" node scripts/stock-order-reservation-smoke.mjs                         # 23 项：订单级预占（重复冻结/越单释放/错量扣减/释放后扣减）
NO_PROXY="*" node scripts/check-stock-freeze-recon.mjs                              # 8 项：交易台账 ↔ 商品预占 ↔ SKU frozen_stock 三方对账
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\payment-smoke.ps1      # 72 项：支付幂等 + 状态机 + 超时关单
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\settlement-smoke.ps1   # 149 项：担保交易 + 资金托管 + 退款 + E22 佣金/T+7/钱包/账单（双端同源 + 库内复算）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\pay-channel-smoke.ps1  # 62 项：沙箱支付渠道（扫码/验签/回调）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\seller-smoke.ps1       # 101 项：卖家中心（准入/归属隔离/发货/售后/结算）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\admin-smoke.ps1        # 75 项：平台管理后台（权限边界/审核/强关/对账/运营账号，含改角色两条 422 规则的文案区分）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\review-smoke.ps1       # 61 项：履约域评价（内部接口/资格/匿名/追评/聚合/**R73 回写链复原**）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\audit-sla-smoke.ps1    # 62 项：审核 SLA 事后抽查（自动放行留 audit_at / 命中风险转人工 / sweep 幂等 / 判违规真下架 / 判合规不下架 / 复核不可重入 / 指标自洽 / 入驻判违规直接停业 + 联动下架 / 夹具自毁 / **E16 权限点：直连服务端口做双向断言（USER·SELLER→403、OPERATOR→200、未登录→401、商家端门槛）**）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\perm-version-smoke.ps1 # 34 项：权限即时生效（改角色→旧令牌**下一个请求**即 401 / 刷新一次就换到新角色且**无需重新登录** / 收权后 403 与 401 分得清 / **无 pv 的老令牌不被拦下**（对照令牌只差一个 pv）/ 副本自愈 / 幂等不推进版本 / 收尾复原 ADMIN 计数）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\audit-log-smoke.ps1     # 58 项：后台操作审计日志（E18：历史动作标识逐字稳定/落库字段/分页/凭据边界/越权）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\privilege-matrix-smoke.ps1 # 115 项：权限越权回归矩阵（E19：10 节 + §7b / 8 模块，**每条「被拒」必配「有权者能过」正对照**，网关层与服务端层分别断言；**§7b = 钱的口径（S5）**：幂等重放只扣一次 / 并发抢最后 1 件恰成一单且可售不为负 / 券分摊 `pay=total+freight-discount` 且明细合计==商品总额 / 全库不变量 `refund<=pay`、`discount<=total`）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tenant-isolation-smoke.ps1 # 37 项：租户隔离（E20：令牌 tid 与刷新重算 / 网关剥离伪造 X-Tenant-Id / 非法头 400 / **验数据层直连服务端口** / 夹具自建自毁 + GUARD）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\admin-2fa-smoke.ps1   # 54 项：平台后台两步验证（E21：绑定三步 / 登录两步 TOTP_REQUIRED / **防重放** / 恢复码一次性且只存哈希 / 刷新链路 / 管理员重置仅 ADMIN / IP 白名单默认关 / 收尾自证）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\backfill-smoke.ps1     # 88 项：托管单回补（dryRun/幂等/挂账清零/控制组/REFUNDED 盲区）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\aftersale-smoke.ps1    # 101 项：售后仲裁（立案/卖家处理/升级/裁定退款/已放款边界/越权）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\logistics-smoke.ps1    # 79 项：物流轨迹（发货即揽收/乱序正序/幂等/批量全有或全无/越权/敏感字段）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\credit-smoke.ps1       # 82 项：信用分（基础分/权重表/因子和=总分/单调性/售后因子/回写幂等/凭据边界）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\coupon-smoke.ps1       # 145 项：优惠券（领券幂等/并发防超发/锁·解·核销 CAS/折扣落交易域/生命周期闭环/凭据边界/断言总数守卫）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\address-smoke.ps1      # 82 项：收货地址簿（CRUD/默认唯一性与删除顺延/归属统一 404/下单写快照/清理复原）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\id-precision-smoke.ps1 # 56 项：跨域 ID 精度回归（只断言 JSON 原始文本，绕开反序列化的精度掩盖）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\wechat-auth-smoke.ps1  # 26 项：微信登录绑定（未绑 422/未配置 503/幂等/抢绑 409/免登 401）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ai-smoke.ps1           # 39 项：AI 导购（形态自适应/推荐真实性/多轮追问/预算硬过滤与区间/极小预算空结果/预算未解析告知/出口预判/澄清反问/推荐上限/伪造 XFF 不能绕过限流/429；**犬粮前置商品现场建、跑完删**）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\search-smoke.ps1       # 40 项：ES 检索（拼音与同义词容错/对账三空/停 ES 降级/**需 ES 在跑**）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\mq-smoke.ps1           # 47 项：削峰队列（入队 PENDING→PLACED/入队幂等/业务失败 FAILED/**死信可查可重放**/30 并发 429 限流/自管 MQ 起停与模式复原）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\seckill-smoke.ps1      # 51 项：限时秒杀（匿名边界/运营准入/下单幂等 replay/限购/售罄/DB 不负数/取消回补/运营编辑·删除与守卫/套件自我清理）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\upload-smoke.ps1      # 8 项：图片上传（白名单只放 GET/匿名上传 401/上传→回读 roundtrip/扩展名白名单）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\proxy-ops-smoke.ps1     # 121 项：E23 代运营最小版（工单一次性消费 CAS / 自营与第三方分道 / 授权可用性先于吻合性 / 两域各自工单表 / 权限点 tenant:proxy-ops）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\recon-smoke.ps1         # S3 每日对账（13 条判据：钱 8 / 库存 5；**同日重跑=覆盖**；`FAILED`（没跑成）与 `CLEAN` 分得开；选主调度）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tenant-health-smoke.ps1 # 90 项：E24 租户健康度（四维横跨三存储 / 网关逐日租户计数**真实链路** / 售后跨域内部接口 / 判级造异常真变红再复原 / 比例分母 0 给 null / 排序·截断 / 权限三层）
python scripts/r58-restock-idempotent-smoke.py     # 10 项：退货入库幂等（同 refNo 三次只加一次 / 换 refNo 正常加 / 缺 refNo 400）
python scripts/r58-compensate-smoke.py             # 22 项：平台垫付与卖家追偿（闭环 / 重复 409 / 未放款拒 / 垫付**不回补库存** / 收回核销二次 409 / 非运营 401）
python scripts/r58-listing-edit-delete-smoke.py    # 33 项：闲置编辑与删除（编辑回待审 / 驳回件恢复在售 / 软删三处不可见 / 非本人 403 / **匿名 401（对照组证明白名单确实放行两段）** / 已售不可删）
python scripts/coupon-window-smoke.py               # 6 项：券领取窗口不变式（**列表可见 ⇒ 当下必可领**；未到开抢时间的券不得提前出现）

# ================= 前端真实浏览器 UI 自测（22 套） =================
# 一键跑：起 dev server → 跑 22 套 → 建/拆券夹具 → 收尾关进程，一条命令即可
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\e2e\run-ui-checks.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\e2e\run-ui-checks.ps1 -Only home   # 只跑自包含那套
# 打**容器入口**（真应用，而不是开发态）：给了 -UiBase 就不再启 dev server
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\e2e\run-ui-checks.ps1 -UiBase http://localhost:8088
# Playwright + Chromium 已装进 scripts/e2e/node_modules（仓库自包含，不依赖用户级 workspace）
# ★ 截图默认落到 scripts/e2e/.shots/（已 gitignore）——跑回归**不再弄脏工作区**。
#   真要更新 frontend/docs/screenshots/ 里的文档配图时才显式指定：
#     $env:SHOTS='frontend/docs/screenshots'   （然后重跑对应套件）
#   落点由 scripts/lib/e2e-shots.mjs 收口，契约由 scripts/lib/check-e2e-shots.mjs 守（见上）
# 三端分离后，涉及后台 / 商家页面的套件通过站点常量定位，可单独覆盖：
#   ADMIN_UI_BASE=http://admin.localhost:8090   MERCHANT_UI_BASE=http://merchant.localhost:8091

# 也可手工跑（默认 UI_BASE=http://localhost:5173）：
cd frontend && npm run dev -- --port 5173 --strictPort --host localhost   # 另开终端（E7 monorepo：转发到 apps/mall）
# 注意必须走 localhost，不能是 127.0.0.1（不在 CORS 白名单）
cd .. && node scripts/e2e/home-ui-check.mjs           # 首页 7 项：非白屏 / 可点击 / 真点进详情 / 无非预期接口错误
node scripts/e2e/seller-ui-check.mjs                 # 商家端 29 项
node scripts/e2e/review-ui-check.mjs                 # 评价与晒单 24 项（含夹具后把商品评分**回写复原**，否则会给下一套留下「N 条评价」脏数据）
node scripts/e2e/aftersale-ui-check.mjs              # 售后三端 44 项（买家[商城]→卖家[商家站点]→运营[后台站点]整条链，**三端各自登录**）
node scripts/e2e/logistics-ui-check.mjs              # 物流轨迹 44 项（买家/卖家同轨 + 空态 + 越权 + 终态仍在；卖家页走**商家独立站点**）
node scripts/e2e/address-ui-check.mjs                # 收货地址簿 64 项（含结算页两种模式与「改地址簿不改历史订单」）
node scripts/e2e/share-report-ui-check.mjs           # 举报处理台 32 项（后台独立站点 / 摘要可见 / 默认不下架 / 驳回不误删 / **非运营在后台登录页被拒 + 接口 403 纵深** / 夹具自建与收尾降权）
node scripts/e2e/notice-chat-follow-ui-check.mjs       # 通知 / 私信 / 关注 50 项（分类 chip **逐个真筛**、私信发送与非互关限一条、关注/粉丝名单、互关后恢复可发、**顶栏全站未读角标**；自清理）
node scripts/e2e/seckill-ui-check.mjs                # 秒杀 97 项（后台独立站点；新建活动 / **选商品助手：搜索污染回归（首次搜索/选后重搜/换关键词）＋ 无手输 SKU ID ＋ 表单网格对齐** / **下架后仍列得出来并能重新上架** / 前台不可见 / 抢购→下单→库存递减 / 限购拦截 / 幂等重放 / 取消回补 / **后台编辑落库** / **有未关闭订单的活动删除被拒且行仍在** / **无订单活动可删** / **订单全关闭后可删且不留残留** / 夹具收尾降权）
node scripts/e2e/admin-entry-ui-check.mjs            # 后台入口 + 账号管理 74 项（未登录/普通用户看不到入口 / 运营两条入口都能进运营概览 / **刷新·新开标签页·只带 dd_rt 重开浏览器后入口仍在** / next 回跳不被破坏 / **S5 改角色仅 ADMIN：UI 正反向 + 接口层 + 审计回表 + 并发不变式**）
node scripts/e2e/header-layout-ui-check.mjs          # 顶栏布局 88 项（4 个角色 × 6 档宽度：**导航一个标签都不许被截断** + 昵称不许折行 + 个人事务入口收进下拉但仍可达 + **下拉可用 Esc/点外部关闭**）
node scripts/e2e/seller-dashboard-ui-check.mjs        # 商家端概览 31 项（**五张真图表：canvas 非零 / 摘要与接口一致 / 悬停 500ms 内出数据 / 不许再用原生 title** / 权限边界）
node scripts/e2e/admin-charts-ui-check.mjs            # 平台后台图表 36 项（后台独立站点；**折线/饼/柱状/分布四类图都在** + 悬停即时 + 数据与接口一致 + **dark 主题像素指纹与 light 不同** + 普通用户 403 + 夹具收尾降权）
node scripts/e2e/share-circle-ui-check.mjs            # 分享圈 21 项（**发布→信息流→详情评论→点赞 toggle→我的分享编辑/删除** + 话题聚合路由 + 未登录入口可发现性 + 无报错）
node scripts/e2e/my-listings-ui-check.mjs             # 我的闲置 47 项（发布→审核中→过审→在售→下架→重新上架→编辑回审核中→软删；未实名 403；公开页匿名可用）
node scripts/e2e/product-detail-ui-check.mjs          # 商品详情页 20 项（主图可放大切图 / 包装规格 / 同类推荐 / 两端标签一致 / 无非预期接口错误）
node scripts/e2e/audit-sla-ui-check.mjs               # 审核事后抽查 56 项（后台站点；**直插 PENDING 任务保证确定性** / 六项指标卡都出数 / **未填依据时"判定违规"按钮禁用（双向断言）** / 判合规后商品仍在售 / 域切换后文案变"停业"口径 / 手动抽查 toast 四要素立标 / **非运营两级都进不来（登录页拒绝 + 页面拒绝）** / 夹具自毁）
node scripts/e2e/merchant-audit-ui-check.mjs          # 入驻审核台 27 项（提交→驳回→重提→通过→开店全链路；材料大图与 PDF 链接；**运营用主运营账号，不新建运营夹具**）
node scripts/e2e/merchant-staff-ui-check.mjs          # 商家子账号 24 项（邀请→店员干活→结算 403→停用即时失效→平台跨租户处置→恢复；收尾删成员行）
node scripts/e2e/product-audit-ui-check.mjs           # 商品上架审核 36 项（真实发布弹窗→前台不可见→**索引有文档但检索不命中**→上架门禁 422→后台通过→上架后可见→改内容回待审→驳回→重提→再通过；**先 reindex 让断言真正测到 ES 过滤**；收尾删夹具商品）
node scripts/e2e/merchant-license-ui-check.mjs         # 资质有效期与提醒 71 项（长期有效基线 → T30 横幅与提醒 → **同档不重复发** → 升档再发 → 过期停业 + 联动下架 + 审计留痕 + 上架 422 → 后台与商家端双横幅 → 续期回 SUBMITTED 且仍不能上架 → 通过后放行 → 续期拒绝过去日期**且事务回滚** → UI 卫生；收尾清 8 张表夹具）
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

二十套脚本的共同约定（踩过坑才有的）：
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
├── scripts/                    一键启动、冒烟测试（25 套接口断言 + 22 套真实浏览器 UI 断言；run-all-smoke.ps1 一键全量 / ci-run.ps1 子集 / export-openapi.ps1 契约导出 / search-smoke.ps1 需 ES）
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

**第三方商品上架审核（E14）** —— 商品有**两个正交维度**：交易维度 `status`（在售 / 下架，卖家可控）与
审核维度 `auditStatus`（`PENDING`/`APPROVED`/`REJECTED`，平台判定）。对外可见的唯一口径是**两者同时满足**，
由 `ProductService.visibleSpuWrapper()` 单点收口（MySQL 7 条读路径 + ES 索引/查询 + 采购视图），
未过审商品详情一律 **404**（与不存在同码，防状态码探测）。**平台自营免审**：`supplyType=self` 的商品恒 `APPROVED`。

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| GET | `/api/seller/products` | **我的商品**（按**审核维度** `auditStatus` 筛选；店主与店员看到同一批，归属店铺主体） | 需登录（SELLER / 店员 / 运营） |
| POST | `/api/seller/products` | **提交商品**（SPU + SKU 一事务落库；价格按**分**存储、SKU 编码服务端生成）。归属 / 供货类型 / 审核态**全部由服务端判定**——第三方落 `PENDING`，运营落 `APPROVED`；请求体带这三个字段无效 | 需登录 |
| GET | `/api/seller/products/{spuId}` | **商家视角详情**（编辑回填用；公开详情对未过审一律 404，但卖家必须读得回自己被我驳回的商品；非本店 403） | 需登录 |
| PUT | `/api/seller/products/{spuId}` | **编辑商品**（全内容字段；**内容变更即回 `PENDING` 并恢复在售**——审核结论针对「当时那段内容」，放它继续挂着过审等于允许「先过审、再改违规内容」；非本店 403） | 需登录 |
| POST | `/api/seller/products/{spuId}/status` | **上下架**（`ON_SALE`/`OFF_SHELF`）。**上架门禁**：目标态为在售时要求已过审，否则 **422** 并给出下一步提示（是「等审核」还是「去改内容」）；平台自营不受限 | 需登录 |
| POST | `/api/seller/products/{spuId}/resubmit` | **重新提交审核**（仅 `REJECTED` 可调，`PENDING`/`APPROVED` 均 422） | 需登录 |
| POST | `/api/seller/products/proxy-requests` | **E23 · 申请代运营（商品类）**：`{targetType:'PRODUCT', targetRef:spuId, actionType:'OFF_SHELF', reason}`；`tenant_id` **由登录态推导**、不由请求体指定 | 店主 / 店员（**平台账号 403**） |
| GET | `/api/seller/products/proxy-requests` | **E23 · 我的商品类工单**（只列本店；跨店看不到） | 店主 / 店员 |
| POST | `/api/seller/products/proxy-requests/{workOrderNo}/void` | **E23 · 撤销未执行的工单**（已消费/已撤销 400；不是本店的 404） | 店主 / 店员 |
| GET | `/api/admin/products/proxy-work-orders` | **E23 · 平台待办（商品域）**：可按 `tenantId` 或 `targetRef`(spuId) 筛选 | 运营 / 管理员（进门），执行另需权限点 |
| POST | `/api/admin/products/{spuId}/proxy-off-shelf` | **E23 · 代下架**：`{workOrderNo, reason}` 均必填；**持有效工单**才能替第三方店铺下架；自营商品走这里 **422** | `tenant:proxy-ops` |
| GET | `/api/admin/products` | **商品审核台**（只列**第三方**商品：自营免审，混进队列只会干扰待办；待审排最前；可按 `sellerId` 筛选） | 运营 / 管理员 |
| GET | `/api/admin/products/{spuId}` | **审核详情**（带全部背审材料，运营不必点进二级页） | 运营 / 管理员 |
| POST | `/api/admin/products/{spuId}/audit` | **审核结论**（`action=APPROVE`/`REJECT`；**驳回必填原因**，否则 400；**可重入**——允许改判，把改判逼成改库才会留下无法追溯的结论；驳回同时把 `status` 压成 `OFF_SHELF`） | 运营 / 管理员 |

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
| GET | `/api/seller/settlements/wallet` | **E22 · 我的钱包**：待结算/冻结中/可提现(净额)/累计到账/欠款/下次解冻 | 卖家（店员 403） |
| GET | `/api/seller/settlements/bills` | **E22 · 结算账单**（租户 × 周期）；`period` 形如 `2026-10` 或 `2026-10-05`，不传=当前月，非法 400 | 卖家（店员 403） |
| POST | `/api/seller/proxy-requests` | **E23 · 申请代运营（订单类）**：`{targetType:'ORDER', targetRef:orderNo, actionType:'SHIP'\|'AFTERSALE', reason}`；工单号带 `WO-ORD-` 前缀，`tenant_id` 由登录态推导 | 店主 / 店员（**平台账号 403**） |
| GET | `/api/seller/proxy-requests` | **E23 · 我的订单类工单**（含 `openCount`） | 店主 / 店员 |
| POST | `/api/seller/proxy-requests/{workOrderNo}/void` | **E23 · 撤销未执行的工单** | 店主 / 店员 |

**E22 佣金与结算口径（4 条规则，唯一出处都在 `SettlementService`）**：

- **抽佣 6%（600 bp）**，四舍五入到分：`(amount × bps + 5000) / 10000`。
  **自营（`seller_id = 0`）免佣** —— 佣金从左手交到右手，收了只会让指标虚增。
  费率走配置 `duoduo.trade.platform-fee-bps`，配 0 即全平台免佣。
- **T+7 可提现**：`released_at`（记账）与 `release_at`（可提现）分开存，
  合成一个字段表达不了"钱已经是他的、但还不能取"。
- **欠款抵扣**：可提现 = 已解冻 − 未结欠款，且不小于 0；**只算 RELEASED 不算 ADVANCED**
  （垫付的钱卖家没拿到，算进去会出现"越退款越有钱"的错账）。
- **双端同源**：商家端与平台端的钱包/账单调**同一个**服务方法，逐分一致 ——
  两边各算一遍的后果是"平台说能提 500、商家看到 480"，而那种分歧在技术上无解。

**★ 金额 / 计数字段的类型口径（2026-10-11 实测更正）**

`LongToStringJacksonModule` 的规则是**按字段名**判：命中 `NUMERIC_LONG_FIELDS`（金额 / 计数 / epoch 时间戳）→ 保持 JSON 数字，
否则（绝大多数 `Long` 是 id）→ 转成字符串。

这里原先写着「**该白名单只对普通 POJO 生效，record 组件不流经那条路径，所以 record 的金额字段必须逐个标注**」。
**该说法已被实验推翻**：新增的 `LongToStringJacksonModuleTest` 用同一个 ObjectMapper 把「普通类」与「record」各序列化一次，
**4/4 通过、行为完全一致**（命中白名单的 `payAmount` 是数字；未命中的 `someNumber` 与标识 `spuId` 是字符串）。
集成侧旁证：`scripts/id-precision-smoke.ps1`（**56/0**）里那 14 条「金额必须是 JSON 数字」的断言，
被测对象恰恰是 record（`OrderView.payAmount` / `TemplateView.thresholdAmount` …）。

- 因此 **record 上加 `@JsonSerialize(using = NumericLongSerializer.class)` 是可选的保险丝**，不是必需补偿（既有 34 处保留不动）；
- **真正会漏的只有一种**：语义是金额/计数、但**名字没登记进白名单**的新字段（例如新写的 `extraFee`）——
  它会被**静默**字符串化成 `"12900"`，不报任何错，只表现为前端 `+` 变拼接、`===` 恒 false、`.toFixed()` 抛错。
  **新增这类字段请把它加进 `NUMERIC_LONG_FIELDS`**（按名字生效，POJO 与 record 都管用），不要给单个 record 打补丁；
- `sellerId` / `tenantId` 这类**标识**刻意不标（标识不该参与算术）。


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

**E23 代运营口径（平台替第三方店铺做事，唯一的合法入口是一张工单）**

- **默认阻断**：平台账号对第三方店铺的订单 / 商品**没有任何直接权限**
  （订单侧 `seller_id` 归属校验 403；商品侧 `changeStatus` 收窄为「只有自营才能直接改」）。
- **唯一例外 = 店主给出的工单**，六个条件同时成立才算有效授权：
  ① 工单存在 ② 状态 `OPEN` ③ `target_type` 对得上 ④ `action_type` 对得上
  ⑤ `target_ref` 就是本次目标 ⑥ `tenant_id` = 目标的店铺主体 userId。
  判据顺序刻意是「先 400/404（授权能不能用）→ 再 403（对不对得上）」：
  运营看到 400 知道查工单、看到 403 知道查归属，两类事故的处理方式完全不同。
- **白名单三个动作**：代发货 / 代处理售后 / 代下架。执行时**必须**填执行说明，全量写审计。
- **一次性消费（CAS）**：`UPDATE ... WHERE id=? AND status='OPEN'`，影响行数 0 → **409 必须中止**；
  消费与动作在**同一个本地事务**里 —— 这正是工单表在交易库与商品库**各放一张**、
  而不是放共享库的原因（跨服务只能"先消费再调用"，中间失败会烧掉授权却没干活）。
  两表列名逐列相同，**共享的是契约不是代码**。
- **可撤销**：`OPEN → VOID`；已消费/已撤销的工单再用 → 400。
- **平台不能自己造授权**：平台账号调申请入口一律 403 —— 若能自己授权再自己执行，
  这套机制就只是个填写框，审计记录会很完整而它证明的事实并不存在。
- **商品域的"自营"判据是两个信号的与**：`supply_type != 'merchant'` **且** `seller_id = 0`
  （`SellerProductService.isPlatformSelf`，全仓唯一出口）。任一信号指向第三方就按第三方走 ——
  判重的代价是多走一步流程，判轻的代价是越权。**归属校验只用 `seller_id` 是不安全的**：
  它会让"归属列被改错"变成一条免授权通道。
- **审计动作码**：`ORDER_PROXY_SHIP`(CONTENT) / `AFTERSALE_PROXY_PROCESS`(FUNDS) /
  `PRODUCT_PROXY_OFF_SHELF`(CONTENT)，留痕 `detail` 里带工单号；**被拒的尝试也留痕**（`outcome=FAILED`）。
  ★ 新增动作码 = 新增一个"接收方（`duoduo-user`）也要认识"的契约，**只重建发送方不够**。
- **遗留**：`PUT /api/seller/products/{spuId}`（编辑商品）目前仍允许平台运营修改第三方店铺的商品，
  与上下架的收窄口径不一致，已登记为遗留项（影响面与回滚方式不同，单独评估）。
  ★ 校正：**并没有** `DELETE /api/seller/products/{spuId}` 这个端点 —— 商品只能上下架（`POST /{spuId}/status`，
  只接受 `ON_SALE` / `OFF_SHELF`）与编辑，不存在删除路径。此处此前写成「改/删」，是照着当时的
  `删除` 一起写的、并未核对控制器；`SellerProductController` 里没有 `@DeleteMapping`，
  `SellerProductService` 也没有删除方法。**登记遗留项时同样要核到端点上，不能凭印象写。**

**E24 租户健康度口径（一个面板横跨三个存储，四维各自要有独立证据）**

- **四维与来源**：订单量 / 成交额 → 交易库 `t_order`（`created_at` 的 UTC 自然日窗口）；
  售后率 → 履约库 `t_after_sale`（走**服务间内部接口** `/api/aftersales/internal/tenant-stats`，不经网关）；
  结算异常 → 交易库 `t_settlement` / `t_seller_debt` / `t_order` 三类**存量**异常（不带窗口，挂账是存量）；
  接口错误率 → Redis `duoduo:api:stat:{yyyy-MM-dd}`（网关对**带店铺身份的请求**逐日计数）。
- **为什么合成一个请求**：拆成四个接口会出现「四个数字来自四个时刻」，以及
  「其中一格加载失败但页面看起来完好」—— 后者是这一整类面板的通病。
- **★ 三态纪律**：比例字段分母为 0 时给 `null`（前端渲染「—」）而**不是** `0.00%`。
  「这段时间一单没成交」与「成交 100 单、零售后」是两个相反结论，写成同一个 `0.00%` 会把前者伪装成健康。
  同理档位有第四态 `NO_DATA`：窗口内既无成交也无接口调用 —— **没有可判断的数据**，不等于正常。
- **★ 样本门槛（有意的"不给档位"）**：结算异常 > 0，一笔就判 `RISK`（资金事实错位没有"轻微"一说）；
  但售后率要「≥ 20% **且** 成交 ≥ 5 单」、5xx 率要「≥ 1% **且** 调用 ≥ 10 次」才谈得上风险 / 关注。
  4 单里出 4 笔售后（100%）不会被标红 —— 绝对量太小，任何比例都没有统计意义；
  而「1 次失败就变红」的面板会在几天内被运维彻底无视，那比漏报更糟。
  所以面板**同时展示原始计数**，规则与门槛由 `/limits` 下发、页面如实渲染（前端不抄阈值）。
- **4xx 不参与判级**：401/404/400 大多是**预期的**（令牌过期、看别人的单、参数写错），
  5xx 才是无歧义的平台故障。4xx 仍照常展示 —— 它是排查商家端集成问题的线索。
- **降级方向是刻意的**：售后 / Redis 两路取不到 → **硬失败 503**（它们是判据，缺了面板就会说谎）；
  店铺名取不到 → 显式降级（响应 `nameResolved=false`，只影响展示）。**两处都不静默。**
- **★ 跨模块硬约束**：`TenantHealthJudgement.MAX_DAYS(30) ≤ TenantApiMetrics.KEEP_DAYS(40)`。
  否则超期那几天的 Redis 键已过期，`HGETALL` 的空 Map 与「那天没调用」**完全一样**，
  错误率会安静地变小。编译器管不到（两个常量在不同模块），只能靠单测守。
- **网关计数只认「带店铺身份的请求」**：`TenantApiMetricsFilter` 的 order 是 `-90`，
  必须**晚于** `JwtAuthGlobalFilter`（`-100`）—— 晚于它才读得到网关改写后的可信 `tid`，
  早于它读到的是调用方自己编的头（那就是一个新的越权面）。买家 / 运营 / 匿名请求一次都不计。
- **展示排序**：档位（风险优先）→ 下单数降序 → 租户 id 升序。第三键不是装饰：
  没有它，同档位同单数的两家店在两次请求间可能换序，被 `limit` 截断的永远是随机一批。

购物车条目的失效原因码（机器可读，前端只管展示文案）：
`SKU_NOT_FOUND` / `OFF_SHELF` / `OUT_OF_STOCK` / `STOCK_NOT_ENOUGH`。
**失效条目不计入结算金额** —— 否则结算页金额与订单金额会对不上。

### 平台管理后台（`/api/admin/**`，M10）

| 方法 | 路径 | 说明 | 认证 |
|---|---|---|---|
| GET | `/api/admin/reports/overview` | 运营报表：GMV / 总订单 / 已付款 / 退款率 / 转化率（全平台口径，SQL 聚合） | 运营 |
| GET | `/api/admin/settlements/summary` | 资金对账：托管中 / 已放款 / 已退回三组金额与笔数、佣金 | 运营 |
| GET | `/api/admin/settlements/wallets?tenantId=` | **E22 · 指定租户的钱包**（与商家端 `/api/seller/settlements/wallet` 同一段计算，逐分一致） | `finance:settlement`（仅 ADMIN） |
| GET | `/api/admin/settlements/bills?tenantId=&period=` | **E22 · 指定租户的结算账单**（租户 × 周期，与商家端同一段计算） | `finance:settlement`（仅 ADMIN） |
| POST | `/api/admin/settlements/compensate` | **平台垫付退款**（已完成订单退款：金额按买家实付记卖家欠款，托管单 `RELEASED→ADVANCED`，订单进 `REFUNDED`；**不回补库存**；`uk_seller_debt_order` 保证一笔订单只垫付一次，重复调用 409） | 运营 |
| GET | `/api/admin/settlements/debts` | 卖家欠款列表（可按 `status=OUTSTANDING/SETTLED/WRITTEN_OFF` 过滤） | 运营 |
| POST | `/api/admin/settlements/debts/{id}/settle` · `/write-off` | 欠款收回 / 核销（CAS 条件更新，二次调用 409） | 运营 |
| GET | `/api/admin/settlements/abnormal` | 异常挂账：已付款却缺托管单的订单（`LEFT JOIN` 排查） | 运营 |
| GET | `/api/admin/tenant-health?days=&limit=` | **E24 · 租户健康度面板**：一次请求返回**四维**（订单量/成交额、售后率、结算异常、接口错误率）—— 这四路分别来自**交易库、履约库、Redis**三个存储，拆成四个接口会得到「四个数字来自四个时刻」。窗口 `days` 默认 30 / 上限 30，行数 `limit` 默认 50 / 上限 200，非法值 **400**（不静默回落）。比例字段是 `String` 且**分母为 0 时给 `null`**（前端渲染「—」，不写 `0.00%`）；每行带 `level` 四态 `RISK`/`WATCH`/`NO_DATA`/`OK`（`NO_DATA` = 无样本，**必须与 `OK` 分开**）。`nameResolved=false` 表示店铺名这一路显式降级（只影响展示，四维判据仍有效） | 运营 |
| GET | `/api/admin/tenant-health/limits` | **E24 · 运行时口径**：窗口上限 / 默认值 / 行数上限 / 三类结算异常来源 / 判级阈值与**样本门槛** / 档位清单 / 接口计数保留天数。前端**不抄一份阈值**，判级规则从它渲染 —— 抄一份意味着政策调整后页面仍在按旧数解释数据 | 运营 |
| POST | `/api/admin/settlements/backfill?dryRun=` | **历史订单托管单回补**（A1 / I-14）：按订单状态推断资金状态（`PAID`/`SHIPPED`→`CUSTODY`、`COMPLETED`→`RELEASED`、`REFUNDED`→`REFUNDED`），金额取 `pay_amount`、佣金按当前 `PLATFORM_FEE_BPS` 重算并回传口径说明；`?dryRun=true` **只出计划、一行都不写库**；不带参数即执行；幂等靠 `uk_settle_order`（重复执行不产生重复单）；**不触碰已有托管单的订单**，也不给 `PENDING_PAYMENT`/`CLOSED` 补账（钱从未收过） | 运营 |
| GET/POST/PUT/DELETE | `/api/admin/categories`、`/{id}`、`/{id}/status` | 类目增改删 + 启停用；**后台列表含停用项**（可重新启用），公开接口仍只暴露 ACTIVE | 运营 |
| GET/POST/PUT/DELETE | `/api/admin/brands`、`/{id}`、`/{id}/status` | 品牌同上 | 运营 |
| GET | `/api/admin/listings` | 闲置审核工作台列表（含待审 / 已驳回，按 `auditStatus` 过滤） | 运营 |
| POST | `/api/admin/listings/{id}/audit` | 审核：`APPROVE` / `REJECT`（驳回必须填原因）；`audit_reason` / `audit_at` 落库留痕 | 运营 |
| GET | `/api/admin/orders`、`/{orderNo}` | 全平台订单列表（状态 / 订单号前缀过滤）与详情（无归属限制） | 运营 |
| POST | `/api/admin/orders/{orderNo}/force-close` | **强制关单**（仅待付款）：复用状态机合法流转 + 作废未支付支付单 + 释放冻结 + 状态日志记 OPERATOR | 运营 |
| GET | `/api/admin/orders/proxy-work-orders` | **E23 · 平台待办（交易域工单）**：可按 `tenantId` 或 `targetRef`(orderNo) 筛选 | 运营 / 管理员（进门） |
| POST | `/api/admin/orders/{orderNo}/proxy-ship` | **E23 · 代发货**：`{workOrderNo, reason, logisticsCompany, logisticsNo}`；**持本店有效工单**才能替第三方发货；自营订单 **422**、他人订单 **403** | `tenant:proxy-ops` |
| POST | `/api/admin/orders/{orderNo}/proxy-aftersale` | **E23 · 代处理售后**：`{workOrderNo, reason}`；转调 `refundInternal`（与内部凭据入口同一段状态机） | `tenant:proxy-ops` |
| GET | `/api/admin/users?role=&keyword=` | **运营账号列表**（A7）：分页 + 角色过滤 + 关键字（手机号/昵称模糊匹配）；**脱敏视图**——不下发实名姓名 / 身份证 / 完整手机号 | 运营/ADMIN |
| POST | `/api/admin/users/{id}/role` | **变更用户角色**（A7，**仅 ADMIN**）：角色白名单（USER/SELLER/OPERATOR/ADMIN）；**先判权后校验参数**（无权者传非法角色也是 403，不泄露白名单）；自己降权 **422**、最后一个 ADMIN 不可降 **422**（==**并发**下才可能触发，见下）、同角色幂等 200 不留审计；每次变更写 `t_audit_log`；生效语义见 notice —— E17 起为「**下一个请求即生效（无需重新登录）**」，仅 Redis 不可用退回吊销令牌时才说「重新登录后生效」 | ADMIN |
| GET | `/api/admin/audit-sla/samples?status=&page=&pageSize=` | **E28 事后抽查队列**（商品域）：`status` 取 `PENDING`（默认，待复核）/ `PASSED` / `REJECTED`，不传=全部；**待复核按建单时间升序**（先来先做，24h 响应时长的守门就是它），已复核按复核时间倒序；视图冗余带出 `spuTitle` 与 `sellerId`，队列里一眼看出"该看哪件" | 运营 |
| POST | `/api/admin/audit-sla/samples/{id}/review` | **复核一条抽查任务**：`{action:'PASS'\|'REJECT', note}`。**PASS 只记结论、不动任何业务数据**（抽中 ≠ 下架）；**REJECT 是真处置** —— 商品驳回（`REJECTED`）+ 强制下架（`OFF_SHELF`）+ 来源记 `SAMPLE`，再上报商家违规计数（跨域，失败不回滚止血动作），最后落结论；`note` 判违规**必填**；**已复核再判 → 422**（否则误判率可被反复点通过刷平） | 运营 |
| GET | `/api/admin/audit-sla/metrics` | **SLA 自证指标**（窗口 30 天）：当前抽样比例 / 是否已自动收紧 / 已复核数 / 抽中违规数 / 抽中违规率 / 平均复核时长 / 误判率 / 待复核数。**读指标会顺带评估一次自动收紧**（连续 3 天抽中违规率 **> 10%** 才翻倍，恰好 10% 不算；封顶 40%） | 运营 |
| POST | `/api/admin/audit-sla/sweep` | **手动触发一轮抽查**：抽的是**昨天**那批自动放行商品（与定时器同口径，避免"点两次看到两个结果"）；**不看开关、不抢选主锁**；返回 `candidates/created/skipped/ratePercent/reasons`，幂等靠 `uk_biz_date`（重复点 `created=0`） | 运营 |
| GET/POST | `/api/admin/merchants/audit-sla/samples`、`/samples/{id}/review`、`/metrics`、`/sweep` | **入驻侧同链路**（店铺维度，路径前缀 `/api/admin/merchants`）：判违规走 `suspendByAudit` —— **资质造假是主体资格问题，直接停业、不攒次数**，停业用条件更新（`WHERE status='APPROVED'`，抢不到就不发通知不写留痕）；停业后**联动下架该店全部商品** | 运营 |
| GET | `/api/admin/audit-logs?actorId=&action=&category=&outcome=&targetId=&tenantId=&from=&to=&page=&pageSize=` | **E18 操作审计检索**：按操作人 / 动作标识 / 类目 / 结果 / 目标 / 租户 / 时间范围过滤，时间参数**两端都含**、起止颠倒或格式非法 **400**。返回 `immutable` 布尔值（**由服务端下发，前端不自行判断**）。**只有读，没有写/改/删** —— 审计表在代码层就没有 UPDATE / DELETE 路径（静态断言逐字扫描全仓） | 运营 |
| GET | `/api/admin/recon/runs?limit=` | **S3 对账轮次列表**（钱 / 库存两个范围各自一轮）：`{bizDate, scope, status, checkedCount, diffCount, highCount, startedAt, finishedAt, message}`。`status` 三态 **`CLEAN` / `DIRTY` / `FAILED`** —— `FAILED` 表示**没跑成**（依赖服务读不到 / 校验对象数为 0），**它与 `CLEAN` 的差别就是「报表一片绿但事实未知」与「真的没问题」的差别** | 运营 |
| GET | `/api/admin/recon/runs/{runId}/diffs?limit=` | **某一轮的差异明细**：按严重度（HIGH 优先）+ 规则 + 目标排序，带 `expected` / `actual` / `detail`（人能看懂的一句话）。**轮次不存在 → 404**（返回空列表会让「这一轮没有差异」与「这一轮不存在」混在一起） | 运营 |
| GET | `/api/admin/recon/rules` | **规则清单（源码声明 vs 运行时同一份）**：13 条（钱 8 / 库存 5），每条带 `scope` / `severity` / `refType`。冒烟脚本拿它和**源码正则数出来的条数**比对 —— 防「写了没上」 | 运营 |
| POST | `/api/admin/recon/runs` | **手动触发一轮对账**（唯一写端点）：`{bizDate?, scope?}`，默认今天 + 两个范围都跑。**同日重复触发是覆盖而非累积**（先删当天该轮次的明细再重算，靠 `uk_recon_run_day_scope` 兜底），返回两个范围里**最严重**的那个结论。动作码 `RECON_RUN_MANUAL` —— 触发本身不改业务数据，但它**会覆盖当天已存的结论**，所以必须留痕（否则"昨天的差异怎么没了"只能靠猜） | 运营 |

**★ S3 对账口径（每日自动对账 + 提醒）**

- **两个范围独立成败**：`MONEY` 与 `STOCK` 各落一条 `t_recon_run`。合并成一个范围的话，
  一次商品服务抖动会让整轮 `FAILED`，**连带把一个本来完全正确的钱对账结论也变成"未知"**。
- **对账 ≠ 把同一个函数算两次**：每条规则必须比**两个不同来源**（订单表 vs 托管表、交易域冻结台账 vs 商品域 `frozen_stock`）。
  拿同一个计算函数调两遍是恒等式，不是检查。唯一例外是 `MONEY_FEE_ILLEGAL`，它**故意不比费率**。
- **★ 钱是「政策的函数」，不是「当前费率的函数」**：拿库里存的历史 `platform_fee` 去比**今天的**费率，
  本地库实测产生 **92 条费率不符 + 375 条"自营被抽佣"** —— 全部是假的（那些单结算时费率还是 1%，
  或早于 E22 的自营免佣规则）。所以每日对账只做**结构性非法**检查（费率为负 / 佣金大于金额 / 金额为负），
  **费率类比较归回归测试**（`settlement-smoke`），不归每日对账。
- **`FAILED` 是一等状态**：取数抛异常、依赖服务不可用、`checkedCount == 0` 一律落 `FAILED`。
  `syncStatusFromCounts` 带 `AND status <> 'FAILED'`，**`FAILED` 永不被重新标成 `CLEAN`**。
  `checked_count == 0` 必须判 `FAILED` 的理由与铁律 2 同族：0 个对象必然「没有差异」，
  于是"什么都没看"与"看了且没问题"在报表上完全一样。
  **验证方式**：`docker stop duoduo-app-product` → STOCK 必须 `FAILED` 且带失败原因，同时 MONEY 不受影响。
- **同日重跑覆盖而非累积**：先 `deleteByRunOfDay` 再 `deleteByDayAndScope`，靠唯一键而不是应用层"查后写"。
- **计数必须回表读**：插入明细后重新 `COUNT(*)` 回写 —— `uk_recon_diff` 可能去重，
  否则报表说 5 条、点开只有 4 条。
- **跨域两向覆盖**：交易域只能送去"它知道有 FROZEN 的 SKU"，那个方向**永远发现不了**「交易域漏记冻结」。
  所以商品域新增反查 `GET /api/products/internal/skus/frozen-holders`，候选集 = 两个来源的**并集**。
- **严格读 vs 宽松读**：`ProductGateway` 原有的宽松读把 4xx 折成空 Map（购物车场景是对的：
  "这个商品不存在"是业务事实）；对账新增 `loadSnapshotsStrict` / `callStrict`，
  **任何**异常都抛 503 DEGRADED —— 对账里"读不到"≠"确实没有"，把前者当后者会把一次调用失败变成 N 条假差异。
- **暴露原始两列而不是 `availableStock`**：出参 `availableStock = max(0, stock - frozenStock)` 会把
  `frozenStock > stock` 这个异常**夹成 0**，前端只显示"售罄" —— 异常被一个看起来很合理的降级掩盖掉了。
- **提醒边界（刻意的）**：本轮不外接告警通道，只有 WARN 日志（带最多 10 条明细）+ 后台页 + 严重度计数。
- **★ 网关路由必须显式加 `/api/admin/recon`**：这个坑已经栽过 5 次（A11 / E14 / E18 / A3 / S3），
  现已固化为静态检查 `scripts/lib/check-gateway-routes.ps1`（250 条服务端路径逐条要求被网关覆盖，接 CI 第一步）。

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
| GET | `/api/socials/notices/unread-summary` | **合并未读** `{notice, message, total}` —— 角标的**唯一数据源**（小程序 tabBar 角标与**网页商城顶栏角标**都读它，两端同口径） | 需登录 |
| POST | `/api/socials/notices/{noticeId}/read` | 标记单条已读（幂等；他人 / 不存在 404，不区分以防枚举） | 需登录 |
| POST | `/api/socials/notices/read-all` | 全部已读（幂等） | 需登录 |
| GET | `/api/socials/messages/unread-count` | 私信未读数（**按条计**，对齐主流 App 的「3 条未读」语义） | 需登录 |
| POST | `/api/socials/messages/{messageId}/recall` | **撤回私信**（2026-10-24）：仅发送者本人 + 2 分钟窗口 + 幂等；软删留痕、库内内容覆写 | 需登录 |
| POST | `/api/support/messages/{messageId}/recall` | **撤回客服消息**（2026-10-24，同上口径）；对端经 WS `RECALL` 信号实时感知 | 需登录 |
| POST | `/api/seller/support/sessions/{id}/messages/{messageId}/recall` | 商家侧撤回（同口径，仅本会话） | 商家 |

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

网页商城侧（**2026-10-12 对齐**）：`packages/shared/src/store/app.tsx`（未读状态 + 实现）+
`apps/mall/src/components/Layout.tsx`（**顶栏头像角标**，按登录态启停轮询）。同样读 `unread-summary` 的 `total`，
刷新点三处（打开菜单 / 回前台 / 60s 轮询）、请求单飞、**失败不清零**。
e2e：`scripts/e2e/notice-chat-follow-ui-check.mjs`（**50 项**，④ 段专测角标）；自检步骤见 `docs/SELFTEST.md` §4.10c。

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
| 平台佣金 | `t_settlement.platform_fee` | 按 `duoduo.trade.platform-fee-bps`（默认 600 bp = 6%，自营 `seller_id=0` 免佣）计，**结算当刻的口径落库留档** |

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
- **越权断言有单一出口**：`scripts/privilege-matrix-smoke.ps1`（E19，**115 条 / 10 节 + §7b / 8 个模块**）
  是"现在到底防住了哪些越权"的唯一回答处，被一键自检按文件名自动收录。三条纪律：
  ① 每条「越权被拒」都必须配一条「有权者能过」的**正对照**（只有反例的矩阵在"整条链路坏掉、
  所有人都 403"时照样全绿，那时它测的不是权限而是服务还活着没有）；
  ② **网关层与服务端层分别断言** —— 只测网关时入口会先返 403，注解没注册 / 漏写照样绿，
  所以直连 8081/8082/8083 并自己写 `X-User-*` 头；
  ③ **状态码写具体值**，`-ne 200` 会把 400 / 401 / 500 一并算成"越权被正确拒绝"；
  ④ **§7b（S5「钱的口径」）**：钱与库存的四条口径固化成**算术**断言 —— 只断言"成功/失败"抓不到
  "少扣一次""多卖一件""优惠没落到明细"；这里每条都要**回表读数再做一次加减小乘**，且**必须报出比对笔数**
  （`checked_count == 0` 判失败 —— "一笔都没比"与"比了、全对"长得一模一样）。
- **业务规则**：宠物档案的「出生日期 vs 生命阶段」矛盾时拒绝写入并要求确认（对应 PRD PET-05）。

---

## 七、买家端 Web 商城（`frontend/`，M6 / P1-c）

### 技术栈与启动

React 18.3 · react-router-dom 6.28 · TypeScript 5.6（strict + noUncheckedIndexedAccess）· Vite 5.4。
无状态管理库（两个 Context 够用）、无 UI 框架（一份手写 CSS 主题）、**无任何运行时依赖做二维码**（自实现编码器）。

```bash
cd frontend
npm install        # monorepo（E7）：一次装齐 packages/* 与 apps/*
npm run dev        # http://localhost:5173，需后端 10 容器在跑（网关 :8080）；转发到 apps/mall
npm run build      # 转发到 apps/mall：tsc --noEmit && vite build
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

## 八、商家端 Web（`frontend/apps/merchant/`，M9 / P1-f；E6 起独立应用与独立站点）

与买家端、后台**共用同一个 npm workspaces 仓库**（`frontend/packages/*` 共享 `api-sdk` 与 `shared`），
但拥有**独立 HTML 产物与独立站点**（`merchant.localhost:8091`），按角色开入口。

| 路由 | 页面 | 说明 |
|---|---|---|
| `/apply` | 入驻申请 / 续期（E10 / E15） | 分步表单（主体信息 / 联系人 / 资质材料**逐份录有效期**）；材料走后端签发的**私有通道**预览；草稿可存；被驳回显示原因并可重提；**停业后在此续期**（提交后回 `SUBMITTED` 重走审核，不自动通过） |
| `/seller` | 经营概览 | 六状态订单数 + 托管 / 已放款 / 已退回三栏资金；平台自营范围提示；**资质到期横幅**（T30 / T7 / 已停业三档，指明**哪张证**要到期 + 剩余天数 + 直达续期） |
| `/seller/orders` | 订单管理 | 状态页签过滤；列表卡片带买家收货信息；**立即发货**弹窗（物流公司 / 单号） |
| `/seller/orders/:orderNo` | 订单详情 | 状态步骤条 + 履约信息 + 资金区 + 发货 / 退款表单 |
| `/seller/settlements` | 结算对账 | 分页表格 + **全量汇总三栏**（过滤不变）；与买家端口径同源 |
| `/seller/products` | 商品管理（E14） | **「+ 发布商品」弹窗**（类目 / 品牌 / 主图 / 详情图 / 详情 / 多规格；价格填**元**、提交按分换算）+ 列表**两个正交维度各一列**（交易状态 / 审核状态）+ 审核维度筛选 + 驳回原因展示 + 编辑（**提示「内容变更会重新送审」**）+ 驳回件的「重新提交」+ 上下架（未过审上架被后端 422，页面把原因说清） |
| `/seller/staff` | 店员管理（E13） | 店主按手机号邀请 / 停用店员；店员进入本页 403 |
| `/seller/after-sales` | 售后处理 | 同意即退款 / 拒绝必填原因 |

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

## 九、平台管理后台 Web（`frontend/apps/admin/`，M10；E6 起独立应用与独立站点）

运营 / 管理员在**后台独立站点**（`admin.localhost:8090`，独立登录，不与商城共享 Cookie）登录后，
**两条入口**同时出现（共用 `isOperator()` 判定，见第六十三轮）：
顶栏导航的「管理后台」+ 头像下拉菜单的「进入管理后台」，都指向 `/admin`（运营概览）。
十四个工作台共用一条标签导航（**其中 12 个在标签里**；「入驻审核台」与「商家子账号」
刻意不常驻 —— 它们从「运营概览」的待办入口进，属于"有活才去"的页面）：

| 路由 | 工作台 | 内容 |
|---|---|---|
| `/admin` | 运营概览 | 经营报表（GMV / 订单量 / 退款率 / 转化率）+ 资金对账三栏 + **异常挂账表**（已付款却缺托管单，需人工回补）+ **入驻待办 / 子账号待办入口** |
| `/admin/merchants` | 入驻审核台（E11 / E15，**不在常驻标签**） | 待审商家队列 + 材料**大图预览** + 一致性核对提示 + 逐条结构化驳回（必填原因）；**资质列**（逐份材料有效期 + 派生最早到期日 + 与店铺的关系）+ **停业原因** + **「立即巡检资质」按钮**（跑生产同一条 `sweepOnce()` 路径，处置全是条件更新 + 只升档才提醒，可安全重复点） |
| `/admin/merchant-staff` | 商家子账号（E13，**不在常驻标签**） | 全平台店员/子账号统一台账：按商家 / 状态检索、平台侧停用（商家自管 + 平台统管） |
| `/admin/audits` | 内容审核 | 待审 / 已通过 / 已驳回三视图；通过后公开可见，驳回必须填原因（留痕：原因 + 时间） |
| `/admin/product-audits` | 商品审核（E14） | 第三方商品待审队列（**自营免审不混入待办**）；卡片直接摊开背审材料（主图 / 标题 / 类目品牌 / 规格价格 / 详情摘要）；通过 / 驳回（**必填原因**）/ **驳回改判**（审核可重入）；可按卖家 ID 筛选 |
| `/admin/audit-sla` | 抽查复核（E28） | 事后抽查队列（**与上面两个"等着放行"的队列语义不同** —— 这是"已经在卖了、我回头看"，所以是独立入口而不是并进审核台）+ 已复核视图 + 六项 SLA 指标 + 判违规必填依据 |
| `/admin/share-reports` | 举报处理 | 列表**直接显示被举报内容摘要**；弹窗默认结论为「举报不成立」（一次误点不会删掉别人的内容） |
| `/admin/categories` | 类目管理 | 增改删 + 启停用；状态列 + 按状态切换按钮；**停用项仍可见可恢复** |
| `/admin/brands` | 品牌管理 | 同类目 |
| `/admin/orders` | 订单干预 | 全平台订单（状态过滤 + 订单号前缀搜索）；**仅待付款**可强制关单（确认弹窗说明后果：作废支付单、释放冻结、写状态日志） |
| `/admin/after-sales` | 售后仲裁 | 全平台售后列表 + 裁定弹窗（支持退款**真的执行** / 驳回，说明必填） |
| `/admin/operators` | 运营账号 | 脱敏列表 + 检索 + 改角色 Modal（**改角色仅 ADMIN 可操作**） |
| `/admin/operation-audits` | **操作审计（E18）** | **只读追责台账**（**与上面的「内容审核」是两件事**：那边是内容审批队列、有操作列；这边是操作留痕、**连操作列都没有**）。按操作人 / 动作标识 / 类目 / 结果 / 目标 / 租户 / 时间范围检索；行展开看变更前后值、来源 IP、链路 ID；**资金与权限两类打「不可删改」标签**，且该标记**由服务端下发** |
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

### 2026-10-22（七十一）· 十问题批次：咨询/店铺/客服/未读体验改造（Q1~Q10）

- **后端**：① social `SupportService` 自营兜底（sellerId 0/null → `officialCsUserId`）+ 会话店铺名
  （`UserClient.storeNames` + `StoreBrief`，`sellerDisplayOf` 店铺名优先、自营保持昵称）；
  ② social `NoticeController.unreadByCategory` 五类分桶（排除私信/客服，与徽标同口径）；
  ③ trade `OrderService` 经 `StoreNameClient`（`contextId=tradeStoreNameClient`）批量补
  `OrderView.storeName`，自营固定「多多自营」；user 域 `storeNamesOf` 只回 `APPROVED`；
  ④ product `storeProducts` 增 `keyword`（本店范围 like，不进 ES）。
- **前端**：`Store.tsx` 排序/搜索工具条；`Orders.tsx` 店铺名 Link；`Support.tsx`/`SupportInbox.tsx`
  逐条未读标记与已读回执；`chat-media.tsx` 二级菜单 + 图片灯箱 + 视频自绘弹窗；
  `product-picker.tsx`「正在看」回源；`MerchantRoot.tsx` `NavBadge` 导航角标；
  `Notifications.tsx` 分类角标。角标一律 `aria-hidden`。
- **新文件**：`docs/notification-unread-badge-design.md`（三端未读方案）、
  `scripts/web10-batch-check.py`（9 项定向验证）、social/trade 两个 `StoreBrief` 与 `StoreNameClient`。
- **验证**：`web10-batch-check.py` **9/0**；e2e store-page **17/0**、notice-chat-follow **50/0**、
  seller **40/0**、cart-batch **31/0**、product-detail **19/0**；support-read **20/0**、
  support-split **22/0**；trade 单测 **119/0**、social **16/0**；六容器重建 healthy。
  顺带修 cart-batch 三处脆弱点（弹窗遮罩等待 /「清空·删除已选」两态文案 / 空车自愈夹具）。

### 2026-10-22（七十）· 修复：第三方商品错挂「多多自营」徽标；自营补进店入口

- **用户报障（附截图）**：「喵星人旗舰店（测试）」的商品卡徽标显示「多多自营」；且多多自营没有独立进店入口。
- **根因①（标签映射）**：`SupplyTag` 只认 `supplyType === 'merchant'`，而数据库
  `t_spu.supply_type` 的真实取值是 **`self` / `third`**（select distinct 实测）——
  所有第三方商品都落进 else 分支被挂上「多多自营」，与店名「喵星人旗舰店」同框。
  判断改为**以 `self` 为白名单**（是自营才显示自营），`secondhand` 单列「个人闲置」
  （首页闲置区此前同样被错挂「多多自营」）。小程序 `product.wxml` 同一份判断、同一处错，已同步修。
- **根因②（字典与数据脱节，连坐 bug）**：`MetaController` 供货类型字典写的是
  `self` / `merchant`，`ProductService.search()` 校验也只认 `merchant` ——
  搜索页选「第三方商家」发 `merchant` → 库里无此值 → **空结果**（静默失败）；
  而正确的 `third` 反而被 422。字典、校验、前端 `ProductQuery` 类型与本地兜底字典
  全部统一到 `self` / `third`。
- **自营进店入口（第八十二轮补全）**：自营商品卡/详情页/购物车组头的「多多自营」
  现在也可点 → `/store/0` 自营货架（第三方跳 `/store/{sellerId}`，sellerId 是店主 userId；
  自营卡片的 sellerId 是客服账号 id，不能当店主 id，故固定跳 0 号货架）。
- **验证**：新增 e2e `supply-tag-ui-check.mjs` **15/15**（第三方卡「品牌入驻」且不含「多多自营」/
  自营卡「多多自营」且店名可点跳 /store/0 / 搜索 third 口径反向验证 / 裂图与 console 健康）；
  回归 `cart-batch-ui-check` 29 项 ×2 轮全过、`product-detail-ui-check` 19/0、`store-page-ui-check` 17/0。
- **测试数据**：`cart-batch-ui-check` 每轮净删 1 行，连续多轮后购物车被清空导致该套件
  起手即超时（非代码回归）——已按 SKU 3010/3020/92001 补回夹具。

### 2026-10-22（六十九）· 店铺主页（参考淘宝店铺页）

- **后端 · 用户域**：新增公开只读端点 `GET /api/users/stores/{ownerUserId}`（`StorePublicView`：
  ownerUserId / name / openedAt）。仅 `APPROVED` 营业中店铺可查，其余状态 404 ——
  与「未过审商品不可见」同口径；只回店名与开店时间，不含资质材料/违规计数/停业原因。
- **网关**：白名单**两段精确**放行 `/api/users/stores/*`（Ant 的 `*` 不跨 `/`）；
  `/api/users/**` 下其余路径（资料/地址/入驻申请/内部端点）仍强制登录。
  单测补「免登放行」+「不连带放行 users 子树其它路径」两组断言。
- **后端 · 商品域**：新增 `GET /api/products/store/{sellerId}?page=&pageSize=&sort=`。
  两段路径避开 `/{spuId:\d+}` 单段详情。可见口径复用 `visibleSpuWrapper()`（在售 + 已过审）；
  **刻意不进 ES**（ES 引擎无 sellerId 维度过滤器，复用 search() 会出现「开 ES 后店铺页展示别家商品」）。
  **sellerId=0 按供货类型过滤**：`t_spu.seller_id` 对自营商品落的是客服账号 id（第七十三轮口径），
  库中没有 0 号行 —— 按 0 过滤会得到空货架，故 0 走 `supply_type='self'`。
  店铺名随 `ProductCard.storeName` 自动下发。
- **前端**：新增路由 `/store/:sellerId`（公开，不套 RequireAuth）+ `Store.tsx` 页面
  （页头：店名 + 自营徽标 + 开店时间/自营文案 + 在售计数；商品网格复用 `ProductCardView`；
  「加载更多」分页）。入口三处：商品卡店名（span + stopPropagation，外层是 Link 不能嵌 a）、
  商品详情页店名（Link，仅第三方）、购物车组头「进店 ›」（preventDefault + stopPropagation，
  外层是 label 点它会切全选）。api-sdk 增 `productApi.storeProducts` / `userApi.storeInfo` / `StorePublicView`。
- **移动端适配**：商品网格 `grid-template-columns: repeat(auto-fill, minmax(210px, 1fr))` 窄屏自动 2 列；
  页头 flex + `min-width:0` 不溢出；「加载更多」按钮式分页不受软键盘/地址栏伸缩影响。
- **数据修正**：测试店铺「喵星人旗舰店（测试）」`t_merchant.status` 脏值 `ACTIVE`（不在枚举内）→ 修为 `APPROVED`。
- **验证**：e2e `store-page-ui-check.mjs` **17/17**（自营货架直开 / 进店跳转 / 页头信息 / 网格与详情跳转 /
  分页 / 不存在店铺 404 兜底 / 裂图与 console 健康）；接口实测 `/api/users/stores/...` 200、
  `/api/products/store/0` 返回自营商品、`/store/{第三方}` 返回该店 2 件商品；
  回归 `cart-batch-ui-check` 全过、`product-detail-ui-check` 19/0；
  单测网关 31/0（新增 2 组断言）、商品可见性 10/0（构造器补 StoreNameClient 桩）。
- **部署坑（复现第八十轮的教训）**：镜像打的是本地 `target/*.jar` —— 只 `mvn compile` 后
  `docker compose up --build` 仍会把**旧 jar** 打进镜像（构建产物没更新，Docker 层缓存“正确地”复用旧文件）。
  必须先 `mvn package` 再 build。

### 2026-10-22（六十八）· 购物车按店铺分组 + 结算明细；店铺名可自助修改

- **购物车按店铺分组**：以 `CartItemView.sellerId` 分组（自营恒 0，第三方为店主 userId），
  每组一个 `section`：店级复选框（部分选中显示半选）+ **店铺名称** + 自营徽标 + 「N 件 · 已选 ¥x」。
  分组基于 `orderedItems`，与第六十七轮的顺序快照护栏叠加，全选/取消不会打乱组内外顺序。
- **真实店铺名打通（原先是前端写死「第三方入驻」）**：
  `t_merchant.name` → `GET /api/users/internal/store-names?ownerIds=`（user 域，内部令牌校验）
  → `StoreNameClient`（product 域 Feign，`contextId=productStoreNameClient`）
  → `SkuPurchaseView.storeName` / `ProductCard.storeName` → trade 域透传进 `CartItemView.storeName`。
  自营固定 `ProductService.SELF_STORE_NAME = "多多自营"` 不查库；user 域异常时降级为 `null`，
  前端回退原「品牌入驻」标识，不阻断渲染。
- **结算明细（参考淘宝）**：勾选金额驱动 `GET /api/coupons/mine/usable?amount=`，取 `bestCouponCode`
  对应券算 `payableAmount = selectedAmount - discountAmount`（下限 0）；面板列「商品总价 / 优惠券 /
  优惠共减 / 合计」，吸底栏共用该值并显示「已优惠 ¥x」。金额不在前端累加，一律用服务端 `selectedAmount`。
  面板注明「实际优惠金额以下单页为准」。
- **店铺名可自助修改（新能力）**：此前 `canEditMaterials()` 仅 DRAFT/REJECTED 可用，APPROVED **无任何改名入口**。
  新增 `PATCH /api/users/me/merchant-application/store-name`：`MerchantStatus.canRenameStore()` 仅 `APPROVED`；
  `validateStoreName` 复用；撞名 → `DuplicateKeyException` → 「店铺名已被使用」；审计 `MERCHANT_STORE_RENAME`
  记 before/after；同值幂等。改名不涉及资质材料，不触发重审。商家端 `Account.tsx` 新增 `StoreRenamePanel`。
- **展示场景**：`ProductCardView` 的 `goods-meta` 以新增 `StoreNameText` 替换品牌名，`ProductDetail`、
  `Cart` 组头、`Checkout`（按 sellerId 分组，文案更正为「按店拆单、券按比例分摊（R60 口径）」）同步。
- **验证**：`cart-batch-ui-check.mjs` **29/29**（新增组名/自营徽标/结算明细面板/明细合计断言，保留 10 次顺序断言）；
  `GET /api/cart` 返回 `{"store":"喵星人旗舰店（测试）","self":false}` 与 `{"store":"多多自营","self":true}`；
  改名 `小李宠物用品 ↔ 小李宠物用品旗舰店` 两次 200 且审计两条；`GET /api/products` 卡片带 `store`。
- **边界**：营销域券表无 `merchant_id/scope`，暂无「店铺优惠」维度（结算明细已预留累加点）；小程序端商家无改名入口；
  改名未限频；店名不进订单快照。
- **部署坑**：monorepo 连续 `npm run build` 偶发第三个应用 `emptyDir` 失败，单独重跑该应用即可；
  核对 `merchant.localhost:8091` 需 `NO_PROXY=*`（系统代理不解析该域名，会返回 502）。

### 2026-10-22（六十七）· 修复：全选/取消全选后购物车商品顺序被打乱

- **现象**：初始「麦富迪 → 卫仕 → 未卡」，点全选再取消，变成「卫仕 → 未卡 → 麦富迪」，
  看起来像随机打乱。
- **根因（代码路径）**：`CartService.list()` 用 `ORDER BY updated_at DESC, id DESC`；
  而「勾选 / 取消勾选」走 `updateSelected()` → `row.setUpdatedAt(nowUtc())` → `updateById`
  ——**勾选这个纯选择动作改写了排序键**。前端 `selectAll()` 逐条串行 PATCH（没有批量接口），
  每轮 `afterMutation()` → `reload()` 重取列表，于是最后一次被 PATCH 的 SKU 必被顶到最前，
  与用户「1、2、3」的初始顺序脱钩。改数量的 `updateQuantity()` 同样写 `updated_at`，同一个坑。
- **修复（后端，根因）**：排序改为 `ORDER BY created_at DESC, id DESC` ——
  只依赖「加入购物车的时间」，任何勾选/改数量都不再影响位置；`created_at` 同毫秒时用雪花 `id` 兜底，
  每行位置绝对确定。
- **修复（前端，护栏）**：`Cart.tsx` 增加顺序快照 `orderRef`——首次拿到列表记下 skuId 顺序，
  之后每次 `reload()` 都按该序列重排；新加购的商品追加到尾部（不会插队）。
  即使将来后端排序再被动过，用户也看不到商品跳位。
- **验证**：`scripts/e2e/cart-batch-ui-check.mjs` 扩到 **23 项全过**——新增「基线顺序 + 全选/取消 ×3 轮
  + 改数量」共 10 次顺序断言，全部逐字比对标题序列。
- **反向验证**：把后端临时改回 `updated_at` 并旁路前端护栏，**第 3 轮全选恰好抓到顺序被打乱**
  （仅这一条红，其余仍绿）；随后恢复正式版，容器内字节码核对为 `getCreatedAt`。
- **踩坑记录**：`docker compose up -d --force-recreate` **不会重建镜像**，
  `image:` + `build.args JAR_FILE` 的组合必须带 `--build`，否则容器里跑的还是旧 jar
  （表现为「代码改了、测试没变」）。核对方式：解包 `/app/app.jar` 里的 `BOOT-INF/classes` 看字段名。

### 2026-10-23（七十）· 修复：非文字消息发送后不恢复「结束咨询」

- 四条发送路径收敛到 `deliver()`：成功即 `setEndedSessionId(null)` + 刷新（此前只在文字分支清理）。
- 验证加 8 条（贴纸/图片），反向验证会红 → 恢复后 39/0。

### 2026-10-23（六十九）· 客服二期跟进：发送后定位 / 视频控制条悬浮 / 结束咨询交互

- **发送定位**：`items.length` → **末条消息 id** 作为 effect 依赖（分页窗口滑动时长度恒定）；
  商城客服/私信/商家/小程序两页共 5 处。
- **视频控制条**：`position:absolute` + 渐变 + `blur(10px)` 浮层，视频全屏 `height:100%`；
  `wake()` 5 秒定时器控制 `opacity` 与 `pointerEvents`，指针移动/按下唤醒。
- **结束咨询**：`endedSessionId` → 按钮消失 + `.sup-system` 小字；发送成功清状态并恢复。
- **验证**：31/0 + 53/0，三套回归全绿。

### 2026-10-23（六十八）· 客服会话二期：视频封面 / 定位未读 / 四档筛选 / 关闭规则 / 30 天清理

- **视频封面**：`captureVideoPoster`（seek 0.1s → canvas ≤520 → JPEG）→ 与视频一起上传 → `thumbUrl`；
  `MediaBubble` 无封面时用媒体片段 `#t=0.1` 渲染首帧；小程序上传 `thumbTempFilePath`。
- **定位最新未读**：`data-first-unread` + `scrollIntoView`，无未读才落底；effect 依赖补 `loading`
  （首屏 `LoadingBlock` 期间容器尚未挂载）。
- **商家筛选**：`filter=ALL|UNREAD|ANSWERED|CLOSED`；「已处理」= `unread_for_seller=0`。
- **关闭规则**：`closed_at` + `POST /api/support/close`（仅买家）；商家 status 接口 403；
  买家侧 status 服务端置 null；重开用 `LambdaUpdateWrapper` 显式清空 `closed_at`；
  24h 静默由 `UPDATE ... WHERE` 批量结束（多实例幂等）。
- **清理**：`SupportMaintenanceService` 每日 04:20；先删对象再删库，失败跳过下轮重试；
  履约域新增 `POST /api/uploads/internal/delete`（只认 `/uploads/`）。
- **小程序**：关闭文案清零、结束咨询、定位未读、视频 poster、未读琥珀、表情/贴纸面板。
- **验证**：32/0 + 25/0 新增，五套回归全绿。

### 2026-10-23（六十七）· 客服二次修复：精确会话 / 店铺归属 / 媒体体验

- **Q1 精确选中**：`SupportEntry` 不再吞掉 `sellerId=0`；`Support.tsx` 依赖改为 `[sellerIdParam]`，
  用服务端返回的 `data.sellerId` 回填（自营 0 → 官方客服 id），并补拉列表、滚动到可见。
- **Q3 订单卡**：移除订单号，店名前置 + `›`（`aria-hidden`），详情页保留「订单 {orderNo}」。
- **Q4 店铺归属**：新增 `MembershipBrief` + `UserClient.merchantMembership`，商家端接待身份统一解析到
  `ownerUserId`；店员与店主同列表，其余 403。实测店员返回 `sellerId=370518346074034176`。
- **Q5 未读**：` · 未读` 并入时间行（`.sup-unread`，`var(--warn)`），无容器。
- **Q6 面板**：`.sup-body` 高度 `clamp(320px,60vh,620px)`；面板 `fixed` 定位 + `maxHeight:min(58vh,420px)`
  + `overflowY:auto`；表情 56、贴纸 12；补 `Escape`/外部点击关闭。
- **Q10 媒体**：去 `.card` 容器 → 轻蒙层；灯箱原生 `wheel` + `passive:false` 实现 Ctrl 缩放（0.3x~6x）、
  拖动平移、双击复位；进度条 16px 命中区 + window 级 `pointermove/pointerup`；全屏改播放器盒子尺寸
  （`calc(100vh - 56px)`），关闭按钮移入全屏元素（否则被 `<video>` 盖住）；暗色按钮显式透明底、
  `▶⏸⛶` 换内联 SVG（Windows 字体缺字会渲染成方块）。
- **未选会话禁发**：四个发送入口判 `activeSellerId`，未选中时不渲染输入区；商家端同改。
- **验证**：`support-ux-ui-check.mjs` 47/0 + 八套回归全绿。

### 2026-10-22（六十六）· 购物车批量操作：全选半选 + 删除已选 + 确认弹窗

- **删除已选**：底部工具栏新增「删除已选（n）」（danger 色，0 件禁用）→ 复用已有
  `DELETE /api/cart/selected`；确认弹窗中**全选时文案升级为「清空已选商品/全部删除」**，
  普通批量显示件数与合计。
- **单条删除加确认**：行内「删除」先弹「确定将「xx」移出购物车吗？」，不可撤销提示。
- **全选半选态**：部分选中时全选框 `indeterminate`（此前只有全选/全不选两态，中间态看不出）。
- **同步**：所有写操作后统一 `reload() + refreshCart()`——已选件数、合计金额、头部角标一起变。
- **验证**：真实浏览器 14 项全过（全选/取消联动、0 件禁用、确认框拦截、取消不删、
  数量改 5→6 合计 564.60→653.60、批量删后行数-1 且归零）；脚本 `scripts/e2e/cart-batch-ui-check.mjs`。

### 2026-10-22（六十五）· 排障：购物车/结算页封面裂图

- **根因**：购物车下发 SKU 图 `/img/sku/*.svg`（语义占位路径，文件从不存在）；
  `Cart.tsx` / `Checkout.tsx` 直接 `src={item.image}` 没走 `images.ts` 的重映射层（全仓仅此两处漏网）。
- **迷惑点**：nginx SPA 回退把 404 变 **200 text/html**，网络面板看是绿的，`<img>` 却渲染不了 HTML。
- **修复**：两页接入 `skuImage(skuId, spuId, image)`，与商品列表/详情同口径。
- **验证**：真实浏览器抓包 src 变为 `p09.jpg`/`p02.jpg`（200 image/jpeg，naturalWidth=600）。
  排查脚本沉淀为 `scripts/e2e/cart-img-debug.mjs`。

### 2026-10-22（六十四）· 足迹页对标淘宝：按天分组 + 分页 + 批量删除 + 搜索

- **分组**：接口返回扁平倒序列表，前端按**北京自然日**分组（`bjDateKey`，不能拿 UTC 串截日期）；
  组标题「今天 / 昨天 / M月D日（N件）」，相对称呼只给近两天，与淘宝同口径；组标题吸顶。
- **分页**：每页 50 条，滚到底自动加载（IntersectionObserver）+ 按钮兜底；前端按 id 兜底去重。
- **删除**：新增 `DELETE /api/history/{spuId}` 与 `DELETE /api/history/batch?ids=`（恒带 userId 条件，
  越权静默无效）；卡片勾选 → 「删除所选（n）」→ Modal 确认；「清空」同样加确认。
- **搜索**：标题本地过滤（不动后端），空结果有专用空态。
- **视觉**：下架商品置灰 + 角标；空态补「去逛逛」按钮；样式全走语义变量（暗夜模式自动适配）。
- **验证**：接口专项 `history-order-verify.py` 15/0（倒序/UTC/批量/单删/越权反向）；媒体+收藏回归
  15/0；UI `history-ui-verify.mjs` 6/0（真实页面两组「今天/昨天」+ 勾选 + 批量按钮截图）。

### 2026-10-22（六十三）· 足迹时间戳：卡片直接显示「哪天看的」

- **后端**：`/api/history/products` 每条卡片随下发 `viewedAt`（UTC ISO，带 Z）；
  排序本就按 `viewed_at` 倒序（同商品重复浏览 upsert 只留最近一次），专项验证 9/0
  （浏览 3 件 → 倒序、时间递减、UTC 格式）。
- **前端**：足迹卡片新增「浏览于 今天/昨天/MM-DD HH:mm」一行（北京时间统一口径
  `time.ts` 渲染）；收藏/已购接口不下发该字段。UI 验证 `history-ui-verify.mjs` 通过。
- **注意**：本轮排障发现 Bash 里 `mvn` 缺 `JAVA_HOME` 会**静默无输出 exit 0**（假绿），
  打包后必须核对 target jar 时间戳。

### 2026-10-22（六十二）· 端侧视频压缩、跨站账号提示、入口可发现性

- **端侧压缩**：仅校验时长 ≤60 秒、不限制大小；>6MB 用 canvas+MediaRecorder 压到 720p/1.6Mbps，
  优先 mp4、否则 webm；失败/不支持/不更小一律回退原视频；压缩与上传都有百分比提示。
  实测 13.2MB/30s → 5.52MB（-58%）；64.8 秒视频被明确拒绝。
- **服务端**：视频不再按大小拒绝，接受 webm，Range 覆盖 webm，nginx 512m。
- **小程序**：`utils/media.js`（getVideoInfo 校验 + compressVideo 压缩 + 回退）。
- **跨站账号**：商家端跳商城带 `expect`；商城发现不是同一账号时提示并提供「切换账号 / 继续」，
  不再让用户误以为商家账号进了买家个人中心。
- **入口**：商家工作台客服页签对所有角色可见（运营/店员看到职责说明）；个人中心新增「我的商品」卡
  （收藏夹/足迹），头像下拉同步加入口。

### 2026-10-21（六十一）· 订单卡片与内置贴纸

- **订单卡**：订单详情新增「咨询订单」；服务端回交易域取事实（`/api/orders/internal/{orderNo}/fact`），
  校验归属后落快照，卡片展示订单尾号/状态/商品；商家端只读不跳转。
- **贴纸**：内置 6 张 SVG，以 EMOJI 类型发送；纯 emoji 文本继续放大显示。
- **验证**：媒体冒烟扩到 15/0（含「他人订单 403」），其余回归全绿。
- **真实视频已验证**：81MB 录屏转码 720p/60s（5.5MB）→ COS → `206` 分段 → 播放器 `duration=60`、
  `currentTime` 正常走带，商家端可播。原文件 81MB 会被 20MB 上限拒绝（设计行为）。

### 2026-10-21（六十）· 客服 B/C 批：图片视频、四类商品来源

- **多媒体**：上传支持 mp4（20MB）、读取支持 Range（206）；客服（用户/商家）与私信统一支持图片/视频/表情；
  Web 与小程序都有「＋」入口（小程序支持拍摄与相册）。
- **商品来源**：新增收藏夹与足迹（增量 40），交易域新增「已购买商品」；客服「🛍」可从
  当前浏览/购物车/收藏夹/已购买四类来源发送商品卡；个人中心新增两个入口，商品详情新增收藏按钮。
- **私信**：同样支持媒体；商品卡仅限当前咨询的商品。
- **验证**：`media-favorite-smoke.py` 12/0，客服两套冒烟 20/0、22/0，登录隔离 11/0，主题守卫 55/0 与 25/0。
- **未做**：订单卡片（C4）、真实视频播放/小程序真机、贴纸表情包。

### 2026-10-21（五十九）· 三端登录态彻底隔离 + 时间统一北京时间 + 工作台账号中心

- **登录态**：Cookie 按主机名隔离但**不区分端口**，`localhost:8088` 与 `localhost:8091` 共用一个罐；
  原先三站共用 `dd_at`，商家端登录会顶掉商城会话。现按入口分名：`dd_at` / `dd_at_m` / `dd_at_a`
  （`AuthCookieWriter` + 网关 `resolveToken`）。商家与后台**不回退**基础名，商城保留回退。
- **个人中心**：商家工作台新增 `/seller/account`，展示当前工作台身份（角色/手机/账号/注册时间/店铺），
  顶栏头像与按钮改为站内跳转；不再指向商城 `/me`（那是另一套登录态，会显示商城账号）。
- **时间**：后端 UTC 序列化，前端原先直接截 ISO 字符串 → 早 8 小时。新增
  `shared/lib/time.ts`（Asia/Shanghai），客服/私信/聊天/通知统一改走它。
- **静态资源**：商家端 `publicDir` 指向商城 `public`，客服商品卡图不再 404。
- **验证**：`scripts/auth-isolation-smoke.py` 11/0；`support-split-smoke.py` 22/0；Playwright 三流程实测。

### 2026-10-21（五十八）· 客服消息独立化：自营/第三方分治，接待在商家工作台

**问题**：客服会话以普通私信存在（`t_social_message`），于是①混进私信列表 ②受「非互关限一条」
约束 ③平台后台也没有接待界面（用户报"后台看不到消息"，实际是页面从未建过）。

**改动**：
- **数据层分离**：增量 37 新增 `t_support_session`（唯一键 `seller_id + user_id`）与
  `t_support_message`；历史客服/商品咨询消息迁出并从私信表删除，迁移台账 `t_support_migration_log`
  可回滚对账。
- **两类客服不靠类型字段区分**：`seller_id` 就是接待方 —— 自营 = 官方客服账号，
  第三方 = 商品归属卖家；判据 `sellerId == officialCsUserId`，与商品域同源。
- **接口**：用户侧 `/api/support/**`（取建会话 / 会话列表 / 消息 / 发送 / 已读 / 未读）；
  商家侧 `/api/seller/support/**`（列表 / 详情 / 回复 / 已读 / 关闭重开 / 未读），
  准入 `LoginUser.isSeller()`，`sellerId` 从登录态推导，不接受请求参数。旧 `/api/socials/support/**` 已删。
- **规则**：客服**没有**「关注后才能发」，频控 60 秒 20 条；普通私信保留原规则；
  闲置（跳蚤市场）只走私信，不进客服。
- **入口**：商城 `/support`；商家工作台「客服信息」页签（`/seller/support`）；
  小程序 `pages/support`。平台运营端**不加**客服入口 —— 运营只管平台级事务。
- **验证**：`scripts/support-split-smoke.py` 22/0；反向验证（去掉角色校验）21/1 且总数不变。

### 2026-10-21（五十七）· 小程序端暗夜模式（A2 四端收官）

D3 拍板「四端全做」后的最后一块。小程序与 Web 机制不同：没有全局 Provider，
靠 **`@media (prefers-color-scheme: dark)`（wxss 原生支持）+ `theme.json` 两档主题**：

- **原生层**：`app.json` 开 `darkmode: true` + `themeLocation`，原生导航栏 / 窗口背景 /
  tabBar 颜色全部改引 `@变量`；`theme.json` 两档定义。暗色 tabBar `#171a21`、选中色提亮一档
  （`#4f6ef7 → #8fa2ff`）；tabBar `borderStyle` 只接受 black/white 字面量、不能引用变量（平台限制，保留 black）。
- **变量层**：`app.wxss` 暗色块覆盖 10 个语义 token（正文 `#c9cdd6`，与 Web 端同一档柔和度；
  卡片 `#171a21`、页面底 `#0f1115`、边框 `#2a2f3a`），并新增 `--gray-weak`/`--warn-weak`；
  骨架屏底与高光反向（深底上 shimmer 才看得见"流动"）；卡片阴影换黑色高不透明度。
- **自绘导航栏**（26 个页面在用）：组件 wxss 加暗色覆盖，品牌蓝压暗为 `#2b3550` ——
  亮蓝大色块在深色页面顶端过于刺眼，压暗后保品牌识别；白字标题/返回键无需改。
- **页面样式批量替换**：27 个 wxss 文件、约 90 处硬编码色 → 语义变量
  （background `#fff`→`var(--card)` 等 9 条映射，全部浅色取值等于原值 —— **浅色档零变化**；
  `color: #fff` 26 处白字压彩色底刻意保留）。tabBar 图标是浅灰线条 PNG，天然适配深底，无需重做。
- wxml 内联样式与 JS 动态配色核查过：只有 2 处轮播指示点品牌色，两主题通用，无需处理。

**验证**：`check-miniprogram-dark.mjs`（新守卫）**25/0** —— darkmode 开关、theme.json 两档、
变量成对、亮度方向（暗色正文更亮/页面底更暗）、自绘导航栏覆盖、`background #fff` 防回潮；
反向验证：注入 2 处破坏（暗色正文改暗 + 页面混入 `background: #fff`）→ 恰好 2 条红、
其余全绿，还原后 25/0；`check-miniprogram.js` 0 错误 / 0 告警；app.json / theme.json JSON 合法性通过。
**真机/开发者工具的最终手感（切系统暗色看 5 个 tab 页 + 商品页）待用户侧确认** ——
自动化测不到微信开发者工具的模拟器渲染。

### 2026-10-21（五十六）· 商品详情页重排：两栏失衡修复 ＋ 宽屏适配

**用户报障**：商详页"图片区和介绍区容器宽度不足，DIV 只占页面一半，页面拥挤狭窄"。

**实测定位根因**（Playwright 读 bounding box）：布局本身是满宽的，但
① 全站容器 `--max-w: 1200px` 在 2K/4K 逻辑宽下只占视口约一半（用户截图内容宽恰为视口 47%，
与 1200/2560 精确吻合）；② 旧结构把「商品介绍（含 3 张大图）/ 规格参数 / 包装规格」四张长卡
全堆在左列，左列被拉到两三屏长、右列（购买信息）大量留白，重心严重失衡。

**重排为标准电商详情结构**：

```
旧：[图片+介绍+大图×3+规格+包装] | [购买信息]     ← 左列超长、右列空
新：[主图+缩略图 45%] | [购买信息 55%]            ← 顶部两栏干净均衡
    [商品介绍 通栏（大图 auto-fit 两列并排）]
    [规格参数 | 包装规格 双栏]
    [评价 / 同类推荐 / 看过又看（原有）]
```

- `--max-w: 1200px → 1320px`：全站统一放宽，1366 笔记本不挤压、2K 屏少留白；
- 两栏比例 `1.05:1 → 46:54`，购买信息列更宽，价格/规格/按钮不再挤；
- 介绍大图 `.pd-detail-imgs`（`auto-fill minmax(300px,1fr)`）：宽屏两列并排，不再竖向长条；
- 规格参数/包装规格 `.pd-cols-2` 并排；两者都用 auto-fit，窄屏自动单列；
- 缩略图从内联样式抽成 `.pd-thumb` 类（72px、选中态品牌色描边、hover 反馈）；
- 介绍正文 `.pd-detail-text`：14px / 行高 1.75，长文可读性优先。

**验证**：tsc 通过；Playwright 在 2560/1440/1024/768 四档视口实测无横向溢出、容器自适应；
`product-detail-ui-check.mjs` **19/0**（含零裂图、无 console 报错）；`header-layout-ui-check.mjs`
**109/0**（--max-w 放宽未破坏顶栏断言）；`check-web-theme.mjs` 55/0、`theme-ui-check.mjs` 13/0。
三端产物已重建部署，8088 线上 CSS 已确认含新类。

### 2026-10-21（五十五）· 暗夜模式修复：导航栏白底 ＋ 文字整体调柔

**① 导航栏白底（用户报障）**：`.app-header` 写死 `background: rgba(255,255,255,0.92)`，
深色档没有任何覆盖 —— 页面其余全黑、唯独顶栏亮白。修法是抽成 `--header-bg` 变量
（浅色 `rgba(255,255,255,.92)` / 深色 `rgba(23,26,33,.86)`），毛玻璃效果两档一致。

**② 文字过亮、对比过强（用户反馈）**：深色档正文 `--text: #e6e8ee` 亮度≈213（几乎贴纯白），
压在 `#0f1115` 上对比度超过 13:1。参照 GitHub Dark / VS Code Dark+ 的正文取值压到 `#c9cdd6`（≈205），
次级/辅助文字与五个 `*-strong` 彩色文字色同步各降一档 —— 对比度回到约 10:1 的舒适区间，层级差不变。

**顺带排查修掉的连坐问题**（都是同一类"深色档盲区"）：
- `.btn-danger` / `.btn-accent-outline` 的 `background: #fff` → `var(--surface)`（暗色下曾是白底按钮）；
- `.pd-price-block`、`.daily-seckill` 渐变里的 `#ffffff` → `var(--surface)`；
- `.hero-pet` 的半透明白底 → `var(--surface)`；
- **Toast 反色 bug**：`--c-1f2430` 等三个色只被 Toast 当背景、文字固定白字，深色档按常规曲线
  把它们映射成了浅色 → 白字压浅底几乎不可读。深色档改为保持深底；
- 弹窗遮罩深色档加深到 62%，弹窗与深底拉得开层次。

**守卫 49 → 55 项**：新增针对性断言（header 背景必须走变量、深色档必须覆盖 `--header-bg`、
Toast 三色在深色档必须保持深底）。守卫当场抓到一次我自己改出来的 `--c-10894c` 重复定义
（新值加在块尾、旧值留在原位，CSS 后者生效但可读性炸了）—— 规则"深色档 Toast 底亮度 < 110"恰好拦住。

验证：`check-web-theme.mjs` **55/0**；`theme-ui-check.mjs`（真实浏览器读计算样式）**13/0**；
暗夜首页截图人工复核（header 与页面融为一体、正文不再刺眼）。三端（商城/后台/商家）共用
`packages/shared/src/styles.css`，一次修复三端生效；`npm run build` + 三端容器已重建。

### 2026-10-21（五十四）· 图片资源优化：压缩落地 ＋ CDN/生命周期/防盗链方案

**已落地：图片压缩（读取时处理，零存储增量）**

- `UploadController` 新增 `imageSuffix` 配置与 `publicUrl()`，上传与「远程导入」两条
  路径返回的图片 URL 统一带数据万象参数：
  `?imageMogr2/auto-orient/thumbnail/1600x1600>/format/webp/quality/82/strip`
- 配置项：`duoduo.uploads.image-suffix` / 环境变量 `UPLOADS_IMAGE_SUFFIX`，
  **留空即完全关闭**，默认空 —— 行为与改造前逐字节一致，可一行回滚。
- **GIF 强制跳过**：`format/webp` 会把动图压成一张静图，表情包会突然不动。
  这条分支有独立断言（`GIF 的 URL 不带处理参数`），不是靠注释保证。
- 参数顺序有讲究：`auto-orient` 必须在 `strip` 之前，否则手机竖拍图会躺倒。

实测（`scripts/check-image-compression.mjs`，走网关真实上传）：

| 用例 | 结果 |
|---|---|
| 商品图 JPEG 20766 B → WebP | 6584 B，**-68.3%**，`Content-Type: image/webp` |
| GIF | URL 无处理参数，动效保留 |
| 反向验证（置空 `UPLOADS_IMAGE_SUFFIX`） | 恰好一条红（`返回 URL 带 imageMogr2 参数`），其余绿 |

端到端 8 通过 / 0 失败。成本：基础图片处理 **≤10TB/月 免费**，本项目量级不产生费用。

**方案文档：`docs/image-delivery-optimization.md`**

CDN 接入 / 生命周期 / 防盗链三项的完整可执行配置，含参数取值理由、成本测算与回滚。
两条必须先知道的结论：

- **CDN 被前置条件卡住**：中国境内加速要求加速域名已备案，项目目前没有自有域名。
- **现有 AK 没有桶配置权限**（`GET ?lifecycle` / `?referer` / `?cors` / `?acl` 全 403），
  这是当初按最小权限刻意设计的，**不应为了配规则给应用密钥加 `cos:*`** ——
  桶级操作请用主账号或独立的运维子账号，且绝不注入容器。

成本对比（假设月图片下行 100GB、50 万请求）：直连 50.5 元 → 仅压缩 15.5 元 →
压缩 + CDN（命中率 85%）约 9.7 元。

新增 `docker-compose.yml` 的 `UPLOADS_IMAGE_SUFFIX` 透传（此前只在 `.env` 里加变量
不会生效，容器收不到）。

### 2026-10-21（五十三）· 个人中心重排 ＋ 头像保存根因 ＋ 撤销注销表单修正

**1. 页面布局重排（只挪卡片位置，不改样式与功能）**

旧版左列只挂一张「基本资料」卡，右列从上到下堆五张卡 —— 左列下半整屏空白，
找齐「账号状态 → 信用分 → 实名 → 快捷入口 → 注销」要跨三屏。重排成三区：

```
第一区 · 身份：        基本资料（左） ｜ 账号状态 ＋ 快捷入口（右）
第二区 · 信用与认证：  信用分（左）    ｜ 实名认证（右）
第三区 · 账号与隐私：  账号注销（全宽，不可逆操作独立成区放最后）
```

**2. 头像不显示 / 保存失败：根因是腾讯云 COS 账户欠费（HTTP 451）**

- 症状：上传报「图片保存失败，请稍后重试」；已存图片全部裂图。
- 实证：容器日志 `COS 写入失败 status=451 msg=Due to your account is arrears`；
  对桶直接探测（连不存在的 key 都返回 451）→ **整个桶被停服**，读和写一起挂。
- "重试"是误导：欠费是账号问题，重试一百次也不会好。修法两层：
  - `CosObjectStorage` 对 451/arrears 单独点名，报「图片存储服务因账户欠费已被停用，
    请在腾讯云控制台充值恢复」；
  - `UploadController` 不再把存储层的可行动消息统一盖成「请稍后重试」
    （该文案只留给"客户端中断读流"这种重试才有意义的场景）。
- **充值恢复后无需任何代码改动**，存量图片自动恢复可读（本轮实测：上传成功 →
  `/api/users/me` 回读 avatar 非空 → 经网关 GET 该 URL 返回 200 + image/png）。

**3. 撤销注销表单：不再被浏览器自动填充**

「预填了当前用户名和密码」不是代码赋值 —— 是这组 input 没声明 `autoComplete`，
Chrome 把登录表单记住的凭据塞了进来。撤销验证和登录是两件事，被填上登录凭据既吓人
又会诱导直接点提交。修正：

- 表单级 `autoComplete="off"` ＋ 密码框 `autoComplete="new-password"`
  （Chrome 对 password 框无视 off，只认 new-password）；
- 撤销区**只在冷静期 / 刚提交后出现**（账号正常时这块表单毫无意义，常驻只会让人困惑）；
- 结果**就地用 Alert 展示**而不是只飘 toast —— 失败的人需要反复看清原因；
- 输入项一律空白手填，校验失败给具体到"下一步该改什么"的提示。

**验证**：新增 e2e `scripts/e2e/me-page-ui-check.mjs` **17/0**（六卡布局用 bounding box
量：基本资料与账号状态同行、信用分与实名同行、注销全宽收尾；头像上传端到端选图 →
落库 → 经网关可显示；撤销区正常账号不渲染）＋ 静态守卫 `scripts/check-me-page.mjs`
**13/0**（autoComplete 两道闸、撤销区条件渲染、COS 欠费点名，反向验证恰好命中对应红项）。
`duoduo-common-web` 改动已 `install` 并重建 fulfilment / user 容器。

---

### 2026-10-21（五十二）· Web 注销入口 ＋ Web 暗夜模式 ＋ 问客服直连 ＋ 支付资质调研

本轮四件事，按用户指定顺序一次做完。

**1. Web 端账号注销入口（与小程序同口径）**

`Me.tsx` 新增 `DeleteAccountPanel`，三态渲染：未申请（风险提示 + 二次确认 Modal）→
有阻塞项（逐条列出订单/售后/退款等未了结业务，不给提交按钮）→ 冷静期（显示执行时间 + 撤销入口）。
提交成功后 `await logout()` —— 服务端此时已停用登录，留在原页只会让下一个请求 401。
文案与小程序端逐条对齐，**二次确认是独立 Modal 而非 `window.confirm`**（全站已无原生弹窗）。

**2. Web 端暗夜模式（三端）**

- `packages/shared/src/store/theme.tsx`：三态 `light | dark | system`，`matchMedia` 跟随系统、
  `localStorage` 记忆、`dataset.theme` + `documentElement.style.colorScheme` 同步（后者让滚动条、
  原生控件、表单控件一起变深，只改 CSS 变量是做不到的）；
- `styles.css` 追加 `html[data-theme='dark']` 覆盖块：**26 个语义变量 + 59 个 `--c-<hex>` 派生变量成对定义**，
  只覆盖颜色，不动尺寸与动效令牌（否则暗色下版式会"变胖"）；
- 深色映射用四档曲线并按亮度压制饱和度 —— 首版直接把颜色反相，`#0b7a6f` 变成荧光青 `#2cf2de`、
  `#191c23`（深底）反过来变成 `#485064`（在深底上看不清），实测不可用后重写；
- 商城 / 后台 / 商家端三个入口都挂 `ThemeProvider`，顶栏加 `ThemeToggle`（三态循环，不是开关）。

**3. 问客服直连（三端）**

- 后端 `SupportController`：`GET /api/socials/support/agent` 下发官方客服 userId，
  `GET /api/socials/support/conversations` 给平台侧接待台留口；**不新造接待系统**，
  直接复用 `/api/socials/messages/**`（未读、非互关限发、上下文隔离全部沿用）；
- 网页 `SupportEntry.tsx` 从「占位弹窗」改成真跳转 `/messages/:peerId`；
  小程序「我的 → 在线客服」同款入口，`profile.js` 的 `goSupport()`；
- ★ **实测抓到一个静默失败**：配置默认值 `710000000000000001` 在本机库中并不存在
  （种子增量 16 从未执行），而私信会话端点对不存在的 peer **返回 200**、昵称退化成
  「用户710000000000000001」—— 端侧会顺利跳进一个查无此人的会话，消息永远没人看。
  修法：`agent()` 回源 `briefs` 校验账号真实存在，不存在回 **503 `SUPPORT_AGENT_NOT_READY`**，
  端侧退回说明弹窗并给 AI 导购出口；同时补跑增量 16（建客服账号 + 32 个自营商品归属回填）。
  反向验证：指向不存在 id 的临时容器恰好返回 503 与预期报文。

**4. 顺带修掉一个真故障：小程序地址页「保存」完全无效**

跑静态自检时抓到 3 条「事件未定义」—— `pages/address/address.js` 里的
`onInput` / `pickLabel` / `save` / `validate` 四个方法**在"坐标系修复"那次改动中被整段吞掉**
（花括号配对替换时把文件尾部一起匹配进去）。症状不是报错，而是**输入框打不进字、点保存没反应**。
已从 `7f7fa8b` 原样补回，并在该处留注释说明教训。

**5. 支付资质调研（A3 补充结论）**

输出 `docs/payment-qualification-alipay-vs-wechat.md`。结论：
**换支付宝解决不了微信小程序收款** —— 一是微信小程序只认 `wx.requestPayment`，
内嵌支付宝违反运营规范（资质再全也绕不过）；二是支付宝虽允许"个人账号 + 无营业执照"开 JSAPI，
但信用卡日限额 1 万，正常经营仍需执照。**办个体工商户是唯一同时解开两家限制的动作。**

---

### 2026-10-20（五十一）· 新增「附近位置」快选入口（wx.choosePoi），三个定位入口并存

**动机**：自动定位有偏差、地图选点要拖地标，而很多场景用户只是想"选旁边那个小区 / 写字楼 / 商圈"。
`wx.choosePoi` 打开微信原生的「所在位置」列表（首项为所在城市，其余为附近 POI），
点一下就带入名称与详细地址，是三个入口里最快的路径。

**接口事实（2026-10-20 核实官方文档）**

- `wx.choosePoi` **未废弃**，官方功能描述："打开 POI 列表选择位置，支持模糊定位（精确到市）和精确定位混选"。
- 它与 `getLocation` / `chooseLocation` 同在 2022-04-18 起实行**准入开通**的接口清单里，
  需在小程序管理后台「开发 - 开发管理 - 接口设置」单独申请；自 2022-07-14 后发布的小程序
  还必须在 `app.json` 的 `requiredPrivateInfos` 中声明，否则调用直接失败。
- 回调 `{ type, city, name, address, latitude, longitude }`，坐标为 gcj02。

**关键设计：并列入口，不替换**

POI 列表只能选到"已存在的地标"，**精确到门牌号仍然要 `chooseLocation` 拖地标**。
所以地址页最终是三个入口并存：

| 入口 | 接口 | 适用 |
| --- | --- | --- |
| 📍 定位当前地址 | `wx.getLocation` + 逆地理 | 想一键带出当前位置 |
| 🗺️ 地图选点 | `wx.chooseLocation` | 需要精确到门牌号（可拖地标） |
| 📋 附近位置 | `wx.choosePoi` | 只想从附近地标里挑一个 |

**两个必须分开处理的回调形态**（写错会把表单写成"空省空区"）

- `type=2`（选了精确位置）：经纬度有效 → 走与地图选点同一条逆地理通道，用户看到的地点名优先；
- `type=1`（只选了"城市"）：**经纬度不可用**，不能拿去反查省市区 → 只填市，
  省与区交给用户手动选，并提示"需要更精确请用地图选点"。

**改动**：`app.json` 的 `requiredPrivateInfos` 加 `choosePoi`；`address.js` 新增
`chooseNearbyPoi()`（同样先授权预检、失败走 `onLocateBlocked('附近位置')`、取消静默）；
`address.wxml` 在省市区字段右侧加第三个按钮。

**测试**：`check-miniprogram-location.mjs` 24 → **32 项 0 失败**（新增声明、
type 分支区分、type=1 不反查、失败走统一收口、未替换地图选点、wxml 三入口、
choosePoi 的 errMsg 分类）。反向验证：把 `res.type === 2` 改成 `=== 1`，
守卫恰好 **1 红 31 绿**，恢复后 32/0 —— 证明断言会红。

**⚠️ 20:00 那条"补漏"是误判，21:00 已回滚并订正（2026-10-20 23:55）**

当时把 `chooseMedia` 写进了 `requiredPrivateInfos`，结果**模拟器直接启动失败**
（`requiredPrivateInfos[3] 字段需为 chooseAddress,chooseLocation,choosePoi,
getFuzzyLocation,getLocation,onLocationChange,startLocationUpdate,
startLocationUpdateBackground`）。事实是：

- `requiredPrivateInfos` **只接受 8 个位置类枚举**，`chooseMedia` 虽是隐私接口
  （相册/摄像头），但**不走这个字段**；
- 它的合规出口是后台《用户隐私保护指引》里声明「摄像头、选中的照片或视频信息」
  —— 这一步已做（见 20:37 的指引终稿），所以**售后上传的合规是齐的，无需再动**。

处理：从 `requiredPrivateInfos` 移除 `chooseMedia`；守卫改为**负向断言**
（枚举之外不得出现任何值 + 显式断言 chooseMedia 不在里面），35 项 0 失败；
反向验证（把 chooseMedia 加回去）恰好 **2 红 33 绿**，恢复后 35/0。
这条教训已入铁律：**字段枚举以报错信息/官方文档为准，"它也是隐私接口"推不出
"它也走这个字段"。**

### 2026-10-20（五十）· 修复：小程序定位与地图选点双双失败（隐私接口未声明）

**现象**：① 点「定位当前地址」提示「定位失败（可能未开启定位服务）」；② 点「地图选点」提示「请检查定位权限」。

**根因（两处同一原因）**

`wx.getLocation` 与 `wx.chooseLocation` 自 2022 年起属于**隐私接口**。`miniprogram/app.json`
里**既没有 `requiredPrivateInfos`，也没有 `permission.scope.userLocation.desc`** ——
未声明时调用会**直接失败**，与用户是否授权、手机是否开定位都无关。
而旧代码把所有非 cancel 的失败都猜成"权限问题"，于是给出了指向错误的提示。

**修复**

1. `app.json` 补声明：
   - `requiredPrivateInfos: ["getLocation", "chooseLocation"]`；
   - `permission["scope.userLocation"].desc`（授权弹窗展示用，键名本身带点，不是嵌套对象）。
2. 新增 `miniprogram/utils/location.js`：
   - `ensureLocationAuth()`：`wx.getSetting` 三态处理（已授权 / 曾拒绝 / 从未询问 → `wx.authorize`）；
   - `classifyLocationError(errMsg)`：**纯函数**分类（`NOT_DECLARED` / `DENIED` / `SYSTEM` /
     `CANCELLED` / `TIMEOUT` / `SERVICE_OFF` / `UNKNOWN`）；
   - `describeLocationError()`：每类给准确文案，且**每一条都写"可直接用下方省市区选择"**；
   - `canOpenSetting()`：只有「拒绝授权 / 系统关闭」才值得弹「去设置」。
3. 地址页：定位与地图选点先预检授权，失败统一走 `onLocateBlocked()` ——
   给准确原因 + 可弹「去设置」（回来后自动重试一次）+ **自动滚到省市区选择**（`.addr-region-field` 锚点）。

**兜底设计（用户问的重点）**

- 省 / 市 / 区是原生 `picker mode="region"`，**不依赖任何定位能力**，任何失败下都能用；
- 失败文案结尾统一是「可直接用下方「省 / 市 / 区」选择，不影响下单」；
- 失败时自动把页面滚到该控件，用户不需要自己找；
- 用户点「去设置」并授权后，自动重试一次，不用再点按钮。

**测试**

- 新增 `scripts/check-miniprogram-location.mjs`：**24 项 0 失败** ——
  校验 app.json 两处声明、页面走预检+统一收口、9 组真实 errMsg 的分类、
  以及"每条文案都提到省市区兜底"。
- 顺带修掉守卫自身一个错误：`permission["scope.userLocation"]` 是**带点的键**，
  不是 `permission.scope.userLocation` 嵌套对象。

**仍需你在微信公众平台完成（真机/正式版必需）**

1. 开发管理 → 接口设置 → 申请开通「获取当前的地理位置」与「打开地图选择位置」；
2. 设置 → 服务内容声明 → 用户隐私保护指引：声明「位置信息」的用途。
未开通时，开发者工具可能可用、真机会失败 —— 这一步无法用代码替代。

### 2026-10-20（四十九）· 修复：小程序「一绑定微信就退出登录」

**现象**：手机号登录后绑定微信，绑定失败的同时被退出登录；注册后再绑定也失败。

**根因（三层叠加，缺一不可）**

1. **配置**：容器内 `WECHAT_SECRET` 与 `WECHAT_APPID` 不匹配。
   实测微信返回 `errcode=40125 invalid appsecret`（用同一对凭据调 `/cgi-bin/token` 也失败）。
   这意味着**任何**真实 `wx.login` 换取都会失败，与用户操作无关。
2. **后端错误码**：`WechatGateway` 把微信侧所有失败一律抛 401 `UNAUTHORIZED`。
   「一次性 code 无效」和「AppSecret 配错」被表达成「你没有登录」。
3. **端侧策略**：小程序 `request.js` 对**任何** 401 都 `clearSession()`，
   于是绑定失败 → 401 → 清令牌 → 用户被踢下线。这就是"一绑定就退出"的直接机制。

**修复**

- 新增两个语义精确的错误码（`duoduo-common/ErrorCode`）：
  - `WECHAT_CODE_INVALID` → **400**（一次性 code 过期/已用）；
  - `WECHAT_CONFIG_ERROR` → **503**（AppSecret/AppID/IP 白名单）。
  ★ 两者都**不是 401**；也刻意避开 422（本项目 422 已被"微信未绑定"占用）。
- `WechatGateway.toException()`：按 errcode/errmsg 分类（纯函数），
  并把 `invalid appsecret` 写进**服务端日志与错误文案**，不再让人猜。
- 小程序 `login.js`：绑定失败的 `tokenGone` 判定只认「401 身份失效」；
  新增 `WECHAT_CODE_INVALID` / `WECHAT_CONFIG_ERROR` 的专属文案，
  并明确告知「你的登录状态不受影响」。
- 新增 `scripts/wechat-config-check.mjs`：3 秒校验 AppID/Secret 是否配对，
  直接给出「去哪重置 secret」的指引（只打印 AppID 后 4 位与密钥长度）。
- `wechat-auth-smoke.ps1` 的形态判定同步升级（原来的断言是"假 code → 401 UNAUTHORIZED"，
  正是本次要废掉的旧约定）：新增 `CODE_INVALID` / `CONFIG_ERROR` 两支，
  断言不变量「微信失败不得返回 401」。

**验证**

| 项 | 结果 |
| --- | --- |
| `wechat-config-check.mjs` | 复现 `40125 invalid appsecret`，退出码 1 |
| bind（有效令牌 + 假 code） | **503 `WECHAT_CONFIG_ERROR`**（修复前是 401） |
| `wechat/token`（假 code） | 503 `WECHAT_CONFIG_ERROR` |
| `WechatGatewayErrorMappingTest` | 5/5（含"任何分类都不得产生 401"） |
| `wechat-auth-smoke.ps1` | **26/0**，形态自动识别为 `CONFIG_ERROR` |
| `wechat-bind-smoke.ps1` | **24/0** |

**仍需你做的一步**：到微信公众平台 → 开发管理 → 开发设置 → **重置 AppSecret**，
把新值写进 `.env` 的 `WECHAT_SECRET`，然后 `docker compose up -d duoduo-user-app`，
再跑 `node scripts/wechat-config-check.mjs` 确认配对成功。在此之前：
真实微信登录会返回 503（文案已明确"服务端配置未就绪"），**手机号登录不受影响、也不会再被踢下线**。

### 2026-10-20（四十八）· 网页端地图选点（高德 JSAPI 2.0）

**需求**：网页端也要像外卖 App 那样——点定位弹地图、拖动地标选位置。

**实现（`apps/mall/src/components/MapPicker.tsx`）**

- 动态注入高德 JSAPI 2.0，**首次打开弹窗才加载**（单例 promise，不进首屏）；
  容器 360px 高（高度为 0 会渲染成空白，高德按容器尺寸初始化）；
  `Marker` `draggable: true`，`dragend` 更新坐标；**点地图任意位置地标也跟随**。
- 确认后按 **gcj02** 调 `/api/geo/regeo`（地图坐标已是 GCJ-02，不能转换），
  回填省市区 + 详细地址，并刷新附近候选。
- 无初始中心点时先自动定位一次（用户点「地图选点」的意图就是"给我定位"）。
- 降级：无 Key / 超时 / 加载失败 → 给出具体原因并置灰「使用这个位置」，
  用户可继续用附近候选或手选下拉。

**Key 的分发方式（比打进产物更稳）**

- 新增 `GET /api/geo/jsapi-config`：登录态下返回 `{configured, key, securityCode}`。
  **Key 不打进前端包**——换 Key 只需改环境变量并重启用户服务，不必重新构建前端；
  未登录拿不到（该路由不在网关白名单，实测 401）。
- 服务端读 `AMAP_JS_KEY` / `AMAP_JS_SECURITY_CODE`（仓库根 `.env`，gitignored）。
- 产物检索验证：`dist` 中三个 Key 命中数均为 **0**（服务端两把 + JS 这一把都不在包里）。

**验证（2026-10-20 实测）**

| 项 | 结果 |
| --- | --- |
| `jsapi-config`（登录） | `configured=true`，key/security 各 32 位 |
| `jsapi-config`（未登录） | HTTP **401** |
| 地图渲染 | `window.AMap.version=2.0`，容器 5 个子节点且生成 `canvas`，无 console 错误 |
| 确认回填 | 点「使用这个位置」→ 广东省 / 深圳市 / 龙岗区 / 花园坪C区一巷8号 |
| e2e | `address-cascade` **30 项 0 失败**（真实地图分支） |

**一处必须记录的自查**：最初我把 Key 写成 31 位（少一个字符），JSAPI 返回
`<AMap JSAPI> Error key!`、REST 返回 `INVALID_USER_KEY`，我据此误判为"Key 未授权域名"。
实际把 32 位的 Key 换上即正常 —— **排障时先校验凭据本身的字面量长度/字符集，
再去怀疑平台配置**。

### 2026-10-20（四十七）· 修复定位偏差 500 米：坐标系用错了；并增加「附近候选 / 地图选点」

**用户反馈**：① 回填地址与实际位置偏差超过 50 米；② 只能点按钮定位，不能像外卖那样在地图上拖地标选点。

**根因（定量实验，不是猜测）**

浏览器 `navigator.geolocation` 返回 **WGS-84**（GPS 原始坐标），高德只认 **GCJ-02**。
把 WGS-84 直接当 GCJ-02 传，地址会整体错位 —— 实测同一地点两种坐标解析结果：

| 地点 | GCJ-02（正确） | WGS-84 直接传（缺陷路径） | 实际相距 |
| --- | --- | --- | --- |
| 深圳「花园坪C区一巷8号」 | 畲吓花园坪小区 / 花园坪C区一巷8号 | 花园坪C区**八巷28-29号** | **589 米** |
| 北京「天安门」 | 天安门 | 西长安街 | **555 米** |

结论：**不是高德接口数据不准，是坐标系用错**。浏览器定位精度（`coords.accuracy`）是次要因素，
量级在几十米，解释不了 500 米级偏移。

**修复**

- 新增 `CoordTransform`（WGS-84→GCJ-02，纯函数）+ **5 项单测**（天安门/深圳还原 <100 米、
  偏移量级 300~800 米、境外原样返回、正反转换往返误差 <1 米）。
- `GET /api/geo/regeo` 增加 `coord=gcj02|wgs84`（默认 gcj02）：
  **网页端传 `wgs84`**（服务端转换后查高德）；**小程序传 `gcj02`**（`wx.getLocation({type:'gcj02'})` 已是 GCJ-02，
  再转会二次偏移）。
- 返回体增加 `coord`（实际使用的坐标系）与 `lng/lat`（转换后的 GCJ-02 值），便于排查。

**交互改进（外卖式挑选）**

- **附近候选（网页 + 小程序）**：逆地理改 `extensions=all`，把周边 POI 作为候选返回，
  后端**按距离升序**排序（高德本身是相关性顺序）、最多 8 条；前端展示「名称 · 距离」，
  点一条即填入详细地址。候选用的是同一次响应，**不额外消耗配额**。
- **小程序地图选点**：新增「🗺️ 地图选点」，调用微信原生 `wx.chooseLocation` ——
  这就是可拖动地标 + 搜索 + 缩放的完整地图选点，**不需要高德 Key**，也不占配额。
- **网页端地图选点尚未实现**：需要高德 **JS API** 类型的 Key（+ 安全密钥），
  当前只有「Web 服务」与「微信小程序」两种，硬接会 401。已登记为后续项。

**测试**

- `CoordTransformTest`：5/5 通过。
- `address-cascade-ui-check` 23 → **26 项**：新增「候选 ≥2 条」「候选按距离升序」「点选候选后详细地址变化」。
- 回归：`address` 65 / `header-layout` 109 / `home` 9 全绿。
- 接口三组对照：GCJ-02+`coord=gcj02` 与 WGS-84+`coord=wgs84` 返回**完全相同**的地址；
  WGS-84+`coord=gcj02`（老路径）返回不同门牌 —— 证明修复生效且缺陷可复现。

### 2026-10-20（四十五）· A1 地址改造：省市区三级联动 + 定位回填（服务端代理地图）

**需求**：新增/编辑收货地址时，省/直辖市/市/区县由手填改为**下拉选择**；详细地址接地图 API
**定位当前地址并回填**；网页商城与微信小程序**两端同步**。

**实现**

- **区划数据内置、按需加载**：`packages/shared/src/data/regions.json`（31 省 / 342 市 / 2978 区县，
  111KB），裁自公开 `pcas-code.json` 并做**直辖市归一**（北京市 / 北京市 / 东城区，不出现「市辖区」）。
  `regions.ts` 用 **动态 import** 单独切块（gzip 26.5KB），只有打开地址弹窗才拉 —— 首屏 JS 仅 +3.9KB。
  这条写成了 e2e 断言：进页面时无 `regions-*.js` 请求，开弹窗后才有。
- **网页端三级联动**（`Addresses.tsx`）：换省会清空市/区、换市清空区（防「浙江省/深圳市/南山区」）；
  历史地址若含数据外名称（如港澳台），自动补进选项，编辑不会被清空。
- **小程序端**：改用微信原生 `picker mode="region"` —— **零数据包**（此前正是因为担心 900KB 数据挤爆
  2MB 主包才退化成手填），原生选择器顺带解决了写法不一致与直辖市重复。
- **定位回填走后端代理**（`GeoController`，`GET /api/geo/regeo`）：Key 只在服务端环境变量
  `AMAP_WEB_KEY`；免跨域、免小程序域名白名单、直辖市与「省直辖县级行政区划」等差异只在一处抹平。
  网关路由显式列出 `/api/geo/**` 且**不进白名单**（按次计费，匿名开放等于把账单交给公网）。
- **降级三条路径**：未配置 Key → 返回 `configured:false`（HTTP 200，不是 5xx）；拒绝授权/超时 →
  提示去手选；上游失败 → 提示手选。**任何一条都不阻断填表**。

**测试**

- 新增 `address-cascade-ui-check.mjs`（22 项）：三字段确是 `<select>`、省级选项 31+1、
  选省后市选项属于该省（含杭州市、不含深圳市）、换省清空市/区、直辖市只列一个市且无「市辖区」、
  区划 chunk 懒加载、定位在无 Key 时给明确提示且表单仍可用、编辑回显。
- `address-ui-check` 适配为下拉填写（`selectOption` 逐级选），**65 项 0 失败**。
- 回归：`header-layout` 109 / `home` 9 全绿；`tsc + vite build` 通过。
- 反向验证：给一个**无效 Key** → HTTP 200 + `configured:true` + 字段全 null（不崩不 500）；
  未登录访问 `/api/geo/regeo` → 401（确认未进白名单）。
- 体积：首屏 JS 235.4→239.3KB；区划 chunk 73.4KB / gzip 26.5KB（按需）。

**高德 Key 配置（2026-10-20 补齐，两个 Key 类型不同、不可互换）**

| Key | 类型（高德控制台） | 落在哪 | 生效范围 | 谁在用 |
| --- | --- | --- | --- | --- |
| `AMAP_WEB_KEY` | **Web 服务** | 仓库根 `.env` → docker-compose → 用户服务环境变量 → `duoduo.geo.amap-key` | **服务端** REST 逆地理（`GET /api/geo/regeo`），即**网页端**「定位当前地址」 | `GeoController` |
| `AMAP_MINI_KEY` | **微信小程序** | `.env` → `scripts/miniprogram-amap-key.mjs` 生成 `miniprogram/config/amap.js`（**该文件不进 Git**） | **小程序端**官方 SDK（`libs/amap-wx.js`）调用逆地理 | `pages/address` |

**为什么不能混用（实测证据，不是推测）**

- Web 服务 Key ↔ REST：`status=1`，返回 浙江省/杭州市/拱墅区 ✅
- **小程序 Key ↔ 裸调 REST**：`status=0`、`info=USERKEY_PLAT_NOMATCH`、`infocode=10009` ❌
  （高德按平台校验 Key，SDK 会自动带 `s=rsx / platform=WXJS / sdkversion / logversion` 才放行）
- 小程序 Key + SDK 标识参数 ↔ REST：`status=1` ✅

**避免硬编码暴露的三条约定**

1. Web 服务 Key **只出现在 `.env`**（gitignored），前端包与小程序包里都没有；
   `GeoController` 从环境变量读取，代码里没有字面量。
2. 小程序 Key **不写进业务代码**：真值只在 `.env`，由
   `node scripts/miniprogram-amap-key.mjs` 生成到 `miniprogram/config/amap.js`（gitignored），
   仓库里只有 `config/amap.example.js` 模板。换 Key 改 `.env` 再跑一次脚本即可。
   注意：小程序包（`.wxapkg`）可被解包，**Key 必然随包下发**——这一层靠高德控制台的
   「微信小程序」绑定 AppID 限制来源，不能靠"藏"。
3. 小程序端优先用 SDK（小程序 Key），**失败自动回落**后端代理（Web 服务 Key）——
   即使忘了在微信公众平台把 `https://restapi.amap.com` 加入 request 合法域名，定位仍可用。

**小程序端需要你在微信公众平台做的一件事**：开发管理 → 服务器域名 → request 合法域名
加入 `https://restapi.amap.com`（否则 SDK 那条通道会被拦，自动回落后端）。

### 2026-10-20（四十四）· 修复：头像面板缩放动画从未生效（条件渲染导致无起始样式）

**用户反馈**：鼠标移到头像上，面板瞬间就是完整大小，没有从小到大的缩放过渡。

**根因（实测确认，不是"幅度太小"）**

1. 面板是**条件渲染**（`menuState !== 'closed' ? <div> : null`），而触发展开时
   `setMenuState('open')` 与挂载发生在**同一次提交**里 —— 元素插入 DOM 的第一帧
   就已经带着 `.is-open`。浏览器拿不到「起始计算样式」，`transition` 不触发、
   `getAnimations()` 为空，CSS 里写的 `scale(0.88)` 从来没被渲染过一次。
   诊断数据：`+60ms` 时面板已存在且 `opacity=1 / transform=none / anims=[]`。
2. 附带问题：`'closing'` 状态下仍然挂着 `is-open`（类名判据是 `!== 'closed'`），
   于是"移开"后先干等 200ms 才摘类 —— 淡出被整体推迟，观感是"先愣一下再消失"。

**修复**

- **面板常驻 DOM**，只由宿主上的 `is-open` 类切换显隐。closed 态真实存在
  （`opacity:0 + visibility:hidden + pointer-events:none`，读屏器与 Tab 都进不去），
  类切换才有前后两个计算样式可插值；收起时 `visibility` 靠
  `transition: ... visibility 0s linear var(--dur-base)` 延迟到淡出结束才隐藏。
- **三态机简化为布尔**：动画交给 CSS 后不再需要"关闭定时器"，摘类即刻开始淡出；
  唯一保留的定时器是进入方向的 80ms 意图延迟（鼠标扫过顶栏不误弹）。
- **参数按录屏校准**：用 ffmpeg 抽 60fps 帧、以悬停前画面为基线做差分反推
  B 站面板：约 150–200ms 的淡入 + 轻微放大、无位移、**无过冲**。
  故起始态 `scale(0.88)`、时长 `--dur-base`（200ms）、曲线 `--ease-out`
  （原来的 `--ease-spring` 1.32 过冲会在末尾顶一下，与录屏不符）。

**e2e（`header-layout-ui-check` 103 → 109 项）**

- 新增逐帧证据：悬停前注入 rAF 采样器，连续记录 900ms 内面板的 computed
  `scale` / `opacity`，断言 ① 存在 ≥3 个缩放中间帧、② 最小 scale ≤0.92
  （抓"0.97 那种看不出来的幅度"）、③ 透明度有中间值、④ 缩放单调递增且终止于 1。
  只断言"最终已展开"抓不到这类假象 —— 这正是上一轮 100 项全绿却仍然坏掉的原因。
- 常驻 DOM 后 `count()` 恒为 1，所有「已关闭」判据改为**可见性 + 可交互性**
  （`visibility:hidden` 或 `opacity:0`，且 `pointer-events:none`），
  否则整套断言会集体假绿；同步修改 `address-ui-check` 的同类判据。
- **反向验证**：容器内把产物 CSS 的 `transform:scale(.88)` 改成 `scale(1)`
  → 恰好 4 条红、其余 105 条绿、总数仍 109（不多不少），`docker cp` 恢复后
  哈希一致、复跑 109/0。

**回归**：`address` 65 / `my-listings` 52 / `notice-chat-follow` 50 /
`admin-entry` 74 / `motion` 22 / `home` 9 全 EXIT=0。

### 2026-10-20（四十三）· 头像面板缩小 ＋ B 站式「从小长大」缩放动画

**需求**（用户截图反馈）：面板尺寸过大、遮挡页面内容，整体缩小到更合适的比例；
展开改成 B 站那样**从小到大平滑长出来**，不要硬弹。

**实现（`packages/shared/src/styles.css` + `Layout.tsx` 头部结构）**

- **尺寸**：面板宽 300→**256px**；头部由竖排（上头像下名字，占 ~110px 高）改为**横排**
  （头像 64→44px 在左、昵称/身份在右，B 站同构），省约 40px；条目 padding 9→6px、
  字号 14→13px、图标 20→18px、分组 padding 收紧。整体高度约省 1/4，遮挡面积明显下降。
- **动画**：起始态 `scale(0.97)`（肉眼几乎看不出）改为 **`scale(0.88)` + 去掉位移**、
  `transform-origin: top right` 不变 —— 展开即从右上角**纯缩放长出来**（B 站手法）；
  展开曲线换 `--ease-spring`（轻微过冲，"活"感，**四十四轮按录屏改回 `--ease-out`**：
  B 站实测无过冲）；收起仍对称收回缩小态再淡出；
  时长保持 `--dur-base`（200ms，在 150–250ms 区间内）。
- **e2e**：`header-layout-ui-check` ②b 段改/增 3 项断言 —— 展开完成 transform 复位、
  展开过渡引用 `--ease-spring`、收起态样式表规则含 `scale(0.88)`。曲线与缩小态读
  **样式表规则**而非 computed style：computed 的 cubic-bezier 自带逗号、按逗号解析会切碎；
  且面板关闭后整个卸载（条件渲染），根本没有计算样式可读。另修一个测试盲区：
  Esc 收起后鼠标若一直停在按钮上，`mouseenter` 不重发、面板不会自己再弹出来 ——
  重新展开必须先移开再移回（真人本来就是这么操作的）。全套件 100 → **103 项全过**。
- **回归**：`my-listings` 52 / `notice-chat-follow` 50 / `admin-entry` 74 / `motion` 22 全 EXIT=0。

### 2026-10-20（四十二）· 顶栏头像改版：B 站式悬浮个人面板

**需求**：右上角只显示头像（不显示用户名），悬浮/点击弹出个人面板，移出延迟收起；
面板与头像之间的间隙可穿透（不闪烁不误关）；弹出/收起 150–250ms 自然缓动，
快速反复进出不堆积动画队列；面板内容与布局参照 B 站；沿用现有技术栈不引新依赖。

**实现（`apps/mall/src/components/Layout.tsx` + `packages/shared/src/styles.css`）**

- **触发区**：`button.icon-btn.avatar-btn`，42px 正圆、只放头像；昵称从按钮移除，
  但保留在 `aria-label`（读屏器与 `getByRole('button', { name: /昵称/ })` 定位不回退）。
- **三态状态机** `closed / open / closing`（布尔做不到「收起也有动画」）：
  悬浮进入 80ms 意图延迟后展开（扫过顶栏不误弹）；离开转 `closing` 走 200ms 淡出再卸载，
  期间鼠标回来会取消收起；定时器全挂 ref。点击＝**打开（幂等）而非开关** ——
  自动化点击与真人「悬停后点击」都会先触发悬浮展开，此刻再 toggle 会立即把面板关掉。
- **桥接**：`.user-entry::after` 在按钮与面板之间铺一块 12px 透明判定区（左右各外扩 8px），
  鼠标穿过 10px 视觉间隙不会把面板收走。
- **动画**：`opacity + translateY(8px) scale(0.97) + visibility` 过渡，时长/缓动全部引用
  动效令牌（`--dur-base` + `--ease-out`）；收起靠 `visibility` 延迟生效实现「淡出完才不可交互」
  （`display:none` 没有退出动画，单用 `opacity:0` 关掉后仍可聚焦、读屏器仍可读）。
- **面板内容**（B 站同构）：大头像 64px + 昵称 + 身份/手机掩码 → 四组条目
  （个人中心/订单/售后/券包 ｜ 宠物/地址/闲置 ｜ 分享圈/我的分享/关注/通知/私信 ｜
  角色工作台）→ 退出登录；条目为「图标 + 文案 + 未读角标 + 右侧箭头」，
  通知/私信角标沿用 `.badge-inline`。
- **定位与层级**：面板锚宿主右缘向左展开（贴屏幕右边不溢出）+ `max-width` 兜底窄视口；
  z-index 90（高于顶栏 60，低于弹窗 150 / Toast 200）。
- **刻意不加** `role="menu"/"menuitem"`：menuitem 会覆盖 `<a>` 原生 link 角色，
  既有 `getByRole('link', …)` 全部失效；menu 容器混用普通链接也是无效 ARIA。

**e2e**：`header-layout-ui-check` 新增 ⑤b 段 12 项（触发区圆形且无昵称文字 / 悬停展开 /
**桥接穿越不误关** / 内容完整 / 移出先淡出再卸载 / 快速反复进出 4 次终态正确），
原「昵称不折行」改为「头像按钮为正圆」；全套件 88 → **100 项全过**。
适配三处旧断言：`admin-entry` 的「页面出现昵称」→「头像按钮 aria-label 出现昵称」；
`my-listings` 双击关闭 → Esc；`notice-chat-follow` 的 `/私信（\d+）/` 正则 ——
该正则在 2026-10-18 括号文本改成角标组件时就该改，一直漏改（近期未跑此套件所以未暴露），
改为结构化断言「私信行内 .badge-inline 数字 = 未读数」。

**回归**：`header-layout` 100/0、`admin-entry` 74 项、`home` / `address` / `my-listings` /
`live-refresh` / `notice-chat-follow` / `motion` / `seckill` 97 / `pagination-search` 全 EXIT=0。
`share-circle` 的「删除后列表消失」为**基线已复现的历史失败**（与本轮无关，已记待查）。

### 2026-10-20（四十一）· 三端动效与渲染性能统一（令牌化 + 微交互 + 降级守卫 + 懒加载）

**需求**：对商店端 / 商家端 / 平台运营端做系统评估，统一规划过渡动画、微交互与渲染效果，
给出并落地性能改进。

**评估结论（改造前的实测基线）**

- 全站共用一份 2432 行样式表，只有 14 处 `transition`、6 处 `@keyframes`；
  时长散落着 `0.15s / 0.16s / 0.18s / 0.2s` 四种写法 —— 同一类交互在不同页面快慢不一；
- **377 行是 `transition: all 0.15s`**（`.btn`）：`all` 会把 width/height/top 这类 layout 属性
  也拖进过渡，浏览器每帧都要重排，无法走合成层，是「看起来卡」的头号元凶；
- `prefers-reduced-motion` 全仓只有 **1 处**（只覆盖秒杀小红点）：系统开了「减少动态效果」的用户
  要承受全站 99% 的动画；
- 27 个 `<img>` 只有 1 处 `loading="lazy"`；商城端 246KB 单入口无路由分包。

**落地（全部在 `packages/shared/src/styles.css` + 三端入口组件）**

1. **动效令牌**：`--dur-instant/fast/base/slow/enter`（80/150/200/400/240ms 五档）、
   `--ease-out/in-out/spring` 三条曲线、按「什么东西在变」划分的组合令牌
   `--t-colors / --t-outline / --t-chip / --t-field / --t-lift / --t-button / --t-fade / --t-progress / --t-press`。
   14 处裸 transition 全部改为令牌引用；**377 行的 `transition: all` 一并消灭**。
2. **微交互原语**：按钮/图标按钮/分页/筛选 chip 的**按压回弹**（80ms，超了会觉得黏）；
   纯色块按钮按压同步压暗（`filter: brightness`）；商品卡悬浮**图片缓推**；
   表格行悬浮底色加过渡；新增 `.card-hover`（首页分类卡用上，原先点了没有任何反馈）；
   **路由切换入场**（`.route-enter`，三端都接）与**列表错峰入场**（`.stagger`，商品/闲置网格用上，
   前 8 张每张差 28ms、第 9 张起封顶 224ms —— 不封顶的话 40 条列表最后一张要等 1.2s）。
3. **两个必须写下来的坑**：
   - `.route-enter` 只能包页面内容，**不能包 `.app-shell` 或带 sticky 顶栏的容器** ——
     祖先一旦有 transform，`position: sticky` 退化成相对定位，顶栏跟着滚走；
   - 入场动画填充模式必须 `backwards` 不能 `both`：`both` 会在动画结束后继续以 100% 关键帧
     参与层叠，把 transform 永久钉死，元素此后的 `:hover` 位移全部失效。
4. **降级守卫**：全局 `@media (prefers-reduced-motion: reduce)` 收口 —— 动画/过渡压到 0.01ms
   （不是 `none`：`none` 会让依赖 `animationend` 的逻辑收不到事件），并把位移类显式归零。
5. **渲染性能**：25 处 `<img>` 补 `loading="lazy"` + `decoding="async"`（顶栏头像与详情页主图
   保持 eager）；商品卡加 `contain: layout style`（刻意不用 `contain: paint` —— 部分浏览器会连
   自身 box-shadow 一起裁掉）；`will-change` 全文 0 处（常驻提层的显存开销比收益大）；
   长列表预留 `.virt-list`（`content-visibility: auto` + 占位高度）。
6. **体积 A/B（同一套构建，vite 口径）**：mall JS 233.68 → 234.37 KB、CSS 32.57 → 35.88 KB；
   admin JS 123.11 → 123.28 KB；merchant JS 85.03 → 85.20 KB。CSS +3.3KB 是令牌与原语的代价，
   JS 只 +0.2~0.7KB。

**新增永久回归 `scripts/e2e/motion-ui-check.mjs`（22 项断言，反向验证过）**

把「手感」钉成计算样式断言：17 个令牌齐全、按钮/输入框/商品卡引用令牌、
**全站 `transition: all` 数量为 0**、悬浮抬升与图片缓推真的发生、路由入场填充模式是 backwards、
图片懒加载占比、卡片 containment、**减少动态效果下过渡时长 < 5ms 且悬浮位移被归零**。
反向验证：故意把容器里 `--dur-fast` 改成 0.19s → 恰好「时长档位」1 项红、其余 21 项绿、总数不减。

**顺手修的两个历史遗留（与本轮无关，但在跑回归时暴露）**

- `admin-entry-ui-check.mjs` 挂在「运营账号」搜索：上一轮全站搜索统一后占位符已改成
  「搜手机号或昵称」且**没有「查询」按钮**（输入即搜 + 350ms 防抖），脚本还在点旧按钮 → 已适配；
- 同一脚本的夹具昵称是固定字符串，每跑一次就多一批同名账号：搜索结果恒多行、
  ADMIN 越攒越多导致「并发前恰有 2 名 ADMIN」恒不成立、`最后一个 ADMIN 不可降级` 分支走不到。
  改为昵称带跑批唯一标记 + 启动时自愈清理历史遗留的「账号管理-管理员」夹具（本次清了 4 个）。

**回归**：三端 `tsc` + `vite build` 全绿 → 三端镜像重建并重启 healthy →
`motion-ui-check` 22/0、`home` / `header-layout` / `pagination-search` / `seckill(97)` /
`seckill-price` / `product-detail` / `my-listings` / `aftersale` / `review` / `seller` /
`admin-charts` / `live-refresh` / `admin-entry(74)` 全部 EXIT=0 → 静态守卫 13 项全绿 →
1440/1180/900 三视口截图复核布局无破坏。
两个与本轮无关的历史失败（基线复现确认）：`share-circle`（删除后列表消失）、
`product-audit`（发布后未出现「正在等待平台审核」）—— 已记录待查；`coupon` 缺夹具前置
（需先跑 `coupon-ui-fixture.ps1 -Action create`）。

### 2026-10-19（四十）· 秒杀价规则（必须低于原价、且大于 0.01 元）＋ 修掉一处双重换算

**需求**：秒杀价必须**严格低于**该商品原售价（原价 159 元时不得 ≥159），且必须**大于 0.01 元**；
在保存/提交时生效，并对不合规价格给出明确提示。

**后端（`SeckillService.createActivity`，绕过页面也拦得住）**
- `秒杀价 > 0.01 元`：`seckillPrice <= 1`（分）即拒，提示 `秒杀价必须大于 0.01 元（SKU xxx 当前填的是 0.01 元）`。
- `秒杀价 < 原售价`：**严格小于**，等于也拒，提示 `秒杀价（129 元）必须低于原售价（129 元），SKU 2001`。
- 原售价取**将要落库的那个值**（请求带 `originalPrice` 用它，否则用商品服务当前售价）——
  校验与存储必须是同一个数字，否则「校验用的原价」和「页面显示的原价」可以不是一回事。
- 新增 `yuan(long cents)` 只用于报错消息：库里是分，直接报 `12600` 会被读成「一万二千六百元」。

**前端（`apps/admin` 秒杀页）**
- 「秒杀价」输入框 `min=0.02`，hint 随原价显示 `必须低于原价 ¥129.00，且大于 ¥0.01`。
- 保存前逐行校验并点名第几个商品 / SKU，金额一律换成分做整数比较（避免浮点 0.1+0.2 类误差）。
- **选商品回填的默认值夹在合法区间**：现价八折 → `min(max(八折, 2), 原价-1)` 分，运营不改也能存。
- 修 UX 缺陷：校验错误原先渲染在**页面顶部**，被模态遮罩挡着 ⇒ 运营点「创建」只看到「没反应」。
  现已移到**弹窗内**（创建按钮上方）。

**同时修掉一个同日发现的真 bug：时间被减了两次 8 小时**
- 后台页提交前已把北京时间换算成 UTC 裸串，而服务端自 `f0e835c` 起把**无时区串按北京时间**解析
  ⇒ 同一份输入被减两次 8 小时：运营填 20:00 开抢，库里存的是 12:00（北京）。
- 修法：前端改为提交**带 `+08:00` 的 ISO 串**（`2026-10-19T20:00:00+08:00`），走服务端「有时区按绝对时刻」分支，
  只换算一次。语义写进报文，不再依赖「谁去猜这个裸串是哪个时区」。

**验证**
- `seckill-smoke.ps1` 新增 §5.6b 七条（= 原价 400 / > 原价 400 / = 0.01 元 400 / 正对照 0.02 元 200 /
  两条消息点名 / 被拒请求零落库），全套 **89 通过 / 0 失败**。
- 新增 `scripts/e2e/seckill-price-ui-check.mjs`，真实浏览器 **18/18**：默认值落在合法区间、
  两条提示**出现在弹窗内**、合法价可创建、**北京时间 20:00 → 库里 12:00 UTC**（只换算一次）、
  落库价格＝元×100、收尾自证 0 残留。
- **反向验证**：临时摘掉后端两条校验 → 同样的「= 原价」请求由 400 变为 **200 放行** → 恢复后重回 400。
- 兼容性：冒烟里原有一处「秒杀价 250 元」（高于该 SKU 现价 129 元）本身即是新规要禁的配置，
  已改为 120 元（仍高于券门槛），券叠加断言随之改算。

### 2026-10-19（三十九）· 实时刷新补全到订单/售后 ＋ 后端校验的接口级用例

接（三十八）收尾时列出的两个「未完成项」，本轮补完。

**补 2：把三层刷新（广播 / 回前台 / 30s 轮询）铺到订单与售后**

- `shared/components/hooks.ts` 新增 `useLiveRefresh(refresh, { intervalMs, enabled, poll })`：
  订阅 `dataBus` + `visibilitychange`（只在切回可见时拉）+ 仅在页面可见时跑的兜底轮询；
  回调用 `ref` 持有，调用方每次渲染新建的箭头函数不会重复订阅。
- `useAsync` 新增 **`reloadSilently()`**：不置 `loading`、不清 `error`，页面不闪骨架屏。
  同时修正 `reload()`：会**清掉**可能残留的「下一次静默」标记 —— 否则「静默刷新刚入队、
  用户紧接着点重试」时，那次重试会被当成静默刷新，用户看不到任何加载反馈。
- 接入 4 个页面：`OrderDetail`（订单 + 结算两路同时静默刷新）、`Orders`、`AfterSales`、
  `AfterSaleDetail`（`enabled: id.length > 0`）。
- 顺手修掉一个被轮询放大的老问题：未支付/已关闭订单**没有**资金托管记录，详情页却照样请求
  `/settlement`，服务端只能回 404；加了轮询之后会变成**每 30 秒一次 404**。现在按状态决定是否发这个请求。

**补 1：后端硬校验的接口级用例（不只靠前端）**

用临时 OPERATOR 账号直打接口（绕过页面）：

| 用例 | 结果 |
| --- | --- |
| 配额 9999 > 可售 193 | **400** `活动配额（9999）不能超过 SKU 2003 的当前可售库存（193）` |
| 限购 5 > 配额 1 | **400** `每人限购（5）不能超过活动配额（1）` |
| 限购 11 > 配额 5 | **400** `每人限购（11）不能超过活动配额（5）` |
| 正对照：配额 1 / 限购 1 | **200**，`activityId` 正常返回，落库 `totalStock=1, perLimit=1` |

三条被拒的请求**一行都没落库**（活动与 SKU 明细均为 0），夹具（自测活动 / 临时账号 / 令牌）已清理并自证 0 残留。

**验证**：新增 `scripts/e2e/live-refresh-ui-check.mjs`，真实浏览器 **21/21** 通过，并做了**反向验证**
（临时摘掉列表页的 `useLiveRefresh` → 恰好「切回标签页」「30s 轮询」两条变红、其余 19 条全绿 → 恢复后重回 21/21）。
反向验证过程中揪出一条**假绿**：状态筛选栏里本来就有「已关闭」这个标签页文案，用整页文本判状态恒为真 ——
断言已改为**只读该订单卡片上的状态标签**。另跑秒杀 UI **65/65**、售后全链路、两个静态守卫，均通过。

### 2026-10-19（三十八）· 秒杀五问题修复 ＋ 全站「手动刷新」问题统一治理

**统一方案（先说结论）**：新增全站数据变更广播 `shared/lib/dataBus.ts` ——
**任何会改变他人正在展示的数据的写操作成功后 `notifyDataChanged()` 喊一嗓子**，
关心实时性的数据源各自订阅、听到就重拉。只传「该刷新了」的信号不带数据，
且各订阅方本就有轮询兜底，广播只是把延迟从 60s 降到 0。已订阅：**秒杀中心**（Provider）、
**商品详情页**（库存/可售状态）。已接入的写点：**下单（Checkout）、支付成功（沙箱与扫码轮询两处）、
买家取消订单（订单详情 + 订单列表）、秒杀抢购成功**。

**逐条修复**

1. **取消订单后仍显示「已抢完」**：根因＝秒杀数据只有 60s 轮询 + 回前台刷新，
   取消订单这个写点不触发任何刷新。修复＝见上（取消 → 广播 → 秒杀中心立刻重拉）。
2. **秒杀抢完后原价购买路径消失**：根因＝详情页只要 SKU 在秒杀就**整体切换**到秒杀购买
   （隐藏数量/加购），配额抢完后只剩一个灰「已抢完」，而秒杀配额与 SKU 库存是两套口径
   —— 剩余 100+ 件库存只能干看着。修复＝新增 `seckillOver`（配额抢完 **或** 用户被限购拦下）
   状态：秒杀条保留并提示「可按原价正常购买 ↓」，价格与加购/立即购买**退回原价口径**；
   规格按钮在配额抢完后不再挂秒杀价标签。
3. **新建活动默认时间随机**：根因＝默认值是「UTC 现在 +5 分钟」，且输入框按 UTC 解读
   （填 20:00 实际 12:00 开抢）。修复＝后台时间**全部改为北京时间口径**：输入框、列表、编辑
   均显示北京时间，提交时自动 ±8h 换算成服务端 UTC；新建默认值＝**北京时间当天 00:00–23:59**，
   仍可自由修改。
4. **配额/限购无校验**：前端＝「选商品回填」带出该 SKU **当前可售库存**并在列表中展示；
   活动配额输入框 `max=库存` 并提示上限，保存前逐行校验「配额 ≤ 库存、1 ≤ 限购 ≤ 配额」，
   报错点名第几个商品与两个数字；后端 `SeckillService.createActivity` **同规则硬校验**
   （超库存 / 限购超配额直接 400），前端绕过也建不出来。
5. **回填价格 15800 而非 158**：根因＝`sku.price` 是**分**（服务端金额一律数字分），
   回填时直接塞进按「元」录入的输入框，提交时再 ×100 ⇒ 百倍放大。修复＝回填时 ÷100
   （原价 15800 分 → 158 元，秒杀价按八折折算 126.4 元）。

**验证**：浏览器实测 **15/15**（抢完退回原价购买 4 项 / 抢购→取消→不刷新页面「马上抢」与余量即时恢复 3 项 /
后台默认时间 3 项 / 回填单位与库存展示 3 项 / 配额超库存拦截与合法创建 2 项）；
trade 编译通过（新增校验与既有冒烟夹具兼容：夹具配额 1~2 ≤ 库存 195）；
三端 typecheck + 构建 + 镜像重建；前端 3 个静态守卫全绿。
自测夹具（临时运营账号 / 自测活动 / 自测地址）已全部清理回库。

### 2026-10-19（三十七）· 头像交互打磨：单入口 + 右上角即时同步

紧接（三十六）的用户反馈，四条诉求全部落地：

1. **右上角即时同步**：顶栏此前根本不显示头像（只有 👤 占位符）—— 这就是「上传后右上角不变」的
   根因。现改为有头像时渲染 `user.avatar` 圆形小图；个人中心选完图**立即落库**（不再要求点「保存资料」）
   并 `refreshMe()`，顶栏随 AuthProvider 重渲染，**无需刷新页面**。点 × 清除同样即时生效并恢复 👤。
2. **移除多余按钮**：`ImageUploader` 单图字段（max=1）已有图时**不再渲染「上传」方块** ——
   缩略图本身就是编辑入口，点击即重选（文件选择框已挪到组件根节点，保证此时仍打得开）。
3. **悬停提示**：单图字段悬停浮现半透明遮罩文案（头像传「编辑头像」，其余默认「更换图片」）。
4. **× = 清除**：清除后该位置变回「上传头像」入口，语义与视觉一致。

顺带修一个连带缺陷：个人中心的表单回填 effect 原来依赖 `[user]`，而 `refreshMe()` 每次都换对象引用 ——
头像自动保存后一次刷新就会把**用户正在编辑的昵称冲掉**。已改为只在「换了账号」时回填。

**验证**：浏览器实测 **9/9**（单入口 / 悬停提示 / 点击头像重选 / 上传后右上角即时出现且与编辑区同图 /
× 清除后右上角恢复默认且入口变回「上传头像」/ 刷新后清除已落库 / 地址入口同样即时同步）。
该交互对全部 max=1 字段（品牌 Logo、商品主图）一致生效。

### 2026-10-19（三十六）· 图片上传全面重构：所有图片字段统一「本地上传 + URL 上传」

**技术栈先确认**：前端是 **React 18 + Vite + TypeScript + react-router-dom + 自研组件库**
（`packages/shared/src/components/ui.tsx`），**不是 Vue，也没有 Element Plus / Element UI / antd / Arco**。
因此不存在「第三方上传组件可直接复用」这条路 —— 唯一成熟的实现是评价晒图那一套（本地 `POST /api/uploads`
＋ 地址 `POST /api/uploads/from-url`），但它当时写在 `ReviewNew.tsx` 里，是**页面私有逻辑**，别处拿不到。

**做法**：把这套行为**抽成一个共用组件** `packages/shared/src/components/ImageUploader.tsx`，
再把原来只有「图片地址输入框」的字段全部换过去 —— 不是复制一份，而是**只剩一份实现**。

- 组件能力：缩略图列表 / 本地多选 / 地址转存 / 删除 / 失败点名（文件名 + 原因）/ 张数上限 /
  圆形（头像）与方形 / `onBusyChange`（父级用它禁用提交）/ `hint` 与 `addLabel`。
- 两条落地口径：本地文件 → `/api/uploads`；外部 http(s) 地址 → `/api/uploads/from-url`
  服务端下载转存（**不用外链**，避免失效、防盗链、混合内容）；以 `/` 开头的本站相对路径**原样采纳**
  （商家历史数据里是 `/img/products/xxx.svg`，强行转存必失败）。
- **单图字段语义修正**：`max=1`（头像 / 封面 / Logo）时，选新图是**替换**而不是「再加一张」。
  原来按上限截断会出现「已有头像时点选图毫无反应」，用户只能先点 × 删掉才换得了 —— 这一步没人猜得到。

**覆盖的功能点（本次改动 7 处）**

| # | 位置 | 改前 | 改后 |
|---|---|---|---|
| 1 | 商城 · 个人资料头像 `Me.tsx` | 只有一个地址输入框 | 圆形上传器 + 地址入口，`max=1` 替换语义 |
| 2 | 商城 · 售后举证图 `AfterSaleNew.tsx` | 多行文本，一行一个地址 | 多图上传器（9 张），提交按钮随上传禁用 |
| 3 | 商城 · 闲置发布 `ListingForm.tsx` | 只有本地选图，无地址入口 | 统一上传器（本地 + 地址） |
| 4 | 商城 · 评价晒图 `ReviewNew.tsx` | 页面内自写一套（150 行） | 改用共用组件，页面内实现删除 |
| 5 | 平台后台 · 品牌 Logo `Brands.tsx` | 地址输入框 | 上传器 + 地址入口，保存按钮随上传禁用 |
| 6 | 商家端 · 发布商品 `ProductPublishModal.tsx` | 主图地址框 + 详情图「每行一个地址」 | 两个上传器（主图 1 张 / 详情图 9 张） |
| 7 | 商家端 · 编辑商品 `Products.tsx` | 同上 | 同上，`imagesText` 文本字段改为 `images: string[]` |

**顺带修掉两个只在真机上才看得见的坑**

- **两个上传器共用一个 `uploading` 布尔量**会被「一个传完、另一个还在传」覆盖 ⇒ 提交按钮提前解禁、
  存下缺图的版本。改为按字段名分记（`{ main, detail }`，任一在传即忙）。
- 评价页原来按草稿各自记 `uploading`，重构后由 `onBusyChange` 回写同一字段，提交条件保持
  「所有草稿都评过分且都不在上传」。

**验证**

- 三端 `typecheck` 通过；`npm run build` 通过；三端镜像重建 + 容器 `--force-recreate`。
- 浏览器实测 **15/15**：商城头像（组件渲染 / 两个入口 / 本地选图出预览 / 地址写本站相对路径 /
  保存后刷新仍回显 —— 证明真的落库）、闲置发布、后台品牌 Logo（新建弹窗 + 本地上传出预览）、
  商家发布弹窗（两个上传器 + 主图双入口），全程无 JS 运行时错误（登录前的 `401` 身份探测不计）。
- 静态守卫：`check-frontend-imports`（518 条）、`check-bundle-isolation`（三端产物互不包含）、
  `check-product-link-spu`（92 文件 / 11 处）、`check-chart-theme-contract`（13 项）全绿。
- 未做 UI 实测（本次无可用夹具，仅类型检查 + 构建覆盖）：售后举证图（需已签收订单）、
  商家编辑商品弹窗（需卖家名下有商品）。

### 2026-10-19（三十五）· 修复：秒杀活动页封面图与首页「每日秒杀」不一致

**现象**：同一商品（spuId 1001），秒杀活动页显示一张图、首页「每日秒杀」显示另一张图。

**根因**：占位图不是真实文件（`/img/sku/2001.svg` 在仓库里不存在），由前端**按 id 做种子从 12 张本地图池选图**
（`seedOf(spuId || skuId)`）。秒杀列表页把 `skuId`（2001）当 `spuId` 位传给了 `skuImage`，
首页栏目传的是真 `spuId`（1001）—— 种子不同 ⇒ `seedOf('2001')`→p09、`seedOf('1001')`→p05，
同一商品在两个入口选到了图池里不同的图。数据本身没有不一致，是**选图参数不一致**。

**修复**：`Seckill.tsx` 改传 `sku.spuId ?? sku.skuId`；小程序同型问题一并修
（`miniprogram/pages/seckill/seckill.js`、`pages/index/index.js` 原来传 `null` → 回退 skuId）。
其余调用点经排查全部用 spuId，无同类问题。

**验证**：浏览器实测首页「每日秒杀」图 = 秒杀活动页图 = 商品详情页主图 = `/img/products/p05.jpg`，三处完全一致。

### 2026-10-19（三十四）· 全站秒杀标识与价格 ＋ 首页「每日秒杀」栏目

**1. 全站商品卡与详情页统一挂秒杀标识、换秒杀价**
- 新增 `SeckillProvider`（`shared/store/seckill.tsx`）：全站**唯一**秒杀数据源 —— 一次拉 `/api/seckill/activities`，
  按 SPU 建索引（同 SPU 多 SKU 取最低价、多价带「起」），60s 轮询 + 页面回前台刷新，失败保旧值不报错。
- 统一组件（`shared/components/seckill.tsx`）：`SeckillTag`（进行中红橙实心 / 即将开始琥珀）、
  `SeckillPrice`（秒杀价醒目 + **原价划线并标注「原价」二字**）、`SeckillCountdown`（tabular-nums 每秒走字，
  到点自动触发全站刷新换相位）、`SeckillProgress`（已抢进度 + 仅剩 N 件）。
- 接入位置：**商品卡** `ProductCardView`（首页推荐/新品/物种精选、搜索结果共用）、**商品详情页**
  （秒杀条 + 大号秒杀价 + 规格按钮标价换秒杀价 + 默认选中秒杀规格）、**秒杀列表页**（改用同一组件）、
  **AI 导购推荐卡**、**分享圈商品卡**。admin/merchant 未挂 Provider 时组件安全降级（不挂标、不换价）。
- 详情页下单链路跟着改：秒杀规格显示「马上抢」（走 `seckillApi.place`，需登录 + 默认地址），
  **隐藏加购/立即购买与数量选择**（秒杀按件抢、接口不收数量）—— 显示秒杀价却按原价成交比不展示更严重。
  商品库存与秒杀配额是两套口径：秒杀规格不因 `sellable=false` 被禁选。
- 新增 `parseServerTime`/`formatServerTime`：服务端 `LocalDateTime` 无时区输出按 **UTC** 解析
  （浏览器默认按本地时区 → 一律早 8 小时，对倒计时是功能性错误）；秒杀各页时间展示同步修正。

**2. 首页「每日秒杀」栏目**
- `DailySeckill`：hero 正下方，展示商品图/标题/活动时间段（同日省略重复日期）/秒杀价与划线原价/
  余量进度/实时倒计时/限购件数；进行中的活动排前、按结束时间升序（最快结束的最先展示）；
  无活动整块不渲染；「查看全部 →」进秒杀列表页。

**3. 后端配套**
- `seckillPrice` 补进 `LongToStringJacksonModule` 金额白名单：此前漏列以字符串 `"100"` 下发，
  与前端 `number` 类型声明不符 —— 全站价格比较/运算会静默出错。补回归测试
  `seckillPriceStaysNumeric`（`LongToStringJacksonModuleTest`，5/0）。

**验证**：三端 typecheck + mall/vite 构建 0 错；浏览器 18 项断言全过
（首页栏目/商品卡/搜索卡/详情页/秒杀列表：标签、秒杀价、原价划线、活动时间、**倒计时实测走秒**、
余量进度、马上抢按钮、无加购入口、时间本地口径）；秒杀冒烟 **82/0**；
交易单测 **107/0**、白名单回归 **5/0**；前端三守卫全绿（import 487 条 / 产物隔离 / 91 文件 11 处链接全按 SPU 寻址）。

### 2026-10-19（三十三）· 修复：秒杀商品点开提示「商品不存在」（SKU / SPU 主键混用）

**根因**：秒杀列表的标题链接写的是 `/product/${sku.skuId}`，而商品详情页按 **SPU** 寻址
（路由 `/product/:spuId` → `GET /api/products/{spuId}`）。活动配置的是 SKU，两者是不同主键：

```
活动 370103554343579648 → t_seckill_sku.sku_id = 2001
t_sku(2001).spu_id = 1001        ← 商品详情真正的地址
t_spu 存在 id=1001，不存在 id=2001
```

于是列表里商品标题/图片/价格都正常（那是按 skuId 查的 SKU 快照），
点开却请求 `/api/products/2001` → `404 {"code":"NOT_FOUND","message":"商品不存在"}`。
**不是商品被删，也不是活动数据脏了**——是链接拼错了主键。

**修复**：
1. 后端 `SeckillDtos.SkuView` 增加 `spuId`（`SkuSnapshot` 本来就带，只是没下发）；`SeckillService.toView` 透出。
2. 前端 `SeckillSkuView` 增 `spuId: string | null`；秒杀列表按 `spuId` 跳转，快照缺失时标题降级为不可点（**不退回**用 skuId）。
3. 新增静态守卫 `scripts/lib/check-product-link-spu.mjs` 并接入 `check-all.ps1` 前端环节：
   扫描所有 `/product/${…}` 模板串，表达式含 `skuId` 且不含 `spuId` 即判红并点名行号；
   扫描数为 0 同样判红（「没扫到」与「扫了且干净」不可区分）。

**验证**：接口实测新旧地址 `404 vs 200`（2001 / 1001）；
浏览器实测秒杀列表 5 个标题链接均为 `/product/1001`，点开 H1 = 商品标题、无「商品不存在」；
守卫正向 **88 文件 / 9 处链接全绿**，反向注入 `skuId` **恰好 1 处红并点名 Seckill.tsx:176**，还原后绿；
秒杀冒烟 **82/0**。

### 2026-10-19（三十二）· 秒杀紧急修复：秒杀单无法支付 ＋ 活动时间时区错位

**1. 秒杀订单无法支付（"订单没有可扣减的库存冻结记录"）**
根因：`OrderService.completePayment` 对所有订单统一查找**商品库存冻结台账**并扣减，
但秒杀单按设计不走商品库存冻结（它的"货"是活动配额，下单时已原子扣减）⇒ 冻结记录必然为空 ⇒ 秒杀订单永远无法支付。
修复：`completePayment` 对 `orderType=SECKILL` 跳过冻结扣减分支（配额口径不变：下单扣、关单回补）。

**2. 活动结束后仍可购买（时区错位）**
根因：管理端输入的是**北京时间**，旧实现按 **UTC** 存取 ⇒ 实际结束时间比展示晚 8 小时
（展示 18 日 23:08 结束，实际 19 日 07:08 才拦）。
修复：活动时间改为**时区感知解析**——无时区的输入按 Asia/Shanghai 转 UTC 存储，带时区/Z 的按其自身时区；
`place()` 的时间窗校验（原已存在）自此与展示一致。存量活动已按 -8h 数据修正。

**3. 排查结论（取消后"库存不足"）**：库内数据核对 `t_seckill_sku.available_stock` 与订单状态**完全一致**
（1 件库存 = 1 笔待付款单占用；4 笔已关闭单均已回补后重新扣减）——取消回补链路本身正常，
当时的"库存不足"发生在上一笔仍待付款（名额与配额均被占用）期间，属正确拦截；配合上午的限购回吐修复，链路已闭环。

**验证**：秒杀冒烟 **82/0**（新增 8 条：秒杀单 MOCK 支付成功、订单转已付款、活动结束后下单 422 且点名时间窗、
关单释放限购后可再抢等）；交易单测 **107/0**。


### 2026-10-18（三十一）· 接入微信支付（Native / JSAPI / H5）＋ 修复二维码不合标

**1. 微信支付渠道**：新增 `payment/wechat` 包，三种产品形态各一个渠道：
`WECHAT_NATIVE`（网页扫码）/ `WECHAT_JSAPI`（小程序，返回 prepay_id）/ `WECHAT_H5`（手机非微信浏览器）。
签名、验签、解密全部用 JDK 自带能力实现（`SHA256withRSA` + `AES/GCM`），**零新依赖**，兼容离线构建。
回调复用既有 `POST /api/payments/callback/{channel}` 与入账链路；SPI 新增 `refund()` default 方法（默认不支持）。

**2. 生产 / 沙箱切换**：`duoduo.pay.wechat.enabled` 决定微信渠道是否注册，
`duoduo.pay.default-channel` 决定前端不传渠道时的默认值。**沙箱与 MOCK 的配置、代码、收银台页面全部保留**。

**3. 修复二维码扫不出来**：自研编码器输出的矩阵不合标准（实测 OpenCV 与 zbar 双解码器均无法识别；
矩阵级诊断：三个定位图案不在位、格式信息非法）。矩阵生成改用成熟库 `qrcode`，
实测 pyzbar 可正确解出内容。这是本次扫码无反应的直接原因。

**验证**：`WechatPayCallbackTest` **8/8**（正常回调、篡改签名、时间戳超窗、缺签名头、非 SUCCESS、
密钥不匹配、配置自检、未启用不校验）；交易模块单测 **107/0**；二维码解码实测通过。

**未完成**：真实联调待商户凭据与公网 HTTPS 回调地址（见《微信支付接入-配置项与联调说明-2026-10-18.md》）。


### 2026-10-18（三十）· 修复：已关闭的秒杀订单不再占用限购名额

**现象**：抢到秒杀 → 未付款 → 关单（手动取消或超时），再抢同一商品提示「已达到该商品的限购件数」。

**根因**：限购由 Redis 计数器 `seckill:bought:{sku}:{user}` 判定，而关单回补（`onOrderClosed`）
只回补了**活动余量**（`seckill:stock:*` 与 `t_seckill_sku.available_stock`），
**没有回吐个人已购计数**——计数只增不减，已关闭订单把名额永久占死。

**修法（两层）**：
1. **关单回吐**：`RESTOCK_LUA` 扩为同时回吐个人计数（有下界，不降到负数），
   `onOrderClosed` 传入 `KEYS[2]=bought` —— 手动取消、超时关单、运营强关三条路径都经过这里；
2. **计数自愈**：预扣收到「超限」拒绝时不再无条件拒绝，先查 DB
   （`t_seckill_order JOIN t_order`，按**未关闭**状态计数；新增 `SeckillOrderMapper.countActiveByUserAndSku`），
   DB 未达限购 → 判定 Redis 计数偏高 → 回吐一次并重试；DB 也到限才真正拒绝。
   这样即使 Redis 曾漏回吐（历史残留 / 进程崩溃），也能在下一次抢购时自愈，无需手工清 Redis。

**口径**：退款（REFUNDED）订单**仍计入**限购——确曾成交；是否释放退款名额是独立口径，未在本次处理。

**验证**：`seckill-smoke` **76/0**（新增 7 条：抢 → 关单 → **同一买家再抢成功** →
未关闭订单仍占名额（第三次 422）→ 收尾）；静态守卫 exit 0。
受影响用户（如 zgh001）无需清 Redis，下次抢购会自动自愈。

### 2026-10-18（二十九）· 售后类通知点击直达售后详情页（不再落到订单详情）

**根因**：售后类通知（`AFTER_SALE_*`）的 `biz_id` 存的是**订单号**，`jump_page` 是小程序售后
**列表页**；而网页映射 `NoticeWebPaths` 对 ORDER 类通知统一生成 `/orders/{订单号}` ——
于是"售后有进展"的通知点开是订单详情。

**修法（三处联动）**：
1. 推送侧（`FulfilmentNotifier`）：8 个售后通知方法增加 `afterSaleId` 参数，
   `biz_id` 改存**售后单 id**（一张订单可能有多次售后，订单号定位不到唯一一条）；
   小程序落地页不变（仍是售后列表，受页面白名单约束）；
2. 映射侧（`NoticeWebPaths`）：`biz_type` 以 `AFTER_SALE` 开头的通知 → `/after-sales/{售后单id}`；
   其余通知（下单/支付/发货/物流/好友/公告）行为不变；
3. 存量数据修正：一条 SQL 把既有 87 条 `AFTER_SALE_*` 通知的 `biz_id` 按「订单号 → 最新售后单」回填，
   老通知点开也能直达售后详情。

**验证**：`NoticeWebPathsTest` **5/5**（新增售后映射 3 断言）；履约单测 **20/0**；
浏览器实测——点「售后申请已提交」通知落到 `/after-sales/370206974748540928`，
内容为对应售后单，**不是**订单详情。

### 2026-10-18（二十八）· 头像菜单新增「我的售后」＋ 售后详情补返回入口

**梳理结论**：售后功能本体是完整的（列表 `GET /api/aftersales/mine`、分类页签、状态标签、
卖家/平台处理说明、退款金额、分页、详情页），缺的是**两个访问入口**：

1. **头像菜单没有「我的售后」**——用户提交售后后没有任何常驻入口能找回记录，
   只能靠「订单卡片 → 申请售后」或记住 `/after-sales` 路径；
   已在头像下拉新增「我的售后」，位置紧跟「我的订单」（订单 → 售后是同一条动线）；
2. **售后详情页没有「返回列表」**——原来只有「查看订单」，看完想回到记录列表只能靠浏览器后退；
   已在详情页头部补「返回列表」，与「查看订单」并排。

**验证（真实浏览器 9/9）**：菜单含「我的售后」且位于我的订单之后 → 列表页展示售后单
（订单号 / 仅退款 / 待卖家处理 / 状态标签）→ 点「查看详情」进入详情 → 「返回列表」可回到列表。

**测试数据**：为使验收时列表非空，把测试账号的一笔演示订单置为已完成，并创建了一笔
「仅退款 / 待卖家处理」的演示售后单（订单 `202610181036302688546`），可在验收时直接查看。

### 2026-10-18（二十七）· 「去支付」改绿色渐变（与查看详情的青石色拉开）

**改动范围**：只改「去支付」的背景；「查看详情」维持青石实心 `#14b8a6`，「申请售后」**一字未动**。

**为什么是渐变而不是纯绿**：
- 纯色大块绿在白色卡片上偏平，且与青石色（174°，偏蓝绿）只差一点色相，远看容易混；
- 改成 `linear-gradient(135deg, #4cc463 → #1b8a43)`：上浅下深，明度层次拉开；
- 叠加 `inset` 内高光 + 绿色投影，与其他实心按钮同一套"凸起"质感；
- 色相取叶绿（约 145°），与青石色保持 60+ 的通道差异。

**应用位置**：订单列表卡片 + 订单详情操作区（同一套 `.btn-pay`）。

**验证**：浏览器计算样式 —— 去支付 `linear-gradient(135deg, rgb(76,196,99), rgb(27,138,67))`、白字；
查看详情仍为 `rgb(20,184,166)` 实心；绿与青石最大通道差 **67**。

### 2026-10-18（二十六）· 订单按钮六档配色：查看详情更醒目，去评价与去支付拆开

**先说重复**：`去评价` 与 `去支付` 原来都用 `btn-primary`（实心橙），确实一模一样；
`查看详情` 原来和 `申请售后 / 问客服` 一样是灰色描边，不醒目。

**现在的六档（任意两个动作都不会"长得一样"）**：

| 语义 | 样式 | 用于 |
|---|---|---|
| 付钱 | **橙实心** `#ff7a45` + 白字 | 去支付 |
| 破坏性 | **红实心** `#e5484d` + 白字 | 取消订单 |
| 查看/导航 | **青实心** `#14b8a6` + 白字 | 查看详情 |
| 建设性次操作 | **浅橙底 + 橙字** | 去评价 |
| 售后 | **青描边 + 青字** | 申请售后 |
| 中性 | **灰描边** | 问客服（**保持现状**）、已评价 |

- `查看详情` 改为青色实心：在待发货/待收货/已完成/已关闭卡片上它都是"下一步"，醒目但不与付款抢；
- `去评价` 改为浅橙底橙字：与"去支付"同色系但不实心，肉眼可区分；
- `申请售后` 改为青描边：不再与问客服的灰描边混在一起；
- 问客服**未做任何改动**。

**验证（浏览器计算样式）**：待付款 `去支付 rgb(255,122,69)/白字`、`取消订单 rgb(229,72,77)/白字`、
`问客服 白底灰字`；已支付/已完成 `查看详情 rgb(20,184,166)/白字`；
已完成 `去评价 rgb(255,241,234)/rgb(232,95,39)`、`申请售后 白底/青字`。
截图 `order-buttons-COMPLETED.png`、`order-buttons-PENDING_PAYMENT.png`。

### 2026-10-18（二十五）· 订单卡片「去支付 / 取消订单」改为实心双色按钮

**诉求**：两个按钮原先都是白底（`btn-ghost`），一眼看不出主次，也容易点错。

**改动**：
- **去支付** → 实心主色（`btn-primary`，橙 `#ff7a45`）＋ 白字；仅在「待付款」使用，
  其他状态的「查看详情」保持描边样式，避免所有卡片都被高亮；
- **取消订单** → 新增 `.btn-danger-solid`（实心危险色 `#e5484d` ＋ 白字 ＋ 同色淡阴影）；
  订单列表与订单详情两处一致；
- 保留原 `.btn-danger`（白底红字）给「申请退款」这类次要破坏性操作，形成两级危险层级：
  **取消订单 = 实心红（高优先）**，申请退款 = 描边红（次优先）。

**验证**：真实浏览器读取计算样式 6/6 —— 去支付 `rgb(255,122,69)` / 白字；
取消订单 `rgb(229,72,77)` / 白字；两者颜色明显不同；详情页同一套。
截图 `order-buttons-solid.png`。

### 2026-10-18（二十四）· 通知入口只留数字角标 ＋ 未读数即时更新

**1. 去掉 🔔 / 💬，只保留数字角标**

头像下拉里的「通知中心 / 私信」不再带图标；有未读时在文字后面跟一个行内数字角标
（`.badge-inline`，与头像角标同一套主色与圆角，>99 显示 `99+`），**没有未读就什么都不显示**。
行内角标参与文字排版，不会像绝对定位角标那样压住文字。

**2. 未读数不实时**

根因：未读状态由 `store/app` 统一维护，原刷新点只有「登录 / 页面重新可见 / 打开头像菜单 /
**60 秒兜底轮询**」。下单与支付成功都没有触发刷新 —— 所以用户付完款看到的还是上一轮的数字，
必须刷新页面或再点头像。

修法（四条一起，缺一条都会漏）：

| 触发点 | 位置 | 效果 |
|---|---|---|
| 下单成功 | `Checkout.tsx`：`await refreshUnread()` | 下单后角标**立即 +1** |
| 支付成功 | `OrderDetail.tsx` 三个成功分支（轮询 / MOCK / 模拟扫码） | 支付后角标**立即 +1** |
| 站内切页 | `Layout.tsx` 监听 `location.pathname` | 跳到订单详情等页面时兜一次 |
| 兜底轮询 | `UNREAD_POLL_MS` 60s → **10s** | 覆盖「别人触发的通知」（卖家发货、公告） |

**边界说明（不粉饰）**：自己的动作（下单、支付）是即时刷新，与购物车角标一致；
卖家发货这类**由他人触发**的通知仍靠 10 秒轮询兜底。若要求这类事件也零延迟，
需要上服务端推送（SSE）——那是下一步，不是继续缩短轮询能解决的。

**验证**：真实浏览器 5/5 —— 起点 0；UI 下单后 **0 → 1**；MOCK 支付后 **1 → 2**；
菜单无 🔔/💬；菜单内存在行内数字角标（截图 `notice-inline-badge.png`）。

### 2026-10-18（二十三）· 通知中心三个问题：角标不即时、点开 404、入口括号数字

**1. 已读后角标不消失**

根因：顶栏未读角标由 `@duoduo/shared/store/app` 提供，刷新只有两个触发点 —— 登录/页面重新可见、
以及 **60 秒兜底轮询**。通知中心标记已读后只更新了本页列表，没有调用 `refreshUnread()`，
所以用户回到任意页面仍看到旧数字，直到下一次轮询或再点一次头像。

修法：`Notifications.tsx` 在「单条已读」与「全部已读」后都 `await refreshUnread()`，
完成后再跳转 —— 角标**即时清零**，不依赖轮询。

**2. 点通知跳到「页面不存在」**

根因：`t_social_notice.jump_page` 存的是**小程序路由**（`/pages/order-detail/order-detail?orderNo=…`，
写入时按小程序页面白名单校验）。网页 SPA 没有 `/pages/**`，直接 `navigate` 必然 404。

修法：服务端新增 `webPath` 字段（与 `jumpPage` 并列下发），映射规则集中在
`NoticeWebPaths`：订单通知 → `/orders/{orderNo}`；好友消息 → `/messages/{peerId}`；
其余（公告、商家违规）返回 `null` —— 网页端**只标已读、不跳转**，不再跳 404。
新增单测 `NoticeWebPathsTest` **4/4**（含「缺 bizId / 缺 peerId 时不能拼出半截路径」）。

**3. 入口括号数字改为图标角标**

头像下拉里的「通知中心（1）/ 私信（1）」改为 **🔔 / 💬 + 右上角数字角标**，复用站内 `.badge-dot`
（与头像、购物车角标同一套样式，>99 显示 99+；通知与私信各显示各自的数量）。

**验证**：真实浏览器 9/9 —— 头像角标=1；菜单为图标且无括号数字、通知图标上角标=1；
点通知落到 `/orders/202610181016420168908` 且不是「页面不存在」；
「全部已读」后头像与菜单角标**立即归零**（无需再点头像、无需等 60 秒）。

### 2026-10-18（二十二）· 原生 confirm/alert 全部替换为站内统一弹窗

**问题**：取消订单、删除分享仍在用 `window.confirm` —— 浏览器原生弹窗与站内风格完全是两张皮
（系统默认字体、方块按钮、无法用主色/危险色），且会阻塞主线程（弹窗期间轮询与动画停摆）。

**做法**：新增全局 `ConfirmProvider`（`@duoduo/shared/components/confirm`），提供 Promise 化 API：

```ts
const confirm = useConfirm();
if (!(await confirm({ title: '取消订单', message: '…', danger: true }))) return;
```

- 弹窗本体**直接复用站内 `Modal`**（与「在线客服」同一张白卡、同一个右上角关闭、同一套 footer 按钮）；
- 行为与原生等价：**确定 → true；取消 / 右上角 × / Esc → false**；`alert` 只有「知道了」；
- `danger: true` 时确定按钮用危险色（删除、取消订单）；文案可定制（如「再想想 / 确定取消」）；
- 三个应用入口（商城 / 后台 / 商家端）都已挂载 Provider；未挂载时直接报错，不静默退回原生弹窗。

**替换点**：`Orders.tsx`（取消订单）、`ShareMine.tsx`（删除分享）。全仓库已无 `window.confirm/alert/prompt`。

**验证**：三端 `tsc` + 构建通过；Playwright 实测 6/6 —— 点击取消**未触发原生 dialog**、
弹出站内弹窗、点「再想想」订单仍为待付款、点「确定取消」订单变已关闭、全程无原生弹窗。

### 2026-10-18（二十一）· 订单按钮统一 ＋ 商品详情「问客服」入口 ＋ 客服方案评估

**先说核对结果**：你截图里的卡片只有「去支付」，但当前线上产物已经包含「取消订单」——
用真实浏览器登录后实测卡片按钮为 `["去支付","取消订单","问客服"]`，点击取消后订单变「已关闭」、
按钮随之消失。截图应来自**重建前已打开的旧标签页**（SPA 不会自动重取 index.html），强制刷新即可。

**本轮改动**：
- 新增统一客服入口组件 `components/SupportEntry.tsx`：商品详情、订单列表卡片、订单详情三处同一个按钮、
  同一种文案「问客服」，位置固定在购买/取消动作之后；
- 入口诚实标注「人工客服正在建设中，暂未开放接待」，不伪造在线状态，并给出可用替代（AI 导购）；
- 订单操作统一口径：**去支付 / 取消订单 / 问客服 / 申请售后 / 确认收货 / 申请退款 / 去评价 / 查看详情 / 刷新**；
  展示顺序固定为「主操作 → 取消 → 问客服 → 其他写操作 → 刷新」，各状态可用条件见评估文档附表。

**客服方案**：输出工作区《问客服-直连平台消息方案评估-2026-10-18.md》。
结论：**不建议「全员可见可回复」直接上线** —— 责任归属（多口径、承诺无主）与响应归属
（首响/解决算谁的、重复回复）会先于技术出问题。建议「一会话一责任人 + 认领锁 + 唯一发言权 + 全程审计」，
并给出状态机、数据模型、权限与验收要点。本次仅做入口，不做会话。

### 2026-10-18（二十）· 秒杀可用券 ＋ 订单卡片直接取消 ＋ 签到/多多币入需求池

**1. 秒杀商品无法用券：确认是设计限制，已按你的要求放开**

原因：`PlaceSeckillRequest` 只有活动 / 规格 / 地址 / 幂等键四个字段，
`SeckillService` 建单时把 `discount_amount` 写死 `0`、`pay_amount` 等于秒杀价 ——
**不是 bug，是本轮之前刻意没做**（普通下单才有券链路）。

修法：
- DTO 增加可选 `couponCode`；建单时先锁券再落库（营销域按订单号定位券，解锁/核销都走订单号）；
- 金额口径：`实付 = 秒杀价 − 券抵扣`（营销域已按订单金额封顶，交易域再兜一层不为负）；
- 锁券失败**整单不成立**并返回可读原因，绝不"券用不了就按原价下单"；
- 建单失败 / 幂等撞车时**立即解锁**，不等营销域 35 分钟 TTL；取消 / 关单已有链路会自动释放；
- 网页与小程序都按"当前秒杀价可用的最优券"自动预选（最终以服务端锁券为准），网页在价格旁标「可用券 -¥X」。

实测（`seckill-smoke` 新增 9 条，**69/0**）：250 元秒杀 + 满 200 减 30 → 实付 220 元、
订单 `discount_amount=3000` 且 `coupon_code` 落库、取消后券**立即**回到未使用。

**2. 订单卡片直接取消（待付款）**

状态 → 按钮口径：

| 订单状态 | 卡片按钮 |
|---|---|
| 待付款 | 去支付 · **取消订单** · 剩余支付时间 |
| 待发货 | 查看详情 |
| 待收货 | 查看详情 · 申请售后 · 资金托管提示 |
| 已完成 | 查看详情 · 申请售后 · 去评价 / 已评价 |
| 已关闭 / 已退款 | 查看详情（终态，无写操作） |

取消前二次确认文案说明"库存与优惠券会立即释放"；成功后刷新列表，避免出现"已关单却仍显示去支付"。

**3. 每日签到 / 多多币：只记录，不实现**

已写入 `docs/PLAN.md` §7 需求池（R-P1），固化了你的口径（100 币 = 1 元、可与所有券叠加、
含秒杀、实付 1:1 得币），并列出实现前需拍板的 6 条规则（连签梯度、抵扣上限、有效期、退款回收、补签防刷、是否发券）。

### 2026-10-18（十九）· 评分两处不一致的根因修复 ＋ 评价图片支持本地/外链双通道

**问题一：首页 4.0 分 4 条，详情页「暂无评分」**

根因：两处读的是**两个数据源** —— 首页卡片读 `t_spu.rating / rating_count`（冗余列），
详情页读评价库实时汇总（`/api/reviews/product/{id}/summary`）。冗余列只在「新评价提交后」回写，
而建库时写入的 4.0 / 4 是**种子假数据**，没有任何评价与之对应，因此永远纠正不过来。
（本轮补的 30 个商品也带了 4.x/126 这类展示值 —— 同一问题的放大版。）

修法：
- 商品域新增内部接口 `GET /api/products/internal/ratings/candidates`（rating_count > 0 的 SPU）；
- 履约域新增**双向对账** `RatingReconcile`：候选有、评价库没有 ⇒ 复位 0/0；
  评价库有、候选没有 ⇒ 补推真实聚合。触发点＝**启动后立即一次 + 每小时一次**，
  不再依赖「必须有人再写一条评价」；
- `recomputeRatings` 从「读整行 → 写整行」改为**只更新 rating / rating_count 两列**，
  避免并发下把别人刚改的标题/价格覆盖掉。

实测：31 个假评分被复位（1101 从 4.8/126 → 0/0）；1001 有 1 条真实评价 ⇒ 保留 5.0/1；
接口对比 `card.rating=5.0/count=1` 与 `summary.avgRating=5.0/total=1` 一致，
1101 两侧同为 0；`review-smoke` 62/0（含「评分复原」）、`review-ui-check` 24/0。

**问题二：评价只能填图片 URL**

- 后端新增 `POST /api/uploads/from-url`：服务端下载 → 校验 → 落**同一个 ObjectStorage**（当前是 COS）
  → 返回本站 URL。安全边界：只允许 http/https、解析后必须是**公网地址**（回环/私有/链路本地/组播一律拒绝）、
  **不跟随重定向**、Content-Type 白名单 + 文件头魔数校验、连接 5s/读取 10s/体积与普通上传同限。
- 评价页（`ReviewNew.tsx`）改为双通道：**「+」选本地图片**（多选、逐张上传、缩略图、可删除、失败点名）
  ＋**地址输入「转存到图床」**（支持多张、同样落 COS）。提交的就是转存后的本站地址。
- 实测：本地上传 → COS URL；用该 URL 走 from-url → 另一个 COS URL 且 HTTP 200；
  `http://127.0.0.1:8080/...` → 400「该地址不允许访问」；`https://example.com/` → 400「不是图片」。

### 2026-10-18（十八）· 每个一级分类补到 5 个自营商品（30 SPU / 36 SKU）＋ 去掉分类名前的英文前缀

**数据**：`docs/sql/incremental/35-seed-test-products.sql`（同时并入 `02-duoduo-product.sql`，
保证 `docker compose down -v` 重建后仍在）。共 **30 个 SPU / 36 个 SKU**，
每个一级分类 5 个（主粮因原有 2 个，现为 7 个）：

| 一级分类 | 商品数 | 覆盖子类 |
|---|---|---|
| 宠物主粮 | 7（含原有 2） | 猫主粮 / 犬主粮 / 冻干与湿粮 |
| 宠物零食 | 5 | 猫零食 / 犬零食 / 训练奖励 |
| 宠物用品 | 5 | 猫砂猫厕 / 食具水具 / 出行牵引 / 窝垫爬架 |
| 健康医疗 | 5 | 体内外驱虫 / 营养保健 / 清洁护理 |
| 智能设备 | 5 | 智能饮水机 / 自动喂食器 / 智能猫砂盆 |
| 活体与领养 | 5 | 猫咪领养 / 犬只领养（**是领养服务包，不是活体交易**） |

- **全部自营**：`supply_type='self'`、`seller_id=0`（实测 `not_self=0`）；
- **只插不改**：固定主键 + `INSERT IGNORE`，未触碰任何既有商品（1001/1002 原样保留）；
- 字段格式与既有商品完全一致：价格单位分、`images` 逗号分隔、`spec_json` 为 JSON 字符串；
- 10 个 SPU 带双规格（用于验规格切换），价格区间覆盖 19.9 元 ~ 1299 元（用于验价格筛选/排序）。

**分类名**：数据库里分类名本来就是中文（`宠物主粮`…），截图里的 `bowl/bone/box/medical/chip/heart`
是 `icon` 字段被首页当成文本渲染。已修 `Home.tsx`（只保留中文名），`icon` 字段保留待后续做图标化；
国际化属于后续需求，本次不做。

**验证（实测）**：按一级分类查询 7/5/5/5/5/5；分页 2+2+2+1=7 且无重复；价格升序/降序正确；
`species=dog`、价格区间筛选正确；详情返回 2 个 SKU 与适配信息；**全量重建 ES 索引后**
搜索「猫粮」命中 6 条（此前只命中 2 条，新数据未入索引）；真实浏览器核对首页分类文本为
「宠物主粮」且无英文前缀，「为你推荐」商品条数 24。

### 2026-10-18（十七）· 把「特权夹具账号」的清扫挪到 check-all 全程结束时

`check-all` 的顺序是「冒烟 → e2e」，而账号清扫原先只在**冒烟结束**执行。
2026-10-18 实测：冒烟结束时已降权 26 个，但 **e2e 跑完又留下 19 个 OPERATOR + 17 个 SELLER**
（e2e 同样需要现场造运营/商家夹具，部分套件不收尾）。

修法：`check-all.ps1` 在**开跑前**快照一次特权账号 id，在 **e2e 之后**再快照一次，
只降权**本轮新增的**（diff，不猜昵称、不误伤既有账号，包括验收用的运营账号）。
清理结果也进汇总表；新增 0 个时显式打印「已核对两次清单」，避免与「这段没跑」混淆。

实测：用 `-Skip*` 全跳过模式验证该段可独立运行（跑前 2 → 跑后 2，新增 0，exit 0）；
并一次性清掉 e2e 遗留的 34 个夹具账号，库里最终只剩 2 个运营账号（平台运营 + 验收账号）。

### 2026-10-18（十六）· 验收首跑的两条红：都是**判据/夹具假设**错，不是产品回归

用户在完整自检中拿到 32 套冒烟 1 红、22 套 e2e 1 红，逐条定位：

1. **`tenant-health-smoke`（2 条）**：租户增长到 **212 家**，超过接口上限 `limit=200` ⇒ `truncated=true`。
   平台汇总按**全量 212 户**计算（接口刻意口径），脚本却拿**返回的 200 行**求和 ——
   差的 17 次调用正来自被截掉的 12 户。改为**双形态断言**：未截断比"平台=行和"，
   已截断断言 `tenantCount = totalTenants` 且 `行数 = limit`，并显式打印 `[PASS*]` 说明。
   复跑 **90 / 0**。
2. **`admin-entry` e2e（4 条）**：用例要求"并发前恰有 2 名 ADMIN"，而本版新建的验收账号是 ADMIN ⇒ 实际 3 名，
   两条降级都成功是**正确行为**。验收账号改为 **OPERATOR**（后台验收动作全够用），复跑 **74 / 0**。

> 两条都没有把红改成"允许失败"，而是让断言在两种事实形态下各自成立。

### 2026-10-18（十五）· D4 秒杀抢购记录补齐（**双端**）＋ 一处台账纠错

**D4 是 S6 对照表里最后一条未补的 D 类缺口。** 补齐前先核实，发现原记载不实：
文档写「小程序的秒杀页含『我的抢购记录』区块」，实测**双端都没有**记录入口——
台账把「计划要有」写成了「已经有」。

**做法：不新造第二份列表，给订单列表加一个服务端筛选参数。**
- `GET /api/orders` 新增 `orderType`，白名单 `NORMAL` / `SECKILL` / `C2C`，
  **未知取值 400**（不静默返回空列表——否则「传错了参数」会表现成「我确实没有这类订单」）。
  **必须服务端筛**：分页下前端过滤只能看到当前页里的那几条，记录落在下一页时表现为「没有」。
- 网页 `/orders?type=SECKILL`：顶部提示条「当前只显示秒杀单」+「查看全部订单」；
  秒杀页头部加「我的抢购记录 →」入口（未登录先引导登录）。
- 小程序：`pages/seckill` 加一行入口 → `/pages/orders/orders?type=SECKILL`；
  订单页 `onLoad` 先读类型（早于 `onShow`），不会出现「先拉全部再拉秒杀」的双请求闪烁。
- 订单卡片的类型标签由英文枚举改为中文（普通单 / 秒杀单 / 闲置单）。

**验证（全部实测）**：新增单测 `OrderListFilterTest` **4/4**（断言直接看下发的 SQL 片段：
`order_type` 条件在/不在、未知类型抛错、`user_id` 维度始终存在）；
`seckill-smoke.ps1` 新增 D4 段 **7 条断言**（记录里确实有本单 / 每条都是 SECKILL /
不含他人订单 / **接口 total 与库内 COUNT 两来源一致** / NORMAL 反向对照 / 无单用户为空 / 小写 400）；
守卫 `check-dual-client.ps1` 扩为 **D1/D2/D4 三项双向断言**，反向验证把文档改回「待补」
→ **恰好 1 条红、exit 1**，还原后全绿。

### 2026-10-13（十四）· 阿里云部署评估（**已搁置**：等大内存服务器）＋ 优惠券口径确认

**部署评估**：用户购入阿里云 ECS（华南3，2 核 4G，CentOS 7.9，按量付费）。核实三个硬问题：
**无公网 IP**（按量付费实例不自带，需绑 EIP）、**CentOS 7.9 已 EOL**（建议恢复时换 Ubuntu 22.04）、
**4G 内存不足以承载全栈**（网关+Java 微服务×N+MySQL+Redis+RocketMQ+ES）。⇒ **部署整体搁置**，
完整评估与 9 步恢复流程、8G 内存预算留档于
[`docs/阿里云部署方案-2026-10-13.md`](./docs/阿里云部署方案-2026-10-13.md)，换 ≥8G 服务器后直接照做。

**优惠券口径确认（非 bug）**：用户反馈「没有可用的优惠券」。核实：`t_coupon_template` 两张在架
（`5001` 新客立减 5 元·满 50 可用，余 920；`5002` 满 200 减 30，余 492），网关
`GET /api/coupons/templates` 实测正常返回；库里 `t_coupon` 的持券记录全部属于冒烟夹具账号。
**券需先进「我的 → 领券中心」领取，才会在结算时出现** —— 明日验收时走一遍「领券 → 用券」真实流程即可。

### 2026-10-13（十三）· 遗留项收口：A-1 越权收窄、备份自动化、特权夹具账号根治、S6-D1/D2b/D3 补齐

**安全**：`SellerProductService.update()` 补上代运营门禁 —— 平台运营**不再能绕过工单直接编辑第三方店铺的商品**
（E23 当初只拦了上下架，"编辑"是登记的遗留项）。接口级实测 `proxy-ops-smoke.ps1` **127 项 / 0 失败**。
★ 同一次改动顺带查出一个真缺陷并修掉：`subtitle` / `images` / `detail` 三个"可清空"字段走
`updateById`（MyBatis-Plus NOT_NULL 策略）⇒「传 "" 清空」**接口 200、库里纹丝不动**。

**备份**：`db-backup.ps1` 注册为每日计划任务 `DuoduoDbBackup`（03:00），注册后立刻试跑 `LastTaskResult=0`。
⚠️ **局限**：本机只有一块物理盘 ⇒ 备份**防误删、不防硬盘损坏**。

**测试账号**：新增 `scripts/lib/fixture-role-sweep.ps1`（默认干跑；写库前先落回滚 SQL；
保护账号不在目标集合里才执行；降权后按两个来源复核）。
执行结果：**降权 945 个**，全库 `USER 3009 / OPERATOR 1`。
**根治**：`run-all-smoke.ps1` 新增"**运行前后 diff**"收尾 —— 只把本轮新增的特权账号降权，
不误伤既有账号，也不依赖昵称约定（实测：跑中 14+13 → 收尾 1|0|0）。

**功能**：网页端个人主页 `/user/:id`（后端零改动，接口已存在）+ 三处入口；
小程序独立搜索结果页 `pages/search/search`（不占 tabBar）；
小程序"宠物档案请在网页端管理"引导文案。

**守卫**：`check-dual-client.ps1` 的 ③ 原本**无条件**断言"缺口仍在" ⇒ 功能补齐后**永远不可能转绿**
（"修好了反而过不了"的守卫，结局只能是被注释掉）。改为**双向**：文档说待补 ⇒ 断言代码里没有；
文档说已完成 ⇒ 断言代码里有。反向验证通过（改回"待补"而代码里有时恰好报红并点名）。

**实测**：全量冒烟 **32 套全过**（1 套按设计跳过并点名）；全量单测 **740 项 / 0 失败**；
前端三应用 `tsc+vite` 全绿；四个静态守卫全 exit 0。

### 2026-10-13（S9：图片与资质材料接入**对象存储**（腾讯云 COS）—— 新增一层介质无关的 `ObjectStorage`，默认仍是本地盘）

**做了什么**：把「图片/材料落在哪」从写死在两个类里的本地盘，改成可切换的抽象层。
新增 `com.duoduo.common.web.storage` 包：`ObjectStorage` 接口 + 两个实现
（`LocalFsObjectStorage` 本地盘 / `CosObjectStorage` 腾讯云 COS）+ 自动装配，
`UploadController` 与 `MerchantMaterialStorage` 改走接口。

**★ 三条不能丢的约束（改造时守住了）**：

1. **对外 URL 形态一字未改**：上传仍返回 `<base>/uploads/<uuid>.<ext>` —— 改了这个等于历史图片全部 404；
2. **私有语义绝不让位**：资质材料走**独立私有桶**，读取仍只经 `MerchantOnboardingService.readMaterial` 的授权判断，
   换介质**没有多出任何一条绕过鉴权的读法**（有实测：私有桶对象匿名访问被拒）；
3. **默认值必须是 `local`**：新存储层一旦有问题，改一行配置重启即可回退；
   `type=cos` 但密钥/桶名没配全时**回落 local 并打 WARN（点名缺哪个）**，不启动失败、也不静默。

**为什么自己写签名而不用 SDK**：本项目的 Maven 用**项目内本地仓库**（`maven/settings.xml` 的
`<localRepository>.../.m2repo</localRepository>`）且构建走 `-o`（离线），实测该仓库里
`com.qcloud:cos_api`、`io.minio:minio`、`software.amazon.awssdk:s3` **一个都没有**。
而 COS 签名 v5 是公开算法：两次 HMAC-SHA1 + 一次 SHA1，JDK 自带即可（约 60 行），
与项目已有的自研签名（`InternalWeb/InternalCallAuth`）风格一致。

**★ 踩到并修掉的坑（值得记住）**：第一次打真接口返回 `403 SignatureDoesNotMatch`。
把官方文档的示例逐字喂进来后发现 —— `HttpString` 与 `SignKey` 两段**都对**，
错在最后一步：`StringToSign` 不是裸的 `SHA1(HttpString)`，而是

```
StringToSign = "sha1" + "\n" + KeyTime + "\n" + SHA1(HttpString) + "\n"
```

**漏了固定前缀 `sha1`、中间段 `KeyTime`、以及结尾换行** —— 服务端只会回一句"签名无效"，
不会告诉你差在哪。另：`HttpString` 里的路径必须是**未编码**形式（官方示例：线上发
`/exampleobject(%E8%85%BE%E8%AE%91)`，签名里写 `/exampleobject(腾讯云)`）。

**测试与守卫**：

- `CosSignerTest`（7 项）：含**官方文档示例向量**（文档把示例签名尾部打码，故按前 36 位十六进制断言），
  以及"KeyTime 变了签名必须变"（防止回退成漏 KeyTime 的写法）；
- `LocalFsObjectStorageTest`（8 项）：前缀剥离、租户目录、缺失返回 `null`、错前缀报错、路径穿越、`delete` 幂等；
- `CosObjectStorageLiveTest`（5 项，`COS_LIVE_TEST=true` 门控，**默认跳过**）：打真实 COS 验证
  **公开桶匿名 GET 必须 200**、**私有桶匿名 GET 必须被拒**、**持密钥列桶必须被拒**（最小权限反向验证）、
  密钥写错必须明确报错；夹具现场建、跑完自毁并断言无残留；
- `scripts/lib/check-storage-guard.ps1`：四层静态守卫（接口不得被 `java.nio.file` 污染、
  调用方不得直接落盘、配置默认值必须是 `local`、★ **正对照**：本地实现里必须能扫到 `java.nio.file`）。

**实测**：全项目单测 **735 项 / 0 失败**（715 + 新增 20）；打真实 COS 联调 **5/5 全过**；
静态守卫 `exit 0`，反向验证（把默认值改成 `cos`）**恰好那一条红并点名**。

**配置**：`STORAGE_TYPE`（默认 `local`）、`COS_SECRET_ID` / `COS_SECRET_KEY` / `COS_REGION` /
`COS_BUCKET_PUBLIC` / `COS_BUCKET_PRIVATE`、`UPLOADS_PUBLIC_BASE_URL`。
密钥只经 `.env`（已 gitignore）注入，**绝不进前端构建产物**。

**S9 收尾（同日）**：

- **`upload-smoke.ps1` 扩成"两种实现各跑一遍"**：先读**容器内**的 `STORAGE_TYPE` 认清后端，
  再按后端分叉断言落点 —— `local` 要求文件**在**卷里（正对照）；
  `cos` 要求文件**不在**卷里 + url 指向桶域名。★ 为什么必须加：①~⑥ 在两种后端下**完全一样**
  （这正是抽象层要的），于是「配了 cos 却因缺密钥回落 local」会**全绿** —— 新判据就是堵这个假绿的。
  实测：**local 11/11、cos 12/12 全过**（cos 那组含 `⑧` 桶内夹具自毁，用 .NET 自写的 COS 签名 DELETE）。
- **`db-backup.ps1` 卷清单同步**：读 `STORAGE_TYPE` 决定卷的角色（`local⇒primary` / `cos⇒legacy`），
  新增 **`[4c/7] 生产存储覆盖度自证`**；`Record` 扩成三态（`PASS` / `FAIL` / **`GAP`**），
  manifest 新增 `storage` 段与 **`OK_WITH_GAP`** 状态。
  为什么要它：切到 COS 后，备份链会呈现最危险的状态 ——
  **「脚本照跑、汇总照绿，但备的东西已经不是生产用的那份了」**。
  实测：local ⇒ `status=OK`；cos ⇒ `status=OK_WITH_GAP` + `[GAP]` 点名 + 照发告警。
- **静态守卫挂进 `check-all.ps1`**（`[0/5]`），否则"不在 CI 里＝迟早静默腐烂"。
- **新增 `scripts/lib/cos-tool.ps1`**：纯 .NET 的 COS 最小客户端（Put/Get/Head/Delete），
  供运维脚本复用（避免为一次性操作去装 COSCMD）。
- **新增 `scripts/migrate-uploads-to-cos.ps1`**：存量图片/材料的迁移器 ——
  **幂等**（桶内已一致则跳过）、**可停可续**、**逐文件双源 sha256 核对**
  （判据是"卷里那份"与"桶里回读那份"相等，不是"上传返回 200 就算成功"）。
  ★ 修掉自身一个开关反了的 bug：第一版把上传条件写成 `-not $VerifyOnly`，
  于是**默认的"干跑"照样把 132 个对象写进了真实桶**，而脚本还在汇总里打印"没有写入任何对象" ——
  「干跑」必须**真的**一个字节都不写。修正后：默认干跑只列 `[计划]`，写桶只由 `-Apply` 决定。
  实测：**132 个文件桶内与卷内逐文件 sha256 全部一致**。

### 2026-10-13（S5：把「涉及钱」的口径固化成**算术**断言 —— 越权矩阵 82 → **115 项**；并修掉一处「自愈误删本轮夹具」）

**一、为什么单开一节**
原 82 条测的都是「**谁能不能做**」。但本项目真正反复栽的一类缺陷不在权限上，而在**钱和库存算错**：
幂等键重放扣两次、并发抢最后 1 件卖出两单、优惠券没落到订单明细、退款金额大于实付。
这类问题**权限全对、状态码全 200**，只断言「成功 / 失败」永远抓不到 —— 必须**回表读数再算一遍**。

**二、新增 §7b 共 32 条 + 1 条「起始自愈」守卫（82 → 115），四条口径**

| # | 口径 | 断言要点 |
|---|---|---|
| ① | **幂等重放只扣一次** | 同 `idempotentKey` 再下单 → 200 且**返回同一订单号**；回表 `t_order` 该键**恰 1 张**；`t_order_place_task` 该键 **≤ 1**；**冻结量只 +1**（不是 +2）；收尾取消订单并自证冻结回到下单前 |
| ② | **并发抢最后 1 件恰好成一单** | 现场建**只有 1 件可售**的 SKU，`Start-Job` 让两个买家**真并发**下单（顺序发两次请求测的是幂等，不是并发）⇒ 恰好一个 200、另一个被拒；回表 `frozen_stock = 1`、`stock - frozen = 0` **不为负**；收尾先 `Remove-SkuFixtures` 再回表自证 0 |
| ③ | **券分摊** | 种子券模板（状态 `ACTIVE`，满 0 减 500 分）→ 领券 → 预览断言 `pay = total + freight - discount` 且 `discount = 500` → 带券下单 → **回表读 4 个数做一次加减** + `discount == 500` + **明细合计 == 商品总额** → 收尾**先删 `t_coupon` 再删 `t_coupon_template`** 并自证都为 0 |
| ④ | **全库金额不变量** | `refund_amount <= pay_amount`；`pay_amount = total + freight - discount`；`discount <= total` |

★ ④ 的「**必须报出比对笔数**」不是形式：**「一笔都没比」与「比了、全对」在输出上一模一样**，
实测报出了「比对 16 笔已退款售后单，违例 0 笔」「比对 1142 单，违例 0 单」——没有这两个数字，
这条断言等于没跑（本项目铁律：`checked_count == 0` 判失败）。

**三、这一轮自己踩的 6 个坑（全是断言写法，不是产品缺陷）**

| # | 症状 | 根因 | 处置 |
|---|---|---|---|
| 1 | `t_order_place_task` 期望 1、实得 0 | 该表只在 `duoduo.trade.order-mode=queue` 下写行（`OrderPlaceService` 带 `@ConditionalOnProperty`），当前**同步模式恒 0 行** | 断言改 **≤ 1**（要抓的是「每请求插一条」），并注明「**0 是当前部署的正常值**」，免得下次换部署模式时被自己人误判 |
| 2 | SKU 收尾自证 `left=1` | 我只断言了「应该被删」，**没真去删** | 补 `Remove-SkuFixtures -Ids @($raceSku)` 再回表 |
| 3 | 领券 422「该优惠券已停用」 | 券模板状态我猜了 `ONLINE` | 实测 `SELECT DISTINCT status` 只有 **`ACTIVE`** —— **别猜枚举值，先查库** |
| 4 | 输出 `12900 0 0 12900 NULL[4]` | `"$oc[4]"` 里 PowerShell 把 `$oc` **整体展开再拼字面量 `[4]`** | 改 **`"$($oc[4])"`** |
| 5 | 库名报空、计数成 0、断言误红 | 把 `duoduo_fulfilment` 写成 `duodoo_fulfilment` | 改正；**拼错库名的症状是「静默空结果」**，与「这段没跑」无从分辨 |
| 6 | 券收尾删模板失败 | 已领的券还引用着模板（外键方向） | **先 `DELETE FROM t_coupon WHERE template_id=...` 再删模板**，并自证两者都为 0 |

**四、反向验证（真做了，但第一跑是假的）**
- ① **就地把 `$pvDiscount -eq 500` 改成 `501`** → 跑全量 ⇒ **恰好 1 条 `[FAIL]`（就是改坏的那条
  「★ ③ 券面额生效：优惠额 = 500 分」）+ 其余 114 全绿 + `[GUARD-OK] 断言总数守卫：115 项全部执行` + exit 1**。
- ② ★ **反向验证副本不能挪目录（第一跑就是被这个骗了）**：本脚本用 `$PSScriptRoot` dot-source
  `lib\platform-operator.ps1` / `lib\seller-fixture.ps1` / `lib\catalog-fixture.ps1` 三个辅助库。
  我第一次把副本放进 `.tmp-rev\` ⇒ `PSScriptRoot` 变成 `.tmp-rev` ⇒ **三个辅助库全部丢掉** ⇒
  夹具集体崩、几十条 FAIL（`店铺（请先完成入驻）不存在`、`phone 手机号不能为空`、**所有请求的
  `requestId` 一模一样**）—— 看上去像「产品坏了一大片」，真相是**我在测一个残缺脚本**；那次 `exit=1`
  差点被当成「反向验证成功」。
  **处置**：副本一律放回 `scripts\` 同目录（名字避开 `*smoke*.ps1` 通配，免被一键自检误收录）；
  判据不是「exit=1」，而是**「恰好那一条红 + 其余全绿 + 断言总数不减少」**。

**验证**：原脚本 **115/115、失败 0、`[GUARD-OK] 断言总数守卫：115 项全部执行`、exit 0**；
改坏副本 **114/115 + 点名 1 条 + exit 1**。

**五、夹具残留的两轮拉锯：先误诊、再定位（这一节记的是我自己的错，比事故本身更值钱）**

- **第 1 幕（事故）**：§7b 首跑时，② 并发段建的 `raceSku` **只断言、没真删**，留下 1 条 `FIX-*`
  （挂在宿主 SPU 1001 下）。而 `smoke.ps1` 恰好断言「SPU 1001 的商品详情含 2 个 SKU」⇒ 它报
  「含 2 个 SKU -> 3」，**红的是与越权毫无关系的那个套件**，归因要从 SPU 1001 的 SKU 列表倒查回来。
- **第 2 幕（误诊）**：我给越权矩阵加了「起始自愈」（开机先按 `LIKE 'FIX-%'` 查一遍、删掉），
  **但位置放错了** —— 放在 §7b 段头，而 §7 的 `Resolve-SkuFixtures` 在本段**之前**就已经现场建了
  3 个 SKU（小库存/售罄/已下架）。⇒ 自愈查到的 3 条**恰恰是本轮刚建的那 3 个**，被当成"上次遗留的"删了。
  症状极具欺骗性：**每次跑都打印「发现 3 条上次遗留的夹具 SKU」**，看着像跨套件污染，其实是自己刚建的。
- **第 3 幕（定位）**：三个手段合起来才还原真相 ——
  ⓐ `diff` 旧版快照 vs 当前脚本 ⇒ 确认只改了收尾自证那一句（**删除行为没变**）⇒ 排除"改动引入"；
  ⓑ 核行号 ⇒ `Resolve-SkuFixtures`（522 行）在自愈（588 行）**之前**；
  ⓒ 跑前/跑后各查一次库 ⇒ 跑前 **0 条**、自愈却"查到 3 条" ⇒ **那 3 条只能是本轮刚建的**。
- **第 4 幕（正确修法，两处）**：
  1. **自愈前移**到 §0 就绪检查之后、**任何夹具创建之前** ⇒ 位置对了以后，库里本来干净时它必须
     查到 **0 条**（实测 `本轮清除 0 条`）。
  2. **收尾自证改成全库口径**：原来是 `WHERE id IN ($createdSkus)`（只查"我建的那几个还在不在"），
     **看不见漏网的**；改成 `WHERE sku_code LIKE 'FIX-%'`（库里**实际**还剩什么）。
     **判据必须比两个来源**：一个是我以为我建了哪些，一个是库里实际还剩哪些。

★ 教训：**「清历史残留」的守卫放在"建完夹具之后"，就会把「本轮的」当成「上一轮的」** ——
而它打印的文案（"上次遗留"）会坚定地把你带偏。**修脏数据的代码，自己必须先站在脏数据之前。**

**六、修完后的全套回归（首次拿到 32/32 全绿）**

`scripts\run-all-smoke.ps1`：**共 32 套 / 通过 32 套 / 失败 0 套**，`EXIT=0`，耗时 **19 分 00 秒**
（另 `search-smoke.ps1` 按设计跳过 —— ES 未运行，汇总里**显式点名**，不静默略过）。
**31 套 PowerShell 合计 2212 项、失败 0**（逐套求和**实算**，不是手填）；本套件单跑 **115/0**；
上一轮被夹具残留挤红的 `smoke.ps1` 回到 **62/0**。
`docs/SELFTEST.md` §3.1 的项数与「最近一次实测」段已按本次实算刷新；顺手校准了套件数量口径 ——
`scripts/*smoke.ps1` 实为 **32 个**（排除 `run-all-smoke.ps1` 自身），原文写的是旧值 31。

### 2026-10-13（S4 延伸：上传文件卷纳入备份与恢复演练 → **顺带修掉 3 个判据层缺陷 + 反查又抓到 1 个**）

> 编号说明：本轮**不是** 附录 A 的 **S9**（S9 = 图片改对象存储，仍是长期项、未做）；
> 这是 **S4 数据库自动备份**的延伸 —— 把「用户上传的文件」也纳入同一条备份/恢复链。

**一、备份范围从「六库」扩到「六库 + 两个上传文件卷」**
`fulfilment-uploads`（商品/评价图片）与 `merchant-materials`（E12 入驻资质材料，私有卷）都是
**用户产生、不可重建**的数据。只备库 ⇒ 恢复出来是「订单在、图没了」；资质材料更是丢了不可补。
现在：`db-backup.ps1 [4b/7]` 逐文件 `sha256sum` 清单 + 复制字节进 run 目录 + `manifest.volumes` 落库
（判据 = **容器内 sha256 vs 宿主机 sha256 逐文件一致**，外加**清单指纹**）；
`ops-restore.ps1 [5b/6]` 把副本回灌到容器内**临时目录**并逐文件核对后删掉、**回读自证**。
★ 卷的真实名带 compose 项目前缀 ⇒ **按后缀反查**而不是拼名字（拼错表现为「复制出一个空目录然后宣布成功」）；
★ 演练**不写回原卷**；★ 空卷不算失败但**必须说出来**；★ manifest 缺 `volumes` 段 / 一个卷都没验成 ⇒ **判红**。

**★ 二、顺带修掉 3 个判据层缺陷（都是"实测才现形"的那一类）**
1. **心跳表用 `CHECKSUM` 判注定报红**：实测 `t_sched_leader` 的选主续租**约每 15 秒**自改一次
   （6 秒探针里就观察到跳变），而备份窗口有 30 秒 ⇒ 产物 / before / after **三者注定互不相同**
   ⇒ 备份正确却每次都红（"狼来了"）。改为维护**持续改写表**名单，名单内改判 **行数 + 主键集合**
   （主键**现查** `information_schema`），日志打 `[PASS~]` 点名；名单解析出 0 张 ⇒ 判红。
   同族：append-only 表在窗口内被插入时，`before` 行数**不**卡产物（改打 `[PASS*]`）。**宁假红勿假绿。**
2. **`rm -rf` 静默失败**：`docker cp` 落进容器的文件属主是 **root**，而 `docker exec` 默认以业务用户跑
   ⇒ 删不掉，且 `rm` 只在 stderr 说话、stderr 被 `Out-Null` 丢掉 ⇒「删失败」与「删成功」无从分辨。
   实测踩中：演练每轮都在容器 `/tmp` 留一份 root 属主的副本，**是回读自证那句 `ls -d` 把它揪出来的**。
   修法：该段统一加 `-u 0`。
3. **空串绑到 `[Parameter(Mandatory)][string]` 会抛异常**：反向验证把持续改写表清空后，指纹函数收到
   空串直接抛「无法将参数绑定到参数 Text」，且恰好炸在"判据算完、日志还没打"那一刻 ⇒ 整轮备份没了结论。
   修法 `[AllowEmptyString()]`；并补 **顶层 `trap`**（未捕获异常也要落告警 + `_ALERT.txt` 再 exit 1 ——
   备份失败**必须留下产物**）+ **单条样本判据 try/catch**（一张表炸了不该掀掉整轮）。

**★ 四、反查时又抓到 1 个（既有缺陷，不是本轮引入）：抽验的「同名表」在同一个临时库里互相覆盖**
抽验把所有抽样表都恢复进**同一个** `duoduo_backup_verify` 库，而每段 dump 都以 `DROP TABLE IF EXISTS` 开头
⇒ **跨库同名的表互相覆盖**，库里只剩最后恢复的那一张。本项目有 4 个跨库同名的表：
`t_sched_leader`（5 个库各一张）、`t_audit_sample` / `t_audit_sample_rate` / `t_proxy_work_order`（各 2 个库）。
于是同一轮只要抽到其中两个，先恢复的那张就变成「拿别人的数据跟自己比」：
- **假红**（实测）：抽到 `duoduo_product.t_sched_leader`（dump 里 2 行）与 `duoduo_marketing.t_sched_leader`
  （1 行），后者恢复在后 ⇒ 判 product 时读到的其实是 marketing 那张，报
  `行数 源2/产物1；主键集合 源指纹 c93c5710cddb / 产物指纹 92dca19ad1fb` —— 备份完全正确却报红。
- **假绿**（同一机制的另一面）：另一轮同时抽到 `duoduo_product.t_audit_sample_rate` 与
  `duoduo_user.t_audit_sample_rate`，两张表内容**恰好相同**（各 1 行 / 校验和都是 `2851413293`）
  ⇒ 即使读错了表也照样 PASS。这种「结论恰好对」比假红更危险 —— 没人会去查它。
修法：**改成「恢复一张立刻比一张」**（抽验主逻辑抽成 `Invoke-SampleCompare`，恢复成功即调用），
库里任一时刻只可能有刚恢复的那一张，冲突自然消失。

**五、给这些判据装上"防悄悄删掉"的静态守卫**
`scripts/lib/check-ops-doc.ps1` 新增 `[3/3]`：逐条断言 `db-backup` / `ops-restore` 里承诺过的
**11 条判据**都还在（trap / 名单非空 / 主键现查 / 名单自证 / 允许空串 / 样本 try-catch /
manifest 写 volumes / 缺 volumes 段判红 / 一个卷都没验成判红 / `-u 0` / **抽验「恢复一张立刻比一张」**），
**一条都没扫到 ⇒ 判违规**。
少一半判据的症状正好是「看起来还在跑、结论全绿」。新增 `-BackupScript` / `-RestoreScript` **专供反向验证**。

**验证**：备份 `[4b/7]` **2 个卷 / 129 个文件 / 2.12 MB** 双源 sha256 一致、抽验 9 张失败 0、**exit 0**；
恢复演练 产物哈希 6/6、**58 张表 / 21,831 行 / 差异 0**、上传卷 2 个 / 129 文件一致、容器临时目录已清、**exit 0**；
**反向验证三处**：清空持续改写表 → 4 条 `[FAIL] ... 稳定投影也不一致`（点名到表 + 期望/实得指纹）、
故意 `throw` → trap 落 `_ALERT.txt` + exit 1、把 5 条判据改坏 → `6/11 条在位` + 5 条
`[FAIL] ... 判据不见了`（含新增那条） + exit 1，还原后 `11/11` 且 exit 0。
**同名表修复的验证（刻意把抽样数调到 40，不等运气）**：本轮 **42 张 / 失败 0 / exit 0**，且**必然**抽到
4 个库各自的 `t_sched_leader` —— 四条各自独立通过、指纹互不相同
（product 2 行 `c93c5710cddb` / trade 1 行 `fb4f492bd2e9` / user 2 行 `3a21593c6ced` / marketing 1 行 `92dca19ad1fb`）；
同一轮里 product 与 user 的 `t_audit_sample_rate` 也各按自己的数据比过（合计 2 分 13 秒）。
按修复前的逻辑，这一轮里必然有 3 条是错的 —— 修复后全部独立成立。

### 2026-10-13（S8 后台账号管理全链路覆盖 → **挖出两个真缺陷**；云端同步装上「暂存副本新鲜度」守卫）

**一、S8：`scripts/e2e/admin-entry-ui-check.mjs` 新增 S5 段（32 → 74 项）**，覆盖改角色这条链路的四件事：
UI 正反向（`OPERATOR` 看不到「改角色」按钮；`ADMIN` 走完整流程并断言 toast 与 `.alert-success` 文案）、
接口层（403 / 冒充仍 403 / 非法角色 422 / 不存在用户 404 / 幂等 200 且审计条数不变 / 自降权 422）、
**回表**（`t_user.role` 真变更 + `t_audit_log` 落 `USER_ROLE_CHANGE`）、**并发不变式**（两名 ADMIN 同时降对方 →
恰好 1 条 200 + 1 条 422，回表 `ADMIN >= 1`）。夹具现场建、跑完自毁并断言残留 0。

**★ 实测抓到的两个真缺陷（都不是测试写错）**：

1. **服务端「平台至少保留一名 ADMIN」在并发下完全失效** —— 反向证据：两个请求同时降对方 →
   `status=200/200`，回表 `role='ADMIN'` 计数 **0**（平台 0 个管理员）。
   根因：`AdminUserService` 用 `selectCount` 统计 ADMIN，而 **InnoDB REPEATABLE READ 下 `selectCount` 是快照读、
   不加锁、看不见别人刚提交的改动** → 两个事务各读到 `count=2`，都判定"还有别人"而放行。
   修法：`UserMapper.selectAdminIdsForUpdate()`（`SELECT id FROM t_user WHERE role = 'ADMIN' FOR UPDATE`）
   —— 后到者阻塞、重扫时只剩 1 人 → 422。★ 不用 `SELECT COUNT(*) … FOR UPDATE`：它的加锁行为依赖执行计划
   （走覆盖索引可能不加行锁），**只取 id、行数即 `size()`**。修复前 S5 段 4 条红，修复后 **74/0**。
2. **`scripts/admin-smoke.ps1` 里一条「名不副实的假绿断言」** —— 原断言用 `actor == target` 去调
   「降级最后一个 ADMIN」（期望 422 / else 期望 200），但 `actor == target` 会**先命中「不能给自己降权」**
   （同为 422）→ **两条规则混为一谈、无论哪条生效都绿**，且 `else` 分支**永远不可达**。
   ★ 更本质：**「最后一个 ADMIN 不可降级」只能在并发下被走到**（能过权限门的 actor 必是 ADMIN，
   库里只剩 1 名 ADMIN 时 target 只能是 actor 自己）→ 正确覆盖点就在 e2e S5d。
   已改写为断言服务端理由文案（`$r.body.message` 含「不能给自己降权」）+ 回表未被改动。**72 → 75 项**。

**顺带校准一处口径矛盾**：`frontend/apps/admin/src/pages/admin/Operators.tsx` 的确认弹窗原写
「被改用户**重新登录后**新角色才生效」，与 E17 实际口径（**旧令牌下一个请求即失效、无需重新登录**）相反 ——
用户是在这个弹窗上判断"要不要让对方重登"，**文案必须与 notice 同源**，已改齐。

**二、#343：云端同步装上「暂存副本新鲜度」守卫**（`scripts/lib/sync-staging.ps1`，`export` / `verify` / `manifest`
三个 action + 11 份映射表；接入 `check-all.ps1 [0/5]`，CI 加 `manifest` 一步）。
**实测抓到 4 份落后**（README / PLAN / SELFTEST / 项目简报）。`N/A` 作为第三态显式报出，不冒充 PASS。
另修掉比对器的一个**假红源**：源文件里残留的 HTML 数字实体（`&#x7684;`）在云端回读时是**已解码的字符** →
两侧都先解码数字实体再归一化（`项目简报` 28 处实体曾导致 21 字符连续未覆盖被误判为缺失）。

**三、同轮其它**：S15 行尾符统一 LF（`064ddeb` + `ad8377d`）、S10 图表换肤入口加固 + 第 8 个静态守卫（`7012b10`）、
S7 网页端全站未读角标（`bd3a248`）、e2e 截图落点收口 + 第 9 个静态守卫（`7e313a5` + `c96f53f`）。

**验证**：e2e `admin-entry` **74/0**；`AdminUserServiceRoleChangeTest` **11/11**；全量 `mvn test`
**715 项 / 69 类 / 0 失败**；`admin-smoke` **75/0**；前端三站点 `npm run build` 通过；相关容器 healthy 且已拆包核验；
`check-all.ps1` 静态段全过（`N/A` 与 `PASS` 两条分支都实测过）；`check-md-bare-tick` 两形态各 0。

### 2026-10-12（S4 数据库自动备份：从「只有人工 dump」到一条能自证的命令）

**新增两个脚本**（都在 `scripts/` 下）：

| 脚本 | 作用 |
|---|---|
| `scripts/db-backup.ps1` | 六库 `mysqldump` → 产物自证 → **从产物切表恢复进临时库做三方比对** → 滚动保留 → 失败即告警 |
| `scripts/db-backup-register.ps1` | 把上面那个注册成每日计划任务；**注册完立刻试跑一次并回读结果**（注册成功 ≠ 跑得成） |

**实测（2026-10-12，Docker 17 容器全 healthy，MySQL 8.0.36）**：六库 **58 张表**、
6 个 dump 合计 **3.85 MB**、耗时 **1 分 40 秒**、退出码 **0**；抽验 9 张（随机 3 + 每个库的最后一张）
**失败 0 张**；滚动保留（`-KeepRuns 2` 连跑 2 次）后 `runs\` 下恰好剩 2 个目录；
`-BackupRoot Z:\nope` 退出码 **1** 并打印「改哪一行」的处置。

**★ 本轮抓到并修掉的三个静默缺陷**（详见 `docs/PLAN.md` §6）：

1. **PowerShell 函数返回单元素数组会被静默展开** → 调用方拿到的是字符串，
   `$r[0]` 实际取到**第一个字符**（`duoduo_user` → `d`）。不报错，症状是"六个库都说不存在"。
   写法：`return ,$res`。
2. **从 dump 里切表必须带上 preamble**：若抽中的是 dump 里的**最后一张**表，
   切片会带上收尾块 `SET TIME_ZONE=@OLD_TIME_ZONE`，而该变量只在 preamble 里赋过值 →
   `ERROR 1231`。所以现在**每个库的最后一张表永远强制入选抽验**（边界用例不靠运气覆盖）。
3. **不能只跟"恢复那一刻的源库"比**：`t_sched_leader` 是选主心跳表（每 2 分钟改写），
   校验和每次都不同 → 备份正确却天天报红。改成**三方比对**（产物 vs before vs after），
   产物与任一侧一致即通过，两侧都不一致才 FAIL 并打出三个值。

**为什么必须有**：这是全项目**唯一**有「不可逆数据损失」风险的一项；此前所有验证与修复
（含 E26 全量删号）都建立在"库坏了能从备份恢复"之上，而那个前提**只靠人工临时 dump 维持**。

**待你拍板**：备份**根目录**（脚本顶部 `$DefaultBackupRoot` 是占位默认值，**不是已定方案**）。

### 2026-10-11（文档排版修复之二：表格行的**列数**不一致 —— 3 行）

**现象**：同一批文档里还有一类排版缺陷 —— 表格**某一行的单元格数**与表头不一致，
于是那一行渲染出来会**多出一列**（表头里没有对应标题）；更严重的是，
用「Markdown → 云文档」的工具导入时，**多出来的那个单元格会被整段丢弃**。

**实测证据**：`docs/PLAN.md:2528` 的 `` `message || '...'` `` 里有一个**未转义的 `|`**，
它被 Markdown 当成了列分隔符 → 该行变成 4 格（表头 2 格）→ 导入飞书时
**「→ 登录接口…同一条修复让两边都变准确」这 35 个字被丢掉**（导入腾讯文档不受影响）。

**修复**（3 行，均为纯转义/并格，无内容增删）：
- `docs/PLAN.md:2528`：表格单元格内的 `||` 转义为 `\|\|`；
- `docs/PLAN.md:1470`：原文在代码段里嵌套反引号（非标准），改为标准写法「双反引号包裹」，
  即把 `{cat.depth > 0 ? 前缀 + cat.name : cat.name}` 这段表达式用双反引号做定界符；
  （改前的写法会让紧随其后的那一句在导入飞书时被整行吞掉。）
- `docs/agent/04-接口契约全集.md:534/621/622`：3 行把多出来的单元格并回上一格
  （`| 免 | \`keyword\` | \`{suggestion}\`… |` → `| 免 | 参数 \`keyword\` → \`{suggestion}\`… |`）。

**约定的写法**：
1. 表格单元格里出现 `|` 必须转义成 `\|` —— **包括行内代码里面**（`` `a|b` `` 要写成 `` `a\|b` ``）；
2. 代码段里要放反引号，用**双反引号**做定界符，不要用 `\``；
3. 写完表格**数一下列数**——每一行的 `|` 个数都应与表头一致；
4. **列表项的续行不要以反引号开头**（尤其是连续两个）—— 见下一节。

**排查口径**：与「表格被空行切断」是**同一族**缺陷（源文件看起来正常、渲染才现形、没有门禁）。
扫描脚本按「同一表块内各行单元格数是否等于表头」判定，本次全仓 31 份 Markdown 扫描命中 3 行，已全部修复。

**★ 补充（2026-10-12）：这一族在飞书上一共有两种形态，都已固化为同一个守卫**

上面第 2 条修完之后，云端同步时又发现这一段的**第三行被整行吞掉**
（只在飞书里丢，GitHub 与腾讯文档都正常）。逐变量排除
（换行位置 / 是否有内联代码 / 是否有嵌套反引号 / 是否加空行）后确认：
触发条件是**某一行以裸反引号开头**。因此这一段改成「不换行、也不用双反引号包表达式」，
并把判据固化进 `scripts/lib/check-md-bare-tick.ps1`（已接 `check-all.ps1` 与 CI）。

**这个守卫上线后立刻抓到第二种形态**——同样是「只在飞书现形」：

| 形态 | 触发写法 | 飞书表现 | 处置 |
|---|---|---|---|
| 一 | 列表续行**以裸反引号开头**（行首连续 ≥2 个） | **整行**被吞 | 收进同一行，或续行不以反引号开头 |
| 二 | 表格单元格里**行内代码含未转义竖线** | 该格**从竖线处被截断** | 竖线一律转义成 `\|`（反斜杠 + 竖线） |

（形态二的反例长这样：行内代码里把 `a` 和 `b` 之间直接写成竖线。此处刻意只在正文举例、
不放进表格，否则本表格自身就会被飞书从那个竖线处截断——正好是本条要防的事。）

形态二的最小复现（4 个探针表各导入飞书回读）：含未转义竖线 ⇒ **丢**；转义成 `\|` ⇒ 正常；
单元格内无竖线 ⇒ 正常；普通行内代码 ⇒ 正常。**GitHub 与腾讯文档两种写法都正常**，只有飞书不行。

★ 形态一判据必须用「**行首连续反引号个数 ≥ 2**」，**不能**用「整行反引号总数为奇数」——
十个反引号的那一行是偶数，自我配对后可全消，但它在飞书导入时**确实**会吞行。
★ 为什么形态一收紧到只判这一种：一开始按「续行以行内代码开头即违规」写，
全仓命中 **85 处**，全是正常写法（列表项说明行里引用标识符）——那种守卫会变成噪音，最后没人看。
★ 形态二则相反：全仓本就**全部已正确转义**（`\|`），命中数为 0 —— 它是「新写的内容容易踩」，
不是「存量有噪音」，因此可以放心判。
★ 形态二的实现**不能先按竖线切单元格再判**：切分本身就把那个裸竖线当分隔符吃掉了，
切开后每格都"干净"、判据恒假（第一版就这么漏判过，靠反向验证才发现）。

### 2026-10-11（文档排版修复：表格行被空行切断，共 13 处）

**现象**：`docs/PLAN.md` 有 13 处表格的**行与表头之间夹了空行**，于是那些行在**任何 Markdown 渲染器里**
都退化成「带竖线的纯文本」而不是表格行 —— 其中 §1 的里程碑表整张被切成了 **14 段**。

**为什么一直没被发现**：源文件**看起来是正常的**（行确实是 `| ... | ... |`），只有渲染出来才现形；
文档类问题没有门禁，不会自己变红。

**修复**：只删除**表格内部**的空行。判定条件是"后续 `|` 块与前面表格**列数一致**且自身不含分隔行"，
宁少改不错并。`git diff --stat` = **13 deletions**，差异内容**全部是空行**（这就是"零语义改动"的证据，
不是口头保证）。

**约定的写法**：**表格的表头、分隔行、数据行之间不要夹空行** —— 要分段请在表格前后各留一个空行。

**残留**：执行总纲里另有 3 行（E29 / E28 / 一行占位）被一整段引用块隔在表格之外，
无法靠删空行安全合并，内容完整（显示为带竖线的文本），暂不改。

### 2026-10-11（修复：E21 的 CI 冒烟随机红 —— 绑定占用了认证计数器）

**现象**：CI #104（`9b5aacd`）两个冒烟 job 都失败在「跑冒烟」步骤，
而「编译检查」job 与全部环境准备（构建 fat jar / 前端产物 / 起全栈 / 等 healthy）都是绿的。
本地当日首跑全绿，**复跑时复现**：`带正确动态码登录 → 401 TOTP_INVALID`，其后 3 条为连锁。

**根因（具体机制）**：`TwoFactorService.enable` 把"绑定用的那枚码"的时间步写进了
`totp_last_step`（当作"已用"）。而测试紧接着用**重新计算的当前步**的码去登录 ——
若绑定与登录落在**同一个 30 秒窗口**内，两次算出的是**同一枚码**，被防重放正确拒绝。
→ 落在同窗口则红、跨窗口则绿，即"本机时而绿时而红、CI 随机红"。
**产品行为本身是对的，不恰当的是"绑定占用了认证计数器"这个设计。**

**修复**：
- `enable` **不再**写 `totp_last_step` —— 绑定是配置动作，不是认证动作。
  否则用户绑完之后最自然的下一步（立刻登录验证）会被拒，且提示"该验证码已被使用"，
  用户完全无法理解。安全上不亏：重放"绑定用的那枚码"仍需同时掌握密码，窗口仅 ±30 秒；
  真正的防重放规则（**登录**成功才记步、同一枚码不得二次登录）原样保留。
- 测试侧两处加固（本次排查的直接教训）：
  ① 增加「E21 迁移已应用」的 schema 自检（`t_user` 的 4 个 totp 列 + 恢复码表）——
     缺列会让 2FA 接口**全线 500**，而这种失败形态很难一眼看出根因是少了一列；
  ② 就绪检查失败时输出**标准汇总行**，不再裸 `exit 1` ——
     裸退出会让汇总表显示 `pass=0 fail=0`，信息量为零。

**★ 方法论收获**：这次"本机绿、CI 红"**不是靠读日志定位的**（当时的凭据读不到 Actions 日志、
本机也无 pwsh 7 可复现平台差异），而是靠**在同一台机器上复跑**——第二次就红了。
事后再用一枚带 `Actions: Read` 的细粒度 PAT 拉取 #104 日志，**逐字确认**了上面的判断：
失败脚本**只有** `admin-2fa-smoke.ps1`（`pass=50 fail=4`），四条中的第一条正是
`★ 带正确动态码登录 → 200 -> status=401`，其余三条为其连锁；同轮 1148 项断言里仅这 4 条红。
而 `pass=0 fail=0` 这种"零信息量"的 CI 输出，本身就是最该先修的东西。

**结果**：#105（`03d313f`）三个作业 —— 编译检查 / 冒烟子集 / 全量冒烟 —— **全部 success**。

**另**：本地密钥统一落到 `<repo>/.secrets/`（已加进 `.gitignore`）。
教训是"只在聊天里发过的凭据用完即失"——上一轮就是因为这个，直到拿到新密钥才补上日志复核。

**验证**：修复前本地复跑 **52/56**；修复后**连跑两次 56/56**；
另起一个**全新 MySQL 容器**执行 `docs/sql` 初始化，确认全新库确有 4 个 totp 列 + 恢复码表
（借此排除"CI 全新库缺迁移"这一竞争假设）。

### 2026-10-10（E21 平台后台两步验证 + IP 白名单 —— P3 第 6 项）

**要解决的问题**：平台运营 / 管理员一个账号能审商品、关订单、改权限、看全站资金。
只靠密码的话，一次撞库或一次钓鱼就足以拿到整个后台。两步验证让「拿到密码」不再等于「拿到后台」。

**★ 强制点放在登录链路，而不是网关** —— 这是本轮最关键的设计决定：
- 已启用 2FA 的账号，登录时必须提供动态码或恢复码，否则**拿不到任何令牌**。
  没有令牌，网关本来就什么都放行不了，因此**不需要**在网关上再加一条"这枚令牌算不算数"的判断
  （判定点越少，口径越不容易分裂，也不必让 `JwtAuthGlobalFilter` 继续膨胀）。
- **刷新令牌带 `tfv` 声明**：刷新令牌有效期 7 天，若不带这个声明，
  一枚"启用 2FA 之前签发"的刷新令牌就能把这道门整个绕开。
  并且**启用 2FA 时会主动吊销该账号的全部刷新令牌** —— 声明与吊销是两道互补的防线
  （吊销清掉存量会话，声明兜住吊销那一刻正在飞行中的请求）。
  这一处**刻意不做向后兼容**：放行"无声明"的老令牌等于"启用之前的会话永远有效"，2FA 就形同虚设。
- **微信登录对已启用 2FA 的账号直接拒绝**：那条通道没有"第二步"的输入位，
  放行它等于给绑定过微信的运营账号留了一扇后门。

**★ 三个安全细节（改动时不要"简化"）**：
1. **防重放**：同一枚动态码在 ±30 秒窗口内可被重复提交，因此记录每次成功校验的"时间步"，
   并用一次**条件更新**（`WHERE totp_last_step IS NULL OR < 本次步号`）原子地推进 ——
   先读后写会在并发下把同一枚码放行两次；
2. **恢复码只存哈希、用一次作废一枚**，且**失败尝试不消耗**
   （否则攻击者可以用错误输入把用户的恢复码一枚枚耗光，比不设恢复码更糟）；
3. **已启用时拒绝重新 setup**：允许覆盖密钥等于给出一条降级路径 ——
   攻击者登录后重新生成密钥，用户的认证器就失效了。换设备必须先解绑（需验证码）。

**★ 本轮冒烟挖出一个真 bug（很隐蔽，值得单记）**：
`AuthService.login` 在两步验证通过后调用 `updateById(entity)` 更新登录时间 ——
而 `entity` 是校验**之前**查出的快照，里面的 `totp_last_step` 还是旧值。
整行回写把刚写入的"已用时间步"**静默覆盖**，于是防重放失效：同一枚动态码可以反复登录。
除"重放"那一条断言外，其余全部照常通过 —— 这正是它难被发现的原因。
**修法**：只 `set(lastLoginAt)` + `set(updatedAt)`，不做整行回写。
**沉淀**：凡在同一个请求里"既读同一行、又改同一行"，都不能用整行回写。

**IP 白名单（默认关）**：只收窄 `/api/admin/**`，只看 TCP 对端（**不**看 `X-Forwarded-For`），
支持精确地址 / IPv4 CIDR / 前缀通配（刻意不用 `InetAddress.getByName` —— 它对非 IP 字符串会做 DNS 解析）。
**开关打开但名单为空 = 拒绝全部**（失败关闭）。默认关闭是刻意的：打开它是"收紧"，
而配错方向的后果是"所有人进不了后台"，不该由一个默认值决定。

**验证**：单测 **604/0**（新增 TOTP 16 项 + 网关白名单 6 项）；
`admin-2fa-smoke.ps1` **54/54**（含防重放、恢复码一次性、刷新链路、管理员重置、收尾自证）。

### 2026-10-10（E20 收尾：全量回归暴露两类「测试没跟上正确行为」的旧断言）

E20 上线后跑全量回归（28 套，`search` 需 ES 被显式跳过），冒出 4 处红。**逐条查下来都不是产品缺陷，
而是旧断言没跟上行为变化** —— 分两类，处置方式不同：

**① 跨店越权由 403 变 404（`privilege-matrix` 3 条 + `seller-smoke` 5 条）**
- 现象：「卖家 B 看 / 改 / 下架 卖家 A 的商品」「另一卖家查看 / 发货 / 退款 订单 A」
  「复原归属后普通卖家看不到订单 A」，期望 403，实返 **404**。
- 原因：E19 写这些断言时，越权是「有权限检查、但归属不符」→ 403。E20 加了租户行级过滤后，
  越权者的请求**在 SQL 层就查不到**那一行 → 系统眼里「不存在」→ 按既定防枚举口径返 404。
- **被拒的事实没变**：`privilege-matrix` 紧随其后的「商品状态未被改动」、`seller-smoke` §6
  「卖家发货」正对照（他人那次发货确实没生效）都仍然绿。
- 顺带理顺一处**口径不一致**：`seller-smoke` 原有「不存在的订单号返回 404（与 403 区分）」，
  即刻意区分「不存在(404)」与「非你的(403)」。E20 后两者统一为 404 —— **这正是防枚举想要的**
  （否则能用 403 / 404 的差别反推订单是否存在）。
- 处置：共 8 条断言 403 → 404，并写明「**不要**写成『403 或 404 都行』」——
  那样会在真正放开成 200 时失去判别力。
- ★ **顺带全局排查**：仓里 50 多处 `-eq 403` 全部过了一遍，其余均属「角色 / 凭据 / 网关入口」类
  （`OperatorGuard`、内部令牌、E16 权限点、ent 不匹配），不经过租户过滤 ——
  `aftersale`(101/0)、`review`(61/0)、`seckill`(51/0) 本轮实测全绿即为印证。

**② 拿「这一次调度做了几张」当判据（`coupon` 1 条 + `settlement` 2 条）**
- 现象：`coupon` 的 `sweep.expired=0`、`settlement` 的 `sweep.autoCompleted=0 / fundsReleased=0`，
  但**紧随其后的终态断言全是绿的**（券确实 EXPIRED、订单确实 COMPLETED + RELEASED）。
- 原因：**定时调度抢跑**。脚本改完数据再手动触发 sweep，而后台作业在两次调用之间已经处理完，
  于是这一调用的计数自然是 0。属**时序竞态**，全量跑得慢更容易踩中 ——
  所以表现为「单跑绿、全量红」，也解释了「本机绿、CI 红」。
- 两套脚本**各自的另一条同类断言早就**这么处理过（`coupon` TTL 解锁那条注释写明
  「判据取终态而非这一次解掉了几张」），唯独这两处漏了 → 补齐为**终态判据**。
- 反向仍有力：调度真坏掉时券会停在 UNUSED、订单会停在 SHIPPED，两处必然红。

**沉淀**：
- ① 是「**行为变好了，断言没跟着改**」—— 拒绝方式升级（403 → 404）不该被当成回归，
  但也不能图省事放宽成「两者皆可」；
- ② 是「**拿易失的过程量当判据**」—— 凡有后台定时任务参与，断言要钉**终态**，
  而不是「这一次调用的计数」，否则测试会随运行时长随机红。

**修后验证**：`privilege-matrix` 82/0、`seller` 99/0、`coupon` 145/0、`settlement` 125/0；
静态守卫 48 脚本 PASS；全量 28 套全绿（`search` 因 ES 未启动被显式跳过）。

**E20 反向验证**（交付前最后一道）：临时注掉 `ProductServiceConfig` 里的数据层租户注册并重新部署
→ 跑 `tenant-isolation-smoke` **恰好红 §3 那 3 条**（「声明租户=0 只看自营」「=第三方只看自己」
「=空店铺一条都看不见」），而**正对照「未声明租户 → 两个都看得见」保持绿** ——
正说明「只有正对照会在拦截器失效时假绿」的设计意图成立；随后还原、重建部署，复跑 **37/37**。

### 2026-10-10（E20 租户隔离硬化 —— 从「谁在操作」到「这些数据属于谁」）

**目标**：E19 管的是「谁**能不能调**这个接口」，E20 管**行级**：「同一张表里，这一行属于哪个店铺」。

**★ 关键口径纠正（先做，否则后面全错）**：`seller_id` / `tenant_id` 存的是**店铺主体 userId**
（`t_user.id`，即 `t_merchant.owner_user_id`），**不是 `t_merchant.id`**。实测 8/8 命中 `t_user.id`、
0/8 命中 `t_merchant.id`。而 `SellerOwnership` 的类注释写的是「值即 `t_merchant.id`」——**注释说反了**
（代码本来写的就对）。这条必须先纠正：**照注释去加租户列，就会加错维度**。

**带租户列的表：10 张物理表 / 9 个表名**（`information_schema` 实算）：product `t_spu`/`t_listing`/`t_audit_sample`、
trade `t_order`/`t_seller_debt`/`t_settlement`、fulfilment `t_after_sale`/`t_review`、
user `t_audit_log`（**唯一叫 `tenant_id` 的**）/`t_audit_sample`。`t_audit_sample` 在 product 与 user **各一张、同名列**，
所以「表名 → 列名」的映射是安全的 —— 但这条前提必须**写在代码里并被断言**（`sharedTableNames()` /
`physicalTableCount()`）：哪天出现「同名不同列」，后写的 `put` 会静默覆盖先写的，其中一张表就被按错误的列过滤。

**落地的四层**（失败方向全部指向「可用」，不指向「打不开」）：
1. **口径层** `duoduo-common/tenant`：`TenantContext`（`ThreadLocal` + 对外只暴露 `Optional`；
   **0 是有效租户**＝自营，未设置＝不追加条件 → 纵深防御的第二层，不是唯一防线）、
   `TenantTables`（表→列的唯一权威 + 表名归一 `T_SPU` / 反引号包裹 / `db.t_spu` 三种写法都要命中）、
   `TenantKeys`（Redis 键统一前缀 `duoduo:tenant:`，现有 4 个键是**收口**而非改名）。
2. **传播层**：访问令牌加 `tid` 声明（**刷新令牌刻意不带**，刷新时**重新解析** —— 「租户＝你**现在**属于谁」，
   人可能已被移出店铺）；网关**先剥离客户端同名的 `X-Tenant-Id`、再按 `tid` 写入**，
   且 `tid` 为空时**不写这个头**而不是写 `0`（写 0 会被当成平台自营，是最宽的桶）；
   `TenantContextInterceptor` 注册在 `UserContextInterceptor` **之后**、`AuditInterceptor` **之前**
   （审计记录的租户维度读的就是它），`preHandle`/`afterCompletion` **两处**清理。
3. **数据层**：MyBatis-Plus `TenantLineInnerInterceptor` 按服务**白名单接入**（product/trade/fulfilment/user 各申报自己那几张表），
   且**必须排在分页插件之前**（反了 → 分页 count 语句漏掉租户条件，列表与总数不一致**还不报错**）。
   非法 `X-Tenant-Id` → **400 响亮拒绝**，而不是「悄悄不加条件」。
4. **搜索层**：ES 文档写入 `sellerId` + 检索追加租户过滤。**今天所有既有链路都不生效**
   （商家端「我的商品」走 MySQL；买家请求本就无租户上下文），价值是**让「将来接 ES」成为安全的默认** ——
   因此只做源码级静态断言 + 文档约束，不假装它现在拦得住什么。

**验证**：新增 `tenant-isolation-smoke.ps1` **37/37** ＋ `TenantContextTest` 9 项单测
（含「0 是有效租户」「线程隔离」「表名归一」「恰好 9 个表名 / 10 张物理表」）＋ 全仓单测 **588/0**。

**★ 两条测试纪律（本项踩到并固化）**：
- **验数据层必须直连服务端口**：走网关时 `X-Tenant-Id` 由网关按令牌写入、伪造的头会被剥离，
  「拦截器有没有生效」根本测不到（E16 踩过同一个坑）。且探针要选**自身零过滤**的接口
  （`/api/products/internal/cards` 走 `selectBatchIds`），否则红与绿都证明不了拦截器在不在。
- **每条「被隔离」必配一条「未声明时可见」的正对照**：否则「查询恰好返回空」也能看起来像隔离成功。

### 2026-10-10（#277 续 2：全量冒烟首次真跑起来 —— 27 套里 5 套红、四个根因）

**进展**：`18be1a8` 触发（`38039084959`）：`编译检查` ✅、**`冒烟子集` 首次 success**（此前一直 failure ——
三个修复确实生效）；只剩 `全量冒烟` 红，但这次它**真跑起来了**：汇总表从「26 套 (无汇总行)」变成
**共 27 套，通过 22 套，失败 5 套**。

| # | 问题 | 为什么现在才现形 | 修法 |
| -- | -- | -- | -- |
| ⑤ | `mq-smoke` 32/13，**并连带** `order-smoke` 67/10、`payment-smoke` 64/8 | `$env:TEMP` **在 Linux 上不存在**（Windows 才有），`Join-Path $env:TEMP …` 全部抛 `Cannot bind argument to parameter 'Path' because it is null` → **限流段 30 发请求一发都没发出去**（`limited=0`/`ok=0` 的假红灯）；更糟的是临时文件没清、**冻结没释放**，于是 `order`/`payment` 的「可售库存 316 vs 320」「冻结量从 0 开始」全跟着红 —— 又是「一个上游故障让它后面所有问题都不可见」。而 `mq-smoke` **不在 `SMOKE_SCRIPTS` 里**，所以子集永远是绿的：表现为「本机绿、子集绿、只在全量红」 | 改 `[System.IO.Path]::GetTempPath()`（两平台都对）；`mq-smoke` 纳入 `SMOKE_SCRIPTS` |
| ⑥ | `mq-smoke` ★首条任务 30s 内未到 `PLACED` | 该套**自管环境**时会把 trade 重建为 queue 模式 —— JVM 全新 + RocketMQ 消费者组正在 rebalance，**第一条消息的延迟是冷启动**，不是链路延迟。本机消费者早热了 → 本机绿、CI 红，且红在 **SLA 断言**上，看上去像「削峰链路变慢了」，会把人往错误方向引 | 加**冷启动预热**：用一条**独立买家**的任务把消费者焐热（预算 180s、不计入 SLA），随后取消它释放冻结；§4 的 30s SLA 断言语义不变，只是不再把冷启动算进去。**用独立买家**是为了不在正式买家的订单列表里留订单（否则「订单已产生且为 1 条」被顶成 2，把环境问题伪装成计数问题） |
| ⑦ | `coupon-smoke` 红 1：`① 时间字段为 ISO8601 UTC（以 Z 结尾） -> 10/10/2027 08:54:14` | **与 ④a 完全同一个** pwsh 7 日期转换坑（`ConvertFrom-Json` 把 ISO 串自动转 `[datetime]`），只是这条先前没人跑到 | 改为断言**原始报文**里**所有** `endAt` 都符合 ISO-8601+Z（不是只挑一个，而是全部合规） |
| ⑧ | `seckill-smoke` 红 1：`编辑后结束时间已改 -> 10/10/2026 09:44:47` | 同上 | 同上（改断言原始报文） |

**★ 顺手把这一「类」坑做成永久守卫**：新增 `scripts/lib/check-smoke-static.ps1`，并接进 CI
**编译检查 job 的第一步**（纯静态、比编译还便宜），两条规则在编译之前就能红：

- **规则 1：`Check` 的判据不得是「裸的比较表达式」。** 实测
  `Check "x" (管道).Count -eq 0 "extra"` 会被 PowerShell **静默误绑定**：`$ok` 拿到的是 `(管道).Count` 这个数、
  `-eq 0` 被吞掉，于是「四个令牌全在」照样打印 `[FAIL]`（今天新写的租户套件就踩了这一条，且**不报任何错**）。
  而仓库里常见的 `Check "x" ((f) -eq 'str')`（整体再包一层括号）**是安全的**
  （实测不等时返回 `False`，不是「永远为真」）——**不要为了统一风格去改这些**。
- **规则 2：`.ps1` 必须带 UTF-8 BOM。** 这是项目铁律（PS 5.1 会把无 BOM 的 UTF-8 按 ANSI 解码 → 中文全乱码，
  而「断言里的中文」往往正是断言文案与被匹配的字符串），但**此前没有任何自动校验**。顺手给唯一漏网的
  `scripts/docker-mirror.ps1` 补上。规则**只扫我们自己的脚本**（排除 `scripts/e2e/node_modules` 里
  Playwright 自带的 9 个 `.ps1`）。

**反向验证**：拿一个故意违规（无 BOM + 裸比较表达式）的假仓库跑守卫 → **恰好 2 项违规、exit 1** 并给出改法；
对真仓库 → 48 个脚本全绿。**失败方向也确认过**：不加外层括号的写法（`(F) -eq '0'`）是**响亮报错**，不会静默通过。

**验证**：本地逐套复跑 —— `coupon 145/0`（原 144/1）、`seckill 51/0`（原 50/1）、`mq 48/0`（原 32/13）、
`order 77/0`（原 67/10）、`payment 72/0`（原 64/8）。

### 2026-10-10（#277 续：修完两个根因后，"起全栈"首次通过 —— 随即暴露三个「只在 CI 上才现形」的问题）

**进展**：`2f99f14` 触发的运行（`38037780298`）：`编译检查` ✅；**「起全栈」首次走通**
（`构建前端三站点产物` 成功执行、空库初始化跑完）→ 失败点前移到「跑冒烟」。也就是说
#277 的两个根因确实修掉了，剩下的是**此前被它们挡住的、从来没跑过的部分**。

| # | 问题 | 为什么现在才现形 | 修法 |
| -- | -- | -- | -- |
| ③ | **全量冒烟 26 套全部「(无汇总行)」** | `run-all-smoke.ps1` 第 50 行写死 `& powershell.exe` —— 这个可执行文件在 ubuntu runner 上**不存在**，每套脚本都以 `The term 'powershell.exe' is not recognized…` 结束。之所以能藏住：全量 job 此前一直死在「起全栈」那一步，**一个上游故障会让它后面所有的问题都不可见** | 按平台选 shell（`$IsLinux/$IsMacOS/$env:CI` → `pwsh`，否则 `powershell`），与已验证跑通的 `ci-run.ps1` 同口径；顺带对齐日志编码与两种历史汇总格式 |
| ④a | `logistics-smoke` 红 1 条：`① 发生时间是 ISO-8601（UTC，以 Z 结尾） -> 10/10/2026 08:32:20` | 断言对**解析后的对象**做正则：pwsh 7 的 `ConvertFrom-Json` 会把 ISO 串**自动转成 `[datetime]`**，`-match` 按 InvariantCulture 把它变成 `10/10/2026 08:32:20`；而 Windows PowerShell 5.1 不转 → **同一条断言本机绿、CI 红**。它测的是"客户端解析成什么类型"，不是"服务端按 UTC 下发" | 改为对**原始报文**断言 `"occurredAt"\s*:\s*"…Z"` —— 测线上协议，两种 PS 版本行为一致 |
| ④b | `privilege-matrix-smoke` 红 1 条：`★ 拒绝文案指向入口 -> (空)` | pwsh 7 取回的**错误响应体是重新序列化过的文本**（缩进 + 非 ASCII → `\uXXXX`），于是 `$r.raw -like '*入口*'` 恒为假 —— 而服务端文案完全正确（`message` 解出来就是「当前登录入口无权访问该接口」） | 改为断言**解码后的字段** `$r.body.message` |

**★ 顺带堵掉一条同类假通过**：`address-smoke` 的 `$r.raw -notmatch '不是你的|无权|他人'` 在 pwsh 7 上
**必然通过**（整句话都是中文，转义成 `\uXXXX` 后一个关键字都匹配不上）—— 一条想拦泄露、却永远拦不住的断言。
已改为断言 `$r.body.message`，并**额外要求文案非空**：body 解析不出来时必须报红，而不是"空串不含关键字"静默通过。

**一句话教训**：**断言要测服务端的输出，不要测客户端解析之后的对象。** 前者是契约，后者是 PowerShell 的类型推断。

**验证**：本地三套 `logistics 79/0`、`matrix 82/0`、`address 82/0`。

### 2026-10-10（#277 GitHub Actions「两三页全红」定位与修复 —— 两个「只有空库 / 干净检出才现形」的坑）

**症状（API 实测）**：仓库共 **99 次运行、97 次失败**，最后成功停在 `2026-09-27 02:50`，
从 `feat(social): 新增社交域` 起再无一次绿。★ 关键判据：**`编译检查`（后端 10 模块 + 前端 tsc/vite）始终全绿**
—— 主线代码没问题，红的恒定是「**起全栈**」那一步。

| # | 根因 | 为什么平时看不见 | 修法 |
| -- | -- | -- | -- |
| ① | `02-duoduo-product.sql` 归属回填写成 `SET seller_id = (SELECT … phone=…)`：**查不到人返回 NULL**，而 `seller_id` 是 `NOT NULL` → `ERROR 1048`，全新初始化在此中断（引用的 3 个手机号在 `01-duoduo-user.sql` 里根本不存在，是「测试账号清零」留下的死引用） | **本机是老库、数据早就在 → 本地永远看不到；CI 每轮空库 → 每轮必炸**，症状只表现为「compose up 失败」（行号 257→289→337 一路漂移，说明一直没修） | 改 `UPDATE t_spu s JOIN t_user u ON u.phone=… SET s.seller_id=u.id`（有账号就回填、没有就跳过），`incremental/15` 同步 |
| ② | `frontend/Dockerfile` 刻意**不在容器内构建**，直接 `COPY apps/${APP}/dist`；而 `frontend/.gitignore` 排除 `dist/` → 干净检出没有 dist → `"/apps/mall/dist": not found` | 本机 dist 建过一次就一直躺着；CI 的 smoke / smoke-full 只跑 `mvn package` 就起全栈，**从没构建前端** | 两个 job 在起全栈**前**各加 `Set up Node 20` + `npm ci && npm run build`；并把 `privilege-matrix-smoke.ps1` 补进 `SMOKE_SCRIPTS` |

**根因①的独立验证**：`docker run --rm mysql:8.0.36` 挂 `docs/sql` 做**全新初始化** → 00–06 全部跑完、
`ready for connections`、零 ERROR（顺带确认 `incremental/` 被 entrypoint 有意忽略）。

**顺带修掉 E19 自身的「跨套件污染」**：E19 用了 `Resolve-SkuFixtures`（在自营 SPU 1001 下「缺啥建啥」）
却**从未调用 `Remove-SkuFixtures`** —— `catalog-fixture.ps1` 文件头恰好写着「夹具越跑越多正是历轮把种子库搞乱的老路」。
后果：**`smoke.ps1` 的「商品详情应有 2 个 SKU」被挤红（实得 4）**，红的是那个与越权毫无关系的套件。
§9 收尾由「仅下架」改为**真删除 + 自证**；`address-smoke` 的前置断言改为**打印占用该 SKU 的未关闭订单**。

**★ 一个值得记的插曲**：新加的「收尾自证」断言第一次跑就红了（`left=3 created=3`），
它抓到的是**我自己**漏写的一行 —— **断言的价值恰在于把「我以为我删了」变成会红的机器结论。**

**验证**：矩阵 **82/82**、`smoke.ps1` **62/0**、`address-smoke` **82/0**；库内 SPU 1001 恰好 2 个 SKU、夹具全归零。

### 2026-10-10（E19 · 权限越权回归矩阵：把"防住了什么"变成机器结论）

**为什么要这一轮**：E16 定了「谁能做什么」的唯一口径、E17 让它即时生效、E18 让它留痕 ——
但三者只保证**机制本身**成立。「机制真的被用在了每一个该用的地方」没有任何机器结论，
而三类缺口的失败方向**全部指向「静默可用」**，日常点页面点不出来：

| 缺口 | 表现 |
|---|---|
| 注解写漏 | 端点静默变成公开：接口 200、无异常、**无任何日志** |
| 归属校验写漏 | 别人的数据可读可改，页面照着正常渲染 |
| 入口裁剪写漏 | 跨端令牌能进对面站点，直到被人拿去用才发现 |

更要紧的是**没有单一出口**：断言此前散在 seller / order / audit-sla / audit-log 四套件里，
想回答"现在到底防住了哪些越权"得翻四份日志 —— **回答不了的问题等于没在守**。

**交付**：`scripts/privilege-matrix-smoke.ps1` —— 一条命令给出整张矩阵。
被 `scripts/run-all-smoke.ps1` 与 `scripts/check-all.ps1` **按文件名通配自动收录**，
所以"纳入一键自检"不需要在任何地方登记（写死数量的做法会在加新套件后变成假话）。

| 节 | 模块 | 越权断言要点 |
|---|---|---|
| §1 | 收货地址（user 域） | 改 / 删 / 设默认**他人**地址 → **404**（刻意不区分"无权限"与"不存在"，防 ID 枚举），并回库确认目标**确实没被删** |
| §2 | 商家子账号（user 域） | **纵向**：店员拉成员名单 / 邀请店员 / 停用成员 → **403**；**横向**：跨店停用店员 → 404；平台运营在**店主侧**拿不到（走统管端点） |
| §3 | 后台入口（网关层） | 商家令牌进 `/api/admin/**`、买家令牌进 `/api/seller/**` → 403，且文案指向**入口**而非笼统没权限 |
| §4 | 服务端判权（**直连服务端口**） | 注解式 + 命令式两条路径 × USER / SELLER / OPERATOR 三态；**同一个 `userId`（真实店主）只把角色写成 USER → 403**，差异只可能来自角色判定 |
| §5 | 商品（product 域） | 跨店看 / 改 / 上下架 → 403 **且回库确认状态未被改动**；E28 审核**双路各建一个商品**：干净内容 `AUTO` 自动放行（**断言 `audit_at` 已写**）vs 标题含手机号转人工 `PENDING`（`audit_source`/`audit_at` 都清空） |
| §6 | 审核台（product 域） | 商家令牌 / 买家令牌 → 403；**直连**：USER → 403、**卖家本人不能自审自己的商品** → 403；正对照：运营审核通过 200 → 过审后买家可见 |
| §7 | 订单（trade 域） | 买家跨用户查 / 取消 → 404；**自营订单 `seller_id = 0`**：卖家列表里看不到、发货被拒 |
| §8 | 审计日志（user 域） | 买家 / 商家 → 403；运营 → 200（E18 刻意开放，边界在"不可删改"）；对审计发起 DELETE → 非 2xx |
| §9 | 收尾 | 运营降权 + 清审计行 + 全库 OPERATOR 数不增 + 夹具商品下架 + **断言总数守卫** |

**★ 反向验证（这一轮最有价值的一条证据）**：临时注释掉 `SellerProductService.audit()` 里的
`OperatorGuard.requireOperator()`，重打包部署 → 矩阵 **77 通过 / 2 失败**，
红的**恰好**是那两条服务端直连断言；而**网关层那两条依旧是绿的**（入口先挡住了）。
也就是说：**如果这张矩阵只测网关层，这次"判权被拆掉"将完全无感。**
随后代码原样还原（`git diff` 为空）、重新打包部署、复跑 **79/79 全绿**。

**★ 本轮撞过的三个坑（脚本自身缺陷，都不是产品缺陷 —— 但都会造成假通过）**

| 坑 | 为什么危险 | 修法 |
|---|---|---|
| 夹具取错字段（邀请店员响应是 `data.memberId` 而非 `data.id`） | 取错**不报错**，只会让后续调用打到 `/api/seller/staff//disable` 拿回 **404** —— 而 404 与 403 在这里**都像"越权被挡住了"** | 取对字段；断言名里写明期望的确切状态码 |
| 断言锁在旧口径上（按 E14 语义断言"第三方提交必为 PENDING"） | E28 已把它改成**默认放行**，真相是 `AUTO / APPROVED`；把期望值改宽只会让断言失去意义 | **两条路各建一个商品**：干净内容走 `AUTO`（并断言 `audit_at` 已写）、含联系方式走转人工 —— 断言反而比原来更强 |
| `POST /api/orders/{no}/cancel` 不带 `Content-Type` 先撞 **415** | 越权断言会拿到一个与权限完全无关的状态码 | 传一个空 JSON 对象，让 403/404 成为唯一可能的结果 |

**与既有套件的覆盖关系（刻意保留的重叠）**：seller-smoke 的商品归属 403、order-smoke 的
跨用户订单 404、audit-sla-smoke 的 E16 直连三角色、audit-log-smoke 的审计角色对照 ——
这几处重叠是**有意保留**的：矩阵要能被单独读懂，不能要求读者先去翻另外四份日志才能拼出一张表。

### 2026-10-10（E18 · 后台操作审计日志：一次后台写操作 = 一条不可删改的记录）

**为什么要这一轮**：E16 把「谁能做什么」收敛到权限矩阵、E17 把「什么时候生效」收敛到下一个请求，
但**「发生了什么」一直没留痕**。后台 37 个写端点（改角色、强关订单、退款裁定、审核改判、资质停业……）
此前只有零星几处手写 insert：覆盖不全、字段不一，而且**漏掉最该记的那类 —— 被拒绝的尝试**。
越权被拒、参数非法被 422 挡下，恰恰是追责时最想知道的两件事。

**为什么是「拦截器 + 注解」而不是 AOP**：项目本来就没有 `spring-boot-starter-aop`。
为一条横切关注点引一套代理机制，代价是启动期多一层代理、排查时多一层调用栈；
而 `HandlerInterceptor` 已经在用（`UserContextInterceptor`、权限拦截器），加一个同族成员**零新依赖**，
并且**能拿到响应状态码** —— 这恰恰是 AOP 拿不到、而又必须看的东西（见下）。

**★ 四个写错就一定出事的点**（每条都有对应断言 + 反向验证）

| # | 点 | 写错的后果 |
|---|---|---|
| 1 | **审计拦截器必须注册在权限拦截器之前** | Spring 只为 `preHandle` 返回 `true` 的拦截器回调 `afterCompletion`；顺序反了，「越权被拒」这条最该留痕的记录**压根不会产生**（而且没有任何报错） |
| 2 | **失败判定必须同时看响应状态码** | 业务异常被 `GlobalExceptionHandler` 消费之后 `ex == null`，只看 `ex` 会把**被拒绝的操作**记成 `SUCCESS` |
| 3 | **不可删改的判据是类目**（`SECURITY` 权限/身份、`FUNDS` 资金），**解析失败折叠到最严档** | 解析失败若折叠成 `OTHER`，一条本该锁死的记录就悄悄变成了"可清理"的 |
| 4 | **枚举名即落库值**：E18 之前已落库的 9 个动作标识必须逐字不变 | 改了就是历史检索分叉成两套词表，而且没人会发现 |

**★ 审计是旁路，不是业务链路的一部分**：写入失败只打 ERROR、**绝不外抛**
（记账失败不该让"改角色"这件事失败）；跨域（product / trade / fulfilment）经 Feign 转发，
**转发失败降级写本域日志** —— 宁可落在"错的库"里，也不能丢。另外时间戳解析失败时**用接收时刻兜底**，
不因为一个格式错误丢掉整条记录。

**实现**

| 位置 | 内容 |
|---|---|
| `duoduo-common` | `AuditAction`（39 个动作标识）/ `AuditCategory` / `AuditOutcome` / `AuditLogRecord`（超长**按码点**截断 —— `substring` 会把代理对切半）/ `@Audited` / `AuditTrail`（ThreadLocal 上下文 + 唯一抑制口 `skip(reason)`）/ `AuditSink` |
| `duoduo-common-web` | `AuditInterceptor` |
| `duoduo-user` | `AuditLogService`（**唯一写入口 + 检索**）/ `AdminAuditLogController` / `AuditLogInternalController`（跨域转发）/ `LocalAuditSink` |
| product / trade / fulfilment | 各一个 `AuditLogClient`（**`contextId` 必须唯一**，同名会启动崩溃） |
| DDL | `docs/sql/incremental/31-e18-audit-log.sql`（14 列 + 4 索引）。**存量 188 行全部落 `OTHER/SUCCESS/0`** —— 新增维度不让存量数据变形 |
| 前端 | 后台「操作审计」页 `/admin/operation-audits`：**只读、连操作列都没有**；不可删改标记**由服务端下发布尔值**，前端不自行判断 |
| 网关 | `/api/admin/audit-logs` **显式**路由（网关按子路径枚举，不走 `/api/admin/**` 兜底；Ant `/**` 也不匹配无尾斜杠的集合端点） |

**★ 本轮修掉 3 个真实/潜在缺陷**（都不在"写新功能"的路径上，是测试把它们挖出来的）

1. **`AuditLogService.search` 打开页面就 500**：`.eq(hasText(x), col, x.trim())` ——
   **Java 会先求值所有实参再调用**，所以不带动作过滤（= 打开审计页的**默认查询**）时
   `x.trim()` 直接 NPE。修法：先算成局部变量。单测新增「只给时间范围不许 NPE」回归用例。
2. **冒烟脚本假通过**：`@($resp.body.data.items).Count -gt 0` 在请求失败（`$null`）时等于 1
   → 三条断言**无条件通过**。修法：加统一的 `Items()` 守卫兜底。
3. **`docker compose build` 用旧 jar 打新镜像**：根 Dockerfile 用 `ARG JAR_FILE` 复制宿主机 jar，
   而我只跑了 `mvn test` 没跑 `package` → 43 秒构建完成，镜像里是旧代码。
   铁律重申：**改完代码必须先 `clean package` 再 build 镜像，验收必须拆包看内容**。

**验收（全部实测）**

- 单测 **579/0**（新增 72：common 39 / common-web 15 / user 18）
- **4 组反向验证全部「恰好红对应条数」且集合互不相交**：只看 `ex` 不看状态码 → 红 3；
  类目未知折叠到 `OTHER` → 红 4；`AuditTrail.start` 不覆盖 → 红 1；`record` 外抛 → 红 1
- 冒烟 `scripts/audit-log-smoke.ps1` **58/58**（含断言总数守卫）。5 条静态断言：
  历史 9 个标识逐字保留 / 全仓无审计表 `UPDATE`·`DELETE`·`TRUNCATE` / `AuditLogMapper` 无 update·delete /
  **后台写端点 `@Audited` 覆盖率 37/37** / 只有 `AuditLogService` 与 `LocalAuditSink` 触碰 Mapper。
  10 组端到端：成功留痕 12 字段核对、越权被拒留痕为 FAILED、422 记 FAILED、幂等不新增、
  按人/动作/类目/目标检索、时间参数边界、判权 403、immutable 双向
- CI：`SMOKE_SCRIPTS` 13 → **14 套**
- 前端：`tsc --noEmit` 三端全绿、`vite build` 通过、两个产物校验工具全绿、
  三站点真实站点验证（`/admin/operation-audits` 深链 **200**，且路由确实在线上产物里）

### 2026-10-09（E17 · 权限即时生效：角色变更从「最长 15 分钟」收敛到「下一个请求」）

**这一步在做什么**：访问令牌里带的是**签发那一刻的角色快照**。改完库里的角色，对方手上的旧令牌
仍然写着旧角色，直到自然过期（15 分钟）才收敛。升级场景下这是「不方便」，
**降级场景下这是「权限收不回来」** —— 一个刚被撤销运营权限的账号，还能继续以更高角色调后台接口。

E17 给令牌加一个版本号 `pv`（权限版本），任何权限相关的变更都让该用户的版本 +1，
网关比对「令牌里的版本」与「当前版本」，**不一致即 401**，客户端刷新一次就换到新角色。

**① 为什么不是"改角色时把令牌全吊销"**：那是 E17 之前的口径（第五十八轮 P1-6），只能保证
「不能静默续期」，旧访问令牌仍带旧角色活满 15 分钟 —— **窗口没关，只是不再延期**。
而且它把人**强制踢去重新登录**。现在改成版本号精确收口：窗口 ≈ 0，且**无需重登**。
`AdminUserService.changeRole` 现在只在**广播失败**时才退回吊销（见 ③）。

**② 权威值在库里，Redis 只是"感知副本"**（`duoduo-common` 的 `PermVersion` 是唯一口径）

| 组件 | 位置 | 作用 |
|---|---|---|
| `t_user.perm_version` | 用户库（`docs/sql/incremental/30-e17-perm-version.sql`） | **权威值**：可审计、重启不丢、与变更同事务 |
| `duoduo:perm:ver:{userId}` | Redis（用户服务写、网关读） | **感知副本**：网关不连业务库，读它做比对 |
| `pv` 声明 | 访问令牌 | 签发时从库内现读；**刷新令牌刻意不带** —— 若刷新也校验，版本过期时连刷新都做不了，客户端会陷入「401 → 刷新 → 401」死循环 |

★ **键名 / TTL / 解析规则只允许有一处定义**（`PermVersion`）。写的一方在用户服务、读的一方在网关，
键名写错的两种情况下**都不会报错**，只表现为「权限变更没即时生效」——正是本机制要解决的问题本身，
却比不引入它更难排查。单测把键名钉成**字面量**（改动即红，逼人停下想一次"线上那些键还要不要认"）。

**③ 三类失败/边界情形的处置（这是本机制唯一的新失败面，逐条写死）**

| 情形 | 处置 | 理由 |
|---|---|---|
| Redis 里**查不到**当前版本（不可用 / 键过期 / 值不是数字） | **放行** | 失败方向要指向可用：一次缓存抖动不该等于「全站已登录用户集体掉线」。代价只是退回 E17 之前的行为（旧令牌最多再多活 15 分钟） |
| 变更时**副本写不进去** | **退回吊销刷新令牌**，并把结果如实写进响应文案 | 不退回就会出现「运营界面写着权限已收回、实际旧令牌还能用」的假象。宁可让人重登一次 |
| 副本**落后**（写失败 / 被人删了） | **签发时自愈**：每次登录 / 刷新 / 重签都按库内最新值覆盖写一次 | 「落后」最多持续到对方下一次刷新，不会永久卡住 |

★ **Redis 不可用时为什么要冷却**（`PermVersionPublisher`，与 AI 域 `IpRateLimiter` 同一考虑）：
连不上时每次都去等连接超时，会直接叠到**登录**延迟上 —— 用户感到"登录变慢了"，而这只是为写一个数字。

**④ 网关侧：401 而不是 403，且 code 单独区分**

- **必须 401**：401 才是「你的身份凭证需要换一张」，前端据此自动刷新并重放请求；
  403 是「你是谁我清楚、这事你不该做」，前端**不会**刷新 —— 写成 403 的结果是用户得手动重登，
  正好丢掉 E17 想要的「无需重登」。
- **code = `PERM_VERSION_STALE`**：401 的默认文案是"登录已过期"，容易被误当成账号有问题；
  带独立 code 之后，排障时一眼能分清「权限被改了」与「令牌真过期了」——两者的处置完全不同。
- **老令牌（无 `pv`）不校验**：与 E8 的 `ent` 同一姿势 —— 加字段不能等于上线当天把在线用户全踢下线。
  这条也让兼容窗口内**零成本**（连 Redis 都不读）。反过来说，把「没有声明」当成版本 0 是**最危险**的错法：
  所有老令牌会因「0 ≠ 当前版本」被判过期。
- **判定与 I/O 分离**：`PermVersionChecker` 返回 `Mono<Optional<Long>>` 而不是「可空值 / 空 Mono」。
  ★ Reactor 里 `Mono.empty()` 是一个**会被 downstream 跳过**的信号，用它表示"未知"会让"放行"这条分支
  根本不执行（请求既不通过也不被拒，直接挂住）；用 `Optional` 把"未知"变成真实信号值就没有这个歧义。

**⑤ 有意不做的两件事**

- **命令式调用点（约 55 处 `requireOperator()`）不逐个改成注解**：改名制造大面积 diff 与漏验证风险，
  收益为零 —— 判权口径在 E16 已经收敛到矩阵一处，E17 改的是"生效时机"，与它正交。
- **不做本地缓存**：哪怕只缓存 1 秒，也会直接削弱本机制要保证的「下一个请求即生效」。
  宁可每个已认证请求多读一次 Redis（`timeout` 150ms 硬上限，只在 Redis 假死时出现）。

**⑥ 顺带修掉的一个"看起来更安全"的陷阱**：`changeRole` 原来的吊销逻辑是**无条件**执行的。
E17 之后若顺手保留它，表现就是「版本收口已经生效了，却还是把人踢去重登」——
**这行代码是很容易被当成"多加一道保险"而加回来的**，所以单测里专门有一条
`广播成功时不吊销刷新令牌`（反向验证时恰好红它 + 提权那条，共 2 条）。

**验证（全部实测）**：
- 单测 **506/0**（新增 31：`common` +14 / `gateway` +7 / `user` +10）；**三处反向验证**，均"恰好红对应条数"：① 网关不比对版本 → 恰好 2 条（两处"应被拒"）；② 无条件吊销刷新令牌 → 恰好 2 条；③ 无版本声明当成 0 → `common` 2 条 + `gateway` 1 条；
- 新增冒烟 `scripts/perm-version-smoke.ps1` **34/34**（含断言总数守卫），并纳入 CI 子集（**13 套**）；
- ★ **对照式断言**：`scripts/lib/legacy-jwt.mjs` 独立用 Node 按 JWT 规范手签两枚令牌，
  **只差一个 `pv` 声明** —— 无 pv → 403（放行到服务端判权），pv 落后 → 401（被网关拦下）。
  差异只可能来自 `pv`，排除了"另找一枚旧令牌"时其它字段干扰的可能。
  ⚠️ 签名算法**从一枚真实令牌的头部读出来**，不写死 HS256：jjwt 的 `signWith(key)` 会按密钥长度
  自动挑最强算法（本项目默认密钥 54 字节 → 实际是 **HS384**），写死 HS256 会导致一律验签失败，
  而那种失败看起来像"网关拒绝老令牌"，会把兼容性问题误判成别的东西；
- 另：本轮实测暴露并记住的打包陷阱 —— `mvn -pl <模块>` **不带 `-am`** 会用本地仓库里的**过期 `duoduo-common` jar**，
  表现是一串"找不到符号 `PermVersion`"（像是新写的类不存在）。反向验证时踩到一次，已按纪律改回 `-am`。



**这一步在做什么**：把「谁能做什么」从**散落在 6 个模块的 20+ 处判权**收敛成**一张「角色 → 权限点」矩阵**，
并提供**声明式判权**（`@RequirePerm`）。**硬约束是行为零变化** —— 谁原来能做什么，改完一模一样。

**① 新增（`duoduo-common`）**

| 类 | 作用 | 关键口径 |
|---|---|---|
| `security/perm/Permission` | 18 个权限点常量，命名 `域:对象:动作` | 清单 `ALL` 用 `LinkedHashSet + unmodifiableSet` —— `Set.of` / `Set.copyOf` 的**迭代顺序不稳定**，而测试要按序遍历（失败信息要能读成"第几条挂掉"） |
| `security/perm/RolePermissionMatrix` | **唯一权威矩阵** | ADMIN=全部；OPERATOR=全部**减去「改角色 + 资金」**（与旧 `isAdmin()` 门等价）；SELLER=仅商家端；USER=空。**未知角色折叠为 USER**（失败方向必须指向拒绝）。角色归一化只做一次（旧代码 `equalsIgnoreCase` 与 `toUpperCase` 混用） |
| `security/perm/RequirePerm` | 注解（**方法级优先于类级**） | 刻意**不提供**「类级默认 + 方法级豁免」—— 豁免语义最容易变成绕过漏洞 |
| `web/PermissionInterceptor` | 注解校验 | **必须注册在 `UserContextInterceptor` 之后**：颠倒的表现是「所有带注解的端点一律 401」，很容易被误读成"鉴权更严了" |

**② 改造点（口径收敛；调用点**刻意不动**）**

- `LoginUser.isOperator()` / `canAccessSellerConsole()` / 新增 `hasPerm()`：全部改为查矩阵 —— **约 55 处 `OperatorGuard.requireOperator()` 调用点一行未改，语义已统一到矩阵**
- `OperatorGuard.requireOperator()` → 委托 `requirePerm(platform:console)`；**403 文案逐字保留**（`SellerProductServiceTest` 断言了原句）
- `AdminUserService.changeRole`：`actor.isAdmin()` → `hasPerm(user:role:manage)`
- `MerchantOnboardingService.readMaterial`：手拼 `"OPERATOR".equals(role) || "ADMIN".equals(role)` → `hasPerm(merchant:material:read)`
- `AdminUserService.ALLOWED_ROLES` → 引用 `RolePermissionMatrix.KNOWN_ROLES`（消掉重复的一份角色清单）
- `AdminAuditSlaController`：4 处命令式判权 → 类级 `@RequirePerm(Permission.AUDIT_SLA)`（**首个注解式落地**）

**③ 测试（`RolePermissionMatrixTest`，17 条）**

- **真值表独立于实现书写**（不复用 `Permission.ALL`）：4 角色 × 18 权限点逐条钉死；真值表的 key 必须与 `Permission.ALL` 一一对应
- **反射校验**：`Permission` 里声明的每个常量都必须进了 `ALL`（防「加了常量忘了登记 → ADMIN 少一项能力」）
- **★ 等价性**：矩阵 vs 旧 `isOperator` / `isAdmin` / `canAccessSellerConsole` **逐角色一致**（改矩阵多给一个权限点就会红）
- 归一化 / 未知角色降级 / `null` 权限点一律拒绝 / 不可变性

**④ ★ 反向验证**：故意让 SELLER 拿到 `platform:console` → 测试**恰好红 3 条**（真值表 / 等价性 / SELLER 范围），改回即全绿 —— 证明守门断言不是恒真。

**⑤ ★ 验证方式上的坑（可复用）**：验「注解到底生效没」**不能走网关** —— E8 的入口收窄会让非 admin 令牌在**网关**先返 403，**那个 403 与注解无关**，只断言网关态会"看起来通过"。因此改为**直连服务端口**（product `8082` / user `8081`）做 8 条双向断言：USER / SELLER → 403、OPERATOR → 200、未登录 → 401（与 403 分得清）、商家端门槛（卖家进得去 / 普通用户进不去）。探测用**只读的 `/samples`** 而非 `/metrics` —— 后者会顺带写库（自动收紧），而此刻自毁刚把比例复位。

**⑥ 顺手修**：`t_user.role` 的 DDL 注释与 `UserEntity` 注释**都漏了实际在用的 `SELLER`**（同一块事实两个说法）。

**⑦ 刻意不做**：其余命令式调用点**不逐个改注解**（改名制造大面积 diff 与漏验证风险，收益为零 —— 口径已统一在矩阵）；前端 `roles.ts` 保持角色判定（**体验层**，真正的边界始终在后端）。

**⑧ 顺带修掉 E28 的一处遗留（全量回归抓出来的）**：全量 e2e 跑出 `merchant-audit` / `product-audit` **两套红**，归因是
**E28 把审核改成「默认放行」后，这两套的夹具假设（"提交后必落 PENDING"）失效** —— 内容干净的商品 / 入驻申请现在直接 APPROVED，
审核台队列空了，后续断言链（「待审核」标签、上架门禁、驳回 → 重提）**全部落空**。
**这不是 E16 引入的**（E16 行为零变化），而是 E28 提交时**没跑全量 e2e** 留下的欠账 ——
与「套件不在 CI 里 = 迟早静默腐烂」是同一类病（CI 至今没有 e2e 作业）。
**修法**：让夹具**故意命中风险规则** → 走转人工 → 才落 PENDING，审核台的断言链恢复有效。
· `product-audit`：商品标题改为 `E14审核夹具商品-微信abc12345-<ts>` → 命中 `SpuRiskJudge.CONTACT_INFO`
· `merchant-audit`：店铺名改为 `审核台官方夹具店-<ts>` → 命中 `MerchantRiskRules.IMPERSONATION_NAME`
两套单独重跑：`product-audit` **36/0**、`merchant-audit` **27/0**。

**验证**：单测 **475/0**（新增 17）；冒烟 `audit-sla-smoke.ps1` **62/0**（新增 8 条权限断言）；**全量冒烟 24 套 0 失败**；**全量 e2e 22 套全绿**（含上面修复的两套）；改的是**被所有服务共用的判权组件** → 按纪律跑**全量回归**；容器重建后**拆包核验**（直接看包内的类，不比大小 / 时间 / 哈希）确认新代码确实在容器里跑。

---

### 2026-10-09（E28 · 审核 SLA 落地：默认放行 + 事后抽查）

**这一步在做什么**：把入驻（E11）与商品（E14）的审核从**强制前置人工**改为**默认自动放行 + 事后抽查**
（方案见《开发执行总纲》附录 D，用户已确认）。人工审核不再是上线的必经环节，而是**抽样复核**。
这件事的对价非常明确：**免掉事前审，事后就必须真的在抽** —— 而这一轮挖出的第一个缺陷，正好就是**抽查一直没跑起来**。

**① 数据层**（`docs/sql/incremental/29-e28-audit-sla.sql`，全量脚本 `01/02` 已同步）

| 位置 | 新增 | 关键口径 |
|---|---|---|
| `duoduo_product.t_spu` | `audit_source` | `AUTO`(按规则自动放行) / `MANUAL`(人工) / `SELF`(自营免审) / **`NULL`=E28 前历史数据（刻意不回填）**。不从 `audit_by` 反推：它为 null 有三种含义，三者后续要做的处置完全不同 |
| `duoduo_product` | `t_audit_sample` | `uk_biz_date(biz_type,biz_id,sample_date)` 是**幂等**的唯一来源；`sample_date` 必须是 **DATE**（用 DATETIME 会让每次重跑都建新行，"幂等"成空话）；`reason` 记录**为什么被抽中**（队列里要能分清"随机抽到"与"本来就重点盯的"） |
| `duoduo_product` | `t_audit_sample_rate` | **单行表，id 固定 1**。比例必须能被自动改写，写进 yml 就成了"改配置再重启"，那不叫自动收紧 |
| `duoduo_user.t_merchant` | `audit_source` + `violation_count` | 商家级违规累计：**只增不减**（"这次抽查没问题"≠"以前的违规撤销了"），唯一重置点是**复业**；升级判定要可原子自增，所以放行上而不是实时聚合 |
| `duoduo_user` | `t_audit_sample` + `t_audit_sample_rate` | **两库各持一份**：服务边界是"每个服务只连自己的库"；抽不抽查不该取决于对方服务此刻是否健康 |
| `duoduo_user.t_sched_leader` | **补建** | ★ 此前**缺这张表** → `SchedLeaderLock` 兜底是"返回 true（放行）" → E15 的 `MerchantLicenseJob` 选主**一直静默失效**。它不算错（任务幂等）所以没人发现 —— 这正是"静默兜底"的典型代价 |

**② 自动放行判定**（`SpuRiskJudge` / `MerchantRiskRules`，纯函数 + 单测）
命中四类风险即**转人工**：违禁类目 / 联系方式（手机号含 `+86` 与分隔符、**带区号**的座机、邮箱、微信/QQ、网址）/ 价格异常 / **该对象此前被驳回过或判过违规**；其余自动放行。
★ 判定**必须放在内容字段都填好之后**（`create()` 里挪到 `setSellerId` 之后）：提前落状态会让判定读到全空值，而空值恰好不命中任何规则 → **所有商品都被自动放行**，且测试很难发现（因为它看起来"都过了"）。
★ 正则收紧过一次：原写法会把「净重 12345678 克」判成座机 → 改为「手机号（可含 `+86`/分隔符）」或「**带区号**的座机」。

**③ 抽查规则**（`AuditSlaPolicy` 放 `duoduo-common`：纯函数、无 Spring 注解、两域共用）

- 基础 **5%**；每日**至少 3 条、最多 20 条**；当日可抽量**不足 3 条则全查**（量小时"抽 5%"等于没抽）。目标数用**上取整**，且不超当日放行量；`ratePercent<=0` → 0（否则出现"比例 0% 却目标 3 条"的自相矛盾）。
- **必抽（100%，不占 optional 配额）**：新商家**头 3 件** / `RESUME`（恢复营业 7 天内）/ 有污点商家按 **20%** 加重（`ESCALATED`）。
- 取样用 **`hash(bizId + epochDay) % 100`**（murmur3 finalizer 打散雪花 id 低位），**不用随机数**：可复现、不可被"卡发布时间"规避，且比例提高时集合是**包含关系**（5% ⊂ 10% ⊂ 20%，这是"收紧不作废上次结论"的全部依据）。
- **自动收紧**：连续 3 天抽中违规率 **> 10%（严格大于，恰好 10% 不算）** → 比例翻倍，封顶 **40%**。抽样为 0 的日子**不占观察期**。
- 候选集 = `supply_type='merchant' AND audit_source='AUTO' AND audit_at ∈ [当天,次日)`。

**④ 两个被堵死的绕过路径（★ 本轮最重要的产出）**

1. **自动放行必须写 `audit_at`** —— 抽查候选集按它筛。`create()` 原为恒 `setAuditAt(null)` → 任务每天照跑、每次 `created=0`，**接口 200、无异常、无日志**，看起来只是"今天没有可抽的"，实际上是这条制度的入口被自己堵死。修复 4 处（`create` / `applyContentReaudit` 两分支 / `update` 显式清空 / `resubmit`）。
2. **曾判违规者不得自动放行** —— `applyContentReaudit` 会对已放行商品重跑判定，而判定里没有"你曾被判过违规"这个前提 → **改一个错别字即可自动重新上线**，平台的处罚结论被冲掉。加 `wasRejected` 短路；**同一条纪律同步到入驻侧**：`submit()` 与 `renewal()` 加 `hasEnforcementRecord`（`violationCount>0 || fromStatus==REJECTED`）—— 否则**因违规停业的店，点一次续期（甚至空请求体）就能自动复业**（它与"资质过期停业"共用同一续期入口）。

**⑤ 复核与处置**：**不可重入**（只有 PENDING 能判，再判 422 —— 否则误判率可被反复点通过刷平）；判合规**只记结论、不动前台可见性**（抽中 ≠ 下架）；判违规是真处置 —— 商品 `REJECTED` + 强制下架 + 来源 `SAMPLE`，再上报商家违规计数（跨域 Feign，失败不回滚止血动作）；入驻侧判违规走 `suspendByAudit`：**资质造假是主体资格问题，直接停业、不攒次数**，停业用**条件更新**（`WHERE status='APPROVED'`，`changed=0` 时不许发通知、不许写留痕）+ 联动下架全店商品；`violation_count` 用 `setSql("violation_count = violation_count + 1")` 原子自增（避免读-改-写丢计数），达阈值升级商家级 `SUSPENDED`。Feign 故障/查不到一律**放行**（纵深防御第二道门：不能把"查不到"当高风险，否则一次跨域抖动把全平台商品推进待审队列）。

**⑥ 前台**：后台第 10 个工作台「**抽查复核**」（`/admin/audit-sla`）—— 两域切换（第三方商品 / 入驻店铺）+ 四状态（待复核 / 复核通过 / 判违规 / 全部）+ 六张指标卡（当前抽样比例 / 近 30 天已复核 / 抽中违规率 / 平均复核时长 / **误判率** / 待复核）+ 复核弹窗（判违规**必填依据**，不填按钮禁用）。**指标与队列分开取**：读指标会顺带触发自动收紧评估，不该跟着翻页反复执行。

**⑦ 验证**：单测 **458/0**（新增 69：`AuditSlaPolicyTest` 35 / `SpuRiskJudgeTest` 21 / `MerchantRiskRulesTest` 13 / `MerchantViolationServiceTest` 12）；新增 `scripts/audit-sla-smoke.ps1` **54/0**（已入 CI 子集）；新增 `scripts/e2e/audit-sla-ui-check.mjs` **56/0**；三端构建 + 产物隔离校验通过。

**⑧ ★ 本轮还抓到一次"验证本身是假绿"**：改完代码后我比对了"容器内 jar 与宿主 jar 的 md5"，**两边逐字节一致**，结论是"已部署"。但**两边都是旧包** —— 打包发生在改代码**之前**（jar 16:41 / 源码 16:42:48），之后只跑了 `mvn test`（重编 classes，未重打包）。改用**拆包核对**才验出来（`javap` 数关键调用：旧包 2 处、新包 4 处）。**已进纪律**：验证部署一律"拆包看内容"，不比大小/时间/哈希。

### 2026-10-09（E27 + E29 · 接口冒烟夹具与数据漂移根治；顺带修掉无品牌商品 500）

**为什么要有这一轮**：E14 收尾时发现**一键全量冒烟有 9 套红**。逐套归因后确认全部非代码回归，
但它们红着这件事本身就说明系统有问题：**没有任何人会发现它们变红**（既不在 CI 子集里，本地也不是天天跑）。
更要紧的是，这两类"红"会把人训练成"反正有几套是红的"，真回归出现时反而没人看。
所以这一轮的目标不是"把红改成绿"，而是**让断言不再依赖环境里恰好有什么数据**。

**① 夹具化：前置由脚本现场造，不再依赖种子数据**（新增 `scripts/lib/`）

| 夹具库 | 造什么 | 供谁用 |
|---|---|---|
| `seller-fixture.ps1` | 库内直建 `t_merchant`(APPROVED) + `t_merchant_member`(OWNER) + `t_user.role=SELLER`，与 `e2e-seller-fixture.mjs` 同构 | seller / aftersale / logistics / credit |
| `catalog-fixture.ps1` | 五类 SKU（可售×2 / 小库存 / 售罄 / 已下架）+ 关键词商品 SPU + **无品牌商品 SPU** | trade / order / smoke / ai |
| `listing-fixture.ps1` | 闲置 1 已过审 + 1 待审 + 1 已驳回 | smoke |

三条硬纪律：**现场建、跑完自毁**（id 用 `97/98/99` 段位，库里一眼可辨）；**建不起来就显式报红**
（否则后面几条会因"库内本来就是 0 条"静默通过）；**夹具默认带复核回读**（写错列名时当场炸，
不让"夹具没建成"伪装成"业务缺陷"）。

**② 断言数据驱动**：`smoke` / `trade` / `order` / `ai` / `review` / `seller` / `aftersale` / `logistics` / `credit`
九套的固定种子件数与固定种子 id 断言，改为「先查库内实际值再断言」或「自建夹具自证」。
写死条数钉的是"当前有几条数据"，环境一变就红 —— 而它想守的性质（"列表只返回已过审的那批"）与条数无关。

**③ CI 从"有人管"这一层收口**：CI 子集 **5 → 11 套**（补入 seller / aftersale / logistics / credit / ai / review）；
并新增 **`smoke-full` 作业**（push 到 main 时跑 `run-all-smoke.ps1` 全量）。
子集只解决"反馈快"，解决不了"没人跑"—— E10 删掉旧端点后这批套件静默红了整整几轮，根因就在这里。
顺带修掉一个**一直坏着**的 CI 门禁：容器健康等待写死 `healthy -eq 10`，服务数从 10 涨到 14 之后
这道门**永远不可能满足**，每轮 CI 都在这里干等 10 分钟后失败（已改为「数量达下限 + 全部 healthy」）。

**④ ★ 夹具反过来挖出的真实生产缺陷（E29，P0 级）**

`ai-smoke` 的两条失败里藏着一条**与测试无关**的缺陷：**无品牌商品的商品列表 / 商品详情 /
AI 候选召回全部 500**（本地复现 `detail=500`、`list=500`、AI 返回 `DEGRADED`）。

- **根因**：`ProductService.toCards()` 取品牌名时没判空 → `brandNames.get(null)`；
  而 `CatalogLookup.names()` 在「一个 id 都没查到」时返回 `Map.of()`，**不可变 Map 的 `get(null)` 直接抛
  `NullPointerException`**（不是返回 null）。品牌自 E14 起是**可选**字段，第三方卖家不填就是 NULL。
- **为什么一直没被测出来（最值得记的一点）**：只要同一页里有**任意一条**商品带品牌，
  返回的就是允许 null key 的 `HashMap`，一切正常。而种子数据每条商品都带品牌 ——
  于是**单测（打桩用了可变 Map，注释还写着"真实的 CatalogLookup 返回的是 HashMap"，而那句是假的）
  与冒烟（种子都带品牌）双双假绿**。
- **修复**：① `toCards` 补判空（与 `SellerProductService` 三处既有写法对齐）；
  ② `CatalogLookup.names()` 空结果改为返回可变空 `HashMap`，让「查不到」与「id 为 null」同形。
- **验证**：单测 +6（`CatalogLookupTest` 5 项 + `ProductVisibilityTest` 1 项），并做**反向验证** ——
  临时摘掉判空，**恰好红掉那 1 条**、其余 9 条全绿；`smoke.ps1` 另加 **5 条端到端守卫**
  （无品牌商品详情/列表均 200、`brandId`/`brandName` 均为空、夹具自拆复原）。
  重建镜像后逐模块比对容器与宿主 jar md5 一致。

**⑤ 另一处脚本级缺陷（同样只有断言真跑才暴露）**：`review-smoke.ps1` 第 11 节给内部 recompute 传的
是**顶层单元素数组**，而 PowerShell 管道会**枚举**数组 → `@(@{...})` 被序列化成 `{...}` 而非 `[{...}]`，
服务端 `@RequestBody List<RatingPatch>` 直接 400。修复：`ConvertTo-Json` 改用 `-InputObject`
（管道的隐式枚举是这类"看起来像接口不认 payload"的常见真凶）。

**⑥ 测试端的假红也一并修掉**：`ai-smoke` 的「预算没听懂时如实告知」曾因**外部大模型超时**假红 ——
`DashScopeClient` 逐个备选模型试（每个 15s），两个都超时就接近 30s，恰好撞上客户端的 30s 超时，
**服务端其实已经正确降级并答完**了。修复：对话请求超时放宽到 75s + 仅在「传输层没拿到响应」(status=0) 时重试 1 次
（4xx/5xx 是有意义的信号，绝不重试、绝不掩盖）。失败信息也从"只打印 reply"改为**带 status**，
一次就能分清"服务回了别的话"和"根本没拿到响应"。

**结果**：九套全绿 —— `seller 99/0`、`aftersale 101/0`、`logistics 79/0`、`credit 82/0`、
`trade 87/0`、`order 77/0`、`smoke 62/0`、`ai 39/0`、`review 61/0`；夹具残留检查 0（`spu 97*` / `sku 99*` / `listing 98*` 均为 0）；
后端单测 **368/0**（362 + 6）。新增文件：`scripts/lib/{seller,catalog,listing}-fixture.ps1`、
`duoduo-product/.../support/CatalogLookupTest.java`。

### 2026-10-09（E15 · P2 第 7 项：资质有效期与提醒，**P2 收官**）

**为什么要有这一轮**：E9–E14 让第三方商家能开店、上架商品，但**资质本身没有有效期** ——
一份 2024 年办的营业执照可以永远用下去。平台替商家把关的第一件事就是「证还在有效期内」，
而这件事的特点是**没有请求会来触发**：商家不会每天打开后台看证还剩几天，买家也不会因为执照过期就停止下单。
若只在「上架商品」时判一次，一家三个月没上新品的过期店会永远挂在架上卖。

**核心设计：有效期落在材料上，店铺级到期日只是派生列。**

- `t_merchant_material.expire_at` 每份材料各一份 —— 提醒文案必须说清**哪张证**要到期。
  「您的营业执照将于 3 天后到期」是可执行的；「您的店铺资质将到期」只会让商家去翻六份材料，
  而营业执照、行业经营许可证、法人身份证的到期日各不相同，压成一列就永久丢了这个信息。
- `t_merchant.license_expire_at` = 所有带有效期材料里的**最早**到期日，由服务端在「保存 / 提交 / 续期」
  时重算，**唯一写入出口** `refreshLicenseExpireAt()`（与 `visibleSpuWrapper()` 同一纪律）。
  冗余的理由有两条：定时扫描要按到期日取区间（JOIN 材料表取 MIN 会让索引失效）；
  上架门禁是每写一次商品就要判一次的高频路径，不能顺带拉起六行材料。
  **展示路径刻意不读这一列** —— `toView()` 实时按已加载的材料行算：低频路径没必要接受
  「材料改了、派生列没刷」的风险。

**★ NULL = 长期有效，不是「已过期」**：加列那一刻所有存量行都是 NULL，若把 NULL 解释成过期，
迁移完成的瞬间**全部存量商家集体停业** —— 纯人为事故。与 `t_spu.audit_status` 默认 `APPROVED`
是同一个取舍：**新增判定维度时，默认值必须让存量数据维持原状**。
（实测：增量 DDL 执行后 58 家商家、状态分布一字未变。）同理 `licenseRemainingDays` 无到期概念时回
`null` 而不是 `0` —— `0` 在界面上会被渲染成「今天到期」。

**提醒去重靠「档位」而不是布尔值**：`expire_notify_stage` 记 `T30 / T7 / EXPIRED`，
规则是「**新档位比已记档位更深才发一条**」。于是 30 天档发一条、7 天档再发一条，
每天扫到同一家也只发两次 —— 只记「提醒过没有」的话，第二天就无处判断该不该再发，
只能变成每日骚扰或再也不发。**通知失败刻意不记档位**：记了就永远不会再发，而商家其实没收到
（状态全对、日志只有一行 WARN），这种漏报没有任何人能发现；不记则下一轮重试，最多重复一条。

**三条业务规则**（每条都对应一种真实绕过手法）：

1. **过期停业用条件更新**（`WHERE status='APPROVED'`）—— 扫描与商家续期可能撞在同一瞬间，
   按 id 覆盖会把商家刚提交的续期（已变 `SUBMITTED`）静默压回 `SUSPENDED`，且两边都不报错。
2. **停业时联动下架在售商品，但门禁只拦「上架」** —— 停业期商家仍应能下架、改价、清库存
   （那是在为恢复营业做准备）；一刀切拦住所有写操作，商家的第一反应是「系统坏了」而不是「我该续期了」。
3. **续期不自动通过** —— 回 `SUBMITTED` 重走审核，否则商家拿一张过期证照的截图就能把停业状态刷回来。
   且续期提交的有效期**仍在过去**时直接 422：走完一圈审核回来照样停业，白白消耗双方精力。

**纵深防御的第二道门**：商品域上架前查用户域「这家店现在能不能卖东西」，**查不到 / 查不通一律放行**。
第一道门是「过期即停业 + 联动下架」，它不依赖用户域可用性；若第二道门在 Feign 抖动时改成「拒绝」，
一次跨域超时就会让全平台商家都无法上架 —— 用一个可自愈的小问题换一次全站停工。
判据是 `status == APPROVED` 而**不是**「没过期」：续期审核中（`SUBMITTED`）同样不能卖。

**运营手动巡检入口** `POST /api/admin/merchants/license-sweep`：定时任务 6 小时一轮，
若只能靠它观察，验收与排障就得掐着表等（改完一份材料的有效期后想确认后果，唯一办法是等下一轮）。
该入口与定时器共用 `sweepOnce()`，跑的就是生产那条路径；处置全是条件更新 + 只升档才提醒，可安全重复点。

**★ 两个由测试驱动暴露并修掉的真实缺陷**：

1. **`duoduo-product` 容器跑的是旧 jar**。E15 新增的内部停售端点与上架门禁源码都在，
   但镜像从未重建 —— 表现是「联动下架 404」+「上架被 E14 审核门禁拦下（提示正在审核中）」。
   两个症状看起来毫不相干（一个是跨域调用、一个是业务规则），实际同一个原因，
   且**只有 e2e 断言「商品真的被下架」才会红**。教训：源码与镜像的不一致不会自己暴露。
   → 全量重建 11 个镜像并逐模块比对容器与宿主 jar 的 md5。
2. **MyBatis-Plus `updateById` 不写 null 字段**（默认更新策略 `NOT_NULL`）。
   「续期后清空提醒档位 / 清掉停业原因 / 重提时清空过时驳回原因」这三个**清空**动作全部静默失效：
   接口返回 200、`status` 也确实变了，只有那几列悄悄留着旧值 —— 没有异常、没有日志，
   也**不影响任何既有断言**。是 e2e 的 S6/S7「档位应清零、停业原因不应再展示」两条断言把它抓出来的。
   修法：新增 `clearNullableState()`（显式 wrapper 更新，**并同时清内存** —— `toView()` 读的是内存对象，
   只清库会让**响应体**带着旧值，那是方向相反的同一个 bug）；同时把单测补齐到**断言 SET 子句**：
   原来那几条只断言内存实体字段，而内存里 `setXxx(null)` 恒为 null，
   **无论实现对不对都恒绿** —— 缺陷正是这样溜过去的。
   （踩到的坑：`getSqlSegment()` 只返回 **WHERE** 段，SET 子句要看 `getSqlSet()`。）

**★ 顺手补上一个既有缺口**：`t_merchant` / `t_merchant_member` / `t_merchant_material` 三张表
此前只存在于 `docs/sql/incremental/25-26`，**全量 DDL `01-duoduo-user.sql` 漏同步**。
compose 只把 `docs/sql/` 根目录挂给容器 entrypoint（**不递归 `incremental/`**），
所以「已有环境升级正常、全新环境 / CI 建不出表」这个缺口不会自己暴露。已补齐并用临时库实测
（8 张表全部建出，临时库用完即删）。另一个同型问题：`licenseRemainingDays` 是**度量**却被当成标识
按字符串下发（`types.ts` 早已声明为 `number`）—— 与第十七轮 `total`、第五十六轮 `commentCount` 同型，
已补进 `NUMERIC_LONG_FIELDS`。

**交付物**：增量 DDL `docs/sql/incremental/28-e15-license-expiry.sql`（4 列 + 1 索引）+ 全量 DDL 同步；
领域规则 `LicenseExpiry`（档位 / 剩余天数 / 最早到期日的**唯一定义处**，剩余天数按**日历日**算，
不是 24 小时块 —— 后者会把「12-31 到期、今天 12-24」算成 6 天）；定时任务 `MerchantLicenseJob`
（选主 + 单轮 200 家上限 + 异常不外抛）+ 运营手动入口；跨域通道（用户域 → 社交域通知
**刻意不带小程序落地页**：续期在商家端网页办，填一个不存在的页面会被白名单拒 400；
用户域 → 商品域批量停售）；上架门禁 `requireShopActive()` + 内部端点
`/api/products/internal/seller/{id}/off-shelf`；前端三处（商家端入驻页日期录入 / 分档横幅 / 续期按钮、
工作台横幅、后台审核台资质列 + 停业原因 + 有效期比对提示 + 立即巡检按钮）。

**验证**：后端单测 **362/0**（E15 新增 57：`LicenseExpiryTest` 16 + `MerchantLicenseRenewalTest` 18 +
`MerchantLicenseJobTest` 15 + `SellerProductServiceTest` +8）；新增真实浏览器 e2e
`scripts/e2e/merchant-license-ui-check.mjs` **71/71**（长期有效基线 → T30 横幅与提醒 →
**同档不重复发** → 升档再发 → 过期停业 + 联动下架 + 审计留痕 → 上架 422 → 后台与商家端双横幅 →
续期回 `SUBMITTED` 且仍不能上架 → 通过后放行 → 续期拒绝过去日期**且事务回滚**）；
全量 e2e 套件通过；全量 DDL 用临时库实测可建表、增量 DDL 在存量库执行后状态分布不变；
镜像重建后逐模块比对容器与宿主 jar 的 md5 一致。

### 2026-10-09（E14 · P2 第 6 项：第三方商品上架审核）

**为什么要有这一轮**：在 E14 之前，「商品能不能被买家看到」只有**一个**开关 —— `t_spu.status`，
而它是**卖家自己**控制的。也就是说卖家只要把商品放到在售，前台立刻可见，平台没有任何人工把关环节。
P2 引入第三方商家（E9–E13 的租户 / 入驻 / 子账号）之后，这条缺口从"无所谓"变成"必须堵"：
任何入驻商家都能直接上架任意商品，包括违规的。

**核心设计：两个正交维度，可见性单点收口。**

- 交易维度 `status`（`ON_SALE`/`OFF_SHELF`，**卖家可控**）与审核维度 `auditStatus`
  （`PENDING`/`APPROVED`/`REJECTED`，**平台判定**）各管一件事。一件商品可以「已过审但被自己下架」，
  也会出现「驳回件等卖家改内容」——两个维度不能合并成一条状态线（与 C2C 闲置 `t_listing` 同一口径）。
- 对外可见的**唯一**判据是「在售 **且** 已过审」，由 `ProductService.visibleSpuWrapper()` 单点定义，
  覆盖 MySQL 的 7 条读路径 + ES 索引文档与查询过滤 + 交易侧采购视图。**未过审商品详情一律 404**
  （与"不存在"同码，防用状态码差异探测别人的商品）。
- **平台自营免审**：判据是**供货类型**（`supplyType='self'`）而不是"谁在操作"，自营商品 `auditStatus` 恒 `APPROVED`。

**三条业务规则是本轮的主要价值**（每条都对应一种真实绕过手法）：

1. **内容变更再审** —— 审核结论是针对"当时那一段内容"作出的。放它继续挂着 `APPROVED`，
   等于允许卖家**先过审、再改成违规内容**。所以内容有实质变化即回 `PENDING`。
2. **上架门禁** —— 目标态为在售时要求已过审。没有它，"先提交 `PENDING`、再自己上架"就直接绕过了整条审核链；
   428 提示语还会说清下一步（是"去等审核"还是"去改内容"，不能只说"不行"）。
3. **驳回必须填原因** —— 否则卖家不知道自己为什么被拒，也留不下可追溯的解释。

**两个刻意的取舍**：① 审核**可重入**（重复通过 / 驳回覆盖上一次结论）——运营改判是真实场景，
"已审过就不能再改"会把改判逼成直接改库，反过来留下无法追溯的结论；② **通过时不动 `status`** ——
卖家提交时期望的就是在售，若它同时被卖家手动下架过就保持下架（尊重卖家的选择），
所以审核台通过后商品**不会**因为"审核过了"就自己重新上架。

**顺带修掉两个既有缺陷**：商家商品列表里 `seller_id = NULL` 的空列表问题（null 塞进 `eq` 生成
`seller_id = NULL`，永不匹配）；主图清空原本静默通过，改为 400。

**★ 一个只在"真启动"时才现形的缺陷**：新增的 Feign 客户端（商品域要实时查"这个账号是不是某家店的店员"，
成员身份在用户域且**停用必须即时生效**，所以不进令牌、不缓存）漏了 `spring-cloud-starter-loadbalancer`。
**单元测试全绿、`package` 也全绿** —— 因为单测用 Mockito 打桩，根本不会去解析服务发现；
只有容器启动那一刻才炸 `No Feign Client for loadBalancing defined`，服务 unhealthy 起不来。
教训记进 `duoduo-product/pom.xml` 注释：**凡是 `@FeignClient(name=...)` 走服务发现，loadbalancer 就不能省。**

**验证**：后端单测 **305/0**（本轮 +45：`SellerProductServiceTest` 36 + `ProductVisibilityTest` 9；
后者专门钉"7 条 MySQL 读路径都带 `audit_status` 过滤"）；真实浏览器 e2e
`scripts/e2e/product-audit-ui-check.mjs` **36/36**（发布 → 前台不可见 → **先 reindex 让索引里确实有文档、
再断言检索不命中** → 上架门禁 422 → 后台通过 → 上架后可见 → 改内容回待审 → 驳回 → 重提 → 再通过；
收尾删除夹具商品，**避免在审核队列里留一条永久待办**）；**全量 e2e 20 套全 PASS**；
前端两个校验工具（import 解析 438 条 / 产物隔离）通过；`tsc --noEmit` 三端零错误、`vite build` 三端通过；
镜像重建后**逐模块比对容器与宿主 jar 的 md5 一致**。

### 2026-10-08~09（P2 前五项补记：E9 租户实体 / E10 入驻申请 / E11 审核台 / E12 材料私有通道 / E13 子账号）

> 这五项此前只记在《开发执行总纲》与《项目简报》，本仓 README 未同步 —— 补记在此，避免文档落后于代码。

- **E9** `t_merchant` / `t_merchant_member` 建表（user 库）；`duoduo-common` 新增 `SellerOwnership`
  把"自营"语义集中成单一权威（`SELF_SELLER_ID=0` + 判定），trade 域常量改为引用。
- **E10** 入驻改成状态机（草稿 → 送审 → 通过 / 驳回 → 重提）：6 类材料清单校验、驳回原因结构化
  （`[{scope,code,note}]`）、重复提交幂等；**封死"实名即开卖家"的旧端点**（无审核开店路径不复存在）。
- **E11** 后台 `/admin/merchants` 入驻审核台：材料大图预览 + 一致性核对提示 + 逐条结构化驳回；
  概览页待办入口卡（**刻意不进顶栏** —— 顶栏宽度已到上限，再加一项会静默截断）。
- **E12** 资质材料私有通道：独立私有卷 + 按租户分目录 + 鉴权代理（店主 / 运营可读，其余 403）+
  **每次下载留痕**（`t_audit_log`）+ 借用他人材料路径被拒。
- **E13** 商家子账号：店主按手机号邀请店员，**成员身份不写进令牌**（否则"停用"要等令牌 15 分钟过期），
  改为每请求实时查 → 停用零延迟生效；店员以店铺主体行动、**结算与提现对其 403**；
  平台侧跨租户总览与处置（审计可区分"店主自停"与"平台处置"）。

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

## 六十四（2026-10-22）：Web 端支付宝「电脑网站支付」渠道上线（沙箱就绪）

- **新增 `ALIPAY_PC` 渠道**：`alipay.trade.page.pay` 预下单（RSA2 签名拼收银台 URL）→ 用户跳转支付宝付款 → 表单回调验签入账 → 同步退款。装进现有 `PaymentChannel` SPI 骨架，订单/账本/幂等代码零改动。
- **手写 RSA2 签名/验签**（`AlipayCrypto`，约 60 行）：字典序拼串 + SHA256withRSA，不引 alipay-sdk 依赖链。单测 6/6：签名往返、篡改金额必拒、缺 sign 必拒、错公钥必拒、PEM 头兼容。
- **沙箱/生产零代码差别**：只换 `ALIPAY_GATEWAY` 与密钥对。沙箱网关（`openapi-sandbox.dl.alipaydev.com`）**无需营业执照**，用沙箱钱包付款；生产网关（`openapi.alipay.com`）需个体工商户/企业实名并签约「电脑网站支付」。
- **凭据只走环境变量**（`ALIPAY_PAY_ENABLED / APP_ID / PRIVATE_KEY_PEM / PUBLIC_KEY_PEM / GATEWAY / NOTIFY_URL / RETURN_URL / EXPIRE_MINUTES`），compose 已透传，启用即启动自检缺项失败（fail closed）。
- **前端**：订单详情「支付渠道」新增「支付宝」——预下单后展示「跳转支付宝付款」按钮，付款后本页轮询 `GET /api/payments/{payNo}` 自动入账；「本机模拟扫码」收窄为沙箱渠道专用。
- 已知边界：个人主体无照**只能跑沙箱**（正式收款必须执照签约，见 `docs/payment-qualification-alipay-vs-wechat.md`）；回调地址网关白名单 `/api/payments/callback/**` 已覆盖，无需新增。

## 六十五（2026-10-22）：支付宝沙箱全链路打通（密钥配对 + 签名口径修正）

- **密钥配对**：沙箱控制台「接口加签方式」上传与本地应用私钥配对的应用公钥（由私钥用 `openssl pkey -pubout` 推导生成），配对后签名即被网关接受。
- **签名口径实测修正**：沙箱网关错误页会展示它构造的验签串——逐字节比对发现**验签串包含 `sign_type`**（只剔除 `sign` 与空值），与部分旧文档「剔除 sign_type」的说法不同。`AlipayCrypto.signContent` 已修正，回调验签同口径。
- **排查方法沉淀**：验签失败先查请求内容再怀疑密钥（官方原则）；用 openssl/python 建第二套独立签名实现做差分，把 charset/format/参数位置逐一排除；网关错误页的「验签字符串」是逐字节比对的权威依据。
- **联调修复合集**：① 表单公共参数（含 charset）必须在 action 查询串、body 只放 biz_content；② `t_payment.code_url` 加宽为 MEDIUMTEXT（增量 41）；③ 回调应答纯文本 success/failure；④ `alipay.trade.query` 查询兜底进补偿调度。
- **验证**：单测 12/12；端到端：登录→下单→`pay(ALIPAY_PC)`→表单提交沙箱网关→返回真实收银台页（61KB，含扫码/付款元素）。
- 生产切换：仅换 `ALIPAY_GATEWAY=https://openapi.alipay.com/gateway.do` + 正式密钥（需执照签约「电脑网站支付」）。
