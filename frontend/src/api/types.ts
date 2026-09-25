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
  /** self 自营 / merchant 第三方 */
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
}

export interface Suitability {
  food: boolean;
  species: string[];
  lifeStages: string[];
  allergens: string[];
}

export interface ProductDetail {
  card: ProductCard;
  detail: string | null;
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

/** 卖家发货请求；物流信息可选，随状态日志留痕 */
export interface ShipRequest {
  logisticsCompany?: string;
  logisticsNo?: string;
}

/** 商家端「我的商品」视图：SPU 主档 + SKU 汇总（数量与总库存） */
export interface SellerProductView {
  id: string;
  title: string;
  mainImage: string | null;
  status: 'ON_SALE' | 'OFF_SHELF' | string;
  /** 状态中文文案由服务端给出，前端不维护映射表 */
  statusLabel: string;
  sellerId: string;
  minPrice: number;
  maxPrice: number;
  salesCount: number;
  skuCount: number;
  totalStock: number;
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
  /** 展示给运营的提示（含「目标用户需重新登录后生效」） */
  notice: string;
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
  skus: SeckillSkuView[];
}

/** 秒杀下单结果：拿 orderNo 去订单详情走沙箱支付 */
export interface PlaceSeckillResult {
  orderNo: string;
  payAmount: number;
  seckillPrice: number;
  /** true = 同幂等键重放，返回的是首次订单 */
  replay: boolean;
}
