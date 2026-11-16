param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$releaseId = Get-Date -Format 'yyyyMMdd-HHmmss'
$bundleDir = Join-Path $PSScriptRoot ".bundle/$releaseId"

if (-not $SkipBuild) {
    Push-Location (Join-Path $repoRoot 'ai-rag-agent')
    try {
        & mvn -B -ntp clean verify
        if ($LASTEXITCODE -ne 0) { throw 'Backend build or tests failed.' }
    } finally { Pop-Location }
    Push-Location (Join-Path $repoRoot 'ai-job-prep-frontend')
    try {
        & pnpm build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    } finally { Pop-Location }
}

New-Item -ItemType Directory -Path $bundleDir -Force | Out-Null
$configFiles = @('backend.Dockerfile', 'frontend.Dockerfile', '.dockerignore', 'nginx.conf', 'application.yml', 'compose.yaml', 'es-init.sh', 'es-index.json', '.env.example', 'README.md')
foreach ($configFile in $configFiles) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $configFile) -Destination $bundleDir
}
Copy-Item -LiteralPath (Join-Path $repoRoot 'ai-rag-agent/bootstrap/target/bootstrap-1.0.0.jar') -Destination (Join-Path $bundleDir 'app.jar')
Copy-Item -LiteralPath (Join-Path $repoRoot 'ai-job-prep-frontend/dist') -Destination (Join-Path $bundleDir 'dist') -Recurse
Copy-Item -LiteralPath (Join-Path $repoRoot 'ai-rag-agent/knowledge/src/main/resources/schema.sql') -Destination $bundleDir
# Linux entrypoint scripts must use LF even if the checkout uses CRLF.
$initScript = Join-Path $bundleDir 'es-init.sh'
[IO.File]::WriteAllText($initScript, [IO.File]::ReadAllText($initScript).Replace("`r`n", "`n"), [Text.UTF8Encoding]::new($false))
$archivePath = Join-Path $PSScriptRoot "memoryai-$releaseId.tar.gz"
& tar -czf $archivePath -C $bundleDir .
if ($LASTEXITCODE -ne 0) { throw 'Archive creation failed.' }
Write-Output "Bundle: $bundleDir"
Write-Output "Archive: $archivePath"
Write-Output "SHA256: $((Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash)"
