package eg.mts.gsuif.audit;

import eg.mts.gsuif.entity.GsuifPage;
import eg.mts.gsuif.entity.GsuifProject;
import eg.mts.gsuif.entity.MetadataVersion;
import eg.mts.gsuif.repository.GsuifPageRepository;
import eg.mts.gsuif.repository.GsuifProjectRepository;
import eg.mts.gsuif.repository.MetadataVersionRepository;
import eg.mts.gsuif.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class JpaAuditingAndEnversIntegrationTest {

    @Autowired
    private GsuifProjectRepository projectRepository;

    @Autowired
    private GsuifPageRepository pageRepository;

    @Autowired
    private MetadataVersionRepository metadataVersionRepository;

    @Autowired
    private AuditService auditService;

    @BeforeEach
    void setUp() {
        metadataVersionRepository.deleteAll();
        pageRepository.deleteAll();
        projectRepository.deleteAll();
    }

    @Test
    @WithMockUser(username = "auditor_user")
    void jpaAuditing_populatesCreatedByAndLastModifiedByAutomatically() {
        GsuifProject project = new GsuifProject();
        project.setName("Audited Project");
        project.setDescription("Initial Description");

        // Save project without setting createdBy or lastModifiedBy manually
        GsuifProject savedProject = projectRepository.saveAndFlush(project);

        assertNotNull(savedProject.getCreatedAt());
        assertNotNull(savedProject.getUpdatedAt());
        assertEquals("auditor_user", savedProject.getCreatedBy());
        assertEquals("auditor_user", savedProject.getLastModifiedBy());
    }

    @Test
    @WithMockUser(username = "project_editor")
    void hibernateEnvers_createThenUpdateProject_verifiesTwoRevisionsExist() {
        // Revision 1: Create
        GsuifProject project = new GsuifProject();
        project.setName("Envers Project");
        project.setDescription("Revision 1 Description");
        project = projectRepository.saveAndFlush(project);

        // Revision 2: Update
        project.setDescription("Revision 2 Updated Description");
        project = projectRepository.saveAndFlush(project);

        // Verify revision records using AuditService (Envers AuditReader API)
        List<Number> revisions = auditService.getRevisions(GsuifProject.class, project.getId());

        assertEquals(2, revisions.size(), "Should have exactly two revision records for the project");

        GsuifProject rev1Project = auditService.getEntityAtRevision(GsuifProject.class, project.getId(), revisions.get(0));
        GsuifProject rev2Project = auditService.getEntityAtRevision(GsuifProject.class, project.getId(), revisions.get(1));

        assertEquals("Revision 1 Description", rev1Project.getDescription());
        assertEquals("Revision 2 Updated Description", rev2Project.getDescription());
        assertEquals("project_editor", rev1Project.getCreatedBy());
        assertEquals("project_editor", rev2Project.getLastModifiedBy());
    }

    @Test
    @WithMockUser(username = "metadata_author")
    void hibernateEnvers_metadataVersionRevisionHistoryIsQueryable() {
        // Setup parent project & page
        GsuifProject project = new GsuifProject();
        project.setName("Metadata Project");
        project.setDescription("Project Description");
        project = projectRepository.saveAndFlush(project);

        GsuifPage page = new GsuifPage();
        page.setProject(project);
        page.setName("Metadata Page");
        page.setRoute("/metadata-page");
        page = pageRepository.saveAndFlush(page);

        // Revision 1: Create MetadataVersion
        MetadataVersion version = new MetadataVersion();
        version.setPage(page);
        version.setVersion(1);
        version.setSchemaVersion("1.0.0");
        version.setCurrent(true);
        version.setSnapshot("{\"title\": \"Version 1\"}");
        version = metadataVersionRepository.saveAndFlush(version);

        // Revision 2: Update MetadataVersion snapshot
        version.setSnapshot("{\"title\": \"Version 1 Updated\"}");
        version = metadataVersionRepository.saveAndFlush(version);

        // Verify revision records via AuditService
        List<Number> versionRevisions = auditService.getRevisions(MetadataVersion.class, version.getId());

        assertEquals(2, versionRevisions.size(), "MetadataVersion should have two revision records");

        MetadataVersion rev1Version = auditService.getEntityAtRevision(MetadataVersion.class, version.getId(), versionRevisions.get(0));
        MetadataVersion rev2Version = auditService.getEntityAtRevision(MetadataVersion.class, version.getId(), versionRevisions.get(1));

        assertEquals("{\"title\": \"Version 1\"}", rev1Version.getSnapshot());
        assertEquals("{\"title\": \"Version 1 Updated\"}", rev2Version.getSnapshot());
        assertEquals("metadata_author", rev1Version.getCreatedBy());
        assertEquals("metadata_author", rev2Version.getLastModifiedBy());
    }
}
