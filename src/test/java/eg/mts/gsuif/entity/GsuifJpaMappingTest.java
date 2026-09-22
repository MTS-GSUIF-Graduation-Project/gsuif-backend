package eg.mts.gsuif.entity;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies T-15 JPA mappings against the T-14 schema using H2 PostgreSQL mode.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GsuifJpaMappingTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void persistsApprovedDomainGraphIncludingNullableRouteAndJoinAudit() {
        GsuifProject project = new GsuifProject();
        project.setName("Mapping Project");
        project.setDescription("T-15 mapping check");
        entityManager.persist(project);

        GsuifPage pageWithRoute = new GsuifPage();
        pageWithRoute.setProject(project);
        pageWithRoute.setName("Login");
        pageWithRoute.setRoute("/login");
        entityManager.persist(pageWithRoute);

        GsuifPage pageWithoutRoute = new GsuifPage();
        pageWithoutRoute.setProject(project);
        pageWithoutRoute.setName("Draft");
        pageWithoutRoute.setRoute(null);
        entityManager.persist(pageWithoutRoute);

        MetadataVersion version = new MetadataVersion();
        version.setPage(pageWithRoute);
        version.setVersion(1);
        version.setSchemaVersion("1.0.0");
        version.setCurrent(true);
        version.setSnapshot("{\"components\":[],\"apiBindings\":[]}");
        entityManager.persist(version);

        GsuifUser user = new GsuifUser();
        user.setUsername("sara");
        user.setPasswordHash("hash");
        entityManager.persist(user);

        GsuifRole role = new GsuifRole();
        role.setName("ADMIN");
        entityManager.persist(role);

        GsuifUserRole userRole = new GsuifUserRole();
        userRole.setId(new GsuifUserRoleId(user.getId(), role.getId()));
        userRole.setUser(user);
        userRole.setRole(role);
        entityManager.persist(userRole);

        GenerationRun run = new GenerationRun();
        run.setMetadataVersion(version);
        run.setTemplateVersion("1.0.0");
        run.setGeneratorVersion("1.0.0");
        run.setToolName("TemplateOnlyProvider");
        run.setToolVersion("1.0.0");
        run.setStatus(GenerationRunStatus.SUCCESS);
        run.setTriggeringUser(user);
        entityManager.persist(run);

        GeneratedArtifact artifact = new GeneratedArtifact();
        artifact.setGenerationRun(run);
        artifact.setArtifactName("WorkOrderController.java");
        artifact.setArtifactType("java-source");
        artifact.setComponentId(null);
        entityManager.persist(artifact);

        entityManager.flush();
        entityManager.clear();

        GsuifProject reloadedProject = entityManager.find(GsuifProject.class, project.getId());
        assertThat(reloadedProject.getName()).isEqualTo("Mapping Project");
        assertThat(reloadedProject.getCreatedAt()).isNotNull();
        assertThat(reloadedProject.getCreatedBy()).isNotBlank();

        GsuifPage reloadedDraft = entityManager.find(GsuifPage.class, pageWithoutRoute.getId());
        assertThat(reloadedDraft.getRoute()).isNull();
        assertThat(reloadedDraft.getProject().getId()).isEqualTo(project.getId());

        MetadataVersion reloadedVersion = entityManager.find(MetadataVersion.class, version.getId());
        assertThat(reloadedVersion.getPage().getId()).isEqualTo(pageWithRoute.getId());
        assertThat(reloadedVersion.getPage().getProject().getId()).isEqualTo(project.getId());
        assertThat(reloadedVersion.getSnapshot()).contains("apiBindings");

        GsuifUserRoleId joinId = new GsuifUserRoleId(user.getId(), role.getId());
        GsuifUserRole reloadedJoin = entityManager.find(GsuifUserRole.class, joinId);
        assertThat(reloadedJoin.getCreatedAt()).isNotNull();
        assertThat(reloadedJoin.getCreatedBy()).isNotBlank();

        GeneratedArtifact reloadedArtifact = entityManager.find(GeneratedArtifact.class, artifact.getId());
        assertThat(reloadedArtifact.getComponentId()).isNull();
        assertThat(reloadedArtifact.getGenerationRun().getStatus()).isEqualTo(GenerationRunStatus.SUCCESS);
    }

    @Test
    void rejectsDuplicateProjectName() {
        GsuifProject first = new GsuifProject();
        first.setName("Unique Name");
        entityManager.persist(first);
        entityManager.flush();

        GsuifProject second = new GsuifProject();
        second.setName("Unique Name");
        entityManager.persist(second);

        assertThatThrownBy(() -> entityManager.flush())
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void persistsDescriptionAndSnapshotLongerThanDefaultVarcharLength() {
        String longText = "x".repeat(1_000);

        GsuifProject project = new GsuifProject();
        project.setName("Long Text Project");
        project.setDescription(longText);
        entityManager.persist(project);

        GsuifPage page = new GsuifPage();
        page.setProject(project);
        page.setName("Long Text Page");
        entityManager.persist(page);

        MetadataVersion version = new MetadataVersion();
        version.setPage(page);
        version.setVersion(1);
        version.setSchemaVersion("1.0.0");
        version.setCurrent(true);
        version.setSnapshot(longText);
        entityManager.persist(version);

        entityManager.flush();
        entityManager.clear();

        GsuifProject savedProject = entityManager.find(GsuifProject.class, project.getId());
        MetadataVersion savedVersion = entityManager.find(MetadataVersion.class, version.getId());

        assertThat(savedProject.getDescription()).isEqualTo(longText);
        assertThat(savedVersion.getSnapshot()).isEqualTo(longText);
    }

    @Test
    void rejectsDuplicatePageNameWithinSameProject() {
        GsuifProject project = persistProject("Duplicate Page Name Project");
        persistPage(project, "Dashboard", "/dashboard");
        entityManager.flush();

        persistPage(project, "Dashboard", "/dashboard-v2");

        assertThatThrownBy(entityManager::flush)
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void allowsSamePageNameInDifferentProjects() {
        GsuifProject firstProject = persistProject("First Page Name Project");
        GsuifProject secondProject = persistProject("Second Page Name Project");

        persistPage(firstProject, "Dashboard", "/first-dashboard");
        persistPage(secondProject, "Dashboard", "/second-dashboard");

        entityManager.flush();
    }

    @Test
    void rejectsDuplicateNonNullRouteWithinSameProject() {
        GsuifProject project = persistProject("Duplicate Route Project");
        persistPage(project, "First Dashboard", "/dashboard");
        entityManager.flush();

        persistPage(project, "Second Dashboard", "/dashboard");

        assertThatThrownBy(entityManager::flush)
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void allowsMultipleNullRoutesWithinSameProject() {
        GsuifProject project = persistProject("Null Route Project");
        persistPage(project, "First Draft", null);
        persistPage(project, "Second Draft", null);

        entityManager.flush();
    }

    @Test
    void rejectsDuplicateVersionWithinSamePage() {
        GsuifProject project = persistProject("Duplicate Version Project");
        GsuifPage page = persistPage(project, "Versioned Page", "/versioned");
        persistMetadataVersion(page, 1);
        entityManager.flush();

        persistMetadataVersion(page, 1);

        assertThatThrownBy(entityManager::flush)
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void rejectsMetadataVersionBelowOne() {
        GsuifProject project = persistProject("Invalid Version Project");
        GsuifPage page = persistPage(project, "Invalid Version Page", "/invalid-version");
        persistMetadataVersion(page, 0);

        assertThatThrownBy(entityManager::flush)
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void rejectsMetadataVersionWithInconsistentProjectAndPage() {
        GsuifProject firstProject = persistProject("Composite FK First Project");
        GsuifProject secondProject = persistProject("Composite FK Second Project");
        GsuifPage secondProjectPage = persistPage(secondProject, "Owned Page", "/owned");
        entityManager.flush();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO gsuif_metadata_version (
                            id, project_id, page_id, version, schema_version, is_current, snapshot,
                            created_at, updated_at, created_by, last_modified_by
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                        """,
                UUID.randomUUID().toString(),
                firstProject.getId().toString(),
                secondProjectPage.getId().toString(),
                1,
                "1.0.0",
                false,
                "{}",
                "constraint-test",
                "constraint-test"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateUserRolePair() {
        GsuifUser user = new GsuifUser();
        user.setUsername("duplicate-role-user");
        user.setPasswordHash("hash");
        entityManager.persist(user);

        GsuifRole role = new GsuifRole();
        role.setName("DUPLICATE_ROLE");
        entityManager.persist(role);

        GsuifUserRole assignment = new GsuifUserRole();
        assignment.setId(new GsuifUserRoleId(user.getId(), role.getId()));
        assignment.setUser(user);
        assignment.setRole(role);
        entityManager.persist(assignment);
        entityManager.flush();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO gsuif_user_role (
                            user_id, role_id, created_at, updated_at, created_by, last_modified_by
                        ) VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                        """,
                user.getId().toString(),
                role.getId().toString(),
                "constraint-test",
                "constraint-test"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private GsuifProject persistProject(String name) {
        GsuifProject project = new GsuifProject();
        project.setName(name);
        entityManager.persist(project);
        return project;
    }

    private GsuifPage persistPage(GsuifProject project, String name, String route) {
        GsuifPage page = new GsuifPage();
        page.setProject(project);
        page.setName(name);
        page.setRoute(route);
        entityManager.persist(page);
        return page;
    }

    private MetadataVersion persistMetadataVersion(GsuifPage page, int versionNumber) {
        MetadataVersion version = new MetadataVersion();
        version.setPage(page);
        version.setVersion(versionNumber);
        version.setSchemaVersion("1.0.0");
        version.setCurrent(false);
        version.setSnapshot("{}");
        entityManager.persist(version);
        return version;
    }
}
