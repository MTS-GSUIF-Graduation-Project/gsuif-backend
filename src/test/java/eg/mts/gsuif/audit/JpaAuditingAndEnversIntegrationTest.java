package eg.mts.gsuif.audit;

import eg.mts.gsuif.dto.CreateMetadataVersionRequest;
import eg.mts.gsuif.dto.MetadataVersionDto;
import eg.mts.gsuif.entity.GsuifPage;
import eg.mts.gsuif.entity.GsuifProject;
import eg.mts.gsuif.entity.MetadataVersion;
import eg.mts.gsuif.repository.GsuifPageRepository;
import eg.mts.gsuif.repository.GsuifProjectRepository;
import eg.mts.gsuif.repository.MetadataVersionRepository;
import eg.mts.gsuif.service.AuditService;
import eg.mts.gsuif.service.MetadataVersionService;
import jakarta.persistence.EntityManager;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.RevisionType;
import org.hibernate.envers.query.AuditEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.List;
import java.util.UUID;

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
    private MetadataVersionService metadataVersionService;

    @Autowired
    private AuditService auditService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

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

    @Test
    @WithMockUser(username = "version_author")
    void hibernateEnvers_creatingTwoMetadataVersions_recordsAddRevisionsAndPageCurrentPointerHistory() {
        GsuifProject project = new GsuifProject();
        project.setName("Versioned Metadata Project");
        project.setDescription("Project for metadata version Envers coverage");
        project = projectRepository.saveAndFlush(project);

        GsuifPage page = new GsuifPage();
        page.setProject(project);
        page.setName("Versioned Metadata Page");
        page.setRoute("/versioned-metadata");
        page = pageRepository.saveAndFlush(page);
        UUID pageId = page.getId();

        CreateMetadataVersionRequest firstRequest = new CreateMetadataVersionRequest(
                "1.0",
                JsonNodeFactory.instance.objectNode().put("label", "first")
        );
        CreateMetadataVersionRequest secondRequest = new CreateMetadataVersionRequest(
                "1.0",
                JsonNodeFactory.instance.objectNode().put("label", "second")
        );

        MetadataVersionDto version1 = metadataVersionService.create(pageId, firstRequest);
        String version1Snapshot = metadataVersionRepository.findById(version1.id()).orElseThrow().getSnapshot();

        MetadataVersionDto version2 = metadataVersionService.create(pageId, secondRequest);

        List<Number> version1Revisions = auditService.getRevisions(MetadataVersion.class, version1.id());
        assertEquals(1, version1Revisions.size(), "First MetadataVersion must have exactly one Envers revision");
        assertEquals(
                RevisionType.ADD,
                revisionType(MetadataVersion.class, version1.id(), version1Revisions.getFirst()),
                "Creating a MetadataVersion must produce an Envers ADD revision"
        );

        List<Number> version2Revisions = auditService.getRevisions(MetadataVersion.class, version2.id());
        assertEquals(1, version2Revisions.size(), "Second MetadataVersion must have exactly one Envers revision");
        assertEquals(
                RevisionType.ADD,
                revisionType(MetadataVersion.class, version2.id(), version2Revisions.getFirst()),
                "Creating a later MetadataVersion must also produce an Envers ADD revision"
        );

        MetadataVersion unchangedVersion1 = metadataVersionRepository.findById(version1.id()).orElseThrow();
        assertEquals(1, unchangedVersion1.getVersion());
        assertEquals(version1Snapshot, unchangedVersion1.getSnapshot());
        assertEquals(
                1,
                auditService.getRevisions(MetadataVersion.class, version1.id()).size(),
                "Creating a later version must not modify the previous version's audit history"
        );

        GsuifPage updatedPage = pageRepository.findById(pageId).orElseThrow();
        assertEquals(version2.id(), updatedPage.getCurrentMetadataVersionId());

        List<Number> pageRevisions = auditService.getRevisions(GsuifPage.class, pageId);
        assertTrue(pageRevisions.size() >= 2, "Page current-version pointer changes must appear in Page revision history");

        boolean sawVersion1AsCurrent = false;
        boolean sawVersion2AsCurrent = false;
        for (Number revision : pageRevisions) {
            GsuifPage pageAtRevision = auditService.getEntityAtRevision(GsuifPage.class, pageId, revision);
            UUID currentId = pageAtRevision.getCurrentMetadataVersionId();
            if (version1.id().equals(currentId)) {
                sawVersion1AsCurrent = true;
            }
            if (version2.id().equals(currentId)) {
                sawVersion2AsCurrent = true;
            }
        }

        assertTrue(sawVersion1AsCurrent, "Page revision history must record current-version pointer to the first version");
        assertTrue(sawVersion2AsCurrent, "Page revision history must record current-version pointer to the later version");

        GsuifPage pageAtLatestRevision = auditService.getEntityAtRevision(
                GsuifPage.class,
                pageId,
                pageRevisions.getLast()
        );
        assertEquals(version2.id(), pageAtLatestRevision.getCurrentMetadataVersionId());
    }

    @SuppressWarnings("unchecked")
    private RevisionType revisionType(Class<?> entityClass, Object id, Number revision) {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        return txTemplate.execute(status -> {
            List<Object[]> rows = AuditReaderFactory.get(entityManager)
                    .createQuery()
                    .forRevisionsOfEntity(entityClass, false, true)
                    .add(AuditEntity.id().eq(id))
                    .add(AuditEntity.revisionNumber().eq(revision))
                    .getResultList();
            assertFalse(rows.isEmpty(), "Expected an Envers revision row for " + entityClass.getSimpleName());
            return (RevisionType) rows.getFirst()[2];
        });
    }
}
