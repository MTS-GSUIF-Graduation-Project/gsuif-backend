package eg.mts.gsuif.entity;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

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
}
