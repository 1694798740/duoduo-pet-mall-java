# 商家端 IP 白名单（E-06）端到端冒烟
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\merchant-ip-whitelist-smoke.ps1
#
# 前置：17 个容器 healthy；user / gateway 已部署 E-06 镜像；docs/sql/incremental/61-merchant-ip-whitelist.sql 已执行。
#
# 覆盖：
#   ① 店主读取 / 保存本店白名单，currentIp 由服务端给出（不靠猜）；
#   ② 网关执行：命中名单放行、未命中 → 403 MERCHANT_IP_DENIED；买家路径完全不受影响；
#   ③ 防锁死：新名单不含当前 IP 且未确认 → 422；确认后才允许保存；
#   ④ 救援通道：运营可远程清空（店主被锁在门外时唯一自救路径）；
#   ⑤ 店主专属：非店主 403；规则形状非法 400；「启用但空名单」422；
#   ⑥ 审计留痕：两次改动各落一条 UPDATE_MERCHANT_IP_WHITELIST / ADMIN_RESET_MERCHANT_IP_WHITELIST。
#
# 说明：本脚本一定会把白名单**恢复为关闭**，并在结尾断言这一点 ——
#       否则跑完一次冒烟，整间测试店会永远进不去（这正是该功能最危险的失败模式）。
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    [string]$SellerPhone = '13600003333',
    [string]$OperatorPhone = '13684295254',
    [string]$Password = 'duoduo12345'
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

function Sql([string]$db, [string]$sql) {
    $env:PATH = 'C:\Program Files\Docker\Docker\resources\bin;' + $env:PATH
    if (-not $env:DOCKER_CONFIG) { $env:DOCKER_CONFIG = 'D:\duoduo-infra\docker-nocreds' }
    $out = & docker exec duoduo-mysql mysql -uduoduo -pduoduo -N -B --default-character-set=utf8mb4 -D $db -e $sql 2>&1
    if ($LASTEXITCODE -ne 0) { return $null }
    return ((@($out) | Where-Object { "$_" -notmatch 'Warning' }) -join "`n").Trim()
}

function Login([string]$phone, [string]$pw) {
    $s = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $r = Call 'Post' "$Gateway/api/auth/login" $s @{ phone = $phone; password = $pw }
    return @{ session = $s; result = $r }
}

# ---------------------------------------------------------------------------
# 0. 就绪与前置
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "=== 多多宠物商店 · 商家端 IP 白名单（E-06）冒烟 @ $Gateway ==="

$ready = $false
for ($i = 0; $i -lt 40; $i++) {
    try {
        $h = Invoke-WebRequest -Uri "$Gateway/actuator/health" -UseBasicParsing -TimeoutSec 5
        if ($h.StatusCode -eq 200) { $ready = $true; break }
    } catch { Start-Sleep -Seconds 1 }
}
Check "网关 /actuator/health 就绪" $ready
if (-not $ready) { Write-Host "网关未就绪，终止"; exit 1 }

$col = Sql 'duoduo_user' "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='duoduo_user' AND table_name='t_merchant' AND column_name IN ('ip_whitelist_enabled','ip_whitelist');"
Check "（前置）61-merchant-ip-whitelist.sql 已执行（两列存在）" ("$col" -eq '2') "实际列数=$col"

# ---------------------------------------------------------------------------
# 1. 登录
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "> 账号"
$seller = Login $SellerPhone $Password
Check "店主登录成功" ($seller.result.status -eq 200) "$($seller.result.status)/$($seller.result.raw)"
$sess = $seller.session
$sellerId = Sql 'duoduo_user' "SELECT id FROM t_user WHERE phone = '$SellerPhone';"
$merchantId = Sql 'duoduo_user' "SELECT id FROM t_merchant WHERE owner_user_id = $sellerId;"
Check "（前置）取得店主 userId 与店铺 id" ((-not [string]::IsNullOrEmpty($sellerId)) -and (-not [string]::IsNullOrEmpty($merchantId))) "userId=$sellerId merchantId=$merchantId"
if (-not $merchantId) { Write-Host "找不到店主名下店铺，终止"; exit 1 }

$op = Login $OperatorPhone $Password
Check "运营登录成功" ($op.result.status -eq 200) "$($op.result.status)/$($op.result.raw)"

# ---------------------------------------------------------------------------
# 2. 读取：默认关闭 + 服务端给出当前 IP
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "> 读取白名单"
$rView = Call 'Get' "$Gateway/api/seller/security/ip-whitelist" $sess $null
Check "① 店主可读取白名单（200）" ($rView.status -eq 200) "$($rView.status)/$($rView.raw)"
Check "① 默认未启用" ($rView.body.data.enabled -eq $false) $rView.body.data.enabled
Check "① 默认无规则" (@($rView.body.data.rules).Count -eq 0) (@($rView.body.data.rules) -join ',')
$CURRENT_IP = $rView.body.data.currentIp
Check "① 服务端回显当前来源 IP（便于「把我也加进去」）" (-not [string]::IsNullOrEmpty($CURRENT_IP)) "currentIp=$CURRENT_IP"

# 基线：未启用时商家接口正常
$rStaffBase = Call 'Get' "$Gateway/api/seller/staff" $sess $null
Check "（基线）未启用白名单时商家接口可正常访问" ($rStaffBase.status -eq 200) $rStaffBase.status

# ---------------------------------------------------------------------------
# 3. 反向：参数与准入
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "> 反向：参数与准入边界"
$rEmpty = Call 'Put' "$Gateway/api/seller/security/ip-whitelist" $sess @{ enabled = $true; rules = @(); confirmLockout = $true }
Check "⑤ 启用但空名单 → 422" ($rEmpty.status -eq 422) "$($rEmpty.status)/$($rEmpty.raw)"
Check "⑤ 空名单提示说明后果" ($rEmpty.body.message -match '至少要有一条规则') $rEmpty.body.message

$rBadRule = Call 'Put' "$Gateway/api/seller/security/ip-whitelist" $sess @{ enabled = $true; rules = @('localhost'); confirmLockout = $true }
Check "⑤ 非法规则 → 400" ($rBadRule.status -eq 400) "$($rBadRule.status)/$($rBadRule.raw)"
Check "⑤ 非法规则提示指出具体值" ($rBadRule.body.message -match 'localhost') $rBadRule.body.message

# 非店主（普通买家）应被两层同时拦下：网关商家族入口 + 服务端店主校验
$phoneBuyer = '134' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$sessBuyer = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$regBuyer = Call 'Post' "$Gateway/api/auth/register" $sessBuyer @{ phone = $phoneBuyer; password = 'duoduo123'; nickname = '白名单测试买家' }
if ($regBuyer.status -eq 409) { $regBuyer = Call 'Post' "$Gateway/api/auth/login" $sessBuyer @{ phone = $phoneBuyer; password = 'duoduo123' } }
$rBuyerTry = Call 'Get' "$Gateway/api/seller/security/ip-whitelist" $sessBuyer $null
Check "⑤ 非店主读取 → 403（商家族 + 店主专属两层）" ($rBuyerTry.status -eq 403) "$($rBuyerTry.status)/$($rBuyerTry.raw)"

# ---------------------------------------------------------------------------
# 4. 防锁死：不含当前 IP 且未确认 → 422
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "> 防锁死自检"
$rNoConfirm = Call 'Put' "$Gateway/api/seller/security/ip-whitelist" $sess @{ enabled = $true; rules = @('203.0.113.9'); confirmLockout = $false }
Check "③ 当前 IP 不在新名单且未确认 → 422" ($rNoConfirm.status -eq 422) "$($rNoConfirm.status)/$($rNoConfirm.raw)"
Check "③ 提示里直接给出当前 IP" ($rNoConfirm.body.message -match [regex]::Escape($CURRENT_IP)) $rNoConfirm.body.message
Check "③ 提示明确「保存后无法进入商家端」" ($rNoConfirm.body.message -match '无法进入商家端') $rNoConfirm.body.message

# ---------------------------------------------------------------------------
# 5. 正常保存（含当前 IP）→ 放行
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "> 正常启用（包含当前 IP）"
$rules = @("$CURRENT_IP", '10.0.0.0/8')
$rSave = Call 'Put' "$Gateway/api/seller/security/ip-whitelist" $sess @{ enabled = $true; rules = $rules; confirmLockout = $false }
Check "① 店主保存白名单成功" ($rSave.status -eq 200) "$($rSave.status)/$($rSave.raw)"
Check "① 回显已启用" ($rSave.body.data.enabled -eq $true) $rSave.body.data.enabled
Check "① 规则去重后原样回显" ((@($rSave.body.data.rules) -join ',') -eq ($rules -join ',')) (@($rSave.body.data.rules) -join ',')
Check "① 网关副本已同步（published=true）" ($rSave.body.data.published -eq $true) $rSave.body.data.published
$dbOn = Sql 'duoduo_user' "SELECT CONCAT(ip_whitelist_enabled,':',ip_whitelist) FROM t_merchant WHERE id = $merchantId;"
Check "① 库内权威值已写入" ("$dbOn" -like '1:*') $dbOn
Check "② 命中名单的商家接口仍可访问（200）" ((Call 'Get' "$Gateway/api/seller/staff" $sess $null).status -eq 200)
Check "② 买家路径不受影响（领券中心 200）" ((Call 'Get' "$Gateway/api/coupons/templates" $sessBuyer $null).status -eq 200)

# ---------------------------------------------------------------------------
# 6. 网关拦截（把名单换成不含当前 IP 的地址，确认后保存）
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "> 网关拦截"
$rLock = Call 'Put' "$Gateway/api/seller/security/ip-whitelist" $sess @{ enabled = $true; rules = @('203.0.113.9'); confirmLockout = $true }
Check "③ 确认锁死后允许保存" ($rLock.status -eq 200) "$($rLock.status)/$($rLock.raw)"
$rBlocked = Call 'Get' "$Gateway/api/seller/staff" $sess $null
Check "② 来源不在名单内 → 403" ($rBlocked.status -eq 403) "$($rBlocked.status)/$($rBlocked.raw)"
Check "② 拒绝码为 MERCHANT_IP_DENIED（与后台区分）" ($rBlocked.body.code -eq 'MERCHANT_IP_DENIED') $rBlocked.body.code
Check "② 提示指出处置入口（账号安全 · IP 白名单）" ($rBlocked.body.message -match 'IP 白名单') $rBlocked.body.message
Check "② 被拦时买家路径依然可用（200）" ((Call 'Get' "$Gateway/api/coupons/templates" $sessBuyer $null).status -eq 200)
$rConfigBlocked = Call 'Get' "$Gateway/api/seller/security/ip-whitelist" $sess $null
Check "② 配置页自身同样被拦（白名单不是纸糊的）" ($rConfigBlocked.status -eq 403) $rConfigBlocked.status

# ---------------------------------------------------------------------------
# 7. 救援通道：运营远程清空
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "> 救援通道"
$rRescue = Call 'Post' "$Gateway/api/admin/merchants/$merchantId/ip-whitelist/clear" $op.session $null
Check "④ 运营清除白名单成功" ($rRescue.status -eq 200) "$($rRescue.status)/$($rRescue.raw)"
Check "④ 清除后已停用" ($rRescue.body.data.enabled -eq $false) $rRescue.body.data.enabled
Check "④ 库内权威值同步关闭" ("$(Sql 'duoduo_user' "SELECT ip_whitelist_enabled FROM t_merchant WHERE id = $merchantId;")" -eq '0')
$rAfterRescue = Call 'Get' "$Gateway/api/seller/staff" $sess $null
Check "④ 店主恢复访问（403 解除）" ($rAfterRescue.status -eq 200) $rAfterRescue.status

# ---------------------------------------------------------------------------
# 8. 审计留痕
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "> 审计留痕"
$updateRows = Sql 'duoduo_user' "SELECT COUNT(*) FROM t_audit_log WHERE action = 'UPDATE_MERCHANT_IP_WHITELIST' AND tenant_id = $sellerId;"
Check "⑥ 店主改动留痕（UPDATE_MERCHANT_IP_WHITELIST ≥ 2 条）" ([int]"$updateRows" -ge 2) $updateRows
$resetRows = Sql 'duoduo_user' "SELECT COUNT(*) FROM t_audit_log WHERE action = 'ADMIN_RESET_MERCHANT_IP_WHITELIST';"
Check "⑥ 平台救援留痕（ADMIN_RESET_MERCHANT_IP_WHITELIST ≥ 1 条）" ([int]"$resetRows" -ge 1) $resetRows

# ---------------------------------------------------------------------------
# 9. 收尾自证：白名单必须已关闭（否则整间测试店会被锁在门外）
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "> 收尾自证"
$finalOn = Sql 'duoduo_user' "SELECT ip_whitelist_enabled FROM t_merchant WHERE id = $merchantId;"
$finalRules = Sql 'duoduo_user' "SELECT COALESCE(ip_whitelist,'<null>') FROM t_merchant WHERE id = $merchantId;"
Check "★ 收尾：白名单已关闭（enabled=0）" ("$finalOn" -eq '0') $finalOn
Check "★ 收尾：规则已清空" ("$finalRules" -eq '<null>') $finalRules
Check "★ 收尾：店主可正常访问商家端" ((Call 'Get' "$Gateway/api/seller/staff" $sess $null).status -eq 200)

# ---------------------------------------------------------------------------
# 汇总
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "-----"
Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
if ($fail -gt 0) {
    Write-Host "失败项："
    $failures | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
exit 0
