package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import eg.mts.gsuif.generation.GenerationCapabilityPreflight.OutputMode;
import eg.mts.gsuif.generation.PackagedContractRegistry.CrudAction;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.net.URLClassLoader;
import java.lang.reflect.Proxy;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.junit.jupiter.api.Assertions.*;

/** Contract and emitted-definition checks; runtime paging has a separate opt-in acceptance test. */
class CompleteBackendPaginationTest {
    private final CompleteOutputTestFixture fixture = new CompleteOutputTestFixture();

    @Test void selectedVersionFreezesPaginationAndEmitsMatchingDefinitionsAndModelRequest() throws Exception {
        var descriptor = fixture.preflight().describe(fixture.version(fixture.pagedSnapshot()),
                fixture.pagedSpecification(), OutputMode.BACKEND);
        assertEquals("3.0.0", descriptor.contractVersion());
        var list = descriptor.operations().stream().filter(op -> op.action() == CrudAction.LIST)
                .findFirst().orElseThrow();
        assertEquals(new PackagedContractLoader.Ordering("id", "ASC"), list.ordering());
        assertEquals(0, list.queryFields().get("page").defaultValue());
        assertEquals(0, list.queryFields().get("page").minimum());
        assertEquals(20, list.queryFields().get("size").defaultValue());
        assertEquals(1, list.queryFields().get("size").minimum());
        assertEquals(100, list.queryFields().get("size").maximum());
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var definitions = new CompleteBackendDefinitionRenderer().render(descriptor, plan);
        var apiFiles = new CompleteBackendApiRenderer().render(descriptor, plan);
        String api = source(apiFiles, "StockUnitApi.java");
        String controller = source(apiFiles, "StockUnitController.java");
        String service = source(definitions.files(), "StockUnitService.java");
        assertTrue(api.contains("name = \"page\", required = false, defaultValue = \"0\""));
        assertTrue(api.contains("name = \"size\", required = false, defaultValue = \"20\""));
        assertTrue(service.contains("Integer page, Integer size"));
        assertTrue(service.contains("Sort by entity id ASC"));
        assertTrue(controller.contains("service.list(condition, label, page, size)"));
        assertTrue(controller.contains("page < 0"));
        assertTrue(controller.contains("size < 1"));
        assertTrue(controller.contains("size > 100"));
        var input = BackendModelInput.from(descriptor, plan, definitions,
                new CompleteBackendRuntimeRenderer(new PackagedContractRegistry("/generation/stock-unit-contracts.yaml")));
        assertEquals(service, new String(input.handoff().definitions().stream()
                .filter(d -> d.path().endsWith("StockUnitService.java")).findFirst().orElseThrow().bytes(),
                StandardCharsets.UTF_8));
        var request = new ObjectMapper().readTree(new OpenRouterBackendModelAdapter((payload, key, timeout) ->
                { throw new AssertionError("No provider request allowed"); },
                (selected, bytes, remaining) -> true, System::nanoTime, ignored -> { }).request(input));
        var instructions = new ObjectMapper().readTree(request.path("messages").get(0).path("content").asText());
        var pagination = java.util.stream.StreamSupport.stream(instructions.path("operations").spliterator(), false)
                .filter(op -> "LIST".equals(op.path("action").asText())).findFirst().orElseThrow()
                .path("pagination");
        assertEquals("id", pagination.path("orderField").asText());
        assertEquals("ASC", pagination.path("orderDirection").asText());
        assertEquals(0, pagination.path("page").path("default").asInt());
        assertEquals(20, pagination.path("size").path("default").asInt());
        assertEquals(100, pagination.path("size").path("maximum").asInt());
        assertTrue(instructions.path("contract").asText().contains("version: 3.0.0"));
    }

    @Test void legacySelectionRemainsExplicitAndUnchanged() throws Exception {
        var legacy = fixture.preflight().describe(fixture.version(fixture.snapshot("condition")),
                fixture.specification(), OutputMode.BACKEND);
        assertEquals("2.0.0", legacy.contractVersion());
        var list = legacy.operations().stream().filter(op -> op.action() == CrudAction.LIST)
                .findFirst().orElseThrow();
        assertNull(list.ordering());
        assertFalse(list.queryFields().containsKey("page"));
        assertFalse(source(new CompleteBackendApiRenderer().render(legacy, GenerationFilePlan.forDescriptor(legacy)),
                "StockUnitApi.java").contains("name = \"page\""));
    }

    @Test void emittedMvcAppliesDefaultsBoundsAndTypedArguments(@TempDir Path scratch) throws Exception {
        var descriptor = fixture.preflight().describe(fixture.version(fixture.pagedSnapshot()),
                fixture.pagedSpecification(), OutputMode.BACKEND);
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var files = new TreeMap<>(new CompleteBackendDefinitionRenderer().render(descriptor, plan).files());
        files.putAll(new CompleteBackendApiRenderer().render(descriptor, plan));
        files.putAll(new CompleteBackendOpenApiRenderer().render(descriptor, plan).entrySet().stream()
                .filter(e -> e.getKey().endsWith("ErrorApiResponse.java"))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        files.putAll(new CompleteBackendObservabilityRenderer().render(descriptor, plan).entrySet().stream()
                .filter(e -> e.getKey().endsWith("Loggable.java"))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        Path classes = Files.createDirectories(scratch.resolve("classes"));
        List<String> sources = new ArrayList<>();
        for (var entry : files.entrySet()) {
            Path file = scratch.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
            sources.add(file.toString());
        }
        List<String> args = new ArrayList<>(List.of("-proc:none", "-classpath",
                System.getProperty("java.class.path"), "-d", classes.toString()));
        args.addAll(sources);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> serviceType = loader.loadClass("com.example.inventory.service.StockUnitService");
            Class<?> controllerType = loader.loadClass("com.example.inventory.controller.StockUnitController");
            Class<?> conditionType = loader.loadClass("com.example.inventory.dto.StockUnitCondition");
            Class<?> pageType = loader.loadClass("com.example.inventory.dto.StockUnitPage");
            Object empty = pageType.getConstructors()[0].newInstance(List.of(), 0, 0L, 20, 0);
            AtomicReference<Object[]> received = new AtomicReference<>();
            AtomicInteger calls = new AtomicInteger();
            Object service = Proxy.newProxyInstance(loader, new Class<?>[]{serviceType}, (proxy, method, values) -> {
                calls.incrementAndGet();
                received.set(values.clone());
                return empty;
            });
            Object controller = controllerType.getConstructor(serviceType).newInstance(service);
            var mvc = MockMvcBuilders.standaloneSetup(controller).build();
            mvc.perform(get("/api/v1/stock-units").param("condition", "OPEN"))
                    .andExpect(status().isOk());
            assertArrayEquals(new Object[]{conditionType.getEnumConstants()[0], null, 0, 20}, received.get());
            mvc.perform(get("/api/v1/stock-units").param("condition", "OPEN")
                    .param("page", "2").param("size", "7"))
                    .andExpect(status().isOk());
            assertArrayEquals(new Object[]{conditionType.getEnumConstants()[0], null, 2, 7}, received.get());
            assertEquals(2, calls.get());
            for (String query : List.of("page=-1", "size=0", "size=101", "page=oops", "size=oops")) {
                String[] parts = query.split("=");
                mvc.perform(get("/api/v1/stock-units").param("condition", "OPEN")
                        .param(parts[0], parts[1])).andExpect(status().isBadRequest());
                assertEquals(2, calls.get(), query + " must not reach the service");
            }
        }
    }

    @Test void unsupportedPaginationParameterShapesAreRejectedBeforeRendering() {
        var schema = Map.<String, Object>of("type", "integer", "format", "int32",
                "default", 0, "minimum", 0, "multipleOf", 2);
        List<String> errors = new ArrayList<>();
        assertTrue(PackagedContractLoader.parseQueryParameters(List.of(Map.of("name", "page",
                "in", "query", "schema", schema)), CrudAction.LIST, Map.of(), "scan", errors).isEmpty());
        assertTrue(errors.stream().anyMatch(error -> error.contains("unsupported parameter schema")));
        errors.clear();
        var referenced = Map.<String, Object>of("$ref", "#/components/schemas/PaginationNumber");
        assertTrue(PackagedContractLoader.parseQueryParameters(List.of(Map.of("name", "size",
                "in", "query", "schema", referenced)), CrudAction.LIST,
                Map.of("PaginationNumber", schema), "scan", errors).isEmpty());
        assertTrue(errors.stream().anyMatch(error -> error.contains("unsupported parameter schema")));
    }

    @Test void selectedPaginationRequiresCompleteMatchingBoundsAndOrdering() {
        var integer = new PackagedContractLoader.FieldSchema("integer", "int32", "", List.of(), false);
        var page = new PackagedContractLoader.QueryParameter(false, integer, 0, 0, null);
        var size = new PackagedContractLoader.QueryParameter(false, integer, 20, 1, 100);
        var errorEnvelope = Map.<String, Object>of("required",
                List.of("status", "clientMessage", "statusCode", "body", "errors"), "properties", Map.of(
                        "status", Map.of("type", "string"), "clientMessage", Map.of("type", "string"),
                        "statusCode", Map.of("type", "integer"), "body", Map.of("nullable", true),
                        "errors", Map.of("type", "object")));
        var schemas = Map.<String, Object>of("Error", errorEnvelope);
        List<String> errors = new ArrayList<>();
        PackagedContractLoader.validatePagination(Map.of("page", page, "size", size),
                new PackagedContractLoader.Ordering("id", "ASC"), CrudAction.LIST,
                "scan", Map.of(400, "Error"), schemas, errors);
        assertTrue(errors.isEmpty(), errors.toString());
        PackagedContractLoader.validatePagination(Map.of("page", page, "size", size), null,
                CrudAction.LIST, "scan", Map.of(400, "Error"), schemas, errors);
        assertTrue(errors.stream().anyMatch(error -> error.contains("required together")));
        errors.clear();
        PackagedContractLoader.validatePagination(Map.of("page", page,
                "size", new PackagedContractLoader.QueryParameter(false, integer, 20, 1, 101)),
                new PackagedContractLoader.Ordering("id", "ASC"), CrudAction.LIST,
                "scan", Map.of(400, "Error"), schemas, errors);
        assertTrue(errors.stream().anyMatch(error -> error.contains("unsupported defaults or bounds")));
        errors.clear();
        PackagedContractLoader.validatePagination(Map.of("page", page, "size", size),
                new PackagedContractLoader.Ordering("id", "ASC"), CrudAction.LIST,
                "scan", Map.of(), schemas, errors);
        assertTrue(errors.stream().anyMatch(error -> error.contains("contracted 400")));
    }

    private static String source(Map<String, byte[]> files, String suffix) {
        return new String(files.entrySet().stream().filter(e -> e.getKey().endsWith(suffix))
                .findFirst().orElseThrow().getValue(), StandardCharsets.UTF_8);
    }
}
