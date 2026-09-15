# Docker Desktop 前置条件检查（只读，不需要管理员权限）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\check-docker-prereq.ps1
#
# 背景：本机 Windows 11 家庭版没有 Hyper-V，Docker Desktop 只能走 WSL2 后端。
#   WSL2 需要两个 Windows 可选功能处于「已安装」状态：
#     Microsoft-Windows-Subsystem-Linux、VirtualMachinePlatform
#   只装 WSL 应用（C:\Program Files\WSL）不够 —— Docker 安装器会自检这两个功能，
#   缺任意一个都会让 msiexec 直接返回 1603（报错文案却写成「通用 MSI 错误」，误导性很强）。

$ErrorActionPreference = 'Continue'

function Line([string]$s) { Write-Host $s }
function Ok([string]$s)   { Write-Host ("  [通过] " + $s) -ForegroundColor Green }
function Bad([string]$s)  { Write-Host ("  [不满足] " + $s) -ForegroundColor Red }
function Info([string]$s) { Write-Host ("  [信息] " + $s) }

$blockers = @()

Line ""
Line "=== Docker Desktop 前置条件检查 ==="
Line ""

# ---------- 1. 操作系统 ----------
Line "> 操作系统"
$os = Get-CimInstance Win32_OperatingSystem
$build = [int]$os.BuildNumber
Info ("版本：" + $os.Caption + "（Build " + $build + "）")
if ($build -ge 22000) { Ok "Windows 11，满足 Docker Desktop 要求" }
elseif ($build -ge 19045) { Ok "Windows 10 22H2，满足要求" }
else { Bad "系统版本过低，Docker Desktop 要求 Win11 或 Win10 22H2+"; $blockers += '系统版本' }

# ---------- 2. 两个必需的 Windows 可选功能 ----------
Line ""
Line "> WSL2 依赖的 Windows 功能（关键项）"
$required = @{
    'Microsoft-Windows-Subsystem-Linux' = 'WSL 子系统'
    'VirtualMachinePlatform'            = '虚拟机平台'
}
foreach ($name in $required.Keys) {
    $feat = Get-CimInstance -ClassName Win32_OptionalFeature -Filter "Name='$name'" -ErrorAction SilentlyContinue
    if (-not $feat) {
        Bad ($required[$name] + "（" + $name + "）：查询不到，可能被裁剪")
        $blockers += $name
        continue
    }
    switch ([int]$feat.InstallState) {
        2 { Ok ($required[$name] + "（" + $name + "）：已安装") }
        default {
            Bad ($required[$name] + "（" + $name + "）：未安装")
            $blockers += $name
        }
    }
}

# ---------- 3. WSL 应用本体 ----------
Line ""
Line "> WSL 应用本体"
$wslApp = 'C:\Program Files\WSL\wsl.exe'
if (Test-Path $wslApp) {
    $v = (Get-Item $wslApp).VersionInfo.FileVersion
    Ok ("商店版 WSL 已安装，版本 " + $v + "（C:\Program Files\WSL）")
} else {
    Info "未在 C:\Program Files\WSL 找到商店版 WSL"
}
$lxss = Test-Path 'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Lxss'
if ($lxss) { Ok "WSL 已初始化（Lxss 注册表项存在）" } else { Info "WSL 尚未初始化（Lxss 注册表项不存在），首次启动 WSL 时会创建" }

# ---------- 4. 挂起重启 ----------
Line ""
Line "> 挂起的重启"
$reboot = (Test-Path 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Component Based Servicing\RebootPending') -or
          (Test-Path 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\WindowsUpdate\Auto Update\RebootRequired')
if ($reboot) {
    Bad "存在挂起的重启 —— 启用 Windows 功能后必须重启才能生效"
    $blockers += '挂起重启'
} else {
    Ok "无挂起的重启"
}

# ---------- 5. Docker 现有痕迹 ----------
Line ""
Line "> Docker 现有安装"
$hasDocker = (Test-Path 'C:\Program Files\Docker') -or ($null -ne (Get-Service -Name 'com.docker.service' -ErrorAction SilentlyContinue))
if ($hasDocker) { Info "检测到已有 Docker 安装痕迹（若安装失败可先卸载再装）" } else { Ok "无残留，全新安装" }

# ---------- 6. 结论 ----------
Line ""
Line "=== 结论 ==="
if ($blockers.Count -eq 0) {
    Line "  前置条件全部满足，可以执行："
    Line "    choco install docker-desktop -y"
} else {
    Line ("  还差 " + $blockers.Count + " 项：" + ($blockers -join '、'))
    Line ""
    Line "  请在【管理员】PowerShell（Win+X 选「终端(管理员)」，提示符应为 C:\Windows\system32）中执行："
    Line ""
    Line "    dism.exe /online /enable-feature /featurename:Microsoft-Windows-Subsystem-Linux /all /norestart"
    Line "    dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart"
    Line ""
    Line "  两条都应输出「操作成功完成」。若提示需要提升权限，说明窗口不是管理员。"
    Line "  然后【重启电脑】，重启后再执行："
    Line ""
    Line "    wsl --update"
    Line "    wsl --set-default-version 2"
    Line "    choco install docker-desktop -y"
}
Line ""
Line "> 提示：即使 Docker 装不上，本项目也不受影响 ——"
Line "  MySQL 与 Redis 已用免安装方式落地，scripts\start-infra.ps1 可一键启动，"
Line "  scripts\start-local.ps1 -Mysql 即可连真实数据库跑三进程。"
Line ""
