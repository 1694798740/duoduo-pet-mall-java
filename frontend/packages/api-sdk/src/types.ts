/**
 * 与 Java 微服务网关对齐的响应类型定义。
 *
 * 后端统一响应结构（见 duoduo-common 的 ApiResponse）：
 *   成功 {"code":"SUCCESS","message":"ok","data":{...},"requestId":"...","timestamp":123}
 *   失败 {"code":"BUSINESS_RULE","message":"库存不足","data":null,"requestId":"...","timestamp":123}
 *
 * 分页结构（见 duoduo-common 的 PageResult）——注意它整个塞在 data 里，
 * 不像 Node 原型版那样把 meta 平铺在响应顶层：
 *   {"items":[...],"total":100,"page":1,"pageSize":20,"pageCount":5}
 *
 * 约定：
 *   · 金额一律整数「分」；
 *   · 时间一律 ISO8601 UTC 字符串；
 *   · **所有 64 位标识（雪花 id 及其外键）一律是 `string`，不是 `number`** —— 见下方说明。
 *
 * ## ⚠️ 为什么 id 必须是 string（T-39）
 *
 * 数据库主键是雪花算法生成的 18~19 位整数，例如 `361084524781768704`。
 * 而 JavaScript 的 `number` 是 IEEE-754 双精度浮点，**安全整数上限只有
 * `Number.MAX_SAFE_INTEGER = 9007199254740991`（16 位）**。
 * 超过这个范围的值会被 `JSON.parse` **静默四舍五入**到另一个整数 —— 末位直接丢：
 *
 * ```js
 * Number('361084524781768704')  // → 361084524781768700（末位 704 变成 700，且不报错）
 * ```
 *
 * 后果是**功能性错误而非显示问题**：把这个值拼进 URL，服务端查不到记录，接口返回 404。
 * 本项目已在真实浏览器自测中复现过两次（售后单、收货地址），每次都表现为
 * 「点按钮 → XX 不存在」这种看不出原因的错误。
 *
 * **因此本文件里所有 `xxxId` 字段都是 `string`。**前端只做两件事：
 *   1. 拼进 URL（`` `/api/addresses/${id}` ``）—— 字符串天然安全；
 *   2. 相等比较（`a.id === selectedId`）—— 两侧都是字符串，精确。
 *
 * **绝对不要**：`Number(id)` / `parseInt(id)` / `id + 1` / `id > 0` 这类数值运算。
 * 若确实需要判断「有没有值」，用 `id !== ''` 或 `id != null`，**不要**用 `Number.isFinite`。
 *
 * 例外：`CategorySaveRequest.parentId` 的哨兵值 `'0'`（表示「顶级类目」）
 * 是**真正的数值语义**，与雪花 id 无关 —— 见 `admin/Categories.tsx` 的处理。
 */

// ---------- 用户域（duoduo-user） ----------

/**
 * E21 · 两步验证状态。
 *
 * <p>`enabled=true` 表示此后登录必须携带动态码或恢复码。前端在两处用到它：
 * 登录页据此展示"第二步"，后台内据此展示"已开启 / 去绑定"。
 */
export interface TotpStatusView {
  enabled: boolean;
  /** 绑定完成时间（ISO-8601 UTC）；未绑定为 null */
  boundAt: string | null;
  /** 剩余可用恢复码数量。明文只在生成那一刻返回一次，这里只给数量 */
  recoveryCodesRemaining: number;
}

/** E21 · 绑定第一步的返回：Base32 密钥 + otpauth 链接 */
export interface TotpSetupView {
  /** 供"扫不了码"时手动录入认证器 */
  secret: string;
  /** 交给二维码组件渲染 */
  otpauthUri: string;
}

/** E21 · 绑定成功的返回：**一次性**的恢复码明文，之后服务端只存哈希 */
export interface TotpEnableView {
  recoveryCodes: string[];
}

export interface UserView {
  id: string;
  /** 手机号掩码，如 138****0001；后端不下发明文 */
  phoneMasked: string;
  nickname: string;
  avatar: string | null;
  role: string;
  status: string;
  realNameVerified: boolean;
  realName: string | null;
  idCardMasked: string | null;
  creditScore: number | null;
  createdAt: string;
  /** E13 · 店铺成员身份（ACTIVE STAFF 才下发；null = 店主或无店） */
  merchant?: MerchantBrief | null;
}

/**
 * C7 · 账号注销：阻塞项（未终态订单 / 进行中售后 / 商家身份 / 在售闲置）。
 * type 决定端侧归类展示，ref 是订单号等业务标识，detail 是可直接展示的说明。
 */
export interface BlockedItemView {
  type: string;
  ref: string;
  detail: string;
}

/** 注销预检结果（点击「注销账号」时先拉一次，把待办摆给用户） */
export interface DeleteBlockersView {
  blocked: boolean;
  items: BlockedItemView[];
  /** 后端同时会把当前注销状态带上，端侧据此切换「申请 / 撤销」形态 */
  accountStatus?: string;
  scheduledAt?: string | null;
  graceDays?: number;
}

/** 提交注销后的状态视图 */
export interface DeleteAccountView {
  accountStatus: string;
  scheduledAt: string | null;
  graceDays: number;
  blockedItems: BlockedItemView[];
}

export interface PetView {
  id: string;
  name: string;
  /** cat / dog / other */
  species: string;
  breed: string | null;
  /** yyyy-MM-dd */
  birthday: string | null;
  ageMonths: number | null;
  /** BABY / ADULT / SENIOR */
  lifeStage: string | null;
  weightKg: number | null;
  /** MALE / FEMALE / UNKNOWN */
  gender: string | null;
  neutered: boolean;
  allergens: string[];
  medicalHistory: string | null;
  isDefault: boolean;
  createdAt: string;
}

export interface PetUpsertRequest {
  name: string;
  species: 'cat' | 'dog' | 'other';
  breed?: string | null;
  birthday?: string | null;
  lifeStage?: 'BABY' | 'ADULT' | 'SENIOR' | null;
  weightKg?: number | null;
  gender?: 'MALE' | 'FEMALE' | 'UNKNOWN' | null;
  neutered?: boolean;
  allergens?: string[];
  medicalHistory?: string | null;
  isDefault?: boolean;
}

// ---------- 商品域（duoduo-product） ----------

export interface CategoryNode {
  id: string;
  name: string;
  parentId: string | null;
  depth: number;
  icon: string | null;
  /** 后台列表会返回停用项；公开接口只暴露 ACTIVE */
  status?: 'ACTIVE' | 'INACTIVE' | string;
  children: CategoryNode[];
}

/**
 * A-10 · 类目属性模板项。
 *
 * <p>它只定义「这个类目要填哪些属性」，属性值仍存在商品的 `specParams` KV 里（按 name 匹配）。
 * `inputType`：TEXT / NUMBER / SELECT；SELECT 时 `options` 非空。
 */
export interface CategoryAttrView {
  id: string;
  categoryId: string;
  name: string;
  inputType: 'TEXT' | 'NUMBER' | 'SELECT' | string;
  required: boolean;
  options: string[] | null;
  sortNo: number;
  status: 'ACTIVE' | 'INACTIVE' | string;
}

export interface CategoryAttrSaveRequest {
  name: string;
  inputType?: 'TEXT' | 'NUMBER' | 'SELECT';
  required?: boolean;
  options?: string[];
  sortNo?: number;
}

export interface BrandView {
  id: string;
  name: string;
  logo: string | null;
  country: string | null;
  story: string | null;
  /** 后台列表会返回停用项；公开接口只暴露 ACTIVE */
  status?: 'ACTIVE' | 'INACTIVE' | string;
}

export interface DictItem {
  code: string;
  label: string;
}

export interface SkuView {
  id: string;
  skuCode: string;
  spec: Record<string, string>;
  image: string | null;
  price: number;
  marketPrice: number;
  /** 可售余量 = 真实库存 − 已被他人下单冻结的量 */
  stock: number;
  food: boolean;
  species: string[];
  lifeStages: string[];
  allergens: string[];
  /** 服务端算好的「是否可下单」，前端不再重复实现「库存 0 / 已下架」两套规则 */
  sellable: boolean;
}

export interface ProductCard {
  id: string;
  title: string;
  subtitle: string | null;
  categoryId: string | null;
  categoryName: string | null;
  brandId: string | null;
  brandName: string | null;
  /** self 自营 / third 第三方（库中真实取值；merchant 为历史别名） */
  supplyType: string;
  mainImage: string | null;
  minPrice: number;
  maxPrice: number;
  salesCount: number;
  rating: number;
  ratingCount: number;
  inStock: boolean;
  /**
   * 卖家归属（0 = 平台自营）。商品详情页据此展示卖家信用分。
   *
   * 声明为可选：老版本后端不下发该字段时，前端按「未知卖家」处理（不展示信用），
   * 而不是把它当成自营（那会把第三方商家的商品错标成平台自营的信用）。
   */
  sellerId?: string | null;
  /**
   * 店铺名（第八十一轮）：第三方 = 商家自有店名；自营 = 多多自营；取名失败/老后端 = undefined。
   * 首页/列表/购物车据此显示真实店铺，而不是千篇一律的「品牌入驻」。
   */
  storeName?: string | null;
}

export interface Suitability {
  food: boolean;
  species: string[];
  lifeStages: string[];
  allergens: string[];
}

export interface ProductDetail {
  card: ProductCard;
  /** 商品介绍（纯文本，历史字段；新商品与 intro 同义） */
  detail: string | null;
  /** 富文本详情（服务端已消毒）；为空时端侧回落 detail */
  detailHtml?: string | null;
  /** 商品介绍（短文本） */
  intro?: string | null;
  specParams?: KvItem[];
  packaging?: KvItem[];
  /** 图集：**第一张恒为主图**，其后为副图（2026-10-24 服务端归一） */
  images: string[];
  skus: SkuView[];
  suitability: Suitability;
  sameCategory: ProductCard[];
  alsoViewed: ProductCard[];
}

export interface ListingCard {
  id: string;
  title: string;
  price: number;
  originalPrice: number;
  conditionLevel: string | null;
  city: string | null;
  cover: string | null;
  species: string | null;
  breed: string | null;
  viewCount: number;
  sellerId: string | null;
}

export interface ListingDetail {
  card: ListingCard;
  description: string | null;
  images: string[];
  /** 「同款全新」推荐 */
  sameCategoryNew: ProductCard[];
}

export interface SpeciesPicks {
  species: string;
  label: string;
  items: ProductCard[];
}

export interface HomeView {
  categories: CategoryNode[];
  recommended: ProductCard[];
  freshArrivals: ProductCard[];
  brands: BrandView[];
  secondhand: ListingCard[];
  speciesPicks: SpeciesPicks[];
}

export interface Dictionaries {
  /** 后端返回的筛选字典：物种 / 生命阶段 / 供货类型 / 排序等 */
  [key: string]: DictItem[];
}

// ---------- 交易域（duoduo-trade） ----------

/** 购物车失效原因码（与后端 CartDtos.InvalidReason 一一对应） */
export type CartInvalidReason =
  | 'SKU_NOT_FOUND'
  | 'OFF_SHELF'
  | 'OUT_OF_STOCK'
  | 'STOCK_NOT_ENOUGH';

export interface CartItemView {
  skuId: string;
  spuId: string;
  title: string;
  specText: string | null;
  image: string | null;
  price: number;
  quantity: number;
  /** 小计（分）；失效项为 0 */
  amount: number;
  availableStock: number;
  selected: boolean;
  valid: boolean;
  invalidReason: CartInvalidReason | null;
  /** 卖家归属（店主 userId；0 = 平台自营）——购物车按店铺分组的键 */
  sellerId: string | null;
  /** 店铺名（第三方 = 商家自有店名；自营 = 多多自营；取名失败 = null） */
  storeName: string | null;
  /** 是否平台自营 */
  selfOwned: boolean;
}

export interface CartView {
  items: CartItemView[];
  itemCount: number;
  totalQuantity: number;
  selectedCount: number;
  /** 已勾选且有效的小计合计（分）—— 即结算页的「商品总额」 */
  selectedAmount: number;
  invalidReasons: string[];
}

export type OrderStatus =
  | 'PENDING_PAYMENT'
  | 'PAID'
  | 'SHIPPED'
  | 'COMPLETED'
  | 'REFUNDED'
  | 'CLOSED';

export interface SettlementView {
  orderNo: string;
  sellerId: string | null;
  amount: number;
  platformFee: number;
  sellerAmount: number;
  /** CUSTODY 托管中 / RELEASED 已放款 / REFUNDED 已退回买家 */
  status: string;
  statusLabel: string;
  releaseAt: string | null;
  releasedAt: string | null;
  createdAt: string;
}

export interface OrderItemView {
  skuId: string;
  spuId: string;
  title: string;
  specText: string | null;
  mainImage: string | null;
  price: number;
  quantity: number;
  amount: number;
}

export interface OrderView {
  orderNo: string;
  sellerId: string | null;
  /**
   * 店铺名（2026-10-22）：第三方 = 商家自有店名；自营 = 多多自营。
   * 取名失败/老后端为 undefined，前端降级为「店铺{sellerId}」或隐藏入口。
   */
  storeName?: string | null;
  orderType: string;
  status: OrderStatus;
  /** 状态中文文案由服务端给出，前端不维护映射表 */
  statusLabel: string;
  totalAmount: number;
  freightAmount: number;
  discountAmount: number;
  /** 本单使用的优惠券券码；未用券为 null */
  couponCode: string | null;
  payAmount: number;
  receiverName: string;
  receiverPhone: string;
  receiverAddress: string;
  remark: string | null;
  items: OrderItemView[];
  /** 资金托管信息；未支付时为 null */
  settlement: SettlementView | null;
  createdAt: string;
  expireAt: string | null;
  paidAt: string | null;
  shippedAt: string | null;
  finishedAt: string | null;
  closedAt: string | null;
  refundedAt: string | null;
  closeReason: string | null;
  /** 物流公司与单号；发货后才有值。买家与卖家看的是同一份事实 */
  logisticsCompany: string | null;
  logisticsNo: string | null;
}

// ---------- 收货地址簿（/api/addresses，A8） ----------

export interface AddressView {
  id: string;
  receiverName: string;
  receiverPhone: string;
  province: string;
  city: string;
  district: string;
  detail: string;
  label: string | null;
  /**
   * 服务端拼好的完整地址，与下单快照写入的文本严格同源。
   *
   * 前端**不要**自己用 province + city + district + detail 拼：
   * 直辖市（如「北京市 / 北京市」）在服务端会被去重，
   * 前端再拼一遍就会得到「北京市北京市朝阳区…」，
   * 与订单里存的地址不一致 —— 这类差异在售后纠纷里会被当成证据问题。
   */
  fullAddress: string;
  isDefault: boolean;
  createdAt: string;
}

export interface AddressUpsertRequest {
  receiverName: string;
  receiverPhone: string;
  province: string;
  city: string;
  district: string;
  detail: string;
  label?: string | null;
  /** true = 设为默认；false / 不传 = 不改变默认归属（不存在「取消默认」这个动作） */
  isDefault?: boolean;
}

export interface PlaceOrderRequest {
  /**
   * 收货信息有两种传法，**二选一**：
   *  · 传 addressId（推荐）—— 服务端去用户域取地址并写入订单快照，三个 receiver* 可省略；
   *  · 传 receiverName / receiverPhone / receiverAddress —— 直填，兼容旧流程。
   *
   * 两者都传时以 addressId 为准（服务端定的优先级），此处定义成可选以反映真实契约。
   */
  addressId?: string | null;
  receiverName?: string;
  receiverPhone?: string;
  receiverAddress?: string;
  remark?: string;
  /**
   * 本单使用的优惠券券码（可空）。
   *
   * 只传券码、**不传优惠金额**：抵扣多少由营销域按券模板算出、交易域落账。
   * 一旦接受客户端传金额，就等于把「能抵多少」的决定权交给浏览器。
   *
   * 注意：一张券只作用于**本次拆单后商品总额最大的那个订单**（v1 口径），
   * 因此用券时提交的多个订单里只有一个会带优惠。
   */
  couponCode?: string;
  /** 客户端生成（建议 UUID），同一用户同键重复提交只会产生一个订单 */
  idempotentKey: string;
}

export interface PlaceOrderResult {
  orders: OrderView[];
  orderCount: number;
  totalPayAmount: number;
  /** true 表示命中幂等键，返回的是已有订单（没有新建） */
  idempotentReplay: boolean;
}

/** 支付单状态：PENDING 待支付（扫码中）/ SUCCESS / EXPIRED / CLOSED / FAILED */
export type PayStatus = 'PENDING' | 'SUCCESS' | 'EXPIRED' | 'CLOSED' | 'FAILED';

export interface PayResult {
  orderNo: string;
  payNo: string;
  channel: string;
  amount: number;
  payStatus: PayStatus;
  orderStatus: OrderStatus;
  orderStatusLabel: string;
  idempotentReplay: boolean;
  /** 二维码内容：沙箱为收银台地址；微信 Native 为 weixin:// 串 */
  codeUrl: string | null;
  /** 支付单过期时间（ISO-8601 UTC） */
  expireAt: string | null;
}

/** 手动触发补偿调度的结果（运维/自测用） */
export interface SweepResult {
  scanned: number;
  closed: number;
  released: number;
  releaseFailed: number;
  autoCompleted: number;
  fundsReleased: number;
  restocked: number;
  restockFailed: number;
}

export interface RefundResult {
  orderNo: string;
  orderStatus: OrderStatus;
  orderStatusLabel: string;
  refundAmount: number;
  fundsRefunded: boolean;
  settlementStatus: string | null;
  settlementStatusLabel: string | null;
  restockPending: boolean;
  refundedAt: string | null;
}

// ---------- 卖家中心（duoduo-trade · /api/seller，需登录 + 卖家角色） ----------

/** 商家端概览：订单状态分布 + 资金三栏（金额单位「分」） */
export interface SellerSummary {
  sellerId: string;
  role: string;
  /** true 表示可见范围额外包含平台自营（seller_id=0）：运营 / 管理员代运营时为真 */
  platformScope: boolean;
  pendingPaymentCount: number;
  /** 待发货 = 已付款，卖家该动了 */
  pendingShipCount: number;
  shippedCount: number;
  completedCount: number;
  refundedCount: number;
  closedCount: number;
  totalOrderCount: number;
  /** 托管中：买家实付总额 */
  custodyAmount: number;
  /** 托管中：卖家可得（已扣平台佣金） */
  custodySellerAmount: number;
  /** 已放款：买家实付总额 */
  releasedAmount: number;
  /** 已放款：卖家实收 */
  releasedSellerAmount: number;
  /** 已退回买家 */
  refundedAmount: number;
  generatedAt: string;
}

/** 资金对账：分页明细 + 全量汇总（汇总不受分页与状态过滤影响） */
export interface SettlementPage {
  items: SettlementView[];
  total: number;
  page: number;
  pageSize: number;
  custodyAmount: number;
  custodyFee: number;
  custodySellerAmount: number;
  releasedAmount: number;
  releasedFee: number;
  releasedSellerAmount: number;
  refundedAmount: number;
}

/**
 * E22 · 卖家钱包：「我现在能取多少」的唯一答案。
 *
 * ★ `netWithdrawableAmount` 才是真正能提的数，`withdrawableAmount` 是抵扣欠款前的毛额。
 *   两个都给，是因为用户看到「有 500 可提、却被扣了 200 欠款」时需要能对上账；
 *   只给净额会让人以为系统少给了钱。
 */
export interface SellerWalletView {
  /** 待结算：钱还在平台托管，订单尚未确认收货 */
  pendingAmount: number;
  /** 冻结中：已记账到卖家名下，但仍在售后期内（T+N 未到期），取不出去 */
  frozenAmount: number;
  /** 可提现（毛额）：已过售后期，尚未抵扣欠款 */
  withdrawableAmount: number;
  /** 累计到账：已记账给卖家的总额（含冻结中与已被垫付的） */
  releasedAmount: number;
  /** 未结欠款（平台垫付形成） */
  outstandingDebt: number;
  /** 可提现（净额）= 毛额 − 欠款，且不小于 0；**提现以此为准** */
  netWithdrawableAmount: number;
  /** 下一笔资金解冻时间（ISO-8601 UTC）；当前无冻结资金时为 null */
  nextReleaseAt: string | null;
  /** 售后期天数，前端据此解释「为什么还不能提」 */
  holdDays: number;
}

/**
 * E22 · 结算账单（租户 × 周期）。
 *
 * ★ 两段刻意分开，因为回答的是两个问题、可信度也不同：
 *   - 「本期发生额」按事件时间严格过滤，任何一天重跑都是同一个数（财务要的可复核性）；
 *   - 「余额快照」与钱包页同源，所以同一时刻两处显示的可提现必然是同一个数。
 * 金额一律整数「分」；periodStart 含、periodEnd 不含（左闭右开）。
 */
export interface SettlementBill {
  period: string;
  periodStart: string;
  periodEnd: string;
  tenantId: string;
  // —— 本期发生额 ——
  orderAmount: number;
  platformFeeAmount: number;
  sellerEarningAmount: number;
  orderCount: number;
  releasedAmount: number;
  releasedSellerAmount: number;
  releasedCount: number;
  unfrozenAmount: number;
  unfrozenCount: number;
  refundedAmount: number;
  refundedCount: number;
  advancedAmount: number;
  advancedCount: number;
  // —— 余额快照（与钱包同源，非期末值）——
  pendingAmount: number;
  frozenAmount: number;
  withdrawableAmount: number;
  outstandingDebt: number;
  snapshotAt: string;
}

/** 卖家发货请求；物流信息可选，随状态日志留痕 */
export interface ShipRequest {
  logisticsCompany?: string;
  logisticsNo?: string;
}

/** 平台商品的审核维度（与交易维度 status 正交，口径同闲置 ListingAuditStatus） */
export type ProductAuditStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

/**
 * 商家端「我的商品」视图：SPU 主档 + SKU 汇总（数量与总库存）。
 *
 * E14 起带出**审核维度**：列表里必须看得到「待审 / 已通过 / 已驳回」，
 * 否则卖家提交完就查无此项，只能反复重发；驳回原因更是刚需 ——
 * 不告诉他为什么被拒，他只能猜着改。
 */
export interface SellerProductView {
  id: string;
  title: string;
  mainImage: string | null;
  status: 'ON_SALE' | 'OFF_SHELF' | string;
  /** 状态中文文案由服务端给出，前端不维护映射表 */
  statusLabel: string;
  /** 审核态：自营商品恒 APPROVED（平台免审） */
  auditStatus: ProductAuditStatus | string;
  /** 审核态中文文案，同样由服务端下发 */
  auditLabel: string;
  /** 被驳回的原因；非驳回态为 null */
  auditReason: string | null;
  sellerId: string;
  minPrice: number;
  maxPrice: number;
  salesCount: number;
  /** 商品评分与评价数（2026-10-24）：来自评价域回写 */
  rating?: number;
  ratingCount?: number;
  skuCount: number;
  totalStock: number;
  /** 软删时间（A-09）：回收站条目非空，正常商品为 null */
  deletedAt?: string | null;
  /** 回收站条目将被物理清理的时间（deletedAt + 30 天）；正常商品为 null */
  purgeAt?: string | null;
}

/** 提交商品时的规格项；价格单位是**分**（表单填元，提交前 ×100） */
export interface SellerSkuInput {
  specName?: string | null;
  price: number;
  stock?: number | null;
  image?: string | null;
}

/**
 * 提交商品请求（E14）。
 *
 * 刻意**没有** sellerId / supplyType / auditStatus：
 * 归属由登录态推导、供货类型与审核态由服务端判定 ——
 * 请求体里给出任何一个，都等于让客户端自证「我该不该被审核」。
 */
export interface KvItem {
  name: string;
  value: string;
}

export interface SellerProductCreateRequest {
  title: string;
  subtitle?: string | null;
  categoryId: string;
  brandId?: string | null;
  mainImage: string;
  images?: string[] | null;
  detail?: string | null;
  /** 商品介绍（纯文本） */
  intro?: string | null;
  /** 富文本详情 HTML */
  detailHtml?: string | null;
  specParams?: KvItem[] | null;
  packaging?: KvItem[] | null;
  skus: SellerSkuInput[];
}

/**
 * 编辑商品请求（E14 扩展为全部内容字段）。
 *
 * 三态语义：不传 = 不改；空串 = 清空（副标题 / 详情 / 详情图可清空）；
 * 有值 = 替换。**内容变更会触发重新审核**（第三方商品），
 * 表单提交前要把「用户没填」显式转成 undefined。
 */
export interface SellerProductUpdateRequest {
  title?: string;
  subtitle?: string;
  mainImage?: string;
  images?: string[];
  detail?: string;
  intro?: string;
  detailHtml?: string;
  /** null/undefined = 不改；[] = 清空 */
  specParams?: KvItem[];
  packaging?: KvItem[];
  categoryId?: string;
  brandId?: string;
  /** 乐观锁版本（A-07）：编辑回填时读到，提交时必带；不一致服务端返回 409 */
  version: number;
}

/** SKU 改价 / 改库存项（2026-10-24）：只允许这两个字段，且不触发内容重审 */
export interface SkuPriceStockInput {
  id: string;
  /** 单位：分；不传 = 不改 */
  price?: number;
  /** 不传 = 不改 */
  stock?: number;
}

/** 规格视图（商家侧编辑回填 / 审核台核对） */
export interface SellerSkuView {
  id: string;
  specName: string | null;
  price: number;
  stock: number;
  image: string | null;
  status: string;
}

/** 商家视角商品详情（编辑回填来源；公开详情对未过审商品一律 404） */
export interface SellerProductDetailView {
  id: string;
  title: string;
  subtitle: string | null;
  categoryId: string | null;
  brandId: string | null;
  mainImage: string | null;
  images: string[];
  detail: string | null;
  intro?: string | null;
  detailHtml?: string | null;
  specParams?: KvItem[];
  packaging?: KvItem[];
  supplyType: string;
  status: string;
  statusLabel: string;
  auditStatus: ProductAuditStatus | string;
  auditLabel: string;
  auditReason: string | null;
  auditAt: string | null;
  sellerId: string;
  minPrice: number;
  maxPrice: number;
  skus: SellerSkuView[];
  /** 乐观锁版本（A-07）：编辑提交时原样带回 */
  version: number;
}

/** 平台审核台条目（E14）：带全部背审材料，运营不必点进二级页就能判断 */
export interface AdminProductAuditView {
  id: string;
  sellerId: string;
  title: string;
  subtitle: string | null;
  mainImage: string | null;
  images: string[];
  detail: string | null;
  /** 商品介绍（2026-10-24）：审核台需展示 */
  intro?: string | null;
  /** 富文本详情（服务端已消毒，2026-10-24）：审核台需展示 */
  detailHtml?: string | null;
  specParams?: KvItem[];
  packaging?: KvItem[];
  categoryId: string | null;
  categoryName: string | null;
  brandId: string | null;
  brandName: string | null;
  supplyType: string;
  status: string;
  auditStatus: ProductAuditStatus | string;
  auditLabel: string;
  auditReason: string | null;
  auditAt: string | null;
  minPrice: number;
  maxPrice: number;
  skus: SellerSkuView[];
  createdAt: string | null;
  /** 乐观锁版本（A-07）：审核结论以此版本为前置条件 */
  version: number;
}

/** 商品审核请求；驳回时 reason 必填（服务端会 400） */
export interface AdminProductAuditRequest {
  action: 'APPROVE' | 'REJECT';
  reason?: string;
  /** 乐观锁版本（A-07）：审核台看到的版本，内容被卖家改过则 409 */
  version: number;
}

/** 一条字段级变更（A-08）。before/after 是给运营看的展示值（富文本已转纯文本、KV 已展开） */
export interface SpuAuditFieldChange {
  field: string;
  label: string;
  before: string | null;
  after: string | null;
  /** 展示值超长（> 2000 码点）被截断；截断仍算一次变更，不会静默丢弃 */
  truncated: boolean;
}

/**
 * 商品审核历史的一条事件（A-08）。
 * id 是雪花 id 字符串 —— 端上禁止 Number()，排序/比较只按字符串或服务端顺序。
 */
export interface SpuAuditLogView {
  id: string;
  /** SUBMIT / EDIT / RESUBMIT / APPROVE / REJECT / SAMPLE_REJECT */
  event: string;
  eventLabel: string;
  /** SELLER / STAFF / OPERATOR / SYSTEM */
  actorType: string;
  actorLabel: string;
  actorId: string | null;
  actorPhone: string | null;
  fromAuditStatus: string | null;
  toAuditStatus: string | null;
  auditSource: string | null;
  reason: string | null;
  versionBefore: number | null;
  versionAfter: number;
  /** 抽查判违规时，抽中那一刻的商品版本；其余事件为 null */
  sampledVersion: number | null;
  changes: SpuAuditFieldChange[];
  createdAt: string | null;
}

// ---------- 平台管理后台（/api/admin，需运营 / 管理员） ----------

export interface CategorySaveRequest {
  name: string;
  parentId?: string;
  sortNo?: number;
  icon?: string;
}

export interface BrandSaveRequest {
  name: string;
  logo?: string;
  country?: string;
  story?: string;
}

/** 闲置审核列表条目（运营视角，含未审核通过的内容） */
export interface ListingAuditView {
  id: string;
  sellerId: string;
  title: string;
  price: number;
  conditionLevel: string;
  city: string | null;
  species: string | null;
  auditStatus: 'PENDING' | 'APPROVED' | 'REJECTED' | string;
  auditReason: string | null;
  createdAt: string | null;
}

export interface ListingAuditRequest {
  action: 'APPROVE' | 'REJECT';
  reason?: string;
}

// ---------------- 运营账号管理（A7，落在 duoduo-user） ----------------

/** 运营侧用户视图（脱敏）：不下发实名姓名 / 身份证掩码 / 完整手机号 */
export interface AdminUserView {
  id: string;
  phoneMasked: string;
  nickname: string;
  role: string;
  status: string;
  realNameVerified: boolean;
  creditScore: number | null;
  createdAt: string | null;
}

export interface RoleChangeRequest {
  role: 'USER' | 'SELLER' | 'OPERATOR' | 'ADMIN' | string;
}

export interface RoleChangeResult {
  user: AdminUserView;
  /**
   * 展示给运营的提示。**原样展示、不要在本地拼文案** —— 它说明这次变更生效到了哪一步：
   * E17 起正常情况是「…在下一个请求即生效（无需重新登录）」；
   * 只有 Redis 不可用、退回吊销刷新令牌时才会出现「…将重新登录后生效」。
   * 两者的处置完全不同（一个什么都不用做，一个要通知对方重登），所以文案本身就是处置依据。
   */
  notice: string;
}

/**
 * 后台操作审计记录（E18）。
 *
 * **全部字段只读**：审计页只有一个 GET 入口，服务端刻意不提供任何修改/删除路径
 * （完整性靠「没有修改路径」保证，而不是靠「没人会去改」）。
 *
 * 几个口径：
 *  - `id` / `actorId` / `targetId` / `tenantId` 一律是**字符串**：雪花 ID 超出 JS 安全整数
 *    范围，转 Number 会丢精度（T-39）；
 *  - `targetId === '0'` 表示**平台级操作、无单一目标**（全站索引重建、资质巡检），
 *    与「找不到目标」不是一回事；
 *  - `tenantId === '0'` 表示平台级 / 自营操作，不属于任何店铺；
 *  - `actorPhone` **刻意不脱敏**（与账号列表的 `phoneMasked` 不同）：审计页的用途是追责，
 *    手机号是定位到具体账号的唯一稳定标识；
 *  - `immutable` 由**服务端**按唯一判据（SECURITY / FUNDS 档）算出后下发，
 *    前端不得自行判断 —— 否则要再抄一份规则，两份规则迟早分叉。
 */
export interface AuditLogView {
  id: string;
  actorId: string | null;
  actorPhone: string;
  /** 动作标识（枚举名，如 USER_ROLE_CHANGE / ORDER_FORCE_CLOSE） */
  action: string;
  /** 动作类目：SECURITY / FUNDS / TENANT / CONTENT / OTHER */
  category: string;
  /** 类目是否属「不可删改」档（资金与权限类），由服务端算出 */
  immutable: boolean;
  /** 结果：SUCCESS / FAILED（失败的越权尝试同样留痕） */
  outcome: string;
  targetId: string;
  targetType: string;
  tenantId: string;
  beforeValue: string | null;
  afterValue: string | null;
  ip: string | null;
  requestId: string | null;
  detail: string;
  createdAt: string | null;
}

/** 审计检索条件（全部可选；不传即「最近若干条」）。起止时间止端按**含**处理。 */
export interface AuditLogQuery {
  actorId?: string;
  tenantId?: string;
  category?: string;
  action?: string;
  outcome?: string;
  targetId?: string;
  /** `2026-10-09`（整日）或 `2026-10-09T13:00:00` */
  from?: string;
  to?: string;
  page?: number;
  pageSize?: number;
}

/** 全平台托管资金汇总（与商家端按卖家过滤的口径分开） */
export interface AdminSettlementSummary {
  custodyAmount: number;
  custodyFee: number;
  custodyCount: number;
  releasedAmount: number;
  releasedFee: number;
  releasedCount: number;
  refundedAmount: number;
  refundedCount: number;
}

/** 异常挂账：已付款却缺托管单的订单 */
export interface AbnormalSettlement {
  orderNo: string;
  orderStatus: string;
  payAmount: number;
}

/** 运营概览报表 */
export interface AdminReport {
  totalOrders: number;
  paidOrders: number;
  gmv: number;
  refundedOrders: number;
  refundRate: string;
  conversionRate: string;
}

export interface ForceCloseRequest {
  reason?: string;
}

// ---------- 评价与晒单（/api/reviews，履约域） ----------

export interface ReviewView {
  id: string;
  orderNo: string;
  spuId: string;
  skuId: string | null;
  /** 商品快照标题（下单时的商品名，商品改名不影响历史评价） */
  productTitle: string;
  productImage: string | null;
  rating: number;
  content: string | null;
  images: string[];
  anonymous: boolean;
  /** 匿名评价此处为脱敏昵称 */
  nickname: string;
  avatar: string | null;
  appendContent: string | null;
  appendAt: string | null;
  createdAt: string;
  /** 是否还在可追评窗口内（前端据此决定显示追评入口） */
  appendable: boolean;
}

export interface ReviewSummary {
  spuId: string;
  total: number;
  avgRating: string;
  goodRate: string;
  /** 下标 0 = 1 星 … 下标 4 = 5 星（顺序固化进契约，前端不必自己排序） */
  starCounts: number[];
  imageCount: number;
  appendCount: number;
}

/** 单件商品的评价内容（一次可提交订单里多件） */
export interface ReviewItemRequest {
  spuId: string;
  skuId?: string | null;
  rating: number;
  content?: string;
  images?: string[];
}

export interface ReviewCreateRequest {
  orderNo: string;
  anonymous?: boolean;
  items: ReviewItemRequest[];
}

export interface ReviewCreateResult {
  created: number;
  reviews: ReviewView[];
}

export interface ReviewedOrders {
  orderNos: string[];
}

// ---------- 售后仲裁（/api/aftersales，履约域 A3） ----------

export type AfterSaleStatus =
  | 'PENDING'
  | 'SELLER_REJECTED'
  /** 卖家已同意退货，等待买家寄回（第五十八轮 P1-5） */
  | 'WAITING_RETURN'
  /** 买家已寄回，等待卖家签收 —— 退款在卖家签收那一刻才发生 */
  | 'RETURNING'
  | 'ARBITRATING'
  | 'REFUNDED'
  | 'CLOSED';

export type AfterSaleType = 'REFUND_ONLY' | 'RETURN_REFUND';

/** 卖家处理动作：AGREE 会**立刻执行退款**，REJECT 必须填说明 */
export type AfterSaleReplyAction = 'AGREE' | 'REJECT';

/** 运营裁定结论：APPROVE_REFUND 会**立刻执行退款** */
export type AfterSaleArbitrateResult = 'APPROVE_REFUND' | 'REJECT';

/**
 * 售后单。
 *
 * ⚠️ `id` 是 **string** 而不是 number：主键是 18 位雪花值（如 359291401479008256），
 * 超过 JS 的 Number.MAX_SAFE_INTEGER，序列化成数字会被 JSON.parse 静默改写，
 * 拿它拼 URL 就会打到一条不存在的单子上（表现为莫名的 404）。
 * 服务端刻意以字符串下发，这里原样透传即可 —— **绝不**做任何数值运算。
 */
export interface AfterSaleView {
  id: string;
  orderNo: string;
  buyerId: string;
  sellerId: string;
  type: AfterSaleType | string;
  typeLabel: string;
  reason: string;
  images: string[];
  status: AfterSaleStatus | string;
  statusLabel: string;
  sellerReply: string | null;
  arbitrateResult: AfterSaleArbitrateResult | string | null;
  arbitrateNote: string | null;
  /** 实际退款金额（分），由交易域退款结果回填；未退款时为 null */
  refundAmount: number | null;
  /** 买家寄回的快递公司（仅退货退款，未寄回时 null） */
  returnCourier: string | null;
  /** 买家寄回的运单号 */
  returnTrackingNo: string | null;
  /** 已寄回时间（ISO-8601 UTC） */
  returnShippedAt: string | null;
  /** 卖家确认收货时间（退款在这一刻发生） */
  returnReceivedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AfterSaleCreateRequest {
  orderNo: string;
  type: AfterSaleType;
  reason: string;
  /** 举证图直链（只收 http/https，最多 6 张，服务端校验） */
  images?: string[];
}

export interface AfterSaleReplyRequest {
  action: AfterSaleReplyAction;
  reply?: string;
}

export interface AfterSaleArbitrateRequest {
  result: AfterSaleArbitrateResult;
  note: string;
}

// ---------- 物流轨迹（/api/logistics，履约域 A2） ----------

/**
 * 物流节点码。白名单由服务端定义（`LogisticsNode`），推送方只能选用不能自创 ——
 * 前端只在需要「按节点类型换图标 / 判断是否已签收」时才用它比较，
 * 界面上的中文一律用服务端下发的 `nodeLabel`，不在这里维护第二份映射
 * （两份映射迟早漂移，而「同一节点在买家页和卖家页叫法不同」在用户眼里就是系统坏了）。
 */
export type LogisticsNodeCode =
  | 'PICKED_UP'
  | 'IN_TRANSIT'
  | 'ARRIVED_HUB'
  | 'OUT_FOR_DELIVERY'
  | 'SIGNED'
  | 'REJECTED'
  | 'EXCEPTION';

/** 轨迹节点。`occurredAt` 是节点**发生**时间（ISO-8601 / UTC），前端只展示不计算。 */
export interface TrackView {
  nodeCode: LogisticsNodeCode | string;
  /** 服务端给出的中文文案（如「已揽收」），前端直接用 */
  nodeLabel: string;
  /** 节点描述原文；推送方未给时由服务端兜底为节点名 */
  nodeText: string;
  occurredAt: string;
}

/**
 * 某订单的轨迹。
 *
 * `total` 为 0 是**正常状态**（未发货，或已发货但推送还没到），不是错误 ——
 * 服务端刻意回空列表而不是 404，前端据此渲染「暂无物流信息」空态。
 */
export interface TrackListView {
  orderNo: string;
  total: number;
  tracks: TrackView[];
}

// ---------- 信用分（/api/reviews/seller/{sellerId}/credit，履约域 A4） ----------

/** 信用等级编码。中文文案由服务端下发，前端不维护第二份映射。 */
export type CreditLevel = 'EXCELLENT' | 'GOOD' | 'FAIR' | 'POOR';

/**
 * 单个因子明细。
 *
 * `weight` 是该因子满分（权重表的一项），`points` 是本次得分，
 * **四项 points 之和恒等于总分** —— 这是服务端在模型层保证的结构不变式，
 * 前端因此可以直接把明细当成「分数的拆解」展示，不必担心对不上。
 */
export interface CreditFactor {
  code: string;
  label: string;
  weight: number;
  /** 人类可读的原始值，如「100%（完成 4 / 已了结 4）」 */
  rawValue: string;
  points: number;
}

/** 计算所用的原始输入。只有计数与评分，**不含任何身份字段**（手机号 / 昵称 / 收货人）。 */
export interface CreditInputs {
  completedOrders: number;
  closedOrders: number;
  refundedOrders: number;
  totalOrders: number;
  reviewCount: number;
  /** 平均评分，两位小数字符串（如 "4.00"），避免浮点噪声 */
  avgRating: string;
  adverseAfterSales: number;
}

/**
 * 卖家信用分（公开只读）。
 *
 * 未登录访客也能读（网关白名单放行）：信用分是「购物决策信息」，
 * 与商品评价同性质 —— 看不见口碑就没法决定买不买。
 *
 * 无交易/评价记录的用户得分即**基础分**（不是 0、也不是 null），
 * 因此这里 `score` 恒为数字，前端不必写空态兜底。
 */
export interface CreditView {
  userId: string;
  score: number;
  level: CreditLevel | string;
  /** 等级中文文案（优秀 / 良好 / 一般 / 待提升） */
  levelLabel: string;
  factors: CreditFactor[];
  inputs: CreditInputs;
  /** 服务端计算时刻（ISO-8601 UTC） */
  generatedAt: string;
}

// ---------- 优惠券（/api/coupons，营销域 A5） ----------

/** 券状态（与后端 com.duoduo.marketing.support.CouponStatus 一致） */
export type CouponStatus = 'UNUSED' | 'LOCKED' | 'USED' | 'EXPIRED';

/** 券类型：v1 只实现满减 */
export type CouponType = 'FULL_REDUCE';

/**
 * 券模板（领券中心的一行）。
 *
 * `claimable` 与 `claimableReason` 由**服务端**算好一起返回：
 * 「为什么这张券不能领」要看领取窗口、剩余量、以及**我是否已领过**，
 * 后者依赖登录态，前端拿不到；与其让前端猜，不如服务端直接给结论。
 */
export interface CouponTemplateView {
  id: string;
  name: string;
  description: string | null;
  type: CouponType | string;
  /** 券类型中文文案，前端不维护映射表 */
  typeLabel: string;
  /** 满减门槛（分）；0 表示无门槛 */
  thresholdAmount: number;
  /** 抵扣金额（分） */
  discountAmount: number;
  totalCount: number;
  claimedCount: number;
  remainingCount: number;
  startAt: string;
  endAt: string;
  status: string;
  claimable: boolean;
  /** 不可领时的原因文案；可领时为 null */
  claimableReason: string | null;
}

/** 用户手上的一张券（我的券包） */
export interface CouponView {
  /** 券码：**字符串**，不要做数值转换（雪花 ID 会丢精度，券码则刻意用随机串规避） */
  code: string;
  templateId: string;
  name: string | null;
  type: CouponType | string | null;
  typeLabel: string | null;
  thresholdAmount: number;
  discountAmount: number;
  status: CouponStatus | string;
  statusLabel: string;
  claimedAt: string;
  expireAt: string;
  /** 被哪个订单锁定；未锁定为 null */
  lockedOrderNo: string | null;
  /** 被哪个订单核销；未核销为 null */
  usedOrderNo: string | null;
}

/** 领券结果；`replay=true` 表示「你已经领过这张券」，本次没有新建 */
export interface CouponClaimResult {
  code: string;
  templateId: string;
  name: string;
  thresholdAmount: number;
  discountAmount: number;
  expireAt: string;
  replay: boolean;
}

/** 结算页的一条候选券 */
export interface UsableCoupon {
  code: string;
  name: string;
  thresholdAmount: number;
  discountAmount: number;
  expireAt: string;
  usable: boolean;
  /** 不可用时的原因；可用时为 null */
  reason: string | null;
}

/**
 * 结算页可用券结果。
 *
 * `bestCouponCode` 是**服务端**挑出的最优券（抵扣最大），前端默认选中它 ——
 * 「默认帮用户选最省钱的一张」只该有一个实现，否则会出现
 * 「页面显示已选 A、实际下单锁的是 B」。
 */
export interface UsableCouponResult {
  amount: number;
  coupons: UsableCoupon[];
  bestCouponCode: string | null;
  bestDiscountAmount: number;
}

// ---------- AI 导购「多多」（A9，duoduo-ai） ----------

/**
 * 能力声明。前端据此决定入口与提示文案。
 *
 * mode：`mock` 演示模式（推荐仍来自真实商品库，只是话术不由大模型生成）/
 * `llm` 已接大模型 / `unconfigured` 未配置（此时 chatAvailable=false，
 * 页面应直接说明而不是让人对着输入框反复点发送）。
 */
export interface DuoduoCapability {
  chatAvailable: boolean;
  mode: 'mock' | 'llm' | 'unconfigured' | string;
  hint: string;
}

/** 导购推荐卡片：价格单位为分，与商品详情同一套语义 */
export interface DuoduoRecommendation {
  id: number;
  title: string;
  subtitle: string | null;
  minPrice: number;
  maxPrice: number;
  inStock: boolean;
}

/** 一轮历史对话（回传给服务端做上下文）。role 只认 user / assistant */
export interface DuoduoChatTurn {
  role: 'user' | 'assistant';
  content: string;
}

/**
 * 对话响应。
 *
 * `recommendations` 每一条都来自真实商品库（服务端先召回再组织话术），
 * 前端按普通商品卡片渲染即可 —— 不需要再校验真实性，那是服务端的责任。
 */
export interface DuoduoChatResponse {
  reply: string;
  recommendations: DuoduoRecommendation[];
  /** mock / llm：一眼看清这句话是谁生成的 */
  mode: string;
}

// ---------- 通用 ----------

export interface PageResult<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
  pageCount: number;
}

// ---------- 限时秒杀（A14，交易域 duoduo-trade） ----------

/** 秒杀 SKU 视图：金额单位为分 */
export interface SeckillSkuView {
  skuId: string;
  /**
   * 所属 SPU id（商品详情按 SPU 寻址）。
   *
   * ⚠️ 不能拿 skuId 当 spuId 去拼 `/product/{id}`：两者是不同的主键，
   * 用 skuId 请求详情接口会 404「商品不存在」。可能为 null（商品服务查不到该 SKU），
   * 此时标题应降级为不可点，不要退回用 skuId。
   */
  spuId: string | null;
  spuTitle: string;
  specText: string | null;
  image: string | null;
  seckillPrice: number;
  originalPrice: number;
  totalStock: number;
  availableStock: number;
  perLimit: number;
}

/** 活动视图：phase 由服务端按当前时间计算（ONGOING / UPCOMING / ENDED） */
export interface SeckillActivityView {
  id: string;
  title: string;
  startAt: string;
  endAt: string;
  phase: 'ONGOING' | 'UPCOMING' | 'ENDED';
  /**
   * 运营开关：false = 已下架，C 端查不到。
   * 运营端列表**仍会下发**下架的活动，否则下架后没有任何入口能重新上架。
   */
  enabled: boolean;
  skus: SeckillSkuView[];
}

/** 运营端创建秒杀活动的入参 */
export interface SeckillActivityCreateRequest {
  title: string;
  /** ISO 字符串（不带时区后缀，服务端按 UTC 挂钟解析） */
  startAt: string;
  endAt: string;
  skus: Array<{
    skuId: string;
    seckillPrice: number;
    originalPrice?: number | null;
    totalStock: number;
    perLimit?: number | null;
  }>;
}

/** 秒杀活动编辑（R74）：**只开放标题与时间** —— 商品 / 价格 / 配额改动会让配置与已成交事实对不上 */
export interface SeckillActivityEditRequest {
  title: string;
  /** ISO 字符串（不带时区后缀，服务端按 UTC 挂钟解析） */
  startAt: string;
  endAt: string;
}

/** 秒杀下单结果：拿 orderNo 去订单详情走沙箱支付 */
export interface PlaceSeckillResult {
  orderNo: string;
  payAmount: number;
  seckillPrice: number;
  /** true = 同幂等键重放，返回的是首次订单 */
  replay: boolean;
}

/* ── 分享 / 评论举报（第五十六轮，落在 duoduo-fulfilment） ── */

/** 举报对象类型：分享 或 评论 */
export type ShareReportTargetType = 'SHARE' | 'COMMENT';
/** 举报处理状态：待处理 / 成立（内容已下架）/ 不成立 */
export type ShareReportStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED';

export interface ShareReportView {
  /** 雪花 id —— 一律 string，禁止参与数值运算（JS 安全整数只有 16 位） */
  id: string;
  targetType: ShareReportTargetType | string;
  targetId: string;
  reporterId: string;
  reason: string;
  status: ShareReportStatus | string;
  createdAt: string;
  handledBy: string | null;
  handledAt: string | null;
  handleNote: string | null;
  /**
   * 被举报内容的摘要（服务端裁剪到 ~60 字）。
   * 列表里必须显示它 —— 否则运营得逐条点开才知道在举报什么，处理效率低到没人愿意处理。
   */
  targetExcerpt: string;
  /** 被举报内容的作者 id（用于识别「同一个人反复被举报」） */
  targetAuthorId: string | null;
  /** 被举报内容所属分享 id（举报评论时用于核对上下文） */
  targetShareId: string | null;
}

export interface ShareReportHandleRequest {
  /** ACCEPT = 成立（会把内容下架）；REJECT = 不成立 */
  action: 'ACCEPT' | 'REJECT';
  /** 处理说明，必填 —— 这是回应申诉的唯一凭据 */
  note: string;
}

// ---------- 图表统计（第六十六轮 · duoduo-trade） ----------
//
// 后端把柱状/饼图/横条统一成一种形状 `Slice`（名称 → 数值），
// 前端因此不需要为每种图各写一份数据组装逻辑，换图类型只是换渲染器。
//
// **数值一律是原始值**：金额单位「分」、计数单位「笔」，
// 千分位与「¥」由展示层处理 —— 后端一旦下发 "¥1,234.00" 这类字符串，
// 前端就无法做占比、排序、同比，等于把数据降级成文案。

/** 通用图表切片：柱状/饼图/横条共用 */
export interface AnalyticsSlice {
  /** 展示名（后端已本地化，前端不再维护映射表） */
  name: string;
  /** 原始数值：计数类为「笔」，金额类为「分」 */
  value: number;
}

/** 趋势点；date 为 UTC 自然日 yyyy-MM-dd */
export interface AnalyticsTrendPoint {
  date: string;
  /** 当日下单数（按下单时间归集） */
  orderCount: number;
  /** 当日成交额（分）：只含「已付款及之后」的订单 */
  gmv: number;
}

/** 商家端多维度统计 */
/** 按商品的经营统计（2026-10-24）：销量 / 成交额 / 退款件数 */
export interface SellerProductStat {
  spuId: string;
  name: string;
  quantity: number;
  amount: number;
  refundQty: number;
}

export interface SellerAnalytics {
  /** 维度一：订单状态分布 */
  statusDistribution: AnalyticsSlice[];
  /** 维度二：近 14 天成交趋势（**已补零**，无成交的日期也在，不会断线） */
  trend: AnalyticsTrendPoint[];
  /** 维度三：商品销量 Top N（value = 销量件数） */
  topProducts: AnalyticsSlice[];
  /** 维度四：资金构成（value = 金额，分） */
  fundComposition: AnalyticsSlice[];
  /** 维度五：订单类型占比 */
  orderTypeMix: AnalyticsSlice[];
  generatedAt: string;
}

/** 平台后台图表数据 */
export interface AdminCharts {
  statusDistribution: AnalyticsSlice[];
  trend: AnalyticsTrendPoint[];
  topProducts: AnalyticsSlice[];
  fundComposition: AnalyticsSlice[];
  generatedAt: string;
}

// ---------- 我的闲置（第六十六轮 · A 方案补齐 Web 端） ----------

/** 交易维度（与审核维度正交） */
export type ListingStatus = 'ON_SALE' | 'OFF_SHELF' | 'SOLD';
/** 审核维度 */
export type ListingAuditStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

/** 我的发布条目（卖家视角：必须带出审核态与驳回原因，否则发布完就「查无此项」） */
export interface ListingMineView {
  id: string;
  title: string;
  price: number;
  conditionLevel: string | null;
  city: string | null;
  cover: string | null;
  species: string | null;
  status: ListingStatus;
  statusLabel: string;
  auditStatus: ListingAuditStatus;
  /** 被驳回的原因 —— 不给原因卖家只能猜着改 */
  auditReason: string | null;
  viewCount: number;
  wantCount: number;
  createdAt: string;
}

/** 卖家视角详情：编辑表单的回填来源（公开详情对未过审内容一律 404） */
export interface ListingOwnerView {
  id: string;
  title: string;
  description: string | null;
  categoryId: string | null;
  price: number;
  originalPrice: number | null;
  conditionLevel: string | null;
  city: string | null;
  images: string[];
  species: string | null;
  breed: string | null;
  status: ListingStatus;
  statusLabel: string;
  auditStatus: ListingAuditStatus;
  auditReason: string | null;
  viewCount: number;
  wantCount: number;
  createdAt: string;
}

/**
 * 发布 / 编辑 / 重提共用的请求体。
 *
 * 价格单位是**分**（后端 `requirePrice` 直接按分校验上限 99999 元）；
 * 表单里用户填的是元，提交前必须换算，别把「元」当「分」发上去 —— 那会让价格差 100 倍。
 */
export interface ListingPublishRequest {
  title: string;
  description?: string | null;
  categoryId?: string | null;
  price: number;
  originalPrice?: number | null;
  conditionLevel?: string | null;
  city?: string | null;
  images?: string[];
  species?: string | null;
  breed?: string | null;
}

// ---------- 通知中心 / 关注与粉丝（第六十七轮 · Web 端功能对齐） ----------

/** 通知条目：定向通知与全站公告在接口层统一为同一视图 */
export interface NoticeView {
  id: string;
  /** 分类（order / aftersale / notice …），后端白名单校验 */
  category: string;
  title: string;
  content: string;
  bizType: string | null;
  bizId: string | null;
  /** 小程序端落地页（`/pages/...`），网页端**不可直接使用** */
  jumpPage: string | null;
  /**
   * 网页端落地页（如 `/orders/2026…`）；为 null 表示该通知在网页端没有对应页面，
   * 此时应「只标已读、不跳转」，而不是拿 jumpPage 去 navigate（会 404）。
   */
  webPath: string | null;
  read: boolean;
  createdAt: string;
}

export interface NoticePageData {
  items: NoticeView[];
  total: number;
  page: number;
  pageSize: number;
}

export interface UnreadCount {
  count: number;
}

/**
 * 未读汇总：**通知 + 私信合并口径**（服务端算好再下发，前端不自己相加）。
 *
 * 全站未读角标一律读这一个接口 —— 若各页面各算一遍（通知查一次、私信查一次再相加），
 * 迟早出现「首页角标 3、切到通知页变 5」这类自相矛盾；用户不会怀疑口径，只会认为数字是错的。
 * 与小程序 `utils/unread.js` 读的是同一个 `/api/socials/notices/unread-summary`。
 */
export interface UnreadSummary {
  notice: number;
  message: number;
  /** 客服未读（第七十八轮起独立成表；第七十九轮起也出现在通知中心） */
  support: number;
  total: number;
}

// ---------- 私信（R74-5 · Web 端补齐） ----------

/**
 * 会话列表项：每会话最新一条 + 未读数 + **我是否还能发**。
 *
 * `canSend` 与 `mutual` 必须由前端如实呈现：非互关时每对用户**只能发一条**（咨询会话豁免），
 * 服务端会拒第二条 —— 若前端不显示原因、只让输入框静默失败，用户会以为「发出去丢了」。
 */
export interface ConversationView {
  peerId: string;
  nickname: string;
  avatar: string | null;
  lastContent: string;
  lastBizType: string | null;
  lastBizId: string | null;
  lastAt: string;
  /** 未读数（服务端按 NumericLongSerializer 下发为数字） */
  unread: number;
  mutual: boolean;
  canSend: boolean;
  /** 是否为商品 / 闲置咨询会话（咨询会话豁免「非互关一条」限制） */
  consulting: boolean;
  productId: string | null;
  productTitle: string | null;
  productImage: string | null;
  listingId: string | null;
  listingTitle: string | null;
  listingImage: string | null;
}

export interface ChatMessageView {
  id: string;
  senderId: string;
  receiverId: string;
  senderRole: string;
  /** TEXT / PRODUCT / IMAGE / VIDEO / EMOJI */
  bizType: string;
  bizId: string | null;
  content: string;
  /** 媒体地址（IMAGE / VIDEO / EMOJI）；其它类型为 null */
  mediaUrl?: string | null;
  thumbUrl?: string | null;
  width?: number | null;
  height?: number | null;
  durationMs?: number | null;
  createdAt: string;
  /** 是否我发的（展示左右分列的依据；前端不再自己比 userId） */
  mine: boolean;
  read: boolean;
  /** 撤回时间（2026-10-24）；null=未撤回。撤回后 content 已是占位符、媒体已清空 */
  recalledAt?: string | null;
  /** 引用的消息 id；null=无引用 */
  replyToId?: string | null;
  /** 服务端拼装的引用摘要 */
  quoted?: QuotedView | null;
}

/** 引用摘要（2026-10-24）：服务端拼装，不信任端侧；不递归嵌套 */
export interface QuotedView {
  id: string;
  bizType: string | null;
  excerpt: string;
}

export interface MessagePageData {
  items: ChatMessageView[];
  total: number;
  page: number;
  pageSize: number;
  peerId: string;
  peerNickname: string;
  peerAvatar: string | null;
  mutual: boolean;
  canSend: boolean;
  /** 会话键（2026-10-23）：实时通道的「正在输入」原样回传，服务端据此定对端 */
  conversationId?: string;
}

/** 关注/粉丝条目 */
export interface FollowView {
  userId: string;
  nickname: string;
  avatar: string | null;
  /** 是否互相关注 */
  mutual: boolean;
  followedAt: string;
}

export interface FollowState {
  following: boolean;
  followedBy: boolean;
  mutual: boolean;
}

export interface FollowCounts {
  followers: number;
  following: number;
}

/**
 * 他人主页（S6-D1 补缺 · Web 端对齐小程序 `pages/user-profile`）。
 * 后端 `GET /api/socials/users/{userId}/profile` 一次拿齐：资料 + 计数 + 我与 TA 的关系。
 */
export interface UserProfileView {
  userId: string;
  nickname: string;
  avatar: string | null;
  followers: number;
  following: number;
  /** 我是否关注了 TA */
  viewerFollowing: boolean;
  /** TA 是否关注了我 */
  viewerFollowedBy: boolean;
  /** 互相关注 */
  mutual: boolean;
}

// ---------- 店铺公开主页（第八十二轮，duoduo-user） ----------

/** 店铺主页页头信息：仅营业中（APPROVED）的店铺可查，其余 404 */
export interface StorePublicView {
  /** 店主 userId（与 t_spu.seller_id 同键）；注意 18 位雪花 id，前端按 string 处理 */
  ownerUserId: string;
  /** 店铺名（全局唯一） */
  name: string;
  /** 开店时间（审核通过时刻，ISO-8601 UTC），可能为空 */
  openedAt: string | null;
}

// ---------- 分享圈（第七十一轮 · Web 端功能对齐 P1，duoduo-fulfilment） ----------

/** 分享条目：原创与转发共用（转发多出 parent* 引用字段） */
export interface ShareView {
  id: string;
  /** 作者：原创 = 发帖人，转发 = 转发人（服务端口径） */
  userId: string;
  spuId: string;
  productTitle: string;
  productImage: string | null;
  minPrice: number;
  content: string;
  nickname: string;
  avatar: string | null;
  createdAt: string;
  likeCount: number;
  favoriteCount: number;
  commentCount: number;
  liked: boolean;
  favorited: boolean;
  /** ORIGINAL | REPOST */
  shareType: string;
  parentId: string | null;
  parentNickname: string | null;
  /** 原帖被作者删除时为 null（删除意图必须被尊重） */
  parentContent: string | null;
  parentDeleted: boolean;
  edited: boolean;
  /** 话题名（不含 #，最多 2 个） */
  topics: string[];
  /** 服务端判定（前端拿不到自己的雪花 id，能不算就不算） */
  mine: boolean;
}

/** 评论条目：一级评论与回复共用（回复多 replyToNickname） */
export interface ShareCommentView {
  id: string;
  shareId: string;
  userId: string;
  nickname: string;
  avatar: string | null;
  content: string;
  createdAt: string;
  likeCount: number;
  liked: boolean;
  mine: boolean;
  replyToNickname: string | null;
  /** 仅一级评论有意义 */
  replyCount: number;
}

/** 话题：名称 + 被使用次数 */
export interface ShareTopicView {
  name: string;
  shareCount: number;
}

/** 点赞/收藏 toggle 后的状态：active = 现在是否生效，count = 新总数 */
export interface EngagementState {
  active: boolean;
  count: number;
}

/** 发布：商品必填，内容 ≤500 字，话题最多 2 个 */
export interface ShareCreateRequest {
  spuId: string;
  content?: string | null;
  topics?: string[] | null;
}

/** 编辑：只改文字与话题；topics 传 null = 不改，传 [] = 清空 */
export interface ShareEditRequest {
  content?: string | null;
  topics?: string[] | null;
}

// ---------- 商家入驻申请（E10） ----------

/** 结构化驳回原因：scope 指明驳回对象（GENERAL / STORE_NAME / 材料类型名） */
export interface MerchantRejectReason {
  scope: string;
  code: string;
  note: string;
}

/** 店铺成员身份简报（E13）：null = 不属于任何店（店主走 role 通道） */
export interface MerchantBrief {
  merchantId: string;
  storeName: string;
  memberRole: 'OWNER' | 'STAFF';
  status: 'ACTIVE' | 'DISABLED';
}

/** 店员视图（店主管理页，E13） */
export interface StaffView {
  memberId: string;
  userId: string;
  phoneMasked: string;
  nickname: string;
  role: 'OWNER' | 'STAFF';
  status: 'ACTIVE' | 'DISABLED';
  invitedBy: string | null;
  createdAt: string | null;
}

/** 平台侧成员总览项（跨租户，E13） */
export interface StaffAdminView {
  memberId: string;
  merchantId: string;
  storeName: string;
  userId: string;
  phoneMasked: string;
  role: 'OWNER' | 'STAFF';
  status: 'ACTIVE' | 'DISABLED';
  createdAt: string | null;
}

/** 审核台列表项（E11；无材料明细 —— 看材料请进详情） */
export interface MerchantApplicationSummary {
  merchantId: string;
  storeName: string;
  status: 'DRAFT' | 'SUBMITTED' | 'REVIEWING' | 'APPROVED' | 'REJECTED' | 'SUSPENDED';
  ownerUserId: string;
  /** 店主手机号掩码（139****0001）；后端脱敏后下发 */
  ownerPhoneMasked: string;
  appliedAt: string | null;
  reviewedAt: string | null;
  /** E15 · 资质最早到期日；null = 长期有效 */
  licenseExpireAt: string | null;
  /** E15 · 资质档位（审核台据此标出临期 / 已停业的店） */
  licenseStage: MerchantLicenseStage;
  /** E15 · 停业原因（SUSPENDED 时有值）—— 运营看到「已停业」时最需要知道的就是这个 */
  suspendedReason: string | null;
}

/** 入驻申请视图（商家侧与审核台共用） */
export interface MerchantApplicationView {
  merchantId: string;
  storeName: string;
  status: 'DRAFT' | 'SUBMITTED' | 'REVIEWING' | 'APPROVED' | 'REJECTED' | 'SUSPENDED';
  isolationLevel: string;
  rejectReasons: MerchantRejectReason[];
  /** 材料类型名 → 文件 URL（未上传的类不出现在对象里） */
  materials: Record<string, string>;
  ownerUserId: string;
  appliedAt: string | null;
  reviewedAt: string | null;
  createdAt: string | null;
  /** E15 · 资质最早到期日（ISO）；null = 长期有效（不是「今天到期」） */
  licenseExpireAt: string | null;
  /** E15 · 资质档位：NONE 正常 / T30 即将到期 / T7 临近到期 / EXPIRED 已过期 */
  licenseStage: MerchantLicenseStage;
  /** E15 · 剩余日历天数；null = 无到期概念（0 才表示今天到期） */
  licenseRemainingDays: number | null;
  /** E15 · 停业原因（SUSPENDED 时有值） */
  suspendedReason: string | null;
  /** E15 · 材料类型名 → 有效期至（yyyy-MM-dd，仅含已填写的类） */
  materialExpiry: Record<string, string>;
}

/** 资质到期档位（E15）。顺序即紧急程度，可直接用于排序/比较 */
export type MerchantLicenseStage = 'NONE' | 'T30' | 'T7' | 'EXPIRED';

/** 档位对应的中文说明与配色意图（商家端横幅、后台审核台共用，避免两处各写一套文案） */
export const MERCHANT_LICENSE_STAGE_LABEL: Record<MerchantLicenseStage, string> = {
  NONE: '正常',
  T30: '即将到期',
  T7: '临近到期',
  EXPIRED: '已过期',
};

/**
 * 资质巡检结果（E15）。运营手动触发一轮「临期提醒 + 过期停业」后的处理计数。
 *
 * 为什么要有手动触发：到期提醒与自动停业若只能靠定时器观察，改完一份材料的有效期后
 * 想确认后果就只剩「等下一轮」这一条路 —— 验收与排障都被迫掐着表。有了它才能当场验证。
 */
export interface MerchantLicenseSweepReport {
  /** 本轮因过期被停业的店铺数 */
  suspended: number;
  /** 本轮成功发出提醒的店铺数（同档位不会重复发） */
  reminded: number;
  /** 提醒没发出去、留待下一轮重试的店铺数（刻意不记档位，见后端注释） */
  notifyFailed: number;
  /** 停业成功但商品批量下架失败的店铺数（>0 需人工核查） */
  offShelfFailed: number;
}

// ---------- 审核 SLA：事后抽查（E28） ----------

/**
 * 抽查任务状态。
 *
 * 与商品/入驻的 `auditStatus` **刻意不共用一套常量**：那是「这条内容能不能上」的结论，
 * 这是「我回头看了一眼，抽中的这条合不合格」的结论。混用会让「PENDING」在页面上
 * 同时意味着「等着上架」和「已经在卖、等着复核」，运营必然看错。
 */
export type AuditSampleStatus = 'PENDING' | 'PASSED' | 'REJECTED';

export const AUDIT_SAMPLE_STATUS_LABEL: Record<string, string> = {
  PENDING: '待复核',
  PASSED: '复核通过',
  REJECTED: '判违规',
};

/** 一条商品侧抽查任务（E28）。`spuTitle` / `sellerId` 由服务端冗余带出，避免列表 1+N 查询 */
export interface AuditSampleView {
  id: string;
  bizType: string;
  bizId: string;
  sellerId: string;
  /** 抽中的那一批业务的日期（yyyy-MM-dd）；不是复核时间 */
  sampleDate: string | null;
  /** 抽中理由的枚举名（BASE / TINY_ALL / NEW_SELLER / RESUME / ESCALATED） */
  reason: string;
  /** 抽中理由的中文说明；服务端给，前端不自己翻译（避免两处文案漂移） */
  reasonLabel: string;
  status: AuditSampleStatus | string;
  spuTitle: string | null;
  reviewNote: string | null;
  reviewedBy: string | null;
  reviewedAt: string | null;
  createdAt: string | null;
  /** 建任务 → 复核完成的小时数；未复核为 null（0 才是「不到一小时」） */
  responseHours: number | null;
  /** 抽中时的商品版本快照（A-08）；null = 旧任务未记录，不做漂移判断 */
  sampledVersion?: number | null;
  /** 商品当前版本（A-08）；商品已不存在时为 null */
  currentVersion?: number | null;
  /** 抽中后卖家又改过内容（A-08）；只提示不阻断复核 */
  drift?: boolean;
}

/** 一条入驻侧抽查任务（E28）。与商品侧差别：没有 sellerId，改带店铺名；无「必抽」理由 */
export interface MerchantAuditSampleView {
  id: string;
  bizType: string;
  bizId: string;
  sampleDate: string | null;
  reason: string;
  reasonLabel: string;
  status: AuditSampleStatus | string;
  storeName: string | null;
  reviewNote: string | null;
  reviewedBy: string | null;
  reviewedAt: string | null;
  createdAt: string | null;
  responseHours: number | null;
}

/**
 * 复核结论。`PASS` 只记结论、不动业务；`REJECT` 是**真处置**：
 * 商品侧驳回并强制下架，入驻侧直接停业 + 联动下架该店商品。因此 note 必填。
 */
export interface AuditSampleReviewRequest {
  action: 'PASS' | 'REJECT';
  note?: string;
}

/** 一轮商品抽查的执行结果（手动触发时回显） */
export interface AuditSampleReport {
  /** 当天自动放行的候选商品数 */
  candidates: number;
  /** 本轮新建的抽查任务数 */
  created: number;
  /** 因唯一键幂等跳过的条数 */
  skipped: number;
  /** 本轮生效的抽样比例（%）—— 可能已被自动收紧抬高 */
  ratePercent: number;
  /** 逐条抽中理由（给运营解释「为什么抽到它」） */
  reasons: string[];
}

/** 一轮入驻抽查的执行结果 */
export interface MerchantAuditSampleReport {
  candidates: number;
  created: number;
  skipped: number;
  ratePercent: number;
}

/**
 * SLA 自证指标（附录 D 第五节）。
 *
 * 抽查制度要能自证「有没有用」，靠的就是这几个数：抽中违规率说明放行是否过松、
 * 平均复核时长说明处置是否及时、误判率说明抽查是否在制造噪音。
 * `tightened` 单列出来：比例从 5% 变 10% 时运营得知道是**系统自行收紧**，不是配置被改。
 */
export interface AuditSlaMetrics {
  /** 统计窗口天数（近 N 天） */
  windowDays: number;
  sampled: number;
  violated: number;
  /** 抽中违规率（%），分母是已复核数 */
  violationPercent: number;
  passed: number;
  /** 平均复核时长（小时） */
  avgResponseHours: number;
  /** 误判率（%）= 连续两次抽查结论不一致的占比 */
  falsePositivePercent: number;
  /** 当前生效的抽样比例（%） */
  currentRatePercent: number;
  /** 本次读取指标时是否触发了自动收紧（连续 3 天超标 → 比例翻倍） */
  tightened: boolean;
}

/**
 * E24 · 租户健康度面板 · 单个租户行（平台后台）。
 *
 * ★ 四维横跨三个存储（交易库订单/结算、履约库售后、Redis 接口计数），
 *   由服务端一次聚合返回 —— 前端分四次拉再自己拼，必然出现
 *   「四个数字来自四个时刻」以及「其中一格失败了但页面看起来完好」。
 *
 * ★ 比例字段是 `string` 且**允许 null**：null 表示分母为 0（无样本），
 *   页面渲染成「—」而不是 `0.00%`。「这段时间一单没成交」与
 *   「成交 100 单、零售后」是两个完全不同的结论，写成同一个 0.00% 会把前者伪装成健康。
 *
 * ★ `level` 里 `NO_DATA`（无样本）必须与 `OK`（健康）分开渲染。
 */
export interface TenantHealthRow {
  /** 租户标识＝店铺主体 userId；`'0'` 表示平台自营（雪花 id，一律按字符串流转） */
  tenantId: string;
  /** 店铺主体昵称；`nameResolved=false` 时全为 null */
  tenantName: string | null;
  orderCount: number;
  paidCount: number;
  gmv: number;
  afterSaleCount: number;
  afterSaleRefundedCount: number;
  afterSaleOpenCount: number;
  afterSaleAdverseCount: number;
  /** 售后率＝售后立案 ÷ 成交单数；成交 0 单时为 null */
  afterSaleRate: string | null;
  debtOutstandingCount: number;
  debtOutstandingAmount: number;
  staleCustodyCount: number;
  staleCustodyAmount: number;
  missingSettlementCount: number;
  /** 合计＝欠款 + 超期托管 + 缺托管单 */
  settlementAbnormalCount: number;
  apiTotal: number;
  apiServerError: number;
  /** 4xx **不参与判级**（客户端写错参数不是店铺不健康） */
  apiClientError: number;
  /** 接口错误率（只看 5xx）；无调用时为 null */
  apiErrorRate: string | null;
  /** `RISK` / `WATCH` / `NO_DATA` / `OK` */
  level: string;
}

/**
 * 平台汇总（面板顶部那排数）。
 * 比例按**合计**重算，不是各行百分比取平均 —— 取平均会让 1 单的小店与 1 万单的大店同权。
 */
export interface TenantHealthPlatform {
  tenantCount: number;
  orderCount: number;
  gmv: number;
  afterSaleCount: number;
  afterSaleRate: string | null;
  settlementAbnormalCount: number;
  apiTotal: number;
  apiErrorRate: string | null;
  riskyTenants: number;
  watchTenants: number;
}

export interface TenantHealthPanel {
  tenants: TenantHealthRow[];
  platform: TenantHealthPlatform;
  /** 实际生效的窗口天数（**回显服务端算的**，不是客户端传的） */
  days: number;
  from: string;
  to: string;
  limit: number;
  totalTenants: number;
  /** 是否因 limit 被截断 —— 没有它，「还有 N 家未展示」与「平台只有这几家」长得完全一样 */
  truncated: boolean;
  /** 店铺名是否取到；false = 用户域降级（只影响展示，四维判据仍然有效） */
  nameResolved: boolean;
  generatedAt: string;
}

/**
 * E24 · 阈值与窗口上限（运行时从服务端取，**前端绝不抄一份**）。
 *
 * 抄一份的后果：政策调整（关注线、风险线、窗口上限）后前端仍按旧数解释数据，
 * 页面不报错、也不空，只是结论悄悄变错。
 */
export interface TenantHealthLimits {
  maxDays: number;
  defaultDays: number;
  maxLimit: number;
  defaultLimit: number;
  /** 超期仍托管判为异常的天数阈值 */
  staleCustodyDays: number;
  /** 接口计数在 Redis 的存活天数；必须 ≥ maxDays（否则超期那几天会静默变成「没调用」） */
  metricKeepDays: number;
  afterSaleWatchPercent: number;
  apiErrorRiskPercent: number;
  apiErrorWatchPercent: number;
  /**
   * 判「关注」所需的最小成交单数门槛。
   * 没有它，页面无法解释「4 单里出 4 笔售后（100%）为什么没变关注」——
   * 样本太小，任何比例都没有统计意义，规则刻意不给档位。
   */
  afterSaleMinBase: number;
  /** 判 5xx 率所需的最小调用数门槛（同理，1 次失败不该把一家店标成风险） */
  apiMinBase: number;
  /** 判级档位声明清单（含 NO_DATA） */
  levels: string[];
  /** 结算异常的三个来源 key：debtOutstanding / staleCustody / missingSettlement */
  settlementAbnormalSources: string[];
}

/** 入驻材料清单（6 类，提交时必须齐全） */export const MERCHANT_MATERIAL_TYPES = [
  { type: 'BUSINESS_LICENSE', label: '营业执照' },
  { type: 'LEGAL_ID_FRONT', label: '法人身份证人像面' },
  { type: 'LEGAL_ID_BACK', label: '法人身份证国徽面' },
  { type: 'BANK_ACCOUNT', label: '对公银行账户证明' },
  { type: 'OPERATION_COMMITMENT', label: '经营承诺书' },
  { type: 'INDUSTRY_PERMIT', label: '行业经营许可证' },
] as const;

/**
 * 逆地理编码结果（A1 地址改造：地图定位回填）。
 *
 * 由后端代理高德「Web 服务」获取 —— 前端永远拿不到地图 Key。
 * `configured=false` 表示服务端尚未配置 Key（此时 province/city/district 均为空），
 * 前端必须走「手选」兜底，而不是报错卡住用户。
 */
/** 周边候选位置（逆地理 extensions=all 带回的 POI，按距离升序） */
export interface RegeoCandidate {
  /** POI 名称，如「花园坪C区」「龙城公园」 */
  name: string;
  /** POI 地址（可能为 null） */
  address: string | null;
  /** 距定位点的直线距离（米） */
  distance: number | null;
  lng: number | null;
  lat: number | null;
}

export interface RegeoView {
  configured: boolean;
  /**
   * 本次实际使用的坐标系。
   * 网页端浏览器给的是 wgs84（服务端会先转成 gcj02 再查高德）；
   * 小程序 wx.getLocation({type:'gcj02'}) 已是 gcj02，不能再转。
   */
  coord: 'gcj02' | 'wgs84';
  province: string | null;
  city: string | null;
  district: string | null;
  /** 街道门牌等详细地址（对应表单的「详细地址」） */
  detail: string | null;
  /** 行政区划代码（区县级） */
  adcode: string | null;
  /** 实际查询用的 GCJ-02 经度（已转换后的值） */
  lng: number | null;
  lat: number | null;
  /** 周边候选：让用户在多个位置里挑，避免单一位置错了就全错 */
  candidates: RegeoCandidate[];
}

/**
 * 网页地图组件配置（高德 JSAPI 2.0）。
 *
 * ★ 这两个值**必须交给浏览器**（地图 SDK 在浏览器里跑），高德机制决定的；
 *   但我们是按登录态从服务端取，不打进前端产物 —— Key 不进仓库、换 Key 不用重新构建。
 * `configured=false` 表示服务端未配置，前端应提示"地图选点未配置"并保留候选/手选路径。
 */
export interface JsApiConfig {
  configured: boolean;
  key: string | null;
  securityCode: string | null;
}
