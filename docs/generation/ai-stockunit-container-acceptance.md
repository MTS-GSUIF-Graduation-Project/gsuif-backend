# Opt-in StockUnit model-source acceptance harness

`CompleteBackendAiStockUnitAcceptanceTest` is test infrastructure only. Its default
test feeds a labelled **mock** OpenRouter response containing the existing handwritten
StockUnit regression fixture. The mock container test is disabled unless
`gsuif.test.ai-mock-containers=true` is supplied. Neither mock test reads an OpenRouter key
or sends a provider request.

The container test freezes the StockUnit BACKEND descriptor, selected contract,
rendered definitions, POM and one MODEL-owned output path. It provisions a compile
classpath from the exact rendered POM, validates the mock response through the
existing adapter and pinned offline compiler, then assembles the package. It checks
the accepted service bytes and SHA-256 against assembly, extraction and the manifest.
It never edits extracted source or compares source bytes with compiled class bytes.

For the build, a trusted empty project containing only the exact rendered POM runs
Maven's package lifecycle with Boot repackaging skipped. A file-only mirror of the
operator's existing Maven cache populates a disposable repository; no model source
is present in that preparation project. The build container receives that repository,
the Maven distribution and the extracted package via `docker cp` into disposable
volumes. Compile-dependency JAR hashes are rechecked after copying. The container
uses the same mirror identity, `-o`, `-Dmaven.compiler.proc=none`, non-root UID 65532,
`--network=none`, a read-only root and resource limits. No host directory or Docker
socket is mounted. The built JAR is hashed separately.

Bootstrap, app and trusted HTTP probe run in separate restricted containers. The app,
PostgreSQL and probe attach only to a newly created `--internal` Docker network;
no ports are published. The probe repeats core StockUnit bootstrap/login, CRUD,
filtering, required-nullable, 401/403/404 and restart scenarios. Disposable SQL
checks confirm row count and stored audit actors. The probe parses the five-field
JSON envelope structurally, checks exact statuses, fixed messages and CRUD values,
and reads database state immediately after rejected create/update and unauthorized
write requests. Jackson 2.18.2 and PostgreSQL JDBC 42.7.11 are pinned by SHA-256,
copied only to the trusted probe volume and verified again after staging. They are
not part of the generated POM, assembled files or executable JAR. The tested source remains the
same mock-accepted bytes. This is orchestration acceptance with a handwritten
fixture, **not** AI-generated CRUD acceptance.

The test requires absolute `gsuif.test.maven-command`, `maven.repo.local`, pinned
`sha256:` IDs in `gsuif.test.ai-jdk-image` and `gsuif.test.ai-postgres-image`, and an
`gsuif.test.ai-evidence-dir` outside JUnit's temporary directory. The mock opt-in
writes a mock label, source/JAR hashes, a generation manifest and cleanup codes
in a fresh attempt directory. Database, JWT and bootstrap values are
disposable; their temporary files are deleted. Docker child environments remove
`OPENROUTER_API_KEY` and `OPENROUTER_MODEL`. The harness does not preserve raw
provider responses, prompts, build output, tokens or passwords. Every Docker
command and process has a bound; cleanup targets only uniquely labelled test
containers, volumes and network and verifies their absence.

Ordinary CI leaves both container acceptance tests skipped and uses mocked transport
checks. The separate live StockUnit test is gated by
`gsuif.test.ai-live-stockunit=true` and requires the exact
`OPENROUTER_MODEL=qwen/qwen3-coder` plus `OPENROUTER_API_KEY` in the launched test
process. The existing Windows `Invoke-OpenRouterLocal.ps1` loader supplies those
environment values from local configuration and the user-bound encrypted store;
the test never reads that store directly. The live path does not read the handwritten
ServiceImpl fixture, construct a synthetic response, substitute source or repair
rejected output. Its manifest records `openrouter`, `api-v1`, the exact selected
model and `mocked:false`. The mock path remains labelled `mocked:true`.

Before a live request, the test checks both pinned Docker image IDs, evidence
location and exact-POM compile dependencies. It then uses the existing HTTPS adapter,
validator and pinned isolated compiler. The adapter allows only two HTTP attempts,
with one retry after two seconds for transport errors, 429 or 5xx; it requests at
most 4,096 output tokens, uses a 10-second connect timeout, 120-second per-attempt
response timeout and 250-second provider/validation deadline. There is no model
fallback or repair request. The accepted source bytes and SHA-256 must match the
assembled file, extracted file and manifest; the offline build does not rewrite
source. Immediately after validator acceptance and before assembly, the live test
creates a fresh `live-attempt-*` directory and writes the exact accepted ServiceImpl
at its MODEL-owned relative path with `CREATE_NEW`, plus `accepted-source.sha256`.
Later assembly or runtime failures write a bounded result in that same directory;
they never replace the accepted source. The executable JAR has its own hash.
Generated code runs only with the disposable PostgreSQL database on the internal
Docker network. Build and runtime
children receive no provider credential, host directory or Docker socket mount.

Each successful container run writes bounded provenance, source/JAR hashes, cleanup
status and the package manifest in its attempt directory outside JUnit's temporary
directory. A failed live attempt adds only a stage and fixed failure code to that
directory; if validation already succeeded, its exact accepted source and hash remain.
No raw provider response, prompt, password, token or API key is retained. This wiring remains disabled until an
explicit live run is approved; no paid request was made for this change.
