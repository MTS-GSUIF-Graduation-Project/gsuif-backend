-- SCRUM-61: bootstrap entity permissions for the established full-access ROLE_ADMIN role.
-- V1 and V5 create the security tables but do not create role rows. The grant below
-- therefore applies when ROLE_ADMIN already exists and does not invent a deployment role.

INSERT INTO gsuif_permission
    (id, code, name, description, created_at, updated_at, created_by, last_modified_by)
SELECT
    '00000000-0000-0000-0000-000000000201',
    'ENTITY_ACCESS:GsuifProject',
    'Access GSUIF projects',
    'Allows service-layer access to GsuifProject entities.',
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system'
WHERE NOT EXISTS (
    SELECT 1 FROM gsuif_permission WHERE code = 'ENTITY_ACCESS:GsuifProject'
);

INSERT INTO gsuif_permission
    (id, code, name, description, created_at, updated_at, created_by, last_modified_by)
SELECT
    '00000000-0000-0000-0000-000000000202',
    'ENTITY_ACCESS:GsuifPage',
    'Access GSUIF pages',
    'Allows service-layer access to GsuifPage entities.',
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system'
WHERE NOT EXISTS (
    SELECT 1 FROM gsuif_permission WHERE code = 'ENTITY_ACCESS:GsuifPage'
);

INSERT INTO gsuif_permission
    (id, code, name, description, created_at, updated_at, created_by, last_modified_by)
SELECT
    '00000000-0000-0000-0000-000000000203',
    'ENTITY_ACCESS:MetadataVersion',
    'Access metadata versions',
    'Allows service-layer access to MetadataVersion entities.',
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system'
WHERE NOT EXISTS (
    SELECT 1 FROM gsuif_permission WHERE code = 'ENTITY_ACCESS:MetadataVersion'
);

INSERT INTO gsuif_permission
    (id, code, name, description, created_at, updated_at, created_by, last_modified_by)
SELECT
    '00000000-0000-0000-0000-000000000204',
    'ENTITY_ACCESS:WorkOrder',
    'Access work orders',
    'Allows service-layer access to WorkOrder entities.',
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system'
WHERE NOT EXISTS (
    SELECT 1 FROM gsuif_permission WHERE code = 'ENTITY_ACCESS:WorkOrder'
);

INSERT INTO gsuif_role_permission
    (role_id, permission_id, created_at, updated_at, created_by, last_modified_by)
SELECT
    r.id, p.id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system'
FROM gsuif_role r
CROSS JOIN gsuif_permission p
WHERE r.name = 'ROLE_ADMIN'
  AND p.code IN (
      'ENTITY_ACCESS:GsuifProject',
      'ENTITY_ACCESS:GsuifPage',
      'ENTITY_ACCESS:MetadataVersion',
      'ENTITY_ACCESS:WorkOrder'
  )
  AND NOT EXISTS (
      SELECT 1
      FROM gsuif_role_permission existing
      WHERE existing.role_id = r.id
        AND existing.permission_id = p.id
  );
