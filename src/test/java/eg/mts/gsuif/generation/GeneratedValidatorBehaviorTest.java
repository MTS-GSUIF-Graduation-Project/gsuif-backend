package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eg.mts.gsuif.generation.GenerationContext.Target;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavioral evaluation of the template-only generated {@code validate()} implementation. Each
 * mutation family is scored: it is detected only when every case of that family produces exactly
 * the expected path, code and detail. The shipped package's own request validation is scored by
 * {@link FinalOutputRequestValidationBehaviorTest}.
 */
class GeneratedValidatorBehaviorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Comparator<Finding> ORDER = Comparator.comparing(Finding::path)
            .thenComparing(Finding::code).thenComparing(Finding::detail);
    @TempDir Path scratch;

    private record Finding(String path, String code, String detail) { }

    private record Score(Set<String> detected, Set<String> missed, List<String> failures,
                         List<String> wronglyRejectedValid) { }

    @FunctionalInterface
    private interface ValidatorInvocation {
        List<Finding> validate(String schema, byte[] jsonUtf8) throws Exception;
    }

    /** Deliberately faulty mutation fixture; the shared corpus must kill it in every family. */
    private static final class AlwaysAcceptingValidator implements ValidatorInvocation {
        @Override
        public List<Finding> validate(String schema, byte[] jsonUtf8) {
            return List.of();
        }
    }

    @Test
    void compiledGeneratedValidateImplementationDetectsEveryMutationFamily() throws Exception {
        GenerationResult result = new TemplateOnlyGoldenFixture().generate(Set.of(Target.CONTROLLER));
        var artifact = result.artifacts().stream()
                .filter(item -> item.relativePath().equals(TemplateOnlyGoldenFixture.VALIDATOR_PATH))
                .findFirst().orElseThrow();
        String source = new String(artifact.bytes(), StandardCharsets.UTF_8);
        assertFalse(source.contains("eg.mts.gsuif"));
        assertFalse(source.contains("org.springframework"));
        assertTrue(source.contains("List<Finding> validate(String schemaName, byte[] jsonUtf8)"));

        Path sourceFile = scratch.resolve(artifact.relativePath());
        Files.createDirectories(sourceFile.getParent());
        Files.write(sourceFile, artifact.bytes());
        Path classes = Files.createDirectories(scratch.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString(), sourceFile.toString()));

        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},
                getClass().getClassLoader())) {
            Class<?> type = loader.loadClass(
                    "com.example.fixture.util.AssetTicketGeneratedValidator");
            Object validator = type.getConstructor().newInstance();
            var method = type.getMethod("validate", String.class, byte[].class);
            Score score = score((schema, bytes) -> findings(method.invoke(validator, schema, bytes)));
            report("generated AssetTicket validate()", score);
            assertEquals(List.of(), score.wronglyRejectedValid(), "Valid corpus must be accepted");
            assertEquals(List.of(), score.failures(), "Wrong path, code or detail");
            assertEquals(families(), score.detected(), "Every required mutation family must be detected");
        }
    }

    @Test
    void sharedCorpusKillsIntentionallyAlwaysAcceptingValidatorInEveryFamily() throws Exception {
        Score score = score(new AlwaysAcceptingValidator());
        report("always-accepting validator fixture", score);
        assertEquals(Set.of(), score.detected());
        assertEquals(families(), score.missed(), "The harness must fail the fixture for every family");
    }

    private static Score score(ValidatorInvocation validator) throws Exception {
        JsonNode corpus = corpus();
        Map<String, Boolean> familyDetected = new LinkedHashMap<>();
        for (String family : families()) familyDetected.put(family, true);
        List<String> failures = new ArrayList<>();
        List<String> wronglyRejected = new ArrayList<>();
        for (JsonNode testCase : corpus.path("cases")) {
            List<Finding> expected = new ArrayList<>();
            for (JsonNode item : testCase.path("expected"))
                expected.add(new Finding(item.path("path").asText(), item.path("code").asText(),
                        item.path("detail").asText()));
            expected.sort(ORDER);
            String json = testCase.path("json").asText()
                    .replace("${a*100}", "a".repeat(100)).replace("${a*101}", "a".repeat(101));
            List<Finding> actual = validator.validate(testCase.path("schema").asText(),
                    json.getBytes(StandardCharsets.UTF_8));
            String family = testCase.path("family").asText();
            if (family.equals("VALID")) {
                if (!actual.isEmpty()) wronglyRejected.add(testCase.path("name").asText() + " -> " + actual);
            } else if (!expected.equals(actual)) {
                familyDetected.put(family, false);
                failures.add(testCase.path("name").asText() + ": expected " + expected + " but was " + actual);
            }
        }
        Set<String> detected = new LinkedHashSet<>();
        Set<String> missed = new LinkedHashSet<>();
        familyDetected.forEach((family, ok) -> (ok ? detected : missed).add(family));
        return new Score(detected, missed, failures, wronglyRejected);
    }

    private static void report(String subject, Score score) {
        System.out.printf("SCRUM-50 validator score - %s: %d/%d families detected %s, missed %s%n",
                subject, score.detected().size(), families().size(), score.detected(), score.missed());
    }

    private static JsonNode corpus() throws Exception {
        try (var input = GeneratedValidatorBehaviorTest.class.getResourceAsStream(
                "/generation/generated-validator-corpus.json")) {
            assertNotNull(input);
            return JSON.readTree(input);
        }
    }

    private static Set<String> families() {
        try {
            Set<String> families = new LinkedHashSet<>();
            corpus().path("families").forEach(item -> families.add(item.asText()));
            return families;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static List<Finding> findings(Object result) throws Exception {
        List<Finding> findings = new ArrayList<>();
        for (Object item : (List<?>) result) {
            findings.add(new Finding(
                    String.valueOf(item.getClass().getMethod("path").invoke(item)),
                    String.valueOf(item.getClass().getMethod("code").invoke(item)),
                    String.valueOf(item.getClass().getMethod("detail").invoke(item))));
        }
        findings.sort(ORDER);
        return List.copyOf(findings);
    }
}
