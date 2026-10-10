# 优惠券营销（A5）端到端冒烟（Windows PowerShell 5.1）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\coupon-smoke.ps1
#
# 前置：9 个容器全部 healthy（docker compose up -d），且已 mvn package + docker compose build 出最新镜像。
#
# 覆盖 A5 的核心验收目标：
#   ① 领券幂等：同一用户同一券模板只能领一张（唯一索引兜底，不是「先查再插」）；
#   ② 券不超发：库存 CAS（claimed_count < total_count）；
#   ③ 锁券 / 解锁 / 核销全部走 CAS 条件更新，并发下只有一方成功（INV-1，禁分布式锁）；
#   ④ 折扣不落营销域：营销域只回答「能不能用、抵多少」，金额由交易域写进 t_order.discount_amount；
#   ⑤ 券的生命周期闭环：领 → 下单锁定 → 支付核销 / 取消解锁 → 超时兜底解锁 → 过期。
#
# 另外覆盖（验收标准没写、但不测迟早出事）：
#   · 公开只读边界：/api/coupons/templates 匿名可读，/api/coupons/mine 与领券匿名 401；
#   · 内部接口凭据：lock / release / consume / sweep 无凭据或错凭据一律 403，且不经网关放行；
#   · ★ 并发领券防超发：4 个用户并发抢 total_count=1 的券，恰好 1 人成功、库内恰好 1 张；
#   · ★ 并发锁券：同一张券被两次并发下单，恰好 1 单成功、另 1 单 422；
#   · 券归属校验：用别人的券码下单被拒（不泄露「这张券存在」以外的信息）；
#   · 门槛拒绝文案包含「门槛」二字（用户要知道差多少钱，而不是只看一个 422）；
#   · 券码不是雪花 ID（18 位数字），规避 JS Number.MAX_SAFE_INTEGER 精度坑（INV-16）；
#   · 券补偿调度是幂等的：同一轮 sweep 重复执行不产生额外副作用；
#   · 运营建券入口：未登录 401、普通用户 403（准入永远在服务端）。
#
# 设计原则（沿用前几套脚本的教训）：
#   1. 夹具从接口 / SQL 动态取，价格与 ID 不硬编码；改库前先记录、结尾自证复原；
#   2. 断言用**业务键**（订单号 / 券码），不拿 19 位雪花 id 做 JSON 比较（ConvertFrom-Json 会丢精度）；
#   3. ★「断言总数守卫」为**动态**：直接数脚本里 Check 的条数并与实跑条数比对（T-31 加强版）。
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    [string]$Marketing = 'http://127.0.0.1:8085',
    [string]$Password = 'duoduo123',
    [string]$InternalToken = 'duoduo-local-dev-internal-token',
    [int]$SKU = 2001
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

. (Join-Path $PSScriptRoot 'lib\internal-token.ps1')

function Call([string]$method, [string]$uri, $session, $bodyObj, [hashtable]$headers) {
    $params = @{ Uri = $uri; Method = $method; UseBasicParsing = $true; TimeoutSec = 25 }
    if ($session) { $params.WebSession = $session }
    # 内部凭据哨兵：'internal' 表示「这条要走服务间调用」，由这里现场签发签名
    # （签名里带一次性 nonce，必须在调用前生成，不能复用同一个请求头）
    if ($headers -and $headers.ContainsKey('X-Internal-Token') -and $headers['X-Internal-Token'] -eq 'internal') {
        $headers = @{} + $headers
        $headers['X-Internal-Token'] = (New-InternalToken -Method $method -Path ([System.Uri]$uri).AbsolutePath)
    }
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

function NewKey { return [guid]::NewGuid().ToString() }

function NewPhone([string]$prefix) { return $prefix + (Get-Random -Minimum 10000000 -Maximum 99999999) }

function Claim($sess, [long]$tplId) {
    return (Call 'Post' "$Gateway/api/coupons/templates/$tplId/claim" $sess $null)
}

function CouponStatusOf([string]$code) {
    return (Sql 'duoduo_marketing' "SELECT status FROM t_coupon WHERE code = '$code';")
}

function CouponLockedOrder([string]$code) {
    return (Sql 'duoduo_marketing' "SELECT COALESCE(locked_order_no,'') FROM t_coupon WHERE code = '$code';")
}

function PlaceOrder($sess, [string]$code, [int]$qty) {
    Call 'Post' "$Gateway/api/cart/items" $sess @{ skuId = $SKU; quantity = $qty } | Out-Null
    $body = @{
        receiverName    = '小林'
        receiverPhone   = '13900000000'
        receiverAddress = '上海市浦东新区张江路 1 号'
        idempotentKey   = (NewKey)
    }
    if ($code) { $body['couponCode'] = $code }
    return (Call 'Post' "$Gateway/api/orders" $sess $body)
}

function FirstOrderNo($resp) {
    return @($resp.body.data.orders)[0].orderNo
}

# ---------------------------------------------------------------------------
# 夹具准备
# ---------------------------------------------------------------------------
$H_INTERNAL = @{ 'X-Internal-Token' = 'internal' }
$H_BAD = @{ 'X-Internal-Token' = 'wrong-token-should-be-rejected' }

$phoneA = NewPhone '137'
$phoneB = NewPhone '136'
$phoneC = NewPhone '138'
$phoneD = NewPhone '139'
$phoneE = NewPhone '135'
$concurrentPhones = @($phoneB, $phoneC, $phoneD, $phoneE)

Write-Host ""
Write-Host "=== 多多宠物商店 · 优惠券营销（A5）冒烟 @ $Gateway ==="
Write-Host "买家 A = $phoneA ｜ 买家 B = $phoneB ｜ 并发用户 = $phoneC/$phoneD/$phoneE"
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
$mktReady = $false
for ($i = 0; $i -lt 40; $i++) {
    try {
        $h = Invoke-WebRequest -Uri "$Marketing/actuator/health" -UseBasicParsing -TimeoutSec 5
        if ($h.StatusCode -eq 200) { $mktReady = $true; break }
    } catch { Start-Sleep -Seconds 1 }
}
Check "营销服务 /actuator/health 就绪" $mktReady
if (-not $mktReady) { Write-Host "营销服务未就绪，终止"; exit 1 }

# ---------- 1. 夹具：券模板（SQL 直建，运营建券接口只验准入边界） ----------
Write-Host ""
Write-Host "> 夹具：六张券模板"
$maxTpl = [long](Sql 'duoduo_marketing' "SELECT COALESCE(MAX(id), 900000) FROM t_coupon_template;")
$TPL_LOW = $maxTpl + 1      # 满 0 减 5，主链路
$TPL_HIGH = $maxTpl + 2     # 门槛极高，用于门槛拒绝
$TPL_ONE = $maxTpl + 3      # 总量 1，用于并发发放
$TPL_OFF = $maxTpl + 4      # 已停用
$TPL_SOLD = $maxTpl + 5     # 已发完
$TPL_EXP = $maxTpl + 6      # 用于过期标记

function SeedTemplate([long]$id, [string]$name, [long]$threshold, [long]$discount, [int]$total, [int]$claimed, [string]$status) {
    $sql = "INSERT INTO t_coupon_template (id, name, description, type, threshold_amount, discount_amount, total_count, claimed_count, start_at, end_at, status, created_at, updated_at) VALUES ($id, '$name', '冒烟夹具', 'FULL_REDUCE', $threshold, $discount, $total, $claimed, UTC_TIMESTAMP() - INTERVAL 1 DAY, UTC_TIMESTAMP() + INTERVAL 365 DAY, '$status', UTC_TIMESTAMP(), UTC_TIMESTAMP());"
    return (Sql 'duoduo_marketing' $sql)
}
SeedTemplate $TPL_LOW '冒烟-满0减5' 0 500 100 0 'ACTIVE' | Out-Null
SeedTemplate $TPL_HIGH '冒烟-满999999减10' 99999900 1000 100 0 'ACTIVE' | Out-Null
SeedTemplate $TPL_ONE '冒烟-仅1张' 0 300 1 0 'ACTIVE' | Out-Null
SeedTemplate $TPL_OFF '冒烟-已停用' 0 100 100 0 'DISABLED' | Out-Null
SeedTemplate $TPL_SOLD '冒烟-已发完' 0 100 1 1 'ACTIVE' | Out-Null
SeedTemplate $TPL_EXP '冒烟-过期用' 0 100 100 0 'ACTIVE' | Out-Null
$seeded = Sql 'duoduo_marketing' "SELECT COUNT(*) FROM t_coupon_template WHERE id IN ($TPL_LOW,$TPL_HIGH,$TPL_ONE,$TPL_OFF,$TPL_SOLD,$TPL_EXP);"
Check "（夹具）六张券模板已建立" ($seeded -eq '6') $seeded

# ---------- 2. 账号与业务夹具 ----------
Write-Host ""
Write-Host "> 账号夹具"
$regA = Register $phoneA '券测试-买家A'
Check "买家 A 注册/登录成功" ($regA.result.status -eq 200) $regA.result.status
$sessA = $regA.session
$regB = Register $phoneB '券测试-买家B'
Check "买家 B 注册/登录成功" ($regB.result.status -eq 200) $regB.result.status
$sessB = $regB.session
$concOk = $true
foreach ($p in @($phoneC, $phoneD, $phoneE)) {
    $rg = Register $p '券测试-并发用户'
    if ($rg.result.status -ne 200) { $concOk = $false }
}
Check "三个并发用户注册成功（B 复用同一部手机号）" $concOk

$idA = Sql 'duoduo_user' "SELECT id FROM t_user WHERE phone = '$phoneA';"
$idB = Sql 'duoduo_user' "SELECT id FROM t_user WHERE phone = '$phoneB';"
Check "（前置）取得买家 A / B 的 userId" ((-not [string]::IsNullOrEmpty($idA)) -and (-not [string]::IsNullOrEmpty($idB))) "A=$idA B=$idB"

$batch = Call 'Get' "$Gateway/api/products/internal/skus/batch?ids=$SKU" $null $null $H_INTERNAL
$snap = @($batch.body.data)[0]
$SKU_PRICE = [long]$snap.price
$stockBefore = [int](Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU;")
Write-Host "  SKU $SKU 单价 $SKU_PRICE 分，可售 $stockBefore"
Check "（夹具）SKU $SKU 单价 > 抵扣额 500 分（保证 payAmount 不为负）" ($SKU_PRICE -gt 500) $SKU_PRICE

$anon = New-Object Microsoft.PowerShell.Commands.WebRequestSession

# ---------- 3. 领券中心：公开只读边界 ----------
Write-Host ""
Write-Host "> 领券中心（公开只读）"
$rListAnon = Call 'Get' "$Gateway/api/coupons/templates" $anon $null
Check "① 未登录可读领券中心（200）" ($rListAnon.status -eq 200) $rListAnon.status
$ids = @($rListAnon.body.data | ForEach-Object { [long]$_.id })
Check "① 领券中心含「满0减5」夹具" ($ids -contains [long]$TPL_LOW) ($ids -join ',')
Check "① 领券中心含「门槛极高」夹具" ($ids -contains [long]$TPL_HIGH) ($ids -join ',')
Check "① 领券中心**不含**已停用券" (-not ($ids -contains [long]$TPL_OFF))
$offView = @($rListAnon.body.data | Where-Object { [long]$_.id -eq [long]$TPL_SOLD })[0]
Check "① 已发完的券仍在列表中但 claimable=false" (($null -ne $offView) -and (-not $offView.claimable))
Check "① 已发完的券给出原因「已被领完」（先于登录判断）" ($offView.claimableReason -eq '已被领完') $offView.claimableReason
$lowView = @($rListAnon.body.data | Where-Object { [long]$_.id -eq [long]$TPL_LOW })[0]
Check "① 未登录访客：可领券也标 claimable=false（避免给一个注定 401 的按钮）" (-not $lowView.claimable)
Check "① 未登录访客的原因文案为「登录后可领取」" ($lowView.claimableReason -eq '登录后可领取') $lowView.claimableReason
$rListAuth = Call 'Get' "$Gateway/api/coupons/templates" $sessA $null
Check "① 登录后可读领券中心（200）" ($rListAuth.status -eq 200) $rListAuth.status
$lowViewAuth = @($rListAuth.body.data | Where-Object { [long]$_.id -eq [long]$TPL_LOW })[0]
Check "① 登录后同一张券 claimable=true 且无原因文案" (($lowViewAuth.claimable) -and ($null -eq $lowViewAuth.claimableReason)) ($lowViewAuth | ConvertTo-Json -Compress)
Check "① 券类型中文文案为「满减券」" ($lowViewAuth.typeLabel -eq '满减券') $lowViewAuth.typeLabel
Check "① 剩余量 = 总量 - 已领（100 = 100 - 0）" ($lowViewAuth.remainingCount -eq 100) $lowViewAuth.remainingCount
Check "① 时间字段为 ISO8601 UTC（以 Z 结尾）" ($lowViewAuth.endAt -match 'Z$') $lowViewAuth.endAt
Check "① 列表顺序稳定（按 id 升序）" ($ids[0] -lt $ids[-1]) ($ids -join ',')
Check "① 匿名访问「我的券包」被网关拦下（401）" ((Call 'Get' "$Gateway/api/coupons/mine" $anon $null).status -eq 401)
Check "① 匿名领券被网关拦下（401）" ((Claim $anon $TPL_LOW).status -eq 401)
$rOp = Call 'Post' "$Gateway/api/coupons/admin/templates" $anon @{ name = 'x'; type = 'FULL_REDUCE'; thresholdAmount = 0; discountAmount = 1; totalCount = 1; startAt = '2026-01-01T00:00:00'; endAt = '2026-12-31T00:00:00' }
Check "① 匿名建券被网关拦下（401）" ($rOp.status -eq 401) $rOp.status
$rOp2 = Call 'Post' "$Gateway/api/coupons/admin/templates" $sessA @{ name = 'x'; type = 'FULL_REDUCE'; thresholdAmount = 0; discountAmount = 1; totalCount = 1; startAt = '2026-01-01T00:00:00'; endAt = '2026-12-31T00:00:00' }
Check "① 普通用户建券被拒（403，准入在服务端）" ($rOp2.status -eq 403) "$($rOp2.status)/$($rOp2.body.code)"

# ---------- 4. 领券与幂等（验收①） ----------
Write-Host ""
Write-Host "> 验收①：领券幂等（唯一索引兜底）"
$rC1 = Claim $sessA $TPL_LOW
Check "① 买家 A 领取「满0减5」成功（200）" ($rC1.status -eq 200) "$($rC1.status)/$($rC1.raw)"
$CODE_A = $rC1.body.data.code
Check "① 返回券码非空" (-not [string]::IsNullOrEmpty($CODE_A)) $CODE_A
Check "★ 券码不是雪花 ID（非 18 位纯数字，规避 JS 精度坑 INV-16）" ($CODE_A -notmatch '^\d{15,}$') $CODE_A
Check "① 券码前缀为 CP（可读、可电话报读）" ($CODE_A -like 'CP*') $CODE_A
Check "① 返回的门槛与抵扣与模板一致（0 / 500）" (($rC1.body.data.thresholdAmount -eq 0) -and ($rC1.body.data.discountAmount -eq 500)) "$($rC1.body.data.thresholdAmount)/$($rC1.body.data.discountAmount)"
Check "① 首次领取 replay=false" ($rC1.body.data.replay -eq $false) $rC1.body.data.replay
$rC2 = Claim $sessA $TPL_LOW
Check "① 重复领取返回 200（不报错，用户双击是常态）" ($rC2.status -eq 200) $rC2.status
Check "① 重复领取 replay=true" ($rC2.body.data.replay -eq $true) $rC2.body.data.replay
Check "① 重复领取返回同一张券码（没有新建）" ($rC2.body.data.code -eq $CODE_A) "$($rC2.body.data.code) vs $CODE_A"
$dbCnt = Sql 'duoduo_marketing' "SELECT COUNT(*) FROM t_coupon WHERE user_id = $idA AND template_id = $TPL_LOW;"
Check "① 库内该用户该模板只有 1 行（唯一索引生效）" ($dbCnt -eq '1') $dbCnt
$claimed = Sql 'duoduo_marketing' "SELECT claimed_count FROM t_coupon_template WHERE id = $TPL_LOW;"
Check "① 重复领取**没有**多占额度（claimed_count 仍为 1）" ($claimed -eq '1') $claimed
Check "① 重复领取后模板剩余量仍为 99" ("$(Sql 'duoduo_marketing' "SELECT total_count - claimed_count FROM t_coupon_template WHERE id = $TPL_LOW;")" -eq '99')
$rC3 = Claim $sessB $TPL_LOW
Check "① 另一用户可领同一模板（一人一张，不是一模板一张）" ($rC3.status -eq 200) $rC3.status
$CODE_B = $rC3.body.data.code
Check "① 买家 B 的券码与 A 不同" ($CODE_B -ne $CODE_A) "$CODE_B vs $CODE_A"
Check "① 两位用户领取后模板 claimed_count 正确（2）" ("$(Sql 'duoduo_marketing' "SELECT claimed_count FROM t_coupon_template WHERE id = $TPL_LOW;")" -eq '2')

# ---------- 5. 领券边界 ----------
Write-Host ""
Write-Host "> 领券边界：不存在 / 已停用 / 已发完"
$r = Claim $sessA 999999999999
Check "领不存在的模板 → 404" ($r.status -eq 404) "$($r.status)/$($r.body.code)"
$r = Claim $sessA $TPL_OFF
Check "领已停用的券 → 422" ($r.status -eq 422) "$($r.status)/$($r.body.code)"
Check "已停用券的提示明确（含「停用」）" ($r.body.message -match '停用') $r.body.message
$r = Claim $sessA $TPL_SOLD
Check "领已发完的券 → 422" ($r.status -eq 422) "$($r.status)/$($r.body.code)"
Check "已发完的提示明确（含「领完」）" ($r.body.message -match '领完') $r.body.message
Check "领不到时不占用额度（claimed_count 未变）" ("$(Sql 'duoduo_marketing' "SELECT claimed_count FROM t_coupon_template WHERE id = $TPL_SOLD;")" -eq '1')
Check "领不到时不产生券记录" ("$(Sql 'duoduo_marketing' "SELECT COUNT(*) FROM t_coupon WHERE user_id = $idA AND template_id = $TPL_SOLD;")" -eq '0')

# ---------- 6. ★ 并发领券防超发（验收②） ----------
Write-Host ""
Write-Host "> 验收②：4 个用户并发抢「仅 1 张」的券"
$jobClaim = {
    param($gw, $phone, $pw, $tplId)
    $s = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    try {
        Invoke-WebRequest -Uri "$gw/api/auth/login" -Method Post -WebSession $s -UseBasicParsing -TimeoutSec 25 `
            -Body (@{ phone = $phone; password = $pw } | ConvertTo-Json -Compress) -ContentType 'application/json; charset=utf-8' | Out-Null
        $r = Invoke-WebRequest -Uri "$gw/api/coupons/templates/$tplId/claim" -Method Post -WebSession $s -UseBasicParsing -TimeoutSec 25
        return "OK:" + [int]$r.StatusCode
    } catch {
        $c = 0; try { $c = [int]$_.Exception.Response.StatusCode.value__ } catch { }
        return "ERR:$c"
    }
}
$jobs = @()
foreach ($p in $concurrentPhones) { $jobs += Start-Job -ScriptBlock $jobClaim -ArgumentList $Gateway, $p, $Password, $TPL_ONE }
$jobs | Wait-Job -Timeout 150 | Out-Null
$results = @($jobs | Receive-Job)
$jobs | Remove-Job -Force
$okCount = @($results | Where-Object { "$_" -like 'OK:*' }).Count
$rejCount = @($results | Where-Object { "$_" -eq 'ERR:422' }).Count
Check "★ 并发 4 请求：恰好 1 个成功（不超发）" ($okCount -eq 1) ($results -join ',')
Check "★ 并发 4 请求：其余 3 个均为 422（被 CAS 拦下，不是 500）" ($rejCount -eq 3) ($results -join ',')
$oneRows = Sql 'duoduo_marketing' "SELECT COUNT(*) FROM t_coupon WHERE template_id = $TPL_ONE;"
Check "★ 库内该模板只发出 1 张券" ($oneRows -eq '1') $oneRows
$oneClaimed = Sql 'duoduo_marketing' "SELECT claimed_count FROM t_coupon_template WHERE id = $TPL_ONE;"
Check "★ 库内 claimed_count 恰好为 1（无凭空自增）" ($oneClaimed -eq '1') $oneClaimed

# ---------- 7. 结算页可用券 ----------
Write-Host ""
Write-Host "> 结算页可用券（按订单金额筛选）"
$rH = Claim $sessA $TPL_HIGH
Check "买家 A 领取「门槛极高」券成功（门槛拒绝用）" ($rH.status -eq 200) "$($rH.status)/$($rH.raw)"
$CODE_HIGH = $rH.body.data.code
$rE = Claim $sessA $TPL_EXP
Check "买家 A 领取「过期用」券成功" ($rE.status -eq 200) $rE.status
$CODE_EXP = $rE.body.data.code

$rUsable = Call 'Get' "$Gateway/api/coupons/mine/usable?amount=$SKU_PRICE" $sessA $null
Check "可用券查询返回 200" ($rUsable.status -eq 200) "$($rUsable.status)/$($rUsable.raw)"
$rows = @($rUsable.body.data.coupons)
Check "可用券列表含 A 的全部 3 张未使用券" ($rows.Count -eq 3) $rows.Count
$rowLow = @($rows | Where-Object { $_.code -eq $CODE_A })[0]
$rowHigh = @($rows | Where-Object { $_.code -eq $CODE_HIGH })[0]
Check "满 0 门槛的券在任意金额下 usable=true" ($rowLow.usable -eq $true) ($rowLow | ConvertTo-Json -Compress)
Check "高门槛券在低金额下 usable=false" ($rowHigh.usable -eq $false) ($rowHigh | ConvertTo-Json -Compress)
Check "不可用原因点明「门槛」（用户要知道差多少钱）" ($rowHigh.reason -match '门槛') $rowHigh.reason
Check "★ 排序：抵扣额大的在前（1000 / 500 / 100）" ($rows[0].discountAmount -ge $rows[-1].discountAmount) ((@($rows | ForEach-Object { $_.discountAmount })) -join ',')
Check "★ 最优券由服务端挑出（bestDiscountAmount=500，对应满0减5）" (($rUsable.body.data.bestDiscountAmount -eq 500) -and ($rUsable.body.data.bestCouponCode -eq $CODE_A)) "$($rUsable.body.data.bestDiscountAmount)/$($rUsable.body.data.bestCouponCode)"
$rUsableHigh = Call 'Get' "$Gateway/api/coupons/mine/usable?amount=99999900" $sessA $null
Check "★ 金额抬高后最优券切换为高门槛券（抵扣 1000）" ($rUsableHigh.body.data.bestDiscountAmount -eq 1000) $rUsableHigh.body.data.bestDiscountAmount
Check "金额抬高后高门槛券变为可用" ((@($rUsableHigh.body.data.coupons | Where-Object { $_.code -eq $CODE_HIGH })[0]).usable -eq $true)
Check "金额为负 → 400（不静默按 0 处理）" ((Call 'Get' "$Gateway/api/coupons/mine/usable?amount=-1" $sessA $null).status -eq 400)
Check "非法状态筛选 → 400（不是返回空列表）" ((Call 'Get' "$Gateway/api/coupons/mine?status=NOPE" $sessA $null).status -eq 400)

# ---------- 8. 下单用券主链路（验收③④） ----------
Write-Host ""
Write-Host "> 验收③④：下单锁券 + 金额落账"
$rOrder1 = PlaceOrder $sessA $CODE_A 1
Check "用券下单成功（200）" ($rOrder1.status -eq 200) "$($rOrder1.status)/$($rOrder1.raw)"
$O1 = FirstOrderNo $rOrder1
Check "下单结果含订单号" (-not [string]::IsNullOrEmpty($O1)) $O1
$ov = @($rOrder1.body.data.orders)[0]
Check "④ 订单 discountAmount = 500（营销域算出、交易域照抄）" ($ov.discountAmount -eq 500) $ov.discountAmount
Check "④ 订单 couponCode 回写为本单券码" ($ov.couponCode -eq $CODE_A) $ov.couponCode
Check "④ 应付 = 商品总额 - 抵扣（口径自洽）" ($ov.payAmount -eq ($ov.totalAmount - $ov.discountAmount)) "$($ov.payAmount)/$($ov.totalAmount)/$($ov.discountAmount)"
$dbOrder = Sql 'duoduo_trade' "SELECT CONCAT(discount_amount,',',coupon_code,',',pay_amount) FROM t_order WHERE order_no = '$O1';"
Check "④ 库内 t_order 三列一致（discount_amount / coupon_code / pay_amount）" ($dbOrder -eq "500,$CODE_A,$($ov.payAmount)") $dbOrder
Check "③ 券状态变为 LOCKED" ("$(CouponStatusOf $CODE_A)" -eq 'LOCKED') (CouponStatusOf $CODE_A)
Check "③ 券锁定到本单（locked_order_no = 订单号）" ("$(CouponLockedOrder $CODE_A)" -eq "$O1") "$(CouponLockedOrder $CODE_A) vs $O1"
$rOrder2 = PlaceOrder $sessA $CODE_A 1
Check "③ 同一张券再用一次 → 422（已被占用）" ($rOrder2.status -eq 422) "$($rOrder2.status)/$($rOrder2.raw)"
Check "③ 拒绝原因明确（含「占用」）" ($rOrder2.body.message -match '占用') $rOrder2.body.message
$rOrder3 = PlaceOrder $sessA $CODE_B 1
Check "券归属校验：用别人的券码 → 422" ($rOrder3.status -eq 422) "$($rOrder3.status)/$($rOrder3.raw)"
Check "归属拒绝文案含「不属于」" ($rOrder3.body.message -match '不属于') $rOrder3.body.message
$rOrder4 = PlaceOrder $sessA 'CPNOTEXIST0000' 1
Check "不存在的券码 → 422" ($rOrder4.status -eq 422) "$($rOrder4.status)/$($rOrder4.raw)"
Check "不存在券码的提示为「优惠券不存在」" ($rOrder4.body.message -match '不存在') $rOrder4.body.message
$rOrder5 = PlaceOrder $sessA $CODE_HIGH 1
Check "门槛不满足 → 422" ($rOrder5.status -eq 422) "$($rOrder5.status)/$($rOrder5.raw)"
Check "★ 门槛拒绝文案含「门槛」（用户知道差多少钱）" ($rOrder5.body.message -match '门槛') $rOrder5.body.message
Check "门槛被拒后该券仍为 UNUSED（没有被误锁）" ("$(CouponStatusOf $CODE_HIGH)" -eq 'UNUSED') (CouponStatusOf $CODE_HIGH)

# ---------- 9. 支付核销（验收⑤） ----------
Write-Host ""
Write-Host "> 验收⑤：支付成功核销券"
$rPay = Call 'Post' "$Gateway/api/orders/$O1/pay" $sessA @{ channel = 'MOCK'; idempotentKey = (NewKey) }
Check "用券订单支付成功" ($rPay.status -eq 200) "$($rPay.status)/$($rPay.raw)"
Check "③ 券状态变为 USED（终态）" ("$(CouponStatusOf $CODE_A)" -eq 'USED') (CouponStatusOf $CODE_A)
Check "③ 核销记录指向本单（used_order_no = 订单号）" ("$(Sql 'duoduo_marketing' "SELECT COALESCE(used_order_no,'') FROM t_coupon WHERE code = '$CODE_A';")" -eq "$O1")
Check "③ 核销后锁定字段被保留（可追溯哪单用过）" ("$(Sql 'duoduo_marketing' "SELECT COALESCE(locked_order_no,'') FROM t_coupon WHERE code = '$CODE_A';")" -eq "$O1")
$rConsume = Call 'Post' "$Marketing/api/coupons/internal/consume" $null @{ orderNo = $O1 } $H_INTERNAL
Check "重复核销返回 consumed=0（USED 是终态，不重复记账）" ($rConsume.body.data.consumed -eq 0) ($rConsume.body | ConvertTo-Json -Compress)
$rRelUsed = Call 'Post' "$Marketing/api/coupons/internal/release" $null @{ orderNo = $O1 } $H_INTERNAL
Check "已核销的券不可被解锁（released=0）" ($rRelUsed.body.data.released -eq 0) ($rRelUsed.body | ConvertTo-Json -Compress)
Check "★ 券仍是 USED（终态不可回退）" ("$(CouponStatusOf $CODE_A)" -eq 'USED') (CouponStatusOf $CODE_A)

# ---------- 10. 取消订单解锁券 ----------
Write-Host ""
Write-Host "> 验收⑤：取消订单解锁券"
$rOrderB = PlaceOrder $sessB $CODE_B 1
Check "买家 B 用券下单成功" ($rOrderB.status -eq 200) "$($rOrderB.status)/$($rOrderB.raw)"
$OB = FirstOrderNo $rOrderB
Check "券 B 已被锁定到 B 的订单" ("$(CouponLockedOrder $CODE_B)" -eq "$OB") (CouponLockedOrder $CODE_B)
$rCancel = Call 'Post' "$Gateway/api/orders/$OB/cancel" $sessB @{ reason = '冒烟：取消解锁' }
Check "取消订单成功" ($rCancel.status -eq 200) "$($rCancel.status)/$($rCancel.raw)"
Check "★ 取消后券回到 UNUSED（用户立刻能用回来）" ("$(CouponStatusOf $CODE_B)" -eq 'UNUSED') (CouponStatusOf $CODE_B)
Check "★ 取消后券的锁定字段被清空" ("$(CouponLockedOrder $CODE_B)" -eq '') (CouponLockedOrder $CODE_B)
$rCancel2 = Call 'Post' "$Gateway/api/orders/$OB/cancel" $sessB @{ reason = '重复取消' }
Check "重复取消被拒不产生二次副作用（4xx）" ($rCancel2.status -ge 400 -and $rCancel2.status -lt 500) $rCancel2.status
Check "重复取消后券仍为 UNUSED" ("$(CouponStatusOf $CODE_B)" -eq 'UNUSED') (CouponStatusOf $CODE_B)

# ---------- 11. ★ 并发锁券（验收③） ----------
Write-Host ""
Write-Host "> 验收③：同一张券被两次并发下单，只能成功一单"
$jobOrder = {
    param($gw, $phone, $pw, $sku, $code)
    $s = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    try {
        Invoke-WebRequest -Uri "$gw/api/auth/login" -Method Post -WebSession $s -UseBasicParsing -TimeoutSec 25 `
            -Body (@{ phone = $phone; password = $pw } | ConvertTo-Json -Compress) -ContentType 'application/json; charset=utf-8' | Out-Null
        # 购物车必须先有货，否则下单会以「购物车为空」失败，测不到锁券竞态
        Invoke-WebRequest -Uri "$gw/api/cart/items" -Method Post -WebSession $s -UseBasicParsing -TimeoutSec 25 `
            -Body (@{ skuId = $sku; quantity = 1 } | ConvertTo-Json -Compress) -ContentType 'application/json; charset=utf-8' | Out-Null
        $body = @{ receiverName = '小林'; receiverPhone = '13900000000'; receiverAddress = '上海市浦东新区张江路 1 号'; idempotentKey = [guid]::NewGuid().ToString(); couponCode = $code }
        $r = Invoke-WebRequest -Uri "$gw/api/orders" -Method Post -WebSession $s -UseBasicParsing -TimeoutSec 25 `
            -Body ($body | ConvertTo-Json -Compress) -ContentType 'application/json; charset=utf-8'
        $j = $r.Content | ConvertFrom-Json
        return "OK:" + @($j.data.orders)[0].orderNo
    } catch {
        $c = 0; try { $c = [int]$_.Exception.Response.StatusCode.value__ } catch { }
        return "ERR:$c"
    }
}
$jobs2 = @()
for ($i = 0; $i -lt 2; $i++) { $jobs2 += Start-Job -ScriptBlock $jobOrder -ArgumentList $Gateway, $phoneB, $Password, $SKU, $CODE_B }
$jobs2 | Wait-Job -Timeout 150 | Out-Null
$res2 = @($jobs2 | Receive-Job)
$jobs2 | Remove-Job -Force
$ok2 = @($res2 | Where-Object { "$_" -like 'OK:*' })
$rej2 = @($res2 | Where-Object { "$_" -eq 'ERR:422' })
Check "★ 并发下单用同一张券：恰好 1 单成功" ($ok2.Count -eq 1) ($res2 -join ',')
Check "★ 并发下单用同一张券：另 1 单 422" ($rej2.Count -eq 1) ($res2 -join ',')
$O2 = ''
if ($ok2.Count -ge 1) { $O2 = "$($ok2[0])".Substring(3) }
Check "★ 券只被一个订单锁住" ("$(CouponLockedOrder $CODE_B)" -eq "$O2") "$(CouponLockedOrder $CODE_B) vs $O2"
Check "★ 库内只有 1 条锁定记录指向该券" ("$(Sql 'duoduo_marketing' "SELECT COUNT(*) FROM t_coupon WHERE code = '$CODE_B' AND status = 'LOCKED';")" -eq '1')
if ($O2) {
    $rCancel3 = Call 'Post' "$Gateway/api/orders/$O2/cancel" $sessB @{ reason = '冒烟：并发后清理' }
} else {
    $rCancel3 = @{ status = 0; body = $null; raw = 'no-order-created' }
}
Check "并发成功的订单可正常取消" ($rCancel3.status -eq 200) $rCancel3.status
Check "取消后券回到 UNUSED（可再次参与后续用例）" ("$(CouponStatusOf $CODE_B)" -eq 'UNUSED') (CouponStatusOf $CODE_B)

# ---------- 12. 内部接口凭据边界 ----------
Write-Host ""
Write-Host "> 内部接口凭据边界（失败关闭）"
$lockBody = @{ code = $CODE_B; userId = [long]$idB; orderNo = 'SMOKE-INTERNAL-1'; orderAmount = 100000 }
$lockBodyA = @{ code = $CODE_B; userId = [long]$idA; orderNo = 'SMOKE-INTERNAL-2'; orderAmount = 100000 }
Check "内部 lock 无凭据 → 403" ((Call 'Post' "$Marketing/api/coupons/internal/lock" $null $lockBody $null).status -eq 403)
Check "内部 lock 错凭据 → 403" ((Call 'Post' "$Marketing/api/coupons/internal/lock" $null $lockBody $H_BAD).status -eq 403)
Check "内部 release 无凭据 → 403" ((Call 'Post' "$Marketing/api/coupons/internal/release" $null @{ orderNo = 'X' } $null).status -eq 403)
Check "内部 consume 无凭据 → 403" ((Call 'Post' "$Marketing/api/coupons/internal/consume" $null @{ orderNo = 'X' } $null).status -eq 403)
Check "内部 sweep 无凭据 → 403" ((Call 'Post' "$Marketing/api/coupons/internal/sweep" $null $null $null).status -eq 403)
Check "内部路径不经网关放行（走网关 401）" ((Call 'Post' "$Gateway/api/coupons/internal/lock" $null $lockBody $H_INTERNAL).status -eq 401)
$rLockBad = Call 'Post' "$Marketing/api/coupons/internal/lock" $null $lockBodyA $H_INTERNAL
Check "内部 lock：券不属于该用户 → success=false（不抛异常，回执可读）" ($rLockBad.body.data.success -eq $false) ($rLockBad.body | ConvertTo-Json -Compress)
Check "内部 lock 归属拒绝给出人话原因" ($rLockBad.body.data.reason -match '不属于') $rLockBad.body.data.reason
$rLockOk = Call 'Post' "$Marketing/api/coupons/internal/lock" $null $lockBody $H_INTERNAL
Check "内部 lock（持凭据、归属正确）→ success=true" ($rLockOk.body.data.success -eq $true) ($rLockOk.body | ConvertTo-Json -Compress)
Check "内部 lock 返回抵扣额 500" ($rLockOk.body.data.discountAmount -eq 500) $rLockOk.body.data.discountAmount
Check "内部 lock 后券为 LOCKED" ("$(CouponStatusOf $CODE_B)" -eq 'LOCKED') (CouponStatusOf $CODE_B)
$rRelOk = Call 'Post' "$Marketing/api/coupons/internal/release" $null @{ orderNo = 'SMOKE-INTERNAL-1' } $H_INTERNAL
Check "内部 release → released=1" ($rRelOk.body.data.released -eq 1) ($rRelOk.body | ConvertTo-Json -Compress)
Check "内部 release 后券回到 UNUSED" ("$(CouponStatusOf $CODE_B)" -eq 'UNUSED') (CouponStatusOf $CODE_B)
Check "内部 release 幂等：再调一次 released=0" ((Call 'Post' "$Marketing/api/coupons/internal/release" $null @{ orderNo = 'SMOKE-INTERNAL-1' } $H_INTERNAL).body.data.released -eq 0)
Check "解锁后的券可被再次锁定（LOCKED 不是终态）" ((Call 'Post' "$Marketing/api/coupons/internal/lock" $null $lockBody $H_INTERNAL).body.data.success -eq $true)
Check "确认已再次锁定" ("$(CouponStatusOf $CODE_B)" -eq 'LOCKED') (CouponStatusOf $CODE_B)

# ---------- 13. 过期与 TTL 兜底（验收⑤） ----------
Write-Host ""
Write-Host "> 验收⑤：TTL 兜底解锁 + 过期标记（幂等）"
$nowUtcSql = Sql 'duoduo_marketing' "SELECT UTC_TIMESTAMP();"
Check "（前置）取得库内 UTC 时间作为锚点" (-not [string]::IsNullOrEmpty($nowUtcSql)) $nowUtcSql
$env:PATH = 'C:\Program Files\Docker\Docker\resources\bin;' + $env:PATH
if (-not $env:DOCKER_CONFIG) { $env:DOCKER_CONFIG = 'D:\duoduo-infra\docker-nocreds' }
$ttlEnv = "$(& docker exec duoduo-app-marketing sh -c 'echo $COUPON_LOCK_TTL_MINUTES' 2>$null)".Trim()
Check "★ 券锁定 TTL 已由环境变量注入（值为 35 分钟）" ($ttlEnv -eq '35') $ttlEnv
Check "★ 券 TTL(35) 严格大于交易域订单超时(30)：订单还活着时券不会被别人领走" (($ttlEnv -match '^\d+$') -and (([int]$ttlEnv) -gt 30)) $ttlEnv

# ★ 这里存在一个**结构性的时序竞态**，而且不是"多试几次"能解决的：
#   定时补偿调度每 60s 扫一次，而本段夹具**故意把这张券做成"锁定超时"** ——
#   也就是**主动让定时调度随时可以合法地把它解掉**。于是「改成超时」与「断言它处于 LOCKED」
#   之间只要插进一次调度，夹具断言就红。**这不是功能坏了，是我们自己在和一个每 60 秒必然发生的
#   动作赛跑。**（实测见过：两个断言一红一绿 —— 状态被解成 UNUSED，而 locked_at 仍是旧值，
#   正好印证是"读到一半被调度改了"。）
#
#   修法不是重试：重试同样跑不过调度（每次都重新留一个新窗口）。而是**把"设夹具"与"读夹具"
#   之间的窗口压到零** —— 用**一条 SQL 同时读出** status 与"是否已超时"，之后所有断言都基于
#   这一次读取的快照，不再各自去查一次（每单独查一次就是一个新窗口）。
#   `$fixtureSnapshot` 形如 `LOCKED|1`。
$fixtureSnapshot = ''
for ($attempt = 1; $attempt -le 3; $attempt++) {
    # 若这张券已被定时调度抢先解锁，先重新锁定再制造「超时」场景
    if ((CouponStatusOf $CODE_B) -ne 'LOCKED') {
        Call 'Post' "$Marketing/api/coupons/internal/lock" $null @{ code = $CODE_B; userId = [long]$idB; orderNo = 'SMOKE-TTL-RE'; orderAmount = 100000 } $H_INTERNAL | Out-Null
    }
    # 把 B 的券改成「40 分钟前锁定」——远超 lock-ttl-minutes(35)
    Sql 'duoduo_marketing' "UPDATE t_coupon SET locked_at = UTC_TIMESTAMP() - INTERVAL 40 MINUTE WHERE code = '$CODE_B';" | Out-Null
    # ★ 一次读取同时拿到两个事实，中间不给调度留缝
    $fixtureSnapshot = "$(Sql 'duoduo_marketing' "SELECT CONCAT(status, '|', IFNULL(locked_at < UTC_TIMESTAMP() - INTERVAL 35 MINUTE, 0)) FROM t_coupon WHERE code = '$CODE_B';")"
    if ($fixtureSnapshot -eq 'LOCKED|1') { break }
    Start-Sleep -Milliseconds 250
}
Check "（夹具）券处于 LOCKED，可制造「锁定超时」场景" ($fixtureSnapshot -like 'LOCKED|*') $fixtureSnapshot
Check "（夹具）券已被人为改成「锁定超时」" ($fixtureSnapshot -eq 'LOCKED|1') "快照=$fixtureSnapshot（尝试 $attempt 次）"
$rSweep = Call 'Post' "$Marketing/api/coupons/internal/sweep" $null $null $H_INTERNAL
Check "补偿调度（带凭据）返回 200" ($rSweep.status -eq 200) "$($rSweep.status)/$($rSweep.raw)"
# 判据取**终态**而非「这一次调用解掉了几张」：定时调度同样解超时锁，
# 它抢先做过之后这一调用的 released 就是 0 —— 但「超时锁不会被永久锁死」这条不变式已经成立。
# 反向看仍有力：调度真的坏掉时券会停在 LOCKED，此处必然红。
Check "★ TTL 兜底解锁生效（released >= 1，或定时调度已先行解开）" (($rSweep.body.data.released -ge 1) -or ("$(CouponStatusOf $CODE_B)" -ne 'LOCKED')) ($rSweep.body | ConvertTo-Json -Compress)
Check "★ 超时锁定的券回到 UNUSED（不会被永久锁死）" ("$(CouponStatusOf $CODE_B)" -eq 'UNUSED') (CouponStatusOf $CODE_B)
$rSweep2 = Call 'Post' "$Marketing/api/coupons/internal/sweep" $null $null $H_INTERNAL
Check "★ 补偿调度幂等：第二次 released=0（无 LOCKED 可解，无重复动作）" ($rSweep2.body.data.released -eq 0) ($rSweep2.body | ConvertTo-Json -Compress)
# 过期标记：把 A 的过期用例券改为「已过期」
Sql 'duoduo_marketing' "UPDATE t_coupon SET expire_at = UTC_TIMESTAMP() - INTERVAL 1 DAY WHERE code = '$CODE_EXP';" | Out-Null
Check "（夹具）券已被人为改成「已过期」" ("$(Sql 'duoduo_marketing' "SELECT expire_at < UTC_TIMESTAMP() FROM t_coupon WHERE code = '$CODE_EXP';")" -eq '1')
$rSweep3 = Call 'Post' "$Marketing/api/coupons/internal/sweep" $null $null $H_INTERNAL
Check "★ 过期券被标记为 EXPIRED（expired >= 1）" ($rSweep3.body.data.expired -ge 1) ($rSweep3.body | ConvertTo-Json -Compress)
Check "★ 过期券状态为 EXPIRED（终态）" ("$(CouponStatusOf $CODE_EXP)" -eq 'EXPIRED') (CouponStatusOf $CODE_EXP)
$rUsableAfter = Call 'Get' "$Gateway/api/coupons/mine/usable?amount=$SKU_PRICE" $sessA $null
$expStillListed = @($rUsableAfter.body.data.coupons | Where-Object { $_.code -eq $CODE_EXP }).Count
Check "★ 已过期券不再出现在结算页可用券里" ($expStillListed -eq 0) $expStillListed
$rPayExpired = PlaceOrder $sessA $CODE_EXP 1
Check "★ 用已过期券下单 → 422" ($rPayExpired.status -eq 422) "$($rPayExpired.status)/$($rPayExpired.raw)"

# ---------- 14. 我的券包 ----------
Write-Host ""
Write-Host "> 我的券包"
$rMine = Call 'Get' "$Gateway/api/coupons/mine" $sessA $null
Check "我的券包返回 200" ($rMine.status -eq 200) $rMine.status
$mineAll = @($rMine.body.data)
Check "券包含 A 的全部券（3 张：已用 / 高门槛未用 / 已过期）" ($mineAll.Count -eq 3) $mineAll.Count
$usedRow = @($mineAll | Where-Object { $_.code -eq $CODE_A })[0]
Check "已用券状态文案为「已使用」（服务端给文案，前端不维护映射）" ($usedRow.statusLabel -eq '已使用') $usedRow.statusLabel
Check "已用券带回核销订单号（可追溯）" ($usedRow.usedOrderNo -eq $O1) $usedRow.usedOrderNo
$rMineUnused = Call 'Get' "$Gateway/api/coupons/mine?status=UNUSED" $sessA $null
Check "按状态筛选：UNUSED 只回未使用券（1 张）" (@($rMineUnused.body.data).Count -eq 1) (@($rMineUnused.body.data).Count)
Check "筛选结果不含已使用券" (@($rMineUnused.body.data | Where-Object { $_.code -eq $CODE_A }).Count -eq 0)
Check "券包不含其他用户的券（B 的券不在 A 的列表里）" (@($mineAll | Where-Object { $_.code -eq $CODE_B }).Count -eq 0)

# ---------- 15. 清理与复原 ----------
Write-Host ""
Write-Host "> 清理夹具与自证复原"
Sql 'duoduo_marketing' "DELETE FROM t_coupon WHERE template_id IN ($TPL_LOW,$TPL_HIGH,$TPL_ONE,$TPL_OFF,$TPL_SOLD,$TPL_EXP);" | Out-Null
$leftCoupon = Sql 'duoduo_marketing' "SELECT COUNT(*) FROM t_coupon WHERE template_id IN ($TPL_LOW,$TPL_HIGH,$TPL_ONE,$TPL_OFF,$TPL_SOLD,$TPL_EXP);"
Check "券夹具已删除（库内复原）" ($leftCoupon -eq '0') $leftCoupon
Sql 'duoduo_marketing' "DELETE FROM t_coupon_template WHERE id IN ($TPL_LOW,$TPL_HIGH,$TPL_ONE,$TPL_OFF,$TPL_SOLD,$TPL_EXP);" | Out-Null
$leftTpl = Sql 'duoduo_marketing' "SELECT COUNT(*) FROM t_coupon_template WHERE id IN ($TPL_LOW,$TPL_HIGH,$TPL_ONE,$TPL_OFF,$TPL_SOLD,$TPL_EXP);"
Check "模板夹具已删除（库内复原）" ($leftTpl -eq '0') $leftTpl
Check "★ 种子券模板未被误删（5001/5002 仍在）" ("$(Sql 'duoduo_marketing' "SELECT COUNT(*) FROM t_coupon_template WHERE id IN (5001,5002);")" -eq '2')
Sql 'duoduo_product' "UPDATE t_sku SET stock = $stockBefore, frozen_stock = 0 WHERE id = $SKU;" | Out-Null
Check "★ SKU $SKU 库存已复原为测试前的 $stockBefore" ("$(Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU;")" -eq "$stockBefore")
Check "★ 冻结量已清零" ("$(Sql 'duoduo_product' "SELECT COALESCE(frozen_stock,0) FROM t_sku WHERE id = $SKU;")" -eq '0')
$rTplFinal = Call 'Get' "$Gateway/api/coupons/templates" $anon $null
$finalIds = @($rTplFinal.body.data | ForEach-Object { [long]$_.id })
Check "★ 领券中心回到只剩种子券（夹具已彻底清理）" ((-not ($finalIds -contains [long]$TPL_LOW)) -and ($finalIds -contains 5001)) ($finalIds -join ',')

# ---------- 结果 ----------
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
    $failures | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
exit 0
