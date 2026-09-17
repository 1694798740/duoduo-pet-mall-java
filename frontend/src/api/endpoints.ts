/**
 * 接口清单 —— 与 Java 微服务网关的实际路由逐条对齐。
 *
 * 网关路由（duoduo-gateway/application.yml）：
 *   /api/auth/**, /api/users/**, /api/pets/**      → duoduo-user   :8081
 *   /api/categories/**, /api/products/**, /api/listings/**,
 *   /api/home/**, /api/meta/**                     → duoduo-product:8082
 *   /api/cart/**, /api/orders/**, /api/payments/**,
 *   /api/seller/**, /sandbox/pay/**                → duoduo-trade  :8083
 *
 * 注意：duoduo-fulfilment / marketing / ai / admin 尚未部署，
 * 因此评价、优惠券、运营后台的接口在本期不存在，前端不实现对应页面。
 * 卖家中心（/api/seller/**）落在 trade 服务，不依赖 fulfilment。
 */

import { api } from './client';
import type {
  BrandView,
  CartItemView,
  CartView,
  CategoryNode,
  DictItem,
  HomeView,
  ListingCard,
  ListingDetail,
  OrderView,
  PageResult,
  PayResult,
  PetUpsertRequest,
  PetView,
  PlaceOrderRequest,
  PlaceOrderResult,
  ProductCard,
  ProductDetail,
  RefundResult,
  SellerSummary,
  SettlementPage,
  SettlementView,
  ShipRequest,
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
