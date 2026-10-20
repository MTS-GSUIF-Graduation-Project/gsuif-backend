$ErrorActionPreference = 'Stop'
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('gsuif-openrouter-loader-' + [guid]::NewGuid())
$testAppData = Join-Path ([IO.Path]::GetTempPath()) ('gsuif-openrouter-appdata-' + [guid]::NewGuid())
$source = Join-Path $PSScriptRoot 'Invoke-OpenRouterLocal.ps1'
$originalKey = $env:OPENROUTER_API_KEY
$originalModel = $env:OPENROUTER_MODEL
$originalAppData = $env:APPDATA
$hadKey = Test-Path Env:OPENROUTER_API_KEY
$hadModel = Test-Path Env:OPENROUTER_MODEL
try {
    New-Item -ItemType Directory -Path (Join-Path $testRoot 'scripts'), (Join-Path $testRoot 'config'), (Join-Path $testAppData 'GSUIF') -Force | Out-Null
    Copy-Item -LiteralPath $source -Destination (Join-Path $testRoot 'scripts/Invoke-OpenRouterLocal.ps1')
    Set-Content -LiteralPath (Join-Path $testRoot 'config/openrouter.local.ps1') -Value '$env:OPENROUTER_MODEL = "fake/model"'
    $env:APPDATA = $testAppData
    $fake = ConvertTo-SecureString 'disposable-test-only' -AsPlainText -Force
    ConvertFrom-SecureString $fake | Set-Content -LiteralPath (Join-Path $env:APPDATA 'GSUIF/openrouter-key.dpapi') -NoNewline
    $child = Join-Path $testRoot 'child.ps1'
    Set-Content -LiteralPath $child -Value 'if ($env:OPENROUTER_API_KEY -ne "disposable-test-only" -or $env:OPENROUTER_MODEL -ne "fake/model") { exit 17 }'
    $env:OPENROUTER_API_KEY = 'pre-existing-fake'
    $env:OPENROUTER_MODEL = 'pre-existing-model'
    $loader = Join-Path $testRoot 'scripts/Invoke-OpenRouterLocal.ps1'
    try { & $loader -BackendCommand 'powershell.exe' -BackendArguments @('-NoProfile', '-File', $child); throw 'Expected rejection' }
    catch { if ($_.Exception.Message -eq 'Expected rejection') { throw } }
    if ($env:OPENROUTER_API_KEY -ne 'pre-existing-fake' -or $env:OPENROUTER_MODEL -ne 'pre-existing-model') { throw 'Rejected invocation changed caller values' }
    Remove-Item Env:OPENROUTER_API_KEY
    Set-Content -LiteralPath (Join-Path $testRoot 'config/openrouter.local.ps1') -Value '$env:OPENROUTER_MODEL = "fake/model"; $env:OPENROUTER_API_KEY = "config-fake"'
    try { & $loader -BackendCommand 'powershell.exe' -BackendArguments @('-NoProfile', '-File', $child); throw 'Expected config rejection' }
    catch { if ($_.Exception.Message -eq 'Expected config rejection') { throw } }
    if ((Test-Path Env:OPENROUTER_API_KEY) -or $env:OPENROUTER_MODEL -ne 'pre-existing-model') { throw 'Config rejection changed caller values' }
    Set-Content -LiteralPath (Join-Path $testRoot 'config/openrouter.local.ps1') -Value '$env:OPENROUTER_MODEL = "fake/model"'
    $secretFile = Join-Path $env:APPDATA 'GSUIF/openrouter-key.dpapi'
    Set-Content -LiteralPath $secretFile -Value 'invalid-disposable-ciphertext' -NoNewline
    try { & $loader -BackendCommand 'powershell.exe' -BackendArguments @('-NoProfile', '-File', $child); throw 'Expected decrypt failure' }
    catch { if ($_.Exception.Message -eq 'Expected decrypt failure') { throw } }
    if ((Test-Path Env:OPENROUTER_API_KEY) -or $env:OPENROUTER_MODEL -ne 'pre-existing-model') { throw 'Decrypt failure changed caller values' }
    ConvertFrom-SecureString $fake | Set-Content -LiteralPath $secretFile -NoNewline
    & $loader -BackendCommand 'powershell.exe' -BackendArguments @('-NoProfile', '-File', $child)
    if (Test-Path Env:OPENROUTER_API_KEY) { throw 'Successful invocation retained temporary key' }
    if ($env:OPENROUTER_MODEL -ne 'pre-existing-model') { throw 'Successful invocation changed caller model' }
    Set-Content -LiteralPath $child -Value 'exit 19'
    try { & $loader -BackendCommand 'powershell.exe' -BackendArguments @('-NoProfile', '-File', $child); throw 'Expected child failure' }
    catch { if ($_.Exception.Message -eq 'Expected child failure') { throw } }
    if (Test-Path Env:OPENROUTER_API_KEY) { throw 'Failed invocation retained temporary key' }
    if ($env:OPENROUTER_MODEL -ne 'pre-existing-model') { throw 'Failed invocation changed caller model' }
    $env:GSUIF_LOADER_TEST_EXISTING = 'caller-value'
    Remove-Item Env:GSUIF_LOADER_TEST_NEW -ErrorAction SilentlyContinue
    Set-Content -LiteralPath $child -Value 'if ($env:GSUIF_LOADER_TEST_EXISTING -ne "launch-value" -or $env:GSUIF_LOADER_TEST_NEW -ne "added" -or $env:OPENROUTER_API_KEY -ne "disposable-test-only") { exit 23 }'
    & $loader -BackendCommand 'powershell.exe' -BackendArguments @('-NoProfile', '-File', $child) `
        -Environment @{ GSUIF_LOADER_TEST_EXISTING = 'launch-value'; GSUIF_LOADER_TEST_NEW = 'added' }
    if ($env:GSUIF_LOADER_TEST_EXISTING -ne 'caller-value' -or (Test-Path Env:GSUIF_LOADER_TEST_NEW)) { throw 'Extra environment was not restored' }
    if (Test-Path Env:OPENROUTER_API_KEY) { throw 'Extra environment invocation retained temporary key' }
    foreach ($forbidden in @(@{ OPENROUTER_API_KEY = 'x' }, @{ OPENROUTER_MODEL = 'x' }, @{ 'bad-name' = 'x' }, @{ GSUIF_EMPTY = ' ' })) {
        try { & $loader -BackendCommand 'powershell.exe' -BackendArguments @('-NoProfile', '-Command', 'exit 0') -Environment $forbidden; throw 'Expected environment rejection' }
        catch { if ($_.Exception.Message -eq 'Expected environment rejection') { throw } }
    }
    if ((Test-Path Env:OPENROUTER_API_KEY) -or $env:GSUIF_LOADER_TEST_EXISTING -ne 'caller-value') { throw 'Environment rejection changed caller values' }
    Remove-Item Env:GSUIF_LOADER_TEST_EXISTING
    Write-Output 'OpenRouter local loader: 7 disposable-value checks passed'
} finally {
    if ($hadKey) { $env:OPENROUTER_API_KEY = $originalKey } else { Remove-Item Env:OPENROUTER_API_KEY -ErrorAction SilentlyContinue }
    if ($hadModel) { $env:OPENROUTER_MODEL = $originalModel } else { Remove-Item Env:OPENROUTER_MODEL -ErrorAction SilentlyContinue }
    $env:APPDATA = $originalAppData
    $resolved = [IO.Path]::GetFullPath($testRoot)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if ($resolved.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $resolved)) {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
    $resolvedAppData = [IO.Path]::GetFullPath($testAppData)
    if ($resolvedAppData.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $resolvedAppData)) {
        Remove-Item -LiteralPath $resolvedAppData -Recurse -Force
    }
}
