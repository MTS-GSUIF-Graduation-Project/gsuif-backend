# Open Questions

This document tracks project questions that require clarification or confirmation.

## Current Open Questions

| ID | Question | Affects | Priority | Status | Source |
|----|----------|---------|----------|--------|--------|
| OQ-02 | Which of the 11 Backend Deliverables are in scope for the Master project and which are future work? | Phase 2 and Final scope; effort estimation | HIGH | OPEN | V3.2 |
| OQ-03 | Is the GraphQL Generator a mandatory deliverable or optional? | Backend scope and effort | HIGH | OPEN | V3.2 |
| OQ-04 | If generated code depends on Jmix libraries, is that acceptable, or must the generated output remain plain Spring? | Jmix spike and template design | HIGH | OPEN | V3.2 |
| OQ-05 | What exactly constitutes a passing Phase 1 submission? | Demo preparation and documentation scope | HIGH | OPEN | V3.2 |
| OQ-07 | Must WOMS be the formal Phase 2 demonstration application? | Phase 2 scope | MEDIUM | OPEN | V3.2 |
| OQ-09 | Is the API Gateway (US-09) in scope for the Master project? | Architecture and Phase 2 scope | MEDIUM | OPEN | V3.2 |
| OQ-10 | Is email/SMS password reset a real requirement? | Security scope and external service dependencies | LOW | OPEN | V3.2 |

## Resolved Questions

The following questions were resolved in V3.2 and are therefore not considered open:

- OQ-01: PostgreSQL is the development/reference database and the architecture remains database-agnostic. Oracle is not mandatory.
- OQ-06: Metadata versions are immutable.
- OQ-08: Multi-tenancy is not implemented in Phase 1 or Phase 2 unless later confirmed.

## Question Status

- `OPEN` — requires clarification or confirmation.
- `RESOLVED` — confirmed by an authoritative project source.
- `DEFERRED` — intentionally postponed to a later phase.

## Adding a New Question

Use the following format:

| ID | Question | Affects | Priority | Status | Source |
|----|----------|---------|----------|--------|--------|
| OQ-XX | Description of the question | Affected area | HIGH/MEDIUM/LOW | OPEN | Source |