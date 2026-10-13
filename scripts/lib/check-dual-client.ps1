<#
  守卫：双端功能对照表一致性（S6）

  守着 docs/S6-DUAL-CLIENT.md，做三件事：

    ① 文档里引用的每个页面路径必须真实存在
       - `miniprogram/pages/<x>/<x>`（小程序页面目录）
       - `frontend/apps/mall/src/pages/<X>.tsx`（网页页面组件）
       - `/pages/<x>/<x>` 形式的小程序路由（出现在 §1 表格里）

    ② tabBar 一致性：文档 §1.1 标为「（tab）」的小程序页，集合必须与
       miniprogram/app.json 的 tabBar.list.pagePath 集合**相等**（双向）。

    ③ 缺口清单自证：§3 里 D1/D2 标注的「对端确实没有」必须成立 ——
       D1 断言网页端 grep UserProfile 命中 0；D2 断言小程序 grep pages/pets 命中 0。
       一旦有人补齐，守卫报红，提醒把该 D 项从「缺口」改为「已完成」。

  ★ 自证纪律（铁律 29）：任何一层「一个都没扫到」都判**违规**，不许降级成 WARN。
    —— 扫描数为 0 时守卫若通过，等于文档改名后守卫静默失效。

  退出码：0 = 通过；1 = 违规。
#>
$ErrorActionPreference = 'Stop'

# 本文件位于 <repo>/scripts/lib/ 下 —— 需要往上**三级**才是仓库根
$repo = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSCommandPath))
$docPath = Join-Path $repo 'docs\S6-DUAL-CLIENT.md'
$appJson = Join-Path $repo 'miniprogram\app.json'
$mallSrc = Join-Path $repo 'frontend\apps\mall\src'

$fail = 0
function Bad([string]$m) { Write-Host "  [FAIL] $m" -ForegroundColor Red; $script:fail++ }
function Ok([string]$m)  { Write-Host "  [OK]   $m" -ForegroundColor Green }
function Say([string]$m, [string]$c = 'Gray') { Write-Host $m -ForegroundColor $c }

Say ''
Say '[check-dual-client] 双端功能对照表一致性守卫' 'Cyan'

if (-not (Test-Path $docPath)) { Bad "对照表不存在：$docPath"; exit 1 }
if (-not (Test-Path $appJson)) { Bad "app.json 不存在：$appJson"; exit 1 }
if (-not (Test-Path $mallSrc)) { Bad "网页商城源码不存在：$mallSrc"; exit 1 }

$doc = Get-Content -Raw -Encoding UTF8 $docPath
Say ("  文档：{0} 字节" -f (Get-Item $docPath).Length)

# ─────────────────────────────────────────────────────────────
# ① 页面路径存在性
# ─────────────────────────────────────────────────────────────
Say ''
Say '① 文档引用的页面路径存在性' 'Cyan'

# §3 缺口清单里**尚未创建**的页面 —— 文档里出现属正常，不能判违规。
# 一旦真被创建，③ 的自证断言会转而提醒把缺口项改为「已完成」。
# ★ 2026-10-13：D3 已补（新增 pages/search/search，首页与分类页都 navigateTo 过去，
#   刻意不占 tabBar —— tabBar 已满 5 项）。这里同步移除 'search'。
# ★ 2026-10-13：D1 已补（新增 mall 的 pages/UserProfile.tsx + 路由 /user/:id，
#   关注/粉丝列表、闲置详情、分享圈三处入口都接上了）。这里同步移除 'UserProfile'。
$expectedMissingMp  = @()                 # D3 已于 2026-10-13 补齐
$expectedMissingWeb = @()                 # D1 已于 2026-10-13 补齐

# 网页端不在 pages/ 下的已知组件（Layout / App 是入口与外壳，不是页面）
$nonPageWeb = @('App', 'Layout', 'main')

# ---- 小程序：反引号包起来的 miniprogram/pages/x/x 或 /pages/x/x ----
$mpRefs = [System.Collections.Generic.HashSet[string]]::new()
foreach ($m in [regex]::Matches($doc, '`(?:miniprogram/)?pages/([a-z0-9\-]+)/\1(?:[`\?]|$)')) {
    [void]$mpRefs.Add($m.Groups[1].Value)
}
# 另收 §5 那种不带反引号的 `miniprogram/pages/<x>/<x>` 描述
foreach ($m in [regex]::Matches($doc, 'miniprogram/pages/([a-z0-9\-]+)/\1')) {
    [void]$mpRefs.Add($m.Groups[1].Value)
}

$mpScanned = 0
foreach ($name in ($mpRefs | Sort-Object)) {
    $mpScanned++
    $dir = Join-Path $repo "miniprogram\pages\$name"
    if (-not (Test-Path $dir)) {
        if ($name -in $expectedMissingMp) {
            Ok "miniprogram/pages/$name/ 尚未创建（§3 缺口项，符合预期）"
        } else {
            Bad "小程序页面不存在：miniprogram/pages/$name/"
        }
    } else {
        # 目录存在还不够：页面四件套至少要有 .js（微信框架约定）
        if (-not (Test-Path (Join-Path $dir "$name.js"))) {
            Bad "小程序页面缺少 $name.js：miniprogram/pages/$name/"
        } else {
            Ok "miniprogram/pages/$name/$name 存在"
        }
    }
}
if ($mpScanned -eq 0) { Bad '文档里一个小程序页面路径都没扫到 —— 判据层静默失效' }

# ---- 网页端：frontend/apps/mall/src/pages/X.tsx ----
$webRefs = [System.Collections.Generic.HashSet[string]]::new()
foreach ($m in [regex]::Matches($doc, '`([A-Z][A-Za-z0-9]*)\.tsx`')) {
    [void]$webRefs.Add($m.Groups[1].Value)
}
# 另收 §3 里 `pages/UserProfile.tsx` 这种写法
foreach ($m in [regex]::Matches($doc, 'pages/([A-Z][A-Za-z0-9]*)\.tsx')) {
    [void]$webRefs.Add($m.Groups[1].Value)
}

$webScanned = 0
foreach ($name in ($webRefs | Sort-Object)) {
    $webScanned++
    if ($name -in $nonPageWeb) {
        # 入口/外壳组件：src/ 或 src/components/ 下存在即可
        $cands = @(
            (Join-Path $mallSrc "$name.tsx"),
            (Join-Path $mallSrc "components\$name.tsx")
        )
        $hit = $cands | Where-Object { Test-Path $_ } | Select-Object -First 1
        if ($hit) {
            Ok ("frontend/apps/mall/src/{0} 存在（入口/外壳，非 pages/）" -f (Split-Path -Leaf $hit))
        } else {
            Bad "网页入口组件不存在：src/$name.tsx 或 src/components/$name.tsx"
        }
        continue
    }
    $f = Join-Path $mallSrc "pages\$name.tsx"
    if (-not (Test-Path $f)) {
        if ($name -in $expectedMissingWeb) {
            Ok "pages/$name.tsx 尚未创建（§3 缺口项，符合预期）"
        } else {
            Bad "网页页面组件不存在：frontend/apps/mall/src/pages/$name.tsx"
        }
    } else {
        Ok "frontend/apps/mall/src/pages/$name.tsx 存在"
    }
}
if ($webScanned -eq 0) { Bad '文档里一个网页页面组件都没扫到 —— 判据层静默失效' }

# ─────────────────────────────────────────────────────────────
# ② tabBar 集合双向相等
# ─────────────────────────────────────────────────────────────
Say ''
Say '② 小程序 tabBar 集合一致性' 'Cyan'

$app = Get-Content -Raw -Encoding UTF8 $appJson | ConvertFrom-Json
$realTabs = @($app.tabBar.list | ForEach-Object { $_.pagePath })

# 文档里标了 tab 的行：括号内可能带 Markdown 加粗标记，如「（**tab**）」「（tab）」
# 取该行第一个反引号内的 pages/x/x 或 /pages/x/x
$docTabs = @()
foreach ($line in ($doc -split "`r?`n")) {
    if ($line -match '（\s*\*{0,2}tab\*{0,2}\s*）' -or $line -match '\(\s*\*{0,2}tab\*{0,2}\s*\)') {
        $m = [regex]::Match($line, '`(?:miniprogram/)?(pages/[a-z0-9\-]+/[a-z0-9\-]+)')
        if ($m.Success) { $docTabs += $m.Groups[1].Value }
    }
}
$docTabs = @($docTabs | Sort-Object -Unique)
$realTabs = @($realTabs | Sort-Object -Unique)

Say ("  文档标注 tab 页 {0} 个；app.json 实际 {1} 个" -f $docTabs.Count, $realTabs.Count)

if ($docTabs.Count -eq 0) { Bad '文档里一个 tab 页都没标到 —— 判据层静默失效' }
elseif ($realTabs.Count -eq 0) { Bad 'app.json 解析出 0 个 tab 页 —— 判据层静默失效' }
else {
    $missing = @($realTabs | Where-Object { $_ -notin $docTabs })
    $extra   = @($docTabs | Where-Object { $_ -notin $realTabs })
    if ($missing.Count -eq 0 -and $extra.Count -eq 0) {
        Ok ("tabBar 双向一致（{0} 项）：{1}" -f $realTabs.Count, ($realTabs -join ', '))
    } else {
        foreach ($x in $missing) { Bad "app.json 有但文档没标 tab：$x" }
        foreach ($x in $extra)   { Bad "文档标了 tab 但 app.json 没有：$x" }
    }
}

# ─────────────────────────────────────────────────────────────
# ③ 缺口清单自证（D1 / D2）
# ─────────────────────────────────────────────────────────────
Say ''
Say '③ 缺口清单自证（§3 D1/D2 必须仍然成立）' 'Cyan'

# ★ 不要用 grep：这个脚本在 CI 与容器里跑，`grep` 不保证存在（实测 PowerShell 下
#   `& grep` 报 CommandNotFoundException，且 $ErrorActionPreference='Stop' 时直接炸）。
#   改用 Select-String —— 它是 PowerShell 内建，任何宿主都有。
#
# ★★ 也不要用 `Get-ChildItem -Path <dir> -Recurse -Include '*.tsx'`：PS 5.1 下
#    `-Include` 配合裸目录路径时**可能静默返回空**（-Include 语义要求路径含通配或
#    与 -Recurse 组合时行为因版本而异）。实测本脚本第一版就踩了这个坑：
#    D1 断言恒返回 0，无论文件存在与否 —— 又是"判据层静默失效"。
#    改用 `-File -Recurse` + 手工按扩展名过滤，行为确定。
function Count-Hits([string]$pattern, [string]$root, [string[]]$exts) {
    if (-not (Test-Path $root)) { return -1 }   # -1 = 根不存在，调用侧当失败处理
    $files = @(Get-ChildItem -Path $root -Recurse -File -ErrorAction SilentlyContinue |
        Where-Object { $exts -contains $_.Extension.ToLower() })
    if ($files.Count -eq 0) { return -2 }        # -2 = 一个候选文件都没有，同样当失败
    return @($files | Select-String -Pattern $pattern -ErrorAction SilentlyContinue).Count
}

# ── 缺口状态从**文档**读，而不是写死在脚本里 ────────────────────────────────
# ★ 2026-10-13 修掉一个设计缺陷：原实现**无条件**断言"缺口仍在"（grep 源码必须命中 0）。
#   于是 D1/D3 一旦真被补齐，这一层就**永远不可能转绿** ——
#   一个"修好了反而过不了"的守卫，结局只能是被整体注释掉（那就连缺口还在时的那道防线也没了）。
#   现在改成**双向**：文档说「待补」⇒ 断言代码里没有；文档说「已完成」⇒ 断言代码里有。
#   两个方向都在钉事实，谁对不上都报红并点名。
function Get-GapStatus([string]$doc, [string]$code) {
    # ★ 单引号里的 \b 与 $ 在 PowerShell 里都是字面量，原样交给 .NET 正则
    $pat = '(?m)^### ' + [regex]::Escape($code) + '\b.*$'
    $m = [regex]::Match($doc, $pat)
    if (-not $m.Success) { return 'UNKNOWN' }
    $line = $m.Value
    if ($line -match '已完成|已补齐|已补\b|✅') { return 'DONE' }
    if ($line -match '待补|缺失|缺口') { return 'OPEN' }
    return 'UNKNOWN'
}

# D1：网页端个人主页
$st1 = Get-GapStatus $doc 'D1'
$n1 = Count-Hits 'UserProfile|user-profile' $mallSrc @('.tsx', '.ts', '.css')
if ($n1 -lt 0) {
    Bad ("D1 无法判定：候选文件扫描失败（{0}）—— 判据层静默失效" -f $(if ($n1 -eq -1) { '源码根不存在' } else { '一个候选文件都没扫到' }))
} elseif ($st1 -eq 'DONE') {
    if ($n1 -gt 0) { Ok ("D1 文档标为已完成，代码里确实有（命中 {0} 处）" -f $n1) }
    else { Bad 'D1 文档标为「已完成」，但网页端源码里一处 UserProfile 都没有 —— 文档在说谎' }
} elseif ($st1 -eq 'OPEN') {
    if ($n1 -eq 0) { Ok 'D1 成立：文档标为待补，网页端确实没有个人主页（命中 0）' }
    else { Bad "D1 已不成立：网页端出现 $n1 处 UserProfile/user-profile 引用 -> 请把 §3-D1 从缺口清单改为「已完成」" }
} else {
    Bad ("D1 状态无法从文档判定（标题行没写 待补/已完成）：" + $(if ($st1 -eq 'UNKNOWN') { '未匹配到 ### D1 标题' } else { $st1 }))
}

# D2：小程序宠物档案页
$st2 = Get-GapStatus $doc 'D2'
$n2 = Count-Hits 'pages/pets' (Join-Path $repo 'miniprogram') @('.js', '.json', '.wxml')
if ($n2 -lt 0) {
    Bad ("D2 无法判定：候选文件扫描失败（{0}）—— 判据层静默失效" -f $(if ($n2 -eq -1) { 'miniprogram 不存在' } else { '一个候选文件都没扫到' }))
} elseif ($st2 -eq 'DONE') {
    if ($n2 -gt 0) { Ok ("D2 文档标为已完成，代码里确实有（命中 {0} 处）" -f $n2) }
    else { Bad 'D2 文档标为「已完成」，但小程序源码里一处 pages/pets 都没有 —— 文档在说谎' }
} elseif ($st2 -eq 'OPEN') {
    if ($n2 -eq 0) { Ok 'D2 成立：文档标为待补/有意不做，小程序确实没有宠物档案页（命中 0）' }
    else { Bad "D2 已不成立：小程序出现 $n2 处 pages/pets 引用 -> 请更新 §3-D2 的状态标注" }
} else {
    Bad 'D2 状态无法从文档判定（标题行没写 待补/已完成）'
}

# ─────────────────────────────────────────────────────────────
Say ''
if ($fail -eq 0) {
    Say '结论：通过 —— 对照表引用的页面全部存在，tabBar 双向一致，缺口清单仍成立。' 'Green'
    exit 0
} else {
    Say ("结论：违规 {0} 处。" -f $fail) 'Red'
    exit 1
}
