<#
  守卫：Markdown 导入飞书的两条「吞行/吞字」书写陷阱

  ★ 本守卫守**同一族缺陷的两种形态**（都是「源文件看起来正常、只有飞书导入才丢」）：

  ── 形态一：列表续行以「行首裸反引号」开头 ──────────────────────────────
  （2026-10-12 实测，代价是一次真实的云端整行丢失）

    README.md 里有一处**跨行的列表项**，第二行以**两个反引号**开头：

        - `docs/PLAN.md:1470`：原文用 `\`` 在代码段里嵌套反引号（非标准），改为标准写法
          **双反引号包裹**：`` ``{cat.depth > 0 ? `└ ${cat.name}` : cat.name}`` ``
          （改前那一行会让紧随其后的一句在导入飞书时被整行吞掉）；

    这段在 GitHub 上渲染正常、导入腾讯文档也正常，但**导入飞书时第三行被整行丢掉**。
    已用「换行位置 / 是否有内联代码 / 是否有嵌套反引号 / 是否加空行」四组变量逐一排除，
    并做了三次重导验证：唯一能稳定复现的触发条件是 ——
    **某一行以裸反引号定界符开头**（该行出现 ≥2 个反引号、且不是正常的行内代码）。

  ── 形态二：表格单元格里「行内代码含未转义竖线」 ────────────────────────
  （2026-10-12 实测，代价是一次真实的云端吞字 —— 本守卫上线后立刻抓到的第一例）

    表格行里这样写：

        | a | 扫全仓 `.md`（跳过 `node_modules|target|dist`），判「x」 |

    单元格里的 `node_modules|target|dist` 是**行内代码**，但其中的 `|` **没有转义**。
    GitHub 与腾讯文档都能正确识别（当作代码内容），**飞书却把它当成单元格分隔符** ——
    于是从该 `|` 之后的内容全部丢失（实测整格文字被截断）。
    最小复现（4 个探针表，各导入飞书回读）：
      A 含未转义竖线的行内代码 ⇒ **丢失**；B 转义成 `\|` ⇒ 正常；
      C 单元格内无竖线 ⇒ 正常；D 普通行内代码 ⇒ 正常。
    ⇒ 判据：**表格行 + 单元格内的行内代码里存在未转义竖线**。

  ★ 为什么形态一不能放宽成"行首是行内代码"：
      一开始写成"续行以 `` x `` 开头即违规"，全仓命中 **85 处**（README 29、PLAN 40…），
      全是**正常写法**（列表项后的说明行用行内代码引用标识符）。
      那种判据会把守卫变成噪音，最后没人看 —— 按「同一判据只留一个出口」的原则，
      只保留**实测能复现的那一种形态**。
    ★ 形态二则相反：全仓本就**全部已正确转义**（`\|`），命中数为 0 —— 它是"新写的内容容易踩"，
      不是"存量有噪音"，因此可以放心判。

  ★ 判据摘要：
      形态一：① 行首（允许空格缩进）第 1 个非空字符是**连续 ≥2 个反引号**（裸定界符）；
              —— 计数法不成立：`` ``{...}`` `` 一共 10 个反引号是偶数，自我配对后可全消，
                 但它在飞书导入时**确实**会吞行；所以判据必须是"行首连续个数"而不是"总个数奇偶"。
              ② 该行是**列表项的续行**（上一行非空、且上一行以 `-` / `*` / `+` / `1.` 开头）。
      形态二：表格行（去空白后以 `|` 开头）+ 任一「成对反引号片段」内部含未转义 `|`。

  ★ 自证纪律（铁律 29）：扫描到的 .md 文件数为 0 判**违规**，不降级为 WARN。
     ★ 豁免：``` 围栏内部不参与扫描 —— 那里反引号是内容不是语法。

  退出码：0 = 通过；1 = 违规。
#>
$ErrorActionPreference = 'Stop'

# 本文件位于 <repo>/scripts/lib/ 下 —— 往上**三级**是仓库根
$repo = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSCommandPath))

$fail = 0
function Bad([string]$m) { Write-Host "  [FAIL] $m" -ForegroundColor Red; $script:fail++ }
function Ok([string]$m)  { Write-Host "  [OK]   $m" -ForegroundColor Green }
function Say([string]$m, [string]$c = 'Gray') { Write-Host $m -ForegroundColor $c }

Say ''
Say '[check-md-bare-tick] Markdown 导入飞书的两条吞行/吞字守卫' 'Cyan'

# ── 扫描范围：仓库内全部 .md（跳过 node_modules / target / dist / .git / 截图目录）──
$skipDirPattern = '\\(node_modules|target|dist|\.git|screenshots)\\'
$files = @(
    Get-ChildItem -Path $repo -Recurse -Filter '*.md' -File -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch $skipDirPattern }
)
Say ("  仓库根：{0}" -f $repo)
Say ("  扫描 .md 文件数：{0}" -f $files.Count)

# ★ 自证：一个文件都没扫到 ⇒ 违规（路径写错时守卫会静默通过）
if ($files.Count -le 0) {
    Bad '一个 .md 都没扫到 —— 守卫整体失效（不是"仓库里没有文档"）'
    Say ''
    Say '结论：违规 ❌'
    exit 1
}

# 行首是否为**连续 ≥2 个反引号**（= 行首裸定界符）
#   实测触发形态：`` ``{...}`` ``、``` ... ```
#   正常的 `xxx` 行内代码只会是 1 个反引号开头，不命中。
function Test-BareTickAtStart([string]$s) {
    $tick = [char]0x0060
    $i = 0
    while ($i -lt $s.Length -and $s[$i] -eq ' ') { $i++ }
    $n = 0
    while ($i -lt $s.Length -and $s[$i] -eq $tick) { $n++; $i++ }
    return ($n -ge 2)
}

# 表格行单元格内的行内代码是否含**未转义竖线**（形态二）。
#   判据：**不切单元格**，直接扫整行 ——
#     维护一个「是否处于成对反引号之间」的状态；只要在**反引号区间内**遇到**未转义**的
#     `|`，就说明这行里某个行内代码把裸竖线包了进来（飞书会把它当单元格分隔符、截断该格）。
#   ★ 为什么不能先按竖线切单元格再判：切分本身就会把那个裸竖线当分隔符吃掉，
#     切开后每格都"干净"，判据恒假（实测第一版就这么漏判的）。
#   ★ `\|`（反斜杠+竖线）是正确写法，飞书也能正确识别，不算违规。
function Test-InlineCodeWithRawPipe([string]$line) {
    $t = $line.Trim()
    if (-not $t.StartsWith('|')) { return $false }
    $tick = [char]0x0060
    $inCode = $false
    for ($i = 0; $i -lt $t.Length; $i++) {
        $ch = $t[$i]
        if ($ch -eq $tick) { $inCode = -not $inCode; continue }
        if ($inCode -and $ch -eq '|' -and $t[$i - 1] -ne '\') { return $true }
    }
    return $false
}

$hits = New-Object System.Collections.Generic.List[string]
$hitsPipe = New-Object System.Collections.Generic.List[string]
$scannedLines = 0

foreach ($f in $files) {
    $lines = [System.IO.File]::ReadAllLines($f.FullName, [System.Text.Encoding]::UTF8)
    $inFence = $false
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        $scannedLines++
        # 围栏切换（``` 或 ~~~ 开头，允许前置空白）
        if ($line -match '^\s{0,3}(```|~~~)') { $inFence = -not $inFence; continue }
        if ($inFence) { continue }

        $rel = $f.FullName.Substring($repo.Length).TrimStart('\')

        # ── 形态二：表格单元格内行内代码含未转义竖线 ──
        if (Test-InlineCodeWithRawPipe $line) {
            $hitsPipe.Add(("{0}:{1}: {2}" -f $rel, ($i + 1), $line.Trim()))
        }

        # ── 形态一：列表续行以行首裸反引号开头 ──
        if (-not (Test-BareTickAtStart $line)) { continue }  # ① 行首是连续 ≥2 个反引号

        # ② 只判**列表项的续行**
        if ($i -eq 0) { continue }
        $prev = $lines[$i - 1]
        if ($prev.Trim() -eq '') { continue }                        # 空行 ⇒ 新段落
        if ($prev -notmatch '^\s{0,8}([-*+]|\d+\.)\s') { continue }  # 上一行不是列表项

        $hits.Add(("{0}:{1}: {2}" -f $rel, ($i + 1), $line.Trim()))
    }
}

Say ("  扫描行数：{0}" -f $scannedLines)
Say ''

if ($hits.Count -eq 0 -and $hitsPipe.Count -eq 0) {
    Ok '形态一（列表续行以裸反引号开头）：0 处 —— 飞书导入不会因此吞行'
    Ok '形态二（表格单元格行内代码含未转义竖线）：0 处 —— 飞书导入不会因此吞字'
    Say ''
    Say '结论：通过 ✅'
    exit 0
}

if ($hits.Count -gt 0) {
    Bad ("形态一：发现 {0} 处「列表续行以裸反引号开头」—— 导入飞书时该行会被整行吞掉：" -f $hits.Count)
    foreach ($h in $hits) { Say ("    {0}" -f $h) 'Yellow' }
    Say ''
    Say '  处置：把该列表项收进同一行，或让续行**不以反引号开头**' 'Gray'
    Say '        （例如把表达式改写成文字描述，或整段收进围栏代码块）。' 'Gray'
    Say ''
}

if ($hitsPipe.Count -gt 0) {
    Bad ("形态二：发现 {0} 处「表格单元格里行内代码含未转义竖线」—— 导入飞书时该格会从竖线处被截断：" -f $hitsPipe.Count)
    foreach ($h in $hitsPipe) { Say ("    {0}" -f $h) 'Yellow' }
    Say ''
    Say '  处置：把单元格里行内代码内的竖线转义成 \|（反斜杠+竖线）。' 'Gray'
    Say '        例：`node_modules|target|dist` → `node_modules\|target\|dist`' 'Gray'
    Say '        （GitHub / 腾讯文档两者都认；飞书只认转义写法。）' 'Gray'
    Say ''
}

Say '结论：违规 ❌'
exit 1
