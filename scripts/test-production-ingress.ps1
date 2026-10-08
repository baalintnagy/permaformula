param([ValidateSet('single-host', 'separate-origins')][string]$Profile = 'single-host',
      [string]$Image = 'permmath-ingress-security')
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$ProjectName = "permmath-ingress-$Profile-check"
$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect = $false
$handler.UseCookies = $false
$handler.UseProxy = $false
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(65)
function Get-Response([string]$Uri, [hashtable]$Headers = @{}, [string]$Method = 'GET') {
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), $Uri)
    foreach ($name in $Headers.Keys) { $null = $request.Headers.TryAddWithoutValidation($name, [string]$Headers[$name]) }
    try { return $client.SendAsync($request).GetAwaiter().GetResult() }
    finally { $request.Dispose() }
}
function Assert-Status($Response, [int]$Expected) {
    if ([int]$Response.StatusCode -ne $Expected) { throw "Expected HTTP $Expected; got $($Response.StatusCode)" }
}
function Text($Response) { return $Response.Content.ReadAsStringAsync().GetAwaiter().GetResult() }
function Header($Response, [string]$Name) {
    if ($Response.Headers.Contains($Name)) { return [string]::Join(', ', $Response.Headers.GetValues($Name)) }
    return ''
}
function Assert-Svg($Response) {
    Assert-Status $Response 200
    if ($Response.Content.Headers.ContentType.MediaType -ne 'image/svg+xml' -or (Text $Response) -notmatch '<svg') { throw 'Expected SVG' }
    if ((Header $Response 'Content-Security-Policy') -notmatch 'sandbox' -or
            (Header $Response 'X-Content-Type-Options') -ne 'nosniff' -or
            (Header $Response 'Referrer-Policy') -ne 'no-referrer') { throw 'SVG security headers lost' }
    if ($Response.Headers.Contains('Set-Cookie') -or $Response.Headers.Contains('Access-Control-Allow-Credentials')) {
        throw 'Image origin must not set cookies or enable credentialed CORS'
    }
}

$network = "${ProjectName}_header-fixture"
$upstream = "${ProjectName}-header-upstream"
$ingress = "${ProjectName}-header-ingress"
$networkCreated = $false; $upstreamCreated = $false; $ingressCreated = $false
$credentials = @{'Cookie'='app_session=sentinel'; 'Authorization'='Bearer sentinel';
    'Proxy-Authorization'='Basic sentinel'; 'X-Permmath-Image-Delivery'='forged'}
try {
    # Exercise the production ingress image against a native nginx echo fixture.
    # This proves what reaches the upstream, rather than merely checking config text.
    foreach ($name in @($upstream, $ingress)) {
        if (docker ps -aq --filter "name=^/$name`$" ) { throw "Fixture container already exists: $name" }
    }
    if (docker network ls -q --filter "name=^$network`$" ) { throw "Fixture network already exists: $network" }
    docker network create $network | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot create header fixture network' }
    $networkCreated = $true
    $config = Join-Path $taskRoot 'docker/ingress/test-upstream.conf'
    docker run -d --name $upstream --network $network --network-alias permmath --read-only --cap-drop ALL `
        --security-opt no-new-privileges:true --tmpfs /tmp:rw,nosuid,nodev,noexec,size=16m,mode=1777 `
        --mount "type=bind,source=$config,target=/fixture.conf,readonly" --entrypoint nginx $image `
        -c /fixture.conf -e stderr -g 'daemon off;' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot start header upstream' }
    $upstreamCreated = $true
    docker run -d --name $ingress --network $network --read-only --cap-drop ALL --security-opt no-new-privileges:true `
        --tmpfs /tmp:rw,nosuid,nodev,noexec,size=16m,mode=1777 -p 127.0.0.1::8080 -p 127.0.0.1::8081 `
        -e "PERMMATH_DEPLOYMENT_PROFILE=$Profile" -e PERMMATH_HOST=http://api.example.test -e PERMMATH_IMAGE_HOST=http://images.example.test $image | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot start header ingress' }
    $ingressCreated = $true
    $ports = (docker inspect $ingress | ConvertFrom-Json)[0].NetworkSettings.Ports
    if (!$ports.'8080/tcp'[0].HostPort) { throw 'Fixture listeners were not published' }
    $apiFixture = "http://127.0.0.1:$($ports.'8080/tcp'[0].HostPort)"
    $imageFixture = "http://127.0.0.1:$($ports.'8081/tcp'[0].HostPort)"
    if ($Profile -eq 'single-host') { $imageFixture = $apiFixture }
    $api = Get-Response "$apiFixture/url" $credentials
    Assert-Status $api 200
    if ((Text $api) -notmatch '^app_session=sentinel\|Bearer sentinel\|' -or (Text $api) -notmatch '\|$') {
        throw 'API credentials must survive; spoofed image-delivery marker must be removed'
    }
    if (!$api.Headers.Contains('Set-Cookie')) { throw 'Echo fixture must set its control cookie' }
    foreach ($path in @('/exp', '/html')) {
        Assert-Status (Get-Response "$imageFixture$path" @{} 'POST') 403
        Assert-Status (Get-Response "$imageFixture$path" @{} 'OPTIONS') 204
        $imageResponse = Get-Response "$imageFixture$path" $credentials
        Assert-Status $imageResponse 200
        if ((Text $imageResponse) -ne '|||1' -or $imageResponse.Headers.Contains('Set-Cookie')) {
            throw 'Image ingress forwarded credentials, accepted a forged marker, or leaked Set-Cookie'
        }
        if ((Header $imageResponse 'Content-Security-Policy') -notmatch 'sandbox') { throw 'Proxy dropped CSP' }
    }
    Assert-Status (Get-Response "$imageFixture/exp;ignored?formula=x" $credentials) 404
    if ($Profile -eq 'separate-origins') {
        Assert-Status (Get-Response "$apiFixture/exp?formula=x" $credentials) 307
        Assert-Status (Get-Response "$imageFixture/url") 404
    }
    Write-Output 'Native upstream fixture confirms Cookie/Authorization/Proxy-Authorization stripping and Set-Cookie suppression on both image endpoints.'
} finally {
    if ($ingressCreated) { docker rm -f $ingress | Out-Null }
    if ($upstreamCreated) { docker rm -f $upstream | Out-Null }
    if ($networkCreated) { docker network rm $network | Out-Null }
    $client.Dispose()
    $handler.Dispose()
}
