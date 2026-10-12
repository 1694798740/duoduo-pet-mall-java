# ops-restore.ps1 —— 备份恢复演练：把备份**真的恢复一次**，并用行数快照逐表证明它是对的
#
# 为什么必须有这个脚本（而不是在文档里写几条恢复命令）：
#   「我们有备份」与「我们能把系统恢复起来」是两件事。前者只需文件存在，
#   后者需要：产物能被 mysql 吃进去、库字符集对、表的依赖顺序对、
#   恢复出来的内容与备份那一刻**逐行一致**。这些只有真跑一次才知道。
#   ★ 而"真跑一次"如果不带判据，就又退化成"命令没报错 = 成功了"——
#     所以本脚本的结论来自一个**可核对的数字**：manifest.rowCounts
#     （备份时逐表回表 COUNT(*) 的快照），恢复后逐表比对，差一行都算失败。
#
# 与 S4（scripts/db-backup.ps1）的分工：
#   db-backup  = 产出一份**可被证明**的备份（产物自证 + 三方校验和比对 + 滚动保留）；
#   ops-restore = 把那份产物**恢复出来**，证明它真能用（本脚本）。
#   两者共用同一个 rowCounts 快照作为判据 —— 备份侧写下它、恢复侧核对它。
#
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ops-restore.ps1
#       用**最近一份**备份做演练（恢复进 zz_restore_* 临时库，验完自动删）
#   ... -BackupDir <run目录>
#       指定某一份备份
#   ... -Keep
#       保留临时库（用于人工比对；**用完请自己删掉**，别留在生产库里）
#
# 退出码：0 = 演练全绿；1 = 任一步失败（含"判据缺失"这种"没验成"的情况）。
[CmdletBinding()]
param(
    [string]$BackupDir,
    [string]$MysqlContainer = 'duoduo-mysql',
    [string]$MysqlUser = 'root',
    [string]$MysqlPassword = 'duoduo_root',
    # 临时库前缀：刻意用极不常见的名字，且下面有断言拦住"与业务库重名"
    [string]$Prefix = 'zz_restore_',
    [switch]$Keep
)

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$repo = Split-Path -Parent $PSScriptRoot
$RepoDbNames = @('duoduo_user', 'duoduo_product', 'duoduo_trade', 'duoduo_fulfilment', 'duoduo_social', 'duoduo_marketing')
$script:fail = 0

function Say([string]$text, [string]$color = 'Gray') { Write-Host $text -ForegroundColor $color }
function Ok([string]$text) { Say ("  [OK]   " + $text) 'Green' }
function Bad([string]$text) { Say ("  [FAIL] " + $text) 'Red'; $script:fail++ }
function Step([string]$text) { Write-Host ''; Say $text 'Cyan' }

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
    return ,$res
}

Say ''
Say '====================================================================' 'Cyan'
Say ' 备份恢复演练（ops-restore）' 'Cyan'
Say '====================================================================' 'Cyan'

# ---------------------------------------------------------------------------
# [0/6] 前置：Docker 可用、MySQL 容器在跑
#
# ★ 为什么单列一步、且失败时**不复用**后面的"产品不全/导入失败"措辞：
#   docker 引擎掉线时，后面每一步都会失败，最后结论会显示"产物不全 / 导入失败"
#   ——那看起来像**备份有问题**，实际是**环境没了**。两者的处置完全相反
#   （一个要重新备份、一个要先修环境），长得却一样，所以必须在这一步就说清楚。
#   实测踩过：Docker Desktop 在演练中途退出，[4/6] 报"导入失败"，
#   差点被当成备份损坏去查 manifest。
# ---------------------------------------------------------------------------
Step '[0/6] 前置检查：Docker 引擎可用、MySQL 容器在跑'
$dockerOk = $false
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'SilentlyContinue'
$ver = & docker info --format '{{.ServerVersion}}' 2>$null
if ($LASTEXITCODE -eq 0 -and $ver) { $dockerOk = $true }
$ErrorActionPreference = $prevEap
if (-not $dockerOk) {
    Bad '连不上 Docker 引擎 —— **这是环境问题，不是备份问题**。'
    Say '         请先启动 Docker Desktop（`docker desktop start` 或从开始菜单打开），' 'Yellow'
    Say '         等 `docker ps` 能出结果后重跑本演练。' 'Yellow'
    Say '         ★ 此时**不要**下"备份不可信"的结论 —— 一次都没验成。' 'Yellow'
    exit 1
}
Ok ("Docker 引擎可用（ServerVersion = {0}）" -f (($ver | Out-String).Trim()))

$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'SilentlyContinue'
$state = (& docker inspect -f '{{.State.Status}}' $MysqlContainer 2>$null | Out-String).Trim()
$ErrorActionPreference = $prevEap
if ($state -ne 'running') {
    Bad ("MySQL 容器 '$MysqlContainer' 不在运行（State = '{0}'）—— 环境问题，不是备份问题。" -f $state)
    Say ("         先起容器：docker start {0}" -f $MysqlContainer) 'Yellow'
    exit 1
}
Ok ("MySQL 容器在跑：{0}" -f $MysqlContainer)

# ---------------------------------------------------------------------------
# [1/6] 定位备份 + 读指纹
# ---------------------------------------------------------------------------
Step '[1/6] 定位备份目录并读 manifest.json'

if (-not $BackupDir) {
    $root = $env:DUODUO_BACKUP_ROOT
    if (-not $root) {
        $bk = Join-Path $PSScriptRoot 'db-backup.ps1'
        if (Test-Path $bk) {
            $m = Select-String -Path $bk -Pattern "^\`$DefaultBackupRoot\s*=\s*'([^']+)'" | Select-Object -First 1
            if ($m) { $root = $m.Matches[0].Groups[1].Value }
        }
    }
    if (-not $root) { Bad "取不到备份根目录（既没有 -BackupDir，也没有 DUODUO_BACKUP_ROOT / db-backup.ps1 的默认值）"; exit 1 }
    $runs = Join-Path $root 'runs'
    if (-not (Test-Path $runs)) { Bad "备份根 '$root' 下没有 runs 目录 —— 备份还没跑过（先跑 scripts\db-backup.ps1）"; exit 1 }
    $latest = Get-ChildItem -Path $runs -Directory | Sort-Object Name -Descending | Select-Object -First 1
    if (-not $latest) { Bad "'$runs' 里一份备份都没有"; exit 1 }
    $BackupDir = $latest.FullName
}
if (-not (Test-Path $BackupDir)) { Bad "备份目录不存在：$BackupDir"; exit 1 }
Ok "备份目录：$BackupDir"

$mfPath = Join-Path $BackupDir 'manifest.json'
if (-not (Test-Path $mfPath)) { Bad '缺 manifest.json —— 没有指纹就没法出结论（拒绝「装作验过」）'; exit 1 }
$mf = Get-Content -Path $mfPath -Raw -Encoding UTF8 | ConvertFrom-Json
Ok ("指纹：runAt={0}  gitHead={1}  status={2}  库数={3}  表数={4}" -f $mf.runAt, $mf.gitHead, $mf.status, @($mf.databases).Count, $mf.totalTables)
if ($mf.status -ne 'OK') {
    # 刻意不自动放行：一份自己都认为失败的备份，不该被用来"证明恢复没问题"。
    Bad ("这份备份的 status = {0}（不是 OK）—— 拒绝用它做恢复演练" -f $mf.status)
    exit 1
}

# ★ 判据自证：没有 rowCounts 就**不能**出结论（旧版本产物），而不是降级成"恢复命令没报错 = 成功"
$rcProps = @()
if (($mf.PSObject.Properties.Name -contains 'rowCounts') -and $mf.rowCounts) {
    $rcProps = @($mf.rowCounts.PSObject.Properties)
}
if ($rcProps.Count -eq 0) {
    Bad '这份备份的 manifest 里没有 rowCounts（行数快照）—— 它是恢复演练的**唯一强判据**。'
    Say '         说明这是一份旧脚本产出的备份。请先用当前版本的 scripts\db-backup.ps1 重新备份一次。' 'Yellow'
    exit 1
}
Ok ("行数快照：{0} 张表（这就是本次演练要逐张核对的判据）" -f $rcProps.Count)

# ★★ 本脚本**刻意不比整表校验和**（一度加过，实测踩坑后移除）。
#   ① 若拿"恢复库 vs 备份产物"比 → 两者同源，是**恒等式**，证明不了任何事；
#   ② 若拿"恢复库 vs dump 前的源表校验和"比 → 心跳表（`t_sched_leader` 每 2 分钟自改）
#      必然不等，**备份完全正确却每次演练都红**（实测 2 处假红）。
#   本脚本的判据因此只取两条**成立**的：
#     ① 产物文件 SHA256（见 [2/6]）—— 回答"我恢复用的文件与当初备份的是同一个吗"；
#     ② 逐表行数快照（见 [5/6]）—— 回答"恢复出来的表数/行数与备份那一刻一致吗"。
#   「行数相同但内容被改」由 db-backup 的 [5/7] 抽验负责：它从产物切片恢复，
#   与 before/after 做**三方比对**（那里有独立的时间点，判据成立）。

# 按库分组（键形如 duoduo_user.t_user）
$byDb = @{}
foreach ($p in $rcProps) {
    $key = $p.Name
    $dot = $key.IndexOf('.')
    if ($dot -le 0) { Bad "行数快照里的键格式不对（期望 库名.表名）：$key"; continue }
    $db = $key.Substring(0, $dot)
    $tb = $key.Substring($dot + 1)
    if (-not $byDb.ContainsKey($db)) { $byDb[$db] = New-Object System.Collections.ArrayList }
    [void]$byDb[$db].Add(@{ Table = $tb; Rows = [int64]$p.Value })
}
$dbList = @($byDb.Keys | Sort-Object)
Ok ("涉及库：{0} 个（{1}）" -f $dbList.Count, ($dbList -join ', '))

# 安全断言：临时库名绝不能与业务库撞名
foreach ($db in $dbList) {
    if ($RepoDbNames -contains ($Prefix + $db)) { Bad "临时库名 '$($Prefix + $db)' 与业务库重名 —— 拒绝继续（会覆盖真实数据）"; exit 1 }
    if ($RepoDbNames -contains $db -and $Prefix -eq '') { Bad 'Prefix 为空会直接写业务库 —— 拒绝继续'; exit 1 }
}

# ---------------------------------------------------------------------------
# [2/6] 产物自证（每个库的 dump 都得在，且非空）
# ---------------------------------------------------------------------------
Step '[2/6] 产物自证：每个库都有非空 dump，且 SHA256 与备份当时一致'
$dumpsByDb = @{}
foreach ($d in @($mf.dumps)) { $dumpsByDb[[string]$d.db] = $d }
$missing = @()
$hashBad = @()
$hashChecked = 0
$declaredSha = 0
$noSha = @()
foreach ($db in $dbList) {
    if (-not $dumpsByDb.ContainsKey($db)) { $missing += "$db(manifest 里没登记)"; continue }
    $d = $dumpsByDb[$db]
    $p = Join-Path $BackupDir ([string]$d.file)
    if (-not (Test-Path $p)) { $missing += "$db(文件不存在)"; continue }
    $len = (Get-Item -LiteralPath $p).Length
    if ($len -le 0) { $missing += "$db(文件为空)"; continue }
    # 第二层：产物文件 SHA256（防"拿到的文件已被改坏/截断"，与 manifest 记录比）。
    # ★ 不能写 `$d.ContainsKey('sha256')`：ConvertFrom-Json 产出的是 **PSCustomObject**，
    #   它没有 ContainsKey 方法 —— 该调用抛 MethodNotFound，而 $ErrorActionPreference='Continue'
    #   会把它当非终止错误吃掉，于是 $wantSha 恒为空、这一层**静默没验**，
    #   只留下一行"没有 sha256"的 WARN（实测踩中：manifest 明明 6/6 都写了 sha256）。
    #   判 PSCustomObject 有没有某个属性，只能用 PSObject.Properties.Name。
    $wantSha = ''
    if ($d.PSObject.Properties.Name -contains 'sha256') { $wantSha = [string]$d.sha256 }
    if ($wantSha) {
        $declaredSha++
        $gotSha = (Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash
        if ($gotSha -eq $wantSha) { $hashChecked++ }
        else { $hashBad += "$db.sql（manifest $($wantSha.Substring(0,12))…，实际 $($gotSha.Substring(0,12))…）" }
    } else { $noSha += $db }
    Ok ("{0} → {1}（{2:N0} 字节）" -f $db, $d.file, $len)
}
if ($hashBad.Count -gt 0) { Bad ("产物哈希不一致：{0}" -f ($hashBad -join '; ')) }
if ($noSha.Count -gt 0) {
    Say ("  [WARN] {0} 份产物在 manifest 里没有 sha256（旧版本备份）—— 这一层没验上：{1}" -f $noSha.Count, ($noSha -join ', ')) 'Yellow'
}
# ★ 自证（防"判据层静默失效"）：manifest 声明了 sha256，就必须真的比对过 ——
#   "声明了但一份都没验成"和"根本没有这条判据"是两件事，前者必须报红。
if ($declaredSha -gt 0) {
    if ($hashChecked -eq 0) {
        Bad ("manifest 声明了 {0} 份 sha256，却一份都没比对成功 —— 判据层**静默失效**（不是「跳过了这一层」）" -f $declaredSha)
    } else {
        Ok ("产物哈希校验通过 {0}/{1} 份（SHA256 与备份当时一致）" -f $hashChecked, $declaredSha)
    }
}
if ($missing.Count -gt 0) { Bad ("产物不全：{0}" -f ($missing -join '; ')); exit 1 }

# ---------------------------------------------------------------------------
# [3/6] 建临时库
# ---------------------------------------------------------------------------
Step '[3/6] 建临时库（<前缀><库名>）'
$dstDbOf = @{}
foreach ($db in $dbList) {
    $dst = $Prefix + $db
    $dstDbOf[$db] = $dst
    $sql = ('DROP DATABASE IF EXISTS `{0}`; CREATE DATABASE `{0}` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;' -f $dst)
    try { [void](Invoke-Sql -Sql $sql); Ok "已建 $dst（先 DROP 再 CREATE，保证演练从零开始）" }
    catch { Bad "建库失败 $dst ：$_"; exit 1 }
}

# ---------------------------------------------------------------------------
# [4/6] 逐库导入
# ---------------------------------------------------------------------------
Step '[4/6] 逐库导入 dump（容器内执行，避开 PowerShell 管道的编码改写）'
& docker exec $MysqlContainer sh -c 'rm -rf /tmp/zzrestore && mkdir -p /tmp/zzrestore' 2>$null | Out-Null
foreach ($db in $dbList) {
    $hostFile = Join-Path $BackupDir ([string]$dumpsByDb[$db].file)
    $inner = "$db.sql"
    & docker cp $hostFile ($MysqlContainer + ':/tmp/zzrestore/' + $inner) 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) { Bad "docker cp 失败：$db"; continue }
    $cmd = 'mysql -u' + $MysqlUser + ' -p' + $MysqlPassword + ' --default-character-set=utf8mb4 ' +
        $dstDbOf[$db] + ' < /tmp/zzrestore/' + $inner
    & docker exec $MysqlContainer sh -c $cmd 2>$null
    if ($LASTEXITCODE -ne 0) { Bad "导入失败（exit=$LASTEXITCODE）：$db" } else { Ok "已导入 → $($dstDbOf[$db])" }
}
if ($script:fail -gt 0) { Say ''; Bad '导入阶段已失败，不再进行比对（避免把"没导进去"混进"内容不一致"）'; exit 1 }

# ---------------------------------------------------------------------------
# [5/6] 逐表比对行数（★ 结论就在这里）
# ---------------------------------------------------------------------------
Step '[5/6] 逐表比对行数（恢复库 vs 备份那一刻的行数快照）'
$fmt = 'SELECT ''{0}'' AS t, COUNT(*) AS c FROM `{1}`.`{2}`'
$checkedTables = 0
$checkedRows = 0
$diffs = New-Object System.Collections.ArrayList

foreach ($db in $dbList) {
    $dst = $dstDbOf[$db]
    $tables = @($byDb[$db] | Sort-Object { $_.Table })
    if ($tables.Count -eq 0) { Bad "$db 的行数快照为空"; continue }

    # 一条查询取回该库全部表的计数（快照里的表清单 = **当时备份的那些表**，
    # 不从恢复库自己读表名 —— 否则"少恢复了一张表"会因为"没这张表所以不查"而消失）
    $parts = @()
    foreach ($t in $tables) { $parts += ($fmt -f $t.Table, $dst, $t.Table) }
    $rows = @()
    try { $rows = Invoke-Sql -Sql ($parts -join ' UNION ALL ') }
    catch { Bad ("读取恢复库行数失败（{0}）：{1}" -f $dst, $_); continue }

    $got = @{}
    foreach ($line in $rows) {
        if (-not $line) { continue }
        $kv = $line -split "`t"
        if ($kv.Count -ge 2) { $got[$kv[0]] = [int64]$kv[1] }
    }

    $badHere = 0
    foreach ($t in $tables) {
        $checkedTables++
        if (-not $got.ContainsKey($t.Table)) {
            [void]$diffs.Add("$db.$($t.Table)：恢复库里**没有这张表**（备份时有 $($t.Rows) 行）")
            $badHere++
            continue
        }
        $now = $got[$t.Table]
        $checkedRows += $t.Rows
        if ($now -ne $t.Rows) {
            [void]$diffs.Add("$db.$($t.Table)：备份 $($t.Rows) 行，恢复后 $now 行")
            $badHere++
            continue
        }
    }
    if ($badHere -eq 0) {
        # ★ 不要用 `$tables | Measure-Object -Property Rows -Sum`：
        #   $tables 的元素是 hashtable（@{}），它的「属性」是 Keys/Values/Count，
        #   `Rows` 只是**键** —— Measure-Object 会抛 GenericMeasurePropertyNotFound，
        #   而那个异常走的是错误流：脚本**不会**因它失败，只是把合计值渲染成空串，
        #   于是输出看起来"有个位置空着"而已（本次实测就是这样，属于静默失效）。
        $sumRows = 0
        foreach ($x in $tables) { $sumRows += [int64]$x.Rows }
        Ok ("{0}：{1} 张表逐张一致（合计 {2:N0} 行）" -f $db, $tables.Count, $sumRows)
    }
    else { Bad ("{0}：{1} 张表不一致" -f $db, $badHere) }
}

foreach ($d in $diffs) { Say ("         · " + $d) 'Yellow' }

# ---------------------------------------------------------------------------
# [6/6] 汇总 + 清理
# ---------------------------------------------------------------------------
Step '[6/6] 汇总与清理'

if ($checkedTables -eq 0) {
    # "比对了 0 张表"绝不能算通过 —— 这正是本项目反复栽的"空结果与链路没接通长得一样"
    Bad '一张表都没比对到 —— 演练没有产生任何证据，不算通过'
} else {
    Say ("  比对了 {0} 张表 / {1:N0} 行；差异 {2} 处。" -f $checkedTables, $checkedRows, $diffs.Count) `
        $(if ($diffs.Count -eq 0) { 'Green' } else { 'Red' })
}

if ($Keep) {
    Say ("  已保留临时库（前缀 {0}），请人工查看后自行删除：" -f $Prefix) 'Yellow'
    foreach ($db in $dbList) { Say ("    DROP DATABASE ``{0}``;" -f $dstDbOf[$db]) 'Yellow' }
} else {
    foreach ($db in $dbList) {
        try { [void](Invoke-Sql -Sql ('DROP DATABASE IF EXISTS `{0}`;' -f $dstDbOf[$db])); }
        catch { Bad "删除临时库失败 $($dstDbOf[$db])：" }
    }
    # 自证：临时库必须真的都删掉了（"删不掉要报红"，不许静默留一堆垃圾库）
    $like = ($Prefix + '%')
    $left = @()
    try { $left = @(Invoke-Sql -Sql ("SELECT schema_name FROM information_schema.schemata WHERE schema_name LIKE '{0}'" -f $like)) } catch { }
    $left = @($left | Where-Object { $_ -ne '' })
    if ($left.Count -eq 0) { Ok '临时库已全部清理（回读 information_schema 自证）' }
    else { Bad ("临时库仍有残留：{0}" -f ($left -join ', ')) }
}
& docker exec $MysqlContainer sh -c 'rm -rf /tmp/zzrestore' 2>$null | Out-Null

Say ''
Say '====================================================================' $(if ($script:fail -eq 0) { 'Green' } else { 'Red' })
if ($script:fail -eq 0) {
    Say ' 演练结论：全绿 —— 这份备份**可以**恢复，且内容与备份那一刻逐表一致。' 'Green'
} else {
    Say (" 演练结论：失败 {0} 处 —— 这份备份**不可信**，请先查清再依赖它。" -f $script:fail) 'Red'
}
Say '====================================================================' $(if ($script:fail -eq 0) { 'Green' } else { 'Red' })
Say ''

if ($script:fail -gt 0) { exit 1 }
exit 0
