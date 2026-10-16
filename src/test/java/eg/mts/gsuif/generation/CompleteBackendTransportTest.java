package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import eg.mts.gsuif.generation.GenerationCapabilityPreflight.OutputMode;
import eg.mts.gsuif.generation.GenerationFilePlan.Entry;
import eg.mts.gsuif.generation.GenerationFilePlan.Owner;
import eg.mts.gsuif.generation.GenerationSpecification.*;
import eg.mts.gsuif.validator.MetadataSchemaValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.TreeMap;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.junit.jupiter.api.Assertions.*;

class CompleteBackendTransportTest {
    private final CompleteOutputTestFixture fixture = new CompleteOutputTestFixture();

    @Configuration
    @EnableMethodSecurity(proxyTargetClass = true)
    static class GeneratedMethodSecurityOnly { }

    @Test void apiAndControllerUseExactOperationsAndOwnedDeterministicBytes() throws Exception {
        var descriptor = fixture.preflight().describe(fixture.version(fixture.snapshot("condition")), fixture.specification(), OutputMode.BACKEND);
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var renderer = new CompleteBackendApiRenderer();
        var files = renderer.render(descriptor, plan);
        assertEquals(2, files.size());
        String api = new String(files.values().stream().filter(bytes -> new String(bytes, StandardCharsets.UTF_8)
                .contains("public interface")).findFirst().orElseThrow(), StandardCharsets.UTF_8);
        String controller = new String(files.values().stream().filter(bytes -> new String(bytes, StandardCharsets.UTF_8)
                .contains("class StockUnitController")).findFirst().orElseThrow(), StandardCharsets.UTF_8);
        for (String id : fixture.ids) assertTrue(api.contains(id + "("), id);
        assertTrue(api.contains("RequestMethod.GET, path = \"/api/v1/stock-units\""));
        assertTrue(api.contains("RequestMethod.POST, path = \"/api/v1/stock-units\""));
        assertTrue(api.contains("RequestMethod.PUT, path = \"/api/v1/stock-units/{id}\""));
        assertTrue(api.contains("RequestMethod.DELETE, path = \"/api/v1/stock-units/{id}\""));
        assertTrue(api.contains("RequestMethod.POST, path = \"/api/v1/stock-units\", produces = \"application/json\", consumes = \"application/json\""));
        assertTrue(api.contains("RequestMethod.PUT, path = \"/api/v1/stock-units/{id}\", produces = \"application/json\", consumes = \"application/json\""));
        assertTrue(api.contains("name = \"condition\", required = true"));
        assertTrue(api.contains("name = \"label\", required = false"));
        assertTrue(controller.contains("service.list(condition, label)"));
        assertTrue(controller.contains("service.create(request)"));
        assertTrue(controller.contains("service.getById(id)"));
        assertTrue(controller.contains("service.update(id, request)"));
        assertTrue(controller.contains("service.delete(id)"));
        assertTrue(controller.contains("ApiResponse.<Void>success(null, \"Success\", 200)"));
        assertTrue(controller.contains("status(201)"));
        assertTrue(controller.contains("hasAnyAuthority('ROLE_ADMIN')"));
        assertFalse(api.contains("eg.mts.gsuif"));
        assertFalse(controller.contains("AssetTicket") || controller.contains("Gadget") || controller.contains("WorkOrder"));
        var again = renderer.render(descriptor, plan);
        for (String path : files.keySet()) assertArrayEquals(files.get(path), again.get(path));
        byte original = files.values().iterator().next()[0];
        files.values().iterator().next()[0] ^= 1;
        assertEquals(original, renderer.render(descriptor, plan).values().iterator().next()[0]);
        var missing = new GenerationFilePlan(plan.entries().stream().filter(e -> !e.path().endsWith("StockUnitApi.java")).toList());
        assertThrows(IllegalArgumentException.class, () -> renderer.render(descriptor, missing));
        var wrongOwner = new GenerationFilePlan(plan.entries().stream().map(e -> e.path().endsWith("StockUnitController.java")
                ? new Entry(e.path(), Owner.MODEL, e.purpose()) : e).toList());
        assertThrows(IllegalArgumentException.class, () -> renderer.render(descriptor, wrongOwner));
        var extra = new TreeMap<>(again);
        extra.put("backend/Unexpected.java", new byte[] {1});
        assertThrows(IllegalArgumentException.class, () -> CompleteBackendApiRenderer.validate(plan, again.keySet(), extra));
    }

    @Test void transportRolesComeOnlyFromFrozenDescriptor() throws Exception {
        var base = fixture.specification();
        var roles = new TreeMap<>(base.operationRoles());
        roles.put("inspectStockUnit", Set.of(SupportedRole.ROLE_USER));
        var changed = new GenerationSpecification(base.specVersion(), base.basePackage(), base.entity(),
                base.openApi(), roles, base.angular());
        var descriptor = fixture.preflight().describe(fixture.version(fixture.snapshot("condition")), changed, OutputMode.BACKEND);
        roles.put("inspectStockUnit", Set.of(SupportedRole.ROLE_ADMIN));
        String controller = new String(new CompleteBackendApiRenderer()
                .render(descriptor, GenerationFilePlan.forDescriptor(descriptor)).entrySet().stream()
                .filter(e -> e.getKey().endsWith("Controller.java")).findFirst().orElseThrow().getValue(),
                StandardCharsets.UTF_8);
        assertTrue(controller.contains("hasAnyAuthority('ROLE_USER')\")\n    @com.example.inventory.aspect.Loggable\n    public org.springframework.http.ResponseEntity<com.example.inventory.api.ApiResponse<com.example.inventory.dto.StockUnitDto>> inspectStockUnit"));
        assertTrue(controller.contains("hasAnyAuthority('ROLE_ADMIN')"));
    }

    @Test void generatedControllerDelegatesAndPreservesSuccessEnvelopeAndDeclaredRoles(@TempDir Path scratch) throws Exception {
        var descriptor = fixture.preflight().describe(fixture.version(fixture.snapshot("condition")), fixture.specification(), OutputMode.BACKEND);
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var source = new TreeMap<>(new CompleteBackendDefinitionRenderer().render(descriptor, plan).files());
        source.putAll(new CompleteBackendApiRenderer().render(descriptor, plan));
        source.putAll(new CompleteBackendObservabilityRenderer().render(descriptor, plan).entrySet().stream()
                .filter(e -> e.getKey().endsWith("Loggable.java")).collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        Path classes = Files.createDirectories(scratch.resolve("classes"));
        List<String> inputs = new ArrayList<>();
        for (var entry : source.entrySet()) {
            Path file = scratch.resolve(entry.getKey());
            Files.createDirectories(file.getParent()); Files.write(file, entry.getValue()); inputs.add(file.toString());
        }
        List<String> args = new ArrayList<>(List.of("-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString()));
        args.addAll(inputs);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
            Class<?> serviceType = loader.loadClass("com.example.inventory.service.StockUnitService");
            Class<?> controllerType = loader.loadClass("com.example.inventory.controller.StockUnitController");
            Class<?> apiType = loader.loadClass("com.example.inventory.api.StockUnitApi");
            Class<?> dtoType = loader.loadClass("com.example.inventory.dto.StockUnitDto");
            Class<?> pageType = loader.loadClass("com.example.inventory.dto.StockUnitPage");
            Class<?> conditionType = loader.loadClass("com.example.inventory.dto.StockUnitCondition");
            Object condition = conditionType.getEnumConstants()[0];
            Object dto = dtoType.getConstructors()[0].newInstance(UUID.randomUUID(), "sample", condition, null);
            Object page = pageType.getConstructors()[0].newInstance(List.of(dto), 1, 1L, 1, 0);
            AtomicReference<String> call = new AtomicReference<>();
            AtomicInteger serviceCalls = new AtomicInteger();
            Object service = Proxy.newProxyInstance(loader, new Class<?>[]{serviceType}, (proxy, method, values) -> {
                serviceCalls.incrementAndGet();
                call.set(method.getName() + ":" + (values == null ? 0 : values.length));
                return method.getName().equals("list") ? page : method.getName().equals("delete") ? null : dto;
            });
            try (var context = new AnnotationConfigApplicationContext()) {
                context.register(GeneratedMethodSecurityOnly.class);
                context.registerBean((Class) controllerType, () -> {
                    try { return controllerType.getConstructor(serviceType).newInstance(service); }
                    catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
                });
                context.refresh();
                Object controller = context.getBean(apiType);
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        "admin", "unused", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
                String[] serviceMethods = {"list:2", "create:1", "getById:1", "update:2", "delete:1"};
                Object[][] values = {{condition, "a"}, {null}, {UUID.randomUUID()}, {UUID.randomUUID(), null}, {UUID.randomUUID()}};
                for (int i = 0; i < fixture.ids.size(); i++) {
                    String operationId = fixture.ids.get(i);
                    var method = java.util.Arrays.stream(apiType.getMethods())
                            .filter(m -> m.getName().equals(operationId)).findFirst().orElseThrow();
                    ResponseEntity<?> response = (ResponseEntity<?>) method.invoke(controller, values[i]);
                    assertEquals(i == 1 ? 201 : 200, response.getStatusCode().value());
                    assertEquals(serviceMethods[i], call.get());
                    Object envelope = response.getBody();
                    assertEquals(5, envelope.getClass().getRecordComponents().length);
                    assertEquals(response.getStatusCode().value(), envelope.getClass().getMethod("statusCode").invoke(envelope));
                    assertEquals("OK", envelope.getClass().getMethod("status").invoke(envelope));
                    assertNull(envelope.getClass().getMethod("errors").invoke(envelope));
                    assertEquals(i == 4 ? null : i == 0 ? page : dto,
                            envelope.getClass().getMethod("body").invoke(envelope));
                }
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        "user", "unused", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
                var read = java.util.Arrays.stream(apiType.getMethods())
                        .filter(m -> m.getName().equals("inspectStockUnit")).findFirst().orElseThrow();
                int callsBeforeDenied = serviceCalls.get();
                var denied = assertThrows(java.lang.reflect.InvocationTargetException.class,
                        () -> read.invoke(controller, UUID.randomUUID()));
                assertInstanceOf(AccessDeniedException.class, denied.getCause());
                assertEquals(callsBeforeDenied, serviceCalls.get(), "Denied call must not reach service");
            } finally { SecurityContextHolder.clearContext(); }
            } finally { Thread.currentThread().setContextClassLoader(previousLoader); }
        }
    }

    @Test void mockMvcInheritsInterfaceMappingsAndBindsTemporalQueryArguments(@TempDir Path scratch) throws Exception {
        var registry = new PackagedContractRegistry("/generation/stock-unit-temporal-contracts.yaml");
        var preflight = new GenerationCapabilityPreflight(
                new GenerationContextBuilder(fixture.mapper, new MetadataSchemaValidator(), registry), registry);
        var metadata = fixture.snapshot("condition");
        ObjectNode query = (ObjectNode) metadata.withArray("apiBindings").get(0).path("requestMapping").path("query");
        query.put("observedOn", "observedOn");
        query.put("changedAt", "changedAt");
        var descriptor = preflight.describe(fixture.version(metadata), fixture.specification(), OutputMode.BACKEND);
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var source = new TreeMap<>(new CompleteBackendDefinitionRenderer().render(descriptor, plan).files());
        source.putAll(new CompleteBackendApiRenderer().render(descriptor, plan));
        source.putAll(new CompleteBackendObservabilityRenderer().render(descriptor, plan).entrySet().stream()
                .filter(e -> e.getKey().endsWith("Loggable.java")).collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        String api = new String(source.entrySet().stream().filter(e -> e.getKey().endsWith("StockUnitApi.java"))
                .findFirst().orElseThrow().getValue(), StandardCharsets.UTF_8);
        assertTrue(api.contains("DateTimeFormat.ISO.DATE)"));
        assertTrue(api.contains("DateTimeFormat.ISO.DATE_TIME)"));
        Path classes = Files.createDirectories(scratch.resolve("classes"));
        List<String> inputs = new ArrayList<>();
        for (var entry : source.entrySet()) {
            Path file = scratch.resolve(entry.getKey());
            Files.createDirectories(file.getParent()); Files.write(file, entry.getValue()); inputs.add(file.toString());
        }
        List<String> arguments = new ArrayList<>(List.of("-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString()));
        arguments.addAll(inputs);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)));
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                Class<?> serviceType = loader.loadClass("com.example.inventory.service.StockUnitService");
                Class<?> controllerType = loader.loadClass("com.example.inventory.controller.StockUnitController");
                Class<?> pageType = loader.loadClass("com.example.inventory.dto.StockUnitPage");
                Class<?> conditionType = loader.loadClass("com.example.inventory.dto.StockUnitCondition");
                Object page = pageType.getConstructors()[0].newInstance(List.of(), 0, 0L, 0, 0);
                AtomicReference<Object[]> received = new AtomicReference<>();
                AtomicInteger calls = new AtomicInteger();
                Object service = Proxy.newProxyInstance(loader, new Class<?>[]{serviceType}, (proxy, method, values) -> {
                    calls.incrementAndGet();
                    received.set(values == null ? new Object[0] : values.clone());
                    return method.getName().equals("list") ? page : null;
                });
                Object controller = controllerType.getConstructor(serviceType).newInstance(service);
                var mvc = MockMvcBuilders.standaloneSetup(controller).build();
                mvc.perform(get("/api/v1/stock-units").param("condition", "OPEN"))
                        .andExpect(status().isBadRequest());
                assertEquals(0, calls.get());
                mvc.perform(get("/api/v1/stock-units").param("condition", "OPEN")
                                .param("observedOn", "2026-10-15"))
                        .andExpect(status().isOk());
                assertEquals(1, calls.get());
                assertArrayEquals(new Object[]{null, conditionType.getEnumConstants()[0], null,
                        LocalDate.of(2026, 10, 15)}, received.get());
                mvc.perform(get("/api/v1/stock-units").param("condition", "CLOSED")
                                .param("label", "warehouse").param("observedOn", "2026-10-16")
                                .param("changedAt", "2026-10-15T12:34:56"))
                        .andExpect(status().isOk());
                assertEquals(2, calls.get());
                assertArrayEquals(new Object[]{LocalDateTime.of(2026, 10, 15, 12, 34, 56),
                        conditionType.getEnumConstants()[1], "warehouse", LocalDate.of(2026, 10, 16)}, received.get());
                mvc.perform(get("/api/v1/stock-units").param("condition", "OPEN")
                                .param("observedOn", "15-10-2026"))
                        .andExpect(status().isBadRequest());
                assertEquals(2, calls.get());
            } finally { Thread.currentThread().setContextClassLoader(previousLoader); }
        }
    }

    @Test void emittedRequestsDistinguishRequiredPresenceAndNullWithPinnedJackson(@TempDir Path scratch) throws Exception {
        var descriptor = fixture.preflight().describe(fixture.version(fixture.snapshot("condition")), fixture.specification(), OutputMode.BACKEND);
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var files = new TreeMap<>(new CompleteBackendDefinitionRenderer().render(descriptor, plan).files());
        files.putAll(new CompleteBackendApiRenderer().render(descriptor, plan));
        files.putAll(new CompleteBackendObservabilityRenderer().render(descriptor, plan).entrySet().stream()
                .filter(e -> e.getKey().endsWith("Loggable.java")).collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        Path classes = Files.createDirectories(scratch.resolve("classes"));
        List<String> sources = new ArrayList<>();
        for (var entry : files.entrySet()) {
            Path file = scratch.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
            sources.add(file.toString());
        }
        List<String> compiler = new ArrayList<>(List.of("-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString()));
        compiler.addAll(sources);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, compiler.toArray(String[]::new)));
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                var json = tools.jackson.databind.json.JsonMapper.builder().build();
                var validator = new org.springframework.validation.beanvalidation.LocalValidatorFactoryBean();
                validator.afterPropertiesSet();
                try {
                    for (String requestName : List.of("CreateStockUnitRequest", "UpdateStockUnitRequest")) {
                        Class<?> requestType = loader.loadClass("com.example.inventory.dto." + requestName);
                        Object explicitNull = json.readValue("{\"label\":\"A\",\"condition\":\"OPEN\",\"retiredOn\":null}", requestType);
                        assertNull(requestType.getMethod("retiredOn").invoke(explicitNull));
                        assertTrue(validator.validate(explicitNull).isEmpty());
                        assertThrows(tools.jackson.core.JacksonException.class,
                                () -> json.readValue("{\"label\":\"A\",\"condition\":\"OPEN\"}", requestType),
                                "Required nullable field must reject omission");
                        for (String missing : List.of(
                                "{\"condition\":\"OPEN\",\"retiredOn\":null}",
                                "{\"label\":\"A\",\"retiredOn\":null}"))
                            assertThrows(tools.jackson.core.JacksonException.class,
                                    () -> json.readValue(missing, requestType));
                        for (var fieldAndJson : Map.of(
                                "label", "{\"label\":null,\"condition\":\"OPEN\",\"retiredOn\":null}",
                                "condition", "{\"label\":\"A\",\"condition\":null,\"retiredOn\":null}").entrySet()) {
                            Object nullNonnullable = json.readValue(fieldAndJson.getValue(), requestType);
                            assertTrue(validator.validate(nullNonnullable).stream().anyMatch(v ->
                                    v.getPropertyPath().toString().equals(fieldAndJson.getKey())));
                        }
                    }
                    Class<?> serviceType = loader.loadClass("com.example.inventory.service.StockUnitService");
                    Class<?> controllerType = loader.loadClass("com.example.inventory.controller.StockUnitController");
                    AtomicInteger calls = new AtomicInteger();
                    AtomicReference<Object[]> received = new AtomicReference<>();
                    Object service = Proxy.newProxyInstance(loader, new Class<?>[]{serviceType}, (proxy, method, args) -> {
                        calls.incrementAndGet();
                        received.set(args == null ? null : args.clone());
                        return null;
                    });
                    Object controller = controllerType.getConstructor(serviceType).newInstance(service);
                    var mvc = MockMvcBuilders.standaloneSetup(controller).setValidator(validator)
                            .setMessageConverters(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter(json))
                            .build();
                    String valid = "{\"label\":\"A\",\"condition\":\"OPEN\",\"retiredOn\":null}";
                    String omittedNullable = "{\"label\":\"A\",\"condition\":\"OPEN\"}";
                    String missingRequired = "{\"condition\":\"OPEN\",\"retiredOn\":null}";
                    String nullRequired = "{\"label\":null,\"condition\":\"OPEN\",\"retiredOn\":null}";
                    String missingCondition = "{\"label\":\"A\",\"retiredOn\":null}";
                    String nullCondition = "{\"label\":\"A\",\"condition\":null,\"retiredOn\":null}";
                    for (String path : List.of("/api/v1/stock-units", "/api/v1/stock-units/00000000-0000-0000-0000-000000000001")) {
                        boolean create = !path.contains("/00000000-");
                        int callsBeforePath = calls.get();
                        for (String invalid : List.of(omittedNullable, missingRequired, nullRequired,
                                missingCondition, nullCondition)) {
                            var request = create ? org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                                    : org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path);
                            mvc.perform(request.contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(invalid))
                                    .andExpect(status().isBadRequest());
                            assertEquals(callsBeforePath, calls.get(), "Rejected request must not invoke the service");
                        }
                        var request = create ? org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                                : org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path);
                        mvc.perform(request.contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(valid))
                                .andExpect(create ? status().isCreated() : status().isOk());
                        assertEquals(callsBeforePath + 1, calls.get());
                        Object accepted = received.get()[create ? 0 : 1];
                        assertNull(accepted.getClass().getMethod("retiredOn").invoke(accepted));
                    }
                } finally { validator.close(); }
            } finally { Thread.currentThread().setContextClassLoader(previous); }
        }
    }

}
