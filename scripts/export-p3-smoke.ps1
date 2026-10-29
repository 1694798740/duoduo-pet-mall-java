# 矩阵 P3 · 剩余导出冒烟（2026-10-28）
#
# 覆盖 7 个只读导出端点（不写库）：
#   商家：价格库存 / 评价 / 商品分析 / 客服会话
#   运营：类目 / 品牌 / 租户健康
#
# 断言每个端点：200、text/csv、UTF-8 BOM、首行表头完全匹配；未登录一律 401。
# 用法（默认本机容器站点）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\export-p3-smoke.ps1
# 线上：
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\export-p3-smoke.ps1 `
#     -SellerGateway http://8.163.71.44:8091 -AdminGateway http://8.163.71.44:8090
param(
    [string]$SellerGateway = 'http://127.0.0.1:8091',
    [string]$AdminGateway = 'http://127.0.0.1:8090',
    [string]$SellerPhone = '13600003333',
    [string]$OperatorPhone = '13684295254',
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

function Login([string]$base, [string]$phone) {
    $s = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $r = Invoke-WebRequest -Uri "$base/api/auth/login" -Method Post -WebSession $s -UseBasicParsing `
        -ContentType 'application/json; charset=utf-8' `
        -Body (@{ phone = $phone; password = $Password } | ConvertTo-Json -Compress)
    if ([int]$r.StatusCode -ne 200) { throw "登录失败 $phone -> HTTP $($r.StatusCode)" }
    return $s
}

function Export([string]$name, [string]$url, $session, [string]$header) {
    try {
        $r = Invoke-WebRequest -Uri $url -WebSession $session -UseBasicParsing -TimeoutSec 60
        $type = [string]$r.Headers['Content-Type']
        $text = [string]$r.Content
        $firstLine = ($text -split "`r?`n")[0]
        $bom = $text.Length -gt 0 -and [int][char]$text[0] -eq 0xFEFF
        $ok = ([int]$r.StatusCode -eq 200) -and $type.StartsWith('text/csv') -and $bom -and ($firstLine -eq ([string][char]0xFEFF + $header))
        Check $name $ok "HTTP=$($r.StatusCode) TYPE=$type BOM=$bom HEAD=$firstLine"
    } catch {
        $code = 0
        try { $code = [int]$_.Exception.Response.StatusCode.value__ } catch { }
        Check $name $false "HTTP=$code $($_.Exception.Message)"
    }
}

Write-Host "登录商家 $SellerPhone / 运营 $OperatorPhone"
$seller = Login $SellerGateway $SellerPhone
$admin = Login $AdminGateway $OperatorPhone

Write-Host "商家导出："
Export '价格库存' "$SellerGateway/api/seller/products/price-stock/export" $seller `
    '商品ID,商品名称,规格ID,规格名称,价格(元),库存,商品状态,审核状态'
Export '店铺评价' "$SellerGateway/api/reviews/seller/export" $seller `
    '评价ID,订单号,商品,评分,评价内容,追评,买家昵称,匿名,回复内容,回复时间,提交时间'
Export '商品分析' "$SellerGateway/api/seller/product-stats/export" $seller `
    '商品ID,商品名称,成交件数,成交金额(元),成交订单数,退款件数,退款金额(元)'
Export '客服会话' "$SellerGateway/api/seller/support/sessions/export" $seller `
    '会话ID,买家,未读消息数,会话状态,处理人,最后消息时间'

Write-Host "运营导出："
Export '类目' "$AdminGateway/api/admin/categories/export" $admin `
    '类目ID,类目名称,父类目ID,父类目名称,层级,状态,图标'
Export '品牌' "$AdminGateway/api/admin/brands/export" $admin `
    '品牌ID,品牌名称,国家/地区,Logo地址,品牌故事,状态'
Export '租户健康' "$AdminGateway/api/admin/tenant-health/export" $admin `
    '租户ID,店铺,档位,下单数,成交单数,成交额(元),售后立案数,已退款,未结案,卖家败诉,售后率,未收回欠款笔数,未收回金额(元),超期托管笔数,超期托管金额(元),缺托管单数,结算异常合计,接口调用数,5xx,4xx,接口错误率'

Write-Host "权限边界："
try {
    Invoke-WebRequest -Uri "$SellerGateway/api/seller/products/price-stock/export" -UseBasicParsing -TimeoutSec 30 | Out-Null
    Check '未登录导出 401' $false '实际未返回 401'
} catch {
    $code = 0
    try { $code = [int]$_.Exception.Response.StatusCode.value__ } catch { }
    Check '未登录导出 401' ($code -eq 401) "HTTP=$code"
}

Write-Host ""
Write-Host "结果：PASS=$pass FAIL=$fail"
if ($fail -gt 0) {
    Write-Host ("失败项：" + ($failures -join ' / '))
    exit 1
}
exit 0
