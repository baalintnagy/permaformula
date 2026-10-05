$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    $env:PLAYWRIGHT_BROWSERS_PATH = Join-Path $projectRoot '.runtime/browsers'
    $env:PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD = '1'
    mvn dependency:build-classpath '-Dmdep.outputFile=target/render-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Dependency resolution failed' }
    $renderClasspath = (Get-Content target/render-classpath.txt -Raw).Trim()
    & "$env:JAVA_HOME/bin/java" -cp $renderClasspath com.microsoft.playwright.CLI install chromium --no-shell
    if ($LASTEXITCODE -ne 0) { throw 'Chromium installation failed' }
} finally { Pop-Location }
