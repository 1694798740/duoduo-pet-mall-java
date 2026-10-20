# C7 · 账号注销冒烟（绕过页面，直打接口）
#
# 前置：
#   1) 9 个容器 healthy（docker compose up -d）；
#   2) 已 mvn package + docker compose build 出最新镜像（否则新端点 404）；
#   3) 已对 duoduo_user 应用增量 36（t_user 五列），否则启动即报未知列。
#
# 两个必须先说清楚的坑（都是实测撞出来的）：
#   ★ 登录令牌**不在响应体里**，而在 Set-Cookie：dd_at（访问，15 分钟）/ dd_rt（刷新，7 天）。
#     拿 data.accessToken 永远为 null，后续请求就全是 UNAUTHORIZED。
#     本脚本用 -SessionVariable 维持 Cookie 会话。
#   ★ 昵称用 ASCII：PowerShell 5.1 发出的中文会被转成 latin1，落库后变问号。
#
# 用法：powershell -File scripts/account-deletion-smoke.ps1
# 退出码：0 全过；1 有失败。

param(
    [string]$BaseUrl = 'http://localhost:8080'
)

$ErrorActionPreference = 'Stop'
$script:pass = 0
$script:fail = 0
$script:phone = $null
$script:password = 'Smoke#2026c7'

function Ok([string]$name) {
    $script:pass++
    Write-Host "  [PASS] $name" -ForegroundColor Green
}
function Bad([string]$name, [string]$detail) {
    $script:fail++
    Write-Host "  [FAIL] $name  实际=$detail" -ForegroundColor Red
}
function Check([string]$name, [bool]$cond, [string]$detail) {
    if ($cond) { Ok $name } else { Bad $name $detail }
}

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [object]$Session = $null,
        [switch]$AllowFail,
        [switch]$KeepSession
    )
    $json = $null
    if ($null -ne $Body) { $json = ($Body | ConvertTo-Json -Depth 6 -Compress) }
    $params = @{
        Method      = $Method
        Uri         = ($BaseUrl + $Path)
        ContentType = 'application/json; charset=utf-8'
        TimeoutSec  = 20
    }
    if ($json) { $params['Body'] = [System.Text.Encoding]::UTF8.GetBytes($json) }
    if ($Session) { $params['WebSession'] = $Session }
    try {
        $resp = Invoke-RestMethod @params
        return @{ ok = $true; data = $resp.data; code = $resp.code; status = 200 }
    } catch {
        if (-not $AllowFail) { throw }
        $status = 0; $raw = ''
        if ($_.Exception.Response) {
            $status = [int]$_.Exception.Response.StatusCode
            $reader = [System.IO.StreamReader]::new($_.Exception.Response.GetResponseStream())
            $raw = $reader.ReadToEnd()
        }
        $code = ''
        try { $code = ($raw | ConvertFrom-Json).code } catch { $code = '' }
        return @{ ok = $false; status = $status; raw = $raw; code = $code }
    }
}

Write-Host "=== C7 账号注销冒烟（$BaseUrl） ===" -ForegroundColor Cyan

# ---------- 1. 准备：注册 + 登录（令牌在 Cookie 里） ----------
$stamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$suffix = $stamp.ToString()
$script:phone = '139' + $suffix.Substring($suffix.Length - 8)

$reg = Invoke-Api -Method 'POST' -Path '/api/auth/register' -Body @{
    phone    = $script:phone
    password = $script:password
    nickname = "c7smoke" + ($suffix.Substring($suffix.Length - 5))
} -AllowFail
Check '注册专用账号' ($reg.ok -or $reg.status -eq 200) "status=$($reg.status) raw=$($reg.raw)"

$login = Invoke-Api -Method 'POST' -Path '/api/auth/login' -Body @{ phone = $script:phone; password = $script:password } -Session $null -AllowFail -KeepSession
# 上面的调用不带会话；这里单独再调一次并保留会话（Invoke-RestMethod 的 SessionVariable 需在同一次调用上指定）
$sess = $null
try {
    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes((@{ phone = $script:phone; password = $script:password } | ConvertTo-Json -Compress))
    $respLogin = Invoke-RestMethod -Method POST -Uri ($BaseUrl + '/api/auth/login') -ContentType 'application/json; charset=utf-8' -Body $bodyBytes -SessionVariable sess -TimeoutSec 20
    $script:pass++
    Write-Host '  [PASS] 登录成功（令牌由 Set-Cookie 下发）' -ForegroundColor Green
} catch {
    $script:fail++
    Write-Host '  [FAIL] 登录成功（令牌由 Set-Cookie 下发）' -ForegroundColor Red
    Write-Host "=== 前置失败，终止 ===" -ForegroundColor Red
    exit 1
}
$script:sess = $sess

# ---------- 2. 预检：干净账号应无阻塞 ----------
$pre = Invoke-Api -Method 'GET' -Path '/api/users/me/delete-account' -Session $script:sess -AllowFail
Check '预检端点可用且 blocked=false' ($pre.ok -and $pre.data.blocked -eq $false) "status=$($pre.status) blocked=$($pre.data.blocked)"

# ---------- 3. 密码错误 → 不进入冷静期 ----------
$bad = Invoke-Api -Method 'POST' -Path '/api/users/me/delete-account' -Body @{ password = 'wrong-password' } -Session $script:sess -AllowFail
Check '密码错误被拒（不进入冷静期）' (-not $bad.ok) "status=$($bad.status)"

# ---------- 4. 提交注销 → CANCELLING ----------
$req = Invoke-Api -Method 'POST' -Path '/api/users/me/delete-account' -Body @{ password = $script:password; reason = 'smoke' } -Session $script:sess -AllowFail
if ($req.ok) {
    Check '提交注销返回 CANCELLING' ($req.data.accountStatus -eq 'CANCELLING') "$($req.data.accountStatus)"
    Check '返回冷静期截止时间' ([bool]$req.data.scheduledAt) "$($req.data.scheduledAt)"
} else {
    Bad '提交注销返回 CANCELLING' "status=$($req.status) raw=$($req.raw)"
    Bad '返回冷静期截止时间' '—'
}

# ---------- 5. 冷静期内登录被拒 ----------
$login2 = Invoke-Api -Method 'POST' -Path '/api/auth/login' -Body @{ phone = $script:phone; password = $script:password } -AllowFail
Check '冷静期内登录被拒（ACCOUNT_DELETING）' (-not $login2.ok -and $login2.code -eq 'ACCOUNT_DELETING') "status=$($login2.status) code=$($login2.code)"

# ---------- 6. 重复提交：幂等返回当前状态（不报错、也不产生第二次执行） ----------
$dup = Invoke-Api -Method 'POST' -Path '/api/users/me/delete-account' -Body @{ password = $script:password } -Session $script:sess -AllowFail
Check '重复提交幂等（200 且仍是 CANCELLING，不触发第二次执行）' ($dup.ok -and $dup.data.accountStatus -eq 'CANCELLING') "status=$($dup.status) state=$($dup.data.accountStatus)"

# ---------- 7. 撤销（手机号 + 密码，无需登录态） ----------
$cancel = Invoke-Api -Method 'POST' -Path '/api/auth/account-deletion/cancel' -Body @{ phone = $script:phone; password = $script:password } -AllowFail
Check '手机号 + 密码撤销成功' ($cancel.ok) "status=$($cancel.status) raw=$($cancel.raw)"

# ---------- 8. 撤销后可重新登录 ----------
$login3 = Invoke-Api -Method 'POST' -Path '/api/auth/login' -Body @{ phone = $script:phone; password = $script:password } -AllowFail
Check '撤销后登录恢复' ($login3.ok) "status=$($login3.status) raw=$($login3.raw)"

# ---------- 9. 对未注销账号撤销 → 409 ----------
$cancel2 = Invoke-Api -Method 'POST' -Path '/api/auth/account-deletion/cancel' -Body @{ phone = $script:phone; password = $script:password } -AllowFail
Check '对未注销账号撤销返回 409' ($cancel2.status -eq 409) "status=$($cancel2.status) code=$($cancel2.code)"

# ---------- 10. 内部端点未带签名 → 401/403 ----------
$me = Invoke-Api -Method 'GET' -Path '/api/users/me' -Session $script:sess -AllowFail
$uid = $me.data.id
if ($uid) {
    $internal = Invoke-Api -Method 'POST' -Path "/api/orders/internal/users/$uid/personal-data" -AllowFail
    Check '内部清理端点未带 X-Internal-Token 被拒' ($internal.status -eq 401 -or $internal.status -eq 403) "status=$($internal.status)"
} else {
    Bad '内部清理端点未带 X-Internal-Token 被拒' "拿不到 userId（$($me.status)）"
}

Write-Host ""
Write-Host "=== C7 账号注销冒烟：通过 $script:pass 项，失败 $script:fail 项 ===" -ForegroundColor $(if ($script:fail -eq 0) { 'Green' } else { 'Red' })
Write-Host "（注：本轮账号回到 ACTIVE，未真正执行注销；执行态用例需人工把 delete_scheduled_at 改到过去再触发 Job。）" -ForegroundColor DarkGray
if ($script:fail -gt 0) { exit 1 }
exit 0
