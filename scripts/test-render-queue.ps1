param([string]$ProjectName = 'permmath-queue-smoke')
$ErrorActionPreference = 'Stop'
if ($ProjectName -notmatch '^[a-z0-9][a-z0-9_-]*$') { throw 'Invalid Compose project name' }
$network = "${ProjectName}_ingress-public"
docker network inspect $network | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Start the isolated Compose test deployment first' }
$probe = Join-Path (Split-Path -Parent $PSScriptRoot) 'docker/ingress/render-queue-probe.sh'
$nonce = [DateTime]::UtcNow.Ticks
$processes = [System.Collections.Generic.List[System.Diagnostics.Process]]::new()
$names = [System.Collections.Generic.List[string]]::new()
try {
    foreach ($number in 1..2) {
        $name = "${ProjectName}-queue-probe-$([guid]::NewGuid().ToString('N').Substring(0, 10))"
        $start = [System.Diagnostics.ProcessStartInfo]::new('docker')
        $start.UseShellExecute = $false; $start.CreateNoWindow = $true
        $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
        foreach ($argument in @('run', '--rm', '--name', $name, '--network', $network, '--read-only', '--cap-drop', 'ALL',
                '--security-opt', 'no-new-privileges:true', '--user', '65534',
                '--tmpfs', '/tmp:rw,nosuid,nodev,noexec,size=20m,mode=1777',
                '--mount', "type=bind,source=$probe,target=/probe.sh,readonly",
                '-e', "QUEUE_TEST_FORMULA=x%5E$number%2B$nonce",
                'alpine:3.23@sha256:85fe1e81d6758c208f3e1eed4338a1997e19d4be002d4dd32d3100c9a8c010a0', 'sh', '/probe.sh')) {
            $start.ArgumentList.Add($argument)
        }
        $process = [System.Diagnostics.Process]::new(); $process.StartInfo = $start
        if (!$process.Start()) { throw 'Cannot start probe' }
        $names.Add($name); $processes.Add($process)
    }
    foreach ($process in $processes) {
        $output = $process.StandardOutput.ReadToEndAsync(); $errors = $process.StandardError.ReadToEndAsync()
        if (!$process.WaitForExit(60000)) { throw 'Concurrent render exceeded its request deadline' }
        $result = $output.GetAwaiter().GetResult(); $errorText = $errors.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0 -or $result -notmatch 'PASS: concurrent unique render returned SVG') {
            throw "Concurrent render failed: $errorText $result"
        }
        Write-Output $result.Trim()
    }
} finally {
    foreach ($name in $names) {
        if (docker ps -aq --filter "name=^/$name`$") { docker rm -f $name | Out-Null }
    }
    foreach ($process in $processes) {
        if (!$process.HasExited) { $process.Kill() }
        $process.Dispose()
    }
}
