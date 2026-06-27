# Person D: Display (SQL Server must be running for login)
. "$PSScriptRoot\_common.ps1"
$projectRoot = Get-ProjectRoot
$config = Read-DeployConfig -ProjectRoot $projectRoot

Write-Host "========== Person D / Display =========="
Show-InfraSummary -Config $config

if ($config.role -ne "display") {
    Write-Warning "role=$($config.role); recommended: setup-config.ps1 -Role display"
}

Start-ModuleWindow -Title "Display" -ProjectRoot $projectRoot `
    -ModuleName "display" -MainClass "com.substation.display.DisplayMain"

$httpPort = if ($config.displayHttpPort) { $config.displayHttpPort } else { 8887 }
Write-Host "Display started. Local: http://localhost:$httpPort"
Write-Host "Remote viewers: http://$($config.displayHost):$httpPort"
