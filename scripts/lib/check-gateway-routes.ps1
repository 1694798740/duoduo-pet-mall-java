# 网关路由覆盖静态自检（S3 起固化；Windows PowerShell 5.1 / pwsh 7 均须可跑）
#
# 用法： pwsh -NoProfile -File scripts/lib/check-gateway-routes.ps1
#        退出码 0 = 全部覆盖；1 = 有漏（漏掉的路径打印在 stdout）
#
# ---------- 为什么需要它 ----------
# 网关的路由**不是** `/api/**` 兜底，而是按具体子路径一条条列举的
# （duoduo-gateway/application.yml 里 product / trade / fulfilment 三条路由都是
#   `Path=/api/xxx/**,/api/yyy/**,…` 这种形式）。
#
# 于是"给服务加了一组新接口、忘了在网关加一段路径"的**唯一表现就是 404**：
#   不报错、不告警、启动日志一片正常。
# 而 404 恰好和另外两件事长得一模一样：
#   ① 服务没起来 / 没注册；
#   ② 接口根本没写。
# 排查的人会先怀疑后两者，反复重建镜像和翻代码，最后才发现是网关没放行。
#
# 本项目已经栽过 5 次（application.yml 里留了 4 处"漏了只会 404"的注释，S3 是第 5 次）：
#   A11 /api/admin/mq/**、E14 /api/admin/products/**、E18 /api/admin/audit-logs、
#   A3 /api/admin/aftersales/**、S3 /api/admin/recon/**
# S3 那次尤其典型：控制器编译进了镜像（`unzip -l` 能看到 class 文件）、
# 服务直连端口也活着（匿名请求返回 401），只有经网关的 27 条断言**全部** 404。
#
# 人的记性靠不住，注释也拦不住下一次。所以改成静态检查：**每个 /api/ 基路径
# 都必须在网关里有能覆盖它的 Path 模式**，没覆盖就是构建失败。
#
# ---------- 这条规则刻意不做的事 ----------
# 1. 不做反向检查（"网关里的每条模式都得有对应控制器"）。
#    网关模式里大量是**叶子路径**（`/api/shares/topics/*`、`/uploads/**` 等），
#    它们对应的不是控制器基路径，反向一查必然一片红 —— 挡多了就会被绕过。
# 2. 不校验白名单（免登路径）。"这条路径该不该免登"是评审的事，
#    静态规则只挡**确定性错误**：路径有没有被路由。
# 3. 不校验方法（GET/POST）。网关 Path 匹配不分方法，方法层的判权在服务端。
#
# ---------- 自证要求 ----------
# 与 S3 对账同一条纪律：**扫描数为 0 不算通过**。
# "一个控制器都没扫到"和"全部覆盖了"在报表上长得一样，所以这里显式断言扫描数 > 0。

param(
    [string]$RepoRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
)

$ErrorActionPreference = 'Continue'
$violations = 0

$gatewayYml = Join-Path $RepoRoot 'duoduo-gateway\src\main\resources\application.yml'

Write-Host ""
Write-Host "=== 网关路由覆盖自检 @ $RepoRoot ==="

if (-not (Test-Path $gatewayYml)) {
    Write-Host "  [FAIL] 找不到网关配置：$gatewayYml"
    Write-Host "=== 路由覆盖自检结果：1 项违规 ==="
    exit 1
}

# ---------- 1. 收集网关的 Path 模式 ----------
# 形式： `- Path=/a/**,/b/**`（predicates 下的一行）
$patterns = New-Object System.Collections.Generic.List[string]
foreach ($line in [System.IO.File]::ReadAllLines($gatewayYml)) {
    $m = [regex]::Match($line, '^\s*-\s*Path\s*=\s*(.+?)\s*$')
    if (-not $m.Success) { continue }
    foreach ($p in $m.Groups[1].Value.Split(',')) {
        $t = $p.Trim()
        if ($t) { $patterns.Add($t) }
    }
}

Write-Host "网关 Path 模式 $($patterns.Count) 条"

# ---------- 2. 收集服务端声明的"实际路径" ----------
# 判据是 **类级基路径 + 方法级路径** 拼出来的完整路径，不是类级基路径本身。
# 为什么：本项目的类级基路径有两种风格 ——
#   ① 资源式（`/api/products`、`/api/admin/orders`）—— 类级基路径就等于资源根；
#   ② 命名空间式（`/api/admin`，子资源在方法上：/categories、/brands）——
#      此时网关列举的是**叶子路径**（`/api/admin/categories/**`），
#      拿类级基路径去比对必然误报（`/api/admin` 本来就没人会单列）。
# 拼成完整路径后，上面两种风格都能对上，而且能抓到**方法级漏配**这种更细的漏（见文末自证）。
#
# 类级注解必然出现在类型声明关键字**之前**，所以先按 `class X` 切一刀，切成 head / body。
# 方法级路径一律从 body 里取；`@GetMapping` 不带参数时路径就是基路径本身。
$controllerFiles = @(Get-ChildItem -Path $RepoRoot -Filter '*Controller.java' -File -Recurse -ErrorAction SilentlyContinue |
        Where-Object {
            $_.FullName -notmatch '[\\/](target|node_modules)[\\/]' -and
            $_.FullName -match '[\\/]src[\\/]main[\\/]java[\\/]'
        })

$effective = New-Object System.Collections.Generic.List[object]
foreach ($f in $controllerFiles) {
    $text = [System.IO.File]::ReadAllText($f.FullName)

    $classMatch = [regex]::Match($text, '(?m)^\s*(?:public\s+)?(?:final\s+|abstract\s+)*(?:class|interface|enum)\s+\w+')
    $head = if ($classMatch.Success) { $text.Substring(0, $classMatch.Index) } else { $text }
    $body = if ($classMatch.Success) { $text.Substring($classMatch.Index) } else { $text }

    $classBases = @([regex]::Matches($head, '@RequestMapping\s*\(\s*(?:value\s*=\s*)?["''](/api/[^"'']*)["'']') |
            ForEach-Object { $_.Groups[1].Value })

    # 方法级映射：可能带括号（含路径，可能是数组），也可能不带括号（路径 = 基路径）
    $methodPaths = New-Object System.Collections.Generic.List[string]
    foreach ($mm in [regex]::Matches($body, '@(?:Get|Post|Put|Patch|Delete|Request)Mapping\s*(\([^)]*\))?')) {
        if (-not $mm.Groups[1].Success) {
            $methodPaths.Add('')
            continue
        }
        $quoted = @([regex]::Matches($mm.Groups[1].Value, '["'']([^"'']*)["'']') | ForEach-Object { $_.Groups[1].Value })
        if ($quoted.Count -eq 0) {
            # 形如 @GetMapping(value = SOME_CONSTANT)：路径由常量决定，静态看不出来。
            # 记为 ''（= 基路径），宁可保守也不要静默跳过 —— 跳过的是一条真路径。
            $methodPaths.Add('')
            continue
        }
        foreach ($q in $quoted) { $methodPaths.Add($q) }
    }

    if ($classBases.Count -eq 0) {
        # 没有类级基路径：方法级必须写全（以 /api/ 开头），否则本检查管不着（例如 /internal/…）
        foreach ($mp in $methodPaths) {
            if ($mp -like '/api/*') {
                $effective.Add([pscustomobject]@{ Path = $mp.TrimEnd('/'); File = $f.Name })
            }
        }
        continue
    }

    if ($methodPaths.Count -eq 0) {
        # 只有类级注解、方法级一个都没扫到：退回用基路径本身兜底，避免"扫到 0 条 = 跳过"
        foreach ($b in $classBases) {
            $effective.Add([pscustomobject]@{ Path = $b.TrimEnd('/'); File = $f.Name })
        }
        continue
    }

    foreach ($b in $classBases) {
        foreach ($mp in $methodPaths) {
            if ($mp -like '/api/*') {
                # 方法级写成了绝对路径，以它为准
                $effective.Add([pscustomobject]@{ Path = $mp.TrimEnd('/'); File = $f.Name })
                continue
            }
            $full = ($b.TrimEnd('/') + '/' + $mp.TrimStart('/')).TrimEnd('/')
            if (-not $full) { $full = '/' }
            $effective.Add([pscustomobject]@{ Path = $full; File = $f.Name })
        }
    }
}

# 去重（同一路径可能来自多个文件 / 多个方法重载）
$distinct = $effective | Sort-Object -Property Path -Unique
Write-Host "服务端声明 /api/ 实际路径 $($distinct.Count) 条（来自 $($controllerFiles.Count) 个 *Controller.java）"

# ★ 扫描数为 0 不算通过
if ($controllerFiles.Count -eq 0 -or $distinct.Count -eq 0) {
    Write-Host "  [FAIL] 一条控制器路径都没扫到 —— 这几乎一定是过滤条件写错了（路径分隔符 / 目录层级），"
    Write-Host "         不是'项目里没有接口'。扫描数为 0 与'全部覆盖'在报表上长得一样，故按违规处理。"
    Write-Host "=== 路由覆盖自检结果：1 项违规 ==="
    exit 1
}

# ---------- 3. 覆盖判定 ----------
# 模式 P 覆盖路径 X，当且仅当：
#   P == X                     （精确）
#   或 P 以 /** 结尾，且 X 等于 P 去掉 /** 之后的前缀、或以该前缀开头
#      —— 注意 `/api/admin/recon/**` 覆盖 `/api/admin/recon` 本身
#         （Spring 的 PathPattern / AntPathMatcher 对 `/x/**` 都匹配 `/x`）。
#      —— 而 `/api/admin/categories/**` 覆盖 `/api/admin/categories/{id}`，
#         因为 `{id}` 在网关这一层只是个普通路径段。
function Test-Covered {
    param([string]$Path, [string[]]$Patterns)
    foreach ($p in $Patterns) {
        if ($p -eq $Path) { return $true }
        if ($p.EndsWith('/**')) {
            $prefix = $p.Substring(0, $p.Length - 3).TrimEnd('/')
            if ($Path -eq $prefix) { return $true }
            if ($Path.StartsWith($prefix + '/')) { return $true }
        }
    }
    return $false
}

# 有意不进网关的路径：**必须写明理由**，否则就是拿豁免掩盖漏配。
# 目前为空 —— 本项目所有 `/api/` 控制器都应经网关，
# 真正的内部接口用的是 `/internal/**` 前缀（不以 /api/ 开头，故本检查天然跳过）。
$intentionallyUnrouted = @{}

Write-Host ""
Write-Host "> 逐条核对：服务端声明的每条 /api/ 实际路径都必须被网关模式覆盖"
$missing = New-Object System.Collections.Generic.List[object]
foreach ($b in $distinct) {
    if ($intentionallyUnrouted.ContainsKey($b.Path)) { continue }
    if (-not (Test-Covered -Path $b.Path -Patterns $patterns)) {
        $missing.Add($b)
    }
}

if ($missing.Count -eq 0) {
    Write-Host "  [PASS] $($distinct.Count) 条实际路径全部被网关覆盖"
}
else {
    foreach ($b in $missing) {
        Write-Host "  [FAIL] $($b.Path)  （声明于 $($b.File)）在网关里没有任何 Path 模式覆盖它"
        $violations++
    }
    Write-Host ""
    Write-Host "  改法：在 duoduo-gateway/src/main/resources/application.yml 里，"
    Write-Host "        找到该服务对应的 route（uri: lb://duoduo-xxx），把这段路径追加到它的 Path=… 列表里；"
    Write-Host "        集合型端点（无尾斜杠）要**同时**写裸路径与 /**，例如 /api/admin/recon,/api/admin/recon/**。"
    Write-Host "        然后必须**重建网关镜像并重启**（配置打进 jar，改源码不重启等于没改）。"
}

# ---------- 4. 自证：这套判据真的会判假 ----------
# 一个"永远通过"的检查比没有检查更危险（报表一片绿、事实未知 —— 与 S3 同一条纪律）。
# 这里用两条合成样本证明覆盖判定**两个方向都动**：
#   ① 合成一条不存在的路径  → 必须判"未覆盖"
#   ② 取一条真实模式的裸前缀 → 必须判"已覆盖"
# 只要这两条断言失效（比如 Patterns 传空、前缀比较写反），本检查会自己报红。
Write-Host ""
Write-Host "> 自证：覆盖判定两个方向都动（防'永远通过'）"
$self1 = -not (Test-Covered -Path '/api/__self-check-should-not-exist__' -Patterns $patterns)
$synthetic = @($patterns | Where-Object { $_.EndsWith('/**') })
$self2 = $false
if ($synthetic.Count -gt 0) {
    $probe = $synthetic[0].Substring(0, $synthetic[0].Length - 3).TrimEnd('/')
    $self2 = Test-Covered -Path $probe -Patterns $patterns
}
if ($self1 -and $self2) {
    Write-Host "  [PASS] 不存在的路径判为未覆盖；真实模式的裸前缀判为已覆盖"
}
else {
    if (-not $self1) { Write-Host "  [FAIL] 不存在的路径被判成了'已覆盖' —— 覆盖判定恒真，本检查没有任何意义" }
    if (-not $self2) { Write-Host "  [FAIL] 真实模式的裸前缀被判成了'未覆盖' —— 前缀比较写反了（没有 /** 结尾的模式？）" }
    $violations++
}

# ---------- 汇总 ----------
Write-Host ""
if ($violations -gt 0) {
    Write-Host "=== 路由覆盖自检结果：$violations 项违规 ==="
    exit 1
}
Write-Host "=== 路由覆盖自检结果：全部通过 ==="
exit 0
