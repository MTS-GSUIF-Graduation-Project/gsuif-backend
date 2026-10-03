package eg.mts.gsuif.controller;

import eg.mts.gsuif.dto.CreateMetadataVersionRequest;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "esraa.abdelrazek", roles = {"USER"})
class MetadataVersionControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private tools.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private GsuifProjectRepository projectRepository;

    @Autowired
    private GsuifPageRepository pageRepository;

    @Autowired
    private MetadataVersionRepository metadataVersionRepository;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("UPDATE gsuif_page SET current_metadata_version_id = NULL");
        metadataVersionRepository.deleteAll();
        pageRepository.deleteAll();
        projectRepository.deleteAll();
    }

    @Test
    void create_withValidPayload_returns201AndTrimsSchemaVersion() throws Exception {
        GsuifPage page = savedPage("Home");

        String payload = """
                {
                    "schemaVersion": "  1.0.0  ",
                    "snapshot": {
                        "components": [
                            {
                                "id": "123e4567-e89b-12d3-a456-426614174000",
                                "type": "text-field",
                                "label": "Test",
                                "position": { "row": 0, "col": 0 },
                                "size": { "width": 6, "height": 1 },
                                "visibility": true,
                                "disabled": false
                            }
                        ],
                        "apiBindings": []
                    }
                }
                """;

        mockMvc.perform(post("/api/v1/pages/{pageId}/metadata", page.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andExpect(jsonPath("$.body.version").value(1))
                .andExpect(jsonPath("$.body.isCurrent").value(true))
                .andExpect(jsonPath("$.body.schemaVersion").value("1.0.0"))
                .andExpect(jsonPath("$.body.snapshot.components").isArray());
    }

    @Test
    void create_withVersionElevenPersistsTypedComponentAndRejectsInvalidConfig() throws Exception {
        GsuifPage page = savedPage("Typed metadata");
        String valid = """
                {
                  "schemaVersion": "1.1.0",
                  "snapshot": {
                    "components": [{
                      "id": "123e4567-e89b-12d3-a456-426614174001",
                      "type": "form", "label": "Edit",
                      "position": {"row": 0, "col": 0},
                      "size": {"width": 2, "height": 1},
                      "visibility": true, "disabled": false,
                      "formConfig": {"submitLabel": "Save"}
                    }],
                    "apiBindings": []
                  }
                }
                """;
        mockMvc.perform(post("/api/v1/pages/{pageId}/metadata", page.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.body.schemaVersion").value("1.1.0"))
                .andExpect(jsonPath("$.body.snapshot.components[0].formConfig.submitLabel").value("Save"));
        MetadataVersion first = metadataVersionRepository.findAll().get(0);
        assertThat(first.getSchemaVersion()).isEqualTo("1.1.0");

        String invalid = valid.replace("\"submitLabel\": \"Save\"", "\"submitLabel\": \"\"");
        mockMvc.perform(post("/api/v1/pages/{pageId}/metadata", page.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['$.snapshot.components[0].formConfig.submitLabel']").exists());
        assertThat(metadataVersionRepository.findAll()).hasSize(1);
        assertThat(pageRepository.findById(page.getId()).orElseThrow().getCurrentMetadataVersionId())
                .isEqualTo(first.getId());
    }

    @Test
    void create_withClientSuppliedServerManagedFields_returns400() throws Exception {
        GsuifPage page = savedPage("Home");

        String payload = """
                {
                    "schemaVersion": "1.0.0",
                    "snapshot": {
                        "components": [
                            {
                                "id": "123e4567-e89b-12d3-a456-426614174000",
                                "type": "text-field",
                                "label": "Test",
                                "position": { "row": 0, "col": 0 },
                                "size": { "width": 6, "height": 1 },
                                "visibility": true,
                                "disabled": false
                            }
                        ],
                        "apiBindings": []
                    },
                    "version": 99,
                    "isCurrent": false
                }
                """;

        mockMvc.perform(post("/api/v1/pages/{pageId}/metadata", page.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.statusCode").value(400))
                .andExpect(jsonPath("$.clientMessage").value("Malformed request body"));
    }

    @Test
    void getLatest_whenVersionsExist_returnsHighestVersion() throws Exception {
        GsuifPage page = savedPage("Home");
        savedMetadataVersion(page, 1, "1.0", false);
        savedMetadataVersion(page, 2, "1.1", true);

        mockMvc.perform(get("/api/v1/pages/{pageId}/metadata/latest", page.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.version").value(2))
                .andExpect(jsonPath("$.body.isCurrent").value(true));
    }

    @Test
    void getLatest_whenNoVersions_returns404() throws Exception {
        GsuifPage page = savedPage("Empty Page");

        mockMvc.perform(get("/api/v1/pages/{pageId}/metadata/latest", page.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.statusCode").value(404));
    }

    @Test
    void getById_whenBelongsToAnotherPage_returns404() throws Exception {
        GsuifPage p1 = savedPage("Page 1");
        GsuifPage p2 = savedPage("Page 2");
        MetadataVersion mv1 = savedMetadataVersion(p1, 1, "1.0", true);

        mockMvc.perform(get("/api/v1/pages/{pageId}/metadata/{versionId}", p2.getId(), mv1.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.statusCode").value(404));
    }

    @Test
    void getAll_whenEmpty_returns200WithEmptyList() throws Exception {
        GsuifPage page = savedPage("Empty List");

        mockMvc.perform(get("/api/v1/pages/{pageId}/metadata", page.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.body.data").isEmpty());
    }

    @Test
    void getAll_returnsSortedByVersionDescByDefault() throws Exception {
        GsuifPage page = savedPage("List Page");
        savedMetadataVersion(page, 1, "1.0", false);
        savedMetadataVersion(page, 3, "1.2", true);
        savedMetadataVersion(page, 2, "1.1", false);

        mockMvc.perform(get("/api/v1/pages/{pageId}/metadata", page.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.data", hasSize(3)))
                .andExpect(jsonPath("$.body.data[0].version").value(3))
                .andExpect(jsonPath("$.body.data[1].version").value(2))
                .andExpect(jsonPath("$.body.data[2].version").value(1));
    }

    @Test
    void create_whenPageIsMissing_returns404() throws Exception {
        String payload = """
                {
                    "schemaVersion": "1.0.0",
                    "snapshot": {
                        "components": [
                            {
                                "id": "123e4567-e89b-12d3-a456-426614174000",
                                "type": "text-field",
                                "label": "Test",
                                "position": { "row": 0, "col": 0 },
                                "size": { "width": 6, "height": 1 },
                                "visibility": true,
                                "disabled": false
                            }
                        ],
                        "apiBindings": []
                    }
                }
                """;

        mockMvc.perform(post("/api/v1/pages/{pageId}/metadata", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.statusCode").value(404));
    }

    @Test
    void getById_whenExists_returns200AndMetadataVersion() throws Exception {
        GsuifPage page = savedPage("By ID Page");
        MetadataVersion mv = savedMetadataVersion(page, 1, "1.0", true);

        mockMvc.perform(get("/api/v1/pages/{pageId}/metadata/{versionId}", page.getId(), mv.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.body.version").value(1));
    }

    @Test
    void getAll_whenPageIsMissing_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/pages/{pageId}/metadata", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.statusCode").value(404));
    }

    @Test
    void getAll_withPaginationParamsAndExceedsSize100_capsSizeTo100() throws Exception {
        GsuifPage page = savedPage("Cap Test Page");
        // We just need to check the pagination metadata reflects the cap.
        mockMvc.perform(get("/api/v1/pages/{pageId}/metadata?page=1&size=200", page.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.number").value(1))
                .andExpect(jsonPath("$.body.size").value(100)); // The capped size
    }

    @Test
    void getAll_whenDatabaseJsonIsInvalid_returns500() throws Exception {
        GsuifPage page = savedPage("Broken Snapshot Page");
        MetadataVersion mv = new MetadataVersion();
        mv.setPage(page);
        mv.setVersion(1);
        mv.setSchemaVersion("1.0");
        // deliberately invalid JSON string to trigger DB-to-DTO deserialization failure
        mv.setSnapshot("invalid json");
        metadataVersionRepository.save(mv);

        mockMvc.perform(get("/api/v1/pages/{pageId}/metadata", page.getId()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.statusCode").value(500))
                .andExpect(jsonPath("$.clientMessage").value("An unexpected error occurred"));
    }

    @Test
    void create_secondVersion_verifiesFirstVersionRemainsUnchanged() throws Exception {
        GsuifPage page = savedPage("Two Versions Page");
        
        // Create first version via API to ensure proper setup
        String payload1 = """
                {
                    "schemaVersion": "1.0.0",
                    "snapshot": {
                        "components": [
                            {
                                "id": "123e4567-e89b-12d3-a456-426614174001",
                                "type": "text-field",
                                "label": "Test1",
                                "position": { "row": 0, "col": 0 },
                                "size": { "width": 6, "height": 1 },
                                "visibility": true,
                                "disabled": false
                            }
                        ],
                        "apiBindings": []
                    }
                }
                """;
        mockMvc.perform(post("/api/v1/pages/{pageId}/metadata", page.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload1))
                .andExpect(status().isCreated());

        // Fetch it directly from DB
        MetadataVersion firstVersion = metadataVersionRepository.findAll().get(0);
        String firstSchema = firstVersion.getSchemaVersion();
        String firstSnapshot = firstVersion.getSnapshot();
        String firstCreatedBy = firstVersion.getCreatedBy();
        java.time.Instant firstCreatedAt = firstVersion.getCreatedAt();
        java.time.Instant firstUpdatedAt = firstVersion.getUpdatedAt();
        String firstLastModifiedBy = firstVersion.getLastModifiedBy();

        // Small delay to ensure timestamp is strictly greater
        Thread.sleep(10);

        // Create second version
        String payload2 = """
                {
                    "schemaVersion": "1.0.0",
                    "snapshot": {
                        "components": [
                            {
                                "id": "123e4567-e89b-12d3-a456-426614174002",
                                "type": "text-field",
                                "label": "Test2",
                                "position": { "row": 0, "col": 0 },
                                "size": { "width": 6, "height": 1 },
                                "visibility": true,
                                "disabled": false
                            }
                        ],
                        "apiBindings": []
                    }
                }
                """;
        mockMvc.perform(post("/api/v1/pages/{pageId}/metadata", page.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload2))
                .andExpect(status().isCreated());

        // Re-fetch first version
        MetadataVersion updatedFirstVersion = metadataVersionRepository.findById(firstVersion.getId()).orElseThrow();
        
        // Assert old version is completely immutable
        assertThat(updatedFirstVersion.getVersion()).isEqualTo(1);
        assertThat(updatedFirstVersion.getSchemaVersion()).isEqualTo(firstSchema);
        assertThat(updatedFirstVersion.getSnapshot()).isEqualTo(firstSnapshot);
        assertThat(updatedFirstVersion.getCreatedBy()).isEqualTo(firstCreatedBy);
        assertThat(updatedFirstVersion.getCreatedAt()).isEqualTo(firstCreatedAt);
        assertThat(updatedFirstVersion.getUpdatedAt()).isEqualTo(firstUpdatedAt);
        assertThat(updatedFirstVersion.getLastModifiedBy()).isEqualTo(firstLastModifiedBy);
        
        // Assert page pointer changed
        GsuifPage updatedPage = pageRepository.findById(page.getId()).orElseThrow();
        assertThat(updatedPage.getCurrentMetadataVersionId()).isNotEqualTo(firstVersion.getId());
    }

    private GsuifPage savedPage(String name) {
        GsuifProject p = new GsuifProject();
        p.setName("Project for " + name);
        projectRepository.save(p);

        GsuifPage page = new GsuifPage();
        page.setProject(p);
        page.setName(name);
        return pageRepository.save(page);
    }

    @Test
    void create_withSchemaVersionLength21AfterTrimming_returns400() throws Exception {
        GsuifPage page = savedPage("Length Test");

        String payload = """
                {
                    "schemaVersion": "  123456789012345678901  ",
                    "snapshot": {
                        "components": [
                            {
                                "id": "123e4567-e89b-12d3-a456-426614174000",
                                "type": "text-field",
                                "label": "Test",
                                "position": { "row": 0, "col": 0 },
                                "size": { "width": 6, "height": 1 },
                                "visibility": true,
                                "disabled": false
                            }
                        ],
                        "apiBindings": []
                    }
                }
                """;

        mockMvc.perform(post("/api/v1/pages/{pageId}/metadata", page.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.statusCode").value(400))
                .andExpect(jsonPath("$.errors.schemaVersion").exists());
    }
    
    @Test
    void create_withSchemaVersionLength20AfterTrimming_passesDtoValidationAndReturns400FromMetadataValidator() throws Exception {
        GsuifPage page = savedPage("Length Test");

        String payload = """
                {
                    "schemaVersion": "  12345678901234567890  ",
                    "snapshot": {
                        "components": [
                            {
                                "id": "123e4567-e89b-12d3-a456-426614174000",
                                "type": "text-field",
                                "label": "Test",
                                "position": { "row": 0, "col": 0 },
                                "size": { "width": 6, "height": 1 },
                                "visibility": true,
                                "disabled": false
                            }
                        ],
                        "apiBindings": []
                    }
                }
                """;

        mockMvc.perform(post("/api/v1/pages/{pageId}/metadata", page.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.statusCode").value(400))
                .andExpect(jsonPath("$.errors['$.schemaVersion']").exists());
    }

    @Test
    void create_withNullSnapshot_returns400() throws Exception {
        GsuifPage page = savedPage("Null Snapshot Test");

        String payload = """
                {
                    "schemaVersion": "1.0",
                    "snapshot": null
                }
                """;

        mockMvc.perform(post("/api/v1/pages/{pageId}/metadata", page.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.statusCode").value(400))
                .andExpect(jsonPath("$.errors.snapshot").exists());
    }
    
    @Test
    void create_withMalformedJson_returns400() throws Exception {
        GsuifPage page = savedPage("Malformed json Test");

        String payload = """
                {
                    "schemaVersion": "1.0",
                    "snapshot": { unquotedKey: "value" }
                }
                """;

        mockMvc.perform(post("/api/v1/pages/{pageId}/metadata", page.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.statusCode").value(400));
    }
    @Test
    void create_withInvalidSnapshot_returns400_createsNoVersion_preservesPointer() throws Exception {
        GsuifPage page = savedPage("Schema Rejection Page");

        // 1. Create valid first version
        String payload1 = """
                {
                    "schemaVersion": "1.0.0",
                    "snapshot": {
                        "components": [
                            {
                                "id": "123e4567-e89b-12d3-a456-426614174001",
                                "type": "text-field",
                                "label": "Valid",
                                "position": { "row": 0, "col": 0 },
                                "size": { "width": 6, "height": 1 },
                                "visibility": true,
                                "disabled": false
                            }
                        ],
                        "apiBindings": []
                    }
                }
                """;
        mockMvc.perform(post("/api/v1/pages/{pageId}/metadata", page.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload1))
                .andExpect(status().isCreated());

        MetadataVersion firstVersion = metadataVersionRepository.findAll().get(0);

        // 2. Try to create second version but it's structurally invalid (missing components)
        String payload2 = """
                {
                    "schemaVersion": "unsupported",
                    "snapshot": {
                        "apiBindings": [{
                            "id": "123e4567-e89b-12d3-a456-426614174000",
                            "httpMethod": 999,
                            "endpointUrl": "/api/v1/work-orders",
                            "headers": {}, "requestMapping": {}, "responseMapping": {}
                        }]
                    }
                }
                """;
        String rejectedBody = mockMvc.perform(post("/api/v1/pages/{pageId}/metadata", page.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload2))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.statusCode").value(400))
                .andExpect(jsonPath("$.errors['$.schemaVersion']").value("unsupported schemaVersion; supported versions: 1.0.0, 1.1.0"))
                .andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode envelope = new com.fasterxml.jackson.databind.ObjectMapper().readTree(rejectedBody);
        java.util.Set<String> fields = new java.util.HashSet<>();
        envelope.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("status", "clientMessage", "statusCode", "body", "errors");
        assertThat(envelope.get("body").isNull()).isTrue();
        assertThat(envelope.get("status").asText()).isEqualTo("BAD_REQUEST");
        assertThat(envelope.get("clientMessage").asText()).isEqualTo("Request validation failed");
        assertThat(envelope.get("errors").size()).isEqualTo(1);

        // 3. Verify no new version created
        assertThat(metadataVersionRepository.findAll()).hasSize(1);

        // 4. Verify pointer not moved
        GsuifPage updatedPage = pageRepository.findById(page.getId()).orElseThrow();
        assertThat(updatedPage.getCurrentMetadataVersionId()).isEqualTo(firstVersion.getId());
    }
    @Test
    void create_withHighPrecisionDecimal_failsSchemaValidationAndDoesNotPersist() throws Exception {
        GsuifPage page = savedPage("Decimal Rejection Page");

        // 1. Create valid first version
        String payload1 = """
                {
                    "schemaVersion": "1.0.0",
                    "snapshot": {
                        "components": [
                            {
                                "id": "123e4567-e89b-12d3-a456-426614174000",
                                "type": "text-field",
                                "label": "Test",
                                "position": { "row": 0, "col": 0 },
                                "size": { "width": 6, "height": 1 },
                                "visibility": true,
                                "disabled": false
                            }
                        ],
                        "apiBindings": []
                    }
                }
                """;

        String responseBody = mockMvc.perform(post("/api/v1/pages/{pageId}/metadata", page.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload1))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        eg.mts.gsuif.dto.MetadataVersionDto firstVersion = new tools.jackson.databind.ObjectMapper()
                .readValue(responseBody, new tools.jackson.core.type.TypeReference<eg.mts.gsuif.dto.ApiResponse<eg.mts.gsuif.dto.MetadataVersionDto>>() {})
                .body();

        // 2. Try to create version with high precision decimal (width is supposed to be integer)
        String invalidPayload = """
                {
                    "schemaVersion": "1.0.0",
                    "snapshot": {
                        "components": [
                            {
                                "id": "123e4567-e89b-12d3-a456-426614174001",
                                "type": "text-field",
                                "label": "Test",
                                "position": { "row": 0, "col": 0 },
                                "size": { "width": 0.9999999999999999999999999999, "height": 1 },
                                "visibility": true,
                                "disabled": false
                            }
                        ],
                        "apiBindings": []
                    }
                }
                """;

        CreateMetadataVersionRequest parsed = objectMapper.readValue(invalidPayload, CreateMetadataVersionRequest.class);
        assertThat(parsed.snapshot().at("/components/0/size/width").decimalValue())
                .isEqualByComparingTo("0.9999999999999999999999999999");

        mockMvc.perform(post("/api/v1/pages/{pageId}/metadata", page.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.statusCode").value(400))
                .andExpect(jsonPath("$.errors['$.snapshot.components[0].size.width']").value(
                        "$.components[0].size.width: must have a minimum value of 1; "
                                + "$.components[0].size.width: number found, integer expected"));

        // 3. Verify no new version created
        assertThat(metadataVersionRepository.findAll()).hasSize(1);

        // 4. Verify pointer not moved
        GsuifPage updatedPage = pageRepository.findById(page.getId()).orElseThrow();
        assertThat(updatedPage.getCurrentMetadataVersionId()).isEqualTo(firstVersion.id());
    }

    @Test
    void current_whenUnselectedOrPageMissing_returns404() throws Exception {
        GsuifPage page = savedPage("Unselected");
        mockMvc.perform(get("/api/v1/pages/{pageId}/metadata/current", page.getId()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.statusCode").value(404));
        mockMvc.perform(get("/api/v1/pages/{pageId}/metadata/current", UUID.randomUUID()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.statusCode").value(404));
    }

    @Test
    void selectCurrent_rollsBackSelectionWhileLatestAndVersionsStayIntact() throws Exception {
        GsuifPage page = savedPage("Selection");
        MetadataVersion first = savedMetadataVersion(page, 1, "1.0", false);
        MetadataVersion second = savedMetadataVersion(page, 2, "1.1", true);
        first = metadataVersionRepository.findById(first.getId()).orElseThrow();
        second = metadataVersionRepository.findById(second.getId()).orElseThrow();
        String firstSnapshot = first.getSnapshot();
        String secondSnapshot = second.getSnapshot();
        var firstCreatedAt = first.getCreatedAt();
        var secondCreatedAt = second.getCreatedAt();
        var firstUpdatedAt = first.getUpdatedAt();
        var secondUpdatedAt = second.getUpdatedAt();
        String firstCreatedBy = first.getCreatedBy();
        String secondCreatedBy = second.getCreatedBy();
        String firstModifiedBy = first.getLastModifiedBy();
        String secondModifiedBy = second.getLastModifiedBy();

        String request = "{\"versionId\":\"" + first.getId() + "\"}";
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(put("/api/v1/pages/{pageId}/metadata/current", page.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(request))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.statusCode").value(200))
                    .andExpect(jsonPath("$.body.id").value(first.getId().toString()))
                    .andExpect(jsonPath("$.body.isCurrent").value(true));
        }
        mockMvc.perform(get("/api/v1/pages/{pageId}/metadata/current", page.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.id").value(first.getId().toString()))
                .andExpect(jsonPath("$.body.isCurrent").value(true));
        mockMvc.perform(get("/api/v1/pages/{pageId}/metadata/latest", page.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.body.id").value(second.getId().toString()))
                .andExpect(jsonPath("$.body.isCurrent").value(false));
        assertThat(pageRepository.findById(page.getId()).orElseThrow().getCurrentMetadataVersionId()).isEqualTo(first.getId());
        MetadataVersion reloadedFirst = metadataVersionRepository.findById(first.getId()).orElseThrow();
        MetadataVersion reloadedSecond = metadataVersionRepository.findById(second.getId()).orElseThrow();
        assertThat(reloadedFirst.getVersion()).isEqualTo(1);
        assertThat(reloadedSecond.getVersion()).isEqualTo(2);
        assertThat(reloadedFirst.getSchemaVersion()).isEqualTo("1.0");
        assertThat(reloadedSecond.getSchemaVersion()).isEqualTo("1.1");
        assertThat(reloadedFirst.getSnapshot()).isEqualTo(firstSnapshot);
        assertThat(reloadedSecond.getSnapshot()).isEqualTo(secondSnapshot);
        assertThat(reloadedFirst.getCreatedAt()).isEqualTo(firstCreatedAt);
        assertThat(reloadedSecond.getCreatedAt()).isEqualTo(secondCreatedAt);
        assertThat(reloadedFirst.getUpdatedAt()).isEqualTo(firstUpdatedAt);
        assertThat(reloadedSecond.getUpdatedAt()).isEqualTo(secondUpdatedAt);
        assertThat(reloadedFirst.getCreatedBy()).isEqualTo(firstCreatedBy);
        assertThat(reloadedSecond.getCreatedBy()).isEqualTo(secondCreatedBy);
        assertThat(reloadedFirst.getLastModifiedBy()).isEqualTo(firstModifiedBy);
        assertThat(reloadedSecond.getLastModifiedBy()).isEqualTo(secondModifiedBy);
    }

    @Test
    void selectCurrent_rejectsMissingAndCrossPageVersions() throws Exception {
        GsuifPage page = savedPage("Selection target");
        GsuifPage other = savedPage("Selection owner");
        MetadataVersion owned = savedMetadataVersion(other, 1, "1.0", true);
        for (UUID versionId : new UUID[]{UUID.randomUUID(), owned.getId()}) {
            mockMvc.perform(put("/api/v1/pages/{pageId}/metadata/current", page.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"versionId\":\"" + versionId + "\"}"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.statusCode").value(404));
        }
        mockMvc.perform(put("/api/v1/pages/{pageId}/metadata/current", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versionId\":\"" + owned.getId() + "\"}"))
                .andExpect(status().isNotFound());
        assertThat(pageRepository.findById(page.getId()).orElseThrow().getCurrentMetadataVersionId()).isNull();
    }

    @Test
    void selectCurrent_rejectsMalformedAndAbsentRequest() throws Exception {
        GsuifPage page = savedPage("Bad selection");
        for (String body : new String[]{"{}", "{\"versionId\":null}", "{\"versionId\":\"invalid\"}"}) {
            mockMvc.perform(put("/api/v1/pages/{pageId}/metadata/current", page.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.statusCode").value(400));
        }
        mockMvc.perform(put("/api/v1/pages/{pageId}/metadata/current", page.getId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.statusCode").value(400));
    }

    private MetadataVersion savedMetadataVersion(GsuifPage page, int version, String schemaVersion, boolean isCurrent) {
        MetadataVersion mv = new MetadataVersion();
        mv.setPage(page);
        mv.setVersion(version);
        mv.setSchemaVersion(schemaVersion);
        mv.setSnapshot("{}");
        mv = metadataVersionRepository.save(mv);
        if (isCurrent) {
            page.setCurrentMetadataVersionId(mv.getId());
            pageRepository.save(page);
        }
        return mv;
    }
}
