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
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GeneratedValidatorBehaviorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path scratch;

    private record Finding(String path, String code) { }

    @FunctionalInterface
    private interface ValidatorInvocation {
        List<Finding> validate(String schema, byte[] jsonUtf8) throws Exception;
    }

    /** Deliberately faulty mutation fixture; the shared corpus must kill it. */
    private static final class AlwaysAcceptingValidator implements ValidatorInvocation {
        @Override
        public List<Finding> validate(String schema, byte[] jsonUtf8) {
            return List.of();
        }
    }

    @Test
    void compiledGeneratedValidateImplementationRejectsSharedMutationCorpus() throws Exception {
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
                    "com.example.fixture.validation.AssetTicketGeneratedValidator");
            Object validator = type.getConstructor().newInstance();
            var method = type.getMethod("validate", String.class, byte[].class);
            ValidatorInvocation generated = (schema, bytes) -> findings(method.invoke(
                    validator, schema, bytes));
            assertCorpus(generated);
        }
    }

    @Test
    void sharedCorpusKillsIntentionallyAlwaysAcceptingValidator() throws Exception {
        AssertionError killed = assertThrows(AssertionError.class,
                () -> assertCorpus(new AlwaysAcceptingValidator()));
        assertTrue(killed.getMessage().contains("missing required field"), killed::getMessage);
    }

    private static void assertCorpus(ValidatorInvocation validator) throws Exception {
        JsonNode corpus;
        try (var input = GeneratedValidatorBehaviorTest.class.getResourceAsStream(
                "/generation/generated-validator-corpus.json")) {
            assertNotNull(input);
            corpus = JSON.readTree(input);
        }
        for (JsonNode testCase : corpus.path("cases")) {
            List<Finding> expected = new ArrayList<>();
            for (JsonNode item : testCase.path("expected"))
                expected.add(new Finding(item.path("path").asText(), item.path("code").asText()));
            expected.sort(Comparator.comparing(Finding::path).thenComparing(Finding::code));
            List<Finding> actual = validator.validate(testCase.path("schema").asText(),
                    testCase.path("json").asText().getBytes(StandardCharsets.UTF_8));
            assertEquals(expected, actual, testCase.path("name").asText());
        }
    }

    private static List<Finding> findings(Object result) throws Exception {
        List<Finding> findings = new ArrayList<>();
        for (Object item : (List<?>) result) {
            Object code = item.getClass().getMethod("code").invoke(item);
            findings.add(new Finding(
                    String.valueOf(item.getClass().getMethod("path").invoke(item)),
                    String.valueOf(code)));
        }
        findings.sort(Comparator.comparing(Finding::path).thenComparing(Finding::code));
        return List.copyOf(findings);
    }
}
