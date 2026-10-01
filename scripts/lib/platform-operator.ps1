# 平台运营会话（供依赖「卖家发货」的冒烟脚本共用）
#
# 【为什么需要它】原先的 `POST /api/orders/{orderNo}/ship` 是一个没有归属校验的旧内部端点，
# 任何登录用户（甚至只带内部凭据）都能给任意订单发货 —— 它已被 `/api/seller/orders/{orderNo}/ship`
# 取代，后者会校验「调用者是不是这笔订单的卖家」。
#
# 自营商品的订单 seller_id = 0 归平台，**只有 OPERATOR 角色可见**（SellerService 的
# platformScope），普通卖家账号对它是 403。所以迁移后的脚本不能沿用买家会话发货，
# 必须拿一个运营会话 —— 这个函数就是干这个的。
#
# 【不污染数据】手机号是本次运行随机生成的，跑完不清理也只是多一个「平台运营-夹具」账号，
# 绝不会把某个既有账号改成 OPERATOR（那才是最难排查的一类脏数据）。
#
# 用法：
#   . (Join-Path $PSScriptRoot 'lib\platform-operator.ps1')
#   $op = New-PlatformOperatorSession -Gateway $Gateway -Password $Password
#   $sessOp = $op.session

function New-PlatformOperatorSession {
    param(
        [string]$Gateway = 'http://127.0.0.1:8080',
        [string]$Password = 'duoduo12345'
    )

    $phone = '139' + (Get-Random -Minimum 10000000 -Maximum 99999999)
    $regBody = @{ phone = $phone; password = $Password; nickname = '平台运营-夹具' } | ConvertTo-Json -Compress
    try {
        Invoke-WebRequest -Uri "$Gateway/api/auth/register" -Method Post -Body $regBody `
            -ContentType 'application/json; charset=utf-8' -UseBasicParsing -TimeoutSec 20 | Out-Null
    } catch {
        # 手机号撞号等情况下继续尝试登录即可（真失败会在下面断言里暴露）
    }

    # 提权走库：运营角色没有自助申请入口（也不该有 —— 那是后台人工审批的事）。
    # 连接参数与其余冒烟脚本的 Sql 保持一致（docker exec + -uduoduo -pduoduo），
    # 写成别的用户会 Access denied，而这里把它 2>$null 掉就只剩「角色没变」这个假象。
    $roleSet = $false
    $lastRole = 'UNKNOWN'
    for ($i = 0; $i -lt 5; $i++) {
        & docker exec duoduo-mysql mysql -uduoduo -pduoduo -N -B -D duoduo_user `
            -e "UPDATE t_user SET role='OPERATOR' WHERE phone='$phone';" 2>&1 | Out-Null
        Start-Sleep -Milliseconds 200
        $row = & docker exec duoduo-mysql mysql -uduoduo -pduoduo -N -B -D duoduo_user `
            -e "SELECT role FROM t_user WHERE phone='$phone';" 2>$null
        $lastRole = ("$row").Trim()
        if ($lastRole -eq 'OPERATOR') {
            $roleSet = $true
            break
        }
    }

    $loginBody = @{ phone = $phone; password = $Password } | ConvertTo-Json -Compress
    Invoke-WebRequest -Uri "$Gateway/api/auth/login" -Method Post -Body $loginBody `
        -ContentType 'application/json; charset=utf-8' -UseBasicParsing -TimeoutSec 20 `
        -SessionVariable sess | Out-Null

    # 角色没提成就必须让调用方看见：带着 USER 角色去调卖家接口只会得到 403，
    # 而报错是「该账号不是卖家」—— 排查方向会完全跑偏到权限逻辑上去。
    return @{ session = $sess; phone = $phone; roleSet = $roleSet; role = $lastRole }
}
