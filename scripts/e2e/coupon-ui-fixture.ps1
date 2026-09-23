# 优惠券营销（A5）· 浏览器 UI 自测的夹具（建 / 删）
#
# 为什么单独拆一个脚本：
#   `coupon-ui-check.mjs` 要用一张**自建自毁**的券模板（不碰种子券 5001/5002），
#   而本机 Node 无法创建任何子进程（spawnSync/execFileSync 一律 EBUSY -4082），
#   在 Node 里调 `docker exec mysql` 跑不通。SQL 留给 PowerShell（`& docker exec` 是通的），
#   Node 只做 HTTP + 浏览器。
#
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\e2e\coupon-ui-fixture.ps1 -Action create
#   ... 跑 coupon-ui-check.mjs ...
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\e2e\coupon-ui-fixture.ps1 -Action drop
#
# create 会把模板 id 写进仓库根的 `.tmp-coupon-ui-tpl-id`（已被 .gitignore 的 .tmp-* 覆盖），
# 供 .mjs 读取；drop 读完即删该文件。

param(
    [ValidateSet('create', 'drop')][string]$Action = 'create',
    [string]$TplName = '【UI自测】满 50 减 5',
    [long]$Threshold = 5000,
    [long]$Discount = 500
)

$ErrorActionPreference = 'Continue'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$idFile = Join-Path $repo '.tmp-coupon-ui-tpl-id'

$pass = 0
$fail = 0
function Check([string]$name, $ok, $got) {
    if ($ok) { $script:pass++; Write-Host "  [PASS] $name" }
    else { $script:fail++; Write-Host "  [FAIL] $name  实际=$got" }
}

# 与 coupon-smoke.ps1 同款：直连容器内的 mysql 客户端
function Sql([string]$db, [string]$sql) {
    $env:PATH = 'C:\Program Files\Docker\Docker\resources\bin;' + $env:PATH
    if (-not $env:DOCKER_CONFIG) { $env:DOCKER_CONFIG = 'D:\duoduo-infra\docker-nocreds' }
    $out = & docker exec duoduo-mysql mysql -uduoduo -pduoduo -N -B --default-character-set=utf8mb4 -D $db -e $sql 2>&1
    if ($LASTEXITCODE -ne 0) { return $null }
    return ((@($out) | Where-Object { "$_" -notmatch 'Warning' }) -join "`n").Trim()
}

if ($Action -eq 'create') {
    Write-Host "> 建立 UI 自测夹具券模板"
    # 幂等：先清掉同名残留（上一次跑崩了没 drop 的情况）
    Sql 'duoduo_marketing' "DELETE FROM t_coupon WHERE template_id IN (SELECT id FROM t_coupon_template WHERE name = '$TplName');" | Out-Null
    Sql 'duoduo_marketing' "DELETE FROM t_coupon_template WHERE name = '$TplName';" | Out-Null

    $maxTpl = [long](Sql 'duoduo_marketing' 'SELECT COALESCE(MAX(id), 900000) FROM t_coupon_template;')
    $tplId = $maxTpl + 1

    $sql = "INSERT INTO t_coupon_template (id, name, description, type, threshold_amount, discount_amount, total_count, claimed_count, start_at, end_at, status, created_at, updated_at) " +
           "VALUES ($tplId, '$TplName', 'UI 自测夹具，跑完即删', 'FULL_REDUCE', $Threshold, $Discount, 100, 0, " +
           "UTC_TIMESTAMP() - INTERVAL 1 DAY, UTC_TIMESTAMP() + INTERVAL 3650 DAY, 'ACTIVE', UTC_TIMESTAMP(), UTC_TIMESTAMP());"
    Sql 'duoduo_marketing' $sql | Out-Null

    $row = Sql 'duoduo_marketing' "SELECT COUNT(*) FROM t_coupon_template WHERE id = $tplId AND name = '$TplName';"
    Check "夹具券模板已建立（id=$tplId）" ($row -eq '1') $row
    $seeds = Sql 'duoduo_marketing' 'SELECT COUNT(*) FROM t_coupon_template WHERE id IN (5001, 5002);'
    Check '种子券模板 5001/5002 未被影响' ($seeds -eq '2') $seeds

    if ($row -eq '1') {
        Set-Content -LiteralPath $idFile -Value "$tplId" -Encoding ascii -NoNewline
        Write-Host "  模板 id 已写入 $idFile"
    }
    Write-Host ""
    Write-Host "夹具就绪：模板 id = $tplId（门槛 $Threshold 分、抵扣 $Discount 分）"
} else {
    Write-Host "> 清理 UI 自测夹具并自证复原"
    $tplId = $null
    if (Test-Path -LiteralPath $idFile) { $tplId = "$(Get-Content -LiteralPath $idFile -Raw)".Trim() }
    if ([string]::IsNullOrEmpty($tplId)) {
        # 没有 id 文件时按名字兜底
        $tplId = Sql 'duoduo_marketing' "SELECT COALESCE(MAX(id), 0) FROM t_coupon_template WHERE name = '$TplName';"
        Write-Host "  （未找到 id 文件，按名字兜底定位到 id=$tplId）"
    }

    Sql 'duoduo_marketing' "DELETE FROM t_coupon WHERE template_id = $tplId;" | Out-Null
    Sql 'duoduo_marketing' "DELETE FROM t_coupon_template WHERE id = $tplId;" | Out-Null

    $leftCoupons = Sql 'duoduo_marketing' "SELECT COUNT(*) FROM t_coupon WHERE template_id = $tplId;"
    $leftTpl = Sql 'duoduo_marketing' "SELECT COUNT(*) FROM t_coupon_template WHERE id = $tplId;"
    Check '券夹具已删除（库内复原）' ($leftCoupons -eq '0') $leftCoupons
    Check '模板夹具已删除（库内复原）' ($leftTpl -eq '0') $leftTpl

    $seeds = Sql 'duoduo_marketing' 'SELECT COUNT(*) FROM t_coupon_template WHERE id IN (5001, 5002);'
    Check '★ 种子券模板未被误删（5001/5002 仍在）' ($seeds -eq '2') $seeds

    if (Test-Path -LiteralPath $idFile) { Remove-Item -LiteralPath $idFile -Force }
}

Write-Host ""
Write-Host "=== 夹具脚本（$Action）结果：通过 $pass 项，失败 $fail 项 ==="
if ($fail -gt 0) { exit 1 }
exit 0
