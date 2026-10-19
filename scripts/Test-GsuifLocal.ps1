# Disposable-value tests for GsuifLocal.psm1 with a fake Docker CLI. Uses no real key, image or database.
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'GsuifLocal.psm1') -Force
$module = Get-Module GsuifLocal
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('gsuif-local-test-' + [guid]::NewGuid())
$originalAppData = $env:APPDATA
$originalModel = [Environment]::GetEnvironmentVariable('OPENROUTER_MODEL', 'Process')
$secretValue = 'disposable-test-only-' + [guid]::NewGuid().ToString('N')
$script:passed = 0

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "FAILED: $Message" }
    $script:passed++
}
function Assert-Throws([scriptblock]$Action, [string]$Expected, [string]$Message) {
    try { & $Action } catch {
        Assert-True ($_.Exception.Message -like "*$Expected*") "$Message (got: $($_.Exception.Message))"
        return
    }
    throw "FAILED: $Message (no exception)"
}
function Get-Status($Checks, [string]$Name) { @($Checks | Where-Object { $_.Name -eq $Name } | ForEach-Object { $_.Status }) }

$fullSchema = @('R|on') +
    (@('gsuif_project', 'gsuif_page', 'gsuif_metadata_version', 'gsuif_user', 'gsuif_role', 'gsuif_user_role', 'gsuif_generation_run',
        'gsuif_generated_artifact', 'gsuif_permission', 'gsuif_role_permission', 'work_orders') | ForEach-Object { "T|$_" }) +
    (@('compile_exit_code', 'test_exit_code', 'compile_output', 'test_output', 'attempt_id', 'input_fingerprint', 'failure_stage',
        'failure_code', 'package_manifest_sha256') | ForEach-Object { "C|gsuif_generation_run.$_" }) +
    @('C|gsuif_generated_artifact.relative_path', 'C|gsuif_generated_artifact.template_version',
        'U|gsuif_generation_run(attempt_id)', 'U|gsuif_generated_artifact(generation_run_id, relative_path)',
        "K|ck_gsuif_generation_run_status|CHECK (((status)::text = ANY ((ARRAY['SUCCESS'::character varying, 'BUILD_FAILED'::character varying, 'IN_PROGRESS'::character varying, 'FAILED'::character varying, 'INTERRUPTED'::character varying])::text[])))") +
    (@('GsuifProject', 'GsuifPage', 'MetadataVersion', 'WorkOrder') | ForEach-Object { "P|ENTITY_ACCESS:$_" })

$imageId = 'sha256:' + ('b' * 64)
$reference = 'eclipse-temurin@sha256:' + ('c' * 64)
function Reset-Fake {
    $global:GsuifFakeDocker = @{ Engine = $true; Images = @{ $reference = "$imageId|linux|amd64" }; Container = 'running';
        Schema = $fullSchema; PsqlInput = ''; PsqlArgs = ''; Started = $false }
}
$fake = {
    $state = $global:GsuifFakeDocker
    $global:LASTEXITCODE = 0
    $joined = $args -join ' '
    switch ($args[0]) {
        'version' { if ($state.Engine) { return 'linux|amd64' } else { $global:LASTEXITCODE = 1; return 'Cannot connect to the Docker daemon' } }
        'image' { $ref = $args[-1]; if ($state.Images.ContainsKey($ref)) { return $state.Images[$ref] }; $global:LASTEXITCODE = 1; return 'No such image' }
        'container' { if ($state.Container) { return $state.Container }; $global:LASTEXITCODE = 1; return 'No such container' }
        'start' { $state.Container = 'running'; $state.Started = $true; return $args[1] }
        'exec' {
            if ($args -contains 'pg_isready') { return }
            $state.PsqlInput = (@($input) -join "`n"); $state.PsqlArgs = $joined
            return $state.Schema
        }
    }
    $global:LASTEXITCODE = 99
    "unexpected docker call: $joined"
}

try {
    & $module { param($f) $script:DockerCommand = $f } $fake
    New-Item -ItemType Directory -Path $testRoot | Out-Null
    $env:APPDATA = Join-Path $testRoot 'appdata'
    New-Item -ItemType Directory -Path $env:APPDATA | Out-Null

    # Image lock
    $lockPath = Join-Path $testRoot 'lock.json'
    function Write-Lock($CompilerReference, $CompilerVariable = 'GSUIF_BACKEND_COMPILER_IMAGE_ID') {
        @{ schemaVersion = 1; images = @{
                compiler = @{ variable = $CompilerVariable; reference = $CompilerReference; reviewedTag = 'jdk:21' }
                build = @{ variable = 'GSUIF_BACKEND_BUILD_IMAGE_ID'; reference = $reference; reviewedTag = 'jdk:21' } } } |
            ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $lockPath
    }
    Write-Lock 'eclipse-temurin:21-jdk'
    Assert-Throws { Get-GsuifImageLock -Path $lockPath } 'pinned by digest' 'a tag-only reference is rejected'
    Write-Lock $reference 'OTHER_VARIABLE'
    Assert-Throws { Get-GsuifImageLock -Path $lockPath } 'must set GSUIF_BACKEND_COMPILER_IMAGE_ID' 'a wrong variable name is rejected'
    Write-Lock $reference
    $lock = Get-GsuifImageLock -Path $lockPath
    $real = Get-GsuifImageLock
    Assert-True ($real.compiler.reference -match '@sha256:[0-9a-f]{64}$' -and $real.build.reference -match '@sha256:[0-9a-f]{64}$') 'the committed lock pins both roles by digest'

    # Images resolve from the pinned reference only
    Reset-Fake
    $server = Get-GsuifDockerServer
    $images = Test-GsuifImages -Lock $lock -Server $server
    Assert-True ($images.Ids['GSUIF_BACKEND_COMPILER_IMAGE_ID'] -eq $imageId -and $images.Ids['GSUIF_BACKEND_BUILD_IMAGE_ID'] -eq $imageId) 'local IDs are resolved from the pinned reference'
    $global:GsuifFakeDocker.Images = @{}
    $images = Test-GsuifImages -Lock $lock -Server $server
    Assert-True ((Get-Status $images.Checks 'compiler image') -eq 'FAIL' -and $images.Checks[0].Fix -like '*Initialize-GsuifLocal*' -and $images.Ids.Count -eq 0) 'a missing image fails with the setup instruction'
    $global:GsuifFakeDocker.Images = @{ $reference = "$imageId|windows|amd64" }
    Assert-True ((Get-Status (Test-GsuifImages -Lock $lock -Server $server).Checks 'build image') -eq 'FAIL') 'a non-Linux image fails'
    $global:GsuifFakeDocker.Images = @{ $reference = 'eclipse-temurin:21-jdk|linux|amd64' }
    Assert-True ((Get-Status (Test-GsuifImages -Lock $lock -Server $server).Checks 'build image') -eq 'FAIL') 'a non-sha256 image ID fails'

    # Database: read-only inspection and marker evaluation
    $sqlDirectory = Join-Path $PSScriptRoot '..\sql'
    Reset-Fake
    $db = Test-GsuifDatabase -SqlDirectory $sqlDirectory -ReadySeconds 1
    Assert-True ((Get-Status $db 'Database schema') -eq 'OK' -and @($db | Where-Object { $_.Status -ne 'OK' }).Count -eq 0) 'a complete schema passes'
    Assert-True ($global:GsuifFakeDocker.PsqlArgs -like '*default_transaction_read_only=on*') 'psql runs in a read-only session'
    $statements = $global:GsuifFakeDocker.PsqlInput -replace "'[^']*'", "''"
    Assert-True ($statements -notmatch '(?i)\b(insert|update|delete|alter|drop|create|truncate|grant|copy)\b') 'the inspection SQL contains no write statements'
    $global:GsuifFakeDocker.Schema = @($fullSchema | Where-Object { $_ -ne 'C|gsuif_generation_run.package_manifest_sha256' }) +
        @("K|gsuif_generation_run_status_check|CHECK (((status)::text = ANY ((ARRAY['SUCCESS'::character varying, 'BUILD_FAILED'::character varying])::text[])))")
    $db = Test-GsuifDatabase -SqlDirectory $sqlDirectory -ReadySeconds 1
    $failure = $db | Where-Object { $_.Status -eq 'FAIL' }
    Assert-True ($failure.Message -like '*V9 (column gsuif_generation_run.package_manifest_sha256)*' -and $failure.Message -like '*V10*' -and $failure.Message -notlike '*V8*') 'missing V9 and a legacy check name V9 and V10 only'
    Assert-True ($failure.Fix -like '*V9__add_backend_package_manifest_hash.sql*' -and $failure.Fix -like '*never change the database*') 'the fix names the scripts and promises no changes'
    $global:GsuifFakeDocker.Schema = @($fullSchema | Where-Object { $_ -notlike 'P|*' -and $_ -ne 'U|gsuif_generated_artifact(generation_run_id, relative_path)' })
    $db = Test-GsuifDatabase -SqlDirectory $sqlDirectory -ReadySeconds 1
    Assert-True ((Get-Status $db 'Database schema') -eq 'OK' -and (Get-Status $db 'Database schema (indexes and seed rows)') -eq 'WARN') 'missing indexes and seed rows warn without blocking'
    $global:GsuifFakeDocker.Schema = @('R|off') + $fullSchema[1..($fullSchema.Count - 1)]
    Assert-True ((Get-Status (Test-GsuifDatabase -SqlDirectory $sqlDirectory -ReadySeconds 1) 'Database schema') -eq 'FAIL') 'a session that is not read-only is refused'
    Reset-Fake
    $global:GsuifFakeDocker.Container = 'exited'
    $db = Test-GsuifDatabase -SqlDirectory $sqlDirectory -ReadySeconds 1
    Assert-True ((Get-Status $db 'PostgreSQL container') -eq 'FAIL' -and $db[0].Fix -like '*docker start*' -and -not $global:GsuifFakeDocker.Started) 'a stopped container is not started unless asked'
    $db = Test-GsuifDatabase -SqlDirectory $sqlDirectory -ReadySeconds 1 -StartIfStopped
    Assert-True ($global:GsuifFakeDocker.Started -and (Get-Status $db 'Database schema') -eq 'OK') 'an existing stopped container is started when asked'
    $global:GsuifFakeDocker.Container = $null
    $db = Test-GsuifDatabase -SqlDirectory $sqlDirectory -ReadySeconds 1 -StartIfStopped
    Assert-True ($db[0].Status -eq 'FAIL' -and $db[0].Fix -like '*docker compose up -d postgres*' -and $db[0].Fix -like '*never create*') 'a missing container is reported, not created'

    # Machine settings and Maven
    Assert-True ($null -eq (Read-GsuifMachineSettings)) 'no machine settings in a fresh profile'
    Assert-True ((Get-Status (Test-GsuifMaven -Settings $null) 'Maven settings') -eq 'FAIL') 'missing machine settings fail'
    $mavenHome = Join-Path $testRoot 'maven'
    New-Item -ItemType Directory -Path (Join-Path $mavenHome 'bin') | Out-Null
    Set-Content -LiteralPath (Join-Path $mavenHome 'bin\mvn.cmd') -Value '@echo off'
    $repository = Join-Path $testRoot 'repository'
    New-Item -ItemType Directory -Path $repository | Out-Null
    $settingsPath = Write-GsuifMachineSettings -MavenCommand (Join-Path $mavenHome 'bin\mvn.cmd') -MavenRepository $repository
    Assert-True ($settingsPath.StartsWith($env:APPDATA)) 'machine settings are written under APPDATA'
    $maven = Test-GsuifMaven -Settings (Read-GsuifMachineSettings)
    Assert-True ((Get-Status $maven 'Maven command') -eq 'FAIL') 'an installation without bin/mvn fails'
    Set-Content -LiteralPath (Join-Path $mavenHome 'bin\mvn') -Value '#!/bin/sh'
    $maven = Test-GsuifMaven -Settings (Read-GsuifMachineSettings)
    Assert-True ((Get-Status $maven 'Maven command') -eq 'OK' -and (Get-Status $maven 'Offline build cache') -eq 'WARN') 'an empty repository warns about the offline cache'
    foreach ($artifact in Get-GsuifTemplateArtifacts) {
        $folder = Join-Path $repository (($artifact.Group -replace '\.', '\') + "\$($artifact.Artifact)\$($artifact.Version)")
        New-Item -ItemType Directory -Path $folder -Force | Out-Null
        Set-Content -LiteralPath (Join-Path $folder "$($artifact.Artifact)-$($artifact.Version).pom") -Value '<project/>'
    }
    Assert-True ((Get-Status (Test-GsuifMaven -Settings (Read-GsuifMachineSettings)) 'Offline build cache') -eq 'OK') 'pinned parent and plugin POMs satisfy the indicative cache check'
    $environment = Get-GsuifGenerationEnvironment -ImageIds @{ GSUIF_BACKEND_COMPILER_IMAGE_ID = $imageId; GSUIF_BACKEND_BUILD_IMAGE_ID = $imageId }
    Assert-True (($environment.Keys | Sort-Object) -join ',' -eq 'GSUIF_BACKEND_BUILD_IMAGE_ID,GSUIF_BACKEND_COMPILER_IMAGE_ID,GSUIF_MAVEN_COMMAND,GSUIF_MAVEN_REPOSITORY') 'the launcher receives only non-secret variables'
    Assert-Throws { Get-GsuifGenerationEnvironment -ImageIds @{ GSUIF_BACKEND_COMPILER_IMAGE_ID = $imageId } } 'could not be resolved' 'an unresolved image ID is refused'
    $env:APPDATA = Join-Path $PSScriptRoot '..\target\appdata'
    Assert-Throws { Get-GsuifMachineSettingsPath } 'outside this repository' 'machine settings inside the repository are refused'
    $env:APPDATA = Join-Path $testRoot 'appdata'

    # Saved key: presence and decryption only
    Assert-True ((Test-GsuifSavedKey).Status -eq 'FAIL') 'a missing key fails'
    New-Item -ItemType Directory -Path (Join-Path $env:APPDATA 'GSUIF') -Force | Out-Null
    Set-Content -LiteralPath (Get-GsuifSecretPath) -Value 'not-dpapi' -NoNewline
    Assert-True ((Test-GsuifSavedKey).Status -eq 'FAIL') 'an undecryptable key fails'
    ConvertTo-SecureString $secretValue -AsPlainText -Force | ConvertFrom-SecureString | Set-Content -LiteralPath (Get-GsuifSecretPath) -NoNewline
    $key = Test-GsuifSavedKey
    Assert-True ($key.Status -eq 'OK') 'a saved DPAPI key passes'

    # Model configuration
    $modelPath = Join-Path $testRoot 'openrouter.local.ps1'
    $env:OPENROUTER_MODEL = 'caller-model'
    Set-Content -LiteralPath $modelPath -Value '$env:OPENROUTER_MODEL = "other/model"'
    Assert-True ((Test-GsuifModelConfig -ConfigPath $modelPath).Status -eq 'FAIL') 'a different model fails'
    Set-Content -LiteralPath $modelPath -Value ('$env:OPENROUTER_MODEL = "qwen/qwen3-coder"; $env:OPENROUTER_API_KEY = "' + $secretValue + '"')
    $model = Test-GsuifModelConfig -ConfigPath $modelPath
    Assert-True ($model.Status -eq 'FAIL' -and $model.Message -like '*sets OPENROUTER_API_KEY*') 'a key in the model file fails'
    Set-Content -LiteralPath $modelPath -Value '$env:OPENROUTER_MODEL = "qwen/qwen3-coder"'
    Assert-True ((Test-GsuifModelConfig -ConfigPath $modelPath).Status -eq 'OK') 'the pinned model passes'
    Assert-True ($env:OPENROUTER_MODEL -eq 'caller-model' -and -not (Test-Path Env:OPENROUTER_API_KEY)) 'evaluating the model file restores the caller environment'
    Assert-True ((Test-GsuifModelConfig -ConfigPath (Join-Path $testRoot 'absent.ps1')).Fix -like '*Initialize-GsuifLocal*') 'a missing model file points to setup'

    # Preflight scope
    Reset-Fake
    $core = Invoke-GsuifPreflight -SkipPort -WithoutGeneration
    Assert-True (@($core.Checks | Where-Object { $_.Area -eq 'Generation' }).Count -eq 0) '-WithoutGeneration runs no generation checks'
    $global:GsuifFakeDocker.Engine = $false
    $down = Invoke-GsuifPreflight -SkipPort
    Assert-True ($down.Failed -and (Get-Status $down.Checks 'Docker') -eq 'FAIL' -and (Get-Status $down.Checks 'Images') -eq 'FAIL') 'an unreachable engine fails and skips image checks'

    # No check output may contain the disposable secret.
    $all = @($images.Checks) + @($db) + @($maven) + @($key) + @($model) + @($core.Checks) + @($down.Checks)
    $text = ($all | ForEach-Object { "$($_.Message) $($_.Fix)" }) -join "`n"
    $printed = (Write-GsuifChecks $all) -join "`n"
    Assert-True (-not $text.Contains($secretValue) -and -not $printed.Contains($secretValue)) 'no check message or fix contains the secret'
    Write-Output "GSUIF local startup helpers: $script:passed disposable-value checks passed"
} finally {
    & $module { $script:DockerCommand = 'docker' }
    Remove-Variable -Name GsuifFakeDocker -Scope Global -ErrorAction SilentlyContinue
    $env:APPDATA = $originalAppData
    [Environment]::SetEnvironmentVariable('OPENROUTER_MODEL', $originalModel, 'Process')
    Remove-Item Env:OPENROUTER_API_KEY -ErrorAction SilentlyContinue
    $resolved = [IO.Path]::GetFullPath($testRoot)
    if ($resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()), [StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $resolved)) {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
