# A8 · 收货地址簿 端到端冒烟（Windows PowerShell 5.1）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\address-smoke.ps1
#
# 前置：容器全部 healthy（docker compose up -d），且已 mvn package + docker compose build 出最新镜像。
#
# 覆盖 docs/agent/07 的 A8 四条验收标准：
#   ① 地址归属校验（他人地址 403 / 不可枚举）；
#   ② 默认地址唯一（设默认自动取消旧的）；
#   ③ 用 addressId 下单时订单快照写入地址文本（历史订单可追溯）；
#   ④ 无 addressId 时旧逻辑不受影响（回归断言）。
#
# 另外覆盖（07 没写但真实存在、不测迟早出事的）：
#   · 首个地址自动成为默认（否则结算页无地址可预选）；
#   · 删除默认地址后默认顺延（不留「一个默认都没有」的状态）；
#   · 直辖市拼接去重（「北京市北京市朝阳区」是程序拼错的典型症状）；
#   · ★ 改地址簿 / 删地址后，历史订单的收货信息**逐字不变**（快照的真正含义）；
#   · ★ 内部快照接口必须凭据保护：不带 / 带错 X-Internal-Token 一律 403；
#   · ★ 内部快照接口未登录也走不通网关（不进白名单）；
#   · 地址簿不进网关白名单（未登录 401）；
#   · 未登录不能下单（新增 addressId 参数没有把下单接口变成匿名接口）；
#   · 越权删除他人地址失败后，对方地址仍在（不是「删了但报错」这种半成功）。
#
# 设计原则（沿用前几套脚本的教训）：
#   1. 夹具从接口动态取（SKU / SPU / 价格不硬编码）；改库前先记录、结尾自证复原；
#   2. 断言用**业务键**（订单号 / 地址 id 的低位可读值），不拿完整 19 位雪花 id 做 JSON 数字比较；
#   3. 「断言总数守卫」动态数脚本里 Check 的条数，与实跑条数比对（T-31 加强版）。
#
# 踩坑备忘（见 docs/agent/08 的 T 表）：
#   · T-36：Call 必须显式声明 [hashtable]$headers 形参，否则实参被静默丢弃 —— 本脚本已遵守；
#   · T-37：源码改了必须重建镜像，否则跑的是旧容器（本脚本假设已 build 过）。
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    [string]$User = 'http://127.0.0.1:8081',
    [string]$Trade = 'http://127.0.0.1:8083',
    [string]$Password = 'duoduo123',
    [string]$InternalToken = 'duoduo-local-dev-internal-token',
    [int]$SKU = 2001
)

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

function Call([string]$method, [string]$uri, $session, $bodyObj, [hashtable]$headers) {
    $params = @{ Uri = $uri; Method = $method; UseBasicParsing = $true; TimeoutSec = 25 }
    if ($session) { $params.WebSession = $session }
    if ($headers) { $params.Headers = $headers }
    if ($null -ne $bodyObj) {
        $params.Body = ($bodyObj | ConvertTo-Json -Compress -Depth 8)
        $params.ContentType = 'application/json; charset=utf-8'
    }
    try {
        $r = Invoke-WebRequest @params
        return @{ status = [int]$r.StatusCode; body = ($r.Content | ConvertFrom-Json); raw = $r.Content }
    } catch {
        $code = 0; $body = $null; $raw = $null
        try { $code = [int]$_.Exception.Response.StatusCode.value__ } catch { }
        try { if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $raw = $_.ErrorDetails.Message; $body = $raw | ConvertFrom-Json } } catch { }
        if ($null -eq $body) {
            # 跨 PS 版本读错误响应体：PS5.1 的 Response 是 HttpWebResponse（GetResponseStream），
            # pwsh 7（CI Linux）的是 HttpResponseMessage —— 只能 Content.ReadAsStringAsync 读
            try {
                $resp = $_.Exception.Response
                $t = $null
                if ($resp -and $resp.PSObject.Methods.Name -contains 'GetResponseStream') {
                    $st = $resp.GetResponseStream()
                    if ($st) { $t = (New-Object System.IO.StreamReader($st)).ReadToEnd() }
                } elseif ($resp -and $null -ne $resp.Content) {
                    $t = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                }
                if ($t) { $raw = $t; $body = $t | ConvertFrom-Json }
            } catch { }
        }
        return @{ status = $code; body = $body; raw = $raw }
    }
}

function Register([string]$phone, [string]$nickname) {
    $s = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $r = Call 'Post' "$Gateway/api/auth/register" $s @{ phone = $phone; password = $Password; nickname = $nickname }
    if ($r.status -eq 409) { $r = Call 'Post' "$Gateway/api/auth/login" $s @{ phone = $phone; password = $Password } }
    return @{ session = $s; result = $r }
}

function Sql([string]$db, [string]$sql) {
    $env:PATH = 'C:\Program Files\Docker\Docker\resources\bin;' + $env:PATH
    if (-not $env:DOCKER_CONFIG) { $env:DOCKER_CONFIG = 'D:\duoduo-infra\docker-nocreds' }
    $out = & docker exec duoduo-mysql mysql -uduoduo -pduoduo -N -B --default-character-set=utf8mb4 -D $db -e $sql 2>&1
    if ($LASTEXITCODE -ne 0) { return $null }
    return ((@($out) | Where-Object { "$_" -notmatch 'Warning' }) -join "`n").Trim()
}

function NewKey { return [guid]::NewGuid().ToString() }

# 说明：Sql 只用于**数字 / ASCII** 的回读（计数、库存、id、状态）。
#
# ★ 不要用它回读中文做字符串比较。原因：PowerShell 5.1 从原生进程接收 stdout 时
#   按控制台 OEM 代码页（简体中文环境是 CP936）解码，而 mysql 客户端输出的是 UTF-8 字节，
#   于是「王五」在 PS 里变成「鐜嬩簲」。这不是数据坏了 —— 库内 HEX 是干净的
#   E78E8BE4BA94（单层 UTF-8），只是**读取端解码错了**。
#   要校验中文，用 HEX() 比对十六进制，或走接口返回的 JSON（JSON 由 .NET 正确解码）。
#   本脚本里中文字段的断言一律打在**接口响应**上，库内校验只用 HEX / COUNT。
function Sql([string]$db, [string]$sql) {
    $env:PATH = 'C:\Program Files\Docker\Docker\resources\bin;' + $env:PATH
    if (-not $env:DOCKER_CONFIG) { $env:DOCKER_CONFIG = 'D:\duoduo-infra\docker-nocreds' }
    $out = & docker exec duoduo-mysql mysql -uduoduo -pduoduo -N -B --default-character-set=utf8mb4 -D $db -e $sql 2>&1
    if ($LASTEXITCODE -ne 0) { return $null }
    return ((@($out) | Where-Object { "$_" -notmatch 'Warning' }) -join "`n").Trim()
}

# 建地址的快捷方法：返回 @{ status; body; raw }
function NewAddr($sess, [string]$name, [string]$phone, [string]$prov, [string]$city,
                 [string]$dist, [string]$detail, [string]$label, $isDefault) {
    $payload = @{
        receiverName  = $name
        receiverPhone = $phone
        province      = $prov
        city          = $city
        district      = $dist
        detail        = $detail
    }
    if ($null -ne $label) { $payload.label = $label }
    if ($null -ne $isDefault) { $payload.isDefault = $isDefault }
    return (Call 'Post' "$Gateway/api/addresses" $sess $payload)
}

# ★ 雪花 id 是 19 位，超过 JS / PowerShell 部分场景的安全整数范围。
# 全部按**字符串**比较，绝不做 [long] / 数值运算 —— 一旦转换就会尾数归零，
# 表现为「明明查到的地址 id 是对的，比较却不等」。
function AddrId($resp) { return "$($resp.body.data.id)" }

$H_INTERNAL = @{ 'X-Internal-Token' = $InternalToken }
$H_BAD = @{ 'X-Internal-Token' = 'wrong-token-should-be-rejected' }

$phoneA = '138' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneB = '137' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$phoneC = '136' + (Get-Random -Minimum 10000000 -Maximum 99999999)

Write-Host ""
Write-Host "=== 多多宠物商店 · 收货地址簿（A8）冒烟 @ $Gateway ==="
Write-Host "用户 A = $phoneA ｜ 用户 B = $phoneB ｜ 用户 C（下单用）= $phoneC"
Write-Host ""

# ---------- 0. 就绪 ----------
Write-Host "> 就绪检查"
$ready = $false
for ($i = 0; $i -lt 40; $i++) {
    try {
        $h = Invoke-WebRequest -Uri "$Gateway/actuator/health" -UseBasicParsing -TimeoutSec 5
        if ($h.StatusCode -eq 200) { $ready = $true; break }
    } catch { Start-Sleep -Seconds 1 }
}
Check "网关 /actuator/health 就绪" $ready
if (-not $ready) { Write-Host "网关未就绪，终止"; exit 1 }
$userReady = $false
for ($i = 0; $i -lt 40; $i++) {
    try {
        $h = Invoke-WebRequest -Uri "$User/actuator/health" -UseBasicParsing -TimeoutSec 5
        if ($h.StatusCode -eq 200) { $userReady = $true; break }
    } catch { Start-Sleep -Seconds 1 }
}
Check "用户服务 /actuator/health 就绪" $userReady
if (-not $userReady) { Write-Host "用户服务未就绪，终止"; exit 1 }

# ---------- 1. 账号夹具 ----------
Write-Host ""
Write-Host "> 账号夹具"
$regA = Register $phoneA '地址测试-用户A'
Check "用户 A 注册/登录成功" ($regA.result.status -eq 200) $regA.result.status
$sessA = $regA.session
$regB = Register $phoneB '地址测试-用户B'
Check "用户 B 注册/登录成功" ($regB.result.status -eq 200) $regB.result.status
$sessB = $regB.session
$regC = Register $phoneC '地址测试-用户C'
Check "用户 C 注册/登录成功" ($regC.result.status -eq 200) $regC.result.status
$sessC = $regC.session
$idA = Sql 'duoduo_user' "SELECT id FROM t_user WHERE phone = '$phoneA';"
$idB = Sql 'duoduo_user' "SELECT id FROM t_user WHERE phone = '$phoneB';"
Check "（前置）取得用户 A / B 的 userId" ((-not [string]::IsNullOrEmpty($idA)) -and (-not [string]::IsNullOrEmpty($idB))) "A=$idA B=$idB"

# ---------- 2. 新用户空地址簿 ----------
Write-Host ""
Write-Host "> 新用户：空地址簿（不是报错）"
$r = Call 'Get' "$Gateway/api/addresses" $sessA $null
Check "新用户地址列表返回 200 且为空数组" ($r.status -eq 200 -and @($r.body.data).Count -eq 0) "$($r.status)/$(@($r.body.data).Count)"
$r = Call 'Get' "$Gateway/api/addresses/default" $sessA $null
Check "新用户默认地址返回 200 且 data 为 null（前端据此渲染「去添加」）" ($r.status -eq 200 -and $null -eq $r.body.data) "$($r.status)/$(if ($null -eq $r.body.data) { 'null' } else { 'not-null' })"

# ---------- 3. 新增地址与「首个自动默认」（验收②） ----------
Write-Host ""
Write-Host "> 验收②：首个地址自动成为默认"
$r1 = NewAddr $sessA '张小三' '13900001111' '上海市' '上海市' '浦东新区' '张江路 1 号 101 室' '家' $null
Check "新增地址①成功（200）" ($r1.status -eq 200) "$($r1.status)/$($r1.body.message)"
$a1 = AddrId $r1
Check "② 首个地址 isDefault=true（未显式指定也自动成为默认）" ($r1.body.data.isDefault -eq $true) $r1.body.data.isDefault
Check "★ 直辖市拼接去重：不含「上海市上海市」" ($r1.body.data.fullAddress -notmatch '上海市上海市') $r1.body.data.fullAddress
Check "★ 完整地址 == 省+区+明细（直辖市只留一次市名）" ($r1.body.data.fullAddress -eq '上海市浦东新区张江路 1 号 101 室') $r1.body.data.fullAddress

$r2 = NewAddr $sessA '张小三' '13900001111' '浙江省' '杭州市' '西湖区' '文三路 99 号' '公司' $null
Check "新增地址②成功（200）" ($r2.status -eq 200) "$($r2.status)/$($r2.body.message)"
$a2 = AddrId $r2
Check "② 第二个地址 isDefault=false（不抢默认）" ($r2.body.data.isDefault -eq $false) $r2.body.data.isDefault
Check "★ 非直辖市拼接为 省+市+区+明细" ($r2.body.data.fullAddress -eq '浙江省杭州市西湖区文三路 99 号') $r2.body.data.fullAddress

$dbDefault = Sql 'duoduo_user' "SELECT COUNT(*) FROM t_user_address WHERE user_id = $idA AND is_default = 1;"
Check "② 库内默认地址恰好 1 条" ($dbDefault -eq '1') $dbDefault

# ---------- 4. 设默认（验收②） ----------
Write-Host ""
Write-Host "> 验收②：设默认自动取消旧默认"
$r = Call 'Post' "$Gateway/api/addresses/$a2/default" $sessA $null
Check "地址②设为默认成功（200）" ($r.status -eq 200) $r.status
Check "② 返回体中地址② isDefault=true" ($r.body.data.isDefault -eq $true) $r.body.data.isDefault
$dbDefault = Sql 'duoduo_user' "SELECT COUNT(*) FROM t_user_address WHERE user_id = $idA AND is_default = 1;"
Check "② 切换后库内默认地址仍恰好 1 条（旧默认被取消）" ($dbDefault -eq '1') $dbDefault
$dbWhich = Sql 'duoduo_user' "SELECT CAST(id AS CHAR) FROM t_user_address WHERE user_id = $idA AND is_default = 1;"
Check "② 且默认地址就是地址②" ("$dbWhich" -eq "$a2") "db=$dbWhich expect=$a2"
$r = Call 'Get' "$Gateway/api/addresses/default" $sessA $null
Check "② GET /default 返回的是地址②" ("$(AddrId $r)" -eq "$a2") "$(AddrId $r)"
$list = Call 'Get' "$Gateway/api/addresses" $sessA $null
Check "② 列表中默认地址排在最前" ("$(@($list.body.data)[0].id)" -eq "$a2") "$(@($list.body.data)[0].id)"

# ---------- 5. 归属校验（验收①） ----------
Write-Host ""
Write-Host "> 验收①：地址归属校验"
$rB1 = NewAddr $sessB '李小四' '13800002222' '广东省' '深圳市' '南山区' '科技园路 8 号' $null $null
Check "用户 B 建地址成功（200）" ($rB1.status -eq 200) $rB1.status
$b1 = AddrId $rB1
Check "（前置）地址 B① 与 地址 A① 不是同一条" ("$b1" -ne "$a1") $b1

$r = Call 'Put' "$Gateway/api/addresses/$b1" $sessA @{
    receiverName = '越权'; receiverPhone = '13900001111'; province = '上海市'
    city = '上海市'; district = '浦东新区'; detail = '越权改地址'
}
Check "① 用户 A 修改用户 B 的地址 → 4xx（拒绝）" ($r.status -ge 400 -and $r.status -lt 500) "$($r.status)/$($r.body.code)"
Check "① 越权修改返回 404（不区分「不存在」与「无权限」，不泄露存在性）" ($r.status -eq 404) "$($r.status)/$($r.body.code)"
$r = Call 'Post' "$Gateway/api/addresses/$b1/default" $sessA $null
Check "① 用户 A 把用户 B 的地址设为默认 → 404" ($r.status -eq 404) "$($r.status)/$($r.body.code)"
$r = Call 'Delete' "$Gateway/api/addresses/$b1" $sessA $null
Check "① 用户 A 删除用户 B 的地址 → 404" ($r.status -eq 404) "$($r.status)/$($r.body.code)"
$stillThere = Sql 'duoduo_user' "SELECT COUNT(*) FROM t_user_address WHERE id = $b1 AND user_id = $idB;"
Check "① 越权删除失败后，用户 B 的地址仍然存在（不是「删了但报错」）" ($stillThere -eq '1') $stillThere
$r = Call 'Put' "$Gateway/api/addresses/999999999999999" $sessA @{
    receiverName = '幽灵'; receiverPhone = '13900001111'; province = '上海市'
    city = '上海市'; district = '浦东新区'; detail = '不存在的地址'
}
Check "① 不存在的地址 id 修改 → 404（与越权同码）" ($r.status -eq 404) "$($r.status)/$($r.body.code)"
Check "① 错误文案不提示「这不是你的地址」" ($r.raw -notmatch '不是你的|无权|他人') $r.raw

# ---------- 6. 内部快照接口与凭据（验收③的前置） ----------
Write-Host ""
Write-Host "> 内部快照接口：凭据边界"
$r = Call 'Get' "$User/api/addresses/internal/snapshot?userId=$idA&addressId=$a1" $null $null $H_INTERNAL
Check "内部快照（带凭据）返回 200" ($r.status -eq 200) "$($r.status)/$($r.body.code)"
Check "内部快照返回收货人姓名" ($r.body.data.receiverName -eq '张小三') $r.body.data.receiverName
Check "内部快照返回收货手机号" ($r.body.data.receiverPhone -eq '13900001111') $r.body.data.receiverPhone
Check "★ 内部快照的 fullAddress 与地址簿接口**逐字相同**（拼接口径只有一处）" ($r.body.data.fullAddress -eq '上海市浦东新区张江路 1 号 101 室') $r.body.data.fullAddress
$r = Call 'Get' "$User/api/addresses/internal/snapshot?userId=$idA&addressId=$a1" $null $null $null
Check "★ 内部快照：不带 X-Internal-Token → 403" ($r.status -eq 403) "$($r.status)/$($r.body.code)"
$r = Call 'Get' "$User/api/addresses/internal/snapshot?userId=$idA&addressId=$a1" $null $null $H_BAD
Check "★ 内部快照：错误 X-Internal-Token → 403" ($r.status -eq 403) "$($r.status)/$($r.body.code)"
# 内部快照接口「经网关走不通」的证明：带正确凭据、但**没有登录态**时经网关访问 → 401。
#
# 这里刻意把断言打在 401（未登录）而不是更细的 404/403：
# 网关的「路由」与「白名单」是两件不同的事 —— 路由按 Path 把请求转发给服务，
# 白名单决定「是否免鉴权」。/api/addresses/** 已进路由，故未登录访问时
# 网关在做完路由后、转发前就会因缺令牌返回 401，请求根本没到服务。
# 也就是说**「内部路径不经网关暴露」这件事本身靠的是它不在白名单里**，
# 而不是靠网关有什么「内部路径拦截」；真正拦截内部调用的是服务自己的
# InternalTokenFilter（失败关闭，凭据不对一律 403，上面两条断言已覆盖）。
$r = Call 'Get' "$Gateway/api/addresses/internal/snapshot?userId=$idA&addressId=$a1" $null $null $H_INTERNAL
Check "★ 内部快照经网关但无登录态 → 401（内部路径不因带凭据就对外放行）" ($r.status -eq 401) "$($r.status)/$($r.body.code)"
$r = Call 'Get' "$User/api/addresses/internal/snapshot?userId=$idB&addressId=$a1" $null $null $H_INTERNAL
Check "★ 归属不符（用 B 的 userId 查 A 的地址）→ 200 + NOT_FOUND（不泄露存在性）" ($r.status -eq 200 -and $r.body.code -eq 'NOT_FOUND') "$($r.status)/$($r.body.code)"

# ---------- 7. 网关白名单：地址簿需登录 ----------
Write-Host ""
Write-Host "> 网关白名单边界"
$anon = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$r = Call 'Get' "$Gateway/api/addresses" $anon $null
Check "未登录访客读地址列表 → 401（地址簿不进白名单）" ($r.status -eq 401) "$($r.status)/$($r.body.code)"
$r = Call 'Post' "$Gateway/api/addresses" $anon @{
    receiverName = '匿名'; receiverPhone = '13900001111'; province = '上海市'
    city = '上海市'; district = '浦东新区'; detail = '匿名建地址'
}
Check "未登录访客建地址 → 401" ($r.status -eq 401) "$($r.status)/$($r.body.code)"

# ---------- 8. 用 addressId 下单（验收③） ----------
Write-Host ""
Write-Host "> 验收③：用 addressId 下单，订单快照写入地址文本"
$batch = Call 'Get' "$Gateway/api/products/internal/skus/batch?ids=$SKU" $null $null $H_INTERNAL
$snap = @($batch.body.data)[0]
$S = [int]$snap.availableStock
Write-Host "  SKU $SKU 可售 $S"
# 夹具前提不满足时直接退出：这里刻意**不**调用 Check（见 credit-smoke 的同款注释）
if ($S -lt 3) {
    Write-Host "  [FATAL] 夹具前提不满足：SKU $SKU 可售 $S < 3，终止本次测试，不执行任何写操作。"
    Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
    exit 1
}
Check "夹具前提：SKU $SKU 可售 >= 3" ($S -ge 3) $S
$dbStockBefore = [int](Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU;")
Check "（前置）库内库存与接口可售一致（冻结为 0）" ($dbStockBefore -eq $S) "db=$dbStockBefore api=$S"

# 用用户 C 的地址簿下单（C 先建地址）
$rC1 = NewAddr $sessC '王五' '13500003333' '北京市' '北京市' '朝阳区' '建国路 88 号 A 座' '公司' $null
Check "用户 C 建地址成功（200）" ($rC1.status -eq 200) $rC1.status
$c1 = AddrId $rC1
Check "★ 直辖市「北京市 / 北京市」拼接去重" ($rC1.body.data.fullAddress -eq '北京市朝阳区建国路 88 号 A 座') $rC1.body.data.fullAddress

Call 'Post' "$Gateway/api/cart/items" $sessC @{ skuId = $SKU; quantity = 1 } | Out-Null
$o1 = Call 'Post' "$Gateway/api/orders" $sessC @{
    addressId = $c1
    remark = 'A8 用地址簿下单'
    idempotentKey = (NewKey)
}
Check "③ 只传 addressId（不传 receiver* 三字段）下单成功（200）" ($o1.status -eq 200) "$($o1.status)/$($o1.body.message)"

# ★ 断言总数守卫要求「声明的 Check 条数 == 实跑条数」。
#   因此这里**刻意不做 if/else 分支**：若把 7 条断言塞进 if，下单失败时会少跑 7 条，
#   守卫就会报「有 Check 被静默跳过」——那会把一次真实的业务失败，伪装成一次脚本缺陷。
#   正确做法是：把「下单成功」这件事先算成一个布尔，再让所有断言无条件执行。
#   这样无论成败，跑过的条数恒定，守卫的语义（漏写 Check）才不被污染。
$o1ok = ($o1.status -eq 200)
$no1 = if ($o1ok) { @($o1.body.data.orders)[0].orderNo } else { $null }
$o1order = if ($o1ok) { @($o1.body.data.orders)[0] } else { $null }

Check "③ 订单收货人 == 地址簿姓名" ($o1ok -and $o1order.receiverName -eq '王五') $(if ($o1ok) { $o1order.receiverName } else { "下单未成功：$($o1.body.message)" })
Check "③ 订单收货电话 == 地址簿电话" ($o1ok -and $o1order.receiverPhone -eq '13500003333') $(if ($o1ok) { $o1order.receiverPhone } else { "下单未成功" })
Check "③ 订单收货地址 == 地址簿完整地址（写入快照）" ($o1ok -and $o1order.receiverAddress -eq '北京市朝阳区建国路 88 号 A 座') $(if ($o1ok) { $o1order.receiverAddress } else { "下单未成功" })

# 库内校验走 HEX（不比对中文字符串，理由见 Sql 函数上方注释）：
#   「王五」                        → E78E8BE4BA94
#   「北京市朝阳区建国路 88 号 A 座」 → E58C97E4BAACE5B882E69C9DE998B3E58CBAE5BBBAE59BBDE8B7AF20383820E58FB7204120E5BAA7
# 用 HEX 逐字节比对比「读回来再比字符串」更严格：它同时证明了编码层次正确（单层 UTF-8，非双重编码）。
$dbRecv = if ($no1) { Sql 'duoduo_trade' "SELECT CONCAT(HEX(receiver_name),'|',receiver_phone,'|',HEX(receiver_address)) FROM t_order WHERE order_no = '$no1';" } else { '' }
$recvBefore = "$dbRecv"
$recvParts = if ($recvBefore -match '\|') { $recvBefore -split '\|' } else { @('', '', '') }
Check "③ 库内 receiver_name 为单层 UTF-8 的「王五」" ($recvParts[0] -eq 'E78E8BE4BA94') "hex=$($recvParts[0])"
Check "③ 库内 receiver_phone 正确落值" ($recvParts[1] -eq '13500003333') $recvParts[1]
Check "③ 库内 receiver_address 与地址簿完整地址**逐字节相同**" ($recvParts[2] -eq 'E58C97E4BAACE5B882E69C9DE998B3E58CBAE5BBBAE59BBDE8B7AF20383820E58FB7204120E5BAA7') "hex=$($recvParts[2])"

# ---------- 9. 快照的真正含义：改 / 删地址后历史订单不变（验收③加强） ----------
Write-Host ""
Write-Host "> 验收③加强：改 / 删地址簿后，历史订单收货信息逐字不变"
$r = Call 'Put' "$Gateway/api/addresses/$c1" $sessC @{
    receiverName = '王五改名后'; receiverPhone = '13500009999'; province = '北京市'
    city = '北京市'; district = '海淀区'; detail = '中关村大街 1 号'
}
Check "用户 C 修改了收货地址（200）" ($r.status -eq 200) $r.status
Check "（前置）地址簿已确实变化" ($r.body.data.fullAddress -eq '北京市海淀区中关村大街 1 号') $r.body.data.fullAddress
if ($no1) {
    $dbRecv2 = Sql 'duoduo_trade' "SELECT CONCAT(HEX(receiver_name),'|',receiver_phone,'|',HEX(receiver_address)) FROM t_order WHERE order_no = '$no1';"
}
Check "★ 改地址后历史订单收货信息**逐字节不变**（快照未被地址簿联动）" (($no1 -ne $null) -and ("$dbRecv2" -eq "$recvBefore")) "$dbRecv2"
$r = Call 'Delete' "$Gateway/api/addresses/$c1" $sessC $null
Check "用户 C 删除了该地址（200）" ($r.status -eq 200) $r.status
if ($no1) {
    $dbRecv3 = Sql 'duoduo_trade' "SELECT CONCAT(HEX(receiver_name),'|',receiver_phone,'|',HEX(receiver_address)) FROM t_order WHERE order_no = '$no1';"
}
Check "★ 删地址后历史订单收货信息**仍然逐字节不变**" (($no1 -ne $null) -and ("$dbRecv3" -eq "$recvBefore")) "$dbRecv3"

# ---------- 10. 回归：无 addressId 时旧逻辑不受影响（验收④） ----------
Write-Host ""
Write-Host "> 验收④：无 addressId 时旧逻辑不受影响（回归断言）"
Call 'Post' "$Gateway/api/cart/items" $sessC @{ skuId = $SKU; quantity = 1 } | Out-Null
$o2 = Call 'Post' "$Gateway/api/orders" $sessC @{
    receiverName = '赵六'; receiverPhone = '13511112222'
    receiverAddress = '江苏省南京市玄武区中山路 100 号'
    remark = 'A8 回归：直填三字段'
    idempotentKey = (NewKey)
}
Check "④ 只传 receiver* 三字段（不传 addressId）下单成功（200）" ($o2.status -eq 200) "$($o2.status)/$($o2.body.message)"
# 同上：不做 if/else 分支，保证断言条数恒定（守卫语义不被业务失败污染）
$o2ok = ($o2.status -eq 200)
$no2 = if ($o2ok) { @($o2.body.data.orders)[0].orderNo } else { $null }
$o2order = if ($o2ok) { @($o2.body.data.orders)[0] } else { $null }
Check "④ 直填下单的收货人 == 客户端传值" ($o2ok -and $o2order.receiverName -eq '赵六') $(if ($o2ok) { $o2order.receiverName } else { "下单未成功" })
Check "④ 直填下单的收货电话 == 客户端传值" ($o2ok -and $o2order.receiverPhone -eq '13511112222') $(if ($o2ok) { $o2order.receiverPhone } else { "下单未成功" })
Check "④ 直填下单的收货地址 == 客户端传值（未被任何地址簿逻辑改写）" ($o2ok -and $o2order.receiverAddress -eq '江苏省南京市玄武区中山路 100 号') $(if ($o2ok) { $o2order.receiverAddress } else { "下单未成功" })
# ★ 直填路径的库内快照也必须正确落值（HEX 校验，理由同第 8 节）
$dbRecvDirect = if ($no2) { Sql 'duoduo_trade' "SELECT CONCAT(HEX(receiver_name),'|',receiver_phone,'|',HEX(receiver_address)) FROM t_order WHERE order_no = '$no2';" } else { '' }
Check "④ 库内 receiver_name 为单层 UTF-8 的「赵六」" (($dbRecvDirect -split '\|')[0] -eq 'E8B5B5E585AD') "hex=$(($dbRecvDirect -split '\|')[0])"

# 边界：两种都不传 → 4xx（而不是建出一张没有收货信息的订单）
Call 'Post' "$Gateway/api/cart/items" $sessC @{ skuId = $SKU; quantity = 1 } | Out-Null
$o3 = Call 'Post' "$Gateway/api/orders" $sessC @{ remark = 'A8 边界：什么都不传'; idempotentKey = (NewKey) }
Check "④ 既无 addressId 也无 receiver* → 4xx（拒单，不产生无收货信息的订单）" ($o3.status -ge 400 -and $o3.status -lt 500) "$($o3.status)/$($o3.body.code)"
$leakOrders = Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_order WHERE remark = 'A8 边界：什么都不传';"
Check "④ 拒单后没有落库任何订单（校验前置）" ($leakOrders -eq '0') $leakOrders
$o4 = Call 'Post' "$Gateway/api/orders" $sessC @{
    addressId = 999999999999999
    idempotentKey = (NewKey)
}
Check "④ 用不存在的 addressId 下单 → 4xx（拒单）" ($o4.status -ge 400 -and $o4.status -lt 500) "$($o4.status)/$($o4.body.code)"
Check "④ 拒单文案指向「地址不可用」，而不是 500 或泛化的系统错误" ($o4.raw -match '地址') "$($o4.status)/$($o4.body.code)/raw=$($o4.raw)"
$o5 = Call 'Post' "$Gateway/api/orders" $sessC @{
    addressId = $a1
    idempotentKey = (NewKey)
}
Check "★ 用**他人**的 addressId 下单 → 4xx（归属校验在下单链路上也生效）" ($o5.status -ge 400 -and $o5.status -lt 500) "$($o5.status)/$($o5.body.code)"

$anon2 = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$o6 = Call 'Post' "$Gateway/api/orders" $anon2 @{
    addressId = $c1
    idempotentKey = (NewKey)
}
Check "④ 未登录下单 → 401（新增 addressId 没有把下单接口变成匿名接口）" ($o6.status -eq 401) "$($o6.status)/$($o6.body.code)"

# ---------- 11. 校验边界 ----------
Write-Host ""
Write-Host "> 校验边界"
$r = NewAddr $sessA '' '13900001111' '上海市' '上海市' '浦东新区' '某路 1 号' $null $null
Check "收货人为空 → 4xx" ($r.status -ge 400 -and $r.status -lt 500) "$($r.status)/$($r.body.code)"
$r = NewAddr $sessA '张三' '12345' '上海市' '上海市' '浦东新区' '某路 1 号' $null $null
Check "手机号非法 → 4xx" ($r.status -ge 400 -and $r.status -lt 500) "$($r.status)/$($r.body.code)"
$r = NewAddr $sessA '张三' '13900001111' '' '上海市' '浦东新区' '某路 1 号' $null $null
Check "省份为空 → 4xx" ($r.status -ge 400 -and $r.status -lt 500) "$($r.status)/$($r.body.code)"
$r = NewAddr $sessA '张三' '13900001111' '上海市' '上海市' '浦东新区' ('长' * 250) $null $null
Check "详细地址超长（250 字）→ 4xx" ($r.status -ge 400 -and $r.status -lt 500) "$($r.status)/$($r.body.code)"

# ---------- 12. 删除默认地址 → 默认顺延 ----------
Write-Host ""
Write-Host "> 删除默认地址：默认顺延（不留空默认）"
# 此时 A 有 a1（非默认）、a2（默认）两条
$r = Call 'Delete' "$Gateway/api/addresses/$a2" $sessA $null
Check "删除当前默认地址（a2）成功" ($r.status -eq 200) $r.status
$dbDefault = Sql 'duoduo_user' "SELECT COUNT(*) FROM t_user_address WHERE user_id = $idA AND is_default = 1;"
Check "★ 删除默认后仍有 1 条默认（顺延，不是 0 条）" ($dbDefault -eq '1') $dbDefault
$dbWhich = Sql 'duoduo_user' "SELECT CAST(id AS CHAR) FROM t_user_address WHERE user_id = $idA AND is_default = 1;"
Check "★ 顺延的默认地址是剩下那条（a1）" ("$dbWhich" -eq "$a1") "db=$dbWhich expect=$a1"
$r = Call 'Delete' "$Gateway/api/addresses/$a1" $sessA $null
Check "删除最后一条地址成功" ($r.status -eq 200) $r.status
$leftA = Sql 'duoduo_user' "SELECT COUNT(*) FROM t_user_address WHERE user_id = $idA;"
Check "★ 用户 A 地址已清空（库内 0 条）" ($leftA -eq '0') $leftA
$r = Call 'Get' "$Gateway/api/addresses/default" $sessA $null
Check "★ 清空后 GET /default 返回 200 + null（不报错）" ($r.status -eq 200 -and $null -eq $r.body.data) "$($r.status)"

# ---------- 13. 清理 ----------
Write-Host ""
Write-Host "> 清理夹具与自证复原"
Sql 'duoduo_user' "DELETE FROM t_user_address WHERE user_id IN ($idA, $idB, (SELECT id FROM (SELECT id FROM t_user WHERE phone = '$phoneC') x));" | Out-Null
$leftAll = Sql 'duoduo_user' "SELECT COUNT(*) FROM t_user_address WHERE user_id IN ($idA, $idB, (SELECT id FROM (SELECT id FROM t_user WHERE phone = '$phoneC') x));"
Check "地址夹具已删除（库内复原）" ($leftAll -eq '0') $leftAll
if ($no1) {
    Sql 'duoduo_trade' "DELETE FROM t_order_item WHERE order_id IN (SELECT id FROM t_order WHERE order_no = '$no1');" | Out-Null
    Sql 'duoduo_trade' "DELETE FROM t_settlement WHERE order_id IN (SELECT id FROM t_order WHERE order_no = '$no1');" | Out-Null
    Sql 'duoduo_trade' "DELETE FROM t_stock_freeze WHERE order_id IN (SELECT id FROM t_order WHERE order_no = '$no1');" | Out-Null
    Sql 'duoduo_trade' "DELETE FROM t_order WHERE order_no = '$no1';" | Out-Null
}
if ($no2) {
    Sql 'duoduo_trade' "DELETE FROM t_order_item WHERE order_id IN (SELECT id FROM t_order WHERE order_no = '$no2');" | Out-Null
    Sql 'duoduo_trade' "DELETE FROM t_settlement WHERE order_id IN (SELECT id FROM t_order WHERE order_no = '$no2');" | Out-Null
    Sql 'duoduo_trade' "DELETE FROM t_stock_freeze WHERE order_id IN (SELECT id FROM t_order WHERE order_no = '$no2');" | Out-Null
    Sql 'duoduo_trade' "DELETE FROM t_order WHERE order_no = '$no2';" | Out-Null
}
$leftOrders = Sql 'duoduo_trade' "SELECT COUNT(*) FROM t_order WHERE order_no IN ('$no1','$no2');"
Check "订单夹具已删除（库内复原）" ($leftOrders -eq '0') $leftOrders
Sql 'duoduo_product' "UPDATE t_sku SET stock = $dbStockBefore, frozen_stock = 0 WHERE id = $SKU;" | Out-Null
Check "★ SKU $SKU 库存已复原为测试前的 $dbStockBefore" ("$(Sql 'duoduo_product' "SELECT stock FROM t_sku WHERE id = $SKU;")" -eq "$dbStockBefore")
Check "★ 冻结量已清零" ((Sql 'duoduo_product' "SELECT COALESCE(frozen_stock,0) FROM t_sku WHERE id = $SKU;") -eq '0')

# ---------- 结果 ----------
Write-Host ""
$declared = ([regex]::Matches((Get-Content -Raw -LiteralPath $PSCommandPath), '(?m)^\s*Check "')).Count
$ran = $pass + $fail
if ($ran -ne $declared) {
    Write-Host "  [GUARD-FAIL] 断言总数守卫：脚本内声明 $declared 项，实际执行 $ran 项 —— 有 Check 被静默跳过（多半是调用写法/参数写错）"
    $fail++
    $failures += "断言总数守卫（实际执行 $ran 项 / 声明 $declared 项）"
} else {
    Write-Host "  [GUARD-OK] 断言总数守卫：$ran 项全部执行，无静默跳过"
}
Write-Host ""
Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
if ($fail -gt 0) {
    Write-Host "失败项："
    $failures | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
exit 0
