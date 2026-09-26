package eg.mts.gsuif.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eg.mts.gsuif.dto.LoginRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class OpenApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void whenTestProfile_thenSwaggerDocsAvailableAndCorrectlySecured() throws Exception {
        // Base API docs
        String baseDocsJson = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode baseDocs = objectMapper.readTree(baseDocsJson);

        // Check global security components
        JsonNode securitySchemes = baseDocs.at("/components/securitySchemes/bearerAuth");
        assertFalse(securitySchemes.isMissingNode(), "bearerAuth scheme is missing");
        assertEquals("http", securitySchemes.get("type").asText());
        assertEquals("bearer", securitySchemes.get("scheme").asText());
        assertEquals("JWT", securitySchemes.get("bearerFormat").asText());

        JsonNode rootSecurity = baseDocs.at("/security");
        boolean hasGlobalSecurity = rootSecurity.isArray() && rootSecurity.get(0).has("bearerAuth");
        assertTrue(hasGlobalSecurity, "Global security bearerAuth is missing from root");

        // Metadata API Group
        String metadataApiJson = mockMvc.perform(get("/v3/api-docs/metadata-api"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode metadataDocs = objectMapper.readTree(metadataApiJson);

        Set<String> expectedMetadataOps = Set.of(
            "GET /api/v1/projects", "POST /api/v1/projects",
            "GET /api/v1/projects/{id}", "PUT /api/v1/projects/{id}", "DELETE /api/v1/projects/{id}",
            "GET /api/v1/projects/{projectId}/pages", "POST /api/v1/projects/{projectId}/pages",
            "GET /api/v1/projects/{projectId}/pages/{pageId}", "PUT /api/v1/projects/{projectId}/pages/{pageId}", "DELETE /api/v1/projects/{projectId}/pages/{pageId}",
            "GET /api/v1/pages/{pageId}/metadata", "POST /api/v1/pages/{pageId}/metadata",
            "GET /api/v1/pages/{pageId}/metadata/latest",
            "GET /api/v1/pages/{pageId}/metadata/{versionId}"
        );
        assertEquals(expectedMetadataOps, getOperations(metadataDocs), "Metadata operations mismatch");
        assertAllOperationsProtected(metadataDocs);

        // Reference API Group
        String referenceApiJson = mockMvc.perform(get("/v3/api-docs/reference-api"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode referenceDocs = objectMapper.readTree(referenceApiJson);

        Set<String> expectedReferenceOps = Set.of(
            "GET /api/v1/work-orders", "POST /api/v1/work-orders",
            "GET /api/v1/work-orders/{id}", "PUT /api/v1/work-orders/{id}", "DELETE /api/v1/work-orders/{id}"
        );
        assertEquals(expectedReferenceOps, getOperations(referenceDocs), "Reference operations mismatch");
        assertAllOperationsProtected(referenceDocs);

        // Auth API Group
        String authApiJson = mockMvc.perform(get("/v3/api-docs/auth-api"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode authDocs = objectMapper.readTree(authApiJson);

        Set<String> expectedAuthOps = Set.of("POST /api/auth/login");
        assertEquals(expectedAuthOps, getOperations(authDocs), "Auth operations mismatch");

        // Verify login operation is public
        JsonNode loginPost = authDocs.at("/paths/~1api~1auth~1login/post");
        JsonNode loginSecurity = loginPost.at("/security");
        assertTrue(loginSecurity.isArray() && loginSecurity.isEmpty(), "Login should have empty security array overriding global");
    }

    private Set<String> getOperations(JsonNode docs) {
        Set<String> ops = new HashSet<>();
        JsonNode paths = docs.path("paths");
        Iterator<Map.Entry<String, JsonNode>> fields = paths.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = fields.next();
            String path = pathEntry.getKey();
            Iterator<String> methods = pathEntry.getValue().fieldNames();
            while (methods.hasNext()) {
                ops.add(methods.next().toUpperCase() + " " + path);
            }
        }
        return ops;
    }

    private void assertAllOperationsProtected(JsonNode docs) {
        JsonNode rootSecurity = docs.at("/security");

        JsonNode paths = docs.path("paths");
        Iterator<Map.Entry<String, JsonNode>> fields = paths.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = fields.next();
            String path = pathEntry.getKey();
            Iterator<Map.Entry<String, JsonNode>> ops = pathEntry.getValue().fields();
            while (ops.hasNext()) {
                Map.Entry<String, JsonNode> opEntry = ops.next();
                JsonNode opNode = opEntry.getValue();
                JsonNode opSecurity = opNode.at("/security");

                JsonNode effectiveSecurity = !opSecurity.isMissingNode() ? opSecurity : rootSecurity;

                assertFalse(effectiveSecurity.isMissingNode() || effectiveSecurity.isEmpty(),
                        "Operation " + opEntry.getKey() + " " + path + " has empty or missing security array (anonymous)");

                // Every alternative must require bearerAuth
                for (JsonNode secObj : effectiveSecurity) {
                    assertTrue(secObj.has("bearerAuth"),
                            "Operation " + opEntry.getKey() + " " + path + " has a security alternative missing bearerAuth");
                }
            }
        }
    }

    @Test
    void testRealLoginFlow() throws Exception {
        // 1. Unauthenticated request is rejected (401)
        mockMvc.perform(get("/api/v1/projects"))
                .andExpect(status().isUnauthorized());

        // 2. Perform real login with test seeded user
        LoginRequest loginRequest = new LoginRequest("admin", "password123");
        String responseContent = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode responseNode = objectMapper.readTree(responseContent);
        String token = responseNode.at("/body/token").asText();
        assertNotNull(token);
        assertFalse(token.isEmpty());

        // 3. Authenticated request succeeds
        mockMvc.perform(get("/api/v1/projects")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
