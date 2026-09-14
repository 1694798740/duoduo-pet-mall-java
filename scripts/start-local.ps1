# 一键启动本地三进程（网关 + 用户服务 + 商品服务），全部使用 local profile（H2，无需 MySQL / Nacos）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-local.ps1
param(
    [string]$GatewayPort = '8080',
    [string]$UserPort = '8081',
    [string]$ProductPort = '8082'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$java = 'D:\jdk\jdk21\bin\java.exe'
if (-not (Test-Path $java)) { $java = 'java' }

function Start-One([string]$name, [string]$jar, [string]$port, [string]$logName) {
    $jarPath = Join-Path $root $jar
    if (-not (Test-Path $jarPath)) {
        Write-Host "[跳过] $name 未构建：$jarPath（先执行 mvn -gs maven/settings.xml -B -DskipTests package）"
        return
    }
    $logPath = Join-Path $root $logName
    $args = @('-Dfile.encoding=UTF-8', '-jar', $jarPath, '--spring.profiles.active=local', "--server.port=$port")
    Start-Process -FilePath $java -ArgumentList $args -WorkingDirectory $root `
        -RedirectStandardOutput $logPath -RedirectStandardError "$logPath.err" -WindowStyle Hidden
    Write-Host "[启动] $name  ->  http://127.0.0.1:$port   (日志：$logName)"
}

Start-One '用户服务' 'duoduo-user\target\duoduo-user-1.0.0.jar'       $UserPort    'user-service.log'
Start-One '商品服务' 'duoduo-product\target\duoduo-product-1.0.0.jar' $ProductPort 'product-service.log'
Start-One 'API 网关' 'duoduo-gateway\target\duoduo-gateway-1.0.0.jar' $GatewayPort 'gateway.log'

Write-Host ''
Write-Host '等待就绪（最多 60 秒）…'
$ok = $false
for ($i = 0; $i -lt 60; $i++) {
    try {
        $r = Invoke-WebRequest -Uri "http://127.0.0.1:$GatewayPort/actuator/health" -UseBasicParsing -TimeoutSec 3
        if ($r.StatusCode -eq 200) { $ok = $true; break }
    } catch { Start-Sleep -Seconds 1 }
}
if ($ok) {
    Write-Host "网关已就绪：http://127.0.0.1:$GatewayPort"
    Write-Host ''
    Write-Host '下一步可执行端到端冒烟：'
    Write-Host '  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke.ps1'
} else {
    Write-Host '网关未在 60 秒内就绪，请检查 gateway.log'
    exit 1
}
