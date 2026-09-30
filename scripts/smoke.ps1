# 网关 → 用户服务 端到端冒烟（Windows PowerShell 5.1）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke.ps1
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    [string]$Phone = '',
    [string]$Password = 'duoduo123'
)

# 每次运行使用随机手机号，保证冒烟测试可重复执行（幂等）。
# 注意：参数默认值里不能直接调用 cmdlet，因此在脚本体内计算。
if ([string]::IsNullOrEmpty($Phone)) {
    $Phone = '139' + (Get-Random -Minimum 10000000 -Maximum 99999999)
}
Write-Host "使用测试手机号：$Phone"

$ErrorActionPreference = 'Continue'
$pass = 0
$fail = 0
$failures = @()

function Check([string]$name, [bool]$ok, $extra) {
    if ($ok) {
        $script:pass++
        Write-Host "  [PASS] $name"
    } else {
        $script:fail++
        $script:failures += $name
        if ($null -ne $extra) { Write-Host "  [FAIL] $name  -> $extra" } else { Write-Host "  [FAIL] $name" }
    }
}

function CodeOf($resp) { if ($resp) { [int]$resp.StatusCode } else { 0 } }

# 跨 PS 版本读错误响应体（T-45）：
#   * PS5.1 的 Response 是 HttpWebResponse → GetResponseStream() 可读；
#   * pwsh 7（CI Linux）的 Response 是 HttpResponseMessage，且 PS7 已自行读过内容流
#     （ReadAsStringAsync 会抛「对象已释放」），错误体只能从 ErrorDetails.Message 拿，
#     但 PS7 会把它重排成多行 / \u 转义形态 —— 所以读出来后一律 ConvertFrom-Json 再用属性。
function ReadErrBody($errRecord) {
    try {
        if ($errRecord.ErrorDetails -and $errRecord.ErrorDetails.Message) {
            return $errRecord.ErrorDetails.Message | ConvertFrom-Json
        }
    } catch { }
    try {
        $resp = $errRecord.Exception.Response
        $t = $null
        if ($resp -and $resp.PSObject.Methods.Name -contains 'GetResponseStream') {
            $st = $resp.GetResponseStream()
            if ($st) { $t = (New-Object System.IO.StreamReader($st)).ReadToEnd() }
        } elseif ($resp -and $null -ne $resp.Content) {
            try { $t = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult() } catch { }
        }
        if ($t) { return $t | ConvertFrom-Json }
    } catch { }
    return $null
}

Write-Host ""
Write-Host "=== 多多宠物商店 · Java 微服务版冒烟测试 @ $Gateway ==="
Write-Host ""

# ---------- 0. 网关就绪 ----------
Write-Host "> 网关就绪"
$ready = $false
for ($i = 0; $i -lt 40; $i++) {
    try {
        $h = Invoke-WebRequest -Uri "$Gateway/actuator/health" -UseBasicParsing -TimeoutSec 5
        if ($h.StatusCode -eq 200) { $ready = $true; break }
    } catch { Start-Sleep -Seconds 1 }
}
Check "网关 /actuator/health 就绪" $ready
if (-not $ready) { Write-Host "网关未就绪，终止"; exit 1 }

# ---------- 1. 注册（免认证，网关放行） ----------
Write-Host ""
Write-Host "> 认证链路"
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$body = @{ phone = $Phone; password = $Password; nickname = '铲屎官小林' } | ConvertTo-Json -Compress
$regCode = 0
$regBody = $null
try {
    $r = Invoke-WebRequest -Uri "$Gateway/api/auth/register" -Method Post -Body $body `
        -ContentType 'application/json; charset=utf-8' -WebSession $session -UseBasicParsing -TimeoutSec 20
    $regCode = [int]$r.StatusCode
    $regBody = $r.Content | ConvertFrom-Json
} catch {
    $regCode = [int]$_.Exception.Response.StatusCode.value__
    $regBody = ReadErrBody $_
}

if ($regCode -eq 409) {
    Write-Host "  手机号已注册，改走登录"
    $loginBody = @{ phone = $Phone; password = $Password } | ConvertTo-Json -Compress
    $r = Invoke-WebRequest -Uri "$Gateway/api/auth/login" -Method Post -Body $loginBody `
        -ContentType 'application/json; charset=utf-8' -WebSession $session -UseBasicParsing -TimeoutSec 20
    $regCode = [int]$r.StatusCode
    $regBody = $r.Content | ConvertFrom-Json
}

Check "注册/登录返回 200" ($regCode -eq 200) $regCode
Check "响应体为统一结构（code=SUCCESS）" ($regBody.code -eq 'SUCCESS') $regBody.code
Check "手机号已脱敏（不含完整号码）" ($regBody.data.phoneMasked -ne $Phone) $regBody.data.phoneMasked
Check "返回 requestId 便于排查" (-not [string]::IsNullOrEmpty($regBody.requestId)) $regBody.requestId
Check "令牌写入 httpOnly Cookie（dd_at）" ($session.Cookies.GetCookies($Gateway).Count -ge 1)

# ---------- 2. 未认证访问受保护接口 → 网关拦截 ----------
Write-Host ""
Write-Host "> 网关鉴权拦截"
$anon = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$anonCode = 0
$anonBody = $null
try {
    $r = Invoke-WebRequest -Uri "$Gateway/api/users/me" -WebSession $anon -UseBasicParsing -TimeoutSec 20
    $anonCode = [int]$r.StatusCode
} catch {
    $anonCode = [int]$_.Exception.Response.StatusCode.value__
    $anonBody = ReadErrBody $_
}
Check "未带令牌访问 /api/users/me 返回 401" ($anonCode -eq 401) $anonCode
# 注：PowerShell 5.1 读取 WebException 响应流不稳定，这里改用 curl 取原始响应体；
#     只校验 ASCII 结构标记，规避控制台代码页对中文的转码影响。
$anonRaw = ((& curl.exe -s "$Gateway/api/users/me" 2>$null) -join '')
Check "401 响应体为规范化错误结构（code=UNAUTHORIZED）" `
    ($anonRaw -match '"code"\s*:\s*"UNAUTHORIZED"') $anonRaw
Check "401 响应体带 requestId" ($anonRaw -match '"requestId"\s*:\s*"[0-9a-f]{8,}"') $null

# ---------- 3. 认证后访问 ----------
Write-Host ""
Write-Host "> 认证后访问"
try {
    $r = Invoke-WebRequest -Uri "$Gateway/api/users/me" -WebSession $session -UseBasicParsing -TimeoutSec 20
    $me = $r.Content | ConvertFrom-Json
    Check "带令牌访问 /api/users/me 返回 200" ($r.StatusCode -eq 200)
    Check "身份由网关透传（昵称正确）" ($me.data.nickname -eq '铲屎官小林') $me.data.nickname
    Check "默认未实名" ($me.data.realNameVerified -eq $false) $me.data.realNameVerified
} catch {
    Check "带令牌访问 /api/users/me 返回 200" $false $_.Exception.Message
}

# ---------- 4. 实名认证 ----------
Write-Host ""
Write-Host "> 实名认证"
$rnBody = @{ realName = '林小柒'; idCard = '110101199505050033' } | ConvertTo-Json -Compress
try {
    $r = Invoke-WebRequest -Uri "$Gateway/api/users/me/realname" -Method Post -Body $rnBody `
        -ContentType 'application/json; charset=utf-8' -WebSession $session -UseBasicParsing -TimeoutSec 20
    $rn = $r.Content | ConvertFrom-Json
    Check "实名认证成功" ($r.StatusCode -eq 200)
    Check "身份证仅保留掩码（首4 + 尾4，中间打码）" ($rn.data.idCardMasked -match '^1101\*{10}0033$') $rn.data.idCardMasked
} catch {
    Check "实名认证成功" $false $_.Exception.Message
}

# ---------- 5. 宠物档案 ----------
Write-Host ""
Write-Host "> 宠物档案"
$petBody = @{
    name = '豆豆'; species = 'cat'; breed = '布偶'
    birthday = '2026-06-14'; weightKg = 2.4
    allergens = @('谷物', '牛肉')
} | ConvertTo-Json -Compress
try {
    $r = Invoke-WebRequest -Uri "$Gateway/api/pets" -Method Post -Body $petBody `
        -ContentType 'application/json; charset=utf-8' -WebSession $session -UseBasicParsing -TimeoutSec 20
    $pet = $r.Content | ConvertFrom-Json
    Check "创建宠物档案成功" ($r.StatusCode -eq 200)
    Check "首只宠物自动设为当前宠物" ($pet.data.isDefault -eq $true)
    Check "按出生日期推算生命阶段为 BABY" ($pet.data.lifeStage -eq 'BABY') $pet.data.lifeStage
    Check "过敏原正确保存" ($pet.data.allergens.Count -eq 2) $pet.data.allergens
} catch {
    Check "创建宠物档案成功" $false $_.Exception.Message
}

# 生日与生命阶段矛盾 → 422（PET-05）
$badPet = @{
    name = '团团'; species = 'dog'; birthday = '2026-08-01'; lifeStage = 'SENIOR'
} | ConvertTo-Json -Compress
$badCode = 0
$badBody = $null
try {
    $r = Invoke-WebRequest -Uri "$Gateway/api/pets" -Method Post -Body $badPet `
        -ContentType 'application/json; charset=utf-8' -WebSession $session -UseBasicParsing -TimeoutSec 20
    $badCode = [int]$r.StatusCode
} catch {
    $badCode = [int]$_.Exception.Response.StatusCode.value__
    $badBody = ReadErrBody $_
}
Check "生日与生命阶段矛盾时拒绝写入（422）" ($badCode -eq 422) $badCode
Check "矛盾提示要求用户确认" ($badBody.code -eq 'BUSINESS_RULE') $badBody.code

# 参数校验 → 400
$invalidPhone = @{ phone = '12345'; password = 'duoduo123' } | ConvertTo-Json -Compress
$vc = 0
try {
    $r = Invoke-WebRequest -Uri "$Gateway/api/auth/register" -Method Post -Body $invalidPhone `
        -ContentType 'application/json; charset=utf-8' -UseBasicParsing -TimeoutSec 20
    $vc = [int]$r.StatusCode
} catch { $vc = [int]$_.Exception.Response.StatusCode.value__ }
Check "非法手机号在边界处被校验拦截（400）" ($vc -eq 400) $vc

# ---------- 6. 越权与档案列表 ----------
Write-Host ""
Write-Host "> 越权与列表"
try {
    $r = Invoke-WebRequest -Uri "$Gateway/api/pets" -WebSession $session -UseBasicParsing -TimeoutSec 20
    $list = $r.Content | ConvertFrom-Json
    Check "档案列表可读" ($r.StatusCode -eq 200)
    Check "档案归属当前用户（1 条）" ($list.data.Count -eq 1) $list.data.Count
} catch {
    Check "档案列表可读" $false $_.Exception.Message
}

# ---------- 7. 商品域 · 类目与首页 ----------
Write-Host ""
Write-Host "> 商品域 · 类目与首页"
try {
    $r = Invoke-WebRequest -Uri "$Gateway/api/categories" -UseBasicParsing -TimeoutSec 20
    $cat = $r.Content | ConvertFrom-Json
    Check "类目树返回 200" ($r.StatusCode -eq 200)
    Check "一级类目 6 个" (@($cat.data).Count -eq 6) (@($cat.data).Count)
    Check "一级类目带二级子节点" (@($cat.data[0].children).Count -ge 2) (@($cat.data[0].children).Count)
} catch {
    Check "类目树返回 200" $false $_.Exception.Message
}

try {
    $r = Invoke-WebRequest -Uri "$Gateway/api/home" -UseBasicParsing -TimeoutSec 30
    # 注意：变量不能叫 $home —— PowerShell 中 $HOME 是只读内置变量，赋值会直接抛错
    $homeData = ($r.Content | ConvertFrom-Json).data
    Check "首页聚合返回 200" ($r.StatusCode -eq 200)
    Check "首页含推荐商品" (@($homeData.recommended).Count -ge 1) (@($homeData.recommended).Count)
    Check "首页含新品" (@($homeData.freshArrivals).Count -ge 1) (@($homeData.freshArrivals).Count)
    Check "首页含品牌" (@($homeData.brands).Count -ge 1) (@($homeData.brands).Count)
    Check "首页含猫咪/狗狗精选两组" (@($homeData.speciesPicks).Count -eq 2) (@($homeData.speciesPicks).Count)
    Check "卡片已补齐类目名与品牌名（批量查询生效）" `
        ((-not [string]::IsNullOrEmpty($homeData.recommended[0].categoryName)) -and (-not [string]::IsNullOrEmpty($homeData.recommended[0].brandName))) `
        ($homeData.recommended[0].categoryName + '/' + $homeData.recommended[0].brandName)
    Check "首页闲置仅返回已审核通过的 6 条（排除待审/驳回）" (@($homeData.secondhand).Count -eq 6) (@($homeData.secondhand).Count)
} catch {
    Check "首页聚合返回 200" $false $_.Exception.Message
}

# ---------- 8. 商品域 · 检索 ----------
Write-Host ""
Write-Host "> 商品域 · 检索"

# 点一级类目应展开其下子类目（主粮 → 猫主粮 / 犬主粮 / 冻干湿粮，共 8 件）
try {
    $r = Invoke-WebRequest -Uri "$Gateway/api/products?categoryId=1&pageSize=50" -UseBasicParsing -TimeoutSec 20
    $p = ($r.Content | ConvertFrom-Json).data
    Check "一级类目自动展开子类目（主粮命中 8 件）" ($p.total -eq 8) $p.total
} catch {
    Check "一级类目自动展开子类目（主粮命中 8 件）" $false $_.Exception.Message
}

# 价格区间是「相交」语义：筛选 12900 分应命中区间 12900~13900 的商品，
# 但不能命中最低价 15800 的商品 —— 后者只在错误写法（min >= 12900）下才会被带出
try {
    $r = Invoke-WebRequest -Uri "$Gateway/api/products?minPrice=12900&maxPrice=12900&pageSize=50" -UseBasicParsing -TimeoutSec 20
    $p = ($r.Content | ConvertFrom-Json).data
    $ids = @($p.items | ForEach-Object { $_.id })
    Check "价格区间按「相交」语义命中 1001" ($ids -contains 1001) ($ids -join ',')
    Check "价格区间未误命中 1002（其最低价高于筛选上限）" (-not ($ids -contains 1002)) ($ids -join ',')
} catch {
    Check "价格区间按「相交」语义命中 1001" $false $_.Exception.Message
}

# 无货过滤：1007 的全部 SKU 库存均为 0
try {
    $r = Invoke-WebRequest -Uri "$Gateway/api/products?inStockOnly=true&pageSize=50" -UseBasicParsing -TimeoutSec 20
    $p = ($r.Content | ConvertFrom-Json).data
    $ids = @($p.items | ForEach-Object { $_.id })
    Check "inStockOnly 过滤掉全部无货商品 1007" (-not ($ids -contains 1007)) ($ids -join ',')
} catch {
    Check "inStockOnly 过滤掉全部无货商品 1007" $false $_.Exception.Message
}

# 按宠物档案筛选：幼猫食品
try {
    $r = Invoke-WebRequest -Uri "$Gateway/api/products?species=cat&lifeStage=BABY&pageSize=50" -UseBasicParsing -TimeoutSec 20
    $p = ($r.Content | ConvertFrom-Json).data
    $ids = @($p.items | ForEach-Object { $_.id })
    Check "按物种+生命阶段筛选命中幼猫粮 1002" ($ids -contains 1002) ($ids -join ',')
    Check "筛选结果不含犬用商品 1005" (-not ($ids -contains 1005)) ($ids -join ',')
} catch {
    Check "按物种+生命阶段筛选命中幼猫粮 1002" $false $_.Exception.Message
}

$kw = [uri]::EscapeDataString('猫粮')
try {
    $r = Invoke-WebRequest -Uri "$Gateway/api/products?keyword=$kw&pageSize=50" -UseBasicParsing -TimeoutSec 20
    $p = ($r.Content | ConvertFrom-Json).data
    Check "关键词检索命中猫粮相关商品（≥3 件）" ($p.total -ge 3) $p.total
    # `total` 是**计数**，必须按 JSON 数字下发。它曾漏进 LongToStringJacksonModule 的白名单
    # 而被当标识转成字符串，于是上面那条 `-ge` 在 PowerShell 里退化成**字典序比较**
    # （"15" -ge "3" → False）恒失败 —— 类型错误伪装成业务失败。
    # 断言原始文本而非反序列化结果，让同类问题**当场现形**（见 docs/agent/08 篇 T-40/T-56）。
    Check "分页 total 以 JSON 数字下发（计数不是标识）" ($r.Content -match '"total"\s*:\s*\d') 'raw'
} catch {
    Check "关键词检索命中猫粮相关商品（≥3 件）" $false $_.Exception.Message
}

try {
    $r = Invoke-WebRequest -Uri "$Gateway/api/products?sort=sales&pageSize=5" -UseBasicParsing -TimeoutSec 20
    $items = @((($r.Content | ConvertFrom-Json).data).items)
    $first = $items[0].salesCount
    $last = $items[$items.Count - 1].salesCount
    Check "销量排序生效（首项销量 ≥ 末项）" ([int]$first -ge [int]$last) "$first >= $last"
} catch {
    Check "销量排序生效（首项销量 ≥ 末项）" $false $_.Exception.Message
}

$banned = [uri]::EscapeDataString('象牙')
$bc = 0
try {
    $r = Invoke-WebRequest -Uri "$Gateway/api/products?keyword=$banned" -UseBasicParsing -TimeoutSec 20
    $bc = [int]$r.StatusCode
} catch {
    $bc = [int]$_.Exception.Response.StatusCode.value__
}
Check "搜索违禁词被拦截（422）" ($bc -eq 422) $bc
$bannedRaw = ((& curl.exe -s "$Gateway/api/products?keyword=$banned" 2>$null) -join '')
Check "违禁词拦截返回 BUSINESS_RULE 错误码" ($bannedRaw -match '"code"\s*:\s*"BUSINESS_RULE"') $bannedRaw

$sc = 0
try {
    $r = Invoke-WebRequest -Uri "$Gateway/api/products?sort=hack" -UseBasicParsing -TimeoutSec 20
    $sc = [int]$r.StatusCode
} catch {
    $sc = [int]$_.Exception.Response.StatusCode.value__
}
Check "非法排序参数被拒绝（400）" ($sc -eq 400) $sc

# ---------- 9. 商品域 · 详情与 C2C 闲置 ----------
Write-Host ""
Write-Host "> 商品域 · 详情与闲置"
try {
    $r = Invoke-WebRequest -Uri "$Gateway/api/products/1001" -UseBasicParsing -TimeoutSec 20
    $d = ($r.Content | ConvertFrom-Json).data
    Check "商品详情返回 200 且含 2 个 SKU" (@($d.skus).Count -eq 2) (@($d.skus).Count)
    Check "详情聚合适用属性（食品 / 猫咪 / 过敏原）" `
        (($d.suitability.food -eq $true) -and (@($d.suitability.species) -contains 'cat') -and (@($d.suitability.allergens) -contains '鸡肉')) `
        (@($d.suitability.species) -join '/')
    Check "SKU 由服务端标注可售状态" ($d.skus[0].sellable -eq $true) $d.skus[0].sellable
    Check "详情含同类推荐" (@($d.sameCategory).Count -ge 1) (@($d.sameCategory).Count)
} catch {
    Check "商品详情返回 200 且含 2 个 SKU" $false $_.Exception.Message
}

$nc = 0
try {
    $r = Invoke-WebRequest -Uri "$Gateway/api/products/999999" -UseBasicParsing -TimeoutSec 20
    $nc = [int]$r.StatusCode
} catch {
    $nc = [int]$_.Exception.Response.StatusCode.value__
}
Check "不存在的商品返回 404" ($nc -eq 404) $nc

try {
    # 列表端点是 /api/listings/search：单段的 /api/listings 是**发布端点**（POST），
    # 网关白名单刻意不放行它（按路径匹配、不区分方法），GET 裸路径只会拿到 401。
    $r = Invoke-WebRequest -Uri "$Gateway/api/listings/search?pageSize=50" -UseBasicParsing -TimeoutSec 20
    $l = ($r.Content | ConvertFrom-Json).data
    $lids = @($l.items | ForEach-Object { $_.id })
    # 断言「不少于 8 条」而不是「恰好 8 条」：库里已累积历次探针创建并通过审核的闲置
    # （实测 42 条 = 种子 8 + 历史探针产物）。写死 8 会让断言随环境数据漂移而变红，
    # 反而把下面两条**真正要守**的断言（不含待审 / 不含驳回）淹没在噪声里 ——
    # 计数类断言要钉「不变的性质」，不要钉「当前有几条」。
    Check "闲置列表只返回已审核通过的（≥8 条且均为上架）" ($l.total -ge 8) $l.total
    Check "待审核闲置不出现在列表" (-not ($lids -contains 3009)) ($lids -join ',')
    Check "已驳回闲置不出现在列表" (-not ($lids -contains 3010)) ($lids -join ',')
} catch {
    Check "闲置列表只返回已审核通过的 8 条" $false $_.Exception.Message
}

# 未审核内容即使拿到 id 直访也必须不可见，否则「先发布再发链接」就绕过了审核
$lc = 0
try {
    $r = Invoke-WebRequest -Uri "$Gateway/api/listings/3009" -UseBasicParsing -TimeoutSec 20
    $lc = [int]$r.StatusCode
} catch {
    $lc = [int]$_.Exception.Response.StatusCode.value__
}
Check "待审核闲置按 id 直访返回 404" ($lc -eq 404) $lc

try {
    $r = Invoke-WebRequest -Uri "$Gateway/api/listings/3001" -UseBasicParsing -TimeoutSec 20
    $d = ($r.Content | ConvertFrom-Json).data
    Check "闲置详情返回 200 且带「同款全新」推荐" `
        (($r.StatusCode -eq 200) -and (@($d.sameCategoryNew).Count -ge 1)) (@($d.sameCategoryNew).Count)
} catch {
    Check "闲置详情返回 200 且带「同款全新」推荐" $false $_.Exception.Message
}

Write-Host ""
Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
if ($fail -gt 0) {
    Write-Host "失败项："
    $failures | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
exit 0
