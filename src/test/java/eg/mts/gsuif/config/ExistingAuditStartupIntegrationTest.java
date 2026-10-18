package eg.mts.gsuif.config;

import eg.mts.gsuif.entity.GsuifProject;
import eg.mts.gsuif.repository.GsuifProjectRepository;
import eg.mts.gsuif.service.AuditService;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Provisions repository SQL before startup, then verifies validation and existing audit history. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:existing_audit_startup;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@ActiveProfiles("local")
class ExistingAuditStartupIntegrationTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000073");

    @DynamicPropertySource
    static void seedBeforeStartup(DynamicPropertyRegistry registry) {
        try (var connection = DriverManager.getConnection(
                "jdbc:h2:mem:existing_audit_startup;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "")) {
            for (int version = 1; version <= 7; version++) {
                String[] names = {"init_schema", "add_build_diagnostics", "add_generated_artifact_registry",
                        "add_generation_attempt_fingerprint", "add_permission_role_permission", "add_work_orders" , "bootstrap_entity_permissions"};
                ScriptUtils.executeSqlScript(connection,
                        new ClassPathResource("sql/V" + version + "__" + names[version - 1] + ".sql"));
            }
            // Prepare the shipped schema through the additive BACKEND package migration.
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("sql/V8__add_generation_attempt_lifecycle.sql"));
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("sql/V9__add_backend_package_manifest_hash.sql"));
            connection.createStatement().executeUpdate("INSERT INTO revinfo (revtstmp) VALUES (1)");
            connection.createStatement().executeUpdate("""
                    INSERT INTO gsuif_project (id, name, description, created_at, updated_at, created_by, last_modified_by)
                    VALUES ('00000000-0000-0000-0000-000000000073', 'Deployed project', 'Before startup',
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'original_user', 'original_user')
                    """);
            connection.createStatement().executeUpdate("""
                    INSERT INTO gsuif_project_aud (id, rev, revtype, name, description, created_by, last_modified_by)
                    VALUES ('00000000-0000-0000-0000-000000000073', 1, 0,
                            'Deployed project', 'Before startup', 'original_user', 'original_user')
                    """);
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Autowired private GsuifProjectRepository projects;
    @Autowired private AuditService auditService;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @WithMockUser(username = "editor")
    void startupValidatesPreparedSchemaAndRetainsExistingHistory() {
        GsuifProject project = projects.findById(PROJECT_ID).orElseThrow();
        project.setDescription("After startup");
        projects.saveAndFlush(project);

        List<Number> revisions = auditService.getRevisions(GsuifProject.class, PROJECT_ID);
        assertEquals(2, revisions.size());
        assertEquals(1, revisions.get(0).intValue());
        assertTrue(revisions.get(1).intValue() > 1);
        assertEquals("Before startup", auditService.getEntityAtRevision(
                GsuifProject.class, PROJECT_ID, 1).getDescription());
        assertEquals("After startup", auditService.getEntityAtRevision(
                GsuifProject.class, PROJECT_ID, revisions.get(1)).getDescription());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM gsuif_project_aud WHERE id = ? AND rev = 1",
                Integer.class, PROJECT_ID.toString()));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.sequences WHERE lower(sequence_name) = 'revinfo_seq'",
                Integer.class));
    }
}
