package eg.mts.gsuif.generation;

import eg.mts.gsuif.generation.GenerationContext.Target;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Template-only controller output; the shipped package is covered by {@link FinalOutputGoldenTest}. */
class GeneratedSpringControllerGoldenTest {
    static final JavaStructuralGoldenRules.ControllerShape TEMPLATE_SHAPE =
            new JavaStructuralGoldenRules.ControllerShape(TemplateOnlyGoldenFixture.CONTROLLER_PATH,
                    "com.example.fixture.controller", "AssetTicketController", "AssetTicketService",
                    "AssetTicketApi", "createAssetTicket",
                    Set.of("createAssetTicket", "getAssetTicketById", "listAssetTickets",
                            "updateAssetTicket", "deleteAssetTicket"),
                    Set.of("getAssetTicketById", "updateAssetTicket", "deleteAssetTicket"), true);

    /** Hand-written WorkOrder reference implementation: own mappings, URL-level (not method) RBAC. */
    static final JavaStructuralGoldenRules.ControllerShape REFERENCE_SHAPE =
            new JavaStructuralGoldenRules.ControllerShape("src/main/java/eg/mts/gsuif/controller/WorkOrderController.java",
                    "eg.mts.gsuif.controller", "WorkOrderController", "WorkOrderService", null, "create",
                    Set.of("create", "getById", "getAll", "update", "delete"),
                    Set.of("getById", "update", "delete"), false);

    private final TemplateOnlyGoldenFixture fixture = new TemplateOnlyGoldenFixture();

    @Test
    void generatedAssetTicketControllerConformsToApplicableStructure() throws Exception {
        assertEquals(List.of(), JavaStructuralGoldenRules.validateController(controller(), TEMPLATE_SHAPE));
    }

    @Test
    void referenceImplementationSatisfiesTheSameStandardsRules() throws Exception {
        String reference = Files.readString(Path.of(REFERENCE_SHAPE.artifact()));
        assertEquals(List.of(), JavaStructuralGoldenRules.validateController(reference, REFERENCE_SHAPE),
                "The golden rules must describe the Reference Implementation's shape");
    }

    @ParameterizedTest(name = "{0} reports {1}")
    @MethodSource("deliberateViolations")
    void deliberateViolationReportsItsNamedContractOrApplicableStandard(
            String name, String expectedRule, String expectedStandard,
            UnaryOperator<String> mutation) throws Exception {
        List<JavaStructuralGoldenRules.Violation> violations =
                JavaStructuralGoldenRules.validateController(mutation.apply(controller()), TEMPLATE_SHAPE);
        assertEquals(1, violations.size(), name + ": " + violations);
        assertEquals(expectedRule, violations.getFirst().rule());
        assertEquals(expectedStandard, violations.getFirst().standard());
        assertEquals(TemplateOnlyGoldenFixture.CONTROLLER_PATH, violations.getFirst().artifact());
    }

    private String controller() throws Exception {
        return fixture.generate(Set.of(Target.CONTROLLER)).artifacts().stream()
                .filter(item -> item.relativePath().equals(TemplateOnlyGoldenFixture.CONTROLLER_PATH))
                .map(item -> new String(item.bytes(), java.nio.charset.StandardCharsets.UTF_8))
                .findFirst().orElseThrow();
    }

    private static Stream<Arguments> deliberateViolations() {
        return Stream.of(
                Arguments.of("package", "JAVA_PACKAGE", "STD-06",
                        replace("package com.example.fixture.controller;",
                                "package com.example.fixture.entity;")),
                Arguments.of("package casing", "JAVA_PACKAGE_NAMING", "STD-08",
                        replace("package com.example.fixture.controller;",
                                "package com.example.Fixture.controller;")),
                Arguments.of("class name", "JAVA_CLASS_NAME", JavaStructuralGoldenRules.UNMAPPED,
                        replace("public class AssetTicketController",
                                "public class BrokenController")),
                Arguments.of("annotation", "JAVA_REST_CONTROLLER_ANNOTATION", "STD-06",
                        replace("@RestController", "")),
                Arguments.of("service field", "JAVA_SERVICE_FIELD", "STD-06",
                        replace("private final AssetTicketService service;",
                                "private final Object service;")),
                Arguments.of("API relationship", "JAVA_API_RELATIONSHIP", "STD-27",
                        replace(" implements AssetTicketApi", "")),
                Arguments.of("constructor relationship", "JAVA_SERVICE_CONSTRUCTOR_RELATIONSHIP", "STD-06",
                        replace("AssetTicketController(AssetTicketService service)",
                                "AssetTicketController(Object service)")),
                Arguments.of("CRUD methods", "JAVA_CRUD_METHODS", JavaStructuralGoldenRules.UNMAPPED,
                        replace("createAssetTicket(CreateAssetTicketRequest request)",
                                "createSomethingElse(CreateAssetTicketRequest request)")),
                Arguments.of("response envelope", "CONTROLLER_RESPONSE_ENVELOPE", "STD-05",
                        replace("ResponseEntity<ApiResponse<AssetTicketDto>> createAssetTicket",
                                "AssetTicketDto createAssetTicket")),
                Arguments.of("create status", "CREATE_STATUS_201", "STD-28",
                        replace("HttpStatus.CREATED", "HttpStatus.OK")),
                Arguments.of("method RBAC", "ENDPOINT_RBAC", "STD-20",
                        removeAnnotationBefore("getAssetTicketById")),
                Arguments.of("UUID identifier", "UUID_IDENTIFIER", "STD-35",
                        replace("getAssetTicketById(UUID id)", "getAssetTicketById(Long id)"))
        );
    }

    private static UnaryOperator<String> replace(String before, String after) {
        return source -> {
            assertTrue(source.contains(before), "Golden fixture changed; missing: " + before);
            return source.replaceFirst(java.util.regex.Pattern.quote(before),
                    java.util.regex.Matcher.quoteReplacement(after));
        };
    }

    /** Removes the {@code @PreAuthorize} line directly above one endpoint method. */
    static UnaryOperator<String> removeAnnotationBefore(String method) {
        return source -> {
            String changed = source.replaceFirst("(?m)^\\s*@(?:[\\w.]+\\.)?PreAuthorize\\([^\\n]*\\)\\R"
                    + "(?=(?:\\s*@[^\\n]*\\R)*[^\\n]*\\b" + method + "\\()", "");
            assertNotEquals(source, changed, "Golden fixture changed; no @PreAuthorize above " + method);
            return changed;
        };
    }
}
