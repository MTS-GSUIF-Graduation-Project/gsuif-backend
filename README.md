# gsuif-backend

[![CI](../../actions/workflows/ci.yml/badge.svg)](../../actions/workflows/ci.yml)

## Git Workflow

All development work must follow the branching workflow below:

1. Create a feature branch: `feature/SCRUM-XX-description`
2. Submit a Pull Request to `develop`
3. After review and approval, merge into `develop`
4. Submit a Pull Request from `develop` to `main`
5. After review and approval, merge into `main`

### Branches

- `main`: Stable branch containing production-ready code.
- `develop`: Integration branch for completed features.
- `feature/SCRUM-XX-description`: Feature or task-specific development branch.

### Pull Requests

- Direct pushes to `main` and `develop` are not allowed.
- All changes must be submitted through Pull Requests.
- Pull Requests require at least one approving review before merging.
- CI checks must pass before merging once the CI pipeline is configured.

### Commit Convention

Use the following format for commit messages:

`[SCRUM-XX] Short description`

Example:

`[SCRUM-17] Add project gitignore`

## AssetTicket vertical slice (SCRUM-31)

This example uses the packaged `asset-ticket-api` 1.0.0 contract. The fixed
binding UUIDs in [demo/woms-sample-metadata.json](demo/woms-sample-metadata.json)
map, in order, `listAssetTickets` (GET `/api/v1/asset-tickets`, query `status`,
response `body.data`), `createAssetTicket` (POST same path, body `title` and
`status`, response `body`), `getAssetTicketById` (GET
`/api/v1/asset-tickets/{id}`), `updateAssetTicket` (PUT same item path, body
`title` and `status`), and `deleteAssetTicket` (DELETE same item path). The last
three map `{id}` from `id` and their responses from `body`. Binding 1 links
the title and status controls and the results table. The explicit specification
in [demo/woms-generation-request.json](demo/woms-generation-request.json)
supplies entity fields, operation roles, and table columns. The Angular template
renders controls and a table; it does not wire them to a running API.

Prerequisites: Java 21, PostgreSQL, Maven wrapper, `curl`, `jq`, and `psql`.
From a clean checkout, create a disposable local database as a PostgreSQL
administrator:

```sh
psql -U postgres -d postgres -c "CREATE ROLE gsuif_user LOGIN PASSWORD 'gsuif_pass'"
psql -U postgres -d postgres -c "CREATE DATABASE gsuif_db OWNER gsuif_user"
```

Start the API with `./mvnw spring-boot:run`. Once local Hibernate schema
setup completes, seed the login from another terminal:

```sh
PGPASSWORD=gsuif_pass psql -h localhost -U gsuif_user -d gsuif_db -f demo/woms-seed-admin.sql
```

The local profile uses `gsuif_user` / `gsuif_pass` by default. Set
`GSUIF_DB_URL`, `GSUIF_DB_USERNAME`, and `GSUIF_DB_PASSWORD` if your local
database differs. The disposable `admin` login uses `password123`; use a
separate account and secret outside the local demo. The Maven build packages
the OpenAPI generated interface and DTO sources used by consumer build
validation.

Run the following from the repository root in a POSIX shell. Set `BASE` if the
server is not on port 8080. Every `curl -f` stops on an HTTP error. `jq -e`
assertions stop when a response fails a hop. Export settings are durable files
under `var/exports/config/<project UUID>`; they are separate from immutable
1.2.0 metadata snapshots. Each project's `path` selects a directory beneath
`var/exports/<project UUID>`. The folder export uses `<path>/<run UUID>` and
the ZIP export uses `<path>/<run UUID>.zip`.

```sh
set -eu
BASE=${BASE:-http://localhost:8080}

# 1. Login with the local demo account and obtain a JWT.
TOKEN=$(curl -fsS -H 'Content-Type: application/json' -d '{"username":"admin","password":"password123"}' "$BASE/api/auth/login" | jq -er '.body.token')
AUTH="Authorization: Bearer $TOKEN"

# 2. Create a project.
PROJECT=$(curl -fsS -H "$AUTH" -H 'Content-Type: application/json' -d '{"name":"WOMS AssetTicket demo","description":"SCRUM-31"}' "$BASE/api/v1/projects" | jq -er '.body.id')

# 3. Create its page.
PAGE=$(curl -fsS -H "$AUTH" -H 'Content-Type: application/json' -d '{"name":"Asset tickets","route":"/asset-tickets"}' "$BASE/api/v1/projects/$PROJECT/pages" | jq -er '.body.id')

# 4. Save the fixture. This save performs structural and business validation.
VERSION=$(curl -fsS -H "$AUTH" -H 'Content-Type: application/json' --data-binary @demo/woms-sample-metadata.json "$BASE/api/v1/pages/$PAGE/metadata" | jq -er 'select(.body.schemaVersion == "1.2.0") | .body.id')

# 5. Verify the immutable current version and its five bindings.
curl -fsS -H "$AUTH" "$BASE/api/v1/pages/$PAGE/metadata/current" | jq -e --arg version "$VERSION" 'select(.body.id == $version and (.body.snapshot.apiBindings | length) == 5)'

# 6. Generate all three targets from that selected version.
jq --arg page "$PAGE" '.pageId = $page' demo/woms-generation-request.json > /tmp/woms-generation-request.json
RUN=$(curl -fsS -H "$AUTH" -H 'Content-Type: application/json' --data-binary @/tmp/woms-generation-request.json "$BASE/api/v1/generation/generate" | jq -er --arg version "$VERSION" 'select(.body.metadataVersionId == $version and .body.status == "SUCCESS") | .body.runId')

# 7. Confirm compile and test exits and select a registered generated file.
DETAILS=$(curl -fsS -H "$AUTH" "$BASE/api/v1/generation/runs/$RUN")
printf '%s' "$DETAILS" | jq -e --arg version "$VERSION" 'select(.body.status == "SUCCESS" and .body.compileExitCode == 0 and .body.testExitCode == 0 and .body.metadataVersionId == $version and (.body.artifacts | length) > 0)'
FILE=$(printf '%s' "$DETAILS" | jq -er '.body.artifacts[0].relativePath')

# 8. Query the registry path history and verify run -> immutable version.
curl -fsSG -H "$AUTH" --data-urlencode "path=$FILE" "$BASE/api/v1/generation/artifacts/history" | jq -e --arg run "$RUN" --arg version "$VERSION" 'any(.body[]; .runId == $run and .metadataVersionId == $version)'

# 9. Download that registered file from the saved run.
curl -fsSG -H "$AUTH" --data-urlencode "path=$FILE" "$BASE/api/v1/generation/runs/$RUN/artifacts/download" -o /tmp/woms-generated-file
test -s /tmp/woms-generated-file

# 10. Configure and export to a local folder; inspect its returned location.
curl -fsS -X PUT -H "$AUTH" -H 'Content-Type: application/json' -d '{"type":"LOCAL_FOLDER","path":"delivery/folder"}' "$BASE/api/v1/generation/projects/$PROJECT/destinations/local" | jq -e 'select(.body.type == "LOCAL_FOLDER" and .body.path == "delivery/folder")'
curl -fsS -X POST -H "$AUTH" "$BASE/api/v1/generation/runs/$RUN/exports/local" | jq -e --arg run "$RUN" 'select(.body.runId == $run and (.body.location | startswith("file:")))'

# 11. Configure and export the same verified run to ZIP.
curl -fsS -X PUT -H "$AUTH" -H 'Content-Type: application/json' -d '{"type":"ZIP","path":"delivery/archive"}' "$BASE/api/v1/generation/projects/$PROJECT/destinations/zip" | jq -e 'select(.body.type == "ZIP" and .body.path == "delivery/archive")'
curl -fsS -X POST -H "$AUTH" "$BASE/api/v1/generation/runs/$RUN/exports/zip" | jq -e --arg run "$RUN" 'select(.body.runId == $run and (.body.location | endswith(".zip")))'
```

Export attempts are logged with run, project, and destination. Repeating an
export to the same destination returns an explicit collision error and leaves
the recorded generation status unchanged. `CLOUD_STUB` is a configured extension
point that reports an explicit unsupported delivery failure.

The shared SCRUM-64 export scope is covered here: destination configuration is
per project and supports `LOCAL_FOLDER`, `ZIP`, and `CLOUD_STUB`; the run export
endpoint returns a file location; exports are logged; steps 10 and 11 exercise
the two successful destination types. Cloud delivery remains a stub by design.
