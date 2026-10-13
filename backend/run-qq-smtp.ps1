param(
    [string]$MavenExecutable = 'E:\apache-maven-3.9.14\bin\mvn.cmd',
    [string]$JavaSocketDirectory = 'E:\tmp\java-sockets'
)
$ErrorActionPreference = 'Stop'

$mailAddress = (Read-Host '发件 QQ 邮箱（例如 123456@qq.com）').Trim()
if ($mailAddress -notmatch '^[^@\s]+@qq\.com$') {
    throw '请填写完整的 QQ 邮箱地址'
}
$authorizationCode = Read-Host 'QQ 邮箱 SMTP 授权码（输入不会显示）' -AsSecureString
if ($authorizationCode.Length -eq 0) {
    throw 'SMTP 授权码不能为空'
}

$env:APP_MAIL_MODE = 'smtp'
$env:MAIL_HOST = 'smtp.qq.com'
$env:MAIL_PORT = '465'
$env:MAIL_SSL_ENABLED = 'true'
$env:MAIL_STARTTLS_ENABLED = 'false'
$env:MAIL_STARTTLS_REQUIRED = 'false'
$env:MAIL_USERNAME = $mailAddress
$env:MAIL_FROM = $mailAddress

$secretPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($authorizationCode)
try {
    $env:MAIL_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($secretPointer)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($secretPointer)
}

try {
    & (Join-Path $PSScriptRoot 'run-local.ps1') -MavenExecutable $MavenExecutable -JavaSocketDirectory $JavaSocketDirectory
} finally {
    Remove-Item Env:MAIL_PASSWORD -ErrorAction SilentlyContinue
}
