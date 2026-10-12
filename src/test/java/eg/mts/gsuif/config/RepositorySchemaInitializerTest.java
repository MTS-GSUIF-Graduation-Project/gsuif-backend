package eg.mts.gsuif.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.ApplicationDataSourceScriptDatabaseInitializer;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RepositorySchemaInitializerTest {

    @Test
    void cleanAndExistingSchemaKeepAuditRowsAndIdentityContinuity() throws Exception {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:startup_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        initialize(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM work_orders"));
            connection.createStatement().executeUpdate("INSERT INTO revinfo (rev, revtstmp) VALUES (100, 1)");
            connection.createStatement().executeUpdate("""
                    INSERT INTO gsuif_project_aud (id, rev, revtype, name)
                    VALUES ('00000000-0000-0000-0000-000000000001', 100, 0, 'Existing project')
                    """);
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM revinfo"));
        }

        // A second startup must not replay V1/V5 or remove a deployed audit revision.
        initialize(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM work_orders"));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM revinfo"));
            connection.createStatement().executeUpdate("INSERT INTO revinfo (revtstmp) VALUES (2)");
            assertEquals(101, scalar(connection, "SELECT MAX(rev) FROM revinfo"));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM gsuif_project_aud WHERE rev = 100"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM gsuif_permission"));
        }
    }

    @Test
    void existingIdentityAheadOfStoredRevisionsIsNotMovedBackward() throws Exception {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:startup_ahead_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        initialize(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().executeUpdate("INSERT INTO revinfo (rev, revtstmp) VALUES (100, 1)");
            connection.createStatement().execute("ALTER TABLE revinfo ALTER COLUMN rev RESTART WITH 200");
        }

        initialize(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().executeUpdate("INSERT INTO revinfo (revtstmp) VALUES (2)");
            assertEquals(200, scalar(connection, "SELECT MAX(rev) FROM revinfo"));
        }
    }

    private static void initialize(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ApplicationDataSourceScriptDatabaseInitializer initializer =
                    new ApplicationDataSourceScriptDatabaseInitializer(
                            dataSource, RepositorySchemaInitializer.settingsFor(connection));
            initializer.setResourceLoader(new DefaultResourceLoader());
            initializer.afterPropertiesSet();
        }
    }

    private static int scalar(Connection connection, String sql) throws Exception {
        try (ResultSet rows = connection.createStatement().executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
