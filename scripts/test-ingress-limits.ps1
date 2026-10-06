param([string]$Image = 'permmath-ingress:latest')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$suffix = [guid]::NewGuid().ToString('N').Substring(0, 10)
$network = "permmath-ingress-test-$suffix"
$upstream = "$network-upstream"; $ingress = "$network-proxy"
$createdNetwork = $false; $createdUpstream = $false; $createdIngress = $false
$sockets = [System.Collections.Generic.List[System.Net.Sockets.TcpClient]]::new()
function Connect([int]$Port) {
    $socket = [System.Net.Sockets.TcpClient]::new()
    $socket.ReceiveBufferSize = 1024
    $socket.Connect('127.0.0.1', $Port)
    $socket.GetStream().ReadTimeout = 10000
    $sockets.Add($socket)
    return $socket
}
function Send($Socket, [string]$Text) {
    $bytes = [System.Text.Encoding]::ASCII.GetBytes($Text)
    $Socket.GetStream().Write($bytes, 0, $bytes.Length)
}
function Read-Headers($Socket) {
    $stream = $Socket.GetStream()
    $text = [System.Text.StringBuilder]::new()
    while ($text.Length -lt 32768) {
        $value = $stream.ReadByte()
        if ($value -lt 0) { break }
        $null = $text.Append([char]$value)
        if ($text.ToString().EndsWith("`r`n`r`n")) { break }
    }
    return $text.ToString()
}
function Assert-Match([string]$Actual, [string]$Pattern, [string]$Reason) {
    if ($Actual -notmatch $Pattern) { throw "$Reason`: $Actual" }
}
try {
    docker network create $network | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Network creation failed' }
    $createdNetwork = $true
    $config = Join-Path $root 'docker/ingress/test-limits-upstream.conf'
    docker run -d --name $upstream --network $network --network-alias permmath --read-only --cap-drop ALL `
        --security-opt no-new-privileges:true --tmpfs /tmp:rw,nosuid,nodev,noexec,size=32m,mode=1777 `
        --mount "type=bind,source=$config,target=/fixture.conf,readonly" --entrypoint nginx $Image `
        -c /fixture.conf -e stderr -g 'daemon off;' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Upstream creation failed' }
    $createdUpstream = $true
    docker exec $upstream truncate -s 16777216 /tmp/large.bin
    if ($LASTEXITCODE -ne 0) { throw 'Large-response fixture creation failed' }
    docker run -d --name $ingress --network $network --read-only --cap-drop ALL --security-opt no-new-privileges:true `
        --tmpfs /tmp:rw,nosuid,nodev,noexec,size=32m,mode=1777 -p 127.0.0.1::8080 -p 127.0.0.1::8081 `
        -e PERMMATH_HOST=http://api.example.test -e PERMMATH_IMAGE_HOST=http://images.example.test `
        -e PERMMATH_INGRESS_CLIENT_CONNECTIONS=2 -e PERMMATH_INGRESS_TOTAL_CONNECTIONS=3 `
        -e PERMMATH_INGRESS_HEADER_TIMEOUT_SECONDS=2 -e PERMMATH_INGRESS_BODY_TIMEOUT_SECONDS=3 `
        -e PERMMATH_INGRESS_SEND_TIMEOUT_SECONDS=2 -e PERMMATH_INGRESS_KEEPALIVE_TIMEOUT_SECONDS=2 $Image | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Ingress creation failed' }
    $createdIngress = $true
    $ports = (docker inspect $ingress | ConvertFrom-Json)[0].NetworkSettings.Ports
    $api = [int]$ports.'8080/tcp'[0].HostPort; $images = [int]$ports.'8081/tcp'[0].HostPort
    if (!$api -or !$images) { throw 'Missing published ports' }
    docker exec $ingress nginx -t -c /tmp/nginx.conf -e stderr
    if ($LASTEXITCODE -ne 0) { throw 'Rendered nginx config invalid' }
    # Enable timeout diagnostics only in this disposable fixture (queries are synthetic).
    docker exec $ingress sh -c "sed -i 's@error_log /dev/stderr warn;@error_log /dev/stderr info;@' /tmp/nginx.conf; nginx -s reload -c /tmp/nginx.conf -e stderr"
    if ($LASTEXITCODE -ne 0) { throw 'Fixture logging setup failed' }
    Start-Sleep -Milliseconds 200
    $large = Connect $api
    $query = 'x' * (11 * 1024 * 1024)
    Send $large "GET /url?formula=$query HTTP/1.1`r`nHost: api.example.test`r`nConnection: close`r`n`r`n"
    Assert-Match (Read-Headers $large) '^HTTP/1.1 200' 'Generous request-line allowance was lost'
    $large.Dispose()
    $oversized = Connect $api
    Send $oversized "GET /url HTTP/1.1`r`nHost: api.example.test`r`nContent-Length: 8388609`r`nConnection: close`r`n`r`n"
    Assert-Match (Read-Headers $oversized) '^HTTP/1.1 413' 'Body size cap was not enforced'
    $oversized.Dispose()
    $first = Connect $api; $second = Connect $images
    $bodyTimer = [System.Diagnostics.Stopwatch]::StartNew()
    Send $first "GET /url HTTP/1.1`r`nHost: api.example.test`r`nContent-Length: 1000`r`n`r`nx"
    Send $second "GET /img HTTP/1.1`r`nHost: images.example.test`r`nContent-Length: 1000`r`n`r`nx"
    # Wait for complete headers to enter nginx's native connection accounting.
    Start-Sleep -Milliseconds 200
    $third = Connect $images
    Send $third "GET /img HTTP/1.1`r`nHost: images.example.test`r`nX-Forwarded-For: 8.8.8.8`r`nConnection: close`r`n`r`n"
    $rejected = Read-Headers $third
    Assert-Match $rejected '^HTTP/1.1 429' 'Shared per-client cap was bypassed'
    Assert-Match $rejected '(?im)^Retry-After: 1\r?$' 'Missing retry hint'
    Assert-Match $rejected '(?im)^Access-Control-Allow-Origin: http://api.example.test\r?$' 'Overload lost image CORS'
    # nginx may close timed-out incomplete requests without sending an HTTP response.
    Assert-Match (Read-Headers $first) '^(HTTP/1.1 408|$)' 'Body-read timeout missing'
    Assert-Match (Read-Headers $second) '^(HTTP/1.1 408|$)' 'Image body-read timeout missing'
    if ($bodyTimer.Elapsed.TotalSeconds -lt 2 -or $bodyTimer.Elapsed.TotalSeconds -gt 8) { throw 'Body deadline did not close within the configured idle budget' }
    $partial = Connect $api
    Send $partial "GET /url HTTP/1.1`r`nHost: api.example.test`r`nX-Slow: "
    Assert-Match (Read-Headers $partial) '^(HTTP/1.1 408|$)' 'Incomplete headers did not time out'
    Assert-Match (docker logs $ingress 2>&1 | Out-String) 'client timed out .*while reading client request headers' 'Header deadline was not exercised'
    $alive = Connect $api
    Send $alive "GET /url HTTP/1.1`r`nHost: api.example.test`r`n`r`n"
    Assert-Match (Read-Headers $alive) '^HTTP/1.1 200' 'Capacity did not recover'
    # Fixture response has no body; next byte must be EOF after keepalive timeout.
    if ($alive.GetStream().ReadByte() -ne -1) { throw 'Idle keepalive did not close' }
    $slow = Connect $api
    Send $slow "GET /url-encode HTTP/1.1`r`nHost: api.example.test`r`n`r`n"
    Assert-Match (Read-Headers $slow) '^HTTP/1.1 200' 'Large response failed'
    # Stop reading long enough to fill the TCP buffers and hit send_timeout.
    Start-Sleep -Seconds 5
    $logs = docker logs $ingress 2>&1 | Out-String
    Assert-Match $logs 'client timed out .*while sending to client' 'Slow response reader was not disconnected'
    foreach ($socket in $sockets) { $socket.Dispose() }
    $sockets.Clear()
    # Make the total cap smaller than the client cap to exercise it independently.
    docker exec $ingress sh -c "sed -i 's/limit_conn clients 2;/limit_conn clients 4;/; s/limit_conn total 3;/limit_conn total 1;/' /tmp/nginx.conf; nginx -s reload -c /tmp/nginx.conf -e stderr"
    if ($LASTEXITCODE -ne 0) { throw 'Total-cap fixture setup failed' }
    Start-Sleep -Milliseconds 300
    $held = Connect $api
    Send $held "GET /url HTTP/1.1`r`nHost: api.example.test`r`nContent-Length: 1000`r`n`r`nx"
    Start-Sleep -Milliseconds 200
    $overTotal = Connect $images
    Send $overTotal "GET /img HTTP/1.1`r`nHost: images.example.test`r`nConnection: close`r`n`r`n"
    Assert-Match (Read-Headers $overTotal) '^HTTP/1.1 429' 'Global cap was not shared across listeners'
    Assert-Match (docker logs $ingress 2>&1 | Out-String) 'limiting connections by zone "total"' 'Global cap was not exercised independently'
    Write-Output 'PASS: 11 MiB request line, body cap, shared client and total caps, spoof resistance, Retry-After/CORS, header/body/keepalive/write timeouts and recovery.'
    $invalid = docker run --rm -e PERMMATH_HOST=http://api.example.test -e PERMMATH_IMAGE_HOST=http://images.example.test `
        -e 'PERMMATH_INGRESS_CLIENT_CONNECTIONS=2;bad' $Image 2>&1 | Out-String
    if ($LASTEXITCODE -eq 0) { throw 'Invalid limit accepted' }
    Assert-Match $invalid 'positive decimal integers' 'Invalid limit did not fail closed'
} finally {
    foreach ($socket in $sockets) { $socket.Dispose() }
    if ($createdIngress) { docker rm -f $ingress | Out-Null }
    if ($createdUpstream) { docker rm -f $upstream | Out-Null }
    if ($createdNetwork) { docker network rm $network | Out-Null }
}
