# Shared helpers for Car_homework launch scripts.

function Get-ProjectRoot {
    return (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
}

function Get-DeployConfigPath {
    param([string]$ProjectRoot)
    return Join-Path $ProjectRoot "deploy\infra.local.json"
}

function Ensure-DeployConfig {
    param([string]$ProjectRoot)

    $localPath = Get-DeployConfigPath -ProjectRoot $ProjectRoot
    if (Test-Path $localPath) {
        return $localPath
    }

    $examplePath = Join-Path $ProjectRoot "deploy\infra.example.json"
    if (-not (Test-Path $examplePath)) {
        throw "Missing deploy\infra.example.json"
    }

    Copy-Item $examplePath $localPath
    Write-Host "Created deploy\infra.local.json (localhost defaults)."
    Write-Host "For distributed mode: edit redisHost/mqHost or run scripts\setup-config.ps1"
    return $localPath
}

function Read-DeployConfig {
    param([string]$ProjectRoot)

    $localPath = Ensure-DeployConfig -ProjectRoot $ProjectRoot
    return Get-Content $localPath -Raw -Encoding UTF8 | ConvertFrom-Json
}

function Start-ModuleWindow {
    param(
        [string]$Title,
        [string]$ProjectRoot,
        [string]$ModuleName,
        [string]$MainClass,
        [string]$ExecArgs = ""
    )

    $command = "title $Title && cd /d `"$ProjectRoot`" && .\mvnw.cmd exec:java -pl $ModuleName -Dexec.mainClass=$MainClass"
    if ($ExecArgs -ne "") {
        $command += " `"-Dexec.args=$ExecArgs`""
    }

    Start-Process cmd -ArgumentList '/k', $command
    Write-Host "Started: $Title"
}

function Show-InfraSummary {
    param($Config)

    Write-Host ""
    Write-Host "Redis: $($Config.redisHost):$($Config.redisPort)"
    Write-Host "MQ:    $($Config.mqHost):$($Config.mqPort)"
    Write-Host "Role:  $($Config.role)"
    Write-Host ""
}
