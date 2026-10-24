# 交易 + 商品服务 · 代运营最小版（E23）端到端冒烟（Windows PowerShell 5.1）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\proxy-ops-smoke.ps1
#
# 前置：容器全部 healthy，且已 mvn package + docker compose build 出最新镜像；
#       docs/sql/incremental/33-e23-proxy-ops.sql 已执行（本脚本会自动兜底灌入）。
#
# 本套要证明的核心事实（不是"功能能用"，而是"绕不过去"）：
#   ① 平台对第三方店铺的订单 / 商品**没有任何直接权限**（默认阻断，有正对照）；
#   ② 唯一例外必须带一份**店主本人给出的、与本动作本目标完全对得上的**有效工单；
#   ③ 工单一次性消费；跨店、错目标、错动作、无事由一律拒绝；
#   ④ 平台账号不能自己造授权（否则这套机制只是填写框）；
#   ⑤ 平台自营不受限、也不该走代运营（那份"商家授权"是假的）；
#   ⑥ 三个动作全部留痕（审计表里能按动作分别检索到）。
#
# 设计纪律（沿用既有脚本教训）：
#   1. 夹具从接口动态取，不硬编码价格/库存；工单号一律从响应里拿，不自己拼；
#   2. 凡改库必先记录原值，结尾自证"库内数据已复原"；
#   3. 凡"被拒"必配"有权者能过"的正对照 —— 只断言拒绝，把方法改成永远 403 也能全绿；
#   4. 每条否定断言都额外断言"状态没有被改"：
#      「拒绝了」与「拒绝了但动作还是执行了」是两回事，后者才是真正的越权。
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    # 直连服务端口：用来验证"绕过网关直连"时服务端判权仍然生效（E16 的分层断言）
    [string]$Trade = 'http://127.0.0.1:8083',
    [string]$Product = 'http://127.0.0.1:8082',
    [string]$Password = 'duoduo123',
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

. (Join-Path $PSScriptRoot 'lib\internal-token.ps1')
. (Join-Path $PSScriptRoot 'lib\platform-operator.ps1')
. (Join-Path $PSScriptRoot 'lib\seller-fixture.ps1')

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

function Login([string]$phone) {
    $s = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $r = Call 'Post' "$Gateway/api/auth/login" $s @{ phone = $phone; password = $Password }
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

# 加购 → 下单 → 支付，打包成可复用动作
$addr = '上海市浦东新区张江路 1 号'
$recv = @{ receiverName = '小林'; receiverPhone = '13900000000'; receiverAddress = $addr }
function PlaceAndPay($session, $skuId, $label) {
    Call 'Post' "$Gateway/api/cart/items" $session @{ skuId = $skuId; quantity = 1 } | Out-Null
    $o = Call 'Post' "$Gateway/api/orders" $session ($recv + @{ idempotentKey = (NewKey) })
    if ($o.status -ne 200) { return @{ ok = $false; orderNo = $null; step = "下单 $label (status=$($o.status))" } }
    $orderNo = @($o.body.data.orders)[0].orderNo
    $p = Call 'Post' "$Gateway/api/orders/$orderNo/pay" $session @{ channel = 'MOCK'; idempotentKey = (NewKey) }
    if ($p.status -ne 200) { return @{ ok = $false; orderNo = $orderNo; step = "支付 $label (status=$($p.status))" } }
    return @{ ok = $true; orderNo = $orderNo; pay = [long]$p.body.data.amount; step = $null }
}

## ============================================================================
## 0. 环境自管：DDL 兜底 + 就绪检查
## ============================================================================
Write-Host ""
Write-Host "> 环境准备（自管）"

$tblT = Sql 'duoduo_trade' "SHOW TABLES LIKE 't_proxy_work_order';"
if ("$tblT" -ne 't_proxy_work_order') {
    Write-Host "  交易库工单表缺失，灌入增量 DDL ..."
    Get-Content (Join-Path $PSScriptRoot '..\docs\sql\incremental\33-e23-proxy-ops.sql') -Raw -Encoding UTF8 |
        & docker exec -i duoduo-mysql mysql -uduoduo -pduoduo --default-character-set=utf8mb4 2>$null
    $tblT = Sql 'duoduo_trade' "SHOW TABLES LIKE 't_proxy_work_order';"
}
Check "交易库工单表 t_proxy_work_order 存在" ("$tblT" -eq 't_proxy_work_order') $tblT

$tblP = Sql 'duoduo_product' "SHOW TABLES LIKE 't_proxy_work_order';"
if ("$tblP" -ne 't_proxy_work_order') {
    Write-Host "  商品库工单表缺失，灌入增量 DDL ..."
    Get-Content (Join-Path $PSScriptRoot '..\docs\sql\incremental\33-e23-proxy-ops.sql') -Raw -Encoding UTF8 |
        & docker exec -i duoduo-mysql mysql -uduoduo -pduoduo --default-character-set=utf8mb4 2>$null
    $tblP = Sql 'duoduo_product' "SHOW TABLES LIKE 't_proxy_work_order';"
}
Check "商品库工单表 t_proxy_work_order 存在" ("$tblP" -eq 't_proxy_work_order') $tblP

$ready = $false
for ($i = 0; $i -lt 30; $i++) {
    try {
        $h = Invoke-WebRequest -Uri "$Gateway/actuator/health" -UseBasicParsing -TimeoutSec 3
        if ($h.StatusCode -eq 200) { $ready = $true; break }
    } catch { Start-Sleep -Seconds 1 }
}
Check "网关 /actuator/health 就绪" $ready
if (-not $ready) { Write-Host "网关未就绪，终止"; exit 1 }

Write-Host "  已部署镜像（服务端）—— 本脚本自证" 
$imgT = (docker inspect --format '{{.Image}}' duoduo-app-trade 2>$null)
$imgP = (docker inspect --format '{{.Image}}' duoduo-app-product 2>$null)
Check "交易服务容器已运行" (-not [string]::IsNullOrWhiteSpace($imgT)) $imgT
Check "商品服务容器已运行" (-not [string]::IsNullOrWhiteSpace($imgP)) $imgP

## ============================================================================
## 1. 夹具：两个第三方店铺 + 买家 + 运营
## ============================================================================
Write-Host ""
Write-Host "> 夹具（现场建；结尾自毁）"

$phoneS = '134' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneT = '135' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneB = '136' + (Get-Random -Minimum 10000000 -Maximum 99999999)

$regS = Register $phoneS '店主S'
Check "（夹具）店主 S 注册/登录成功（200）" ($regS.result.status -eq 200) $regS.result.status
Call 'Post' "$Gateway/api/users/me/realname" $regS.session @{ realName = '沈小七'; idCard = '310101199001011234' } | Out-Null
$fxS = New-SellerFixture -Phone $phoneS -StoreName '代运营夹具S' -Gateway $Gateway
Check "（夹具）店主 S 直建为 APPROVED 商家" ($fxS.roleSet) $fxS.role
$reS = Login $phoneS
$sessS = $reS.session
$sidS = "$($reS.result.body.data.id)"

$regT = Register $phoneT '店主T'
Check "（夹具）店主 T 注册/登录成功（200）" ($regT.result.status -eq 200) $regT.result.status
Call 'Post' "$Gateway/api/users/me/realname" $regT.session @{ realName = '田小七'; idCard = '310101199002022345' } | Out-Null
$fxT = New-SellerFixture -Phone $phoneT -StoreName '代运营夹具T' -Gateway $Gateway
Check "（夹具）店主 T 直建为 APPROVED 商家" ($fxT.roleSet) $fxT.role
$reT = Login $phoneT
$sessT = $reT.session
$sidT = "$($reT.result.body.data.id)"

Check "（夹具）两个店主是不同的租户主体" ($sidS -ne $sidT) "S=$sidS T=$sidT"

$regB = Register $phoneB '买家己'
Check "（夹具）买家注册/登录成功（200）" ($regB.result.status -eq 200) $regB.result.status
$sessB = $regB.session

$op = New-PlatformOperatorSession -Gateway $Gateway -Password $Password
Check "（夹具）运营账号已就位（role=OPERATOR）" ($op.roleSet) "role=$($op.role)"
$sessOp = $op.session

## ============================================================================
## 2. 商品与订单夹具：把种子 SPU 划给店主 S（★ 必须同时改 supply_type）
## ============================================================================
Write-Host ""
Write-Host "> 商品 / 订单夹具"

# ★ 为什么要同时改 supply_type：E27 起「自营 / 第三方」的判据是 t_spu.supply_type，
#   不是 seller_id。采购视图（下单拆单读的那份）对自营商品**无条件返回 sellerId=0**，
#   只改 seller_id 的话订单仍会落回平台自营 —— 那样后面所有"第三方订单"的断言
#   都会退化成一个自营订单，测出来的是假绿。
# ★ 为什么自营那条要**现场建**，而不是"从库里挑一条现成的"：
#   本机库被清理过，在售 SPU 曾经只剩两条。若先挑 S 的、再回头找自营，
#   自营就会一条都不剩 —— 正对照请求的 URL 于是变成一个空的 spuId
#   （/api/seller/products//status），返回 405。405 长得像"接口用错了"，
#   看不出真正的原因是"夹具没建成"，这一处就白测了。
#   '97' 段位与 catalog-fixture.ps1 的夹具 SPU 并列（'98' 闲置 / '99' SKU），跑完自毁。
$spuSelf = ('97' + (Get-Random -Minimum 1000000 -Maximum 9999999))
$spuS = Sql 'duoduo_product' "SELECT id FROM t_spu WHERE status='ON_SALE' AND id <> $spuSelf ORDER BY id LIMIT 1;"

Check "（夹具）取到一条在售商品作为店主 S 的第三方商品" `
    (-not [string]::IsNullOrWhiteSpace($spuS)) "库里没有在售 SPU，请先导入种子商品（docs/sql/02-duoduo-product.sql）"
$origSupply = Sql 'duoduo_product' "SELECT CONCAT(id,':',IFNULL(seller_id,0),':',supply_type) FROM t_spu WHERE id = $spuS;"

if (-not $SkipDbCheck) {
    # 自营夹具：supply_type='self' 且 seller_id=0 —— 两个信号都指向自营
    # （判据口径见 SellerProductService.isPlatformSelf，这里刻意两个都给，避免夹具本身
    #   落在"两个信号打架"的模糊区，那样测出来的是模糊区的行为、不是正常自营）
    Sql 'duoduo_product' "INSERT INTO t_spu (id, title, category_id, supply_type, status, audit_status, sales_count, rating, rating_count, min_price, max_price, created_at, updated_at, seller_id) VALUES ($spuSelf, 'E23夹具·平台自营商品', 101, 'self', 'ON_SALE', 'APPROVED', 0, 5.00, 0, 9900, 9900, UTC_TIMESTAMP(), UTC_TIMESTAMP(), 0);" | Out-Null
    # 回读复核：列名/约束写错时当场炸，而不是等 §3.4 收到一个 405 再去猜
    $selfBack = Sql 'duoduo_product' "SELECT CONCAT(status,'/',supply_type,'/',IFNULL(seller_id,0)) FROM t_spu WHERE id=$spuSelf;"
    Check "★（夹具）自营商品已现场建好且可回读（自营正对照的前提）" ("$selfBack" -eq 'ON_SALE/self/0') $selfBack
    Check "★（夹具）自营夹具与店主 S 的商品不是同一条（否则正对照会测到被收窄的那条）" `
        ("$spuS" -ne "$spuSelf") "spuS=$spuS spuSelf=$spuSelf"

    Sql 'duoduo_product' "UPDATE t_spu SET seller_id = $sidS, supply_type = 'merchant' WHERE id = $spuS;" | Out-Null
    Check "（夹具）SPU S 已划给店主 S 且供货类型改为 merchant" `
        ((Sql 'duoduo_product' "SELECT COUNT(*) FROM t_spu WHERE id = $spuS AND seller_id = $sidS AND supply_type = 'merchant';") -eq '1')
} else {
    Check "（夹具）跳过了商品归属夹具（-SkipDbCheck）" $false "跳过会让后续全部断言失去意义"
}

$skuS = Sql 'duoduo_product' "SELECT id FROM t_sku WHERE spu_id = $spuS ORDER BY id LIMIT 1;"
$stockBefore = [int](Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $skuS;")
Check "（夹具）取到可选 SKU（库存 $stockBefore）" ($stockBefore -ge 3) $stockBefore

$c1 = PlaceAndPay $sessB $skuS '订单1'
Check "（夹具）订单 1 下单并支付成功" $c1.ok $c1.step
$c2 = PlaceAndPay $sessB $skuS '订单2'
Check "（夹具）订单 2 下单并支付成功" $c2.ok $c2.step
$c3 = PlaceAndPay $sessB $skuS '订单3'
Check "（夹具）订单 3 下单并支付成功" $c3.ok $c3.step
$o1 = $c1.orderNo; $o2 = $c2.orderNo; $o3 = $c3.orderNo

if (-not $o1 -or -not $o2 -or -not $o3) {
    Write-Host "订单夹具创建失败，终止。"
    Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
    exit 1
}

Check "★（夹具）订单 1 的 seller_id 已自动为店主 S（拆单链路打通）" `
    ((Sql 'duoduo_trade' "SELECT seller_id FROM t_order WHERE order_no='$o1';") -eq $sidS) `
    (Sql 'duoduo_trade' "SELECT seller_id FROM t_order WHERE order_no='$o1';")
Check "（夹具）订单 2 同属店主 S" ((Sql 'duoduo_trade' "SELECT seller_id FROM t_order WHERE order_no='$o2';") -eq $sidS)
Check "（夹具）订单 3 同属店主 S" ((Sql 'duoduo_trade' "SELECT seller_id FROM t_order WHERE order_no='$o3';") -eq $sidS)

## ============================================================================
## 3. 默认阻断（本项的前提）：平台对第三方订单 / 商品没有直接权限
## ============================================================================
Write-Host ""
Write-Host "> 默认阻断（平台对第三方店铺**没有任何直接权限**）"

# 3.1 另一店主操作别人的订单 → 404（E20 起租户过滤让它在 SQL 层就查不到）
$r = Call 'Post' "$Gateway/api/seller/orders/$o1/ship" $sessT @{ logisticsNo = 'SF-T-0001' }
Check "★ 另一店主对订单 1 发货 → 404（E20 防枚举口径）" ($r.status -eq 404) $r.status

# 3.2 ★ 运营（平台后台身份）操作第三方订单 → 403
#     注意与上一条的差别：运营的令牌**不声明 tid**（B-21），所以租户过滤对他不生效，
#     订单是查得到的 —— 挡住他的是 SellerService 的归属校验（403），不是"看不见"（404）。
#     这个区分必须在断言里体现：将来若真放开了，会变成 200，而 403/404 都会变红。
$r = Call 'Post' "$Gateway/api/seller/orders/$o1/ship" $sessOp @{ logisticsNo = 'SF-OP-0001' }
Check "★ 运营对第三方订单 1 发货 → 403（订单可见但无归属）" ($r.status -eq 403) $r.status
Check "★ 被拒后订单 1 仍为 PAID（拒绝 ≠ 拒绝了但还是执行了）" `
    ((Sql 'duoduo_trade' "SELECT status FROM t_order WHERE order_no='$o1';") -eq 'PAID') `
    (Sql 'duoduo_trade' "SELECT status FROM t_order WHERE order_no='$o1';")

# 3.3 ★ E23 收窄：运营不能直接改第三方商品的状态
$r = Call 'Post' "$Gateway/api/seller/products/$spuS/status" $sessOp @{ action = 'OFF_SHELF' }
Check "★ 运营直接下架第三方商品 → 403（E23 已收窄该口子）" ($r.status -eq 403) $r.status
Check "★ 被拒后商品仍是 ON_SALE" `
    ((Sql 'duoduo_product' "SELECT status FROM t_spu WHERE id=$spuS;") -eq 'ON_SALE') `
    (Sql 'duoduo_product' "SELECT status FROM t_spu WHERE id=$spuS;")

# 3.4 正对照：自营商品运营可以直接改（收窄不该把平台自己的货也拦住）
$r = Call 'Post' "$Gateway/api/seller/products/$spuSelf/status" $sessOp @{ action = 'OFF_SHELF' }
Check "正对照：运营直接下架自营商品 → 200（自营不受限）" ($r.status -eq 200) $r.status
Sql 'duoduo_product' "UPDATE t_spu SET status='ON_SALE' WHERE id=$spuSelf;" | Out-Null
Check "（复原）自营夹具已重新上架" `
    ((Sql 'duoduo_product' "SELECT status FROM t_spu WHERE id=$spuSelf;") -eq 'ON_SALE')

# 3.5 服务端层（绕过网关直连）同样拒绝 —— 与网关层分别断言（E16 的分层纪律）
$r = Call 'Post' "$Product/api/seller/products/$spuS/status" $null @{ action = 'OFF_SHELF' } @{
    'X-User-Id' = '8888'; 'X-User-Role' = 'OPERATOR'; 'X-User-Phone' = '13900000000'
}
Check "★ 服务端层（直连商品端口，伪造运营头）改第三方商品 → 403" ($r.status -eq 403) $r.status

# 3.6 ★ A-1 / PLAN B-23 收窄：运营不能直接**编辑**第三方商品
#      E23 当时**刻意只拦上下架**（理由：不借一次收窄顺手改变更多行为），
#      留下的口子就是这里 —— 运营可以绕过代运营工单直接改第三方商品的内容，
#      而"改了什么"既没有工单、也没有改前值留档。
$r = Call 'Put' "$Gateway/api/seller/products/$spuS" $sessOp @{ title = '运营越权改的标题' }
Check "★ 运营直接编辑第三方商品 → 403（A-1 已收窄该口子）" ($r.status -eq 403) $r.status
# ★ 断言"没落库"：只在入口抛异常、而写库已经发生，是最难查的一类（接口报错、数据却变了）。
#   这里用 COUNT 而不是"把原标题取出来再比"，是为了不在 SQL 里插值 —— 商品标题来自种子数据，
#   一旦标题里出现单引号，插值写法会把整个脚本掀掉（连汇总都打不出来）。
$hit = Sql 'duoduo_product' "SELECT COUNT(*) FROM t_spu WHERE id=$spuS AND title='运营越权改的标题';"
Check "★ 被拒后库里查不到那个被改的标题（证明没有先写后抛）" ("$hit" -eq '0') $hit

# 3.7 正对照：自营商品运营可以直接编辑（收窄不该把平台自己的货也拦住）
#     刻意改 subtitle 而不是 title：subtitle 可以再置空复原，不必把原标题读出来插回 SQL。
#     A-07：编辑必须带读到的 version；每次改完版本 +1，所以第二次要重新取。
$selfVer = [int](Sql 'duoduo_product' "SELECT version FROM t_spu WHERE id=$spuSelf;")
$r = Call 'Put' "$Gateway/api/seller/products/$spuSelf" $sessOp @{ subtitle = 'A1正对照标记'; version = $selfVer }
Check "正对照：运营直接编辑自营商品 → 200（自营不受限）" ($r.status -eq 200) $r.status
$selfVer2 = [int](Sql 'duoduo_product' "SELECT version FROM t_spu WHERE id=$spuSelf;")
Check "A-07：编辑成功 → 版本 +1（$selfVer → $selfVer2）" ($selfVer2 -eq $selfVer + 1) "before=$selfVer after=$selfVer2"
$null = Call 'Put' "$Gateway/api/seller/products/$spuSelf" $sessOp @{ subtitle = ''; version = $selfVer2 }
Check "（复原）自营夹具副标题已置空" `
    ((Sql 'duoduo_product' "SELECT IFNULL(subtitle,'') FROM t_spu WHERE id=$spuSelf;") -eq '')

# 3.8 服务端层（绕过网关直连）同样拒绝 —— 与网关层分别断言（E16 的分层纪律）
$r = Call 'Put' "$Product/api/seller/products/$spuS" $null @{ title = '直连越权改的标题' } @{
    'X-User-Id' = '8888'; 'X-User-Role' = 'OPERATOR'; 'X-User-Phone' = '13900000000'
}
Check "★ 服务端层（直连商品端口，伪造运营头）编辑第三方商品 → 403" ($r.status -eq 403) $r.status
$hit2 = Sql 'duoduo_product' "SELECT COUNT(*) FROM t_spu WHERE id=$spuS AND title='直连越权改的标题';"
Check "★ 服务端层被拒后同样没有落库" ("$hit2" -eq '0') $hit2

## ============================================================================
## 4. 无工单不动作 / 平台不能自己授权
## ============================================================================
Write-Host ""
Write-Host "> 无工单不动作 · 平台不能自己造授权"

$r = Call 'Post' "$Gateway/api/admin/orders/$o1/proxy-ship" $sessOp @{
    workOrderNo = 'WO-ORD-20260101000000-99999'; reason = '无工单硬闯'; logisticsNo = 'SF-X'
}
Check "★ 不存在的工单 → 404（无工单不动作）" ($r.status -eq 404) $r.status

$r = Call 'Post' "$Gateway/api/admin/orders/$o1/proxy-ship" $sessOp @{
    workOrderNo = 'WO-ORD-20260101000000-99999'; reason = '   '; logisticsNo = 'SF-X'
}
Check "★ 执行说明为空白 → 400（全空格在页面上像填了、在库里等于没填）" ($r.status -eq 400) $r.status

$r = Call 'Post' "$Gateway/api/admin/orders/$o1/proxy-ship" $sessB @{
    workOrderNo = 'WO-ORD-20260101000000-99999'; reason = '买家越权'; logisticsNo = 'SF-X'
}
Check "★ 买家调用代发货 → 403" ($r.status -eq 403) $r.status

$r = Call 'Post' "$Gateway/api/admin/orders/$o1/proxy-ship" $sessS @{
    workOrderNo = 'WO-ORD-20260101000000-99999'; reason = '卖家越权'; logisticsNo = 'SF-X'
}
Check "★ 卖家调用代发货 → 403（代运营是平台侧权限，不是卖家侧）" ($r.status -eq 403) $r.status

$r = Call 'Post' "$Gateway/api/seller/proxy-requests" $sessOp @{
    targetType = 'ORDER'; targetRef = $o1; actionType = 'SHIP'; reason = '平台自己给自己授权'
}
Check "★ 平台账号申请代运营工单（交易域）→ 403（授权只能由被代理方给出）" ($r.status -eq 403) $r.status

$r = Call 'Post' "$Gateway/api/seller/products/proxy-requests" $sessOp @{
    targetType = 'PRODUCT'; targetRef = "$spuS"; actionType = 'OFF_SHELF'; reason = '平台自己给自己授权'
}
Check "★ 平台账号申请代运营工单（商品域）→ 403" ($r.status -eq 403) $r.status

## ============================================================================
## 5. 商家申请：工单的真实来源
## ============================================================================
Write-Host ""
Write-Host "> 商家申请（工单 = 店主本人的授权凭证）"

$r = Call 'Post' "$Gateway/api/seller/proxy-requests" $sessS @{
    targetType = 'ORDER'; targetRef = $o1; actionType = 'SHIP'; reason = '本人在外地，请平台协助发货'
}
Check "★ 店主 S 申请代发货工单 → 200" ($r.status -eq 200) $r.status
$wo1 = "$($r.body.data.workOrderNo)"
Check "★ 工单号带交易域前缀 WO-ORD-" ($wo1.StartsWith('WO-ORD-')) $wo1
Check "工单初始状态为 OPEN" ($r.body.data.status -eq 'OPEN') $r.body.data.status
Check "工单租户 = 店主 S 本人（不由请求体指定）" ("$($r.body.data.tenantId)" -eq $sidS) $r.body.data.tenantId
Check "工单目标 = 订单 1（目标对象按字符串原样下发）" ("$($r.body.data.targetRef)" -eq $o1) $r.body.data.targetRef
Check "工单动作 = SHIP 且带中文标签" `
    (($r.body.data.actionType -eq 'SHIP') -and ($r.body.data.actionLabel -eq '代发货')) `
    "$($r.body.data.actionType)/$($r.body.data.actionLabel)"

$r = Call 'Post' "$Gateway/api/seller/proxy-requests" $sessS @{
    targetType = 'ORDER'; targetRef = $o2; actionType = 'AFTERSALE'; reason = '买家要求退货，本人不便处理，请平台代为退款'
}
Check "★ 店主 S 申请代处理售后工单 → 200" ($r.status -eq 200) $r.status
$woBad = "$($r.body.data.workOrderNo)"

$r = Call 'Post' "$Gateway/api/seller/proxy-requests" $sessS @{
    targetType = 'ORDER'; targetRef = $o3; actionType = 'SHIP'; reason = '请平台协助发货（跨店测试用）'
}
Check "★ 店主 S 申请订单 3 的代发货工单 → 200" ($r.status -eq 200) $r.status
$wo3 = "$($r.body.data.workOrderNo)"

$r = Call 'Post' "$Gateway/api/seller/proxy-requests" $sessS @{
    targetType = 'ORDER'; targetRef = $o3; actionType = 'SHIP'; reason = '请平台协助发货（撤销测试用）'
}
Check "★ 店主 S 再申请一张订单 3 的工单（撤销用）→ 200" ($r.status -eq 200) $r.status
$wo4 = "$($r.body.data.workOrderNo)"

Check "四张工单号互不相同（工单号唯一）" `
    ((@($wo1, $woBad, $wo3, $wo4) | Select-Object -Unique).Count -eq 4) "wo1=$wo1 woBad=$woBad wo3=$wo3 wo4=$wo4"

# 申请入参校验
$r = Call 'Post' "$Gateway/api/seller/proxy-requests" $sessS @{
    targetType = 'ORDER'; targetRef = $o1; actionType = 'SHIP'; reason = '   '
}
Check "申请事由为空 → 400" ($r.status -eq 400) $r.status

$r = Call 'Post' "$Gateway/api/seller/proxy-requests" $sessS @{
    targetType = 'ORDER'; targetRef = $o1; actionType = 'FLY_TO_MOON'; reason = '非法动作'
}
Check "非法动作 → 400" ($r.status -eq 400) $r.status

$r = Call 'Post' "$Gateway/api/seller/proxy-requests" $sessS @{
    targetType = 'PRODUCT'; targetRef = "$spuS"; actionType = 'OFF_SHELF'; reason = '跨域申请'
}
Check "★ 交易域入口不接受商品类申请 → 400（两库目标不混用）" ($r.status -eq 400) $r.status

$r = Call 'Post' "$Gateway/api/seller/proxy-requests" $sessT @{
    targetType = 'ORDER'; targetRef = $o1; actionType = 'SHIP'; reason = '别人的订单'
}
Check "★ 店主 T 对 S 的订单申请工单 → 404（不是你的订单就是不存在）" ($r.status -eq 404) $r.status

# 自营订单不能申请代运营（没有第三方商家，这份授权是假的）
$r = Call 'Post' "$Gateway/api/seller/proxy-requests" $sessS @{
    targetType = 'ORDER'; targetRef = 'NOTEXIST-ORDER'; actionType = 'SHIP'; reason = '不存在的订单'
}
Check "对不存在的订单申请工单 → 404" ($r.status -eq 404) $r.status

## ============================================================================
## 6. 工单可见性（只读的租户数据查看）
## ============================================================================
Write-Host ""
Write-Host "> 工单可见性"

$r = Call 'Get' "$Gateway/api/seller/proxy-requests" $sessS $null
Check "店主 S 查看自己的工单列表 → 200" ($r.status -eq 200) $r.status
$mine = @($r.body.data.items | Where-Object { $_.workOrderNo -eq $wo1 })
Check "★ 列表含刚申请的工单" ($mine.Count -eq 1) $mine.Count
Check "列表 openCount ≥ 4（四张都是待执行）" ([long]$r.body.data.openCount -ge 4) $r.body.data.openCount

$r = Call 'Get' "$Gateway/api/seller/proxy-requests" $sessT $null
$leak = @($r.body.data.items | Where-Object { $_.workOrderNo -eq $wo1 })
Check "★ 店主 T 看不到 S 的工单（授权不跨店）" ($leak.Count -eq 0) $leak.Count

$r = Call 'Post' "$Gateway/api/seller/proxy-requests/$wo1/void" $sessT $null
Check "★ 店主 T 撤销 S 的工单 → 404" ($r.status -eq 404) $r.status
Check "被拒后工单 1 仍为 OPEN" `
    ((Sql 'duoduo_trade' "SELECT status FROM t_proxy_work_order WHERE work_order_no='$wo1';") -eq 'OPEN')

$r = Call 'Get' "$Gateway/api/admin/orders/proxy-work-orders?tenantId=$sidS" $sessOp $null
Check "平台待办（按租户筛选）→ 200" ($r.status -eq 200) $r.status
Check "★ 平台待办能看到店主 S 的工单（只读查看租户数据）" `
    ((@($r.body.data.items | Where-Object { $_.workOrderNo -eq $wo1 }).Count) -eq 1)

$r = Call 'Get' "$Gateway/api/admin/orders/proxy-work-orders" $sessB $null
Check "买家查看平台待办 → 403（进门要平台身份）" ($r.status -eq 403) $r.status

## ============================================================================
## 7. 错配一律拒绝（且不得消费工单）
## ============================================================================
Write-Host ""
Write-Host "> 错配拒绝（动作 / 目标 / 类型）"

$r = Call 'Post' "$Gateway/api/admin/orders/$o2/proxy-ship" $sessOp @{
    workOrderNo = $woBad; reason = '申请的是售后，却拿去发货'; logisticsNo = 'SF-X'
}
Check "★ 售后工单拿去代发货 → 400（动作必须一一对应）" ($r.status -eq 400) $r.status

$r = Call 'Post' "$Gateway/api/admin/orders/$o2/proxy-ship" $sessOp @{
    workOrderNo = $wo1; reason = '拿订单1的工单动订单2'; logisticsNo = 'SF-X'
}
Check "★ 拿订单 1 的工单去发订单 2 → 400（目标必须一一对应）" ($r.status -eq 400) $r.status

$r = Call 'Post' "$Gateway/api/admin/products/$spuS/proxy-off-shelf" $sessOp @{
    workOrderNo = $wo1; reason = '拿交易域工单去商品域'
}
Check "★ 拿交易域工单去商品域执行 → 404（两库工单不通用）" ($r.status -eq 404) $r.status

$r = Call 'Post' "$Gateway/api/admin/orders/$o1/proxy-ship" $sessOp @{
    workOrderNo = 'WO-PRD-20260101000000-99999'; reason = '拿商品域工单去交易域'; logisticsNo = 'SF-X'
}
Check "★ 拿商品域工单去交易域执行 → 404" ($r.status -eq 404) $r.status

Check "（自证）以上错配都没有消费掉工单 1 / 售后工单" `
    (((Sql 'duoduo_trade' "SELECT status FROM t_proxy_work_order WHERE work_order_no='$wo1';") -eq 'OPEN') -and `
        ((Sql 'duoduo_trade' "SELECT status FROM t_proxy_work_order WHERE work_order_no='$woBad';") -eq 'OPEN'))

## ============================================================================
## 8. 安全关键：跨店工单 + 自营订单
## ============================================================================
Write-Host ""
Write-Host "> ★ 安全关键：跨店工单 / 自营订单"

# 8.1 把订单 3 的归属临时改成店主 T —— 构造出「S 的工单、T 的订单」这个精确的攻击形态
Sql 'duoduo_trade' "UPDATE t_order SET seller_id = $sidT WHERE order_no = '$o3';" | Out-Null
Check "（夹具）订单 3 的归属已临时改为店主 T" `
    ((Sql 'duoduo_trade' "SELECT seller_id FROM t_order WHERE order_no='$o3';") -eq $sidT)

$r = Call 'Post' "$Gateway/api/admin/orders/$o3/proxy-ship" $sessOp @{
    workOrderNo = $wo3; reason = '拿 A 店的工单动 B 店的订单'; logisticsNo = 'SF-X'
}
Check "★★ 拿 S 的工单动 T 的订单 → 403（本机制最典型的漏洞形态）" ($r.status -eq 403) $r.status
Check "★★ 被拒后：订单 3 仍是 PAID 且工单 3 仍是 OPEN（授权没被浪费）" `
    (((Sql 'duoduo_trade' "SELECT status FROM t_order WHERE order_no='$o3';") -eq 'PAID') -and `
        ((Sql 'duoduo_trade' "SELECT status FROM t_proxy_work_order WHERE work_order_no='$wo3';") -eq 'OPEN')) `
    "order=$(Sql 'duoduo_trade' "SELECT status FROM t_order WHERE order_no='$o3';") wo=$(Sql 'duoduo_trade' "SELECT status FROM t_proxy_work_order WHERE work_order_no='$wo3';")"

# 8.2 复原归属，然后**同一张工单、同一个动作**就能过 —— 证明 8.1 被拒的唯一原因就是归属
Sql 'duoduo_trade' "UPDATE t_order SET seller_id = $sidS WHERE order_no = '$o3';" | Out-Null
Check "（复原）订单 3 的归属已改回店主 S" `
    ((Sql 'duoduo_trade' "SELECT seller_id FROM t_order WHERE order_no='$o3';") -eq $sidS)

# 8.3 自营订单不能走代运营（那份「商家授权」是假的）
Sql 'duoduo_trade' "UPDATE t_order SET seller_id = 0 WHERE order_no = '$o1';" | Out-Null
Check "（夹具）订单 1 的归属已临时改为平台自营" `
    ((Sql 'duoduo_trade' "SELECT seller_id FROM t_order WHERE order_no='$o1';") -eq '0')

$r = Call 'Post' "$Gateway/api/admin/orders/$o1/proxy-ship" $sessOp @{
    workOrderNo = $wo1; reason = '自营订单走代运营'; logisticsNo = 'SF-X'
}
Check "★ 自营订单走代运营 → 422（自营没有第三方商家，授权是假的）" ($r.status -eq 422) $r.status
Check "被拒后工单 1 仍为 OPEN" `
    ((Sql 'duoduo_trade' "SELECT status FROM t_proxy_work_order WHERE work_order_no='$wo1';") -eq 'OPEN')

Sql 'duoduo_trade' "UPDATE t_order SET seller_id = $sidS WHERE order_no = '$o1';" | Out-Null
Check "（复原）订单 1 的归属已改回店主 S" `
    ((Sql 'duoduo_trade' "SELECT seller_id FROM t_order WHERE order_no='$o1';") -eq $sidS)

## ============================================================================
## 9. 成功路径：代发货 / 代处理售后（并验证一次性消费）
## ============================================================================
Write-Host ""
Write-Host "> 成功路径（代发货 / 代处理售后）"

$r = Call 'Post' "$Gateway/api/admin/orders/$o1/proxy-ship" $sessOp @{
    workOrderNo = $wo1; reason = '已与店主电话确认，代为发货'; logisticsCompany = '顺丰速运'; logisticsNo = 'SF-PROXY-0001'
}
Check "★★ 运营持本店有效工单代发货 → 200" ($r.status -eq 200) $r.status
Check "★ 订单 1 转为 SHIPPED" ($r.body.data.status -eq 'SHIPPED') $r.body.data.status
Check "★ 物流信息真的落库（不是只写进日志）" `
    ((Sql 'duoduo_trade' "SELECT IFNULL(logistics_no,'') FROM t_order WHERE order_no='$o1';") -eq 'SF-PROXY-0001')
Check "★ 发货不等于放款：托管仍为 CUSTODY" ($r.body.data.settlement.status -eq 'CUSTODY') $r.body.data.settlement.status
Check "★ 工单 1 已被消费（一次性）" `
    ((Sql 'duoduo_trade' "SELECT status FROM t_proxy_work_order WHERE work_order_no='$wo1';") -eq 'CONSUMED')
Check "工单消费时刻与操作人已留痕" `
    ((Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_proxy_work_order WHERE work_order_no='$wo1' AND consumed_at IS NOT NULL AND consumed_by IS NOT NULL;") -eq '1')

$r = Call 'Post' "$Gateway/api/admin/orders/$o1/proxy-ship" $sessOp @{
    workOrderNo = $wo1; reason = '想用第二次'; logisticsNo = 'SF-PROXY-0002'
}
Check "★★ 同一张工单再用一次 → 400（授权一次性）" ($r.status -eq 400) $r.status
Check "被拒后订单 1 仍为 SHIPPED（没有产生第二次发货）" `
    ((Sql 'duoduo_trade' "SELECT status FROM t_order WHERE order_no='$o1';") -eq 'SHIPPED')

$r = Call 'Post' "$Gateway/api/admin/orders/$o2/proxy-aftersale" $sessOp @{
    workOrderNo = $woBad; reason = '店主确认已收到退货，代为退款'
}
Check "★★ 运营持本店有效工单代处理售后 → 200" ($r.status -eq 200) $r.status
Check "★ 订单 2 转为 REFUNDED" ($r.body.data.orderStatus -eq 'REFUNDED') $r.body.data.orderStatus
Check "★ 退款金额 = 买家实付（$($c2.pay) 分）" ([long]$r.body.data.refundAmount -eq [long]$c2.pay) $r.body.data.refundAmount
Check "★ 托管状态为 REFUNDED" ($r.body.data.settlementStatus -eq 'REFUNDED') $r.body.data.settlementStatus
Check "★ 售后工单已被消费" `
    ((Sql 'duoduo_trade' "SELECT status FROM t_proxy_work_order WHERE work_order_no='$woBad';") -eq 'CONSUMED')

## ============================================================================
## 10. 授权可撤销
## ============================================================================
Write-Host ""
Write-Host "> 授权可撤销（一份收不回来的授权不是授权）"

$r = Call 'Post' "$Gateway/api/seller/proxy-requests/$wo4/void" $sessS $null
Check "★ 店主自行撤销未执行的工单 → 200" ($r.status -eq 200) $r.status
Check "撤销后状态为 VOID" ($r.body.data.status -eq 'VOID') $r.body.data.status

$r = Call 'Post' "$Gateway/api/admin/orders/$o3/proxy-ship" $sessOp @{
    workOrderNo = $wo4; reason = '用已撤销的工单执行'; logisticsNo = 'SF-X'
}
Check "★★ 用已撤销的工单执行 → 400" ($r.status -eq 400) $r.status
Check "被拒后订单 3 仍为 PAID" `
    ((Sql 'duoduo_trade' "SELECT status FROM t_order WHERE order_no='$o3';") -eq 'PAID')

$r = Call 'Post' "$Gateway/api/seller/proxy-requests/$wo1/void" $sessS $null
Check "撤销已消费的工单 → 400（终态不可回退）" ($r.status -eq 400) $r.status

$r = Call 'Post' "$Gateway/api/seller/proxy-requests/WO-ORD-NOPE/void" $sessS $null
Check "撤销不存在的工单 → 404" ($r.status -eq 404) $r.status

# 正对照：8.2 复原归属后，同一张工单 + 同一个动作能过
$r = Call 'Post' "$Gateway/api/admin/orders/$o3/proxy-ship" $sessOp @{
    workOrderNo = $wo3; reason = '归属复原后代为发货'; logisticsNo = 'SF-PROXY-0003'
}
Check "★★ 正对照：同一张工单、同一个动作，归属对上就能过 → 200" ($r.status -eq 200) $r.status
Check "订单 3 转为 SHIPPED" ($r.body.data.status -eq 'SHIPPED') $r.body.data.status

## ============================================================================
## 11. 商品域：代下架
## ============================================================================
Write-Host ""
Write-Host "> 商品域代下架"

$r = Call 'Post' "$Gateway/api/seller/products/proxy-requests" $sessS @{
    targetType = 'PRODUCT'; targetRef = "$spuS"; actionType = 'OFF_SHELF'; reason = '该商品被投诉，请平台协助下架'
}
Check "★ 店主 S 申请代下架工单 → 200" ($r.status -eq 200) $r.status
$woP = "$($r.body.data.workOrderNo)"
Check "★ 工单号带商品域前缀 WO-PRD-" ($woP.StartsWith('WO-PRD-')) $woP

$r = Call 'Post' "$Gateway/api/seller/products/proxy-requests" $sessS @{
    targetType = 'PRODUCT'; targetRef = "$spuS"; actionType = 'SHIP'; reason = '订单类动作'
}
Check "★ 商品域入口不接受订单类动作 → 400" ($r.status -eq 400) $r.status

$r = Call 'Post' "$Gateway/api/seller/products/proxy-requests" $sessT @{
    targetType = 'PRODUCT'; targetRef = "$spuS"; actionType = 'OFF_SHELF'; reason = '别人的商品'
}
Check "★ 店主 T 对 S 的商品申请工单 → 404" ($r.status -eq 404) $r.status

$r = Call 'Post' "$Gateway/api/admin/products/$spuS/proxy-off-shelf" $sessOp @{
    workOrderNo = $woP; reason = '已与店主确认，代为下架'
}
Check "★★ 运营持本店有效工单代下架 → 200" ($r.status -eq 200) $r.status
Check "★ 商品状态变为 OFF_SHELF" ($r.body.data.status -eq 'OFF_SHELF') $r.body.data.status
Check "★ 商品域工单已被消费" `
    ((Sql 'duoduo_product' "SELECT status FROM t_proxy_work_order WHERE work_order_no='$woP';") -eq 'CONSUMED')

$r = Call 'Post' "$Gateway/api/admin/products/$spuS/proxy-off-shelf" $sessOp @{
    workOrderNo = $woP; reason = '再用一次'
}
Check "★★ 商品域工单再用一次 → 400" ($r.status -eq 400) $r.status

$r = Call 'Post' "$Gateway/api/admin/products/$spuS/proxy-off-shelf" $sessS @{
    workOrderNo = 'WO-PRD-X'; reason = '卖家越权'
}
Check "★ 卖家调用代下架 → 403" ($r.status -eq 403) $r.status

$r = Call 'Get' "$Gateway/api/admin/products/proxy-work-orders?targetRef=$spuS" $sessOp $null
Check "平台商品域待办（按目标筛选）→ 200" ($r.status -eq 200) $r.status

## ============================================================================
## 12. 审计留痕（三个动作可分别检索）
## ============================================================================
Write-Host ""
Write-Host "> 审计留痕"

$auditActorSql = "(SELECT id FROM t_user WHERE phone='$($op.phone)')"

Check "★ 代发货已留痕（ORDER_PROXY_SHIP）" `
    ((Sql 'duoduo_user' "SELECT COUNT(*) FROM t_audit_log WHERE action='ORDER_PROXY_SHIP' AND actor_id=$auditActorSql;") -ge '1')
Check "★ 代处理售后已留痕（AFTERSALE_PROXY_PROCESS）" `
    ((Sql 'duoduo_user' "SELECT COUNT(*) FROM t_audit_log WHERE action='AFTERSALE_PROXY_PROCESS' AND actor_id=$auditActorSql;") -ge '1')
Check "★ 代下架已留痕（PRODUCT_PROXY_OFF_SHELF）" `
    ((Sql 'duoduo_user' "SELECT COUNT(*) FROM t_audit_log WHERE action='PRODUCT_PROXY_OFF_SHELF' AND actor_id=$auditActorSql;") -ge '1')

# 留痕里必须带工单号 —— 不带的话，"平台代操作"与"平台自己操作"在台账上就分不出来
Check "★ 代发货的留痕里带着工单号（能追到是哪份授权）" `
    ((Sql 'duoduo_user' "SELECT COUNT(*) FROM t_audit_log WHERE action='ORDER_PROXY_SHIP' AND detail LIKE '%$wo1%';") -ge '1')
Check "★ 代下架的留痕里带着工单号" `
    ((Sql 'duoduo_user' "SELECT COUNT(*) FROM t_audit_log WHERE action='PRODUCT_PROXY_OFF_SHELF' AND detail LIKE '%$woP%';") -ge '1')

# 留痕的**类别**是有语义的：发货不动钱，敏感点在「身份」→ CONTENT；
# 代处理售后直接决定退款 → FUNDS（不可删改的台账）。归错类的后果是
# 「本该留三年的记录按普通内容记录处理」，而这不会让任何功能出错。
Check "★ 代发货留痕类别为 CONTENT（发货不动钱，敏感点在于身份）" `
    ((Sql 'duoduo_user' "SELECT DISTINCT category FROM t_audit_log WHERE action='ORDER_PROXY_SHIP' AND actor_id=$auditActorSql;") -eq 'CONTENT') `
    (Sql 'duoduo_user' "SELECT DISTINCT category FROM t_audit_log WHERE action='ORDER_PROXY_SHIP' AND actor_id=$auditActorSql;")
Check "★ 代处理售后留痕类别为 FUNDS（动钱 → 不可删改的台账）" `
    ((Sql 'duoduo_user' "SELECT DISTINCT category FROM t_audit_log WHERE action='AFTERSALE_PROXY_PROCESS' AND actor_id=$auditActorSql;") -eq 'FUNDS') `
    (Sql 'duoduo_user' "SELECT DISTINCT category FROM t_audit_log WHERE action='AFTERSALE_PROXY_PROCESS' AND actor_id=$auditActorSql;")

$denied = Sql 'duoduo_user' "SELECT COUNT(*) FROM t_audit_log WHERE action='ORDER_PROXY_SHIP' AND outcome='FAILED' AND actor_id=$auditActorSql;"
Check "★ 被拒的代运营尝试也留痕（outcome=FAILED，连续失败正是最该被看见的信号）" ([int]$denied -ge 1) $denied

## ============================================================================
## 13. 复原夹具（拆包自证）
## ============================================================================
Write-Host ""
Write-Host "> 复原夹具"

if (-not $SkipDbCheck) {
    # 13.1 商品归属（按原值写回，再自证与原值一致）
    $kv = "$origSupply" -split ':'
    if ($kv.Count -eq 3 -and $kv[0] -ne '') {
        Sql 'duoduo_product' "UPDATE t_spu SET seller_id = $($kv[1]), supply_type = '$($kv[2])' WHERE id = $($kv[0]);" | Out-Null
    }
    $restored = Sql 'duoduo_product' "SELECT CONCAT(id,':',IFNULL(seller_id,0),':',supply_type) FROM t_spu WHERE id = $spuS;"
    Check "（复原）SPU S 的归属与供货类型已按原值写回" ("$restored" -eq "$origSupply") "restored=$restored vs orig=$origSupply"

    # 13.2 商品状态（代下架会把 S 置为 OFF_SHELF）
    Sql 'duoduo_product' "UPDATE t_spu SET status='ON_SALE' WHERE id = $spuS;" | Out-Null
    Check "（复原）SPU S 已重新上架" `
        ((Sql 'duoduo_product' "SELECT status FROM t_spu WHERE id = $spuS;") -eq 'ON_SALE')

    # 13.3 自营夹具：现场建的必须现场拆掉（否则每跑一次就多一条脏 SPU）
    Sql 'duoduo_product' "DELETE FROM t_sku WHERE spu_id = $spuSelf;" | Out-Null
    Sql 'duoduo_product' "DELETE FROM t_spu WHERE id = $spuSelf;" | Out-Null
    Check "（复原）现场建的自营夹具 SPU 已删除（不往库里留脏数据）" `
        ((Sql 'duoduo_product' "SELECT COUNT(*) FROM t_spu WHERE id = $spuSelf;") -eq '0')

    # 13.3 工单：只删本次夹具账号申请的（按 requester_user_id 精确到本次运行）
    Sql 'duoduo_trade' "DELETE FROM t_proxy_work_order WHERE requester_user_id IN ($sidS,$sidT);" | Out-Null
    Sql 'duoduo_product' "DELETE FROM t_proxy_work_order WHERE requester_user_id IN ($sidS,$sidT);" | Out-Null
    $leftT = [int](Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_proxy_work_order WHERE requester_user_id IN ($sidS,$sidT);")
    $leftP = [int](Sql 'duoduo_product' "SELECT COUNT(*) FROM t_proxy_work_order WHERE requester_user_id IN ($sidS,$sidT);")
    Check "（复原）交易库本次工单已清空" ($leftT -eq 0) $leftT
    Check "（复原）商品库本次工单已清空" ($leftP -eq 0) $leftP

    # 13.4 库存复原
    Sql 'duoduo_product' "UPDATE t_sku SET stock = $stockBefore, frozen_stock = 0 WHERE id = $skuS;" | Out-Null
    Check "（复原）SKU 库存已还原为 $stockBefore" `
        ((Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $skuS;") -eq "$stockBefore")

    # 13.5 订单归属自证（本脚本只在 8.x 里临时改过，已在其后逐条改回）
    $badOwner = Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_order WHERE order_no IN ('$o1','$o2','$o3') AND seller_id <> $sidS;"
    Check "（复原）订单 1/2/3 的归属均已回到店主 S" ("$badOwner" -eq '0') $badOwner
}

# 收尾：收回运营角色（顺带清理该账号的审计行）
$roleBack = Remove-PlatformOperatorPrivilege -Phone $op.phone
Check "（收尾）临时提权的运营已降回 USER（不留永久后台钥匙）" ("$roleBack" -eq 'USER') $roleBack

## ============================================================================
## 汇总
## ============================================================================
Write-Host ""
Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
if ($fail -gt 0) {
    Write-Host "失败项："
    foreach ($f in $failures) { Write-Host "  - $f" }
    exit 1
}
exit 0
