package eg.mts.gsuif.generation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConsumerBuildValidatorTest {
    @TempDir Path temporaryDirectory;

    @Test void keepsLateCompilerErrorAndTruncationNotice() {
        var result = new ConsumerBuildValidator.CommandResult(1,
                "Maven startup\n" + "x".repeat(ConsumerBuildValidator.OUTPUT_LIMIT) + "\nLATE COMPILER ERROR");
        assertTrue(result.output().startsWith("Maven startup"));
        assertTrue(result.output().contains("LATE COMPILER ERROR"));
        assertTrue(result.output().contains("[build output truncated]"));
        assertTrue(result.output().length() <= ConsumerBuildValidator.OUTPUT_LIMIT);
    }

    @Test void keepsTimeoutNoticeAfterVerboseOutput() {
        var result = new ConsumerBuildValidator.CommandResult(null,
                "x".repeat(ConsumerBuildValidator.OUTPUT_LIMIT) + "\nMaven test timed out");
        assertTrue(result.output().contains("Maven test timed out"));
        assertTrue(result.output().contains("[build output truncated]"));
        assertTrue(result.output().length() <= ConsumerBuildValidator.OUTPUT_LIMIT);
    }

    @Test void streamedOutputKeepsLateErrorAndTimeoutNotice() {
        var capture = new ConsumerBuildValidator.OutputCapture();
        char[] verbose = "x".repeat(ConsumerBuildValidator.OUTPUT_LIMIT + 3000).toCharArray();
        capture.append(verbose, verbose.length);
        capture.append("\nLATE COMPILER ERROR");
        String output = capture.resultWithSuffix("\nMaven compile timed out");
        assertTrue(output.contains("[build output truncated]"));
        assertTrue(output.contains("LATE COMPILER ERROR"));
        assertTrue(output.contains("Maven compile timed out"));
        assertTrue(output.length() <= ConsumerBuildValidator.OUTPUT_LIMIT);
    }

    @Test void streamedOutputRemainsBoundedAfterSmallLateChunk() {
        var capture = new ConsumerBuildValidator.OutputCapture();
        char[] verbose = "x".repeat(ConsumerBuildValidator.OUTPUT_LIMIT + 1).toCharArray();
        capture.append(verbose, verbose.length);
        capture.append("late error");
        String output = capture.result();
        assertTrue(output.contains("[build output truncated]"));
        assertTrue(output.endsWith("late error"));
        assertEquals(ConsumerBuildValidator.OUTPUT_LIMIT, output.length());
    }

    @Test void emptyGeneratedArtifactsCannotReportSuccess() {
        var validator = new ConsumerBuildValidator(temporaryDirectory, (project, goal) ->
                new ConsumerBuildValidator.CommandResult(0, "BUILD SUCCESS"));
        var empty = new GenerationResult(List.of(), List.of(), ConsumerBuildContract.phaseOne());
        var build = validator.validate(empty, Map.of());
        assertFalse(build.passed());
        assertTrue(build.compile().output().contains("Generated Java sources are missing"));
    }

    @Test void rejectsBuildControlAndPrebuiltInputsBeforeRunningMaven() {
        for (String path : List.of(".mvn/maven.config", "target/classes/example/Generated.class",
                "src/main/resources/example/Generated.class", "src/test/resources/library.jar")) {
            var calls = new AtomicInteger();
            var validator = new ConsumerBuildValidator(temporaryDirectory, (project, goal) -> {
                calls.incrementAndGet();
                return new ConsumerBuildValidator.CommandResult(0, "BUILD SUCCESS");
            });
            var build = validator.validate(javaResult(), Map.of(path, bytes("-Dmaven.main.skip=true")));
            assertFalse(build.passed(), path);
            assertNull(build.compile().exitCode(), path);
            assertTrue(build.compile().output().contains("Unsupported consumer input path"), build.compile().output());
            assertEquals(0, calls.get(), path);
        }
    }

    @Test void rejectsBuildControlArtifactAndKeepsAngularArtifactOutOfMaven() {
        var calls = new AtomicInteger();
        var validator = new ConsumerBuildValidator(temporaryDirectory, (project, goal) -> {
            calls.incrementAndGet();
            assertFalse(Files.exists(project.resolve("src/app/generated/widget.component.ts")));
            assertTrue(Files.exists(project.resolve("src/main/java/example/Generated.java")));
            assertTrue(Files.exists(project.resolve("src/test/java/example/GeneratedTest.java")));
            assertTrue(Files.exists(project.resolve("src/main/resources/application.properties")));
            assertTrue(Files.exists(project.resolve("src/test/resources/sample.txt")));
            return new ConsumerBuildValidator.CommandResult(0, "BUILD SUCCESS");
        });
        var mixed = new GenerationResult(List.of(javaArtifact(), artifact("src/app/generated/widget.component.ts")),
                List.of(), ConsumerBuildContract.phaseOne());
        var inputs = Map.of(
                "src/test/java/example/GeneratedTest.java", bytes("package example; class GeneratedTest {}"),
                "src/main/resources/application.properties", bytes("app.name=consumer"),
                "src/test/resources/sample.txt", bytes("sample"));
        assertTrue(validator.validate(mixed, inputs).passed());
        assertEquals(2, calls.get());

        var controlled = new GenerationResult(List.of(javaArtifact(), artifact(".mvn/maven.config")),
                List.of(), ConsumerBuildContract.phaseOne());
        var rejected = validator.validate(controlled, Map.of());
        assertFalse(rejected.passed());
        assertTrue(rejected.compile().output().contains("Unsupported consumer input path"));
        assertEquals(2, calls.get());
    }

    private GenerationResult javaResult() {
        return new GenerationResult(List.of(javaArtifact()), List.of(), ConsumerBuildContract.phaseOne());
    }

    private GenerationResult.Artifact javaArtifact() {
        return artifact("src/main/java/example/Generated.java");
    }

    private GenerationResult.Artifact artifact(String path) {
        return new GenerationResult.Artifact(path, bytes("data"), "hash", "1.0.0", "1.0.0");
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
