package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.json.JsonMapper;

import javax.tools.ToolProvider;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * SCRUM-50 behavioral evaluation of the validation that actually ships: the real generated request
 * DTOs, API interface, controller and GlobalExceptionHandler are compiled from the final output and
 * exercised through Spring MVC with the generated application's auto-configured Jackson mapper.
 * Behavior is scored per mutation family and per error path, not by test execution alone.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinalOutputRequestValidationBehaviorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COLLECTION = "/api/v1/stock-units";
    private static final String ITEM = COLLECTION + "/00000000-0000-0000-0000-000000000001";

    private JsonNode corpus;
    private URLClassLoader generated;
    private MockMvc mvc;

    /** One evaluated corpus case: HTTP status and the STD-01 error keys the response carried. */
    record Outcome(int status, Set<String> errorKeys) { }

    @FunctionalInterface
    interface Endpoint {
        Outcome send(String method, String body) throws Exception;
    }

    record FamilyScore(String family, int cases, int rejected, int correctPath) {
        boolean detected() { return cases > 0 && rejected == cases; }
    }

    record Score(Map<String, FamilyScore> families, List<String> wronglyRejectedValid, List<String> trace) {
        Set<String> detected() {
            Set<String> detected = new LinkedHashSet<>();
            families.values().stream().filter(FamilyScore::detected).forEach(f -> detected.add(f.family()));
            return detected;
        }
    }

    @BeforeAll
    void compileRealOutput(@TempDir Path scratch) throws Exception {
        try (var input = getClass().getResourceAsStream("/generation/final-output/stock-unit-request-corpus.json")) {
            corpus = JSON.readTree(input);
        }
        var output = FinalGeneratedOutput.load();
        Path sources = scratch.resolve("src");
        output.extractTo(sources);
        Path root = sources.resolve(FinalGeneratedOutput.JAVA_ROOT);
        List<String> files = new ArrayList<>();
        for (String directory : List.of("api", "controller", "dto", "exception"))
            try (var paths = Files.list(root.resolve(directory))) {
                paths.map(Path::toString).filter(path -> path.endsWith(".java")).forEach(files::add);
            }
        for (String single : List.of("service/StockUnitService.java", "logging/SafeFailureLogger.java",
                "aspect/Loggable.java"))
            files.add(root.resolve(single).toString());
        Path classes = Files.createDirectories(scratch.resolve("classes"));
        List<String> arguments = new ArrayList<>(List.of("-proc:none", "-parameters",
                "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()));
        arguments.addAll(files);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)),
                "Real generated request layer must compile");

        generated = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader());
        Class<?> serviceType = generated.loadClass("com.example.inventory.service.StockUnitService");
        Object service = Proxy.newProxyInstance(generated, new Class<?>[]{serviceType}, (proxy, method, args) ->
                switch (method.getName()) {
                    case "toString" -> "StubStockUnitService";
                    case "hashCode" -> 0;
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
        Object controller = generated.loadClass("com.example.inventory.controller.StockUnitController")
                .getConstructor(serviceType).newInstance(service);
        Object handler = generated.loadClass("com.example.inventory.exception.GlobalExceptionHandler")
                .getConstructor().newInstance();
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(handler)
                .setMessageConverters(new JacksonJsonHttpMessageConverter(generatedAppJsonMapper()))
                .setValidator(validator).build();
    }

    @Test
    void realGeneratedValidationScoreMatchesTheDocumentedDetection() throws Exception {
        Score score = evaluate(this::sendToRealOutput);
        writeReport("real generated StockUnit request validation", score);
        assertEquals(List.of(), score.wronglyRejectedValid(), "Valid corpus must be accepted");
        // Pinned to the measured behavior so the evidence doc cannot drift from the shipped code.
        for (FamilyScore family : score.families().values()) {
            JsonNode expected = corpus.path("expectedShippedScore").path(family.family());
            assertEquals(expected.path("rejected").asInt(), family.rejected(),
                    family.family() + " rejection changed; update expectedShippedScore and the evidence doc");
            assertEquals(expected.path("correctPath").asInt(), family.correctPath(),
                    family.family() + " error-path accuracy changed; update expectedShippedScore and the evidence doc");
        }
        assertTrue(score.detected().containsAll(Set.of("REQUIRED_FIELD", "INVALID_ENUM")),
                "Required-field and enum rejection must never regress");
    }

    @Test
    void scoringHarnessRejectsAnAlwaysAcceptingEndpoint() throws Exception {
        Score score = evaluate((method, body) -> new Outcome(method.equals("POST") ? 201 : 200, Set.of()));
        writeReport("always-accepting endpoint fixture", score);
        assertEquals(Set.of(), score.detected(), "An endpoint that accepts everything must detect no family");
        assertNotEquals(families(), score.detected(), "The harness must fail such an endpoint");
    }

    private Score evaluate(Endpoint endpoint) throws Exception {
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (String family : families()) counts.put(family, new int[3]);
        List<String> wronglyRejected = new ArrayList<>();
        List<String> trace = new ArrayList<>();
        for (JsonNode testCase : corpus.path("cases")) {
            String body = testCase.path("body").asText()
                    .replace("${L*100}", "L".repeat(100)).replace("${L*101}", "L".repeat(101));
            Outcome outcome = endpoint.send(testCase.path("method").asText(), body);
            trace.add(String.format("  %-7s %-42s -> HTTP %d errors=%s", testCase.path("expect").asText(),
                    testCase.path("name").asText(), outcome.status(), outcome.errorKeys()));
            if (testCase.path("expect").asText().equals("ACCEPT")) {
                if (outcome.status() >= 300) wronglyRejected.add(testCase.path("name").asText() + " -> " + outcome);
                continue;
            }
            int[] count = counts.get(testCase.path("family").asText());
            count[0]++;
            if (outcome.status() == 400) count[1]++;
            if (outcome.status() == 400 && outcome.errorKeys().contains(testCase.path("errorKey").asText())) count[2]++;
        }
        Map<String, FamilyScore> families = new LinkedHashMap<>();
        counts.forEach((family, count) -> families.put(family, new FamilyScore(family, count[0], count[1], count[2])));
        return new Score(families, wronglyRejected, trace);
    }

    private Outcome sendToRealOutput(String method, String body) throws Exception {
        var result = mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method),
                        method.equals("POST") ? COLLECTION : ITEM)
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8))).andReturn().getResponse();
        Set<String> keys = new LinkedHashSet<>();
        String content = result.getContentAsString(StandardCharsets.UTF_8);
        if (!content.isBlank()) {
            JsonNode envelope = JSON.readTree(content);
            if (result.getStatus() >= 400) {
                assertEquals(result.getStatus(), envelope.path("statusCode").asInt(), "STD-01 envelope on errors");
                envelope.path("errors").fieldNames().forEachRemaining(keys::add);
            }
        }
        return new Outcome(result.getStatus(), keys);
    }

    /** The JsonMapper a generated Spring Boot app gets: Boot's Jackson auto-configuration, no overrides. */
    private static JsonMapper generatedAppJsonMapper() {
        AtomicReference<JsonMapper> mapper = new AtomicReference<>();
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(context -> mapper.set(context.getBean(JsonMapper.class)));
        return mapper.get();
    }

    private List<String> families() {
        List<String> families = new ArrayList<>();
        corpus.path("families").forEach(item -> families.add(item.asText()));
        return families;
    }

    private void writeReport(String subject, Score score) throws Exception {
        StringBuilder report = new StringBuilder("SCRUM-50 validation score - ").append(subject).append('\n');
        report.append(String.format("%-20s %6s %9s %13s %9s%n", "family", "cases", "rejected", "correct-path", "detected"));
        for (FamilyScore family : score.families().values())
            report.append(String.format("%-20s %6d %9d %13d %9s%n", family.family(), family.cases(),
                    family.rejected(), family.correctPath(), family.detected() ? "YES" : "NO"));
        report.append("families detected: ").append(score.detected().size()).append('/')
                .append(score.families().size()).append(' ').append(score.detected()).append('\n');
        report.append("valid cases wrongly rejected: ").append(score.wronglyRejectedValid()).append('\n');
        report.append("cases:\n");
        score.trace().forEach(line -> report.append(line).append('\n'));
        System.out.print(report);
        Path directory = Files.createDirectories(Path.of("target", "generation-evidence"));
        String file = subject.startsWith("always") ? "always-accepting-score.txt" : "final-output-validation-score.txt";
        Files.writeString(directory.resolve(file), report);
    }
}
