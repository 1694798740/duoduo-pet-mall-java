<#
.SYNOPSIS
    一条命令跑完全部自检（S2）：前端构建 → 后端单测 → 接口冒烟 → 浏览器 e2e → 汇总。

.DESCRIPTION
    为什么需要它：此前的自检分散在 4 类脚本里（23 套接口冒烟、17 套浏览器测试 654 项、
    单元测试、前端构建），每类各有入口、各有前置 —— 每次交付都要靠记忆拼顺序，
    券的测试还要先建夹具。**容易漏跑；漏跑的检查等于没检查**（S2 立项的原因）。

    本脚本只做「串联与汇总」，不重复实现任何检查：
      [1/5] 前端构建        `npm run build`（= tsc 类型检查 + vite 打包）
      [2/5] 后端单元测试     `mvn test`（228 项；最耗时，可 -SkipBackendTests）
      [3/5] 接口冒烟         `run-all-smoke.ps1`（23 套）
      [4/5] 浏览器 e2e       `run-ui-checks.ps1 -UiBase <入口>`（17 套 654 项；券夹具自动建/撤）
      [5/5] 汇总表           每段 PASS/FAIL + 总耗时；**全绿才 exit 0**

    e2e 默认打**正式版容器**（http://localhost:8088，S1 的产物）——
    测的就是用户实际在用的入口，且**不需要启动 dev server**。
    想测开发态：-UiBase 'http://localhost:5173'（会自动起/停 dev server）。

.PARAMETER SkipFrontendBuild  跳过前端构建（改过后端没动前端时省 30 秒）
.PARAMETER SkipBackendTests   跳过 mvn test（最耗时的一段）
.PARAMETER SkipSmoke          跳过 23 套接口冒烟
.PARAMETER SkipE2E            跳过浏览器 e2e
.PARAMETER UiBase             e2e 被测入口，默认 http://localhost:8088

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\check-all.ps1
.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\check-all.ps1 -SkipBackendTests
#>
[CmdletBinding()]
param(
    [switch]$SkipFrontendBuild,
    [switch]$SkipBackendTests,
    [switch]$SkipSmoke,
    [switch]$SkipE2E,
    [string]$UiBase = 'http://localhost:8088'
)

$ErrorActionPreference = 'Continue'
# 输出必须 UTF-8：脚本与子进程输出都是 UTF-8，PS 5.1 默认按控制台代码页解码会乱码，
# 乱码会让汇总匹配失灵（同款坑见 run-ui-checks.ps1 头注）
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$results = @()
$anyFail = $false

function Record($name, $ok, $detail) {
    if ($ok) { Write-Host ("  [PASS] {0}  {1}" -f $name, $detail) -ForegroundColor Green }
    else     { Write-Host ("  [FAIL] {0}  {1}" -f $name, $detail) -ForegroundColor Red }
    $script:results += @{ name = $name; ok = $ok; detail = $detail }
    if (-not $ok) { $script:anyFail = $true }
}

Write-Host '======================================================================'
Write-Host '多多宠物商店 · 一键全量自检（S2）'
Write-Host "开始时间：$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
Write-Host '======================================================================'

# ---------- 前置：容器健康 ----------
Write-Host "`n[0/5] 前置检查：容器状态"
$ps = & docker compose ps --format '{{.Name}} {{.Status}}' 2>$null
if (-not $ps) {
    Write-Host '  [!!] 读不到容器状态（Docker 没在跑？）—— 后面的检查多半会红'; $anyFail = $true
    $results += @{ name = '容器前置'; ok = $false; detail = 'Docker 不可用' }
} else {
    $bad = @($ps | Where-Object { $_ -match 'unhealthy|Exited|Restarting' })
    if ($bad.Count -gt 0) {
        Write-Host ("  [!!] 有容器不健康：`n    " + ($bad -join "`n    "))
        $anyFail = $true
        $results += @{ name = '容器前置'; ok = $false; detail = ($bad -join '; ') }
    } else {
        $n = @($ps).Count
        Write-Host ("  [OK] {0} 个容器全部 healthy" -f $n) -ForegroundColor Green
        $results += @{ name = '容器前置'; ok = $true; detail = "$n 个 healthy" }
    }
}

# ---------- [1/5] 前端构建 ----------
if (-not $SkipFrontendBuild) {
    Write-Host "`n[1/5] 前端构建（tsc --noEmit && vite build，三应用）"
    Push-Location (Join-Path $repo 'frontend')
    & npm run build 2>&1 | Select-Object -Last 4 | Write-Host
    $code = $LASTEXITCODE
    Pop-Location
    Record '前端构建（tsc + vite）' ($code -eq 0) "exit=$code"

    # E7/E8 固化的两个产物校验（纳入门禁）：
    #   · import 说明符逐条解析 —— 大迁移后「静默失效引用」的唯一可靠抓法（E7-1 抓出 11 处）
    #   · 产物隔离 —— 「三端互不含对方代码」的机器可检结论（E8 起"商城不含后台代码"靠它证明）
    Write-Host "`n[1/5] 前端引用与产物隔离校验（check-frontend-imports / check-bundle-isolation）"
    & node (Join-Path $repo 'scripts\lib\check-frontend-imports.mjs') 2>&1 | Select-Object -Last 3 | Write-Host
    Record 'import 说明符解析校验' ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"
    & node (Join-Path $repo 'scripts\lib\check-bundle-isolation.mjs') 2>&1 | Select-Object -Last 6 | Write-Host
    Record '产物隔离校验' ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"
} else {
    Write-Host "`n[1/5] 前端构建：跳过（-SkipFrontendBuild）"
    $results += @{ name = '前端构建'; ok = $true; detail = '跳过' }
}

# ---------- [2/5] 后端单元测试 ----------
if (-not $SkipBackendTests) {
    Write-Host "`n[2/5] 后端单元测试（mvn test）"
    $env:JAVA_HOME = 'C:\Program Files\Java\latest\jdk-21\jdk21'
    $mvn = 'D:\devsoft\apache-maven-3.9.4\bin\mvn.cmd'
    if (-not (Test-Path $mvn)) {
        Write-Host "  [!!] 找不到 mvn：$mvn（路径见 docs/PLAN.md 环境速查）"
        Record '后端单元测试' $false "mvn 不存在：$mvn"
    } else {
        & $mvn -gs maven/settings.xml -B test 2>&1 |
            Select-String -Pattern 'Tests run:|BUILD SUCCESS|BUILD FAILURE|ERROR' |
            Select-Object -Last 8 | Write-Host
        Record '后端单元测试（mvn test）' ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"
    }
} else {
    Write-Host "`n[2/5] 后端单元测试：跳过（-SkipBackendTests）"
    $results += @{ name = '后端单元测试'; ok = $true; detail = '跳过' }
}

# ---------- [3/5] 接口冒烟 ----------
if (-not $SkipSmoke) {
    Write-Host "`n[3/5] 接口冒烟（run-all-smoke.ps1，23 套）"
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repo 'scripts\run-all-smoke.ps1') 2>&1 |
        Select-String -Pattern '通过|失败|ALL_EXIT|汇总' | Select-Object -Last 6 | Write-Host
    Record '接口冒烟（23 套）' ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"
} else {
    Write-Host "`n[3/5] 接口冒烟：跳过（-SkipSmoke）"
    $results += @{ name = '接口冒烟'; ok = $true; detail = '跳过' }
}

# ---------- [4/5] 浏览器 e2e ----------
if (-not $SkipE2E) {
    Write-Host "`n[4/5] 浏览器 e2e（run-ui-checks.ps1，17 套 654 项，入口 $UiBase）"
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repo 'scripts\e2e\run-ui-checks.ps1') -UiBase $UiBase 2>&1 |
        Select-String -Pattern '=====|PASS|FAIL' | Select-Object -Last 24 | Write-Host
    Record ('浏览器 e2e（17 套，入口 ' + $UiBase + '）') ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"
} else {
    Write-Host "`n[4/5] 浏览器 e2e：跳过（-SkipE2E）"
    $results += @{ name = '浏览器 e2e'; ok = $true; detail = '跳过' }
}

# ---------- [5/5] 汇总 ----------
$sw.Stop()
Write-Host '`n======================================================================'
Write-Host ('汇总（总耗时 ' + [int]$sw.Elapsed.TotalMinutes + ' 分 ' + $sw.Elapsed.Seconds + ' 秒）')
Write-Host '======================================================================'
foreach ($r in $results) {
    $tag = if ($r.ok) { 'PASS' } else { 'FAIL' }
    Write-Host ("  [{0}] {1}   {2}" -f $tag, $r.name, $r.detail)
}
if ($anyFail) {
    Write-Host "`n结论：存在失败项 —— 请先看上面各段的明细（日志在 logs\\smoke\\ 与本窗口输出）" -ForegroundColor Red
    exit 1
}
Write-Host "`n结论：全部通过 ✅（构建 / 单测 / 冒烟 / e2e）" -ForegroundColor Green
exit 0
