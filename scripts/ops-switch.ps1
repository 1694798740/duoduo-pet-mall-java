# ops-switch.ps1 —— 运行期开关的**单一权威清单**：列出 / 校验 / 切换
#
# 为什么要有这个脚本（而不是写一篇文档列举开关）：
#   本项目里「有开关」这句话曾经**只有一半是真的** —— 代码里有字段、yml 里有 ${...}，
#   但 compose 没透传，于是在 .env 里改了、重启了、行为没变。不报错、不告警，
#   只在某次排障时把人耗掉半小时。E25 落地前实测到的四处：
#     ① duoduo.security.api-metrics-enabled（E24）有 yml 占位符但 compose 未透传；
#     ② entryScopeEnabled（E8）连 yml 占位符都没有（硬编码默认值）；
#     ③ adminIpWhitelistEnabled + adminIpWhitelist（E21）同样没有 yml 占位符
#        —— 也就是「IP 白名单可配置」这件事在运维侧根本做不到；
#     ④ DUODUO_SEARCH_ENABLED 在 compose 里是硬编码 'true'（引号包住的字面量）。
#   所以判据不能是"文档里写了几个开关"，必须是**四层逐层核到实物**：
#     清单登记 → compose 真透传（${NAME:-…}） → 服务 yml 真读（${NAME:…}） → 容器里真生效
#
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ops-switch.ps1
#       列出全部开关、当前生效值、默认值、影响；并在最后做一次静态校验
#   ... -Check
#       只做静态校验（不依赖 Docker，可进 CI）；退出码即结论
#   ... -Set DUODUO_API_METRICS_ENABLED=false
#       写入仓库根 .env（可多次传入），并打印需要重建哪个容器
#
# 纪律：
#   · 本脚本的开关清单是**唯一**一份；docs/OPS.md 的开关表由它渲染而来，
#     守卫 scripts/lib/check-ops-doc.ps1 会断言两者一致（双向）。
#   · compose 里凡带 `# OPS-SWITCH` 标记的透传行，都必须在本清单里登记；
#     反之本清单每一项都必须在 compose 里找到带标记的透传行 —— 两向都查。
param(
    [string[]]$Set = @(),
    [switch]$Check,
    [switch]$Json
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$composePath = Join-Path $repo 'docker-compose.yml'
$envPath = Join-Path $repo '.env'

# ---------------------------------------------------------------------------
# 一、权威清单
#
# 字段：Name 环境变量名 / Service compose 服务名 / Kind 类别 / Default 默认值
#       On 打开（或有值）时的行为 / Off 关掉（或置空）会怎样
# ---------------------------------------------------------------------------
$SwitchList = @(
    # ---- 网关：认证链路上的三个「一行配置退回旧行为」 ----
    @{ Name = 'DUODUO_PERM_VERSION_ENABLED'; Service = 'duoduo-gateway-app'; Kind = '开关'
       Default = 'true'
       On  = 'E17 权限版本校验：改完角色，下一个请求即生效（不用等令牌过期）'
       Off = '退回旧行为：角色变更要等令牌自然过期（≤15 分钟）才生效。授权判定本身不受影响' },

    @{ Name = 'DUODUO_ENTRY_SCOPE_ENABLED'; Service = 'duoduo-gateway-app'; Kind = '开关'
       Default = 'true'
       On  = 'E8 入口收窄：平台后台面 /api/admin/** 与商家面 /api/seller/** 只允许对应入口访问'
       Off = '★ 三端**入口隔离失效**：小程序 / 商城令牌也能调另两端的专属接口。仅排障时临时关' },

    @{ Name = 'DUODUO_API_METRICS_ENABLED'; Service = 'duoduo-gateway-app'; Kind = '开关'
       Default = 'true'
       On  = 'E24 逐日记录「带店铺身份的请求」的调用数与 4xx/5xx（租户健康度面板第四维的数据源）'
       Off = '★ 面板的「接口错误率」恒为 0，且**不会有任何报错**（典型静默失效）' },

    @{ Name = 'DUODUO_ADMIN_IP_WHITELIST_ENABLED'; Service = 'duoduo-gateway-app'; Kind = '开关'
       Default = 'false'
       On  = 'E21 只允许名单内的来源访问 /api/admin/**（名单为空 = 拒绝全部后台访问）'
       Off = '后台对任意来源开放（当前默认）。上线前应打开并把运维出口地址配全' },

    @{ Name = 'DUODUO_ADMIN_IP_WHITELIST'; Service = 'duoduo-gateway-app'; Kind = '名单'
       Default = ''
       On  = '逗号分隔的来源清单：精确地址 / CIDR / 前缀通配，如 203.0.113.7,10.0.0.0/8'
       Off = '空名单 + 上一条打开 = **拒绝全部后台访问**（失败关闭，这是刻意的）' },

    # ---- 商品域 ----
    @{ Name = 'DUODUO_SEARCH_ENABLED'; Service = 'duoduo-product-app'; Kind = '开关'
       Default = 'true'
       On  = 'A10 搜索走 ES 优先，失败自动降级 MySQL'
       Off = '搜索直接走 MySQL（ES 不参与）。ES 未部署时表现相同，仅供排障' },

    # ---- 交易域 ----
    @{ Name = 'DUODUO_TRADE_ORDER_MODE'; Service = 'duoduo-trade-app'; Kind = '模式'
       Default = 'sync'
       On  = 'queue = 下单走削峰队列（需先起 mq profile：rocketmq 两容器）'
       Off = 'sync = 下单同步落库（CI/本地默认，零 MQ 依赖）' },

    @{ Name = 'DUODUO_TRADE_PLACE_QPS'; Service = 'duoduo-trade-app'; Kind = '阈值'
       Default = '100'
       On  = '下单入口 QPS 上限（Sentinel 限流；同步与队列端点共用）'
       Off = '调小 = 限流更早介入；调 0 等于封死下单入口' },

    # ---- 用户域 ----
    @{ Name = 'DUODUO_WECHAT_MOCK_ENABLED'; Service = 'duoduo-user-app'; Kind = '开关'
       Default = 'true'
       On  = 'A13 微信登录走 mock 通道（不请求微信服务器，本地/CI 用）'
       Off = '走真实微信通道（需同时注入 WECHAT_APPID / WECHAT_SECRET），否则微信登录不可用' },

    # ---- AI 域 ----
    @{ Name = 'DUODUO_AI_MOCK_ENABLED'; Service = 'duoduo-ai-app'; Kind = '开关'
       Default = 'true'
       On  = 'AI 导购走本地 mock 回复（不调用大模型）'
       Off = '调用真实大模型（需注入 DASHSCOPE_API_KEY），否则 AI 导购不可用' },

    @{ Name = 'DUODUO_AI_MODEL'; Service = 'duoduo-ai-app'; Kind = '取值'
       Default = 'qwen-plus'
       On  = 'AI 导购使用的大模型名'
       Off = '换成别的模型名即切换模型（改完重建 ai 容器）' },

    @{ Name = 'DUODUO_AI_RATE_LIMIT'; Service = 'duoduo-ai-app'; Kind = '阈值'
       Default = '20'
       On  = '单用户每分钟 AI 调用上限'
       Off = '调小 = 更早限流；调大 = 更容易被单个用户刷满' },

    @{ Name = 'DUODUO_AI_GLOBAL_RATE_LIMIT'; Service = 'duoduo-ai-app'; Kind = '阈值'
       Default = '300'
       On  = '全站每分钟 AI 调用总上限（防换 IP 刷）'
       Off = '调小 = 全站 AI 更容易被限流；调 0 = AI 入口整体不可用' },

    @{ Name = 'DUODUO_AI_RECALL_READ_TIMEOUT_MS'; Service = 'duoduo-ai-app'; Kind = '阈值'
       Default = '1500'
       On  = '商品召回跨域读超时（毫秒）；超时即降级为无召回答复'
       Off = '调小 = 更早降级（答得更快但可用商品更少）；调大 = 更易拿到结果但更慢' },

    @{ Name = 'DUODUO_AI_TIMEOUT_SECONDS'; Service = 'duoduo-ai-app'; Kind = '阈值'
       Default = '15'
       On  = '单次 AI 对话超时秒数；超时按 DEGRADED(503) 返回，不挂死请求线程'
       Off = '调小 = 更快失败；调大 = 更易拿到长回复但占用线程更久' }
)

# ---------------------------------------------------------------------------
# 二、读取实物
# ---------------------------------------------------------------------------

function Read-DotEnv([string]$path) {
    $map = [ordered]@{}
    if (-not (Test-Path $path)) { return (Write-Output -NoEnumerate $map) }
    foreach ($raw in (Get-Content -Path $path -Encoding UTF8)) {
        $line = $raw.Trim()
        if ($line -eq '' -or $line.StartsWith('#')) { continue }
        $idx = $line.IndexOf('=')
        if ($idx -le 0) { continue }
        $k = $line.Substring(0, $idx).Trim()
        $v = $line.Substring($idx + 1).Trim()
        if ($v.Length -ge 2) {
            $first = $v.Substring(0, 1); $last = $v.Substring($v.Length - 1, 1)
            if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) {
                $v = $v.Substring(1, $v.Length - 2)
            }
        }
        $map[$k] = $v
    }
    return (Write-Output -NoEnumerate $map)
}

# 从 compose 里解析「带 # OPS-SWITCH 标记的透传行」+「各服务的真实 container_name」
# 返回 @{ Rows; MarkCount; PassthroughNames; ContainerByService }
#
# ★ 为什么要解析 container_name（2026-10-12 修）：
#   第④层「容器里真生效」原先用**猜名**——"duoduo-app-" + (service 去掉 '^duoduo-' 与 '-app$')。
#   实测它只对 `duoduo-xxx-app` 这一种命名成立：`duoduo-mq-broker` / `duoduo-es` 都猜错
#   （→ duoduo-app-mq-broker / duoduo-app-es，均不存在）。猜错的后果不是报错而是**静默降级**：
#   Get-ContainerEnv 返回 $null，输出退回「.env（容器未读到，可能未重建）」；若那项的值又恰好
#   等于默认值，人眼根本分不出「没读到」与「真的没覆盖」。今天没出假绿纯属巧合（清单内 10 项
#   的服务恰好全叫 duoduo-*-app）。改为**读 compose 声明的 container_name**，这才是一等事实。
function Read-ComposeSwitches([string]$path) {
    $lines = @(Get-Content -Path $path -Encoding UTF8)
    $rows = New-Object System.Collections.ArrayList
    $names = New-Object System.Collections.Generic.HashSet[string]
    $containers = @{}
    $markCount = 0
    $curService = ''
    $pending = ''

    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]

        # 所有 ${NAME} / ${NAME:-x} 形式出现过的变量名（用于判断 .env 里的项是否真能生效）
        foreach ($m in [regex]::Matches($line, '\$\{([A-Za-z_][A-Za-z0-9_]*)')) {
            [void]$names.Add($m.Groups[1].Value)
        }

        if ($line -match '^  ([A-Za-z0-9][A-Za-z0-9_-]*):\s*$') { $curService = $Matches[1]; $pending = ''; continue }
        # container_name: xxx —— 服务级事实，记进映射
        if ($curService -ne '' -and $line -match '^\s{4}container_name:\s*([A-Za-z0-9][A-Za-z0-9_.-]*)\s*$') {
            $containers[$curService] = $Matches[1]
            continue
        }
        if ($line -match '^\s*#\s*OPS-SWITCH\b') { $markCount++; $pending = $curService; continue }

        # NAME: ${NAME:-默认值}   —— 反引用保证「左边名字」与「花括号里的名字」是同一个
        if ($line -match '^\s*([A-Z][A-Z0-9_]*):\s*\$\{\1:-([^}]*)\}\s*$') {
            if ($pending -ne '') {
                [void]$rows.Add([pscustomobject]@{
                    Name    = $Matches[1]
                    Default = $Matches[2]
                    Service = $pending
                    Line    = $i + 1
                })
                $pending = ''
            }
            continue
        }

        # 其它含内容的行让标记作废（标记必须**紧贴**在透传行上一行）
        if ($line -match '^\s*\S') { $pending = '' }
    }

    return @{ Rows = $rows; MarkCount = $markCount; PassthroughNames = $names; ContainerByService = $containers }
}

# 查容器内环境变量。
# ★ 注意 docker exec 的退出码**不可信**：容器不存在时它打印
#   `Error response from daemon: No such container: xxx` 到 stderr，但 **$LASTEXITCODE 仍是 0**
#   （2026-10-12 实测）。所以不能靠退出码判"查到了没"——下面两道防线才是真的：
#     ① 非空且含 'No such container' / 'Error response' 的一律判**查不到**（不是"值就是这个"）；
#     ② 真正的自证在调用侧：猜名/命名一旦对不上，会进 Add-Ck 的失败分支（见「容器名可解析」）。
function Get-ContainerEnv([string]$container, [string]$name) {
    if (-not $container) { return $null }
    $out = & docker exec $container printenv $name 2>$null
    if ($null -eq $out) { return $null }
    $s = ($out | Out-String).Trim()
    if ($s -eq '') { return $null }
    if ($s -match 'No such container' -or $s -match '^Error response from daemon') { return $null }
    return $s
}

function Test-DockerUp {
    try {
        $prev = $ErrorActionPreference; $ErrorActionPreference = 'SilentlyContinue'
        $names = @(& docker ps --format '{{.Names}}' 2>$null)
        $ErrorActionPreference = $prev
        if ($names.Count -eq 0) { return @() }
        return $names
    } catch { return @() }
}

$dotenv = Read-DotEnv $envPath
$compose = Read-ComposeSwitches $composePath

# ---------------------------------------------------------------------------
# 三、静态校验（四层逐层核）
# ---------------------------------------------------------------------------
$fails = New-Object System.Collections.ArrayList
$passes = New-Object System.Collections.ArrayList

function Add-Ck([bool]$ok, [string]$label, [string]$detail) {
    if ($ok) { [void]$passes.Add($label) } else { [void]$fails.Add("$label —— $detail") }
}

# 自证：解析器本身必须先证明它读到了东西（本项目最常栽的一类：解析失效却报 OK）
Add-Ck ($SwitchList.Count -gt 0) '清单非空' '清单为 0 项，脚本自身失效'
Add-Ck ($compose.MarkCount -gt 0) 'compose 里存在 # OPS-SWITCH 标记' '一个标记都没找到，解析失效（不是"没有开关"）'
Add-Ck ($compose.MarkCount -eq $compose.Rows.Count) '标记数与解析出的透传行数一致' `
    ("标记 $($compose.MarkCount) 行，只解析出 $($compose.Rows.Count) 行 —— 有标记没紧贴透传行")

$composeByName = @{}
foreach ($r in $compose.Rows) { $composeByName[$r.Name] = $r }

# ★ 自证（2026-10-12 新增）：第④层「容器里真生效」依赖 container_name 能解析出来。
#   解析不出来的话，$container 为 $null → Get-ContainerEnv 恒 $null → 输出退回「.env（容器未
#   读到，可能未重建）」。**这不会报错**，只会让人误以为"容器还没重建"而反复重建。
#   所以「解析到 0 个 container_name」必须直接判失败，而不是降级。
Add-Ck ($compose.ContainerByService.Count -gt 0) 'compose 里能解析出 container_name' `
    '一个都没解析到 —— 第④层「容器里真生效」将整体失效（不是"没有容器"）'
$svcInList = @($SwitchList | ForEach-Object { $_.Service } | Sort-Object -Unique)
$svcNoName = @($svcInList | Where-Object { -not $compose.ContainerByService.ContainsKey($_) })
Add-Ck ($svcNoName.Count -eq 0) '开关清单涉及的每个服务都能解析到 container_name' `
    ("这些服务在 compose 里找不到 container_name：{0}（第④层对它们是盲区）" -f ($svcNoName -join ', '))

# 正向：清单每一项 → compose 真透传 + 默认值一致 + 服务 yml 真读
foreach ($s in $SwitchList) {
    $n = $s.Name
    if (-not $composeByName.ContainsKey($n)) {
        [void]$fails.Add("$n —— ★ compose 里没有带 # OPS-SWITCH 标记的透传行（在 .env 里改了不会生效）")
        continue
    }
    $row = $composeByName[$n]
    Add-Ck ($row.Default -eq $s.Default) "$n 默认值与清单一致" `
        "compose 写 '$($row.Default)'，清单写 '$($s.Default)'"

    # 服务 yml 是否真的读这个变量
    $module = $row.Service -replace '-app$', ''
    $yml = Join-Path $repo "$module/src/main/resources/application.yml"
    if (-not (Test-Path $yml)) {
        [void]$fails.Add("$n —— 找不到 $module 的 application.yml（没有「读」的一方）")
        continue
    }
    $ymlText = [string]::Join("`n", @(Get-Content -Path $yml -Encoding UTF8))
    Add-Ck ($ymlText.Contains('${' + $n + ':')) "$n 已绑定到服务 yml" `
        "$module/application.yml 里没有该变量的占位符（compose 传了但服务不读）"
}

# 反向：compose 里带标记的每一项 → 清单必须登记
foreach ($row in $compose.Rows) {
    if (-not ($SwitchList | Where-Object { $_.Name -eq $row.Name })) {
        [void]$fails.Add("$($row.Name) —— compose 第 $($row.Line) 行标了 # OPS-SWITCH，但清单里没登记")
    }
}

# .env 里的每一项 → 必须真的被 compose 引用过（否则改了不生效）
foreach ($k in $dotenv.Keys) {
    if (-not $compose.PassthroughNames.Contains($k)) {
        [void]$fails.Add("$k —— .env 里有这一项，但 compose 没有任何地方引用它，**改了不会生效**")
    }
}

# ---------------------------------------------------------------------------
# 四、输出
# ---------------------------------------------------------------------------
if (-not $Check) {
    $up = Test-DockerUp
    $running = $up.Count -gt 0

    Write-Host ''
    Write-Host '====================================================================' -ForegroundColor Cyan
    Write-Host ' 运行期开关（唯一权威清单）' -ForegroundColor Cyan
    Write-Host '====================================================================' -ForegroundColor Cyan
    Write-Host ''
    Write-Host ("  {0,-38} {1,-14} {2,-10} {3}" -f '开关', '当前生效', '默认', '来源') -ForegroundColor DarkGray
    Write-Host ("  {0,-38} {1,-14} {2,-10} {3}" -f ('-' * 38), ('-' * 14), ('-' * 10), ('-' * 22)) -ForegroundColor DarkGray

    $overridden = 0
    foreach ($s in $SwitchList) {
        $n = $s.Name
        $row = $composeByName[$n]
        $effective = $null
        $src = '默认值'
        if ($dotenv.Contains($n)) { $effective = $dotenv[$n]; $src = '.env'; $overridden++ }
        else { $effective = $s.Default }

        if ($running -and $row) {
            # ★ 用 compose 声明的真实 container_name，不再猜名（见 Read-ComposeSwitches 顶部注释）
            $container = $compose.ContainerByService[$row.Service]
            $live = Get-ContainerEnv $container $n
            if ($null -ne $live) { $effective = $live; $src = '容器实际生效' }
            elseif ($src -eq '.env') { $src = '.env（容器未读到，可能未重建）' }
        } elseif (-not $running) {
            $src = "$src（容器未运行，推断值）"
        }

        $color = 'Gray'
        if ($src -like '容器实际生效*') { $color = 'Green' }
        if ($effective -ne $s.Default) { $color = 'Yellow' }

        Write-Host ("  {0,-38} {1,-14} {2,-10} {3}" -f $n, ('' + $effective), $s.Default, $src) -ForegroundColor $color
    }

    Write-Host ''
    Write-Host "  共 $($SwitchList.Count) 项；被 .env 或容器覆盖 $overridden 项。" -ForegroundColor Gray
    Write-Host '  改法：在本脚本后面加 -Set NAME=VALUE（写入 .env），再按提示重建目标容器。' -ForegroundColor Gray
    Write-Host ''
    Write-Host '  各项含义与「关掉会怎样」：' -ForegroundColor DarkGray
    foreach ($s in $SwitchList) {
        Write-Host ("    {0}" -f $s.Name) -ForegroundColor White
        Write-Host ("      开/有值：{0}" -f $s.On) -ForegroundColor Gray
        Write-Host ("      关/置空：{0}" -f $s.Off) -ForegroundColor Gray
    }
    Write-Host ''
}

# 校验结论
Write-Host ''
Write-Host '--------------------------------------------------------------------' -ForegroundColor Cyan
Write-Host " 静态校验：通过 $($passes.Count) 条，失败 $($fails.Count) 条" -ForegroundColor $(if ($fails.Count -eq 0) { 'Green' } else { 'Red' })
Write-Host '--------------------------------------------------------------------' -ForegroundColor Cyan
foreach ($p in $passes) { Write-Host "  [OK]   $p" -ForegroundColor DarkGray }
foreach ($f in $fails) { Write-Host "  [FAIL] $f" -ForegroundColor Red }

# -Set：写 .env
if ($Set.Count -gt 0) {
    Write-Host ''
    $existing = @(Get-Content -Path $envPath -Encoding UTF8)
    $touched = New-Object System.Collections.ArrayList
    foreach ($pair in $Set) {
        $eq = $pair.IndexOf('=')
        if ($eq -le 0) { throw "参数格式应为 NAME=VALUE，收到：$pair" }
        $k = $pair.Substring(0, $eq).Trim()
        $v = $pair.Substring($eq + 1).Trim()

        if (-not $compose.PassthroughNames.Contains($k)) {
            Write-Host "  [FAIL] $k 没有被 compose 引用，写进去也不会生效 —— 已拒绝" -ForegroundColor Red
            exit 1
        }
        $hit = $false
        for ($i = 0; $i -lt $existing.Count; $i++) {
            if ($existing[$i] -match ('^\s*' + [regex]::Escape($k) + '\s*=')) {
                $existing[$i] = "$k=$v"; $hit = $true; break
            }
        }
        if (-not $hit) { $existing += "$k=$v" }
        [void]$touched.Add($k)
        Write-Host "  已写入 .env: $k=$v" -ForegroundColor Green
    }
    Set-Content -Path $envPath -Value $existing -Encoding UTF8

    $svcs = @()
    foreach ($k in $touched) {
        $row = $composeByName[$k]
        if ($row) { $svcs += $row.Service }
        else {
            foreach ($s in $SwitchList) { if ($s.Name -eq $k) { $svcs += $s.Service } }
        }
    }
    $svcs = $svcs | Select-Object -Unique
    Write-Host ''
    Write-Host "  下一步（改环境变量只需重建容器，不必重新打镜像）：" -ForegroundColor Yellow
    foreach ($svc in $svcs) {
        Write-Host "    docker compose up -d --force-recreate --no-deps $svc" -ForegroundColor Yellow
    }
    Write-Host ''
}

if ($fails.Count -gt 0) { exit 1 }
exit 0
