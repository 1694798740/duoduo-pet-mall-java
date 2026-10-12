<#
.SYNOPSIS
    云端同步暂存副本的「新鲜度」守卫与导出器（#343）。

.DESCRIPTION
    为什么需要它：腾讯文档 / 飞书 / 资料库三处上传的**都不是源文件本身**，而是
    `.workbuddy/sync-staging/` 下的一批副本（为了统一命名、避免中文路径问题）。
    于是存在一条**没有检查覆盖的失效链**：
        源文件改了 -> 忘了重新导出副本 -> 上传的还是旧副本 -> 云端「导入成功」
    而「导入成功」只说明传输没问题，**不说明内容是最新的**。
    2026-10-12 复核时就真的抓到 4 份落后（README / PLAN / SELFTEST / 项目简报）。

    本脚本把这条链拆成两半，各给一个能自动判的出口：
      ① export —— 从**源文件**重建全部副本（副本从此不再是"手工维护的第二份真相"）；
      ② verify —— 逐份比 SHA256，**副本 != 源文件即判失败**（不是 WARN、不降级）。
    ★ 「一次都没比」与「比了、结果全对」长得一样，故 verify 必须**自报比对数**，为 0 直接判违规。

    另外给了一个不需要暂存目录的 action：
      ③ manifest —— 只校验映射表自身没腐烂（条数 > 0、仓库侧源文件都真实存在、
                    且约定的 5 份仓库文档全在表里）。CI 里跑这个，
                    因为它不需要开发者本机的暂存目录。

.PARAMETER Action
    verify（默认）| export | manifest
.PARAMETER StagingDir
    暂存目录。默认取 $env:DUODUO_SYNC_STAGING。未提供时 verify/export 会明确报「不适用」并 exit 3
    （★ 不返回 0 —— 「没跑」不能长得像「跑过且通过」）。
.PARAMETER WorkspaceDir
    工作区根目录（`项目简报.md` 等 6 份源文件在这里，不在仓库里）。
    默认取 $env:DUODUO_WORKSPACE。

.EXAMPLE
    powershell -File scripts\lib\sync-staging.ps1 -Action manifest
.EXAMPLE
    powershell -File scripts\lib\sync-staging.ps1 -Action export -StagingDir D:\wb\sync-staging -WorkspaceDir D:\wb
.EXAMPLE
    powershell -File scripts\lib\sync-staging.ps1 -Action verify -StagingDir D:\wb\sync-staging -WorkspaceDir D:\wb
#>
[CmdletBinding()]
param(
    [ValidateSet('verify', 'export', 'manifest')]
    [string]$Action = 'verify',
    [string]$StagingDir = $env:DUODUO_SYNC_STAGING,
    [string]$WorkspaceDir = $env:DUODUO_WORKSPACE
)

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

# ---------------------------------------------------------------------------
# 映射表：**唯一出处**。新增一份要上云的文档，必须在这里加一行，否则 verify 看不见它、
# 云端就会一直拿着一份旧副本而没有任何人报错。
#   kind = repo -> 源文件在仓库里（相对 $repo）
#   kind = ws   -> 源文件在工作区根目录（相对 $WorkspaceDir）
# ★ 路径一律用**正斜杠**：这个文件也要在 CI（ubuntu-latest + pwsh）里跑，
#   反斜杠在 Linux 下是普通字符，`Join-Path $repo 'docs\PLAN.md'` 会解析不到文件。
# ---------------------------------------------------------------------------
$MAP = @(
    @{ kind = 'repo'; src = 'README.md';                          dst = 'README.md' }
    @{ kind = 'repo'; src = 'docs/PLAN.md';                       dst = '开发计划与已知边界-PLAN.md' }
    @{ kind = 'repo'; src = 'docs/SELFTEST.md';                   dst = '自测方法-SELFTEST.md' }
    @{ kind = 'repo'; src = 'docs/OPS.md';                        dst = '运维手册-OPS.md' }
    @{ kind = 'repo'; src = 'docs/S6-DUAL-CLIENT.md';             dst = '双端功能对照-S6-DUAL-CLIENT.md' }
    @{ kind = 'ws';   src = '项目简报.md';                          dst = '项目简报.md' }
    @{ kind = 'ws';   src = '遗留项与待办总清单-2026-10-11.md';      dst = '遗留项与待办总清单-2026-10-11.md' }
    @{ kind = 'ws';   src = '开发执行总纲-唯一执行依据-2026-10-08.md'; dst = '开发执行总纲-唯一执行依据-2026-10-08.md' }
    @{ kind = 'ws';   src = '完整验收-测试报告与执行指引.md';         dst = '完整验收-测试报告与执行指引.md' }
    @{ kind = 'ws';   src = '三平台独立部署-架构调整方案-2026-10-08.md'; dst = '三平台独立部署-架构调整方案-2026-10-08.md' }
    @{ kind = 'ws';   src = '优化建议与待确认事项-最新版-2026-10-07.md'; dst = '优化建议与待确认事项-最新版-2026-10-07.md' }
)

# 仓库侧必须全部在映射里的文档（双向断言用：既防"表里多"、也防"表里漏"）
$REPO_EXPECTED = @('README.md', 'docs/PLAN.md', 'docs/SELFTEST.md', 'docs/OPS.md', 'docs/S6-DUAL-CLIENT.md')

function Get-SourcePath($item) {
    if ($item.kind -eq 'repo') { return (Join-Path $repo $item.src) }
    if (-not $WorkspaceDir) { return $null }
    return (Join-Path $WorkspaceDir $item.src)
}

function Get-HashOf($path) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $null }
    return (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
}

Write-Host ''
Write-Host '[sync-staging] 云端同步暂存副本的新鲜度守卫'
Write-Host "  仓库根：$repo"
Write-Host "  action：$Action"
Write-Host ("  映射条数：{0}" -f $MAP.Count)

# ★ 自证：映射表为空 = 后面每一项都会"全部通过"，而其实一份都没比（铁律「扫描数 0 判违规」）
if ($MAP.Count -eq 0) {
    Write-Host '  [FAIL] 映射表为空 —— 没有任何一份被比对，不能判通过' -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------------------
# action = manifest：只校验映射表自身，不需要暂存目录（CI 用这个）
# ---------------------------------------------------------------------------
if ($Action -eq 'manifest') {
    $bad = @()
    foreach ($item in $MAP) {
        if ($item.kind -ne 'repo') { continue }
        $p = Join-Path $repo $item.src
        if (-not (Test-Path -LiteralPath $p -PathType Leaf)) {
            Write-Host ("  [FAIL] 映射表登记了仓库文档但文件不存在：{0}" -f $item.src) -ForegroundColor Red
            $bad += $item.src
        }
    }
    $inMap = @($MAP | Where-Object { $_.kind -eq 'repo' } | ForEach-Object { $_.src })
    foreach ($want in $REPO_EXPECTED) {
        if ($inMap -notcontains $want) {
            Write-Host ("  [FAIL] 约定的仓库文档没有登记进映射表：{0}（新增文档忘了加行 -> 云端会一直是旧副本）" -f $want) -ForegroundColor Red
            $bad += $want
        }
    }
    if ($inMap.Count -ne $REPO_EXPECTED.Count) {
        Write-Host ("  [FAIL] 仓库侧映射 {0} 条与约定 {1} 条不等（多登记或少登记都要显式说清）" -f $inMap.Count, $REPO_EXPECTED.Count) -ForegroundColor Red
        $bad += 'count'
    }
    if ($bad.Count -gt 0) { Write-Host '  结论：不通过' -ForegroundColor Red; exit 1 }
    Write-Host ("  [OK]   仓库侧映射 {0} 条全部存在且与约定一致" -f $inMap.Count) -ForegroundColor Green
    Write-Host '  结论：通过' -ForegroundColor Green
    exit 0
}

# ---------------------------------------------------------------------------
# verify / export 都需要暂存目录
# ---------------------------------------------------------------------------
if (-not $StagingDir) {
    Write-Host '  [N/A]  未提供暂存目录（-StagingDir 或 $env:DUODUO_SYNC_STAGING）' -ForegroundColor Yellow
    Write-Host '         本步只在「做云端同步的那台机器」上适用；这里没跑，不冒充通过。' -ForegroundColor Yellow
    Write-Host '  结论：不适用（exit 3，不是 0）' -ForegroundColor Yellow
    exit 3
}
if (-not (Test-Path -LiteralPath $StagingDir -PathType Container)) {
    Write-Host ("  [N/A]  暂存目录不存在：{0}" -f $StagingDir) -ForegroundColor Yellow
    Write-Host '  结论：不适用（exit 3，不是 0）' -ForegroundColor Yellow
    exit 3
}

if (-not $WorkspaceDir) {
    Write-Host '  [FAIL] 提供了暂存目录但没给工作区目录（-WorkspaceDir 或 $env:DUODUO_WORKSPACE）——' -ForegroundColor Red
    Write-Host '         6 份源文件在工作区根目录，缺了它们只会验到一半' -ForegroundColor Red
    exit 1
}

if ($Action -eq 'export') {
    New-Item -ItemType Directory -Force -Path $StagingDir | Out-Null
    $copied = 0
    $exported = @()
    foreach ($item in $MAP) {
        $src = Get-SourcePath $item
        if (-not $src -or -not (Test-Path -LiteralPath $src -PathType Leaf)) {
            Write-Host ("  [FAIL] 源文件不存在，无法导出：{0}" -f $src) -ForegroundColor Red
            exit 1
        }
        $dst = Join-Path $StagingDir $item.dst
        Copy-Item -LiteralPath $src -Destination $dst -Force
        $copied++
    }
    Write-Host ("  已导出 {0} 份副本到 {1}" -f $copied, $StagingDir)
    Write-Host ''
    Write-Host '  —— 导出后立刻自验 ——'
}

# ---------------------------------------------------------------------------
# 逐份比对
# ---------------------------------------------------------------------------
$stale = @()
$checked = 0
foreach ($item in $MAP) {
    $src = Get-SourcePath $item
    $dst = Join-Path $StagingDir $item.dst
    if (-not $src) {
        Write-Host ("  [FAIL] 源路径无法解析（工作区目录未给全）：{0}" -f $item.dst) -ForegroundColor Red
        $stale += $item.dst
        continue
    }
    if (-not (Test-Path -LiteralPath $src -PathType Leaf)) {
        Write-Host ("  [FAIL] 源文件不存在：{0}（改名了？映射表要跟着改）" -f $src) -ForegroundColor Red
        $stale += $item.dst
        continue
    }
    if (-not (Test-Path -LiteralPath $dst -PathType Leaf)) {
        Write-Host ("  [FAIL] 暂存副本不存在：{0}" -f $dst) -ForegroundColor Red
        $stale += $item.dst
        continue
    }
    $hs = Get-HashOf $src
    $hd = Get-HashOf $dst
    $checked++
    if ($hs -eq $hd) {
        Write-Host ("  [OK]   {0}" -f $item.dst)
    }
    else {
        $ss = (Get-Item -LiteralPath $src).Length
        $sd = (Get-Item -LiteralPath $dst).Length
        Write-Host ("  [STALE] {0}  源 {1}B / 副本 {2}B  —— 副本落后了，先 export 再上传" -f $item.dst, $ss, $sd) -ForegroundColor Red
        $stale += $item.dst
    }
}

Write-Host ''
Write-Host ("  实际比对份数：{0} / 映射 {1}" -f $checked, $MAP.Count)
if ($checked -eq 0) {
    Write-Host '  [FAIL] 一份都没比成 —— 「没比」不能判通过' -ForegroundColor Red
    exit 1
}
if ($stale.Count -gt 0) {
    Write-Host ("  结论：不通过 —— {0} 份不新鲜/缺失：{1}" -f $stale.Count, ($stale -join '、')) -ForegroundColor Red
    exit 1
}
Write-Host ("  结论：通过 —— {0} 份副本与源文件逐份 SHA256 一致" -f $checked) -ForegroundColor Green
exit 0
