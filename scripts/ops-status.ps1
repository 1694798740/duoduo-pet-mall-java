# ops-status.ps1 —— 一条命令看全栈状态（运维日常第一屏）
#
# 为什么要有它：出问题时"哪里坏了"通常要靠翻十几个地方 ——
#   容器起没起、三个站点能不能打开、网关到后端通不通、库和 Redis 活着没、
#   开关是什么值、最近一次备份是什么时候。这个脚本把这些合成**一屏**，
#   并且**用一个退出码回答"整体是否正常"**（可以直接放进巡检计划任务）。
#
# ★ 判据纪律（本项目的既有教训，逐条体现在下面）：
#   · 容器清单**从 compose 解析**，不写死在脚本里 —— 写死就会在加服务后静默漏检；
#   · 站点探针**只看 HTTP 状态码**（不看响应体文本）：业务异常被全局处理器吃掉后，
#     正文可能仍像正常，状态码才是结论；
#   · 直连 8081/8082 的探测**刻意不做**：本机上 WorkBuddy.exe 占用了这两个端口，
#     探到的是别的进程（会给出虚假的"健康"），所以应用层一律**经由网关**验证；
#   · 失败与"没数据"分开：没有备份时输出"尚未配置"，不算失败但要显式可见。
#
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ops-status.ps1
#   ... -SkipSites      只查容器与开关（站点探针跳过，用于 DDL 期间等场景）
param(
    [switch]$SkipSites,
    [switch]$Json,
    [int]$TimeoutSec = 6
)

$ErrorActionPreference = 'Continue'
# Invoke-WebRequest 的进度条会往控制台刷"正在读取 Web 响应"，
# 把一屏状态冲成噪声（且 `*> 日志` 落盘时会把进度条也写进去）—— 一律关掉。
$ProgressPreference = 'SilentlyContinue'
$repo = Split-Path -Parent $PSScriptRoot
$composePath = Join-Path $repo 'docker-compose.yml'

$rows = New-Object System.Collections.ArrayList   # 结果行：Scope / Item / Ok / Detail
$hardFail = 0

function Add-Row([string]$scope, [string]$item, [bool]$ok, [string]$detail, [bool]$soft = $false) {
    $state = if ($ok) { 'OK' } elseif ($soft) { 'WARN' } else { 'FAIL' }
    [void]$rows.Add([pscustomobject]@{ Scope = $scope; Item = $item; State = $state; Detail = $detail })
    if (-not $ok -and -not $soft) { $script:hardFail++ }
}

# ---------------------------------------------------------------------------
# 1. 期望的容器清单（从 compose 解析；profile 服务标记为可选）
# ---------------------------------------------------------------------------
function Read-ComposeContainers([string]$path) {
    $lines = @(Get-Content -Path $path -Encoding UTF8)
    $list = New-Object System.Collections.ArrayList
    $cur = ''
    $optional = New-Object System.Collections.Generic.HashSet[string]
    $names = @{}
    foreach ($line in $lines) {
        if ($line -match '^  ([A-Za-z0-9][A-Za-z0-9_-]*):\s*$') { $cur = $Matches[1]; continue }
        if ($line -match '^\s+profiles:\s*\[') { if ($cur) { [void]$optional.Add($cur) } ; continue }
        if ($line -match '^\s+container_name:\s*(\S+)\s*$') { if ($cur) { $names[$cur] = $Matches[1] } }
    }
    foreach ($k in $names.Keys) {
        [void]$list.Add([pscustomobject]@{ Service = $k; Container = $names[$k]; Optional = $optional.Contains($k) })
    }
    return $list
}

$expected = Read-ComposeContainers $composePath
$expected = @($expected | Sort-Object Container)
if ($expected.Count -eq 0) {
    Write-Host '  [FAIL] 未能从 compose 解析出任何容器 —— 解析失效，脚本自身失效' -ForegroundColor Red
    exit 1
}

$running = New-Object System.Collections.Generic.HashSet[string]
$statusMap = @{}
try {
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'SilentlyContinue'
    foreach ($l in @(& docker ps --format '{{.Names}}|{{.Status}}' 2>$null)) {
        if ($l -match '^([^|]+)\|(.*)$') {
            [void]$running.Add($Matches[1].Trim())
            $statusMap[$Matches[1].Trim()] = $Matches[2].Trim()
        }
    }
    $ErrorActionPreference = $prev
} catch { }

$dockerUp = $running.Count -gt 0
Add-Row '环境' 'Docker 引擎' $dockerUp $(if ($dockerUp) { "运行中容器 $($running.Count) 个" } else { '引擎未响应或未启动' })

foreach ($e in $expected) {
    if ($running.Contains($e.Container)) {
        Add-Row '容器' "$($e.Service) ($($e.Container))" $true $statusMap[$e.Container]
    } elseif ($e.Optional) {
        Add-Row '容器' "$($e.Service) ($($e.Container))" $false '未启动（属可选 profile，未启用时不影响功能）' $true
    } else {
        Add-Row '容器' "$($e.Service) ($($e.Container))" $false '★ 未运行'
    }
}

# ---------------------------------------------------------------------------
# 2. 站点与链路探针（只看状态码；应用层一律经网关）
# ---------------------------------------------------------------------------
if (-not $SkipSites -and $dockerUp) {
    # 网关 → 商品域：走一条**公开只读**接口，验证「网关 + 服务发现 + 商品域 + 库」整条链
    $probes = @(
        @{ Name = '网关 → 商品域（列表）'; Url = 'http://localhost:8080/api/products?page=1&pageSize=1'; Want = 200 }
        @{ Name = '网关 → 类目（公开）'; Url = 'http://localhost:8080/api/categories'; Want = 200 }
        @{ Name = '商城站点 8088'; Url = 'http://localhost:8088/'; Want = 200 }
        @{ Name = '平台后台站点 8090'; Url = 'http://localhost:8090/'; Want = 200 }
        @{ Name = '商家平台站点 8091'; Url = 'http://localhost:8091/'; Want = 200 }
    )
    foreach ($p in $probes) {
        $code = 0; $err = ''
        try {
            $r = Invoke-WebRequest -Uri $p.Url -UseBasicParsing -TimeoutSec $TimeoutSec -MaximumRedirection 3
            $code = [int]$r.StatusCode
        } catch {
            $resp = $_.Exception.Response
            if ($resp -and $resp.StatusCode) { $code = [int]$resp.StatusCode } else { $err = $_.Exception.Message }
        }
        if ($code -eq $p.Want) {
            Add-Row '链路' $p.Name $true "HTTP $code"
        } else {
            Add-Row '链路' $p.Name $false $(if ($code -gt 0) { "HTTP $code（期望 $($p.Want)）" } else { "不可达：$err" })
        }
    }
} elseif ($SkipSites) {
    Add-Row '链路' '站点探针' $true '本次以 -SkipSites 跳过' $true
} else {
    Add-Row '链路' '站点探针' $false '引擎未运行，跳过' $true
}

# ---------------------------------------------------------------------------
# 3. 数据与中间件（在容器内探测，避开宿主端口占用）
# ---------------------------------------------------------------------------
if ($dockerUp) {
    if ($running.Contains('duoduo-mysql')) {
        $prev = $ErrorActionPreference; $ErrorActionPreference = 'SilentlyContinue'
        $out = & docker exec duoduo-mysql mysql -uduoduo -pduoduo -N -e 'SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME LIKE ''duoduo_%''' 2>$null
        $code = $LASTEXITCODE
        $ErrorActionPreference = $prev
        if ($code -eq 0 -and "$out".Trim() -match '^\d+$') {
            Add-Row '数据' 'MySQL 六库' $true "库数 $("$out".Trim())"
        } else {
            Add-Row '数据' 'MySQL 六库' $false '查询失败（凭据或服务异常）'
        }
    }
    if ($running.Contains('duoduo-redis')) {
        $prev = $ErrorActionPreference; $ErrorActionPreference = 'SilentlyContinue'
        $pong = & docker exec duoduo-redis redis-cli ping 2>$null
        $code = $LASTEXITCODE
        $ErrorActionPreference = $prev
        $okPing = ($code -eq 0) -and ("$pong".Trim() -eq 'PONG')
        Add-Row '数据' 'Redis' $okPing $(if ($okPing) { 'PONG' } else { '无响应' })
    }
}

# ---------------------------------------------------------------------------
# 4. 开关（复用 ops-switch 的单一权威清单与校验）
# ---------------------------------------------------------------------------
$switchScript = Join-Path $PSScriptRoot 'ops-switch.ps1'
if (Test-Path $switchScript) {
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'SilentlyContinue'
    $swOut = & powershell -NoProfile -ExecutionPolicy Bypass -File $switchScript -Check 2>&1 | Out-String
    $swCode = $LASTEXITCODE
    $ErrorActionPreference = $prev
    $swFails = ([regex]::Matches($swOut, '\[FAIL\]')).Count
    Add-Row '开关' '可切换性自检（清单 / compose / yml）' ($swCode -eq 0) `
        $(if ($swCode -eq 0) { '全部开关都能从 .env 覆盖' } else { "有 $swFails 项不可达，详见 scripts\ops-switch.ps1 -Check" })
} else {
    Add-Row '开关' 'ops-switch.ps1' $false '脚本缺失'
}

# ---------------------------------------------------------------------------
# 5. 最近一次数据库备份（没有 = 显式提示，不静默）
# ---------------------------------------------------------------------------
$backupRoot = $env:DUODUO_BACKUP_ROOT
if (-not $backupRoot) {
    $bk = Join-Path $PSScriptRoot 'db-backup.ps1'
    if (Test-Path $bk) {
        $m = Select-String -Path $bk -Pattern "^\`$DefaultBackupRoot\s*=\s*'([^']+)'" | Select-Object -First 1
        if ($m) { $backupRoot = $m.Matches[0].Groups[1].Value }
    }
}
if ($backupRoot -and (Test-Path $backupRoot)) {
    $runs = Join-Path $backupRoot 'runs'
    if (Test-Path $runs) {
        $latest = Get-ChildItem -Path $runs -Directory | Sort-Object Name -Descending | Select-Object -First 1
        if ($latest) {
            $ageH = [math]::Round(((Get-Date) - $latest.LastWriteTime).TotalHours, 1)
            Add-Row '备份' '最近一次备份' $true "$($latest.Name)（$ageH 小时前）" 
        } else {
            Add-Row '备份' '最近一次备份' $false '备份目录存在但里面没有任何一份' 
        }
    } else {
        Add-Row '备份' '最近一次备份' $false "备份根 '$backupRoot' 里没有 runs 目录（脚本还没跑过）"
    }
} else {
    Add-Row '备份' '最近一次备份' $false `
        "尚未配置：备份根 '$backupRoot' 不存在。见 docs\OPS.md『备份恢复』—— 加件待你拍板（落在哪个盘）" $true
}

# ---------------------------------------------------------------------------
# 输出
# ---------------------------------------------------------------------------
$okN = @($rows | Where-Object { $_.State -eq 'OK' }).Count
$warnN = @($rows | Where-Object { $_.State -eq 'WARN' }).Count
$failN = @($rows | Where-Object { $_.State -eq 'FAIL' }).Count

if ($Json) {
    $rows | ConvertTo-Json -Depth 3
} else {
    Write-Host ''
    Write-Host '====================================================================' -ForegroundColor Cyan
    Write-Host ' 多多宠物商店 · 全栈状态' -ForegroundColor Cyan
    Write-Host '====================================================================' -ForegroundColor Cyan
    Write-Host ("  {0,-6} {1,-44} {2}" -f '范围', '项目', '结果') -ForegroundColor DarkGray
    foreach ($r in $rows) {
        $color = switch ($r.State) { 'OK' { 'Green' } 'WARN' { 'Yellow' } default { 'Red' } }
        Write-Host ("  {0,-6} {1,-44} {2}  {3}" -f $r.Scope, $r.Item, $r.State, $r.Detail) -ForegroundColor $color
    }
    Write-Host ''
    Write-Host ("  合计：OK $okN ／ 需留意 $warnN ／ 失败 $failN") -ForegroundColor $(if ($failN -eq 0) { 'Green' } else { 'Red' })
    Write-Host '  退出码：0 = 全部必需项正常；1 = 有失败项（可直接用于巡检计划任务）' -ForegroundColor DarkGray
    Write-Host ''
}

if ($hardFail -gt 0) { exit 1 }
exit 0
