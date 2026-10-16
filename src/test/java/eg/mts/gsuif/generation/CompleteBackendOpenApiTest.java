package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eg.mts.gsuif.generation.GenerationCapabilityPreflight.OutputMode;
import eg.mts.gsuif.generation.GenerationFilePlan.Entry;
import eg.mts.gsuif.generation.GenerationFilePlan.Owner;
import eg.mts.gsuif.generation.GenerationSpecification.*;
import eg.mts.gsuif.validator.MetadataSchemaValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import javax.tools.ToolProvider;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CompleteBackendOpenApiTest {
    private final CompleteOutputTestFixture fixture = new CompleteOutputTestFixture();
    private final ObjectMapper json = new ObjectMapper();

    @Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "gsuif.openapi.probe", havingValue = "true")
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    static class BootProbe { }

    @Test void rendererOwnsOnlyPlannedDefinitionsAndIsDeterministic() throws Exception {
        var descriptor = stockUnit();
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var renderer = new CompleteBackendOpenApiRenderer();
        var files = renderer.render(descriptor, plan);
        assertEquals(2, files.size());
        assertTrue(files.keySet().stream().allMatch(path -> plan.entries().stream()
                .anyMatch(entry -> entry.path().equals(path) && entry.owner() == Owner.DETERMINISTIC)));
        files.forEach((path, bytes) -> assertArrayEquals(bytes, renderer.render(descriptor, plan).get(path)));
        String path = files.keySet().iterator().next();
        var missing = new GenerationFilePlan(plan.entries().stream().filter(entry -> !entry.path().equals(path)).toList());
        assertThrows(IllegalArgumentException.class, () -> renderer.render(descriptor, missing));
        var wrong = new GenerationFilePlan(plan.entries().stream().map(entry -> entry.path().equals(path)
                ? new Entry(path, Owner.MODEL, entry.purpose()) : entry).toList());
        assertThrows(IllegalArgumentException.class, () -> renderer.render(descriptor, wrong));
        byte original = files.get(path)[0];
        files.get(path)[0] ^= 1;
        assertEquals(original, renderer.render(descriptor, plan).get(path)[0]);
    }

    @Test void emittedBootHttpDocumentsCrudAndLoginOnlyInDevAndTest(@TempDir Path scratch) throws Exception {
        verifyProfiles(stockUnit(), "/generation/stock-unit-contracts.yaml", scratch.resolve("stock"));
    }

    @Test void renamedGadgetOperationsUseExactDescriptorAndNoStockUnitRuntimePaths(@TempDir Path scratch) throws Exception {
        verifyProfiles(gadget(), "/generation/gadget-contracts.yaml", scratch.resolve("gadget"));
    }

    private void verifyProfiles(CompleteOutputDescriptor descriptor, String registryResource, Path scratch) throws Exception {
        Files.createDirectories(scratch);
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var runtime = new CompleteBackendRuntimeRenderer(new PackagedContractRegistry(registryResource))
                .render(descriptor, plan);
        assertEquals(descriptor.contractResourceSha256(), sha256(runtime.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("contracts/")).findFirst().orElseThrow().getValue()));
        Path applicationYaml = scratch.resolve("application.yml");
        Files.write(applicationYaml, runtime.get("backend/src/main/resources/application.yml"));
        try (URLClassLoader loader = compiled(descriptor, plan, scratch)) {
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            String inheritedProfile = System.getProperty("spring.profiles.active");
            Thread.currentThread().setContextClassLoader(loader);
            try {
                String base = descriptor.basePackage();
                Class<?> service = loader.loadClass(base + ".service." + descriptor.contractServiceName());
                Class<?> repository = loader.loadClass(base + ".security.UserAccountRepository");
                Object serviceMock = Proxy.newProxyInstance(loader, new Class<?>[]{service},
                        (proxy, method, args) -> null);
                for (String profile : List.of("dev", "test", "prod")) {
                    var builder = new SpringApplicationBuilder(BootProbe.class,
                            loader.loadClass(base + ".config.OpenApiConfig"),
                            loader.loadClass(base + ".security.SecurityConfig"),
                            loader.loadClass(base + ".security.SecurityMode"),
                            loader.loadClass(base + ".security.EnforcedMethodSecurityConfig"),
                            loader.loadClass(base + ".security.AccountIdentityService"),
                            loader.loadClass(base + ".security.JwtService"),
                            loader.loadClass(base + ".security.AuthController"),
                            loader.loadClass(base + ".security.PasswordEncodingConfig"),
                            loader.loadClass(base + ".controller." + descriptor.entityName() + "Controller"),
                            loader.loadClass(base + ".exception.GlobalExceptionHandler"))
                            .web(WebApplicationType.SERVLET)
                            .initializers(application -> {
                                application.getBeanFactory().registerSingleton("accountRepositoryProbe", mock(repository));
                                application.getBeanFactory().registerSingleton("generatedCrudServiceProbe", serviceMock);
                            });
                    List<String> args = new ArrayList<>(List.of("--spring.profiles.active=" + profile,
                            "--spring.config.location=" + applicationYaml.toUri(), "--server.port=0",
                            "--spring.main.banner-mode=off", "--logging.level.root=OFF",
                            "--gsuif.openapi.probe=true"));
                    if (!profile.equals("dev")) args.add("--GENERATED_APP_JWT_SECRET="
                            + Base64.getEncoder().encodeToString(new byte[32]));
                    try (ConfigurableApplicationContext context = builder.run(args.toArray(String[]::new));
                         HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
                        Object webServer = context.getClass().getMethod("getWebServer").invoke(context);
                        int port = (int) webServer.getClass().getMethod("getPort").invoke(webServer);
                        URI root = URI.create("http://127.0.0.1:" + port);
                        assertEquals(profile.equals("dev") ? 0 : 1,
                                context.getBeansOfType(loader.loadClass(base + ".security.JwtService")).size());
                        int expected = profile.equals("prod") ? 404 : 200;
                        var docs = get(client, root.resolve("/v3/api-docs"));
                        assertEquals(expected, docs.statusCode(), profile + " docs: " + docs.body());
                        assertEquals(expected, get(client, root.resolve("/swagger-ui/index.html")).statusCode(),
                                profile + " Swagger UI");
                        if (!profile.equals("dev")) {
                            String protectedPath = descriptor.operations().stream()
                                    .filter(operation -> operation.action() == PackagedContractRegistry.CrudAction.LIST)
                                    .findFirst().orElseThrow().path();
                            var denied = get(client, root.resolve(protectedPath));
                            assertEquals(401, denied.statusCode(), profile + " protected CRUD");
                            assertEquals(Set.of("status", "clientMessage", "statusCode", "body", "errors"),
                                    names(json.readTree(denied.body())));
                        }
                        if (profile.equals("prod")) continue;
                        JsonNode document = json.readTree(docs.body());
                        if (descriptor.entityName().equals("Gadget"))
                            assertFalse(docs.body().contains("StockUnit"));
                        assertEquals(descriptor.contractVersion(), document.path("info").path("version").asText());
                        assertEquals("http", document.path("components").path("securitySchemes")
                                .path("bearerAuth").path("type").asText());
                        assertEquals("bearer", document.path("components").path("securitySchemes")
                                .path("bearerAuth").path("scheme").asText());
                        JsonNode error = document.path("components").path("schemas").path("ErrorApiResponse")
                                .path("properties");
                        assertEquals(Set.of("status", "clientMessage", "statusCode", "body", "errors"),
                                names(error));
                        for (var operation : descriptor.operations()) {
                            JsonNode method = document.path("paths").path(operation.path())
                                    .path(operation.method().toLowerCase(java.util.Locale.ROOT));
                            assertFalse(method.isMissingNode(), operation.operationId());
                            assertEquals(operation.operationId(), method.path("operationId").asText());
                            JsonNode success = method.path("responses").path(String.valueOf(operation.successStatus()));
                            assertFalse(success.isMissingNode());
                            assertFalse(success.path("content").path("application/json").path("schema").isMissingNode());
                            for (String status : List.of("400", "401", "403", "405", "500"))
                                assertFalse(method.path("responses").path(status).isMissingNode(), status);
                            assertTrue(method.path("responses").path("400").path("content").path("application/json")
                                    .path("schema").path("$ref").asText().endsWith("/ErrorApiResponse"));
                            if (operation.action() == PackagedContractRegistry.CrudAction.CREATE
                                    || operation.action() == PackagedContractRegistry.CrudAction.UPDATE)
                                assertFalse(method.path("requestBody").path("content").path("application/json")
                                        .path("schema").isMissingNode());
                            if (operation.action() == PackagedContractRegistry.CrudAction.LIST)
                                operation.queryFields().forEach((name, parameter) -> {
                                    boolean found = false;
                                    for (JsonNode declared : method.path("parameters"))
                                        if (name.equals(declared.path("name").asText())) {
                                            found = true;
                                            assertEquals(parameter.required(), declared.path("required").asBoolean());
                                        }
                                    assertTrue(found, "Missing query parameter: " + name);
                                });
                            assertTrue(profile.equals("dev") ? method.path("security").isEmpty()
                                    : method.path("security").toString().contains("bearerAuth"));
                        }
                        JsonNode login = document.path("paths").path("/api/auth/login").path("post");
                        if (profile.equals("dev")) {
                            assertTrue(login.isMissingNode());
                            assertEquals(404, post(client, root.resolve("/api/auth/login")).statusCode());
                        } else {
                            assertEquals("login", login.path("operationId").asText());
                            assertTrue(login.path("security").isEmpty());
                            assertFalse(login.path("requestBody").path("content").path("application/json")
                                    .path("schema").isMissingNode());
                            assertFalse(login.path("responses").path("200").path("content")
                                    .path("application/json").path("schema").isMissingNode());
                            assertFalse(login.path("responses").path("401").isMissingNode());
                            assertTrue(login.path("responses").path("401").path("content")
                                    .path("application/json").path("schema").path("$ref")
                                    .asText().endsWith("/ErrorApiResponse"));
                            assertTrue(document.path("components").path("schemas").path("LoginRequest")
                                    .path("properties").path("password").path("writeOnly").asBoolean());
                            assertEquals(400, post(client, root.resolve("/api/auth/login")).statusCode());
                        }
                    }
                }
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
                if (inheritedProfile == null) System.clearProperty("spring.profiles.active");
                else System.setProperty("spring.profiles.active", inheritedProfile);
            }
        }
    }

    private URLClassLoader compiled(CompleteOutputDescriptor descriptor, GenerationFilePlan plan, Path scratch) throws Exception {
        Map<String, byte[]> files = new TreeMap<>(new CompleteBackendDefinitionRenderer().render(descriptor, plan).files());
        files.putAll(new CompleteBackendApiRenderer().render(descriptor, plan));
        files.putAll(new CompleteBackendErrorRenderer().render(descriptor, plan));
        files.putAll(new CompleteBackendSecurityRenderer().render(descriptor, plan));
        files.putAll(new CompleteBackendOpenApiRenderer().render(descriptor, plan));
        new CompleteBackendObservabilityRenderer().render(descriptor, plan).forEach((path, bytes) -> {
            if (path.endsWith(".java")) files.put(path, bytes);
        });
        new CompleteBackendInitialMigrationRenderer().render(descriptor, plan).forEach((path, bytes) -> {
            if (path.endsWith("/UserAccount.java")) files.put(path, bytes);
        });
        Path classes = Files.createDirectories(scratch.resolve("classes"));
        List<String> arguments = new ArrayList<>(List.of("-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString()));
        for (var file : files.entrySet()) {
            if (!file.getKey().endsWith(".java")) continue;
            Path source = scratch.resolve(file.getKey());
            Files.createDirectories(source.getParent());
            Files.write(source, file.getValue());
            arguments.add(source.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)));
        return new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader());
    }

    private CompleteOutputDescriptor stockUnit() throws Exception {
        return fixture.preflight().describe(fixture.version(fixture.snapshot("condition")),
                fixture.specification(), OutputMode.BACKEND);
    }

    private CompleteOutputDescriptor gadget() throws Exception {
        var registry = new PackagedContractRegistry("/generation/gadget-contracts.yaml");
        var preflight = new GenerationCapabilityPreflight(
                new GenerationContextBuilder(fixture.mapper, new MetadataSchemaValidator(), registry), registry);
        List<String> operations = List.of("fetchGadgets", "submitGadget", "findGadget", "replaceGadget", "removeGadget");
        Map<UUID, String> bindings = new java.util.LinkedHashMap<>();
        Map<String, Set<SupportedRole>> roles = new java.util.LinkedHashMap<>();
        for (int i = 0; i < operations.size(); i++) {
            bindings.put(new UUID(0, i + 1), operations.get(i));
            roles.put(operations.get(i), Set.of(SupportedRole.ROLE_ADMIN));
        }
        var spec = new GenerationSpecification("1.0.0", "com.example.fixture",
                new EntitySpec("Gadget", "gadget", List.of(
                        new FieldSpec("title", "title", "String", false, 100, null),
                        new FieldSpec("status", "status", "GadgetStatus", false, null, "GadgetStatus"),
                        new FieldSpec("note", "note", "String", true, 100, null)), false),
                new OpenApiSpec("gadget-api", "2.0.0", bindings), roles, new AngularSpec("Gadget", Map.of()));
        ObjectNode snapshot = fixture.snapshot("condition");
        for (int i = 0; i < 5; i++) {
            ObjectNode binding = (ObjectNode) snapshot.withArray("apiBindings").get(i);
            binding.put("endpointUrl", binding.path("endpointUrl").asText().replace("stock-units", "gadgets"));
            if (i == 0) {
                ObjectNode query = (ObjectNode) binding.path("requestMapping").path("query");
                query.removeAll(); query.put("status", "status");
            }
            if (i == 1 || i == 3) {
                ObjectNode body = (ObjectNode) binding.path("requestMapping").path("body");
                body.removeAll(); body.put("title", "title").put("status", "status").put("note", "note");
            }
        }
        return preflight.describe(fixture.version(snapshot), spec, OutputMode.BACKEND);
    }

    private static Set<String> names(JsonNode properties) {
        var result = new java.util.TreeSet<String>();
        properties.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static HttpResponse<String> get(HttpClient client, URI uri) throws Exception {
        return client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(HttpClient client, URI uri) throws Exception {
        return client.send(HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
    }
}
