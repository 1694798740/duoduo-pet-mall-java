# 权限越权回归矩阵（E19，Windows PowerShell 5.1）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\privilege-matrix-smoke.ps1
#
# 前置：容器全部 healthy、已部署 E18 及之前的全部工作。
#
# ================================================================================
# 这个脚本要解决的问题
# ================================================================================
# E16 定下「谁能做什么」的唯一口径（RolePermissionMatrix），E17 让权限变更即时生效，
# E18 让每一次后台写操作留痕。但三者都只保证**机制本身**成立 ——
# 「机制真的被用在了每一个该用的地方」没有任何机器结论：
#   注解写漏 → 端点静默变成公开，接口 200、无异常、无日志；
#   归属校验写漏 → 别人的数据可读可改；
#   入口裁剪写漏 → 跨端令牌能进对面站点。
# 这三类缺陷的共同点是**失败方向全部指向「静默可用」**，日常点页面点不出来。
# 此前这些断言散在 seller / order / audit-sla / audit-log 各套件里，
# 没有单一出口 —— 想回答「现在到底防住了哪些越权」得翻四份日志。
#
# 本套件 = 把这些断言**收拢成一张可检索的矩阵**（每模块至少一条「不能碰别人」
# + 纵向越权：店员做店主动作、商家进平台后台），并把上面三类缺口补齐。
#
# ================================================================================
# 断言纪律（沿用 E16/E17/E27 的教训，改动前先读）
# ================================================================================
# 1. **每条「越权被拒」都必须配一条「有权者能过」的正对照**。
#    只有反例的矩阵在「整条链路坏掉、所有人都 403」时照样全绿 ——
#    那时它测的不是权限，而是服务还活着没有。
# 2. **必须同时覆盖网关层与服务端层**。E16 踩过：只断言网关态时，
#    入口收窄会**先**返 403，于是「服务端注解/判权是否生效」完全没被测到，
#    注解压根没注册也照样绿。所以 §4 一律**直连服务端口**并伪造 X-User-* 身份头。
# 3. **状态码必须写具体值**。写 `-ne 200` 会把 400（参数校验先拦）、
#    401（令牌失效）、500（服务炸了）一并算成「越权被正确拒绝」。
#    只在设计上确实允许两种答案时（跨店不可见 = 403 或 404）才写成闭合集合，
#    并在断言名里注明为什么。
# 4. **断言失败信息必须带 status**：只打印响应体分不清
#    「服务答了别的话」与「根本没拿到响应」（status=0）。
# 5. **夹具建不起来必须当场报红**，不能带着空值往下跑 ——
#    否则所有「越权被拒」都会因为「对方本来就没权限」而静默通过（假绿）。
#
# ================================================================================
# 与既有套件的覆盖关系（刻意保留的重叠，不是复制粘贴）
# ================================================================================
# · seller-smoke       —— 商家商品列表归属 / 跨店下架 403（E14 起有）
# · order-smoke        —— 买家 B 查/取消买家 A 的订单 → 404
# · audit-sla-smoke    —— E16 直连服务端口的三角色对照（抽查队列）
# · audit-log-smoke    —— E18 审计接口的角色对照
# · merchant-staff-ui-check / seller-ui-check —— 浏览器层的同型断言
# 本套件与它们**重叠的那几条**是有意保留的：矩阵要能被单独读懂，
# 不能要求读者先去翻另外四份日志才能拼出一张表。
# 改动归属逻辑时，这几处要一起改 —— 这是重复断言唯一的代价，换来的是单一出口。
#
# ================================================================================
# 输出
# ================================================================================
# 逐条 [PASS]/[FAIL]，末尾给通过/失败计数；失败时非 0 退出。
# 被 scripts/run-all-smoke.ps1 与 scripts/check-all.ps1 **自动收录**（按文件名通配），
# 无需在别处登记 —— 这正是「纳入一键自检」的实现方式。
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    [string]$Password = 'duoduo12345',
    # 直连端口（§4 用）：服务端判权必须在**不经网关**的路径上验，
    # 否则测到的是入口裁剪，与服务端注解是否生效无关。
    [string]$SvcUser = 'http://localhost:8081',
    [string]$SvcProduct = 'http://localhost:8082',
    [string]$SvcTrade = 'http://localhost:8083'
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

function Bearer([string]$token) { return @{ 'Authorization' = "Bearer $token" } }

function NewKey { return [guid]::NewGuid().ToString('N') }

# 身份头伪造（§4 直连用）：服务端只认网关注入的 X-User-*，直连时由本函数自己写，
# 于是「服务端判权是否独立成立」被单独测到（E16 的结论）。
function AsUser([string]$role, [string]$userId = '1') {
    return @{ 'X-User-Id' = $userId; 'X-User-Role' = $role; 'X-User-Phone' = '13000000000' }
}

. (Join-Path $PSScriptRoot 'lib\platform-operator.ps1')
. (Join-Path $PSScriptRoot 'lib\seller-fixture.ps1')
. (Join-Path $PSScriptRoot 'lib\catalog-fixture.ps1')

$phoneBuyA = '137' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneBuyB = '136' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneSellA = '135' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneSellB = '134' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneStaff = '133' + (Get-Random -Minimum 10000000 -Maximum 99999999)

Write-Host ""
Write-Host "=== 多多宠物商店 · 权限越权回归矩阵（E19） @ $Gateway ==="
Write-Host "买家 A = $phoneBuyA   买家 B = $phoneBuyB"
Write-Host "卖家 A = $phoneSellA   卖家 B = $phoneSellB   店员 = $phoneStaff"
Write-Host ""

# ==============================================================================
# §0 就绪与夹具
# ==============================================================================
Write-Host "> §0 就绪与夹具"

$ready = (Call 'Get' "$Gateway/api/categories" $null $null).status -eq 200
Check "网关可达（/api/categories → 200）" $ready "网关未就绪，后续断言无意义"
if (-not $ready) {
    Write-Host "  [ABORT] 网关不可达，终止"
    Write-Host ""
    Write-Host "=== 冒烟结果：通过 $pass 项，失败 $($fail + 1) 项 ==="
    exit 1
}

# 直连端口必须可达 —— 不可达时 §4 的每条断言都会拿到 status=0，
# 而那看起来像"被拒绝了"，必须在这里先用一条断言把前提钉住。
$svcOk = $true
$svcDetail = @()
foreach ($svc in @(@{ n = 'user'; u = $SvcUser }, @{ n = 'product'; u = $SvcProduct }, @{ n = 'trade'; u = $SvcTrade })) {
    $h = (Call 'Get' "$($svc.u)/actuator/health" $null $null).status
    $svcDetail += "$($svc.n)=$h"
    if ($h -ne 200) { $svcOk = $false }
}
Check "直连服务端口可达（user/product/trade 的 /actuator/health 均 200）" $svcOk ($svcDetail -join ' ')

# ---- 买家 A / B ----
Call 'Post' "$Gateway/api/auth/register" @{ phone = $phoneBuyA; password = $Password; nickname = '矩阵夹具-买家A' } @{ 'X-Duoduo-Entry' = 'mall' } | Out-Null
$loginA = Call 'Post' "$Gateway/api/auth/login/token" @{ phone = $phoneBuyA; password = $Password } @{ 'X-Duoduo-Entry' = 'mall' }
$tokenA = $loginA.body.data.accessToken
Check "夹具：买家 A 登录并拿到访问令牌" ($loginA.status -eq 200 -and -not [string]::IsNullOrWhiteSpace($tokenA)) "status=$($loginA.status) raw=$($loginA.raw)"

Call 'Post' "$Gateway/api/auth/register" @{ phone = $phoneBuyB; password = $Password; nickname = '矩阵夹具-买家B' } @{ 'X-Duoduo-Entry' = 'mall' } | Out-Null
$loginB = Call 'Post' "$Gateway/api/auth/login/token" @{ phone = $phoneBuyB; password = $Password } @{ 'X-Duoduo-Entry' = 'mall' }
$tokenB = $loginB.body.data.accessToken
Check "夹具：买家 B 登录并拿到访问令牌" ($loginB.status -eq 200 -and -not [string]::IsNullOrWhiteSpace($tokenB)) "status=$($loginB.status) raw=$($loginB.raw)"

# ---- 卖家 A / B（库内直建 APPROVED 店铺 + 实名）----
# 实名走真实接口（New-SellerFixture -DoRealname），而那条接口走的是 Cookie 会话 ——
# 所以这里必须先**真的登录出一个会话**，不能传一个空 WebRequestSession：
# 空会话下实名会 401，夹具抛异常 → $fxA 为 null → 后面所有「越权被拒」都会因为
# 「对方压根不是卖家」而静默通过（E27 定下的夹具纪律：建不起来必须报红）。
$regSellA = Call 'Post' "$Gateway/api/auth/register" @{ phone = $phoneSellA; password = $Password; nickname = '矩阵夹具-卖家A' } @{ 'X-Duoduo-Entry' = 'merchant' }
$sessSellA = New-Object Microsoft.PowerShell.Commands.WebRequestSession
Invoke-WebRequest -Uri "$Gateway/api/auth/login" -Method Post -WebSession $sessSellA `
    -Body (@{ phone = $phoneSellA; password = $Password } | ConvertTo-Json -Compress) `
    -ContentType 'application/json; charset=utf-8' -UseBasicParsing -TimeoutSec 20 | Out-Null
$fxA = $null
try { $fxA = New-SellerFixture -Phone $phoneSellA -StoreName '矩阵-店A' -Session $sessSellA -DoRealname } catch { }
$loginSA = Call 'Post' "$Gateway/api/auth/login/token" @{ phone = $phoneSellA; password = $Password } @{ 'X-Duoduo-Entry' = 'merchant' }
$tokenSA = $loginSA.body.data.accessToken
Check "夹具：卖家 A 就位（SELLER + 店铺 APPROVED）" ($null -ne $fxA -and $fxA.role -eq 'SELLER') "$($fxA | ConvertTo-Json -Compress)"
Check "夹具：卖家 A 登录（ent=merchant）" ($loginSA.status -eq 200 -and -not [string]::IsNullOrWhiteSpace($tokenSA)) "status=$($loginSA.status) raw=$($loginSA.raw)"
$uidSellA = Sql 'duoduo_user' "SELECT id FROM t_user WHERE phone='$phoneSellA';"

$regSellB = Call 'Post' "$Gateway/api/auth/register" @{ phone = $phoneSellB; password = $Password; nickname = '矩阵夹具-卖家B' } @{ 'X-Duoduo-Entry' = 'merchant' }
$sessSellB = New-Object Microsoft.PowerShell.Commands.WebRequestSession
Invoke-WebRequest -Uri "$Gateway/api/auth/login" -Method Post -WebSession $sessSellB `
    -Body (@{ phone = $phoneSellB; password = $Password } | ConvertTo-Json -Compress) `
    -ContentType 'application/json; charset=utf-8' -UseBasicParsing -TimeoutSec 20 | Out-Null
$fxB = $null
try { $fxB = New-SellerFixture -Phone $phoneSellB -StoreName '矩阵-店B' -Session $sessSellB -DoRealname } catch { }
$loginSB = Call 'Post' "$Gateway/api/auth/login/token" @{ phone = $phoneSellB; password = $Password } @{ 'X-Duoduo-Entry' = 'merchant' }
$tokenSB = $loginSB.body.data.accessToken
Check "夹具：卖家 B 就位（SELLER + 店铺 APPROVED）" ($null -ne $fxB -and $fxB.role -eq 'SELLER') "$($fxB | ConvertTo-Json -Compress)"
Check "夹具：卖家 B 登录（ent=merchant）" ($loginSB.status -eq 200 -and -not [string]::IsNullOrWhiteSpace($tokenSB)) "status=$($loginSB.status) raw=$($loginSB.raw)"

# ---- 店员（由卖家 A 通过**真实接口**邀请，而不是改库）----
# 走真实接口的理由：邀请链本身也是越权矩阵的一环（谁能拉人进店），
# 改库造出来的店员会绕开那道门，之后所有「店员能不能做 X」的断言就都不成立。
Call 'Post' "$Gateway/api/auth/register" @{ phone = $phoneStaff; password = $Password; nickname = '矩阵夹具-店员' } @{ 'X-Duoduo-Entry' = 'merchant' } | Out-Null
$invite = Call 'Post' "$Gateway/api/seller/staff" @{ phone = $phoneStaff } (Bearer $tokenSA)
# 响应字段是 memberId（不是 id）。取错字段不会报错，只会让后面所有
# 「停用成员」的调用打到 /api/seller/staff//disable 上，拿回 404 ——
# 而 404 与 403 在这里**看起来都像"越权被挡住了"**，是一条典型的假通过。
$memberIdStaff = "$($invite.body.data.memberId)"
Check "夹具：卖家 A 通过真实接口邀请店员成功" ($invite.status -eq 200 -and -not [string]::IsNullOrWhiteSpace("$memberIdStaff")) "status=$($invite.status) raw=$($invite.raw)"
$staffMemberState = Sql 'duoduo_user' "SELECT CONCAT(role,'/',status) FROM t_merchant_member WHERE user_id=(SELECT id FROM t_user WHERE phone='$phoneStaff');"
Check "夹具：店员的成员记录为 STAFF/ACTIVE（权限由成员身份驱动，不是角色）" ($staffMemberState -eq 'STAFF/ACTIVE') $staffMemberState
$loginStaff = Call 'Post' "$Gateway/api/auth/login/token" @{ phone = $phoneStaff; password = $Password } @{ 'X-Duoduo-Entry' = 'merchant' }
$tokenStaff = $loginStaff.body.data.accessToken
Check "夹具：店员登录（角色仍是 USER，ent=merchant）" `
    ($loginStaff.status -eq 200 -and -not [string]::IsNullOrWhiteSpace($tokenStaff) -and $loginStaff.body.data.user.role -eq 'USER') `
    "status=$($loginStaff.status) role=$($loginStaff.body.data.user.role)"

# ---- 平台运营 ----
$opBefore = [int](Sql 'duoduo_user' "SELECT COUNT(*) FROM t_user WHERE role='OPERATOR';")
$op = New-PlatformOperatorSession -Gateway $Gateway -Password $Password
$tokenOp = $null
$loginOp = Call 'Post' "$Gateway/api/auth/login/token" @{ phone = $op.phone; password = $Password } @{ 'X-Duoduo-Entry' = 'admin' }
$tokenOp = $loginOp.body.data.accessToken
Check "夹具：平台运营就位（库内 role=OPERATOR）" $op.roleSet "role=$($op.role)"
Check "夹具：平台运营登录（ent=admin）" ($loginOp.status -eq 200 -and -not [string]::IsNullOrWhiteSpace($tokenOp)) "status=$($loginOp.status) raw=$($loginOp.raw)"

# ---- 两个买家的收货地址（横向越权的靶子）----
$addrA = (Call 'Post' "$Gateway/api/addresses" @{ receiverName = '买家A'; receiverPhone = $phoneBuyA; province = '上海市'; city = '上海市'; district = '浦东新区'; detail = '矩阵路 1 号'; isDefault = $true } (Bearer $tokenA)).body.data.id
$addrB = (Call 'Post' "$Gateway/api/addresses" @{ receiverName = '买家B'; receiverPhone = $phoneBuyB; province = '北京市'; city = '北京市'; district = '朝阳区'; detail = '矩阵路 2 号'; isDefault = $true } (Bearer $tokenB)).body.data.id
Check "夹具：买家 A / B 各自建了一个收货地址" `
    (-not [string]::IsNullOrWhiteSpace("$addrA") -and -not [string]::IsNullOrWhiteSpace("$addrB") -and "$addrA" -ne "$addrB") `
    "addrA=$addrA addrB=$addrB"

# ==============================================================================
# §1 收货地址（user 域）· 横向越权
# ==============================================================================
# 口径：越权访问他人地址返回 **404** —— 刻意不区分「不存在」与「无权限」，
# 避免用返回码差异枚举他人地址 ID（见 AddressController 类注释）。
# 因为它是刻意的设计选择，断言就写 404 而不是"非 200"：写宽了就再也发现不了
# 「哪天有人改成 403」，而那正是把枚举通道打开的那一次改动。
Write-Host ""
Write-Host "> §1 收货地址（user 域）· 横向越权"

$r = Call 'Get' "$Gateway/api/addresses/default" $null (Bearer $tokenA)
Check "正对照：买家 A 读自己的默认地址 → 200" ($r.status -eq 200) "status=$($r.status) raw=$($r.raw)"

$r = Call 'Put' "$Gateway/api/addresses/$addrB" @{ receiverName = '被改的'; receiverPhone = $phoneBuyB; province = '北京市'; city = '北京市'; district = '朝阳区'; detail = '被篡改' } (Bearer $tokenA)
Check "★ 买家 A 改买家 B 的地址 → 404（刻意不区分无权限与不存在）" ($r.status -eq 404) "status=$($r.status) raw=$($r.raw)"

$r = Call 'Delete' "$Gateway/api/addresses/$addrB" $null (Bearer $tokenA)
Check "★ 买家 A 删买家 B 的地址 → 404" ($r.status -eq 404) "status=$($r.status) raw=$($r.raw)"
$addrBStill = Sql 'duoduo_user' "SELECT COUNT(*) FROM t_user_address WHERE id=$addrB;"
Check "★ 且买家 B 的地址**确实还在**（拒绝不是'删了但报错'）" ($addrBStill -eq '1') "rows=$addrBStill"

$r = Call 'Post' "$Gateway/api/addresses/$addrB/default" $null (Bearer $tokenA)
Check "★ 买家 A 把买家 B 的地址设为默认 → 404" ($r.status -eq 404) "status=$($r.status) raw=$($r.raw)"

$r = Call 'Get' "$Gateway/api/addresses" $null $null
Check "未登录读地址 → 401（与 403 分得清）" ($r.status -eq 401) "status=$($r.status) raw=$($r.raw)"

# ==============================================================================
# §2 商家子账号（user 域）· 纵向（店员做店主动作）+ 横向（跨店）
# ==============================================================================
# 三条边界（E13）：一人一店 / OWNER 不可停用 / **店员不可管理成员**。
# 第三条是本节的纵向主线：店员能经营，但不能决定「谁在这个店里」——
# 否则成员名单就不是店主可控的范围，一个店员能拉进无限个"同事"。
Write-Host ""
Write-Host "> §2 商家子账号（user 域）· 纵向 + 横向"

$r = Call 'Get' "$Gateway/api/seller/staff" $null (Bearer $tokenSA)
Check "正对照：卖家 A 拉本店成员列表 → 200" ($r.status -eq 200) "status=$($r.status) raw=$($r.raw)"

$r = Call 'Get' "$Gateway/api/seller/staff" $null (Bearer $tokenStaff)
Check "★ 纵向：店员拉成员列表 → 403（店员做店主动作）" ($r.status -eq 403) "status=$($r.status) raw=$($r.raw)"

$r = Call 'Post' "$Gateway/api/seller/staff" @{ phone = $phoneBuyB } (Bearer $tokenStaff)
Check "★ 纵向：店员邀请新店员 → 403（否则一个店员能拉进无限个同事）" ($r.status -eq 403) "status=$($r.status) raw=$($r.raw)"

$r = Call 'Post' "$Gateway/api/seller/staff/$memberIdStaff/disable" $null (Bearer $tokenStaff)
Check "★ 纵向：店员停用成员（含自己）→ 403" ($r.status -eq 403) "status=$($r.status) raw=$($r.raw)"

$r = Call 'Post' "$Gateway/api/seller/staff/$memberIdStaff/disable" $null (Bearer $tokenSB)
Check "★ 横向：卖家 B 停用卖家 A 的店员 → 404（跨店不可见）" ($r.status -eq 404) "status=$($r.status) raw=$($r.raw)"

# 运营侧要分两层验，缺一层就会漏掉一类失效：
#   ① 网关层：运营令牌带 ent=admin，而 /api/seller/** 属商家入口族 → 入口先拦（403）
#   ② 服务端层：绕过入口直连 user 服务 → 判权仍把运营挡在"店主侧成员列表"之外（404）
# 只写 ① 的话，测到的是入口裁剪；只写 ② 的话，真实链路上运营其实根本到不了这一步。
$r = Call 'Get' "$Gateway/api/seller/staff" $null (Bearer $tokenOp)
Check "★ 平台运营令牌（ent=admin）访问 /api/seller/** → 403（入口族隔离）" ($r.status -eq 403) "status=$($r.status) raw=$($r.raw)"

$r = Call 'Get' "$SvcUser/api/seller/staff" $null (AsUser 'OPERATOR')
Check "★ 服务端（直连）：OPERATOR 调店主侧成员列表 → 404（E13 口径：子账号=商家自有）" ($r.status -eq 404) "status=$($r.status) raw=$($r.raw)"

$r = Call 'Get' "$Gateway/api/admin/merchant-staff?page=1&pageSize=1" $null (Bearer $tokenOp)
Check "正对照：平台运营调**统管**端点 → 200（平台统管的能力没有被上面两条挡掉）" ($r.status -eq 200) "status=$($r.status) raw=$($r.raw)"

# ==============================================================================
# §3 后台入口边界（网关层，E8）
# ==============================================================================
# 令牌里的 ent 说"我是从哪个站点签发的"，网关据此裁剪跨端专属路径族。
# ★ 定位是纵深防御，不是角色校验的替代品（直连网关可伪造入口头）——
#   所以本节测的是"误用"被挡住，真正的角色边界在 §4 与 §6。
Write-Host ""
Write-Host "> §3 后台入口边界（网关层）"

$r = Call 'Get' "$Gateway/api/admin/users?page=1&pageSize=1" $null (Bearer $tokenSA)
Check "★ 商家令牌（ent=merchant）访问 /api/admin/** → 403" ($r.status -eq 403) "status=$($r.status) raw=$($r.raw)"
Check "★ 拒绝文案指向入口（不是笼统的没权限）" ($r.raw -like '*入口*') $r.raw

$r = Call 'Get' "$Gateway/api/seller/orders?page=1&pageSize=1" $null (Bearer $tokenA)
Check "★ 买家令牌（ent=mall）访问 /api/seller/** → 403" ($r.status -eq 403) "status=$($r.status) raw=$($r.raw)"

$r = Call 'Get' "$Gateway/api/admin/users?page=1&pageSize=1" $null (Bearer $tokenA)
Check "★ 买家令牌访问 /api/admin/** → 403" ($r.status -eq 403) "status=$($r.status) raw=$($r.raw)"

$r = Call 'Get' "$Gateway/api/seller/orders?page=1&pageSize=1" $null (Bearer $tokenSA)
Check "正对照：商家令牌访问商家端 → 200（入口收窄没有把合法用法一起挡掉）" ($r.status -eq 200) "status=$($r.status) raw=$($r.raw)"

$r = Call 'Get' "$Gateway/api/admin/users?page=1&pageSize=1" $null $null
Check "未登录访问后台 → 401（与 403 分得清）" ($r.status -eq 401) "status=$($r.status) raw=$($r.raw)"

# ==============================================================================
# §4 服务端判权（**直连服务端口**，不经网关）
# ==============================================================================
# ★ E16 的核心结论：走网关验不出服务端判权。
#   网关会先按入口返 403，于是「注解有没有注册、命令式判权有没有生效」完全没被测到
#   ——注解压根没加也照样绿。所以本节一律直连 8081/8082/8083 并自己写 X-User-* 头。
#   ★ 失效方向仍是「静默放开」：注解没扫到 → 200 + 任何人都能调 + 无任何报错。
Write-Host ""
Write-Host "> §4 服务端判权（直连服务端口）"

$svcAdminUsers = "$SvcUser/api/admin/users?page=1&pageSize=1"
$r = Call 'Get' $svcAdminUsers $null (AsUser 'USER')
Check "★ 服务端（直连）：USER → /api/admin/users 403" ($r.status -eq 403) "status=$($r.status) raw=$($r.raw)"
$r = Call 'Get' $svcAdminUsers $null (AsUser 'OPERATOR')
Check "正对照：服务端（直连）OPERATOR → 200" ($r.status -eq 200) "status=$($r.status) raw=$($r.raw)"
$r = Call 'Get' $svcAdminUsers $null $null
Check "★ 服务端（直连）：完全不带身份头 → 401" ($r.status -eq 401) "status=$($r.status) raw=$($r.raw)"

$svcAuditSla = "$SvcProduct/api/admin/audit-sla/samples?page=1&pageSize=1"
$r = Call 'Get' $svcAuditSla $null (AsUser 'USER')
Check "★ 服务端（直连·注解式）：USER → 抽查队列 403" ($r.status -eq 403) "status=$($r.status) raw=$($r.raw)"
$r = Call 'Get' $svcAuditSla $null (AsUser 'SELLER')
Check "★ 服务端（直连·注解式）：SELLER → 抽查队列 403" ($r.status -eq 403) "status=$($r.status) raw=$($r.raw)"
$r = Call 'Get' $svcAuditSla $null (AsUser 'OPERATOR')
Check "正对照：服务端（直连·注解式）OPERATOR → 200（注解不误拦）" ($r.status -eq 200) "status=$($r.status) raw=$($r.raw)"

$svcSellerOrders = "$SvcTrade/api/seller/orders?page=1&pageSize=1"
# ★ 这一对是本节的**最强形式**：同一个 userId（卖家 A 本人），只有角色不同。
#   差异因此只可能来自角色判定 —— 既有正对照，又排除了"这个 id 本来就不是卖家"的干扰。
$r = Call 'Get' $svcSellerOrders $null (AsUser 'SELLER' $uidSellA)
Check "正对照：服务端（直连）真实店主 userId + SELLER → 商家订单 200" ($r.status -eq 200) "status=$($r.status) raw=$($r.raw)"
$r = Call 'Get' $svcSellerOrders $null (AsUser 'USER' $uidSellA)
Check "★ 服务端（直连）：同一个 userId 只把角色写成 USER → 商家订单 403（差异只能来自角色）" ($r.status -eq 403) "status=$($r.status) raw=$($r.raw)"

$svcSellerSpus = "$SvcProduct/api/seller/products?page=1&pageSize=1"
$r = Call 'Get' $svcSellerSpus $null (AsUser 'SELLER' $uidSellA)
Check "正对照：服务端（直连）真实店主 userId + SELLER → 商家商品 200" ($r.status -eq 200) "status=$($r.status) raw=$($r.raw)"
$r = Call 'Get' $svcSellerSpus $null (AsUser 'USER' $uidSellA)
Check "★ 服务端（直连）：同一 userId 角色写成 USER → 商家商品 403" ($r.status -eq 403) "status=$($r.status) raw=$($r.raw)"

# 审计日志也直连验一次：走网关那条会被**入口**先拦（商家令牌 ent=merchant），
# 于是「@RequirePerm(AUDIT_LOG_READ) 到底认不认 SELLER」根本没被测到 —— E16 踩过的假通过。
$svcAuditLogs = "$SvcUser/api/admin/audit-logs?page=1&pageSize=1"
$r = Call 'Get' $svcAuditLogs $null (AsUser 'SELLER' $uidSellA)
Check "★ 服务端（直连·注解式）：SELLER → 审计日志 403（与网关那条的理由不同，两条都要有）" ($r.status -eq 403) "status=$($r.status) raw=$($r.raw)"
$r = Call 'Get' $svcAuditLogs $null (AsUser 'OPERATOR')
Check "正对照：服务端（直连）OPERATOR → 审计日志 200" ($r.status -eq 200) "status=$($r.status) raw=$($r.raw)"

# ==============================================================================
# §5 商品（product 域）· 横向（跨店）+ 审核维度的可见性
# ==============================================================================
# E14：status（卖家控）与 audit_status（平台判）**正交**，可见性唯一出口是
# 「在售 且 已过审」。E28 起审核本身也分两条路 —— 干净内容**默认放行**（AUTO），
# 命中风险规则才**转人工**（PENDING）。所以本节必须**两条路各建一个商品**：
# 只建一个的话，断言会锁死在「第三方提交必为 PENDING」这个 E28 之前的旧口径上，
# 而那条旧口径现在是**错的**（第一次跑就是被它判红的）。
Write-Host ""
Write-Host "> §5 商品（product 域）· 横向 + 审核维度可见性"

$tplCat = Sql 'duoduo_product' "SELECT id FROM t_category ORDER BY id LIMIT 1;"

# ---- 商品 A：内容干净 → 走 E28「默认放行」 ----
$created = Call 'Post' "$Gateway/api/seller/products" @{
    title      = '矩阵夹具商品A-' + (Get-Random -Minimum 100000 -Maximum 999999)
    subtitle   = '越权矩阵用'
    categoryId = [long]$tplCat
    mainImage  = '/img/fixture.png'
    detail     = '越权矩阵夹具商品（内容干净）'
    skus       = @(@{ specName = '标准'; price = 1990; stock = 20 })
} (Bearer $tokenSA)
$spuA = "$($created.body.data.id)"
Check "正对照：卖家 A 提交内容干净的商品 → 200" ($created.status -eq 200 -and $created.body.data.id) "status=$($created.status) raw=$($created.raw)"
$spuStatus = "$($created.body.data.status)"
Check "★ E28 默认放行：干净内容直接 APPROVED（人工审核不再是必经环节）" `
    ("$($created.body.data.auditStatus)" -eq 'APPROVED') "auditStatus=$($created.body.data.auditStatus)"
# 这条是 E28 最核心的机器结论：自动放行**必须写 audit_at**。
# 抽查候选集是按 audit_at ∈ [T-1 当天] 筛的 —— 不写它，抽查任务照跑、created=0、
# 接口 200 无日志，也就是"免事前审核"的全部对价**永久静默空转**，没有任何断言会红。
$autoRow = Sql 'duoduo_product' "SELECT CONCAT(audit_source,'/',IF(audit_at IS NULL,'NO_AT','AT')) FROM t_spu WHERE id=$spuA;"
Check "★ E28 自动放行必须写 audit_at 且来源为 AUTO（否则事后抽查静默空转）" ($autoRow -eq 'AUTO/AT') $autoRow

# ---- 商品 B：标题含手机号 → 命中 CONTACT_INFO → 转人工 ----
$createdB = Call 'Post' "$Gateway/api/seller/products" @{
    title      = '矩阵夹具商品B 咨询 13900001234'
    subtitle   = '越权矩阵用'
    categoryId = [long]$tplCat
    mainImage  = '/img/fixture.png'
    detail     = '越权矩阵夹具商品（含联系方式）'
    skus       = @(@{ specName = '标准'; price = 1990; stock = 20 })
} (Bearer $tokenSA)
$spuB = "$($createdB.body.data.id)"
Check "★ E28 转人工：命中风险规则（标题含联系方式）→ 审核态回到 PENDING" `
    ("$($createdB.body.data.auditStatus)" -eq 'PENDING') "auditStatus=$($createdB.body.data.auditStatus)"
$pendRow = Sql 'duoduo_product' "SELECT CONCAT(IFNULL(audit_source,'NULL'),'/',IF(audit_at IS NULL,'NO_AT','AT')) FROM t_spu WHERE id=$spuB;"
Check "★ 转人工时 audit_source 与 audit_at 都清空（上一次结论已作废，留着会被当成「今天刚自动放行」抽进复核队列）" `
    ($pendRow -eq 'NULL/NO_AT') $pendRow

# ---- 跨店（横向）----
# 先立正对照：同一端点、同一动作，只有"是不是自己的商品"这一项不同。
# 没有它的话，下面的 403 在"端点整个坏掉、谁都拿不到"时也照样绿。
$r = Call 'Get' "$Gateway/api/seller/products/$spuA" $null (Bearer $tokenSA)
Check "正对照：卖家 A 看自己的商品详情 → 200" ($r.status -eq 200) "status=$($r.status) raw=$($r.raw)"

$r = Call 'Get' "$Gateway/api/seller/products/$spuA" $null (Bearer $tokenSB)
Check "★ 横向：卖家 B 看卖家 A 的商品详情 → 403" ($r.status -eq 403) "status=$($r.status) raw=$($r.raw)"

$r = Call 'Put' "$Gateway/api/seller/products/$spuA" @{ title = '被卖家B改掉的标题' } (Bearer $tokenSB)
Check "★ 横向：卖家 B 改卖家 A 的商品 → 403" ($r.status -eq 403) "status=$($r.status) raw=$($r.raw)"

$r = Call 'Post' "$Gateway/api/seller/products/$spuA/status" @{ action = 'OFF_SHELF' } (Bearer $tokenSB)
Check "★ 横向：卖家 B 下架卖家 A 的商品 → 403" ($r.status -eq 403) "status=$($r.status) raw=$($r.raw)"
$spuStill = Sql 'duoduo_product' "SELECT status FROM t_spu WHERE id=$spuA;"
Check "★ 且卖家 A 的商品状态未被改动" ($spuStill -eq "$spuStatus") "now=$spuStill before=$spuStatus"

$r = Call 'Get' "$Gateway/api/seller/products" $null (Bearer $tokenA)
Check "★ 纵向：买家访问商家商品列表 → 403（买家不进商家端）" ($r.status -eq 403) "status=$($r.status) raw=$($r.raw)"

$r = Call 'Get' "$Gateway/api/seller/products?pageSize=50" $null (Bearer $tokenSA)
$selfOwned = @($r.body.data.items | Where-Object { $_.sellerId -eq 0 }).Count
Check "★ 卖家商品列表不含平台自营（sellerId=0 是平台的，不该出现在卖家视角）" ($r.status -eq 200 -and $selfOwned -eq 0) "status=$($r.status) selfOwned=$selfOwned"

# ---- 可见性出口：两个维度各验一条，避免"所有第三方都看不见"也能全绿 ----
$r = Call 'Get' "$Gateway/api/products/$spuA" $null (Bearer $tokenA)
Check "正对照：自动放行的商品买家可见 → 200" ($r.status -eq 200) "status=$($r.status) raw=$($r.raw)"

$r = Call 'Get' "$Gateway/api/products/$spuB" $null (Bearer $tokenA)
# 未过审商品对买家应当"看起来不存在"（404 而不是 403）：
# 403 等于告诉对方"这里有个商品，你只是没权限看"，那是可枚举的信息泄露。
Check "★ 转人工（未过审）商品：买家读公开详情 → 404（不是 403，避免暴露商品存在）" ($r.status -eq 404) "status=$($r.status) raw=$($r.raw)"

# ==============================================================================
# §6 审核台（product 域）· 纵向（谁能判）
# ==============================================================================
# 这组是「补齐缺口」的重点：审核端点的判权只有一条实测断言（audit-sla-smoke 里
# 的抽查队列），商品审核端点此前**没有任何越权断言** ——
# 而它恰恰是「第三方商品必须审核后上架」这条口径的执行者。
Write-Host ""
Write-Host "> §6 审核台（product 域）· 纵向"

$r = Call 'Post' "$Gateway/api/admin/products/$spuB/audit" @{ action = 'APPROVE' } (Bearer $tokenSA)
Check "★ 商家令牌调商品审核 → 403（经网关，入口先挡）" ($r.status -eq 403) "status=$($r.status) raw=$($r.raw)"

$r = Call 'Post' "$Gateway/api/admin/products/$spuB/audit" @{ action = 'APPROVE' } (Bearer $tokenA)
Check "★ 买家令牌调商品审核 → 403" ($r.status -eq 403) "status=$($r.status) raw=$($r.raw)"

$r = Call 'Post' "$SvcProduct/api/admin/products/$spuB/audit" @{ action = 'APPROVE' } (AsUser 'USER')
Check "★ 服务端（直连）：USER 调商品审核 → 403（注解/判权独立成立）" ($r.status -eq 403) "status=$($r.status) raw=$($r.raw)"

# ★ 纵向最要紧的一条：**商家不能自己审自己**。
#   走网关时入口会先挡住（上一对已验），所以这里必须直连 —— 否则「服务端是否也拦」
#   根本没被测到，而注解没注册/漏写恰恰只会表现为"接口 200、任何人都能过"。
$r = Call 'Post' "$SvcProduct/api/admin/products/$spuB/audit" @{ action = 'APPROVE' } (AsUser 'SELLER' $uidSellA)
Check "★ 纵向（服务端·直连）：卖家本人调商品审核 → 403（不能自审自己的商品）" ($r.status -eq 403) "status=$($r.status) raw=$($r.raw)"

$r = Call 'Post' "$Gateway/api/admin/products/$spuB/audit" @{ action = 'APPROVE' } (Bearer $tokenOp)
Check "正对照：运营审核通过 → 200" ($r.status -eq 200) "status=$($r.status) raw=$($r.raw)"

$r = Call 'Get' "$Gateway/api/products/$spuB" $null (Bearer $tokenA)
Check "★ 过审后买家即可见 → 200（反证上一节的 404 来自审核态，而不是商品压根没建出来）" ($r.status -eq 200) "status=$($r.status) raw=$($r.raw)"

# ==============================================================================
# §7 订单（trade 域）· 横向 + 自营归属
# ==============================================================================
# 自营订单（seller_id = 0）归平台，**只有平台侧可见**。
# 这条边界很关键：它保证卖家既看不到、也动不了平台自己的订单 ——
# 而它是「卖家能发货」这条能力的前提（否则任何卖家都能给任何单发货，
# 那正是 E27 之前那个无归属校验的旧内部端点干的事）。
Write-Host ""
Write-Host "> §7 订单（trade 域）· 横向 + 自营归属"

$fxSku = Resolve-SkuFixtures -MinStock 2
$selfSku = $fxSku.A
# 下单前先记下该 SKU 的冻结量，收尾时用它自证「我冻的那件确实还回去了」。
$frozenBefore = [int](Sql 'duoduo_product' "SELECT frozen_stock FROM t_sku WHERE id = $selfSku;")
Call 'Post' "$Gateway/api/cart/items" @{ skuId = [long]$selfSku; quantity = 1 } (Bearer $tokenA) | Out-Null
$placed = Call 'Post' "$Gateway/api/orders" @{ receiverName = '买家A'; receiverPhone = $phoneBuyA; receiverAddress = '上海市浦东新区矩阵路 1 号'; idempotentKey = (NewKey) } (Bearer $tokenA)
$orderNo = "$(@($placed.body.data.orders)[0].orderNo)"
Check "夹具：买家 A 下单成功（自营 SKU $selfSku）" ($placed.status -eq 200 -and $orderNo.Length -eq 21) "status=$($placed.status) orderNo=$orderNo raw=$($placed.raw)"

$r = Call 'Get' "$Gateway/api/orders/$orderNo" $null (Bearer $tokenB)
Check "★ 横向：买家 B 查买家 A 的订单 → 404" ($r.status -eq 404) "status=$($r.status) raw=$($r.raw)"

# cancel 的请求体是 @RequestBody(required=false)，但**不带 Content-Type 会先撞 415**
# （UNSUPPORTED_MEDIA_TYPE），于是「越权被拒」的断言会拿到一个与权限完全无关的状态码。
# 传一个空对象既是合法调用，也让 404/403 成为这条链路上唯一的可能结果。
$r = Call 'Post' "$Gateway/api/orders/$orderNo/cancel" @{} (Bearer $tokenB)
Check "★ 横向：买家 B 取消买家 A 的订单 → 404" ($r.status -eq 404) "status=$($r.status) raw=$($r.raw)"

$r = Call 'Get' "$Gateway/api/seller/orders?page=1&pageSize=50" $null (Bearer $tokenSA)
$hitSelf = @($r.body.data.items | Where-Object { "$($_.orderNo)" -eq $orderNo }).Count
Check "★ 卖家 A 的订单列表不含该自营订单（seller_id=0 只有平台可见）" ($r.status -eq 200 -and $hitSelf -eq 0) "status=$($r.status) hit=$hitSelf"

$r = Call 'Post' "$Gateway/api/seller/orders/$orderNo/ship" @{ logisticsCompany = '顺丰'; logisticsNo = ('SF' + (Get-Random -Minimum 100000 -Maximum 999999)) } (Bearer $tokenSA)
Check "★ 卖家 A 给平台自营订单发货 → 403 或 404（绝不能是 200）" ($r.status -eq 403 -or $r.status -eq 404) "status=$($r.status) raw=$($r.raw)"

$r = Call 'Get' "$Gateway/api/orders/$orderNo" $null (Bearer $tokenA)
Check "正对照：买家 A 查自己的订单 → 200" ($r.status -eq 200) "status=$($r.status) raw=$($r.raw)"

# 收尾：把这张单取消掉，释放它冻结的库存。
# ★ **必须断言，不能 `| Out-Null` 静默收尾** —— 这是本套件真实踩过的坑：
#   有一轮这里的取消没生效（那次运行被中断），那张单就一直停在 PENDING_PAYMENT 冻着
#   自营 SKU 的 1 件可售；而下一个跑到的 address-smoke 前置断言恰好会读
#   「库内库存 vs 接口可售」，于是它以 `db=105 api=104` 的形式报红 ——
#   一个与本套件主题毫无关系的红，归因要从订单表倒查到夹具账号，成本极高。
#   夹具纪律「跑完自毁」的后半句就是这里：**自毁也必须自证**。
#   （进程被强杀时任何收尾都跑不到，这没法靠脚本兜；能兜的是「跑到了但没成功」必须变红。）
$r = Call 'Post' "$Gateway/api/orders/$orderNo/cancel" @{} (Bearer $tokenA)
Check "收尾：买家 A 取消自己刚下的订单 → 200（释放它冻结的可售量）" ($r.status -eq 200) "status=$($r.status) raw=$($r.raw)"
$frozenAfter = [int](Sql 'duoduo_product' "SELECT frozen_stock FROM t_sku WHERE id = $selfSku;")
Check "★ 收尾自证：该 SKU 冻结量已回到下单前（否则冻结跨套件累积，坑的是下一个套件）" `
    ($frozenAfter -eq $frozenBefore) "frozen=$frozenAfter before=$frozenBefore sku=$selfSku"

# ==============================================================================
# §8 审计日志（user 域）· 纵向
# ==============================================================================
# E18 的口径：审计日志**对运营开放**（运营正是后台操作的主体，日志对他们的价值是自查互查），
# 真正的边界在「不可删改」而不是「不给看」（见 Permission.AUDIT_LOG_READ 注释）。
# 所以这里要断言的正是：看得出、改不了 —— 对商家/买家则一律看不出。
Write-Host ""
Write-Host "> §8 审计日志（user 域）· 纵向"

$r = Call 'Get' "$Gateway/api/admin/audit-logs?page=1&pageSize=1" $null (Bearer $tokenA)
Check "★ 买家调审计日志 → 403（网关层：入口不匹配先拦，角色那层由 §4 直连独立验过）" ($r.status -eq 403) "status=$($r.status) raw=$($r.raw)"

$r = Call 'Get' "$Gateway/api/admin/audit-logs?page=1&pageSize=1" $null (Bearer $tokenSA)
Check "★ 商家令牌调审计日志 → 403（同上，两条路径的理由不同，缺一条就会漏掉一类失效）" ($r.status -eq 403) "status=$($r.status) raw=$($r.raw)"

$r = Call 'Get' "$Gateway/api/admin/audit-logs?page=1&pageSize=1" $null (Bearer $tokenOp)
Check "正对照：运营调审计日志 → 200（E18 刻意开放给运营）" ($r.status -eq 200) "status=$($r.status) raw=$($r.raw)"

$r = Call 'Delete' "$Gateway/api/admin/audit-logs/1" $null (Bearer $tokenOp)
Check "★ 审计记录不可删改：运营对它发起删除 → 非 2xx（无此入口，不存在「有权删除」的角色）" `
    ($r.status -ne 200 -and $r.status -ne 204) "status=$($r.status) raw=$($r.raw)"

# ==============================================================================
# §9 收尾自证
# ==============================================================================
Write-Host ""
Write-Host "> §9 收尾"

$deleted = Remove-PlatformOperatorPrivilege -Phone $op.phone
Check "临时运营已降回 USER（并顺带清掉它的审计行）" ($deleted -eq 'USER') "role=$deleted"

$opAfter = [int](Sql 'duoduo_user' "SELECT COUNT(*) FROM t_user WHERE role='OPERATOR';")
Check "★ 全库 OPERATOR 数量没有增加（收尾自证：没有留下永久后台钥匙）" `
    ($opAfter -le $opBefore) "before=$opBefore after=$opAfter"

# 夹具商品：**必须拆除，不能只下架**。
# 「下架」只解决了"前台多两条测试商品"，解决不了"库里的夹具越跑越多" ——
# 下一轮跑时这些行会被夹具解析当成**既有数据**吃掉（catalog-fixture.ps1 文件头写得很明白：
# 「夹具越跑越多」正是历轮把种子库搞乱的老路）。
#
# ★ 这是本套件真实踩过并造成**跨套件污染**的一个坑：
#   §7 用 Resolve-SkuFixtures 在自营 SPU 1001 下现场建了 3 个 SKU（小库存/售罄/已下架，
#   因为种子 SKU 2001/2002 都"够用"，夹具只补了这三类），却从没调用 Remove-SkuFixtures。
#   结果是 smoke.ps1 的「商品详情应含 2 个 SKU」断言被挤红（实得 4）——
#   **红的是那个与越权毫无关系的套件**，而归因要从 SPU 1001 的 SKU 列表倒查回本脚本。
#   夹具纪律「跑完自毁」不是"记得删一下"，是**必须自证删干净了**。
Remove-SpuFixtures -Ids @($spuA, $spuB)
$spuLeft = [int](Sql 'duoduo_product' "SELECT COUNT(*) FROM t_spu WHERE id IN ($spuA,$spuB);")
Check "★ 收尾自证：两件夹具商品已从库中删除（只下架不删除 = 夹具越跑越多，坑下一个套件）" `
    ($spuLeft -eq 0) "left=$spuLeft ids=$spuA,$spuB"

$createdSkus = @($fxSku.CreatedIds)
Remove-SkuFixtures -Ids $createdSkus
$skuLeft = if ($createdSkus.Count -gt 0) {
    [int](Sql 'duoduo_product' "SELECT COUNT(*) FROM t_sku WHERE id IN ($($createdSkus -join ','));")
} else { 0 }
Check "★ 收尾自证：§7 现场创建的夹具 SKU 已全部拆除（本套件创建 $($createdSkus.Count) 个）" `
    ($skuLeft -eq 0) "left=$skuLeft created=$($createdSkus.Count)"

# 吊销本次全部夹具账号的刷新令牌（账号本身留着，与 platform-operator.ps1 同一取舍）
$fixturePhones = "'$phoneBuyA','$phoneBuyB','$phoneSellA','$phoneSellB','$phoneStaff','$($op.phone)'"
Sql 'duoduo_user' "UPDATE t_refresh_token SET revoked=1 WHERE user_id IN (SELECT id FROM t_user WHERE phone IN ($fixturePhones));" | Out-Null

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
