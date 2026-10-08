# 前端真实浏览器 e2e 一键运行器
#
# 职责：把「起服务 → 跑 e2e → 收尾」三步合成一条命令。
# 之前这一步全靠人手（另开终端跑 vite、记住夹具顺序、跑完忘关后台进程），
# 换台机器就跑不起来 —— 而「跑不起来的测试」等于没有测试。
#
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\e2e\run-ui-checks.ps1
#   powershell ... -File scripts\e2e\run-ui-checks.ps1 -Only home              # 只跑自包含那一套
#   powershell ... -File scripts\e2e\run-ui-checks.ps1 -KeepServer            # 跑完保留 dev server
param(
    [string]$Only = '',          # 只跑某一套（文件名去掉 -ui-check，如 home / coupon）
    [switch]$KeepServer,
    # S2 新增：直接指定被测入口（如正式版容器 http://localhost:8088）。
    # 给了它就**不再启动/管理 dev server**，并把 UI_BASE 传给每套脚本 ——
    # 这样「一键自检」打的就是用户实际在用的入口（真应用），而不是开发态。
    [string]$UiBase = ''
)

$ErrorActionPreference = 'Stop'
# 输出编码必须先设为 UTF-8：脚本与 node 输出都是 UTF-8，
# 而 PowerShell 5.1 默认按控制台代码页解码，中文会变成乱码，
# 表现是「单套脚本实际全过，但汇总行因为匹配不到中文而显示 NO-RESULT」。
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$frontend = Join-Path $repo 'frontend'
$uiPort = 5173

function Test-Port([int]$port) {
    $c = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    return $null -ne $c
}

# 1) 保证被测入口可用
$serverStarted = $false
if ($UiBase) {
    try {
        $r = Invoke-WebRequest -Uri "$UiBase/" -UseBasicParsing -TimeoutSec 10
        if ($r.StatusCode -ne 200) { throw "HTTP $($r.StatusCode)" }
        $env:UI_BASE = $UiBase   # 套件读 process.env.UI_BASE，子进程继承
        Write-Host "被测入口：$UiBase（复用；不启动 dev server）"
    } catch {
        Write-Host "被测入口不可达：$UiBase —— $($_.Exception.Message)"
        Write-Host "提示：先起网页容器（bash scripts/web-up.sh）或不带 -UiBase 改用 dev server"
        exit 2
    }
} elseif (Test-Port $uiPort) {
    Write-Host "dev server 已在 :$uiPort 监听，复用"
} else {
    Write-Host "启动 frontend dev server（:$uiPort）..."
    $p = Start-Process -FilePath 'cmd.exe' -ArgumentList "/c cd /d $frontend && npm run dev -- --port $uiPort" `
        -PassThru -WindowStyle Minimized
    $serverStarted = $true
    $deadline = (Get-Date).AddSeconds(90)
    while (-not (Test-Port $uiPort)) {
        Start-Sleep -Seconds 2
        if ((Get-Date) -gt $deadline) { Write-Host "dev server 启动超时"; exit 2 }
    }
    Write-Host "dev server 就绪（pid=$($p.Id)）"
}

# 2) 选脚本
$order = @(if ($Only) { $Only } else { 'home' })
# 自包含的先跑（它不需要夹具），其余按名字排（夹具各自在注释里写明前置）
if (-not $Only) {
    # 必须整体加 @( )：管道运算符 `|` 的优先级高于 `+=`，
    # 不括起来会解析成 `($order += Get-ChildItem ...) | ForEach-Object ...`，
    # 结果被丢弃，$order 里只剩原始文件名，最后拼出「homeaddress...-ui-check.mjs」这种不存在的文件。
    $order += @(
        Get-ChildItem (Join-Path $PSScriptRoot '*-ui-check.mjs') |
            ForEach-Object { $_.Name -replace '-ui-check\.mjs$', '' } |
            Where-Object { $_ -ne 'home' } | Sort-Object
    )
}

$results = @()
foreach ($name in $order) {
    $file = Join-Path $PSScriptRoot "$name-ui-check.mjs"
    if (-not (Test-Path $file)) { Write-Host "跳过（不存在）：$file"; continue }
    Write-Host "`n===== $name ====="

    # 券这套依赖一个库里的券模板（coupon-ui-fixture.ps1 建）。
    # 不在这里建/拆，一键跑就必然卡在「未拿到夹具模板 id」，而提示还只在脚本中间才打印一次。
    if ($name -eq 'coupon') {
        $ErrorActionPreference = 'Continue'
        & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'coupon-ui-fixture.ps1') -Action create 2>&1 | Out-Host
        $fixtureCode = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        if ($fixtureCode -ne 0) {
            $results += "$name : FAIL（夹具建立失败 exit=$fixtureCode）"
            continue
        }
    }

    # 跑 node 时必须临时放开 ErrorActionPreference：
    # PS 5.1 里「本机命令往 stderr 写了东西」+ 'Stop' 会被当成终止错误，
    # 于是第一套脚本刚开始跑，整个循环就中断了（后面几套根本没执行，也没有汇总）。
    $ErrorActionPreference = 'Continue'
    & node $file 2>&1 | Out-Host
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    # 判定用**退出码**，不解析中文输出：各脚本失败时统一 exit(1)，
    # 靠正则去匹配「通过 N 项，失败 M 项」会在编码一乱时静默变成 NO-RESULT。
    $results += "$name : " + $(if ($code -eq 0) { 'PASS' } else { "FAIL (exit=$code)" })

    # 券模板用完就拆：留着会污染领券中心的展示口径（下一轮看到的不是干净基线）
    if ($name -eq 'coupon') {
        $ErrorActionPreference = 'Continue'
        & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'coupon-ui-fixture.ps1') -Action drop 2>&1 | Out-Host
        $ErrorActionPreference = 'Stop'
    }
}

# 3) 收尾
if ($serverStarted -and -not $KeepServer) {
    Get-NetTCPConnection -LocalPort $uiPort -State Listen -ErrorAction SilentlyContinue |
        ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }
    Write-Host "`n已关闭本次启动的 dev server"
}

Write-Host "`n===== 汇总 ====="
$results | ForEach-Object { Write-Host "  $_" }

# ★ 退出码必须反映失败：此前这里没有 exit，脚本恒以 0 结束 ——
#   而上层 `check-all.ps1` 只看子进程退出码，于是"套件红了、门禁却报通过"（假通过）。
#   判据用**退出码/结果串**，不解析中文输出（编码一乱就会静默变 NO-RESULT）。
$failed = @($results | Where-Object { $_ -match 'FAIL' })
if ($failed.Count -gt 0) {
    Write-Host ""
    Write-Host "存在失败套件 $($failed.Count) 个：" -ForegroundColor Red
    $failed | ForEach-Object { Write-Host "  $_" }
    exit 1
}
exit 0
