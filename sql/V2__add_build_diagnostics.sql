-- SCRUM-47: add bounded build diagnostics without changing the established V1 schema.
ALTER TABLE gsuif_generation_run ADD COLUMN IF NOT EXISTS compile_exit_code INTEGER;
ALTER TABLE gsuif_generation_run ADD COLUMN IF NOT EXISTS test_exit_code INTEGER;
ALTER TABLE gsuif_generation_run ADD COLUMN IF NOT EXISTS compile_output VARCHAR(16000);
ALTER TABLE gsuif_generation_run ADD COLUMN IF NOT EXISTS test_output VARCHAR(16000);
