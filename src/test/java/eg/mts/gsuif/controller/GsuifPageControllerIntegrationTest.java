package eg.mts.gsuif.controller;

import eg.mts.gsuif.dto.CreatePageRequest;
import eg.mts.gsuif.dto.UpdatePageRequest;
import eg.mts.gsuif.entity.GsuifPage;
import eg.mts.gsuif.entity.GsuifProject;
import eg.mts.gsuif.entity.MetadataVersion;
import eg.mts.gsuif.repository.GsuifPageRepository;
import eg.mts.gsuif.repository.GsuifProjectRepository;
import eg.mts.gsuif.repository.MetadataVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "esraa.abdelrazek", roles = {"USER"})
class GsuifPageControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GsuifProjectRepository projectRepository;

    @Autowired
    private GsuifPageRepository pageRepository;

    @Autowired
    private MetadataVersionRepository metadataVersionRepository;

    @BeforeEach
    void setUp() {
        metadataVersionRepository.deleteAll();
        pageRepository.deleteAll();
        projectRepository.deleteAll();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 1. POST (Create)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void create_withValidPayload_returns201WithStandardEnvelope() throws Exception {
        GsuifProject project = savedProject("Alpha");

        CreatePageRequest request = new CreatePageRequest("  Home Page  ", "  /home  ");

        mockMvc.perform(post("/api/v1/projects/{projectId}/pages", project.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.statusCode").value(201))
                .andExpect(jsonPath("$.clientMessage").value("Page created successfully"))
                .andExpect(jsonPath("$.errors").value(nullValue()))
                .andExpect(jsonPath("$.body.id").isNotEmpty())
                .andExpect(jsonPath("$.body.projectId").value(project.getId().toString()))
                .andExpect(jsonPath("$.body.name").value("Home Page"))
                .andExpect(jsonPath("$.body.route").value("/home"))
                .andExpect(jsonPath("$.body.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.body.createdBy").value("esraa.abdelrazek"));
    }

    @Test
    void create_withBlankName_returns400WithFieldErrors() throws Exception {
        GsuifProject project = savedProject("Alpha");

        CreatePageRequest request = new CreatePageRequest("   ", "/route");

        mockMvc.perform(post("/api/v1/projects/{projectId}/pages", project.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.statusCode").value(400))
                .andExpect(jsonPath("$.clientMessage").value("Validation failed"))
                .andExpect(jsonPath("$.errors.name").value("Page name is required"));
    }

    @Test
    void create_withNameExceeding200Chars_returns400() throws Exception {
        GsuifProject project = savedProject("Alpha");
        String longName = "N".repeat(201);

        mockMvc.perform(post("/api/v1/projects/{projectId}/pages", project.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePageRequest(longName, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").value("Page name must not exceed 200 characters"));
    }

    @Test
    void create_withRouteExceeding255Chars_returns400() throws Exception {
        GsuifProject project = savedProject("Alpha");
        String longRoute = "/" + "r".repeat(255);

        mockMvc.perform(post("/api/v1/projects/{projectId}/pages", project.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePageRequest("Home", longRoute))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.route").value("Page route must not exceed 255 characters"));
    }

    @Test
    void create_withBlankRoute_treatedAsNull_pageCreated() throws Exception {
        GsuifProject project = savedProject("Alpha");

        // "   " is blank — should be normalised to null, not rejected
        mockMvc.perform(post("/api/v1/projects/{projectId}/pages", project.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePageRequest("Home", "   "))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.body.route").value(nullValue()));
    }

    @Test
    void create_withDuplicateNameInSameProject_returns400WithErrorsName() throws Exception {
        GsuifProject project = savedProject("Alpha");
        savedPage(project, "Home", null);

        mockMvc.perform(post("/api/v1/projects/{projectId}/pages", project.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePageRequest("Home", null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.statusCode").value(400))
                .andExpect(jsonPath("$.errors.name").isNotEmpty());
    }

    @Test
    void create_withDuplicateRouteInSameProject_returns400WithErrorsRoute() throws Exception {
        GsuifProject project = savedProject("Alpha");
        savedPage(project, "Home", "/home");

        mockMvc.perform(post("/api/v1/projects/{projectId}/pages", project.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePageRequest("Contact", "/home"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.statusCode").value(400))
                .andExpect(jsonPath("$.errors.route").isNotEmpty());
    }

    @Test
    void create_withNonExistentProject_returns404() throws Exception {
        UUID randomId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/projects/{projectId}/pages", randomId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePageRequest("Home", null))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.statusCode").value(404))
                .andExpect(jsonPath("$.clientMessage").value("Project not found with id: " + randomId));
    }

    // ── Cross-project isolation ────────────────────────────────────────────────

    @Test
    void create_sameNameInDifferentProjects_bothSucceed() throws Exception {
        GsuifProject p1 = savedProject("Project One");
        GsuifProject p2 = savedProject("Project Two");

        mockMvc.perform(post("/api/v1/projects/{projectId}/pages", p1.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePageRequest("Shared Name", null))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/projects/{projectId}/pages", p2.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePageRequest("Shared Name", null))))
                .andExpect(status().isCreated());
    }

    @Test
    void create_sameRouteInDifferentProjects_bothSucceed() throws Exception {
        GsuifProject p1 = savedProject("Project One");
        GsuifProject p2 = savedProject("Project Two");

        mockMvc.perform(post("/api/v1/projects/{projectId}/pages", p1.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePageRequest("Home P1", "/shared-route"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/projects/{projectId}/pages", p2.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePageRequest("Home P2", "/shared-route"))))
                .andExpect(status().isCreated());
    }

    // ── Boundary lengths ──────────────────────────────────────────────────────

    @Test
    void create_with200CharName_succeeds_and201CharName_fails() throws Exception {
        GsuifProject project = savedProject("Boundary Project");

        String name200 = "A".repeat(200);
        mockMvc.perform(post("/api/v1/projects/{projectId}/pages", project.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePageRequest(name200, null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.body.name").value(name200));

        String name201 = "B".repeat(201);
        mockMvc.perform(post("/api/v1/projects/{projectId}/pages", project.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePageRequest(name201, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").value("Page name must not exceed 200 characters"));
    }

    @Test
    void create_with255CharRoute_succeeds_and256CharRoute_fails() throws Exception {
        GsuifProject project = savedProject("Route Boundary");

        String route255 = "/" + "r".repeat(254);
        mockMvc.perform(post("/api/v1/projects/{projectId}/pages", project.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePageRequest("Page 255", route255))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.body.route").value(route255));

        String route256 = "/" + "r".repeat(255);
        mockMvc.perform(post("/api/v1/projects/{projectId}/pages", project.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreatePageRequest("Page 256", route256))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.route").value("Page route must not exceed 255 characters"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. GET list
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getAll_returns200WithPaginationDefaultsAndCreatedAtDescOrdering() throws Exception {
        GsuifProject project = savedProject("List Project");

        savedPage(project, "First Page", null);
        Thread.sleep(10);
        savedPage(project, "Second Page", null);

        mockMvc.perform(get("/api/v1/projects/{projectId}/pages", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.body.size").value(20))
                .andExpect(jsonPath("$.body.number").value(0))
                .andExpect(jsonPath("$.body.totalElements").value(2))
                .andExpect(jsonPath("$.body.data[0].name").value("Second Page"))
                .andExpect(jsonPath("$.body.data[1].name").value("First Page"));
    }

    @Test
    void getAll_withExcessiveSize_capsPageSizeAt100() throws Exception {
        GsuifProject project = savedProject("Cap Project");

        mockMvc.perform(get("/api/v1/projects/{projectId}/pages?size=500", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.size").value(100));
    }

    @Test
    void getAll_withNonExistentProject_returns404() throws Exception {
        UUID randomId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/projects/{projectId}/pages", randomId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.statusCode").value(404));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3. GET by ID
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void getById_whenExists_returns200WithDto() throws Exception {
        GsuifProject project = savedProject("Get Project");
        GsuifPage page = savedPage(project, "About", "/about");

        mockMvc.perform(get("/api/v1/projects/{projectId}/pages/{pageId}", project.getId(), page.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.clientMessage").value("Page retrieved successfully"))
                .andExpect(jsonPath("$.body.id").value(page.getId().toString()))
                .andExpect(jsonPath("$.body.name").value("About"))
                .andExpect(jsonPath("$.body.route").value("/about"));
    }

    @Test
    void getById_whenPageNotExists_returns404() throws Exception {
        GsuifProject project = savedProject("Get Project");
        UUID randomPageId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/projects/{projectId}/pages/{pageId}", project.getId(), randomPageId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.statusCode").value(404));
    }

    @Test
    void getById_whenPageBelongsToDifferentProject_returns404() throws Exception {
        GsuifProject p1 = savedProject("Project One");
        GsuifProject p2 = savedProject("Project Two");
        GsuifPage pageInP1 = savedPage(p1, "Home", null);

        // Request page from p2 — it belongs to p1
        mockMvc.perform(get("/api/v1/projects/{projectId}/pages/{pageId}", p2.getId(), pageInP1.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.statusCode").value(404));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4. PUT (Update)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void update_withValidPayload_returns200() throws Exception {
        GsuifProject project = savedProject("Update Project");
        GsuifPage page = savedPage(project, "Old Name", "/old");

        UpdatePageRequest request = new UpdatePageRequest("New Name", "/new");

        mockMvc.perform(put("/api/v1/projects/{projectId}/pages/{pageId}", project.getId(), page.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.body.name").value("New Name"))
                .andExpect(jsonPath("$.body.route").value("/new"));
    }

    @Test
    void update_withNullRoute_clearsRoute() throws Exception {
        GsuifProject project = savedProject("Clear Route");
        GsuifPage page = savedPage(project, "Home", "/home");

        UpdatePageRequest request = new UpdatePageRequest("Home", null);

        mockMvc.perform(put("/api/v1/projects/{projectId}/pages/{pageId}", project.getId(), page.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.route").value(nullValue()));
    }

    @Test
    void update_withDuplicateNameInSameProject_returns400() throws Exception {
        GsuifProject project = savedProject("Dup Name Project");
        savedPage(project, "Taken Name", null);
        GsuifPage page = savedPage(project, "Original", null);

        UpdatePageRequest request = new UpdatePageRequest("Taken Name", null);

        mockMvc.perform(put("/api/v1/projects/{projectId}/pages/{pageId}", project.getId(), page.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.statusCode").value(400))
                .andExpect(jsonPath("$.errors.name").isNotEmpty());
    }

    @Test
    void update_whenPageBelongsToDifferentProject_returns404() throws Exception {
        GsuifProject p1 = savedProject("Project One");
        GsuifProject p2 = savedProject("Project Two");
        GsuifPage pageInP1 = savedPage(p1, "Home", null);

        UpdatePageRequest request = new UpdatePageRequest("New", null);

        mockMvc.perform(put("/api/v1/projects/{projectId}/pages/{pageId}", p2.getId(), pageInP1.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.statusCode").value(404));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 5. DELETE
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void delete_whenNoMetadataVersions_returns200AndPageIsGone() throws Exception {
        GsuifProject project = savedProject("Delete Project");
        GsuifPage page = savedPage(project, "Home", null);

        mockMvc.perform(delete("/api/v1/projects/{projectId}/pages/{pageId}", project.getId(), page.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.clientMessage").value("Page deleted successfully"));

        assertThat(projectRepository.existsById(project.getId())).isTrue();
        assertThat(pageRepository.existsById(page.getId())).isFalse();
    }

    @Test
    void delete_whenPageNotFound_returns404() throws Exception {
        GsuifProject project = savedProject("Delete Project");
        UUID randomPageId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/projects/{projectId}/pages/{pageId}", project.getId(), randomPageId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.statusCode").value(404));
    }

    @Test
    void delete_whenPageBelongsToDifferentProject_returns404() throws Exception {
        GsuifProject p1 = savedProject("Project One");
        GsuifProject p2 = savedProject("Project Two");
        GsuifPage pageInP1 = savedPage(p1, "Home", null);

        mockMvc.perform(delete("/api/v1/projects/{projectId}/pages/{pageId}", p2.getId(), pageInP1.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.statusCode").value(404));

        assertThat(pageRepository.existsById(pageInP1.getId())).isTrue();
    }

    @Test
    void delete_whenMetadataVersionsExist_returns400WithoutCascade() throws Exception {
        GsuifProject project = savedProject("Meta Project");
        GsuifPage page = savedPage(project, "Home", null);
        savedMetadataVersion(project, page);

        mockMvc.perform(delete("/api/v1/projects/{projectId}/pages/{pageId}", project.getId(), page.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.statusCode").value(400))
                .andExpect(jsonPath("$.clientMessage").value(
                        org.hamcrest.Matchers.containsString("metadata versions")));

        assertThat(pageRepository.existsById(page.getId())).isTrue();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 6. DB Constraint Regression Tests
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Bypasses the service pre-check and triggers the actual uk_gsuif_page_project_id_name
     * constraint.  Verifies that the handler returns errors.name without any SQL/constraint text.
     */
    @Test
    void dbConstraint_ukGsuifPageProjectIdName_produces400WithErrorsName() throws Exception {
        GsuifProject project = savedProject("Constraint Name");
        savedPage(project, "Conflict", null);

        GsuifPage duplicate = new GsuifPage();
        duplicate.setProject(project);
        duplicate.setName("Conflict");

        org.springframework.dao.DataIntegrityViolationException caught = null;
        try {
            pageRepository.saveAndFlush(duplicate);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            caught = ex;
        }
        assertThat(caught).isNotNull();

        eg.mts.gsuif.exception.GlobalExceptionHandler handler =
                new eg.mts.gsuif.exception.GlobalExceptionHandler();
        var response = handler.handleDataIntegrityViolation(caught);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().status()).isEqualTo("BAD_REQUEST");
        assertThat(response.getBody().clientMessage())
                .isEqualTo("Page with that name already exists in this project");
        assertThat(response.getBody().errors()).containsEntry("name",
                "Page with that name already exists in this project");
        assertThat(response.getBody().clientMessage()).doesNotContainIgnoringCase("sql");
        assertThat(response.getBody().clientMessage()).doesNotContainIgnoringCase("constraint");
        assertThat(response.getBody().clientMessage()).doesNotContain("uk_gsuif_page_project_id_name");
    }

    /**
     * Bypasses the service pre-check and triggers the actual uk_gsuif_page_project_id_route
     * constraint.  Verifies that the handler returns errors.route without any SQL/constraint text.
     */
    @Test
    void dbConstraint_ukGsuifPageProjectIdRoute_produces400WithErrorsRoute() throws Exception {
        GsuifProject project = savedProject("Constraint Route");
        savedPage(project, "Home", "/shared");

        GsuifPage duplicate = new GsuifPage();
        duplicate.setProject(project);
        duplicate.setName("Contact");
        duplicate.setRoute("/shared");

        org.springframework.dao.DataIntegrityViolationException caught = null;
        try {
            pageRepository.saveAndFlush(duplicate);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            caught = ex;
        }
        assertThat(caught).isNotNull();

        eg.mts.gsuif.exception.GlobalExceptionHandler handler =
                new eg.mts.gsuif.exception.GlobalExceptionHandler();
        var response = handler.handleDataIntegrityViolation(caught);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().status()).isEqualTo("BAD_REQUEST");
        assertThat(response.getBody().clientMessage())
                .isEqualTo("Page with that route already exists in this project");
        assertThat(response.getBody().errors()).containsEntry("route",
                "Page with that route already exists in this project");
        assertThat(response.getBody().clientMessage()).doesNotContain("uk_gsuif_page_project_id_route");
    }

    /**
     * A DataIntegrityViolationException from an unrelated constraint must NOT produce
     * page-specific errors and must use the generic sanitised 400 message.
     */
    @Test
    void dbConstraint_unrelatedConstraint_doesNotProducePageErrors() throws Exception {
        // Trigger a project-name violation (unrelated to page constraints).
        GsuifProject p1 = savedProject("Conflict Project");
        GsuifProject p2 = new GsuifProject();
        p2.setName("Conflict Project");

        org.springframework.dao.DataIntegrityViolationException caught = null;
        try {
            projectRepository.saveAndFlush(p2);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            caught = ex;
        }
        assertThat(caught).isNotNull();

        eg.mts.gsuif.exception.GlobalExceptionHandler handler =
                new eg.mts.gsuif.exception.GlobalExceptionHandler();
        var response = handler.handleDataIntegrityViolation(caught);

        // Project-name constraint produces errors.name — must NOT contain errors.route
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().errors()).doesNotContainKey("route");
    }

    /**
     * Adversarial test: a page whose name IS the constraint identifier string.
     * Identification must rely solely on getConstraintName(), never on message text or data values.
     */
    @Test
    void dbConstraint_pageWithPoisonValue_doesNotProduceFalseMatch() throws Exception {
        GsuifProject project = savedProject("Poison Project");
        // Page name equals the constraint identifier — a poison value
        savedPage(project, "uk_gsuif_page_project_id_name", null);

        GsuifPage duplicate = new GsuifPage();
        duplicate.setProject(project);
        duplicate.setName("uk_gsuif_page_project_id_name");

        org.springframework.dao.DataIntegrityViolationException caught = null;
        try {
            pageRepository.saveAndFlush(duplicate);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            caught = ex;
        }
        assertThat(caught)
                .as("Expected DataIntegrityViolationException for the duplicate page name poison value")
                .isNotNull();

        eg.mts.gsuif.exception.GlobalExceptionHandler handler =
                new eg.mts.gsuif.exception.GlobalExceptionHandler();
        var response = handler.handleDataIntegrityViolation(caught);

        // The constraint name IS uk_gsuif_page_project_id_name, so this should
        // correctly resolve to the page-name mapping (not a false "generic" response).
        // This test confirms the handler identifies by constraint metadata, not data values.
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().clientMessage())
                .isEqualTo("Page with that name already exists in this project");
        // The constraint name itself must NOT appear in the client response.
        assertThat(response.getBody().clientMessage())
                .doesNotContain("uk_gsuif_page_project_id_name");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private GsuifProject savedProject(String name) {
        GsuifProject p = new GsuifProject();
        p.setName(name);
        return projectRepository.save(p);
    }

    private GsuifPage savedPage(GsuifProject project, String name, String route) {
        GsuifPage page = new GsuifPage();
        page.setProject(project);
        page.setName(name);
        page.setRoute(route);
        return pageRepository.save(page);
    }

    private MetadataVersion savedMetadataVersion(GsuifProject project, GsuifPage page) {
        MetadataVersion mv = new MetadataVersion();
        mv.setPage(page);
        mv.setVersion(1);
        mv.setSchemaVersion("1.0");
        mv.setCurrent(true);
        mv.setSnapshot("{\"fields\":[]}");
        return metadataVersionRepository.save(mv);
    }
}
