-- Add the existing WorkOrder API's mapped table without changing V1 or existing rows.
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
