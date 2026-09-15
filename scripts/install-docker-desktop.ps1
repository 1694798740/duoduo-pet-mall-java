# Docker Desktop 安装脚本（需管理员权限）
# 针对 1603 的已知「tick 碰撞」竞态做多次重试，并从本地 MSI 安装（不依赖网络）
#
# 用法：Win + X → 「终端(管理员)」，确认提示符当前目录是 C:\Windows\system32，然后：
#   powershell -NoProfile -ExecutionPolicy Bypass -File "D:\智能体项目\duoduo-pet-mall-java\scripts\install-docker-desktop.ps1"
#
# 可选参数：
#   -MaxAttempts 60      最多重试几次（默认 60）
#   -MsiPath "X:\a.msi"  指定本地 MSI；不指定则自动在常见位置里找
#
# ─────────────────────────── 为什么要重试（根因，源码级）───────────────────────────
# 报错出处：WiX DTF 的 SfxCA.cpp → InvokeOutOfProcManagedCustomAction()
#
#     wchar_t szSessionName[100] = {0};
#     swprintf_s(szSessionName, 100, L"SfxCA_%d", ::GetTickCount());   // ← 管道名取自系统 tick
#     RemoteMsiSession remote(szSessionName, true);
#     DWORD ret = remote.Connect();
#     if (ret != 0) {
#         Log(hSession, L"Failed to create communication pipe for new CA process. Error code: %d", ret);
#         return ERROR_INSTALL_FAILURE;
#     }
#
# 也就是说：自定义动作（CA）之间通信用命名管道，而**管道名 = "SfxCA_" + GetTickCount()**，
# 一个毫秒级的系统 tick。GetTickCount() 的更新粒度约 15.6 ms —— 并不是每毫秒都变。
#
# 而 Docker Desktop 的安装包把两个自定义动作排在紧邻位置，实测两者只隔 1 毫秒：
#     MSI (s) (A8:D4) [17:51:35:552]: Invoking remote custom action ... AnalyticsInstallStarted
#     MSI (s) (A8:08) [17:51:35:553]: Invoking remote custom action ... ValidateInstallParameters
# 两次 GetTickCount() 落在同一个 tick → 管道名完全相同 → 第二个动作 CreateNamedPipe 拿到
# 231（ERROR_PIPE_BUSY：该管道名的实例已被占满）→ 动作启动失败 → 整个安装返回 1603。
#
# 结论：这是一次**按 tick 边界碰撞的竞态**，与本机环境无关。已被实测排除的因素：
#   权限（已是管理员）、WSL2 两个功能（均已启用）、挂起重启（已重启过）、磁盘空间、
#   旧版本残留、以及**杀毒软件**（关闭实时保护后现象完全一致；且本机并没有国产安全
#   软件的内核驱动 sysdiag / TesSafe 等）。
#
# 既然是竞态，**每次尝试都是独立事件**：两次取 tick 恰好跨过 tick 边界的概率约为
#     (两次间隔 1 ms) / (tick 粒度 ≈ 15.6 ms) ≈ 6%
# 因此重试 N 次的成功概率约为 1 − 0.94^N：40 次 ≈ 92%，60 次 ≈ 98%。
# 这就是本脚本的核心动作 —— 反复重试，直到那一次「撞对 tick」。
# ──────────────────────────────────────────────────────────────────────────────────

param(
    [int]$MaxAttempts = 60,
    [string]$MsiPath = ''
)

$ErrorActionPreference = 'Continue'

$stamp  = Get-Date -Format 'yyyyMMdd-HHmmss'
$logDir = Join-Path $env:USERPROFILE ("docker-install-" + $stamp)
[System.IO.Directory]::CreateDirectory($logDir) | Out-Null
$msiLog = Join-Path $logDir 'dd-msi.log'
$report = Join-Path $logDir 'report.txt'
$enc = New-Object System.Text.UTF8Encoding($false)

function Say([string]$s) {
    Write-Host $s
    [System.IO.File]::AppendAllText($report, $s + "`r`n", $enc)
}

Say "=== Docker Desktop 安装（带 tick 竞态重试）==="
Say ("时间：" + (Get-Date))
Say ("日志目录：" + $logDir)
Say ("最多尝试：" + $MaxAttempts + " 次")
Say ""

# ---------- 1. 权限 ----------
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
    Say ("体检报告已写到 " + $report)
    exit 1
}

# ---------- 2. 找本地 MSI（不走网络） ----------
Say ""
Say "[2] 定位本地安装包（本环境从 desktop.docker.com 下载会 TLS 失败，所以只用本地文件）"
$candidates = @()
if ($MsiPath -ne '') { $candidates += $MsiPath }
$candidates += @(Get-ChildItem (Join-Path $env:LOCALAPPDATA 'Temp\chocolatey\docker-desktop') -Recurse -Filter '*.MSI' -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)
$candidates += @(Get-ChildItem 'D:\duoduo-infra' -Filter 'DockerDesktop*.msi' -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)

$msi = ''
foreach ($c in $candidates) {
    if ($c -and (Test-Path $c)) {
        $sz = [math]::Round((Get-Item $c).Length / 1MB, 1)
        Say ("    候选：" + $c + "  (" + $sz + " MB)")
        if ($msi -eq '' -and $sz -gt 100) { $msi = $c }
    }
}
if ($msi -eq '') {
    Say ""
    Say "    ✗ 没找到可用的本地 MSI（> 100 MB）。"
    Say "      请用 -MsiPath 指定安装包路径，例如："
    Say '      powershell -NoProfile -ExecutionPolicy Bypass -File "...\install-docker-desktop.ps1" -MsiPath "C:\path\DockerDesktop.msi"'
    exit 3
}
Say ("    使用：" + $msi)

# ---------- 3. 清理上次残留 ----------
Say ""
Say "[3] 清理上次残留"
$roamDocker = Join-Path $env:APPDATA 'Docker'
if (Test-Path $roamDocker) {
    $bak = $roamDocker + '.bak-' + $stamp
    try {
        Move-Item -LiteralPath $roamDocker -Destination $bak -Force -ErrorAction Stop
        Say ("    已备份用户级配置 → " + $bak)
    } catch {
        Say ("    备份失败（不影响继续）：" + $_.Exception.Message)
    }
} else {
    Say "    无用户级残留"
}
$sfxca = @(Get-ChildItem 'C:\Windows\Installer' -Directory -Filter 'SFXCA*' -ErrorAction SilentlyContinue)
Say ("    C:\Windows\Installer 下遗留 SFXCA 临时目录：" + $sfxca.Count + " 个")
$sfxca | ForEach-Object {
    try { [System.IO.Directory]::Delete($_.FullName, $true) } catch { }
}
Say "    已尝试清理（被占用的会跳过，不影响继续）"

# ---------- 4. 重试安装 ----------
Say ""
Say "[4] 开始安装（每次失败都会自动重试；ctrl+C 可中断）"
Say "    说明：失败是安装包自身的 tick 碰撞竞态，跟本机环境无关。"
Say ""

$ok = $false
$exitCode = -1
$attempts = 0
$startAll = Get-Date

for ($i = 1; $i -le $MaxAttempts; $i++) {
    $attempts = $i
    $t0 = Get-Date

    if ($i -eq 1) {
        $argLine = '/i "' + $msi + '" /qn /norestart /l*v "' + $msiLog + '" REMOVEEXISTINGINSTALL=1'
    } else {
        # 第 2 次起用 + 追加写同一个日志，避免留下几十份日志
        $argLine = '/i "' + $msi + '" /qn /norestart /l*v+ "' + $msiLog + '" REMOVEEXISTINGINSTALL=1'
    }

    $p = Start-Process -FilePath 'msiexec.exe' -ArgumentList $argLine -Wait -PassThru
    $exitCode = $p.ExitCode
    $secs = [math]::Round(((Get-Date) - $t0).TotalSeconds, 1)

    if ($exitCode -eq 0 -or $exitCode -eq 3010) {
        $ok = $true
        Say ("    [第 " + $i + "/" + $MaxAttempts + " 次] 成功（退出码 " + $exitCode + "，" + $secs + " 秒）")
        break
    }
    if ($exitCode -eq 1602) {
        Say ("    [第 " + $i + "/" + $MaxAttempts + " 次] 用户取消了安装，停止。")
        break
    }

    $elapsed = [math]::Round(((Get-Date) - $startAll).TotalSeconds, 0)
    Say ("    [第 " + $i + "/" + $MaxAttempts + " 次] 退出码 " + $exitCode + "（本次 " + $secs + " 秒，累计 " + $elapsed + " 秒）")

    if ($i -lt $MaxAttempts) { Start-Sleep -Milliseconds 800 }
}

# ---------- 5. 结果 ----------
Say ""
Say "[5] 结果"
if ($ok) {
    $exe = 'C:\Program Files\Docker\Docker\Docker Desktop.exe'
    Say ("    安装成功（共尝试 " + $attempts + " 次）")
    Say ("    安装目录存在 = " + (Test-Path 'C:\Program Files\Docker'))
    Say ("    主程序存在 = " + (Test-Path $exe))
    Say ""
    Say "    接下来：从开始菜单启动 Docker Desktop，首次启动会初始化 WSL 发行版；"
    Say "    托盘图标变为 Running 即就绪。验证命令：docker version"
    Say ""
    Say ("    之前备份的用户级配置在：" + (Join-Path $env:APPDATA ('Docker.bak-' + $stamp)))
} else {
    Say ("    " + $attempts + " 次尝试后仍未成功（最后一次退出码 " + $exitCode + "）")
    Say ""
    if (Test-Path $msiLog) {
        $lines = [System.IO.File]::ReadAllLines($msiLog)
        $pipeHit = @($lines | Where-Object { $_ -match 'Failed to create communication pipe' })
        $rv3 = -1
        for ($k = 0; $k -lt $lines.Count; $k++) { if ($lines[$k] -match 'Return value 3') { $rv3 = $k; break } }
        if ($rv3 -ge 0) {
            Say "    最后一次的失败点："
            for ($k = [Math]::Max(0, $rv3 - 3); $k -le [Math]::Min($rv3 + 1, $lines.Count - 1); $k++) {
                Say ("      " + ($lines[$k] -replace '[\x00-\x1F]', ''))
            }
        }
        Say ""
        if ($pipeHit.Count -gt 0) {
            Say "    → 仍是同一条 tick 碰撞（Error code: 231），说明这次运气没撞上，"
            Say "      并不是出现了新问题。可以："
            Say "      1) 直接再跑一次本脚本（每次都是独立概率，换个时间点 tick 不同）"
            Say "      2) 把重试次数调大，例如 150 次："
            Say "         ...install-docker-desktop.ps1 -MaxAttempts 150"
            Say "      3) 换一个 Docker Desktop 版本的安装包，用 -MsiPath 指过来"
            Say "      4) 接受「本项目不需要 Docker」，走免安装中间件（MySQL + Redis 已跑通）"
        } else {
            Say "    → 失败点已变化（不再是管道竞态），把这个日志交给助手继续分析："
            Say ("      " + $msiLog)
        }
    } else {
        Say "    未生成 MSI 日志，说明安装命令本身没跑起来。"
    }
}

Say ""
Say ("完整报告：" + $report)
Say ("MSI 日志：" + $msiLog)
