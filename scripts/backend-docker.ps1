param(
    [ValidateSet("refresh", "restart", "logs", "up", "down", "status", "help")]
    [string]$Action = "refresh"
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptDir
$composeArgs = @("compose")
$backendInfraServices = @(
    "mysql",
    "redis",
    "milvus-etcd",
    "milvus-minio",
    "milvus-standalone"
)

function Invoke-Compose {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Args
    )

    & docker @composeArgs @Args
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose command failed: docker $($composeArgs -join ' ') $($Args -join ' ')"
    }
}

function Show-Help {
    Write-Host "Usage: powershell -ExecutionPolicy Bypass -File scripts/backend-docker.ps1 <action>"
    Write-Host ""
    Write-Host "Actions:"
    Write-Host "  refresh  Start infra, rebuild backend image, recreate backend container"
    Write-Host "  restart  Restart backend container without rebuilding"
    Write-Host "  logs     Follow backend logs"
    Write-Host "  up       Start infra and backend without forcing rebuild"
    Write-Host "  down     Stop backend container only"
    Write-Host "  status   Show compose status"
    Write-Host "  help     Show this help"
}

Push-Location $projectRoot
try {
    switch ($Action) {
        "refresh" {
            Write-Host "[backend-docker] starting infra services..."
            $infraUpArgs = @("up", "-d") + $backendInfraServices
            Invoke-Compose -Args $infraUpArgs

            Write-Host "[backend-docker] rebuilding and restarting backend..."
            Invoke-Compose -Args @("up", "-d", "--build", "backend")

            Write-Host "[backend-docker] backend is refreshing. tail logs with:"
            Write-Host "  npm run backend:docker:logs"
        }
        "restart" {
            Write-Host "[backend-docker] restarting backend container..."
            Invoke-Compose -Args @("restart", "backend")
        }
        "logs" {
            Invoke-Compose -Args @("logs", "-f", "backend")
        }
        "up" {
            Write-Host "[backend-docker] starting infra services..."
            $infraUpArgs = @("up", "-d") + $backendInfraServices
            Invoke-Compose -Args $infraUpArgs

            Write-Host "[backend-docker] starting backend container..."
            Invoke-Compose -Args @("up", "-d", "backend")
        }
        "down" {
            Write-Host "[backend-docker] stopping backend container..."
            Invoke-Compose -Args @("stop", "backend")
        }
        "status" {
            Invoke-Compose -Args @("ps")
        }
        "help" {
            Show-Help
        }
    }
}
finally {
    Pop-Location
}
