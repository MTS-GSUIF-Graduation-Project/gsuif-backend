# T-12 Week 1 Sync Notes

**Ticket:** SCRUM-41 (T-12: Week 1 Sync — Adopt Artifacts & Send Company Questions)  
**Date:** 2026-09-09  
**Attendees:** M1 (Sondos Hashem), M2 (Alaa Elmasry), M3 (Esraa Abdelrazek), M4 (Nancy Abdo)

---

## 1. Discovery Track Outputs Presented

| Track | Ticket | Artifact | Status on `develop` |
|---|---|---|---|
| Standards Checklist | T-06 / SCRUM-35 | `standards-checklist.md` | Merged (PR #5) |
| Component Catalog | T-07 / SCRUM-39 | `components.yaml` | Merged (PR #15) |
| Jmix spike | T-08 / SCRUM-37 | DEC-021 (Jmix REJECT) | Merged (PR #4) |
| OpenAPI spike | T-09 / SCRUM-38 | DEC-022 (OpenAPI ACCEPT WITH LIMITATIONS) | Merged (PR #7) |
| Reference Implementation | T-10 / SCRUM-36 | WorkOrder CRUD | Merged (PR #14) |
| Capability Matrix | T-11 / SCRUM-40 | `capability-matrix.md` | Merged (PR #11) |

---

## 2. Standards Checklist Adoption

The team formally adopted `standards-checklist.md` (v1) as the Phase 1 conformance benchmark for generated and framework code.

- Reviewed by M3 (Esraa Abdelrazek)
- Adopted by all four members at the Week 1 sync on **2026-09-09**
- Adoption recorded in `standards-checklist.md` Adoption Checklist

---

## 3. Tool Decisions Confirmed

| Decision | Verdict | Notes |
|---|---|---|
| DEC-021 | Jmix REJECTED | Generated output must not depend on `io.jmix.*` (ADR-008) |
| DEC-022 | OpenAPI Generator ACCEPT WITH LIMITATIONS | Interface/DTO scaffolding only; not the Phase 1 engine |
| DEC-023 | STD-28 semantic HTTP baseline | 200/201/400/401/403/404/405/415/500; every error uses `ApiResponse` |

Phase 1 generation engine remains **TemplateOnlyProvider + Apache FreeMarker** (ADR-003).

---

## 4. Open Questions Sent to Supervisor

Sent on **2026-09-09**. Recorded in `QUESTIONS.md`.

| ID | Question | Status |
|---|---|---|
| OQ-05 | What exactly constitutes a passing Phase 1 submission? | OPEN |
| OQ-07 | Must WOMS be the formal Phase 2 demonstration application? | OPEN |

All other T-12 questions are resolved (see `QUESTIONS.md`).

---

## 5. Metadata JSON Schema Outline (Input to T-13)

**Owner of formal schema files:** SCRUM-20 (T-13) — files under `/metadata/schema/`  
**This section:** T-12 agreed outline only; not a JSON Schema implementation.

### 5.1 Design rules (from approved decisions)

| Rule | Source |
|---|---|
| Five first-class entities — not a generic "Entity" model | BE-02 (`components.yaml`) |
| UUID identifiers | ADR-005 / STD-35 |
| Metadata versions are immutable — each save creates a new version record | ADR-006 |
| No hardcoded environment URLs — `endpointUrl` is relative only | T-13 scope |
| Database-agnostic — no vendor-specific types in metadata | ADR-004 / STD-33 |

### 5.2 Entity relationships

```text
Project (1) ──< Page (N)
Page (1) ──< Component (N)
Page (1) ──< APIBinding (N)
Page (1) ──< MetadataVersion (N, immutable snapshots)
```

`MetadataVersion` stores an immutable snapshot of a page's `components` and `apiBindings` at save time.

### 5.3 Entity field outline

#### Project

| Field | Type | Required |
|---|---|---|
| `id` | UUID | yes |
| `name` | string | yes |
| `description` | string | no |
| `createdAt` | datetime | yes |
| `updatedAt` | datetime | yes |

#### Page

| Field | Type | Required |
|---|---|---|
| `id` | UUID | yes |
| `projectId` | UUID | yes |
| `name` | string | yes |
| `route` | string | no — relative UI route, e.g. `/work-orders` |
| `components` | Component[] | yes |
| `apiBindings` | APIBinding[] | yes |

#### Component (T-13 required fields)

| Field | Type | Required |
|---|---|---|
| `id` | UUID | yes |
| `type` | string | yes — e.g. `text-field`, `select`, `table`, `button` |
| `label` | string | yes |
| `position` | object | yes — e.g. `{ "row": 0, "col": 0 }` |
| `size` | object | yes — e.g. `{ "width": 12, "height": 1 }` |
| `visibility` | boolean | yes |
| `disabled` | boolean | yes |
| `fieldKey` | string | no — maps to generated entity/DTO field |

#### APIBinding (T-13 required fields)

| Field | Type | Required |
|---|---|---|
| `id` | UUID | yes |
| `name` | string | no — e.g. `listWorkOrders`, `createWorkOrder` |
| `httpMethod` | enum | yes — `GET`, `POST`, `PUT`, `DELETE` |
| `endpointUrl` | string | yes — relative only, e.g. `/api/v1/work-orders` |
| `headers` | object | yes — map of header name → value or placeholder |
| `requestMapping` | object | yes — maps fields → path, query, body |
| `responseMapping` | object | yes — maps API response → UI/table fields |
| `linkedComponentIds` | UUID[] | no — components that consume this binding |

**Mapping shape (to be finalized in T-13):**

```json
{
  "requestMapping": {
    "path": { "id": "path.id" },
    "query": { "status": "query.status" },
    "body": { "orderNumber": "orderNumber", "status": "status" }
  },
  "responseMapping": {
    "item": "body",
    "list": "body.data",
    "pagination": "body.totalPages"
  }
}
```

#### MetadataVersion (T-13 envelope + ADR-006)

| Field | Type | Required |
|---|---|---|
| `id` | UUID | yes |
| `projectId` | UUID | yes |
| `pageId` | UUID | yes |
| `version` | integer | yes — monotonic per page |
| `schemaVersion` | string | yes — contract version, e.g. `"1.0.0"` |
| `createdAt` | datetime | yes |
| `createdBy` | string | yes — username |
| `isCurrent` | boolean | yes — latest version flag (BE-17) |
| `snapshot` | object | yes — immutable copy of `components` + `apiBindings` |

**Rule:** no update or delete of existing `MetadataVersion` records. New save = new version.

### 5.4 WorkOrder sanity check (T-10 / SCRUM-36)

Validated against the hand-written reference implementation on `develop` (PR #14).

| T-10 artifact | Expressible in this outline? |
|---|---|
| Entity fields: `orderNumber`, `status`, `dueDate`, `assignedTo` | Yes — `Component.fieldKey` |
| `POST /api/v1/work-orders` | Yes — `APIBinding` |
| `GET /api/v1/work-orders` (paginated, optional `status` filter) | Yes — binding + `requestMapping.query` |
| `GET /api/v1/work-orders/{id}` | Yes |
| `PUT /api/v1/work-orders/{id}` | Yes |
| `DELETE /api/v1/work-orders/{id}` | Yes |
| `ApiResponse` envelope, STD-28 status codes | No — framework standards (BE-04, BE-09), not page metadata |

**Gaps for T-13 to resolve:**

- Exact `requestMapping` / `responseMapping` syntax
- Whether `Component.type` is a closed enum or open string
- Pagination/filter metadata for list endpoints
- Three worked examples (simple, one-to-many, many-to-many) per SCRUM-20 acceptance criteria

### 5.5 Illustrative WorkOrder page (not a formal schema file)

```json
{
  "schemaVersion": "1.0.0",
  "page": {
    "id": "00000000-0000-4000-8000-000000000001",
    "projectId": "00000000-0000-4000-8000-000000000099",
    "name": "Work Order Management",
    "route": "/work-orders",
    "components": [
      { "id": "c1", "type": "text-field", "label": "Order Number", "fieldKey": "orderNumber", "position": { "row": 0, "col": 0 }, "size": { "width": 6, "height": 1 }, "visibility": true, "disabled": false },
      { "id": "c2", "type": "select", "label": "Status", "fieldKey": "status", "position": { "row": 0, "col": 6 }, "size": { "width": 6, "height": 1 }, "visibility": true, "disabled": false },
      { "id": "c3", "type": "date-field", "label": "Due Date", "fieldKey": "dueDate", "position": { "row": 1, "col": 0 }, "size": { "width": 6, "height": 1 }, "visibility": true, "disabled": false },
      { "id": "c4", "type": "text-field", "label": "Assigned To", "fieldKey": "assignedTo", "position": { "row": 1, "col": 6 }, "size": { "width": 6, "height": 1 }, "visibility": true, "disabled": false },
      { "id": "c5", "type": "table", "label": "Work Orders", "fieldKey": "workOrders", "position": { "row": 2, "col": 0 }, "size": { "width": 12, "height": 4 }, "visibility": true, "disabled": false }
    ],
    "apiBindings": [
      { "id": "b1", "name": "listWorkOrders", "httpMethod": "GET", "endpointUrl": "/api/v1/work-orders", "headers": {}, "requestMapping": { "query": { "status": "status" } }, "responseMapping": { "list": "body.data" } },
      { "id": "b2", "name": "createWorkOrder", "httpMethod": "POST", "endpointUrl": "/api/v1/work-orders", "headers": {}, "requestMapping": { "body": { "orderNumber": "orderNumber", "status": "status", "dueDate": "dueDate", "assignedTo": "assignedTo" } }, "responseMapping": { "item": "body" } },
      { "id": "b3", "name": "getWorkOrder", "httpMethod": "GET", "endpointUrl": "/api/v1/work-orders/{id}", "headers": {}, "requestMapping": { "path": { "id": "id" } }, "responseMapping": { "item": "body" } },
      { "id": "b4", "name": "updateWorkOrder", "httpMethod": "PUT", "endpointUrl": "/api/v1/work-orders/{id}", "headers": {}, "requestMapping": { "path": { "id": "id" }, "body": { "orderNumber": "orderNumber", "status": "status", "dueDate": "dueDate", "assignedTo": "assignedTo" } }, "responseMapping": { "item": "body" } },
      { "id": "b5", "name": "deleteWorkOrder", "httpMethod": "DELETE", "endpointUrl": "/api/v1/work-orders/{id}", "headers": {}, "requestMapping": { "path": { "id": "id" } }, "responseMapping": {} }
    ]
  }
}
```

---

## 6. T-13 Handoff

Formal JSON Schema files, three worked examples, and M1+M3 contract review belong to **SCRUM-20 (T-13)**. This outline is the agreed starting point from T-12.
