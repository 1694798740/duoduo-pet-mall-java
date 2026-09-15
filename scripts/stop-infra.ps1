# 停止本地基础设施（MySQL / Redis）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\stop-infra.ps1
Write-Host '停止 MySQL…'
Get-Process -Name mysqld -ErrorAction SilentlyContinue | ForEach-Object {
    Write-Host ("  停止 PID " + $_.Id)
    Stop-Process -Id $_.Id -Force
}

Write-Host '停止 Redis…'
Get-Process -Name redis-server -ErrorAction SilentlyContinue | ForEach-Object {
    Write-Host ("  停止 PID " + $_.Id)
    Stop-Process -Id $_.Id -Force
}

Write-Host '基础设施已停止。'
