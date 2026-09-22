-- Test-only schema for the pre-existing WorkOrder entity, which is outside T-14.
-- The GSUIF tables under test are loaded from sql/V1__init_schema.sql.
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
