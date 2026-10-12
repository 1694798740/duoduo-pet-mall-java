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

    自证（本项目最常栽的一类：解析失效却报 OK）：
      · 文档标记块没找到、或块内一个开关名都没抽到 → **判违规**，而不是"没意见"；
      · `ops-switch.ps1` 清单没解析出项 → **判违规**。
      否则「文档被改坏了 / 正则失配」会表现为"全绿"。

    退出码：0 = 通过；1 = 有违规或自证失败。

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\lib\check-ops-doc.ps1
#>
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
# [0/2] 自证：两个输入都必须在
# ---------------------------------------------------------------------------
if (-not (Test-Path -LiteralPath $docPath)) { Bad "找不到文档：$docPath"; exit 1 }
if (-not (Test-Path -LiteralPath $switchPath)) { Bad "找不到开关清单脚本：$switchPath"; exit 1 }
$docText = [string]::Join("`n", @(Get-Content -LiteralPath $docPath -Encoding UTF8))
Write-Host ("  文档：{0} 字节；清单：{1}" -f $docText.Length, $switchPath)

# ---------------------------------------------------------------------------
# [1/2] 文档里引用的每个 scripts 路径都必须真实存在
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
# [2/2] 文档开关表 ↔ ops-switch.ps1 $SwitchList 双向一致
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

Write-Host ''
if ($fail -eq 0) {
    Write-Host ' 结论：通过 —— 文档引用的脚本都存在，开关表与清单双向一致。' -ForegroundColor Green
    exit 0
} else {
    Write-Host (" 结论：失败 {0} 处。" -f $fail) -ForegroundColor Red
    exit 1
}
