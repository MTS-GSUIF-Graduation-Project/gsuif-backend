package eg.mts.gsuif.audit;

import eg.mts.gsuif.entity.GsuifPage;
import eg.mts.gsuif.entity.GsuifProject;
// import eg.mts.gsuif.entity.MetadataVersion;
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
// import org.springframework.transaction.annotation.Transactional;

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

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("UPDATE gsuif_page SET current_metadata_version_id = NULL");
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
    @WithMockUser(username = "page_author")
    void hibernateEnvers_pageRevisionHistoryIsQueryable() {
        // Setup parent project
        GsuifProject project = new GsuifProject();
        project.setName("Page Project");
        project.setDescription("Project Description");
        project = projectRepository.saveAndFlush(project);

        // Revision 1: Create Page
        GsuifPage page = new GsuifPage();
        page.setProject(project);
        page.setName("Metadata Page");
        page.setRoute("/metadata-page");
        page = pageRepository.saveAndFlush(page);

        // Revision 2: Update Page
        page.setName("Metadata Page Updated");
        page = pageRepository.saveAndFlush(page);

        // Verify revision records via AuditService
        List<Number> pageRevisions = auditService.getRevisions(GsuifPage.class, page.getId());

        assertEquals(2, pageRevisions.size(), "GsuifPage should have two revision records");

        GsuifPage rev1Page = auditService.getEntityAtRevision(GsuifPage.class, page.getId(), pageRevisions.get(0));
        GsuifPage rev2Page = auditService.getEntityAtRevision(GsuifPage.class, page.getId(), pageRevisions.get(1));

        assertEquals("Metadata Page", rev1Page.getName());
        assertEquals("Metadata Page Updated", rev2Page.getName());
        assertEquals("page_author", rev1Page.getCreatedBy());
        assertEquals("page_author", rev2Page.getLastModifiedBy());
    }
}
