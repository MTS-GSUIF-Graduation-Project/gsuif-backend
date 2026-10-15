-- Initial seed data for security testing (Username: admin, Password: password123)
INSERT INTO gsuif_role (id, name, created_at, updated_at, created_by, last_modified_by)
VALUES ('00000000-0000-0000-0000-000000000001', 'ROLE_ADMIN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system');

INSERT INTO gsuif_user (id, username, password_hash, created_at, updated_at, created_by, last_modified_by)
VALUES ('00000000-0000-0000-0000-000000000002', 'admin', '$2a$10$s0BHlupflpolkNsDOsaBEuEftehIkhgsKfeFL9ZxGrb0BL.nmMiUW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system');

INSERT INTO gsuif_user_role (user_id, role_id, created_at, updated_at, created_by, last_modified_by)
VALUES ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system');

-- SCRUM-61: the existing integration-test principals use a full-access role.
INSERT INTO gsuif_user (id, username, password_hash, created_at, updated_at, created_by, last_modified_by) VALUES
('00000000-0000-0000-0000-000000000003', 'esraa.abdelrazek', 'test-only', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system'),
('00000000-0000-0000-0000-000000000004', 'scrum51', 'test-only', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system'),
('00000000-0000-0000-0000-000000000005', 'user', 'test-only', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system'),
('00000000-0000-0000-0000-000000000006', 'esraa', 'test-only', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system'),
('00000000-0000-0000-0000-000000000007', 'auditor_user', 'test-only', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system'),
('00000000-0000-0000-0000-000000000008', 'project_editor', 'test-only', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system'),
('00000000-0000-0000-0000-000000000009', 'page_author', 'test-only', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system'),
('00000000-0000-0000-0000-000000000010', 'version_author', 'test-only', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system');

INSERT INTO gsuif_user_role (user_id, role_id, created_at, updated_at, created_by, last_modified_by) VALUES
('00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system'),
('00000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system'),
('00000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system'),
('00000000-0000-0000-0000-000000000006', '00000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system'),
('00000000-0000-0000-0000-000000000007', '00000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system'),
('00000000-0000-0000-0000-000000000008', '00000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system'),
('00000000-0000-0000-0000-000000000009', '00000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system'),
('00000000-0000-0000-0000-000000000010', '00000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system');

-- V7 creates the permission rows before this test-only role is seeded. Grant the
-- migrated permissions to the test ADMIN role without duplicating production data.
INSERT INTO gsuif_role_permission
    (role_id, permission_id, created_at, updated_at, created_by, last_modified_by)
SELECT r.id, p.id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system'
FROM gsuif_role r
CROSS JOIN gsuif_permission p
WHERE r.name = 'ROLE_ADMIN'
  AND p.code LIKE 'ENTITY_ACCESS:%'
  AND NOT EXISTS (
      SELECT 1 FROM gsuif_role_permission existing
      WHERE existing.role_id = r.id
        AND existing.permission_id = p.id
  );
