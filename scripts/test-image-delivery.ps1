param([string]$ProjectName = 'permmath-delivery-smoke',
      [string]$BaseUrl = 'http://localhost:5017', [string]$ImageUrl = 'http://localhost:5018')
$ErrorActionPreference = 'Stop'
if ($ProjectName -notmatch '^[a-z0-9][a-z0-9_-]*$') { throw 'Invalid Compose project name' }
$taskRoot = Split-Path -Parent $PSScriptRoot
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
try {
    $html = [uri]::EscapeDataString('<p>Isolated image `x^2`</p>')
    $response = Get-Response "$BaseUrl/url?formula=$html"
    Assert-Status $response 200
    $url = Text $response
    if (!$url.StartsWith("$ImageUrl/image?ubcq=")) { throw "Permalink does not use image origin: $url" }
    $credentials = @{'Cookie'='app_session=sentinel'; 'Authorization'='Bearer sentinel';
            'Proxy-Authorization'='Basic sentinel'; 'X-Permmath-Image-Delivery'='forged'}
    Assert-Svg (Get-Response $url $credentials)
    $redirect = Get-Response "$BaseUrl/img?formula=x%5E2" $credentials
    Assert-Status $redirect 307
    if ((Header $redirect 'Location') -ne "$ImageUrl/img?formula=x%5E2") { throw 'Legacy redirect lost origin/query' }
    Assert-Svg (Get-Response "$ImageUrl/img?formula=x%5E2" $credentials)
    foreach ($path in @('/url?formula=x', '/url-encode?text=x', '/decode?ubcq=bad', '/swagger-ui/index.html', '/v3/api-docs', '/img;ignored?formula=x', '/')) {
        Assert-Status (Get-Response "$ImageUrl$path") 404
    }
    Assert-Status (Get-Response "$BaseUrl/img;ignored?formula=x" $credentials) 404
    Assert-Status (Get-Response "$ImageUrl/img?formula=x" @{} 'POST') 403
    $cors = Get-Response "$ImageUrl/img?formula=x" @{'Origin'=$BaseUrl} 'OPTIONS'
    Assert-Status $cors 204
    if ((Header $cors 'Access-Control-Allow-Origin') -ne $BaseUrl -or $cors.Headers.Contains('Access-Control-Allow-Credentials')) {
        throw 'Image CORS must allow only anonymous reads from the configured API origin'
    }
    Assert-Status (Get-Response "$BaseUrl/swagger-ui/index.html") 200
    $builder = Get-Response "$BaseUrl/"
    Assert-Status $builder 200
    if ((Text $builder) -notmatch 'PermMath' -or $builder.Content.Headers.ContentType.MediaType -ne 'text/html') {
        throw 'API origin must serve the Builder UI'
    }
    Assert-Status (Get-Response "$BaseUrl/mathjax/tex-svg.js") 200
    Assert-Status (Get-Response "$ImageUrl/mathjax/tex-svg.js") 404
    Assert-Status (Get-Response "$BaseUrl/decode?ubcq=bad") 404
    $encoded = Get-Response "$BaseUrl/url-encode?text=x%20%2B%20%25%20%CE%B1"
    Assert-Status $encoded 200
    if ((Text $encoded) -ne 'x+%2B+%25+%CE%B1' -or $encoded.Content.Headers.ContentType.MediaType -ne 'text/plain' -or
            (Header $encoded 'Cache-Control') -ne 'no-store' -or (Header $encoded 'X-Content-Type-Options') -ne 'nosniff') {
        throw 'URL encoding or plain-text security headers failed'
    }
    Write-Output 'Public /url returns image-origin links; API redirects preserve queries; image routes and SVG headers/CORS are restricted.'

    $image = (docker inspect "${ProjectName}-ingress-1" | ConvertFrom-Json)[0].Config.Image
    $probe = Join-Path $taskRoot 'docker/ingress/delivery-probe.sh'
    foreach ($path in @('/img?formula=x', '/img;ignored?formula=x')) {
        $direct = docker run --rm --network "${ProjectName}_api-private" --read-only --cap-drop ALL `
            --security-opt no-new-privileges:true --mount "type=bind,source=$probe,target=/probe.sh,readonly" `
            --entrypoint sh $image /probe.sh $path
        if ($LASTEXITCODE -ne 0 -or ($direct -join "`n") -notmatch '^HTTP/1.1 307 ') {
            throw 'Private API accepted a forged image-delivery marker from an untrusted peer'
        }
    }
    Write-Output 'Private API redirects forged delivery markers, including matrix-parameter image paths.'

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
        -e PERMMATH_DEPLOYMENT_PROFILE=separate-origins -e PERMMATH_HOST=http://api.example.test -e PERMMATH_IMAGE_HOST=http://images.example.test $image | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot start header ingress' }
    $ingressCreated = $true
    $ports = (docker inspect $ingress | ConvertFrom-Json)[0].NetworkSettings.Ports
    if (!$ports.'8080/tcp'[0].HostPort -or !$ports.'8081/tcp'[0].HostPort) { throw 'Fixture listeners were not published' }
    $apiFixture = "http://127.0.0.1:$($ports.'8080/tcp'[0].HostPort)"
    $imageFixture = "http://127.0.0.1:$($ports.'8081/tcp'[0].HostPort)"
    $api = Get-Response "$apiFixture/url" $credentials
    Assert-Status $api 200
    if ((Text $api) -notmatch '^app_session=sentinel\|Bearer sentinel\|' -or (Text $api) -notmatch '\|$') {
        throw 'API credentials must survive; spoofed image-delivery marker must be removed'
    }
    if (!$api.Headers.Contains('Set-Cookie')) { throw 'Echo fixture must set its control cookie' }
    foreach ($path in @('/img', '/image')) {
        $imageResponse = Get-Response "$imageFixture$path" $credentials
        Assert-Status $imageResponse 200
        if ((Text $imageResponse) -ne '|||1' -or $imageResponse.Headers.Contains('Set-Cookie')) {
            throw 'Image ingress forwarded credentials, accepted a forged marker, or leaked Set-Cookie'
        }
        if ((Header $imageResponse 'Content-Security-Policy') -notmatch 'sandbox') { throw 'Proxy dropped CSP' }
    }
    Write-Output 'Native upstream fixture confirms Cookie/Authorization/Proxy-Authorization stripping and Set-Cookie suppression on both image endpoints.'
} finally {
    if ($ingressCreated) { docker rm -f $ingress | Out-Null }
    if ($upstreamCreated) { docker rm -f $upstream | Out-Null }
    if ($networkCreated) { docker network rm $network | Out-Null }
    $client.Dispose()
    $handler.Dispose()
}
