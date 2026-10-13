# scripts/lib/cos-tool.ps1 —— 腾讯云 COS 的最小 PowerShell 客户端（S9 配套）
#
# 为什么需要它：备份/迁移/清理这些**运维脚本**是 PowerShell 写的，而 COS 的
# 官方命令行工具（COSCMD/COSCLI）在本机没装、也不该为了一次性操作去装。
# 于是这里用 .NET 自带的 HMACSHA1/SHA1 实现 COS 签名 v5（与 Java 侧
# `CosSigner` 同一算法），提供 Put/Get/Head/Delete 四个动作。
#
# ★ 两个必须照抄的地方（Java 侧各踩过一次，这里不再踩）：
#   ① StringToSign = "sha1" + "\n" + KeyTime + "\n" + SHA1(HttpString) + "\n"
#      —— 不是裸的 SHA1(HttpString)；
#   ② Authorization 头必须用 TryAddWithoutValidation 加
#      —— .NET 会按"标准鉴权头"的语法校验，`q-sign-algorithm=...` 过不了校验，直接抛"格式无效"。
#
# ★ 签名用的 path 是**未编码**形式，发出去的 URI 才做编码（与 Java 侧一致）。
#
# 用法（点号引入后调函数）：
#   . scripts\lib\cos-tool.ps1
#   $cfg = Get-CosConfig -Role public        # 或 -Role private；也可 -Bucket <名>
#   Invoke-CosPut  -Cfg $cfg -Key 'uploads/x.png' -FilePath .\x.png -ContentType 'image/png'
#   Invoke-CosHead -Cfg $cfg -Key 'uploads/x.png'
#   Invoke-CosDelete -Cfg $cfg -Key 'uploads/x.png'

Set-StrictMode -Version Latest

# 从仓库根 .env 读值（环境变量优先）。与 docker-compose / db-backup 同一优先级。
function Get-CosEnvValue {
    param([string]$Name, [string]$Default = '', [string]$RepoRoot = '')
    $v = [Environment]::GetEnvironmentVariable($Name)
    if (-not [string]::IsNullOrWhiteSpace($v)) { return $v.Trim() }
    if ($RepoRoot -and (Test-Path (Join-Path $RepoRoot '.env'))) {
        $hit = Select-String -LiteralPath (Join-Path $RepoRoot '.env') `
            -Pattern ('^\s*' + [regex]::Escape($Name) + '\s*=') -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($hit) {
            $val = ($hit.Line -split '=', 2)[1].Trim()
            if ($val) { return $val }
        }
    }
    return $Default
}

# 组装一份可用的配置（密钥 + 桶 + 地域 + host）
function Get-CosConfig {
    param(
        [ValidateSet('public', 'private')][string]$Role = 'public',
        [string]$Bucket = '',
        [string]$RepoRoot = ''
    )
    if (-not $RepoRoot) {
        $RepoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSCommandPath))
    }
    $region = Get-CosEnvValue 'COS_REGION' 'ap-guangzhou' $RepoRoot
    if (-not $Bucket) {
        $name = $(if ($Role -eq 'private') { 'COS_BUCKET_PRIVATE' } else { 'COS_BUCKET_PUBLIC' })
        $Bucket = Get-CosEnvValue $name '' $RepoRoot
    }
    $cfg = [pscustomobject]@{
        Role      = $Role
        Region    = $region
        Bucket    = $Bucket
        SecretId  = (Get-CosEnvValue 'COS_SECRET_ID' '' $RepoRoot)
        SecretKey = (Get-CosEnvValue 'COS_SECRET_KEY' '' $RepoRoot)
        Host      = ''
    }
    if (-not $cfg.Bucket) { throw "COS 桶名为空（Role=$Role）—— 检查 .env 里的 COS_BUCKET_*" }
    if (-not $cfg.SecretId -or -not $cfg.SecretKey) { throw 'COS 密钥为空 —— 检查 .env 里的 COS_SECRET_ID / COS_SECRET_KEY' }
    $cfg.Host = "$($cfg.Bucket).cos.$region.myqcloud.com"
    return $cfg
}

# COS 签名 v5 → Authorization 头的值
function New-CosAuthorization {
    param([string]$Method, [string]$Path, [string]$HostName, [string]$SecretId, [string]$SecretKey)
    $now = [int][double]::Parse((Get-Date -Date (Get-Date).ToUniversalTime() -UFormat %s), [Globalization.CultureInfo]::InvariantCulture)
    $keyTime = "$($now - 60);$($now + 900)"
    $enc = [System.Text.Encoding]::UTF8

    $h1 = New-Object System.Security.Cryptography.HMACSHA1
    $h1.Key = $enc.GetBytes($SecretKey)
    $signKey = (($h1.ComputeHash($enc.GetBytes($keyTime)) | ForEach-Object { $_.ToString('x2') }) -join '')

    $httpString = "$($Method.ToLower())`n$Path`n`nhost=$HostName`n"
    $sha1 = [System.Security.Cryptography.SHA1]::Create()
    $sha1Hex = (($sha1.ComputeHash($enc.GetBytes($httpString)) | ForEach-Object { $_.ToString('x2') }) -join '')

    $stringToSign = "sha1`n$keyTime`n$sha1Hex`n"
    $h2 = New-Object System.Security.Cryptography.HMACSHA1
    $h2.Key = $enc.GetBytes($signKey)
    $sig = (($h2.ComputeHash($enc.GetBytes($stringToSign)) | ForEach-Object { $_.ToString('x2') }) -join '')

    return "q-sign-algorithm=sha1&q-ak=$SecretId&q-sign-time=$keyTime&q-key-time=$keyTime" +
    "&q-header-list=host&q-url-param-list=&q-signature=$sig"
}

# 统一的请求构造：签名用未编码 path，URI 用编码 path
function Send-CosRequest {
    param(
        [Parameter(Mandatory)][psobject]$Cfg,
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Key,
        [string]$ContentType = '',
        [byte[]]$Body = $null
    )
    $signPath = '/' + $Key
    $uri = 'https://' + $Cfg.Host + '/' + ($Key -replace ' ', '%20')
    $auth = New-CosAuthorization -Method $Method -Path $signPath -HostName $Cfg.Host `
        -SecretId $Cfg.SecretId -SecretKey $Cfg.SecretKey

    Add-Type -AssemblyName System.Net.Http
    $client = New-Object System.Net.Http.HttpClient
    $client.Timeout = [TimeSpan]::FromSeconds(60)
    $req = New-Object System.Net.Http.HttpRequestMessage((New-Object System.Net.Http.HttpMethod($Method.ToUpper())), $uri)
    # ★ 见文件头第 ② 条
    $null = $req.Headers.TryAddWithoutValidation('Authorization', $auth)
    if ($Body -ne $null) {
        $bc = New-Object System.Net.Http.ByteArrayContent(, $Body)
        if ($ContentType) { $bc.Headers.ContentType = New-Object System.Net.Http.Headers.MediaTypeHeaderValue($ContentType) }
        $req.Content = $bc
    }
    $resp = $client.SendAsync($req).Result
    return [pscustomobject]@{
        Status = [int]$resp.StatusCode
        Body   = $resp.Content.ReadAsByteArrayAsync().Result
        Header = $resp.Headers
    }
}

function Invoke-CosPut {
    param([psobject]$Cfg, [string]$Key, [byte[]]$Body, [string]$ContentType = 'application/octet-stream')
    return Send-CosRequest -Cfg $Cfg -Method 'put' -Key $Key -Body $Body -ContentType $ContentType
}

function Invoke-CosGet {
    param([psobject]$Cfg, [string]$Key)
    return Send-CosRequest -Cfg $Cfg -Method 'get' -Key $Key
}

function Invoke-CosHead {
    param([psobject]$Cfg, [string]$Key)
    return Send-CosRequest -Cfg $Cfg -Method 'head' -Key $Key
}

function Invoke-CosDelete {
    param([psobject]$Cfg, [string]$Key)
    return Send-CosRequest -Cfg $Cfg -Method 'delete' -Key $Key
}
