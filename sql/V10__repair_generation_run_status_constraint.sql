-- Repair databases that retained the legacy two-state check after V8.
-- PostgreSQL DDL is transactional: either all three changes commit or none do.
BEGIN;
ALTER TABLE gsuif_generation_run DROP CONSTRAINT IF EXISTS gsuif_generation_run_status_check;
ALTER TABLE gsuif_generation_run DROP CONSTRAINT IF EXISTS ck_gsuif_generation_run_status;
ALTER TABLE gsuif_generation_run ADD CONSTRAINT ck_gsuif_generation_run_status
    CHECK (status IN ('SUCCESS', 'BUILD_FAILED', 'IN_PROGRESS', 'FAILED', 'INTERRUPTED'));
COMMIT;
