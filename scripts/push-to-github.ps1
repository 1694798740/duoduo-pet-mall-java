<#
.SYNOPSIS
    把 duoduo-pet-mall-java 本地仓库推送到 GitHub，并校验结果。

.DESCRIPTION
    为什么需要 --force：
      远端 main 上现有的提交，是早期通过 GitHub Git Data API 逐次生成的
      （文件内容取自本地工作区，但提交对象由 API 重建），
      所以本地 14 个提交与远端那批提交是两套「互不相干的历史」——
      内容一致、对象哈希不同，普通的 git push 会被判定为非快进而拒绝。

    安全闸门：
      push 之前脚本会先把远端取下来（git fetch），逐个比较文件路径。
      只有当「远端不存在任何本地没有的文件」时才继续推送，
      以避免误覆盖别人后来推上去的东西。若发现远端独有文件，
      脚本会列出并停止（除非显式加 -Force）。

    凭据怎么来的：
      使用系统已配置的 Git Credential Manager（D:\devsoft\Git 自带）。
      首次运行会弹出浏览器要求登录 GitHub，登录一次后
      凭据存入 Windows 凭据管理器，之后不再询问。

.PARAMETER RepoRoot
    仓库根目录。默认取脚本所在目录的上一级。

.PARAMETER RemoteUrl
    远端地址。默认 https://github.com/1694798740/duoduo-pet-mall-java.git

.PARAMETER PreflightOnly
    只做本地检查（分支 / 工作区 / remote / 提交数），不联网。

.PARAMETER Force
    即使远端存在本地没有的文件，也照样推送（有覆盖风险，慎用）。

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\push-to-github.ps1
#>
[CmdletBinding()]
param(
    [string]$RepoRoot,
    [string]$RemoteUrl = 'https://github.com/1694798740/duoduo-pet-mall-java.git',
    [switch]$PreflightOnly,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

function Say([string]$t) { Write-Host $t }
function Rule { Write-Host ('-' * 70) }

if (-not $RepoRoot) { $RepoRoot = Split-Path -Parent $PSScriptRoot }
if (-not $RepoRoot -or -not (Test-Path (Join-Path $RepoRoot '.git'))) {
    Say "[x] 不是 git 仓库：$RepoRoot"
    Say "    用 -RepoRoot 指定仓库根目录，例如："
    Say "    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\push-to-github.ps1 -RepoRoot D:\智能体项目\duoduo-pet-mall-java"
    exit 1
}
Set-Location $RepoRoot

Rule
Say "推送 GitHub · duoduo-pet-mall-java"
Say "仓库：$RepoRoot"
Rule

# ---------- 1. 本地检查 ----------
Say "[1] 本地检查"

$branch = ((& git rev-parse --abbrev-ref HEAD 2>&1) -join '').Trim()
if ($branch -ne 'main') {
    Say "    [!] 当前分支是 $branch，不是 main"
    Say "        远端默认分支是 main，继续会推错位置。切回 main 再运行："
    Say "        git checkout main"
    exit 1
}
Say "    分支 = main"

$headSha   = ((& git rev-parse HEAD 2>&1) -join '').Trim()
$headShort = ((& git rev-parse --short HEAD 2>&1) -join '').Trim()
$localCount = ((& git rev-list --count HEAD 2>&1) -join '').Trim()
Say "    本地 HEAD = $headShort（共 $localCount 个提交）"

$tracked = @(& git ls-files 2>&1)
Say "    纳入版本控制的文件 = $($tracked.Count)"

$dirty = @(& git status --porcelain 2>&1)
if ($dirty.Count -eq 0) {
    Say "    工作区 = 干净"
} else {
    Say "    [!] 工作区有 $($dirty.Count) 处未提交改动（不影响推送，但不会出现在远端）"
    $dirty | Select-Object -First 5 | ForEach-Object { Say "        $_" }
}

# 敏感文件自检：确保 .env 之类的不会被推上去
$sensitive = @(& git ls-files 2>&1 | Where-Object { $_ -match '(^|/)\.env$|\.pem$|id_rsa|settings-security' })
if ($sensitive.Count -gt 0) {
    Say "    [x] 检测到敏感文件已被纳入版本控制，已中止："
    $sensitive | ForEach-Object { Say "        $_" }
    exit 1
}
Say "    密钥自检 = 通过（无 .env / 私钥入库）"

# ---------- 2. remote 配置 ----------
Say ""
Say "[2] 远端配置"

$remotes = @(& git remote 2>&1 | ForEach-Object { "$_".Trim() } | Where-Object { $_ })
if ($remotes -contains 'origin') {
    $curUrl = ((& git remote get-url origin 2>&1) -join '').Trim()
    if ($curUrl -ne $RemoteUrl) {
        & git remote set-url origin $RemoteUrl 2>&1 | Out-Null
        Say "    origin 已改指向 $RemoteUrl"
    } else {
        Say "    origin = $RemoteUrl（已存在，无需改动）"
    }
} else {
    & git remote add origin $RemoteUrl 2>&1 | Out-Null
    Say "    origin 已添加 = $RemoteUrl"
}

# 本地 git 配置里不该出现明文令牌
$leaked = @(& git config --local --list 2>&1 | Where-Object { $_ -match 'ghp_|github_pat_' })
if ($leaked.Count -gt 0) {
    Say "    [x] 本地 .git/config 中发现明文令牌，已中止。请先执行："
    Say "        git remote set-url origin $RemoteUrl"
    exit 1
}
Say "    令牌残留自检 = 通过"

if ($PreflightOnly) {
    Say ""
    Rule
    Say "预检完成（-PreflightOnly，未联网、未推送）"
    Rule
    exit 0
}

# ---------- 3. 取远端并做覆盖安全闸门 ----------
Say ""
Say "[3] 读取远端"
Say "    首次运行会弹出 GitHub 登录窗口 —— 请用 1694798740 账号登录授权。"
Say "    登录一次后凭据会存入 Windows 凭据管理器，之后不再询问。"

$fetchOut = & git fetch --no-tags origin '+refs/heads/main:refs/remotes/origin/main' 2>&1
$fetchCode = $LASTEXITCODE
if ($fetchCode -ne 0) {
    Say "    [x] 读取远端失败（退出码 $fetchCode）："
    $fetchOut | Select-Object -First 15 | ForEach-Object { Say "        $_" }
    Say ""
    Say "    常见原因："
    Say "      · 弹出的登录窗口被关掉或超过时限 → 重新运行本脚本"
    Say "      · 代理拦住了 git 的 HTTPS 隧道 → 检查 HTTPS_PROXY 环境变量"
    Say "      · 远端仓库被改名 / 删除 → 到 GitHub 网页确认仓库还在"
    exit 1
}
$fetchOut | ForEach-Object { Say "    $_" }

$remoteRef = 'refs/remotes/origin/main'
$remoteExists = $true
$probe = & git rev-parse --verify --quiet $remoteRef 2>&1
if ($LASTEXITCODE -ne 0 -or -not $probe) { $remoteExists = $false }

if ($remoteExists) {
    $remoteCount = ((& git rev-list --count $remoteRef 2>&1) -join '').Trim()
    $remoteShort = ((& git rev-parse --short $remoteRef 2>&1) -join '').Trim()
    Say "    远端 main = $remoteShort（$remoteCount 个提交）"
} else {
    Say "    远端 main 目前是空的"
}

Say ""
Say "[4] 覆盖安全闸门"

if ($remoteExists) {
    $remoteFiles = @(& git ls-tree -r --name-only $remoteRef 2>&1 | Where-Object { "$_" })
    $localFiles  = @(& git ls-tree -r --name-only main 2>&1 | Where-Object { "$_" })
    Say "    远端文件 $($remoteFiles.Count) 个 / 本地文件 $($localFiles.Count) 个"

    $set = New-Object 'System.Collections.Generic.HashSet[string]'
    foreach ($f in $localFiles) { [void]$set.Add($f) }
    $remoteOnly = @($remoteFiles | Where-Object { -not $set.Contains($_) })

    if ($remoteOnly.Count -eq 0) {
        Say "    远端所有文件本地都有 = 是"
        Say "    结论：远端内容是本地的子集，覆盖不会丢掉任何文件，可以安全 --force"
    } else {
        Say "    [x] 远端存在 $($remoteOnly.Count) 个本地没有的文件："
        $remoteOnly | Select-Object -First 20 | ForEach-Object { Say "        $_" }
        if ($remoteOnly.Count -gt 20) { Say "        ...（其余 $($remoteOnly.Count - 20) 个省略）" }
        if (-not $Force) {
            Say ""
            Say "    已停止，未做任何推送。"
            Say "    这些文件说明远端有本地没有的内容（可能是别人推的，或历史遗留）。"
            Say "    确认可以丢弃后，加 -Force 重跑："
            Say "      powershell -NoProfile -ExecutionPolicy Bypass -File scripts\push-to-github.ps1 -Force"
            exit 2
        }
        Say "    [!] 收到 -Force，将继续覆盖"
    }
} else {
    Say "    远端为空，无覆盖风险，跳过闸门"
}

# ---------- 5. 推送 ----------
Say ""
Say "[5] 推送（--force 覆盖远端 main 的分支指针）"
$pushOut = & git push --force origin "main`:refs/heads/main" 2>&1
$pushCode = $LASTEXITCODE
$pushOut | ForEach-Object { Say "    $_" }

if ($pushCode -ne 0) {
    Say ""
    Say "    [x] 推送失败（退出码 $pushCode）。把上面的原始输出交给助手即可继续定位。"
    exit 1
}

# ---------- 6. 校验 ----------
Say ""
Say "[6] 校验"
$verify = ((& git ls-remote origin refs/heads/main 2>&1) | Select-Object -First 1)
if (-not $verify) {
    Say "    [!] 无法读取远端引用，请到 GitHub 网页确认"
    exit 1
}
$verifySha = ("$verify" -split "\s+")[0]
if ($verifySha -eq $headSha) {
    Say "    远端 main 现在 = $($verifySha.Substring(0,7))"
    Say "    与本地 HEAD 一致 = 是"
} else {
    Say "    [!] 远端 = $($verifySha.Substring(0,7))，本地 = $headShort，不一致"
    Say "        可能推送未完全生效，请重跑一次脚本"
    exit 1
}

Rule
Say "成功：本地 $localCount 个提交已全部同步到远端。"
Say ""
Say "仓库地址：$RemoteUrl"
Say "远端：https://github.com/1694798740/duoduo-pet-mall-java"
Say ""
Say "凭据已存入 Windows 凭据管理器，后续推送不再需要登录。"
Say "撤销凭据：控制面板 → 凭据管理器 → Windows 凭据 → 删除 git:https://github.com"
Rule
exit 0
