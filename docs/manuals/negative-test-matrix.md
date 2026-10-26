# 反向验证覆盖矩阵（J-05）

> 目的：每条关键风控 / 资金 / 权限规则，至少有一个「不该通过时必须失败」的自动化断言。
> 正向用例证明功能可用；反向用例证明**门禁真的存在**。
> 最后更新：2026-10-26

| 规则 | 反向断言 | 覆盖位置 |
|---|---|---|
| 商品编辑并发（乐观锁） | 旧版本提交 → 409，且不写库 | `SellerProductServiceTest`（A-07）、`product-manage-r93-smoke` |
| 审核驳回后改两次绕过 | 被驳回后再编辑仍为 PENDING（`rejected_at` 持久化） | `spu-audit-history-a08-smoke` |
| 审核中重复提交 | PENDING 商品再次提交 → 422 | `spu-audit-history-a08-smoke` |
| 第三方商品未过审上架 | 未 APPROVED 上架 → 422 | `product-manage-r93-smoke` |
| 跨店改商品 | 店员 / 店主操作别家商品 → 404/403 | `SellerProductServiceTest`（E14）、A-17 冒烟 |
| 平台自营商品被第三方改 | 第三方操作 `seller_id=0` → 403 | `SellerProductServiceTest` |
| 批量上下架超限 | >100 条 → 400；单条失败不回滚已成功项 | `SellerProductServiceTest`（A-13）、A-13 冒烟 |
| CSV 文件超限 | >2MB / >500 行 / >100 组 → 拒绝 | `ProductCsvTest`、`SellerProductCsvServiceTest`（A-17） |
| CSV 公式注入 | `= + - @` 开头文本被转义 | `ProductCsvTest` |
| SKU 删除有订单 | 已有订单 / 冻结库存的规格删除 → 拒绝 | `SellerProductServiceTest`（A-14）、A-14 冒烟 |
| 分类属性必填 | 缺失必填属性 → 拒绝发布 | `CategoryAttrServiceTest`（A-10） |
| 商品图片注入 | 富文本脚本 / 危险标签 → 被消毒剥离 | `ProductContent` 相关单测、A-04 冒烟 |
| 未登录 / 买家访问商家端 | 401 / 403 | 各 seller 冒烟 |
| F-04 店员越权 | 只有发货权时改价 / 上下架 / 新建 / 退款 / 客服回复 → 403 | `staff-permissions-f04-smoke`、`SellerProductServiceTest` |
| F-04 店员自改权限 | 店员调用权限配置 → 403 | `staff-permissions-f04-smoke` |
| F-04 未知权限键 | 拼错键 → 400，不写库 | `MerchantStaffServiceTest`、F-04 冒烟 |
| 店员停用即时生效 | 停用后立即访问商家端 → 403 | E13 `merchant-staff-ui-check` |
| 2FA 未带码 / 错码 | 启用后无码 → `TOTP_REQUIRED`；错码 → `TOTP_INVALID` | `admin-2fa-smoke.ps1`、`merchant-2fa-f05-ui-check` |
| 2FA 重放 | 同一时间步的码不能二次使用 | `TwoFactorService` 条件更新 + `admin-2fa-smoke` |
| 库存超卖 | 库存不足下单 → 拒绝 / 部分失败 | `StockServiceTest`、`order-smoke` |
| 库存冻结重复释放 | 已释放的预占再次释放 → 幂等 / 拒绝 | `stock-order-reservation-smoke` |
| 支付幂等 | 同一幂等键重复支付 → 不重复入账 | `payment-smoke`、`order-smoke` |
| 优惠券重复核销 | 已核销券再次使用 → 拒绝 | `CouponAllocationTest`、`coupon-smoke` |
| 优惠券跨用户 | 使用他人券 → 拒绝 | `coupon-smoke` |
| 重复收款 | 重复支付被检出，人工退款（不自动退） | `payment-smoke` 对账 |
| 卖家跨店发货 / 退款 | 操作别家订单 → 404/403 | trade seller 冒烟、D-05 冒烟 |
| 售后退款权限 | 无 ORDER_REFUND 的店员退款 → 403 | F-04 冒烟 |
| 评价重复回复 | 同一评价第二次回复 → 409 | A-16 冒烟 |
| 未过审评价回复 | PENDING / REJECTED 评价回复 → 422 | A-16 冒烟 |
| 评价违规内容 | 联系方式 / 站外链接 → 转 PENDING 或拒绝 | A-15 冒烟 |
| 客服并发认领 | 两人同时认领 → 一个 200、一个 409 | C-04 冒烟 |
| 客服转交非本店成员 | 转交给外部账号 → 400 | C-04 冒烟 |
| 客服回复无权限 | 无 SUPPORT_REPLY 的店员回复 → 403 | F-04 冒烟 |
| 聊天敏感词 | 链接 / 诈骗 / 敏感词 → 422 且零落库 | C-05 冒烟、`ChatRiskJudgeTest` |
| 聊天联系方式 | 默认仅告警；开启拦截后 → 422 | C-05 冒烟 |
| 分享删除后可见 | 软删后买家「我的分享」不可见 | `share-circle-ui-check` |
| 举报处理 | 处理需原因；成立即下架且不可撤销 | H-02 / 举报冒烟 |
| 后台权限 | 非运营访问 `/api/admin/**` → 403 | 各 admin 冒烟 |
| 审计不可删改 | 资金 / 安全类审计无删除入口 | `AuditActionTest` / `AuditCategoryTest` |
| 变更历史权限 | 卖家访问跨商品历史 → 403；未登录 401 | H-03 冒烟 |
| 登出 / 令牌吊销 | 登出后旧令牌不可用 | `auth-isolation-smoke` |
| 账号注销 | 有未终态订单 / 售后 / 商家身份 → 拒绝注销 | 账号注销冒烟 |

## 结论

- 上表覆盖商品、交易、支付、优惠券、售后、评价、客服、权限、审计、检索等关键规则。
- 新增风控规则时，必须同时补一条反向断言；只有正向用例的规则视为**未验收**。
- J-04（小程序自动化）与真机相关项不在本表范围，见 G-03 / C-03 / J-04。
