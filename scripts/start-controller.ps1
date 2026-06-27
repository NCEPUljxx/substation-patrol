# Person A: start Controller (must be last)
. "$PSScriptRoot\_common.ps1"
$projectRoot = Get-ProjectRoot
$config = Read-DeployConfig -ProjectRoot $projectRoot

Write-Host "========== Controller =========="
Show-InfraSummary -Config $config

Start-ModuleWindow -Title "Controller" -ProjectRoot $projectRoot `
    -ModuleName "controller" -MainClass "com.substation.controller.ControllerMain"
