# WOMS Phase 2 demo metadata

This directory is the task-local WOMS dataset selected for SCRUM-60. It demonstrates the generic GSUIF 1.2.0 metadata contract; WOMS is not a mandatory framework dependency. All `/api/demo/woms/...` endpoints are illustrative and do not establish a runtime API contract.

The three independent bundles cover:

- `/demo/woms/work-orders`: search results and pagination.
- `/demo/woms/work-orders/edit`: create and edit bindings.
- `/demo/woms/work-orders/{id}`: detail metadata and relationship examples.

Relationship trace:

- 1:N — `listWorkOrderTasks` models one WorkOrder summary component as the parent of the work-order tasks table. Its endpoint reads `/api/demo/woms/work-orders/{id}/tasks`.
- M:N — WorkOrders and technicians are connected through the assignment table and the join-style `listAssignedTechnicians`, `assignTechnicianToWorkOrder`, and `removeTechnicianFromWorkOrder` bindings. The POST and DELETE endpoints operate on `/api/demo/woms/work-orders/{id}/technicians` and its technician-specific child resource. Component links describe UI consumers; the join-style operations document business cardinality.

Each bundle contains a standalone Page and its matching immutable 1.2.0 snapshot. The Page and snapshot reuse the same components and API bindings so both public validation entry points cover identical metadata.

## Proposed Jira clarification

For SCRUM-60, WOMS is the formal Phase 2 demo dataset covering search, create/edit, detail, 1:N, and M:N cases. This task-local choice supersedes OQ-07 wording for SCRUM-60 only; broader project scope can remain open in `QUESTIONS.md` pending supervisor confirmation.
