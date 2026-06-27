# Person C: TaskConfigurator, Navigator, TargetPlanner, StrategySupervisor
. "$PSScriptRoot\_common.ps1"
$projectRoot = Get-ProjectRoot
$config = Read-DeployConfig -ProjectRoot $projectRoot

Write-Host "========== Person C / Planner =========="
Show-InfraSummary -Config $config

if ($config.role -ne "planner") {
    Write-Warning "role=$($config.role); recommended: setup-config.ps1 -Role planner"
}

Start-ModuleWindow -Title "TaskConfigurator" -ProjectRoot $projectRoot `
    -ModuleName "task-configurator" -MainClass "com.substation.taskconfigurator.TaskConfiguratorMain"
Start-Sleep -Seconds 3

Start-ModuleWindow -Title "Navigator" -ProjectRoot $projectRoot `
    -ModuleName "navigator" -MainClass "com.substation.navigator.NavigatorMain"
Start-ModuleWindow -Title "TargetPlanner" -ProjectRoot $projectRoot `
    -ModuleName "target-planner" -MainClass "com.substation.targetplanner.TargetPlannerMain"
Start-ModuleWindow -Title "StrategySupervisor" -ProjectRoot $projectRoot `
    -ModuleName "strategy-supervisor" -MainClass "com.substation.strategysupervisor.StrategySupervisorMain"

Write-Host "4 planner modules started (config from deploy\infra.local.json)."
