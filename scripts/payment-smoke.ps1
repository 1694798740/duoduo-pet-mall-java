# 交易服务 · 支付幂等 / 订单状态机 / 超时关单 端到端冒烟（Windows PowerShell 5.1）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\payment-smoke.ps1
#
# 前置：容器全部 healthy（docker compose up -d），且已 mvn package + docker compose build 出最新镜像。
#
# 设计原则（沿用前两套脚本的教训）：
#   1. 夹具从接口动态取，不硬编码价格/库存；
#   2. 凡改库必先记录原值，结尾自证「库内数据已复原」；
#   3. 已付款订单会真实扣减库存，所以结尾会把测试消耗的库存**显式复原**，
#      以保证脚本可反复执行（生产环境当然没有这种操作，仅测试夹具使用）。
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    # 直连交易服务端口：用来验证「内部接口被外部直连」时会被服务自身拒绝
    [string]$Trade = 'http://127.0.0.1:8083',
    [string]$Password = 'duoduo123',
    [string]$InternalToken = 'duoduo-local-dev-internal-token',
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
$H_INTERNAL = @{ 'X-Internal-Token' = $InternalToken }

$phoneA = '139' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneB = '138' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$SKU_A = 2001
$SKU_B = 2002
$addr = '上海市浦东新区张江路 1 号'
$recv = @{ receiverName = '小林'; receiverPhone = '13900000000'; receiverAddress = $addr }

Write-Host ""
Write-Host "=== 多多宠物商店 · 交易服务（支付/状态机/超时关单）冒烟 @ $Gateway ==="
Write-Host "用户 A = $phoneA"
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
Write-Host "> 内部接口访问控制（第一层：服务自身拒绝外部直连）"
$r = Call 'Post' "$Trade/api/orders/internal/sweep" $null $null
Check "直连交易服务、无凭据触发关单调度被拒绝（403）" ($r.status -eq 403) $r.status
Check "拒绝响应为规范化错误结构（code=FORBIDDEN）" ($r.body.code -eq 'FORBIDDEN') $r.body.code
$r = Call 'Post' "$Trade/api/orders/internal/sweep" $null $null @{ 'X-Internal-Token' = 'wrong-token' }
Check "直连交易服务、错误凭据被拒绝（403）" ($r.status -eq 403) $r.status
$r = Call 'Post' "$Trade/api/orders/ANYORDER/ship" $null $null
Check "直连交易服务、无凭据发货被拒绝（403）" ($r.status -eq 403) $r.status

Write-Host "> 内部接口访问控制（第二层：网关先挡未登录流量）"
$r = Call 'Post' "$Gateway/api/orders/internal/sweep" $null $null
Check "经网关未登录触发内部接口返回 401（网关先拦一层）" ($r.status -eq 401) $r.status

# ---------- 2. 夹具 ----------
Write-Host ""
Write-Host "> 夹具"
$batch = Call 'Get' "$Gateway/api/products/internal/skus/batch?ids=$SKU_A,$SKU_B" $null $null @{ 'X-Internal-Token' = 'duoduo-local-dev-internal-token' }
$skus = @{}
foreach ($x in @($batch.body.data)) { $skus["$($x.skuId)"] = $x }
$P1 = [long]$skus["$SKU_A"].price
$P2 = [long]$skus["$SKU_B"].price
$S1 = [int]$skus["$SKU_A"].availableStock
$S2 = [int]$skus["$SKU_B"].availableStock
Write-Host "  2001 单价 $P1 可售 $S1 ｜ 2002 单价 $P2 可售 $S2"
if ($P1 -le 0 -or $P2 -le 0 -or $S1 -lt 10) {
    Write-Host "夹具前提不满足，终止本次测试，不执行任何写操作。"
    Check "夹具前提：价格与库存均从接口正确读到" $false "P1=$P1 P2=$P2 S1=$S1 S2=$S2"
    Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
    exit 1
}
Check "夹具前提：2001 可售 ≥ 10（够本脚本消耗）" ($S1 -ge 10) $S1

$dbS1 = [int](Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU_A;")
$dbS2 = [int](Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU_B;")
Check "（前置）库内 2001 库存与接口一致（$S1）" ($dbS1 -eq $S1) $dbS1
Check "（前置）库内 2002 库存与接口一致（$S2）" ($dbS2 -eq $S2) $dbS2
Check "（前置）两个 SKU 冻结量均为 0" ((Sql 'duoduo_product' "SELECT COALESCE(SUM(frozen_stock),0) FROM t_sku WHERE id IN ($SKU_A,$SKU_B);") -eq '0')

# ---------- 3. 认证 ----------
Write-Host ""
Write-Host "> 认证"
$regA = Register $phoneA '支付测试甲'
Check "用户 A 注册/登录成功（200）" ($regA.result.status -eq 200) $regA.result.status
$sessA = $regA.session
$anon = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$r = Call 'Post' "$Gateway/api/orders/ANY/pay" $anon @{ channel = 'MOCK'; idempotentKey = (NewKey) }
Check "未登录支付返回 401" ($r.status -eq 401) $r.status

# ---------- 4. 下单（待付款） ----------
Write-Host ""
Write-Host "> 准备一单待付款订单"
$r = Call 'Post' "$Gateway/api/cart/items" $sessA @{ skuId = $SKU_A; quantity = 2 }
Check "（准备）加购 2001 × 2" ($r.status -eq 200) $r.status
$r = Call 'Post' "$Gateway/api/cart/items" $sessA @{ skuId = $SKU_B; quantity = 1 }
Check "（准备）加购 2002 × 1" ($r.status -eq 200) $r.status
$r = Call 'Post' "$Gateway/api/orders" $sessA ($recv + @{ idempotentKey = (NewKey) })
Check "下单返回 200" ($r.status -eq 200) $r.status
$order1 = @($r.body.data.orders)[0]
$on1 = $order1.orderNo
Check "订单初始状态为 PENDING_PAYMENT" ($order1.status -eq 'PENDING_PAYMENT') $order1.status

# ---------- 5. 支付 ----------
Write-Host ""
Write-Host "> 支付（模拟渠道即时成功）"
$payKey = NewKey
$r = Call 'Post' "$Gateway/api/orders/$on1/pay" $sessA @{ channel = 'MOCK'; idempotentKey = $payKey }
Check "支付返回 200" ($r.status -eq 200) $r.status
Check "支付单状态为 SUCCESS" ($r.body.data.payStatus -eq 'SUCCESS') $r.body.data.payStatus
Check "订单转为 PAID" ($r.body.data.orderStatus -eq 'PAID') $r.body.data.orderStatus
Check "状态文案为「已付款」" ($r.body.data.orderStatusLabel -eq '已付款') $r.body.data.orderStatusLabel
Check "支付金额 = 应付金额" ($r.body.data.amount -eq $order1.payAmount) $r.body.data.amount
Check "非幂等重放（首次支付）" ($r.body.data.idempotentReplay -eq $false) $r.body.data.idempotentReplay
Check "支付单号非空" (-not [string]::IsNullOrEmpty($r.body.data.payNo)) $r.body.data.payNo
$payNo1 = $r.body.data.payNo

$r = Call 'Get' "$Gateway/api/orders/$on1" $sessA $null
Check "订单详情已付款时间非空" (-not [string]::IsNullOrEmpty($r.body.data.paidAt)) $r.body.data.paidAt
Check "订单详情状态为 PAID" ($r.body.data.status -eq 'PAID') $r.body.data.status

if (-not $SkipDbCheck) {
    Check "商品库 2001 库存已扣减为 $S1 - 2" ((Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU_A;") -eq "$($S1 - 2)")
    Check "支付后 2001 冻结量归零" ((Sql 'duoduo_product' "SELECT COALESCE(frozen_stock,0) FROM t_sku WHERE id = $SKU_A;") -eq '0')
    Check "冻结台账 2 行均置为 DEDUCTED" ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_stock_freeze WHERE order_id=(SELECT id FROM t_order WHERE order_no='$on1') AND status='DEDUCTED';") -eq '2')
    Check "支付单落库 1 行 SUCCESS" ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_payment WHERE order_id=(SELECT id FROM t_order WHERE order_no='$on1') AND status='SUCCESS';") -eq '1')
    Check "状态流转日志 2 行（创建 + 支付）" ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_order_status_log WHERE order_id=(SELECT id FROM t_order WHERE order_no='$on1');") -eq '2')
}

# ---------- 6. 支付幂等 ----------
Write-Host ""
Write-Host "> 支付幂等（重复回调只入账一次）"
$r = Call 'Post' "$Gateway/api/orders/$on1/pay" $sessA @{ channel = 'MOCK'; idempotentKey = $payKey }
Check "同一 (渠道+幂等键) 重复支付返回 200" ($r.status -eq 200) $r.status
Check "标记为幂等重放（idempotentReplay=true）" ($r.body.data.idempotentReplay -eq $true) $r.body.data.idempotentReplay
Check "返回同一支付单号" ($r.body.data.payNo -eq $payNo1) $r.body.data.payNo
if (-not $SkipDbCheck) {
    Check "重复回调没有二次扣减库存（仍为 $S1 - 2）" ((Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU_A;") -eq "$($S1 - 2)")
    Check "支付单仍只有 1 行" ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_payment WHERE order_id=(SELECT id FROM t_order WHERE order_no='$on1');") -eq '1')
}
$r = Call 'Post' "$Gateway/api/orders/$on1/pay" $sessA @{ channel = 'MOCK'; idempotentKey = (NewKey) }
Check "换新幂等键重复支付同一订单被拒（422）" ($r.status -eq 422) $r.status
Check "错误码为 ORDER_STATUS_ILLEGAL" ($r.body.code -eq 'ORDER_STATUS_ILLEGAL') $r.body.code
$r = Call 'Post' "$Gateway/api/orders/$on1/cancel" $sessA @{ reason = '反悔了' }
Check "已付款订单不能取消（422）" ($r.status -eq 422) $r.status

# ---------- 7. 发货与确认收货 ----------
Write-Host ""
Write-Host "> 发货与确认收货"
$r = Call 'Post' "$Gateway/api/orders/$on1/confirm" $sessA $null
Check "未发货不能确认收货（422）" ($r.status -eq 422) $r.status
$r = Call 'Post' "$Gateway/api/orders/$on1/ship" $sessA $null $H_INTERNAL
Check "带内部凭据发货成功（200）" ($r.status -eq 200) $r.status
Check "订单转为 SHIPPED" ($r.body.data.status -eq 'SHIPPED') $r.body.data.status
Check "状态文案为「已发货」" ($r.body.data.statusLabel -eq '已发货') $r.body.data.statusLabel
Check "发货时间非空" (-not [string]::IsNullOrEmpty($r.body.data.shippedAt)) $r.body.data.shippedAt
$r = Call 'Post' "$Gateway/api/orders/$on1/ship" $sessA $null $H_INTERNAL
Check "重复发货被拒（422）" ($r.status -eq 422) $r.status
$r = Call 'Post' "$Gateway/api/orders/$on1/confirm" $sessA $null
Check "确认收货返回 200" ($r.status -eq 200) $r.status
Check "订单转为 COMPLETED" ($r.body.data.status -eq 'COMPLETED') $r.body.data.status
Check "状态文案为「已完成」" ($r.body.data.statusLabel -eq '已完成') $r.body.data.statusLabel
Check "完成时间非空" (-not [string]::IsNullOrEmpty($r.body.data.finishedAt)) $r.body.data.finishedAt
$r = Call 'Post' "$Gateway/api/orders/$on1/confirm" $sessA $null
Check "重复确认收货被拒（422）" ($r.status -eq 422) $r.status
if (-not $SkipDbCheck) {
    Check "状态流转日志 4 行（创建/支付/发货/完成）" ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_order_status_log WHERE order_id=(SELECT id FROM t_order WHERE order_no='$on1');") -eq '4')
}

# ---------- 8. 越权 ----------
Write-Host ""
Write-Host "> 越权"
$regB = Register $phoneB '支付测试乙'
Check "用户 B 注册/登录成功（200）" ($regB.result.status -eq 200) $regB.result.status
$r = Call 'Post' "$Gateway/api/orders/$on1/pay" $regB.session @{ channel = 'MOCK'; idempotentKey = (NewKey) }
Check "用户 B 支付 A 的订单返回 404" ($r.status -eq 404) $r.status
$r = Call 'Post' "$Gateway/api/orders/$on1/confirm" $regB.session $null
Check "用户 B 确认收货 A 的订单返回 404" ($r.status -eq 404) $r.status

# ---------- 9. 超时关单 ----------
Write-Host ""
Write-Host "> 超时关单（补偿调度）"
$r = Call 'Post' "$Gateway/api/cart/items" $sessA @{ skuId = $SKU_A; quantity = 1 }
Check "（准备）加购 2001 × 1" ($r.status -eq 200) $r.status
$r = Call 'Post' "$Gateway/api/orders" $sessA ($recv + @{ idempotentKey = (NewKey) })
Check "（准备）再下一单（待付款）" ($r.status -eq 200) $r.status
$order2 = @($r.body.data.orders)[0]
$on2 = $order2.orderNo
if (-not $SkipDbCheck) {
    Check "（准备）第二单已冻结 2001 库存 1 件" ((Sql 'duoduo_product' "SELECT COALESCE(frozen_stock,0) FROM t_sku WHERE id = $SKU_A;") -eq '1')
    # 把过期时间改到过去，模拟「已超时未支付」（这是测试夹具，不是业务写入）
    Sql 'duoduo_trade' "UPDATE t_order SET expire_at = DATE_SUB(UTC_TIMESTAMP(), INTERVAL 1 MINUTE) WHERE order_no = '$on2';" | Out-Null
    Check "（准备）第二单过期时间已置为过去" ((Sql 'duoduo_trade' "SELECT CASE WHEN expire_at < UTC_TIMESTAMP() THEN 'yes' ELSE 'no' END FROM t_order WHERE order_no='$on2';") -eq 'yes')

    $r = Call 'Post' "$Gateway/api/orders/internal/sweep" $sessA $null $H_INTERNAL
    Check "带内部凭据触发关单调度返回 200" ($r.status -eq 200) $r.status
    # 注意：定时调度每 60 秒也会跑一次，所以不校验「本轮关了几单」，只校验最终状态 —— 这样断言是确定性的
    $r = Call 'Get' "$Gateway/api/orders/$on2" $sessA $null
    Check "超时订单已自动关闭（CLOSED）" ($r.body.data.status -eq 'CLOSED') $r.body.data.status
    Check "关闭原因标明超时" ($r.body.data.closeReason -match '超时') $r.body.data.closeReason
    Check "超时关闭时间非空" (-not [string]::IsNullOrEmpty($r.body.data.closedAt)) $r.body.data.closedAt
    $closedAt1 = $r.body.data.closedAt
    Check "★ 超时关单已释放冻结（2001 冻结量回到 0）" ((Sql 'duoduo_product' "SELECT COALESCE(frozen_stock,0) FROM t_sku WHERE id = $SKU_A;") -eq '0')
    Check "冻结台账置为 RELEASED" ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_stock_freeze WHERE order_id=(SELECT id FROM t_order WHERE order_no='$on2') AND status='RELEASED';") -eq '1')
    Check "状态日志含 SYSTEM 操作者（自动关单）" ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_order_status_log WHERE order_id=(SELECT id FROM t_order WHERE order_no='$on2') AND operator_type='SYSTEM';") -eq '1')

    # 可重入：再触发一次不应报错，且不会把已关闭的订单重复处理
    # （用「关闭时间是否被改写」判断，而不是用返回的计数 —— 计数会受其他历史数据影响，不确定）
    $r = Call 'Post' "$Gateway/api/orders/internal/sweep" $sessA $null $H_INTERNAL
    Check "重复触发关单调度不报错（200）" ($r.status -eq 200) $r.status
    $r = Call 'Get' "$Gateway/api/orders/$on2" $sessA $null
    Check "重复调度未改写已关闭订单的关闭时间（可重入）" ($r.body.data.closedAt -eq $closedAt1) $r.body.data.closedAt
    # 不变式：**已关闭订单不允许残留 FROZEN 台账**（在途订单的冻结当然要保留，所以这里按订单状态限定）
    Check "已关闭订单无残留 FROZEN 台账（可重入）" ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_stock_freeze f JOIN t_order o ON o.id=f.order_id WHERE f.status='FROZEN' AND o.status='CLOSED';") -eq '0')
    Check "2001 冻结量仍为 0（未重复释放）" ((Sql 'duoduo_product' "SELECT COALESCE(frozen_stock,0) FROM t_sku WHERE id = $SKU_A;") -eq '0')
}

# ---------- 10. 复原测试消耗的库存 ----------
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

# ---------- 汇总 ----------
Write-Host ""
Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
if ($fail -gt 0) {
    Write-Host "失败项："
    foreach ($f in $failures) { Write-Host "  - $f" }
    exit 1
}
exit 0
