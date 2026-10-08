/**
 * 接口清单 —— 与 Java 微服务网关的实际路由逐条对齐。
 *
 * 网关路由（duoduo-gateway/application.yml）：
 *   /api/auth/**, /api/users/**, /api/pets/**,
 *   /api/addresses/**                              → duoduo-user   :8081
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
  AdminCharts,
  AdminReport,
  AdminSettlementSummary,
  AdminUserView,
  AddressUpsertRequest,
  AddressView,
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
  DuoduoCapability,
  DuoduoChatResponse,
  DuoduoChatTurn,
  HomeView,
  ListingAuditRequest,
  ListingAuditView,
  ListingCard,
  ListingMineView,
  ListingOwnerView,
  ListingPublishRequest,
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
  RoleChangeRequest,
  RoleChangeResult,
  SellerProductView,
  FollowCounts,
  FollowState,
  FollowView,
  ChatMessageView,
  ConversationView,
  MessagePageData,
  NoticePageData,
  EngagementState,
  SellerAnalytics,
  ShareCommentView,
  ShareCreateRequest,
  ShareEditRequest,
  ShareTopicView,
  ShareView,
  UnreadCount,
  SellerSummary,
  SettlementPage,
  SettlementView,
  ShipRequest,
  TrackListView,
  UsableCouponResult,
  UserView,
  PlaceSeckillResult,
  SeckillActivityCreateRequest,
  SeckillActivityEditRequest,
  SeckillActivityView,
  ShareReportHandleRequest,
  ShareReportView,
  MerchantApplicationView,
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
  // 【E10】旧的 applyMerchant（实名即升 SELLER）已随旧端点删除 ——
  // 入驻走下方 merchantApplication 状态机（提交材料 → 平台审核 → 开店）。

  /** 查看我的入驻申请（首次查看即在服务端创建 DRAFT 草稿） */
  getMerchantApplication: () =>
    api.get<MerchantApplicationView>('/api/users/me/merchant-application'),

  /** 保存草稿（店名 + 材料引用）。仅 DRAFT / REJECTED 可改；材料引用来自 uploadImage 的返回 url */
  saveMerchantApplication: (body: { storeName?: string; materials?: Record<string, string> }) =>
    api.put<MerchantApplicationView>('/api/users/me/merchant-application', body),

  /** 提交送审（幂等：重复提交返回现状；被驳回后可修改材料重提）。服务端校验实名 + 店名 + 6 类材料 */
  submitMerchantApplication: () =>
    api.post<MerchantApplicationView>('/api/users/me/merchant-application/submit'),

  /** 激活（审核通过后）：返回重签的访问令牌（入口固定商家侧）；浏览器通道同时写 Cookie */
  activateMerchant: () =>
    api.post<{ accessToken: string; expiresInSeconds: number }>(
      '/api/users/me/merchant-application/activate'),
};

// ---------- 宠物档案（/api/pets） ----------

export const petApi = {
  list: () => api.get<PetView[]>('/api/pets'),
  create: (body: PetUpsertRequest) => api.post<PetView>('/api/pets', body),
  update: (id: string, body: PetUpsertRequest) => api.put<PetView>(`/api/pets/${id}`, body),
  setDefault: (id: string) => api.post<PetView>(`/api/pets/${id}/default`),
  remove: (id: string) => api.del<void>(`/api/pets/${id}`),
};

// ---------- 收货地址簿（/api/addresses，A8） ----------

export const addressApi = {
  list: () => api.get<AddressView[]>('/api/addresses'),
  /**
   * 默认地址；新用户尚无地址时后端返回 `data: null`（不是报错）。
   * 因此返回类型是 `AddressView | null`，调用方必须处理 null。
   */
  defaultAddress: () => api.get<AddressView | null>('/api/addresses/default'),
  create: (body: AddressUpsertRequest) => api.post<AddressView>('/api/addresses', body),
  update: (id: string, body: AddressUpsertRequest) => api.put<AddressView>(`/api/addresses/${id}`, body),
  setDefault: (id: string) => api.post<AddressView>(`/api/addresses/${id}/default`),
  remove: (id: string) => api.del<void>(`/api/addresses/${id}`),
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
  /**
   * 类目 / 品牌筛选。
   *
   * ⚠️ 是 **string**：这两个都是 18 位雪花 id，转成 number 会静默丢精度（T-39），
   * 于是「点类目筛选」会筛出一片空白。它们只被拼进查询串，字符串完全够用。
   */
  categoryId?: string;
  brandId?: string;
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
  detail: (spuId: string) => api.get<ProductDetail>(`/api/products/${spuId}`),

  /**
   * C2C 闲置列表（只返回已上架且审核通过的）。
   *
   * ★ 路径是 `/api/listings/search` 而**不是** `/api/listings`。
   * 后端第五十二轮把公开检索从裸 `/api/listings` 挪到了 `/search` ——
   * 因为单段路径要让给 `POST /api/listings`（发布），而网关白名单**不区分 HTTP 方法**，
   * 两者不可能共存于同一路径。前端当时漏改，于是这个页面**对所有人 401**（不是空列表，是直接报错），
   * 而十二套 e2e 恰好没有一套访问过 `/listings`，所以一直没被发现。
   * 第六十六轮补 A 方案时才发现并修正。
   */
  listings: (query: ListingQuery & { signal?: AbortSignal }) =>
    api.get<PageResult<ListingCard>>('/api/listings/search', { query }),

  /** 闲置详情 + 「同款全新」推荐 */
  listing: (id: string) => api.get<ListingDetail>(`/api/listings/${id}`),
};

// ---------- 购物车（/api/cart，需登录） ----------

export const cartApi = {
  list: () => api.get<CartView>('/api/cart'),
  /** 件数角标；不查商品服务，可高频调用 */
  count: () => api.get<{ count: number }>('/api/cart/count'),
  /** 加购；同 SKU 累加数量，不新增行 */
  add: (body: { skuId: string; quantity?: number }) => api.post<CartItemView>('/api/cart/items', body),
  updateQuantity: (skuId: string, quantity: number) =>
    api.patch<CartItemView>(`/api/cart/items/${skuId}`, { quantity }),
  setSelected: (skuId: string, selected: boolean) =>
    api.patch<CartItemView>(`/api/cart/items/${skuId}/selection`, { selected }),
  remove: (skuId: string) => api.del<void>(`/api/cart/items/${skuId}`),
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

  /**
   * 商家端多维度统计（图表用）。
   *
   * <p>与 summary 分成两个请求是刻意的：统计卡比图表重要得多，
   * 图表取数异常不该把卡片一起拖下水 —— 两个 useAsync 各自降级。
   */
  analytics: () => api.get<SellerAnalytics>('/api/seller/analytics'),

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
  changeProductStatus: (spuId: string, action: 'ON_SALE' | 'OFF_SHELF') =>
    api.post<SellerProductView>(`/api/seller/products/${spuId}/status`, { action }),

  /**
   * 编辑自己的商品（标题 / 主图）。
   *
   * 字段三态：不传 = 不改；空串 = 清空（只有 mainImage 允许）；有值 = 替换。
   * 这里**必须把「用户没填」显式转成 undefined**，让 JSON 里根本不出现该字段 ——
   * 直接传 '' 会把卖家的主图清掉（服务端按三态语义处理，不是容错）。
   */
  updateProduct: (spuId: string, body: { title?: string; mainImage?: string }) =>
    api.put<SellerProductView>(`/api/seller/products/${spuId}`, body),
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

  /** 运营概览的图表数据（分布图 / 折线图 / 柱状图 / 饼图） */
  reportCharts: () => api.get<AdminCharts>('/api/admin/reports/charts'),
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
  updateCategory: (id: string, body: CategorySaveRequest) =>
    api.put<CategoryNode>(`/api/admin/categories/${id}`, body),
  setCategoryStatus: (id: string, active: boolean) =>
    api.put<void>(`/api/admin/categories/${id}/status?active=${active}`),
  deleteCategory: (id: string) => api.del<void>(`/api/admin/categories/${id}`),

  brands: () => api.get<BrandView[]>('/api/admin/brands'),
  createBrand: (body: BrandSaveRequest) => api.post<BrandView>('/api/admin/brands', body),
  updateBrand: (id: string, body: BrandSaveRequest) => api.put<BrandView>(`/api/admin/brands/${id}`, body),
  setBrandStatus: (id: string, active: boolean) =>
    api.put<void>(`/api/admin/brands/${id}/status?active=${active}`),
  deleteBrand: (id: string) => api.del<void>(`/api/admin/brands/${id}`),

  // ---- 内容审核（落在 duoduo-product；含未审核通过的内容） ----
  listingsForAudit: (query?: { auditStatus?: string; page?: number; pageSize?: number }) =>
    api.get<PageResult<ListingAuditView>>('/api/admin/listings', { query }),
  auditListing: (id: string, body: ListingAuditRequest) =>
    api.post<ListingAuditView>(`/api/admin/listings/${id}/audit`, body),

  // ---- 运营账号管理（A7，落在 duoduo-user；改角色仅 ADMIN） ----
  adminUsers: (query?: { role?: string; keyword?: string; page?: number; pageSize?: number }) =>
    api.get<PageResult<AdminUserView>>('/api/admin/users', { query }),
  adminChangeRole: (id: string, body: RoleChangeRequest) =>
    api.post<RoleChangeResult>(`/api/admin/users/${id}/role`, body),

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

  // ---- 分享 / 评论举报处理台（第五十六轮，落在 duoduo-fulfilment） ----

  /**
   * 全平台举报列表（按状态过滤；不传 status 即全部）。
   *
   * 服务端会把被举报内容的摘要（`targetExcerpt`）一并下发 —— 列表里直接显示摘要，
   * 运营不必逐条点开才知道在举报什么。内容已被删除时摘要是「（内容已被删除）」。
   */
  shareReports: (query?: { status?: string; page?: number; pageSize?: number }) =>
    api.get<PageResult<ShareReportView>>('/api/admin/share-reports', { query }),

  /**
   * 处理举报。`ACCEPT` = 举报成立，**会把内容下架**（分享 / 评论置为已删除，
   * 并回退相关计数），不可撤销 —— 所以确认文案必须写清后果。
   * `note` 必填：它是回应申诉与事后复盘的唯一凭据。
   */
  handleShareReport: (id: string, body: ShareReportHandleRequest) =>
    api.post<ShareReportView>(`/api/admin/share-reports/${id}/handle`, body),

  // ---- 秒杀活动运营（A14，落在 duoduo-trade） ----

  /**
   * 秒杀活动全量列表（**含已下架**）。
   * 只列已上架的话，「下架」就成了不可逆操作 —— 运营点不到「上架」把它放回来。
   */
  seckillActivities: () => api.get<SeckillActivityView[]>('/api/admin/seckill/activities'),

  /** 建活动：skuId 必须真实存在（服务端会去商品服务校验，不存在返回 404） */
  createSeckillActivity: (body: SeckillActivityCreateRequest) =>
    api.post<SeckillActivityView>('/api/admin/seckill/activities', body),

  /** 上下架；enabled=false 即对 C 端隐藏 */
  setSeckillActivityStatus: (id: string, enabled: boolean) =>
    api.post<SeckillActivityView>(`/api/admin/seckill/activities/${id}/status`, { enabled }),

  /**
   * 编辑活动（R74）：只传标题与时间 —— 商品 / 价格 / 配额由服务端刻意不开。
   * 已成交的活动把开始时间改到未来会被服务端拒（422）。
   */
  updateSeckillActivity: (id: string, body: SeckillActivityEditRequest) =>
    api.put<SeckillActivityView>(`/api/admin/seckill/activities/${id}`, body),

  /**
   * 删除活动（R74）：**只要还有未关闭的秒杀订单就被拒**（待付款 / 已付款 / 已发货 / 已完成）。
   * 订单全部已关闭的活动可以删 —— 否则「自测建一场、下单再取消」会让活动只增不减。
   * 被拒时服务端返回 422 并提示改用「下架」；`t_order` 一律留档。
   */
  deleteSeckillActivity: (id: string) =>
    api.del<void>(`/api/admin/seckill/activities/${id}`),
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
    spuId: string,
    query?: { rating?: number; hasImage?: boolean; page?: number; pageSize?: number },
  ) => api.get<PageResult<ReviewView>>(`/api/reviews/product/${spuId}`, { query }),

  /** 商品评分汇总（公开） */
  summary: (spuId: string) => api.get<ReviewSummary>(`/api/reviews/product/${spuId}/summary`),

  /** 提交评价：一次可提交订单里多件商品 */
  create: (body: ReviewCreateRequest) => api.post<ReviewCreateResult>('/api/reviews', body),

  /** 追评（每条评价一次） */
  append: (id: string, content: string) => api.post<ReviewView>(`/api/reviews/${id}/append`, { content }),

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

  /**
   * 买家填写寄回物流（第五十八轮 P1-5）：只有「待买家寄回」的退货退款能提交。
   * 重复提交服务端返回 409 —— 前端按失败提示，不要当成成功（单号并没有被覆盖）。
   */
  shipReturn: (id: string, body: { courierCompany: string; trackingNo: string }) =>
    api.post<AfterSaleView>(`/api/aftersales/${id}/return-shipment`, body),

  /** 卖家确认收货并退款：退货退款的钱在这一刻才动，此前一直没动 */
  receiveReturn: (id: string) => api.post<AfterSaleView>(`/api/aftersales/${id}/return-receipt`),
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
  seller: (sellerId: string, options?: { signal?: AbortSignal }) =>
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
  claim: (templateId: string) =>
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

/**
 * AI 导购「多多」（/api/duoduo，duoduo-ai 服务）。
 *
 * 两条路径都在网关白名单，**未登录也能问答**（验收②）：
 *  · `capabilities` 先问能力，再决定要不要展示对话入口；
 *  · `chat` 的回复里推荐的商品由服务端从真实商品库召回，前端只负责渲染；
 *  · 「加购 / 下单」这类需要身份的动作**不在这里做** —— 回复文案会引导用户
 *    登录后到商品页操作，所以本模块不需要令牌，也不该往这里塞带身份的请求。
 *
 * 限流是每 IP 每分钟（服务端），触发时返回 429 —— 页面按「太频繁」提示，
 * **不**做自动重试：重试只会让下一次更早撞上限流。
 */
export const duoduoApi = {
  capabilities: (options?: { signal?: AbortSignal }) =>
    api.get<DuoduoCapability>('/api/duoduo/capabilities', options),

  /** 对话；history 为最近 6 轮的带角色历史（服务端上限 6 条） */
  chat: (body: { message: string; history?: DuoduoChatTurn[] }) =>
    api.post<DuoduoChatResponse>('/api/duoduo/chat', body),
};

// ---------- 限时秒杀（/api/seckill，交易域 A14） ----------

/**
 * 秒杀接口。
 *
 * 网关白名单只放行 activities 两级只读路径：活动列表 / 详情未登录可读（导流页），
 * 下单 `place` 需要登录。防超卖的正确性在服务端（Redis 预扣挡量 + DB 原子扣减兜底），
 * 前端不做任何余量判断之外的本地限制 —— 余量显示可能与真实值有瞬间偏差，以服务端为准。
 */
export const seckillApi = {
  activities: (options?: { signal?: AbortSignal }) =>
    api.get<SeckillActivityView[]>('/api/seckill/activities', options),

  detail: (id: string, options?: { signal?: AbortSignal }) =>
    api.get<SeckillActivityView>(`/api/seckill/activities/${id}`, options),

  /**
   * 秒杀下单。idempotentKey 由前端生成（uuid）：双击 / 网络重试都会被服务端幂等去重，
   * 返回 replay=true 与首次订单号，不会重复扣配额。
   */
  place: (body: { activityId: string; skuId: string; addressId: string; idempotentKey: string }) =>
    api.post<PlaceSeckillResult>('/api/seckill/orders', body),
};

// ---------- 我的闲置（C2C 闲置管理 · 第六十六轮补齐 Web 端） ----------
//
// 写入路径一律**三段或单段 + POST**（`/{id}/status`、`/{id}/edit`、`/{id}/delete`、`/mine/list`）：
// 网关白名单按**路径**匹配且不区分方法，`/api/listings/*` 只放行了公开只读那一层，
// 把写端点写成两段等于给匿名用户开了 UGC 直写。
//
// 这六个端点**只要求「登录 + 实名」，不要求卖家角色** —— 二手闲置是个人对个人，
// 与「自营店铺」是两条线。所以普通用户也能在这里发布与管理。
export const listingApi = {
  /** 发布闲置（需登录 + 实名；未实名服务端 403，文案会说明原因） */
  publish: (body: ListingPublishRequest) => api.post<ListingMineView>('/api/listings', body),

  /** 我的发布；status 与 auditStatus 正交组合（前端分类标签按此过滤） */
  mine: (query?: { status?: string; auditStatus?: string; page?: number; pageSize?: number }) =>
    api.get<PageResult<ListingMineView>>('/api/listings/mine/list', { query }),

  /** 卖家视角详情：编辑表单的回填来源（公开详情对未过审内容 404） */
  mineDetail: (id: string) => api.get<ListingOwnerView>(`/api/listings/mine/${id}`),

  /** 上下架；action = ON_SALE | OFF_SHELF */
  changeStatus: (id: string, action: 'ON_SALE' | 'OFF_SHELF') =>
    api.post<ListingMineView>(`/api/listings/${id}/status`, { action }),

  /** 编辑（内容变更后审核态回到 PENDING —— 审核结论只对「当时那段内容」有效） */
  edit: (id: string, body: ListingPublishRequest) =>
    api.post<ListingMineView>(`/api/listings/${id}/edit`, body),

  /** 重新提审（仅 auditStatus=REJECTED 可调，其余 422） */
  resubmit: (id: string, body: ListingPublishRequest) =>
    api.post<ListingMineView>(`/api/listings/${id}/resubmit`, body),

  /** 删除（**软删**：别人的订单页与聊天记录里不会变成「查无此物」） */
  remove: (id: string) => api.post<void>(`/api/listings/${id}/delete`),
};

// ---------- 图片上传（duoduo-fulfilment；写走 /api/uploads，读走公开的 /uploads/**） ----------
//
// 读路径刻意没有 /api 前缀：`<img>` 标签发不出 Authorization 头，
// 而白名单是按路径放行的 —— 读写同路径的话，给 GET 开白名单会把匿名 POST 一起放行。
export const uploadApi = {
  /** 上传一张图片，返回可直接放进 `images` 的 URL */
  image: (file: File) => {
    const form = new FormData();
    form.append('file', file);
    return api.post<{ url: string }>('/api/uploads', form);
  },
};

// ---------- 通知中心 / 关注与粉丝（duoduo-social；第六十七轮补齐 Web 端） ----------
export const noticeApi = {
  /** 通知列表：定向通知 + 全站公告合并视图 */
  list: (query?: { category?: string; page?: number; pageSize?: number }) =>
    api.get<NoticePageData>('/api/socials/notices', { query }),
  unreadCount: () => api.get<UnreadCount>('/api/socials/notices/unread-count'),
  /** 标记单条已读（幂等） */
  markRead: (id: string) => api.post<void>(`/api/socials/notices/${id}/read`),
  /** 全部已读（幂等） */
  readAll: () => api.post<void>('/api/socials/notices/read-all'),
};

export const followApi = {
  follow: (userId: string) => api.post<FollowState>(`/api/socials/follows/${userId}`),
  unfollow: (userId: string) => api.del<FollowState>(`/api/socials/follows/${userId}`),
  following: (query?: { page?: number; pageSize?: number }) =>
    api.get<PageResult<FollowView>>('/api/socials/follows/following', { query }),
  followers: (query?: { page?: number; pageSize?: number }) =>
    api.get<PageResult<FollowView>>('/api/socials/follows/followers', { query }),
  counts: (userId: string) => api.get<FollowCounts>(`/api/socials/follows/${userId}/counts`),
};

// ---------- 私信（R74-5 · Web 端补齐；此前只有小程序有） ----------
export const messageApi = {
  /** 会话列表：每会话最新一条 + 未读数 + 我是否还能发 */
  conversations: (query?: { page?: number; pageSize?: number }) =>
    api.get<PageResult<ConversationView>>('/api/socials/messages/conversations', { query }),

  /**
   * 与某人的双向消息（时间正序，取最新一页）。
   *
   * `productId` / `listingId` 可选：会话键**带上下文段**（同一对用户的多类会话互不串线），
   * 缺省读「主会话」。Web 端 v1 只接主会话与咨询入口透传，不自己拼会话键。
   */
  conversation: (
    peerId: string,
    query?: { productId?: string; listingId?: string; page?: number; pageSize?: number },
  ) => api.get<MessagePageData>(`/api/socials/messages/conversations/${peerId}`, { query }),

  /** 打开会话即标记已读（幂等），返回被标记的条数 */
  markRead: (peerId: string, query?: { productId?: string; listingId?: string }) =>
    api.post<number>(`/api/socials/messages/conversations/${peerId}/read`, undefined, { query }),

  /** 发消息；非互关每人限发一条（咨询会话豁免），被拒时服务端给人话原因 */
  send: (body: { receiverId: string; content: string; productId?: string; listingId?: string }) =>
    api.post<ChatMessageView>('/api/socials/messages', body),

  /** 我的私信未读总数（角标走合并口径 unread-summary；这里用于入口上的独立未读数） */
  unreadCount: () => api.get<UnreadCount>('/api/socials/messages/unread-count'),
};

// ---------- 分享圈（第七十一轮 · Web 端功能对齐 P1） ----------
//
// 信息流 / 详情 / 话题 / 评论列表公开可逛（匿名标记恒 false）；写操作一律 POST 两段路径
// （/{id}/like、/{id}/repost…），网关白名单只放行公开读那几条。
export const shareApi = {
  /** 信息流：scope = RECOMMEND（默认全站）| FOLLOWING（关注流，需登录） */
  feed: (query?: { page?: number; pageSize?: number; scope?: string }) =>
    api.get<PageResult<ShareView>>('/api/shares/feed', { query }),
  hotTopics: (limit = 10) => api.get<ShareTopicView[]>('/api/shares/topics', { query: { limit } }),
  topicFeed: (name: string, query?: { page?: number; pageSize?: number }) =>
    api.get<PageResult<ShareView>>(`/api/shares/topics/${encodeURIComponent(name)}`, { query }),
  detail: (id: string) => api.get<ShareView>(`/api/shares/${id}`),
  /** 发布（需登录）：spuId 必填 */
  create: (body: ShareCreateRequest) => api.post<ShareView>('/api/shares', body),
  repost: (id: string, content: string | null) =>
    api.post<ShareView>(`/api/shares/${id}/repost`, { content }),
  /** 编辑：topics 传 null = 不改，[] = 清空 */
  edit: (id: string, body: ShareEditRequest) => api.post<ShareView>(`/api/shares/${id}/edit`, body),
  /** 删除（软删：别人转发单的引用块会显示「原内容已被作者删除」） */
  remove: (id: string) => api.post<void>(`/api/shares/${id}/delete`),
  toggleLike: (id: string) => api.post<EngagementState>(`/api/shares/${id}/like`),
  toggleFavorite: (id: string) => api.post<EngagementState>(`/api/shares/${id}/favorite`),
  mine: (query?: { page?: number; pageSize?: number }) =>
    api.get<PageResult<ShareView>>('/api/shares/mine/list', { query }),
};

export const shareCommentApi = {
  /** 评论列表：parentId 为空 = 一级评论分页；非空 = 该评论下的回复分页 */
  list: (shareId: string, query?: { parentId?: string; page?: number; pageSize?: number }) =>
    api.get<PageResult<ShareCommentView>>('/api/shares/comments', { query: { shareId, ...query } }),
  create: (shareId: string, content: string, parentCommentId?: string | null) =>
    api.post<ShareCommentView>(`/api/shares/${shareId}/comments`, { content, parentCommentId: parentCommentId ?? null }),
  toggleLike: (commentId: string) => api.post<EngagementState>(`/api/shares/comments/${commentId}/like`),
};
