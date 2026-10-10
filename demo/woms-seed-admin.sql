-- Disposable local demo account: admin / password123.
INSERT INTO gsuif_role (id, name, created_at, updated_at, created_by, last_modified_by)
VALUES ('00000000-0000-0000-0000-000000000001', 'ROLE_ADMIN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system');

INSERT INTO gsuif_user (id, username, password_hash, created_at, updated_at, created_by, last_modified_by)
VALUES ('00000000-0000-0000-0000-000000000002', 'admin', '$2a$10$s0BHlupflpolkNsDOsaBEuEftehIkhgsKfeFL9ZxGrb0BL.nmMiUW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system');

INSERT INTO gsuif_user_role (user_id, role_id, created_at, updated_at, created_by, last_modified_by)
VALUES ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system');
