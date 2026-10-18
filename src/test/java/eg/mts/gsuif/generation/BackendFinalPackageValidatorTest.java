package eg.mts.gsuif.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import eg.mts.gsuif.generation.GenerationCapabilityPreflight.OutputMode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BackendFinalPackageValidatorTest {
    @TempDir Path scratch;

    @Test void commandUsesPinnedOfflineNonRootBuildWithOnlyBoundedReadOnlyInputs() {
        List<String> command = BackendFinalPackageValidator.command("sha256:" + "a".repeat(64),
                "gsuif-backend-build-test", scratch.resolve("input"), scratch.resolve("repo"), scratch.resolve("maven"));
        assertTrue(command.contains("--network=none"));
        assertTrue(command.contains("--read-only"));
        assertTrue(command.contains("--user=65532:65532"));
        assertTrue(command.contains("--security-opt=no-new-privileges"));
        assertTrue(command.stream().anyMatch(s -> s.contains("/input,readonly")));
        assertTrue(command.stream().anyMatch(s -> s.contains("/repo,readonly")));
        assertTrue(command.stream().anyMatch(s -> s.contains("/maven,readonly")));
        String script = command.getLast();
        assertTrue(script.contains("-o -q"));
        assertTrue(script.contains("-Dmaven.compiler.proc=none"));
        assertFalse(script.contains("OPENROUTER"));
    }

    @Test void mockedRunnerSeesBuildThenContainerCleanup() throws Exception {
        var assembled = assembled();
        Path repo = Files.createDirectory(scratch.resolve("repo"));
        Path maven = Files.createDirectories(scratch.resolve("maven/bin"));
        Files.writeString(maven.resolve("mvn"), "test-only");
        List<List<String>> calls = new ArrayList<>();
        var validator = new BackendFinalPackageValidator("sha256:" + "a".repeat(64), repo,
                maven.getParent(), (command, timeout) -> {
                    calls.add(command);
                    return BackendFinalPackageValidator.Exit.SUCCESS;
                });
        validator.validate(assembled);
        assertEquals(2, calls.size());
        assertEquals("run", calls.getFirst().get(1));
        assertEquals(List.of("docker", "rm", "-f"), calls.getLast().subList(0, 3));
    }

    @Test void buildFailureRemainsBoundedAndStillCleansContainer() throws Exception {
        var assembled = assembled();
        Path repo = Files.createDirectory(scratch.resolve("repo"));
        Path maven = Files.createDirectories(scratch.resolve("maven/bin"));
        Files.writeString(maven.resolve("mvn"), "test-only");
        List<List<String>> calls = new ArrayList<>();
        var validator = new BackendFinalPackageValidator("sha256:" + "a".repeat(64), repo,
                maven.getParent(), (command, timeout) -> {
                    calls.add(command);
                    return calls.size() == 1 ? BackendFinalPackageValidator.Exit.FAILURE
                            : BackendFinalPackageValidator.Exit.SUCCESS;
                });
        var failure = assertThrows(BackendFinalPackageValidator.Failed.class, () -> validator.validate(assembled));
        assertEquals(BackendFinalPackageValidator.Failure.BUILD, failure.reason());
        assertEquals(2, calls.size());
    }

    @Test void selectedPackageBuildsInPinnedOfflineDockerWhenOptedIn() throws Exception {
        String image = System.getProperty("gsuif.test.backend-build-image-id");
        String home = System.getProperty("gsuif.test.maven-home");
        String repository = System.getProperty("maven.repo.local");
        Assumptions.assumeTrue(image != null && home != null && repository != null,
                "Pinned image, Maven distribution and cache must be supplied explicitly");
        new BackendFinalPackageValidator(image, Path.of(repository), Path.of(home)).validate(assembled());
    }

    private CompleteBackendPackageAssembler.Assembled assembled() throws Exception {
        var fixture = new CompleteOutputTestFixture();
        var descriptor = fixture.preflight().describe(fixture.version(fixture.snapshot("condition")),
                fixture.specification(), OutputMode.BACKEND);
        var plan = GenerationFilePlan.forDescriptor(descriptor);
        var runtime = new CompleteBackendRuntimeRenderer(
                new PackagedContractRegistry("/generation/stock-unit-contracts.yaml"));
        var input = BackendModelInput.from(descriptor, plan,
                new CompleteBackendDefinitionRenderer().render(descriptor, plan), runtime);
        String source;
        try (var resource = getClass().getResourceAsStream("/generation/fixtures/StockUnitServiceImpl.java.txt")) {
            source = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
        var response = new ObjectMapper().createObjectNode();
        response.putArray("files").addObject().put("path", input.writablePath()).put("content", source);
        var accepted = new BackendModelOutputValidator((selected, bytes) -> true)
                .validate(input, new ObjectMapper().writeValueAsBytes(response));
        return new CompleteBackendPackageAssembler(runtime).assemble(input, accepted,
                new CompleteBackendPackageAssembler.Provenance("mock-provider", "test-1", "mock-model", true));
    }
}
