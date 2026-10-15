-- Additive attempt states and bounded failure metadata; existing run/artifact rows remain intact.
ALTER TABLE gsuif_generation_run ADD COLUMN IF NOT EXISTS failure_stage VARCHAR(32);
ALTER TABLE gsuif_generation_run ADD COLUMN IF NOT EXISTS failure_code VARCHAR(64);
ALTER TABLE gsuif_generation_run DROP CONSTRAINT IF EXISTS ck_gsuif_generation_run_status;
ALTER TABLE gsuif_generation_run ADD CONSTRAINT ck_gsuif_generation_run_status
    CHECK (status IN ('SUCCESS', 'BUILD_FAILED', 'IN_PROGRESS', 'FAILED', 'INTERRUPTED'));
