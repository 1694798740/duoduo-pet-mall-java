# 启动本地基础设施（MySQL 8 + Redis），无需 Docker、无需管理员权限
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-infra.ps1
param(
    [string]$MysqlHome = 'D:\duoduo-infra\mysql-8.0.29-winx64',
    [string]$MysqlData = 'D:\duoduo-infra\mysql-data',
    [int]$MysqlPort = 3306,
    [string]$RedisHome = 'D:\devsoft\redis',
    [int]$RedisPort = 6379
)

$ErrorActionPreference = 'Stop'

function Test-Port([string]$Host_, [int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try { $client.Connect($Host_, $Port); $client.Close(); return $true } catch { return $false }
}

# ---------- MySQL ----------
if (Test-Port '127.0.0.1' $MysqlPort) {
    Write-Host "[已运行] MySQL  ($MysqlPort)"
} else {
    $mysqld = Join-Path $MysqlHome 'bin\mysqld.exe'
    if (-not (Test-Path $mysqld)) {
        Write-Host "[缺失] 未找到 mysqld：$mysqld"
        Write-Host "        重新安装：解压 mysql-8.0.29-winx64.zip 到 $MysqlHome，再执行："
        Write-Host "        mysqld --initialize-insecure --basedir=$MysqlHome --datadir=$MysqlData"
    } else {
        $log = 'D:\duoduo-infra\mysql.log'
        Start-Process -FilePath $mysqld `
            -ArgumentList @(
                "--basedir=$MysqlHome",
                "--datadir=$MysqlData",
                "--port=$MysqlPort",
                '--console',
                '--character-set-server=utf8mb4',
                '--collation-server=utf8mb4_general_ci',
                '--default-time-zone=+00:00',
                '--max-connections=300'
            ) -WindowStyle Hidden -RedirectStandardOutput $log -RedirectStandardError "$log.err"
        Write-Host "[启动] MySQL  ->  127.0.0.1:$MysqlPort   (日志：$log)"
    }
}

# ---------- Redis ----------
if (Test-Port '127.0.0.1' $RedisPort) {
    Write-Host "[已运行] Redis  ($RedisPort)"
} else {
    $redis = Join-Path $RedisHome 'redis-server.exe'
    if (-not (Test-Path $redis)) {
        Write-Host "[缺失] 未找到 redis-server.exe：$redis"
    } else {
        Start-Process -FilePath $redis `
            -ArgumentList @('--port', "$RedisPort", '--bind', '127.0.0.1', '--maxmemory', '512mb') `
            -WorkingDirectory $RedisHome -WindowStyle Hidden `
            -RedirectStandardOutput 'D:\duoduo-infra\redis.log' -RedirectStandardError 'D:\duoduo-infra\redis.log.err'
        Write-Host "[启动] Redis  ->  127.0.0.1:$RedisPort"
    }
}

Write-Host ''
Write-Host '等待就绪（最多 40 秒）…'
$mysqlOk = $false
$redisOk = $false
for ($i = 0; $i -lt 40; $i++) {
    if (-not $mysqlOk) { $mysqlOk = Test-Port '127.0.0.1' $MysqlPort }
    if (-not $redisOk) { $redisOk = Test-Port '127.0.0.1' $RedisPort }
    if ($mysqlOk -and $redisOk) { break }
    Start-Sleep -Seconds 1
}
Write-Host ("MySQL 就绪 = {0} ；Redis 就绪 = {1}" -f $mysqlOk, $redisOk)
if (-not $mysqlOk) { Write-Host 'MySQL 未就绪，请查看 D:\duoduo-infra\mysql.log'; exit 1 }
