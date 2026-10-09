# 冒烟「卖家夹具」（E27）：在库内直建一个「已通过审核的商家」，把账号升为 SELLER。
#
# 【为什么不再调 POST /api/users/me/merchant】
# 那条端点是 E10 之前的"实名即开店"旧路径，**E10 已主动删除**（无审核开店的口子必须封死）。
# 四个业务套件（seller / aftersale / logistics / credit）真正需要的只是
# 「一个能接单的卖家」，让它们各自完整走一遍入驻状态机会多出十几个与自身无关的步骤 ——
# 完整入驻链路的覆盖是 E11 审核台（merchant-audit-ui-check）的交付项。
#
# 【与 JS 版同构】本文件是 `scripts/lib/e2e-seller-fixture.mjs` 的 PowerShell 对应物，
# 直建的三张表与 `MerchantOnboardingService.approve` 的开店动作一致：
#   t_merchant         status=APPROVED（审核字段按真实形态填充，审核台列表能看到它）
#   t_merchant_member  role=OWNER / status=ACTIVE
#   t_user             role=SELLER
#
# 【为什么直建不用 API 拼】t_merchant.uk_owner 与 t_merchant_member.uk_user 都是唯一键，
# 夹具必须用**本次运行新注册**的账号；且账号被清库删掉后重跑必须仍能建 —— 直建天然满足。
#
# 用法：
#   . (Join-Path $PSScriptRoot 'lib\seller-fixture.ps1')
#   $fx = New-SellerFixture -Phone $phoneS -StoreName '物流测试-卖家S' -Session $sessS
#   # 之后必须重新登录（角色是 access token 里的快照）
#
# 前置：账号已注册（本函数不负责注册）。

function Invoke-FixtureSql {
    # 与各冒烟脚本的 Sql 同参数（docker exec + -uduoduo -pduoduo），保持连接口径一致 ——
    # 写成别的用户会 Access denied，而错误被吞掉时就只剩「夹具没建成」这个假象。
    param(
        [Parameter(Mandatory = $true)][string]$Database,
        [Parameter(Mandatory = $true)][string]$Query
    )
    $out = & docker exec duoduo-mysql mysql -uduoduo -pduoduo -N -B `
        --default-character-set=utf8mb4 -D $Database -e $Query 2>$null
    return ("$out").Trim()
}

# 与雪花 id 同量级的唯一 id（毫秒时间戳 × 1000 + 随机后三位）。
# 纪律：BIGINT 主键一律**按字符串**流转 —— 转成 Int64 再拼 SQL 会在 PS 5.1 里
# 走 Double 路径丢精度（19 位雪花超过 double 的 53 位有效位）。
function New-FixtureId {
    $ms = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $tail = Get-Random -Minimum 100 -Maximum 999
    return (([bigint]$ms * 1000) + [bigint]$tail).ToString()
}

function New-SellerFixture {
    <#
    .SYNOPSIS
      在库内直建一个 APPROVED 商家，并把账号角色升为 SELLER。
    .OUTPUTS
      @{ merchantId; storeName; roleSet; role }
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Phone,
        [string]$StoreName = '夹具店铺',
        [string]$Gateway = 'http://127.0.0.1:8080',
        [object]$Session,           # 卖家登录会话；给了且 -DoRealname 时会先调实名接口
        [switch]$DoRealname,
        [switch]$SkipDbCheck        # 与各套件同名开关：夹具依赖库内直建，开了就无法工作
    )

    if ($SkipDbCheck) {
        throw 'New-SellerFixture 依赖库内直建（docker exec mysql）；-SkipDbCheck 下无法构造卖家，请去掉该开关'
    }

    # 1) 实名前置：卖家接口（发布商品 / 发货 / 结算）都要求已实名。
    #    走真实接口而不是改库，是因为实名字段还有 t_user 侧的一致性约束，
    #    绕过它会让「实名前置 422」这类断言失去意义。
    if ($DoRealname) {
        if (-not $Session) {
            throw 'DoRealname 需要提供 -Session'
        }
        $body = @{ realName = '李小商'; idCard = '310101199005054321' } | ConvertTo-Json -Compress
        try {
            $res = Invoke-WebRequest -Uri "$Gateway/api/users/me/realname" -Method Post -Body $body `
                -ContentType 'application/json; charset=utf-8' -UseBasicParsing `
                -WebSession $Session -TimeoutSec 20
            if ($res.StatusCode -ne 200) {
                throw "实名失败 status=$($res.StatusCode)"
            }
        } catch {
            throw "实名失败：$($_.Exception.Message)"
        }
    }

    # 2) 店铺名必须带唯一后缀：t_merchant.uk_name 是唯一键，
    #    固定店名第二跑就撞（首跑绿、重跑红 —— 「看起来像抖动其实是夹具缺陷」的典型）。
    $name = "$StoreName-$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"

    $merchantId = New-FixtureId
    $memberId = New-FixtureId

    # owner_user_id 用子查询按手机号取，避免在 PS 里搬运 19 位 id。
    $sqlMerchant = @"
INSERT INTO t_merchant (id, name, owner_user_id, status, isolation_level, applied_at, reviewed_at, created_at, updated_at)
VALUES ($merchantId, '$name', (SELECT id FROM t_user WHERE phone='$Phone'), 'APPROVED', 'STANDARD',
        UTC_TIMESTAMP(), UTC_TIMESTAMP(), UTC_TIMESTAMP(), UTC_TIMESTAMP());
"@
    Invoke-FixtureSql -Database 'duoduo_user' -Query $sqlMerchant | Out-Null

    $sqlMember = @"
INSERT INTO t_merchant_member (id, merchant_id, user_id, role, status, created_at, updated_at)
VALUES ($memberId, $merchantId, (SELECT id FROM t_user WHERE phone='$Phone'), 'OWNER', 'ACTIVE',
        UTC_TIMESTAMP(), UTC_TIMESTAMP());
"@
    Invoke-FixtureSql -Database 'duoduo_user' -Query $sqlMember | Out-Null

    Invoke-FixtureSql -Database 'duoduo_user' -Query "UPDATE t_user SET role='SELLER' WHERE phone='$Phone';" | Out-Null

    # 3) 回读复核：三步都静默失败时（例如表名写错、账号不存在）函数必须**当场炸**，
    #    否则套件会带着 USER 角色往下跑，报错变成「该账号不是卖家」——
    #    排查方向会完全跑偏到权限逻辑上去，而根因其实在夹具。
    $role = Invoke-FixtureSql -Database 'duoduo_user' -Query "SELECT role FROM t_user WHERE phone='$Phone';"
    $row = Invoke-FixtureSql -Database 'duoduo_user' -Query "SELECT status, owner_user_id FROM t_merchant WHERE id=$merchantId;"
    $member = Invoke-FixtureSql -Database 'duoduo_user' -Query "SELECT role FROM t_merchant_member WHERE merchant_id=$merchantId;"

    $roleSet = ($role -eq 'SELLER')
    if (-not $roleSet) {
        throw "夹具失败：t_user.role 期望 SELLER 实得 '$role'（phone=$Phone）"
    }
    if ("$row" -notmatch '^APPROVED\b') {
        throw "夹具失败：t_merchant.status 期望 APPROVED 实得 '$row'（id=$merchantId）"
    }
    if ("$member" -ne 'OWNER') {
        throw "夹具失败：t_merchant_member.role 期望 OWNER 实得 '$member'（merchantId=$merchantId）"
    }

    return @{ merchantId = $merchantId; storeName = $name; roleSet = $roleSet; role = $role }
}

function Remove-SellerFixture {
    <#
    .SYNOPSIS
      拆除夹具（子表先删，再删主表）。默认不自动调用 —— 四个套件历来保留夹具账号，
      清理口径变更需各套件的断言一并复核；此处只提供能力，供新套件或手工清理使用。
    #>
    param([Parameter(Mandatory = $true)][string]$MerchantId)
    Invoke-FixtureSql -Database 'duoduo_user' -Query "DELETE FROM t_merchant_member WHERE merchant_id=$MerchantId;" | Out-Null
    Invoke-FixtureSql -Database 'duoduo_user' -Query "DELETE FROM t_merchant_material WHERE merchant_id=$MerchantId;" | Out-Null
    Invoke-FixtureSql -Database 'duoduo_user' -Query "DELETE FROM t_merchant WHERE id=$MerchantId;" | Out-Null
}
