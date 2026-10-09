# 权限即时生效（permVersion）端到端冒烟（E17，Windows PowerShell 5.1）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\perm-version-smoke.ps1
#
# 前置：容器全部 healthy，且已部署 E17 镜像（docs/sql/incremental/30-e17-perm-version.sql 已执行）。
#
# 覆盖（对应 E17 验收要点）：
#   1. 令牌带版本：登录拿到的访问令牌里 pv 与库内 perm_version 一致；
#   2. 版本落后即失效：改角色后用**旧访问令牌**调后台接口 → 401（不是 403），
#      且 code = PERM_VERSION_STALE（可区分"权限被改了"与"登录过期了"）；
#   3. ★ 无需重新登录：用同一个刷新令牌刷新一次 → 新令牌带新角色 → 原接口 200；
#   4. 收回权限同样即时：再降一级 → 旧令牌 401 → 刷新后**403**（不是 401）
#      —— 401/403 的分野证明"拒绝"来自角色判权（E16）而不是版本校验在乱拦；
#   5. 兼容窗口：不带 pv 的老令牌**不被版本校验拦下**（走到服务端判权，403），
#      而唯一差别只是"多了一个落后 pv"的对照令牌被拦成 401 —— 差异只可能来自 pv；
#   6. 幂等不扰民：角色没变时重复提交不推进版本（否则每次重复点一下都会把对方判过期）；
#   7. 副本自愈：刷新一次之后 Redis 副本与库内版本重新一致；
#   8. 收尾自证：临时提权账号降回 USER，且全库 ADMIN 数量与开跑前一致；
#      再让它的在途令牌立即失效（否则"收尾"只是改了个库字段，令牌还能用 15 分钟）。
#
# 设计原则（沿用 E27 定稿的夹具三纪律）：
#   1. **现场建 / 跑完自毁 / 建不起来就显式报红** —— 提权失败必须报红，
#      否则后面所有断言都会因为"对方本来就没权限"而静默通过（假绿）。
#   2. **对照而非孤立断言**：401 与放行的对照里，两枚令牌只差一个 pv 声明，
#      差异不可能来自别的原因。
#   3. 断言失败一律带 status；只有 reply 分不清"答了别的话"与"根本没拿到响应"。
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    [string]$Password = 'duoduo12345'
)

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
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
    $out = & docker exec duoduo-mysql mysql -uduoduo -pduoduo -N -B `
        --default-character-set=utf8mb4 -D $db -e $sql 2>&1
    if ($LASTEXITCODE -ne 0) { return $null }
    return ((@($out) | Where-Object { "$_" -notmatch 'Warning' }) -join "`n").Trim()
}

function RedisGet([string]$key) {
    $out = & docker exec duoduo-redis redis-cli GET $key 2>&1
    return ("$out").Trim()
}

function RedisSet([string]$key, [string]$value) {
    & docker exec duoduo-redis redis-cli SET $key $value 2>&1 | Out-Null
}

function Bearer([string]$token) {
    return @{ 'Authorization' = "Bearer $token" }
}

. (Join-Path $PSScriptRoot 'lib\platform-operator.ps1')

$actorPhone = '139' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$targetPhone = '138' + (Get-Random -Minimum 10000000 -Maximum 99999999)

Write-Host ""
Write-Host "=== 多多宠物商店 · 权限即时生效冒烟（E17） @ $Gateway ==="
Write-Host "临时管理员 = $actorPhone    被改的人 = $targetPhone"
Write-Host ""

# ---------- 0. 就绪 ----------
Write-Host "> 就绪检查"
$ready = (Call 'Get' "$Gateway/api/categories" $null $null).status -eq 200
Check "网关可达（/api/categories → 200）" $ready "网关未就绪，后续断言无意义"
if (-not $ready) {
    Write-Host "  [ABORT] 网关不可达，终止"
    Write-Host ""
    Write-Host "=== 冒烟结果：通过 $pass 项，失败 $($fail + 1) 项 ==="
    exit 1
}

# 开跑前的 ADMIN 总数：收尾要证明"没有留下提权账号"，必须与它比对
$adminBefore = [int](Sql 'duoduo_user' "SELECT COUNT(*) FROM t_user WHERE role='ADMIN';")

# ---------- 1. 造夹具：临时管理员 + 被改的人 ----------
Write-Host "> 1. 夹具"
$actorId = $null
$targetId = $null

Call 'Post' "$Gateway/api/auth/register" @{ phone = $actorPhone; password = $Password; nickname = '权限版本夹具-管理员' } | Out-Null
$roleSet = $false
for ($i = 0; $i -lt 5; $i++) {
    & docker exec duoduo-mysql mysql -uduoduo -pduoduo -N -B -D duoduo_user `
        -e "UPDATE t_user SET role='ADMIN' WHERE phone='$actorPhone';" 2>&1 | Out-Null
    Start-Sleep -Milliseconds 200
    $row = ("$(& docker exec duoduo-mysql mysql -uduoduo -pduoduo -N -B -D duoduo_user -e "SELECT role FROM t_user WHERE phone='$actorPhone';" 2>$null)").Trim()
    if ($row -eq 'ADMIN') { $roleSet = $true; break }
}
# ★ 建不起来必须报红：带着 USER 角色去改角色只会 403，而报错看起来像"越权被正确拦下"，
#   后续断言会因为"什么都没变"而静默通过 —— 这是 E27 定下的夹具纪律
Check "夹具：临时管理员已就位（库内 role=ADMIN）" $roleSet "role=$row"

$actorLogin = Call 'Post' "$Gateway/api/auth/login/token" @{ phone = $actorPhone; password = $Password }
$actorToken = $actorLogin.body.data.accessToken
Check "夹具：临时管理员登录并拿到访问令牌" ($actorLogin.status -eq 200 -and -not [string]::IsNullOrWhiteSpace($actorToken)) "status=$($actorLogin.status) raw=$($actorLogin.raw)"
$actorId = Sql 'duoduo_user' "SELECT id FROM t_user WHERE phone='$actorPhone';"

$targetReg = Call 'Post' "$Gateway/api/auth/register" @{ phone = $targetPhone; password = $Password; nickname = '权限版本夹具-被改的人' }
$targetId = [string]$targetReg.body.data.id
$targetLogin = Call 'Post' "$Gateway/api/auth/login/token" @{ phone = $targetPhone; password = $Password }
$tokenV0 = $targetLogin.body.data.accessToken
$refresh0 = $targetLogin.body.data.refreshToken
Check "夹具：被改的人登录并拿到访问令牌/刷新令牌" `
    ($targetLogin.status -eq 200 -and -not [string]::IsNullOrWhiteSpace($tokenV0) -and -not [string]::IsNullOrWhiteSpace($refresh0)) `
    "status=$($targetLogin.status) raw=$($targetLogin.raw)"
Check "夹具：被改的人初始为普通用户（role=USER）" `
    ((Sql 'duoduo_user' "SELECT role FROM t_user WHERE id=$targetId;") -eq 'USER') `
    (Sql 'duoduo_user' "SELECT role FROM t_user WHERE id=$targetId;")

$versionBefore = Sql 'duoduo_user' "SELECT perm_version FROM t_user WHERE id=$targetId;"

# ---------- 2. 变更前的对照 ----------
Write-Host "> 2. 变更前对照"
$svcAdminUsers = "$Gateway/api/admin/users?page=1&pageSize=1"
$r = Call 'Get' $svcAdminUsers $null (Bearer $tokenV0)
Check "变更前：USER 令牌调后台账号列表 → 403（无 platform:console）" ($r.status -eq 403) "status=$($r.status) raw=$($r.raw)"

$redisBefore = RedisGet "duoduo:perm:ver:$targetId"
Check "夹具：登录已把权限版本副本写入 Redis" ($redisBefore -ne '' -and $redisBefore -ne '(nil)') "redis=$redisBefore"
Check "口径一致：Redis 副本版本 == 库内 perm_version" ($redisBefore -eq $versionBefore) "redis=$redisBefore db=$versionBefore"

# ---------- 3. 提权 → 旧令牌下一个请求即失效 ----------
Write-Host "> 3. 提权（USER → OPERATOR）"
$up = Call 'Post' "$Gateway/api/admin/users/$targetId/role" @{ role = 'OPERATOR' } (Bearer $actorToken)
Check "平台管理员改角色成功（200）" ($up.status -eq 200) "status=$($up.status) raw=$($up.raw)"
Check "响应文案说明「下一个请求即生效」" ($up.body.data.notice -like '*下一个请求即生效*') $up.body.data.notice

$r = Call 'Get' $svcAdminUsers $null (Bearer $tokenV0)
Check "★ 旧令牌（改角色之前签发的）下一个请求即被拒 → 401" ($r.status -eq 401) "status=$($r.status) raw=$($r.raw)"
Check "★ 拒绝原因可区分：code=PERM_VERSION_STALE（不是笼统的 UNAUTHORIZED）" `
    ($r.body.code -eq 'PERM_VERSION_STALE') "code=$($r.body.code) raw=$($r.raw)"

$versionAfterUp = Sql 'duoduo_user' "SELECT perm_version FROM t_user WHERE id=$targetId;"
Check "版本随变更递增（自增，不是被覆盖成固定值）" ([int]$versionAfterUp -eq [int]$versionBefore + 1) `
    "before=$versionBefore after=$versionAfterUp"
$redisAfterUp = RedisGet "duoduo:perm:ver:$targetId"
Check "变更后 Redis 副本同步到新版本" ($redisAfterUp -eq $versionAfterUp) "redis=$redisAfterUp db=$versionAfterUp"

# ---------- 4. 刷新一次即换到新角色（无需重新登录） ----------
Write-Host "> 4. 刷新令牌 → 新角色即时可用"
$rf1 = Call 'Post' "$Gateway/api/auth/refresh/token" @{ refreshToken = $refresh0 }
Check "刷新成功（200，未被迫重新登录）" ($rf1.status -eq 200) "status=$($rf1.status) raw=$($rf1.raw)"
$tokenV1 = $rf1.body.data.accessToken
Check "刷新拿到的新令牌带新角色 OPERATOR" ($rf1.body.data.user.role -eq 'OPERATOR') $rf1.body.data.user.role

$r = Call 'Get' $svcAdminUsers $null (Bearer $tokenV1)
Check "★ 刷新后的令牌可进后台 → 200（权限即时生效且无需重登）" ($r.status -eq 200) "status=$($r.status) raw=$($r.raw)"
$refresh1 = $rf1.body.data.refreshToken

# ---------- 5. 收回权限同样即时，且 401 与 403 分得清 ----------
Write-Host "> 5. 降级（OPERATOR → USER）"
$down = Call 'Post' "$Gateway/api/admin/users/$targetId/role" @{ role = 'USER' } (Bearer $actorToken)
Check "平台管理员降级成功（200）" ($down.status -eq 200) "status=$($down.status) raw=$($down.raw)"

$r = Call 'Get' $svcAdminUsers $null (Bearer $tokenV1)
Check "★ 收权后旧令牌立即失效 → 401" ($r.status -eq 401) "status=$($r.status) raw=$($r.raw)"

$rf2 = Call 'Post' "$Gateway/api/auth/refresh/token" @{ refreshToken = $refresh1 }
$tokenV2 = $rf2.body.data.accessToken
Check "再次刷新成功（200）" ($rf2.status -eq 200) "status=$($rf2.status) raw=$($rf2.raw)"

$r = Call 'Get' $svcAdminUsers $null (Bearer $tokenV2)
Check "★ 刷新后是 403 而不是 401 —— 拒绝来自角色判权（E16），版本校验没有乱拦" `
    ($r.status -eq 403) "status=$($r.status) raw=$($r.raw)"
$refresh2 = $rf2.body.data.refreshToken

# ---------- 6. 幂等：角色没变时不推进版本 ----------
Write-Host "> 6. 幂等"
$versionBeforeIdem = Sql 'duoduo_user' "SELECT perm_version FROM t_user WHERE id=$targetId;"
$same = Call 'Post' "$Gateway/api/admin/users/$targetId/role" @{ role = 'USER' } (Bearer $actorToken)
Check "重复提交同一角色 → 200 且文案为「未做变更」" `
    ($same.status -eq 200 -and $same.body.data.notice -like '*未做变更*') "status=$($same.status) notice=$($same.body.data.notice)"
$r = Call 'Get' $svcAdminUsers $null (Bearer $tokenV2)
# 此刻该账号的角色是 USER（第 5 节刚降过级），所以**有权限**的自然结果是 403 而不是 200。
# 这条断言要守的性质是"版本没有被幂等提交推进"，判据因此是 **不是 401**（401 才是版本过期的信号）
# —— 若写成"期望 200"，它测的就变成"USER 能不能进后台"，与本用例名完全无关，
# 而且会永远红（这类"期望值写错方向"的断言最容易被误读成实现有 bug）。
Check "★ 幂等不推进版本：刚刷新过的令牌不被判过期（403 而非 401）" `
    ($r.status -eq 403) "status=$($r.status) raw=$($r.raw)"
Check "库内版本在幂等提交后没有变化" `
    ((Sql 'duoduo_user' "SELECT perm_version FROM t_user WHERE id=$targetId;") -eq $versionBeforeIdem) `
    (Sql 'duoduo_user' "SELECT perm_version FROM t_user WHERE id=$targetId;")

# ---------- 7. 兼容窗口：不带 pv 的老令牌不被拦下（对照只差 pv 一项） ----------
Write-Host "> 7. 兼容窗口（老令牌）"
$versionNow = [int](Sql 'duoduo_user' "SELECT perm_version FROM t_user WHERE id=$targetId;")
$legacyScript = Join-Path $PSScriptRoot 'lib\legacy-jwt.mjs'

# 两枚手签令牌：除了 pv 一模一样 —— 于是 401 与放行的差异只可能来自 pv
$legacyNoPv = ("$(& node $legacyScript --alg-from $tokenV2 --sub $targetId --role USER 2>$null)" | Select-Object -Last 1).Trim()
$legacyStale = ("$(& node $legacyScript --alg-from $tokenV2 --sub $targetId --role USER --pv $($versionNow - 1) 2>$null)" | Select-Object -Last 1).Trim()
Check "夹具：手签出两枚对照令牌（一枚无 pv、一枚 pv 落后）" `
    ($legacyNoPv -like '*.*.*' -and $legacyStale -like '*.*.*') "noPv=$legacyNoPv stale=$legacyStale"

$rNoPv = Call 'Get' $svcAdminUsers $null (Bearer $legacyNoPv)
Check "★ 无 pv 的老令牌不被版本校验拦下（走到服务端判权 → 403，不是 401）" `
    ($rNoPv.status -eq 403) "status=$($rNoPv.status) raw=$($rNoPv.raw)"

$rStale = Call 'Get' $svcAdminUsers $null (Bearer $legacyStale)
Check "★ 对照组：只多带一个落后 pv → 401（差异只能来自 pv）" `
    ($rStale.status -eq 401) "status=$($rStale.status) raw=$($rStale.raw)"

# ---------- 8. 副本自愈 ----------
Write-Host "> 8. 副本自愈"
# 先把副本搅坏（模拟"写失败 / 被人删了 / 被别人覆盖"），再刷新一次令牌，看它能否被修回来
RedisSet "duoduo:perm:ver:$targetId" "999999"
$rBroken = Call 'Get' $svcAdminUsers $null (Bearer $tokenV2)
Check "副本被搅乱后，带 pv 的令牌被拦 → 401（说明副本确实在参与判定）" `
    ($rBroken.status -eq 401) "status=$($rBroken.status) raw=$($rBroken.raw)"

$rf3 = Call 'Post' "$Gateway/api/auth/refresh/token" @{ refreshToken = $refresh2 }
Check "刷新成功（200）" ($rf3.status -eq 200) "status=$($rf3.status) raw=$($rf3.raw)"
$redisHealed = RedisGet "duoduo:perm:ver:$targetId"
Check "★ 刷新即把副本修回库内版本（自愈，不会永久卡住）" ($redisHealed -eq $versionNow) `
    "redis=$redisHealed db=$versionNow"
$tokenV3 = $rf3.body.data.accessToken
$r = Call 'Get' $svcAdminUsers $null (Bearer $tokenV3)
Check "自愈后令牌恢复正常判权 → 403（USER 无权，但不再是版本过期）" ($r.status -eq 403) "status=$($r.status) raw=$($r.raw)"

# ---------- 9. 收尾自证 ----------
Write-Host "> 9. 收尾"
$actorRoleBack = Remove-PlatformOperatorPrivilege -Phone $actorPhone
Check "临时管理员已降回 USER" ($actorRoleBack -eq 'USER') "role=$actorRoleBack"

# 只改库是不够的：脚本内的管理令牌仍在有效期内（≤15 分钟），且服务端判权看的是令牌里的角色。
# 把副本置成一个不可能的值，让它在途令牌**立刻**失效 —— 这一步同时反过来证明了这个键真的在起作用。
RedisSet "duoduo:perm:ver:$actorId" "999999"
$rActor = Call 'Get' $svcAdminUsers $null (Bearer $actorToken)
Check "收尾后临时管理员的在途令牌立即失效 → 401" ($rActor.status -eq 401) "status=$($rActor.status) raw=$($rActor.raw)"

# 顺手吊销本次夹具账号的刷新令牌：账号是随机手机号注册的、跑完留着也无害（与
# platform-operator.ps1 同一取舍），但刷新令牌没必要继续留在库里。
if ($targetId -and $actorId) {
    Sql 'duoduo_user' "UPDATE t_refresh_token SET revoked=1 WHERE user_id IN ($targetId, $actorId);" | Out-Null
}
$adminAfter = [int](Sql 'duoduo_user' "SELECT COUNT(*) FROM t_user WHERE role='ADMIN';")
Check "★ 全库 ADMIN 数量与开跑前一致（没有留下提权账号）" ($adminAfter -eq $adminBefore) `
    "before=$adminBefore after=$adminAfter"

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
