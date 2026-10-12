<#
.SYNOPSIS
    S4 配套 —— 把 `scripts/db-backup.ps1` 注册成每天固定时间的 Windows 计划任务。

.DESCRIPTION
    备份脚本本身只负责"跑一次并自证"；要真的做到"自动"，还需要有人按时叫它。
    本脚本就是那个"按时叫它"的注册器，做三件事：
      ① 用 `powershell -File scripts\db-backup.ps1 -BackupRoot <你指定的盘>` 包一条命令行；
      ② 注册计划任务（默认每天 03:00，刻意排在 03:30 的对账选主之前，别抢同一段时间）；
      ③ **立刻试跑一次**并把结果回读出来 —— 「任务注册成功」不等于「任务能跑成」，
         注册完不试跑，最常见的结局是任务每天静默失败 0x1 而没人知道。

    ★ 本脚本**自身不会**被自动执行：它只在你手动跑的时候改计划任务。
      想撤掉：`Unregister-ScheduledTask -TaskName DuoduoDbBackup -Confirm:$false`

.PARAMETER BackupRoot
    备份根目录。**必填**（本脚本不设默认值 —— 备份放哪个盘是老板要拍的事，
    写成默认值等于替他拍板）。

.PARAMETER Time
    每天执行时刻，默认 '03:00'。

.PARAMETER TaskName
    计划任务名，默认 DuoduoDbBackup。

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\db-backup-register.ps1 -BackupRoot 'E:\duoduo-backup'
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$BackupRoot,
    [string]$Time = '03:00',
    [string]$TaskName = 'DuoduoDbBackup'
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$repo = Split-Path -Parent $PSScriptRoot
$target = $repo + '\scripts\db-backup.ps1'
if (-not (Test-Path -LiteralPath $target)) { throw "找不到备份脚本：$target" }

# 备份根目录先探一下可写：不可写就别注册 —— 否则注册出来的任务每天失败，
# 而"注册成功"那一下看起来是绿的。
if (-not (Test-Path -LiteralPath $BackupRoot)) {
    New-Item -ItemType Directory -Path $BackupRoot -Force | Out-Null
}
$probe = $BackupRoot.TrimEnd('\', '/') + '\.write-probe'
[System.IO.File]::WriteAllText($probe, 'ok', (New-Object System.Text.UTF8Encoding $false))
Remove-Item -LiteralPath $probe -Force
Write-Host "[OK] 备份根目录可写：$BackupRoot" -ForegroundColor Green

$argLine = '-NoProfile -ExecutionPolicy Bypass -File "' + $target + '" -BackupRoot "' + $BackupRoot + '"'
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $argLine -WorkingDirectory $repo
$trigger = New-ScheduledTaskTrigger -Daily -At $Time
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -DontStopOnIdleEnd -ExecutionTimeLimit (New-TimeSpan -Hours 1)
# -StartWhenAvailable：机器当时没开机，开机后补跑（否则"昨天没备份"会无声无息）
# -ExecutionTimeLimit 1 小时：卡死别拖到第二天

if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
    Write-Host "[..] 已存在同名任务，先注销再重建" -ForegroundColor Yellow
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
}
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings `
    -Description '多多宠物商店 S4：每天 mysqldump 六库 + 抽验 + 滚动保留' | Out-Null
Write-Host "[OK] 已注册计划任务 $TaskName（每天 $Time）" -ForegroundColor Green

$info = Get-ScheduledTask -TaskName $TaskName | Get-ScheduledTaskInfo
Write-Host "[OK] 下次执行：$($info.NextRunTime)"

# ---------- 立刻试跑一次（注册成功 ≠ 跑得成）----------
Write-Host ''
Write-Host '立刻试跑一次（这步不能省：注册成功但跑不起来是最常见的失败形态）'
Start-ScheduledTask -TaskName $TaskName
$waited = 0
do {
    Start-Sleep -Seconds 5
    $waited += 5
    $info = Get-ScheduledTask -TaskName $TaskName | Get-ScheduledTaskInfo
} while ($info.LastTaskResult -eq 267009 -and $waited -lt 300)   # 267009 = 正在运行
$info = Get-ScheduledTask -TaskName $TaskName | Get-ScheduledTaskInfo

Write-Host ("试跑结果：LastTaskResult = {0}（0 = 成功），耗时 {1} 秒" -f $info.LastTaskResult, $waited)
if ($info.LastTaskResult -eq 0) {
    Write-Host "[OK] 计划任务可用 ✅" -ForegroundColor Green
    Write-Host "     最近一份备份：$BackupRoot\runs\ 下按时间命名的目录"
    exit 0
}
Write-Host "[FAIL] 试跑没成功（LastTaskResult=$($info.LastTaskResult)）—— 别就这么放着" -ForegroundColor Red
Write-Host '       先手动跑一遍看真实报错：'
Write-Host ('         powershell -NoProfile -ExecutionPolicy Bypass -File "' + $target + '" -BackupRoot "' + $BackupRoot + '"')
exit 1
