package eg.mts.gsuif.config;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.boot.jdbc.autoconfigure.ApplicationDataSourceScriptDatabaseInitializer;
import org.springframework.boot.sql.init.DatabaseInitializationMode;
import org.springframework.boot.sql.init.DatabaseInitializationSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;

/** Applies the packaged SQL before JPA validates the schema. */
@Configuration
@Profile({"local", "prod"})
public class RepositorySchemaInitializer {

    private static final Set<String> BASE_TABLES = Set.of(
            "gsuif_project", "gsuif_page", "gsuif_metadata_version", "gsuif_user",
            "gsuif_role", "gsuif_user_role", "gsuif_generation_run",
            "gsuif_generated_artifact", "revinfo", "gsuif_project_aud",
            "gsuif_page_aud", "gsuif_metadata_version_aud");
    private static final Set<String> PERMISSION_TABLES = Set.of(
            "gsuif_permission", "gsuif_role_permission");

    @Bean
    ApplicationDataSourceScriptDatabaseInitializer repositoryDatabaseInitializer(DataSource dataSource,
            @Value("${gsuif.schema.allow-h2-initialization:false}") boolean allowH2Initialization)
            throws SQLException {
        DatabaseInitializationSettings settings = new DatabaseInitializationSettings();
        try (Connection connection = dataSource.getConnection()) {
            // Some profile-specific tests replace PostgreSQL with H2 and manage their own schema.
            if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())
                    && !allowH2Initialization) {
                settings.setMode(DatabaseInitializationMode.NEVER);
                return new ApplicationDataSourceScriptDatabaseInitializer(dataSource, settings);
            }
            settings = settingsFor(connection);
        }
        return new ApplicationDataSourceScriptDatabaseInitializer(dataSource, settings);
    }

    static DatabaseInitializationSettings settingsFor(Connection connection) throws SQLException {
        Set<String> tables = existingTables(connection);
        int baseCount = countPresent(tables, BASE_TABLES);
        int permissionCount = countPresent(tables, PERMISSION_TABLES);
        if ((baseCount != 0 && baseCount != BASE_TABLES.size())
                || (permissionCount != 0 && permissionCount != PERMISSION_TABLES.size())
                || (baseCount == 0 && permissionCount != 0)) {
            throw new IllegalStateException("Incomplete repository schema; inspect it before applying migrations");
        }
        if (baseCount == BASE_TABLES.size()) {
            alignRevisionIdentity(connection);
        }
        List<String> scripts = new ArrayList<>();
        if (baseCount == 0) {
            scripts.add("classpath:sql/V1__init_schema.sql");
        }
        // These forward scripts are intentionally repeatable and preserve existing rows.
        scripts.add("classpath:sql/V2__add_build_diagnostics.sql");
        scripts.add("classpath:sql/V3__add_generated_artifact_registry.sql");
        scripts.add("classpath:sql/V4__add_generation_attempt_fingerprint.sql");
        if (permissionCount == 0) {
            scripts.add("classpath:sql/V5__add_permission_role_permission.sql");
        }
        if (!tables.contains("work_orders")) {
            scripts.add("classpath:sql/V6__add_work_orders.sql");
        }
        DatabaseInitializationSettings settings = new DatabaseInitializationSettings();
        settings.setSchemaLocations(scripts);
        settings.setMode(DatabaseInitializationMode.ALWAYS);
        return settings;
    }

    private static void alignRevisionIdentity(Connection connection) throws SQLException {
        long maximum;
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT MAX(rev) FROM revinfo")) {
            rows.next();
            maximum = rows.getLong(1);
            if (rows.wasNull()) {
                return;
            }
        }
        if (maximum >= Integer.MAX_VALUE) {
            throw new IllegalStateException("revinfo identity has exhausted its integer range");
        }
        long nextValue;
        if ("PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
            // pg_sequences.last_value is null until the identity has been used. A cached
            // value may be ahead of the next generated value, which is safe to keep.
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery("""
                            SELECT s.last_value
                            FROM pg_sequences s
                            WHERE s.schemaname = current_schema()
                              AND s.sequencename = (
                                  SELECT c.relname FROM pg_class c
                                  WHERE c.oid = pg_get_serial_sequence('revinfo', 'rev')::regclass)
                            """)) {
                if (!rows.next()) {
                    throw new IllegalStateException("revinfo identity sequence is missing");
                }
                nextValue = rows.getLong(1);
                if (rows.wasNull()) {
                    nextValue = 1;
                } else {
                    nextValue++;
                }
            }
        } else {
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery("""
                            SELECT identity_base FROM information_schema.columns
                            WHERE table_schema = current_schema()
                              AND table_name = 'REVINFO' AND column_name = 'REV'
                            """)) {
                if (!rows.next()) {
                    throw new IllegalStateException("revinfo identity column is missing");
                }
                nextValue = rows.getLong(1);
            }
        }
        if (nextValue > maximum) {
            return;
        }
        // Older Envers versions assigned explicit values from revinfo_seq; those
        // inserts did not advance the identity sequence declared in V1.
        try (var alter = connection.createStatement()) {
            alter.execute("ALTER TABLE revinfo ALTER COLUMN rev RESTART WITH " + (maximum + 1));
        }
    }

    private static int countPresent(Set<String> tables, Set<String> required) {
        int count = 0;
        for (String table : required) {
            if (tables.contains(table)) {
                count++;
            }
        }
        return count;
    }

    private static Set<String> existingTables(Connection connection) throws SQLException {
        Set<String> tables = new HashSet<>();
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet rows = metadata.getTables(connection.getCatalog(), connection.getSchema(), "%", new String[] {"TABLE"})) {
            while (rows.next()) {
                tables.add(rows.getString("TABLE_NAME").toLowerCase(java.util.Locale.ROOT));
            }
        }
        return tables;
    }
}
