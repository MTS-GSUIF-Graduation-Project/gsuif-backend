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
