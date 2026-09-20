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
 * 约定：金额一律整数「分」；时间一律 ISO8601 UTC 字符串。
 */

// ---------- 用户域（duoduo-user） ----------

export interface UserView {
  id: number;
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
  id: number;
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
  id: number;
  name: string;
  parentId: number | null;
  depth: number;
  icon: string | null;
  /** 后台列表会返回停用项；公开接口只暴露 ACTIVE */
  status?: 'ACTIVE' | 'INACTIVE' | string;
  children: CategoryNode[];
}

export interface BrandView {
  id: number;
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
  id: number;
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
  id: number;
  title: string;
  subtitle: string | null;
  categoryId: number | null;
  categoryName: string | null;
  brandId: number | null;
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
  id: number;
  title: string;
  price: number;
  originalPrice: number;
  conditionLevel: string | null;
  city: string | null;
  cover: string | null;
  species: string | null;
  breed: string | null;
  viewCount: number;
  sellerId: number | null;
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
  skuId: number;
  spuId: number;
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
  sellerId: number | null;
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
  skuId: number;
  spuId: number;
  title: string;
  specText: string | null;
  mainImage: string | null;
  price: number;
  quantity: number;
  amount: number;
}

export interface OrderView {
  orderNo: string;
  sellerId: number | null;
  orderType: string;
  status: OrderStatus;
  /** 状态中文文案由服务端给出，前端不维护映射表 */
  statusLabel: string;
  totalAmount: number;
  freightAmount: number;
  discountAmount: number;
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

export interface PlaceOrderRequest {
  receiverName: string;
  receiverPhone: string;
  receiverAddress: string;
  remark?: string;
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
  sellerId: number;
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
  id: number;
  title: string;
  mainImage: string | null;
  status: 'ON_SALE' | 'OFF_SHELF' | string;
  /** 状态中文文案由服务端给出，前端不维护映射表 */
  statusLabel: string;
  sellerId: number;
  minPrice: number;
  maxPrice: number;
  salesCount: number;
  skuCount: number;
  totalStock: number;
}

// ---------- 平台管理后台（/api/admin，需运营 / 管理员） ----------

export interface CategorySaveRequest {
  name: string;
  parentId?: number;
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
  id: number;
  sellerId: number;
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
  id: number;
  orderNo: string;
  spuId: number;
  skuId: number | null;
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
  spuId: number;
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
  spuId: number;
  skuId?: number | null;
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
  buyerId: number;
  sellerId: number;
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

// ---------- 通用 ----------

export interface PageResult<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
  pageCount: number;
}
