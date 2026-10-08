param([string]$BaseUrl = 'http://localhost:5017')
$ErrorActionPreference = 'Stop'
# Requires a fresh deployment with docker/rate-limit/smoke.override.yaml.
function Get-Response([string]$Path, [hashtable]$Headers = @{}) {
    return Invoke-WebRequest -Uri "$BaseUrl$Path" -Headers $Headers -SkipHttpErrorCheck -TimeoutSec 65
}
function Assert-Status($Response, [int]$Expected) {
    if ([int]$Response.StatusCode -ne $Expected) { throw "Expected HTTP $Expected; got $($Response.StatusCode)" }
}
function Assert-Limited($Response) {
    Assert-Status $Response 429
    $retry = ($Response.Headers['Retry-After'] -join '')
    if ($retry -notmatch '^\d+$' -or [long]$retry -lt 1) { throw 'Missing positive Retry-After' }
    if (($Response.Headers['Cache-Control'] -join '') -ne 'no-store') { throw '429 must not be cached' }
}

$ready = $false
for ($attempt = 0; $attempt -lt 60; $attempt++) {
    try {
        $response = Get-Response '/url?formula=rate-ready'
        if ($response.StatusCode -eq 200) { $ready = $true; break }
        if ($response.StatusCode -eq 429 -or $response.StatusCode -eq 400) {
            throw 'Use a fresh test deployment; check trusted ingress configuration'
        }
    } catch [System.Net.Http.HttpRequestException] { }
    Start-Sleep -Seconds 1
}
if (!$ready) { throw 'API did not become ready through ingress' }

$formula = [Uri]::EscapeDataString("rate_$(Get-Random -Maximum 1000000)")
$spoof = @{'X-Forwarded-For'='198.51.100.1'; 'Forwarded'='for=198.51.100.2'; 'X-Real-IP'='198.51.100.3'}
Assert-Status (Get-Response "/exp?formula=$formula" $spoof) 200
Assert-Limited (Get-Response "/exp?formula=${formula}%2B1" @{'X-Forwarded-For'='198.51.100.4'})
$cached = Get-Response "/exp?formula=$formula" @{'X-Forwarded-For'='198.51.100.5'}
Assert-Status $cached 200
if (!$cached.Headers.ContainsKey('Content-Security-Policy')) { throw 'Cached SVG lost its CSP' }
Assert-Status (Get-Response '/url?formula=x' @{'X-Forwarded-For'='198.51.100.6'}) 200
Assert-Status (Get-Response '/url?formula=y' @{'X-Forwarded-For'='198.51.100.7'}) 200
Assert-Limited (Get-Response '/url-encode?text=x' @{'X-Forwarded-For'='198.51.100.8'})
Assert-Limited (Get-Response '/url?formula=z' @{'X-Forwarded-For'='198.51.100.9'})
Write-Output 'nginx overwrites spoofed identities; request/render quotas return 429 with Retry-After; cached SVG remains available after render quota exhaustion.'
