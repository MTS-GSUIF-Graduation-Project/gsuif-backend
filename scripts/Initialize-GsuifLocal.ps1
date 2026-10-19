# One-time local setup for backend generation. Safe to re-run; it only fills in what is missing
# unless asked. It never prints the OpenRouter key and never changes the database.
param(
    # Absolute path to bin\mvn.cmd of a complete Apache Maven distribution. Default: the Maven wrapper's download.
    [string]$MavenCommand,
    # Local Maven repository used for provisioning and the offline package build. Default: %USERPROFILE%\.m2\repository.
    [string]$MavenRepository,
    # Prompt for a new key even though one is saved.
    [switch]$RotateKey,
    # Do not pull missing pinned images; report them instead.
    [switch]$NoPull
)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'GsuifLocal.psm1') -Force
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$checks = @()

# 1. OpenRouter key: reuse the DPAPI store written by Save-OpenRouterKey.ps1.
if ($RotateKey -or -not (Test-Path -LiteralPath (Get-GsuifSecretPath) -PathType Leaf)) {
    & (Join-Path $PSScriptRoot 'Save-OpenRouterKey.ps1')
}
$checks += Test-GsuifSavedKey

# 2. Model: the ignored config/openrouter.local.ps1 the launcher already reads.
$modelConfig = Join-Path $root 'config\openrouter.local.ps1'
if (-not (Test-Path -LiteralPath $modelConfig)) {
    Copy-Item -LiteralPath (Join-Path $root 'config\openrouter.local.example.ps1') -Destination $modelConfig
    Write-Output 'Created config\openrouter.local.ps1 from the example (ignored by Git).'
}
$checks += Test-GsuifModelConfig

# 3. Maven paths, stored outside Git.
$saved = Read-GsuifMachineSettings
if (-not $MavenCommand -and $saved) { $MavenCommand = [string]$saved.mavenCommand }
if (-not $MavenCommand) { $MavenCommand = Find-GsuifWrapperMaven }
if (-not $MavenCommand) {
    Write-Output 'Downloading the Maven distribution pinned by the Maven wrapper (one time)...'
    Push-Location $root
    try { & (Join-Path $root 'mvnw.cmd') -q -v | Out-Null } finally { Pop-Location }
    $MavenCommand = Find-GsuifWrapperMaven
}
if (-not $MavenRepository -and $saved) { $MavenRepository = [string]$saved.mavenRepository }
if (-not $MavenRepository) { $MavenRepository = Join-Path $env:USERPROFILE '.m2\repository' }
if ($MavenCommand -and (Test-Path -LiteralPath $MavenCommand -PathType Leaf)) {
    $MavenCommand = [IO.Path]::GetFullPath($MavenCommand)
    New-Item -ItemType Directory -Path $MavenRepository -Force | Out-Null
    $MavenRepository = [IO.Path]::GetFullPath($MavenRepository)
    $settingsPath = Write-GsuifMachineSettings -MavenCommand $MavenCommand -MavenRepository $MavenRepository
    Write-Output "Saved Maven paths to $settingsPath."
}
$checks += Test-GsuifMaven -Settings (Read-GsuifMachineSettings)
$mavenReady = @($checks | Where-Object { $_.Area -eq 'Generation' -and $_.Name -like 'Maven*' -and $_.Status -eq 'FAIL' }).Count -eq 0

# 4. Pinned images: pull by digest if missing, resolve the local ID, then probe each role.
$server = Get-GsuifDockerServer
if (-not $server.Reachable -or $server.Os -ne 'linux') {
    $checks += New-GsuifCheck 'Core' 'Docker' 'FAIL' 'A Linux Docker engine is not reachable.' 'Start Docker Desktop with Linux containers and re-run this script.'
} else {
    $lock = Get-GsuifImageLock
    foreach ($reference in @($lock.Values | ForEach-Object { $_.reference } | Sort-Object -Unique)) {
        if (-not (Resolve-GsuifImage -Reference $reference).Present -and -not $NoPull) {
            Write-Output "Pulling pinned image $reference (one time)..."
            $pull = Invoke-GsuifDocker -Arguments @('pull', $reference)
            if ($pull.ExitCode -ne 0) { Write-Output "docker pull failed: $($pull.Output)" }
        }
    }
    $images = Test-GsuifImages -Lock $lock -Server $server
    $checks += $images.Checks
    $settings = Read-GsuifMachineSettings
    foreach ($role in $lock.Keys) {
        $id = $images.Ids[$lock[$role].variable]
        if (-not $id) { continue }
        if ($role -eq 'build' -and -not $mavenReady) {
            $checks += New-GsuifCheck 'Generation' 'build image probe' 'FAIL' 'Skipped because the Maven settings are not valid.' 'Fix the Maven checks first.'
            continue
        }
        $probe = Invoke-GsuifImageProbe -Role $role -ImageId $id `
            -MavenHome $(if ($settings) { Get-GsuifMavenHome ([string]$settings.mavenCommand) }) `
            -MavenRepository $(if ($settings) { [string]$settings.mavenRepository })
        if ($probe.Ok) {
            $checks += New-GsuifCheck 'Generation' "$role image probe" 'OK' 'Requirements met inside the same isolated, offline, non-root container setup the backend uses.'
        } else {
            $checks += New-GsuifCheck 'Generation' "$role image probe" 'FAIL' "Exit $($probe.ExitCode); missing: $($probe.Missing -join ', ')." `
                "The pinned image does not meet: $($lock[$role].requires) Choose and review another digest in config\docker-images.lock.json."
        }
    }
    # 5. Database: read-only report; nothing is created, migrated or reset.
    $checks += Test-GsuifDatabase
}

Write-Output ''
Write-GsuifChecks $checks
if (@($checks | Where-Object { $_.Status -eq 'FAIL' }).Count -gt 0) {
    Write-Output ''
    Write-Output 'Setup is incomplete. Fix the items above and run .\scripts\Initialize-GsuifLocal.ps1 again.'
    exit 1
}
Write-Output ''
Write-Output 'Setup complete. Start the backend with: .\scripts\Start-GsuifLocal.ps1'
