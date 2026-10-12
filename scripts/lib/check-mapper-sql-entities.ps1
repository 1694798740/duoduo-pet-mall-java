<#
.SYNOPSIS
    Mapper 注解 SQL 的 XML 实体守卫：**不含 <script> 的注解 SQL 里不得出现 &gt; / &lt; / &amp; / &#NN; / &quot;。**

.DESCRIPTION
    为什么需要它 —— 这类缺陷在编译期、启动期、发请求之前**毫无征兆**：

      MyBatis 只在注解字符串**以 `<script>` 开头**时，才把内容交给 XML 解析器。
      此时 `&gt;` / `&lt;` 会被解码成 `>` / `<`，所以**在 <script> 里写实体是必须的**
      （裸的 `<` 会让 XML 解析直接失败）。

      但如果注解**没有** <script>，MyBatis 走的是纯文本路径（TextSqlNode），
      实体**原样拼进 SQL** —— 语句真的变成 `WHERE created_at &gt;= ?`。
      它一路编译通过、服务正常启动、接口也照常进到 Service，
      直到 MyBatis-Plus 的 `TenantLineInnerInterceptor` 在 `beforeQuery` 里
      用 JSqlParser 解析（**无条件先解析，之后才逐表判 ignoreTable**）时被拒：

          ParseException: Encountered unexpected token: "=" "="

      表象是一个 500「服务内部错误」，根因藏在 SQL 字符串里。
      E24 的 `tenantHealthOrders` / `staleCustodyBySeller` / `tenantStats` 三处实测踩中
      （同文件里的 dailyTrend 等之所以没事，是因为它们在 <script> 内）。

    判据只有一条：**没有 <script> 的注解 SQL 不得含 XML 实体**。
    含 <script> 的块内不检查 —— 那里实体才是正确写法，检查它反而会逼人写坏。

    注释行（javadoc 的 `*` / `//` / `/*`）不检查：说明文字里引用实体是无害的，
    而注释里出现的 `&gt;` 根本进不了 SQL。

    自证：扫描到的 main 源码 .java 文件数为 0 时**判违规** ——
    「脚本写错了也返回 0」是这类守卫最坏的失效方向。

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\lib\check-mapper-sql-entities.ps1
#>
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)   # scripts/lib -> scripts -> repo

# ★ 不要写成 Get-ChildItem -Path (Join-Path $repo 'duoduo-*')：-Path 不解析**中间段**的通配符，
#   结果是静默扫到 0 个文件 —— 而 0 个文件正是本脚本判违规的条件（自证救了这一把）。
$files = @(Get-ChildItem -Path $repo -Directory -Filter 'duoduo-*' -ErrorAction SilentlyContinue |
    ForEach-Object { Get-ChildItem -Path $_.FullName -Recurse -Filter '*.java' -File -ErrorAction SilentlyContinue } |
    Where-Object { $_.FullName -match '[\\/]src[\\/]main[\\/]java[\\/]' })

Write-Host "[check-mapper-sql-entities] 扫描 $($files.Count) 个 main 源码 .java 文件"

# ★ 自证：一个文件都没扫到，说明路径推导错了（而不是「仓库很干净」）。
if ($files.Count -eq 0) {
    Write-Host '  [FAIL] 未扫描到任何 Java 源文件 —— 守卫自身失效（路径推导错误？），按违规处理' -ForegroundColor Red
    exit 1
}

$entityPattern = '&gt;|&lt;|&amp;|&#\d+;|&quot;'
$violations = New-Object System.Collections.ArrayList
$unclosed = New-Object System.Collections.ArrayList
$scannedAnnotated = 0

foreach ($f in $files) {
    $lines = @(Get-Content -LiteralPath $f.FullName -Encoding UTF8)
    $inScript = $false
    $rel = $f.FullName.Substring($repo.Length + 1)

    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        $trim = $line.TrimStart()
        $isComment = $trim.StartsWith('*') -or $trim.StartsWith('//') -or $trim.StartsWith('/*')

        if ($line -match '@(Select|Update|Insert|Delete)\s*\(') { $scannedAnnotated++ }

        # ★ 注释行**不参与** <script> 状态判定：说明文字里出现「<script>」是无害的，
        #   让它翻转状态会让本行之后（乃至整个文件余下）全部被误当成「在 script 内」而漏检。
        if (-not $isComment) {
            # ★ 本行是否算「在 script 内」＝ 上一行遗留的状态 **或本行开启了 script**。
            #   少了后半句，`@Select("<script>SELECT … &gt;= …")` 这种「script 与实体同行」的
            #   正确写法会被误报（E24 反向验证实测：假阳性 1 处）。
            $inScriptHere = $inScript -or ($line -match '<script>')

            if ((-not $inScriptHere) -and ($line -match $entityPattern)) {
                [void]$violations.Add(('{0}:{1}: {2}' -f $rel, ($i + 1), $trim))
            }

            # 单行内同时开启与闭合（`"<script>…</script>"`）时以闭合为准 ⇒ 回到块外。
            if ($line -match '</script>') { $inScript = $false }
            elseif ($line -match '<script>') { $inScript = $true }
        }
    }

    # <script> 未闭合会让本文件后续所有行都被当成「在 script 内」而漏检 —— 必须报出来
    if ($inScript) { [void]$unclosed.Add($rel) }
}

Write-Host "  含注解 SQL 的行数（参考值）：$scannedAnnotated"

$bad = $false
if ($unclosed.Count -gt 0) {
    Write-Host '  [FAIL] 以下文件的 <script> 未闭合（会让本文件余下行全部漏检）：' -ForegroundColor Red
    $unclosed | ForEach-Object { Write-Host "    $_" }
    $bad = $true
}

if ($violations.Count -gt 0) {
    Write-Host "  [FAIL] 发现 $($violations.Count) 处「无 <script> 却写了 XML 实体」——运行时才会以 JSqlParser 解析失败暴露：" -ForegroundColor Red
    $violations | ForEach-Object { Write-Host "    $_" }
    Write-Host '  修法：该注解没有 <script> ⇒ 直接写字面量 >= / < / &；' -ForegroundColor Yellow
    Write-Host '        确实需要动态标签时，把整个 SQL 包进 <script>…</script>，那时实体写法才是对的。' -ForegroundColor Yellow
    $bad = $true
}

if ($bad) { exit 1 }

Write-Host "  [OK] 无 <script> 的注解 SQL 里未出现 XML 实体（自证：扫描 $($files.Count) 个文件，>0）" -ForegroundColor Green
exit 0
