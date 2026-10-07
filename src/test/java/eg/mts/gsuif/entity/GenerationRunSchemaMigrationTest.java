package eg.mts.gsuif.entity;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationRunSchemaMigrationTest {

    @Test void v2AddsDiagnosticsToExistingV1Database() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:migration_" + UUID.randomUUID() + ";MODE=PostgreSQL", "sa", "")) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/V1__init_schema.sql"));
            assertFalse(hasColumn(connection, "COMPILE_EXIT_CODE"));

            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/V2__add_build_diagnostics.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/V3__add_generated_artifact_registry.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/V4__add_generation_attempt_fingerprint.sql"));
            assertTrue(hasColumn(connection, "GSUIF_GENERATION_RUN", "INPUT_FINGERPRINT"));
            assertTrue(hasColumn(connection, "GSUIF_GENERATION_RUN", "ATTEMPT_ID"));
            assertTrue(hasColumn(connection, "GSUIF_GENERATED_ARTIFACT", "RELATIVE_PATH"));
            assertTrue(hasColumn(connection, "GSUIF_GENERATED_ARTIFACT", "TEMPLATE_VERSION"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/V3__add_generated_artifact_registry.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/V4__add_generation_attempt_fingerprint.sql"));
            for (String column : new String[] {
                    "COMPILE_EXIT_CODE", "TEST_EXIT_CODE", "COMPILE_OUTPUT", "TEST_OUTPUT" }) {
                assertTrue(hasColumn(connection, column), column);
            }

            // The migration is safe when test setup or deployment applies it again.
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/V2__add_build_diagnostics.sql"));
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
