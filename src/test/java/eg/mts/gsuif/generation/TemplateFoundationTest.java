package eg.mts.gsuif.generation;

import freemarker.template.Configuration;
import freemarker.template.TemplateExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.lang.reflect.Method;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.core.annotation.AnnotatedElementUtils;
import java.net.URLClassLoader;
import io.swagger.v3.oas.annotations.Operation;

import static org.junit.jupiter.api.Assertions.*;

/** Narrow SCRUM-44 fixture: explicit inputs, rendered source, and Java 21 compilation. */
class TemplateFoundationTest {
    private static final String ASSET_TICKET_ROUTE = "/api/v1/asset-tickets";
    @TempDir Path temporaryDirectory;

    private final Configuration configuration = new Configuration(Configuration.VERSION_2_3_34);

    TemplateFoundationTest() {
        configuration.setClassForTemplateLoading(TemplateFoundationTest.class, "/");
        configuration.setDefaultEncoding("UTF-8");
        configuration.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        configuration.setLogTemplateExceptions(false);
    }

    @Test
    void catalogSelectedFixturesRenderAndCompile() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("openapiFixtureRequired"),
                "Run with -Popenapi-fixture to generate the interface and DTOs");
        Map<String, Object> project = Map.of("basePackage", "com.example.fixture", "name", "Asset Ticket Fixture");
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("className", "AssetTicket");
        entity.put("tableName", "asset_tickets");
        entity.put("auditBasePackage", "eg.mts.gsuif.entity");
        entity.put("enversAudited", false);
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field("title", "title", "String", false, 1000, null));
        entity.put("fields", fields);

        Map<String, Object> api = new LinkedHashMap<>();
        api.put("dtoPackage", "com.example.fixture.dto");
        api.put("responsePackage", "eg.mts.gsuif.dto");
        api.put("dtoClass", "AssetTicketDto");
        api.put("createRequestClass", "CreateAssetTicketRequest");
        api.put("updateRequestClass", "UpdateAssetTicketRequest");
        api.put("statusEnumClass", "AssetTicketStatus");
        api.put("statusEnumPackage", "com.example.fixture.dto");
        api.put("serviceInterface", "AssetTicketService");
        api.put("servicePackage", "com.example.fixture.service");
        api.put("interfacePackage", "com.example.fixture.api");
        api.put("interfaceClass", "AssetTicketApi");
        api.put("pageDtoClass", "AssetTicketPage");
        api.put("createMethod", "createAssetTicket");
        api.put("getMethod", "getAssetTicketById");
        api.put("listMethod", "listAssetTickets");
        api.put("updateMethod", "updateAssetTicket");
        api.put("deleteMethod", "deleteAssetTicket");

        String entitySource = render(entityPath(), Map.of("project", project, "entity", entity));
        String controllerSource = render(controllerPath(), Map.of("project", project, "entity", entity, "api", api));

        assertTrue(entitySource.contains("public class AssetTicket extends AuditableEntity"));
        assertTrue(entitySource.contains("import eg.mts.gsuif.entity.AuditableEntity;"));
        assertTrue(entitySource.contains("@Table(name = \"asset_tickets\")"));
        assertTrue(entitySource.contains("length = 1000"), "Numeric Java literals must not use locale separators");
        assertFalse(entitySource.contains("length = 1,000"));
        assertFalse(entitySource.contains("import org.hibernate.envers.Audited;"));
        assertTrue(entitySource.contains("return AssetTicket.class.hashCode();"));
        assertTrue(controllerSource.contains("public class AssetTicketController"));
        assertTrue(controllerSource.contains("implements AssetTicketApi"));
        assertFalse(controllerSource.contains("@RequestMapping"), "Mappings belong to the generated interface");
        assertTrue(controllerSource.contains("import eg.mts.gsuif.dto.ApiResponse;"));
        assertTrue(controllerSource.contains("import com.example.fixture.dto.AssetTicketDto;"));
        assertTrue(controllerSource.contains("import com.example.fixture.service.AssetTicketService;"));
        assertTrue(controllerSource.contains("ResponseEntity<ApiResponse<AssetTicketPage>>"));
        assertTrue(controllerSource.contains("HttpStatus.CREATED"));
        assertFalse(controllerSource.contains("Intentionally absent annotations"));
        assertFalse(controllerSource.contains("@PreAuthorize"));
        assertFalse(controllerSource.contains("@Loggable"));

        Path fixtureRoot = Path.of("target/generated-sources/openapi-fixture/src/main/java");
        assertTrue(Files.isDirectory(fixtureRoot), "Generate the consumer fixture with -Popenapi-fixture");
        List<Path> sources = new ArrayList<>();
        try (var files = Files.walk(fixtureRoot)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(sources::add);
        }
        sources.add(write("com/example/fixture/entity/AssetTicket.java", entitySource));
        sources.add(write("com/example/fixture/controller/AssetTicketController.java", controllerSource));
        sources.add(writeFixtureService());
        compile(sources);
        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[]{temporaryDirectory.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> apiInterface = loader.loadClass("com.example.fixture.api.AssetTicketApi");
            Class<?> dto = loader.loadClass("com.example.fixture.dto.AssetTicketDto");
            Class<?> page = loader.loadClass("com.example.fixture.dto.AssetTicketPage");
            Class<?> status = loader.loadClass("com.example.fixture.dto.AssetTicketStatus");
            Class<?> createRequest = loader.loadClass("com.example.fixture.dto.CreateAssetTicketRequest");
            Class<?> updateRequest = loader.loadClass("com.example.fixture.dto.UpdateAssetTicketRequest");
            assertMapping(apiInterface, "createAssetTicket", RequestMethod.POST, ASSET_TICKET_ROUTE, createRequest);
            assertMapping(apiInterface, "listAssetTickets", RequestMethod.GET, ASSET_TICKET_ROUTE, status);
            assertMapping(apiInterface, "getAssetTicketById", RequestMethod.GET, ASSET_TICKET_ROUTE + "/{id}", java.util.UUID.class);
            assertMapping(apiInterface, "updateAssetTicket", RequestMethod.PUT, ASSET_TICKET_ROUTE + "/{id}", java.util.UUID.class, updateRequest);
            assertMapping(apiInterface, "deleteAssetTicket", RequestMethod.DELETE, ASSET_TICKET_ROUTE + "/{id}", java.util.UUID.class);
            assertReturn(apiInterface.getMethod("createAssetTicket", createRequest), dto);
            assertReturn(apiInterface.getMethod("listAssetTickets", status), page);
            assertReturn(apiInterface.getMethod("getAssetTicketById", java.util.UUID.class), dto);
            assertReturn(apiInterface.getMethod("updateAssetTicket", java.util.UUID.class, updateRequest), dto);
            assertReturn(apiInterface.getMethod("deleteAssetTicket", java.util.UUID.class), Void.class);
            assertPublishedEnvelope(apiInterface.getMethod("createAssetTicket", createRequest),
                    "AssetTicketDtoResponse", dto);
            assertPublishedEnvelope(apiInterface.getMethod("listAssetTickets", status),
                    "AssetTicketPageResponse", page);
            assertPublishedEnvelope(apiInterface.getMethod("getAssetTicketById", java.util.UUID.class),
                    "AssetTicketDtoResponse", dto);
            assertPublishedEnvelope(apiInterface.getMethod("updateAssetTicket", java.util.UUID.class, updateRequest),
                    "AssetTicketDtoResponse", dto);
            assertPublishedEnvelope(apiInterface.getMethod("deleteAssetTicket", java.util.UUID.class),
                    "AssetTicketVoidResponse", null);
            assertNotNull(page.getMethod("getData"));
            assertNotNull(page.getMethod("getTotalPages"));
            assertNotNull(page.getMethod("getTotalElements"));
            assertNotNull(page.getMethod("getSize"));
            assertNotNull(page.getMethod("getNumber"));

            Class<?> generatedEntity = loader.loadClass("com.example.fixture.entity.AssetTicket");
            Object first = generatedEntity.getConstructor().newInstance();
            Object second = generatedEntity.getConstructor().newInstance();
            assertEquals(first, first);
            assertNotEquals(first, second, "Transient entities must not compare equal");
            var setId = generatedEntity.getDeclaredMethod("setId", java.util.UUID.class);
            setId.setAccessible(true);
            java.util.UUID sharedId = java.util.UUID.randomUUID();
            setId.invoke(first, sharedId);
            setId.invoke(second, sharedId);
            assertEquals(first, second, "Entities with the same persisted ID must compare equal");
            assertEquals(first.hashCode(), second.hashCode());
            assertEquals(generatedEntity.hashCode(), first.hashCode());
            Class<?> controller = loader.loadClass("com.example.fixture.controller.AssetTicketController");
            assertTrue(apiInterface.isAssignableFrom(controller));
            assertInheritedMapping(controller, "createAssetTicket", RequestMethod.POST,
                    "/api/v1/asset-tickets", createRequest);
            assertInheritedMapping(controller, "listAssetTickets", RequestMethod.GET,
                    "/api/v1/asset-tickets", status);
            assertInheritedMapping(controller, "getAssetTicketById", RequestMethod.GET,
                    "/api/v1/asset-tickets/{id}", java.util.UUID.class);
            assertInheritedMapping(controller, "updateAssetTicket", RequestMethod.PUT,
                    "/api/v1/asset-tickets/{id}", java.util.UUID.class, updateRequest);
            assertInheritedMapping(controller, "deleteAssetTicket", RequestMethod.DELETE,
                    "/api/v1/asset-tickets/{id}", java.util.UUID.class);
        }
    }

    @Test
    void missingRequiredFieldNamesFailClearlyBeforeOutput() throws Exception {
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("className", "AssetTicket");
        entity.put("tableName", "asset_tickets");
        entity.put("auditBasePackage", "eg.mts.gsuif.entity");
        entity.put("enversAudited", false);
        entity.put("fields", List.of(Map.of("columnName", "title", "javaType", "String", "nullable", false)));
        Exception error = assertThrows(Exception.class,
                () -> render(entityPath(), Map.of("project", Map.of("basePackage", "com.example.fixture"), "entity", entity)));
        assertTrue(error.getMessage().contains("entity.fields[0].name"), error::getMessage);
    }

    @Test
    void invalidNestedTypesAndIdentifiersFailWithIndexedPaths() throws Exception {
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("className", "AssetTicket");
        entity.put("tableName", "asset_tickets");
        entity.put("auditBasePackage", "eg.mts.gsuif.entity");
        entity.put("fields", List.of(Map.of("name", "title", "columnName", "title",
                "javaType", "String", "nullable", "false")));
        Map<String, Object> model = Map.of("project", Map.of("basePackage", "com.example.fixture"), "entity", entity);
        Exception wrongBoolean = assertThrows(Exception.class, () -> render(entityPath(), model));
        assertTrue(wrongBoolean.getMessage().contains("entity.fields[0].nullable"), wrongBoolean::getMessage);

        entity.put("fields", List.of(Map.of("name", "title", "columnName", "title",
                "javaType", "String", "nullable", false, "columnLength", 0)));
        Exception badLength = assertThrows(Exception.class, () -> render(entityPath(), model));
        assertTrue(badLength.getMessage().contains("entity.fields[0].columnLength"), badLength::getMessage);

        entity.put("className", "123Ticket");
        Exception badIdentifier = assertThrows(Exception.class, () -> render(entityPath(), model));
        assertTrue(badIdentifier.getMessage().contains("entity.className"), badIdentifier::getMessage);
    }

    @Test
    void scalarTypeCannotBePresentedAsAnEnum() throws Exception {
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("className", "AssetTicket");
        entity.put("tableName", "asset_tickets");
        entity.put("auditBasePackage", "eg.mts.gsuif.entity");
        entity.put("fields", List.of(field("title", "title", "String", false, 64, "String")));
        Exception error = assertThrows(Exception.class, () -> render(entityPath(),
                Map.of("project", Map.of("basePackage", "com.example.fixture"), "entity", entity)));
        assertTrue(error.getMessage().contains("entity.fields[0].enumType"), error::getMessage);

        entity.put("className", "UUID");
        entity.put("fields", List.of());
        Exception collision = assertThrows(Exception.class, () -> render(entityPath(),
                Map.of("project", Map.of("basePackage", "com.example.fixture"), "entity", entity)));
        assertTrue(collision.getMessage().contains("entity.className"), collision::getMessage);
    }

    @Test
    void controllerRejectsJavaReservedPackageAndMethodNames() throws Exception {
        Map<String, Object> api = fixtureApi();
        Map<String, Object> entity = Map.of("className", "AssetTicket");
        Exception badProjectPackage = assertThrows(Exception.class, () -> render(controllerPath(),
                Map.of("project", Map.of("basePackage", "com.class"), "entity", entity, "api", api)));
        assertTrue(badProjectPackage.getMessage().contains("project.basePackage"), badProjectPackage::getMessage);

        api.put("dtoPackage", "com.class.dto");
        Exception badApiPackage = assertThrows(Exception.class, () -> render(controllerPath(),
                Map.of("project", Map.of("basePackage", "com.example.fixture"), "entity", entity, "api", api)));
        assertTrue(badApiPackage.getMessage().contains("api.dtoPackage"), badApiPackage::getMessage);

        api.put("dtoPackage", "com.example.fixture.dto");
        api.put("createMethod", "class");
        Exception badMethod = assertThrows(Exception.class, () -> render(controllerPath(),
                Map.of("project", Map.of("basePackage", "com.example.fixture"), "entity", entity, "api", api)));
        assertTrue(badMethod.getMessage().contains("api.createMethod"), badMethod::getMessage);

        api.put("createMethod", "createAssetTicket");
        api.put("getMethod", "createAssetTicket");
        Exception duplicateMethod = assertThrows(Exception.class, () -> render(controllerPath(),
                Map.of("project", Map.of("basePackage", "com.example.fixture"), "entity", entity, "api", api)));
        assertTrue(duplicateMethod.getMessage().contains("api.getMethod"), duplicateMethod::getMessage);

        api.put("getMethod", "getAssetTicketById");
        api.put("dtoClass", "AssetTicketController");
        Exception classCollision = assertThrows(Exception.class, () -> render(controllerPath(),
                Map.of("project", Map.of("basePackage", "com.example.fixture"), "entity", entity, "api", api)));
        assertTrue(classCollision.getMessage().contains("api.dtoClass"), classCollision::getMessage);
    }

    @Test
    void entityRequiresExplicitAuditSupportPackage() throws Exception {
        Map<String, Object> model = Map.of("project", Map.of("basePackage", "com.example.fixture"),
                "entity", Map.of("className", "AssetTicket", "tableName", "asset_tickets", "fields", List.of()));
        Exception error = assertThrows(Exception.class, () -> render(entityPath(), model));
        assertTrue(error.getMessage().contains("entity.auditBasePackage"), error::getMessage);
    }

    @Test
    void enversAnnotationIsExplicitlyConditional() throws Exception {
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("className", "AssetTicket");
        entity.put("tableName", "asset_tickets");
        entity.put("auditBasePackage", "eg.mts.gsuif.entity");
        entity.put("fields", List.of());
        entity.put("enversAudited", true);
        String source = render(entityPath(), Map.of("project", Map.of("basePackage", "com.example.fixture"), "entity", entity));
        assertTrue(source.contains("import org.hibernate.envers.Audited;"));
        assertTrue(source.contains("@Audited"));
    }

    @Test
    void catalogOverrideResolvesAtClasspathRoot() throws Exception {
        List<?> artifacts = (List<?>) ((Map<?, ?>) generation("BE-05").get("template")).get("artifacts");
        Map<?, ?> override = artifacts.stream().map(item -> (Map<?, ?>) item)
                .filter(item -> "OPENAPI_OVERRIDE".equals(item.get("role"))).findFirst().orElseThrow();
        assertEquals("CLASSPATH", override.get("location_type"));
        try (InputStream stream = getClass().getResourceAsStream("/" + override.get("path"))) {
            assertNotNull(stream, "Catalog Mustache override must resolve at its exact path");
        }
    }

    private String entityPath() throws Exception {
        return (String) ((Map<?, ?>) generation("BE-02").get("template")).get("path");
    }

    private String controllerPath() throws Exception {
        List<?> artifacts = (List<?>) ((Map<?, ?>) generation("BE-05").get("template")).get("artifacts");
        Map<?, ?> controller = artifacts.stream().map(item -> (Map<?, ?>) item)
                .filter(item -> "CONTROLLER_IMPLEMENTATION".equals(item.get("role"))).findFirst().orElseThrow();
        assertEquals("CLASSPATH", controller.get("location_type"));
        return (String) controller.get("path");
    }

    private Map<?, ?> generation(String id) throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/components.yaml")) {
            assertNotNull(stream, "Maven must include the catalog as a test resource");
            Map<?, ?> catalog = new Yaml().load(stream);
            List<?> components = (List<?>) catalog.get("components");
            Map<?, ?> component = components.stream().map(item -> (Map<?, ?>) item)
                    .filter(item -> id.equals(item.get("id"))).findFirst().orElseThrow();
            return (Map<?, ?>) component.get("generation");
        }
    }

    private String render(String path, Map<String, Object> model) throws Exception {
        StringWriter output = new StringWriter();
        configuration.getTemplate(path).process(model, output);
        return output.toString();
    }

    private Map<String, Object> field(String name, String column, String type, boolean nullable,
                                      Integer length, String enumType) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("name", name);
        field.put("columnName", column);
        field.put("javaType", type);
        field.put("nullable", nullable);
        field.put("columnLength", length);
        field.put("enumType", enumType);
        return field;
    }

    private Map<String, Object> fixtureApi() {
        Map<String, Object> api = new LinkedHashMap<>();
        api.put("dtoPackage", "com.example.fixture.dto");
        api.put("responsePackage", "eg.mts.gsuif.dto");
        api.put("dtoClass", "AssetTicketDto");
        api.put("pageDtoClass", "AssetTicketPage");
        api.put("createRequestClass", "CreateAssetTicketRequest");
        api.put("updateRequestClass", "UpdateAssetTicketRequest");
        api.put("statusEnumClass", "AssetTicketStatus");
        api.put("statusEnumPackage", "com.example.fixture.dto");
        api.put("serviceInterface", "AssetTicketService");
        api.put("servicePackage", "com.example.fixture.service");
        api.put("interfacePackage", "com.example.fixture.api");
        api.put("interfaceClass", "AssetTicketApi");
        api.put("createMethod", "createAssetTicket");
        api.put("getMethod", "getAssetTicketById");
        api.put("listMethod", "listAssetTickets");
        api.put("updateMethod", "updateAssetTicket");
        api.put("deleteMethod", "deleteAssetTicket");
        return api;
    }

    private void assertPublishedEnvelope(Method method, String schemaClassName, Class<?> bodyClass) throws Exception {
        Operation operation = method.getAnnotation(Operation.class);
        assertNotNull(operation);
        assertEquals(1, operation.responses().length);
        Class<?> schema = operation.responses()[0].content()[0].schema().implementation();
        assertEquals("com.example.fixture.dto." + schemaClassName, schema.getName());
        assertNotNull(schema.getMethod("getStatus"));
        assertNotNull(schema.getMethod("getClientMessage"));
        assertNotNull(schema.getMethod("getStatusCode"));
        assertNotNull(schema.getMethod("getBody"));
        assertNotNull(schema.getMethod("getErrors"));
        if (bodyClass != null) assertEquals(bodyClass, schema.getMethod("getBody").getReturnType());

        try (InputStream stream = getClass().getResourceAsStream("/openapi/asset-ticket-api.yaml")) {
            assertNotNull(stream, "The published OpenAPI fixture must be on the classpath");
            Map<?, ?> spec = new Yaml().load(stream);
            Map<?, ?> schemas = (Map<?, ?>) ((Map<?, ?>) spec.get("components")).get("schemas");
            Map<?, ?> envelope = (Map<?, ?>) schemas.get(schemaClassName);
            assertNotNull(envelope, schemaClassName);
            Set<String> expectedFields = Set.of("status", "clientMessage", "statusCode", "body", "errors");
            assertEquals(expectedFields, Set.copyOf((List<?>) envelope.get("required")));
            Map<?, ?> properties = (Map<?, ?>) envelope.get("properties");
            assertEquals(expectedFields, properties.keySet());
            assertEquals(Boolean.TRUE, ((Map<?, ?>) properties.get("errors")).get("nullable"));
            Map<?, ?> body = (Map<?, ?>) properties.get("body");
            if (bodyClass == null) {
                assertEquals("object", body.get("type"));
                assertEquals(Boolean.TRUE, body.get("nullable"));
            } else {
                assertEquals("#/components/schemas/" + bodyClass.getSimpleName(), body.get("$ref"));
            }

            Map<?, ?> paths = (Map<?, ?>) spec.get("paths");
            Map<?, ?> sourceOperation = null;
            for (Object pathItem : paths.values()) {
                for (Object candidate : ((Map<?, ?>) pathItem).values()) {
                    if (candidate instanceof Map<?, ?> operationMap
                            && method.getName().equals(operationMap.get("operationId"))) {
                        sourceOperation = operationMap;
                    }
                }
            }
            assertNotNull(sourceOperation, method.getName());
            assertEquals(bodyClass == null ? Void.class.getName() : bodyClass.getName(),
                    sourceOperation.get("x-gsuif-payload-java-type"));
            Map<?, ?> responses = (Map<?, ?>) sourceOperation.get("responses");
            Map<?, ?> sourceResponse = (Map<?, ?>) responses.get(operation.responses()[0].responseCode());
            Map<?, ?> content = (Map<?, ?>) sourceResponse.get("content");
            Map<?, ?> media = (Map<?, ?>) content.get("application/json");
            assertEquals("#/components/schemas/" + schemaClassName,
                    ((Map<?, ?>) media.get("schema")).get("$ref"));
        }
    }

    private Path write(String relative, String source) throws Exception {
        Path output = temporaryDirectory.resolve(relative);
        Files.createDirectories(output.getParent());
        Files.writeString(output, source, StandardCharsets.UTF_8);
        return output;
    }

    private Path writeFixtureService() throws Exception {
        return write("com/example/fixture/service/AssetTicketService.java", """
                package com.example.fixture.service;
                import java.util.UUID;
                import com.example.fixture.dto.AssetTicketDto;
                import com.example.fixture.dto.AssetTicketPage;
                import com.example.fixture.dto.CreateAssetTicketRequest;
                import com.example.fixture.dto.UpdateAssetTicketRequest;
                import com.example.fixture.dto.AssetTicketStatus;
                public interface AssetTicketService {
                    AssetTicketDto create(CreateAssetTicketRequest request);
                    AssetTicketDto getById(UUID id);
                    AssetTicketPage list(AssetTicketStatus status);
                    AssetTicketDto update(UUID id, UpdateAssetTicketRequest request);
                    void delete(UUID id);
                }
                """);
    }

    private void assertMapping(Class<?> apiInterface, String methodName, RequestMethod verb,
                               String path, Class<?>... parameters) throws Exception {
        Method method = apiInterface.getMethod(methodName, parameters);
        RequestMapping mapping = method.getAnnotation(RequestMapping.class);
        assertNotNull(mapping, methodName + " must carry a generated Spring mapping");
        assertArrayEquals(new RequestMethod[]{verb}, mapping.method());
        assertArrayEquals(new String[]{path}, mapping.value());
        assertArrayEquals(new String[]{"application/json"}, mapping.produces());
        if (verb == RequestMethod.POST || verb == RequestMethod.PUT) {
            assertArrayEquals(new String[]{"application/json"}, mapping.consumes());
        }
    }

    private void assertReturn(Method method, Class<?> body) {
        assertEquals("org.springframework.http.ResponseEntity<eg.mts.gsuif.dto.ApiResponse<"
                + body.getName() + ">>", method.getGenericReturnType().getTypeName());
    }

    private void assertInheritedMapping(Class<?> controller, String name, RequestMethod verb,
                                        String path, Class<?>... parameters) throws Exception {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(
                controller.getMethod(name, parameters), RequestMapping.class);
        assertNotNull(mapping, name + " must inherit the generated interface mapping");
        assertArrayEquals(new RequestMethod[]{verb}, mapping.method());
        assertArrayEquals(new String[]{path}, mapping.value());
    }

    private void compile(List<Path> sources) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "The Maven test requires JDK 21, not a JRE");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            var units = files.getJavaFileObjectsFromPaths(sources);
            String classpath = System.getProperty("java.class.path");
            List<String> options = List.of("--release", "21", "-proc:none", "-classpath", classpath,
                    "-d", temporaryDirectory.toString());
            boolean passed = compiler.getTask(null, files, diagnostics, options, null, units).call();
            StringBuilder errors = new StringBuilder();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) errors.append(diagnostic).append('\n');
            }
            assertTrue(passed, errors.toString());
        }
    }
}
