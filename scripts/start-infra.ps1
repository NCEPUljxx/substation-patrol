# Person A: Docker (Redis+MQ); start Controller last with start-controller.ps1
param(
    [switch]$SkipDocker,
    [switch]$StartControllerNow
)

. "$PSScriptRoot\_common.ps1"
$projectRoot = Get-ProjectRoot
$config = Read-DeployConfig -ProjectRoot $projectRoot

Write-Host "========== Person A / Infra =========="
Show-InfraSummary -Config $config

if (-not $SkipDocker) {
    Set-Location $projectRoot

    $redisPort = $config.redisPort
    $mqPort = $config.mqPort
    $redisOk = (Test-NetConnection -ComputerName $config.redisHost -Port $redisPort -WarningAction SilentlyContinue).TcpTestSucceeded
    $mqOk = (Test-NetConnection -ComputerName $config.mqHost -Port $mqPort -WarningAction SilentlyContinue).TcpTestSucceeded

    if ($redisOk -and $mqOk) {
        Write-Host "Redis ($redisPort) and RabbitMQ ($mqPort) are already running -- skipping docker compose."
    } else {
        Write-Host "Starting Docker (Redis + RabbitMQ)..."
        docker compose up -d
        docker ps
    }
}

if ($StartControllerNow) {
    Start-ModuleWindow -Title "Controller" -ProjectRoot $projectRoot `
        -ModuleName "controller" -MainClass "com.substation.controller.ControllerMain"
    Write-Host "Controller started."
} else {
    Write-Host "After B/C/D are ready, run:"
    Write-Host "  .\scripts\start-controller.ps1"
}
