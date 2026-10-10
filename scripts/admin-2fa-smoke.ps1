# 平台后台两步验证冒烟（E21，Windows PowerShell 5.1 / pwsh 7 均须可跑）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\admin-2fa-smoke.ps1
#
# 前置：容器全部 healthy，且已部署 E21 镜像（含 duoduo-common 的 security/totp 包、
#       user 域的 TwoFactorService / TwoFactorController、网关的 IP 白名单开关）。
#
# 覆盖（对应 E21 验收要点）：
#   1. 绑定三步：setup 只生成密钥**不启用** → enable 校验通过才启用并下发恢复码；
#      ★ 已启用时再 setup 必须被拒（允许覆盖密钥等于给出一条降级路径）。
#   2. 登录两步：未带码 → 401 TOTP_REQUIRED（与"密码错"不同码）；带错码 → 401 TOTP_INVALID；
#      带对码 → 200。★ 这组是整套的核心：2FA 的价值全在"拿不到码就进不来"。
#   3. ★ 防重放：同一枚动态码第二次提交必须被拒（时间步被记录并原子推进）；
#      同一枚恢复码第二次使用同样被拒，且**失败尝试不消耗**恢复码。
#   4. ★ 恢复码只存哈希：库里查不到明文（用 SQL 断言），数量随使用递减。
#   5. ★ 刷新链路：启用 2FA 会吊销存量刷新令牌 —— "启用之前签发的刷新令牌"若还能刷新，
#      这道门就被绕开了整整 7 天。断言旧 refresh 拿不到新令牌。
#   6. 解绑：动态码与恢复码两条路都能关；关闭后登录不再要求码。
#   7. 管理员重置：仅 ADMIN 可用（OPERATOR → 403），重置后该账号回到未绑定。
#   8. IP 白名单**默认关闭**：带合法运营令牌访问 /api/admin/** 必须照常放行
#      （证明这项新配置没有误伤既有链路；"打开后拦截"由网关单测覆盖 ——
#       冒烟里改配置要重启网关，那会把"跑一次冒烟"变成"动一次环境"）。
#   9. 收尾自证：夹具账号/恢复码/审计行全部拆除，全库账号数与开跑前一致。
#
# 设计原则（沿用 E27 夹具三纪律 + E19 越权矩阵纪律）：
#   1. 现场建 / 跑完自毁 / 建不起来或删不掉都显式报红；
#   2. 每条「被拒」必配「正确姿势能过」的正对照 —— 否则"谁都被拒"也会显得全绿；
#   3. 状态码写具体值，否定断言显式；失败一律带 status + raw。
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    [string]$Password = 'duoduo12345'
)

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
$pass = 0
$fail = 0
$failures = @()
$declared = 54

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
            # 跨 PS 版本读错误响应体：PS5.1 的 Response 是 HttpWebResponse（GetResponseStream），
            # pwsh 7（CI Linux）的是 HttpResponseMessage —— 只能 Content.ReadAsStringAsync 读
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

function SqlExec([string]$sql, [string]$db = 'duoduo_user') {
    & docker exec duoduo-mysql mysql -uduoduo -pduoduo -N -B --default-character-set=utf8mb4 `
        -D $db -e $sql 2>&1 | Out-Null
}

function Bearer([string]$token) {
    return @{ 'Authorization' = "Bearer $token" }
}

# ---------------- TOTP 计算（脚本侧必须能自己算出正确的码，否则整套测不了） ----------------
#
# ★ 这里是**独立实现**（不是调服务端算），意义在于：如果只把服务端的码喂回去，
#   就变成"服务端算的码服务端当然认"，什么也没验证。自己按 RFC 6238 算，
#   才能证明"认证器 App 里显示的那串数字"确实能用。

function Base32Decode([string]$secret) {
    $alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
    $clean = ($secret.ToUpper() -replace '[^A-Z2-7]', '')
    $bits = ''
    foreach ($ch in $clean.ToCharArray()) {
        $idx = $alphabet.IndexOf($ch)
        if ($idx -lt 0) { throw "非法的 Base32 字符: $ch" }
        $bits += [Convert]::ToString($idx, 2).PadLeft(5, '0')
    }
    $bytes = New-Object System.Collections.Generic.List[byte]
    for ($i = 0; ($i + 8) -le $bits.Length; $i += 8) {
        $bytes.Add([Convert]::ToByte($bits.Substring($i, 8), 2))
    }
    return , $bytes.ToArray()
}

function TotpCode([string]$secret, [int]$stepOffset = 0) {
    $key = Base32Decode $secret
    $step = [long][Math]::Floor([DateTimeOffset]::UtcNow.ToUnixTimeSeconds() / 30) + $stepOffset
    $msg = New-Object byte[] 8
    for ($i = 7; $i -ge 0; $i--) {
        $msg[$i] = [byte]($step -band 0xFF)
        $step = $step -shr 8
    }
    $hmac = [System.Security.Cryptography.HMACSHA1]::new($key)
    try { $hash = $hmac.ComputeHash($msg) } finally { $hmac.Dispose() }
    $offset = $hash[19] -band 0x0F
    $binary = ((($hash[$offset] -band 0x7F) -shl 24) -bor
               (($hash[$offset + 1] -band 0xFF) -shl 16) -bor
               (($hash[$offset + 2] -band 0xFF) -shl 8) -bor
               ($hash[$offset + 3] -band 0xFF))
    return ($binary % 1000000).ToString('D6')
}

$phoneOp = '134' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneAdmin = '133' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneVictim = '132' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$usersBefore = Sql 'duoduo_user' "SELECT COUNT(*) FROM t_user;"

Write-Host ""
Write-Host "=== 多多宠物商店 · 平台后台两步验证冒烟（E21） @ $Gateway ==="
Write-Host "夹具：运营 $phoneOp / 管理员 $phoneAdmin / 被重置者 $phoneVictim"
Write-Host ""

# ---------- 0. 就绪 ----------
Write-Host "> 0. 就绪检查"
$ready = $false
try {
    $probe = Invoke-WebRequest -Uri "$Gateway/api/categories" -UseBasicParsing -TimeoutSec 10
    $ready = ($probe.StatusCode -eq 200)
} catch { }
Check "网关可达（/api/categories → 200）" $ready "网关未就绪，后续断言无意义"
if (-not $ready) {
    Write-Host ""
    Write-Host "=== 冒烟结果：网关不可达，已中止 ==="
    exit 1
}

# ---------- 1. 夹具 ----------
Write-Host ""
Write-Host "> 1. 夹具（现场建：账号 + 提权 + 令牌）"

function Register([string]$phone) {
    $r = Call 'Post' "$Gateway/api/auth/register" @{ phone = $phone; password = $Password; nickname = "2FA夹具$phone" } $null
    return $r
}

$rRegOp = Register $phoneOp
Check "（夹具）运营账号注册成功" ($rRegOp.status -eq 200) "status=$($rRegOp.status) raw=$($rRegOp.raw)"
$rRegAdmin = Register $phoneAdmin
Check "（夹具）管理员账号注册成功" ($rRegAdmin.status -eq 200) "status=$($rRegAdmin.status) raw=$($rRegAdmin.raw)"
$rRegVictim = Register $phoneVictim
Check "（夹具）被重置者账号注册成功" ($rRegVictim.status -eq 200) "status=$($rRegVictim.status) raw=$($rRegVictim.raw)"

# 提权必须在**登录之前**：令牌里的 role 是签发那一刻的快照
SqlExec "UPDATE t_user SET role='OPERATOR' WHERE phone='$phoneOp';" | Out-Null
SqlExec "UPDATE t_user SET role='ADMIN' WHERE phone='$phoneAdmin';" | Out-Null
Check "（夹具）运营角色已写入库" ((Sql 'duoduo_user' "SELECT role FROM t_user WHERE phone='$phoneOp';") -eq 'OPERATOR')
Check "（夹具）管理员角色已写入库" ((Sql 'duoduo_user' "SELECT role FROM t_user WHERE phone='$phoneAdmin';") -eq 'ADMIN')

function LoginToken([string]$phone, [string]$totpCode, [string]$recoveryCode, [string]$userAgent = 'e21-smoke') {
    $body = @{ phone = $phone; password = $Password }
    if (-not [string]::IsNullOrWhiteSpace($totpCode)) { $body.totpCode = $totpCode }
    if (-not [string]::IsNullOrWhiteSpace($recoveryCode)) { $body.recoveryCode = $recoveryCode }
    return Call 'Post' "$Gateway/api/auth/login/token" $body @{ 'User-Agent' = $userAgent }
}

$rLoginOp = LoginToken $phoneOp '' '' 
Check "（夹具）运营账号密码登录成功（未启用 2FA 时不要求验证码）" ($rLoginOp.status -eq 200) "status=$($rLoginOp.status) raw=$($rLoginOp.raw)"
$opAccess = $rLoginOp.body.data.accessToken
$opRefresh = $rLoginOp.body.data.refreshToken
Check "（夹具）拿到运营访问令牌" (-not [string]::IsNullOrWhiteSpace($opAccess))

$rLoginAdmin = LoginToken $phoneAdmin '' ''
Check "（夹具）管理员账号登录成功" ($rLoginAdmin.status -eq 200) "status=$($rLoginAdmin.status) raw=$($rLoginAdmin.raw)"
$admAccess = $rLoginAdmin.body.data.accessToken

$rLoginVictim = LoginToken $phoneVictim '' ''
Check "（夹具）被重置者账号登录成功" ($rLoginVictim.status -eq 200) "status=$($rLoginVictim.status) raw=$($rLoginVictim.raw)"
$victimAccess = $rLoginVictim.body.data.accessToken

# ---------- 2. 初始状态 ----------
Write-Host ""
Write-Host "> 2. 初始状态（未绑定）"

$rNoAuth = Call 'Get' "$Gateway/api/auth/2fa/status" $null $null
Check "未登录访问 2FA 状态 → 401（不在网关白名单内）" ($rNoAuth.status -eq 401) "status=$($rNoAuth.status)"

$rStatus0 = Call 'Get' "$Gateway/api/auth/2fa/status" $null (Bearer $opAccess)
Check "正对照：带令牌查自身 2FA 状态 → 200" ($rStatus0.status -eq 200) "status=$($rStatus0.status) raw=$($rStatus0.raw)"
Check "初始为未启用" ($rStatus0.body.data.enabled -eq $false) "enabled=$($rStatus0.body.data.enabled)"
Check "初始恢复码为 0 枚" ($rStatus0.body.data.recoveryCodesRemaining -eq 0) "remaining=$($rStatus0.body.data.recoveryCodesRemaining)"

# ---------- 3. 绑定 ----------
Write-Host ""
Write-Host "> 3. 绑定三步（setup → enable）"

$rSetup = Call 'Post' "$Gateway/api/auth/2fa/setup" $null (Bearer $opAccess)
Check "setup 返回 200" ($rSetup.status -eq 200) "status=$($rSetup.status) raw=$($rSetup.raw)"
$secret = $rSetup.body.data.secret
$otpauth = $rSetup.body.data.otpauthUri
Check "setup 下发了非空密钥" (-not [string]::IsNullOrWhiteSpace($secret)) "secret=$secret"
Check "otpauth 链接格式正确（认证器 App 认的就是它）" ($otpauth -like 'otpauth://totp/*' -and $otpauth -like "*secret=$secret*") "uri=$otpauth"
Check "setup 之后**仍未启用**（必须再验一次码才算绑定成功）" `
    ((Sql 'duoduo_user' "SELECT totp_enabled FROM t_user WHERE phone='$phoneOp';") -eq '0')

$rEnableBad = Call 'Post' "$Gateway/api/auth/2fa/enable" @{ code = '000000' } (Bearer $opAccess)
Check "★ enable 用错误的码 → 401 TOTP_INVALID" `
    ($rEnableBad.status -eq 401 -and "$($rEnableBad.body.code)" -eq 'TOTP_INVALID') `
    "status=$($rEnableBad.status) code=$($rEnableBad.body.code)"

$rEnable = Call 'Post' "$Gateway/api/auth/2fa/enable" @{ code = (TotpCode $secret) } (Bearer $opAccess)
Check "★ enable 用正确动态码 → 200" ($rEnable.status -eq 200) "status=$($rEnable.status) raw=$($rEnable.raw)"
$recoveryCodes = @($rEnable.body.data.recoveryCodes)
Check "★ 下发 10 枚恢复码" ($recoveryCodes.Count -eq 10) "count=$($recoveryCodes.Count)"
Check "恢复码格式为 4-4 分组" `
    (@($recoveryCodes | Where-Object { $_ -notmatch '^[A-Z2-7]{4}-[A-Z2-7]{4}$' }).Count -eq 0) `
    ($recoveryCodes -join ',')
Check "★ 库内已启用，且记录绑定时间" `
    ((Sql 'duoduo_user' "SELECT CONCAT(totp_enabled,'/',IF(totp_bound_at IS NULL,'NO_BOUND','BOUND')) FROM t_user WHERE phone='$phoneOp';") -eq '1/BOUND')
# 防重放的起点：绑定用的那一枚码，其时间步必须已经被记录下来 ——
# 若这里是 NULL，则"绑定用的码"可以立刻拿去登录，而防重放形同虚设。
Check "★ 绑定用的那枚码的时间步已被记录（防重放的起点）" `
    (-not [string]::IsNullOrWhiteSpace("$(Sql 'duoduo_user' "SELECT totp_last_step FROM t_user WHERE phone='$phoneOp';")")) `
    "last_step=$(Sql 'duoduo_user' "SELECT IFNULL(totp_last_step,'NULL') FROM t_user WHERE phone='$phoneOp';")"

# ★ 恢复码只存哈希：库里查不到任何一枚明文
$plainHit = 0
foreach ($rc in $recoveryCodes) {
    $hit = Sql 'duoduo_user' "SELECT COUNT(*) FROM t_totp_recovery_code WHERE user_id=(SELECT id FROM t_user WHERE phone='$phoneOp') AND code_hash='$rc';"
    if ("$hit" -ne '0') { $plainHit++ }
}
Check "★ 恢复码只存哈希（明文在库里一行都查不到）" ($plainHit -eq 0) "明文命中 $plainHit 枚"

$rSetupAgain = Call 'Post' "$Gateway/api/auth/2fa/setup" $null (Bearer $opAccess)
Check "★ 已启用时再 setup → 422（允许覆盖密钥等于给出一条降级路径）" `
    ($rSetupAgain.status -eq 422) "status=$($rSetupAgain.status) code=$($rSetupAgain.body.code)"

# ---------- 4. 登录两步 ----------
Write-Host ""
Write-Host "> 4. 登录两步（本次的核心）"

$rNoCode = LoginToken $phoneOp '' ''
Check "★ 启用后不带验证码登录 → 401 TOTP_REQUIRED（与「密码错」用不同 code）" `
    ($rNoCode.status -eq 401 -and "$($rNoCode.body.code)" -eq 'TOTP_REQUIRED') `
    "status=$($rNoCode.status) code=$($rNoCode.body.code)"

$rBadCode = LoginToken $phoneOp '000000' ''
Check "带错误验证码 → 401 TOTP_INVALID" `
    ($rBadCode.status -eq 401 -and "$($rBadCode.body.code)" -eq 'TOTP_INVALID') `
    "status=$($rBadCode.status) code=$($rBadCode.body.code)"

$rBadPwd = Call 'Post' "$Gateway/api/auth/login/token" @{ phone = $phoneOp; password = 'wrong-password-xx'; totpCode = (TotpCode $secret) } @{ 'User-Agent' = 'e21-smoke' }
Check "正对照：密码错时即使验证码对 → 401（先验密码后验码）" `
    ($rBadPwd.status -eq 401 -and "$($rBadPwd.body.code)" -eq 'UNAUTHORIZED') `
    "status=$($rBadPwd.status) code=$($rBadPwd.body.code)"

$goodCode = TotpCode $secret
$rOk = LoginToken $phoneOp $goodCode ''
Check "★ 带正确动态码登录 → 200" ($rOk.status -eq 200) "status=$($rOk.status) raw=$($rOk.raw)"
$opAccess2 = $rOk.body.data.accessToken
$opRefresh2 = $rOk.body.data.refreshToken
Check "登录确实下发了新令牌" (-not [string]::IsNullOrWhiteSpace($opAccess2))

$rReplay = LoginToken $phoneOp $goodCode ''
Check "★ 防重放：同一枚动态码再次登录 → 401（时间步已用掉）" `
    ($rReplay.status -eq 401) "status=$($rReplay.status) code=$($rReplay.body.code)"

# 恢复码使用
$rc1 = $recoveryCodes[0]
$rRecovery = LoginToken $phoneOp '' $rc1
Check "★ 用恢复码登录 → 200" ($rRecovery.status -eq 200) "status=$($rRecovery.status) raw=$($rRecovery.raw)"
$opAccess3 = $rRecovery.body.data.accessToken

$rRecoveryReplay = LoginToken $phoneOp '' $rc1
Check "★ 同一枚恢复码再次使用 → 401（一次性）" `
    ($rRecoveryReplay.status -eq 401) "status=$($rRecoveryReplay.status) code=$($rRecoveryReplay.body.code)"

$rStatusAfter = Call 'Get' "$Gateway/api/auth/2fa/status" $null (Bearer $opAccess3)
Check "恢复码数量递减为 9 枚" ($rStatusAfter.body.data.recoveryCodesRemaining -eq 9) `
    "remaining=$($rStatusAfter.body.data.recoveryCodesRemaining)"

$rBadRecovery = LoginToken $phoneOp '' 'ZZZZ-ZZZZ'
Check "不存在的恢复码 → 401" ($rBadRecovery.status -eq 401) "status=$($rBadRecovery.status)"
$rStatusAfter2 = Call 'Get' "$Gateway/api/auth/2fa/status" $null (Bearer $opAccess3)
Check "★ 失败尝试不消耗恢复码（仍是 9 枚 —— 否则攻击者能把码耗光）" `
    ($rStatusAfter2.body.data.recoveryCodesRemaining -eq 9) `
    "remaining=$($rStatusAfter2.body.data.recoveryCodesRemaining)"

# ---------- 5. 刷新链路 ----------
Write-Host ""
Write-Host "> 5. 刷新链路（启用前的令牌不能续命）"

$rRefreshOld = Call 'Post' "$Gateway/api/auth/refresh/token" @{ refreshToken = $opRefresh } (Bearer 'ignored')
Check "★ 启用 2FA 之前签发的刷新令牌 → 401（否则这道门能被绕开 7 天）" `
    ($rRefreshOld.status -eq 401) "status=$($rRefreshOld.status) code=$($rRefreshOld.body.code)"

$rRefreshNew = Call 'Post' "$Gateway/api/auth/refresh/token" @{ refreshToken = $opRefresh2 } (Bearer 'ignored')
Check "正对照：通过 2FA 之后拿到的刷新令牌可以正常刷新" `
    ($rRefreshNew.status -eq 200) "status=$($rRefreshNew.status) raw=$($rRefreshNew.raw)"

# ---------- 6. 解绑 ----------
Write-Host ""
Write-Host "> 6. 解绑"

$rDisableBad = Call 'Post' "$Gateway/api/auth/2fa/disable" @{ code = '000000' } (Bearer $opAccess3)
Check "解绑时用错误验证码 → 401" ($rDisableBad.status -eq 401) "status=$($rDisableBad.status)"
Check "解绑失败后仍是启用状态（失败不产生副作用）" `
    ((Sql 'duoduo_user' "SELECT totp_enabled FROM t_user WHERE phone='$phoneOp';") -eq '1')

$rDisable = Call 'Post' "$Gateway/api/auth/2fa/disable" @{ recoveryCode = $recoveryCodes[1] } (Bearer $opAccess3)
Check "★ 用恢复码解绑 → 200（手机丢了只能走这条路）" ($rDisable.status -eq 200) "status=$($rDisable.status) raw=$($rDisable.raw)"
Check "★ 库内密钥与启用标记都已清空" `
    ((Sql 'duoduo_user' "SELECT CONCAT(IFNULL(totp_secret,'NULL'),'/',totp_enabled) FROM t_user WHERE phone='$phoneOp';") -eq 'NULL/0')
Check "★ 解绑后恢复码全部删除（不留长期后备入口）" `
    ((Sql 'duoduo_user' "SELECT COUNT(*) FROM t_totp_recovery_code WHERE user_id=(SELECT id FROM t_user WHERE phone='$phoneOp');") -eq '0')

$rLoginAfterDisable = LoginToken $phoneOp '' ''
Check "★ 解绑后登录不再要求验证码 → 200" ($rLoginAfterDisable.status -eq 200) "status=$($rLoginAfterDisable.status) raw=$($rLoginAfterDisable.raw)"

# ---------- 7. 管理员重置 ----------
Write-Host ""
Write-Host "> 7. 管理员重置他人 2FA（仅 ADMIN）"

$rVictimSetup = Call 'Post' "$Gateway/api/auth/2fa/setup" $null (Bearer $victimAccess)
$victimSecret = $rVictimSetup.body.data.secret
$rVictimEnable = Call 'Post' "$Gateway/api/auth/2fa/enable" @{ code = (TotpCode $victimSecret) } (Bearer $victimAccess)
Check "（夹具）被重置者已启用 2FA" ($rVictimEnable.status -eq 200) "status=$($rVictimEnable.status) raw=$($rVictimEnable.raw)"
$victimId = (Sql 'duoduo_user' "SELECT id FROM t_user WHERE phone='$phoneVictim';").Trim()

$rResetByOp = Call 'Post' "$Gateway/api/admin/users/$victimId/2fa/reset" $null (Bearer $opAccess2)
Check "★ 运营（OPERATOR）重置他人 2FA → 403（该权限点仅 ADMIN）" `
    ($rResetByOp.status -eq 403) "status=$($rResetByOp.status) code=$($rResetByOp.body.code)"

$rResetByAdmin = Call 'Post' "$Gateway/api/admin/users/$victimId/2fa/reset" $null (Bearer $admAccess)
Check "★ 管理员重置 → 200" ($rResetByAdmin.status -eq 200) "status=$($rResetByAdmin.status) raw=$($rResetByAdmin.raw)"
Check "重置后被重置者回到未绑定" `
    ((Sql 'duoduo_user' "SELECT totp_enabled FROM t_user WHERE phone='$phoneVictim';") -eq '0')

$rVictimLoginAfterReset = LoginToken $phoneVictim '' ''
Check "★ 重置后该账号登录不再要求验证码（真的救回来了）" `
    ($rVictimLoginAfterReset.status -eq 200) "status=$($rVictimLoginAfterReset.status) raw=$($rVictimLoginAfterReset.raw)"

# ---------- 8. IP 白名单默认关闭 ----------
Write-Host ""
Write-Host "> 8. IP 白名单默认关闭（不误伤既有链路）"

$rAdminCall = Call 'Get' "$Gateway/api/admin/users?page=1&pageSize=5" $null (Bearer $admAccess)
Check "★ 默认关闭时：合法管理员访问 /api/admin/** 照常 200" `
    ($rAdminCall.status -eq 200) "status=$($rAdminCall.status) raw=$($rAdminCall.raw)"

# ---------- 9. 收尾自证 ----------
Write-Host ""
Write-Host "> 9. 收尾自证"

SqlExec "DELETE FROM t_totp_recovery_code WHERE user_id IN (SELECT id FROM t_user WHERE phone IN ('$phoneOp','$phoneAdmin','$phoneVictim'));" | Out-Null
SqlExec "DELETE FROM t_audit_log WHERE actor_id IN (SELECT id FROM t_user WHERE phone IN ('$phoneOp','$phoneAdmin','$phoneVictim'));" | Out-Null
SqlExec "DELETE FROM t_refresh_token WHERE user_id IN (SELECT id FROM t_user WHERE phone IN ('$phoneOp','$phoneAdmin','$phoneVictim'));" | Out-Null
SqlExec "DELETE FROM t_user WHERE phone IN ('$phoneOp','$phoneAdmin','$phoneVictim');" | Out-Null

Check "★ 收尾自证：夹具账号已删除" `
    ((Sql 'duoduo_user' "SELECT COUNT(*) FROM t_user WHERE phone IN ('$phoneOp','$phoneAdmin','$phoneVictim');") -eq '0')
Check "★ 收尾自证：夹具恢复码已清空" `
    ((Sql 'duoduo_user' "SELECT COUNT(*) FROM t_totp_recovery_code c WHERE c.user_id NOT IN (SELECT id FROM t_user);") -eq '0')
Check "★ 收尾自证：全库账号数与开跑前一致（没有留下永久后台钥匙）" `
    ((Sql 'duoduo_user' "SELECT COUNT(*) FROM t_user;") -eq "$usersBefore") `
    "before=$usersBefore after=$(Sql 'duoduo_user' 'SELECT COUNT(*) FROM t_user;')"

# ---------- 汇总 ----------
$ran = $pass + $fail
Write-Host ""
Write-Host "=================================================================="
if ($ran -ne $declared) {
    Write-Host "  [GUARD-FAIL] 断言总数守卫：声明 $declared 项，实际执行 $ran 项" -ForegroundColor Red
    $fail++
} else {
    Write-Host "  [GUARD-OK] 断言总数守卫：$declared 项全部执行，无静默跳过" -ForegroundColor Green
}
Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
if ($fail -gt 0) {
    Write-Host "失败清单："
    $failures | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
exit 0
