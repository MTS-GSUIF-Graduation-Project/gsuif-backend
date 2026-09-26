package eg.mts.gsuif;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.HashMap;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.security.test.context.support.WithMockUser;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class OpenApiValidationTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void testOpenApiResponsesAndSchemas() throws Exception {
        // Test Auth API
        String authDocs = mockMvc.perform(get("/v3/api-docs/auth-api"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode authTree = objectMapper.readTree(authDocs);
        JsonNode loginPost = authTree.at("/paths/~1api~1auth~1login/post/responses");

        assertTrue(loginPost.has("200"));
        assertTrue(loginPost.has("400"));
        assertTrue(loginPost.has("401"));
        assertTrue(loginPost.has("415"));
        assertTrue(loginPost.has("500"));
        assertFalse(loginPost.has("404"));

        // Verify ErrorApiResponse schema definition exists
        JsonNode errorSchemaDef = authTree.at("/components/schemas/ErrorApiResponse");
        assertNotNull(errorSchemaDef);
        assertEquals("null", errorSchemaDef.at("/properties/body/type").asText());

        JsonNode errorsType = errorSchemaDef.at("/properties/errors/type");
        boolean hasNullType = false;
        if (errorsType.isArray()) {
            for (JsonNode typeNode : errorsType) {
                if ("null".equals(typeNode.asText())) hasNullType = true;
            }
        }
        assertTrue(hasNullType || errorSchemaDef.at("/properties/errors/nullable").asBoolean());

        // Test Metadata API (Projects)
        String metaDocs = mockMvc.perform(get("/v3/api-docs/metadata-api"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode metaTree = objectMapper.readTree(metaDocs);

        JsonNode projectPost = metaTree.at("/paths/~1api~1v1~1projects/post/responses");
        assertTrue(projectPost.has("201"));
        assertTrue(projectPost.has("400"));
        assertTrue(projectPost.has("401"));
        assertTrue(projectPost.has("415"));
        assertTrue(projectPost.has("500"));
        assertFalse(projectPost.has("404"));

        JsonNode projectGet = metaTree.at("/paths/~1api~1v1~1projects~1{id}/get/responses");
        assertTrue(projectGet.has("200"));
        assertTrue(projectGet.has("400"));
        assertTrue(projectGet.has("401"));
        assertTrue(projectGet.has("404"));
        assertTrue(projectGet.has("500"));
        assertFalse(projectGet.has("415"));

        // Verify the 404 response references ErrorApiResponse
        JsonNode notFoundSchemaRef = projectGet.at("/404/content/*~1*/schema/$ref");
        assertEquals("#/components/schemas/ErrorApiResponse", notFoundSchemaRef.asText());

        // Test Reference API (Work Orders)
        String refDocs = mockMvc.perform(get("/v3/api-docs/reference-api"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode refTree = objectMapper.readTree(refDocs);
        JsonNode woGet = refTree.at("/paths/~1api~1v1~1work-orders~1{id}/get/responses");
        assertTrue(woGet.has("200"));
        assertTrue(woGet.has("404"));
        assertTrue(woGet.has("401"));
        // Fetch base API docs
        String baseDocs = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode baseTree = objectMapper.readTree(baseDocs);

        // Verify that 405 error envelope and API-level description are documented in all groups and the base document
        for (JsonNode tree : new JsonNode[]{baseTree, authTree, metaTree, refTree}) {
            String description = tree.at("/info/description").asText();
            assertTrue(description.contains("Requests using an unsupported HTTP method return 405 with the standard error envelope."),
                    "API description must mention 405 unsupported method behavior");

            assertTrue(tree.at("/components/responses/Error405").isObject(), "Error405 response must be documented in components");
            JsonNode ref405 = tree.at("/components/responses/Error405/content/application~1json/schema/$ref");
            if (ref405.isMissingNode()) {
                ref405 = tree.at("/components/responses/Error405/content/*~1*/schema/$ref");
            }
            assertEquals("#/components/schemas/ErrorApiResponse", ref405.asText(), "405 error envelope should reference ErrorApiResponse");
        }
    }

    @Test
    public void testSuccessfulLoginResponse() throws Exception {
        eg.mts.gsuif.dto.LoginRequest loginRequest = new eg.mts.gsuif.dto.LoginRequest("admin", "password123");
        String responseContent = mockMvc.perform(post("/api/auth/login")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode responseNode = objectMapper.readTree(responseContent);
        assertEquals("OK", responseNode.get("status").asText());
        assertEquals(200, responseNode.get("statusCode").asInt());
        assertTrue(responseNode.has("body"), "body field must exist");
        assertNotNull(responseNode.get("body").get("token"), "token must exist in success body");

        assertTrue(responseNode.has("errors") && responseNode.get("errors").isNull(),
                "errors should be explicitly null for a successful response");
    }

    @Test
    @WithMockUser
    public void testAuthenticatedProjectListResponse() throws Exception {
        String responseContent = mockMvc.perform(get("/api/v1/projects"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode responseNode = objectMapper.readTree(responseContent);
        assertEquals("OK", responseNode.get("status").asText());
        assertEquals(200, responseNode.get("statusCode").asInt());
        assertTrue(responseNode.has("body"), "body field must exist");
        assertTrue(responseNode.get("body").has("data"), "paginated data must exist");

        assertTrue(responseNode.has("errors") && responseNode.get("errors").isNull(),
                "errors should be explicitly null for a successful response");
    }

    @Test
    @WithMockUser
    public void testMethodNotAllowedRuntime() throws Exception {
        // Send a PATCH request to /api/v1/projects which does not support PATCH
        mockMvc.perform(patch("/api/v1/projects"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.statusCode").value(405))
                .andExpect(jsonPath("$.status").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.clientMessage").value("HTTP method not supported"));
    }

    @Test
    public void testOperationIdsAndLinks() throws Exception {
        Map<String, String> baseExpected = new HashMap<>();
        baseExpected.put("post /api/auth/login", "login");
        baseExpected.put("post /api/v1/projects", "createProject");
        baseExpected.put("get /api/v1/projects/{id}", "getProjectById");
        baseExpected.put("get /api/v1/projects", "getProjects");
        baseExpected.put("put /api/v1/projects/{id}", "updateProject");
        baseExpected.put("delete /api/v1/projects/{id}", "deleteProject");
        baseExpected.put("post /api/v1/projects/{projectId}/pages", "createPage");
        baseExpected.put("get /api/v1/projects/{projectId}/pages", "getPagesByProject");
        baseExpected.put("get /api/v1/projects/{projectId}/pages/{pageId}", "getPageById");
        baseExpected.put("put /api/v1/projects/{projectId}/pages/{pageId}", "updatePage");
        baseExpected.put("delete /api/v1/projects/{projectId}/pages/{pageId}", "deletePage");
        baseExpected.put("post /api/v1/pages/{pageId}/metadata", "createMetadataVersion");
        baseExpected.put("get /api/v1/pages/{pageId}/metadata", "getMetadataVersionsByPage");
        baseExpected.put("get /api/v1/pages/{pageId}/metadata/latest", "getLatestMetadataVersion");
        baseExpected.put("get /api/v1/pages/{pageId}/metadata/{versionId}", "getMetadataVersionById");
        baseExpected.put("post /api/v1/work-orders", "createWorkOrder");
        baseExpected.put("get /api/v1/work-orders/{id}", "getWorkOrderById");
        baseExpected.put("get /api/v1/work-orders", "getWorkOrders");
        baseExpected.put("put /api/v1/work-orders/{id}", "updateWorkOrder");
        baseExpected.put("delete /api/v1/work-orders/{id}", "deleteWorkOrder");

        Map<String, String> authExpected = new HashMap<>();
        authExpected.put("post /api/auth/login", "login");

        Map<String, String> refExpected = new HashMap<>();
        baseExpected.entrySet().stream().filter(e -> e.getKey().contains("/work-orders")).forEach(e -> refExpected.put(e.getKey(), e.getValue()));

        Map<String, String> metaExpected = new HashMap<>();
        baseExpected.entrySet().stream().filter(e -> e.getKey().contains("/projects") || e.getKey().contains("/pages")).forEach(e -> metaExpected.put(e.getKey(), e.getValue()));

        Map<String, Map<String, String>> groupExpectations = new HashMap<>();
        groupExpectations.put("/v3/api-docs", baseExpected);
        groupExpectations.put("/v3/api-docs/metadata-api", metaExpected);
        groupExpectations.put("/v3/api-docs/auth-api", authExpected);
        groupExpectations.put("/v3/api-docs/reference-api", refExpected);

        for (Map.Entry<String, Map<String, String>> entry : groupExpectations.entrySet()) {
            String url = entry.getKey();
            Map<String, String> expectedMap = entry.getValue();

            String json = mockMvc.perform(get(url)).andReturn().getResponse().getContentAsString();
            JsonNode root = new ObjectMapper().readTree(json);
            JsonNode paths = root.path("paths");

            Map<String, String> actualMap = new HashMap<>();
            if (!paths.isMissingNode() && !paths.isEmpty()) {
                paths.fields().forEachRemaining(path -> {
                    path.getValue().fields().forEachRemaining(method -> {
                        JsonNode op = method.getValue();
                        assertTrue(op.has("operationId"), "operationId missing for " + method.getKey() + " " + path.getKey() + " in " + url);
                        actualMap.put(method.getKey() + " " + path.getKey(), op.get("operationId").asText());
                    });
                });
            }
            assertEquals(expectedMap, actualMap, "Mapping mismatch in " + url);

            if (url.equals("/v3/api-docs/auth-api")) {
                if (!paths.isMissingNode() && !paths.isEmpty()) {
                    paths.fields().forEachRemaining(path -> {
                        path.getValue().fields().forEachRemaining(method -> {
                            JsonNode responses = method.getValue().path("responses");
                            if (!responses.isMissingNode()) {
                                responses.fields().forEachRemaining(resp -> {
                                    assertTrue(resp.getValue().path("links").isMissingNode() || resp.getValue().path("links").isEmpty(), "Auth should have no links");
                                });
                            }
                        });
                    });
                }
            } else if (url.equals("/v3/api-docs") || url.equals("/v3/api-docs/metadata-api") || url.equals("/v3/api-docs/reference-api")) {
                validateExactLinks(url, paths);
            }
        }
    }

    private void validateExactLinks(String url, JsonNode paths) {
        Map<String, JsonNode> actualLinks = new HashMap<>();
        if (!paths.isMissingNode() && !paths.isEmpty()) {
            paths.fields().forEachRemaining(path -> {
                path.getValue().fields().forEachRemaining(method -> {
                    JsonNode responses = method.getValue().path("responses");
                    if (!responses.isMissingNode()) {
                        responses.fields().forEachRemaining(resp -> {
                            JsonNode links = resp.getValue().path("links");
                            if (!links.isMissingNode() && !links.isEmpty()) {
                                links.fields().forEachRemaining(link -> {
                                    String key = method.getKey() + " " + path.getKey() + " " + resp.getKey() + " " + link.getKey();
                                    actualLinks.put(key, link.getValue());
                                });
                            }
                        });
                    }
                });
            });
        }

        Map<String, Map<String, Object>> expectedLinks = new HashMap<>();

        // Projects
        addExpectedLink(expectedLinks, "post /api/v1/projects 201 GetProjectById", "getProjectById", Map.of("id", "$response.body#/body/id"));
        addExpectedLink(expectedLinks, "post /api/v1/projects 201 UpdateProject", "updateProject", Map.of("id", "$response.body#/body/id"));
        addExpectedLink(expectedLinks, "post /api/v1/projects 201 DeleteProject", "deleteProject", Map.of("id", "$response.body#/body/id"));
        addExpectedLink(expectedLinks, "post /api/v1/projects 201 GetPagesByProject", "getPagesByProject", Map.of("projectId", "$response.body#/body/id"));
        addExpectedLink(expectedLinks, "post /api/v1/projects 201 CreatePage", "createPage", Map.of("projectId", "$response.body#/body/id"));

        addExpectedLink(expectedLinks, "get /api/v1/projects/{id} 200 GetProjectById", "getProjectById", Map.of("id", "$response.body#/body/id"));
        addExpectedLink(expectedLinks, "get /api/v1/projects/{id} 200 UpdateProject", "updateProject", Map.of("id", "$response.body#/body/id"));
        addExpectedLink(expectedLinks, "get /api/v1/projects/{id} 200 DeleteProject", "deleteProject", Map.of("id", "$response.body#/body/id"));
        addExpectedLink(expectedLinks, "get /api/v1/projects/{id} 200 GetPagesByProject", "getPagesByProject", Map.of("projectId", "$response.body#/body/id"));
        addExpectedLink(expectedLinks, "get /api/v1/projects/{id} 200 CreatePage", "createPage", Map.of("projectId", "$response.body#/body/id"));

        addExpectedLink(expectedLinks, "put /api/v1/projects/{id} 200 GetProjectById", "getProjectById", Map.of("id", "$response.body#/body/id"));
        addExpectedLink(expectedLinks, "put /api/v1/projects/{id} 200 UpdateProject", "updateProject", Map.of("id", "$response.body#/body/id"));
        addExpectedLink(expectedLinks, "put /api/v1/projects/{id} 200 DeleteProject", "deleteProject", Map.of("id", "$response.body#/body/id"));
        addExpectedLink(expectedLinks, "put /api/v1/projects/{id} 200 GetPagesByProject", "getPagesByProject", Map.of("projectId", "$response.body#/body/id"));
        addExpectedLink(expectedLinks, "put /api/v1/projects/{id} 200 CreatePage", "createPage", Map.of("projectId", "$response.body#/body/id"));

        // Pages
        addExpectedLink(expectedLinks, "post /api/v1/projects/{projectId}/pages 201 GetPageById", "getPageById", Map.of("projectId", "$response.body#/body/projectId", "pageId", "$response.body#/body/id"));
        addExpectedLink(expectedLinks, "post /api/v1/projects/{projectId}/pages 201 UpdatePage", "updatePage", Map.of("projectId", "$response.body#/body/projectId", "pageId", "$response.body#/body/id"));
        addExpectedLink(expectedLinks, "post /api/v1/projects/{projectId}/pages 201 DeletePage", "deletePage", Map.of("projectId", "$response.body#/body/projectId", "pageId", "$response.body#/body/id"));
        addExpectedLink(expectedLinks, "post /api/v1/projects/{projectId}/pages 201 GetMetadataVersionsByPage", "getMetadataVersionsByPage", Map.of("pageId", "$response.body#/body/id"));
        addExpectedLink(expectedLinks, "post /api/v1/projects/{projectId}/pages 201 CreateMetadataVersion", "createMetadataVersion", Map.of("pageId", "$response.body#/body/id"));

        addExpectedLink(expectedLinks, "get /api/v1/projects/{projectId}/pages/{pageId} 200 GetPageById", "getPageById", Map.of("projectId", "$response.body#/body/projectId", "pageId", "$response.body#/body/id"));
        addExpectedLink(expectedLinks, "get /api/v1/projects/{projectId}/pages/{pageId} 200 UpdatePage", "updatePage", Map.of("projectId", "$response.body#/body/projectId", "pageId", "$response.body#/body/id"));
        addExpectedLink(expectedLinks, "get /api/v1/projects/{projectId}/pages/{pageId} 200 DeletePage", "deletePage", Map.of("projectId", "$response.body#/body/projectId", "pageId", "$response.body#/body/id"));
        addExpectedLink(expectedLinks, "get /api/v1/projects/{projectId}/pages/{pageId} 200 GetMetadataVersionsByPage", "getMetadataVersionsByPage", Map.of("pageId", "$response.body#/body/id"));
        addExpectedLink(expectedLinks, "get /api/v1/projects/{projectId}/pages/{pageId} 200 CreateMetadataVersion", "createMetadataVersion", Map.of("pageId", "$response.body#/body/id"));

        addExpectedLink(expectedLinks, "put /api/v1/projects/{projectId}/pages/{pageId} 200 GetPageById", "getPageById", Map.of("projectId", "$response.body#/body/projectId", "pageId", "$response.body#/body/id"));
        addExpectedLink(expectedLinks, "put /api/v1/projects/{projectId}/pages/{pageId} 200 UpdatePage", "updatePage", Map.of("projectId", "$response.body#/body/projectId", "pageId", "$response.body#/body/id"));
        addExpectedLink(expectedLinks, "put /api/v1/projects/{projectId}/pages/{pageId} 200 DeletePage", "deletePage", Map.of("projectId", "$response.body#/body/projectId", "pageId", "$response.body#/body/id"));
        addExpectedLink(expectedLinks, "put /api/v1/projects/{projectId}/pages/{pageId} 200 GetMetadataVersionsByPage", "getMetadataVersionsByPage", Map.of("pageId", "$response.body#/body/id"));
        addExpectedLink(expectedLinks, "put /api/v1/projects/{projectId}/pages/{pageId} 200 CreateMetadataVersion", "createMetadataVersion", Map.of("pageId", "$response.body#/body/id"));

        // Metadata
        addExpectedLink(expectedLinks, "post /api/v1/pages/{pageId}/metadata 201 GetMetadataVersionById", "getMetadataVersionById", Map.of("pageId", "$response.body#/body/pageId", "versionId", "$response.body#/body/id"));
        addExpectedLink(expectedLinks, "post /api/v1/pages/{pageId}/metadata 201 GetLatestMetadataVersion", "getLatestMetadataVersion", Map.of("pageId", "$response.body#/body/pageId"));

        addExpectedLink(expectedLinks, "get /api/v1/pages/{pageId}/metadata/latest 200 GetMetadataVersionById", "getMetadataVersionById", Map.of("pageId", "$response.body#/body/pageId", "versionId", "$response.body#/body/id"));
        addExpectedLink(expectedLinks, "get /api/v1/pages/{pageId}/metadata/latest 200 GetLatestMetadataVersion", "getLatestMetadataVersion", Map.of("pageId", "$response.body#/body/pageId"));

        addExpectedLink(expectedLinks, "get /api/v1/pages/{pageId}/metadata/{versionId} 200 GetMetadataVersionById", "getMetadataVersionById", Map.of("pageId", "$response.body#/body/pageId", "versionId", "$response.body#/body/id"));
        addExpectedLink(expectedLinks, "get /api/v1/pages/{pageId}/metadata/{versionId} 200 GetLatestMetadataVersion", "getLatestMetadataVersion", Map.of("pageId", "$response.body#/body/pageId"));

        // Work Orders
        addExpectedLink(expectedLinks, "post /api/v1/work-orders 201 GetWorkOrderById", "getWorkOrderById", Map.of("id", "$response.body#/body/id"));
        addExpectedLink(expectedLinks, "post /api/v1/work-orders 201 UpdateWorkOrder", "updateWorkOrder", Map.of("id", "$response.body#/body/id"));
        addExpectedLink(expectedLinks, "post /api/v1/work-orders 201 DeleteWorkOrder", "deleteWorkOrder", Map.of("id", "$response.body#/body/id"));

        addExpectedLink(expectedLinks, "get /api/v1/work-orders/{id} 200 GetWorkOrderById", "getWorkOrderById", Map.of("id", "$response.body#/body/id"));
        addExpectedLink(expectedLinks, "get /api/v1/work-orders/{id} 200 UpdateWorkOrder", "updateWorkOrder", Map.of("id", "$response.body#/body/id"));
        addExpectedLink(expectedLinks, "get /api/v1/work-orders/{id} 200 DeleteWorkOrder", "deleteWorkOrder", Map.of("id", "$response.body#/body/id"));

        addExpectedLink(expectedLinks, "put /api/v1/work-orders/{id} 200 GetWorkOrderById", "getWorkOrderById", Map.of("id", "$response.body#/body/id"));
        addExpectedLink(expectedLinks, "put /api/v1/work-orders/{id} 200 UpdateWorkOrder", "updateWorkOrder", Map.of("id", "$response.body#/body/id"));
        addExpectedLink(expectedLinks, "put /api/v1/work-orders/{id} 200 DeleteWorkOrder", "deleteWorkOrder", Map.of("id", "$response.body#/body/id"));

        Map<String, Map<String, Object>> filteredExpectedLinks = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : expectedLinks.entrySet()) {
            if (url.equals("/v3/api-docs/metadata-api") && e.getKey().contains("work-order")) continue;
            if (url.equals("/v3/api-docs/reference-api") && !e.getKey().contains("work-order")) continue;
            filteredExpectedLinks.put(e.getKey(), e.getValue());
        }

        assertEquals(filteredExpectedLinks.keySet(), actualLinks.keySet(), "Link keys mismatch in " + url);

        for (Map.Entry<String, JsonNode> entry : actualLinks.entrySet()) {
            String key = entry.getKey();
            JsonNode actualLink = entry.getValue();
            Map<String, Object> exp = filteredExpectedLinks.get(key);

            assertEquals(exp.get("operationId"), actualLink.path("operationId").asText(), "Target op mismatch for " + key);

            @SuppressWarnings("unchecked")
            Map<String, String> expParams = (Map<String, String>) exp.get("parameters");
            JsonNode actualParams = actualLink.path("parameters");

            if (expParams == null || expParams.isEmpty()) {
                assertTrue(actualParams.isMissingNode() || actualParams.isEmpty());
            } else {
                assertEquals(expParams.size(), actualParams.size(), "Param count mismatch for " + key);
                expParams.forEach((k, v) -> {
                    assertEquals(v, actualParams.path(k).asText(), "Param mismatch for " + k + " in " + key);
                });
            }
        }
    }

    private void addExpectedLink(Map<String, Map<String, Object>> map, String key, String opId, Map<String, String> params) {
        Map<String, Object> details = new HashMap<>();
        details.put("operationId", opId);
        details.put("parameters", params);
        map.put(key, details);
    }

    @Test
    @WithMockUser
    public void testLinkExpressionsAgainstRealResponse() throws Exception {
        String createProjJson = mockMvc.perform(post("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Link Proj\", \"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String projId = new ObjectMapper().readTree(createProjJson).path("body").path("id").asText();
        String createPageJson = mockMvc.perform(post("/api/v1/projects/" + projId + "/pages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Link Page\", \"route\":\"/link\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String baseJson = mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString();
        JsonNode paths = new ObjectMapper().readTree(baseJson).path("paths");
        JsonNode link = paths.path("/api/v1/projects/{projectId}/pages").path("post").path("responses").path("201").path("links").path("GetPageById");

        String projParamExp = link.path("parameters").path("projectId").asText();
        String pageParamExp = link.path("parameters").path("pageId").asText();

        JsonNode realRespNode = new ObjectMapper().readTree(createPageJson);
        String evalProjId = realRespNode.at("/" + projParamExp.split("#/")[1]).asText();
        String evalPageId = realRespNode.at("/" + pageParamExp.split("#/")[1]).asText();

        assertEquals(projId, evalProjId);

        mockMvc.perform(get("/api/v1/projects/" + evalProjId + "/pages/" + evalPageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.name").value("Link Page"));
    }
}
