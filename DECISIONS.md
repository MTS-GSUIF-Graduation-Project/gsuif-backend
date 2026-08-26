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
| DEC-021 | OpenAPI Generator (`openapi-generator-maven-plugin:7.16.0`) is ACCEPTED WITH LIMITATIONS: used for contract-first REST interface scaffolding and Phase 2 client SDK generation. It is not the Phase 1 generation engine. FreeMarker / TemplateOnlyProvider (ADR-003) remains the Phase 1 engine. STD-05 conformance requires a project-local `responseType.mustache` override (1 file, 62 bytes). STD-01 envelope construction remains the responsibility of the implementing `@RestController`. | Spike T-09 / SCRUM-38 | CONFIRMED | Team |

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

## Adding a New Decision

New confirmed decisions should be added using the following format:

| ID | Decision | Source | Status | Approved by |
|----|----------|--------|--------|-------------|
| ADR-XXX | Description of the decision | Source | CONFIRMED | Person or Team |

---

## DEC-021 — OpenAPI Generator Evaluation Rationale (T-09 / SCRUM-38)

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

**STD-27 (Swagger/OpenAPI coverage):** Generator emits `@Operation`, `@ApiResponse`,
`@Parameter` annotations from Springdoc — fully conformant.

**STD-28 (HTTP status codes):** Status codes are declared in the OpenAPI YAML
`responses:` blocks and flow through to annotations — conformant.

**STD-33 (database-agnostic persistence):** Not applicable — OpenAPI Generator is not
a persistence-layer tool.

**ADR-008 (no lock-in):** Generated interfaces are implemented in developer-authored
`@RestController` classes in `src/main/java/`. Generated code lives in
`target/generated-sources/` only — not committed to the repository.
One additional compile-scope dependency is introduced:
`org.openapitools:jackson-databind-nullable:0.2.6`.

### Decision

**ACCEPTED WITH LIMITATIONS.**

- OpenAPI Generator 7.16.0 is accepted for: contract-first REST API interface scaffolding,
  and Phase 2 TypeScript client SDK generation from OpenAPI specifications.
- OpenAPI Generator is **not** the Phase 1 generation engine.
  FreeMarker / `TemplateOnlyProvider` remains the Phase 1 engine (ADR-003).
- The `responseType.mustache` single-file override is the adopted template customization
  approach. The full `api.mustache` copy is **not** committed to the repository.

### Upgrade Note

If `openapi-generator-maven-plugin` is upgraded across major versions in the future,
`responseType.mustache` (1 line) should be verified against the new version's template
hierarchy to confirm the sub-template name and rendering contract remain unchanged.
