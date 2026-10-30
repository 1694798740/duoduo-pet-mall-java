# AI 导购「多多」（A9）端到端冒烟（PowerShell 5.1 / pwsh 7 双兼容）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ai-smoke.ps1
#
# 前置：容器全部 healthy（duoduo-ai 为第 10 个容器），且已执行幂等 DDL：
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\apply-73-ai-butler-ddl.ps1
#
# 可选：设置环境变量 DUODUO_SMOKE_TOKEN（一个有效用户令牌）以启用「登录态卡片 / 红点」断言；
#      未提供时该段明确 SKIP，不算失败（不把「没有夹具」伪装成「通过」）。
#
# 覆盖：
#   1. capabilities：可用性标识与提示（llm / unconfigured，前端据此决定是否展示入口）；
#   2. 对话：回复非空 + 推荐每一条都来自真实商品库（id 可经公开详情接口验证，标题同源）——
#      「不得编造商品」是 A9 验收①的核心，断言落在 id 与 title 双对齐上；
#   3. 中文意图召回（幼犬/狗粮/猫）与无匹配时的如实告知；
#      ★ E27：「幼犬/狗粮」的种子商品现场建（Resolve-DogFoodSpuFixture），跑完删除 ——
#        本机库清理后只剩猫粮 SPU，不建前置则该断言红的是数据而非代码（详见该 helper 注释）。
#   3b. 多轮追问召回（上下文并入关键词）；
#   3c. 推荐数量上限 + 预算硬过滤（无条件断言：mock 与 llm 共享召回链路，候选在 SQL 里已按预算筛过）；
#   3d. 预算区间过滤（重叠语义）+ 极小预算空结果（证明过滤真的生效，不是空转断言）
#       + 预算没听懂的如实告知 + 「最便宜」追问不被误判为预算；
#   3e. 出口预判（物流/售后类问题不调模型、给入口）；
#   3f. 澄清：信息不足反问一次给可点选项，告知已澄清后不再反问；
#   4. 响应不泄露服务间口径字段（sellerId 不出候选视图）；
#   5. 空/超长消息边界 400；
#   6. 全能管家（3g）：游客深度功能给登录入口、游客第 4 轮被拦；
#      提供 DUODUO_SMOKE_TOKEN 时额外断言登录态通知卡片与红点补丁一致性；
#   7. 每 IP 限流：连发超限必现 429，且**伪造 X-Forwarded-For 不能绕过**
#      （限流键取网关写入的 X-Real-IP，不再信任客户端可写的头）（放最后，避免占用本分钟配额）。
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    [string]$Token = $env:DUODUO_SMOKE_TOKEN
)

$ErrorActionPreference = 'Continue'
$pass = 0
$fail = 0
$failures = @()

# E27：关键词召回夹具（现场建 SPU，跑完删）
. (Join-Path $PSScriptRoot 'lib\catalog-fixture.ps1')

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

function ChatTimeoutOf([string]$uri) {
    # `llm` 形态下 /chat 要等外部大模型；DashScopeClient 是**逐个备选模型试**的
    # （每个 15s，见其超时配置），两个模型都超时就接近 30s。
    # 客户端若也给 30s，就会恰好卡在这条线上：**服务端已经正确降级并答完**，
    # 客户端却判成"没拿到响应"（status=0）—— 这是纯假红，而且是最坏的一种：
    # 它会训练出"这条本来就是红的"的习惯，真坏掉时反而没人看。
    # `llm` 形态下 /chat 要等外部大模型；整轮预算已调至 75s（2026-10-30），
    # 客户端超时必须大于它，否则服务端答完而客户端已判超时（status=0 假红）。
    if ($uri -like '*/api/duoduo/chat*') { return 120 }
    return 30
}

function Call([string]$method, [string]$uri, $session, $bodyObj, [hashtable]$headers) {
    $r = CallOnce $method $uri $session $bodyObj $headers
    # 只在**传输层没拿到响应**（status=0）时重试一次：这类失败可以安全重来。
    # HTTP 层的 4xx/5xx 是有意义的信号（含限流 429），绝不重试、绝不掩盖。
    if ($r.status -eq 0 -and $uri -like '*/api/duoduo/chat*') {
        Write-Host "    （对话请求未拿到 HTTP 响应：多半是外部模型超时，重试 1 次）"
        Start-Sleep -Seconds 2
        $r = CallOnce $method $uri $session $bodyObj $headers
    }
    # 503 + DEGRADED 与 status=0 是**同一类**：服务端明确说"我没能在超时内取到召回结果"
    # —— 这是可用性抖动（商品域慢了一下），不是判断错误，可以安全重来一次。
    # ★ 划清界限：**重试的是"拿不到"，绝不重试"答得不对"** —— 召回为空、答非所问、
    #   幂等/权限不符这类都是真实信号，重试只会把它们洗成绿色（那是本项目最忌讳的假绿）。
    if ($r.status -eq 503 -and "$($r.body.code)" -eq 'DEGRADED' -and $uri -like '*/api/duoduo/chat*') {
        Write-Host "    （服务端 503 DEGRADED：召回超时 / 商品域不可达 —— 属「拿不到」，重试 1 次）"
        Start-Sleep -Seconds 2
        $r = CallOnce $method $uri $session $bodyObj $headers
    }
    return $r
}

function CallOnce([string]$method, [string]$uri, $session, $bodyObj, [hashtable]$headers) {
    $params = @{ Uri = $uri; Method = $method; UseBasicParsing = $true; TimeoutSec = (ChatTimeoutOf $uri) }
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
# DEF-003：犬粮直插夹具无法在秒级 smoke 内进入 duoduo_spu_ai（AiSearchIndexJob 默认 5 分钟），
# 继续依赖它只会制造假红；这里改用 AI 索引中真实存在的猫粮商品验证「推荐必须来自商品库」。
# 犬粮/新商品索引延迟属于独立的索引回归项，不在本套件断言。
$r = Call 'Post' "$Gateway/api/duoduo/chat" $sess @{ message = '推荐一款猫粮' } $null $null
Check "对话返回 200" ($r.status -eq 200) ($r.body | ConvertTo-Json -Compress -Depth 6)
Check "回复非空" ($r.body.data.reply -and "$($r.body.data.reply)".Length -gt 0) $r.body.data.reply
Check "推荐列表非空（真实猫粮可召回）" ($null -ne $r.body.data.recommendations -and @($r.body.data.recommendations).Count -ge 1) ($r.body.data.recommendations | ConvertTo-Json -Compress)

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
# 游客配额按 3 轮/设备计：本节改用新的游客会话，避免前面的推荐问答把配额用尽。
$sess = New-Object Microsoft.PowerShell.Commands.WebRequestSession
# 多轮上下文走服务端 Redis 会话：同一 sessionId 连续三轮；
# history 字段是旧客户端兼容形态，AgentCommand 未消费它，不能用来证明多轮召回。
$multiSession = 'smoke-multi-' + [guid]::NewGuid().ToString('N')
$r = Call 'Post' "$Gateway/api/duoduo/chat" $sess @{ message = '推荐猫粮'; clarified = $true; sessionId = $multiSession } $null $null
Check "多轮第 1 轮返回 200" ($r.status -eq 200) $r.status
$r = Call 'Post' "$Gateway/api/duoduo/chat" $sess @{ message = '那最便宜的是哪个？'; clarified = $true; sessionId = $multiSession } $null $null
Check "追问句返回 200" ($r.status -eq 200) ($r.body | ConvertTo-Json -Compress -Depth 6)
Check "追问仍能召回（上下文并入关键词生效）" (@($r.body.data.recommendations).Count -ge 1) ($r.body.data.recommendations | ConvertTo-Json -Compress)
$followupIds = @($r.body.data.recommendations | ForEach-Object { $_.id })
$followupValid = $true
foreach ($fid in $followupIds) {
    $d = Call 'Get' "$Gateway/api/products/$fid" $sess $null $null
    if ($d.status -ne 200) { $followupValid = $false }
}
Check "追问推荐的每条仍真实存在（无编造）" $followupValid ($followupIds -join ',')

# 连续追问：前两轮用户消息都不含商品词，第三轮仍须命中（验证「多轮」而非「仅上一轮」）
$r = Call 'Post' "$Gateway/api/duoduo/chat" $sess @{ message = '有货吗？'; clarified = $true; sessionId = $multiSession } $null $null
Check "连续追问（第二轮追问）仍能召回" (@($r.body.data.recommendations).Count -ge 1) ("status=$($r.status) | " + ($r.body | ConvertTo-Json -Compress -Depth 6))

# 对照：新开游客会话、不带任何上下文，追问句必须如实说没有
$noHistorySess = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$r = Call 'Post' "$Gateway/api/duoduo/chat" $noHistorySess @{ message = '那最便宜的是哪个？'; clarified = $true } $null $null
Check "无历史上下文的追问句召回为空（对照成立）" (@($r.body.data.recommendations).Count -eq 0) ($r.body.data.recommendations | ConvertTo-Json -Compress)
# 「最便宜」问的是候选里最便宜的商品、不是预算 —— 不该被误判成「预算没听懂」（回归探针）
Check "「最便宜」追问不被误判为预算未解析" ("$($r.body.data.reply)".Contains('没听清你的预算') -eq $false) "$($r.body.data.reply)"

# ---------- 3c. 推荐与话术一致性（候选≠必推清单）----------
# 候选里混有超预算商品时，正文只挑符合的、recommendations 却全量下发，会让卡片与话术互相打脸。
Write-Host ""
Write-Host "> 推荐与话术一致性"
# 独立游客会话：本节不再与前文共享 3 轮配额。
$sess = New-Object Microsoft.PowerShell.Commands.WebRequestSession
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
$sess = New-Object Microsoft.PowerShell.Commands.WebRequestSession
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
# 失败信息里带上 status：T- 记录过「reply 打印为空」的坑 —— 只看 reply 分不清
# 「服务真的回了一句别的话」还是「根本没拿到 200 响应」（限流 429 / 超时都会让 data 为 null）。
Check "预算没听懂时如实告知（不静默失效）" ($r.status -eq 200 -and "$($r.body.data.reply)".Contains('没听清你的预算')) "status=$($r.status) reply=[$($r.body.data.reply)]"

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
$sess = New-Object Microsoft.PowerShell.Commands.WebRequestSession
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

# ---------- 3g. 全能管家：卡片 / 红点 / 游客 3 轮（阶段四 + B4） ----------
Write-Host ""
Write-Host "> 全能管家：游客登录引导 / 3 轮限制 / 卡片与红点"
# 独立游客会话：本节要精确验证 3 轮限制，不能继承前面的任何配额消耗。
$sess = New-Object Microsoft.PowerShell.Commands.WebRequestSession

# (1) 游客访问深度功能：必须给出登录入口，而不是编一句「已查到」
#     loginUrl 由服务端工具层冒泡上来（游客没有 userId，工具返回 NEED_LOGIN）
$r = Call 'Post' "$Gateway/api/duoduo/chat" $sess @{ message = '帮我看看我的订单'; clarified = $true } $null $null
Check "游客查订单：200 且给出登录入口（不假装查到）" `
    ($r.status -eq 200 -and "$($r.body.data.loginUrl)" -ne '') `
    ("status=$($r.status) loginUrl=[$($r.body.data.loginUrl)] reply=[$($r.body.data.reply)]")

# (2) 游客 3 轮：同一设备（Cookie 会话）第 4 轮必须被拦。
#     用独立 WebSession 隔离：前面的对话已经把 $sess 的配额用掉一部分。
$guest = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$blocked = $null
for ($gi = 1; $gi -le 4; $gi++) {
    $r = Call 'Post' "$Gateway/api/duoduo/chat" $guest @{ message = "游客轮次探测 $gi"; clarified = $true } $null $null
    if ($gi -lt 4) {
        Check "游客第 $gi 轮可对话（200）" ($r.status -eq 200) "status=$($r.status)"
    } else {
        $blocked = $r
    }
}
Check "游客第 4 轮被 3 轮限制拦截（给出登录入口）" `
    ($null -ne $blocked -and $blocked.status -eq 200 -and "$($blocked.body.data.loginUrl)" -ne '') `
    ("status=$($blocked.status) loginUrl=[$($blocked.body.data.loginUrl)] reply=[$($blocked.body.data.reply)]")

# (3) 登录态卡片与红点：需要 DUODUO_SMOKE_TOKEN（没有夹具时不伪装成通过）
if (-not $Token) {
    Write-Host "  [SKIP] 未提供 DUODUO_SMOKE_TOKEN，跳过登录态卡片/红点断言"
} else {
    $auth = @{ Authorization = "Bearer $Token" }
    $before = Call 'Get' "$Gateway/api/socials/notices/unread-summary" $sess $null $auth
    Check "登录态可读未读汇总（红点前置）" ($before.status -eq 200) "status=$($before.status)"

    $r = Call 'Post' "$Gateway/api/duoduo/chat" $sess @{ message = '我有什么未读通知？'; clarified = $true } $null $auth
    $noticeCards = @($r.body.data.cards | Where-Object { $_.type -eq 'notification_list' })
    Check "登录后返回通知卡片（cards 含 notification_list）" `
        ($r.status -eq 200 -and $noticeCards.Count -eq 1) `
        ("status=$($r.status) cards=[$($r.body.data.cards | ConvertTo-Json -Compress -Depth 4)]")

    $patch = $r.body.data.badgePatch
    if ($null -ne $patch -and $null -ne $patch.notification) {
        $after = Call 'Get' "$Gateway/api/socials/notices/unread-summary" $sess $null $auth
        Check "红点补丁与服务端未读一致（AI 转述的就是权威值）" `
            ($after.status -eq 200 -and [int]$after.body.data.notice -eq [int]$patch.notification) `
            ("before=$($before.body.data.notice) patch=$($patch.notification) after=$($after.body.data.notice)")
    } else {
        Write-Host "  [SKIP] 本轮没有红点变化（当前没有未读），跳过补丁一致性断言"
    }
}

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
