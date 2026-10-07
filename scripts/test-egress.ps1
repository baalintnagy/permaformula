param([string]$ProjectName = 'permmath-egress-smoke', [string]$BaseUrl = 'http://localhost:5017')
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
Push-Location $taskRoot
$dnsCreated = $false
$proxyCreated = $false
try {
    if ($ProjectName -notmatch '^[a-z0-9][a-z0-9-]+$') { throw 'Invalid Compose project name' }
    $privateNetwork = "${ProjectName}_api-private"
    $network = (docker network inspect $privateNetwork | ConvertFrom-Json)[0]
    if (!$network.Internal -or $network.Options.'com.docker.network.bridge.gateway_mode_ipv4' -ne 'isolated') {
        throw 'API network must be internal with isolated gateway mode'
    }
    $apiId = docker compose -p $ProjectName ps -q permmath
    $api = (docker inspect $apiId | ConvertFrom-Json)[0]
    if (@($api.NetworkSettings.Networks.PSObject.Properties).Count -ne 1) { throw 'API has an extra network' }
    $probeImage = 'alpine:3.23@sha256:85fe1e81d6758c208f3e1eed4338a1997e19d4be002d4dd32d3100c9a8c010a0'
    $probePath = Join-Path $taskRoot 'docker/egress/probe.sh'
    function Invoke-Probe([string[]]$ProbeArguments) {
        $output = docker run --rm --network $privateNetwork --cap-drop ALL --read-only --user 65534 `
            --mount "type=bind,source=$probePath,target=/probe.sh,readonly" $probeImage sh /probe.sh @ProbeArguments
        if ($LASTEXITCODE -ne 0) { throw "Network probe failed: $ProbeArguments" }
        return ($output -join "`n")
    }
    Write-Output (Invoke-Probe -ProbeArguments @('direct'))
    foreach ($target in @('127.0.0.1:443', '169.254.169.254:443', '10.0.0.1:443', 'localhost:443',
            'permmath:443', '[::1]:443', '[fc00::1]:443', '[64:ff9b::7f00:1]:443', '[2002:7f00:1::1]:443',
            '[2001:db8::1]:443', 'cdnjs.cloudflare.com:80')) {
        $status = Invoke-Probe -ProbeArguments @('tunnel', 'stylesheet-egress', $target)
        if ($status -notmatch '^HTTP/1.1 403 ') { throw "Proxy did not deny ${target}: $status" }
    }
    $status = Invoke-Probe -ProbeArguments @('tunnel', 'stylesheet-egress', 'cdnjs.cloudflare.com:443')
    if ($status -notmatch '^HTTP/1.1 200 ') { throw "Public HTTPS tunnel failed: $status" }
    Write-Output 'Public HTTPS allowed; internal/metadata/transition addresses and non-443 ports denied'

    $dnsName = "${ProjectName}-dns-fixture"
    $proxyName = "${ProjectName}-dns-proxy-fixture"
    foreach ($name in @($dnsName, $proxyName)) {
        $existing = docker ps -aq --filter "name=^/$name$"
        if ($existing) { throw "Test fixture name already exists: $name" }
    }
    docker build -q -f docker/egress/Dockerfile --target test-dns -t permmath-egress-test-dns . | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'DNS fixture build failed' }
    docker run -d --name $dnsName --network $privateNetwork --cap-drop ALL --read-only --tmpfs /tmp permmath-egress-test-dns | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'DNS fixture startup failed' }
    $dnsCreated = $true
    $dnsIp = docker inspect $dnsName --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}'
    $egressId = docker compose -p $ProjectName ps -q stylesheet-egress
    $egressImage = docker inspect $egressId --format '{{.Image}}'
    $shell = 'cat /etc/squid/squid.conf > /tmp/test.conf; echo "dns_nameservers $1" >> /tmp/test.conf; exec squid -N -f /tmp/test.conf'
    docker run -d --name $proxyName --network $privateNetwork --cap-drop ALL --read-only --tmpfs /tmp `
        --memory 128m --ulimit nofile=8192:8192 --entrypoint sh $egressImage -c $shell sh $dnsIp | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'DNS proxy fixture startup failed' }
    $proxyCreated = $true
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        docker exec $proxyName nc -z 127.0.0.1 3128
        if ($LASTEXITCODE -eq 0) { break }
        Start-Sleep -Milliseconds 250
    }
    foreach ($target in @('mixed.test:443', 'private.test:443')) {
        $status = Invoke-Probe -ProbeArguments @('tunnel', $proxyName, $target)
        if ($status -notmatch '^HTTP/1.1 403 ') { throw "DNS destination bypass: $status" }
    }
    Write-Output 'Mixed public/private DNS and private DNS destinations denied by Squid'

    $formula = Invoke-WebRequest "$BaseUrl/img?formula=x%5E2" -TimeoutSec 40
    $formulaSvg = if ($formula.Content -is [string]) { $formula.Content } else { [Text.Encoding]::UTF8.GetString($formula.Content) }
    if ($formula.StatusCode -ne 200 -or $formulaSvg -notmatch '<svg') { throw 'Formula ingress failed' }
    $html = [uri]::EscapeDataString('<p class="text-danger">Egress verified `x^2`</p>')
    $css = [uri]::EscapeDataString('https://cdn.jsdelivr.net/npm/bootstrap@5.3.8/dist/css/bootstrap.min.css')
    $url = (Invoke-WebRequest "$BaseUrl/url?formula=$html&imageWidth=500&styleRefs=$css" -TimeoutSec 10).Content
    $image = Invoke-WebRequest $url -TimeoutSec 60
    $svg = if ($image.Content -is [string]) { $image.Content } else { [Text.Encoding]::UTF8.GetString($image.Content) }
    if ($svg -notmatch 'Egress verified' -or $svg -notmatch 'rgb\(220,\s*53,\s*69\)') { throw 'External Bootstrap styling failed' }
    if ($image.Headers.'Content-Security-Policy' -notmatch 'sandbox') { throw 'SVG headers lost at ingress' }
    Write-Output 'Formula and Bootstrap-styled HTML render through nginx and the required egress proxy'
} finally {
    if ($proxyCreated) { docker rm -f $proxyName | Out-Null }
    if ($dnsCreated) { docker rm -f $dnsName | Out-Null }
    Pop-Location
}
