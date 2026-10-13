<#
  S9 · 把上传卷里的存量图片/材料搬进对象存储（可停可续、幂等、逐文件双源核对）

  ── 它在整个迁移里的位置 ─────────────────────────────────────────────
  接入对象存储的顺序是：① 出清单 → ② 双写 → **③ 迁存量（本脚本）** → ④ 切读 → ⑤ 停写旧路径。
  本脚本只做 ③：把卷里**已有**的文件复制一份到桶里，并逐文件核对**字节一致**。
  它**不动线上行为**（`STORAGE_TYPE` 该是什么还是什么），因此可以随时跑、随时停、随时重跑。

  ── 三条硬要求 ──────────────────────────────────────────────────────
  ① **幂等**：目标已存在且内容一致 ⇒ 跳过。可反复执行，不会产生第二份、也不会覆盖坏。
  ② **可停可续**：每传一个立刻核对一个；中途中断只影响那一个文件，重跑接着来。
  ③ **双源核对**：判据是「**卷里那份**的 sha256」与「**桶里那份**回读出来的 sha256」相等 ——
     不是"上传接口返回 200 就算成功"。上传成功但内容截断，在只看返回码的写法里是完全看不出来的。

  ── 用法 ────────────────────────────────────────────────────────────
    # 只看会做什么（默认就是干跑，不写桶）
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\migrate-uploads-to-cos.ps1
    # 真跑：两个卷都迁
    ... -File scripts\migrate-uploads-to-cos.ps1 -Apply
    # 只迁某一个卷 / 只核对不写
    ... -File scripts\migrate-uploads-to-cos.ps1 -Apply -Only fulfilment-uploads
    ... -File scripts\migrate-uploads-to-cos.ps1 -VerifyOnly

  退出码：0 = 全部核对通过；1 = 有失败项（会逐条点名）。
#>
[CmdletBinding()]
param(
    [switch]$Apply,
    [switch]$VerifyOnly,
    [ValidateSet('', 'fulfilment-uploads', 'merchant-materials')][string]$Only = ''
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
. (Join-Path $repo 'scripts\lib\cos-tool.ps1')

# 卷 → 桶 + 逻辑前缀的映射。逻辑前缀与 Java 侧 ObjectStorage.PREFIX_* 必须一致：
# 本地实现会把前缀**剥掉**（所以卷里是 `uploads/<uuid>` 而不是 `uploads/uploads/<uuid>`），
# 而桶里的 key **带上**前缀（公开图 URL 就是 <桶域名>/uploads/<uuid>）。
$Targets = @(
    @{ volume = 'fulfilment-uploads'; container = 'duoduo-app-fulfilment'; inner = '/data/uploads'
       role = 'public'; prefix = 'uploads/'; label = '商品 / 评价图片（公开桶）' }
    @{ volume = 'merchant-materials'; container = 'duoduo-app-user'; inner = '/data/merchant-materials'
       role = 'private'; prefix = 'materials/'; label = '入驻资质材料（私有桶）' }
)

$script:uploaded = 0
$script:skipped = 0
$script:verified = 0
$script:planned = 0
$script:failed = 0

function Say([string]$m, [string]$c = 'Gray') { Write-Host $m -ForegroundColor $c }

# ★★ 模式判定只允许在这一个表达式里发生。
#    2026-10-13 实测踩到：第一版把上传条件写成 `-not $VerifyOnly`，
#    于是**默认的"干跑"照样把 132 个对象写进了真实桶** —— 开关语义写反，
#    而脚本还在汇总里打印"（干跑结束：没有写入任何对象）"。这与本项目其它
#    "看起来没做、其实做了"的坑同型（"干跑"必须**真的**一个字节都不写）。
#    ⇒ 现在只有一个开关 $Apply 决定"写不写桶"，其余模式一律只读。
$doWrite = [bool]$Apply
if ($VerifyOnly -and $Apply) { throw '-VerifyOnly 与 -Apply 互斥：核对模式不应该写桶' }

$mode = $(if ($VerifyOnly) { '仅核对（只读桶）' } elseif ($doWrite) { '执行（会写入桶）' } else { '干跑（只读桶、不写任何对象）' })
Say ("[migrate] 模式 = {0}" -f $mode) 'Cyan'
Say ''

foreach ($t in $Targets) {
    if ($Only -and $t.volume -ne $Only) { continue }

    Say ("── {0}：{1}  →  桶 {2}" -f $t.volume, $t.label, $t.role) 'Cyan'

    # ① 卷的**真实名字**带 compose 项目前缀 ⇒ 按后缀反查（拼错的表现是"复制出一个空目录然后宣布成功"）
    $realVol = (& docker volume ls --format '{{.Name}}' 2>$null |
        Where-Object { $_ -match ('(^|_)' + [regex]::Escape($t.volume) + '$') } | Select-Object -First 1)
    if (-not $realVol) { Say ("  [FAIL] 反查不到卷「{0}」" -f $t.volume) 'Red'; $script:failed++; continue }

    $running = ("$(& docker inspect --format '{{.State.Running}}' $t.container 2>$null)").Trim()
    if ($running -ne 'true') { Say ("  [FAIL] 容器 {0} 没在跑" -f $t.container) 'Red'; $script:failed++; continue }

    # ② 把卷内容复制到宿主机临时目录（docker cp 不经过 PowerShell 管道，字节不会被重新编码）
    $tmp = Join-Path $env:TEMP ('s9-migrate-' + $t.volume)
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    & docker cp ($t.container + ':' + $t.inner + '/.') $tmp 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) { Say ("  [FAIL] docker cp 失败（exit={0}）" -f $LASTEXITCODE) 'Red'; $script:failed++; continue }

    $files = @(Get-ChildItem -LiteralPath $tmp -Recurse -File -Force -ErrorAction SilentlyContinue)
    if ($files.Count -eq 0) {
        # 空卷不算失败，但**必须说出来**（"0 个文件"与"没读到文件"长得一样）
        Say ("  [注意] 该卷当前是空目录（0 个文件）—— 不算失败，但这次迁移对它没有信息量") 'Yellow'
    }

    $cfg = Get-CosConfig -Role $t.role -RepoRoot $repo

    foreach ($f in $files) {
        $rel = ($f.FullName.Substring($tmp.Length).TrimStart('\', '/')) -replace '\\', '/'
        $key = $t.prefix + $rel
        $localSha = (Get-FileHash -LiteralPath $f.FullName -Algorithm SHA256).Hash.ToLower()

        try {
            if (-not $doWrite -and -not $VerifyOnly) {
                # ── 干跑：**一个字节都不写桶**，只报"会迁哪些" ──────────────────
                $head = Invoke-CosHead -Cfg $cfg -Key $key
                $tag = $(if ($head.Status -eq 200) { '（桶内已存在，真跑时会先核对再决定跳过/重传）' } else { '（桶内尚无）' })
                Say ("    [计划] {0}  {1} 字节 {2}{3}" -f $rel, $f.Length, $localSha.Substring(0, 12), $tag) 'DarkGray'
                $script:planned++
                continue
            }

            if ($doWrite) {
                # ③ 先探一探：已存在就**回读比一次 sha256**，一致则跳过（幂等 —— 可反复执行）
                $head = Invoke-CosHead -Cfg $cfg -Key $key
                if ($head.Status -eq 200) {
                    $got = Invoke-CosGet -Cfg $cfg -Key $key
                    if ($got.Status -eq 200) {
                        $sha0 = [System.Security.Cryptography.SHA256]::Create()
                        $remote0 = ([BitConverter]::ToString($sha0.ComputeHash($got.Body)) -replace '-', '').ToLower()
                        if ($remote0 -eq $localSha) {
                            $script:skipped++
                            Say ("    [跳过] {0}（桶内已有且 sha256 一致）" -f $rel) 'DarkGray'
                            continue
                        }
                        Say ("    [重传] {0}（桶内已有但内容不同：{1} != {2}）" -f $rel, $remote0.Substring(0, 12), $localSha.Substring(0, 12)) 'Yellow'
                    }
                }
                $bytes = [System.IO.File]::ReadAllBytes($f.FullName)
                $ct = switch -Regex ($rel) {
                    '\.jpg$|\.jpeg$' { 'image/jpeg' }
                    '\.png$' { 'image/png' }
                    '\.webp$' { 'image/webp' }
                    '\.gif$' { 'image/gif' }
                    '\.pdf$' { 'application/pdf' }
                    default { 'application/octet-stream' }
                }
                $put = Invoke-CosPut -Cfg $cfg -Key $key -Body $bytes -ContentType $ct
                if ($put.Status -lt 200 -or $put.Status -ge 300) {
                    Say ("    [FAIL] 上传 {0} → HTTP {1}" -f $rel, $put.Status) 'Red'
                    $script:failed++
                    continue
                }
            }

            # ④ ★ 双源核对：把桶里那份**回读出来**再算一遍 sha256，与卷里那份比。
            #    （只写不读＝"接口返回 200 就算成功"，内容被截断时完全看不出来。）
            $back = Invoke-CosGet -Cfg $cfg -Key $key
            if ($back.Status -ne 200) {
                Say ("    [FAIL] 回读 {0} → HTTP {1}（写入成功但读不回来，不能算成功）" -f $rel, $back.Status) 'Red'
                $script:failed++
                continue
            }
            $sha2 = [System.Security.Cryptography.SHA256]::Create()
            $remote2 = ([BitConverter]::ToString($sha2.ComputeHash($back.Body)) -replace '-', '').ToLower()
            if ($remote2 -ne $localSha) {
                Say ("    [FAIL] {0} 字节不一致：卷 {1} vs 桶 {2}" -f $rel, $localSha.Substring(0, 12), $remote2.Substring(0, 12)) 'Red'
                $script:failed++
                continue
            }
            if ($doWrite) { $script:uploaded++ } else { $script:verified++ }
            Say ("    [OK] {0}（{1} 字节，双源 sha256 一致 {2}）" -f $rel, $f.Length, $localSha.Substring(0, 12)) 'Green'
        } catch {
            Say ("    [FAIL] {0} 异常：{1}" -f $rel, $_.Exception.Message) 'Red'
            $script:failed++
        }
    }

    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    Say ''
}

Say '======================================================================'
if ($script:failed -gt 0) {
    Say ("迁移汇总：失败 {0} 项 —— 见上面的 [FAIL] 行（逐条点名）" -f $script:failed) 'Red'
    exit 1
}
if (-not $doWrite -and -not $VerifyOnly) {
    Say ("干跑汇总：待迁 {0} 个文件（**没有写入任何对象**）" -f $script:planned) 'Yellow'
} elseif ($doWrite) {
    Say ("迁移汇总：写入并核对 {0} / 跳过（桶内已一致） {1} / 失败 0" -f $script:uploaded, $script:skipped) 'Green'
} else {
    Say ("核对汇总：桶内与卷内逐文件 sha256 一致 {0} 个（只读，未写桶）" -f $script:verified) 'Green'
}
Say ''
Say '★ 提醒：本脚本**不改线上行为**。切到对象存储是另一件事 —— 把 .env 的' 'DarkGray'
Say '  STORAGE_TYPE 改成 cos、并把 UPLOADS_PUBLIC_BASE_URL 设为公开桶域名，然后重建容器。' 'DarkGray'
exit 0
