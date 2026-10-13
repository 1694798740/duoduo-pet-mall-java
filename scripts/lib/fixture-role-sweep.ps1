<#
    B-2 配套 —— 把「历轮自测漏下的特权夹具账号」降权（可逆，附回滚脚本）。

    ── 为什么需要它 ────────────────────────────────────────────────────
    冒烟 / e2e 为了测「运营」「商家」两条链路，必须现场造出 OPERATOR / SELLER 账号。
    这些脚本**注册完就赋权、跑完不收尾**，于是每跑一轮全量回归就沉淀几十个特权账号。
    实测：2026-10-10 记的是 199 OPERATOR + 139 SELLER，到 2026-10-13 已涨到
    **510 OPERATOR + 434 SELLER（合计 944）** —— 也就是说「根因已在 E18 修掉」这句话
    至少在**部分脚本上**是不成立的，暴露面还在扩大。

    ── 为什么选「降权」而不是「删号」 ──────────────────────────────────
    ① 可逆：只改 role 一列，原值全部留档，一条 SQL 就能恢复；
    ② 无副作用：账号一旦没有特权，就再也进不了后台 —— 暴露面当场消失；
    ③ 删号不可逆，而这些账号还挂在 t_merchant / t_order 等表的外键语义上，
       删了会留下一堆孤儿引用，反而制造新问题。

    ── 判据（保守，且每条都能解释） ────────────────────────────────────
    · `role <> 'USER'`
    · `created_at >= -SinceCreatedAt`（默认 2026-10-08）—— 实测该日之前只有 **1 个**
      特权账号（`13684295254`，2026-10-01 建的），其余全在自测期之后
    · `id <> -ProtectedId`（默认就是那一个）—— **绝不误伤唯一的真账号**
    · 命中数必须 > 0，否则判「扫描失效」而不是「很干净」

    ── 用法 ────────────────────────────────────────────────────────────
      # 干跑（默认）：只列计数与样本，不写库
      powershell -NoProfile -ExecutionPolicy Bypass -File scripts\lib\fixture-role-sweep.ps1
      # 真跑：写库前先把 (id, role) 落成回滚脚本
      ... -Apply
      # 只处理指定 id（给 run-all-smoke.ps1 的"运行前后 diff"用）
      ... -Apply -IdsFile <每行一个 id>
      # 回滚（如需）
      docker exec -i duoduo-mysql mysql -uroot -p*** -D duoduo_user < <回滚目录>\rollback-<时间戳>.sql

    退出码：0 = 正常（含干跑）；1 = 判据失效 / 执行出错。
#>
[CmdletBinding()]
param(
    [string]$MysqlContainer = 'duoduo-mysql',
    [string]$MysqlUser = 'root',
    [string]$MysqlPassword = 'duoduo_root',
    [string]$SinceCreatedAt = '2026-10-08 00:00:00',
    [string]$ProtectedId = '363903492038987776',
    [string]$OutDir = 'D:\duoduo-backup\fixture-sweep',
    [string]$IdsFile = '',
    [switch]$Apply
)

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# ★ 用 MYSQL_PWD 而不是 `-p<密码>`：后者每次都往 stderr 打一句
#   "Using a password on the command line interface can be insecure"，
#   而 PowerShell 会把**原生命令的 stderr** 当成 error record ——
#   配上 $ErrorActionPreference='Stop' 会直接掀掉整个脚本（实测踩过一次）。
#   从源头消掉警告，比到处 try/catch 干净。
# ★ 但必须用 `docker exec -e` 显式传进去：docker exec 是**新起进程**，
#   宿主机的环境变量不会自动带进容器（实测：不带 -e 时 mysql 回 1045 Access denied
#   "using password: NO"，而报错里只有一句 Access denied，看不出是密码根本没送到）。
function SqlScalar([string]$sql) {
    $out = & docker exec -i -e "MYSQL_PWD=$MysqlPassword" $MysqlContainer mysql "-u$MysqlUser" -N -B --default-character-set=utf8mb4 -e $sql 2>$null
    if ($LASTEXITCODE -ne 0) { throw "mysql 调用失败（exit=$LASTEXITCODE）：$sql" }
    return ($out | Out-String).Trim()
}
function SqlRows([string]$sql) {
    $out = & docker exec -i -e "MYSQL_PWD=$MysqlPassword" $MysqlContainer mysql "-u$MysqlUser" -N -B --default-character-set=utf8mb4 -e $sql 2>$null
    if ($LASTEXITCODE -ne 0) { throw "mysql 调用失败（exit=$LASTEXITCODE）：$sql" }
    return @($out | Where-Object { $_ -ne '' })
}
function Say([string]$m, [string]$c = 'Gray') { Write-Host $m -ForegroundColor $c }

# ── ① 选目标 ────────────────────────────────────────────────────────────
if ($IdsFile) {
    if (-not (Test-Path -LiteralPath $IdsFile)) { throw "找不到 IdsFile：$IdsFile" }
    $ids = @(Get-Content -LiteralPath $IdsFile | Where-Object { $_.Trim() -match '^\d+$' } | ForEach-Object { $_.Trim() })
    if ($ids.Count -eq 0) { throw 'IdsFile 里没有有效 id —— 拒绝在"零目标"的前提下继续（那会让人误以为"扫过了、很干净"）' }
    $idList = ($ids -join ',')
    Say ("[sweep] 来源 = IdsFile（{0} 个 id）" -f $ids.Count) 'Cyan'
} else {
    $where = "role <> 'USER' AND created_at >= '$SinceCreatedAt' AND id <> $ProtectedId"
    $ids = @(SqlRows "SELECT id FROM duoduo_user.t_user WHERE $where;")
    Say ("[sweep] 来源 = 判据（role<>USER 且 created_at >= {0}，保护 id={1}）" -f $SinceCreatedAt, $ProtectedId) 'Cyan'
}

if ($ids.Count -eq 0) {
    Say '[sweep] [FAIL] 命中 0 条 —— 判据失效或库连不上。拒绝把它当成"很干净"。' 'Red'
    exit 1
}
$idList = ($ids -join ',')

# ── ② 保护账号自证：确认它确实存在、且**不在**待处理集合里 ───────────────
$protected = SqlScalar "SELECT CONCAT(id,'|',phone,'|',role,'|',created_at) FROM duoduo_user.t_user WHERE id=$ProtectedId;"
if (-not $protected) {
    Say ("[sweep] [FAIL] 保护账号 {0} 不存在 —— 判据前提不成立，拒绝执行" -f $ProtectedId) 'Red'
    exit 1
}
if ($ids -contains $ProtectedId) {
    Say '[sweep] [FAIL] 保护账号竟然出现在目标集合里 —— 立即中止' 'Red'
    exit 1
}
Say ("[sweep] 保护账号（保留不动）：{0}" -f $protected) 'Green'

# ── ③ 现状统计（降权前） ────────────────────────────────────────────────
$before = SqlScalar "SELECT CONCAT(SUM(role='OPERATOR'),'|',SUM(role='SELLER'),'|',SUM(role='ADMIN')) FROM duoduo_user.t_user;"
Say ("[sweep] 降权前 全库：OPERATOR|SELLER|ADMIN = {0}" -f $before)
Say ("[sweep] 待降权 {0} 个" -f $ids.Count) 'Yellow'
Say '[sweep] 样本（前 8 条）：'
SqlRows "SELECT id, phone, role, nickname FROM duoduo_user.t_user WHERE id IN ($idList) ORDER BY created_at LIMIT 8;" |
    ForEach-Object { Say ("           {0}" -f $_) 'DarkGray' }

# ── ④ 落回滚脚本（**写库之前**必须先落档） ──────────────────────────────
if (-not (Test-Path -LiteralPath $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$rollbackPath = Join-Path $OutDir ("rollback-$stamp.sql")
$listPath = Join-Path $OutDir ("downgraded-$stamp.txt")

$rows = SqlRows "SELECT id, role FROM duoduo_user.t_user WHERE id IN ($idList);"
$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine("-- B-2 夹具特权账号降权 —— 回滚脚本（由 fixture-role-sweep.ps1 于 $stamp 生成）")
[void]$sb.AppendLine("-- 用法：docker exec -i duoduo-mysql mysql -uroot -p*** -D duoduo_user < rollback-$stamp.sql")
foreach ($r in $rows) {
    $p = $r -split "`t"
    if ($p.Count -ge 2) {
        [void]$sb.AppendLine("UPDATE t_user SET role='$($p[1].Trim())' WHERE id=$($p[0].Trim());")
    }
}
[System.IO.File]::WriteAllText($rollbackPath, $sb.ToString(), (New-Object System.Text.UTF8Encoding $false))
[System.IO.File]::WriteAllLines($listPath, $ids, (New-Object System.Text.UTF8Encoding $false))
Say ("[sweep] 回滚脚本已落档：{0}（{1} 条 UPDATE）" -f $rollbackPath, $rows.Count) 'Green'
Say ("[sweep] 待降权清单已落档：{0}" -f $listPath) 'Green'

if (-not $Apply) {
    Say ''
    Say '[sweep] 干跑结束：**没有写库**。要真跑请加 -Apply。' 'Yellow'
    exit 0
}

# ── ⑤ 执行降权 ──────────────────────────────────────────────────────────
$affected = SqlScalar "UPDATE duoduo_user.t_user SET role='USER' WHERE id IN ($idList); SELECT ROW_COUNT();"
Say ("[sweep] UPDATE 影响行数 = {0}" -f $affected)

# ── ⑥ 回读复核（两个来源：预期的 id 数 vs 库里实际剩下的特权数） ─────────
$stillPriv = SqlScalar "SELECT COUNT(*) FROM duoduo_user.t_user WHERE id IN ($idList) AND role <> 'USER';"
$after = SqlScalar "SELECT CONCAT(SUM(role='OPERATOR'),'|',SUM(role='SELLER'),'|',SUM(role='ADMIN')) FROM duoduo_user.t_user;"
Say ("[sweep] 降权后 全库：OPERATOR|SELLER|ADMIN = {0}" -f $after)

$fail = 0
if ("$stillPriv".Trim() -ne '0') { Say ("[sweep] [FAIL] 目标集合里仍有 {0} 个账号是特权角色" -f $stillPriv) 'Red'; $fail++ }
if ("$affected".Trim() -ne [string]$ids.Count) {
    Say ("[sweep] [FAIL] 影响行数 {0} ≠ 目标数 {1}（有账号在两次查询之间被改动？）" -f $affected, $ids.Count) 'Red'; $fail++
}
if ($fail -gt 0) { exit 1 }
Say ("[sweep] [OK] {0} 个夹具特权账号已降为 USER；回滚见 {1}" -f $ids.Count, $rollbackPath) 'Green'
exit 0
