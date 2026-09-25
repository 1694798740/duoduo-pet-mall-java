# 微信解绑 / 换绑（A14）冒烟（Windows PowerShell 5.1）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\wechat-bind-smoke.ps1
#
# 前置：容器全部 healthy；user 容器带 DUODUO_WECHAT_MOCK_ENABLED=true（compose 默认开）；
#       已 mvn package + docker compose build duoduo-user-app（含 /wechat/unbind 与 /wechat/rebind）。
#
# 覆盖场景：
#   ① 登录态绑定 → 200（后续解绑/换绑的前置）；
#   ② 解绑时用「别人的微信」的 code → 422 BUSINESS_RULE（不能解绑不属于本账号的微信）；
#   ③ 解绑用「自己的微信」→ 200，且**库内 wx_openid 必须为 NULL**
#      （关键回归点：MyBatis-Plus updateById 默认忽略 null 字段，置空必须走 update wrapper）；
#   ④ 未绑定时再解绑 → 200 幂等（已是目标状态）；
#   ⑤ 未绑定时换绑 → 等价首次绑定（200）；
#   ⑥ 换绑到当前已绑的同一微信 → 200 幂等；
#   ⑦ 换绑到新微信 → 200，库内换成新 openid，旧微信被释放（可为他人绑定）；
#   ⑧ 换绑到「已被其他账号占用」的微信 → 409 CONFLICT；
#   ⑨ 两个端点都不在网关白名单：无登录态 → 401；
#   ⑩ 安全口径：响应只给 wechatBound 布尔，绝不下发 openid 本体。
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    [string]$Password = 'duoduo123'
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

function Call([string]$method, [string]$uri, $session, $bodyObj, [hashtable]$headers) {
    $params = @{ Uri = $uri; Method = $method; UseBasicParsing = $true; TimeoutSec = 25 }
    if ($session) { $params.WebSession = $session }
    if ($headers) { $params.Headers = $headers }
    if ($null -ne $bodyObj) {
        $params.Body = ($bodyObj | ConvertTo-Json -Compress -Depth 8)
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
            # 跨版本读错误响应体：PS5.1 是 HttpWebResponse，pwsh 7 是 HttpResponseMessage
            try {
                $resp = $_.Exception.Response
                $t = $null
                if ($resp -and $resp.PSObject.Methods.Name -contains 'GetResponseStream') {
                    $st = $resp.GetResponseStream()
                    if ($st) { $t = (New-Object System.IO.StreamReader($st)).ReadToEnd() }
                } elseif ($resp -and $null -ne $resp.Content) {
                    $t = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                }
                if ($t) { $raw = $t; $body = $t | ConvertFrom-Json }
            } catch { }
        }
        return @{ status = $code; body = $body; raw = $raw }
    }
}

function Register([string]$phone, [string]$nickname) {
    $s = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $r = Call 'Post' "$Gateway/api/auth/register" $s @{ phone = $phone; password = $Password; nickname = $nickname }
    if ($r.status -eq 409) { $r = Call 'Post' "$Gateway/api/auth/login" $s @{ phone = $phone; password = $Password } }
    return @{ session = $s; result = $r }
}

function Sql([string]$db, [string]$sql) {
    $env:PATH = 'C:\Program Files\Docker\Docker\resources\bin;' + $env:PATH
    if (-not $env:DOCKER_CONFIG) { $env:DOCKER_CONFIG = 'D:\duoduo-infra\docker-nocreds' }
    $out = & docker exec duoduo-mysql mysql -uduoduo -pduoduo -N -B --default-character-set=utf8mb4 -D $db -e $sql 2>&1
    if ($LASTEXITCODE -ne 0) { return $null }
    return ((@($out) | Where-Object { "$_" -notmatch 'Warning' }) -join "`n").Trim()
}

$phoneA = '138' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneB = '139' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$openidX = 'smoke-x-' + ([guid]::NewGuid().ToString('N').Substring(0, 16))
$openidY = 'smoke-y-' + ([guid]::NewGuid().ToString('N').Substring(0, 16))

Write-Host ""
Write-Host "=== 多多宠物商店 · 微信解绑/换绑（A14）冒烟 @ $Gateway ==="
Write-Host "用户 A = $phoneA | 用户 B = $phoneB | 微信X = $openidX | 微信Y = $openidY"
Write-Host ""

# ---------- 0. 就绪 ----------
Write-Host "> 就绪检查"
$ready = $false
for ($i = 0; $i -lt 40; $i++) {
    try {
        $h = Invoke-WebRequest -Uri "$Gateway/actuator/health" -UseBasicParsing -TimeoutSec 5
        if ($h.StatusCode -eq 200) { $ready = $true; break }
    } catch { Start-Sleep -Seconds 1 }
}
Check "网关 /actuator/health 就绪" $ready
if (-not $ready) { Write-Host "网关未就绪，终止"; exit 1 }
$colOn = Sql 'duoduo_user' "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='duoduo_user' AND TABLE_NAME='t_user' AND COLUMN_NAME='wx_openid';"
Check "（前置）t_user.wx_openid 列已存在" ("$colOn" -eq '1') $colOn

# ---------- 1. 账号夹具 ----------
Write-Host ""
Write-Host "> 账号夹具"
$regA = Register $phoneA '解绑冒烟-A'
Check "用户 A 注册/登录成功" ($regA.result.status -eq 200) $regA.result.status
$sessA = $regA.session
$regB = Register $phoneB '解绑冒烟-B'
Check "用户 B 注册/登录成功" ($regB.result.status -eq 200) $regB.result.status
$sessB = $regB.session

# ---------- 2. 绑定 ----------
Write-Host ""
Write-Host "> 场景①：登录态绑定"
$r = Call 'Post' "$Gateway/api/auth/wechat/bind" $sessA @{ code = "mock:$openidX" } $null
Check "① A 绑定微信 X → 200" ($r.status -eq 200) "$($r.status)/$($r.body.code)"
Check "① 响应 wechatBound=true" ($r.body.data.wechatBound -eq $true) $r.body.data.wechatBound
Check "⑩ 响应不含 openid 本体（只给布尔）" (($r.raw -notmatch 'wenid') -and ($r.raw -notmatch $openidX)) "raw 含 openid"

# ---------- 3. 解绑（场景②③④） ----------
Write-Host ""
Write-Host "> 场景②③④：解绑校验 / 解绑成功 / 幂等"
$r = Call 'Post' "$Gateway/api/auth/wechat/unbind" $sessA @{ code = "mock:$openidY" } $null
Check "② 用「别人的微信 Y」解绑 → 422" ($r.status -eq 422) "$($r.status)/$($r.body.code)"
Check "② 错误码 BUSINESS_RULE" ($r.body.code -eq 'BUSINESS_RULE') $r.body.code

$r = Call 'Post' "$Gateway/api/auth/wechat/unbind" $sessA @{ code = "mock:$openidX" } $null
Check "③ 用自己的微信 X 解绑 → 200" ($r.status -eq 200) "$($r.status)/$($r.body.code)"
$dbAfterUnbind = Sql 'duoduo_user' "SELECT IFNULL(wx_openid, 'NULL') FROM t_user WHERE phone = '$phoneA';"
Check "③ ★库内 wx_openid 已置空（NULL）" ("$dbAfterUnbind" -eq 'NULL') $dbAfterUnbind
Check "③ 响应 wechatBound=false" ($r.body.data.wechatBound -eq $false) $r.body.data.wechatBound

$r = Call 'Post' "$Gateway/api/auth/wechat/unbind" $sessA @{ code = "mock:$openidX" } $null
Check "④ 未绑定时再解绑 → 200 幂等" ($r.status -eq 200) "$($r.status)/$($r.body.code)"

# ---------- 4. 换绑（场景⑤⑥⑦⑧） ----------
Write-Host ""
Write-Host "> 场景⑤⑥⑦⑧：换绑首次 / 幂等 / 成功 / 冲突"
$r = Call 'Post' "$Gateway/api/auth/wechat/rebind" $sessA @{ code = "mock:$openidX" } $null
Check "⑤ 未绑定时换绑 → 200（等价首次绑定）" ($r.status -eq 200) "$($r.status)/$($r.body.code)"

$r = Call 'Post' "$Gateway/api/auth/wechat/rebind" $sessA @{ code = "mock:$openidX" } $null
Check "⑥ 换绑到当前已绑的同一微信 → 200 幂等" ($r.status -eq 200) "$($r.status)/$($r.body.code)"

$r = Call 'Post' "$Gateway/api/auth/wechat/rebind" $sessA @{ code = "mock:$openidY" } $null
Check "⑦ 换绑到新微信 Y → 200" ($r.status -eq 200) "$($r.status)/$($r.body.code)"
$dbAfterRebind = Sql 'duoduo_user' "SELECT wx_openid FROM t_user WHERE phone = '$phoneA';"
Check "⑦ ★库内已换成新微信 Y" ("$dbAfterRebind" -eq $openidY) $dbAfterRebind
$freedX = Sql 'duoduo_user' "SELECT COUNT(*) FROM t_user WHERE wx_openid = '$openidX';"
Check "⑦ 旧微信 X 已释放（不再属于任何账号）" ("$freedX" -eq '0') $freedX

$r = Call 'Post' "$Gateway/api/auth/wechat/bind" $sessB @{ code = "mock:$openidX" } $null
Check "（夹具）B 占用已释放的微信 X → 200" ($r.status -eq 200) "$($r.status)/$($r.body.code)"
$r = Call 'Post' "$Gateway/api/auth/wechat/rebind" $sessA @{ code = "mock:$openidX" } $null
Check "⑧ 换绑到「已被他人占用」的微信 → 409" ($r.status -eq 409) "$($r.status)/$($r.body.code)"
Check "⑧ 错误码 CONFLICT" ($r.body.code -eq 'CONFLICT') $r.body.code

# ---------- 5. 鉴权边界（场景⑨） ----------
Write-Host ""
Write-Host "> 场景⑨：两端点不在白名单"
$r = Call 'Post' "$Gateway/api/auth/wechat/unbind" $null @{ code = "mock:$openidY" } $null
Check "⑨ 无登录态解绑 → 401" ($r.status -eq 401) "$($r.status)/$($r.body.code)"
$r = Call 'Post' "$Gateway/api/auth/wechat/rebind" $null @{ code = "mock:$openidY" } $null
Check "⑨ 无登录态换绑 → 401" ($r.status -eq 401) "$($r.status)/$($r.body.code)"

# ---------- 6. 清理 ----------
Write-Host ""
Write-Host "> 清理夹具"
Sql 'duoduo_user' "DELETE FROM t_user WHERE phone IN ('$phoneA', '$phoneB');" | Out-Null
$left = Sql 'duoduo_user' "SELECT COUNT(*) FROM t_user WHERE phone IN ('$phoneA', '$phoneB');"
Check "夹具账号已清理" ("$left" -eq '0') $left

Write-Host ""
Write-Host "=== 结果：$pass 通过 / $fail 失败 ==="
if ($fail -gt 0) {
    Write-Host "失败项："
    $failures | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
exit 0
