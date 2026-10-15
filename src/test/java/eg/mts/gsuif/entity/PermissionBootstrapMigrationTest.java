package eg.mts.gsuif.entity;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionBootstrapMigrationTest {

    @Test
    void v6CreatesAndGrantsEntityPermissionsIdempotently() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:permission_migration_" + UUID.randomUUID() + ";MODE=PostgreSQL", "sa", "")) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/V1__init_schema.sql"));
            seedExistingAdminRole(connection);
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("sql/V5__add_permission_role_permission.sql"));

            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("sql/V7__bootstrap_entity_permissions.sql"));

            assertEquals(4, count(connection,
                    "SELECT COUNT(*) FROM gsuif_permission WHERE code LIKE 'ENTITY_ACCESS:%'"));
            assertEquals(4, count(connection, """
                    SELECT COUNT(*)
                    FROM gsuif_role r
                    JOIN gsuif_role_permission rp ON rp.role_id = r.id
                    JOIN gsuif_permission p ON p.id = rp.permission_id
                    WHERE r.name = 'ROLE_ADMIN' AND p.code LIKE 'ENTITY_ACCESS:%'
                    """));
            for (String code : new String[] {
                    "ENTITY_ACCESS:GsuifProject",
                    "ENTITY_ACCESS:GsuifPage",
                    "ENTITY_ACCESS:MetadataVersion",
                    "ENTITY_ACCESS:WorkOrder" }) {
                assertTrue(exists(connection, code), code);
            }

            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("sql/V7__bootstrap_entity_permissions.sql"));
            assertEquals(4, count(connection,
                    "SELECT COUNT(*) FROM gsuif_permission WHERE code LIKE 'ENTITY_ACCESS:%'"));
            assertEquals(4, count(connection, "SELECT COUNT(*) FROM gsuif_role_permission"));
        }
    }

    private void seedExistingAdminRole(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO gsuif_role
                    (id, name, created_at, updated_at, created_by, last_modified_by)
                VALUES
                    ('00000000-0000-0000-0000-000000000001', 'ROLE_ADMIN',
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system')
                """)) {
            statement.executeUpdate();
        }
    }

    private int count(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }

    private boolean exists(Connection connection, String code) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM gsuif_permission WHERE code = ?")) {
            statement.setString(1, code);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }
}
