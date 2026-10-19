# Normal local startup: checks prerequisites, loads the generation settings for the backend
# process only, then runs the backend with the local profile. Prints no secret values.
param(
    [int]$Port = 8080,
    # Start without loading generation settings; everything except backend generation works.
    [switch]$WithoutGeneration,
    # Run the checks and stop.
    [switch]$CheckOnly
)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'GsuifLocal.psm1') -Force
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))

$preflight = Invoke-GsuifPreflight -Port $Port -WithoutGeneration:$WithoutGeneration -StartDatabase
Write-GsuifChecks $preflight.Checks
if ($preflight.Failed) {
    $failed = @($preflight.Checks | Where-Object { $_.Status -eq 'FAIL' })
    Write-Output ''
    Write-Output "Not starting: $($failed.Count) check(s) failed."
    if (-not $WithoutGeneration -and @($failed | Where-Object { $_.Area -ne 'Generation' }).Count -eq 0) {
        Write-Output 'Only generation settings failed. Run .\scripts\Initialize-GsuifLocal.ps1, or start without generation: .\scripts\Start-GsuifLocal.ps1 -WithoutGeneration'
    }
    exit 1
}
if ($CheckOnly) {
    Write-Output ''
    Write-Output 'All checks passed (-CheckOnly; not starting).'
    exit 0
}

$mavenArguments = @('spring-boot:run', '-Dspring-boot.run.profiles=local')
if ($Port -ne 8080) { $mavenArguments += "-Dspring-boot.run.arguments=--server.port=$Port" }
$mvnw = Join-Path $root 'mvnw.cmd'
Write-Output ''
Push-Location $root
try {
    if ($WithoutGeneration) {
        Write-Output "Starting GSUIF on port $Port without generation settings."
        & $mvnw @mavenArguments
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    } else {
        Write-Output "Starting GSUIF on port $Port with generation settings (values not shown)."
        $environment = Get-GsuifGenerationEnvironment -ImageIds $preflight.ImageIds
        & (Join-Path $PSScriptRoot 'Invoke-OpenRouterLocal.ps1') -BackendCommand $mvnw -BackendArguments $mavenArguments -Environment $environment
    }
} finally {
    Pop-Location
}
