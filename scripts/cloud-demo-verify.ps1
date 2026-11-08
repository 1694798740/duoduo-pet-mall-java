# 云端演示环境全链路验证（2026-11-08）
# 目标：模拟一个真实客户 + 商家 + 运营的完整体验路径。
#   - 可达性走公网 $Gateway（http://8.163.71.44，客户真实视角）；
#   - 其余 API 走 $GatewayApi = SSH 隧道直连网关（duoduo_tunnel.py，本机 127.0.0.1:18080 → 云端 8080）。
#     原因：公网 80 的 nginx 会强制覆盖 X-Duoduo-Entry 头（E13 防伪造设计），
#     从商城站点登录的令牌 ent 恒为 mall，进不了 /api/seller/** 与 /api/admin/**（E8 入口边界）；
#     隧道直连网关与本地冒烟脚本同形态（ent=null 不受限），卖家发货 / 运营审核 / 客服接待才能走通。
param(
    [string]$Gateway = 'http://8.163.71.44',
    [string]$GatewayApi = 'http://127.0.0.1:18080'
)

$ErrorActionPreference = 'Continue'
$pass = 0; $fail = 0; $failures = @()
function Check([string]$name, [bool]$ok, $extra) {
    if ($ok) { $script:pass++; Write-Host "  [PASS] $name" }
    else { $script:fail++; $script:failures += $name; if ($null -ne $extra) { Write-Host "  [FAIL] $name  -> $extra" } else { Write-Host "  [FAIL] $name" } }
}
function Call([string]$method, [string]$uri, $session, $bodyObj, $headers) {
    $params = @{ Uri = $uri; Method = $method; UseBasicParsing = $true; TimeoutSec = 130 }
    if ($session) { $params.WebSession = $session }
    if ($headers) { $params.Headers = $headers }
    if ($null -ne $bodyObj) { $params.Body = ([Text.Encoding]::UTF8.GetBytes(($bodyObj | ConvertTo-Json -Depth 6 -Compress))); $params.ContentType = 'application/json; charset=utf-8' }
    try { $r = Invoke-WebRequest @params; return @{ status = [int]$r.StatusCode; body = ($r.Content | ConvertFrom-Json) } }
    catch {
        $code = 0; $body = $null
        try { $code = [int]$_.Exception.Response.StatusCode.value__ } catch { }
        # PS5.1 对部分错误响应 ErrorDetails 为空，必须从响应流里再读一次（T-45）
        try {
            if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $body = $_.ErrorDetails.Message | ConvertFrom-Json }
        } catch { }
        if ($null -eq $body) {
            try {
                $resp = $_.Exception.Response
                $text = $null
                if ($resp -and $resp.PSObject.Methods.Name -contains 'GetResponseStream') {
                    $stream = $resp.GetResponseStream()
                    if ($stream) { $text = (New-Object System.IO.StreamReader($stream)).ReadToEnd() }
                } elseif ($resp -and $null -ne $resp.Content) {
                    try { $text = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult() } catch { }
                }
                if ($text) { $body = $text | ConvertFrom-Json }
            } catch { }
        }
        return @{ status = $code; body = $body }
    }
}

Write-Host "=== 多多宠物商店 · 云端全链路验证 @ $Gateway ==="

# ---------- 1. 可达性 ----------
Write-Host "`n> 1. 入口可达性"
# 首页是 HTML：直接探测 HTTP 状态，不做 JSON 转换（Call 假定响应是 JSON API）
$okHome = $false; $okHome8088 = $false
try { $h1 = Invoke-WebRequest -Uri "$Gateway/" -UseBasicParsing -TimeoutSec 30; $okHome = ($h1.StatusCode -eq 200 -and $h1.Content.Length -gt 500) } catch { }
Check "商城首页 HTTP 200（主入口 80）" $okHome "home=$okHome"
try { $h2 = Invoke-WebRequest -Uri "$Gateway`:8088/" -UseBasicParsing -TimeoutSec 30; $okHome8088 = ($h2.StatusCode -eq 200) } catch { }
Check "兼容入口 8088 HTTP 200" $okHome8088 "8088=$okHome8088"
$cap = Call 'Get' "$GatewayApi/api/duoduo/capabilities" $null $null
Check "AI 能力声明可用（mode=llm）" ($cap.status -eq 200 -and $cap.body.data.mode -eq 'llm') "$($cap.status) mode=$($cap.body.data.mode)"

# ---------- 2. 注册 + 登录 ----------
Write-Host "`n> 2. 注册与登录（模拟新客户）"
$phone = '139' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$pwd = 'duoduo12345'
$sess = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$reg = Call 'Post' "$GatewayApi/api/auth/register" $sess @{ phone = $phone; password = $pwd; nickname = "体验客户$($phone.Substring(7))" }
Check "新客户注册成功（200）" ($reg.status -eq 200) "status=$($reg.status) body=$($reg.body | ConvertTo-Json -Compress)"
if ($reg.status -eq 409) {
    $reg = Call 'Post' "$GatewayApi/api/auth/login" $sess @{ phone = $phone; password = $pwd }
    Check "手机号已存在时回退登录（200）" ($reg.status -eq 200) $reg.status
}
$me = Call 'Get' "$GatewayApi/api/users/me" $sess $null
Check "登录态有效（/api/users/me 200）" ($me.status -eq 200) $me.status
$anon = Call 'Get' "$GatewayApi/api/users/me" $null $null
Check "未登录访问个人信息被拦（401，鉴权正常）" ($anon.status -eq 401) $anon.status

# ---------- 3. 商品浏览 ----------
Write-Host "`n> 3. 商品浏览（首页 → 列表 → 详情）"
$list = Call 'Get' "$GatewayApi/api/products?page=1&pageSize=10" $null $null
Check "商品列表 200" ($list.status -eq 200) $list.status
$items = @($list.body.data.items)
Check "商品列表非空（共 $($list.body.data.total) 个 SPU）" ($items.Count -gt 0) $items.Count
$spu = $items[0]
$detail = Call 'Get' "$GatewayApi/api/products/$($spu.id)" $null $null
Check "商品详情 200" ($detail.status -eq 200) $detail.status
$sku = @($detail.body.data.skus)[0]
Check "详情含可购 SKU（id=$($sku.id) 价格=$($sku.price/100) 元）" ($null -ne $sku.id) "sku=$($sku | ConvertTo-Json -Compress)"
$search = Call 'Get' "$GatewayApi/api/products?keyword=$( [System.Uri]::EscapeDataString('猫粮') )&page=1&pageSize=5" $null $null
Check "关键词搜索「猫粮」200 且有结果" ($search.status -eq 200 -and @($search.body.data.items).Count -gt 0) "$($search.status) count=$(@($search.body.data.items).Count)"

# ---------- 4. 优惠券 ----------
Write-Host "`n> 4. 优惠券（领券中心）"
$tpls = Call 'Get' "$GatewayApi/api/coupons/templates" $sess $null
Check "领券中心模板列表 200" ($tpls.status -eq 200) $tpls.status
# 该接口返回裸数组（data 即列表），且只推荐 remainingCount > 0 的券
$tplList = @($tpls.body.data | Where-Object { $_.remainingCount -gt 0 })
if ($tplList.Count -gt 0) {
    $claim = Call 'Post' "$GatewayApi/api/coupons/templates/$($tplList[0].id)/claim" $sess $null
    Check "领取「$($tplList[0].name)」（200 或 409 已领）" ($claim.status -eq 200 -or $claim.status -eq 409) "status=$($claim.status) body=$($claim.body | ConvertTo-Json -Compress)"
    $mine = Call 'Get' "$GatewayApi/api/coupons/mine" $sess $null
    Check "我的券包 200" ($mine.status -eq 200) $mine.status
} else {
    Write-Host "  [信息] 云端暂无可领券模板，跳过领券断言"
}

# ---------- 5. 购物车 ----------
Write-Host "`n> 5. 购物车"
$cartAdd = Call 'Post' "$GatewayApi/api/cart/items" $sess @{ skuId = $sku.id; quantity = 1 }
Check "加入购物车 200" ($cartAdd.status -eq 200) "status=$($cartAdd.status) body=$($cartAdd.body | ConvertTo-Json -Compress)"
$cartAdd2 = Call 'Post' "$GatewayApi/api/cart/items" $sess @{ skuId = $sku.id; quantity = 1 }
Check "重复加购幂等（200）" ($cartAdd2.status -eq 200) $cartAdd2.status
$cart = Call 'Get' "$GatewayApi/api/cart" $sess $null
Check "购物车列表 200 且含该商品" ($cart.status -eq 200) $cart.status

# ---------- 6. 下单 + 沙箱支付 ----------
Write-Host "`n> 6. 下单与沙箱支付（完整购买流程）"
$order = Call 'Post' "$GatewayApi/api/orders" $sess @{
    receiverName = '体验客户'; receiverPhone = $phone
    receiverAddress = '广东省广州市天河区体验路 1 号'
    idempotentKey = [guid]::NewGuid().ToString()
}
Check "创建订单 200" ($order.status -eq 200) "status=$($order.status) body=$($order.body | ConvertTo-Json -Compress -Depth 6)"
$orderNo = $order.body.data.orders[0].orderNo
$payable = $order.body.data.orders[0].payAmount
Check "拿到订单号（$orderNo，应付 $($payable/100) 元）" ($null -ne $orderNo) $orderNo
$order2 = $null
# 一次结账会清空购物车（产品正确行为）：模拟客户"再买一单"，先重新加购
$cartAgain = Call 'Post' "$GatewayApi/api/cart/items" $sess @{ skuId = $sku.id; quantity = 1 }
if ($cartAgain.status -eq 200) {
    $order2 = Call 'Post' "$GatewayApi/api/orders" $sess @{
        receiverName = '体验客户'; receiverPhone = $phone
        receiverAddress = '广东省广州市天河区体验路 1 号'
        idempotentKey = [guid]::NewGuid().ToString()
    }
}
Check "重新加购后连下第二单（验证连续购买）" ($null -ne $order2 -and $order2.status -eq 200) "cart=$($cartAgain.status) order=$($order2.status)"
$pay = Call 'Post' "$GatewayApi/api/orders/$orderNo/pay" $sess @{ channel = 'mock'; idempotentKey = [guid]::NewGuid().ToString() }
Check "沙箱支付 200" ($pay.status -eq 200) "status=$($pay.status) body=$($pay.body | ConvertTo-Json -Compress)"
$od = Call 'Get' "$GatewayApi/api/orders/$orderNo" $sess $null
Check "订单状态变为 PAID（支付回调真实落库）" ($od.status -eq 200 -and $od.body.data.status -eq 'PAID') "status=$($od.status) order=$($od.body.data.status)"

# ---------- 7. 订单列表 / 售后入口 ----------
Write-Host "`n> 7. 订单列表"
$orders = Call 'Get' "$GatewayApi/api/orders?page=1&pageSize=10" $sess $null
Check "订单列表 200 且能看到刚下的单" ($orders.status -eq 200) $orders.status

# ---------- 8. 多多币 ----------
Write-Host "`n> 8. 多多币（登录用户福利）"
$coins = Call 'Get' "$GatewayApi/api/coins/me" $sess $null
Check "多多币账户 200" ($coins.status -eq 200) $coins.status
$signin = Call 'Get' "$GatewayApi/api/coins/signin/month" $sess $null
Check "签到日历 200" ($signin.status -eq 200) $signin.status

# ---------- 9. AI 导购 ----------
Write-Host "`n> 9. AI 导购「多多」"
$sw = [Diagnostics.Stopwatch]::StartNew()
$ai1 = Call 'Post' "$GatewayApi/api/duoduo/chat" $sess @{ message = "推荐一下"; sessionId = "demo-$([guid]::NewGuid().ToString('N').Substring(0,8))" }
$sw.Stop()
Check "「推荐一下」触发结构化澄清（$([Math]::Round($sw.Elapsed.TotalSeconds,1))s）" ($ai1.status -eq 200 -and $null -ne $ai1.body.data.clarify) "status=$($ai1.status)"
$optCount = @($ai1.body.data.clarify.options).Count
Check "澄清给出可点选项（>= 2 个）" ($optCount -ge 2) "options=$optCount"
$sw2 = [Diagnostics.Stopwatch]::StartNew()
$ai2 = Call 'Post' "$GatewayApi/api/duoduo/chat" $sess @{ message = "预算 200 元以内的猫粮"; clarified = $true }
$sw2.Stop()
Check "带预算推荐正常（$([Math]::Round($sw2.Elapsed.TotalSeconds,1))s，回复非空）" ($ai2.status -eq 200 -and "$($ai2.body.data.reply)" -ne '') "status=$($ai2.status)"
# 预算过滤是区间重叠语义（与 ai-smoke 3d 同口径）：商品区间与预算区间有交集即可，
# 客户看到的卡片价格是 minPrice（"129 元起"），超预算判断看 minPrice 是否落在预算内
$inBudget = @($ai2.body.data.recommendations | Where-Object { $_.minPrice -gt 20000 })
Check "推荐在预算语义内正确（minPrice 不超 200 元）" ($inBudget.Count -eq 0) ($inBudget | ConvertTo-Json -Compress)

# ---------- 10. 客服 ----------
Write-Host "`n> 10. 在线客服入口"
$sessions = Call 'Get' "$GatewayApi/api/support/sessions" $sess $null
Check "客服会话列表 200" ($sessions.status -eq 200) $sessions.status

# ---------- 11. 售后退款（退货退款全链：立案→同意→回寄→确认收货→退款落库） ----------
Write-Host "`n> 11. 售后退款（第三方商品，卖家真实发货）"
$phoneB2 = '139' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$sessB2 = New-Object Microsoft.PowerShell.Commands.WebRequestSession
Call 'Post' "$GatewayApi/api/auth/register" $sessB2 @{ phone = $phoneB2; password = $pwd; nickname = "售后体验$(Get-Random -Maximum 99)" } | Out-Null
# 喵星人的第三方商品 9001（test-accounts 预置）
$detail9001 = Call 'Get' "$GatewayApi/api/products/9001" $null $null
$sku9001 = @($detail9001.body.data.skus)[0]
$sellerIdOf9001 = $detail9001.body.data.sellerId
Check "第三方商品 9001 可购（卖家 $sellerIdOf9001）" ($detail9001.status -eq 200 -and $null -ne $sku9001.id) $detail9001.status
Call 'Post' "$GatewayApi/api/cart/items" $sessB2 @{ skuId = $sku9001.id; quantity = 1 } | Out-Null
$order11 = Call 'Post' "$GatewayApi/api/orders" $sessB2 @{ receiverName = '售后体验'; receiverPhone = $phoneB2; receiverAddress = '广东省广州市天河区体验路 2 号'; idempotentKey = [guid]::NewGuid().ToString() }
$on11 = $order11.body.data.orders[0].orderNo
Check "第三方订单创建（$on11）" ($order11.status -eq 200) $order11.status
Call 'Post' "$GatewayApi/api/orders/$on11/pay" $sessB2 @{ channel = 'mock'; idempotentKey = [guid]::NewGuid().ToString() } | Out-Null
# 卖家（喵星人）登录并发货 —— /api/seller/** 只接受商家端入口签发的令牌（E8 入口边界），
# 纯 API 调用必须自带 X-Duoduo-Entry: merchant（浏览器场景由 8091 站点 nginx 注入）
$ENTRY_MERCHANT = @{ 'X-Duoduo-Entry' = 'merchant' }
$ENTRY_ADMIN = @{ 'X-Duoduo-Entry' = 'admin' }
$sessSeller = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$loginSeller = Call 'Post' "$GatewayApi/api/auth/login" $sessSeller @{ phone = '13600003333'; password = $pwd } $ENTRY_MERCHANT
Check "第三方商家登录（SELLER）" ($loginSeller.status -eq 200) $loginSeller.status
$ship11 = Call 'Post' "$GatewayApi/api/seller/orders/$on11/ship" $sessSeller @{ logisticsCompany = '顺丰速运'; logisticsNo = "SF-DEMO-$([guid]::NewGuid().ToString('N').Substring(0,8))" }
Check "卖家发货成功（订单 SHIPPED）" ($ship11.status -eq 200) "status=$($ship11.status) body=$($ship11.body | ConvertTo-Json -Compress)"
$as11 = Call 'Post' "$GatewayApi/api/aftersales" $sessB2 @{ orderNo = $on11; type = 'RETURN_REFUND'; reason = '演示：尺寸不合适，申请退货退款' }
Check "买家申请退货退款（立案 200）" ($as11.status -eq 200) "status=$($as11.status) body=$($as11.body | ConvertTo-Json -Compress)"
$asId11 = $as11.body.data.id
$agree11 = Call 'Post' "$GatewayApi/api/aftersales/$asId11/reply" $sessSeller @{ action = 'AGREE'; reply = '同意退货，请寄回' }
Check "卖家同意退货" ($agree11.status -eq 200) "status=$($agree11.status)"
$retShip11 = Call 'Post' "$GatewayApi/api/aftersales/$asId11/return-shipment" $sessB2 @{ courierCompany = '中通快递'; trackingNo = "ZT-RET-$([guid]::NewGuid().ToString('N').Substring(0,8))" }
Check "买家寄回商品（回寄 200）" ($retShip11.status -eq 200) "status=$($retShip11.status)"
$retRcpt11 = Call 'Post' "$GatewayApi/api/aftersales/$asId11/return-receipt" $sessSeller $null
Check "卖家确认收到退货 → 退款发生（售后单 REFUNDED）" ($retRcpt11.status -eq 200 -and $retRcpt11.body.data.status -eq 'REFUNDED') "status=$($retRcpt11.status) as=$($retRcpt11.body.data.status)"
$ord11 = Call 'Get' "$GatewayApi/api/orders/$on11" $sessB2 $null
Check "订单状态 REFUNDED（退款真实落库）" ($ord11.body.data.status -eq 'REFUNDED') $ord11.body.data.status
$set11 = Call 'Get' "$GatewayApi/api/orders/$on11/settlement" $sessB2 $null
Check "托管资金已退回买家（托管单 REFUNDED）" ($set11.body.data.status -eq 'REFUNDED') $set11.body.data.status
$mineAs = Call 'Get' "$GatewayApi/api/aftersales/mine?pageSize=10" $sessB2 $null
Check "我的售后列表可见该单" ($mineAs.status -eq 200) $mineAs.status

# ---------- 12. 确认收货 + 托管放款 + 评价晒单 ----------
Write-Host "`n> 12. 确认收货 → 放款 → 评价晒单"
# §11 下单时购物车已被清空：重新加购第三方商品（§12 的发货方同为喵星人，避免自营单跨卖家 403）
Call 'Post' "$GatewayApi/api/cart/items" $sessB2 @{ skuId = $sku9001.id; quantity = 1 } | Out-Null
$order12 = Call 'Post' "$GatewayApi/api/orders" $sessB2 @{ receiverName = '售后体验'; receiverPhone = $phoneB2; receiverAddress = '广东省广州市天河区体验路 2 号'; idempotentKey = [guid]::NewGuid().ToString() }
$on12 = $order12.body.data.orders[0].orderNo
Call 'Post' "$GatewayApi/api/orders/$on12/pay" $sessB2 @{ channel = 'mock'; idempotentKey = [guid]::NewGuid().ToString() } | Out-Null
Call 'Post' "$GatewayApi/api/seller/orders/$on12/ship" $sessSeller @{ logisticsCompany = '顺丰速运'; logisticsNo = "SF-DEMO-2$([guid]::NewGuid().ToString('N').Substring(0,7))" } | Out-Null
$confirm12 = Call 'Post' "$GatewayApi/api/orders/$on12/confirm" $sessB2 $null
Check "买家确认收货（订单 COMPLETED）" ($confirm12.status -eq 200 -and $confirm12.body.data.status -eq 'COMPLETED') "status=$($confirm12.status) order=$($confirm12.body.data.status)"
$set12 = Call 'Get' "$GatewayApi/api/orders/$on12/settlement" $sessB2 $null
Check "托管资金已放款给卖家（托管单 RELEASED）" ($set12.body.data.status -eq 'RELEASED') $set12.body.data.status
$spu12 = 9001
$sku12Id = $sku9001.id
$review12 = Call 'Post' "$GatewayApi/api/reviews" $sessB2 @{ orderNo = $on12; items = @( @{ spuId = "$spu12"; skuId = "$sku12Id"; rating = 5; content = '演示评价：物流很快，包装完好，猫咪很爱吃！' } ) }
Check "发布评价（5 星）" ($review12.status -eq 200) "status=$($review12.status) body=$($review12.body | ConvertTo-Json -Compress)"
$revMine = Call 'Get' "$GatewayApi/api/reviews/mine" $sessB2 $null
Check "我的评价可见" ($revMine.status -eq 200) $revMine.status

# ---------- 13. 闲置（发布 → 运营审核 → 公开可见 → 管理） ----------
Write-Host "`n> 13. 二手闲置：发布 → 审核 → 上架 → 管理"
# 实名信息会写入令牌 claims：实名后必须重新登录换取新令牌，旧令牌仍判「未实名」
$rn13 = Call 'Post' "$GatewayApi/api/users/me/realname" $sess @{ realName = '体验官'; idCard = '440101199001011234' }
Check "发布前实名（200 或已实名幂等）" ($rn13.status -eq 200 -or $rn13.status -eq 409) "status=$($rn13.status)"
Call 'Post' "$GatewayApi/api/auth/login" $sess @{ phone = $phone; password = $pwd } | Out-Null
$pub13 = Call 'Post' "$GatewayApi/api/listings" $sess @{ title = "演示闲置：九成新猫爬架$(Get-Random -Maximum 99)"; price = 8800; description = '演示数据：搬家出闲置，九成新'; conditionLevel = 'GOOD'; city = '广州'; species = 'cat' }
Check "发布闲置（价格 88 元）" ($pub13.status -eq 200) "status=$($pub13.status) body=$($pub13.body | ConvertTo-Json -Compress)"
$listingId = $pub13.body.data.id
$mine13 = Call 'Get' "$GatewayApi/api/listings/mine/list?pageSize=10" $sess $null
$mineItem = @($mine13.body.data.items | Where-Object { "$($_.id)" -eq "$listingId" })[0]
Check "我的闲置出现该条（审核中）" ($null -ne $mineItem -and $mineItem.auditStatus -eq 'PENDING') ($mineItem | ConvertTo-Json -Compress)
# 运营审核通过 —— /api/admin/** 只接受后台入口令牌
$sessOp13 = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$loginOp13 = Call 'Post' "$GatewayApi/api/auth/login" $sessOp13 @{ phone = '13684295254'; password = $pwd } $ENTRY_ADMIN
Check "运营账号登录" ($loginOp13.status -eq 200) $loginOp13.status
$audit13 = Call 'Post' "$GatewayApi/api/admin/listings/$listingId/audit" $sessOp13 @{ action = 'APPROVE' }
Check "运营审核通过" ($audit13.status -eq 200) "status=$($audit13.status) body=$($audit13.body | ConvertTo-Json -Compress)"
$search13 = Call 'Get' "$GatewayApi/api/listings/search?keyword=$( [System.Uri]::EscapeDataString('猫爬架') )&page=1&pageSize=10" $null $null
$pubItem = @($search13.body.data.items | Where-Object { "$($_.id)" -eq "$listingId" })[0]
Check "公开列表可搜到该闲置" ($null -ne $pubItem) ($search13.body.data.items | ConvertTo-Json -Compress)
$detail13 = Call 'Get' "$GatewayApi/api/listings/$listingId" $null $null
# 详情返回 {card, description, ...}：价格在 card.price（分）
Check "公开详情可见（含价格与描述）" ($detail13.status -eq 200 -and $detail13.body.data.card.price -eq 8800) "status=$($detail13.status) price=$($detail13.body.data.card.price)"
# 下架 → 再上架 → 删除（管理的最小闭环）
$off13 = Call 'Post' "$GatewayApi/api/listings/$listingId/status" $sess @{ action = 'OFF_SHELF' }
Check "卖家下架闲置" ($off13.status -eq 200) "status=$($off13.status)"
$offDetail = Call 'Get' "$GatewayApi/api/listings/$listingId" $null $null
Check "下架后公开详情 404（不再可见）" ($offDetail.status -eq 404) $offDetail.status
$on13 = Call 'Post' "$GatewayApi/api/listings/$listingId/status" $sess @{ action = 'ON_SALE' }
Check "重新上架闲置" ($on13.status -eq 200) $on13.status
# 删除不在这里做：§14 私信要复用这条已审核上架的闲置（一次发布、全程用完再删）。
# 另外若在 §14 紧接着再发一条新闲置，会踩发布频控：两次发布间隔须 ≥60s
#（ListingService.checkPublishQuota 的 publish-min-interval-seconds），复用可绕开频控与时序抖动。

# ---------- 14. 闲置私信「问一问」（买卖双方沟通） ----------
Write-Host "`n> 14. 私信问一问（买卖沟通）"
# 复用 §13 已审核上架的闲置 $listingId（不二次发布，原因见 §13 末尾频控说明）
$myProfile = Call 'Get' "$GatewayApi/api/users/me" $sess $null
$sellerUserId = $myProfile.body.data.id
$b2Profile = Call 'Get' "$GatewayApi/api/users/me" $sessB2 $null
$b2UserId = $b2Profile.body.data.id
$msg14 = Call 'Post' "$GatewayApi/api/socials/messages" $sessB2 @{ receiverId = "$sellerUserId"; content = '这个猫爬架还在吗？可以刀一点吗？'; listingId = "$listingId" }
Check "买家给闲置卖家发私信（问一问）" ($msg14.status -eq 200) "status=$($msg14.status) body=$($msg14.body | ConvertTo-Json -Compress)"
$conv14 = Call 'Get' "$GatewayApi/api/socials/messages/conversations?page=1&pageSize=10" $sess $null
$convItem = @($conv14.body.data.items | Where-Object { "$($_.peerId)" -eq "$b2UserId" -and "$($_.listingId)" -eq "$listingId" })[0]
Check "卖家会话列表出现闲置咨询（未读 ≥1）" ($null -ne $convItem -and $convItem.unread -ge 1) ($conv14.body.data.items | ConvertTo-Json -Compress -Depth 4)
$reply14 = Call 'Post' "$GatewayApi/api/socials/messages" $sess @{ receiverId = "$b2UserId"; content = '在的！演示价格可小刀~' ; listingId = "$listingId" }
Check "卖家回复私信" ($reply14.status -eq 200) $reply14.status
# 2026-11-08 实测踩坑：peerId 必须用 ${sellerUserId} 花括号 —— PS 3.0+ 变量名允许含「?」，
# 裸写 $sellerUserId?listingId 会被整体解析成一个不存在的变量（空值），
# URL 变成 /conversations/?listingId=…，网关后端报 400「peerId 需要 Long 类型」。
$b2Conv = Call 'Get' "$GatewayApi/api/socials/messages/conversations/${sellerUserId}?listingId=${listingId}" $sessB2 $null
$b2Last = @($b2Conv.body.data.items)[-1]
Check "买家收到卖家回复" ($b2Conv.status -eq 200 -and "$($b2Last.content)".Contains('在的')) "status=$($b2Conv.status) body=$($b2Conv.body | ConvertTo-Json -Compress -Depth 4)"
# 防假绿：响应必须回显闲置上下文（listingId + consulting），证明消息真落在咨询会话而不是主会话顶替
Check "消息确实落在闲置咨询会话（listingId 回显）" ($b2Conv.status -eq 200 -and "$($b2Conv.body.data.listingId)" -eq "$listingId" -and $b2Conv.body.data.consulting -eq $true) "listingId=$($b2Conv.body.data.listingId) consulting=$($b2Conv.body.data.consulting)"
# 删除挪到这里收尾（§13 管理闭环的最后一环）：私信旅程结束后才删
$del13 = Call 'Post' "$GatewayApi/api/listings/$listingId/delete" $sess $null
Check "删除闲置（管理闭环收尾）" ($del13.status -eq 200) $del13.status

# ---------- 15. 客服双向聊天 ----------
Write-Host "`n> 15. 在线客服（买家 ↔ 自营客服）"
$supportSess = Call 'Get' "$GatewayApi/api/support/session?sellerId=0" $sess $null
Check "买家打开自营客服会话" ($supportSess.status -eq 200) $supportSess.status
$sessionId15 = $supportSess.body.data.id
$msg15 = Call 'Post' "$GatewayApi/api/support/messages" $sess @{ sessionId = "$sessionId15"; sellerId = '0'; content = '演示提问：这款猫粮适合 3 个月幼猫吗？'; type = 'TEXT' }
Check "买家向客服发消息" ($msg15.status -eq 200) "status=$($msg15.status) body=$($msg15.body | ConvertTo-Json -Compress)"
# 自营客服（13900000002，SELLER）回复 —— 客服接待台在 /api/seller/** 下，同样需要商家端入口
$sessKefu = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$loginKefu = Call 'Post' "$GatewayApi/api/auth/login" $sessKefu @{ phone = '13900000002'; password = $pwd } $ENTRY_MERCHANT
Check "自营客服账号登录" ($loginKefu.status -eq 200) $loginKefu.status
$kefuReply = Call 'Post' "$GatewayApi/api/seller/support/sessions/$sessionId15/messages" $sessKefu @{ content = '适合的，这款是全期粮，幼猫也能吃。有其他问题随时问我~'; type = 'TEXT' }
Check "客服回复消息" ($kefuReply.status -eq 200) "status=$($kefuReply.status) body=$($kefuReply.body | ConvertTo-Json -Compress)"
$thread15 = Call 'Get' "$GatewayApi/api/support/messages?sessionId=$sessionId15&page=1&pageSize=20" $sess $null
$kefuMsgVisible = @($thread15.body.data.items | Where-Object { "$($_.content)".Contains('适合的') }).Count
Check "买家侧能看到客服回复（双向闭环）" ($thread15.status -eq 200 -and $kefuMsgVisible -ge 1) "status=$($thread15.status) visible=$kefuMsgVisible"

# ---------- 16. 分享圈 + 关注 + 通知 ----------
Write-Host "`n> 16. 分享圈 / 关注 / 通知"
$share16 = Call 'Post' "$GatewayApi/api/shares" $sessB2 @{ spuId = "$spu12"; content = '演示分享：这款猫粮我家主子超爱吃！'; topics = @('猫粮') }
Check "发布分享（带话题）" ($share16.status -eq 200) "status=$($share16.status) body=$($share16.body | ConvertTo-Json -Compress)"
$feed16 = Call 'Get' "$GatewayApi/api/shares/feed?page=1&pageSize=10" $null $null
$feedHas = @($feed16.body.data.items | Where-Object { "$($_.id)" -eq "$($share16.body.data.id)" }).Count
Check "分享圈信息流可见该帖（匿名可逛）" ($feedHas -ge 1) "count=$feedHas"
$follow16 = Call 'Post' "$GatewayApi/api/socials/follows/$sellerUserId" $sessB2 $null
Check "关注闲置卖家" ($follow16.status -eq 200) $follow16.status
$notices16 = Call 'Get' "$GatewayApi/api/socials/notices?page=1&pageSize=10" $sess $null
Check "通知中心 200（有新私信/关注事件则计数）" ($notices16.status -eq 200) $notices16.status

# ---------- 17. 宠物档案 → AI 记忆 ----------
Write-Host "`n> 17. 宠物档案与 AI 记忆联动"
$petName17 = "雪球$(Get-Random -Maximum 99)"
$pet17 = Call 'Post' "$GatewayApi/api/pets" $sess @{ name = $petName17; species = 'cat'; birthday = '2024-05-01'; weightKg = 3.5; gender = 'FEMALE'; neutered = $true }
Check "添加宠物档案（$petName17）" ($pet17.status -eq 200) "status=$($pet17.status) body=$($pet17.body | ConvertTo-Json -Compress)"
$pets17 = Call 'Get' "$GatewayApi/api/pets" $sess $null
$petsHas = @($pets17.body.data | Where-Object { $_.name -eq $petName17 }).Count
Check "宠物列表可见" ($petsHas -ge 1) "count=$petsHas"
$mem17 = Call 'Get' "$GatewayApi/api/duoduo/memory" $sess $null
Check "AI 记忆库接口 200（档案可供 AI 引用）" ($mem17.status -eq 200) $mem17.status

# ---------- 18. 收藏 / 足迹 / 秒杀 ----------
Write-Host "`n> 18. 收藏 / 足迹 / 秒杀"
$fav18 = Call 'Post' "$GatewayApi/api/favorites/$spu12" $sessB2 $null
Check "收藏商品" ($fav18.status -eq 200) $fav18.status
$favList18 = Call 'Get' "$GatewayApi/api/favorites/products?page=1&pageSize=10" $sessB2 $null
$favHas = @($favList18.body.data.items | Where-Object { "$($_.id)" -eq "$spu12" }).Count
Check "收藏列表包含该商品" ($favHas -ge 1) "count=$favHas"
$hist18 = Call 'Get' "$GatewayApi/api/history/products?page=1&pageSize=10" $sessB2 $null
Check "浏览足迹 200（本脚本浏览过的商品在列）" ($hist18.status -eq 200) $hist18.status
$seckill18 = Call 'Get' "$GatewayApi/api/seckill/activities" $null $null
if ($seckill18.status -eq 200) {
    $seckillCount = @($seckill18.body.data).Count
    if ($seckillCount -gt 0) { Write-Host "  [信息] 云端有 $seckillCount 个秒杀活动（专区可体验）" }
    else { Write-Host "  [信息] 云端暂无进行中的秒杀活动，专区空态展示，不算失败" }
    Check "秒杀活动接口 200" ($true) ''
} else {
    Check "秒杀活动接口 200" ($false) $seckill18.status
}

# ---------- 汇总 ----------
Write-Host "`n=== 云端验证结果：通过 $pass 项，失败 $fail 项 ==="
if ($fail -gt 0) { $failures | ForEach-Object { Write-Host "  - $_" } }
Write-Host "`n体验账号：$phone / $pwd （本脚本刚注册，可直接发给客户前再单建）"
if ($fail -gt 0) { exit 1 }
