# Metadata schema versions

Stored snapshots are validated against the schema selected by their incoming
`schemaVersion`. Version `1.0.0` uses the original `metadata-version.schema.json`
and its original component schema. Version `1.1.0` uses the corresponding
`*-1.1.0.schema.json` resources. Unsupported versions fail at
`$.schemaVersion`; a missing snapshot is reported independently. This is the
approved SCRUM-57 version-dispatch decision: an unknown version has no selected
snapshot schema, so its snapshot cannot be checked for version-specific
structural errors. For either supported version, independent snapshot errors
are still collected and returned together. This intentionally changes the
earlier SCRUM-27 behavior of applying the sole 1.0.0 snapshot schema even when
the incoming version was unsupported.

## Approved 1.1.0 component contract

The SCRUM-57 authorizing-user decision of 2026-10-03 approved these exact
fields and checks. The base
Component fields remain available, `type` stays an open nonempty string, and
only the matching config is permitted. Unknown types use the base fields only.

| Type | 1.1.0 structural rule |
| --- | --- |
| `form` | Require `formConfig.submitLabel` as a nonempty string. It labels the built-in submit action. |
| `table` | Allow a legacy table without `tableConfig`. When present, require nonempty `columns`; every column has nonempty `fieldKey` (a response-row property) and `label`. |
| `navigation` | Require nonempty `navigationConfig.items`; each item has a nonempty `label` and a relative `route` using Page.route's `^/(?!/)` pattern. |
| `modal` | Use the required base `Component.label` as the title. There is no `modalConfig`; it is rejected. |
| `pagination` | Require `paginationConfig.pageSize` as an integer of at least 1. |

Config objects reject unknown properties. A matching config on another type is
invalid. These rules are schema checks, not runtime wiring or route-existence
checks.

Standalone Page validation retains `page.schema.json` as its existing entry
point and uses the original 1.0.0 component contract. It accepts the unchanged
Phase 1 Page examples. Callers validating a Page with 1.1.0 typed configs must
select `page-1.1.0.schema.json` explicitly.

Stored metadata does not include the Page envelope; it contains the immutable
snapshot of components and API bindings. The original 1.0.0 snapshot schema
continues to reference the unchanged 1.0.0 component schema.

The five type configs describe structure. They do not define where a form
submission goes, how table data is resolved, whether navigation routes exist,
what triggers a modal, or how pagination is coupled to data. Those runtime
behaviors need separate implementation. Parent-child bindings and conditional
visibility belong to SCRUM-58. Schema support also does not require every
page to contain all five types.
