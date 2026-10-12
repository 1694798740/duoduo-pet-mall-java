# 交易服务 · C2C 担保交易（资金托管 / 放款 / 退款 / 自动确认收货）端到端冒烟（Windows PowerShell 5.1）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\settlement-smoke.ps1
#
# 前置：容器全部 healthy（docker compose up -d），且已 mvn package + docker compose build 出最新镜像。
#
# 设计原则（沿用前几套脚本的教训）：
#   1. 夹具从接口动态取，不硬编码价格/库存/佣金；佣金用与 Java 完全相同的整数公式算出来再比对；
#   2. 凡改库必先记录原值，结尾自证「库内数据已复原」；
#   3. 已付款订单会真实扣减库存、退款会真实回补，所以结尾会把测试消耗的库存显式复原，
#      保证脚本可反复执行（仅测试夹具使用，生产当然没有这种操作）；
#   4. 只改本脚本自己创建的订单，绝不碰种子数据与历史订单；
#   5. 跨轮次的断言一律按「本次测试开始时间」限定范围 ——
#      否则会被功能上线前留下的历史数据误伤，那是断言范围没划清，不是服务有问题。
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    # 直连交易服务端口：用来验证「内部接口被外部直连」时会被服务自身拒绝
    [string]$Trade = 'http://127.0.0.1:8083',
    [string]$Password = 'duoduo123',
    [string]$InternalToken = 'duoduo-local-dev-internal-token',
    # 必须与交易服务配置的 duoduo.trade.platform-fee-bps 一致
    # （E22 起默认 600 = 6%，此前是 100 = 1%）
    [int]$PlatformFeeBps = 600,
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

. (Join-Path $PSScriptRoot 'lib\internal-token.ps1')
. (Join-Path $PSScriptRoot 'lib\platform-operator.ps1')
# 平台运营会话：自营订单归平台（seller_id=0），只有 OPERATOR 能发货；
# 迁移前脚本用买家会话调旧的 /api/orders/{no}/ship（无归属校验），迁移后必须换身份。
$opShip = New-PlatformOperatorSession -Gateway $Gateway -Password $Password
$sessShip = $opShip.session


function Call([string]$method, [string]$uri, $session, $bodyObj, [hashtable]$headers) {
    $params = @{ Uri = $uri; Method = $method; UseBasicParsing = $true; TimeoutSec = 25 }
    if ($session) { $params.WebSession = $session }
    # 内部凭据哨兵：'internal' 表示「这条要走服务间调用」，由这里现场签发签名
    # （签名里带一次性 nonce，必须在调用前生成，不能复用同一个请求头）
    if ($headers -and $headers.ContainsKey('X-Internal-Token') -and $headers['X-Internal-Token'] -eq 'internal') {
        $headers = @{} + $headers
        $headers['X-Internal-Token'] = (New-InternalToken -Method $method -Path ([System.Uri]$uri).AbsolutePath)
    }
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
                # 跨 PS 版本：PS5.1 用 GetResponseStream，pwsh 7（CI Linux）用 Content.ReadAsStringAsync
                $resp = $_.Exception.Response
                $t = $null
                if ($resp -and $resp.PSObject.Methods.Name -contains 'GetResponseStream') {
                    $st = $resp.GetResponseStream()
                    if ($st) { $t = (New-Object System.IO.StreamReader($st)).ReadToEnd() }
                } elseif ($resp -and $null -ne $resp.Content) {
                    $t = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                }
                if ($t) { $body = $t | ConvertFrom-Json }
            } catch { }
        }
        return @{ status = $code; body = $body }
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

# 与 SettlementService.platformFeeOf 完全相同的算法： fee = floor((amount * bps + 5000) / 10000)
# 必须走 [math]::Floor：PowerShell 的 [long] 强制转换是**四舍五入**，
# 直接用 [long]($a/10000) 会在 .5 处与 Java 的整数除法结果不同（曾经的断言误报就出在这里）。
function FeeOf([long]$amount, [int]$bps) {
    if ($amount -le 0 -or $bps -le 0) { return [long]0 }
    return [long][math]::Floor(([double]$amount * $bps + 5000) / 10000)
}

function NewKey { return [guid]::NewGuid().ToString() }
$H_INTERNAL = @{ 'X-Internal-Token' = 'internal' }

$phoneA = '139' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneB = '138' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$SKU_A = 2001
$SKU_B = 2002
$addr = '上海市浦东新区张江路 1 号'
$recv = @{ receiverName = '小林'; receiverPhone = '13900000000'; receiverAddress = $addr }

# 把「加购 → 下单 → 支付」打包成可复用动作，避免五段场景重复写三遍
function PlaceAndPay($session, $skuA, $qtyA, $skuB, $qtyB, $label) {
    if ($qtyA -gt 0) { Call 'Post' "$Gateway/api/cart/items" $session @{ skuId = $skuA; quantity = $qtyA } | Out-Null }
    if ($qtyB -gt 0) { Call 'Post' "$Gateway/api/cart/items" $session @{ skuId = $skuB; quantity = $qtyB } | Out-Null }
    $o = Call 'Post' "$Gateway/api/orders" $session ($recv + @{ idempotentKey = (NewKey) })
    if ($o.status -ne 200) { return @{ ok = $false; orderNo = $null; order = $null; pay = $null; step = "下单 $label" } }
    $order = @($o.body.data.orders)[0]
    $p = Call 'Post' "$Gateway/api/orders/$($order.orderNo)/pay" $session @{ channel = 'MOCK'; idempotentKey = (NewKey) }
    if ($p.status -ne 200) { return @{ ok = $false; orderNo = $order.orderNo; order = $order; pay = $null; step = "支付 $label" } }
    return @{ ok = $true; orderNo = $order.orderNo; order = $order; pay = $p.body.data; step = $null }
}

Write-Host ""
Write-Host "=== 多多宠物商店 · 交易服务（担保交易 / 资金托管）冒烟 @ $Gateway ==="
Write-Host "用户 A = $phoneA ｜ 佣金费率 = $PlatformFeeBps bp"
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

# ---------- 1. 内部接口保护（分两层验证） ----------
Write-Host ""
Write-Host "> 内部接口访问控制"
$r = Call 'Post' "$Trade/api/orders/internal/ANYORDER/refund" $null @{ reason = 'x' }
Check "直连交易服务、无凭据调用运营侧退款被拒绝（403）" ($r.status -eq 403) $r.status
Check "拒绝响应为规范化错误结构（code=FORBIDDEN）" ($r.body.code -eq 'FORBIDDEN') $r.body.code
$r = Call 'Post' "$Trade/api/orders/internal/ANYORDER/refund" $null @{ reason = 'x' } @{ 'X-Internal-Token' = 'wrong-token' }
Check "直连交易服务、错误凭据被拒绝（403）" ($r.status -eq 403) $r.status
$r = Call 'Post' "$Gateway/api/orders/internal/sweep" $null $null
Check "经网关未登录触发补偿调度返回 401（网关先拦一层）" ($r.status -eq 401) $r.status

# ---------- 2. 夹具 ----------
Write-Host ""
Write-Host "> 夹具"
$batch = Call 'Get' "$Gateway/api/products/internal/skus/batch?ids=$SKU_A,$SKU_B" $null $null @{ 'X-Internal-Token' = 'internal' }
$skus = @{}
foreach ($x in @($batch.body.data)) { $skus["$($x.skuId)"] = $x }
$P1 = [long]$skus["$SKU_A"].price
$P2 = [long]$skus["$SKU_B"].price
$S1 = [int]$skus["$SKU_A"].availableStock
$S2 = [int]$skus["$SKU_B"].availableStock
Write-Host "  2001 单价 $P1 可售 $S1 ｜ 2002 单价 $P2 可售 $S2"
if ($P1 -le 0 -or $P2 -le 0 -or $S1 -lt 12) {
    Write-Host "夹具前提不满足，终止本次测试，不执行任何写操作。"
    Check "夹具前提：价格与库存均从接口正确读到" $false "P1=$P1 P2=$P2 S1=$S1 S2=$S2"
    Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
    exit 1
}
Check "夹具前提：2001 可售 ≥ 12（够本脚本消耗）" ($S1 -ge 12) $S1

$dbS1 = [int](Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU_A;")
$dbS2 = [int](Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU_B;")
Check "（前置）库内 2001 库存与接口一致（$S1）" ($dbS1 -eq $S1) $dbS1
Check "（前置）库内 2002 库存与接口一致（$S2）" ($dbS2 -eq $S2) $dbS2
Check "（前置）2001 冻结量为 0" ((Sql 'duoduo_product' "SELECT COALESCE(frozen_stock,0) FROM t_sku WHERE id = $SKU_A;") -eq '0')

# 本次测试的时间起点（用库时钟，与 created_at 同一时区口径）。
# 后面的不变式按它划范围：功能上线前的历史订单本就没有托管单，
# 拿它们去断言「已付款订单必有托管单」会一直失败 —— 那是范围没划清，不是服务错。
$testStart = Sql 'duoduo_trade' "SELECT DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-%d %H:%i:%s');"
Check "（前置）已取得本次测试的库时间起点" (-not [string]::IsNullOrEmpty($testStart)) $testStart
Check "（前置）该时间点之后尚无已付款订单（范围干净）" ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_order WHERE created_at >= '$testStart' AND status IN ('PAID','SHIPPED','COMPLETED','REFUNDED');") -eq '0')

# ---------- 3. 认证 ----------
Write-Host ""
Write-Host "> 认证"
$regA = Register $phoneA '结算测试甲'
Check "用户 A 注册/登录成功（200）" ($regA.result.status -eq 200) $regA.result.status
$sessA = $regA.session
$anon = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$r = Call 'Get' "$Gateway/api/orders/ANY/settlement" $anon $null
Check "未登录查询资金托管返回 401" ($r.status -eq 401) $r.status
$r = Call 'Post' "$Gateway/api/orders/ANY/refund" $anon @{ reason = 'x' }
Check "未登录申请退款返回 401" ($r.status -eq 401) $r.status

# ---------- 4. 场景一：托管 → 发货 → 确认收货 → 放款 ----------
Write-Host ""
Write-Host "> 场景一：托管 → 发货 → 确认收货 → 放款"
$c1 = PlaceAndPay $sessA $SKU_A 2 $SKU_B 1 '场景一'
Check "场景一：下单并支付成功（200）" $c1.ok $c1.step
$on1 = $c1.orderNo
$pay1 = [long]$c1.pay.amount

$r = Call 'Get' "$Gateway/api/orders/$on1" $sessA $null
Check "支付后订单详情内嵌托管信息（非空）" ($null -ne $r.body.data.settlement)
Check "托管金额 = 订单实付（$pay1 分）" ([long]$r.body.data.settlement.amount -eq $pay1) $r.body.data.settlement.amount

# ★ E22：佣金口径分两种 —— 自营（seller_id = 0）**免佣**，第三方按 $PlatformFeeBps 抽佣。
#   判据取**接口返回的 sellerId**，不查库：这样 -SkipDbCheck 时这条也照常跑。
#   夹具商品属于平台自营，所以此处期望佣金为 0 —— 不是"没算"，而是"按规则不该收"。
#   第三方路径的取整公式由 SettlementServiceFeeTest 单测覆盖（冒烟夹具里只有自营商品）。
$selfSeller = ([string]$r.body.data.settlement.sellerId -eq '0')
$fee1 = if ($selfSeller) { [long]0 } else { FeeOf $pay1 $PlatformFeeBps }
Check "★ E22：佣金口径正确（自营免佣 / 第三方 $PlatformFeeBps bp，期望 $fee1 分）" `
    ([long]$r.body.data.settlement.platformFee -eq $fee1) `
    "sellerId=$($r.body.data.settlement.sellerId) 实收佣金=$($r.body.data.settlement.platformFee) 期望=$fee1"
Check "卖家可得 = 托管金额 − 佣金（$($pay1 - $fee1) 分）" ([long]$r.body.data.settlement.sellerAmount -eq ($pay1 - $fee1)) $r.body.data.settlement.sellerAmount
Check "托管状态为 CUSTODY（资金在平台）" ($r.body.data.settlement.status -eq 'CUSTODY') $r.body.data.settlement.status
Check "托管文案为「托管中」" ($r.body.data.settlement.statusLabel -eq '托管中') $r.body.data.settlement.statusLabel
Check "未放款时 releasedAt 为空" ([string]::IsNullOrEmpty($r.body.data.settlement.releasedAt)) $r.body.data.settlement.releasedAt

$r = Call 'Get' "$Gateway/api/orders/$on1/settlement" $sessA $null
Check "单独查询托管状态返回 200" ($r.status -eq 200) $r.status
Check "单独查询：金额与订单一致" ([long]$r.body.data.amount -eq $pay1) $r.body.data.amount
Check "单独查询：orderNo 回填正确" ($r.body.data.orderNo -eq $on1) $r.body.data.orderNo

$r = Call 'Get' "$Gateway/api/orders?pageSize=5" $sessA $null
$foundInList = @($r.body.data.items | Where-Object { $_.orderNo -eq $on1 })
Check "订单列表也带托管信息（避免前端逐单再查）" ($foundInList.Count -eq 1 -and $null -ne $foundInList[0].settlement) $foundInList.Count

if (-not $SkipDbCheck) {
    Check "★ 交易库托管单 1 行且为 CUSTODY" ((Sql 'duoduo_trade' "SELECT CONCAT(COUNT(*),'/',COALESCE(MAX(status),'-')) FROM t_settlement WHERE order_id=(SELECT id FROM t_order WHERE order_no='$on1');") -eq '1/CUSTODY')
    Check "托管金额落库 = $pay1 分" ((Sql 'duoduo_trade' "SELECT amount FROM t_settlement WHERE order_id=(SELECT id FROM t_order WHERE order_no='$on1');") -eq "$pay1")
    Check "佣金落库 = $fee1 分" ((Sql 'duoduo_trade' "SELECT platform_fee FROM t_settlement WHERE order_id=(SELECT id FROM t_order WHERE order_no='$on1');") -eq "$fee1")
}

# 支付幂等重放不应产生第二条托管单（uk_settle_order）
$r = Call 'Post' "$Gateway/api/orders/$on1/pay" $sessA @{ channel = 'MOCK'; idempotentKey = (NewKey) }
Check "已付款订单换键重复支付被拒（422）" ($r.status -eq 422) $r.status
if (-not $SkipDbCheck) {
    Check "支付被拒后托管单仍只有 1 行（未被复制）" ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_settlement WHERE order_id=(SELECT id FROM t_order WHERE order_no='$on1');") -eq '1')
}

# 未发货不能确认收货
$r = Call 'Post' "$Gateway/api/orders/$on1/confirm" $sessA $null
Check "未发货不能确认收货（422）" ($r.status -eq 422) $r.status

# 发货：钱仍应在托管里
$r = Call 'Post' "$Gateway/api/seller/orders/$on1/ship" $sessShip @{}
Check "带内部凭据发货成功（200）" ($r.status -eq 200) $r.status
Check "发货后订单为 SHIPPED" ($r.body.data.status -eq 'SHIPPED') $r.body.data.status
Check "★ 发货不等于放款：托管仍为 CUSTODY" ($r.body.data.settlement.status -eq 'CUSTODY') $r.body.data.settlement.status

# 确认收货 → 放款
$r = Call 'Post' "$Gateway/api/orders/$on1/confirm" $sessA $null
Check "确认收货返回 200" ($r.status -eq 200) $r.status
Check "订单转为 COMPLETED" ($r.body.data.status -eq 'COMPLETED') $r.body.data.status
Check "★ 确认收货后资金已放款给卖家（RELEASED）" ($r.body.data.settlement.status -eq 'RELEASED') $r.body.data.settlement.status
Check "放款文案为「已放款」" ($r.body.data.settlement.statusLabel -eq '已放款') $r.body.data.settlement.statusLabel
Check "放款时间非空" (-not [string]::IsNullOrEmpty($r.body.data.settlement.releasedAt)) $r.body.data.settlement.releasedAt
Check "放款后金额不变（$pay1 分）" ([long]$r.body.data.settlement.amount -eq $pay1) $r.body.data.settlement.amount
$releasedAt1 = $r.body.data.settlement.releasedAt

$r = Call 'Post' "$Gateway/api/orders/$on1/confirm" $sessA $null
Check "重复确认收货被拒（422）" ($r.status -eq 422) $r.status
$r = Call 'Get' "$Gateway/api/orders/$on1/settlement" $sessA $null
Check "重复确认收货未二次放款（releasedAt 未被改写）" ($r.body.data.releasedAt -eq $releasedAt1) $r.body.data.releasedAt
if (-not $SkipDbCheck) {
    Check "托管单仍只有 1 行、状态 RELEASED" ((Sql 'duoduo_trade' "SELECT CONCAT(COUNT(*),'/',COALESCE(MAX(status),'-')) FROM t_settlement WHERE order_id=(SELECT id FROM t_order WHERE order_no='$on1');") -eq '1/RELEASED')
    Check "released_at 已落库" ((Sql 'duoduo_trade' "SELECT CASE WHEN released_at IS NULL THEN 'null' ELSE 'set' END FROM t_settlement WHERE order_id=(SELECT id FROM t_order WHERE order_no='$on1');") -eq 'set')

    # ---------- E22 口径（放款即记账、T+7 才可提现）----------
    # ★ 这一条是 E22 的核心变化：release_at 与 released_at **不再相等**。
    #   前者是"什么时候能取"，后者是"钱什么时候变成他的" —— 分开存才能表达
    #   "钱已经是他的、但还在售后期内取不走"这个中间状态。
    Check "★ E22：release_at 晚于 released_at（可提现时刻被推后）" `
        ((Sql 'duoduo_trade' "SELECT CASE WHEN release_at > released_at THEN 'later' ELSE 'same' END FROM t_settlement WHERE order_id=(SELECT id FROM t_order WHERE order_no='$on1');") -eq 'later') `
        "release_at=$(Sql 'duoduo_trade' "SELECT IFNULL(release_at,'NULL') FROM t_settlement WHERE order_id=(SELECT id FROM t_order WHERE order_no='$on1');")"
    Check "★ E22：售后期差额恰为 7 天（T+7）" `
        ((Sql 'duoduo_trade' "SELECT DATEDIFF(release_at, released_at) FROM t_settlement WHERE order_id=(SELECT id FROM t_order WHERE order_no='$on1');") -eq '7')
}

# 钱包接口可用性 + 不串数据：运营（非本单卖家本人）查自己的钱包，应为 200 且金额全 0。
# ★ 刻意用"运营"而不是"订单卖家"来查：脚本里没有订单卖家本人的会话（发货是运营代发的），
#   而这条断言真正要证明的是「接口通、且不会把别人的钱算给我」——
#   若哪天租户/归属过滤写错，这里会立刻变成非 0。
$r = Call 'Get' "$Gateway/api/seller/settlements/wallet" $sessShip $null
Check "★ E22：卖家钱包接口返回 200" ($r.status -eq 200) "status=$($r.status) raw=$($r.raw)"
Check "★ E22：非本单卖家查钱包，金额全 0（不串别人的钱）" `
    ([long]$r.body.data.pendingAmount -eq 0 -and [long]$r.body.data.frozenAmount -eq 0 -and [long]$r.body.data.netWithdrawableAmount -eq 0) `
    "pending=$($r.body.data.pendingAmount) frozen=$($r.body.data.frozenAmount) net=$($r.body.data.netWithdrawableAmount)"
Check "★ E22：钱包回述售后期天数 = 7" ([int]$r.body.data.holdDays -eq 7) "holdDays=$($r.body.data.holdDays)"

# ---------- 4.5 E22-4：结算账单（租户 × 周期，双侧同源） ----------
Write-Host ""
Write-Host "> E22-4：结算账单（租户 × 周期）"

# ★ 时间断言前必须**归一化**再比。
#   pwsh 7 的 ConvertFrom-Json 会把 ISO 串自动转成 [datetime]（PS 5.1 不转），
#   于是 `$r.body.data.periodStart -match 'T00:00:00Z$'` 这类写法在 CI 上恒假 ——
#   典型的「本机绿、CI 红」成因（AGENTS 铁律 10）。这里统一转回 UTC 的 ISO 串。
function IsoUtc([object]$v) {
    if ($null -eq $v) { return '' }
    return ([datetimeoffset]$v).UtcDateTime.ToString('yyyy-MM-ddTHH:mm:ssZ')
}

$period = [DateTime]::UtcNow.ToString('yyyy-MM')
$expectStart = "$period-01T00:00:00Z"
$expectEnd = ([datetime]::ParseExact("$period-01", 'yyyy-MM-dd', $null).AddMonths(1)).ToString('yyyy-MM-dd') + 'T00:00:00Z'

$r = Call 'Get' "$Gateway/api/seller/settlements/bills?period=$period" $sessShip $null
$bill = $r.body.data
Check "★ E22：商家端结算账单返回 200" ($r.status -eq 200) "status=$($r.status)"
Check "★ E22：账单周期原样回述（$period）" ("$($bill.period)" -eq $period) "$($bill.period)"
Check "★ E22：账单起点 = 本月初 00:00Z" ((IsoUtc $bill.periodStart) -eq $expectStart) (IsoUtc $bill.periodStart)
Check "★ E22：账单终点 = 下月初 00:00Z（左闭右开，跨月不重不漏）" ((IsoUtc $bill.periodEnd) -eq $expectEnd) (IsoUtc $bill.periodEnd)
# 跨字段自洽：卖家应得恒等于「订单金额 − 佣金」。两项出自同一条 SQL 的同一趟扫描，
# 若哪天被拆成两条查询，这条会立刻变红 —— 而拆开之后不一致的概率不低。
Check "★ E22：账单自洽 —— 卖家应得 = 订单金额 − 佣金" `
    (([long]$bill.sellerEarningAmount) -eq ([long]$bill.orderAmount - [long]$bill.platformFeeAmount)) `
    "order=$($bill.orderAmount) fee=$($bill.platformFeeAmount) seller=$($bill.sellerEarningAmount)"

# ★ 同源证据：账单的「余额段」必须与钱包接口**逐分一致**。
#   两边各算一遍最常见的后果是"钱包说能提 500、账单说 480"，而用户只会相信小的那个，
#   于是另一个页面在他眼里就成了"系统在骗人"。这条断言就是钉住这一点的。
$w = Call 'Get' "$Gateway/api/seller/settlements/wallet" $sessShip $null
Check "★ E22：账单余额段与钱包接口逐分一致（同源）" `
    (([long]$bill.pendingAmount -eq [long]$w.body.data.pendingAmount) -and `
     ([long]$bill.frozenAmount -eq [long]$w.body.data.frozenAmount) -and `
     ([long]$bill.withdrawableAmount -eq [long]$w.body.data.withdrawableAmount) -and `
     ([long]$bill.outstandingDebt -eq [long]$w.body.data.outstandingDebt)) `
    "bill=$($bill.pendingAmount)/$($bill.frozenAmount)/$($bill.withdrawableAmount)/$($bill.outstandingDebt) wallet=$($w.body.data.pendingAmount)/$($w.body.data.frozenAmount)/$($w.body.data.withdrawableAmount)/$($w.body.data.outstandingDebt)"

# 非法周期必须被拒：**不许**静默回落到当前月 —— 回落的后果是"我查 9 月、系统给 10 月"，
# 页面一切正常，只有答案是错的（最难被发现的一类）。
$bad = Call 'Get' "$Gateway/api/seller/settlements/bills?period=2026-13" $sessShip $null
Check "★ E22：非法周期被拒（400），不静默回落当前月" ($bad.status -eq 400) "status=$($bad.status)"

# —— 平台端：权限点 + 双侧一致性 ——
$tenantId = [string]$bill.tenantId
$rq = Call 'Get' "$Gateway/api/admin/settlements/bills?tenantId=$tenantId&period=$period" $sessShip $null
Check "★ E22：运营查租户结算账单被拒（403，财务权限点生效）" ($rq.status -eq 403) "status=$($rq.status)"

$phoneFin = $null
if ($SkipDbCheck) {
    Write-Host "  [SKIP] 已指定 -SkipDbCheck：跳过财务权限**正对照**（提权需要直接改库）"
} else {
    # 正对照夹具：随机手机号现场注册 → 库内提权为 ADMIN → 重新登录。
    # ★ 刻意**不**沿用既有管理员账号：那会污染真实账号的角色，也不可重复。
    $phoneFin = '137' + (Get-Random -Minimum 10000000 -Maximum 99999999)
    Register $phoneFin '结算测试-财务' | Out-Null
    $finOk = $false
    for ($i = 0; $i -lt 5; $i++) {
        Sql 'duoduo_user' "UPDATE t_user SET role='ADMIN' WHERE phone='$phoneFin';" | Out-Null
        Start-Sleep -Milliseconds 200
        if ((Sql 'duoduo_user' "SELECT role FROM t_user WHERE phone='$phoneFin';") -eq 'ADMIN') { $finOk = $true; break }
    }
    # 夹具三纪律之一：**建不起必须显式报红**。悄悄跳过后续断言等于把"没测"记成"通过"。
    Check "★ E22：财务夹具——ADMIN 角色写库成功（建不起即报红）" $finOk "phone=$phoneFin"

    $sessFin = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $lg = Call 'Post' "$Gateway/api/auth/login" $sessFin @{ phone = $phoneFin; password = $Password }
    Check "★ E22：财务夹具——提权后重新登录成功（令牌里带新角色）" ($lg.status -eq 200) "status=$($lg.status)"

    $ra = Call 'Get' "$Gateway/api/admin/settlements/bills?tenantId=$tenantId&period=$period" $sessFin $null
    Check "★ E22：财务（ADMIN）查租户账单返回 200（正对照）" ($ra.status -eq 200) "status=$($ra.status)"
    Check "★ E22：平台端与商家端账单**逐分一致**（双侧同源）" `
        (([long]$ra.body.data.orderAmount -eq [long]$bill.orderAmount) -and `
         ([long]$ra.body.data.platformFeeAmount -eq [long]$bill.platformFeeAmount) -and `
         ([long]$ra.body.data.releasedAmount -eq [long]$bill.releasedAmount) -and `
         ([long]$ra.body.data.withdrawableAmount -eq [long]$bill.withdrawableAmount) -and `
         ([long]$ra.body.data.outstandingDebt -eq [long]$bill.outstandingDebt)) `
        "admin=$($ra.body.data.orderAmount)/$($ra.body.data.platformFeeAmount) seller=$($bill.orderAmount)/$($bill.platformFeeAmount)"
    Check "★ E22：平台端账单回述同一 tenantId（不串租户）" ("$($ra.body.data.tenantId)" -eq $tenantId) "want=$tenantId got=$($ra.body.data.tenantId)"

    # ★★ 防「空结果被当成正常」★★
    #   上面那几条同源断言用的租户是夹具运营账号 —— 他名下**一张托管单都没有**，
    #   所以四个口径全是 0，"两边都 0" 无法区分「口径一致」与「聚合恒空/链路没接通」。
    #   自营订单的 seller_id = 0，而本套件刚刚建了 4 张自营订单，
    #   所以 tenantId=0 的账单**必须有真实入账** —— 这才证明聚合真的读到了数据。
    $self = Call 'Get' "$Gateway/api/admin/settlements/bills?tenantId=0&period=$period" $sessFin $null
    Check "★ E22：自营（tenantId=0）账单返回 200" ($self.status -eq 200) "status=$($self.status)"
    Check "★ E22：自营账单本期确有入账（笔数>0，防「聚合恒空被当成正常」）" `
        (([long]$self.body.data.orderCount -gt 0) -and ([long]$self.body.data.orderAmount -gt 0)) `
        "count=$($self.body.data.orderCount) amount=$($self.body.data.orderAmount)"

    if (-not $SkipDbCheck) {
        # 独立复算：同一条口径在库里再算一遍。账单若把时间列写错（比如用 released_at
        # 去算"本期入账"），笔数与金额会双双对不上 —— 这是唯一能不靠读代码发现它的办法。
        $dbStart = "$period-01 00:00:00"
        $dbEnd = ([datetime]::ParseExact("$period-01", 'yyyy-MM-dd', $null).AddMonths(1)).ToString('yyyy-MM-dd') + ' 00:00:00'
        $dbCnt = Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_settlement WHERE seller_id=0 AND created_at >= '$dbStart' AND created_at < '$dbEnd';"
        $dbAmt = Sql 'duoduo_trade' "SELECT COALESCE(SUM(amount),0) FROM t_settlement WHERE seller_id=0 AND created_at >= '$dbStart' AND created_at < '$dbEnd';"
        Check "★ E22：账单「本期入账」与库内独立复算一致（笔数）" ("$dbCnt" -eq "$([long]$self.body.data.orderCount)") "db=$dbCnt bill=$($self.body.data.orderCount)"
        Check "★ E22：账单「本期入账」与库内独立复算一致（金额，分）" ("$dbAmt" -eq "$([long]$self.body.data.orderAmount)") "db=$dbAmt bill=$($self.body.data.orderAmount)"
    }

    # 不指定租户必须被拒，而不是"返回全平台的账"—— 后者会被当成某个租户的账单来用。
    $rb = Call 'Get' "$Gateway/api/admin/settlements/bills?period=$period" $sessFin $null
    Check "★ E22：平台端未指定租户被拒（400）" ($rb.status -eq 400) "status=$($rb.status)"
    $rc = Call 'Get' "$Gateway/api/admin/settlements/bills?tenantId=$tenantId&period=2026-13" $sessFin $null
    Check "★ E22：平台端非法周期被拒（400）" ($rc.status -eq 400) "status=$($rc.status)"
}

# 已放款的订单不能再退款（钱已到卖家手里，需走追偿）
$r = Call 'Post' "$Gateway/api/orders/internal/$on1/refund" $sessA @{ reason = '已放款后尝试退款' } $H_INTERNAL
Check "★ 已放款订单退款被拒（422）" ($r.status -eq 422) $r.status
Check "拒绝原因说明需走追偿/垫付" ($r.body.message -match '追偿|垫付') $r.body.message
$r = Call 'Get' "$Gateway/api/orders/$on1/settlement" $sessA $null
Check "被拒后托管仍为 RELEASED（未被改写为 REFUNDED）" ($r.body.data.status -eq 'RELEASED') $r.body.data.status

# ---------- 5. 场景二：买家自助退款（已付款 · 未发货） ----------
Write-Host ""
Write-Host "> 场景二：买家自助退款（已付款 · 未发货）"
$before2 = [int](Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU_A;")
$c2 = PlaceAndPay $sessA $SKU_A 1 0 0 '场景二'
Check "场景二：下单并支付成功（200）" $c2.ok $c2.step
$on2 = $c2.orderNo
$pay2 = [long]$c2.pay.amount
$after2 = [int](Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU_A;")
Check "支付后库存真实扣减 1 件（$before2 → $after2）" ($after2 -eq ($before2 - 1)) "$before2 -> $after2"
Check "支付后 2001 冻结量归零" ((Sql 'duoduo_product' "SELECT COALESCE(frozen_stock,0) FROM t_sku WHERE id = $SKU_A;") -eq '0')

$r = Call 'Post' "$Gateway/api/orders/$on2/cancel" $sessA @{ reason = '反悔' }
Check "已付款订单不能自行取消（422，回归 I-11①）" ($r.status -eq 422) $r.status

$r = Call 'Post' "$Gateway/api/orders/$on2/refund" $anon @{ reason = 'x' }
Check "未登录退款返回 401" ($r.status -eq 401) $r.status
$regB = Register $phoneB '结算测试乙'
Check "用户 B 注册/登录成功（200）" ($regB.result.status -eq 200) $regB.result.status
$r = Call 'Post' "$Gateway/api/orders/$on2/refund" $regB.session @{ reason = '越权尝试' }
Check "用户 B 退款 A 的订单返回 404" ($r.status -eq 404) $r.status
$r = Call 'Get' "$Gateway/api/orders/$on2/settlement" $regB.session $null
Check "用户 B 查询 A 的托管信息返回 404" ($r.status -eq 404) $r.status

$r = Call 'Post' "$Gateway/api/orders/$on2/refund" $sessA @{ reason = '不想要了' }
Check "买家自助退款返回 200" ($r.status -eq 200) $r.status
Check "订单转为 REFUNDED" ($r.body.data.orderStatus -eq 'REFUNDED') $r.body.data.orderStatus
Check "退款文案为「已退款」" ($r.body.data.orderStatusLabel -eq '已退款') $r.body.data.orderStatusLabel
Check "退款金额 = 实付金额（$pay2 分）" ([long]$r.body.data.refundAmount -eq $pay2) $r.body.data.refundAmount
Check "★ 资金已退回买家（fundsRefunded=true）" ($r.body.data.fundsRefunded -eq $true) $r.body.data.fundsRefunded
Check "托管状态为 REFUNDED" ($r.body.data.settlementStatus -eq 'REFUNDED') $r.body.data.settlementStatus
Check "托管文案为「已退回买家」" ($r.body.data.settlementStatusLabel -eq '已退回买家') $r.body.data.settlementStatusLabel
Check "响应如实告知「货待回库」（restockPending=true）" ($r.body.data.restockPending -eq $true) $r.body.data.restockPending
Check "退款时间非空" (-not [string]::IsNullOrEmpty($r.body.data.refundedAt)) $r.body.data.refundedAt

$r = Call 'Get' "$Gateway/api/orders/$on2" $sessA $null
Check "订单详情状态为 REFUNDED" ($r.body.data.status -eq 'REFUNDED') $r.body.data.status
Check "订单详情 refundedAt 非空" (-not [string]::IsNullOrEmpty($r.body.data.refundedAt)) $r.body.data.refundedAt
Check "订单详情托管状态 REFUNDED" ($r.body.data.settlement.status -eq 'REFUNDED') $r.body.data.settlement.status

if (-not $SkipDbCheck) {
    Check "托管单状态落库为 REFUNDED" ((Sql 'duoduo_trade' "SELECT status FROM t_settlement WHERE order_id=(SELECT id FROM t_order WHERE order_no='$on2');") -eq 'REFUNDED')
    Check "订单 refunded_at 已落库" ((Sql 'duoduo_trade' "SELECT CASE WHEN refunded_at IS NULL THEN 'null' ELSE 'set' END FROM t_order WHERE order_no='$on2';") -eq 'set')
    Check "订单明细已标记退款（refund_status=REFUNDED）" ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_order_item WHERE order_id=(SELECT id FROM t_order WHERE order_no='$on2') AND refund_status='REFUNDED';") -eq '1')
    Check "★ 台账已从 DEDUCTED 迁出（不再是已扣减）" ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_stock_freeze WHERE order_id=(SELECT id FROM t_order WHERE order_no='$on2') AND status='DEDUCTED';") -eq '0')
    Check "状态日志含 REFUNDED 流转" ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_order_status_log WHERE order_id=(SELECT id FROM t_order WHERE order_no='$on2') AND to_status='REFUNDED';") -eq '1')

    # 触发补偿调度：确认入库回补最终完成（退款后它会异步做，也可能已被事务提交后的监听器做完）
    $r = Call 'Post' "$Gateway/api/orders/internal/sweep" $sessA $null $H_INTERNAL
    Check "带内部凭据触发补偿调度返回 200" ($r.status -eq 200) $r.status
    Check "★ 台账已置 RESTOCKED（货已回库）" ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_stock_freeze WHERE order_id=(SELECT id FROM t_order WHERE order_no='$on2') AND status='RESTOCKED';") -eq '1')
    $afterRestock = [int](Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU_A;")
    Check "★ 退款后库存已回补（$after2 → $afterRestock，回到 $before2）" ($afterRestock -eq $before2) "$after2 -> $afterRestock"
    $r = Call 'Post' "$Gateway/api/orders/internal/sweep" $sessA $null $H_INTERNAL
    Check "重复触发补偿调度不报错（200，可重入）" ($r.status -eq 200) $r.status
    Check "重复调度未重复入库（库存仍为 $before2）" ((Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU_A;") -eq "$before2")
}

$r = Call 'Post' "$Gateway/api/orders/$on2/refund" $sessA @{ reason = '再退一次' }
Check "重复退款被拒（422）" ($r.status -eq 422) $r.status
$r = Call 'Post' "$Gateway/api/orders/internal/$on2/refund" $sessA @{ reason = '运营侧重复退' } $H_INTERNAL
Check "运营侧重复退款同样被拒（422）" ($r.status -eq 422) $r.status
# 退款路径先做订单状态检查（已退款 → 直接拒绝），再到资金状态检查，
# 因此提示语是「订单当前状态（已退款）不允许退款」—— 这些表述都算正确，不作过细约束。
Check "拒绝原因说明当前状态" ($r.body.message -match '已退款|不允许退款|不能重复退款') $r.body.message

# 未付款订单不应该能退款
Write-Host ""
Write-Host "> 场景二补充：未付款订单不可退款"
$r = Call 'Post' "$Gateway/api/cart/items" $sessA @{ skuId = $SKU_A; quantity = 1 }
Check "（准备）加购 2001 × 1" ($r.status -eq 200) $r.status
$r = Call 'Post' "$Gateway/api/orders" $sessA ($recv + @{ idempotentKey = (NewKey) })
Check "（准备）再下一单（待付款）" ($r.status -eq 200) $r.status
$on3 = @($r.body.data.orders)[0].orderNo
$r = Call 'Post' "$Gateway/api/orders/$on3/refund" $sessA @{ reason = '不想买了' }
Check "未付款订单自助退款被拒（422）" ($r.status -eq 422) $r.status
Check "拒绝原因提示直接取消订单" ($r.body.message -match '尚未支付') $r.body.message
$r = Call 'Post' "$Gateway/api/orders/$on3/cancel" $sessA @{ reason = '改主意了' }
Check "未付款订单可正常取消（200）" ($r.status -eq 200) $r.status
$r = Call 'Post' "$Gateway/api/orders/$on3/refund" $sessA @{ reason = '取消后再退' }
Check "已取消订单退款被拒（422）" ($r.status -eq 422) $r.status

# ---------- 6. 场景三：已发货订单买家不能自助退，运营侧可退 ----------
Write-Host ""
Write-Host "> 场景三：已发货订单的退款边界"
$before3 = [int](Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU_A;")
$c3 = PlaceAndPay $sessA $SKU_A 1 0 0 '场景三'
Check "场景三：下单并支付成功（200）" $c3.ok $c3.step
$on4 = $c3.orderNo
$r = Call 'Post' "$Gateway/api/seller/orders/$on4/ship" $sessShip @{}
Check "（准备）发货成功" ($r.status -eq 200) $r.status
Check "（准备）订单为 SHIPPED" ($r.body.data.status -eq 'SHIPPED') $r.body.data.status

$r = Call 'Post' "$Gateway/api/orders/$on4/refund" $sessA @{ reason = '不想要了' }
Check "★ 已发货订单买家自助退款被拒（422）" ($r.status -eq 422) $r.status
Check "拒绝原因提示走售后" ($r.body.message -match '售后') $r.body.message
$r = Call 'Post' "$Trade/api/orders/internal/$on4/refund" $null @{ reason = 'x' }
Check "直连交易服务、无凭据的运营侧退款被拒（403）" ($r.status -eq 403) $r.status

$r = Call 'Post' "$Gateway/api/orders/internal/$on4/refund" $sessA @{ reason = '卖家已收到退货，同意退款' } $H_INTERNAL
Check "运营侧退款（售后）返回 200" ($r.status -eq 200) $r.status
Check "订单转为 REFUNDED" ($r.body.data.orderStatus -eq 'REFUNDED') $r.body.data.orderStatus
Check "资金已退回买家" ($r.body.data.fundsRefunded -eq $true) $r.body.data.fundsRefunded
if (-not $SkipDbCheck) {
    Check "（售后）托管单状态 REFUNDED" ((Sql 'duoduo_trade' "SELECT status FROM t_settlement WHERE order_id=(SELECT id FROM t_order WHERE order_no='$on4');") -eq 'REFUNDED')
    Check "（售后）状态日志操作者为 SELLER" ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_order_status_log WHERE order_id=(SELECT id FROM t_order WHERE order_no='$on4') AND to_status='REFUNDED' AND operator_type='SELLER';") -eq '1')
    $r = Call 'Post' "$Gateway/api/orders/internal/sweep" $sessA $null $H_INTERNAL
    Check "（售后）入库回补完成（RESTOCKED）" ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_stock_freeze WHERE order_id=(SELECT id FROM t_order WHERE order_no='$on4') AND status='RESTOCKED';") -eq '1')
    Check "（售后）库存已回补" ((Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU_A;") -eq "$before3")
}

# ---------- 7. 场景四：自动确认收货并放款 ----------
Write-Host ""
Write-Host "> 场景四：发货满 N 天自动确认收货并放款"
$c4 = PlaceAndPay $sessA $SKU_A 1 0 0 '场景四'
Check "场景四：下单并支付成功（200）" $c4.ok $c4.step
$on5 = $c4.orderNo
$r = Call 'Post' "$Gateway/api/seller/orders/$on5/ship" $sessShip @{}
Check "（准备）发货成功（SHIPPED）" ($r.status -eq 200) $r.status
$r = Call 'Get' "$Gateway/api/orders/$on5/settlement" $sessA $null
Check "（准备）自动收货前资金仍在托管（CUSTODY）" ($r.body.data.status -eq 'CUSTODY') $r.body.data.status

if (-not $SkipDbCheck) {
    # 测试夹具：把发货时间往前挪 8 天（只改本脚本自己创建的订单），触发「发货满 7 天自动收货」
    Sql 'duoduo_trade' "UPDATE t_order SET shipped_at = DATE_SUB(UTC_TIMESTAMP(), INTERVAL 8 DAY) WHERE order_no = '$on5';" | Out-Null
    Check "（准备）发货时间已置为 8 天前" ((Sql 'duoduo_trade' "SELECT CASE WHEN shipped_at < DATE_SUB(UTC_TIMESTAMP(), INTERVAL 7 DAY) THEN 'yes' ELSE 'no' END FROM t_order WHERE order_no='$on5';") -eq 'yes')

    $r = Call 'Post' "$Gateway/api/orders/internal/sweep" $sessA $null $H_INTERNAL
    Check "带内部凭据触发补偿调度返回 200" ($r.status -eq 200) $r.status
    # 与 coupon 的「过期标记」同型：定时调度（自动收货 / 放款作业）可能在这两次调用之间抢先处理，
    # 抢跑之后这一调用的计数自然就是 0 —— 但「超期订单会被自动收货并放款」这条不变式已经成立。
    # 反向仍有力：调度真坏掉时订单会停在 SHIPPED、托管停在 CUSTODY，此处必然红（下面两行还钉着终态）。
    $autoState = Sql 'duoduo_trade' "SELECT CONCAT(o.status,'/',IFNULL(s.status,'NULL')) FROM t_order o LEFT JOIN t_settlement s ON s.order_id=o.id WHERE o.order_no='$on5';"
    Check "调度报告自动收货 ≥ 1 单（或定时调度已先行处理）" (([int]$r.body.data.autoCompleted -ge 1) -or ($autoState -eq 'COMPLETED/RELEASED')) $r.body.data.autoCompleted
    Check "调度报告放款 ≥ 1 笔（或定时调度已先行放款）" (([int]$r.body.data.fundsReleased -ge 1) -or ($autoState -eq 'COMPLETED/RELEASED')) $r.body.data.fundsReleased

    $r = Call 'Get' "$Gateway/api/orders/$on5" $sessA $null
    Check "★ 超期订单已自动确认收货（COMPLETED）" ($r.body.data.status -eq 'COMPLETED') $r.body.data.status
    Check "★ 自动收货同时放款给卖家（RELEASED）" ($r.body.data.settlement.status -eq 'RELEASED') $r.body.data.settlement.status
    Check "自动收货写入完成时间" (-not [string]::IsNullOrEmpty($r.body.data.finishedAt)) $r.body.data.finishedAt
    $finished1 = $r.body.data.finishedAt
    Check "自动收货的状态日志操作者为 SYSTEM" ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_order_status_log WHERE order_id=(SELECT id FROM t_order WHERE order_no='$on5') AND to_status='COMPLETED' AND operator_type='SYSTEM';") -eq '1')
    Check "日志原因标明自动确认收货" ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_order_status_log WHERE order_id=(SELECT id FROM t_order WHERE order_no='$on5') AND reason LIKE '%自动确认收货%';") -eq '1')

    $r = Call 'Post' "$Gateway/api/orders/internal/sweep" $sessA $null $H_INTERNAL
    Check "重复调度不报错（200）" ($r.status -eq 200) $r.status
    $r = Call 'Get' "$Gateway/api/orders/$on5" $sessA $null
    Check "重复调度未改写完成时间（可重入）" ($r.body.data.finishedAt -eq $finished1) $r.body.data.finishedAt
    Check "重复调度未二次放款（released 仍为 1 行 RELEASED）" ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_settlement WHERE order_id=(SELECT id FROM t_order WHERE order_no='$on5') AND status='RELEASED';") -eq '1')
}

# ---------- 8. 资金与订单的交叉不变式（限定本次测试范围） ----------
Write-Host ""
Write-Host "> 不变式（范围：本次测试创建的订单）"
Check "本次测试的已付款及以后订单全部有托管单" ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_order o LEFT JOIN t_settlement s ON s.order_id=o.id WHERE o.created_at >= '$testStart' AND o.status IN ('PAID','SHIPPED','COMPLETED','REFUNDED') AND s.id IS NULL;") -eq '0')
# 刻意不写死条数：条数取决于脚本里场景的增删，写死了每次改脚本都要跟着改，
# 而真正要守的不变式是**一一对应**（一单一条托管单，既不多也不少）。
$rangeOrders = [int](Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_order WHERE created_at >= '$testStart' AND status IN ('PAID','SHIPPED','COMPLETED','REFUNDED');")
$rangeSettles = [int](Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_settlement s JOIN t_order o ON o.id=s.order_id WHERE o.created_at >= '$testStart';")
Check "本次测试产生 ≥ 4 张已支付订单（脚本场景数下限）" ($rangeOrders -ge 4) $rangeOrders
Check "订单数与托管单数一一对应（$rangeOrders 单 / $rangeSettles 条）" ($rangeOrders -eq $rangeSettles) "$rangeOrders vs $rangeSettles"
Check "不存在「订单已终结但资金仍托管中」的订单" ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_order o JOIN t_settlement s ON s.order_id=o.id WHERE s.status='CUSTODY' AND o.status IN ('COMPLETED','REFUNDED','CLOSED');") -eq '0')
# ★ ADVANCED（第五十八轮新增）必须算**合法终态**：已放款订单退款时钱由平台垫付给买家，
#   托管单走 RELEASED → ADVANCED，卖家转为欠平台钱（t_seller_debt）。
#   把它当成异常会误报 —— 真正要抓的是「已退款但托管单还停在 RELEASED/CUSTODY」。
Check "不存在「已退款但托管单未同步终结」的订单" ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_order o JOIN t_settlement s ON s.order_id=o.id WHERE o.status='REFUNDED' AND s.status NOT IN ('REFUNDED','ADVANCED');") -eq '0')
Check "已关闭订单无残留 FROZEN 台账" ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_stock_freeze f JOIN t_order o ON o.id=f.order_id WHERE f.status='FROZEN' AND o.status='CLOSED';") -eq '0')
Check "已退款订单无残留 RESTOCK_PENDING 台账" ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_stock_freeze f JOIN t_order o ON o.id=f.order_id WHERE f.status='RESTOCK_PENDING' AND o.status='REFUNDED';") -eq '0')

# ---------- 9. 复原测试消耗的库存 ----------
Write-Host ""
Write-Host "> 复原测试消耗的库存（仅测试夹具用）"
if ($SkipDbCheck) {
    Write-Host "  [SKIP] 已指定 -SkipDbCheck"
} else {
    Sql 'duoduo_product' "UPDATE t_sku SET stock = $S1, frozen_stock = 0 WHERE id = $SKU_A;" | Out-Null
    Sql 'duoduo_product' "UPDATE t_sku SET stock = $S2, frozen_stock = 0 WHERE id = $SKU_B;" | Out-Null
    Check "2001 库存已复原为 $S1" ((Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU_A;") -eq "$S1")
    Check "2002 库存已复原为 $S2" ((Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU_B;") -eq "$S2")
    Check "两个 SKU 冻结量均为 0" ((Sql 'duoduo_product' "SELECT COALESCE(SUM(frozen_stock),0) FROM t_sku WHERE id IN ($SKU_A,$SKU_B);") -eq '0')
}

# ---------- 收尾：夹具后台账号降回 USER + 清掉它留下的审计行 ----------
# 本套件为拿到后台入口提了临时运营账号（发货会话）与临时财务账号（E22-4 的正对照）。
# 不收尾的话，每跑一轮就在库里留几个永久可进平台后台的账号（E26 清理存量时就见过一批）；
# 而 E18 起后台每次写操作都会留痕 —— 那些审计行还会假装成"真人干过的运营动作"。
# 放在所有 Check 之后：不新增断言，断言总数守卫的期望值不受影响。
$fxPhones = @($opShip.phone)
if ($null -ne $phoneFin -and "$phoneFin" -ne '') { $fxPhones += $phoneFin }
foreach ($p in $fxPhones) {
    if ($null -ne $p -and "$p" -ne '') { Remove-PlatformOperatorPrivilege -Phone $p | Out-Null }
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
