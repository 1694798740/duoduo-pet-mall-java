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

function Clear-OperatorAuditTrail {
    <#
    .SYNOPSIS
      清掉「本次夹具运营账号」作为**操作人**的审计行（E18 之后的收尾必做项）。
    .DESCRIPTION
      E18 起，后台**每一次写操作都会留痕** —— 包括冒烟脚本自己发起的那些
      （建类目、改品牌状态、审闲置、强关订单、改角色……）。它们会以本次
      随机手机号那个夹具账号为 `actor_id` 落进 `t_audit_log`。

      不清的话有两层坏处：
        ① 审计台账（它是「追责台账」）被测试噪声持续灌入，越跑越像真的；
        ② 更隐蔽的一层：那个账号收尾后已降回 USER，台账里却留着
           「一个 USER 干了这些运营动作」的记录 —— 将来真去查账会得出错误结论。

      为什么按 `actor_id` 删是安全的：本函数只接受**本次运行随机生成的**手机号，
      对应的 `user_id` 在整个库里只可能是这一个夹具账号，删除范围精确到它自己。
      **绝不按时间/按动作/按库批量删** —— 那会把真实运营记录一起清掉。
    .PARAMETER Phone
      本次夹具运营账号的手机号（`New-PlatformOperatorSession` 返回值里的 `phone`）。
    .OUTPUTS
      删除后该 actor 剩余行数（应为 0），供调用方断言。
    #>
    param([Parameter(Mandatory = $true)][string]$Phone)
    & docker exec duoduo-mysql mysql -uduoduo -pduoduo -N -B -D duoduo_user `
        -e "DELETE a FROM t_audit_log a JOIN t_user u ON u.id = a.actor_id WHERE u.phone = '$Phone';" 2>&1 | Out-Null
    $row = & docker exec duoduo-mysql mysql -uduoduo -pduoduo -N -B -D duoduo_user `
        -e "SELECT COUNT(*) FROM t_audit_log a JOIN t_user u ON u.id = a.actor_id WHERE u.phone = '$Phone';" 2>$null
    return ("$row").Trim()
}

function Remove-PlatformOperatorPrivilege {
    <#
    .SYNOPSIS
      收回本次运行临时提权的运营角色（收尾用）。
    .DESCRIPTION
      运营角色只是冒烟为了拿到后台入口而临时提的。不收尾的话，每跑一轮就在库里留一个
      永久可进后台的账号 —— 这类残留不会让任何断言变红，只会让攻击面随运行次数增长
      （E26 清理存量时就见过一批）。所以新套件一律在结尾调用本函数。
      刻意「降级」而不是「删号」：账号是本次随机手机号注册的，随时可再提权；
      而删号会牵连它参与过的业务数据，副作用大于收益。

      ★ E18 起本函数**顺带清理该账号的审计行**（调用 `Clear-OperatorAuditTrail`）。
      刻意合并在同一个收尾函数里而不是拆成两个让调用方各调一次：
      「收尾要做两件事、但只记得做一件」是注定会发生的（E26 的根因就是收尾不可靠），
      合并之后**现有 3 个调用点一行业务代码都没改就自动正确了**。
    .OUTPUTS
      收回后的角色字符串（应为 USER），供调用方断言。
    #>
    param([Parameter(Mandatory = $true)][string]$Phone)
    & docker exec duoduo-mysql mysql -uduoduo -pduoduo -N -B -D duoduo_user `
        -e "UPDATE t_user SET role='USER' WHERE phone='$Phone';" 2>&1 | Out-Null
    Clear-OperatorAuditTrail -Phone $Phone | Out-Null
    $row = & docker exec duoduo-mysql mysql -uduoduo -pduoduo -N -B -D duoduo_user `
        -e "SELECT role FROM t_user WHERE phone='$Phone';" 2>$null
    return ("$row").Trim()
}
