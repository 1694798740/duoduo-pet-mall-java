# 图片上传（A15）端到端冒烟（Windows PowerShell 5.1）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\upload-smoke.ps1
#
# 前置：duoduo-fulfilment / duoduo-gateway 容器为最新镜像且 healthy。
#
# 覆盖：
#   ① GET /uploads/{不存在} → 404（证明白名单放行生效：是 404 不是 401）；
#   ② POST /api/uploads 无令牌 → 401（上传必须登录，白名单没把 POST 连带放行）；
#   ③ 带 Bearer 上传 1x1 PNG → 200，返回 url 形如 http(s)://host/uploads/<uuid>.png；
#   ④ GET ③ 的 url → 200 且 Content-Type 为 image/png（<image> 标签可直出的关键）；
#   ⑤ 上传 .txt → 400（扩展名白名单拒绝）；
#   ⑥ 上传结果可作为售后 images 直链被后端接受（走 ImageUrls.validate 的口径一致）：
#      用一次「我的售后」空列表读（无副作用）验证令牌链路，直链合法性由 ③④ 与
#      售后创建接口（aftersale-smoke.ps1 扩展覆盖）共同保证。
#   ⑦ ★ S9 · 落点自证（**这一组让"两种实现各跑一遍"成立**）：
#      先读容器内的 STORAGE_TYPE 认清后端，再按后端分叉断言落点 ——
#        local：文件**必须**在卷 /data/uploads 里（正对照）；
#        cos  ：文件**必须不在**卷里（证明真的走了桶、没悄悄回落本地盘），
#               且返回的 url 必须指向桶域名（UPLOADS_PUBLIC_BASE_URL 已配）。
#      ★ 为什么需要它：①~⑥ 在两种后端下**完全一样**（这正是抽象层要的），
#        但也意味着光看它们分不清图落在哪 —— 「配了 cos 却因缺密钥回落 local」会全绿。
#
# 夹具：随机手机号注册，结尾不需要清理（不写任何业务表，图片落 fulfilment 卷）。
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    [string]$Password = 'duoduo123'
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

$phone = '135' + (Get-Random -Minimum 10000000 -Maximum 99999999)

# ── 夹具：注册 + 令牌版登录拿 Bearer ────────────────────────────────────────
$reg = Invoke-WebRequest -Uri "$Gateway/api/auth/register" -Method Post -Body (@{
    phone = $phone; password = $Password; nickname = '上传冒烟' } | ConvertTo-Json) `
    -ContentType 'application/json; charset=utf-8' -UseBasicParsing -TimeoutSec 25 -ErrorAction SilentlyContinue
if (-not $reg) {
    $reg = Invoke-WebRequest -Uri "$Gateway/api/auth/register" -Method Post -Body (@{
        phone = $phone; password = $Password; nickname = '上传冒烟' } | ConvertTo-Json) `
        -ContentType 'application/json; charset=utf-8' -UseBasicParsing -TimeoutSec 25
}
$loginBody = @{ phone = $phone; password = $Password } | ConvertTo-Json
$login = Invoke-RestMethod -Uri "$Gateway/api/auth/login/token" -Method Post -Body $loginBody `
    -ContentType 'application/json; charset=utf-8' -TimeoutSec 25
$token = $login.data.accessToken
Check '夹具：令牌版登录拿到 accessToken' ([bool]$token) ($login | ConvertTo-Json -Depth 3)

# ── HTTP 客户端（multipart 需要 HttpClient） ────────────────────────────────
Add-Type -AssemblyName System.Net.Http
$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(30)
$client.BaseAddress = [Uri]$Gateway

# ── ★ S9 · 先认清「当前跑的是哪种存储实现」────────────────────────────────────
# 为什么必须显式认：本套件的行为断言（①~⑥）在 local / cos 下**完全一样** ——
# 这正是抽象层想要的效果（对外契约不变）。但"行为一样"也意味着
# **光看这几条判据，分不清图到底落在卷里还是桶里**，于是存在一种最危险的假绿：
# 「配置改成了 cos，实际却因为缺密钥回落到本地盘」，①~⑥ 依然全绿。
# 所以这里读容器环境变量认后端，并按后端追加一条**落点自证**（⑦b / ⑦c）。
$FulfilContainer = 'duoduo-app-fulfilment'
$script:envRaw = @(& docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' $FulfilContainer 2>$null)
function Get-ContainerEnv([string[]]$Raw, [string]$Name) {
    $hit = $Raw | Where-Object { $_ -like ($Name + '=*') } | Select-Object -First 1
    if ($hit) { return ($hit -split '=', 2)[1].Trim() }
    return ''
}
$backend = Get-ContainerEnv $script:envRaw 'STORAGE_TYPE'
if (-not $backend) { $backend = 'local' }   # 与 ObjectStorageAutoConfiguration 的默认值保持一致
$backend = $backend.ToLower()
$uploadsBaseUrl = Get-ContainerEnv $script:envRaw 'UPLOADS_PUBLIC_BASE_URL'
$cosPublicBucket = Get-ContainerEnv $script:envRaw 'COS_BUCKET_PUBLIC'
Write-Host ("  存储后端（容器内 STORAGE_TYPE）= {0}" -f $backend) -ForegroundColor Cyan
if ($backend -eq 'cos') {
    Write-Host ("  图片对外 base（UPLOADS_PUBLIC_BASE_URL）= '{0}'；公开桶 = {1}" -f $uploadsBaseUrl, $cosPublicBucket) -ForegroundColor Cyan
}

# 1x1 透明 PNG
$pngBytes = [Convert]::FromBase64String('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==')

# ── ① GET /uploads/{不存在} → 404 ───────────────────────────────────────────
$resp = $client.GetAsync('/uploads/00000000-0000-0000-0000-000000000000.png').Result
Check '① 未存在的图片读 404（白名单放行生效）' ([int]$resp.StatusCode -eq 404) ("实际 " + [int]$resp.StatusCode)

# ── ② POST /api/uploads 无令牌 → 401 ────────────────────────────────────────
$content = New-Object System.Net.Http.MultipartFormDataContent
$fileContent = New-Object System.Net.Http.ByteArrayContent(,$pngBytes)
$content.Add($fileContent, 'file', 'test.png')
$resp = $client.PostAsync('/api/uploads', $content).Result
Check '② 匿名上传 401（POST 未被白名单连带放行）' ([int]$resp.StatusCode -eq 401) ("实际 " + [int]$resp.StatusCode)

# ── ③ 带 Bearer 上传 PNG → 200 + url ────────────────────────────────────────
$client.DefaultRequestHeaders.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $token)
$content = New-Object System.Net.Http.MultipartFormDataContent
$fileContent = New-Object System.Net.Http.ByteArrayContent(,$pngBytes)
# 真实客户端（wx.uploadFile / 浏览器）都会给 part 带 Content-Type；不设会被服务端按非法类型拒
$fileContent.Headers.ContentType = New-Object System.Net.Http.Headers.MediaTypeHeaderValue('image/png')
$content.Add($fileContent, 'file', 'hello.png')
$resp = $client.PostAsync('/api/uploads', $content).Result
$upJson = $resp.Content.ReadAsStringAsync().Result
$url = ''
if ($upJson) {
    try { $url = ($upJson | ConvertFrom-Json).data.url } catch {}
}
Check '③ 登录后上传 PNG → 200 + SUCCESS' ([int]$resp.StatusCode -eq 200 -and $upJson -match '"code":"SUCCESS"') ("实际 " + [int]$resp.StatusCode + " " + $upJson)
# COS（数据万象）回的 url 可能带处理参数（?imageMogr2/...），正则允许可选 query —— 存储落点仍是 /uploads/<uuid>.png
Check '③ url 形如 http(s)://host/uploads/<uuid>.png' ($url -match '^https?://.+/uploads/[0-9a-f\-]{36}\.png(\?.*)?$') $url

# ── ④ GET 上传返回的 url → 200 image/png ────────────────────────────────────
if ($url) {
    $imgPath = $url -replace '^https?://[^/]+', ''
    $respImg = $client.GetAsync($imgPath).Result
    $ct = ''
    if ($respImg.Content.Headers.ContentType) { $ct = $respImg.Content.Headers.ContentType.MediaType }
    Check '④ 回读图片 200 且 Content-Type=image/png' ([int]$respImg.StatusCode -eq 200 -and $ct -eq 'image/png') ("实际 " + [int]$respImg.StatusCode + " " + $ct)
} else {
    Check '④ 回读图片 200 且 Content-Type=image/png' $false '③ 未返回 url'
}

# ── ⑤ 上传 .txt → 400 ───────────────────────────────────────────────────────
$content = New-Object System.Net.Http.MultipartFormDataContent
$txtContent = New-Object System.Net.Http.ByteArrayContent(,[System.Text.Encoding]::UTF8.GetBytes('not an image'))
$content.Add($txtContent, 'file', 'evil.txt')
$resp = $client.PostAsync('/api/uploads', $content).Result
Check '⑤ 非图片扩展名 400' ([int]$resp.StatusCode -eq 400) ("实际 " + [int]$resp.StatusCode)

# ── ⑥ 令牌链路与售后域连通（读我的售后，无副作用） ──────────────────────────
$mine = Invoke-RestMethod -Uri "$Gateway/api/aftersales/mine?page=1&pageSize=1" -Method Get `
    -Headers @{ Authorization = "Bearer $token" } -TimeoutSec 25
Check '⑥ Bearer 令牌读售后列表连通' ($mine.code -eq 'SUCCESS') ($mine | ConvertTo-Json -Depth 2)

# ── ⑦ S9 · 落点自证：图到底落在卷里还是桶里 ──────────────────────────────────
# ★ 这一组是"两种实现各跑一遍"的落点判据：同一套行为断言 + 一条按后端分叉的落点证据。
#   判据刻意做成**互斥的正对照**（本地实现要求"在卷里"，对象存储要求"不在卷里"），
#   这样"悄悄回落本地盘"与"配了桶却还在写盘"都能被当场抓住。
Check '⑦ 容器内的存储后端可识别（只允许 local / cos，不许未知）' `
    (@('local', 'cos') -contains $backend) ("STORAGE_TYPE='$backend'")

$storedName = ''
if ($url -match '/uploads/([0-9a-f\-]{36}\.[a-z]+)(\?.*)?$') { $storedName = $Matches[1] }
if (-not $storedName) {
    Check '⑦b 落点自证（需要 ③ 返回的 url）' $false "没能从 url 里取出存储文件名：$url"
} else {
    # 用 -u 0 读（卷内文件的属主是 root，业务用户可能读不到 —— 见铁律 28）
    $inVol = ("$(& docker exec -u 0 $FulfilContainer sh -c ('test -f /data/uploads/' + $storedName + ' && echo YES || echo NO') 2>$null)").Trim()
    if ($backend -eq 'local') {
        Check '⑦b [local] 上传的文件确实落在卷 /data/uploads 里（正对照）' `
            ($inVol -eq 'YES') "卷内=$inVol 文件=$storedName"
    } else {
        Check '⑦b [cos] 上传的文件**不在**卷里（证明真的走了桶，没有悄悄回落本地盘）' `
            ($inVol -eq 'NO') "卷内竟然是 $inVol —— 配置写着 cos 却仍在写本地盘。文件=$storedName"
        Check '⑦c [cos] 返回的 url 指向桶域名（UPLOADS_PUBLIC_BASE_URL 已配置）' `
            ($uploadsBaseUrl -ne '' -and $url.StartsWith($uploadsBaseUrl)) `
            "base='$uploadsBaseUrl' url='$url'"
    }
}

Write-Host ''
Write-Host ("===== 上传冒烟（存储后端 {0}）：{1} 通过 / {2} 失败 =====" -f $backend, $pass, $fail)

# ── ⑧ S9 · 夹具自毁（两种后端各自的最干净删法） ─────────────────────────────
# ★ 夹具三纪律：现场建 / 跑完自毁 / 删不掉要说出来。
#   local：删卷里的文件（-u 0，卷内属主是 root —— 见铁律 28）；
#   cos  ：必须**签名 DELETE** 掉桶里的对象。若不删，每跑一次回归就在**真实桶**里
#         多留一个 70 字节的测试图 —— 体积小但会越积越多，而且"桶里那些不知道哪来的对象"
#          日后没人敢删（这正是"夹具不清＝迟早腐烂"的成本）。
if ($storedName) {
    if ($backend -eq 'cos') {
        $okDel = $false
        try {
            $region = Get-ContainerEnv $script:envRaw 'COS_REGION'
            $ak = Get-ContainerEnv $script:envRaw 'COS_SECRET_ID'
            $sk = Get-ContainerEnv $script:envRaw 'COS_SECRET_KEY'
            $bucket = Get-ContainerEnv $script:envRaw 'COS_BUCKET_PUBLIC'
            $hostName = "$bucket.cos.$region.myqcloud.com"
            $key = "uploads/$storedName"

            # COS 签名 v5（与 Java 侧同一算法；★ StringToSign 必须带 sha1 前缀与 KeyTime 中间段）
            function New-CosAuth([string]$Method, [string]$Path, [string]$HostName, [string]$Ak, [string]$Sk) {
                $now = [int][double]::Parse((Get-Date -Date (Get-Date).ToUniversalTime() -UFormat %s))
                $kt = "$($now - 60);$($now + 900)"
                $hmac = New-Object System.Security.Cryptography.HMACSHA1
                $hmac.Key = [System.Text.Encoding]::UTF8.GetBytes($Sk)
                $signKeyBytes = $hmac.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($kt))
                $signKey = (($signKeyBytes | ForEach-Object { $_.ToString('x2') }) -join '')
                $httpString = "$($Method.ToLower())`n$Path`n`nhost=$HostName`n"
                $sha1 = [System.Security.Cryptography.SHA1]::Create()
                $sha1Hex = (($sha1.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($httpString)) | ForEach-Object { $_.ToString('x2') }) -join '')
                $stringToSign = "sha1`n$kt`n$sha1Hex`n"
                $hmac2 = New-Object System.Security.Cryptography.HMACSHA1
                $hmac2.Key = [System.Text.Encoding]::UTF8.GetBytes($signKey)
                $sig = (($hmac2.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($stringToSign)) | ForEach-Object { $_.ToString('x2') }) -join '')
                return "q-sign-algorithm=sha1&q-ak=$Ak&q-sign-time=$kt&q-key-time=$kt&q-header-list=host&q-url-param-list=&q-signature=$sig"
            }
            $auth = New-CosAuth 'delete' "/$key" $hostName $ak $sk
            $dclient = New-Object System.Net.Http.HttpClient
            $dreq = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Delete, "https://$hostName/$key")
            # ★ 必须用 TryAddWithoutValidation：.NET 会把 Authorization 当**标准鉴权头**去按
            #   "scheme + 参数" 的语法校验，而 COS 的 `q-sign-algorithm=...&...` 不符合那个语法，
            #   Headers.Add 会直接抛"值格式无效" —— 实测就栽在这里。
            $null = $dreq.Headers.TryAddWithoutValidation('Authorization', $auth)
            $dresp = $dclient.SendAsync($dreq).Result
            $okDel = ([int]$dresp.StatusCode -ge 200 -and [int]$dresp.StatusCode -lt 300)
            Check '⑧ [cos] 夹具自毁：桶内测试对象已删除' $okDel ("DELETE /{0} -> HTTP {1}" -f $key, [int]$dresp.StatusCode)
        } catch {
            Check '⑧ [cos] 夹具自毁：桶内测试对象已删除' $false ("异常：" + $_.Exception.Message)
        }
    } else {
        $null = & docker exec -u 0 $FulfilContainer sh -c ('rm -f /data/uploads/' + $storedName) 2>$null
        # ★ 回读确认（铁律 28：`rm` 的失败可能只在 stderr，且被 2>$null 吞掉 —— 必须回读才知行没行）
        $left = ("$(& docker exec -u 0 $FulfilContainer sh -c ('test -f /data/uploads/' + $storedName + ' && echo YES || echo NO') 2>$null)").Trim()
        Check '⑧ [local] 夹具自毁：卷内测试文件已删除（回读确认）' ($left -eq 'NO') "回读=$left 文件=$storedName"
    }
}

Write-Host ("===== 上传冒烟最终：{0} 通过 / {1} 失败 =====" -f $pass, $fail)
if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Host ("  失败项：{0}" -f $_) }
    exit 1
}
exit 0
