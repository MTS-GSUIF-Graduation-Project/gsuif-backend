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
                        "elements": []
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
                .andExpect(jsonPath("$.body.snapshot.elements").isArray());
    }

    @Test
    void create_withClientSuppliedServerManagedFields_returns400() throws Exception {
        GsuifPage page = savedPage("Home");

        String payload = """
                {
                    "schemaVersion": "1.0",
                    "snapshot": {},
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
                    "schemaVersion": "1.0",
                    "snapshot": {}
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
                    "schemaVersion": "1.0",
                    "snapshot": {"v":1}
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
                    "schemaVersion": "2.0",
                    "snapshot": {"v":2}
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
                    "snapshot": {}
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
    void create_withSchemaVersionLength20AfterTrimming_returns201() throws Exception {
        GsuifPage page = savedPage("Length Test");

        String payload = """
                {
                    "schemaVersion": "  12345678901234567890  ",
                    "snapshot": {}
                }
                """;

        mockMvc.perform(post("/api/v1/pages/{pageId}/metadata", page.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andExpect(jsonPath("$.body.schemaVersion").value("12345678901234567890"));
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
