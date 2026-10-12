# SCRUM-61 permission provisioning

SCRUM-61 uses free-text codes in `gsuif_permission` for service-layer authorization:

- `ENTITY_ACCESS:{SimpleEntityName}` grants access to an entity type. The initial entity names are `GsuifProject`, `GsuifPage`, `MetadataVersion`, and `WorkOrder`.
- `FIELD_DENY:{SimpleEntityName}:{fieldName}` masks that field in read-path DTO responses. Write-path field enforcement is not part of SCRUM-61 and remains open under OQ-12.

## Automatic upgrade behavior

`V6__bootstrap_entity_permissions.sql` creates the four initial `ENTITY_ACCESS:*` permission rows when they do not already exist. V1 and V5 create the security tables but do not seed roles, so V6 does not create a role or invent a role ID. If the established full-access role `ROLE_ADMIN` exists when V6 runs, V6 grants all four permissions to it. Re-running V6 is safe.

Deployments that provision `ROLE_ADMIN` after applying V6 must run the grant statement below after creating the role.

## Grant permissions to additional roles

Grant entity access to an existing custom role:

```sql
INSERT INTO gsuif_role_permission
    (role_id, permission_id, created_at, updated_at, created_by, last_modified_by)
SELECT r.id, p.id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system'
FROM gsuif_role r
JOIN gsuif_permission p ON p.code = 'ENTITY_ACCESS:GsuifProject'
WHERE r.name = 'ROLE_PROJECT_EDITOR'
  AND NOT EXISTS (
      SELECT 1 FROM gsuif_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
```

Create a read-response field denial and grant it to a role:

```sql
INSERT INTO gsuif_permission
    (id, code, name, description, created_at, updated_at, created_by, last_modified_by)
SELECT
    '11111111-1111-1111-1111-111111111111',
    'FIELD_DENY:WorkOrder:assignedTo',
    'Hide work-order assignee',
    'Masks assignedTo in WorkOrder read responses.',
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system'
WHERE NOT EXISTS (
    SELECT 1 FROM gsuif_permission WHERE code = 'FIELD_DENY:WorkOrder:assignedTo'
);

INSERT INTO gsuif_role_permission
    (role_id, permission_id, created_at, updated_at, created_by, last_modified_by)
SELECT r.id, p.id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system'
FROM gsuif_role r
JOIN gsuif_permission p ON p.code = 'FIELD_DENY:WorkOrder:assignedTo'
WHERE r.name = 'ROLE_WORK_ORDER_READER'
  AND NOT EXISTS (
      SELECT 1 FROM gsuif_role_permission rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
```

Use deployment-specific UUIDs for newly provisioned permission rows.

## Verify an upgrade

```sql
SELECT code
FROM gsuif_permission
WHERE code LIKE 'ENTITY_ACCESS:%';
```

```sql
SELECT r.name, p.code
FROM gsuif_role r
JOIN gsuif_role_permission rp ON rp.role_id = r.id
JOIN gsuif_permission p ON p.id = rp.permission_id
WHERE p.code LIKE 'ENTITY_ACCESS:%';
```
