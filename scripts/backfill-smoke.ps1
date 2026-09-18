# 历史订单托管单回补（A1 / I-14）端到端冒烟（Windows PowerShell 5.1）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\backfill-smoke.ps1
#
# 前置：容器全部 healthy（docker compose up -d），且已 mvn package + docker compose build 出最新镜像。
#
# 覆盖：
#   1. 权限边界 —— 未登录 401（网关拦）/ 直连交易服务无身份 401（服务自身拦）/ 普通用户 403（OperatorGuard）；
#   2. dryRun 预演 —— 只出计划、一行都不写库（summary 与托管单行数都不变）；
#   3. 真实回补 —— 三类订单（PAID / COMPLETED / REFUNDED）按状态推断资金状态，
#      佣金按当前 PLATFORM_FEE_BPS 重算，放款时间取订单**真实的 finished_at**；
#   4. 幂等 —— 重复执行不再产生任何托管单（靠 uk_settle_order 兜底，不是靠「先查再插」）；
#   5. 异常挂账清空 —— 回补后 /api/admin/settlements/abnormal 不再有挂账；
#   6. 控制组 —— 已有托管单的订单**逐字段**比对，确认未被改动；
#   7. ★ REFUNDED 的盲区验证 —— abnormal 的定义是「已付款却缺托管单」，**不含退款单**，
#      所以「abnormal 为空」并不能证明退款单补对了，必须逐单读回托管状态单独断言；
#   8. 夹具自建自毁 —— 只改本脚本自己创建的订单；结尾复原被消耗的库存，
#      并自证「库里再无缺托管单的订单」。
#
# 设计原则（沿用前几套脚本的教训）：
#   1. 夹具从接口动态取（SKU 价格与库存不硬编码），佣金用与 Java 完全相同的整数公式算出来再比对；
#   2. 断言用业务键（订单号），不用 19 位雪花主键 —— JSON 解析会丢精度，出现「数据在库里、断言找不到」的假失败；
#   3. 带查询串的 URL 一律写 ${VAR}?：PS 5.1 会把 $VAR?page 整体当成变量名；
#   4. 中文内容一律走接口读回比对，不用 docker exec mysql 的 GBK 控制台比中文（会假失败）；
#   5. 已付款订单会真实扣减库存（退款会回补），结尾显式复原库存，保证脚本可反复执行。
#
# 注意（预期副作用，不是污染）：真实回补会把库里**既有的**缺托管单订单一并补上 ——
# 这正是 A1 的目的（当前开发库有 3 笔 COMPLETED 缺账本）。脚本自身创建的订单在结尾会被补齐到
# 「与正常订单一样有账本」的状态，因此下一次执行仍然从「3 笔新缺口」开始，可反复执行。
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    # 直连交易服务端口：用来验证「绕过网关直连」时服务自身会不会拦
    [string]$Trade = 'http://127.0.0.1:8083',
    [string]$Password = 'duoduo123',
    [string]$InternalToken = 'duoduo-local-dev-internal-token',
    # 必须与交易服务配置的 duoduo.trade.platform-fee-bps 一致（默认 100 = 1%）
    [int]$PlatformFeeBps = 100,
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
            try {
                $st = $_.Exception.Response.GetResponseStream()
                if ($st) { $t = (New-Object System.IO.StreamReader($st)).ReadToEnd(); if ($t) { $raw = $t; $body = $t | ConvertFrom-Json } }
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

# 与 SettlementService.platformFeeOf 完全相同的算法： fee = floor((amount * bps + 5000) / 10000)
# 必须走 [math]::Floor：PowerShell 的 [long] 强制转换是**四舍五入**，
# 直接用 [long]($a/10000) 会在 .5 处与 Java 的整数除法结果不同。
function FeeOf([long]$amount, [int]$bps) {
    if ($amount -le 0 -or $bps -le 0) { return [long]0 }
    return [long][math]::Floor(([double]$amount * $bps + 5000) / 10000)
}

function NewKey { return [guid]::NewGuid().ToString() }

function Summary($sess) {
    $r = Call 'Get' "$Gateway/api/admin/settlements/summary" $sess $null $null
    if ($r.status -ne 200) { return $null }
    return $r.body.data
}

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
$phoneOp = '137' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneU = '136' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneB = '135' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$recv = @{ receiverName = '小林'; receiverPhone = '13900000000'; receiverAddress = '上海市浦东新区张江路 1 号' }

Write-Host ""
Write-Host "=== 多多宠物商店 · 历史订单托管单回补（A1 / I-14）冒烟 @ $Gateway ==="
Write-Host "运营账号 = $phoneOp ｜ 普通用户 = $phoneU ｜ 买家 = $phoneB ｜ 佣金费率 = $PlatformFeeBps bp"
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
$regOp = Register $phoneOp '回补测试-运营'
Check "运营账号注册成功（初始为 USER）" ($regOp.result.status -eq 200) $regOp.result.status
Sql 'duoduo_user' "UPDATE t_user SET role = 'OPERATOR' WHERE phone = '$phoneOp';" | Out-Null
Check "库内角色已提权为 OPERATOR" ((Sql 'duoduo_user' "SELECT role FROM t_user WHERE phone = '$phoneOp';") -eq 'OPERATOR')
$sessOp = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$loginOp = Call 'Post' "$Gateway/api/auth/login" $sessOp @{ phone = $phoneOp; password = $Password }
Check "运营账号重新登录成功（角色是 token 快照，必须重登）" ($loginOp.status -eq 200) $loginOp.status

$regU = Register $phoneU '回补测试-路人'
Check "普通用户注册/登录成功" ($regU.result.status -eq 200) $regU.result.status
$sessU = $regU.session

$regB = Register $phoneB '回补测试-买家'
Check "买家账号注册/登录成功" ($regB.result.status -eq 200) $regB.result.status
$sessB = $regB.session

# ---------- 2. 权限边界（三层） ----------
# 参数走查询串（?dryRun=true），不带请求体 —— 与接口设计一致，也避开「默认 Content-Type → 415」的坑。
Write-Host ""
Write-Host "> 权限边界（网关 401 / 服务自身 401 / 角色 403）"
$anon = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$r = Call 'Post' "$Gateway/api/admin/settlements/backfill?dryRun=true" $anon $null $null
Check "未登录经网关调用回补返回 401（网关先拦一层）" ($r.status -eq 401) $r.status

$r = Call 'Post' "$Trade/api/admin/settlements/backfill?dryRun=true" $null $null $null
Check "绕过网关直连交易服务、无身份调用回补返回 401（服务自身也拦）" ($r.status -eq 401) $r.status

$r = Call 'Post' "$Gateway/api/admin/settlements/backfill?dryRun=true" $sessU $null $null
Check "普通用户调用回补返回 403（OperatorGuard）" ($r.status -eq 403) $r.status
Check "拒绝响应为规范化错误结构（code=FORBIDDEN）" ($r.body.code -eq 'FORBIDDEN') $r.body.code

$r = Call 'Post' "$Gateway/api/admin/settlements/backfill?dryRun=true" $sessOp $null $null
Check "运营调用回补放行（200）" ($r.status -eq 200) $r.status
Check "不带请求体也能调用（查询串参数，不依赖 Content-Type）" ($null -ne $r.body.data) ($r.raw -replace '\s+', ' ')

# ---------- 3. 夹具前提 ----------
Write-Host ""
Write-Host "> 夹具前提（SKU $SKU）"
$batch = Call 'Get' "$Gateway/api/products/internal/skus/batch?ids=$SKU" $null $null $H_INTERNAL
$skuInfo = @($batch.body.data)[0]
$P = [long]$skuInfo.price
$S = [int]$skuInfo.availableStock
Write-Host "  SKU $SKU 单价 $P 分 可售 $S"
if ($P -le 0 -or $S -lt 6) {
    Write-Host "夹具前提不满足，终止本次测试，不执行任何写操作。"
    Check "夹具前提：价格与库存均从接口正确读到且可售 ≥ 6" $false "P=$P S=$S"
    Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
    exit 1
}
Check "夹具前提：SKU $SKU 可售 ≥ 6（本脚本需 4 件）" ($S -ge 6) $S

$dbStockBefore = [int](Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU;")
Check "（前置）库内库存与接口可售一致（冻结为 0）" ($dbStockBefore -eq $S) "db=$dbStockBefore api=$S"
Check "（前置）库内冻结量为 0" ((Sql 'duoduo_product' "SELECT COALESCE(frozen_stock,0) FROM t_sku WHERE id = $SKU;") -eq '0')

# 回补前的「既有缺口」快照：按状态分三组取 [笔数, 金额]，回补结果必须精确等于「既有 + 本次夹具」
$preMissingSql = "SELECT CONCAT(" +
    "COALESCE(SUM(CASE WHEN o.status IN ('PAID','SHIPPED') THEN 1 ELSE 0 END),0),','," +
    "COALESCE(SUM(CASE WHEN o.status IN ('PAID','SHIPPED') THEN o.pay_amount ELSE 0 END),0),','," +
    "COALESCE(SUM(CASE WHEN o.status='COMPLETED' THEN 1 ELSE 0 END),0),','," +
    "COALESCE(SUM(CASE WHEN o.status='COMPLETED' THEN o.pay_amount ELSE 0 END),0),','," +
    "COALESCE(SUM(CASE WHEN o.status='REFUNDED' THEN 1 ELSE 0 END),0),','," +
    "COALESCE(SUM(CASE WHEN o.status='REFUNDED' THEN o.pay_amount ELSE 0 END),0)) " +
    "FROM t_order o LEFT JOIN t_settlement s ON s.order_id = o.id " +
    "WHERE s.id IS NULL AND o.status IN ('PAID','SHIPPED','COMPLETED','REFUNDED');"
$preMissing = Sql 'duoduo_trade' $preMissingSql
$preParts = @($preMissing -split ',')
Check "（前置）已取得回补前的缺口快照（6 个数字）" ($preParts.Count -eq 6) $preMissing
$preCustodyCnt = [int]$preParts[0]; $preCustodyAmt = [long]$preParts[1]
$preReleasedCnt = [int]$preParts[2]; $preReleasedAmt = [long]$preParts[3]
$preRefundedCnt = [int]$preParts[4]; $preRefundedAmt = [long]$preParts[5]
$preTotal = $preCustodyCnt + $preReleasedCnt + $preRefundedCnt
Write-Host "  回补前缺口：托管类 $preCustodyCnt 笔 / 已完成 $preReleasedCnt 笔 / 已退款 $preRefundedCnt 笔（合计 $preTotal）"

$sumBefore = Summary $sessOp
Check "（前置）取得回补前资金汇总" ($null -ne $sumBefore)

# ---------- 4. 夹具订单：四笔（A 待放款 / B 已完成缺口 / C 已退款 / D 控制组） ----------
Write-Host ""
Write-Host "> 夹具订单（买家下单并支付 ×4）"
$cA = PlaceAndPay $sessB $SKU 1 'A'
Check "夹具订单 A 下单支付成功" $cA.ok $cA.step
$cB = PlaceAndPay $sessB $SKU 1 'B'
Check "夹具订单 B 下单支付成功" $cB.ok $cB.step
$cC = PlaceAndPay $sessB $SKU 1 'C'
Check "夹具订单 C 下单支付成功" $cC.ok $cC.step
$cD = PlaceAndPay $sessB $SKU 1 'D'
Check "夹具订单 D 下单支付成功" $cD.ok $cD.step
if (-not ($cA.ok -and $cB.ok -and $cC.ok -and $cD.ok)) {
    Write-Host "夹具订单创建失败，终止。"
    Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
    exit 1
}
$onA = $cA.orderNo; $onB = $cB.orderNo; $onC = $cC.orderNo; $onD = $cD.orderNo
$payA = [long]$cA.order.payAmount
$payB = [long]$cB.order.payAmount
$payC = [long]$cC.order.payAmount
$payD = [long]$cD.order.payAmount
Write-Host "  A=$onA ($payA 分) ｜ B=$onB ｜ C=$onC ｜ D=$onD"

# ---------- 5. 制造缺口：删掉 A/B 的托管单（真实复现 I-14） ----------
Write-Host ""
Write-Host "> 制造缺口：删除 A / B 的托管单，再走 B 的发货 + 确认收货"
if (-not $SkipDbCheck) {
    Sql 'duoduo_trade' "DELETE FROM t_settlement WHERE order_id IN (SELECT id FROM t_order WHERE order_no IN ('$onA','$onB'));" | Out-Null
    $leftAB = Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_settlement WHERE order_id IN (SELECT id FROM t_order WHERE order_no IN ('$onA','$onB'));"
    Check "（夹具）A / B 的托管单已删除" ($leftAB -eq '0') $leftAB
}

$r = Call 'Post' "$Gateway/api/orders/$onB/ship" $sessB $null $H_INTERNAL
Check "B 发货成功（200）" ($r.status -eq 200) $r.status
$r = Call 'Post' "$Gateway/api/orders/$onB/confirm" $sessB $null
Check "★ B 确认收货仍然成功（放款失败不阻断买家收货）" ($r.status -eq 200) $r.status
Check "B 订单已为 COMPLETED" ($r.body.data.status -eq 'COMPLETED') $r.body.data.status

$r = Call 'Post' "$Gateway/api/orders/$onC/refund" $sessB @{ reason = '回补夹具：买家自助退款' }
Check "C 买家自助退款成功（200）" ($r.status -eq 200) ($r.body | ConvertTo-Json -Compress)
Check "C 订单已为 REFUNDED" ($r.body.data.orderStatus -eq 'REFUNDED') $r.body.data.orderStatus

$r = Call 'Post' "$Trade/api/orders/internal/sweep" $null $null $H_INTERNAL
Check "手动触发补偿调度成功（入库补偿等收口）" ($r.status -eq 200) $r.status

if (-not $SkipDbCheck) {
    Sql 'duoduo_trade' "DELETE FROM t_settlement WHERE order_id = (SELECT id FROM t_order WHERE order_no = '$onC');" | Out-Null
    $leftC = Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_settlement WHERE order_id = (SELECT id FROM t_order WHERE order_no = '$onC');"
    Check "（夹具）C 的托管单已删除（退款单同样缺账本）" ($leftC -eq '0') $leftC
}

# 控制组 D：走正常链路，保留托管单，用于验证「已有托管单的订单不被改动」
$r = Call 'Post' "$Gateway/api/orders/$onD/ship" $sessB $null $H_INTERNAL
Check "D 发货成功（控制组）" ($r.status -eq 200) $r.status
$r = Call 'Post' "$Gateway/api/orders/$onD/confirm" $sessB $null
Check "D 确认收货成功且已放款（控制组）" ($r.status -eq 200 -and $r.body.data.settlement.status -eq 'RELEASED') $r.status
$ctrlSql = "SELECT CONCAT(id,'|',amount,'|',platform_fee,'|',status,'|'," +
    "COALESCE(DATE_FORMAT(released_at,'%Y-%m-%d %H:%i:%s'),'null')) " +
    "FROM t_settlement WHERE order_id = (SELECT id FROM t_order WHERE order_no = '$onD');"
$ctrlBefore = Sql 'duoduo_trade' $ctrlSql
Check "（夹具）控制组 D 的托管单快照已取得" (-not [string]::IsNullOrEmpty($ctrlBefore)) $ctrlBefore

# ---------- 6. dryRun 预演：只出计划，一行都不写库 ----------
# 基准必须**紧贴**被测操作取一次。第一版脚本用的是「创建夹具之前」的汇总，
# 结果同时踩了两种错：把控制组 D 自己确认收货带来的放款增量算到 dryRun 头上（假失败），
# 又把 A/B/C 三个托管单被删掉造成的托管额下降抵消掉了（假通过）。
# 教训：基准离被测操作越远，越容易既报假失败、又放过真问题。
Write-Host ""
Write-Host "> dryRun 预演"
$sumBeforeDry = Summary $sessOp
Check "（前置）取得 dryRun 前的资金汇总基准" ($null -ne $sumBeforeDry)
$r = Call 'Post' "$Gateway/api/admin/settlements/backfill?dryRun=true" $sessOp $null $null
Check "dryRun 返回 200" ($r.status -eq 200) ($r.body | ConvertTo-Json -Compress)
Check "响应标记 dryRun=true" ($null -ne $r.body.data -and $r.body.data.dryRun -eq $true) $r.body.data.dryRun
Check "dryRun 不写库（created=0）" ($null -ne $r.body.data -and [int]$r.body.data.created -eq 0) $r.body.data.created
Check "dryRun 扫描到全部缺口（既有 $preTotal + 本次 3 = $($preTotal + 3)）" ($null -ne $r.body.data -and [int]$r.body.data.scanned -eq ($preTotal + 3)) $r.body.data.scanned
$itemA = @($r.body.data.items | Where-Object { $_.orderNo -eq $onA })[0]
Check "计划：A（PAID）→ CUSTODY" ($null -ne $itemA -and $itemA.settlementStatus -eq 'CUSTODY') ($itemA | ConvertTo-Json -Compress)
$itemB = @($r.body.data.items | Where-Object { $_.orderNo -eq $onB })[0]
Check "计划：B（COMPLETED）→ RELEASED" ($null -ne $itemB -and $itemB.settlementStatus -eq 'RELEASED') ($itemB | ConvertTo-Json -Compress)
$itemC = @($r.body.data.items | Where-Object { $_.orderNo -eq $onC })[0]
Check "计划：C（REFUNDED）→ REFUNDED" ($null -ne $itemC -and $itemC.settlementStatus -eq 'REFUNDED') ($itemC | ConvertTo-Json -Compress)
Check "计划：A 的佣金按 $PlatformFeeBps bp 算对（$(FeeOf $payA $PlatformFeeBps) 分）" ($null -ne $itemA -and [long]$itemA.platformFee -eq (FeeOf $payA $PlatformFeeBps)) $itemA.platformFee

$sumAfterDry = Summary $sessOp
Check "★ dryRun 后托管金额一分未动（托管中）" ($null -ne $sumAfterDry -and [long]$sumAfterDry.custodyAmount -eq [long]$sumBeforeDry.custodyAmount) "before=$($sumBeforeDry.custodyAmount) after=$($sumAfterDry.custodyAmount)"
Check "★ dryRun 后托管金额一分未动（已放款）" ($null -ne $sumAfterDry -and [long]$sumAfterDry.releasedAmount -eq [long]$sumBeforeDry.releasedAmount) "before=$($sumBeforeDry.releasedAmount) after=$($sumAfterDry.releasedAmount)"
Check "★ dryRun 后托管金额一分未动（已退回）" ($null -ne $sumAfterDry -and [long]$sumAfterDry.refundedAmount -eq [long]$sumBeforeDry.refundedAmount) "before=$($sumBeforeDry.refundedAmount) after=$($sumAfterDry.refundedAmount)"
if (-not $SkipDbCheck) {
    $stillMissing = Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_settlement WHERE order_id IN (SELECT id FROM t_order WHERE order_no IN ('$onA','$onB','$onC'));"
    Check "★ dryRun 后 A/B/C 仍无托管单（确实没写库）" ($stillMissing -eq '0') $stillMissing
}

# ---------- 7. 真实回补 ----------
Write-Host ""
Write-Host "> 真实回补（dryRun=false）"
$sumBeforeReal = Summary $sessOp
Check "（前置）取得真实回补前的资金汇总基准" ($null -ne $sumBeforeReal)
$r = Call 'Post' "$Gateway/api/admin/settlements/backfill?dryRun=false" $sessOp $null $null
Check "真实回补返回 200" ($r.status -eq 200) ($r.body | ConvertTo-Json -Compress)
$bf = $r.body.data
Check "响应标记 dryRun=false" ($null -ne $bf -and $bf.dryRun -eq $false) $bf.dryRun
Check "新建条数 = 既有缺口 $preTotal + 本次 3 = $($preTotal + 3)" ($null -ne $bf -and [int]$bf.created -eq ($preTotal + 3)) $bf.created
Check "无撞车跳过（skipped=0）" ($null -ne $bf -and [int]$bf.skipped -eq 0) $bf.skipped
Check "托管中笔数精确增量（1 + $preCustodyCnt）" ([int]$bf.custodyCount -eq (1 + $preCustodyCnt)) $bf.custodyCount
Check "已放款笔数精确增量（1 + $preReleasedCnt）" ([int]$bf.releasedCount -eq (1 + $preReleasedCnt)) $bf.releasedCount
Check "已退回笔数精确增量（1 + $preRefundedCnt）" ([int]$bf.refundedCount -eq (1 + $preRefundedCnt)) $bf.refundedCount
Check "托管中金额精确增量（$payA + $preCustodyAmt）" ([long]$bf.custodyAmount -eq ($payA + $preCustodyAmt)) $bf.custodyAmount
Check "已放款金额精确增量（$payB + $preReleasedAmt）" ([long]$bf.releasedAmount -eq ($payB + $preReleasedAmt)) $bf.releasedAmount
Check "已退回金额精确增量（$payC + $preRefundedAmt）" ([long]$bf.refundedAmount -eq ($payC + $preRefundedAmt)) $bf.refundedAmount
Check "审计：回补口径带上了佣金费率（$PlatformFeeBps bp）" ([int]$bf.commissionBps -eq $PlatformFeeBps) $bf.commissionBps
Check "审计：回补口径有文字说明（历史费率不可还原）" (-not [string]::IsNullOrEmpty($bf.commissionNote)) $bf.commissionNote

$sumAfter = Summary $sessOp
Check "★ 汇总口径与回补结果自洽（已放款金额增量一致）" ($null -ne $sumAfter -and [long]$sumAfter.releasedAmount -eq ([long]$sumBeforeReal.releasedAmount + $payB + $preReleasedAmt)) "before=$($sumBeforeReal.releasedAmount) after=$($sumAfter.releasedAmount)"
Check "★ 汇总口径与回补结果自洽（托管中金额增量一致）" ($null -ne $sumAfter -and [long]$sumAfter.custodyAmount -eq ([long]$sumBeforeReal.custodyAmount + $payA + $preCustodyAmt)) "before=$($sumBeforeReal.custodyAmount) after=$($sumAfter.custodyAmount)"
Check "★ 汇总口径与回补结果自洽（已退回金额增量一致）" ($null -ne $sumAfter -and [long]$sumAfter.refundedAmount -eq ([long]$sumBeforeReal.refundedAmount + $payC + $preRefundedAmt)) "before=$($sumBeforeReal.refundedAmount) after=$($sumAfter.refundedAmount)"

# ---------- 8. 幂等：重复执行 ----------
Write-Host ""
Write-Host "> 幂等（重复执行）"
$cntBefore2 = Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_settlement;"
$r = Call 'Post' "$Gateway/api/admin/settlements/backfill" $sessOp $null $null
Check "重复执行返回 200（不带参数，默认即执行）" ($r.status -eq 200) $r.status
Check "不带参数时语义为「执行」而非「预演」（dryRun=false）" ($null -ne $r.body.data -and $r.body.data.dryRun -eq $false) $r.body.data.dryRun
Check "重复执行扫描到 0 条（已无缺口）" ($null -ne $r.body.data -and [int]$r.body.data.scanned -eq 0) $r.body.data.scanned
Check "重复执行不产生任何新托管单（created=0）" ($null -ne $r.body.data -and [int]$r.body.data.created -eq 0) $r.body.data.created
$cntAfter2 = Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_settlement;"
Check "★ 托管单总行数未变（$cntBefore2 → $cntAfter2）" ($cntBefore2 -eq $cntAfter2) "$cntBefore2/$cntAfter2"

# ---------- 9. 异常挂账清空 ----------
Write-Host ""
Write-Host "> 异常挂账"
$r = Call 'Get' "$Gateway/api/admin/settlements/abnormal" $sessOp $null $null
Check "★ 回补后异常挂账列表为空" ($r.status -eq 200 -and @($r.body.data).Count -eq 0) (@($r.body.data | ConvertTo-Json -Compress) -join ' ')

# ---------- 10. 逐单核对（含 REFUNDED 盲区） ----------
Write-Host ""
Write-Host "> 逐单核对（买家视角读回托管状态）"
$r = Call 'Get' "$Gateway/api/orders/$onA/settlement" $sessB $null
Check "A：托管单已补出（200）" ($r.status -eq 200) $r.status
Check "A：状态为 CUSTODY（已付款未完成 → 钱应留在托管）" ($r.body.data.status -eq 'CUSTODY') $r.body.data.status
Check "A：金额 = 订单实付（$payA 分）" ([long]$r.body.data.amount -eq $payA) $r.body.data.amount
Check "A：佣金 = $PlatformFeeBps bp 算出的 $(FeeOf $payA $PlatformFeeBps) 分" ([long]$r.body.data.platformFee -eq (FeeOf $payA $PlatformFeeBps)) $r.body.data.platformFee
Check "A：卖家可得 = 金额 − 佣金" ([long]$r.body.data.sellerAmount -eq ($payA - (FeeOf $payA $PlatformFeeBps))) $r.body.data.sellerAmount
Check "A：未放款（releasedAt 为空）" ([string]::IsNullOrEmpty($r.body.data.releasedAt)) $r.body.data.releasedAt

$r = Call 'Get' "$Gateway/api/orders/$onB/settlement" $sessB $null
Check "B：状态为 RELEASED（已完成 → 钱应已放款）" ($r.body.data.status -eq 'RELEASED') $r.body.data.status
Check "B：金额 = 订单实付（$payB 分）" ([long]$r.body.data.amount -eq $payB) $r.body.data.amount
Check "B：佣金按 $PlatformFeeBps bp 算对" ([long]$r.body.data.platformFee -eq (FeeOf $payB $PlatformFeeBps)) $r.body.data.platformFee
Check "B：放款时间非空（补出来的放款必须带时间）" (-not [string]::IsNullOrEmpty($r.body.data.releasedAt)) $r.body.data.releasedAt
$bRel = $r.body.data.releasedAt
$odB = Call 'Get' "$Gateway/api/orders/$onB" $sessB $null
Check "★ B：放款时间取的是订单真实的 finishedAt（不是脚本执行时刻）" ($bRel -eq $odB.body.data.finishedAt) "releasedAt=$bRel finishedAt=$($odB.body.data.finishedAt)"

$r = Call 'Get' "$Gateway/api/orders/$onC/settlement" $sessB $null
Check "★ C：状态为 REFUNDED（退款单也必须有钱在哪里的答案）" ($r.body.data.status -eq 'REFUNDED') $r.body.data.status
Check "C：金额 = 订单实付（$payC 分）" ([long]$r.body.data.amount -eq $payC) $r.body.data.amount
Check "C：托管文案为「已退回买家」" ($r.body.data.statusLabel -match '退回') $r.body.data.statusLabel

# ---------- 11. 控制组：已有托管单的订单未被改动 ----------
Write-Host ""
Write-Host "> 控制组（已有托管单的订单必须原样）"
$ctrlAfter = Sql 'duoduo_trade' $ctrlSql
Check "★ D 的托管单逐字段未变（id/金额/佣金/状态/放款时间）" ($ctrlAfter -eq $ctrlBefore) "before=$ctrlBefore after=$ctrlAfter"
$r = Call 'Get' "$Gateway/api/orders/$onD/settlement" $sessB $null
Check "D：状态仍为 RELEASED" ($r.body.data.status -eq 'RELEASED') $r.body.data.status
Check "D：金额仍为 $payD 分（未被改写）" ([long]$r.body.data.amount -eq $payD) $r.body.data.amount

# ---------- 12. 清理与自证复原 ----------
Write-Host ""
Write-Host "> 清理夹具与自证复原"
if (-not $SkipDbCheck) {
    Sql 'duoduo_product' "UPDATE t_sku SET stock = $dbStockBefore, frozen_stock = 0 WHERE id = $SKU;" | Out-Null
    $nowStock = Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU;"
    Check "★ SKU $SKU 库存已复原为测试前的 $dbStockBefore" ("$nowStock" -eq "$dbStockBefore") $nowStock
    Check "★ 冻结量已清零" ((Sql 'duoduo_product' "SELECT COALESCE(frozen_stock,0) FROM t_sku WHERE id = $SKU;") -eq '0')

    $leftover = Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_order o LEFT JOIN t_settlement s ON s.order_id = o.id WHERE s.id IS NULL AND o.status IN ('PAID','SHIPPED','COMPLETED','REFUNDED');"
    Check "★ 库里再无「缺托管单」的订单（含本次夹具，已全部补齐）" ($leftover -eq '0') $leftover

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
