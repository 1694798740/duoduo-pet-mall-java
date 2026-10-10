# 后台操作审计日志端到端冒烟（E18，Windows PowerShell 5.1）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\audit-log-smoke.ps1
#
# 前置：容器全部 healthy，且已部署 E18 镜像（docs/sql/incremental/31-e18-audit-log.sql 已执行）。
#
# 覆盖（对应 E18 验收要点）：
#   一、静态断言（不联网，扫仓库）
#     S1  E18 之前落库的 9 个动作标识在 AuditAction 里逐字存在（改名会让历史查询分叉）；
#     S2  全仓不存在对 t_audit_log 的 UPDATE / DELETE / TRUNCATE 语句；
#     S3  AuditLogMapper 接口不声明任何 update/delete 方法；
#     S4  后台族（/api/admin/** + /api/seller/staff/**）的写端点 @Audited 覆盖率 100%；
#     S5  只有 AuditLogService / LocalAuditSink 触碰 AuditLogMapper（防"绕过唯一入口自己 insert"）。
#   二、端到端
#     1  就绪；2  夹具：临时 ADMIN + 一个「入口是 admin 但角色是 USER」的对照账号；
#     3  成功留痕：改角色 → 库里多一条 SUCCESS，字段逐个核对（人/动作/类目/目标/租户/前后值/IP/链路）；
#     4  ★ 越权被拒也留痕：带 ent=admin 的 USER 令牌调后台 → 403，且多一条 FAILED
#        （这是"审计拦截器必须注册在权限拦截器之前"的唯一实测证据）；
#     5  ★ 业务规则拒绝记 FAILED 而不是 SUCCESS：非法角色名 → 422 → FAILED，
#        且 detail 带「未生效」——只看异常不看状态码时这里会变成 SUCCESS；
#     6  幂等抑制：把同一个用户再设成同一角色 → 不新增记录；
#     7  ★ 不带任何过滤条件的默认查询（打开审计页那一下）→ 200，
#        专门盯 `.eq(hasText(x), 列, x.trim())` 那个「条件为 false 也照样求值」的 NPE；
#     8  按动作 / 按类目 / 按人过滤都能命中刚写的记录；
#     9  起止时间颠倒 → 400（不是静默返回空）；时间格式错 → 400；
#    10  检索端点判权：USER 令牌 → 403（前端不渲染入口只是体验，不是边界）；
#    11  immutable 下发：SECURITY 记录为 true、CONTENT 记录为 false（两个方向都断言）；
#    12  平台级动作的 target_id 下发为 "0"；落库时间接近当前 UTC（不是本地时区）；
#    13  收尾自证：临时管理员降回 USER + 全库 ADMIN 数不变 + 夹具账号刷新令牌吊销。
#
# 设计原则（沿用 E27 定稿的夹具三纪律）：
#   1. **现场建 / 跑完自毁 / 建不起来就显式报红** —— 提权失败必须报红，
#      否则后面所有断言都会因为"对方本来就没权限"而静默通过（假绿）。
#   2. **对照而非孤立断言**：403 那条与成功那条走的是同一个端点、同一个动作，
#      唯一差别是调用者的权限档位，差异不可能来自别的原因。
#   3. 断言失败一律带 status；只有 reply 分不清"答了别的话"与"根本没拿到响应"。
#   4. **期望值从库内实算**，不写死条数（写死条数钉的是"当时有几条数据"，环境一变就红）。
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    [string]$Password = 'duoduo12345'
)

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
# ★ 读 `docker exec mysql` 的中文要临时把控制台编码切成 UTF-8：PowerShell 5.1 用**控制台代码页**
#   （简中环境为 GBK）解码外部命令的 stdout，而 mysql 回传的是 UTF-8 —— 不切的话读出来是乱码
#   （「未生效」变成「鏈敓鏁」），`-like '*未生效*'` 这类断言会**永远为假**：
#   看起来像"功能没生效"，其实是"我们把它读错了"。
#   ★★ 但**只能围着那几条命令切、切完立刻切回去**，绝不能在这里全局设一次：
#   `[Console]::OutputEncoding` 同时也决定**我们自己** Write-Host 的输出编码 ——
#   全局设成 UTF-8 之后，`run-all-smoke.ps1` 捕获本进程 stdout 时按控制台代码页去解，
#   我们打印的中文全变乱码写进 `logs/smoke/audit-log-smoke.log`，
#   连 "=== 冒烟结果：通过 X 项 ===" 那行都匹配不上，汇总里出现「(无汇总行)」。
#   （这是本项目第三次踩输出编码；教训一致：**范围收得越小越安全**。）
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

function Call([string]$method, [string]$uri, $bodyObj, [hashtable]$headers) {
    $params = @{ Uri = $uri; Method = $method; UseBasicParsing = $true; TimeoutSec = 30 }
    if ($headers) { $params.Headers = $headers }
    if ($null -ne $bodyObj) {
        # 用 -InputObject 而不是管道：管道会枚举数组，把单元素数组拆成裸对象
        $params.Body = (ConvertTo-Json -InputObject $bodyObj -Compress -Depth 6)
        $params.ContentType = 'application/json; charset=utf-8'
    }
    try {
        $r = Invoke-WebRequest @params
        return @{ status = [int]$r.StatusCode; body = ($r.Content | ConvertFrom-Json); raw = $r.Content }
    } catch {
        $code = 0; $body = $null; $raw = $null
        try { $code = [int]$_.Exception.Response.StatusCode.value__ } catch { }
        try { if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $raw = $_.ErrorDetails.Message; $body = $raw | ConvertFrom-Json } } catch { }
        if ($null -eq $body) {
            try {
                $resp = $_.Exception.Response
                if ($resp -and $resp.PSObject.Methods.Name -contains 'GetResponseStream') {
                    $st = $resp.GetResponseStream()
                    if ($st) { $raw = (New-Object System.IO.StreamReader($st)).ReadToEnd() }
                } elseif ($resp -and $null -ne $resp.Content) {
                    $raw = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                }
                if ($raw) { $body = $raw | ConvertFrom-Json }
            } catch { }
        }
        return @{ status = $code; body = $body; raw = $raw }
    }
}

function Sql([string]$db, [string]$sql) {
    # 编码只在这一次调用期间切到 UTF-8（理由见文件头 ★★），调用结束立刻还原
    $prevEnc = [Console]::OutputEncoding
    [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false
    try {
        $out = & docker exec duoduo-mysql mysql -uduoduo -pduoduo -N -B `
            --default-character-set=utf8mb4 -D $db -e $sql 2>&1
        $code = $LASTEXITCODE
    } finally {
        [Console]::OutputEncoding = $prevEnc
    }
    if ($code -ne 0) { return $null }
    return ((@($out) | Where-Object { "$_" -notmatch 'Warning' }) -join "`n").Trim()
}

function SqlRoot([string]$db, [string]$sql) {
    $prevEnc = [Console]::OutputEncoding
    [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false
    try {
        $out = & docker exec duoduo-mysql mysql -uroot -pduoduo_root -N -B `
            --default-character-set=utf8mb4 -D $db -e $sql 2>&1
        $code = $LASTEXITCODE
    } finally {
        [Console]::OutputEncoding = $prevEnc
    }
    if ($code -ne 0) { return $null }
    return ((@($out) | Where-Object { "$_" -notmatch 'Warning' }) -join "`n").Trim()
}

function Bearer([string]$token) {
    return @{ 'Authorization' = "Bearer $token" }
}

<#
.SYNOPSIS
  从调用结果里取条目数组；任何一层缺失都返回**真正的空数组**。
.DESCRIPTION
  ★ 这是本轮实测踩到的假通过来源：PowerShell 里 @($null) 的 Count 是 **1**，
  所以 `@($resp.body.data.items).Count -gt 0` 在「请求压根没发出去、body 为 null」时
  也为真 —— 它恰好出现在「验证过滤条件真的生效了」这类最需要真信号的断言上，
  表现是「过滤功能看起来全对，其实一次都没查到」。凡是要数条数的地方一律走本函数。
#>
function Items($resp) {
    if ($null -eq $resp -or $null -eq $resp.body -or $null -eq $resp.body.data -or $null -eq $resp.body.data.items) {
        return @()
    }
    return @($resp.body.data.items)
}

# 审计表的单行取值（避免把整行按分隔符切开时踩到 detail 里的制表符）
function AuditCell([string]$id, [string]$column) {
    return (Sql 'duoduo_user' "SELECT $column FROM t_audit_log WHERE id=$id;")
}

. (Join-Path $PSScriptRoot 'lib\platform-operator.ps1')

$repo = Split-Path -Parent $PSScriptRoot
$actorPhone = '139' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$viewerPhone = '138' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$targetPhone = '137' + (Get-Random -Minimum 10000000 -Maximum 99999999)

Write-Host ""
Write-Host "=== 多多宠物商店 · 后台操作审计日志冒烟（E18） @ $Gateway ==="
Write-Host "临时管理员 = $actorPhone   对照账号(ent=admin,角色USER) = $viewerPhone   被改的人 = $targetPhone"
Write-Host ""

# ============================================================================
# 一、静态断言（扫仓库；不需要服务在跑，但坏消息必须早暴露，所以放在最前面）
# ============================================================================
Write-Host "> 一、静态断言（仓库级）"

$actionFile = Join-Path $repo 'duoduo-common/src/main/java/com/duoduo/common/audit/AuditAction.java'
$actionText = Get-Content -Raw -LiteralPath $actionFile

# S1 · E18 之前已落库的 9 个动作标识逐字保留。
# 这条断言防的是「顺手规整命名」：USER_ROLE_CHANGE 改成 USER_ROLE_CHANGED 之后，
# 「这个人被改过几次角色」的查询会只返回一半，而两处代码看起来都是对的。
$legacy = @('USER_ROLE_CHANGE', 'MERCHANT_LICENSE_SUSPEND', 'MERCHANT_VIOLATION_SUSPEND',
    'MERCHANT_MATERIAL_READ', 'INVITE_STAFF', 'ENABLE_STAFF', 'DISABLE_STAFF',
    'ADMIN_ENABLE_STAFF', 'ADMIN_DISABLE_STAFF')
$missingLegacy = @($legacy | Where-Object { $actionText -notmatch ("(?m)^\s*" + $_ + "\(") })
Check "S1 历史动作标识逐字保留（$($legacy.Count) 个）" ($missingLegacy.Count -eq 0) `
    "缺失或改名：$($missingLegacy -join ', ')"

# S2 · 全仓不存在对审计表的 UPDATE / DELETE / TRUNCATE。
# 「审计的完整性靠没有任何修改路径来保证，而不是靠没人会去改」——
# 将来有人顺手加一个「清理端点」，这条会立刻红。
$sqlFiles = @(Get-ChildItem -Path $repo -Recurse -Include '*.sql','*.java','*.xml','*.yml','*.yaml' -File |
    Where-Object { $_.FullName -notmatch '\\target\\' })
$mutating = @()
foreach ($f in $sqlFiles) {
    $t = Get-Content -Raw -LiteralPath $f.FullName
    if ($t -match '(?is)\bUPDATE\s+`?t_audit_log`?' -or `
        $t -match '(?is)\bDELETE\s+FROM\s+`?t_audit_log`?' -or `
        $t -match '(?is)\bTRUNCATE\s+(TABLE\s+)?`?t_audit_log`?') {
        $mutating += $f.FullName.Substring($repo.Length + 1)
    }
}
Check "S2 全仓不存在 t_audit_log 的 UPDATE/DELETE/TRUNCATE" ($mutating.Count -eq 0) `
    "命中：$($mutating -join ' | ')"

# S3 · Mapper 接口不声明修改方法（应用层没有任何删除/修改入口）。
$mapperFile = Join-Path $repo 'duoduo-user/src/main/java/com/duoduo/user/mapper/AuditLogMapper.java'
$mapperText = Get-Content -Raw -LiteralPath $mapperFile
$mapperMutators = @(([regex]::Matches($mapperText, '\b(update|delete|remove)[A-Za-z]*\s*\(')) |
    ForEach-Object { $_.Value.Trim() } | Sort-Object -Unique)
Check "S3 AuditLogMapper 无 update/delete 方法声明" ($mapperMutators.Count -eq 0) `
    "命中：$($mapperMutators -join ', ')"

# S4 · 后台族写端点 @Audited 覆盖率。
# 注解式留痕的失效方向是「静默漏记」：新加一个写端点忘了加注解，接口一切正常、
# 只是不在审计表里，没有任何报错。所以覆盖率必须由脚本算，不能靠人工比对。
$scopeRegex = '@RequestMapping\("/api/(admin|seller/staff)'
$controllers = @(Get-ChildItem -Path $repo -Recurse -Filter '*Controller.java' -File |
    Where-Object { $_.FullName -notmatch '\\target\\' })
$writeTotal = 0
$annotated = 0
$gaps = @()
foreach ($c in $controllers) {
    $t = Get-Content -Raw -LiteralPath $c.FullName
    if ($t -notmatch $scopeRegex) { continue }
    $w = ([regex]::Matches($t, '@(Post|Put|Patch|Delete)Mapping')).Count
    $a = ([regex]::Matches($t, '@Audited\(')).Count
    $writeTotal += $w
    $annotated += $a
    if ($w -ne $a) { $gaps += "$($c.Name)(写=$w 注解=$a)" }
}
Check "S4 后台写端点 @Audited 覆盖 $annotated/$writeTotal（逐文件相等）" `
    ($writeTotal -gt 0 -and $annotated -eq $writeTotal) `
    "不匹配：$($gaps -join ' | ')"

# S5 · 只有唯一入口能碰审计 mapper。
# 绕过入口自己 insert 的表现是「同一张表里的记录信息量不一致」：有的有 target、有的没有，
# 而新增维度时要改 N 处、漏一处不报错 —— 只会让那一类动作在审计页上永远缺列。
$allowedTouchers = @('AuditLogService.java', 'LocalAuditSink.java', 'AuditLogMapper.java')
$touchers = @(Get-ChildItem -Path (Join-Path $repo 'duoduo-user/src/main/java') -Recurse -Filter '*.java' -File |
    Where-Object { $_.FullName -notmatch '\\target\\' -and $_.Name -notin $allowedTouchers } |
    Where-Object { (Get-Content -Raw -LiteralPath $_.FullName) -match 'AuditLogMapper' } |
    ForEach-Object { $_.Name })
Check "S5 只有唯一入口（AuditLogService / LocalAuditSink）触碰 AuditLogMapper" ($touchers.Count -eq 0) `
    "越界引用：$($touchers -join ', ')"

# ============================================================================
# 二、端到端
# ============================================================================
Write-Host "> 0. 就绪检查"
$ready = (Call 'Get' "$Gateway/api/categories" $null $null).status -eq 200
Check "网关可达（/api/categories → 200）" $ready "网关未就绪，后续断言无意义"
if (-not $ready) {
    Write-Host "  [ABORT] 网关不可达，终止"
    Write-Host ""
    Write-Host "=== 冒烟结果：通过 $pass 项，失败 $($fail + 1) 项 ==="
    exit 1
}

$auditUrl = "$Gateway/api/admin/audit-logs"
$adminBefore = [int](Sql 'duoduo_user' "SELECT COUNT(*) FROM t_user WHERE role='ADMIN';")
$auditRowsBefore = [int](Sql 'duoduo_user' "SELECT COUNT(*) FROM t_audit_log;")

# ---------- 1. 夹具 ----------
Write-Host "> 1. 夹具"
$actorId = $null; $viewerId = $null; $targetId = $null

Call 'Post' "$Gateway/api/auth/register" @{ phone = $actorPhone; password = $Password; nickname = '审计夹具-管理员' } | Out-Null
$roleSet = $false
for ($i = 0; $i -lt 5; $i++) {
    SqlRoot 'duoduo_user' "UPDATE t_user SET role='ADMIN' WHERE phone='$actorPhone';" | Out-Null
    Start-Sleep -Milliseconds 200
    $row = SqlRoot 'duoduo_user' "SELECT role FROM t_user WHERE phone='$actorPhone';"
    if ($row -eq 'ADMIN') { $roleSet = $true; break }
}
# ★ 建不起来必须报红：带着 USER 角色去改角色只会 403，而报错看起来像"越权被正确拦下"，
#   后续断言会因为"什么都没变"而静默通过 —— 这是 E27 定下的夹具纪律
Check "夹具：临时管理员已就位（库内 role=ADMIN）" $roleSet "role=$row"
$actorId = SqlRoot 'duoduo_user' "SELECT id FROM t_user WHERE phone='$actorPhone';"

$aLogin = Call 'Post' "$Gateway/api/auth/login/token" @{ phone = $actorPhone; password = $Password }
$actorToken = $aLogin.body.data.accessToken
Check "夹具：临时管理员登录并拿到访问令牌" `
    ($aLogin.status -eq 200 -and -not [string]::IsNullOrWhiteSpace($actorToken)) `
    "status=$($aLogin.status) raw=$($aLogin.raw)"

# 对照账号：**入口声明是 admin、但角色仍是 USER**。
# 没有它就只能测"网关入口不匹配 → 403"，那层 403 发生在网关，服务端根本收不到请求，
# 也就永远测不到「越权被拒也留痕」。带上 X-Duoduo-Entry: admin 之后，
# 请求会穿过网关到达服务端，由 PermissionInterceptor 拒绝 —— 那一次才会留痕。
Call 'Post' "$Gateway/api/auth/register" @{ phone = $viewerPhone; password = $Password; nickname = '审计夹具-无权限访客' } | Out-Null
$vLogin = Call 'Post' "$Gateway/api/auth/login/token" `
    @{ phone = $viewerPhone; password = $Password } @{ 'X-Duoduo-Entry' = 'admin' }
$viewerToken = $vLogin.body.data.accessToken
$viewerId = SqlRoot 'duoduo_user' "SELECT id FROM t_user WHERE phone='$viewerPhone';"
Check "夹具：对照账号登录（ent=admin，角色 USER）" `
    ($vLogin.status -eq 200 -and -not [string]::IsNullOrWhiteSpace($viewerToken)) `
    "status=$($vLogin.status) raw=$($vLogin.raw)"
Check "夹具：对照账号角色为 USER" `
    ((SqlRoot 'duoduo_user' "SELECT role FROM t_user WHERE id=$viewerId;") -eq 'USER') `
    (SqlRoot 'duoduo_user' "SELECT role FROM t_user WHERE id=$viewerId;")

Call 'Post' "$Gateway/api/auth/register" @{ phone = $targetPhone; password = $Password; nickname = '审计夹具-被改的人' } | Out-Null
$targetId = SqlRoot 'duoduo_user' "SELECT id FROM t_user WHERE phone='$targetPhone';"
Check "夹具：被改的人已注册（role=USER）" `
    ((SqlRoot 'duoduo_user' "SELECT role FROM t_user WHERE id=$targetId;") -eq 'USER') `
    (SqlRoot 'duoduo_user' "SELECT role FROM t_user WHERE id=$targetId;")

# ---------- 2. 成功留痕 ----------
Write-Host "> 2. 成功路径留痕"
$up = Call 'Post' "$Gateway/api/admin/users/$targetId/role" @{ role = 'SELLER' } (Bearer $actorToken)
Check "平台管理员改角色成功（200）" ($up.status -eq 200) "status=$($up.status) raw=$($up.raw)"

$newId = Sql 'duoduo_user' "SELECT id FROM t_audit_log WHERE action='USER_ROLE_CHANGE' AND target_id=$targetId ORDER BY created_at DESC, id DESC LIMIT 1;"
Check "库里出现这次角色变更的审计记录" (-not [string]::IsNullOrWhiteSpace($newId)) "newId=$newId"

# 单行构造（PowerShell 5.1 对「跨行括号内字符串拼接」的解析很挑，单行最稳）
$auditCols = "CONCAT_WS('|', actor_id, action, category, outcome, target_id, target_type, tenant_id, before_value, after_value, IFNULL(ip,''), IFNULL(request_id,''))"
$row = Sql 'duoduo_user' "SELECT $auditCols FROM t_audit_log WHERE id=$newId;"
$p = @("$row" -split '\|')
Check "审计字段齐全：actor=临时管理员" ($p.Count -ge 11 -and $p[0] -eq "$actorId") "row=$row"
Check "动作标识 = USER_ROLE_CHANGE（历史标识不变）" ($p[1] -eq 'USER_ROLE_CHANGE') "action=$($p[1])"
Check "★ 类目 = SECURITY（权限类，不可删改档）" ($p[2] -eq 'SECURITY') "category=$($p[2])"
Check "结果 = SUCCESS" ($p[3] -eq 'SUCCESS') "outcome=$($p[3])"
Check "目标 = 被改的人（id=$targetId）" ($p[4] -eq "$targetId") "target_id=$($p[4])"
Check "目标类型默认取自动作定义 = USER" ($p[5] -eq 'USER') "target_type=$($p[5])"
Check "★ 租户 = 0（角色是平台级概念，不属于任何店铺）" ($p[6] -eq '0') "tenant_id=$($p[6])"
Check "前后值记下了 USER -> SELLER" ($p[7] -eq 'USER' -and $p[8] -eq 'SELLER') "before=$($p[7]) after=$($p[8])"
Check "IP 非空（网关透传 X-Real-IP）" (-not [string]::IsNullOrWhiteSpace($p[9])) "ip=[$($p[9])]"
Check "链路 ID 非空（可与访问日志对上）" (-not [string]::IsNullOrWhiteSpace($p[10])) "request_id=[$($p[10])]"

$createdUtc = Sql 'duoduo_user' "SELECT created_at FROM t_audit_log WHERE id=$newId;"
$ageMin = [int](Sql 'duoduo_user' "SELECT TIMESTAMPDIFF(MINUTE, created_at, UTC_TIMESTAMP()) FROM t_audit_log WHERE id=$newId;")
Check "★ 落库时间是 UTC 且就是刚才（差 $ageMin 分钟；差 480 说明写成了本地时区）" `
    ($ageMin -ge 0 -and $ageMin -le 5) "created_at=$createdUtc age=$ageMin 分钟"

# ---------- 3. ★ 越权被拒也留痕 ----------
Write-Host "> 3. 越权被拒也留痕"
$denied = Call 'Post' "$Gateway/api/admin/users/$targetId/role" @{ role = 'OPERATOR' } (Bearer $viewerToken)
Check "对照账号（USER）调后台写端点 → 403" ($denied.status -eq 403) "status=$($denied.status) raw=$($denied.raw)"

$deniedId = Sql 'duoduo_user' "SELECT id FROM t_audit_log WHERE actor_id=$viewerId AND action='USER_ROLE_CHANGE' AND outcome='FAILED' ORDER BY created_at DESC, id DESC LIMIT 1;"
# ★ 这条是「审计拦截器必须注册在权限拦截器之前」的唯一实测证据：
#   排在权限之后的话，越权请求的 afterCompletion 根本不会被回调，这里就查不到记录。
#   而「连续失败的提权尝试」恰恰是审计最该看见的信号。
Check "★ 越权被拒也留下了一条 FAILED 记录（审计排在权限之前）" `
    (-not [string]::IsNullOrWhiteSpace($deniedId)) "deniedId=$deniedId"
$deniedDetail = Sql 'duoduo_user' "SELECT detail FROM t_audit_log WHERE id=$deniedId;"
Check "失败记录的摘要写明未生效" ("$deniedDetail" -like '*未生效*') "detail=$deniedDetail"
# 越权请求在**业务代码执行之前**就被权限拦截器拒绝，因此业务层没有机会调 AuditTrail.target(...)
# —— target_id 必然是 0。这不是缺陷，而是「注解采集不到的信息只能由业务补充」这件事的必然结果：
# 既然业务没跑，就没有信息可补。
# 目标靠 detail 里的完整路径来体现（/api/admin/users/{id}/role），那条路径本身带 id；
# 断言这一点而不是断言 target_id，才是对**真实保证**的检查。
$deniedRow = Sql 'duoduo_user' "SELECT CONCAT_WS('|', target_id, target_type) FROM t_audit_log WHERE id=$deniedId;"
Check "被拒记录的 target_id 为 0（业务未执行，无从得知目标）" `
    (("$deniedRow" -split '\|')[0] -eq '0') "target/type=$deniedRow"
Check "★ 目标信息由 detail 里的完整请求路径承载（含目标 id=$targetId，事后仍可追责）" `
    ("$deniedDetail" -like "*users/$targetId/*") "detail=$deniedDetail"

# ---------- 4. ★ 业务规则拒绝记 FAILED（只看异常会记成 SUCCESS） ----------
Write-Host "> 4. 业务规则拒绝同样记 FAILED"
$before422 = [int](Sql 'duoduo_user' "SELECT COUNT(*) FROM t_audit_log;")
$bad = Call 'Post' "$Gateway/api/admin/users/$targetId/role" @{ role = 'KING' } (Bearer $actorToken)
Check "非法角色名 → 422（业务规则）" ($bad.status -eq 422) "status=$($bad.status) raw=$($bad.raw)"
$badId = Sql 'duoduo_user' "SELECT id FROM t_audit_log WHERE outcome='FAILED' ORDER BY created_at DESC, id DESC LIMIT 1;"
Check "★ 被业务规则拒绝的动作记为 FAILED（不是 SUCCESS）" `
    (-not [string]::IsNullOrWhiteSpace($badId)) "id=$badId"
$badOutcome = Sql 'duoduo_user' "SELECT outcome FROM t_audit_log WHERE id=$badId;"
Check "该条结果的字面量就是 FAILED" ("$badOutcome" -eq 'FAILED') "outcome=$badOutcome"
$badDetail = Sql 'duoduo_user' "SELECT detail FROM t_audit_log WHERE id=$badId;"
Check "★ 摘要带 HTTP 状态码，读记录的人不会把它误读成成功" ("$badDetail" -like '*未生效*' -and "$badDetail" -like '*422*') "detail=$badDetail"
$after422 = [int](Sql 'duoduo_user' "SELECT COUNT(*) FROM t_audit_log;")
Check "这次被拒的动作确实新增了记录（$before422 → $after422）" ($after422 -eq $before422 + 1) `
    "before=$before422 after=$after422"

# ---------- 5. 幂等抑制 ----------
Write-Host "> 5. 幂等抑制（无事实变化不留痕）"
$beforeIdem = [int](Sql 'duoduo_user' "SELECT COUNT(*) FROM t_audit_log;")
$same = Call 'Post' "$Gateway/api/admin/users/$targetId/role" @{ role = 'SELLER' } (Bearer $actorToken)
Check "再把同一用户设成同一角色 → 200（幂等成功）" ($same.status -eq 200) "status=$($same.status) raw=$($same.raw)"
$afterIdem = [int](Sql 'duoduo_user' "SELECT COUNT(*) FROM t_audit_log;")
# ★ 「重复确认同一状态」不构成事件：写进去只会稀释信噪比，让真正的变更被淹没。
#   这条断言与上面那条（被拒也要留痕）方向相反，但两者缺一不可 ——
#   只测「必须留痕」会让人把抑制口当成 bug 删掉，只测抑制会丢掉越权信号。
Check "★ 幂等确认不新增审计记录（$beforeIdem → $afterIdem）" ($afterIdem -eq $beforeIdem) `
    "before=$beforeIdem after=$afterIdem"
Check "幂等响应文案说明未做变更" ("$($same.body.data.notice)" -like '*未做变更*') $same.body.data.notice

# ---------- 6. ★ 默认查询（打开审计页那一下）----------
Write-Host "> 6. 检索端点"
$def = Call 'Get' "$auditUrl" $null (Bearer $actorToken)
Check "★ 不带任何过滤条件的默认查询 → 200（不做无过滤条件的 NPE 回归）" `
    ($def.status -eq 200) "status=$($def.status) raw=$($def.raw)"
Check "默认查询返回分页结构" ($null -ne $def.body.data -and $null -ne $def.body.data.items) `
    "data=$($def.body.data)"

$byAction = Call 'Get' "${auditUrl}?action=USER_ROLE_CHANGE&actorId=$actorId&pageSize=50" $null (Bearer $actorToken)
Check "按动作 + 按人过滤 → 200" ($byAction.status -eq 200) "status=$($byAction.status) raw=$($byAction.raw)"
$actionItems = Items $byAction
Check "★ 过滤结果里能查到刚写的那条（id=$newId）" `
    ($actionItems.Count -gt 0 -and (@($actionItems | ForEach-Object { "$($_.id)" }) -contains "$newId")) `
    "count=$($actionItems.Count) items=$(@($actionItems | ForEach-Object { $_.id }) -join ',')"

$byCat = Call 'Get' "${auditUrl}?category=security" $null (Bearer $actorToken)
Check "按类目过滤（小写 security 也应命中，服务端归一化）→ 200" ($byCat.status -eq 200) `
    "status=$($byCat.status) raw=$($byCat.raw)"
$catItems = Items $byCat
Check "该类目下返回了记录且全部 category=SECURITY" `
    ($catItems.Count -gt 0 -and @($catItems | Where-Object { $_.category -ne 'SECURITY' }).Count -eq 0) `
    "status=$($byCat.status) count=$($catItems.Count)"
Check "★ immutable 下发：SECURITY 记录全部为 true" `
    ($catItems.Count -gt 0 -and @($catItems | Where-Object { $_.immutable -ne $true }).Count -eq 0) `
    "count=$($catItems.Count) bad=$(@($catItems | Where-Object { $_.immutable -ne $true } | ForEach-Object { $_.id }) -join ',')"

$plan = Call 'Get' "${auditUrl}?targetId=0&pageSize=5" $null (Bearer $actorToken)
Check "按目标过滤（平台级 target_id=0）→ 200" ($plan.status -eq 200) "status=$($plan.status) raw=$($plan.raw)"

# ---------- 7. 时间参数口径 ----------
Write-Host "> 7. 时间参数口径"
$reversed = Call 'Get' "${auditUrl}?from=2026-12-31&to=2026-01-01" $null (Bearer $actorToken)
# 返回空列表会让人以为"这段时间真的没发生什么"，而实际只是两个参数填反了 ——
# 那是最容易让人带着错误结论离开的一种失败方式
Check "★ 起止颠倒 → 400（不是静默返回空）" ($reversed.status -eq 400) `
    "status=$($reversed.status) raw=$($reversed.raw)"
$badTime = Call 'Get' "${auditUrl}?from=2026/10/09" $null (Bearer $actorToken)
Check "时间格式错 → 400（不是静默忽略该条件）" ($badTime.status -eq 400) `
    "status=$($badTime.status) raw=$($badTime.raw)"

$today = (Get-Date).ToUniversalTime().ToString('yyyy-MM-dd')
$todayQ = Call 'Get' "${auditUrl}?from=$today&to=$today&actorId=$actorId&pageSize=50" $null (Bearer $actorToken)
Check "只给到「日」的时间范围 → 200" ($todayQ.status -eq 200) "status=$($todayQ.status) raw=$($todayQ.raw)"
$todayItems = Items $todayQ
Check "★ 止端按「含」：今天写的记录能被查出来（写死 < to 会漏掉今天一整天）" `
    ($todayItems.Count -gt 0 -and (@($todayItems | ForEach-Object { "$($_.id)" }) -contains "$newId")) `
    "today=$today count=$($todayItems.Count) items=$(@($todayItems | ForEach-Object { $_.id }) -join ',')"

# ---------- 8. 判权 ----------
Write-Host "> 8. 检索端点判权"
$reader = Call 'Get' "$auditUrl" $null (Bearer $viewerToken)
Check "★ USER 令牌读审计 → 403（前端不渲染入口只是体验，不是边界）" ($reader.status -eq 403) `
    "status=$($reader.status) raw=$($reader.raw)"

# ---------- 9. immutable 的另一半：CONTENT 档为 false ----------
Write-Host "> 9. immutable 判据的两个方向"
$reindex = Call 'Post' "$Gateway/api/admin/search/reindex" $null (Bearer $actorToken)
Check "平台管理员触发全站索引重建 → 200" ($reindex.status -eq 200) "status=$($reindex.status) raw=$($reindex.raw)"
$reindexId = Sql 'duoduo_user' "SELECT id FROM t_audit_log WHERE action='SEARCH_REINDEX' ORDER BY created_at DESC, id DESC LIMIT 1;"
Check "平台级动作也留痕（action=SEARCH_REINDEX）" (-not [string]::IsNullOrWhiteSpace($reindexId)) "id=$reindexId"
Check "★ 平台级动作的 target_id 落库为 0（不是 NULL）" `
    ((Sql 'duoduo_user' "SELECT target_id FROM t_audit_log WHERE id=$reindexId;") -eq '0') `
    (Sql 'duoduo_user' "SELECT target_id FROM t_audit_log WHERE id=$reindexId;")

$contentQ = Call 'Get' "${auditUrl}?category=CONTENT&action=SEARCH_REINDEX&pageSize=5" $null (Bearer $actorToken)
$contentItems = Items $contentQ
Check "按 CONTENT + SEARCH_REINDEX 过滤能查到" ($contentItems.Count -gt 0) `
    "status=$($contentQ.status) count=$($contentItems.Count) raw=$($contentQ.raw)"
Check "★ immutable 下发：CONTENT 记录为 false（与 SECURITY 那条形成两个方向的对照）" `
    ($contentItems.Count -gt 0 -and @($contentItems | Where-Object { $_.immutable -ne $false }).Count -eq 0) `
    "count=$($contentItems.Count) bad=$(@($contentItems | Where-Object { $_.immutable -ne $false } | ForEach-Object { $_.id }) -join ',')"

# ---------- 10. 收尾自证 ----------
Write-Host "> 10. 收尾"
$actorRoleBack = Remove-PlatformOperatorPrivilege -Phone $actorPhone
Check "临时管理员已降回 USER" ($actorRoleBack -eq 'USER') "role=$actorRoleBack"

$adminAfter = [int](Sql 'duoduo_user' "SELECT COUNT(*) FROM t_user WHERE role='ADMIN';")
Check "★ 全库 ADMIN 数量与开跑前一致（没有留下提权账号）" ($adminAfter -eq $adminBefore) `
    "before=$adminBefore after=$adminAfter"

if ($actorId -and $viewerId -and $targetId) {
    Sql 'duoduo_user' "UPDATE t_refresh_token SET revoked=1 WHERE user_id IN ($actorId, $viewerId, $targetId);" | Out-Null
}
# 审计记录**刻意不清理**：它本来就是"留下痕迹"这件事的产物，
# 把它删掉等于为了让测试变干净而破坏被测对象（真正的清理要走单独的归档流程，
# 且必须先过 AuditCategory.immutable() 那道判据）。
$auditRowsAfter = [int](Sql 'duoduo_user' "SELECT COUNT(*) FROM t_audit_log;")
Check "★ 本轮确实写进了审计表（$auditRowsBefore → $auditRowsAfter，且记录保留不删）" `
    ($auditRowsAfter -gt $auditRowsBefore) "before=$auditRowsBefore after=$auditRowsAfter"

Write-Host ""
$declared = ([regex]::Matches((Get-Content -Raw -LiteralPath $PSCommandPath), '(?m)^\s*Check "')).Count
$ran = $pass + $fail
if ($ran -ne $declared) {
    Write-Host "  [GUARD-FAIL] 断言总数守卫：脚本内声明 $declared 项，实际执行 $ran 项 —— 有 Check 被静默跳过（多半是调用写法/参数写错）"
    $fail++
    $failures += "断言总数守卫（实际执行 $ran 项 / 声明 $declared 项）"
} else {
    Write-Host "  [GUARD-OK] 断言总数守卫：$ran 项全部执行，无静默跳过"
}
Write-Host ""
Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
if ($fail -gt 0) {
    Write-Host "失败项："
    foreach ($f in $failures) { Write-Host "  - $f" }
    exit 1
}
exit 0
