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
