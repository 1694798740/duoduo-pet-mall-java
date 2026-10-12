# 物流轨迹（A2）端到端冒烟（Windows PowerShell 5.1）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\logistics-smoke.ps1
#
# 前置：容器全部 healthy（docker compose up -d），且已 mvn package + docker compose build 出最新镜像。
#
# 覆盖 docs/agent/07 的 A2 六条验收标准：
#   ① 发货后轨迹里有一条「已揽收」；
#   ② 追加节点后按时间正序返回；
#   ③ 重复上报不产生重复行（幂等靠唯一索引，不是「先查再插」）；
#   ④ 非当事人查询 403；
#   ⑤ 未登录查询 401；
#   ⑥ 响应不含收货人敏感字段（INV-9）。
#
# 另外覆盖（07 没写但真实存在、不测迟早出事的）：
#   · ★ 乱序入库（先推「派送中」再推「运输中」）仍必须按发生时间正序返回；
#   · ★ 批量「全有或全无」：批次里混一条非法节点码，整批 400 且**一条都不落**
#     （若边校验边写，调用方无从知道前面几条落了没有，只能整体重推 —— 而重推会被判成重复）；
#   · ★ 部分重复只插新的：2 条里 1 条重复 1 条新增 → inserted=1 skipped=1
#     （只回一个「成功」的话，重推与漏推在监控上看不出区别）；
#   · ★ 无时区写法与 Z 写法指向同一时刻（默认按 UTC 解释）→ 第二次被判重复，
#     若按"服务器本地时区"解释，东八区开发机与 UTC 容器会落成相差 8 小时的两条；
#   · 未发货订单查轨迹返回**空列表 + total=0**，而不是 404/500（「暂无轨迹」是正常状态）；
#   · 内部摄入接口的凭据边界（无凭据 / 错凭据 403）与参数校验（非法节点码、非法时间、
#     未来时间、空列表、超批量、订单不存在 404）；
#   · 卖家本人与运营都可查（当事人之外还有合理的角色）；
#   · 响应既不回 raw、也不回收货人任何字段；库内 raw 只存上报来源与原始报文。
#
# 设计原则（沿用前几套脚本的教训）：
#   1. 夹具从接口动态取（SKU 价格与库存不硬编码），归属夹具改库前先记录、结尾复原；
#   2. 断言用**业务键**（订单号），不拿 19 位雪花 id 做 JSON 比较（ConvertFrom-Json 会丢精度）；
#   3. 反向场景用状态码写清口径：参数/取值错 400、业务规则不允许 422、越权 403、
#      不存在 404、唯一键撞键 409；
#   4. 中文内容一律走接口读回比对，不用 docker exec mysql 的 GBK 控制台比中文；
#   5. 已付款订单会真实扣减库存，结尾显式复原，保证脚本可反复执行；
#   6. ★ 结尾有「断言总数守卫」：Check 若因参数写错而在参数绑定阶段抛异常，
#      它既不进 pass 也不进 fail —— 运行结果仍然是「0 失败」，
#      比一条断言失败危险得多（本脚本第一版就踩到：多行续行里的 -eq 被当成参数名）。
#      所以增删断言必须同步 $EXPECTED_CHECKS，否则守卫会主动报失败。
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    # 直连履约服务端口：内部摄入接口必须直连，走网关既无路由也会被按未登录拦成 401
    [string]$Fulfilment = 'http://127.0.0.1:8084',
    [string]$Password = 'duoduo123',
    [string]$InternalToken = 'duoduo-local-dev-internal-token',
    [int]$SKU = 2001,
    [switch]$SkipDbCheck
)

$ErrorActionPreference = 'Continue'
$pass = 0
$fail = 0
$failures = @()

# 断言总数守卫（设计原则 6）。默认路径 79 项；-SkipDbCheck 会少掉 7 项库内断言。
$EXPECTED_CHECKS = if ($SkipDbCheck) { 72 } else { 79 }

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
. (Join-Path $PSScriptRoot 'lib\platform-operator.ps1')
. (Join-Path $PSScriptRoot 'lib\seller-fixture.ps1')
# 平台运营会话：自营订单归平台（seller_id=0），只有 OPERATOR 能发货；
# 迁移前脚本用买家会话调旧的 /api/orders/{no}/ship（无归属校验），迁移后必须换身份。
$sessShip = (New-PlatformOperatorSession -Gateway $Gateway -Password $Password).session


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

# ISO-8601（UTC，带 Z）。轨迹接口只接受 ISO-8601，且不接受只有日期的写法。
function Iso([datetime]$dt) { return $dt.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss") + 'Z' }

function PlaceAndPay($session, $sku, $qty, $label) {
    Call 'Post' "$Gateway/api/cart/items" $session @{ skuId = $sku; quantity = $qty } | Out-Null
    $o = Call 'Post' "$Gateway/api/orders" $session ($recv + @{ idempotentKey = (NewKey) })
    if ($o.status -ne 200) { return @{ ok = $false; orderNo = $null; order = $null; step = "下单 $label" } }
    $order = @($o.body.data.orders)[0]
    $p = Call 'Post' "$Gateway/api/orders/$($order.orderNo)/pay" $session @{ channel = 'MOCK'; idempotentKey = (NewKey) }
    if ($p.status -ne 200) { return @{ ok = $false; orderNo = $order.orderNo; order = $order; step = "支付 $label" } }
    return @{ ok = $true; orderNo = $order.orderNo; order = $order; step = $null }
}

# 从原始响应文本里取轨迹节点（避免 ConvertFrom-Json 对长整数的任何隐式处理）。
# 返回 [{nodeCode, nodeLabel, nodeText, occurredAt}]，顺序即服务端返回顺序。
function TracksOf($r) {
    if (-not $r -or -not $r.body -or -not $r.body.data) { return @() }
    return @($r.body.data.tracks)
}

$H_INTERNAL = @{ 'X-Internal-Token' = 'internal' }
$H_BAD = @{ 'X-Internal-Token' = 'wrong-token-should-be-rejected' }
$phoneA = '135' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneB = '134' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneS = '133' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneOp = '132' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$recv = @{ receiverName = '小林'; receiverPhone = '13900000000'; receiverAddress = '上海市浦东新区张江路 1 号' }

Write-Host ""
Write-Host "=== 多多宠物商店 · 物流轨迹（A2）冒烟 @ $Gateway ==="
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
$regA = Register $phoneA '物流测试-买家A'
Check "买家 A 注册/登录成功" ($regA.result.status -eq 200) $regA.result.status
$sessA = $regA.session

$regB = Register $phoneB '物流测试-他人B'
Check "他人 B 注册/登录成功" ($regB.result.status -eq 200) $regB.result.status
$sessB = $regB.session

$regS = Register $phoneS '物流测试-卖家S'
Check "卖家 S 注册成功（初始 USER）" ($regS.result.status -eq 200) $regS.result.status
$sessS = $regS.session
$r = Call 'Post' "$Gateway/api/users/me/realname" $sessS @{ realName = '张老板'; idCard = '310101199001011234' }
Check "卖家 S 实名认证成功" ($r.status -eq 200) $r.status
$fxS = New-SellerFixture -Phone $phoneS -StoreName '物流测试-卖家S' -Gateway $Gateway
Check "（夹具）卖家 S 直建为 APPROVED 商家且角色升为 SELLER" ($fxS.roleSet) $fxS.role
$sessS = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$r = Call 'Post' "$Gateway/api/auth/login" $sessS @{ phone = $phoneS; password = $Password }
Check "卖家 S 重新登录成功（角色是 token 快照，必须重登）" ($r.status -eq 200) $r.status

$regOp = Register $phoneOp '物流测试-运营'
Check "运营账号注册成功（初始 USER）" ($regOp.result.status -eq 200) $regOp.result.status
if (-not $SkipDbCheck) {
    Sql 'duoduo_user' "UPDATE t_user SET role = 'OPERATOR' WHERE phone = '$phoneOp';" | Out-Null
    Check "库内角色已提权为 OPERATOR" ((Sql 'duoduo_user' "SELECT role FROM t_user WHERE phone = '$phoneOp';") -eq 'OPERATOR')
}
$sessOp = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$r = Call 'Post' "$Gateway/api/auth/login" $sessOp @{ phone = $phoneOp; password = $Password }
Check "运营账号重新登录成功" ($r.status -eq 200) $r.status

# 卖家 userId 从库内直取字符串，避开 19 位雪花经 JSON 解析丢精度
$sellerId = Sql 'duoduo_user' "SELECT id FROM t_user WHERE phone = '$phoneS';"
Check "（前置）取得卖家 S 的 userId" (-not [string]::IsNullOrEmpty($sellerId)) $sellerId

# ---------- 2. 权限与凭据边界 ----------
Write-Host ""
Write-Host "> 权限与凭据边界（未登录 401 / 内部凭据 403）"
$anon = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$r = Call 'Get' "$Gateway/api/logistics/orders/ANY-ORDER-NO/tracks" $anon $null $null
Check "⑤ 未登录查轨迹返回 401（网关拦截）" ($r.status -eq 401) $r.status

$r = Call 'Post' "$Fulfilment/api/logistics/internal/ANY/tracks" $null @{ source = 'COURIER'; nodes = @(@{ nodeCode = 'PICKED_UP'; occurredAt = (Iso (Get-Date)) }) } $null
Check "内部摄入接口无凭据返回 403" ($r.status -eq 403) $r.status
$r = Call 'Post' "$Fulfilment/api/logistics/internal/ANY/tracks" $null @{ source = 'COURIER'; nodes = @(@{ nodeCode = 'PICKED_UP'; occurredAt = (Iso (Get-Date)) }) } $H_BAD
Check "内部摄入接口错误凭据返回 403" ($r.status -eq 403) $r.status

# ---------- 3. 夹具前提与订单 ----------
Write-Host ""
Write-Host "> 夹具（SKU $SKU，本脚本需 3 件）"
$batch = Call 'Get' "$Gateway/api/products/internal/skus/batch?ids=$SKU" $null $null $H_INTERNAL
$skuInfo = @($batch.body.data)[0]
$P = [long]$skuInfo.price
$S = [int]$skuInfo.availableStock
Write-Host "  SKU $SKU 单价 $P 分 可售 $S"
if ($P -le 0 -or $S -lt 3) {
    Write-Host "夹具前提不满足，终止本次测试，不执行任何写操作。"
    Check "夹具前提：价格与库存均从接口正确读到且可售 ≥ 3" $false "P=$P S=$S"
    Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
    exit 1
}
Check "夹具前提：SKU $SKU 可售 ≥ 3" ($S -ge 3) $S
$dbStockBefore = [int](Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU;")
Check "（前置）库内库存与接口可售一致（冻结为 0）" ($dbStockBefore -eq $S) "db=$dbStockBefore api=$S"
Check "（前置）库内冻结量为 0" ((Sql 'duoduo_product' "SELECT COALESCE(frozen_stock,0) FROM t_sku WHERE id = $SKU;") -eq '0')

Write-Host ""
Write-Host "> 夹具订单：O1（卖家端发货，带物流信息）/ O2（内部接口发货，无物流信息）/ O3（已付款未发货）"
$c1 = PlaceAndPay $sessA $SKU 1 'O1'
$c2 = PlaceAndPay $sessA $SKU 1 'O2'
$c3 = PlaceAndPay $sessA $SKU 1 'O3'
Check "三笔夹具订单均下单支付成功" ($c1.ok -and $c2.ok -and $c3.ok) "$($c1.step) $($c2.step) $($c3.step)"
if (-not ($c1.ok -and $c2.ok -and $c3.ok)) {
    Write-Host "夹具订单创建失败，终止。"
    Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
    exit 1
}
$on1 = $c1.orderNo; $on2 = $c2.orderNo; $on3 = $c3.orderNo

# 归属夹具：把两笔待发货订单划给卖家 S（记录原值，结尾复原）。
# 订单与托管单必须一起改：两表的 seller_id 是同一事实的两份记录，只改一处会得到自相矛盾的夹具。
$origSellers = Sql 'duoduo_trade' "SELECT CONCAT(order_no,'=',seller_id) FROM t_order WHERE order_no IN ('$on1','$on2') ORDER BY order_no;"
if (-not $SkipDbCheck) {
    Sql 'duoduo_trade' "UPDATE t_order SET seller_id = $sellerId WHERE order_no IN ('$on1','$on2');" | Out-Null
    Sql 'duoduo_trade' "UPDATE t_settlement s JOIN t_order o ON o.id = s.order_id SET s.seller_id = $sellerId WHERE o.order_no IN ('$on1','$on2');" | Out-Null
    $bound = Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_order WHERE order_no IN ('$on1','$on2') AND seller_id = $sellerId;"
    Check "（夹具）两笔待发货订单已划给卖家 S" ($bound -eq '2') $bound
}

# ---------- 4. 未发货订单：没有轨迹是正常状态 ----------
Write-Host ""
Write-Host "> 未发货订单（O3）：轨迹为空，但不是错误"
$r = Call 'Get' "$Gateway/api/logistics/orders/$on3/tracks" $sessA $null $null
Check "未发货订单查轨迹返回 200（不是 404/500）" ($r.status -eq 200) $r.status
Check "未发货订单轨迹条数为 0" ($r.body.data.total -eq 0) $r.body.data.total
Check "未发货订单 tracks 是空数组（前端好渲染空态）" (@(TracksOf $r).Count -eq 0) (@(TracksOf $r).Count)

# ---------- 5. 发货即揽收（验收①） ----------
Write-Host ""
Write-Host "> 验收①：发货后轨迹里出现「已揽收」"
$r = Call 'Post' "$Gateway/api/seller/orders/$on1/ship" $sessS @{ logisticsCompany = '顺丰速运'; logisticsNo = 'SF1234567890' }
Check "卖家 S 通过商家端发货成功（带物流信息，200）" ($r.status -eq 200) ($r.body | ConvertTo-Json -Compress)
Check "订单物流公司已落库" ($r.body.data.logisticsCompany -eq '顺丰速运') $r.body.data.logisticsCompany
Check "订单物流单号已落库" ($r.body.data.logisticsNo -eq 'SF1234567890') $r.body.data.logisticsNo

# 揽收节点是「发货事务提交后」由交易域通知履约域写入的（AFTER_COMMIT），可能存在毫秒级延迟 —— 轮询几秒
$tracks1 = @()
for ($i = 0; $i -lt 20; $i++) {
    $r = Call 'Get' "$Gateway/api/logistics/orders/$on1/tracks" $sessA $null $null
    $tracks1 = @(TracksOf $r)
    if ($tracks1.Count -ge 1) { break }
    Start-Sleep -Milliseconds 500
}
Check "① 发货后轨迹里有 1 条节点（交易域 best-effort 补推生效）" ($tracks1.Count -eq 1) $tracks1.Count
Check "① 该节点是 PICKED_UP" ($tracks1[0].nodeCode -eq 'PICKED_UP') $tracks1[0].nodeCode
Check "① 服务端给出的节点文案是「已揽收」" ($tracks1[0].nodeLabel -eq '已揽收') $tracks1[0].nodeLabel
Check "① 节点描述带上了卖家填的物流公司与单号" ($tracks1[0].nodeText -match '顺丰速运' -and $tracks1[0].nodeText -match 'SF1234567890') $tracks1[0].nodeText
# ★ 这条断言测的是**服务端下发的序列化格式**，所以必须看原始报文，不能看解析后的对象。
#
# 原因（CI 实测踩到）：pwsh 7 的 `ConvertFrom-Json` 会把 ISO-8601 字符串**自动转成 [datetime]**，
# 而 Windows PowerShell 5.1 不转 —— 于是 `$tracks1[0].occurredAt -match '…Z$'` 里
# 左边根本不是字符串，是 DateTime，`-match` 按 InvariantCulture 把它变成
#     "10/10/2026 08:32:20"
# 再去比正则，必然失败。也就是说：**同一条断言在 Windows 上绿、在 CI 上红**，
# 而红的原因与"服务端有没有按 UTC 下发"毫无关系 —— 它测的是客户端解析后的类型。
# 改成对原始报文断言后，测的就是真正要保证的东西（线上协议），且两种 PS 版本行为一致。
Check "① 发生时间是 ISO-8601（UTC，以 Z 结尾）" `
    ($r.raw -match '"occurredAt"\s*:\s*"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z"') `
    "parsed=$($tracks1[0].occurredAt)"

# 内部接口发货（不带物流信息）—— 证明「节点文案缺失时由服务端兜底为节点名」
# 注意归属：$on2 是卖家 S 的订单，只能由 S 自己发货 —— 用平台运营会话会 403
# （旧的无归属校验端点不校验这个，迁移后身份必须对得上）。
$r = Call 'Post' "$Gateway/api/seller/orders/$on2/ship" $sessS @{}
Check "卖家 S 不带物流信息发货也成功（200）" ($r.status -eq 200) $r.status
$tracks2 = @()
for ($i = 0; $i -lt 20; $i++) {
    $r = Call 'Get' "$Gateway/api/logistics/orders/$on2/tracks" $sessA $null $null
    $tracks2 = @(TracksOf $r)
    if ($tracks2.Count -ge 1) { break }
    Start-Sleep -Milliseconds 500
}
Check "内部接口发货同样补推了揽收节点" ($tracks2.Count -eq 1 -and $tracks2[0].nodeCode -eq 'PICKED_UP') $tracks2.Count
Check "没填物流信息时节点描述由服务端兜底为节点名" ($tracks2[0].nodeText -eq '已揽收') $tracks2[0].nodeText

# ---------- 6. 追加节点 + 乱序入库仍正序返回（验收②） ----------
Write-Host ""
Write-Host "> 验收②：追加 3 条节点，且**乱序入库**仍按发生时间正序返回"
$t0 = (Get-Date).ToUniversalTime()
$tInTransit = Iso $t0.AddMinutes(1)
$tArrived = Iso $t0.AddMinutes(2)
$tOutFor = Iso $t0.AddMinutes(3)
# 故意乱序推送：先派送中、再运输中、最后到达分拨中心
$shuffled = @{ source = 'COURIER'; nodes = @(
    @{ nodeCode = 'OUT_FOR_DELIVERY'; nodeText = '【上海浦东网点】快递员正在派送'; occurredAt = $tOutFor; raw = '{"courier":"SF"}' },
    @{ nodeCode = 'IN_TRANSIT'; nodeText = '【上海分拨中心】已发出，下一站南京'; occurredAt = $tInTransit },
    @{ nodeCode = 'ARRIVED_HUB'; nodeText = '【南京分拨中心】已收入'; occurredAt = $tArrived }
) }
$r = Call 'Post' "$Fulfilment/api/logistics/internal/$on1/tracks" $null $shuffled $H_INTERNAL
Check "内部批量上报 3 条节点返回 200" ($r.status -eq 200) ($r.body | ConvertTo-Json -Compress)
Check "回执：收到 3、新增 3、跳过 0" ($r.body.data.received -eq 3 -and $r.body.data.inserted -eq 3 -and $r.body.data.skipped -eq 0) ($r.body | ConvertTo-Json -Compress)

$r = Call 'Get' "$Gateway/api/logistics/orders/$on1/tracks" $sessA $null $null
$tracks1 = @(TracksOf $r)
Check "轨迹现有 4 条（1 揽收 + 3 追加）" ($tracks1.Count -eq 4) $tracks1.Count
# 注意：表达式先落到变量再传，**不要**写成「Check "…" (…) -eq '…'」的多行续行 ——
# PowerShell 会把行首的 -eq 当成 Check 的参数名，于是该断言在参数绑定阶段抛异常被跳过，
# 而结果依旧是「0 失败」（运行日志里只有一段看不出所以然的 CategoryInfo）。
$codeSeq = ($tracks1 | ForEach-Object { $_.nodeCode }) -join ','
Check "② 返回顺序为发生时间正序（乱序入库也被排好）" ($codeSeq -eq 'PICKED_UP,IN_TRANSIT,ARRIVED_HUB,OUT_FOR_DELIVERY') $codeSeq
$times = @($tracks1 | ForEach-Object { [datetime]$_.occurredAt })
$asc = $true
for ($i = 1; $i -lt $times.Count; $i++) { if ($times[$i] -lt $times[$i - 1]) { $asc = $false } }
Check "② 时间戳本身也是严格递增（不只是恰好顺序对）" $asc ($times -join ' | ')
Check "中文节点描述经接口读回无乱码" ($tracks1[1].nodeText -eq '【上海分拨中心】已发出，下一站南京') $tracks1[1].nodeText

# ---------- 7. 幂等（验收③） ----------
Write-Host ""
Write-Host "> 验收③：重复上报不产生重复行"
$r = Call 'Post' "$Fulfilment/api/logistics/internal/$on1/tracks" $null $shuffled $H_INTERNAL
Check "③ 原样重推同一批：返回 200（重推是正常业务，不是错误）" ($r.status -eq 200) $r.status
Check "③ 回执 inserted=0（一条都没新增）" ($r.body.data.inserted -eq 0) ($r.body | ConvertTo-Json -Compress)
Check "③ 回执 skipped=3（三条全被判重复）" ($r.body.data.skipped -eq 3) ($r.body | ConvertTo-Json -Compress)

$r = Call 'Get' "$Gateway/api/logistics/orders/$on1/tracks" $sessA $null $null
Check "③ 轨迹条数仍是 4（没有出现重复行）" (@(TracksOf $r).Count -eq 4) (@(TracksOf $r).Count)

# 部分重复：其中 2 条重复 + 1 条新增 → inserted=1 / skipped=2
$partial = @{ source = 'COURIER'; nodes = @(
    @{ nodeCode = 'IN_TRANSIT'; occurredAt = $tInTransit },
    @{ nodeCode = 'ARRIVED_HUB'; occurredAt = $tArrived },
    @{ nodeCode = 'SIGNED'; nodeText = '【已签收】本人签收'; occurredAt = (Iso $t0.AddMinutes(10)) }
) }
$r = Call 'Post' "$Fulfilment/api/logistics/internal/$on1/tracks" $null $partial $H_INTERNAL
Check "部分重复：inserted=1 / skipped=2（重推与漏推可区分）" ($r.body.data.inserted -eq 1 -and $r.body.data.skipped -eq 2) ($r.body | ConvertTo-Json -Compress)
$r = Call 'Get' "$Gateway/api/logistics/orders/$on1/tracks" $sessA $null $null
Check "轨迹条数变为 5（只多了新的那一条）" (@(TracksOf $r).Count -eq 5) (@(TracksOf $r).Count)

# 无时区写法与 Z 写法必须指向同一时刻（默认按 UTC 解释）→ 第二次被判重复
$bare = $t0.AddMinutes(20)
$r = Call 'Post' "$Fulfilment/api/logistics/internal/$on1/tracks" $null @{ source = 'COURIER'; nodes = @(@{ nodeCode = 'EXCEPTION'; nodeText = '无时区写法首次'; occurredAt = $bare.ToString("yyyy-MM-ddTHH:mm:ss") }) } $H_INTERNAL
Check "无时区写法的节点被接受（首次 inserted=1）" ($r.status -eq 200 -and $r.body.data.inserted -eq 1) ($r.body | ConvertTo-Json -Compress)
$r = Call 'Post' "$Fulfilment/api/logistics/internal/$on1/tracks" $null @{ source = 'COURIER'; nodes = @(@{ nodeCode = 'EXCEPTION'; nodeText = '同一时刻换成 Z 写法'; occurredAt = (Iso $bare) }) } $H_INTERNAL
Check "★ 同一时刻的 Z 写法被判为重复（无时区按 UTC 解释，不是本地时区）" ($r.body.data.skipped -eq 1 -and $r.body.data.inserted -eq 0) ($r.body | ConvertTo-Json -Compress)

# ---------- 8. 批量「全有或全无」与参数校验 ----------
Write-Host ""
Write-Host "> 批量语义与参数校验"
$before = Sql 'duoduo_fulfilment' "SELECT COUNT(*) FROM t_logistics_track WHERE order_no = '$on1';"
$mixed = @{ source = 'COURIER'; nodes = @(
    @{ nodeCode = 'IN_TRANSIT'; occurredAt = (Iso $t0.AddMinutes(31)) },
    @{ nodeCode = 'NOT_A_NODE'; occurredAt = (Iso $t0.AddMinutes(32)) }
) }
$r = Call 'Post' "$Fulfilment/api/logistics/internal/$on1/tracks" $null $mixed $H_INTERNAL
Check "批次内含非法节点码返回 400" ($r.status -eq 400) $r.status
Check "拒绝文案点明是第几条、什么码（可定位）" ($r.body.message -match '第 2 条' -and $r.body.message -match 'NOT_A_NODE') $r.body.message
$after = Sql 'duoduo_fulfilment' "SELECT COUNT(*) FROM t_logistics_track WHERE order_no = '$on1';"
Check "★ 非法批次整批未落库（全有或全无，不是写一半）" ("$before" -eq "$after") "before=$before after=$after"

$r = Call 'Post' "$Fulfilment/api/logistics/internal/$on1/tracks" $null @{ source = 'COURIER'; nodes = @(@{ nodeCode = 'PICKED_UP'; occurredAt = '2026/09/20 10:30' }) } $H_INTERNAL
Check "非法时间格式（本地化写法）返回 400" ($r.status -eq 400) $r.status
$r = Call 'Post' "$Fulfilment/api/logistics/internal/$on1/tracks" $null @{ source = 'COURIER'; nodes = @(@{ nodeCode = 'PICKED_UP'; occurredAt = '2026-09-20' }) } $H_INTERNAL
Check "只有日期的写法返回 400（物流时间要精确到秒，否则同天节点会互撞）" ($r.status -eq 400) $r.status
$r = Call 'Post' "$Fulfilment/api/logistics/internal/$on1/tracks" $null @{ source = 'COURIER'; nodes = @(@{ nodeCode = 'PICKED_UP'; occurredAt = (Iso ((Get-Date).AddHours(5))) }) } $H_INTERNAL
Check "发生时间超前过多返回 400（推送方时钟或字段有误）" ($r.status -eq 400) $r.status
$r = Call 'Post' "$Fulfilment/api/logistics/internal/$on1/tracks" $null @{ source = 'COURIER'; nodes = @() } $H_INTERNAL
Check "空节点列表返回 400" ($r.status -eq 400) $r.status
$r = Call 'Post' "$Fulfilment/api/logistics/internal/$on1/tracks" $null @{ source = 'COURIER' } $H_INTERNAL
Check "缺 nodes 字段返回 400" ($r.status -eq 400) $r.status

$many = @()
for ($i = 0; $i -lt 51; $i++) { $many += @{ nodeCode = 'IN_TRANSIT'; occurredAt = (Iso $t0.AddSeconds($i)) } }
$r = Call 'Post' "$Fulfilment/api/logistics/internal/$on1/tracks" $null @{ source = 'COURIER'; nodes = $many } $H_INTERNAL
Check "单批超过 50 条返回 400（防一个坏批次刷满时间线）" ($r.status -eq 400) $r.status
Check "超量文案说明了上限" ($r.body.message -match '50') $r.body.message

$r = Call 'Post' "$Fulfilment/api/logistics/internal/NOT-EXIST-ORDER-NO/tracks" $null @{ source = 'COURIER'; nodes = @(@{ nodeCode = 'PICKED_UP'; occurredAt = (Iso $t0) }) } $H_INTERNAL
Check "给不存在的订单上报轨迹返回 404（不放孤儿轨迹进库）" ($r.status -eq 404) $r.status

# ---------- 9. 可见范围（验收④）与敏感字段（验收⑥） ----------
Write-Host ""
Write-Host "> 验收④⑤⑥：可见范围与敏感字段"
$r = Call 'Get' "$Gateway/api/logistics/orders/$on1/tracks" $sessA $null $null
Check "④ 买家本人可查（200）" ($r.status -eq 200) $r.status
$rA = $r
$r = Call 'Get' "$Gateway/api/logistics/orders/$on1/tracks" $sessS $null $null
Check "该单卖家可查（200）" ($r.status -eq 200) $r.status
$r = Call 'Get' "$Gateway/api/logistics/orders/$on1/tracks" $sessOp $null $null
Check "运营可查（200）" ($r.status -eq 200) $r.status
$r = Call 'Get' "$Gateway/api/logistics/orders/$on1/tracks" $sessB $null $null
Check "④ 非当事人（无关第三方）查询返回 403" ($r.status -eq 403) $r.status
Check "④ 拒绝文案说明了原因" ($r.body.message -match '相关') $r.body.message
$r = Call 'Get' "$Gateway/api/logistics/orders/NOT-EXIST-ORDER-NO/tracks" $sessA $null $null
Check "查询不存在订单的轨迹返回 404" ($r.status -eq 404) $r.status

Check "⑥ 响应里不含收货人姓名" ($rA.raw -notmatch '小林') "hit=$($rA.raw -match '小林')"
Check "⑥ 响应里不含 receiver 任何字段（收货人/电话/地址）" ($rA.raw -notmatch 'receiver') "hit=$($rA.raw -match 'receiver')"
Check "⑥ 响应里不含手机号" ($rA.raw -notmatch '13900000000') "hit=$($rA.raw -match '13900000000')"
Check "⑥ 响应不回上报方原始报文 raw" ($rA.raw -notmatch '"raw"') "hit=$($rA.raw -match '\"raw\"')"
$r = Call 'Get' "$Gateway/api/logistics/orders/$on2/tracks" $sessB $null $null
Check "④ 他人对另一笔订单同样 403（不是只挡了一笔）" ($r.status -eq 403) $r.status

# ---------- 10. 库内一致性与留档 ----------
Write-Host ""
Write-Host "> 库内一致性与留档"
$dbCount = Sql 'duoduo_fulfilment' "SELECT COUNT(*) FROM t_logistics_track WHERE order_no = '$on1';"
Check "库内条数与接口返回的 total 一致" ("$dbCount" -eq "$($rA.body.data.total)") "db=$dbCount api=$($rA.body.data.total)"
Check "库内节点码全部落在白名单内" ((Sql 'duoduo_fulfilment' "SELECT COUNT(*) FROM t_logistics_track WHERE node_code NOT IN ('PICKED_UP','IN_TRANSIT','ARRIVED_HUB','OUT_FOR_DELIVERY','SIGNED','REJECTED','EXCEPTION');") -eq '0')
$dbRaw = Sql 'duoduo_fulfilment' "SELECT raw FROM t_logistics_track WHERE order_no = '$on1' AND node_code = 'OUT_FOR_DELIVERY' LIMIT 1;"
Check "库内 raw 留了上报来源（COURIER）便于回溯" ($dbRaw -match 'COURIER') $dbRaw
Check "库内 raw 留了推送方原始字段" ($dbRaw -match 'courier') $dbRaw
Check "内部摄入的节点 order_id 由订单号派生（与评价/售后同口径，非 0）" ((Sql 'duoduo_fulfilment' "SELECT COUNT(*) FROM t_logistics_track WHERE order_no = '$on1' AND order_id > 0;") -eq "$dbCount")

# ---------- 11. 清理与复原 ----------
Write-Host ""
Write-Host "> 清理夹具与自证复原"
if (-not $SkipDbCheck) {
    Sql 'duoduo_fulfilment' "DELETE FROM t_logistics_track WHERE order_no IN ('$on1','$on2','$on3');" | Out-Null
    $left = Sql 'duoduo_fulfilment' "SELECT COUNT(*) FROM t_logistics_track WHERE order_no IN ('$on1','$on2','$on3');"
    Check "轨迹夹具已删除（库内复原）" ($left -eq '0') $left

    Sql 'duoduo_product' "UPDATE t_sku SET stock = $dbStockBefore, frozen_stock = 0 WHERE id = $SKU;" | Out-Null
    $nowStock = Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU;"
    Check "★ SKU $SKU 库存已复原为测试前的 $dbStockBefore" ("$nowStock" -eq "$dbStockBefore") $nowStock
    Check "★ 冻结量已清零" ((Sql 'duoduo_product' "SELECT COALESCE(frozen_stock,0) FROM t_sku WHERE id = $SKU;") -eq '0')

    # 归属复原：按记录的原始值逐条写回
    foreach ($pair in @($origSellers -split "`n")) {
        if ($pair -match '^(.+?)=(\d+)$') {
            $on = $Matches[1]; $sid = $Matches[2]
            Sql 'duoduo_trade' "UPDATE t_order SET seller_id = $sid WHERE order_no = '$on';" | Out-Null
            Sql 'duoduo_trade' "UPDATE t_settlement s JOIN t_order o ON o.id = s.order_id SET s.seller_id = $sid WHERE o.order_no = '$on';" | Out-Null
        }
    }
    Check "★ 订单归属已复原为测试前记录值" ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_order WHERE order_no IN ('$on1','$on2') AND seller_id = $sellerId;") -eq '0')

    Sql 'duoduo_user' "UPDATE t_user SET role = 'USER' WHERE phone = '$phoneOp';" | Out-Null
    Check "运营账号已降回 USER（库内复原）" ((Sql 'duoduo_user' "SELECT role FROM t_user WHERE phone = '$phoneOp';") -eq 'USER')
}

# ---------- 结果 ----------
Write-Host ""
# 断言总数守卫：Check 抛异常被跳过时 pass/fail 都不加，只有这个计数能发现。
$ran = $pass + $fail
if ($ran -ne $EXPECTED_CHECKS) {
    Write-Host "  [GUARD-FAIL] 断言总数守卫：应执行 $EXPECTED_CHECKS 项，实际执行 $ran 项 —— 有 Check 被静默跳过（多半是调用写法/参数写错）"
    $fail++
    $failures += "断言总数守卫（实际执行 $ran 项 / 期望 $EXPECTED_CHECKS 项）"
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
