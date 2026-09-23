# A6 · 从各业务服务的 /v3/api-docs 导出 OpenAPI JSON，并生成端点摘要供与 docs/agent/04 比对。
# 用法（需先重建容器使 knife4j 生效）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\export-openapi.ps1
# 产物：
#   docs/openapi/<service>.json      —— 各服务完整 OpenAPI 3 契约
#   docs/openapi/endpoints.md       —— 按「服务 → 方法 路径」排序的端点清单（人读，逐条比对 04 篇用）
# 注意：只导 5 个业务服务（knife4j 是 MVC 生态，网关 WebFlux 没有 /v3/api-docs）。

param(
    [string]$OutDir = (Join-Path $PSScriptRoot '..\docs\openapi')
)

$ErrorActionPreference = 'Stop'

# 与 docker-compose.yml / 各 application.yml 一一对应
$Services = @(
    @{ Name = 'user';       Port = 8081 },
    @{ Name = 'product';    Port = 8082 },
    @{ Name = 'trade';      Port = 8083 },
    @{ Name = 'fulfilment'; Port = 8084 },
    @{ Name = 'marketing';  Port = 8085 }
)

if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir | Out-Null }

$summary = New-Object System.Collections.Generic.List[string]
$summary.Add('# 端点清单（由 scripts/export-openapi.ps1 从 /v3/api-docs 自动生成，勿手改）')
$summary.Add('')
$summary.Add('> 比对基准：`docs/agent/04-接口契约全集.md`。导出时间见各 json 的 mtime。')
$summary.Add('')

$totalEndpoints = 0
foreach ($svc in $Services) {
    $url = "http://127.0.0.1:$($svc.Port)/v3/api-docs"
    Write-Host "> $($svc.Name) ← $url"
    try {
        $resp = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 15
    } catch {
        Write-Host "  [FAIL] 无法访问 $url —— 先确认容器已用带 knife4j 的镜像重建（T-41：clean install + build + force-recreate）"
        throw
    }
    $json = $resp.Content | ConvertFrom-Json

    # 落盘（统一缩进，diff 友好）
    $pretty = $json | ConvertTo-Json -Depth 40
    $file = Join-Path $OutDir ("$($svc.Name).json")
    [System.IO.File]::WriteAllText($file, $pretty, (New-Object System.Text.UTF8Encoding($true)))

    # 摘要
    $summary.Add("## $($svc.Name) （:$($svc.Port)）")
    $summary.Add('')
    $count = 0
    $paths = $json.paths.PSObject.Properties | Sort-Object Name
    foreach ($p in $paths) {
        $methods = $p.Value.PSObject.Properties.Name | Where-Object { $_ -in @('get','post','put','delete','patch') } | Sort-Object
        foreach ($m in $methods) {
            $summary.Add(('- `{0} {1}`' -f $m.ToUpper(), $p.Name))
            $count++
        }
    }
    $summary.Add('')
    $summary.Add("小计：**$count** 个端点。")
    $summary.Add('')
    $totalEndpoints += $count
    Write-Host ("  [OK] {0} 个端点 → {1}" -f $count, $file)
}

$summary.Add(("合计：**{0}** 个端点（5 个业务服务）。" -f $totalEndpoints))
$summaryFile = Join-Path $OutDir 'endpoints.md'
[System.IO.File]::WriteAllLines($summaryFile, $summary, (New-Object System.Text.UTF8Encoding($true)))
Write-Host ""
Write-Host ("导出完成：{0} 个端点，摘要 → {1}" -f $totalEndpoints, $summaryFile)
