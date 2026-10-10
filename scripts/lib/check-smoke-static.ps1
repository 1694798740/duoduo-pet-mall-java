# 冒烟脚本静态自检（E20 起固化；Windows PowerShell 5.1 / pwsh 7 均须可跑）
#
# 用法： pwsh -NoProfile -File scripts/lib/check-smoke-static.ps1
#        退出码 0 = 全部通过；1 = 有违规（违规明细打印在 stdout）
#
# 为什么需要它：这两条规则违反之后**都不报错**，只表现为"某条断言悄悄算错"
# 或者"某个中文串在某台机器上变成乱码"，靠人眼 review 抓不住。项目里已经有过
# 一次"关键字顺带命中导致断言测不出任何东西"（E17 的 admin-smoke）的假绿事故，
# 这一条是同一族里的另一支。
#
# ---------- 规则 1：Check 的判据不能是"裸的比较表达式" ----------
# 反例（实测，静默判假且无任何报错）：
#     Check "缺令牌" (@($a,$b) | Where-Object {...}).Count -eq 0 "extra"
#   → $ok 拿到的是 `(...).Count` 这个数，`-eq 0` 被吞掉；
#     四个令牌全在、missing=0，断言却打印 [FAIL]。
# 正例（任选其一，实测均正确）：
#     $n = (@($a,$b) | Where-Object {...}).Count
#     Check "缺令牌" ($n -eq 0) "extra"
#
#     Check "冻结已清零" ((Sql 'db' "SELECT ...;") -eq '0')
#   ↑ 整体再包一层括号的形式**是安全的**，不要为了"统一风格"去改它：
#     实测 `((F) -eq '9')` 在不等时返回 False（不是"永远为真"）。
#
# 危险形状的判据（用 PowerShell 自己的解析器，不靠正则猜）：
#   命令的第 3 个实参文本**含右括号、但不以右括号结尾**
#   —— 说明括号表达式没能覆盖整个条件，后面还挂着 `.成员` 或 ` -运算符 右值`。
#
# ---------- 规则 2：.ps1 必须带 UTF-8 BOM ----------
# Windows PowerShell 5.1 会把**无 BOM** 的 UTF-8 文件按 ANSI 解码 →
# 脚本里的中文全变乱码。而"断言里的中文"往往正是断言文案与被匹配的字符串，
# 于是失败信息看不懂、或者断言恒不命中。
# （pwsh 7 默认按 UTF-8 读，所以这个坑只在 Windows 上炸 —— 本机跑、CI 不炸，
#   或者反过来，取决于谁在跑。）
#
# 刻意不做的事：不检查"断言数量""有没有写 Check"这类软要求 —— 那是评审的事，
# 静态规则只挡**确定性错误**，挡多了会被绕过（把守卫注释掉比改代码省事）。

param(
    [string]$RepoRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
)

$ErrorActionPreference = 'Continue'
$violations = 0

$scriptDir = Join-Path $RepoRoot 'scripts'
# ★ 排除 node_modules：那是 Playwright 自带的 .ps1（install_*.ps1 / playwright.ps1 …），
#   无 BOM 是上游的事，我们既不该改也不该被它拖红。规则只约束"我们自己的脚本"。
$files = @(Get-ChildItem -Path $scriptDir -Filter '*.ps1' -File -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch '[\\/]node_modules[\\/]' })

Write-Host ""
Write-Host "=== 冒烟脚本静态自检 @ $scriptDir ==="
Write-Host "扫描 *.ps1 共 $($files.Count) 个"

# ---------- 规则 1 ----------
Write-Host ""
Write-Host "> 规则 1：Check 判据不得是裸的比较表达式（静默误绑定）"
$rule1 = 0
foreach ($f in $files) {
    $errs = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($f.FullName, [ref]$null, [ref]$errs)
    if ($errs -and $errs.Count -gt 0) {
        Write-Host "  [FAIL] $($f.Name) 解析失败（$($errs.Count) 个语法错误）"
        $violations++
        $rule1++
        continue
    }
    $cmds = $ast.FindAll({
            param($n)
            $n -is [System.Management.Automation.Language.CommandAst] -and $n.GetCommandName() -eq 'Check'
        }, $true)
    foreach ($c in $cmds) {
        $el = $c.CommandElements
        if ($el.Count -lt 3) { continue }
        $cond = $el[2].Extent.Text
        # 含右括号、却不以右括号结尾 → 括号表达式没盖住整个条件
        if ($cond.Contains(')') -and (-not $cond.TrimEnd().EndsWith(')'))) {
            Write-Host "  [FAIL] $($f.Name):$($c.Extent.StartLineNumber)  判据=[$cond]"
            Write-Host "         改法一：先把表达式算进变量，再 Check '…' (`$n -eq …)"
            Write-Host "         改法二：把整个比较再包一层括号 Check '…' ((…) -eq …)"
            $violations++
            $rule1++
        }
    }
}
if ($rule1 -eq 0) { Write-Host "  [PASS] 无裸比较表达式（$($files.Count) 个脚本全部合规）" }

# ---------- 规则 2 ----------
Write-Host ""
Write-Host "> 规则 2：.ps1 必须带 UTF-8 BOM（否则 PS 5.1 按 ANSI 解码 → 中文乱码）"
$rule2 = 0
foreach ($f in $files) {
    $bytes = [System.IO.File]::ReadAllBytes($f.FullName)
    # 空文件按"无需 BOM"处理：没有内容就没有解码问题
    if ($bytes.Length -eq 0) { continue }
    $hasBom = ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)
    if (-not $hasBom) {
        Write-Host "  [FAIL] $($f.Name) 缺少 UTF-8 BOM"
        $violations++
        $rule2++
    }
}
if ($rule2 -eq 0) { Write-Host "  [PASS] 全部带 BOM" }

# ---------- 汇总 ----------
Write-Host ""
if ($violations -gt 0) {
    Write-Host "=== 静态自检结果：$violations 项违规 ==="
    exit 1
}
Write-Host "=== 静态自检结果：全部通过 ==="
exit 0
