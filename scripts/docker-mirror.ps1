<#
.SYNOPSIS
    一条命令给 Docker Desktop 配置国内镜像源（解决「拉不到镜像」）。

.DESCRIPTION
    为什么需要：Docker 守护进程**默认直连 registry-1.docker.io**，在国内网络常被拦
    （现象：`failed to resolve source metadata for docker.io/library/xxx`，报
    `Docker Desktop has no HTTPS proxy ... direct connection` 超时），
    而同一台机器 `curl` 访问 GitHub / npm 却是 200 —— **不是没网，是 Docker 直连被挡**。
    注意：容器里看到的 `http.docker.internal:3128` 是**容器侧**代理，与**镜像拉取**无关。

    本脚本做四件事：
      ① 备份现有 `~/.docker/daemon.json`（带时间戳，可回滚）
      ② 合并写入 `registry-mirrors`（保留原有其它配置，不覆盖）
      ③ 重启 Docker Desktop 使其生效（可用 -NoRestart 跳过）
      ④ 用**之前失败的那个镜像**做验证（默认 `node:22-alpine`）—— 不用已在本地的镜像"验证"（会假通过）

.PARAMETER Mirrors
    镜像源地址列表。默认 `https://docker.1panel.live`（本机实测唯一可达）。
    可传多个，Docker 会按顺序尝试：-Mirrors 'https://a','https://b'

.PARAMETER VerifyImage
    用于验证的镜像，默认 `node:22-alpine`（S1 首次构建时失败的正是它）。

.PARAMETER NoRestart
    只改配置、不重启 Docker Desktop（下次手动重启后生效）。

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\docker-mirror.ps1

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\docker-mirror.ps1 -Mirrors 'https://docker.1panel.live'
#>
[CmdletBinding()]
param(
    [string[]]$Mirrors = @('https://docker.1panel.live'),
    [string]$VerifyImage = 'node:22-alpine',
    [switch]$NoRestart
)

$ErrorActionPreference = 'Stop'
function Say($m) { Write-Host $m }
function Ok($m)  { Write-Host "  [OK] $m" -ForegroundColor Green }
function Bad($m) { Write-Host "  [!!] $m" -ForegroundColor Red }

$dockerDir = Join-Path $env:USERPROFILE '.docker'
$daemon    = Join-Path $dockerDir 'daemon.json'
$stamp     = Get-Date -Format 'yyyyMMdd-HHmmss'

Say '----------------------------------------------------------------------'
Say 'Docker Desktop 镜像源配置'
Say '----------------------------------------------------------------------'

# ---------- ① 备份 ----------
Say "[1/4] 备份现有配置"
if (Test-Path $daemon) {
    $backup = "$daemon.bak-$stamp"
    Copy-Item $daemon $backup -Force
    Ok "已备份 → $backup"
} else {
    New-Item -ItemType Directory -Force -Path $dockerDir | Out-Null
    Ok "无既有 daemon.json（将新建）"
}

# ---------- ② 合并写入 registry-mirrors ----------
Say "[2/4] 写入 registry-mirrors"
$cfg = @{}
if (Test-Path $daemon) {
    $raw = Get-Content $daemon -Raw -Encoding UTF8
    if ($raw.Trim().Length -gt 0) {
        $cfg = $raw | ConvertFrom-Json -AsHashtable
    }
}
if ($null -eq $cfg) { $cfg = @{} }
$cfg['registry-mirrors'] = $Mirrors
# ConvertTo-Json 默认深度 2 会截断嵌套结构（本文件里有 builder.gc），必须给足深度
$json = $cfg | ConvertTo-Json -Depth 10
# 用 UTF8 无 BOM 写（daemon.json 必须是纯 JSON；PS 5.1 的 Out-File -Encoding UTF8 会带 BOM，Docker 解析会失败）
[System.IO.File]::WriteAllText($daemon, $json, (New-Object System.Text.UTF8Encoding($false)))
Ok "registry-mirrors = $($Mirrors -join ', ')"
Say "     配置文件：$daemon"

# ---------- ③ 重启 Docker Desktop ----------
Say "[3/4] 重启 Docker Desktop（容器会随之重启，配了 restart 策略的会自动回来）"
if ($NoRestart) {
    Say '     -NoRestart：跳过。请手动重启 Docker Desktop 后生效。'
} elseif (Get-Command docker -ErrorAction SilentlyContinue) {
    & docker desktop restart 2>$null | Out-Null
    Say '     等待引擎恢复…'
    $ok = $false
    for ($i = 0; $i -lt 60; $i++) {
        & docker info *> $null
        if ($LASTEXITCODE -eq 0) { $ok = $true; break }
        Start-Sleep -Seconds 5
    }
    if ($ok) { Ok 'Docker 引擎已恢复' } else { Bad '引擎未在 5 分钟内恢复，请手动查看 Docker Desktop' }
} else {
    Bad '未找到 docker 命令，请手动重启 Docker Desktop'
}

Say '[4/4] 验证（用之前失败过的镜像，避免"已在本地的镜像"造成的假通过）'
& docker info 2>$null | Select-String -Pattern 'Registry Mirrors' -Context 0,3

$before = (& docker images --format '{{.Repository}}:{{.Tag}}') -contains $VerifyImage
if ($before) {
    Say "     注意：$VerifyImage 已在本机，先删掉再拉，才能真正验证通道"
    & docker rmi $VerifyImage *> $null
}
& docker pull $VerifyImage
if ($LASTEXITCODE -eq 0) {
    Ok "拉取成功：$VerifyImage（镜像源已生效）"
} else {
    Bad "拉取失败：$VerifyImage"
    Say '     排查建议：① docker info 看 Registry Mirrors 是否已列出；② 换一个镜像源再试（-Mirrors）；'
    Say '               ③ 若公司网络需代理，改配 Docker Desktop → Settings → Resources → Proxies（需重启）'
    exit 1
}
