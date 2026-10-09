# 交易服务 · 购物车端到端冒烟（Windows PowerShell 5.1）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\trade-smoke.ps1
#
# 前置：容器全部 healthy（docker compose up -d），且已 mvn package 出最新 jar。
#
# 设计原则（血泪教训换来的）：
#   1. **夹具从接口动态取**，不硬编码库存/价格 —— 种子数据一改，硬编码的期望值就会
#      变成「测试通过但实际是错的」或「服务没错却报失败」。
#   2. **凡改库必先记录原值**，复原时写回原值而不是写死一个数字，
#      否则每跑一次测试就把种子数据改坏一次（本脚本早期版本确实干过这事）。
#   3. 错误响应体用 $_.ErrorDetails.Message 取：通过网关返回的 401
#      用 Response.GetResponseStream() 读出来是空的（已实测）。
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
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

# 统一请求：返回 @{ status=..; body=.. }，非 2xx 不抛异常
. (Join-Path $PSScriptRoot 'lib\internal-token.ps1')
. (Join-Path $PSScriptRoot 'lib\catalog-fixture.ps1')

function Call([string]$method, [string]$uri, $session, $bodyObj, [hashtable]$headers) {
    $params = @{
        Uri             = $uri
        Method          = $method
        UseBasicParsing = $true
        TimeoutSec      = 20
    }
    if ($session) { $params.WebSession = $session }
    # 内部凭据哨兵：'internal' 表示「这条要走服务间调用」，由这里现场签发签名
    # （签名里带一次性 nonce，必须在调用前生成，不能复用同一个请求头）
    if ($headers -and $headers.ContainsKey('X-Internal-Token') -and $headers['X-Internal-Token'] -eq 'internal') {
        $headers = @{} + $headers
        $headers['X-Internal-Token'] = (New-InternalToken -Method $method -Path ([System.Uri]$uri).AbsolutePath)
    }
    if ($headers) { $params.Headers = $headers }
    if ($null -ne $bodyObj) {
        $params.Body = ($bodyObj | ConvertTo-Json -Compress)
        $params.ContentType = 'application/json; charset=utf-8'
    }
    try {
        $r = Invoke-WebRequest @params
        return @{ status = [int]$r.StatusCode; body = ($r.Content | ConvertFrom-Json) }
    } catch {
        $code = 0
        $body = $null
        try { $code = [int]$_.Exception.Response.StatusCode.value__ } catch { }
        # 先试 ErrorDetails（网关 401 只有这条路能拿到 body），再退回读流
        try {
            if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
                $body = $_.ErrorDetails.Message | ConvertFrom-Json
            }
        } catch { }
        if ($null -eq $body) {
            try {
                # 跨 PS 版本：PS5.1 用 GetResponseStream，pwsh 7（CI Linux）用 Content.ReadAsStringAsync
                $resp = $_.Exception.Response
                $text = $null
                if ($resp -and $resp.PSObject.Methods.Name -contains 'GetResponseStream') {
                    $stream = $resp.GetResponseStream()
                    if ($stream) { $text = (New-Object System.IO.StreamReader($stream)).ReadToEnd() }
                } elseif ($resp -and $null -ne $resp.Content) {
                    $text = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                }
                if ($text) { $body = $text | ConvertFrom-Json }
            } catch { }
        }
        return @{ status = $code; body = $body }
    }
}

function Register([string]$phone, [string]$nickname) {
    $s = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $r = Call 'Post' "$Gateway/api/auth/register" $s @{ phone = $phone; password = $Password; nickname = $nickname }
    if ($r.status -eq 409) {
        $r = Call 'Post' "$Gateway/api/auth/login" $s @{ phone = $phone; password = $Password }
    }
    return @{ session = $s; result = $r }
}

function Sql([string]$sql) {
    $env:PATH = 'C:\Program Files\Docker\Docker\resources\bin;' + $env:PATH
    if (-not $env:DOCKER_CONFIG) { $env:DOCKER_CONFIG = 'D:\duoduo-infra\docker-nocreds' }
    $out = & docker exec duoduo-mysql mysql -uduoduo -pduoduo -N -B --default-character-set=utf8mb4 -D duoduo_product -e $sql 2>&1
    if ($LASTEXITCODE -ne 0) { return $null }
    return ((@($out) | Where-Object { "$_" -notmatch 'Warning' }) -join "`n").Trim()
}

$phoneA = '139' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneB = '138' + (Get-Random -Minimum 10000000 -Maximum 99999999)

# 参考 SKU：**现场解析**（E27），不再写死 2001/2002/2004/2009/2021。
# 原先写死的那几个号（尤其"库存较小的 2004 / 售罄的 2009 / 已下架的 2021"）
# 在本机库经多轮清理后已不存在 → 夹具读到 0 → 「本机红、CI 绿」，
# 谁都不确定是数据漂移还是代码回归。现在改为「按需解析、缺啥建啥」，跑完删除。
if ($SkipDbCheck) {
    # 保留一条无库访问的退路（-SkipDbCheck 的语义就是"不碰库"）。
    # 注意：这条路径在数据漂移的库上**本来就会红** —— 它只用于「有库但想跳过库断言」的排查场景，
    # run-all-smoke.ps1 与 ci-run.ps1 从不传这个开关。
    $SKU_A = 2001
    $SKU_B = 2002
    $SKU_TIGHT = 2004
    $SKU_SOLD_OUT = 2009
    $SKU_OFF_SHELF = 2021
    $fxSku = $null
} else {
    $fxSku = Resolve-SkuFixtures -MinStock 5
    $SKU_A = $fxSku.A
    $SKU_B = $fxSku.B
    $SKU_TIGHT = $fxSku.Tight
    $SKU_SOLD_OUT = $fxSku.SoldOut
    $SKU_OFF_SHELF = $fxSku.OffShelf
}
$SKU_MISSING = 999999

Write-Host ""
Write-Host "=== 多多宠物商店 · 交易服务（购物车）冒烟 @ $Gateway ==="
Write-Host "用户 A = $phoneA"
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

# ---------- 0b. 批量 SKU 接口（交易服务加购校验所依赖的内部接口） ----------
Write-Host ""
Write-Host "> 批量 SKU 接口（夹具来源）"
$batch = Call 'Get' "$Gateway/api/products/internal/skus/batch?ids=$SKU_A,$SKU_B,$SKU_TIGHT,$SKU_SOLD_OUT,$SKU_OFF_SHELF" $null $null @{ 'X-Internal-Token' = 'internal' }
Check "批量 SKU 接口返回 200" ($batch.status -eq 200) $batch.status
# 注意：PowerShell 哈希表按键的**类型相等**匹配，[long]2001 与 [int]2001 不相等，
# 用数值键会静默查不到（返回 $null，进而把价格库存都当 0）—— 所以统一用字符串键。
$skus = @{}
foreach ($x in @($batch.body.data)) { $skus["$($x.skuId)"] = $x }
Check "返回 5 个 SKU" ($skus.Count -eq 5) $skus.Count
Check "可售 SKU 标记 sellable=true" ($skus["$SKU_A"].sellable -eq $true) $skus["$SKU_A"].sellable
Check "售罄 SKU 标记 sellable=false" ($skus["$SKU_SOLD_OUT"].sellable -eq $false) $skus["$SKU_SOLD_OUT"].sellable
Check "下架 SKU 标记 sellable=false" ($skus["$SKU_OFF_SHELF"].sellable -eq $false) $skus["$SKU_OFF_SHELF"].sellable
Check "售罄 SKU 可售库存为 0" ($skus["$SKU_SOLD_OUT"].availableStock -eq 0) $skus["$SKU_SOLD_OUT"].availableStock

$P1 = [long]$skus["$SKU_A"].price
$P2 = [long]$skus["$SKU_B"].price
$P4 = [long]$skus["$SKU_TIGHT"].price
$S1 = [int]$skus["$SKU_A"].availableStock
$S4 = [int]$skus["$SKU_TIGHT"].availableStock
Write-Host "  夹具：2001 单价 $P1 库存 $S1 ｜ 2002 单价 $P2 ｜ 2004 单价 $P4 库存 $S4"

# 夹具读不到就立刻终止：带着 0 继续跑会「写库 + 用错误期望断言」，
# 既可能污染种子数据，也会产生一堆误导性的失败。
if ($P1 -le 0 -or $P2 -le 0 -or $P4 -le 0 -or $S1 -lt 5 -or $S4 -lt 1 -or $S4 -ge 200) {
    Write-Host ""
    Write-Host "夹具前提不满足（单价或库存读到 0 / 库存不适合做边界断言），终止本次测试，不执行任何写操作。"
    Check "夹具前提：价格与库存均从接口正确读到" $false "P1=$P1 P2=$P2 P4=$P4 S1=$S1 S4=$S4"
    Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
    exit 1
}
Check "夹具前提：2001 库存 ≥ 5（够做累加测试）" ($S1 -ge 5) $S1
Check "夹具前提：2004 库存 < 200（够做「超库存」测试且不触发数量上限）" ($S4 -lt 200) $S4

# ---------- 1. 未登录 → 401 ----------
Write-Host ""
Write-Host "> 未认证拦截"
$anon = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$r = Call 'Get' "$Gateway/api/cart" $anon $null
Check "未带令牌访问 /api/cart 返回 401" ($r.status -eq 401) $r.status
Check "401 响应体为规范化错误结构（code=UNAUTHORIZED）" ($r.body.code -eq 'UNAUTHORIZED') $r.body.code
Check "401 响应体带 requestId" (-not [string]::IsNullOrEmpty($r.body.requestId)) $r.body.requestId

# ---------- 2. 登录 ----------
Write-Host ""
Write-Host "> 认证"
$regA = Register $phoneA '购物车测试甲'
Check "用户 A 注册/登录成功（200）" ($regA.result.status -eq 200) $regA.result.status
$sessA = $regA.session

# ---------- 3. 空购物车 ----------
Write-Host ""
Write-Host "> 空购物车"
$r = Call 'Get' "$Gateway/api/cart" $sessA $null
Check "空购物车返回 200" ($r.status -eq 200) $r.status
Check "空购物车条目数为 0" ($r.body.data.itemCount -eq 0) $r.body.data.itemCount
Check "空购物车结算金额为 0" ($r.body.data.selectedAmount -eq 0) $r.body.data.selectedAmount

# ---------- 4. 加购与累加 ----------
Write-Host ""
Write-Host "> 加购（同 SKU 累加，不新增行）"
$r = Call 'Post' "$Gateway/api/cart/items" $sessA @{ skuId = $SKU_A; quantity = 2 }
Check "加购 2001 × 2 返回 200" ($r.status -eq 200) $r.status
Check "返回数量为 2" ($r.body.data.quantity -eq 2) $r.body.data.quantity
Check "返回单价与商品服务一致（$P1 分）" ($r.body.data.price -eq $P1) $r.body.data.price
Check "返回小计 = 单价 × 2" ($r.body.data.amount -eq ($P1 * 2)) $r.body.data.amount
Check "带出商品标题" (-not [string]::IsNullOrEmpty($r.body.data.title)) $r.body.data.title
Check "带出规格文本" (-not [string]::IsNullOrEmpty($r.body.data.specText)) $r.body.data.specText
Check "新加购项默认已勾选" ($r.body.data.selected -eq $true) $r.body.data.selected

$r = Call 'Post' "$Gateway/api/cart/items" $sessA @{ skuId = $SKU_A; quantity = 3 }
Check "重复加购 2001 × 3 累加为 5 件" ($r.body.data.quantity -eq 5) $r.body.data.quantity

$r = Call 'Get' "$Gateway/api/cart" $sessA $null
Check "同 SKU 重复加购是累加而非新增行（条目数=1）" ($r.body.data.itemCount -eq 1) $r.body.data.itemCount

$r = Call 'Post' "$Gateway/api/cart/items" $sessA @{ skuId = $SKU_B; quantity = 1 }
Check "加购 2002 × 1 返回 200" ($r.status -eq 200) $r.status

$r = Call 'Get' "$Gateway/api/cart" $sessA $null
Check "购物车两条（不同 SKU）" ($r.body.data.itemCount -eq 2) $r.body.data.itemCount
Check "商品件数合计 6" ($r.body.data.totalQuantity -eq 6) $r.body.data.totalQuantity
Check "已勾选结算金额 = 单价1×5 + 单价2" ($r.body.data.selectedAmount -eq ($P1 * 5 + $P2)) $r.body.data.selectedAmount
Check "同 SPU 1001 下的两个 SKU 都带出标题" ((@($r.body.data.items | Where-Object { $_.spuId -eq 1001 }).Count) -eq 2) (@($r.body.data.items).Count)

# ---------- 5. 参数边界与不可售 SKU ----------
Write-Host ""
Write-Host "> 参数边界"
$r = Call 'Post' "$Gateway/api/cart/items" $sessA @{ skuId = $SKU_A; quantity = 0 }
Check "加购数量 0 被边界拦截（400）" ($r.status -eq 400) $r.status
Check "边界错误码为 VALIDATION_ERROR" ($r.body.code -eq 'VALIDATION_ERROR') $r.body.code
$r = Call 'Post' "$Gateway/api/cart/items" $sessA @{ skuId = $SKU_A; quantity = 201 }
Check "加购数量 201 超过上限被拦截（400）" ($r.status -eq 400) $r.status
$r = Call 'Post' "$Gateway/api/cart/items" $sessA @{ quantity = 1 }
Check "缺少 skuId 被拦截（400）" ($r.status -eq 400) $r.status

Write-Host ""
Write-Host "> 不可售商品"
$r = Call 'Post' "$Gateway/api/cart/items" $sessA @{ skuId = $SKU_MISSING; quantity = 1 }
Check "加购不存在的 SKU 返回 404" ($r.status -eq 404) $r.status
$r = Call 'Post' "$Gateway/api/cart/items" $sessA @{ skuId = $SKU_SOLD_OUT; quantity = 1 }
Check "加购已售罄 SKU 返回 422" ($r.status -eq 422) $r.status
Check "售罄错误码为 STOCK_INSUFFICIENT" ($r.body.code -eq 'STOCK_INSUFFICIENT') $r.body.code
$r = Call 'Post' "$Gateway/api/cart/items" $sessA @{ skuId = $SKU_OFF_SHELF; quantity = 1 }
Check "加购已下架 SKU 返回 422" ($r.status -eq 422) $r.status
Check "下架错误码为 BUSINESS_RULE" ($r.body.code -eq 'BUSINESS_RULE') $r.body.code

Write-Host ""
Write-Host "> 库存上限（用 2004，库存 $S4 < 200，可精确命中「超库存」而非「超数量上限」）"
$r = Call 'Post' "$Gateway/api/cart/items" $sessA @{ skuId = $SKU_TIGHT; quantity = ($S4 + 1) }
Check "加购数量 = 库存+1 返回 422" ($r.status -eq 422) $r.status
Check "超库存错误码为 STOCK_INSUFFICIENT" ($r.body.code -eq 'STOCK_INSUFFICIENT') $r.body.code
$r = Call 'Post' "$Gateway/api/cart/items" $sessA @{ skuId = $SKU_TIGHT; quantity = $S4 }
Check "加购数量 = 库存（边界）返回 200" ($r.status -eq 200) $r.status
Check "边界数量小计 = 单价4 × 库存" ($r.body.data.amount -eq ($P4 * $S4)) $r.body.data.amount
$r = Call 'Delete' "$Gateway/api/cart/items/$SKU_TIGHT" $sessA $null
Check "清理边界测试用的 2004 条目" ($r.status -eq 200) $r.status

# ---------- 6. 改数量 / 勾选 / 合计 ----------
Write-Host ""
Write-Host "> 改数量与勾选"
$r = Call 'Patch' "$Gateway/api/cart/items/$SKU_A" $sessA @{ quantity = 3 }
Check "改数量 2001 → 3 返回 200" ($r.status -eq 200) $r.status
Check "改数量后小计 = 单价1 × 3" ($r.body.data.amount -eq ($P1 * 3)) $r.body.data.amount

$r = Call 'Get' "$Gateway/api/cart" $sessA $null
Check "改数量后结算金额 = 单价1×3 + 单价2" ($r.body.data.selectedAmount -eq ($P1 * 3 + $P2)) $r.body.data.selectedAmount

$r = Call 'Patch' "$Gateway/api/cart/items/999999" $sessA @{ quantity = 1 }
Check "改不存在条目的数量返回 404" ($r.status -eq 404) $r.status
$r = Call 'Patch' "$Gateway/api/cart/items/$SKU_A" $sessA @{ quantity = 0 }
Check "改数量为 0 被拦截（400）" ($r.status -eq 400) $r.status

$r = Call 'Post' "$Gateway/api/cart/items" $sessA @{ skuId = $SKU_TIGHT; quantity = 1 }
Check "再次加购 2004 × 1 返回 200" ($r.status -eq 200) $r.status
$r = Call 'Patch' "$Gateway/api/cart/items/$SKU_TIGHT" $sessA @{ quantity = ($S4 + 1) }
Check "改数量为「库存+1」返回 422" ($r.status -eq 422) $r.status
$r = Call 'Delete' "$Gateway/api/cart/items/$SKU_TIGHT" $sessA $null
Check "清理 2004 条目" ($r.status -eq 200) $r.status

$r = Call 'Patch' "$Gateway/api/cart/items/$SKU_A/selection" $sessA @{ selected = $false }
Check "取消勾选返回 200" ($r.status -eq 200) $r.status
$r = Call 'Get' "$Gateway/api/cart" $sessA $null
Check "取消勾选后已选条数 1" ($r.body.data.selectedCount -eq 1) $r.body.data.selectedCount
Check "取消勾选后结算金额仅剩 单价2" ($r.body.data.selectedAmount -eq $P2) $r.body.data.selectedAmount
Check "取消勾选不影响商品件数（仍 4 件）" ($r.body.data.totalQuantity -eq 4) $r.body.data.totalQuantity

$r = Call 'Patch' "$Gateway/api/cart/items/$SKU_A/selection" $sessA @{ selected = $true }
Check "重新勾选返回 200" ($r.status -eq 200) $r.status
$r = Call 'Get' "$Gateway/api/cart" $sessA $null
Check "重新勾选后结算金额复原" ($r.body.data.selectedAmount -eq ($P1 * 3 + $P2)) $r.body.data.selectedAmount

# ---------- 7. 服务端失效判定（改库模拟，且**先记录原值**） ----------
Write-Host ""
Write-Host "> 失效判定（服务端给出原因码，且失效项不计入结算金额）"
if ($SkipDbCheck) {
    Write-Host "  [SKIP] 已指定 -SkipDbCheck"
} else {
    $dbStockBefore = Sql "SELECT stock FROM t_sku WHERE id = $SKU_A;"
    Check "（前置）库内 2001 库存 = $S1，与接口一致" ($dbStockBefore -eq "$S1") $dbStockBefore

    $r = Call 'Post' "$Gateway/api/cart/items" $sessA @{ skuId = $SKU_A; quantity = 1 }
    Check "（前置）确保 2001 在购物车中" ($r.status -eq 200) $r.status

    Sql "UPDATE t_sku SET stock = 0 WHERE id = $SKU_A;" | Out-Null
    $after = Sql "SELECT stock FROM t_sku WHERE id = $SKU_A;"
    Check "（准备）把 SKU 2001 库存改为 0 已生效" ($after -eq '0') $after
    if ($after -eq '0') {
        $r = Call 'Get' "$Gateway/api/cart" $sessA $null
        $itemA = @($r.body.data.items | Where-Object { $_.skuId -eq $SKU_A })[0]
        Check "库存归零后该条标记为失效" ($itemA.valid -eq $false) $itemA.valid
        Check "失效原因码为 OUT_OF_STOCK" ($itemA.invalidReason -eq 'OUT_OF_STOCK') $itemA.invalidReason
        Check "失效项小计归零" ($itemA.amount -eq 0) $itemA.amount
        Check "失效项不计入结算金额（仅剩 单价2）" ($r.body.data.selectedAmount -eq $P2) $r.body.data.selectedAmount
        Check "失效原因集合返回给前端" ((@($r.body.data.invalidReasons) -contains 'OUT_OF_STOCK')) (@($r.body.data.invalidReasons) -join ',')

        Sql "UPDATE t_sku SET stock = $S1 WHERE id = $SKU_A;" | Out-Null
        $back = Sql "SELECT stock FROM t_sku WHERE id = $SKU_A;"
        Check "（复原）库存写回**原值 $S1**（不是写死数字）" ($back -eq "$S1") $back
        $r = Call 'Get' "$Gateway/api/cart" $sessA $null
        Check "库存复原后该条重新有效，结算金额复原" ($r.body.data.selectedAmount -eq ($P1 * 4 + $P2)) $r.body.data.selectedAmount

        Sql "UPDATE t_spu SET status = 'OFF_SHELF' WHERE id = 1001;" | Out-Null
        $r = Call 'Get' "$Gateway/api/cart" $sessA $null
        $itemA = @($r.body.data.items | Where-Object { $_.skuId -eq $SKU_A })[0]
        Check "SPU 整店下架后条目失效" ($itemA.valid -eq $false) $itemA.valid
        Check "下架原因码为 OFF_SHELF" ($itemA.invalidReason -eq 'OFF_SHELF') $itemA.invalidReason
        # 2001 与 2002 同属 SPU 1001，整店下架时两条都失效，因此结算金额归零（不是只剩 2002）
        Check "SPU 下架后同 SPU 的两条都失效" ((@($r.body.data.items | Where-Object { $_.valid -eq $false }).Count) -eq 2) (@($r.body.data.items | Where-Object { $_.valid -eq $false }).Count)
        Check "SPU 下架后已选条数为 0" ($r.body.data.selectedCount -eq 0) $r.body.data.selectedCount
        Check "SPU 下架后结算金额为 0" ($r.body.data.selectedAmount -eq 0) $r.body.data.selectedAmount

        Sql "UPDATE t_spu SET status = 'ON_SALE' WHERE id = 1001;" | Out-Null
        $spuBack = Sql "SELECT status FROM t_spu WHERE id = 1001;"
        Check "（复原）SPU 1001 恢复上架" ($spuBack -eq 'ON_SALE') $spuBack
        $r = Call 'Get' "$Gateway/api/cart" $sessA $null
        Check "恢复上架后结算金额复原" ($r.body.data.selectedAmount -eq ($P1 * 4 + $P2)) $r.body.data.selectedAmount

        # 自证非破坏性：跑完测试，种子数据必须和跑之前一模一样
        $dbStockAfter = Sql "SELECT stock FROM t_sku WHERE id = $SKU_A;"
        Check "测试结束后库内库存仍为原值 $S1（证明本脚本不污染数据）" ($dbStockAfter -eq "$S1") $dbStockAfter
    }
}

# ---------- 8. 件数角标 ----------
Write-Host ""
Write-Host "> 件数角标"
$r = Call 'Get' "$Gateway/api/cart/count" $sessA $null
Check "角标件数 5" ($r.body.data.count -eq 5) $r.body.data.count

# ---------- 9. 删除 ----------
Write-Host ""
Write-Host "> 删除"
$r = Call 'Delete' "$Gateway/api/cart/items/$SKU_A" $sessA $null
Check "删除 2001 返回 200" ($r.status -eq 200) $r.status
$r = Call 'Get' "$Gateway/api/cart" $sessA $null
Check "删除后条目数 1" ($r.body.data.itemCount -eq 1) $r.body.data.itemCount
$r = Call 'Delete' "$Gateway/api/cart/items/$SKU_A" $sessA $null
Check "重复删除返回 404" ($r.status -eq 404) $r.status

# ---------- 10. 用户隔离 ----------
Write-Host ""
Write-Host "> 用户隔离（拿不到别人的购物车）"
$regB = Register $phoneB '购物车测试乙'
Check "用户 B 注册/登录成功（200）" ($regB.result.status -eq 200) $regB.result.status
$r = Call 'Get' "$Gateway/api/cart" $regB.session $null
Check "用户 B 的购物车为空（看不到 A 的数据）" ($r.body.data.itemCount -eq 0) $r.body.data.itemCount
$r = Call 'Delete' "$Gateway/api/cart/items/$SKU_B" $regB.session $null
Check "用户 B 删除 A 的条目返回 404" ($r.status -eq 404) $r.status
$r = Call 'Get' "$Gateway/api/cart" $sessA $null
Check "用户 A 的购物车未受影响（仍 1 条）" ($r.body.data.itemCount -eq 1) $r.body.data.itemCount

# ---------- 11. 清空已勾选 ----------
Write-Host ""
Write-Host "> 清空已勾选"
$r = Call 'Delete' "$Gateway/api/cart/selected" $sessA $null
Check "清空已勾选返回 200" ($r.status -eq 200) $r.status
Check "清空条数为 1" ($r.body.data.removed -eq 1) $r.body.data.removed
$r = Call 'Get' "$Gateway/api/cart" $sessA $null
Check "清空后购物车为空" ($r.body.data.itemCount -eq 0) $r.body.data.itemCount

# ---------- 汇总 ----------
if ($fxSku) { Remove-SkuFixtures -Ids $fxSku.CreatedIds }
Write-Host ""
Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
if ($fail -gt 0) {
    Write-Host "失败项："
    foreach ($f in $failures) { Write-Host "  - $f" }
    exit 1
}
exit 0
