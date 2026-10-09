# 商品域「闲置（C2C）夹具」（E27）：现场造出冒烟脚本需要的那几条闲置。
#
# 【要解决的问题】
# smoke.ps1 原先依赖种子闲置：写死「首页 6 条 / 列表 ≥8 条」、直访 `3001` 详情、
# 直访 `3009`（待审）与 `3010`（驳回）验证不可见。
# 本机库经多轮清理后 `t_listing` 里**已无一条 APPROVED**（历次 e2e 收尾都会把自建的闲置下架），
# 于是「首页 0 条 / 详情 404」全红；而 CI 用全新库初始化，这些行又都在 —— 本机红、CI 绿。
#
# 【做法】现场建三条最小闲置（1 通过 + 1 待审 + 1 驳回），跑完删除。
# 用直建而不是走「发布接口 + 运营审核接口」，是因为本套件的覆盖面是**商品域读路径**
# （首页聚合 / 列表过滤 / 详情与推荐），而不是发布与审核状态机 ——
# 后者由 `my-listings-ui-check.mjs`（47 项）与后台审核套件专门覆盖，重复一遍只会拖慢回归。
#
# 关键约束：`category_id` 必须取一个**确有其 SKU 在售的类目**，
# 否则详情页的「同款全新」推荐必然为空，断言会变成"测不出东西"的假红。
#
# 用法：
#   . (Join-Path $PSScriptRoot 'lib\listing-fixture.ps1')
#   $lf = Resolve-ListingFixtures
#   # $lf.ApprovedId / $lf.PendingId / $lf.RejectedId / $lf.CreatedIds / $lf.ApprovedOnSaleCount

function Invoke-ListingSql {
    # 默认连 duoduo_product（闲置与商品同库）；取发布者 id 时要跨到 duoduo_user，
    # 所以这里必须能换库 —— 写死单库会让 `SELECT id FROM t_user` 恒空。
    param(
        [Parameter(Mandatory = $true)][string]$Query,
        [string]$Database = 'duoduo_product'
    )
    $out = & docker exec duoduo-mysql mysql -uduoduo -pduoduo -N -B `
        --default-character-set=utf8mb4 -D $Database -e $Query 2>$null
    return @(@($out) | ForEach-Object { "$_".Trim() } | Where-Object { $_ -ne '' })
}

function New-ListingFixtureId {
    # '98' 段位：与种子（3001–3010 量级）、雪花（18 位）、SKU 夹具（'99'）都不重叠，
    # 库里一眼能认出这是夹具造的行。
    return ('98' + (Get-Random -Minimum 1000000 -Maximum 9999999))
}

function New-ListingFixtureRow {
    param(
        [Parameter(Mandatory = $true)][string]$SellerId,
        [Parameter(Mandatory = $true)][string]$CategoryId,
        [Parameter(Mandatory = $true)][string]$Title,
        [Parameter(Mandatory = $true)][string]$Status,
        [Parameter(Mandatory = $true)][string]$AuditStatus,
        [Parameter(Mandatory = $true)][long]$Price
    )
    $id = New-ListingFixtureId
    $reason = if ($AuditStatus -eq 'REJECTED') { "'夹具驳回原因'" } else { 'NULL' }
    $auditAt = if ($AuditStatus -eq 'PENDING') { 'NULL' } else { 'UTC_TIMESTAMP()' }
    $sql = @"
INSERT INTO t_listing (id, seller_id, title, description, category_id, price, original_price,
                       condition_level, city, images, species, status, audit_status, audit_reason,
                       audit_at, view_count, want_count, created_at, updated_at)
VALUES ($id, $SellerId, '$Title', 'E27 冒烟夹具（脚本自建、跑完自动删除）', $CategoryId, $Price, 0,
        'GOOD', '上海', NULL, 'CAT', '$Status', '$AuditStatus', $reason,
        $auditAt, 0, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP());
"@
    Invoke-ListingSql -Query $sql | Out-Null
    $back = Invoke-ListingSql -Query "SELECT CONCAT(status,'/',audit_status) FROM t_listing WHERE id=$id;"
    if ("$back" -ne "$Status/$AuditStatus") {
        throw "闲置夹具建立失败：id=$id 期望 $Status/$AuditStatus 实得 '$back'"
    }
    return $id
}

function Resolve-ListingFixtures {
    <#
    .SYNOPSIS
      现场建 1 条已过审 + 1 条待审 + 1 条已驳回的闲置，返回其 id。
    .OUTPUTS
      @{ ApprovedId; PendingId; RejectedId; CreatedIds; CategoryId }
    #>
    param(
        [string]$SellerId,
        [switch]$SkipDbCheck
    )
    if ($SkipDbCheck) {
        throw 'Resolve-ListingFixtures 依赖库内直建；-SkipDbCheck 下无法构造，请去掉该开关'
    }

    if (-not $SellerId) {
        # 优先复用库内**既有闲置的发布者**：它一定是个真实存在、且历史上确实发布过闲置的用户。
        $SellerId = "$(Invoke-ListingSql -Query 'SELECT seller_id FROM t_listing WHERE seller_id IS NOT NULL ORDER BY id LIMIT 1;')"
    }
    if (-not $SellerId) {
        # 兜底：库内已无任何闲置时，跨到 duoduo_user 取一个用户。
        $SellerId = "$(Invoke-ListingSql -Database 'duoduo_user' -Query 'SELECT id FROM t_user ORDER BY id LIMIT 1;')"
    }
    if (-not $SellerId) {
        throw '找不到可用的发布者 id（既无存量闲置，duoduo_user.t_user 也为空）'
    }

    # 类目取「确有一个在售且已过审的 SPU」的那个 —— 详情页的「同款全新」推荐按类目推荐，
    # 取到空类目会让断言恒为空，测不出任何东西。
    $categoryId = "$(Invoke-ListingSql -Query "SELECT category_id FROM t_spu WHERE status='ON_SALE' AND audit_status='APPROVED' AND category_id IS NOT NULL ORDER BY id LIMIT 1;")"
    if (-not $categoryId) {
        throw '库内没有「在售 + 已过审」的 SPU，无法确定闲置夹具的类目'
    }

    $created = New-Object System.Collections.ArrayList
    $approved = New-ListingFixtureRow -SellerId $SellerId -CategoryId $categoryId `
        -Title 'E27 夹具·在售闲置' -Status 'ON_SALE' -AuditStatus 'APPROVED' -Price 12000
    $created.Add($approved) | Out-Null
    $pending = New-ListingFixtureRow -SellerId $SellerId -CategoryId $categoryId `
        -Title 'E27 夹具·待审闲置' -Status 'ON_SALE' -AuditStatus 'PENDING' -Price 13000
    $created.Add($pending) | Out-Null
    $rejected = New-ListingFixtureRow -SellerId $SellerId -CategoryId $categoryId `
        -Title 'E27 夹具·已驳回闲置' -Status 'ON_SALE' -AuditStatus 'REJECTED' -Price 14000
    $created.Add($rejected) | Out-Null

    return @{
        ApprovedId    = "$approved"
        PendingId     = "$pending"
        RejectedId    = "$rejected"
        CategoryId    = $categoryId
        CreatedIds    = @($created)
    }
}

# 删除本次现场建的闲置（只动 CreatedIds）。
function Remove-ListingFixtures {
    param([string[]]$Ids)
    if (-not $Ids -or $Ids.Count -eq 0) { return }
    $list = (@($Ids) | Where-Object { $_ }) -join ','
    if (-not $list) { return }
    Invoke-ListingSql -Query "DELETE FROM t_listing WHERE id IN ($list);" | Out-Null
}

# 库内「已过审且在售且未删除」的闲置条数 —— 列表/首页断言的**期望值来源**。
# 计数类断言必须对着它算，不能写死条数：写死就变成"钉当前有几条数据"，
# 环境一变就红，而它想守的其实是「只返回已过审的那批」。
function Get-ApprovedOnSaleListingCount {
    $n = "$(Invoke-ListingSql -Query "SELECT COUNT(*) FROM t_listing WHERE audit_status='APPROVED' AND status='ON_SALE' AND deleted_at IS NULL;")"
    if (-not $n) { return 0 }
    return [int]$n
}
