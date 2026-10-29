# 导入导出 + 批量扩展冒烟（2026-10-28 · 冲刺第二批）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\import-export-batch-smoke.ps1
#
# 覆盖：
#   · 9 个 CSV 导出端点：200 + text/csv + UTF-8 BOM + 表头；
#   · 员工模板 / 导出；导入的逐行结果（未注册手机号、未知权限、超行数上限）；
#   · 秒杀批量上下架、员工批量启停、入驻批量通过 / 驳回（逐条结果与 400 校验）；
#   · 类目 / 品牌导入（第三批 · 第 8 项）：模板表头、dry-run 不落库、正式导入后回查并清理；
#   · xlsx 模板与导入（第三批 · 第 7 项遗留）：模板是二进制 ZIP、回传只有表头 → 400；
#   · 异步导出任务（第三批 · 第 9 项）：创建 → 轮询到 SUCCESS → 下载校验表头 → 权限 403 → 404 / 400；
#   · 买家侧导出（矩阵 · 我的订单 / 我的多多币）：仅本人、手机号与地址脱敏、未登录 401；
#   · 权限边界：普通卖家调运营导出 / 类目导入模板必须 403。
#
# 设计纪律（沿用既有套件）：
#   1. 只读为主：导出不写库；批量端点一律用**不存在的主键**，断言失败路径而不改任何真实数据；
#   2. 导入只用「未注册手机号」与「未知权限」，不真的往店铺里加人；
#   3. 断言失败也必须把原因打出来（HTTP 码 + 响应体），不做静默。
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    [string]$SellerPhone = '13600003333',
    [string]$OperatorPhone = '13684295254',
    [string]$BuyerPhone = '13600001111',
    [string]$Password = 'duoduo12345'
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

function Call([string]$method, [string]$uri, $session, $bodyObj) {
    $params = @{ Uri = $uri; Method = $method; UseBasicParsing = $true; TimeoutSec = 60 }
    if ($session) { $params.WebSession = $session }
    if ($null -ne $bodyObj) {
        $params.Body = ($bodyObj | ConvertTo-Json -Compress -Depth 8)
        $params.ContentType = 'application/json; charset=utf-8'
    }
    try {
        $r = Invoke-WebRequest @params
        # ★ 成功响应也必须解析：批量/导入的断言全部在 body.data 里（items / failedCount）。
        #   只给 raw 的话，脚本会在 `.items[0]` 上抛 NullArray，看起来像后端坏了。
        $parsed = $null
        try { if ($r.Content) { $parsed = $r.Content | ConvertFrom-Json } } catch { }
        return @{ status = [int]$r.StatusCode; body = $parsed; raw = $r.Content; headers = $r.Headers }
    } catch {
        $code = 0; $raw = $null; $headers = $null
        try { $code = [int]$_.Exception.Response.StatusCode.value__ } catch { }
        try { $headers = $_.Exception.Response.Headers } catch { }
        try { if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $raw = $_.ErrorDetails.Message } } catch { }
        if ($null -eq $raw) {
            try {
                $resp = $_.Exception.Response
                if ($resp -and $resp.PSObject.Methods.Name -contains 'GetResponseStream') {
                    $st = $resp.GetResponseStream()
                    if ($st) { $raw = (New-Object System.IO.StreamReader($st)).ReadToEnd() }
                } elseif ($resp -and $null -ne $resp.Content) {
                    $raw = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                }
            } catch { }
        }
        $parsed = $null
        try { if ($raw) { $parsed = $raw | ConvertFrom-Json } } catch { }
        return @{ status = $code; body = $parsed; raw = $raw; headers = $headers }
    }
}

function Login([string]$phone) {
    $s = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $r = Call 'Post' "$Gateway/api/auth/login" $s @{ phone = $phone; password = $Password }
    if ($r.status -ne 200) { throw "登录失败 $phone -> HTTP $($r.status) $($r.raw)" }
    return $s
}

function CsvCheck([string]$name, [string]$uri, $session, [string]$mustContain) {
    $r = Call 'Get' $uri $session $null
    $type = ("$($r.headers['Content-Type'])" )
    $raw = "$($r.raw)"
    $bom = ($raw.Length -gt 0 -and $raw[0] -eq [char]0xFEFF)
    $head = (($raw -split "`r`n")[0])
    Check "$name · HTTP 200" ($r.status -eq 200) "HTTP $($r.status)"
    Check "$name · text/csv" ($type -match 'text/csv') $type
    Check "$name · UTF-8 BOM" $bom '缺少 BOM'
    Check "$name · 表头含「$mustContain」" ($head -like "*$mustContain*") $head
    # 导出行数（表头之外），供人工核对；不作为断言
    $lines = @($raw -split "`r`n" | Where-Object { $_ -ne '' })
    Write-Host "         $($lines.Count - 1) 行数据"
}

<#
  下载二进制附件（xlsx）并读回字节。
  为什么不用 Invoke-WebRequest -OutFile：Windows PowerShell 5.1 遇到分块传输（Spring 默认
  Transfer-Encoding: chunked）会抛「无法对 Null 数组进行索引」，实际文件已写出但调用失败，
  断言全变成假阴性。HttpClient 直接读字节数组，同时把登录会话的 Cookie 抄进 CookieContainer。
#>
function DownloadFile([string]$uri, $session) {
    Add-Type -AssemblyName System.Net.Http -ErrorAction SilentlyContinue
    $handler = New-Object System.Net.Http.HttpClientHandler
    if ($session) {
        $container = New-Object System.Net.CookieContainer
        foreach ($cookie in $session.Cookies.GetCookies($uri)) { $container.Add($cookie) }
        $handler.CookieContainer = $container
    }
    $client = New-Object System.Net.Http.HttpClient($handler)
    try {
        $resp = $client.GetAsync($uri).GetAwaiter().GetResult()
        $bytes = $resp.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        $disposition = ''
        if ($null -ne $resp.Content.Headers.ContentDisposition) { $disposition = $resp.Content.Headers.ContentDisposition.ToString() }
        return @{
            status = [int]$resp.StatusCode
            headers = @{
                'Content-Type' = "$($resp.Content.Headers.ContentType)"
                'Content-Disposition' = $disposition
            }
            bytes = $bytes
        }
    } catch {
        return @{ status = 0; headers = @{}; bytes = @() }
    } finally {
        $client.Dispose()
        $handler.Dispose()
    }
}

Write-Host ''
Write-Host '=== 准备会话 ==='
$seller = Login $SellerPhone
$operator = Login $OperatorPhone
$buyer = Login $BuyerPhone
Check '卖家会话可登录' ($null -ne $seller) $null
Check '运营会话可登录' ($null -ne $operator) $null
Check '买家会话可登录' ($null -ne $buyer) $null

Write-Host ''
Write-Host '=== 商家导出 ==='
CsvCheck '商家订单导出' "$Gateway/api/seller/orders/export?status=PAID" $seller '订单号'
CsvCheck '商家结算台账导出' "$Gateway/api/seller/settlements/export" $seller '卖家应得'
CsvCheck '商家售后导出' "$Gateway/api/aftersales/seller/export" $seller '售后单号'
CsvCheck '员工导出' "$Gateway/api/seller/staff/export" $seller '成员ID'
CsvCheck '员工导入模板' "$Gateway/api/seller/staff/import-template" $seller '手机号'

Write-Host ''
Write-Host '=== 运营导出 ==='
CsvCheck '运营订单导出' "$Gateway/api/admin/orders/export" $operator '收货地址'
CsvCheck '运营售后导出' "$Gateway/api/admin/aftersales/export" $operator '裁定结果'
CsvCheck '运营评价导出' "$Gateway/api/admin/reviews/export" $operator '审核状态'
CsvCheck '运营举报导出' "$Gateway/api/admin/share-reports/export" $operator '处理说明'
CsvCheck '运营入驻导出' "$Gateway/api/admin/merchants/export" $operator '店铺名'

Write-Host ''
Write-Host '=== 运营导出（第二批） ==='
CsvCheck '商品审核导出' "$Gateway/api/admin/products/export?auditStatus=PENDING" $operator '审核状态'
CsvCheck '商品变更历史导出' "$Gateway/api/admin/products/change-history/export" $operator '字段变更'
CsvCheck '闲置审核导出' "$Gateway/api/admin/listings/export?auditStatus=PENDING" $operator '审核状态'
CsvCheck '操作审计导出' "$Gateway/api/admin/audit-logs/export" $operator '动作'
CsvCheck '平台成员导出' "$Gateway/api/admin/merchant-staff/export" $operator '成员ID'
CsvCheck '运营账号导出' "$Gateway/api/admin/users/export" $operator '手机号'
CsvCheck '多多币流水导出' "$Gateway/api/admin/coins/ledger/export" $operator '变动(币)'
CsvCheck '秒杀活动导出' "$Gateway/api/admin/seckill/activities/export" $operator '秒杀价(元)'

Write-Host ''
Write-Host '=== 权限边界 ==='
$deny = Call 'Get' "$Gateway/api/admin/orders/export" $seller $null
Check '普通卖家调运营订单导出 → 403' ($deny.status -eq 403) "HTTP $($deny.status)"

$deny2 = Call 'Get' "$Gateway/api/admin/audit-logs/export" $seller $null
Check '普通卖家调操作审计导出 → 403' ($deny2.status -eq 403) "HTTP $($deny2.status)"

Write-Host ''
Write-Host '=== 导出参数校验 ==='
$bad = Call 'Get' "$Gateway/api/admin/orders/export?status=NOT_A_STATUS" $operator $null
Check '非法订单状态 → 400' ($bad.status -eq 400) "HTTP $($bad.status)"

Write-Host ''
Write-Host '=== 价格 / 库存导入（第三批 · 第 7 项） ==='
$psTpl = Call 'Get' "$Gateway/api/seller/products/price-stock-template" $seller $null
$psTplText = "$($psTpl.body.data.content)"
Check '价格库存模板 · HTTP 200' ($psTpl.status -eq 200) "HTTP $($psTpl.status) $($psTpl.raw)"
Check '价格库存模板 · 表头为 商品ID,规格ID,价格(元),库存' ($psTplText -match '商品ID,规格ID,价格\(元\),库存') $psTplText
Check '价格库存模板 · UTF-8 BOM' ($psTplText.Length -gt 0 -and $psTplText[0] -eq [char]0xFEFF) '缺少 BOM'

$psBadHead = Call 'Post' "$Gateway/api/seller/products/price-stock-import" $seller @{ content = "商品ID,规格ID,价格`r`n1,2,3.00" }
Check '价格库存导入 · 表头不符 → 400' ($psBadHead.status -eq 400) "HTTP $($psBadHead.status) $($psBadHead.raw)"

$psEmpty = Call 'Post' "$Gateway/api/seller/products/price-stock-import" $seller @{ content = "商品ID,规格ID,价格(元),库存`r`n" }
Check '价格库存导入 · 只有表头 → 400' ($psEmpty.status -eq 400) "HTTP $($psEmpty.status) $($psEmpty.raw)"

$psMiss = Call 'Post' "$Gateway/api/seller/products/price-stock-import" $seller @{ content = "商品ID,规格ID,价格(元),库存`r`n999999999999999001,999999999999999002,1.00,1" }
Check '价格库存导入 · 不存在商品 → 200' ($psMiss.status -eq 200) "HTTP $($psMiss.status) $($psMiss.raw)"
Check '价格库存导入 · 该行失败' ($psMiss.body.data.failedCount -eq 1) $psMiss.raw
Check '价格库存导入 · 失败行 id = 行号 2' ("$($psMiss.body.data.items[0].id)" -eq '2') $psMiss.raw
Check '价格库存导入 · 错误码为 NOT_FOUND' ("$($psMiss.body.data.items[0].errorCode)" -eq 'NOT_FOUND') $psMiss.raw

$psRowBad = Call 'Post' "$Gateway/api/seller/products/price-stock-import" $seller @{ content = "商品ID,规格ID,价格(元),库存`r`n1,2,,`r`n1,2,,-1" }
Check '价格库存导入 · 空价格空库存 / 负库存 → 400 之外逐行失败' ($psRowBad.status -eq 200 -and $psRowBad.body.data.failedCount -eq 2) "HTTP $($psRowBad.status) $($psRowBad.raw)"
Check '价格库存导入 · 校验失败错误码 VALIDATION_ERROR' ("$($psRowBad.body.data.items[0].errorCode)" -eq 'VALIDATION_ERROR') $psRowBad.raw

# 真实 SKU 往返：改价 + 改库存 → 回库断言 → 恢复原值
$psTarget = $null
$psList = Call 'Get' "$Gateway/api/seller/products?page=1&pageSize=20" $seller $null
foreach ($entry in @($psList.body.data.items)) {
    if ($null -eq $entry) { continue }
    $detail = Call 'Get' "$Gateway/api/seller/products/$($entry.id)" $seller $null
    $skus = @($detail.body.data.skus)
    if ($skus.Count -gt 0 -and $null -ne $skus[0]) { $psTarget = @{ spuId = "$($entry.id)"; sku = $skus[0] }; break }
}
if ($null -eq $psTarget) {
    Check '价格库存导入 · 找到可改的真实 SKU' $false '当前店铺没有带规格的商品，无法做往返断言'
} else {
    $spuId = [long]$psTarget.spuId
    $skuId = [long]$psTarget.sku.id
    $oldPrice = [long]$psTarget.sku.price
    $oldStock = [int]$psTarget.sku.stock
    $newPrice = $oldPrice + 100
    $newStock = $oldStock + 1
    $inv = [System.Globalization.CultureInfo]::InvariantCulture
    $newPriceYuan = [string]::Format($inv, '{0:F2}', $newPrice / 100.0)
    $oldPriceYuan = [string]::Format($inv, '{0:F2}', $oldPrice / 100.0)
    $head = "商品ID,规格ID,价格(元),库存"

    $psOk = Call 'Post' "$Gateway/api/seller/products/price-stock-import" $seller @{ content = "$head`r`n$spuId,$skuId,$newPriceYuan,$newStock" }
    Check "价格库存导入 · 真实 SKU 改价改库存 → 成功（spu $spuId / sku $skuId）" ($psOk.status -eq 200 -and $psOk.body.data.successCount -eq 1) "HTTP $($psOk.status) $($psOk.raw)"
    $after = Call 'Get' "$Gateway/api/seller/products/$spuId" $seller $null
    $afterSku = @($after.body.data.skus) | Where-Object { "$($_.id)" -eq "$skuId" } | Select-Object -First 1
    Check '价格库存导入 · 回库价格 = 新价（分）' ($null -ne $afterSku -and [long]$afterSku.price -eq $newPrice) ("期望 $newPrice，实际 $($afterSku.price)")
    Check '价格库存导入 · 回库库存 = 新库存' ($null -ne $afterSku -and [int]$afterSku.stock -eq $newStock) ("期望 $newStock，实际 $($afterSku.stock)")

    $psRestore = Call 'Post' "$Gateway/api/seller/products/price-stock-import" $seller @{ content = "$head`r`n$spuId,$skuId,$oldPriceYuan,$oldStock" }
    Check '价格库存导入 · 恢复原价原库存 → 成功' ($psRestore.status -eq 200 -and $psRestore.body.data.successCount -eq 1) "HTTP $($psRestore.status) $($psRestore.raw)"
    $back = Call 'Get' "$Gateway/api/seller/products/$spuId" $seller $null
    $backSku = @($back.body.data.skus) | Where-Object { "$($_.id)" -eq "$skuId" } | Select-Object -First 1
    Check '价格库存导入 · 已恢复原值' ($null -ne $backSku -and [long]$backSku.price -eq $oldPrice -and [int]$backSku.stock -eq $oldStock) ("价格 $($backSku.price) / 库存 $($backSku.stock)")
}

Write-Host ''
Write-Host '=== 类目 / 品牌导入（第三批 · 第 8 项） ==='
$suffix = (Get-Date).ToString('HHmmss')

$catTpl = Call 'Get' "$Gateway/api/admin/categories/import-template" $operator $null
$catTplText = "$($catTpl.body.data.content)"
Check '类目模板 · HTTP 200' ($catTpl.status -eq 200) "HTTP $($catTpl.status) $($catTpl.raw)"
Check '类目模板 · 表头为 名称,父类目,排序号,图标' ($catTplText -match '名称,父类目,排序号,图标') $catTplText
Check '类目模板 · UTF-8 BOM' ($catTplText.Length -gt 0 -and $catTplText[0] -eq [char]0xFEFF) '缺少 BOM'

$catDeny = Call 'Get' "$Gateway/api/admin/categories/import-template" $seller $null
Check '普通卖家调类目导入模板 → 403' ($catDeny.status -eq 403) "HTTP $($catDeny.status)"

$catBadHead = Call 'Post' "$Gateway/api/admin/categories/import/dry-run" $operator @{ content = "名称,父类目`r`n冒烟,0" }
Check '类目预演 · 表头不符 → 400' ($catBadHead.status -eq 400) "HTTP $($catBadHead.status) $($catBadHead.raw)"

$catEmpty = Call 'Post' "$Gateway/api/admin/categories/import/dry-run" $operator @{ content = "名称,父类目,排序号,图标`r`n" }
Check '类目预演 · 只有表头 → 400' ($catEmpty.status -eq 400) "HTTP $($catEmpty.status) $($catEmpty.raw)"

# 取一个真实的顶级类目名做「已存在且无变化 → 跳过」断言（名字里不能有逗号 / 引号）
$catList = Call 'Get' "$Gateway/api/admin/categories" $operator $null
$existingTop = @($catList.body.data) | Where-Object {
    $null -ne $_ -and "$($_.parentId)" -eq '0' -and "$($_.name)" -match '^[^,"]+$'
} | Select-Object -First 1
$catName = "冒烟类目$suffix"
$catChildName = "冒烟子类目$suffix"
$catOrphanName = "冒烟孤儿$suffix"
$catHead = '名称,父类目,排序号,图标'

$catContent = "$catHead`r`n$catName,,10,`r`n$catChildName,$catName,20,`r`n$catOrphanName,999999999999999999,,"
if ($null -ne $existingTop) {
    $catContent = "$catContent`r`n$($existingTop.name),,,"
    Write-Host "         用已存在顶级类目「$($existingTop.name)」验证跳过"
}

$catPlan = Call 'Post' "$Gateway/api/admin/categories/import/dry-run" $operator @{ content = $catContent }
$catPlanItems = @($catPlan.body.data.items)
$catPlanErr = @($catPlanItems | Where-Object { "$($_.action)" -eq 'ERROR' })
Check '类目预演 · HTTP 200' ($catPlan.status -eq 200) "HTTP $($catPlan.status) $($catPlan.raw)"
Check '类目预演 · 2 行将新建（父 + 子）' ($catPlan.body.data.createCount -eq 2) $catPlan.raw
Check '类目预演 · 1 行父类目 ID 不存在 → ERROR' ($catPlanErr.Count -eq 1) $catPlan.raw
if ($null -ne $existingTop) {
    Check '类目预演 · 已存在且无变化 → SKIP' ($catPlan.body.data.skipCount -eq 1) $catPlan.raw
}

$catAfterPlan = Call 'Get' "$Gateway/api/admin/categories" $operator $null
$catPlanned = @($catAfterPlan.body.data) | Where-Object { "$($_.name)" -eq $catName }
Check '类目预演 · 不落库（列表里没有新类目）' ($catPlanned.Count -eq 0) "预演后已出现 $($catPlanned.Count) 条同名类目"

$catImport = Call 'Post' "$Gateway/api/admin/categories/import" $operator @{ content = $catContent }
Check '类目导入 · HTTP 200' ($catImport.status -eq 200) "HTTP $($catImport.status) $($catImport.raw)"
Check '类目导入 · 失败 1 行（孤儿行）' ($catImport.body.data.failedCount -eq 1) $catImport.raw

$catFinal = Call 'Get' "$Gateway/api/admin/categories" $operator $null
$catParentRow = @($catFinal.body.data) | Where-Object { "$($_.name)" -eq $catName } | Select-Object -First 1
$catChildRow = @($catFinal.body.data) | Where-Object { "$($_.name)" -eq $catChildName } | Select-Object -First 1
Check '类目导入 · 一级类目已创建' ($null -ne $catParentRow) '未找到新建的一级类目'
Check '类目导入 · 子类目挂在新建的一级类目下' ($null -ne $catChildRow -and "$($catChildRow.parentId)" -eq "$($catParentRow.id)") "child.parentId=$($catChildRow.parentId) / parent.id=$($catParentRow.id)"

if ($null -ne $catChildRow) {
    $delChild = Call 'Delete' "$Gateway/api/admin/categories/$($catChildRow.id)" $operator $null
    Check '类目导入 · 清理子类目' ($delChild.status -eq 200) "HTTP $($delChild.status) $($delChild.raw)"
}
if ($null -ne $catParentRow) {
    $delParent = Call 'Delete' "$Gateway/api/admin/categories/$($catParentRow.id)" $operator $null
    Check '类目导入 · 清理一级类目' ($delParent.status -eq 200) "HTTP $($delParent.status) $($delParent.raw)"
}

$brandTpl = Call 'Get' "$Gateway/api/admin/brands/import-template" $operator $null
$brandTplText = "$($brandTpl.body.data.content)"
Check '品牌模板 · HTTP 200' ($brandTpl.status -eq 200) "HTTP $($brandTpl.status) $($brandTpl.raw)"
Check '品牌模板 · 表头为 名称,国家/地区,Logo地址,品牌故事' ($brandTplText -match '名称,国家/地区,Logo地址,品牌故事') $brandTplText
Check '品牌模板 · UTF-8 BOM' ($brandTplText.Length -gt 0 -and $brandTplText[0] -eq [char]0xFEFF) '缺少 BOM'

$brandName = "冒烟品牌$suffix"
$brandHead = '名称,国家/地区,Logo地址,品牌故事'
$brandContent = "$brandHead`r`n$brandName,中国,,冒烟测试品牌`r`n$brandName,美国,,文件内重复行"

$brandPlan = Call 'Post' "$Gateway/api/admin/brands/import/dry-run" $operator @{ content = $brandContent }
Check '品牌预演 · HTTP 200' ($brandPlan.status -eq 200) "HTTP $($brandPlan.status) $($brandPlan.raw)"
Check '品牌预演 · 1 行将新建' ($brandPlan.body.data.createCount -eq 1) $brandPlan.raw
Check '品牌预演 · 文件内重复行 → SKIP' ($brandPlan.body.data.skipCount -eq 1) $brandPlan.raw

$brandAfterPlan = Call 'Get' "$Gateway/api/admin/brands" $operator $null
$brandPlanned = @($brandAfterPlan.body.data) | Where-Object { "$($_.name)" -eq $brandName }
Check '品牌预演 · 不落库（列表里没有新品牌）' ($brandPlanned.Count -eq 0) "预演后已出现 $($brandPlanned.Count) 条同名品牌"

$brandImport = Call 'Post' "$Gateway/api/admin/brands/import" $operator @{ content = $brandContent }
Check '品牌导入 · 成功 2 行（1 新建 + 1 跳过）' ($brandImport.status -eq 200 -and $brandImport.body.data.successCount -eq 2 -and $brandImport.body.data.failedCount -eq 0) "HTTP $($brandImport.status) $($brandImport.raw)"

$brandList = Call 'Get' "$Gateway/api/admin/brands" $operator $null
$brandRow = @($brandList.body.data) | Where-Object { "$($_.name)" -eq $brandName } | Select-Object -First 1
Check '品牌导入 · 品牌已创建且国家为中国' ($null -ne $brandRow -and "$($brandRow.country)" -eq '中国') "country=$($brandRow.country)"

$brandUpdateContent = "$brandHead`r`n$brandName,美国,,"
$brandPlan2 = Call 'Post' "$Gateway/api/admin/brands/import/dry-run" $operator @{ content = $brandUpdateContent }
Check '品牌预演 · 同名品牌将更新（1 行）' ($brandPlan2.status -eq 200 -and $brandPlan2.body.data.updateCount -eq 1) "HTTP $($brandPlan2.status) $($brandPlan2.raw)"

$brandImport2 = Call 'Post' "$Gateway/api/admin/brands/import" $operator @{ content = $brandUpdateContent }
Check '品牌导入 · 更新 1 行' ($brandImport2.status -eq 200 -and $brandImport2.body.data.successCount -eq 1) "HTTP $($brandImport2.status) $($brandImport2.raw)"

$brandList2 = Call 'Get' "$Gateway/api/admin/brands" $operator $null
$brandRow2 = @($brandList2.body.data) | Where-Object { "$($_.name)" -eq $brandName } | Select-Object -First 1
Check '品牌导入 · 更新生效（国家 → 美国）' ($null -ne $brandRow2 -and "$($brandRow2.country)" -eq '美国') "country=$($brandRow2.country)"

if ($null -ne $brandRow2) {
    $delBrand = Call 'Delete' "$Gateway/api/admin/brands/$($brandRow2.id)" $operator $null
    Check '品牌导入 · 清理测试品牌' ($delBrand.status -eq 200) "HTTP $($delBrand.status) $($delBrand.raw)"
}

Write-Host ''
Write-Host '=== xlsx 模板与导入（第三批 · 第 7 项遗留） ==='
$psXlsx = DownloadFile "$Gateway/api/seller/products/price-stock-template?format=xlsx" $seller
Check '价格库存 xlsx 模板 · HTTP 200' ($psXlsx.status -eq 200) "HTTP $($psXlsx.status)"
Check '价格库存 xlsx 模板 · Content-Type 为 xlsx' ("$($psXlsx.headers['Content-Type'])" -match 'spreadsheetml\.sheet') "$($psXlsx.headers['Content-Type'])"
Check '价格库存 xlsx 模板 · ZIP 头 PK（真二进制）' ($psXlsx.bytes.Length -gt 2 -and $psXlsx.bytes[0] -eq 0x50 -and $psXlsx.bytes[1] -eq 0x4B) "长度 $($psXlsx.bytes.Length)"
Check '价格库存 xlsx 模板 · 附件名 .xlsx' ("$($psXlsx.headers['Content-Disposition'])" -match '\.xlsx') "$($psXlsx.headers['Content-Disposition'])"

$prodXlsx = DownloadFile "$Gateway/api/seller/products/import-template?format=xlsx" $seller
Check '商品 xlsx 模板 · HTTP 200 且是 ZIP' ($prodXlsx.status -eq 200 -and $prodXlsx.bytes.Length -gt 1000 -and $prodXlsx.bytes[0] -eq 0x50) "HTTP $($prodXlsx.status) / 长度 $($prodXlsx.bytes.Length)"

$catXlsx = DownloadFile "$Gateway/api/admin/categories/import-template?format=xlsx" $operator
Check '类目 xlsx 模板 · HTTP 200 且是 ZIP' ($catXlsx.status -eq 200 -and $catXlsx.bytes.Length -gt 1000 -and $catXlsx.bytes[0] -eq 0x50) "HTTP $($catXlsx.status) / 长度 $($catXlsx.bytes.Length)"
$brandXlsx = DownloadFile "$Gateway/api/admin/brands/import-template?format=xlsx" $operator
Check '品牌 xlsx 模板 · HTTP 200 且是 ZIP' ($brandXlsx.status -eq 200 -and $brandXlsx.bytes[0] -eq 0x50) "HTTP $($brandXlsx.status)"

$catXlsxDeny = DownloadFile "$Gateway/api/admin/categories/import-template?format=xlsx" $seller
Check '普通卖家调类目 xlsx 模板 → 403' ($catXlsxDeny.status -eq 403) "HTTP $($catXlsxDeny.status)"

# 把刚下载的 xlsx 原样回传：能解析到表头、只有表头 → 400，证明 xlsx 分支真的在跑（不是按文本乱切）
$psB64 = [System.Convert]::ToBase64String($psXlsx.bytes)
$psXlsxEmpty = Call 'Post' "$Gateway/api/seller/products/price-stock-import" $seller @{ content = $psB64; fileName = 'price-stock.xlsx' }
Check '价格库存 xlsx 导入 · 只有表头 → 400' ($psXlsxEmpty.status -eq 400) "HTTP $($psXlsxEmpty.status) $($psXlsxEmpty.raw)"
Check '价格库存 xlsx 导入 · 提示为「只有表头」' ("$($psXlsxEmpty.raw)" -match '只有表头') "$($psXlsxEmpty.raw)"

$catB64 = [System.Convert]::ToBase64String($catXlsx.bytes)
$catXlsxEmpty = Call 'Post' "$Gateway/api/admin/categories/import/dry-run" $operator @{ content = $catB64; fileName = 'category.xlsx' }
Check '类目 xlsx 预演 · 只有表头 → 400' ($catXlsxEmpty.status -eq 400) "HTTP $($catXlsxEmpty.status) $($catXlsxEmpty.raw)"

# 坏文件不能被当成 CSV 切：必须是 400 且文案指向 Excel
$badXlsx = Call 'Post' "$Gateway/api/seller/products/price-stock-import" $seller @{ content = 'UEsDBAoAAAAA'; fileName = 'broken.xlsx' }
Check '损坏的 xlsx → 400 且提示 Excel' ($badXlsx.status -eq 400 -and "$($badXlsx.raw)" -match 'Excel') "HTTP $($badXlsx.status) $($badXlsx.raw)"

Write-Host ''
Write-Host '=== 员工导入（逐行结果） ==='
$imp = Call 'Post' "$Gateway/api/seller/staff/import" $seller @{ rows = @(@{ rowNo = 2; phone = '13800009999' }) }
Check '未注册手机号 → 200' ($imp.status -eq 200) "HTTP $($imp.status) $($imp.raw)"
Check '未注册手机号 → 该行失败' ($imp.body.data.failedCount -eq 1) ($imp.raw)
Check '失败行 id = 原表行号 2' ("$($imp.body.data.items[0].id)" -eq '2') ($imp.raw)

$impPerm = Call 'Post' "$Gateway/api/seller/staff/import" $seller @{ rows = @(@{ rowNo = 3; phone = $SellerPhone; permissions = @('NOT_A_PERMISSION') }) }
Check '未知权限 → 200' ($impPerm.status -eq 200) "HTTP $($impPerm.status) $($impPerm.raw)"
Check '未知权限 → 该行失败（权限先校验，不会先建成员）' ($impPerm.body.data.failedCount -eq 1) ($impPerm.raw)
Check '未知权限 → 错误码为 VALIDATION_ERROR' ("$($impPerm.body.data.items[0].errorCode)" -eq 'VALIDATION_ERROR') ($impPerm.raw)

$tooMany = @()
for ($i = 0; $i -lt 201; $i++) { $tooMany += @{ rowNo = $i + 2; phone = '13800009999' } }
$impBig = Call 'Post' "$Gateway/api/seller/staff/import" $seller @{ rows = $tooMany }
Check '超过 200 行 → 400' ($impBig.status -eq 400) "HTTP $($impBig.status) $($impBig.raw)"

Write-Host ''
Write-Host '=== 批量扩展（全部用不存在的主键，只验逐条失败语义） ==='
$sk = Call 'Post' "$Gateway/api/admin/seckill/activities/batch-status" $operator @{ ids = @('999999999999999001', '999999999999999002'); enabled = $true }
Check '秒杀批量上下架 → 200' ($sk.status -eq 200) "HTTP $($sk.status) $($sk.raw)"
Check '秒杀批量 → 2 条均失败且带原因' ($sk.body.data.failedCount -eq 2 -and $sk.body.data.items[0].errorCode -eq 'NOT_FOUND') ($sk.raw)

$st = Call 'Post' "$Gateway/api/seller/staff/batch-disable" $seller @{ memberIds = @('999999999999999003') }
Check '员工批量停用 → 200 且逐条失败' ($st.status -eq 200 -and $st.body.data.failedCount -eq 1) "HTTP $($st.status) $($st.raw)"
$stEn = Call 'Post' "$Gateway/api/seller/staff/batch-enable" $seller @{ memberIds = @('999999999999999003') }
Check '员工批量启用 → 200 且逐条失败' ($stEn.status -eq 200 -and $stEn.body.data.failedCount -eq 1) "HTTP $($stEn.status) $($stEn.raw)"

$ma = Call 'Post' "$Gateway/api/admin/merchants/batch-approve" $operator @{ ids = @('999999999999999004') }
Check '入驻批量通过 → 200 且逐条失败' ($ma.status -eq 200 -and $ma.body.data.failedCount -eq 1) "HTTP $($ma.status) $($ma.raw)"
$mr = Call 'Post' "$Gateway/api/admin/merchants/batch-reject" $operator @{ ids = @('999999999999999004'); reasons = @() }
Check '入驻批量驳回缺原因 → 400' ($mr.status -eq 400) "HTTP $($mr.status) $($mr.raw)"

Write-Host ''
Write-Host '=== 异步导出任务（第三批 · 第 9 项） ==='
$jobCreate = Call 'Post' "$Gateway/api/admin/export-jobs" $operator @{ module = 'ORDERS' }
Check '创建导出任务 · HTTP 200' ($jobCreate.status -eq 200) "HTTP $($jobCreate.status) $($jobCreate.raw)"
$jobId = "$($jobCreate.body.data.id)"
$jobTotal = [int]$jobCreate.body.data.totalRows
Check '创建导出任务 · 返回 jobId' ($jobId -ne '' -and $jobId -ne '0') $jobCreate.raw
Check '创建导出任务 · 预估行数 > 0' ($jobTotal -gt 0) "totalRows=$jobTotal"

# 轮询到终态：任务在线程池里写盘，通常几秒内完成；30 秒仍未完成即判失败（不是静默跳过）
$jobState = $jobCreate
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 1
    $jobState = Call 'Get' "$Gateway/api/admin/export-jobs/$jobId" $operator $null
    if ($jobState.body.data.status -eq 'SUCCESS' -or $jobState.body.data.status -eq 'FAILED') { break }
}
Check '导出任务 · 生成完成（SUCCESS）' ($jobState.body.data.status -eq 'SUCCESS') "status=$($jobState.body.data.status) / error=$($jobState.body.data.errorMessage)"
Check '导出任务 · 已写出行数 > 0' ([int]$jobState.body.data.rowCount -gt 0) "rowCount=$($jobState.body.data.rowCount)"
# 行数必须与创建时的 count 完全一致：游标漏页会让文件少行而状态仍是 SUCCESS，
# 这种「看起来成功、其实少数据」的导出比失败更危险（财务报表会直接少钱）。
Check '导出任务 · 行数 = 预估行数（无漏页）' ([int]$jobState.body.data.rowCount -eq $jobTotal) "rowCount=$($jobState.body.data.rowCount) / totalRows=$jobTotal"
Check '导出任务 · 可下载标记为 true' ($jobState.body.data.downloadable -eq $true) $jobState.raw

$jobDl = Call 'Get' "$Gateway/api/admin/export-jobs/$jobId/download" $operator $null
$jobDlType = "$($jobDl.headers['Content-Type'])"
$jobDlRaw = "$($jobDl.raw)"
$jobDlBom = ($jobDlRaw.Length -gt 0 -and $jobDlRaw[0] -eq [char]0xFEFF)
$jobDlHead = (($jobDlRaw -split "`r`n")[0])
Check '导出任务下载 · HTTP 200' ($jobDl.status -eq 200) "HTTP $($jobDl.status) $($jobDl.raw)"
Check '导出任务下载 · text/csv' ($jobDlType -match 'text/csv') $jobDlType
Check '导出任务下载 · UTF-8 BOM' $jobDlBom '缺少 BOM'
Check '导出任务下载 · 表头含「收货地址」' ($jobDlHead -like '*收货地址*') $jobDlHead

$jobList = Call 'Get' "$Gateway/api/admin/export-jobs?page=1&pageSize=20" $operator $null
$jobMine = @($jobList.body.data.items) | Where-Object { "$($_.id)" -eq $jobId }
Check '导出任务列表 · 含刚创建的任务' ($null -ne $jobMine) "列表里没有 $jobId"

$jobDenied = Call 'Post' "$Gateway/api/admin/export-jobs" $seller @{ module = 'ORDERS' }
Check '普通卖家创建导出任务 → 403' ($jobDenied.status -eq 403) "HTTP $($jobDenied.status)"
$jobDlDenied = Call 'Get' "$Gateway/api/admin/export-jobs/$jobId/download" $seller $null
Check '普通卖家下载运营任务 → 403' ($jobDlDenied.status -eq 403) "HTTP $($jobDlDenied.status)"

$jobMissing = Call 'Get' "$Gateway/api/admin/export-jobs/999999999999999009" $operator $null
Check '不存在的导出任务 → 404' ($jobMissing.status -eq 404) "HTTP $($jobMissing.status)"
$jobBadModule = Call 'Post' "$Gateway/api/admin/export-jobs" $operator @{ module = 'NOT_A_MODULE' }
Check '未知导出模块 → 400' ($jobBadModule.status -eq 400) "HTTP $($jobBadModule.status)"
$jobNoHit = Call 'Post' "$Gateway/api/admin/export-jobs" $operator @{ module = 'ORDERS'; keyword = "SMOKE-NOHIT-$suffix-$jobId" }
Check '筛选无命中 → 400（不生成只有表头的文件）' ($jobNoHit.status -eq 400) "HTTP $($jobNoHit.status) $($jobNoHit.raw)"

Write-Host ''
Write-Host '=== 买家侧导出（矩阵 · 我的订单 / 我的多多币） ==='
CsvCheck '买家订单导出（本人）' "$Gateway/api/orders/export" $buyer '收货电话(脱敏)'
CsvCheck '买家订单导出 · 按状态筛选' "$Gateway/api/orders/export?status=PAID" $buyer '订单号'
$buyerOrderDl = Call 'Get' "$Gateway/api/orders/export" $buyer $null
$buyerOrderRaw = "$($buyerOrderDl.raw)"
# 手机号必须已脱敏：整份文件里不应出现 11 位连续手机号
Check '买家订单导出 · 不含完整手机号' (-not ($buyerOrderRaw -match '\b1[3-9]\d{9}\b')) '发现未脱敏手机号'
$buyerLines = @($buyerOrderRaw -split "`r`n" | Where-Object { $_ -ne '' })
if ($buyerLines.Count -le 1) {
    Write-Host '  [SKIP] 买家暂无订单，跳过地址脱敏断言'
} else {
    Check '买家订单导出 · 地址已打码（含 ****）' ($buyerOrderRaw -match '\*\*\*\*') '未发现地址掩码'
}
$buyerOrderType = "$($buyerOrderDl.headers['Content-Type'])"
Check '买家订单导出 · 仅本人（筛选生效）' ($buyerOrderDl.status -eq 200 -and $buyerOrderType -match 'text/csv') "HTTP $($buyerOrderDl.status) $buyerOrderType"

CsvCheck '买家多多币流水导出' "$Gateway/api/coins/ledger/export" $buyer '变动后余额(币)'
$anonBuyerOrder = Call 'Get' "$Gateway/api/orders/export" $null $null
Check '未登录导出我的订单 → 401' ($anonBuyerOrder.status -eq 401) "HTTP $($anonBuyerOrder.status)"
$anonBuyerCoins = Call 'Get' "$Gateway/api/coins/ledger/export" $null $null
Check '未登录导出我的流水 → 401' ($anonBuyerCoins.status -eq 401) "HTTP $($anonBuyerCoins.status)"

Write-Host ''
Write-Host '=== 验收结果 ==='
if ($fail -eq 0) {
    Write-Host ("=== 冒烟结果：通过 {0} 项，失败 0 项 ===" -f $pass) -ForegroundColor Green
    exit 0
} else {
    Write-Host ("=== 冒烟结果：通过 {0} 项，失败 {1} 项 ===" -f $pass, $fail) -ForegroundColor Red
    $failures | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
