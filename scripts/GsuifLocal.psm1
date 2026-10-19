# Shared checks for Initialize-GsuifLocal.ps1 and Start-GsuifLocal.ps1 (Windows PowerShell 5.1+).
# Nothing here prints, logs or stores the OpenRouter key. The key stays in the DPAPI store
# written by Save-OpenRouterKey.ps1 and is decrypted only by Invoke-OpenRouterLocal.ps1.
$ErrorActionPreference = 'Stop'

$script:RepositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
# Replaced by a script block in Test-GsuifLocal.ps1.
$script:DockerCommand = 'docker'
$script:ImageIdPattern = '^sha256:[0-9a-f]{64}$'
$script:ReferencePattern = '^[a-z0-9][a-z0-9._/-]*(:[A-Za-z0-9._-]+)?@sha256:[0-9a-f]{64}$'
$script:GenerationVariables = @('OPENROUTER_API_KEY', 'OPENROUTER_MODEL', 'GSUIF_BACKEND_COMPILER_IMAGE_ID',
    'GSUIF_BACKEND_BUILD_IMAGE_ID', 'GSUIF_MAVEN_COMMAND', 'GSUIF_MAVEN_REPOSITORY')

# Markers left by each sql/V*.sql script. Without migration history these show that the
# expected objects exist, not that each script ran exactly as written. Missing tables, columns
# or the status check break startup or generation (FAIL); unique indexes and seed rows are not
# validated by Hibernate and are reported as WARN. Unique indexes are matched by columns, not name.
$script:SchemaMarkers = @(
    @{ Version = 1; Tables = @('gsuif_project', 'gsuif_page', 'gsuif_metadata_version', 'gsuif_user', 'gsuif_role',
            'gsuif_user_role', 'gsuif_generation_run', 'gsuif_generated_artifact') },
    @{ Version = 2; Columns = @('gsuif_generation_run.compile_exit_code', 'gsuif_generation_run.test_exit_code',
            'gsuif_generation_run.compile_output', 'gsuif_generation_run.test_output') },
    @{ Version = 3; Columns = @('gsuif_generation_run.attempt_id', 'gsuif_generated_artifact.relative_path',
            'gsuif_generated_artifact.template_version');
        Unique = @('gsuif_generation_run(attempt_id)', 'gsuif_generated_artifact(generation_run_id, relative_path)') },
    @{ Version = 4; Columns = @('gsuif_generation_run.input_fingerprint') },
    @{ Version = 5; Tables = @('gsuif_permission', 'gsuif_role_permission') },
    @{ Version = 6; Tables = @('work_orders') },
    @{ Version = 7; Permissions = @('ENTITY_ACCESS:GsuifProject', 'ENTITY_ACCESS:GsuifPage',
            'ENTITY_ACCESS:MetadataVersion', 'ENTITY_ACCESS:WorkOrder') },
    @{ Version = 8; Columns = @('gsuif_generation_run.failure_stage', 'gsuif_generation_run.failure_code');
        StatusCheck = 'lifecycle' },
    @{ Version = 9; Columns = @('gsuif_generation_run.package_manifest_sha256') },
    @{ Version = 10; StatusCheck = 'repaired' }
)

function New-GsuifCheck {
    param(
        [Parameter(Mandatory = $true)][string]$Area,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][ValidateSet('OK', 'WARN', 'FAIL')][string]$Status,
        [string]$Message = '',
        [string]$Fix = ''
    )
    [pscustomobject]@{ Area = $Area; Name = $Name; Status = $Status; Message = $Message; Fix = $Fix }
}

function Write-GsuifChecks {
    param([Parameter(Mandatory = $true)][object[]]$Checks)
    foreach ($check in $Checks) {
        $label = switch ($check.Status) { 'OK' { '[ OK ]' } 'WARN' { '[WARN]' } default { '[FAIL]' } }
        $line = "$label $($check.Area): $($check.Name)"
        if ($check.Message) { $line += " - $($check.Message)" }
        Write-Output $line
        if ($check.Status -ne 'OK' -and $check.Fix) { Write-Output "       Fix: $($check.Fix)" }
    }
}

function Invoke-GsuifDocker {
    param([Parameter(Mandatory = $true)][string[]]$Arguments, [string]$InputText)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $global:LASTEXITCODE = 0
    try {
        if ($PSBoundParameters.ContainsKey('InputText')) {
            $output = $InputText | & $script:DockerCommand @Arguments 2>&1
        } else {
            $output = & $script:DockerCommand @Arguments 2>&1
        }
        $code = $LASTEXITCODE
    } catch {
        $output = @($_.Exception.Message)
        $code = 127
    } finally {
        $ErrorActionPreference = $previous
    }
    [pscustomobject]@{ ExitCode = [int]$code; Output = ((@($output) | ForEach-Object { "$_" }) -join "`n").Trim() }
}

function Get-GsuifPinnedModel {
    param([string]$SourcePath = (Join-Path $script:RepositoryRoot 'src\main\java\eg\mts\gsuif\generation\OpenRouterBackendModelAdapter.java'))
    $match = [regex]::Match((Get-Content -LiteralPath $SourcePath -Raw), 'static final String MODEL = "([^"]+)";')
    if (-not $match.Success) { throw "Could not read the pinned model from $SourcePath." }
    $match.Groups[1].Value
}

function Get-GsuifImageLock {
    param([string]$Path = (Join-Path $script:RepositoryRoot 'config\docker-images.lock.json'))
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Image lock file not found: $Path." }
    $lock = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    if ($lock.schemaVersion -ne 1) { throw "Unsupported image lock schemaVersion in $Path." }
    $expected = @{ compiler = 'GSUIF_BACKEND_COMPILER_IMAGE_ID'; build = 'GSUIF_BACKEND_BUILD_IMAGE_ID' }
    $roles = [ordered]@{}
    foreach ($role in @('compiler', 'build')) {
        $entry = $lock.images.$role
        if ($null -eq $entry) { throw "The image lock has no '$role' entry." }
        if ($entry.variable -cne $expected[$role]) { throw "The '$role' image must set $($expected[$role])." }
        if ([string]$entry.reference -cnotmatch $script:ReferencePattern) {
            throw "The '$role' image reference must be pinned by digest (name@sha256:<64 hex>), not a tag."
        }
        $roles[$role] = $entry
    }
    $roles
}

function Get-GsuifDockerServer {
    $result = Invoke-GsuifDocker -Arguments @('version', '--format', '{{.Server.Os}}|{{.Server.Arch}}')
    $parts = $result.Output.Split('|')
    if ($result.ExitCode -ne 0 -or $parts.Count -ne 2) { return [pscustomobject]@{ Reachable = $false; Os = ''; Arch = '' } }
    [pscustomobject]@{ Reachable = $true; Os = $parts[0].Trim(); Arch = $parts[1].Trim() }
}

function Resolve-GsuifImage {
    param([Parameter(Mandatory = $true)][string]$Reference)
    $result = Invoke-GsuifDocker -Arguments @('image', 'inspect', '--format', '{{.Id}}|{{.Os}}|{{.Architecture}}', $Reference)
    $parts = $result.Output.Split('|')
    if ($result.ExitCode -ne 0 -or $parts.Count -ne 3) {
        return [pscustomobject]@{ Present = $false; Id = ''; Os = ''; Architecture = '' }
    }
    [pscustomobject]@{ Present = $true; Id = $parts[0].Trim(); Os = $parts[1].Trim(); Architecture = $parts[2].Trim() }
}

function Short-GsuifId([string]$Id) { if ($Id.Length -ge 19) { $Id.Substring(0, 19) } else { $Id } }

function Test-GsuifImages {
    param([Parameter(Mandatory = $true)]$Lock, [Parameter(Mandatory = $true)]$Server)
    $checks = @()
    $ids = @{}
    foreach ($role in $Lock.Keys) {
        $entry = $Lock[$role]
        $image = Resolve-GsuifImage -Reference $entry.reference
        $name = "$role image"
        if (-not $image.Present) {
            $checks += New-GsuifCheck 'Generation' $name 'FAIL' "The pinned image is not present locally ($($entry.reviewedTag))." `
                'Run .\scripts\Initialize-GsuifLocal.ps1 once; it pulls the pinned digest. Startup never pulls images.'
        } elseif ($image.Id -cnotmatch $script:ImageIdPattern) {
            $checks += New-GsuifCheck 'Generation' $name 'FAIL' 'Docker did not return an immutable sha256 image ID.' `
                'Check the Docker installation, then re-run .\scripts\Initialize-GsuifLocal.ps1.'
        } elseif ($image.Os -ne 'linux' -or ($Server.Arch -and $image.Architecture -ne $Server.Arch)) {
            $checks += New-GsuifCheck 'Generation' $name 'FAIL' "The local image is $($image.Os)/$($image.Architecture), the Docker engine is linux/$($Server.Arch)." `
                'Switch Docker Desktop to Linux containers, remove the mismatched copy and re-run .\scripts\Initialize-GsuifLocal.ps1.'
        } else {
            $ids[$entry.variable] = $image.Id
            $checks += New-GsuifCheck 'Generation' $name 'OK' "$($entry.reviewedTag) resolved to $(Short-GsuifId $image.Id) ($($image.Os)/$($image.Architecture))."
        }
    }
    [pscustomobject]@{ Checks = $checks; Ids = $ids }
}

function Get-GsuifSecretPath {
    if ([string]::IsNullOrWhiteSpace($env:APPDATA)) { throw 'APPDATA is required.' }
    Join-Path (Join-Path $env:APPDATA 'GSUIF') 'openrouter-key.dpapi'
}

function Get-GsuifMachineSettingsPath {
    if ([string]::IsNullOrWhiteSpace($env:APPDATA)) { throw 'APPDATA is required.' }
    $path = Join-Path (Join-Path $env:APPDATA 'GSUIF') 'backend-local.json'
    $root = $script:RepositoryRoot.TrimEnd('\') + '\'
    if ([IO.Path]::GetFullPath($path).StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Machine settings must be stored outside this repository.'
    }
    $path
}

function Read-GsuifMachineSettings {
    $path = Get-GsuifMachineSettingsPath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $null }
    $settings = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    if ($settings.schemaVersion -ne 1) { throw "Unsupported machine settings file: $path." }
    $settings
}

function Write-GsuifMachineSettings {
    param([Parameter(Mandatory = $true)][string]$MavenCommand, [Parameter(Mandatory = $true)][string]$MavenRepository)
    $path = Get-GsuifMachineSettingsPath
    $directory = Split-Path -Parent $path
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $json = [ordered]@{ schemaVersion = 1; mavenCommand = $MavenCommand; mavenRepository = $MavenRepository } | ConvertTo-Json
    $temporary = Join-Path $directory ([guid]::NewGuid().ToString() + '.json')
    try {
        [IO.File]::WriteAllText($temporary, $json, (New-Object Text.UTF8Encoding $false))
        if (Test-Path -LiteralPath $path) { [IO.File]::Replace($temporary, $path, $null) } else { [IO.File]::Move($temporary, $path) }
    } finally {
        Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
    }
    $path
}

function Find-GsuifWrapperMaven {
    $properties = Join-Path $script:RepositoryRoot '.mvn\wrapper\maven-wrapper.properties'
    $match = [regex]::Match((Get-Content -LiteralPath $properties -Raw), 'apache-maven-(\d+\.\d+\.\d+)-bin\.zip')
    if (-not $match.Success -or [string]::IsNullOrWhiteSpace($env:USERPROFILE)) { return $null }
    $dists = Join-Path $env:USERPROFILE ".m2\wrapper\dists\apache-maven-$($match.Groups[1].Value)"
    if (-not (Test-Path -LiteralPath $dists -PathType Container)) { return $null }
    $found = Get-ChildItem -LiteralPath $dists -Directory | ForEach-Object { Join-Path $_.FullName 'bin\mvn.cmd' } |
        Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
        Sort-Object { (Get-Item -LiteralPath $_).LastWriteTimeUtc } -Descending | Select-Object -First 1
    $found
}

function Get-GsuifMavenHome([string]$MavenCommand) {
    $bin = Split-Path -Parent ([IO.Path]::GetFullPath($MavenCommand))
    if (-not $bin) { return $null }
    Split-Path -Parent $bin
}

# Artifacts the offline package build needs that the trusted pre-request provisioning does not resolve.
function Get-GsuifTemplateArtifacts {
    param([string]$TemplatePath = (Join-Path $script:RepositoryRoot 'src\main\resources\templates\complete-backend\runtime-pom.xml.ftl'))
    $text = Get-Content -LiteralPath $TemplatePath -Raw
    $artifacts = @()
    $parentVersion = $null
    foreach ($m in [regex]::Matches($text, '<groupId>([^<$]+)</groupId>\s*<artifactId>([^<$]+)</artifactId>\s*<version>([^<$]+)</version>')) {
        $artifacts += [pscustomobject]@{ Group = $m.Groups[1].Value; Artifact = $m.Groups[2].Value; Version = $m.Groups[3].Value }
        if ($m.Groups[2].Value -eq 'spring-boot-starter-parent') { $parentVersion = $m.Groups[3].Value }
    }
    if ($parentVersion -and $text.Contains('<artifactId>spring-boot-maven-plugin</artifactId>')) {
        $artifacts += [pscustomobject]@{ Group = 'org.springframework.boot'; Artifact = 'spring-boot-maven-plugin'; Version = $parentVersion }
    }
    $artifacts | Sort-Object Group, Artifact, Version -Unique
}

function Test-GsuifMaven {
    param($Settings, [string]$TemplatePath)
    $fix = 'Run .\scripts\Initialize-GsuifLocal.ps1 once (optionally with -MavenCommand <path to bin\mvn.cmd>).'
    if ($null -eq $Settings) {
        return @(New-GsuifCheck 'Generation' 'Maven settings' 'FAIL' "No machine settings at $(Get-GsuifMachineSettingsPath)." $fix)
    }
    $checks = @()
    $command = [string]$Settings.mavenCommand
    $repository = [string]$Settings.mavenRepository
    $mavenHome = if ($command) { Get-GsuifMavenHome $command } else { $null }
    if (-not $command -or -not (Test-Path -LiteralPath $command -PathType Leaf)) {
        $checks += New-GsuifCheck 'Generation' 'Maven command' 'FAIL' "Not found: $command" $fix
    } elseif (-not $mavenHome -or -not (Test-Path -LiteralPath (Join-Path $mavenHome 'bin\mvn') -PathType Leaf)) {
        $checks += New-GsuifCheck 'Generation' 'Maven command' 'FAIL' 'The installation has no bin/mvn script for the Linux build container.' `
            'Point -MavenCommand at a complete Apache Maven distribution and re-run .\scripts\Initialize-GsuifLocal.ps1.'
    } else {
        $checks += New-GsuifCheck 'Generation' 'Maven command' 'OK' $command
    }
    if (-not $repository -or -not (Test-Path -LiteralPath $repository -PathType Container)) {
        $checks += New-GsuifCheck 'Generation' 'Maven repository' 'FAIL' "Not found: $repository" $fix
        return $checks
    }
    $checks += New-GsuifCheck 'Generation' 'Maven repository' 'OK' $repository
    $arguments = @{}
    if ($TemplatePath) { $arguments.TemplatePath = $TemplatePath }
    $missing = @(Get-GsuifTemplateArtifacts @arguments | Where-Object {
            $folder = Join-Path $repository (($_.Group -replace '\.', '\') + "\$($_.Artifact)\$($_.Version)")
            -not (Test-Path -LiteralPath (Join-Path $folder "$($_.Artifact)-$($_.Version).pom") -PathType Leaf)
        } | ForEach-Object { "$($_.Group):$($_.Artifact):$($_.Version)" })
    if ($missing.Count -gt 0) {
        $checks += New-GsuifCheck 'Generation' 'Offline build cache' 'WARN' "Missing from the repository: $($missing -join ', ')." `
            'The final package build runs offline (-o). Build a project with these versions once using this repository, or populate it deliberately.'
    } else {
        $checks += New-GsuifCheck 'Generation' 'Offline build cache' 'OK' 'Pinned parent and plugin versions are present (indicative check only).'
    }
    $checks
}

function Test-GsuifSavedKey {
    $path = Get-GsuifSecretPath
    $fix = 'Run .\scripts\Initialize-GsuifLocal.ps1 (or .\scripts\Save-OpenRouterKey.ps1) from this Windows account.'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        return New-GsuifCheck 'Generation' 'OpenRouter key' 'FAIL' 'No saved key for this Windows user.' $fix
    }
    $secure = $null
    try {
        $secure = Get-Content -LiteralPath $path -Raw | ConvertTo-SecureString
        if ($secure.Length -eq 0) { return New-GsuifCheck 'Generation' 'OpenRouter key' 'FAIL' 'The saved key is empty.' $fix }
        New-GsuifCheck 'Generation' 'OpenRouter key' 'OK' 'Saved key decrypts for this Windows user (value not shown).'
    } catch {
        New-GsuifCheck 'Generation' 'OpenRouter key' 'FAIL' 'The saved key cannot be decrypted by this Windows user.' "Save it again: $fix"
    } finally {
        if ($null -ne $secure) { $secure.Dispose() }
    }
}

function Test-GsuifModelConfig {
    param([string]$ConfigPath = (Join-Path $script:RepositoryRoot 'config\openrouter.local.ps1'), [string]$PinnedModel = (Get-GsuifPinnedModel))
    if (-not (Test-Path -LiteralPath $ConfigPath -PathType Leaf)) {
        return New-GsuifCheck 'Generation' 'Model configuration' 'FAIL' 'config\openrouter.local.ps1 is missing.' `
            'Run .\scripts\Initialize-GsuifLocal.ps1 once; it copies config\openrouter.local.example.ps1 (ignored by Git).'
    }
    # Evaluate the ignored local file as the launcher does, then restore the caller's environment.
    $saved = @{}
    foreach ($name in @('OPENROUTER_MODEL', 'OPENROUTER_API_KEY', 'GSUIF_QUALIFICATION_IMAGE')) {
        $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $null, 'Process')
    }
    try {
        . $ConfigPath
        $model = $env:OPENROUTER_MODEL
        $setsKey = -not [string]::IsNullOrEmpty($env:OPENROUTER_API_KEY)
    } finally {
        foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
    }
    if ($setsKey) {
        return New-GsuifCheck 'Generation' 'Model configuration' 'FAIL' 'config\openrouter.local.ps1 sets OPENROUTER_API_KEY.' `
            'Remove the key from that file; the launcher reads it only from the encrypted store.'
    }
    if ($model -cne $PinnedModel) {
        return New-GsuifCheck 'Generation' 'Model configuration' 'FAIL' "OPENROUTER_MODEL must be exactly $PinnedModel." `
            "Set `$env:OPENROUTER_MODEL = '$PinnedModel' in config\openrouter.local.ps1."
    }
    New-GsuifCheck 'Generation' 'Model configuration' 'OK' $PinnedModel
}

function Test-GsuifHostJava {
    $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $output = (& $java -version 2>&1 | ForEach-Object { "$_" }) -join "`n" } catch { $output = '' }
    finally { $ErrorActionPreference = $previous }
    $match = [regex]::Match($output, 'version "(\d+)')
    if (-not $match.Success) {
        return New-GsuifCheck 'Core' 'Host Java' 'FAIL' 'No Java runtime found for the Maven wrapper.' 'Install JDK 21 and set JAVA_HOME.'
    }
    if ([int]$match.Groups[1].Value -lt 21) {
        return New-GsuifCheck 'Core' 'Host Java' 'FAIL' "Java $($match.Groups[1].Value) found; GSUIF needs 21 or later." 'Install JDK 21 and set JAVA_HOME.'
    }
    New-GsuifCheck 'Core' 'Host Java' 'OK' "Java $($match.Groups[1].Value)"
}

function Test-GsuifPort {
    param([int]$Port)
    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $listener) { return New-GsuifCheck 'Core' "Port $Port" 'OK' 'Free.' }
    $process = Get-Process -Id $listener.OwningProcess -ErrorAction SilentlyContinue
    $owner = if ($process) { "$($process.ProcessName) (PID $($process.Id))" } else { "PID $($listener.OwningProcess)" }
    New-GsuifCheck 'Core' "Port $Port" 'FAIL' "Already in use by $owner." 'Stop that process (for example a backend started from the IDE), or pass -Port <free port>.'
}

function Get-GsuifSchemaSnapshot {
    param([string]$Container, [string]$Database, [string]$User)
    $sql = @"
SELECT 'R|' || current_setting('default_transaction_read_only');
SELECT 'T|' || table_name FROM information_schema.tables WHERE table_schema = 'public';
SELECT 'C|' || table_name || '.' || column_name FROM information_schema.columns WHERE table_schema = 'public';
SELECT 'U|' || tablename || '(' || substring(indexdef from '\((.*)\)$') || ')' FROM pg_indexes WHERE schemaname = 'public' AND indexdef LIKE 'CREATE UNIQUE INDEX %';
SELECT 'K|' || c.conname || '|' || pg_get_constraintdef(c.oid) FROM pg_constraint c JOIN pg_class r ON r.oid = c.conrelid JOIN pg_namespace n ON n.oid = r.relnamespace WHERE n.nspname = 'public' AND r.relname = 'gsuif_generation_run' AND c.contype = 'c';
SELECT to_regclass('public.gsuif_permission') IS NOT NULL AS has_permission_table \gset
\if :has_permission_table
SELECT 'P|' || code FROM public.gsuif_permission WHERE code LIKE 'ENTITY_ACCESS:%';
\endif
"@
    $result = Invoke-GsuifDocker -InputText $sql -Arguments @('exec', '-i', '-e', 'PGOPTIONS=-c default_transaction_read_only=on',
        $Container, 'psql', '-X', '-q', '-At', '-v', 'ON_ERROR_STOP=1', '-U', $User, '-d', $Database, '-f', '-')
    if ($result.ExitCode -ne 0) { return $null }
    $snapshot = @{ ReadOnly = ''; Tables = @{}; Columns = @{}; Unique = @{}; Permissions = @{}; StatusChecks = @() }
    foreach ($line in $result.Output -split "`r?`n") {
        $parts = $line.Split('|', 2)
        if ($parts.Count -lt 2) { continue }
        switch ($parts[0]) {
            'R' { $snapshot.ReadOnly = $parts[1] }
            'T' { $snapshot.Tables[$parts[1]] = $true }
            'C' { $snapshot.Columns[$parts[1]] = $true }
            'U' { $snapshot.Unique[$parts[1]] = $true }
            'P' { $snapshot.Permissions[$parts[1]] = $true }
            'K' {
                $constraint = $parts[1].Split('|', 2)
                if ($constraint.Count -eq 2 -and $constraint[1] -match '\bstatus\b') {
                    $snapshot.StatusChecks += [pscustomobject]@{ Name = $constraint[0]; Definition = $constraint[1] }
                }
            }
        }
    }
    $snapshot
}

function Get-GsuifSchemaGaps {
    param([Parameter(Mandatory = $true)]$Snapshot)
    $lifecycle = @('IN_PROGRESS', 'FAILED', 'INTERRUPTED')
    $all = @('SUCCESS', 'BUILD_FAILED') + $lifecycle
    $gaps = @()
    foreach ($marker in $script:SchemaMarkers) {
        $required = @()
        $advisory = @()
        foreach ($table in @($marker.Tables)) { if ($table -and -not $Snapshot.Tables[$table]) { $required += "table $table" } }
        foreach ($column in @($marker.Columns)) { if ($column -and -not $Snapshot.Columns[$column]) { $required += "column $column" } }
        foreach ($unique in @($marker.Unique)) { if ($unique -and -not $Snapshot.Unique[$unique]) { $advisory += "unique index on $unique" } }
        foreach ($code in @($marker.Permissions)) { if ($code -and -not $Snapshot.Permissions[$code]) { $advisory += "permission row $code" } }
        if ($marker.StatusCheck -eq 'lifecycle') {
            $allowsLifecycle = @($Snapshot.StatusChecks | Where-Object { $d = $_.Definition; @($lifecycle | Where-Object { $d -notmatch "'$_'" }).Count -eq 0 })
            if ($allowsLifecycle.Count -eq 0) { $required += 'status check allowing IN_PROGRESS, FAILED and INTERRUPTED' }
        }
        if ($marker.StatusCheck -eq 'repaired') {
            $checks = @($Snapshot.StatusChecks)
            $named = @($checks | Where-Object { $_.Name -eq 'ck_gsuif_generation_run_status' })
            $complete = $named.Count -eq 1 -and @($all | Where-Object { $named[0].Definition -notmatch "'$_'" }).Count -eq 0
            if ($checks.Count -ne 1 -or -not $complete) { $required += 'a single ck_gsuif_generation_run_status check allowing all five statuses' }
        }
        if ($required.Count -gt 0) { $gaps += [pscustomobject]@{ Version = $marker.Version; Severity = 'FAIL'; Missing = $required } }
        if ($advisory.Count -gt 0) { $gaps += [pscustomobject]@{ Version = $marker.Version; Severity = 'WARN'; Missing = $advisory } }
    }
    $gaps
}

function Test-GsuifDatabase {
    param([string]$Container = 'gsuif-postgres', [string]$Database = 'gsuif_db', [string]$User = 'gsuif_user',
        [switch]$StartIfStopped, [string]$SqlDirectory = (Join-Path $script:RepositoryRoot 'sql'), [int]$ReadySeconds = 30)
    $checks = @()
    $state = Invoke-GsuifDocker -Arguments @('container', 'inspect', '--format', '{{.State.Status}}', $Container)
    if ($state.ExitCode -ne 0) {
        return @(New-GsuifCheck 'Core' 'PostgreSQL container' 'FAIL' "No '$Container' container exists." `
            'Create it with: docker compose up -d postgres. A new volume is an empty database: apply sql\V1..V10 as described in README.md first. These scripts never create, migrate or reset databases.')
    }
    if ($state.Output -ne 'running') {
        if (-not $StartIfStopped) {
            return @(New-GsuifCheck 'Core' 'PostgreSQL container' 'FAIL' "'$Container' is $($state.Output)." "Start it with: docker start $Container")
        }
        $started = Invoke-GsuifDocker -Arguments @('start', $Container)
        if ($started.ExitCode -ne 0) {
            return @(New-GsuifCheck 'Core' 'PostgreSQL container' 'FAIL' "'$Container' could not be started." 'Check it with: docker logs gsuif-postgres')
        }
        $checks += New-GsuifCheck 'Core' 'PostgreSQL container' 'OK' "Started the existing stopped '$Container' container (same volume)."
    } else {
        $checks += New-GsuifCheck 'Core' 'PostgreSQL container' 'OK' "'$Container' is running."
    }
    $ready = $false
    for ($i = 0; $i -lt [Math]::Max(1, $ReadySeconds); $i++) {
        if ((Invoke-GsuifDocker -Arguments @('exec', $Container, 'pg_isready', '-q', '-U', $User, '-d', $Database)).ExitCode -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) {
        return $checks + (New-GsuifCheck 'Core' 'PostgreSQL ready' 'FAIL' "Database '$Database' did not accept connections within $ReadySeconds s." 'Check: docker logs gsuif-postgres')
    }
    $snapshot = Get-GsuifSchemaSnapshot -Container $Container -Database $Database -User $User
    if ($null -eq $snapshot -or $snapshot.ReadOnly -ne 'on') {
        return $checks + (New-GsuifCheck 'Core' 'Database schema' 'FAIL' 'The read-only schema inspection could not run.' "Check the database and user: docker exec $Container psql -U $User -d $Database -c 'select 1'")
    }
    $known = @($script:SchemaMarkers | ForEach-Object { $_.Version })
    $unknown = @(Get-ChildItem -LiteralPath $SqlDirectory -Filter 'V*__*.sql' | ForEach-Object {
            [int]([regex]::Match($_.Name, '^V(\d+)__').Groups[1].Value) } | Where-Object { $known -notcontains $_ } | Sort-Object)
    $gaps = @(Get-GsuifSchemaGaps -Snapshot $snapshot)
    $latest = ($known | Measure-Object -Maximum).Maximum
    if (@($gaps | Where-Object { $_.Severity -eq 'FAIL' }).Count -eq 0) {
        $scope = if ($gaps.Count -eq 0) { 'all markers' } else { 'required tables, columns and status check' }
        $checks += New-GsuifCheck 'Core' 'Database schema' 'OK' "Read-only check: $scope of V1-V$latest present."
    }
    foreach ($severity in @('FAIL', 'WARN')) {
        $selected = @($gaps | Where-Object { $_.Severity -eq $severity })
        if ($selected.Count -eq 0) { continue }
        $detail = ($selected | ForEach-Object { "V$($_.Version) ($($_.Missing -join '; '))" }) -join ', '
        $files = ($selected | ForEach-Object { $v = $_.Version; (Get-ChildItem -LiteralPath $SqlDirectory -Filter "V${v}__*.sql" | Select-Object -First 1).Name }) -join ', '
        $name = if ($severity -eq 'FAIL') { 'Database schema' } else { 'Database schema (indexes and seed rows)' }
        $checks += New-GsuifCheck 'Core' $name $severity "Read-only check found missing markers for $detail." `
            "Inspect $files and apply only what is missing, deliberately and in order (see README.md). These scripts never change the database."
    }
    if ($unknown.Count -gt 0) {
        $checks += New-GsuifCheck 'Core' 'Database schema' 'WARN' "No markers are defined for V$($unknown -join ', V'); those migrations were not checked." `
            'Add markers for them to scripts\GsuifLocal.psm1.'
    }
    $checks
}

function Invoke-GsuifPreflight {
    param([int]$Port = 8080, [switch]$WithoutGeneration, [switch]$StartDatabase, [switch]$SkipPort)
    $checks = @()
    $server = Get-GsuifDockerServer
    if (-not $server.Reachable) {
        $checks += New-GsuifCheck 'Core' 'Docker' 'FAIL' 'The Docker engine is not reachable.' 'Start Docker Desktop and wait until it reports that the engine is running.'
    } elseif ($server.Os -ne 'linux') {
        $checks += New-GsuifCheck 'Core' 'Docker' 'FAIL' "The engine runs $($server.Os) containers." 'Switch Docker Desktop to Linux containers.'
    } else {
        $checks += New-GsuifCheck 'Core' 'Docker' 'OK' "Engine reachable (linux/$($server.Arch))."
    }
    $checks += Test-GsuifHostJava
    if (-not $SkipPort) { $checks += Test-GsuifPort -Port $Port }
    if ($server.Reachable) { $checks += Test-GsuifDatabase -StartIfStopped:$StartDatabase }
    $ids = @{}
    if (-not $WithoutGeneration) {
        $checks += Test-GsuifModelConfig
        $checks += Test-GsuifSavedKey
        try {
            $lock = Get-GsuifImageLock
            if ($server.Reachable -and $server.Os -eq 'linux') {
                $images = Test-GsuifImages -Lock $lock -Server $server
                $checks += $images.Checks
                $ids = $images.Ids
            } else {
                $checks += New-GsuifCheck 'Generation' 'Images' 'FAIL' 'Not checked because Docker is unavailable.' 'Fix the Docker check first.'
            }
        } catch {
            $checks += New-GsuifCheck 'Generation' 'Image lock' 'FAIL' $_.Exception.Message 'Restore config\docker-images.lock.json from Git.'
        }
        $checks += Test-GsuifMaven -Settings (Read-GsuifMachineSettings)
    }
    [pscustomobject]@{ Checks = $checks; ImageIds = $ids; Failed = @($checks | Where-Object { $_.Status -eq 'FAIL' }).Count -gt 0 }
}

# Non-secret variables for Invoke-OpenRouterLocal.ps1 -Environment. The launcher adds the key and model.
function Get-GsuifGenerationEnvironment {
    param([Parameter(Mandatory = $true)][hashtable]$ImageIds)
    $settings = Read-GsuifMachineSettings
    if ($null -eq $settings) { throw 'Machine settings are missing; run .\scripts\Initialize-GsuifLocal.ps1.' }
    foreach ($name in @('GSUIF_BACKEND_COMPILER_IMAGE_ID', 'GSUIF_BACKEND_BUILD_IMAGE_ID')) {
        if ([string]$ImageIds[$name] -cnotmatch $script:ImageIdPattern) { throw "$name could not be resolved from the image lock." }
    }
    @{
        GSUIF_BACKEND_COMPILER_IMAGE_ID = $ImageIds['GSUIF_BACKEND_COMPILER_IMAGE_ID']
        GSUIF_BACKEND_BUILD_IMAGE_ID = $ImageIds['GSUIF_BACKEND_BUILD_IMAGE_ID']
        GSUIF_MAVEN_COMMAND = [string]$settings.mavenCommand
        GSUIF_MAVEN_REPOSITORY = [string]$settings.mavenRepository
    }
}

# Runs a role probe with the same isolation flags as DockerBackendJavaCompiler.command and
# BackendFinalPackageValidator.command; keep them in step when those flags change.
function Invoke-GsuifImageProbe {
    param([Parameter(Mandatory = $true)][ValidateSet('compiler', 'build')][string]$Role,
        [Parameter(Mandatory = $true)][string]$ImageId, [string]$MavenHome, [string]$MavenRepository)
    $staged = Join-Path ([IO.Path]::GetTempPath()) ('gsuif-image-probe-' + [guid]::NewGuid().ToString('N'))
    $container = 'gsuif-image-probe-' + [guid]::NewGuid().ToString('N')
    $started = $false
    try {
        New-Item -ItemType Directory -Path $staged | Out-Null
        if ($Role -eq 'compiler') {
            [IO.File]::WriteAllText((Join-Path $staged 'Probe.java'), "final class Probe { static int answer() { return 42; } }`n")
            $limits = @('--pids-limit=64', '--memory=512m', '--cpus=1', '--tmpfs=/work:rw,exec,nosuid,size=128m,mode=1777',
                '--tmpfs=/tmp:rw,noexec,nosuid,size=16m,mode=1777', "--mount=type=bind,src=$staged,dst=/input,readonly")
            $script = 'mkdir -p /work/classes && javac -version 2>&1 && javac -proc:none -encoding UTF-8 -d /work/classes /input/Probe.java && test -s /work/classes/Probe.class && echo GSUIF_PROBE_OK'
            $expected = @('javac 21.', 'GSUIF_PROBE_OK')
        } else {
            $limits = @('--pids-limit=128', '--memory=1536m', '--cpus=2', '--tmpfs=/work:rw,exec,nosuid,size=768m,mode=1777',
                '--tmpfs=/tmp:rw,noexec,nosuid,size=64m,mode=1777', "--mount=type=bind,src=$staged,dst=/input,readonly",
                "--mount=type=bind,src=$MavenRepository,dst=/repo,readonly", "--mount=type=bind,src=$MavenHome,dst=/maven,readonly")
            $script = 'command -v cp >/dev/null && command -v find >/dev/null && command -v grep >/dev/null && javac -version 2>&1 && /maven/bin/mvn -B -v 2>&1 && echo GSUIF_PROBE_OK'
            $expected = @('javac 21.', 'Apache Maven', 'Java version: 21', 'GSUIF_PROBE_OK')
        }
        $arguments = @('run', '--pull=never', '--name', $container, '--network=none', '--read-only', '--ipc=none', '--cap-drop=ALL',
            '--security-opt=no-new-privileges', '--user=65532:65532') + $limits +
            @('--workdir=/work', '--env=HOME=/tmp', '--entrypoint=/bin/sh', $ImageId, '-c', $script)
        $started = $true
        $result = Invoke-GsuifDocker -Arguments $arguments
        $absent = @($expected | Where-Object { -not $result.Output.Contains($_) })
        [pscustomobject]@{ Ok = ($result.ExitCode -eq 0 -and $absent.Count -eq 0); Missing = $absent; ExitCode = $result.ExitCode }
    } finally {
        if ($started) {
            $removed = Invoke-GsuifDocker -Arguments @('rm', '-f', $container)
            if ($removed.ExitCode -ne 0) { Write-Warning "Could not confirm removal of probe container $container." }
        }
        Remove-Item -LiteralPath $staged -Recurse -Force -ErrorAction SilentlyContinue
    }
}

Export-ModuleMember -Function New-GsuifCheck, Write-GsuifChecks, Invoke-GsuifDocker, Get-GsuifPinnedModel, Get-GsuifImageLock,
    Get-GsuifDockerServer, Resolve-GsuifImage, Test-GsuifImages, Get-GsuifSecretPath, Get-GsuifMachineSettingsPath,
    Read-GsuifMachineSettings, Write-GsuifMachineSettings, Find-GsuifWrapperMaven, Get-GsuifMavenHome, Get-GsuifTemplateArtifacts,
    Test-GsuifMaven, Test-GsuifSavedKey, Test-GsuifModelConfig, Test-GsuifHostJava, Test-GsuifPort, Get-GsuifSchemaSnapshot,
    Get-GsuifSchemaGaps, Test-GsuifDatabase, Invoke-GsuifPreflight, Get-GsuifGenerationEnvironment, Invoke-GsuifImageProbe
