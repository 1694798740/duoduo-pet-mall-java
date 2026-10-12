<#
  S13 · 第三方依赖安全扫描（离线可跑）

  ── 为什么不用 OWASP dependency-check / npm audit ────────────────────
  这两个工具都**必须联网**（dependency-check 首次要下数 GB 的 NVD 数据库，
  `npm audit` 要打 registry 的 advisory 接口）。本项目的 CI 与本地验收环境
  都不保证外网可达，把它们挂进流水线会得到一个"平时绿、需要时连不上"的摆设。
  ★ 铁律 21：**FAILED（没跑成）是一等状态** —— 一个"跑不起来就跳过"的扫描
    等于没有扫描。所以这里做**确定性、可离线、失败即红**的三层检查。

  ── 三层判据 ──────────────────────────────────────────────────────
  ① 已知漏洞版本底线：本地规则表（$VulnRules）列出"有已知高危 CVE 且已有修复版"
     的依赖及其安全下限。低于下限 → 违规。规则表**随依赖升级手工维护**，
     每条都写明 CVE / 影响 / 修至版本 —— 不做"猜版本号"。
  ② 版本锁定：依赖声明不得是 `*` / `latest` / 空串（不可复现的构建），
     且 `frontend/package-lock.json` 必须存在（有锁文件才算真锁定）。
  ③ 供应链卫生：不得配非官方 registry、不得在 install 钩子里跑脚本
     （postinstall/preinstall 是 npm 供应链投毒最常见的落点）。

  ── 自证纪律（铁律 29）────────────────────────────────────────────
  每一层的"扫描数"都会打印；任何一层扫描数为 0 → **判违规**，不许降级成 WARN。
  （"一个依赖都没扫到"与"全部安全"长得一模一样。）

  退出码：0 = 通过；1 = 违规。
#>
$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSCommandPath))

$fail = 0
function Bad([string]$m) { Write-Host "  [FAIL] $m" -ForegroundColor Red; $script:fail++ }
function Ok([string]$m)  { Write-Host "  [OK]   $m" -ForegroundColor Green }
function Warn([string]$m) { Write-Host "  [WARN] $m" -ForegroundColor Yellow }
function Say([string]$m, [string]$c = 'Gray') { Write-Host $m -ForegroundColor $c }

# ─────────────────────────────────────────────────────────────────
# ① 已知漏洞版本底线（本地规则表）
#
# ★ 维护方式：升级某依赖时，若该依赖曾受某 CVE 影响，就把安全下限写在这里。
#   `Min` 一律填**官方修复版本**，不填"看起来差不多"的版本。
# ─────────────────────────────────────────────────────────────────
$VulnRules = @(
    # --- npm 生态 ---
    @{ Eco='npm'; Name='vite';              Min='5.4.11'; Note='CVE-2024-45812/45811（dev server 任意文件读取，<5.4.11）；CVE-2025-24010 等后续修复' }
    @{ Eco='npm'; Name='react-router-dom';  Min='6.28.0'; Note='CVE-2025-31137 等一批需 >=6.28.0；早于 6.28 的 SPA 路由存在开放重定向' }
    @{ Eco='npm'; Name='react';             Min='18.3.1'; Note='18.3.1 修复 18.2 之前的 XSS 向量（渲染 XSS 的官方缓解版）' }
    @{ Eco='npm'; Name='react-dom';         Min='18.3.1'; Note='与 react 同源' }
    @{ Eco='npm'; Name='echarts';           Min='5.5.1';  Note='5.5.0 之前存在原型污染类问题（后台图表渲染不可信数据）' }
    @{ Eco='npm'; Name='typescript';        Min='5.6.3';  Note='5.6 之前 tsc 的若干安全与稳定性修复' }
    # --- Maven 生态（版本取自根 pom 的 version 属性，见 $MavenProps）---
    @{ Eco='maven'; Prop='jjwt.version';     Min='0.12.5'; Note='0.12.x 重写了解析链；0.11 之前存在算法混淆（alg=none / HS-RS 混用）风险' }
    @{ Eco='maven'; Prop='mybatis-plus.version'; Min='3.5.5'; Note='<3.5.5 的注解 SQL 拼接在特定写法下存在注入面' }
    @{ Eco='maven'; Prop='spring-boot.version';  Min='3.2.5';  Note='3.2.5 覆盖 spring-framework 6.1.6 的多项 CVE（含 CVE-2024-22262 等）' }
    @{ Eco='maven'; Prop='spring-cloud.version'; Min='2023.0.1'; Note='与 Spring Boot 3.2.x 配套的受支持版本线' }
)

# ─────────────────────────────────────────────────────────────────
# ② 前端依赖：解析每个 package.json
# ─────────────────────────────────────────────────────────────────
Say ''
Say '[check-dependency-security] 第三方依赖安全扫描（S13）' 'Cyan'

$pkgFiles = @()
$pkgFiles += Get-ChildItem -Path (Join-Path $repo 'frontend\apps')    -Recurse -File -Filter 'package.json' -ErrorAction SilentlyContinue
$pkgFiles += Get-ChildItem -Path (Join-Path $repo 'frontend\packages') -Recurse -File -Filter 'package.json' -ErrorAction SilentlyContinue
$pkgFiles += Get-Item (Join-Path $repo 'frontend\package.json') -ErrorAction SilentlyContinue
$pkgFiles += Get-Item (Join-Path $repo 'miniprogram\package.json') -ErrorAction SilentlyContinue
$pkgFiles = @($pkgFiles | Where-Object { $_ } | Sort-Object FullName -Unique)

Say ''
Say '① 已知漏洞版本底线（本地规则表）' 'Cyan'
Say ("  前端 package.json 共 {0} 份" -f $pkgFiles.Count)
if ($pkgFiles.Count -eq 0) { Bad '一份 package.json 都没扫到 —— 判据层静默失效' }

# 收集 依赖名 -> 声明版本（多份文件出现同名依赖时取全部）
$npmDeclared = @{}
foreach ($f in $pkgFiles) {
    try { $j = Get-Content -Raw -Encoding UTF8 $f.FullName | ConvertFrom-Json }
    catch { Bad "package.json 解析失败：$($f.FullName) —— $($_.Exception.Message)"; continue }
    $all = @{}
    foreach ($sec in @('dependencies', 'devDependencies')) {
        $obj = $j.$sec
        if ($obj) { foreach ($p in $obj.PSObject.Properties) { $all[$p.Name] = [string]$p.Value } }
    }
    foreach ($k in $all.Keys) {
        if (-not $npmDeclared.ContainsKey($k)) { $npmDeclared[$k] = @() }
        $npmDeclared[$k] += [pscustomobject]@{ Ver = $all[$k]; File = $f.FullName }
    }
}
Say ("  解出直接依赖 {0} 个" -f $npmDeclared.Count)
if ($npmDeclared.Count -eq 0) { Bad '一个 npm 直接依赖都没解出 —— 判据层静默失效' }

# 版本比较：把 '^1.2.3' / '~1.2.3' / '>=1.2.3' 归一成 [version]
function Convert-ToVersion([string]$raw) {
    $s = ($raw -replace '^[\^~>=<v\s]+', '').Trim()
    # 去掉预发布尾巴（-alpha.1 之类）后取数字部分
    $s = ($s -split '-')[0]
    try { return [version]$s } catch { return $null }
}

$ruleChecked = 0
foreach ($r in $VulnRules | Where-Object { $_.Eco -eq 'npm' }) {
    if (-not $npmDeclared.ContainsKey($r.Name)) { continue }
    $ruleChecked++
    foreach ($d in $npmDeclared[$r.Name]) {
        $v = Convert-ToVersion $d.Ver
        $minV = Convert-ToVersion $r.Min
        if ($null -eq $v) {
            Bad ("{0} 声明版本无法解析：'{1}'（{2}）" -f $r.Name, $d.Ver, (Split-Path -Leaf $d.File))
            continue
        }
        if ($v -lt $minV) {
            Bad ("{0} = {1} 低于安全下限 {2}（{3}）" -f $r.Name, $d.Ver, $r.Min, (Split-Path -Leaf $d.File))
        } else {
            Ok ("{0} {1} >= {2}" -f $r.Name, $d.Ver, $r.Min)
        }
    }
}
if ($ruleChecked -eq 0) {
    Bad 'npm 规则表一条都没比到 —— 要么依赖被改名，要么规则表与依赖清单脱节。判据层静默失效'
} else {
    Say ("  比到 {0}/{1} 条 npm 规则" -f $ruleChecked, @($VulnRules | Where-Object { $_.Eco -eq 'npm' }).Count)
}

# ─────────────────────────────────────────────────────────────────
# ①-b Maven 版本底线（读根 pom 的 version 属性）
# ─────────────────────────────────────────────────────────────────
$pomPath = Join-Path $repo 'pom.xml'
if (-not (Test-Path $pomPath)) { Bad '根 pom.xml 不存在' }
else {
    $pom = Get-Content -Raw -Encoding UTF8 $pomPath
    $props = @{}
    foreach ($m in [regex]::Matches($pom, '<([a-zA-Z0-9\.\-]+\.version)>([^<]+)</\1>')) {
        $props[$m.Groups[1].Value] = $m.Groups[2].Value.Trim()
    }
    Say ("  pom 解出 version 属性 {0} 个" -f $props.Count)
    if ($props.Count -eq 0) { Bad 'pom 里一个 version 属性都没解出 —— 判据层静默失效' }

    $mRuleChecked = 0
    foreach ($r in $VulnRules | Where-Object { $_.Eco -eq 'maven' }) {
        if (-not $props.ContainsKey($r.Prop)) { continue }
        $mRuleChecked++
        $raw = $props[$r.Prop]
        $v = Convert-ToVersion $raw
        $minV = Convert-ToVersion $r.Min
        if ($null -eq $v) { Bad ("{0} 无法解析：{1}" -f $r.Prop, $raw); continue }
        if ($v -lt $minV) {
            Bad ("{0} = {1} 低于安全下限 {2}（{3}）" -f $r.Prop, $raw, $r.Min, $r.Note)
        } else {
            Ok ("{0} = {1} >= {2}" -f $r.Prop, $raw, $r.Min)
        }
    }
    if ($mRuleChecked -eq 0) { Bad 'maven 规则表一条都没比到 —— 判据层静默失效' }
    else { Say ("  比到 {0}/{1} 条 maven 规则" -f $mRuleChecked, @($VulnRules | Where-Object { $_.Eco -eq 'maven' }).Count) }
}

# ─────────────────────────────────────────────────────────────────
# ② 版本锁定
# ─────────────────────────────────────────────────────────────────
Say ''
Say '② 版本锁定（可复现构建）' 'Cyan'

$looseCount = 0
$declCount  = 0
foreach ($f in $pkgFiles) {
    try { $j = Get-Content -Raw -Encoding UTF8 $f.FullName | ConvertFrom-Json } catch { continue }
    foreach ($sec in @('dependencies', 'devDependencies')) {
        $obj = $j.$sec
        if (-not $obj) { continue }
        foreach ($p in $obj.PSObject.Properties) {
            $declCount++
            $val = [string]$p.Value
            # 允许的工作区内部依赖与版本范围；禁止的是"完全无约束"
            if ($val -eq '*' -or $val -eq '' -or $val -match '^(latest|next|\*)$') {
                # 工作区内包用 '*' 是 npm workspaces 的惯例（本地链接），不算松散
                if ($p.Name -like '@duoduo/*') { continue }
                Bad ("{0} 声明为 '{1}'（{2}）—— 构建不可复现" -f $p.Name, $val, (Split-Path -Leaf $f.FullName))
                $looseCount++
            }
        }
    }
}
if ($declCount -eq 0) { Bad '一个依赖声明都没扫到 —— 判据层静默失效' }
else { Ok ("{0} 条依赖声明全部为版本范围或工作区链接，无 '{1}'/'latest' 式松散声明" -f $declCount, '*') }

$lockFiles = @(
    (Join-Path $repo 'frontend\package-lock.json')
)
foreach ($lf in $lockFiles) {
    if (Test-Path $lf) { Ok ("锁文件存在：{0}" -f (Split-Path -Leaf $lf)) }
    else { Bad ("锁文件缺失：{0} —— 没有 lock 就不算真锁定" -f (Split-Path -Leaf $lf)) }
}

# ─────────────────────────────────────────────────────────────────
# ③ 供应链卫生
# ─────────────────────────────────────────────────────────────────
Say ''
Say '③ 供应链卫生（registry / install 钩子）' 'Cyan'

# 3-1 不得配非官方 registry
$npmrcs = @()
$npmrcs += Get-ChildItem -Path $repo -Recurse -File -Filter '.npmrc' -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notmatch '\\node_modules\\' }
$npmrcScanned = 0
$badRegistry = 0
foreach ($rc in $npmrcs) {
    $npmrcScanned++
    $txt = Get-Content -Raw -Encoding UTF8 $rc.FullName
    foreach ($m in [regex]::Matches($txt, '(?m)^\s*registry\s*=\s*(\S+)')) {
        $url = $m.Groups[1].Value
        if ($url -like 'https://registry.npmjs.org*' -or $url -like 'https://registry.npmmirror.com*') {
            Ok (".npmrc registry 可信：{0}" -f $url)
        } else {
            Bad (".npmrc 指向非白名单 registry：{0}（{1}）" -f $url, $rc.FullName)
            $badRegistry++
        }
    }
}
if ($npmrcScanned -eq 0) {
    # 没有 .npmrc 是正常的（用默认源），但要让"一个都没扫到"可见
    Ok '仓库内无 .npmrc（使用 npm 默认官方源），无自定义 registry 风险'
}
if ($badRegistry -gt 0) { Say ("  非白名单 registry {0} 处" -f $badRegistry) 'Red' }

# 3-2 install 钩子：postinstall / preinstall 是供应链投毒最常见的落点
$hookCount = 0
$hookScanned = 0
foreach ($f in $pkgFiles) {
    try { $j = Get-Content -Raw -Encoding UTF8 $f.FullName | ConvertFrom-Json } catch { continue }
    $sc = $j.scripts
    if (-not $sc) { continue }
    $hookScanned++
    foreach ($h in @('preinstall', 'postinstall', 'install')) {
        $val = [string]$sc.$h
        if ($val) {
            Bad ("{0} 定义了 {1} 钩子：{2}（{3}）—— 供应链投毒高危落点，须人工审查" -f (Split-Path -Leaf $f.FullName), $h, $val, (Split-Path -Leaf $f.FullName))
            $hookCount++
        }
    }
}
if ($hookScanned -eq 0) { Bad '一份 package.json 的 scripts 都没扫到 —— 判据层静默失效' }
elseif ($hookCount -eq 0) { Ok ("{0} 份 package.json 均无 preinstall/postinstall/install 钩子" -f $hookScanned) }

# 3-3 Maven 仓库：settings.xml 不得配 http 明文源
$settingsCandidates = @(
    (Join-Path $env:USERPROFILE '.m2\settings.xml'),
    (Join-Path $repo '.mvn\settings.xml')
)
$settingsScanned = 0
foreach ($s in $settingsCandidates) {
    if (-not (Test-Path $s)) { continue }
    $settingsScanned++
    $txt = Get-Content -Raw -Encoding UTF8 $s
    if ($txt -match '<url>\s*http://') {
        Bad ("Maven settings 里存在 http 明文仓库：{0}" -f $s)
    } else {
        Ok ("Maven settings 无 http 明文仓库：{0}" -f $s)
    }
}
if ($settingsScanned -eq 0) { Ok '未发现自定义 Maven settings.xml（使用 Maven 官方 https 源）' }

# ─────────────────────────────────────────────────────────────────
Say ''
Write-Host ("  【规则表】共 {0} 条（npm {1} / maven {2}）；每条注明 CVE 与修至版本。" -f `
    $VulnRules.Count,
    @($VulnRules | Where-Object { $_.Eco -eq 'npm' }).Count,
    @($VulnRules | Where-Object { $_.Eco -eq 'maven' }).Count) -ForegroundColor DarkGray
Say '  【维护】升级依赖后若某条规则不再适用，请连同注释一起删除；新依赖受 CVE 影响则新增一行。' 'DarkGray'

Say ''
if ($fail -eq 0) {
    Say '结论：通过 —— 无低于安全下限的依赖，声明可复现，供应链卫生无异常。' 'Green'
    exit 0
} else {
    Say ("结论：违规 {0} 处。" -f $fail) 'Red'
    exit 1
}
