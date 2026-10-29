# 批量跑全部冒烟脚本并汇总结果（Windows PowerShell 5.1 / pwsh 7 均可）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\run-all-smoke.ps1
#        pwsh     ./scripts/run-all-smoke.ps1
#
# 目的：交付前一次性确认「全站 27 套冒烟」没有因为某处公共改动（如序列化规则、id 类型）
#       而被连带打破。逐套落盘日志，最后打印汇总表与失败脚本清单。
#       search-smoke 需要 Elasticsearch 在跑（compose profile=search）：
#       不可达时**显式跳过并列入汇总**，不静默略过。
#
# ⚠ 本脚本必须同时在 Windows（PS 5.1）与 CI（ubuntu + pwsh 7）上可跑：
#   · 调子进程的 shell 名**不能写死**（见下面的 $shell）；
#   · 读日志要显式 -Encoding UTF8（见下面的 Get-Content）；
#   · 汇总行有两种历史格式，两种都要认。
#   这三条都是「本机绿、CI 红」的实际教训，改动本文件时不要"简化"回去。
param(
    [string]$Only = ''
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $root 'logs/smoke'
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

# ── B-2 · 特权夹具账号的「运行前后 diff」（根治那一半）──────────────────────
# 为什么必须在这里做：冒烟为了测「运营 / 商家」两条链路，**必须**现场造 OPERATOR/SELLER 账号；
# 而几十套脚本里只有一部分记得收尾 ⇒ 每跑一轮全量回归就沉淀几十个能进后台的账号。
# 实测：2026-10-10 是 199+139，2026-10-13 已涨到 510+436（合计 946）—— 暴露面在扩大。
#
# 做法刻意选「diff」而不是「按昵称/手机号猜夹具」：
#   · diff 只看**这次运行新产生的**特权账号，天然不会误伤任何既有账号；
#   · 不依赖昵称规范（那是会变的约定，今天加了明天就忘）；
#   · 两个来源：跑之前一份 id 清单，跑之后一份，差集就是要收尾的那批。
$sweepHelper = Join-Path (Join-Path $root 'scripts') 'lib/fixture-role-sweep.ps1'
$privBefore = @()
if (Test-Path $sweepHelper) {
    $privBefore = @(& docker exec -i -e 'MYSQL_PWD=duoduo_root' duoduo-mysql mysql -uroot -N -B `
            --default-character-set=utf8mb4 -e "SELECT id FROM duoduo_user.t_user WHERE role<>'USER';" 2>$null |
        Where-Object { "$_".Trim() -match '^\d+$' } | ForEach-Object { "$_".Trim() })
}
Write-Host ("  [夹具收尾] 开跑前特权账号 {0} 个（跑完会把**新增的**降权）" -f $privBefore.Count) -ForegroundColor DarkGray

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
# ★★ 子进程用的 shell 必须按平台选 ★★
#
# 这里原先写死 `powershell.exe`。在 Windows 上没问题，在 CI（ubuntu-latest）上
# 那个可执行文件**根本不存在**，于是每一套脚本都直接以
#     The term 'powershell.exe' is not recognized as a name of a cmdlet,
#     function, script file, or executable program.
# 结束、日志里没有汇总行、结果表全是 `(无汇总行)`，汇总打印「共 27 套，通过 1 套，失败 26 套」。
#
# 为什么这个错能藏这么久：全量冒烟 job 在那之前就死在「起全栈」那一步，
# 压根没跑到这里。前端产物缺失（#277 根因②）把这条链路一直挡在外面 ——
# **一个上游故障会让它后面所有的问题都不可见**。
#
# 判据与 ci-run.ps1 保持一致（那一份在 CI 上是跑通的）：
# 非 Windows 或 CI 环境用 `pwsh`，Windows 本地用自带的 `powershell`。
# 不用 `$IsWindows`：它在 Windows PowerShell 5.1 里**不存在**，
# 取值为 $null 会被判成"非 Windows"，于是本地反而去找不存在的 pwsh。
#
# ★ 2026-10-27 修：判据不能再看 `$env:CI` —— 本机 agent 沙箱里也会带 CI 变量，
#   结果 Windows 本地全量回归 27 套**全部**死于 'pwsh' is not recognized，
#   每套都 EXIT=0 + (无汇总行) 照常进表，一个上游变量把整份「全绿」变成假绿。
#   改成按**可执行文件是否真的存在**选：CI（ubuntu）没有 powershell → pwsh；
#   Windows 本地有 powershell → 用它，$env:CI 设没设都不再影响。
$shell = if ($IsLinux -or $IsMacOS) { 'pwsh' }
elseif (Get-Command powershell -ErrorAction SilentlyContinue) { 'powershell' }
else { 'pwsh' }
foreach ($s in $scripts) {
    Write-Host ""
    Write-Host "==================================================================" -ForegroundColor Cyan
    Write-Host " 运行 $($s.Name)" -ForegroundColor Cyan
    Write-Host "==================================================================" -ForegroundColor Cyan
    $logPath = Join-Path $logDir ($s.BaseName + '.log')
    # 用子进程跑，避免脚本里的 exit 影响本脚本
    & $shell -NoProfile -ExecutionPolicy Bypass -File $s.FullName 2>&1 |
        Where-Object { "$_" -notmatch '正在读取|字节数' } |
        Tee-Object -FilePath $logPath | Out-Null
    $code = $LASTEXITCODE
    # 读日志一律显式 UTF8：Windows PowerShell 5.1 的 Get-Content 默认按 ANSI 解码，
    # 而 CI（pwsh）写出的日志是无 BOM 的 UTF-8 —— 不指定编码时中文汇总行会变成乱码，
    # 「通过 N 项，失败 M 项」匹配不上，于是**跑绿的套件也被记成「无汇总行」**。
    $content = Get-Content $logPath -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
    # 汇总行有两种格式（本仓历史遗留），两种都要认 —— 与 ci-run.ps1 同口径：
    #   ① "=== 冒烟结果：通过 82 项，失败 0 项 ==="
    #   ② "冒烟结果：62 通过 / 0 失败"
    $summary = '(无汇总行)'
    $m = [regex]::Match([string]$content, '通过\s*(\d+)\s*项，失败\s*(\d+)\s*项')
    if (-not $m.Success) { $m = [regex]::Match([string]$content, '(\d+)\s*通过\s*[/／]\s*(\d+)\s*失败') }
    if ($m.Success) { $summary = "通过 $($m.Groups[1].Value) / 失败 $($m.Groups[2].Value)" }
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

# ── B-2 · 收尾：把本轮**新增的**特权夹具账号降权（详见文件上方开跑前的注释）──────
if (Test-Path $sweepHelper) {
    Write-Host ""
    Write-Host "------------------------------------------------------------------" -ForegroundColor DarkYellow
    Write-Host " 夹具收尾：把本轮新增的特权账号降权" -ForegroundColor DarkYellow
    Write-Host "------------------------------------------------------------------" -ForegroundColor DarkYellow
    $privAfter = @(& docker exec -i -e 'MYSQL_PWD=duoduo_root' duoduo-mysql mysql -uroot -N -B `
            --default-character-set=utf8mb4 -e "SELECT id FROM duoduo_user.t_user WHERE role<>'USER';" 2>$null |
        Where-Object { "$_".Trim() -match '^\d+$' } | ForEach-Object { "$_".Trim() })
    $newPriv = @($privAfter | Where-Object { $privBefore -notcontains $_ })
    Write-Host ("  本轮新增特权账号 {0} 个（跑前 {1} → 跑后 {2}）" -f $newPriv.Count, $privBefore.Count, $privAfter.Count)
    if ($newPriv.Count -eq 0) {
        # ★ 0 个也要说出来：它和"这段没跑"长得一样（本项目的常见坑），所以显式打印"确实扫到 0"
        Write-Host "  本轮没有新增特权账号（已核对：跑前跑后都读到了真实清单）" -ForegroundColor Green
    } else {
        $idsFile = Join-Path $env:TEMP ("duoduo-newpriv-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.txt')
        [System.IO.File]::WriteAllLines($idsFile, $newPriv, (New-Object System.Text.UTF8Encoding $false))
        & powershell -NoProfile -ExecutionPolicy Bypass -File $sweepHelper -Apply -IdsFile $idsFile 2>&1 | Write-Host
        if ($LASTEXITCODE -ne 0) {
            Write-Host "  [WARN] 夹具收尾未成功（exit=$LASTEXITCODE）—— 不改本次冒烟结论，但请人工看一眼" -ForegroundColor Yellow
        }
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
