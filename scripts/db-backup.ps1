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

      · 铁律 22（续）—— **心跳表不能用 CHECKSUM 判**，否则"备份正确却天天报红"。
        实测（2026-10-13）：`t_sched_leader` 的选主续租**约每 15 秒**自改一次，
        而备份窗口有 30 秒上下 ⇒ 窗口内必然发生 ≥2 次改写 ⇒ 产物 / before / after
        三者**注定**互不相同。所以声明一个**持续改写表**名单，名单内的表改判
        **行数 + 主键集合**（主键列现查 information_schema），日志打 **[PASS~]** 点名，
        绝不静默当成普通 PASS。名单解析出 0 张时判红（防名单静默失效）。
        同族问题：append-only 表在窗口内被插入时，`before` 行数也不该拿去卡产物 ——
        此时行数只按「源库 ↔ 产物」两来源判，并同样点名（[PASS*]）。

      · 铁律 3 —— 临时库现场建、跑完自毁；**建不起或删不掉都显式报红**并自证。

      · 铁律 26 —— 备份根目录是**待你拍板的占位默认值**，不是"已确定的方案"。
        改一行即可（见下方 `$DefaultBackupRoot`）。

      · 铁律 11/8 —— 临时文件位置用 `[System.IO.Path]::GetTempPath()`，不吃
        `$env:TEMP`；本文件必须带 UTF-8 BOM，否则 PS 5.1 按 ANSI 解码、中文全乱码。

      · 铁律 2（续）—— **用户上传的文件卷不是"附件"，是数据的一部分**。
        六库备好、文件卷没备，恢复出来的系统是「订单在、图没了」；E12 资质材料更是
        丢了不可补（商家要重新上传、平台要重新审）。所以 [4b/7] 一并备 `fulfilment-uploads`
        与 `merchant-materials` 两个卷，并同样要求**两个来源**：容器内算一遍 sha256、
        复制到宿主机后再算一遍，逐文件一致才算数。卷的真实名字带 compose 项目前缀 ⇒
        **按后缀反查**而不是拼字符串（拼错的表现是"复制出一个空目录然后宣布成功"）。

    产物布局（每次备份一个 run 目录）：
      <BackupRoot>\runs\<yyyy-MM-dd_HHmmss>\          ← 秒级命名：同一分钟内连跑两次不会互相覆盖
          duoduo_user.sql … duoduo_marketing.sql     六库的 dump
          uploads\<卷名>\…                           两个上传卷的文件（逐文件 sha256 记进 manifest）
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

# --------------------------------------------------------------------------
# ★★ 声明：**持续被改写的表**（选主 / 心跳 / 计数器类）
#
#   为什么必须有这份名单：抽验的主判据是「产物的 CHECKSUM 必须 == before 或 after」。
#   这条判据对**静态表**成立，对**心跳表**不成立 ——
#   实测（2026-10-13）：`t_sched_leader` 的选主续租**约每 15 秒**改一次
#   expire_at/updated_at（6 秒探针里就观察到一次跳变），而备份窗口从"取 before"
#   到"取 after"有 30 秒上下 ⇒ 窗口内**必然**发生 ≥2 次改写 ⇒
#   产物 / before / after 三者**注定**互不相同 ⇒ **备份完全正确却每次都报红**。
#   这是最坏的一种报错：狼来了。报到最后没人看，真正的坏备份反而被淹没。
#
#   做法：名单里的表改用**在心跳下依然稳定的判据** ——
#        行数 + **主键集合**（主键列从 information_schema 现查，绝不写死列名）。
#   并且**必须点名**（日志打 [PASS~] 并写明"校验和对它不成立"），不许静默降级 ——
#   白名单本质上是一次「判据放宽」，放宽必须看得见（铁律：兜底/降级不许静默）。
#
#   ★ 判据方向：**宁假红勿假绿**。
#     漏登记一张 → 多报一次红（看得见、能改）；名单里的表若一张都不存在
#     → 由下面的 declared-vs-actual 守卫当场判红，防止名单静默失效。
#
#   这里按**表名**声明、运行时解析成 (库, 表) 对，因为同一个逻辑表按域各存一份
#   （`t_sched_leader` 目前分布在 5 个库里各一张）。
# --------------------------------------------------------------------------
$ChurningTableNames = @('t_sched_leader')
$script:churnSet = @{}     # "db.table" -> $true

$script:logFile = $null
$script:runDir = $null
$script:results = @()
$script:samples = $null
$script:anyFail = $false

# --------------------------------------------------------------------------
# ★★ 顶层 trap：**未捕获的异常也必须喊人**。
#
#   为什么必须加：本脚本的产物流水都是"跑到 [7/7] 才写"（manifest / 汇总 /
#   `_ALERT.txt`）。而 `Invoke-Sql` 在 mysql 失败时是 `throw` —— 一旦某处没包
#   try/catch，异常会**直接掀掉整个脚本**：日志停在中途，manifest、汇总、
#   `_ALERT.txt` **一份都不产出**，退出码 1 也传不到调用方（可能被当成"跑起来了"）。
#   实测踩过一次：一条 SQL 的引号写错，整套备份只留下半截日志。
#   ⇒ 「备份失败」这件事**必须留下产物**，否则夜间无人值守跑失败等于没有备份。
# --------------------------------------------------------------------------
trap {
    $msg = $_.Exception.Message
    $pos = '' + $_.InvocationInfo.PositionMessage
    Write-Host ''
    Write-Host ("  [!!] 脚本被未捕获的异常中断：{0}" -f $msg) -ForegroundColor Red
    Write-Host ("       {0}" -f ($pos -replace "`r?`n", ' ')) -ForegroundColor Red
    if ($script:logFile) {
        [System.IO.File]::AppendAllText($script:logFile,
            ("[{0}]   [FAIL] 未捕获异常中断：{1}`r`n[{0}]          {2}`r`n" -f (Get-Date -Format 'HH:mm:ss'), $msg, ($pos -replace "`r?`n", ' ')),
            (New-Object System.Text.UTF8Encoding $false))
    }
    $script:anyFail = $true
    try { Send-Alert ("脚本被未捕获的异常中断：" + $msg) } catch { }
    exit 1
}

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

function Get-TextFingerprint {
    # 取一段文本的 SHA256 前 12 位十六进制。
    # 用途：给"主键集合"这类判据在日志里留一个**可核对的口子** ——
    # 只打印行数会被"行数对了、键不对"骗过去；打印指纹则能事后回查。
    #
    # ★★ `[AllowEmptyString()]` 不能省：空表（0 行）时待指纹化的文本就是空串，
    #   而 Mandatory 的 `[string]` 参数**默认拒绝空串** —— 少了这个特性，
    #   一遇到空表就抛「无法将参数绑定到参数 Text，因为该参数为空字符串」，
    #   而且是在判据算完、日志还没打的那一刻炸 ⇒ 整轮备份没了结论。
    #   实测踩中（反向验证时把持续改写表清空，正好造出空串这个边界）。
    param([Parameter(Mandatory)][AllowEmptyString()][string]$Text)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($Text))
        return ([BitConverter]::ToString($bytes) -replace '-', '').ToLower().Substring(0, 12)
    } finally {
        $sha.Dispose()
    }
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

# ---------- 持续改写表（心跳/选主）名单：解析 + 自证 ----------
#   名单按**表名**声明 ⇒ 在这里解析成"哪个库真有这张表"的 (库, 表) 对。
#   解析出 0 张就判红：表改名/换库之后，名单会**静默失效**，
#   后果是那几张表又退回"注定报红"的 CHECKSUM 判据 —— 必须有人当场喊出来。
$churnPairs = @()
foreach ($db in $Databases) {
    foreach ($t in @($declared[$db])) {
        if ($ChurningTableNames -contains $t) {
            $churnPairs += , @{ db = $db; table = $t }
            $script:churnSet["$db.$t"] = $true
        }
    }
}
if ($churnPairs.Count -eq 0) {
    Log ("  [!!] 声明为『持续改写表』的（{0}）在任何库里都没找到 —— 名单已失效，拒绝让它静默退化" -f ($ChurningTableNames -join ', ')) 'Red'
    Record '持续改写表名单自证' $false ("0 张（声明：$($ChurningTableNames -join ', ')）")
} else {
    Log ("  持续改写表 {0} 张：{1}" -f $churnPairs.Count, (($churnPairs | ForEach-Object { $_.db + '.' + $_.table }) -join ', ')) 'Yellow'
    Log '    判据＝行数 + 主键集合（不用 CHECKSUM —— 见文件头「持续被改写的表」段）' 'Yellow'
    Record '持续改写表名单自证' $true ("$($churnPairs.Count) 张（声明：$($ChurningTableNames -join ', ')）")
}

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

# ---------- [4b/7] 用户上传文件卷（图片 / 资质材料）----------
Write-Host ''
Write-Host '[4b/7] 上传文件卷：逐文件 sha256 清单 + 把字节复制进 run 目录'
Log '  为什么必须与六库一起备：这两份卷里放的是**用户产生、不可重建**的数据'
Log '  （商品/评价图片、E12 入驻资质材料）。只备数据库等于「订单在、图没了」；'
Log '  资质材料更是丢了不可补（商家要重新上传、平台要重新审）。'
Log '  ★ 判据同样要求**两个来源**：容器内算一遍 sha256，复制到宿主机后再算一遍，逐文件必须一致。'

# 卷的**真实名字**带 compose 项目前缀（<项目>_<卷名>），所以按后缀反查而不是拼字符串 ——
# 拼错的表现不是报错，而是「复制出一个空目录然后宣布成功」。
$UploadVolumes = @(
    @{ name = 'fulfilment-uploads'; container = 'duoduo-app-fulfilment'; inner = '/data/uploads'; label = '商品 / 评价图片上传目录' }
    @{ name = 'merchant-materials'; container = 'duoduo-app-user'; inner = '/data/merchant-materials'; label = 'E12 入驻资质材料（私有卷，读取走鉴权端点）' }
)
$volManifest = @()
$volFail = 0
$volFiles = 0
$volBytes = 0
foreach ($v in $UploadVolumes) {
    Log ("  — {0}（{1}）" -f $v.name, $v.label)

    # ① 反查真实卷名。解析到 0 个 ⇒ 显式失败（不许当成"空卷"放过）
    $realVol = (& docker volume ls --format '{{.Name}}' 2>$null |
        Where-Object { $_ -match ('(^|_)' + [regex]::Escape($v.name) + '$') } | Select-Object -First 1)
    if (-not $realVol) {
        Log ("    [FAIL] 反查不到卷「{0}」—— 卷不存在或还没建" -f $v.name) 'Red'
        $volFail++
        continue
    }

    # ② 容器在跑、且卷真的挂在预期路径（挂到别处 = 备份了别的东西）
    $running = ("$(& docker inspect --format '{{.State.Running}}' $v.container 2>$null)").Trim()
    if ($running -ne 'true') {
        Log ("    [FAIL] 容器 {0} 没在跑，拿不到卷内容" -f $v.container) 'Red'
        $volFail++
        continue
    }
    $dirOk = ("$(& docker exec $v.container sh -c ('test -d ' + $v.inner + ' && echo YES || echo NO') 2>$null)").Trim()
    if ($dirOk -ne 'YES') {
        Log ("    [FAIL] 容器内 {0} 不存在 —— 卷没挂在预期位置" -f $v.inner) 'Red'
        $volFail++
        continue
    }

    # ③ 容器内逐文件 sha256（xargs -r：0 个文件时不要退化成"从 stdin 读"而挂住）
    $rawInner = @(& docker exec $v.container sh -c `
        ('cd ' + $v.inner + ' && find . -type f -print0 2>/dev/null | xargs -0 -r sha256sum 2>/dev/null | LC_ALL=C sort -k2') 2>$null)
    $innerList = @()
    foreach ($l in $rawInner) {
        if (-not $l) { continue }
        $t = $l -split '\s+', 2
        if ($t.Count -eq 2) { $innerList += @{ h = $t[0].Trim(); p = $t[1].Trim() } }
    }

    # ④ 把字节复制出来（docker cp 不经过 PowerShell 管道，字节不会被重新编码）
    $destRoot = Join-Path $script:runDir 'uploads'
    New-Item -ItemType Directory -Force -Path $destRoot | Out-Null
    $dest = Join-Path $destRoot $v.name
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    & docker cp ($v.container + ':' + $v.inner + '/.') $dest 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Log ("    [FAIL] docker cp 失败（exit={0}）" -f $LASTEXITCODE) 'Red'
        $volFail++
        continue
    }

    # ⑤ 宿主机侧重新算一遍 → 与容器内逐文件比对（这才是"复制没截断"的证据）
    $hostList = @()
    $bytesHere = 0
    foreach ($f in @(Get-ChildItem -LiteralPath $dest -Recurse -File -Force -ErrorAction SilentlyContinue)) {
        $rel = './' + ($f.FullName.Substring($dest.Length).TrimStart('\', '/') -replace '\\', '/')
        $hostList += @{ h = (Get-FileHash -LiteralPath $f.FullName -Algorithm SHA256).Hash.ToLower(); p = $rel }
        $bytesHere += $f.Length
    }
    $a = @($innerList | Sort-Object { $_.p })
    $b = @($hostList | Sort-Object { $_.p })
    $bad = 0
    if ($a.Count -ne $b.Count) { $bad++ }
    for ($i = 0; $i -lt [Math]::Min($a.Count, $b.Count); $i++) {
        if ($a[$i].p -ne $b[$i].p -or $a[$i].h -ne $b[$i].h) { $bad++ }
    }
    if ($bad -gt 0) {
        Log ("    [FAIL] 复制出来的字节与容器内不一致（容器 {0} 个文件 / 宿主机 {1} 个文件，差异 {2} 处）" -f $a.Count, $b.Count, $bad) 'Red'
        $volFail++
        continue
    }

    # ⑥ 清单指纹：把逐文件 "hash  相对路径" 排序拼起来再取 SHA256
    $listing = (($b | ForEach-Object { $_.h + '  ' + $_.p }) -join "`n")
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $listingHash = ([BitConverter]::ToString($sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($listing))) -replace '-', '').ToLower()
    $sha.Dispose()

    # ★ 逐文件清单落进 manifest：恢复演练靠它回答「放进来的每一个文件、与备份那一刻是不是同一份字节」。
    #   体积说明：本项目的开发卷只有几百个文件，直接存清单最直观；若将来文件数上万，
    #   应改为只存 listingSha256 + 分片清单（判据不变）。
    $volManifest += @{
        name = $v.name; volume = [string]$realVol; container = $v.container; inner = $v.inner; label = $v.label
        files = $b.Count; bytes = $bytesHere; listingSha256 = $listingHash
        copiedTo = ('uploads/' + $v.name)
        listing = @($b | ForEach-Object { @{ p = $_.p; h = $_.h } })
    }
    $volFiles += $b.Count
    $volBytes += $bytesHere

    if ($b.Count -eq 0) {
        Log ("    [注意] 该卷当前是**空目录**（0 个文件）—— 不算失败，但要说出来：空卷的『备份成功』没有信息量（清单指纹 {0}）" -f $listingHash.Substring(0, 12)) 'Yellow'
    } else {
        Log ("    [OK] {0} 个文件 / {1:N2} MB，容器内与宿主机逐文件 sha256 一致（清单指纹 {2}）" -f $b.Count, ($bytesHere / 1MB), $listingHash.Substring(0, 12)) 'Green'
    }
}
Record '上传文件卷备份' ($volFail -eq 0) ("{0} 个卷 / {1} 个文件 / {2:N2} MB" -f $volManifest.Count, $volFiles, ($volBytes / 1MB))

# ---------------------------------------------------------------------------
# 单张抽样表的比对（**恢复一张就立刻比一张**）
#
# ★★ 为什么必须「立刻比」而不能「全部恢复完再一起比」：
#   所有抽样表都恢复进**同一个** `duoduo_backup_verify` 库，而 dump 的每一段都以
#   `DROP TABLE IF EXISTS ...` 开头 ⇒ **同名的表互相覆盖**，库里只剩最后恢复的那一张。
#   本项目有 4 个**跨库同名**的表：`t_sched_leader`（5 个库各一张）、
#   `t_audit_sample` / `t_audit_sample_rate` / `t_proxy_work_order`（各 2 个库）。
#   同一轮只要抽到其中两个，先恢复的那张就变成「拿别人的数据跟自己比」——
#   实测**假红**（dump 里 product 的 leader 有 2 行，判据却报产物只有 1 行 ——
#   读到的其实是随后恢复的 marketing 那一张）与**假绿**（两张内容恰好相同、
#   校验和一样 ⇒「比过了、其实比的是另一张」）都真实发生过。
#   改成"恢复一张立刻比一张"之后，库里任一时刻只可能有刚恢复的那一张，冲突消失。
# ---------------------------------------------------------------------------
function Invoke-SampleCompare {
    param([Parameter(Mandatory)][hashtable]$S)
    $s = $S
    $beforeSum = $script:beforeSum
    $rowsBefore = $before      # "库.表" -> dump 前行数（改名，避免与函数内行数变量混淆）
    # ★ 单张表的判据代码炸了，不该让整轮备份"没有结论"。
    #   按"这张表没验成"记为**失败并点名**（响亮，不是静默降级），
    #   而不是把异常往上抛、掀掉整个脚本。
    #   实测踩中：空字符串绑到 Mandatory 的 `[string]` 参数上会抛异常。
    try {
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
    if ($rowsBefore.ContainsKey($s.db + '.' + $s.table)) { $bRows = $rowsBefore[$s.db + '.' + $s.table] }

    # 行数：主判据是**源库 ↔ 产物**（两个不同来源）。
    #   before 行数只是第三参照 —— 它只在"这张表在备份窗口内没被写过"时
    #   才该吻合。若 before 已经不等于**此刻**的源库，说明窗口内发生过
    #   插入/删除（典型的 append-only 审计表），此时拿 before 去卡产物，
    #   会让"备份期间刚好来了一条"变成假红 —— 与心跳表同一族的狼来了。
    $windowChanged = ($bRows -ge 0) -and ($srcCnt -ne '') -and ($bRows -ne [int64]$srcCnt)
    $rowsOk = ($srcCnt -ne '') -and ($srcCnt -eq $dstCnt) -and `
        (($bRows -lt 0) -or $windowChanged -or ($bRows -eq [int64]$dstCnt))

    if ($script:churnSet.ContainsKey($s.db + '.' + $s.table)) {
        # ================= 持续改写表：稳定投影判据 =================
        #   CHECKSUM 对它**不成立**（实测每 ~15 秒自改一次，见文件头）。
        #   改判：行数 + **主键集合**（主键列现查 information_schema，
        #   不写死列名；换主键也不用改这里）。
        $pkCols = ''
        # ★★ 外层用**单引号**字符串：里面的 `''` 才是"一个单引号"。
        #   这里曾经写成双引号 + `''{0}''` —— 双引号里 `''` 不是转义，
        #   结果 SQL 里出现**两个**单引号 ⇒ 语法错 ⇒ Invoke-Sql 抛异常
        #   ⇒ 整个备份脚本当场中止（连汇总和告警都没了）。实测踩过。
        try {
            $pkCols = Invoke-SqlScalar -Sql ('SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY ORDINAL_POSITION SEPARATOR '','') FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=''{0}'' AND TABLE_NAME=''{1}'' AND CONSTRAINT_NAME=''PRIMARY''' -f $s.db, $s.table)
        } catch {
            Log ("  [FAIL] {0}.{1}：查主键的 SQL 失败 —— {2}" -f $s.db, $s.table, $_.Exception.Message) 'Red'
        }
        if (-not $pkCols) {
            Log ("  [FAIL] {0}.{1}：声明为持续改写表，但查不到主键 —— 稳定投影做不了，拒绝静默退回 CHECKSUM" -f $s.db, $s.table) 'Red'
            $s.ok = $false
            $s.note = 'churning 表查不到主键'
            return
        }
        $pkExpr = ((@($pkCols -split ',') | ForEach-Object { '`' + $_.Trim() + '`' }) -join ', ')
        # 拼串而不用 -f：两次套用格式串容易把 `{0}` 留在半个字符串里，
        # 那种 bug 的症状是 SQL 报语法错、看着像"库有问题"。
        $selFrag = 'SELECT CONCAT_WS(''#'', ' + $pkExpr + ') FROM '
        try {
            $srcKeys = Invoke-Sql -Sql ($selFrag + '`' + $s.db + '`.`' + $s.table + '` ORDER BY ' + $pkExpr)
            $dstKeys = Invoke-Sql -Sql ($selFrag + '`' + $VerifyDb + '`.`' + $s.table + '` ORDER BY ' + $pkExpr)
        } catch {
            Log ("  [FAIL] {0}.{1}：稳定投影 SQL 失败 —— {2}" -f $s.db, $s.table, $_.Exception.Message) 'Red'
            $s.ok = $false
            $s.note = 'churning 投影 SQL 失败'
            return
        }
        $sk = (@($srcKeys) | Where-Object { $_ -ne '' }) -join "`n"
        $dk = (@($dstKeys) | Where-Object { $_ -ne '' }) -join "`n"
        # 空表（0 行）允许两边都为 ''；非空则要求逐行完全一致。
        $keyOk = ($sk -eq $dk) -and (($sk -ne '') -or ($srcCnt -eq '0'))
        $ok = $rowsOk -and $keyOk
        $kfp = Get-TextFingerprint -Text $dk
        if ($ok) {
            Log ("  [PASS~] {0}.{1}：**持续改写表**（校验和对它不成立）；改判 行数 源{2}=产物{3} + 主键集合一致（主键 {4} / {5} 行 / 指纹 {6}）" -f `
                $s.db, $s.table, $srcCnt, $dstCnt, $pkExpr, (@($dstKeys) | Where-Object { $_ -ne '' }).Count, $kfp) 'Yellow'
        } else {
            Log ("  [FAIL] {0}.{1}：持续改写表，**稳定投影也不一致** —— 行数 源{2}/产物{3}/before{4}；主键集合 源指纹 {5} / 产物指纹 {6}（主键 {7}）" -f `
                $s.db, $s.table, $srcCnt, $dstCnt, $bRows, (Get-TextFingerprint -Text $sk), $kfp, $pkExpr) 'Red'
        }
        $s.ok = $ok
        $s.note = "churning(rows+pkset) rows=$bRows/$srcCnt/$dstCnt pk=$pkExpr srcKeyfp=$(Get-TextFingerprint -Text $sk) dstKeyfp=$kfp"
        return
    }

    $sumOk = ($dstSum -ne '') -and (($dstSum -eq $bSum) -or ($dstSum -eq $afterSum))
    $ok = $rowsOk -and $sumOk

    if ($ok) {
        if ($dstSum -eq $bSum) {
            Log ("  [PASS] {0}.{1}：行数源{2}=产物{3}（before {4}），校验和 {5} 与 before 一致" -f $s.db, $s.table, $srcCnt, $dstCnt, $bRows, $dstSum) 'Green'
        } else {
            # 源表在备份窗口内被写过：产物与 after 一致即可，但**必须点名**，
            # 不能悄悄放过 —— 否则"到底比的是哪个快照"就没人知道了。
            Log ("  [PASS*] {0}.{1}：源表在备份窗口内被改写（before 和={2} / after 和={3}，before 行={4}/现在={5}）；产物与 after 一致，行数 {6}" -f `
                $s.db, $s.table, $bSum, $afterSum, $bRows, $srcCnt, $dstCnt) 'Yellow'
        }
    } else {
        Log ("  [FAIL] {0}.{1}：产物(行{2}/和{3}) 与源库两侧都不一致 —— before(行{4}/和{5}) / after(行{6}/和{7})" -f `
            $s.db, $s.table, $dstCnt, $dstSum, $bRows, $bSum, $srcCnt, $afterSum) 'Red'
    }
    $s.ok = $ok
    $s.note = "before=$bSum after=$afterSum product=$dstSum rows=$bRows/$srcCnt/$dstCnt"
    } catch {
        Log ("  [FAIL] {0}.{1}：判据代码抛异常 —— {2}" -f $s.db, $s.table, $_.Exception.Message) 'Red'
        $s.ok = $false
        $s.note = '判据抛异常：' + $_.Exception.Message
    }
}

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
                    # ★★ 恢复一张就**立刻比对一张**（原因见 Invoke-SampleCompare 的注释）
                    $rec = @{ db = $s.db; table = $s.table; ok = $null; note = '待比对' }
                    Invoke-SampleCompare -S $rec
                    $samples += $rec
                }
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
    # ★ 上传文件卷的备份清单（[4b/7] 产出）。恢复演练（ops-restore.ps1）会读它并逐文件核 sha256；
    #   缺这一段 ⇒ 演练**拒绝出结论**（只备了库、没备文件，等于"订单在、图没了"而无人知道）。
    volumes     = @()
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
$manifest.volumes = $volManifest
$manifest.status = $(if ($script:anyFail) { 'FAILED' } else { 'OK' })
$manifestPath = Join-Path $script:runDir 'manifest.json'
[System.IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 8), (New-Object System.Text.UTF8Encoding $false))

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
Write-Host '结论：六库备份完整、抽验双源一致；上传文件卷逐文件 sha256 双源一致 ✅' -ForegroundColor Green
Write-Host ("      恢复示例：docker exec -i duoduo-mysql mysql -uroot -p*** -e 'CREATE DATABASE duoduo_user'") -ForegroundColor DarkGray
Write-Host ("                docker exec -i duoduo-mysql mysql -uroot -p*** -D duoduo_user < <run目录>\duoduo_user.sql") -ForegroundColor DarkGray
exit 0
