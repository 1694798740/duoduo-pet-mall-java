# 卖家中心（M5-9）端到端冒烟（Windows PowerShell 5.1）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\seller-smoke.ps1
#
# 前置：容器全部 healthy（docker compose up -d），且已 mvn package + docker compose build 出最新镜像。
#
# 覆盖：商家入驻（实名前置 + 幂等）、商家端准入、订单归属隔离（越权 403）、
#       卖家发货（资金仍托管）、卖家售后退款、已完成订单不可退、结算对账、平台自营可见性。
#
# 设计原则（沿用前几套脚本的教训）：
#   1. 夹具从接口动态取，不硬编码价格/库存；断言按「本次测试创建的订单号」限定范围；
#   2. 凡改库必先记录原值，结尾自证「库内数据已复原」；
#   3. 只改本脚本自己创建的订单，**绝不碰种子数据与历史订单**；
#   4. 归属夹具必须同时改 t_order 与 t_settlement 的 seller_id ——
#      只改订单会让「订单是我的、托管单却是平台的」这种自相矛盾的状态出现在测试里，
#      随之而来的断言失败会被误读成服务缺陷。
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    [string]$Trade = 'http://127.0.0.1:8083',
    [string]$Password = 'duoduo123',
    [int]$PlatformFeeBps = 100,
    [int]$SKU = 2001,
    [switch]$SkipDbCheck
)

$ErrorActionPreference = 'Continue'
$pass = 0
$fail = 0
$failures = @()

function Check([string]$name, [bool]$ok, $extra) {
    if ($ok) {
        $script:pass++
        Write-Host "  [PASS] $name"
    } else {
        $script:fail++
        $script:failures += $name
        if ($null -ne $extra) { Write-Host "  [FAIL] $name  -> $extra" } else { Write-Host "  [FAIL] $name" }
    }
}

function Call([string]$method, [string]$uri, $session, $bodyObj, [hashtable]$headers) {
    $params = @{ Uri = $uri; Method = $method; UseBasicParsing = $true; TimeoutSec = 25 }
    if ($session) { $params.WebSession = $session }
    if ($headers) { $params.Headers = $headers }
    if ($null -ne $bodyObj) {
        $params.Body = ($bodyObj | ConvertTo-Json -Compress -Depth 6)
        $params.ContentType = 'application/json; charset=utf-8'
    }
    try {
        $r = Invoke-WebRequest @params
        return @{ status = [int]$r.StatusCode; body = ($r.Content | ConvertFrom-Json) }
    } catch {
        $code = 0; $body = $null
        try { $code = [int]$_.Exception.Response.StatusCode.value__ } catch { }
        try { if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $body = $_.ErrorDetails.Message | ConvertFrom-Json } } catch { }
        if ($null -eq $body) {
            try {
                $st = $_.Exception.Response.GetResponseStream()
                if ($st) { $t = (New-Object System.IO.StreamReader($st)).ReadToEnd(); if ($t) { $body = $t | ConvertFrom-Json } }
            } catch { }
        }
        return @{ status = $code; body = $body }
    }
}

function NewSession { return New-Object Microsoft.PowerShell.Commands.WebRequestSession }

function Register([string]$phone, [string]$nickname) {
    $s = NewSession
    $r = Call 'Post' "$Gateway/api/auth/register" $s @{ phone = $phone; password = $Password; nickname = $nickname }
    if ($r.status -eq 409) { $r = Call 'Post' "$Gateway/api/auth/login" $s @{ phone = $phone; password = $Password } }
    return @{ session = $s; result = $r }
}

function Login([string]$phone) {
    $s = NewSession
    $r = Call 'Post' "$Gateway/api/auth/login" $s @{ phone = $phone; password = $Password }
    return @{ session = $s; result = $r }
}

function Sql([string]$db, [string]$sql) {
    $env:PATH = 'C:\Program Files\Docker\Docker\resources\bin;' + $env:PATH
    if (-not $env:DOCKER_CONFIG) { $env:DOCKER_CONFIG = 'D:\duoduo-infra\docker-nocreds' }
    $out = & docker exec duoduo-mysql mysql -uduoduo -pduoduo -N -B --default-character-set=utf8mb4 -D $db -e $sql 2>&1
    if ($LASTEXITCODE -ne 0) { return $null }
    return ((@($out) | Where-Object { "$_" -notmatch 'Warning' }) -join "`n").Trim()
}

function FeeOf([long]$amount, [int]$bps) {
    if ($amount -le 0 -or $bps -le 0) { return [long]0 }
    return [long][math]::Floor(([double]$amount * $bps + 5000) / 10000)
}

function NewKey { return [guid]::NewGuid().ToString() }

$recv = @{ receiverName = '钱多多'; receiverPhone = '13900000001'; receiverAddress = '上海市浦东新区张江路 1 号' }

$phoneBuyer = '139' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneSeller = '137' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneOther = '136' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneNew = '135' + (Get-Random -Minimum 10000000 -Maximum 99999999)

Write-Host ""
Write-Host "=== 多多宠物商店 · 卖家中心（M5-9）冒烟 @ $Gateway ==="
Write-Host "买家 $phoneBuyer ｜ 卖家 $phoneSeller ｜ 另一卖家 $phoneOther ｜ 新用户 $phoneNew"
Write-Host ""

# ---------- 0. 网关就绪 ----------
Write-Host "> 网关就绪"
$ready = $false
for ($i = 0; $i -lt 40; $i++) {
    try {
        $h = Invoke-WebRequest -Uri "$Gateway/actuator/health" -UseBasicParsing -TimeoutSec 5
        if ($h.StatusCode -eq 200) { $ready = $true; break }
    } catch { Start-Sleep -Seconds 1 }
}
Check "网关 /actuator/health 就绪" $ready
if (-not $ready) { Write-Host "网关未就绪，终止"; exit 1 }

# ---------- 1. 商家端准入 ----------
Write-Host ""
Write-Host "> 商家端准入"
$regNew = Register $phoneNew '新用户丙'
Check "新用户注册/登录成功（200）" ($regNew.result.status -eq 200) $regNew.result.status
$sessNew = $regNew.session

$anon = NewSession
$r = Call 'Get' "$Gateway/api/seller/summary" $anon $null
Check "未登录访问商家端概览返回 401（网关先拦一层）" ($r.status -eq 401) $r.status

$r = Call 'Get' "$Gateway/api/seller/summary" $sessNew $null
Check "★ 普通买家访问商家端概览返回 403" ($r.status -eq 403) $r.status
Check "403 响应为规范化错误结构（code=FORBIDDEN）" ($r.body.code -eq 'FORBIDDEN') $r.body.code
Check "拒绝原因说明需要先申请成为卖家" ($r.body.message -match '卖家') $r.body.message

$r = Call 'Get' "$Gateway/api/seller/orders" $sessNew $null
Check "普通买家访问卖出订单列表返回 403" ($r.status -eq 403) $r.status
$r = Call 'Get' "$Gateway/api/seller/settlements" $sessNew $null
Check "普通买家访问资金对账返回 403" ($r.status -eq 403) $r.status

# ---------- 2. 商家入驻：实名前置 + 幂等 ----------
Write-Host ""
Write-Host "> 商家入驻"
$r = Call 'Post' "$Gateway/api/users/me/merchant" $sessNew $null
Check "★ 未实名申请成为卖家被拒（422）" ($r.status -eq 422) $r.status
Check "拒绝原因说明需先完成实名" ($r.body.message -match '实名') $r.body.message

$r = Call 'Post' "$Gateway/api/users/me/realname" $sessNew @{ realName = '丙小明'; idCard = '310101199001011234' }
Check "（准备）新用户完成实名认证（200）" ($r.status -eq 200) $r.status

$r = Call 'Post' "$Gateway/api/users/me/merchant" $sessNew $null
Check "★ 实名后申请成为卖家成功（200）" ($r.status -eq 200) $r.status
Check "角色已升级为 SELLER" ($r.body.data.role -eq 'SELLER') $r.body.data.role

$r = Call 'Post' "$Gateway/api/users/me/merchant" $sessNew $null
Check "重复申请成为卖家幂等（200，不报错）" ($r.status -eq 200) $r.status

# 角色在 access token 里是快照，必须重新登录才能带到下游服务
$reNew = Login $phoneNew
Check "（准备）重新登录成功（刷新令牌内的角色快照）" ($reNew.result.status -eq 200) $reNew.result.status
$sessNew = $reNew.session
$r = Call 'Get' "$Gateway/api/seller/summary" $sessNew $null
Check "★ 重新登录后商家端概览返回 200" ($r.status -eq 200) $r.status
Check "概览回显卖家 ID 与新用户一致" ([long]$r.body.data.sellerId -eq [long]$reNew.result.body.data.id) "$($r.body.data.sellerId) vs $($reNew.result.body.data.id)"
Check "普通卖家不含平台自营范围（platformScope=false）" ($r.body.data.platformScope -eq $false) $r.body.data.platformScope

# ---------- 3. 夹具：买家下单支付 ----------
Write-Host ""
Write-Host "> 夹具：买家下单并支付"
$batch = Call 'Get' "$Gateway/api/products/skus/batch?ids=$SKU" $null $null
$skuInfo = @($batch.body.data)[0]
$P1 = [long]$skuInfo.price
$stockBefore = [int](Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU;")
Write-Host "  SKU $SKU 单价 $P1 可售 $stockBefore"
if ($P1 -le 0 -or $stockBefore -lt 4) {
    Write-Host "夹具前提不满足，终止本次测试，不执行任何写操作。"
    Check "夹具前提：价格与库存充足" $false "P1=$P1 stock=$stockBefore"
    Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
    exit 1
}
Check "夹具前提：SKU $SKU 库存 ≥ 4" ($stockBefore -ge 4) $stockBefore

$regBuyer = Register $phoneBuyer '买家甲'
Check "买家注册/登录成功（200）" ($regBuyer.result.status -eq 200) $regBuyer.result.status
$sessBuyer = $regBuyer.session

function PlaceAndPay($session, $sku, $qty, $label) {
    Call 'Post' "$Gateway/api/cart/items" $session @{ skuId = $sku; quantity = $qty } | Out-Null
    $o = Call 'Post' "$Gateway/api/orders" $session ($recv + @{ idempotentKey = (NewKey) })
    if ($o.status -ne 200) { return @{ ok = $false; orderNo = $null; pay = $null; step = "下单 $label" } }
    $orderNo = @($o.body.data.orders)[0].orderNo
    $p = Call 'Post' "$Gateway/api/orders/$orderNo/pay" $session @{ channel = 'MOCK'; idempotentKey = (NewKey) }
    if ($p.status -ne 200) { return @{ ok = $false; orderNo = $orderNo; pay = $null; step = "支付 $label" } }
    return @{ ok = $true; orderNo = $orderNo; pay = $p.body.data; step = $null }
}

$c1 = PlaceAndPay $sessBuyer $SKU 1 '订单A'
Check "（准备）订单 A 下单并支付成功（200）" $c1.ok $c1.step
$onA = $c1.orderNo
$payA = [long]$c1.pay.amount
$feeA = FeeOf $payA $PlatformFeeBps

$c2 = PlaceAndPay $sessBuyer $SKU 1 '订单B'
Check "（准备）订单 B 下单并支付成功（200）" $c2.ok $c2.step
$onB = $c2.orderNo

if (-not $onA -or -not $onB) {
    Write-Host "订单夹具创建失败，终止。"
    Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
    exit 1
}

# ---------- 4. 归属夹具：把订单 A 划给卖家 ----------
Write-Host ""
Write-Host "> 归属夹具：把订单 A / B 划给卖家（记录原值，结尾复原）"
$regSeller = Register $phoneSeller '卖家乙'
Check "卖家注册/登录成功（200）" ($regSeller.result.status -eq 200) $regSeller.result.status
$r = Call 'Post' "$Gateway/api/users/me/realname" $regSeller.session @{ realName = '乙小商'; idCard = '310101199002022345' }
Check "（准备）卖家完成实名（200）" ($r.status -eq 200) $r.status
$r = Call 'Post' "$Gateway/api/users/me/merchant" $regSeller.session $null
Check "（准备）卖家入驻成功（200）" ($r.status -eq 200) $r.status
$reSeller = Login $phoneSeller
$sessSeller = $reSeller.session
$sellerId = [long]$reSeller.result.body.data.id

$regOther = Register $phoneOther '另一卖家丁'
Call 'Post' "$Gateway/api/users/me/realname" $regOther.session @{ realName = '丁小二'; idCard = '310101199003033456' } | Out-Null
Call 'Post' "$Gateway/api/users/me/merchant" $regOther.session $null | Out-Null
$reOther = Login $phoneOther
$sessOther = $reOther.session
Check "（准备）另一卖家入驻成功且已刷新登录态" ($reOther.result.status -eq 200) $reOther.result.status

if (-not $SkipDbCheck) {
    # 归属夹具必须订单与托管单一起改：两表的 seller_id 是同一事实的两份记录，
    # 只改其一会制造出「订单是我的、钱却是平台的」这种自相矛盾的状态。
    Sql 'duoduo_trade' "UPDATE t_order o LEFT JOIN t_settlement s ON s.order_id = o.id
         SET o.seller_id = $sellerId, s.seller_id = $sellerId
         WHERE o.order_no IN ('$onA','$onB');" | Out-Null
    Check "（夹具）订单 A 的 seller_id 已置为卖家 $sellerId" ((Sql 'duoduo_trade' "SELECT seller_id FROM t_order WHERE order_no='$onA';") -eq "$sellerId")
    Check "（夹具）订单 A 的托管单 seller_id 同步一致" ((Sql 'duoduo_trade' "SELECT s.seller_id FROM t_settlement s JOIN t_order o ON o.id=s.order_id WHERE o.order_no='$onA';") -eq "$sellerId")
}

# ---------- 5. 归属隔离 ----------
Write-Host ""
Write-Host "> 归属隔离（越权必须 403）"
$r = Call 'Get' "$Gateway/api/seller/orders" $sessSeller $null
$mineA = @($r.body.data.items | Where-Object { $_.orderNo -eq $onA })
$mineB = @($r.body.data.items | Where-Object { $_.orderNo -eq $onB })
Check "★ 卖家能看到属于自己的订单 A" ($mineA.Count -eq 1) $mineA.Count
Check "★ 卖家能看到属于自己的订单 B" ($mineB.Count -eq 1) $mineB.Count

$r = Call 'Get' "$Gateway/api/seller/orders" $sessOther $null
$otherSees = @($r.body.data.items | Where-Object { $_.orderNo -eq $onA -or $_.orderNo -eq $onB })
Check "★ 另一卖家的订单列表看不到 A / B" ($otherSees.Count -eq 0) $otherSees.Count

$r = Call 'Get' "$Gateway/api/seller/orders/$onA" $sessOther $null
Check "★ 另一卖家查看订单 A 详情返回 403" ($r.status -eq 403) $r.status
# 注意：带 @RequestBody 的 POST 必须显式发空 JSON 对象，不能发「无 body」。
# PowerShell 的 Invoke-WebRequest 在无 body 的 POST 上会带
# Content-Type: application/x-www-form-urlencoded，服务端因此返回 415 ——
# 那是**客户端**发错了格式，把它断言成 403 会把测试意图彻底带偏。
$r = Call 'Post' "$Gateway/api/seller/orders/$onA/ship" $sessOther @{}
Check "★ 另一卖家对订单 A 发货返回 403" ($r.status -eq 403) $r.status
$r = Call 'Post' "$Gateway/api/seller/orders/$onA/refund" $sessOther @{ reason = '越权退款' }
Check "★ 另一卖家对订单 A 退款返回 403" ($r.status -eq 403) $r.status

$r = Call 'Get' "$Gateway/api/seller/orders/NOTEXIST$((Get-Random -Maximum 999999))" $sessSeller $null
Check "不存在的订单号返回 404（与 403 区分）" ($r.status -eq 404) $r.status

# ---------- 6. 卖家发货 ----------
Write-Host ""
Write-Host "> 卖家发货"
$r = Call 'Get' "$Gateway/api/seller/orders/$onA" $sessSeller $null
Check "卖家查看订单 A 详情返回 200" ($r.status -eq 200) $r.status
Check "订单 A 当前为 PAID（待发货）" ($r.body.data.status -eq 'PAID') $r.body.data.status

$r = Call 'Post' "$Gateway/api/seller/orders/$onA/ship" $sessSeller @{ logisticsCompany = '顺丰速运'; logisticsNo = 'SF1234567890' }
Check "★ 卖家发货成功（200，无需内部凭据）" ($r.status -eq 200) $r.status
Check "发货后订单状态为 SHIPPED" ($r.body.data.status -eq 'SHIPPED') $r.body.data.status
Check "★ 发货不等于放款：托管仍为 CUSTODY" ($r.body.data.settlement.status -eq 'CUSTODY') $r.body.data.settlement.status
# 物流必须落库并回显：只写进状态日志的话，卖家事后在详情页看不到自己填的单号，
# 买家来问「我的货到哪了」时系统给不出答案。
Check "★ 订单视图回显物流公司与单号" (($r.body.data.logisticsCompany -eq '顺丰速运') -and ($r.body.data.logisticsNo -eq 'SF1234567890')) "$($r.body.data.logisticsCompany)/$($r.body.data.logisticsNo)"

if (-not $SkipDbCheck) {
    Check "物流信息随状态日志留痕（含顺丰速运）" ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_order_status_log WHERE order_id=(SELECT id FROM t_order WHERE order_no='$onA') AND to_status='SHIPPED' AND reason LIKE '%顺丰速运%';") -eq '1')
    Check "发货日志操作者类型为 SELLER" ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_order_status_log WHERE order_id=(SELECT id FROM t_order WHERE order_no='$onA') AND to_status='SHIPPED' AND operator_type='SELLER';") -eq '1')
    # 只比 ASCII 的单号：docker exec 的中文输出在 PowerShell 里按 ANSI 解析会乱码，
    # 断言中文必然假失败（视图里那条 200 回显已经证明了中文没问题）
    Check "★ 物流单号已落库 t_order（不是只写进日志）" ((Sql 'duoduo_trade' "SELECT IFNULL(logistics_no,'') FROM t_order WHERE order_no='$onA';") -eq 'SF1234567890')
}

$r = Call 'Post' "$Gateway/api/seller/orders/$onA/ship" $sessSeller @{}
Check "重复发货被拒（422，状态机不允许 SHIPPED→SHIPPED）" ($r.status -eq 422) $r.status

# 顺带验证「客户端发错 Content-Type」现在被归为 4xx 而不是 5xx：
# 5xx 会把「你发错了格式」说成「我坏了」，还会污染服务告警。
$r = Call 'Post' "$Gateway/api/seller/orders/$onA/ship" $sessSeller $null @{ 'Content-Type' = 'application/x-www-form-urlencoded' }
Check "★ 错误的 Content-Type 返回 4xx（415），不是 500" ($r.status -eq 415) $r.status

# ---------- 7. 已完成订单不可退款 ----------
Write-Host ""
Write-Host "> 已完成订单的退款边界（钱已放款给卖家）"
# 必须先发货：确认收货只能从 SHIPPED 流转，PAID 直接确认收货会被状态机拒绝（422）。
$r = Call 'Post' "$Gateway/api/seller/orders/$onB/ship" $sessSeller @{ logisticsNo = 'SF-B-0001' }
Check "（准备）卖家对订单 B 发货（200）" ($r.status -eq 200) $r.status
$r = Call 'Post' "$Gateway/api/orders/$onB/confirm" $sessBuyer $null
Check "买家先对订单 B 确认收货（200）" ($r.status -eq 200) $r.status
Check "订单 B 已放款（RELEASED）" ($r.body.data.settlement.status -eq 'RELEASED') $r.body.data.settlement.status
$r = Call 'Post' "$Gateway/api/seller/orders/$onB/refund" $sessSeller @{ reason = '已完成订单尝试退款' }
Check "★ 已完成订单卖家退款被拒（422）" ($r.status -eq 422) $r.status
Check "拒绝原因说明需走追偿或平台垫付" ($r.body.message -match '追偿|垫付') $r.body.message
$r = Call 'Get' "$Gateway/api/seller/orders/$onB" $sessSeller $null
Check "被拒后托管单仍为 RELEASED（未被改写）" ($r.body.data.settlement.status -eq 'RELEASED') $r.body.data.settlement.status

# ---------- 8. 卖家售后退款（已发货） ----------
Write-Host ""
Write-Host "> 卖家售后退款（已发货，卖家已确认收到退货）"
$stockBeforeRefund = [int](Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU;")
$r = Call 'Post' "$Gateway/api/seller/orders/$onA/refund" $sessSeller @{ reason = '买家退回，商品完好' }
Check "★ 卖家售后退款成功（200）" ($r.status -eq 200) $r.status
Check "订单转为 REFUNDED" ($r.body.data.orderStatus -eq 'REFUNDED') $r.body.data.orderStatus
Check "退款金额 = 买家实付（$payA 分）" ([long]$r.body.data.refundAmount -eq $payA) $r.body.data.refundAmount
Check "★ 资金已退回买家（fundsRefunded=true）" ($r.body.data.fundsRefunded -eq $true) $r.body.data.fundsRefunded
Check "托管状态为 REFUNDED" ($r.body.data.settlementStatus -eq 'REFUNDED') $r.body.data.settlementStatus

$r = Call 'Post' "$Gateway/api/seller/orders/$onA/refund" $sessSeller @{ reason = '再退一次' }
Check "重复退款被拒（422）" ($r.status -eq 422) $r.status

if (-not $SkipDbCheck) {
    Check "退款日志操作者类型为 SELLER" ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_order_status_log WHERE order_id=(SELECT id FROM t_order WHERE order_no='$onA') AND to_status='REFUNDED' AND operator_type='SELLER';") -eq '1')
    # 触发补偿调度完成退货入库
    Call 'Post' "$Gateway/api/orders/internal/sweep" $sessBuyer $null @{ 'X-Internal-Token' = 'duoduo-local-dev-internal-token' } | Out-Null
    Check "（售后）台账已置 RESTOCKED（货已回库）" ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_stock_freeze WHERE order_id=(SELECT id FROM t_order WHERE order_no='$onA') AND status='RESTOCKED';") -eq '1')
    Check "（售后）库存已回补（$stockBeforeRefund → $(Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU;")）" ((Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU;") -eq "$($stockBeforeRefund + 1)")
}

# ---------- 9. 结算对账 ----------
Write-Host ""
Write-Host "> 结算对账"
$r = Call 'Get' "$Gateway/api/seller/settlements?pageSize=50" $sessSeller $null
Check "资金对账返回 200" ($r.status -eq 200) $r.status
Check "★ 对账包含订单 A（已退款）" ((@($r.body.data.items | Where-Object { $_.orderNo -eq $onA }).Count) -eq 1)
Check "★ 对账包含订单 B（已放款）" ((@($r.body.data.items | Where-Object { $_.orderNo -eq $onB }).Count) -eq 1)
Check "汇总：已退款金额 ≥ 订单 A 实付（$payA）" ([long]$r.body.data.refundedAmount -ge $payA) $r.body.data.refundedAmount
Check "汇总：已到账金额 ≥ 0 且为整数分" ([long]$r.body.data.releasedSellerAmount -ge 0) $r.body.data.releasedSellerAmount

$r2 = Call 'Get' "$Gateway/api/seller/settlements?status=REFUNDED" $sessSeller $null
Check "按状态过滤 REFUNDED 只返回已退款流水" ((@($r2.body.data.items | Where-Object { $_.status -ne 'REFUNDED' }).Count) -eq 0)
Check "★ 过滤后全量汇总不变（仍是 $($r.body.data.refundedAmount)）" ([long]$r2.body.data.refundedAmount -eq [long]$r.body.data.refundedAmount) "$($r2.body.data.refundedAmount) vs $($r.body.data.refundedAmount)"

$r = Call 'Get' "$Gateway/api/seller/settlements?status=BAD_STATUS" $sessSeller $null
Check "非法资金状态返回 400" ($r.status -eq 400) $r.status
$r = Call 'Get' "$Gateway/api/seller/orders?status=BAD_STATUS" $sessSeller $null
Check "非法订单状态返回 400" ($r.status -eq 400) $r.status

$r = Call 'Get' "$Gateway/api/seller/settlements?pageSize=50" $sessOther $null
Check "★ 另一卖家的对账不含 A / B" ((@($r.body.data.items | Where-Object { $_.orderNo -eq $onA -or $_.orderNo -eq $onB }).Count) -eq 0)

# ---------- 10. 概览口径 ----------
Write-Host ""
Write-Host "> 概览口径"
$r = Call 'Get' "$Gateway/api/seller/summary" $sessSeller $null
Check "概览返回 200" ($r.status -eq 200) $r.status
Check "概览统计到 2 笔订单（A + B）" ([long]$r.body.data.totalOrderCount -ge 2) $r.body.data.totalOrderCount
Check "已退款计数 ≥ 1" ([long]$r.body.data.refundedCount -ge 1) $r.body.data.refundedCount
Check "已完成计数 ≥ 1" ([long]$r.body.data.completedCount -ge 1) $r.body.data.completedCount

# ---------- 11. 平台自营可见性（运营 / 管理员） ----------
Write-Host ""
Write-Host "> 平台自营可见性（运营代运营）"
if ($SkipDbCheck) {
    Write-Host "  [SKIP] 已指定 -SkipDbCheck"
} else {
    $otherId = [long]$reOther.result.body.data.id
    Sql 'duoduo_user' "UPDATE t_user SET role = 'OPERATOR' WHERE id = $otherId;" | Out-Null
    $reOp = Login $phoneOther
    $sessOp = $reOp.session
    $r = Call 'Get' "$Gateway/api/seller/summary" $sessOp $null
    Check "（准备）运营账号已生效（重新登录刷新角色快照）" ($r.status -eq 200) $r.status
    Check "★ 运营的可见范围含平台自营（platformScope=true）" ($r.body.data.platformScope -eq $true) $r.body.data.platformScope

    # 把订单 A 的归属复原为平台自营（0），运营应能看到，普通卖家看不到
    Sql 'duoduo_trade' "UPDATE t_order o LEFT JOIN t_settlement s ON s.order_id = o.id
         SET o.seller_id = 0, s.seller_id = 0 WHERE o.order_no = '$onA';" | Out-Null
    $r = Call 'Get' "$Gateway/api/seller/orders?pageSize=50" $sessOp $null
    Check "★ 运营能看到平台自营订单 A" ((@($r.body.data.items | Where-Object { $_.orderNo -eq $onA }).Count) -eq 1)
    $r = Call 'Get' "$Gateway/api/seller/orders/$onA" $sessSeller $null
    Check "★ 复原归属后，普通卖家看不到订单 A（403）" ($r.status -eq 403) $r.status

    Sql 'duoduo_user' "UPDATE t_user SET role = 'SELLER' WHERE id = $otherId;" | Out-Null
    Check "（复原）另一卖家角色已改回 SELLER" ((Sql 'duoduo_user' "SELECT role FROM t_user WHERE id = $otherId;") -eq 'SELLER')
}

# ---------- 12. 复原夹具 ----------
Write-Host ""
Write-Host "> 复原夹具"
if ($SkipDbCheck) {
    Write-Host "  [SKIP] 已指定 -SkipDbCheck"
} else {
    Sql 'duoduo_trade' "UPDATE t_order o LEFT JOIN t_settlement s ON s.order_id = o.id
         SET o.seller_id = 0, s.seller_id = 0 WHERE o.order_no IN ('$onA','$onB');" | Out-Null
    Check "订单 A / B 的 seller_id 已复原为 0" ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_order WHERE order_no IN ('$onA','$onB') AND seller_id <> 0;") -eq '0')
    Check "托管单 seller_id 已同步复原为 0" ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_settlement s JOIN t_order o ON o.id=s.order_id WHERE o.order_no IN ('$onA','$onB') AND s.seller_id <> 0;") -eq '0')
    Sql 'duoduo_product' "UPDATE t_sku SET stock = $stockBefore, frozen_stock = 0 WHERE id = $SKU;" | Out-Null
    Check "SKU $SKU 库存已复原为 $stockBefore" ((Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU;") -eq "$stockBefore")
    Check "SKU $SKU 冻结量为 0" ((Sql 'duoduo_product' "SELECT COALESCE(frozen_stock,0) FROM t_sku WHERE id = $SKU;") -eq '0')
}

# ---------- 汇总 ----------
Write-Host ""
Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
if ($fail -gt 0) {
    Write-Host "失败项："
    foreach ($f in $failures) { Write-Host "  - $f" }
    exit 1
}
exit 0
