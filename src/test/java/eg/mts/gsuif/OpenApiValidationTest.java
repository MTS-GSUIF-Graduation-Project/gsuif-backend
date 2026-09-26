package eg.mts.gsuif;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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
}
