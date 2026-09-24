# 检索引擎（A10 · Elasticsearch）端到端冒烟（PowerShell 5.1 / pwsh 7 双兼容）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\search-smoke.ps1
# 可选： -SkipDegrade  跳过「ES 停服降级回 MySQL」一段（该段会 stop/start duoduo-es 容器）
#
# 前置：
#   1. 全量容器已起（含 product，且 DUODUO_SEARCH_ENABLED=true）；
#   2. ES 已起：docker compose --profile search up -d duoduo-es
#
# 覆盖 A10 四条验收：
#   ① 同义词与拼音容错   —— 用「MySQL 无论如何都查不到」的查询词反证 ES 路径确实生效；
#   ② 索引与库最终一致   —— 对账接口 missing / stale / extra 三项为空；
#   ③ 上下架 N 秒内反映  —— 改库后轮询，断言在扫描周期内从检索结果中消失 / 恢复；
#   ④ 检索不可用降级     —— 停掉 ES，检索仍 200 且返回 MySQL 结果。
#
# 设计要点：所有「ES 专属能力」的断言都配一条 SQL 反证（同一查询词在 MySQL 下命中数为 0）——
# 否则「查得到」无法区分是 ES 生效还是降级后在 MySQL 里撞上的，断言就没有证明力。

param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    [string]$EsHttp = 'http://127.0.0.1:9200',
    [int]$WaitSec = 60,
    [switch]$SkipDegrade
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
    $params = @{ Uri = $uri; Method = $method; UseBasicParsing = $true; TimeoutSec = 60 }
    if ($session) { $params.WebSession = $session }
    if ($headers) { $params.Headers = $headers }
    if ($null -ne $bodyObj) {
        $params.Body = ($bodyObj | ConvertTo-Json -Compress -Depth 6)
        $params.ContentType = 'application/json; charset=utf-8'
    }
    try {
        $r = Invoke-WebRequest @params
        return @{ status = [int]$r.StatusCode; body = ($r.Content | ConvertFrom-Json) }
    } catch {
        $code = 0; $body = $null
        try { $code = [int]$_.Exception.Response.StatusCode.value__ } catch { }
        try { if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $body = $_.ErrorDetails.Message | ConvertFrom-Json } } catch { }
        if ($null -eq $body) {
            try {
                # 跨 PS 版本：PS5.1 用 GetResponseStream，pwsh 7 用 Content.ReadAsStringAsync
                $resp = $_.Exception.Response
                $t = $null
                if ($resp -and $resp.PSObject.Methods.Name -contains 'GetResponseStream') {
                    $st = $resp.GetResponseStream()
                    if ($st) { $t = (New-Object System.IO.StreamReader($st)).ReadToEnd() }
                } elseif ($resp -and $null -ne $resp.Content) {
                    $t = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                }
                if ($t) { $body = $t | ConvertFrom-Json }
            } catch { }
        }
        return @{ status = $code; body = $body }
    }
}

# 刻意不叫 Docker：PowerShell 命令名大小写不敏感，函数 `Docker` 会与外部命令 `docker`
# 撞名，函数体里的 `& docker ...` 会递归调用自己 —— docker 根本没执行、返回空数组，
# SQL 静默失败（表现为「提权没生效 → 对账接口 403」，极难定位）。
function Dkr([string[]]$cmdArgs) {
    if ($env:OS -eq 'Windows_NT') {
        $env:PATH = 'C:\Program Files\Docker\Docker\resources\bin;' + $env:PATH
        if (-not $env:DOCKER_CONFIG) { $env:DOCKER_CONFIG = 'D:\duoduo-infra\docker-nocreds' }
    }
    return (& docker @cmdArgs 2>&1)
}

function Sql([string]$db, [string]$sql) {
    $out = Dkr @('exec', 'duoduo-mysql', 'mysql', '-uduoduo', '-pduoduo', '-N', '-B', '--default-character-set=utf8mb4', '-D', $db, '-e', $sql)
    if ($LASTEXITCODE -ne 0) { return $null }
    return ((@($out) | Where-Object { "$_" -notmatch 'Warning' }) -join "`n").Trim()
}

function SqlScalar([string]$db, [string]$sql) {
    $v = Sql $db $sql
    if ($null -eq $v -or "$v" -eq '') { return 0 }
    return [int]"$v".Split("`n")[0]
}

function Register([string]$phone, [string]$nickname) {
    $s = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $r = Call 'Post' "$Gateway/api/auth/register" $s @{ phone = $phone; password = $Password; nickname = $nickname }
    if ($r.status -eq 409) { $r = Call 'Post' "$Gateway/api/auth/login" $s @{ phone = $phone; password = $Password } }
    return @{ session = $s; result = $r }
}

# 检索一次，判断结果里是否含夹具 —— 供轮询使用
function SearchHasFixture($sess, [string]$keyword, [long]$fixtureId) {
    $url = "$Gateway/api/products?keyword=$([uri]::EscapeDataString($keyword))&pageSize=50"
    $r = Call 'Get' $url $sess $null $null
    if ($r.status -ne 200) { return $false }
    return (@($r.body.data.items | Where-Object { $_.id -eq $fixtureId }).Count -ge 1)
}

function WaitUntil([scriptblock]$probe, [int]$timeoutSec) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $timeoutSec) {
        if (& $probe) { return @{ ok = $true; sec = [math]::Round($sw.Elapsed.TotalSeconds, 1) } }
        Start-Sleep -Milliseconds 1500
    }
    return @{ ok = $false; sec = [math]::Round($sw.Elapsed.TotalSeconds, 1) }
}

$Password = 'Duoduo@123'
$phoneOp = '138' + (Get-Random -Minimum 10000000 -Maximum 99999999)
# 夹具 id：固定小号段（避开雪花 id 与种子 1001~1010），便于随时人工复核
$fxSpu = 99000101
$fxSku = 99000201
$fxTitle = '皇家 幼犬粮 测试款 SEARCHFIXTURE'
$fxPinyinQuery = 'youquanliang'      # 夹具标题「幼犬粮」的拼音 —— MySQL LIKE 不可能命中
$fxSynonymQuery = '小狗'              # 同义词组「幼犬,小狗」的另一端

Write-Host ""
Write-Host "=== 多多宠物商店 · 检索引擎（A10）冒烟 @ $Gateway ==="
Write-Host "ES = $EsHttp ｜ 运营账号 = $phoneOp ｜ 夹具 SPU = $fxSpu"
Write-Host ""

# ---------- 0. 网关与 ES 就绪 ----------
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

$esUp = $false
try {
    $eh = Invoke-WebRequest -Uri "$EsHttp/_cluster/health" -UseBasicParsing -TimeoutSec 5
    $esUp = ($eh.StatusCode -eq 200)
} catch { }
Check "Elasticsearch 可达（$EsHttp）" $esUp
if (-not $esUp) {
    Write-Host "  提示：先执行  docker compose --profile search up -d duoduo-es  并等待其 healthy"
    exit 1
}

# ---------- 1. 运营会话 + 对账接口可用性 ----------
Write-Host ""
Write-Host "> 运营会话与接口可用性"
$regOp = Register $phoneOp '检索冒烟-运营'
Check "运营账号注册/登录成功" ($regOp.result.status -eq 200) $regOp.result.status
Sql 'duoduo_user' "UPDATE t_user SET role = 'OPERATOR' WHERE phone = '$phoneOp';" | Out-Null
# 提权必须**断言**：验不过的话后面全是 403，会被误读成「权限实现有问题」
$opRole = Sql 'duoduo_user' "SELECT role FROM t_user WHERE phone = '$phoneOp';"
Check "库内角色已提权为 OPERATOR" ($opRole -eq 'OPERATOR') $opRole
$sessOp = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$loginOp = Call 'Post' "$Gateway/api/auth/login" $sessOp @{ phone = $phoneOp; password = $Password }
Check "运营账号重新登录成功（角色进入会话）" ($loginOp.status -eq 200) $loginOp.status

$anon = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$r = Call 'Get' "$Gateway/api/admin/search/reconcile" $anon $null $null
Check "匿名访问对账接口返回 401" ($r.status -eq 401) $r.status

$sessU = (Register ('137' + (Get-Random -Minimum 10000000 -Maximum 99999999)) '检索冒烟-路人').session
$r = Call 'Get' "$Gateway/api/admin/search/reconcile" $sessU $null $null
Check "普通用户访问对账接口返回 403" ($r.status -eq 403) $r.status

$r = Call 'Get' "$Gateway/api/admin/search/reconcile?type=product" $sessOp $null $null
Check "运营访问对账接口返回 200（search.enabled=true）" ($r.status -eq 200) ($r.status)
if ($r.status -ne 200) {
    Write-Host "  对账接口不可用：请确认 compose 中 product 的 DUODUO_SEARCH_ENABLED=true 且已重建镜像"
    exit 1
}

# ---------- 2. 夹具：插入一个可被 ES 专属能力召回的 SPU ----------
Write-Host ""
Write-Host "> 夹具准备"
Sql 'duoduo_product' "DELETE FROM t_sku WHERE spu_id = $fxSpu; DELETE FROM t_spu WHERE id = $fxSpu;" | Out-Null
Sql 'duoduo_product' "INSERT INTO t_spu (id, title, subtitle, category_id, brand_id, supply_type, main_image, images, detail, status, sales_count, rating, rating_count, min_price, max_price, created_at, updated_at, seller_id) VALUES ($fxSpu, '$fxTitle', '检索引擎冒烟夹具', 102, 2, 'self', '/img/products/1006.svg', '/img/products/1006.svg', '检索引擎冒烟专用夹具，测试结束即删除。', 'ON_SALE', 999, 4.90, 10, 9999, 9999, UTC_TIMESTAMP(), UTC_TIMESTAMP(), 0);" | Out-Null
Sql 'duoduo_product' "INSERT INTO t_sku (id, spu_id, sku_code, spec_json, image, price, market_price, stock, frozen_stock, is_food, target_species, target_life_stage, allergens, status, created_at, updated_at) VALUES ($fxSku, $fxSpu, 'SEARCHFIXTURE-SKU', '{}', '/img/products/1006.svg', 9999, 9999, 100, 0, 1, 'dog', 'puppy', '', 'ACTIVE', UTC_TIMESTAMP(), UTC_TIMESTAMP());" | Out-Null

$fxInDb = SqlScalar 'duoduo_product' "SELECT COUNT(*) FROM t_spu WHERE id = $fxSpu;"
Check "夹具 SPU 已入库" ($fxInDb -eq 1) "count=$fxInDb"
if ($fxInDb -ne 1) {
    Write-Host "夹具插入失败，终止（后续断言无意义）"
    exit 1
}
# 自检证据链：夹具标题必须既不含「小」「狗」，也不含拼音串 —— 否则后面的「强证据」断言会失真
$fxSelfCheck = SqlScalar 'duoduo_product' "SELECT COUNT(*) FROM t_spu WHERE id = $fxSpu AND (title LIKE '%小%' OR title LIKE '%狗%' OR LOWER(title) LIKE '%$fxPinyinQuery%');"
Check "夹具标题不含「小」「狗」与拼音串（保证反证成立）" ($fxSelfCheck -eq 0) "hit=$fxSelfCheck"

# ---------- 3. 全量重建 → 对账一致（验收②） ----------
Write-Host ""
Write-Host "> 全量重建与对账（验收②：索引与库最终一致）"
$r = Call 'Post' "$Gateway/api/admin/search/reindex" $sessOp $null $null
Check "触发全量重建返回 200" ($r.status -eq 200) ($r.body | ConvertTo-Json -Compress -Depth 4)
Check "重建后同步状态 indexReady=true" ($r.body.data.syncStatus.indexReady -eq $true) ($r.body.data.syncStatus | ConvertTo-Json -Compress)

$r = Call 'Get' "$Gateway/api/admin/search/reconcile?type=product" $sessOp $null $null
$missP = @($r.body.data.missingInIndex)
$staleP = @($r.body.data.staleInIndex)
$extraP = @($r.body.data.extraInIndex)
Check "商品索引无缺失（missingInIndex 为空）" ($missP.Count -eq 0) ($missP -join ',')
Check "商品索引无过期（staleInIndex 为空）" ($staleP.Count -eq 0) ($staleP -join ',')
Check "商品索引无孤儿（extraInIndex 为空）" ($extraP.Count -eq 0) ($extraP -join ',')
Check "对账报告库侧数量覆盖夹具" ($r.body.data.dbCount -ge 1) "dbCount=$($r.body.data.dbCount)"

$r = Call 'Get' "$Gateway/api/admin/search/reconcile?type=listing" $sessOp $null $null
$missL = @($r.body.data.missingInIndex)
$extraL = @($r.body.data.extraInIndex)
Check "闲置索引无缺失（missingInIndex 为空）" ($missL.Count -eq 0) ($missL -join ',')
Check "闲置索引无孤儿（extraInIndex 为空）" ($extraL.Count -eq 0) ($extraL -join ',')

# ---------- 4. 拼音容错（验收①，含 MySQL 反证） ----------
Write-Host ""
Write-Host "> 拼音容错（验收①）"
$mysqlHitPinyin = SqlScalar 'duoduo_product' "SELECT COUNT(*) FROM t_spu WHERE status = 'ON_SALE' AND (title LIKE '%$fxPinyinQuery%' OR subtitle LIKE '%$fxPinyinQuery%');"
Check "反证：同一拼音词在 MySQL 侧命中 0 条" ($mysqlHitPinyin -eq 0) "mysqlHit=$mysqlHitPinyin"

$r = Call 'Get' "$Gateway/api/products?keyword=$fxPinyinQuery&pageSize=50" $sessOp $null $null
$hitByPinyin = @($r.body.data.items | Where-Object { $_.id -eq $fxSpu }).Count
Check "拼音 youquanliang 召回夹具（仅 ES 能做到）" ($hitByPinyin -ge 1) "items=$(@($r.body.data.items).Count)"
# 拼音召回还必须是「精确子串」而非任意命中：换一个不相邻的拼音组合应当查不到夹具
$r2 = Call 'Get' "$Gateway/api/products?keyword=liangyouquan&pageSize=50" $sessOp $null $null
$falseHit = @($r2.body.data.items | Where-Object { $_.id -eq $fxSpu }).Count
Check "拼音非子串顺序（liangyouquan）不召回夹具（and 语义生效）" ($falseHit -eq 0) "hit=$falseHit"

# ---------- 5. 同义词容错（验收①，含 MySQL 反证） ----------
Write-Host ""
Write-Host "> 同义词容错（验收①）"
$mysqlHitSyn = SqlScalar 'duoduo_product' "SELECT COUNT(*) FROM t_spu WHERE status = 'ON_SALE' AND title LIKE '%$fxSynonymQuery%';"
Check "反证：同义词查询「小狗」在 MySQL 侧命中 0 条" ($mysqlHitSyn -eq 0) "mysqlHit=$mysqlHitSyn"

$r = Call 'Get' "$Gateway/api/products?keyword=$([uri]::EscapeDataString($fxSynonymQuery))&pageSize=50" $sessOp $null $null
$hitBySyn = @($r.body.data.items | Where-Object { $_.id -eq $fxSpu }).Count
Check "查询「小狗」召回标题含「幼犬」的夹具（同义词替换生效）" ($hitBySyn -ge 1) "items=$(@($r.body.data.items).Count)"

# 第二组证据：组「猫咪,喵星人」两侧**无共同单字** —— 靠单字交集不可能撞上，命中只能来自替换。
# （这也是同义词配置的正确形态：共享单字的词在逐字分词下本来就互相命中，写成同义词没有增量。）
$synFree = '喵星人'
$mysqlHitSyn2 = SqlScalar 'duoduo_product' "SELECT COUNT(*) FROM t_spu WHERE status = 'ON_SALE' AND title LIKE '%$synFree%';"
Check "反证：「喵星人」在 MySQL 侧命中 0 条" ($mysqlHitSyn2 -eq 0) "mysqlHit=$mysqlHitSyn2"
$r = Call 'Get' "$Gateway/api/products?keyword=$([uri]::EscapeDataString($synFree))&pageSize=50" $sessOp $null $null
$catHit = @($r.body.data.items | Where-Object { "$($_.title)" -match '猫' }).Count
Check "查询「喵星人」经同义词替换召回猫类商品（与原文无共同单字）" ($catHit -ge 1) "items=$(@($r.body.data.items).Count)"

# ---------- 6. 上下架 N 秒内反映（验收③） ----------
Write-Host ""
Write-Host "> 上下架一致性（验收③：扫描周期内反映）"
$r = Call 'Get' "$Gateway/api/products?keyword=SEARCHFIXTURE&pageSize=50" $sessOp $null $null
$beforeOff = @($r.body.data.items | Where-Object { $_.id -eq $fxSpu }).Count
Check "下架前：关键词 SEARCHFIXTURE 命中夹具" ($beforeOff -ge 1) "hit=$beforeOff"

Sql 'duoduo_product' "UPDATE t_spu SET status = 'OFF_SHELF', updated_at = UTC_TIMESTAMP() WHERE id = $fxSpu;" | Out-Null
$w = WaitUntil { -not (SearchHasFixture $sessOp 'SEARCHFIXTURE' $fxSpu) } $WaitSec
Check "下架后 ${WaitSec}s 内从检索结果消失（${WaitSec}s 内容忍，实测 $($w.sec)s）" ($w.ok -eq $true) "耗时=$($w.sec)s"
$d = Call 'Get' "$Gateway/api/products/$fxSpu" $sessOp $null $null
Check "下架后详情页返回 404（与 MySQL 可见性同口径）" ($d.status -eq 404) $d.status

Sql 'duoduo_product' "UPDATE t_spu SET status = 'ON_SALE', updated_at = UTC_TIMESTAMP() WHERE id = $fxSpu;" | Out-Null
$w2 = WaitUntil { SearchHasFixture $sessOp 'SEARCHFIXTURE' $fxSpu } $WaitSec
Check "重新上架后 ${WaitSec}s 内回到检索结果（实测 $($w2.sec)s）" ($w2.ok -eq $true) "耗时=$($w2.sec)s"

# ---------- 7. 检索不可用降级回 MySQL（验收④） ----------
if (-not $SkipDegrade) {
    Write-Host ""
    Write-Host "> 降级（验收④：ES 不可用时检索仍可用）"
    Dkr @('stop', 'duoduo-es') | Out-Null
    Start-Sleep -Seconds 3
    $r = Call 'Get' "$Gateway/api/products?keyword=SEARCHFIXTURE&pageSize=50" $sessOp $null $null
    Check "ES 停服后检索仍返回 200（原地降级 MySQL）" ($r.status -eq 200) $r.status
    Check "降级路径仍能返回商品（非空结果）" (@($r.body.data.items).Count -ge 1) "items=$(@($r.body.data.items).Count)"
    # 降级后拼音查询当然查不到 —— 这正是「ES 生效」的旁证：有 ES 才有拼音能力
    $r = Call 'Get' "$Gateway/api/products?keyword=$fxPinyinQuery&pageSize=50" $sessOp $null $null
    $hitPinyinDegraded = @($r.body.data.items | Where-Object { $_.id -eq $fxSpu }).Count
    Check "降级后拼音查询不再命中（旁证 ES 才是能力来源）" ($hitPinyinDegraded -eq 0) "hit=$hitPinyinDegraded"

    Dkr @('start', 'duoduo-es') | Out-Null
    $esBack = $false
    for ($i = 0; $i -lt 60; $i++) {
        try {
            $eh = Invoke-WebRequest -Uri "$EsHttp/_cluster/health" -UseBasicParsing -TimeoutSec 5
            if ($eh.StatusCode -eq 200) { $esBack = $true; break }
        } catch { Start-Sleep -Seconds 2 }
    }
    Check "ES 重启后恢复可达" $esBack
    if ($esBack) {
        $w3 = WaitUntil { SearchHasFixture $sessOp $fxPinyinQuery $fxSpu } $WaitSec
        Check "ES 恢复后拼音检索自动重新生效（无需人工干预，实测 $($w3.sec)s）" ($w3.ok -eq $true) "耗时=$($w3.sec)s"
    }
} else {
    Write-Host ""
    Write-Host "> 降级段已按 -SkipDegrade 跳过"
}

# ---------- 8. 清理：硬删除 → 全量重建清孤儿 ----------
Write-Host ""
Write-Host "> 清理与孤儿回收"
Sql 'duoduo_product' "DELETE FROM t_sku WHERE spu_id = $fxSpu; DELETE FROM t_spu WHERE id = $fxSpu;" | Out-Null
Check "夹具已从库中删除" ((SqlScalar 'duoduo_product' "SELECT COUNT(*) FROM t_spu WHERE id = $fxSpu;") -eq 0)

$r = Call 'Post' "$Gateway/api/admin/search/reindex" $sessOp $null $null
Check "清理后重建返回 200" ($r.status -eq 200) $r.status
$r = Call 'Get' "$Gateway/api/admin/search/reconcile?type=product" $sessOp $null $null
$missP2 = @($r.body.data.missingInIndex)
$staleP2 = @($r.body.data.staleInIndex)
$extraP2 = @($r.body.data.extraInIndex)
Check "重建后商品索引无缺失" ($missP2.Count -eq 0) ($missP2 -join ',')
Check "重建后商品索引无过期" ($staleP2.Count -eq 0) ($staleP2 -join ',')
Check "硬删除的文档已被清出索引（extraInIndex 为空）" ($extraP2.Count -eq 0) ($extraP2 -join ',')
Check "夹具不再出现在检索结果中" (-not (SearchHasFixture $sessOp 'SEARCHFIXTURE' $fxSpu))

# ---------- 结果 ----------
Write-Host ""
Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
if ($fail -gt 0) {
    Write-Host "失败项："
    $failures | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
exit 0
