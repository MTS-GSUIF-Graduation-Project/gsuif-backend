package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eg.mts.gsuif.generation.GenerationContext.Target;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GeneratedAngularComponentGoldenTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private Path harness;
    private String typescript;
    private String html;

    @BeforeAll
    void generateAndProvisionHarness() throws Exception {
        harness = Path.of("target", "angular-component-golden");
        Files.createDirectories(harness);
        for (String file : List.of("package.json", "package-lock.json", "tsconfig.json",
                "angular-component-golden.mjs"))
            Files.copy(Path.of("src/test/angular-harness", file), harness.resolve(file),
                    StandardCopyOption.REPLACE_EXISTING);

        GenerationResult generated = new TemplateOnlyGoldenFixture().generate(Set.of(Target.ANGULAR));
        for (var artifact : generated.artifacts()) {
            Path destination = harness.resolve(artifact.relativePath());
            Files.createDirectories(destination.getParent());
            Files.write(destination, artifact.bytes());
        }
        typescript = artifact(generated, TemplateOnlyGoldenFixture.ANGULAR_TYPESCRIPT_PATH);
        html = artifact(generated, TemplateOnlyGoldenFixture.ANGULAR_HTML_PATH);
        installPinnedDependencies();
    }

    @Test
    void generatedAssetTicketAngularComponentConformsAndStrictlyCompiles() throws Exception {
        assertTrue(violations(typescript, html).isEmpty());
        ProcessResult compile = run(npm(), "run", "check");
        assertEquals(0, compile.exitCode(), compile.output());
    }

    @ParameterizedTest(name = "{0} reports {1}")
    @MethodSource("deliberateViolations")
    void deliberateViolationReportsNamedAngularContract(String name, String expectedRule,
                                                         BiFunction<String, String, Sources> mutation)
            throws Exception {
        Sources changed = mutation.apply(typescript, html);
        List<JsonNode> violations = violations(changed.typescript(), changed.html());
        assertTrue(violations.stream().anyMatch(item ->
                        item.path("rule").asText().equals(expectedRule)
                                && item.path("standard").asText().equals("SCRUM-50/UNMAPPED")),
                name + ": " + violations);
    }

    private List<JsonNode> violations(String ts, String template) throws Exception {
        Path source = harness.resolve(TemplateOnlyGoldenFixture.ANGULAR_TYPESCRIPT_PATH);
        Path htmlFile = harness.resolve(TemplateOnlyGoldenFixture.ANGULAR_HTML_PATH);
        Files.createDirectories(source.getParent());
        Files.writeString(source, ts);
        Files.writeString(htmlFile, template);
        ProcessResult result = run(node(), "angular-component-golden.mjs",
                source.toAbsolutePath().toString(), htmlFile.toAbsolutePath().toString());
        assertEquals(0, result.exitCode(), result.output());
        return JSON.readValue(result.output(),
                new com.fasterxml.jackson.core.type.TypeReference<List<JsonNode>>() { });
    }

    private void installPinnedDependencies() throws Exception {
        if (Files.isDirectory(harness.resolve("node_modules/@angular/compiler-cli"))) return;
        ProcessResult install = run(npm(), "ci", "--offline", "--ignore-scripts",
                "--no-audit", "--no-fund");
        assertEquals(0, install.exitCode(),
                "Pinned Angular dependencies unavailable in the local npm cache: " + install.output());
    }

    private ProcessResult run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(harness.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(process.waitFor(), output);
    }

    private static String artifact(GenerationResult generated, String path) {
        return generated.artifacts().stream().filter(item -> item.relativePath().equals(path))
                .map(item -> new String(item.bytes(), StandardCharsets.UTF_8))
                .findFirst().orElseThrow();
    }

    private static Stream<Arguments> deliberateViolations() {
        return Stream.of(
                mutation("imports", "ANGULAR_IMPORTS",
                        (ts, html) -> new Sources(ts.replace(
                                "import { CommonModule } from '@angular/common';", ""), html)),
                mutation("DTO interface", "ANGULAR_DTO_INTERFACE",
                        (ts, html) -> new Sources(ts.replace(
                                "interface AssetTicketDto", "interface BrokenDto"), html)),
                mutation("component class", "ANGULAR_COMPONENT_CLASS",
                        (ts, html) -> new Sources(ts.replace(
                                "class AssetTicketComponent", "class BrokenComponent"), html)),
                mutation("component decorator", "ANGULAR_COMPONENT_DECORATOR",
                        (ts, html) -> new Sources(ts.replaceFirst(
                                "(?m)^@Component\\([^\\n]+\\)\\R", ""), html)),
                mutation("selector", "ANGULAR_COMPONENT_SELECTOR",
                        (ts, html) -> new Sources(ts.replace(
                                "selector: 'app-generated-asset-ticket'",
                                "selector: 'app-broken'"), html)),
                mutation("standalone flag", "ANGULAR_COMPONENT_STANDALONE",
                        (ts, html) -> new Sources(ts.replace(
                                "standalone: true", "standalone: false"), html)),
                mutation("template relationship", "ANGULAR_TEMPLATE_RELATIONSHIP",
                        (ts, html) -> new Sources(ts.replace(
                                "templateUrl: './asset-ticket.component.html'",
                                "templateUrl: './missing.html'"), html)),
                mutation("component fields", "ANGULAR_COMPONENT_FIELDS",
                        (ts, html) -> new Sources(ts.replace("model = {}", "changedModel = {}"), html)),
                mutation("component methods", "ANGULAR_COMPONENT_METHODS",
                        (ts, html) -> new Sources(ts.replace("isVisible(id:", "wasVisible(id:"), html)),
                mutation("template structure", "ANGULAR_TEMPLATE_STRUCTURE",
                        (ts, html) -> new Sources(ts, html.replace("class=\"generated-grid\"",
                                "class=\"broken-grid\"")))
        );
    }

    private static Arguments mutation(String name, String rule,
                                      BiFunction<String, String, Sources> mutation) {
        return Arguments.of(name, rule, mutation);
    }

    private static String npm() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                ? "npm.cmd" : "npm";
    }

    private static String node() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                ? "node.exe" : "node";
    }

    private record Sources(String typescript, String html) { }
    private record ProcessResult(int exitCode, String output) { }
}
