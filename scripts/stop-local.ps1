# 停止本地三进程
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\stop-local.ps1
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -like '*duoduo-*jar*' } |
    ForEach-Object {
        Write-Host ("停止 PID " + $_.ProcessId)
        Stop-Process -Id $_.ProcessId -Force
    }
Write-Host '已停止全部多多服务进程。'
