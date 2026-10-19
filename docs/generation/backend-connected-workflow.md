# Connected BACKEND generation (SCRUM-65 review)

`POST /api/v1/generation/backend` accepts `pageId`, a versioned `specification`, and optional `attemptId`. The API does not select a provider or model. The server uses `OPENROUTER_MODEL=qwen/qwen3-coder` and `OPENROUTER_API_KEY` from its runtime environment. It also needs `GSUIF_BACKEND_COMPILER_IMAGE_ID`, `GSUIF_BACKEND_BUILD_IMAGE_ID` (immutable Docker SHA-256 IDs), `GSUIF_MAVEN_COMMAND` (trusted Maven installation), and `GSUIF_MAVEN_REPOSITORY` or `maven.repo.local` (provisioned dependency cache). The provider key belongs only to GSUIF; generated applications do not receive it.

The path checks persisted Page and MetadataVersion grants, resolves the page's current immutable metadata version, and applies complete-output BACKEND preflight against the exact registered contract version. Invalid client input is rejected before reservation. Internal preparation failures after a valid request are reserved and recorded with bounded failure stage/code. The attempt fingerprint includes selected metadata/specification, contract and rendered bytes, provider/model, and build image. Reusing an ID with changed inputs is rejected; a committed SUCCESS can be replayed after package integrity verification. An IN_PROGRESS or FAILED ID requires a fresh ID. Interrupted-attempt recovery is not implemented.

The production contract registry contains explicit `stock-unit-api` 2.0.0 and 3.0.0 entries. The latter declares page/size defaults and bounds plus ascending ID ordering; it is selected only by its exact version. Existing registered versions remain selectable. Production generation code has no StockUnit-specific branch.

Reservation and completion use short, separate database transactions. Dependency provisioning, provider I/O, isolated compilation, deterministic assembly, and the offline executable-JAR build run outside those transactions. The model owns only the planned ServiceImpl path. The assembled package contains a hash-bound manifest with file ownership and provider/model provenance, excluding the manifest's own hash. Mocked tests mark provenance `mocked: true`.

The existing artifact store writes a private candidate under its own root, verifies every byte, then atomically publishes it during locked completion. The database stores the package-manifest hash and artifact registrations; publication failure rolls the transaction back. A commit rollback removes the published directory. Download and export require committed SUCCESS and verify registry, store, manifest, contract bytes, and hashes. Filesystem cleanup after a process crash is still operational work; no automatic reset of an interrupted attempt is claimed.

The final build runs with Java 21 in the pinned image, as UID 65532, offline, without a network, with read-only inputs and a disposable writable work area. It has a four-minute bound and returns fixed failure codes. Production build prerequisites must be provisioned deliberately. Existing fragment generation and TemplateOnly continue on their prior paths. FRONTEND and FULL_STACK dispatch remain outside this increment.

Mocked connected-path tests verify orchestration and byte integrity. Standalone package build/start/CRUD tests use handwritten test-only ServiceImpl fixtures. Those fixtures are never a fallback for model output.

On 18 October 2026, one real-provider public BACKEND request selected `stock-unit-api` 3.0.0 and reached committed generation `SUCCESS` for run `e2d2b1e6-e235-4d0d-a678-8f9451e58abc`. The existing export API produced the exact ZIP with SHA-256 `c331cbf9c82f9d46579e249e97f4e9ad0e1821f5ef358e6374be5a4de58b486f`; the accepted model-owned ServiceImpl has SHA-256 `839ebda02170a50c600a58c44db5324f27dfd68b7d361583e74eede201533786`. The paid test then errored while creating its extraction directory because its JUnit temporary parent was absent. The preserved stack trace does not establish why that parent disappeared. That invocation established public generation and export success, not runtime acceptance.

A later credential-free continuation used only that preserved ZIP. It verified the ZIP, source, manifest, exact file set and selected contract, then built and ran the extracted application in disposable isolated containers. Bootstrap, CRUD, pagination across more than 20 matches, restart and targeted cleanup passed: one Surefire test passed with no failures, errors or skips. Its separate continuation evidence records the generated JAR SHA-256 `28acdfe15efbeb11621d9d69d9bc82b9c53197e3ce2d75064ead179078a141af`. No provider request, generated-source repair, or handwritten ServiceImpl substitution occurred during the continuation. The original generation result and subsequent runtime acceptance remain separate results.

Successful BACKEND create and run-detail responses include the selected contract ID/version, committed manifest SHA-256, and a backend validation scope. For mocked provenance, the scope does not claim isolated compilation or package build even when an opt-in test performs them; that evidence lives in the test report. For real configured provider runs, completion records source-structure validation, isolated Java compilation, and standalone Maven package validation. Runtime startup and CRUD behavior are never claimed by the generation response. Legacy fragment run details omit the additive `backendPackage` field.

## Read-only frontend workspace APIs

All three routes require the normal bearer authentication outside the explicit dev profile. They do not call the model, reserve an attempt, build, or change generation state.

`GET /api/v1/generation/contracts?output=BACKEND` returns a five-field `ApiResponse` whose `body` is an ordered array of `{contractId, contractVersion, label, operationIds, updateMode, pageCompatibilityChecked}`. For example, a registered entry can read:

```json
{"contractId":"stock-unit-api","contractVersion":"3.0.0","label":"StockUnit fixture API","operationIds":["registerStockUnit","inspectStockUnit","scanStockUnits","replaceStockUnit","discardStockUnit"],"updateMode":"FULL","pageCompatibilityChecked":false}
```

The list comes from the packaged production registry and contract parser. Legacy optional-update and invalid contracts are omitted. It does not say that any entry fits a particular page; the frontend must send the exact version and its explicit operation bindings in `POST /api/v1/generation/backend`. Other `output` values return HTTP 400 in the standard error envelope. No specification skeleton is offered.

`GET /api/v1/generation/attempts/{attemptId}` returns the owner's persisted attempt after current Page and MetadataVersion grant checks. An in-progress example is:

```json
{"status":"OK","clientMessage":"Generation attempt retrieved","statusCode":200,"body":{"runId":"00000000-0000-0000-0000-000000000001","attemptId":"00000000-0000-0000-0000-000000000002","status":"IN_PROGRESS","metadataVersionId":"00000000-0000-0000-0000-000000000003","failureStage":null,"failureCode":null},"errors":null}
```

FAILED attempts return only recognized persisted failure stage/code; other statuses return null failure fields. Missing and other-user IDs both return HTTP 404 (`clientMessage: "Generation attempt not found"`); a known owner's revoked grant returns the standard HTTP 403. A 404 is a snapshot of persistence, not proof that a concurrent POST cannot reserve that ID later. Continue to use the existing POST idempotency behavior.

`GET /api/v1/generation/runs/{runId}/package.zip` downloads a committed complete BACKEND package directly. It needs no destination configuration or export POST. The service checks Project, Page and MetadataVersion grants, SUCCESS and BACKEND identity, registry, private storage, package manifest, exact selected contract and hashes before sending ZIP headers. The response is `application/zip` with `Content-Disposition: attachment; filename="{runId}.zip"`. The direct path bounds the verified snapshot to 512 files, 64 MiB of artifact content and a 1 MiB private storage manifest, then streams the ZIP without a temporary archive. Frontends should use an authorized `fetch`, check `response.ok`, and save the response Blob; do not put tokens in the URL. Existing configurable export APIs remain available.

Before binary output, missing runs return the standard 404 envelope, missing grants return 403, and incomplete, oversized or damaged packages return the safe 500 envelope. For example:

```json
{"status":"INTERNAL_SERVER_ERROR","clientMessage":"An unexpected error occurred","statusCode":500,"body":null,"errors":null}
```

A client disconnect during streaming terminates that response; there is no server-side temporary ZIP to retain. The generated application and OpenRouter are never invoked by these read routes.
