# SCRUM-49 artifact registry schema rollout

Apply `sql/V3__add_generated_artifact_registry.sql` after V1 and V2, before
starting the application. The production profile validates the schema and does
not run migrations automatically. Existing run and artifact rows retain null
values for the new fields because their original attempt ID, path, and per-file
template version cannot be reconstructed. New service writes supply these
values. The migration can be rerun safely.

The unique attempt ID protects against duplicate completed attempts. The
unique `(generation_run_id, relative_path)` index ensures a file has one row
per run. A path can appear in multiple runs, so path-only lookup returns
history. A concurrent retry can lose the uniqueness race and fail; callers
can then read the winning run by attempt ID or retry the call.
