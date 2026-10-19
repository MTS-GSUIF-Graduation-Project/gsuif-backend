param(
    [Parameter(Mandatory = $true)][string]$BackendCommand,
    [string[]]$BackendArguments = @(),
    # Additional non-secret variables for the launched process; restored like the key and model.
    [hashtable]$Environment = @{}
)
$ErrorActionPreference = 'Stop'
foreach ($name in @($Environment.Keys)) {
    if ([string]$name -cnotmatch '^[A-Z][A-Z0-9_]*$') { throw "Invalid environment variable name: $name" }
    if (@('OPENROUTER_API_KEY', 'OPENROUTER_MODEL') -contains [string]$name) {
        throw "$name cannot be passed through -Environment."
    }
    if ([string]::IsNullOrWhiteSpace([string]$Environment[$name])) { throw "$name must not be empty." }
}
$previousExtra = @{}
foreach ($name in @($Environment.Keys)) {
    $previousExtra[$name] = @{ Had = (Test-Path "Env:$name"); Value = [Environment]::GetEnvironmentVariable($name, 'Process') }
}
$previousModel = $env:OPENROUTER_MODEL
$hadModel = Test-Path Env:OPENROUTER_MODEL
$previousKey = $env:OPENROUTER_API_KEY
$hadKey = Test-Path Env:OPENROUTER_API_KEY
$pointer = [IntPtr]::Zero
$secureKey = $null
try {
    if (-not $IsWindows -and $PSVersionTable.PSEdition -eq 'Core') { throw 'Windows DPAPI is required.' }
    if ([string]::IsNullOrWhiteSpace($env:APPDATA)) { throw 'APPDATA is required.' }
    $localConfig = Join-Path $PSScriptRoot '..\config\openrouter.local.ps1'
    if (-not (Test-Path -LiteralPath $localConfig)) {
        throw 'Copy config/openrouter.local.example.ps1 to config/openrouter.local.ps1 and select a model.'
    }
    . $localConfig
    if ([string]::IsNullOrWhiteSpace($env:OPENROUTER_MODEL)) { throw 'OPENROUTER_MODEL must be set explicitly.' }
    if (Test-Path Env:OPENROUTER_API_KEY) {
        throw 'OPENROUTER_API_KEY must be supplied by the encrypted local store, not the model config.'
    }
    $secretPath = Join-Path (Join-Path $env:APPDATA 'GSUIF') 'openrouter-key.dpapi'
    $repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')).TrimEnd('\') + '\'
    if ([IO.Path]::GetFullPath($secretPath).StartsWith($repositoryRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The secret store must be outside this repository.'
    }
    if (-not (Test-Path -LiteralPath $secretPath -PathType Leaf)) { throw 'OpenRouter key is not configured for this Windows user.' }
    $secureKey = Get-Content -LiteralPath $secretPath -Raw | ConvertTo-SecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)
    $env:OPENROUTER_API_KEY = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    if ([string]::IsNullOrWhiteSpace($env:OPENROUTER_API_KEY)) { throw 'Stored OpenRouter key is empty.' }
    foreach ($name in @($Environment.Keys)) { [Environment]::SetEnvironmentVariable($name, [string]$Environment[$name], 'Process') }
    $global:LASTEXITCODE = $null
    & $BackendCommand @BackendArguments
    if ($LASTEXITCODE -ne $null -and $LASTEXITCODE -ne 0) {
        throw "Backend process exited with code $LASTEXITCODE"
    }
} finally {
    if ($hadKey) { $env:OPENROUTER_API_KEY = $previousKey }
    else { Remove-Item Env:OPENROUTER_API_KEY -ErrorAction SilentlyContinue }
    if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
    if ($null -ne $secureKey) { $secureKey.Dispose() }
    if ($hadModel) { $env:OPENROUTER_MODEL = $previousModel }
    else { Remove-Item Env:OPENROUTER_MODEL -ErrorAction SilentlyContinue }
    foreach ($name in @($previousExtra.Keys)) {
        if ($previousExtra[$name].Had) { [Environment]::SetEnvironmentVariable($name, $previousExtra[$name].Value, 'Process') }
        else { [Environment]::SetEnvironmentVariable($name, $null, 'Process') }
    }
}
