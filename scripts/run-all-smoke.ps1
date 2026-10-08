# 批量跑全部冒烟脚本并汇总结果（Windows PowerShell 5.1）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\run-all-smoke.ps1
#
# 目的：交付前一次性确认「全站 19 套冒烟」没有因为某处公共改动（如序列化规则、id 类型）
#       而被连带打破。逐套落盘日志，最后打印汇总表与失败脚本清单。
#       search-smoke 需要 Elasticsearch 在跑（compose profile=search）：
#       不可达时**显式跳过并列入汇总**，不静默略过。
param(
    [string]$Only = ''
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $root 'logs\smoke'
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir -Force | Out-Null }

# 排除本脚本自身（文件名也以 smoke.ps1 结尾，若不排除会自我递归调用）
# ⚠️ 通配符必须是 '*smoke.ps1' 而不是 '*-smoke.ps1'：后者要求名字里带 '-s'，
#    会把最基础的那套 `smoke.ps1` 静默漏掉 —— 于是「一键全量」少跑一套还照样报「全部通过」。
$scripts = @(Get-ChildItem -Path (Join-Path $script:root 'scripts') -Filter '*smoke.ps1' -File |
    Where-Object { $_.Name -ne 'run-all-smoke.ps1' } |
    Sort-Object Name)
if ($Only) {
    $scripts = @($scripts | Where-Object { $_.BaseName -like "*$Only*" })
}

# 前置条件检查：search-smoke 要 ES。跳过的套件必须**说出来** ——
# 静默跳过会让「19 套全绿」变成一句谎话。
$skipped = @()
if (@($scripts | Where-Object { $_.Name -eq 'search-smoke.ps1' }).Count -gt 0) {
    $esUp = $false
    try {
        $esProbe = Invoke-WebRequest -Uri 'http://127.0.0.1:9200/_cluster/health' -UseBasicParsing -TimeoutSec 3
        $esUp = ($esProbe.StatusCode -eq 200)
    } catch { }
    if (-not $esUp) {
        $skipped += 'search-smoke.ps1  （ES 未运行；先执行 docker compose --profile search up -d duoduo-es）'
        $scripts = @($scripts | Where-Object { $_.Name -ne 'search-smoke.ps1' })
    }
}

$results = @()
foreach ($s in $scripts) {
    Write-Host ""
    Write-Host "==================================================================" -ForegroundColor Cyan
    Write-Host " 运行 $($s.Name)" -ForegroundColor Cyan
    Write-Host "==================================================================" -ForegroundColor Cyan
    $logPath = Join-Path $logDir ($s.BaseName + '.log')
    # 用子进程跑，避免脚本里的 exit 影响本脚本
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $s.FullName 2>&1 |
        Where-Object { "$_" -notmatch '正在读取|字节数' } |
        Tee-Object -FilePath $logPath | Out-Null
    $code = $LASTEXITCODE
    $content = Get-Content $logPath -Raw -ErrorAction SilentlyContinue
    $summary = if ($content -match '===.*?結果|===.*?结果') {
        ([regex]::Match($content, '===.*?通过\s*(\d+)\s*项，失败\s*(\d+)\s*项.*?===')).Value
    } else { '(无汇总行)' }
    $guardOk = $content -match '\[GUARD-OK\]'
    $guardFail = $content -match '\[GUARD-FAIL\]'
    Write-Host "  -> EXIT=$code  $summary"
    if ($guardFail) { Write-Host "  -> ⚠ 断言总数守卫未通过" -ForegroundColor Yellow }
    $results += [pscustomobject]@{
        Script  = $s.Name
        Exit    = $code
        Summary = $summary
        Guard   = if ($guardFail) { 'FAIL' } elseif ($guardOk) { 'OK' } else { 'n/a' }
    }
}

# E8 · node 冒烟显式列出：上面的发现逻辑只认 *smoke.ps1，node 脚本不会自动进清单 ——
# 漏列的后果是「门禁少跑一套还照样报全绿」（与文件头注释里 smoke.ps1 通配符那个坑同性质）
$nodeSmoke = @('e8-entry-scope-smoke.mjs')
foreach ($n in $nodeSmoke) {
    $f = Join-Path (Join-Path $script:root 'scripts') $n
    if (-not (Test-Path $f)) { continue }
    Write-Host ""
    Write-Host "==================================================================" -ForegroundColor Cyan
    Write-Host " 运行 $n" -ForegroundColor Cyan
    Write-Host "==================================================================" -ForegroundColor Cyan
    $logPath = Join-Path $logDir ([IO.Path]::GetFileNameWithoutExtension($n) + '.log')
    & node $f 2>&1 | Tee-Object -FilePath $logPath | Out-Null
    $code = $LASTEXITCODE
    Write-Host "  -> EXIT=$code"
    $results += [pscustomobject]@{
        Script  = $n
        Exit    = $code
        Summary = '(node 脚本，以退出码为准)'
        Guard   = 'n/a'
    }
}

Write-Host ""
Write-Host "==================================================================" -ForegroundColor Green
Write-Host " 汇总" -ForegroundColor Green
Write-Host "==================================================================" -ForegroundColor Green
$results | Format-Table -AutoSize | Out-String | Write-Host

$failed = @($results | Where-Object { $_.Exit -ne 0 })
Write-Host "共 $($results.Count) 套，通过 $(($results.Count - $failed.Count)) 套，失败 $($failed.Count) 套"
if ($skipped.Count -gt 0) {
    Write-Host "已跳过 $($skipped.Count) 套（前置条件未满足）：" -ForegroundColor Yellow
    $skipped | ForEach-Object { Write-Host "  - $_" -ForegroundColor Yellow }
}
if ($failed.Count -gt 0) {
    Write-Host "失败清单：" -ForegroundColor Red
    $failed | ForEach-Object { Write-Host "  - $($_.Script)  EXIT=$($_.Exit)  $($_.Summary)" -ForegroundColor Red }
    exit 1
}
Write-Host "全部通过。" -ForegroundColor Green
exit 0
