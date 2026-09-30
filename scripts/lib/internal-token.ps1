# 服务间调用凭据（第五十八轮 · P0-3）
#
# 背景：内部接口（/internal/** 等）此前靠「明文共享密钥」把关 —— 请求头里原样带着密钥，
#       于是任何一处访问日志 / 抓包都会留下它，且它没有时效、也分不清是谁在调用。
# 现在：请求头里带的是 HMAC 签名，格式为
#       dd1.<caller>.<tsSeconds>.<nonce>.<签名>
#       签名 = Base64Url(HMAC-SHA256(密钥(caller), "dd1\ncaller\nts\nnonce\nMETHOD\npath"))
#       密钥(caller) = Base64Url(HMAC-SHA256(根密钥, "dd1|key|<caller 小写>"))
# 效果：链路上不再出现密钥；签名只在时间窗内有效；nonce 一次性，同一条请求头用第二次判重放。
#
# 用法：各冒烟脚本在 Call 里把哨兵值 'internal' 换成现场签发的头（见 Call 内注释），
#       业务脚本不需要知道签名细节 —— 换算法时只改这里一处。

# 计算 HMAC-SHA256，返回 Base64Url（无填充）：与 Java 侧 Base64.getUrlEncoder().withoutPadding() 同口径
function Get-InternalHmac {
    param([string]$Key, [string]$Data)

    # 不用 [HMACSHA256]::new()：Windows PowerShell 5.1 与 pwsh 7 都支持 New-Object 这种写法
    $hmac = New-Object System.Security.Cryptography.HMACSHA256
    $hmac.Key = [System.Text.Encoding]::UTF8.GetBytes($Key)
    $raw = $hmac.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($Data))
    return ([Convert]::ToBase64String($raw)).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

# 签发一次内部调用的凭据。每次调用都要重新签 —— nonce 是一次性的，复用会被判重放。
function New-InternalToken {
    param(
        [string]$Method,
        [string]$Path,
        [string]$Caller = 'duoduo-smoke'
    )

    # 根密钥：脚本有 $InternalToken 参数就用它，没有就跟服务端默认保持一致
    $root = 'duoduo-local-dev-internal-token'
    if ($InternalToken) { $root = $InternalToken }

    $ts = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $nonce = [guid]::NewGuid().ToString('N')
    $key = Get-InternalHmac -Key $root -Data "dd1|key|$($Caller.ToLowerInvariant())"
    $sig = Get-InternalHmac -Key $key -Data "dd1`n$Caller`n$ts`n$nonce`n$($Method.ToUpperInvariant())`n$Path"
    return "dd1.$Caller.$ts.$nonce.$sig"
}
