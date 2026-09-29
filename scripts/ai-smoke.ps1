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
#   3b. 多轮追问召回（上下文并入关键词）；
#   3c. 推荐数量上限 + 预算硬过滤（无条件断言：mock 与 llm 共享召回链路，候选在 SQL 里已按预算筛过）；
#   3d. 预算区间过滤（重叠语义）+ 极小预算空结果（证明过滤真的生效，不是空转断言）
#       + 预算没听懂的如实告知 + 「最便宜」追问不被误判为预算；
#   3e. 出口预判（物流/售后类问题不调模型、给入口）；
#   3f. 澄清：信息不足反问一次给可点选项，告知已澄清后不再反问；
#   4. 响应不泄露服务间口径字段（sellerId 不出候选视图）；
#   5. 空/超长消息边界 400；
#   6. 每 IP 限流：连发超限必现 429，且**伪造 X-Forwarded-For 不能绕过**
#      （限流键取网关写入的 X-Real-IP，不再信任客户端可写的头）（放最后，避免占用本分钟配额）。
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
# 形态自适应：CI 容器无 Key → mock；本地/生产配了百炼 Key → llm。
# 两种形态都是"正确部署"，断言只锁死**绝不降级到 unconfigured**（有 Key 却报 503 才是真故障）。
# 与下方 llm 预算断言同一思路：形态差异用 SKIP/分支消化，不硬编码某一种形态。
$aiMode = "$($r.body.data.mode)"
Check "AI 形态标识正确（mock 或 llm，绝不 unconfigured）" (($aiMode -eq 'mock') -or ($aiMode -eq 'llm')) ($r.body.data | ConvertTo-Json -Compress)
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

# ---------- 3b. 多轮追问召回（上下文并入关键词）----------
# 追问句本身不含商品词（「那最便宜的是哪个？」），若召回只用当前消息，历史传了也白传；
# 历史里最近若干轮**用户**提问会被并入召回关键词，故追问仍能落到同一批商品上。
Write-Host ""
Write-Host "> 多轮追问召回"
$multiHistory = @(
    @{ role = 'user'; content = '推荐猫粮' },
    @{ role = 'assistant'; content = '皇家幼猫粮、麦富迪冻干双拼' }
)
$r = Call 'Post' "$Gateway/api/duoduo/chat" $sess @{ message = '那最便宜的是哪个？'; history = $multiHistory } $null $null
Check "追问句返回 200" ($r.status -eq 200) ($r.body | ConvertTo-Json -Compress -Depth 6)
Check "追问仍能召回（上下文并入关键词生效）" (@($r.body.data.recommendations).Count -ge 1) ($r.body.data.recommendations | ConvertTo-Json -Compress)
$followupIds = @($r.body.data.recommendations | ForEach-Object { $_.id })
$followupValid = $true
foreach ($fid in $followupIds) {
    $d = Call 'Get' "$Gateway/api/products/$fid" $sess $null $null
    if ($d.status -ne 200) { $followupValid = $false }
}
Check "追问推荐的每条仍真实存在（无编造）" $followupValid ($followupIds -join ',')

# 连续追问：前两轮用户消息都不含商品词，第二句追问仍须命中（验证「多轮」而非「仅上一轮」）
$deepHistory = @(
    @{ role = 'user'; content = '推荐猫粮' },
    @{ role = 'assistant'; content = '皇家幼猫粮' },
    @{ role = 'user'; content = '那最便宜的是哪个？' },
    @{ role = 'assistant'; content = '网易严选 99 元' }
)
$r = Call 'Post' "$Gateway/api/duoduo/chat" $sess @{ message = '有货吗？'; clarified = $true; history = $deepHistory } $null $null
Check "连续追问（第二轮追问）仍能召回" (@($r.body.data.recommendations).Count -ge 1) ($r.body.data.recommendations | ConvertTo-Json -Compress)

# 对照：不带 history 的追问句必须如实说没有 —— 证明上面命中的确来自上下文并入，而非该句本身可召回
$r = Call 'Post' "$Gateway/api/duoduo/chat" $sess @{ message = '那最便宜的是哪个？'; clarified = $true } $null $null
Check "无 history 的追问句召回为空（对照成立）" (@($r.body.data.recommendations).Count -eq 0) ($r.body.data.recommendations | ConvertTo-Json -Compress)
# 「最便宜」问的是候选里最便宜的商品、不是预算 —— 不该被误判成「预算没听懂」（回归探针）
Check "「最便宜」追问不被误判为预算未解析" ("$($r.body.data.reply)".Contains('没听清你的预算') -eq $false) "$($r.body.data.reply)"

# ---------- 3c. 推荐与话术一致性（候选≠必推清单）----------
# 候选里混有超预算商品时，正文只挑符合的、recommendations 却全量下发，会让卡片与话术互相打脸。
Write-Host ""
Write-Host "> 推荐与话术一致性"
# clarified=$true：短句会被澄清反问截住（见 3f 探针），这里要测的是数量上限，跳过澄清
$r = Call 'Post' "$Gateway/api/duoduo/chat" $sess @{ message = '推荐猫粮'; clarified = $true } $null $null
$allCandCount = @($r.body.data.recommendations).Count
Check "推荐数量受上限约束（<= 4）" ($allCandCount -le 4) $allCandCount

$r = Call 'Post' "$Gateway/api/duoduo/chat" $sess @{ message = '预算 200 元以内的猫粮，有推荐吗？' } $null $null
Check "带预算条件的对话返回 200" ($r.status -eq 200) $r.status
# 无条件必查（第五十七轮）：mock 与 llm 共享同一条召回链路，约束在商品域 SQL 里生效 ——
# mock 的推荐就是候选前 3，候选没筛干净 mock 照样超预算。旧版把这条断言限定在 llm 形态，
# 而 CI 永远跑 mock → 断言从未执行过（假绿），这正是要修掉的问题。
$overBudget = @($r.body.data.recommendations | Where-Object { $_.minPrice -gt 20000 }).Count
Check "推荐不超出用户预算（200 元，无条件必查）" ($overBudget -eq 0) ($r.body.data.recommendations | ConvertTo-Json -Compress)

# ---------- 3d. 约束过滤与告知（第五十七轮 · M1）----------
Write-Host ""
Write-Host "> 约束过滤与告知"
# 区间语义是「价格区间与预算窗口有重叠」：maxPrice >= 下限 且 minPrice <= 上限
$r = Call 'Post' "$Gateway/api/duoduo/chat" $sess @{ message = '预算 100-300 元的猫粮' } $null $null
Check "预算区间对话返回 200" ($r.status -eq 200) $r.status
$outOfRange = @($r.body.data.recommendations | Where-Object { $_.maxPrice -lt 10000 -or $_.minPrice -gt 30000 }).Count
Check "区间推荐全部落在 100-300 元窗口（重叠语义）" ($outOfRange -eq 0) ($r.body.data.recommendations | ConvertTo-Json -Compress)

# 极小预算必得空结果：若 SQL 过滤没接上，猫粮候选照常召回 → 这条就会失败。
# 它是 3c 无条件断言的「反向验证」——证明预算参数真的传进了数据库查询，而不是被半路丢掉。
$r = Call 'Post' "$Gateway/api/duoduo/chat" $sess @{ message = '预算 1 元以内的猫粮'; clarified = $true } $null $null
Check "极小预算（1 元）召回为空（证明 SQL 过滤真实生效）" ($r.status -eq 200 -and @($r.body.data.recommendations).Count -eq 0) ($r.body | ConvertTo-Json -Compress -Depth 6)

# 用户提了预算但没给出数字：必须如实告知，绝不能当「没提预算」静默放过
$r = Call 'Post' "$Gateway/api/duoduo/chat" $sess @{ message = '有没有便宜划算的猫粮'; clarified = $true } $null $null
Check "预算没听懂时如实告知（不静默失效）" ($r.status -eq 200 -and "$($r.body.data.reply)".Contains('没听清你的预算')) "$($r.body.data.reply)"

# ---------- 3e. 出口预判（M5）：答不了的问题不硬答 ----------
Write-Host ""
Write-Host "> 出口预判（物流/售后类问题给入口、不编答案）"
$r = Call 'Post' "$Gateway/api/duoduo/chat" $sess @{ message = '我的快递到哪了？' } $null $null
Check "物流问题返回 200" ($r.status -eq 200) $r.status
Check "物流问题引导去订单页（不编造物流信息）" ("$($r.body.data.reply)".Contains('订单') -or "$($r.body.data.reply)".Contains('物流')) "$($r.body.data.reply)"
Check "物流问题不下发商品推荐" (@($r.body.data.recommendations).Count -eq 0) ($r.body.data.recommendations | ConvertTo-Json -Compress)

# ---------- 3f. 澄清（M5）：信息不足反问一次，给可点选项 ----------
Write-Host ""
Write-Host "> 澄清反问与出口"
$r = Call 'Post' "$Gateway/api/duoduo/chat" $sess @{ message = '推荐一下' } $null $null
$hasClarify = ($null -ne $r.body.data.clarify) -and ("$($r.body.data.clarify.question)" -ne '')
$optCount = @($r.body.data.clarify.options).Count
Check "极短且无约束的消息触发澄清反问" ($r.status -eq 200 -and $hasClarify) ($r.body | ConvertTo-Json -Compress -Depth 6)
Check "澄清给出可点选项（>= 2 个）" ($optCount -ge 2) $optCount
Check "澄清时不夹带商品推荐" (@($r.body.data.recommendations).Count -eq 0) ($r.body.data.recommendations | ConvertTo-Json -Compress)

$r = Call 'Post' "$Gateway/api/duoduo/chat" $sess @{ message = '推荐一下'; clarified = $true } $null $null
Check "告知已澄清后不再反问（一次对话最多问一次）" ($r.status -eq 200 -and $null -eq $r.body.data.clarify) ($r.body | ConvertTo-Json -Compress -Depth 6)

$r = Call 'Post' "$Gateway/api/duoduo/chat" $sess @{ message = 'QWERASDFZXCV 无关输入'; clarified = $true } $null $null
Check "无关输入返回 200 且如实答复" ($r.status -eq 200 -and "$($r.body.data.reply)" -ne '') ($r.body | ConvertTo-Json -Compress -Depth 6)
$r = Call 'Post' "$Gateway/api/duoduo/chat" $sess @{ message = '' } $null $null
Check "空消息返回 400（边界校验）" ($r.status -eq 400) $r.status
$long = '啊' * 501
$r = Call 'Post' "$Gateway/api/duoduo/chat" $sess @{ message = $long } $null $null
Check "超长消息返回 400（500 上限）" ($r.status -eq 400) $r.status

# ---------- 4. 每 IP 限流（放最后：本分钟配额已被前面用掉一部分） ----------
Write-Host ""
Write-Host "> 每 IP 限流（连发超限必现 429；伪造来源头不能绕过）"
$limitHits = 0
$forgedHits = 0
for ($i = 0; $i -lt 30; $i++) {
    $r = Call 'Post' "$Gateway/api/duoduo/chat" $sess @{ message = "限流探测 $i" } $null $null
    if ($r.status -eq 429) { $limitHits++ }
    # 每第 3 发夹带一个「每次都不同的伪造 X-Forwarded-For」：旧实现取这个头当限流键，
    # 换个假 IP 就等于新用户、永不限流。现在网关先剥离再写入可信 X-Real-IP，
    # 伪造值到不了服务 → 与正常请求共用同一个键 → 窗口耗尽后照样 429。
    if ($i % 3 -eq 2) {
        $fake = "198.18.$($i).$($i)"
        $r2 = Call 'Post' "$Gateway/api/duoduo/chat" $sess @{ message = "伪造来源探测 $i" } $null @{ 'X-Forwarded-For' = $fake }
        if ($r2.status -eq 429) { $forgedHits++ }
    }
}
Check "连发 30 次至少触发一次 429" ($limitHits -ge 1) "limitHits=$limitHits"
Check "伪造 X-Forwarded-For 不能绕过限流（也吃到 429）" ($forgedHits -ge 1) "forgedHits=$forgedHits"

# ---------- 结果 ----------
Write-Host ""
Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
if ($fail -gt 0) {
    Write-Host "失败项："
    $failures | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
exit 0
