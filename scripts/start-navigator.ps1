# Extra Navigator instances (any machine; scales via RabbitMQ competing consumers)
param(
    [int]$Count = 1
)

. "$PSScriptRoot\_common.ps1"
$projectRoot = Get-ProjectRoot
$config = Read-DeployConfig -ProjectRoot $projectRoot

Write-Host "========== Navigator x$Count =========="
Show-InfraSummary -Config $config

for ($i = 1; $i -le $Count; $i++) {
    $title = if ($Count -eq 1) { "Navigator" } else { "Navigator$i" }
    Start-ModuleWindow -Title $title -ProjectRoot $projectRoot `
        -ModuleName "navigator" -MainClass "com.substation.navigator.NavigatorMain"
    Start-Sleep -Milliseconds 300
}

Write-Host "$Count Navigator instance(s) started."
