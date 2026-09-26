package eg.mts.gsuif;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
class OpenApiDtoContractTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private jakarta.validation.Validator validator;

    private final ObjectMapper mapper = new ObjectMapper();
    private static final Map<String, Map<String, Integer>> TEXT_FIELDS = Map.of(
            "CreateProjectRequest", Map.of("name", 200),
            "UpdateProjectRequest", Map.of("name", 200),
            "CreatePageRequest", Map.of("name", 200),
            "UpdatePageRequest", Map.of("name", 200),
            "CreateMetadataVersionRequest", Map.of("schemaVersion", 20),
            "CreateWorkOrderRequest", Map.of("orderNumber", 64, "assignedTo", 128),
            "UpdateWorkOrderRequest", Map.of("orderNumber", 64, "assignedTo", 128),
            "LoginRequest", Map.of("username", 0, "password", 0));

    @Test
    void servedSchemasDescribeJsonValuesNullabilityConstraintsAndExamplesInEveryDocument() throws Exception {
        for (String document : List.of("", "/metadata-api", "/reference-api", "/auth-api")) {
            JsonNode root = docs(document);
            assertTrue(root.path("openapi").asText().startsWith("3.1."));
            JsonNode schemas = root.path("components").path("schemas");
            assertFalse(schemas.has("JsonNode"), "Jackson internals must not become the public contract");
            boolean metadata = document.isEmpty() || document.equals("/metadata-api");
            if (metadata) {
                for (String dto : List.of("CreateMetadataVersionRequest", "MetadataVersionDto")) {
                    JsonNode snapshot = schemas.path(dto).path("properties").path("snapshot");
                    assertEquals(Set.of("object", "array", "string", "number", "boolean"), types(snapshot), dto);
                    for (String restriction : List.of("$ref", "properties", "items", "allOf", "oneOf", "anyOf")) {
                        assertFalse(snapshot.has(restriction), dto + " must allow arbitrary nested JSON");
                    }
                    assertFalse(snapshot.path("additionalProperties").isBoolean()
                            && !snapshot.path("additionalProperties").asBoolean());
                }
                assertTrue(strings(schemas.path("CreateMetadataVersionRequest").path("required")).contains("snapshot"));
                for (String dto : List.of("CreateProjectRequest", "UpdateProjectRequest", "ProjectDto",
                        "CreatePageRequest", "UpdatePageRequest", "PageDto")) {
                    String field = dto.contains("Project") ? "description" : "route";
                    JsonNode schema = schemas.path(dto);
                    assertEquals(Set.of("string", "null"), types(schema.path("properties").path(field)), dto);
                    if (dto.endsWith("Request")) {
                        assertFalse(strings(schema.path("required")).contains(field));
                        if (field.equals("route")) assertEquals(255, schema.path("properties").path(field).path("maxLength").asInt());
                    }
                }
                assertTrue(schemas.path("UpdatePageRequest").path("properties").path("route")
                        .path("description").asText().contains("clears"));
            }
            for (var dto : TEXT_FIELDS.entrySet()) {
                boolean belongs = document.isEmpty()
                        || (document.equals("/auth-api") && dto.getKey().equals("LoginRequest"))
                        || (document.equals("/reference-api") && dto.getKey().contains("WorkOrder"))
                        || (document.equals("/metadata-api") && !dto.getKey().contains("WorkOrder") && !dto.getKey().equals("LoginRequest"));
                if (!belongs) continue;
                JsonNode schema = schemas.path(dto.getKey());
                assertTrue(schema.isObject(), dto.getKey() + " missing in " + document);
                for (var field : dto.getValue().entrySet()) {
                    JsonNode property = schema.path("properties").path(field.getKey());
                    assertTrue(strings(schema.path("required")).contains(field.getKey()));
                    assertEquals(Set.of("string"), types(property));
                    assertEquals(1, property.path("minLength").asInt(), dto.getKey() + "." + field.getKey());
                    if (field.getValue() > 0) assertEquals(field.getValue(), property.path("maxLength").asInt());
                    assertTrue(property.path("description").asText().contains("non-whitespace"));
                    assertTrue(property.path("example").isTextual());
                    String example = property.path("example").asText();
                    assertFalse(example.isBlank());
                    if (field.getValue() > 0) assertTrue(example.length() <= field.getValue());
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"nested\":[null,1,true]}", "[1,\"a\",null]", "\"text\"", "42", "1.25", "true", "false",
            "{}", "[]", "\"\"", "-2"})
    void snapshotsRoundTripAsJsonValues(String json) throws Exception {
        String pageId = fixtures().pageId();
        ObjectNode payload = mapper.createObjectNode().put("schemaVersion", "1.0");
        payload.set("snapshot", mapper.readTree(json));
        JsonNode created = send("POST", "/api/v1/pages/" + pageId + "/metadata", payload, 201);
        assertEquals(mapper.readTree(json), created.at("/body/snapshot"));
        JsonNode fetched = read("/api/v1/pages/" + pageId + "/metadata/" + created.at("/body/id").asText());
        assertEquals(mapper.readTree(json), fetched.at("/body/snapshot"));
    }

    @Test
    void missingAndTopLevelNullSnapshotsAreRejected() throws Exception {
        String path = "/api/v1/pages/" + fixtures().pageId() + "/metadata";
        ObjectNode payload = mapper.createObjectNode().put("schemaVersion", "1.0");
        assertFieldError(send("POST", path, payload, 400), "snapshot");
        payload.putNull("snapshot");
        assertFieldError(send("POST", path, payload, 400), "snapshot");
    }

    @Test
    void nullableFieldsAreAcceptedOnCreateAndClearedOnUpdate() throws Exception {
        ObjectNode project = mapper.createObjectNode().put("name", "Nullable project").putNull("description");
        JsonNode created = send("POST", "/api/v1/projects", project, 201);
        assertTrue(created.at("/body/description").isNull());
        String projectPath = "/api/v1/projects/" + created.at("/body/id").asText();
        project.put("description", "A description");
        assertEquals("A description", send("PUT", projectPath, project, 200).at("/body/description").asText());
        project.putNull("description");
        assertTrue(send("PUT", projectPath, project, 200).at("/body/description").isNull());
        assertTrue(read(projectPath).at("/body/description").isNull());

        ObjectNode page = mapper.createObjectNode().put("name", "Nullable route").putNull("route");
        JsonNode createdPage = send("POST", projectPath + "/pages", page, 201);
        assertTrue(createdPage.at("/body/route").isNull());
        String pagePath = projectPath + "/pages/" + createdPage.at("/body/id").asText();
        for (String clearMode : List.of("null", "blank", "omitted")) {
            page.put("route", "/orders");
            assertEquals("/orders", send("PUT", pagePath, page, 200).at("/body/route").asText());
            if (clearMode.equals("null")) page.putNull("route");
            else if (clearMode.equals("blank")) page.put("route", " \t ");
            else page.remove("route");
            assertTrue(send("PUT", pagePath, page, 200).at("/body/route").isNull());
            assertTrue(read(pagePath).at("/body/route").isNull());
        }
    }

    @Test
    void documentedExamplesSucceedAndRequiredTextRejectsEmptyWhitespaceAndExcessLength() throws Exception {
        JsonNode schemas = docs("").path("components").path("schemas");
        assertTrue(validator.validate(mapper.treeToValue(example(schemas, "LoginRequest"),
                eg.mts.gsuif.dto.LoginRequest.class)).isEmpty(), "Login examples must satisfy actual DTO validation");
        Fixtures f = fixtures();
        JsonNode exampleProject = send("POST", "/api/v1/projects", example(schemas, "CreateProjectRequest"), 201);
        String exampleProjectId = exampleProject.at("/body/id").asText();
        JsonNode examplePage = send("POST", "/api/v1/projects/" + exampleProjectId + "/pages", example(schemas, "CreatePageRequest"), 201);
        String examplePageId = examplePage.at("/body/id").asText();
        JsonNode workOrder = send("POST", "/api/v1/work-orders", example(schemas, "CreateWorkOrderRequest"), 201);
        record Endpoint(String dto, String method, String path, int success) {}
        List<Endpoint> endpoints = List.of(
                new Endpoint("CreateProjectRequest", "POST", "/api/v1/projects", 201),
                new Endpoint("UpdateProjectRequest", "PUT", "/api/v1/projects/" + exampleProjectId, 200),
                new Endpoint("CreatePageRequest", "POST", "/api/v1/projects/" + f.projectId() + "/pages", 201),
                new Endpoint("UpdatePageRequest", "PUT", "/api/v1/projects/" + exampleProjectId + "/pages/" + examplePageId, 200),
                new Endpoint("CreateMetadataVersionRequest", "POST", "/api/v1/pages/" + f.pageId() + "/metadata", 201),
                new Endpoint("CreateWorkOrderRequest", "POST", "/api/v1/work-orders", 201),
                new Endpoint("UpdateWorkOrderRequest", "PUT", "/api/v1/work-orders/" + workOrder.at("/body/id").asText(), 200),
                new Endpoint("LoginRequest", "POST", "/api/auth/login", 401));
        for (Endpoint endpoint : endpoints) {
            ObjectNode valid = example(schemas, endpoint.dto());
            // Login examples illustrate valid syntax, not credentials for a deployed account.
            if (!endpoint.dto().equals("LoginRequest") && !endpoint.dto().equals("CreateWorkOrderRequest")
                    && !endpoint.dto().equals("CreateProjectRequest")) {
                send(endpoint.method(), endpoint.path(), valid, endpoint.success());
            }
            for (var field : TEXT_FIELDS.get(endpoint.dto()).entrySet()) {
                for (String invalid : List.of("", " \t\n", "\u2003")) {
                    ObjectNode payload = valid.deepCopy().put(field.getKey(), invalid);
                    assertFieldError(send(endpoint.method(), endpoint.path(), payload, 400), field.getKey());
                }
                ObjectNode nullField = valid.deepCopy().putNull(field.getKey());
                assertFieldError(send(endpoint.method(), endpoint.path(), nullField, 400), field.getKey());
                ObjectNode missingField = valid.deepCopy();
                missingField.remove(field.getKey());
                assertFieldError(send(endpoint.method(), endpoint.path(), missingField, 400), field.getKey());
                if (field.getValue() > 0) {
                    ObjectNode payload = valid.deepCopy().put(field.getKey(), "x".repeat(field.getValue() + 1));
                    assertFieldError(send(endpoint.method(), endpoint.path(), payload, 400), field.getKey());
                }
            }
        }
    }

    private ObjectNode example(JsonNode schemas, String dto) {
        ObjectNode payload = mapper.createObjectNode();
        schemas.path(dto).path("properties").fields().forEachRemaining(field -> {
            assertTrue(field.getValue().has("example"), dto + "." + field.getKey() + " needs an example");
            payload.set(field.getKey(), field.getValue().get("example"));
        });
        return payload;
    }

    private record Fixtures(String projectId, String pageId) {}

    private Fixtures fixtures() throws Exception {
        String projectId = send("POST", "/api/v1/projects", mapper.createObjectNode().put("name", "Contract test " + UUID.randomUUID()), 201)
                .at("/body/id").asText();
        String pageId = send("POST", "/api/v1/projects/" + projectId + "/pages", mapper.createObjectNode().put("name", "Contract page"), 201)
                .at("/body/id").asText();
        return new Fixtures(projectId, pageId);
    }

    private JsonNode docs(String suffix) throws Exception {
        return read("/v3/api-docs" + suffix);
    }

    private JsonNode read(String path) throws Exception {
        return mapper.readTree(mockMvc.perform(get(path)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode send(String method, String path, JsonNode payload, int expectedStatus) throws Exception {
        JsonNode result = mapper.readTree(mockMvc.perform(request(HttpMethod.valueOf(method), path)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(payload)))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString());
        assertEquals(Set.of("status", "clientMessage", "statusCode", "body", "errors"), fieldNames(result));
        assertEquals(expectedStatus, result.path("statusCode").asInt());
        if (expectedStatus < 300) assertTrue(result.path("errors").isNull());
        return result;
    }

    private void assertFieldError(JsonNode result, String field) {
        assertTrue(result.path("body").isNull());
        assertTrue(result.path("errors").has(field), "Expected validation error for " + field + ": " + result);
    }

    private Set<String> types(JsonNode schema) {
        JsonNode type = schema.path("type");
        return type.isTextual() ? Set.of(type.asText()) : strings(type);
    }

    private Set<String> strings(JsonNode array) {
        Set<String> result = new HashSet<>();
        array.forEach(value -> result.add(value.asText()));
        return result;
    }

    private Set<String> fieldNames(JsonNode object) {
        Set<String> result = new HashSet<>();
        object.fieldNames().forEachRemaining(result::add);
        return result;
    }
}
