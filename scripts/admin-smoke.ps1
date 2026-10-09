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
#   5. 订单干预：运营全平台列表、强制关单（含冻结释放）、重复关单被状态机拒绝、普通用户强关 403；
#   6. 运营账号管理（A7）：普通用户 403 / 运营可查脱敏列表但改角色 403 / ADMIN 提权 200 +
#      「下一个请求即生效（无需重新登录）」提示（E17 口径）/ 旧令牌**下一个请求即 401** /
#      审计日志落库 /
#      同角色幂等不留痕 / 自己降自己 422 / 最后一个 ADMIN 不可降 422 / keyword + role 检索。
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

# ---------- 8. 运营账号管理（A7） ----------
Write-Host ""
Write-Host "> 运营账号管理（A7：列表 / 改角色 / 审计 / 边界）"

# 夹具：第三个账号 DB 提权为 ADMIN（临时唯一管理员，结尾降回 USER）
$phoneAd = '138' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$regAd = Register $phoneAd '后台测试-管理员'
Check "（A7 前置）管理员账号注册成功" ($regAd.result.status -eq 200) $regAd.result.status
Sql 'duoduo_user' "UPDATE t_user SET role = 'ADMIN' WHERE phone = '$phoneAd';" | Out-Null
$sessAd = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$loginAd = Call 'Post' "$Gateway/api/auth/login" $sessAd @{ phone = $phoneAd; password = $Password }
Check "（A7 前置）管理员重新登录成功" ($loginAd.status -eq 200) $loginAd.status
# 认证响应 data 直接是 UserView（令牌走 httpOnly Cookie），id 是雪花大数字符串
$uidAd = [long]$loginAd.body.data.id
$uidU  = [long]$regU.result.body.data.id

# —— 边界：进后台（OperatorGuard） ——
$anonA7 = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$r = Call 'Get' "$Gateway/api/admin/users" $anonA7 $null $null
Check "A7 未登录查用户列表返回 401（网关拦截）" ($r.status -eq 401) $r.status
$r = Call 'Get' "$Gateway/api/admin/users" $sessU $null $null
Check "A7 普通用户查用户列表返回 403（OperatorGuard）" ($r.status -eq 403) $r.status

# —— 运营可查列表（脱敏） ——
$r = Call 'Get' "$Gateway/api/admin/users?pageSize=5" $sessOp $null $null
Check "A7 运营查用户列表返回 200" ($r.status -eq 200) $r.status
$row0 = @($r.body.data.items)[0]
Check "A7 列表字段脱敏（手机号掩码）" ($null -ne $row0 -and $row0.phoneMasked -match '^\d{3}\*{4}\d{4}$') ($row0 | ConvertTo-Json -Compress)
$hasSensitive = ($null -ne $row0.PSObject.Properties['realName']) -or ($null -ne $row0.PSObject.Properties['idCardMasked'])
Check "A7 列表不下发实名姓名 / 身份证字段" (-not $hasSensitive) ($row0 | ConvertTo-Json -Compress)

# —— 检索 ——
$r = Call 'Get' "$Gateway/api/admin/users?keyword=$phoneU&pageSize=20" $sessOp $null $null
$hitU = @($r.body.data.items | Where-Object { [long]$_.id -eq $uidU }).Count
Check "A7 keyword=手机号命中目标用户" ($r.status -eq 200 -and $hitU -eq 1) ($r.body | ConvertTo-Json -Compress)
$r = Call 'Get' "$Gateway/api/admin/users?role=ADMIN&pageSize=20" $sessOp $null $null
$hitAd = @($r.body.data.items | Where-Object { [long]$_.id -eq $uidAd }).Count
Check "A7 role=ADMIN 过滤命中管理员夹具" ($r.status -eq 200 -and $hitAd -eq 1) ($r.body | ConvertTo-Json -Compress)

# —— 改角色：两层权限门 ——
$r = Call 'Post' "$Gateway/api/admin/users/$uidU/role" $sessOp @{ role = 'OPERATOR' }
Check "A7 运营（OPERATOR）改角色返回 403（仅 ADMIN）" ($r.status -eq 403) $r.status
$r = Call 'Post' "$Gateway/api/admin/users/$uidU/role" $sessAd @{ role = 'VIP' }
Check "A7 非法角色名返回 422" ($r.status -eq 422) $r.status
$r = Call 'Post' "$Gateway/api/admin/users/$uidAd/role" $sessAd @{ role = 'USER' }
Check "A7 管理员给自己降权返回 422" ($r.status -eq 422) $r.status

# —— ADMIN 提权 + 验收②（E17 起：下一个请求即生效，无需重登） ——
$r = Call 'Post' "$Gateway/api/admin/users/$uidU/role" $sessAd @{ role = 'OPERATOR' }
Check "A7 管理员提权返回 200" ($r.status -eq 200) ($r.body | ConvertTo-Json -Compress)
Check "A7 响应角色已更新为 OPERATOR" ($r.body.data.user.role -eq 'OPERATOR') $r.body.data.user.role
# ★ 这条断言的**方向**在 E17 反过来了，必须写清否则会变成假绿：
#   E17 之前文案是「已吊销刷新令牌…将重新登录后生效」，于是 `-match '重新登录'` 是有效的；
#   E17 之后成功文案是「…在下一个请求即生效（**无需**重新登录）」—— 里面**同样含**这四个字。
#   继续用 `-match '重新登录'` 会让它无条件通过（连"要不要重登"这件事都没在测）。
Check "A7 提示文案含『下一个请求即生效』" ("$($r.body.data.notice)" -match '下一个请求即生效') $r.body.data.notice
Check "A7 成功路径的文案明确『无需重新登录』（不能反过来命中『重新登录』）" `
    ("$($r.body.data.notice)" -match '无需重新登录' -and "$($r.body.data.notice)" -notmatch '将重新登录') $r.body.data.notice
# 旧令牌现在被**直接拒绝**（401），而不是"保留旧角色、只是拿不到新权限"（403）。
# 这是更强的保证：旧令牌不再有任何作用面。断言必须跟着改成 401 —— 否则这条会一直红，
# 而且红的样子像是"越权（403 变 401 了？）"，容易被误判成缺陷。
$r = Call 'Get' "$Gateway/api/admin/users" $sessU $null $null
Check "A7 被提权用户旧令牌下一个请求即失效 → 401（E17 收口，比『仅不放大』更强）" ($r.status -eq 401) `
    "status=$($r.status) raw=$($r.raw)"
$sessU2 = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$r = Call 'Post' "$Gateway/api/auth/login" $sessU2 @{ phone = $phoneU; password = $Password }
Check "A7 被提权用户重新登录拿到 OPERATOR 角色" ($r.status -eq 200 -and $r.body.data.role -eq 'OPERATOR') ($r.body | ConvertTo-Json -Compress -Depth 6)
$r = Call 'Get' "$Gateway/api/admin/users?pageSize=1" $sessU2 $null $null
Check "A7 重新登录后访问后台生效（200）" ($r.status -eq 200) $r.status

# —— 审计日志（谁给谁改了什么；detail 为 ASCII，直接比对安全） ——
$audit = Sql 'duoduo_user' "SELECT CONCAT(actor_id, '|', action, '|', detail, '|', target_id) FROM t_audit_log WHERE target_id = $uidU AND detail = 'USER -> OPERATOR' ORDER BY created_at DESC LIMIT 1;"
Check "A7 审计日志已落库（管理员 → 目标用户 USER->OPERATOR）" ("$audit" -eq "$uidAd|USER_ROLE_CHANGE|USER -> OPERATOR|$uidU") $audit

# —— 幂等：同角色重复变更不留审计 ——
$before = Sql 'duoduo_user' "SELECT COUNT(*) FROM t_audit_log WHERE target_id = $uidU;"
$r = Call 'Post' "$Gateway/api/admin/users/$uidU/role" $sessAd @{ role = 'OPERATOR' }
$after = Sql 'duoduo_user' "SELECT COUNT(*) FROM t_audit_log WHERE target_id = $uidU;"
Check "A7 同角色重复变更幂等（200）" ($r.status -eq 200) $r.status
Check "A7 幂等调用不产生新审计事件" ("$before" -eq "$after") "$before -> $after"

# —— 最后一个 ADMIN 不可降级（动态口径：若平台恰有一名 ADMIN 则 422，否则放行并复原） ——
$adminCount = Sql 'duoduo_user' "SELECT COUNT(*) FROM t_user WHERE role = 'ADMIN';"
$r = Call 'Post' "$Gateway/api/admin/users/$uidAd/role" $sessAd @{ role = 'OPERATOR' }
if ("$adminCount" -eq '1') {
    Check "A7 最后一个 ADMIN 不可降级返回 422" ($r.status -eq 422) $r.status
} else {
    Check "A7 平台存在多名 ADMIN，降级放行（动态口径 200）" ($r.status -eq 200) $r.status
    Sql 'duoduo_user' "UPDATE t_user SET role = 'ADMIN' WHERE phone = '$phoneAd';" | Out-Null
}

# ---------- 9. 清理与复原 ----------
Write-Host ""
Write-Host "> 清理夹具"
Sql 'duoduo_product' "DELETE FROM t_listing WHERE id IN ($listingId1, $listingId2);" | Out-Null
$left = Sql 'duoduo_product' "SELECT COUNT(*) FROM t_listing WHERE id IN ($listingId1, $listingId2);"
Check "闲置夹具已删除（库内复原）" ($left -eq '0') $left
Sql 'duoduo_user' "UPDATE t_user SET role = 'USER' WHERE phone IN ('$phoneOp', '$phoneAd');" | Out-Null
$roleBack = Sql 'duoduo_user' "SELECT COUNT(*) FROM t_user WHERE phone IN ('$phoneOp', '$phoneAd') AND role = 'USER';"
Check "运营 / 管理员账号已降回 USER（库内复原）" ("$roleBack" -eq '2') $roleBack
# A7 审计夹具行一并清掉（自建自毁，不污染真实审计流水）
Sql 'duoduo_user' "DELETE FROM t_audit_log WHERE actor_id = $uidAd AND target_id IN ($uidU, $uidAd);" | Out-Null
$auditLeft = Sql 'duoduo_user' "SELECT COUNT(*) FROM t_audit_log WHERE actor_id = $uidAd;"
Check "A7 审计日志夹具行已清除（库内复原）" ("$auditLeft" -eq '0') $auditLeft

# ---------- 结果 ----------
Write-Host ""
Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
if ($fail -gt 0) {
    Write-Host "失败项："
    $failures | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
exit 0
