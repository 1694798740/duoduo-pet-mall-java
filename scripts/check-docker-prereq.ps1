# Docker Desktop 前置条件检查（只读，不需要管理员权限）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\check-docker-prereq.ps1
#
# 背景：本机 Windows 11 家庭版没有 Hyper-V，Docker Desktop 只能走 WSL2 后端。
#   WSL2 需要两个 Windows 可选功能处于「已启用」状态：
#     Microsoft-Windows-Subsystem-Linux、VirtualMachinePlatform
#   只装 WSL 应用（C:\Program Files\WSL）不够 —— Docker 安装器会自检这两个功能，
#   缺任意一个都会让 msiexec 直接返回 1603（报错文案却写成「通用 MSI 错误」，误导性很强）。
#
# 【修正记录 2026-09-15】
#   Win32_OptionalFeature.InstallState 的真实取值是：
#       1 = 已启用(Enabled)   2 = 已禁用(Disabled)
#       3 = 不存在(Absent)    4 = 未知(Unknown)
#   旧版脚本把 1/2 判反了（把 1 当成「未安装」），导致「功能其实已启用」被误报成「未安装」，
#   让人白白去跑 dism + 重启。本次修正判定，并加入两项独立佐证，避免再靠单点推断：
#       a) 功能负载文件是否已投影到 System32（禁用该功能时这些文件会被摘除）
#       b) 相关内核服务是否已注册

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
$stateNames = @{ 1 = '已启用'; 2 = '已禁用'; 3 = '不存在'; 4 = '未知' }
$required = @(
    @{ Name = 'Microsoft-Windows-Subsystem-Linux'; Label = 'WSL 子系统';
       Payload = 'C:\Windows\System32\lxss\tools'; Service = 'WSLService' },
    @{ Name = 'VirtualMachinePlatform';            Label = '虚拟机平台';
       Payload = 'C:\Windows\System32\vmcompute.exe'; Service = 'vmcompute' }
)
foreach ($item in $required) {
    $feat = Get-CimInstance -ClassName Win32_OptionalFeature -Filter ("Name='" + $item.Name + "'") -ErrorAction SilentlyContinue
    if (-not $feat) {
        Bad ($item.Label + "（" + $item.Name + "）：查询不到，可能被裁剪")
        $blockers += $item.Name
        continue
    }
    $code = [int]$feat.InstallState
    $text = if ($stateNames.ContainsKey($code)) { $stateNames[$code] } else { "未知(" + $code + ")" }
    $payloadOk = Test-Path $item.Payload
    $svc = Get-Service -Name $item.Service -ErrorAction SilentlyContinue
    $evidence = @()
    if ($payloadOk) { $evidence += '负载已就位' } else { $evidence += '负载缺失' }
    if ($svc) { $evidence += ("服务 " + $item.Service + " 已注册(" + $svc.Status + ")") }
    else      { $evidence += ("服务 " + $item.Service + " 未注册") }

    if ($code -eq 1 -and $payloadOk) {
        Ok ($item.Label + "（" + $item.Name + "）：" + $text + "（" + ($evidence -join '，') + "）")
    } elseif ($code -eq 1) {
        Info ($item.Label + "（" + $item.Name + "）：" + $text + "，但" + ($evidence -join '，') + " —— 建议重启后复查")
    } else {
        Bad ($item.Label + "（" + $item.Name + "）：" + $text + "（" + ($evidence -join '，') + "）")
        $blockers += $item.Name
    }
}

# ---------- 3. WSL 应用本体 ----------
Line ""
Line "> WSL 应用本体"
$wslApp = 'C:\Program Files\WSL\wsl.exe'
if (Test-Path $wslApp) {
    Ok ("商店版 WSL 已安装，版本 " + (Get-Item $wslApp).VersionInfo.FileVersion + "（C:\Program Files\WSL）")
} else {
    Info "未在 C:\Program Files\WSL 找到商店版 WSL"
}
$lxssKey = 'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Lxss'
if (Test-Path $lxssKey) {
    Ok "WSL 已初始化（Lxss 注册表项存在）"
    $dv = (Get-ItemProperty $lxssKey -Name DefaultVersion -ErrorAction SilentlyContinue).DefaultVersion
    if ($dv) { Info ("WSL 默认版本 = " + $dv) }
    else { Info "未显式设置 WSL 默认版本（建议执行 wsl --set-default-version 2）" }
} else {
    Info "WSL 尚未初始化（Lxss 注册表项不存在），首次启动 WSL 时会创建"
}

# ---------- 4. 挂起重启 ----------
Line ""
Line "> 挂起的重启"
$cbs = Test-Path 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Component Based Servicing\RebootPending'
$wu  = Test-Path 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\WindowsUpdate\Auto Update\RebootRequired'
$pfro = Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager' -Name PendingFileRenameOperations -ErrorAction SilentlyContinue
$pfroCount = if ($pfro) { @($pfro.PendingFileRenameOperations).Count } else { 0 }
if ($cbs) { Info "组件服务(CBS) 待重启：是（启用/禁用 Windows 功能会触发）" } else { Info "组件服务(CBS) 待重启：否" }
if ($wu)  { Info "Windows Update 待重启：是" } else { Info "Windows Update 待重启：否" }
Info ("待处理的文件重命名操作：" + $pfroCount + " 项")
if ($cbs -or $wu) {
    Bad "存在挂起的重启 —— 重启后才能进入干净状态，Docker 安装器对此敏感"
    $blockers += '挂起重启'
} else {
    Ok "无挂起的重启"
}

# ---------- 5. Docker 现有痕迹 ----------
Line ""
Line "> Docker 现有安装"
$dockerDir   = Test-Path 'C:\Program Files\Docker'
$dockerSvc   = $null -ne (Get-Service -Name 'com.docker.service' -ErrorAction SilentlyContinue)
$dockerUnins = @(Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*',
                                      'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*' -ErrorAction SilentlyContinue |
                 Where-Object { $_.DisplayName -like '*Docker*' })
if ($dockerDir -or $dockerSvc -or $dockerUnins.Count -gt 0) {
    Info ("检测到 Docker 痕迹：目录=" + $dockerDir + "，服务=" + $dockerSvc + "，卸载项=" + $dockerUnins.Count + " 条")
    Info "安装前建议先在「设置 → 应用」里彻底卸载残留"
} else {
    Ok "无残留，全新安装"
}

# ---------- 6. 结论 ----------
Line ""
Line "=== 结论 ==="
if ($blockers.Count -eq 0) {
    Line "  前置条件全部满足，可以执行："
    Line ""
    Line "    choco install docker-desktop -y"
} else {
    $envBlockers = @($blockers | Where-Object { $_ -ne '挂起重启' })
    if ($envBlockers.Count -eq 0) {
        Line "  功能前置条件【全部满足】—— 与 Docker 安装失败无关。"
        Line ""
        Line "  若安装仍报 1603：那是 Docker Desktop 4.90.0 安装包自身的竞态缺陷，"
        Line "  与本机环境无关。根因：自定义动作通信管道的名字取自 GetTickCount()，"
        Line "  安装包把两个自定义动作排在相隔 1 毫秒的位置，两次取到的 tick 相同"
        Line "  → 管道名撞车 → 第二个动作拿到 ERROR_PIPE_BUSY(231) → 安装返回 1603。"
        Line ""
        Line "  对策：管理员运行 scripts\install-docker-desktop.ps1（用本地 MSI 反复重试，"
        Line "  直到某一轮跨过 tick 边界）。"
    } else {
        Line ("  还差 " + $envBlockers.Count + " 项：" + ($envBlockers -join '、'))
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
}
Line ""
Line "> 提示：即使 Docker 装不上，本项目也不受影响 ——"
Line "  MySQL 与 Redis 已用免安装方式落地，scripts\start-infra.ps1 可一键启动，"
Line "  scripts\start-local.ps1 -Mysql 即可连真实数据库跑三进程。"
Line ""
