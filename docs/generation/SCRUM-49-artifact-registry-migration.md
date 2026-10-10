# SCRUM-49 artifact registry schema rollout

Apply `sql/V3__add_generated_artifact_registry.sql` after V1 and V2, before
`sql/V4__add_generation_attempt_fingerprint.sql`, then
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

New runs bind the attempt ID to a SHA-256 fingerprint of generation and consumer
build inputs. Existing run rows have a null fingerprint and cannot be safely
replayed as an identical request. A retry with a different fingerprint is
rejected before generation.

Configure `gsuif.generation.artifact-root` on a persistent filesystem volume
(default `var/generated-artifacts`). The service writes each returned
`GenerationResult` artifact under its run ID and stores a versioned manifest
with the diagnostics and consumer build contract. Both SUCCESS and BUILD_FAILED
runs retain their files. The run ID plus relative path is the stable lookup
reference; no file bytes are stored in the database. Back up this directory
alongside the database. Files have no automatic expiration or cleanup policy;
normal failed transaction writes are removed; an interrupted process can leave
an orphan directory without a committed database reference. Existing rows predate this storage
contract and cannot be retroactively served without their original bytes.
