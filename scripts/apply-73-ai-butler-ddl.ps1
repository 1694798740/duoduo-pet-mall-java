# 作用：把 AI 管家的增量 DDL（73 + 74）安全应用到运行中的 MySQL 容器，
#       并**连跑两遍**验证幂等（第二遍不允许出现 Duplicate column / ERROR）。
#
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\apply-73-ai-butler-ddl.ps1
#   可选参数：
#     -Container duoduo-mysql
#     -DbUser duoduo -DbPassword duoduo
#     -Files @('docs/sql/incremental/73-ai-butler-memory-and-tools.sql',
#              'docs/sql/incremental/74-ai-butler-audit.sql')
#
# 为什么不是 Flyway/Liquibase：
#   * 本仓库的库表由容器初始化 SQL 与 docs/sql/incremental 统一管理，
#     没有迁移框架的历史表；只给一个模块接 Flyway 会造出第二套版本事实源；
#   * 73/74 里的动态 SQL（information_schema 判重 + PREPARE）本身就是幂等的，
#     缺的不是版本账本，而是「有人真的执行过」的证据。
#   因此这里做两件事：
#     1) 本脚本负责**执行**并可重复执行；
#     2) AiSchemaGuard（Java 启动自检）负责**验证**——缺表/缺列直接启动失败，
#        而不是让审计写入静默失败。
#
# 幂等性验证方法（脚本已内置）：
#   * 每个文件执行两遍；第二遍输出中不得出现 "ERROR" 或 "Duplicate column"；
#   * 执行前后各查一次目标列/索引，指纹必须一致（多一列/少一列都算失败）。

param(
    [string]$Container = 'duoduo-mysql',
    [string]$DbUser = 'duoduo',
    [string]$DbPassword = 'duoduo',
    [string[]]$Files = @(
        'docs/sql/incremental/73-ai-butler-memory-and-tools.sql',
        'docs/sql/incremental/74-ai-butler-audit.sql'
    )
)

$ErrorActionPreference = 'Continue'
# 管道传给 docker exec 时，PowerShell 默认按 ASCII 编码 —— SQL 里的中文注释会被截坏。
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$pass = 0
$fail = 0

# 原生命令的参数先拼成完整字符串：PowerShell 5.1 对 "-u$DbUser" 这种紧邻写法
# 在部分调用形态下会把变量当字面量传出去（本脚本首跑就踩到：mysql 收到用户 "$DbUser"）。
$userArg = "-u$DbUser"

function Check([string]$name, [bool]$ok, $extra) {
    if ($ok) {
        $script:pass++
        Write-Host "  [PASS] $name"
    } else {
        $script:fail++
        if ($null -ne $extra) { Write-Host "  [FAIL] $name -> $extra" } else { Write-Host "  [FAIL] $name" }
    }
}

# 容器必须在跑：DDL 的目标是真实库，不是本地文件
$ps = docker ps --filter "name=$Container" --format "{{.Names}}" 2>$null
Check "MySQL 容器 $Container 在运行" ("$ps".Trim() -eq $Container) $ps
if ($fail -gt 0) { exit 1 }

# 目标结构指纹：只查「我们关心的列/索引」，用它证明第二遍没有副作用
$fingerprintSql = @"
SELECT CONCAT(TABLE_NAME, '.', COLUMN_NAME)
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA='duoduo_ai'
   AND ((TABLE_NAME='t_ai_user_memory'   AND COLUMN_NAME='memory_json')
     OR (TABLE_NAME='t_ai_chat_message'  AND COLUMN_NAME IN ('page_url','card_refs'))
     OR (TABLE_NAME='t_ai_tool_call_log' AND COLUMN_NAME IN ('page_url','badge_patch')))
 ORDER BY TABLE_NAME, COLUMN_NAME;
SELECT CONCAT(TABLE_NAME, '.', INDEX_NAME)
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA='duoduo_ai'
   AND ((TABLE_NAME='t_ai_tool_call_log' AND INDEX_NAME IN ('uk_request_tool','idx_request_tool'))
     OR (TABLE_NAME='t_ai_memory_event'  AND INDEX_NAME='idx_user_time'))
 ORDER BY TABLE_NAME, INDEX_NAME;
SELECT TABLE_NAME FROM information_schema.TABLES
 WHERE TABLE_SCHEMA='duoduo_ai' AND TABLE_NAME='t_ai_memory_event';
"@

function Get-Fingerprint() {
    $out = $fingerprintSql | docker exec -i -e "MYSQL_PWD=$DbPassword" $Container mysql -N -B $userArg 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    return ($out | Where-Object { "$_".Trim() -ne '' }) -join "`n"
}

function Invoke-SqlFile([string]$file, [int]$round) {
    # 必须显式 -Encoding UTF8：Windows PowerShell 5.1 对无 BOM 的 UTF-8 文件
    # 默认按 ANSI 读，中文注释会被解成乱码，其中某些字节会变成单引号 ——
    # SQL 字符串字面量因此提前闭合（首跑实测：报错位置正好在 COMMENT 之后）。
    $raw = Get-Content -Raw -Encoding UTF8 -Path $file
    # 密码走 MYSQL_PWD 环境变量：既没有命令行密码警告，也不会因为 stderr 警告被
    # PowerShell 包成 NativeCommandError（那会让「执行成功」被误判为失败）
    $out = $raw | docker exec -i -e "MYSQL_PWD=$DbPassword" $Container mysql --default-character-set=utf8mb4 $userArg 2>&1
    $text = ($out | Out-String)
    $bad = $LASTEXITCODE -ne 0 -or $text -match 'ERROR \d+' -or $text -match 'Duplicate column'
    Check "$([System.IO.Path]::GetFileName($file)) 第 $round 遍执行成功" (-not $bad) $text
}

# 逐文件执行：第一遍 → 指纹 → 第二遍 → 指纹，两次指纹必须完全一致
foreach ($file in $Files) {
    if (-not (Test-Path $file)) {
        Check "SQL 文件存在：$file" $false $file
    }
}
foreach ($file in $Files) {
    if (Test-Path $file) { Invoke-SqlFile $file 1 }
}
$afterFirst = Get-Fingerprint

foreach ($file in $Files) {
    if (Test-Path $file) { Invoke-SqlFile $file 2 }
}
$afterSecond = Get-Fingerprint

Check "两遍执行后结构指纹一致（幂等）" `
    ($null -ne $afterFirst -and $afterFirst -eq $afterSecond) `
    "first=[$afterFirst] second=[$afterSecond]"
$after = $afterSecond
Check "关键列已存在（memory_json/page_url/card_refs/badge_patch）" `
    ($after -match 't_ai_user_memory.memory_json' -and $after -match 't_ai_chat_message.card_refs' `
        -and $after -match 't_ai_tool_call_log.badge_patch') $after
Check "t_ai_memory_event 已建表" ($after -match 't_ai_memory_event') $after

Write-Host ""
Write-Host "=== DDL 结果：通过 $pass 项，失败 $fail 项 ==="
if ($fail -gt 0) { exit 1 }
exit 0
