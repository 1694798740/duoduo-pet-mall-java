# 端点清单（由 scripts/export-openapi.ps1 从 /v3/api-docs 自动生成，勿手改）

> 比对基准：`docs/agent/04-接口契约全集.md`。导出时间见各 json 的 mtime。

## user （:8081）

- `GET /api/addresses`
- `POST /api/addresses`
- `DELETE /api/addresses/{addressId}`
- `PUT /api/addresses/{addressId}`
- `POST /api/addresses/{addressId}/default`
- `GET /api/addresses/default`
- `GET /api/addresses/internal/snapshot`
- `GET /api/admin/users`
- `POST /api/admin/users/{id}/role`
- `POST /api/auth/login`
- `POST /api/auth/logout`
- `POST /api/auth/refresh`
- `POST /api/auth/register`
- `POST /api/auth/wechat`
- `POST /api/auth/wechat/bind`
- `GET /api/pets`
- `POST /api/pets`
- `DELETE /api/pets/{petId}`
- `PUT /api/pets/{petId}`
- `POST /api/pets/{petId}/default`
- `POST /api/users/internal/{userId}/credit`
- `GET /api/users/internal/briefs`
- `GET /api/users/me`
- `PATCH /api/users/me`
- `POST /api/users/me/merchant`
- `POST /api/users/me/realname`

小计：**26** 个端点。

## product （:8082）

- `GET /api/admin/brands`
- `POST /api/admin/brands`
- `DELETE /api/admin/brands/{id}`
- `PUT /api/admin/brands/{id}`
- `PUT /api/admin/brands/{id}/status`
- `GET /api/admin/categories`
- `POST /api/admin/categories`
- `DELETE /api/admin/categories/{id}`
- `PUT /api/admin/categories/{id}`
- `PUT /api/admin/categories/{id}/status`
- `GET /api/admin/listings`
- `POST /api/admin/listings/{id}/audit`
- `GET /api/admin/search/reconcile`
- `POST /api/admin/search/reindex`
- `GET /api/categories`
- `GET /api/categories/{id}`
- `GET /api/categories/flat`
- `GET /api/home`
- `GET /api/listings`
- `GET /api/listings/{id}`
- `GET /api/meta/brands`
- `GET /api/meta/dictionaries`
- `GET /api/products`
- `GET /api/products/{spuId}`
- `GET /api/products/internal/candidates`
- `GET /api/products/internal/skus/batch`
- `POST /api/products/skus/stock/deduct`
- `POST /api/products/skus/stock/freeze`
- `POST /api/products/skus/stock/release`
- `POST /api/products/skus/stock/restock`
- `GET /api/seller/products`
- `POST /api/seller/products/{spuId}/status`

小计：**32** 个端点。

## trade （:8083）

- `GET /api/admin/orders`
- `GET /api/admin/orders/{orderNo}`
- `POST /api/admin/orders/{orderNo}/force-close`
- `GET /api/admin/reports/overview`
- `GET /api/admin/settlements/abnormal`
- `POST /api/admin/settlements/backfill`
- `GET /api/admin/settlements/summary`
- `GET /api/cart`
- `GET /api/cart/count`
- `POST /api/cart/items`
- `DELETE /api/cart/items/{skuId}`
- `PATCH /api/cart/items/{skuId}`
- `PATCH /api/cart/items/{skuId}/selection`
- `DELETE /api/cart/selected`
- `GET /api/orders`
- `POST /api/orders`
- `GET /api/orders/{orderNo}`
- `POST /api/orders/{orderNo}/cancel`
- `POST /api/orders/{orderNo}/confirm`
- `POST /api/orders/{orderNo}/pay`
- `POST /api/orders/{orderNo}/refund`
- `GET /api/orders/{orderNo}/settlement`
- `POST /api/orders/{orderNo}/ship`
- `GET /api/orders/internal/{orderNo}/fact`
- `POST /api/orders/internal/{orderNo}/refund`
- `GET /api/orders/internal/seller/{sellerId}/stats`
- `POST /api/orders/internal/sweep`
- `GET /api/payments/{payNo}`
- `POST /api/payments/callback/{channel}`
- `POST /api/payments/sandbox/{payNo}`
- `GET /api/seller/orders`
- `GET /api/seller/orders/{orderNo}`
- `POST /api/seller/orders/{orderNo}/refund`
- `POST /api/seller/orders/{orderNo}/ship`
- `GET /api/seller/settlements`
- `GET /api/seller/summary`
- `GET /sandbox/pay/{payNo}`

小计：**37** 个端点。

## fulfilment （:8084）

- `GET /api/admin/aftersales`
- `POST /api/admin/aftersales/{id}/arbitrate`
- `POST /api/aftersales`
- `GET /api/aftersales/{id}`
- `POST /api/aftersales/{id}/escalate`
- `POST /api/aftersales/{id}/reply`
- `GET /api/aftersales/mine`
- `GET /api/aftersales/seller`
- `POST /api/logistics/internal/{orderNo}/tracks`
- `GET /api/logistics/orders/{orderNo}/tracks`
- `POST /api/reviews`
- `POST /api/reviews/{id}/append`
- `POST /api/reviews/internal/credits/refresh`
- `GET /api/reviews/mine`
- `GET /api/reviews/order/{orderNo}`
- `GET /api/reviews/product/{spuId}`
- `GET /api/reviews/product/{spuId}/summary`
- `GET /api/reviews/reviewed`
- `GET /api/reviews/seller/{sellerId}/credit`

小计：**19** 个端点。

## marketing （:8085）

- `POST /api/coupons/admin/templates`
- `POST /api/coupons/internal/consume`
- `POST /api/coupons/internal/lock`
- `POST /api/coupons/internal/release`
- `POST /api/coupons/internal/sweep`
- `GET /api/coupons/mine`
- `GET /api/coupons/mine/usable`
- `GET /api/coupons/templates`
- `POST /api/coupons/templates/{templateId}/claim`

小计：**9** 个端点。

## ai （:8086）

- `GET /api/duoduo/capabilities`
- `POST /api/duoduo/chat`

小计：**2** 个端点。

合计：**125** 个端点（6 个业务服务）。
