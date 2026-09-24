-- T-14 / SCRUM-21: portable Phase-1 persistence schema.
-- Target: PostgreSQL (dev/reference) and H2 in PostgreSQL compatibility mode (test/CI).
-- Identifiers are application-generated CHAR(36) UUID strings. No sequences, SERIAL, JSONB, CITEXT, or vendor functions.
-- Foreign keys use the database default (NO ACTION / RESTRICT). No ON DELETE / ON UPDATE clauses.

CREATE TABLE gsuif_project (
    id               CHAR(36)                  NOT NULL,
    name             VARCHAR(200)              NOT NULL,
    description      TEXT                      NULL,
    created_at       TIMESTAMP WITH TIME ZONE  NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE  NOT NULL,
    created_by       VARCHAR(100)              NOT NULL,
    last_modified_by VARCHAR(100)              NOT NULL,
    CONSTRAINT pk_gsuif_project PRIMARY KEY (id),
    CONSTRAINT uk_gsuif_project_name UNIQUE (name)
);

CREATE TABLE gsuif_page (
    id               CHAR(36)                  NOT NULL,
    project_id       CHAR(36)                  NOT NULL,
    name             VARCHAR(200)              NOT NULL,
    -- route is intentionally nullable; multiple pages may have no route assigned.
    -- When provided, the route must be unique within the project.
    route            VARCHAR(255)              NULL,
    created_at       TIMESTAMP WITH TIME ZONE  NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE  NOT NULL,
    created_by       VARCHAR(100)              NOT NULL,
    last_modified_by VARCHAR(100)              NOT NULL,
    current_metadata_version_id CHAR(36)       NULL,
    CONSTRAINT pk_gsuif_page PRIMARY KEY (id),
    CONSTRAINT uk_gsuif_page_project_id_name UNIQUE (project_id, name),
    CONSTRAINT uk_gsuif_page_project_id_route UNIQUE (project_id, route),
    CONSTRAINT uk_gsuif_page_project_id_id UNIQUE (project_id, id),
    CONSTRAINT fk_gsuif_page_project_id FOREIGN KEY (project_id) REFERENCES gsuif_project (id)
);

CREATE TABLE gsuif_metadata_version (
    id               CHAR(36)                  NOT NULL,
    project_id       CHAR(36)                  NOT NULL,
    page_id          CHAR(36)                  NOT NULL,
    version          INTEGER                   NOT NULL,
    schema_version   VARCHAR(20)               NOT NULL,
    snapshot         TEXT                      NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE  NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE  NOT NULL,
    created_by       VARCHAR(100)              NOT NULL,
    last_modified_by VARCHAR(100)              NOT NULL,
    CONSTRAINT pk_gsuif_metadata_version PRIMARY KEY (id),
    CONSTRAINT uk_gsuif_metadata_version_page_id_id UNIQUE (page_id, id),
    CONSTRAINT uk_gsuif_metadata_version_page_id_version UNIQUE (page_id, version),
    CONSTRAINT ck_gsuif_metadata_version_version CHECK (version >= 1),
    CONSTRAINT fk_gsuif_metadata_version_project_id_page_id
        FOREIGN KEY (project_id, page_id) REFERENCES gsuif_page (project_id, id)
);

CREATE TABLE gsuif_user (
    id               CHAR(36)                  NOT NULL,
    username         VARCHAR(50)               NOT NULL,
    password_hash    VARCHAR(255)              NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE  NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE  NOT NULL,
    created_by       VARCHAR(100)              NOT NULL,
    last_modified_by VARCHAR(100)              NOT NULL,
    CONSTRAINT pk_gsuif_user PRIMARY KEY (id),
    CONSTRAINT uk_gsuif_user_username UNIQUE (username)
);

CREATE TABLE gsuif_role (
    id               CHAR(36)                  NOT NULL,
    name             VARCHAR(50)               NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE  NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE  NOT NULL,
    created_by       VARCHAR(100)              NOT NULL,
    last_modified_by VARCHAR(100)              NOT NULL,
    CONSTRAINT pk_gsuif_role PRIMARY KEY (id),
    CONSTRAINT uk_gsuif_role_name UNIQUE (name)
);

CREATE TABLE gsuif_user_role (
    user_id          CHAR(36)                  NOT NULL,
    role_id          CHAR(36)                  NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE  NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE  NOT NULL,
    created_by       VARCHAR(100)              NOT NULL,
    last_modified_by VARCHAR(100)              NOT NULL,
    CONSTRAINT pk_gsuif_user_role PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_gsuif_user_role_user_id FOREIGN KEY (user_id) REFERENCES gsuif_user (id),
    CONSTRAINT fk_gsuif_user_role_role_id FOREIGN KEY (role_id) REFERENCES gsuif_role (id)
);

CREATE TABLE gsuif_generation_run (
    id                   CHAR(36)                  NOT NULL,
    metadata_version_id  CHAR(36)                  NOT NULL,
    template_version     VARCHAR(100)              NOT NULL,
    generator_version    VARCHAR(100)              NOT NULL,
    tool_name            VARCHAR(100)              NOT NULL,
    tool_version         VARCHAR(100)              NOT NULL,
    status               VARCHAR(20)               NOT NULL,
    triggering_user_id   CHAR(36)                  NOT NULL,
    created_at           TIMESTAMP WITH TIME ZONE  NOT NULL,
    updated_at           TIMESTAMP WITH TIME ZONE  NOT NULL,
    created_by           VARCHAR(100)              NOT NULL,
    last_modified_by     VARCHAR(100)              NOT NULL,
    CONSTRAINT pk_gsuif_generation_run PRIMARY KEY (id),
    CONSTRAINT ck_gsuif_generation_run_status CHECK (status IN ('SUCCESS', 'BUILD_FAILED')),
    CONSTRAINT fk_gsuif_generation_run_metadata_version_id
        FOREIGN KEY (metadata_version_id) REFERENCES gsuif_metadata_version (id),
    CONSTRAINT fk_gsuif_generation_run_triggering_user_id
        FOREIGN KEY (triggering_user_id) REFERENCES gsuif_user (id)
);

CREATE TABLE gsuif_generated_artifact (
    id                 CHAR(36)                  NOT NULL,
    generation_run_id  CHAR(36)                  NOT NULL,
    artifact_name      VARCHAR(255)              NOT NULL,
    artifact_type      VARCHAR(100)              NOT NULL,
    component_id       CHAR(36)                  NULL,
    created_at         TIMESTAMP WITH TIME ZONE  NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE  NOT NULL,
    created_by         VARCHAR(100)              NOT NULL,
    last_modified_by   VARCHAR(100)              NOT NULL,
    CONSTRAINT pk_gsuif_generated_artifact PRIMARY KEY (id),
    CONSTRAINT fk_gsuif_generated_artifact_generation_run_id
        FOREIGN KEY (generation_run_id) REFERENCES gsuif_generation_run (id)
);

-- Non-unique indexes: lookup by the non-leading FK side of user_role, and by generation FKs that are not unique.
-- Not added: page.project_id (covered by UNIQUE(project_id, name/route/id)), metadata_version.page_id (covered by UNIQUE(page_id, version)), user_role.user_id (leading PK column).
CREATE INDEX idx_gsuif_user_role_role_id ON gsuif_user_role (role_id);
CREATE INDEX idx_gsuif_generation_run_metadata_version_id ON gsuif_generation_run (metadata_version_id);
CREATE INDEX idx_gsuif_generation_run_triggering_user_id ON gsuif_generation_run (triggering_user_id);
CREATE INDEX idx_gsuif_generated_artifact_generation_run_id ON gsuif_generated_artifact (generation_run_id);

ALTER TABLE gsuif_page
    ADD CONSTRAINT fk_gsuif_page_current_version
    FOREIGN KEY (id, current_metadata_version_id)
    REFERENCES gsuif_metadata_version (page_id, id);
