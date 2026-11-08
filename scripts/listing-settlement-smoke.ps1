param(
    [string]$GatewayApi = 'http://127.0.0.1:8080',
    [string]$Pwd = 'duoduo12345'
)
# 闲置担保交易冒烟（B-11 · B4）：本地/云端通用，直连网关（ent=null 不受入口边界限制）。
#
# 双闲置双链路设计（避免发布频控 60s 间隔 → 用两个卖家各发一条）：
#   闲置A（sellerA）完整成交链：下单 → CAS 抢空 → 幂等重放 → 支付 → 托管 → 发货 → 确认收货 → 放款
#   闲置B（sellerB）退款释放链：下单 → 支付 → 托管 → 未发货退款 → 托管退回 → 闲置回 ON_SALE（释放钩子生效）
#
# 断言点对照后端不变量（ListingOrderServiceTest / ListingOrderTradeServiceTest）：
#   - CAS 单件防超卖（并发窗口）：第二个买家 422
#   - 幂等键重放：同键重复下单返回同一单号
#   - 释放幂等：退款后闲置 B 公开详情从「已售 404」恢复 200
$ErrorActionPreference = 'Continue'
$pass = 0; $fail = 0; $failures = @()
function Check([string]$name, [bool]$ok, $extra) {
    if ($ok) { $script:pass++; Write-Host "  [PASS] $name" }
    else { $script:fail++; $script:failures += $name; Write-Host "  [FAIL] $name  -> $extra" }
}
function Call([string]$method, [string]$uri, $session, $bodyObj) {
    $params = @{ Uri = $uri; Method = $method; UseBasicParsing = $true; TimeoutSec = 30 }
    if ($session) { $params.WebSession = $session }
    if ($null -ne $bodyObj) { $params.Body = ([Text.Encoding]::UTF8.GetBytes(($bodyObj | ConvertTo-Json -Depth 6 -Compress))); $params.ContentType = 'application/json; charset=utf-8' }
    try { $r = Invoke-WebRequest @params; return @{ status = [int]$r.StatusCode; body = ($r.Content | ConvertFrom-Json) } }
    catch {
        $code = 0; $body = $null
        try { $code = [int]$_.Exception.Response.StatusCode.value__ } catch { }
        try {
            if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $body = $_.ErrorDetails.Message | ConvertFrom-Json }
        } catch { }
        if ($null -eq $body) {
            try {
                $resp = $_.Exception.Response; $text = $null
                if ($resp -and $resp.PSObject.Methods.Name -contains 'GetResponseStream') {
                    $stream = $resp.GetResponseStream()
                    if ($stream) { $text = (New-Object System.IO.StreamReader($stream)).ReadToEnd() }
                }
                if ($text) { $body = $text | ConvertFrom-Json }
            } catch { }
        }
        return @{ status = $code; body = $body }
    }
}
function New-Account([string]$nickname) {
    $phone = '139' + (Get-Random -Minimum 10000000 -Maximum 99999999)
    $s = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $reg = Call 'Post' "$GatewayApi/api/auth/register" $s @{ phone = $phone; password = $Pwd; nickname = $nickname }
    if ($reg.status -ne 200) {
        $reg = Call 'Post' "$GatewayApi/api/auth/login" $s @{ phone = $phone; password = $Pwd }
    }
    return @{ phone = $phone; sess = $s }
}
function Enable-RealName($acct) {
    # 实名写入 claims：实名后必须重登换取新令牌（与 aftersale-smoke 同款纪律）
    Call 'Post' "$GatewayApi/api/users/me/realname" $acct.sess @{ realName = '担保卖家'; idCard = '44010119900101' + (Get-Random -Minimum 1000 -Maximum 9999) } | Out-Null
    Call 'Post' "$GatewayApi/api/auth/login" $acct.sess @{ phone = $acct.phone; password = $Pwd } | Out-Null
}

Write-Host "=== 闲置担保交易冒烟（B-11）@ $GatewayApi ==="

# ---------- 1. 角色就位 ----------
Write-Host "`n> 1. 角色与发布（两个卖家规避 60s 发布间隔频控）"
$sellerA = New-Account '担保卖家A'; Enable-RealName $sellerA
$sellerB = New-Account '担保卖家B'; Enable-RealName $sellerB
$buyer1 = New-Account '担保买家1'
$buyer2 = New-Account '担保买家2'
$opSess = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$opLogin = Call 'Post' "$GatewayApi/api/auth/login" $opSess @{ phone = '13684295254'; password = $Pwd }
Check "运营账号登录" ($opLogin.status -eq 200) $opLogin.status

$pubA = Call 'Post' "$GatewayApi/api/listings" $sellerA.sess @{ title = "担保冒烟：猫爬架$(Get-Random -Maximum 999)"; price = 8800; description = '担保交易冒烟数据 A'; conditionLevel = 'GOOD'; city = '广州'; species = 'cat' }
Check "卖家A 发布闲置A（88 元）" ($pubA.status -eq 200) "status=$($pubA.status) body=$($pubA.body | ConvertTo-Json -Compress)"
$listingA = $pubA.body.data.id
$pubB = Call 'Post' "$GatewayApi/api/listings" $sellerB.sess @{ title = "担保冒烟：宠物背包$(Get-Random -Maximum 999)"; price = 5900; description = '担保交易冒烟数据 B'; conditionLevel = 'LIKE_NEW'; city = '上海'; species = 'cat' }
Check "卖家B 发布闲置B（59 元）" ($pubB.status -eq 200) "status=$($pubB.status)"
$listingB = $pubB.body.data.id

$auditA = Call 'Post' "$GatewayApi/api/admin/listings/$listingA/audit" $opSess @{ action = 'APPROVE' }
$auditB = Call 'Post' "$GatewayApi/api/admin/listings/$listingB/audit" $opSess @{ action = 'APPROVE' }
Check "运营审核 A/B 通过" ($auditA.status -eq 200 -and $auditB.status -eq 200) "A=$($auditA.status) B=$($auditB.status)"

# ---------- 2. 下单：CAS 防超卖 + 幂等重放 ----------
Write-Host "`n> 2. 下单（CAS 单件防超卖 / 幂等键重放）"
$idem1 = [guid]::NewGuid().ToString()
$orderBody = @{ receiverName = '担保买家'; receiverPhone = $buyer1.phone; receiverAddress = '广东省广州市天河区担保路 1 号'; idempotentKey = $idem1 }
$placed1 = Call 'Post' "$GatewayApi/api/listings/$listingA/orders" $buyer1.sess $orderBody
Check "买家1 担保下单成功（orderNo 落定）" ($placed1.status -eq 200 -and $placed1.body.data.orderNo) "status=$($placed1.status) body=$($placed1.body | ConvertTo-Json -Compress)"
$orderNo = $placed1.body.data.orderNo
Check "应付金额 = 闲置快照价 8800 分" ($placed1.body.data.payAmount -eq 8800) "payAmount=$($placed1.body.data.payAmount)"

$placed2 = Call 'Post' "$GatewayApi/api/listings/$listingA/orders" $buyer2.sess @{ receiverName = '担保买家2'; receiverPhone = $buyer2.phone; receiverAddress = '上海市静安区担保路 2 号'; idempotentKey = [guid]::NewGuid().ToString() }
Check "买家2 再买同一条被 CAS 拒绝（422 手慢了）" ($placed2.status -eq 422) "status=$($placed2.status) body=$($placed2.body | ConvertTo-Json -Compress)"

$replay = Call 'Post' "$GatewayApi/api/listings/$listingA/orders" $buyer1.sess $orderBody
Check "买家1 同幂等键重试 → 重放同一单" ($replay.status -eq 200 -and $replay.body.data.orderNo -eq $orderNo) "status=$($replay.status) orderNo=$($replay.body.data.orderNo)"

$ownBuy = Call 'Post' "$GatewayApi/api/listings/$listingB/orders" $sellerB.sess @{ receiverName = '自买'; receiverPhone = $sellerB.phone; receiverAddress = '地址地址地址'; idempotentKey = [guid]::NewGuid().ToString() }
Check "卖家买自己的闲置被拒（422）" ($ownBuy.status -eq 422) "status=$($ownBuy.status)"

# ---------- 3. 支付 → 托管 ----------
Write-Host "`n> 3. 支付与资金托管"
$pay = Call 'Post' "$GatewayApi/api/orders/$orderNo/pay" $buyer1.sess @{ channel = 'mock'; idempotentKey = [guid]::NewGuid().ToString() }
Check "买家1 沙箱支付成功" ($pay.status -eq 200) "status=$($pay.status)"
$set1 = Call 'Get' "$GatewayApi/api/orders/$orderNo/settlement" $buyer1.sess $null
Check "托管单已开（CUSTODY，货款冻结）" ($set1.status -eq 200 -and $set1.body.data.status -eq 'CUSTODY') "status=$($set1.status) settlement=$($set1.body.data.status)"
$ord1 = Call 'Get' "$GatewayApi/api/orders/$orderNo" $buyer1.sess $null
Check "订单 PAID 且类型 C2C" ($ord1.body.data.status -eq 'PAID' -and $ord1.body.data.orderType -eq 'C2C') "order=$($ord1.body.data.status) type=$($ord1.body.data.orderType)"

# ---------- 4. 发货 ----------
Write-Host "`n> 4. 卖家发货"
$sold1 = Call 'Get' "$GatewayApi/api/listings/orders/sold?page=1&pageSize=10" $sellerA.sess $null
$soldRow = @($sold1.body.data.items | Where-Object { $_.orderNo -eq $orderNo })[0]
Check "卖家A 卖出列表可见该单（买家已付款·待发货）" ($null -ne $soldRow -and $soldRow.status -eq 'PAID') "row=$($soldRow | ConvertTo-Json -Compress)"

$wrongShip = Call 'Post' "$GatewayApi/api/listings/orders/$orderNo/ship" $sellerB.sess @{ logisticsCompany = '冒名'; logisticsNo = 'X-1' }
Check "非本人发货被拒（403）" ($wrongShip.status -eq 403) "status=$($wrongShip.status)"

$ship = Call 'Post' "$GatewayApi/api/listings/orders/$orderNo/ship" $sellerA.sess @{ logisticsCompany = '顺丰速运'; logisticsNo = "SF-B11-$([guid]::NewGuid().ToString('N').Substring(0,8))" }
Check "卖家A 发货成功（SHIPPED）" ($ship.status -eq 200) "status=$($ship.status) body=$($ship.body | ConvertTo-Json -Compress)"

# ---------- 5. 确认收货 → 放款 ----------
Write-Host "`n> 5. 确认收货与放款"
$confirm = Call 'Post' "$GatewayApi/api/orders/$orderNo/confirm" $buyer1.sess $null
Check "买家1 确认收货（COMPLETED）" ($confirm.status -eq 200 -and $confirm.body.data.status -eq 'COMPLETED') "status=$($confirm.status)"
$set2 = Call 'Get' "$GatewayApi/api/orders/$orderNo/settlement" $buyer1.sess $null
Check "托管放款（RELEASED，货款结算给卖家）" ($set2.body.data.status -eq 'RELEASED') "settlement=$($set2.body.data.status)"

# ---------- 6. 退款释放链（闲置B） ----------
Write-Host "`n> 6. 未发货退款 → 托管退回 → 闲置释放（释放钩子）"
$idem2 = [guid]::NewGuid().ToString()
$placedB = Call 'Post' "$GatewayApi/api/listings/$listingB/orders" $buyer2.sess @{ receiverName = '担保买家2'; receiverPhone = $buyer2.phone; receiverAddress = '上海市静安区担保路 2 号'; idempotentKey = $idem2 }
$orderNoB = $placedB.body.data.orderNo
Check "买家2 对闲置B 下单成功" ($placedB.status -eq 200 -and $orderNoB) "status=$($placedB.status)"
$detailSold = Call 'Get' "$GatewayApi/api/listings/$listingB" $null $null
Check "售出后公开详情不可见（404）" ($detailSold.status -eq 404) "status=$($detailSold.status)"

$payB = Call 'Post' "$GatewayApi/api/orders/$orderNoB/pay" $buyer2.sess @{ channel = 'mock'; idempotentKey = [guid]::NewGuid().ToString() }
Check "买家2 支付闲置B" ($payB.status -eq 200) "status=$($payB.status)"

Start-Sleep -Seconds 2
$refund = Call 'Post' "$GatewayApi/api/orders/$orderNoB/refund" $buyer2.sess @{ reason = '担保冒烟：未发货退款' }
Check "未发货自助退款成功（REFUNDED）" ($refund.status -eq 200 -and $refund.body.data.orderStatus -eq 'REFUNDED') "status=$($refund.status) body=$($refund.body | ConvertTo-Json -Compress)"

$setB = Call 'Get' "$GatewayApi/api/orders/$orderNoB/settlement" $buyer2.sess $null
Check "托管资金退回买家（REFUNDED）" ($setB.body.data.status -eq 'REFUNDED') "settlement=$($setB.body.data.status)"

# 释放钩子是 AFTER_COMMIT 事件 + 跨域回调：给一点传播时间再验证
Start-Sleep -Seconds 2
$detailBack = Call 'Get' "$GatewayApi/api/listings/$listingB" $null $null
Check "闲置B 释放后公开详情恢复可见（200，SOLD→ON_SALE）" ($detailBack.status -eq 200) "status=$($detailBack.status)"

# ---------- 7. 收尾核对 ----------
Write-Host "`n> 7. 收尾核对"
$soldB = Call 'Get' "$GatewayApi/api/listings/orders/sold?page=1&pageSize=10" $sellerB.sess $null
$soldBRow = @($soldB.body.data.items | Where-Object { $_.orderNo -eq $orderNoB })[0]
Check "卖家B 卖出列表可见退款单（REFUNDED）" ($null -ne $soldBRow -and $soldBRow.status -eq 'REFUNDED') "row=$($soldBRow | ConvertTo-Json -Compress)"

$myOrders = Call 'Get' "$GatewayApi/api/orders?orderType=C2C&page=1&pageSize=10" $buyer1.sess $null
Check "买家订单列表按 C2C 过滤可见成交单" ($myOrders.status -eq 200 -and @($myOrders.body.data.items | Where-Object { $_.orderNo -eq $orderNo }).Count -eq 1) "status=$($myOrders.status)"

# 清理：删除两条冒烟闲置（软删，留证不污染公开列表）
Call 'Post' "$GatewayApi/api/listings/$listingA/delete" $sellerA.sess $null | Out-Null
Call 'Post' "$GatewayApi/api/listings/$listingB/delete" $sellerB.sess $null | Out-Null

Write-Host "`n=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
if ($fail -gt 0) { $failures | ForEach-Object { Write-Host "  - $_" }; exit 1 }
exit 0
