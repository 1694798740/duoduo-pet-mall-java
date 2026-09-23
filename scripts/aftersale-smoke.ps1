# 售后仲裁工单（A3）端到端冒烟（Windows PowerShell 5.1）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\aftersale-smoke.ps1
#
# 前置：容器全部 healthy（docker compose up -d），且已 mvn package + docker compose build 出最新镜像。
#
# 覆盖 docs/agent/07 的 A3 六条验收标准：
#   ① 非订单当事人发起 403；
#   ② 未发货 / 未完成订单发起 422；
#   ③ 重复对同一订单同一类型立案 409；
#   ④ 裁定退款后 trade 侧订单变 REFUNDED 且托管转 REFUNDED；
#   ⑤ 缺裁定说明 422；
#   ⑥ 全流程状态可查、有留痕（卖家说明、裁定说明、退款金额都读得回来）。
#
# 另外覆盖（07 没写但真实存在、不测迟早出事的）：
#   · 同单**不同类型**可各自立案（证明唯一键是 (order_id, type)，不是 order_id）；
#   · 卖家「同意即退款」这条与运营裁定**不同**的资金路径；
#   · ★ 已完成订单（钱已放款给卖家）：立案可以，但裁定退款必须被拒并提示走追偿 ——
#     这是项目已知边界 B-01，不测的话这个口子会以「钱退了两次」的形式暴露在线上；
#   · 卖家拒绝 → 买家申请仲裁 → 运营裁定 的完整闭环；
#   · 越权读详情、卖家列表准入、运营列表准入、状态过滤。
#
# 设计原则（沿用前几套脚本的教训）：
#   1. 夹具从接口动态取（SKU 价格与库存不硬编码），归属夹具改库前先记录、结尾复原；
#   2. 断言用**业务键**（订单号）与**从原始响应文本正则提取的售后单 id**，不用 JSON 解析出来的 19 位雪花值
#      （JSON.parse / ConvertFrom-Json 都会丢精度，出现「数据在库里、断言找不到」的假失败）；
#   3. 反向场景用状态码写清口径：参数/取值错 400、业务规则与状态机不允许 422、越权 403、
#      不存在 404、唯一键撞键 409；
#   4. 中文内容一律走接口读回比对，不用 docker exec mysql 的 GBK 控制台比中文；
#   5. 已付款订单会真实扣减库存、退款会真实回补，结尾显式复原库存，保证脚本可反复执行。
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    # 直连交易服务端口：内部接口（补偿调度）必须直连，走网关会被按未登录拦成 401
    [string]$Trade = 'http://127.0.0.1:8083',
    [string]$Password = 'duoduo123',
    [string]$InternalToken = 'duoduo-local-dev-internal-token',
    [int]$SKU = 2001,
    [switch]$SkipDbCheck
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
        $params.Body = ($bodyObj | ConvertTo-Json -Compress -Depth 6)
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

# 售后单 id 在响应里是**字符串**（服务端刻意如此：18 位雪花值超过 JS 安全整数上限，
# 序列化成数字会被 JSON.parse 静默改写）。这里直接从原始响应文本取，不经任何数值转换。
function IdOf($r) {
    if (-not $r -or -not $r.raw) { return $null }
    $m = [regex]::Match($r.raw, '"id":"(\d+)"')
    if ($m.Success) { return $m.Groups[1].Value }
    return $null
}

function NewKey { return [guid]::NewGuid().ToString() }

function PlaceAndPay($session, $sku, $qty, $label) {
    Call 'Post' "$Gateway/api/cart/items" $session @{ skuId = $sku; quantity = $qty } | Out-Null
    $o = Call 'Post' "$Gateway/api/orders" $session ($recv + @{ idempotentKey = (NewKey) })
    if ($o.status -ne 200) { return @{ ok = $false; orderNo = $null; order = $null; step = "下单 $label" } }
    $order = @($o.body.data.orders)[0]
    $p = Call 'Post' "$Gateway/api/orders/$($order.orderNo)/pay" $session @{ channel = 'MOCK'; idempotentKey = (NewKey) }
    if ($p.status -ne 200) { return @{ ok = $false; orderNo = $order.orderNo; order = $order; step = "支付 $label" } }
    return @{ ok = $true; orderNo = $order.orderNo; order = $order; step = $null }
}

$H_INTERNAL = @{ 'X-Internal-Token' = $InternalToken }
$phoneA = '139' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneB = '138' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneS = '137' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneOp = '136' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$recv = @{ receiverName = '小林'; receiverPhone = '13900000000'; receiverAddress = '上海市浦东新区张江路 1 号' }

Write-Host ""
Write-Host "=== 多多宠物商店 · 售后仲裁工单（A3）冒烟 @ $Gateway ==="
Write-Host "买家 A = $phoneA ｜ 他人 B = $phoneB ｜ 卖家 S = $phoneS ｜ 运营 = $phoneOp"
Write-Host ""

# ---------- 0. 网关就绪 ----------
Write-Host "> 网关就绪"
$ready = $false
for ($i = 0; $i -lt 40; $i++) {
    try {
        $h = Invoke-WebRequest -Uri "$Gateway/actuator/health" -UseBasicParsing -TimeoutSec 5
        if ($h.StatusCode -eq 200) { $ready = $true; break }
    } catch { Start-Sleep -Seconds 1 }
}
Check "网关 /actuator/health 就绪" $ready
if (-not $ready) { Write-Host "网关未就绪，终止"; exit 1 }

# ---------- 1. 账号夹具 ----------
Write-Host ""
Write-Host "> 账号夹具"
$regA = Register $phoneA '售后测试-买家A'
Check "买家 A 注册/登录成功" ($regA.result.status -eq 200) $regA.result.status
$sessA = $regA.session

$regB = Register $phoneB '售后测试-他人B'
Check "他人 B 注册/登录成功" ($regB.result.status -eq 200) $regB.result.status
$sessB = $regB.session

$regS = Register $phoneS '售后测试-卖家S'
Check "卖家 S 注册成功（初始 USER）" ($regS.result.status -eq 200) $regS.result.status
$sessS = $regS.session
$r = Call 'Post' "$Gateway/api/users/me/realname" $sessS @{ realName = '张老板'; idCard = '310101199001011234' }
Check "卖家 S 实名认证成功" ($r.status -eq 200) $r.status
$r = Call 'Post' "$Gateway/api/users/me/merchant" $sessS $null
Check "卖家 S 申请成为卖家成功" ($r.status -eq 200) $r.status
$sessS = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$r = Call 'Post' "$Gateway/api/auth/login" $sessS @{ phone = $phoneS; password = $Password }
Check "卖家 S 重新登录成功（角色是 token 快照，必须重登）" ($r.status -eq 200) $r.status

$regOp = Register $phoneOp '售后测试-运营'
Check "运营账号注册成功（初始 USER）" ($regOp.result.status -eq 200) $regOp.result.status
Sql 'duoduo_user' "UPDATE t_user SET role = 'OPERATOR' WHERE phone = '$phoneOp';" | Out-Null
Check "库内角色已提权为 OPERATOR" ((Sql 'duoduo_user' "SELECT role FROM t_user WHERE phone = '$phoneOp';") -eq 'OPERATOR')
$sessOp = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$r = Call 'Post' "$Gateway/api/auth/login" $sessOp @{ phone = $phoneOp; password = $Password }
Check "运营账号重新登录成功" ($r.status -eq 200) $r.status

# 卖家 userId 从库内直取字符串，避开 19 位雪花经 JSON 解析丢精度
$sellerId = Sql 'duoduo_user' "SELECT id FROM t_user WHERE phone = '$phoneS';"
Check "（前置）取得卖家 S 的 userId" (-not [string]::IsNullOrEmpty($sellerId)) $sellerId

# ---------- 2. 权限边界 ----------
Write-Host ""
Write-Host "> 权限边界（未登录 401 / 非运营 403 / 非卖家 403）"
$anon = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$r = Call 'Post' "$Gateway/api/aftersales" $anon @{ orderNo = 'ANY'; type = 'REFUND_ONLY'; reason = 'x' }
Check "未登录立案返回 401（网关拦截）" ($r.status -eq 401) $r.status
$r = Call 'Get' "$Gateway/api/aftersales/mine" $anon $null $null
Check "未登录查我的售后返回 401" ($r.status -eq 401) $r.status
$r = Call 'Get' "$Gateway/api/admin/aftersales" $anon $null $null
Check "未登录访问运营售后工作台返回 401" ($r.status -eq 401) $r.status
$r = Call 'Get' "$Gateway/api/admin/aftersales" $sessA $null $null
Check "普通用户访问运营售后工作台返回 403（OperatorGuard）" ($r.status -eq 403) $r.status
$r = Call 'Post' "$Gateway/api/admin/aftersales/1/arbitrate" $sessA @{ result = 'REJECT'; note = 'x' }
Check "普通用户裁定返回 403" ($r.status -eq 403) $r.status
$r = Call 'Get' "$Gateway/api/aftersales/seller" $sessA $null $null
Check "非卖家访问店铺售后返回 403" ($r.status -eq 403) $r.status
$r = Call 'Get' "$Gateway/api/aftersales/seller" $sessS $null $null
Check "卖家访问店铺售后放行（200）" ($r.status -eq 200) $r.status

# ---------- 3. 夹具前提与订单 ----------
Write-Host ""
Write-Host "> 夹具（SKU $SKU，本脚本需 5 件）"
$batch = Call 'Get' "$Gateway/api/products/internal/skus/batch?ids=$SKU" $null $null $H_INTERNAL
$skuInfo = @($batch.body.data)[0]
$P = [long]$skuInfo.price
$S = [int]$skuInfo.availableStock
Write-Host "  SKU $SKU 单价 $P 分 可售 $S"
if ($P -le 0 -or $S -lt 8) {
    Write-Host "夹具前提不满足，终止本次测试，不执行任何写操作。"
    Check "夹具前提：价格与库存均从接口正确读到且可售 ≥ 8" $false "P=$P S=$S"
    Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
    exit 1
}
Check "夹具前提：SKU $SKU 可售 ≥ 8（本脚本需 5 件）" ($S -ge 8) $S
$dbStockBefore = [int](Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU;")
Check "（前置）库内库存与接口可售一致（冻结为 0）" ($dbStockBefore -eq $S) "db=$dbStockBefore api=$S"
Check "（前置）库内冻结量为 0" ((Sql 'duoduo_product' "SELECT COALESCE(frozen_stock,0) FROM t_sku WHERE id = $SKU;") -eq '0')

Write-Host ""
Write-Host "> 夹具订单：O1/O2/O3 已发货（SHIPPED）、O4 已完成（COMPLETED）、O5 已付款未发货（PAID）"
$c1 = PlaceAndPay $sessA $SKU 1 'O1'
$c2 = PlaceAndPay $sessA $SKU 1 'O2'
$c3 = PlaceAndPay $sessA $SKU 1 'O3'
$c4 = PlaceAndPay $sessA $SKU 1 'O4'
$c5 = PlaceAndPay $sessA $SKU 1 'O5'
Check "五笔夹具订单均下单支付成功" ($c1.ok -and $c2.ok -and $c3.ok -and $c4.ok -and $c5.ok) "$($c1.step) $($c2.step) $($c3.step) $($c4.step) $($c5.step)"
if (-not ($c1.ok -and $c2.ok -and $c3.ok -and $c4.ok -and $c5.ok)) {
    Write-Host "夹具订单创建失败，终止。"
    Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
    exit 1
}
$on1 = $c1.orderNo; $on2 = $c2.orderNo; $on3 = $c3.orderNo; $on4 = $c4.orderNo; $on5 = $c5.orderNo
$pay1 = [long]$c1.order.payAmount

foreach ($on in @($on1, $on2, $on3)) {
    $r = Call 'Post' "$Gateway/api/orders/$on/ship" $sessA $null $H_INTERNAL
    if ($r.status -ne 200) { Check "夹具：订单 $on 发货成功" $false $r.status }
}
$r = Call 'Post' "$Gateway/api/orders/$on4/ship" $sessA $null $H_INTERNAL
$r = Call 'Post' "$Gateway/api/orders/$on4/confirm" $sessA $null
Check "（夹具）O4 已发货并确认收货（COMPLETED，钱已放款给卖家）" ($r.status -eq 200 -and $r.body.data.status -eq 'COMPLETED') $r.body.data.status

# 归属夹具：把这五笔订单划给卖家 S（记录原值，结尾复原）
# 订单与托管单必须一起改：两表的 seller_id 是同一事实的两份记录，只改一处会得到自相矛盾的夹具。
$origSellers = Sql 'duoduo_trade' "SELECT CONCAT(order_no,'=',seller_id) FROM t_order WHERE order_no IN ('$on1','$on2','$on3','$on4','$on5') ORDER BY order_no;"
if (-not $SkipDbCheck) {
    Sql 'duoduo_trade' "UPDATE t_order SET seller_id = $sellerId WHERE order_no IN ('$on1','$on2','$on3','$on4','$on5');" | Out-Null
    Sql 'duoduo_trade' "UPDATE t_settlement s JOIN t_order o ON o.id = s.order_id SET s.seller_id = $sellerId WHERE o.order_no IN ('$on1','$on2','$on3','$on4','$on5');" | Out-Null
    $bound = Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_order WHERE order_no IN ('$on1','$on2','$on3','$on4','$on5') AND seller_id = $sellerId;"
    Check "（夹具）五笔订单已划给卖家 S" ($bound -eq '5') $bound
}

# ---------- 4. 立案：反向场景 ----------
Write-Host ""
Write-Host "> 立案（反向场景）"
$r = Call 'Post' "$Gateway/api/aftersales" $sessB @{ orderNo = $on1; type = 'REFUND_ONLY'; reason = '不是我的订单' }
Check "① 非订单当事人立案返回 403" ($r.status -eq 403) $r.status
Check "① 拒绝文案说明是归属问题" ($r.body.message -match '自己') $r.body.message
$r = Call 'Post' "$Gateway/api/aftersales" $sessA @{ orderNo = $on5; type = 'REFUND_ONLY'; reason = '还没发货就想售后' }
Check "② 已付款未发货订单立案返回 422" ($r.status -eq 422) $r.status
Check "② 提示把「当前状态 + 发货后才可售后」讲清楚" ($r.body.message -match '订单当前状态为' -and $r.body.message -match '发货后才能申请售后') $r.body.message
$r = Call 'Post' "$Gateway/api/aftersales" $sessA @{ orderNo = 'NOT-EXIST-ORDER-NO'; type = 'REFUND_ONLY'; reason = 'x' }
Check "订单不存在立案返回 404" ($r.status -eq 404) $r.status
$r = Call 'Post' "$Gateway/api/aftersales" $sessA @{ orderNo = $on1; type = 'NOT_A_TYPE'; reason = 'x' }
Check "非法售后类型返回 400" ($r.status -eq 400) $r.status
$r = Call 'Post' "$Gateway/api/aftersales" $sessA @{ orderNo = $on1; type = 'REFUND_ONLY' }
Check "缺诉求描述返回 400（字段级必填，注解拦在入口）" ($r.status -eq 400) $r.status
$r = Call 'Post' "$Gateway/api/aftersales" $sessA @{ orderNo = $on1; type = 'REFUND_ONLY'; reason = 'x'; images = @('javascript:alert(1)') }
Check "举证图非 http/https 直链返回 400（挡住 XSS scheme）" ($r.status -eq 400) $r.status
$r = Call 'Post' "$Gateway/api/aftersales" $sessA @{ orderNo = $on1; type = 'REFUND_ONLY'; reason = 'x'; images = @('http://a/1.png','http://a/2.png','http://a/3.png','http://a/4.png','http://a/5.png','http://a/6.png','http://a/7.png') }
Check "举证图超过 6 张返回 400" ($r.status -eq 400) $r.status

# ---------- 5. 立案：正常与重复 ----------
Write-Host ""
Write-Host "> 立案（正常 / 重复 / 不同类型）"
$r = Call 'Post' "$Gateway/api/aftersales" $sessA @{ orderNo = $on1; type = 'REFUND_ONLY'; reason = '收到的猫粮包装破损，要求退款'; images = @('http://img.example.com/p1.png') }
Check "立案返回 200" ($r.status -eq 200) ($r.body | ConvertTo-Json -Compress)
$asId1 = IdOf $r
Check "（前置）取得售后单 id（原始文本正则提取）" (-not [string]::IsNullOrEmpty($asId1)) $asId1
Check "新立案状态为 PENDING" ($r.body.data.status -eq 'PENDING') $r.body.data.status
Check "状态文案为「待卖家处理」" ($r.body.data.statusLabel -eq '待卖家处理') $r.body.data.statusLabel
Check "类型文案为「仅退款」" ($r.body.data.typeLabel -eq '仅退款') $r.body.data.typeLabel
# 买家 id 必须等于订单事实里的归属人（而不是请求体里能传的东西）。
# 注意：这里刻意从**库内**读两边再比字符串 —— 19 位雪花 id 经 ConvertFrom-Json 会丢精度，
# 拿 JSON 里的 buyerId 去比永远不等（那是断言写法错，不是实现错）。
$buyerIdDb = Sql 'duoduo_user' "SELECT id FROM t_user WHERE phone = '$phoneA';"
Check "买家 id 取自订单事实（请求体里没有 buyerId 可传）" ((Sql 'duoduo_fulfilment' "SELECT buyer_id FROM t_after_sale WHERE id = $asId1;") -eq "$buyerIdDb") "$(Sql 'duoduo_fulfilment' "SELECT buyer_id FROM t_after_sale WHERE id = $asId1;")/$buyerIdDb"
Check "中文诉求经接口读回无乱码" ($r.body.data.reason -eq '收到的猫粮包装破损，要求退款') $r.body.data.reason
Check "举证图读回一致" (@($r.body.data.images).Count -eq 1) ($r.body.data.images -join ',')

$r = Call 'Post' "$Gateway/api/aftersales" $sessA @{ orderNo = $on1; type = 'REFUND_ONLY'; reason = '再提一次' }
Check "③ 同单同类型重复立案返回 409（唯一索引兜底，不是 500）" ($r.status -eq 409) $r.status

$r = Call 'Post' "$Gateway/api/aftersales" $sessA @{ orderNo = $on1; type = 'RETURN_REFUND'; reason = '同单另一种类型：退货退款' }
Check "同单不同类型可以各自立案（唯一键是 order+type 而非 order）" ($r.status -eq 200) $r.status
$asId1t = IdOf $r
Check "不同类型单的类型文案为「退货退款」" ($r.body.data.typeLabel -eq '退货退款') $r.body.data.typeLabel

# ---------- 6. 详情可见范围 ----------
Write-Host ""
Write-Host "> 详情可见范围"
$r = Call 'Get' "$Gateway/api/aftersales/$asId1" $sessA $null $null
Check "买家本人可看详情（200）" ($r.status -eq 200) $r.status
$r = Call 'Get' "$Gateway/api/aftersales/$asId1" $sessS $null $null
Check "该单卖家可看详情（200）" ($r.status -eq 200) $r.status
$r = Call 'Get' "$Gateway/api/aftersales/$asId1" $sessOp $null $null
Check "运营可看详情（200）" ($r.status -eq 200) $r.status
$r = Call 'Get' "$Gateway/api/aftersales/$asId1" $sessB $null $null
Check "无关第三方看详情返回 403" ($r.status -eq 403) $r.status
Check "详情里不含裁定人（运营内部信息不扩散到用户端）" ($r.raw -notmatch 'arbitratedBy') "hasField=$($r.raw -match 'arbitratedBy')"

# ---------- 7. 卖家处理：拒绝 → 买家升级 → 运营裁定退款（验收④） ----------
Write-Host ""
Write-Host "> 闭环一：立案 → 卖家拒绝 → 买家申请仲裁 → 运营裁定支持退款"
$r = Call 'Post' "$Gateway/api/aftersales" $sessA @{ orderNo = $on2; type = 'REFUND_ONLY'; reason = '闭环一：要求退款' }
Check "为 O2 立案成功" ($r.status -eq 200) $r.status
$asId2 = IdOf $r

$r = Call 'Post' "$Gateway/api/aftersales/$asId2/reply" $sessB @{ action = 'REJECT'; reply = 'x' }
Check "非该单卖家处理返回 403" ($r.status -eq 403) $r.status
$r = Call 'Post' "$Gateway/api/aftersales/$asId2/reply" $sessS @{ action = 'MAYBE'; reply = 'x' }
Check "非法处理动作返回 400" ($r.status -eq 400) $r.status
$r = Call 'Post' "$Gateway/api/aftersales/$asId2/reply" $sessS @{ action = 'REJECT' }
Check "⑤-1 卖家拒绝但缺说明返回 422（流程要求，不是字段格式错）" ($r.status -eq 422) $r.status
$r = Call 'Post' "$Gateway/api/aftersales/$asId2/reply" $sessS @{ action = 'REJECT'; reply = '商品已拆封，影响二次销售' }
Check "卖家拒绝成功（200）" ($r.status -eq 200) $r.status
Check "状态转为 SELLER_REJECTED" ($r.body.data.status -eq 'SELLER_REJECTED') $r.body.data.status
Check "⑥ 卖家处理说明已留痕并可读回" ($r.body.data.sellerReply -eq '商品已拆封，影响二次销售') $r.body.data.sellerReply
$r = Call 'Post' "$Gateway/api/aftersales/$asId2/reply" $sessS @{ action = 'REJECT'; reply = '再拒一次' }
Check "已拒绝的单再次处理返回 422（状态机不允许）" ($r.status -eq 422) $r.status

$r = Call 'Post' "$Gateway/api/aftersales/$asId2/escalate" $sessB $null
Check "他人申请仲裁返回 403" ($r.status -eq 403) $r.status
$r = Call 'Post' "$Gateway/api/aftersales/$asId2/escalate" $sessA $null
Check "买家申请平台仲裁成功（200）" ($r.status -eq 200) $r.status
Check "状态转为 ARBITRATING" ($r.body.data.status -eq 'ARBITRATING') $r.body.data.status
$r = Call 'Post' "$Gateway/api/aftersales/$asId2/escalate" $sessA $null
Check "已是仲裁中的单再次申请返回 422" ($r.status -eq 422) $r.status

$r = Call 'Post' "$Gateway/api/admin/aftersales/$asId2/arbitrate" $sessOp @{ result = 'APPROVE_REFUND' }
Check "⑤-2 运营缺裁定说明返回 422" ($r.status -eq 422) $r.status
Check "⑤-2 拒绝文案说明「必须填写说明」" ($r.body.message -match '说明') $r.body.message
$r = Call 'Post' "$Gateway/api/admin/aftersales/$asId2/arbitrate" $sessOp @{ result = 'WHATEVER'; note = 'x' }
Check "非法裁定结论返回 400" ($r.status -eq 400) $r.status
$r = Call 'Post' "$Gateway/api/admin/aftersales/$asId2/arbitrate" $sessOp @{ result = 'APPROVE_REFUND'; note = '举证显示包装确已破损，支持退款' }
Check "运营裁定支持退款返回 200" ($r.status -eq 200) ($r.body | ConvertTo-Json -Compress)
Check "售后单状态转为 REFUNDED" ($r.body.data.status -eq 'REFUNDED') $r.body.data.status
Check "⑥ 裁定说明已留痕" ($r.body.data.arbitrateNote -eq '举证显示包装确已破损，支持退款') $r.body.data.arbitrateNote
Check "裁定结论记录为 APPROVE_REFUND" ($r.body.data.arbitrateResult -eq 'APPROVE_REFUND') $r.body.data.arbitrateResult
Check "退款金额由交易域回填且等于订单实付（$pay1 分）" ([long]$r.body.data.refundAmount -eq [long]$c2.order.payAmount) $r.body.data.refundAmount

$rd = Call 'Get' "$Gateway/api/orders/$on2" $sessA $null $null
Check "④ 交易侧订单已变 REFUNDED" ($rd.body.data.status -eq 'REFUNDED') $rd.body.data.status
$rs = Call 'Get' "$Gateway/api/orders/$on2/settlement" $sessA $null $null
Check "④ 交易侧托管单已转 REFUNDED" ($rs.body.data.status -eq 'REFUNDED') $rs.body.data.status
Check "④ 托管金额与订单实付一致（钱没退错数）" ([long]$rs.body.data.amount -eq [long]$c2.order.payAmount) $rs.body.data.amount

$r = Call 'Post' "$Gateway/api/admin/aftersales/$asId2/arbitrate" $sessOp @{ result = 'REJECT'; note = '改判试试' }
Check "已终结的售后单重复裁定返回 422" ($r.status -eq 422) $r.status

# ---------- 8. 闭环二：卖家同意即退款 ----------
Write-Host ""
Write-Host "> 闭环二：立案 → 卖家同意（直接退款）"
$r = Call 'Post' "$Gateway/api/aftersales" $sessA @{ orderNo = $on3; type = 'REFUND_ONLY'; reason = '闭环二：卖家同意' }
Check "为 O3 立案成功" ($r.status -eq 200) $r.status
$asId3 = IdOf $r
$r = Call 'Post' "$Gateway/api/aftersales/$asId3/reply" $sessS @{ action = 'AGREE' }
Check "卖家同意并退款成功（200）" ($r.status -eq 200) ($r.body | ConvertTo-Json -Compress)
Check "售后单状态转为 REFUNDED" ($r.body.data.status -eq 'REFUNDED') $r.body.data.status
Check "退款金额已回填（非空且 > 0）" ([long]$r.body.data.refundAmount -gt 0) $r.body.data.refundAmount
$rd = Call 'Get' "$Gateway/api/orders/$on3" $sessA $null $null
Check "O3 订单已变 REFUNDED" ($rd.body.data.status -eq 'REFUNDED') $rd.body.data.status
$rs = Call 'Get' "$Gateway/api/orders/$on3/settlement" $sessA $null $null
Check "O3 托管单已转 REFUNDED" ($rs.body.data.status -eq 'REFUNDED') $rs.body.data.status

# ---------- 9. 已完成订单（已放款）的裁定退款必须被拒（B-01 边界） ----------
Write-Host ""
Write-Host "> 闭环三：已完成订单立案可以，但系统退不了款（钱在卖家手里）"
$r = Call 'Post' "$Gateway/api/aftersales" $sessA @{ orderNo = $on4; type = 'REFUND_ONLY'; reason = '确认收货后发现是假货' }
Check "① 已完成订单可以立案（买家诉求该被受理）" ($r.status -eq 200) $r.status
$asId4 = IdOf $r
$r = Call 'Post' "$Gateway/api/admin/aftersales/$asId4/arbitrate" $sessOp @{ result = 'APPROVE_REFUND'; note = '证据充分，支持退款' }
Check "★ 已完成且已放款的订单：裁定退款被拒（422）" ($r.status -eq 422) $r.status
Check "★ 拒绝文案把「需走追偿 / 垫付」说清楚（B-01）" ($r.body.message -match '追偿|垫付') $r.body.message
$r = Call 'Get' "$Gateway/api/aftersales/$asId4" $sessOp $null $null
Check "★ 退款未执行时售后单状态保持不变（不落半个状态）" ($r.body.data.status -eq 'PENDING') $r.body.data.status
$rd = Call 'Get' "$Gateway/api/orders/$on4" $sessA $null $null
Check "★ 订单仍为 COMPLETED（钱没被退出去）" ($rd.body.data.status -eq 'COMPLETED') $rd.body.data.status

# ---------- 10. 运营驳回（验收⑤ 的另一半） ----------
Write-Host ""
Write-Host "> 运营驳回"
$r = Call 'Post' "$Gateway/api/admin/aftersales/$asId1/arbitrate" $sessOp @{ result = 'REJECT'; note = '破损属物流责任，已与承运方协商赔付' }
Check "运营驳回返回 200" ($r.status -eq 200) $r.status
Check "状态转为 CLOSED" ($r.body.data.status -eq 'CLOSED') $r.body.data.status
Check "驳回结论与说明均已留痕" ($r.body.data.arbitrateResult -eq 'REJECT' -and $r.body.data.arbitrateNote -match '物流责任') $r.body.data.arbitrateNote

# ---------- 11. 列表与过滤（验收⑥） ----------
Write-Host ""
Write-Host "> 列表与过滤"
$r = Call 'Get' "$Gateway/api/aftersales/mine?pageSize=50" $sessA $null $null
Check "买家自己的售后列表（200）" ($r.status -eq 200) $r.status
$mine = @($r.body.data.items | Where-Object { $_.orderNo -in @($on1, $on2, $on3, $on4) })
Check "⑥ 列表包含本次立案的全部售后单（4 笔订单 5 条）" ($mine.Count -eq 5) $mine.Count
$r = Call 'Get' "$Gateway/api/aftersales/mine?status=REFUNDED&pageSize=50" $sessA $null $null
Check "状态过滤生效：REFUNDED 只返回已退款的单" ($r.status -eq 200 -and @($r.body.data.items | Where-Object { $_.status -ne 'REFUNDED' }).Count -eq 0) $r.status
Check "REFUNDED 过滤里能看到闭环一与闭环二的两条" (@($r.body.data.items | Where-Object { $_.orderNo -in @($on2, $on3) }).Count -eq 2) (@($r.body.data.items | ForEach-Object { $_.orderNo }) -join ',')

$r = Call 'Get' "$Gateway/api/aftersales/seller?pageSize=50" $sessS $null $null
Check "卖家能看到归属自己的售后单（200）" ($r.status -eq 200) $r.status
Check "卖家列表里不含他人店铺的单" (@($r.body.data.items | Where-Object { $_.orderNo -in @($on1, $on2, $on3, $on4) }).Count -eq 5) (@($r.body.data.items | ForEach-Object { $_.orderNo }) -join ',')

$r = Call 'Get' "$Gateway/api/admin/aftersales?pageSize=50" $sessOp $null $null
Check "运营全平台列表（200）" ($r.status -eq 200) $r.status
Check "运营列表包含本次夹具单" (@($r.body.data.items | Where-Object { $_.orderNo -in @($on1, $on2, $on3, $on4) }).Count -eq 5) (@($r.body.data.items | ForEach-Object { $_.orderNo }) -join ',')
$r = Call 'Get' "$Gateway/api/admin/aftersales?status=CLOSED&pageSize=50" $sessOp $null $null
Check "运营按状态过滤生效（CLOSED）" ($r.status -eq 200 -and @($r.body.data.items | Where-Object { $_.status -ne 'CLOSED' }).Count -eq 0) $r.status

# ---------- 12. 补偿调度与清理 ----------
Write-Host ""
Write-Host "> 清理夹具与自证复原"
# 退款会真实回补库存。先手动跑一轮补偿调度把退货入库收口，
# 否则后台调度可能在「我们复原库存之后」再加一次，造成库存数字对不上的假失败。
$r = Call 'Post' "$Trade/api/orders/internal/sweep" $null $null $H_INTERNAL
Check "手动触发补偿调度成功（入库补偿收口）" ($r.status -eq 200) $r.status

if (-not $SkipDbCheck) {
    Sql 'duoduo_fulfilment' "DELETE FROM t_after_sale WHERE order_no IN ('$on1','$on2','$on3','$on4','$on5');" | Out-Null
    $left = Sql 'duoduo_fulfilment' "SELECT COUNT(*) FROM t_after_sale WHERE order_no IN ('$on1','$on2','$on3','$on4','$on5');"
    Check "售后单夹具已删除（库内复原）" ($left -eq '0') $left

    Sql 'duoduo_product' "UPDATE t_sku SET stock = $dbStockBefore, frozen_stock = 0 WHERE id = $SKU;" | Out-Null
    $nowStock = Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU;"
    Check "★ SKU $SKU 库存已复原为测试前的 $dbStockBefore" ("$nowStock" -eq "$dbStockBefore") $nowStock
    Check "★ 冻结量已清零" ((Sql 'duoduo_product' "SELECT COALESCE(frozen_stock,0) FROM t_sku WHERE id = $SKU;") -eq '0')

    Sql 'duoduo_user' "UPDATE t_user SET role = 'USER' WHERE phone = '$phoneOp';" | Out-Null
    Check "运营账号已降回 USER（库内复原）" ((Sql 'duoduo_user' "SELECT role FROM t_user WHERE phone = '$phoneOp';") -eq 'USER')
}

# ---------- 结果 ----------
Write-Host ""
Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
if ($fail -gt 0) {
    Write-Host "失败项："
    $failures | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
exit 0
