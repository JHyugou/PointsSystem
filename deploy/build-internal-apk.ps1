param(
    [string]$JavaHome = 'E:\Java\jdk17',
    [string]$AndroidSdk = 'E:\Android\Sdk',
    [string]$JavaSocketDirectory = 'E:\tmp\java-sockets',
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
$workspace = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$privateDirectory = Join-Path $PSScriptRoot 'private'
$distributionDirectory = Join-Path $PSScriptRoot 'dist'
New-Item -ItemType Directory -Force -Path $privateDirectory, $distributionDirectory | Out-Null
$privateDirectory = (Resolve-Path -LiteralPath $privateDirectory).Path
if (-not $privateDirectory.StartsWith($workspace + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Signing files must remain inside this workspace.'
}
$currentIdentity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls.exe $privateDirectory /inheritance:r /grant:r ('{0}:(OI)(CI)F' -f $currentIdentity) 'SYSTEM:(OI)(CI)F' /Q | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not restrict access to the signing directory.' }
Get-ChildItem -LiteralPath $privateDirectory -Force | ForEach-Object {
    & icacls.exe $_.FullName /reset /T /Q | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not apply private directory permissions to existing files.' }
}
$keyFile = Join-Path $privateDirectory 'points-home-release.p12'
$propertiesFile = Join-Path $privateDirectory 'android-signing.properties'
$keyTool = Join-Path $JavaHome 'bin\keytool.exe'
if (-not (Test-Path -LiteralPath $keyTool)) { throw 'Java keytool was not found.' }
if ((Test-Path -LiteralPath $keyFile) -xor (Test-Path -LiteralPath $propertiesFile)) {
    throw 'Signing key and configuration must be restored together; refusing to replace either.'
}
if (-not (Test-Path -LiteralPath $keyFile)) {
    $passwordBytes = New-Object byte[] 32
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($passwordBytes) } finally { $generator.Dispose() }
    $env:POINTS_HOME_SIGNING_PASSWORD = [Convert]::ToBase64String($passwordBytes)
    try {
        & $keyTool -genkeypair -keystore $keyFile -storetype PKCS12 -alias points-home `
            -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 10000 `
            -dname 'CN=Points Home, OU=Internal App, O=Points Home, C=CN' `
            -storepass:env POINTS_HOME_SIGNING_PASSWORD -keypass:env POINTS_HOME_SIGNING_PASSWORD
        if ($LASTEXITCODE -ne 0) { throw 'Signing key generation failed.' }
        $properties = "storeFile=$($keyFile.Replace('\', '/'))`nstorePassword=$env:POINTS_HOME_SIGNING_PASSWORD`nkeyAlias=points-home`nkeyPassword=$env:POINTS_HOME_SIGNING_PASSWORD`n"
        [System.IO.File]::WriteAllText($propertiesFile, $properties, (New-Object System.Text.UTF8Encoding $false))
    } finally {
        Remove-Item Env:POINTS_HOME_SIGNING_PASSWORD -ErrorAction SilentlyContinue
        [Array]::Clear($passwordBytes, 0, $passwordBytes.Length)
        $properties = $null
    }
}
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $AndroidSdk
New-Item -ItemType Directory -Force -Path $JavaSocketDirectory | Out-Null
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir="' + $JavaSocketDirectory + '"'
Push-Location (Join-Path $workspace 'android')
try {
    $buildArguments = @(':app:assembleDebug', ':app:assembleRelease')
    if ($Offline) { $buildArguments = @('--offline') + $buildArguments }
    & '.\gradlew.bat' @buildArguments
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed.' }
} finally { Pop-Location }
$apkSource = Join-Path $workspace 'android\app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path -LiteralPath $apkSource)) { throw 'A signed release APK was not produced.' }
$buildTools = Get-ChildItem -LiteralPath (Join-Path $AndroidSdk 'build-tools') -Directory |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'apksigner.bat') } |
    Sort-Object { [version]($_.Name -replace '-.*$', '') } -Descending | Select-Object -First 1
if (-not $buildTools) { throw 'Android apksigner was not found.' }
& (Join-Path $buildTools.FullName 'apksigner.bat') verify --verbose --print-certs $apkSource
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
$metadata = Get-Content -LiteralPath (Join-Path $workspace 'android\app\build\outputs\apk\release\output-metadata.json') -Raw | ConvertFrom-Json
$appVersion = $metadata.elements[0].versionName
if ($appVersion -notmatch '^\d+\.\d+\.\d+$') { throw 'Invalid APK version metadata.' }
$apkOutput = Join-Path $distributionDirectory ('points-home-internal-{0}.apk' -f $appVersion)
Copy-Item -LiteralPath $apkSource -Destination $apkOutput -Force
$apkHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $apkOutput).Hash.ToLowerInvariant()
[System.IO.File]::WriteAllText($apkOutput + '.sha256', "$apkHash  $([System.IO.Path]::GetFileName($apkOutput))`n")
Write-Output "Signed internal APK: $apkOutput"
Write-Output 'Keep deploy/private/points-home-release.p12 and android-signing.properties together for future app updates.'
