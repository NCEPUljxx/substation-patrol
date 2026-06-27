# First-time setup: writes deploy/infra.local.json (IP entered once, reused on every start)
param(
    [switch]$Remote,
    [string]$InfraHost = "",
    [ValidateSet("infra", "planner", "car", "display")]
    [string]$Role = "",
    [string]$DisplayHost = ""
)

. "$PSScriptRoot\_common.ps1"
$projectRoot = Get-ProjectRoot
$localPath = Get-DeployConfigPath -ProjectRoot $projectRoot

$isRemoteRole = $Role -in @("planner", "car", "display")
if ($isRemoteRole) {
    $Remote = $true
}

if ($Role -eq "infra") {
    $Remote = $false
}

if ($Remote -and $InfraHost -eq "" -and (Test-Path $localPath)) {
    $existing = Get-Content $localPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($existing.redisHost -and $existing.redisHost -ne "localhost") {
        Write-Host "Existing redisHost=$($existing.redisHost)"
        $reuse = Read-Host "Press Enter to keep, or type new Person A IP"
        if ($reuse -ne "") {
            $InfraHost = $reuse.Trim()
        } else {
            $InfraHost = $existing.redisHost
        }
    }
}

if ($Remote -and $InfraHost -eq "") {
    $InfraHost = (Read-Host "Person A 局域网 IP (saved to deploy/infra.local.json)").Trim()
}

if ($Remote -and $InfraHost -eq "") {
    Write-Error "Infra IP is required for remote roles"
    exit 1
}

if ($Role -eq "display" -and $DisplayHost -eq "") {
    $displayInput = Read-Host "本机局域网 IP (供其他人浏览器访问，回车自动检测)"
    if ($displayInput -ne "") {
        $DisplayHost = $displayInput.Trim()
    } else {
        # 自动检测本机局域网 IP
        $detected = (Get-NetIPAddress -AddressFamily IPv4 -PrefixOrigin Manual,Dhcp |
            Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" } |
            Select-Object -First 1).IPAddress
        if ($detected) {
            Write-Host "Detected: $detected"
            $DisplayHost = $detected
        } else {
            $DisplayHost = (Read-Host "无法自动检测，请手动输入本机 IP").Trim()
        }
    }
}

$templateName = if ($Remote) { "infra.remote.example.json" } else { "infra.example.json" }
$templatePath = Join-Path $projectRoot "deploy\$templateName"

if (-not (Test-Path $templatePath)) {
    Write-Error "Missing template: $templatePath"
    exit 1
}

Copy-Item $templatePath $localPath -Force
$config = Get-Content $localPath -Raw -Encoding UTF8 | ConvertFrom-Json

if ($Remote) {
    $config.redisHost = $InfraHost
    $config.mqHost = $InfraHost
}

if ($Role -ne "") {
    $config.role = $Role
}

if ($DisplayHost -ne "") {
    $config.displayHost = $DisplayHost
}

$config | ConvertTo-Json -Depth 4 | Set-Content $localPath -Encoding UTF8

Write-Host ""
Write-Host "Written: $localPath"
Show-InfraSummary -Config $config
Write-Host "Next: run the matching start-*.ps1 script. Modules read this file automatically."
