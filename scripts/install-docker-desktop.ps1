# Docker Desktop 一键安装脚本（需管理员权限，自带日志解析）
#
# 用法：Win + X → 选「终端(管理员)」或「Windows PowerShell(管理员)」，
#       确认提示符当前目录是 C:\Windows\system32，然后粘贴执行：
#   powershell -NoProfile -ExecutionPolicy Bypass -File "D:\智能体项目\duoduo-pet-mall-java\scripts\install-docker-desktop.ps1"
#
# ─────────────────────────────────────────────────────────────────────────────
# 背景：2026-09-15 从 MSI 详细日志（C:\dd-msi.log）定位到的真实失败位置
#
#   Action ended 17:19:16: ValidateInstallParameters. Return value 3.
#   SFXCA: Failed to create communication pipe for new CA process. Error code: 231
#   CustomAction ValidateInstallParameters returned actual error code 1603
#
#   错误码 231 = ERROR_PIPE_BUSY（命名管道全忙）。也就是说：
#   Docker 的安装前校验动作「根本还没开始执行」，它卡在「创建自定义动作通信管道」这一步。
#   相邻日志显示，同一个 msiexec 进程内两个自定义动作在 2 毫秒内并发启动：
#       MSI (s) (68:48)  Invoking remote custom action ... AnalyticsInstallStarted   ← 成功
#       MSI (s) (68:E4)  Invoking remote custom action ... ValidateInstallParameters ← 管道占用，失败
#
#   Windows Installer 的自定义动作服务启动本身存在并发竞态：后启动的那个动作在
#   CreateNamedPipe 时会拿到 ERROR_PIPE_BUSY（Windows 端与 Wine 实现都记载了这个行为）。
#   本机同时运行着两套国产安全软件（腾讯电脑管家 + 联想 PC Manager/火绒引擎），
#   它们会对每一个新派生进程做实时代码扫描，把自定义动作宿主进程的启动时间拉长，
#   于是原本偶发的竞态变成几乎必然发生。
#
# 本脚本做四件事：
#   ① 体检并打印证据（权限 / 系统功能 / 安全软件 / 残留 / 挂起重启）
#   ② 备份并清理上一次安装留下的用户级残留
#   ③ 带详细日志执行安装
#   ④ 自动解析新日志，直接给出「失败在哪个动作 + 下一步做什么」
# ─────────────────────────────────────────────────────────────────────────────

$ErrorActionPreference = 'Continue'

$stamp   = Get-Date -Format 'yyyyMMdd-HHmmss'
$logDir  = Join-Path $env:USERPROFILE ("docker-install-" + $stamp)
$msiLog  = Join-Path $logDir 'dd-msi.log'
$report  = Join-Path $logDir 'report.txt'
[System.IO.Directory]::CreateDirectory($logDir) | Out-Null
$enc = New-Object System.Text.UTF8Encoding($false)

function Say([string]$s) {
    Write-Host $s
    [System.IO.File]::AppendAllText($report, $s + "`r`n", $enc)
}

Say "=== Docker Desktop 安装 / 修复 ==="
Say ("时间：" + (Get-Date) + "    日志目录：" + $logDir)
Say ""

# ---------- 1. 权限检查 ----------
$ident = [System.Security.Principal.WindowsIdentity]::GetCurrent()
$princ = New-Object System.Security.Principal.WindowsPrincipal($ident)
$elevated = $princ.IsInRole([System.Security.Principal.WindowsBuiltInRole]::Administrator)
Say ("[1] 管理员权限 = " + $elevated)
if (-not $elevated) {
    Say ""
    Say "本脚本必须在【管理员】窗口里运行。请这样打开："
    Say "    Win + X  →  选「终端(管理员)」或「Windows PowerShell(管理员)」"
    Say "    确认提示符当前目录是 C:\Windows\system32（这就是已提权的标志）"
    Say "    然后重新粘贴本命令执行。"
    Say ""
    Say ("（体检报告已写到 " + $report + "）")
    exit 1
}

# ---------- 2. 环境体检 ----------
Say ""
Say "[2] 环境体检"

$os = Get-CimInstance Win32_OperatingSystem
Say ("    系统：" + $os.Caption + "  Build " + $os.BuildNumber)

foreach ($f in @('Microsoft-Windows-Subsystem-Linux', 'VirtualMachinePlatform')) {
    $feat = Get-CimInstance -ClassName Win32_OptionalFeature -Filter ("Name='" + $f + "'") -ErrorAction SilentlyContinue
    $stateText = '查询不到'
    if ($feat) {
        $code = [int]$feat.InstallState
        $stateText = '已启用'
        if ($code -eq 2) { $stateText = '已禁用' }
        elseif ($code -eq 3) { $stateText = '不存在' }
        elseif ($code -ne 1) { $stateText = "未知(" + $code + ")" }
    }
    Say ("    " + $f + " = " + $stateText)
}
$wslApp = 'C:\Program Files\WSL\wsl.exe'
Say ("    商店版 WSL = " + (Test-Path $wslApp) + ("  版本 " + $(if (Test-Path $wslApp) { (Get-Item $wslApp).VersionInfo.FileVersion } else { 'n/a' })))

$rebootPending = (Test-Path 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Component Based Servicing\RebootPending')
Say ("    挂起的重启 = " + $rebootPending)

Say "    已注册的安全软件："
try {
    $av = @(Get-CimInstance -Namespace 'root\SecurityCenter2' -ClassName AntiVirusProduct -ErrorAction Stop)
    if ($av.Count -eq 0) { Say "      无" }
    else { $av | ForEach-Object { Say ("      - " + $_.displayName) } }
} catch { Say ("      查询失败：" + $_.Exception.Message) }
try {
    $mp = Get-MpComputerStatus -ErrorAction Stop
    Say ("      Windows Defender 实时保护 = " + $mp.RealTimeProtectionEnabled + "  防篡改保护 = " + $mp.IsTamperProtected)
} catch { }

Say "    Docker 残留："
foreach ($p in @('C:\Program Files\Docker', 'C:\ProgramData\Docker', 'C:\ProgramData\DockerDesktop',
                 (Join-Path $env:APPDATA 'Docker'), (Join-Path $env:LOCALAPPDATA 'Docker'))) {
    Say ("      " + $(if (Test-Path $p) { '[有] ' } else { '[无] ' }) + $p)
}

$stale = @(Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.ProcessName -match 'msiexec|SfxCA|Docker' })
Say ("    遗留的安装相关进程：" + $stale.Count + " 个")
$stale | ForEach-Object { Say ("      - " + $_.Id + " " + $_.ProcessName) }

# ---------- 3. 挂起重启则不装 ----------
if ($rebootPending) {
    Say ""
    Say "[3] 存在挂起的重启 —— 请先重启电脑，再运行本脚本。"
    Say "    （挂起状态下 Windows 组件服务处于未收敛状态，安装容易失败）"
    exit 2
}
Say ""
Say "[3] 无挂起重启，继续"

# ---------- 4. 清理遗留进程与用户级残留 ----------
Say ""
Say "[4] 清理"
if ($stale.Count -gt 0) {
    $stale | Where-Object { $_.ProcessName -ne 'Docker Desktop' } | ForEach-Object {
        try { Stop-Process -Id $_.Id -Force -ErrorAction Stop; Say ("    已结束进程 " + $_.Id) } catch { Say ("    结束进程 " + $_.Id + " 失败：" + $_.Exception.Message) }
    }
    Start-Sleep -Seconds 2
} else {
    Say "    无遗留进程需要清理"
}

$roamDocker = Join-Path $env:APPDATA 'Docker'
if (Test-Path $roamDocker) {
    $bak = $roamDocker + '.bak-' + $stamp
    try {
        Move-Item -LiteralPath $roamDocker -Destination $bak -Force -ErrorAction Stop
        Say ("    已把上次的用户级配置备份为：" + $bak)
        Say "    （若安装成功且想保留旧设置，可手工把里面的 settings 文件搬回去）"
    } catch {
        Say ("    备份 " + $roamDocker + " 失败：" + $_.Exception.Message)
    }
} else {
    Say "    无用户级 Docker 残留"
}

# 清理上次遗留的自定义动作临时目录
$sfxca = @(Get-ChildItem 'C:\Windows\Installer' -Directory -Filter 'SFXCA*' -ErrorAction SilentlyContinue)
Say ("    C:\Windows\Installer 下遗留的 SFXCA 临时目录：" + $sfxca.Count + " 个")
$sfxca | ForEach-Object {
    try { [System.IO.Directory]::Delete($_.FullName, $true); Say ("      已清理 " + $_.Name) } catch { Say ("      清理 " + $_.Name + " 失败（可能被占用，忽略）") }
}

# ---------- 5. 安装 ----------
Say ""
Say ("[5] 开始安装（详细 MSI 日志：" + $msiLog + "）")
Say "    注意：这一步耗时较长，请勿关闭窗口。"
Say ""
Say "    ⚠️ 强烈建议先做这一步（做完再回车/继续）："
Say "       临时退出【腾讯电脑管家】与【联想电脑管家】，并关闭 Windows Defender 的实时保护。"
Say "       原因：它们会逐个扫描每个派生进程，把自定义动作宿主进程的启动时间拉长，"
Say "             从而让两个动作的管道创建撞车（就是这次 1603 的直接原因）。"
Say "       装完请务必重新打开它们。"
Say ""
Read-Host "    按回车开始安装"

$installed = $false
$exitCode = -1

if (Get-Command choco -ErrorAction SilentlyContinue) {
    $installArgs = "--install-arguments=/l*v " + $msiLog
    & choco install docker-desktop -y --no-progress $installArgs
    $exitCode = $LASTEXITCODE
    if ($exitCode -eq 0) { $installed = $true }
    Say ("    choco 退出码 = " + $exitCode)
} else {
    Say "    未找到 choco，改用 msiexec 直接安装"
}

if (-not $installed) {
    $cachedMsi = Join-Path $env:LOCALAPPDATA 'Temp\chocolatey\docker-desktop\4.90.0\docker-desktopInstall.MSI'
    if (Test-Path $cachedMsi) {
        Say ("    用已缓存的 MSI 再试一次：" + $cachedMsi)
        & msiexec.exe /i $cachedMsi /qn /norestart /l*v $msiLog REMOVEEXISTINGINSTALL=1
        $exitCode = $LASTEXITCODE
        Say ("    msiexec 退出码 = " + $exitCode)
        if ($exitCode -eq 0) { $installed = $true }
    } else {
        Say "    未找到缓存的 MSI，跳过直接安装兜底"
    }
}

# ---------- 6. 解析日志 ----------
Say ""
Say "[6] 结果"
if ($installed) {
    Say "    ✅ 安装成功。"
    Say "    接下来：从开始菜单启动 Docker Desktop，首次启动会初始化 WSL 发行版，"
    Say "    看到左下角状态变成 Running（绿色）即就绪。"
    Say "    验证：docker version"
} else {
    Say ("    ❌ 安装未成功（退出码 " + $exitCode + "），下面是日志里定位到的失败点：")
    Say ""
    if (Test-Path $msiLog) {
        $lines = [System.IO.File]::ReadAllLines($msiLog)
        $hit = -1
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i] -match 'Return value 3') { $hit = $i; break }
        }
        if ($hit -ge 0) {
            $from = [Math]::Max(0, $hit - 10)
            for ($i = $from; $i -le [Math]::Min($hit + 2, $lines.Count - 1); $i++) {
                Say ("      L" + ($i + 1) + ": " + ($lines[$i] -replace '[\x00-\x1F]', ''))
            }
        } else {
            Say "      日志里没有 'Return value 3'，说明失败发生在序列之外（可能是服务启动阶段）"
        }
        $pipeErr = @($lines | Where-Object { $_ -match 'Failed to create communication pipe' })
        Say ""
        if ($pipeErr.Count -gt 0) {
            Say "    → 仍是同一个原因：自定义动作通信管道创建失败（ERROR_PIPE_BUSY / 231）。"
            Say "      说明安全软件的影响还没排除干净。请确认："
            Say "      1) 腾讯电脑管家：主界面 → 病毒查杀/系统防护，退出程序（不是只关某个开关）"
            Say "      2) 联想电脑管家：关闭「病毒防护」或直接退出主进程"
            Say "      3) Windows Defender：Windows 安全中心 → 病毒和威胁防护 → 实时保护（临时关闭）"
            Say "         （若开了「篡改保护」，实时保护开关会变灰，需要先在「管理设置」里关掉篡改保护）"
            Say "      4) 全部退出后重启一次电脑，再运行本脚本"
            Say ("      5) 仍然失败则把 " + $msiLog + " 交给助手继续分析")
        } else {
            Say ("    → 失败点已变化（不再是管道问题），把 " + $msiLog + " 交给助手继续分析。")
        }
    } else {
        Say "    未生成 MSI 日志，可能安装命令本身没跑起来。"
    }
}

Say ""
Say ("完整报告：" + $report)
Say ("MSI 日志：" + $msiLog)
Say ""
Say "⚠️ 无论成功失败，都请记得重新打开刚才临时关闭的安全软件。"
