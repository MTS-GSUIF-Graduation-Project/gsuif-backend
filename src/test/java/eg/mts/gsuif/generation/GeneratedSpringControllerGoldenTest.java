package eg.mts.gsuif.generation;

import eg.mts.gsuif.generation.GenerationContext.Target;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class GeneratedSpringControllerGoldenTest {
    private final Scrum50GoldenFixture fixture = new Scrum50GoldenFixture();

    @Test
    void generatedAssetTicketControllerConformsToApplicableStructure() throws Exception {
        assertTrue(Scrum50JavaGoldenRules.validateController(controller()).isEmpty());
    }

    @ParameterizedTest(name = "{0} reports {1}")
    @MethodSource("deliberateViolations")
    void deliberateViolationReportsItsNamedContractOrApplicableStandard(
            String name, String expectedRule, String expectedStandard,
            UnaryOperator<String> mutation) throws Exception {
        List<Scrum50JavaGoldenRules.Violation> violations =
                Scrum50JavaGoldenRules.validateController(mutation.apply(controller()));
        assertEquals(1, violations.size(), name + ": " + violations);
        assertEquals(expectedRule, violations.getFirst().rule());
        assertEquals(expectedStandard, violations.getFirst().standard());
        assertEquals(Scrum50GoldenFixture.CONTROLLER_PATH, violations.getFirst().artifact());
    }

    private String controller() throws Exception {
        return fixture.generate(Set.of(Target.CONTROLLER)).artifacts().stream()
                .filter(item -> item.relativePath().equals(Scrum50GoldenFixture.CONTROLLER_PATH))
                .map(item -> new String(item.bytes(), java.nio.charset.StandardCharsets.UTF_8))
                .findFirst().orElseThrow();
    }

    private static Stream<Arguments> deliberateViolations() {
        return Stream.of(
                Arguments.of("package", "JAVA_PACKAGE", "STD-06",
                        replace("package com.example.fixture.controller;",
                                "package com.example.fixture.entity;")),
                Arguments.of("class name", "JAVA_CLASS_NAME", "SCRUM-50/UNMAPPED",
                        replace("public class AssetTicketController",
                                "public class BrokenController")),
                Arguments.of("annotation", "JAVA_REST_CONTROLLER_ANNOTATION", "STD-06",
                        replace("@RestController", "")),
                Arguments.of("service field", "JAVA_SERVICE_FIELD", "SCRUM-50/UNMAPPED",
                        replace("private final AssetTicketService service;",
                                "private final Object service;")),
                Arguments.of("API relationship", "JAVA_API_RELATIONSHIP", "SCRUM-50/UNMAPPED",
                        replace(" implements AssetTicketApi", "")),
                Arguments.of("constructor relationship", "JAVA_SERVICE_CONSTRUCTOR_RELATIONSHIP",
                        "SCRUM-50/UNMAPPED",
                        replace("AssetTicketController(AssetTicketService service)",
                                "AssetTicketController(Object service)")),
                Arguments.of("CRUD methods", "JAVA_CRUD_METHODS", "SCRUM-50/UNMAPPED",
                        replace("createAssetTicket(CreateAssetTicketRequest request)",
                                "createSomethingElse(CreateAssetTicketRequest request)")),
                Arguments.of("response envelope", "CONTROLLER_RESPONSE_ENVELOPE", "STD-05",
                        replace("ResponseEntity<ApiResponse<AssetTicketDto>> createAssetTicket",
                                "AssetTicketDto createAssetTicket")),
                Arguments.of("create status", "CREATE_STATUS_201", "STD-28",
                        replace("HttpStatus.CREATED", "HttpStatus.OK"))
        );
    }

    private static UnaryOperator<String> replace(String before, String after) {
        return source -> {
            assertTrue(source.contains(before), "Golden fixture changed; missing: " + before);
            return source.replaceFirst(java.util.regex.Pattern.quote(before),
                    java.util.regex.Matcher.quoteReplacement(after));
        };
    }
}
