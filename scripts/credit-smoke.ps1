# 信用分（A4）端到端冒烟（Windows PowerShell 5.1）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\credit-smoke.ps1
#
# 前置：容器全部 healthy（docker compose up -d），且已 mvn package + docker compose build 出最新镜像。
#
# 覆盖 docs/agent/07 的 A4 四条验收标准：
#   ① 无数据用户得基础分（不返回 null）；
#   ② 分数落在 0~100 且单调可解释（含权重表）；
#   ③ 回写幂等；
#   ④ 明细与分数自洽（因子和 = 总分）。
#
# 另外覆盖（07 没写但真实存在、不测迟早出事的）：
#   · 公开只读：未登录访客可读卖家信用分（网关白名单精确到 /api/reviews/seller/*/credit）；
#   · 内部刷新路径不经网关放行（未登录走网关 401），回写必须直连 + X-Internal-Token；
#   · ★ 单调方向：均分下降 / 出现「被裁定退款」时分数只降不升，且始终落在区间内；
#   · ★ 售后诚信因子「只算被平台裁定支持买家」的口径（卖家主动同意不是污点，见 DDL 注释）；
#   · 响应不含任何身份字段（手机号 / 昵称 / 收货人）—— 信用分只暴露「计数与评分」；
#   · 用户域侧回写是「设置」不是「累加」：同值重放库里不变；
#   · 非法 userIds 返回 400 而不是 500（否则运维只会看到一个没有上下文的服务端错误）。
#
# 设计原则（沿用前几套脚本的教训）：
#   1. 夹具从接口动态取（SKU / SPU / 价格不硬编码）；改库前先记录、结尾自证复原；
#   2. 断言用**业务键**（订单号），不拿 19 位雪花 id 做 JSON 比较（ConvertFrom-Json 会丢精度）；
#   3. ★「断言总数守卫」改为**动态**：直接数脚本里 Check 的条数，与实跑条数比对 ——
#      写死期望值会随增删断言而漂移，数文件本身则永远自洽（T-31 的加强版）。
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    [string]$Fulfilment = 'http://127.0.0.1:8084',
    [string]$User = 'http://127.0.0.1:8081',
    [string]$Trade = 'http://127.0.0.1:8083',
    [string]$Password = 'duoduo123',
    [string]$InternalToken = 'duoduo-local-dev-internal-token',
    [int]$SKU = 2001
)

$ErrorActionPreference = 'Continue'
$pass = 0
$fail = 0
$failures = @()

# 信用分权重表（默认口径，与 duoduo-fulfilment application.yml 的 duoduo.credit.* 对齐）
$W_BASE = 60
$W_COMPLETION = 18
$W_REVIEW = 16
$W_AFTERSALE = 6

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

# 平台运营会话：自营订单归平台（seller_id=0），只有 OPERATOR 能发货 ——
# 迁移前脚本用买家会话调旧的 /api/orders/{no}/ship（无归属校验），迁移后必须换身份。
$op = New-PlatformOperatorSession -Gateway $Gateway -Password $Password
$sessOp = $op.session
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

function Credit([string]$sellerId, $sess) {
    return (Call 'Get' "$Gateway/api/reviews/seller/$sellerId/credit" $sess $null $null)
}

function Factor($resp, [string]$code) {
    return @($resp.body.data.factors | Where-Object { $_.code -eq $code })[0]
}

function SumPoints($resp) {
    $s = 0
    foreach ($f in @($resp.body.data.factors)) { $s += [int]$f.points }
    return $s
}

# 下单 → 支付 → 发货（内部凭据）→ 确认收货 → COMPLETED
function CompleteOrder($sess, [int]$sku) {
    Call 'Post' "$Gateway/api/cart/items" $sess @{ skuId = $sku; quantity = 1 } | Out-Null
    $o = Call 'Post' "$Gateway/api/orders" $sess ($recv + @{ idempotentKey = (NewKey) })
    if ($o.status -ne 200) { return @{ ok = $false; orderNo = $null; step = '下单' } }
    $no = @($o.body.data.orders)[0].orderNo
    $p = Call 'Post' "$Gateway/api/orders/$no/pay" $sess @{ channel = 'MOCK'; idempotentKey = (NewKey) }
    if ($p.status -ne 200) { return @{ ok = $false; orderNo = $no; step = '支付' } }
    $s = Call 'Post' "$Gateway/api/seller/orders/$no/ship" $sessShip @{ logisticsCompany = '顺丰速运'; logisticsNo = ('SF' + (Get-Random)) }
    if ($s.status -ne 200) { return @{ ok = $false; orderNo = $no; step = '发货' } }
    $c = Call 'Post' "$Gateway/api/orders/$no/confirm" $sess $null
    if ($c.status -ne 200) { return @{ ok = $false; orderNo = $no; step = '确认收货' } }
    return @{ ok = $true; orderNo = $no; step = $null }
}

function SubmitReview($sess, [string]$orderNo, [long]$spuId, [int]$skuId, [int]$rating, [string]$content) {
    return (Call 'Post' "$Gateway/api/reviews" $sess @{
        orderNo = $orderNo
        anonymous = $false
        items = @(@{ spuId = $spuId; skuId = $skuId; rating = $rating; content = $content })
    })
}

$H_INTERNAL = @{ 'X-Internal-Token' = 'internal' }
$H_BAD = @{ 'X-Internal-Token' = 'wrong-token-should-be-rejected' }
$phoneS = '133' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneA = '135' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneN = '132' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$recv = @{ receiverName = '小林'; receiverPhone = '13900000000'; receiverAddress = '上海市浦东新区张江路 1 号' }

Write-Host ""
Write-Host "=== 多多宠物商店 · 信用分（A4）冒烟 @ $Gateway ==="
Write-Host "卖家 S = $phoneS ｜ 买家 A = $phoneA ｜ 无数据用户 N = $phoneN"
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
$fulfilReady = $false
for ($i = 0; $i -lt 40; $i++) {
    try {
        $h = Invoke-WebRequest -Uri "$Fulfilment/actuator/health" -UseBasicParsing -TimeoutSec 5
        if ($h.StatusCode -eq 200) { $fulfilReady = $true; break }
    } catch { Start-Sleep -Seconds 1 }
}
Check "履约服务 /actuator/health 就绪" $fulfilReady
if (-not $fulfilReady) { Write-Host "履约服务未就绪，终止"; exit 1 }

# ---------- 1. 账号夹具 ----------
Write-Host ""
Write-Host "> 账号夹具"
$regS = Register $phoneS '信用测试-卖家S'
Check "卖家 S 注册成功（初始 USER）" ($regS.result.status -eq 200) $regS.result.status
$sessS = $regS.session
$r = Call 'Post' "$Gateway/api/users/me/realname" $sessS @{ realName = '张老板'; idCard = '310101199001011234' }
Check "卖家 S 实名认证成功" ($r.status -eq 200) $r.status
$r = Call 'Post' "$Gateway/api/users/me/merchant" $sessS $null
Check "卖家 S 申请成为卖家成功" ($r.status -eq 200) $r.status
$sessS = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$r = Call 'Post' "$Gateway/api/auth/login" $sessS @{ phone = $phoneS; password = $Password }
Check "卖家 S 重新登录成功（角色是 token 快照，必须重登）" ($r.status -eq 200) $r.status

$regA = Register $phoneA '信用测试-买家A'
Check "买家 A 注册/登录成功" ($regA.result.status -eq 200) $regA.result.status
$sessA = $regA.session

$regN = Register $phoneN '信用测试-无数据N'
Check "无数据用户 N 注册成功" ($regN.result.status -eq 200) $regN.result.status

$sellerId = Sql 'duoduo_user' "SELECT id FROM t_user WHERE phone = '$phoneS';"
Check "（前置）取得卖家 S 的 userId" (-not [string]::IsNullOrEmpty($sellerId)) $sellerId
$buyerId = Sql 'duoduo_user' "SELECT id FROM t_user WHERE phone = '$phoneA';"
$noneId = Sql 'duoduo_user' "SELECT id FROM t_user WHERE phone = '$phoneN';"
Check "（前置）取得买家 A / 用户 N 的 userId" ((-not [string]::IsNullOrEmpty($buyerId)) -and (-not [string]::IsNullOrEmpty($noneId))) "buyer=$buyerId none=$noneId"

# ---------- 2. 基础分与公开只读（验收①） ----------
Write-Host ""
Write-Host "> 验收①：无数据 = 基础分；且未登录可读"
$anon = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$rN = Credit $noneId $anon
Check "未登录访客可读信用分（200，白名单放行）" ($rN.status -eq 200) $rN.status
Check "① 无数据用户信用分 = 基础分 $W_BASE（不是 null）" ($rN.body.data.score -eq $W_BASE) $rN.body.data.score
Check "① 无数据用户 score 字段存在且非 null" ($null -ne $rN.body.data.score) "null"
$rS0 = Credit $sellerId $anon
Check "① 卖家 S 尚无数据时同样得基础分 $W_BASE" ($rS0.body.data.score -eq $W_BASE) $rS0.body.data.score
$rGhost = Credit '999999999999' $anon
Check "① 从未出现过的 userId 也得基础分（不报错、不 404）" ($rGhost.status -eq 200 -and $rGhost.body.data.score -eq $W_BASE) "$($rGhost.status)/$($rGhost.body.data.score)"
Check "① 基础分对应等级 POOR" ($rN.body.data.level -eq 'POOR') $rN.body.data.level
Check "① 等级中文文案为「待提升」" ($rN.body.data.levelLabel -eq '待提升') $rN.body.data.levelLabel
$dbNone = Sql 'duoduo_user' "SELECT credit_score FROM t_user WHERE phone = '$phoneN';"
Check "① 新注册用户库内分数 == 信用分接口基础分（两端常量对齐）" ("$dbNone" -eq "$W_BASE") "db=$dbNone api=$W_BASE"

# ---------- 3. 权重表与明细自洽（验收②④） ----------
Write-Host ""
Write-Host "> 验收②④：权重表与明细自洽"
$fBase = Factor $rN 'BASE'
$fComp = Factor $rN 'COMPLETION'
$fRev = Factor $rN 'REVIEW'
$fAfter = Factor $rN 'AFTERSALE'
Check "② 权重表：基础分满分 $W_BASE" ($fBase.weight -eq $W_BASE) $fBase.weight
Check "② 权重表：履约完成率满分 $W_COMPLETION" ($fComp.weight -eq $W_COMPLETION) $fComp.weight
Check "② 权重表：评价质量满分 $W_REVIEW" ($fRev.weight -eq $W_REVIEW) $fRev.weight
Check "② 权重表：售后诚信满分 $W_AFTERSALE" ($fAfter.weight -eq $W_AFTERSALE) $fAfter.weight
Check "② 四项权重之和 = 100" (($fBase.weight + $fComp.weight + $fRev.weight + $fAfter.weight) -eq 100)
Check "④ 构成明细共 4 项" (@($rN.body.data.factors).Count -eq 4) (@($rN.body.data.factors).Count)
Check "④ 因子得分之和 == 总分" ((SumPoints $rN) -eq $rN.body.data.score) "sum=$(SumPoints $rN) score=$($rN.body.data.score)"
Check "② 分数落在 [0,100]" ($rN.body.data.score -ge 0 -and $rN.body.data.score -le 100) $rN.body.data.score
Check "无数据时三项加权因子均为 0 分（只余基础分）" (($fComp.points -eq 0) -and ($fRev.points -eq 0) -and ($fAfter.points -eq 0)) "$($fComp.points)/$($fRev.points)/$($fAfter.points)"
$inp = $rN.body.data.inputs
Check "无数据时 inputs 计数全为 0（avgRating 规范为 0.00）" (($inp.completedOrders -eq 0) -and ($inp.totalOrders -eq 0) -and ($inp.reviewCount -eq 0) -and ($inp.avgRating -eq '0.00')) ($inp | ConvertTo-Json -Compress)
Check "响应不含任何身份字段（手机号 / 昵称 / 收货人）" ($rN.raw -notmatch 'phone|nickname|receiver|idCard|realName') "hit=$($rN.raw -match 'phone|nickname|receiver|idCard|realName')"

# ---------- 4. 建真实数据：4 笔已完成订单 + 3 条五星评价 ----------
Write-Host ""
Write-Host "> 夹具（SKU $SKU，本脚本需 4 件）"
$batch = Call 'Get' "$Gateway/api/products/internal/skus/batch?ids=$SKU" $null $null $H_INTERNAL
$snap = @($batch.body.data)[0]
$SPU = [long]$snap.spuId
$S = [int]$snap.availableStock
Write-Host "  SKU $SKU 属 SPU $SPU，可售 $S"
# 夹具前提不满足时直接退出：这里刻意**不**调用 Check —— 断言总数守卫是「声明数 vs 实跑数」，
# 放进条件分支的断言在正常路径上会被跳过，反而把守卫本身弄成假失败。
if ($S -lt 4) {
    Write-Host "  [FATAL] 夹具前提不满足：SKU $SKU 可售 $S < 4，终止本次测试，不执行任何写操作。"
    Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
    exit 1
}
Check "夹具前提：SKU $SKU 可售 >= 4" ($S -ge 4) $S
$dbStockBefore = [int](Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU;")
Check "（前置）库内库存与接口可售一致（冻结为 0）" ($dbStockBefore -eq $S) "db=$dbStockBefore api=$S"

Write-Host ""
Write-Host "> 四笔已完成订单（下单 → 支付 → 发货 → 确认收货）"
$orders = @()
$allOk = $true
$badStep = $null
for ($i = 1; $i -le 4; $i++) {
    $c = CompleteOrder $sessA $SKU
    if (-not $c.ok) { $allOk = $false; $badStep = "第 $i 笔：$($c.step)"; break }
    $orders += $c.orderNo
}
Check "四笔订单全链路成功（COMPLETED）" $allOk $badStep
if (-not $allOk) { Write-Host "订单夹具创建失败，终止。"; Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="; exit 1 }
$on1 = $orders[0]; $on2 = $orders[1]; $on3 = $orders[2]; $on4 = $orders[3]

# 归属夹具：把四笔订单划给卖家 S（记录原值，结尾复原）。
# 订单与托管单必须一起改：两表的 seller_id 是同一事实的两份记录，只改一处会得到自相矛盾的夹具。
$origSellers = Sql 'duoduo_trade' "SELECT CONCAT(order_no,'=',seller_id) FROM t_order WHERE order_no IN ('$on1','$on2','$on3','$on4') ORDER BY order_no;"
Sql 'duoduo_trade' "UPDATE t_order SET seller_id = $sellerId WHERE order_no IN ('$on1','$on2','$on3','$on4');" | Out-Null
Sql 'duoduo_trade' "UPDATE t_settlement s JOIN t_order o ON o.id = s.order_id SET s.seller_id = $sellerId WHERE o.order_no IN ('$on1','$on2','$on3','$on4');" | Out-Null
$bound = Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_order WHERE order_no IN ('$on1','$on2','$on3','$on4') AND seller_id = $sellerId;"
Check "（夹具）四笔订单已划给卖家 S" ($bound -eq '4') $bound

# 三条五星评价（评价的 seller_id 取自订单事实，必须在划归之后提交）
$r1 = SubmitReview $sessA $on1 $SPU $SKU 5 '猫粮很香，毛孩子吃得干干净净'
$r2 = SubmitReview $sessA $on2 $SPU $SKU 5 '物流快，包装结实'
$r3 = SubmitReview $sessA $on3 $SPU $SKU 5 '回购第三次了，放心'
Check "三条五星评价均提交成功" (($r1.status -eq 200) -and ($r2.status -eq 200) -and ($r3.status -eq 200)) "$($r1.status)/$($r2.status)/$($r3.status)"
$dbRev = Sql 'duoduo_fulfilment' "SELECT COUNT(*) FROM t_review WHERE seller_id = $sellerId;"
Check "（夹具）库内归属卖家 S 的评价共 3 条" ($dbRev -eq '3') $dbRev

# ---------- 5. 满分（验收②） ----------
Write-Host ""
Write-Host "> 验收②：3 单完成 + 3 条五星 → 满分"
$rC = Credit $sellerId $anon
$cComp = Factor $rC 'COMPLETION'
$cRev = Factor $rC 'REVIEW'
$cAfter = Factor $rC 'AFTERSALE'
$cInp = $rC.body.data.inputs
Check "卖家订单计数：已完成 4 / 总计 4" (($cInp.completedOrders -eq 4) -and ($cInp.totalOrders -eq 4)) "$($cInp.completedOrders)/$($cInp.totalOrders)"
Check "履约因子得满分 $W_COMPLETION（完成率 100%）" ($cComp.points -eq $W_COMPLETION) $cComp.points
Check "评价因子得满分 $W_REVIEW（均分 5.00）" ($cRev.points -eq $W_REVIEW) $cRev.points
Check "信用分 = 100（基础 + 履约 + 评价 + 售后）" ($rC.body.data.score -eq 100) $rC.body.data.score
Check "等级为 EXCELLENT" ($rC.body.data.level -eq 'EXCELLENT') $rC.body.data.level
Check "等级中文文案为「优秀」" ($rC.body.data.levelLabel -eq '优秀') $rC.body.data.levelLabel
Check "④ 因子和 == 总分" ((SumPoints $rC) -eq $rC.body.data.score) "sum=$(SumPoints $rC) score=$($rC.body.data.score)"
Check "履约因子 rawValue 显示 100% 与完成/已了结数" ($cComp.rawValue -match '100%') $cComp.rawValue
Check "评价因子 rawValue 显示 5.00/5 与条数" (($cRev.rawValue -match '5\.00/5') -and ($cRev.rawValue -match '3')) $cRev.rawValue
Check "售后因子 rawValue 为「无被裁定退款的记录」" ($cAfter.rawValue -eq '无被裁定退款的记录') $cAfter.rawValue

# ---------- 6. 单调可解释（验收②） ----------
Write-Host ""
Write-Host "> 验收②：评分下降 → 分数只降不升"
$r4 = SubmitReview $sessA $on4 $SPU $SKU 1 '和描述不符，尺寸偏小'
Check "第 4 条（1 星）评价提交成功" ($r4.status -eq 200) $r4.status
$rC2 = Credit $sellerId $anon
$c2Rev = Factor $rC2 'REVIEW'
Check "★ 评价因子降为 13（均分 4.00）" ($c2Rev.points -eq 13) $c2Rev.points
Check "★ 分数随均分下降：100 → 97（单调可解释）" ($rC2.body.data.score -eq 97) $rC2.body.data.score
Check "★ 下降后分数仍在 [0,100]" ($rC2.body.data.score -ge 0 -and $rC2.body.data.score -le 100) $rC2.body.data.score
Check "★ 因子和仍等于总分" ((SumPoints $rC2) -eq $rC2.body.data.score) "sum=$(SumPoints $rC2) score=$($rC2.body.data.score)"

# ---------- 7. 售后诚信因子 ----------
Write-Host ""
Write-Host "> 售后诚信因子：只算「被平台裁定支持买家」"
$maxId = Sql 'duoduo_fulfilment' "SELECT COALESCE(MAX(id),1000000) FROM t_after_sale;"
$id1 = [long]$maxId + 1
$id2 = [long]$maxId + 2
$seed1 = "INSERT INTO t_after_sale (id, order_id, order_no, buyer_id, seller_id, type, reason, status, arbitrate_result, arbitrate_note, created_at, updated_at) SELECT $id1, o.id, o.order_no, o.user_id, o.seller_id, 'REFUND_ONLY', '冒烟夹具', 'REFUNDED', 'APPROVE_REFUND', '冒烟夹具', UTC_TIMESTAMP(), UTC_TIMESTAMP() FROM duoduo_trade.t_order o WHERE o.order_no = '$on1';"
$seed2 = "INSERT INTO t_after_sale (id, order_id, order_no, buyer_id, seller_id, type, reason, status, arbitrate_result, arbitrate_note, created_at, updated_at) SELECT $id2, o.id, o.order_no, o.user_id, o.seller_id, 'REFUND_ONLY', '冒烟夹具', 'REFUNDED', 'APPROVE_REFUND', '冒烟夹具', UTC_TIMESTAMP(), UTC_TIMESTAMP() FROM duoduo_trade.t_order o WHERE o.order_no = '$on2';"
Sql 'duoduo_fulfilment' $seed1 | Out-Null
Sql 'duoduo_fulfilment' $seed2 | Out-Null
$seeded = Sql 'duoduo_fulfilment' "SELECT COUNT(*) FROM t_after_sale WHERE order_no IN ('$on1','$on2') AND arbitrate_result = 'APPROVE_REFUND';"
Check "（夹具）已注入 2 笔「被裁定退款」的售后单" ($seeded -eq '2') $seeded
$rC3 = Credit $sellerId $anon
$c3After = Factor $rC3 'AFTERSALE'
Check "★ 售后因子降为 3（半数订单被裁定退款）" ($c3After.points -eq 3) $c3After.points
Check "★ 分数再降：97 → 94" ($rC3.body.data.score -eq 94) $rC3.body.data.score
Check "售后因子 rawValue 点明被裁定退款笔数与总单数" (($c3After.rawValue -match '2') -and ($c3After.rawValue -match '4')) $c3After.rawValue
Check "★ 因子和仍等于总分" ((SumPoints $rC3) -eq $rC3.body.data.score) "sum=$(SumPoints $rC3) score=$($rC3.body.data.score)"

# ---------- 8. 回写与幂等（验收③） ----------
Write-Host ""
Write-Host "> 验收③：回写幂等"
$origCredit = Sql 'duoduo_user' "SELECT credit_score FROM t_user WHERE id = $sellerId;"
Check "（前置）记录卖家 S 回写前的库内信用分" (-not [string]::IsNullOrEmpty($origCredit)) $origCredit
$r = Call 'Post' "$Fulfilment/api/reviews/internal/credits/refresh?userIds=$sellerId" $null $null $H_INTERNAL
Check "履约内部刷新接口（带凭据）返回 200" ($r.status -eq 200) ($r.body | ConvertTo-Json -Compress)
Check "刷新结果 scanned >= 1" ($r.body.data.scanned -ge 1) $r.body.data.scanned
Check "③ 首次刷新 updated=1（分数确实变化并写库）" ($r.body.data.updated -eq 1) $r.body.data.updated
$dbAfter = Sql 'duoduo_user' "SELECT credit_score FROM t_user WHERE id = $sellerId;"
Check "③ 库内 t_user.credit_score 已回写为 94" ("$dbAfter" -eq '94') $dbAfter
$r = Call 'Post' "$Fulfilment/api/reviews/internal/credits/refresh?userIds=$sellerId" $null $null $H_INTERNAL
Check "③ 二次刷新返回 200" ($r.status -eq 200) $r.status
Check "③ 二次刷新 updated=0 / unchanged=1（无变化不写库）" (($r.body.data.updated -eq 0) -and ($r.body.data.unchanged -eq 1)) ($r.body | ConvertTo-Json -Compress)
$dbAfter2 = Sql 'duoduo_user' "SELECT credit_score FROM t_user WHERE id = $sellerId;"
Check "③ 库内分数不变（仍为 94）" ("$dbAfter2" -eq '94') $dbAfter2
$r = Call 'Post' "$User/api/users/internal/$sellerId/credit" $null @{ score = 94; source = 'SMOKE' } $H_INTERNAL
Check "用户域内部回写接口（直连带凭据）返回 200" ($r.status -eq 200) $r.status
Check "③ 用户域幂等：写与库内相同的分数 changed=false" ($r.body.data.changed -eq $false) ($r.body | ConvertTo-Json -Compress)
$r = Call 'Post' "$User/api/users/internal/$sellerId/credit" $null @{ score = 90; source = 'SMOKE' } $H_INTERNAL
Check "③ 用户域写新分数 changed=true 且 previousScore=94" (($r.body.data.changed -eq $true) -and ($r.body.data.previousScore -eq 94)) ($r.body | ConvertTo-Json -Compress)
$r = Call 'Post' "$User/api/users/internal/$sellerId/credit" $null @{ score = 90; source = 'SMOKE' } $H_INTERNAL
$db90 = Sql 'duoduo_user' "SELECT credit_score FROM t_user WHERE id = $sellerId;"
Check "③ 同一新分数再写 changed=false；库内为 90" (($r.body.data.changed -eq $false) -and ("$db90" -eq '90')) "changed=$($r.body.data.changed) db=$db90"

# ---------- 9. 校验与凭据边界 ----------
Write-Host ""
Write-Host "> 校验与凭据边界"
$r = Call 'Post' "$Fulfilment/api/reviews/internal/credits/refresh" $null $null $null
Check "履约内部刷新：无凭据 403" ($r.status -eq 403) $r.status
$r = Call 'Post' "$Fulfilment/api/reviews/internal/credits/refresh" $null $null $H_BAD
Check "履约内部刷新：错误凭据 403" ($r.status -eq 403) $r.status
$r = Call 'Post' "$User/api/users/internal/$sellerId/credit" $null @{ score = 90; source = 'SMOKE' } $null
Check "用户域内部回写：无凭据 403" ($r.status -eq 403) $r.status
$r = Call 'Post' "$Fulfilment/api/reviews/internal/credits/refresh?userIds=abc" $null $null $H_INTERNAL
Check "履约内部刷新：非法 userIds 返回 400（不是 500）" ($r.status -eq 400) "$($r.status)/$($r.body.code)"
$r = Call 'Post' "$User/api/users/internal/$sellerId/credit" $null @{ score = 101; source = 'SMOKE' } $H_INTERNAL
Check "用户域回写：分数 101 被拒（4xx）" ($r.status -ge 400 -and $r.status -lt 500) "$($r.status)/$($r.body.code)"
$r = Call 'Post' "$User/api/users/internal/$sellerId/credit" $null @{ score = -1; source = 'SMOKE' } $H_INTERNAL
Check "用户域回写：分数 -1 被拒（4xx）" ($r.status -ge 400 -and $r.status -lt 500) "$($r.status)/$($r.body.code)"
$r = Call 'Post' "$User/api/users/internal/999999999999/credit" $null @{ score = 90; source = 'SMOKE' } $H_INTERNAL
Check "用户域回写：用户不存在返回 404" ($r.status -eq 404) $r.status
$r = Call 'Post' "$Gateway/api/reviews/internal/credits/refresh" $null $null $null
Check "内部路径未经网关白名单放行（未登录走网关 401）" ($r.status -eq 401) $r.status

# ---------- 10. 清理与复原 ----------
Write-Host ""
Write-Host "> 清理夹具与自证复原"
Sql 'duoduo_fulfilment' "DELETE FROM t_after_sale WHERE order_no IN ('$on1','$on2','$on3','$on4');" | Out-Null
$leftSa = Sql 'duoduo_fulfilment' "SELECT COUNT(*) FROM t_after_sale WHERE order_no IN ('$on1','$on2','$on3','$on4');"
Check "售后夹具已删除（库内复原）" ($leftSa -eq '0') $leftSa
Sql 'duoduo_fulfilment' "DELETE FROM t_review WHERE order_no IN ('$on1','$on2','$on3','$on4');" | Out-Null
$leftRv = Sql 'duoduo_fulfilment' "SELECT COUNT(*) FROM t_review WHERE order_no IN ('$on1','$on2','$on3','$on4');"
Check "评价夹具已删除（库内复原）" ($leftRv -eq '0') $leftRv
foreach ($pair in @($origSellers -split "`n")) {
    if ($pair -match '^(.+?)=(\d+)$') {
        $on = $Matches[1]; $sid = $Matches[2]
        Sql 'duoduo_trade' "UPDATE t_order SET seller_id = $sid WHERE order_no = '$on';" | Out-Null
        Sql 'duoduo_trade' "UPDATE t_settlement s JOIN t_order o ON o.id = s.order_id SET s.seller_id = $sid WHERE o.order_no = '$on';" | Out-Null
    }
}
Check "★ 订单归属已复原为测试前记录值" ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_order WHERE order_no IN ('$on1','$on2','$on3','$on4') AND seller_id = $sellerId;") -eq '0')
Sql 'duoduo_product' "UPDATE t_sku SET stock = $dbStockBefore, frozen_stock = 0 WHERE id = $SKU;" | Out-Null
Check "★ SKU $SKU 库存已复原为测试前的 $dbStockBefore" ("$(Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU;")" -eq "$dbStockBefore")
Check "★ 冻结量已清零" ((Sql 'duoduo_product' "SELECT COALESCE(frozen_stock,0) FROM t_sku WHERE id = $SKU;") -eq '0')
Sql 'duoduo_user' "UPDATE t_user SET credit_score = $origCredit WHERE id = $sellerId;" | Out-Null
Check "★ 卖家 S 信用分已复原为测试前的 $origCredit" ("$(Sql 'duoduo_user' "SELECT credit_score FROM t_user WHERE id = $sellerId;")" -eq "$origCredit")
$rFinal = Credit $sellerId $anon
Check "（复原后）卖家 S 信用分回到基础分 $W_BASE" ($rFinal.body.data.score -eq $W_BASE) $rFinal.body.data.score

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
