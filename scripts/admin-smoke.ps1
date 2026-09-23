# 平台管理后台（M10）端到端冒烟（Windows PowerShell 5.1）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\admin-smoke.ps1
#
# 前置：容器全部 healthy（docker compose up -d），且已 mvn package + docker compose build 出最新镜像。
#
# 覆盖：
#   1. 权限边界 —— 未登录 401（网关拦）/ 普通用户 403（OperatorGuard）/ 运营放行；
#   2. 运营报表 + 资金对账（全平台口径）；
#   3. 类目 / 品牌的增改删与启停用；
#   4. 闲置审核：待审列表可见、通过后公开可查、驳回留痕（原因 + 时间）且公开不可见；
#   5. 订单干预：运营全平台列表、强制关单（含冻结释放）、重复关单被状态机拒绝、普通用户强关 403。
#
# 设计原则（沿用前几套脚本的教训）：
#   1. 夹具从接口动态取或自建自毁，不硬编码价格/库存，不碰种子数据；
#   2. 凡入库夹具（测试闲置）结尾必删，并自证「库内数据已复原」；
#   3. 运营账号由脚本自助注册 + DB 提权，结尾降回 USER，保证脚本可反复执行。
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    [string]$Password = 'duoduo123',
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
        return @{ status = [int]$r.StatusCode; body = ($r.Content | ConvertFrom-Json) }
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

$phoneOp = '137' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneU  = '136' + (Get-Random -Minimum 10000000 -Maximum 99999999)
# 测试闲置夹具 id：大数段，避开雪花 id 与种子数据
$listingId1 = 990000000000000000 + (Get-Random -Minimum 100000 -Maximum 899999)
$listingId2 = 990000000000000000 + (Get-Random -Minimum 100000 -Maximum 899999)

Write-Host ""
Write-Host "=== 多多宠物商店 · 平台管理后台（M10）冒烟 @ $Gateway ==="
Write-Host "运营账号 = $phoneOp ｜ 普通用户 = $phoneU"
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

# ---------- 1. 账号夹具：运营 + 普通用户 ----------
Write-Host ""
Write-Host "> 账号夹具"
$regU = Register $phoneU '后台测试-路人'
Check "普通用户注册/登录成功" ($regU.result.status -eq 200) $regU.result.status
$sessU = $regU.session

$regOp = Register $phoneOp '后台测试-运营'
Check "运营账号注册成功（初始为 USER）" ($regOp.result.status -eq 200) $regOp.result.status
# 提权为 OPERATOR 后重新登录，让新角色进入会话
Sql 'duoduo_user' "UPDATE t_user SET role = 'OPERATOR' WHERE phone = '$phoneOp';" | Out-Null
$opRole = Sql 'duoduo_user' "SELECT role FROM t_user WHERE phone = '$phoneOp';"
Check "库内角色已提权为 OPERATOR" ($opRole -eq 'OPERATOR') $opRole
$sessOp = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$loginOp = Call 'Post' "$Gateway/api/auth/login" $sessOp @{ phone = $phoneOp; password = $Password }
Check "运营账号重新登录成功" ($loginOp.status -eq 200) $loginOp.status

# ---------- 2. 权限边界 ----------
Write-Host ""
Write-Host "> 权限边界（401 / 403 / 放行）"
$anon = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$r = Call 'Get' "$Gateway/api/admin/reports/overview" $anon $null $null
Check "未登录访问后台返回 401（网关拦截）" ($r.status -eq 401) $r.status
$r = Call 'Get' "$Gateway/api/admin/reports/overview" $sessU $null $null
Check "普通用户访问后台返回 403（OperatorGuard）" ($r.status -eq 403) $r.status
$r = Call 'Get' "$Gateway/api/admin/reports/overview" $sessOp $null $null
Check "运营访问报表放行（200）" ($r.status -eq 200) $r.status
Check "报表含 GMV 与订单量字段" ($null -ne $r.body.data.gmv -and $null -ne $r.body.data.totalOrders) ($r.body.data | ConvertTo-Json -Compress)

# ---------- 3. 资金对账 ----------
Write-Host ""
Write-Host "> 资金对账（全平台口径）"
$r = Call 'Get' "$Gateway/api/admin/settlements/summary" $sessOp $null $null
Check "托管汇总返回 200" ($r.status -eq 200) $r.status
Check "汇总含托管/放款/退款三组金额" ($null -ne $r.body.data.custodyAmount -and $null -ne $r.body.data.releasedAmount -and $null -ne $r.body.data.refundedAmount) ($r.body.data | ConvertTo-Json -Compress)
$r = Call 'Get' "$Gateway/api/admin/settlements/abnormal" $sessOp $null $null
Check "异常挂账列表返回 200（数组）" ($r.status -eq 200 -and $r.body.data -is [array]) $r.status

# ---------- 4. 类目管理 ----------
Write-Host ""
Write-Host "> 类目管理（增改删 + 启停用）"
$catName = '冒烟类目' + (Get-Random -Minimum 1000 -Maximum 9999)
$r = Call 'Post' "$Gateway/api/admin/categories" $sessOp @{ name = $catName; parentId = 0; sortNo = 99; icon = '' }
Check "创建类目返回 200" ($r.status -eq 200) ($r.body | ConvertTo-Json -Compress)
$catId = [long]$r.body.data.id
$r = Call 'Put' "$Gateway/api/admin/categories/$catId" $sessOp @{ name = "$catName-改"; parentId = 0; sortNo = 98; icon = '' }
Check "更新类目返回 200 且名称生效" ($r.status -eq 200 -and $r.body.data.name -eq "$catName-改") ($r.body | ConvertTo-Json -Compress)
$r = Call 'Put' "$Gateway/api/admin/categories/$catId/status?active=false" $sessOp $null $null
Check "停用类目返回 200" ($r.status -eq 200) $r.status
$r = Call 'Get' "$Gateway/api/admin/categories" $sessOp $null $null
$catRow = @($r.body.data | Where-Object { $_.id -eq $catId })[0]
Check "后台类目列表可见且已停用" ($null -ne $catRow -and $catRow.status -eq 'INACTIVE') ($catRow | ConvertTo-Json -Compress)
$r = Call 'Delete' "$Gateway/api/admin/categories/$catId" $sessOp $null $null
Check "删除类目返回 200" ($r.status -eq 200) $r.status
$r = Call 'Get' "$Gateway/api/admin/categories" $sessOp $null $null
Check "删除后类目不再出现" (@($r.body.data | Where-Object { $_.id -eq $catId }).Count -eq 0)

# ---------- 5. 品牌管理 ----------
Write-Host ""
Write-Host "> 品牌管理（增改删 + 启停用）"
$brandName = '冒烟品牌' + (Get-Random -Minimum 1000 -Maximum 9999)
$r = Call 'Post' "$Gateway/api/admin/brands" $sessOp @{ name = $brandName; logo = ''; country = '中国'; story = '' }
Check "创建品牌返回 200" ($r.status -eq 200) ($r.body | ConvertTo-Json -Compress)
$brandId = [long]$r.body.data.id
$r = Call 'Put' "$Gateway/api/admin/brands/$brandId" $sessOp @{ name = "$brandName-改"; logo = ''; country = '中国'; story = '更新' }
Check "更新品牌返回 200 且名称生效" ($r.status -eq 200 -and $r.body.data.name -eq "$brandName-改") ($r.body | ConvertTo-Json -Compress)
$r = Call 'Put' "$Gateway/api/admin/brands/$brandId/status?active=false" $sessOp $null $null
Check "停用品牌返回 200" ($r.status -eq 200) $r.status
$r = Call 'Get' "$Gateway/api/admin/brands" $sessOp $null $null
$brandRow = @($r.body.data | Where-Object { $_.id -eq $brandId })[0]
Check "停用后品牌后台仍可见且标记 INACTIVE" ($null -ne $brandRow -and $brandRow.status -eq 'INACTIVE') ($brandRow | ConvertTo-Json -Compress)
$r = Call 'Delete' "$Gateway/api/admin/brands/$brandId" $sessOp $null $null
Check "删除品牌返回 200" ($r.status -eq 200) $r.status
$r = Call 'Get' "$Gateway/api/admin/brands" $sessOp $null $null
Check "删除后品牌不再出现" (@($r.body.data | Where-Object { $_.id -eq $brandId }).Count -eq 0)

# ---------- 6. 闲置审核 ----------
Write-Host ""
Write-Host "> 闲置内容审核（DB 夹具，自建自毁）"
$now = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
Sql 'duoduo_product' ("INSERT INTO t_listing (id, seller_id, title, description, price, original_price, condition_level, status, audit_status, created_at, updated_at) " +
    "VALUES ($listingId1, 1, '冒烟闲置-待审通过', 'admin-smoke 夹具', 12345, 20000, 'GOOD', 'ON_SALE', 'PENDING', '$now', '$now')," +
    "($listingId2, 1, '冒烟闲置-待审驳回', 'admin-smoke 夹具', 23456, 30000, 'FAIR', 'ON_SALE', 'PENDING', '$now', '$now');") | Out-Null
$cnt = Sql 'duoduo_product' "SELECT COUNT(*) FROM t_listing WHERE id IN ($listingId1, $listingId2);"
Check "（前置）两条待审闲置夹具已入库" ($cnt -eq '2') $cnt

$r = Call 'Get' "$Gateway/api/admin/listings?auditStatus=PENDING&pageSize=50" $sessOp $null $null
Check "待审列表返回 200" ($r.status -eq 200) $r.status
$ids = @($r.body.data.items | ForEach-Object { [long]$_.id })
Check "待审列表包含夹具闲置" (($ids -contains $listingId1) -and ($ids -contains $listingId2)) ($ids -join ',')

$r = Call 'Post' "$Gateway/api/admin/listings/$listingId1/audit" $sessOp @{ action = 'APPROVE' }
Check "审核通过返回 200" ($r.status -eq 200) ($r.body | ConvertTo-Json -Compress)
$dbAudit = Sql 'duoduo_product' "SELECT CONCAT(audit_status, '|', IF(audit_at IS NULL, 'NULL', 'SET')) FROM t_listing WHERE id = $listingId1;"
Check "库内状态 APPROVED 且审核时间已落库" ($dbAudit -eq 'APPROVED|SET') $dbAudit
$r = Call 'Get' "$Gateway/api/listings/$listingId1" $sessU $null $null
Check "通过后公开详情可查" ($r.status -eq 200) $r.status

$r = Call 'Post' "$Gateway/api/admin/listings/$listingId2/audit" $sessOp @{ action = 'REJECT' }
Check "驳回不填原因被拦截（4xx）" ($r.status -ge 400 -and $r.status -lt 500) $r.status
$r = Call 'Post' "$Gateway/api/admin/listings/$listingId2/audit" $sessOp @{ action = 'REJECT'; reason = '图片与描述不符' }
Check "填原因后驳回返回 200" ($r.status -eq 200) ($r.body | ConvertTo-Json -Compress)
# 注意：docker exec 的 mysql 输出走控制台 GBK，直接比对中文会得到假失败 ——
# 库内断言只判「状态 + 非空」，中文内容改走接口读回（JSON UTF-8 链路是干净的）
$dbReject = Sql 'duoduo_product' "SELECT CONCAT(audit_status, '|', IF(audit_reason IS NULL OR audit_reason = '', 'EMPTY', 'SET'), '|', IF(audit_at IS NULL, 'NULL', 'SET')) FROM t_listing WHERE id = $listingId2;"
Check "库内状态 REJECTED 且原因/时间已留痕" ($dbReject -eq 'REJECTED|SET|SET') $dbReject
$r = Call 'Get' "$Gateway/api/admin/listings?auditStatus=REJECTED&pageSize=50" $sessOp $null $null
$rejRow = @($r.body.data.items | Where-Object { [long]$_.id -eq $listingId2 })[0]
Check "驳回原因经接口读回（中文无乱码）" ($rejRow.auditReason -eq '图片与描述不符') $rejRow.auditReason
$r = Call 'Get' "$Gateway/api/listings/$listingId2" $sessU $null $null
Check "被驳回内容公开不可见（4xx）" ($r.status -ge 400 -and $r.status -lt 500) $r.status

$r = Call 'Post' "$Gateway/api/admin/listings/$listingId1/audit" $sessU @{ action = 'APPROVE' }
Check "普通用户审核返回 403" ($r.status -eq 403) $r.status

# ---------- 7. 订单干预 ----------
Write-Host ""
Write-Host "> 订单干预（强制关单）"
$r = Call 'Post' "$Gateway/api/cart/items" $sessU @{ skuId = $SKU; quantity = 1 }
Check "（准备）普通用户加购 SKU $SKU" ($r.status -eq 200) $r.status
$r = Call 'Post' "$Gateway/api/orders" $sessU @{ receiverName = '路人'; receiverPhone = $phoneU; receiverAddress = '上海市浦东新区张江路 1 号'; idempotentKey = (NewKey) }
Check "（准备）下单返回 200" ($r.status -eq 200) ($r.body | ConvertTo-Json -Compress)
$orderNo = @($r.body.data.orders)[0].orderNo
Check "（准备）订单为待付款" (@($r.body.data.orders)[0].status -eq 'PENDING_PAYMENT') @($r.body.data.orders)[0].status

$r = Call 'Get' "$Gateway/api/admin/orders?orderNo=$orderNo" $sessOp $null $null
Check "运营可按订单号查到该单" ($r.status -eq 200 -and @($r.body.data.items | Where-Object { $_.orderNo -eq $orderNo }).Count -eq 1) ($r.body | ConvertTo-Json -Compress)
$r = Call 'Get' "$Gateway/api/admin/orders/$orderNo" $sessOp $null $null
Check "运营可查订单详情（无归属限制）" ($r.status -eq 200 -and $r.body.data.orderNo -eq $orderNo) $r.status

$r = Call 'Post' "$Gateway/api/admin/orders/$orderNo/force-close" $sessU @{ reason = '越权尝试' }
Check "普通用户强制关单返回 403" ($r.status -eq 403) $r.status
$r = Call 'Post' "$Gateway/api/admin/orders/$orderNo/force-close" $sessOp @{ reason = '恶意占库存（冒烟）' }
Check "运营强制关单返回 200" ($r.status -eq 200) ($r.body | ConvertTo-Json -Compress)
Check "关单后状态为 CLOSED" ($r.body.data.status -eq 'CLOSED') $r.body.data.status
$r = Call 'Get' "$Gateway/api/orders/$orderNo" $sessU $null $null
Check "买家侧可见关单原因" ($r.body.data.closeReason -like '*恶意占库存*') $r.body.data.closeReason
$r = Call 'Post' "$Gateway/api/admin/orders/$orderNo/force-close" $sessOp @{ reason = '重复关单' }
Check "已关闭订单再次强关被状态机拒绝（4xx）" ($r.status -ge 400 -and $r.status -lt 500) $r.status
$frozen = Sql 'duoduo_product' "SELECT COALESCE(SUM(frozen_stock),0) FROM t_sku WHERE id = $SKU;"
Check "关单后 SKU 冻结量已释放" ($frozen -eq '0') $frozen

# ---------- 8. 清理与复原 ----------
Write-Host ""
Write-Host "> 清理夹具"
Sql 'duoduo_product' "DELETE FROM t_listing WHERE id IN ($listingId1, $listingId2);" | Out-Null
$left = Sql 'duoduo_product' "SELECT COUNT(*) FROM t_listing WHERE id IN ($listingId1, $listingId2);"
Check "闲置夹具已删除（库内复原）" ($left -eq '0') $left
Sql 'duoduo_user' "UPDATE t_user SET role = 'USER' WHERE phone = '$phoneOp';" | Out-Null
$roleBack = Sql 'duoduo_user' "SELECT role FROM t_user WHERE phone = '$phoneOp';"
Check "运营账号已降回 USER（库内复原）" ($roleBack -eq 'USER') $roleBack

# ---------- 结果 ----------
Write-Host ""
Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
if ($fail -gt 0) {
    Write-Host "失败项："
    $failures | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
exit 0
