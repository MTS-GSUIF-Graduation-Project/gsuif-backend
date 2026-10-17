# Opt-in StockUnit model-source acceptance harness

`CompleteBackendAiStockUnitAcceptanceTest` is test infrastructure only. Its default
test feeds a labelled **mock** OpenRouter response containing the existing handwritten
StockUnit regression fixture. The container test is disabled unless
`gsuif.test.ai-mock-containers=true` is supplied. Neither test reads an OpenRouter key
or sends a provider request. There is no live opt-in in this slice.

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
`gsuif.test.ai-evidence-dir` outside JUnit's temporary directory. The opt-in writes
only a mock label, source/JAR hashes, a generation manifest and completion/cleanup
codes in a fresh attempt directory. Database, JWT and bootstrap values are
disposable; their temporary files are deleted. Docker child environments remove
`OPENROUTER_API_KEY` and `OPENROUTER_MODEL`. The harness does not preserve raw
provider responses, prompts, build output, tokens or passwords. Every Docker
command and process has a bound; cleanup targets only uniquely labelled test
containers, volumes and network and verifies their absence.

Ordinary CI leaves the container acceptance skipped and uses mocked transport
checks. A later reviewed change must connect a real opt-in transport and retain the
accepted model bytes unchanged through this same build/runtime path. A failed model
response must fail that attempt; the handwritten fixture is never a repair or
fallback for live output.
