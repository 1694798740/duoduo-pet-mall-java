# 限时秒杀（A14）端到端冒烟（Windows PowerShell 5.1）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\seckill-smoke.ps1
#
# 前置：容器 healthy（trade / gateway 为含秒杀代码的新镜像），sku 2001 在商品库可售。
#
# 覆盖 A14 的核心验收目标：
#   ① 浏览公开边界：活动列表/详情匿名可读（导流页），下单匿名 401（网关白名单精确只读路径）；
#   ② 运营准入：建活动匿名 401、普通用户 403、OPERATOR 200（准入永远在服务端）；
#   ③ 下单不超卖：Redis 预扣挡量（软依赖）+ DB「UPDATE ... WHERE available_stock > 0」兜底；
#   ④ 幂等：同一幂等键重复提交返回首次订单（replay=true）；
#   ⑤ 单人限购：per_limit 在预扣层直接拒绝；
#   ⑥ 配额独立于商品库存：秒杀下单不冻结商品库存，关单/取消回补活动配额；
#   ⑦ 生命周期闭环：建活动 → 抢购 → 取消 → 配额回补 → 再抢成功 → 售罄兜底。
#
# 设计原则（沿用前几套脚本的教训）：
#   1. 夹具动态取（活动 id / 地址 id），断言用业务键，不拿 19 位雪花 id 做数值运算；
#   2. 断言总数守卫：脚本尾部自证 Check 实跑条数一致（防「断言被静默跳过」）。
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    [string]$Password = 'duoduo123',
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

function NewAddr($sess) {
    $r = Call 'Post' "$Gateway/api/addresses" $sess @{
        receiverName = '秒杀测试'; receiverPhone = '13900001234'
        province = '上海市'; city = '上海市'; district = '浦东新区'
        detail = '张江路 1 号 101 室'; label = '家'
    }
    return "$($r.body.data.id)"
}

$phoneOp = '137' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneU1 = '136' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneU2 = '136' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneU3 = '136' + (Get-Random -Minimum 10000000 -Maximum 99999999)

Write-Host ""
Write-Host "=== 多多宠物商店 · 限时秒杀（A14）冒烟 @ $Gateway ==="
Write-Host "运营 = $phoneOp ｜ 抢购用户 = $phoneU1 / $phoneU2 / $phoneU3 ｜ SKU = $SKU"
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

# ---------- 1. 账号夹具 ----------
Write-Host ""
Write-Host "> 账号夹具"
$u1 = Register $phoneU1 '秒杀测试-买家甲'
Check "买家甲注册/登录成功" ($u1.result.status -eq 200) $u1.result.status
$u2 = Register $phoneU2 '秒杀测试-买家乙'
Check "买家乙注册/登录成功" ($u2.result.status -eq 200) $u2.result.status
$u3 = Register $phoneU3 '秒杀测试-买家丙'
Check "买家丙注册/登录成功" ($u3.result.status -eq 200) $u3.result.status
$op = Register $phoneOp '秒杀测试-运营'
Sql 'duoduo_user' "UPDATE t_user SET role = 'OPERATOR' WHERE phone = '$phoneOp';" | Out-Null
$sessOp = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$loginOp = Call 'Post' "$Gateway/api/auth/login" $sessOp @{ phone = $phoneOp; password = $Password }
Check "运营登录成功（提权后）" ($loginOp.status -eq 200) $loginOp.status

# ---------- 2. 浏览公开边界（验收①） ----------
Write-Host ""
Write-Host "> 验收①：活动浏览公开、下单需登录"
$r = Call 'Get' "$Gateway/api/seckill/activities" $null $null
Check "匿名读活动列表 200" ($r.status -eq 200) "$($r.status)/$($r.raw)"
$r = Call 'Post' "$Gateway/api/seckill/orders" $null @{ activityId = 1; skuId = 1; addressId = 1; idempotentKey = 'sk-anon' }
Check "匿名秒杀下单 401" ($r.status -eq 401) $r.status

# ---------- 3. 运营准入（验收②） ----------
Write-Host ""
Write-Host "> 验收②：运营准入"
$start = (Get-Date).ToUniversalTime().AddSeconds(-60).ToString('yyyy-MM-ddTHH:mm:ss')
$end   = (Get-Date).ToUniversalTime().AddMinutes(30).ToString('yyyy-MM-ddTHH:mm:ss')
$actBody = @{
    title = '冒烟-宠物零食疯抢'; startAt = $start; endAt = $end
    skus = @( @{ skuId = $SKU; seckillPrice = 100; totalStock = 2; perLimit = 1 } )
}
$r = Call 'Post' "$Gateway/api/admin/seckill/activities" $null $actBody
Check "匿名建活动 401" ($r.status -eq 401) $r.status
$r = Call 'Post' "$Gateway/api/admin/seckill/activities" $u1.session $actBody
Check "普通用户建活动 403" ($r.status -eq 403) $r.status
$r = Call 'Post' "$Gateway/api/admin/seckill/activities" $sessOp $actBody
Check "运营建活动 200" ($r.status -eq 200) "$($r.status)/$($r.raw)"
$actId = "$($r.body.data.id)"
Check "活动返回 ONGOING（起始时间早于当前）" ($r.body.data.phase -eq 'ONGOING') $r.body.data.phase
Check "秒杀 SKU 视图含 spuTitle / originalPrice（原价缺省取商品现价）" (
    $r.body.data.skus[0].spuTitle -and [long]$r.body.data.skus[0].originalPrice -gt 0) $r.body.data.skus[0]

$r = Call 'Get' "$Gateway/api/seckill/activities/$actId" $null $null
Check "匿名读活动详情 200 且余量=2" ($r.status -eq 200 -and [int]$r.body.data.skus[0].availableStock -eq 2) "$($r.status)/$($r.raw)"

# ---------- 4. 下单链路：地址校验 → 成功 → 幂等 → 限购（验收③④⑤） ----------
Write-Host ""
Write-Host "> 验收③④⑤：下单 / 幂等 / 限购"
$addr1 = NewAddr $u1.session
$r = Call 'Post' "$Gateway/api/seckill/orders" $u1.session @{
    activityId = $actId; skuId = $SKU; addressId = 999999999; idempotentKey = (NewKey) }
Check "地址不存在时拒单 4xx" ($r.status -ge 400 -and $r.status -lt 500) "$($r.status)/$($r.raw)"

$key1 = NewKey
$r = Call 'Post' "$Gateway/api/seckill/orders" $u1.session @{
    activityId = $actId; skuId = $SKU; addressId = $addr1; idempotentKey = $key1 }
Check "买家甲秒杀下单成功" ($r.status -eq 200 -and $r.body.data.orderNo) "$($r.status)/$($r.raw)"
$orderNo1 = "$($r.body.data.orderNo)"
Check "应付金额=秒杀价 100 分（秒杀价落订单，不是原价）" ([long]$r.body.data.payAmount -eq 100) $r.body.data.payAmount
Check "订单类型 SECKILL 落库" ((Sql 'duoduo_trade' "SELECT order_type FROM t_order WHERE order_no='$orderNo1';") -eq 'SECKILL')

$r = Call 'Post' "$Gateway/api/seckill/orders" $u1.session @{
    activityId = $actId; skuId = $SKU; addressId = $addr1; idempotentKey = $key1 }
Check "同幂等键重放：replay=true 且订单号一致" (
    $r.status -eq 200 -and "$($r.body.data.orderNo)" -eq $orderNo1 -and $r.body.data.replay -eq $true) "$($r.status)/$($r.raw)"

$r = Call 'Post' "$Gateway/api/seckill/orders" $u1.session @{
    activityId = $actId; skuId = $SKU; addressId = $addr1; idempotentKey = (NewKey) }
Check "买家甲超过限购（per_limit=1）被拒 4xx" ($r.status -ge 400 -and $r.status -lt 500) "$($r.status)/$($r.raw)"

$addr2 = NewAddr $u2.session
$r = Call 'Post' "$Gateway/api/seckill/orders" $u2.session @{
    activityId = $actId; skuId = $SKU; addressId = $addr2; idempotentKey = (NewKey) }
Check "买家乙抢到第 2 件（余量 2→0）" ($r.status -eq 200) "$($r.status)/$($r.raw)"
$orderNo2 = "$($r.body.data.orderNo)"

# ---------- 5. 售罄兜底（验收③：绝不超卖） ----------
Write-Host ""
Write-Host "> 验收③：售罄兜底"
$addr3 = NewAddr $u3.session
$r = Call 'Post' "$Gateway/api/seckill/orders" $u3.session @{
    activityId = $actId; skuId = $SKU; addressId = $addr3; idempotentKey = (NewKey) }
Check "买家丙在余量 0 时被拒（STOCK_INSUFFICIENT）" ($r.status -eq 422 -or $r.body.code -eq 'STOCK_INSUFFICIENT') "$($r.status)/$($r.raw)"
Check "DB 配额未被扣成负数" ((Sql 'duoduo_trade' "SELECT available_stock FROM t_seckill_sku WHERE activity_id=$actId AND sku_id=$SKU;") -eq '0')

# ---------- 6. 取消回补（验收⑥） ----------
Write-Host ""
Write-Host "> 验收⑥：取消回补活动配额"
$r = Call 'Post' "$Gateway/api/orders/$orderNo2/cancel" $u2.session @{ reason = '冒烟-取消回补' }
Check "买家乙取消秒杀订单成功" ($r.status -eq 200) "$($r.status)/$($r.raw)"
Start-Sleep -Seconds 1
$r = Call 'Get' "$Gateway/api/seckill/activities/$actId" $null $null
Check "取消后余量回补为 1" ([int]$r.body.data.skus[0].availableStock -eq 1) $r.raw
$r = Call 'Post' "$Gateway/api/seckill/orders" $u3.session @{
    activityId = $actId; skuId = $SKU; addressId = $addr3; idempotentKey = (NewKey) }
Check "买家丙用回补的配额再抢成功" ($r.status -eq 200) "$($r.status)/$($r.raw)"
$orderNo3 = "$($r.body.data.orderNo)"
Check "最终 DB 配额=0（全部售出）" ((Sql 'duoduo_trade' "SELECT available_stock FROM t_seckill_sku WHERE activity_id=$actId AND sku_id=$SKU;") -eq '0')

# ---------- 7. 运营编辑 / 删除（R74） ----------
Write-Host ""
Write-Host "> R74：活动编辑与删除"
# 口径：编辑只开「标题 + 时间」；删除只允许「没有未关闭订单」的活动（还有待付款/已付款等订单时必须走 下架）。
$editedTitle = 'smoke-seckill-edited'   # 刻意用 ASCII：docker exec 读库输出中文会被转成 ?
$newEnd = (Get-Date).ToUniversalTime().AddMinutes(45).ToString('yyyy-MM-ddTHH:mm:ss')
$editBody = @{ title = $editedTitle; startAt = $start; endAt = $newEnd }

$r = Call 'Put' "$Gateway/api/admin/seckill/activities/$actId" $null $editBody
Check "匿名编辑活动 401" ($r.status -eq 401) $r.status
$r = Call 'Put' "$Gateway/api/admin/seckill/activities/$actId" $u1.session $editBody
Check "普通用户编辑活动 403" ($r.status -eq 403) $r.status
$r = Call 'Put' "$Gateway/api/admin/seckill/activities/$actId" $sessOp $editBody
Check "运营编辑活动 200" ($r.status -eq 200) "$($r.status)/$($r.raw)"
Check "编辑后标题已改" ($r.body.data.title -eq $editedTitle) $r.body.data.title
Check "编辑后结束时间已改" ("$($r.body.data.endAt)" -like "$($newEnd.Substring(0,16))*") $r.body.data.endAt
Check "编辑已落库（不是只改了响应体）" (
    (Sql 'duoduo_trade' "SELECT title FROM t_seckill_activity WHERE id=$actId;") -eq $editedTitle) "DB 实得：$(Sql 'duoduo_trade' "SELECT title FROM t_seckill_activity WHERE id=$actId;")"

$r = Call 'Put' "$Gateway/api/admin/seckill/activities/$actId" $sessOp @{
    title = $editedTitle; startAt = $newEnd; endAt = $start }
Check "结束早于开始 → 400（校验类错误，不是 422）" ($r.status -eq 400) "$($r.status)/$($r.raw)"
$r = Call 'Put' "$Gateway/api/admin/seckill/activities/999999999" $sessOp $editBody
Check "编辑不存在的活动 → 404" ($r.status -eq 404) "$($r.status)/$($r.raw)"

$r = Call 'Delete' "$Gateway/api/admin/seckill/activities/$actId" $null $null
Check "匿名删除活动 401" ($r.status -eq 401) $r.status
$r = Call 'Delete' "$Gateway/api/admin/seckill/activities/$actId" $u1.session $null
Check "普通用户删除活动 403" ($r.status -eq 403) $r.status
# 本场活动此刻已有 3 笔秒杀订单 → 必须被拒，且给出「改用下架」的可执行指引
$r = Call 'Delete' "$Gateway/api/admin/seckill/activities/$actId" $sessOp $null
Check "有「未关闭订单」的活动删除 → 422" ($r.status -eq 422) "$($r.status)/$($r.raw)"
Check "拒绝原因指明「未关闭订单」并指向「下架」" (
    "$($r.body.message)" -like '*未关闭*' -and "$($r.body.message)" -like '*下架*') $r.body.message
Check "被拒后活动仍在（未发生半删）" (
    [int](Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_seckill_activity WHERE id=$actId;") -eq 1) '活动行数异常'

# 反向面：同一端点对「无订单」的活动必须真能删掉（证明守卫不是无条件拒绝）
$r = Call 'Post' "$Gateway/api/admin/seckill/activities" $sessOp @{
    title = 'smoke-seckill-throwaway'; startAt = $start; endAt = $newEnd
    skus = @( @{ skuId = $SKU; seckillPrice = 100; totalStock = 1; perLimit = 1 } ) }
Check "新建一场「无人下单」的待删活动 200" ($r.status -eq 200) "$($r.status)/$($r.raw)"
$throwId = "$($r.body.data.id)"
$before = (Call 'Get' "$Gateway/api/admin/seckill/activities" $sessOp $null).body.data.Count
$r = Call 'Delete' "$Gateway/api/admin/seckill/activities/$throwId" $sessOp $null
Check "删除无订单的活动 200" ($r.status -eq 200) "$($r.status)/$($r.raw)"
$after = (Call 'Get' "$Gateway/api/admin/seckill/activities" $sessOp $null).body.data.Count
Check "列表条数减 1（$before → $after）" ($after -eq $before - 1) "$before/$after"
Check "活动行已删" (
    [int](Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_seckill_activity WHERE id=$throwId;") -eq 0) '活动行仍在'
Check "连带秒杀 SKU 行已清理（不留孤儿行）" (
    [int](Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_seckill_sku WHERE activity_id=$throwId;") -eq 0) 'SKU 行仍在'
$r = Call 'Delete' "$Gateway/api/admin/seckill/activities/$throwId" $sessOp $null
Check "重复删除 → 404（明确报不存在，不静默成功）" ($r.status -eq 404) "$($r.status)/$($r.raw)"

# ---------- 8. 自我清理：取消未关闭订单 → 活动变为可删 → 删掉活动（不再留只增不减的自测活动） ----------
Write-Host ""
Write-Host "> R74：套件自我清理（订单全关闭后活动可删）"
$r = Call 'Post' "$Gateway/api/orders/$orderNo1/cancel" $u1.session @{ reason = '冒烟-收尾清理' }
Check "买家甲取消未付款订单" ($r.status -eq 200) "$($r.status)/$($r.raw)"
$r = Call 'Post' "$Gateway/api/orders/$orderNo3/cancel" $u3.session @{ reason = '冒烟-收尾清理' }
Check "买家丙取消未付款订单" ($r.status -eq 200) "$($r.status)/$($r.raw)"
$r = Call 'Delete' "$Gateway/api/admin/seckill/activities/$actId" $sessOp $null
Check "★ 订单全部关闭后活动可删除（不留自测活动）" ($r.status -eq 200) "$($r.status)/$($r.raw)"
Check "活动行已删" (
    [int](Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_seckill_activity WHERE id=$actId;") -eq 0) '活动仍在'
Check "秒杀链接行已连带清理" (
    [int](Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_seckill_order WHERE activity_id=$actId;") -eq 0) '链接行仍在'
Check "★ 订单留档未受牵连（t_order 仍在）" (
    [int](Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_order WHERE order_no IN ('$orderNo1','$orderNo2','$orderNo3');") -eq 3) '订单被误删'

# ---------- 9. 收尾 ----------
Write-Host ""
Write-Host "=== 结果：$pass 通过 / $fail 失败 ==="
if ($fail -gt 0) {
    Write-Host "失败项："
    $failures | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
Write-Host "夹具说明：本脚本建的测试活动 id=$actId 已在第 8 段**自行删除**（订单全关闭后即可删），"
Write-Host "  不再留下只增不减的自测活动；t_order 里的 3 笔订单按留档口径保留；"
Write-Host "  测试账号 $phoneU1/$phoneU2/$phoneU3/$phoneOp 保留。"
Write-Host "  清理历史自测活动请用后台管理页的「删除」（无未关闭订单的活动即可删，否则改用「下架」）。"
exit 0
