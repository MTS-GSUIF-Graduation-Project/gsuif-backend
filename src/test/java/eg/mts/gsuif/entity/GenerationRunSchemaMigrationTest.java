package eg.mts.gsuif.entity;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationRunSchemaMigrationTest {

    @Test void v8PreservesExistingRunsAndArtifacts() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:migration_" + UUID.randomUUID() + ";MODE=PostgreSQL", "sa", "")) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/V1__init_schema.sql"));
            assertFalse(hasColumn(connection, "COMPILE_EXIT_CODE"));

            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/V2__add_build_diagnostics.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/V3__add_generated_artifact_registry.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/V4__add_generation_attempt_fingerprint.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/V5__add_permission_role_permission.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/V6__add_work_orders.sql"));
            String project = UUID.randomUUID().toString(), page = UUID.randomUUID().toString();
            String version = UUID.randomUUID().toString(), user = UUID.randomUUID().toString();
            String run = UUID.randomUUID().toString(), artifact = UUID.randomUUID().toString();
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO gsuif_project (id, name, created_at, updated_at, created_by, last_modified_by) "
                        + "VALUES ('" + project + "', 'existing', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'test', 'test')");
                statement.executeUpdate("INSERT INTO gsuif_page (id, project_id, name, created_at, updated_at, created_by, last_modified_by) "
                        + "VALUES ('" + page + "', '" + project + "', 'existing', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'test', 'test')");
                statement.executeUpdate("INSERT INTO gsuif_metadata_version (id, project_id, page_id, version, schema_version, snapshot, "
                        + "created_at, updated_at, created_by, last_modified_by) VALUES ('" + version + "', '" + project
                        + "', '" + page + "', 1, '1.0.0', '{}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'test', 'test')");
                statement.executeUpdate("INSERT INTO gsuif_user (id, username, password_hash, created_at, updated_at, created_by, last_modified_by) "
                        + "VALUES ('" + user + "', 'existing', 'hash', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'test', 'test')");
                statement.executeUpdate("INSERT INTO gsuif_generation_run (id, metadata_version_id, template_version, generator_version, "
                        + "tool_name, tool_version, status, triggering_user_id, created_at, updated_at, created_by, last_modified_by) "
                        + "VALUES ('" + run + "', '" + version + "', '1', '1', 'legacy', '1', 'SUCCESS', '" + user
                        + "', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'test', 'test')");
                statement.executeUpdate("INSERT INTO gsuif_generated_artifact (id, generation_run_id, artifact_name, artifact_type, "
                        + "created_at, updated_at, created_by, last_modified_by) VALUES ('" + artifact + "', '" + run
                        + "', 'existing.java', 'java-source', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'test', 'test')");
            }
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/V8__add_generation_attempt_lifecycle.sql"));
            assertTrue(hasColumn(connection, "GSUIF_GENERATION_RUN", "INPUT_FINGERPRINT"));
            assertTrue(hasColumn(connection, "GSUIF_GENERATION_RUN", "ATTEMPT_ID"));
            assertTrue(hasColumn(connection, "GSUIF_GENERATED_ARTIFACT", "RELATIVE_PATH"));
            assertTrue(hasColumn(connection, "GSUIF_GENERATED_ARTIFACT", "TEMPLATE_VERSION"));
            assertTrue(hasColumn(connection, "GSUIF_GENERATION_RUN", "FAILURE_STAGE"));
            assertTrue(hasColumn(connection, "GSUIF_GENERATION_RUN", "FAILURE_CODE"));
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT r.status, r.failure_stage, r.failure_code, a.artifact_name "
                            + "FROM gsuif_generation_run r JOIN gsuif_generated_artifact a ON a.generation_run_id = r.id "
                            + "WHERE r.id = '" + run + "'")) {
                assertTrue(rows.next());
                assertEquals("SUCCESS", rows.getString(1));
                assertEquals(null, rows.getString(2));
                assertEquals(null, rows.getString(3));
                assertEquals("existing.java", rows.getString(4));
                assertFalse(rows.next());
            }
            for (String column : new String[] {
                    "COMPILE_EXIT_CODE", "TEST_EXIT_CODE", "COMPILE_OUTPUT", "TEST_OUTPUT" }) {
                assertTrue(hasColumn(connection, column), column);
            }

        }
    }

    private boolean hasColumn(Connection connection, String column) throws Exception {
        return hasColumn(connection, "GSUIF_GENERATION_RUN", column);
    }

    private boolean hasColumn(Connection connection, String table, String column) throws Exception {
        try (ResultSet columns = connection.getMetaData().getColumns(null, null,
                table, column)) {
            return columns.next();
        }
    }
}
