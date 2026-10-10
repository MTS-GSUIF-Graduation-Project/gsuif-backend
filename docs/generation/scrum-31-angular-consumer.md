# SCRUM-31 Angular consumer output

The SCRUM-31 example emits Angular component TypeScript and HTML files alongside
its Java consumer sources. The Angular template reflects the page controls and
table columns in the saved metadata. It uses bracket access for metadata keys so
strict TypeScript settings accept keys represented by an index signature.

The generated files provide a starting point for a consumer application. They do
not include a complete WOMS application, API client, routing, authentication,
server state, or CRUD wiring. The example template does not submit its form or
load rows from the generated Java API. Add those behaviors in the consuming
application using its chosen Angular architecture and API client.

The backend's runtime consumer validation compiles Java and runs a generated
controller behavior test against the packaged OpenAPI contract. It does not run
the Angular CLI for each generation request. Angular template and strict
TypeScript compilation are covered by the repository's pinned Angular harness
tests. Generation responses expose this boundary in `validationScope`: Java
targets that passed appear in `passedTargets`, while generated Angular output
appears in `notValidatedTargets`.

See the [AssetTicket vertical slice](../../README.md#assetticket-vertical-slice-scrum-31)
for local setup, generation, build-result inspection, artifact download, and
folder/ZIP export steps.

## Compile and display the generated view

After completing README steps 1–7, keep `BASE`, `AUTH`, and `RUN` in the same
POSIX shell. Install Node.js and npm, then download the two registered Angular
files into a fresh copy of the repository's pinned strict template harness:

```sh
DETAILS=$(curl -fsS -H "$AUTH" "$BASE/api/v1/generation/runs/$RUN")
TS_PATH=$(printf '%s' "$DETAILS" | jq -er '.body.artifacts[] | select(.relativePath == "src/app/generated/asset-ticket.component.ts") | .relativePath')
HTML_PATH=$(printf '%s' "$DETAILS" | jq -er '.body.artifacts[] | select(.relativePath == "src/app/generated/asset-ticket.component.html") | .relativePath')
mkdir -p /tmp/woms-angular/src/app/generated
cp src/test/angular-harness/{package.json,package-lock.json,tsconfig.json} /tmp/woms-angular/
curl -fsSG -H "$AUTH" --data-urlencode "path=$TS_PATH" "$BASE/api/v1/generation/runs/$RUN/artifacts/download" -o /tmp/woms-angular/$TS_PATH
curl -fsSG -H "$AUTH" --data-urlencode "path=$HTML_PATH" "$BASE/api/v1/generation/runs/$RUN/artifacts/download" -o /tmp/woms-angular/$HTML_PATH
(cd /tmp/woms-angular && npm ci && npm run check)
```

`npm run check` must exit zero: it runs Angular's strict template compiler on
the downloaded component. To display it, create a disposable Angular 20 app
and use that same component as its root. Run these commands from the repository
root after the downloads above:

```sh
cd /tmp
npx --yes @angular/cli@20.3.0 new woms-preview --standalone --routing=false --style=css --skip-git --skip-tests --package-manager=npm --defaults
mkdir -p woms-preview/src/app/generated
cp woms-angular/src/app/generated/asset-ticket.component.{ts,html} woms-preview/src/app/generated/
sed 's#<app-root></app-root>#<app-generated-asset-ticket></app-generated-asset-ticket>#' woms-preview/src/index.html > woms-preview/src/index.html.tmp
mv woms-preview/src/index.html.tmp woms-preview/src/index.html
cat > woms-preview/src/main.ts <<'EOF'
import { bootstrapApplication } from '@angular/platform-browser';
import { AssetTicketComponent } from './app/generated/asset-ticket.component';
bootstrapApplication(AssetTicketComponent).catch(console.error);
EOF
cd woms-preview
npm run build
npm start -- --host 127.0.0.1
```

Open `http://127.0.0.1:4200/`. The page should show the title control, status
dropdown, and results table headers from the metadata fixture. The table has no
rows until a consuming application loads them; this preview verifies rendering,
not API integration.
