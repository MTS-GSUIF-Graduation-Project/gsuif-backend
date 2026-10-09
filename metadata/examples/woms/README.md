# WOMS Phase 2 demo metadata

This directory is the task-local WOMS dataset selected for SCRUM-60. It demonstrates the generic GSUIF 1.2.0 metadata contract; WOMS is not a mandatory framework dependency. All /api/demo/woms/... endpoints are illustrative and do not establish a runtime API contract. The repository-level /demo/ entry point is demo/README.md; it points here because versioned metadata examples are validated from metadata/examples/.

The three independent bundles cover:

- /demo/woms/work-orders: query and status filter inputs, search results, and pagination.
- /demo/woms/work-orders/edit: create and edit bindings. In edit mode, the host supplies the selected work-order id in page context (for example, from a selected search row). loadWorkOrderForEdit and updateWorkOrder both map that same context value to the {id} path parameter. Create mode does not invoke the load binding and posts a new item.
- /demo/woms/work-orders/{id}: detail metadata and relationship examples.

Relationship trace:

- 1:N — listWorkOrderTasks models one WorkOrder summary component as the parent of the work-order tasks table. Its endpoint reads /api/demo/woms/work-orders/{id}/tasks.
- M:N — WorkOrders and technicians are connected through the assignment table and the join-style listTechnicianAssignments, assignTechnicianToWorkOrder, and removeTechnicianFromWorkOrder bindings. The POST and DELETE endpoints operate on /api/demo/woms/work-orders/{id}/technicians and its technician-specific child resource. Component links describe UI consumers; the join-style operations document business cardinality.

The two detail lists intentionally use separate response shapes:

- listAssignedTechnicians supplies body.data rows shaped like { "technicianId": "...", "technicianName": "..." } to the assigned-technicians table.
- listTechnicianAssignments supplies body.data rows shaped like { "workOrderId": "...", "technicianId": "..." } to the assignment table.

Each bundle contains a standalone Page and its matching immutable 1.2.0 snapshot. The Page and snapshot reuse the same components and API bindings so both public validation entry points cover identical metadata.

## Proposed Jira clarification

For SCRUM-60, WOMS is the formal Phase 2 demo dataset covering search, create/edit, detail, 1:N, and M:N cases. This task-local choice supersedes OQ-07 wording for SCRUM-60 only; broader project scope can remain open in QUESTIONS.md pending supervisor confirmation.
