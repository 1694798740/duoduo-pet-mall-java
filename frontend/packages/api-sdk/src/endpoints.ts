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
  SellerProductStat,
  SellerProductRefundStat,
  AbnormalSettlement,
  AdminCharts,
  AdminProductAuditRequest,
  AdminProductAuditView,
  AdminReviewView,
  SpuAuditLogView,
  AuditLogQuery,
  AuditLogView,
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
  CategoryAttrSaveRequest,
  CategoryAttrView,
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
  ReviewAuditRequest,
  ReviewCreateRequest,
  ReviewCreateResult,
  ReviewSummary,
  ReviewView,
  ReviewedOrders,
  SellerReviewView,
  PetView,
  PlaceOrderRequest,
  PlaceOrderResult,
  ProductCard,
  ProductDetail,
  RefundResult,
  RoleChangeRequest,
  RoleChangeResult,
  SellerProductCreateRequest,
  SellerProductBatchResultView,
  SellerProductCsvView,
  SellerProductDetailView,
  SellerProductDiagnosticsView,
  SellerProductImportResultView,
  SellerProductUpdateRequest,
  SellerProductView,
  SellerSkuStructureRequest,
  SellerSkuStructureResultView,
  SellerSkuUsageView,
  FollowCounts,
  FollowState,
  FollowView,
  UserProfileView,
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
  UnreadSummary,
  TotpEnableView,
  TotpSetupView,
  TotpStatusView,
  SellerSummary,
  SellerWalletView,
  SettlementBill,
  SettlementPage,
  SettlementView,
  ShipRequest,
  StorePublicView,
  TrackListView,
  CourierView,
  WaybillView,
  UsableCouponResult,
  UserView,
  PlaceSeckillResult,
  SeckillActivityCreateRequest,
  SeckillActivityEditRequest,
  SeckillActivityView,
  ShareReportHandleRequest,
  ShareReportView,
  MerchantApplicationSummary as ApplicationSummary,
  MerchantApplicationView,
  MerchantAuditSampleReport,
  MerchantAuditSampleView,
  MerchantLicenseSweepReport,
  MerchantRejectReason,
  StaffView,
  StaffAdminView,
  AuditSlaMetrics,
  AuditSampleReport,
  AuditSampleReviewRequest,
  AuditSampleView,
  TenantHealthLimits,
  TenantHealthPanel,
  RegeoView,
  JsApiConfig,
  DeleteBlockersView,
  DeleteAccountView,
} from './types';

// ---------- 认证（/api/auth，令牌走 httpOnly Cookie） ----------

export const authApi = {
  /** 注册并直接登录；手机号冲突后端返回 409 */
  register: (body: { phone: string; password: string; nickname?: string }) =>
    api.post<UserView>('/api/auth/register', body, { allowRefresh: false }),

  /**
   * 密码登录；账号不存在与密码错误返回同一提示（防手机号枚举）。
   *
   * <p>E21：已启用两步验证的账号必须再带 `totpCode`（认证器里的 6 位码）
   * 或 `recoveryCode`（一次性恢复码）。缺失时后端返回 401 且 code 为 `TOTP_REQUIRED`，
   * 前端据此切到"第二步" —— 而不是把用户引去反复重输密码。
   */
  login: (body: { phone: string; password: string; totpCode?: string; recoveryCode?: string }) =>
    api.post<UserView>('/api/auth/login', body, { allowRefresh: false }),

  /** 刷新令牌轮转：旧令牌立即吊销，签发新对 */
  refresh: () => api.post<UserView>('/api/auth/refresh', undefined, { allowRefresh: false }),

  /** 吊销刷新令牌并清 Cookie */
  logout: () => api.post<void>('/api/auth/logout', undefined, { allowRefresh: false }),
};

/**
 * E21 · 两步验证（TOTP）自助管理。
 *
 * <p>路径在 `/api/auth/2fa/**` 而非 `/api/admin/**`：它是账号安全设置，
 * 且**必须在通过第二步之前就能访问** —— 否则"要进后台先绑 2FA、要绑 2FA 先进后台"就成了死锁。
 */
export const twoFactorApi = {
  /** 当前账号的绑定状态（是否已启用、绑定时间、剩余恢复码数量） */
  status: () => api.get<TotpStatusView>('/api/auth/2fa/status'),

  /** 第一步：取密钥与 otpauth 链接（此时**尚未**启用） */
  setup: () => api.post<TotpSetupView>('/api/auth/2fa/setup'),

  /** 第二步：用一次正确的动态码确认绑定；响应里**一次性**返回恢复码明文 */
  enable: (body: { code: string }) => api.post<TotpEnableView>('/api/auth/2fa/enable', body),

  /** 解绑：动态码与恢复码二选一（手机丢了只能靠后者） */
  disable: (body: { code?: string; recoveryCode?: string }) =>
    api.post<void>('/api/auth/2fa/disable', body),
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
   * 店铺公开主页信息（第八十二轮）：免登可查，仅营业中店铺返回、其余 404。
   * ownerUserId 必须以 string 传入（18 位雪花 id）。
   */
  storeInfo: (ownerUserId: string) =>
    api.get<StorePublicView>(`/api/users/stores/${ownerUserId}`),

  /**
   * C7 · 账号注销。
   *
   * 三个动作与后端状态机一一对应：预检（阻塞清单）→ 申请（进入冷静期）
   * → 撤销（冷静期内可反悔）。撤销刻意不依赖登录态：注销期间登录已被停用。
   */
  deleteAccount: {
    /** 预检：返回阻塞项清单 + 当前注销状态 */
    precheck: () => api.get<DeleteBlockersView>('/api/users/me/delete-account'),
    /** 提交申请；被业务挡住时后端返回 409 ACCOUNT_DELETE_BLOCKED */
    request: (body: { password: string; reason?: string }) =>
      api.post<DeleteAccountView>('/api/users/me/delete-account', body),
    /** 撤销（需登录态，便捷通道） */
    cancel: (body: { password: string }) =>
      api.del<null>('/api/users/me/delete-account', body),
    /** 撤销（无登录态：手机号 + 密码） */
    cancelByPhone: (body: { phone: string; password: string }) =>
      api.post<null>('/api/auth/account-deletion/cancel', body),
  },

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
  saveMerchantApplication: (body: {
    storeName?: string;
    materials?: Record<string, string>;
    /**
     * E15 · 材料类型名 → 有效期至（yyyy-MM-dd）。
     * 未出现的类型 = 不修改；显式传空串 = 改为「长期有效」。
     */
    materialExpiry?: Record<string, string>;
  }) => api.put<MerchantApplicationView>('/api/users/me/merchant-application', body),

  /**
   * E15 · 资质续期：临期（APPROVED）或已停业（SUSPENDED）的店铺换新证后重新送审。
   *
   * <p>提交后状态回到 SUBMITTED，需平台重新审核 —— 续期不是自动通过，
   * 否则商家拿一张过期证照的截图就能把停业状态刷回来。
   */
  renewMerchantApplication: (body?: {
    storeName?: string;
    materials?: Record<string, string>;
    materialExpiry?: Record<string, string>;
  }) => api.post<MerchantApplicationView>('/api/users/me/merchant-application/renewal', body ?? {}),

  /** 提交送审（幂等：重复提交返回现状；被驳回后可修改材料重提）。服务端校验实名 + 店名 + 6 类材料 */
  submitMerchantApplication: () =>
    api.post<MerchantApplicationView>('/api/users/me/merchant-application/submit'),

  /**
   * 营业中店铺改名（第八十一轮）。仅 APPROVED 可改；全局唯一，重名报业务冲突；
   * 改名即时生效（购物车分组 / 商品卡片实时查店名，无缓存）。
   */
  renameStore: (storeName: string) =>
    api.patch<MerchantApplicationView>('/api/users/me/merchant-application/store-name',
      { storeName }),

  /** 激活（审核通过后）：返回重签的访问令牌（入口固定商家侧）；浏览器通道同时写 Cookie */
  activateMerchant: () =>
    api.post<{ accessToken: string; expiresInSeconds: number }>(
      '/api/users/me/merchant-application/activate'),

  /**
   * E12 · 上传一份资质材料（**私有通道**）：文件落私有卷、读取走鉴权代理，
   * 返回的 path 是鉴权读取路径（不是公开 URL）—— 把它填进 saveDraft 的 materials。
   * 支持 jpg/png/webp/pdf，单文件 ≤ 6MB。
   */
  uploadMerchantMaterial: (file: File) => {
    const form = new FormData();
    form.append('file', file);
    return api.post<{ path: string; storedName: string; originalName: string }>(
      '/api/users/me/merchant-application/materials', form);
  },
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
  /** 类目属性模板（A-10）：只返回 ACTIVE 项，发布 / 编辑商品时按它渲染与校验 */
  categoryAttrs: (categoryId: string) => api.get<CategoryAttrView[]>(`/api/categories/${categoryId}/attrs`),
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
  /** self 自营 / third 第三方（与 t_spu.supply_type 真实取值一致；merchant 为历史别名，库中不存在） */
  supplyType?: 'self' | 'third';
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
   * 店铺主页在售商品（第八十二轮）：按 sellerId（= 店主 userId）过滤的公开列表。
   * sellerId=0 即「多多自营」货架。sellerId 必须以 string 传入（18 位雪花 id）。
   */
  storeProducts: (
    sellerId: string,
    query?: { sort?: string; keyword?: string; page?: number; pageSize?: number; signal?: AbortSignal },
  ) => api.get<PageResult<ProductCard>>(`/api/products/store/${sellerId}`, { query }),

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

  /**
   * 我的订单列表。
   * @param orderType 订单类型（NORMAL / SECKILL / C2C）；**服务端筛选**，不是前端过滤 ——
   *                  分页下前端过滤只会得到「当前页里的那几条」，数据在下一页时表现为「没有」。
   */
  list: (query?: { status?: string; orderType?: string; keyword?: string; page?: number; pageSize?: number }) =>
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

  /** 按商品的经营统计（2026-10-24）：销量 / 成交额 / 退款件数，商品分析表用 */
  productStats: () => api.get<SellerProductStat[]>('/api/seller/product-stats'),

  /** 按商品 / SKU 的退款统计（D-05）：退款件数 / 退款金额 / 涉及订单数，售后页用 */
  productRefundStats: () => api.get<SellerProductRefundStat[]>('/api/seller/product-refunds'),

  /** 卖出的订单列表；status 可选 PENDING_PAYMENT / PAID / SHIPPED / COMPLETED / REFUNDED / CLOSED */
  orders: (query?: { status?: string; keyword?: string; page?: number; pageSize?: number }) =>
    api.get<PageResult<OrderView>>('/api/seller/orders', { query }),

  orderDetail: (orderNo: string) => api.get<OrderView>(`/api/seller/orders/${orderNo}`),

  /** 卖家发货；物流信息可选，随状态日志留痕。发货后资金仍在托管中 */
  ship: (orderNo: string, body?: ShipRequest) =>
    api.post<OrderView>(`/api/seller/orders/${orderNo}/ship`, body),

  /** 卖家售后退款（已付款 / 已发货都可，前提是已确认收到退货） */
  refund: (orderNo: string, reason?: string) =>
    api.post<RefundResult>(`/api/seller/orders/${orderNo}/refund`, reason ? { reason } : undefined),

  /** 我的资金托管单；status 可选 CUSTODY / RELEASED / REFUNDED，汇总不受过滤影响 */
  settlements: (query?: { status?: string; keyword?: string; page?: number; pageSize?: number }) =>
    api.get<SettlementPage>('/api/seller/settlements', { query }),

  /**
   * E22 · 我的钱包：「我现在能取多少」的**唯一答案**。
   *
   * 与 {@link settlements} 的分工：那个是明细台账（每笔钱在哪），这个是四个数
   * （待结算 / 冻结中 / 可提现 / 累计到账）。
   * ★ 前端**不要**从明细里自己加总 —— 冻结规则一变，自己加的总就会与提现结果对不上，
   *   而对不上的那个数恰好是用户最在意的。展示什么就以这里返回的为准。
   */
  wallet: () => api.get<SellerWalletView>('/api/seller/settlements/wallet'),

  /**
   * E22 · 我的结算账单（租户 × 周期）。
   *
   * period 形如 `2026-10`（整月）或 `2026-10-05`（单日）；不传 = 当前月。
   * 格式非法服务端返回 400（**不会**静默回落到当前月 —— 那会让"查 9 月看到 10 月"不报错）。
   */
  bill: (query?: { period?: string }) =>
    api.get<SettlementBill>('/api/seller/settlements/bills', { query }),

  // ---- 商品管理（/api/seller/products/**，落在 duoduo-product 服务） ----

  /**
   * 我的商品列表。
   *
   * auditStatus 按**审核维度**筛（PENDING / APPROVED / REJECTED），
   * 与交易维度的 status 是两件事：一件商品可以「已过审但被自己下架」，
   * 也会出现「驳回件等卖家改内容」。列表页把两个维度做成两组标签。
   *
   * 运营 / 管理员可显式按 sellerId 筛选（普通卖家传了也会被服务端忽略）。
   */
  products: (query?: {
    keyword?: string;
    auditStatus?: string;
    sellerId?: number;
    /** true = 回收站（A-09）；缺省 = 正常商品 */
    deleted?: boolean;
    page?: number;
    pageSize?: number;
  }) => api.get<PageResult<SellerProductView>>('/api/seller/products', { query }),

  /**
   * 删除商品（A-09，软删进回收站，30 天内可恢复）。
   *
   * 语义是「移入回收站」而不是「资源消失」，所以走 POST 动作式端点、不是 HTTP DELETE。
   * 幂等：重复删除不会报错，也不会刷新保留期。
   */
  deleteProduct: (spuId: string) => api.post<SellerProductView>(`/api/seller/products/${spuId}/delete`),

  /** 从回收站恢复（A-09）：超期返回业务错误；恢复后为下架态，需卖家核对后重新上架 */
  restoreProduct: (spuId: string) => api.post<SellerProductView>(`/api/seller/products/${spuId}/restore`),

  /**
   * 商品详情（商家视角，编辑回填用）。
   *
   * 为什么不复用公开的 `/api/products/{id}`：公开详情对未过审商品一律 404
   * （防状态码探测），而卖家必须能读回自己被驳回的商品才能修改重提。
   */
  productDetail: (spuId: string) => api.get<SellerProductDetailView>(`/api/seller/products/${spuId}`),

  /**
   * 提交商品（E14）。
   *
   * 归属 / 供货类型 / 审核态**全部由服务端决定**：第三方商家提交的落 PENDING（必须审核），
   * 运营提交的落 APPROVED（自营免审）。请求体里不要带这三个字段。
   *
   * 提交后商品即处于「在售 + 待审核」：前台看不见，审核通过的那一刻自动出现 ——
   * 商家不需要再点一次上架。
   */
  createProduct: (body: SellerProductCreateRequest) =>
    api.post<SellerProductView>('/api/seller/products', body),

  /** 上下架（幂等开关）：action = ON_SALE | OFF_SHELF；越权操作别人的商品返回 403 */
  changeProductStatus: (spuId: string, action: 'ON_SALE' | 'OFF_SHELF') =>
    api.post<SellerProductView>(`/api/seller/products/${spuId}/status`, { action }),

  /**
   * 批量上下架（A-13）：逐条结果，部分失败不回滚。
   *
   * 服务端对每件商品独立执行与单品相同的门禁（归属 / 资质 / 审核态）；
   * 请求整体 200 只代表处理完成，成败看返回里每条的 success。
   * 单次上限 100 件，重复 id 去重。
   */
  batchChangeProductStatus: (ids: string[], action: 'ON_SALE' | 'OFF_SHELF') =>
    api.post<SellerProductBatchResultView>('/api/seller/products/batch-status', { ids, action }),

  /**
   * 重新提审（E14）：被驳回且**无需改内容**的商品回到审核队列。
   *
   * 用途是「申请复核」—— 驳回可能是误判（图片被判违规但实际合规），
   * 此时卖家没有内容可改；只给「改内容才能重提」会逼他随便改一个字来凑。
   */
  resubmitProduct: (spuId: string) => api.post<SellerProductView>(`/api/seller/products/${spuId}/resubmit`),

  /**
   * 编辑自己的商品（内容字段）。
   *
   * 字段三态：不传 = 不改；空串 = 清空（副标题 / 详情 / 详情图可清空，主图不允许）；
   * 有值 = 替换。这里**必须把「用户没填」显式转成 undefined**，
   * 让 JSON 里根本不出现该字段 —— 直接传 '' 会把卖家的内容清掉（服务端按三态语义处理，不是容错）。
   *
   * ⚠️ **内容变更会触发重新审核**（第三方商品）：审核结论是针对「当时那段内容」作出的。
   * 界面上必须提前告知卖家「保存后商品将重新进入审核，期间前台不可见」。
   */
  updateProduct: (spuId: string, body: SellerProductUpdateRequest) =>
    api.put<SellerProductView>(`/api/seller/products/${spuId}`, body),

  /** SKU 改价 / 改库存（2026-10-24）：不触发内容重审，服务端会同步商品价格区间 */
  updateSkuPriceStock: (spuId: string, body: { skus: import('./types').SkuPriceStockInput[] }) =>
    api.put<import('./types').SellerProductDetailView>(`/api/seller/products/${spuId}/skus/price-stock`, body),

  /**
   * 规格增删改（A-14）：请求里的 skus 是保存后的**全部规格**。
   *
   * 结构变更（新增 / 删除 / 改名 / 换图）会让第三方商品重新进入审核并升版本；
   * 只改价格 / 库存时不重审、不升版本。
   * 有订单（或仍有冻结库存）的规格不能删除：服务端整单拒绝，不会删一半。
   */
  updateProductSkus: (spuId: string, body: SellerSkuStructureRequest) =>
    api.put<SellerSkuStructureResultView>(`/api/seller/products/${spuId}/skus`, body),

  /** 规格订单占用（A-14，只读）：列表里没有的规格才允许删除 */
  productSkuUsage: (spuId: string) =>
    api.get<SellerSkuUsageView[]>(`/api/seller/products/${spuId}/skus/usage`),

  // ---------------- A-17 · 商品复制 / CSV 导入导出 / 诊断 ----------------

  /** 复制商品：生成同款新商品，默认下架 */
  copyProduct: (spuId: string) => api.post<SellerProductView>(`/api/seller/products/${spuId}/copy`),

  /** 导出商品为 CSV：返回 JSON 信封里的 UTF-8 BOM 文本，自行转 Blob 下载 */
  exportProductsCsv: () => api.get<SellerProductCsvView>('/api/seller/products/export'),

  /** 下载商品 CSV 导入模板（表头 + 一行示例） */
  importProductTemplate: () => api.get<SellerProductCsvView>('/api/seller/products/import-template'),

  /** 上传商品 CSV 文本导入：逐分组键创建，部分成功返回逐行详情 */
  importProductsCsv: (body: { content: string }) =>
    api.post<SellerProductImportResultView>('/api/seller/products/import', body),

  /** 商品诊断：近 days 天曝光 / 点击 / 点击率 + 建议；days 缺省 30，上限 90 */
  productDiagnostics: (query?: { days?: number }) =>
    api.get<SellerProductDiagnosticsView>('/api/seller/products/diagnostics', { query }),
};

// ---------- 平台管理后台（/api/admin，需运营 / 管理员） ----------

/**
 * 平台管理后台接口。
 *
 * 与卖家中心的关键差别：卖家中心的可见范围是「我的单」，这里是「全平台」。
 * 全部接口由服务端 OperatorGuard.requireOperator() 把关，非运营返回 403；
 * 前端据此把入口藏给运营 / 管理员，但那只是体验优化，不是安全边界。
 */
/** 店员管理（E13，店主侧；OWNER-only —— 店员调用一律 403） */
export const merchantStaffApi = {
  list: () => api.get<StaffView[]>('/api/seller/staff'),
  invite: (phone: string) =>
    api.post<StaffView>('/api/seller/staff', { phone }),
  disable: (memberId: string) =>
    api.post<StaffView>(`/api/seller/staff/${memberId}/disable`),
  enable: (memberId: string) =>
    api.post<StaffView>(`/api/seller/staff/${memberId}/enable`),
  /** F-04 · 配置店员操作权限（空数组 = 仅查看） */
  updatePermissions: (memberId: string, permissions: string[]) =>
    api.post<StaffView>(`/api/seller/staff/${memberId}/permissions`, { permissions }),
};

/** 平台侧成员总览（E13；跨租户 + 平台处置） */
export const adminMerchantStaffApi = {
  page: (query?: { keyword?: string; page?: number; pageSize?: number }) =>
    api.get<PageResult<StaffAdminView>>('/api/admin/merchant-staff', { query }),
  disable: (memberId: string) =>
    api.post<StaffAdminView>(`/api/admin/merchant-staff/${memberId}/disable`),
  enable: (memberId: string) =>
    api.post<StaffAdminView>(`/api/admin/merchant-staff/${memberId}/enable`),
};

/** 入驻审核台（E11；落在 duoduo-user，路径属 E8 的 admin 专属族） */
export const adminMerchantApi = {
  page: (query?: { status?: string; keyword?: string; page?: number; pageSize?: number }) =>
    api.get<PageResult<ApplicationSummary>>('/api/admin/merchants', { query }),
  detail: (id: string) => api.get<MerchantApplicationView>(`/api/admin/merchants/${id}`),
  /** 开始审核：SUBMITTED → REVIEWING（幂等） */
  startReview: (id: string) =>
    api.post<MerchantApplicationView>(`/api/admin/merchants/${id}/start-review`),
  /** 通过并开店（幂等：重复通过返回现状） */
  approve: (id: string) =>
    api.post<MerchantApplicationView>(`/api/admin/merchants/${id}/approve`),
  /** 驳回（结构化原因必填；结论一致时幂等更新） */
  reject: (id: string, reasons: MerchantRejectReason[]) =>
    api.post<MerchantApplicationView>(`/api/admin/merchants/${id}/reject`, { reasons }),
  /**
   * E15 · 手动触发一轮资质巡检（临期提醒 + 过期自动停业），返回本轮处理计数。
   *
   * 定时任务 6 小时一轮，这个入口给运营一个「现在就按最新数据重算一遍」的按钮：
   * 补录/修正有效期后不必等到下一轮，验收与排障都能当场看到结果。
   * 处置动作均为条件更新且提醒「只升档才发」，可安全重复点击。
   */
  licenseSweep: () =>
    api.post<MerchantLicenseSweepReport>('/api/admin/merchants/license-sweep'),

  // ---- E28 · 入驻侧事后抽查（挂在同一个控制器上，路径两段以免与 /{id} 抢匹配） ----

  /**
   * 入驻抽查队列。`status` 不传 = PENDING（运营的待办就是「还没复核的那批」）。
   *
   * 抽查**不是**上线前的人工关卡：店铺已经自动放行、已在营业，这里复核的是
   * 「放行判对了没有」。所以这个队列里的东西比待审队列更紧急（处置错了两边后果不同）。
   */
  auditSamples: (query?: { status?: string; keyword?: string; page?: number; pageSize?: number }) =>
    api.get<PageResult<MerchantAuditSampleView>>('/api/admin/merchants/audit-sla/samples', { query }),

  /** 复核：PASS 只记结论；REJECT = 直接停业 + 联动下架该店商品（note 必填） */
  reviewAuditSample: (id: string, body: AuditSampleReviewRequest) =>
    api.post<MerchantAuditSampleView>(`/api/admin/merchants/audit-sla/samples/${id}/review`, body),

  /** SLA 自证指标（读它顺带评估并执行自动收紧） */
  auditMetrics: () => api.get<AuditSlaMetrics>('/api/admin/merchants/audit-sla/metrics'),

  /** 立即跑一轮入驻抽查（抽昨天那批自动放行的店铺，唯一键幂等） */
  auditSweep: () => api.post<MerchantAuditSampleReport>('/api/admin/merchants/audit-sla/sweep'),
};

export const adminApi = {
  // ---- 运营报表与资金对账（落在 duoduo-trade） ----
  reportOverview: () => api.get<AdminReport>('/api/admin/reports/overview'),

  /** 运营概览的图表数据（分布图 / 折线图 / 柱状图 / 饼图） */
  reportCharts: () => api.get<AdminCharts>('/api/admin/reports/charts'),
  settlementSummary: () => api.get<AdminSettlementSummary>('/api/admin/settlements/summary'),
  settlementAbnormal: () => api.get<AbnormalSettlement[]>('/api/admin/settlements/abnormal'),

  // ---- E24 · 租户健康度面板（落在 duoduo-trade） ----
  //
  // 一次请求拿四维（订单/成交、售后率、结算异常、接口错误率）——
  // 那四路分别来自交易库、履约库、Redis，前端分四次拉再自己拼，
  // 会出现"四个数字来自四个时刻"以及"其中一格失败但页面看起来完好"。
  //
  // days / limit 的默认值与上限一律来自 tenantHealthLimits()，
  // **不在这里写死**：阈值是政策，抄一份意味着政策调整后前端静默按旧数解释。
  tenantHealth: (query?: { days?: number; limit?: number }) =>
    api.get<TenantHealthPanel>('/api/admin/tenant-health', { query }),

  /** 运行时口径（窗口上限、判级阈值、档位清单）—— 页面用它渲染"为什么是风险" */
  tenantHealthLimits: () => api.get<TenantHealthLimits>('/api/admin/tenant-health/limits'),

  // ---- 订单干预（落在 duoduo-trade；仅待付款订单可强关） ----
  orders: (query?: { status?: string; orderNo?: string; keyword?: string; page?: number; pageSize?: number }) =>
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

  // ---- 类目属性模板（A-10） ----
  // 与类目 CRUD 同一个控制器：模板是类目的从属主数据，运营在类目页里原地维护。
  categoryAttrs: (categoryId: string) =>
    api.get<CategoryAttrView[]>(`/api/admin/categories/${categoryId}/attrs`),
  createCategoryAttr: (categoryId: string, body: CategoryAttrSaveRequest) =>
    api.post<CategoryAttrView>(`/api/admin/categories/${categoryId}/attrs`, body),
  updateCategoryAttr: (id: string, body: CategoryAttrSaveRequest) =>
    api.put<CategoryAttrView>(`/api/admin/category-attrs/${id}`, body),
  setCategoryAttrStatus: (id: string, active: boolean) =>
    api.put<void>(`/api/admin/category-attrs/${id}/status?active=${active}`),
  deleteCategoryAttr: (id: string) => api.del<void>(`/api/admin/category-attrs/${id}`),

  brands: () => api.get<BrandView[]>('/api/admin/brands'),
  createBrand: (body: BrandSaveRequest) => api.post<BrandView>('/api/admin/brands', body),
  updateBrand: (id: string, body: BrandSaveRequest) => api.put<BrandView>(`/api/admin/brands/${id}`, body),
  setBrandStatus: (id: string, active: boolean) =>
    api.put<void>(`/api/admin/brands/${id}/status?active=${active}`),
  deleteBrand: (id: string) => api.del<void>(`/api/admin/brands/${id}`),

  // ---- 内容审核（落在 duoduo-product；含未审核通过的内容） ----
  listingsForAudit: (query?: { auditStatus?: string; keyword?: string; page?: number; pageSize?: number }) =>
    api.get<PageResult<ListingAuditView>>('/api/admin/listings', { query }),
  auditListing: (id: string, body: ListingAuditRequest) =>
    api.post<ListingAuditView>(`/api/admin/listings/${id}/audit`, body),

  // ---- 第三方商品上架审核（E14，落在 duoduo-product） ----
  // 只列第三方商品：平台自营免审（auditStatus 恒 APPROVED），混进队列只会干扰待办。
  // 与闲置审核同一套状态口径（PENDING / APPROVED / REJECTED + 驳回必填原因），
  // 运营在两处不需要学两套规则。
  productsForAudit: (query?: { auditStatus?: string; sellerId?: number; keyword?: string; page?: number; pageSize?: number }) =>
    api.get<PageResult<AdminProductAuditView>>('/api/admin/products', { query }),
  productAuditDetail: (spuId: string) => api.get<AdminProductAuditView>(`/api/admin/products/${spuId}`),
  /** 审核结论：驳回时 reason 必填（服务端会 400）；审核可重入（允许改判） */
  auditProduct: (spuId: string, body: AdminProductAuditRequest) =>
    api.post<AdminProductAuditView>(`/api/admin/products/${spuId}/audit`, body),

  /** 审核历史（A-08）：最新在前；limit 缺省 50、服务端上限 100 */
  productAuditHistory: (spuId: string, limit?: number) =>
    api.get<SpuAuditLogView[]>(`/api/admin/products/${spuId}/history`, {
      query: limit == null ? undefined : { limit },
    }),

  // ---- E28 · 商品侧事后抽查（落在 duoduo-product，路径族与审核台分开） ----
  //
  // 为什么另开一个路径族而不是塞进 /api/admin/products：那个是「人工待审队列」，
  // 这个是「已经自动放行、现在回头抽查」。合并后运营分不清「在等我审」和
  // 「已经在卖、我只是复核」—— 后者更紧急，且判错的后果落在真实在售商品上。
  /** 抽查队列；status 不传 = PENDING */
  auditSlaSamples: (query?: { status?: string; keyword?: string; page?: number; pageSize?: number }) =>
    api.get<PageResult<AuditSampleView>>('/api/admin/audit-sla/samples', { query }),

  /** 复核：PASS 只记结论；REJECT = 驳回并**强制下架**（note 必填） */
  reviewAuditSlaSample: (id: string, body: AuditSampleReviewRequest) =>
    api.post<AuditSampleView>(`/api/admin/audit-sla/samples/${id}/review`, body),

  /** SLA 自证指标（读它顺带评估并执行自动收紧） */
  auditSlaMetrics: () => api.get<AuditSlaMetrics>('/api/admin/audit-sla/metrics'),

  /** 立即跑一轮商品抽查（抽昨天那批自动放行的第三方商品） */
  auditSlaSweep: () => api.post<AuditSampleReport>('/api/admin/audit-sla/sweep'),

  // ---- 运营账号管理（A7，落在 duoduo-user；改角色仅 ADMIN） ----
  adminUsers: (query?: { role?: string; keyword?: string; page?: number; pageSize?: number }) =>
    api.get<PageResult<AdminUserView>>('/api/admin/users', { query }),
  adminChangeRole: (id: string, body: RoleChangeRequest) =>
    api.post<RoleChangeResult>(`/api/admin/users/${id}/role`, body),

  /**
   * E21 · 重置某账号的两步验证（**仅 ADMIN**；手机与恢复码都丢失时的最后手段）。
   * 这是全项目唯一一处"能替别人摘下第二把锁"的入口，后端强制留痕。
   */
  adminResetTwoFactor: (id: string) => api.post<void>(`/api/admin/users/${id}/2fa/reset`),

  // ---- 售后仲裁工作台（A3，落在 duoduo-fulfilment） ----

  /** 全平台售后列表（按状态过滤） */
  afterSales: (query?: { status?: string; keyword?: string; page?: number; pageSize?: number }) =>
    api.get<PageResult<AfterSaleView>>('/api/admin/aftersales', { query }),

  /**
   * 裁定。`APPROVE_REFUND` 会**真的执行退款**（调交易域既有的运营侧退款入口）。
   * 一种会失败的情况必须在前端讲清楚：订单已完成且钱已放款给卖家时，交易域返回 422
   * 「需向卖家追偿 / 平台垫付」——那是资金边界 B-01，系统退不了，只能人工处理。
   */
  arbitrateAfterSale: (id: string, body: AfterSaleArbitrateRequest) =>
    api.post<AfterSaleView>(`/api/admin/aftersales/${id}/arbitrate`, body),

  // ---- 评价管理台（H-02，落在 duoduo-fulfilment） ----

  /**
   * 全平台评价列表（含待审 / 已下架）。
   *
   * `status` 为空 = 全部；`rating` 1-5；`keyword` 匹配评价正文 / 追评 / 商品标题 / 订单号。
   */
  reviews: (query?: { status?: string; rating?: number; keyword?: string; page?: number; pageSize?: number }) =>
    api.get<PageResult<AdminReviewView>>('/api/admin/reviews', { query }),

  /**
   * 人工审核评价：`REJECT` 下架（原因必填）、`APPROVE` 恢复。
   *
   * 下架会立即把评价从公开评价区 / 评分汇总中移除，并在事务提交后触发商品评分重算；
   * 已处于目标态时幂等成功且不写库。
   */
  auditReview: (id: string, body: ReviewAuditRequest) =>
    api.post<AdminReviewView>(`/api/admin/reviews/${id}/audit`, body),

  // ---- 分享 / 评论举报处理台（第五十六轮，落在 duoduo-fulfilment） ----

  /**
   * 全平台举报列表（按状态过滤；不传 status 即全部）。
   *
   * 服务端会把被举报内容的摘要（`targetExcerpt`）一并下发 —— 列表里直接显示摘要，
   * 运营不必逐条点开才知道在举报什么。内容已被删除时摘要是「（内容已被删除）」。
   */
  shareReports: (query?: { status?: string; keyword?: string; page?: number; pageSize?: number }) =>
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

  // ---- 后台操作审计日志（E18，落在 duoduo-user） ----

  /**
   * 检索审计记录（**只有读，没有写**）。
   *
   * 服务端刻意不提供任何修改 / 删除入口：审计的完整性靠「根本没有修改路径」来保证，
   * 而不是靠「没人会去改」。将来若要按类目归档，必须先改服务端的档位判据
   * （`AuditCategory.immutable()`）并让「允许删什么」变成一次显式决定。
   *
   * 过滤条件全部可选；**不带任何条件即「最近若干条」** —— 这正是打开页面那一下发出的请求。
   * 时间参数接受 `2026-10-09`（整日，止端按「含」处理）或 `2026-10-09T13:00:00`；
   * 格式不对服务端回 400 而不是静默忽略（静默忽略会让人以为「这段时间真的没发生什么」）。
   */
  auditLogs: (query?: AuditLogQuery) =>
    api.get<PageResult<AuditLogView>>('/api/admin/audit-logs', { query }),
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

  // ---- 商家端（A-16，需登录 + 卖家角色） ----

  /**
   * 我店铺的评价分页。
   *
   * 可见范围由登录态推导，**没有 sellerId 参数**；只返回审核通过的评价。
   * `replied`：true 已回复 / false 待回复 / 不传 全部。
   */
  sellerList: (query?: {
    replied?: boolean;
    rating?: number;
    keyword?: string;
    page?: number;
    pageSize?: number;
  }) => api.get<PageResult<SellerReviewView>>('/api/reviews/seller', { query }),

  /** 商家回复评价（每条一次；回复后不可修改） */
  sellerReply: (id: string, content: string) =>
    api.post<SellerReviewView>(`/api/reviews/${id}/reply`, { content }),
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
  mine: (query?: { status?: string; keyword?: string; page?: number; pageSize?: number }) =>
    api.get<PageResult<AfterSaleView>>('/api/aftersales/mine', { query }),

  /** 我店铺的售后（卖家 / 运营视角；非卖家返回 403） */
  seller: (query?: { status?: string; keyword?: string; page?: number; pageSize?: number }) =>
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

  /** 支持的快递公司（B-05）：卖家发货弹窗里的下拉选项 */
  couriers: () => api.get<CourierView[]>('/api/logistics/couriers'),

  /** 申请电子面单（B-05，幂等）：同订单重复申请返回同一张 */
  createWaybill: (body: { orderNo: string; courierCode?: string }) =>
    api.post<WaybillView>('/api/logistics/waybills', body),

  /** 订单当前电子面单；没有面单返回 404（前端按「尚未申请」处理） */
  waybill: (orderNo: string) =>
    api.get<WaybillView>(`/api/logistics/orders/${encodeURIComponent(orderNo)}/waybill`),
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
   * couponCode 可选：秒杀价与券**叠加**（门槛按秒杀价判定），不传即不用券。
   */
  place: (body: {
    activityId: string;
    skuId: string;
    addressId: string;
    idempotentKey: string;
    couponCode?: string;
  }) => api.post<PlaceSeckillResult>('/api/seckill/orders', body),
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
  mine: (query?: { status?: string; auditStatus?: string; keyword?: string; page?: number; pageSize?: number }) =>
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
  /**
   * 把外部图片地址转存到自己的对象存储，返回本站 URL。
   * 为什么要转存：外站图可能失效 / 防盗链 / 是 http 混合内容；转存后与本地图同源同生命周期。
   */
  fromUrl: (url: string) => api.post<{ url: string }>('/api/uploads/from-url', { url }),
};

// ---------- 通知中心 / 关注与粉丝（duoduo-social；第六十七轮补齐 Web 端） ----------
export const noticeApi = {
  /** 通知列表：定向通知 + 全站公告合并视图 */
  list: (query?: { category?: string; page?: number; pageSize?: number }) =>
    api.get<NoticePageData>('/api/socials/notices', { query }),
  unreadCount: () => api.get<UnreadCount>('/api/socials/notices/unread-count'),
  /**
   * 未读汇总：**通知 + 私信合并口径**（服务端算好再下发，前端不自己相加）。
   *
   * 这是全站未读角标的**唯一数据源** —— 与小程序 `utils/unread.js` 同接口、同口径。
   * 返回同源同刻的三个计数，页面可拿 `message` 单独显示「私信 N 条」而不必再发一次请求。
   */
  unreadSummary: () => api.get<UnreadSummary>('/api/socials/notices/unread-summary'),
  /**
   * 分类未读数（2026-10-22 · Q9）：键为 ORDER / FRIEND / MERCHANT / SYSTEM / ANNOUNCEMENT。
   * 与 unreadCount 同口径分桶（排除私信/客服通知；公告按已读集合扣除）。
   */
  unreadByCategory: () =>
    api.get<Record<string, number>>('/api/socials/notices/unread-by-category'),
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
  /** 他人主页（S6-D1）：资料 + 计数 + 我与 TA 的关注关系，一次拿齐 */
  profile: (userId: string) => api.get<UserProfileView>(`/api/socials/users/${userId}/profile`),
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
    query?: { productId?: string; listingId?: string; page?: number; pageSize?: number; aroundId?: string },
  ) => api.get<MessagePageData>(`/api/socials/messages/conversations/${peerId}`, { query }),

  /** 单向删除私信（2026-10-25）：批量隐藏本人视角，对方仍可见。返回本次隐藏条数 */
  deleteMessages: (messageIds: string[]) =>
    api.post<number>('/api/socials/messages/delete', { messageIds }),

  /** 打开会话即标记已读（幂等），返回被标记的条数 */
  markRead: (peerId: string, query?: { productId?: string; listingId?: string }) =>
    api.post<number>(`/api/socials/messages/conversations/${peerId}/read`, undefined, { query }),

  /** 发消息；非互关每人限发一条（咨询会话豁免），被拒时服务端给人话原因 */
  send: (body: {
    receiverId: string;
    content: string;
    bizType?: string;
    bizId?: string;
    productId?: string;
    listingId?: string;
    mediaUrl?: string;
    thumbUrl?: string;
    width?: number;
    height?: number;
    durationMs?: number;
    /** 引用的消息 id（2026-10-24）：必须同会话 */
    replyToId?: string;
  }) => api.post<ChatMessageView>('/api/socials/messages', body),

  /** 撤回自己 2 分钟内发出的私信（2026-10-24，幂等） */
  recall: (messageId: string) =>
    api.post<ChatMessageView>(`/api/socials/messages/${messageId}/recall`),

  /** 我的私信未读总数（角标走合并口径 unread-summary；这里用于入口上的独立未读数） */
  unreadCount: () => api.get<UnreadCount>('/api/socials/messages/unread-count'),
};

// ---------- 客服（第七十八轮 · 从私信独立出来） ----------
//
// 边界：
//   · 客服消息有独立表（t_support_session / t_support_message）与独立前缀 /api/support/**，
//     **不会**出现在私信列表里 —— 这不是查询过滤，是数据层分离；
//   · 两类客服共用一个模型，靠 sellerId 区分：自营 = 官方客服账号（服务端缺省），
//     第三方 = 商品归属卖家。前端只需要在第三方商品上带 sellerId；
//   · 客服没有「互相关注才能发」的限制，普通私信有 —— 两套规则不要互相串。
export interface SupportSessionView {
  id: string;
  sellerId: string;
  sellerName: string;
  sellerAvatar: string | null;
  userId: string;
  userName: string;
  userAvatar: string | null;
  lastMessage: string;
  lastMessageAt: string;
  unread: number;
  status: 'OPEN' | 'CLOSED';
  /** C-04：当前处理人；null=未认领。买家侧恒为 null */
  assigneeId: string | null;
  assigneeName: string | null;
}

// ---------- 客服工单（C-04） ----------

export interface SupportTicketMemberView {
  userId: string;
  name: string;
  avatar: string | null;
  role: 'OWNER' | 'STAFF' | string;
}

export interface SupportTicketLogView {
  id: string;
  action: 'CLAIM' | 'TRANSFER' | 'RELEASE' | string;
  actionLabel: string;
  fromAssigneeId: string | null;
  fromAssigneeName: string | null;
  toAssigneeId: string | null;
  toAssigneeName: string | null;
  actorId: string;
  actorName: string | null;
  note: string | null;
  createdAt: string;
}

export interface SupportTicketView {
  sessionId: string;
  status: 'OPEN' | 'CLOSED';
  assigneeId: string | null;
  assigneeName: string | null;
  assignedAt: string | null;
  members: SupportTicketMemberView[];
  logs: SupportTicketLogView[];
}

export interface SupportProductCard {
  productId: string;
  title: string;
  image: string | null;
  price: number | null;
}

export interface SupportMessageView {
  id: string;
  senderId: string;
  senderRole: 'USER' | 'SELLER' | 'SYSTEM';
  content: string;
  /** TEXT / PRODUCT / IMAGE / VIDEO / EMOJI / ORDER / VOICE */
  bizType: 'TEXT' | 'PRODUCT' | 'IMAGE' | 'VIDEO' | 'EMOJI' | 'ORDER' | 'VOICE';
  bizId: string | null;
  mediaUrl: string | null;
  thumbUrl: string | null;
  width: number | null;
  height: number | null;
  durationMs: number | null;
  metaJson: string | null;
  createdAt: string;
  mine: boolean;
  read: boolean;
  card: SupportProductCard | null;
  /** 撤回时间（2026-10-24）；null=未撤回 */
  recalledAt: string | null;
  /** 引用的消息 id；null=无引用 */
  replyToId: string | null;
  /** 服务端拼装的引用摘要 */
  quoted: { id: string; bizType: string | null; excerpt: string } | null;
}

export interface SupportThreadView {
  sessionId: string;
  sellerId: string;
  sellerName: string;
  sellerAvatar: string | null;
  userId: string;
  userName: string;
  userAvatar: string | null;
  status: 'OPEN' | 'CLOSED';
  /** 对端读到的最大消息 id（已读回执判据） */
  peerLastReadId: string | null;
  /** 我方已发出、对端尚未读到的条数；0 = 全部已读 */
  myUnreadCount: number;
  items: SupportMessageView[];
  total: number;
  page: number;
  pageSize: number;
}

/**
 * 实时通道（2026-10-23）。
 *
 * 浏览器建 WebSocket 带不了 Authorization 头，所以先换一张 60 秒、一次性、无权限的票，
 * 再用 `/ws/chat?ticket=…` 建连。票据泄露也只能建一条连接，且用后即焚。
 */
export const realtimeApi = {
  ticket: () => api.post<{ ticket: string; expiresIn: number; path: string }>(
    '/api/socials/realtime/ticket',
  ),
  /** 本实例的连接规模（运维/自检用，多实例时是单实例口径） */
  online: () => api.get<{ connections: number; users: number }>('/api/socials/realtime/online'),
};

export const supportApi = {
  /** 取或建「我 ↔ 某接待方」的会话；sellerId 缺省 = 自营客服 */
  session: (sellerId?: string) =>
    api.get<SupportSessionView>('/api/support/session', { query: { sellerId } }),
  /** 我的客服会话列表（自营 + 各商家各一条） */
  sessions: () => api.get<SupportSessionView[]>('/api/support/sessions'),
  messages: (query?: { sellerId?: string; page?: number; pageSize?: number; aroundId?: string }) =>
    api.get<SupportThreadView>('/api/support/messages', { query }),

  /** 单向删除客服消息（2026-10-25）：批量隐藏本人视角，对方仍可见。返回本次隐藏条数 */
  deleteMessages: (messageIds: string[]) =>
    api.post<number>('/api/support/messages/delete', { messageIds }),
  /** 发消息；productId 非空 = 附商品卡（服务端会校验商品归属该店铺） */
  send: (body: {
    sellerId?: string;
    content: string;
    productId?: string;
    type?: 'TEXT' | 'PRODUCT' | 'IMAGE' | 'VIDEO' | 'EMOJI' | 'ORDER' | 'VOICE';
    /** type=ORDER 时必填 */
    orderNo?: string;
    mediaUrl?: string;
    thumbUrl?: string;
    width?: number;
    height?: number;
    durationMs?: number;
    /** 引用的消息 id（2026-10-24）：必须同会话 */
    replyToId?: string;
  }) => api.post<SupportMessageView>('/api/support/messages', body),
  /** 撤回自己 2 分钟内发出的消息（2026-10-24，幂等） */
  recall: (messageId: string) =>
    api.post<SupportMessageView>(`/api/support/messages/${messageId}/recall`),
  markRead: (sessionId: string) =>
    api.post<number>('/api/support/read', undefined, { query: { sessionId } }),
  /**
   * 结束本次咨询（仅买家）。
   *
   * 语义是「我这边问完了」，不是禁止再发：买家再发消息服务端会自动重开，
   * 因此端侧**不展示任何关闭状态**（服务端也把 status 抹成 null 了）。
   */
  close: (sessionId: string) =>
    api.post<void>('/api/support/close', undefined, { query: { sessionId } }),
  unreadCount: () => api.get<number>('/api/support/unread-count'),
};

/**
 * 商家工作台的客服接待接口。
 *
 * sellerId **不由前端传**：服务端从登录态推导，避免「换个 id 看别家会话」。
 * 自营客服账号以 SELLER 身份登录时，这里就是它的接待台 —— 平台运营端不参与私信。
 */
export const sellerSupportApi = {
  /**
   * 会话列表。
   *
   * filter（2026-10-23 扩展）：ALL / UNREAD / ANSWERED（已处理=没有待回）/ CLOSED。
   * `onlyUnread` 保留给老调用点，等价于 filter=UNREAD。
   */
  sessions: (query?: {
    status?: string;
    filter?: 'ALL' | 'UNREAD' | 'ANSWERED' | 'CLOSED';
    onlyUnread?: boolean;
    page?: number;
    pageSize?: number;
  }) => api.get<PageResult<SupportSessionView>>('/api/seller/support/sessions', { query }),
  messages: (id: string, query?: { page?: number; pageSize?: number; aroundId?: string }) =>
    api.get<SupportThreadView>(`/api/seller/support/sessions/${id}/messages`, { query }),
  /** 单向删除客服消息（2026-10-25，商家侧）：批量隐藏本人视角，用户仍可见 */
  deleteMessages: (id: string, messageIds: string[]) =>
    api.post<number>(`/api/seller/support/sessions/${id}/messages/delete`, { messageIds }),
  reply: (id: string, payload: {
    content: string;
    type?: 'TEXT' | 'IMAGE' | 'VIDEO' | 'EMOJI' | 'VOICE';
    mediaUrl?: string;
    thumbUrl?: string;
    width?: number;
    height?: number;
    durationMs?: number;
    /** 引用的消息 id（2026-10-24）：必须同会话 */
    replyToId?: string;
  }) => api.post<SupportMessageView>(`/api/seller/support/sessions/${id}/messages`, payload),
  markRead: (id: string) => api.post<number>(`/api/seller/support/sessions/${id}/read`),
  /** 撤回客服 2 分钟内发出的回复（2026-10-24，幂等） */
  recall: (id: string, messageId: string) =>
    api.post<SupportMessageView>(`/api/seller/support/sessions/${id}/messages/${messageId}/recall`),
  // 关闭 / 重开已下线（2026-10-23）：关闭权只在买家手里，客服侧调用恒返回 403
  unreadCount: () => api.get<number>('/api/seller/support/unread-count'),

  // ---- C-04 · 客服工单认领 / 转交 / 审计 ----

  /** 工单详情：当前处理人 + 本店在职成员 + 处理记录 */
  ticket: (id: string) => api.get<SupportTicketView>(`/api/seller/support/sessions/${id}/ticket`),

  /** 认领：并发只有一个成功；已是本人时幂等 */
  claimTicket: (id: string) =>
    api.post<SupportTicketView>(`/api/seller/support/sessions/${id}/ticket/claim`),

  /** 转交：目标必须是本店在职成员；备注可选 */
  transferTicket: (id: string, body: { toUserId: string; note?: string }) =>
    api.post<SupportTicketView>(`/api/seller/support/sessions/${id}/ticket/transfer`, body),

  /** 释放：归还为未认领 */
  releaseTicket: (id: string) =>
    api.post<SupportTicketView>(`/api/seller/support/sessions/${id}/ticket/release`),
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

// ---------- 地理（/api/geo，服务端代理地图服务商，Key 不下发） ----------

/**
 * 逆地理编码：把浏览器/小程序拿到的经纬度换成省市区 + 详细地址。
 *
 * ★ 必须走后端：地图服务商的 Key 只配在服务端环境变量里。
 * ★ 服务端未配置 Key 时不报错，而是返回 `configured:false` 的空结果 ——
 *   调用方据此提示「可手动选择」，不阻断填地址。
 */
export const geoApi = {
  /** 网页地图组件的 Key 与安全密钥（登录态下发，不进前端产物） */
  jsapiConfig: () => api.get<JsApiConfig>('/api/geo/jsapi-config'),
  /**
   * 逆地理编码（含周边候选）。
   *
   * ★ `coord` 必须如实声明：
   *   · 网页端 `navigator.geolocation` → **wgs84**（服务端转 GCJ-02 再查高德）；
   *   · 小程序 `wx.getLocation({type:'gcj02'})` → **gcj02**（再转就会二次偏移）。
   * 声明错的后果是地址落到 500 米开外，而不是报错 —— 所以这里是必填参数。
   */
  regeo: (lng: number, lat: number, coord: 'gcj02' | 'wgs84') =>
    api.get<RegeoView>('/api/geo/regeo', { query: { lng, lat, coord } }),
};


// ---------- 收藏夹 / 足迹 / 已购（C 批） ----------
export interface PickerProduct {
  id: string;
  title: string;
  image: string | null;
  price: number | null;
  status?: string | null;
  /** 仅足迹接口下发（UTC ISO，如 2026-10-22T05:02:00Z）；收藏/已购买为 null */
  viewedAt?: string | null;
}

export const favoriteApi = {
  /** 切换收藏，返回 { favorited } */
  toggle: (spuId: string) => api.post<{ favorited: boolean }>(`/api/favorites/${spuId}`),
  /** 批量收藏状态（详情页回填按钮） */
  status: (spuIds: string[]) =>
    api.get<string[]>('/api/favorites/status', { query: { spuIds: spuIds.join(',') } }),
  list: (query?: { page?: number; pageSize?: number }) =>
    api.get<PageResult<PickerProduct>>('/api/favorites/products', { query }),
};

export const historyApi = {
  list: (query?: { page?: number; pageSize?: number }) =>
    api.get<PageResult<PickerProduct>>('/api/history/products', { query }),
  clear: () => api.del<number>('/api/history'),
  /** 删除单条足迹（幂等） */
  remove: (spuId: string) => api.del<number>(`/api/history/${spuId}`),
  /** 批量删除足迹（服务端只删本人数据） */
  removeBatch: (spuIds: string[]) =>
    api.del<number>(`/api/history/batch?ids=${spuIds.join(',')}`),
};

export const purchasedApi = {
  list: (query?: { page?: number; pageSize?: number }) =>
    api.get<PageResult<PickerProduct>>('/api/orders/purchased-products', { query }),
};
