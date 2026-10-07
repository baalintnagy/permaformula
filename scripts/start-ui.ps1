param(
    [string]$ApiOrigin,
    [ValidateRange(1024, 65535)][int]$Port = 5173
)

$ErrorActionPreference = 'Stop'
$taskFrontend = Join-Path (Split-Path -Parent $PSScriptRoot) 'frontend'
$taskPreviousApi = $env:PERMMATH_DEV_API
if ($ApiOrigin) {
    $taskUri = [uri]$ApiOrigin
    if (!$taskUri.IsAbsoluteUri -or $taskUri.Scheme -notin @('http', 'https') -or
            $taskUri.UserInfo -or $taskUri.AbsolutePath -ne '/' -or $taskUri.Query -or $taskUri.Fragment) {
        throw 'ApiOrigin must be an HTTP(S) origin, for example http://localhost:8086.'
    }
    $env:PERMMATH_DEV_API = $ApiOrigin.TrimEnd('/')
}

Push-Location $taskFrontend
try {
    if (!(Test-Path -LiteralPath 'node_modules')) {
        & npm.cmd ci
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
    }
    Write-Host "PermMath Studio: http://127.0.0.1:$Port"
    Write-Host 'Start the renderer and API in Eclipse. The UI uses the API on port 8086 by default.'
    Write-Host 'Override with -ApiOrigin or frontend/.env.local. Ctrl+C stops only the UI.'
    & npm.cmd run dev -- --port $Port --strictPort
    if ($LASTEXITCODE -ne 0) { throw 'Frontend development server stopped with an error.' }
} finally {
    Pop-Location
    $env:PERMMATH_DEV_API = $taskPreviousApi
}
