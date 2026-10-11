# WOMS Phase 2 demo metadata

This directory is the task-local WOMS dataset selected for SCRUM-60. It demonstrates the generic GSUIF 1.2.0 metadata contract; WOMS is not a mandatory framework dependency. All /api/demo/woms/... endpoints are illustrative and do not establish a runtime API contract. The repository-level /demo/ entry point is demo/README.md; it points here because versioned metadata examples are validated from metadata/examples/.

The three independent bundles cover:

- /demo/woms/work-orders: query and status filter inputs, search results, and pagination.
- /demo/woms/work-orders/edit: create and edit bindings. In edit mode, the host supplies the selected work-order id in page context (for example, from a selected search row). loadWorkOrderForEdit and updateWorkOrder both map that same context value to the {id} path parameter. Create mode does not invoke the load binding and posts a new item.
- /demo/woms/work-orders/{id}: detail metadata and relationship examples.

Relationship trace:

- 1:N — listWorkOrderTasks models the read-only order-number field as the parent of the work-order tasks table. Its endpoint reads /api/demo/woms/work-orders/{id}/tasks.
- M:N — WorkOrders and technicians are connected through the assignment table and the join-style listTechnicianAssignments, assignTechnicianToWorkOrder, and removeTechnicianFromWorkOrder bindings. The POST and DELETE endpoints operate on /api/demo/woms/work-orders/{id}/technicians and its technician-specific child resource. Component links describe UI consumers; the join-style operations document business cardinality. assignTechnicianToWorkOrder parents the assignment table and uses the technician-to-assign select as its child.

The two detail lists intentionally use separate response shapes:

- listAssignedTechnicians supplies body.data rows shaped like { "technicianId": "...", "technicianName": "..." } to the assigned-technicians table.
- listTechnicianAssignments supplies body.data rows shaped like { "workOrderId": "...", "technicianId": "..." } to the assignment table.

Each bundle contains a standalone Page and its matching immutable 1.2.0 snapshot. The Page and snapshot reuse the same components and API bindings so both public validation entry points cover identical metadata.

## Detail summary

getWorkOrder maps response item to body and links three visible disabled components: orderNumber (text-field), status (text-field), and dueDate (date-field). A representative body such as { "orderNumber": "WO-42", "status": "OPEN", "dueDate": "2026-10-11" } displays through those field keys. The summary is not a form. formConfig.submitLabel always labels a built-in submit action, and the component schema has no separate read-only flag, so disabled on a field component is the read-only representation. Status is a text-field because select has no option-list or current-value property.

## Technician assignment

assignTechnicianToWorkOrder posts body property technicianId from the visible enabled select whose fieldKey is technicianToAssign. That select is a child of the assignment table on the POST binding. The component schema has no option list, so this dataset does not store technician choices and does not reference a technician catalog API.

removeTechnicianFromWorkOrder deletes /api/demo/woms/work-orders/{id}/technicians/{technicianId}. Path id remains the detail page context. Path technicianId is the technicianId column of the selected assignment row on the linked assignment table, not the technicianToAssign select. listTechnicianAssignments returns rows shaped like { "workOrderId": "...", "technicianId": "..." }, which is the row the delete binding reads.

## Create and edit contract

These shapes describe metadata mappings only. The /api/demo/woms/... paths stay illustrative and have no runtime implementation here.

createWorkOrder (POST /api/demo/woms/work-orders) sends { "orderNumber", "dueDate" } from the visible enabled components with those field keys. Those components are children of the editor form. The POST binding has no path id.

loadWorkOrderForEdit and updateWorkOrder both map path id from host page context. The editor has no component whose fieldKey is id. Both GET and PUT use /api/demo/woms/work-orders/{id}. The PUT body uses the same orderNumber and dueDate sources. A component can be a child of only one binding, so the update binding references those fields through linkedComponentIds instead of repeating the create binding's parent and children.

Response mapping item: body attaches the response body to the linked editor fields. Only orderNumber and dueDate are bound. Status and other reference WorkOrder properties are intentionally omitted. The Save work order label does not choose POST or PUT; the host decides create versus edit before rendering. Any response property without a matching fieldKey is unspecified.

## Proposed Jira clarification

For SCRUM-60, WOMS is the formal Phase 2 demo dataset covering search, create/edit, detail, 1:N, and M:N cases. This task-local choice supersedes OQ-07 wording for SCRUM-60 only; broader project scope can remain open in QUESTIONS.md pending supervisor confirmation.
