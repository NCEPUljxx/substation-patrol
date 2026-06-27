# Person B: car processes (IDs from deploy/infra.local.json "cars" array)
. "$PSScriptRoot\_common.ps1"
$projectRoot = Get-ProjectRoot
$config = Read-DeployConfig -ProjectRoot $projectRoot

Write-Host "========== Person B / Cars =========="
Show-InfraSummary -Config $config

if ($config.role -ne "car") {
    Write-Warning "role=$($config.role); recommended: setup-config.ps1 -Role car"
}

$carIds = @($config.cars)
if ($carIds.Count -eq 0) {
    $carIds = @("Car001", "Car002", "Car003")
}

foreach ($carId in $carIds) {
    Start-ModuleWindow -Title $carId -ProjectRoot $projectRoot `
        -ModuleName "car" -MainClass "com.substation.car.CarMain" -ExecArgs $carId
    Start-Sleep -Milliseconds 500
}

Write-Host "$($carIds.Count) car process(es) started."
