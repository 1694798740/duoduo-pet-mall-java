# AI 导购「多多」（A9）端到端冒烟（PowerShell 5.1 / pwsh 7 双兼容）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ai-smoke.ps1
#
# 前置：容器全部 healthy（duoduo-ai 为第 10 个容器），AI 默认 mock 模式（DUODUO_AI_MOCK_ENABLED=true）。
#
# 覆盖：
#   1. capabilities：mock 形态标识与可用性（前端据此展示入口与提示）；
#   2. 对话：回复非空 + 推荐每一条都来自真实商品库（id 可经公开详情接口验证，标题同源）——
#      「不得编造商品」是 A9 验收①的核心，断言落在 id 与 title 双对齐上；
#   3. 中文意图召回（幼犬/狗粮/猫）与无匹配时的如实告知；
#   4. 响应不泄露服务间口径字段（sellerId 不出候选视图）；
#   5. 空/超长消息边界 400；
#   6. 每 IP 限流：连发超限请求必现 429（放最后，避免占用本分钟配额）。
param(
    [string]$Gateway = 'http://127.0.0.1:8080'
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
    $params = @{ Uri = $uri; Method = $method; UseBasicParsing = $true; TimeoutSec = 30 }
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
                $resp = $_.Exception.Response
                $t = $null
                if ($resp -and $resp.PSObject.Methods.Name -contains 'GetResponseStream') {
                    $st = $resp.GetResponseStream()
                    if ($st) { $t = (New-Object System.IO.StreamReader($st)).ReadToEnd() }
                } elseif ($resp -and $null -ne $resp.Content) {
                    try { $t = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult() } catch { }
                }
                if ($t) { $body = $t | ConvertFrom-Json }
            } catch { }
        }
        return @{ status = $code; body = $body }
    }
}

Write-Host ""
Write-Host "=== 多多宠物商店 · AI 导购（A9）冒烟 @ $Gateway ==="
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

# ---------- 1. 能力声明 ----------
Write-Host ""
Write-Host "> 能力声明（capabilities）"
$sess = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$r = Call 'Get' "$Gateway/api/duoduo/capabilities" $sess $null $null
Check "capabilities 返回 200" ($r.status -eq 200) $r.status
Check "mock 模式标识正确" ($r.body.data.mode -eq 'mock') ($r.body.data | ConvertTo-Json -Compress)
Check "chat 可用" ($r.body.data.chatAvailable -eq $true) ($r.body.data | ConvertTo-Json -Compress)

# ---------- 2. 对话主链路：真实商品库不变式 ----------
Write-Host ""
Write-Host "> 对话（推荐必须来自真实商品库）"
$r = Call 'Post' "$Gateway/api/duoduo/chat" $sess @{ message = '推荐一款适合幼犬吃的狗粮' } $null $null
Check "对话返回 200" ($r.status -eq 200) ($r.body | ConvertTo-Json -Compress -Depth 6)
Check "回复非空" ($r.body.data.reply -and "$($r.body.data.reply)".Length -gt 0) $r.body.data.reply
Check "推荐列表非空（幼犬/狗粮种子可召回）" ($null -ne $r.body.data.recommendations -and @($r.body.data.recommendations).Count -ge 1) ($r.body.data.recommendations | ConvertTo-Json -Compress)

$recIds = @($r.body.data.recommendations | ForEach-Object { $_.id })
$allValid = $true
$detailMatched = $true
foreach ($rid in $recIds) {
    $d = Call 'Get' "$Gateway/api/products/$rid" $sess $null $null
    if ($d.status -ne 200) { $allValid = $false }
    $rec = @($r.body.data.recommendations | Where-Object { $_.id -eq $rid })[0]
    # 详情响应是 { card, detail, images, skus, ... } —— 标题在 card 里，不是顶层
    if ($d.status -eq 200 -and $d.body.data.card.title -ne $rec.title) { $detailMatched = $false }
}
Check "每条推荐 id 都能经公开详情验证（无编造商品）" $allValid ($recIds -join ',')
Check "推荐标题与商品库逐字同源" $detailMatched

$leak = @($r.body.data.recommendations | Where-Object { $null -ne $_.PSObject.Properties['sellerId'] }).Count
Check "候选视图不泄露服务间字段（sellerId）" ($leak -eq 0)

# ---------- 3. 意图召回与无匹配 ----------
Write-Host ""
Write-Host "> 意图召回与无匹配"
$r = Call 'Post' "$Gateway/api/duoduo/chat" $sess @{ message = '猫咪爱吃的零食罐头' } $null $null
Check "猫品类对话返回 200" ($r.status -eq 200) $r.status
$catIds = @($r.body.data.recommendations | ForEach-Object { $_.id })
$catValid = $true
foreach ($cid in $catIds) {
    $d = Call 'Get' "$Gateway/api/products/$cid" $sess $null $null
    if ($d.status -ne 200) { $catValid = $false }
}
Check "猫品类推荐同样全部真实存在" $catValid ($catIds -join ',')

$r = Call 'Post' "$Gateway/api/duoduo/chat" $sess @{ message = 'QWERASDFZXCV 无关输入' } $null $null
Check "无关输入返回 200 且如实答复" ($r.status -eq 200 -and "$($r.body.data.reply)" -ne '') ($r.body | ConvertTo-Json -Compress -Depth 6)
$r = Call 'Post' "$Gateway/api/duoduo/chat" $sess @{ message = '' } $null $null
Check "空消息返回 400（边界校验）" ($r.status -eq 400) $r.status
$long = '啊' * 501
$r = Call 'Post' "$Gateway/api/duoduo/chat" $sess @{ message = $long } $null $null
Check "超长消息返回 400（500 上限）" ($r.status -eq 400) $r.status

# ---------- 4. 每 IP 限流（放最后：本分钟配额已被前面用掉一部分） ----------
Write-Host ""
Write-Host "> 每 IP 限流（连发超限必现 429）"
$limitHits = 0
for ($i = 0; $i -lt 30; $i++) {
    $r = Call 'Post' "$Gateway/api/duoduo/chat" $sess @{ message = "限流探测 $i" } $null $null
    if ($r.status -eq 429) { $limitHits++ }
}
Check "连发 30 次至少触发一次 429" ($limitHits -ge 1) "limitHits=$limitHits"

# ---------- 结果 ----------
Write-Host ""
Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
if ($fail -gt 0) {
    Write-Host "失败项："
    $failures | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
exit 0
