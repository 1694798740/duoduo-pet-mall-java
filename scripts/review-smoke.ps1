# 履约服务 · 评价与晒单端到端冒烟（Windows PowerShell 5.1）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\review-smoke.ps1
#
# 前置：容器全部 healthy（docker compose up -d），且已 mvn package + docker compose build 出最新镜像。
#
# 覆盖：
#   1. 公开读 / 登录写边界：未登录看评价 200、未登录取我的评价 401；
#   2. 内部接口保护：直连履约/交易/用户服务的内部接口，无凭据或错凭据一律 403；
#   3. 订单事实裁剪：内部接口返回**不含**收货人信息（服务间最小必要字段）；
#   4. 评价资格：未完成订单 422、别人的订单 403、不在订单里的商品 422；
#   5. 参数边界：评分越界 422、图片非 http/https 422、超量图片 422；
#   6. 一单一商品一评：重复提交 409；补评另一件商品成功；
#   7. 匿名展示：昵称脱敏为「匿名用户」，真实昵称不出现；
#   8. 追评：一次成功、二次 409；
#   9. 聚合口径：平均分 / 好评率 / 星级分布与写入值一致；
#  10. 夹具自建自毁：结尾删除本次评价并自证库内复原。
#
# 设计原则（沿用前几套脚本的教训）：
#   1. 夹具从接口动态取（SKU 与价格不硬编码）；
#   2. 凡改库必先记录、结尾自证复原；
#   3. 中文断言一律走接口读回（docker exec mysql 控制台是 GBK，直接比中文会假失败）。
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    [string]$Trade = 'http://127.0.0.1:8083',
    [string]$Fulfilment = 'http://127.0.0.1:8084',
    [string]$User = 'http://127.0.0.1:8081',
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

. (Join-Path $PSScriptRoot 'lib\internal-token.ps1')

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
                # 跨 PS 版本：PS5.1 用 GetResponseStream，pwsh 7（CI Linux）用 Content.ReadAsStringAsync
                $resp = $_.Exception.Response
                $raw = $null
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
$buyerPhone = '136' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$otherPhone = '137' + (Get-Random -Minimum 10000000 -Maximum 99999999)

Write-Host ""
Write-Host "=== 多多宠物商店 · 履约服务（评价与晒单）冒烟 @ $Gateway ==="
Write-Host "买家 = $buyerPhone ｜ 旁观者 = $otherPhone"
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

# ---------- 1. 夹具：SKU 快照 ----------
Write-Host ""
Write-Host "> 夹具（从接口动态取）"
$batch = Call 'Get' "$Gateway/api/products/internal/skus/batch?ids=$SKU" $null $null $H_INTERNAL
$snap = @($batch.body.data)[0]
Check "取到 SKU $SKU 快照" ($null -ne $snap -and $snap.skuId -eq $SKU) ($batch.body | ConvertTo-Json -Compress)
$SPU = [long]$snap.spuId
$PRICE = [long]$snap.price
$STOCK0 = [int]$snap.availableStock
Write-Host "  SKU $SKU 属 SPU $SPU，单价 $PRICE，可售 $STOCK0"
if ($STOCK0 -lt 2) {
    Check "夹具前提：SKU $SKU 可售 ≥ 2（够本脚本消耗）" $false $STOCK0
    Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
    exit 1
}
Check "夹具前提：SKU $SKU 可售 ≥ 2" ($STOCK0 -ge 2) $STOCK0

# ---------- 2. 账号与订单 ----------
Write-Host ""
Write-Host "> 准备一笔已完成订单（下单 → 支付 → 发货 → 确认收货）"
$buyer = Register $buyerPhone '评价测试买家'
Check "买家注册/登录成功" ($buyer.result.status -eq 200) $buyer.result.status
$sessB = $buyer.session
$other = Register $otherPhone '评价测试旁观者'
Check "旁观者注册/登录成功" ($other.result.status -eq 200) $other.result.status
$sessO = $other.session

$r = Call 'Post' "$Gateway/api/cart/items" $sessB @{ skuId = $SKU; quantity = 2 }
Check "（准备）加购 SKU $SKU × 2" ($r.status -eq 200) $r.status
$r = Call 'Post' "$Gateway/api/orders" $sessB @{
    receiverName = '小林'; receiverPhone = '13900000000'; receiverAddress = '上海市浦东新区张江路 1 号'
    idempotentKey = (NewKey)
}
Check "（准备）下单返回 200" ($r.status -eq 200) ($r.body | ConvertTo-Json -Compress)
$order = @($r.body.data.orders)[0]
$orderNo = $order.orderNo
Check "（准备）订单待付款" ($order.status -eq 'PENDING_PAYMENT') $order.status

$r = Call 'Post' "$Gateway/api/orders/$orderNo/pay" $sessB @{ channel = 'MOCK'; idempotentKey = (NewKey) }
Check "（准备）支付成功（MOCK 即时）" ($r.status -eq 200 -and $r.body.data.orderStatus -eq 'PAID') ($r.body | ConvertTo-Json -Compress)
$r = Call 'Post' "$Trade/api/orders/$orderNo/ship" $null @{ logisticsCompany = '顺丰速运'; logisticsNo = 'SF' + (Get-Random) } $H_INTERNAL
Check "（准备）内部凭据发货 200" ($r.status -eq 200 -and $r.body.data.status -eq 'SHIPPED') ($r.body | ConvertTo-Json -Compress)
$r = Call 'Post' "$Gateway/api/orders/$orderNo/confirm" $sessB $null
Check "（准备）确认收货 → COMPLETED" ($r.status -eq 200 -and $r.body.data.status -eq 'COMPLETED') ($r.body | ConvertTo-Json -Compress)

# 第二笔：已付款但未完成（用于「未完成不可评价」）
$r = Call 'Post' "$Gateway/api/cart/items" $sessB @{ skuId = $SKU; quantity = 1 }
$r = Call 'Post' "$Gateway/api/orders" $sessB @{
    receiverName = '小林'; receiverPhone = '13900000000'; receiverAddress = '上海市浦东新区张江路 1 号'
    idempotentKey = (NewKey)
}
$pendingNo = @($r.body.data.orders)[0].orderNo
$r = Call 'Post' "$Gateway/api/orders/$pendingNo/pay" $sessB @{ channel = 'MOCK'; idempotentKey = (NewKey) }
Check "（准备）第二笔订单已付款未收货" ($r.status -eq 200 -and $r.body.data.orderStatus -eq 'PAID') $r.body.data.orderStatus

# ---------- 3. 内部接口保护与字段裁剪 ----------
Write-Host ""
Write-Host "> 内部接口保护与字段裁剪"
# 注意：必须直连**用户服务**（8081）。连错端口拿到的是「无此路由」的 500，
# 会让人误判成过滤器的锅 —— 断言失败先确认打的是哪个服务（I-13 的同类教训）
$r = Call 'Get' "$User/api/users/internal/briefs?ids=1" $null $null
Check "直连用户域内部接口无凭据 403" ($r.status -eq 403) $r.status
$r = Call 'Get' "$User/api/users/internal/briefs?ids=1" $null $null @{ 'X-Internal-Token' = 'wrong-token' }
Check "直连用户域内部接口错凭据 403" ($r.status -eq 403) $r.status
$r = Call 'Get' "$Trade/api/orders/internal/$orderNo/fact" $null $null
Check "直连交易域订单事实无凭据 403" ($r.status -eq 403) $r.status
$r = Call 'Get' "$Trade/api/orders/internal/$orderNo/fact" $null $null $H_INTERNAL
Check "带凭据可读订单事实" ($r.status -eq 200 -and $r.body.data.orderNo -eq $orderNo) $r.status
$factJson = $r.body.data | ConvertTo-Json -Compress -Depth 6
Check "订单事实不含收货人姓名（服务间最小必要字段）" (-not ($factJson -match 'receiverName')) 'receiverName 出现在响应里'
Check "订单事实不含收货人电话" (-not ($factJson -match 'receiverPhone')) 'receiverPhone 出现在响应里'
Check "订单事实不含收货地址" (-not ($factJson -match 'receiverAddress')) 'receiverAddress 出现在响应里'

# ---------- 4. 公开读 / 登录写边界 ----------
Write-Host ""
Write-Host "> 公开读 / 登录写边界"
$anon = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$r = Call 'Get' "$Gateway/api/reviews/product/$SPU/summary" $anon $null $null
Check "未登录可看评分汇总（200）" ($r.status -eq 200) $r.status
$before = [long]$r.body.data.total
$r = Call 'Get' "$Gateway/api/reviews/mine" $anon $null $null
Check "未登录取我的评价 401" ($r.status -eq 401) $r.status
$r = Call 'Post' "$Gateway/api/reviews" $anon @{ orderNo = $orderNo; items = @(@{ spuId = $SPU; rating = 5 }) }
Check "未登录提交评价 401" ($r.status -eq 401) $r.status

# ---------- 5. 评价资格 ----------
Write-Host ""
Write-Host "> 评价资格校验"
$r = Call 'Post' "$Gateway/api/reviews" $sessB @{ orderNo = $pendingNo; items = @(@{ spuId = $SPU; rating = 5; content = '未收货就评价' }) }
Check "未完成订单评价 422" ($r.status -eq 422) ($r.body | ConvertTo-Json -Compress)
$r = Call 'Post' "$Gateway/api/reviews" $sessO @{ orderNo = $orderNo; items = @(@{ spuId = $SPU; rating = 5; content = '别人的订单' }) }
Check "评价他人订单 403" ($r.status -eq 403) ($r.body | ConvertTo-Json -Compress)
$r = Call 'Post' "$Gateway/api/reviews" $sessB @{ orderNo = 'NOT_EXIST_ORDER_NO'; items = @(@{ spuId = $SPU; rating = 5 }) }
Check "订单不存在 404" ($r.status -eq 404) ($r.body | ConvertTo-Json -Compress)
$r = Call 'Post' "$Gateway/api/reviews" $sessB @{ orderNo = $orderNo; items = @(@{ spuId = 999999999; rating = 5; content = '不在订单里' }) }
Check "评价不在订单中的商品 422" ($r.status -eq 422) ($r.body | ConvertTo-Json -Compress)

# ---------- 6. 参数边界 ----------
Write-Host ""
Write-Host "> 参数边界"
$r = Call 'Post' "$Gateway/api/reviews" $sessB @{ orderNo = $orderNo; items = @(@{ spuId = $SPU; rating = 6; content = '超范围' }) }
Check "评分 6 星被拒（4xx 且 code 明确）" ($r.status -ge 400 -and $r.status -lt 500 -and $r.body.code -eq 'VALIDATION_ERROR') "$($r.status)/$($r.body.code)"
$r = Call 'Post' "$Gateway/api/reviews" $sessB @{ orderNo = $orderNo; items = @(@{ spuId = $SPU; rating = 0; content = '零分' }) }
Check "评分 0 星被拒（4xx 且 code 明确）" ($r.status -ge 400 -and $r.status -lt 500 -and $r.body.code -eq 'VALIDATION_ERROR') "$($r.status)/$($r.body.code)"
$r = Call 'Post' "$Gateway/api/reviews" $sessB @{ orderNo = $orderNo; items = @(@{ spuId = $SPU; rating = 5; content = 'x'; images = @('javascript:alert(1)') }) }
Check "非 http/https 图片被拒（4xx 且 code 明确）" ($r.status -ge 400 -and $r.status -lt 500 -and $r.body.code -eq 'VALIDATION_ERROR') "$($r.status)/$($r.body.code)"
$manyImages = 1..10 | ForEach-Object { "https://example.com/$_.jpg" }
$r = Call 'Post' "$Gateway/api/reviews" $sessB @{ orderNo = $orderNo; items = @(@{ spuId = $SPU; rating = 5; content = 'x'; images = $manyImages }) }
Check "超过 9 张图片被拒（4xx）" ($r.status -ge 400 -and $r.status -lt 500) $r.status

# ---------- 7. 正常提交（匿名）+ 重复提交 ----------
Write-Host ""
Write-Host "> 正常提交与幂等"
$r = Call 'Post' "$Gateway/api/reviews" $sessB @{
    orderNo = $orderNo
    anonymous = $true
    items = @(@{
        spuId = $SPU
        skuId = $SKU
        rating = 5
        content = '猫粮很香，毛孩子吃得干干净净'
        images = @('https://example.com/review-1.jpg')
    })
}
Check "匿名提交评价返回 200" ($r.status -eq 200) ($r.body | ConvertTo-Json -Compress)
Check "提交结果为 1 条" ($r.body.data.created -eq 1) $r.body.data.created
# 主键必须从**原始响应文本**正则提取（不要走 ConvertFrom-Json）。
# 注意：自 T-39 起，64 位标识**统一以 JSON 字符串下发**（`"id":"361..."`），
# 所以正则要允许可选引号 —— 写成 '"id":(\d+)' 会匹配不到（引号把它挡住了），
# 拿到空串后 `WHERE id = ` 会变成语法错误，表现为"追评接口全挂"。
# 用 "?(\d+)"? 兼容两种形态：老写法（裸数字）与新写法（字符串）。
$reviewId = [regex]::Match($r.raw, '"id"\s*:\s*"?(\d+)"?').Groups[1].Value
Check "取到评价主键（原始文本）" ($reviewId.Length -ge 15) "reviewId='$reviewId'"
$dbCount = Sql 'duoduo_fulfilment' "SELECT COUNT(*) FROM t_review WHERE order_no = '$orderNo';"
Check "库内已落 1 条评价" ($dbCount -eq '1') $dbCount
$dbAnon = Sql 'duoduo_fulfilment' "SELECT anonymous FROM t_review WHERE id = $reviewId;"
Check "库内匿名标记为 1" ($dbAnon -eq '1') $dbAnon

$r = Call 'Post' "$Gateway/api/reviews" $sessB @{ orderNo = $orderNo; items = @(@{ spuId = $SPU; rating = 4; content = '重复提交' }) }
Check "同商品重复评价被拒 409" ($r.status -eq 409) ($r.body | ConvertTo-Json -Compress)

# ---------- 8. 公开可见性与匿名脱敏 ----------
Write-Host ""
Write-Host "> 公开可见性与匿名脱敏"
# 断言一律用**业务键**（orderNo）匹配，绝不用 19 位雪花主键做等值比较：
# PowerShell 5.1 的 ConvertFrom-Json 基于 JavaScriptSerializer，大整数会丢精度
# （与前端 JSON.parse 同类坑），否则会出现「数据明明在库里，断言却找不到」的假失败
$r = Call 'Get' "$Gateway/api/reviews/product/${SPU}?page=1&pageSize=50" $anon $null $null
$found = @($r.body.data.items | Where-Object { $_.orderNo -eq $orderNo })[0]
Check "公开列表能查到该评价" ($null -ne $found) ($r.body.data.items | ConvertTo-Json -Compress)
Check "匿名评价昵称脱敏为「匿名用户」" ($found.nickname -eq '匿名用户') $found.nickname
Check "匿名评价不返回头像" ($null -eq $found.avatar -or $found.avatar -eq '') $found.avatar
$contentOk = $found.content -eq '猫粮很香，毛孩子吃得干干净净'
Check "评价正文中文经接口读回无乱码" $contentOk $found.content

$r = Call 'Get' "$Gateway/api/reviews/product/${SPU}?page=1&pageSize=50&rating=5" $anon $null $null
$fiveStarHit = @($r.body.data.items | Where-Object { $_.orderNo -eq $orderNo }).Count -eq 1
Check "按 5 星筛选能命中" $fiveStarHit $r.body.data.total
$r = Call 'Get' "$Gateway/api/reviews/product/${SPU}?page=1&pageSize=50&rating=1" $anon $null $null
$oneStarHit = @($r.body.data.items | Where-Object { $_.orderNo -eq $orderNo }).Count
Check "按 1 星筛选未命中" ($oneStarHit -eq 0) $oneStarHit
$r = Call 'Get' "$Gateway/api/reviews/product/${SPU}?page=1&pageSize=50&hasImage=true" $anon $null $null
$imgHit = @($r.body.data.items | Where-Object { $_.orderNo -eq $orderNo }).Count -eq 1
Check "按「有图」筛选能命中" $imgHit $r.body.data.total

# ---------- 9. 追评 ----------
Write-Host ""
Write-Host "> 追评"
$r = Call 'Post' "$Gateway/api/reviews/$reviewId/append" $sessO @{ content = '越权追评' }
Check "追评他人评价 403" ($r.status -eq 403) $r.status
$r = Call 'Post' "$Gateway/api/reviews/$reviewId/append" $sessB @{ content = '吃了一周，便便正常，回购' }
Check "本人追评返回 200" ($r.status -eq 200) ($r.body | ConvertTo-Json -Compress)
$r = Call 'Post' "$Gateway/api/reviews/$reviewId/append" $sessB @{ content = '再追一次' }
Check "二次追评被拒 409" ($r.status -eq 409) $r.status
$dbAppend = Sql 'duoduo_fulfilment' "SELECT CONCAT(IF(append_content IS NULL OR append_content = '', 'EMPTY', 'SET'), '|', IF(append_at IS NULL, 'NULL', 'SET')) FROM t_review WHERE id = $reviewId;"
Check "库内追评内容与时间已落" ($dbAppend -eq 'SET|SET') $dbAppend

# ---------- 10. 聚合口径 ----------
Write-Host ""
Write-Host "> 聚合口径"
$r = Call 'Get' "$Gateway/api/reviews/product/$SPU/summary" $anon $null $null
$sum = $r.body.data
Check "汇总总数较测试前增加 1" ([long]$sum.total -eq $before + 1) "$before -> $($sum.total)"
Check "平均分为 5.0" ($sum.avgRating -eq '5.0') $sum.avgRating
Check "好评率为 100%" ($sum.goodRate -eq '100%') $sum.goodRate
Check "星级分布：5 星计数 ≥ 1" ([long]$sum.starCounts[4] -ge 1) ($sum.starCounts -join ',')
Check "带图计数 ≥ 1" ([long]$sum.imageCount -ge 1) $sum.imageCount
Check "追评计数 ≥ 1" ([long]$sum.appendCount -ge 1) $sum.appendCount

$r = Call 'Get' "$Gateway/api/reviews/mine?page=1&pageSize=10" $sessB $null $null
Check "我的评价能查到该条" (@($r.body.data.items | Where-Object { $_.orderNo -eq $orderNo }).Count -eq 1) $r.body.data.total
$r = Call 'Get' "$Gateway/api/reviews/order/$orderNo" $sessB $null $null
Check "按订单查我的评价返回 1 条" (@($r.body.data).Count -eq 1) (@($r.body.data).Count)
$r = Call 'Get' "$Gateway/api/reviews/reviewed?orderNos=$orderNo,$pendingNo" $sessB $null $null
Check "已评价订单集合包含该单" (@($r.body.data.orderNos) -contains $orderNo) ($r.body.data.orderNos -join ',')

# ---------- 11. 清理与复原 ----------
Write-Host ""
Write-Host "> 清理夹具"
Sql 'duoduo_fulfilment' "DELETE FROM t_review WHERE order_no = '$orderNo';" | Out-Null
$left = Sql 'duoduo_fulfilment' "SELECT COUNT(*) FROM t_review WHERE order_no = '$orderNo';"
Check "评价夹具已删除（库内复原）" ($left -eq '0') $left
$r = Call 'Get' "$Gateway/api/reviews/product/$SPU/summary" $anon $null $null
Check "汇总回到测试前水平" ([long]$r.body.data.total -eq $before) "$before -> $($r.body.data.total)"

# ---------- 结果 ----------
Write-Host ""
Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
if ($fail -gt 0) {
    Write-Host "失败项："
    $failures | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
exit 0
