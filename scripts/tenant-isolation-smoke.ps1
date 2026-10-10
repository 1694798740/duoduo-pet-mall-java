# 租户隔离冒烟（E20，Windows PowerShell 5.1 / pwsh 7 均须可跑）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tenant-isolation-smoke.ps1
#
# 前置：容器全部 healthy，且已部署 E20 镜像（含 duoduo-common 的 tenant 包、
#       common-web 的 TenantContextInterceptor 与 TenantLineInterceptors）。
#
# 覆盖（对应 E20 验收要点）：
#   1. 令牌里的租户声明（tid）：卖家 → 自己；店员 → 所属店铺的 owner_user_id
#      （★ 不是 t_merchant.id）；买家 / 运营 / 管理员 → 不声明（跨租户是他们的职责）；
#      刷新令牌换新令牌时**重新解析**（所以不带 tid 的旧值不会被继承）。
#   2. 数据层真的追加了条件：直连 product 服务（绕过网关），伪造 X-Tenant-Id 查询
#      一个**自身不带任何归属过滤**的接口（/api/products/internal/cards 用 selectBatchIds），
#      于是「过滤生效」只可能来自租户拦截器 —— 每条「隔离生效」都配一条「未声明时看得见」正对照。
#      夹具商品由本脚本自建（一个自营 + 一个第三方），不依赖库里原有数据：
#      本机库实测只有 2 个商品且 seller_id 全是 0，靠存量会变成「夹具找不到」→ 误报成隔离失效。
#   3. 非法值响亮失败：直连服务带 abc / 负数 → 400（而不是「悄悄不加条件」）。
#   4. 网关会剥离伪造头：带非法 X-Tenant-Id 走网关 → 仍是 200（服务端压根看不到它）。
#   5. ES 侧租户过滤存在（静态断言）：ES 是独立于 MySQL 的第三个可见性出口，
#      今天商家端没有可达的 ES 检索路径，所以只做源码级断言 + 文档约束；
#      将来一旦出现商家端 ES 检索，这条过滤会自动生效。
#   6. 收尾自证：夹具店铺/成员行全部拆除、临时账号降回 USER、
#      全库 OPERATOR 数量与开跑前一致、夹具租户的审计行已清空。
#
# 设计原则（沿用 E27 夹具三纪律 + E19 越权矩阵纪律）：
#   1. 现场建 / 跑完自毁 / 建不起来就显式报红；
#   2. 每条「被隔离」必配「未声明时可见」的正对照 —— 否则「查询恰好返回空」也能看起来像隔离成功；
#   3. 验数据层必须直连服务端口：走网关时 X-Tenant-Id 由网关按令牌写入、伪造的头会被剥离，
#      于是「拦截器有没有生效」根本测不到（E16 踩过同一个坑）；
#   4. 断言失败一律带 status + raw，区分「服务答了别的话」与「根本没拿到响应」。
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    [string]$SvcUser = 'http://127.0.0.1:8081',
    [string]$SvcProduct = 'http://127.0.0.1:8082',
    [string]$Password = 'duoduo12345'
)

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
$root = Split-Path -Parent $PSScriptRoot
$pass = 0
$fail = 0
$failures = @()
$declared = 37

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

# 执行写语句。$db 可指定库 —— 本套夹具要同时写 user 域（账号/店铺）与 product 域（夹具商品）。
# ★ 必须带 --default-character-set=utf8mb4：夹具商品标题是中文，不带会写进去变成 '?'，
#   而那种失败看起来像"商品建好了但标题不对"，与隔离毫无关系却会浪费排查时间。
function SqlExec([string]$sql, [string]$db = 'duoduo_user') {
    & docker exec duoduo-mysql mysql -uduoduo -pduoduo -N -B --default-character-set=utf8mb4 `
        -D $db -e $sql 2>&1 | Out-Null
}

function Bearer([string]$token) {
    return @{ 'Authorization' = "Bearer $token" }
}

# 解出 JWT 的 payload（不验签，只看声明）。E20 要断言的就是「声明里有没有 tid、值是多少」。
function JwtPayload([string]$token) {
    if ([string]::IsNullOrWhiteSpace($token)) { return $null }
    $parts = $token.Split('.')
    if ($parts.Count -lt 2) { return $null }
    $p = $parts[1].Replace('-', '+').Replace('_', '/')
    switch ($p.Length % 4) {
        2 { $p += '==' }
        3 { $p += '=' }
        1 { $p += '===' }
    }
    try {
        $json = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($p))
        return ($json | ConvertFrom-Json)
    } catch { return $null }
}

. (Join-Path $PSScriptRoot 'lib\internal-token.ps1')

$phoneSeller = '137' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneStaff = '136' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneBuyer = '135' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneOp = '134' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$merchantId = [string]([long]([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()) * 1000 + (Get-Random -Minimum 1 -Maximum 999))
$memberId = [string]([long]$merchantId + 1)
# 两个夹具商品的 id（同一条时间基，避免与存量 1001/1002 撞号）
$spuSelfId = [string]([long]$memberId + 1)
$spuMerchantId = [string]([long]$spuSelfId + 1)
$merchantName = "租户隔离夹具店-$phoneSeller"

Write-Host ""
Write-Host "=== 多多宠物商店 · 租户隔离冒烟（E20） @ $Gateway ==="
Write-Host "夹具：卖家 $phoneSeller / 店员 $phoneStaff / 买家 $phoneBuyer / 运营 $phoneOp"
Write-Host "夹具店铺 id=$merchantId（★ 它不是租户键，租户键是店主 userId）"
Write-Host ""

# ---------- 0. 就绪 ----------
Write-Host "> 0. 就绪检查"
$ready = (Call 'Get' "$Gateway/api/categories" $null $null).status -eq 200
Check "网关可达（/api/categories → 200）" $ready "网关未就绪，后续断言无意义"
if (-not $ready) {
    Write-Host "  [ABORT] 网关不可达，终止"
    Write-Host ""
    Write-Host "=== 冒烟结果：通过 $pass 项，失败 $($fail + 1) 项 ==="
    exit 1
}
$userRows = [int](Sql 'duoduo_user' "SELECT COUNT(*) FROM t_user;")
Check "夹具前置：SQL 通道可用（能读到 t_user）" ($userRows -ge 1) "t_user 行数=$userRows"
$opBefore = [int](Sql 'duoduo_user' "SELECT COUNT(*) FROM t_user WHERE role='OPERATOR';")
$merchantBefore = [int](Sql 'duoduo_user' "SELECT COUNT(*) FROM t_merchant;")

# ---------- 1. 夹具 ----------
Write-Host ""
Write-Host "> 1. 夹具（现场建，建不起来必须报红）"

# 1a. 四个临时账号：卖家 / 店员 / 买家 / 运营
foreach ($p in @($phoneSeller, $phoneStaff, $phoneBuyer, $phoneOp)) {
    Call 'Post' "$Gateway/api/auth/register" @{ phone = $p; password = $Password; nickname = "租户夹具-$p" } | Out-Null
}
SqlExec "UPDATE t_user SET role='SELLER' WHERE phone='$phoneSeller'; UPDATE t_user SET role='OPERATOR' WHERE phone='$phoneOp';"
Start-Sleep -Milliseconds 300

$sellerId = Sql 'duoduo_user' "SELECT id FROM t_user WHERE phone='$phoneSeller';"
$staffId = Sql 'duoduo_user' "SELECT id FROM t_user WHERE phone='$phoneStaff';"
$opId = Sql 'duoduo_user' "SELECT id FROM t_user WHERE phone='$phoneOp';"
Check "夹具：卖家账号已就位（库内 role=SELLER）" `
    ((Sql 'duoduo_user' "SELECT role FROM t_user WHERE phone='$phoneSeller';") -eq 'SELLER') `
    "role=$(Sql 'duoduo_user' "SELECT role FROM t_user WHERE phone='$phoneSeller';")"
Check "夹具：运营账号已就位（库内 role=OPERATOR）" `
    ((Sql 'duoduo_user' "SELECT role FROM t_user WHERE phone='$phoneOp';") -eq 'OPERATOR') `
    "role=$(Sql 'duoduo_user' "SELECT role FROM t_user WHERE phone='$phoneOp';")"

# 1b. 给卖家建一个「已通过」的店铺 + 一个 ACTIVE 店员
SqlExec "INSERT INTO t_merchant (id, name, owner_user_id, status) VALUES ($merchantId, '$merchantName', $sellerId, 'APPROVED');"
SqlExec "INSERT INTO t_merchant_member (id, merchant_id, user_id, role, status) VALUES ($memberId, $merchantId, $staffId, 'STAFF', 'ACTIVE');"
Check "夹具：店铺已建（status=APPROVED，owner_user_id=$sellerId）" `
    ((Sql 'duoduo_user' "SELECT COUNT(*) FROM t_merchant WHERE id=$merchantId AND status='APPROVED';") -eq '1') `
    "merchantId=$merchantId"
Check "夹具：店员成员行已建（role=STAFF / status=ACTIVE）" `
    ((Sql 'duoduo_user' "SELECT COUNT(*) FROM t_merchant_member WHERE merchant_id=$merchantId AND user_id=$staffId AND role='STAFF' AND status='ACTIVE';") -eq '1') `
    "memberId=$memberId"

# 1c. 登录拿令牌
$tSeller = (Call 'Post' "$Gateway/api/auth/login/token" @{ phone = $phoneSeller; password = $Password }).body.data
$tStaff = (Call 'Post' "$Gateway/api/auth/login/token" @{ phone = $phoneStaff; password = $Password }).body.data
$tBuyer = (Call 'Post' "$Gateway/api/auth/login/token" @{ phone = $phoneBuyer; password = $Password }).body.data
$tOp = (Call 'Post' "$Gateway/api/auth/login/token" @{ phone = $phoneOp; password = $Password }).body.data
# ★ 先算进变量再传，不要在实参位置写裸的比较表达式：
#   `Check "x" (管道).Count -eq 0 "extra"` 会被 PowerShell **静默误绑定** ——
#   $ok 拿到的是 `(管道).Count` 这个数，`-eq 0` 被吞掉，于是"没人缺令牌"照样判 False。
#   实测（scripts/lib/check-check-binding.ps1 就是为拦这一条写的）：
#     Check "A" (expr).Count -eq 0   → ok=False（静默，且无任何报错）
#     Check "B" ($n -eq 0)           → ok=True（正确）
#   而仓库里常见的 `Check "x" ((f) -eq 'str')`（整体再包一层括号）是安全的，不必改。
$missingToken = @(@($tSeller, $tStaff, $tBuyer, $tOp) | Where-Object { [string]::IsNullOrWhiteSpace($_.accessToken) }).Count
Check "夹具：四个账号都登录成功并拿到访问令牌" ($missingToken -eq 0) `
    "缺令牌=$missingToken seller=$(-not [string]::IsNullOrWhiteSpace($tSeller.accessToken)) staff=$(-not [string]::IsNullOrWhiteSpace($tStaff.accessToken)) buyer=$(-not [string]::IsNullOrWhiteSpace($tBuyer.accessToken)) op=$(-not [string]::IsNullOrWhiteSpace($tOp.accessToken))"

# 1d. 两个夹具商品：一个自营（seller_id=0）、一个属于夹具卖家（seller_id=$sellerId）。
# ★ 为什么自己造而不是挑存量商品（本机实测踩到）：
#   本机库里 t_spu 只有 2 行、且 seller_id 全是 0 —— 一个第三方商品都没有。
#   若夹具依赖"库里已有一个 seller_id>0 的商品"，那天它就变成"夹具找不到"，
#   会被误读成"租户隔离失效"。自己造还能让 §3 的正对照有确定答案（恰好这两个），
#   不依赖库里原有几行数据。
$spuInsert = @"
INSERT INTO t_spu (id,title,category_id,supply_type,main_image,status,audit_status,min_price,max_price,sales_count,created_at,updated_at,seller_id) VALUES
 ($spuSelfId,'租户夹具-自营商品',1,'self','/fixture/self.png','ON_SALE','APPROVED',100,100,0,UTC_TIMESTAMP(),UTC_TIMESTAMP(),0),
 ($spuMerchantId,'租户夹具-第三方商品',1,'merchant','/fixture/merchant.png','ON_SALE','APPROVED',200,200,0,UTC_TIMESTAMP(),UTC_TIMESTAMP(),$sellerId);
"@
SqlExec $spuInsert 'duoduo_product'
Check "夹具：自营夹具商品已建（id=$spuSelfId，seller_id=0）" `
    ((Sql 'duoduo_product' "SELECT COUNT(*) FROM t_spu WHERE id=$spuSelfId AND seller_id=0;") -eq '1') `
    "selfSpu=$spuSelfId"
Check "夹具：第三方夹具商品已建（id=$spuMerchantId，seller_id=$sellerId）" `
    ((Sql 'duoduo_product' "SELECT COUNT(*) FROM t_spu WHERE id=$spuMerchantId AND seller_id=$sellerId;") -eq '1') `
    "merchantSpu=$spuMerchantId sellerId=$sellerId"
$spuIds = "$spuSelfId,$spuMerchantId"

# ---------- 2. 令牌里的租户声明 ----------
Write-Host ""
Write-Host "> 2. 令牌里的租户声明 tid（谁代表哪个店铺）"

$pSeller = JwtPayload $tSeller.accessToken
$pStaff = JwtPayload $tStaff.accessToken
$pBuyer = JwtPayload $tBuyer.accessToken
$pOp = JwtPayload $tOp.accessToken

Check "卖家令牌带 tid 且等于自己的 userId（店铺主体就是他本人）" `
    ("$($pSeller.tid)" -eq "$sellerId") "tid=$($pSeller.tid) sellerId=$sellerId"
Check "★ 卖家令牌的 tid ≠ 店铺 id（口径纠正：租户键是 owner_user_id，不是 t_merchant.id）" `
    ("$($pSeller.tid)" -ne "$merchantId") "tid=$($pSeller.tid) merchantId=$merchantId"
Check "★ 店员令牌带 tid 且等于店主的 userId（以店铺主体行动）" `
    ("$($pStaff.tid)" -eq "$sellerId") "tid=$($pStaff.tid) ownerUserId=$sellerId"
Check "★ 店员令牌的 tid ≠ 店员自己的 userId（不是拿自己当租户）" `
    ("$($pStaff.tid)" -ne "$staffId") "tid=$($pStaff.tid) staffId=$staffId"
Check "买家令牌不带 tid（不属于任何店铺 → 下游不追加条件）" `
    ($null -eq $pBuyer.tid) "tid=$($pBuyer.tid)"
Check "运营令牌不带 tid（平台方跨租户巡查，钉一个租户会让后台少看数据）" `
    ($null -eq $pOp.tid) "tid=$($pOp.tid)"
Check "对照：令牌确实解析成功（payload 里同时有 sub 与 role）" `
    (("$($pSeller.sub)" -eq "$sellerId") -and ("$($pSeller.role)" -eq 'SELLER')) `
    "sub=$($pSeller.sub) role=$($pSeller.role)"

# 刷新令牌换新令牌：租户重新解析（刷新令牌刻意不带 tid，就是为了走这一步重算）
$rf = Call 'Post' "$Gateway/api/auth/refresh/token" @{ refreshToken = $tStaff.refreshToken }
$pStaff2 = JwtPayload $rf.body.data.accessToken
Check "★ 刷新后店员令牌的 tid 仍是店主 userId（刷新时重新解析，不继承旧值）" `
    ($rf.status -eq 200 -and ("$($pStaff2.tid)" -eq "$sellerId")) `
    "status=$($rf.status) tid=$($pStaff2.tid) ownerUserId=$sellerId"

# 把店员停用后再刷新：应不再带 tid（人已不在店里）
SqlExec "UPDATE t_merchant_member SET status='DISABLED' WHERE id=$memberId;"
$rf2 = Call 'Post' "$Gateway/api/auth/refresh/token" @{ refreshToken = $rf.body.data.refreshToken }
$pStaff3 = JwtPayload $rf2.body.data.accessToken
Check "★ 店员被停用后刷新 → 令牌不再带 tid（租户是「你现在属于谁」，必须重算）" `
    ($rf2.status -eq 200 -and ($null -eq $pStaff3.tid)) "status=$($rf2.status) tid=$($pStaff3.tid)"
SqlExec "UPDATE t_merchant_member SET status='ACTIVE' WHERE id=$memberId;"

# ---------- 3. 数据层真的追加了条件（直连服务端口） ----------
Write-Host ""
Write-Host "> 3. 数据层租户过滤（直连 product :8082，绕过网关）"
# /api/products/internal/cards 走 spuMapper.selectBatchIds —— 自身不带任何归属过滤，
# 所以「结果被过滤了」只可能来自租户拦截器。这是本节最重要的选择：
# 若换成一个自己就 where seller_id=... 的接口，红与绿都证明不了拦截器在不在。
$cardsPath = '/api/products/internal/cards'
function CardsWithTenant($tenantId) {
    $h = @{ 'X-Internal-Token' = (New-InternalToken -Method 'Get' -Path $cardsPath) }
    if ($null -ne $tenantId) { $h['X-Tenant-Id'] = "$tenantId" }
    $r = Call 'Get' "$SvcProduct$cardsPath`?ids=$spuIds" $null $h
    $ids = @()
    if ($r.body -and $r.body.data) { $ids = @($r.body.data | ForEach-Object { "$($_.id)" }) }
    return @{ r = $r; ids = $ids }
}

$noTenant = CardsWithTenant $null
Check "★ 正对照：未声明租户 → 两个 SPU 都看得见（= E20 之前的行为）" `
    ($noTenant.r.status -eq 200 -and $noTenant.ids.Count -eq 2) `
    "status=$($noTenant.r.status) ids=$($noTenant.ids -join ',')"

$asSelf = CardsWithTenant '0'
Check "★ 声明租户=0（自营）→ 只看得见自营那一个（第三方被过滤掉）" `
    ($asSelf.r.status -eq 200 -and $asSelf.ids.Count -eq 1 -and $asSelf.ids -contains "$spuSelfId") `
    "status=$($asSelf.r.status) ids=$($asSelf.ids -join ',')"

$asMerchant = CardsWithTenant $sellerId
Check "★ 声明租户=第三方卖家 → 只看得见他自己那一个" `
    ($asMerchant.r.status -eq 200 -and $asMerchant.ids.Count -eq 1 -and $asMerchant.ids -contains "$spuMerchantId") `
    "status=$($asMerchant.r.status) ids=$($asMerchant.ids -join ',')"

$asStranger = CardsWithTenant '999999999999999999'
Check "★ 声明租户=一个没有商品的店铺 → 一条都看不见（条件真的拼进 SQL 了）" `
    ($asStranger.r.status -eq 200 -and $asStranger.ids.Count -eq 0) `
    "status=$($asStranger.r.status) ids=$($asStranger.ids -join ',')"

# ---------- 4. 非法值必须响亮失败 ----------
Write-Host ""
Write-Host "> 4. 非法租户头 → 400（而不是悄悄不加条件）"
$r = Call 'Get' "$SvcProduct$cardsPath`?ids=$spuIds" $null @{ 'X-Internal-Token' = (New-InternalToken -Method 'Get' -Path $cardsPath); 'X-Tenant-Id' = 'abc' }
Check "直连服务带非法 X-Tenant-Id=abc → 400（拒绝，而不是当成未声明放行）" `
    ($r.status -eq 400) "status=$($r.status) msg=$($r.body.message)"
$r = Call 'Get' "$SvcProduct$cardsPath`?ids=$spuIds" $null @{ 'X-Internal-Token' = (New-InternalToken -Method 'Get' -Path $cardsPath); 'X-Tenant-Id' = '-5' }
Check "直连服务带负数 X-Tenant-Id → 400" ($r.status -eq 400) "status=$($r.status) msg=$($r.body.message)"

# ---------- 5. 网关会剥离客户端伪造的租户头 ----------
Write-Host ""
Write-Host "> 5. 网关剥离（伪造头走网关应被丢掉）"
$r = Call 'Get' "$Gateway/api/products/$spuSelfId" $null (Bearer $tBuyer.accessToken)
Check "（前置）买家走网关读商品详情 → 200" ($r.status -eq 200) "status=$($r.status) raw=$($r.raw)"
$r = Call 'Get' "$Gateway/api/products/$spuSelfId" $null @{
    'Authorization' = "Bearer $($tBuyer.accessToken)"
    'X-Tenant-Id'   = 'abc'
}
Check "★ 伪造的非法 X-Tenant-Id 走网关被剥离 → 仍 200（服务端压根看不到它）" `
    ($r.status -eq 200) "status=$($r.status) —— 若为 400，说明网关没有剥离；与上面那条直连 400 形成对照"

# ---------- 6. ES 侧租户过滤（静态断言） ----------
Write-Host ""
Write-Host "> 6. ES 是第三个可见性出口（静态断言 + 文档约束）"
$engineSrc = Get-Content (Join-Path $root 'duoduo-product/src/main/java/com/duoduo/product/support/search/EsSearchEngine.java') -Raw -Encoding UTF8
$indexerSrc = Get-Content (Join-Path $root 'duoduo-product/src/main/java/com/duoduo/product/support/search/SearchIndexer.java') -Raw -Encoding UTF8
Check "EsSearchEngine 的商品检索调用了租户过滤" `
    (($engineSrc -split 'public EsHits searchProducts')[1] -match 'addTenantFilter\(filters\)') "未找到 addTenantFilter"
Check "EsSearchEngine 的闲置检索也调用了租户过滤" `
    (($engineSrc -split 'public EsHits searchListings')[1] -match 'addTenantFilter\(filters\)') "未找到 addTenantFilter"
# ★ 先把计数取到变量里再拼提示串：PowerShell 的 `"` 不能靠 `\` 转义，
#   写成 "count=$(([regex]::Matches($s, 'doc\.put("sellerId")).Count)" 会让内层引号
#   提前终止外层字符串（解析期就报「字符串缺少终止符」，而不是断言失败）。
$sellerIdPutCount = ([regex]::Matches($indexerSrc, 'doc\.put\("sellerId"')).Count
Check "ES 文档里写入了 sellerId（商品 + 闲置两处）" ($sellerIdPutCount -eq 2) "count=$sellerIdPutCount"
Check "ES mapping 里有 sellerId 字段" ($indexerSrc -match '"sellerId"\s*:\s*\{"type":\s*"long"\}') "未在 mapping 中找到 sellerId"

# ---------- 7. 收尾自证 ----------
Write-Host ""
Write-Host "> 7. 收尾自证"
SqlExec "DELETE FROM t_merchant_member WHERE id=$memberId;"
SqlExec "DELETE FROM t_merchant WHERE id=$merchantId;"
SqlExec "UPDATE t_user SET role='USER' WHERE phone IN ('$phoneSeller','$phoneOp');"
SqlExec "DELETE FROM t_audit_log WHERE tenant_id = $sellerId;"
SqlExec "DELETE FROM t_spu WHERE id IN ($spuSelfId,$spuMerchantId);" 'duoduo_product'

Check "★ 收尾自证：夹具店铺行已删除" `
    ((Sql 'duoduo_user' "SELECT COUNT(*) FROM t_merchant WHERE id=$merchantId;") -eq '0') `
    "left=$(Sql 'duoduo_user' "SELECT COUNT(*) FROM t_merchant WHERE id=$merchantId;")"
Check "★ 收尾自证：夹具成员行已删除" `
    ((Sql 'duoduo_user' "SELECT COUNT(*) FROM t_merchant_member WHERE id=$memberId;") -eq '0') `
    "left=$(Sql 'duoduo_user' "SELECT COUNT(*) FROM t_merchant_member WHERE id=$memberId;")"
Check "★ 收尾自证：两个夹具商品已删除（不留脏商品影响别的套件）" `
    ((Sql 'duoduo_product' "SELECT COUNT(*) FROM t_spu WHERE id IN ($spuSelfId,$spuMerchantId);") -eq '0') `
    "left=$(Sql 'duoduo_product' "SELECT COUNT(*) FROM t_spu WHERE id IN ($spuSelfId,$spuMerchantId);")"
Check "★ 收尾自证：临时账号已降回 USER（不留多余商家/后台身份）" `
    ((Sql 'duoduo_user' "SELECT COUNT(*) FROM t_user WHERE phone IN ('$phoneSeller','$phoneOp') AND role<>'USER';") -eq '0') `
    "left=$(Sql 'duoduo_user' "SELECT COUNT(*) FROM t_user WHERE phone IN ('$phoneSeller','$phoneOp') AND role<>'USER';")"
Check "★ 收尾自证：全库 OPERATOR 数量与开跑前一致（没有留下永久后台钥匙）" `
    (([int](Sql 'duoduo_user' "SELECT COUNT(*) FROM t_user WHERE role='OPERATOR';")) -eq $opBefore) `
    "before=$opBefore after=$(Sql 'duoduo_user' "SELECT COUNT(*) FROM t_user WHERE role='OPERATOR';")"
Check "★ 收尾自证：t_merchant 行数与开跑前一致（没有把夹具店留在库里）" `
    (([int](Sql 'duoduo_user' "SELECT COUNT(*) FROM t_merchant;")) -eq $merchantBefore) `
    "before=$merchantBefore after=$(Sql 'duoduo_user' "SELECT COUNT(*) FROM t_merchant;")"
Check "★ 收尾自证：夹具租户名下的审计行已清空（tenant_id=$sellerId）" `
    ((Sql 'duoduo_user' "SELECT COUNT(*) FROM t_audit_log WHERE tenant_id = $sellerId;") -eq '0') `
    "left=$(Sql 'duoduo_user' "SELECT COUNT(*) FROM t_audit_log WHERE tenant_id = $sellerId;")"

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
