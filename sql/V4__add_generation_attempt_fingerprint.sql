-- Existing runs cannot be bound retroactively to their original generation inputs.
ALTER TABLE gsuif_generation_run ADD COLUMN IF NOT EXISTS input_fingerprint VARCHAR(64);
