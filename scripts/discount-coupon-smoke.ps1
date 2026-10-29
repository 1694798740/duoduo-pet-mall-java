# 折扣券（E-05）端到端冒烟
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\discount-coupon-smoke.ps1
#
# 前置：17 个容器 healthy；marketing 已部署 E-05 镜像；docs/sql/incremental/60-discount-coupon.sql 已执行。
#
# 覆盖的验收点（与满减券 coupon-smoke.ps1 互补，不重复它的幂等 / 并发用例）：
#   ① 折扣券可建：DISCOUNT + 折后比例（85 = 8.5 折）+ 可选封顶；
#   ② 两种券并存：同一订单金额下按「本单实际可抵金额」排序，最优券可能从满减券切换为折扣券；
#   ③ 金额口径：floor(订单金额 × (100 − 折后比例) / 100)，封顶生效，且不超过订单金额；
#   ④ 一单一券：下单接口只认单个 couponCode，多券字段不会被叠加；
#   ⑤ 反向：混填固定额 / 缺比例 / 比例越界 / 未知类型全部拒绝，提示可读；
#   ⑥ 小额订单用折扣券减不到 1 分时，明确拒绝而不是「用了等于没用」。
#
# 设计原则沿用前几套脚本：夹具走运营接口创建（顺带验收建券链路），结尾按 id 清理；
# 断言只用业务键（券码 / 订单号 / 金额），不拿 19 位雪花 id 做 JSON 比较。
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    [string]$Marketing = 'http://127.0.0.1:8085',
    [string]$Password = 'duoduo123',
    [string]$OperatorPhone = '13684295254',
    [string]$OperatorPassword = 'duoduo12345',
    [string]$InternalToken = 'duoduo-local-dev-internal-token',
    [int]$SKU = 2001
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

. (Join-Path $PSScriptRoot 'lib\internal-token.ps1')

function Call([string]$method, [string]$uri, $session, $bodyObj, [hashtable]$headers) {
    $params = @{ Uri = $uri; Method = $method; UseBasicParsing = $true; TimeoutSec = 25 }
    if ($session) { $params.WebSession = $session }
    if ($headers -and $headers.ContainsKey('X-Internal-Token') -and $headers['X-Internal-Token'] -eq 'internal') {
        $headers = @{} + $headers
        $headers['X-Internal-Token'] = (New-InternalToken -Method $method -Path ([System.Uri]$uri).AbsolutePath)
    }
    if ($headers) { $params.Headers = $headers }
    if ($null -ne $bodyObj) {
        $params.Body = ($bodyObj | ConvertTo-Json -Compress -Depth 8)
        $params.ContentType = 'application/json; charset=utf-8'
    }
    try {
        $r = Invoke-WebRequest @params
        return @{ status = [int]$r.StatusCode; body = ($r.Content | ConvertFrom-Json); raw = $r.Content }
    } catch {
        $code = 0; $body = $null; $raw = $null
        try { $code = [int]$_.Exception.Response.StatusCode.value__ } catch { }
        try { if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $raw = $_.ErrorDetails.Message; $body = $raw | ConvertFrom-Json } } catch { }
        if ($null -eq $body) {
            try {
                $resp = $_.Exception.Response
                $t = $null
                if ($resp -and $resp.PSObject.Methods.Name -contains 'GetResponseStream') {
                    $st = $resp.GetResponseStream()
                    if ($st) { $t = (New-Object System.IO.StreamReader($st)).ReadToEnd() }
                } elseif ($resp -and $null -ne $resp.Content) {
                    $t = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                }
                if ($t) { $raw = $t; $body = $t | ConvertFrom-Json }
            } catch { }
        }
        return @{ status = $code; body = $body; raw = $raw }
    }
}

function Register([string]$phone, [string]$nickname) {
    $s = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $r = Call 'Post' "$Gateway/api/auth/register" $s @{ phone = $phone; password = $Password; nickname = $nickname }
    if ($r.status -eq 409) { $r = Call 'Post' "$Gateway/api/auth/login" $s @{ phone = $phone; password = $Password } }
    return @{ session = $s; result = $r }
}

function Sql([string]$db, [string]$sql) {
    $env:PATH = 'C:\Program Files\Docker\Docker\resources\bin;' + $env:PATH
    if (-not $env:DOCKER_CONFIG) { $env:DOCKER_CONFIG = 'D:\duoduo-infra\docker-nocreds' }
    $out = & docker exec duoduo-mysql mysql -uduoduo -pduoduo -N -B --default-character-set=utf8mb4 -D $db -e $sql 2>&1
    if ($LASTEXITCODE -ne 0) { return $null }
    return ((@($out) | Where-Object { "$_" -notmatch 'Warning' }) -join "`n").Trim()
}

function NewKey { return [guid]::NewGuid().ToString() }
function NewPhone([string]$prefix) { return $prefix + (Get-Random -Minimum 10000000 -Maximum 99999999) }

function Claim($sess, $tplId) {
    return (Call 'Post' "$Gateway/api/coupons/templates/$tplId/claim" $sess $null)
}

function CouponStatusOf([string]$code) {
    return (Sql 'duoduo_marketing' "SELECT status FROM t_coupon WHERE code = '$code';")
}

function CreateTemplate($sess, [string]$name, [hashtable]$body) {
    return (Call 'Post' "$Gateway/api/coupons/admin/templates" $sess $body)
}

function PlaceOrder($sess, [string]$code, [int]$qty) {
    Call 'Post' "$Gateway/api/cart/items" $sess @{ skuId = $SKU; quantity = $qty } | Out-Null
    $body = @{
        receiverName    = '小林'
        receiverPhone   = '13900000000'
        receiverAddress = '上海市浦东新区张江路 1 号'
        idempotentKey   = (NewKey)
    }
    if ($code) { $body['couponCode'] = $code }
    return (Call 'Post' "$Gateway/api/orders" $sess $body)
}

# ---------------------------------------------------------------------------
# 0. 就绪
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "=== 多多宠物商店 · 折扣券（E-05）冒烟 @ $Gateway ==="

$ready = $false
for ($i = 0; $i -lt 40; $i++) {
    try {
        $h = Invoke-WebRequest -Uri "$Gateway/actuator/health" -UseBasicParsing -TimeoutSec 5
        if ($h.StatusCode -eq 200) { $ready = $true; break }
    } catch { Start-Sleep -Seconds 1 }
}
Check "网关 /actuator/health 就绪" $ready
if (-not $ready) { Write-Host "网关未就绪，终止"; exit 1 }

$mktReady = $false
for ($i = 0; $i -lt 40; $i++) {
    try {
        $h = Invoke-WebRequest -Uri "$Marketing/actuator/health" -UseBasicParsing -TimeoutSec 5
        if ($h.StatusCode -eq 200) { $mktReady = $true; break }
    } catch { Start-Sleep -Seconds 1 }
}
Check "营销服务 /actuator/health 就绪" $mktReady
if (-not $mktReady) { Write-Host "营销服务未就绪，终止"; exit 1 }

# schema 自检：老库没执行 60 迁移时，下面会以「Unknown column」连片失败，先把原因说出来
$col = Sql 'duoduo_marketing' "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='duoduo_marketing' AND table_name='t_coupon_template' AND column_name IN ('discount_percent','max_discount_amount');"
Check "（前置）60-discount-coupon.sql 已执行（两列存在）" ("$col" -eq '2') "实际列数=$col"

# ---------------------------------------------------------------------------
# 1. 夹具：运营建券（顺带验收建券链路）
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "> 运营建折扣券"
$opSess = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$rOpLogin = Call 'Post' "$Gateway/api/auth/login" $opSess @{ phone = $OperatorPhone; password = $OperatorPassword }
Check "运营账号登录成功" ($rOpLogin.status -eq 200) "$($rOpLogin.status)/$($rOpLogin.raw)"
if ($rOpLogin.status -ne 200) { Write-Host "运营登录失败，终止（后续建券用例无法执行）"; exit 1 }

$tplCap = @{
    name            = '冒烟-8.5折封顶20'
    description     = '冒烟夹具（15% off，最高减 20 元）'
    type            = 'DISCOUNT'
    thresholdAmount = 10000
    discountAmount  = 0
    discountPercent = 85
    maxDiscountAmount = 2000
    totalCount      = 20
    startAt         = '2025-01-01T00:00:00'
    endAt           = '2035-12-31T00:00:00'
}
$rCap = CreateTemplate $opSess '冒烟-8.5折封顶20' $tplCap
Check "① 运营创建折扣券（封顶）成功" ($rCap.status -eq 200) "$($rCap.status)/$($rCap.raw)"
$TPL_CAP = [long]$rCap.body.data.id
Check "① 类型回显 DISCOUNT" ($rCap.body.data.type -eq 'DISCOUNT') $rCap.body.data.type
Check "① 类型中文文案为「折扣券」" ($rCap.body.data.typeLabel -eq '折扣券') $rCap.body.data.typeLabel
Check "① 折后比例回显 85（8.5 折）" ($rCap.body.data.discountPercent -eq 85) $rCap.body.data.discountPercent
Check "① 封顶回显 2000 分" ($rCap.body.data.maxDiscountAmount -eq 2000) $rCap.body.data.maxDiscountAmount
Check "① 折扣券的固定抵扣额为 0（两种口径不混用）" ($rCap.body.data.discountAmount -eq 0) $rCap.body.data.discountAmount

$tplFree = @{
    name            = '冒烟-无门槛8折'
    description     = '冒烟夹具（20% off，不封顶）'
    type            = 'DISCOUNT'
    thresholdAmount = 0
    discountAmount  = 0
    discountPercent = 80
    totalCount      = 20
    startAt         = '2025-01-01T00:00:00'
    endAt           = '2035-12-31T00:00:00'
}
$rFree = CreateTemplate $opSess '冒烟-无门槛8折' $tplFree
Check "① 运营创建折扣券（无门槛不封顶）成功" ($rFree.status -eq 200) "$($rFree.status)/$($rFree.raw)"
$TPL_FREE = [long]$rFree.body.data.id
Check "① 未填封顶时默认 0（不封顶）" ($rFree.body.data.maxDiscountAmount -eq 0) $rFree.body.data.maxDiscountAmount
Check "① 库内折后比例落对（85 / 80）" ("$(Sql 'duoduo_marketing' "SELECT CONCAT(discount_percent,'/',max_discount_amount) FROM t_coupon_template WHERE id = $TPL_CAP;")" -eq '85/2000') (Sql 'duoduo_marketing' "SELECT CONCAT(discount_percent,'/',max_discount_amount) FROM t_coupon_template WHERE id = $TPL_CAP;")

# ---------------------------------------------------------------------------
# 2. 反向：参数边界（用户拍板的两种券不能混填）
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "> 反向：建券参数边界"
$rMix = CreateTemplate $opSess '冒烟-混填' @{ name = '冒烟-混填'; type = 'DISCOUNT'; thresholdAmount = 0; discountAmount = 1000; discountPercent = 85; totalCount = 1; startAt = '2025-01-01T00:00:00'; endAt = '2035-12-31T00:00:00' }
Check "折扣券同时给固定抵扣额 → 400" ($rMix.status -eq 400) "$($rMix.status)/$($rMix.raw)"
Check "混填提示点明 discountAmount 必须为 0" ($rMix.body.message -match 'discountAmount 必须为 0') $rMix.body.message

$rNoPct = CreateTemplate $opSess '冒烟-缺比例' @{ name = '冒烟-缺比例'; type = 'DISCOUNT'; thresholdAmount = 0; discountAmount = 0; totalCount = 1; startAt = '2025-01-01T00:00:00'; endAt = '2035-12-31T00:00:00' }
Check "折扣券缺折后比例 → 400" ($rNoPct.status -eq 400) "$($rNoPct.status)/$($rNoPct.raw)"
Check "缺比例提示包含取值区间" ($rNoPct.body.message -match '折后比例') $rNoPct.body.message

$rPct100 = CreateTemplate $opSess '冒烟-比例100' @{ name = '冒烟-比例100'; type = 'DISCOUNT'; thresholdAmount = 0; discountAmount = 0; discountPercent = 100; totalCount = 1; startAt = '2025-01-01T00:00:00'; endAt = '2035-12-31T00:00:00' }
Check "折后比例 100（等于不打折）→ 400" ($rPct100.status -eq 400) "$($rPct100.status)/$($rPct100.raw)"

$rPct0 = CreateTemplate $opSess '冒烟-比例0' @{ name = '冒烟-比例0'; type = 'DISCOUNT'; thresholdAmount = 0; discountAmount = 0; discountPercent = 0; totalCount = 1; startAt = '2025-01-01T00:00:00'; endAt = '2035-12-31T00:00:00' }
Check "折后比例 0（等于白送）→ 400" ($rPct0.status -eq 400) "$($rPct0.status)/$($rPct0.raw)"

$rUnknown = CreateTemplate $opSess '冒烟-未知类型' @{ name = '冒烟-未知类型'; type = 'BUY_ONE_GET_ONE'; thresholdAmount = 0; discountAmount = 100; totalCount = 1; startAt = '2025-01-01T00:00:00'; endAt = '2035-12-31T00:00:00' }
Check "未知券类型 → 422" ($rUnknown.status -eq 422) "$($rUnknown.status)/$($rUnknown.raw)"
Check "未知类型提示列出当前支持的类型" (($rUnknown.body.message -match 'FULL_REDUCE') -and ($rUnknown.body.message -match 'DISCOUNT')) $rUnknown.body.message

# ---------------------------------------------------------------------------
# 3. 买家领取与券包
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "> 买家领取折扣券"
$phone = NewPhone '134'
$reg = Register $phone '折扣券测试买家'
Check "买家注册/登录成功" ($reg.result.status -eq 200) $reg.result.status
$sess = $reg.session
$idBuyer = Sql 'duoduo_user' "SELECT id FROM t_user WHERE phone = '$phone';"
Check "（前置）取得买家 userId" (-not [string]::IsNullOrEmpty($idBuyer)) $idBuyer

$rClaimCap = Claim $sess $TPL_CAP
Check "② 领取「8.5 折封顶」券成功" ($rClaimCap.status -eq 200) "$($rClaimCap.status)/$($rClaimCap.raw)"
$CODE_CAP = $rClaimCap.body.data.code
$rClaimFree = Claim $sess $TPL_FREE
Check "② 领取「无门槛 8 折」券成功" ($rClaimFree.status -eq 200) "$($rClaimFree.status)/$($rClaimFree.raw)"
$CODE_FREE = $rClaimFree.body.data.code
Check "② 两张券码不同" ($CODE_CAP -ne $CODE_FREE) "$CODE_CAP / $CODE_FREE"

$rMine = Call 'Get' "$Gateway/api/coupons/mine?status=UNUSED" $sess $null
Check "② 我的券包返回 200" ($rMine.status -eq 200) "$($rMine.status)/$($rMine.raw)"
$mineCap = @($rMine.body.data | Where-Object { $_.code -eq $CODE_CAP })[0]
$mineFree = @($rMine.body.data | Where-Object { $_.code -eq $CODE_FREE })[0]
Check "② 券包回显类型「折扣券」" ($mineCap.typeLabel -eq '折扣券') $mineCap.typeLabel
Check "② 券包回显折后比例 85" ($mineCap.discountPercent -eq 85) $mineCap.discountPercent
Check "② 券包回显封顶 2000" ($mineCap.maxDiscountAmount -eq 2000) $mineCap.maxDiscountAmount
Check "② 券包回显门槛 10000（折扣券同样受门槛约束）" ($mineCap.thresholdAmount -eq 10000) $mineCap.thresholdAmount
Check "② 无门槛券的门槛为 0" ($mineFree.thresholdAmount -eq 0) $mineFree.thresholdAmount

# ---------------------------------------------------------------------------
# 4. 结算页可用券：金额口径由服务端算
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "> 结算页可用券（金额口径）"
$rU1 = Call 'Get' "$Gateway/api/coupons/mine/usable?amount=12345" $sess $null
Check "③ 可用券查询返回 200" ($rU1.status -eq 200) "$($rU1.status)/$($rU1.raw)"
$u1Cap = @($rU1.body.data.coupons | Where-Object { $_.code -eq $CODE_CAP })[0]
$u1Free = @($rU1.body.data.coupons | Where-Object { $_.code -eq $CODE_FREE })[0]
# 123.45 元：8.5 折减 18.51 元（12345 × 15% = 1851.75，向下取整 1851）；8 折减 24.69 元（2469）
Check "③ 8.5 折券在 123.45 元订单上抵 1851 分（向下取整）" ($u1Cap.discountAmount -eq 1851) $u1Cap.discountAmount
Check "③ 8 折券在 123.45 元订单上抵 2469 分" ($u1Free.discountAmount -eq 2469) $u1Free.discountAmount
Check "③ 最优券按实际抵扣额排序（8 折 2469 > 8.5 折 1851）" ($rU1.body.data.bestCouponCode -eq $CODE_FREE) "$($rU1.body.data.bestCouponCode)/$($rU1.body.data.bestDiscountAmount)"
Check "③ bestDiscountAmount 与排序首项一致" ($rU1.body.data.bestDiscountAmount -eq 2469) $rU1.body.data.bestDiscountAmount
Check "③ 折扣券回显折率（前端据此显示「8.5 折」）" ($u1Cap.discountPercent -eq 85) $u1Cap.discountPercent

$rU2 = Call 'Get' "$Gateway/api/coupons/mine/usable?amount=999900" $sess $null
$u2Cap = @($rU2.body.data.coupons | Where-Object { $_.code -eq $CODE_CAP })[0]
$u2Free = @($rU2.body.data.coupons | Where-Object { $_.code -eq $CODE_FREE })[0]
Check "③ 封顶生效：9999 元订单上 8.5 折券只抵 2000 分" ($u2Cap.discountAmount -eq 2000) $u2Cap.discountAmount
Check "③ 不封顶的 8 折券在 9999 元订单上抵 199980 分" ($u2Free.discountAmount -eq 199980) $u2Free.discountAmount

$rU3 = Call 'Get' "$Gateway/api/coupons/mine/usable?amount=5000" $sess $null
$u3Cap = @($rU3.body.data.coupons | Where-Object { $_.code -eq $CODE_CAP })[0]
$u3Free = @($rU3.body.data.coupons | Where-Object { $_.code -eq $CODE_FREE })[0]
Check "③ 未达门槛的折扣券 usable=false" ($u3Cap.usable -eq $false) ($u3Cap | ConvertTo-Json -Compress)
Check "③ 未达门槛原因点明「门槛」" ($u3Cap.reason -match '门槛') $u3Cap.reason
Check "③ 无门槛的 8 折券在 50 元订单上可用（抵 1000 分）" (($u3Free.usable -eq $true) -and ($u3Free.discountAmount -eq 1000)) "$($u3Free.usable)/$($u3Free.discountAmount)"

# ---------------------------------------------------------------------------
# 5. 内部试算边界（小额 / 门槛 / 封顶）—— 必须排在「下单核销」之前：
#    试算要求券仍处于 UNUSED，跑在支付核销之后只会得到「该优惠券已被使用」。
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "> 内部试算边界"
$H_INTERNAL = @{ 'X-Internal-Token' = 'internal' }
$bodyTiny = @{ code = $CODE_FREE; userId = [long]$idBuyer; orderNo = 'SMOKE-DC-TINY'; orderAmount = 1 }
$rTiny = Call 'Post' "$Marketing/api/coupons/internal/lock" $null $bodyTiny $H_INTERNAL
Check "⑥ 1 分订单用 8 折券 → 拒绝（减不到 1 分）" ($rTiny.body.data.success -eq $false) ($rTiny.body | ConvertTo-Json -Compress)
Check "⑥ 拒绝原因说明「没有实际优惠」" ($rTiny.body.data.reason -match '没有实际优惠') $rTiny.body.data.reason

$bodyTrial = @{ code = $CODE_CAP; userId = [long]$idBuyer; orderNo = 'SMOKE-DC-TRIAL'; orderAmount = 10000 }
$rTrial = Call 'Post' "$Marketing/api/coupons/internal/trial" $null $bodyTrial $H_INTERNAL
Check "③ 内部试算：100 元订单 8.5 折抵 1500 分" ($rTrial.body.data.discountAmount -eq 1500) $rTrial.body.data.discountAmount

$bodyCap = @{ code = $CODE_CAP; userId = [long]$idBuyer; orderNo = 'SMOKE-DC-CAP'; orderAmount = 1000000 }
$rCapTrial = Call 'Post' "$Marketing/api/coupons/internal/trial" $null $bodyCap $H_INTERNAL
Check "③ 内部试算：封顶后只抵 2000 分" ($rCapTrial.body.data.discountAmount -eq 2000) $rCapTrial.body.data.discountAmount

$bodyBelow = @{ code = $CODE_CAP; userId = [long]$idBuyer; orderNo = 'SMOKE-DC-BELOW'; orderAmount = 9999 }
$rBelow = Call 'Post' "$Marketing/api/coupons/internal/trial" $null $bodyBelow $H_INTERNAL
Check "③ 内部试算：差 1 分未达门槛 → 拒绝" ($rBelow.body.data.success -eq $false) ($rBelow.body | ConvertTo-Json -Compress)
Check "③ 拒绝原因含「门槛」" ($rBelow.body.data.reason -match '门槛') $rBelow.body.data.reason

# ---------------------------------------------------------------------------
# 6. 下单 → 支付主链路（折扣券金额落账）
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "> 下单用折扣券（金额落账 + 核销）"
$unitPrice = [long](Sql 'duoduo_product' "SELECT price FROM t_sku WHERE id = $SKU;")
Check "（前置）SKU $SKU 单价 > 0" ($unitPrice -gt 0) $unitPrice
$qty = [int][math]::Ceiling(10000 / [double]$unitPrice)  # 凑到满减门槛 100 元
$expectAmount = [long]($unitPrice * $qty)
$expectDiscount = [long][math]::Min([math]::Floor($expectAmount * 15 / 100), 2000)
Write-Host "  单价 $unitPrice 分 × $qty 件 = $expectAmount 分，预期抵扣 $expectDiscount 分"

$rOrder = PlaceOrder $sess $CODE_CAP $qty
Check "③ 用折扣券下单成功" ($rOrder.status -eq 200) "$($rOrder.status)/$($rOrder.raw)"
$O1 = @($rOrder.body.data.orders)[0].orderNo
$ov = @($rOrder.body.data.orders)[0]
Check "③ 订单抵扣额 = floor(商品总额 × 15%) 且受封顶约束" ($ov.discountAmount -eq $expectDiscount) "$($ov.discountAmount) vs $expectDiscount"
Check "③ 订单 couponCode 回写为本单券码" ($ov.couponCode -eq $CODE_CAP) $ov.couponCode
Check "③ 应付 = 商品总额 − 抵扣（口径自洽）" ($ov.payAmount -eq ($ov.totalAmount - $ov.discountAmount)) "$($ov.payAmount)/$($ov.totalAmount)/$($ov.discountAmount)"
$dbOrder = Sql 'duoduo_trade' "SELECT CONCAT(discount_amount,',',COALESCE(coupon_code,''),',',pay_amount) FROM t_order WHERE order_no = '$O1';"
Check "③ 库内 t_order 三列一致" ($dbOrder -eq "$expectDiscount,$CODE_CAP,$($ov.payAmount)") $dbOrder
Check "③ 券状态变为 LOCKED" ("$(CouponStatusOf $CODE_CAP)" -eq 'LOCKED') (CouponStatusOf $CODE_CAP)

$rPay = Call 'Post' "$Gateway/api/orders/$O1/pay" $sess @{ channel = 'MOCK'; idempotentKey = (NewKey) }
Check "③ 用折扣券的订单支付成功" ($rPay.status -eq 200) "$($rPay.status)/$($rPay.raw)"
Check "③ 支付后券核销为 USED" ("$(CouponStatusOf $CODE_CAP)" -eq 'USED') (CouponStatusOf $CODE_CAP)

# ---------------------------------------------------------------------------
# 6. 一单一券：多券字段不被识别、更不会被叠加
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "> 一单一券（不允许叠加）"
Call 'Post' "$Gateway/api/cart/items" $sess @{ skuId = $SKU; quantity = 1 } | Out-Null
$rMulti = Call 'Post' "$Gateway/api/orders" $sess @{
    receiverName    = '小林'
    receiverPhone   = '13900000000'
    receiverAddress = '上海市浦东新区张江路 1 号'
    idempotentKey   = (NewKey)
    couponCodes     = @($CODE_FREE, $CODE_CAP)
}
Check "④ 传多券字段时下单仍成功（未知字段不报错，但也不生效）" ($rMulti.status -eq 200) "$($rMulti.status)/$($rMulti.raw)"
$om = @($rMulti.body.data.orders)[0]
Check "④ 多券字段未被叠加：抵扣额为 0" ($om.discountAmount -eq 0) $om.discountAmount
Check "④ 多券字段未被识别：订单无券码" ([string]::IsNullOrEmpty($om.couponCode)) $om.couponCode
$OM = $om.orderNo
$rCancelMulti = Call 'Post' "$Gateway/api/orders/$OM/cancel" $sess @{ reason = '冒烟：一单一券清理' }
Check "④ 该订单可正常取消（不遗留占用）" ($rCancelMulti.status -eq 200) $rCancelMulti.status

# ---------------------------------------------------------------------------
# 7. 清理（按 id 删夹具，避免污染领券中心）
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "> 清理夹具"
Sql 'duoduo_marketing' "DELETE FROM t_coupon WHERE template_id IN ($TPL_CAP,$TPL_FREE);" | Out-Null
Sql 'duoduo_marketing' "DELETE FROM t_coupon_template WHERE id IN ($TPL_CAP,$TPL_FREE);" | Out-Null
$leftTpl = Sql 'duoduo_marketing' "SELECT COUNT(*) FROM t_coupon_template WHERE id IN ($TPL_CAP,$TPL_FREE);"
$leftCoupon = Sql 'duoduo_marketing' "SELECT COUNT(*) FROM t_coupon WHERE template_id IN ($TPL_CAP,$TPL_FREE);"
Check "清理：模板行已删除" ("$leftTpl" -eq '0') $leftTpl
Check "清理：券行已删除" ("$leftCoupon" -eq '0') $leftCoupon

# ---------------------------------------------------------------------------
# 汇总
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "-----"
Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
if ($fail -gt 0) {
    Write-Host "失败项："
    $failures | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
exit 0
