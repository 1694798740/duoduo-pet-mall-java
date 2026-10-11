# 交易域 · 钱与库存每日自动对账（S3）端到端冒烟（Windows PowerShell 5.1）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\recon-smoke.ps1
#        powershell -NoProfile -ExecutionPolicy Bypass -File scripts\recon-smoke.ps1 -SkipFailPathCheck
#
# 前置：容器全部 healthy；已 mvn package + docker compose build 出最新镜像。
#       docs/sql/incremental/34-s3-recon.sql 已执行（本脚本会兜底灌入）。
#
# 本套要证明的核心事实（不是"接口返回 200"，而是"结论可以被验证"）：
#   ① 干净数据上不报差异 —— 假差异比漏报更贵：查两次发现是判据自己的问题之后，
#      真差异也没人信了；
#   ② 每一类差异都**真的能被抓到**，并且定位到**具体对象**（"有 N 条差异"是无法行动的结论）；
#   ③ ★ 抓到的差异在数据复原后**必须消失** —— 否则这条规则测的其实是别的东西。
#      这是本套最有分量的一类断言，"能抓到"与"抓对了"是两回事；
#   ④ ★ "没跑成"（FAILED）与"跑成了且干净"（CLEAN）必须分得开：
#      真实做法是**停掉商品服务**再跑库存对账，断言结果是 FAILED 而不是全绿。
#      把失败记成全绿是这套功能最危险的失效方式 —— 报表可信是它唯一的产出；
#   ⑤ 校验对象数为 0 同样不许算"干净"（0 个对象必然没有差异，"没看"与"看了没问题"长得一样）；
#   ⑥ 同一天重跑**覆盖**而不是累积；
#   ⑦ 手动触发留痕，且动作代号必须被收件方**正确识别**（E23 踩过：落成 UNKNOWN）。
#
# 设计纪律（沿用既有脚本教训）：
#   1. 夹具从库里动态取，不硬编码 id；差异对象 id 一律从接口响应里拿；
#   2. 凡改库必先记录原值，结尾自证"库内数据已复原"；
#   3. 每条"能抓到"的断言都配一条"复原后消失"的断言（同一个对象、同一条规则）；
#   4. 规则清单以**运行中的服务**为准，并与源码里声明的条数对照 ——
#      源码里写着与服务里跑着的是两回事（本项目踩过「改了代码、容器里跑的是旧版本」）。
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    # 直连交易服务端口：验证"绕过网关匿名直连"不会被放行
    [string]$Trade = 'http://127.0.0.1:8083',
    [string]$Password = 'duoduo123',
    [string]$OperatorPassword = 'duoduo12345',
    # 停商品服务再跑对账那一段会重启容器；跑整套回归时若嫌慢可跳过（但 CI 不该跳）
    [switch]$SkipFailPathCheck
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

. (Join-Path $PSScriptRoot 'lib\platform-operator.ps1')

function Call([string]$method, [string]$uri, $session, $bodyObj, [hashtable]$headers) {
    $params = @{ Uri = $uri; Method = $method; UseBasicParsing = $true; TimeoutSec = 30 }
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

function Sql([string]$db, [string]$sql) {
    $env:PATH = 'C:\Program Files\Docker\Docker\resources\bin;' + $env:PATH
    if (-not $env:DOCKER_CONFIG) { $env:DOCKER_CONFIG = 'D:\duoduo-infra\docker-nocreds' }
    $out = & docker exec duoduo-mysql mysql -uduoduo -pduoduo -N -B --default-character-set=utf8mb4 -D $db -e $sql 2>&1
    if ($LASTEXITCODE -ne 0) { return $null }
    return ((@($out) | Where-Object { "$_" -notmatch 'Warning' }) -join "`n").Trim()
}

# 从一轮的差异明细里挑出「某条规则 × 某个对象」的条数。
# 刻意按 (rule, refId) 两个键一起筛：只看规则名会得到"这一类有没有"，
# 而那个结论无法定位到具体对象，也就无法判断"改对了没有"。
function DiffCount($session, $runId, [string]$rule, [string]$refId) {
    $r = Call 'Get' "$Gateway/api/admin/recon/runs/$runId/diffs?limit=1000" $session
    if ($r.status -ne 200) { return -1 }
    $n = 0
    foreach ($d in @($r.body.data)) {
        if ("$($d.rule)" -eq $rule -and "$($d.refId)" -eq $refId) { $n++ }
    }
    return $n
}

function TriggerRun($session, [string]$scope, [string]$bizDate) {
    return Call 'Post' "$Gateway/api/admin/recon/runs" $session @{ scope = $scope; bizDate = $bizDate }
}

function WaitHealthy([string]$container, [int]$maxSeconds = 180) {
    for ($i = 0; $i -lt $maxSeconds; $i += 3) {
        $h = "$(& docker inspect --format '{{.State.Health.Status}}' $container 2>$null)".Trim()
        if ($h -eq 'healthy') { return $true }
        Start-Sleep -Seconds 3
    }
    return $false
}

## ============================================================================
## 0. 环境自管：DDL 兜底 + 就绪检查
## ============================================================================
Write-Host ""
Write-Host "> 环境准备（自管）"

$tblRun = Sql 'duoduo_trade' "SHOW TABLES LIKE 't_recon_run';"
if ("$tblRun" -ne 't_recon_run') {
    Write-Host "  交易库对账表缺失，灌入增量 DDL ..."
    Get-Content (Join-Path $PSScriptRoot '..\docs\sql\incremental\34-s3-recon.sql') -Raw -Encoding UTF8 |
        & docker exec -i duoduo-mysql mysql -uduoduo -pduoduo --default-character-set=utf8mb4 2>$null
    $tblRun = Sql 'duoduo_trade' "SHOW TABLES LIKE 't_recon_run';"
}
Check "对账轮次表 t_recon_run 存在" ("$tblRun" -eq 't_recon_run') $tblRun
Check "对账明细表 t_recon_diff 存在" `
    ((Sql 'duoduo_trade' "SHOW TABLES LIKE 't_recon_diff';") -eq 't_recon_diff')

$op = New-PlatformOperatorSession -Gateway $Gateway -Password $OperatorPassword
Check "（夹具）平台运营会话就绪（角色已生效）" $op.roleSet "role=$($op.role)"
$sessOp = $op.session

# 买家会话（用于"没权限"的反向断言）
$buyerPhone = '138' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$sessBuyer = New-Object Microsoft.PowerShell.Commands.WebRequestSession
Call 'Post' "$Gateway/api/auth/register" $sessBuyer `
    @{ phone = $buyerPhone; password = $Password; nickname = '对账冒烟-买家' } | Out-Null

$bizDate = (Get-Date).ToUniversalTime().ToString('yyyy-MM-dd')

## ============================================================================
## 1. 规则清单：声明 vs 运行（不能只信源码）
## ============================================================================
Write-Host ""
Write-Host "> 规则清单（声明 vs 运行）"

$rulesResp = Call 'Get' "$Gateway/api/admin/recon/rules" $sessOp
Check "规则清单可读（运营）" ($rulesResp.status -eq 200) "status=$($rulesResp.status)"

$runtimeCodes = @(@($rulesResp.body.data) | ForEach-Object { "$($_.code)" })
$srcPath = Join-Path $PSScriptRoot '..\duoduo-trade\src\main\java\com\duoduo\trade\support\ReconRule.java'
$srcText = Get-Content -Raw -LiteralPath $srcPath
$declared = @([regex]::Matches($srcText, '(?m)^\s{4}[A-Z][A-Z0-9_]*\(ReconScope\.'))
$declaredCodes = @($declared | ForEach-Object { ($_.Value -replace '\(ReconScope\.', '').Trim() })

Check "源码声明的规则数与运行时一致（$($declaredCodes.Count) 条）" `
    ($declaredCodes.Count -eq $runtimeCodes.Count -and $runtimeCodes.Count -gt 0) `
    "声明=$($declaredCodes.Count) 运行=$($runtimeCodes.Count)"
$missingRule = @($declaredCodes | Where-Object { $runtimeCodes -notcontains $_ })
Check "源码里声明的每条规则在运行时都能查到（无「写了没上」）" `
    ($missingRule.Count -eq 0) "缺失：$($missingRule -join ',')"

Check "规则代号唯一（每条的 code 只出现一次）" `
    ((@($runtimeCodes | Sort-Object -Unique)).Count -eq $runtimeCodes.Count)
$noSeverity = @(@($rulesResp.body.data) | Where-Object { [string]::IsNullOrWhiteSpace("$($_.severity)") })
Check "每条规则都带严重度（缺失会让前端排序与统计失真）" ($noSeverity.Count -eq 0)

## ============================================================================
## 2. 判权：网关层（没权限一律 403）
## ============================================================================
Write-Host ""
Write-Host "> 判权（网关层）"

$r = Call 'Get' "$Gateway/api/admin/recon/runs" $sessBuyer
Check "买家查对账列表 → 403" ($r.status -eq 403) "status=$($r.status)"
$r = Call 'Get' "$Gateway/api/admin/recon/rules" $sessBuyer
Check "买家查规则清单 → 403" ($r.status -eq 403) "status=$($r.status)"
$r = Call 'Post' "$Gateway/api/admin/recon/runs" $sessBuyer @{ scope = 'MONEY' }
Check "买家触发对账 → 403" ($r.status -eq 403) "status=$($r.status)"
$r = Call 'Get' "$Gateway/api/admin/recon/runs" $null
Check "匿名查对账列表 → 401" ($r.status -eq 401) "status=$($r.status)"

# 直连服务端口（不经网关）：匿名调用必须被拒，而不是被放行或被判成 500
$r = Call 'Get' "$Trade/api/admin/recon/runs" $null
Check "直连交易端口匿名调用 → 401（服务端不接受无身份请求）" ($r.status -eq 401) "status=$($r.status)"

## ============================================================================
## 3. 干净基线：钱
## ============================================================================
Write-Host ""
Write-Host "> 干净基线（钱）"

$base = TriggerRun $sessOp 'MONEY' $bizDate
Check "手动触发钱对账 → 200" ($base.status -eq 200) "status=$($base.status)"
$baseRunId = "$($base.body.data.runId)"
$baseStatus = "$($base.body.data.status)"
$baseChecked = [int]$base.body.data.checkedCount

Check "★ 校验对象数 > 0（0 个对象必然「没有差异」，那是没看而不是没问题）" `
    ($baseChecked -gt 0) "checkedCount=$baseChecked"
Check "基线结论不是 FAILED（链路通、取数成功）" ($baseStatus -ne 'FAILED') "status=$baseStatus"
Check "基线差异条数与明细表实际条数一致（报表数字必须能被点开验证）" `
    ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_recon_diff WHERE run_id = $baseRunId;") -eq "$($base.body.data.diffCount)") `
    "接口=$($base.body.data.diffCount) 库=$(Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_recon_diff WHERE run_id = $baseRunId;")"

## ============================================================================
## 4. 造差异 A（钱）· 托管金额与订单实付不一致
## ============================================================================
Write-Host ""
Write-Host "> 造差异（钱 · 托管金额 ≠ 订单实付）"

# 取一笔"订单存在且托管单存在"的单子（不限定状态：金额比对与状态无关）
$rowA = Sql 'duoduo_trade' "SELECT s.order_id, s.amount FROM t_settlement s JOIN t_order o ON o.id = s.order_id ORDER BY s.order_id DESC LIMIT 1;"
$partsA = "$rowA".Split("`t")
$orderA = $partsA[0]
$amountA = $partsA[1]
Check "（夹具）取到一笔有托管单的订单" (-not [string]::IsNullOrWhiteSpace($orderA)) "row=$rowA"

if (-not [string]::IsNullOrWhiteSpace($orderA)) {
    Sql 'duoduo_trade' "UPDATE t_settlement SET amount = amount + 1 WHERE order_id = $orderA;" | Out-Null
    $nowA = Sql 'duoduo_trade' "SELECT amount FROM t_settlement WHERE order_id = $orderA;"
    Check "（夹具）托管金额已改（$amountA → $nowA）并回读确认" ("$nowA" -eq "$([long]$amountA + 1)") $nowA

    $runA = TriggerRun $sessOp 'MONEY' $bizDate
    $runAId = "$($runA.body.data.runId)"
    $hitA = DiffCount $sessOp $runAId 'MONEY_AMOUNT_MISMATCH' $orderA
    Check "★ 抓到了 MONEY_AMOUNT_MISMATCH 且定位到订单 $orderA" ($hitA -eq 1) "命中 $hitA 条"

    # ★ 复原后必须消失 —— 否则这条规则测的其实是别的东西
    Sql 'duoduo_trade' "UPDATE t_settlement SET amount = $amountA WHERE order_id = $orderA;" | Out-Null
    Check "（复原）托管金额已还原为 $amountA" `
        ((Sql 'duoduo_trade' "SELECT amount FROM t_settlement WHERE order_id = $orderA;") -eq "$amountA")

    $runA2 = TriggerRun $sessOp 'MONEY' $bizDate
    $runA2Id = "$($runA2.body.data.runId)"
    $goneA = DiffCount $sessOp $runA2Id 'MONEY_AMOUNT_MISMATCH' $orderA
    Check "★ 复原后同一条差异消失（证明规则测的就是这个字段）" ($goneA -eq 0) "仍命中 $goneA 条"
}

## ============================================================================
## 5. 造差异 B（钱）· 已放款却没有放款时间
## ============================================================================
Write-Host ""
Write-Host "> 造差异（钱 · 放款状态缺放款时间）"

$rowB = Sql 'duoduo_trade' "SELECT order_id, released_at FROM t_settlement WHERE status = 'RELEASED' AND released_at IS NOT NULL ORDER BY order_id DESC LIMIT 1;"
$partsB = "$rowB".Split("`t")
$orderB = $partsB[0]
$releasedB = $partsB[1]
Check "（夹具）取到一笔已放款且有放款时间的托管单" (-not [string]::IsNullOrWhiteSpace($orderB)) "row=$rowB"

if (-not [string]::IsNullOrWhiteSpace($orderB)) {
    Sql 'duoduo_trade' "UPDATE t_settlement SET released_at = NULL WHERE order_id = $orderB;" | Out-Null
    Check "（夹具）放款时间已清空并回读确认" `
        ((Sql 'duoduo_trade' "SELECT IFNULL(released_at,'NULL') FROM t_settlement WHERE order_id = $orderB;") -eq 'NULL')

    $runB = TriggerRun $sessOp 'MONEY' $bizDate
    $runBId = "$($runB.body.data.runId)"
    $hitB = DiffCount $sessOp $runBId 'MONEY_RELEASE_TIME_MISSING' $orderB
    Check "★ 抓到了 MONEY_RELEASE_TIME_MISSING 且定位到订单 $orderB" ($hitB -eq 1) "命中 $hitB 条"

    Sql 'duoduo_trade' "UPDATE t_settlement SET released_at = '$releasedB' WHERE order_id = $orderB;" | Out-Null
    Check "（复原）放款时间已还原" `
        ((Sql 'duoduo_trade' "SELECT IFNULL(released_at,'NULL') FROM t_settlement WHERE order_id = $orderB;") -eq "$releasedB")

    $runB2 = TriggerRun $sessOp 'MONEY' $bizDate
    $goneB = DiffCount $sessOp "$($runB2.body.data.runId)" 'MONEY_RELEASE_TIME_MISSING' $orderB
    Check "★ 复原后同一条差异消失" ($goneB -eq 0) "仍命中 $goneB 条"
}

## ============================================================================
## 6. 造差异 C（库存）· 跨域冻结量与商品域不一致 + 库存为负向异常
## ============================================================================
Write-Host ""
Write-Host "> 造差异（库存 · 商品域 frozen_stock 与交易域台账不一致）"

$rowC = Sql 'duoduo_product' "SELECT id, stock, COALESCE(frozen_stock,0) FROM t_sku WHERE status='ACTIVE' AND stock >= 10 ORDER BY id LIMIT 1;"
$partsC = "$rowC".Split("`t")
$skuC = $partsC[0]
$stockC = $partsC[1]
$frozenC = $partsC[2]
Check "（夹具）取到一只在售且有货的 SKU" (-not [string]::IsNullOrWhiteSpace($skuC)) "row=$rowC"

if (-not [string]::IsNullOrWhiteSpace($skuC)) {
    $badC = [int]$stockC + 5
    Sql 'duoduo_product' "UPDATE t_sku SET frozen_stock = $badC WHERE id = $skuC;" | Out-Null
    Check "（夹具）商品域 frozen_stock 已改成 $badC（> 总库存 $stockC）并回读确认" `
        ((Sql 'duoduo_product' "SELECT frozen_stock FROM t_sku WHERE id = $skuC;") -eq "$badC")

    $runC = TriggerRun $sessOp 'STOCK' $bizDate
    $runCId = "$($runC.body.data.runId)"
    Check "库存对账校验对象数 > 0" ([int]$runC.body.data.checkedCount -gt 0) "checked=$($runC.body.data.checkedCount)"
    $hitC1 = DiffCount $sessOp $runCId 'STOCK_CROSS_DOMAIN_DRIFT' $skuC
    $hitC2 = DiffCount $sessOp $runCId 'STOCK_NEGATIVE' $skuC
    Check "★ 抓到跨域冻结不一致且定位到 SKU $skuC" ($hitC1 -eq 1) "命中 $hitC1 条"
    Check "★ 抓到「冻结大于总库存」（可售库存那个 max(0,…) 会把它夹成 0，前台看不出来）" `
        ($hitC2 -eq 1) "命中 $hitC2 条"

    Sql 'duoduo_product' "UPDATE t_sku SET frozen_stock = $frozenC WHERE id = $skuC;" | Out-Null
    Check "（复原）商品域 frozen_stock 已还原为 $frozenC" `
        ((Sql 'duoduo_product' "SELECT frozen_stock FROM t_sku WHERE id = $skuC;") -eq "$frozenC")

    $runC2 = TriggerRun $sessOp 'STOCK' $bizDate
    $runC2Id = "$($runC2.body.data.runId)"
    Check "★ 复原后跨域差异消失" ((DiffCount $sessOp $runC2Id 'STOCK_CROSS_DOMAIN_DRIFT' $skuC) -eq 0)
    Check "★ 复原后库存异常消失" ((DiffCount $sessOp $runC2Id 'STOCK_NEGATIVE' $skuC) -eq 0)
}

## ============================================================================
## 7. 造差异 D（库存）· 冻结台账与订单状态不相容 + 超期未释放
## ============================================================================
Write-Host ""
Write-Host "> 造差异（库存 · 僵尸冻结：订单已终结，冻结还挂着）"

# 找一笔已终结的订单 + 它的一条明细（数量刻意与明细一致，好让本节的命中只来自"泄漏"与"过期"两条规则）
$rowD = Sql 'duoduo_trade' "SELECT o.id, i.sku_id, i.quantity FROM t_order o JOIN t_order_item i ON i.order_id = o.id WHERE o.status IN ('COMPLETED','CLOSED','REFUNDED') ORDER BY o.id DESC LIMIT 1;"
$partsD = "$rowD".Split("`t")
$orderD = $partsD[0]
$skuD = $partsD[1]
$qtyD = $partsD[2]
Check "（夹具）取到一笔已终结订单及其明细" (-not [string]::IsNullOrWhiteSpace($orderD)) "row=$rowD"

$zombieId = $null
if (-not [string]::IsNullOrWhiteSpace($orderD)) {
    $exists = Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_stock_freeze WHERE order_id = $orderD AND sku_id = $skuD;"
    if ("$exists" -ne '0') {
        Check "（夹具）该 (订单,SKU) 上已有冻结行，跳过本节插入以免撞唯一键" $true
    } else {
        $zombieId = '95' + (Get-Random -Minimum 1000000 -Maximum 9999999)
        Sql 'duoduo_trade' "INSERT INTO t_stock_freeze (id, sku_id, order_id, quantity, status, expire_at, created_at, updated_at) VALUES ($zombieId, $skuD, $orderD, $qtyD, 'FROZEN', UTC_TIMESTAMP() - INTERVAL 48 HOUR, UTC_TIMESTAMP(), UTC_TIMESTAMP());" | Out-Null
        Check "（夹具）已现场插入一条僵尸冻结并回读确认" `
            ((Sql 'duoduo_trade' "SELECT CONCAT(status,'/',quantity) FROM t_stock_freeze WHERE id = $zombieId;") -eq "FROZEN/$qtyD") `
            (Sql 'duoduo_trade' "SELECT CONCAT(status,'/',quantity) FROM t_stock_freeze WHERE id = $zombieId;")

        $runD = TriggerRun $sessOp 'STOCK' $bizDate
        $runDId = "$($runD.body.data.runId)"
        $hitD1 = DiffCount $sessOp $runDId 'STOCK_FREEZE_LEAK' $orderD
        $hitD2 = DiffCount $sessOp $runDId 'STOCK_FREEZE_STALE' $orderD
        Check "★ 抓到「冻结与订单状态不相容」且定位到订单 $orderD" ($hitD1 -eq 1) "命中 $hitD1 条"
        Check "★ 抓到「超期未释放」（补偿调度是否活着的探针）" ($hitD2 -eq 1) "命中 $hitD2 条"

        Sql 'duoduo_trade' "DELETE FROM t_stock_freeze WHERE id = $zombieId;" | Out-Null
        Check "（复原）僵尸冻结已删除" `
            ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_stock_freeze WHERE id = $zombieId;") -eq '0')

        $runD2 = TriggerRun $sessOp 'STOCK' $bizDate
        $runD2Id = "$($runD2.body.data.runId)"
        Check "★ 复原后两条差异都消失" `
            ((DiffCount $sessOp $runD2Id 'STOCK_FREEZE_LEAK' $orderD) -eq 0 -and
             (DiffCount $sessOp $runD2Id 'STOCK_FREEZE_STALE' $orderD) -eq 0)
    }
}

## ============================================================================
## 8. 同日重跑覆盖，不累积
## ============================================================================
Write-Host ""
Write-Host "> 同日重跑（覆盖而非累积）"

$dup = Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_recon_run WHERE biz_date = '$bizDate' AND scope = 'MONEY';"
Check "同一天同一范围只有一轮（重跑覆盖）" ("$dup" -eq '1') "实际 $dup 轮"

$orphanDiff = Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_recon_diff d LEFT JOIN t_recon_run r ON r.id = d.run_id WHERE r.id IS NULL;"
Check "差异明细无孤儿（删轮次时明细一并删净，否则表会越跑越大且数字虚高）" ("$orphanDiff" -eq '0') "孤儿 $orphanDiff 条"

## ============================================================================
## 9. 入参与边界
## ============================================================================
Write-Host ""
Write-Host "> 入参与边界"

$r = Call 'Post' "$Gateway/api/admin/recon/runs" $sessOp @{ scope = 'NOT_A_SCOPE' }
Check "非法范围 → 400（不是 500，也不是静默按默认值跑）" ($r.status -eq 400) "status=$($r.status)"
$r = Call 'Post' "$Gateway/api/admin/recon/runs" $sessOp @{ scope = 'MONEY'; bizDate = '2026/10/11' }
Check "非法业务日格式 → 400" ($r.status -eq 400) "status=$($r.status)"
$r = Call 'Get' "$Gateway/api/admin/recon/runs/1/diffs" $sessOp
Check "不存在的轮次查明细 → 404（返回空列表会让「没有差异」与「轮次不存在」混在一起）" `
    ($r.status -eq 404) "status=$($r.status)"
$r = Call 'Post' "$Gateway/api/admin/recon/runs" $sessOp @{ scope = 'MONEY' }
Check "不传业务日 = 今天（默认值可用）" ($r.status -eq 200) "status=$($r.status)"

## ============================================================================
## 10. 手动触发留痕，且动作代号被收件方正确识别
## ============================================================================
Write-Host ""
Write-Host "> 留痕（E23 教训：动作代号落在收件方翻译）"

$auditRow = Sql 'duoduo_user' "SELECT COUNT(*) FROM t_audit_log WHERE action = 'RECON_RUN_MANUAL' AND created_at >= UTC_TIMESTAMP() - INTERVAL 20 MINUTE;"
Check "★ 手动触发已留痕（action = RECON_RUN_MANUAL）" ("$auditRow" -ne '0' -and "$auditRow" -ne $null) "命中 $auditRow 条"
$unknownRow = Sql 'duoduo_user' "SELECT COUNT(*) FROM t_audit_log WHERE action = 'UNKNOWN' AND created_at >= UTC_TIMESTAMP() - INTERVAL 20 MINUTE;"
Check "★ 近 20 分钟没有 UNKNOWN 动作（收件方认识所有新动作代号 —— 只重建发件方会让它变成 UNKNOWN）" `
    ("$unknownRow" -eq '0') "UNKNOWN $unknownRow 条"

## ============================================================================
## 11. FAILED 与 CLEAN 必须分得开（停掉商品服务真跑一次）
## ============================================================================
if (-not $SkipFailPathCheck) {
    Write-Host ""
    Write-Host "> 「没跑成」不许记成「全绿」（停商品服务真跑一次）"

    $stopped = $false
    try {
        & docker stop duoduo-app-product 2>&1 | Out-Null
        $stopped = $true
        Start-Sleep -Seconds 2

        $runFail = TriggerRun $sessOp 'STOCK' $bizDate
        $failStatus = "$($runFail.body.data.status)"
        Check "★ 商品服务不可用时，库存对账结论是 FAILED（不是 CLEAN / 不是 DIRTY）" `
            ($failStatus -eq 'FAILED') "status=$failStatus"
        Check "★ FAILED 轮的差异条数为 0，但状态不是 CLEAN（0 条差异 ≠ 干净）" `
            ([int]$runFail.body.data.diffCount -eq 0 -and $failStatus -ne 'CLEAN') `
            "diff=$($runFail.body.data.diffCount) status=$failStatus"
        $failMessage = "$($runFail.body.data.message)"
        Check "FAILED 轮带失败原因（运维要知道是「没跑成」而不是「没问题」）" `
            (-not [string]::IsNullOrWhiteSpace($failMessage)) "message=$failMessage"

        # 钱侧不受影响：两个范围独立成败，「钱是干净的、库存没跑成」是一个能说清楚的结论
        $runMoneyOk = TriggerRun $sessOp 'MONEY' $bizDate
        Check "★ 商品服务挂了不影响钱对账（两个范围独立成败）" `
            ("$($runMoneyOk.body.data.status)" -ne 'FAILED') "status=$($runMoneyOk.body.data.status)"
    } finally {
        if ($stopped) {
            & docker start duoduo-app-product 2>&1 | Out-Null
            $ok = WaitHealthy 'duoduo-app-product'
            Write-Host "  商品服务已重新启动（healthy=$ok）"
        }
    }

    # 恢复后必须能重新跑成：否则"FAILED"会变成常态而被忽略
    $warm = Call 'Get' "$Gateway/api/products/1001" $null
    Start-Sleep -Seconds 2
    $runRecover = TriggerRun $sessOp 'STOCK' $bizDate
    Check "★ 商品服务恢复后库存对账不再是 FAILED（证明刚才那次失败是依赖故障、不是永久坏掉）" `
        ("$($runRecover.body.data.status)" -ne 'FAILED') `
        "status=$($runRecover.body.data.status) checked=$($runRecover.body.data.checkedCount)"
} else {
    Write-Host ""
    Write-Host "> 已按 -SkipFailPathCheck 跳过「没跑成不许记成全绿」实测（CI / 全套回归不该跳）"
}

## ============================================================================
## 12. 收尾：库内数据自证已复原
## ============================================================================
Write-Host ""
Write-Host "> 收尾（自证复原）"

if (-not [string]::IsNullOrWhiteSpace($orderA)) {
    Check "（复原）订单 $orderA 的托管金额 = 原值 $amountA" `
        ((Sql 'duoduo_trade' "SELECT amount FROM t_settlement WHERE order_id = $orderA;") -eq "$amountA")
}
if ($null -ne $zombieId) {
    Check "（复原）本脚本插入的冻结行已清空" `
        ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_stock_freeze WHERE id = $zombieId;") -eq '0')
}
$leftover = Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_stock_freeze WHERE id >= 9500000000000000000;"
Check "（复原）交易库无 95 段夹具残留" ("$leftover" -eq '0') "残留 $leftover 条"

$roleBack = Remove-PlatformOperatorPrivilege -Phone $op.phone
Check "（收尾）临时提权的运营已降回 USER（不留永久后台钥匙）" ("$roleBack" -eq 'USER') $roleBack

## ============================================================================
## 汇总
## ============================================================================
Write-Host ""
Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
if ($fail -gt 0) {
    Write-Host "失败项："
    foreach ($f in $failures) { Write-Host "  - $f" }
    exit 1
}
exit 0
