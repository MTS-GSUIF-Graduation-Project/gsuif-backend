package eg.mts.gsuif.controller;

import eg.mts.gsuif.entity.*;
import eg.mts.gsuif.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import static eg.mts.gsuif.validator.BusinessRuleTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "scrum51", roles = "USER")
class MetadataBusinessControllerIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired GsuifProjectRepository projects;
    @Autowired GsuifPageRepository pages;
    @Autowired MetadataVersionRepository versions;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void clean() {
        jdbc.execute("UPDATE gsuif_page SET current_metadata_version_id = NULL");
        versions.deleteAll();
        pages.deleteAll();
        projects.deleteAll();
    }

    @ParameterizedTest @ValueSource(ints = {4_999_999, 5_000_000, 5_000_001})
    void exactSnapshotBoundaryIgnoresEnvelopeWhitespaceAndEquivalentEscapes(int bytes) throws Exception {
        GsuifPage page = page(project("Boundary"), "Home", null);
        MetadataVersion prior = prior(page);
        String snapshot = mapper.writeValueAsString(mapper.readTree(sized(bytes, false).toString()));
        assertThat(utf8(snapshot)).isEqualTo(bytes);
        // Equivalent JSON transport escape adds bytes but must not change the persisted representation.
        String escaped = snapshot.replace("é", "\\u00e9");
        for (String payload : new String[]{
                "{\"schemaVersion\":\"1.0.0\",\"snapshot\":" + snapshot + "}",
                "{\n  \"schemaVersion\": \"  1.0.0  \",\n  \"snapshot\": " + escaped + "\n}"}) {
            long before = versions.count();
            var response = mvc.perform(post("/api/v1/pages/{id}/metadata", page.getId())
                    .contentType(MediaType.APPLICATION_JSON).content(payload));
            if (bytes <= 5_000_000) {
                response.andExpect(status().isCreated());
                var stored = versions.findFirstByPageIdOrderByVersionDesc(page.getId()).orElseThrow();
                assertThat(stored.getSnapshot()).isEqualTo(snapshot);
                assertThat(utf8(stored.getSnapshot())).isEqualTo(bytes);
                assertThat(versions.count()).isEqualTo(before + 1);
                assertThat(pages.findById(page.getId()).orElseThrow().getCurrentMetadataVersionId())
                        .isEqualTo(stored.getId());
            } else {
                String body = response.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
                var json = assertEnvelope(body);
                assertThat(json.get("errors").get("$.snapshot").asText()).contains(SIZE);
                assertThat(versions.count()).isEqualTo(before);
                assertThat(pages.findById(page.getId()).orElseThrow().getCurrentMetadataVersionId()).isEqualTo(prior.getId());
            }
        }
        assertThat(versions.findById(prior.getId()).orElseThrow().getSnapshot()).isEqualTo("{\"components\":[],\"apiBindings\":[]}");
    }

    @Test void aggregatesNamedBusinessErrorsInFiveField400WithoutChangingPointer() throws Exception {
        GsuifPage page = page(project("Aggregation"), "Home", null);
        MetadataVersion prior = prior(page);
        String payload = "{\"schemaVersion\":\"1.0.0\",\"snapshot\":" + sized(5_000_001, true) + "}";
        String response = mvc.perform(post("/api/v1/pages/{id}/metadata", page.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        var json = assertEnvelope(response);
        assertThat(json.get("errors").get("$.snapshot").asText()).contains(SIZE);
        assertThat(json.get("errors").get("$.snapshot.components[1].id").asText()).contains(COMPONENT);
        assertThat(versions.count()).isEqualTo(1);
        assertThat(pages.findById(page.getId()).orElseThrow().getCurrentMetadataVersionId()).isEqualTo(prior.getId());
    }

    @Test void duplicateComponentAloneIsRejected() throws Exception {
        GsuifPage page = page(project("Duplicate"), "Home", null);
        var response = mvc.perform(post("/api/v1/pages/{id}/metadata", page.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"schemaVersion\":\"1.0.0\",\"snapshot\":" + duplicate(0) + "}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(assertEnvelope(response).get("errors").get("$.snapshot.components[1].id").asText()).contains(COMPONENT);
        assertThat(versions.count()).isZero();
        assertThat(pages.findById(page.getId()).orElseThrow().getCurrentMetadataVersionId()).isNull();
    }

    @Test void componentIdsAreLocalToEachSnapshotAndHistoryRemainsImmutable() throws Exception {
        GsuifProject project = project("Local IDs");
        GsuifPage firstPage = page(project, "First", null), secondPage = page(project, "Second", null);
        String payload = "{\"schemaVersion\":\"1.0.0\",\"snapshot\":" + snapshot(1, 0, "GET") + "}";
        mvc.perform(post("/api/v1/pages/{id}/metadata", firstPage.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isCreated());
        var original = versions.findFirstByPageIdOrderByVersionDesc(firstPage.getId()).orElseThrow();
        String originalSnapshot = original.getSnapshot();
        mvc.perform(post("/api/v1/pages/{id}/metadata", firstPage.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.body.version").value(2));
        mvc.perform(post("/api/v1/pages/{id}/metadata", secondPage.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.body.version").value(1));
        var unchanged = versions.findById(original.getId()).orElseThrow();
        assertThat(unchanged.getVersion()).isEqualTo(1);
        assertThat(unchanged.getSnapshot()).isEqualTo(originalSnapshot);
        assertThat(unchanged.getCreatedAt()).isEqualTo(original.getCreatedAt());
        assertThat(unchanged.getUpdatedAt()).isEqualTo(original.getUpdatedAt());
        assertThat(versions.count()).isEqualTo(3);
    }

    @ParameterizedTest @ValueSource(strings = {"PATCH", "get", " GET", "GET ", ""})
    void unsupportedMethodsRetainStructuralEnumResponse(String method) throws Exception {
        GsuifPage page = page(project("Methods"), "Home", null);
        String response = mvc.perform(post("/api/v1/pages/{id}/metadata", page.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schemaVersion\":\"1.0.0\",\"snapshot\":" + snapshot(0, 0, method) + "}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(assertEnvelope(response).get("errors").get("$.snapshot.apiBindings[0].httpMethod").asText()).contains("enum");
        assertThat(versions.count()).isZero();
    }

    @Test void routeConflictIsNamedAndScopeNullAndSelfUpdateArePreserved() throws Exception {
        GsuifProject a = project("A"), b = project("B");
        GsuifPage occupied = page(a, "Occupied", "/same");
        GsuifPage target = page(a, "Target", "/target");
        long before = pages.count();
        String response = mvc.perform(post("/api/v1/projects/{id}/pages", a.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"New\",\"route\":\"/same\"}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(assertEnvelope(response).get("errors").get("route").asText()).contains(ROUTE);
        assertThat(pages.count()).isEqualTo(before);
        String update = mvc.perform(put("/api/v1/projects/{project}/pages/{page}", a.getId(), target.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Target\",\"route\":\"/same\"}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(assertEnvelope(update).get("errors").get("route").asText()).contains(ROUTE);
        assertThat(pages.findById(target.getId()).orElseThrow().getRoute()).isEqualTo("/target");
        mvc.perform(put("/api/v1/projects/{project}/pages/{page}", a.getId(), occupied.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Occupied\",\"route\":\"/same\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/projects/{id}/pages", b.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Other project\",\"route\":\"/same\"}"))
                .andExpect(status().isCreated());
        for (String name : new String[]{"Null one", "Null two"})
            mvc.perform(post("/api/v1/projects/{id}/pages", a.getId()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"" + name + "\",\"route\":null}"))
                    .andExpect(status().isCreated());
    }

    private com.fasterxml.jackson.databind.JsonNode assertEnvelope(String body) throws Exception {
        var json = JSON.readTree(body);
        assertThat(json.size()).isEqualTo(5);
        assertThat(json.has("status") && json.has("clientMessage") && json.has("statusCode")
                && json.has("body") && json.has("errors")).isTrue();
        assertThat(json.get("statusCode").asInt()).isEqualTo(400);
        assertThat(json.get("clientMessage").asText()).isNotBlank();
        assertThat(json.get("body").isNull()).isTrue();
        assertThat(json.get("errors").isObject()).isTrue();
        assertThat(json.get("errors").size()).isPositive();
        return json;
    }
    private GsuifProject project(String name) {
        var p = new GsuifProject(); p.setName(name); return projects.save(p);
    }
    private GsuifPage page(GsuifProject project, String name, String route) {
        var p = new GsuifPage(); p.setProject(project); p.setName(name); p.setRoute(route); return pages.save(p);
    }
    private MetadataVersion prior(GsuifPage page) {
        var v = new MetadataVersion(); v.setPage(page); v.setVersion(1); v.setSchemaVersion("1.0.0");
        v.setSnapshot("{\"components\":[],\"apiBindings\":[]}"); v = versions.save(v);
        page.setCurrentMetadataVersionId(v.getId()); pages.save(page); return v;
    }
}
