# 交易服务 · 下单/拆单/库存冻结 端到端冒烟（Windows PowerShell 5.1）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\order-smoke.ps1
#
# 前置：容器全部 healthy（docker compose up -d），且已 mvn package + docker compose build 出最新镜像。
#
# 设计原则（沿用 trade-smoke.ps1 的两条教训）：
#   1. 夹具从接口动态取，不硬编码价格/库存；
#   2. 凡改库必先记录原值、复原写回原值，并在结尾自证「库内数据仍是原值」。
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    [string]$Password = 'duoduo123',
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

$phoneA = '139' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneB = '138' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$SKU_A = 2001
$SKU_B = 2002
$SKU_TIGHT = 2004

Write-Host ""
Write-Host "=== 多多宠物商店 · 交易服务（下单/拆单/库存冻结）冒烟 @ $Gateway ==="
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

# ---------- 1. 内部库存写接口必须受保护（安全断言） ----------
Write-Host ""
Write-Host "> 内部接口访问控制"
$noToken = Call 'Post' "$Gateway/api/products/skus/stock/freeze" $null @{ items = @(@{ skuId = $SKU_A; quantity = 1 }) }
Check "无凭据直连冻结接口被拒绝（403）" ($noToken.status -eq 403) $noToken.status
Check "拒绝响应为规范化错误结构（code=FORBIDDEN）" ($noToken.body.code -eq 'FORBIDDEN') $noToken.body.code
$badToken = Call 'Post' "$Gateway/api/products/skus/stock/freeze" $null @{ items = @(@{ skuId = $SKU_A; quantity = 1 }) } @{ 'X-Internal-Token' = 'wrong-token' }
Check "错误凭据同样被拒绝（403）" ($badToken.status -eq 403) $badToken.status

# ---------- 2. 夹具 ----------
Write-Host ""
Write-Host "> 夹具"
$batch = Call 'Get' "$Gateway/api/products/skus/batch?ids=$SKU_A,$SKU_B,$SKU_TIGHT" $null $null
$skus = @{}
foreach ($x in @($batch.body.data)) { $skus["$($x.skuId)"] = $x }
$P1 = [long]$skus["$SKU_A"].price
$P2 = [long]$skus["$SKU_B"].price
$P4 = [long]$skus["$SKU_TIGHT"].price
$S1 = [int]$skus["$SKU_A"].availableStock
$S4 = [int]$skus["$SKU_TIGHT"].availableStock
Write-Host "  2001 单价 $P1 可售 $S1 ｜ 2002 单价 $P2 ｜ 2004 单价 $P4 可售 $S4"
if ($P1 -le 0 -or $P2 -le 0 -or $P4 -le 0 -or $S1 -lt 5 -or $S4 -lt 1) {
    Write-Host "夹具前提不满足，终止本次测试，不执行任何写操作。"
    Check "夹具前提：价格与库存均从接口正确读到" $false "P1=$P1 P2=$P2 P4=$P4 S1=$S1 S4=$S4"
    Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
    exit 1
}
Check "夹具前提：2001 可售 ≥ 5" ($S1 -ge 5) $S1
Check "夹具前提：2004 可售 ≥ 1" ($S4 -ge 1) $S4

$dbStockA = [int](Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU_A;")
$dbStockT = [int](Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU_TIGHT;")
Check "（前置）库内 2001 库存 = $S1 且与接口一致" ($dbStockA -eq $S1) $dbStockA
Check "（前置）库内 2004 库存 = $S4 且与接口一致" ($dbStockT -eq $S4) $dbStockT
Check "（前置）两个 SKU 的冻结量都从 0 开始" ((Sql 'duoduo_product' "SELECT SUM(COALESCE(frozen_stock,0)) FROM t_sku WHERE id IN ($SKU_A,$SKU_TIGHT);") -eq '0')

# ---------- 3. 认证 ----------
Write-Host ""
Write-Host "> 认证"
$regA = Register $phoneA '下单测试甲'
Check "用户 A 注册/登录成功（200）" ($regA.result.status -eq 200) $regA.result.status
$sessA = $regA.session
$anon = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$r = Call 'Get' "$Gateway/api/orders" $anon $null
Check "未登录访问订单接口返回 401" ($r.status -eq 401) $r.status

# ---------- 4. 下单参数校验 ----------
Write-Host ""
Write-Host "> 下单参数校验"
$addr = '上海市浦东新区张江路 1 号'
$r = Call 'Post' "$Gateway/api/orders" $sessA @{ receiverName = '小林'; receiverPhone = '13900000000'; receiverAddress = $addr }
Check "缺 idempotentKey 被拦截（400）" ($r.status -eq 400) $r.status
$r = Call 'Post' "$Gateway/api/orders" $sessA @{ receiverName = '小林'; receiverPhone = '12345'; receiverAddress = $addr; idempotentKey = (NewKey) }
Check "收货手机号格式错误被拦截（400）" ($r.status -eq 400) $r.status
$r = Call 'Post' "$Gateway/api/orders" $sessA @{ receiverPhone = '13900000000'; receiverAddress = $addr; idempotentKey = (NewKey) }
Check "缺收货人被拦截（400）" ($r.status -eq 400) $r.status

$r = Call 'Post' "$Gateway/api/orders" $sessA @{ receiverName = '小林'; receiverPhone = '13900000000'; receiverAddress = $addr; idempotentKey = (NewKey) }
Check "购物车为空时下单返回 422" ($r.status -eq 422) $r.status

# ---------- 5. 下单成功 ----------
Write-Host ""
Write-Host "> 下单（拆单 + 快照 + 冻结）"
$r = Call 'Post' "$Gateway/api/cart/items" $sessA @{ skuId = $SKU_A; quantity = 2 }
Check "（准备）加购 2001 × 2" ($r.status -eq 200) $r.status
$r = Call 'Post' "$Gateway/api/cart/items" $sessA @{ skuId = $SKU_B; quantity = 1 }
Check "（准备）加购 2002 × 1" ($r.status -eq 200) $r.status

$key1 = NewKey
$r = Call 'Post' "$Gateway/api/orders" $sessA @{ receiverName = '小林'; receiverPhone = '13900000000'; receiverAddress = $addr; remark = '请放快递柜'; idempotentKey = $key1 }
Check "下单返回 200" ($r.status -eq 200) $r.status
$order1 = @($r.body.data.orders)[0]
Check "拆单结果：同卖家合并为 1 单" ($r.body.data.orderCount -eq 1) $r.body.data.orderCount
Check "应付金额 = 单价1×2 + 单价2" ($r.body.data.totalPayAmount -eq ($P1 * 2 + $P2)) $r.body.data.totalPayAmount
Check "订单状态为 PENDING_PAYMENT" ($order1.status -eq 'PENDING_PAYMENT') $order1.status
Check "状态中文文案为「待付款」" ($order1.statusLabel -eq '待付款') $order1.statusLabel
Check "明细 2 条" (@($order1.items).Count -eq 2) (@($order1.items).Count)
Check "明细带商品标题快照" (-not [string]::IsNullOrEmpty(@($order1.items)[0].title)) (@($order1.items)[0].title)
Check "明细带规格快照" (-not [string]::IsNullOrEmpty(@($order1.items)[0].specText)) (@($order1.items)[0].specText)
Check "明细单价与下单时一致" (@($order1.items | Where-Object { $_.skuId -eq $SKU_A })[0].price -eq $P1) (@($order1.items | Where-Object { $_.skuId -eq $SKU_A })[0].price)
Check "订单号非空且 21 位" ($order1.orderNo.Length -eq 21) $order1.orderNo.Length
Check "下单后购物车已清空" ((Call 'Get' "$Gateway/api/cart" $sessA $null).body.data.itemCount -eq 0)
Check "订单创建时间返回 ISO8601 UTC 字符串" ($order1.createdAt -match '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$') $order1.createdAt

if (-not $SkipDbCheck) {
    $frozenA = Sql 'duoduo_product' "SELECT COALESCE(frozen_stock,0) FROM t_sku WHERE id = $SKU_A;"
    Check "商品库 2001 冻结量 = 2（下单已冻结）" ($frozenA -eq '2') $frozenA
    $freezeRows = Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_stock_freeze WHERE order_id = (SELECT id FROM t_order WHERE order_no = '$($order1.orderNo)') AND status = 'FROZEN';"
    Check "冻结台账 2 行且状态为 FROZEN" ($freezeRows -eq '2') $freezeRows
    $logRows = Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_order_status_log WHERE order_id = (SELECT id FROM t_order WHERE order_no = '$($order1.orderNo)');"
    Check "状态流转日志 1 行（创建）" ($logRows -eq '1') $logRows
    $availAfter = [int]((@((Call 'Get' "$Gateway/api/products/skus/batch?ids=$SKU_A" $null $null).body.data)[0].availableStock))
    Check "接口可售余量下降为 $S1 - 2" ($availAfter -eq ($S1 - 2)) $availAfter
}

# ---------- 6. 幂等 ----------
Write-Host ""
Write-Host "> 下单幂等（重复提交只产生一个订单）"
$r = Call 'Post' "$Gateway/api/orders" $sessA @{ receiverName = '小林'; receiverPhone = '13900000000'; receiverAddress = $addr; idempotentKey = $key1 }
Check "同一 idempotentKey 重复提交返回 200" ($r.status -eq 200) $r.status
Check "标记为幂等重放（idempotentReplay=true）" ($r.body.data.idempotentReplay -eq $true) $r.body.data.idempotentReplay
Check "返回的是同一个订单号" (@($r.body.data.orders)[0].orderNo -eq $order1.orderNo) (@($r.body.data.orders)[0].orderNo)
$r = Call 'Get' "$Gateway/api/orders" $sessA $null
Check "订单总数仍为 1（没有重复下单）" ($r.body.data.total -eq 1) $r.body.data.total
if (-not $SkipDbCheck) {
    $frozenA2 = Sql 'duoduo_product' "SELECT COALESCE(frozen_stock,0) FROM t_sku WHERE id = $SKU_A;"
    Check "幂等重放没有二次冻结库存（仍为 2）" ($frozenA2 -eq '2') $frozenA2
}

# ---------- 7. 查询 ----------
Write-Host ""
Write-Host "> 订单查询"
$r = Call 'Get' "$Gateway/api/orders?status=PENDING_PAYMENT" $sessA $null
Check "按状态过滤可查到待付款订单" ($r.body.data.total -eq 1) $r.body.data.total
$r = Call 'Get' "$Gateway/api/orders?status=PAID" $sessA $null
Check "按 PAID 过滤查不到（本阶段未支付）" ($r.body.data.total -eq 0) $r.body.data.total
$r = Call 'Get' "$Gateway/api/orders?status=NOT_A_STATUS" $sessA $null
Check "非法状态参数被拒绝（400）" ($r.status -eq 400) $r.status
$r = Call 'Get' "$Gateway/api/orders/$($order1.orderNo)" $sessA $null
Check "订单详情返回 200" ($r.status -eq 200) $r.status
Check "详情订单号一致" ($r.body.data.orderNo -eq $order1.orderNo) $r.body.data.orderNo
Check "详情含收货信息" ($r.body.data.receiverPhone -eq '13900000000') $r.body.data.receiverPhone
$r = Call 'Get' "$Gateway/api/orders/999999999999999999999999" $sessA $null
Check "不存在的订单返回 404" ($r.status -eq 404) $r.status

Write-Host ""
Write-Host "> 越权（拿不到/改不了别人的订单）"
$regB = Register $phoneB '下单测试乙'
Check "用户 B 注册/登录成功（200）" ($regB.result.status -eq 200) $regB.result.status
$r = Call 'Get' "$Gateway/api/orders/$($order1.orderNo)" $regB.session $null
Check "用户 B 查 A 的订单返回 404" ($r.status -eq 404) $r.status
$r = Call 'Post' "$Gateway/api/orders/$($order1.orderNo)/cancel" $regB.session @{ reason = '试试' }
Check "用户 B 取消 A 的订单返回 404" ($r.status -eq 404) $r.status
$r = Call 'Get' "$Gateway/api/orders" $regB.session $null
Check "用户 B 的订单列表为空" ($r.body.data.total -eq 0) $r.body.data.total

# ---------- 8. 取消与释放 ----------
Write-Host ""
Write-Host "> 取消订单（释放冻结）"
$r = Call 'Post' "$Gateway/api/orders/$($order1.orderNo)/cancel" $sessA @{ reason = '不想要了' }
Check "取消订单返回 200" ($r.status -eq 200) $r.status
Check "订单状态变为 CLOSED" ($r.body.data.status -eq 'CLOSED') $r.body.data.status
Check "状态文案为「已关闭」" ($r.body.data.statusLabel -eq '已关闭') $r.body.data.statusLabel
Check "关闭时间非空" (-not [string]::IsNullOrEmpty($r.body.data.closedAt)) $r.body.data.closedAt
Check "关闭原因已记录" ($r.body.data.closeReason -eq '不想要了') $r.body.data.closeReason
$r = Call 'Post' "$Gateway/api/orders/$($order1.orderNo)/cancel" $sessA @{ reason = '再取消一次' }
Check "重复取消返回 422" ($r.status -eq 422) $r.status
Check "重复取消错误码为 ORDER_STATUS_ILLEGAL" ($r.body.code -eq 'ORDER_STATUS_ILLEGAL') $r.body.code

if (-not $SkipDbCheck) {
    $frozenAfter = Sql 'duoduo_product' "SELECT COALESCE(frozen_stock,0) FROM t_sku WHERE id = $SKU_A;"
    Check "取消后商品库冻结量回到 0（已释放）" ($frozenAfter -eq '0') $frozenAfter
    $released = Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_stock_freeze WHERE order_id = (SELECT id FROM t_order WHERE order_no = '$($order1.orderNo)') AND status = 'RELEASED';"
    Check "冻结台账 2 行均标记为 RELEASED" ($released -eq '2') $released
    $availBack = [int]((@((Call 'Get' "$Gateway/api/products/skus/batch?ids=$SKU_A" $null $null).body.data)[0].availableStock))
    Check "接口可售余量恢复到 $S1" ($availBack -eq $S1) $availBack
    $cancelLog = Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_order_status_log WHERE order_id = (SELECT id FROM t_order WHERE order_no = '$($order1.orderNo)');"
    Check "状态流转日志 2 行（创建 + 关闭）" ($cancelLog -eq '2') $cancelLog
}

# ---------- 9. 释放后可再次下单（证明释放是真的） ----------
Write-Host ""
Write-Host "> 释放后再次下单"
$r = Call 'Post' "$Gateway/api/cart/items" $sessA @{ skuId = $SKU_A; quantity = 1 }
Check "（准备）重新加购 2001 × 1" ($r.status -eq 200) $r.status
$r = Call 'Post' "$Gateway/api/orders" $sessA @{ receiverName = '小林'; receiverPhone = '13900000000'; receiverAddress = $addr; idempotentKey = (NewKey) }
Check "释放后可再次下单成功（200）" ($r.status -eq 200) $r.status
$order2 = @($r.body.data.orders)[0]
if (-not $SkipDbCheck) {
    $f2 = Sql 'duoduo_product' "SELECT COALESCE(frozen_stock,0) FROM t_sku WHERE id = $SKU_A;"
    Check "第二单冻结量为 1" ($f2 -eq '1') $f2
}
$r = Call 'Post' "$Gateway/api/orders/$($order2.orderNo)/cancel" $sessA @{ reason = '测试结束清理' }
Check "第二单取消成功（200）" ($r.status -eq 200) $r.status

# ---------- 10. 部分冻结失败的补偿（关键用例） ----------
Write-Host ""
Write-Host "> 部分冻结失败必须补偿（不留幽灵冻结）"
if ($SkipDbCheck) {
    Write-Host "  [SKIP] 已指定 -SkipDbCheck"
} else {
    $beforeCount = [int]((Call 'Get' "$Gateway/api/orders" $sessA $null).body.data.total)
    $r = Call 'Post' "$Gateway/api/cart/items" $sessA @{ skuId = $SKU_A; quantity = 1 }
    Check "（准备）加购 2001 × 1" ($r.status -eq 200) $r.status
    $r = Call 'Post' "$Gateway/api/cart/items" $sessA @{ skuId = $SKU_TIGHT; quantity = 1 }
    Check "（准备）加购 2004 × 1" ($r.status -eq 200) $r.status

    # 把 2004 库存改成 0：下单时 2001 能冻成功、2004 冻失败 → 触发部分失败补偿
    Sql 'duoduo_product' "UPDATE t_sku SET stock = 0 WHERE id = $SKU_TIGHT;" | Out-Null
    $zero = Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU_TIGHT;"
    Check "（准备）2004 库存已置 0" ($zero -eq '0') $zero

    $r = Call 'Post' "$Gateway/api/orders" $sessA @{ receiverName = '小林'; receiverPhone = '13900000000'; receiverAddress = $addr; idempotentKey = (NewKey) }
    Check "库存不足时下单失败（422）" ($r.status -eq 422) $r.status

    $afterCount = [int]((Call 'Get' "$Gateway/api/orders" $sessA $null).body.data.total)
    Check "失败订单没有落库（订单数不变）" ($afterCount -eq $beforeCount) "$beforeCount -> $afterCount"
    $cartNow = (Call 'Get' "$Gateway/api/cart" $sessA $null).body.data.itemCount
    Check "失败后购物车条目保留（未被清空）" ($cartNow -eq 2) $cartNow
    $ghost = Sql 'duoduo_product' "SELECT COALESCE(frozen_stock,0) FROM t_sku WHERE id = $SKU_A;"
    Check "★ 已成功冻结的 2001 被补偿释放（冻结量回到 0，无幽灵冻结）" ($ghost -eq '0') $ghost
    $ghostRows = Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_stock_freeze WHERE status = 'FROZEN';"
    Check "★ 交易库没有残留 FROZEN 台账行" ($ghostRows -eq '0') $ghostRows

    # 复原 2004 库存并清空购物车
    Sql 'duoduo_product' "UPDATE t_sku SET stock = $S4 WHERE id = $SKU_TIGHT;" | Out-Null
    $backT = Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU_TIGHT;"
    Check "（复原）2004 库存写回原值 $S4" ($backT -eq "$S4") $backT
    Call 'Delete' "$Gateway/api/cart/items/$SKU_A" $sessA $null | Out-Null
    Call 'Delete' "$Gateway/api/cart/items/$SKU_TIGHT" $sessA $null | Out-Null
}

# ---------- 11. 自证不污染数据 ----------
Write-Host ""
Write-Host "> 自证非破坏性"
if ($SkipDbCheck) {
    Write-Host "  [SKIP] 已指定 -SkipDbCheck"
} else {
    $endA = Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU_A;"
    $endT = Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU_TIGHT;"
    $endFrozen = Sql 'duoduo_product' "SELECT SUM(COALESCE(frozen_stock,0)) FROM t_sku WHERE id IN ($SKU_A,$SKU_TIGHT);"
    Check "测试结束后 2001 库存仍为原值 $S1" ($endA -eq "$S1") $endA
    Check "测试结束后 2004 库存仍为原值 $S4" ($endT -eq "$S4") $endT
    Check "测试结束后两个 SKU 冻结量均为 0" ($endFrozen -eq '0') $endFrozen
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
