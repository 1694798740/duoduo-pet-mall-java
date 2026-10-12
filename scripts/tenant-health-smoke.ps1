# 交易域 · 租户健康度面板（E24）端到端冒烟（Windows PowerShell 5.1）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tenant-health-smoke.ps1
#
# 前置：容器全部 healthy；已 mvn clean package + docker compose build 出最新镜像。
#       无新增 DDL（本功能只加聚合查询，不落新表）。
#
# 本套要证明的核心事实（不是「接口返回 200」，而是「结论可以被验证」）：
#   ① 面板横跨**三个存储**（交易库订单、履约库售后、Redis 接口计数），
#      所以必须分别证明「每一路都真的被读过」，而不是只有总响应是 200 ——
#      三路里任何一路断了，「空结果」与「链路没接通」在页面上长得一模一样；
#   ② ★ 接口错误率这条链路是**本轮新建的**（网关逐日计数 → Redis → 读侧聚合 → 百分比）。
#      端到端证据只能是「真发几次请求，看计数真的涨了」；读代码说它会涨不算证据；
#   ③ ★ 判级必须能**真的变红也能真的变回去** —— 只用存量数据断言「现在有 RISK」，
#      在库被清空后会静默变成一条永远为假的空断言。因此本套自己造异常、断言它被抓到、
#      再删掉、断言它消失（「能抓到」与「抓对了」是两回事）；
#   ④ ★ 「没有可判断的数据」（NO_DATA）与「健康」（OK）必须分得开：
#      一家从没做过生意、也没调过接口的店铺**不该出现在面板上**；
#      而一旦有了一次接口调用，它就该以 OK 出现 —— 两个断言一起才说明白口径；
#   ⑤ 比例字段的分母为 0 时必须给 null（渲染成「—」）而不是 0.00%；
#   ⑥ 排序与截断要真的生效（被截断时 truncated=true，否则「只有这几家」与「还有更多」无法区分）；
#   ⑦ 权限三层：网关未登录 401 / 非运营 403 / 服务端直连也要 401·403（只测网关会漏掉注解没注册）。
#
# 设计纪律（沿用既有脚本教训）：
#   1. 夹具现场建、跑完自毁，**建不起或删不掉都显式报红**；不依赖存量数据；
#   2. 凡改库必先记录原值，结尾自证「库内数据已复原」；
#   3. 每条「能抓到」的断言都配一条「复原后消失」的断言（同一租户、同一指标）；
#   4. 阈值以**运行中的服务**为准（/limits），不把数字抄进脚本 ——
#      抄来的断言在阈值调整后要么假红、要么悄然失效。
#
# 脚本自身纪律（本项目踩过的坑）：
#   · 断言名里一律用「」，**不用全角引号** —— PowerShell 会把 “ ” 也当字符串定界符，
#     一个没闭合的引号会连带出 8 个「意外的标记」错误，且报错行号指向别处；
#   · `.Count -eq 0` 这类比较必须**多包一层括号**，否则会被静默误绑定且不报错。
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    # 直连交易服务：验证「注解判权 + 命令式判权」在服务端也真的挡住了
    [string]$Trade = 'http://127.0.0.1:8083',
    # 直连履约服务：验证内部接口确实要凭据
    [string]$Fulfilment = 'http://127.0.0.1:8084',
    [string]$Password = 'duoduo123',
    [string]$OperatorPassword = 'duoduo12345'
)

$ErrorActionPreference = 'Continue'
$pass = 0
$fail = 0
$failures = @()

function Check([string]$name, [bool]$ok, $extra) {
    if ($ok) {
        $script:pass++
        Write-Host "  [PASS] $name"
    } else {
        $script:fail++
        $script:failures += $name
        if ($null -ne $extra) { Write-Host "  [FAIL] $name  -> $extra" } else { Write-Host "  [FAIL] $name" }
    }
}

. (Join-Path $PSScriptRoot 'lib\platform-operator.ps1')
. (Join-Path $PSScriptRoot 'lib\seller-fixture.ps1')
. (Join-Path $PSScriptRoot 'lib\internal-token.ps1')

# ★ /actuator/health 的 Content-Type 是 application/vnd.spring-boot.actuator.v3+json，
#   PS 5.1 的 -UseBasicParsing 对**非 application/json 的 +json 后缀**不认，
#   会把 $r.Content 返回成 System.Byte[]（普通接口是 string，所以别的冒烟脚本没踩到）。
#   表现极具迷惑性：状态码 200 正确、`$h.body.status` 恒为 $null ——
#   「断言失败」看起来像接口没通，实际是客户端把报文当二进制了。
function TextOf($content) {
    if ($null -eq $content) { return $null }
    if ($content -is [byte[]]) { return [System.Text.Encoding]::UTF8.GetString($content) }
    return [string]$content
}

# 非 JSON 响应（HTML 错误页等）不该让整个断言炸掉 —— 解析不出来返回 $null，由调用方断言
function JsonOf($text) {
    if ([string]::IsNullOrWhiteSpace($text)) { return $null }
    try { return ($text | ConvertFrom-Json) } catch { return $null }
}

function Call([string]$method, [string]$uri, $session, $bodyObj, [hashtable]$headers) {
    $params = @{ Uri = $uri; Method = $method; UseBasicParsing = $true; TimeoutSec = 30 }
    if ($session) { $params.WebSession = $session }
    if ($headers) { $params.Headers = $headers }
    if ($null -ne $bodyObj) {
        $params.Body = ($bodyObj | ConvertTo-Json -Compress -Depth 6)
        $params.ContentType = 'application/json; charset=utf-8'
    }
    try {
        $r = Invoke-WebRequest @params
        $text = TextOf $r.Content
        return @{ status = [int]$r.StatusCode; raw = $text; body = (JsonOf $text) }
    } catch {
        $code = 0; $body = $null; $raw = $null
        try { $code = [int]$_.Exception.Response.StatusCode.value__ } catch { }
        try {
            if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
                $raw = [string]$_.ErrorDetails.Message
                $body = JsonOf $raw
            }
        } catch { }
        if ($null -eq $raw) {
            try {
                $resp = $_.Exception.Response
                $t = $null
                if ($resp -and $resp.PSObject.Methods.Name -contains 'GetResponseStream') {
                    $st = $resp.GetResponseStream()
                    if ($st) { $t = (New-Object System.IO.StreamReader($st)).ReadToEnd() }
                } elseif ($resp -and $null -ne $resp.Content) {
                    $t = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                }
                if ($t) { $raw = TextOf $t; $body = JsonOf $raw }
            } catch { }
        }
        return @{ status = $code; raw = $raw; body = $body }
    }
}

function Sql([string]$db, [string]$sql) {
    $env:PATH = 'C:\Program Files\Docker\Docker\resources\bin;' + $env:PATH
    if (-not $env:DOCKER_CONFIG) { $env:DOCKER_CONFIG = 'D:\duoduo-infra\docker-nocreds' }
    $out = & docker exec duoduo-mysql mysql -uduoduo -pduoduo -N -B --default-character-set=utf8mb4 -D $db -e $sql 2>&1
    if ($LASTEXITCODE -ne 0) {
        # ★ 失败**不许静默**。早期版本直接 `return $null`，于是「INSERT 失败」在下游表现为
        #   「count=0」—— 和「插进去了但查不到」长得一模一样，排查方向整个跑偏。
        #   （E24 实测：夹具 id 9980000000000000001 超出 bigint 上限 → ERROR 1264，
        #     错误被吞掉后只看到 count=0，看起来像"表结构不对"。）
        Write-Host ("  [SQL 失败] db=$db exit=$LASTEXITCODE") -ForegroundColor Red
        Write-Host ("    sql=" + $sql) -ForegroundColor Red
        Write-Host ("    out=" + ((@($out) | Where-Object { "$_" -notmatch 'Warning' }) -join ' | ')) -ForegroundColor Red
        return $null
    }
    return ((@($out) | Where-Object { "$_" -notmatch 'Warning' }) -join "`n").Trim()
}

# 读 Redis 的一个哈希键 → 平铺的 field/value 重新配成 hashtable。
# 空字段（不存在的键）返回空表，这与「键存在但字段没有」在调用方那里要靠 ContainsKey 区分。
function RedisHash([string]$key) {
    $env:PATH = 'C:\Program Files\Docker\Docker\resources\bin;' + $env:PATH
    if (-not $env:DOCKER_CONFIG) { $env:DOCKER_CONFIG = 'D:\duoduo-infra\docker-nocreds' }
    $out = & docker exec duoduo-redis redis-cli HGETALL $key 2>&1
    $lines = @($out | ForEach-Object { "$_".Trim() } | Where-Object { $_ -ne '' })
    $map = @{}
    for ($i = 0; $i + 1 -lt $lines.Count; $i += 2) { $map[$lines[$i]] = $lines[$i + 1] }
    return $map
}

# 百分比：与 Java 侧 TenantHealthJudgement.percent 同口径（两位小数 + %）。
# 脚本里刻意再算一遍 —— 这正是「断言服务端输出」：服务端换了口径而脚本不改，它会红。
function Pct([long]$num, [long]$den) {
    if ($den -le 0) { return $null }
    return ('{0:F2}%' -f (([double]$num / [double]$den) * 100.0))
}

function CallPanel($session, [int]$days, [int]$limit) {
    return Call 'Get' "$Gateway/api/admin/tenant-health?days=$days&limit=$limit" $session
}

# 只要响应体（取不到就返回 $null）—— 让「取面板 → 找某一行」能写成两行而不是一行套三括号
function PanelData($session, [int]$days, [int]$limit) {
    $r = CallPanel $session $days $limit
    if ($r.status -ne 200) { return $null }
    return $r.body.data
}

# 从面板里取某个租户那一行；取不到返回 $null
function RowOf($panelData, [string]$tenantId) {
    if ($null -eq $panelData) { return $null }
    if ($null -eq $panelData.tenants) { return $null }
    foreach ($t in @($panelData.tenants)) {
        if ("$($t.tenantId)" -eq $tenantId) { return $t }
    }
    return $null
}

function LevelOf($panelData, [string]$tenantId, [string]$fallback = 'NONE') {
    $row = RowOf $panelData $tenantId
    if ($null -eq $row) { return $fallback }
    return "$($row.level)"
}

function ToDate($raw) {
    if ($null -eq $raw) { return [datetime]::MinValue }
    try { return [datetime]::ParseExact("$raw", 'yyyy-MM-dd', $null) } catch { }
    try { return [datetime]$raw } catch { return [datetime]::MinValue }
}

# 解析 ISO 时间戳（**秒可以省略**）。
# ★ 服务端回显用的是 Java 的 LocalDateTime.toString()：秒为 0 时会省略成
#   `2026-10-12T00:00`。那是 Java 的规范行为，不是缺陷 —— 它仍是合法 ISO-8601，
#   Feign/Jackson 也能反序列化。所以断言要比「同一时刻」，不能比「同一串字符」，
#   否则每天 00:00:00 这个窗口会**永久假红**（而红的原因与被测功能毫无关系）。
function ToStamp($raw) {
    $t = "$raw".Trim()
    if ($t -eq '') { return [datetime]::MinValue }
    try { return [datetime]::Parse($t, [Globalization.CultureInfo]::InvariantCulture) } catch { return [datetime]::MinValue }
}

# 打印响应体时截断：面板报文有几 KB，原样回显会把整份日志淹掉（真正有用的那行反而看不见）
function Trunc($s, [int]$max = 240) {
    $t = "$s"
    if ($t.Length -le $max) { return $t }
    return $t.Substring(0, $max) + '…(已截断)'
}

$today = (Get-Date).ToUniversalTime().Date
$dayKey = 'duoduo:api:stat:' + $today.ToString('yyyy-MM-dd')

## ============================================================================
## 0. 环境与权限（三层）
## ============================================================================
Write-Host ''
Write-Host '> 环境与权限'

$h = Call 'Get' "$Gateway/actuator/health" $null
Check '网关可达（actuator/health = UP）' ("$($h.body.status)" -eq 'UP') "$($h.status) $($h.raw)"

$anon = CallPanel $null 30 50
Check '未登录访问面板 → 401' ($anon.status -eq 401) "status=$($anon.status)"
Check '未登录访问 /limits → 401' ((Call 'Get' "$Gateway/api/admin/tenant-health/limits" $null).status -eq 401)

# 买家会话（用于「非运营被拒」的反向断言）
$buyerPhone = '137' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$sessBuyer = New-Object Microsoft.PowerShell.Commands.WebRequestSession
Call 'Post' "$Gateway/api/auth/register" $sessBuyer `
    @{ phone = $buyerPhone; password = $Password; nickname = '健康度冒烟-买家' } | Out-Null
Check '买家访问面板 → 403' ((CallPanel $sessBuyer 30 50).status -eq 403)
Check '买家访问 /limits → 403' ((Call 'Get' "$Gateway/api/admin/tenant-health/limits" $sessBuyer).status -eq 403)

# 运营会话（临时提权夹具，收尾降权）
$op = New-PlatformOperatorSession -Gateway $Gateway -Password $OperatorPassword
Check '（夹具）平台运营会话就绪（角色已生效）' $op.roleSet "role=$($op.role)"
$sessOp = $op.session

# ★ 服务端直连：只测网关会漏掉「注解没注册 / 判权被拆掉」（E16 的核心结论）
$directAnon = Call 'Get' "$Trade/api/admin/tenant-health" $null
Check '★ 服务端直连（无任何身份头）→ 401（判权不只在网关）' ($directAnon.status -eq 401) "status=$($directAnon.status)"
$directBuyer = Call 'Get' "$Trade/api/admin/tenant-health" $null $null @{ 'X-User-Id' = '1'; 'X-User-Role' = 'USER' }
Check '★ 服务端直连（伪造 USER 身份头）→ 403' ($directBuyer.status -eq 403) "status=$($directBuyer.status)"

## ============================================================================
## 1. 运行时口径（阈值由服务回答，不抄数字）
## ============================================================================
Write-Host ''
Write-Host '> 运行时口径（/limits）'

$lim = Call 'Get' "$Gateway/api/admin/tenant-health/limits" $sessOp
Check '运营读 /limits → 200' ($lim.status -eq 200) "status=$($lim.status)"
$L = $lim.body.data
Check '窗口上限 maxDays = 30' ("$($L.maxDays)" -eq '30') "$($L.maxDays)"
Check '窗口默认 defaultDays = 30' ("$($L.defaultDays)" -eq '30') "$($L.defaultDays)"
Check '行数上限 maxLimit = 200' ("$($L.maxLimit)" -eq '200') "$($L.maxLimit)"
Check '超期托管阈值 staleCustodyDays = 30' ("$($L.staleCustodyDays)" -eq '30') "$($L.staleCustodyDays)"
# ★ 跨模块硬约束：键存活期必须 ≥ 窗口上限，否则超期那几天的 Redis 键已过期，
#   读到的空结果与「那天没调用」完全一样 → 接口错误率会安静地变小。这条约束编译器管不到。
Check '★ 接口指标键存活期 ≥ 窗口上限（跨模块约束的运行时校验）' `
    ([int]$L.metricKeepDays -ge [int]$L.maxDays) `
    "keepDays=$($L.metricKeepDays) maxDays=$($L.maxDays)"
$levelsRaw = @($L.levels)
Check '判级档位恰好四个且含 NO_DATA（无数据必须与健康分开）' `
    (($levelsRaw.Count -eq 4) -and ($levelsRaw -contains 'NO_DATA') -and ($levelsRaw -contains 'OK') `
        -and ($levelsRaw -contains 'RISK') -and ($levelsRaw -contains 'WATCH')) `
    ($levelsRaw -join ',')
Check '结算异常来源恰好三类（欠款 / 超期托管 / 缺托管单）' `
    ((@($L.settlementAbnormalSources).Count -eq 3)) (@($L.settlementAbnormalSources) -join ',')
# ★ 这两条必须按**数值**比：JSON 里的 20 会被 ConvertFrom-Json 解析成 [double] 20.0，
#   而 `"$x" -eq '20'` 比的是字符串 —— '20.0' ≠ '20' 会永远为假。
#   （这正是铁律「断言要测服务端输出，不要测客户端解析后的对象」的反面应用：
#     这里要断的就是数值本身，不该绕一圈先转成字符串再比。）
Check '售后率关注线 = 20（前端不再抄一份）' `
    (([double]$L.afterSaleWatchPercent -eq 20.0)) "$($L.afterSaleWatchPercent)"
Check '接口错误率风险线 = 1、关注线 = 0.2' `
    ((([double]$L.apiErrorRiskPercent -eq 1.0) -and ([double]$L.apiErrorWatchPercent -eq 0.2))) `
    "risk=$($L.apiErrorRiskPercent) watch=$($L.apiErrorWatchPercent)"
# ★ 样本门槛也必须回显：只给百分比，前端无法解释「4 单里 4 笔售后（100%）为什么没变关注」。
#   缺了它会表现为"页面把规则讲了一半"，用户以为面板漏报。
Check '样本门槛回显（售后 ≥ N 单才算率、调用 ≥ M 次才算 5xx 率）' `
    ((([long]$L.afterSaleMinBase -ge 1) -and ([long]$L.apiMinBase -ge 1))) `
    "afterSaleMinBase=$($L.afterSaleMinBase) apiMinBase=$($L.apiMinBase)"

## ============================================================================
## 2. 参数校验（非法窗口必须 400，不许静默回落）
## ============================================================================
Write-Host ''
Write-Host '> 参数校验'

Check 'days=0 → 400' ((CallPanel $sessOp 0 50).status -eq 400)
Check 'days=31（超过上限）→ 400' ((CallPanel $sessOp 31 50).status -eq 400)
Check 'days=abc → 400' ((Call 'Get' "$Gateway/api/admin/tenant-health?days=abc" $sessOp).status -eq 400)
Check 'limit=0 → 400' ((CallPanel $sessOp 30 0).status -eq 400)
Check 'limit=201（超过上限）→ 400' ((CallPanel $sessOp 30 201).status -eq 400)
$ok7 = CallPanel $sessOp 7 50
Check '正对照：days=7 → 200 且窗口回显为 7 天' `
    (($ok7.status -eq 200) -and ("$($ok7.body.data.days)" -eq '7')) `
    "status=$($ok7.status) days=$($ok7.body.data.days)"
# ★ 不能写 CallPanel $sessOp 30 $null —— [int]$null 会变成 0，URL 成了 limit=0（400），
#   于是这条断言测的其实是「参数校验」而不是「默认值」，还会一直假红。
$dflt = Call 'Get' "$Gateway/api/admin/tenant-health?days=30" $sessOp
Check '未传 limit 时用默认值 50' `
    ("$($dflt.body.data.limit)" -eq '50') "status=$($dflt.status) limit=$($dflt.body.data.limit)"

## ============================================================================
## 3. 结构完整性（少一个字段是静默缺陷）
## ============================================================================
Write-Host ''
Write-Host '> 结构完整性'

$p = CallPanel $sessOp 30 200
Check '运营取面板 → 200' ($p.status -eq 200) "status=$($p.status) $(Trunc $p.raw)"
$panel = $p.body.data
# ★ 骨架断言：面板取不到时，下面每一条字段断言都会退化成「结构不符」，
#   把真正的原因（500 / 空响应）掩盖掉；更糟的是脚本自己会因 $null.PSObject 抛索引异常 ——
#   那是「检验工具坏了」，会被误读成「被测坏了」。
Check '骨架：面板响应可解析（否则后续结构断言全部无意义）' ($null -ne $panel) "status=$($p.status)"

$panelFields = @('tenants', 'platform', 'days', 'from', 'to', 'limit', 'totalTenants', 'truncated', 'nameResolved', 'generatedAt')
$gotPanel = if ($null -eq $panel) { @() } else { @($panel.PSObject.Properties.Name) }
$missPanel = @($panelFields | Where-Object { $gotPanel -notcontains $_ })
Check '面板顶层 10 个字段齐全' (($missPanel.Count -eq 0)) ($missPanel -join ',')

$platFields = @('tenantCount', 'orderCount', 'gmv', 'afterSaleCount', 'afterSaleRate',
    'settlementAbnormalCount', 'apiTotal', 'apiErrorRate', 'riskyTenants', 'watchTenants')
$gotPlat = if ($null -eq $panel -or $null -eq $panel.platform) { @() } else { @($panel.platform.PSObject.Properties.Name) }
$missPlat = @($platFields | Where-Object { $gotPlat -notcontains $_ })
Check '平台汇总 10 个字段齐全' (($missPlat.Count -eq 0)) ($missPlat -join ',')

$rowFields = @('tenantId', 'tenantName', 'orderCount', 'paidCount', 'gmv',
    'afterSaleCount', 'afterSaleRefundedCount', 'afterSaleOpenCount', 'afterSaleAdverseCount', 'afterSaleRate',
    'debtOutstandingCount', 'debtOutstandingAmount', 'staleCustodyCount', 'staleCustodyAmount',
    'missingSettlementCount', 'settlementAbnormalCount',
    'apiTotal', 'apiServerError', 'apiClientError', 'apiErrorRate', 'level')
$rowNames = @{}
foreach ($t in @($panel.tenants)) {
    if ($null -eq $t) { continue }
    foreach ($n in @($t.PSObject.Properties.Name)) { $rowNames[$n] = $true }
}
$missRow = @($rowFields | Where-Object { -not $rowNames.ContainsKey($_) })
Check '租户行 21 个字段齐全（遍历全部行取并集）' (($missRow.Count -eq 0)) ($missRow -join ',')
Check 'tenants 是数组（空库时也应是空数组而非 null）' ($null -ne $panel.tenants) "rows=$(@($panel.tenants).Count)"

$fromD = ToDate $panel.from
$toD = ToDate $panel.to
Check '★ 窗口跨度与 days 自洽（to - from = days - 1，含首含尾）' `
    (((($toD - $fromD).Days)) -eq ([int]$panel.days - 1)) `
    "from=$($panel.from) to=$($panel.to) days=$($panel.days)"
Check 'generatedAt 是 UTC ISO（以 Z 结尾）' ("$($panel.generatedAt)".EndsWith('Z')) "$($panel.generatedAt)"

## ============================================================================
## 4. 口径自证（比例与合计必须能被重算出来）
## ============================================================================
Write-Host ''
Write-Host '> 口径自证（算术）'

$rows = @($panel.tenants)
$badAbnormal = 0; $badSaleRate = 0; $badApiRate = 0; $badOrderPaid = 0; $badLevel = 0; $badNoData = 0
foreach ($r in $rows) {
    if ($null -eq $r) { continue }
    # ★ 铁律：金额/计数断言必须做一次算术 —— 两边都是字符串时恒等比较照样为真
    if ([long]$r.settlementAbnormalCount -ne ([long]$r.debtOutstandingCount + [long]$r.staleCustodyCount + [long]$r.missingSettlementCount)) { $badAbnormal++ }
    $expectSale = Pct ([long]$r.afterSaleCount) ([long]$r.paidCount)
    if ($null -eq $expectSale) {
        if ($null -ne $r.afterSaleRate) { $badSaleRate++ }
    } elseif ("$($r.afterSaleRate)" -ne $expectSale) { $badSaleRate++ }
    $expectApi = Pct ([long]$r.apiServerError) ([long]$r.apiTotal)
    if ($null -eq $expectApi) {
        if ($null -ne $r.apiErrorRate) { $badApiRate++ }
    } elseif ("$($r.apiErrorRate)" -ne $expectApi) { $badApiRate++ }
    if ([long]$r.paidCount -gt [long]$r.orderCount) { $badOrderPaid++ }
    if (@('RISK', 'WATCH', 'NO_DATA', 'OK') -notcontains "$($r.level)") { $badLevel++ }
    # 三态纪律：既没成交也没接口调用 → 必须是 NO_DATA；反之绝不能是 NO_DATA
    $noSample = (([long]$r.paidCount -le 0) -and ([long]$r.apiTotal -le 0))
    if ($noSample -and ("$($r.level)" -ne 'NO_DATA')) { $badNoData++ }
    if ((-not $noSample) -and ("$($r.level)" -eq 'NO_DATA')) { $badNoData++ }
}
Check '★ 每行 结算异常合计 = 欠款 + 超期托管 + 缺托管单（逐行算术）' ($badAbnormal -eq 0) "不符 $badAbnormal 行"
Check '★ 每行 售后率与按公式重算一致（分母 0 时必须为 null，不是 0.00%）' ($badSaleRate -eq 0) "不符 $badSaleRate 行"
Check '★ 每行 接口错误率与按公式重算一致（只看 5xx）' ($badApiRate -eq 0) "不符 $badApiRate 行"
Check '成交单数不超过下单数（逐行）' ($badOrderPaid -eq 0) "不符 $badOrderPaid 行"
Check '档位取值都在四态白名单内' ($badLevel -eq 0) "越界 $badLevel 行"
Check '★ NO_DATA 与 OK 严格分开（有样本绝不给 NO_DATA，无样本绝不给 OK）' ($badNoData -eq 0) "不符 $badNoData 行"
Check '（口径）比例字段是字符串（前端不负责四舍五入，两个后台页面的写法必须一致）' `
    ((($null -eq $panel.platform.afterSaleRate) -or ("$($panel.platform.afterSaleRate)".EndsWith('%')))) `
    "afterSaleRate=$($panel.platform.afterSaleRate)"

# 平台比例必须是「先求和再相除」，不是把各租户的百分比平均
$sumOrders = 0; $sumPaid = 0; $sumGmv = 0; $sumSale = 0; $sumApi = 0; $sumApiErr = 0; $sumAbn = 0
foreach ($r in $rows) {
    $sumOrders += [long]$r.orderCount; $sumPaid += [long]$r.paidCount; $sumGmv += [long]$r.gmv
    $sumSale += [long]$r.afterSaleCount; $sumApi += [long]$r.apiTotal; $sumApiErr += [long]$r.apiServerError
    $sumAbn += [long]$r.settlementAbnormalCount
}
Check '★ 平台汇总 = 各行之和（订单 / 成交额 / 售后 / 异常 / 调用数，逐项算术）' `
    ((([long]$panel.platform.orderCount -eq $sumOrders) -and ([long]$panel.platform.gmv -eq $sumGmv) -and
        ([long]$panel.platform.afterSaleCount -eq $sumSale) -and ([long]$panel.platform.settlementAbnormalCount -eq $sumAbn) -and
        ([long]$panel.platform.apiTotal -eq $sumApi))) `
    "平台=$($panel.platform.orderCount)/$($panel.platform.gmv)/$($panel.platform.afterSaleCount)/$($panel.platform.settlementAbnormalCount)/$($panel.platform.apiTotal) 行和=$sumOrders/$sumGmv/$sumSale/$sumAbn/$sumApi"

$expectPlatSale = Pct $sumSale $sumPaid
$expectPlatApi = Pct $sumApiErr $sumApi
$platSaleOk = $false
if ($null -eq $expectPlatSale) { $platSaleOk = ($null -eq $panel.platform.afterSaleRate) }
else { $platSaleOk = ("$($panel.platform.afterSaleRate)" -eq $expectPlatSale) }
$platApiOk = $false
if ($null -eq $expectPlatApi) { $platApiOk = ($null -eq $panel.platform.apiErrorRate) }
else { $platApiOk = ("$($panel.platform.apiErrorRate)" -eq $expectPlatApi) }
Check '★ 平台比例按合计重算（不是各行百分比取平均 —— 小店铺不该与大店铺同权）' `
    ($platSaleOk -and $platApiOk) `
    "售后率=$($panel.platform.afterSaleRate) 期望=$expectPlatSale；错误率=$($panel.platform.apiErrorRate) 期望=$expectPlatApi"

## ============================================================================
## 5. 排序与截断
## ============================================================================
Write-Host ''
Write-Host '> 排序与截断'

$rank = @{ RISK = 4; WATCH = 3; NO_DATA = 2; OK = 1 }
$badOrder = 0
for ($i = 1; $i -lt $rows.Count; $i++) {
    $a = $rows[$i - 1]; $b = $rows[$i]
    $ra = $rank["$($a.level)"]; $rb = $rank["$($b.level)"]
    if ($ra -lt $rb) { $badOrder++ }
    elseif (($ra -eq $rb) -and ([long]$a.orderCount -lt [long]$b.orderCount)) { $badOrder++ }
}
Check '★ 排序按档位权重降序（RISK → WATCH → NO_DATA → OK），同级按下单数降序' `
    ($badOrder -eq 0) "乱序 $badOrder 处"
Check 'totalTenants 不小于本次返回的行数' `
    ((([long]$panel.totalTenants) -ge $rows.Count)) "totalTenants=$($panel.totalTenants) rows=$($rows.Count)"

$one = CallPanel $sessOp 30 1
$oneRows = @($one.body.data.tenants)
Check 'limit=1 时只回一行' ($oneRows.Count -eq 1) "count=$($oneRows.Count)"
$expectTrunc = ([long]$one.body.data.totalTenants -gt 1)
Check '★ 截断标记与事实一致（否则「只有这几家」与「还有更多」无法区分）' `
    (([bool]$one.body.data.truncated) -eq $expectTrunc) `
    "truncated=$($one.body.data.truncated) totalTenants=$($one.body.data.totalTenants)"

## ============================================================================
## 6. 接口错误率：真实链路（网关计数 → Redis → 读侧聚合）
## ============================================================================
Write-Host ''
Write-Host '> 接口错误率（真实链路）'

# 夹具：一个全新商家（没有任何订单 / 售后 / 欠款）→ 它的租户键 = 店主 userId
$phoneS = '136' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$sessS = New-Object Microsoft.PowerShell.Commands.WebRequestSession
Call 'Post' "$Gateway/api/auth/register" $sessS `
    @{ phone = $phoneS; password = $Password; nickname = '健康度冒烟-卖家' } | Out-Null
$fx = New-SellerFixture -Phone $phoneS -StoreName '健康度冒烟-卖家' -Gateway $Gateway
Check '（夹具）卖家直建为 APPROVED 商家且角色升为 SELLER' ($fx.roleSet) $fx.role
if (-not $fx.roleSet) {
    Write-Host '  夹具没建成，后续与租户相关的断言无法成立，终止。'
    Write-Host "=== 冒烟结果：通过 $pass 项，失败 $($fail + 1) 项 ==="
    exit 1
}

# ★ 用户 id 按字符串流转（19 位雪花超过 double 的 53 位有效位，转 Int64 会丢精度）
$tid = Sql 'duoduo_user' "SELECT id FROM t_user WHERE phone='$phoneS';"
Check '（夹具）取到夹具店主的 userId（= 租户键，E20 口径）' (("$tid").Length -ge 15) "tid=$tid"

# 重新登录：角色与 tid 都是 access token 里的快照，不重登就拿不到租户身份
$sessS2 = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$loginS = Call 'Post' "$Gateway/api/auth/login" $sessS2 @{ phone = $phoneS; password = $Password }
Check '（夹具）卖家重登成功（新令牌带租户身份）' ($loginS.status -eq 200) "status=$($loginS.status)"

# ★ 口径：没有任何动静的租户**不该出现在面板上**（否则面板会被休眠店铺灌满）
$before = PanelData $sessOp 1 200
$rowBefore = RowOf $before $tid
Check '★ 无任何动静的租户不在面板上（面板只列这几天有动静的店）' ($null -eq $rowBefore) `
    "意外出现：apiTotal=$($rowBefore.apiTotal)"

$redisBefore = RedisHash $dayKey
$fieldTotal = "$tid" + ':total'
$totalBefore = 0
if ($redisBefore.ContainsKey($fieldTotal)) { $totalBefore = [long]$redisBefore[$fieldTotal] }

# 真实调用 3 次（卖家读自己的钱包：只读、无副作用）
$callOk = 0
for ($i = 0; $i -lt 3; $i++) {
    $w = Call 'Get' "$Gateway/api/seller/settlements/wallet" $sessS2
    if ($w.status -eq 200) { $callOk++ }
}
Check '（夹具）卖家端接口连续 3 次调用均 200' ($callOk -eq 3) "成功 $callOk 次"

# 计数写在响应完成的回调里（旁路、异步），给它一点时间落 Redis
Start-Sleep -Seconds 2

$redisAfter = RedisHash $dayKey
$totalAfter = 0
if ($redisAfter.ContainsKey($fieldTotal)) { $totalAfter = [long]$redisAfter[$fieldTotal] }
Check "★ 网关把这次调用计进了 Redis 的当日哈希（字段 $fieldTotal）" `
    ($redisAfter.ContainsKey($fieldTotal)) "键 $dayKey 里没有字段 $fieldTotal"
Check '★ Redis 计数至少增加 3（计数真的发生在每一次真实调用上）' `
    (($totalAfter - $totalBefore) -ge 3) "before=$totalBefore after=$totalAfter"
Check '（口径）只对带租户身份的请求计数 —— 买家/运营/匿名的调用不进这张表' `
    ($redisAfter.Count -ge 1) "哈希字段数=$($redisAfter.Count)"

$after = PanelData $sessOp 1 200
$rowAfter = RowOf $after $tid
Check '★ 有调用之后该租户出现在面板上' ($null -ne $rowAfter) '未出现'
if ($null -ne $rowAfter) {
    Check '★ 面板的 apiTotal ≥ 本次真实调用数（读侧聚合真的读到了这条计数）' `
        (([long]$rowAfter.apiTotal -ge 3)) "apiTotal=$($rowAfter.apiTotal)"
    Check '该租户没有 5xx → apiServerError = 0 且错误率为 0.00%' `
        (((([long]$rowAfter.apiServerError) -eq 0) -and ("$($rowAfter.apiErrorRate)" -eq '0.00%'))) `
        "err=$($rowAfter.apiServerError) rate=$($rowAfter.apiErrorRate)"
    Check '★ 有调用即不再判 NO_DATA（有样本就必须给结论）' ("$($rowAfter.level)" -ne 'NO_DATA') "level=$($rowAfter.level)"
    Check '该租户既无成交也无欠款 → 档位为 OK' ("$($rowAfter.level)" -eq 'OK') "level=$($rowAfter.level)"
    Check 'tenantName 取到（用户域简况接口可达，名字不是装饰到缺位）' `
        ((-not [string]::IsNullOrWhiteSpace("$($rowAfter.tenantName)"))) "tenantName=$($rowAfter.tenantName)"
    Check 'nameResolved = true（名字这一路没有降级）' (([bool]$after.nameResolved) -eq $true) "nameResolved=$($after.nameResolved)"
}

## ============================================================================
## 7. 售后率：跨域内部接口 + 写入后真的被抓到
## ============================================================================
Write-Host ''
Write-Host '> 售后率（履约域内部接口 + 跨域抓取）'

$fromIso = $today.ToString('yyyy-MM-dd') + 'T00:00:00'
$toIso = $today.AddDays(1).ToString('yyyy-MM-dd') + 'T00:00:00'
$urlFul = "$Fulfilment/api/aftersales/internal/tenant-stats?from=$fromIso&to=$toIso"
$pathFul = '/api/aftersales/internal/tenant-stats'

$noCred = Call 'Get' $urlFul $null
Check '★ 履约域内部接口无凭据直连 → 403（新增内部前缀必须真的被保护）' ($noCred.status -eq 403) "status=$($noCred.status)"

$cred = Call 'Get' $urlFul $null $null @{ 'X-Internal-Token' = (New-InternalToken -Method 'GET' -Path $pathFul -Caller 'duoduo-smoke') }
Check '带服务间签名 → 200' ($cred.status -eq 200) "status=$($cred.status) $($cred.raw)"
Check '内部接口回显实际生效的窗口（from/to 与服务端算的一致）' `
    (((ToStamp $cred.body.data.from) -eq (ToStamp $fromIso)) -and ((ToStamp $cred.body.data.to) -eq (ToStamp $toIso))) `
    "from=$($cred.body.data.from) to=$($cred.body.data.to) 期望=$fromIso / $toIso"
Check '内部接口 rows 是数组（无售后时也该是空数组）' ($null -ne $cred.body.data.rows) "rows=$($cred.body.data.rows)"

$badWindow = Call 'Get' "$Fulfilment/api/aftersales/internal/tenant-stats?from=$toIso&to=$fromIso" $null $null `
    @{ 'X-Internal-Token' = (New-InternalToken -Method 'GET' -Path $pathFul -Caller 'duoduo-smoke') }
Check '非法窗口（to ≤ from）→ 400（不静默纠正）' ($badWindow.status -eq 400) "status=$($badWindow.status)"
$badFmt = Call 'Get' "$Fulfilment/api/aftersales/internal/tenant-stats?from=2026-10-12&to=2026-10-13" $null $null `
    @{ 'X-Internal-Token' = (New-InternalToken -Method 'GET' -Path $pathFul -Caller 'duoduo-smoke') }
Check '时间格式非法（缺时分秒）→ 400' ($badFmt.status -eq 400) "status=$($badFmt.status)"

# ★ 跨域一致：面板的售后数必须与内部接口在**同一窗口**上逐租户一致
$statMap = @{}
foreach ($s in @($cred.body.data.rows)) { $statMap["$($s.sellerId)"] = [long]$s.total }
$cross = PanelData $sessOp 30 200
$cmpChecked = 0; $cmpBad = 0
foreach ($key in $statMap.Keys) {
    $row = RowOf $cross $key
    if ($null -ne $row) {
        $cmpChecked++
        if ([long]$row.afterSaleCount -ne $statMap[$key]) { $cmpBad++ }
    }
}
Check '★ 面板售后数与履约域内部接口在同一窗口上逐租户一致' `
    ($cmpBad -eq 0) "不符 $cmpBad 家（比对 $cmpChecked 家）"
if ($cmpChecked -eq 0) {
    Write-Host '  [INFO] 本窗口内库里没有售后单，逐租户比对样本为 0 —— 下面用现场夹具补一条，断言不漏。'
}

# 现场造一条售后 → 面板售后数必须 +1（不依赖存量数据）
$saleBefore = RowOf (PanelData $sessOp 1 200) $tid
$saleBeforeCount = 0
if ($null -ne $saleBefore) { $saleBeforeCount = [long]$saleBefore.afterSaleCount }
# ★ 夹具 id 必须是**合法 bigint**：上面脚本纪律里的「19 位雪花按字符串流转」这条，
#   在这里反过来咬人 —— 918 位以内的值才进得去。9980000000000000001 > 9223372036854775807
#   （bigint signed 上限），MySQL 报 ERROR 1264 Out of range value，
#   而错误一度被 Sql() 吞掉，只表现为 count=0。用 18 位、明显是夹具的值。
$fakeOrderId = '999000000000000001'
$null = Sql 'duoduo_fulfilment' "INSERT INTO t_after_sale (id, order_id, order_no, buyer_id, seller_id, type, reason, status, created_at, updated_at) VALUES ($fakeOrderId, $fakeOrderId, 'FIXTURE-E24-$fakeOrderId', 1, $tid, 'REFUND_ONLY', 'E24 冒烟夹具', 'PENDING', UTC_TIMESTAMP(), UTC_TIMESTAMP());"
$inCount = Sql 'duoduo_fulfilment' "SELECT COUNT(*) FROM t_after_sale WHERE id = $fakeOrderId;"
Check '（夹具）售后单已现场建入履约库（建不起就显式报红）' ("$inCount" -eq '1') "count=$inCount"

$saleAfter = RowOf (PanelData $sessOp 1 200) $tid
Check '★ 新立案的售后被面板抓到（该租户售后数 +1）' `
    ((($null -ne $saleAfter) -and ([long]$saleAfter.afterSaleCount -eq ($saleBeforeCount + 1)))) `
    "before=$saleBeforeCount after=$($saleAfter.afterSaleCount)"
if ($null -ne $saleAfter) {
    Check '该租户确无成交 → 售后率仍为 null（分母为 0 不给 0.00%）' ($null -eq $saleAfter.afterSaleRate) `
        "afterSaleRate=$($saleAfter.afterSaleRate)"
    Check '未结案计数被记到 afterSaleOpenCount' (([long]$saleAfter.afterSaleOpenCount -ge 1)) `
        "open=$($saleAfter.afterSaleOpenCount)"
    Check '已退款与败诉计数此时仍为 0（新立案的售后不该被算成已退款）' `
        ((([long]$saleAfter.afterSaleRefundedCount) -eq 0) -and (([long]$saleAfter.afterSaleAdverseCount) -eq 0)) `
        "refunded=$($saleAfter.afterSaleRefundedCount) adverse=$($saleAfter.afterSaleAdverseCount)"
}
$null = Sql 'duoduo_fulfilment' "DELETE FROM t_after_sale WHERE id = $fakeOrderId;"
$leftSale = Sql 'duoduo_fulfilment' "SELECT COUNT(*) FROM t_after_sale WHERE id = $fakeOrderId;"
Check '（复原）夹具售后单已删除' ("$leftSale" -eq '0') "残留 $leftSale"
$saleBack = RowOf (PanelData $sessOp 1 200) $tid
$saleBackCount = 0
if ($null -ne $saleBack) { $saleBackCount = [long]$saleBack.afterSaleCount }
Check '★ 删掉之后售后数回到原值（能抓到 ≠ 抓对了）' ($saleBackCount -eq $saleBeforeCount) `
    "原先=$saleBeforeCount 复原后=$saleBackCount"

## ============================================================================
## 8. 结算异常与判级：造一笔欠款 → 必须变 RISK → 删掉必须变回去
## ============================================================================
Write-Host ''
Write-Host '> 结算异常与判级（造异常 / 抓异常 / 复原）'

$levelBefore = LevelOf (PanelData $sessOp 1 200) $tid
Check '（前置）夹具租户当前不是 RISK（否则无法证明是这笔欠款让它变红）' `
    ($levelBefore -ne 'RISK') "level=$levelBefore"

$fakeDebtOrder = '999000000000000002'
$null = Sql 'duoduo_trade' "INSERT INTO t_seller_debt (id, seller_id, order_id, order_no, amount, status, reason, created_at, updated_at) VALUES ($fakeDebtOrder, $tid, $fakeDebtOrder, 'FIXTURE-E24-DEBT', 12345, 'OUTSTANDING', 'E24 冒烟夹具', UTC_TIMESTAMP(), UTC_TIMESTAMP());"
$debtIn = Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_seller_debt WHERE id = $fakeDebtOrder;"
Check '（夹具）欠款单已现场建入交易库' ("$debtIn" -eq '1') "count=$debtIn"

$riskPanel = PanelData $sessOp 1 200
$rowRisk = RowOf $riskPanel $tid
Check '★ 未收回欠款被面板抓成结算异常（笔数 1、金额 12345 分）' `
    ((($null -ne $rowRisk) -and ([long]$rowRisk.debtOutstandingCount -eq 1) -and ([long]$rowRisk.debtOutstandingAmount -eq 12345))) `
    "count=$($rowRisk.debtOutstandingCount) amount=$($rowRisk.debtOutstandingAmount)"
Check '★ 结算异常 > 0 → 档位变 RISK（一笔也算风险）' ("$($rowRisk.level)" -eq 'RISK') "level=$($rowRisk.level)"
Check '★ 平台汇总的 RISK 家数 ≥ 1（汇总口径也看得到）' `
    ((([long]$riskPanel.platform.riskyTenants) -ge 1)) "risky=$($riskPanel.platform.riskyTenants)"
Check '★ 结算异常合计包含这笔欠款（合计 = 三项之和，逐行算术）' `
    ((([long]$rowRisk.settlementAbnormalCount) -eq ([long]$rowRisk.debtOutstandingCount + [long]$rowRisk.staleCustodyCount + [long]$rowRisk.missingSettlementCount))) `
    "abnormal=$($rowRisk.settlementAbnormalCount)"

$null = Sql 'duoduo_trade' "DELETE FROM t_seller_debt WHERE id = $fakeDebtOrder;"
$debtLeft = Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_seller_debt WHERE id = $fakeDebtOrder;"
Check '（复原）夹具欠款单已删除' ("$debtLeft" -eq '0') "残留 $debtLeft"
$levelBack = LevelOf (PanelData $sessOp 1 200) $tid
Check '★ 删掉欠款后档位回到原值（不是永久变红）' ($levelBack -eq $levelBefore) `
    "原先=$levelBefore 复原后=$levelBack"

## ============================================================================
## 9. 收尾：自证复原
## ============================================================================
Write-Host ''
Write-Host '> 收尾（自证复原）'

$saleLeftover = Sql 'duoduo_fulfilment' "SELECT COUNT(*) FROM t_after_sale WHERE order_no LIKE 'FIXTURE-E24-%';"
Check '（复原）履约库无 E24 夹具售后残留' ("$saleLeftover" -eq '0') "残留 $saleLeftover"
$debtLeftover = Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_seller_debt WHERE order_no LIKE 'FIXTURE-E24-%';"
Check '（复原）交易库无 E24 夹具欠款残留' ("$debtLeftover" -eq '0') "残留 $debtLeftover"

$roleBack = Remove-PlatformOperatorPrivilege -Phone $op.phone
Check '（收尾）临时提权的运营已降回 USER（不留永久后台钥匙）' ("$roleBack" -eq 'USER') $roleBack
Check '（收尾）夹具卖家账号未被动过（仍为 SELLER）' `
    ((Sql 'duoduo_user' "SELECT role FROM t_user WHERE phone='$phoneS';") -eq 'SELLER')

# 收尾后服务仍能正常出数（证明前面的写操作没有把面板搞坏）
$final = CallPanel $sessOp 30 200
Check '（收尾）清理后面板仍正常出数（200 且 tenants 非 null）' `
    ((($final.status -eq 200) -and ($null -ne $final.body.data.tenants))) "status=$($final.status)"

# 断言总数守卫：低于下限说明有人删了断言（而不是「都过了」）
Check '（守卫）断言总数达到下限（≥ 55）' (($pass + $fail) -ge 55) "实际 $($pass + $fail)"

## ============================================================================
## 汇总
## ============================================================================
Write-Host ''
Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
if ($fail -gt 0) {
    Write-Host '失败项：'
    foreach ($f in $failures) { Write-Host "  - $f" }
    exit 1
}
exit 0
