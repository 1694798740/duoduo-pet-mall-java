/**
 * 接口清单 —— 与 Java 微服务网关的实际路由逐条对齐。
 *
 * 网关路由（duoduo-gateway/application.yml）：
 *   /api/auth/**, /api/users/**, /api/pets/**      → duoduo-user   :8081
 *   /api/categories/**, /api/products/**, /api/listings/**,
 *   /api/home/**, /api/meta/**,
 *   /api/seller/products/**                        → duoduo-product:8082
 *   /api/cart/**, /api/orders/**, /api/payments/**,
 *   /api/seller/**, /sandbox/pay/**                → duoduo-trade  :8083
 *   /api/aftersales/**, /api/admin/aftersales/**,
 *   /api/reviews/**, /api/logistics/**             → duoduo-fulfilment:8084
 *
 * 履约域（duoduo-fulfilment）已部署：评价与晒单、售后仲裁、物流轨迹三个子域都可用。
 * 卖家中心（/api/seller/orders|summary|settlements）落在 trade 服务；
 * 商品管理（/api/seller/products/**）落在 product 服务 —— 两者共用 /api/seller 前缀与准入模型，
 * 网关按「products 先于 trade 兜底」的顺序路由。
 * 物流轨迹**不挂在 /api/orders/** 下**：那一段整段归 trade，而轨迹数据在 fulfilment，
 * 硬挂进去就得靠网关里的路由优先级特例来撑，属于「新增端点只 404」那类脆弱写法。
 */

import { api } from './client';
import type {
  AbnormalSettlement,
  AdminReport,
  AdminSettlementSummary,
  AfterSaleArbitrateRequest,
  AfterSaleCreateRequest,
  AfterSaleReplyRequest,
  AfterSaleView,
  BrandSaveRequest,
  BrandView,
  CartItemView,
  CartView,
  CategoryNode,
  CategorySaveRequest,
  CouponClaimResult,
  CouponTemplateView,
  CouponView,
  CreditView,
  DictItem,
  HomeView,
  ListingAuditRequest,
  ListingAuditView,
  ListingCard,
  ListingDetail,
  OrderView,
  PageResult,
  PayResult,
  PetUpsertRequest,
  ReviewCreateRequest,
  ReviewCreateResult,
  ReviewSummary,
  ReviewView,
  ReviewedOrders,
  PetView,
  PlaceOrderRequest,
  PlaceOrderResult,
  ProductCard,
  ProductDetail,
  RefundResult,
  SellerProductView,
  SellerSummary,
  SettlementPage,
  SettlementView,
  ShipRequest,
  TrackListView,
  UsableCouponResult,
  UserView,
} from './types';

// ---------- 认证（/api/auth，令牌走 httpOnly Cookie） ----------

export const authApi = {
  /** 注册并直接登录；手机号冲突后端返回 409 */
  register: (body: { phone: string; password: string; nickname?: string }) =>
    api.post<UserView>('/api/auth/register', body, { allowRefresh: false }),

  /** 密码登录；账号不存在与密码错误返回同一提示（防手机号枚举） */
  login: (body: { phone: string; password: string }) =>
    api.post<UserView>('/api/auth/login', body, { allowRefresh: false }),

  /** 刷新令牌轮转：旧令牌立即吊销，签发新对 */
  refresh: () => api.post<UserView>('/api/auth/refresh', undefined, { allowRefresh: false }),

  /** 吊销刷新令牌并清 Cookie */
  logout: () => api.post<void>('/api/auth/logout', undefined, { allowRefresh: false }),
};

// ---------- 用户（/api/users） ----------

export const userApi = {
  me: () => api.get<UserView>('/api/users/me'),
  update: (body: { nickname?: string; avatar?: string }) =>
    api.patch<UserView>('/api/users/me', body),
  /** 实名认证；身份证仅存掩码 */
  realName: (body: { realName: string; idCard: string }) =>
    api.post<UserView>('/api/users/me/realname', body),

  /**
   * 申请成为卖家（商家入驻）：把 role 升级为 SELLER。
   *
   * 两个后端约束会体现在错误里：未实名 → 422；幂等（已是卖家）→ 直接返回当前资料。
   * 角色升级后 access token 里的 role 仍是旧快照，因此前端要重新拉一次 me()
   * 并提示用户「下次登录生效」。
   */
  applyMerchant: () => api.post<UserView>('/api/users/me/merchant'),
};

// ---------- 宠物档案（/api/pets） ----------

export const petApi = {
  list: () => api.get<PetView[]>('/api/pets'),
  create: (body: PetUpsertRequest) => api.post<PetView>('/api/pets', body),
  update: (id: number, body: PetUpsertRequest) => api.put<PetView>(`/api/pets/${id}`, body),
  setDefault: (id: number) => api.post<PetView>(`/api/pets/${id}/default`),
  remove: (id: number) => api.del<void>(`/api/pets/${id}`),
};

// ---------- 类目 / 元数据 ----------

export const catalogApi = {
  /** 两级类目树 */
  categories: () => api.get<CategoryNode[]>('/api/categories'),
  /** 品牌列表 */
  brands: () => api.get<BrandView[]>('/api/meta/brands'),
  /** 筛选字典：物种 / 生命阶段 / 成色 / 供货类型 / 排序 */
  dictionaries: () => api.get<Record<string, DictItem[]>>('/api/meta/dictionaries'),
};

// ---------- 首页 / 商品 ----------

/** 商品检索参数（与后端 ProductQuery 字段一一对应） */
export interface ProductQuery {
  keyword?: string;
  categoryId?: number;
  brandId?: number;
  supplyType?: 'self' | 'merchant';
  minPrice?: number;
  maxPrice?: number;
  species?: 'cat' | 'dog';
  lifeStage?: 'BABY' | 'ADULT' | 'SENIOR';
  inStockOnly?: boolean;
  sort?: 'default' | 'sales' | 'price_asc' | 'price_desc' | 'rating' | 'newest';
  page?: number;
  pageSize?: number;
}

export interface ListingQuery {
  keyword?: string;
  city?: string;
  species?: string;
  conditionLevel?: string;
  minPrice?: number;
  maxPrice?: number;
  sort?: string;
  page?: number;
  pageSize?: number;
}

export const productApi = {
  /** 首页聚合：类目 / 推荐 / 新品 / 品牌 / 闲置 / 物种精选，一次拿全首屏 */
  home: () => api.get<HomeView>('/api/home'),

  /** 商品检索；keyword 含违禁词后端返回 422 */
  search: (query: ProductQuery & { signal?: AbortSignal }) =>
    api.get<PageResult<ProductCard>>('/api/products', { query }),

  /** 商品详情：SKU、适用属性聚合、同类与相关推荐 */
  detail: (spuId: number) => api.get<ProductDetail>(`/api/products/${spuId}`),

  /** C2C 闲置列表（只返回已上架且审核通过的） */
  listings: (query: ListingQuery & { signal?: AbortSignal }) =>
    api.get<PageResult<ListingCard>>('/api/listings', { query }),

  /** 闲置详情 + 「同款全新」推荐 */
  listing: (id: number) => api.get<ListingDetail>(`/api/listings/${id}`),
};

// ---------- 购物车（/api/cart，需登录） ----------

export const cartApi = {
  list: () => api.get<CartView>('/api/cart'),
  /** 件数角标；不查商品服务，可高频调用 */
  count: () => api.get<{ count: number }>('/api/cart/count'),
  /** 加购；同 SKU 累加数量，不新增行 */
  add: (body: { skuId: number; quantity?: number }) => api.post<CartItemView>('/api/cart/items', body),
  updateQuantity: (skuId: number, quantity: number) =>
    api.patch<CartItemView>(`/api/cart/items/${skuId}`, { quantity }),
  setSelected: (skuId: number, selected: boolean) =>
    api.patch<CartItemView>(`/api/cart/items/${skuId}/selection`, { selected }),
  remove: (skuId: number) => api.del<void>(`/api/cart/items/${skuId}`),
  /** 清空已勾选条目（下单成功后调用） */
  removeSelected: () => api.del<{ removed: number }>('/api/cart/selected'),
};

// ---------- 订单（/api/orders，需登录） ----------

export const orderApi = {
  /**
   * 提交订单：把购物车中已勾选的商品下单。
   * 必须带 idempotentKey —— 网络重试、用户连点两次都靠它兜住。
   * 收货信息直接随请求提交（本期无地址簿）。
   */
  place: (body: PlaceOrderRequest) => api.post<PlaceOrderResult>('/api/orders', body),

  list: (query?: { status?: string; page?: number; pageSize?: number }) =>
    api.get<PageResult<OrderView>>('/api/orders', { query }),

  detail: (orderNo: string) => api.get<OrderView>(`/api/orders/${orderNo}`),

  /** 支付预下单：sandbox 渠道返回 codeUrl（二维码内容）与 expireAt */
  pay: (orderNo: string, body: { channel: string; idempotentKey: string }) =>
    api.post<PayResult>(`/api/orders/${orderNo}/pay`, body),

  /** 取消（仅待付款可取消），并释放冻结 + 作废未支付支付单 */
  cancel: (orderNo: string, reason?: string) =>
    api.post<OrderView>(`/api/orders/${orderNo}/cancel`, reason ? { reason } : undefined),

  /** 确认收货（已发货 → 已完成），并放款给卖家 */
  confirm: (orderNo: string) => api.post<OrderView>(`/api/orders/${orderNo}/confirm`),

  /** 买家自助退款（仅「已付款且未发货」） */
  refund: (orderNo: string, reason?: string) =>
    api.post<RefundResult>(`/api/orders/${orderNo}/refund`, reason ? { reason } : undefined),

  /** 查询该订单的资金托管状态（托管中 / 已放款 / 已退回买家） */
  settlement: (orderNo: string) => api.get<SettlementView>(`/api/orders/${orderNo}/settlement`),
};

// ---------- 卖家中心（/api/seller，需登录 + 卖家角色） ----------

/**
 * 卖家中心接口。
 *
 * 与买家端订单接口的关键差别：这里**没有任何一个参数带 sellerId** ——
 * 卖家身份由网关从令牌解析后透传，服务端逐单校验归属，不属于你的订单返回 403。
 * 前端也不做任何「这单是不是我的」的过滤，那属于把越权防护建立在 UI 上。
 */
export const sellerApi = {
  /**
   * 商家端概览。同时充当权限探测：非卖家返回 403，
   * 前端据此引导去申请成为卖家，而不是进一个到处 403 的空页面。
   */
  summary: () => api.get<SellerSummary>('/api/seller/summary'),

  /** 卖出的订单列表；status 可选 PENDING_PAYMENT / PAID / SHIPPED / COMPLETED / REFUNDED / CLOSED */
  orders: (query?: { status?: string; page?: number; pageSize?: number }) =>
    api.get<PageResult<OrderView>>('/api/seller/orders', { query }),

  orderDetail: (orderNo: string) => api.get<OrderView>(`/api/seller/orders/${orderNo}`),

  /** 卖家发货；物流信息可选，随状态日志留痕。发货后资金仍在托管中 */
  ship: (orderNo: string, body?: ShipRequest) =>
    api.post<OrderView>(`/api/seller/orders/${orderNo}/ship`, body),

  /** 卖家售后退款（已付款 / 已发货都可，前提是已确认收到退货） */
  refund: (orderNo: string, reason?: string) =>
    api.post<RefundResult>(`/api/seller/orders/${orderNo}/refund`, reason ? { reason } : undefined),

  /** 我的资金托管单；status 可选 CUSTODY / RELEASED / REFUNDED，汇总不受过滤影响 */
  settlements: (query?: { status?: string; page?: number; pageSize?: number }) =>
    api.get<SettlementPage>('/api/seller/settlements', { query }),

  // ---- 商品管理（/api/seller/products/**，落在 duoduo-product 服务） ----

  /** 我的商品列表；运营 / 管理员可显式按 sellerId 筛选（普通卖家传了也会被服务端忽略） */
  products: (query?: { keyword?: string; sellerId?: number; page?: number; pageSize?: number }) =>
    api.get<PageResult<SellerProductView>>('/api/seller/products', { query }),

  /** 上下架（幂等开关）：action = ON_SALE | OFF_SHELF；越权操作别人的商品返回 403 */
  changeProductStatus: (spuId: number, action: 'ON_SALE' | 'OFF_SHELF') =>
    api.post<SellerProductView>(`/api/seller/products/${spuId}/status`, { action }),
};

// ---------- 平台管理后台（/api/admin，需运营 / 管理员） ----------

/**
 * 平台管理后台接口。
 *
 * 与卖家中心的关键差别：卖家中心的可见范围是「我的单」，这里是「全平台」。
 * 全部接口由服务端 OperatorGuard.requireOperator() 把关，非运营返回 403；
 * 前端据此把入口藏给运营 / 管理员，但那只是体验优化，不是安全边界。
 */
export const adminApi = {
  // ---- 运营报表与资金对账（落在 duoduo-trade） ----
  reportOverview: () => api.get<AdminReport>('/api/admin/reports/overview'),
  settlementSummary: () => api.get<AdminSettlementSummary>('/api/admin/settlements/summary'),
  settlementAbnormal: () => api.get<AbnormalSettlement[]>('/api/admin/settlements/abnormal'),

  // ---- 订单干预（落在 duoduo-trade；仅待付款订单可强关） ----
  orders: (query?: { status?: string; orderNo?: string; page?: number; pageSize?: number }) =>
    api.get<PageResult<OrderView>>('/api/admin/orders', { query }),
  orderDetail: (orderNo: string) => api.get<OrderView>(`/api/admin/orders/${orderNo}`),
  forceCloseOrder: (orderNo: string, reason?: string) =>
    api.post<OrderView>(`/api/admin/orders/${orderNo}/force-close`, reason ? { reason } : undefined),

  // ---- 基础数据：类目 / 品牌（落在 duoduo-product） ----
  categories: () => api.get<CategoryNode[]>('/api/admin/categories'),
  createCategory: (body: CategorySaveRequest) => api.post<CategoryNode>('/api/admin/categories', body),
  updateCategory: (id: number, body: CategorySaveRequest) =>
    api.put<CategoryNode>(`/api/admin/categories/${id}`, body),
  setCategoryStatus: (id: number, active: boolean) =>
    api.put<void>(`/api/admin/categories/${id}/status?active=${active}`),
  deleteCategory: (id: number) => api.del<void>(`/api/admin/categories/${id}`),

  brands: () => api.get<BrandView[]>('/api/admin/brands'),
  createBrand: (body: BrandSaveRequest) => api.post<BrandView>('/api/admin/brands', body),
  updateBrand: (id: number, body: BrandSaveRequest) => api.put<BrandView>(`/api/admin/brands/${id}`, body),
  setBrandStatus: (id: number, active: boolean) =>
    api.put<void>(`/api/admin/brands/${id}/status?active=${active}`),
  deleteBrand: (id: number) => api.del<void>(`/api/admin/brands/${id}`),

  // ---- 内容审核（落在 duoduo-product；含未审核通过的内容） ----
  listingsForAudit: (query?: { auditStatus?: string; page?: number; pageSize?: number }) =>
    api.get<PageResult<ListingAuditView>>('/api/admin/listings', { query }),
  auditListing: (id: number, body: ListingAuditRequest) =>
    api.post<ListingAuditView>(`/api/admin/listings/${id}/audit`, body),

  // ---- 售后仲裁工作台（A3，落在 duoduo-fulfilment） ----

  /** 全平台售后列表（按状态过滤） */
  afterSales: (query?: { status?: string; page?: number; pageSize?: number }) =>
    api.get<PageResult<AfterSaleView>>('/api/admin/aftersales', { query }),

  /**
   * 裁定。`APPROVE_REFUND` 会**真的执行退款**（调交易域既有的运营侧退款入口）。
   * 一种会失败的情况必须在前端讲清楚：订单已完成且钱已放款给卖家时，交易域返回 422
   * 「需向卖家追偿 / 平台垫付」——那是资金边界 B-01，系统退不了，只能人工处理。
   */
  arbitrateAfterSale: (id: string, body: AfterSaleArbitrateRequest) =>
    api.post<AfterSaleView>(`/api/admin/aftersales/${id}/arbitrate`, body),
};

// ---------- 支付（/api/payments） ----------

export const paymentApi = {
  /** 查询支付单状态；仅本人可见，不是自己的返回 404 */
  get: (payNo: string, options?: { signal?: AbortSignal }) =>
    api.get<PayResult>(`/api/payments/${payNo}`, options),

  /**
   * 沙箱「扫码后确认支付」。
   * 现实中扫码设备没有买家登录态，因此该接口免登录；
   * 前端在「本机模拟支付」场景下调用它，省去第二台设备。
   */
  sandboxPay: (payNo: string) => api.post<PayResult>(`/api/payments/sandbox/${payNo}`),
};

// ---------- 评价与晒单（/api/reviews，履约域） ----------

/**
 * 评价接口。
 *
 * 公开读（商品评价与评分汇总，未登录也能看）与登录写（提交 / 追评 / 我的评价）
 * 混在同一个前缀下，网关只放行 `/api/reviews/product/**` 这一段 ——
 * 因此**不要**把需要登录的接口挪进 `product/` 子路径。
 */
export const reviewApi = {
  /** 商品评价分页（公开） */
  byProduct: (
    spuId: number,
    query?: { rating?: number; hasImage?: boolean; page?: number; pageSize?: number },
  ) => api.get<PageResult<ReviewView>>(`/api/reviews/product/${spuId}`, { query }),

  /** 商品评分汇总（公开） */
  summary: (spuId: number) => api.get<ReviewSummary>(`/api/reviews/product/${spuId}/summary`),

  /** 提交评价：一次可提交订单里多件商品 */
  create: (body: ReviewCreateRequest) => api.post<ReviewCreateResult>('/api/reviews', body),

  /** 追评（每条评价一次） */
  append: (id: number, content: string) => api.post<ReviewView>(`/api/reviews/${id}/append`, { content }),

  /** 我的评价（分页） */
  mine: (query?: { page?: number; pageSize?: number }) =>
    api.get<PageResult<ReviewView>>('/api/reviews/mine', { query }),

  /** 某订单下我的评价 */
  byOrder: (orderNo: string) => api.get<ReviewView[]>(`/api/reviews/order/${orderNo}`),

  /** 我在这批订单里已评价过的订单号（订单列表页一次判断） */
  reviewed: (orderNos: string[]) =>
    api.get<ReviewedOrders>('/api/reviews/reviewed', { query: { orderNos: orderNos.join(',') } }),
};

// ---------- 售后仲裁（/api/aftersales，履约域 A3） ----------

/**
 * 售后接口。
 *
 * 与卖家中心同一条纪律：**参数里没有 buyerId / sellerId** ——
 * 归属完全由登录态推导，服务端逐单校验（不是你的单返回 403，单不存在返回 404，刻意区分）。
 *
 * ⚠️ 所有 `{id}` 都是**字符串**（18 位雪花值），从列表里原样回传即可；
 * 千万不要 `Number(id)` 或做任何运算，那会把它悄悄改成一个不存在的单号。
 */
export const afterSaleApi = {
  /** 买家立案：仅本人订单、仅「已发货 / 已完成」订单（否则 422） */
  create: (body: AfterSaleCreateRequest) => api.post<AfterSaleView>('/api/aftersales', body),

  /** 我提起的售后（买家视角） */
  mine: (query?: { status?: string; page?: number; pageSize?: number }) =>
    api.get<PageResult<AfterSaleView>>('/api/aftersales/mine', { query }),

  /** 我店铺的售后（卖家 / 运营视角；非卖家返回 403） */
  seller: (query?: { status?: string; page?: number; pageSize?: number }) =>
    api.get<PageResult<AfterSaleView>>('/api/aftersales/seller', { query }),

  /** 详情（当事人或运营） */
  detail: (id: string) => api.get<AfterSaleView>(`/api/aftersales/${id}`),

  /** 卖家处理：AGREE 立刻退款 / REJECT 必须填说明（缺说明 422） */
  reply: (id: string, body: AfterSaleReplyRequest) =>
    api.post<AfterSaleView>(`/api/aftersales/${id}/reply`, body),

  /** 买家申请平台仲裁（待卖家处理 / 卖家已拒绝 时可进入） */
  escalate: (id: string) => api.post<AfterSaleView>(`/api/aftersales/${id}/escalate`),
};

// ---------- 物流轨迹（/api/logistics，履约域 A2） ----------

/**
 * 物流轨迹查询。
 *
 * 路径**不挂在 `/api/orders/**` 下**：那一段整段路由给交易域，而轨迹数据在履约域。
 * 若硬挂在订单命名空间下，就得在网关里为它插一条优先级更高的路由 ——
 * 那正是历史上造成过「新增端点不报错、只 404」的写法。这里用履约域自己的命名空间，
 * 前端本来就是「订单详情 + 轨迹」两次调用，前缀不同不影响使用。
 *
 * 准入：买家本人 / 该单卖家 / 运营。非当事人返回 403，订单不存在返回 404，
 * 订单还没有轨迹返回 **200 + 空列表**（这是正常状态，不该按错误渲染）。
 *
 * @param orderNo 订单号（业务键，非雪花 id，字符串直接传即可）
 */
export const logisticsApi = {
  tracks: (orderNo: string, options?: { signal?: AbortSignal }) =>
    api.get<TrackListView>(`/api/logistics/orders/${encodeURIComponent(orderNo)}/tracks`, options),
};

// ---------- 信用分（/api/reviews/seller/{sellerId}/credit，履约域 A4） ----------

/**
 * 信用分查询。
 *
 * 挂在 `/api/reviews` 前缀之下而不是另起一段：信用分的两类事实（评价、售后）
 * 本来就属于评价 / 履约语义，同一个前缀让网关路由与白名单都不用新增规则段。
 * 网关只放行 `seller/*&#47;credit` 这一条**精确路径**（公开只读），
 * 发表评价、刷新回写等仍在白名单之外 —— 不要把需要登录的接口挪进这个子路径。
 *
 * 语义要点：
 *   · 分数体现的是该用户**作为卖方**的可信度（商品 / 闲置页展示的就是卖家信用），
 *     因此「我的信用」也复用同一端点（传自己的 userId）；
 *   · 未登录也能读（公开只读）；
 *   · 无数据用户得基础分，不返回 null —— 前端无需写「暂无信用」的空态。
 */
export const creditApi = {
  /** 某卖家（用户）的信用分与构成明细（公开，未登录可读） */
  seller: (sellerId: number, options?: { signal?: AbortSignal }) =>
    api.get<CreditView>(`/api/reviews/seller/${sellerId}/credit`, options),
};

// ---------- 优惠券（/api/coupons，营销域 A5） ----------

/**
 * 优惠券接口。
 *
 * 网关白名单**只放行 `/api/coupons/templates` 这一条精确路径**，因此：
 *   · 领券中心（`templates`）未登录可读 —— 那是营销落地页，它的作用就是把人留住；
 *   · 我的券包、可用券、领券、运营建券全部需要登录。
 *
 * 三条纪律：
 *   1. **不要把需要登录的接口挪进 `templates/` 子路径**：白名单按路径而非按方法匹配，
 *      挪进去等于把写接口也一起放行了；
 *   2. 下单只传 `couponCode`，**不传优惠金额** —— 抵扣多少由服务端算、交易域落账；
 *   3. 券码是字符串，从列表里原样回传即可，不要 `Number(code)`。
 */
export const couponApi = {
  /** 领券中心：当前可领取的券模板（公开只读）。是否已领由服务端按登录态判定 */
  templates: (options?: { signal?: AbortSignal }) =>
    api.get<CouponTemplateView[]>('/api/coupons/templates', options),

  /**
   * 领取一张券。
   *
   * 重复领取不会报错，而是返回 `replay=true` 与**已有那张券的券码** ——
   * 用户双击、多端并发、网络重试都是常态，把它们报成失败只会让用户以为操作没成功。
   */
  claim: (templateId: number) =>
    api.post<CouponClaimResult>(`/api/coupons/templates/${templateId}/claim`),

  /** 我的券包；status 可选 UNUSED / LOCKED / USED / EXPIRED，不传为全部 */
  mine: (query?: { status?: string }, options?: { signal?: AbortSignal }) =>
    api.get<CouponView[]>('/api/coupons/mine', { query, ...options }),

  /**
   * 结算页可用券：给定本次订单的商品总额（分），返回每张券能不能用与最优券。
   *
   * 做成「传金额查」而不是「前端拿券包自己算」：门槛、有效期、是否已被锁定
   * 这三件事都在服务端，两边各算一次迟早不一致，表现出来就是
   * 「页面说能用、下单说不能用」。
   */
  usable: (amount: number, options?: { signal?: AbortSignal }) =>
    api.get<UsableCouponResult>('/api/coupons/mine/usable', { query: { amount }, ...options }),
};
