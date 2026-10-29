# 用户服务 · 多多币 P2 签到端到端冒烟（Windows PowerShell 5.1）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\coins-signin-smoke.ps1
#
# 前置：容器 healthy，duoduo-user 已用含签到功能的镜像重建，
#       docs/sql/incremental/64-duoduo-coins-signin.sql 已应用（t_coin_signin + 唯一索引）。
#
# 验收口径（docs/PLAN.md §7 R-P2 · 第一百二十六轮）：
#   1. 首次签到：发 5 币、连签 1，签到行 / 批次 / 流水 / 账户四处一致；
#   2. 同日重复签到：业务拒绝（幂等），余额不变；
#   3. 月历：signedDates 含今日、signedToday=true、连签快照正确；
#   4. 连签加发：昨日连 2 → 今日发 15（5+10）、连签 3；
#   5. 断签重置：昨日无签（夹具只造前天）→ 今日发 5、连签 1；
#   6. 账实一致：账户余额 = Σ 未过期批次（对账恒等式）；
#   7. 收尾：测试账号的签到 / 币数据清干净，脚本可重复执行。
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

function Call([string]$method, [string]$uri, $session, $bodyObj) {
    $params = @{ Uri = $uri; Method = $method; UseBasicParsing = $true; TimeoutSec = 25 }
    if ($session) { $params.WebSession = $session }
    if ($null -ne $bodyObj) {
        $params.Body = ($bodyObj | ConvertTo-Json -Compress -Depth 6)
        $params.ContentType = 'application/json; charset=utf-8'
    }
    try {
        $r = Invoke-WebRequest @params
        return @{ status = [int]$r.StatusCode; body = ($r.Content | ConvertFrom-Json) }
    } catch {
        $code = 0; $body = $null
        try { $code = [int]$_.Exception.Response.StatusCode.value__ } catch { }
        try { if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $body = $_.ErrorDetails.Message | ConvertFrom-Json } } catch { }
        if ($null -eq $body) {
            try {
                $resp = $_.Exception.Response
                $t = $null
                if ($resp -and $resp.PSObject.Methods.Name -contains 'GetResponseStream') {
                    $st = $resp.GetResponseStream()
                    if ($st) { $t = (New-Object System.IO.StreamReader($st)).ReadToEnd() }
                } elseif ($resp -and $null -ne $resp.Content) {
                    $t = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                }
                if ($t) { $body = $t | ConvertFrom-Json }
            } catch { }
        }
        return @{ status = $code; body = $body }
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

Write-Host ""
Write-Host "=== 多多宠物商店 · 多多币 P2 签到冒烟 @ $Gateway ==="

# ---------- 0. 网关就绪 ----------
$ready = $false
foreach ($i in 1..30) {
    $h = Call 'Get' "$Gateway/actuator/health" $null $null
    if ($h.status -eq 200) { $ready = $true; break }
    Start-Sleep 2
}
Check '网关就绪' $ready "health status=$($h.status)"
if (-not $ready) { Write-Host '网关不可用，中止。'; exit 1 }

# ---------- 1. 测试买家 ----------
$phoneA = '136' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$a = Register $phoneA '签到冒烟-买家'
Check '测试买家注册/登录' ($a.result.status -eq 200 -and $a.result.body.data.id) "status=$($a.result.status)"
$uid = [string]$a.result.body.data.id
# ⚠️ 日期一律按 Asia/Shanghai（服务端 signin 的判定时区）在 PowerShell 侧算好字面量：
# MySQL 容器是 UTC 时区，CURDATE()/DATE_SUB 在北京时间凌晨跑会把"昨天"插成"前天"，
# 服务端读不到夹具行 → 连签场景变断签，账户回退账目跟着错位（第一轮踩过）。
$cn = [System.TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTime]::UtcNow, 'China Standard Time')
$today = $cn.ToString('yyyy-MM-dd')
$yesterday = $cn.AddDays(-1).ToString('yyyy-MM-dd')
$dayBefore = $cn.AddDays(-2).ToString('yyyy-MM-dd')

# 清历史（前一轮残留，保证可重复执行）：签到行 / 币流水 / 批次 / 账户
Sql duoduo_user "DELETE FROM t_coin_signin WHERE user_id = $uid;" | Out-Null
Sql duoduo_user "DELETE FROM t_coin_ledger WHERE user_id = $uid;" | Out-Null
Sql duoduo_user "DELETE FROM t_coin_freeze_lot WHERE freeze_id IN (SELECT id FROM t_coin_freeze WHERE user_id = $uid);" | Out-Null
Sql duoduo_user "DELETE FROM t_coin_freeze WHERE user_id = $uid;" | Out-Null
Sql duoduo_user "DELETE FROM t_coin_lot WHERE user_id = $uid;" | Out-Null
Sql duoduo_user "DELETE FROM t_coin_account WHERE user_id = $uid;" | Out-Null

# ---------- 2. 首次签到 ----------
$r1 = Call 'Post' "$Gateway/api/coins/signin" $a.session $null
Check '首次签到 200' ($r1.status -eq 200) "status=$($r1.status) body=$($r1.body)"
Check '发放 5 币、连签 1' ($r1.body.data.coins -eq 5 -and $r1.body.data.continuousDays -eq 1) "coins=$($r1.body.data.coins) cont=$($r1.body.data.continuousDays)"
$row = Sql duoduo_user "SELECT COUNT(*) FROM t_coin_signin WHERE user_id = $uid AND sign_date = '$today' AND coins = 5 AND continuous_days = 1;"
Check '签到行落地（日期/币数/连签）' ($row -eq '1') "rows=$row"
$lot = Sql duoduo_user "SELECT COUNT(*) FROM t_coin_lot WHERE user_id = $uid AND biz_type = 'SIGNIN_EARN' AND remaining = 5;"
Check '批次落地（SIGNIN_EARN 5 币）' ($lot -eq '1') "lots=$lot"
$led = Sql duoduo_user "SELECT COUNT(*) FROM t_coin_ledger WHERE user_id = $uid AND direction = 'EARN' AND biz_type = 'SIGNIN_EARN' AND amount = 5;"
Check '流水落地（EARN / SIGNIN_EARN）' ($led -eq '1') "ledger=$led"
$acc = Sql duoduo_user "SELECT CONCAT(balance, '/', total_earned) FROM t_coin_account WHERE user_id = $uid;"
Check '账户余额与累计获得 = 5' ($acc -eq '5/5') "acc=$acc"

# ---------- 3. 同日重复签到 ----------
$r2 = Call 'Post' "$Gateway/api/coins/signin" $a.session $null
Check '重复签到被拒（4xx）' ($r2.status -ge 400 -and $r2.status -lt 500) "status=$($r2.status)"
$acc2 = Sql duoduo_user "SELECT balance FROM t_coin_account WHERE user_id = $uid;"
Check '重复签到不发币（余额仍 5）' ($acc2 -eq '5') "balance=$acc2"

# ---------- 4. 月历 ----------
$m1 = Call 'Get' "$Gateway/api/coins/signin/month" $a.session $null
Check '月历 200 且今日已签' ($m1.status -eq 200 -and $m1.body.data.signedToday -eq $true) "status=$($m1.status)"
Check '月历含今日、连签 1' (($m1.body.data.signedDates -contains $today) -and $m1.body.data.continuousDays -eq 1) "dates=$($m1.body.data.signedDates) cont=$($m1.body.data.continuousDays)"

# ---------- 5. 连签加发（夹具：昨日连 2 → 今日应发 15、连 3） ----------
# 清今日数据（签到行 + 流水 + 批次 + 账户回退），再插昨日行
Sql duoduo_user "DELETE FROM t_coin_signin WHERE user_id = $uid AND sign_date = '$today';" | Out-Null
Sql duoduo_user "DELETE FROM t_coin_ledger WHERE user_id = $uid AND biz_type = 'SIGNIN_EARN';" | Out-Null
Sql duoduo_user "DELETE FROM t_coin_lot WHERE user_id = $uid AND biz_type = 'SIGNIN_EARN';" | Out-Null
Sql duoduo_user "UPDATE t_coin_account SET balance = balance - 5, total_earned = total_earned - 5 WHERE user_id = $uid;" | Out-Null
Sql duoduo_user "INSERT INTO t_coin_signin (id, user_id, sign_date, coins, continuous_days, created_at) VALUES (990001, $uid, '$yesterday', 5, 2, NOW());" | Out-Null

$r3 = Call 'Post' "$Gateway/api/coins/signin" $a.session $null
Check '连签第 3 天：发 15 币、连签 3' ($r3.body.data.coins -eq 15 -and $r3.body.data.continuousDays -eq 3) "coins=$($r3.body.data.coins) cont=$($r3.body.data.continuousDays)"
$lot3 = Sql duoduo_user "SELECT remaining FROM t_coin_lot WHERE user_id = $uid AND biz_type = 'SIGNIN_EARN' ORDER BY id DESC LIMIT 1;"
Check '加发批次 15 币' ($lot3 -eq '15') "lot=$lot3"

# ---------- 6. 断签重置（夹具：昨日无签、前天连 6 → 今日发 5、连 1） ----------
Sql duoduo_user "DELETE FROM t_coin_signin WHERE user_id = $uid AND sign_date IN ('$today', '$yesterday');" | Out-Null
Sql duoduo_user "DELETE FROM t_coin_ledger WHERE user_id = $uid AND biz_type = 'SIGNIN_EARN';" | Out-Null
Sql duoduo_user "DELETE FROM t_coin_lot WHERE user_id = $uid AND biz_type = 'SIGNIN_EARN';" | Out-Null
Sql duoduo_user "UPDATE t_coin_account SET balance = balance - 15, total_earned = total_earned - 15 WHERE user_id = $uid;" | Out-Null
Sql duoduo_user "INSERT INTO t_coin_signin (id, user_id, sign_date, coins, continuous_days, created_at) VALUES (990002, $uid, '$dayBefore', 5, 6, NOW());" | Out-Null

$r4 = Call 'Post' "$Gateway/api/coins/signin" $a.session $null
Check '断签重置：发 5 币、连签 1' ($r4.body.data.coins -eq 5 -and $r4.body.data.continuousDays -eq 1) "coins=$($r4.body.data.coins) cont=$($r4.body.data.continuousDays)"

# ---------- 7. 账实一致（对账恒等式） ----------
$mismatch = Sql duoduo_user "SELECT COUNT(*) FROM t_coin_account a WHERE a.user_id = $uid AND a.balance <> (SELECT COALESCE(SUM(l.remaining),0) FROM t_coin_lot l WHERE l.user_id = a.user_id AND l.expire_at > UTC_TIMESTAMP());"
Check '账户余额 = Σ 未过期批次' ($mismatch -eq '0') "mismatch=$mismatch"

# ---------- 8. 收尾：币数据清干净（账号留存无特权，不影响下轮） ----------
Sql duoduo_user "DELETE FROM t_coin_signin WHERE user_id = $uid;" | Out-Null
Sql duoduo_user "DELETE FROM t_coin_ledger WHERE user_id = $uid;" | Out-Null
Sql duoduo_user "DELETE FROM t_coin_lot WHERE user_id = $uid;" | Out-Null
Sql duoduo_user "DELETE FROM t_coin_account WHERE user_id = $uid;" | Out-Null
$leftover = Sql duoduo_user "SELECT (SELECT COUNT(*) FROM t_coin_signin WHERE user_id = $uid) + (SELECT COUNT(*) FROM t_coin_ledger WHERE user_id = $uid) + (SELECT COUNT(*) FROM t_coin_lot WHERE user_id = $uid) + (SELECT COUNT(*) FROM t_coin_account WHERE user_id = $uid);"
Check '收尾自证：该用户币数据零残留' ($leftover -eq '0') "leftover=$leftover"

Write-Host ""
Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
if ($fail -gt 0) {
    Write-Host "失败清单："
    foreach ($f in $failures) { Write-Host "  - $f" }
    exit 1
}
exit 0
