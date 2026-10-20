package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.javaparser.ast.body.RecordDeclaration;
import eg.mts.gsuif.generation.GenerationCapabilityPreflight.OutputMode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SCRUM-50 structural golden tests against the real final output of a previous generation run,
 * plus a parity check that today's generator still reproduces that output's deterministic files.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinalOutputGoldenTest {
    private FinalGeneratedOutput output;
    private JavaStructuralGoldenRules.ControllerShape shape;

    @BeforeAll
    void loadFinalOutput() throws Exception {
        output = FinalGeneratedOutput.load();
        shape = output.controllerShape();
    }

    @Test
    void testCopyIsTheUnchangedRunOutputAndMatchesItsManifest() {
        assertEquals(FinalGeneratedOutput.ZIP_SHA256, output.zipSha256(),
                "The test fixture must stay byte-identical to the original generation ZIP");
        var manifest = output.manifest();
        assertEquals("BACKEND", manifest.path("outputMode").asText());
        assertFalse(manifest.path("provider").path("mocked").asBoolean(), "Fixture must be real provider output");
        TreeSet<String> listed = new TreeSet<>();
        for (var item : manifest.path("files")) {
            String path = item.path("path").asText();
            assertTrue(listed.add(path), "Duplicate manifest path " + path);
            byte[] bytes = output.bytes(path);
            assertEquals(item.path("bytes").asInt(), bytes.length, path);
            assertEquals(item.path("sha256").asText(), FinalGeneratedOutput.sha256(bytes), path);
        }
        TreeSet<String> packaged = new TreeSet<>(output.files().keySet());
        packaged.remove("generation-manifest.json");
        assertEquals(packaged, listed, "Every packaged file must be listed in the manifest");
    }

    @Test
    void realGeneratedControllerConformsToStandards() {
        assertEquals("registerStockUnit", shape.createMethod(), "Shape is derived from the shipped contract");
        assertEquals(List.of(), JavaStructuralGoldenRules.validateController(
                output.text(FinalGeneratedOutput.CONTROLLER_PATH), shape));
    }

    @ParameterizedTest(name = "{0} reports {1}")
    @MethodSource("deliberateViolations")
    void deliberateViolationInRealControllerIsDetectedAndNamed(String name, String expectedRule,
                                                               String expectedStandard,
                                                               UnaryOperator<String> mutation) {
        var violations = JavaStructuralGoldenRules.validateController(
                mutation.apply(output.text(FinalGeneratedOutput.CONTROLLER_PATH)), shape);
        assertEquals(1, violations.size(), name + ": " + violations);
        assertEquals(expectedRule, violations.getFirst().rule());
        assertEquals(expectedStandard, violations.getFirst().standard());
        assertEquals(FinalGeneratedOutput.CONTROLLER_PATH, violations.getFirst().artifact());
    }

    @Test
    void crudArtifactsLiveInTheirStd06Layers() {
        String entity = FinalGeneratedOutput.ENTITY;
        Map<String, String> expected = new TreeMap<>(Map.of(
                "controller/" + entity + "Controller.java", "controller",
                "entity/" + entity + ".java", "entity",
                "repository/" + entity + "Repository.java", "repository",
                "service/" + entity + "Service.java", "service",
                "service/" + entity + "ServiceImpl.java", "service",
                "dto/" + entity + "Dto.java", "dto",
                "dto/Create" + entity + "Request.java", "dto",
                "dto/Update" + entity + "Request.java", "dto",
                "dto/" + entity + "Page.java", "dto"));
        List<String> misplaced = new ArrayList<>();
        for (var item : expected.entrySet()) {
            String layer = JavaStructuralGoldenRules.layerOf(output.text(FinalGeneratedOutput.JAVA_ROOT + item.getKey()));
            if (!layer.equals(item.getValue())) misplaced.add(item.getKey() + " -> " + layer + " [STD-06]");
        }
        assertEquals(List.of(), misplaced);
    }

    @Test
    void responseEnvelopeHasExactlyTheFiveStd01Fields() {
        RecordDeclaration envelope = JavaStructuralGoldenRules.parse(output.text(FinalGeneratedOutput.JAVA_ROOT + "api/ApiResponse.java"))
                .findFirst(RecordDeclaration.class).orElseThrow();
        assertEquals(List.of("status", "clientMessage", "statusCode", "body", "errors"),
                envelope.getParameters().stream().map(parameter -> parameter.getNameAsString()).toList(),
                "STD-01 five-field envelope");
    }

    @Test
    void entityUsesUuidIdentifierPerStd35() {
        var entity = JavaStructuralGoldenRules.parse(output.text(FinalGeneratedOutput.JAVA_ROOT + "entity/StockUnit.java"));
        var id = entity.findAll(com.github.javaparser.ast.body.FieldDeclaration.class).stream()
                .filter(field -> field.getAnnotationByName("Id").isPresent()).findFirst().orElseThrow();
        assertEquals("UUID", id.getElementType().asString(), "STD-35 UUID identifier");
    }

    @Test
    void realOutputHasNoReferenceToTheGeneratorAnywhere() {
        List<String> offenders = new ArrayList<>();
        for (var file : output.files().entrySet()) {
            if (!file.getKey().startsWith("backend/")) continue;
            String text = new String(file.getValue(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            // Any reference (import or fully qualified name), not only import statements.
            for (String forbidden : List.of("eg.mts.gsuif", "freemarker", "io.jmix", "javaparser",
                    "openapi-generator", "openrouter"))
                if (text.contains(forbidden)) offenders.add(file.getKey() + " -> " + forbidden);
        }
        assertEquals(List.of(), offenders);
    }

    @Test
    void currentGeneratorDiffersFromTheRealOutputOnlyInTheRequestValidationFiles() throws Exception {
        Map<String, byte[]> regenerated = regenerateWithRealModelOutput();
        List<String> drift = new ArrayList<>();
        for (var item : output.manifest().path("files")) {
            String path = item.path("path").asText();
            if (!"DETERMINISTIC".equals(item.path("owner").asText())) continue;
            byte[] current = regenerated.get(path);
            if (current == null) drift.add(path + " (no longer generated)");
            else if (path.startsWith("contracts/")) {
                // The contract is copied byte-for-byte from the checked-out resource. The historical run used a
                // Windows checkout (core.autocrlf=true -> CRLF); CI checks out LF. Compare it line-ending-neutrally.
                if (!lineNeutral(output.bytes(path)).equals(lineNeutral(current))) drift.add(path);
            } else if (!item.path("sha256").asText().equals(FinalGeneratedOutput.sha256(current))) drift.add(path);
        }
        for (String path : regenerated.keySet())
            if (!path.equals("generation-manifest.json") && !output.files().containsKey(path))
                drift.add(path + " (new file)");
        // SCRUM-50 request-validation hardening intentionally changes exactly these files; every other
        // deterministic file must still match the historical run byte-for-byte.
        String root = FinalGeneratedOutput.JAVA_ROOT;
        assertEquals(new java.util.TreeSet<>(List.of("README.md",
                        root + "dto/CreateStockUnitRequest.java",
                        root + "dto/UpdateStockUnitRequest.java",
                        root + "exception/GlobalExceptionHandler.java",
                        root + "config/RequestJsonStrictnessConfig.java (new file)")),
                new java.util.TreeSet<>(drift),
                "Generator output differs from the historical run outside the request-validation change");
        assertEquals(List.of(), JavaStructuralGoldenRules.validateController(
                new String(regenerated.get(FinalGeneratedOutput.CONTROLLER_PATH), StandardCharsets.UTF_8), shape));
    }

    private static String lineNeutral(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** Re-assembles the package with today's generator; the model-owned file is the real model output. */
    private Map<String, byte[]> regenerateWithRealModelOutput() throws Exception {
        var fixture = new CompleteOutputTestFixture();
        var descriptor = fixture.preflight().describe(fixture.version(fixture.pagedSnapshot()),
                fixture.pagedSpecification(), OutputMode.BACKEND);
        var registry = new PackagedContractRegistry("/generation/stock-unit-contracts.yaml");
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var runtime = new CompleteBackendRuntimeRenderer(registry);
        var input = BackendModelInput.from(descriptor, plan,
                new CompleteBackendDefinitionRenderer().render(descriptor, plan), runtime);
        assertEquals(FinalGeneratedOutput.SERVICE_IMPL_PATH, input.writablePath());
        ObjectMapper json = new ObjectMapper();
        ObjectNode response = json.createObjectNode();
        response.putArray("files").addObject().put("path", input.writablePath())
                .put("content", output.text(FinalGeneratedOutput.SERVICE_IMPL_PATH));
        var accepted = new BackendModelOutputValidator((chosen, bytes) -> true)
                .validate(input, json.writeValueAsBytes(response));
        var provider = output.manifest().path("provider");
        return new CompleteBackendPackageAssembler(runtime).assemble(input, accepted,
                new CompleteBackendPackageAssembler.Provenance(provider.path("id").asText(),
                        provider.path("version").asText(), provider.path("model").asText(), false)).files();
    }

    private static Stream<Arguments> deliberateViolations() {
        String api = "com.example.inventory.api.";
        return Stream.of(
                Arguments.of("package", "JAVA_PACKAGE", "STD-06",
                        replace("package com.example.inventory.controller;", "package com.example.inventory.entity;")),
                Arguments.of("package casing", "JAVA_PACKAGE_NAMING", "STD-08",
                        replace("package com.example.inventory.controller;", "package com.example.Inventory.controller;")),
                Arguments.of("class name", "JAVA_CLASS_NAME", JavaStructuralGoldenRules.UNMAPPED,
                        replace("public class StockUnitController", "public class BrokenController")),
                Arguments.of("annotation", "JAVA_REST_CONTROLLER_ANNOTATION", "STD-06",
                        replace("@org.springframework.web.bind.annotation.RestController", "")),
                Arguments.of("service field", "JAVA_SERVICE_FIELD", "STD-06",
                        replace("private final com.example.inventory.service.StockUnitService service;",
                                "private final Object service;")),
                Arguments.of("API relationship", "JAVA_API_RELATIONSHIP", "STD-27",
                        replace(" implements " + api + "StockUnitApi", "")),
                Arguments.of("constructor relationship", "JAVA_SERVICE_CONSTRUCTOR_RELATIONSHIP", "STD-06",
                        replace("StockUnitController(com.example.inventory.service.StockUnitService service)",
                                "StockUnitController(Object service)")),
                Arguments.of("CRUD methods", "JAVA_CRUD_METHODS", JavaStructuralGoldenRules.UNMAPPED,
                        replace("registerStockUnit(", "registerSomething(")),
                Arguments.of("response envelope", "CONTROLLER_RESPONSE_ENVELOPE", "STD-05",
                        replace("public org.springframework.http.ResponseEntity<" + api
                                        + "ApiResponse<com.example.inventory.dto.StockUnitDto>> registerStockUnit",
                                "public com.example.inventory.dto.StockUnitDto registerStockUnit")),
                Arguments.of("create status", "CREATE_STATUS_201", "STD-28",
                        replace("status(201)", "status(200)")),
                Arguments.of("method RBAC", "ENDPOINT_RBAC", "STD-20",
                        GeneratedSpringControllerGoldenTest.removeAnnotationBefore("inspectStockUnit")),
                Arguments.of("UUID identifier", "UUID_IDENTIFIER", "STD-35",
                        replace("inspectStockUnit(java.util.UUID id)", "inspectStockUnit(Long id)"))
        );
    }

    private static UnaryOperator<String> replace(String before, String after) {
        return source -> {
            assertTrue(source.contains(before), "Final output changed; missing: " + before);
            return source.replaceFirst(java.util.regex.Pattern.quote(before),
                    java.util.regex.Matcher.quoteReplacement(after));
        };
    }
}
