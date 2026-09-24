# 交易服务 · A11 削峰队列（RocketMQ 异步下单 + 死信 + Sentinel 限流）端到端冒烟（Windows PowerShell 5.1）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\mq-smoke.ps1
#
# 前置：基础容器全部 healthy，且已 mvn package + docker compose build 出最新镜像
#       （网关路由 /api/admin/mq/** 与 trade 队列代码都要在镜像里）。
#
# 与其它冒烟脚本的三点不同（都写进断言，不靠人记）：
#   1. 自管环境：本脚本负责把 MQ profile 容器拉起、把 trade 切到 queue 模式重建，
#      结尾复原 trade=sync 并把 MQ 容器恢复到进场前的状态；
#   2. 夹具从接口动态取，不硬编码价格/库存；凡改库必复原（沿用 order-smoke 教训）；
#   3. 限流压测用 curl.exe --parallel 瞬间并发 —— PS 逐个发请求横跨多个秒窗，
#      QPS=5 根本打不满，会得到"限流没生效"的假红灯。
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    [string]$Password = 'duoduo123'
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

function WaitContainerHealthy([string]$container, [int]$timeoutSec) {
    for ($i = 0; $i -lt $timeoutSec; $i++) {
        $st = & docker ps --filter "name=$container" --format '{{.Status}}' 2>$null
        if ("$st" -match '\(healthy\)') { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

# 轮询任务状态直到出现期望值；返回最终状态字符串（超时返回实际最后状态）
function WaitTask($sess, [string]$key, [string]$want, [int]$timeoutSec) {
    $last = ''
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $r = Call 'Get' "$Gateway/api/orders/placement/$key" $sess $null
        if ($r.status -eq 200 -and $r.body.data) {
            $last = $r.body.data.status
            if ($last -eq $want) { return $last }
            if ($last -eq 'FAILED') { return $last }   # 终态，没必要再等
        }
        Start-Sleep -Seconds 2
    }
    return $last
}

$Compose = Join-Path $PSScriptRoot '..\docker-compose.yml' | Resolve-Path
$env:PATH = 'C:\Program Files\Docker\Docker\resources\bin;' + $env:PATH
if (-not $env:DOCKER_CONFIG) { $env:DOCKER_CONFIG = 'D:\duoduo-infra\docker-nocreds' }

$phoneA = '137' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneOp = '136' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$SKU_A = 2001
$addr = '上海市浦东新区张江路 1 号'

# 进场前状态记录（结尾按此复原）
$mqWasRunning = [bool](& docker ps --filter 'name=duoduo-mq-namesrv' --format '{{.Names}}')
$productWasRunning = [bool](& docker ps --filter 'name=duoduo-app-product' --format '{{.Names}}')

Write-Host ""
Write-Host "=== 多多宠物商店 · 削峰队列（A11：MQ 异步下单 + 死信 + 限流）冒烟 @ $Gateway ==="
Write-Host "用户 A = $phoneA ｜ 运营 = $phoneOp ｜ MQ 进场前运行 = $mqWasRunning"
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

# ---------- 1. 环境自管：DDL 兜底 + MQ 拉起 + trade 切 queue ----------
Write-Host ""
Write-Host "> 环境准备（自管）"
$tbl = Sql 'duoduo_trade' "SHOW TABLES LIKE 't_order_place_task';"
if ("$tbl" -ne 't_order_place_task') {
    Write-Host "  任务台账缺失，灌入增量 DDL ..."
    Get-Content (Join-Path $PSScriptRoot '..\docs\sql\incremental\10-a11-order-place-task.sql') -Raw -Encoding UTF8 |
        & docker exec -i duoduo-mysql mysql -uduoduo -pduoduo --default-character-set=utf8mb4 2>$null
    $tbl = Sql 'duoduo_trade' "SHOW TABLES LIKE 't_order_place_task';"
}
Check "任务台账 t_order_place_task 存在" ("$tbl" -eq 't_order_place_task') $tbl

& docker compose -f $Compose --profile mq up -d rocketmq-namesrv rocketmq-broker 2>&1 | Out-Null
$mqOk = WaitContainerHealthy 'duoduo-mq-namesrv' 90 -and (WaitContainerHealthy 'duoduo-mq-broker' 90)
Check "RocketMQ namesrv + broker healthy" $mqOk

Write-Host "  trade 切 queue 模式重建（QPS=5 测试阈值）..."
$env:DUODUO_TRADE_ORDER_MODE = 'queue'
$env:DUODUO_TRADE_PLACE_QPS = '5'
& docker compose -f $Compose up -d --force-recreate duoduo-trade-app 2>&1 | Out-Null
$tradeOk = WaitContainerHealthy 'duoduo-app-trade' 120
Check "trade 已按 queue 模式重建并 healthy" $tradeOk

# ---------- 2. 权限边界 ----------
Write-Host ""
Write-Host "> 权限边界"
$anon = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$r = Call 'Get' "$Gateway/api/admin/mq/dlq?key=probe" $anon $null
Check "未登录查死信返回 401" ($r.status -eq 401) $r.status
$regA = Register $phoneA '削峰测试甲'
Check "用户 A 注册/登录成功" ($regA.result.status -eq 200) $regA.result.status
$sessA = $regA.session
$r = Call 'Get' "$Gateway/api/admin/mq/dlq?key=probe" $sessA $null
Check "普通用户查死信返回 403" ($r.status -eq 403) $r.status

# ---------- 3. 运营夹具 ----------
Write-Host ""
Write-Host "> 运营夹具"
$regOp = Register $phoneOp '削峰测试-运营'
Check "运营账号注册成功（初始为 USER）" ($regOp.result.status -eq 200) $regOp.result.status
Sql 'duoduo_user' "UPDATE t_user SET role = 'OPERATOR' WHERE phone = '$phoneOp';" | Out-Null
$opRole = Sql 'duoduo_user' "SELECT role FROM t_user WHERE phone = '$phoneOp';"
Check "库内角色已提权为 OPERATOR" ($opRole -eq 'OPERATOR') $opRole
$sessOp = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$loginOp = Call 'Post' "$Gateway/api/auth/login" $sessOp @{ phone = $phoneOp; password = $Password }
Check "运营账号重新登录成功" ($loginOp.status -eq 200) $loginOp.status

# ---------- 4. 削峰主链路：入队 PENDING → PLACED ----------
Write-Host ""
Write-Host "> 削峰主链路"
$batch = Call 'Get' "$Gateway/api/products/internal/skus/batch?ids=$SKU_A" $null $null @{ 'X-Internal-Token' = 'duoduo-local-dev-internal-token' }
$P1 = [long](@($batch.body.data)[0].price)
$S1 = [int](@($batch.body.data)[0].availableStock)
# 自证基线取**库内原值**而非接口可售量：可售 = stock - frozen，
# 若进场前本就有历史遗留冻结，两者不相等，用可售量当基线会误报破坏性。
$startStock = [int](Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU_A;")
$startFrozen = [int](Sql 'duoduo_product' "SELECT COALESCE(frozen_stock,0) FROM t_sku WHERE id = $SKU_A;")
Check "（前置）夹具：2001 单价 > 0 且可售 ≥ 3" (($P1 -gt 0) -and ($S1 -ge 3)) "P1=$P1 S1=$S1"

$r = Call 'Post' "$Gateway/api/cart/items" $sessA @{ skuId = $SKU_A; quantity = 2 }
Check "（准备）加购 2001 × 2" ($r.status -eq 200) $r.status

$key1 = NewKey
$r = Call 'Post' "$Gateway/api/orders/queued" $sessA @{ receiverName = '小峰'; receiverPhone = '13700000000'; receiverAddress = $addr; idempotentKey = $key1 }
Check "队列下单受理返回 200（不是同步下单语义）" ($r.status -eq 200) $r.status
Check "受理即返回任务视图（status=PENDING）" ($r.body.data.status -eq 'PENDING') $r.body.data.status
Check "任务视图回显 idempotentKey" ($r.body.data.idempotentKey -eq $key1) $r.body.data.idempotentKey
Check "任务视图不含订单号（订单尚未产生）" ($null -eq $r.body.data.orderNo) ($r.body.data.orderNo)

$st = WaitTask $sessA $key1 'PLACED' 30
Check "★ 任务在 30s 内到达 PLACED（消费完成落库）" ($st -eq 'PLACED') $st
$orders = (Call 'Get' "$Gateway/api/orders" $sessA $null).body.data
Check "订单已产生且为 1 条" ($orders.total -eq 1) $orders.total
$orderA = @($orders.items)[0]
Check "订单状态 PENDING_PAYMENT" ($orderA.status -eq 'PENDING_PAYMENT') $orderA.status
Check "订单金额 = 单价 × 2（队列链路与同步链路算价一致）" ($orderA.totalAmount -eq ($P1 * 2)) $orderA.totalAmount

# ---------- 5. 入队幂等 ----------
Write-Host ""
Write-Host "> 入队幂等"
$r = Call 'Post' "$Gateway/api/orders/queued" $sessA @{ receiverName = '小峰'; receiverPhone = '13700000000'; receiverAddress = $addr; idempotentKey = $key1 }
Check "同 key 重复入队返回 200" ($r.status -eq 200) $r.status
Check "返回的是同一个任务（同 createdAt）" ($r.body.data.createdAt -eq (Call 'Get' "$Gateway/api/orders/placement/$key1" $sessA $null).body.data.createdAt) 'createdAt 不一致'
$orders2 = (Call 'Get' "$Gateway/api/orders" $sessA $null).body.data
Check "重复入队没有产生第二个订单" ($orders2.total -eq 1) $orders2.total

# ---------- 6. 业务性失败：不重试、任务 FAILED ----------
Write-Host ""
Write-Host "> 业务性失败（购物车为空）"
$key2 = NewKey
$r = Call 'Post' "$Gateway/api/orders/queued" $sessA @{ receiverName = '小峰'; receiverPhone = '13700000000'; receiverAddress = $addr; idempotentKey = $key2 }
Check "空购物车入队仍受理 200（失败发生在消费侧）" ($r.status -eq 200) $r.status
$st = WaitTask $sessA $key2 'FAILED' 30
Check "★ 任务到达 FAILED（业务失败直接终态）" ($st -eq 'FAILED') $st
$task2 = (Call 'Get' "$Gateway/api/orders/placement/$key2" $sessA $null).body.data
Check "失败原因可读且指向购物车规则" ($task2.failReason -match '购物车') $task2.failReason
Check "业务失败没有产生订单" (((Call 'Get' "$Gateway/api/orders" $sessA $null).body.data.total) -eq 1) '订单数变了'

# ---------- 7. 死信：可查 + 可重放 ----------
Write-Host ""
Write-Host "> 死信队列（依赖商品域宕机制造系统级失败）"
$r = Call 'Post' "$Gateway/api/cart/items" $sessA @{ skuId = $SKU_A; quantity = 1 }
Check "（准备）加购 2001 × 1" ($r.status -eq 200) $r.status
# ⚠ docker stop/start 要用**容器名**（duoduo-app-product），不是 compose 服务名（duoduo-product-app）
#   —— 服务名只对 docker compose 子命令有效，docker 原生命令会报 No such container。
& docker stop duoduo-app-product 2>&1 | Out-Null
Check "（准备）商品域已停止" (-not [bool](& docker ps --filter 'name=duoduo-app-product' --format '{{.Names}}'))

$key3 = NewKey
$r = Call 'Post' "$Gateway/api/orders/queued" $sessA @{ receiverName = '小峰'; receiverPhone = '13700000000'; receiverAddress = $addr; idempotentKey = $key3 }
Check "商品宕机时入队仍受理 200" ($r.status -eq 200) $r.status

# 消费侧快照加载打商品域 → DEGRADED → 重试（10s+30s）→ 耗尽进死信；期间任务保持 PENDING
Start-Sleep -Seconds 12
$midSt = (Call 'Get' "$Gateway/api/orders/placement/$key3" $sessA $null).body.data.status
Check "重试期间任务保持 PENDING（没有误标 FAILED）" ($midSt -eq 'PENDING') $midSt

$dlqFound = $false; $dlqRow = $null
for ($i = 0; $i -lt 40; $i++) {
    $r = Call 'Get' "$Gateway/api/admin/mq/dlq?key=$key3" $sessOp $null
    if ($r.status -eq 200 -and $r.body.data -and @($r.body.data).Count -ge 1) { $dlqFound = $true; $dlqRow = @($r.body.data)[0]; break }
    Start-Sleep -Seconds 3
}
Check "★ 死信可查：按幂等键在 DLQ 中查到消息" $dlqFound
if ($dlqFound) {
    Check "死信记录回显幂等键" ($dlqRow.idempotentKey -eq $key3) $dlqRow.idempotentKey
    Check "死信记录带重试次数（≥2，重试耗尽才进 DLQ）" ([int]$dlqRow.reconsumeTimes -ge 2) $dlqRow.reconsumeTimes
    Check "死信 payload 保留原始下单请求（可重放的前提）" ("$($dlqRow.payload)" -match $key3) ($dlqRow.payload | Out-String).Substring(0, 80)
}

& docker start duoduo-app-product 2>&1 | Out-Null
Check "（准备）商品域已恢复" (WaitContainerHealthy 'duoduo-app-product' 90)

$r = Call 'Post' "$Gateway/api/admin/mq/dlq/replay" $sessOp @{ idempotentKey = $key3 }
Check "★ 死信可重放：replay 返回 replayed=1" (($r.status -eq 200) -and ($r.body.data.replayed -eq 1)) "status=$($r.status) replayed=$($r.body.data.replayed)"
$st = WaitTask $sessA $key3 'PLACED' 30
Check "★ 重放后任务到达 PLACED（闭环）" ($st -eq 'PLACED') $st
$orders3 = (Call 'Get' "$Gateway/api/orders" $sessA $null).body.data
Check "重放产生第 2 条订单（幂等键不同，属新订单）" ($orders3.total -eq 2) $orders3.total
$replayedOrder = @(@($orders3.items) | Where-Object { $_.status -eq 'PENDING_PAYMENT' })[0]

# ---------- 8. Sentinel 限流（burst 打满 QPS=5） ----------
Write-Host ""
Write-Host "> Sentinel 限流（30 并发瞬时请求，QPS=5）"
$jar = Join-Path $env:TEMP 'mqsmoke-op-cookies.txt'
# ⚠ PS 5.1 给原生 exe 传参会剥掉参数里内嵌的双引号：`-d '{"a":"b"}'` 到 curl.exe
#   已变成 `-d {a:b}` → 服务端 400。curl.exe 的登录与业务 body 都必须落临时文件走 `@file`。
#   （首版脚本栽在这里：登录 JSON 被剥成裸键名 → 登录失败 → jar 无令牌 →
#    30 发 burst 全 401，限流断言拿到"没有任何标记"的假红灯。）
$loginBodyFile = Join-Path $env:TEMP 'mqsmoke-login-body.json'
Set-Content -Path $loginBodyFile -Value ('{"phone":"' + $phoneOp + '","password":"' + $Password + '"}') -Encoding ASCII -NoNewline
& curl.exe -s -c $jar -X POST "$Gateway/api/auth/login" -H 'Content-Type: application/json' -d "@$loginBodyFile" | Out-Null
Remove-Item $loginBodyFile -Force -ErrorAction SilentlyContinue
# jar 里必须真有会话 cookie（curl 对失败登录也会写文件头，只看文件存在/非空是假绿灯）
Check "（准备）运营登录成功（cookie jar 含会话）" ((Get-Content $jar -ErrorAction SilentlyContinue | Where-Object { $_ -notmatch '^#' } | Measure-Object).Count -ge 1)

# ⚠ PS 5.1 给原生 exe 传参会剥掉参数里内嵌的双引号（JSON 变裸键名 → 服务端 400，
#   请求根本到不了 Sentinel 切面 → 限流断言假红灯）。body 必须落临时文件走 `curl -d @file`。
# 同一个 idempotentKey 打满 30 发即可：限流切面在业务之前，命中阈值后其余全被拦；
# 放行的那几发是同一任务的幂等重放，不会产生多余数据。
$bodyFile = Join-Path $env:TEMP 'mqsmoke-burst-body.json'
Set-Content -Path $bodyFile -Value ('{"receiverName":"rl","receiverPhone":"13800000000","receiverAddress":"Perf Road 1","idempotentKey":"' + (NewKey) + '"}') -Encoding ASCII -NoNewline
$curlArgs = @('-s', '--parallel', '--parallel-max', '30')
for ($i = 1; $i -le 30; $i++) {
    $f = Join-Path $env:TEMP "mqrl_$i.json"
    $curlArgs += @('-o', $f, '-b', $jar, '-X', 'POST', "$Gateway/api/orders/queued", '-H', 'Content-Type: application/json', '-d', "@$bodyFile")
}
& curl.exe @curlArgs | Out-Null
$limited = 0; $okQps = 0
for ($i = 1; $i -le 30; $i++) {
    $f = Join-Path $env:TEMP "mqrl_$i.json"
    if (Test-Path $f) {
        $c = Get-Content $f -Raw
        if ("$c" -match 'RATE_LIMITED') { $limited++ }
        elseif ("$c" -match '"SUCCESS"') { $okQps++ }
        Remove-Item $f -Force -ErrorAction SilentlyContinue
    }
}
Remove-Item $jar -Force -ErrorAction SilentlyContinue
Remove-Item $bodyFile -Force -ErrorAction SilentlyContinue
Check "★ 突发流量触发限流（出现 429 RATE_LIMITED）" ($limited -ge 1) "limited=$limited"
Check "阈值不是 0（仍放行了部分请求）" ($okQps -ge 1) "ok=$okQps"

# ---------- 9. 复原与自证 ----------
Write-Host ""
Write-Host "> 复原与自证"
# 取消两条测试订单，释放冻结库存
$cancelled = 0
foreach ($on in @($orderA.orderNo, $replayedOrder.orderNo)) {
    if ($on) {
        $r = Call 'Post' "$Gateway/api/orders/$on/cancel" $sessA @{ reason = 'mq-smoke 清理' }
        if ($r.status -eq 200) { $cancelled++ }
    }
}
Check "测试订单已取消并释放冻结（2 单）" ($cancelled -eq 2) $cancelled
Call 'Delete' "$Gateway/api/cart/items/$SKU_A" $sessA $null | Out-Null

Write-Host "  trade 复原为 sync 模式..."
Remove-Item Env:DUODUO_TRADE_ORDER_MODE -ErrorAction SilentlyContinue
Remove-Item Env:DUODUO_TRADE_PLACE_QPS -ErrorAction SilentlyContinue
& docker compose -f $Compose up -d --force-recreate duoduo-trade-app 2>&1 | Out-Null
Check "trade 已复原 sync 并 healthy" (WaitContainerHealthy 'duoduo-app-trade' 120)

if (-not $mqWasRunning) {
    Write-Host "  MQ 容器进场前未运行，停止复原..."
    & docker compose -f $Compose --profile mq stop rocketmq-namesrv rocketmq-broker 2>&1 | Out-Null
} else {
    Check "MQ 容器保持运行（进场前就在跑）" ([bool](& docker ps --filter 'name=duoduo-mq-namesrv' --format '{{.Names}}'))
}
if (-not $productWasRunning) { & docker stop duoduo-app-product 2>&1 | Out-Null }

$endStock = [int](Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU_A;")
$endFrozen = [int](Sql 'duoduo_product' "SELECT COALESCE(frozen_stock,0) FROM t_sku WHERE id = $SKU_A;")
Check "自证：2001 库存未被消耗（进场 $startStock → 结束 $endStock）" ($endStock -eq $startStock) $endStock
Check "自证：2001 冻结量与进场一致（本脚本冻结的已全部释放）" ($endFrozen -eq $startFrozen) "$startFrozen -> $endFrozen"

# ---------- 汇总 ----------
Write-Host ""
Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
if ($fail -gt 0) {
    Write-Host "失败项："
    foreach ($f in $failures) { Write-Host "  - $f" }
    exit 1
}
exit 0
