# 微信登录绑定（A13）冒烟（Windows PowerShell 5.1）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\wechat-auth-smoke.ps1
#
# 前置：容器全部 healthy；user 容器已带 DUODUO_WECHAT_MOCK_ENABLED=true（compose 默认开）；
#       且已 mvn package + docker compose build duoduo-user-app 出最新镜像；
#       duoduo_user.t_user 已执行 08-a13-wechat-bind.sql（wx_openid 列 + uk_user_wxopenid 唯一索引）。
#
# 覆盖场景（对应 docs/agent/07 A13 验收）：
#   ① 未绑定微信直接登录 → 422 BUSINESS_RULE（引导先登录再绑定）；
#   ② 未配置 AppID/Secret 且非 mock code → 503 DEGRADED（外部依赖缺失如实降级，不是 422/500）；
#   ③ 登录态绑定 → 200，库内 wx_openid 写入；
#   ④ 重复绑定同一 openid → 200 幂等（不报错）；
#   ⑤ 绑定后 mock 登录 → 200，且 userId 与绑定账号一致；
#   ⑥ openid 已属他人 → 409 CONFLICT（并发防线是 uk_user_wxopenid 唯一索引）；
#   ⑦ 空 code / 纯空白 code → 400（@NotBlank 边界校验）；
#   ⑧ /api/auth/wechat/bind 不在网关白名单：无登录态 401；
#   ⑨ 日志安全口径：服务端日志只出现 openid 前 4 位（脚本侧验证响应不含 openid 全文）。
#
# 设计原则（沿用前几套脚本的教训）：
#   · 断言用业务值（userId 比较两边同精度转换后相等即可）；夹具账号随机手机号；
#   · 断言总数守卫动态计数（声明数 vs 实跑数，T-31 加强版）。
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    [string]$User = 'http://127.0.0.1:8081',
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
            # 跨 PS 版本读错误响应体：PS5.1 的 Response 是 HttpWebResponse（GetResponseStream），
            # pwsh 7（CI Linux）的是 HttpResponseMessage —— 只能 Content.ReadAsStringAsync 读
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
# mock openid：可读前缀便于日志定位，后缀随机避免跨次运行串号
$openidA = 'smoke-a-' + ([guid]::NewGuid().ToString('N').Substring(0, 16))
$openidB = 'smoke-b-' + ([guid]::NewGuid().ToString('N').Substring(0, 16))

Write-Host ""
Write-Host "=== 多多宠物商店 · 微信登录绑定（A13）冒烟 @ $Gateway ==="
Write-Host "用户 A = $phoneA ｜ 用户 B = $phoneB ｜ openidA = $openidA"
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
$mockOn = Sql 'duoduo_user' "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='duoduo_user' AND TABLE_NAME='t_user' AND COLUMN_NAME='wx_openid';"
Check "（前置）t_user.wx_openid 列已存在（08 DDL 已执行）" ("$mockOn" -eq '1') $mockOn
$idxOn = Sql 'duoduo_user' "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='duoduo_user' AND TABLE_NAME='t_user' AND INDEX_NAME='uk_user_wxopenid';"
Check "（前置）唯一索引 uk_user_wxopenid 已存在" ("$idxOn" -eq '1') $idxOn

# ---------- 1. 未绑定登录 → 422；未配置真实通道 → 503 ----------
Write-Host ""
Write-Host "> 场景①②：未绑定 422 / 未配置降级 503"
$r = Call 'Post' "$Gateway/api/auth/wechat" $null @{ code = "mock:$openidA" } $null
Check "① 未绑定微信直接登录 → 422" ($r.status -eq 422) "$($r.status)/$($r.body.code)"
Check "① 错误码为 BUSINESS_RULE" ($r.body.code -eq 'BUSINESS_RULE') $r.body.code
Check "① 提示引导先登录再绑定" ($r.body.message -match '先') $r.body.message
$r = Call 'Post' "$Gateway/api/auth/wechat" $null @{ code = 'real-jscode-not-mock' } $null
Check "② 非 mock code 且未配置 AppID → 503 DEGRADED" ($r.status -eq 503) "$($r.status)/$($r.body.code)"
Check "② 错误码为 DEGRADED" ($r.body.code -eq 'DEGRADED') $r.body.code
Check "② 降级提示改用手机号登录" ($r.body.message -match '手机号') $r.body.message

# ---------- 2. 账号夹具 ----------
Write-Host ""
Write-Host "> 账号夹具"
$regA = Register $phoneA '微信冒烟-A'
Check "用户 A 注册/登录成功" ($regA.result.status -eq 200) $regA.result.status
$sessA = $regA.session
$regB = Register $phoneB '微信冒烟-B'
Check "用户 B 注册/登录成功" ($regB.result.status -eq 200) $regB.result.status
$sessB = $regB.session
$userIdA = Sql 'duoduo_user' "SELECT id FROM t_user WHERE phone = '$phoneA';"
$userIdB = Sql 'duoduo_user' "SELECT id FROM t_user WHERE phone = '$phoneB';"
Check "（前置）取得 A / B 的 userId" ((-not [string]::IsNullOrEmpty($userIdA)) -and (-not [string]::IsNullOrEmpty($userIdB))) "A=$userIdA B=$userIdB"

# ---------- 3. 绑定与幂等（场景③④） ----------
Write-Host ""
Write-Host "> 场景③④：登录态绑定 + 幂等"
$r = Call 'Post' "$Gateway/api/auth/wechat/bind" $sessA @{ code = "mock:$openidA" } $null
Check "③ A 登录态绑定微信 → 200" ($r.status -eq 200) "$($r.status)/$($r.body.code)"
$dbOpenid = Sql 'duoduo_user' "SELECT wx_openid FROM t_user WHERE phone = '$phoneA';"
Check "③ 库内 wx_openid 已写入且值正确" ("$dbOpenid" -eq $openidA) "db=$dbOpenid"
$r = Call 'Post' "$Gateway/api/auth/wechat/bind" $sessA @{ code = "mock:$openidA" } $null
Check "④ A 重复绑定同一微信 → 200 幂等" ($r.status -eq 200) "$($r.status)/$($r.body.code)"
$r = Call 'Post' "$Gateway/api/auth/wechat/bind" $sessB @{ code = "mock:$openidB" } $null
Check "③ B 绑定自己的微信 → 200" ($r.status -eq 200) "$($r.status)/$($r.body.code)"

# ---------- 4. 绑定后登录（场景⑤） ----------
Write-Host ""
Write-Host "> 场景⑤：绑定后 mock 登录"
$r = Call 'Post' "$Gateway/api/auth/wechat" $null @{ code = "mock:$openidA" } $null
Check "⑤ 绑定后微信登录 → 200" ($r.status -eq 200) "$($r.status)/$($r.body.code)"
Check "⑤ 登录返回的 userId 与绑定账号一致" ("$($r.body.data.id)" -eq "$userIdA") "api=$($r.body.data.id) db=$userIdA"
Check "⑤ 返回昵称为 A 的昵称" ($r.body.data.nickname -eq '微信冒烟-A') $r.body.data.nickname
Check "⑨ 响应体不含 openid 全文（最小暴露）" ($r.raw -notmatch [regex]::Escape($openidA)) $r.raw

# ---------- 5. 冲突与边界（场景⑥⑦⑧） ----------
Write-Host ""
Write-Host "> 场景⑥⑦⑧：冲突 / 校验 / 白名单边界"
$r = Call 'Post' "$Gateway/api/auth/wechat/bind" $sessB @{ code = "mock:$openidA" } $null
Check "⑥ B 尝试绑定 A 的微信 → 409" ($r.status -eq 409) "$($r.status)/$($r.body.code)"
Check "⑥ 错误码为 CONFLICT" ($r.body.code -eq 'CONFLICT') $r.body.code
$dbB = Sql 'duoduo_user' "SELECT wx_openid FROM t_user WHERE phone = '$phoneB';"
Check "⑥ 冲突后 B 的绑定未被篡改（仍为 openidB）" ("$dbB" -eq $openidB) "db=$dbB"
$r = Call 'Post' "$Gateway/api/auth/wechat" $null @{ code = '' } $null
Check "⑦ 空 code → 400（@NotBlank）" ($r.status -eq 400) "$($r.status)/$($r.body.code)"
$r = Call 'Post' "$Gateway/api/auth/wechat" $null @{ code = '   ' } $null
Check "⑦ 纯空白 code → 400（@NotBlank）" ($r.status -eq 400) "$($r.status)/$($r.body.code)"
$anon = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$r = Call 'Post' "$Gateway/api/auth/wechat/bind" $anon @{ code = "mock:$openidA" } $null
Check "⑧ 未登录调 bind → 401（bind 不在白名单）" ($r.status -eq 401) $r.status

# ---------- 结果 ----------
Write-Host ""
$declared = ([regex]::Matches((Get-Content -Raw -LiteralPath $PSCommandPath), '(?m)^\s*Check "')).Count
$ran = $pass + $fail
if ($ran -ne $declared) {
    Write-Host "  [GUARD-FAIL] 断言总数守卫：脚本内声明 $declared 项，实际执行 $ran 项 —— 有 Check 被静默跳过"
    $fail++
    $failures += "断言总数守卫（实际执行 $ran 项 / 声明 $declared 项）"
} else {
    Write-Host "  [GUARD-OK] 断言总数守卫：$ran 项全部执行，无静默跳过"
}
Write-Host ""
# ---------- 清理夹具（避免历史账号堆积；A14 补）----------
# 之前每次运行都会留下 A/B 两个账号，长期跑下来库里堆了几十个「微信冒烟-A/B」。
# 这里按手机号精确删除本次创建的两个夹具 —— 只删自己建的，不碰他人数据。
Write-Host ""
Write-Host "> 清理夹具"
Sql 'duoduo_user' "DELETE FROM t_user WHERE phone IN ('$phoneA', '$phoneB');" | Out-Null
$left = Sql 'duoduo_user' "SELECT COUNT(*) FROM t_user WHERE phone IN ('$phoneA', '$phoneB');"
Check "夹具账号已清理（不留历史垃圾）" ("$left" -eq '0') $left

Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
if ($fail -gt 0) {
    Write-Host "失败项："
    $failures | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
exit 0
