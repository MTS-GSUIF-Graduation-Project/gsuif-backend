package eg.mts.gsuif.controller;

import eg.mts.gsuif.dto.CreateWorkOrderRequest;
import eg.mts.gsuif.dto.UpdateWorkOrderRequest;
import eg.mts.gsuif.entity.WorkOrder;
import eg.mts.gsuif.entity.WorkOrderStatus;
import eg.mts.gsuif.filter.CorrelationIdFilter;
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
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration tests for {@link WorkOrderController}.
 *
 * <p>Validates:
 * <ul>
 *   <li>STD-01..03: Standard 5-field envelope on success and error responses.
 *   <li>STD-04: Paginated shape on {@code GET /api/v1/work-orders}.
 *   <li>STD-05: Non-raw return types.
 *   <li>STD-10: HTTP 400 on validation failure.
 *   <li>STD-11: HTTP 404 on non-existent resource.
 *   <li>STD-17: Correlation ID header propagation.
 *   <li>STD-24: JPA Auditing fields populated.
 *   <li>STD-28: HTTP 201 on create, HTTP 200 on get/put/delete.
 *   <li>STD-34: Executes on in-memory H2 (PostgreSQL mode).
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "esraa.abdelrazek", roles = {"USER"})
class WorkOrderControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkOrderRepository workOrderRepository;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @BeforeEach
    void setUp() {
        workOrderRepository.deleteAll();
    }

    // ── 1. Create (POST) ──────────────────────────────────────────────────────

    @Test
    void create_withValidPayload_returns201WithStandardEnvelope() throws Exception {
        CreateWorkOrderRequest request = new CreateWorkOrderRequest(
                "WO-2026-001",
                WorkOrderStatus.OPEN,
                LocalDate.of(2026, 10, 1),
                "esraa.abdelrazek"
        );

        mockMvc.perform(post("/api/v1/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.statusCode").value(201))
                .andExpect(jsonPath("$.clientMessage").value("Work order created successfully"))
                .andExpect(jsonPath("$.errors").value(nullValue()))
                .andExpect(jsonPath("$.body.id").isNotEmpty())
                .andExpect(jsonPath("$.body.orderNumber").value("WO-2026-001"))
                .andExpect(jsonPath("$.body.status").value("OPEN"))
                .andExpect(jsonPath("$.body.dueDate").value("2026-10-01"))
                .andExpect(jsonPath("$.body.assignedTo").value("esraa.abdelrazek"))
                .andExpect(jsonPath("$.body.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.body.createdBy").value("esraa.abdelrazek"))
                .andExpect(header().string(CorrelationIdFilter.CORRELATION_ID_HEADER, not(emptyOrNullString())));

        assertThat(workOrderRepository.existsByOrderNumber("WO-2026-001")).isTrue();
    }

    @Test
    void create_withBlankOrderNumber_returns400WithFieldErrors() throws Exception {
        CreateWorkOrderRequest request = new CreateWorkOrderRequest(
                "",
                WorkOrderStatus.OPEN,
                LocalDate.now(),
                "user"
        );

        mockMvc.perform(post("/api/v1/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.statusCode").value(400))
                .andExpect(jsonPath("$.clientMessage").value("Validation failed"))
                .andExpect(jsonPath("$.body").value(nullValue()))
                .andExpect(jsonPath("$.errors.orderNumber").value("Order number is required"));
    }

    @Test
    void create_withDuplicateOrderNumber_returns400WithDuplicateMessage() throws Exception {
        WorkOrder existing = new WorkOrder("WO-DUP-01", WorkOrderStatus.OPEN, LocalDate.now(), "user");
        workOrderRepository.save(existing);

        CreateWorkOrderRequest request = new CreateWorkOrderRequest(
                "WO-DUP-01",
                WorkOrderStatus.OPEN,
                LocalDate.now(),
                "another.user"
        );

        mockMvc.perform(post("/api/v1/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.statusCode").value(400))
                .andExpect(jsonPath("$.clientMessage").value("Work order with order number 'WO-DUP-01' already exists"))
                .andExpect(jsonPath("$.body").value(nullValue()))
                .andExpect(jsonPath("$.errors").value(nullValue()));
    }

    @Test
    void create_withMalformedJson_returns400WithStandardEnvelope() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ invalid json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.statusCode").value(400))
                .andExpect(jsonPath("$.clientMessage").value("Malformed JSON or invalid request payload"))
                .andExpect(jsonPath("$.body").value(nullValue()))
                .andExpect(jsonPath("$.errors").value(nullValue()));
    }

    @Test
    void create_withInvalidStatusEnum_returns400WithStandardEnvelope() throws Exception {
        String payload = """
                {
                    "orderNumber": "WO-INV-001",
                    "status": "NON_EXISTENT_STATUS",
                    "dueDate": "2026-10-01",
                    "assignedTo": "user"
                }
                """;

        mockMvc.perform(post("/api/v1/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.statusCode").value(400))
                .andExpect(jsonPath("$.clientMessage").value("Malformed JSON or invalid request payload"))
                .andExpect(jsonPath("$.body").value(nullValue()))
                .andExpect(jsonPath("$.errors").value(nullValue()));
    }

    // ── 2. Get By ID (GET) ────────────────────────────────────────────────────

    @Test
    void getById_whenExists_returns200WithDto() throws Exception {
        WorkOrder saved = workOrderRepository.save(
                new WorkOrder("WO-2026-002", WorkOrderStatus.IN_PROGRESS, LocalDate.of(2026, 11, 15), "user")
        );

        mockMvc.perform(get("/api/v1/work-orders/{id}", saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.errors").value(nullValue()))
                .andExpect(jsonPath("$.body.orderNumber").value("WO-2026-002"))
                .andExpect(jsonPath("$.body.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.body.assignedTo").value("user"));
    }

    @Test
    void getById_whenNotFound_returns404() throws Exception {
        UUID nonExistentId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/work-orders/{id}", nonExistentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.status").value("NOT_FOUND"))
                .andExpect(jsonPath("$.statusCode").value(404))
                .andExpect(jsonPath("$.clientMessage").value("Work order not found with id: " + nonExistentId))
                .andExpect(jsonPath("$.body").value(nullValue()))
                .andExpect(jsonPath("$.errors").value(nullValue()));
    }

    @Test
    void getById_withInvalidUuid_returns400WithStandardEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/work-orders/{id}", "not-a-valid-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.statusCode").value(400))
                .andExpect(jsonPath("$.clientMessage").value("Invalid value 'not-a-valid-uuid' for parameter 'id'"))
                .andExpect(jsonPath("$.body").value(nullValue()))
                .andExpect(jsonPath("$.errors").value(nullValue()));
    }

    // ── 3. List / Pagination (GET) ────────────────────────────────────────────

    @Test
    void getAll_returnsPaginatedBodyPerSTD04() throws Exception {
        workOrderRepository.save(new WorkOrder("WO-PAGE-1", WorkOrderStatus.OPEN, LocalDate.now(), "user1"));
        workOrderRepository.save(new WorkOrder("WO-PAGE-2", WorkOrderStatus.COMPLETED, LocalDate.now(), "user2"));

        mockMvc.perform(get("/api/v1/work-orders?page=0&size=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.errors").value(nullValue()))
                .andExpect(jsonPath("$.body.data").isArray())
                .andExpect(jsonPath("$.body.data.length()").value(2))
                .andExpect(jsonPath("$.body.totalPages").value(1))
                .andExpect(jsonPath("$.body.totalElements").value(2))
                .andExpect(jsonPath("$.body.size").value(5))
                .andExpect(jsonPath("$.body.number").value(0));
    }

    @Test
    void getAll_withStatusFilter_returnsOnlyMatchingStatus() throws Exception {
        workOrderRepository.save(new WorkOrder("WO-FILTER-1", WorkOrderStatus.OPEN, LocalDate.now(), "user1"));
        workOrderRepository.save(new WorkOrder("WO-FILTER-2", WorkOrderStatus.COMPLETED, LocalDate.now(), "user2"));

        mockMvc.perform(get("/api/v1/work-orders?status=OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.errors").value(nullValue()))
                .andExpect(jsonPath("$.body.data.length()").value(1))
                .andExpect(jsonPath("$.body.data[0].orderNumber").value("WO-FILTER-1"));
    }

    // ── 4. Update (PUT) ───────────────────────────────────────────────────────

    @Test
    void update_whenExists_returns200AndUpdatesRecord() throws Exception {
        WorkOrder saved = workOrderRepository.save(
                new WorkOrder("WO-UPDATE-1", WorkOrderStatus.OPEN, LocalDate.of(2026, 9, 1), "old.user")
        );

        UpdateWorkOrderRequest request = new UpdateWorkOrderRequest(
                WorkOrderStatus.COMPLETED,
                LocalDate.of(2026, 9, 25),
                "new.user"
        );

        mockMvc.perform(put("/api/v1/work-orders/{id}", saved.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.errors").value(nullValue()))
                .andExpect(jsonPath("$.body.status").value("COMPLETED"))
                .andExpect(jsonPath("$.body.dueDate").value("2026-09-25"))
                .andExpect(jsonPath("$.body.assignedTo").value("new.user"));

        WorkOrder refreshed = workOrderRepository.findById(saved.getId()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(WorkOrderStatus.COMPLETED);
        assertThat(refreshed.getAssignedTo()).isEqualTo("new.user");
    }

    @Test
    void update_whenNotFound_returns404() throws Exception {
        UUID nonExistentId = UUID.randomUUID();
        UpdateWorkOrderRequest request = new UpdateWorkOrderRequest(
                WorkOrderStatus.COMPLETED,
                LocalDate.now(),
                "user"
        );

        mockMvc.perform(put("/api/v1/work-orders/{id}", nonExistentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.status").value("NOT_FOUND"))
                .andExpect(jsonPath("$.statusCode").value(404))
                .andExpect(jsonPath("$.body").value(nullValue()))
                .andExpect(jsonPath("$.errors").value(nullValue()));
    }

    // ── 5. Delete (DELETE) ────────────────────────────────────────────────────

    @Test
    void delete_whenExists_returns200AndDeletesRecord() throws Exception {
        WorkOrder saved = workOrderRepository.save(
                new WorkOrder("WO-DEL-1", WorkOrderStatus.OPEN, LocalDate.now(), "user")
        );

        mockMvc.perform(delete("/api/v1/work-orders/{id}", saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.clientMessage").value("Work order deleted successfully"))
                .andExpect(jsonPath("$.errors").value(nullValue()));

        assertThat(workOrderRepository.existsById(saved.getId())).isFalse();

        // Verify subsequent GET returns 404
        mockMvc.perform(get("/api/v1/work-orders/{id}", saved.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_whenNotFound_returns404() throws Exception {
        UUID nonExistentId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/work-orders/{id}", nonExistentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.status").value("NOT_FOUND"))
                .andExpect(jsonPath("$.statusCode").value(404))
                .andExpect(jsonPath("$.body").value(nullValue()))
                .andExpect(jsonPath("$.errors").value(nullValue()));
    }

    // ── 6. Entity Proxy Safety ────────────────────────────────────────────────

    @Test
    @org.springframework.transaction.annotation.Transactional
    void entityEqualityAndHashCode_isFullyProxySafeWithHibernateProxy() {
        WorkOrder saved = new WorkOrder("WO-PROXY-01", WorkOrderStatus.OPEN, LocalDate.now(), "user");
        entityManager.persist(saved);
        entityManager.flush();
        entityManager.clear();

        // Obtain an uninitialized Hibernate proxy
        WorkOrder proxy = entityManager.getReference(WorkOrder.class, saved.getId());

        // Verify it is an actual Hibernate runtime dynamic proxy
        assertThat(proxy).isInstanceOf(org.hibernate.proxy.HibernateProxy.class);

        // Verify equals() is symmetric across proxy and entity
        assertThat(proxy.equals(saved)).isTrue();
        assertThat(saved.equals(proxy)).isTrue();

        // Verify hashCode() is identical for proxy and entity
        assertThat(proxy.hashCode()).isEqualTo(saved.hashCode());

        // Verify collection semantics (HashSet contains both interchangeably)
        java.util.Set<WorkOrder> set = new java.util.HashSet<>();
        set.add(proxy);
        assertThat(set.contains(saved)).isTrue();
    }

    // ── 7. Global Handling Verification ───────────────────────────────────────

    @Test
    void methodNotSupported_returns405WithStandardEnvelope() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.status").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.statusCode").value(405))
                .andExpect(jsonPath("$.clientMessage").value("Method Not Allowed"))
                .andExpect(jsonPath("$.body").value(nullValue()))
                .andExpect(jsonPath("$.errors").value(nullValue()));
    }

    @Test
    void mediaTypeNotSupported_returns415WithStandardEnvelope() throws Exception {
        mockMvc.perform(post("/api/v1/work-orders")
                        .contentType(MediaType.APPLICATION_XML)
                        .content("<xml></xml>"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.status").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.statusCode").value(415))
                .andExpect(jsonPath("$.clientMessage").value("Unsupported Media Type"))
                .andExpect(jsonPath("$.body").value(nullValue()))
                .andExpect(jsonPath("$.errors").value(nullValue()));
    }
}
