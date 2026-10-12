<#
.SYNOPSIS
    运维手册守卫：`docs/OPS.md` 里提到的每个脚本都必须真实存在；文档的开关表必须与
    `scripts/ops-switch.ps1` 的权威清单**双向一致**。

.DESCRIPTION
    为什么需要它 —— 运维手册属于「最易腐烂且无门禁」的一类文档：

      · 它大量引用 `scripts\xxx.ps1`。脚本改名/删除后，文档不会报错、不会有人发现，
        直到某天出事时照着它敲命令、发现"文件不存在"，而那时候没人有余力去查文档。
      · 它里面那张开关表是「运维侧对开关的全部认知」。如果 `ops-switch.ps1` 加了新开关
        而表里没写，运维就永远不知道有这个东西（**漏一个 = 少一条排障手段**）；
        反之表里写了清单里没有的开关，运维会去 `.env` 里改一个**根本不生效**的变量。

    两条断言：
      ① 文档里出现的每个 `scripts/...` 路径必须能在仓库里找到（双向不存在即失败）；
      ② 文档标记块内出现的开关名集合 == `ops-switch.ps1` 的 `$SwitchList` 的名字集合
         （少一个、多一个都失败）。
      ③ 备份/恢复脚本里**承诺过的判据都还在**（见 [3/3]）。这一条与文档直接相关：
         `docs/OPS.md` 向运维承诺了"心跳表要显式声明 / 异常也要告警 / 上传卷缺段即判红"，
         而这些判据全都住在脚本源码里 —— **删掉它们没有任何编译期征兆**，而"少一半判据"
         的症状正好是「看起来还在跑、结论全绿」。

    自证（本项目最常栽的一类：解析失效却报 OK）：
      · 文档标记块没找到、或块内一个开关名都没抽到 → **判违规**，而不是"没意见"；
      · `ops-switch.ps1` 清单没解析出项 → **判违规**；
      · ③ 一节如果一条判据都没扫到 → **判违规**（「什么都没扫到」与「扫了、结果干净」一模一样）。
      否则「文档被改坏了 / 正则失配」会表现为"全绿"。

    退出码：0 = 通过；1 = 有违规或自证失败。

.PARAMETER BackupScript / RestoreScript
    仅用于**反向验证**：指向一份故意改坏的脚本副本，断言本守卫会报红。
    不给参数时用仓库里的真实路径。

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\lib\check-ops-doc.ps1
#>
param(
    [string]$BackupScript,
    [string]$RestoreScript
)
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)   # scripts/lib -> scripts -> repo
$docPath = Join-Path $repo 'docs\OPS.md'
$switchPath = Join-Path $repo 'scripts\ops-switch.ps1'

$fail = 0
function Bad([string]$m) { Write-Host ("  [FAIL] " + $m) -ForegroundColor Red; $script:fail++ }
function Ok([string]$m) { Write-Host ("  [OK]   " + $m) -ForegroundColor DarkGray }

Write-Host '[check-ops-doc] 运维手册一致性守卫'

# ---------------------------------------------------------------------------
# [0/3] 自证：两个输入都必须在
# ---------------------------------------------------------------------------
if (-not (Test-Path -LiteralPath $docPath)) { Bad "找不到文档：$docPath"; exit 1 }
if (-not (Test-Path -LiteralPath $switchPath)) { Bad "找不到开关清单脚本：$switchPath"; exit 1 }
$docText = [string]::Join("`n", @(Get-Content -LiteralPath $docPath -Encoding UTF8))
Write-Host ("  文档：{0} 字节；清单：{1}" -f $docText.Length, $switchPath)

# ---------------------------------------------------------------------------
# [1/3] 文档里引用的每个 scripts 路径都必须真实存在
# ---------------------------------------------------------------------------
$pathRx = [regex]'scripts[\\/][A-Za-z0-9_][A-Za-z0-9_./\\-]*\.(ps1|sh|mjs|js|sql|cmd)'
$refs = New-Object System.Collections.Generic.HashSet[string]
foreach ($m in $pathRx.Matches($docText)) {
    # 归一化反斜杠 → 正斜杠（文档里两种写法都有）
    $p = $m.Value -replace '\\', '/'
    [void]$refs.Add($p)
}
if ($refs.Count -eq 0) {
    Bad '文档里一个 scripts 路径都没解析出来 —— 正则失配或文档被改坏（按违规处理）'
} else {
    Write-Host ("  文档引用脚本 {0} 个" -f $refs.Count)
    foreach ($rel in ($refs | Sort-Object)) {
        $abs = Join-Path $repo ($rel -replace '/', '\')
        if (Test-Path -LiteralPath $abs) { Ok "$rel 存在" }
        else { Bad "$rel —— 文档引用了一个**不存在**的脚本（改名/删除后没同步文档）" }
    }
}

# ---------------------------------------------------------------------------
# [2/3] 文档开关表 ↔ ops-switch.ps1 $SwitchList 双向一致
# ---------------------------------------------------------------------------
$begin = '<!-- OPS-SWITCH-TABLE-BEGIN -->'
$end = '<!-- OPS-SWITCH-TABLE-END -->'
$bi = $docText.IndexOf($begin)
$ei = $docText.IndexOf($end)
if ($bi -lt 0 -or $ei -lt 0 -or $ei -le $bi) {
    Bad '文档里找不到 OPS-SWITCH-TABLE 标记块（BEGIN/END）—— 无法核对开关表'
} else {
    $block = $docText.Substring($bi, $ei - $bi)
    $docNames = New-Object System.Collections.Generic.HashSet[string]
    foreach ($m in [regex]::Matches($block, 'DUODUO_[A-Z0-9_]+')) { [void]$docNames.Add($m.Value) }

    if ($docNames.Count -eq 0) {
        Bad '开关表标记块内没有解析出任何 DUODUO_ 变量名 —— 表被改坏或正则失配（按违规处理）'
    } else {
        # 解析 ops-switch.ps1 的 $SwitchList 名字（限定在 $SwitchList = @( ... ) 区块内，避免误匹配）
        $swLines = @(Get-Content -LiteralPath $switchPath -Encoding UTF8)
        $inList = $false
        $listNames = New-Object System.Collections.Generic.HashSet[string]
        foreach ($line in $swLines) {
            if (-not $inList) { if ($line -match '\$SwitchList\s*=\s*@\(') { $inList = $true }; continue }
            if ($line -match '^\s*\)\s*$') { $inList = $false; continue }
            $nm = [regex]::Match($line, "Name\s*=\s*'([A-Z][A-Z0-9_]*)'")
            if ($nm.Success) { [void]$listNames.Add($nm.Groups[1].Value) }
        }

        if ($listNames.Count -eq 0) {
            Bad 'ops-switch.ps1 的 $SwitchList 没解析出任何项 —— 清单被改坏或正则失配（按违规处理）'
        } else {
            Write-Host ("  清单 {0} 项；文档表 {1} 项" -f $listNames.Count, $docNames.Count)

            $missing = @($listNames | Where-Object { -not $docNames.Contains($_) } | Sort-Object)
            $extra = @($docNames | Where-Object { -not $listNames.Contains($_) } | Sort-Object)

            if ($missing.Count -eq 0) { Ok '清单每一项都在文档表里（无遗漏）' }
            else { Bad ("清单里有、文档表里没有（运维会不知道有这个开关）：`n         " + ($missing -join "`n         ")) }

            if ($extra.Count -eq 0) { Ok '文档表每一项都在清单里（无多写）' }
            else { Bad ("文档表里有、清单里没有（运维会去改一个不生效的变量）：`n         " + ($extra -join "`n         ")) }
        }
    }
}

# ---------------------------------------------------------------------------
# [3/3] 备份/恢复脚本的判据自证：**判据不能被悄悄删掉**
# ---------------------------------------------------------------------------
#   为什么单列一节：`docs/OPS.md` 向运维承诺了几条判据（心跳表要显式声明、异常也要告警、
#   上传文件卷缺段即判红）。这些判据都住在脚本源码里，删掉它们**没有任何编译期征兆**，
#   而"少一半判据"的症状正好是「看起来还在跑、结论全绿」—— 这是本项目反复栽的一类。
#   ★ 一条都没扫到 ⇒ 判违规（扫描失效与"扫了、结果干净"长得一模一样）。
$bk = $BackupScript
if (-not $bk) { $bk = Join-Path $repo 'scripts\db-backup.ps1' }
$rs = $RestoreScript
if (-not $rs) { $rs = Join-Path $repo 'scripts\ops-restore.ps1' }
foreach ($p in @($bk, $rs)) {
    if (-not (Test-Path -LiteralPath $p)) { Bad "找不到脚本：$p"; exit 1 }
}
$bkText = [string]::Join("`n", @(Get-Content -LiteralPath $bk -Encoding UTF8))
$rsText = [string]::Join("`n", @(Get-Content -LiteralPath $rs -Encoding UTF8))

$judges = @(
    @{ n = 'db-backup: 顶层 trap（未捕获异常也要告警）'; t = $bkText; rx = '(?m)^trap\s*\{';
       why = '没有它，任何未捕获异常会掀掉整个脚本：manifest / 汇总 / _ALERT.txt 一份都不产出' }
    @{ n = 'db-backup: 持续改写表名单非空'; t = $bkText; rx = '\$ChurningTableNames\s*=\s*@\(\s*''[^'']+''';
       why = '名单空了 → 心跳表退回"注定报红"的 CHECKSUM 判据（备份正确却天天报红）' }
    @{ n = 'db-backup: 主键现查 information_schema'; t = $bkText; rx = 'KEY_COLUMN_USAGE';
       why = '稳定投影判据靠它取主键；写死列名后一换主键就静默失效' }
    @{ n = 'db-backup: 名单解析出 0 张即判红'; t = $bkText; rx = '持续改写表名单自证';
       why = '名单解析失效时没人喊，判据静默退化' }
    @{ n = 'db-backup: 指纹函数允许空串'; t = $bkText; rx = '\[AllowEmptyString\(\)\]\[string\]\$Text';
       why = '空表会让指纹函数抛参数绑定异常，整轮备份没有结论' }
    @{ n = 'db-backup: 单条样本判据有 try/catch'; t = $bkText; rx = '判据代码抛异常';
       why = '一张表的判据炸了不该掀掉整轮备份' }
    @{ n = 'db-backup: manifest 写 volumes 段'; t = $bkText; rx = '\$manifest\.volumes\s*=';
       why = '恢复演练靠它判断"这份备份到底有没有备文件"' }
    @{ n = 'ops-restore: 缺 volumes 段即判红'; t = $rsText; rx = '没有 volumes 段';
       why = '缺了就变成把「没备」当成「验过了」' }
    @{ n = 'ops-restore: 一个卷都没验成即判红'; t = $rsText; rx = '\$volChecked';
       why = '没有证据不算通过' }
    @{ n = 'ops-restore: 临时卷目录用 docker exec -u 0'; t = $rsText; rx = 'docker exec -u 0';
       why = 'docker cp 落的文件属主是 root，业务用户删不掉且失败被静默吞掉' }
    @{ n = 'db-backup: 抽验「恢复一张立刻比一张」'; t = $bkText; rx = 'Invoke-SampleCompare\s+-S\s+\$rec';
       why = '改回"全部恢复完再一起比" ⇒ 跨库同名表在同一个临时库里互相覆盖，先恢复的那张「拿别人的数据跟自己比」（既有假红也有假绿）' }
)
$found = 0
foreach ($j in $judges) {
    if ($j.t -match $j.rx) { $found++ }
    else { Bad ("{0} —— 判据不见了：{1}" -f $j.n, $j.why) }
}
if ($found -eq 0) {
    Bad '备份/恢复判据一条都没扫到 —— 扫描失效（按违规处理）'
} else {
    Ok ("备份/恢复判据 {0}/{1} 条在位" -f $found, $judges.Count)
}

Write-Host ''
if ($fail -eq 0) {
    Write-Host ' 结论：通过 —— 文档引用的脚本都存在，开关表与清单双向一致，备份/恢复判据齐全。' -ForegroundColor Green
    exit 0
} else {
    Write-Host (" 结论：失败 {0} 处。" -f $fail) -ForegroundColor Red
    exit 1
}
