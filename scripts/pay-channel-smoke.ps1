# 交易服务 · 沙箱支付渠道（预下单 / 扫码 / 回调验签 / 幂等入账 / 过期作废）端到端冒烟（Windows PowerShell 5.1）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\pay-channel-smoke.ps1
#
# 前置：容器全部 healthy，且已 mvn package + docker compose build 出最新镜像。
#
# 设计原则（沿用既有脚本的教训）：
#   1. 夹具从接口动态取，不硬编码价格 / 库存；
#   2. 攻击面测试（篡改签名 / 重放 / 金额篡改 / 头体时间戳拼接）必须先于正常路径跑，
#      断言「全部被拒且状态未变」，再走正常支付 —— 顺序反过来就测不出「被拒后是否留下脏状态」；
#   3. 凡改库必先记录原值，结尾自证「库内数据已复原」；成功支付会真实扣库存，结尾显式复原；
#   4. 只操作本脚本自己创建的订单与支付单。
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    # 直连交易服务：收银台页面与内部调度入口（经网关要么 401 要么路径不通）
    [string]$Trade = 'http://127.0.0.1:8083',
    [string]$Password = 'duoduo123',
    [string]$InternalToken = 'duoduo-local-dev-internal-token',
    # 必须与交易服务配置 duoduo.pay.sandbox.callback-secret 一致
    [string]$CallbackSecret = 'duoduo-sandbox-pay-secret',
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
        $code = 0; $body = $null
        try { $code = [int]$_.Exception.Response.StatusCode.value__ } catch { }
        try { if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $body = $_.ErrorDetails.Message | ConvertFrom-Json } } catch { }
        if ($null -eq $body) {
            try {
                # 跨 PS 版本：PS5.1 用 GetResponseStream，pwsh 7（CI Linux）用 Content.ReadAsStringAsync
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
        return @{ status = $code; body = $body; raw = $null }
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
$H_INTERNAL = @{ 'X-Internal-Token' = $InternalToken }

# 与 SandboxPaymentChannel 完全相同的签名规则：
#   canonical = payNo + "|" + channelTradeNo + "|" + amount + "|" + status + "|" + timestamp
#   signature = HMAC-SHA256(secret, canonical) 的十六进制小写
function Sign([string]$payNo, [string]$tradeNo, [long]$amount, [string]$status, [string]$timestamp) {
    $canonical = "$payNo|$tradeNo|$amount|$status|$timestamp"
    $hmac = New-Object System.Security.Cryptography.HMACSHA256
    $hmac.Key = [Text.Encoding]::UTF8.GetBytes($CallbackSecret)
    $hash = $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical))
    return ([BitConverter]::ToString($hash) -replace '-', '').ToLower()
}

# 构造一条完整签名回调（headers + 原始报文体），供 Invoke-WebRequest 直接发送
function BuildCallback([string]$payNo, [string]$tradeNo, [long]$amount, [long]$tsOffsetSeconds) {
    $ts = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() + $tsOffsetSeconds
    $tss = "$ts"
    $status = 'SUCCESS'
    $sig = Sign $payNo $tradeNo $amount $status $tss
    $body = @{ payNo = $payNo; channelTradeNo = $tradeNo; amount = $amount; status = $status; timestamp = $tss } | ConvertTo-Json -Compress
    return @{ headers = @{ 'X-Pay-Timestamp' = $tss; 'X-Pay-Signature' = $sig }; body = $body }
}

function SendCallback([string]$channel, $cb) {
    return Call 'Post' "$Gateway/api/payments/callback/$channel" $null $cb.body $cb.headers
}

# 直接用 Invoke-WebRequest 发原始字符串体（绕过 Call 的 ConvertTo-Json，签名是对原始字节算的）
function PostRaw([string]$uri, [string]$rawBody, [hashtable]$headers) {
    try {
        $r = Invoke-WebRequest -Uri $uri -Method Post -Body $rawBody -ContentType 'application/json' -Headers $headers -UseBasicParsing -TimeoutSec 25
        return @{ status = [int]$r.StatusCode; body = ($r.Content | ConvertFrom-Json) }
    } catch {
        $code = 0; $body = $null
        try { $code = [int]$_.Exception.Response.StatusCode.value__ } catch { }
        try { if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $body = $_.ErrorDetails.Message | ConvertFrom-Json } } catch { }
        return @{ status = $code; body = $body }
    }
}

$phoneA = '137' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneB = '136' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$SKU_A = 2001
$addr = '上海市浦东新区张江路 1 号'
$recv = @{ receiverName = '小沙'; receiverPhone = '13700000000'; receiverAddress = $addr }

Write-Host ""
Write-Host "=== 多多宠物商店 · 沙箱支付渠道冒烟 @ $Gateway ==="
Write-Host "用户 A = $phoneA ｜ 用户 B = $phoneB"
Write-Host ""

# ---------- 0. 网关就绪 ----------
$ready = $false
for ($i = 0; $i -lt 40; $i++) {
    try {
        $h = Invoke-WebRequest -Uri "$Gateway/actuator/health" -UseBasicParsing -TimeoutSec 5
        if ($h.StatusCode -eq 200) { $ready = $true; break }
    } catch { Start-Sleep -Seconds 1 }
}
Check "网关 /actuator/health 就绪" $ready
if (-not $ready) { Write-Host "网关未就绪，终止"; exit 1 }

# ---------- 1. 夹具与原值记录 ----------
Write-Host "> 夹具准备"
$uA = Register $phoneA '沙箱买家A'
$uB = Register $phoneB '沙箱买家B'
Check "用户 A 注册/登录" ($uA.result.status -eq 200) $uA.result.status
Check "用户 B 注册/登录" ($uB.result.status -eq 200) $uB.result.status
$sessA = $uA.session
$sessB = $uB.session

$batch = Call 'Get' "$Gateway/api/products/internal/skus/batch?ids=$SKU_A" $null $null @{ 'X-Internal-Token' = 'duoduo-local-dev-internal-token' }
$sku = @($batch.body.data)[0]
$P1 = [long]$sku.price
$S1 = [int]$sku.availableStock
Check "夹具：SKU $SKU_A 单价 $P1 分、可售 $S1" ($S1 -ge 5) $S1

$origStock = [int](Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU_A;")
$origFrozen = [int](Sql 'duoduo_product' "SELECT COALESCE(frozen_stock,0) FROM t_sku WHERE id = $SKU_A;")
$testStart = (Get-Date).ToUniversalTime().ToString('yyyy-MM-dd HH:mm:ss')

# ---------- 2. 下单 + 沙箱预下单 ----------
Write-Host ""
Write-Host "> 下单 + 沙箱预下单（PENDING + 二维码）"
Call 'Post' "$Gateway/api/cart/items" $sessA @{ skuId = $SKU_A; quantity = 2 } | Out-Null
$o1 = Call 'Post' "$Gateway/api/orders" $sessA ($recv + @{ idempotentKey = (NewKey) })
Check "下单成功" ($o1.status -eq 200) $o1.status
$orderNo = @($o1.body.data.orders)[0].orderNo
$payAmount = [long]@($o1.body.data.orders)[0].payAmount

$p1 = Call 'Post' "$Gateway/api/orders/$orderNo/pay" $sessA @{ channel = 'SANDBOX'; idempotentKey = (NewKey) }
Check "沙箱预下单返回 200" ($p1.status -eq 200) $p1.status
$pay = $p1.body.data
Check "支付单状态为 PENDING（等回调，不是预下单即成功）" ($pay.payStatus -eq 'PENDING') $pay.payStatus
Check "返回二维码内容（收银台地址）" ($pay.codeUrl -match '^https?://.+/sandbox/pay/') $pay.codeUrl
Check "二维码指向网关入口（对外可达，而非服务直连 8083）" ($pay.codeUrl.StartsWith("$Gateway/sandbox/pay/")) $pay.codeUrl
Check "返回过期时间（ISO-8601）" ($pay.expireAt -match '^\d{4}-\d{2}-\d{2}T') $pay.expireAt
$payNo = $pay.payNo
Check "订单仍为待付款" ($pay.orderStatus -eq 'PENDING_PAYMENT') $pay.orderStatus
Check "库内支付单为 PENDING 且落了二维码（与接口返回一致）" ((Sql 'duoduo_trade' "SELECT CONCAT(status,'|',COALESCE(code_url,'NULL')) FROM t_payment WHERE pay_no='$payNo';") -eq "PENDING|$($pay.codeUrl)")
Check "预下单未动库存（仍冻结中，未扣减）" ((Sql 'duoduo_product' "SELECT CONCAT(stock,',',frozen_stock) FROM t_sku WHERE id=$SKU_A;") -eq "$origStock,$($origFrozen + 2)")

# 未知渠道预下单被拒（不回退到默认渠道）
$pBad = Call 'Post' "$Gateway/api/orders/$orderNo/pay" $sessA @{ channel = 'WECHAT'; idempotentKey = (NewKey) }
Check "未知渠道预下单被拒（422）" ($pBad.status -eq 422) $pBad.status

# ---------- 3. 收银台页面（扫码设备直接打开，无登录态） ----------
Write-Host ""
Write-Host "> 沙箱收银台页面"
# 收银台地址直接用二维码里返回的（端到端：扫码设备看到什么，这里就访问什么；
# 二维码应指向网关 8080，任何设备可达 —— 若指向服务直连 8083 会被下面的断言抓住）
$cashier = $null
try { $cashier = Invoke-WebRequest -Uri $pay.codeUrl -UseBasicParsing -TimeoutSec 15 } catch { }
Check "收银台页面可访问（200）" ($cashier -and $cashier.StatusCode -eq 200) $(if($cashier){$cashier.StatusCode}else{'无响应'})
if ($cashier) {
    $html = $cashier.Content
    Check "页面含支付单号" ($html -match $payNo) 'payNo'
    Check "页面含金额（元）" ($html -match [regex]::Escape(([math]::Round($payAmount / 100.0, 2)).ToString())) 'amount'
    Check "页面含模拟支付按钮" ($html -match '模拟扫码支付') 'button'
}
# 不存在的支付单：PS 5.1 里非 2xx 会抛异常，状态码必须从异常的 Response 上取
$code404 = 0
try {
    Invoke-WebRequest -Uri "$Gateway/sandbox/pay/PAYNOTEXIST999" -UseBasicParsing -TimeoutSec 15 | Out-Null
    $code404 = 200
} catch {
    try { $code404 = [int]$_.Exception.Response.StatusCode.value__ } catch { $code404 = 0 }
}
Check "不存在的支付单返回 404 页面" ($code404 -eq 404) $code404

# ---------- 4. 回调攻击面（先攻击，再正常支付） ----------
Write-Host ""
Write-Host "> 回调攻击面（全部必须被拒，且不留脏状态）"
$tradeNo = "SANDBOX-$payNo"

# 4.1 无签名头
$r = PostRaw "$Gateway/api/payments/callback/sandbox" "{}" @{}
Check "缺少签名头被拒（401）" ($r.status -eq 401) $r.status

# 4.2 篡改签名（头体都合法，仅签名换成垃圾）
$cb = BuildCallback $payNo $tradeNo $payAmount 0
$badSig = @{ 'X-Pay-Timestamp' = $cb.headers['X-Pay-Timestamp']; 'X-Pay-Signature' = 'deadbeef' * 8 }
$r = PostRaw "$Gateway/api/payments/callback/sandbox" $cb.body $badSig
Check "篡改签名被拒（401）" ($r.status -eq 401) $r.status

# 4.3 重放：时间戳超出 300 秒窗口（签名本身按那个时间戳算是合法的）
$cbStale = BuildCallback $payNo $tradeNo $payAmount -3600
$r = PostRaw "$Gateway/api/payments/callback/sandbox" $cbStale.body $cbStale.headers
Check "过期时间戳（疑似重放）被拒（401）" ($r.status -eq 401) $r.status

# 4.4 头体时间戳拼接：头用当前时间（并按它签名），报文体里放另一个时间
$tsNow = "$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"
$sigSplice = Sign $payNo $tradeNo $payAmount 'SUCCESS' $tsNow
$spliceBody = @{ payNo = $payNo; channelTradeNo = $tradeNo; amount = $payAmount; status = 'SUCCESS'; timestamp = "$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds() - 120)" } | ConvertTo-Json -Compress
$r = PostRaw "$Gateway/api/payments/callback/sandbox" $spliceBody @{ 'X-Pay-Timestamp' = $tsNow; 'X-Pay-Signature' = $sigSplice }
Check "头体时间戳不一致（拼接攻击）被拒（401）" ($r.status -eq 401) $r.status

# 4.5 金额篡改：签名按篡改后的金额算（签名合法），但与支付单不符
$cbAmt = BuildCallback $payNo $tradeNo ($payAmount + 1) 0
$r = PostRaw "$Gateway/api/payments/callback/sandbox" $cbAmt.body $cbAmt.headers
Check "金额篡改（签名合法但金额+1）被拒（422）" ($r.status -eq 422) $r.status

# 4.6 未知渠道
$cbUnknown = BuildCallback $payNo $tradeNo $payAmount 0
$r = PostRaw "$Gateway/api/payments/callback/wechat" $cbUnknown.body $cbUnknown.headers
Check "未知渠道回调被拒（422）" ($r.status -eq 422) $r.status

# 4.7 MOCK 渠道不接受回调
$r = PostRaw "$Gateway/api/payments/callback/mock" $cbUnknown.body $cbUnknown.headers
Check "即时渠道（MOCK）回调被拒（422）" ($r.status -eq 422) $r.status

# 4.8 不存在的支付单（签名合法）
$cbGhost = BuildCallback 'PAYNOTEXIST999' 'GHOST-1' 100 0
$r = PostRaw "$Gateway/api/payments/callback/sandbox" $cbGhost.body $cbGhost.headers
Check "不存在的支付单回调返回 404" ($r.status -eq 404) $r.status

# 4.9 攻击面跑完：状态必须未变
Check "全部攻击被拒后支付单仍为 PENDING" ((Sql 'duoduo_trade' "SELECT status FROM t_payment WHERE pay_no='$payNo';") -eq 'PENDING')
Check "全部攻击被拒后订单仍为待付款" ((Sql 'duoduo_trade' "SELECT status FROM t_order WHERE order_no='$orderNo';") -eq 'PENDING_PAYMENT')

# ---------- 5. 沙箱收银台支付（正常路径：走同一套验签与入账） ----------
Write-Host ""
Write-Host "> 沙箱收银台支付（正常路径）"
$sp = Call 'Post' "$Gateway/api/payments/sandbox/$payNo" $null $null $null
Check "沙箱支付返回 200" ($sp.status -eq 200) $sp.status
Check "支付单状态变为 SUCCESS" ($sp.body.data.payStatus -eq 'SUCCESS') $sp.body.data.payStatus
Check "订单状态变为已付款" ($sp.body.data.orderStatus -eq 'PAID') $sp.body.data.orderStatus
Check "库内流水号为渠道返回值（SANDBOX- 前缀）" ((Sql 'duoduo_trade' "SELECT channel_trade_no FROM t_payment WHERE pay_no='$payNo';") -eq $tradeNo)
Check "库存已真实扣减（stock-2 且冻结归零）" ((Sql 'duoduo_product' "SELECT CONCAT(stock,',',frozen_stock) FROM t_sku WHERE id=$SKU_A;") -eq "$($origStock - 2),$origFrozen")
Check "冻结台账置为 DEDUCTED" ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_stock_freeze WHERE order_id=(SELECT id FROM t_order WHERE order_no='$orderNo') AND status='DEDUCTED';") -eq '1')
Check "资金进平台托管（CUSTODY）" ((Sql 'duoduo_trade' "SELECT status FROM t_settlement WHERE order_id=(SELECT id FROM t_order WHERE order_no='$orderNo');") -eq 'CUSTODY')

# ---------- 6. 幂等：重复支付与重复回调 ----------
Write-Host ""
Write-Host "> 幂等（重复支付 / 重复回调只入账一次）"
$sp2 = Call 'Post' "$Gateway/api/payments/sandbox/$payNo" $null $null $null
Check "重复沙箱支付返回 200（按重放处理）" ($sp2.status -eq 200) $sp2.status
Check "重复沙箱支付不改变结果（SUCCESS）" ($sp2.body.data.payStatus -eq 'SUCCESS') $sp2.body.data.payStatus
Check "重复沙箱支付不二次扣库存" ((Sql 'duoduo_product' "SELECT CONCAT(stock,',',frozen_stock) FROM t_sku WHERE id=$SKU_A;") -eq "$($origStock - 2),$origFrozen")

$cbOk = BuildCallback $payNo $tradeNo $payAmount 0
$r = PostRaw "$Gateway/api/payments/callback/sandbox" $cbOk.body $cbOk.headers
Check "支付成功后的重复渠道回调返回 200（重放）" ($r.status -eq 200) $r.status
Check "重复回调不产生第二笔入账" ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_payment WHERE pay_no='$payNo' AND status='SUCCESS';") -eq '1')
Check "重复回调不建第二条托管" ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_settlement WHERE order_id=(SELECT id FROM t_order WHERE order_no='$orderNo');") -eq '1')

# ---------- 7. 支付单查询与越权 ----------
Write-Host ""
Write-Host "> 支付单查询"
$qa = Call 'Get' "$Gateway/api/payments/$payNo" $sessA $null
Check "买家查询自己的支付单（200）" ($qa.status -eq 200) $qa.status
Check "查询结果显示 SUCCESS" ($qa.body.data.payStatus -eq 'SUCCESS') $qa.body.data.payStatus
$qb = Call 'Get' "$Gateway/api/payments/$payNo" $sessB $null
Check "他人查询返回 404（不暴露存在性）" ($qb.status -eq 404) $qb.status

# ---------- 8. MOCK 即时渠道回归（既有路径不破坏） ----------
Write-Host ""
Write-Host "> MOCK 即时渠道回归"
Call 'Post' "$Gateway/api/cart/items" $sessA @{ skuId = $SKU_A; quantity = 1 } | Out-Null
$o2 = Call 'Post' "$Gateway/api/orders" $sessA ($recv + @{ idempotentKey = (NewKey) })
$orderNo2 = @($o2.body.data.orders)[0].orderNo
$p2 = Call 'Post' "$Gateway/api/orders/$orderNo2/pay" $sessA @{ channel = 'MOCK'; idempotentKey = (NewKey) }
Check "MOCK 渠道支付返回 200" ($p2.status -eq 200) $p2.status
Check "MOCK 渠道即时 SUCCESS（payStatus）" ($p2.body.data.payStatus -eq 'SUCCESS') $p2.body.data.payStatus
Check "MOCK 渠道订单即时已付款" ($p2.body.data.orderStatus -eq 'PAID') $p2.body.data.orderStatus
Check "MOCK 二维码为可辨识伪协议串" ($p2.body.data.codeUrl -match '^mock://instant-pay/') $p2.body.data.codeUrl

# ---------- 9. 支付单过期作废（补偿调度） ----------
Write-Host ""
Write-Host "> 支付单过期作废"
Call 'Post' "$Gateway/api/cart/items" $sessA @{ skuId = $SKU_A; quantity = 1 } | Out-Null
$o3 = Call 'Post' "$Gateway/api/orders" $sessA ($recv + @{ idempotentKey = (NewKey) })
$orderNo3 = @($o3.body.data.orders)[0].orderNo
$p3 = Call 'Post' "$Gateway/api/orders/$orderNo3/pay" $sessA @{ channel = 'SANDBOX'; idempotentKey = (NewKey) }
$payNo3 = $p3.body.data.payNo
Check "第三单沙箱预下单 PENDING" ($p3.body.data.payStatus -eq 'PENDING') $p3.body.data.payStatus

# 手动把过期时间改到过去（只改本脚本自己创建的支付单），再触发补偿调度
Sql 'duoduo_trade' "UPDATE t_payment SET expire_at = DATE_SUB(UTC_TIMESTAMP(), INTERVAL 5 MINUTE) WHERE pay_no = '$payNo3';" | Out-Null
$sweep = Call 'Post' "$Trade/api/orders/internal/sweep" $null $null $H_INTERNAL
Check "触发补偿调度返回 200" ($sweep.status -eq 200) $sweep.status
Check "过期支付单已置为 EXPIRED" ((Sql 'duoduo_trade' "SELECT status FROM t_payment WHERE pay_no='$payNo3';") -eq 'EXPIRED')
Check "订单仍未被支付单过期而关闭（订单有自己的过期时间）" ((Sql 'duoduo_trade' "SELECT status FROM t_order WHERE order_no='$orderNo3';") -eq 'PENDING_PAYMENT')

# 过期后扫码：沙箱支付应被拒（状态不再是 PENDING）
$sp3 = Call 'Post' "$Gateway/api/payments/sandbox/$payNo3" $null $null $null
Check "过期支付单不能再支付（422）" ($sp3.status -eq 422) $sp3.status

# 重新发起支付：新幂等键 → 新支付单（旧的已 EXPIRED，唯一索引不冲突）
$p3b = Call 'Post' "$Gateway/api/orders/$orderNo3/pay" $sessA @{ channel = 'SANDBOX'; idempotentKey = (NewKey) }
$payNo3b = $p3b.body.data.payNo
Check "同一订单可重新发起支付（新支付单 PENDING）" ($p3b.body.data.payStatus -eq 'PENDING') $p3b.body.data.payStatus

# ---------- 10. 取消订单作废未支付单 ----------
Write-Host ""
Write-Host "> 取消订单作废支付单"
$cancel = Call 'Post' "$Gateway/api/orders/$orderNo3/cancel" $sessA @{ reason = '沙箱测试取消' }
Check "取消订单成功" ($cancel.status -eq 200) $cancel.status
Check "取消后待支付支付单被作废（CLOSED）" ((Sql 'duoduo_trade' "SELECT status FROM t_payment WHERE pay_no='$payNo3b';") -eq 'CLOSED')
Check "取消后冻结释放（可售恢复）" ((Sql 'duoduo_product' "SELECT COALESCE(frozen_stock,0) FROM t_sku WHERE id=$SKU_A;") -eq "$origFrozen")

# ---------- 11. 数据复原与自证 ----------
Write-Host ""
Write-Host "> 数据复原与自证"
# 成功支付真实消耗了 3 件库存（订单1 买 2 + 订单2 买 1），显式复原到测试前原值
Sql 'duoduo_product' "UPDATE t_sku SET stock = $origStock, frozen_stock = $origFrozen WHERE id = $SKU_A;" | Out-Null
$nowStock = [int](Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU_A;")
$nowFrozen = [int](Sql 'duoduo_product' "SELECT COALESCE(frozen_stock,0) FROM t_sku WHERE id = $SKU_A;")
Check "★ 库存已复原到测试前原值（$origStock）" ($nowStock -eq $origStock) $nowStock
Check "★ 冻结量已复原到测试前原值（$origFrozen）" ($nowFrozen -eq $origFrozen) $nowFrozen
$leftover = Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_payment p JOIN t_order o ON o.id=p.order_id WHERE o.created_at >= '$testStart' AND p.status='PENDING';"
Check "★ 本次测试无残留 PENDING 支付单（过期的已 EXPIRED、取消的已 CLOSED）" ($leftover -eq '0') $leftover
$frozenLedger = Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_stock_freeze f JOIN t_order o ON o.id=f.order_id WHERE o.created_at >= '$testStart' AND f.status='FROZEN';"
Check "★ 本次测试无残留 FROZEN 冻结台账" ($frozenLedger -eq '0') $frozenLedger

# ---------- 结果 ----------
Write-Host ""
Write-Host "=========================================="
if ($fail -eq 0) {
    Write-Host "冒烟结果：$pass 通过 / 0 失败"
    exit 0
} else {
    Write-Host "冒烟结果：$pass 通过 / $fail 失败"
    $failures | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
