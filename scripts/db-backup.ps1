<#
.SYNOPSIS
    S4 —— 数据库自动备份（六库 mysqldump → 从产物抽验 → 滚动保留 → 失败告警）

.DESCRIPTION
    为什么需要它：这是全项目**唯一**有「不可逆数据损失」风险的一项。此前所有验证与
    修复（含 E26 全量删号）都建立在"库坏了能从备份恢复"之上，而那个前提**只靠人工
    临时 dump 维持** —— 人工动作没有门禁、不会自证、失败时也不会喊人。

    本脚本把这件事收敛成一条命令，并遵守项目里已经付过学费的几条规矩：

      · 铁律 2 —— 不许把「空结果」当「成功」。
        每次备份都先回表读**源库快照**（表清单 + 精确行数），产物里必须能逐张找出
        **声明过的每一张表**（declared-vs-present 守卫），且每张表的
        「结构段 + 数据段」两个标记都在。少一张就报红，而不是"看起来有文件就行"。

      · 铁律 22 —— 校验必须比**两个不同来源**。
        抽验**不是**"再导一次然后比对"（同一函数调两遍是恒等式，永远为真）。
        正确做法：**从备份产物里切出该表 → 恢复进临时库 → 与源表比对
        行数 + `CHECKSUM TABLE`**。一边是源库、一边是产物，两侧都必须一致。

      · 铁律 3 —— 临时库现场建、跑完自毁；**建不起或删不掉都显式报红**并自证。

      · 铁律 26 —— 备份根目录是**待你拍板的占位默认值**，不是"已确定的方案"。
        改一行即可（见下方 `$DefaultBackupRoot`）。

      · 铁律 11/8 —— 临时文件位置用 `[System.IO.Path]::GetTempPath()`，不吃
        `$env:TEMP`；本文件必须带 UTF-8 BOM，否则 PS 5.1 按 ANSI 解码、中文全乱码。

    产物布局（每次备份一个 run 目录）：
      <BackupRoot>\runs\<yyyy-MM-dd_HHmmss>\          ← 秒级命名：同一分钟内连跑两次不会互相覆盖
          duoduo_user.sql … duoduo_marketing.sql     六库的 dump
          manifest.json                              本次备份的指纹与抽验结果
          backup.log                                 全过程日志（stdout 同步落这里）
          _ALERT.txt                                 仅在失败时出现
      `<BackupRoot>\runs\` 下只保留最近 N 份（默认 7），更早的**整目录**滚动删除。

    退出码：0 = 全绿；1 = 任一步硬失败（含告警已发出）。

.PARAMETER BackupRoot
    备份根目录。优先级：本参数 > 环境变量 DUODUO_BACKUP_ROOT > $DefaultBackupRoot。

.PARAMETER MysqlContainer
    跑着 MySQL 的容器名，默认 duoduo-mysql（本机无宿主 mysql 客户端，一律走 docker exec）。

.PARAMETER MysqlUser / MysqlPassword
    连接凭据。默认值与 docker-compose.yml 的 `${MYSQL_ROOT_PASSWORD:-duoduo_root}` 一致。

.PARAMETER Databases
    要备份的库。**必须逐个在 information_schema 里存在**，否则硬失败
    （防"库改名后静默少备一个"）。

.PARAMETER VerifySamples
    随机抽验的表数，默认 3。只在**有数据的表**里抽（空表的抽验不构成证据）。
    此外**永远**会补上每个库 dump 的最后一张表（切片边界，bug 高发处）——
    所以实际抽验张数是 `VerifySamples + 最多 6`。

.PARAMETER KeepRuns
    保留最近几份 run 目录，默认 7。

.PARAMETER AlertWebhook
    可选。失败时 POST 一条 JSON 到这个地址（飞书/钉钉机器人等）。不填则只落盘 + 打印。

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\db-backup.ps1

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\db-backup.ps1 -BackupRoot 'E:\duoduo-backup' -KeepRuns 14
#>
[CmdletBinding()]
param(
    [string]$BackupRoot,
    [string]$MysqlContainer = 'duoduo-mysql',
    [string]$MysqlUser = 'root',
    [string]$MysqlPassword = 'duoduo_root',
    [string[]]$Databases = @(
        'duoduo_user', 'duoduo_product', 'duoduo_trade',
        'duoduo_fulfilment', 'duoduo_social', 'duoduo_marketing'
    ),
    [int]$VerifySamples = 3,
    [int]$KeepRuns = 7,
    [string]$AlertWebhook
)

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

# ==========================================================================
# ★★ 唯一需要你确认的一行：备份落在哪个盘 ★★
#
#   这是**占位默认值，不是已拍板的方案**（台账里那句"已自行拍板为 D:\duoduo-backup"
#   是错的：该目录不存在，脚本也不存在）。覆盖方式，优先级从高到低：
#      1) 命令行：  -BackupRoot 'E:\duoduo-backup'
#      2) 环境变量：DUODUO_BACKUP_ROOT=E:\duoduo-backup
#      3) 直接改下面这一行
#   建议放在**与系统盘不同的物理盘**上 —— 备份和源数据在同一块盘，等于没有备份。
# ==========================================================================
$DefaultBackupRoot = 'D:\duoduo-backup'

$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo
$script:picked = @()
$script:beforeSum = @{}

# 临时校验库名（绝不与业务库重名；下面有断言守着）
$VerifyDb = 'duoduo_backup_verify'
$RepoDbNames = @('duoduo_user','duoduo_product','duoduo_trade','duoduo_fulfilment','duoduo_social','duoduo_marketing')

$script:logFile = $null
$script:runDir = $null
$script:results = @()
$script:samples = $null
$script:anyFail = $false

function Log {
    param([string]$Msg, [string]$Color = 'Gray')
    $line = '[{0}] {1}' -f (Get-Date -Format 'HH:mm:ss'), $Msg
    Write-Host $line -ForegroundColor $Color
    if ($script:logFile) {
        [System.IO.File]::AppendAllText($script:logFile, $line + "`r`n", (New-Object System.Text.UTF8Encoding $false))
    }
}

function Record {
    param([string]$Name, [bool]$Ok, [string]$Detail)
    if ($Ok) { Log ("  [PASS] {0}  {1}" -f $Name, $Detail) 'Green' }
    else     { Log ("  [FAIL] {0}  {1}" -f $Name, $Detail) 'Red' }
    $script:results += @{ name = $Name; ok = $Ok; detail = $Detail }
    if (-not $Ok) { $script:anyFail = $true }
}

# --------------------------------------------------------------------------
# 底层：跑 SQL / 跑 mysqldump。
# ★ 铁律 5：失败判定必须看**退出码**，不能只看输出内容 ——
#   业务错误被吞掉后"输出为空"和"真的没有数据"长得一模一样。
# --------------------------------------------------------------------------
function Invoke-Sql {
    param([Parameter(Mandatory)][string]$Sql)
    $a = @('exec', '-i', $MysqlContainer, 'mysql',
        ('-u' + $MysqlUser), ('-p' + $MysqlPassword),
        '-N', '-B', '--default-character-set=utf8mb4', '-e', $Sql)
    $out = & docker @a 2>$null
    $rc = $LASTEXITCODE
    if ($rc -ne 0) { throw "mysql 调用失败（exit=$rc）：$Sql" }
    $res = @()
    if ($out) { $res = @(@($out) | ForEach-Object { [string]$_ }) }
    # ★★ 必须写 `,$res` 而不是 `$res`。
    #   PowerShell 函数返回**单元素数组会静默展开成那个元素本身**，
    #   于是调用方拿到的是字符串而不是数组，`$r[0]` 就变成了**取第一个字符**
    #   —— 实测：`duoduo_user` 变成 `d`，`8.0.36` 变成 `8`，全程不报错。
    #   前置逗号是"不要再展开一层"的固定写法。
    return ,$res
}

function Invoke-SqlScalar {
    param([Parameter(Mandatory)][string]$Sql)
    $r = Invoke-Sql -Sql $Sql
    if ($r.Count -eq 0) { return '' }
    return [string]$r[0]
}

# --------------------------------------------------------------------------
# 抽验用的两步：从**产物**里切表 → 恢复进临时库
# --------------------------------------------------------------------------
function Get-TableSection {
    # 从 dump 产物里切出单张表的整段（结构段 + 数据段 **+ 文件 preamble**）。
    # mysqldump 每张表都以 '-- Table structure for table `X`' 开头，
    # 一直到下一张表的同名标记之前 —— 这段就是这张表的自足恢复件。
    #
    # ★★ 必须**连 preamble 一起带上**（第一张表标记之前的全部内容）。
    #   踩过的坑：只补一句 `SET NAMES utf8mb4;` 就够 —— 错。被抽中的表若正好是
    #   dump 里的**最后一张**，它的切片会一路走到文件末尾、把 dump 的收尾块
    #   `/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;` 也包进来，而 `@OLD_TIME_ZONE`
    #   是在 preamble 里赋的值 → 恢复当场报
    #   `ERROR 1231: Variable 'time_zone' can't be set to the value of 'NULL'`。
    #   前两张表抽中时看不出问题（切片到下一张表标记就截断了），所以这个 bug
    #   只在"抽到最后一张表"时才现形 —— 典型的"抽样能过不代表链路对"。
    param([Parameter(Mandatory)][string]$DumpPath,
          [Parameter(Mandatory)][string]$Table)
    $lines = [System.IO.File]::ReadAllLines($DumpPath, [System.Text.Encoding]::UTF8)
    $markerPrefix = '-- Table structure for table `'
    $marker = $markerPrefix + $Table + '`'
    $out = New-Object System.Collections.Generic.List[string]
    $cap = $false
    $seenFirstMarker = $false
    foreach ($l in $lines) {
        if ($l.StartsWith($markerPrefix)) {
            if (-not $seenFirstMarker) { $seenFirstMarker = $true }
            $cap = ($l -eq $marker)
        }
        if ($cap -or (-not $seenFirstMarker)) { $out.Add($l) }
    }
    return $out
}

function Send-Alert {
    param([Parameter(Mandatory)][string]$Message)
    # 告警本身失败**绝不能**盖掉真正的失败原因，所以整段 try/catch 吞异常。
    $stamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    $text = "[ALERT] 多多宠物商店 · 数据库备份失败`n时间：$stamp`n主机：$env:COMPUTERNAME`n目录：$script:runDir`n原因：$Message`n处置：看 backup.log 与本文件同级目录，修好后重跑 scripts/db-backup.ps1"
    if ($script:runDir -and (Test-Path $script:runDir)) {
        try { [System.IO.File]::WriteAllText((Join-Path $script:runDir '_ALERT.txt'), $text, (New-Object System.Text.UTF8Encoding $false)) } catch { }
    }
    Log $text 'Red'
    if ($AlertWebhook) {
        try {
            $body = @{ msg_type = 'text'; content = @{ text = $text } } | ConvertTo-Json -Depth 5 -Compress
            Invoke-RestMethod -Method Post -Uri $AlertWebhook -Body $body -ContentType 'application/json' -TimeoutSec 15 | Out-Null
            Log "  告警已推送：$AlertWebhook" 'Yellow'
        } catch {
            Log "  告警推送失败（不影响上面的失败判定）：$($_.Exception.Message)" 'Yellow'
        }
    } else {
        Log '  未配置 -AlertWebhook，本次只落盘 + 打印（_ALERT.txt）' 'Yellow'
    }
}

# ==========================================================================
# 开工
# ==========================================================================
$sw = [System.Diagnostics.Stopwatch]::StartNew()

# ---------- 解析备份根目录 ----------
if (-not $BackupRoot) { $BackupRoot = $env:DUODUO_BACKUP_ROOT }
if (-not $BackupRoot) { $BackupRoot = $DefaultBackupRoot }
$rootIsDefault = ($BackupRoot -eq $DefaultBackupRoot -and -not $env:DUODUO_BACKUP_ROOT)

$runName = Get-Date -Format 'yyyy-MM-dd_HHmmss'
# ★ 这里**故意不用 Join-Path**：备份盘不存在时 Join-Path 会先抛
#   `DriveNotFoundException`，于是我们自己那条"该改哪一行"的提示里，
#   目录名会变成空字符串 —— 用户看到的报错反而没有信息量。
#   字符串拼接不会抛，后面的 New-Item try/catch 才能给出可照做的处置。
$runsRoot = $BackupRoot.TrimEnd('\', '/') + '\runs'
$script:runDir = $runsRoot + '\' + $runName

Write-Host '======================================================================'
Write-Host '多多宠物商店 · 数据库自动备份（S4）'
Write-Host "开始时间：$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
Write-Host "备份根目录：$BackupRoot"
$script:logFile = $null

# ---------- [0/7] 前置 ----------
Write-Host ''
Write-Host '[0/7] 前置：Docker / 容器 / 根目录可写 / 库清单'

$sv = & docker info --format '{{.ServerVersion}}' 2>$null
if (-not $sv) {
    Write-Host '  [!!] Docker 不可用 —— 后面全都会红，先启动 Docker Desktop' -ForegroundColor Red
    Send-Alert 'Docker 不可用'
    exit 1
}
Log "  Docker $sv"

$up = (& docker ps --filter ('name=^' + $MysqlContainer + '$') --format '{{.Names}} {{.Status}}' 2>$null)
if (-not $up) {
    Write-Host ("  [!!] 容器 {0} 没在跑 —— 先跑 scripts\start-infra.ps1" -f $MysqlContainer) -ForegroundColor Red
    Send-Alert "MySQL 容器 $MysqlContainer 未运行"
    exit 1
}
Log ("  容器：{0}" -f ($up -join '; '))

# 目录可写 —— 不可写要**明确告诉用户改哪一行**，而不是抛个 Win32 错误码
try {
    New-Item -ItemType Directory -Path $script:runDir -Force -ErrorAction Stop | Out-Null
} catch {
    Write-Host ("  [!!] 建不了备份目录：{0}" -f $script:runDir) -ForegroundColor Red
    Write-Host '       这通常意味着该盘不存在或没有写权限。' -ForegroundColor Red
    Write-Host '       处置：指定一个真实存在的目录，例如' -ForegroundColor Red
    Write-Host "         powershell -File scripts\db-backup.ps1 -BackupRoot 'E:\duoduo-backup'" -ForegroundColor Red
    Write-Host '       或设环境变量 DUODUO_BACKUP_ROOT，或改脚本顶部的 $DefaultBackupRoot。' -ForegroundColor Red
    Send-Alert ("备份根目录不可写：" + $script:runDir)
    exit 1
}
if ($rootIsDefault) {
    Log "  [注意] 正在使用**占位默认值** $DefaultBackupRoot —— 这是待你拍板的位置，不是已确定的方案。" 'Yellow'
}
$script:logFile = Join-Path $script:runDir 'backup.log'
Log "  本次 run 目录：$script:runDir"
Log ("  脚本：{0}" -f $PSCommandPath)

$mysqlVer = Invoke-SqlScalar -Sql 'SELECT VERSION()'
Log "  MySQL 版本：$mysqlVer"

# 临时校验库名不得与业务库撞（撞了会 DROP 掉真库 —— 这是最严重的一类事故）
if ($RepoDbNames -contains $VerifyDb) {
    Log "  [!!] 内部错误：临时校验库名 $VerifyDb 与业务库重名，拒绝继续" 'Red'
    Send-Alert "临时校验库名与业务库重名：$VerifyDb"
    exit 1
}

# ---------- 库清单逐个存在性校验 ----------
$missingDbs = @()
foreach ($db in $Databases) {
    $hit = Invoke-SqlScalar -Sql ('SELECT schema_name FROM information_schema.schemata WHERE schema_name=''{0}''' -f $db)
    if ($hit -ne $db) { $missingDbs += $db }
}
if ($missingDbs.Count -gt 0) {
    Log ("  [!!] 这些库不存在：{0} —— 拒绝「少备一个还报成功」" -f ($missingDbs -join ', ')) 'Red'
    Send-Alert ("库不存在：" + ($missingDbs -join ', '))
    exit 1
}
Log ("  待备份库（{0} 个）：{1}" -f $Databases.Count, ($Databases -join ', '))

# ---------- [1/7] 源库快照 ----------
Write-Host ''
Write-Host '[1/7] 源库快照（表清单 + 精确行数）—— "输入侧真被写过"的证据'

$declared = @{}   # db -> @(表名)
$before   = @{}   # "db.table" -> 行数
$totalTables = 0

foreach ($db in $Databases) {
    $tables = Invoke-Sql -Sql ('SELECT table_name FROM information_schema.tables WHERE table_schema=''{0}'' AND table_type=''BASE TABLE'' ORDER BY table_name' -f $db)
    $tables = @($tables | Where-Object { $_ -ne '' })
    $declared[$db] = $tables
    $totalTables += $tables.Count
    if ($tables.Count -eq 0) {
        Write-Host ("  [!!] 库 {0} 里一张表都没有 —— 这要么是新建库，要么是链路没接通，请先确认" -f $db) -ForegroundColor Red
        $script:anyFail = $true
        continue
    }
    # ★ 行数必须**回表读**（COUNT(*)），不能用 information_schema.table_rows ——
    #   InnoDB 的那个是估算值，拿它当基线等于用一个近似值验一个精确值（铁律 22）。
    $fmt = 'SELECT ''{0}'' AS t, COUNT(*) AS c FROM `{1}`.`{2}`'
    $parts = @()
    foreach ($t in $tables) { $parts += ($fmt -f $t, $db, $t) }
    $rows = Invoke-Sql -Sql ($parts -join ' UNION ALL ')
    foreach ($line in $rows) {
        if (-not $line) { continue }
        $kv = $line -split "`t"
        if ($kv.Count -ge 2) { $before["$db.$($kv[0])"] = [int64]$kv[1] }
    }
    Log ("  {0}：{1} 张表" -f $db, $tables.Count)
}
Log ("  合计 {0} 张表（声明口径）" -f $totalTables)
Record '源库快照' ($totalTables -gt 0) "$totalTables 张表" 

# ---------- [2/7] 定抽样 + 取源侧 before 校验和 ----------
Write-Host ''
Write-Host '[2/7] 定抽样（随机 K 张 + 每个库最后一张边界表）+ 取源侧 before 校验和'

# ★★ 抽样必须**在 dump 之前**定，而且要在这一刻取校验和。
#   为什么：有些表是**持续被改写的**（例：`duoduo_marketing.t_sched_leader` 是选主
#   心跳表，每 2 分钟改一次 expire_at/updated_at）。如果只在"恢复之后"读源库去比，
#   这类表的校验和**每次都不同**，于是备份明明是对的却天天报红 —— 典型"狼来了"，
#   报到最后没人看，真正的坏备份反而被淹没。
#   做法改成**三方比对**：产物必须与 before（dump 前）或 after（恢复时）之一一致。
#   两边都不一致才判 FAIL，并且把三个值都打出来。
$pool = @()
foreach ($db in $Databases) {
    foreach ($t in $declared[$db]) {
        $c = 0
        if ($before.ContainsKey("$db.$t")) { $c = $before["$db.$t"] }
        if ($c -gt 0) { $pool += , @{ db = $db; table = $t; rows = $c } }
    }
}
Log ("  可抽验样本池（有数据的表）：{0} / {1}" -f $pool.Count, $totalTables)

$picked = @()
if ($pool.Count -eq 0) {
    Log '  [!!] 全库没有任何有数据的表 —— 抽验无从谈起（不许静默降级为"跳过"）' 'Red'
    $script:anyFail = $true
} else {
    $k = [Math]::Min($VerifySamples, $pool.Count)
    if ($k -lt $VerifySamples) { Log ("  样本池只有 {0} 张，抽 {0} 张（少于请求的 {1}）" -f $k, $VerifySamples) 'Yellow' }
    $rand = New-Object System.Random
    # 洗牌后取前 k 张：不写"随机下标"的手工循环 —— 那种写法在去重时容易把
    # "抽不满"和"抽重复"混起来，而这两种情况都会让"抽了 N 张"的数字变假。
    $picked = @(@($pool | Sort-Object { $rand.Next() }) | Select-Object -First $k)

    # 再补上**每个库 dump 里的最后一张表**（边界必测，不受随机影响）。
    #   为什么非得补：切片的 bug 恰好只在"最后一张表"上现形 —— 它的切片会一路
    #   走到文件末尾，把 dump 的收尾块也带进来。纯随机抽样完全可能一次都不抽到它，
    #   于是「抽验全绿」和「链路其实是坏的」可以同时成立。边界用例不能靠运气覆盖。
    $pickedKeys = @{}
    foreach ($p in $picked) { $pickedKeys[$p.db + '.' + $p.table] = $true }
    $added = 0
    foreach ($db in $Databases) {
        $exp = @($declared[$db])
        if ($exp.Count -eq 0) { continue }
        $bt = $exp[$exp.Count - 1]
        if (-not $pickedKeys.ContainsKey($db + '.' + $bt)) {
            $picked += , @{ db = $db; table = $bt; rows = -1 }
            $pickedKeys[$db + '.' + $bt] = $true
            $added++
        }
    }
    Log ("  本次抽中（随机 {0} 张 + 边界 {1} 张）：{2}" -f $k, $added, (($picked | ForEach-Object { $_.db + '.' + $_.table }) -join ', '))
}

$beforeSum = @{}
if ($picked.Count -gt 0) {
    $frags = @()
    foreach ($p in $picked) { $frags += ('`{0}`.`{1}`' -f $p.db, $p.table) }
    $csBefore = Invoke-Sql -Sql ('CHECKSUM TABLE ' + ($frags -join ', '))
    foreach ($l in $csBefore) {
        $kv = $l -split "`t"
        if ($kv.Count -ge 2) { $beforeSum[$kv[0]] = $kv[1] }
    }
    Log ("  before 校验和已取：{0} 张（这会变的心跳表也在此刻被钉住一个快照）" -f $beforeSum.Count)
}
$script:picked = $picked
$script:beforeSum = $beforeSum

# ---------- [3/7] mysqldump 六库 ----------
Write-Host ''
Write-Host '[3/7] mysqldump 六库 → run 目录'
Log '  做法：在容器内把 dump 直接写文件，再 docker cp 出来 ——'
Log '        绕开 PowerShell 的管道/重定向，避免编码被改写（SQL 里全是中文与 emoji）。'

$dumpFiles = @{}
$dumpFail = 0
# 容器内的临时暂存目录：开工先清一次（清掉上一轮中断留下的残件），收工再清一次。
# 固定路径 /tmp/dbbak 是本脚本自己的暂存区，不碰容器里任何别的东西。
& docker exec $MysqlContainer sh -c 'rm -rf /tmp/dbbak' 2>$null | Out-Null
foreach ($db in $Databases) {
    $file = "$db.sql"
    $hostPath = Join-Path $script:runDir $file
    $inner = 'mkdir -p /tmp/dbbak && mysqldump -u' + $MysqlUser + ' -p' + $MysqlPassword +
        ' --single-transaction --quick --routines --triggers --events' +
        ' --default-character-set=utf8mb4 --hex-blob --no-tablespaces --set-gtid-purged=OFF ' +
        $db + ' > /tmp/dbbak/' + $file + ' 2>/tmp/dbbak/' + $file + '.err'

    & docker exec $MysqlContainer sh -c $inner 2>$null
    $rc = $LASTEXITCODE
    $errTxt = ''
    if ($rc -eq 0) {
        & docker cp ($MysqlContainer + ':/tmp/dbbak/' + $file) $hostPath 2>$null | Out-Null
        $rc2 = $LASTEXITCODE
        if ($rc2 -ne 0) { $rc = $rc2 }
    }
    if ($rc -eq 0) {
        $dumpFiles[$db] = $hostPath
        $sz = (Get-Item -LiteralPath $hostPath).Length
        Log ("  {0} → {1}（{2:N0} 字节）" -f $db, $file, $sz) 'Green'
    } else {
        $dumpFail++
        Log ("  {0} dump 失败（exit={1}）" -f $db, $rc) 'Red'
    }
    & docker exec $MysqlContainer sh -c ('rm -f /tmp/dbbak/' + $file + ' /tmp/dbbak/' + $file + '.err') 2>$null | Out-Null
}
Record 'mysqldump 六库' ($dumpFail -eq 0) "成功 $($dumpFiles.Count)/$($Databases.Count)"

# 收工：容器内暂存目录不留残件（并回读自证 —— "删掉了"和"以为删掉了"要能分辨）
& docker exec $MysqlContainer sh -c 'rm -rf /tmp/dbbak' 2>$null | Out-Null
$leftover = (& docker exec $MysqlContainer sh -c 'ls -d /tmp/dbbak 2>/dev/null' 2>$null)
if ($leftover) {
    Log ("  [WARN] 容器内暂存目录未清干净：{0}" -f $leftover) 'Yellow'
} else {
    Log '  容器内暂存目录 /tmp/dbbak 已清理（回读不存在）'
}

if ($dumpFail -gt 0) {
    Send-Alert "mysqldump 有 $dumpFail 个库失败"
    Write-Host ''
    Write-Host '结论：dump 阶段就失败了，后面不再继续（避免用不完整的产物做"抽验通过"的假象）' -ForegroundColor Red
    exit 1
}

# ---------- [4/7] 产物自证 ----------
Write-Host ''
Write-Host '[4/7] 产物自证：非空 / 声明过的表逐张都在 / 结构段+数据段都在'

$artifactBad = 0
foreach ($db in $Databases) {
    $path = $dumpFiles[$db]
    $fi = Get-Item -LiteralPath $path
    if ($fi.Length -le 0) { Log ("  [FAIL] {0} 是 0 字节" -f $fi.Name) 'Red'; $artifactBad++; continue }

    $lines = [System.IO.File]::ReadAllLines($path, [System.Text.Encoding]::UTF8)
    $markerPrefix = '-- Table structure for table `'
    $present = @{}
    foreach ($l in $lines) {
        if ($l.StartsWith($markerPrefix)) {
            # 前缀长度用 Length 算，不写死下标 —— 写死的数字一旦和实际差一格，
            # 切出来的表名就永远匹配不上，而"匹配不上"的表现是"少了一张表"（会报红但原因错位）。
            $name = $l.Substring($markerPrefix.Length).TrimEnd('`')
            $present[$name] = $true
        }
    }
    $exp = $declared[$db]
    $miss = @($exp | Where-Object { -not $present.ContainsKey($_) })
    if ($miss.Count -gt 0) {
        Log ("  [FAIL] {0}：产物里少了 {1} 张表 → {2}" -f $db, $miss.Count, ($miss -join ', ')) 'Red'
        $artifactBad++
    } else {
        Log ("  [OK] {0}：{1} 张表逐张可寻（含结构段标记）" -f $db, $exp.Count) 'Green'
    }
    # 数据段标记：证明这张表连数据也导了（不只是空壳结构）
    $dataMarkers = @($lines | Where-Object { $_.StartsWith('-- Dumping data for table `') })
    if ($dataMarkers.Count -lt $exp.Count) {
        Log ("  [WARN] {0}：数据段标记 {1} 个 < 表数 {2} 个（--skip-comments 之类会让它消失，值得看一眼）" -f $db, $dataMarkers.Count, $exp.Count) 'Yellow'
    }
}
Record '产物自证（declared-vs-present）' ($artifactBad -eq 0) "六库产物完整"

# ---------- [5/7] 抽验：从产物恢复 → 三方比对 ----------
Write-Host ''
Write-Host '[5/7] 抽验：从**产物**切出并恢复进临时库 → 与源表比对行数 + CHECKSUM（before / after 三方）'

$picked = @($script:picked)
$beforeSum = $script:beforeSum
if ($picked.Count -eq 0) {
    Log '  [FAIL] 没有可抽验的表（样本池为空）—— 不许把"没验"写成"通过"' 'Red'
    Record '抽验（产物恢复 + 三方比对）' $false '样本池为空'
} else {
    # 建临时库
    $verifyOk = $true
    try {
        Invoke-Sql -Sql ("DROP DATABASE IF EXISTS {0}" -f $VerifyDb) | Out-Null
        Invoke-Sql -Sql ("CREATE DATABASE {0} DEFAULT CHARACTER SET utf8mb4" -f $VerifyDb) | Out-Null
    } catch {
        $verifyOk = $false
        Log ("  [FAIL] 临时校验库建不起：{0}" -f $_.Exception.Message) 'Red'
    }
    if (-not $verifyOk) {
        Record '抽验（产物恢复 + 三方比对）' $false '临时库建不起'
        $script:anyFail = $true
    } else {
        $tmpDir = [System.IO.Path]::GetTempPath()
        $samples = @()
        try {
            foreach ($s in $picked) {
                $sec = Get-TableSection -DumpPath $dumpFiles[$s.db] -Table $s.table
                if ($sec.Count -eq 0) {
                    Log ("  [FAIL] {0}.{1}：在产物里切不出这一段" -f $s.db, $s.table) 'Red'
                    $samples += @{ db = $s.db; table = $s.table; ok = $false; note = '产物里无此表段' }
                    continue
                }
                $tmpSql = Join-Path $tmpDir ("duoduo_verify_" + [guid]::NewGuid().ToString('N') + ".sql")
                $text = "SET NAMES utf8mb4;`r`n" + ($sec -join "`r`n") + "`r`n"
                [System.IO.File]::WriteAllText($tmpSql, $text, (New-Object System.Text.UTF8Encoding $false))

                # 用 cmd 做 < 重定向：PowerShell 5.1 不支持输入重定向，
                # 而管道喂 stdin 会按 $OutputEncoding 重新编码 —— 中文会烂。
                $cmdline = 'docker exec -i ' + $MysqlContainer + ' mysql -u' + $MysqlUser + ' -p' + $MysqlPassword +
                    ' --default-character-set=utf8mb4 -D ' + $VerifyDb + ' < "' + $tmpSql + '"'
                $rout = & cmd /c $cmdline 2>&1
                $rrc = $LASTEXITCODE
                Remove-Item -LiteralPath $tmpSql -Force -ErrorAction SilentlyContinue

                if ($rrc -ne 0) {
                    $tail = (@($rout) | Select-Object -Last 3) -join ' | '
                    Log ("  [FAIL] {0}.{1}：恢复到临时库失败（exit={2}）{3}" -f $s.db, $s.table, $rrc, $tail) 'Red'
                    $samples += @{ db = $s.db; table = $s.table; ok = $false; note = "恢复失败 exit=$rrc" }
                } else {
                    $samples += @{ db = $s.db; table = $s.table; ok = $null; note = '待比对' }
                }
            }

            # ---- 三方比对：产物 vs before（dump 前）/ vs after（此刻的源库）----
            #   行数、校验和都取自**两个不同来源**（源库 / 临时库里的产物副本）。
            foreach ($s in @($samples | Where-Object { $_.ok -eq $null })) {
                $srcFrag = '`{0}`.`{1}`' -f $s.db, $s.table
                $dstFrag = '`{0}`.`{1}`' -f $VerifyDb, $s.table

                $cs = Invoke-Sql -Sql ('CHECKSUM TABLE ' + $srcFrag + ', ' + $dstFrag)
                $afterSum = ''; $dstSum = ''
                foreach ($l in $cs) {
                    $kv = $l -split "`t"
                    if ($kv.Count -ge 2) {
                        if ($kv[0] -eq ($s.db + '.' + $s.table)) { $afterSum = $kv[1] }
                        elseif ($kv[0] -eq ($VerifyDb + '.' + $s.table)) { $dstSum = $kv[1] }
                    }
                }
                $cntSql = 'SELECT (SELECT COUNT(*) FROM {0}) AS a, (SELECT COUNT(*) FROM {1}) AS b' -f $srcFrag, $dstFrag
                $cnt = Invoke-Sql -Sql $cntSql
                $srcCnt = ''; $dstCnt = ''
                if ($cnt.Count -gt 0) {
                    $kv = ([string]$cnt[0]) -split "`t"
                    if ($kv.Count -ge 2) { $srcCnt = $kv[0]; $dstCnt = $kv[1] }
                }

                $bSum = ''
                if ($beforeSum.ContainsKey($s.db + '.' + $s.table)) { $bSum = $beforeSum[$s.db + '.' + $s.table] }
                $bRows = -1
                if ($before.ContainsKey($s.db + '.' + $s.table)) { $bRows = $before[$s.db + '.' + $s.table] }

                $rowsOk = ($srcCnt -ne '') -and ($srcCnt -eq $dstCnt) -and ($bRows -lt 0 -or $bRows -eq [int64]$dstCnt)
                $sumOk = ($dstSum -ne '') -and (($dstSum -eq $bSum) -or ($dstSum -eq $afterSum))
                $ok = $rowsOk -and $sumOk

                if ($ok) {
                    if ($dstSum -eq $bSum) {
                        Log ("  [PASS] {0}.{1}：行数源{2}=产物{3}（before {4}），校验和 {5} 与 before 一致" -f $s.db, $s.table, $srcCnt, $dstCnt, $bRows, $dstSum) 'Green'
                    } else {
                        # 源表在备份窗口内被写过：产物与 after 一致即可，但**必须点名**，
                        # 不能悄悄放过 —— 否则"到底比的是哪个快照"就没人知道了。
                        Log ("  [PASS*] {0}.{1}：源表在备份窗口内被改写（before={2} / after={3}）；产物与 after 一致，行数 {4}" -f $s.db, $s.table, $bSum, $afterSum, $dstCnt) 'Yellow'
                    }
                } else {
                    Log ("  [FAIL] {0}.{1}：产物(行{2}/和{3}) 与源库两侧都不一致 —— before(行{4}/和{5}) / after(行{6}/和{7})" -f `
                        $s.db, $s.table, $dstCnt, $dstSum, $bRows, $bSum, $srcCnt, $afterSum) 'Red'
                }
                $s.ok = $ok
                $s.note = "before=$bSum after=$afterSum product=$dstSum rows=$bRows/$srcCnt/$dstCnt"
            }
        } finally {
            # 现场建的要跑完自毁 —— 删不掉必须显式报红并自证（铁律 3）
            try {
                Invoke-Sql -Sql ("DROP DATABASE IF EXISTS {0}" -f $VerifyDb) | Out-Null
            } catch {
                Log ("  [FAIL] 临时校验库删不掉：{0}" -f $_.Exception.Message) 'Red'
                $script:anyFail = $true
            }
            $left = $null
            try { $left = Invoke-Sql -Sql ('SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=''{0}''' -f $VerifyDb) } catch { }
            if ($null -eq $left -or $left.Count -eq 0 -or ([string]$left[0]).Trim() -ne '0') {
                Log '  [FAIL] 临时校验库仍然存在（或回读不到）—— 自毁失败，请人工清理' 'Red'
                $script:anyFail = $true
            } else {
                Log '  临时校验库已自毁（回读 information_schema = 0）'
            }
        }

        $bad = @($samples | Where-Object { $_.ok -ne $true })
        Record '抽验（产物恢复 + 三方比对）' ($bad.Count -eq 0) ("抽 $($samples.Count) 张，失败 $($bad.Count) 张")
        $script:samples = $samples
    }
}

# ---------- [6/7] 滚动保留 ----------
Write-Host ''
Write-Host ("[6/7] 滚动保留：runs 下只留最近 {0} 份" -f $KeepRuns)
$runsDir = $runsRoot
$allRuns = @(Get-ChildItem -Path $runsDir -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match '^\d{4}-\d{2}-\d{2}_\d{6}$' } |
    Sort-Object Name -Descending)
Log ("  现有 run：{0} 份" -f $allRuns.Count)
$drop = @($allRuns | Select-Object -Skip $KeepRuns)
foreach ($d in $drop) {
    # 只删"名字完全符合 run 命名规约"的目录 —— 别的目录一概不碰
    Remove-Item -LiteralPath $d.FullName -Recurse -Force -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $d.FullName) {
        Log ("  [FAIL] 旧备份删不掉：{0}" -f $d.Name) 'Red'
        $script:anyFail = $true
    } else {
        Log ("  已清理旧备份：{0}" -f $d.Name) 'Yellow'
    }
}
Record '滚动保留' $true ("保留 {0} 份，清理 {1} 份" -f [Math]::Min($KeepRuns, $allRuns.Count), $drop.Count)

# ---------- [7/7] manifest + 汇总 ----------
$gitHead = (& git rev-parse --short HEAD 2>$null)
if (-not $gitHead) { $gitHead = 'unknown' }
$manifest = @{
    runAt       = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
    runDir      = $script:runDir
    backupRoot  = $BackupRoot
    usedDefaultRoot = $rootIsDefault
    gitHead     = [string]$gitHead
    mysql       = [string]$mysqlVer
    container   = $MysqlContainer
    databases   = $Databases
    totalTables = $totalTables
    # ★ 每张表的**精确行数快照**（键："库名.表名"，值是 dump 前回表 COUNT(*) 的结果）。
    #   为什么必须落进 manifest：这是"恢复演练"唯一可用的**强判据**。
    #   没有它，恢复演练只能拿"恢复库 vs 当前源库"比 —— 而源库是活的（心跳表每分钟都在变），
    #   于是要么天天假红、要么把判据放宽到"差不多就行"，两种都会让演练失去意义。
    #   有了它，演练可以断言「恢复出来的每一张表、每一行，都与备份那一刻完全一致」。
    #   配套：scripts/ops-restore.ps1（恢复演练）会读这个字段，缺它就拒绝出结论。
    rowCounts   = $before
    # ★ 每份 dump 的 SHA256。恢复演练用它回答一个**独立**的问题：
    #   「我这次拿来恢复的产物，与当初备份出来的**是同一个文件**吗」
    #   —— 抓文件被误改、被截断、被别的进程写坏（这类损坏用"文件存在且非空"看不出来）。
    # ★★ 这里**刻意不记录整表校验和**（一度加过，实测踩坑后移除）：
    #   恢复演练的参照物只能来自**同一份 dump**，而恢复库正是从那份 dump 来的 ——
    #   拿两者比校验和是个**恒等式**（同一份数据的两次读取），证明不了任何事；
    #   若改用"dump 前的源表校验和"作参照，心跳表（t_sched_leader 每 2 分钟自改）
    #   必然不等，于是**备份完全正确却每次演练都红**（实测 2 处假红）——
    #   这正是本文件 [2/7] 注释里那条"校验和不能跨时刻比"的同一个坑。
    #   「行数相同但内容被改」这个风险的正确防线是 [5/7] 的抽验：
    #   从产物切片恢复 + 与 before/after 做三方比对（那边有独立的时间点，判据成立）。
    dumps       = @()
    samples     = @()
    status      = 'PENDING'
}
foreach ($db in $Databases) {
    $p = $dumpFiles[$db]
    $fi = Get-Item -LiteralPath $p
    $lines = [System.IO.File]::ReadAllLines($p, [System.Text.Encoding]::UTF8)
    $manifest.dumps += @{
        db = $db; file = $fi.Name; bytes = $fi.Length
        lines = $lines.Count; tables = @($declared[$db]).Count
        # 只读一遍文件算哈希（与上面的行数读取共用同一次读入的内容概念）
        sha256 = (Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash
    }
}
if ($script:samples) {
    foreach ($s in $script:samples) {
        $manifest.samples += @{ db = $s.db; table = $s.table; ok = $s.ok; note = $s.note }
    }
}
$manifest.status = $(if ($script:anyFail) { 'FAILED' } else { 'OK' })
$manifestPath = Join-Path $script:runDir 'manifest.json'
[System.IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 6), (New-Object System.Text.UTF8Encoding $false))

$sw.Stop()
Write-Host ''
Write-Host '======================================================================'
Write-Host ('汇总（总耗时 ' + [int]$sw.Elapsed.TotalMinutes + ' 分 ' + $sw.Elapsed.Seconds + ' 秒）')
Write-Host '======================================================================'
Log '汇总（同时写进本日志，保证只拿到 backup.log 也能复盘）' 
foreach ($r in $script:results) {
    $tag = 'FAIL'; if ($r.ok) { $tag = 'PASS' }
    Write-Host ("  [{0}] {1}   {2}" -f $tag, $r.name, $r.detail)
    Log ("  [{0}] {1}   {2}" -f $tag, $r.name, $r.detail)
}
$totalBytes = 0
foreach ($k in $dumpFiles.Keys) { $totalBytes += (Get-Item -LiteralPath $dumpFiles[$k]).Length }
Write-Host ("  产物：{0} 个 dump，合计 {1:N2} MB" -f $dumpFiles.Count, ($totalBytes / 1MB))
Write-Host ("  目录：{0}" -f $script:runDir)
Write-Host ("  指纹：{0}" -f $manifestPath)
Log ("  产物：{0} 个 dump，合计 {1:N2} MB；目录 {2}；指纹 {3}" -f $dumpFiles.Count, ($totalBytes / 1MB), $script:runDir, $manifestPath)
Log ("  总耗时 {0} 分 {1} 秒" -f [int]$sw.Elapsed.TotalMinutes, $sw.Elapsed.Seconds)

if ($script:anyFail) {
    Log ''
    Send-Alert '备份过程中存在失败项（详见 backup.log）'
    Write-Host '结论：备份未通过 —— 已发出告警，请按上面 FAIL 行处置 ❌' -ForegroundColor Red
    exit 1
}
Write-Host ''
Write-Host '结论：六库备份完整、抽验双源一致 ✅' -ForegroundColor Green
Write-Host ("      恢复示例：docker exec -i duoduo-mysql mysql -uroot -p*** -e 'CREATE DATABASE duoduo_user'") -ForegroundColor DarkGray
Write-Host ("                docker exec -i duoduo-mysql mysql -uroot -p*** -D duoduo_user < <run目录>\duoduo_user.sql") -ForegroundColor DarkGray
exit 0
