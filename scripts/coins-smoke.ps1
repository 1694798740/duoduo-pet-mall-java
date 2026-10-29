# 用户服务 · 多多币 P1 端到端冒烟（Windows PowerShell 5.1）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\coins-smoke.ps1
#
# 前置：容器全部 healthy，且 duoduo-user / duoduo-trade / duoduo-gateway 已用本轮镜像重建，
#       docs/sql/incremental/63-duoduo-coins.sql 已应用（用户域四表 + 交易域订单/结算新列）。
#
# 验收口径（docs/PLAN.md §7 R-P1 · 第一百二十六轮）：
#   1. 运营发币：后台人工调整入账（带审计），余额 / 累计 / 流水可查；
#   2. 预览：币在券后抵扣、单笔上限 50%（cap = 券后/2 向下取整）、开关关闭不影响应付；
#   3. 下单冻结：真实扣减余额（先到期先扣批次），幂等重放不重复扣；
#   4. 取消释放：余额原路退回，冻结单到 RELEASED；
#   5. 支付确认：冻结 → DEDUCTED，结算托管 = 实付现金 + 币抵扣，补贴单列；
#   6. 退款退币：余额回补、批次退回，对账零问题；
#   7. T+7 返币：完成满 7 天的订单按现金实付商品金额 1:1 发币（DB 夹具回拨 finished_at）；
#   8. 边界：未登录 401、买家访问运营端点 403。
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    [string]$Password = 'duoduo123',
    [string]$InternalToken = 'duoduo-local-dev-internal-token'
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
. (Join-Path $PSScriptRoot 'lib\platform-operator.ps1')

function Call([string]$method, [string]$uri, $session, $bodyObj, [hashtable]$headers) {
    $params = @{ Uri = $uri; Method = $method; UseBasicParsing = $true; TimeoutSec = 25 }
    if ($session) { $params.WebSession = $session }
    if ($headers -and $headers.ContainsKey('X-Internal-Token') -and $headers['X-Internal-Token'] -eq 'internal') {
        $headers = @{} + $headers
        $headers['X-Internal-Token'] = (New-InternalToken -Method $method -Path ([System.Uri]$uri).AbsolutePath)
    }
    if ($headers) { $params.Headers = $headers }
    if ($null -ne $bodyObj) {
        $params.Body = ($bodyObj | ConvertTo-Json -Compress -Depth 6)
        $params.ContentType = 'application/json; charset=utf-8'
    }
    try {
        $r = Invoke-WebRequest @params
        return @{ status = [int]$r.StatusCode; body = ($r.Content | ConvertFrom-Json) }
    } catch {
        $code = 0; $body = $null
        try { $code = [int]$_.Exception.Response.StatusCode.value__ } catch { }
        try { if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $body = $_.ErrorDetails.Message | ConvertFrom-Json } } catch { }
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
                if ($t) { $body = $t | ConvertFrom-Json }
            } catch { }
        }
        return @{ status = $code; body = $body }
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
$H_INTERNAL = @{ 'X-Internal-Token' = 'internal' }

$phoneA = '135' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$SKU_A = 2001
$recv = @{ receiverName = '小币'; receiverPhone = '13500000000'; receiverAddress = '上海市浦东新区币桥路 1 号' }

Write-Host ""
Write-Host "=== 多多宠物商店 · 多多币 P1 冒烟 @ $Gateway ==="
Write-Host "买家 A = $phoneA"
Write-Host ""

# ---------- 0. 网关就绪 + 建表前置 ----------
Write-Host "> 网关就绪与前置检查"
$ready = $false
for ($i = 0; $i -lt 40; $i++) {
    try {
        $h = Invoke-WebRequest -Uri "$Gateway/actuator/health" -UseBasicParsing -TimeoutSec 5
        if ($h.StatusCode -eq 200) { $ready = $true; break }
    } catch { Start-Sleep -Seconds 1 }
}
Check "网关 /actuator/health 就绪" $ready
if (-not $ready) { Write-Host "网关未就绪，终止"; exit 1 }
$coinTables = Sql 'duoduo_user' "SHOW TABLES LIKE 't_coin_account';"
Check "前置：t_coin_account 已存在（迁移 63 已应用）" ($coinTables -eq 't_coin_account') $coinTables
$freezeTable = Sql 'duoduo_user' "SHOW TABLES LIKE 't_coin_freeze';"
Check "前置：t_coin_freeze 已存在" ($freezeTable -eq 't_coin_freeze') $freezeTable

# ---------- 1. 买家与运营发币 ----------
Write-Host ""
Write-Host "> 运营发币与账户可见性"
$regA = Register $phoneA '多多币冒烟甲'
Check "买家 A 注册/登录成功（200）" ($regA.result.status -eq 200) $regA.result.status
$sessA = $regA.session

$userIdA = Sql 'duoduo_user' "SELECT id FROM t_user WHERE phone = '$phoneA' LIMIT 1;"
Check "（准备）取到买家 A 的 userId" (-not [string]::IsNullOrEmpty($userIdA)) $userIdA

$op = New-PlatformOperatorSession -Gateway $Gateway -Password $Password
$r = Call 'Post' "$Gateway/api/admin/coins/adjust" $op.session @{ userId = [long]$userIdA; amount = 10000; reason = '冒烟发币：一百元等额多多币' }
Check "运营人工调整发币 10000（200）" ($r.status -eq 200) "$($r.status) $($r.body.message)"
Check "调整后余额返回 10000" ([long]$r.body.data.balance -eq 10000L) $r.body.data.balance

$r = Call 'Get' "$Gateway/api/coins/me" $sessA $null
Check "买家 A 查询我的多多币（200，网关路由已放行 /api/coins）" ($r.status -eq 200) $r.status
Check "余额 = 10000" ([long]$r.body.data.balance -eq 10000L) $r.body.data.balance
Check "累计获得 = 10000" ([long]$r.body.data.totalEarned -eq 10000L) $r.body.data.totalEarned

$r = Call 'Get' "$Gateway/api/coins/ledger?page=1&pageSize=10" $sessA $null
Check "买家 A 查询币流水（200）" ($r.status -eq 200) $r.status
# R-P1 契约：分页端点返回 PageResult（items/total/page/pageCount），不是裸数组
$adjRow = @($r.body.data.items) | Where-Object { $_.direction -eq 'ADJUST' -and $_.bizType -eq 'ADMIN_ADJUST' }
Check "流水含 ADMIN_ADJUST 记账" ($null -ne $adjRow) ($r.body.data | ConvertTo-Json -Compress -Depth 4)
Check "流水分页元数据齐全（total/page/pageCount）" `
    ($null -ne $r.body.data.total -and $r.body.data.page -ge 1 -and $null -ne $r.body.data.pageCount) `
    ($r.body.data | ConvertTo-Json -Compress -Depth 3)

# ---------- 2. 边界与运营端 ----------
Write-Host ""
Write-Host "> 边界与运营端可见性"
$r = Call 'Get' "$Gateway/api/coins/me" $null $null
Check "未登录查询我的多多币 → 401" ($r.status -eq 401) $r.status
$r = Call 'Get' "$Gateway/api/admin/coins/summary" $sessA $null
Check "买家访问运营汇总端点 → 403" ($r.status -eq 403) $r.status
$r = Call 'Get' "$Gateway/api/admin/coins/summary" $op.session $null
Check "运营查询全站汇总（200）" ($r.status -eq 200) $r.status
Check "全站负债余额 ≥ 10000" ([long]$r.body.data.totalBalance -ge 10000L) $r.body.data.totalBalance
$r = Call 'Post' "$Gateway/api/admin/coins/adjust" $op.session @{ userId = [long]$userIdA; amount = 0; reason = '零调整' }
Check "运营零数量调整被拒（400）" ($r.status -eq 400) $r.status
$r = Call 'Post' "$Gateway/api/admin/coins/adjust" $op.session @{ userId = [long]$userIdA; amount = 5; reason = '' }
Check "运营空原因调整被拒（400）" ($r.status -eq 400) $r.status

# ---------- 3. 预览：50% 上限与开关 ----------
Write-Host ""
Write-Host "> 预览试算（1 币 = 1 分，单笔上限 = 券后 50%）"
$r = Call 'Get' "$Gateway/api/products/internal/skus/batch?ids=$SKU_A" $null $null $H_INTERNAL
$P1 = [long]$r.body.data[0].price
$S1 = [int]$r.body.data[0].availableStock
$cap = [long][math]::Floor($P1 / 2)
Write-Host "  2001 单价 $P1 分，可售 $S1，币抵上限 $cap 分"
Check "夹具前提：2001 可售 ≥ 3" ($S1 -ge 3) $S1

$r = Call 'Post' "$Gateway/api/orders/preview" $sessA (@{ buySkuId = $SKU_A; buyQuantity = 1; useCoins = $true })
Check "预览（用币）返回 200" ($r.status -eq 200) $r.status
Check "预览显示币余额 10000" ([long]$r.body.data.coinBalance -eq 10000L) $r.body.data.coinBalance
Check "预览币抵扣 = 上限（券后 50%）" ([long]$r.body.data.coinUsed -eq $cap) "$($r.body.data.coinUsed) vs cap=$cap"
Check "预览应付 = 单价 − 币抵" ([long]$r.body.data.payAmount -eq ($P1 - $cap)) "$($r.body.data.payAmount) vs $($P1 - $cap)"
$previewPay = [long]$r.body.data.payAmount
$previewCoin = [long]$r.body.data.coinUsed
$pv1 = @($r.body.data.orders)[0]
Check "预览子单也带币抵明细" ([long]$pv1.coinUsed -eq $previewCoin) $pv1.coinUsed

$r = Call 'Post' "$Gateway/api/orders/preview" $sessA (@{ buySkuId = $SKU_A; buyQuantity = 1; useCoins = $false })
Check "预览（不用币）币抵为 0" ([long]$r.body.data.coinUsed -eq 0) $r.body.data.coinUsed
Check "预览（不用币）应付 = 单价" ([long]$r.body.data.payAmount -eq $P1) $r.body.data.payAmount

# ---------- 4. 下单冻结与幂等 ----------
Write-Host ""
Write-Host "> 下单冻结（真实扣余额）与幂等重放"
$key1 = NewKey
$r = Call 'Post' "$Gateway/api/orders" $sessA ($recv + @{ buySkuId = $SKU_A; buyQuantity = 1; useCoins = $true; idempotentKey = $key1 })
Check "下单（用币）返回 200" ($r.status -eq 200) $r.status
$order1 = @($r.body.data.orders)[0]
$on1 = $order1.orderNo
$used1 = [long]$order1.coinUsed
Check "订单带币抵快照 coinUsed = 预览值" ($used1 -eq $previewCoin) "$used1 vs $previewCoin"
Check "订单应付 = 单价 − 币抵" ([long]$order1.payAmount -eq ($P1 - $used1)) $order1.payAmount
Check "订单带币抵金额 coinDeductAmount" ([long]$order1.coinDeductAmount -eq $used1) $order1.coinDeductAmount

$balAfterFreeze = [long](Sql 'duoduo_user' "SELECT balance FROM t_coin_account WHERE user_id = $userIdA;")
Check "下单后余额被冻结扣减（10000 − $used1）" ($balAfterFreeze -eq (10000 - $used1)) $balAfterFreeze
$freezeRow = Sql 'duoduo_user' "SELECT status, coins FROM t_coin_freeze WHERE order_no = '$on1';"
Check "冻结单 FROZEN 且数量一致" ("$freezeRow" -match "FROZEN`t$used1") $freezeRow
$freezeLots = Sql 'duoduo_user' "SELECT COUNT(*) FROM t_coin_freeze_lot fl JOIN t_coin_freeze f ON fl.freeze_id = f.id WHERE f.order_no = '$on1';"
Check "冻结批次明细已落（≥1 条）" ([int]$freezeLots -ge 1) $freezeLots

$r = Call 'Post' "$Gateway/api/orders" $sessA ($recv + @{ buySkuId = $SKU_A; buyQuantity = 1; useCoins = $true; idempotentKey = $key1 })
Check "同幂等键重放返回同一订单" (@($r.body.data.orders)[0].orderNo -eq $on1) @($r.body.data.orders)[0].orderNo
$balReplay = [long](Sql 'duoduo_user' "SELECT balance FROM t_coin_account WHERE user_id = $userIdA;")
Check "幂等重放不再扣币" ($balReplay -eq $balAfterFreeze) $balReplay

# ---------- 5. 取消释放 ----------
Write-Host ""
Write-Host "> 取消订单释放冻结"
$r = Call 'Post' "$Gateway/api/orders/$on1/cancel" $sessA @{ reason = '冒烟：不想买了' }
Check "取消订单返回 200" ($r.status -eq 200) $r.status
$balAfterCancel = [long](Sql 'duoduo_user' "SELECT balance FROM t_coin_account WHERE user_id = $userIdA;")
Check "取消后余额退回 10000" ($balAfterCancel -eq 10000L) $balAfterCancel
$releasedRow = Sql 'duoduo_user' "SELECT status FROM t_coin_freeze WHERE order_no = '$on1';"
Check "冻结单到 RELEASED" ($releasedRow -eq 'RELEASED') $releasedRow

# ---------- 6. 再下单 → 支付确认扣减与结算补贴 ----------
Write-Host ""
Write-Host "> 支付成功：冻结转扣减、结算补贴单列"
$key2 = NewKey
$r = Call 'Post' "$Gateway/api/orders" $sessA ($recv + @{ buySkuId = $SKU_A; buyQuantity = 1; useCoins = $true; idempotentKey = $key2 })
Check "第二单下单（用币）返回 200" ($r.status -eq 200) $r.status
$order2 = @($r.body.data.orders)[0]
$on2 = $order2.orderNo
$used2 = [long]$order2.coinUsed
$pay2 = [long]$order2.payAmount
Check "第二单币抵 = 上限" ($used2 -eq $cap) "$used2 vs $cap"

$r = Call 'Post' "$Gateway/api/orders/$on2/pay" $sessA @{ channel = 'SANDBOX'; idempotentKey = (NewKey) }
Check "第二单创建支付（SANDBOX）" ($r.status -eq 200) $r.status
$payNo2 = $r.body.data.payNo
$r = Call 'Post' "$Gateway/api/payments/sandbox/$payNo2" $sessA $null
Check "沙箱支付成功（200）" ($r.status -eq 200) $r.status
Check "订单转为 PAID" ($r.body.data.orderStatus -eq 'PAID') $r.body.data.orderStatus

$deductedRow = Sql 'duoduo_user' "SELECT status FROM t_coin_freeze WHERE order_no = '$on2';"
Check "冻结单到 DEDUCTED（支付确认扣减）" ($deductedRow -eq 'DEDUCTED') $deductedRow
$balAfterPay = [long](Sql 'duoduo_user' "SELECT balance FROM t_coin_account WHERE user_id = $userIdA;")
# R-P1 第三轮：新用户首单支付成功会额外触发「首单」任务 +50 币（事件幂等，只发一次）；
# 这里把 50 计入期望，否则会把新功能误判为「余额扣错了」。
Check "支付后余额保持扣减态（10000 − $used2 + 首单任务 50）" ($balAfterPay -eq (10000 - $used2 + 50)) $balAfterPay

$orderId2 = Sql 'duoduo_trade' "SELECT id FROM t_order WHERE order_no = '$on2' LIMIT 1;"
$settleRow = Sql 'duoduo_trade' "SELECT amount, coin_subsidy_amount FROM t_settlement WHERE order_id = $orderId2;"
$expectCustody = "$($pay2 + $used2)`t$used2"
Check "结算托管金额 = 实付现金 + 币抵，补贴单列 = 币抵" ("$settleRow" -eq $expectCustody) "$settleRow vs $expectCustody"

# ---------- 7. 退款退币 ----------
Write-Host ""
Write-Host "> 未发货退款：钱与币一起退"
$r = Call 'Post' "$Gateway/api/orders/$on2/refund" $sessA @{ reason = '冒烟：测试退款退币' }
Check "买家申请退款返回 200" ($r.status -eq 200) $r.status
Start-Sleep -Seconds 1
$balAfterRefund = [long](Sql 'duoduo_user' "SELECT balance FROM t_coin_account WHERE user_id = $userIdA;")
Check "退款后币余额退回 10000 + 首单任务 50" ($balAfterRefund -eq 10050L) $balAfterRefund
$refundLedger = Sql 'duoduo_user' "SELECT COUNT(*) FROM t_coin_ledger WHERE user_id = $userIdA AND direction = 'REFUND' AND biz_type = 'ORDER_REFUND' AND biz_no = '$on2';"
Check "退款退币流水恰好 1 条（幂等键唯一）" ($refundLedger -eq '1') $refundLedger

# ---------- 8. T+7 返币（DB 夹具回拨完成时间） ----------
Write-Host ""
Write-Host "> T+7 返币（夹具把第三单完成时间回拨 8 天）"
$key3 = NewKey
$r = Call 'Post' "$Gateway/api/orders" $sessA ($recv + @{ buySkuId = $SKU_A; buyQuantity = 1; useCoins = $false; idempotentKey = $key3 })
Check "第三单下单（不用币）返回 200" ($r.status -eq 200) $r.status
$order3 = @($r.body.data.orders)[0]
$on3 = $order3.orderNo
$pay3 = [long]$order3.payAmount
Check "第三单未用币" ([long]$order3.coinUsed -eq 0) $order3.coinUsed

$r = Call 'Post' "$Gateway/api/orders/$on3/pay" $sessA @{ channel = 'SANDBOX'; idempotentKey = (NewKey) }
$payNo3 = $r.body.data.payNo
$r = Call 'Post' "$Gateway/api/payments/sandbox/$payNo3" $sessA $null
Check "第三单支付成功" ($r.status -eq 200) $r.status

$orderId3 = Sql 'duoduo_trade' "SELECT id FROM t_order WHERE order_no = '$on3' LIMIT 1;"
Sql 'duoduo_trade' "UPDATE t_order SET status = 'COMPLETED', finished_at = DATE_SUB(NOW(6), INTERVAL 8 DAY) WHERE id = $orderId3;" | Out-Null
Sql 'duoduo_trade' "UPDATE t_settlement SET status = 'RELEASED', release_at = DATE_SUB(NOW(6), INTERVAL 8 DAY), released_at = NOW(6) WHERE order_id = $orderId3 AND status = 'CUSTODY';" | Out-Null
$fixtureRow = Sql 'duoduo_trade' "SELECT status FROM t_order WHERE id = $orderId3;"
Check "（夹具）第三单已置为 COMPLETED（完成于 8 天前）" ($fixtureRow -eq 'COMPLETED') $fixtureRow

$balBeforeEarn = $balAfterRefund
$r = Call 'Post' "$Gateway/api/orders/internal/sweep" $sessA $null $H_INTERNAL
Check "内部触发 sweep 返回 200" ($r.status -eq 200) $r.status
$earnExpect = [long][math]::Floor($pay3 / 100)
Check "sweep 本轮完成返币 ≥ 1 单" ([int]$r.body.data.coinsEarned -ge 1) $r.body.data.coinsEarned

$earnedAt = Sql 'duoduo_trade' "SELECT coin_earned_at FROM t_order WHERE id = $orderId3;"
Check "订单游标 coin_earned_at 已落（防重扫）" (-not [string]::IsNullOrEmpty("$earnedAt")) $earnedAt
$earnCoin = Sql 'duoduo_trade' "SELECT coin_earn_amount FROM t_order WHERE id = $orderId3;"
Check "返币数 = 现金实付商品金额元数（$earnExpect 币）" ([long]$earnCoin -eq $earnExpect) $earnCoin

$balAfterEarn = [long](Sql 'duoduo_user' "SELECT balance FROM t_coin_account WHERE user_id = $userIdA;")
Check "返币后余额 = $balBeforeEarn + $earnExpect" ($balAfterEarn -eq ($balBeforeEarn + $earnExpect)) $balAfterEarn
$earnLedger = Sql 'duoduo_user' "SELECT COUNT(*) FROM t_coin_ledger WHERE user_id = $userIdA AND direction = 'EARN' AND biz_type = 'ORDER_EARN' AND biz_no = '$on3';"
Check "返币流水恰好 1 条" ($earnLedger -eq '1') $earnLedger

$r = Call 'Post' "$Gateway/api/orders/internal/sweep" $sessA $null $H_INTERNAL
Check "再次 sweep 不重复返币（coinsEarned 不含本单）" ($r.status -eq 200) $r.status
$earnCoinAgain = Sql 'duoduo_user' "SELECT COUNT(*) FROM t_coin_ledger WHERE user_id = $userIdA AND direction = 'EARN' AND biz_type = 'ORDER_EARN' AND biz_no = '$on3';"
Check "返币流水仍为 1 条（幂等）" ($earnCoinAgain -eq '1') $earnCoinAgain

# ---------- 9. 对账 ----------
Write-Host ""
Write-Host "> 对账守卫"
# 经网关的内部端点也要带登录会话（网关按会话鉴权、服务按内部令牌鉴权，两层都要过）
$r = Call 'Get' "$Gateway/api/coins/internal/recon" $sessA $null $H_INTERNAL
Check "币对账返回 200" ($r.status -eq 200) $r.status
$reconIssues = @($r.body.data.issues)
Check "对账零问题（余额 = 批次合计、流水快照一致）" ($reconIssues.Count -eq 0) ($reconIssues -join '; ')

# ---------- 10. 收尾：复原夹具消耗的库存 ----------
Write-Host ""
Write-Host "> 收尾（第三单已完成不回补库存，显式 +1 保证脚本可重复执行）"
Sql 'duoduo_product' "UPDATE t_sku SET stock = stock + 1 WHERE id = $SKU_A;" | Out-Null
$stockNow = Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU_A;"
Check "库存已复原（= $S1）" ([int]$stockNow -eq $S1) $stockNow

Write-Host ""
Write-Host "=== 结果：$pass 通过 / $fail 失败 ==="
if ($fail -gt 0) {
    Write-Host "失败项："
    $failures | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
exit 0
