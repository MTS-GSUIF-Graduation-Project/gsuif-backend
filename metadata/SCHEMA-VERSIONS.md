# Metadata schema versions

Stored snapshots are validated against the schema selected by their incoming
`schemaVersion`. Version `1.0.0` uses the original `metadata-version.schema.json`
and its original component schema. Version `1.1.0` uses the corresponding
`*-1.1.0.schema.json` resources. Unsupported versions fail at
`$.schemaVersion`; a missing snapshot is reported independently.

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
