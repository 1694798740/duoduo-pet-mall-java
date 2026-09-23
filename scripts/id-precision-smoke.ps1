# 雪花 ID 精度保护（T-39）跨域回归冒烟（Windows PowerShell 5.1）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\id-precision-smoke.ps1
#
# 前置：容器全部 healthy（docker compose up -d），且已 mvn clean install + docker compose build + up -d。
#
# ── 这个脚本为什么必须存在 ─────────────────────────────────────────────
# T-39：雪花 id 是 18~19 位整数，超出 JS 安全整数上限 2^53-1（16 位）。
# 以 JSON **number** 下发时，浏览器 JSON.parse 会**静默四舍五入**，末位直接丢：
#     Number('361084524781768704') === 361084524781768700   // 704 → 700，不报错
# 前端拿这个已损坏的值拼 URL → 服务端查不到 → 404。A3 与 A8 各踩过一次。
#
# ★ 本脚本的核心手法：**断言 JSON 原始文本，不做反序列化**
#   PowerShell / .NET 用 Decimal/Int64 解 JSON 数字**不丢精度** —— 这正是
#   所有既有冒烟脚本"照不出这个问题"的原因（它们全都走 ConvertFrom-Json，
#   拿到的是精确值，自然认为一切正常）。所以本脚本刻意只对 $resp.raw
#   做**正则断言**，检查"该字段在原始 JSON 里是否带引号"。
#   只要哪天有人把 LongToStringJacksonModule 摘掉，这里立刻变红。
#
# ── 覆盖范围 ──────────────────────────────────────────────────────────
#   A. 反向断言（防回归）：各域下发的 id 类字段**必须是带引号的字符串**，
#      且字符串值**与库内真实 id 逐字符相同**（末位不许被舍入）；
#   B. 正向断言（防误伤）：金额 / 计数 / epoch 毫秒时间戳**必须仍是不带引号的数字**
#      —— 若为了修 T-39 而"见到 Long 就转字符串"，前端 price/100 会算出 NaN，
#      页面金额全打成"¥NaN"。判据是字段语义（会不会参与运算），不是 Java 类型；
#   C. 端到端：用**原始 JSON 文本里抠出来的 id** 直接拼 URL 再请求一次，
#      证明这个 id 是"可用的"（不只是长得像字符串）。
#
# 设计原则（沿用既有脚本教训）：
#   1. 夹具从接口动态取，不硬编码 id / 价格；
#   2. 不在本脚本里做算术（唯一例外是"末位比对"，用字符串下标，不用数值）；
#   3. 断言总数守卫：数脚本里 Check 的条数，与实跑条数比对（T-31）。
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    [string]$Product = 'http://127.0.0.1:8082',
    [string]$User = 'http://127.0.0.1:8081',
    [string]$Marketing = 'http://127.0.0.1:8085',
    [string]$Trade = 'http://127.0.0.1:8083',
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
        if ($null -eq $body -and $raw) { try { $body = $raw | ConvertFrom-Json } catch { } }
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

# ───────── 核心工具：对 **JSON 原始文本** 做断言，绕开 ConvertFrom-Json ─────────

# 从原始 JSON 里取某字段的「值文本」，保留它到底带不带引号。
# 返回 @{ raw = '"12900"' 或 '12900'; quoted = $true/$false; value = 去引号后的字符串 }
function FieldText([string]$json, [string]$name) {
    if (-not $json) { return $null }
    # 匹配 "name" : 后面紧跟的值（字符串或数字），只取第一处
    $m = [regex]::Match($json, '"' + [regex]::Escape($name) + '"\s*:\s*(?<v>"[^"]*"|-?\d+(?:\.\d+)?|null|true|false)')
    if (-not $m.Success) { return $null }
    $v = $m.Groups['v'].Value
    $quoted = $v.StartsWith('"')
    $inner = if ($quoted) { $v.Substring(1, $v.Length - 2) } else { $v }
    return @{ raw = $v; quoted = $quoted; value = $inner }
}

# ⚠ 辅助函数内的 Check 计数问题：这两个函数每次被调用都会执行 2~3 条断言，
#   所以「文件里 Check 字面出现次数」不等于「实际执行次数」。
#   为让断言总数守卫仍然有效，这两个函数每执行一条断言就累加 $script:declaredDynamic，
#   守卫最终比对的是 (文件里的字面 Check 数 + 已膨胀数) 与 (实跑数)。
#   这样：新增一个"漏跑"的分支会立刻失配；辅助函数被多调用几次也能自洽。
$script:declaredDynamic = 0

# 断言：某字段是**字符串**，且其值**与库内真实值逐字符相同**
function CheckIdField([string]$label, [string]$json, [string]$field, [string]$dbValue) {
    $f = FieldText $json $field
    if ($null -eq $f) {
        $script:declaredDynamic++
        Check "$label · $field 出现在响应里" $false '字段缺失'
        return $null
    }
    $script:declaredDynamic += 2
    Check "$label · $field 是 JSON 字符串（不是裸数字）" $f.quoted $f.raw
    Check "$label · $field 的值与库内逐字符相同（末位未被舍入）" ($f.value -eq $dbValue) "响应=$($f.value) 库内=$dbValue"
    return $f.value
}

# 断言：某字段是**数字**（不带引号）
function CheckNumericField([string]$label, [string]$json, [string]$field) {
    $f = FieldText $json $field
    if ($null -eq $f) {
        $script:declaredDynamic++
        Check "$label · $field 出现在响应里" $false '字段缺失'
        return
    }
    $script:declaredDynamic++
    Check "$label · $field 仍是 JSON 数字（前端要参与运算，不能字符串化）" ((-not $f.quoted) -and ($f.value -match '^-?\d+$')) $f.raw
}

# 数值比较用（时间戳量级），只用于"它是合理的 epoch 毫秒"这类宽松判断
function AsLong([string]$s) {
    $v = 0L
    if ([long]::TryParse($s, [ref]$v)) { return $v }
    return 0L
}

Write-Host "=== 多多宠物商店 · 雪花 ID 精度保护（T-39）回归 @ $Gateway ==="
Write-Host "内部直连：product=$Product  user=$User  marketing=$Marketing  trade=$Trade"
Write-Host ""

# ---------- 0. 就绪检查 ----------
Write-Host "> 就绪检查"
$gw = Call 'Get' "$Gateway/actuator/health" $null $null $null
Check "网关 /actuator/health 就绪" ($gw.status -eq 200) $gw.status
$pr = Call 'Get' "$Product/actuator/health" $null $null $null
Check "商品服务 /actuator/health 就绪" ($pr.status -eq 200) $pr.status

# ---------- 1. 商品域：SKU / SPU / 卖家 id ----------
Write-Host ""
Write-Host "> 商品域：SKU 详情（id 必须字符串，price 必须数字）"
$dbSkuId = "$(Sql 'duoduo_product' "SELECT id FROM t_sku WHERE id = $SKU;")"
Check "（前置）库内 SKU $SKU 存在" ($dbSkuId -eq "$SKU") $dbSkuId

$skuRaw = Call 'Get' "$Product/api/products/internal/skus/batch?ids=$SKU" $null $null @{ 'X-Internal-Token' = $InternalToken }
Check "内部批量 SKU 接口返回 200" ($skuRaw.status -eq 200) $skuRaw.status
CheckNumericField "SKU 详情" $skuRaw.raw 'price'
CheckNumericField "SKU 详情" $skuRaw.raw 'availableStock'
# skuId / spuId / sellerId 必须是字符串
$skuIdVal = CheckIdField "SKU 详情" $skuRaw.raw 'skuId' "$SKU"
$spuIdVal = FieldText $skuRaw.raw 'spuId'
Check "SKU 详情 · spuId 是 JSON 字符串" ($spuIdVal -and $spuIdVal.quoted) $(if ($spuIdVal) { $spuIdVal.raw } else { '缺失' })
$sellerF = FieldText $skuRaw.raw 'sellerId'
Check "SKU 详情 · sellerId 是 JSON 字符串" ($sellerF -and $sellerF.quoted) $(if ($sellerF) { $sellerF.raw } else { '缺失' })
# ★ 端到端：用原始文本里抠出来的 skuId 再请求一次，证明它是"可用的"
if ($skuIdVal) {
    $again = Call 'Get' "$Product/api/products/internal/skus/batch?ids=$skuIdVal" $null $null @{ 'X-Internal-Token' = $InternalToken }
    $back = if ($again.raw) { FieldText $again.raw 'skuId' } else { $null }
    Check "★ 用响应里抠出的 skuId 反查，仍能命中同一条（id 是可用的，不只是长得像字符串）" (($again.status -eq 200) -and $back -and ($back.value -eq $skuIdVal)) "status=$($again.status) back=$(if ($back) { $back.value } else { 'null' })"
}

# ---------- 2. 商品域：类目 / 品牌 id（前端要用于筛选） ----------
Write-Host ""
Write-Host "> 商品域：类目列表（前端用 id 做筛选，精度丢失会导致筛出空白）"
$catRaw = Call 'Get' "$Product/api/categories" $null $null $null
Check "类目列表返回 200" ($catRaw.status -eq 200) $catRaw.status
$dbCatCount = "$(Sql 'duoduo_product' "SELECT COUNT(*) FROM t_category;")"
$rawIdCount = ([regex]::Matches($catRaw.raw, '"(?:id|parentId)"\s*:\s*"?\d+"?')).Count
Check "★ 类目响应里的 id/parentId 全部带引号（字符串）" `
    (([regex]::Matches($catRaw.raw, '"(?:id|parentId)"\s*:\s*\d')).Count -eq 0) `
    "发现裸数字 id 的处数=$(([regex]::Matches($catRaw.raw, '"(?:id|parentId)"\s*:\s*\d')).Count)"
Check "类目响应结构完整（id 字段数量与库内条数相当）" ($rawIdCount -ge [int]$dbCatCount) "响应 id 处数=$rawIdCount 库内条数=$dbCatCount"

# ---------- 3. 用户域：登录用户 id ----------
Write-Host ""
Write-Host "> 用户域：登录态（userId 是雪花 id，前端要用于路由与内部请求）"
$phone = "133" + (Get-Random -Minimum 10000000 -Maximum 99999999)
$reg = Register $phone 'id精度测试'
Check "账号注册/登录成功" ($reg.result.status -eq 200) $reg.result.status
$sess = $reg.session
$meRaw = Call 'Get' "$Gateway/api/users/me" $sess $null $null
Check "GET /api/users/me 返回 200" ($meRaw.status -eq 200) $meRaw.status
$meIdF = FieldText $meRaw.raw 'id'
Check "★ 用户 id 是 JSON 字符串" ($meIdF -and $meIdF.quoted) $(if ($meIdF) { $meIdF.raw } else { '缺失' })
if ($meIdF) {
    $dbUid = "$(Sql 'duoduo_user' "SELECT id FROM t_user WHERE phone = '$phone';")"
    Check "★ 用户 id 与库内逐字符相同（末位未被舍入）" ($meIdF.value -eq $dbUid) "响应=$($meIdF.value) 库内=$dbUid"
    # 反向：证明这个 id 真的超出了 JS 安全整数（否则本脚本没有存在意义）
    Check "★（自证前提）该 id 确实超过 2^53-1（16 位），即本脚本测的是真问题" ($meIdF.value.Length -gt 16) "id=$($meIdF.value) 长度=$($meIdF.value.Length)"
}

# ---------- 4. 交易域：下单 ----------
# 注意：OrderView **刻意不暴露内部 id**，对外统一用业务键 orderNo（见 types.ts 的 OrderView 定义）。
# 因此订单域要断言的是**嵌套项里的雪花 id**：OrderItemView.skuId / spuId（前端要拿它跳商品详情）。
Write-Host ""
Write-Host "> 交易域：用 addressId 下单（嵌套 items 里的 skuId 是雪花值，前端要用于跳转）"
# 先建一个地址，拿 addressId
$addrRaw = Call 'Post' "$Gateway/api/addresses" $sess @{
    receiverName = '精度测试'; receiverPhone = '13800001111'
    province = '北京市'; city = '北京市'; district = '朝阳区'
    detail = '建国路 88 号 T-39 测试'; label = '家'
} $null
Check "新建收货地址成功" ($addrRaw.status -eq 200) $addrRaw.status
$addrIdF = FieldText $addrRaw.raw 'id'
Check "★ 地址 id 是 JSON 字符串" ($addrIdF -and $addrIdF.quoted) $(if ($addrIdF) { $addrIdF.raw } else { '缺失' })

$cart = Call 'Post' "$Gateway/api/cart/items" $sess @{ skuId = $SKU; quantity = 1 } $null
Check "加入购物车成功" ($cart.status -eq 200) $cart.status
$orderRaw = Call 'Post' "$Gateway/api/orders" $sess @{
    addressId = $(if ($addrIdF) { $addrIdF.value } else { $null }); idempotentKey = (NewKey)
} $null
Check "用 addressId 下单成功（顺带验 id 经网关可用）" ($orderRaw.status -eq 200) "$($orderRaw.status) $($orderRaw.raw)"
CheckNumericField "下单响应" $orderRaw.raw 'payAmount'
CheckNumericField "下单响应" $orderRaw.raw 'totalAmount'
CheckNumericField "下单响应" $orderRaw.raw 'orderCount'
# 嵌套 items[].skuId 必须是字符串（这是本域真正的雪花 id 暴露点）
Check "★ 下单响应 items[].skuId 全部带引号（字符串）" `
    (([regex]::Matches($orderRaw.raw, '"skuId"\s*:\s*\d')).Count -eq 0) `
    "裸数字处数=$(([regex]::Matches($orderRaw.raw, '"skuId"\s*:\s*\d')).Count)"
Check "★ 下单响应 items[].spuId 全部带引号（字符串）" `
    (([regex]::Matches($orderRaw.raw, '"spuId"\s*:\s*\d')).Count -eq 0) `
    "裸数字处数=$(([regex]::Matches($orderRaw.raw, '"spuId"\s*:\s*\d')).Count)"
Check "★ 下单响应 items[].price 仍是数字（金额不能被字符串化）" `
    (([regex]::Matches($orderRaw.raw, '"price"\s*:\s*"')).Count -eq 0) `
    "被字符串化处数=$(([regex]::Matches($orderRaw.raw, '"price"\s*:\s*"')).Count)"
CheckNumericField "下单响应 items[0]" $orderRaw.raw 'amount'
CheckNumericField "下单响应 items[0]" $orderRaw.raw 'quantity'

$orderNo = if ($orderRaw.body.data.orders) { @($orderRaw.body.data.orders)[0].orderNo } else { $null }
if ($orderNo) {
    $detailRaw = Call 'Get' "$Gateway/api/orders/$orderNo" $sess $null $null
    Check "订单详情返回 200" ($detailRaw.status -eq 200) $detailRaw.status
    CheckNumericField "订单详情" $detailRaw.raw 'payAmount'
    # 订单详情里 sellerId 是字符串（前端用于/卖家相关跳转）
    $osF = FieldText $detailRaw.raw 'sellerId'
    Check "★ 订单详情 · sellerId 是 JSON 字符串" ($osF -and $osF.quoted) $(if ($osF) { $osF.raw } else { '缺失' })
    # createdAt 是 ISO 字符串（不是 epoch 数字），前端直接交给 new Date()
    $tsF = FieldText $detailRaw.raw 'createdAt'
    Check "订单详情 · createdAt 是 ISO 字符串（秒级可读，非 epoch 数字）" ($tsF -and $tsF.quoted) $(if ($tsF) { $tsF.raw } else { '缺失' })
    # ★ 端到端：用响应里抠出的 skuId 反查商品，证明嵌套 id 也可用
    $itemSkuF = FieldText $detailRaw.raw 'skuId'
    # 哨兵写法：if/else 各写一遍断言会让"声明数"虚高、守卫误报，这里只保留一处断言
    $back2 = $null
    $b2 = $null
    if ($itemSkuF) {
        $back2 = Call 'Get' "$Product/api/products/internal/skus/batch?ids=$($itemSkuF.value)" $null $null @{ 'X-Internal-Token' = $InternalToken }
        $b2 = if ($back2.raw) { FieldText $back2.raw 'skuId' } else { $null }
    }
    Check "★ 用订单里抠出的 items[].skuId 反查商品，能命中同一条" `
        ($itemSkuF -and $back2 -and ($back2.status -eq 200) -and $b2 -and ($b2.value -eq $itemSkuF.value)) `
        "skuId=$(if ($itemSkuF) { $itemSkuF.value } else { '订单详情里没有 skuId 字段' }) status=$(if ($back2) { $back2.status } else { 'n/a' })"
}

# ---------- 5. 营销域：券 ----------
# 注意：CouponView **刻意不暴露内部 id**，对外用 code（随机串）+ templateId（字符串）。
# 券码用随机串正是因为"券要贴给用户看、可能要抄写"，用雪花 id 既长又踩精度坑（见 types.ts 注释）。
Write-Host ""
Write-Host "> 营销域：优惠券（模板 id 是种子小整数，也统一按字符串下发）"
$tplRaw = Call 'Get' "$Gateway/api/coupons/templates" $sess $null $null
Check "券模板列表返回 200" ($tplRaw.status -eq 200) $tplRaw.status
$tplIdF = FieldText $tplRaw.raw 'id'
Check "★ 券模板 id 是 JSON 字符串（种子小整数也统一按字符串，避免前端两套写法）" ($tplIdF -and $tplIdF.quoted) $(if ($tplIdF) { $tplIdF.raw } else { '缺失' })
Check "★ 券模板响应里所有 id 字段都不带裸数字" `
    (([regex]::Matches($tplRaw.raw, '"(?:id|templateId)"\s*:\s*\d')).Count -eq 0) `
    "裸数字处数=$(([regex]::Matches($tplRaw.raw, '"(?:id|templateId)"\s*:\s*\d')).Count)"
CheckNumericField "券模板" $tplRaw.raw 'thresholdAmount'
CheckNumericField "券模板" $tplRaw.raw 'discountAmount'
CheckNumericField "券模板" $tplRaw.raw 'totalCount'
CheckNumericField "券模板" $tplRaw.raw 'remainingCount'

if ($tplIdF) {
    # 真实领取路径：POST /api/coupons/templates/{templateId}/claim
    $claim = Call 'Post' "$Gateway/api/coupons/templates/$($tplIdF.value)/claim" $sess $null $null
    Check "领券成功（顺带验 templateId 经网关可用）" (@(200, 201) -contains $claim.status) "$($claim.status) $($claim.raw)"
    $mineRaw = Call 'Get' "$Gateway/api/coupons/mine" $sess $null $null
    Check "我的券列表返回 200" ($mineRaw.status -eq 200) $mineRaw.status
    # 用"哨兵 + 单一路径"写法：两条分支各写一遍断言会让声明数虚高、守卫误报。
    # 因此把失败路径也归一成"取出空响应"，只保留一组断言（沿用既有脚本的写法纪律）。
    $mineOk = ($mineRaw.status -eq 200)
    $mineJson = if ($mineOk) { $mineRaw.raw } else { '' }
    $ucCodeF = if ($mineOk) { FieldText $mineJson 'code' } else { $null }
    Check "★ 用户券的 code 是字符串（刻意用随机串规避雪花 id 精度坑）" ($ucCodeF -and $ucCodeF.quoted -and $ucCodeF.value.Length -gt 0) $(if ($ucCodeF) { $ucCodeF.raw } else { "mine 接口 status=$($mineRaw.status)" })
    $ucTplF = if ($mineOk) { FieldText $mineJson 'templateId' } else { $null }
    Check "★ 我的券 · templateId 是 JSON 字符串" ($ucTplF -and $ucTplF.quoted) $(if ($ucTplF) { $ucTplF.raw } else { "mine 接口 status=$($mineRaw.status)" })
    Check "★ 我的券响应里没有裸数字的 id/templateId" `
        ($mineOk -and (([regex]::Matches($mineJson, '"(?:id|templateId)"\s*:\s*\d')).Count -eq 0)) `
        "mine status=$($mineRaw.status) 裸数字处数=$(([regex]::Matches($mineJson, '"(?:id|templateId)"\s*:\s*\d')).Count)"
    CheckNumericField "我的券" $mineJson 'thresholdAmount'
    CheckNumericField "我的券" $mineJson 'discountAmount'
}

# ---------- 6. 全站扫描：响应体里不应出现"裸数字的长 id 字段" ----------
Write-Host ""
Write-Host "> 全站扫描：常见 id 字段名一律不得以裸数字出现"
$idFieldPattern = '"(?:id|userId|spuId|skuId|sellerId|categoryId|brandId|addressId|templateId|buyerId|petId|orderId)"\s*:\s*-?\d'
$bareHits = @()
foreach ($probe in @(
        @{ name = '商品 · 类目';   resp = $catRaw },
        @{ name = '商品 · SKU';    resp = $skuRaw },
        @{ name = '用户 · me';     resp = $meRaw },
        @{ name = '营销 · 券模板'; resp = $tplRaw },
        @{ name = '交易 · 下单';   resp = $orderRaw }
    )) {
    $n = ([regex]::Matches($probe.resp.raw, $idFieldPattern)).Count
    if ($n -gt 0) { $bareHits += "$($probe.name)=$n 处" }
}
Check "★ 已探测的 5 个响应体里，id 类字段没有任何一处是裸数字" ($bareHits.Count -eq 0) ($bareHits -join '；')

# ---------- 7. 清理夹具 ----------
Write-Host ""
Write-Host "> 清理夹具与自证复原"
if ($orderNo) {
    $cancel = Call 'Post' "$Gateway/api/orders/$orderNo/cancel" $sess @{ reason = 'T-39 回归自清理' } $null
    Check "订单已取消（释放冻结库存）" (@(200, 409) -contains $cancel.status) $cancel.status
    # 订单是**状态机数据**，取消只改状态不删行（审计需要），所以断言的是状态而非行数。
    # 终态是 CLOSED（已关闭）—— OrderStatus 枚举里没有 CANCELLED，取消动作落到 CLOSED。
    $orderStatus = Sql 'duoduo_trade' "SELECT status FROM t_order WHERE order_no = '$orderNo';"
    Check "★ 订单夹具已落到终态 CLOSED（已关闭，不是残留的待付款）" ($orderStatus -eq 'CLOSED') "status=$orderStatus"
}
if ($addrIdF) {
    $delAddr = Call 'Delete' "$Gateway/api/addresses/$($addrIdF.value)" $sess $null $null
    Check "地址夹具已删除" (@(200, 404) -contains $delAddr.status) $delAddr.status
}
Check "★ SKU $SKU 冻结量已清零（不留脏库存）" ((Sql 'duoduo_product' "SELECT COALESCE(frozen_stock,0) FROM t_sku WHERE id = $SKU;") -eq '0')
Sql 'duoduo_user' "DELETE FROM t_user_address WHERE user_id = (SELECT id FROM t_user WHERE phone = '$phone');" | Out-Null
Sql 'duoduo_user' "DELETE FROM t_user WHERE phone = '$phone';" | Out-Null
Check "用户夹具已删除" ((Sql 'duoduo_user' "SELECT COUNT(*) FROM t_user WHERE phone = '$phone';") -eq '0')

# ---------- 结果 ----------
Write-Host ""
# 断言总数守卫（T-31 的加强版）：声明数 = 文件里的字面 Check 数 + 辅助函数膨胀数
#   ① 字面数：`Check "` 在行首出现几次；
#   ② 膨胀数：CheckIdField / CheckNumericField 每次调用会执行 1~2 条，函数内已累加 $declaredDynamic，
#      但它们的字面 Check 也在①里被数到了（函数体本身），所以要减掉函数体的字面数。
# 简化做法：直接信 $declaredDynamic（辅助函数内每条断言都精确计数），
#          再加上"函数体外"的字面 Check 数（脚本主体里的 Check 调用）。
# → 主体字面数 = 文件字面总数 - 辅助函数体内的字面数
$literalTotal = ([regex]::Matches((Get-Content -Raw -LiteralPath $PSCommandPath), '(?m)^\s*Check\s+"')).Count
$literalInHelpers = 5   # CheckIdField 内 3 条 + CheckNumericField 内 2 条（改动这两个函数时需同步本数字）
$declared = ($literalTotal - $literalInHelpers) + $script:declaredDynamic
$ran = $pass + $fail
if ($ran -ne $declared) {
    Write-Host "  [GUARD-FAIL] 断言总数守卫：声明 $declared 项（主体 $($literalTotal - $literalInHelpers) + 辅助膨胀 $($script:declaredDynamic)），实际执行 $ran 项 —— 有 Check 被静默跳过（多半是调用写法/参数写错）"
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
