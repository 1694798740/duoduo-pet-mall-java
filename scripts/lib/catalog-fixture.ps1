# 商品域「SKU 夹具」+「关键词 SPU 夹具」（E27）：把冒烟脚本里**硬编码的种子商品**换成
# 「现场解析 + 缺啥建啥」。
#
# 【要解决的问题】
# ① SKU 号写死：trade-smoke / order-smoke 原先写死 `2001/2002/2004/2009/2021` 等种子 SKU：
#   2004 库存较小、2009 售罄、2021 已下架。
# ② 商品词依赖种子：ai-smoke 断言「幼犬/狗粮可召回」，靠的是种子里有一条犬粮 SPU。
# 本机库经多轮清理后这些行**已不存在**，于是夹具读到 0 → 断言用错误期望继续跑或直接终止；
# 而 CI 用全新库初始化，这些号又是存在的 —— 于是「本机红、CI 绿」，
# 谁都不确定到底是数据漂移还是代码回归。这正是 E27 要消掉的那类不确定性。
#
# 【做法】需要什么样的商品就去找，找不到就**按真实形态建一个**（同一张表、同样的列约束），
# 跑完由调用方调用对应的 Remove-* 删掉。断言因此不再依赖「种子数据里恰好有第 2009 号」。
#
# 【为什么 SKU 建在既有 SPU 下而不是新建 SPU】SKU 的可见性由宿主 SPU 决定
# （在售 + 已过审），新建 SPU 还要一并处理类目/品牌/审核字段，成本远高于收益；
# 挂在既有可售 SPU 下，语义与真实商品完全一致。
# 反过来，「关键词召回」这条断言要的**正是**一条标题含目标词的 SPU —— 那里就该新建 SPU。
#
# 用法：
#   . (Join-Path $PSScriptRoot 'lib\catalog-fixture.ps1')
#   $fx = Resolve-SkuFixtures -MinStock 5
#   # $fx.A / $fx.B / $fx.Tight / $fx.SoldOut / $fx.OffShelf / $fx.CreatedIds
#   $df = Resolve-DogFoodSpuFixture
#   # $df.Id / $df.CreatedIds
#   Remove-SkuFixtures -Ids $fx.CreatedIds
#   Remove-SpuFixtures -Ids $df.CreatedIds

function Invoke-ProductSql {
    param([Parameter(Mandatory = $true)][string]$Query)
    $out = & docker exec duoduo-mysql mysql -uduoduo -pduoduo -N -B `
        --default-character-set=utf8mb4 -D duoduo_product -e $Query 2>$null
    return @(@($out) | ForEach-Object { "$_".Trim() } | Where-Object { $_ -ne '' })
}

# 夹具 SKU 的 id 段位：'99' 开头 + 7 位随机。
# 刻意避开种子区间（2001–2021 量级）与雪花区间（18 位），
# 于是「库里冒出这个号」一眼就能认出是夹具，不会与真实商品混淆。
function New-CatalogFixtureSkuId {
    return ('99' + (Get-Random -Minimum 1000000 -Maximum 9999999))
}

function New-CatalogFixtureSku {
    param(
        [Parameter(Mandatory = $true)][string]$SpuId,
        [Parameter(Mandatory = $true)][long]$Price,
        [Parameter(Mandatory = $true)][int]$Stock,
        [Parameter(Mandatory = $true)][string]$Status,
        [Parameter(Mandatory = $true)][string]$Label
    )
    $id = New-CatalogFixtureSkuId
    $spec = "{`"夹具`":`"$Label`"}"
    $sql = @"
INSERT INTO t_sku (id, spu_id, sku_code, spec_json, image, price, market_price, stock, frozen_stock,
                   is_food, status, created_at, updated_at)
VALUES ($id, $SpuId, 'FIX-$id', '$spec', NULL, $Price, 0, $Stock, 0, 0, '$Status',
        UTC_TIMESTAMP(), UTC_TIMESTAMP());
"@
    Invoke-ProductSql -Query $sql | Out-Null
    # 回读复核：静默失败（列名/约束写错）时必须当场炸，
    # 否则夹具"看起来建了"，断言却拿着空值往下跑，失败原因会被误读成业务缺陷。
    $back = Invoke-ProductSql -Query "SELECT CONCAT(spu_id,'/',stock,'/',status,'/',price) FROM t_sku WHERE id=$id;"
    if ("$back" -ne "$SpuId/$Stock/$Status/$Price") {
        throw "SKU 夹具建立失败：id=$id 期望 $SpuId/$Stock/$Status/$Price 实得 '$back'"
    }
    return $id
}

function Resolve-SkuFixtures {
    <#
    .SYNOPSIS
      解析（必要时现场创建）冒烟脚本需要的五类 SKU。
    .OUTPUTS
      @{ A; B; Tight; SoldOut; OffShelf; HostSpu; CreatedIds }
    #>
    param(
        [int]$MinStock = 5,
        [switch]$SkipDbCheck
    )
    if ($SkipDbCheck) {
        throw 'Resolve-SkuFixtures 依赖库内解析（docker exec mysql）；-SkipDbCheck 下无法构造，请去掉该开关'
    }

    # 宿主 SPU 必须「在售 + 已过审」（采购视图可见性的两个条件），取 id 最小者保证确定性。
    # 找不到就明确炸：这说明种子数据没导入，跑下去的每一个断言都会是无意义的红。
    $hostSpu = (Invoke-ProductSql -Query "SELECT id FROM t_spu WHERE status='ON_SALE' AND audit_status='APPROVED' ORDER BY id LIMIT 1;")
    if (-not $hostSpu) {
        throw '库内没有「在售 + 已过审」的 SPU，无法构造购物车夹具 —— 请先导入种子数据（docs/sql/02-duoduo-product.sql）'
    }
    $hostSpu = "$hostSpu"

    $created = New-Object System.Collections.ArrayList

    # 该 SPU 下所有 ACTIVE 且价格 > 0 的 SKU（含可售库存），按 id 排序保证确定性
    $rows = Invoke-ProductSql -Query "SELECT id, price, (stock - frozen_stock) FROM t_sku WHERE spu_id=$hostSpu AND status='ACTIVE' AND price > 0 ORDER BY id;"
    $pool = @()
    foreach ($line in $rows) {
        $p = "$line" -split "`t"
        if ($p.Count -ge 3) { $pool += [pscustomobject]@{ id = "$($p[0])"; price = [long]$p[1]; avail = [int]$p[2] } }
    }

    # A / B：同一 SPU 下两个「够做累加测试」的 SKU
    $enough = @($pool | Where-Object { $_.avail -ge $MinStock })
    if ($enough.Count -lt 2) {
        $price = if ($pool.Count -gt 0) { $pool[0].price } else { 9900 }
        while ($enough.Count -lt 2) {
            $id = New-CatalogFixtureSku -SpuId $hostSpu -Price $price -Stock 500 -Status 'ACTIVE' -Label '可售'
            $created.Add($id) | Out-Null
            $enough += [pscustomobject]@{ id = "$id"; price = [long]$price; avail = 500 }
        }
    }
    $skuA = "$($enough[0].id)"
    $skuB = "$($enough[1].id)"

    # Tight：库存 < 200，用来精确命中「超库存」而不是「超单次数量上限」
    $tight = $pool | Where-Object { $_.avail -ge 1 -and $_.avail -lt 200 -and $_.id -ne $skuA -and $_.id -ne $skuB } | Select-Object -First 1
    if (-not $tight) {
        $id = New-CatalogFixtureSku -SpuId $hostSpu -Price ([long]$enough[0].price) -Stock 5 -Status 'ACTIVE' -Label '小库存'
        $created.Add($id) | Out-Null
        $tight = [pscustomobject]@{ id = "$id"; price = [long]$enough[0].price; avail = 5 }
    }

    # SoldOut：可售库存为 0（ACTIVE + stock=0 → 采购视图 sellable=false、availableStock=0）
    $sold = Invoke-ProductSql -Query "SELECT id FROM t_sku WHERE spu_id=$hostSpu AND status='ACTIVE' AND (stock - frozen_stock) <= 0 ORDER BY id LIMIT 1;"
    if (-not $sold) {
        $id = New-CatalogFixtureSku -SpuId $hostSpu -Price ([long]$enough[0].price) -Stock 0 -Status 'ACTIVE' -Label '售罄'
        $created.Add($id) | Out-Null
        $sold = "$id"
    }

    # OffShelf：SKU 状态非 ACTIVE（下架）
    $off = Invoke-ProductSql -Query "SELECT id FROM t_sku WHERE spu_id=$hostSpu AND status <> 'ACTIVE' ORDER BY id LIMIT 1;"
    if (-not $off) {
        $id = New-CatalogFixtureSku -SpuId $hostSpu -Price ([long]$enough[0].price) -Stock 100 -Status 'OFF_SHELF' -Label '已下架'
        $created.Add($id) | Out-Null
        $off = "$id"
    }

    return @{
        A          = $skuA
        B          = $skuB
        Tight      = "$($tight.id)"
        SoldOut    = "$sold"
        OffShelf   = "$off"
        HostSpu    = $hostSpu
        CreatedIds = @($created)
    }
}

# 拆除本次现场创建的夹具 SKU（只在 CreatedIds 上动手，绝不碰种子数据）。
# 不删会怎样：夹具 SKU 会一直躺在库里，下一次跑又被当成"既有数据"解析出来 ——
# 「夹具越跑越多」正是历轮把种子库搞乱的老路。
function Remove-SkuFixtures {
    param([string[]]$Ids)
    if (-not $Ids -or $Ids.Count -eq 0) { return }
    $list = (@($Ids) | Where-Object { $_ }) -join ','
    if (-not $list) { return }
    Invoke-ProductSql -Query "DELETE FROM t_sku WHERE id IN ($list);" | Out-Null
}

# ---------------- 关键词 SPU 夹具（E27，供 ai-smoke） ----------------

# 夹具 SPU 的 id 段位：'97' 开头 + 7 位随机（与 SKU 夹具 '99'、闲置夹具 '98' 并列）。
function New-CatalogFixtureSpuId {
    return ('97' + (Get-Random -Minimum 1000000 -Maximum 9999999))
}

function New-CatalogFixtureSpu {
    param(
        [Parameter(Mandatory = $true)][string]$Title,
        [Parameter(Mandatory = $true)][string]$Subtitle,
        [Parameter(Mandatory = $true)][string]$CategoryId,
        [Parameter(Mandatory = $true)][long]$Price,
        [string]$SupplyType = 'self'
    )
    $id = New-CatalogFixtureSpuId
    $sql = @"
INSERT INTO t_spu (id, title, subtitle, category_id, brand_id, supply_type, main_image, images, detail,
                   status, audit_status, sales_count, rating, rating_count, min_price, max_price,
                   created_at, updated_at, seller_id)
VALUES ($id, '$Title', '$Subtitle', $CategoryId, NULL, '$SupplyType', NULL, NULL,
        'E27 冒烟夹具（脚本自建、跑完自动删除）',
        'ON_SALE', 'APPROVED', 0, 5.00, 0, $Price, $Price,
        UTC_TIMESTAMP(), UTC_TIMESTAMP(), 0);
"@
    Invoke-ProductSql -Query $sql | Out-Null
    # 回读复核：列名/约束写错时当场炸，避免"夹具看着建了、断言却拿着空值往下跑"
    $back = Invoke-ProductSql -Query "SELECT CONCAT(status,'/',audit_status,'/',category_id,'/',min_price) FROM t_spu WHERE id=$id;"
    if ("$back" -ne "ON_SALE/APPROVED/$CategoryId/$Price") {
        throw "SPU 夹具建立失败：id=$id 期望 ON_SALE/APPROVED/$CategoryId/$Price 实得 '$back'"
    }
    return $id
}

function Resolve-DogFoodSpuFixture {
    <#
    .SYNOPSIS
      现场建一条「犬主粮」SPU，让「幼犬/狗粮」这类中文意图**确有货可召**。
    .DESCRIPTION
      ai-smoke 的原断言「推荐一款适合幼犬吃的狗粮 → 推荐列表非空」依赖种子里有一条犬粮商品。
      本机库清理后只剩两条猫粮 SPU，召回必然为空 → 断言红的是数据、不是代码。

      为什么不改成「用库里现有词」：那等于把断言从「品类意图能被召回」
      降级成「随便什么词都能召回」，覆盖直接塌掉。
      这里按本仓 E27 的既定做法「现场建前置、跑完自毁」补齐商品，
      断言仍是原来那条，且在本机 / CI 上行为一致。

      ★ 类目必须落在「宠物主粮」子树内：CategoryCatalog 把「狗粮」别名到**一级类目**
      「宠物主粮」，商品域再展开成「自己 + 子类目」过滤 —— 挂错类目会被类目条件筛掉，
      表现为"召回还是空的"，与没建夹具长得一模一样。
    .OUTPUTS
      @{ Id; CreatedIds }
    #>
    param([switch]$SkipDbCheck)
    if ($SkipDbCheck) {
        throw 'Resolve-DogFoodSpuFixture 依赖库内直建；-SkipDbCheck 下无法构造，请去掉该开关'
    }

    # 类目按**名字**查而不是写死 id：类目重建时 id 会变、名字不会。
    $catId = "$(Invoke-ProductSql -Query "SELECT id FROM t_category WHERE name='犬主粮' AND status='ACTIVE' ORDER BY id LIMIT 1;")"
    if (-not $catId) {
        throw '库内没有启用中的「犬主粮」类目，无法构造关键词召回夹具 —— 请先导入种子类目（docs/sql/02-duoduo-product.sql）'
    }

    $created = New-Object System.Collections.ArrayList
    $id = New-CatalogFixtureSpu -Title 'E27夹具·幼犬狗粮 2kg' -Subtitle '幼犬狗粮 · 冒烟夹具' `
        -CategoryId $catId -Price 12900
    $created.Add($id) | Out-Null

    return @{
        Id         = "$id"
        CategoryId = $catId
        CreatedIds = @($created)
    }
}

# 拆除本次现场创建的夹具 SPU（只在 CreatedIds 上动手，绝不碰种子数据）。
function Remove-SpuFixtures {
    param([string[]]$Ids)
    if (-not $Ids -or $Ids.Count -eq 0) { return }
    $list = (@($Ids) | Where-Object { $_ }) -join ','
    if (-not $list) { return }
    Invoke-ProductSql -Query "DELETE FROM t_sku WHERE spu_id IN ($list);" | Out-Null
    Invoke-ProductSql -Query "DELETE FROM t_spu WHERE id IN ($list);" | Out-Null
}
