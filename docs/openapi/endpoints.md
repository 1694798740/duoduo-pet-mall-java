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
- `GET /api/admin/audit-logs`
- `GET /api/admin/merchants`
- `GET /api/admin/merchants/{id}`
- `POST /api/admin/merchants/{id}/approve`
- `POST /api/admin/merchants/{id}/reject`
- `POST /api/admin/merchants/{id}/start-review`
- `GET /api/admin/merchants/audit-sla/metrics`
- `GET /api/admin/merchants/audit-sla/samples`
- `POST /api/admin/merchants/audit-sla/samples/{id}/review`
- `POST /api/admin/merchants/audit-sla/sweep`
- `POST /api/admin/merchants/license-sweep`
- `GET /api/admin/merchant-staff`
- `POST /api/admin/merchant-staff/{memberId}/disable`
- `POST /api/admin/merchant-staff/{memberId}/enable`
- `GET /api/admin/users`
- `POST /api/admin/users/{id}/2fa/reset`
- `POST /api/admin/users/{id}/role`
- `POST /api/auth/2fa/disable`
- `POST /api/auth/2fa/enable`
- `POST /api/auth/2fa/setup`
- `GET /api/auth/2fa/status`
- `POST /api/auth/login`
- `POST /api/auth/login/token`
- `POST /api/auth/logout`
- `POST /api/auth/refresh`
- `POST /api/auth/refresh/token`
- `POST /api/auth/register`
- `POST /api/auth/wechat`
- `POST /api/auth/wechat/bind`
- `POST /api/auth/wechat/rebind`
- `POST /api/auth/wechat/token`
- `POST /api/auth/wechat/unbind`
- `GET /api/merchant-materials/{merchantId}/{storedName}`
- `GET /api/pets`
- `POST /api/pets`
- `DELETE /api/pets/{petId}`
- `PUT /api/pets/{petId}`
- `POST /api/pets/{petId}/default`
- `GET /api/seller/staff`
- `POST /api/seller/staff`
- `POST /api/seller/staff/{memberId}/disable`
- `POST /api/seller/staff/{memberId}/enable`
- `POST /api/users/internal/{userId}/credit`
- `POST /api/users/internal/audit-logs`
- `GET /api/users/internal/briefs`
- `GET /api/users/internal/merchant-license`
- `GET /api/users/internal/merchant-membership`
- `GET /api/users/internal/merchant-risk`
- `POST /api/users/internal/merchant-violation`
- `GET /api/users/internal/pet-profiles`
- `GET /api/users/internal/roles`
- `GET /api/users/me`
- `PATCH /api/users/me`
- `GET /api/users/me/merchant-application`
- `PUT /api/users/me/merchant-application`
- `POST /api/users/me/merchant-application/activate`
- `POST /api/users/me/merchant-application/materials`
- `POST /api/users/me/merchant-application/renewal`
- `POST /api/users/me/merchant-application/submit`
- `POST /api/users/me/realname`

小计：**67** 个端点。

## product （:8082）

- `GET /api/admin/audit-sla/metrics`
- `GET /api/admin/audit-sla/samples`
- `POST /api/admin/audit-sla/samples/{id}/review`
- `POST /api/admin/audit-sla/sweep`
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
- `GET /api/admin/products`
- `GET /api/admin/products/{spuId}`
- `POST /api/admin/products/{spuId}/audit`
- `POST /api/admin/products/{spuId}/proxy-off-shelf`
- `GET /api/admin/products/proxy-work-orders`
- `GET /api/admin/search/reconcile`
- `POST /api/admin/search/reindex`
- `GET /api/categories`
- `GET /api/categories/{id}`
- `GET /api/categories/flat`
- `GET /api/home`
- `POST /api/listings`
- `GET /api/listings/{id}`
- `POST /api/listings/{id}/delete`
- `POST /api/listings/{id}/edit`
- `POST /api/listings/{id}/resubmit`
- `POST /api/listings/{id}/status`
- `POST /api/listings/internal/{id}/want`
- `GET /api/listings/internal/cards`
- `GET /api/listings/mine/{id}`
- `GET /api/listings/mine/list`
- `GET /api/listings/search`
- `GET /api/meta/brands`
- `GET /api/meta/dictionaries`
- `GET /api/products`
- `GET /api/products/{spuId}`
- `GET /api/products/internal/candidates`
- `GET /api/products/internal/cards`
- `GET /api/products/internal/categories`
- `POST /api/products/internal/ratings/recompute`
- `POST /api/products/internal/seller/{sellerId}/off-shelf`
- `GET /api/products/internal/skus/batch`
- `GET /api/products/internal/skus/frozen-holders`
- `GET /api/products/search/suggestion`
- `POST /api/products/skus/stock/deduct`
- `POST /api/products/skus/stock/freeze`
- `POST /api/products/skus/stock/release`
- `POST /api/products/skus/stock/restock`
- `GET /api/seller/products`
- `POST /api/seller/products`
- `GET /api/seller/products/{spuId}`
- `PUT /api/seller/products/{spuId}`
- `POST /api/seller/products/{spuId}/resubmit`
- `POST /api/seller/products/{spuId}/status`
- `GET /api/seller/products/proxy-requests`
- `POST /api/seller/products/proxy-requests`
- `POST /api/seller/products/proxy-requests/{workOrderNo}/void`

小计：**63** 个端点。

## trade （:8083）

- `GET /api/admin/mq/dlq`
- `POST /api/admin/mq/dlq/replay`
- `GET /api/admin/orders`
- `GET /api/admin/orders/{orderNo}`
- `POST /api/admin/orders/{orderNo}/force-close`
- `POST /api/admin/orders/{orderNo}/proxy-aftersale`
- `POST /api/admin/orders/{orderNo}/proxy-ship`
- `GET /api/admin/orders/proxy-work-orders`
- `GET /api/admin/recon/rules`
- `GET /api/admin/recon/runs`
- `POST /api/admin/recon/runs`
- `GET /api/admin/recon/runs/{runId}/diffs`
- `GET /api/admin/reports/charts`
- `GET /api/admin/reports/overview`
- `GET /api/admin/seckill/activities`
- `POST /api/admin/seckill/activities`
- `DELETE /api/admin/seckill/activities/{id}`
- `PUT /api/admin/seckill/activities/{id}`
- `POST /api/admin/seckill/activities/{id}/status`
- `GET /api/admin/settlements/abnormal`
- `POST /api/admin/settlements/backfill`
- `GET /api/admin/settlements/bills`
- `POST /api/admin/settlements/compensate`
- `GET /api/admin/settlements/debts`
- `POST /api/admin/settlements/debts/{id}/settle`
- `POST /api/admin/settlements/debts/{id}/write-off`
- `GET /api/admin/settlements/summary`
- `GET /api/admin/settlements/wallets`
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
- `GET /api/orders/internal/{orderNo}/fact`
- `POST /api/orders/internal/{orderNo}/refund`
- `GET /api/orders/internal/seller/{sellerId}/stats`
- `POST /api/orders/internal/sweep`
- `GET /api/orders/placement/{idempotentKey}`
- `POST /api/orders/preview`
- `POST /api/orders/queued`
- `GET /api/payments/{payNo}`
- `POST /api/payments/callback/{channel}`
- `POST /api/payments/sandbox/{payNo}`
- `GET /api/seckill/activities`
- `GET /api/seckill/activities/{id}`
- `POST /api/seckill/orders`
- `GET /api/seller/analytics`
- `GET /api/seller/orders`
- `GET /api/seller/orders/{orderNo}`
- `POST /api/seller/orders/{orderNo}/refund`
- `POST /api/seller/orders/{orderNo}/ship`
- `GET /api/seller/proxy-requests`
- `POST /api/seller/proxy-requests`
- `POST /api/seller/proxy-requests/{workOrderNo}/void`
- `GET /api/seller/settlements`
- `GET /api/seller/settlements/bills`
- `GET /api/seller/settlements/wallet`
- `GET /api/seller/summary`
- `GET /sandbox/pay/{payNo}`

小计：**69** 个端点。

## fulfilment （:8084）

- `GET /api/admin/aftersales`
- `POST /api/admin/aftersales/{id}/arbitrate`
- `GET /api/admin/share-reports`
- `POST /api/admin/share-reports/{id}/handle`
- `POST /api/aftersales`
- `GET /api/aftersales/{id}`
- `POST /api/aftersales/{id}/escalate`
- `POST /api/aftersales/{id}/reply`
- `POST /api/aftersales/{id}/return-receipt`
- `POST /api/aftersales/{id}/return-shipment`
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
- `POST /api/shares`
- `GET /api/shares/{id}`
- `POST /api/shares/{id}/comments`
- `POST /api/shares/{id}/delete`
- `POST /api/shares/{id}/edit`
- `POST /api/shares/{id}/favorite`
- `POST /api/shares/{id}/like`
- `POST /api/shares/{id}/report`
- `POST /api/shares/{id}/repost`
- `GET /api/shares/comments`
- `POST /api/shares/comments/{commentId}/like`
- `POST /api/shares/comments/{commentId}/report`
- `GET /api/shares/feed`
- `GET /api/shares/mine/list`
- `GET /api/shares/topics`
- `GET /api/shares/topics/{name}`
- `POST /api/uploads`
- `GET /uploads/{filename}`

小计：**41** 个端点。

## marketing （:8085）

- `POST /api/coupons/admin/templates`
- `POST /api/coupons/internal/consume`
- `POST /api/coupons/internal/lock`
- `POST /api/coupons/internal/release`
- `POST /api/coupons/internal/sweep`
- `POST /api/coupons/internal/trial`
- `GET /api/coupons/mine`
- `GET /api/coupons/mine/usable`
- `GET /api/coupons/templates`
- `POST /api/coupons/templates/{templateId}/claim`

小计：**10** 个端点。

## ai （:8086）

- `GET /api/duoduo/capabilities`
- `POST /api/duoduo/chat`

小计：**2** 个端点。

合计：**252** 个端点（6 个业务服务）。
