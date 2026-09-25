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
Check '③ url 形如 http(s)://host/uploads/<uuid>.png' ($url -match '^https?://.+/uploads/[0-9a-f\-]{36}\.png$') $url

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

Write-Host ''
Write-Host "===== 上传冒烟：$pass 通过 / $fail 失败 ====="
if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Host ("  失败项：{0}" -f $_) }
    exit 1
}
exit 0
