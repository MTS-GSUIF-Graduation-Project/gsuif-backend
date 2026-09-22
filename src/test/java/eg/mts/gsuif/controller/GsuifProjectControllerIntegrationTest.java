package eg.mts.gsuif.controller;

import eg.mts.gsuif.dto.CreateProjectRequest;
import eg.mts.gsuif.dto.UpdateProjectRequest;
import eg.mts.gsuif.entity.GsuifPage;
import eg.mts.gsuif.entity.GsuifProject;
import eg.mts.gsuif.entity.WorkOrder;
import eg.mts.gsuif.entity.WorkOrderStatus;
import eg.mts.gsuif.repository.GsuifPageRepository;
import eg.mts.gsuif.repository.GsuifProjectRepository;
import eg.mts.gsuif.repository.WorkOrderRepository;
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

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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
class GsuifProjectControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GsuifProjectRepository projectRepository;

    @Autowired
    private GsuifPageRepository pageRepository;

    @Autowired
    private WorkOrderRepository workOrderRepository;

    @BeforeEach
    void setUp() {
        pageRepository.deleteAll();
        projectRepository.deleteAll();
        workOrderRepository.deleteAll();
    }

    // ── 1. Create (POST) ──────────────────────────────────────────────────────

    @Test
    void create_withValidPayload_returns201WithStandardEnvelope() throws Exception {
        CreateProjectRequest request = new CreateProjectRequest("  Project Alpha  ", "  Primary backend project  ");

        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.statusCode").value(201))
                .andExpect(jsonPath("$.clientMessage").value("Project created successfully"))
                .andExpect(jsonPath("$.errors").value(nullValue()))
                .andExpect(jsonPath("$.body.id").isNotEmpty())
                .andExpect(jsonPath("$.body.name").value("Project Alpha"))
                .andExpect(jsonPath("$.body.description").value("Primary backend project"))
                .andExpect(jsonPath("$.body.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.body.createdBy").value("esraa.abdelrazek"));

        assertThat(projectRepository.existsByName("Project Alpha")).isTrue();
    }

    @Test
    void create_withBlankName_returns400WithFieldErrors() throws Exception {
        CreateProjectRequest request = new CreateProjectRequest("   ", "Desc");

        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.statusCode").value(400))
                .andExpect(jsonPath("$.clientMessage").value("Validation failed"))
                .andExpect(jsonPath("$.body").value(nullValue()))
                .andExpect(jsonPath("$.errors.name").value("Project name is required"));
    }

    @Test
    void create_withDuplicateName_returns400WithErrorsNamePopulated() throws Exception {
        GsuifProject existing = new GsuifProject();
        existing.setName("Duplicate Project");
        projectRepository.save(existing);

        CreateProjectRequest request = new CreateProjectRequest("Duplicate Project", "Another Desc");

        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.statusCode").value(400))
                .andExpect(jsonPath("$.clientMessage").value("Project with name 'Duplicate Project' already exists"))
                .andExpect(jsonPath("$.body").value(nullValue()))
                .andExpect(jsonPath("$.errors.name").value("Project with name 'Duplicate Project' already exists"));
    }

    @Test
    void create_with200CharName_succeeds_and201CharName_failsValidation() throws Exception {
        String name200 = "A".repeat(200);
        CreateProjectRequest validRequest = new CreateProjectRequest(name200, "Desc");

        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.body.name").value(name200));

        String name201 = "B".repeat(201);
        CreateProjectRequest invalidRequest = new CreateProjectRequest(name201, "Desc");

        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").value("Project name must not exceed 200 characters"));
    }

    @Test
    void create_caseSensitiveUniqueness_allowsSameNameWithDifferentCase() throws Exception {
        GsuifProject existing = new GsuifProject();
        existing.setName("Project Alpha");
        projectRepository.save(existing);

        CreateProjectRequest request = new CreateProjectRequest("project alpha", "Different case");

        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.body.name").value("project alpha"));
    }

    // ── 2. Get By ID (GET) ────────────────────────────────────────────────────

    @Test
    void getById_whenExists_returns200WithDto() throws Exception {
        GsuifProject saved = new GsuifProject();
        saved.setName("Project Beta");
        saved.setDescription("Secondary project");
        saved = projectRepository.save(saved);

        mockMvc.perform(get("/api/v1/projects/{id}", saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.clientMessage").value("Project retrieved successfully"))
                .andExpect(jsonPath("$.body.id").value(saved.getId().toString()))
                .andExpect(jsonPath("$.body.name").value("Project Beta"));
    }

    @Test
    void getById_whenNotExists_returns404StandardEnvelope() throws Exception {
        UUID randomId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/projects/{id}", randomId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.status").value("NOT_FOUND"))
                .andExpect(jsonPath("$.statusCode").value(404))
                .andExpect(jsonPath("$.clientMessage").value("Project not found with id: " + randomId))
                .andExpect(jsonPath("$.body").value(nullValue()));
    }

    // ── 3. List All (GET) & Pagination ────────────────────────────────────────

    @Test
    void getAll_returns200WithPaginationDefaultsAndCreatedAtDescOrdering() throws Exception {
        GsuifProject p1 = new GsuifProject();
        p1.setName("First Project");
        projectRepository.save(p1);

        Thread.sleep(10);

        GsuifProject p2 = new GsuifProject();
        p2.setName("Second Project");
        projectRepository.save(p2);

        mockMvc.perform(get("/api/v1/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.body.size").value(20))
                .andExpect(jsonPath("$.body.number").value(0))
                .andExpect(jsonPath("$.body.totalElements").value(2))
                .andExpect(jsonPath("$.body.data[0].name").value("Second Project"))
                .andExpect(jsonPath("$.body.data[1].name").value("First Project"));
    }

    @Test
    void getAll_withExcessiveSize_capsPageSizeAt100() throws Exception {
        mockMvc.perform(get("/api/v1/projects?size=500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.size").value(100));
    }

    // ── 4. Update (PUT) ───────────────────────────────────────────────────────

    @Test
    void update_withValidPayload_returns200() throws Exception {
        GsuifProject existing = new GsuifProject();
        existing.setName("Original Name");
        existing = projectRepository.save(existing);

        UpdateProjectRequest request = new UpdateProjectRequest("Updated Name", "Updated Desc");

        mockMvc.perform(put("/api/v1/projects/{id}", existing.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.body.name").value("Updated Name"))
                .andExpect(jsonPath("$.body.description").value("Updated Desc"));
    }

    @Test
    void update_withDuplicateName_returns400WithErrorsNamePopulated() throws Exception {
        GsuifProject p1 = new GsuifProject();
        p1.setName("Project One");
        projectRepository.save(p1);

        GsuifProject p2 = new GsuifProject();
        p2.setName("Project Two");
        p2 = projectRepository.save(p2);

        UpdateProjectRequest request = new UpdateProjectRequest("Project One", "Desc");

        mockMvc.perform(put("/api/v1/projects/{id}", p2.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.statusCode").value(400))
                .andExpect(jsonPath("$.clientMessage").value("Project with name 'Project One' already exists"))
                .andExpect(jsonPath("$.errors.name").value("Project with name 'Project One' already exists"));
    }

    @Test
    void update_whenNotFound_returns404() throws Exception {
        UUID randomId = UUID.randomUUID();
        UpdateProjectRequest request = new UpdateProjectRequest("Valid Name", "Desc");

        mockMvc.perform(put("/api/v1/projects/{id}", randomId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.statusCode").value(404));
    }

    // ── 5. Delete (DELETE) ────────────────────────────────────────────────────

    @Test
    void delete_whenNoPages_returns200() throws Exception {
        GsuifProject project = new GsuifProject();
        project.setName("Deletable Project");
        project = projectRepository.save(project);

        mockMvc.perform(delete("/api/v1/projects/{id}", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.clientMessage").value("Project deleted successfully"));

        assertThat(projectRepository.existsById(project.getId())).isFalse();
    }

    @Test
    void delete_whenNotFound_returns404() throws Exception {
        UUID randomId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/projects/{id}", randomId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.statusCode").value(404))
                .andExpect(jsonPath("$.clientMessage").value("Project not found with id: " + randomId));
    }

    @Test
    void delete_whenPagesExist_returns400WithoutCascadeDeletion() throws Exception {
        GsuifProject project = new GsuifProject();
        project.setName("Project With Page");
        project = projectRepository.save(project);

        GsuifPage page = new GsuifPage();
        page.setProject(project);
        page.setName("Home Page");
        pageRepository.save(page);

        mockMvc.perform(delete("/api/v1/projects/{id}", project.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.statusCode").value(400))
                .andExpect(jsonPath("$.clientMessage").value("Cannot delete project with id '" + project.getId() + "' because it contains existing pages"));

        assertThat(projectRepository.existsById(project.getId())).isTrue();
        assertThat(pageRepository.existsById(page.getId())).isTrue();
    }

    // ── 6. DB Constraint Routing Regression Tests ─────────────────────────────

    /**
     * uk_gsuif_project_name race-condition: bypasses the application pre-check and hits the DB
     * unique constraint directly, then verifies the response carries errors.name without exposing
     * SQL or constraint details.
     */
    @Test
    void dbConstraint_ukGsuifProjectName_produces400WithErrorsName() throws Exception {
        // Seed a project directly so the repository uniqueness check doesn't fire.
        GsuifProject existing = new GsuifProject();
        existing.setName("Conflict Name");
        projectRepository.saveAndFlush(existing);

        // A second entity with the same name — directly persist to bypass the service guard.
        GsuifProject duplicate = new GsuifProject();
        duplicate.setName("Conflict Name");

        org.springframework.dao.DataIntegrityViolationException caught = null;
        try {
            projectRepository.saveAndFlush(duplicate);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            caught = ex;
        }
        assertThat(caught).isNotNull();

        eg.mts.gsuif.exception.GlobalExceptionHandler handler = new eg.mts.gsuif.exception.GlobalExceptionHandler();
        org.springframework.http.ResponseEntity<eg.mts.gsuif.dto.ApiResponse<Void>> response =
                handler.handleDataIntegrityViolation(caught);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().status()).isEqualTo("BAD_REQUEST");
        assertThat(response.getBody().clientMessage()).isEqualTo("Project with name already exists");
        assertThat(response.getBody().errors()).containsEntry("name", "Project with name already exists");
        // SQL details must not be exposed
        assertThat(response.getBody().clientMessage()).doesNotContainIgnoringCase("sql");
        assertThat(response.getBody().clientMessage()).doesNotContainIgnoringCase("constraint");
        assertThat(response.getBody().clientMessage()).doesNotContain("uk_gsuif_project_name");
    }

    /**
     * An application-level duplicate detected before flush routes through DuplicateResourceException
     * and produces errors.name with the full message via MVC routing.
     */
    @Test
    void post_applicationLevelDuplicate_routesViaExceptionHandlerWithErrorsName() throws Exception {
        GsuifProject existing = new GsuifProject();
        existing.setName("App Level Dup");
        projectRepository.save(existing);

        CreateProjectRequest request = new CreateProjectRequest("App Level Dup", "Desc");

        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.statusCode").value(400))
                .andExpect(jsonPath("$.errors.name").value("Project with name 'App Level Dup' already exists"))
                // Internal identifiers must not appear in the response
                .andExpect(jsonPath("$.clientMessage").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("uk_gsuif_project_name"))));
    }

    /**
     * A DataIntegrityViolationException from an unrelated WorkOrder uniqueness constraint
     * (uk_work_order_order_number) must NOT produce errors.name and must NOT mention
     * project-name details even if the surrounding message coincidentally contains "project".
     */
    @Test
    void dbConstraint_unrelatedWorkOrder_doesNotProduceErrorsName() throws Exception {
        WorkOrder wo1 = new WorkOrder("WO-UNIQUE-001", WorkOrderStatus.OPEN, LocalDate.now(), "user");
        workOrderRepository.saveAndFlush(wo1);

        WorkOrder wo2 = new WorkOrder("WO-UNIQUE-001", WorkOrderStatus.OPEN, LocalDate.now(), "user");

        org.springframework.dao.DataIntegrityViolationException caught = null;
        try {
            workOrderRepository.saveAndFlush(wo2);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            caught = ex;
        }
        assertThat(caught).isNotNull();

        eg.mts.gsuif.exception.GlobalExceptionHandler handler = new eg.mts.gsuif.exception.GlobalExceptionHandler();
        org.springframework.http.ResponseEntity<eg.mts.gsuif.dto.ApiResponse<Void>> response =
                handler.handleDataIntegrityViolation(caught);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        // Must use generic message — NOT the project-name path
        assertThat(response.getBody().clientMessage()).isEqualTo("Resource already exists or violates unique constraint");
        assertThat(response.getBody().errors()).isNull();
    }

    /**
     * Sanitized 500: unexpected runtime failures must reach the catch-all and hide internal details.
     */
    @Test
    void unexpectedRuntimeFailure_reachesSanitized500Handler() {
        eg.mts.gsuif.exception.GlobalExceptionHandler handler = new eg.mts.gsuif.exception.GlobalExceptionHandler();
        RuntimeException unexpected = new RuntimeException("Simulated unexpected NullPointer or Internal error");

        org.springframework.http.ResponseEntity<eg.mts.gsuif.dto.ApiResponse<Void>> response =
                handler.handleGeneral(unexpected);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().status()).isEqualTo("INTERNAL_SERVER_ERROR");
        assertThat(response.getBody().clientMessage()).isEqualTo("An unexpected error occurred");
        assertThat(response.getBody().errors()).isNull();
        assertThat(response.getBody().clientMessage()).doesNotContain("Simulated unexpected");
    }

    /**
     * Poison-value regression: a WorkOrder whose orderNumber is literally "uk_gsuif_project_name"
     * produces a unique-constraint violation whose SQL error message contains the constraint-name
     * token as a data value.  This must NOT be misclassified as a project-name constraint and must
     * NOT produce errors.name.
     *
     * <p>This verifies that constraint identification relies solely on structured Hibernate metadata
     * (ConstraintViolationException#getConstraintName) and never on free-form message text.
     */
    @Test
    void dbConstraint_workOrderWithPoisonValue_doesNotProduceProjectErrorsName() throws Exception {
        // Insert via raw SQL to avoid the service-level duplicate check, then attempt a duplicate
        // insert through the repository so we get a real DataIntegrityViolationException.
        WorkOrder wo1 = new WorkOrder("uk_gsuif_project_name", WorkOrderStatus.OPEN, LocalDate.now(), "user");
        workOrderRepository.saveAndFlush(wo1);

        WorkOrder wo2 = new WorkOrder("uk_gsuif_project_name", WorkOrderStatus.OPEN, LocalDate.now(), "user");

        org.springframework.dao.DataIntegrityViolationException caught = null;
        try {
            workOrderRepository.saveAndFlush(wo2);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            caught = ex;
        }
        assertThat(caught).as("Expected a DataIntegrityViolationException for the duplicate WorkOrder").isNotNull();

        eg.mts.gsuif.exception.GlobalExceptionHandler handler = new eg.mts.gsuif.exception.GlobalExceptionHandler();
        org.springframework.http.ResponseEntity<eg.mts.gsuif.dto.ApiResponse<Void>> response =
                handler.handleDataIntegrityViolation(caught);

        // Must use the generic uniqueness path — NOT the project-name path.
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().clientMessage())
                .as("Poison WorkOrder value must not trigger the project-name error path")
                .isEqualTo("Resource already exists or violates unique constraint");
        assertThat(response.getBody().errors())
                .as("errors must be null for a non-project-name constraint")
                .isNull();
        // Confirm the constraint name itself is not exposed anywhere in the client response.
        assertThat(response.getBody().clientMessage()).doesNotContain("uk_gsuif_project_name");
    }
}
