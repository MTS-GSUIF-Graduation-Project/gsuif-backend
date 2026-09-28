# SCRUM-44 to SCRUM-28 handoff

This document records the agreed narrow direction for BE-05. The original handoff
file named in the request was absent from this checkout, so this version records
the decisions supplied in the ticket discussion and the verified AssetTicket fixture.

## Decisions

1. SCRUM-28 converts `GenerationContext` and an explicit entity generation
   specification into validated `project`, `entity`, and `api` maps. UI fields
   such as `component.type` and `fieldKey` do not define database entities or
   column types. Invalid or unsupported descriptors fail with an indexed path.
2. OpenAPI Generator 7.16.0 defines the Spring interface and business DTOs.
   FreeMarker renders an implementation against the selected interface.
3. SCRUM-28 exports versioned `ApiResponse`, `PagedBody`, and audit support
   source into consumer projects, with their required dependencies. The current
   Mustache override hardcodes `eg.mts.gsuif.dto.ApiResponse`, so source export
   must preserve that package or the override, fixture, and contract must change
   together. Imports alone do not deliver support classes.

## Verified SCRUM-44 slice

`src/main/resources/openapi/asset-ticket-api.yaml` has five deterministic
AssetTicket operations with five-field success-envelope schemas. The opt-in `openapi-fixture` Maven profile uses
the same Spring Generator options and `responseType.mustache` override as the
existing OpenAPI spike. An operation-level `x-gsuif-payload-java-type` tells the
override the inner payload type so Java does not double-wrap `ApiResponse`;
operations without the extension retain the old override behavior.
`TemplateFoundationTest` renders `controller-crud.ftl`,
checks the generated interface's method names, parameters, DTO packages,
generic return types, and verb/path mappings, and compiles the implementation
with the generated interface and DTOs. It also checks Spring's inherited mapping
lookup on the compiled controller. The entity fixture checks a 1000-character
column length, invalid descriptors (including scalar-as-enum and reserved Java
identifiers), and conditional Envers. Run:

```sh
./mvnw -B -Popenapi-fixture -DopenapiFixtureRequired=true -Dtest=TemplateFoundationTest test
```

Local result on 2026-09-28: 13 tests, 0 failures, 0 errors, 0 skipped.
The existing `openapi-spike` profile also compiled from a clean target with its
prior return-type behavior.
The fixture uses existing backend `ApiResponse` and `AuditableEntity` classes
for compilation; it does not prove consumer source export. `PagedBody` is not
used in this OpenAPI example.

## SCRUM-28 ownership and limits

SCRUM-28 owns the context adapter, explicit entity specification, catalog
selection, general binding-to-OpenAPI mapping, provider and render orchestration,
versioned support-source export, output paths, and artifact persistence. The
controller template emits exactly five AssetTicket-shaped operations and does
not consume `api.bindings`. It does not establish arbitrary contract compatibility.
Restricted-role expressions and logging policy are unresolved; generated Java
contains no placeholder authorization or logging annotations. The catalog entry
remains `PLANNED` until production integration and review.
