package eg.mts.gsuif.entity;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in, real PostgreSQL migration gate. Use only a newly created disposable localhost database. */
class GenerationRunPostgresStatusMigrationTest {
    private static final String[] MIGRATIONS = {
            "init_schema", "add_build_diagnostics", "add_generated_artifact_registry",
            "add_generation_attempt_fingerprint", "add_permission_role_permission", "add_work_orders",
            "bootstrap_entity_permissions", "add_generation_attempt_lifecycle",
            "add_backend_package_manifest_hash", "repair_generation_run_status_constraint"
    };

    @Test void repairsLegacyConstraint() throws Exception { verifyUpgrade(Layout.LEGACY); }
    @Test void repairsCanonicalConstraint() throws Exception { verifyUpgrade(Layout.CANONICAL); }
    @Test void repairsBothConstraints() throws Exception { verifyUpgrade(Layout.BOTH); }

    private enum Layout { LEGACY, CANONICAL, BOTH }

    private void verifyUpgrade(Layout layout) throws Exception {
        String url = System.getProperty("gsuif.test.status-migration-jdbc-url");
        Assumptions.assumeTrue("true".equals(System.getProperty("gsuif.test.status-migration-disposable"))
                        && url != null && url.matches("jdbc:postgresql://127\\.0\\.0\\.1:\\d+/gsuif_status_migration_test"),
                "Requires an explicitly designated disposable localhost PostgreSQL database");
        String password = System.getProperty("gsuif.test.status-migration-password");
        String schema = "status_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(url, "postgres", password)) {
            execute(connection, "CREATE SCHEMA " + schema);
            try {
                connection.setSchema(schema);
                for (int version = 1; version <= 7; version++) migrate(connection, version);
                String project = id(), page = id(), metadata = id(), user = id();
                String historicalRun = id(), artifact = id();
                execute(connection, "INSERT INTO gsuif_project (id,name,created_at,updated_at,created_by,last_modified_by) "
                        + "VALUES ('" + project + "','historical',now(),now(),'test','test')");
                execute(connection, "INSERT INTO gsuif_page (id,project_id,name,created_at,updated_at,created_by,last_modified_by) "
                        + "VALUES ('" + page + "','" + project + "','historical',now(),now(),'test','test')");
                execute(connection, "INSERT INTO gsuif_metadata_version "
                        + "(id,project_id,page_id,version,schema_version,snapshot,created_at,updated_at,created_by,last_modified_by) "
                        + "VALUES ('" + metadata + "','" + project + "','" + page
                        + "',1,'1.0.0','{}',now(),now(),'test','test')");
                execute(connection, "INSERT INTO gsuif_user "
                        + "(id,username,password_hash,created_at,updated_at,created_by,last_modified_by) "
                        + "VALUES ('" + user + "','historical','hash',now(),now(),'test','test')");
                insertRun(connection, historicalRun, metadata, user, "SUCCESS");
                execute(connection, "INSERT INTO gsuif_generated_artifact "
                        + "(id,generation_run_id,artifact_name,artifact_type,created_at,updated_at,created_by,last_modified_by) "
                        + "VALUES ('" + artifact + "','" + historicalRun
                        + "','historical.java','java-source',now(),now(),'test','test')");

                if (layout == Layout.LEGACY) {
                    execute(connection, "ALTER TABLE gsuif_generation_run DROP CONSTRAINT ck_gsuif_generation_run_status");
                    addLegacy(connection);
                }
                migrate(connection, 8);
                migrate(connection, 9);
                if (layout == Layout.BOTH) addLegacy(connection);
                if (layout != Layout.CANONICAL) {
                    SQLException rejected = assertThrows(SQLException.class,
                            () -> execute(connection, "UPDATE gsuif_generation_run SET status='IN_PROGRESS' WHERE id='"
                                    + historicalRun + "'"));
                    assertEquals("23514", rejected.getSQLState());
                }
                migrate(connection, 10);

                assertEquals(1, count(connection, "SELECT count(*) FROM pg_constraint WHERE conrelid="
                        + "'gsuif_generation_run'::regclass AND conname='ck_gsuif_generation_run_status'"));
                assertEquals(0, count(connection, "SELECT count(*) FROM pg_constraint WHERE conrelid="
                        + "'gsuif_generation_run'::regclass AND conname='gsuif_generation_run_status_check'"));
                assertEquals(1, count(connection, "SELECT count(*) FROM gsuif_generation_run r "
                        + "JOIN gsuif_generated_artifact a ON a.generation_run_id=r.id WHERE r.id='"
                        + historicalRun + "' AND r.status='SUCCESS' AND a.id='" + artifact + "'"));
                assertEquals(1, count(connection, "SELECT count(*) FROM gsuif_generated_artifact "
                        + "WHERE id='" + artifact + "' AND relative_path IS NULL"));

                String successful = id(), failed = id();
                insertRun(connection, successful, metadata, user, "IN_PROGRESS");
                insertRun(connection, failed, metadata, user, "IN_PROGRESS");
                execute(connection, "UPDATE gsuif_generation_run SET status='SUCCESS' WHERE id='" + successful + "'");
                execute(connection, "UPDATE gsuif_generation_run SET status='FAILED' WHERE id='" + failed + "'");
                assertEquals(1, count(connection, "SELECT count(*) FROM gsuif_generation_run WHERE id='"
                        + successful + "' AND status='SUCCESS'"));
                assertEquals(1, count(connection, "SELECT count(*) FROM gsuif_generation_run WHERE id='"
                        + failed + "' AND status='FAILED'"));
                SQLException invalid = assertThrows(SQLException.class,
                        () -> execute(connection, "UPDATE gsuif_generation_run SET status='BOGUS' WHERE id='"
                                + failed + "'"));
                assertEquals("23514", invalid.getSQLState());
                assertTrue(count(connection, "SELECT count(*) FROM gsuif_generation_run WHERE id='"
                        + failed + "' AND status='FAILED'") == 1);
            } finally {
                connection.setSchema("public");
                execute(connection, "DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static void addLegacy(Connection connection) throws SQLException {
        execute(connection, "ALTER TABLE gsuif_generation_run ADD CONSTRAINT gsuif_generation_run_status_check "
                + "CHECK (status IN ('SUCCESS', 'BUILD_FAILED'))");
    }

    private static void migrate(Connection connection, int version) {
        ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                "sql/V" + version + "__" + MIGRATIONS[version - 1] + ".sql"));
    }

    private static void insertRun(Connection connection, String run, String metadata, String user, String status)
            throws SQLException {
        execute(connection, "INSERT INTO gsuif_generation_run "
                + "(id,metadata_version_id,template_version,generator_version,tool_name,tool_version,status,"
                + "triggering_user_id,created_at,updated_at,created_by,last_modified_by) VALUES ('"
                + run + "','" + metadata + "','1','1','test','1','" + status + "','" + user
                + "',now(),now(),'test','test')");
    }

    private static int count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute(sql); }
    }

    private static String id() { return UUID.randomUUID().toString(); }
}
