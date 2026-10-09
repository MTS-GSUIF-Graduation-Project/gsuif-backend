-- SCRUM-59: generic permission persistence with Spring Data JPA lifecycle audit fields.
-- This migration does not add Envers grant/revoke revision history.
CREATE TABLE gsuif_permission (
    id               CHAR(36)                  NOT NULL,
    code             VARCHAR(50)               NOT NULL,
    name             VARCHAR(200)              NOT NULL,
    description      TEXT                      NULL,
    created_at       TIMESTAMP WITH TIME ZONE  NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE  NOT NULL,
    created_by       VARCHAR(100)              NOT NULL,
    last_modified_by VARCHAR(100)              NOT NULL,
    CONSTRAINT pk_gsuif_permission PRIMARY KEY (id),
    CONSTRAINT uk_gsuif_permission_code UNIQUE (code)
);

CREATE TABLE gsuif_role_permission (
    role_id          CHAR(36)                  NOT NULL,
    permission_id    CHAR(36)                  NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE  NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE  NOT NULL,
    created_by       VARCHAR(100)              NOT NULL,
    last_modified_by VARCHAR(100)              NOT NULL,
    CONSTRAINT pk_gsuif_role_permission PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_gsuif_role_permission_role_id
        FOREIGN KEY (role_id) REFERENCES gsuif_role (id),
    CONSTRAINT fk_gsuif_role_permission_permission_id
        FOREIGN KEY (permission_id) REFERENCES gsuif_permission (id)
);

CREATE INDEX idx_gsuif_role_permission_permission_id
    ON gsuif_role_permission (permission_id);
