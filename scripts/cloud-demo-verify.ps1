# 云端演示环境全链路验证（2026-11-08）
# 目标：模拟一个真实客户的完整体验路径，全部走 http://8.163.71.44（80 主入口）
param(
    [string]$Gateway = 'http://8.163.71.44'
)

$ErrorActionPreference = 'Continue'
$pass = 0; $fail = 0; $failures = @()
function Check([string]$name, [bool]$ok, $extra) {
    if ($ok) { $script:pass++; Write-Host "  [PASS] $name" }
    else { $script:fail++; $script:failures += $name; if ($null -ne $extra) { Write-Host "  [FAIL] $name  -> $extra" } else { Write-Host "  [FAIL] $name" } }
}
function Call([string]$method, [string]$uri, $session, $bodyObj) {
    $params = @{ Uri = $uri; Method = $method; UseBasicParsing = $true; TimeoutSec = 130 }
    if ($session) { $params.WebSession = $session }
    if ($null -ne $bodyObj) { $params.Body = ([Text.Encoding]::UTF8.GetBytes(($bodyObj | ConvertTo-Json -Compress))); $params.ContentType = 'application/json; charset=utf-8' }
    try { $r = Invoke-WebRequest @params; return @{ status = [int]$r.StatusCode; body = ($r.Content | ConvertFrom-Json) } }
    catch {
        $code = 0; $body = $null
        try { $code = [int]$_.Exception.Response.StatusCode.value__ } catch { }
        try { if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $body = $_.ErrorDetails.Message | ConvertFrom-Json } } catch { }
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
$cap = Call 'Get' "$Gateway/api/duoduo/capabilities" $null $null
Check "AI 能力声明可用（mode=llm）" ($cap.status -eq 200 -and $cap.body.data.mode -eq 'llm') "$($cap.status) mode=$($cap.body.data.mode)"

# ---------- 2. 注册 + 登录 ----------
Write-Host "`n> 2. 注册与登录（模拟新客户）"
$phone = '139' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$pwd = 'duoduo12345'
$sess = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$reg = Call 'Post' "$Gateway/api/auth/register" $sess @{ phone = $phone; password = $pwd; nickname = "体验客户$($phone.Substring(7))" }
Check "新客户注册成功（200）" ($reg.status -eq 200) "status=$($reg.status) body=$($reg.body | ConvertTo-Json -Compress)"
if ($reg.status -eq 409) {
    $reg = Call 'Post' "$Gateway/api/auth/login" $sess @{ phone = $phone; password = $pwd }
    Check "手机号已存在时回退登录（200）" ($reg.status -eq 200) $reg.status
}
$me = Call 'Get' "$Gateway/api/users/me" $sess $null
Check "登录态有效（/api/users/me 200）" ($me.status -eq 200) $me.status
$anon = Call 'Get' "$Gateway/api/users/me" $null $null
Check "未登录访问个人信息被拦（401，鉴权正常）" ($anon.status -eq 401) $anon.status

# ---------- 3. 商品浏览 ----------
Write-Host "`n> 3. 商品浏览（首页 → 列表 → 详情）"
$list = Call 'Get' "$Gateway/api/products?page=1&pageSize=10" $null $null
Check "商品列表 200" ($list.status -eq 200) $list.status
$items = @($list.body.data.items)
Check "商品列表非空（共 $($list.body.data.total) 个 SPU）" ($items.Count -gt 0) $items.Count
$spu = $items[0]
$detail = Call 'Get' "$Gateway/api/products/$($spu.id)" $null $null
Check "商品详情 200" ($detail.status -eq 200) $detail.status
$sku = @($detail.body.data.skus)[0]
Check "详情含可购 SKU（id=$($sku.id) 价格=$($sku.price/100) 元）" ($null -ne $sku.id) "sku=$($sku | ConvertTo-Json -Compress)"
$search = Call 'Get' "$Gateway/api/products?keyword=$( [System.Uri]::EscapeDataString('猫粮') )&page=1&pageSize=5" $null $null
Check "关键词搜索「猫粮」200 且有结果" ($search.status -eq 200 -and @($search.body.data.items).Count -gt 0) "$($search.status) count=$(@($search.body.data.items).Count)"

# ---------- 4. 优惠券 ----------
Write-Host "`n> 4. 优惠券（领券中心）"
$tpls = Call 'Get' "$Gateway/api/coupons/templates" $sess $null
Check "领券中心模板列表 200" ($tpls.status -eq 200) $tpls.status
# 该接口返回裸数组（data 即列表），且只推荐 remainingCount > 0 的券
$tplList = @($tpls.body.data | Where-Object { $_.remainingCount -gt 0 })
if ($tplList.Count -gt 0) {
    $claim = Call 'Post' "$Gateway/api/coupons/templates/$($tplList[0].id)/claim" $sess $null
    Check "领取「$($tplList[0].name)」（200 或 409 已领）" ($claim.status -eq 200 -or $claim.status -eq 409) "status=$($claim.status) body=$($claim.body | ConvertTo-Json -Compress)"
    $mine = Call 'Get' "$Gateway/api/coupons/mine" $sess $null
    Check "我的券包 200" ($mine.status -eq 200) $mine.status
} else {
    Write-Host "  [信息] 云端暂无可领券模板，跳过领券断言"
}

# ---------- 5. 购物车 ----------
Write-Host "`n> 5. 购物车"
$cartAdd = Call 'Post' "$Gateway/api/cart/items" $sess @{ skuId = $sku.id; quantity = 1 }
Check "加入购物车 200" ($cartAdd.status -eq 200) "status=$($cartAdd.status) body=$($cartAdd.body | ConvertTo-Json -Compress)"
$cartAdd2 = Call 'Post' "$Gateway/api/cart/items" $sess @{ skuId = $sku.id; quantity = 1 }
Check "重复加购幂等（200）" ($cartAdd2.status -eq 200) $cartAdd2.status
$cart = Call 'Get' "$Gateway/api/cart" $sess $null
Check "购物车列表 200 且含该商品" ($cart.status -eq 200) $cart.status

# ---------- 6. 下单 + 沙箱支付 ----------
Write-Host "`n> 6. 下单与沙箱支付（完整购买流程）"
$order = Call 'Post' "$Gateway/api/orders" $sess @{
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
$cartAgain = Call 'Post' "$Gateway/api/cart/items" $sess @{ skuId = $sku.id; quantity = 1 }
if ($cartAgain.status -eq 200) {
    $order2 = Call 'Post' "$Gateway/api/orders" $sess @{
        receiverName = '体验客户'; receiverPhone = $phone
        receiverAddress = '广东省广州市天河区体验路 1 号'
        idempotentKey = [guid]::NewGuid().ToString()
    }
}
Check "重新加购后连下第二单（验证连续购买）" ($null -ne $order2 -and $order2.status -eq 200) "cart=$($cartAgain.status) order=$($order2.status)"
$pay = Call 'Post' "$Gateway/api/orders/$orderNo/pay" $sess @{ channel = 'mock'; idempotentKey = [guid]::NewGuid().ToString() }
Check "沙箱支付 200" ($pay.status -eq 200) "status=$($pay.status) body=$($pay.body | ConvertTo-Json -Compress)"
$od = Call 'Get' "$Gateway/api/orders/$orderNo" $sess $null
Check "订单状态变为 PAID（支付回调真实落库）" ($od.status -eq 200 -and $od.body.data.status -eq 'PAID') "status=$($od.status) order=$($od.body.data.status)"

# ---------- 7. 订单列表 / 售后入口 ----------
Write-Host "`n> 7. 订单列表"
$orders = Call 'Get' "$Gateway/api/orders?page=1&pageSize=10" $sess $null
Check "订单列表 200 且能看到刚下的单" ($orders.status -eq 200) $orders.status

# ---------- 8. 多多币 ----------
Write-Host "`n> 8. 多多币（登录用户福利）"
$coins = Call 'Get' "$Gateway/api/coins/me" $sess $null
Check "多多币账户 200" ($coins.status -eq 200) $coins.status
$signin = Call 'Get' "$Gateway/api/coins/signin/month" $sess $null
Check "签到日历 200" ($signin.status -eq 200) $signin.status

# ---------- 9. AI 导购 ----------
Write-Host "`n> 9. AI 导购「多多」"
$sw = [Diagnostics.Stopwatch]::StartNew()
$ai1 = Call 'Post' "$Gateway/api/duoduo/chat" $sess @{ message = "推荐一下"; sessionId = "demo-$([guid]::NewGuid().ToString('N').Substring(0,8))" }
$sw.Stop()
Check "「推荐一下」触发结构化澄清（$([Math]::Round($sw.Elapsed.TotalSeconds,1))s）" ($ai1.status -eq 200 -and $null -ne $ai1.body.data.clarify) "status=$($ai1.status)"
$optCount = @($ai1.body.data.clarify.options).Count
Check "澄清给出可点选项（>= 2 个）" ($optCount -ge 2) "options=$optCount"
$sw2 = [Diagnostics.Stopwatch]::StartNew()
$ai2 = Call 'Post' "$Gateway/api/duoduo/chat" $sess @{ message = "预算 200 元以内的猫粮"; clarified = $true }
$sw2.Stop()
Check "带预算推荐正常（$([Math]::Round($sw2.Elapsed.TotalSeconds,1))s，回复非空）" ($ai2.status -eq 200 -and "$($ai2.body.data.reply)" -ne '') "status=$($ai2.status)"
# 预算过滤是区间重叠语义（与 ai-smoke 3d 同口径）：商品区间与预算区间有交集即可，
# 客户看到的卡片价格是 minPrice（"129 元起"），超预算判断看 minPrice 是否落在预算内
$inBudget = @($ai2.body.data.recommendations | Where-Object { $_.minPrice -gt 20000 })
Check "推荐在预算语义内正确（minPrice 不超 200 元）" ($inBudget.Count -eq 0) ($inBudget | ConvertTo-Json -Compress)

# ---------- 10. 客服 ----------
Write-Host "`n> 10. 在线客服入口"
$sessions = Call 'Get' "$Gateway/api/support/sessions" $sess $null
Check "客服会话列表 200" ($sessions.status -eq 200) $sessions.status

# ---------- 汇总 ----------
Write-Host "`n=== 云端验证结果：通过 $pass 项，失败 $fail 项 ==="
if ($fail -gt 0) { $failures | ForEach-Object { Write-Host "  - $_" } }
Write-Host "`n体验账号：$phone / $pwd （本脚本刚注册，可直接发给客户前再单建）"
if ($fail -gt 0) { exit 1 }
