-- Test-only schema for the pre-existing WorkOrder entity, which is outside T-14.
-- The GSUIF tables under test are loaded from sql/V1__init_schema.sql followed by sql/V2__add_build_diagnostics.sql.
CREATE TABLE work_orders (
    id               UUID                     NOT NULL,
    order_number     VARCHAR(64)              NOT NULL,
    status           VARCHAR(32)              NOT NULL,
    due_date         DATE                     NOT NULL,
    assigned_to      VARCHAR(128)             NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by       VARCHAR(100)             NOT NULL,
    last_modified_by VARCHAR(100)             NOT NULL,
    CONSTRAINT pk_work_orders PRIMARY KEY (id),
    CONSTRAINT uk_work_orders_order_number UNIQUE (order_number)
);

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

INSERT INTO gsuif_permission (id, code, name, created_at, updated_at, created_by, last_modified_by) VALUES
('00000000-0000-0000-0000-000000000101', 'ENTITY_ACCESS:GsuifProject', 'Test project access', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system'),
('00000000-0000-0000-0000-000000000102', 'ENTITY_ACCESS:GsuifPage', 'Test page access', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system'),
('00000000-0000-0000-0000-000000000103', 'ENTITY_ACCESS:WorkOrder', 'Test work order access', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system'),
('00000000-0000-0000-0000-000000000104', 'ENTITY_ACCESS:MetadataVersion', 'Test metadata version access', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system');

INSERT INTO gsuif_role_permission (role_id, permission_id, created_at, updated_at, created_by, last_modified_by) VALUES
('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000101', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system'),
('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000102', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system'),
('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000103', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system'),
('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000104', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system');
