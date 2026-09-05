# Open Questions

This document tracks project questions that require clarification or confirmation.

## Current Open Questions

| ID | Question | Affects | Priority | Status | Source |
|----|----------|---------|----------|--------|--------|
| OQ-04 | If generated code depends on Jmix libraries, is that acceptable, or must the generated output remain plain Spring? | Jmix spike and template design | HIGH | OPEN | V3.2 |
| OQ-05 | What exactly constitutes a passing Phase 1 submission? | Demo preparation and documentation scope | HIGH | OPEN | V3.2 |
| OQ-07 | Must WOMS be the formal Phase 2 demonstration application? | Phase 2 scope | MEDIUM | OPEN | V3.2 |

## Resolved Questions

The following questions were resolved and are therefore not considered open:

- OQ-01: PostgreSQL is the development/reference database and the architecture remains database-agnostic. Oracle is not mandatory. *(V3.2)*
- OQ-02: Confirmed scope is Backend Platform Architecture, Metadata Engine, Unified Request/Response Frameworks, Automatic CRUD Generator, JWT Authentication, Basic RBAC, Audit Framework, Exception Handling, Logging, Validation, Swagger/OpenAPI, Database abstraction, Testing & Build Validation, Metadata Versioning. Deferred and out-of-scope items are listed in DECISIONS.md and the Standards Checklist. *(Scope Decision v1)*
- OQ-03: GraphQL Generator is deferred/optional, not a mandatory deliverable. *(Scope Decision v1)*
- OQ-06: Metadata versions are immutable. *(V3.2)*
- OQ-08: Multi-tenancy is not implemented in Phase 1 or Phase 2 unless later confirmed. *(V3.2)*
- OQ-09: The API Gateway is deferred — do only if time remains after core scope, not a Phase 1 blocker. *(Scope Decision v1)*
- OQ-10: Email/SMS password reset is deferred; not a core requirement. Authentication itself (login, JWT) remains mandatory. *(Scope Decision v1)*

## Question Status

- `OPEN` — requires clarification or confirmation.
- `RESOLVED` — confirmed by an authoritative project source.
- `DEFERRED` — intentionally postponed to a later phase.

## Adding a New Question

Use the following format:

| ID | Question | Affects | Priority | Status | Source |
|----|----------|---------|----------|--------|--------|
| OQ-XX | Description of the question | Affected area | HIGH/MEDIUM/LOW | OPEN | Source |
