<#
  守卫：Markdown「行首裸反引号」续行守卫

  为什么需要它（2026-10-12 实测，代价是一次真实的云端内容丢失）：

    README.md 里有一处**跨行的列表项**，第二行以**两个反引号**开头：

        - `docs/PLAN.md:1470`：原文用 `\`` 在代码段里嵌套反引号（非标准），改为标准写法
          **双反引号包裹**：`` ``{cat.depth > 0 ? `└ ${cat.name}` : cat.name}`` ``
          （改前那一行会让紧随其后的一句在导入飞书时被整行吞掉）；

    这段在 GitHub 上渲染正常、导入腾讯文档也正常，但**导入飞书时第三行被整行丢掉**。
    已用「换行位置 / 是否有内联代码 / 是否有嵌套反引号 / 是否加空行」四组变量逐一排除，
    并做了三次重导验证：唯一能稳定复现的触发条件是 ——
    **某一行以裸反引号定界符开头**（该行出现 ≥2 个反引号、且不是正常的行内代码）。

  ★ 为什么不能放宽成"行首是行内代码"：
      一开始写成"续行以 `` x `` 开头即违规"，全仓命中 **85 处**（README 29、PLAN 40…），
      全是**正常写法**（列表项后的说明行用行内代码引用标识符）。
      那种判据会把守卫变成噪音，最后没人看 —— 按「同一判据只留一个出口」的原则，
      只保留**实测能复现的那一种形态**。

  ★ 判据（两条同时成立）：
      ① 行首（允许空格缩进）第 1 个非空字符是**连续 ≥2 个反引号**（裸定界符）；
         —— 计数法不成立：`` ``{...}`` `` 一共 10 个反引号是偶数，自我配对后可全消，
            但它在飞书导入时**确实**会吞行；所以判据必须是"行首连续个数"而不是"总个数奇偶"。
      ② 该行是**列表项的续行**（上一行非空、且上一行以 `-` / `*` / `+` / `1.` 开头）。

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
Say '[check-md-bare-tick] Markdown「行首裸反引号」续行守卫' 'Cyan'

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

$hits = New-Object System.Collections.Generic.List[string]
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

        if (-not (Test-BareTickAtStart $line)) { continue }  # ① 行首是连续 ≥2 个反引号

        # ③ 只判**列表项的续行**
        if ($i -eq 0) { continue }
        $prev = $lines[$i - 1]
        if ($prev.Trim() -eq '') { continue }                        # 空行 ⇒ 新段落
        if ($prev -notmatch '^\s{0,8}([-*+]|\d+\.)\s') { continue }  # 上一行不是列表项

        $rel = $f.FullName.Substring($repo.Length).TrimStart('\')
        $hits.Add(("{0}:{1}: {2}" -f $rel, ($i + 1), $line.Trim()))
    }
}

Say ("  扫描行数：{0}" -f $scannedLines)
Say ''

if ($hits.Count -eq 0) {
    Ok '没有「列表续行以裸反引号开头」的写法（飞书导入不会因此吞行）'
    Say ''
    Say '结论：通过 ✅'
    exit 0
}

Bad ("发现 {0} 处「列表续行以裸反引号开头」—— 导入飞书时该行会被整行吞掉：" -f $hits.Count)
foreach ($h in $hits) { Say ("    {0}" -f $h) 'Yellow' }
Say ''
Say '  处置：把该列表项收进同一行，或让续行**不以反引号开头**' 'Gray'
Say '        （例如把表达式改写成文字描述，或整段收进围栏代码块）。' 'Gray'
Say ''
Say '结论：违规 ❌'
exit 1
