-- Existing rows predate per-file traceability and have no recoverable path or template version.
-- Nullable columns preserve those rows; all newly registered artifacts provide both values.
ALTER TABLE gsuif_generation_run ADD COLUMN IF NOT EXISTS attempt_id CHAR(36);
ALTER TABLE gsuif_generated_artifact ADD COLUMN IF NOT EXISTS relative_path VARCHAR(1024);
ALTER TABLE gsuif_generated_artifact ADD COLUMN IF NOT EXISTS template_version VARCHAR(100);
CREATE UNIQUE INDEX IF NOT EXISTS uk_gsuif_generation_run_attempt_id ON gsuif_generation_run (attempt_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_gsuif_generated_artifact_run_path ON gsuif_generated_artifact (generation_run_id, relative_path);
