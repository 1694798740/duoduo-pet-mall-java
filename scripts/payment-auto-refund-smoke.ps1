# 交易服务 · 重复收款自动退款（B-04）端到端冒烟（Windows PowerShell 5.1）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\payment-auto-refund-smoke.ps1
#
# 前置：容器全部 healthy，且 duoduo-trade / duoduo-gateway 已用本轮镜像重建。
#
# 验收口径（docs/PLAN.md §7 B-04 · 第一百二十六轮）：
#   1. 重复收款「检出即退款」：检出事务同落退款台账（INIT），不再依赖人工；
#   2. 幂等：一张支付单只落一条退款（uk_refund_pay_no），重复回调 / 重复支付不翻倍；
#   3. 执行：补偿调度把 INIT 推给渠道原路退回，台账到 SUCCESS，渠道退款流水号可对上；
#   4. 可见性：买家能查到自己的退款，运营能查全量台账（admin 端点 + 网关路由）；
#   5. 边界：未登录 401、买家访问运营端点 403、重复支付单再次支付被拒（422）。
#
# 造重复收款的原理：接口层已经不允许对已付款订单再发起支付（paidOne 检查），
# 真实世界里重复收款只发生在「两笔支付单都已创建、先后都支付成功」的并发窗口。
# 脚本用 SQL 直接夹具出第二笔 PENDING 支付单来复现这个窗口（与 coupon-ui-fixture
# 的夹具口径一致：夹具只造数据，业务动作全部走真实接口）。
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    [string]$Password = 'duoduo123',
    [string]$InternalToken = 'duoduo-local-dev-internal-token'
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

function Call([string]$method, [string]$uri, $session, $bodyObj, [hashtable]$headers) {
    $params = @{ Uri = $uri; Method = $method; UseBasicParsing = $true; TimeoutSec = 25 }
    if ($session) { $params.WebSession = $session }
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

function NewKey { return [guid]::NewGuid().ToString() }
$H_INTERNAL = @{ 'X-Internal-Token' = 'internal' }

$phoneA = '137' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneB = '136' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$SKU_A = 2001
$addr = '上海市浦东新区张江路 1 号'
$recv = @{ receiverName = '小林'; receiverPhone = '13700000000'; receiverAddress = $addr }

Write-Host ""
Write-Host "=== 多多宠物商店 · 重复收款自动退款（B-04）冒烟 @ $Gateway ==="
Write-Host "买家 A = $phoneA"
Write-Host ""

# ---------- 0. 网关就绪 + 建表前置 ----------
Write-Host "> 网关就绪与前置检查"
$ready = $false
for ($i = 0; $i -lt 40; $i++) {
    try {
        $h = Invoke-WebRequest -Uri "$Gateway/actuator/health" -UseBasicParsing -TimeoutSec 5
        if ($h.StatusCode -eq 200) { $ready = $true; break }
    } catch { Start-Sleep -Seconds 1 }
}
Check "网关 /actuator/health 就绪" $ready
if (-not $ready) { Write-Host "网关未就绪，终止"; exit 1 }
$refundTable = Sql 'duoduo_trade' "SHOW TABLES LIKE 't_payment_refund';"
Check "前置：t_payment_refund 台账表已存在（迁移 62 已应用）" ($refundTable -eq 't_payment_refund') $refundTable

# ---------- 1. 夹具 ----------
Write-Host ""
Write-Host "> 夹具（买家 A、订单、以及一笔模拟并发窗口的第二支付单）"
$regA = Register $phoneA '自动退款测试甲'
Check "买家 A 注册/登录成功（200）" ($regA.result.status -eq 200) $regA.result.status
$sessA = $regA.session
$regB = Register $phoneB '自动退款测试乙'
Check "买家 B 注册/登录成功（200，用于验证台账归属隔离）" ($regB.result.status -eq 200) $regB.result.status
$sessB = $regB.session

$batch = Call 'Get' "$Gateway/api/products/internal/skus/batch?ids=$SKU_A" $null $null $H_INTERNAL
$P1 = [long]$batch.body.data[0].price
$S1 = [int]$batch.body.data[0].availableStock
Write-Host "  2001 单价 $P1 可售 $S1"
Check "夹具前提：2001 可售 ≥ 5" ($S1 -ge 5) $S1
if ($P1 -le 0 -or $S1 -lt 5) { Write-Host "夹具前提不满足，终止"; exit 1 }

$r = Call 'Post' "$Gateway/api/cart/items" $sessA @{ skuId = $SKU_A; quantity = 1 }
Check "（准备）加购 2001 × 1" ($r.status -eq 200) $r.status
$r = Call 'Post' "$Gateway/api/orders" $sessA ($recv + @{ idempotentKey = (NewKey) })
Check "下单返回 200" ($r.status -eq 200) $r.status
$order1 = @($r.body.data.orders)[0]
$on1 = $order1.orderNo
$payAmount = [long]$order1.payAmount
Check "订单初始状态为 PENDING_PAYMENT" ($order1.status -eq 'PENDING_PAYMENT') $order1.status

$userIdA = Sql 'duoduo_user' "SELECT id FROM t_user WHERE phone = '$phoneA' LIMIT 1;"
$orderId1 = Sql 'duoduo_trade' "SELECT id FROM t_order WHERE order_no = '$on1' LIMIT 1;"
Check "（准备）取到买家 A 的 userId 与订单 ID" ((-not [string]::IsNullOrEmpty($userIdA)) -and (-not [string]::IsNullOrEmpty($orderId1))) "user=$userIdA order=$orderId1"

# ---------- 2. 建立重复收款窗口 ----------
Write-Host ""
Write-Host "> 重复收款窗口：第一笔支付单走接口，第二笔由夹具插入（复现并发双支付）"
$r = Call 'Post' "$Gateway/api/orders/$on1/pay" $sessA @{ channel = 'SANDBOX'; idempotentKey = (NewKey) }
Check "第一笔支付（SANDBOX）创建成功（200）" ($r.status -eq 200) $r.status
$payNo1 = $r.body.data.payNo
Check "第一笔支付单为 PENDING" ($r.body.data.payStatus -eq 'PENDING') $r.body.data.payStatus

# 同渠道不同幂等键再发起：应幂等重放同一笔，而不是开出第二张收银台
$r = Call 'Post' "$Gateway/api/orders/$on1/pay" $sessA @{ channel = 'SANDBOX'; idempotentKey = (NewKey) }
Check "同渠道重复发起支付 → 幂等重放返回同一支付单" ($r.body.data.payNo -eq $payNo1) "$($r.body.data.payNo) vs $payNo1"
Check "同渠道重复发起支付标记 idempotentReplay=true" ($r.body.data.idempotentReplay -eq $true) $r.body.data.idempotentReplay

# 夹具：模拟「另一张已开出的收银台」（并发窗口里真实存在的第二笔 PENDING 支付单）
$payNo2 = 'PAYDUP' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$payId2 = "9$(Get-Random -Minimum 1000000000000000 -Maximum 9999999999999999)"
$idem2 = 'smoke-dup-' + (NewKey)
$sqlInsert = "INSERT INTO t_payment (id, pay_no, order_id, user_id, channel, amount, status, idempotent_key, expire_at, created_at, updated_at) VALUES ($payId2, '$payNo2', $orderId1, $userIdA, 'SANDBOX', $payAmount, 'PENDING', '$idem2', DATE_ADD(NOW(6), INTERVAL 30 MINUTE), NOW(6), NOW(6));"
Sql 'duoduo_trade' $sqlInsert | Out-Null
$pay2Row = Sql 'duoduo_trade' "SELECT status FROM t_payment WHERE pay_no = '$payNo2';"
Check "（夹具）第二笔 PENDING 支付单已就位" ($pay2Row -eq 'PENDING') $pay2Row

# ---------- 3. 第一笔支付成功 → 第二笔支付触发检出 ----------
Write-Host ""
Write-Host "> 支付第一笔 → 沙箱支付第二笔 → 触发重复收款检出"
$r = Call 'Post' "$Gateway/api/payments/sandbox/$payNo1" $sessA $null
Check "第一笔沙箱支付成功（200）" ($r.status -eq 200) $r.status
Check "第一笔支付单 SUCCESS" ($r.body.data.payStatus -eq 'SUCCESS') $r.body.data.payStatus
Check "订单转为 PAID" ($r.body.data.orderStatus -eq 'PAID') $r.body.data.orderStatus

$r = Call 'Post' "$Gateway/api/payments/sandbox/$payNo2" $sessA $null
Check "第二笔沙箱支付被检出为重复收款（200 应答）" ($r.status -eq 200) $r.status
Check "第二笔支付单应答为 CLOSED（未入账）" ($r.body.data.payStatus -eq 'CLOSED') $r.body.data.payStatus
$dupRow = Sql 'duoduo_trade' "SELECT fail_reason FROM t_payment WHERE pay_no = '$payNo2';"
Check "支付单留痕：fail_reason 写明重复支付" ("$dupRow" -like '重复支付*') $dupRow

# ---------- 4. 检出即落台账（幂等） ----------
Write-Host ""
Write-Host "> 检出即退款台账"
$refundRow = Sql 'duoduo_trade' "SELECT refund_no, status, amount FROM t_payment_refund WHERE pay_no = '$payNo2';"
Check "检出同事务落退款台账（1 条 INIT）" ("$refundRow" -like "*`t*INIT*`t$payAmount*" -or "$refundRow" -match "`tINIT`t$payAmount$") $refundRow
$refundNo = ("$refundRow" -split "`t")[0]
Check "台账 refund_no 已生成（21 位）" ("$refundNo".Length -ge 20) $refundNo

# 重复回调/重复支付不得翻倍：对已 CLOSED 的重复支付单再次发起沙箱支付应被拒（422）
$r = Call 'Post' "$Gateway/api/payments/sandbox/$payNo2" $sessA $null
Check "重复支付单再次支付被拒（422）" ($r.status -eq 422) $r.status
$refundCount = Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_payment_refund WHERE pay_no = '$payNo2';"
Check "重复触发后台账仍只有 1 条（uk_refund_pay_no 幂等）" ($refundCount -eq '1') $refundCount

# ---------- 5. 补偿调度执行退款 ----------
Write-Host ""
Write-Host "> 补偿调度执行退款（内部触发 sweep）"
$r = Call 'Post' "$Gateway/api/orders/internal/sweep" $sessA $null $H_INTERNAL
Check "内部触发 sweep 返回 200" ($r.status -eq 200) $r.status
Check "本轮 sweep 执行退款 1 笔" ($r.body.data.refundsExecuted -ge 1) $r.body.data.refundsExecuted

$refunded = Sql 'duoduo_trade' "SELECT status, refunded_at, channel_refund_no FROM t_payment_refund WHERE refund_no = '$refundNo';"
Check "台账状态到 SUCCESS" ("$refunded" -like 'SUCCESS*') $refunded
Check "渠道退款流水号由沙箱渠道回填" ("$refunded" -like '*SANDBOX-REFUND-*') $refunded
$refundedAt1 = ("$refunded" -split "`t")[1]

Start-Sleep -Seconds 1
$r = Call 'Post' "$Gateway/api/orders/internal/sweep" $sessA $null $H_INTERNAL
Check "再次 sweep：待执行退款为 0（不重复执行）" ($r.body.data.refundsExecuted -eq 0 -and $r.body.data.refundsPending -eq 0) "executed=$($r.body.data.refundsExecuted) pending=$($r.body.data.refundsPending)"
$refundedAt2 = (Sql 'duoduo_trade' "SELECT refunded_at FROM t_payment_refund WHERE refund_no = '$refundNo';")
Check "到账时间未变化（幂等不覆盖）" ($refundedAt1 -eq $refundedAt2) "$refundedAt1 vs $refundedAt2"

# ---------- 6. 可见性与权限 ----------
Write-Host ""
Write-Host "> 可见性与权限"
$r = Call 'Get' "$Gateway/api/payments/refunds/mine" $sessA $null
Check "买家 A 查询我的退款（200）" ($r.status -eq 200) $r.status
$mine = @($r.body.data) | Where-Object { $_.payNo -eq $payNo2 }
Check "买家 A 能看到这笔退款且状态 SUCCESS" ($mine -and $mine.status -eq 'SUCCESS') ($mine | ConvertTo-Json -Compress)
Check "退款金额 = 多收金额" ($mine.amount -eq $payAmount) $mine.amount
Check "退款视图带订单号" ($mine.orderNo -eq $on1) $mine.orderNo

$r = Call 'Get' "$Gateway/api/payments/refunds/mine" $sessB $null
Check "买家 B 查不到买家 A 的退款（0 条）" ($r.status -eq 200 -and @($r.body.data).Count -eq 0) "status=$($r.status) count=$(@($r.body.data).Count)"

$r = Call 'Get' "$Gateway/api/payments/refunds/mine" $null $null
Check "未登录查询我的退款 → 401" ($r.status -eq 401) $r.status

$r = Call 'Get' "$Gateway/api/admin/payments/refunds?page=1&pageSize=20&status=SUCCESS" $sessB $null
Check "买家访问运营台账端点 → 403" ($r.status -eq 403) $r.status

$op = New-PlatformOperatorSession -Gateway $Gateway -Password $Password
$r = Call 'Get' "$Gateway/api/admin/payments/refunds?page=1&pageSize=20&payNo=$payNo2" $op.session $null
Check "运营查询退款台账（200，网关路由已放行 /api/admin/payments）" ($r.status -eq 200) $r.status
Check "运营台账 total ≥ 1 且含该退款单" ($r.body.data.total -ge 1 -and (@($r.body.data.items) | Where-Object { $_.refundNo -eq $refundNo })) $r.body.data.total

# ---------- 7. 收尾：复原夹具消耗的库存 ----------
Write-Host ""
Write-Host "> 收尾（本单已真实扣减 1 件 2001 库存，显式复原保证脚本可重复执行）"
Sql 'duoduo_product' "UPDATE t_sku SET stock = stock + 1 WHERE id = $SKU_A;" | Out-Null
$stockNow = Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU_A;"
Check "库存已复原（= $S1）" ([int]$stockNow -eq $S1) $stockNow

Write-Host ""
if ($fail -eq 0) {
    Write-Host "=== 重复收款自动退款冒烟：通过 $pass 项，失败 0 项 ==="
    exit 0
} else {
    Write-Host "=== 重复收款自动退款冒烟：通过 $pass 项，失败 $fail 项 ==="
    $failures | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
