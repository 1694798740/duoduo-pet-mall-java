# 审核 SLA 事后抽查端到端冒烟（E28，Windows PowerShell 5.1）
# 用法： powershell -NoProfile -ExecutionPolicy Bypass -File scripts\audit-sla-smoke.ps1
#
# 前置：容器全部 healthy，且已部署 E28 镜像（docs/sql/incremental/29-e28-audit-sla.sql 已执行）。
#
# 覆盖（对应 E28 验收要点）：
#   1. 自动放行：内容干净 → 提交即 APPROVED 且来源 AUTO、audit_at 有值
#      （audit_at 是抽查候选集的唯一锚点，为空则整条抽查链路静默空转）；
#   2. 转人工：风险命中（标题含微信号）→ PENDING、来源为空、audit_at 为空；
#   3. 抽查任务：sweep 可跑、比例正确、唯一键幂等（重复触发不重复建单）；
#   4. 复核判违规：商品驳回 + 强制下架 + 来源 SAMPLE + 商家违规计数 +1；
#   5. 复核不可重入：已复核的任务再判 → 422（否则误判率指标可以被反复点通过刷平）；
#   6. 抽查不改可见性：判合规后商品仍在售（抽中 ≠ 下架）；
#   7. SLA 指标：抽中违规率 / 平均复核时长 / 误判率 / 当前比例，且违规率与计数自洽；
#   8. 入驻侧同链路：复核判违规 → 店铺直接停业（不攒次数）+ 联动下架其商品；
#   9. 夹具自建自毁：结尾删除本次直插的抽查任务、商品与店铺，并复位抽样比例。
#  10. E16 权限点：直连服务端口做双向断言 —— USER/SELLER 403、OPERATOR 200、未登录 401，
#      验证「注解式判权」与「命令式判权」都真的拦得住（注解失效 = 静默变成公开接口）。
#
# 设计原则：
#   1. **端到端不赌概率**：抽样命中是概率事件，所以「任务 → 复核 → 处置」这条链
#      用【直插一条 PENDING 任务】保证确定性；抽样规则本身由 AuditSlaPolicyTest 覆盖
#      （可复现、可解释、可单测）。sweep 接口只验「能跑、比例对、幂等」。
#   2. 凡改库必先记录、结尾自证复原。
#   3. 断言失败一律带 status：只有 reply 分不清「答了别的话」与「根本没拿到响应」。
param(
    [string]$Gateway = 'http://127.0.0.1:8080',
    [string]$Password = 'duoduo123'
)

$ErrorActionPreference = 'Continue'
# Invoke-WebRequest 默认每读一次响应就刷一行进度条。这套件要发几十个请求，
# 进度噪音会把 [PASS]/[FAIL] 与最后的汇总行整个淹没（实测：控制台只剩「正在读取 Web 响应」），
# 于是「跑过了但没人看得出结果」。关掉它，输出才只剩结论。
$ProgressPreference = 'SilentlyContinue'
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
    $params = @{ Uri = $uri; Method = $method; UseBasicParsing = $true; TimeoutSec = 30 }
    if ($session) { $params.WebSession = $session }
    if ($headers) { $params.Headers = $headers }
    if ($null -ne $bodyObj) {
        # 用 -InputObject 而不是管道：管道会枚举数组，把单元素数组拆成裸对象
        $params.Body = (ConvertTo-Json -InputObject $bodyObj -Compress -Depth 6)
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
            try {
                $resp = $_.Exception.Response
                if ($resp -and $resp.PSObject.Methods.Name -contains 'GetResponseStream') {
                    $st = $resp.GetResponseStream()
                    if ($st) { $raw = (New-Object System.IO.StreamReader($st)).ReadToEnd() }
                } elseif ($resp -and $null -ne $resp.Content) {
                    $raw = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                }
                if ($raw) { $body = $raw | ConvertFrom-Json }
            } catch { }
        }
        return @{ status = $code; body = $body; raw = $raw }
    }
}

function Sql([string]$db, [string]$sql) {
    $out = & docker exec duoduo-mysql mysql -uduoduo -pduoduo -N -B `
        --default-character-set=utf8mb4 -D $db -e $sql 2>&1
    if ($LASTEXITCODE -ne 0) { return $null }
    return ((@($out) | Where-Object { "$_" -notmatch 'Warning' }) -join "`n").Trim()
}

function Register([string]$phone, [string]$nickname) {
    $s = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $r = Call 'Post' "$Gateway/api/auth/register" $s @{ phone = $phone; password = $Password; nickname = $nickname }
    if ($r.status -eq 409) { $r = Call 'Post' "$Gateway/api/auth/login" $s @{ phone = $phone; password = $Password } }
    return @{ session = $s; result = $r }
}

. (Join-Path $PSScriptRoot 'lib\platform-operator.ps1')
. (Join-Path $PSScriptRoot 'lib\seller-fixture.ps1')

$sellerPhone = '135' + (Get-Random -Minimum 10000000 -Maximum 99999999)
$createdSpus = @()

Write-Host ""
Write-Host "=== 多多宠物商店 · 审核 SLA 事后抽查冒烟（E28） @ $Gateway ==="
Write-Host "卖家夹具 = $sellerPhone"
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

# 新端点是否真的路由到了商品域 / 用户域（网关路由写错时这里会 404 或落到别处）
$probe = Call 'Get' "$Gateway/api/admin/audit-sla/samples?page=1&pageSize=1" $null $null
Check "商品侧抽查端点已暴露（未登录 401，非 404）" ($probe.status -eq 401) "status=$($probe.status) raw=$($probe.raw)"
$probe2 = Call 'Get' "$Gateway/api/admin/merchants/audit-sla/samples?page=1&pageSize=1" $null $null
Check "入驻侧抽查端点已暴露（未登录 401，非 404）" ($probe2.status -eq 401) "status=$($probe2.status) raw=$($probe2.raw)"

# ---------- 1. 夹具 ----------
Write-Host ""
Write-Host "> 夹具（运营 + 卖家 + 类目，均动态取）"
$op = New-PlatformOperatorSession -Gateway $Gateway -Password $Password
$sessOp = $op.session
Check "运营会话就绪（角色已提为 OPERATOR）" $op.roleSet $op.role

$cats = (Call 'Get' "$Gateway/api/categories" $null $null).body.data
$root = @($cats)[0]
$kids = @($root.children)
$cid = if ($kids.Count -gt 0) { "$($kids[0].id)" } else { "$($root.id)" }
Check "取到可上架类目" (-not [string]::IsNullOrWhiteSpace($cid)) $cid

$seller0 = Register $sellerPhone '审核抽查-卖家'
Check "卖家账号注册/登录成功" ($seller0.result.status -eq 200) "status=$($seller0.result.status)"
$fx = New-SellerFixture -Phone $sellerPhone -StoreName '抽查冒烟店' -Gateway $Gateway -Session $seller0.session -DoRealname
$merchantId = "$($fx.merchantId)"
$ownerUserId = Sql 'duoduo_user' "SELECT owner_user_id FROM t_merchant WHERE id=$merchantId;"
Check "夹具店铺建成（APPROVED）" (-not [string]::IsNullOrWhiteSpace($ownerUserId)) "ownerUserId=$ownerUserId"
# 角色是 access token 里的快照：改库之后必须重新登录才生效
$seller = Register $sellerPhone '审核抽查-卖家'
$sessSeller = $seller.session
Check "卖家令牌已轮转（重新登录拿到 SELLER）" ($seller.result.status -eq 200 -and $seller.result.body.data.role -eq 'SELLER') "role=$($seller.result.body.data.role)"

function SpuBody([string]$title, [string]$detail, [string]$categoryId) {
    return @{
        title      = $title
        subtitle   = '端到端冒烟夹具'
        categoryId = $categoryId
        brandId    = $null
        mainImage  = 'https://img.duoduo.local/smoke/main.png'
        images     = @('https://img.duoduo.local/smoke/1.png')
        detail     = $detail
        skus       = @(@{ specName = '500g'; price = 1990; stock = 10 })
    }
}

# ---------- 2. 自动放行 vs 转人工 ----------
Write-Host ""
Write-Host "> 自动放行判定（E28 的核心口径）"
$rA = Call 'Post' "$Gateway/api/seller/products" $sessSeller (SpuBody '端到端冒烟-干净商品A' '配方温和，易消化，适合全年龄段。' $cid)
Check "提交干净商品 A → 200" ($rA.status -eq 200) "status=$($rA.status) raw=$($rA.raw)"
$spuA = "$($rA.body.data.id)"
if ([string]::IsNullOrWhiteSpace($spuA)) {
    Write-Host "商品 A 未创建，后续断言无意义，终止"
    exit 1
}
$createdSpus += $spuA
$rowA = Sql 'duoduo_product' "SELECT CONCAT(audit_status,'|',IFNULL(audit_source,'NULL'),'|',status) FROM t_spu WHERE id=$spuA;"
Check "商品 A 自动放行（APPROVED|AUTO|ON_SALE）" ("$rowA" -eq 'APPROVED|AUTO|ON_SALE') $rowA
$atA = Sql 'duoduo_product' "SELECT IFNULL(DATE_FORMAT(audit_at,'%Y-%m-%d'),'NULL') FROM t_spu WHERE id=$spuA;"
$todayUtc = Sql 'duoduo_product' "SELECT DATE_FORMAT(UTC_DATE(),'%Y-%m-%d');"
Check "★ 商品 A 的 audit_at = 今天（抽查候选集靠它筛，为空则抽查永远空转）" ("$atA" -eq "$todayUtc") "audit_at=$atA today=$todayUtc"

$rC = Call 'Post' "$Gateway/api/seller/products" $sessSeller (SpuBody '加微信 duoduo888 领券-冒烟' '正常详情文案。' $cid)
Check "提交命中风险的标题 → 200" ($rC.status -eq 200) "status=$($rC.status) raw=$($rC.raw)"
$spuC = "$($rC.body.data.id)"
$createdSpus += $spuC
$rowC = Sql 'duoduo_product' "SELECT CONCAT(audit_status,'|',IFNULL(audit_source,'NULL'),'|',IFNULL(DATE_FORMAT(audit_at,'%Y-%m-%d'),'NULL')) FROM t_spu WHERE id=$spuC;"
Check "★ 标题含微信号 → 转人工（PENDING|NULL|NULL）" ("$rowC" -eq 'PENDING|NULL|NULL') $rowC

$rB = Call 'Post' "$Gateway/api/seller/products" $sessSeller (SpuBody '端到端冒烟-干净商品B' '第二件干净商品，用于验证「抽查不改可见性」。' $cid)
Check "提交干净商品 B → 200" ($rB.status -eq 200) "status=$($rB.status) raw=$($rB.raw)"
$spuB = "$($rB.body.data.id)"
$createdSpus += $spuB
$rowB = Sql 'duoduo_product' "SELECT CONCAT(audit_status,'|',IFNULL(audit_source,'NULL'),'|',status) FROM t_spu WHERE id=$spuB;"
Check "商品 B 自动放行（APPROVED|AUTO|ON_SALE）" ("$rowB" -eq 'APPROVED|AUTO|ON_SALE') $rowB

# ---------- 3. sweep：可跑 + 比例 + 幂等 ----------
Write-Host ""
Write-Host "> 抽查任务生成（sweep）"
$sw1 = Call 'Post' "$Gateway/api/admin/audit-sla/sweep" $sessOp $null
Check "商品侧 sweep → 200" ($sw1.status -eq 200) "status=$($sw1.status) raw=$($sw1.raw)"
Check "sweep 返回基础比例 5%" ($sw1.body.data.ratePercent -eq 5) $sw1.body.data.ratePercent
Check "sweep 返回结构完整（candidates/created/skipped 均为数字）" `
    ($null -ne $sw1.body.data.candidates -and $null -ne $sw1.body.data.created -and $null -ne $sw1.body.data.skipped) `
    ($sw1.body.data | ConvertTo-Json -Compress)
$sw2 = Call 'Post' "$Gateway/api/admin/audit-sla/sweep" $sessOp $null
Check "★ 重复 sweep 幂等（第二次 created=0，靠 uk_biz_date 唯一键）" ($sw2.status -eq 200 -and $sw2.body.data.created -eq 0) `
    "status=$($sw2.status) created=$($sw2.body.data.created)"

# ---------- 4. 复核判违规（直插任务，绕开概率） ----------
Write-Host ""
Write-Host "> 复核 · 判违规（直插 PENDING 任务，保证可测）"
$taskA = New-FixtureId
Sql 'duoduo_product' "INSERT INTO t_audit_sample (id, biz_type, biz_id, seller_id, sample_date, reason, status, created_at, updated_at) VALUES ($taskA, 'SPU', $spuA, $ownerUserId, UTC_DATE(), 'BASE', 'PENDING', UTC_TIMESTAMP(), UTC_TIMESTAMP());" | Out-Null
$tasks = @($taskA)
$chk = Sql 'duoduo_product' "SELECT COUNT(*) FROM t_audit_sample WHERE id=$taskA;"
Check "夹具：抽查任务已直插（回读复核）" ("$chk" -eq '1') "count=$chk"

$list = Call 'Get' "$Gateway/api/admin/audit-sla/samples?status=PENDING&page=1&pageSize=100" $sessOp
Check "抽查队列可读（PENDING）" ($list.status -eq 200) "status=$($list.status) raw=$($list.raw)"
$hit = @($list.body.data.items | Where-Object { "$($_.id)" -eq "$taskA" })
Check "队列里能查到本次任务（含抽中理由中文说明）" ($hit.Count -eq 1 -and -not [string]::IsNullOrWhiteSpace($hit[0].reasonLabel)) `
    "hit=$($hit.Count) total=$($list.body.data.total)"

$c0 = Sql 'duoduo_user' "SELECT IFNULL(violation_count,0) FROM t_merchant WHERE id=$merchantId;"
$rv = Call 'Post' "$Gateway/api/admin/audit-sla/samples/$taskA/review" $sessOp @{ action = 'REJECT'; note = '端到端冒烟：模拟判违规' }
Check "复核判违规 → 200 且结论 REJECTED" ($rv.status -eq 200 -and $rv.body.data.status -eq 'REJECTED') "status=$($rv.status) raw=$($rv.raw)"
$rowA2 = Sql 'duoduo_product' "SELECT CONCAT(audit_status,'|',IFNULL(audit_source,'NULL'),'|',status) FROM t_spu WHERE id=$spuA;"
Check "★ 判违规后商品被驳回并强制下架（REJECTED|SAMPLE|OFF_SHELF）" ("$rowA2" -eq 'REJECTED|SAMPLE|OFF_SHELF') $rowA2
$c1 = Sql 'duoduo_user' "SELECT IFNULL(violation_count,0) FROM t_merchant WHERE id=$merchantId;"
Check "★ 判违规 → 商家违规计数 +1（跨域回写）" ([int]$c1 -eq ([int]$c0 + 1)) "before=$c0 after=$c1"

$again = Call 'Post' "$Gateway/api/admin/audit-sla/samples/$taskA/review" $sessOp @{ action = 'PASS'; note = '试图改判' }
Check "★ 复核不可重入（已复核再判 → 422）" ($again.status -eq 422) "status=$($again.status) raw=$($again.raw)"

# ---------- 5. 复核判合规：抽查不改可见性 ----------
Write-Host ""
Write-Host "> 复核 · 判合规（抽查不得改变前台可见性）"
$taskB = New-FixtureId
Sql 'duoduo_product' "INSERT INTO t_audit_sample (id, biz_type, biz_id, seller_id, sample_date, reason, status, created_at, updated_at) VALUES ($taskB, 'SPU', $spuB, $ownerUserId, UTC_DATE() - INTERVAL 2 DAY, 'BASE', 'PENDING', UTC_TIMESTAMP(), UTC_TIMESTAMP());" | Out-Null
$tasks += $taskB
$chk = Sql 'duoduo_product' "SELECT COUNT(*) FROM t_audit_sample WHERE id=$taskB;"
Check "夹具：第二条抽查任务已直插" ("$chk" -eq '1') "count=$chk"
$rp = Call 'Post' "$Gateway/api/admin/audit-sla/samples/$taskB/review" $sessOp @{ action = 'PASS'; note = '端到端冒烟：判定合规' }
Check "复核判合规 → 200 且结论 PASSED" ($rp.status -eq 200 -and $rp.body.data.status -eq 'PASSED') "status=$($rp.status) raw=$($rp.raw)"
Check "复核耗时已记账（responseHours 非空）" ($null -ne $rp.body.data.responseHours) $rp.body.data.responseHours
$rowB2 = Sql 'duoduo_product' "SELECT CONCAT(audit_status,'|',IFNULL(audit_source,'NULL'),'|',status) FROM t_spu WHERE id=$spuB;"
Check "★ 判合规后商品仍在售（抽中 ≠ 下架）" ("$rowB2" -eq 'APPROVED|AUTO|ON_SALE') $rowB2

# ---------- 6. SLA 指标 ----------
Write-Host ""
Write-Host "> SLA 自证指标"
$m = Call 'Get' "$Gateway/api/admin/audit-sla/metrics" $sessOp
Check "指标接口 200" ($m.status -eq 200) "status=$($m.status) raw=$($m.raw)"
$md = $m.body.data
Check "指标窗口 = 30 天" ($md.windowDays -eq 30) $md.windowDays
Check "近 30 天已复核 ≥ 2（本次两条）" ($md.sampled -ge 2) $md.sampled
Check "判违规 ≥ 1" ($md.violated -ge 1) $md.violated
Check "判合规 ≥ 1" ($md.passed -ge 1) $md.passed
$expectPct = [int][math]::Floor($md.violated * 100.0 / $md.sampled + 0.5)
Check "违规率与计数自洽（四舍五入口径一致）" ($md.violationPercent -eq $expectPct) "$($md.violationPercent) vs expect=$expectPct ($($md.violated)/$($md.sampled))"
Check "当前抽样比例在 [5,40]" ($md.currentRatePercent -ge 5 -and $md.currentRatePercent -le 40) $md.currentRatePercent
Check "平均复核时长可读（≥0）" ($md.avgResponseHours -ge 0) $md.avgResponseHours

# ---------- 7. 入驻侧同链路 ----------
Write-Host ""
Write-Host "> 入驻侧抽查（店铺）"
$taskM1 = New-FixtureId
Sql 'duoduo_user' "INSERT INTO t_audit_sample (id, biz_type, biz_id, seller_id, sample_date, reason, status, created_at, updated_at) VALUES ($taskM1, 'MERCHANT', $merchantId, $ownerUserId, UTC_DATE() - INTERVAL 2 DAY, 'BASE', 'PENDING', UTC_TIMESTAMP(), UTC_TIMESTAMP());" | Out-Null
$chk = Sql 'duoduo_user' "SELECT COUNT(*) FROM t_audit_sample WHERE id=$taskM1;"
Check "夹具：入驻侧抽查任务已直插" ("$chk" -eq '1') "count=$chk"

$mlist = Call 'Get' "$Gateway/api/admin/merchants/audit-sla/samples?status=PENDING&page=1&pageSize=100" $sessOp
Check "入驻侧抽查队列可读" ($mlist.status -eq 200) "status=$($mlist.status) raw=$($mlist.raw)"
$mhit = @($mlist.body.data.items | Where-Object { "$($_.id)" -eq "$taskM1" })
Check "入驻侧队列里能查到本次任务（带店铺名）" ($mhit.Count -eq 1 -and -not [string]::IsNullOrWhiteSpace($mhit[0].storeName)) `
    "hit=$($mhit.Count) total=$($mlist.body.data.total)"

$mp = Call 'Post' "$Gateway/api/admin/merchants/audit-sla/samples/$taskM1/review" $sessOp @{ action = 'PASS'; note = '端到端冒烟：入驻判定合规' }
Check "入驻复核判合规 → PASSED" ($mp.status -eq 200 -and $mp.body.data.status -eq 'PASSED') "status=$($mp.status) raw=$($mp.raw)"

$taskM2 = New-FixtureId
Sql 'duoduo_user' "INSERT INTO t_audit_sample (id, biz_type, biz_id, seller_id, sample_date, reason, status, created_at, updated_at) VALUES ($taskM2, 'MERCHANT', $merchantId, $ownerUserId, UTC_DATE() - INTERVAL 3 DAY, 'BASE', 'PENDING', UTC_TIMESTAMP(), UTC_TIMESTAMP());" | Out-Null
$chk = Sql 'duoduo_user' "SELECT COUNT(*) FROM t_audit_sample WHERE id=$taskM2;"
Check "夹具：第二条入驻侧抽查任务已直插" ("$chk" -eq '1') "count=$chk"
$c2 = Sql 'duoduo_user' "SELECT IFNULL(violation_count,0) FROM t_merchant WHERE id=$merchantId;"
$mr = Call 'Post' "$Gateway/api/admin/merchants/audit-sla/samples/$taskM2/review" $sessOp @{ action = 'REJECT'; note = '端到端冒烟：入驻资质判违规' }
Check "入驻复核判违规 → REJECTED" ($mr.status -eq 200 -and $mr.body.data.status -eq 'REJECTED') "status=$($mr.status) raw=$($mr.raw)"
$st = Sql 'duoduo_user' "SELECT CONCAT(status,'|',IFNULL(suspended_reason,'NULL')) FROM t_merchant WHERE id=$merchantId;"
Check "★ 入驻判违规 → 店铺直接停业（不攒次数）" ("$st" -match '^SUSPENDED\|') $st
$c3 = Sql 'duoduo_user' "SELECT IFNULL(violation_count,0) FROM t_merchant WHERE id=$merchantId;"
Check "入驻判违规同时留痕（违规计数再 +1）" ([int]$c3 -eq ([int]$c2 + 1)) "before=$c2 after=$c3"
$rowB3 = Sql 'duoduo_product' "SELECT status FROM t_spu WHERE id=$spuB;"
Check "★ 停业联动下架该店商品（商品 B 变 OFF_SHELF）" ("$rowB3" -eq 'OFF_SHELF') $rowB3

$msw = Call 'Post' "$Gateway/api/admin/merchants/audit-sla/sweep" $sessOp $null
Check "入驻侧 sweep → 200" ($msw.status -eq 200) "status=$($msw.status) raw=$($msw.raw)"
$mm = Call 'Get' "$Gateway/api/admin/merchants/audit-sla/metrics" $sessOp
Check "入驻侧指标接口 200" ($mm.status -eq 200) "status=$($mm.status) raw=$($mm.raw)"

# ---------- 8. 夹具自毁 ----------
Write-Host ""
Write-Host "> 夹具自毁（删除本次直插的任务 / 商品 / 店铺，并复位抽样比例）"
$idListP = ($tasks -join ',')
$idListU = ($taskM1, $taskM2) -join ','
Sql 'duoduo_product' "DELETE FROM t_audit_sample WHERE id IN ($idListP);" | Out-Null
Sql 'duoduo_user' "DELETE FROM t_audit_sample WHERE id IN ($idListU);" | Out-Null
$spuList = ($createdSpus | Where-Object { -not [string]::IsNullOrWhiteSpace("$_") }) -join ','
Sql 'duoduo_product' "DELETE FROM t_sku WHERE spu_id IN ($spuList);" | Out-Null
Sql 'duoduo_product' "DELETE FROM t_spu WHERE id IN ($spuList);" | Out-Null
Sql 'duoduo_user' "DELETE FROM t_merchant_member WHERE merchant_id=$merchantId;" | Out-Null
Sql 'duoduo_user' "DELETE FROM t_merchant_material WHERE merchant_id=$merchantId;" | Out-Null
Sql 'duoduo_user' "DELETE FROM t_merchant WHERE id=$merchantId;" | Out-Null
Sql 'duoduo_product' "UPDATE t_audit_sample_rate SET sample_rate_percent=5, tightened_at=NULL WHERE id=1;" | Out-Null
Sql 'duoduo_user' "UPDATE t_audit_sample_rate SET sample_rate_percent=5, tightened_at=NULL WHERE id=1;" | Out-Null

$leftSpu = Sql 'duoduo_product' "SELECT COUNT(*) FROM t_spu WHERE id IN ($spuList);"
$leftSku = Sql 'duoduo_product' "SELECT COUNT(*) FROM t_sku WHERE spu_id IN ($spuList);"
$leftTaskP = Sql 'duoduo_product' "SELECT COUNT(*) FROM t_audit_sample WHERE id IN ($idListP);"
$leftTaskU = Sql 'duoduo_user' "SELECT COUNT(*) FROM t_audit_sample WHERE id IN ($idListU);"
$leftMer = Sql 'duoduo_user' "SELECT COUNT(*) FROM t_merchant WHERE id=$merchantId;"
$rateP = Sql 'duoduo_product' "SELECT sample_rate_percent FROM t_audit_sample_rate WHERE id=1;"
$rateU = Sql 'duoduo_user' "SELECT sample_rate_percent FROM t_audit_sample_rate WHERE id=1;"
Check "自证：夹具商品已删除" ("$leftSpu" -eq '0' -and "$leftSku" -eq '0') "spu=$leftSpu sku=$leftSku"
Check "自证：直插的抽查任务已删除" ("$leftTaskP" -eq '0' -and "$leftTaskU" -eq '0') "product=$leftTaskP user=$leftTaskU"
Check "自证：夹具店铺已删除" ("$leftMer" -eq '0') $leftMer
Check "自证：两侧抽样比例已复位为 5" ("$rateP" -eq '5' -and "$rateU" -eq '5') "product=$rateP user=$rateU"
# 临时提权必须收回：不收尾的话，每跑一轮就在库里多一个永久能进后台的账号
$roleAfter = Remove-PlatformOperatorPrivilege -Phone $op.phone
Check "自证：临时运营角色已收回（不留后台入口）" ($roleAfter -eq 'USER') "role=$roleAfter"

# ---------- 9. E16 · 权限点与注解式判权 ----------
Write-Host ""
Write-Host "> E16 权限点：注解式判权是否真的拦得住（**直连服务端口**）"
# ★ 为什么必须直连 8082/8081 而不是走网关：
#   E8 的入口收窄会让「非 admin 入口」的令牌在**网关**就被 403 —— 那个 403 与
#   「服务端注解有没有生效」毫无关系。只断言网关态的话，注解压根没注册也会「看起来通过」。
#   直连服务端口 + 伪造网关头（服务端口不对外，这里只为自证），问的才是真问题。
#   ★ 失效方向：注解失效 = 接口静默变成任何人可调（200），不会报错、不会变红 ——
#   所以这组断言里 **SELLER → 403** 是最关键的一条（命令式那行已被删掉，
#   若拦截器没挂上，这里会 200）。
#   用只读的 /samples 而不是 /metrics：后者会顺带评估并执行自动收紧（会写库），
#   而此刻自毁刚把抽样比例复位，不该再被这次探测改动。
$svcProduct = 'http://localhost:8082'
$svcUser = 'http://localhost:8081'
$svcSamples = "$svcProduct/api/admin/audit-sla/samples?page=1&pageSize=1"

$r1 = Call 'Get' $svcSamples $null $null @{ 'X-User-Id' = '1'; 'X-User-Role' = 'USER' }
Check "E16 · USER 直连抽查队列 → 403（注解拦得住）" ($r1.status -eq 403) "status=$($r1.status) raw=$($r1.raw)"

$r2 = Call 'Get' $svcSamples $null $null @{ 'X-User-Id' = '1'; 'X-User-Role' = 'SELLER' }
Check "E16 · SELLER 直连抽查队列 → 403（注解拦得住，命令式那行已删）" ($r2.status -eq 403) "status=$($r2.status) raw=$($r2.raw)"

$r3 = Call 'Get' $svcSamples $null $null @{ 'X-User-Id' = '1'; 'X-User-Role' = 'OPERATOR' }
Check "E16 · OPERATOR 直连抽查队列 → 200（注解不误拦）" ($r3.status -eq 200) "status=$($r3.status) raw=$($r3.raw)"

$r4 = Call 'Get' $svcSamples $null $null $null
Check "E16 · 未登录直连 → 401（与 403 分得清）" ($r4.status -eq 401) "status=$($r4.status) raw=$($r4.raw)"

# 命令式路径（OperatorGuard → 权限矩阵）同时回归：抽一个未改注解的老端点
$svcUsers = "$svcUser/api/admin/users?page=1&pageSize=5"
$r5 = Call 'Get' $svcUsers $null $null @{ 'X-User-Id' = '1'; 'X-User-Role' = 'USER' }
Check "E16 · 命令式路径：USER 直连用户列表 → 403" ($r5.status -eq 403) "status=$($r5.status) raw=$($r5.raw)"

$r6 = Call 'Get' $svcUsers $null $null @{ 'X-User-Id' = '1'; 'X-User-Role' = 'OPERATOR' }
Check "E16 · 命令式路径：OPERATOR 直连用户列表 → 200" ($r6.status -eq 200) "status=$($r6.status) raw=$($r6.raw)"

# 商家端门槛同源：SELLER 能进商家端（有 seller:console），USER 不能
$svcSellerSpus = "$svcProduct/api/seller/products?page=1&pageSize=1"
$r7 = Call 'Get' $svcSellerSpus $null $null @{ 'X-User-Id' = '1'; 'X-User-Role' = 'SELLER' }
Check "E16 · SELLER 直连商家商品 → 非 403（有 seller:console）" ($r7.status -ne 403) "status=$($r7.status) raw=$($r7.raw)"

$r8 = Call 'Get' $svcSellerSpus $null $null @{ 'X-User-Id' = '1'; 'X-User-Role' = 'USER' }
Check "E16 · USER 直连商家商品 → 403" ($r8.status -eq 403) "status=$($r8.status) raw=$($r8.raw)"

Write-Host ""
Write-Host "=== 冒烟结果：通过 $pass 项，失败 $fail 项 ==="
if ($fail -gt 0) {
    Write-Host "失败项："
    foreach ($f in $failures) { Write-Host "  - $f" }
    exit 1
}
exit 0
