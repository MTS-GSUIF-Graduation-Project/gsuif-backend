package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eg.mts.gsuif.entity.GsuifPage;
import eg.mts.gsuif.entity.GsuifProject;
import eg.mts.gsuif.entity.MetadataVersion;
import eg.mts.gsuif.generation.GenerationContext.Target;
import eg.mts.gsuif.generation.GenerationSpecification.*;
import eg.mts.gsuif.validator.MetadataSchemaValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.tools.*;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class GenerationEngineTest {
    @TempDir Path temporaryDirectory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final GenerationContextBuilder builder = new GenerationContextBuilder(mapper, new MetadataSchemaValidator());

    @Test void providerSubstitutionAndBufferedEntity() throws Exception {
        var version = version("1.0.0", emptySnapshot());
        var spec = spec("AssetTicket", "asset-ticket-api", Map.of(), Map.of());
        AICodeGenerationProvider fake = context -> {
            assertEquals("AssetTicket", context.specification().entity().className());
            assertEquals(Set.of(Target.ENTITY), context.targets());
            return new GenerationResult(List.of(), List.of("fake"));
        };
        assertEquals(List.of("fake"), new GenerationEngine(builder, fake).generate(version, spec, Set.of(Target.ENTITY), "spring-angular").diagnostics());
        var result = GenerationEngine.templateOnly(builder).generate(version, spec, Set.of(Target.ENTITY), "spring-angular");
        assertEquals(1, result.artifacts().size());
        var artifact = result.artifacts().getFirst();
        assertEquals("src/main/java/com/example/fixture/entity/AssetTicket.java", artifact.relativePath());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(artifact.bytes())), artifact.sha256());
        assertEquals(GenerationCatalog.load().version(), artifact.catalogVersion());
        assertEquals(GenerationCatalog.load().entity().version(), artifact.templateVersion());
        assertTrue(new String(artifact.bytes(), StandardCharsets.UTF_8).contains("class AssetTicket extends AuditableEntity"));
    }

    @Test void indexedValidationAndSchemaDispatch() throws Exception {
        var version = version("1.0.0", emptySnapshot());
        var original = spec("AssetTicket", "asset-ticket-api", Map.of(), Map.of());
        var invalid = new GenerationSpecification("1.0.0", "com.example.fixture",
                new EntitySpec("AssetTicket", "asset_ticket", List.of(new FieldSpec("../escape", "title", "String", false, 10, null)), false),
                original.openApi(), original.operationRoles(), original.angular());
        var failure = assertThrows(GenerationValidationException.class,
                () -> builder.build(version, invalid, Set.of(Target.ENTITY), "spring-angular"));
        assertTrue(failure.diagnostics().stream().anyMatch(d -> d.contains("fields[0].name")));
        version.setSchemaVersion("9.0.0");
        assertTrue(assertThrows(GenerationValidationException.class,
                () -> builder.build(version, original, Set.of(Target.ENTITY), "spring-angular"))
                .diagnostics().stream().anyMatch(d -> d.contains("schemaVersion")));
        assertThrows(GenerationValidationException.class, () -> builder.build(version("1.0.0", emptySnapshot()), original,
                Set.of(Target.ENTITY), "unknown"));
        var unknownContract = new GenerationSpecification("1.0.0", original.basePackage(), original.entity(),
                new OpenApiSpec(null, "1.0.0", Map.of()), original.operationRoles(), original.angular());
        assertTrue(assertThrows(GenerationValidationException.class,
                () -> builder.build(version("1.0.0", emptySnapshot()), unknownContract, Set.of(Target.ENTITY), "spring-angular"))
                .diagnostics().stream().anyMatch(d -> d.contains("openApi.contractId")));
    }

    @Test void entityEnumMustExistInSelectedPackagedContract() throws Exception {
        for (String entity : List.of("AssetTicket", "ServiceRequest")) {
            String contract = entity.equals("AssetTicket") ? "asset-ticket-api" : "service-request-api";
            var original = spec(entity, contract, Map.of(), Map.of());
            List<FieldSpec> fields = new ArrayList<>(original.entity().fields());
            fields.set(1, new FieldSpec("status", "status", "MissingStatus", false, null, "MissingStatus"));
            var invalid = new GenerationSpecification(original.specVersion(), original.basePackage(),
                    new EntitySpec(entity, original.entity().tableName(), fields, false),
                    original.openApi(), original.operationRoles(), original.angular());
            for (Target target : List.of(Target.ENTITY, Target.CONTROLLER)) {
                var failure = assertThrows(GenerationValidationException.class,
                        () -> GenerationEngine.templateOnly(builder).generate(version("1.0.0", emptySnapshot()),
                                invalid, Set.of(target), "spring-angular"));
                assertTrue(failure.diagnostics().stream().anyMatch(d -> d.contains("fields[1].enumType")
                        && d.contains("selected packaged contract")), failure.diagnostics().toString());
            }
        }
    }

    @Test void bothContractsDeriveControllerNamesAndEnforceRoles() throws Exception {
        for (String entity : List.of("AssetTicket", "ServiceRequest")) {
            var snapshot = bindings(entity);
            String contract = entity.equals("AssetTicket") ? "asset-ticket-api" : "service-request-api";
            Map<UUID, String> selected = new LinkedHashMap<>();
            Map<String, Set<SupportedRole>> roles = new LinkedHashMap<>();
            String[] methods = {"list" + entity + "s", "create" + entity, "get" + entity + "ById", "update" + entity, "delete" + entity};
            for (int i = 0; i < 5; i++) {
                selected.put(new UUID(0, i + 1), methods[i]);
                roles.put(methods[i], i == 0 ? EnumSet.of(SupportedRole.ROLE_ADMIN, SupportedRole.ROLE_USER) : EnumSet.of(SupportedRole.ROLE_ADMIN));
            }
            var spec = spec(entity, contract, selected, roles);
            var result = GenerationEngine.templateOnly(builder).generate(version("1.0.0", snapshot), spec, Set.of(Target.CONTROLLER), "spring-angular");
            assertEquals(6, result.artifacts().size());
            String controller = new String(result.artifacts().getFirst().bytes(), StandardCharsets.UTF_8);
            assertTrue(controller.contains("implements " + entity + "Api"));
            assertTrue(controller.contains("hasAnyAuthority('ROLE_ADMIN','ROLE_USER')"));
            assertEquals(5, result.artifacts().stream().filter(a -> a.relativePath().contains("eg/mts/gsuif/")).count());
            for (var artifact : result.artifacts()) if (artifact.relativePath().contains("eg/mts/gsuif/"))
                assertArrayEquals(Files.readAllBytes(Path.of(artifact.relativePath())), artifact.bytes());
            roles.remove(methods[1]);
            assertTrue(assertThrows(GenerationValidationException.class,
                    () -> builder.build(version("1.0.0", snapshot), spec(entity, contract, selected, roles), Set.of(Target.CONTROLLER), "spring-angular"))
                    .diagnostics().stream().anyMatch(d -> d.contains("operationRoles." + methods[1])));
        }
    }

    @Test void dateAngularForBothSchemaVersions() throws Exception {
        ObjectNode snapshot = bindings("ServiceRequest");
        ObjectNode date = snapshot.withArray("components").addObject();
        date.put("id", new UUID(0, 10).toString()); date.put("type", "date-field");
        date.put("label", "Due date"); date.put("fieldKey", "dueDate");
        date.putObject("position").put("row", 0).put("col", 0);
        date.putObject("size").put("width", 6).put("height", 1);
        date.put("visibility", true); date.put("disabled", false);
        ((ObjectNode) snapshot.withArray("apiBindings").get(0)).putArray("linkedComponentIds").add(new UUID(0, 10).toString());
        Map<UUID, String> selected = new LinkedHashMap<>();
        Map<String, Set<SupportedRole>> roles = new LinkedHashMap<>();
        String[] methods = {"listServiceRequests", "createServiceRequest", "getServiceRequestById", "updateServiceRequest", "deleteServiceRequest"};
        for (int i = 0; i < 5; i++) { selected.put(new UUID(0, i + 1), methods[i]); roles.put(methods[i], EnumSet.of(SupportedRole.ROLE_ADMIN)); }
        var spec = spec("ServiceRequest", "service-request-api", selected, roles);
        for (String schema : List.of("1.0.0", "1.1.0")) {
            var result = GenerationEngine.templateOnly(builder).generate(version(schema, snapshot), spec, Set.of(Target.ANGULAR), "spring-angular");
            assertEquals(2, result.artifacts().size());
            String ts = new String(result.artifacts().getFirst().bytes(), StandardCharsets.UTF_8);
            assertTrue(ts.contains("id: string"));
            assertTrue(ts.contains("dueDate: string"));
            assertTrue(new String(result.artifacts().get(1).bytes(), StandardCharsets.UTF_8).contains("type=\"date\""));
            stageAngularForCompilation(result);
        }
    }

    @Test void deleteBindingRequiresContractBodyMapping() throws Exception {
        ObjectNode snapshot = bindings("AssetTicket");
        ((ObjectNode) snapshot.withArray("apiBindings").get(4)).putObject("responseMapping");
        Map<UUID, String> selected = new LinkedHashMap<>();
        Map<String, Set<SupportedRole>> roles = new LinkedHashMap<>();
        String[] methods = {"listAssetTickets", "createAssetTicket", "getAssetTicketById", "updateAssetTicket", "deleteAssetTicket"};
        for (int i = 0; i < methods.length; i++) {
            selected.put(new UUID(0, i + 1), methods[i]);
            roles.put(methods[i], EnumSet.of(SupportedRole.ROLE_ADMIN));
        }
        var failure = assertThrows(GenerationValidationException.class,
                () -> builder.build(version("1.0.0", snapshot), spec("AssetTicket", "asset-ticket-api", selected, roles),
                        Set.of(Target.CONTROLLER), "spring-angular"));
        assertTrue(failure.diagnostics().stream().anyMatch(d -> d.contains("bindingOperations[4].responseMapping.item")));
    }

    @Test void angularTextSelectAndLegacyTableUseSelectedColumns() throws Exception {
        ObjectNode snapshot = bindings("AssetTicket");
        String[] types = {"text-field", "select", "table"};
        String[] keys = {"title", "status", ""};
        for (int i = 0; i < types.length; i++) {
            UUID id = new UUID(0, i + 10);
            ObjectNode component = snapshot.withArray("components").addObject();
            component.put("id", id.toString()); component.put("type", types[i]);
            component.put("label", types[i]);
            if (!keys[i].isEmpty()) component.put("fieldKey", keys[i]);
            component.putObject("position").put("row", i).put("col", 0);
            component.putObject("size").put("width", 6).put("height", 1);
            component.put("visibility", true); component.put("disabled", false);
            ((ObjectNode) snapshot.withArray("apiBindings").get(0)).withArray("linkedComponentIds").add(id.toString());
        }
        Map<UUID, String> selected = new LinkedHashMap<>();
        Map<String, Set<SupportedRole>> roles = new LinkedHashMap<>();
        String[] methods = {"listAssetTickets", "createAssetTicket", "getAssetTicketById", "updateAssetTicket", "deleteAssetTicket"};
        for (int i = 0; i < 5; i++) { selected.put(new UUID(0, i + 1), methods[i]); roles.put(methods[i], EnumSet.of(SupportedRole.ROLE_USER)); }
        var baseline = spec("AssetTicket", "asset-ticket-api", selected, roles);
        var spec = new GenerationSpecification("1.0.0", baseline.basePackage(), baseline.entity(), baseline.openApi(), baseline.operationRoles(),
                new AngularSpec("AssetTicket", Map.of(new UUID(0, 12), List.of("id", "title"))));
        var result = GenerationEngine.templateOnly(builder).generate(version("1.0.0", snapshot), spec, Set.of(Target.ANGULAR), "spring-angular");
        String html = new String(result.artifacts().get(1).bytes(), StandardCharsets.UTF_8);
        assertTrue(html.contains("type=\"text\"")); assertTrue(html.contains("<select"));
        assertTrue(html.contains("row.id")); assertFalse(html.contains("row.status"));
        String ts = new String(result.artifacts().getFirst().bytes(), StandardCharsets.UTF_8);
        assertTrue(ts.contains("status: ['OPEN', 'CLOSED']")); assertTrue(ts.contains("id: string"));
        stageAngularForCompilation(result);
        ObjectNode configured = (ObjectNode) snapshot.withArray("components").get(2);
        configured.putObject("tableConfig").putArray("columns").addObject().put("fieldKey", "title").put("label", "Title");
        var configuredFailure = assertThrows(GenerationValidationException.class,
                () -> GenerationEngine.templateOnly(builder).generate(version("1.1.0", snapshot), spec,
                        Set.of(Target.ANGULAR), "spring-angular"));
        assertTrue(configuredFailure.diagnostics().stream().anyMatch(d -> d.contains("components[2].tableConfig")
                && d.contains("deferred")), configuredFailure.diagnostics().toString());
        configured.remove("tableConfig");
        ((ObjectNode) snapshot.withArray("components").get(0)).put("type", "navigation");
        assertTrue(assertThrows(GenerationValidationException.class,
                () -> builder.build(version("1.0.0", snapshot), spec, Set.of(Target.ANGULAR), "spring-angular"))
                .diagnostics().stream().anyMatch(d -> d.contains("unsupported Angular component")));
    }

    @Test void cleanAssetTicketConsumerCompilesWithMethodSecurityEnabled() throws Exception {
        compileConsumer("AssetTicket", "asset-ticket-api", "openapi-fixture");
    }

    @Test void cleanServiceRequestConsumerCompilesWithDateDto() throws Exception {
        compileConsumer("ServiceRequest", "service-request-api", "openapi-second-fixture");
    }

    private void compileConsumer(String entity, String contract, String fixtureDirectory) throws Exception {
        ObjectNode snapshot = bindings(entity);
        Map<UUID, String> selected = new LinkedHashMap<>();
        Map<String, Set<SupportedRole>> roles = new LinkedHashMap<>();
        String[] methods = {"list" + entity + "s", "create" + entity, "get" + entity + "ById", "update" + entity, "delete" + entity};
        for (int i = 0; i < 5; i++) { selected.put(new UUID(0, i + 1), methods[i]); roles.put(methods[i], EnumSet.of(SupportedRole.ROLE_ADMIN, SupportedRole.ROLE_USER)); }
        var artifacts = GenerationEngine.templateOnly(builder).generate(version("1.0.0", snapshot),
                spec(entity, contract, selected, roles), Set.of(Target.ENTITY, Target.CONTROLLER), "spring-angular").artifacts();
        List<Path> sources = new ArrayList<>();
        for (var artifact : artifacts) {
            Path destination = temporaryDirectory.resolve(artifact.relativePath());
            Files.createDirectories(destination.getParent()); Files.write(destination, artifact.bytes()); sources.add(destination);
        }
        Path service = temporaryDirectory.resolve("src/main/java/com/example/fixture/service/" + entity + "Service.java");
        Files.createDirectories(service.getParent());
        Files.writeString(service, ("""
                package com.example.fixture.service;
                import java.util.UUID;
                import com.example.fixture.dto.*;
                public interface %sService {
                    %sDto create(Create%sRequest request);
                    %sDto getById(UUID id);
                    %sPage list(%sStatus status);
                    %sDto update(UUID id, Update%sRequest request);
                    void delete(UUID id);
                }
                """).formatted(entity, entity, entity, entity, entity, entity, entity, entity));
        sources.add(service);
        Path methodSecurity = temporaryDirectory.resolve("src/main/java/com/example/fixture/ConsumerMethodSecurity.java");
        Files.writeString(methodSecurity, """
                package com.example.fixture;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
                @Configuration @EnableMethodSecurity
                public class ConsumerMethodSecurity { }
                """);
        sources.add(methodSecurity);
        Path generated = Path.of("target/generated-sources/" + fixtureDirectory + "/src/main/java");
        assertTrue(Files.isDirectory(generated), "Generate the consumer fixtures with -Popenapi-fixture,openapi-second-fixture");
        try (var files = Files.walk(generated)) { files.filter(p -> p.toString().endsWith(".java")).forEach(sources::add); }
        String classpath = Arrays.stream(System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .filter(p -> !p.replace('\\', '/').endsWith("/target/classes") && !p.replace('\\', '/').endsWith("/target/test-classes"))
                .collect(java.util.stream.Collectors.joining(File.pathSeparator));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            boolean compiled = compiler.getTask(null, files, diagnostics,
                    List.of("--release", "21", "-proc:none", "-classpath", classpath, "-d", temporaryDirectory.toString()),
                    null, files.getJavaFileObjectsFromPaths(sources)).call();
            assertTrue(compiled, () -> diagnostics.getDiagnostics().toString());
        }
        assertTrue(Files.exists(temporaryDirectory.resolve("com/example/fixture/controller/" + entity + "Controller.class")));
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{temporaryDirectory.toUri().toURL()}, getClass().getClassLoader())) {
            verifyConsumerMethodSecurity(entity, loader);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void verifyConsumerMethodSecurity(String entity, ClassLoader loader) throws Exception {
        Class<?> controllerType = loader.loadClass("com.example.fixture.controller." + entity + "Controller");
        Class<?> serviceType = loader.loadClass("com.example.fixture.service." + entity + "Service");
        Object service = Proxy.newProxyInstance(loader, new Class<?>[]{serviceType}, (proxy, method, args) -> null);
        Object bareController = controllerType.getConstructor(serviceType).newInstance(service);
        String[] operationNames = {"list" + entity + "s", "create" + entity, "get" + entity + "ById", "update" + entity, "delete" + entity};
        for (String name : operationNames) {
            var method = Arrays.stream(controllerType.getMethods()).filter(candidate -> candidate.getName().equals(name)).findFirst().orElseThrow();
            assertNotNull(method.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class), name);
        }
        var delete = controllerType.getMethod("delete" + entity, UUID.class);
        try {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("reader", "",
                    List.of(new SimpleGrantedAuthority("ROLE_OTHER"))));
            assertNotNull(delete.invoke(bareController, UUID.randomUUID()), "Without method security, an annotation alone does not enforce access");
        } finally { SecurityContextHolder.clearContext(); }
        Class<?> config = loader.loadClass("com.example.fixture.ConsumerMethodSecurity");
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.setClassLoader(loader);
            context.register((Class) config);
            context.registerBean("generatedController", (Class) controllerType, () -> bareController);
            context.refresh();
            Object secured = context.getBean("generatedController");
            for (String name : operationNames) {
                var method = Arrays.stream(controllerType.getMethods()).filter(candidate -> candidate.getName().equals(name)).findFirst().orElseThrow();
                var securedMethod = secured.getClass().getMethod(name, method.getParameterTypes());
                Object[] args = Arrays.stream(method.getParameterTypes())
                        .map(type -> type == UUID.class ? UUID.randomUUID() : null).toArray();
                for (String role : List.of("ROLE_ADMIN", "ROLE_USER")) {
                    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("reader", "",
                            List.of(new SimpleGrantedAuthority(role))));
                    assertNotNull(securedMethod.invoke(secured, args), name + " must allow " + role);
                }
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("reader", "",
                        List.of(new SimpleGrantedAuthority("ROLE_OTHER"))));
                InvocationTargetException denied = assertThrows(InvocationTargetException.class,
                        () -> securedMethod.invoke(secured, args), name);
                assertInstanceOf(AccessDeniedException.class, denied.getCause());
            }
        } finally { SecurityContextHolder.clearContext(); }
    }

    private ObjectNode emptySnapshot() {
        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.set("components", mapper.createArrayNode()); snapshot.set("apiBindings", mapper.createArrayNode());
        return snapshot;
    }

    private void stageAngularForCompilation(GenerationResult result) throws Exception {
        Path harness = Path.of("target/angular-harness");
        Files.createDirectories(harness);
        for (String file : List.of("package.json", "package-lock.json", "tsconfig.json"))
            Files.copy(Path.of("src/test/angular-harness", file), harness.resolve(file), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        for (var artifact : result.artifacts()) {
            Path destination = harness.resolve(artifact.relativePath());
            Files.createDirectories(destination.getParent()); Files.write(destination, artifact.bytes());
        }
        String npm = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "npm.cmd" : "npm";
        if (!Files.isDirectory(harness.resolve("node_modules/@angular/compiler-cli"))) {
            Process install = new ProcessBuilder(npm, "ci", "--offline", "--ignore-scripts", "--no-audit", "--no-fund")
                    .directory(harness.toFile()).redirectErrorStream(true).start();
            String installOutput = new String(install.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, install.waitFor(), "Pinned Angular dependencies unavailable in the local npm cache: " + installOutput);
        }
        Process process = new ProcessBuilder(npm, "run", "check")
                .directory(harness.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }

    private ObjectNode bindings(String entity) {
        ObjectNode snapshot = emptySnapshot();
        String path = entity.equals("AssetTicket") ? "asset-tickets" : "service-requests";
        String[] verbs = {"GET", "POST", "GET", "PUT", "DELETE"};
        for (int i = 0; i < 5; i++) {
            ObjectNode b = snapshot.withArray("apiBindings").addObject();
            b.put("id", new UUID(0, i + 1).toString()); b.put("httpMethod", verbs[i]);
            b.put("endpointUrl", "/api/v1/" + path + (i > 1 ? "/{id}" : "")); b.putObject("headers");
            ObjectNode request = b.putObject("requestMapping");
            if (i == 0) request.putObject("query").put("status", "status");
            if (i > 1) request.putObject("path").put("id", "id");
            if (i == 1 || i == 3) {
                ObjectNode body = request.putObject("body");
                body.put("title", "title").put("status", "status");
                if (entity.equals("ServiceRequest")) body.put("dueDate", "dueDate");
            }
            ObjectNode response = b.putObject("responseMapping");
            if (i == 0) response.put("list", "body.data"); else response.put("item", "body");
        }
        return snapshot;
    }

    private GenerationSpecification spec(String entity, String contract, Map<UUID, String> bindings,
                                         Map<String, Set<SupportedRole>> roles) {
        List<FieldSpec> fields = new ArrayList<>();
        fields.add(new FieldSpec("title", "title", "String", false, 100, null));
        fields.add(new FieldSpec("status", "status", entity + "Status", false, null, entity + "Status"));
        if (entity.equals("ServiceRequest")) fields.add(new FieldSpec("dueDate", "due_date", "LocalDate", false, null, null));
        return new GenerationSpecification("1.0.0", "com.example.fixture",
                new EntitySpec(entity, entity.equals("ServiceRequest") ? "service_request" : "asset_ticket", fields, false),
                new OpenApiSpec(contract, "1.0.0", bindings), roles, new AngularSpec(entity, Map.of()));
    }

    private MetadataVersion version(String schema, ObjectNode snapshot) throws Exception {
        GsuifProject project = new GsuifProject(); project.setName("Fixture");
        GsuifPage page = new GsuifPage(); page.setProject(project);
        MetadataVersion version = new MetadataVersion(); version.setPage(page);
        version.setSchemaVersion(schema); version.setSnapshot(mapper.writeValueAsString(snapshot));
        return version;
    }
}
