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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.junit.jupiter.api.Assertions.*;

class CompleteBackendErrorTest {
    private final CompleteOutputTestFixture fixture = new CompleteOutputTestFixture();

    @Test void errorRendererOwnsOnlyItsSubsetAndUsesSelectedContractFields(@TempDir Path scratch) throws Exception {
        var descriptor = fixture.preflight().describe(fixture.version(fixture.snapshot("condition")), fixture.specification(), OutputMode.BACKEND);
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var handlerPath = "backend/src/main/java/com/example/inventory/exception/GlobalExceptionHandler.java";
        var missingPath = "backend/src/main/java/com/example/inventory/exception/RequestedRecordNotFoundException.java";
        assertFalse(plan.entries().stream().anyMatch(e -> e.path().endsWith("api/GlobalExceptionHandler.java")));
        assertEquals(Owner.DETERMINISTIC, plan.entries().stream().filter(e -> e.path().equals(handlerPath))
                .findFirst().orElseThrow().owner());
        var renderer = new CompleteBackendErrorRenderer();
        var files = renderer.render(descriptor, plan);
        assertEquals(Set.of(handlerPath, missingPath), files.keySet());
        String handler = new String(files.get(handlerPath), StandardCharsets.UTF_8);
        assertTrue(handler.contains("package com.example.inventory.exception;"));
        assertTrue(handler.contains("import com.example.inventory.api.ApiResponse;"));
        assertTrue(handler.contains("\"condition\"") && handler.contains("\"retiredOn\""));
        assertFalse(handler.contains("AssetTicket") || handler.contains("WorkOrder") || handler.contains("Gadget"));
        assertFalse(handler.contains("\r"));
        String serviceDefinition = new String(new CompleteBackendDefinitionRenderer().render(descriptor, plan).files()
                .entrySet().stream().filter(e -> e.getKey().endsWith("StockUnitService.java"))
                .findFirst().orElseThrow().getValue(), StandardCharsets.UTF_8);
        assertTrue(serviceDefinition.contains("RequestedRecordNotFoundException"));
        for (String path : files.keySet()) assertArrayEquals(files.get(path), renderer.render(descriptor, plan).get(path));
        byte original = files.get(handlerPath)[0];
        files.get(handlerPath)[0] ^= 1;
        assertEquals(original, renderer.render(descriptor, plan).get(handlerPath)[0]);
        assertThrows(UnsupportedOperationException.class, files::clear);
        var wrongOwner = new GenerationFilePlan(plan.entries().stream().map(e -> e.path().equals(handlerPath)
                ? new Entry(e.path(), Owner.MODEL, e.purpose()) : e).toList());
        assertThrows(IllegalArgumentException.class, () -> renderer.render(descriptor, wrongOwner));
        var absent = new GenerationFilePlan(plan.entries().stream().filter(e -> !e.path().equals(missingPath)).toList());
        assertThrows(IllegalArgumentException.class, () -> renderer.render(descriptor, absent));

        var gadgetRegistry = new PackagedContractRegistry("/generation/gadget-contracts.yaml");
        var gadgetPreflight = new GenerationCapabilityPreflight(
                new GenerationContextBuilder(fixture.mapper, new MetadataSchemaValidator(), gadgetRegistry), gadgetRegistry);
        var names = List.of("fetchGadgets", "submitGadget", "findGadget", "replaceGadget", "removeGadget");
        Map<UUID, String> bindings = new java.util.LinkedHashMap<>();
        Map<String, Set<SupportedRole>> roles = new java.util.LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            bindings.put(new UUID(0, i + 1), names.get(i));
            roles.put(names.get(i), Set.of(SupportedRole.ROLE_ADMIN));
        }
        var spec = new GenerationSpecification("1.0.0", "com.example.fixture",
                new EntitySpec("Gadget", "gadget", List.of(
                        new FieldSpec("title", "title", "String", false, 100, null),
                        new FieldSpec("status", "status", "GadgetStatus", false, null, "GadgetStatus"),
                        new FieldSpec("note", "note", "String", true, 100, null)), false),
                new OpenApiSpec("gadget-api", "2.0.0", bindings), roles, new AngularSpec("Gadget", Map.of()));
        var metadata = fixture.snapshot("condition");
        for (int i = 0; i < 5; i++) {
            var binding = (ObjectNode) metadata.withArray("apiBindings").get(i);
            binding.put("endpointUrl", binding.path("endpointUrl").asText().replace("stock-units", "gadgets"));
            if (i == 0) {
                var query = (ObjectNode) binding.path("requestMapping").path("query");
                query.removeAll(); query.put("status", "status");
            }
            if (i == 1 || i == 3) {
                var body = (ObjectNode) binding.path("requestMapping").path("body");
                body.removeAll(); body.put("title", "title").put("status", "status").put("note", "note");
            }
        }
        var gadget = gadgetPreflight.describe(fixture.version(metadata), spec, OutputMode.BACKEND);
        var gadgetFiles = renderer.render(gadget, GenerationFilePlan.forDescriptor(gadget));
        String gadgetHandler = new String(gadgetFiles.entrySet().stream()
                .filter(e -> e.getKey().endsWith("GlobalExceptionHandler.java")).findFirst().orElseThrow().getValue(),
                StandardCharsets.UTF_8);
        assertTrue(gadgetHandler.contains("package com.example.fixture.exception;"));
        assertTrue(gadgetHandler.contains("\"title\"") && gadgetHandler.contains("\"status\""));
        assertFalse(gadgetHandler.contains("\"condition\"") || gadgetHandler.contains("\"retiredOn\""));
        var gadgetSources = new TreeMap<>(new CompleteBackendDefinitionRenderer()
                .render(gadget, GenerationFilePlan.forDescriptor(gadget)).files());
        gadgetSources.putAll(new CompleteBackendApiRenderer().render(gadget, GenerationFilePlan.forDescriptor(gadget)));
        gadgetSources.putAll(gadgetFiles);
        Path classes = Files.createDirectories(scratch.resolve("classes"));
        List<String> sourcePaths = new ArrayList<>();
        for (var entry : gadgetSources.entrySet()) {
            Path file = scratch.resolve(entry.getKey());
            Files.createDirectories(file.getParent()); Files.write(file, entry.getValue()); sourcePaths.add(file.toString());
        }
        List<String> compiler = new ArrayList<>(List.of("-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString()));
        compiler.addAll(sourcePaths);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, compiler.toArray(String[]::new)));
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                Class<?> serviceType = loader.loadClass("com.example.fixture.service.GadgetService");
                Class<?> controllerType = loader.loadClass("com.example.fixture.controller.GadgetController");
                Class<?> handlerType = loader.loadClass("com.example.fixture.exception.GlobalExceptionHandler");
                AtomicInteger calls = new AtomicInteger();
                Object service = Proxy.newProxyInstance(loader, new Class<?>[]{serviceType}, (proxy, method, args) -> {
                    calls.incrementAndGet(); return null;
                });
                var validator = new org.springframework.validation.beanvalidation.LocalValidatorFactoryBean();
                validator.afterPropertiesSet();
                try {
                    var json = tools.jackson.databind.json.JsonMapper.builder().build();
                    var mvc = MockMvcBuilders.standaloneSetup(controllerType.getConstructor(serviceType).newInstance(service))
                            .setControllerAdvice(handlerType.getConstructor().newInstance()).setValidator(validator)
                            .setMessageConverters(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter(json))
                            .build();
                    var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/v1/gadgets").contentType("application/json")
                            .content("{\"title\":null,\"status\":\"OPEN\",\"note\":null}"))
                            .andExpect(status().isBadRequest()).andReturn().getResponse();
                    assertGeneratedError(json, response, 400, "BAD_REQUEST", "Invalid request", "title", "Invalid value");
                    assertEquals(0, calls.get());
                } finally { validator.close(); }
            } finally { Thread.currentThread().setContextClassLoader(previous); }
        }
    }

    @Test void emittedErrorHandlerReturnsSafeExactEnvelopes(@TempDir Path scratch) throws Exception {
        var descriptor = fixture.preflight().describe(fixture.version(fixture.snapshot("condition")), fixture.specification(), OutputMode.BACKEND);
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var files = new TreeMap<>(new CompleteBackendDefinitionRenderer().render(descriptor, plan).files());
        files.putAll(new CompleteBackendApiRenderer().render(descriptor, plan));
        files.putAll(new CompleteBackendErrorRenderer().render(descriptor, plan));
        Path classes = Files.createDirectories(scratch.resolve("classes"));
        List<String> sources = new ArrayList<>();
        for (var entry : files.entrySet()) {
            Path file = scratch.resolve(entry.getKey());
            Files.createDirectories(file.getParent()); Files.write(file, entry.getValue()); sources.add(file.toString());
        }
        List<String> compiler = new ArrayList<>(List.of("-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString()));
        compiler.addAll(sources);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, compiler.toArray(String[]::new)));
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                Class<?> serviceType = loader.loadClass("com.example.inventory.service.StockUnitService");
                Class<?> controllerType = loader.loadClass("com.example.inventory.controller.StockUnitController");
                Class<?> handlerType = loader.loadClass("com.example.inventory.exception.GlobalExceptionHandler");
                Class<?> missingType = loader.loadClass("com.example.inventory.exception.RequestedRecordNotFoundException");
                AtomicInteger calls = new AtomicInteger();
                AtomicReference<RuntimeException> failure = new AtomicReference<>();
                Object service = Proxy.newProxyInstance(loader, new Class<?>[]{serviceType}, (proxy, method, args) -> {
                    calls.incrementAndGet();
                    if (failure.get() != null) throw failure.get();
                    return null;
                });
                Object controller = controllerType.getConstructor(serviceType).newInstance(service);
                var json = tools.jackson.databind.json.JsonMapper.builder().build();
                var validator = new org.springframework.validation.beanvalidation.LocalValidatorFactoryBean();
                validator.afterPropertiesSet();
                try {
                    var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(handlerType.getConstructor().newInstance())
                            .setValidator(validator)
                            .setMessageConverters(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter(json))
                            .build();
                    var badField = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/v1/stock-units").contentType("application/json")
                            .content("{\"label\":null,\"condition\":\"OPEN\",\"retiredOn\":null}"))
                            .andExpect(status().isBadRequest()).andReturn().getResponse();
                    assertGeneratedError(json, badField, 400, "BAD_REQUEST", "Invalid request", "label", "Invalid value");
                    var omitted = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .put("/api/v1/stock-units/00000000-0000-0000-0000-000000000001")
                            .contentType("application/json").content("{\"label\":\"A\",\"condition\":\"OPEN\"}"))
                            .andExpect(status().isBadRequest()).andReturn().getResponse();
                    assertGeneratedError(json, omitted, 400, "BAD_REQUEST", "Invalid request", "_global", "Invalid request body");
                    var missingQuery = mvc.perform(get("/api/v1/stock-units"))
                            .andExpect(status().isBadRequest()).andReturn().getResponse();
                    assertGeneratedError(json, missingQuery, 400, "BAD_REQUEST", "Invalid request",
                            "condition", "Required parameter is missing");
                    var invalidQuery = mvc.perform(get("/api/v1/stock-units").param("condition", "rejected-secret"))
                            .andExpect(status().isBadRequest()).andReturn().getResponse();
                    assertGeneratedError(json, invalidQuery, 400, "BAD_REQUEST", "Invalid request",
                            "condition", "Invalid value");
                    assertFalse(invalidQuery.getContentAsString().contains("rejected-secret"));
                    var malformed = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/v1/stock-units").contentType("application/json").content("{bad-json"))
                            .andExpect(status().isBadRequest()).andReturn().getResponse();
                    assertGeneratedError(json, malformed, 400, "BAD_REQUEST", "Invalid request",
                            "_global", "Invalid request body");
                    assertEquals(0, calls.get(), "Invalid requests must not reach the service");
                    failure.set((RuntimeException) missingType.getConstructor().newInstance());
                    var notFound = mvc.perform(get("/api/v1/stock-units/00000000-0000-0000-0000-000000000001"))
                            .andExpect(status().isNotFound()).andReturn().getResponse();
                    assertGeneratedError(json, notFound, 404, "NOT_FOUND", "Resource not found",
                            "_global", "Resource not found");
                    failure.set(new jakarta.persistence.EntityNotFoundException("persistence secret"));
                    var unrelated = mvc.perform(get("/api/v1/stock-units/00000000-0000-0000-0000-000000000001"))
                            .andExpect(status().isInternalServerError()).andReturn().getResponse();
                    assertGeneratedError(json, unrelated, 500, "INTERNAL_SERVER_ERROR", "Unexpected server error",
                            "_global", "Unexpected server error");
                    assertFalse(unrelated.getContentAsString().contains("persistence secret"));
                    failure.set(new IllegalStateException("provider secret"));
                    var unexpected = mvc.perform(get("/api/v1/stock-units/00000000-0000-0000-0000-000000000001"))
                            .andExpect(status().isInternalServerError()).andReturn().getResponse();
                    assertGeneratedError(json, unexpected, 500, "INTERNAL_SERVER_ERROR", "Unexpected server error",
                            "_global", "Unexpected server error");
                    assertFalse(unexpected.getContentAsString().contains("provider secret"));
                    failure.set(null);
                    var methodNotAllowed = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .patch("/api/v1/stock-units/00000000-0000-0000-0000-000000000001"))
                            .andExpect(status().isMethodNotAllowed()).andReturn().getResponse();
                    assertGeneratedError(json, methodNotAllowed, 405, "METHOD_NOT_ALLOWED", "Method not allowed",
                            "_global", "Method not allowed");
                    String allow = methodNotAllowed.getHeader("Allow");
                    assertNotNull(allow);
                    Set<String> methods = java.util.Arrays.stream(allow.split(","))
                            .map(String::trim).collect(java.util.stream.Collectors.toSet());
                    assertTrue(methods.containsAll(Set.of("GET", "PUT", "DELETE")), allow);
                    assertFalse(methods.contains("PATCH"), allow);
                    var unsupportedMedia = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/v1/stock-units").contentType("text/plain").content("invalid"))
                            .andExpect(status().isUnsupportedMediaType()).andReturn().getResponse();
                    assertGeneratedError(json, unsupportedMedia, 415, "UNSUPPORTED_MEDIA_TYPE", "Unsupported media type",
                            "_global", "Unsupported media type");
                    assertTrue(unsupportedMedia.getHeaders("Accept").stream()
                            .anyMatch(value -> value.contains("application/json")));
                } finally { validator.close(); }
            } finally { Thread.currentThread().setContextClassLoader(previous); }
        }
    }

    private static void assertGeneratedError(tools.jackson.databind.json.JsonMapper json,
                                             org.springframework.mock.web.MockHttpServletResponse response,
                                             int code, String statusName, String message, String key, String value) throws Exception {
        var body = json.readTree(response.getContentAsByteArray());
        assertEquals(5, body.size());
        assertEquals(statusName, body.get("status").asText());
        assertEquals(message, body.get("clientMessage").asText());
        assertEquals(code, body.get("statusCode").asInt());
        assertTrue(body.get("body").isNull());
        assertEquals(1, body.get("errors").size());
        assertEquals(value, body.get("errors").get(key).asText());
    }

}
