# A6 · CI 冒烟子集执行器：顺序跑指定的冒烟脚本，逐套落盘 + 分组输出 + 汇总，任何一套失败即整体退出非 0。
# 设计与 run-all-smoke.ps1 相同的输出契约（"通过 X / 失败 Y"），差别只在于：**脚本集合由参数指定**，
# 供 CI 按「受改动域」选择子集（全套跑下来太慢，全套回归留在本地 / 夜间）。
#
# 用法：
#   pwsh ./scripts/ci-run.ps1 -Scripts smoke.ps1,trade-smoke.ps1,order-smoke.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ci-run.ps1 -Scripts smoke.ps1,trade-smoke.ps1
#
# 日志：logs/ci/<脚本名>.log；汇总表打到最后，失败清单逐条列出（CI 里用 ::group 折叠由 workflow 负责）。

param(
    [Parameter(Mandatory = $true)]
    [string]$Scripts
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $root 'logs/ci'
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }

$names = $Scripts.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ }
if (-not $names) { Write-Error '未指定任何脚本'; exit 2 }

# 自我防护：不允许把 ci-run.ps1 / run-all-smoke.ps1 传进来（递归 / 重复执行全套）
$forbidden = @('ci-run.ps1', 'run-all-smoke.ps1')
foreach ($n in $names) {
    if ($forbidden -contains $n) {
        Write-Host "[x] 禁止把 $n 传给 -Scripts（会递归/重复全套）"; exit 2
    }
}

$results = @()
foreach ($n in $names) {
    $path = Join-Path $root "scripts\$n"
    if (-not (Test-Path $path)) {
        Write-Host "[x] 脚本不存在：$path"; exit 2
    }
    Write-Host ""
    Write-Host "===== [$n] 开始 ====="
    $log = Join-Path $logDir ($n -replace '\.ps1$', '.log')
    # Windows 用自带 powershell（5.1），Linux/macOS（CI）用 pwsh
    $pwsh = if ($IsLinux -or $IsMacOS -or $env:CI) { 'pwsh' } else { 'powershell' }
    & $pwsh -NoProfile -ExecutionPolicy Bypass -File $path *> $log
    $exit = $LASTEXITCODE

    # 与各冒烟脚本的汇总行对齐（本仓两种格式都存在）：
    #   ① "=== 冒烟结果：通过 82 项，失败 0 项 ==="
    #   ② "冒烟结果：62 通过 / 0 失败"
    $tail = Get-Content $log -Tail 40 -Encoding UTF8
    $pass = $null; $fail = $null
    foreach ($line in $tail) {
        if ($line -match '通过\s*(\d+)\s*项，失败\s*(\d+)\s*项') { $pass = [int]$Matches[1]; $fail = [int]$Matches[2] }
        elseif ($line -match '(\d+)\s*通过\s*[/／]\s*(\d+)\s*失败') { $pass = [int]$Matches[1]; $fail = [int]$Matches[2] }
    }

    $status = if ($exit -eq 0 -and $null -ne $fail -and $fail -eq 0) { 'PASS' } else { 'FAIL' }
    $results += [pscustomobject]@{ Script = $n; Pass = $pass; Fail = $fail; Exit = $exit; Status = $status; Log = $log }
    Write-Host "===== [$n] 结束：$status（pass=$pass fail=$fail exit=$exit） ====="
}

Write-Host ""
Write-Host "========== CI 冒烟汇总 =========="
$totals = 0; $totalf = 0; $bad = @()
foreach ($r in $results) {
    Write-Host ("  {0,-28} {1,-5} pass={2,-5} fail={3}" -f $r.Script, $r.Status, $r.Pass, $r.Fail)
    if ($null -ne $r.Pass) { $totals += $r.Pass }
    if ($null -ne $r.Fail) { $totalf += $r.Fail }
    if ($r.Status -eq 'FAIL') { $bad += $r.Script }
}
Write-Host ("  合计：通过 {0} / 失败 {1}" -f $totals, $totalf)
if ($bad) {
    Write-Host ""
    Write-Host "失败脚本：$($bad -join ', ')"
    Write-Host "失败断言明细（节选自各日志）："
    foreach ($b in $bad) {
        $log = Join-Path $logDir ($b -replace '\.ps1$', '.log')
        Write-Host "--- $b ---"
        Get-Content $log -Encoding UTF8 | Where-Object { $_ -match '\[FAIL\]' } | ForEach-Object { Write-Host "  $_" }
    }
    exit 1
}
Write-Host "全部通过。"
exit 0
