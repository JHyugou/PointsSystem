param(
    [string]$MavenExecutable = 'E:\apache-maven-3.9.14\bin\mvn.cmd',
    [string]$JavaSocketDirectory = 'E:\tmp\java-sockets'
)
$ErrorActionPreference = 'Stop'
if (-not (Get-Command $MavenExecutable -ErrorAction SilentlyContinue)) {
    throw 'Maven was not found. Pass -MavenExecutable with mvn.cmd or its full path.'
}
Set-Location $PSScriptRoot

$dataDirectory = Join-Path $PSScriptRoot 'data'
New-Item -ItemType Directory -Force -Path $dataDirectory | Out-Null
if ([string]::IsNullOrWhiteSpace($env:APP_OTP_SECRET)) {
    $otpSecretFile = Join-Path $dataDirectory 'otp-secret.key'
    if (-not (Test-Path -LiteralPath $otpSecretFile)) {
        $otpBytes = New-Object byte[] 32
        $otpGenerator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        try { $otpGenerator.GetBytes($otpBytes) } finally { $otpGenerator.Dispose() }
        [System.IO.File]::WriteAllText($otpSecretFile, [Convert]::ToBase64String($otpBytes))
    }
    $env:APP_OTP_SECRET = [System.IO.File]::ReadAllText($otpSecretFile).Trim()
}
if ([string]::IsNullOrWhiteSpace($env:APP_MAIL_MODE)) {
    $env:APP_MAIL_MODE = 'console'
}
if ([string]::IsNullOrWhiteSpace($env:APP_AI_MASTER_KEY)) {
    $masterKeyFile = Join-Path $dataDirectory 'ai-master.key'
    if (-not (Test-Path -LiteralPath $masterKeyFile)) {
        $aiKeyBytes = New-Object byte[] 32
        $aiKeyGenerator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        try { $aiKeyGenerator.GetBytes($aiKeyBytes) } finally { $aiKeyGenerator.Dispose() }
        [System.IO.File]::WriteAllText($masterKeyFile, [Convert]::ToBase64String($aiKeyBytes))
    }
    $env:APP_AI_MASTER_KEY = [System.IO.File]::ReadAllText($masterKeyFile).Trim()
}
New-Item -ItemType Directory -Force -Path $JavaSocketDirectory | Out-Null
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir="' + $JavaSocketDirectory + '"'

& $MavenExecutable spring-boot:run
if ($LASTEXITCODE -ne 0) { throw 'Backend startup failed.' }
