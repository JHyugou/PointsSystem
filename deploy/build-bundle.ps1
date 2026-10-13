param(
    [switch]$SkipBuild,
    [string]$MavenExecutable = 'E:\apache-maven-3.9.14\bin\mvn.cmd',
    [string]$JavaSocketDirectory = 'E:\tmp\java-sockets'
)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$backendDirectory = Join-Path $projectDirectory 'backend'
if (-not $SkipBuild) {
    New-Item -ItemType Directory -Force -Path $JavaSocketDirectory | Out-Null
    $env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir="' + $JavaSocketDirectory + '"'
    Push-Location $backendDirectory
    try {
        & $MavenExecutable -o -q package
        if ($LASTEXITCODE -ne 0) { throw '后端构建或测试失败' }
    } finally { Pop-Location }
}
$jarFile = Join-Path $backendDirectory 'target\points-api-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jarFile)) { throw '找不到后端 JAR' }
$distributionDirectory = Join-Path $PSScriptRoot 'dist'
New-Item -ItemType Directory -Force -Path $distributionDirectory | Out-Null
$bundleName = 'points-home-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
$stagingDirectory = Join-Path $distributionDirectory $bundleName
New-Item -ItemType Directory -Path $stagingDirectory | Out-Null
Copy-Item -LiteralPath $jarFile -Destination (Join-Path $stagingDirectory 'points-api.jar')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'linux') -Destination (Join-Path $stagingDirectory 'linux') -Recurse
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'README.md') -Destination $stagingDirectory
$archiveFile = Join-Path $distributionDirectory ($bundleName + '.tar.gz')
& tar -czf $archiveFile -C $stagingDirectory points-api.jar linux README.md
if ($LASTEXITCODE -ne 0) { throw '部署包打包失败' }
$hash = (Get-FileHash -LiteralPath $archiveFile -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText($archiveFile + '.sha256', $hash + '  ' + [IO.Path]::GetFileName($archiveFile) + "`n", [Text.UTF8Encoding]::new($false))
# Only these explicitly selected deployment files are included, never data or secrets.
Get-Item -LiteralPath $archiveFile | Select-Object FullName,Length
