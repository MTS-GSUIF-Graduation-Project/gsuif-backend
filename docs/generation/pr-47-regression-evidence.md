# PR #47 pre-commit regression evidence (2026-10-19)

Branch: `feature/SCRUM-65-hybrid-generation`; HEAD at verification:
`203fb19b89ca900f2da9ae40a62d286d832de7c6`. The 14 pending
implementation, test, SQL, and documentation inputs other than this evidence
file had the same combined SHA-256 before and after the run:
`a8248040ac0a2a25b66660e1b16d3cdf9807105b3890a35a19b401de31ced312`.

## Combined regression and disposable PostgreSQL

The run used a new `postgres:16` container named
`gsuif-pr47-precommit-88542bac9b57`, published only on
`127.0.0.1:64089`. `POSTGRES_DB=gsuif_status_migration_test` created its
database. A random disposable password was generated in the launching
PowerShell process and was not retained in this document. After Maven exited,
`docker rm --force` removed that container; a subsequent `docker container
inspect` confirmed it absent. No developer database or existing container was
used.

Reproduce the selection from this checkout with a newly created disposable
container. The same generated `$testPassword` is supplied to PostgreSQL and
Maven. Docker allocates a loopback port; the observed run used port `64089`.
The readiness check must pass before Maven starts. The `finally` block removes
only the container created by this command and checks that it is absent.

```powershell
$mavenExe = 'C:\Users\eabde\.m2\wrapper\dists\apache-maven-3.9.16\0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0\bin\mvn.cmd'
$selection = 'GenerationExportServiceTest,GenerationControllerIntegrationTest,GenerationPermissionIntegrationTest,GenerationRunPostgresStatusMigrationTest,GenerationRunSchemaMigrationTest,ExistingAuditStartupIntegrationTest,OpenApiIntegrationTest,OpenApiValidationTest'
$testPassword = [guid]::NewGuid().ToString('N') + 'A9!'
$containerName = 'gsuif-pr47-check-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
$containerCreated = $false
try {
  docker run --detach --name $containerName --publish '127.0.0.1::5432' --env "POSTGRES_PASSWORD=$testPassword" --env 'POSTGRES_DB=gsuif_status_migration_test' postgres:16 | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Disposable PostgreSQL creation failed' }
  $containerCreated = $true
  $ready = $false
  for ($attempt = 0; $attempt -lt 60; $attempt++) {
    docker exec $containerName pg_isready -U postgres -d gsuif_status_migration_test *> $null
    if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    Start-Sleep -Milliseconds 500
  }
  if (-not $ready) { throw 'Disposable PostgreSQL did not become ready' }
  $binding = (docker port $containerName 5432/tcp) -join ' '
  if ($LASTEXITCODE -ne 0 -or $binding -notmatch '127\.0\.0\.1:(\d+)') { throw 'Loopback port lookup failed' }
  $jdbcUrl = "jdbc:postgresql://127.0.0.1:$($Matches[1])/gsuif_status_migration_test"
  & $mavenExe -B -ntp '-Dmaven.repo.local=C:\Users\eabde\.m2\repository' "-Dtest=$selection" '-Dgsuif.test.status-migration-disposable=true' "-Dgsuif.test.status-migration-jdbc-url=$jdbcUrl" "-Dgsuif.test.status-migration-password=$testPassword" test
  if ($LASTEXITCODE -ne 0) { throw 'Combined regression failed' }
} finally {
  if ($containerCreated) {
    docker rm --force $containerName | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Disposable PostgreSQL removal failed' }
    docker container inspect $containerName *> $null
    if ($LASTEXITCODE -eq 0) { throw 'Disposable PostgreSQL remains after cleanup' }
  }
  $testPassword = $null
}
```

The V10 PostgreSQL test therefore ran with the explicit disposable opt-in and
localhost URL. It checks legacy, canonical, and combined constraint layouts,
preserves historical run/artifact links, and rejects an invalid status. Its
result was **3 passed, 0 failures, 0 errors, 0 skipped**.
The V10 SQL resource in `target/classes/sql/` matched the source SHA-256
`12a9a853d96cd3483900afda13040cf86bf30eead0fa82c41e7509f3e2e25e73`.

| Test class | Tests | Passed | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| ExistingAuditStartupIntegrationTest | 1 | 1 | 0 | 0 | 0 |
| OpenApiIntegrationTest | 2 | 2 | 0 | 0 | 0 |
| GenerationControllerIntegrationTest | 12 | 12 | 0 | 0 | 0 |
| GenerationRunPostgresStatusMigrationTest | 3 | 3 | 0 | 0 | 0 |
| GenerationRunSchemaMigrationTest | 1 | 1 | 0 | 0 | 0 |
| GenerationExportServiceTest | 7 | 7 | 0 | 0 | 0 |
| GenerationPermissionIntegrationTest | 14 | 14 | 0 | 0 | 0 |
| OpenApiValidationTest | 6 | 6 | 0 | 0 | 0 |
| **Total** | **46** | **46** | **0** | **0** | **0** |

Maven exited 0 with `BUILD SUCCESS`. The run summary and cleanup output are
under `review-packages/SCRUM-65-pr47-precommit-20261019/`. That folder
contains the full Maven log and Surefire text reports. Its copied XML reports
redact only the disposable password property; raw originals remain in ignored
`target/surefire-reports/`.

## ZIP download checks

`GenerationPermissionIntegrationTest` exercises enforced anonymous 401,
persisted-grant 403 for each missing Project, Page, or MetadataVersion grant,
and authorized HTTP ZIP bytes. `GenerationExportServiceTest` exercises a
missing ZIP, non-ZIP destination, uncommitted run, altered payload, appended
bytes, and central-directory-only filename and attribute changes. Each
corrupted ZIP is rejected before download. These checks use test-generated ZIP
content and mocked run retrieval; they do not rerun real-provider generation.

Separate manual evidence remains: run
`aa30fd3c-5e1f-497f-aeaa-368495564fdd` completed `SUCCESS`; its downloaded
ZIP contained 48 files, with file set, sizes, and hashes matching manifest
SHA-256 `cd42c8b4654a6828e5ed7da9b6a029d95e9b9e9b17036f2e1079885c984f7184`.
That ZIP check is separate from runtime and CRUD acceptance. The earlier run's
package-integrity failure remains unresolved by this evidence.

## Reused validator cleanup evidence and limits

`BackendFinalPackageValidator.java` is unchanged from commit
`203fb19b89ca900f2da9ae40a62d286d832de7c6`; both committed and current
bytes have SHA-256
`8f30fbae4e8fb7b692ae37e2862d31ffbfc5d4e1e30a8561ad4967b09ff30a0c`.
The existing PR review package
`SCRUM-65-validator-cleanup-203fb19-20261019.zip` retains the stress command,
its raw `completed=200` output, and Surefire reports. The Linux real-process
selection passed 2/2 with no skips. The full validator class ran on Windows:
8 passed and one optional Docker build test skipped. **The full validator
class is not claimed to have passed on Linux.** No validator tests were
repeated for this combined run.

This is an eight-class combined regression, not a full backend suite or a new
live OpenRouter acceptance. The disposable PostgreSQL test validates V10 in
isolated schemas; no shared database migration was executed.
