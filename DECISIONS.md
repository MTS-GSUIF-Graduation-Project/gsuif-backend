# Project Decisions

This document records confirmed project and architecture decisions.

## Confirmed Decisions

| ID | Decision | Source | Status | Approved by |
|----|----------|--------|--------|-------------|
| ADR-001 | Java 21 is the project Java version. | V3.2 | CONFIRMED | Team |
| ADR-002 | The AI generation architecture uses a provider-agnostic interface. | V3.2 | CONFIRMED | Team |
| ADR-003 | Phase 1 generation uses TemplateOnlyProvider with Apache FreeMarker. No external AI provider is required. | V3.2 | CONFIRMED | Team |
| ADR-004 | The architecture is database-agnostic, with PostgreSQL as the development, reference, and validation database. | V3.2 | CONFIRMED | Supervisor |
| ADR-005 | UUID is the identifier strategy for project entities. | V3.2 | CONFIRMED | Team |
| ADR-006 | Metadata versions are immutable; each save creates a new version record. | V3.2 | CONFIRMED | FRS |
| ADR-007 | Build Validation is mandatory for generated code. | V3.2 | CONFIRMED | Team |
| ADR-008 | Generated code must remain editable and must not depend on framework-specific generated-code lock-in. | V3.2 | CONFIRMED | Company |
| ADR-009 | Jmix is optional and will only be evaluated through a capability spike. | V3.2 | CONFIRMED | Team |
| ADR-010 | WOMS is a reference/demo candidate, not a mandatory framework requirement. | V3.2 | ARCHITECTURAL ASSUMPTION | Team |
| DEC-011 | Redis is deferred and is not a Phase 1 or Phase 2 dependency. | V3.2 | CONFIRMED | Team |
| DEC-012 | Multi-tenancy is not implemented in Phase 1 or Phase 2 unless later confirmed. | V3.2 | CONFIRMED | Team |
| DEC-013 | H2 in PostgreSQL compatibility mode is used for testing and CI. | V3.2 | CONFIRMED | Team |
| DEC-014 | Core Phase 1 backend scope is: Backend Platform Architecture, Metadata Engine, Unified Request Framework, Unified Response Framework, Automatic CRUD Generator, JWT Authentication, Basic RBAC, Audit Framework, Exception Handling, Logging, Validation, Swagger/OpenAPI, Database abstraction, Testing & Build Validation, Metadata Versioning. | Scope Decision v1 | CONFIRMED | Team |
| DEC-015 | GraphQL Generator is deferred; not a mandatory deliverable. | Scope Decision v1 | CONFIRMED | Team |
| DEC-016 | API Gateway is deferred; not a Phase 1 blocker. Resolves OQ-09. | Scope Decision v1 | CONFIRMED | Team |
| DEC-017 | Advanced/dynamic authorization (entity-level, field-level, policy engine) is deferred. Basic RBAC via `@PreAuthorize` remains mandatory. | Scope Decision v1 | CONFIRMED | Team |
| DEC-018 | Full password-reset workflow with email/SMS/external providers (e.g. Twilio/SMTP) is deferred. Core authentication (login, JWT) remains mandatory. Resolves OQ-10. | Scope Decision v1 | CONFIRMED | Team |
| DEC-019 | Frontend/UI deliverables (Angular component library, dynamic form engine, theme engine, configuration framework, API model generator, UI starter kit) are deferred behind the backend/metadata core. | Scope Decision v1 | CONFIRMED | Team |
| DEC-020 | Oracle-specific implementation and WebLogic/JSESSIONID/WOMS legacy infrastructure are explicitly not part of current scope. | Scope Decision v1 | CONFIRMED | Team |
| DEC-021 | Jmix is REJECTED as a generation foundation / output tool for GSUIF. Spike (T-08) showed generated code depends on `io.jmix.*`, uses EclipseLink + Vaadin/FlowUI, and does not match the Standards Checklist (ApiResponse, package layout, Hibernate/JPA stack). Phase 1 generation stays TemplateOnlyProvider (FreeMarker). | T-08 / SCRUM-37 spike | CONFIRMED | M2 (Alaa) |
| DEC-022 | OpenAPI Generator (`openapi-generator-maven-plugin:7.16.0`) is ACCEPTED WITH LIMITATIONS: used for contract-first REST interface scaffolding. Phase 2 TypeScript client SDK generation is an unverified candidate — not tested in the T-09 spike. It is not the Phase 1 generation engine. FreeMarker / TemplateOnlyProvider (ADR-003) remains the Phase 1 engine. STD-05 conformance requires a project-local `responseType.mustache` override (1 file, 62 bytes). STD-01 envelope construction remains the responsibility of the implementing `@RestController`. STD-27 and STD-28 are partial for generated scaffolding only — see `capability-matrix.md`. | Spike T-09 / SCRUM-38 | CONFIRMED | M3 (Esraa) |
| DEC-023 | STD-28 defines a required semantic HTTP baseline rather than a closed status-code whitelist. The mappings defined in the Technical Document remain required. Standard protocol responses such as 405 Method Not Allowed and 415 Unsupported Media Type are permitted and must be used when applicable. Other standard HTTP codes may be used only when documented and semantically appropriate. Every error response must use the five-field ApiResponse envelope. | STD-28 clarification | CONFIRMED | Team |
| DEC-024 | `GSUIF_METADATA_VERSION` stores both `project_id` and `page_id`. Database consistency is enforced by `UNIQUE(project_id, id)` on `GSUIF_PAGE` and a composite foreign key `(project_id, page_id)` referencing `GSUIF_PAGE(project_id, id)`, so a version cannot point at a page that belongs to a different project. T-13 `metadata-version.schema.json` is unchanged. | T-14 / SCRUM-21 | CONFIRMED | Team |

## SCRUM-51 review alignment

The team approved DEC-026 through DEC-029 during PR #29 review. These entries
record the agreed SCRUM-51 behavior in the repository. Jira AC2, AC3, AC4 and
AC9 were updated on 2026-10-02 to match the wording below.

| ID | Decision | Source | Status | Approved by |
|----|----------|--------|--------|-------------|
| DEC-026 | Accept GET, POST, PUT and DELETE; reject PATCH. Jira AC3's five-method wording was replaced. | PR #29 team review; consistent with `api-binding.schema.json`. | CONFIRMED | Team |
| DEC-027 | Keep sequential numeric immutable versions and do not add `versionName`; retire only duplicate-version-name rejection from Jira AC2. Route and component-ID uniqueness remain required. | PR #29 team review; consistent with ADR-006, DEC-025 and `metadata-version.schema.json`. | CONFIRMED | Team |
| DEC-028 | The inclusive size limit is 5,000,000 UTF-8 bytes of the exact snapshot JSON string persisted. Serialize once, measure and persist that same string; exclude request/version envelopes and transport formatting. | PR #29 team review; verified by exact-size persistence tests. | CONFIRMED | Team |
| DEC-029 | Unsupported binding-method values reach structural schema enum validation first at the API boundary, returning HTTP 400 with the existing `enum` field error. The `SupportedHttpMethod` business rule is tested directly without that structural gate; its name appears in direct business-rule findings, not the structural API error. | PR #29 team review; behavior covered by `MetadataBusinessControllerIntegrationTest`. | CONFIRMED | Team |

### Jira acceptance-criterion wording for SCRUM-51

Jira AC2, AC3, AC4 and AC9 were updated on 2026-10-02 with the following text.
All other acceptance criteria remained unchanged.

- **AC2:** Generated duplicate routes within a project and duplicate component
  IDs within a page are rejected by their named business rules. Duplicate-version-name
  validation is retired; versions remain numeric and immutable.
- **AC3:** Generated unsupported HTTP methods, including PATCH, are rejected.
  GET, POST, PUT and DELETE remain accepted. Direct property tests exercise the
  `SupportedHttpMethod` business rule independently of structural validation.
- **AC4:** Otherwise-valid snapshot JSON strings of 4,999,999 and 5,000,000
  UTF-8 bytes are accepted; 5,000,001 bytes is rejected. Measure the exact JSON
  string persisted, excluding request/version envelopes and transport formatting;
  serialize once, measure and persist that same string.
- **AC9:** Business-rule violations return HTTP 400 with errors naming the rule.
  Unsupported method values rejected first by structural schema validation
  retain the existing `enum` error in the HTTP 400 response.

## SCRUM-47 diagnostics decision

| ID | Decision | Source | Status | Approved by |
|----|----------|--------|--------|-------------|
| DEC-030 | SCRUM-47 owns build validation, persists bounded build output and exit codes, and makes diagnostics retrievable through an internal generation-run lookup by ID. SCRUM-48 owns the REST `GET /api/v1/generation/runs/{id}` response that exposes those diagnostics to clients. SCRUM-47 adds no REST endpoint. | Team decision, 2026-10-05 | CONFIRMED | Team |

### SCRUM-47 zero-test success rule

The team confirmed that SCRUM-47 records `SUCCESS` when the generated Java
consumer's Maven compile and test goals both succeed. No minimum executed-test
count is required for SCRUM-47. Maven may discover or execute zero tests, so
`SUCCESS` does not guarantee that any test executed. A stricter rule for future
generation work requires a separate decision; this clarification preserves the
saved SCRUM-47 acceptance criteria.


## Database Rules

- PostgreSQL is the current development, reference, and validation database.
- The persistence layer must remain database-agnostic.
- Spring Data JPA and Hibernate are used as the persistence abstraction.
- Native queries are not allowed in the framework core.
- Vendor-specific database types, sequences, and functions must not be used.
- H2 in PostgreSQL compatibility mode is used for testing and CI.

## Phase 1 Generation

- Phase 1 uses deterministic template-based generation.
- TemplateOnlyProvider is the Phase 1 implementation of the provider interface.
- Apache FreeMarker is used for template-based generation.
- External AI services and AI credentials are not required for Phase 1.
- Generated code must pass build validation.
- Generated code must remain editable.

## Phase 1 Scope Summary

**In scope (core, mandatory):** Backend Platform Architecture, Metadata Engine, Unified Request/Response Frameworks, Automatic CRUD Generator, JWT Authentication, Basic RBAC, Audit Framework, Exception Handling, Logging, Validation, Swagger/OpenAPI, Database abstraction, Testing & Build Validation, Metadata Versioning.

**Deferred (do only if time allows):** GraphQL Generator, API Gateway, Advanced Authorization, Password Reset + Email/SMS integrations, Redis/Distributed Cache, AI Generation Provider, full Frontend/UI deliverables.

**Not part of current scope:** Multi-tenancy, Oracle-specific implementation, WebLogic/JSESSIONID/WOMS legacy infrastructure.

## DEC-024 — Metadata version project/page consistency (T-14 / SCRUM-21)

`GSUIF_METADATA_VERSION` keeps both `project_id` and `page_id` so the stored envelope matches T-13 without changing `metadata-version.schema.json`.

Relational integrity is not left to the application alone:

- `GSUIF_PAGE` has `UNIQUE(project_id, id)` (`uk_gsuif_page_project_id_id`).
- `GSUIF_METADATA_VERSION` has `FOREIGN KEY (project_id, page_id) REFERENCES gsuif_page (project_id, id)` (`fk_gsuif_metadata_version_project_id_page_id`).

A metadata version therefore cannot reference a page whose `project_id` differs from the version's `project_id`.

## Adding a New Decision

New confirmed decisions should be added using the following format:

| ID | Decision | Source | Status | Approved by |
|----|----------|--------|--------|-------------|
| ADR-XXX | Description of the decision | Source | CONFIRMED | Person or Team |

---

## DEC-025 — Page-pointer architecture (SCRUM-22)

MetadataVersion records are immutable and append-only. Current-version selection is stored as mutable Page state through a nullable MetadataVersion reference.
- `latest` refers to the highest sequential version number.
- `current` refers to the version referenced by the Page pointer.
- Creating a new version also automatically updates the Page pointer to that new version within the same atomic transaction.
- Explicit rollbacks (to be implemented) only update the Page pointer without modifying or copying existing versions.
- The `isCurrent` DTO field is derived dynamically by comparing a version's ID to the Page's pointer.

---

## DEC-022 — OpenAPI Generator Evaluation Rationale (T-09 / SCRUM-38)

**Spike:** T-09 · **Branch:** `feature/SCRUM-38-openapi-generator`
**Artifact:** `capability-matrix.md`
**Standards referenced:** STD-01, STD-02, STD-03, STD-05, STD-27, STD-28, STD-33, STD-35, ADR-001, ADR-003, ADR-007, ADR-008, ADR-009

### Context

T-09 evaluated `openapi-generator-maven-plugin:7.16.0` as a candidate source of standard
request/response scaffolding for the GSUIF generation pipeline, using the same evaluation
criteria applied to the Jmix spike (T-08).

### Empirical Findings

**Environment:** Java 21, Spring Boot 4.0.8, Jakarta EE (`useJakartaEe=true`),
`interfaceOnly=true`, generator version 7.16.0.

**Build validation (ADR-007):** `.\mvnw.cmd clean compile` → `BUILD SUCCESS` confirmed
on two consecutive runs with a clean `target/` directory each time.

**STD-05 (controller return type):** By default, the generator produces raw
`ResponseEntity<T>` return types (e.g. `ResponseEntity<WorkOrderDto>`), which does not
satisfy STD-05. Conformance is achieved by placing a single project-local template file:

```
src/main/resources/templates/openapi/spring/responseType.mustache   (1 line, 62 bytes)
```

Content:
```
ResponseEntity<eg.mts.gsuif.dto.ApiResponse<{{>returnTypes}}>>
```

All five WorkOrder operations then generate the correct signature. The parent `api.mustache`
is resolved from the generator JAR at build time — no local copy is needed.

**STD-01 (five-field envelope):** The generator scaffolds interface signatures only. The
developer implementing the `@RestController` constructs the `ApiResponse<T>` value,
consistent with STD-02 (success shape) and STD-03 (error shape). This gap is expected
and acceptable for `interfaceOnly=true` contract-first scaffolding.

**STD-35 (UUID identifier strategy):** `format: uuid` in the OpenAPI YAML generates
the Java `UUID` type — fully conformant.

**STD-27 (Swagger/OpenAPI coverage):** ⚠️ partial. Generator emits `@Operation`,
`@ApiResponse`, and `@Parameter` annotations from declared YAML `responses:` blocks.
SpringDoc bean config, SecurityConfig Swagger integration, `@Schema` on framework DTOs,
and full endpoint coverage remain hand-written (BE-11).

**STD-28 (HTTP status codes):** ⚠️ partial. The T-09 spike YAML declares **200**,
**201**, and **404** only (DELETE uses **200**, not 204). Runtime 405/415 are
implemented in `GlobalExceptionHandler` (BE-09), but the spike spec does not document
them. OpenAPI Generator can emit additional codes only if declared in the spec; full
STD-28 OpenAPI documentation remains a SCRUM-29 (T-20) gap.

**STD-33 (database-agnostic persistence):** Not applicable — OpenAPI Generator is not
a persistence-layer tool.

**ADR-008 (no lock-in):** Generated interfaces are implemented in developer-authored
`@RestController` classes in `src/main/java/`. Generated code lives in
`target/generated-sources/` only — not committed to the repository.
One additional compile-scope dependency is introduced:
`org.openapitools:jackson-databind-nullable:0.2.6`.

### Decision

**ACCEPTED WITH LIMITATIONS.**

- OpenAPI Generator 7.16.0 is accepted for: contract-first REST API interface scaffolding.
- Phase 2 TypeScript client SDK generation is a candidate use case only — **not verified**
  in the T-09 spike (see `capability-matrix.md`).
- OpenAPI Generator is **not** the Phase 1 generation engine.
  FreeMarker / `TemplateOnlyProvider` remains the Phase 1 engine (ADR-003).
- The `responseType.mustache` single-file override is the adopted template customization
  approach. The full `api.mustache` copy is **not** committed to the repository.

### Upgrade Note

If `openapi-generator-maven-plugin` is upgraded across major versions in the future,
`responseType.mustache` (1 line) should be verified against the new version's template
hierarchy to confirm the sub-template name and rendering contract remain unchanged.
