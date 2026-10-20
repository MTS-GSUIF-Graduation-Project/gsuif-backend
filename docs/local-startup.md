# Local startup (Windows)

Backend generation needs six process environment variables: `OPENROUTER_API_KEY`, `OPENROUTER_MODEL`,
`GSUIF_BACKEND_COMPILER_IMAGE_ID`, `GSUIF_BACKEND_BUILD_IMAGE_ID`, `GSUIF_MAVEN_COMMAND` and
`GSUIF_MAVEN_REPOSITORY`. The scripts below set them for the backend process only, from settings saved
once per Windows user. Nothing is typed again after a restart.

Requirements: Windows PowerShell 5.1 or later, JDK 21 (`JAVA_HOME`), Docker Desktop with Linux
containers, and the `gsuif-postgres` container from `docker-compose.yml` with the GSUIF schema applied.

## One-time setup

```powershell
.\scripts\Initialize-GsuifLocal.ps1
```

It is safe to re-run. It:

1. Reuses the encrypted key saved by `scripts\Save-OpenRouterKey.ps1` in `%APPDATA%\GSUIF\openrouter-key.dpapi`
   (DPAPI, bound to this Windows user). If no key is saved it prompts with masked input. Use `-RotateKey`
   to replace it.
2. Creates the ignored `config\openrouter.local.ps1` from the example when it is missing. The model must be
   exactly the pinned `qwen/qwen3-coder`.
3. Finds the Maven installation downloaded by the Maven wrapper (downloading it once if needed) and stores
   its path and the local repository (default `%USERPROFILE%\.m2\repository`) in
   `%APPDATA%\GSUIF\backend-local.json`, outside Git. Override with `-MavenCommand` and `-MavenRepository`.
4. Pulls each image pinned by digest in `config\docker-images.lock.json` if it is missing (`-NoPull` to only
   report), resolves its local image ID, and probes each role with the backend's own isolation flags
   (offline, read-only root, UID 65532, no capabilities):
   - compiler: `/bin/sh`, `mkdir` and `javac` 21 compile a probe source from a read-only mount;
   - build: `cp`, `find`, `grep` and `javac` 21 in the image, plus the host Maven installation mounted
     read-only at `/maven` reports Maven on Java 21. The image does not need to contain Maven.
5. Reports the database schema (read-only, see below).

## Normal startup

```powershell
.\scripts\Start-GsuifLocal.ps1
```

It checks Docker, host Java, the port, PostgreSQL (starting the existing stopped `gsuif-postgres`
container if needed), the schema, the model file, the saved key (decryption only), the pinned images and
the Maven settings. It stops at failures with a fix for each, then runs
`mvnw spring-boot:run -Dspring-boot.run.profiles=local` through `scripts\Invoke-OpenRouterLocal.ps1`, which
decrypts the key in memory and sets all six variables for that process only. The terminal's own
environment is restored when the backend stops.

Options:

- `-WithoutGeneration` starts without loading generation settings. Everything except backend generation works.
- `-Port 8081` uses another port (for example while a backend started from the IDE holds 8080).
- `-CheckOnly` runs the checks without starting.

At startup the backend logs whether generation settings are present (names only, never values). A backend
started another way, for example from the IDE, logs a warning listing what is missing; generation requests
then fail with `CONFIG_MISSING`.

Image IDs are resolved from the pinned digests at every start; startup never pulls. To change an image,
review a new digest, update `config\docker-images.lock.json`, then run the setup again.

## What the schema check establishes

GSUIF's own schema is applied manually from `sql\V*.sql` and has no migration history table. The check
runs `psql` in a read-only session (`default_transaction_read_only=on`) and compares the result with
markers each script leaves behind:

- **Required (failure):** the tables and columns of V1-V9 and the generation status check (V8, V10:
  a single `ck_gsuif_generation_run_status` allowing all five statuses). Without these, startup or
  generation breaks.
- **Advisory (warning):** the V3 unique indexes (matched by columns, not name) and the V7 permission rows.
  Hibernate does not validate these, so the application starts without them.

It can establish that the expected objects exist. It cannot establish that each script ran, ran in order,
or ran exactly as written; that column types, defaults and other constraints match the scripts; that seed
data beyond the V7 codes is present; or that there are no extra objects. A new `sql\V*.sql` without
markers in `scripts\GsuifLocal.psm1` is reported as not checked. The scripts never create, migrate or
reset a database and never delete volumes; apply missing scripts deliberately as described in
`README.md`.

## What the Maven cache check establishes

The trusted pre-request provisioning resolves compile dependencies, but the final package build runs
offline. The check only confirms that the POMs of the parent, dependencies and plugins pinned in the
runtime template exist in the repository. Plugins inherited from the Spring Boot parent and transitive
artifacts are not checked; a missing one appears as a build failure during generation.

## Tests

```powershell
.\scripts\Test-GsuifLocal.ps1
.\scripts\Test-OpenRouterLocal.ps1
```

Both use disposable values and a fake Docker CLI or temporary profile; neither uses the saved key, pulls an
image, contacts OpenRouter or changes the database.

## Limits and deployment

- Windows only: the key store uses DPAPI for the current Windows user. Each teammate runs setup once.
- Starting from the IDE does not load the key; use `Start-GsuifLocal.ps1`, or accept the startup warning.
- The probes in `GsuifLocal.psm1` mirror the flags in `DockerBackendJavaCompiler.command` and
  `BackendFinalPackageValidator.command`; keep them in step when those change.
- Deployment is out of scope here. Generation runs `docker run` with host bind mounts, so typical managed
  application platforms without a Docker engine cannot run it, and mounting the Docker socket into a
  container would undermine the compiler isolation. A deployed environment needs a host with Docker
  (preferably rootless) running the backend directly, images pulled by the locked digests during
  provisioning, and the same variables plus `JWT_SECRET` and `GSUIF_DB_*` supplied by the platform's secret
  manager. Never use the local profile's committed JWT secret or demo database password there.
