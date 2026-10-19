-- Anchors complete BACKEND package hashes without changing historical fragment runs.
ALTER TABLE gsuif_generation_run ADD COLUMN IF NOT EXISTS package_manifest_sha256 VARCHAR(64);
