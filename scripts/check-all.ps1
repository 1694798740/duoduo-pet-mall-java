<#
.SYNOPSIS
    一条命令跑完全部自检（S2）：前端构建 → 后端单测 → 接口冒烟 → 浏览器 e2e → 汇总。

.DESCRIPTION
    为什么需要它：此前的自检分散在 4 类脚本里（接口冒烟、浏览器 e2e、单元测试、前端构建），
    每类各有入口、各有前置 —— 每次交付都要靠记忆拼顺序，
    券的测试还要先建夹具。**容易漏跑；漏跑的检查等于没检查**（S2 立项的原因）。

    ★ 本脚本里的套件数量**一律从目录实算**（见下面的 $smokeCount / $e2eCount），
      不写死数字：写死的那个数会在「加了新套件」之后变成假话，而且没人会记得改它
      —— 于是「23 套」这种文案会比实际少一套还照样报全绿（T-1 同型坑）。

    本脚本只做「串联与汇总」，不重复实现任何检查：
      [0/5] 静态守卫        冒烟脚本静态自检 + 网关路由覆盖自检 + Mapper SQL 实体自检
                            + 运维手册一致性自检 + 双端功能对照表自检 + 依赖安全自检（无需 Docker / 编译）
                            + 对象存储抽象层自检（S9）
                            + 文档飞书吞行吞字自检 + 图表主题接入点 + e2e 截图输出
                            + 云端暂存副本新鲜度（★ 只在配了 DUODUO_SYNC_STAGING 的机器上适用，
                              否则显式记 [N/A] 并在此处说明，不冒充通过）
      [1/5] 前端构建        `npm run build`（= tsc 类型检查 + vite 打包）
      [2/5] 后端单元测试     `mvn test`（最耗时，可 -SkipBackendTests）
      [3/5] 接口冒烟         `run-all-smoke.ps1`（脚本自发现）
      [4/5] 浏览器 e2e       `run-ui-checks.ps1 -UiBase <入口>`（套件自发现；券夹具自动建/撤）
      [5/5] 汇总表           每段 PASS/FAIL + 总耗时；**全绿才 exit 0**

    e2e 默认打**正式版容器**（http://localhost:8088，S1 的产物）——
    测的就是用户实际在用的入口，且**不需要启动 dev server**。
    想测开发态：-UiBase 'http://localhost:5173'（会自动起/停 dev server）。

.PARAMETER SkipFrontendBuild  跳过前端构建（改过后端没动前端时省 30 秒）
.PARAMETER SkipBackendTests   跳过 mvn test（最耗时的一段）
.PARAMETER SkipSmoke          跳过接口冒烟
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
# 套件数量从目录**实算**（口径与两个 runner 的自发现规则一致）：
#   冒烟 = scripts/*smoke.ps1 去掉 runner 自身；e2e = scripts/e2e/*-ui-check.mjs
# ★ 通配符必须写 '*smoke.ps1' 而不是 '*-smoke.ps1'：后者要求名字里带连字符，
#   会把最基础的那套 `smoke.ps1` 漏掉 —— 数量少报一套，而所有断言照旧全绿。
$smokeCount = @(Get-ChildItem -Path (Join-Path $repo 'scripts') -Filter '*smoke.ps1' -File |
    Where-Object { $_.Name -ne 'run-all-smoke.ps1' }).Count
$e2eCount = @(Get-ChildItem -Path (Join-Path $repo 'scripts\e2e') -Filter '*-ui-check.mjs' -File).Count
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$results = @()
$anyFail = $false

# ── 特权夹具账号的「跑前快照」（B-2）────────────────────────────────────────
# 为什么放在 check-all 而不是只放在冒烟里：e2e 也会现场造 OPERATOR/SELLER，且部分套件不收尾。
# 2026-10-18 实测：冒烟结束时已清扫 26 个，但 e2e 跑完又留下 19 个 OPERATOR + 17 个 SELLER。
# 做法仍是 diff（只看**本轮新增的**），不猜昵称、不误伤既有账号 —— 包括你验收用的运营账号。
$sweepHelper = Join-Path $repo 'scripts\lib\fixture-role-sweep.ps1'
function Get-PrivilegedIds {
    return @(& docker exec -i -e 'MYSQL_PWD=duoduo_root' duoduo-mysql mysql -uroot -N -B `
            --default-character-set=utf8mb4 -e "SELECT id FROM duoduo_user.t_user WHERE role<>'USER';" 2>$null |
        Where-Object { "$_".Trim() -match '^\d+$' } | ForEach-Object { "$_".Trim() })
}
$privBefore = @()
if (Test-Path $sweepHelper) {
    $privBefore = @(Get-PrivilegedIds)
    Write-Host ("  [夹具] 跑前特权账号 {0} 个（跑完只降权本轮新增的）" -f $privBefore.Count) -ForegroundColor DarkGray
}

function Record($name, $ok, $detail) {
    if ($ok) { Write-Host ("  [PASS] {0}  {1}" -f $name, $detail) -ForegroundColor Green }
    else     { Write-Host ("  [FAIL] {0}  {1}" -f $name, $detail) -ForegroundColor Red }
    $script:results += @{ name = $name; ok = $ok; detail = $detail }
    if (-not $ok) { $script:anyFail = $true }
}

# ★ 第三态：这一步在本机**不适用**（前置产物不在这台机器上）。
#   为什么不直接判 PASS：那会把「没跑」写成「跑过且通过」；
#   为什么不判 FAIL：本机的取舍不该拦住宿主的全量自检。
#   做法＝显式打 [N/A] + 写清原因，并在汇总里单独计数（**不静默**）。
function RecordNa($name, $why) {
    Write-Host ("  [N/A]  {0}  {1}" -f $name, $why) -ForegroundColor Yellow
    $script:results += @{ name = $name; ok = $null; detail = $why }
}

Write-Host '======================================================================'
Write-Host '多多宠物商店 · 一键全量自检（S2）'
Write-Host "开始时间：$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
Write-Host '======================================================================'

# ---------- [0/5] 前置：静态守卫 ----------
# 放在最前面：纯静态、不需要 Docker、不需要编译，是全场最便宜的一段，
# 却能挡掉三类"不报错但结论失效"的问题：
#   · 断言绑定写错（判假且不报错）
#   · .ps1 少 BOM（PS 5.1 按 ANSI 解码 → 中文乱码 → 断言恒不命中）
#   · 接口写了但网关没放行（唯一表现是 404，与"服务没起来"长得一模一样）
Write-Host "`n[0/5] 前置检查：静态守卫（断言绑定 / UTF-8 BOM / 网关路由覆盖 / Mapper SQL 实体 / 运维手册一致性 / 双端功能对照表 / 依赖安全 / 对象存储抽象层 / 文档飞书吞行吞字 / 图表主题接入点 / e2e 截图输出 / 云端暂存新鲜度）"
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repo 'scripts\lib\check-smoke-static.ps1') 2>&1 | Select-Object -Last 2 | Write-Host
Record '冒烟脚本静态自检' ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repo 'scripts\lib\check-gateway-routes.ps1') 2>&1 | Select-Object -Last 2 | Write-Host
Record '网关路由覆盖自检' ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"
# Mapper 注解 SQL 里「无 <script> 却写 XML 实体」——编译期/启动期都无征兆，
# 只在 MyBatis-Plus 解析 SQL 时以 500 暴露（E24 实测踩中）。纯静态，放这里最便宜。
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repo 'scripts\lib\check-mapper-sql-entities.ps1') 2>&1 | Select-Object -Last 2 | Write-Host
Record 'Mapper SQL 实体自检' ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"
# 运维手册一致性（docs/OPS.md 引用的每个 scripts 路径必须真实存在 + 开关表与
# ops-switch.ps1 清单双向一致）—— "文档落后"是一整类、且大多没有门禁，这条给运维手册装上。
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repo 'scripts\lib\check-ops-doc.ps1') 2>&1 | Select-Object -Last 2 | Write-Host
Record '运维手册一致性自检' ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"
# 双端功能对照表一致性（docs/S6-DUAL-CLIENT.md）—— 小程序与网页商城各 30+ 页面，
# "某端漏做一个页面"没有任何编译期征兆，只能靠文档 + 静态断言守。这条同时守
# tabBar 双向一致与「缺口清单仍成立」（缺口被补齐时会提醒改文档）。
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repo 'scripts\lib\check-dual-client.ps1') 2>&1 | Select-Object -Last 2 | Write-Host
Record '双端功能对照表自检' ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"
# 第三方依赖安全扫描（S13）—— 纯离线：本地漏洞版本底线 + 版本锁定 + 供应链卫生。
# 刻意不用 OWASP dependency-check / npm audit（都要联网，挂了等于没挂）。
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repo 'scripts\lib\check-dependency-security.ps1') 2>&1 | Select-Object -Last 2 | Write-Host
Record '第三方依赖安全自检' ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"
# 对象存储抽象层守卫（S9）—— 四层判据：① ObjectStorage 接口不得被 java.nio.file 污染；
# ② 三个调用方（UploadController / MerchantMaterialReadController / MerchantMaterialStorage）
# 不得绕过接口直接落盘；③ 配置一致性（type 默认必须是 local、compose 必须透传
# UPLOADS_PUBLIC_BASE_URL 与 4 个 COS_*）；④ ★ 正对照（本地实现里必须能扫到 java.nio.file，
# 否则"干净"只是没读到文件）。
# ★ 为什么必须进一键自检：这类退化**不会自己报错** —— 表现是"配置改成了 COS，
#   但某些新功能依然落在容器卷里"，两处都"看起来正常"。不进 CI ＝ 迟早静默腐烂。
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repo 'scripts\lib\check-storage-guard.ps1') 2>&1 | Select-Object -Last 2 | Write-Host
Record '对象存储抽象层自检' ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"
# 文档「只在飞书现形」的两条书写陷阱（2026-10-12 实测）——
#   形态一：列表续行以行首裸反引号开头 ⇒ 导入飞书**整行**被吞；
#   形态二：表格单元格里行内代码含未转义竖线 ⇒ 导入飞书该格**从竖线处被截断**。
#   两种写法在 GitHub / 腾讯文档渲染都正常；没有门禁的话，"内容丢失"只能靠人工逐行比对发现。
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repo 'scripts\lib\check-md-bare-tick.ps1') 2>&1 | Select-Object -Last 3 | Write-Host
Record '文档飞书吞行/吞字自检' ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"
# 图表主题接入点契约（S10）—— currentChartThemeMode() 是全站图表主题的**唯一接入点**，
# 它「将来做全局深色主题只改这一处」的价值全靠四条约定撑着：读 <html data-theme> /
# 回落正确（只有精确 dark 才切深色）/ 消费端在**组件内**求值（模块级常量主题切了不生效）/
# 不引入 prefers-color-scheme（会造成浅底+深轴线的不可读组合）。四条都不会自己报错 ——
# 尤其「接入点是死代码」这一条，没有门禁就静默腐烂。纯静态 + 真 import 该模块，放这里最便宜。
& node (Join-Path $repo 'scripts\lib\check-chart-theme-contract.mjs') 2>&1 | Select-Object -Last 3 | Write-Host
Record '图表主题接入点契约' ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"
# e2e 截图输出契约（S8 收尾项）—— 22 套 e2e 里有 20 套会落盘截图。曾全部写到**受版本控制**的
# frontend/docs/screenshots（9 套还写死 D:/… 绝对路径），于是每跑一次回归工作区就多出上百个
# modified / untracked PNG，"我改了代码"和"我刚跑过回归"再也分不清。
# 现在默认落点收口到 shotsDir()（scripts/e2e/.shots，gitignore）。★ 这条判据的关键是
# **同时**断言脚本侧与 .gitignore 侧 —— 只改脚本不改 .gitignore，等于把污染换个地方。
& node (Join-Path $repo 'scripts\lib\check-e2e-shots.mjs') 2>&1 | Select-Object -Last 3 | Write-Host
Record 'e2e 截图输出契约' ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"
# 云端同步暂存副本新鲜度（#343）—— 上云的不是源文件，而是 sync-staging 下的副本。
# 「源文件改了但没重新导出副本」不会有任何报错：导入照样成功，只是内容停在旧版本
# （2026-10-12 复核实测 4 份落后）。本步逐份比 SHA256，副本 != 源文件即判失败。
# ★ 暂存目录在开发者本机、CI 与干净检出都没有 —— 所以「不适用」是一种**必须报出来的状态**：
#   配了 DUODUO_SYNC_STAGING 就真跑；没配就记 [N/A] 并写明原因，绝不写成 PASS。
$stagingDir = $env:DUODUO_SYNC_STAGING
$wsDir = $env:DUODUO_WORKSPACE
if ($stagingDir -and (Test-Path -LiteralPath $stagingDir)) {
    $slog = Join-Path ([System.IO.Path]::GetTempPath()) 'duoduo-sync-staging.log'
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repo 'scripts\lib\sync-staging.ps1') `
        -Action verify -StagingDir $stagingDir -WorkspaceDir $wsDir *> $slog
    Get-Content $slog | Select-Object -Last 4 | Write-Host
    Record '云端暂存副本新鲜度' ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"
}
else {
    RecordNa '云端暂存副本新鲜度' '未设置 DUODUO_SYNC_STAGING（本机没有云端暂存目录）—— 本步不适用，未执行'
}

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
    # 2026-10-19 线上 bug 固化：商品详情链接必须用 spuId。
    # 秒杀列表曾写成 `/product/${sku.skuId}` → 404「商品不存在」，而列表里商品看着完全正常。
    & node (Join-Path $repo 'scripts\lib\check-product-link-spu.mjs') 2>&1 | Select-Object -Last 4 | Write-Host
    Record '商品详情链接按 SPU 寻址' ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"
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
    Write-Host "`n[3/5] 接口冒烟（run-all-smoke.ps1，$smokeCount 套）"
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repo 'scripts\run-all-smoke.ps1') 2>&1 |
        Select-String -Pattern '通过|失败|ALL_EXIT|汇总' | Select-Object -Last 6 | Write-Host
    Record "接口冒烟（$smokeCount 套）" ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"
} else {
    Write-Host "`n[3/5] 接口冒烟：跳过（-SkipSmoke）"
    $results += @{ name = '接口冒烟'; ok = $true; detail = '跳过' }
}

# ---------- [4/5] 浏览器 e2e ----------
if (-not $SkipE2E) {
    Write-Host "`n[4/5] 浏览器 e2e（run-ui-checks.ps1，$e2eCount 套，入口 $UiBase）"
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repo 'scripts\e2e\run-ui-checks.ps1') -UiBase $UiBase 2>&1 |
        Select-String -Pattern '=====|PASS|FAIL' | Select-Object -Last 24 | Write-Host
    Record ("浏览器 e2e（$e2eCount 套，入口 $UiBase）") ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE"
} else {
    Write-Host "`n[4/5] 浏览器 e2e：跳过（-SkipE2E）"
    $results += @{ name = '浏览器 e2e'; ok = $true; detail = '跳过' }
}

# ---------- 夹具收尾：降权本轮新增的特权账号（B-2） ----------
# 必须在 e2e **之后**：e2e 同样会造运营/商家夹具，先扫后造等于没扫。
if (Test-Path $sweepHelper) {
    Write-Host "`n[夹具收尾] 降权本轮新增的 OPERATOR / SELLER 账号"
    $privAfter = @(Get-PrivilegedIds)
    $newPriv = @($privAfter | Where-Object { $privBefore -notcontains $_ })
    Write-Host ("  跑前 {0} → 跑后 {1}，本轮新增 {2}" -f $privBefore.Count, $privAfter.Count, $newPriv.Count)
    if ($newPriv.Count -eq 0) {
        # ★ 0 个也要说出来：否则与「这段根本没跑」长得一样
        Write-Host '  本轮没有新增特权账号（已核对：两次都读到了真实清单）' -ForegroundColor Green
    } else {
        $idsFile = Join-Path $env:TEMP ("duoduo-checkall-newpriv-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.txt')
        [System.IO.File]::WriteAllLines($idsFile, $newPriv, (New-Object System.Text.UTF8Encoding $false))
        & powershell -NoProfile -ExecutionPolicy Bypass -File $sweepHelper -Apply -IdsFile $idsFile 2>&1 | Write-Host
        if ($LASTEXITCODE -ne 0) {
            Write-Host ("  [WARN] 夹具收尾未成功（exit={0}）—— 不改变本次结论，但请人工确认" -f $LASTEXITCODE) -ForegroundColor Yellow
        } else {
            Record '夹具收尾（本轮新增特权账号已降权）' $true ("降权 {0} 个" -f $newPriv.Count)
        }
    }
}

# ---------- [5/5] 汇总 ----------
$sw.Stop()
Write-Host '`n======================================================================'
Write-Host ('汇总（总耗时 ' + [int]$sw.Elapsed.TotalMinutes + ' 分 ' + $sw.Elapsed.Seconds + ' 秒）')
Write-Host '======================================================================'
$naCount = 0
foreach ($r in $results) {
    # 三态：PASS / FAIL / N/A（N/A 是"本机不适用、没跑"，既不算通过也不算失败，但要**数出来**）
    if ($null -eq $r.ok) { $tag = 'N/A '; $naCount++ }
    elseif ($r.ok) { $tag = 'PASS' }
    else { $tag = 'FAIL' }
    Write-Host ("  [{0}] {1}   {2}" -f $tag, $r.name, $r.detail)
}
if ($naCount -gt 0) {
    Write-Host ("`n  （另有 {0} 项在本机不适用，已标 N/A —— 它们没有被执行，不要当成通过）" -f $naCount) -ForegroundColor Yellow
}
if ($anyFail) {
    Write-Host "`n结论：存在失败项 —— 请先看上面各段的明细（日志在 logs\\smoke\\ 与本窗口输出）" -ForegroundColor Red
    exit 1
}
Write-Host "`n结论：全部通过 ✅（构建 / 单测 / 冒烟 / e2e）" -ForegroundColor Green
exit 0
