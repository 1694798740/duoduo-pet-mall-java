<#
  S9 · 对象存储抽象层的静态守卫

  ── 它防的是什么 ────────────────────────────────────────────────────
  S9 把「图片/材料落在哪」从"写死在两个类里的本地盘"改成了可切换的 ObjectStorage。
  这类改造最典型的退化方式是**有人绕过抽象层直接碰本地盘**：
  新写一段 `Files.copy(in, uploadsDir.resolve(...))`、或者把 `java.nio.file.Path`
  塞回接口签名里。它的后果不会立刻报错 —— 表现是"配置改成了 COS，但有些新功能
  依然落在容器卷里"，属于**看起来一切正常**的那类故障。

  所以这里做四层判据，每层都要求"扫到了东西"+ 有正对照：

  ① 接口层：`ObjectStorage` 及其调用方**不得出现 java.nio.file**（抽象没被污染）；
  ② 调用方：`UploadController` / `MerchantMaterialReadController` 不得出现
     `Files.copy|Files.write|FileSystemResource`（不再直接落盘）；
  ③ 配置一致性：一旦 `duoduo.storage.type` 的**默认值**写成 cos，
     `UPLOADS_PUBLIC_BASE_URL` 就必须在 compose 里被透传（否则 URL 仍指向应用自身）；
  ④ ★ **正对照**：本地实现 `LocalFsObjectStorage` 里**必须**能扫到 `java.nio.file` ——
     否则说明 ① 的扫描根本没读到文件，"扫不到"会被误判成"很干净"。

  ── 自证纪律（铁律 29）────────────────────────────────────────────
  扫描文件数会打印；**任何一个层级的扫描数为 0 直接判违规**，不降级成 WARN。
  退出码：0 = 通过；1 = 违规。
#>
$ErrorActionPreference = 'Stop'

# 本脚本在 scripts\lib\ 下 ⇒ 上溯三层才是仓库根（少写一层会得到 scripts\，
# 然后所有 Test-Path 全落空 —— ★ 正是"扫描数为 0 判违规"这一条把这次错误当场揪出来的，
# 否则会输出一片"全部通过"）。
$repo = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSCommandPath))

$fail = 0
function Bad([string]$m) { Write-Host "  [FAIL] $m" -ForegroundColor Red; $script:fail++ }
function Ok([string]$m)  { Write-Host "  [OK]   $m" -ForegroundColor Green }
function Say([string]$m, [string]$c = 'Gray') { Write-Host $m -ForegroundColor $c }

Say "[storage-guard] 对象存储抽象层守卫（S9）"

# ─────────────────────────────────────────────────────────────────
# ★ 必须先剥注释再匹配（首次运行就栽在这里）：
#   这几个类的注释里**故意**写了 "为什么不能用 java.nio.file.Path"、"与改造前 Files.readAllBytes 同量级"
#   这类反向说明 —— 直接全文匹配会把"解释为什么不用"判成"用了"。
#   静态守卫必须只看代码，不看文档。
#   代价：字符串字面量里的 `//` 会被误当注释截断。对本守卫无影响（只判"有没有"）。
function Get-CodeOnly([string]$path) {
    $t = Get-Content -Raw -LiteralPath $path
    $t = [regex]::Replace($t, '/\*[\s\S]*?\*/', '')   # 块注释
    $t = [regex]::Replace($t, '//[^\r\n]*', '')        # 行注释
    return $t
}

$storageDir = Join-Path $repo 'duoduo-common-web\src\main\java\com\duoduo\common\web\storage'

# ─────────────────────────────────────────────────────────────────
# ① 接口不得被 java.nio.file 污染
#    「把 Path 塞进签名」等于把本地盘焊进抽象 —— 换介质时所有调用方还得改一遍。
$iface = Join-Path $storageDir 'ObjectStorage.java'
if (-not (Test-Path $iface)) {
    Bad "找不到接口文件：$iface"
} else {
    $text = Get-CodeOnly $iface
    if ($text -match 'java\.nio\.file|Files\.|\bPath\b') {
        Bad "ObjectStorage 接口里出现了本地盘痕迹（java.nio.file / Files / Path）—— 抽象被污染，换介质时调用方还得改"
    } else {
        Ok "接口保持介质无关（无 java.nio.file / Files / Path）"
    }
    # 自证：剥注释后仍必须能看到类型声明本身 —— 比"看字符数"精确：
    # 字符数阈值是拍出来的（首次设 400，结果这个接口正文只有 367 字符被误判），
    # 而"能不能看到 interface 声明"直接回答"我们读到的到底是不是这个文件、剥注释有没有把代码剥没"。
    if ($text -notmatch 'interface\s+ObjectStorage') {
        Bad "剥注释后看不到 interface ObjectStorage 声明 —— 上面的「干净」不可信"
    }
}

# ─────────────────────────────────────────────────────────────────
# ② 调用方不得直接落盘
$callers = @(
    @{ name = 'UploadController'; path = 'duoduo-fulfilment\src\main\java\com\duoduo\fulfilment\controller\UploadController.java' },
    @{ name = 'MerchantMaterialReadController'; path = 'duoduo-user\src\main\java\com\duoduo\user\controller\MerchantMaterialReadController.java' },
    @{ name = 'MerchantMaterialStorage'; path = 'duoduo-user\src\main\java\com\duoduo\user\support\MerchantMaterialStorage.java' }
)
$scanned = 0
foreach ($c in $callers) {
    $p = Join-Path $repo $c.path
    if (-not (Test-Path $p)) { Bad "找不到 $($c.name)：$p"; continue }
    $scanned++
    $t = Get-CodeOnly $p
    if ($t -match 'Files\.(copy|write|readAllBytes|createDirectories)|FileSystemResource|\bimport\s+java\.nio\.file') {
        Bad "$($c.name) 仍然直接操作本地盘（Files.* / FileSystemResource / java.nio.file）—— 必须走 ObjectStorage"
    } else {
        Ok "$($c.name) 已完全走接口"
    }
}
if ($scanned -eq 0) { Bad "调用方扫描数为 0 —— 一个文件都没读到，"全部走接口"这个结论不成立" }

# ─────────────────────────────────────────────────────────────────
# ③ 配置一致性：默认 local 才允许 UPLOADS_PUBLIC_BASE_URL 留空透传
$compose = Join-Path $repo 'docker-compose.yml'
$fulfilmentYml = Join-Path $repo 'duoduo-fulfilment\src\main\resources\application.yml'
if (-not (Test-Path $compose)) {
    Bad "找不到 docker-compose.yml"
} elseif (-not (Test-Path $fulfilmentYml)) {
    Bad "找不到 duoduo-fulfilment/application.yml"
} else {
    $cy = Get-Content -Raw -LiteralPath $compose
    $fy = Get-Content -Raw -LiteralPath $fulfilmentYml

    # 默认值必须是 local：新存储层出问题时，不动配置就等于没上对象存储
    if ($fy -match 'type:\s*\$\{STORAGE_TYPE:(?<def>[^}]*)\}') {
        $def = $Matches['def'].Trim()
        if ($def -eq 'local') { Ok "duoduo.storage.type 默认 = local（回退路径可用）" }
        else { Bad "duoduo.storage.type 默认 = '$def' —— 默认必须是 local，否则「没配密钥」的环境会直接走 cos" }
    } else {
        Bad "application.yml 里没有 type: \${STORAGE_TYPE:...} —— 存储选型缺省值缺失"
    }

    # 公开桶域名必须能被注入：否则切 COS 后返回的 URL 仍指向应用自身
    if ($cy -match 'UPLOADS_PUBLIC_BASE_URL') { Ok "compose 已透传 UPLOADS_PUBLIC_BASE_URL" }
    else { Bad "compose 未透传 UPLOADS_PUBLIC_BASE_URL —— 切 COS 后图片 URL 会指向应用自身而不是桶" }

    # 两个服务都要能拿到 COS_*（各配一半是最常见的漏配）
    foreach ($need in @('COS_SECRET_ID', 'COS_REGION', 'COS_BUCKET_PUBLIC', 'COS_BUCKET_PRIVATE')) {
        if ($cy -match $need) { Ok "compose 已透传 $need" } else { Bad "compose 未透传 $need" }
    }
}

# ─────────────────────────────────────────────────────────────────
# ④ ★ 正对照：本地实现里必须能扫到 java.nio.file
#    否则说明前面几层的"干净"只是因为**没读到文件**，判据恒真。
$localImpl = Join-Path $storageDir 'LocalFsObjectStorage.java'
if (-not (Test-Path $localImpl)) {
    Bad "找不到本地实现 LocalFsObjectStorage.java —— 回退路径缺失"
} else {
    $lt = Get-CodeOnly $localImpl
    if ($lt -match 'java\.nio\.file') {
        Ok "正对照通过：本地实现里确实存在 java.nio.file（说明扫描真的读到了文件、剥注释没把代码剥没）"
    } else {
        Bad "正对照失败：本地实现里扫不到 java.nio.file —— 前几层的「干净」不可信，扫描或剥注释可能没生效"
    }
}

Say ''
if ($fail -eq 0) {
    Write-Host "[storage-guard] 通过：4 层判据全绿，扫描 $scanned 个调用方文件" -ForegroundColor Green
    exit 0
}
Write-Host "[storage-guard] 违规 $fail 项" -ForegroundColor Red
exit 1
