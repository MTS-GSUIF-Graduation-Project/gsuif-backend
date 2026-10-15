# Run interactively on Windows. DPAPI binds the saved value to this Windows user.
$ErrorActionPreference = 'Stop'
if (-not $IsWindows -and $PSVersionTable.PSEdition -eq 'Core') { throw 'Windows DPAPI is required.' }
if ([string]::IsNullOrWhiteSpace($env:APPDATA)) { throw 'APPDATA is required.' }
$secretDirectory = Join-Path $env:APPDATA 'GSUIF'
$secretPath = Join-Path $secretDirectory 'openrouter-key.dpapi'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')).TrimEnd('\') + '\'
if ([IO.Path]::GetFullPath($secretPath).StartsWith($repositoryRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The secret store must be outside this repository.'
}
$secureKey = Read-Host 'OpenRouter API key' -AsSecureString
if ($secureKey.Length -eq 0) { throw 'An API key is required.' }
$temporaryPath = Join-Path $secretDirectory ([guid]::NewGuid().ToString() + '.dpapi')
try {
    New-Item -ItemType Directory -Path $secretDirectory -Force | Out-Null
    $secureKey | ConvertFrom-SecureString | Set-Content -LiteralPath $temporaryPath -NoNewline
    $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
    $rule = New-Object System.Security.AccessControl.FileSystemAccessRule($identity.User, 'FullControl', 'Allow')
    $acl = Get-Acl -LiteralPath $temporaryPath
    $acl.SetAccessRuleProtection($true, $false)
    $acl.SetAccessRule($rule)
    Set-Acl -LiteralPath $temporaryPath -AclObject $acl
    if (Test-Path -LiteralPath $secretPath) {
        [IO.File]::Replace($temporaryPath, $secretPath, $null)
    } else {
        [IO.File]::Move($temporaryPath, $secretPath)
    }
} finally {
    Remove-Item -LiteralPath $temporaryPath -Force -ErrorAction SilentlyContinue
    $secureKey.Dispose()
}
Write-Output 'OpenRouter key is stored for the current Windows user outside Git.'
